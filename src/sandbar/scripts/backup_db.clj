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

(defn- acquire-lock! [lock-path]
  (let [f (File. ^String lock-path)]
    (when (.exists f)
      (binding [*out* *err*]
        (println (str "ABORT: lock file present at " lock-path))
        (println "       another backup/restore may be in progress; remove the file if stale"))
      (System/exit 3))
    (ensure-dir! (.getParent f))
    (spit f (str (.pid (java.lang.ProcessHandle/current)) "\n"))
    f))

(defn- release-lock! [^File f]
  (when (and f (.exists f)) (.delete f)))

(defn- parse-segments [out]
  (when out
    (when-let [m (re-find #"Copied\s+(\d+)\s+segments?,\s+skipped\s+(\d+)" out)]
      {:copied (Long/parseLong (nth m 1))
       :skipped (Long/parseLong (nth m 2))})))

(defn -main [& args]
  (let [label     (first args)
        src-uri   (db/db-uri)
        sid       (or (:sid (db/db-spec)) "sandbar")
        ts        (timestamp)
        root      (backup-root)
        dirname   (dir-name sid ts label)
        target    (str root "/" dirname)
        backup-uri (str "file://" target)
        lock-file (str root "/.lock")]
    (ensure-dir! root)
    (let [lock (acquire-lock! lock-file)
          started (System/currentTimeMillis)]
      (try
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
            (spit (str target "/status.edn") (pr-str sidecar))
            (println (str "Wrote sidecar: " target "/status.edn"))
            (if (zero? exit)
              (do
                (println (str "Backup complete in " duration "ms"
                              (when (:copied segs)
                                (str " (copied " (:copied segs)
                                     ", skipped " (:skipped segs) ")"))))
                (System/exit 0))
              (do
                (binding [*out* *err*]
                  (println (str "Backup FAILED (exit " exit ")")))
                (System/exit exit)))))
        (catch Throwable t
          (binding [*out* *err*]
            (println (str "Backup error: " (.getMessage t))))
          (System/exit 1))
        (finally
          (release-lock! lock))))))
