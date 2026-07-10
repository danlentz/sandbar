;; W1.D byte-correctness + edge-case verification (in-memory; ZERO writes).
(require '[sandbar.migrate.id-backfill :as bf]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(def scratch "/private/tmp/claude-501/-Users-dan-claude/0d71181d-485f-44de-838f-71c18aec0495/scratchpad/w1d-wt")
(def corpus "/Users/dan/claude")
(def id-map (:id-map (edn/read-string (slurp (str scratch "/db-id-map.edn")))))

(defn show [rel]
  (let [f (str corpus "/memory/" rel)
        content (slurp f)
        plan (bf/plan-file content (get id-map rel))
        applied (bf/apply-plan content plan)]
    (println "\n########" rel "########")
    (println "action:" (:action plan) "| reason:" (:reason plan))
    (when (#{:insert :rewrite} (:action plan))
      (let [ob (first (bf/split-frontmatter content))
            nb (first (bf/split-frontmatter applied))]
        (println "--- FM BEFORE (" (count ob) "lines) ---")
        (doseq [l ob] (println "  " l))
        (println "--- FM AFTER (" (count nb) "lines) ---")
        (doseq [l nb] (println "  " l))
        (println "body byte-identical?"
                 (= (str/join "\n" (rest (bf/split-frontmatter content)))
                    (str/join "\n" (rest (bf/split-frontmatter applied)))))
        (println "only-1-line-added-or-changed?"
                 (let [ol (str/split-lines content) al (str/split-lines applied)]
                   (cond (= :insert (:action plan)) (= (inc (count ol)) (count al))
                         :else (= (count ol) (count al)))))
        (println "trailing-newline preserved?"
                 (= (str/ends-with? content "\n") (str/ends-with? applied "\n")))))))

;; one insert (plain), one rewrite (java-tag), the block-scalar file (dan.md)
(show "anti-patterns/bypassing_scoped_diagnostics.md")
(show "bugs/project_import_drops_memory_namespace_prefix_bare_ident_2026_07_01.md")
(show "actors/dan.md")

;; enumerate the 20 no-frontmatter files (should be index/README/docs)
(println "\n\n=== no-frontmatter files (SKIP set) ===")
(let [files (->> (file-seq (io/file (str corpus "/memory")))
                 (filter #(.isFile %)) (map #(.getPath %))
                 (filter #(str/ends-with? % ".md")) sort)]
  (doseq [f files]
    (let [rel (subs f (count (str corpus "/memory/")))
          top (first (str/split rel #"/"))]
      (when (and (not (#{"audit-results" "memory"} top))
                 (nil? (first (bf/split-frontmatter (slurp f)))))
        (println "  " rel)))))
(println "\n[verify complete — no writes]")
