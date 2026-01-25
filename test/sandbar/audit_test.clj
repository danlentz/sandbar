(ns sandbar.audit-test
  "Test suite for the Audit domain model.

   Every change tells a story. The audit system captures who changed what,
   when, and (if you're lucky) why. When the auditors come knocking, or when
   you need to figure out how production data got into that state, this is
   where you'll find answers.

   This test suite validates:
   - Change class hierarchy (Create, Update, Delete)
   - Property inheritance and domain constraints
   - Audit trail creation and querying
   - Correlation of related changes

   Schema defined in schema/audit.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu])
  (:import [java.util Date UUID]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "audit-test"
                                              :extra-schema [:audit]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;
;; The foundation: three types of changes, one abstract parent.
;; Because "something changed" isn't specific enough for compliance.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest audit-class-hierarchy-test
  ;; audit/Change is abstract because you can't just "change" something
  ;; without knowing what kind of change it was.
  (testing "Change class exists and is abstract"
    (is (some? (db/entity :audit/Change)) "Change class should exist")
    (is (= :dt/Class (dt/class-of :audit/Change)) "Change should be a Class")
    (is (dt/abstract? :audit/Change) "Change should be abstract"))

  (testing "Change subtypes exist and are concrete"
    ;; The holy trinity of database operations
    (is (some? (db/entity :audit/Create)) "Create class should exist")
    (is (some? (db/entity :audit/Update)) "Update class should exist")
    (is (some? (db/entity :audit/Delete)) "Delete class should exist")

    (is (not (dt/abstract? :audit/Create)) "Create should be concrete")
    (is (not (dt/abstract? :audit/Update)) "Update should be concrete")
    (is (not (dt/abstract? :audit/Delete)) "Delete should be concrete"))

  (testing "Subtype inheritance"
    ;; All changes are Changes (profound, I know)
    (is (dt/subclass-of? :audit/Change :audit/Create) "Create extends Change")
    (is (dt/subclass-of? :audit/Change :audit/Update) "Update extends Change")
    (is (dt/subclass-of? :audit/Change :audit/Delete) "Delete extends Change")

    ;; But they're not each other
    (is (not (dt/subclass-of? :audit/Create :audit/Update)) "Create is not Update")
    (is (not (dt/subclass-of? :audit/Delete :audit/Create)) "Delete is not Create")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Inheritance Tests
;;
;; Common properties flow down to all change types.
;; Specific properties stay where they belong.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest audit-property-inheritance-test
  (testing "Common properties inherited by all change types"
    (let [common-slots #{:audit/timestamp :audit/entity :audit/entity-type
                         :audit/actor :audit/reason :audit/source
                         :audit/correlation-id :audit/transaction-id :audit/tags}]
      (doseq [change-type [:audit/Create :audit/Update :audit/Delete]]
        (let [slots (dt/slots-of change-type)]
          (doseq [slot common-slots]
            (is (contains? slots slot)
                (str slot " should be inherited by " change-type)))))))

  (testing "Update has attribute change tracking"
    ;; Update is the chatty one: old value, new value, which attribute
    (let [update-slots (dt/slots-of :audit/Update)]
      (is (contains? update-slots :audit/attribute) "Update tracks which attribute changed")
      (is (contains? update-slots :audit/old-value) "Update records the old value")
      (is (contains? update-slots :audit/new-value) "Update records the new value")))

  (testing "Create has initial data"
    ;; Create: "Here's what it looked like when it was born"
    (let [create-slots (dt/slots-of :audit/Create)]
      (is (contains? create-slots :audit/initial-data) "Create captures initial state")))

  (testing "Delete has final data and deletion type"
    ;; Delete: "Here's what it looked like when it died"
    (let [delete-slots (dt/slots-of :audit/Delete)]
      (is (contains? delete-slots :audit/final-data) "Delete captures final state")
      (is (contains? delete-slots :audit/hard-delete?) "Delete tracks if it was hard or soft"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Creation Tests
;;
;; Actually creating audit records. The paperwork of the database world.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-audit-entries-test
  (testing "Cannot instantiate abstract Change class"
    ;; "I made a change" - "What kind?" - "Just... a change" - *auditor screams*
    (is (thrown? Exception
          (dt/make :audit/Change {:audit/timestamp (Date.)}))))

  (testing "Create an Update record"
    ;; Someone changed Zorp's email. Let's document it for posterity.
    (let [update (dt/make :audit/Update
                   {:audit/timestamp (Date.)
                    :audit/entity-type :auth/User
                    :audit/attribute :auth/email
                    :audit/old-value "\"zorp@pluto.net\""
                    :audit/new-value "\"zorp@galactic-emporium.io\""
                    :audit/reason "Rebranding initiative"
                    :audit/source :api})]
      (is (some? update) "Update record created")
      (is (dt/instance-of? :audit/Update update) "Is an Update")
      (is (dt/instance-of? :audit/Change update) "Is also a Change")
      (is (= :auth/email (:audit/attribute update)) "Tracks the attribute")
      (is (= :api (:audit/source update)) "Tracks the source")))

  (testing "Create a Create record"
    ;; The birth certificate of an entity
    (let [create (dt/make :audit/Create
                   {:audit/timestamp (Date.)
                    :audit/entity-type :zorp/SpaceBoot
                    :audit/initial-data "{:footwear/name \"Cosmic Stomper\" :footwear/price 599.99M}"
                    :audit/source :console
                    :audit/tags #{:inventory :new-product}})]
      (is (some? create) "Create record created")
      (is (dt/instance-of? :audit/Create create) "Is a Create")
      (is (contains? (:audit/tags create) :inventory) "Tags are stored")))

  (testing "Create a Delete record"
    ;; The death certificate. RIP discontinued product.
    (let [delete (dt/make :audit/Delete
                   {:audit/timestamp (Date.)
                    :audit/entity-type :zorp/Sandal
                    :audit/final-data "{:footwear/name \"Solar Sandal\" :footwear/price 149.99M}"
                    :audit/hard-delete? false
                    :audit/reason "Discontinued - customers kept getting sunburned feet"
                    :audit/source :migration})]
      (is (some? delete) "Delete record created")
      (is (dt/instance-of? :audit/Delete delete) "Is a Delete")
      (is (= false (:audit/hard-delete? delete)) "Soft delete recorded"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Correlation Tests
;;
;; When one operation creates multiple audit entries, they share a correlation ID.
;; Essential for understanding "what happened in that one request."
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest correlation-id-test
  (testing "Multiple changes share a correlation ID"
    ;; Scenario: Updating a user profile changes email AND username.
    ;; These should be correlated so we know they happened together.
    (let [correlation-id (UUID/randomUUID)
          change1 (dt/make :audit/Update
                    {:audit/timestamp (Date.)
                     :audit/entity-type :auth/User
                     :audit/attribute :auth/email
                     :audit/old-value "\"old@example.com\""
                     :audit/new-value "\"new@example.com\""
                     :audit/correlation-id correlation-id})
          change2 (dt/make :audit/Update
                    {:audit/timestamp (Date.)
                     :audit/entity-type :auth/User
                     :audit/attribute :auth/username
                     :audit/old-value "\"oldname\""
                     :audit/new-value "\"newname\""
                     :audit/correlation-id correlation-id})]
      (is (= correlation-id (:audit/correlation-id change1)))
      (is (= correlation-id (:audit/correlation-id change2)))
      (is (= (:audit/correlation-id change1) (:audit/correlation-id change2))
          "Both changes share the same correlation ID"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query Tests
;;
;; The whole point of audit logs is to answer questions later.
;; "Who did this?" "When?" "Why?" "And can we blame someone?"
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest query-audit-entries-test
  ;; Set up a variety of audit entries
  (let [now (Date.)
        _ (dt/make :audit/Create
            {:audit/timestamp now
             :audit/entity-type :zorp/SpaceBoot
             :audit/initial-data "{:name \"Boot 1\"}"
             :audit/source :api
             :audit/tags #{:inventory}})
        _ (dt/make :audit/Update
            {:audit/timestamp now
             :audit/entity-type :zorp/SpaceBoot
             :audit/attribute :footwear/price
             :audit/old-value "100M"
             :audit/new-value "150M"
             :audit/source :api})
        _ (dt/make :audit/Delete
            {:audit/timestamp now
             :audit/entity-type :zorp/Sandal
             :audit/final-data "{:name \"Old Sandal\"}"
             :audit/source :console
             :audit/hard-delete? true})]

    (testing "Find all changes"
      (let [all-changes (dt/all-instances-of :audit/Change)]
        (is (= 3 (count all-changes)) "Should find all 3 changes")))

    (testing "Find changes by type"
      (let [creates (dt/all-instances-of :audit/Create)
            updates (dt/all-instances-of :audit/Update)
            deletes (dt/all-instances-of :audit/Delete)]
        (is (= 1 (count creates)) "One create")
        (is (= 1 (count updates)) "One update")
        (is (= 1 (count deletes)) "One delete")))

    (testing "Query by source using Datalog"
      (let [api-changes (d/q '[:find ?e
                               :where
                               [?e :audit/source :api]]
                             (db/db))]
        (is (= 2 (count api-changes)) "Two changes from API")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST API Tests
;;
;; The audit schema should be introspectable via the store API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest audit-api-test
  (testing "Audit classes visible via API"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/audit/Change")]
      (is (= 200 status))
      (is (= :audit/Change (:class body)))
      (is (true? (:abstract? body)))))

  (testing "Audit subclasses visible"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/audit/Change/subclasses")]
      (is (= 200 status))
      (is (contains? (set (:subclasses body)) :audit/Create))
      (is (contains? (set (:subclasses body)) :audit/Update))
      (is (contains? (set (:subclasses body)) :audit/Delete))))

  (testing "Property introspection"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/audit/old-value")]
      (is (= 200 status))
      (is (= :audit/Update (:domain body)) "old-value belongs to Update"))))
