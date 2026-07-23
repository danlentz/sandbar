;; W1.D byte-correctness verification (in-memory; ZERO writes) — POST-FIX.
;;
;; Round-1's version was structurally BLIND: it compared "body byte-identical?"
;; by piping BOTH operands through split-frontmatter + (str/join "\n" ...), the
;; very lossy path that DROPS trailing blank lines and collapses CRLF — so it
;; could never see the corruption it was meant to catch.  This version:
;;   (1) HARNESS SELF-TEST — synthesize the exact pre-fix corruptions (dropped
;;       trailing blank line; CRLF->LF collapse) and assert verify-surgical now
;;       FLAGS them.  If the self-test fails, the harness is untrustworthy and we
;;       abort before reporting anything.
;;   (2) FULL SWEEP — run apply-plan + verify-surgical over EVERY actionable
;;       dogfood file; assert byte-drift == 0 (every non-id byte preserved).
;;   (3) BLAST-RADIUS — reconstruct the pre-fix (buggy) apply inline and count
;;       exactly how many actionable files it WOULD have corrupted, categorized;
;;       plus an EOF-shape census of the actionable set.
;; Per constraint (e): read-only; every write targets the scratchpad, never the corpus.
(require '[sandbar.migrate.id-backfill :as bf]
         '[clojure.edn :as edn]
         '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def scratch "/private/tmp/claude-501/-Users-dan-claude/0d71181d-485f-44de-838f-71c18aec0495/scratchpad/w1d-wt")
(def corpus "/Users/dan/claude")
(def id-map (:id-map (edn/read-string (slurp (str scratch "/db-id-map.edn")))))

;;;; ---- the PRE-FIX (buggy) apply-plan, reconstructed verbatim, to measure the
;;;; exact blast radius (files whose bytes the old code would have changed). ----
(defn old-apply-plan [content {:keys [action new-line current] :as _plan}]
  (let [lines (vec (str/split-lines content))
        trailing-nl? (or (str/ends-with? content "\n") (str/blank? content))
        rejoin (fn [ls] (cond-> (str/join "\n" ls) trailing-nl? (str "\n")))]
    (case action
      (:rewrite :conflict) (rejoin (assoc lines (inc (:idx current)) new-line))
      :insert (let [close (first (keep-indexed (fn [i l] (when (and (pos? i) (= "---" (str/trim l))) i)) lines))]
                (if close (rejoin (vec (concat (subvec lines 0 close) [new-line] (subvec lines close)))) content))
      content)))

;;;; ---- (1) HARNESS SELF-TEST — does verify-surgical DETECT the corruptions? ----
(println "=== (1) HARNESS SELF-TEST — verify-surgical must catch pre-fix corruption ===")
(def self-tests
  (let [u "facad8b8-728b-582d-ac01-f8c152d020f2"
        c-blank "---\nname: x\n---\nbody\n\n"           ; blank-line EOF
        c-crlf  "---\r\nname: x\r\n---\r\nbody\r\n"      ; CRLF
        p-blank (bf/plan-file c-blank u)
        p-crlf  (bf/plan-file c-crlf u)
        good-blank (bf/apply-plan c-blank p-blank)
        good-crlf  (bf/apply-plan c-crlf p-crlf)
        bug-blank  (old-apply-plan c-blank p-blank)     ; the OLD code's output
        bug-crlf   (old-apply-plan c-crlf p-crlf)]
    [["correct blank-EOF passes"  (:ok? (bf/verify-surgical c-blank good-blank p-blank)) true]
     ["pre-fix blank-EOF FLAGGED" (:ok? (bf/verify-surgical c-blank bug-blank  p-blank)) false]
     ["correct CRLF passes"       (:ok? (bf/verify-surgical c-crlf  good-crlf  p-crlf))  true]
     ["pre-fix CRLF FLAGGED"      (:ok? (bf/verify-surgical c-crlf  bug-crlf   p-crlf))  false]]))
(doseq [[label got want] self-tests]
  (println (format "  %-28s got ok?=%-5s  expect %-5s  %s" label got want (if (= got want) "PASS" "*** FAIL ***"))))
(when-not (every? (fn [[_ got want]] (= got want)) self-tests)
  (println "\n!!! HARNESS SELF-TEST FAILED — verify-surgical is not trustworthy; ABORTING.")
  (System/exit 1))
(println "  --> harness has teeth (it detects the pre-fix corruption it was blind to before).")

;;;; ---- corpus enumeration (dogfood scope) ----
(def files (->> (fs/glob (str corpus "/memory") "**.md") (map str) sort))
(defn dogfood? [f]
  (let [rel (subs f (count (str corpus "/memory/")))
        top (first (str/split rel #"/"))]
    (not (contains? #{"audit-results" "memory"} top))))

(def actionable
  (for [f files
        :when (dogfood? f)
        :let [rel (subs f (count (str corpus "/memory/")))
              content (slurp f)
              plan (bf/plan-file content (get id-map rel))]
        :when (#{:insert :rewrite} (:action plan))]
    {:f f :rel rel :content content :plan plan}))

;;;; ---- (2) FULL SWEEP — byte-surgical over every actionable file ----
(println "\n=== (2) FULL SWEEP — verify-surgical over every actionable dogfood file ===")
(def sweep
  (for [{:keys [f rel content plan]} actionable]
    (let [applied (bf/apply-plan content plan)
          v (bf/verify-surgical content applied plan)]
      {:rel rel :action (:action plan) :ok? (:ok? v) :reason (:reason v)})))
(def drift (remove :ok? sweep))
(println (format "  actionable files swept : %d" (count sweep)))
(println (format "  verify-surgical OK     : %d" (count (filter :ok? sweep))))
(println (format "  BYTE-DRIFT (must be 0) : %d" (count drift)))
(doseq [d (take 20 drift)] (println "   ! DRIFT" (:rel d) "—" (:reason d)))
(spit (str scratch "/verify-sweep.edn") (with-out-str (pp/pprint {:swept (count sweep)
                                                                  :ok (count (filter :ok? sweep))
                                                                  :drift (mapv #(dissoc % :ok?) drift)})))

;;;; ---- (3) BLAST RADIUS + EOF-shape census of the actionable set ----
(println "\n=== (3) BLAST RADIUS — files the PRE-FIX apply would have corrupted ===")
(defn eof-shape [content]
  (cond
    (re-find #"\r\n" content)          :crlf
    (not (str/ends-with? content "\n")) :no-final-nl
    (str/ends-with? content "\n\n")     :blank-line-eof   ; body ends in >=1 blank line
    :else                               :single-nl))
(def radius
  (for [{:keys [rel content plan]} actionable]
    (let [old (old-apply-plan content plan)
          new (bf/apply-plan content plan)]
      {:rel rel :corrupted? (not= old new) :shape (eof-shape content)})))
(def corrupted (filter :corrupted? radius))
(println (format "  actionable total            : %d" (count radius)))
(println (format "  WOULD-HAVE-BEEN-CORRUPTED   : %d  (old apply-plan output != new byte-exact output)" (count corrupted)))
(println "  --- corrupted, by EOF shape ---")
(doseq [[shape n] (sort-by (comp - val) (frequencies (map :shape corrupted)))]
  (println (format "      %-14s %d" (name shape) n)))
(println "  --- EOF-shape census of ALL actionable files ---")
(doseq [[shape n] (sort-by (comp - val) (frequencies (map :shape radius)))]
  (println (format "      %-14s %d" (name shape) n)))
(spit (str scratch "/blast-radius.edn")
      (with-out-str (pp/pprint {:actionable (count radius)
                                :would-corrupt (count corrupted)
                                :corrupted-by-shape (frequencies (map :shape corrupted))
                                :eof-census (frequencies (map :shape radius))
                                :corrupted-rels (mapv :rel corrupted)})))

;;;; ---- spot-check 3 representative files with the escaped, drift-aware diff ----
(println "\n=== SPOT-CHECK (escaped byte-delta diff; drift banner if any) ===")
(defn spot [rel]
  (let [f (str corpus "/memory/" rel)
        content (slurp f)
        plan (bf/plan-file content (get id-map rel))
        applied (bf/apply-plan content plan)]
    (println "########" rel "  (" (:action plan) ") shape=" (name (eof-shape content)))
    (print (bf/unified-diff rel content applied plan))
    (println "  verify-surgical:" (:reason (bf/verify-surgical content applied plan)))))
(doseq [r ["anti-patterns/bypassing_scoped_diagnostics.md"
           "bugs/project_import_drops_memory_namespace_prefix_bare_ident_2026_07_01.md"
           "actors/dan.md"]]
  (when (get id-map r) (spot r)))

(println "\n[verify complete — no corpus writes; artifacts: verify-sweep.edn, blast-radius.edn]")
