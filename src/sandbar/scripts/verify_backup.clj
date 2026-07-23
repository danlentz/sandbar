(ns sandbar.scripts.verify-backup
  "Verify the structural integrity of a Datomic backup directory.

   Usage:

       lein verify-backup [<backup-dir>]

   `<backup-dir>` defaults to the LATEST backup under `<backup-root>/`
   (sorted lexicographically by directory name; backup-db's TS-prefix
   convention makes this chronological).

   Shells out to `bin/datomic list-backups <backup-uri>` to discover
   the latest `t` value (a backup may contain multiple point-in-time
   t-values; we verify the latest), then `bin/datomic verify-backup
   <backup-uri> true <t>` to walk every segment + assert readability.

   The 3-arg form `verify-backup <uri> read-all t` is REQUIRED by the
   Datomic CLI; passing only `<uri>` returns exit 255 with a usage
   error.  Discovered 2026-05-24 when the initial scripted invocation
   failed against the baseline backup; the Datomic CLI's usage line is
   the only documentation of the arg shape.

   Updates `<backup-dir>/status.edn` with
   `{:verified-at <inst> :verify-exit-code N}` (merge-style; preserves
   the original backup-db sidecar fields).

   Per memory/libraries/datomic/backup_restore.md §8 (verify-backup
   cadence: post-baseline full-read; weekly thereafter)."
  (:require [clojure.edn                 :as edn]
            [clojure.java.shell          :as sh]
            [clojure.string              :as str]
            [sandbar.scripts.datomic-cli :as datomic-cli])
  (:import (java.io File)
           (java.time Instant))
  (:gen-class))

(defn- backup-root []
  (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                       (str (System/getProperty "user.home") "/claude"))]
    (str client-dir "/.sandbar/backups")))

(defn- list-backup-dirs []
  ;; Match any per-store backup dir (`<sid>-YYYYMMDD-HHMMSS-...`).
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
    (or (latest-backup-dir)
        (binding [*out* *err*]
          (println "ABORT: no backups found under " (backup-root))
          (System/exit 2)))))

(defn- merge-sidecar! [path patch]
  (let [f (File. ^String path)
        existing (if (.exists f)
                   (try (edn/read-string (slurp f))
                        (catch Throwable _ {}))
                   {})]
    (spit f (pr-str (merge existing patch)))))

(defn- parse-t-values
  "Datomic `list-backups` prints a Clojure-readable list like `(54579)`
   or `(54579 60000 70000)`.  Parse + return as a vec of longs (or nil
   if parse fails)."
  [stdout]
  (try
    (let [s (str/trim stdout)]
      (when (and (seq s) (str/starts-with? s "("))
        (vec (edn/read-string s))))
    (catch Throwable _ nil)))

(defn- discover-latest-t
  "Shell `bin/datomic list-backups <uri>` to find the t-values in the
   backup; return the latest (max) or nil if discovery fails."
  [backup-uri]
  (let [{:keys [exit out]} (sh/sh (datomic-cli/datomic-bin) "list-backups" backup-uri)]
    (when (zero? exit)
      (when-let [ts (parse-t-values out)]
        (when (seq ts) (apply max ts))))))

(defn -main [& args]
  (let [backup-dir (resolve-backup-dir (first args))
        backup-uri (str "file://" backup-dir)]
    (println (str "Discovering t-values: " backup-uri))
    (if-let [t (discover-latest-t backup-uri)]
      (do
        (println (str "Verifying at t=" t " (read-all)"))
        (let [started (System/currentTimeMillis)
              {:keys [exit out err]} (sh/sh (datomic-cli/datomic-bin) "verify-backup"
                                            backup-uri "true" (str t))
              duration (- (System/currentTimeMillis) started)]
          (when out (print out))
          (when (and err (seq err)) (binding [*out* *err*] (print err)))
          (merge-sidecar! (str backup-dir "/status.edn")
                          {:verified-at        (str (Instant/now))
                           :verified-at-t      t
                           :verify-exit-code   exit
                           :verify-duration-ms duration})
          (if (zero? exit)
            (do
              (println (str "Verify SUCCEEDED in " duration "ms (t=" t ")"))
              (System/exit 0))
            (do
              (binding [*out* *err*]
                (println (str "Verify FAILED (exit " exit ")")))
              (System/exit exit)))))
      (do
        (binding [*out* *err*]
          (println (str "ABORT: could not discover t-values via "
                        "`bin/datomic list-backups` on " backup-uri)))
        (System/exit 3)))))
