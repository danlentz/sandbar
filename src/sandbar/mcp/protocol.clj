(ns sandbar.mcp.protocol
  "MCP protocol methods over JSON-RPC 2.0.

   Declares server identity and capabilities, answers initialization, and
   dispatches methods to the tool, resource, prompt and task adapters after
   an operation-scope check. HTTP authentication and response transport live
   in `sandbar.mcp.auth` and `sandbar.mcp.transport`.

   Tools come from an implementation-owned operation catalog. Model classes
   and properties are discovered through those tools; adding a class does
   not add a tool. Compatibility task methods are dispatched here, but the
   server does not currently advertise the standard MCP Tasks capability."
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
  "Identity returned in `initialize`. The version identifies the server's
   client-facing release line; it is distinct from the MCP protocol version
   and does not establish that the corresponding library artifact is published."
  {:name    "sandbar"
   :title   "Sandbar"
   :version "0.2.0"})

(def protocol-version
  "Protocol version returned by initialization. Clients must check that they
   support this version; specific transport differences are documented in
   doc/guides/writing-an-mcp-client.md."
  "2025-11-25")

(def server-capabilities
  "Capabilities declared during initialization: operation tools, resources
   with subscriptions, prompts, change notifications, and logging. These
   advertise protocol facilities; they do not grant permission to an operation
   or entity, nor imply that new model classes create new tools."
  {:tools     {:listChanged true}
   :resources {:subscribe   true
               :listChanged true}
   :prompts   {:listChanged true}
   :logging   {}})

(def server-instructions
  "Brief orientation returned in InitializeResult.instructions. Points clients
   to common retrieval operations and catalog discovery without duplicating
   the full tool list."
  (str "Sandbar is a typed-edge knowledge substrate (Datomic-backed) served over "
       "MCP.  Prefer its typed verbs over raw text scanning: sandbar.search.bm25f "
       "for content-relevance retrieval; sandbar.entity.find / sandbar.class.instances "
       "for known entities and classes; sandbar.navigate.* for typed-edge traversal; "
       "sandbar.aggregate.* for counts, group-by, and rankings.  Use "
       "sandbar.tools.search + sandbar.tools.describe to discover and inspect any "
       "verb before calling it."))

;; Envelope helpers are a leaf namespace shared with notifications, avoiding
;; a protocol → notifications dependency cycle.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle method — initialize
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-initialize
  "Return a JSON-RPC initialization result with `protocol-version`, server
   identity, capabilities and orientation. The supplied client version is
   logged; this handler always returns the server version. A client must
   decide whether it supports that result before sending
   `notifications/initialized` and continuing."
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
      :serverInfo      server-info
      :instructions    server-instructions})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Method dispatch table
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Each method name maps to a handler fn of [id params principal] -> response-map.
;; Notifications (no id) are dispatched but their responses are discarded
;; per JSON-RPC notification semantics.

(def method-handlers
  "Map method names to handlers of `[id params principal] -> response`.
   `dispatch` applies method-scope authorization before invocation. Tool calls
   receive the principal for their operation gate; resource listing, reading
   and subscription also receive it for entity visibility checks. Notifications
   have no response. Compatibility task methods do not imply advertised
   standard Tasks support."
  {"initialize"                  (fn [id params _] (handle-initialize id params))
   "notifications/initialized"   (fn [_ _ _] nil) ;; client confirms ready; no response
   "tools/list"                  (fn [id params _] (tools/handle-list id params))
   "tools/call"                  (fn [id params principal] (tools/handle-call id params principal))
   "resources/list"              (fn [id params principal] (resources/handle-list id params principal))
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

   `principal` is the authenticated MCP principal. The one-argument overload
   uses nil, the trusted in-process path with unrestricted operation scope;
   remote adapters must supply their authenticated principal.

   A method-scope refusal returns the authorization error envelope before the
   handler runs. Tool calls have an additional verb-level check in their
   handler. Unknown methods return `method-not-found`; malformed envelopes
   return `invalid-request`; uncaught handler exceptions become
   `internal-error`. Individual handlers may return more specific envelopes."
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
         ;; Run the pure scope decision before the handler.
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
