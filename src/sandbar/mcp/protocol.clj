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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON-RPC 2.0 envelope shapes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn jsonrpc-result
  "Construct a JSON-RPC 2.0 success response."
  [id result]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn jsonrpc-error
  "Construct a JSON-RPC 2.0 error response.
   Standard codes per https://www.jsonrpc.org/specification#error_object:
   -32700 Parse error
   -32600 Invalid Request
   -32601 Method not found
   -32602 Invalid params
   -32603 Internal error
   -32000 to -32099 Server error (implementation-defined)"
  [id code message & [data]]
  {:jsonrpc "2.0"
   :id      id
   :error   (cond-> {:code    code
                     :message message}
              data (assoc :data data))})

(defn jsonrpc-notification
  "Construct a JSON-RPC 2.0 notification (no id; server → client push)."
  [method params]
  {:jsonrpc "2.0"
   :method  method
   :params  params})

(defn valid-envelope?
  "Quick structural validation of an inbound JSON-RPC 2.0 message.
   Requires `:jsonrpc` field equal to '2.0' and either `:method` (request
   or notification) or `:result`/`:error` (response). Returns boolean."
  [msg]
  (and (map? msg)
       (= "2.0" (:jsonrpc msg))
       (or (contains? msg :method)
           (contains? msg :result)
           (contains? msg :error))))

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
    (jsonrpc-result
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
  "Stage C.1 dispatch table. Subsequent stages register more handlers
   (resources/list, resources/read, prompts/list, prompts/get, tasks/get)."
  {"initialize"                  handle-initialize
   "notifications/initialized"   (fn [_ _] nil) ;; client confirms ready; no response
   "tools/list"                  (fn [id params] (tools/handle-list id params))
   "tools/call"                  (fn [id params] (tools/handle-call id params))})

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
      (not (valid-envelope? msg))
      (jsonrpc-error nil -32600 "Invalid Request" {:received msg})

      (nil? method)
      (jsonrpc-error id -32600 "Invalid Request — method missing")

      :else
      (if-let [handler (get method-handlers method)]
        (try
          (handler id params)
          (catch Exception e
            (log/error e :MCP/dispatch-error
                       {:method method :id id})
            (jsonrpc-error id -32603 "Internal error"
                           {:exception-message (.getMessage e)})))
        (jsonrpc-error id -32601 (str "Method not found: " method))))))
