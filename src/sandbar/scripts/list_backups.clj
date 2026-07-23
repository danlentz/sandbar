(ns sandbar.scripts.list-backups
  "Enumerate Datomic backups under `<backup-root>/`.

   Usage:

       lein list-backups

   For each `sandbar-*/` directory, reads `status.edn` (if present) and
   prints a row showing `{dir-name, datomic-version, segments, duration,
   verified?, restore-cmd}`.  Sorted by timestamp descending (newest
   first).

   Per memory/libraries/datomic/backup_restore.md §12."
  (:require [clojure.edn    :as edn]
            [clojure.string :as str])
  (:import (java.io File))
  (:gen-class))

(defn- backup-root []
  (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                       (str (System/getProperty "user.home") "/claude"))]
    (str client-dir "/.sandbar/backups")))

(defn- list-backup-dirs []
  ;; Match any per-store backup dir (`<sid>-YYYYMMDD-HHMMSS-...`); per the
  ;; multi-store HYBRID strategy each `:sid` gets its own URI prefix.
  ;; Pattern is permissive: any subdir whose name matches `*-YYYYMMDD-HHMMSS*`.
  (let [root (File. ^String (backup-root))]
    (when (.isDirectory root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (filter #(re-find #"-\d{8}-\d{6}" (.getName ^File %)))
           (sort-by #(.getName ^File %))
           reverse))))

(defn- read-sidecar [^File dir]
  (let [f (File. dir "status.edn")]
    (if (.exists f)
      (try (edn/read-string (slurp f))
           (catch Throwable _ {:error "unreadable status.edn"}))
      {})))

(defn- fmt-row [^File dir]
  (let [info     (read-sidecar dir)
        name     (.getName dir)
        version  (or (:datomic-version info) "?")
        segs     (or (:segments-copied info) "?")
        dur      (when-let [d (:duration-ms info)] (str (long (/ d 1000.0)) "s"))
        verified (cond
                   (= 0 (:verify-exit-code info)) "yes"
                   (some? (:verify-exit-code info)) "FAIL"
                   :else "—")
        restored (if (:restored-to info) "yes" "—")]
    {:dir-name name
     :version  version
     :segments segs
     :duration (or dur "—")
     :verified verified
     :restored restored
     :restore-cmd (str "bin/sandbar restore " (.getCanonicalPath dir))}))

(defn -main [& _args]
  (let [dirs (list-backup-dirs)]
    (if (empty? dirs)
      (println (str "No backups found under " (backup-root)))
      (do
        (println (str "Backup root: " (backup-root)))
        (println)
        (println (format "%-50s %-10s %-10s %-8s %-9s %-9s"
                         "DIR" "DATOMIC" "SEGMENTS" "DUR" "VERIFIED" "RESTORED"))
        (println (apply str (repeat 100 "-")))
        (doseq [d dirs]
          (let [row (fmt-row d)]
            (println (format "%-50s %-10s %-10s %-8s %-9s %-9s"
                             (:dir-name row)
                             (:version row)
                             (str (:segments row))
                             (:duration row)
                             (:verified row)
                             (:restored row)))))
        (println)
        (println "Restore latest:")
        (println (str "  " (:restore-cmd (fmt-row (first dirs)))))))))
