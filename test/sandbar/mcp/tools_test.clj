(ns sandbar.mcp.tools-test
  "Test suite for the MCP tools layer (sandbar.mcp.tools) — pure tests for
   the operational verb catalog shape + projection helpers + JSON Schema
   mapping.  DB-backed handler dispatch tests live alongside the test
   fixture under sandbar.mcp.tools-db-test (F-M-005 release-gate suite).

   Per decisions/sandbar_mcp_tool_surface_resolution_operational_verb_catalog_per_adr_b13_2026_05_12.md
   (F-B-001 resolution)."
  (:require [clojure.test     :refer :all]
            [sandbar.mcp.tools :as tools]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Verb catalog shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest verb-catalog-is-stable-and-shaped
  (testing "catalog is a non-empty sequential collection"
    (is (sequential? tools/verb-catalog))
    (is (pos? (count tools/verb-catalog))))

  (testing "every entry has the required keys"
    (doseq [entry tools/verb-catalog]
      (is (string? (:name entry)) (str "missing :name in " entry))
      (is (string? (:title entry)) (str "missing :title in " entry))
      (is (string? (:description entry)) (str "missing :description in " entry))
      (is (map? (:inputSchema entry)) (str "missing :inputSchema in " entry))
      (is (fn? (:handler entry)) (str "missing :handler in " entry))))

  (testing "every name is unique"
    (let [names (map :name tools/verb-catalog)]
      (is (= (count names) (count (distinct names))))))

  (testing "names follow the sandbar.<group>(.<verb>)? convention"
    ;; Most verbs are sandbar.<group>.<verb> (3 dot-separated segments).
    ;; Stage 7.D introduced sandbar.ground (2 segments) as an intentional
    ;; sandbar-level entry point — per ADR §2.5, the grounding workflow is
    ;; load-bearing and NOT in a sub-namespace.  Regex accepts both forms.
    (doseq [entry tools/verb-catalog]
      (is (re-matches #"sandbar\.[a-z]+(\.[a-z][a-z\-]*)?"
                      (:name entry))
          (str ":name doesn't match convention: " (:name entry))))))

(deftest expected-verbs-present
  (testing "schema introspection verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.schema.classes"))
      (is (contains? names "sandbar.schema.properties"))
      (is (contains? names "sandbar.schema.datatypes"))
      (is (contains? names "sandbar.schema.entities")
          "Stage G Signal 8 — batch verb for N+1 elimination")))

  (testing "class introspection verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.class.describe"))
      (is (contains? names "sandbar.class.slots"))
      (is (contains? names "sandbar.class.direct-slots"))
      (is (contains? names "sandbar.class.required-slots"))
      (is (contains? names "sandbar.class.instances"))
      (is (contains? names "sandbar.class.subclasses"))
      (is (contains? names "sandbar.class.parents"))
      (is (contains? names "sandbar.class.validate-all-instances"))))

  (testing "type predicate verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.types.instance-of"))
      (is (contains? names "sandbar.types.subclass-of"))))

  (testing "property introspection verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.property.domain"))
      (is (contains? names "sandbar.property.range"))
      (is (contains? names "sandbar.property.cardinality"))))

  (testing "entity operation verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.entity.create"))
      (is (contains? names "sandbar.entity.find"))
      (is (contains? names "sandbar.entity.update"))
      (is (contains? names "sandbar.entity.validate"))))

  (testing "workflow operation verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.workflow.define"))
      (is (contains? names "sandbar.workflow.find"))
      (is (contains? names "sandbar.workflow.start-process"))
      (is (contains? names "sandbar.workflow.transition"))
      (is (contains? names "sandbar.workflow.process-state"))
      (is (contains? names "sandbar.workflow.process-history"))
      (is (contains? names "sandbar.workflow.active-processes"))))

  (testing "validation service verbs"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.validation.start"))
      (is (contains? names "sandbar.validation.run"))
      (is (contains? names "sandbar.validation.cancel"))
      (is (contains? names "sandbar.validation.retry"))
      (is (contains? names "sandbar.validation.results"))
      (is (contains? names "sandbar.validation.history"))))

  (testing "codec + project-graph verbs (Stage F.3b)"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.codec.list"))
      (is (contains? names "sandbar.project.export"))
      (is (contains? names "sandbar.project.import"))))

  (testing "aggregation verbs (Stage 14 — fulltext arc Phase G)"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.aggregate.count"))
      (is (contains? names "sandbar.aggregate.group-by"))
      (is (contains? names "sandbar.aggregate.rank-by"))))

  (testing "navigation path-via verb (Stage P-6 — fulltext arc Stage P)"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.navigate.path-via"))))

  (testing "navigation siblings-of verb (Stage 22 — fulltext arc Phase N)"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.navigate.siblings-of"))))

  (testing "orientation library-card verb (Phase O — fulltext arc)"
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.orient.library-card"))))

  (testing "tag-vocabulary verbs (Stage 7.D — tag-modeling first-class arc)"
    ;; Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md §2.5
    (let [names (set (map :name tools/verb-catalog))]
      (is (contains? names "sandbar.ground")           "compositional grounding workflow (sandbar-level)")
      (is (contains? names "sandbar.tag.lookup")       "tag-vocabulary primitive")
      (is (contains? names "sandbar.tag.define")       "author new canonical tag")
      (is (contains? names "sandbar.tag.audit")        "run 7 tag-lifecycle invariants")
      (is (contains? names "sandbar.tag.consolidate")  "merge tags; preserve alt-label")
      (is (contains? names "sandbar.tag.split")        "partition into narrower tags")
      (is (contains? names "sandbar.tag.rename")       "rename canonical; preserve hidden-label")
      (is (contains? names "sandbar.tag.align")        "cross-vocabulary SKOS mapping")
      (is (contains? names "sandbar.tag.harmonize")    "bulk-harmonization DRY-RUN report"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aggregation verb input-schema + handler-error tests (Stage 14)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- aggregate-verb [verb-name]
  (->> tools/verb-catalog
       (some (fn [v] (when (= verb-name (:name v)) v)))))

(deftest aggregation-verb-input-schemas-shape
  (testing "sandbar.aggregate.count requires :class only"
    (let [schema (:inputSchema (aggregate-verb "sandbar.aggregate.count"))]
      (is (= ["class"] (:required schema)))
      (is (contains? (:properties schema) :class))
      (is (contains? (:properties schema) :where))))

  (testing "sandbar.aggregate.group-by requires :class + :group-by"
    (let [schema (:inputSchema (aggregate-verb "sandbar.aggregate.group-by"))]
      (is (= #{"class" "group-by"} (set (:required schema))))
      (is (contains? (:properties schema) :class))
      (is (contains? (:properties schema) :group-by))
      (is (contains? (:properties schema) :where))))

  (testing "sandbar.aggregate.rank-by requires :class + :rank-by"
    (let [schema (:inputSchema (aggregate-verb "sandbar.aggregate.rank-by"))]
      (is (= #{"class" "rank-by"} (set (:required schema))))
      (is (contains? (:properties schema) :class))
      (is (contains? (:properties schema) :rank-by))
      (is (contains? (:properties schema) :limit))
      (is (contains? (:properties schema) :temporal-slot)))))

(deftest aggregation-handler-required-args
  (testing "sandbar.aggregate.count rejects missing :class with isError"
    (let [response (tools/handle-call 1 {:name "sandbar.aggregate.count"
                                         :arguments {}})]
      (is (true? (-> response :result :isError)))
      (is (re-find #"(?i)class"
                   (-> response :result :content first :text)))))

  (testing "sandbar.aggregate.group-by rejects missing :group-by with isError"
    (let [response (tools/handle-call 1 {:name "sandbar.aggregate.group-by"
                                         :arguments {"class" ":mm/Memory"}})]
      (is (true? (-> response :result :isError)))
      (is (re-find #"(?i)group-by"
                   (-> response :result :content first :text)))))

  (testing "sandbar.aggregate.rank-by rejects missing :rank-by with isError"
    (let [response (tools/handle-call 1 {:name "sandbar.aggregate.rank-by"
                                         :arguments {"class" ":mm/Memory"}})]
      (is (true? (-> response :result :isError)))
      (is (re-find #"(?i)rank-by"
                   (-> response :result :content first :text))))))

(deftest aggregation-where-malformed-edn-rejected
  (testing "malformed :where EDN string raises isError before substrate call"
    (let [response (tools/handle-call 1 {:name "sandbar.aggregate.count"
                                         :arguments {"class" ":mm/Memory"
                                                     "where" "["}})]
      (is (true? (-> response :result :isError)))
      (is (re-find #"(?i):where|EDN"
                   (-> response :result :content first :text))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation path-via verb shape (Stage P-6)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- nav-verb [verb-name]
  (->> tools/verb-catalog
       (some (fn [v] (when (= verb-name (:name v)) v)))))

(deftest navigate-path-via-input-schema-shape
  (let [schema (:inputSchema (nav-verb "sandbar.navigate.path-via"))]
    (is (= #{"from" "via"} (set (:required schema))))
    (is (contains? (:properties schema) :from))
    (is (contains? (:properties schema) :via))
    (is (contains? (:properties schema) :limit))
    (is (contains? (:properties schema) :include))))

(deftest navigate-path-via-rejects-missing-from
  (let [response (tools/handle-call 1 {:name "sandbar.navigate.path-via"
                                       :arguments {"via" ":cites"}})]
    (is (true? (-> response :result :isError)))
    (is (re-find #"(?i)from" (-> response :result :content first :text)))))

(deftest navigate-path-via-rejects-missing-via
  (let [response (tools/handle-call 1 {:name "sandbar.navigate.path-via"
                                       :arguments {"from" ":dt/Property"}})]
    (is (true? (-> response :result :isError)))
    (is (re-find #"(?i)via" (-> response :result :content first :text)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/list response shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest handle-list-returns-catalog-without-handlers
  (let [response (tools/handle-list 42 {})
        result   (:result response)
        tools-vec (:tools result)]
    (testing "response is a JSON-RPC success envelope"
      (is (= "2.0" (:jsonrpc response)))
      (is (= 42 (:id response)))
      (is (some? result)))

    (testing "tools list contains every verb"
      (is (= (count tools/verb-catalog) (count tools-vec))))

    (testing "tools list omits :handler keys (not JSON-serializable)"
      (doseq [tool tools-vec]
        (is (not (contains? tool :handler))
            (str "tool entry leaks :handler: " tool))))

    (testing "tools list preserves :name + :inputSchema (the MCP-visible parts)"
      (doseq [tool tools-vec]
        (is (string? (:name tool)))
        (is (map? (:inputSchema tool)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/call dispatch — error paths (no DB required)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unknown-tool-returns-32602
  (let [response (tools/handle-call 1 {:name "nonexistent.tool"
                                        :arguments {}})]
    (is (= -32602 (-> response :error :code)))
    (is (re-find #"Unknown tool" (-> response :error :message)))
    (is (some? (-> response :error :data :available-tools)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datomic-type → JSON Schema mapping (carried over from prior tests)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest datomic-type-mapping
  (testing "primitive types map to canonical JSON Schema types"
    (is (= "string"  (:type (tools/datomic-type->json-schema :db.type/string))))
    (is (= "integer" (:type (tools/datomic-type->json-schema :db.type/long))))
    (is (= "number"  (:type (tools/datomic-type->json-schema :db.type/double))))
    (is (= "boolean" (:type (tools/datomic-type->json-schema :db.type/boolean))))
    (is (= "string"  (:type (tools/datomic-type->json-schema :db.type/instant))))
    (is (= "date-time" (:format (tools/datomic-type->json-schema :db.type/instant))))
    (is (= "uuid"    (:format (tools/datomic-type->json-schema :db.type/uuid))))
    (is (= "uri"     (:format (tools/datomic-type->json-schema :db.type/uri)))))
  (testing "ref type carries a description"
    (let [r (tools/datomic-type->json-schema :db.type/ref)]
      (is (= "string" (:type r)))
      (is (re-find #"(?i)reference" (:description r)))))
  (testing "unknown type degrades gracefully"
    (let [r (tools/datomic-type->json-schema :db.type/oddball)]
      (is (= "string" (:type r)))
      (is (re-find #":db.type/oddball" (:description r))))))
