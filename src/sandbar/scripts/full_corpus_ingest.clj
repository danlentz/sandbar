(ns sandbar.scripts.full-corpus-ingest
  "Stage 3.B — full-corpus ingest validation via the canonical
   `sandbar.project.import` pathway.

   Per `~/claude/memory/plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md`
   Stage 3.B (project-graph initialization).  Where `sandbar.scripts.m1-preview`
   BYPASSES the MCP layer + transacts parse-document output directly, this
   script uses the CANONICAL pipeline that `sandbar.project.import` MCP verb
   invokes:

     pg/ingest-graph
       → codec-md/group-by-source
       → for each group: codec-md/entity-specs->tx-data + dt/make-all*

   This is the exact end-to-end shape any consumer would get through the
   MCP surface — running it locally validates the canonical pathway.

   ## Usage

     lein run -m sandbar.scripts.full-corpus-ingest <corpus-root> [--limit N]

   `--limit` caps the number of files ingested (0 = no cap; default 0 for
   full corpus).

   ## Output

     Stdout: per-group ingest outcomes (success / failure) + final summary
       (total ingested / failed / audit violations).
     Exit 0 on completion regardless of per-group failures.
     Exit 1 on script-level failure (DB setup, etc.)."
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
  "Transact a single per-source-file group via the canonical pipeline:
   entity-specs->tx-data (assign tempids + translate ref-slot values) +
   dt/make-all* (one atomic tx per group).  Returns
   `{:ok? bool :group-size N :ident <kw> :error nil-or-msg}`."
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
              ;; ingest-graph walks the tree + parses each file (no transact)
              all-entities (pg/ingest-graph root)
              entities (cond->> all-entities
                         (pos? limit) (take (* limit 10))) ; rough section-aware limit
              groups   (codec-md/group-by-source entities)
              groups   (cond->> groups (pos? limit) (take limit))
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
              (println "First 5 failure samples:")
              (doseq [f (take 5 failures)]
                (println (str "  " (:ident f) " (" (:group-size f) " entities): "
                              (subs (str (:error f)) 0 (min 200 (count (str (:error f))))))))
              (when (> (count failures) 5)
                (println (str "  ... and " (- (count failures) 5) " more")))))
          (println "Running sandbar.audit.tag/audit-all...")
          (let [report (audit/audit-all)]
            (println)
            (println "=== AUDIT SUMMARY ===")
            (println (:summary report))
            (doseq [inv (:invariants report)]
              (printf "  %-22s violations: %d%n"
                      (name (:invariant inv))
                      (:violation-count inv)))
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
