#!/usr/bin/env bb
;; grep-fallback-classifier.clj — classify memory-touching Bash commands
;; (captured by block_filesystem_enumeration_of_memory) into WHY-buckets, to
;; answer deliverable (a): grep-substituted-for-navigate vs
;; existence/liveness-check vs path-batch-read vs genuinely-external.
;;
;; The hook records the :command string, so we can classify by shape.
;; Usage: bb etc/grep-fallback-classifier.clj <hook-log-dir>

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def log-dir (or (first *command-line-args*) "/Users/dan/claude/hook-log"))

(defn parse-line [ln] (try (edn/read-string ln) (catch Exception _ nil)))

(def fs-enum-events
  (->> (io/file log-dir)
       (.listFiles)
       (filter #(str/ends-with? (.getName %) ".edn"))
       (filter #(str/starts-with? (.getName %) "2026-"))
       (mapcat #(line-seq (io/reader %)))
       (keep parse-line)
       (filter #(= (:hook %) "block_filesystem_enumeration_of_memory"))
       (filter :command)))

(defn classify [cmd]
  (let [c (str/lower-case cmd)
        touches-memory (str/includes? c "memory/")]
    (cond
      ;; EXISTENCE / LIVENESS CHECK: verifying a file/ident exists, often with
      ;; echo OK/MISSING/EXISTS/ABSENT/CONFIRMED, or `test -f`, or `2>/dev/null && echo`
      (and touches-memory
           (or (re-find #"echo\s+\"?(ok|exists|absent|missing|dead|confirmed|present|ident)" c)
               (re-find #"\btest\s+-f\b" c)
               (re-find #"if\s+\[\s+-f\b" c)
               (re-find #"-name\s+\"?\*[^\"]*\*" c) ;; find -name '*glob*' = discovery
               (str/includes? c "2>/dev/null && echo")
               (str/includes? c "|| echo")))
      :existence-liveness-check

      ;; PATH-BATCH-READ: for-loop / cat over one or more KNOWN memory/ paths
      (and touches-memory
           (or (re-find #"for\s+f\s+in\b" c)
               (re-find #"\bcat\s+" c)
               (re-find #"\bwc\s+-l\b" c)
               (re-find #"\bhead\b" c)
               (re-find #"\btail\b" c)))
      :path-batch-read-or-slice

      ;; CONTENT-GREP: grep -r for a CONCEPT/term inside memory bodies — the
      ;; case bm25f/search.attribute would serve
      (and touches-memory (re-find #"\bgrep\b" c) (re-find #"grep\s+-[a-z]*r" c))
      :content-grep-recursive-SUBSTITUTABLE-by-bm25f

      ;; single-file grep (line-locate within a known file) — NOT substitutable
      ;; by bm25f (bm25f ranks whole docs; this pulls a line out of one doc)
      (and touches-memory (re-find #"\bgrep\b" c))
      :single-file-line-grep

      ;; DIRECTORY LISTING: ls memory/subtree  (browse / count / fuzzy-name)
      (and touches-memory (re-find #"\bls\b" c))
      :dir-listing-browse

      ;; find without a name-glob = tree walk
      (and touches-memory (re-find #"\bfind\b" c))
      :find-tree-walk

      touches-memory :other-memory-touching
      :else          :non-memory-collateral)))

(def by-decision-class
  (->> fs-enum-events
       (map (fn [e] [(:decision e) (classify (:command e))]))
       (reduce (fn [acc [d k]] (update-in acc [d k] (fnil inc 0)) ) {})))

(def overall
  (->> fs-enum-events (map #(classify (:command %))) frequencies (sort-by val >)))

(def total (count fs-enum-events))

(println "=== grep-fallback classification over" total "memory-touching Bash commands ===")
(println "(captured by block_filesystem_enumeration_of_memory across all days)\n")
(println "--- OVERALL by WHY-bucket ---")
(doseq [[k n] overall]
  (println (format "%6d  %5.1f%%  %s" n (* 100.0 (/ n total)) (name k))))
(println)
(println "--- by decision × bucket (denied = hook actually blocked it) ---")
(doseq [[d m] (sort-by (comp - (fn [mm] (reduce + (vals mm))) val) by-decision-class)]
  (println (str "  " d " (total " (reduce + (vals m)) "):"))
  (doseq [[k n] (sort-by val > m)]
    (println (format "      %5d  %s" n (name k)))))
(println)
;; Sample the SUBSTITUTABLE-by-bm25f bucket to sanity-check the classifier
(println "--- SAMPLE: content-grep-recursive (the genuinely-bm25f-substitutable ones) ---")
(doseq [e (->> fs-enum-events
               (filter #(= :content-grep-recursive-SUBSTITUTABLE-by-bm25f (classify (:command %))))
               (take 12))]
  (println "   [" (:decision e) "]" (subs (:command e) 0 (min 130 (count (:command e))))))
