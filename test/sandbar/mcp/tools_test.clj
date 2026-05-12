(ns sandbar.mcp.tools-test
  "Test suite for the MCP tools layer (sandbar.mcp.tools) — focused on
   pure-data helpers (naming convention; JSON Schema mapping). DB-backed
   bootstrap-by-discovery tests would require the test-db fixture and
   land in C.4 when tools/call actually dispatches dt/* operations.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4."
  (:require [clojure.test     :refer :all]
            [sandbar.mcp.tools :as tools]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tool naming convention
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest class->tool-name-roundtrip
  (testing "keyword → tool name"
    (is (= "sandbar.class.zorp.Footwear"
           (tools/class->tool-name :zorp/Footwear)))
    (is (= "sandbar.class.dt.Class"
           (tools/class->tool-name :dt/Class)))
    (is (= "sandbar.class.mm.Memory"
           (tools/class->tool-name :mm/Memory))))
  (testing "map (entity-shaped) → tool name"
    (is (= "sandbar.class.mm.Section"
           (tools/class->tool-name {:db/ident :mm/Section})))))

(deftest tool-name->class-ident-roundtrip
  (testing "valid tool-name extracts class ident"
    (is (= :zorp/Footwear
           (tools/tool-name->class-ident "sandbar.class.zorp.Footwear")))
    (is (= :mm/Memory
           (tools/tool-name->class-ident "sandbar.class.mm.Memory"))))
  (testing "non-class-tool names return nil"
    (is (nil? (tools/tool-name->class-ident "not.a.class.tool")))
    (is (nil? (tools/tool-name->class-ident "sandbar.schema.classes")))))

(deftest naming-roundtrips-cleanly
  (testing "class→tool-name→class-ident is identity for class idents"
    (doseq [ident [:zorp/Footwear :dt/Class :mm/Memory :sandbar/User]]
      (is (= ident
             (-> ident
                 tools/class->tool-name
                 tools/tool-name->class-ident))
          (str "Roundtrip failed for " ident)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datomic-type → JSON Schema mapping
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
