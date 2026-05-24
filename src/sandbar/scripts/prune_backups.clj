(ns sandbar.scripts.prune-backups
  "Prune Datomic backups under `<backup-root>/` by age or count.

   Usage:

       lein prune-backups [--keep-days N] [--keep-count K] [--confirm]

   Defaults: `--keep-days 7` (delete anything older than 7 days).
   Without `--confirm`, performs a DRY RUN — prints which dirs WOULD be
   deleted, then exits 0 without touching the filesystem.

   `--keep-days` AND `--keep-count` may be combined: a dir survives if it
   passes EITHER predicate (within the last N days OR among the K most
   recent).

   Datomic provides no native pruning verb — pruning is destructive
   `rm -rf` per the docs (\"delete the entire content hierarchy at a
   particular backup-uri's location\").  Voice-match `reset_db.clj`'s
   ABORT-unless-confirm pattern.

   Per memory/libraries/datomic/backup_restore.md §11 + §14."
  (:require [clojure.edn    :as edn]
            [clojure.string :as str])
  (:import (java.io File)
           (java.time Instant ZonedDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           (java.time.temporal ChronoUnit))
  (:gen-class))

(defn- backup-root []
  (let [client-dir (or (System/getenv "SANDBAR_CLIENT_DIR")
                       (str (System/getProperty "user.home") "/claude"))]
    (str client-dir "/.sandbar/backups")))

(defn- parse-args [args]
  (loop [opts {:keep-days 7 :keep-count nil :confirm? false}
         [a & more] args]
    (cond
      (nil? a)             opts
      (= a "--confirm")    (recur (assoc opts :confirm? true) more)
      (= a "--keep-days")  (recur (assoc opts :keep-days  (Long/parseLong (first more))) (rest more))
      (= a "--keep-count") (recur (assoc opts :keep-count (Long/parseLong (first more))) (rest more))
      :else                (recur opts more))))

(defn- list-backup-dirs []
  ;; Match any per-store backup dir (`<sid>-YYYYMMDD-HHMMSS-...`).
  (let [root (File. ^String (backup-root))]
    (when (.isDirectory root)
      (->> (.listFiles root)
           (filter #(.isDirectory ^File %))
           (filter #(re-find #"-\d{8}-\d{6}" (.getName ^File %)))))))

(def ^:private ts-fmt (DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))

(defn- parse-ts [^File dir]
  ;; Directory name shape: <sid>-YYYYMMDD-HHMMSS[-...]-datomic1.0.7482
  ;; Extract the YYYYMMDD-HHMMSS component.
  (let [name (.getName dir)
        m    (re-find #"-(\d{8}-\d{6})" name)]
    (when m
      (try
        (.toInstant (.atZone (java.time.LocalDateTime/parse (nth m 1) ts-fmt)
                             (ZoneId/systemDefault)))
        (catch Throwable _ nil)))))

(defn- with-ts [dirs]
  (keep (fn [d]
          (when-let [ts (parse-ts d)]
            {:dir d :ts ts :name (.getName ^File d)}))
        dirs))

(defn- rm-rf! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [c (.listFiles f)] (rm-rf! c)))
    (.delete f)))

(defn -main [& args]
  (let [{:keys [keep-days keep-count confirm?]} (parse-args args)
        now    (Instant/now)
        cutoff (.minus now (long keep-days) ChronoUnit/DAYS)
        all    (->> (list-backup-dirs) with-ts (sort-by :ts) reverse)  ;; newest-first
        keep-by-count   (if keep-count
                          (set (map :name (take keep-count all)))
                          #{})
        kept   (filter (fn [{:keys [ts name]}]
                         (or (.isAfter ts cutoff)
                             (contains? keep-by-count name)))
                       all)
        pruned (remove (fn [{:keys [name]}] (some #(= (:name %) name) kept)) all)]
    (println (str "Backup root: " (backup-root)))
    (println (str "Retention: keep-days=" keep-days
                  (when keep-count (str " keep-count=" keep-count))
                  (if confirm? "  (CONFIRMED — will delete)" "  (DRY RUN — pass --confirm to delete)")))
    (println)
    (println (str "Keeping " (count kept) " backup(s):"))
    (doseq [{:keys [name]} kept] (println (str "  KEEP   " name)))
    (println)
    (println (str "Pruning " (count pruned) " backup(s):"))
    (doseq [{:keys [name]} pruned] (println (str "  PRUNE  " name)))
    (when confirm?
      (doseq [{:keys [dir name]} pruned]
        (println (str "Deleting " name " ..."))
        (rm-rf! dir))
      (println (str "Deleted " (count pruned) " backup(s).")))
    (when-not confirm?
      (println)
      (println "DRY RUN — no files deleted.  Re-run with --confirm to apply."))
    (System/exit 0)))
