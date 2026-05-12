(ns sandbar.mcp.transport
  "MCP transport layer — Streamable HTTP per
   decisions/sandbar_mcp_server_design_2026_05_12.md B.1.1.

   Slot routes into the existing Pedestal HTTP service rather than
   spawning a separate server process. POST `/mcp` accepts a JSON-RPC
   2.0 message; server responds with the corresponding response.

   Stage C.1 foundation:
   - Single POST `/mcp` endpoint accepting one JSON-RPC message
   - Response is the dispatched method's result (or error)

   Subsequent stages:
   - SSE channel at `/mcp/sse` for server-streaming responses + notifications
   - Batched message support (JSON-RPC allows array of messages)
   - Connection-level state (session identity for capability negotiation)"
  (:require [clojure.tools.logging      :as log]
            [io.pedestal.http.sse       :as sse]
            [clojure.core.async         :as async]
            [sandbar.mcp.notifications  :as notifications]
            [sandbar.mcp.protocol       :as protocol]
            [sandbar.service.endpoint   :as endpoint :refer [defhandler]]
            [sandbar.util.http-status   :as http-status])
  (:import [java.nio.charset StandardCharsets]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MCP request handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler mcp-handler
  "POST `/mcp` — accept a JSON-RPC 2.0 message; dispatch via
   sandbar.mcp.protocol/dispatch; return the response.

   Per B.1.1: Streamable HTTP returns either a JSON response (single
   message) or initiates an SSE stream (multi-message responses;
   Stage C.3). C.1 returns single-message JSON responses only."
  [request _ent-store data]
  (log/debug :MCP/inbound {:method (get data :method)
                            :id     (get data :id)})
  (let [response (protocol/dispatch data)]
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
   SSE spec; the event name defaults to 'message'."
  [event-channel notification]
  (try
    (let [json (pr-str notification)] ;; Stage C.3 uses EDN→string;
                                      ;; production Cheshire/Transit
                                      ;; serialization lands in C.3.1
      (sse/send-event event-channel "message" json))
    (catch Exception e
      (log/warn e :MCP/sse-send-failed)
      (throw e))))

(defn sse-stream-ready
  "Pedestal SSE setup callback. Called once when the SSE connection
   opens; receives the event-channel + Pedestal context. Registers the
   subscriber + arranges cleanup on disconnect."
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
                     (protocol/jsonrpc-notification
                       "notifications/sandbar/sse-ready"
                       {:subscriber-id sub-id}))

    ;; Watch the channel close; unregister on disconnect.
    (async/go
      (async/<! (async/timeout 1000)) ;; brief delay before take-loop
      (loop []
        (let [v (async/<! event-channel)]
          (when (nil? v) ;; channel closed
            (notifications/unregister! sub-id))
          (when v (recur)))))))

(def sse-handler
  "GET `/mcp/sse` — opens a Server-Sent Events channel for server →
   client notifications.

   Per ADR B.1.1: Streamable HTTP transport optionally upgrades to SSE
   for server-streaming responses + push notifications. Subscriber
   registry lives in sandbar.mcp.notifications.

   Returns a Pedestal SSE interceptor (NOT a defhandler — SSE needs
   long-lived response). The Pedestal SSE start-event-stream takes a
   ready-fn that gets a core.async channel; events sent via send-event!
   propagate to the client."
  (sse/start-event-stream sse-stream-ready))
