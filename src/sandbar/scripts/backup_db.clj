(ns sandbar.scripts.backup-db
  "Incremental Datomic backup via `bin/datomic backup-db` (admin JVM).

   Usage:

       lein backup-db [<label>]

   Writes to `<backup-root>/sandbar-<TS>[-<label>]-datomic1.0.7482/`
   where `<backup-root>` is `${SANDBAR_CLIENT_DIR:-$HOME/claude}/.sandbar/backups`
   and `<TS>` is `yyyyMMdd-HHmmss`.  Acquires a PID-file lock to refuse
   concurrent backup/restore on the same URI.  Live-system safe (transactor
   stays up).  Idempotent at the URI granularity per backup-db's incremental
   segment-copy semantics — but each invocation here writes to a FRESH
   directory (TS-stamped), so repeat invocations produce independent restore
   points rather than incrementing one.

   Writes sidecar `status.edn` to the backup dir capturing
   `{:duration-ms ... :segments-copied ... :exit-code ... :datomic-version ...
     :source-uri ... :backup-uri ... :timestamp ... :label ...}`.

   LOCK LIFECYCLE (fixed 2026-09-19).  The lock is released in a `finally`
   and `System/exit` is called only AFTER that block has run.  Before the
   fix every exit path called `System/exit` inside the `try`, and finally
   blocks do not run on `System/exit`, so the lock survived every run —
   including successful ones — and the next backup aborted on a dead PID
   until an operator deleted the file (bugs/backup_db_leaves_its_lock_file_-
   behind_after_a_successful_run_... 2026-09-19).  A lock whose recorded
   PID is no longer alive is now reported as stale and taken over; only a
   lock whose PID is alive aborts (exit 3).

   Per memory/libraries/datomic/backup_restore.md §12 + Dan-directive
   2026-05-23 (authorizations/c_then_a_backup_restore_foundation_then_job_-
   to_mm_migration_no_parallel_stacks_dan_directive_2026_05_23.md)."
  (:require [clojure.java.shell          :as sh]
            [clojure.string              :as str]
            [sandbar.db.datomic          :as db]
            [sandbar.scripts.datomic-cli :as datomic-cli])
  (:import (java.io File)
           (java.time Instant ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter))
  (:gen-class))

(def ^:const datomic-version "1.0.7482")

(defn- backup-root []
  (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                       (str (System/getProperty "user.home") "/claude"))]
    (str client-dir "/.sandbar/backups")))

(defn- timestamp []
  (.format (ZonedDateTime/now (ZoneId/systemDefault))
           (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss")))

(defn- dir-name [sid ts label]
  (str sid "-" ts
       (when (and label (not (str/blank? label))) (str "-" label))
       "-datomic" datomic-version))

(defn- ensure-dir! [^String path]
  (.mkdirs (File. path)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lock
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn lock-holder-pid
  "PID recorded in lock file `f`, or nil when the file is unreadable or does
   not hold a number."
  [^File f]
  (try (Long/parseLong (str/trim (slurp f)))
       (catch Exception _ nil)))

(defn pid-alive?
  "True when a process with `pid` exists and is alive on this machine.
   nil / unknown pids are never alive."
  [pid]
  (boolean
   (when pid
     (try
       (let [h (java.lang.ProcessHandle/of (long pid))]
         (and (.isPresent h) (.isAlive (.get h))))
       (catch Exception _ false)))))

(defn acquire-lock!
  "Write this JVM's PID to the lock file at `lock-path` and return the file.

   - No lock file: create it.
   - Lock file whose PID is ALIVE: another backup/restore is running —
     throws ex-info `{:reason :lock-held :pid n :path p}`; the file is left
     untouched.
   - Lock file whose PID is dead or unreadable: STALE — reported on stderr
     and taken over."
  [lock-path]
  (let [f   (File. ^String lock-path)
        pid (when (.exists f) (lock-holder-pid f))]
    (when (.exists f)
      (if (pid-alive? pid)
        (throw (ex-info (str "lock file present at " lock-path
                             " and its process " pid " is alive")
                        {:reason :lock-held :pid pid :path lock-path}))
        (binding [*out* *err*]
          (println (str "stale lock at " lock-path " (process "
                        (or pid "unreadable") " is not running); taking it over")))))
    (ensure-dir! (.getParent f))
    (spit f (str (.pid (java.lang.ProcessHandle/current)) "\n"))
    f))

(defn release-lock!
  "Delete the lock file if present.  Idempotent; nil-safe."
  [^File f]
  (when (and f (.exists f)) (.delete f))
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Backup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-segments
  "Segment counts from backup-db's output.  backup-db prints a progress line
   per pass and the FIRST one is a preflight zero, so the LAST match is the
   total (before 2026-09-19 the first match was taken and every sidecar
   recorded zero segments)."
  [out]
  (when out
    (when-let [m (last (re-seq #"Copied\s+(\d+)\s+segments?,\s+skipped\s+(\d+)" out))]
      {:copied (Long/parseLong (nth m 1))
       :skipped (Long/parseLong (nth m 2))})))

(defn- run-backup!
  "Run `backup-db` and write the sidecar.  Returns the exit code to report;
   never calls `System/exit`, so the caller's `finally` can release the lock."
  [{:keys [src-uri backup-uri target label]}]
  (let [started (System/currentTimeMillis)]
    (println (str "Backing up: " src-uri))
    (println (str "        → " backup-uri))
    (let [{:keys [exit out err]} (sh/sh (datomic-cli/datomic-bin) "backup-db" src-uri backup-uri)
          duration (- (System/currentTimeMillis) started)
          segs     (parse-segments (str out "\n" err))]
      (when out (print out))
      (when (and err (seq err)) (binding [*out* *err*] (print err)))
      (let [sidecar (cond-> {:duration-ms     duration
                             :exit-code       exit
                             :datomic-version datomic-version
                             :source-uri      src-uri
                             :backup-uri      backup-uri
                             :timestamp       (str (Instant/now))
                             :label           label}
                      (:copied segs)  (assoc :segments-copied  (:copied segs))
                      (:skipped segs) (assoc :segments-skipped (:skipped segs)))]
        (ensure-dir! target)
        (spit (str target "/status.edn") (pr-str sidecar))
        (println (str "Wrote sidecar: " target "/status.edn"))
        (if (zero? exit)
          (do
            (println (str "Backup complete in " duration "ms"
                          (when (:copied segs)
                            (str " (copied " (:copied segs)
                                 ", skipped " (:skipped segs) ")"))))
            0)
          (do
            (binding [*out* *err*]
              (println (str "Backup FAILED (exit " exit ")")))
            exit))))))

(defn -main [& args]
  (let [label      (first args)
        src-uri    (db/db-uri)
        sid        (or (:sid (db/db-spec)) "sandbar")
        ts         (timestamp)
        root       (backup-root)
        dirname    (dir-name sid ts label)
        target     (str root "/" dirname)
        backup-uri (str "file://" target)
        lock-file  (str root "/.lock")]
    (ensure-dir! root)
    (let [lock (try
                 (acquire-lock! lock-file)
                 (catch clojure.lang.ExceptionInfo e
                   (binding [*out* *err*]
                     (println (str "ABORT: " (.getMessage e)))
                     (println "       another backup/restore is in progress"))
                   (System/exit 3)))
          code (try
                 (run-backup! {:src-uri    src-uri
                               :backup-uri backup-uri
                               :target     target
                               :label      label})
                 (catch Throwable t
                   (binding [*out* *err*]
                     (println (str "Backup error: " (.getMessage t))))
                   1)
                 (finally
                   (release-lock! lock)))]
      (System/exit code))))
