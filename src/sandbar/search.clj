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
  (:require [clojure.set]
            [clojure.tools.logging   :as log]
            [sandbar.api.projection  :as projection]
            [sandbar.db.datatype     :as dt]
            [sandbar.db.datomic      :as db]
            [sandbar.db.rules        :refer [all-rules]]
            [datomic.api             :as d]
            [sandbar.search.analysis :as analysis]
            [sandbar.search.bm25f    :as bm25f]
            [sandbar.navigate.path   :as path]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Result-shape projection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- hit-map
  "Project an [eid score] tuple into the canonical hit-map shape.

  Resolves the entity via dt/* substrate, then applies `projection-mode`
  (`:full` / `:metadata-only`) via `sandbar.api.projection/apply-projection`.
  When `projection-mode` is nil, apply-projection defaults to `:full`
  (legacy substrate-fn contract; MCP handlers explicitly opt to
  :metadata-only at the MCP boundary).  Per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md
  + B.3 of substrate-stab arc (:projection opt on bulky-response verbs)."
  [[eid score] projection-mode]
  {:entity (projection/apply-projection (db/entity eid) projection-mode)
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
    :projection  — :metadata-only (default) or :full; controls per-hit
                   :entity shape.  B.3 of substrate-stab arc — exploration
                   verbs default to :metadata-only at substrate boundary
                   for payload safety (10-300x reduction).  Consumers opt
                   to :full when slot bodies needed.

  Returns:
    {:hits     [{:entity <entity-map> :score <double>} ...]
     :total    <integer>
     :returned <integer>
     :timing   {:total-ms <int>}}

  Throws (via precondition) if attribute is not `:db/fulltext true`.

  Per fulltext arc Stage 3 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [{:keys [attribute query limit projection]
    :or   {limit 50}}]
  {:pre [(keyword? attribute)
         (string? query)
         (integer? limit)
         (>= limit 0)
         (or (nil? projection) (#{:full :metadata-only} projection))
         (dt/fulltext-indexed? attribute)]}
  (let [t-start    (now-ms)
        raw-hits   (dt/search-fulltext attribute query)
        sorted     (sort-by (fn [[_ score]] (- score)) raw-hits)
        total      (count sorted)
        limited    (if (zero? limit) sorted (take limit sorted))
        hits       (mapv #(hit-map % projection) limited)
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
;;   - sandbar.mcp.tools/entity-create-handler — enqueues entity-changed!
;;     (via entity-changed-async!) after dt/make on the new entity
;;   - sandbar.mcp.tools/entity-update-handler — enqueues entity-changed!
;;     (via entity-changed-async!) after dt/update-entity! on the updated
;;     entity
;;   - test fixtures — clear via clear-bm25f-cache! (see search_test fixture)
;;
;; Since 2026-07-07 (arc/bm25f-async-index) the MCP write path enqueues
;; the refresh onto a dedicated single-thread executor instead of running
;; it inline — entity-changed! is ~144ms for a 6.5KB body (tokenize +
;; ref-resolution dominates) and was the dominant per-write cost, causing
;; MCP-client response timeouts on larger writes.  The index is therefore
;; EVENTUALLY-CONSISTENT after writes; readers that need read-your-writes
;; call `await-bm25f-quiescent!` (the MCP search.bm25f + tag.lookup
;; handlers do).
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

(defn- dependent-classes-on
  "Returns the set of class-idents whose `:dt/bm25f-weights` includes a
  ref-slot whose `:dt/range` is `target-class`.  Used for transitive
  cache invalidation: when an entity of `target-class` changes, every
  dependent class's entries that REFERENCE the changed entity have
  stale analyzed-text (since the tag-content tokenizer in
  `sandbar.search.bm25f/ref-target-text` resolved the ref at analyze
  time) and must re-tokenize.

  Substrate-quality: metamodel-driven enumeration via
  `dt/all-classes` + `dt/effective-bm25f-weights-of` + `dt/range-of`.  No
  hardcoded consumer-class knowledge."
  [target-class]
  (into #{}
        (filter (fn [class-ident]
                  (some (fn [[slot _w]]
                          (= target-class (dt/range-of slot)))
                        (dt/effective-bm25f-weights-of class-ident))))
        (dt/all-classes)))

(defn- referencing-eids
  "Returns the set of `dependent-class` eids whose entries reference
  `target-eid` via any ref-slot in `dependent-class`'s bm25f-weights
  whose range is `target-class`.  Pure metamodel-driven Datalog walk."
  [dependent-class target-class target-eid]
  (let [db        (db/db)
        ref-slots (->> (dt/effective-bm25f-weights-of dependent-class)
                       (filter (fn [[slot _w]]
                                 (= target-class (dt/range-of slot))))
                       (mapv first))]
    (into #{}
          (mapcat (fn [slot]
                    (d/q '[:find [?e ...]
                           :in $ ?slot ?target
                           :where [?e ?slot ?target]]
                         db slot target-eid)))
          ref-slots)))

(defn entity-changed!
  "Cache hook for post-mutation invalidation/refresh.  Called by mutators
  of `:mm/*` class instances (sandbar.entity.create + .update via the MCP
  layer) after a successful transact.  `entity-map` is the new (post-
  update) entity-map; `class` is its class ident.

  Behavior:
   - Direct: Re-tokenizes the entity (single bm25f/analyze-entity call;
     ~4ms), stores the new analyzed-entry under [class eid], drops the
     cached corpus-stats for `class`.
   - Transitive (Phase B tag-content tokenizer support): For every
     dependent class whose bm25f-weights reference this entity's class
     via a ref-slot, finds entries that reference this entity's eid and
     re-tokenizes them in place (since their analyzed-text included
     this entity's resolved content at analyze time and is now stale).

  Skipped (no-op for direct path) when the class has no
  `:dt/bm25f-weights` declaration.  Transitive path still runs — a
  class without bm25f-weights of its own may still be referenced by
  bm25f-weighted classes via the tag-content tokenizer.

  Idempotent: safe to call multiple times for the same entity."
  [class entity-map]
  (let [eid (:db/id entity-map)]
    ;; Direct cache update (only if the changed class itself is bm25f-weighted)
    (when (and eid (seq (dt/effective-bm25f-weights-of class)))
      (let [analyzed (bm25f/analyze-entity class entity-map)]
        (swap! bm25f-entry-cache assoc-in [class eid] analyzed)
        (swap! bm25f-stats-cache dissoc class)))
    ;; Transitive invalidation — dependent classes that resolve this entity
    ;; via ref-target-text in their tokenization.
    (when eid
      (doseq [dep-class (dependent-classes-on class)]
        (let [stale-eids (referencing-eids dep-class class eid)]
          (when (seq stale-eids)
            (doseq [dep-eid stale-eids]
              (let [dep-entity (db/entity dep-eid)
                    dep-map    (when dep-entity
                                 (into {:db/id dep-eid} dep-entity))]
                (when dep-map
                  (let [analyzed (bm25f/analyze-entity dep-class dep-map)]
                    (swap! bm25f-entry-cache assoc-in [dep-class dep-eid] analyzed)))))
            (swap! bm25f-stats-cache dissoc dep-class)))))))

(defn entity-removed!
  "Cache hook for post-delete invalidation.  Called by mutators when an
  entity is retracted/deleted.  Drops the entry from the cache + drops
  stats for the class.  Idempotent."
  [class eid]
  (when (seq (dt/effective-bm25f-weights-of class))
    (swap! bm25f-entry-cache update class dissoc eid)
    (swap! bm25f-stats-cache dissoc class)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Async BM25F refresh (2026-07-07 — arc/bm25f-async-index)
;;
;; entity-changed! is the dominant per-write cost on the MCP write path
;; (~144ms for a 6.5KB body; scales with body size — it re-tokenizes the
;; full body-raw and resolves ref-typed tag/theme slots).  Running it
;; inline on the write-RESPONSE path caused MCP-client request timeouts
;; on larger writes (the write itself commits sub-ms; only the response
;; stalled).  `entity-changed-async!` moves the refresh onto a dedicated
;; single-thread daemon executor so the write returns immediately.
;;
;; Why a dedicated executor and NOT the reactive-projection queue
;; (sandbar.reactive.queue):
;;   - the reactive queue's sinks fire at the dt/make/update-entity!
;;     substrate boundary for ALL mutation paths with an
;;     [eid post-tx-slots] payload — registering a BM25F sink there is
;;     the larger architectural move (all-mutator index refresh), kept
;;     as a follow-up, not this minimal fix;
;;   - its worker is a core.async go-loop; parking 144ms+ of blocking
;;     tokenization + Datalog ref-resolution inside a go block starves
;;     the fixed core.async dispatch pool;
;;   - its per-entity coalescing drops the entity-map payload that
;;     entity-changed! needs.
;;
;; Guarantees:
;;   - FIFO single-thread ⇒ per-entity refresh order == write order,
;;     and a no-op marker task doubles as a quiescence barrier
;;     (`await-bm25f-quiescent!`).
;;   - Worker errors are LOGGED + counted, never rethrown — the write
;;     already committed; a failed refresh must never surface as a
;;     write error.
;;   - Cross-restart durability is inherent: `warm-bm25f-cache!`
;;     rebuilds the whole cache from the DB at JVM startup, so a
;;     refresh lost to JVM death is repaired at next start.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private bm25f-refresh-executor
  (java.util.concurrent.Executors/newSingleThreadExecutor
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ r]
       (doto (Thread. ^Runnable r "sandbar-bm25f-refresh")
         (.setDaemon true))))))

(defonce bm25f-refresh-stats
  ;; Observability + test surface: cumulative counters since JVM start.
  (atom {:enqueued 0 :completed 0 :failed 0}))

(defn entity-changed-async!
  "Enqueue `(entity-changed! class entity-map)` onto the dedicated
  single-thread BM25F refresh executor and return immediately.

  The refresh runs asynchronously — the BM25F index is EVENTUALLY-
  CONSISTENT after the caller's write.  Callers needing read-your-writes
  (tests; the MCP read handlers) call `await-bm25f-quiescent!` first.

  Semantics preserved from the sync call: the enqueued job is exactly
  `entity-changed!` (still a direct-path no-op when the class has no
  :dt/bm25f-weights; transitive invalidation still runs).  Job failures
  are logged (:SEARCH/bm25f-async-refresh-failed) + counted in
  `bm25f-refresh-stats`, never rethrown — the write already committed.

  Returns the java.util.concurrent.Future for the enqueued job."
  [class entity-map]
  (swap! bm25f-refresh-stats update :enqueued inc)
  (let [^java.util.concurrent.ExecutorService ex bm25f-refresh-executor]
    (.submit ex
             ^Runnable
             (fn []
               (try
                 ;; Late-bound var deref (NOT a captured fn value) so
                 ;; with-redefs in tests governs the worker too.
                 (entity-changed! class entity-map)
                 (swap! bm25f-refresh-stats update :completed inc)
                 (catch Throwable t
                   (swap! bm25f-refresh-stats update :failed inc)
                   (log/warn t :SEARCH/bm25f-async-refresh-failed
                             {:class     class
                              :entity-id (:db/id entity-map)})))))))

(defn await-bm25f-quiescent!
  "Block until every previously-enqueued async BM25F refresh has been
  processed (completed OR failed-and-logged), up to `timeout-ms`
  (default 30000).

  Implementation: submits a no-op marker to the single-thread FIFO
  refresh executor and waits for it — when the marker runs, everything
  enqueued before it has drained.

  Returns true when quiescent within the timeout; false on timeout
  (index may still be catching up — callers should proceed with
  possibly-stale results rather than error).

  This is the sync-flush hook for create-then-search consumers:
  tests, and the MCP search.bm25f / tag.lookup read handlers (which
  call it with a short bounded timeout to preserve read-your-writes
  semantics across the MCP surface)."
  ([] (await-bm25f-quiescent! 30000))
  ([timeout-ms]
   (let [^java.util.concurrent.ExecutorService ex bm25f-refresh-executor
         marker (.submit ex ^Runnable (fn []))]
     (try
       (.get marker timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
       true
       (catch java.util.concurrent.TimeoutException _
         (log/warn :SEARCH/bm25f-quiescence-timeout
                   {:timeout-ms timeout-ms
                    :stats      @bm25f-refresh-stats})
         false)))))

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

(defn- search-bm25f-single
  "Single-class BM25F search over Datomic-stored entities of `:class`.

  This is the substrate-correct single-class scoring pipeline (unchanged
  from HEAD; the public `search-bm25f` below dispatches here for a
  keyword `:class` and fans out to it per-class for a vec `:class`).

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

  Stage 29 cross-axis composition (Phase B):
    :from + :via   — graph-walk pre-filter; restricts candidate set to
                     entities REACHABLE from `:from` under path-grammar
                     expression `:via`.  Reuses `sandbar.navigate.path/
                     path-via` for the walk.  Composes with `:where` —
                     both filters apply (intersection).
    :rank-by       — :degree / :backlink-density / :recency / :freshness
                     — re-rank top-K hits by structural axis instead of
                     by BM25F score.  BM25F score is preserved on each
                     hit as `:relevance-score`; the primary `:score`
                     becomes the structural rank value.
    :temporal-slot — REQUIRED for :rank-by :recency / :freshness.

  Per fulltext arc Stage 4c + Stage 29 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [{:keys [query class field-weights limit where facet-by include
           from via rank-by temporal-slot projection]
    :or   {limit 20 include []}}]
  {:pre [(string? query)
         (keyword? class)
         (integer? limit)
         (>= limit 0)
         (or (nil? where) (sequential? where))
         (or (nil? facet-by) (sequential? facet-by))
         (or (nil? rank-by) (#{:degree :backlink-density :recency :freshness} rank-by))
         (or (not (#{:recency :freshness} rank-by))
             (keyword? temporal-slot))
         (or (and (nil? from) (nil? via))
             (and (some? from) (some? via)))
         (or (nil? projection) (#{:full :metadata-only} projection))]}
  (let [t-start         (System/currentTimeMillis)
        weights         (or field-weights (dt/effective-bm25f-weights-of class))
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
        ;; Stage 29 cross-axis composition — :from + :via graph-walk pre-filter.
        ;; Compute reachable eids; intersect with :where-eids if both supplied.
        from-via-eids   (when (and from via)
                          (let [walk-result (path/path-via {:from from :via via :limit 0})]
                            (into #{}
                                  (keep (fn [e] (or (:db/id e) (:db/id (:entity e)))))
                                  (:reachable walk-result))))
        candidate-eids  (cond
                          (and where-eids from-via-eids)
                          (clojure.set/intersection where-eids from-via-eids)
                          where-eids     where-eids
                          from-via-eids  from-via-eids
                          :else          nil)
        scoring-corpus  (if candidate-eids
                          (filter #(contains? candidate-eids (:eid %)) analyzed-corpus)
                          analyzed-corpus)
        scored          (for [ae    scoring-corpus
                              :let  [s (bm25f/score q-tokens ae stats weights)]
                              :when (pos? s)]
                          {:entity (:entity ae)
                           :eid    (:eid ae)
                           :score  s
                           :analyzed ae})
        ;; Stage 29 — :rank-by re-rank composition.  After BM25F filter,
        ;; re-order surviving hits by structural axis (degree / backlink-
        ;; density / recency / freshness).  BM25F score preserved as
        ;; :relevance-score; structural value becomes :score.
        sorted          (if rank-by
                          (let [scored-with-rank
                                (mapv (fn [{:keys [entity score] :as h}]
                                        (let [eid-or-ident (or (:db/ident entity)
                                                               (:db/id entity))
                                              rank-val (case rank-by
                                                         :degree           (dt/degree-of eid-or-ident)
                                                         :backlink-density (dt/backlink-density-of eid-or-ident)
                                                         :recency          (get entity temporal-slot)
                                                         :freshness        (get entity temporal-slot))]
                                          (assoc h
                                                 :relevance-score score
                                                 :rank-score      rank-val)))
                                      scored)
                                cmp (if (= rank-by :freshness)
                                      compare
                                      #(compare %2 %1))]
                            (sort-by :rank-score cmp scored-with-rank))
                          (sort-by :score > scored))
        total           (count sorted)
        limited         (if (zero? limit) sorted (take limit sorted))
        include-set     (set include)
        q-raw-words     (when (include-set :snippets) (raw-query-words query))
        hits            (mapv (fn [{:keys [entity eid score analyzed relevance-score rank-score]}]
                                (cond-> {:entity (projection/apply-projection entity projection)
                                         :eid    eid
                                         :score  (if rank-by (or rank-score score) score)}
                                  rank-by
                                  (assoc :relevance-score (or relevance-score score))
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; D7 — multi-class :class vec (strategic-subgroup scope; C12 gap closure)
;;
;; Per D7 of decisions/c8_ratification_batch_d1_d9_plus_defaults_all_approved_
;; 2026_07_02 (Dan-ratified): the retrieval discipline's DEFAULT tier
;; (strategic-subgroup scope per interaction/scope_vs_global_retrieval_
;; discipline.md) had no single-call MCP surface because `:class` was
;; single-valued.  The ratified fix: `:class` accepts a VEC of class idents
;; (single ident stays supported, byte-for-byte backward-compatible).
;;
;; v1 merge semantics — transparent and simple: query each class with its
;; OWN declared `:dt/bm25f-weights` (per-class field weighting is the
;; design center of BM25F here — a Tag's :mm.tag/value weight ≠ a Memory's
;; :mm.memory/name weight), then merge hit lists and sort by RAW score
;; descending, applying `:limit` AFTER the merge.
;;
;; HONEST CAVEAT (documented in the MCP card + here): cross-class raw-score
;; comparability is APPROXIMATE.  Each class computes its own IDF (df/N over
;; that class's corpus) and length-normalization (per-class avgdl), so raw
;; BM25F scores across classes are not on a unified scale.  A deeper
;; score-unification (e.g. per-class score standardization, or a shared
;; corpus-stats model) is explicitly OUT of v1 — noted as a future
;; refinement, not a silent limitation.
;;
;; Cache: NO new cache layer.  A multi-class call hits N per-class BM25F
;; caches exactly as N independent single-class calls would (the
;; per-class analyzed-corpus + stats caches keyed by class-ident; see the
;; cache section above).  Invalidation is unchanged (hook-driven per-entity
;; per interaction/bm25f_cache_invalidation_must_support_normal_mm_memory_
;; updates).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +multiclass-cap+
  "Upper bound on the number of classes in a multi-class `:class` vec.
  A strategic subgroup is a curated handful, not the whole lattice
  (per D7 — vec is 2..8).  Over-cap raises a loud error."
  8)

(defn- multiclass?
  "True when `class` is a sequential of class idents (the multi-class
  vec form), false when it is a single class-ident keyword."
  [class]
  (sequential? class))

(defn- validate-multiclass-vec!
  "Loud-error boundary for the multi-class `:class` vec form.  Throws
  ex-info when: the vec is empty, exceeds `+multiclass-cap+`, or contains
  a non-keyword member.  Per D7 decision-token 1 (2..8; empty vec → loud
  error) + HARD-CONSTRAINT loud-error semantics."
  [classes]
  (when (empty? classes)
    (throw (ex-info "Multi-class :class vec must be non-empty (2..8 class idents)"
                    {:class classes :count 0})))
  (when (> (count classes) +multiclass-cap+)
    (throw (ex-info (str "Multi-class :class vec exceeds cap of " +multiclass-cap+
                         " (a strategic subgroup is a curated handful, not the whole lattice)")
                    {:class classes :count (count classes) :cap +multiclass-cap+})))
  (when-not (every? keyword? classes)
    (throw (ex-info "Every member of a multi-class :class vec must be a class-ident keyword"
                    {:class classes
                     :non-keyword-members (vec (remove keyword? classes))})))
  ;; A single-element vec is a degenerate subgroup; require >= 2 so the
  ;; single-ident form stays the canonical single-class surface (D7
  ;; decision-token 1 declares the vec range as 2..8).
  (when (< (count classes) 2)
    (throw (ex-info "Multi-class :class vec must contain at least 2 class idents; use a bare ident for single-class search"
                    {:class classes :count (count classes)}))))

(defn- search-bm25f-multi
  "Multi-class BM25F search — the D7 strategic-subgroup fan-out.

  Queries each class in `classes` via `search-bm25f-single` with its OWN
  declared `:dt/bm25f-weights` and `:limit 0` (no per-class cap — the cap
  applies AFTER the merge), then merges the per-class hit lists, sorts by
  raw score descending, and applies `:limit` post-merge.

  Compositions:
   - `:where`     applies per-class (same `?e` contract; each class's
                  single-class call runs the Datalog filter against its
                  own instance set).
   - `:from`/`:via` graph-walk pre-filter applies per-class (each single
                  call intersects the walk-reachable set with its own
                  candidate set — path-via is class-agnostic so the same
                  reachable eids intersect correctly per class).
   - `:rank-by`   applies per-class BEFORE the merge (structural re-rank
                  is a post-scoring re-order; each class re-ranks its own
                  survivors, then the merged list sorts by the resulting
                  `:score`).  Documented in NOTES as a v1 approximation —
                  cross-class structural ranking on a unified axis is a
                  future refinement.
   - `:facet-by`  facets over the MERGED full match-set (computed here,
                  post-merge, so counts span all classes).
   - `:field-weights` is REJECTED with a vec (loud error) — per-class
                  overrides are out of v1 (D7 decision-token 4).

  Per-hit class visibility: each hit's `:entity` carries `:dt/type` even
  in metadata-only projection (per sandbar.api.projection), so callers can
  tell classes apart in the merged list.  Verified by T2.

  Returns the canonical `{:hits :total :returned :timing}` shape (plus
  `:facets` when `:facet-by` supplied).  `:total` is the merged full-match
  count across all classes; `:returned` is post-limit."
  [{:keys [class limit facet-by field-weights] :or {limit 20} :as opts}]
  (validate-multiclass-vec! class)
  (when field-weights
    (throw (ex-info ":field-weights override is not supported with a multi-class :class vec (per-class overrides are out of v1); supply a single :class for a weights override"
                    {:class class :field-weights field-weights})))
  (let [t-start   (System/currentTimeMillis)
        ;; Per-class calls: strip :facet-by (faceting happens over the
        ;; MERGED set here, not per-class), force :limit 0 (cap applies
        ;; post-merge), keep everything else (:query :where :from :via
        ;; :rank-by :temporal-slot :include :projection) so each class's
        ;; single-class pipeline composes them per-class.
        per-class-opts (-> opts
                           (dissoc :facet-by :class :limit)
                           (assoc :limit 0))
        per-class-res  (mapv (fn [c]
                               (search-bm25f-single (assoc per-class-opts :class c)))
                             class)
        merged-hits    (into [] (mapcat :hits) per-class-res)
        ;; Merge + sort by raw score descending.  Cross-class raw-score
        ;; comparability is APPROXIMATE (per-class IDF/length norm differ).
        sorted         (vec (sort-by :score > merged-hits))
        total          (count sorted)
        limited        (if (zero? limit) sorted (vec (take limit sorted)))
        t-end          (System/currentTimeMillis)
        result         {:hits     limited
                        :total    total
                        :returned (count limited)
                        :timing   {:total-ms (- t-end t-start)}}]
    (cond-> result
      (seq facet-by) (assoc :facets (facet-counts sorted facet-by)))))

(defn search-bm25f
  "Multi-field BM25F search over Datomic-stored entities of `:class`.

  `:class` accepts EITHER a single class-ident keyword (single-class
  scope — unchanged from HEAD) OR a vec of 2..8 class-ident keywords
  (multi-class strategic-subgroup scope — D7).  For the single form this
  delegates byte-identically to the substrate-correct single-class
  pipeline; for the vec form it fans out per-class (each with its own
  `:dt/bm25f-weights`), merges the hit lists, sorts by raw score
  descending, and applies `:limit` post-merge.

  See `search-bm25f-single` (the single-class pipeline; full opt
  documentation) and `search-bm25f-multi` (the multi-class fan-out +
  composition semantics + the approximate cross-class score-comparability
  caveat).

  Per D7 of decisions/c8_ratification_batch_d1_d9_plus_defaults_all_
  approved_2026_07_02 (closes the C12 strategic-subgroup gap) + fulltext
  arc Stage 4c + Stage 29."
  [{:keys [class] :as opts}]
  (if (multiclass? class)
    (search-bm25f-multi opts)
    (search-bm25f-single opts)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Post-schema-reload handler registration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The BM25F caches are schema-derived (per-class field weights from
;; `:dt/bm25f-weights` declarations); schema changes invalidate them.
;; Register with the post-schema-reload handler registry per the precedent in
;; sandbar.db.datatype + the symmetric handler-fire pattern of
;; decisions/zorp_test_cross_fixture_cache_survival_fix_option_2_2026_05_23.md.
;; Wave 0 W.0.4 of the metamodel-unification arc.
(db/register-post-schema-reload-handler! clear-bm25f-cache!)
