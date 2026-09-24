(ns sandbar.scripts.maintenance-import
  "Import staged Markdown into an existing store during stopped-writer
   maintenance. Stop every writer before canonical file edits and keep them
   stopped through preview, import, and audit; a port check alone cannot prove
   that admin peers or other filesystem writers are quiescent.

   Each invocation computes a fresh preview. With --persist, that invocation's
   preview basis and source pin constrain its own persist call; a previous
   human-reviewed receipt is not consumed. Use separate dry/persist receipt
   directories and a frozen staging manifest as described in doc/operations.md.
   Import may commit earlier files before a later file fails.

   Stage only selected inputs at <root>/memory/<rel-path>. A root mixing those
   paths with other Markdown is refused. --exclude-file entries match paths
   relative to the staging root, including memory/. Without --persist, the
   script writes receipts but performs no transactions or function installation.

   Usage: lein maintenance-import -- --from <staging-root> [--persist]
          [--exclude-file <file>] [--receipts <directory>] [--port 8389]
          [--mode replace|additive] [--retract-file <file>] [--stamp]
   --stamp completes new-document enrollment at the canonical destination by
   adding only a missing id line under the import's source pins. It requires
   --receipts when persisting; ordinary projection stays strict. Staging copies
   at other paths cannot be stamped as if they were canonical files.
   Optional explicit retractions require their own review; they are outside
   the bounded replacement recipe. Exit 0 reflects `complete?` on the import
   preview/persist reports, not proof of all optional retraction effects."
  (:require [clojure.edn            :as edn]
            [clojure.java.io        :as io]
            [clojure.pprint         :as pp]
            [clojure.string         :as str]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.db.fn          :as dbfn]
            [sandbar.mcp.tools      :as tools]
            [sandbar.project.enrollment-stamp :as stamp]
            [sandbar.projection     :as pg]
            [sandbar.retract        :as retract]))

(defn server-up?
  "True when a TCP connection to localhost at `port` succeeds.
   This is a listener check, not a census of every possible database writer."
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

(defn sources-outside-memory
  "The walk-relative rel-paths among `rel-paths` that do not lie under `memory/`."
  [rel-paths]
  (vec (remove #(str/starts-with? (str %) "memory/") rel-paths)))

(defn mixed-root?
  "True when enumerated Markdown paths include both memory/ descendants and
   paths outside memory/. The check needs only the directory walk, not parsing.
   An all-memory/ staging tree and a flat tree without that prefix both pass."
  [rel-paths]
  (let [outside (count (sources-outside-memory rel-paths))]
    (and (pos? outside) (< outside (count rel-paths)))))

(defn complete?
  "True when the import preview has no parse failures or conflicts and any
   persist report is reconciled, has no conflicts/failures/refusals, and counts
   every previewed unit as persisted. Optional retraction reports are not
   inspected by this predicate."
  [{:keys [preview persist stamp]}]
  (boolean
    (and (some? preview)
         (zero? (or (:parse-failed-count preview) 0))
         (zero? (or (:conflict-count preview) 0))
         (or (nil? stamp) (true? (:complete? stamp)))
         (or (nil? persist)
             (and (true? (:reconciled? persist))
                  (zero? (or (:conflict-count persist) 0))
                  (zero? (or (:failed-count persist) 0))
                  (zero? (or (:refused-count persist) 0))
                  (= (or (:persisted-count persist) 0) (count (:units preview))))))))

(defn run!
  "Programmatic entry.  `opts`: `:from` (required), `:persist?`, `:exclude`
   (a coll of rel-paths), `:retract` (a coll of idents or eids), `:receipts`
   (a directory), `:port` (the server port to check; default 8389; nil skips
   the check), `:uri` (the store uri whose transactor-side functions to
   install before a persist; nil skips the install), `:mode` (\"replace\"
   default), `:stamp?` (bind newly imported UUIDs to their pinned canonical
   source files; with persistence, requires `:receipts`). Returns
   `{:retract-dry-run :retract :preview :persist}`, plus `:stamp` when run
   (the other absent steps nil). Throws before any transaction when the server
   answers or the root is mixed; a mixed root leaves a `refused.edn`
   receipt naming what was walked."
  [{:keys [from persist? exclude retract receipts port uri mode stamp?] :or {port 8389 mode "replace"}}]
  (when-not from (throw (ex-info "maintenance-import requires :from" {})))
  (when (and stamp? persist? (str/blank? receipts))
    (throw (ex-info "maintenance-import --stamp requires --receipts when persisting"
                    {:reasons #{:maintenance/stamp-receipts-required}})))
  (when (and port (server-up? port))
    (throw (ex-info (str "the server answers on port " port "; stop it first — the maintenance import runs only on a quiescent store")
                    {:reasons #{:maintenance/server-up} :port port})))
  (md/register!)
  ;; the root's shape is checked on the bare directory walk, before any
  ;; transaction and before the parse; the refusal leaves its own receipt
  (let [walked (pg/walk-markdown-rel-paths (io/file from) {})]
    (when (mixed-root? walked)
      (let [outside (sources-outside-memory walked)]
        (write-receipt! receipts "refused" {:reason :maintenance/mixed-root :from from
                                            :walked (count walked) :outside-memory (count outside)
                                            :outside (vec (take 50 outside))})
        (throw (ex-info (str "the root " from " mixes files under memory/ with files elsewhere — a project root, not a staging root; nothing transacted")
                        {:reasons #{:maintenance/mixed-root} :from from :outside (vec (take 20 outside))})))))
  (when (and uri persist?) (dbfn/load-all-dbfn uri))
  ;; order: the retractions first (dry run, then persist), THEN the preview
  ;; the persist is pinned to — a retraction after the preview would move
  ;; the basis the pin asserts (the a4e3778 order, kept)
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
        _           (write-receipt! receipts "import-persist" persist)
        stamped     (when (and stamp? persist)
                      (stamp/stamp! (db/db) from persist))
        _           (write-receipt! receipts "identity-stamp" stamped)]
    (cond-> {:retract-dry-run retract-dry :retract retract-run :preview preview :persist persist}
      stamped (assoc :stamp stamped))))

(defn- retraction-line [u]
  (format "  %s: sections %d, slots %s, carrier %s"
          (:source u) (or (:retracted-sections u) 0)
          (if (seq (:retracted-slot-attrs u)) (pr-str (:retracted-slot-attrs u)) (str (or (:retracted-slots u) 0)))
          (boolean (:retracted-carrier? u))))

(defn- summarize [{:keys [retract-dry-run retract preview persist stamp] :as result}]
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
    (println "  planned modes:" (pr-str (frequencies (map :mode (:units preview)))))
    (doseq [u (:units preview)
            :when (or (pos? (or (:retracted-sections u) 0)) (pos? (or (:retracted-slots u) 0)) (:retracted-carrier? u))]
      (println (retraction-line u)))
    (doseq [u (:units preview) :when (seq (:conflicts u))]
      (println "  CONFLICT" (:source u) (pr-str (:conflicts u)))))
  (when persist
    (println (format "persist: persisted %d, conflicts %d, failed %d, refused %d, reconciled %s, final basis %s"
                     (:persisted-count persist) (:conflict-count persist) (:failed-count persist) (:refused-count persist)
                     (:reconciled? persist) (:final-basis persist))))
  (when stamp
    (println (format "identity stamp: %d stamped, %d already present, %d held — audit before starting writers"
                     (:stamped-count stamp) (:already-present-count stamp) (:held-count stamp)))
    (doseq [row (:results stamp) :when (= :held (:outcome row))]
      (println "  HELD" (:source row) (:reason row))))
  (println (if (complete? result) "complete: every step finished whole" "INCOMPLETE: a report carries a parse failure, conflict, failure or refusal — read the receipts")))

(defn- read-lines [f] (when f (remove str/blank? (str/split-lines (slurp f)))))

(defn -main [& args]
  (let [opts (loop [[a & more] args acc {}]
               (cond (nil? a) acc
                     (= a "--persist") (recur more (assoc acc :persist? true))
                     (= a "--stamp") (recur more (assoc acc :stamp? true))
                     (= a "--from") (recur (rest more) (assoc acc :from (first more)))
                     (= a "--exclude-file") (recur (rest more) (assoc acc :exclude (read-lines (first more))))
                     (= a "--retract-file") (recur (rest more) (assoc acc :retract (read-lines (first more))))
                     (= a "--receipts") (recur (rest more) (assoc acc :receipts (first more)))
                     (= a "--port") (recur (rest more) (assoc acc :port (Long/parseLong (first more))))
                     (= a "--mode") (recur (rest more) (assoc acc :mode (first more)))
                     :else (recur more acc)))]
    (when-not (:from opts)
      (println "Usage: lein maintenance-import -- --from <input-root> [--persist] [--stamp] [--exclude-file f] [--retract-file f] [--receipts dir] [--port 8389] [--mode replace|additive]")
      (System/exit 2))
    (let [result (try (run! (assoc opts :uri (db/db-uri)))
                      (catch clojure.lang.ExceptionInfo ex
                        (println "REFUSED:" (ex-message ex))
                        (when-let [outside (seq (:outside (ex-data ex)))]
                          (println "  outside memory/:" (pr-str outside)))
                        (System/exit 3)))]
      (summarize result)
      (System/exit (if (complete? result) 0 1)))))
