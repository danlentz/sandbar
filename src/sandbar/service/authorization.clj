(ns sandbar.service.authorization
  "Authorize protected REST requests using the same principal-scope
   decision as MCP: authz/principal->scope and authz/family-scope-decision.

   Auth session self-service routes are :exempt and retain their own handler
   checks; GET is :read; other HTTP methods are :mutating. A denial returns
   HTTP 403 with :reason, :method, and :role, encoded using the negotiated
   content type. It logs the refusal without creating an event entity.

   Allowed requests bind sandbar.security.visibility/*principal* through
   Pedestal's downstream interceptor context, so projections share the same
   visibility decision even when individual handlers do not bind it."
  (:require [clojure.string          :as str]
            [clojure.tools.logging   :as log]
            [io.pedestal.interceptor :as interceptor]
            [sandbar.mcp.authz       :as authz]
            [sandbar.security.visibility :as visibility]
            [sandbar.util.http-status :as http-status]))

(def self-service-prefix
  "Path prefix of the session self-service routes exempt from the scope gate."
  "/api/auth/")

(defn request-family
  "Classify an HTTP `request` into the scope family the shared decision
   consumes: `:exempt` for the session self-service routes, `:read` for a
   GET, `:mutating` for anything else.  Pure — reads `:uri` and
   `:request-method` only."
  [{:keys [uri request-method]}]
  (cond
    (and (string? uri) (str/starts-with? uri self-service-prefix)) :exempt
    (= :get request-method)                                          :read
    :else                                                            :mutating))

(defn request-unit
  "The refused unit's label for the deny message and log line, e.g.
   `POST /api/events/server`."
  [{:keys [uri request-method]}]
  (str (str/upper-case (name (or request-method :unknown))) " " uri))

(defn deny-response
  "The HTTP 403 response for a `family-scope-decision` deny.  The body is a
   data map (encoded by the negotiated encoder on the way out) carrying the
   same keys as the MCP deny envelope's `:data`: `:reason`, the refused
   `:method` (here the HTTP unit) and the principal's `:role` when the scope
   carries one."
  [request decision scope]
  (let [reason (:reason decision)
        unit   (request-unit request)
        role   (when (map? scope) (first (:scope/capabilities scope)))]
    {:status  http-status/forbidden
     :headers {}
     :body    (cond-> {:error   "Permission denied"
                       :message (authz/deny-message unit reason)
                       :reason  reason
                       :method  unit}
                role (assoc :role role))}))

(defn require-scope-enter
  "The gate's `:enter`: compute the principal's scope, classify the request,
   run the shared decision; pass the context through on allow, terminate with
   the 403 deny response otherwise.  A nil `:identity` (no authenticated
   principal) projects to `::authz/unrestricted` and is allowed here — the
   stack's `require-authentication` interceptor, which runs BEFORE this one,
   is what refuses an unauthenticated request with 401; this gate never
   substitutes for it.

   Split out as a top-level fn so the interceptor value frozen into the route
   table calls through a var and a hot `require :reload` reaches the live
   serving path (the ceremony-#4 lesson, see `mcp.auth/bearer-enter`)."
  [context]
  (let [request   (:request context)
        principal (:identity context)
        family    (request-family request)
        scope     (authz/principal->scope principal)
        decision  (authz/family-scope-decision family scope)]
    (if (authz/deny? decision)
      (do
        (log/warn :REST/scope-denied
                  {:unit   (request-unit request)
                   :family family
                   :reason (:reason decision)
                   :role   (when (map? scope) (first (:scope/capabilities scope)))})
        (assoc context :response (deny-response request decision scope)))
      ;; Allowed: bind the principal for every downstream interceptor and
      ;; handler (Pedestal honours `:bindings` on the context).
      (update context :bindings assoc #'visibility/*principal* principal))))

(def require-scope
  "Pedestal interceptor applying `require-scope-enter` — mount it in the
   `/api` stack AFTER `auth/require-authentication`."
  (interceptor/interceptor
    {:name  ::require-scope
     :enter (fn [context] (require-scope-enter context))}))
