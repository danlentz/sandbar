(ns sandbar.mcp.transport
  "Sandbar's MCP HTTP endpoints on the existing Pedestal service.

   POST /mcp accepts one JSON-RPC message and returns its response, or HTTP 204
   for a notification. GET /mcp/sse is a separate Sandbar notification stream.
   This implementation does not provide JSON-RPC batches, negotiated MCP
   sessions, or a standard Tasks surface. See doc/concepts/mcp-protocol.md
   for the supported client contract."
  (:require [cheshire.core              :as json]
            [clojure.core.async         :as async]
            [clojure.tools.logging      :as log]
            [io.pedestal.http.sse       :as sse]
            [sandbar.mcp.envelope       :as envelope]
            [sandbar.mcp.notifications  :as notifications]
            [sandbar.mcp.protocol       :as protocol]
            [sandbar.service.endpoint   :as endpoint :refer [defhandler]]
            [sandbar.util.http-status   :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MCP request handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler mcp-handler
  "Dispatch one JSON-RPC message from POST /mcp using the authenticated
   request identity. Return the method response or HTTP 204 with no body for
   a notification. Authentication is supplied by the route's interceptor
   chain; scope and method decisions are made by protocol dispatch."
  [request _ent-store data]
  (log/debug :MCP/inbound {:method (get data :method)
                            :id     (get data :id)})
  (let [response (protocol/dispatch data (:identity request))]
    (if response
      (endpoint/return http-status/success response)
      ;; Notification (no id, no expected response) — return 204 No Content
      (endpoint/return http-status/no-content nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SSE handler — server → client notifications channel (Stage C.3)
;;
;; Per ADR B.1.1 (Streamable HTTP optional SSE) + B.1.10 (notifications
;; for long-running operations + dynamic surface updates).
;;
;; Clients GET /mcp/sse; server keeps the connection open + writes
;; JSON-RPC notification events as they fire. Subscriber registration
;; lives in sandbar.mcp.notifications.

(defn- send-sse-event!
  "Write one JSON-RPC notification event to a Pedestal SSE channel.
   The notification is serialized as JSON in the `data:` field per the
   SSE spec; the event name defaults to 'message'.

   Per Pedestal 0.8.x SSE: put `{:name :data :id}` maps onto the
   event-channel; the framework handles SSE framing.  Returns the
   put-channel result (nil-able if the channel is closed)."
  [event-channel notification]
  (try
    (let [json-data (json/generate-string notification)]
      (async/put! event-channel {:name "message" :data json-data}))
    (catch Exception e
      (log/warn e :MCP/sse-send-failed)
      (throw e))))

(defn sse-stream-ready
  "Register an SSE send function and principal when Pedestal opens a stream.
   Pedestal owns the channel's read end; a competing consumer would steal
   notification events. Disconnect cleanup is lazy: a later send returning
   falsey or throwing causes the notification registry to evict the subscriber.
   Emit the Sandbar ready event with the newly registered subscriber id."
  [event-channel context]
  (let [identity-info (:identity context)
        send-fn       (fn [notification]
                        (send-sse-event! event-channel notification))
        sub-id        (notifications/register!
                        {:send!    send-fn
                         :identity identity-info})]
    (log/info :MCP/sse-opened
              {:subscriber-id sub-id
               :identity-eid  (:db/id identity-info)})

    ;; Send a no-op initial event to confirm the connection is live
    (send-sse-event! event-channel
                     (envelope/jsonrpc-notification
                       "notifications/sandbar/sse-ready"
                       {:subscriber-id sub-id}))))

(def sse-handler
  "GET /mcp/sse opens Sandbar's long-lived server-notification stream.
   Return Pedestal's SSE interceptor with `sse-stream-ready` as its setup
   callback. This separate endpoint does not establish an MCP session."
  (sse/start-event-stream sse-stream-ready))
