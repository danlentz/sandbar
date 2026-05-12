(ns sandbar.mcp.protocol
  "MCP (Model Context Protocol) protocol layer — JSON-RPC 2.0 envelope
   handling + lifecycle methods (`initialize`) + method dispatch.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.1-B.1.10
   (Stage B design ADR of the Sandbar-as-MCP-Server arc per
   plans/sandbar_as_mcp_server_arc_2026-05-12.md).

   Discipline: tool implementations call `dt/*` introspection + `util/*`
   + `service/*` abstraction layers — NEVER raw `datomic.api` — per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md.

   Stage C.1 foundation:
   - JSON-RPC 2.0 envelope decode/encode
   - `initialize` handshake (capabilities negotiation)
   - Method dispatch table delegating to sub-namespaces
   - Error response shaping per JSON-RPC spec

   Subsequent stages:
   - C.2 Bearer-token Pedestal interceptor (sandbar.mcp.auth)
   - C.3 Notifications channel (sandbar.mcp.notifications)
   - C.4 Resources + Prompts + Tasks support"
  (:require [clojure.tools.logging :as log]
            [sandbar.mcp.envelope  :as envelope]
            [sandbar.mcp.prompts   :as prompts]
            [sandbar.mcp.resources :as resources]
            [sandbar.mcp.tasks     :as tasks]
            [sandbar.mcp.tools     :as tools]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server identity + protocol version
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server-info
  "Identity returned to clients in the `initialize` response.
   Version aligns with Sandbar's project version."
  {:name    "sandbar"
   :title   "Sandbar"
   :version "0.0.1-SNAPSHOT"})

(def protocol-version
  "MCP protocol version this server speaks.
   Per libraries/mcp_protocol.md §1 — current spec version is 2025-11-25;
   this server claims compatibility with that."
  "2025-11-25")

(def server-capabilities
  "Capabilities declared during initialize handshake.
   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4 + B.1.5 + B.1.6:
   - tools — bootstrap-by-discovery from dt/all-classes; supports listChanged
     notifications (when new dt/Class instances land)
   - resources — every Sandbar entity addressable; supports subscribe +
     listChanged
   - prompts — workflow templates as prompts; supports listChanged"
  {:tools     {:listChanged true}
   :resources {:subscribe   true
               :listChanged true}
   :prompts   {:listChanged true}
   :logging   {}})

;; JSON-RPC 2.0 envelope shapes live in `sandbar.mcp.envelope` — extracted
;; to a leaf namespace to break the protocol → notifications cycle per the
;; F-M-001 resolution in
;; audit-results/codex_sandbar_as_mcp_server_2026_05_12.md.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle method — initialize
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-initialize
  "Respond to the MCP `initialize` request per
   libraries/mcp_protocol.md §4. Negotiates protocol version + declares
   server capabilities. Client follows up with `notifications/initialized`
   to indicate readiness.

   Per spec: if mutual protocol version is not negotiable the connection
   SHOULD be terminated. Stage C.1 accepts any client version + returns
   our protocol-version; downstream tightening lands when we observe
   real client mismatches."
  [id params]
  (let [client-version (:protocolVersion params)
        client-info    (:clientInfo params)]
    (log/info :MCP/initialize
              {:client-version   client-version
               :client-info      client-info
               :server-version   protocol-version
               :server-capabilities (keys server-capabilities)})
    (envelope/jsonrpc-result
     id
     {:protocolVersion protocol-version
      :capabilities    server-capabilities
      :serverInfo      server-info})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Method dispatch table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Each method name maps to a handler fn of [id params] -> response-map.
;; Notifications (no id) are dispatched but their responses are discarded
;; per JSON-RPC notification semantics.

(def method-handlers
  "Method dispatch table. Stages C.1+C.4 added initialize + tools/*;
   Stage C.5 adds resources/*; subsequent stages add prompts/* +
   tasks/*."
  {"initialize"                  handle-initialize
   "notifications/initialized"   (fn [_ _] nil) ;; client confirms ready; no response
   "tools/list"                  (fn [id params] (tools/handle-list id params))
   "tools/call"                  (fn [id params] (tools/handle-call id params))
   "resources/list"              (fn [id params] (resources/handle-list id params))
   "resources/read"              (fn [id params] (resources/handle-read id params))
   "resources/subscribe"         (fn [id params] (resources/handle-subscribe id params))
   "resources/unsubscribe"       (fn [id params] (resources/handle-unsubscribe id params))
   "prompts/list"                (fn [id params] (prompts/handle-list id params))
   "prompts/get"                 (fn [id params] (prompts/handle-get id params))
   "tasks/get"                   (fn [id params] (tasks/handle-get id params))
   "tasks/cancel"                (fn [id params] (tasks/handle-cancel id params))})

(defn dispatch
  "Dispatch a single JSON-RPC message. Returns a response map (or nil for
   pure-notification messages with no response expected).

   Error handling per JSON-RPC spec:
   - Unknown method → -32601 Method not found
   - Invalid params → -32602 Invalid params (handler may raise; we catch + map)
   - Handler exception → -32603 Internal error"
  [msg]
  (let [{:keys [id method params]} msg]
    (cond
      (not (envelope/valid-envelope? msg))
      (envelope/jsonrpc-error nil -32600 "Invalid Request" {:received msg})

      (nil? method)
      (envelope/jsonrpc-error id -32600 "Invalid Request — method missing")

      :else
      (if-let [handler (get method-handlers method)]
        (try
          (handler id params)
          (catch Exception e
            (log/error e :MCP/dispatch-error
                       {:method method :id id})
            (envelope/jsonrpc-error id -32603 "Internal error"
                                    {:exception-message (.getMessage e)})))
        (envelope/jsonrpc-error id -32601 (str "Method not found: " method))))))
