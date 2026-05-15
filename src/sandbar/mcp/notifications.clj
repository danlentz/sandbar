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

(defn- deliver-event!
  "Internal: call a subscriber's `send!` with `event`, returning true on
   successful delivery + false on any failure shape.  Two failure modes
   are recognized + treated identically (evict + warn):

   1. **Exception path** — `send!` throws (e.g., transport encountered an
      I/O error the underlying machinery surfaces as an exception).
   2. **Falsey-return path** — `send!` returns false / nil.  This is the
      core.async `async/put!` semantics: put! to a CLOSED channel returns
      false WITHOUT throwing.  Per ultrareview UR-13 + observation
      `sandbar_sse_subscriber_async_put_closed_channel_silent_drop_2026_05_14`:
      treating exceptions as the only failure signal silently drops
      notifications to disconnected clients + leaks subscriber-registry
      entries across connect-disconnect cycles.

   On failure: log warn with `:reason` (`:exception` or `:closed-channel`)
   + `unregister!` the subscriber.  Returns true iff `send!` returned a
   truthy value AND did not throw."
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
  "Broadcast a JSON-RPC notification to all registered subscribers.
   Failures are detected via two mechanisms (see `deliver-event!`):

   1. `send!` throws (caught + subscriber unregistered)
   2. `send!` returns falsey (closed core.async channel — `async/put!`
      returns false to a closed channel WITHOUT throwing; subscriber
      unregistered, log warn emitted)

   Per ultrareview UR-13: ignoring the falsey return value silently
   drops notifications + leaks subscriber-registry entries across SSE
   client connect-disconnect cycles.

   Returns the count of subscribers that received the notification
   successfully."
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
  "Send a JSON-RPC notification to a specific set of subscriber-ids only.
   Per-URI / per-subscription routing per F-S-001 resolution; the broadcast
   shape of `publish!` is the fallback for legacy `::broadcast`-bound
   subscriptions.

   Failure detection matches `publish!`: both exception + falsey-return
   from `send!` trigger eviction + a warn log (closed core.async channels
   manifest as the falsey-return path — see `deliver-event!` docstring +
   ultrareview UR-13).

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
