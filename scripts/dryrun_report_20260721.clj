;; Seat D:backfill-dryrun (2026-07-21 fleet) — dry-run report driver.
;; Exercises the REAL sandbar.migrate.id-backfill tool (mainline @18a8fd3,
;; w1d slice merged @b4e6b1b) against the live corpus + the read-only DB
;; id-map dump.  Writes NOTHING to the corpus; emits report artifacts to the
;; seat scratchpad worktree only.  Adapted from scripts/dryrun_report.clj
;; (w1d seat) with: seat paths, per-DIRECTORY breakdown (full dirname, not
;; just top-level class), idempotence sweep over ALL actionable plans, and a
;; machine-readable summary EDN for the lane REPORT.md.
(require '[sandbar.migrate.id-backfill :as bf]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.pprint :as pp])

(def scratch "/private/tmp/claude-501/-Users-dan-claude/f6032830-453a-4550-a1ed-5d52b1d035da/scratchpad/d-backfill-dryrun/out")
(def corpus-root "/Users/dan/claude")

(def t0 (java.time.Instant/now))

(def dump (edn/read-string (slurp (str scratch "/db-id-map.edn"))))
(def id-map (:id-map dump))

(def files
  (->> (file-seq (io/file (str corpus-root "/memory")))
       (filter #(.isFile ^java.io.File %))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".md"))
       sort))

(def result (bf/run {:corpus-root corpus-root :id-map id-map :files files}))
(def plans (:plans result))
(def stats (:stats result))

(defn by-action [a] (filter #(= a (:action %)) plans))

(println "=== id: BACKFILL FRESH DRY-RUN 2026-07-21 (real tool, real DB id-map; ZERO writes) ===")
(println "probe instant :" (str t0))
(println "corpus-root   :" corpus-root)
(println "id-map source :" (str scratch "/db-id-map.edn")
         "generated" (:generated dump) "db-basis-t" (:db-basis-t dump))
(println "id-map size   :" (count id-map) "(distinct rel-paths with :mm/id in live DB)")
(println "md files under memory/ (pre-filter):" (count files))
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

(defn top-class [p] (first (str/split (:rel p) #"/")))
(defn dir-of [p]
  (let [rel (:rel p)
        i   (str/last-index-of rel "/")]
    (if i (subs rel 0 i) "(top-level)")))

;; per-class actionable breakdown (top-level dir under memory/)
(println "\n--- actionable (insert+rewrite) per top-level class dir ---")
(let [act (filter #(#{:insert :rewrite} (:action %)) plans)]
  (doseq [[c ps] (sort-by (comp - count second) (group-by top-class act))]
    (let [ins (count (filter #(= :insert (:action %)) ps))
          rew (count (filter #(= :rewrite (:action %)) ps))]
      (println (format "  %-16s insert=%-4d rewrite=%-3d  total=%-4d" c ins rew (count ps))))))

;; per-DIRECTORY actionable breakdown (full dirname under memory/)
(println "\n--- actionable (insert+rewrite) per directory (full dirname) ---")
(let [act (filter #(#{:insert :rewrite} (:action %)) plans)]
  (doseq [[d ps] (sort-by (juxt (comp - count second) first) (group-by dir-of act))]
    (let [ins (count (filter #(= :insert (:action %)) ps))
          rew (count (filter #(= :rewrite (:action %)) ps))]
      (println (format "  %-56s insert=%-4d rewrite=%-3d total=%-4d" d ins rew (count ps))))))

;; full per-directory ALL-action matrix (for the lane report)
(def per-dir-matrix
  (into (sorted-map)
        (for [[d ps] (group-by dir-of plans)]
          [d (merge {:total (count ps)}
                    (frequencies (map :action ps)))])))

;; write all diffs
(spit (str scratch "/id-backfill.dryrun.patch") (str/join "\n" (:diffs result)))
(println (format "\n[wrote id-backfill.dryrun.patch — %d diffs]" (count (:diffs result))))

;; write full plans EDN (audit trail)
(spit (str scratch "/id-backfill.plans.edn")
      (with-out-str (pp/pprint (mapv #(select-keys % [:rel :action :db-id :reason]) plans))))

;; conflict report (clean id: on disk that DISAGREES with DB) — safety-critical
(def conflicts (by-action :conflict))
(spit (str scratch "/id-backfill.conflicts.edn")
      (with-out-str (pp/pprint (mapv #(select-keys % [:rel :reason :db-id :current]) conflicts))))
(println (format "[CONFLICTS: %d — on-disk clean id: != DB :mm/id; NOT auto-applied]" (count conflicts)))
(doseq [c (take 20 conflicts)]
  (println "   !" (:rel c) "\n      " (:reason c)))

;; no-db-id report grouped by class (mint-candidates)
(def nodb (by-action :no-db-id))
(println "\n--- no-db-id (cannot backfill; mint-candidates) per top-level class dir ---")
(doseq [[c ps] (sort-by (comp - count second) (group-by top-class nodb))]
  (println (format "  %-16s %4d" c (count ps))))
(spit (str scratch "/id-backfill.no-db-id.edn")
      (with-out-str (pp/pprint (mapv :rel nodb))))

;; rewrite-form breakdown (what legacy forms are being normalized)
(println "\n--- rewrite source-form breakdown ---")
(doseq [[form n] (sort-by (comp - second)
                          (frequencies (map (comp :form :current) (by-action :rewrite))))]
  (println (format "  %-10s %4d" (name form) n)))

;; sample diffs (first 3 inserts + first 3 rewrites) — derived from ACTUAL
;; before/after bytes (escaped), so any non-target drift is visible.
(println "\n=== SAMPLE DIFFS (escaped byte-delta; drift banner if any) ===")
(doseq [p (concat (take 3 (by-action :insert)) (take 3 (by-action :rewrite)))]
  (let [content (slurp (:file p))
        applied (bf/apply-plan content p)]
    (println (bf/unified-diff (:rel p) content applied p))))

;; idempotence proof over ALL actionable plans: apply in-memory, re-plan,
;; expect :skip-clean for every one.
(println "=== IDEMPOTENCE SWEEP (apply-plan in-memory, re-plan, ALL actionable) ===")
(let [act (filter #(#{:insert :rewrite} (:action %)) plans)
      re-tally (frequencies
                (for [p act]
                  (let [content (slurp (:file p))
                        applied (bf/apply-plan content p)]
                    (:action (bf/plan-file applied (:db-id p))))))]
  (println "  re-plan tally over" (count act) "actionable files:" re-tally)
  (println (if (= #{:skip-clean} (set (keys re-tally)))
             "  IDEMPOTENT — every applied plan re-classifies :skip-clean"
             "  *** NOT IDEMPOTENT — investigate ***")))

(def t1 (java.time.Instant/now))

;; machine-readable summary for the lane REPORT.md
(spit (str scratch "/dryrun-summary.edn")
      (with-out-str
        (pp/pprint
         {:probe-start (str t0)
          :probe-end   (str t1)
          :corpus-root corpus-root
          :id-map      {:path (str scratch "/db-id-map.edn")
                        :generated (:generated dump)
                        :db-basis-t (:db-basis-t dump)
                        :uri (:uri dump)
                        :count-with-id (:count-with-id dump)
                        :count-all-relpath (:count-all-relpath dump)
                        :count-no-id (:count-no-id dump)}
          :md-files-prefilter (count files)
          :stats stats
          :per-dir-matrix per-dir-matrix
          :conflict-rels (mapv :rel conflicts)
          :no-db-id-count (count nodb)})))
(println "\n[wrote dryrun-summary.edn]")
(println "[dry-run complete — no corpus writes]  elapsed:" (str (java.time.Duration/between t0 t1)))
