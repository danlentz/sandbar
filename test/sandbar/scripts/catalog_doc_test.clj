(ns sandbar.scripts.catalog-doc-test
  "Regression checks for complete schemas and reproducible local documentation."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.catalog-model :as model]
            [sandbar.mcp.tools :as tools]
            [sandbar.scripts.affordance-map :as affordance]
            [sandbar.scripts.catalog-check :as check]
            [sandbar.scripts.mcp-verbs-doc :as reference]))

(use-fixtures :each
  (fn [f]
    (let [no-database (fn [& _] (throw (AssertionError. "Documentation must not access a database")))]
      (with-redefs [db/conn no-database
                    db/db no-database
                    d/connect no-database
                    d/create-database no-database]
        (f)))))

(defn- wire-value [x]
  (json/parse-string (json/generate-string x) true))

(deftest complete-reference-schemas-match-tools-list
  (let [doc (reference/render (model/build-catalog-model))
        sections (re-seq #"(?s)### `(sandbar\.[^`]+)`\n.*?\*\*Complete input schema:\*\*\n\n```json\n(.*?)\n```" doc)
        documented (into {} (map (fn [[_ name schema]]
                                   [(tools/wire-name name) (json/parse-string schema true)])
                                 sections))
        advertised (into {} (map (juxt :name (comp wire-value :inputSchema))
                                  (get-in (tools/handle-list 1 {}) [:result :tools])))]
    ;; Equality covers nested oneOf/items/required/default/enum constraints, not
    ;; just the count or top-level property names that the old renderer retained.
    (is (= (count advertised) (count sections)))
    (is (= advertised documented))
    (doseq [[_ block] (re-seq #"(?s)```json\n(.*?)\n```" doc)]
      (is (map? (json/parse-string block true)) "Every JSON fence is an actual object"))))

(deftest regeneration-and-drift-check-use-the-same-renderers
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "sandbar-catalog-docs-" (make-array java.nio.file.attribute.FileAttribute 0)))
        aff (io/file dir "operation-map.md")
        ref (io/file dir "reference.md")
        args ["--affordance" (.getPath aff) "--mcp-verbs" (.getPath ref)]
        run (fn [options]
              (binding [*out* (java.io.StringWriter.)]
                ;; Avoid optional client projections inherited from a developer's
                ;; environment; these checks own only the explicit temp files.
                (with-redefs-fn
                  {#'sandbar.scripts.catalog-check/resolve-path
                   (fn [cli k _ default] (get cli k default))}
                  #(check/run options))))]
    (try
      (is (= 0 (run (into ["--write"] args))))
      (let [m (model/build-catalog-model)]
        (is (= (affordance/render m) (slurp aff)))
        (is (= (reference/render m) (slurp ref))))
      (is (= 0 (run args)))
      (spit ref "stale documentation\n")
      (is (= 1 (run args)))
      (is (= "stale documentation\n" (slurp ref)) "Checking alone does not repair files")
      (is (= 0 (run (into ["--write"] args))))
      (finally
        (doseq [file (reverse (file-seq dir))]
          (io/delete-file file true))))))
