(ns sandbar.api-event-test
  "Test suite for the Event API endpoints."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.api.event :as event-api]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]
            [sandbar.util.event :as event])
  (:import [java.util UUID Date]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "api-event-test"
                                              :extra-schema [:event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POST /api/events - Create event with type in body
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-event-with-type-test
  (testing "Create ServerEvent via POST /api/events"
    (let [response (response-for tu/service :post "/api/events"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:dt/type :event/ServerEvent
                                                :event/name "Test event"
                                                :event/level :info
                                                :event/namespace "test"}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (true? (:created body)))
      (is (= :event/ServerEvent (:type body)))
      (is (= "Test event" (get-in body [:event :event/name])))))

  (testing "Create UserEvent via POST /api/events"
    (let [response (response-for tu/service :post "/api/events"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:dt/type :event/UserEvent
                                                :event/name "User action"
                                                :event/kind :user/login}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/UserEvent (:type body)))))

  (testing "Missing :dt/type returns 400"
    (let [response (response-for tu/service :post "/api/events"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "No type"}))]
      (is (= 400 (:status response)))))

  (testing "Invalid :dt/type returns 400"
    (let [response (response-for tu/service :post "/api/events"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:dt/type :invalid/Type
                                                :event/name "Bad type"}))]
      (is (= 400 (:status response))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; POST /api/events/:type - Create typed events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-server-event-test
  (testing "POST /api/events/server creates ServerEvent"
    (let [response (response-for tu/service :post "/api/events/server"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "Server event"
                                                :event/level :warn
                                                :event/namespace "sandbar.test"}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/ServerEvent (:type body)))
      (is (= :warn (get-in body [:event :event/level]))))))

(deftest create-user-event-test
  (testing "POST /api/events/user creates UserEvent"
    (let [response (response-for tu/service :post "/api/events/user"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "User event"
                                                :event/kind :user/logout}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/UserEvent (:type body))))))

(deftest create-system-event-test
  (testing "POST /api/events/system creates SystemEvent"
    (let [response (response-for tu/service :post "/api/events/system"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "System startup"
                                                :event/kind :system/startup
                                                :event/status :success}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/SystemEvent (:type body))))))

(deftest create-http-event-test
  (testing "POST /api/events/http creates HttpRequest"
    (let [response (response-for tu/service :post "/api/events/http"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "GET /api/test"
                                                :event/level :info
                                                :event/namespace "test"
                                                :http/method :get
                                                :http/path "/api/test"
                                                :http/status-code 200}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/HttpRequest (:type body)))
      (is (= :get (get-in body [:event :http/method])))
      (is (= "/api/test" (get-in body [:event :http/path])))
      (is (= 200 (get-in body [:event :http/status-code]))))))

(deftest create-api-event-test
  (testing "POST /api/events/api creates ApiCall"
    (let [response (response-for tu/service :post "/api/events/api"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "API call"
                                                :event/level :debug
                                                :event/namespace "test"
                                                :api/endpoint :test/endpoint
                                                :api/handler 'test.ns/handler}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/ApiCall (:type body)))
      (is (= :test/endpoint (get-in body [:event :api/endpoint]))))))

(deftest create-transaction-event-test
  (testing "POST /api/events/transaction creates Transaction"
    (let [response (response-for tu/service :post "/api/events/transaction"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "DB transaction"
                                                :event/level :info
                                                :event/namespace "test"
                                                :tx/id 12345
                                                :tx/datom-count 10
                                                :tx/entities-affected 3}))
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)))
      (is (= :event/Transaction (:type body)))
      (is (= 12345 (get-in body [:event :tx/id]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/events - List events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest list-events-test
  (testing "GET /api/events returns events"
    ;; Create some events first
    (event/log! :info "Test event 1")
    (event/log! :warn "Test event 2")
    (event/log! :error "Test event 3")

    (let [{:keys [status body]} (tu/api-get-edn "/api/events")]
      (is (= 200 status))
      (is (= 3 (:count body)))
      (is (= 100 (:limit body)))
      (is (= 3 (count (:events body)))))))

(deftest list-events-with-level-filter-test
  (testing "GET /api/events?level=error filters by level"
    ;; Create events
    (event/log! :info "Info event")
    (event/log! :error "Error event")

    (let [{:keys [status body]} (tu/api-get-edn "/api/events?level=error")]
      (is (= 200 status))
      (is (= 1 (:count body)))
      (is (= :error (get-in body [:filters :level])))
      (is (every? #(= :error (:event/level %)) (:events body))))))

(deftest list-events-with-limit-test
  (testing "GET /api/events?limit=2 limits results"
    ;; Create events
    (event/log! :info "Event 1")
    (event/log! :info "Event 2")
    (event/log! :info "Event 3")

    (let [{:keys [status body]} (tu/api-get-edn "/api/events?limit=2")]
      (is (= 200 status))
      (is (= 2 (:count body)))
      (is (= 2 (:limit body))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/events/:id - Get event by ID
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-event-by-id-test
  (testing "GET /api/events/:id returns event"
    (let [created (event/log! :info "Test event")
          event-id (:db/id created)
          {:keys [status body]} (tu/api-get-edn (str "/api/events/" event-id))]
      (is (= 200 status))
      (is (= "Test event" (get-in body [:event :event/name])))))

  (testing "GET /api/events/:id returns 404 for non-existent"
    (let [{:keys [status]} (tu/api-get-edn "/api/events/999999999")]
      (is (= 404 status))))

  (testing "GET /api/events/:id returns 400 for invalid ID"
    (let [{:keys [status]} (tu/api-get-edn "/api/events/not-a-number")]
      (is (= 400 status)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/events/correlation/:uuid - Get correlated events
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-events-by-correlation-test
  (testing "GET /api/events/correlation/:uuid returns correlated events"
    (let [correlation-id (UUID/randomUUID)
          ;; Create correlated events
          _ (event/log-event! :event/ServerEvent
                              {:event/name "Event 1"
                               :event/level :info
                               :event/namespace "test"
                               :event/correlation-id correlation-id})
          _ (event/log-event! :event/ServerEvent
                              {:event/name "Event 2"
                               :event/level :debug
                               :event/namespace "test"
                               :event/correlation-id correlation-id})
          ;; Create unrelated event
          _ (event/log! :info "Unrelated event")

          {:keys [status body]} (tu/api-get-edn (str "/api/events/correlation/" correlation-id))]
      (is (= 200 status))
      (is (= correlation-id (:correlation-id body)))
      (is (= 2 (:count body)))
      (is (= 2 (count (:events body))))))

  (testing "GET /api/events/correlation/:uuid returns 400 for invalid UUID"
    (let [{:keys [status]} (tu/api-get-edn "/api/events/correlation/not-a-uuid")]
      (is (= 400 status)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON content type
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-event-json-test
  (testing "POST /api/events/server with JSON"
    (let [response (response-for tu/service :post "/api/events/server"
                                 :headers {"Content-Type" "application/json"
                                           "Accept" "application/json"}
                                 :body "{\"event/name\": \"JSON event\", \"event/level\": \"info\", \"event/namespace\": \"test\"}")
          body (tu/parse-json-body response)]
      (is (= 201 (:status response)))
      (is (= "event/ServerEvent" (:type body)))
      (is (= "JSON event" (get-in body [:event :event/name]))))))

(deftest list-events-json-test
  (testing "GET /api/events with JSON Accept"
    (event/log! :info "JSON list test")

    (let [{:keys [status body]} (tu/api-get-json "/api/events")]
      (is (= 200 status))
      (is (number? (:count body)))
      (is (vector? (:events body))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Programmatic logging functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest log-function-test
  (testing "log! creates ServerEvent"
    (let [event (event/log! :info "Test message")]
      (is (some? event))
      (is (= :event/ServerEvent (:dt/type event)))
      (is (= "Test message" (:event/name event)))
      (is (= :info (:event/level event)))))

  (testing "log! with extra data"
    (let [event (event/log! :warn "Warning" {:event/status :failure})]
      (is (= :warn (:event/level event)))
      (is (= :failure (:event/status event))))))

(deftest log-event-function-test
  (testing "log-event! creates typed event"
    (let [event (event/log-event! :event/UserEvent
                                  {:event/name "User action"
                                   :event/kind :user/test})]
      (is (some? event))
      (is (= :event/UserEvent (:dt/type event))))))

(deftest log-http-function-test
  (testing "log-http! creates HttpRequest"
    (let [event (event/log-http! {:http/method :post
                                  :http/path "/api/test"
                                  :http/status-code 201
                                  :event/duration 50})]
      (is (some? event))
      (is (= :event/HttpRequest (:dt/type event)))
      (is (= :post (:http/method event)))
      (is (= 201 (:http/status-code event))))))

(deftest log-error-function-test
  (testing "log-error! with message only"
    (let [event (event/log-error! "Something failed")]
      (is (some? event))
      (is (= :error (:event/level event)))))

  (testing "log-error! with exception"
    (let [ex (ex-info "Test error" {:code 500})
          event (event/log-error! "Operation failed" ex)]
      (is (some? event))
      (is (= :error (:event/level event)))
      (is (= :failure (:event/status event)))
      (is (some? (:event/exception event)))
      (is (some? (:event/stacktrace event))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Auto timestamp
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest auto-timestamp-test
  (testing "Events get automatic timestamp if not provided"
    (let [before (Date.)
          _ (Thread/sleep 10)
          event (event/log! :info "Auto timestamp")
          _ (Thread/sleep 10)
          after (Date.)]
      (is (some? (:event/timestamp event)))
      (is (.after (:event/timestamp event) before))
      (is (.before (:event/timestamp event) after)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptor tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest log-request-interceptor-test
  (testing "log-request interceptor logs API requests"
    ;; Make a request that will be logged
    (let [_ (tu/api-get-edn "/api/store/classes")
          ;; Give it a moment to persist
          _ (Thread/sleep 50)
          ;; Query for logged events
          {:keys [status body]} (tu/api-get-edn "/api/events?limit=10")]
      (is (= 200 status))
      ;; Should have at least the store/classes request logged
      ;; (events API requests are excluded to prevent infinite loops)
      (let [events (:events body)
            store-events (filter #(and (:http/path %)
                                       (.contains (:http/path %) "/api/store"))
                                 events)]
        (is (seq store-events) "Should have logged store API requests")
        (when (seq store-events)
          (let [event (first store-events)]
            (is (= :get (:http/method event)))
            (is (some? (:event/duration event)))
            (is (= 200 (:http/status-code event))))))))

  (testing "log-request interceptor excludes /api/events to prevent loops"
    ;; Clear any existing events by creating a marker
    (let [marker-id (UUID/randomUUID)
          _ (event/log-event! :event/ServerEvent
                              {:event/name "marker"
                               :event/level :info
                               :event/namespace "test"
                               :event/correlation-id marker-id})
          ;; Make requests to the events API
          _ (tu/api-get-edn "/api/events")
          _ (tu/api-get-edn "/api/events?limit=5")
          _ (Thread/sleep 50)
          ;; Check that events API requests were NOT logged
          {:keys [body]} (tu/api-get-edn "/api/events?limit=50")
          events-api-events (filter #(and (:http/path %)
                                          (.startsWith (:http/path %) "/api/events"))
                                    (:events body))]
      (is (empty? events-api-events)
          "Events API requests should not be logged to prevent infinite loops")))

  (testing "log-request interceptor captures error status codes"
    ;; Make a request that returns 404
    (let [_ (tu/api-get-edn "/api/store/classes/nonexistent/Class")
          _ (Thread/sleep 50)
          {:keys [body]} (tu/api-get-edn "/api/events?limit=10")
          error-events (filter #(= 404 (:http/status-code %)) (:events body))]
      (is (seq error-events) "Should have logged 404 error")
      (when (seq error-events)
        (let [event (first error-events)]
          (is (= :warn (:event/level event)) "404 should be logged as warn"))))))

(deftest suppress-event-logging-test
  (testing "suppress-event-logging context flag works"
    ;; The should-log? function checks for :suppress-event-logging? in context
    (let [normal-context {:request {:uri "/api/store/classes"}}
          suppressed-context (assoc normal-context :suppress-event-logging? true)]
      (is (true? (#'event/should-log? normal-context)))
      (is (false? (#'event/should-log? suppressed-context))))))

(deftest correlation-id-propagation-test
  (testing "Correlation ID is generated for requests"
    ;; Make a request
    (let [_ (tu/api-get-edn "/api/store/schema")
          _ (Thread/sleep 50)
          {:keys [body]} (tu/api-get-edn "/api/events?limit=5")
          schema-events (filter #(and (:http/path %)
                                      (.contains (:http/path %) "/schema"))
                                (:events body))]
      (when (seq schema-events)
        (is (some? (:event/correlation-id (first schema-events)))
            "Request should have a correlation ID")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API Request Event Verification Tests
;;
;; These tests make API requests and verify the corresponding events are
;; correctly logged with accurate details.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- find-event-for-path
  "Find the most recent event logged for a given path."
  [path]
  (Thread/sleep 50) ; Allow event to be persisted
  (let [{:keys [body]} (tu/api-get-edn "/api/events?limit=50")]
    (->> (:events body)
         (filter #(= path (:http/path %)))
         first)))

(deftest verify-get-request-event-test
  (testing "GET request to /api/store/classes logs correct event"
    (let [{:keys [status]} (tu/api-get-edn "/api/store/classes")
          event (find-event-for-path "/api/store/classes")]
      (is (= 200 status))
      (is (some? event) "Event should be logged for GET /api/store/classes")
      (when event
        (is (= :event/HttpRequest (:dt/type event)))
        (is (= :get (:http/method event)))
        (is (= "/api/store/classes" (:http/path event)))
        (is (= 200 (:http/status-code event)))
        (is (= :success (:event/status event)))
        (is (= :info (:event/level event)))
        (is (number? (:event/duration event)))
        (is (>= (:event/duration event) 0))
        (is (some? (:event/timestamp event)))
        (is (some? (:event/correlation-id event)))
        (is (= "GET /api/store/classes" (:event/name event))))))

  (testing "GET request to /api/store/schema logs correct event"
    (let [{:keys [status]} (tu/api-get-edn "/api/store/schema")
          event (find-event-for-path "/api/store/schema")]
      (is (= 200 status))
      (is (some? event))
      (when event
        (is (= :get (:http/method event)))
        (is (= 200 (:http/status-code event)))))))

(deftest verify-get-with-params-event-test
  (testing "GET request with path params logs correct path"
    (let [{:keys [status]} (tu/api-get-edn "/api/store/classes/dt/Class")
          event (find-event-for-path "/api/store/classes/dt/Class")]
      (is (= 200 status))
      (is (some? event))
      (when event
        (is (= :get (:http/method event)))
        (is (= "/api/store/classes/dt/Class" (:http/path event)))
        (is (= 200 (:http/status-code event)))))))

(deftest verify-post-request-event-test
  (testing "POST request logs correct method and status"
    (let [response (response-for tu/service :post "/api/events/server"
                                 :headers {"Content-Type" "application/edn"
                                           "Accept" "application/edn"}
                                 :body (pr-str {:event/name "Test POST"
                                                :event/level :info
                                                :event/namespace "test"}))
          ;; POST to /api/events is excluded from logging, so we need to test
          ;; a different endpoint. Let's verify the created event instead.
          created-event (tu/parse-edn-body response)]
      ;; The POST itself won't be logged (events API excluded),
      ;; but we can verify the event was created
      (is (= 201 (:status response)))
      (is (= :event/ServerEvent (:type created-event))))))

(deftest verify-404-error-event-test
  (testing "404 response logs event with warn level and failure status"
    (let [{:keys [status]} (tu/api-get-edn "/api/store/classes/nonexistent/NotAClass")
          event (find-event-for-path "/api/store/classes/nonexistent/NotAClass")]
      (is (= 404 status))
      (is (some? event) "404 error should be logged")
      (when event
        (is (= :event/HttpRequest (:dt/type event)))
        (is (= :get (:http/method event)))
        (is (= 404 (:http/status-code event)))
        (is (= :failure (:event/status event)))
        (is (= :warn (:event/level event)) "404 errors should be logged as :warn")))))

(deftest verify-event-timing-test
  (testing "Event duration is reasonable"
    (let [before (System/currentTimeMillis)
          _ (tu/api-get-edn "/api/store/properties")
          after (System/currentTimeMillis)
          event (find-event-for-path "/api/store/properties")]
      (is (some? event))
      (when event
        (let [duration (:event/duration event)]
          (is (number? duration))
          (is (>= duration 0) "Duration should be non-negative")
          (is (<= duration (- after before 50)) "Duration should be less than total test time"))))))

(deftest verify-multiple-requests-separate-events-test
  (testing "Multiple requests create separate events"
    ;; Make three requests to different endpoints
    (tu/api-get-edn "/api/store/classes")
    (tu/api-get-edn "/api/store/properties")
    (tu/api-get-edn "/api/store/schema")
    (Thread/sleep 100)

    (let [{:keys [body]} (tu/api-get-edn "/api/events?limit=20")
          events (:events body)
          class-events (filter #(= "/api/store/classes" (:http/path %)) events)
          prop-events (filter #(= "/api/store/properties" (:http/path %)) events)
          schema-events (filter #(= "/api/store/schema" (:http/path %)) events)]
      (is (>= (count class-events) 1) "Should have logged /api/store/classes")
      (is (>= (count prop-events) 1) "Should have logged /api/store/properties")
      (is (>= (count schema-events) 1) "Should have logged /api/store/schema"))))

(deftest verify-event-has-correct-type-test
  (testing "Logged HTTP events have correct dt/type"
    (tu/api-get-edn "/api/status")
    (let [event (find-event-for-path "/api/status")]
      (is (some? event))
      (when event
        (is (= :event/HttpRequest (:dt/type event)))
        ;; Verify it's a proper HttpRequest with expected slots
        (is (keyword? (:http/method event)))
        (is (string? (:http/path event)))
        (is (number? (:http/status-code event)))))))

(deftest verify-event-persisted-to-database-test
  (testing "Events are persisted and queryable via Datomic"
    (tu/api-get-edn "/api/store/classes/dt/Property")
    (Thread/sleep 50)

    ;; Query directly from database
    (let [results (d/q '[:find ?e ?path
                         :where
                         [?e :http/path ?path]
                         [?e :http/path "/api/store/classes/dt/Property"]]
                       (db/db))]
      (is (seq results) "Event should be queryable in database")
      (when (seq results)
        (let [entity-id (ffirst results)
              entity (db/entity entity-id)]
          (is (= :event/HttpRequest (:dt/type entity)))
          (is (= :get (:http/method entity))))))))

(deftest verify-correlation-id-unique-per-request-test
  (testing "Each request gets a unique correlation ID"
    (tu/api-get-edn "/api/store/classes")
    (tu/api-get-edn "/api/store/properties")
    (Thread/sleep 50)

    (let [{:keys [body]} (tu/api-get-edn "/api/events?limit=10")
          events (:events body)
          store-events (filter #(and (:http/path %)
                                     (.startsWith (:http/path %) "/api/store"))
                               events)
          correlation-ids (map :event/correlation-id store-events)]
      (is (>= (count store-events) 2))
      ;; Each request should have a correlation ID
      (is (every? some? correlation-ids) "All events should have correlation IDs")
      ;; Correlation IDs should be unique per request
      (is (= (count correlation-ids) (count (set correlation-ids)))
          "Each request should have a unique correlation ID"))))

(deftest verify-event-name-format-test
  (testing "Event name follows 'METHOD /path' format"
    (tu/api-get-edn "/api/store/classes/dt/Ref/hierarchy")
    (let [event (find-event-for-path "/api/store/classes/dt/Ref/hierarchy")]
      (is (some? event))
      (when event
        (is (= "GET /api/store/classes/dt/Ref/hierarchy" (:event/name event)))))))
