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
            [sandbar.db.rules        :refer [all-rules]]
            [datomic.api             :as d]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 5 — structured composition via :where Datalog clauses
;;
;; The :where opt accepts a vector of Datalog clauses that must
;; reference ?e as the entity variable.  Search results are filtered
;; to entities whose eid is in the Datalog result set.  Composes
;; fulltext relevance with structural predicates at substrate level.
;;
;; Per fulltext arc plan §9.2 + §13 Stage 5.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- valid-where-shape?
  "True if `where-clauses` is a vector-of-clause-vectors —
   `[[?e :slot value] [?e :other-slot ?bound] ...]`.

   Phase U Stage U-9 (UR-9) input-shape guard: `where-matching-eids`
   was failing with opaque Datalog stack traces when given malformed
   shapes (e.g., a flat single clause `[?e :slot value]` instead of
   `[[?e :slot value]]`).  This predicate codifies the accepted
   contract; the `:pre` on `where-matching-eids` enforces it at the
   boundary."
  [where-clauses]
  (and (sequential? where-clauses)
       (every? sequential? where-clauses)))

(defn- where-matching-eids
  "Run a Datalog query restricting to instances of `class` AND the
  user-supplied `:where` clauses.  Returns a set of matching eids.

  `where-clauses` MUST be a vector-of-clause-vectors:
  `[[?e :slot value] [?e :other-slot ?bound] ...]` — each clause is a
  sequential `[entity-var attribute value-or-var]` triple.  Each
  clause must reference `?e` as the entity variable for the join with
  the class instance-of constraint to take effect.

  Acceptable inputs (per the `valid-where-shape?` contract):
  - `[]`                                 — empty vec; no restriction
  - `[[?e :mm.memory/scope :global]]`    — single clause
  - `[[?e :slot v] [?e :other-slot v2]]` — multiple clauses

  REJECTED at the boundary (`:pre` throws AssertionError):
  - `'[?e :slot value]`     — flat single clause; missing outer vec
  - `'[:slot value]`        — bare keyword-slot; no entity-var
  - non-sequential          — string, number, map

  The query splices user clauses onto a base query that constrains
  `?e` to be a direct instance of `class`.  Uses `instance-of`
  recursive rule for subclass coverage.

  Phase U Stage U-9 (UR-9): the boundary `:pre` replaces an opaque
  deep-Datalog failure with a structured AssertionError carrying
  the malformed-input shape."
  [class where-clauses]
  {:pre [(valid-where-shape? where-clauses)]}
  (let [base-query   '[:find ?e
                       :in $ % ?class
                       :where (instance-of ?class ?e)]
        merged-query (apply conj base-query where-clauses)]
    (set
      (map first
           (d/q merged-query
                (db/db)
                (all-rules)
                class)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 7 — facets (count-by-slot-value over fulltext hit-set)
;;
;; Per fulltext arc plan §13 Stage 7.  Facet counts produced over the
;; FULL match-set (before limit) so consumers see corpus-wide
;; distribution across facet axes, not just the top-N truncated view.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- facet-counts
  "For each slot in `facet-by`, group hits by the slot value and count.
  Returns `{facet-slot {value count}}` map.  Hits with nil/missing slot
  values are skipped (do not appear in any facet bucket)."
  [scored facet-by]
  (into {}
        (for [slot facet-by]
          [slot (->> scored
                     (keep #(get-in % [:entity slot]))
                     frequencies)])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 6 — snippets + highlights (regex-based; approximate)
;;
;; Per fulltext arc plan §8.1 + §13 Stage 6.  Approximate snippet
;; extraction around the first matching term in each slot's raw text,
;; with `**term**` markdown highlighting of all matched-term
;; occurrences within the window.
;;
;; Approximation caveat: matching uses raw-substring (case-insensitive)
;; against the original query string's tokens, not against Porter-
;; stemmed forms.  This means a query for "running" will highlight
;; "running" in the body but NOT "ran" (even though BM25F would have
;; scored both as matching via the Porter pipeline).  Lucene-native
;; positional highlighters address this; deferred to β-scope per
;; §17 out-of-scope.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +snippet-width+
  "Approximate width of generated snippets in characters (window centers
  on first match; expands halfwidth on each side)."
  240)

(defn- raw-query-words
  "Split raw query string into lowercase non-empty word tokens for
  substring matching.  Splits on the same regex the analyzer tokenizer
  uses (`[^\\p{L}\\p{N}]+`) but does NOT stem — substring matching
  needs the raw form so it can match against raw slot text."
  [query]
  (->> (clojure.string/split (clojure.string/lower-case query)
                             #"[^\p{L}\p{N}]+")
       (remove empty?)
       distinct
       vec))

(defn- snippet-for-slot
  "Extract a ~snippet-width-char window around the first occurrence of
  any query-word in `text`.  Returns nil when text is nil/blank OR
  contains no matching word.  Highlights each matched word with
  `**word**` markdown."
  [text query-words]
  (when (and (string? text) (not (clojure.string/blank? text)) (seq query-words))
    (let [lower (clojure.string/lower-case text)
          ;; For each word: position of first occurrence (or nil)
          positions (keep (fn [w]
                            (when-let [idx (clojure.string/index-of lower w)]
                              [w idx]))
                          query-words)]
      (when (seq positions)
        (let [[_ first-pos] (apply min-key second positions)
              half          (quot +snippet-width+ 2)
              start         (max 0 (- first-pos half))
              end           (min (count text) (+ first-pos half))
              window        (subs text start end)
              prefix        (if (zero? start) "" "...")
              suffix        (if (= end (count text)) "" "...")
              ;; Highlight each query-word within the window (case-insensitive)
              highlighted   (reduce
                              (fn [acc word]
                                (clojure.string/replace
                                  acc
                                  (re-pattern (str "(?i)"
                                                   (java.util.regex.Pattern/quote word)))
                                  #(str "**" % "**")))
                              window
                              query-words)]
          (str prefix highlighted suffix))))))

(defn- per-slot-snippets
  "For each slot in field-weights, generate a snippet (or nil if no
  match).  Returns a map of {slot-ident snippet-string} pruned of nil
  entries — only slots with matches appear."
  [entity-map query-words field-weights]
  (into {}
        (keep (fn [[slot _weight]]
                (let [v (get entity-map slot)
                      text (cond
                             (string? v)     v
                             (sequential? v) (clojure.string/join " " (filter string? v))
                             :else           nil)]
                  (when-let [snippet (snippet-for-slot text query-words)]
                    [slot snippet]))))
        field-weights))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; BM25F analyzed-entry + corpus-stats cache (Stage 5 D5 resolution)
;;
;; Per-class, per-entity cache of analyzed entries + the derived corpus
;; stats.  Re-tokenizing 1500+ entities per query costs ~5.7s (per the
;; corpus parity probe at
;; observations/sandbar_bm25f_parity_probe_5_divergences_2026_05_22.md).
;; Cache hit drops latency to <100ms (score loop dominates).
;;
;; Invalidation strategy: per-entity, hook-driven.  basis-t advances on
;; EVERY MCP request (audit log + token-last-used + session bookkeeping
;; transact even when :mm/Memory entities are untouched), so basis-t-
;; keyed invalidation produces 0% hit rate.  Instead, mutators of class
;; instances call `entity-changed!` / `entity-removed!` post-transact:
;;
;;   - sandbar.mcp.tools/entity-create-handler — fires entity-changed!
;;     after dt/make on the new entity
;;   - sandbar.mcp.tools/entity-update-handler — fires entity-changed!
;;     after dt/update-entity! on the updated entity
;;   - test fixtures — clear via clear-bm25f-cache! (see search_test fixture)
;;
;; Per-class because each :mm/* class has its own :dt/bm25f-weights.
;; Per-entity because file-level edits (one :mm/Memory per file in the
;; canonical FS shape) map to one entity update; only that entity's
;; analyzed-entry needs recomputation.
;;
;; Sub-entity walk: :mm/Memory's BM25F weights cover only :mm.memory/name
;; + :mm.memory/description + :mm.memory/body-raw.  Sections are a
;; separate :mm/Section class with its own tokenization; section updates
;; invalidate the :mm/Section cache but NOT :mm/Memory (sections aren't
;; in :mm/Memory's weights).  File-edit semantics are preserved because
;; the codec updates body-raw on the parent :mm/Memory simultaneously
;; with section recreation; both invalidations fire naturally.
;;
;; Cold-warm: `warm-bm25f-cache!` populates the cache for a class at
;; startup (called from sandbar.core/start).  First-query post-restart
;; is therefore <100ms instead of 5.7s.
;;
;; Per Stage 5 D5 resolution of the sandbar 0.1.1 co-evolution arc:
;; plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private bm25f-entry-cache
  ;; {class-ident {eid analyzed-entry}}
  (atom {}))

(defonce ^:private bm25f-stats-cache
  ;; {class-ident corpus-stats}  — dropped on any mutation; lazy-rebuilt
  ;; from bm25f-entry-cache values on next query.
  (atom {}))

(defn clear-bm25f-cache!
  "Clear the BM25F cache.  No-arg form clears every class; the 1-arg form
  clears just the named class.  Returns the prior cache values.

  Callers:
   - test fixtures (per-test isolation)
   - sandbar.project.import (post-bulk-import; cheaper than per-entity
     re-analyze for large imports)
   - any mutator path that bypasses `entity-changed!`"
  ([]
   (let [prior-entries @bm25f-entry-cache
         prior-stats   @bm25f-stats-cache]
     (reset! bm25f-entry-cache {})
     (reset! bm25f-stats-cache {})
     {:entries prior-entries :stats prior-stats}))
  ([class]
   (swap! bm25f-entry-cache dissoc class)
   (swap! bm25f-stats-cache dissoc class)))

(defn entity-changed!
  "Cache hook for post-mutation invalidation/refresh.  Called by mutators
  of `:mm/*` class instances (sandbar.entity.create + .update via the MCP
  layer) after a successful transact.  `entity-map` is the new (post-
  update) entity-map; `class` is its class ident.

  Behavior:
   - Re-tokenizes the entity (single bm25f/analyze-entity call; ~4ms)
   - Stores the new analyzed-entry under [class eid]
   - Drops the cached corpus-stats for `class` so the next query
     rebuilds them from the updated entry set

  Skipped (no-op) when the class has no `:dt/bm25f-weights` declaration
  — the class isn't BM25F-searchable, so cache work is irrelevant.

  Idempotent: safe to call multiple times for the same entity."
  [class entity-map]
  (when (seq (dt/bm25f-weights-of class))
    (let [eid (:db/id entity-map)]
      (when eid
        (let [analyzed (bm25f/analyze-entity class entity-map)]
          (swap! bm25f-entry-cache assoc-in [class eid] analyzed)
          (swap! bm25f-stats-cache dissoc class))))))

(defn entity-removed!
  "Cache hook for post-delete invalidation.  Called by mutators when an
  entity is retracted/deleted.  Drops the entry from the cache + drops
  stats for the class.  Idempotent."
  [class eid]
  (when (seq (dt/bm25f-weights-of class))
    (swap! bm25f-entry-cache update class dissoc eid)
    (swap! bm25f-stats-cache dissoc class)))

(defn warm-bm25f-cache!
  "Cold-warm the BM25F cache for `class` by walking all current instances,
  tokenizing each, and storing the per-entity analyzed-entries + the
  derived corpus-stats.  Called from sandbar.core/start at JVM startup
  so the first query post-restart hits <100ms instead of cold-tokenizing.

  Returns a map describing the warm:
    {:class C :count N :ms <timing>}"
  [class]
  (let [t0       (System/currentTimeMillis)
        entities (dt/all-instances-of class)
        analyzed (mapv #(bm25f/analyze-entity class %) entities)
        entries  (into {} (map (fn [ae] [(:eid ae) ae])) analyzed)
        stats    (bm25f/corpus-stats analyzed)
        t1       (System/currentTimeMillis)]
    (swap! bm25f-entry-cache assoc class entries)
    (swap! bm25f-stats-cache assoc class stats)
    {:class class
     :count (count entries)
     :ms    (- t1 t0)}))

(defn- analyzed-corpus-for
  "Get `[analyzed-corpus stats]` for `class`.  Cache lookup; lazy-builds
  the per-entity cache on miss via `warm-bm25f-cache!`.  Stats are
  lazy-derived from the per-entity cache when missing (post-mutation
  invalidation drops stats but preserves entries; next query rebuilds
  stats from the cached entries — fast).

  Returns:
    [analyzed-corpus stats]
  where analyzed-corpus is a vector of analyzed-entries for scoring
  (order doesn't matter — search-bm25f sorts by score post-scoring).

  Performance:
   - cache hit (entries + stats both cached): O(N) walk of vals to build
     the analyzed-corpus vector; ~5ms at N=1500
   - stats-miss (post-mutation; entries cached): + bm25f/corpus-stats
     recompute over the cached values; ~200ms at N=1500
   - full-miss (cold; first query post-restart if not warmed): full
     corpus walk + tokenize + stats; ~5.7s at N=1500"
  [class]
  (let [entries (or (get @bm25f-entry-cache class)
                    (do (warm-bm25f-cache! class)
                        (get @bm25f-entry-cache class)))
        stats   (or (get @bm25f-stats-cache class)
                    (let [s (bm25f/corpus-stats (vals entries))]
                      (swap! bm25f-stats-cache assoc class s)
                      s))]
    [(vec (vals entries)) stats]))

(defn search-bm25f
  "Multi-field BM25F search over Datomic-stored entities of `:class`.

  Pipeline tokenizes the query, walks all instances of the class, analyzes
  + stats + scores each via the ported Robertson-Zaragoza canonical
  `sandbar.search.bm25f`, and projects results into the canonical
  `{:hits :total :returned :timing}` shape.

  ## Query language contract (narrow scope; F-SF-1 fix, Phase R Stage R-5)

  `:query` is a bag-of-words string.  It is tokenized via
  `sandbar.search.analysis` (Porter stemmer + lowercase + word-boundary
  split) and scored token-by-token across the entity's BM25F-weighted
  slots.  Boolean operators (`AND` / `OR` / `NOT`), phrase quoting
  (`\"exact phrase\"`), wildcards (`term*`), fuzzy matching (`term~`),
  field-prefixes (`field:value`) and other Lucene-query-parser shapes
  are NOT recognized — they tokenize as literal terms (e.g., the query
  `\"AND\"` matches the literal stem `\"and\"` wherever it appears).

  For Lucene-query-syntax support, use `search-attribute` instead — it
  hits Datomic's `:db.fn/fulltext-search` over a single :db/fulltext-
  indexed slot.  The BM25F surface deliberately stays narrow so
  multi-field length-normalized scoring (Robertson & Zaragoza 2009 §3.4)
  is the only concern.

  Required opts:
    :query  — query string (bag-of-words; NO Lucene query-language
              parsing — see contract above)
    :class  — class ident (e.g. `:mm/Memory`) whose `:dt/bm25f-weights`
              declaration drives field selection + per-slot weighting

  Optional opts:
    :field-weights — `{slot-ident weight-double}` map overriding the
                     class's declared `:dt/bm25f-weights`
    :limit         — max hits to return (default 20; 0 = no cap)
    :where         — vec of Datalog clauses (Stage 5) restricting hits to
                     entities matching the predicate.  Clauses must
                     reference `?e` as the entity variable.  Composes
                     fulltext relevance with structural filtering.
                     Example: `[[?e :mm.memory/memory-type :decision]]`
    :facet-by      — vec of slot-idents (Stage 7) to facet over.  Adds
                     `:facets {slot-ident {value count}}` to result.
                     Counts computed over the FULL match-set (before
                     limit), so consumers see corpus-wide distribution.
                     Example: `[:mm.memory/memory-type :mm.memory/scope]`
    :include       — vec of result-projection options:
                       :field-scores  per-slot single-slot-equivalent scores
                       :snippets      per-slot ~240-char window around the
                                      first matched term in each slot,
                                      with `**term**` markdown highlighting
                                      of all matched-term occurrences

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
  [{:keys [query class field-weights limit where facet-by include]
    :or   {limit 20 include []}}]
  {:pre [(string? query)
         (keyword? class)
         (integer? limit)
         (>= limit 0)
         (or (nil? where) (sequential? where))
         (or (nil? facet-by) (sequential? facet-by))]}
  (let [t-start         (System/currentTimeMillis)
        weights         (or field-weights (dt/bm25f-weights-of class))
        _               (when (empty? weights)
                          (throw (ex-info "No :dt/bm25f-weights declared on class; supply :field-weights opt"
                                          {:class         class
                                           :field-weights field-weights})))
        q-tokens        (analysis/tokenize query)
        ;; Stage 5 D5 — cached per (class, basis-t); see analyzed-corpus-for
        ;; above.  Cache miss on first-after-tx: full ~5.7s corpus rebuild.
        ;; Cache hit (overwhelmingly common): <100ms; score loop dominates.
        [analyzed-corpus stats] (analyzed-corpus-for class)
        ;; Stage 5: structured composition via :where Datalog clauses.
        ;; Filter scoring-set to entities matching the predicate before
        ;; the scoring pass.  Stats are still computed over the FULL
        ;; class corpus (correct IDF + avgdl); the :where filter only
        ;; restricts which entities are scored + returned.
        where-eids      (when (seq where)
                          (where-matching-eids class where))
        scoring-corpus  (if where-eids
                          (filter #(contains? where-eids (:eid %)) analyzed-corpus)
                          analyzed-corpus)
        scored          (for [ae    scoring-corpus
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
        q-raw-words     (when (include-set :snippets) (raw-query-words query))
        hits            (mapv (fn [{:keys [entity eid score analyzed]}]
                                (cond-> {:entity entity
                                         :eid    eid
                                         :score  score}
                                  (include-set :field-scores)
                                  (assoc :field-scores
                                         (per-slot-field-scores
                                          q-tokens analyzed stats weights))
                                  (include-set :snippets)
                                  (assoc :snippets
                                         (per-slot-snippets
                                          entity q-raw-words weights))))
                              limited)
        t-end           (System/currentTimeMillis)
        result          {:hits     hits
                         :total    total
                         :returned (count hits)
                         :timing   {:total-ms (- t-end t-start)}}]
    (cond-> result
      (seq facet-by) (assoc :facets (facet-counts sorted facet-by)))))
