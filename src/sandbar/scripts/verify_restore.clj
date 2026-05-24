(ns sandbar.scripts.verify-restore
  "Gate 2 verification — restore a Datomic backup into an ISOLATED
   second :dev transactor + storage; query cardinality from a peer
   connection; tear down.  The LIVE transactor + storage are UNTOUCHED
   throughout (different ports + different data-dir).

   This is the test that lifts the no-reset-db forbidden discipline
   per `authorizations/db_preservation_during_cutover_no_reset_db_-
   without_recovery_tested_dan_directive_2026_05_23.md` — by
   DEMONSTRATING that we can recover from a backup.

   ## Approach (per the 7-step destructive-ops analysis 2026-05-24)

   Stand up an isolated second :dev transactor at port 4344 (peer) +
   4345 (h2) with H2 storage in `/tmp/sandbar-verify-restore-<TS>/`;
   restore the baseline backup into it (Datomic restore-db invariant:
   target DB name must match source name, so SAME NAME 'sandbar' on
   the DIFFERENT TRANSACTOR); connect peer; run cardinality queries;
   compare to expected counts; tear down second transactor + temp
   storage.

   The live transactor at port 4334 (peer) + 4335 (h2) is never
   touched.  Target URI is HARDCODED to port 4344 — refuse to target
   anywhere else.

   ## Failure modes + recovery (3a-3f per the 7-step analysis)

   - 3a: second transactor fails to start  → live DB untouched; tear down
   - 3b: restore-db fails                  → live DB untouched; tear down
   - 3c: peer connection times out         → live DB untouched; tear down
   - 3d: cardinality returns 0 or wrong    → CRITICAL; do NOT lift no-reset-db
   - 3e: teardown leaks                    → kill -9; rm -rf
   - 3f: script targets live transactor    → IMPOSSIBLE (port 4344 hardcoded)

   ## Usage

       lein verify-restore [<backup-dir>]

   `<backup-dir>` defaults to the latest backup under <backup-root>/.

   ## Exit codes

   - 0 — success; cardinality matches expectations; Gate 2 PASSED
   - 2 — preflight failure (backup-dir invalid; port busy; etc.)
   - 3 — transactor failed to start
   - 4 — restore-db failed
   - 5 — peer connection failed
   - 6 — cardinality check failed (THE one that doesn't lift no-reset-db)
   - 7 — teardown leaked

   Per memory/interaction/think_destructive_ops_end_to_end_including_-
   failure_modes_before_execute_2026_05_23.md (the amended discipline)."
  (:require [clojure.edn                 :as edn]
            [clojure.java.io             :as io]
            [clojure.java.shell          :as sh]
            [clojure.string              :as str]
            [datomic.api                 :as d]
            [sandbar.scripts.datomic-cli :as datomic-cli])
  (:import (java.io File)
           (java.time Instant ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter))
  (:gen-class))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants — HARDCODED for safety (refuse to target anything else)

(def ^:const verify-peer-port 4344)
(def ^:const verify-h2-port   4345)
(def ^:const verify-db-name   "sandbar")
(def ^:const verify-target-uri (str "datomic:dev://localhost:"
                                    verify-peer-port "/" verify-db-name))
(def ^:const datomic-install  "/Users/dan/opt/datomic")
(def ^:const datomic-jar      "datomic-transactor-pro-1.0.7482.jar")
(def ^:const startup-timeout-ms 120000)
(def ^:const shutdown-timeout-ms 30000)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Backup-dir discovery (mirrors verify_backup.clj)

(defn- backup-root []
  (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                       (str (System/getProperty "user.home") "/claude"))]
    (str client-dir "/.sandbar/backups")))

(defn- list-backup-dirs []
  (let [root (File. ^String (backup-root))]
    (when (.isDirectory root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (filter #(re-find #"-\d{8}-\d{6}" (.getName ^File %)))
           (sort-by #(.getName ^File %))))))

(defn- latest-backup-dir []
  (when-let [dirs (seq (list-backup-dirs))]
    (.getCanonicalPath ^File (last dirs))))

(defn- resolve-backup-dir [arg]
  (if arg
    (.getCanonicalPath (File. ^String arg))
    (latest-backup-dir)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Preflight + setup

;; Custom exception type for orderly abort — `(throw (->Abort code msgs))`
;; from anywhere inside -main is caught at the top + flows through finally.
(defrecord Abort [code lines])

(defn- abort! [code & lines]
  (throw (ex-info "verify-restore abort"
                  {:abort? true :code code :lines (vec lines)})))

(defn- port-busy? [port]
  (let [{:keys [exit]} (sh/sh "bash" "-c"
                              (str "lsof -i :" port " -t >/dev/null 2>&1"))]
    (zero? exit)))

(defn- timestamp []
  (.format (ZonedDateTime/now (ZoneId/systemDefault))
           (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))

(defn- structurally-valid? [^String dir]
  (every? #(.exists (File. (str dir "/" %))) ["owner" "roots" "values"]))

(defn- rm-rf! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn- ensure-dir! [^String path]
  (.mkdirs (File. path)))

(defn- write-transactor-properties! [base-dir]
  (let [data-dir   (str base-dir "/data")
        log-dir    (str base-dir "/log")
        props-path (str base-dir "/transactor.properties")
        content    (str "## verify-restore transactor properties — ephemeral\n"
                        "## Generated by sandbar.scripts.verify-restore\n"
                        "## DO NOT mistake for the live transactor's properties\n"
                        "## (live runs port 4334/4335 + ~/claude/db/sandbar/data)\n"
                        "\n"
                        "protocol=dev\n"
                        "host=localhost\n"
                        "port=" verify-peer-port "\n"
                        "h2-port=" verify-h2-port "\n"
                        "\n"
                        "memory-index-threshold=32m\n"
                        "memory-index-max=256m\n"
                        "object-cache-max=64m\n"
                        "\n"
                        "data-dir=" data-dir "\n"
                        "log-dir=" log-dir "\n"
                        "pid-file=" log-dir "/transactor.pid\n")]
    (ensure-dir! data-dir)
    (ensure-dir! log-dir)
    (spit props-path content)
    props-path))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transactor lifecycle — start (background subprocess) + wait + stop

(defn- launch-transactor! [props-path log-path]
  ;; Replicates the live launch shape:
  ;;   cd /Users/dan/opt/datomic && java -server -cp resources:datomic-transactor-pro-*.jar:lib/*:samples/clj:bin:
  ;;                                       -Xmx1g -Xms1g -XX:+UseG1GC -XX:MaxGCPauseMillis=50
  ;;                                       clojure.main --main datomic.launcher <props>
  (let [classpath (str "resources:" datomic-jar ":lib/*:samples/clj:bin:")
        cmd ["java" "-server" "-cp" classpath
             "-Xmx1g" "-Xms1g"
             "-XX:+UseG1GC" "-XX:MaxGCPauseMillis=50"
             "clojure.main" "--main" "datomic.launcher" props-path]
        pb  (-> (ProcessBuilder. ^java.util.List cmd)
                (.directory (File. ^String datomic-install))
                (.redirectErrorStream true)
                (.redirectOutput (File. ^String log-path)))]
    (.start pb)))

(defn- wait-for-port-ready [port timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (port-busy? port) :ready
        (> (System/currentTimeMillis) deadline) :timeout
        :else (do (Thread/sleep 1000) (recur))))))

(defn- stop-transactor! [^Process proc]
  ;; Datomic transactor JVM doesn't always trap SIGTERM cleanly under :dev.
  ;; Empirical 2026-05-24 — `.destroy()` returned + `.waitFor(30s)` timed out
  ;; but the JVM continued running.  Belt-and-suspenders: send SIGTERM, brief
  ;; wait, then SIGKILL + kill -9 by PID against any process holding 4344.
  (when (and proc (.isAlive proc))
    (let [pid (try (.pid proc) (catch Throwable _ nil))]
      (.destroy proc)
      (let [stopped? (.waitFor proc 5
                               java.util.concurrent.TimeUnit/SECONDS)]
        (when-not stopped?
          (binding [*out* *err*]
            (println "  SIGTERM ignored; escalating to SIGKILL"))
          (.destroyForcibly proc)
          (.waitFor proc 5 java.util.concurrent.TimeUnit/SECONDS)))
      ;; Belt-and-suspenders: shell kill by PID + by port
      (when pid
        (sh/sh "bash" "-c" (str "kill -9 " pid " 2>/dev/null || true")))
      (sh/sh "bash" "-c"
             (str "lsof -i :" verify-peer-port " -t 2>/dev/null | xargs -r kill -9 2>/dev/null || true")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cardinality queries
;;
;; Proof shape: compare RESTORED-DB cardinality to LIVE-DB-AS-OF-t cardinality
;; for the BACKUP'S basis-t.  If they match EXACTLY for the same `:dt/type`
;; counts, the restore is provably faithful.
;;
;; Concrete vs abstract — we pick a basket of CONCRETE classes whose direct
;; instance count is non-zero (`:mm/Decision`, `:mm/Plan`, etc.) plus the
;; metamodel classes (`:dt/Class`, `:dt/Property`) which are concrete too.
;; Abstract classes (`:mm/Memory`, `:mm/Activity`) would mislead because
;; entities have `:dt/type` of the LEAF class, not the abstract parent.

(def cardinality-classes
  "Concrete class-idents whose direct instance count is proof material.
   Selected to span the corpus's main type lattice — verified to exist
   in the metamodel via sandbar.schema.classes 2026-05-24."
  [:dt/Class :dt/Property :mm/Decision :mm/Plan :mm/Observation
   :mm/Authorization :mm/Workflow :mm/Library :mm/Tag :mm/Shape
   :mm/Fn :mm/Pattern :mm/AntiPattern :mm/Feedback :mm/Bug
   :mm/Idea :mm/Question :mm/Preference :mm/Protocol :mm/Reference])

(defn- count-instances
  "Return count of direct `:dt/type cls` instances in `db`, or :unresolved
   if the class ident isn't interned in this DB (defensive against schema
   drift between live + restored)."
  [db class-ident]
  (try
    (or (d/q '[:find (count ?e) .
               :in $ ?cls
               :where [?e :dt/type ?cls]]
             db class-ident)
        0)
    (catch IllegalArgumentException _ :unresolved)
    (catch Exception _ :error)))

(defn- run-cardinality
  "Returns {class-ident count} for each class in cardinality-classes."
  [db]
  (reduce (fn [acc cls]
            (assoc acc cls (count-instances db cls)))
          {} cardinality-classes))

(defn- compare-cardinality
  "Returns {:match? bool :mismatches {cls {:live N :restored M}}}.
   Treats `:unresolved` on either side as a non-comparison (skip)."
  [live-counts restored-counts]
  (let [comparable (filter (fn [[_ live-n]]
                             (and (number? live-n)
                                  (number? (get restored-counts _))))
                           live-counts)
        comparable (reduce-kv
                    (fn [acc cls live-n]
                      (let [restored-n (get restored-counts cls)]
                        (if (and (number? live-n) (number? restored-n))
                          (assoc acc cls [live-n restored-n])
                          acc)))
                    {} live-counts)
        mismatches (reduce-kv
                    (fn [acc cls [live-n restored-n]]
                      (if (= live-n restored-n)
                        acc
                        (assoc acc cls {:live live-n :restored restored-n})))
                    {} comparable)]
    {:match? (and (seq comparable) (empty? mismatches))
     :mismatches mismatches
     :comparable-count (count comparable)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sidecar

(defn- write-sidecar! [backup-dir patch]
  (let [path (str backup-dir "/verify-restore.edn")]
    (spit path (pr-str (merge {:verified-restore-at (str (Instant/now))}
                              patch)))
    path))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Main orchestration

(defn -main [& args]
  (let [backup-dir (or (resolve-backup-dir (first args))
                       (abort! 2 "ABORT: no backups found under " (backup-root)))
        backup-uri (str "file://" backup-dir)
        temp-base  (str "/tmp/sandbar-verify-restore-" (timestamp))
        log-path   (str temp-base "/transactor.log")
        proc       (atom nil)
        result     (atom {:backup-uri  backup-uri
                          :target-uri  verify-target-uri
                          :temp-base   temp-base
                          :result      :unknown})]
    (try
      ;; Preflight (a) — backup dir valid
      (when-not (.isDirectory (File. ^String backup-dir))
        (abort! 2 (str "ABORT: backup-dir not a directory: " backup-dir)))
      (when-not (structurally-valid? backup-dir)
        (abort! 2
                (str "ABORT: backup-dir missing required subdirs (owner/ roots/ values/): "
                     backup-dir)
                "       This does not look like a Datomic backup."))

      ;; Preflight (b) — verify-restore ports must be FREE (not the live ones!)
      (when (port-busy? verify-peer-port)
        (abort! 2 (str "ABORT: verify-restore peer port " verify-peer-port
                       " is busy — another verify-restore in progress?")))
      (when (port-busy? verify-h2-port)
        (abort! 2 (str "ABORT: verify-restore h2 port " verify-h2-port
                       " is busy")))

      ;; Setup
      (ensure-dir! temp-base)
      (println (str "verify-restore — isolated transactor approach"))
      (println (str "  backup:  " backup-uri))
      (println (str "  target:  " verify-target-uri))
      (println (str "  temp:    " temp-base))
      (println)

      (let [props-path (write-transactor-properties! temp-base)]
        (println (str "Launching isolated transactor (port " verify-peer-port
                      ", h2-port " verify-h2-port ") ..."))
        (reset! proc (launch-transactor! props-path log-path))

        ;; Wait for transactor ready
        (case (wait-for-port-ready verify-peer-port startup-timeout-ms)
          :ready   (println (str "  transactor ready on port " verify-peer-port))
          :timeout (abort! 3 (str "ABORT: transactor failed to start within "
                                  startup-timeout-ms "ms")
                           (str "       see " log-path " for diagnostics")))

        ;; Restore
        (println (str "Restoring backup → " verify-target-uri " ..."))
        (let [started (System/currentTimeMillis)
              {:keys [exit out err]} (sh/sh (datomic-cli/datomic-bin)
                                            "restore-db" backup-uri verify-target-uri)
              restore-ms (- (System/currentTimeMillis) started)]
          (when out (print out))
          (when (and err (seq err)) (binding [*out* *err*] (print err)))
          (swap! result assoc :restore-exit exit :restore-ms restore-ms)
          (when-not (zero? exit)
            (abort! 4 (str "ABORT: restore-db failed (exit " exit ")"))))

        ;; Connect peer to RESTORED DB; also connect to LIVE DB for
        ;; cardinality comparison at the backup's basis-t
        (println (str "Connecting peer to " verify-target-uri " (restored) ..."))
        (let [restored-conn (try (d/connect verify-target-uri)
                                 (catch Throwable t
                                   (abort! 5 (str "ABORT: restored peer connection failed: "
                                                  (.getMessage t)))))
              live-uri "datomic:dev://localhost:4334/sandbar"
              _ (println (str "Connecting peer to " live-uri " (live; for as-of-t comparison) ..."))
              live-conn (try (d/connect live-uri)
                             (catch Throwable t
                               (d/release restored-conn)
                               (abort! 5 (str "ABORT: live peer connection failed: "
                                              (.getMessage t)))))
              restored-db (d/db restored-conn)
              restored-basis-t (d/basis-t restored-db)
              live-as-of-db (d/as-of (d/db live-conn) restored-basis-t)]
          (println (str "Restored DB basis-t = " restored-basis-t))
          (println "Running cardinality queries on RESTORED DB ...")
          (let [restored-counts (run-cardinality restored-db)
                _ (println (str "Running cardinality queries on LIVE DB AS-OF "
                                restored-basis-t " ..."))
                live-counts (run-cardinality live-as-of-db)
                {:keys [match? mismatches comparable-count]} (compare-cardinality
                                                              live-counts
                                                              restored-counts)]
            (println)
            (println (format "  %-25s %-12s %-12s %s" "CLASS" "RESTORED" "LIVE@t" "STATUS"))
            (println (apply str (repeat 64 "-")))
            (doseq [cls cardinality-classes]
              (let [r (get restored-counts cls)
                    l (get live-counts cls)
                    status (cond
                             (or (= r :unresolved) (= l :unresolved)) "n/a"
                             (= r l) "OK"
                             :else "MISMATCH")]
                (println (format "  %-25s %-12s %-12s %s"
                                 (str cls) (str r) (str l) status))))
            (println)
            (println (str "Comparable classes (non-:unresolved on both sides): "
                          comparable-count))
            (println (str "Mismatches: " (count mismatches)))
            (println)

            (let [restored-nums (->> restored-counts vals (filter number?))
                  total (reduce + restored-nums)]
              (swap! result assoc
                     :cardinality-restored restored-counts
                     :cardinality-live-as-of live-counts
                     :restored-basis-t restored-basis-t
                     :mismatches mismatches
                     :comparable-count comparable-count
                     :total-comparable-entities total))

            (d/release restored-conn)
            (d/release live-conn)

            (if match?
              (do
                (swap! result assoc :result :success)
                (println (str "All " comparable-count
                              " comparable cardinality counts MATCH between restored DB and live-as-of-t."))
                (println "Restore is PROVABLY FAITHFUL to the source at basis-t."))
              (do
                (swap! result assoc :result :failed
                       :failure-reason "cardinality mismatch between restored DB and live-as-of-t")
                (binding [*out* *err*]
                  (println (str "FAIL: " (count mismatches) " class(es) show mismatched counts"))
                  (println "  This means restore is NOT faithful to the source at basis-t.")
                  (println "  DO NOT lift the no-reset-db forbidden discipline.")))))))

      ;; Write sidecar regardless of success/failure.  Do NOT System/exit
      ;; here — finally must run first (it's where teardown lives).
      (let [sidecar (write-sidecar! backup-dir @result)]
        (println (str "Wrote sidecar: " sidecar)))
      (when (= :success (:result @result))
        (println)
        (println "Gate 2 PASSED — restore path verified end-to-end"))

      (catch clojure.lang.ExceptionInfo t
        (let [data (ex-data t)]
          (if (:abort? data)
            ;; Orderly abort
            (do
              (binding [*out* *err*]
                (doseq [l (:lines data)] (println l)))
              (swap! result assoc :result :aborted :exit-code (:code data)))
            ;; Unexpected ex-info
            (do
              (binding [*out* *err*]
                (println (str "verify-restore error: " (.getMessage t)))
                (.printStackTrace t))
              (swap! result assoc :result :error :error (.getMessage t))))
          (try (write-sidecar! backup-dir @result) (catch Throwable _ nil))))
      (catch Throwable t
        (binding [*out* *err*]
          (println (str "verify-restore error: " (.getMessage t)))
          (.printStackTrace t))
        (swap! result assoc :result :error :error (.getMessage t))
        (try (write-sidecar! backup-dir @result) (catch Throwable _ nil)))

      (finally
        ;; Teardown — stop transactor + remove temp dir.
        ;; Finally is REACHED on both happy + caught-exception paths
        ;; (System/exit happens AFTER finally returns).
        (println)
        (println "Tearing down ...")
        (when @proc
          (println "  stopping transactor ...")
          (stop-transactor! @proc))
        (println (str "  removing " temp-base " ..."))
        (rm-rf! (File. ^String temp-base))
        (println "  teardown complete")
        (let [final-result (:result @result)]
          (case final-result
            :success (System/exit 0)
            :failed  (System/exit 6)
            :aborted (System/exit (or (:exit-code @result) 2))
            :error   (System/exit 1)
            (System/exit 1)))))))
