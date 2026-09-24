(ns sandbar.scripts.memory-open-affordance
  "Render a client memory-open recipe's generated catalog fragments:
   the ToolSearch selection, verb/axis count, and axis affordance table.

   Structural values come from sandbar.mcp.catalog-model, editorial guidance
   from catalog/affordance-editorial.edn, and eager selection from
   catalog/eager-core.edn. Missing editorial rows or unknown policy verbs
   fail loudly, keeping catalog changes visible to maintainers.

   This script prints the fenced region's content; it does not edit the
   consumer's recipe. Review and splice it into the intended client file.
   Usage: lein memory-open-affordance"
  (:require [clojure.string           :as str]
            [clojure.edn              :as edn]
            [clojure.java.io          :as io]
            [sandbar.mcp.catalog-model :as model]))

(defn- verb->tool-wire
  "sandbar.navigate.outbound-edges -> mcp__sandbar__sandbar_navigate_outbound-edges.
   The axis-separating dots become underscores; the leaf keeps its internal
   hyphens (matching the live ToolSearch select block)."
  [verb-name]
  (str "mcp__sandbar__" (str/replace verb-name "." "_")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (1) eager-core ToolSearch block
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-eager-core-block
  "model + eager-core policy -> the `ToolSearch select:...` line.
   FAILS LOUD if any :verbs-eager name is absent from the catalog (a rename that
   orphans an eager entry) or any :verbs-demoted name is absent (a typo that
   would silently fail to demote, letting a verb regress into the eager core)."
  [model policy]
  (let [catalog-names   (set (map :name (:verbs model)))
        eager           (:verbs-eager policy)
        demoted         (:verbs-demoted policy #{})
        missing         (remove catalog-names eager)
        demoted-missing (remove catalog-names demoted)
        included        (remove demoted eager)]
    (when (seq missing)
      (throw (ex-info "eager-core policy names verbs absent from the catalog"
                      {:missing (vec missing)})))
    (when (seq demoted-missing)
      (throw (ex-info "eager-core policy demotes verbs absent from the catalog"
                      {:demoted-missing (vec demoted-missing)})))
    (str "ToolSearch select:"
         (str/join "," (map verb->tool-wire included)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (2)+(3) count sentence + affordance table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn count-sentence
  "Render the current verb and axis counts from the shared catalog model."
  [model]
  (let [{:keys [verb-count axis-count]} (model/catalog-summary model)]
    (format "%d verbs / %d axes" verb-count axis-count)))

(defn render-affordance-table
  "Render Markdown table rows and header from the catalog model and editorial
   sidecar. Throw if an axis lacks editorial text or is omitted from the
   editorial order."
  [model editorial]
  (let [{:keys [by-axis]} (model/catalog-summary model)
        order          (:order editorial)
        axes-ed        (:axes editorial)
        catalog-axes   (set (keys by-axis))
        editorial-axes (set (keys axes-ed))
        undocumented   (remove editorial-axes catalog-axes)
        unordered      (remove (set order) catalog-axes)]
    (when (seq undocumented)
      (throw (ex-info "catalog axis lacks an editorial row — add one to catalog/affordance-editorial.edn"
                      {:undocumented (vec (sort undocumented))})))
    (when (seq unordered)
      (throw (ex-info "catalog axis missing from editorial :order"
                      {:unordered (vec (sort unordered))})))
    (let [sb (StringBuilder.)]
      (.append sb "| Axis | n | Reach for it when… | Schema |\n")
      (.append sb "|---|---|---|---|\n")
      (doseq [axis order
              :let [n (get by-axis axis)]
              :when n]
        (let [{:keys [reach-for schema]} (get axes-ed axis)]
          (.append sb (format "| `%s` | %d | %s | %s |\n"
                              (name axis) n reach-for schema))))
      (str sb))))

(defn render-region
  "Emit the full generated region: eager-core block + count sentence +
   affordance table, each wrapped in the sentinel markers the landed
   memory-open.md carries.  Byte-identical to the drift gate's regenerated
   region so the diff against the committed file is exactly the drift."
  [model editorial policy]
  (str
   "<!-- BEGIN GENERATED eager-core (source: verb-catalog + eager-core.edn; regen: lein memory-open-affordance) -->\n"
   "```\n"
   (render-eager-core-block model policy) "\n"
   "```\n"
   "<!-- END GENERATED eager-core -->\n\n"
   "<!-- BEGIN GENERATED affordance-count (source: verb-catalog) -->\n"
   "The full surface is **" (count-sentence model) "**.\n"
   "<!-- END GENERATED affordance-count -->\n\n"
   "<!-- BEGIN GENERATED affordance-table (source: verb-catalog + affordance-editorial.edn; regen: lein memory-open-affordance) -->\n"
   (render-affordance-table model editorial)
   "<!-- END GENERATED affordance-table -->\n"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sidecar loading — resources by default, path override for cross-repo runs.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-editorial
  "Editorial sidecar: from `path` if given, else the vendored resource."
  [path]
  (edn/read-string (slurp (or path (io/resource "catalog/affordance-editorial.edn")))))

(defn load-eager-core
  "Eager-core policy: from `path` if given, else the vendored resource."
  [path]
  (edn/read-string (slurp (or path (io/resource "catalog/eager-core.edn")))))

(defn -main
  "Project the verb catalog (DB-free) and print the generated memory-open region.
   Optional args: <editorial.edn-path> <eager-core.edn-path> (default: vendored
   resources)."
  [& [editorial-path policy-path]]
  (let [m         (model/build-catalog-model)
        editorial (load-editorial editorial-path)
        policy    (load-eager-core policy-path)]
    (print (render-region m editorial policy))
    (flush)
    (shutdown-agents)
    (System/exit 0)))
