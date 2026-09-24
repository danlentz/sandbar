(ns sandbar.scripts.full-corpus-ingest
  "Exercise Markdown parsing and per-file entity creation in an in-memory
   database, then run the tag audit.
   Usage: lein run -m sandbar.scripts.full-corpus-ingest <corpus-root> [--limit N]

   `ingest-units` supplies parsed source groups; each group is converted to
   transaction data and submitted through dt/make-all*. This does not execute
   the maintenance import's preview, identity reconciliation, or source-owned
   replacement contract. Parse failures are filtered out and transaction
   failures are reported per group, so completion exit 0 is not an all-files
   success certificate.

   Zero (the default limit) means no cap. Run as a separate process: the script
   creates/deletes its fixed in-memory database and replaces the process-global
   database connection while it runs."
  (:require [clojure.java.io        :as io]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.audit.tag      :as audit]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]
            [sandbar.projection     :as pg]
            [sandbar.test-util      :as tu]))

(defn- ingest-group!
  "Create one parsed source group with entity-specs->tx-data and dt/make-all*,
   using one transaction. Return {:ok? :group-size :ident :error}. This helper
   is a creation probe, not the source-owned replacement import path."
  [group]
  (let [memory (first group)
        ident  (:db/ident memory)
        tx-data (->> (codec-md/entity-specs->tx-data group)
                     (mapv #(dissoc % :dt/type)))]
    (try
      (dt/make-all* tx-data)
      {:ok? true :group-size (count group) :ident ident}
      (catch Throwable ex
        {:ok? false :group-size (count group) :ident ident
         :error (.getMessage ex)}))))

(defn run!
  "Programmatic entry — `opts` keys :root + :limit."
  [{:keys [root limit] :or {limit 0}}]
  (when (str/blank? (str root))
    (throw (ex-info "full-corpus-ingest/run! requires :root" {})))
  (let [test-uri "datomic:mem://full-corpus-ingest"]
    (d/delete-database test-uri)
    (d/create-database test-uri)
    (let [conn (d/connect test-uri)]
      (reset! db/**conn* conn)
      (try
        (println "Loading sandbar schema...")
        (tu/load-required-schema conn)
        (println (str "Walking " root " for markdown files..."))
        (let [t0 (System/currentTimeMillis)
              ;; ingest-units walks the tree + parses each file (no transact);
              ;; one SOURCE UNIT per parsed file is the transaction unit
              ;; (REP-06, D6 2026-09-19).
              groups   (->> (pg/ingest-units root)
                            (filter #(= :parsed (:status %)))
                            (map :entities))
              groups   (cond->> groups (pos? limit) (take limit))
              entities (mapcat identity groups)
              t1 (System/currentTimeMillis)]
          (println (str "Parsed " (count entities) " entities in "
                        (count groups) " groups (" (- t1 t0) "ms)"))
          (println "Transacting per-group via canonical pipeline...")
          (let [t2 (System/currentTimeMillis)
                results (mapv ingest-group! groups)
                t3 (System/currentTimeMillis)
                successes (filter :ok? results)
                failures  (remove :ok? results)]
            (println (str "Transacted " (count results) " groups in "
                          (- t3 t2) "ms"))
            (println (str "  successes: " (count successes)))
            (println (str "  failures:  " (count failures)))
            (when (seq failures)
              (println (str "ALL " (count failures) " failures:"))
              (doseq [f failures]
                (println (str "  " (:ident f) " (" (:group-size f) " entities): "
                              (subs (str (:error f)) 0 (min 250 (count (str (:error f))))))))))
          (println "Running sandbar.audit.tag/audit-all...")
          (let [report (audit/audit-all)]
            (println)
            (println "=== AUDIT SUMMARY ===")
            (println (:summary report))
            (doseq [inv (:invariants report)]
              (println (format "  %-22s violations: %d"
                               (name (:invariant inv))
                               (:violation-count inv))))
            report))
        (finally
          (reset! db/**conn* nil)
          (d/delete-database test-uri))))))

(defn- parse-args
  [args]
  (loop [[a & more] args
         out {:limit 0}]
    (cond
      (nil? a)            out
      (= a "--limit")     (recur (rest more) (assoc out :limit (Long/parseLong (first more))))
      (nil? (:root out))  (recur more (assoc out :root a))
      :else               (recur more out))))

(defn -main
  "Lein entry point.  Usage:
     lein run -m sandbar.scripts.full-corpus-ingest <corpus-root> [--limit N]"
  [& args]
  (let [opts (parse-args args)]
    (when (str/blank? (str (:root opts)))
      (println "Usage: lein run -m sandbar.scripts.full-corpus-ingest <corpus-root> [--limit N]")
      (System/exit 2))
    (run! opts)
    (System/exit 0)))
