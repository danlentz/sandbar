(ns sandbar.scripts.memory-open-affordance
  "Project the verb catalog -> the two machine-owned fragments of the corpus
   ceremony recipe `.claude/commands/memory-open.md`: the eager-core
   `ToolSearch select:` block, the `N verbs / M axes` count sentence, and the
   axis affordance table.

   This is the MISSING generator (design §5.5 + §6).  The memory-open table was
   the most-drifted projection (79/21) precisely because it had NO generator —
   it was hand-maintained markdown that fell three verbs and one axis behind the
   82-verb catalog.  This closes that gap: the structural columns (axis, n) come
   straight from `sandbar.mcp.catalog-model/catalog-summary`; the editorial
   columns (the 'reach for it when…' prose + EAGER/LAZY tier) come from the
   `catalog/affordance-editorial.edn` sidecar; the eager-core block is derived
   by applying the `catalog/eager-core.edn` policy to the model.

   FAIL-LOUD (design P4): a catalog axis with no editorial row, or an eager-core
   policy verb absent from the catalog, throws — the forcing function so a new
   axis/verb cannot slip into the surface undocumented.

   Because memory-open.md is a hand-edited recipe with much non-generated prose,
   the landed form owns a FENCED region delimited by sentinel comments; this
   generator emits that region's content.  It renders (it does not splice the
   live file); the lead splices the region in after review, and the drift gate
   (sandbar.scripts.catalog-check) diffs the region against the committed file.

   Usage: lein memory-open-affordance    ; prints the generated region to stdout"
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
  "The canonical 'N verbs / M axes' fragment from catalog-summary — the single
   source of every count string in memory-open.md (was hand-written '79/21')."
  [model]
  (let [{:keys [verb-count axis-count]} (model/catalog-summary model)]
    (format "%d verbs / %d axes" verb-count axis-count)))

(defn render-affordance-table
  "model + editorial sidecar -> the markdown table rows (with header).
   FAILS LOUD if a catalog axis has no editorial entry, or the editorial :order
   omits a catalog axis (design P4)."
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
