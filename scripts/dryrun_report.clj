;; W1.D dry-run report driver — exercises the REAL sandbar.migrate.id-backfill
;; tool against the live corpus + the read-only DB id-map dump.  Writes NOTHING
;; to the corpus; emits report artifacts to the scratchpad only.
(require '[sandbar.migrate.id-backfill :as bf]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def scratch "/private/tmp/claude-501/-Users-dan-claude/0d71181d-485f-44de-838f-71c18aec0495/scratchpad/w1d-wt")
(def corpus-root "/Users/dan/claude")

(def id-map (:id-map (edn/read-string (slurp (str scratch "/db-id-map.edn")))))

(def files
  (->> (file-seq (io/file (str corpus-root "/memory")))
       (filter #(.isFile %))
       (map #(.getPath %))
       (filter #(str/ends-with? % ".md"))
       sort))

(def result (bf/run {:corpus-root corpus-root :id-map id-map :files files}))
(def plans (:plans result))
(def stats (:stats result))

(defn by-action [a] (filter #(= a (:action %)) plans))

(println "=== W1.D id: BACKFILL DRY-RUN (real tool, real DB id-map; ZERO writes) ===")
(println "corpus-root :" corpus-root)
(println "id-map size :" (count id-map) "(distinct rel-paths with :mm/id in live DB)")
(println "files scanned (dogfood memory/**.md):" (count plans))
(println "\n--- action tally ---")
(doseq [a [:skip-clean :insert :rewrite :conflict :no-db-id :no-frontmatter]]
  (println (format "  %-15s %5d" (name a) (count (by-action a)))))
(println (format "  %-15s %5d" "ACTIONABLE" (:actionable stats)))

;; byte-fidelity gate: run byte-verifies every actionable file in memory (no write)
(println "\n--- byte-fidelity (verify-surgical over every actionable file; 0 writes) ---")
(println (format "  byte-clean : %5d" (:byte-clean stats)))
(println (format "  byte-drift : %5d %s" (:byte-drift stats)
                 (if (zero? (:byte-drift stats)) "(OK — every non-id byte preserved)" "*** NON-SURGICAL DRIFT DETECTED ***")))
(when (pos? (:byte-drift stats))
  (doseq [p (filter #(and (#{:insert :rewrite} (:action %)) (false? (:byte-ok? %))) plans)]
    (println "   ! DRIFT" (:rel p) "—" (:byte-check p))))

;; per-class actionable breakdown
(println "\n--- actionable (insert+rewrite) per class ---")
(let [cls (fn [p] (first (str/split (:rel p) #"/")))
      act (filter #(#{:insert :rewrite} (:action %)) plans)]
  (doseq [[c ps] (sort-by (comp - count second) (group-by cls act))]
    (let [ins (count (filter #(= :insert (:action %)) ps))
          rew (count (filter #(= :rewrite (:action %)) ps))]
      (println (format "  %-16s insert=%-4d rewrite=%-3d  total=%-4d" c ins rew (count ps))))))

;; write all diffs
(spit (str scratch "/id-backfill.dryrun.patch") (str/join "\n" (:diffs result)))
(println "\n[wrote id-backfill.dryrun.patch —" (count (:diffs result)) "diffs]")

;; write full plans EDN (audit trail)
(spit (str scratch "/id-backfill.plans.edn")
      (with-out-str (pp/pprint (mapv #(select-keys % [:rel :action :db-id :reason]) plans))))

;; conflict report (clean id: on disk that DISAGREES with DB) — safety-critical
(let [conflicts (by-action :conflict)]
  (spit (str scratch "/id-backfill.conflicts.edn")
        (with-out-str (pp/pprint (mapv #(select-keys % [:rel :reason :db-id :current]) conflicts))))
  (println "[CONFLICTS:" (count conflicts) "— on-disk clean id: != DB :mm/id; NOT auto-applied]")
  (doseq [c (take 20 conflicts)]
    (println "   !" (:rel c) "\n      " (:reason c))))

;; no-db-id report grouped by class (mint-candidates)
(let [nodb (by-action :no-db-id)
      cls (fn [p] (first (str/split (:rel p) #"/")))]
  (println "\n--- no-db-id (cannot backfill; mint-candidates) per class ---")
  (doseq [[c ps] (sort-by (comp - count second) (group-by cls nodb))]
    (println (format "  %-16s %4d" c (count ps))))
  (spit (str scratch "/id-backfill.no-db-id.edn")
        (with-out-str (pp/pprint (mapv :rel nodb)))))

;; sample diffs (first 3 inserts + first 3 rewrites) for eyeball — derived from
;; the ACTUAL before/after bytes (escaped), so any non-target drift is visible.
(println "\n=== SAMPLE DIFFS (escaped byte-delta; drift banner if any) ===")
(doseq [p (concat (take 3 (by-action :insert)) (take 3 (by-action :rewrite)))]
  (let [content (slurp (:file p))
        applied (bf/apply-plan content p)]
    (println (bf/unified-diff (:rel p) content applied p))))

;; idempotence proof: re-plan the inserts/rewrites AS IF applied, confirm they
;; then classify :skip-clean.
(println "=== IDEMPOTENCE PROOF (apply-plan in-memory, re-plan) ===")
(let [sample (take 5 (filter #(#{:insert :rewrite} (:action %)) plans))]
  (doseq [p sample]
    (let [content (slurp (:file p))
          applied (bf/apply-plan content p)
          re (bf/plan-file applied (:db-id p))]
      (println (format "  %-60s %s -> reapply=%s"
                       (subs (:rel p) 0 (min 60 (count (:rel p))))
                       (name (:action p)) (name (:action re)))))))
(println "\n[dry-run complete — no corpus writes]")
