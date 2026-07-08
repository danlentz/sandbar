(ns sandbar.mcp.tool-name-rename-test
  "Unit + in-process coverage for the dots→underscores MCP tool-name rename
   (decisions/sandbar_mcp_tool_names_underscore_not_dot_durable_fix_not_papering_over_dan_directive_2026_07_04.md).

   Asserts the wire projection is pattern-conformant + bijective + round-trips;
   tools/list advertises ONLY underscore names; tools/call resolves BOTH the
   underscore name and the deprecated dotted alias; and — THE authz-flip guard —
   every verb's read-only classification is IDENTICAL whether reached by the
   canonical dotted name or resolved from its wire name.  The internal catalog
   identity stays dotted, so this whole suite is the standing tripwire proving
   the rename never flips a verb's authorization class."
  (:require [clojure.string    :as str]
            [clojure.test      :refer :all]
            [sandbar.mcp.tools :as tools]))

(def ^:private wire-pattern #"^[a-zA-Z0-9_-]{1,64}$")
(def ^:private catalog-names (map :name tools/verb-catalog))
(def ^:private wire->canonical @#'tools/wire->canonical)
(def ^:private resolve-tool-name @#'tools/resolve-tool-name)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The wire projection

(deftest wire-name-transform-samples
  (is (= "sandbar_entity_find" (tools/wire-name "sandbar.entity.find")))
  (is (= "sandbar_class_validate-all-instances"
         (tools/wire-name "sandbar.class.validate-all-instances"))
      "hyphens inside leaf tokens are preserved — only dotted separators change")
  (is (= "sandbar_entity_find-by-rel-path"
         (tools/wire-name "sandbar.entity.find-by-rel-path")))
  (is (= "sandbar_aggregate_group-by"
         (tools/wire-name "sandbar.aggregate.group-by"))))

(deftest every-wire-name-is-pattern-conformant
  (doseq [n catalog-names]
    (let [w (tools/wire-name n)]
      (is (not (str/includes? w ".")) (str "wire name still carries a dot: " w))
      (is (re-matches wire-pattern w) (str "wire name off Anthropic pattern: " w)))))

(deftest wire-projection-is-bijective
  (is (= (count catalog-names)
         (count (distinct (map tools/wire-name catalog-names)))
         (count wire->canonical))
      "dots→underscores must be injective over the catalog (no wire collision)"))

(deftest wire-resolves-back-to-canonical
  (doseq [n catalog-names]
    (is (= n (get wire->canonical (tools/wire-name n))))
    (is (= {:canonical n :deprecated? false} (resolve-tool-name (tools/wire-name n)))
        "the NEW underscore name resolves to canonical, not deprecated")
    (is (= {:canonical n :deprecated? true} (resolve-tool-name n))
        "the OLD dotted name resolves to itself and is flagged deprecated")))

(deftest resolve-unknown-name-yields-nil-canonical
  (is (= {:canonical nil :deprecated? false} (resolve-tool-name "sandbar_not_a_verb")))
  (is (= {:canonical nil :deprecated? false} (resolve-tool-name "nonexistent.tool"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/list emission (in-process handler)

(deftest tools-list-advertises-only-underscore-names
  (let [tools-vec (get-in (tools/handle-list 1 {}) [:result :tools])
        names     (map :name tools-vec)]
    (is (= (count tools/verb-catalog) (count tools-vec)) "count unchanged")
    (is (every? #(not (str/includes? % ".")) names) "NO dotted names advertised")
    (is (every? #(re-matches wire-pattern %) names) "every advertised name on-pattern")
    (is (= (set (map tools/wire-name catalog-names)) (set names))
        "the advertised set is exactly the wire projection of the catalog")
    (is (every? #(contains? % :annotations) tools-vec)
        "annotations survive the wire-name assoc")
    (is (not-any? #(contains? % :handler) tools-vec) "handler still stripped")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; THE authz-flip guard — classification identical via wire vs dotted

(deftest authz-classification-identical-via-wire-and-dotted
  ;; For EVERY verb, the read-only decision + behavioral hints reached by the
  ;; canonical dotted name equal those reached by resolving its wire name.  This
  ;; is the standing proof that the rename cannot flip a verb's authz class.
  (doseq [n catalog-names]
    (let [via-wire (:canonical (resolve-tool-name (tools/wire-name n)))]
      (is (= n via-wire))
      (is (= (tools/verb-permitted-for-read-only? n)
             (tools/verb-permitted-for-read-only? via-wire))
          (str "read-only classification differs via wire vs dotted for " n))
      (is (= (tools/verb-behavioral-hints n)
             (tools/verb-behavioral-hints via-wire))
          (str "behavioral hints differ via wire vs dotted for " n)))))

(deftest specific-authz-anchors-hold-under-rename
  ;; A read verb stays read-only; a mutating verb stays denied — reached by the
  ;; NEW wire name resolved back to canonical.
  (let [ro (:canonical (resolve-tool-name "sandbar_entity_find"))
        wr (:canonical (resolve-tool-name "sandbar_entity_create"))
        ex (:canonical (resolve-tool-name "sandbar_project_export"))]
    (is (= "sandbar.entity.find" ro))
    (is (= "sandbar.entity.create" wr))
    (is (tools/verb-permitted-for-read-only? ro) "entity.find stays read-only-permitted")
    (is (not (tools/verb-permitted-for-read-only? wr)) "entity.create stays mutating/denied")
    (is (not (tools/verb-permitted-for-read-only? ex))
        "project.export stays on the read-only-denied-override (leaf-classifies read-only but writes FS)")))
