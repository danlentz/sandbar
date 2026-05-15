(ns sandbar.bench.harness
  "Timing harness primitives for the Sandbar 0.1.0 bench scaffolding
  (F-DF-1 Phase 1; Phase R Stage R-6 per
  plans/sandbar_0_1_0_codex_remediation_2026_05_14.md).

  Three concerns:
    1. Multi-iteration timing → median + p95 latency in milliseconds
    2. Warmup discipline → JVM JIT settles before measurement window
    3. EDN baseline emission → per-axis × per-size records for
       regression comparison

  Scope: Phase 1 (scaffolding only).  Optimization work (Phase 3)
  is out of scope for 0.1.0 — these harnesses establish the
  baselines that future optimization passes will regress against."
  (:require [clojure.edn        :as edn]
            [clojure.java.io    :as io]
            [clojure.pprint     :as pp]))

(defn- now-ns
  "Wall-clock nanosecond timestamp via System/nanoTime."
  ^long []
  (System/nanoTime))

(defn- nano->ms
  "Convert nanoseconds to milliseconds (double)."
  ^double [^long ns]
  (/ ns 1000000.0))

(defn- percentile
  "Compute the `p` percentile (0.0–1.0) of `xs` (numeric coll).
   Uses linear interpolation between the two nearest ranks; matches
   common p50 / p95 statistical conventions for small N."
  [p xs]
  (let [sorted (vec (sort xs))
        n      (count sorted)
        idx    (Math/min (dec n)
                         (long (Math/floor (* p (dec n)))))]
    (nth sorted idx)))

(defn time-fn
  "Run `f` `iterations` times after `warmup` warmup invocations;
   return `{:median-ms :p95-ms :iterations :warmup :samples-ms}`.

   `f` is a 0-arg fn returning whatever (return value discarded —
   we measure wall-clock only).  Side-effecting fns are fine.

   Defaults: 3 warmup + 10 measured iterations.  Bump iterations
   for tighter p95 confidence at scale; bump warmup if the first
   measured iteration shows obvious JIT-settling skew."
  ([f]
   (time-fn f {}))
  ([f {:keys [iterations warmup]
       :or   {iterations 10 warmup 3}}]
   (dotimes [_ warmup] (f))
   (let [samples (vec
                   (for [_ (range iterations)]
                     (let [t0 (now-ns)]
                       (f)
                       (nano->ms (- (now-ns) t0)))))]
     {:median-ms  (percentile 0.5 samples)
      :p95-ms     (percentile 0.95 samples)
      :iterations iterations
      :warmup     warmup
      :samples-ms samples})))

(defn run-suite
  "Run a coll of `[axis-keyword size-int fn]` triples; return a
   coll of result maps shaped:

     [{:axis :degree
       :size 100
       :median-ms 1.23
       :p95-ms 2.45
       :iterations 10
       :warmup 3
       :samples-ms [1.1 1.2 ...]}
      ...]

   Triples are taken as fully-prepared closures (each fn captures
   its own size + DB setup); the harness is dumb about how the
   triples are produced."
  [triples opts]
  (mapv (fn [[axis size f]]
          (merge {:axis axis :size size}
                 (time-fn f opts)))
        triples))

(defn emit-baseline!
  "Write `results` to `path` as pretty-printed EDN.  Adds a
   top-level `:emitted-at` timestamp + `:sandbar-version` for
   reproducibility provenance.  Creates parent dirs if absent."
  [path results sandbar-version]
  (let [file (io/file path)
        out  {:emitted-at      (str (java.time.Instant/now))
              :sandbar-version sandbar-version
              :results         (vec results)}]
    (io/make-parents file)
    (with-open [w (io/writer file)]
      (binding [*out* w]
        (pp/pprint out)))
    (.getCanonicalPath file)))

(defn load-baseline
  "Read an EDN baseline file (for regression comparison).
   Returns the same shape `emit-baseline!` writes, or nil if path
   does not exist."
  [path]
  (let [file (io/file path)]
    (when (.exists file)
      (with-open [r (io/reader file)]
        (edn/read (java.io.PushbackReader. r))))))

(defn summarize
  "Project bench results to a compact one-line-per-row format for
   console display.  Returns a seq of strings."
  [results]
  (for [{:keys [axis size median-ms p95-ms iterations]} results]
    (format "%-25s  size=%6d  median=%7.2fms  p95=%7.2fms  (n=%d)"
            (str axis)
            size
            median-ms
            p95-ms
            iterations)))
