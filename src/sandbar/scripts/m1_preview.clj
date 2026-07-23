(ns sandbar.scripts.m1-preview
  "M.1 preview script — ingest a corpus markdown tree + run sandbar.audit.tag
   against the populated DB.  Produces a real-world tag-inventory report
   exercising the Stage 7 substrate against actual data.

   Per the tag-modeling first-class arc Stage 8.A (`mem tag-audit` run
   against current corpus produces clustering report) — this script is
   the SANDBAR-side equivalent that anyone can run locally before sandbar
   0.1.2 publishes to Clojars.

   ## Usage

     lein run -m sandbar.scripts.m1-preview \\
              /Users/dan/claude/memory \\
              [--limit N]
             [--out-edn audit-results/m1-preview-<date>.edn]

   Default --limit is 100 (a tractable preview slice).  Pass 0 for no cap.
   Default --out-edn pretty-prints to stdout if absent.

   ## Output

     Stdout: per-invariant summary lines + total violation count.
     EDN file: the full `sandbar.audit.tag/audit-all` report (when --out-edn).

   ## Implementation notes

     - In-memory Datomic; ephemeral DB per invocation.
     - Walks `<root>/**/*.md` files via `file-seq` (no fs-enumeration of
       memory/ — this script operates on the consumer's filesystem, not
       sandbar's own; the consumer corpus IS the input).
     - Parse uses `sandbar.codec.markdown/parse-document` with rel-path
       derived from the file's path relative to <root>.  Per Stage 7.C
       class-routing, `type: tag` frontmatter routes the entity to :mm/Tag;
       everything else routes to :mm/Memory.
     - Per-file parse errors logged + skipped (doesn't abort the pass).
     - Run sandbar.audit.tag/audit-all over the populated DB."
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
  "Programmatic entry point.  `opts` keys:
     :root      — corpus root (e.g., \"/Users/dan/claude/memory\")
     :limit     — max files to ingest (0 = no cap; default 100)
     :out-edn   — optional path to write the EDN audit report
                  (relative to the cwd; created with parent dirs)"
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
