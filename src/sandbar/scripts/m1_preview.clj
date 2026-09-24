(ns sandbar.scripts.m1-preview
  "Preview tag invariants on Markdown loaded into an ephemeral database.
   Usage: lein run -m sandbar.scripts.m1-preview <markdown-root>
          [--limit N] [--out-edn <report.edn>]

   The default limit is 100 files; zero removes the cap. Each file is parsed
   with its path relative to the input root. Parse errors are logged and
   skipped. The populated in-memory database is checked by audit.tag/audit-all;
   print a summary and optionally write the full EDN report.
   This exploratory ingest is not the maintenance-import replacement path."
  (:require [clojure.edn            :as edn]
            [clojure.java.io        :as io]
            [clojure.pprint         :as pp]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.audit.tag      :as audit]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.test-util      :as tu]))

(defn- markdown-files
  "Walk `root` and return every regular `.md` file."
  [root]
  (->> (file-seq (io/file root))
       (filter #(.isFile ^java.io.File %))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".md"))
       (sort-by #(.getPath ^java.io.File %))))

(defn- rel-path
  "Compute the rel-path from `root` (an io/file or path string) to `file`."
  [^java.io.File root ^java.io.File file]
  (let [root-path (.getCanonicalPath root)
        file-path (.getCanonicalPath file)]
    (if (str/starts-with? file-path (str root-path "/"))
      (subs file-path (inc (count root-path)))
      file-path)))

(defn ingest-corpus-slice!
  "Walk `root` for markdown files, parse each via parse-document, transact
   into the DB.  Returns `{:ingested N :errors [...]}`."
  [root limit]
  (let [root-file (io/file root)
        files     (cond->> (markdown-files root-file)
                    (pos? limit) (take limit))
        outcome   (atom {:ingested 0 :errors []})]
    (doseq [f files]
      (let [rp     (rel-path root-file f)
            source (slurp f)]
        (try
          (let [entities (md/parse-document source rp)
                tx-data  (md/entity-specs->tx-data entities)]
            @(d/transact (db/conn) tx-data)
            (swap! outcome update :ingested inc))
          (catch Exception e
            (swap! outcome update :errors conj {:rel-path rp
                                                :error    (.getMessage e)})))))
    @outcome))

(defn run!
  "Run the preview with :root (Markdown input directory), :limit (maximum
   files, default 100; zero means no cap), and :out-edn (optional report file).
   Returns the tag-audit report."
  [{:keys [root limit out-edn]
    :or   {limit 100}}]
  (when (str/blank? (str root))
    (throw (ex-info "m1-preview/run! requires :root" {})))
  (let [test-uri "datomic:mem://m1-preview"]
    (d/delete-database test-uri)
    (d/create-database test-uri)
    (let [conn (d/connect test-uri)]
      (reset! db/**conn* conn)
      (try
        (println "Loading sandbar schema...")
        (tu/load-required-schema conn)
        (println (str "Ingesting from " root " (limit " (if (pos? limit) limit "∞") ")..."))
        (let [t0 (System/currentTimeMillis)
              {:keys [ingested errors]} (ingest-corpus-slice! root limit)
              t1 (System/currentTimeMillis)]
          (println (str "Ingested " ingested " memorials in " (- t1 t0) "ms"))
          (when (seq errors)
            (println (str (count errors) " ingest errors:"))
            (doseq [e (take 5 errors)]
              (println (str "  " (:rel-path e) ": " (:error e))))
            (when (> (count errors) 5)
              (println (str "  ... and " (- (count errors) 5) " more")))))
        (println "Running sandbar.audit.tag/audit-all...")
        (let [report (audit/audit-all)]
          (println)
          (println "=== AUDIT SUMMARY ===")
          (println (:summary report))
          (println)
          (doseq [inv (:invariants report)]
            (printf "  %-22s violations: %d%n"
                    (name (:invariant inv))
                    (:violation-count inv)))
          (when out-edn
            (io/make-parents out-edn)
            (spit out-edn (with-out-str (pp/pprint report)))
            (println (str "Wrote full EDN report → " out-edn)))
          report)
        (finally
          (reset! db/**conn* nil)
          (d/delete-database test-uri))))))

(defn- parse-args
  "Tiny arg parser — positional <root> + --limit N + --out-edn PATH."
  [args]
  (loop [[a & more] args
         out {:limit 100}]
    (cond
      (nil? a)            out
      (= a "--limit")     (recur (rest more) (assoc out :limit (Long/parseLong (first more))))
      (= a "--out-edn")   (recur (rest more) (assoc out :out-edn (first more)))
      (nil? (:root out))  (recur more (assoc out :root a))
      :else               (recur more out))))

(defn -main
  "Lein entry point.  Usage:
     lein run -m sandbar.scripts.m1-preview <corpus-root> [--limit N] [--out-edn PATH]"
  [& args]
  (let [opts (parse-args args)]
    (when (str/blank? (str (:root opts)))
      (println "Usage: lein run -m sandbar.scripts.m1-preview <corpus-root> [--limit N] [--out-edn PATH]")
      (System/exit 2))
    (run! opts)
    (System/exit 0)))
