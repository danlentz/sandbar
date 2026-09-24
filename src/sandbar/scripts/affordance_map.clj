(ns sandbar.scripts.affordance-map
  "Generate a reader's operation guide followed by the complete lean catalog.
   The source catalog model supplies every operation, title, axis and hint;
   resources/catalog/operation-guide.edn supplies the question-led introduction.
   Rendering does not read or refresh persisted :mm/Verb cards.

   Usage: lein affordance-map > doc/mcp-affordance-map.md"
  (:require [clojure.edn                :as edn]
            [clojure.java.io            :as io]
            [sandbar.mcp.catalog-model :as model]))

(defn render
  "Render the operation guide and inventory from a catalog model."
  [m]
  (let [{:keys [verb-count axis-count]} (model/catalog-summary m)
        by-axis (into (sorted-map) (group-by :axis (:verbs m)))
        intro   (:introduction (edn/read-string
                                 (slurp (io/resource "catalog/operation-guide.edn"))))
        sb      (StringBuilder.)]
    (.append sb (str "# Choose an MCP operation by the question\n\n"
                    "_Generated from `sandbar.mcp.tools/verb-catalog` through "
                    "`sandbar.mcp.catalog-model`, with editorial text in "
                    "`resources/catalog/operation-guide.edn`. Regenerate with "
                    "`lein affordance-map > doc/mcp-affordance-map.md` or "
                    "`lein catalog-regen`; verify with `lein catalog-check`._\n\n"
                    intro "\n\n"))
    (.append sb (format (str "## Complete operation inventory\n\n"
                             "%d operations across %d axes. These rows come from the source catalog, "
                             "not a database seed. Catalog hints: `[safe]` classified read-only; "
                             "`[idem]` classified idempotent write; `[unsafe]` classified mutating. "
                             "A hint does not grant permission or establish all side effects. "
                             "Use the [complete reference](api/mcp-verbs.md) for schemas and behavior.\n\n")
                        verb-count axis-count))
    (doseq [[axis vs] by-axis]
      (.append sb (format "### %s (%d)\n\n" (name axis) (count vs)))
      (doseq [v (sort-by :name vs)]
        (.append sb (format "- `%s` — %s [%s]\n"
                            (:name v) (or (:title v) "") (:safety v))))
      (.append sb "\n"))
    (str sb)))

(defn -main
  "Print the generated map; no files or database state are changed."
  [& _]
  (print (render (model/build-catalog-model)))
  (flush)
  (shutdown-agents)
  (System/exit 0))
