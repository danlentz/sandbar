(ns sandbar.event-test
  "Test suite for the Event ontology.
   Tests the event schema from schema/event.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu])
  (:import [java.util UUID Date]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "event-test"
                                              :extra-schema [:event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest event-class-hierarchy-test
  (testing "Event class exists and is abstract"
    (is (some? (db/entity :dt/Event)) "Event class should exist")
    (is (= :dt/Class (dt/class-of :dt/Event)) "Event should be a Class")
    (is (dt/abstract? :dt/Event) "Event should be abstract"))

  (testing "Event inherits from dt/Ref"
    (is (= #{:dt/Ref} (set (dt/parents-of :dt/Event)))
        "Event should have dt/Ref as parent"))

  (testing "Event ancestors include dt/Resource"
    (let [ancestors (set (dt/ancestors-of :dt/Event))]
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest server-event-hierarchy-test
  (testing "ServerEvent class hierarchy"
    (is (some? (db/entity :event/ServerEvent)) "ServerEvent should exist")
    (is (= #{:dt/Event} (set (dt/parents-of :event/ServerEvent)))
        "ServerEvent parent should be Event")
    (is (not (dt/abstract? :event/ServerEvent)) "ServerEvent should not be abstract"))

  (testing "ServerEvent ancestors"
    (let [ancestors (set (dt/ancestors-of :event/ServerEvent))]
      (is (contains? ancestors :dt/Event) "Should include Event")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest user-event-hierarchy-test
  (testing "UserEvent class hierarchy"
    (is (some? (db/entity :event/UserEvent)) "UserEvent should exist")
    (is (= #{:dt/Event} (set (dt/parents-of :event/UserEvent)))
        "UserEvent parent should be Event")
    (is (not (dt/abstract? :event/UserEvent)) "UserEvent should not be abstract")))

(deftest system-event-hierarchy-test
  (testing "SystemEvent class hierarchy"
    (is (some? (db/entity :event/SystemEvent)) "SystemEvent should exist")
    (is (= #{:dt/Event} (set (dt/parents-of :event/SystemEvent)))
        "SystemEvent parent should be Event")
    (is (not (dt/abstract? :event/SystemEvent)) "SystemEvent should not be abstract")))

(deftest http-request-hierarchy-test
  (testing "HttpRequest class hierarchy"
    (is (some? (db/entity :event/HttpRequest)) "HttpRequest should exist")
    (is (= #{:event/ServerEvent} (set (dt/parents-of :event/HttpRequest)))
        "HttpRequest parent should be ServerEvent"))

  (testing "HttpRequest full ancestor chain"
    (let [ancestors (set (dt/ancestors-of :event/HttpRequest))]
      (is (contains? ancestors :event/ServerEvent) "Should include ServerEvent")
      (is (contains? ancestors :dt/Event) "Should include Event")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest api-call-hierarchy-test
  (testing "ApiCall class hierarchy"
    (is (some? (db/entity :event/ApiCall)) "ApiCall should exist")
    (is (= #{:event/ServerEvent} (set (dt/parents-of :event/ApiCall)))
        "ApiCall parent should be ServerEvent")))

(deftest transaction-hierarchy-test
  (testing "Transaction class hierarchy"
    (is (some? (db/entity :event/Transaction)) "Transaction should exist")
    (is (= #{:event/ServerEvent} (set (dt/parents-of :event/Transaction)))
        "Transaction parent should be ServerEvent")))

(deftest subclass-relationships-test
  (testing "subclasses-of returns all transitive subclasses"
    (let [event-subclasses (set (dt/subclasses-of :dt/Event))]
      (is (contains? event-subclasses :event/ServerEvent) "Should include ServerEvent")
      (is (contains? event-subclasses :event/UserEvent) "Should include UserEvent")
      (is (contains? event-subclasses :event/SystemEvent) "Should include SystemEvent")
      (is (contains? event-subclasses :event/HttpRequest) "Should include HttpRequest")
      (is (contains? event-subclasses :event/ApiCall) "Should include ApiCall")
      (is (contains? event-subclasses :event/Transaction) "Should include Transaction")))

  (testing "direct-subclasses-of returns immediate subclasses only"
    (let [event-direct (set (dt/direct-subclasses-of :dt/Event))]
      (is (contains? event-direct :event/ServerEvent) "Should include ServerEvent")
      (is (contains? event-direct :event/UserEvent) "Should include UserEvent")
      (is (contains? event-direct :event/SystemEvent) "Should include SystemEvent")
      (is (not (contains? event-direct :event/HttpRequest)) "Should NOT include HttpRequest")
      (is (not (contains? event-direct :event/ApiCall)) "Should NOT include ApiCall")))

  (testing "subclass-of? predicate"
    (is (dt/subclass-of? :dt/Event :event/ServerEvent) "ServerEvent is subclass of Event")
    (is (dt/subclass-of? :dt/Event :event/HttpRequest) "HttpRequest is subclass of Event")
    (is (dt/subclass-of? :event/ServerEvent :event/HttpRequest) "HttpRequest is subclass of ServerEvent")
    (is (dt/subclass-of? :dt/Resource :event/Transaction) "Transaction is subclass of dt/Resource")
    (is (not (dt/subclass-of? :event/UserEvent :event/ServerEvent)) "ServerEvent is NOT subclass of UserEvent")
    (is (not (dt/subclass-of? :event/HttpRequest :event/ApiCall)) "ApiCall is NOT subclass of HttpRequest")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property/Slot Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest event-properties-test
  (testing "Core event properties exist"
    (is (some? (db/entity :event/timestamp)) "event/timestamp should exist")
    (is (some? (db/entity :event/name)) "event/name should exist")
    (is (some? (db/entity :event/description)) "event/description should exist")
    (is (some? (db/entity :event/kind)) "event/kind should exist")
    (is (some? (db/entity :event/actor)) "event/actor should exist")
    (is (some? (db/entity :event/target)) "event/target should exist")
    (is (some? (db/entity :event/duration)) "event/duration should exist")
    (is (some? (db/entity :event/status)) "event/status should exist")
    (is (some? (db/entity :event/tags)) "event/tags should exist")
    (is (some? (db/entity :event/correlation-id)) "event/correlation-id should exist")
    (is (some? (db/entity :event/parent)) "event/parent should exist"))

  (testing "Event property domains"
    (is (= :dt/Event (dt/domain-of :event/timestamp)) "event/timestamp domain")
    (is (= :dt/Event (dt/domain-of :event/name)) "event/name domain")
    (is (= :dt/Event (dt/domain-of :event/kind)) "event/kind domain")
    (is (= :dt/Event (dt/domain-of :event/actor)) "event/actor domain")
    (is (= :dt/Event (dt/domain-of :event/target)) "event/target domain"))

  (testing "Event property ranges"
    (is (= :db.type/instant (dt/range-of :event/timestamp)) "event/timestamp range")
    (is (= :db.type/string (dt/range-of :event/name)) "event/name range")
    (is (= :db.type/string (dt/range-of :event/description)) "event/description range")
    (is (= :db.type/keyword (dt/range-of :event/kind)) "event/kind range")
    (is (= :dt/Ref (dt/range-of :event/actor)) "event/actor range")
    (is (= :dt/Ref (dt/range-of :event/target)) "event/target range")
    (is (= :db.type/long (dt/range-of :event/duration)) "event/duration range")
    (is (= :db.type/keyword (dt/range-of :event/status)) "event/status range")
    (is (= :db.type/keyword (dt/range-of :event/tags)) "event/tags range")
    (is (= :db.type/uuid (dt/range-of :event/correlation-id)) "event/correlation-id range")
    (is (= :dt/Event (dt/range-of :event/parent)) "event/parent range")))

(deftest server-event-properties-test
  (testing "Server event properties exist"
    (is (some? (db/entity :event/level)) "event/level should exist")
    (is (some? (db/entity :event/namespace)) "event/namespace should exist")
    (is (some? (db/entity :event/thread)) "event/thread should exist")
    (is (some? (db/entity :event/exception)) "event/exception should exist")
    (is (some? (db/entity :event/stacktrace)) "event/stacktrace should exist"))

  (testing "Server event property domains"
    (is (= :event/ServerEvent (dt/domain-of :event/level)) "event/level domain")
    (is (= :event/ServerEvent (dt/domain-of :event/namespace)) "event/namespace domain")
    (is (= :event/ServerEvent (dt/domain-of :event/thread)) "event/thread domain")
    (is (= :event/ServerEvent (dt/domain-of :event/exception)) "event/exception domain"))

  (testing "Server event property ranges"
    (is (= :db.type/keyword (dt/range-of :event/level)) "event/level range")
    (is (= :db.type/string (dt/range-of :event/namespace)) "event/namespace range")
    (is (= :db.type/string (dt/range-of :event/thread)) "event/thread range")
    (is (= :db.type/string (dt/range-of :event/exception)) "event/exception range")
    (is (= :db.type/string (dt/range-of :event/stacktrace)) "event/stacktrace range")))

(deftest http-request-properties-test
  (testing "HTTP request properties exist"
    (is (some? (db/entity :http/method)) "http/method should exist")
    (is (some? (db/entity :http/path)) "http/path should exist")
    (is (some? (db/entity :http/query-string)) "http/query-string should exist")
    (is (some? (db/entity :http/status-code)) "http/status-code should exist")
    (is (some? (db/entity :http/content-type)) "http/content-type should exist")
    (is (some? (db/entity :http/remote-addr)) "http/remote-addr should exist")
    (is (some? (db/entity :http/user-agent)) "http/user-agent should exist")
    (is (some? (db/entity :http/request-size)) "http/request-size should exist")
    (is (some? (db/entity :http/response-size)) "http/response-size should exist"))

  (testing "HTTP request property domains"
    (is (= :event/HttpRequest (dt/domain-of :http/method)) "http/method domain")
    (is (= :event/HttpRequest (dt/domain-of :http/path)) "http/path domain")
    (is (= :event/HttpRequest (dt/domain-of :http/status-code)) "http/status-code domain"))

  (testing "HTTP request property ranges"
    (is (= :db.type/keyword (dt/range-of :http/method)) "http/method range")
    (is (= :db.type/string (dt/range-of :http/path)) "http/path range")
    (is (= :db.type/string (dt/range-of :http/query-string)) "http/query-string range")
    (is (= :db.type/long (dt/range-of :http/status-code)) "http/status-code range")
    (is (= :db.type/string (dt/range-of :http/content-type)) "http/content-type range")
    (is (= :db.type/string (dt/range-of :http/remote-addr)) "http/remote-addr range")
    (is (= :db.type/string (dt/range-of :http/user-agent)) "http/user-agent range")
    (is (= :db.type/long (dt/range-of :http/request-size)) "http/request-size range")
    (is (= :db.type/long (dt/range-of :http/response-size)) "http/response-size range")))

(deftest api-call-properties-test
  (testing "API call properties exist"
    (is (some? (db/entity :api/endpoint)) "api/endpoint should exist")
    (is (some? (db/entity :api/handler)) "api/handler should exist")
    (is (some? (db/entity :api/params)) "api/params should exist"))

  (testing "API call property domains and ranges"
    (is (= :event/ApiCall (dt/domain-of :api/endpoint)) "api/endpoint domain")
    (is (= :db.type/keyword (dt/range-of :api/endpoint)) "api/endpoint range")
    (is (= :event/ApiCall (dt/domain-of :api/handler)) "api/handler domain")
    (is (= :db.type/symbol (dt/range-of :api/handler)) "api/handler range")
    (is (= :event/ApiCall (dt/domain-of :api/params)) "api/params domain")
    (is (= :db.type/string (dt/range-of :api/params)) "api/params range")))

(deftest transaction-properties-test
  (testing "Transaction properties exist"
    (is (some? (db/entity :tx/id)) "tx/id should exist")
    (is (some? (db/entity :tx/datom-count)) "tx/datom-count should exist")
    (is (some? (db/entity :tx/entities-affected)) "tx/entities-affected should exist"))

  (testing "Transaction property domains and ranges"
    (is (= :event/Transaction (dt/domain-of :tx/id)) "tx/id domain")
    (is (= :db.type/long (dt/range-of :tx/id)) "tx/id range")
    (is (= :event/Transaction (dt/domain-of :tx/datom-count)) "tx/datom-count domain")
    (is (= :db.type/long (dt/range-of :tx/datom-count)) "tx/datom-count range")
    (is (= :event/Transaction (dt/domain-of :tx/entities-affected)) "tx/entities-affected domain")
    (is (= :db.type/long (dt/range-of :tx/entities-affected)) "tx/entities-affected range")))

(deftest slot-inheritance-test
  (testing "HttpRequest inherits all slots from ServerEvent and Event"
    (let [slots (dt/slots-of :event/HttpRequest)]
      ;; From dt/Resource
      (is (contains? slots :dt/type) "Should have dt/type from Resource")
      (is (contains? slots :db/ident) "Should have db/ident from Resource")
      ;; From dt/Event
      (is (contains? slots :event/timestamp) "Should have event/timestamp")
      (is (contains? slots :event/name) "Should have event/name")
      (is (contains? slots :event/kind) "Should have event/kind")
      (is (contains? slots :event/actor) "Should have event/actor")
      (is (contains? slots :event/duration) "Should have event/duration")
      (is (contains? slots :event/status) "Should have event/status")
      (is (contains? slots :event/correlation-id) "Should have event/correlation-id")
      ;; From ServerEvent
      (is (contains? slots :event/level) "Should have event/level")
      (is (contains? slots :event/namespace) "Should have event/namespace")
      (is (contains? slots :event/exception) "Should have event/exception")
      ;; HttpRequest's own slots
      (is (contains? slots :http/method) "Should have http/method")
      (is (contains? slots :http/path) "Should have http/path")
      (is (contains? slots :http/status-code) "Should have http/status-code")))

  (testing "UserEvent inherits Event slots but not ServerEvent slots"
    (let [slots (dt/slots-of :event/UserEvent)]
      ;; From Event
      (is (contains? slots :event/timestamp) "Should have event/timestamp")
      (is (contains? slots :event/name) "Should have event/name")
      (is (contains? slots :event/actor) "Should have event/actor")
      ;; Should NOT have ServerEvent slots
      (is (not (contains? slots :event/level)) "Should NOT have event/level")
      (is (not (contains? slots :event/stacktrace)) "Should NOT have event/stacktrace")
      ;; Should NOT have HTTP slots
      (is (not (contains? slots :http/method)) "Should NOT have http/method")))

  (testing "Transaction inherits ServerEvent slots"
    (let [slots (dt/slots-of :event/Transaction)]
      ;; From ServerEvent
      (is (contains? slots :event/level) "Should have event/level")
      (is (contains? slots :event/namespace) "Should have event/namespace")
      ;; Transaction's own slots
      (is (contains? slots :tx/id) "Should have tx/id")
      (is (contains? slots :tx/datom-count) "Should have tx/datom-count")
      ;; Should NOT have HTTP slots
      (is (not (contains? slots :http/method)) "Should NOT have http/method"))))

(deftest direct-slots-test
  (testing "direct-slots-of returns only directly declared slots"
    (let [event-direct (dt/direct-slots-of :dt/Event)
          event-slot-idents (set (map #(:db/ident (db/entity %)) event-direct))]
      (is (contains? event-slot-idents :event/timestamp) "Should have timestamp")
      (is (contains? event-slot-idents :event/name) "Should have name")
      (is (contains? event-slot-idents :event/kind) "Should have kind")
      (is (contains? event-slot-idents :event/actor) "Should have actor")
      (is (not (contains? event-slot-idents :event/level)) "Should NOT have level (ServerEvent)"))

    (let [server-direct (dt/direct-slots-of :event/ServerEvent)
          server-slot-idents (set (map #(:db/ident (db/entity %)) server-direct))]
      (is (contains? server-slot-idents :event/level) "Should have level")
      (is (contains? server-slot-idents :event/namespace) "Should have namespace")
      (is (not (contains? server-slot-idents :event/timestamp)) "Should NOT have timestamp (Event)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Creation Tests (dt/make)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-server-event-test
  (testing "Create a basic ServerEvent"
    (let [now (Date.)
          event (dt/make :event/ServerEvent
                  {:event/timestamp now
                   :event/name "Test server event"
                   :event/kind :test/event
                   :event/level :info
                   :event/namespace "sandbar.test"
                   :event/status :success})]
      (is (some? event) "Should create entity")
      (is (= :event/ServerEvent (:dt/type event)) "Should have correct type")
      (is (= "Test server event" (:event/name event)) "Should have name")
      (is (= :test/event (:event/kind event)) "Should have kind")
      (is (= :info (:event/level event)) "Should have level")
      (is (= "sandbar.test" (:event/namespace event)) "Should have namespace")
      (is (= :success (:event/status event)) "Should have status"))))

(deftest create-user-event-test
  (testing "Create a UserEvent"
    (let [now (Date.)
          event (dt/make :event/UserEvent
                  {:event/timestamp now
                   :event/name "User login"
                   :event/kind :user/login
                   :event/status :success
                   :event/description "User alice logged in successfully"})]
      (is (some? event) "Should create entity")
      (is (= :event/UserEvent (:dt/type event)) "Should have correct type")
      (is (= "User login" (:event/name event)) "Should have name")
      (is (= :user/login (:event/kind event)) "Should have kind"))))

(deftest create-system-event-test
  (testing "Create a SystemEvent"
    (let [now (Date.)
          event (dt/make :event/SystemEvent
                  {:event/timestamp now
                   :event/name "Server startup"
                   :event/kind :system/startup
                   :event/status :success
                   :event/duration 3500})]
      (is (some? event) "Should create entity")
      (is (= :event/SystemEvent (:dt/type event)) "Should have correct type")
      (is (= 3500 (:event/duration event)) "Should have duration"))))

(deftest create-http-request-test
  (testing "Create an HttpRequest event"
    (let [now (Date.)
          correlation-id (UUID/randomUUID)
          request (dt/make :event/HttpRequest
                    {:event/timestamp now
                     :event/name "GET /api/store/classes"
                     :event/kind :http/request
                     :event/level :info
                     :event/namespace "sandbar.api.store"
                     :event/status :success
                     :event/duration 42
                     :event/correlation-id correlation-id
                     :http/method :get
                     :http/path "/api/store/classes"
                     :http/status-code 200
                     :http/content-type "application/edn"
                     :http/remote-addr "127.0.0.1"
                     :http/user-agent "curl/7.79.1"
                     :http/response-size 1024})]
      (is (some? request) "Should create entity")
      (is (= :event/HttpRequest (:dt/type request)) "Should have correct type")
      (is (= :get (:http/method request)) "Should have method")
      (is (= "/api/store/classes" (:http/path request)) "Should have path")
      (is (= 200 (:http/status-code request)) "Should have status code")
      (is (= "application/edn" (:http/content-type request)) "Should have content type")
      (is (= "127.0.0.1" (:http/remote-addr request)) "Should have remote addr")
      (is (= 42 (:event/duration request)) "Should have duration")
      (is (= correlation-id (:event/correlation-id request)) "Should have correlation ID"))))

(deftest create-api-call-test
  (testing "Create an ApiCall event"
    (let [now (Date.)
          api-call (dt/make :event/ApiCall
                     {:event/timestamp now
                      :event/name "list-classes"
                      :event/kind :api/call
                      :event/level :debug
                      :event/namespace "sandbar.api.store"
                      :event/status :success
                      :event/duration 15
                      :api/endpoint :store/list-classes
                      :api/handler 'sandbar.api.store/list-classes
                      :api/params "{}"})]
      (is (some? api-call) "Should create entity")
      (is (= :event/ApiCall (:dt/type api-call)) "Should have correct type")
      (is (= :store/list-classes (:api/endpoint api-call)) "Should have endpoint")
      (is (= 'sandbar.api.store/list-classes (:api/handler api-call)) "Should have handler")
      (is (= "{}" (:api/params api-call)) "Should have params"))))

(deftest create-transaction-test
  (testing "Create a Transaction event"
    (let [now (Date.)
          tx-event (dt/make :event/Transaction
                     {:event/timestamp now
                      :event/name "Create user"
                      :event/kind :db/transaction
                      :event/level :info
                      :event/namespace "sandbar.db.datomic"
                      :event/status :success
                      :event/duration 8
                      :tx/id 13194139534312
                      :tx/datom-count 5
                      :tx/entities-affected 1})]
      (is (some? tx-event) "Should create entity")
      (is (= :event/Transaction (:dt/type tx-event)) "Should have correct type")
      (is (= 13194139534312 (:tx/id tx-event)) "Should have tx id")
      (is (= 5 (:tx/datom-count tx-event)) "Should have datom count")
      (is (= 1 (:tx/entities-affected tx-event)) "Should have entities affected"))))

(deftest create-event-with-tags-test
  (testing "Create event with multiple tags"
    (let [event (dt/make :event/ServerEvent
                  {:event/timestamp (Date.)
                   :event/name "Tagged event"
                   :event/kind :test/tagged
                   :event/level :info
                   :event/namespace "test"
                   :event/tags #{:important :security :audit}})]
      (is (some? event) "Should create entity")
      (is (= #{:important :security :audit} (:event/tags event)) "Should have all tags"))))

(deftest create-event-with-error-test
  (testing "Create ServerEvent representing an error"
    (let [event (dt/make :event/ServerEvent
                  {:event/timestamp (Date.)
                   :event/name "NullPointerException"
                   :event/kind :error/exception
                   :event/level :error
                   :event/namespace "sandbar.api.store"
                   :event/status :failure
                   :event/exception "NullPointerException: Cannot invoke method on null"
                   :event/stacktrace "at sandbar.api.store/get-class(store.clj:42)\n  at clojure.lang.AFn.invoke(AFn.java:154)"})]
      (is (some? event) "Should create entity")
      (is (= :error (:event/level event)) "Should have error level")
      (is (= :failure (:event/status event)) "Should have failure status")
      (is (some? (:event/exception event)) "Should have exception")
      (is (some? (:event/stacktrace event)) "Should have stacktrace"))))

(deftest abstract-class-rejection-test
  (testing "Cannot instantiate abstract Event class"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Validation failed"
                          (dt/make :dt/Event
                            {:event/timestamp (Date.)
                             :event/name "Abstract event"}))
        "Should reject abstract class instantiation")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Query Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest instance-of-predicate-test
  (testing "instance-of? with created events"
    (let [http-request (dt/make :event/HttpRequest
                         {:event/timestamp (Date.)
                          :event/name "Test request"
                          :event/level :info
                          :event/namespace "test"
                          :http/method :get
                          :http/path "/test"
                          :http/status-code 200})
          user-event (dt/make :event/UserEvent
                       {:event/timestamp (Date.)
                        :event/name "Test user event"
                        :event/kind :user/action})]
      ;; HttpRequest instances
      (is (dt/instance-of? :event/HttpRequest http-request) "Should be instance of HttpRequest")
      (is (dt/instance-of? :event/ServerEvent http-request) "Should be instance of ServerEvent")
      (is (dt/instance-of? :dt/Event http-request) "Should be instance of Event")
      (is (dt/instance-of? :dt/Ref http-request) "Should be instance of dt/Ref")
      (is (dt/instance-of? :dt/Resource http-request) "Should be instance of dt/Resource")
      (is (not (dt/instance-of? :event/UserEvent http-request)) "Should NOT be instance of UserEvent")
      (is (not (dt/instance-of? :event/Transaction http-request)) "Should NOT be instance of Transaction")

      ;; UserEvent instances
      (is (dt/instance-of? :event/UserEvent user-event) "Should be instance of UserEvent")
      (is (dt/instance-of? :dt/Event user-event) "Should be instance of Event")
      (is (not (dt/instance-of? :event/ServerEvent user-event)) "Should NOT be instance of ServerEvent"))))

(deftest all-instances-query-test
  (testing "all-instances-of returns instances including subclasses"
    ;; Create various events
    (dt/make :event/HttpRequest
      {:event/timestamp (Date.)
       :event/name "HTTP Request 1"
       :event/level :info
       :event/namespace "test"
       :http/method :get
       :http/path "/test1"
       :http/status-code 200})
    (dt/make :event/ApiCall
      {:event/timestamp (Date.)
       :event/name "API Call 1"
       :event/level :debug
       :event/namespace "test"
       :api/endpoint :test/endpoint})
    (dt/make :event/UserEvent
      {:event/timestamp (Date.)
       :event/name "User Event 1"
       :event/kind :user/test})
    (dt/make :event/Transaction
      {:event/timestamp (Date.)
       :event/name "Transaction 1"
       :event/level :info
       :event/namespace "test"
       :tx/id 123
       :tx/datom-count 1
       :tx/entities-affected 1})

    (testing "Querying Event returns all events"
      (let [all-events (dt/all-instances-of :dt/Event)
            names (set (map :event/name all-events))]
        (is (contains? names "HTTP Request 1") "Should include HttpRequest")
        (is (contains? names "API Call 1") "Should include ApiCall")
        (is (contains? names "User Event 1") "Should include UserEvent")
        (is (contains? names "Transaction 1") "Should include Transaction")))

    (testing "Querying ServerEvent returns only server events"
      (let [server-events (dt/all-instances-of :event/ServerEvent)
            names (set (map :event/name server-events))]
        (is (contains? names "HTTP Request 1") "Should include HttpRequest")
        (is (contains? names "API Call 1") "Should include ApiCall")
        (is (contains? names "Transaction 1") "Should include Transaction")
        (is (not (contains? names "User Event 1")) "Should NOT include UserEvent")))

    (testing "Querying HttpRequest returns only http requests"
      (let [http-requests (dt/all-instances-of :event/HttpRequest)
            names (set (map :event/name http-requests))]
        (is (contains? names "HTTP Request 1") "Should include HttpRequest")
        (is (not (contains? names "API Call 1")) "Should NOT include ApiCall")))))

(deftest direct-instances-query-test
  (testing "direct-instances-of excludes subclass instances"
    (dt/make :event/HttpRequest
      {:event/timestamp (Date.)
       :event/name "Direct Test HTTP"
       :event/level :info
       :event/namespace "test"
       :http/method :get
       :http/path "/direct"
       :http/status-code 200})
    (dt/make :event/ServerEvent
      {:event/timestamp (Date.)
       :event/name "Direct Test Server"
       :event/level :info
       :event/namespace "test"})

    (testing "ServerEvent direct instances exclude HttpRequest"
      (let [direct-server (dt/direct-instances-of :event/ServerEvent)
            names (set (map :event/name direct-server))]
        (is (contains? names "Direct Test Server") "Should include direct ServerEvent")
        (is (not (contains? names "Direct Test HTTP")) "Should NOT include HttpRequest")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-event-instance-test
  (testing "Valid instances pass validation"
    (let [valid-event (dt/make :event/ServerEvent
                        {:event/timestamp (Date.)
                         :event/name "Valid event"
                         :event/level :info
                         :event/namespace "test"})]
      (is (dt/valid? valid-event) "Valid event should pass validation"))))

(deftest class-type-checking-test
  (testing "Class types are correctly identified"
    (is (= :dt/Class (dt/class-of :dt/Event)) "Event is a Class")
    (is (= :dt/Class (dt/class-of :event/ServerEvent)) "ServerEvent is a Class")
    (is (= :dt/Class (dt/class-of :event/HttpRequest)) "HttpRequest is a Class")
    (is (= :dt/Property (dt/class-of :event/timestamp)) "event/timestamp is a Property")
    (is (= :dt/Property (dt/class-of :http/method)) "http/method is a Property")
    (is (= :dt/Property (dt/class-of :tx/id)) "tx/id is a Property")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests - Full Scenarios
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest request-lifecycle-scenario-test
  (testing "Complete HTTP request lifecycle with correlated events"
    (let [correlation-id (UUID/randomUUID)
          request-time (Date.)

          ;; Parent HTTP request event
          http-event (dt/make :event/HttpRequest
                       {:event/timestamp request-time
                        :event/name "GET /api/store/classes/dt/Resource"
                        :event/kind :http/request
                        :event/level :info
                        :event/namespace "sandbar.server.pedestal"
                        :event/correlation-id correlation-id
                        :event/duration 150
                        :event/status :success
                        :http/method :get
                        :http/path "/api/store/classes/dt/Resource"
                        :http/status-code 200
                        :http/content-type "application/edn"
                        :http/remote-addr "192.168.1.100"
                        :http/response-size 2048})

          ;; Child API call event
          api-event (dt/make :event/ApiCall
                      {:event/timestamp request-time
                       :event/name "get-class"
                       :event/kind :api/handler
                       :event/level :debug
                       :event/namespace "sandbar.api.store"
                       :event/correlation-id correlation-id
                       :event/parent (:db/id http-event)
                       :event/duration 45
                       :event/status :success
                       :api/endpoint :store/get-class
                       :api/handler 'sandbar.api.store/get-class
                       :api/params "{:ns \"dt\", :name \"Resource\"}"})

          ;; Database query event
          tx-event (dt/make :event/Transaction
                     {:event/timestamp request-time
                      :event/name "Query class dt/Resource"
                      :event/kind :db/query
                      :event/level :trace
                      :event/namespace "sandbar.db.datomic"
                      :event/correlation-id correlation-id
                      :event/parent (:db/id api-event)
                      :event/duration 12
                      :event/status :success
                      :tx/id 13194139534400
                      :tx/datom-count 0
                      :tx/entities-affected 0})]

      (testing "All events created successfully"
        (is (some? http-event))
        (is (some? api-event))
        (is (some? tx-event)))

      (testing "Events share correlation ID"
        (is (= correlation-id (:event/correlation-id http-event)))
        (is (= correlation-id (:event/correlation-id api-event)))
        (is (= correlation-id (:event/correlation-id tx-event))))

      (testing "Parent references are correct"
        (is (= (:db/id http-event) (:db/id (:event/parent api-event))))
        (is (= (:db/id api-event) (:db/id (:event/parent tx-event)))))

      (testing "Query events by correlation ID"
        (let [correlated (d/q '[:find ?e
                                :in $ ?cid
                                :where [?e :event/correlation-id ?cid]]
                              (db/db) correlation-id)]
          (is (= 3 (count correlated)) "Should find 3 correlated events"))))))

(deftest error-tracking-scenario-test
  (testing "Error event with full context"
    (let [correlation-id (UUID/randomUUID)

          ;; Request that caused error
          http-event (dt/make :event/HttpRequest
                       {:event/timestamp (Date.)
                        :event/name "GET /api/store/classes/nonexistent/Class"
                        :event/kind :http/request
                        :event/level :warn
                        :event/namespace "sandbar.server.pedestal"
                        :event/correlation-id correlation-id
                        :event/status :failure
                        :event/duration 25
                        :http/method :get
                        :http/path "/api/store/classes/nonexistent/Class"
                        :http/status-code 404
                        :http/content-type "application/edn"
                        :http/remote-addr "10.0.0.5"})

          ;; Error event
          error-event (dt/make :event/ServerEvent
                        {:event/timestamp (Date.)
                         :event/name "Class not found"
                         :event/kind :error/not-found
                         :event/level :warn
                         :event/namespace "sandbar.api.store"
                         :event/correlation-id correlation-id
                         :event/parent (:db/id http-event)
                         :event/status :failure
                         :event/description "Class :nonexistent/Class does not exist in the metamodel"
                         :event/tags #{:client-error :404}})]

      (testing "Error events created"
        (is (some? http-event))
        (is (some? error-event)))

      (testing "Error has appropriate level and status"
        (is (= :warn (:event/level error-event)))
        (is (= :failure (:event/status error-event))))

      (testing "HTTP request shows 404"
        (is (= 404 (:http/status-code http-event)))))))

(deftest event-level-query-test
  (testing "Query events by severity level"
    ;; Create events at different levels
    (dt/make :event/ServerEvent
      {:event/timestamp (Date.)
       :event/name "Debug event"
       :event/level :debug
       :event/namespace "test"})
    (dt/make :event/ServerEvent
      {:event/timestamp (Date.)
       :event/name "Info event"
       :event/level :info
       :event/namespace "test"})
    (dt/make :event/ServerEvent
      {:event/timestamp (Date.)
       :event/name "Error event"
       :event/level :error
       :event/namespace "test"})

    (testing "Find error-level events"
      (let [errors (d/q '[:find ?e ?name
                          :where
                          [?e :event/level :error]
                          [?e :event/name ?name]]
                        (db/db))]
        (is (= 1 (count errors)))
        (is (= "Error event" (second (first errors))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API Tests - EDN/JSON/Transit Content Types
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest api-event-class-edn-test
  (testing "Event class via EDN returns keywords"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/dt/Event")]
      (is (= 200 status))
      (is (= :dt/Event (:class body)) "class should be keyword")
      (is (keyword? (:class body)))
      (is (true? (:abstract? body)) "Event should be abstract")
      (is (contains? (set (:subclasses body)) :event/ServerEvent))
      (is (contains? (set (:subclasses body)) :event/UserEvent))
      (is (contains? (set (:subclasses body)) :event/SystemEvent))))

  (testing "Event class hierarchy via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/event/HttpRequest/hierarchy")]
      (is (= 200 status))
      (is (= :event/HttpRequest (:class body)))
      (is (contains? (set (:parents body)) :event/ServerEvent))
      (is (contains? (set (:ancestors body)) :dt/Event))
      (is (contains? (set (:ancestors body)) :dt/Resource)))))

(deftest api-event-class-json-test
  (testing "Event class via JSON returns strings"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/dt/Event")]
      (is (= 200 status))
      (is (= "dt/Event" (:class body)) "JSON converts keywords to strings")
      (is (string? (:class body)))
      (is (every? string? (:slots body)) "slots are strings in JSON")))

  (testing "Event subclasses via JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/dt/Event/subclasses")]
      (is (= 200 status))
      (is (= "dt/Event" (:class body)))
      (is (contains? (set (:subclasses body)) "event/ServerEvent"))
      (is (contains? (set (:subclasses body)) "event/HttpRequest")))))

(deftest api-event-class-transit-test
  (testing "Event class via Transit preserves keywords"
    (let [{:keys [status body]} (tu/api-get-transit "/api/store/classes/dt/Event")]
      (is (= 200 status))
      (is (= :dt/Event (:class body)) "Transit preserves keywords")
      (is (keyword? (:class body)))
      (is (every? keyword? (:slots body)) "Transit preserves keyword slots")))

  (testing "Event ancestors via Transit"
    (let [{:keys [status body]} (tu/api-get-transit "/api/store/classes/event/Transaction/ancestors")]
      (is (= 200 status))
      (is (= :event/Transaction (:class body)))
      (is (keyword? (:class body)))
      (is (every? keyword? (:ancestors body)))
      (is (contains? (set (:ancestors body)) :event/ServerEvent))
      (is (contains? (set (:ancestors body)) :dt/Event))
      (is (contains? (set (:ancestors body)) :dt/Ref)))))

(deftest api-event-property-types-test
  (testing "Property with instant range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/event/timestamp")]
      (is (= 200 status))
      (is (= :event/timestamp (:property body)))
      (is (= :db.type/instant (:range body)) "range should be db.type/instant")))

  (testing "Property with keyword range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/event/level")]
      (is (= 200 status))
      (is (= :event/level (:property body)))
      (is (= :db.type/keyword (:range body)))))

  (testing "Property with long range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/http/status-code")]
      (is (= 200 status))
      (is (= :http/status-code (:property body)))
      (is (= :db.type/long (:range body)))))

  (testing "Property with symbol range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/api/handler")]
      (is (= 200 status))
      (is (= :api/handler (:property body)))
      (is (= :db.type/symbol (:range body))))))

(deftest api-event-type-predicates-test
  (testing "subclass-of predicate via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/types/subclass-of/dt/Event/event/HttpRequest")]
      (is (= 200 status))
      (is (= :dt/Event (:parent body)))
      (is (= :event/HttpRequest (:child body)))
      (is (true? (:subclass-of? body)))))

  (testing "subclass-of predicate - negative case"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/types/subclass-of/event/UserEvent/event/ServerEvent")]
      (is (= 200 status))
      (is (false? (:subclass-of? body)) "ServerEvent is not subclass of UserEvent")))

  (testing "subclass-of via JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/types/subclass-of/event/ServerEvent/event/Transaction")]
      (is (= 200 status))
      (is (= "event/ServerEvent" (:parent body)))
      (is (= "event/Transaction" (:child body)))
      (is (true? (:subclass-of? body))))))

(deftest api-event-slots-test
  (testing "HttpRequest slots include inherited slots via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/event/HttpRequest/slots")]
      (is (= 200 status))
      (is (= :event/HttpRequest (:class body)))
      (let [slot-idents (set (map :ident (:slots body)))]
        ;; From Resource
        (is (contains? slot-idents :dt/type))
        ;; From Event
        (is (contains? slot-idents :event/timestamp))
        (is (contains? slot-idents :event/name))
        (is (contains? slot-idents :event/correlation-id))
        ;; From ServerEvent
        (is (contains? slot-idents :event/level))
        (is (contains? slot-idents :event/namespace))
        ;; HttpRequest's own
        (is (contains? slot-idents :http/method))
        (is (contains? slot-idents :http/path))
        (is (contains? slot-idents :http/status-code)))))

  (testing "Transaction direct slots only"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/event/Transaction/slots/direct")]
      (is (= 200 status))
      (is (= :event/Transaction (:class body)))
      (is (= 3 (:count body)) "Transaction has 3 direct slots")
      (let [slots (set (:slots body))]
        (is (contains? slots :tx/id))
        (is (contains? slots :tx/datom-count))
        (is (contains? slots :tx/entities-affected))
        (is (not (contains? slots :event/level)) "Should not include inherited")))))

(deftest api-event-abstract-class-test
  (testing "Event is abstract"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/dt/Event")]
      (is (= 200 status))
      (is (true? (:abstract? body)) "Event should be abstract")))

  (testing "ServerEvent is not abstract"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/event/ServerEvent")]
      (is (= 200 status))
      (is (false? (:abstract? body)) "ServerEvent should not be abstract")))

  (testing "HttpRequest is not abstract"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/event/HttpRequest")]
      (is (= 200 status))
      (is (false? (:abstract? body)) "HttpRequest should not be abstract"))))
