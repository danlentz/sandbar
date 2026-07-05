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
            [sandbar.mcp.authz     :as authz]
            [sandbar.mcp.envelope  :as envelope]
            [sandbar.mcp.prompts   :as prompts]
            [sandbar.mcp.resources :as resources]
            [sandbar.mcp.tasks     :as tasks]
            [sandbar.mcp.tools     :as tools]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server identity + protocol version
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def server-info
  "Identity returned to clients in the `initialize` response.
   Version aligns with Sandbar's project version (project.clj)."
  {:name    "sandbar"
   :title   "Sandbar"
   :version "0.1.0"})

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
   tasks/*.

   Every handler is `(fn [id params principal] -> response)`.  The dispatch
   gate (`authz/method-scope-decision`, run in `dispatch` before the handler)
   covers the principal-SCOPE axis for every method uniformly, so most rows
   accept and ignore the principal (`_`).  The rows that ALSO need the
   principal INSIDE the handler consume it explicitly: `tools/call` (the
   read-only verb-class gate, Shape A′) and — per S5 charter item 3 — the
   compartment-aware `resources/read` + `resources/subscribe` handlers, which
   thread the principal to their EP-N3/EP-N1 compartment checks (inert until S6
   mints the visibility/clearance slots)."
  {"initialize"                  (fn [id params _] (handle-initialize id params))
   "notifications/initialized"   (fn [_ _ _] nil) ;; client confirms ready; no response
   "tools/list"                  (fn [id params _] (tools/handle-list id params))
   "tools/call"                  (fn [id params principal] (tools/handle-call id params principal))
   "resources/list"              (fn [id params _] (resources/handle-list id params))
   "resources/read"              (fn [id params principal] (resources/handle-read id params principal))
   "resources/subscribe"         (fn [id params principal] (resources/handle-subscribe id params principal))
   "resources/unsubscribe"       (fn [id params _] (resources/handle-unsubscribe id params))
   "prompts/list"                (fn [id params _] (prompts/handle-list id params))
   "prompts/get"                 (fn [id params _] (prompts/handle-get id params))
   "tasks/list"                  (fn [id params _] (tasks/handle-list id params))
   "tasks/get"                   (fn [id params _] (tasks/handle-get id params))
   "tasks/cancel"                (fn [id params _] (tasks/handle-cancel id params))})

(defn dispatch
  "Dispatch a single JSON-RPC message. Returns a response map (or nil for
   pure-notification messages with no response expected).

   `principal` is the authenticated MCP principal (or nil on the
   legacy/local path); it is threaded to the method handler so `tools/call`
   can authorize the verb under the read-only token gate.  The 1-arity
   overload dispatches with no principal (full access), preserving the
   pre-gate call contract.

   S5 dispatch-layer gate (principal-check-at-dispatch, S5-PLAN §2.2 item 4 /
   §1.4 Shape A′): for every dispatchable method we compute the principal's
   scope descriptor and run the pure `authz/method-scope-decision` BEFORE the
   handler is invoked.  On a deny we return the JSON-RPC deny envelope (or nil
   for a notification, which expects no response); on allow we proceed to the
   handler unchanged.  The gate is ADDITIVE — it does NOT duplicate the
   `tools/call` read-only verb-class check, which stays byte-identical inside
   `tools/handle-call` (Shape A′).  This is the axis that did not exist before:
   fail-closed unscoped-deny (AP-2) + family policy for the non-tools methods.

   Error handling per JSON-RPC spec (codes via `sandbar.util.jsonrpc-status`):
   - Unknown method → `method-not-found`
   - Denied by scope → `invalid-params` deny envelope (via authz; AP-2 + family)
   - Invalid params → `invalid-params` (handler may raise; we catch + map)
   - Handler exception → `internal-error`"
  ([msg] (dispatch msg nil))
  ([msg principal]
   (let [{:keys [id method params]} msg]
     (cond
       (not (envelope/valid-envelope? msg))
       (envelope/jsonrpc-error nil jsonrpc-status/invalid-request "Invalid Request" {:received msg})

       (nil? method)
       (envelope/jsonrpc-error id jsonrpc-status/invalid-request "Invalid Request — method missing")

       :else
       (if-let [handler (get method-handlers method)]
         ;; S5 dispatch gate — run the pure scope decision before the handler.
         ;; On deny, short-circuit with the deny envelope (nil for a
         ;; notification); on allow, invoke the handler as before.
         (let [scope    (authz/principal->scope principal)
               decision (authz/method-scope-decision method scope)]
           (if (authz/deny? decision)
             (authz/deny->jsonrpc-error id method decision scope)
             (try
               (handler id params principal)
               (catch Exception e
                 (log/error e :MCP/dispatch-error
                            {:method method :id id})
                 (envelope/jsonrpc-error id jsonrpc-status/internal-error "Internal error"
                                         {:exception-message (.getMessage e)})))))
         (envelope/jsonrpc-error id jsonrpc-status/method-not-found
                                 (str "Method not found: " method)))))))
