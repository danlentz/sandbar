(ns sandbar.service.authorization
  "The REST `/api` scope gate — the same principal decision the MCP dispatch
   gate applies, run as a Pedestal interceptor before any handler.

   Before this gate the protected REST stack required only AUTHENTICATION
   (`auth/require-authentication`): any active account, including one carrying
   the read-only role or no role at all, could create events, jobs, workflow
   processes and transitions over HTTP while the same credential was refused
   the same mutations over MCP (contract CT-01 of the 0.2.0 review fleet,
   reliability sprint D4b, 2026-09-19).  The two transports authenticate
   against one account store, so they must authorize against one decision:
   `authz/family-scope-decision`, fed by `authz/principal->scope`.

   Classification of an HTTP request into a scope family:

   - `:exempt`   — the session self-service routes under `/api/auth/*`
                   (logout, password change, session listing and invalidation,
                   the latter two already permission-gated in their handlers).
                   A session must be able to end itself whatever its scope,
                   mirroring the MCP lifecycle exemption for `initialize`.
   - `:read`     — every GET.  The route table has no mutating GET.
   - `:mutating` — every other method (POST / DELETE / PUT / PATCH).

   A deny short-circuits with HTTP 403 and a data body whose keys mirror the
   MCP deny envelope (`:reason`, `:method`, `:role`) so a client sees the same
   vocabulary on both transports; the negotiated encoder (`content/data-body`)
   renders it as EDN or JSON per the Accept header.  The deny is a log line,
   not an event row (Dan's retention ruling of 2026-09-19).

   Requires `sandbar.mcp.authz` (the pure decision core) and nothing of the
   verb catalog; it is a thin transport adapter, like the dispatch gate."
  (:require [clojure.string          :as str]
            [clojure.tools.logging   :as log]
            [io.pedestal.interceptor :as interceptor]
            [sandbar.mcp.authz       :as authz]
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
      context)))

(def require-scope
  "Pedestal interceptor applying `require-scope-enter` — mount it in the
   `/api` stack AFTER `auth/require-authentication`."
  (interceptor/interceptor
    {:name  ::require-scope
     :enter (fn [context] (require-scope-enter context))}))
