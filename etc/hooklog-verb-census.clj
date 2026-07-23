#!/usr/bin/env bb
;; hooklog-verb-census.clj — per-verb call census from the client hook-log.
;;
;; The hook-log records EVERY PreToolUse fire.  `inject_predicate_suggestion`
;; fires exactly ONCE per PreToolUse (verified), so counting its lines by
;; :tool-name yields an accurate per-tool call census WITHOUT the multi-hook
;; inflation that raw :tool-name grep produces.
;;
;; Deliverable-C caveat this script makes concrete: the log records the
;; :tool-name (verb NAME) but NOT the verb ARGS.  So we can count that
;; search.bm25f was called 1210x but CANNOT tell from the hook-log whether
;; :from / :via / :rank-by opts were EVER passed.  That is the telemetry
;; blind spot.
;;
;; Usage: bb etc/hooklog-verb-census.clj <hook-log-dir>

(require '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def log-dir (or (first *command-line-args*) "/Users/dan/claude/hook-log"))

(def dedup-hook "inject_predicate_suggestion") ;; fires 1x/PreToolUse

(defn parse-line [ln]
  (try (edn/read-string ln) (catch Exception _ nil)))

(def all-lines
  (->> (io/file log-dir)
       (.listFiles)
       (filter #(str/ends-with? (.getName %) ".edn"))
       (filter #(str/starts-with? (.getName %) "2026-"))
       (mapcat #(line-seq (io/reader %)))
       (keep parse-line)))

;; ---- 1. per-verb PreToolUse census (deduped) ----
(def dedup-events
  (filter #(= (:hook %) dedup-hook) all-lines))

(def by-tool (frequencies (map :tool-name dedup-events)))

(def total-calls (reduce + (vals by-tool)))

(def sandbar-verbs
  (->> by-tool
       (filter (fn [[k _]] (and k (str/starts-with? k "mcp__sandbar__"))))
       (sort-by val >)))

(def sandbar-total (reduce + (map val sandbar-verbs)))

;; ---- 2. axis rollup ----
(defn verb->axis [v]
  ;; mcp__sandbar__sandbar_<axis>_<op>  -> :<axis>
  (let [tail (str/replace v #"^mcp__sandbar__sandbar_" "")
        axis (first (str/split tail #"_"))]
    axis))

(def by-axis
  (->> sandbar-verbs
       (map (fn [[v n]] [(verb->axis v) n]))
       (reduce (fn [acc [a n]] (update acc a (fnil + 0) n)) {})
       (sort-by val >)))

;; ---- 3. grep/find/rg/cat Bash census (memory-touching heuristic) ----
;; We can only see :tool-name Bash here, not the command string (hook-log
;; doesn't record commands).  So we count the block_filesystem_enumeration_of_memory
;; hook fires which DO fire specifically on memory-touching FS enumeration.
(def fs-enum-blocks
  (filter #(= (:hook %) "block_filesystem_enumeration_of_memory") all-lines))
(def fs-enum-by-day
  (->> fs-enum-blocks
       (map #(subs (or (:ts %) "?") 0 10))
       frequencies
       (sort-by key)))
(def fs-enum-by-decision
  (frequencies (map :decision fs-enum-blocks)))

;; block_mem_use_slash_command + block_bb_use_mem also mark FS-tooling reaches
(def mem-slash-blocks (frequencies (map :decision (filter #(= (:hook %) "block_mem_use_slash_command") all-lines))))

;; ---- 4. verb-composition hook (deliverable d — the inject_verb_composition telemetry) ----
(def vc-events (filter #(= (:hook %) "inject_verb_composition") all-lines))
(def vc-by-decision (frequencies (map :decision vc-events)))
(def vc-by-day (->> vc-events (map #(subs (or (:ts %) "?") 0 10)) frequencies (sort-by key)))

;; ---- 5. date span ----
(def date-span
  (let [ds (->> dedup-events (keep :ts) (map #(subs % 0 10)) sort)]
    [(first ds) (last ds) (count (distinct ds))]))

;; ---- OUTPUT ----
(println "======================================================================")
(println "HOOK-LOG VERB CENSUS  (dedup via" dedup-hook "— 1 fire/PreToolUse)")
(println "date span:" (pr-str date-span) " total deduped PreToolUse events:" total-calls)
(println "======================================================================")
(println)
(println "--- ALL TOOL CALLS (top 30, incl. non-sandbar) ---")
(doseq [[t n] (take 30 (sort-by val > by-tool))]
  (println (format "%6d  %5.1f%%  %s" n (* 100.0 (/ n total-calls)) t)))
(println)
(println "--- SANDBAR VERBS ONLY (n =" sandbar-total "=" (format "%.1f%%" (* 100.0 (/ sandbar-total total-calls))) "of all calls) ---")
(doseq [[v n] sandbar-verbs]
  (println (format "%6d  %5.1f%% (of sandbar)  %s" n (* 100.0 (/ n sandbar-total)) v)))
(println)
(println "--- SANDBAR BY AXIS ---")
(doseq [[a n] by-axis]
  (println (format "%6d  %5.1f%%  %s" n (* 100.0 (/ n sandbar-total)) a)))
(println)
(println "--- block_filesystem_enumeration_of_memory (grep/find/ls over memory/) ---")
(println "  total fires:" (count fs-enum-blocks))
(println "  by decision:" (pr-str fs-enum-by-decision))
(println "  by day:")
(doseq [[d n] fs-enum-by-day] (println (format "    %s  %d" d n)))
(println)
(println "--- block_mem_use_slash_command by decision:" (pr-str mem-slash-blocks))
(println)
(println "--- inject_verb_composition (n =" (count vc-events) ") ---")
(println "  by decision:" (pr-str vc-by-decision))
(println "  by day:")
(doseq [[d n] vc-by-day] (println (format "    %s  %d" d n)))
