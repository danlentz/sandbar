(ns sandbar.db.entailment.quality
  "Substrate-quality utilities for the entailment graph — β.2.0 pre-work.

   Two responsibilities:

   1. **S.3 — Entailment cost benchmark**: instrument rdfs2 / rdfs3 / rdfs9 /
      rdfs11 entailment latency at corpus scale.  Compares ground-fact vs
      entailment-aware query latency; produces median + p95 numbers per rule.
      Informs Q.B.0.a (threshold for Stage-6 materialization trigger; master
      plan suggests >10x ground-fact latency triggers materialization sub-arc).

   2. **S.8 — Schema-load DAG cycle detection**: walks the `:dt/subclass-of`
      and `:dt/subproperty-of` graphs at schema-load boundary; detects cycles
      via DFS with visited-set tracking.  Loud-fails on cycle detection per
      Q.B.0.b (recommendation: loud-fail; cycles in these graphs are
      pathological per RDFS semantics).

   Per:
   - `:memory.plans/sandbar_pre_0_2_0_release_arc_phase_b_h_i_d_g_…_2026_05_25`
     §3.β.2 (B.0 pre-work mandatory before B.1)
   - `:memory.plans/sandbar_metamodel_unification_arc_…_2026_05_24` §10.B.0
   - β.2 plan (`/Users/dan/.claude/plans/wise-splashing-stardust.md`) §3 stage
     β.2.0

   Both functions are read-only against the substrate — no mutations.
   Validation throws ex-info on cycle detection (the loud-fail path); benchmark
   returns the latency-report map for caller-side analysis."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.entailment.core :as ent]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S.8 — Schema-load DAG cycle detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- subclass-edges
  "Returns a vec of `[child-ident parent-ident]` pairs from `:dt/subclass-of`
   substrate state.  Empty if no such edges declared."
  [db]
  (->> (d/q '[:find ?child-ident ?parent-ident
              :where
              [?child :dt/subclass-of ?parent]
              [?child :db/ident ?child-ident]
              [?parent :db/ident ?parent-ident]]
            db)
       (mapv vec)))

(defn- subproperty-edges
  "Returns a vec of `[sub-ident super-ident]` pairs from `:dt/subproperty-of`
   substrate state.  Empty if no such edges declared."
  [db]
  (->> (d/q '[:find ?sub-ident ?super-ident
              :where
              [?sub :dt/subproperty-of ?super]
              [?sub :db/ident ?sub-ident]
              [?super :db/ident ?super-ident]]
            db)
       (mapv vec)))

(defn- edges->adjacency
  "Convert `[[child parent] ...]` edge list to `{child #{parent ...}}` adjacency
   map (child → set-of-parents)."
  [edges]
  (reduce (fn [acc [c p]]
            (update acc c (fnil conj #{}) p))
          {}
          edges))

(defn- find-cycle
  "DFS for cycle detection.  Returns the first cycle found as a vec of
   idents `[a b c a]` (path closing on itself), or nil if the graph is
   acyclic.  Uses three-color DFS (white=unvisited / gray=in-stack /
   black=done)."
  [adj]
  (let [state (volatile! {})  ; ident → :gray | :black
        path  (volatile! [])
        result (volatile! nil)]
    (letfn [(visit [node]
              (when (nil? @result)
                (case (get @state node)
                  :gray (let [idx (or (->> @path
                                            (keep-indexed (fn [i n] (when (= n node) i)))
                                            first)
                                       0)
                              cycle-path (vec (concat (subvec @path idx) [node]))]
                          (vreset! result cycle-path))
                  :black nil
                  ;; default (:white / nil): proceed
                  (do (vswap! state assoc node :gray)
                      (vswap! path conj node)
                      (doseq [n (get adj node)]
                        (when (nil? @result)
                          (visit n)))
                      (vswap! path #(if (seq %) (pop %) %))
                      (vswap! state assoc node :black)))))]
      (doseq [node (keys adj)]
        (when (nil? @result)
          (visit node)))
      @result)))

(defn detect-cycles
  "Run cycle detection over `:dt/subclass-of` + `:dt/subproperty-of` graphs.
   Returns `{:subclass-cycle <vec-or-nil> :subproperty-cycle <vec-or-nil>}`.
   Each cycle is a vec of idents closing on itself, or nil if acyclic."
  [db]
  (let [sc-edges  (subclass-edges db)
        sp-edges  (subproperty-edges db)
        sc-adj    (edges->adjacency sc-edges)
        sp-adj    (edges->adjacency sp-edges)]
    {:subclass-cycle    (find-cycle sc-adj)
     :subproperty-cycle (find-cycle sp-adj)
     :subclass-edge-count    (count sc-edges)
     :subproperty-edge-count (count sp-edges)}))

(defn validate-entailment-graph!
  "Run DAG cycle detection over `:dt/subclass-of` + `:dt/subproperty-of`.
   On cycle detection: throws ex-info with `:reason :entailment-graph-cycle`
   + the offending cycle path.  Q.B.0.b ratified loud-fail policy.

   Two arities:
     (validate-entailment-graph!)        ; uses (db/db) — current connection
     (validate-entailment-graph! uri)    ; resolves via (d/db (db/conn uri))

   Called from `sandbar.db.datomic/initialize-db!` after `load-all-schema!`
   with the boot-path URI.  Idempotent + read-only against substrate.
   Per β.2.0 pre-work."
  ([] (validate-entailment-graph! (db/db)))
  ([uri-or-db]
   (let [db (if (or (string? uri-or-db) (keyword? uri-or-db))
              (d/db (db/conn uri-or-db))
              uri-or-db) ; assume it's already a db value
         {:keys [subclass-cycle subproperty-cycle
                 subclass-edge-count subproperty-edge-count]} (detect-cycles db)]
    (log/info :ENTAILMENT-GRAPH/VALIDATE
              {:subclass-edges subclass-edge-count
               :subproperty-edges subproperty-edge-count
               :subclass-cycle? (boolean subclass-cycle)
               :subproperty-cycle? (boolean subproperty-cycle)})
    (when subclass-cycle
      (throw (ex-info ":dt/subclass-of graph contains a cycle"
                      {:reason :entailment-graph-cycle
                       :graph :dt/subclass-of
                       :cycle subclass-cycle})))
    (when subproperty-cycle
      (throw (ex-info ":dt/subproperty-of graph contains a cycle"
                      {:reason :entailment-graph-cycle
                       :graph :dt/subproperty-of
                       :cycle subproperty-cycle})))
    {:valid? true
     :subclass-edge-count subclass-edge-count
     :subproperty-edge-count subproperty-edge-count})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S.3 — Entailment cost benchmark
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- percentile
  "Compute the p-th percentile (0-100) of a vec of numbers via linear
   interpolation.  Returns nil if empty."
  [xs p]
  (when (seq xs)
    (let [sorted (sort xs)
          n      (count sorted)
          idx    (min (dec n) (int (Math/floor (* (/ p 100.0) (dec n)))))]
      (nth (vec sorted) idx))))

(defn- timed-query
  "Invoke `f` repeatedly (default 50 iterations); return latency stats in ms."
  ([f] (timed-query f 50))
  ([f n]
   (let [latencies-ns (vec (for [_ (range n)]
                              (let [t-start (System/nanoTime)
                                    _ (f)
                                    t-end (System/nanoTime)]
                                (- t-end t-start))))
         to-ms #(/ % 1e6)]
     {:iterations n
      :min-ms     (to-ms (apply min latencies-ns))
      :median-ms  (to-ms (percentile latencies-ns 50))
      :p95-ms     (to-ms (percentile latencies-ns 95))
      :max-ms     (to-ms (apply max latencies-ns))})))

(defn benchmark-rdfs9-instance-lift
  "Benchmark rdfs9 (subClassOf instance lift) cost over the substrate.

   Compares two queries:
   - GROUND: `(?e :dt/type ?c)` direct-only (no entailment closure)
   - ENTAILED: `(?e :dt/type ?direct-c)` + `(?direct-c :dt/subclass-of ?c)`
     transitive (the entailment-aware variant via apply-entailment)

   Returns latency stats + ratio (entailed-median / ground-median)."
  [db & {:keys [iterations] :or {iterations 50}}]
  (let [ground-fn   (fn []
                      (d/q '[:find ?e ?c
                             :where [?e :dt/type ?c]]
                           db))
        entailed-fn (fn []
                      (ent/apply-entailment
                        db
                        '[:find ?e ?c
                          :in $ %
                          :where (rdfs9-isa ?e ?c)]
                        ent/rdfs-rules))
        ground-stats   (timed-query ground-fn iterations)
        entailed-stats (timed-query entailed-fn iterations)]
    {:ground   ground-stats
     :entailed entailed-stats
     :ratio    (if (pos? (:median-ms ground-stats))
                 (/ (:median-ms entailed-stats) (:median-ms ground-stats))
                 nil)}))

(defn run-benchmark
  "Run the β.2.0 entailment-cost benchmark suite.  Returns a structured report
   suitable for the β.2.0 closure observation memorial.

   Currently exercises rdfs9 (subClassOf instance lift) as the representative
   rule; rdfs2 / rdfs3 / rdfs11 follow the same shape and can be added if
   per-rule numbers are wanted.  Per Q.B.0.a ratification + master plan §3.β.2.

   Required opt: `:db` — a Datomic DB value to benchmark against."
  [{:keys [db iterations] :or {iterations 50} :as _opts}]
  (when (nil? db)
    (throw (ex-info "sandbar.db.entailment.quality/run-benchmark requires :db"
                    {:reason :missing-db})))
  (let [t-start (System/currentTimeMillis)
        rdfs9-report (benchmark-rdfs9-instance-lift db :iterations iterations)
        t-end (System/currentTimeMillis)]
    {:rule-reports {:rdfs9-isa rdfs9-report}
     :total-duration-ms (- t-end t-start)
     :benchmark-instant (java.util.Date.)
     :iterations iterations}))

(comment
  ;; REPL usage:
  ;;   (require '[sandbar.db.entailment.quality :as ent-q])
  ;;   (require '[sandbar.db.datomic :as db])
  ;;   (ent-q/validate-entailment-graph! (db/db-uri))
  ;;   (ent-q/run-benchmark {:db (db/db)})
  )
