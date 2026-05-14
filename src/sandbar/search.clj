(ns sandbar.search
  "Sandbar Search Namespace — Phase S of comprehensive memory-model MCP arc

  Consumer-facing wrappers around the dt/* fulltext primitives.  Composes
  per-field BM25 results into BM25F multi-field weighted scoring, projects
  results into the canonical result-shape (`:hits` / `:total` / `:returned`
  / `:timing` / optional `:snippets` / `:facets` / `:field-scores`), and
  handles result-set concerns (limit, sort-order, timing).

  Three layers in this namespace as scoped stages land:
  - search-attribute  (Stage 3 — LANDED) — single-field BM25 search
  - search-bm25f      (Stage 4c — LANDED) — multi-field weighted BM25F
  - search-with-snippets / -facets       (Stages 6-7)

  Higher-level concerns (path-grammar + aggregation + orientation) live
  in sibling namespaces sandbar.{navigate,aggregate,orient}.

  Per fulltext arc plan §1.1, §1.6, §6.5 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [sandbar.db.datatype     :as dt]
            [sandbar.db.datomic      :as db]
            [sandbar.search.analysis :as analysis]
            [sandbar.search.bm25f    :as bm25f]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Result-shape projection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- hit-map
  "Project an [eid score] tuple into the canonical hit-map shape.

  Resolves the entity via dt/* substrate (entity-map view).  Per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md,
  uses the introspectable API layer rather than raw Datomic d/pull."
  [[eid score]]
  {:entity (into {:db/id eid} (db/entity eid))
   :score  score})

(defn- now-ms []
  (System/currentTimeMillis))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; search-attribute — single-field BM25 search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 3 — search-attribute (single-field BM25)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn search-attribute
  "Single-field fulltext search over a `:db/fulltext true` attribute.

  Wraps `dt/search-fulltext` with result-shape projection, limit handling,
  and timing.  Returns a map with `:hits` (ranked descending by Lucene's
  BM25 single-field score), `:total` (total hits before limit), `:returned`
  (count after limit), and `:timing`.

  Required opts:
    :attribute   — slot/property ident with :db/fulltext true
    :query       — Lucene query string (phrase, boolean, wildcard, etc.)

  Optional opts:
    :limit       — max hits to return (default 50; 0 = no limit)

  Returns:
    {:hits     [{:entity <entity-map> :score <double>} ...]
     :total    <integer>
     :returned <integer>
     :timing   {:total-ms <int>}}

  Throws (via precondition) if attribute is not `:db/fulltext true`.

  Per fulltext arc Stage 3 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [{:keys [attribute query limit]
    :or   {limit 50}}]
  {:pre [(keyword? attribute)
         (string? query)
         (integer? limit)
         (>= limit 0)
         (dt/fulltext-indexed? attribute)]}
  (let [t-start    (now-ms)
        raw-hits   (dt/search-fulltext attribute query)
        sorted     (sort-by (fn [[_ score]] (- score)) raw-hits)
        total      (count sorted)
        limited    (if (zero? limit) sorted (take limit sorted))
        hits       (mapv hit-map limited)
        t-end      (now-ms)]
    {:hits     hits
     :total    total
     :returned (count hits)
     :timing   {:total-ms (- t-end t-start)}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 4c — search-bm25f (multi-field weighted; ported BM25F + analyzer
;; + Datomic-backend integration)
;;
;; Pipeline:
;;   1. Tokenize query via sandbar.search.analysis (Porter stemmer)
;;   2. Walk all class instances via dt/all-instances-of (correct IDF + avgdl)
;;   3. Analyze each entity via bm25f/analyze-entity (metamodel-driven slot
;;      extraction reads :dt/bm25f-weights from class)
;;   4. Compute corpus-stats via bm25f/corpus-stats (N + df + avgdl)
;;   5. Score each entity via bm25f/score (Robertson-Zaragoza canonical form)
;;   6. Filter zero-score; sort descending; limit; project to canonical
;;      {:hits :total :returned :timing} result shape
;;   7. Optional :include [:field-scores] adds per-slot single-slot-equivalent
;;      score breakdown to each hit
;;
;; LAYER design (per authorizations/move_bm25f_and_generic_primitives_to_sandbar_
;; 2026_05_13.md §A) intends to leverage Datomic's :db/fulltext index for
;; candidate-pruning before scoring.  Stage 4c ships the substrate-correct
;; full-corpus-walk baseline; LAYER candidate-pruning via Datomic fulltext
;; deferred to Stage 32 / Phase X because Sandbar's Porter-stemmed analyzer
;; differs from Datomic's default StandardAnalyzer (using Datomic candidates
;; without analyzer alignment would create false negatives at query time).
;; Alignment options for Stage 32+: configure Datomic to use an
;; EnglishAnalyzer-style Porter-stemming analyzer; OR add Porter-stemmed
;; index-side analysis under a dedicated Sandbar attribute.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- per-slot-field-scores
  "Compute per-slot single-slot-equivalent score for one analyzed entity.
  For each slot in `field-weights`, re-score with only that slot's weight
  active (others zeroed).  Returns `{slot-ident score}` map.

  NOTE: per-slot scores do NOT sum to the total `score` because BM25F's
  cross-field length-normalized-TF accumulates BEFORE saturation
  (Robertson & Zaragoza 2009 §3.4).  Per-slot scores are a debugging
  surface for relative-contribution intuition, not an additive
  decomposition."
  [query-tokens analyzed-entity stats field-weights]
  (into {}
        (for [[slot weight] field-weights]
          [slot (bm25f/score query-tokens analyzed-entity stats {slot weight})])))

(defn search-bm25f
  "Multi-field BM25F search over Datomic-stored entities of `:class`.

  Pipeline tokenizes the query, walks all instances of the class, analyzes
  + stats + scores each via the ported Robertson-Zaragoza canonical
  `sandbar.search.bm25f`, and projects results into the canonical
  `{:hits :total :returned :timing}` shape.

  Required opts:
    :query  — query string; Lucene syntax accepted (phrase, boolean, etc.)
    :class  — class ident (e.g. `:mm/Memory`) whose `:dt/bm25f-weights`
              declaration drives field selection + per-slot weighting

  Optional opts:
    :field-weights — `{slot-ident weight-double}` map overriding the
                     class's declared `:dt/bm25f-weights`
    :limit         — max hits to return (default 20; 0 = no cap)
    :include       — vec of result-projection options:
                       :field-scores  per-slot single-slot-equivalent scores

  Returns:
    {:hits     [{:entity <entity-map> :eid <id> :score <double>
                 :field-scores {<slot> <double>}?} ...]
     :total    <int>
     :returned <int>
     :timing   {:total-ms <int>}}

  Throws (via precondition) for invalid arg shapes.  Throws via ex-info
  if no `:dt/bm25f-weights` declared on the class AND no `:field-weights`
  supplied (substrate has no default weights per substrate-quality rule).

  Per fulltext arc Stage 4c of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [{:keys [query class field-weights limit include]
    :or   {limit 20 include []}}]
  {:pre [(string? query)
         (keyword? class)
         (integer? limit)
         (>= limit 0)]}
  (let [t-start         (System/currentTimeMillis)
        weights         (or field-weights (dt/bm25f-weights-of class))
        _               (when (empty? weights)
                          (throw (ex-info "No :dt/bm25f-weights declared on class; supply :field-weights opt"
                                          {:class         class
                                           :field-weights field-weights})))
        q-tokens        (analysis/tokenize query)
        all-entities    (dt/all-instances-of class)
        analyzed-corpus (mapv #(bm25f/analyze-entity class %) all-entities)
        stats           (bm25f/corpus-stats analyzed-corpus)
        scored          (for [ae    analyzed-corpus
                              :let  [s (bm25f/score q-tokens ae stats weights)]
                              :when (pos? s)]
                          {:entity (:entity ae)
                           :eid    (:eid ae)
                           :score  s
                           :analyzed ae})
        sorted          (sort-by :score > scored)
        total           (count sorted)
        limited         (if (zero? limit) sorted (take limit sorted))
        include-set     (set include)
        hits            (mapv (fn [{:keys [entity eid score analyzed]}]
                                (cond-> {:entity entity
                                         :eid    eid
                                         :score  score}
                                  (include-set :field-scores)
                                  (assoc :field-scores
                                         (per-slot-field-scores
                                          q-tokens analyzed stats weights))))
                              limited)
        t-end           (System/currentTimeMillis)]
    {:hits     hits
     :total    total
     :returned (count hits)
     :timing   {:total-ms (- t-end t-start)}}))
