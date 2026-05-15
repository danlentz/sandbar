(ns sandbar.api.event
  "REST API for logging and querying events.

  ## Create Events
    POST /api/events                    - Create event (type in body)
    POST /api/events/server             - Create ServerEvent
    POST /api/events/user               - Create UserEvent
    POST /api/events/system             - Create SystemEvent
    POST /api/events/http               - Create HttpRequest event
    POST /api/events/api                - Create ApiCall event
    POST /api/events/transaction        - Create Transaction event

  ## Query Events
    GET /api/events                     - List recent events (with filters)
    GET /api/events/:id                 - Get event by database ID
    GET /api/events/correlation/:uuid   - Get events by correlation ID

  ## Filters (query params for GET /api/events)
    ?type=event/ServerEvent             - Filter by event type
    ?level=error                        - Filter by level (error, warn, info, debug, trace)
    ?since=2024-01-01T00:00:00Z         - Events after timestamp
    ?until=2024-01-02T00:00:00Z         - Events before timestamp
    ?limit=100                          - Max results (default 100)
    ?status=success|failure             - Filter by status

  ## Programmatic Logging
    Use sandbar.util.event for programmatic logging functions (log!, log-event!, etc.)
    and Pedestal interceptors (log-request, log-request-minimal)."
  (:require [clojure.instant          :as instant]
            [sandbar.entity-ref       :as eref]
            [datomic.api              :as d]
            [sandbar.db.datomic       :as db]
            [sandbar.db.datatype      :as dt]
            [sandbar.service.endpoint :as endpoint :refer [defhandler]]
            [sandbar.service.params   :as params :refer [defvalidator]]
            [sandbar.util.http-status :as http-status])
  (:import [java.util Date UUID]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- now [] (Date.))

(defn- str->uuid [s]
  (when s
    (try (UUID/fromString s)
         (catch Exception _ nil))))

(defn- str->instant [s]
  (when s
    (try (instant/read-instant-date s)
         (catch Exception _ nil))))

(defn- str->long [s]
  (when s
    (try (Long/parseLong s)
         (catch Exception _ nil))))

(defn- str->keyword [s]
  (when s
    (if (keyword? s) s (keyword s))))

(def ^:private event-type-map
  {"server"      :event/ServerEvent
   "user"        :event/UserEvent
   "system"      :event/SystemEvent
   "http"        :event/HttpRequest
   "api"         :event/ApiCall
   "transaction" :event/Transaction})

(defn- valid-event-type? [type-kw]
  (contains? (set (vals event-type-map)) type-kw))

(defn- entity->map
  "Convert a Datomic entity to a plain map for serialization."
  [e]
  (when e
    (into {:db/id (:db/id e)} (d/touch e))))

(defn- ensure-timestamp
  "Ensure event data has a timestamp, defaulting to now."
  [data]
  (if (:event/timestamp data)
    data
    (assoc data :event/timestamp (now))))

(defn- coerce-event-data
  "Coerce string values to appropriate types for event properties."
  [data]
  (cond-> data
    (string? (:event/correlation-id data))
    (update :event/correlation-id str->uuid)

    (string? (:event/timestamp data))
    (update :event/timestamp str->instant)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvalidator ::create-event [_] identity)
(defvalidator ::create-typed-event [_] identity)
(defvalidator ::list-events [_] identity)
(defvalidator ::get-event [_] identity)
(defvalidator ::get-by-correlation [_] identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Create Event Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler create-event
  "POST /api/events - Create an event with type specified in body.
   Body must include :dt/type (e.g., :event/ServerEvent)"
  [request _ data]
  (let [event-type (:dt/type data)]
    (cond
      (nil? event-type)
      (endpoint/bad-request {:error "Missing :dt/type in request body"
                             :valid-types (vals event-type-map)})

      (not (valid-event-type? event-type))
      (endpoint/bad-request {:error "Invalid event type"
                             :type event-type
                             :valid-types (vals event-type-map)})

      :else
      (try
        (let [event-data (-> data
                             (dissoc :dt/type)
                             coerce-event-data
                             ensure-timestamp)
              event (dt/make event-type event-data)]
          (endpoint/return http-status/created
                           {:created true
                            :type event-type
                            :event (entity->map event)}))
        (catch clojure.lang.ExceptionInfo e
          (endpoint/bad-request {:error "Validation failed"
                                 :details (ex-data e)}))))))

(defhandler create-server-event
  "POST /api/events/server - Create a ServerEvent"
  [request _ data]
  (try
    (let [event-data (-> data coerce-event-data ensure-timestamp)
          event (dt/make :event/ServerEvent event-data)]
      (endpoint/return http-status/created
                       {:created true
                        :type :event/ServerEvent
                        :event (entity->map event)}))
    (catch clojure.lang.ExceptionInfo e
      (endpoint/bad-request {:error "Validation failed"
                             :details (ex-data e)}))))

(defhandler create-user-event
  "POST /api/events/user - Create a UserEvent"
  [request _ data]
  (try
    (let [event-data (-> data coerce-event-data ensure-timestamp)
          event (dt/make :event/UserEvent event-data)]
      (endpoint/return http-status/created
                       {:created true
                        :type :event/UserEvent
                        :event (entity->map event)}))
    (catch clojure.lang.ExceptionInfo e
      (endpoint/bad-request {:error "Validation failed"
                             :details (ex-data e)}))))

(defhandler create-system-event
  "POST /api/events/system - Create a SystemEvent"
  [request _ data]
  (try
    (let [event-data (-> data coerce-event-data ensure-timestamp)
          event (dt/make :event/SystemEvent event-data)]
      (endpoint/return http-status/created
                       {:created true
                        :type :event/SystemEvent
                        :event (entity->map event)}))
    (catch clojure.lang.ExceptionInfo e
      (endpoint/bad-request {:error "Validation failed"
                             :details (ex-data e)}))))

(defhandler create-http-event
  "POST /api/events/http - Create an HttpRequest event"
  [request _ data]
  (try
    (let [event-data (-> data coerce-event-data ensure-timestamp)
          event (dt/make :event/HttpRequest event-data)]
      (endpoint/return http-status/created
                       {:created true
                        :type :event/HttpRequest
                        :event (entity->map event)}))
    (catch clojure.lang.ExceptionInfo e
      (endpoint/bad-request {:error "Validation failed"
                             :details (ex-data e)}))))

(defhandler create-api-event
  "POST /api/events/api - Create an ApiCall event"
  [request _ data]
  (try
    (let [event-data (-> data coerce-event-data ensure-timestamp)
          ;; Convert handler string to symbol if needed
          event-data (if (string? (:api/handler event-data))
                       (update event-data :api/handler symbol)
                       event-data)
          event (dt/make :event/ApiCall event-data)]
      (endpoint/return http-status/created
                       {:created true
                        :type :event/ApiCall
                        :event (entity->map event)}))
    (catch clojure.lang.ExceptionInfo e
      (endpoint/bad-request {:error "Validation failed"
                             :details (ex-data e)}))))

(defhandler create-transaction-event
  "POST /api/events/transaction - Create a Transaction event"
  [request _ data]
  (try
    (let [event-data (-> data coerce-event-data ensure-timestamp)
          event (dt/make :event/Transaction event-data)]
      (endpoint/return http-status/created
                       {:created true
                        :type :event/Transaction
                        :event (entity->map event)}))
    (catch clojure.lang.ExceptionInfo e
      (endpoint/bad-request {:error "Validation failed"
                             :details (ex-data e)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query Event Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-events
  "GET /api/events - List events with optional filters.
   Query params: type, level, since, until, limit, status"
  [request _ params]
  (let [;; Parse query params
        type-filter  (some-> (:type params) str->keyword)
        level-filter (some-> (:level params) str->keyword)
        status-filter (some-> (:status params) str->keyword)
        since        (some-> (:since params) str->instant)
        until        (some-> (:until params) str->instant)
        limit        (or (some-> (:limit params) str->long) 100)

        ;; Build query dynamically
        base-query   '[:find ?e ?ts
                       :in $ ?limit
                       :where
                       [?e :event/timestamp ?ts]
                       [?e :dt/type ?type]]

        ;; Add filters
        query-clauses (cond-> []
                        type-filter   (conj '[?e :dt/type ?type-filter])
                        level-filter  (conj '[?e :event/level ?level-filter])
                        status-filter (conj '[?e :event/status ?status-filter])
                        since         (conj '[(>= ?ts ?since)])
                        until         (conj '[(< ?ts ?until)]))

        ;; Build full query with filters
        full-query (if (empty? query-clauses)
                     base-query
                     (let [where-clauses (concat '[[?e :event/timestamp ?ts]
                                                   [?e :dt/type ?type]]
                                                 query-clauses)
                           in-clause (cond-> '[$ ?limit]
                                       type-filter   (conj '?type-filter)
                                       level-filter  (conj '?level-filter)
                                       status-filter (conj '?status-filter)
                                       since         (conj '?since)
                                       until         (conj '?until))]
                       (vec (concat [:find '?e '?ts :in] in-clause [:where] where-clauses))))

        ;; Build args
        query-args (cond-> [(db/db) limit]
                     type-filter   (conj type-filter)
                     level-filter  (conj level-filter)
                     status-filter (conj status-filter)
                     since         (conj since)
                     until         (conj until))

        ;; Execute query and sort by timestamp desc
        results (->> (apply d/q full-query query-args)
                     (sort-by second #(compare %2 %1))  ; desc by timestamp
                     (take limit)
                     (map first)
                     (map #(entity->map (db/entity %))))]

    {:count (count results)
     :limit limit
     :filters (cond-> {}
                type-filter   (assoc :type type-filter)
                level-filter  (assoc :level level-filter)
                status-filter (assoc :status status-filter)
                since         (assoc :since since)
                until         (assoc :until until))
     :events results}))

(defhandler get-event
  "GET /api/events/:id - Get event by database ID"
  [request _ {:keys [id]}]
  (let [entity-id (str->long id)]
    (if-not entity-id
      (endpoint/bad-request {:error "Invalid event ID" :id id})
      (let [{:keys [valid? entity]} (eref/validate entity-id)]
        (if valid?
          (if (:event/timestamp entity)  ; Verify it's actually an event
            {:event (entity->map entity)}
            (endpoint/not-found {:error "Not an event" :id entity-id}))
          (endpoint/not-found {:error "Event not found" :id entity-id}))))))

(defhandler get-by-correlation
  "GET /api/events/correlation/:uuid - Get all events with given correlation ID"
  [request _ {:keys [uuid]}]
  (let [correlation-id (str->uuid uuid)]
    (if-not correlation-id
      (endpoint/bad-request {:error "Invalid UUID" :uuid uuid})
      (let [results (d/q '[:find ?e ?ts
                           :in $ ?cid
                           :where
                           [?e :event/correlation-id ?cid]
                           [?e :event/timestamp ?ts]]
                         (db/db) correlation-id)
            events (->> results
                        (sort-by second)  ; asc by timestamp
                        (map first)
                        (map #(entity->map (db/entity %))))]
        {:correlation-id correlation-id
         :count (count events)
         :events events}))))

