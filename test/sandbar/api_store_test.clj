(ns sandbar.api-store-test
  "Test suite for the Store REST API (datatype metamodel endpoints)

  URL Conventions:
    - All keywords are namespaced: /ns/name (e.g., /dt/Resource for :dt/Resource)
    - Example: /model/User for :model/User"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [io.pedestal.test :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu :refer [service api-get api-get-edn api-get-json
                                              api-get-transit api-get-csv with-auth-headers]]
            [sandbar.util.http-status :as http-status]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "api-store-test"}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema Overview Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest schema-overview-test
  (testing "GET /api/store/schema returns schema overview"
    (let [{:keys [status body]} (api-get-edn "/api/store/schema")]
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
    (let [{:keys [status body]} (api-get-edn "/api/store/classes")]
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
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:class body))
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
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/nonexistent/Class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")
      (is (contains? body :error)
          "Should contain error message"))))

(deftest get-class-user-test
  (testing "GET /api/store/classes/model/User returns User class"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
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
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/dt/Class/instances")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Class (:class body))
          "Should return correct class")
      (is (contains? body :count)
          "Should have count")
      (is (contains? body :instances)
          "Should have instances list")
      (is (pos? (:count body))
          "Should have some class instances")))

  (testing "GET /api/store/classes/:ns/:name/instances returns 404 for unknown class"
    (let [{:keys [status]} (api-get-edn "/api/store/classes/unknown/Class/instances")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

(deftest list-direct-instances-test
  (testing "GET /api/store/classes/dt/Class/instances/direct returns direct instances"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/dt/Class/instances/direct")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Class (:class body))
          "Should return correct class")
      (is (contains? body :instances)
          "Should have instances list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Slots Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-slots-test
  (testing "GET /api/store/classes/model/User/slots returns effective slots"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/slots")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
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
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/slots/direct")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
          "Should return correct class")
      (is (sequential? (:slots body))
          "Should have slots list"))))

(deftest list-required-slots-test
  (testing "GET /api/store/classes/model/User/slots/required returns required slots"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/slots/required")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
          "Should return correct class")
      (is (contains? body :slots)
          "Should have slots list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest class-hierarchy-test
  (testing "GET /api/store/classes/model/User/hierarchy returns full hierarchy"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/hierarchy")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
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
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/dt/Resource/subclasses")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:class body))
          "Should return correct class")
      (is (pos? (:count body))
          "dt/Resource should have subclasses")
      (is (sequential? (:subclasses body))
          "Should have subclasses list"))))

(deftest list-direct-subclasses-test
  (testing "GET /api/store/classes/dt/Resource/subclasses/direct returns direct subclasses"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/dt/Resource/subclasses/direct")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:class body))
          "Should return correct class")
      (is (sequential? (:subclasses body))
          "Should have subclasses list"))))

(deftest list-ancestors-test
  (testing "GET /api/store/classes/model/User/ancestors returns ancestors"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/ancestors")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
          "Should return correct class")
      (is (contains? body :ancestors)
          "Should have ancestors list"))))

(deftest list-parents-test
  (testing "GET /api/store/classes/model/User/parents returns parents"
    (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/parents")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
          "Should return correct class")
      (is (contains? body :parents)
          "Should have parents list"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Listing Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-properties-test
  (testing "GET /api/store/properties returns all properties"
    (let [{:keys [status body]} (api-get-edn "/api/store/properties")]
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
    (let [{:keys [status body]} (api-get-edn "/api/store/properties/dt/type")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/type (:property body))
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
    (let [{:keys [status body]} (api-get-edn "/api/store/properties/nonexistent/prop")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")
      (is (contains? body :error)
          "Should contain error message"))))

(deftest property-domain-test
  (testing "GET /api/store/properties/user/login/domain returns domain"
    (let [{:keys [status body]} (api-get-edn "/api/store/properties/user/login/domain")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :user/login (:property body))
          "Should return correct property")
      (is (contains? body :domain)
          "Should have domain"))))

(deftest property-range-test
  (testing "GET /api/store/properties/user/login/range returns range"
    (let [{:keys [status body]} (api-get-edn "/api/store/properties/user/login/range")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :user/login (:property body))
          "Should return correct property")
      (is (contains? body :range)
          "Should have range"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-entity-test
  (testing "GET /api/store/entities/dt/Resource returns entity by namespaced ident"
    (let [{:keys [status body]} (api-get-edn "/api/store/entities/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :entity)
          "Should have entity")
      (is (contains? body :class)
          "Should have class")))

  (testing "GET /api/store/entities/:ns/:name returns 404 for unknown entity"
    (let [{:keys [status body]} (api-get-edn "/api/store/entities/nonexistent/entity")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")
      (is (contains? body :error)
          "Should contain error message"))))

(deftest validate-entity-test
  (testing "GET /api/store/entities/dt/Resource/validate returns validation result"
    (let [{:keys [status body]} (api-get-edn "/api/store/entities/dt/Resource/validate")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :valid?)
          "Should have valid? field")
      (is (contains? body :validation)
          "Should have validation details")))

  (testing "GET /api/store/entities/:ns/:name/validate returns 404 for unknown entity"
    (let [{:keys [status]} (api-get-edn "/api/store/entities/unknown/nonexistent/validate")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

(deftest entity-class-test
  (testing "GET /api/store/entities/dt/Resource/class returns entity's class"
    (let [{:keys [status body]} (api-get-edn "/api/store/entities/dt/Resource/class")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :class)
          "Should have class")
      (is (= :dt/Class (:class body))
          "dt/Resource should be a dt/Class")))

  (testing "GET /api/store/entities/:ns/:name/class returns 404 for unknown entity"
    (let [{:keys [status]} (api-get-edn "/api/store/entities/unknown/nonexistent/class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Predicate Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check-instance-of-test
  (testing "GET /api/store/types/instance-of checks instance relationship"
    (let [{:keys [status body]} (api-get-edn "/api/store/types/instance-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :instance-of?)
          "Should have instance-of? field")
      (is (true? (:instance-of? body))
          "dt/Resource should be instance of dt/Class")))

  (testing "instance-of returns false for non-instances"
    (let [{:keys [status body]} (api-get-edn "/api/store/types/instance-of/dt/Property/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (false? (:instance-of? body))
          "dt/Resource should not be instance of dt/Property")))

  (testing "instance-of returns 404 for unknown class"
    (let [{:keys [status]} (api-get-edn "/api/store/types/instance-of/unknown/Class/dt/Resource")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")))

  (testing "instance-of returns 404 for unknown entity"
    (let [{:keys [status]} (api-get-edn "/api/store/types/instance-of/dt/Class/unknown/entity")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

(deftest check-subclass-of-test
  (testing "GET /api/store/types/subclass-of checks subclass relationship"
    (let [{:keys [status body]} (api-get-edn "/api/store/types/subclass-of/dt/Resource/dt/Class")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (contains? body :subclass-of?)
          "Should have subclass-of? field")
      (is (true? (:subclass-of? body))
          "dt/Class should be subclass of dt/Resource")))

  (testing "GET /api/store/types/subclass-of for direct subclass dt/Ref"
    (let [{:keys [status body]} (api-get-edn "/api/store/types/subclass-of/dt/Resource/dt/Ref")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:parent body))
          "Parent should be dt/Resource")
      (is (= :dt/Ref (:child body))
          "Child should be dt/Ref")
      (is (true? (:subclass-of? body))
          "dt/Ref should be a direct subclass of dt/Resource")))

  (testing "subclass-of returns false for non-subclasses"
    (let [{:keys [status body]} (api-get-edn "/api/store/types/subclass-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (false? (:subclass-of? body))
          "dt/Resource should not be subclass of dt/Class")))

  (testing "subclass-of returns 404 for unknown parent"
    (let [{:keys [status]} (api-get-edn "/api/store/types/subclass-of/unknown/Class/dt/Class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found")))

  (testing "subclass-of returns 404 for unknown child"
    (let [{:keys [status]} (api-get-edn "/api/store/types/subclass-of/dt/Resource/unknown/Class")]
      (is (= http-status/not-found status)
          "Should return 404 Not Found"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Type Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest content-type-test
  (testing "API returns EDN by default"
    (let [response (api-get "/api/store/classes")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "application/edn")
          "Should return EDN content type")))

  (testing "API returns JSON when requested"
    (let [response (response-for service :get "/api/store/classes"
                                 :headers (with-auth-headers {"Accept" "application/json"}))]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "application/json")
          "Should return JSON content type")))

  (testing "API returns CSV when requested"
    (let [response (response-for service :get "/api/store/classes"
                                 :headers (with-auth-headers {"Accept" "text/csv"}))]
      (is (= http-status/success (:status response))
          "Should return 200 OK for CSV")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "text/csv")
          "Should return CSV content type")
      (is (str/includes? (:body response) "count,classes")
          "CSV should have header row")))

  (testing "API returns Transit+JSON when requested"
    (let [response (response-for service :get "/api/store/classes"
                                 :headers (with-auth-headers {"Accept" "application/transit+json"}))]
      (is (= http-status/success (:status response))
          "Should return 200 OK for Transit+JSON")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "application/transit+json")
          "Should return Transit+JSON content type")))

  (testing "API rejects text/html with 406 Not Acceptable"
    (let [response (response-for service :get "/api/store/classes"
                                 :headers (with-auth-headers {"Accept" "text/html"}))]
      (is (= http-status/not-acceptable (:status response))
          "Should return 406 Not Acceptable for HTML")))

  (testing "API rejects application/xml with 406 Not Acceptable"
    (let [response (response-for service :get "/api/store/classes"
                                 :headers (with-auth-headers {"Accept" "application/xml"}))]
      (is (= http-status/not-acceptable (:status response))
          "Should return 406 Not Acceptable for XML"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CSV Content Type Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest csv-classes-test
  (testing "GET /api/store/classes returns valid CSV"
    (let [response (api-get-csv "/api/store/classes")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (get-in response [:headers "Content-Type"]) "text/csv")
          "Should return CSV content type")
      (let [lines (str/split-lines (:body response))]
        (is (= "count,classes" (first lines))
            "CSV should have header row")
        (is (>= (count lines) 2)
            "CSV should have at least header and data row"))))

  (testing "GET /api/store/classes/dt/Resource returns valid CSV"
    (let [response (api-get-csv "/api/store/classes/dt/Resource")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (:body response) "class")
          "CSV should include class column"))))

(deftest csv-properties-test
  (testing "GET /api/store/properties returns valid CSV"
    (let [response (api-get-csv "/api/store/properties")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (let [lines (str/split-lines (:body response))]
        (is (str/includes? (first lines) "count")
            "CSV should have count in header")
        (is (str/includes? (first lines) "properties")
            "CSV should have properties in header"))))

  (testing "GET /api/store/properties/dt/type returns valid CSV"
    (let [response (api-get-csv "/api/store/properties/dt/type")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (:body response) "property")
          "CSV should include property column"))))

(deftest csv-type-predicates-test
  (testing "GET /api/store/types/instance-of returns valid CSV"
    (let [response (api-get-csv "/api/store/types/instance-of/dt/Class/dt/Resource")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (:body response) "instance-of?")
          "CSV should include instance-of? column")))

  (testing "GET /api/store/types/subclass-of returns valid CSV"
    (let [response (api-get-csv "/api/store/types/subclass-of/dt/Resource/model/User")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (:body response) "subclass-of?")
          "CSV should include subclass-of? column")))

  (testing "GET /api/store/types/subclass-of for direct subclass returns valid CSV"
    (let [response (api-get-csv "/api/store/types/subclass-of/dt/Resource/dt/Ref")]
      (is (= http-status/success (:status response))
          "Should return 200 OK")
      (is (str/includes? (:body response) "true")
          "dt/Ref should be a subclass of dt/Resource"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Content Type Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest json-classes-test
  (testing "GET /api/store/classes returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/classes")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (number? (:count body))
          "JSON count should be a number")
      (is (vector? (:classes body))
          "JSON classes should be a vector")
      ;; JSON returns strings, not keywords
      (is (every? string? (:classes body))
          "JSON classes should be strings")))

  (testing "GET /api/store/classes/dt/Resource returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:class body))
          "JSON should return class as string")
      (is (map? (:description body))
          "JSON description should be a map")
      (is (boolean? (:abstract? body))
          "JSON abstract? should be a boolean"))))

(deftest json-properties-test
  (testing "GET /api/store/properties returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/properties")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (number? (:count body))
          "JSON count should be a number")
      (is (every? string? (:properties body))
          "JSON properties should be strings")))

  (testing "GET /api/store/properties/dt/type returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/properties/dt/type")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/type" (:property body))
          "JSON should return property as string")
      (is (= "dt/Resource" (:domain body))
          "JSON domain should be a string")
      (is (= "dt/Class" (:range body))
          "JSON range should be a string")
      (is (boolean? (:cardinality-one? body))
          "JSON cardinality-one? should be a boolean"))))

(deftest json-entities-test
  (testing "GET /api/store/entities/dt/Resource returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/entities/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:id body))
          "JSON id should be a string")
      (is (= "dt/Class" (:class body))
          "JSON class should be a string")
      (is (map? (:entity body))
          "JSON entity should be a map")))

  (testing "GET /api/store/entities/dt/Resource/validate returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/entities/dt/Resource/validate")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (boolean? (:valid? body))
          "JSON valid? should be a boolean")
      (is (map? (:validation body))
          "JSON validation should be a map"))))

(deftest json-type-predicates-test
  (testing "GET /api/store/types/instance-of returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/types/instance-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Class" (:class body))
          "JSON class should be a string")
      (is (= "dt/Resource" (:entity body))
          "JSON entity should be a string")
      (is (boolean? (:instance-of? body))
          "JSON instance-of? should be a boolean")))

  (testing "GET /api/store/types/subclass-of returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/types/subclass-of/dt/Resource/model/User")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:parent body))
          "JSON parent should be a string")
      (is (= "model/User" (:child body))
          "JSON child should be a string")
      (is (boolean? (:subclass-of? body))
          "JSON subclass-of? should be a boolean")))

  (testing "GET /api/store/types/subclass-of for direct subclass dt/Ref"
    (let [{:keys [status body]} (api-get-json "/api/store/types/subclass-of/dt/Resource/dt/Ref")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "dt/Resource" (:parent body))
          "Parent should be dt/Resource")
      (is (= "dt/Ref" (:child body))
          "Child should be dt/Ref")
      (is (true? (:subclass-of? body))
          "dt/Ref should be a direct subclass of dt/Resource"))))

(deftest json-hierarchy-test
  (testing "GET /api/store/classes/model/User/hierarchy returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/hierarchy")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "JSON class should be a string")
      (is (vector? (:parents body))
          "JSON parents should be a vector")
      (is (vector? (:ancestors body))
          "JSON ancestors should be a vector")
      (is (every? string? (:ancestors body))
          "JSON ancestors should be strings")))

  (testing "GET /api/store/classes/model/User/slots returns valid JSON"
    (let [{:keys [status body]} (api-get-json "/api/store/classes/model/User/slots")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= "model/User" (:class body))
          "JSON class should be a string")
      (is (vector? (:slots body))
          "JSON slots should be a vector")
      (when (seq (:slots body))
        (let [first-slot (first (:slots body))]
          (is (string? (:ident first-slot))
              "JSON slot ident should be a string")
          (is (string? (:domain first-slot))
              "JSON slot domain should be a string"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transit+JSON Content Type Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest transit-classes-test
  (testing "GET /api/store/classes returns valid Transit+JSON"
    (let [{:keys [status body]} (api-get-transit "/api/store/classes")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (pos? (:count body))
          "Transit count should be positive")
      (is (some #{:dt/Resource} (:classes body))
          "classes should include dt/Resource")
      (is (some #{:dt/Class} (:classes body))
          "classes should include dt/Class")
      (is (some #{:model/User} (:classes body))
          "classes should include model/User")))

  (testing "GET /api/store/classes/dt/Resource returns valid Transit+JSON"
    (let [{:keys [status body]} (api-get-transit "/api/store/classes/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:class body))
          "class should be dt/Resource")
      (is (= :dt/Resource (get-in body [:description :db/ident]))
          "description db/ident should be dt/Resource")
      (is (= :dt/Class (get-in body [:description :dt/type]))
          "description dt/type should be dt/Class")
      (is (false? (:abstract? body))
          "dt/Resource should not be abstract")
      (is (some #{:dt/type} (:slots body))
          "slots should include dt/type")
      (is (some #{:db/ident} (:slots body))
          "slots should include db/ident")))

  (testing "GET /api/store/classes/dt/Class returns class metadata"
    (let [{:keys [status body]} (api-get-transit "/api/store/classes/dt/Class")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Class (:class body))
          "class should be dt/Class")
      (is (some #{:dt/Resource} (:parents body))
          "dt/Class parent should include dt/Resource")
      (is (pos? (:instance-count body))
          "dt/Class should have instances"))))

(deftest transit-properties-test
  (testing "GET /api/store/properties returns valid Transit+JSON"
    (let [{:keys [status body]} (api-get-transit "/api/store/properties")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (pos? (:count body))
          "count should be positive")
      (is (some #{:dt/type} (:properties body))
          "properties should include dt/type")
      (is (some #{:dt/domain} (:properties body))
          "properties should include dt/domain")
      (is (some #{:db/ident} (:properties body))
          "properties should include db/ident")))

  (testing "GET /api/store/properties/dt/type returns property details"
    (let [{:keys [status body]} (api-get-transit "/api/store/properties/dt/type")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/type (:property body))
          "property should be dt/type")
      (is (= :dt/Resource (:domain body))
          "dt/type domain should be dt/Resource")
      (is (= :dt/Class (:range body))
          "dt/type range should be dt/Class")
      (is (= :db.cardinality/one (:cardinality body))
          "dt/type cardinality should be one")
      (is (true? (:cardinality-one? body))
          "cardinality-one? should be true")
      (is (false? (:cardinality-many? body))
          "cardinality-many? should be false")))

  (testing "GET /api/store/properties/user/login returns user property"
    (let [{:keys [status body]} (api-get-transit "/api/store/properties/user/login")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :user/login (:property body))
          "property should be user/login")
      (is (= :model/User (:domain body))
          "user/login domain should be model/User")
      (is (= :db.type/string (:range body))
          "user/login range should be db.type/string"))))

(deftest transit-type-predicates-test
  (testing "dt/Resource is an instance of dt/Class"
    (let [{:keys [status body]} (api-get-transit "/api/store/types/instance-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Class (:class body))
          "class should be dt/Class")
      (is (= :dt/Resource (:entity body))
          "entity should be dt/Resource")
      (is (true? (:instance-of? body))
          "dt/Resource should be an instance of dt/Class")))

  (testing "dt/Resource is NOT an instance of dt/Property"
    (let [{:keys [status body]} (api-get-transit "/api/store/types/instance-of/dt/Property/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Property (:class body))
          "class should be dt/Property")
      (is (= :dt/Resource (:entity body))
          "entity should be dt/Resource")
      (is (false? (:instance-of? body))
          "dt/Resource should NOT be an instance of dt/Property")))

  (testing "dt/Ref is a direct subclass of dt/Resource"
    (let [{:keys [status body]} (api-get-transit "/api/store/types/subclass-of/dt/Resource/dt/Ref")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:parent body))
          "parent should be dt/Resource")
      (is (= :dt/Ref (:child body))
          "child should be dt/Ref")
      (is (true? (:subclass-of? body))
          "dt/Ref should be a subclass of dt/Resource")))

  (testing "model/User is a transitive subclass of dt/Resource"
    (let [{:keys [status body]} (api-get-transit "/api/store/types/subclass-of/dt/Resource/model/User")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:parent body))
          "parent should be dt/Resource")
      (is (= :model/User (:child body))
          "child should be model/User")
      (is (true? (:subclass-of? body))
          "model/User should be a subclass of dt/Resource (via dt/Ref)")))

  (testing "dt/Resource is NOT a subclass of dt/Class"
    (let [{:keys [status body]} (api-get-transit "/api/store/types/subclass-of/dt/Class/dt/Resource")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (false? (:subclass-of? body))
          "dt/Resource should NOT be a subclass of dt/Class"))))

(deftest transit-hierarchy-test
  (testing "GET /api/store/classes/model/User/hierarchy returns valid Transit+JSON"
    (let [{:keys [status body]} (api-get-transit "/api/store/classes/model/User/hierarchy")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :model/User (:class body))
          "Transit class should be a keyword")
      (is (= [:dt/Ref] (:parents body))
          "model/User parent should be dt/Ref")
      (is (some #{:dt/Ref} (:ancestors body))
          "ancestors should include dt/Ref")
      (is (some #{:dt/Resource} (:ancestors body))
          "ancestors should include dt/Resource")))

  (testing "GET /api/store/classes/dt/Ref/hierarchy returns correct hierarchy"
    (let [{:keys [status body]} (api-get-transit "/api/store/classes/dt/Ref/hierarchy")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Ref (:class body))
          "class should be dt/Ref")
      (is (= [:dt/Resource] (:parents body))
          "dt/Ref parent should be dt/Resource")
      (is (= [:dt/Resource] (:ancestors body))
          "dt/Ref ancestors should be just dt/Resource")
      (is (some #{:model/User} (:all-subclasses body))
          "dt/Ref subclasses should include model/User")))

  (testing "GET /api/store/classes/dt/Resource/hierarchy is the root class"
    (let [{:keys [status body]} (api-get-transit "/api/store/classes/dt/Resource/hierarchy")]
      (is (= http-status/success status)
          "Should return 200 OK")
      (is (= :dt/Resource (:class body))
          "class should be dt/Resource")
      (is (empty? (:parents body))
          "dt/Resource should have no parents")
      (is (empty? (:ancestors body))
          "dt/Resource should have no ancestors")
      (is (some #{:dt/Ref} (:direct-subclasses body))
          "dt/Resource direct subclasses should include dt/Ref")
      (is (some #{:dt/Class} (:direct-subclasses body))
          "dt/Resource direct subclasses should include dt/Class"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests with Created Entities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest entity-lifecycle-test
  (testing "Created entities appear in API responses"
    ;; Create a test User instance
    (let [user (dt/make* :model/User {:user/login "testuser"
                                        :user/secret "testhash"})]
      ;; Verify instance appears in list
      (let [{:keys [status body]} (api-get-edn "/api/store/classes/model/User/instances")]
        (is (= http-status/success status)
            "Should return 200 OK")
        (is (pos? (:count body))
            "Should have at least one User instance")
        (is (some #(= "testuser" (:user/login %)) (:instances body))
            "Should include the created user")))))

(deftest class-hierarchy-integration-test
  (testing "Class hierarchy is consistent"
    ;; Get User hierarchy
    (let [{:keys [body]} (api-get-edn "/api/store/classes/model/User/hierarchy")]
      ;; User should have dt/Ref as ancestor
      (is (some #(and % (str/includes? % "Ref")) (:ancestors body))
          "User should have dt/Ref as ancestor"))

    ;; Get dt/Resource subclasses
    (let [{:keys [body]} (api-get-edn "/api/store/classes/dt/Resource/subclasses")]
      ;; Should include core metamodel classes
      (is (pos? (:count body))
          "dt/Resource should have subclasses"))))
