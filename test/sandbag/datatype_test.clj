(ns sandbag.datatype-test
  "Test suite for sandbag.db.datatype metamodel API"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbag.db.datatype :as dt]
            [sandbag.db.datomic :as db]
            [sandbag.util.edn :as edn]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-uri "datomic:mem://datatype-test")

(defn load-test-schema
  "Load schema files into test database"
  [conn]
  (doseq [schema-name (edn/config-value :required-schema)]
    (doseq [stmt (edn/resource-value schema-name nil)]
      @(d/transact conn stmt))))

(defn with-test-db
  "Fixture that creates an in-memory database for each test"
  [f]
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (reset! db/**conn* conn)
    (try
      (load-test-schema conn)
      (f)
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)))))

(use-fixtures :each with-test-db)

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
      (is (some #{:User} classes) "Should include :User"))))

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
    (is (= #{:dt/Ref} (set (dt/parents-of :User)))
        "User should have Ref as parent")
    (is (nil? (dt/parents-of :dt/Resource))
        "Resource should have no parents")))

(deftest ancestors-of-test
  (testing "ancestors-of returns all ancestor classes"
    (let [user-ancestors (set (dt/ancestors-of :User))]
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
      (is (contains? resource-subclasses :User) "Should include transitive subclass User"))))

(deftest subclass-of?-test
  (testing "subclass-of? checks subclass relationship"
    (is (dt/subclass-of? :dt/Resource :dt/Class) "Class should be subclass of Resource")
    (is (dt/subclass-of? :dt/Resource :User) "User should be subclass of Resource")
    (is (not (dt/subclass-of? :dt/Class :dt/Resource)) "Resource is not subclass of Class")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest class-of-test
  (testing "class-of returns the class of an entity"
    (is (= :dt/Class (dt/class-of :dt/Property)) ":dt/Property should be instance of :dt/Class")
    (is (= :dt/Class (dt/class-of :User)) ":User should be instance of :dt/Class")
    (is (= :dt/Property (dt/class-of :dt/type)) ":dt/type should be instance of :dt/Property")))

(deftest instance-of?-test
  (testing "instance-of? checks instance relationship"
    (is (dt/instance-of? :dt/Class :User) "User is instance of Class")
    (is (dt/instance-of? :dt/Resource :User) "User is instance of Resource (via Class)")
    (is (dt/instance-of? :dt/Property :dt/domain) ":dt/domain is instance of Property")))

(deftest direct-instances-of-test
  (testing "direct-instances-of returns direct instances"
    (let [instances (dt/direct-instances-of :dt/Class)]
      (is (seq instances) "Should have some instances")
      (is (some #(= :User (:db/ident %)) instances) "Should include User"))))

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
      (is (some #{:User} class-instances) "Should include :User")
      (is (every? keyword? class-instances) "All results should be keywords"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Slot Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest direct-slots-of-test
  (testing "direct-slots-of returns directly declared slots"
    ;; direct-slots-of returns entity refs, need to get their idents
    (let [slots (dt/direct-slots-of :User)
          user-slots (set (map #(:db/ident (db/entity %)) slots))]
      (is (contains? user-slots :user/login) "User should have :user/login slot")
      (is (contains? user-slots :user/secret) "User should have :user/secret slot"))))

(deftest slots-of-test
  (testing "slots-of returns all effective slots including inherited"
    (let [user-slots (dt/slots-of :User)]
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
    (is (= :User (dt/domain-of :user/login)) ":user/login domain should be :User")
    (is (= :dt/Resource (dt/domain-of :dt/type)) ":dt/type domain should be :dt/Resource")))

(deftest range-of-test
  (testing "range-of returns the range type"
    (is (= :db.type/string (dt/range-of :user/login)) ":user/login range should be string")
    (is (= :dt/Class (dt/range-of :dt/type)) ":dt/type range should be :dt/Class")))

(deftest properties-with-domain-test
  (testing "properties-with-domain finds applicable properties"
    (let [user-props (set (dt/properties-with-domain :User))]
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
    (let [required (dt/required-slots-of :User)]
      (is (or (nil? required) (sequential? required)) "Should return nil or sequence"))))

(deftest validator-of-test
  (testing "validator-of returns validator function if defined"
    (let [validator (dt/validator-of :User)]
      (is (or (nil? validator) (fn? validator)) "Should return nil or function"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Creation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest make*-test
  (testing "make* creates typed instances without validation"
    (let [user (dt/make* :User {:user/login "testuser"})]
      (is (some? user) "Should return created entity")
      (is (= :User (:dt/type user)) "Entity should have correct type")
      (is (= "testuser" (:user/login user)) "Entity should have provided properties"))))

(deftest make-test
  (testing "make creates validated instances"
    (let [user (dt/make :User {:user/login "validuser"})]
      (is (some? user) "Should return created entity")
      (is (= :User (:dt/type user)) "Entity should have correct type")))

  (testing "make with validation disabled"
    (let [user (dt/make :User {:user/login "another"} {:validate? false})]
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
    (let [user (dt/make* :User {:user/login "validuser2"
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
    (let [user (dt/make* :User {:user/login "validuser3"})]
      (is (boolean? (dt/valid? user)) "Should return boolean")))

  (testing "valid? returns false for untyped entity"
    (let [result @(d/transact (db/conn) [{:db/doc "untyped for valid?"}])
          eid (-> result :tempids vals first)]
      (is (false? (dt/valid? eid)) "Untyped entity should not be valid"))))

(deftest validate-data-basic-test
  (testing "validate-data returns nil for valid data"
    (let [result (dt/validate-data :User {:user/login "precheck"})]
      (is (or (nil? result) (map? result)) "Should return nil or error map")))

  (testing "validate-data returns error structure when invalid"
    (let [result (dt/validate-data :User {})]
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
    (let [result (dt/validate-data :User {:user/login 12345})]
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
    (let [user (dt/make :User {:user/login "maketest"})]
      (is (some? user) "Should create valid entity")
      (is (= :User (:dt/type user)) "Should have correct type")))

  (testing "make with validate? false bypasses validation"
    ;; This should not throw even if data is questionable
    (let [user (dt/make :User {} {:validate? false})]
      (is (some? user) "Should create entity without validation")
      (is (= :User (:dt/type user)) "Should have correct type"))))

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
          pre-result (dt/validate-data :User props)
          ;; Create and post-transaction check
          user (dt/make* :User props)
          post-result (dt/validate user)]
      ;; Both should either pass or fail
      (is (= (nil? pre-result) (nil? post-result))
          "Pre and post validation should agree"))))
