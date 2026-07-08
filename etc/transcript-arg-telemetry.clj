#!/usr/bin/env bb
;; transcript-arg-telemetry.clj — recover ARG-LEVEL verb telemetry from the
;; Claude Code transcript JSONL files (the blind spot the hook-log has).
;;
;; The hook-log records verb NAMES only.  The transcripts record full
;; tool_use.input objects.  This answers deliverable (c): are bm25f's
;; :from / :via / :rank-by / :facet-by composition opts EVER actually used?
;; And which opts do the graph/orient verbs actually receive?
;;
;; Usage: bb etc/transcript-arg-telemetry.clj <transcript-dir>

(require '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.java.io :as io])

(def tdir (or (first *command-line-args*)
              "/Users/dan/.claude/projects/-Users-dan-claude"))

(def files
  (->> (io/file tdir) (.listFiles)
       (filter #(str/ends-with? (.getName %) ".jsonl"))))

;; Each JSONL line is one transcript event.  Assistant messages carry a
;; :message with :content vec; tool_use blocks have {:type "tool_use" :name .. :input ..}.
(defn tool-uses-in-line [ln]
  (try
    (let [ev (json/parse-string ln true)
          content (get-in ev [:message :content])]
      (when (sequential? content)
        (keep (fn [b] (when (= (:type b) "tool_use")
                        {:name (:name b) :input (:input b)}))
              content)))
    (catch Exception _ nil)))

(def all-tool-uses
  (->> files
       (mapcat #(line-seq (io/reader %)))
       (mapcat tool-uses-in-line)
       (filter :name)))

(def sandbar-uses
  (filter #(str/starts-with? (str (:name %)) "mcp__sandbar__") all-tool-uses))

(defn verb-short [n] (str/replace (str n) #"^mcp__sandbar__sandbar_" ""))

;; ---- bm25f arg-opt census ----
(def bm25f (filter #(= (:name %) "mcp__sandbar__sandbar_search_bm25f") sandbar-uses))
(defn opt-present? [u k] (contains? (:input u) k))
(def bm25f-opt-counts
  (reduce (fn [acc u]
            (reduce (fn [a k] (cond-> a (opt-present? u k) (update k (fnil inc 0))))
                    acc
                    [:query :class :limit :where :from :via :rank-by :facet-by
                     :temporal-slot :include :field-weights :projection]))
          {} bm25f))

;; ---- navigate/orient/path-via arg census ----
(defn verb-arg-census [verb-name]
  (let [us (filter #(= (:name %) verb-name) sandbar-uses)
        opt-freq (->> us
                      (mapcat #(keys (:input %)))
                      frequencies
                      (sort-by val >))]
    {:n (count us) :opts opt-freq
     :samples (take 4 (map :input us))}))

;; ---- overall sandbar verb frequency from transcripts (cross-check vs hook-log) ----
(def verb-freq (->> sandbar-uses (map #(verb-short (:name %))) frequencies (sort-by val >)))

(println "=== TRANSCRIPT ARG-LEVEL TELEMETRY ===")
(println "transcript files:" (count files) " total tool_use blocks:" (count all-tool-uses)
         " sandbar tool_use:" (count sandbar-uses))
(println "NOTE: transcripts on disk =" (count files)
         "— this is a SUBSET of all-time sessions (older transcripts rotated); treat as a recent-window sample.\n")

(println "--- search.bm25f (" (count bm25f) " calls): which OPTS are actually passed? ---")
(doseq [[k n] (sort-by val > bm25f-opt-counts)]
  (println (format "   %5d  %5.1f%%  %s" n (* 100.0 (/ n (max 1 (count bm25f)))) (name k))))
(println "   >>> COMPOSITION OPTS (:from :via :rank-by :facet-by :temporal-slot):"
         (reduce + (map #(get bm25f-opt-counts % 0) [:from :via :rank-by :facet-by :temporal-slot]))
         "total across" (count bm25f) "bm25f calls")
(println)

(doseq [v ["mcp__sandbar__sandbar_navigate_path-via"
           "mcp__sandbar__sandbar_navigate_inbound-edges"
           "mcp__sandbar__sandbar_navigate_outbound-edges"
           "mcp__sandbar__sandbar_orient_library-card"
           "mcp__sandbar__sandbar_aggregate_rank-by"]]
  (let [{:keys [n opts samples]} (verb-arg-census v)]
    (println (format "--- %s (%d calls) ---" (verb-short v) n))
    (doseq [[k c] opts] (println (format "      %4d  %s" c (name k))))
    (when (seq samples)
      (println "      sample inputs:")
      (doseq [s samples] (println "        " (pr-str s))))
    (println)))

(println "--- top 25 sandbar verbs (transcript window; cross-check vs hook-log all-time) ---")
(doseq [[v n] (take 25 verb-freq)] (println (format "   %5d  %s" n v)))
