(ns sandbar.bench.run
  "Top-level bench orchestrator (F-DF-1 Phase 1+2; Phase R Stage
  R-6 per plans/sandbar_0_1_0_codex_remediation_2026_05_14.md).

  Runs the three structural-rank + path-grammar hot paths against
  a synthetic-graph at the fixed size ladder (10 / 100 / 1k / 10k)
  and emits a baseline EDN file to `bench-results/`.

  Invocation:
    lein bench             — full ladder + baseline emission

  ## Bench discipline (run-before-optimization)

  This harness establishes performance baselines for the four-axis
  retrieval surface's structural-rank + path-grammar hot paths.
  Phase 3 (optimization) lands post-0.1.0; the baselines committed
  in `bench-results/baseline.edn` are the regression bar future
  optimization work must improve against."
  (:require [datomic.api         :as d]
            [sandbar.aggregate   :as agg]
            [sandbar.bench.harness   :as harness]
            [sandbar.bench.synthetic :as syn]
            [sandbar.db.datomic  :as db]
            [sandbar.navigate.path :as nav-path]
            [sandbar.util.edn    :as edn-util])
  (:gen-class))

(def ^:private size-ladder
  "Default class-size ladder.  Override via main argv for a
   smoke-run (e.g. `lein bench 10 100`) when investigating without
   re-running the full 10k tier (slow on cold JVMs)."
  [10 100 1000 10000])

(defn- load-required-schema!
  "Mirror sandbar.test-util/load-required-schema without depending
   on the test-source-path (which is not on the bench classpath).
   Uses sandbar.util.edn helpers — same code path as production."
  [conn]
  (let [schema-names (edn-util/config-value :required-schema)
        names        (if (keyword? schema-names) [schema-names] schema-names)]
    (doseq [schema-name names]
      (doseq [stmt (edn-util/resource-value schema-name nil)]
        @(d/transact conn stmt)))))

(defn- with-fresh-db!
  "Open a fresh in-memory Datomic DB; load required schema; run
   `body-fn` with the connection bound via `db/**conn*`; tear down
   on exit."
  [body-fn]
  (let [uri (str "datomic:mem://bench-" (System/nanoTime))]
    (d/create-database uri)
    (let [conn (d/connect uri)]
      (reset! db/**conn* conn)
      (try
        (load-required-schema! conn)
        (body-fn)
        (finally
          (reset! db/**conn* nil)
          (d/delete-database uri))))))

(defn- bench-rank-by-degree
  [_ctx]
  (agg/rank-by {:class :dt/Class :rank-by :degree :limit 0}))

(defn- bench-rank-by-backlink-density
  [_ctx]
  (agg/rank-by {:class :dt/Class :rank-by :backlink-density :limit 0}))

(defn- bench-path-via-rep-plus
  [{:keys [chain-root]}]
  (nav-path/path-via {:from chain-root :via [:REP+ :dt/subclass-of]}))

(defn- bench-at-size
  "Populate synthetic graph at `size`; time the three axes; tear down."
  [size opts]
  (with-fresh-db!
    (fn []
      (let [ctx (syn/populate! {:size size :chain-depth 3})]
        (harness/run-suite
          [[:rank-by/degree            size #(bench-rank-by-degree ctx)]
           [:rank-by/backlink-density  size #(bench-rank-by-backlink-density ctx)]
           [:path-via/rep+             size #(bench-path-via-rep-plus ctx)]]
          opts)))))

(defn run
  "Run the full bench suite at every size in `sizes`; return the
   flat coll of result maps.

   Opts forwarded to `harness/time-fn` (`:iterations`, `:warmup`)."
  ([] (run size-ladder {}))
  ([sizes] (run sizes {}))
  ([sizes opts]
   (vec (mapcat (fn [size]
                  (println (format "  [size %d] running …" size))
                  (bench-at-size size opts))
                sizes))))

(defn -main
  "Entry point for `lein bench`.  Optional argv overrides the size
   ladder for a smoke-run; without args, runs the full
   10 / 100 / 1k / 10k ladder + emits baseline EDN."
  [& args]
  (let [sizes      (if (seq args)
                     (mapv #(Long/parseLong %) args)
                     size-ladder)
        ;; At the 10k tier, query latency dominates harness overhead
        ;; enough that 10 iterations + 3 warmup is reasonable.  Bump
        ;; per-axis if confidence is needed.
        opts       {:iterations 10 :warmup 3}
        _          (println (format "Sandbar bench — sizes %s, iterations %d, warmup %d"
                                    (vec sizes)
                                    (:iterations opts)
                                    (:warmup opts)))
        results    (run sizes opts)
        out-path   "bench-results/baseline.edn"
        sandbar-version (or (System/getProperty "sandbar.version")
                            "0.1.0")
        canonical  (harness/emit-baseline! out-path results sandbar-version)]
    (println)
    (doseq [line (harness/summarize results)]
      (println " " line))
    (println)
    (println (format "Baseline written: %s" canonical))
    (println "  (commit this file as the regression bar; future")
    (println "   optimization work must improve against it.)")
    (shutdown-agents)))
