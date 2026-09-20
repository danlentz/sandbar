(ns sandbar.scripts.maintenance-import
  "The quiescent maintenance import — the sprint's maintenance-only import
   contract (Dan's scope refinement, 2026-09-20): with the server STOPPED,
   so no MCP caller, no reactive worker and no scheduler can write, and
   nothing projects until the records are consistent, this entry point
   connects to the configured store in-process, registers the codecs,
   installs the transactor-side functions, and runs the SAME import the
   MCP verb runs (`sandbar.mcp.tools/project-import-handler`): a preview
   first, whose source pin and basis the persist is then pinned to.  It
   can first retract a named list of entities through the retract verb,
   dry run before persist.  Every step's report is written as EDN to a
   receipts directory and summarized on stdout.  It refuses to run while
   the server's port answers.

   Usage:
     lein maintenance-import -- --from <corpus-root> [--persist]
          [--exclude-file <rel-paths, one per line>]
          [--retract-file <idents or eids, one per line>]
          [--receipts <dir>] [--port 8389] [--mode replace|additive]

   Without `--persist` everything is a dry run: the retraction reports and
   the import preview are written, nothing is transacted."
  (:require [clojure.edn            :as edn]
            [clojure.java.io        :as io]
            [clojure.pprint         :as pp]
            [clojure.string         :as str]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.db.fn          :as dbfn]
            [sandbar.mcp.tools      :as tools]
            [sandbar.retract        :as retract]))

(defn server-up?
  "True when something answers on `port` — the server has not been stopped."
  [port]
  (try (with-open [s (java.net.Socket.)]
         (.connect s (java.net.InetSocketAddress. "localhost" (int port)) 500))
       true
       (catch Exception _ false)))

(defn- write-receipt! [dir name data]
  (when (and dir (some? data))
    (.mkdirs (io/file dir))
    (let [f (io/file dir (str name ".edn"))]
      (spit f (with-out-str (pp/pprint data)))
      (.getPath f))))

(defn- ->target [s]
  (let [s (str/trim s)]
    (cond (str/blank? s) nil
          (re-matches #"\d+" s) (Long/parseLong s)
          (str/starts-with? s ":") (keyword (subs s 1))
          :else (keyword s))))

(defn run!
  "Programmatic entry.  `opts`: `:from` (required), `:persist?`, `:exclude`
   (a coll of rel-paths), `:retract` (a coll of idents or eids), `:receipts`
   (a directory), `:port` (the server port to check; default 8389; nil skips
   the check), `:uri` (the store uri whose transactor-side functions to
   install; nil skips the install), `:mode` (\"replace\" default).  Returns
   `{:retract-dry-run :retract :preview :persist}` (the absent steps nil)."
  [{:keys [from persist? exclude retract receipts port uri mode] :or {port 8389 mode "replace"}}]
  (when-not from (throw (ex-info "maintenance-import requires :from" {})))
  (when (and port (server-up? port))
    (throw (ex-info (str "the server answers on port " port "; stop it first — the maintenance import runs only on a quiescent store")
                    {:reasons #{:maintenance/server-up} :port port})))
  (md/register!)
  (when uri (dbfn/load-all-dbfn uri))
  (let [targets     (vec (keep ->target retract))
        retract-dry (when (seq targets)
                      (retract/retract! targets {:persist false :cascade true}))
        _           (write-receipt! receipts "retract-dry-run" retract-dry)
        retract-run (when (and persist? (seq targets))
                      (retract/retract! targets {:persist true :cascade true
                                                 :acknowledge-dangling true
                                                 :reason "D7 quiescent maintenance import (2026-09-20): the named twins and empty rows, with the files' own references taking over"}))
        _           (write-receipt! receipts "retract-persist" retract-run)
        base-args   (cond-> {"from" from "mode" mode}
                      (seq exclude) (assoc "exclude" (vec exclude)))
        preview     (tools/project-import-handler base-args)
        _           (write-receipt! receipts "import-preview" preview)
        persist     (when persist?
                      (tools/project-import-handler (assoc base-args
                                                          "persist" true
                                                          "expect-basis" (:basis preview)
                                                          "expect-sources" (:sources-sha256 preview))))
        _           (write-receipt! receipts "import-persist" persist)]
    {:retract-dry-run retract-dry :retract retract-run :preview preview :persist persist}))

(defn- summarize [{:keys [retract-dry-run retract preview persist]}]
  (when retract-dry-run
    (println (format "retract dry run: %d targets; file effects %s"
                     (count (:targets retract-dry-run))
                     (pr-str (frequencies (map #(get-in % [:file-effect :outcome]) (:targets retract-dry-run)))))))
  (when retract
    (println (format "retract persist: %d retracted, files removed %d, files %s"
                     (:retracted-count retract) (:files-removed retract) (pr-str (frequencies (map :outcome (:files retract)))))))
  (println (format "preview: attempted %d, parsed units %d, parse-failed %d, excluded %d, conflicts %d, basis %s, sources-sha256 %s"
                   (:attempted preview) (count (:units preview)) (:parse-failed-count preview) (:excluded-count preview)
                   (:conflict-count preview) (:basis preview) (some-> (:sources-sha256 preview) (subs 0 12))))
  (when preview
    (println "  planned modes:" (pr-str (frequencies (map :mode (:units preview))))))
  (when persist
    (println (format "persist: persisted %d, conflicts %d, failed %d, refused %d, reconciled %s, final basis %s"
                     (:persisted-count persist) (:conflict-count persist) (:failed-count persist) (:refused-count persist)
                     (:reconciled? persist) (:final-basis persist)))))

(defn- read-lines [f] (when f (remove str/blank? (str/split-lines (slurp f)))))

(defn -main [& args]
  (let [opts (loop [[a & more] args acc {}]
               (cond (nil? a) acc
                     (= a "--persist") (recur more (assoc acc :persist? true))
                     (= a "--from") (recur (rest more) (assoc acc :from (first more)))
                     (= a "--exclude-file") (recur (rest more) (assoc acc :exclude (read-lines (first more))))
                     (= a "--retract-file") (recur (rest more) (assoc acc :retract (read-lines (first more))))
                     (= a "--receipts") (recur (rest more) (assoc acc :receipts (first more)))
                     (= a "--port") (recur (rest more) (assoc acc :port (Long/parseLong (first more))))
                     (= a "--mode") (recur (rest more) (assoc acc :mode (first more)))
                     :else (recur more acc)))]
    (when-not (:from opts)
      (println "Usage: lein maintenance-import -- --from <corpus-root> [--persist] [--exclude-file f] [--retract-file f] [--receipts dir] [--port 8389] [--mode replace|additive]")
      (System/exit 2))
    (let [result (run! (assoc opts :uri (db/db-uri)))]
      (summarize result)
      (System/exit 0))))
