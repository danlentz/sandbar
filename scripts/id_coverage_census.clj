#!/usr/bin/env bb
;; W1.D id: frontmatter coverage census (READ-ONLY).
;; Globs the :mm/Memory subtree at <corpus>/memory via babashka.fs (NOT raw
;; find/grep), parses each file's frontmatter block, and categorizes its `id:`
;; line byte-form.  Zero writes.  Per the W1.D-slice task + the fidelity
;; pipeline (decisions/db_fs_emitter_fidelity_option_a_wire_dormant_frontmatter_carrier_2026_07_02).
;;
;; id: line taxonomy (the emitter's clean form is `id: '<uuid>'`, per
;; markdown.clj uuid->id-line):
;;   :clean-sq     id: '<uuid>'                 (canonical emitter form — GOOD)
;;   :double-q     id: "<uuid>"
;;   :bare         id: <uuid>                   (unquoted)
;;   :java-tag     id: !!java.util.UUID '<uuid>' (BAD — pre-fix java-tagged)
;;   :other-id     an id: line that isn't a UUID value
;;   :missing      frontmatter present, no id: line
;;   :no-fm        no frontmatter block at all
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.pprint])

(def corpus-root
  (or (first *command-line-args*) "/Users/dan/claude"))

(def memory-dir (str corpus-root "/memory"))

(def uuid-re #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

(defn split-frontmatter
  "Return [fm-lines body?] — fm-lines is the seq of lines between the leading
   `---` fence and its closing `---`, or nil when there's no frontmatter block."
  [content]
  (let [lines (str/split-lines content)]
    (when (and (seq lines) (= "---" (str/trim (first lines))))
      (let [rest-lines (rest lines)
            fm (take-while #(not= "---" (str/trim %)) rest-lines)
            closed? (some #(= "---" (str/trim %)) rest-lines)]
        (when closed? (vec fm))))))

(defn id-line-of [fm-lines]
  (first (filter #(re-find #"^id:\s" %) fm-lines)))

(defn classify-id [line]
  (cond
    (nil? line) :missing
    (re-find #"^id:\s*!!java\.util\.UUID\s" line) :java-tag
    (re-find (re-pattern (str "^id:\\s*'" uuid-re "'\\s*$")) line) :clean-sq
    (re-find (re-pattern (str "^id:\\s*\"" uuid-re "\"\\s*$")) line) :double-q
    (re-find (re-pattern (str "^id:\\s*" uuid-re "\\s*$")) line) :bare
    :else :other-id))

(defn type-of [fm-lines]
  (when-let [l (first (filter #(re-find #"^type:\s" %) fm-lines))]
    (str/trim (str/replace l #"^type:\s*" ""))))

;; Directories that are NOT :mm/Memory dogfood classes (working artifacts /
;; schema self-description / the accidental junk-twin).  Everything else under
;; memory/ that carries a `type:` line is a dogfood class.
(def non-dogfood-dirs #{"audit-results" "memory"}) ; memory/memory = junk-twin

(def files (->> (fs/glob memory-dir "**.md") (map str) sort))

(defn leaf-class [path]
  ;; class = first path segment under memory/
  (let [rel (subs path (inc (count memory-dir)))]
    (first (str/split rel #"/"))))

(def rows
  (for [f files]
    (let [content (slurp f)
          fm (split-frontmatter content)
          cls (leaf-class f)
          idl (when fm (id-line-of fm))
          cat (if fm (classify-id idl) :no-fm)]
      {:file f :class cls :type (when fm (type-of fm)) :cat cat
       :dogfood? (not (contains? non-dogfood-dirs cls))})))

(defn pct [n d] (if (zero? d) 0.0 (* 100.0 (/ (double n) d))))

(defn report [label rs]
  (let [total (count rs)
        by-cat (frequencies (map :cat rs))
        clean (get by-cat :clean-sq 0)
        java (get by-cat :java-tag 0)
        dq (get by-cat :double-q 0)
        bare (get by-cat :bare 0)
        other (get by-cat :other-id 0)
        missing (get by-cat :missing 0)
        nofm (get by-cat :no-fm 0)
        any-id (+ clean java dq bare other)]
    (println (format "\n=== %s ===" label))
    (println (format "  total files          : %d" total))
    (println (format "  clean single-quoted  : %4d  (%.1f%%)  <- canonical emitter form" clean (pct clean total)))
    (println (format "  double-quoted        : %4d  (%.1f%%)" dq (pct dq total)))
    (println (format "  bare/unquoted        : %4d  (%.1f%%)" bare (pct bare total)))
    (println (format "  java-tagged (BAD)    : %4d  (%.1f%%)  <- !!java.util.UUID" java (pct java total)))
    (println (format "  other id: (non-uuid) : %4d  (%.1f%%)" other (pct other total)))
    (println (format "  missing (fm, no id:) : %4d  (%.1f%%)" missing (pct missing total)))
    (println (format "  no frontmatter block : %4d  (%.1f%%)" nofm (pct nofm total)))
    (println (format "  --- ANY id: line     : %4d  (%.1f%%)" any-id (pct any-id total)))
    (println (format "  --- CLEAN id: (fix target=covered)   : %.1f%%" (pct clean total)))
    (println (format "  --- files LACKING clean id: (backfill candidates): %d" (- total clean)))))

(report "FULL CENSUS (all memory/**.md)" rows)
(report "DOGFOOD CLASSES ONLY (:mm/Memory subtree; excl audit-results/, memory/memory junk-twin)"
        (filter :dogfood? rows))

;; Per-class breakdown (dogfood only), sorted by file count desc.
(println "\n=== PER-CLASS (dogfood) — clean / java / missing / nofm / total ===")
(let [by-class (group-by :class (filter :dogfood? rows))]
  (doseq [[cls rs] (sort-by (comp - count second) by-class)]
    (let [bc (frequencies (map :cat rs))
          t (count rs)
          clean (get bc :clean-sq 0)]
      (println (format "  %-16s clean=%-4d java=%-3d dq=%-3d bare=%-3d miss=%-4d nofm=%-3d  total=%-4d  clean%%=%.0f"
                       cls clean (get bc :java-tag 0) (get bc :double-q 0) (get bc :bare 0)
                       (get bc :missing 0) (get bc :no-fm 0) t (pct clean t))))))

;; Emit machine-readable EDN summary for the migration tool to consume.
(let [dogfood (filter :dogfood? rows)
      out {:corpus-root corpus-root
           :total-files (count rows)
           :dogfood-files (count dogfood)
           :full-by-cat (frequencies (map :cat rows))
           :dogfood-by-cat (frequencies (map :cat dogfood))
           :backfill-candidates
           (->> dogfood
                (remove #(= :clean-sq (:cat %)))
                (map #(select-keys % [:file :class :type :cat])))}]
  (spit (or (second *command-line-args*)
            "/private/tmp/claude-501/-Users-dan-claude/0d71181d-485f-44de-838f-71c18aec0495/scratchpad/w1d-wt/census-summary.edn")
        (with-out-str (clojure.pprint/pprint out)))
  (println (format "\n[wrote census-summary.edn — %d dogfood backfill-candidate files]"
                   (count (:backfill-candidates out)))))
