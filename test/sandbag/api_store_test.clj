(ns sandbag.api-store-test
  "Test suite for the Store REST API (datatype metamodel endpoints)

  URL Conventions:
    - All keywords are namespaced: /ns/name (e.g., /dt/Resource for :dt/Resource)
    - Example: /model/User for :model/User"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [cheshire.core :as json]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.http :as http]
            [datomic.api :as d]
            [sandbag.db.datomic :as db]
            [sandbag.db.datatype :as dt]
            [sandbag.service.config :as config]
            [sandbag.util.edn :as edn]
            [sandbag.util.http-status :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-uri "datomic:mem://api-store-test")

(defn load-test-schema
  "Load schema files into test database"
  [conn]
  (doseq [schema-name (edn/config-value :required-schema)]
    (doseq [stmt (edn/resource-value schema-name nil)]
      @(d/transact conn stmt))))

(def service
  "Create test service from config"
  (::http/service-fn (http/create-servlet config/service)))

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
;; Helper Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-json-body
  "Parse JSON response body"
  [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn api-get
  "Helper to make GET requests to API with JSON accept header"
  [path]
  (response-for service :get path
                :headers {"Accept" "application/json"}))

(defn api-get-json
  "Helper to make GET request and parse JSON response"
  [path]
  (let [response (api-get path)]
    {:status (:status response)
     :body (parse-json-body response)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema Overview Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest schema-overview-test
  (testing "GET /api/store/schema returns schema overview"
    (let [{:keys [status body]} (api-get-json "/api/store/schema")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :schema)
          "Response should contain :schema key")
      (is (pos? (get-in body [:schema :class-count]))
          "Should have positive class count")
      (is (pos? (get-in body [:schema :property-count]))
          "Should have positive property count")
      (is (sequential? (get-in body [:schema :classes]))
          "Should have classes list")
      (is (sequential? (get-in body [:schema :properties]))
          "Should have properties list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Listing Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-classes-test
  (testing "GET /api/store/classes returns all classes"
    (let [{:keys [status body]} (api-get-json "/api/store/classes")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :count)
          "Response should contain :count")
      (is (contains? body :classes)
          "Response should contain :classes")
      (is (pos? (:count body))
          "Should have positive count")
      (is (some #(str/includes? % "Resource") (:classes body))
          "Should include dt/Resource class"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Detail Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-class-test
  (testing "GET /api/store/classes/dt/Resource returns namespaced class details"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:class body))
          "Should return correct class")
      (is (contains? body :description)
          "Should have description")
      (is (contains? body :slots)
          "Should have slots")
      (is (contains? body :abstract?)
          "Should have abstract? flag")
      (is (contains? body :instance-count)
          "Should have instance count")))

  (testing "GET /api/store/classes/:ns/:name returns 404 for unknown class"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/nonexistent/Class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")
      (is (contains? body :error)
          "Should contain error message"))))

(deftest get-class-user-test
  (testing "GET /api/store/classes/model/User returns User class"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return model/User class")
      (is (sequential? (:slots body))
          "Should have slots list")
      (is (sequential? (:subclasses body))
          "Should have subclasses list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Instances Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-instances-test
  (testing "GET /api/store/classes/dt/Class/instances returns instances"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/dt/Class/instances")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Class" (:class body))
          "Should return correct class")
      (is (contains? body :count)
          "Should have count")
      (is (contains? body :instances)
          "Should have instances list")
      (is (pos? (:count body))
          "Should have some class instances")))

  (testing "GET /api/store/classes/:ns/:name/instances returns 404 for unknown class"
    (let [{:keys [status]} (api-get-json "/api/store/classes/unknown/Class/instances")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

(deftest list-direct-instances-test
  (testing "GET /api/store/classes/dt/Class/instances/direct returns direct instances"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/dt/Class/instances/direct")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Class" (:class body))
          "Should return correct class")
      (is (contains? body :instances)
          "Should have instances list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Slots Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-slots-test
  (testing "GET /api/store/classes/model/User/slots returns effective slots"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/slots")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return correct class")
      (is (pos? (:count body))
          "Should have slots")
      (is (sequential? (:slots body))
          "Should have slots list")
      ;; Check slot structure
      (let [first-slot (first (:slots body))]
        (is (contains? first-slot :ident)
            "Slot should have :ident")
        (is (contains? first-slot :cardinality)
            "Slot should have :cardinality")
        (is (contains? first-slot :required?)
            "Slot should have :required?")))))

(deftest list-direct-slots-test
  (testing "GET /api/store/classes/model/User/slots/direct returns direct slots"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/slots/direct")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return correct class")
      (is (sequential? (:slots body))
          "Should have slots list"))))

(deftest list-required-slots-test
  (testing "GET /api/store/classes/model/User/slots/required returns required slots"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/slots/required")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return correct class")
      (is (contains? body :slots)
          "Should have slots list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest class-hierarchy-test
  (testing "GET /api/store/classes/model/User/hierarchy returns full hierarchy"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/hierarchy")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return correct class")
      (is (contains? body :parents)
          "Should have parents")
      (is (contains? body :ancestors)
          "Should have ancestors")
      (is (contains? body :direct-subclasses)
          "Should have direct-subclasses")
      (is (contains? body :all-subclasses)
          "Should have all-subclasses"))))

(deftest list-subclasses-test
  (testing "GET /api/store/classes/dt/Resource/subclasses returns subclasses"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/dt/Resource/subclasses")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:class body))
          "Should return correct class")
      (is (pos? (:count body))
          "dt/Resource should have subclasses")
      (is (sequential? (:subclasses body))
          "Should have subclasses list"))))

(deftest list-direct-subclasses-test
  (testing "GET /api/store/classes/dt/Resource/subclasses/direct returns direct subclasses"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/dt/Resource/subclasses/direct")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:class body))
          "Should return correct class")
      (is (sequential? (:subclasses body))
          "Should have subclasses list"))))

(deftest list-ancestors-test
  (testing "GET /api/store/classes/model/User/ancestors returns ancestors"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/ancestors")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return correct class")
      (is (contains? body :ancestors)
          "Should have ancestors list"))))

(deftest list-parents-test
  (testing "GET /api/store/classes/model/User/parents returns parents"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/parents")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "Should return correct class")
      (is (contains? body :parents)
          "Should have parents list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Listing Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-properties-test
  (testing "GET /api/store/properties returns all properties"
    (let [{:keys [status body]} (api-get-json "/api/store/properties")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :count)
          "Should have count")
      (is (contains? body :properties)
          "Should have properties list")
      (is (pos? (:count body))
          "Should have positive count"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Detail Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-property-test
  (testing "GET /api/store/properties/dt/type returns property details"
    (let [{:keys [status body]} (api-get-json "/api/store/properties/dt/type")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/type" (:property body))
          "Should return correct property")
      (is (contains? body :description)
          "Should have description")
      (is (contains? body :domain)
          "Should have domain")
      (is (contains? body :range)
          "Should have range")
      (is (contains? body :cardinality)
          "Should have cardinality")
      (is (contains? body :required?)
          "Should have required?")))

  (testing "GET /api/store/properties/:ns/:name returns 404 for unknown property"
    (let [{:keys [status body]} (api-get-json "/api/store/properties/nonexistent/prop")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")
      (is (contains? body :error)
          "Should contain error message"))))

(deftest property-domain-test
  (testing "GET /api/store/properties/user/login/domain returns domain"
    (let [{:keys [status body]} (api-get-json "/api/store/properties/user/login/domain")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "user/login" (:property body))
          "Should return correct property")
      (is (contains? body :domain)
          "Should have domain"))))

(deftest property-range-test
  (testing "GET /api/store/properties/user/login/range returns range"
    (let [{:keys [status body]} (api-get-json "/api/store/properties/user/login/range")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "user/login" (:property body))
          "Should return correct property")
      (is (contains? body :range)
          "Should have range"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-entity-test
  (testing "GET /api/store/entities/dt/Resource returns entity by namespaced ident"
    (let [{:keys [status body]} (api-get-json "/api/store/entities/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :entity)
          "Should have entity")
      (is (contains? body :class)
          "Should have class")))

  (testing "GET /api/store/entities/:ns/:name returns 404 for unknown entity"
    (let [{:keys [status body]} (api-get-json "/api/store/entities/nonexistent/entity")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")
      (is (contains? body :error)
          "Should contain error message"))))

(deftest validate-entity-test
  (testing "GET /api/store/entities/dt/Resource/validate returns validation result"
    (let [{:keys [status body]} (api-get-json "/api/store/entities/dt/Resource/validate")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :valid?)
          "Should have valid? field")
      (is (contains? body :validation)
          "Should have validation details")))

  (testing "GET /api/store/entities/:ns/:name/validate returns 404 for unknown entity"
    (let [{:keys [status]} (api-get-json "/api/store/entities/unknown/nonexistent/validate")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

(deftest entity-class-test
  (testing "GET /api/store/entities/dt/Resource/class returns entity's class"
    (let [{:keys [status body]} (api-get-json "/api/store/entities/dt/Resource/class")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :class)
          "Should have class")
      (is (= "dt/Class" (:class body))
          "dt/Resource should be a dt/Class")))

  (testing "GET /api/store/entities/:ns/:name/class returns 404 for unknown entity"
    (let [{:keys [status]} (api-get-json "/api/store/entities/unknown/nonexistent/class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Predicate Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check-instance-of-test
  (testing "GET /api/store/types/instance-of checks instance relationship"
    (let [{:keys [status body]} (api-get-json "/api/store/types/instance-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :instance-of?)
          "Should have instance-of? field")
      (is (true? (:instance-of? body))
          "dt/Resource should be instance of dt/Class")))

  (testing "instance-of returns false for non-instances"
    (let [{:keys [status body]} (api-get-json "/api/store/types/instance-of/dt/Property/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (false? (:instance-of? body))
          "dt/Resource should not be instance of dt/Property")))

  (testing "instance-of returns 404 for unknown class"
    (let [{:keys [status]} (api-get-json "/api/store/types/instance-of/unknown/Class/dt/Resource")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")))

  (testing "instance-of returns 404 for unknown entity"
    (let [{:keys [status]} (api-get-json "/api/store/types/instance-of/dt/Class/unknown/entity")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

(deftest check-subclass-of-test
  (testing "GET /api/store/types/subclass-of checks subclass relationship"
    (let [{:keys [status body]} (api-get-json "/api/store/types/subclass-of/dt/Resource/dt/Class")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :subclass-of?)
          "Should have subclass-of? field")
      (is (true? (:subclass-of? body))
          "dt/Class should be subclass of dt/Resource")))

  (testing "subclass-of returns false for non-subclasses"
    (let [{:keys [status body]} (api-get-json "/api/store/types/subclass-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (false? (:subclass-of? body))
          "dt/Resource should not be subclass of dt/Class")))

  (testing "subclass-of returns 404 for unknown parent"
    (let [{:keys [status]} (api-get-json "/api/store/types/subclass-of/unknown/Class/dt/Class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")))

  (testing "subclass-of returns 404 for unknown child"
    (let [{:keys [status]} (api-get-json "/api/store/types/subclass-of/dt/Resource/unknown/Class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Type Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest content-type-test
  (testing "API returns JSON for Accept: application/json"
    (let [response (api-get "/api/store/classes")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "application/json")
          "Should return JSON content type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests with Created Entities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest entity-lifecycle-test
  (testing "Created entities appear in API responses"
    ;; Create a test User instance
    (let [user (dt/make* :model/User {:user/login "testuser"
                                        :user/secret "testhash"})]
      ;; Verify instance appears in list
      (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/instances")]
        (is (= http-status/success status)
            "Should return 200 OK")
        (is (pos? (:count body))
            "Should have at least one User instance")
        (is (some #(= "testuser" (:user/login %)) (:instances body))
            "Should include the created user")))))

(deftest class-hierarchy-integration-test
  (testing "Class hierarchy is consistent"
    ;; Get User hierarchy
    (let [{:keys [body]} (api-get-json "/api/store/classes/model/User/hierarchy")]
      ;; User should have dt/Ref as ancestor
      (is (some #(and % (str/includes? % "Ref")) (:ancestors body))
          "User should have dt/Ref as ancestor"))

    ;; Get dt/Resource subclasses
    (let [{:keys [body]} (api-get-json "/api/store/classes/dt/Resource/subclasses")]
      ;; Should include core metamodel classes
      (is (pos? (:count body))
          "dt/Resource should have subclasses"))))
