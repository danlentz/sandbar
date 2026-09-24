(ns sandbar.scripts.mcp-verbs-doc
  "Generate the complete MCP reference from the source catalog and an editorial
   introduction. Rendering does not connect to or seed a database.

   The catalog model supplies names, behavioral hints, complete input schemas
   and per-operation descriptions. resources/catalog/reference-guide.edn owns
   the introduction. catalog-check compares the rendered bytes with the file.

   Usage: lein mcp-verbs-doc > doc/api/mcp-verbs.md"
  (:require [cheshire.core              :as json]
            [clojure.edn                :as edn]
            [clojure.java.io            :as io]
            [clojure.string             :as str]
            [clojure.walk               :as walk]
            [sandbar.mcp.catalog-model :as model]))

(defn- load-preamble []
  (:preamble (edn/read-string
              (slurp (io/resource "catalog/reference-guide.edn")))))

(defn- schema-type [spec]
  (or (:type spec)
      (when-let [alternatives (or (:oneOf spec) (:anyOf spec))]
        (str/join " or " (map schema-type alternatives)))
      "see schema"))

(defn- render-arg-lines
  "Summarize top-level arguments; the complete schema follows each summary."
  [input-schema]
  (let [props (:properties input-schema)
        req   (set (map name (or (:required input-schema) [])))]
    (if (empty? props)
      "**Arguments:** none.\n"
      (str "**Arguments** (`*` = required):\n\n"
           (->> props
                (map (fn [[k v]] [(name k) v]))
                (sort-by first)
                (map (fn [[pname pspec]]
                       (str "- `" pname "`" (when (contains? req pname) "\\*")
                            " (" (schema-type pspec) ")"
                            (when-let [d (:description pspec)]
                              (str " — " (str/trim (str d)))))))
                (str/join "\n"))
           "\n"))))

(defn- render-schema
  "Render every schema constraint, sorting map keys for reproducible bytes."
  [input-schema]
  (let [stable (walk/postwalk
                 (fn [x]
                   (if (map? x)
                     (into (sorted-map-by #(compare (str %1) (str %2))) x)
                     x))
                 input-schema)]
    (str "\n**Complete input schema:**\n\n```json\n"
         (json/generate-string stable {:pretty true})
         "\n```\n")))

(def ^:private prose-sections
  [[:which "WHICH"] [:when "WHEN"] [:how "HOW"]
   [:order "ORDER"] [:combination "COMBINATION"]])

(defn- render-verb [v]
  (let [sb (StringBuilder.)]
    (.append sb (format "### `%s`\n\n" (:name v)))
    (.append sb (format "%s. Catalog hint: `[%s]`. Wire name: `%s`.\n\n"
                        (:title v) (:safety v) (:wire-name v)))
    (.append sb (render-arg-lines (:input-schema v)))
    (.append sb (render-schema (:input-schema v)))
    (doseq [[k label] prose-sections]
      (when-let [text (get v k)]
        (when-not (str/blank? text)
          (.append sb (format "\n**%s:** %s\n" label (str/trim text))))))
    (str sb)))

(defn render
  "Render the full MCP reference from a catalog model without database access."
  [m]
  (let [{:keys [verb-count axis-count]} (model/catalog-summary m)
        by-axis (into (sorted-map) (group-by :axis (:verbs m)))
        sb      (StringBuilder.)]
    (.append sb (str "# MCP tool reference\n\n"
                    "_Generated from `sandbar.mcp.tools/verb-catalog` through "
                    "`sandbar.mcp.catalog-model`, with editorial text in "
                    "`resources/catalog/reference-guide.edn`. Regenerate with "
                    "`lein mcp-verbs-doc > doc/api/mcp-verbs.md` or "
                    "`lein catalog-regen`; verify with `lein catalog-check`._\n\n"))
    (.append sb (load-preamble))
    (.append sb (format (str "\n\n## Catalog inventory\n\n"
                             "%d operations across %d axes. Catalog hints: "
                             "`[safe]` classified read-only; `[idem]` classified idempotent write; "
                             "`[unsafe]` classified mutating. These classifications are not "
                             "authorization or a proof of every side effect. In particular, "
                             "export writes files. The operation descriptions and "
                             "[current release limits](../known-gaps-0.2.0.md) qualify use.\n\n")
                        verb-count axis-count))
    (doseq [[axis vs] by-axis]
      ;; Preserve the previous generated axis anchor for incoming links.
      (.append sb (format "<a id=\"%s-%d\"></a>\n\n## %s\n\n"
                          (name axis) (count vs) (name axis)))
      (doseq [v (sort-by :name vs)]
        (.append sb (render-verb v))
        (.append sb "\n")))
    (str (str/trimr (str sb)) "\n")))

(defn -main
  "Print the generated reference; no files or database state are changed."
  [& _]
  (print (render (model/build-catalog-model)))
  (flush)
  (shutdown-agents)
  (System/exit 0))
