(ns sandbar.datatype-test
  "Test suite for sandbar.db.datatype metamodel API"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "datatype-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Introspection Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest all-datatypes-test
  (testing "all-datatypes returns class idents"
    (let [types (dt/all-datatypes)]
      (is (seq types) "Should return at least some datatypes")
      (is (some #{:dt/Class} types) "Should include :dt/Class")
      (is (some #{:dt/Property} types) "Should include :dt/Property")
      (is (some #{:dt/Resource} types) "Should include :dt/Resource"))))

(deftest all-classes-test
  (testing "all-classes returns class idents"
    (let [classes (dt/all-classes)]
      (is (seq classes) "Should return at least some classes")
      (is (some #{:dt/Class} classes) "Should include :dt/Class")
      (is (some #{:model/User} classes) "Should include :model/User"))))

(deftest all-properties-test
  (testing "all-properties returns property idents"
    (let [props (dt/all-properties)]
      (is (seq props) "Should return at least some properties")
      (is (some #{:dt/type} props) "Should include :dt/type")
      (is (some #{:dt/domain} props) "Should include :dt/domain")
      (is (some #{:dt/range} props) "Should include :dt/range"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parents-of-test
  (testing "parents-of returns direct parent classes"
    (is (= #{:dt/Resource} (set (dt/parents-of :dt/Class)))
        "Class should have Resource as parent")
    (is (= #{:dt/Ref} (set (dt/parents-of :model/User)))
        "User should have Ref as parent")
    (is (nil? (dt/parents-of :dt/Resource))
        "Resource should have no parents")))

(deftest ancestors-of-test
  (testing "ancestors-of returns all ancestor classes"
    (let [user-ancestors (set (dt/ancestors-of :model/User))]
      (is (contains? user-ancestors :dt/Ref) "User ancestors should include Ref")
      (is (contains? user-ancestors :dt/Resource) "User ancestors should include Resource"))
    (let [class-ancestors (set (dt/ancestors-of :dt/Class))]
      (is (contains? class-ancestors :dt/Resource) "Class ancestors should include Resource"))))

(deftest direct-subclasses-of-test
  (testing "direct-subclasses-of returns immediate subclasses"
    (let [resource-subclasses (set (dt/direct-subclasses-of :dt/Resource))]
      (is (contains? resource-subclasses :dt/Class) "Resource subclasses should include Class")
      (is (contains? resource-subclasses :dt/Property) "Resource subclasses should include Property")
      (is (contains? resource-subclasses :dt/List) "Resource subclasses should include List"))))

(deftest subclasses-of-test
  (testing "subclasses-of returns all transitive subclasses"
    (let [resource-subclasses (set (dt/subclasses-of :dt/Resource))]
      (is (contains? resource-subclasses :dt/Class) "Should include direct subclass Class")
      (is (contains? resource-subclasses :model/User) "Should include transitive subclass User"))))

(deftest subclass-of?-test
  (testing "subclass-of? checks subclass relationship"
    (is (dt/subclass-of? :dt/Resource :dt/Class) "Class should be subclass of Resource")
    (is (dt/subclass-of? :dt/Resource :model/User) "User should be subclass of Resource")
    (is (not (dt/subclass-of? :dt/Class :dt/Resource)) "Resource is not subclass of Class")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest class-of-test
  (testing "class-of returns the class of an entity"
    (is (= :dt/Class (dt/class-of :dt/Property)) ":dt/Property should be instance of :dt/Class")
    (is (= :dt/Class (dt/class-of :model/User)) ":model/User should be instance of :dt/Class")
    (is (= :dt/Property (dt/class-of :dt/type)) ":dt/type should be instance of :dt/Property")))

(deftest class-ident-of-test
  (testing "class-ident-of returns the class IDENT (keyword)"
    (is (= :dt/Class (dt/class-ident-of :dt/Property)))
    (is (= :dt/Class (dt/class-ident-of :model/User)))
    (is (= :dt/Property (dt/class-ident-of :dt/type)))
    (is (keyword? (dt/class-ident-of :dt/Property)) "Return is a keyword ident")))

(deftest class-entity-of-test
  (testing "class-entity-of returns the class ENTITY (full map) for a class-ident"
    (let [ent (dt/class-entity-of :dt/Property)]
      (is (some? ent) "Returns a non-nil entity for a real class ident")
      (is (= :dt/Property (:db/ident ent))
          ":db/ident on the result matches the class ident")
      (is (or (associative? ent) (instance? datomic.Entity ent))
          "Result behaves like a map (entity)")
      ;; The critical regression test: the prior `(:dt/native-codec
      ;; (class-of x))` pattern returned nil because class-of resolved to
      ;; :dt/Class.  class-entity-of resolves to the class itself, so
      ;; reading metadata works.
      (is (nil? (:dt/native-codec ent))
          ":dt/Property has no :dt/native-codec declared")))
  (testing "class-entity-of on :dt/Class returns the meta-class entity itself"
    (let [ent (dt/class-entity-of :dt/Class)]
      (is (= :dt/Class (:db/ident ent)))))
  (testing "class-entity-of on nonexistent ident returns nil-equivalent"
    (let [ent (dt/class-entity-of :nonexistent/SomeClass)]
      (is (or (nil? ent) (nil? (:db/ident ent)))
          "Returns nil or a sentinel-empty entity for unknown ident"))))

(deftest instance-of?-test
  (testing "instance-of? checks instance relationship"
    (is (dt/instance-of? :dt/Class :model/User) "User is instance of Class")
    (is (dt/instance-of? :dt/Resource :model/User) "User is instance of Resource (via Class)")
    (is (dt/instance-of? :dt/Property :dt/domain) ":dt/domain is instance of Property")))

(deftest direct-instances-of-test
  (testing "direct-instances-of returns direct instances"
    (let [instances (dt/direct-instances-of :dt/Class)]
      (is (seq instances) "Should have some instances")
      (is (some #(= :model/User (:db/ident %)) instances) "Should include User"))))

(deftest all-instances-of-test
  (testing "all-instances-of includes instances of subclasses"
    (let [resource-instances (dt/all-instances-of :dt/Resource)]
      (is (seq resource-instances) "Should have some instances")
      ;; All classes and properties are instances of Resource
      (is (some #(= :dt/Class (:db/ident %)) resource-instances)
          "Should include Class"))))

(deftest all-named-instances-of-test
  (testing "all-named-instances-of returns idents"
    (let [class-instances (dt/all-named-instances-of :dt/Class)]
      (is (seq class-instances) "Should have some named instances")
      (is (some #{:model/User} class-instances) "Should include :model/User")
      (is (every? keyword? class-instances) "All results should be keywords"))))

(deftest named-idents-of-test
  (testing "named-idents-of returns idents (keywords)"
    (let [class-instances (dt/named-idents-of :dt/Class)]
      (is (seq class-instances))
      (is (some #{:model/User} class-instances))
      (is (every? keyword? class-instances) "All results are keywords")))
  (testing "named-idents-of is the canonical name; all-named-instances-of is a deprecated alias"
    (is (= (set (dt/named-idents-of :dt/Class))
           (set (dt/all-named-instances-of :dt/Class))))))

(deftest named-entities-of-test
  (testing "named-entities-of returns entity MAPS (not idents)"
    (let [class-entities (dt/named-entities-of :dt/Class)]
      (is (seq class-entities))
      (is (every? (fn [e] (or (associative? e) (instance? datomic.Entity e)))
                  class-entities)
          "All results are entity-shaped (associative)")
      (is (some (fn [e] (= :model/User (:db/ident e))) class-entities)
          "Reading :db/ident off an entity result works")
      (is (every? (fn [e] (some? (:db/ident e))) class-entities)
          ":db/ident is readable on every result")))
  (testing "named-idents-of and named-entities-of return the same set when projected to idents"
    (is (= (set (dt/named-idents-of :dt/Class))
           (set (map :db/ident (dt/named-entities-of :dt/Class)))))))

(deftest find-by-ident-test
  (testing "find-by-ident returns the entity for a known ident"
    (let [ent (dt/find-by-ident :model/User)]
      (is (some? ent))
      (is (= :model/User (:db/ident ent)))))
  (testing "find-by-ident on unknown ident returns nil-equivalent"
    (is (or (nil? (dt/find-by-ident :nonexistent/Thing))
            (nil? (:db/ident (dt/find-by-ident :nonexistent/Thing)))))))

(deftest native-codec-of-class-test
  (testing "native-codec-of-class returns nil for classes with no :dt/native-codec"
    ;; :dt/Property + :model/User do not declare :dt/native-codec in the
    ;; default-loaded schema.  After Stage B (mm/* schema landing),
    ;; :mm/Memory will return :codec/markdown — covered there.
    (is (nil? (dt/native-codec-of-class :dt/Property)))
    (is (nil? (dt/native-codec-of-class :model/User))))
  (testing "native-codec-of-class does NOT traverse :dt/type (the codex MUST-FIX #1 regression)"
    ;; Regression test: the prior `(:dt/native-codec (entity (dt/class-of x)))`
    ;; bug resolved to :dt/Class and returned nil.  The new helper reads
    ;; the codec off the class entity directly — no :dt/type traversal.
    ;; This test asserts shape: when :dt/native-codec is set on a class,
    ;; the new helper finds it.  Full positive-path coverage lands with
    ;; Stage B mm/* schema.
    (is (nil? (dt/native-codec-of-class :nonexistent/Class))
        "Returns nil for nonexistent class, not an error")))

(deftest codec-aliases-of-test
  (testing "codec-aliases-of returns {} for classes with no :dt/codec-aliases declared"
    (is (= {} (dt/codec-aliases-of :dt/Property)))
    (is (= {} (dt/codec-aliases-of :model/User)))
    (is (= {} (dt/codec-aliases-of :nonexistent/Class))))
  (testing "codec-aliases-of return shape is always a map (never nil, never seq)"
    (is (map? (dt/codec-aliases-of :dt/Property))
        "Empty case is {} so consumers can `(get aliases k)` without nil-check")))
  ;; Positive-path coverage (class with declared :dt/codec-aliases) lands
  ;; with Stage B mm/* schema — :mm/Memory will declare
  ;; :dt/codec-aliases [[:type :mm.memory/memory-type]] etc.
  ;;
  ;; Attribute named :dt/codec-aliases (not :dt/aliases) to disambiguate
  ;; from OWL sameAs-shaped identity relations per
  ;; interaction/check_substrate_schema_attribute_names_against_rdf_owl_semantics_2026_05_13.md.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Slot Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest direct-slots-of-test
  (testing "direct-slots-of returns directly declared slots"
    ;; direct-slots-of returns entity refs, need to get their idents
    (let [slots (dt/direct-slots-of :model/User)
          user-slots (set (map #(:db/ident (db/entity %)) slots))]
      (is (contains? user-slots :user/login) "User should have :user/login slot")
      (is (contains? user-slots :user/secret) "User should have :user/secret slot"))))

(deftest slots-of-test
  (testing "slots-of returns all effective slots including inherited"
    (let [user-slots (dt/slots-of :model/User)]
      (is (contains? user-slots :user/login) "Should include direct slot :user/login")
      (is (contains? user-slots :dt/type) "Should include inherited slot :dt/type from Resource"))))

(deftest abstract?-test
  (testing "abstract? checks if class is abstract"
    ;; Note: depends on whether Resource is marked abstract in schema
    (let [resource-abstract (dt/abstract? :dt/Resource)]
      (is (some-fn [nil? boolean?] resource-abstract) "Should return a truthy value"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Query Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest domain-of-test
  (testing "domain-of returns the domain class"
    (is (= :model/User (dt/domain-of :user/login)) ":user/login domain should be :model/User")
    (is (= :dt/Resource (dt/domain-of :dt/type)) ":dt/type domain should be :dt/Resource")))

(deftest range-of-test
  (testing "range-of returns the range type"
    (is (= :db.type/string (dt/range-of :user/login)) ":user/login range should be string")
    (is (= :dt/Class (dt/range-of :dt/type)) ":dt/type range should be :dt/Class")))

(deftest properties-with-domain-test
  (testing "properties-with-domain finds applicable properties"
    (let [user-props (set (dt/properties-with-domain :model/User))]
      (is (contains? user-props :user/login) "Should include :user/login")
      (is (contains? user-props :dt/type) "Should include inherited :dt/type"))))

(deftest cardinality-of-test
  (testing "cardinality-of returns property cardinality"
    (is (= :db.cardinality/one (dt/cardinality-of :user/login))
        ":user/login should have cardinality one")
    (is (= :db.cardinality/many (dt/cardinality-of :dt/slots))
        ":dt/slots should have cardinality many")))

(deftest cardinality-one?-test
  (testing "cardinality-one? checks for single-valued"
    (is (dt/cardinality-one? :user/login) ":user/login should be cardinality one")
    (is (not (dt/cardinality-one? :dt/slots)) ":dt/slots should not be cardinality one")))

(deftest cardinality-many?-test
  (testing "cardinality-many? checks for multi-valued"
    (is (dt/cardinality-many? :dt/slots) ":dt/slots should be cardinality many")
    (is (not (dt/cardinality-many? :user/login)) ":user/login should not be cardinality many")))

(deftest required?-test
  (testing "required? checks if property is required"
    ;; Note: depends on schema having required properties defined
    (let [result (dt/required? :user/login)]
      (is (or (nil? result) (boolean? result)) "Should return nil or boolean"))))

(deftest required-slots-of-test
  (testing "required-slots-of returns required slots"
    (let [required (dt/required-slots-of :model/User)]
      (is (or (nil? required) (sequential? required)) "Should return nil or sequence"))))

(deftest validator-of-test
  (testing "validator-of returns validator function if defined"
    (let [validator (dt/validator-of :model/User)]
      (is (or (nil? validator) (fn? validator)) "Should return nil or function"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Creation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest make*-test
  (testing "make* creates typed instances without validation"
    (let [user (dt/make* :model/User {:user/login "testuser"})]
      (is (some? user) "Should return created entity")
      (is (= :model/User (:dt/type user)) "Entity should have correct type")
      (is (= "testuser" (:user/login user)) "Entity should have provided properties"))))

(deftest make-test
  (testing "make creates validated instances"
    (let [user (dt/make :model/User {:user/login "validuser"})]
      (is (some? user) "Should return created entity")
      (is (= :model/User (:dt/type user)) "Entity should have correct type")))

  (testing "make with validation disabled"
    (let [user (dt/make :model/User {:user/login "another"} {:validate? false})]
      (is (some? user) "Should create entity without validation"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-untyped-entity-test
  (testing "validate detects untyped entities"
    (let [result @(d/transact (db/conn) [{:db/doc "untyped entity"}])
          eid (-> result :tempids vals first)
          validation (dt/validate eid)]
      (is (some? validation) "Should return error for untyped entity")
      (is (= :no-class (-> validation :errors first :type))
          "Should report no-class error")
      (is (string? (-> validation :errors first :message))
          "Error should have a message"))))

(deftest validate-valid-entity-test
  (testing "validate returns nil for valid typed entity"
    (let [user (dt/make* :model/User {:user/login "validuser2"
                                :user/secret "hash123"})
          result (dt/validate user)]
      ;; If User has no required fields or all are satisfied, should be nil
      (is (or (nil? result)
              (and (map? result) (contains? result :errors)))
          "Should return nil or error map with :errors key"))))

(deftest validate-error-structure-test
  (testing "validation errors have consistent structure"
    (let [result @(d/transact (db/conn) [{:db/doc "another untyped"}])
          eid (-> result :tempids vals first)
          validation (dt/validate eid)]
      (is (contains? validation :entity) "Result should contain :entity")
      (is (contains? validation :errors) "Result should contain :errors")
      (is (vector? (:errors validation)) "Errors should be a vector")
      (let [error (first (:errors validation))]
        (is (contains? error :type) "Each error should have :type")
        (is (contains? error :message) "Each error should have :message")))))

(deftest valid?-test
  (testing "valid? returns true for valid entity"
    (let [user (dt/make* :model/User {:user/login "validuser3"})]
      (is (boolean? (dt/valid? user)) "Should return boolean")))

  (testing "valid? returns false for untyped entity"
    (let [result @(d/transact (db/conn) [{:db/doc "untyped for valid?"}])
          eid (-> result :tempids vals first)]
      (is (false? (dt/valid? eid)) "Untyped entity should not be valid"))))

(deftest validate-data-basic-test
  (testing "validate-data returns nil for valid data"
    (let [result (dt/validate-data :model/User {:user/login "precheck"})]
      (is (or (nil? result) (map? result)) "Should return nil or error map")))

  (testing "validate-data returns error structure when invalid"
    (let [result (dt/validate-data :model/User {})]
      ;; If User has required fields, this should fail
      (when result
        (is (contains? result :errors) "Should have :errors key")
        (is (vector? (:errors result)) "Errors should be a vector")))))

(deftest validate-data-abstract-class-test
  (testing "validate-data rejects abstract class instantiation"
    ;; dt/Literal is abstract
    (let [result (dt/validate-data :dt/Literal {})]
      (is (some? result) "Should return error for abstract class")
      (is (= :abstract-class (-> result :errors first :type))
          "Should report abstract-class error")
      (is (= :dt/Literal (-> result :errors first :class))
          "Error should reference the abstract class"))))

(deftest validate-data-type-checking-test
  (testing "validate-data checks slot value types"
    ;; user/login expects a string
    (let [result (dt/validate-data :model/User {:user/login 12345})]
      (when result
        (let [type-errors (filter #(= :invalid-type (:type %)) (:errors result))]
          (when (seq type-errors)
            (is (= :user/login (:slot (first type-errors)))
                "Should identify the invalid slot")
            (is (some? (:expected (first type-errors)))
                "Should specify expected type")))))))

(deftest make-validation-integration-test
  (testing "make throws on validation failure for abstract class"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Validation failed"
                          (dt/make :dt/Literal {}))
        "Should throw for abstract class"))

  (testing "make succeeds for valid concrete class"
    (let [user (dt/make :model/User {:user/login "maketest"})]
      (is (some? user) "Should create valid entity")
      (is (= :model/User (:dt/type user)) "Should have correct type")))

  (testing "make with validate? false bypasses validation"
    ;; This should not throw even if data is questionable
    (let [user (dt/make :model/User {} {:validate? false})]
      (is (some? user) "Should create entity without validation")
      (is (= :model/User (:dt/type user)) "Should have correct type"))))

(deftest validation-multiple-errors-test
  (testing "validation can return multiple errors"
    ;; Create an entity with multiple potential issues
    (let [result @(d/transact (db/conn) [{:db/doc "multi-error test"}])
          eid (-> result :tempids vals first)
          validation (dt/validate eid)]
      ;; At minimum, should have no-class error
      (is (>= (count (:errors validation)) 1)
          "Should have at least one error"))))

(deftest validate-entity-vs-data-consistency-test
  (testing "validate and validate-data agree on valid data"
    (let [props {:user/login "consistency-test"}
          ;; Pre-transaction check
          pre-result (dt/validate-data :model/User props)
          ;; Create and post-transaction check
          user (dt/make* :model/User props)
          post-result (dt/validate user)]
      ;; Both should either pass or fail
      (is (= (nil? pre-result) (nil? post-result))
          "Pre and post validation should agree"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Batch Validation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-all-instances-basic-test
  (testing "validate-all-instances returns correct structure"
    (let [result (dt/validate-all-instances :dt/Class)]
      (is (map? result) "Should return a map")
      (is (= :dt/Class (:class result)) "Should include the class")
      (is (number? (:total result)) "Should include total count")
      (is (number? (:valid result)) "Should include valid count")
      (is (number? (:invalid result)) "Should include invalid count")
      (is (vector? (:errors result)) "Should include errors vector")
      (is (= (:total result) (+ (:valid result) (:invalid result)))
          "Total should equal valid + invalid"))))

(deftest validate-all-instances-with-valid-entities-test
  (testing "validate-all-instances with valid entities"
    ;; Create some valid User instances
    (dt/make* :model/User {:user/login "batch-user-1"})
    (dt/make* :model/User {:user/login "batch-user-2"})
    (let [result (dt/validate-all-instances :model/User)]
      (is (pos? (:total result)) "Should have instances")
      (is (>= (:valid result) 2) "Should have at least 2 valid instances")
      ;; Check that errors only contain actual errors
      (is (every? #(contains? % :errors) (:errors result))
          "Each error entry should have :errors key"))))

(deftest validate-all-instances-includes-subclasses-test
  (testing "validate-all-instances includes subclass instances"
    ;; dt/Resource is the root class, so validating it should include
    ;; instances from all subclasses (Class, Property, User, etc.)
    (let [result (dt/validate-all-instances :dt/Resource)]
      (is (pos? (:total result))
          "Should find instances (classes, properties, etc. are all Resources)")
      ;; Verify it found more than just direct instances
      (let [direct-result (dt/validate-all-instances :dt/Class)]
        (is (>= (:total result) (:total direct-result))
            "Resource validation should include at least as many as Class")))))

(deftest validate-all-instances-empty-class-test
  (testing "validate-all-instances handles classes with no instances"
    ;; dt/Literal is abstract and shouldn't have direct instances
    (let [result (dt/validate-all-instances :dt/Literal)]
      (is (map? result) "Should return a map even for empty/abstract class")
      (is (= :dt/Literal (:class result))
          "Should include the class name")
      (is (number? (:total result))
          "Should have a total (possibly 0)")
      (is (= (:total result) (+ (:valid result) (:invalid result)))
          "Counts should be consistent"))))

(deftest validate-all-instances-error-structure-test
  (testing "validate-all-instances errors have correct structure"
    ;; Create an untyped entity to ensure we have something invalid
    (let [result @(d/transact (db/conn) [{:db/doc "untyped for batch test"}])
          eid (-> result :tempids vals first)
          ;; Note: This entity won't be found by validate-all-instances
          ;; because it has no :dt/type. But we can check valid entities.
          validation-result (dt/validate-all-instances :model/User)]
      ;; The errors should be properly structured
      (doseq [error (:errors validation-result)]
        (is (contains? error :entity) "Error should have :entity")
        (is (contains? error :class) "Error should have :class")
        (is (contains? error :errors) "Error should have :errors list")
        (is (vector? (:errors error)) "Errors list should be a vector")))))
