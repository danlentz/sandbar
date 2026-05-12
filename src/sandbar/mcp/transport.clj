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
  (:require [clojure.tools.logging :as log]
            [sandbar.mcp.protocol  :as protocol]
            [sandbar.service.endpoint :as endpoint :refer [defhandler]]
            [sandbar.util.http-status :as http-status]))

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
