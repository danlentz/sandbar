(ns sandbar.util.event
  "Core event logging utilities.

   This namespace provides the foundational event logging functions and
   Pedestal interceptors for automatic request logging. Use this for
   programmatic event logging throughout the application.

   ## Quick Start

     (require '[sandbar.util.event :as event])

     ;; Simple logging
     (event/log! :info \"Something happened\")
     (event/log! :error \"Failed\" {:event/status :failure})

     ;; Typed events
     (event/log-event! :event/HttpRequest {...})
     (event/log-http! {:http/method :get :http/path \"/api/foo\"})
     (event/log-error! \"Operation failed\" ex)

   ## Interceptors

     event/log-request          - Logs all HTTP requests
     event/log-request-minimal  - Logs only errors and slow requests
     event/suppress-event-logging - Suppresses logging for specific routes"
  (:require [clojure.tools.logging   :as log]
            [io.pedestal.interceptor :as interceptor]
            [sandbar.db.datatype     :as dt])
  (:import [java.util Date UUID]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- now [] (Date.))

(defn- str->keyword [s]
  (when s
    (if (keyword? s) s (keyword s))))

(defn- coerce-event-data
  "Coerce string values to appropriate types for event properties."
  [data]
  (cond-> data
    (string? (:event/correlation-id data))
    (update :event/correlation-id #(try (UUID/fromString %) (catch Exception _ nil)))

    (string? (:event/kind data))
    (update :event/kind str->keyword)

    (string? (:event/level data))
    (update :event/level str->keyword)

    (string? (:event/status data))
    (update :event/status str->keyword)

    (string? (:http/method data))
    (update :http/method str->keyword)

    (and (contains? data :event/tags) (not (set? (:event/tags data))))
    (update :event/tags #(if (coll? %) (set (map str->keyword %)) #{(str->keyword %)}))))

(defn- ensure-timestamp
  "Ensure event data has a timestamp, defaulting to now."
  [data]
  (if (:event/timestamp data)
    data
    (assoc data :event/timestamp (now))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Core Logging Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn log-event!
  "Log a typed event to the database. Returns the created event entity.

   Usage:
     (log-event! :event/ServerEvent {:event/name \"Error occurred\"
                                     :event/level :error
                                     :event/namespace \"my.ns\"})"
  [event-type data]
  (let [data (-> data coerce-event-data ensure-timestamp)]
    (dt/make event-type data)))

(defn log!
  "Log a ServerEvent to the database. Returns the created event entity.

   Usage:
     (log! :info \"Something happened\")
     (log! :error \"Failed\" {:event/exception \"NullPointer\"})"
  ([level message]
   (log-event! :event/ServerEvent
               {:event/name message
                :event/level level
                :event/namespace (str *ns*)}))
  ([level message data]
   (log-event! :event/ServerEvent
               (merge {:event/name message
                       :event/level level
                       :event/namespace (str *ns*)}
                      data))))

(defn log-http!
  "Log an HTTP request event.

   Usage:
     (log-http! {:http/method :get
                 :http/path \"/api/foo\"
                 :http/status-code 200
                 :event/duration 42})"
  [data]
  (log-event! :event/HttpRequest
              (merge {:event/level :info
                      :event/namespace "sandbar.server.pedestal"}
                     data)))

(defn log-error!
  "Log an error event with exception details.

   Usage:
     (log-error! \"Operation failed\")
     (log-error! \"Operation failed\" ex)"
  ([message]
   (log! :error message))
  ([message ex]
   (log-event! :event/ServerEvent
               {:event/name message
                :event/level :error
                :event/status :failure
                :event/namespace (str *ns*)
                :event/exception (str (type ex) ": " (.getMessage ex))
                :event/stacktrace (with-out-str (.printStackTrace ex))})))

(defn log-api!
  "Log an API call event.

   Usage:
     (log-api! {:api/endpoint :store/get-class
                :api/handler 'sandbar.api.store/get-class
                :event/duration 15})"
  [data]
  (log-event! :event/ApiCall
              (merge {:event/level :debug
                      :event/namespace (str *ns*)}
                     data)))

(defn log-transaction!
  "Log a database transaction event.

   Usage:
     (log-transaction! {:tx/id 12345
                        :tx/datom-count 10
                        :tx/entities-affected 3})"
  [data]
  (log-event! :event/Transaction
              (merge {:event/level :trace
                      :event/namespace "sandbar.db.datomic"}
                     data)))

(defn log-user!
  "Log a user event.

   Usage:
     (log-user! {:event/name \"User login\"
                 :event/kind :user/login
                 :event/actor user-entity-id})"
  [data]
  (log-event! :event/UserEvent data))

(defn log-system!
  "Log a system event.

   Usage:
     (log-system! {:event/name \"Server startup\"
                   :event/kind :system/startup
                   :event/status :success})"
  [data]
  (log-event! :event/SystemEvent data))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pedestal Interceptor for Request Logging
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- request->event-data
  "Extract event data from a Pedestal request/response context."
  [{:keys [request response route] :as context}]
  (let [start-time  (:event/start-time context)
        end-time    (System/currentTimeMillis)
        duration    (when start-time (- end-time start-time))
        status-code (:status response)
        level       (cond
                      (nil? status-code)      :error
                      (< status-code 400)     :info
                      (< status-code 500)     :warn
                      :else                   :error)
        status      (if (and status-code (< status-code 400))
                      :success
                      :failure)]
    (cond-> {:event/timestamp     (Date.)
             :event/name          (str (-> request :request-method name .toUpperCase)
                                       " " (:uri request))
             :event/kind          :http/request
             :event/level         level
             :event/namespace     (some-> route :route-name namespace)
             :event/status        status
             :http/method         (:request-method request)
             :http/path           (:uri request)}

      ;; Optional fields
      duration
      (assoc :event/duration duration)

      (:query-string request)
      (assoc :http/query-string (:query-string request))

      status-code
      (assoc :http/status-code status-code)

      (:content-type request)
      (assoc :http/content-type (:content-type request))

      (:remote-addr request)
      (assoc :http/remote-addr (:remote-addr request))

      (get-in request [:headers "user-agent"])
      (assoc :http/user-agent (get-in request [:headers "user-agent"]))

      (get-in request [:headers "content-length"])
      (assoc :http/request-size (try (Long/parseLong (get-in request [:headers "content-length"]))
                                     (catch Exception _ nil)))

      (:event/correlation-id context)
      (assoc :event/correlation-id (:event/correlation-id context)))))

(defn should-log?
  "Determine if this request should be logged.
   Skip logging for the events API itself to prevent infinite loops."
  [{:keys [request] :as context}]
  (let [uri (:uri request)]
    (and (not (:suppress-event-logging? context))
         (not (and uri (.startsWith uri "/api/events"))))))

(def log-request
  "Pedestal interceptor that logs HTTP requests to the event database.

   On enter: Records the start time and generates correlation ID.
   On leave: Creates an HttpRequest event with request/response details.

   Usage in routes:
     [[\"/\" ^:interceptors [event/log-request ...]
       ...]]

   To suppress logging for specific routes, set :suppress-event-logging? true
   in the context."
  (interceptor/interceptor
    {:name  ::log-request
     :enter (fn [context]
              ;; Record start time and optionally generate correlation ID
              (let [correlation-id (or (:event/correlation-id context)
                                       (UUID/randomUUID))]
                (assoc context
                       :event/start-time (System/currentTimeMillis)
                       :event/correlation-id correlation-id)))
     :leave (fn [context]
              ;; Log the request/response
              (when (should-log? context)
                (try
                  (let [event-data (request->event-data context)]
                    (log-event! :event/HttpRequest event-data))
                  (catch Exception e
                    (log/warn e "Failed to log HTTP request event"))))
              context)
     :error (fn [context ex]
              ;; Log errors
              (when (should-log? context)
                (try
                  (let [event-data (-> (request->event-data context)
                                       (assoc :event/level :error
                                              :event/status :failure
                                              :event/exception (str (type ex) ": " (.getMessage ex))
                                              :event/stacktrace (with-out-str (.printStackTrace ex))))]
                    (log-event! :event/HttpRequest event-data))
                  (catch Exception e
                    (log/warn e "Failed to log HTTP error event"))))
              (assoc context :io.pedestal.interceptor.chain/error ex))}))

(def log-request-minimal
  "Lightweight version of log-request that only logs errors and slow requests.

   - Logs requests that result in 5xx errors
   - Logs requests that take longer than 1000ms
   - Skips logging for normal successful requests

   Useful for high-traffic endpoints where full logging would be too expensive."
  (let [slow-threshold-ms 1000]
    (interceptor/interceptor
      {:name  ::log-request-minimal
       :enter (fn [context]
                (assoc context :event/start-time (System/currentTimeMillis)))
       :leave (fn [context]
                (when (should-log? context)
                  (let [start-time  (:event/start-time context)
                        duration    (when start-time (- (System/currentTimeMillis) start-time))
                        status-code (get-in context [:response :status])
                        is-error?   (and status-code (>= status-code 500))
                        is-slow?    (and duration (> duration slow-threshold-ms))]
                    (when (or is-error? is-slow?)
                      (try
                        (let [event-data (-> (request->event-data context)
                                             (assoc :event/tags (cond-> #{}
                                                                  is-error? (conj :error)
                                                                  is-slow?  (conj :slow))))]
                          (log-event! :event/HttpRequest event-data))
                        (catch Exception e
                          (log/warn e "Failed to log HTTP request event"))))))
                context)
       :error (fn [context ex]
                (when (should-log? context)
                  (try
                    (let [event-data (-> (request->event-data context)
                                         (assoc :event/level :error
                                                :event/status :failure
                                                :event/exception (str (type ex) ": " (.getMessage ex))
                                                :event/tags #{:error :exception}))]
                      (log-event! :event/HttpRequest event-data))
                    (catch Exception e
                      (log/warn e "Failed to log HTTP error event"))))
                (assoc context :io.pedestal.interceptor.chain/error ex))})))

(def suppress-event-logging
  "Interceptor that suppresses event logging for specific routes.

   Usage:
     [[\"/health\" {:get health-check} ^:interceptors [event/suppress-event-logging]]]"
  (interceptor/interceptor
    {:name  ::suppress-event-logging
     :enter (fn [context]
              (assoc context :suppress-event-logging? true))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Correlation ID Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *correlation-id*
  "Dynamic var for the current correlation ID.
   Bind this in your code to correlate events across function calls."
  nil)

(defmacro with-correlation
  "Execute body with a correlation ID bound.
   All events logged within the body will share this correlation ID.

   Usage:
     (with-correlation (UUID/randomUUID)
       (log! :info \"Step 1\")
       (do-something)
       (log! :info \"Step 2\"))"
  [correlation-id & body]
  `(binding [*correlation-id* ~correlation-id]
     ~@body))

(defn log-with-correlation!
  "Log an event with the current correlation ID (if bound).

   Usage:
     (with-correlation my-uuid
       (log-with-correlation! :info \"Correlated event\"))"
  ([level message]
   (log! level message (when *correlation-id*
                         {:event/correlation-id *correlation-id*})))
  ([level message data]
   (log! level message (if *correlation-id*
                         (assoc data :event/correlation-id *correlation-id*)
                         data))))
