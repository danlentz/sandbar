(ns sandbar.scripts.restore-db
  "Restore a Datomic database from a backup directory.

   Usage:

       lein restore-db-from <backup-dir> [<target-db-name>]

   `<backup-dir>` is REQUIRED (absolute path or relative to PWD).
   `<target-db-name>` defaults to the source DB name (per backup-db
   invariant: Datomic restore-db requires the target URI's DB name to
   match the original; differing names ABORT with a clear message).

   Preflight checks:
     (a) backup-dir exists + has `owner/`, `roots/`, `values/` subdirs
     (b) `bin/datomic verify-backup <backup-uri>` reports :succeeded
     (c) for :dev backend, the transactor MUST BE RUNNING (lsof :4334)

   :dev lifecycle inversion — for `:dev` storage, the transactor must
   stay up during restore (storage lives inside the transactor process;
   H2 embedded).  Other backends invert this (peers + transactors down).
   This script encodes the :dev variant.

   Per memory/libraries/datomic/backup_restore.md §7 + §12."
  (:require [clojure.edn                 :as edn]
            [clojure.java.shell          :as sh]
            [clojure.string              :as str]
            [sandbar.db.datomic          :as db]
            [sandbar.scripts.datomic-cli :as datomic-cli])
  (:import (java.io File)
           (java.time Instant))
  (:gen-class))

(defn- backup-root []
  (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                       (str (System/getProperty "user.home") "/claude"))]
    (str client-dir "/.sandbar/backups")))

(defn- abort! [code & lines]
  (binding [*out* *err*]
    (doseq [l lines] (println l)))
  (System/exit code))

(defn- ensure-dir! [^String path]
  (.mkdirs (File. path)))

(defn- acquire-lock! [lock-path]
  (let [f (File. ^String lock-path)]
    (when (.exists f)
      (abort! 3
              (str "ABORT: lock file present at " lock-path)
              "       another backup/restore may be in progress; remove the file if stale"))
    (ensure-dir! (.getParent f))
    (spit f (str (.pid (java.lang.ProcessHandle/current)) "\n"))
    f))

(defn- release-lock! [^File f]
  (when (and f (.exists f)) (.delete f)))

(defn- resolve-backup-dir [arg]
  (let [f (File. ^String arg)]
    (if (.isAbsolute f)
      (.getCanonicalPath f)
      (.getCanonicalPath (File. (System/getProperty "user.dir") arg)))))

(defn- structurally-valid? [^String dir]
  (every? #(.exists (File. (str dir "/" %))) ["owner" "roots" "values"]))

(defn- read-source-uri [^String dir]
  (let [sidecar (File. (str dir "/status.edn"))]
    (when (.exists sidecar)
      (try (:source-uri (edn/read-string (slurp sidecar)))
           (catch Throwable _ nil)))))

(defn- parse-db-name [uri]
  (when uri (last (str/split uri #"/"))))

(defn- dev-transactor-up? []
  (try
    (let [{:keys [exit]} (sh/sh "bash" "-c" "lsof -i :4334 -t >/dev/null 2>&1")]
      (zero? exit))
    (catch Throwable _ false)))

(defn -main [& args]
  (when (empty? args)
    (abort! 2
            "ABORT: <backup-dir> is required."
            "       Usage: lein restore-db-from <backup-dir> [<target-db-name>]"))
  (let [backup-dir (resolve-backup-dir (first args))
        target-name (second args)
        backup-uri (str "file://" backup-dir)
        src-uri    (db/db-uri)
        src-name   (parse-db-name src-uri)
        sidecar-src (read-source-uri backup-dir)
        sidecar-name (parse-db-name sidecar-src)
        canonical-name (or sidecar-name src-name)
        final-name (or target-name canonical-name)
        lock-file  (str (backup-root) "/.lock")]

    ;; Preflight (a) — directory + subdir presence
    (when-not (.isDirectory (File. backup-dir))
      (abort! 2 (str "ABORT: backup-dir not a directory: " backup-dir)))
    (when-not (structurally-valid? backup-dir)
      (abort! 2
              (str "ABORT: backup-dir missing required subdirs (owner/ roots/ values/): " backup-dir)
              "       This does not look like a Datomic backup."))

    ;; Preflight — target name MUST match original (Datomic restore-db invariant)
    (when (and target-name (not= target-name canonical-name))
      (abort! 2
              (str "ABORT: target-db-name (" target-name ") differs from original (" canonical-name ")")
              "       Datomic restore-db requires matching DB names.  Restore to the original name,"
              "       then use `d/rename-database` post-restore."))

    ;; Preflight (c) — :dev transactor must be running
    (when-not (dev-transactor-up?)
      (abort! 4
              "ABORT: :dev transactor not detected on port 4334."
              "       For :dev backend, the transactor MUST be running during restore."
              "       Start it first: bin/sandbar start"))

    ;; Acquire lock
    (let [lock     (acquire-lock! lock-file)
          target-uri (str "datomic:dev://localhost:4334/" final-name)
          started   (System/currentTimeMillis)]
      (try
        ;; Preflight (b) — verify-backup must succeed first
        (println (str "Preflight verify: " backup-uri))
        (let [{:keys [exit out err]} (sh/sh (datomic-cli/datomic-bin) "verify-backup" backup-uri)]
          (when out (print out))
          (when (and err (seq err)) (binding [*out* *err*] (print err)))
          (when-not (zero? exit)
            (abort! exit
                    (str "ABORT: verify-backup failed (exit " exit ")")
                    "       Refusing to restore from an unverified backup.")))

        ;; Restore
        (println (str "Restoring: " backup-uri))
        (println (str "        → " target-uri))
        (let [{:keys [exit out err]} (sh/sh (datomic-cli/datomic-bin) "restore-db" backup-uri target-uri)
              duration (- (System/currentTimeMillis) started)]
          (when out (print out))
          (when (and err (seq err)) (binding [*out* *err*] (print err)))
          (let [sidecar {:duration-ms duration
                         :restored-to target-uri
                         :backup-uri  backup-uri
                         :exit-code   exit
                         :timestamp   (str (Instant/now))}]
            (spit (str backup-dir "/restore.edn") (pr-str sidecar))
            (println (str "Wrote sidecar: " backup-dir "/restore.edn")))
          (if (zero? exit)
            (do
              (println (str "Restore complete in " duration "ms → " target-uri))
              (System/exit 0))
            (do
              (binding [*out* *err*]
                (println (str "Restore FAILED (exit " exit ")")))
              (System/exit exit))))
        (catch Throwable t
          (binding [*out* *err*]
            (println (str "Restore error: " (.getMessage t))))
          (System/exit 1))
        (finally
          (release-lock! lock))))))
