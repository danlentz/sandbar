(ns sandbar.mcp.notifications
  "Server-to-client JSON-RPC notifications and an in-process subscriber
   registry. Notifications carry no request id and expect no response.

   The SSE transport registers a send function and authenticated identity for
   each connection. `publish!` broadcasts; `publish-to!` targets selected ids.
   Failed sends evict subscribers, including a falsey return from a closed
   core.async channel. Disconnect cleanup is therefore lazy until delivery.

   Callers explicitly publish catalog changes, resource updates, or messages;
   this namespace does not observe database transactions itself. Resource
   delivery clearance is enforced by `sandbar.mcp.resources/entity-updated!`."
  (:require [clojure.tools.logging :as log]
            [sandbar.mcp.envelope  :as envelope]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriber registry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Each subscriber is a map:
;;   {:id        uuid                       — opaque session identifier
;;    :send!     (fn [event-map] ...)       — closure that writes one SSE
;;                                            event to the client
;;    :identity  principal (optional)       — authenticated principal at
;;                                            subscribe time
;;    :subscribed-at instant                — when the subscription opened}

(defonce ^:private +subscribers+
  (atom {}))

(defn register!
  "Register a new subscriber. Returns the subscriber id (uuid string).
   The `send!` fn is called with each event map; the transport layer
   (sandbar.mcp.transport) supplies a closure that writes to the client's
   SSE channel."
  [{:keys [send! identity]}]
  (let [id (str (java.util.UUID/randomUUID))]
    (swap! +subscribers+ assoc id
           {:id            id
            :send!         send!
            :identity      identity
            :subscribed-at (java.util.Date.)})
    (log/info :MCP/notification-subscribe
              {:id            id
               :identity-eid  (:db/id identity)
               :total-subscribers (count @+subscribers+)})
    id))

(defn unregister!
  "Unregister a subscriber by id. Idempotent."
  [id]
  (when id
    (swap! +subscribers+ dissoc id)
    (log/info :MCP/notification-unsubscribe
              {:id id :remaining-subscribers (count @+subscribers+)})))

(defn subscriber-count
  "Return current subscriber count. Useful for tests + diagnostics."
  []
  (count @+subscribers+))

(defn all-subscribers
  "Return a snapshot of current subscribers (for diagnostics + tests)."
  []
  @+subscribers+)

(defn clear-all!
  "Remove all subscribers. ONLY for tests; do NOT call in production."
  []
  (reset! +subscribers+ {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Publish (broadcast to all subscribers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- deliver-event!
  "Call a subscriber's send function; return true only for a truthy result.
   Both an exception and a falsey return cause a warning and eviction.
   Checking the return value is necessary because core.async put! returns
   false on a closed channel without throwing."
  [id send! event method]
  (try
    (let [result (send! event)]
      (if result
        true
        (do
          (log/warn :MCP/notification-send-failed
                    {:subscriber-id id
                     :method        method
                     :reason        :closed-channel})
          (unregister! id)
          false)))
    (catch Exception e
      (log/warn e :MCP/notification-send-failed
                {:subscriber-id id
                 :method        method
                 :reason        :exception})
      (unregister! id)
      false)))

(defn publish!
  "Broadcast a JSON-RPC notification to registered subscribers and return
   the count of truthy, non-throwing send results. Failed sends are logged and
   evicted by `deliver-event!`. This function does not perform per-resource
   clearance filtering; resource updates use `entity-updated!` and `publish-to!`."
  [method params]
  (let [event (envelope/jsonrpc-notification method params)]
    (log/debug :MCP/notification-publish
               {:method method :subscribers (subscriber-count)})
    (reduce-kv
      (fn [n id {:keys [send!]}]
        (if (deliver-event! id send! event method)
          (inc n)
          n))
      0
      @+subscribers+)))

(defn publish-to!
  "Send a JSON-RPC notification to the supplied subscriber-id collection.
   Unknown ids are skipped. Exceptions and falsey send results are logged and
   evicted, as in `publish!`. Return the count of successful send results."
  [subscriber-ids method params]
  (let [event (envelope/jsonrpc-notification method params)
        subs  @+subscribers+]
    (log/debug :MCP/notification-publish-to
               {:method method :subscriber-ids subscriber-ids})
    (reduce
      (fn [n id]
        (if-let [{:keys [send!]} (get subs id)]
          (if (deliver-event! id send! event method)
            (inc n)
            n)
          n))
      0
      subscriber-ids)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canonical MCP notification methods
;;
;; Convenience wrappers around publish! that emit the MCP-standard
;; notification methods per libraries/mcp_protocol.md §8.

(defn tools-list-changed!
  "Publish notifications/tools/list_changed so clients can refresh tools/list.
   This helper is invoked by callers; it does not monitor schema changes."
  []
  (publish! "notifications/tools/list_changed" {}))

(defn resources-list-changed!
  "Emit `notifications/resources/list_changed`."
  []
  (publish! "notifications/resources/list_changed" {}))

(defn resources-updated!
  "Emit `notifications/resources/updated` with the URI of the changed
   resource. Subscribed clients re-fetch via `resources/read`."
  [uri]
  (publish! "notifications/resources/updated" {:uri uri}))

(defn prompts-list-changed!
  "Emit `notifications/prompts/list_changed`."
  []
  (publish! "notifications/prompts/list_changed" {}))

(defn message!
  "Emit `notifications/message` (server log entry pushed to client).
   Level is :debug | :info | :warning | :error."
  [level data]
  (publish! "notifications/message" {:level (name level)
                                     :data  data
                                     :logger "sandbar"}))
