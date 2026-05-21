(ns sandbar.scripts.roundtrip-stability
  "Stability test for codec round-trip.  Per
   `decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md`:
   the codec MAY normalize source text on first emit (whitespace, key
   order, list flow style, etc.), but the normalized form MUST be a
   FIXED POINT under further round-trips — emit1 must equal emit2 when
   emit1 is itself re-parsed and emitted.

   This catches non-deterministic emit paths (set iteration order
   variations, extras-slot appendage order, etc.) that would create git
   churn on repeated projection.

   Usage:
     lein run -m sandbar.scripts.roundtrip-stability <corpus-root> [<rel-path>]

   With <rel-path>: stability check on one file; prints first ~30
   differing lines between emit1 and emit2.

   Without <rel-path>: stability check on the full corpus.  Reports
   #stable / #unstable / #errors counts."
  (:require [clojure.java.io        :as io]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datomic     :as db]
            [sandbar.test-util      :as tu]))

(defn- round-trip-once
  "Parse src then emit; return the emitted string."
  [src rel-path]
  (let [entities (codec-md/parse-document src rel-path)]
    (codec-md/emit-document entities)))

(defn- two-emit-check
  "Round-trip src twice; return [emit1 emit2 stable?]."
  [src rel-path]
  (let [emit1 (round-trip-once src rel-path)
        emit2 (round-trip-once emit1 rel-path)]
    [emit1 emit2 (= emit1 emit2)]))

(defn- single-file-check
  [corpus-root rel-path]
  (let [src   (slurp (io/file corpus-root rel-path))
        [emit1 emit2 stable?] (two-emit-check src rel-path)]
    (println (str "rel-path:   " rel-path))
    (println (str "src bytes:  " (count src)))
    (println (str "emit1 bytes: " (count emit1)))
    (println (str "emit2 bytes: " (count emit2)))
    (println (str "STABLE?     " stable?))
    (when-not stable?
      (println)
      (println "=== FIRST ~30 LINES OF EMIT1 vs EMIT2 DIFF ===")
      (loop [[a & as] (str/split-lines emit1)
             [b & bs] (str/split-lines emit2)
             i 0
             shown 0]
        (cond
          (>= shown 30) (println "... (truncated)")
          (and (nil? a) (nil? b)) (println "(end of file)")
          (= a b) (recur as bs (inc i) shown)
          :else (do
                  (println (str "L" i ": EMIT1: " (pr-str a)))
                  (println (str "L" i ": EMIT2: " (pr-str b)))
                  (recur as bs (inc i) (inc shown))))))))

(defn- full-corpus-check
  [corpus-root]
  (let [root-file (io/file corpus-root)
        all-files (->> (file-seq root-file)
                       (filter #(.isFile ^java.io.File %))
                       (filter #(str/ends-with? (.getName ^java.io.File %) ".md")))
        total    (count all-files)]
    (println (str "Stability-checking " total " files from " corpus-root "..."))
    (let [t0 (System/currentTimeMillis)
          {:keys [stable unstable errors unstable-samples error-samples]}
          (reduce
            (fn [acc f]
              (let [rel-path (-> (.toPath root-file)
                                 (.relativize (.toPath ^java.io.File f))
                                 str)]
                (try
                  (let [src (slurp f)
                        [_ _ stable?] (two-emit-check src rel-path)]
                    (if stable?
                      (update acc :stable inc)
                      (-> acc
                          (update :unstable inc)
                          (update :unstable-samples conj rel-path))))
                  (catch Exception e
                    (-> acc
                        (update :errors inc)
                        (update :error-samples conj [rel-path (.getMessage e)]))))))
            {:stable 0 :unstable 0 :errors 0 :unstable-samples [] :error-samples []}
            all-files)
          elapsed (- (System/currentTimeMillis) t0)]
      (println (str "Completed in " elapsed "ms"))
      (println (str "  stable:   " stable))
      (println (str "  unstable: " unstable))
      (println (str "  errors:   " errors))
      (let [stability-rate (* 100.0 (/ stable (max 1 (+ stable unstable))))]
        (println (format "  stability rate: %.1f%% (of non-error files)" stability-rate)))
      (println)
      (println "First 10 unstable samples:")
      (doseq [p (take 10 unstable-samples)]
        (println "  " p))
      (println)
      (println "First 5 error samples:")
      (doseq [[p msg] (take 5 error-samples)]
        (println "  " p ": " msg)))))

(defn -main
  [& [corpus-root rel-path]]
  (when (str/blank? (str corpus-root))
    (println "Usage: lein run -m sandbar.scripts.roundtrip-stability <corpus-root> [<rel-path>]")
    (System/exit 2))
  (let [test-uri "datomic:mem://roundtrip-stability"]
    (d/delete-database test-uri)
    (d/create-database test-uri)
    (reset! db/**conn* (d/connect test-uri))
    (try
      (tu/load-required-schema (db/conn))
      (if rel-path
        (single-file-check corpus-root rel-path)
        (full-corpus-check corpus-root))
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)
        (System/exit 0)))))
