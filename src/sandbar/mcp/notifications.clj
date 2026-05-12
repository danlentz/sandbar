(ns sandbar.mcp.notifications
  "MCP notifications channel — server → client push messages without
   request/response correlation (JSON-RPC 2.0 notification semantics:
   no `:id` field; no response expected).

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4 + B.1.5:
   - `notifications/tools/list_changed` — emitted when new `:dt/Class`
     instances land via `dt/make` (Phase 2 schema evolution)
   - `notifications/resources/updated` — emitted when subscribed entities
     mutate
   - `notifications/resources/list_changed` — emitted when the resource
     catalog changes
   - `notifications/message` — server log message pushed to client

   Pure-data subscriber registry: tracks active SSE channels; `publish!`
   broadcasts to all subscribers. The wire-level transport
   (sandbar.mcp.transport/sse-handler) registers channels here when
   clients connect to `GET /mcp/sse` and unregisters them on disconnect.

   Per the layer-targeting discipline
   (interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md):
   this module does NOT directly observe Datomic. Future hooks that
   listen to `d/tx-report-queue` and forward as `resources/updated`
   notifications will live in a separate `sandbar.mcp.tx-listener`
   namespace (Stage C.5+); for now `publish!` is invoked explicitly by
   callers that know an entity changed."
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

(defn publish!
  "Broadcast a JSON-RPC notification to all registered subscribers.
   Failures (e.g., a subscriber's send! throws because the client
   disconnected) are caught + the offending subscriber is unregistered.

   Returns the count of subscribers that received the notification
   successfully."
  [method params]
  (let [event (envelope/jsonrpc-notification method params)]
    (log/debug :MCP/notification-publish
               {:method method :subscribers (subscriber-count)})
    (reduce-kv
      (fn [n id {:keys [send!]}]
        (try
          (send! event)
          (inc n)
          (catch Exception e
            (log/warn e :MCP/notification-send-failed
                      {:subscriber-id id :method method})
            (unregister! id)
            n)))
      0
      @+subscribers+)))

(defn publish-to!
  "Send a JSON-RPC notification to a specific set of subscriber-ids only.
   Per-URI / per-subscription routing per F-S-001 resolution; the broadcast
   shape of `publish!` is the fallback for legacy `::broadcast`-bound
   subscriptions.

   subscriber-ids — collection of subscriber-id strings registered via
                    `register!`.  Unknown ids are silently skipped.
   Returns the count of subscribers that received the notification."
  [subscriber-ids method params]
  (let [event (envelope/jsonrpc-notification method params)
        subs  @+subscribers+]
    (log/debug :MCP/notification-publish-to
               {:method method :subscriber-ids subscriber-ids})
    (reduce
      (fn [n id]
        (if-let [{:keys [send!]} (get subs id)]
          (try
            (send! event)
            (inc n)
            (catch Exception e
              (log/warn e :MCP/notification-send-failed
                        {:subscriber-id id :method method})
              (unregister! id)
              n))
          n))
      0
      subscriber-ids)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canonical MCP notification methods
;;
;; Convenience wrappers around publish! that emit the MCP-standard
;; notification methods per libraries/mcp_protocol.md §8.

(defn tools-list-changed!
  "Per the MCP spec: emit `notifications/tools/list_changed` so clients
   re-fetch tools/list. Fires when new `:dt/Class` instances land via
   `dt/make` (Phase 2 schema evolution; e.g., a new mm/* class)."
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
