(ns sandbar.mcp.auth
  "MCP Bearer-token Pedestal interceptor — composes with Sandbar's
   existing `sandbar.util.auth/authenticate-api-key` via thin
   Bearer-extraction layer.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.2:
   - Authorization: Bearer <token> header on each MCP request
   - Token format: `<service-name>:<api-key>` matching Sandbar's
     existing X-API-Key convention (per sandbar.util.auth)
   - Validation routes through authenticate-api-key (Buddy-hashers
     under the hood)

   Discipline per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
   this interceptor calls Sandbar's auth abstraction layer (authenticate-api-key);
   it does NOT reimplement password / api-key verification.

   Stage C.2 of the Sandbar-as-MCP-Server arc.

   Subsequent enhancements:
   - C.2.1 OAuth 2.0 flow (deferred; see ADR §1 B.1.2 + arc plan §4 Q2)
   - C.2.2 Dynamic-token / rotating-token support per Claude Code's
     env-var-expansion semantics"
  (:require [clojure.string                :as str]
            [clojure.tools.logging         :as log]
            [io.pedestal.interceptor       :as interceptor]
            [sandbar.util.auth             :as auth]
            [sandbar.util.http-status      :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bearer token extraction
;;
;; RFC 6750: Authorization: Bearer <token>
;; Sandbar API-key format: <service-name>:<api-key>
;; Combined: Authorization: Bearer <service-name>:<api-key>

(defn- bearer-scheme?
  "True iff `s` begins with the seven-character Bearer scheme prefix
   under RFC-compliant case-insensitive comparison (RFC 6750 §2.1 +
   RFC 7235 §2.1 mandate case-insensitive auth-scheme matching)."
  [s]
  (and (string? s)
       (>= (count s) 7)
       (= "BEARER " (-> s (subs 0 7) str/upper-case))))

(defn extract-bearer-token
  "Extract the bearer token string from an HTTP request's Authorization
   header. Returns the token (without the 'Bearer ' prefix) or nil if
   the header is missing or malformed.

   Header keys are case-insensitive per the HTTP spec; this checks the
   lowercase form Pedestal normalizes to.

   Per RFC 6750 §2.1 + RFC 7235 §2.1 the Bearer scheme name is matched
   case-insensitively: \"Bearer\", \"bearer\", \"BEARER\", \"BeArEr\",
   etc. all accepted."
  [request]
  (when-let [hdr (or (get-in request [:headers "authorization"])
                     (get-in request [:headers "Authorization"]))]
    (let [trimmed (str/trim hdr)]
      (when (bearer-scheme? trimmed)
        (str/trim (subs trimmed 7))))))

(defn parse-token
  "Parse a Bearer token into [service-name api-key]. Returns nil if the
   token doesn't match the expected `<service-name>:<api-key>` shape.

   Per ADR B.1.2 + the existing X-API-Key convention in
   sandbar.util.auth."
  [token]
  (when token
    (let [colon (.indexOf ^String token ":")]
      (when (and (pos? colon) (< colon (dec (count token))))
        [(subs token 0 colon)
         (subs token (inc colon))]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pedestal interceptor
;;
;; Mirrors the shape of sandbar.util.auth/api-key-interceptor + delegates
;; to authenticate-api-key. Attaches :identity to context on success;
;; leaves context unchanged on failure (require-authentication handles
;; the 401 separately).

(defn bearer-enter
  "Bearer-token `:enter` behavior: authenticate the request's `Authorization:
   Bearer <service>:<key>` header via authenticate-api-key and, on success,
   attach the principal to BOTH the Pedestal context (`:identity`) AND the
   request map (`[:request :identity]`).

   The request-map attach is load-bearing for the read-only token gate: the
   MCP handler is a `defhandler` and receives only the REQUEST
   (service/endpoint.clj standard-endpoint), then reads `(:identity request)`
   to thread the principal into protocol/dispatch → handle-call.  Attaching to
   the context alone (the pre-ff176c2 shape) leaves the wire principal nil at
   the gate → the gate no-ops → mutations run under legacy full access
   (fail-open).  Mirrors the X-API-Key + session interceptors (util/auth.clj).

   Split out as a top-level fn (not inlined in the interceptor value) SO the
   `bearer-interceptor` record stays HOT-RELOADABLE: Pedestal freezes the
   interceptor VALUE into the routing table at route-expansion (a `defonce`
   Jetty connector expands it once at server construction — see
   sandbar.server.pedestal), and its IntoInterceptor Symbol/Var impls deref
   the interceptor to its value at that instant.  A running-JVM `require
   :reload` rebinds this var, and because the frozen record's `:enter` closure
   calls THROUGH the var, the live serving path picks up the reload without a
   full connector rebuild.  This is the same reloadability idiom the endpoint
   definers use (`defendpoint`'s `@(var impl)`, `defhandler`'s `#'impl`).  Its
   absence here is the ceremony-#4 wire breach
   (bugs/readonly_token_gate_not_enforced_on_live_wire_path_principal_not_threaded_2026_07_03.md):
   the gate landed in the vars but the frozen connector kept the pre-threading
   `bearer-interceptor` value.

   Failure modes (context returned unchanged; downstream require-bearer 401s):
   - Missing Authorization header / no Bearer token → pass through
   - Already-authenticated (upstream session) → pass through, do NOT overwrite
   - Malformed token (no colon separator) → log + pass through
   - authenticate-api-key {:success false} → log the reason + pass through"
  [context]
  (let [request (:request context)
        token   (extract-bearer-token request)
        parsed  (parse-token token)]
    (cond
      ;; No identity already; no Bearer token presented; pass through
      (nil? token)
      context

      ;; Already authenticated by an upstream interceptor
      ;; (e.g., session); pass through
      (some? (:identity context))
      context

      ;; Malformed Bearer token
      (nil? parsed)
      (do
        (log/warn :MCP/bearer-malformed
                  {:reason :token-missing-colon-separator})
        context)

      :else
      ;; Per F-M-002 fix: `authenticate-api-key` expects the
      ;; service-name as a keyword (schema type
      ;; :db.type/keyword); the Bearer token carries it as a
      ;; string per RFC 6750.  Keywordize at the boundary —
      ;; mirrors the existing X-API-Key interceptor path.
      (let [[service-name api-key] parsed
            service-key (keyword service-name)
            result (auth/authenticate-api-key service-key api-key)]
        (if (:success result)
          (do
            (log/debug :MCP/bearer-authenticated
                       {:principal-id (-> result :principal :db/id)})
            (-> context
                (assoc :identity (:principal result))
                (assoc-in [:request :identity] (:principal result))))
          (do
            (log/warn :MCP/bearer-rejected
                      {:reason (:reason result)
                       :service-key service-key})
            context))))))

(def bearer-interceptor
  "Pedestal interceptor that extracts a Bearer token from the request's
   Authorization header + validates via authenticate-api-key.
   Attaches :identity to the context AND `[:request :identity]` on success.

   The `:enter` delegates to `bearer-enter` (a top-level var) rather than
   inlining the body, so a frozen route table / `defonce` connector stays
   hot-reloadable — see the `bearer-enter` docstring for why this is the wire
   enforcement contract's keystone.

   Per ADR B.1.2: this is the canonical MCP auth path; OAuth 2.0 is a
   future extension."
  (interceptor/interceptor
    {:name  ::bearer
     :enter (fn [context] (bearer-enter context))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Require-bearer interceptor
;;
;; Terminates the request with HTTP 401 if :identity isn't attached by
;; the bearer-interceptor (or another upstream auth interceptor).
;; Adds WWW-Authenticate: Bearer header per RFC 6750.

(defn require-bearer-enter
  "Require-bearer `:enter` behavior: pass the context through when an upstream
   auth interceptor has attached `:identity`, else terminate with HTTP 401 +
   `WWW-Authenticate: Bearer` (RFC 6750).

   Split out as a top-level fn for the same hot-reloadability reason as
   `bearer-enter` — the frozen route table calls through this var, so a running
   JVM's `require :reload` reaches the live serving path."
  [context]
  (if (:identity context)
    context
    (assoc context
      :response {:status  http-status/not-authorized
                 :headers {"WWW-Authenticate" "Bearer realm=\"sandbar-mcp\""
                           "Content-Type"     "application/json"}
                 :body    "{\"error\":\"Bearer token required\"}"})))

(def require-bearer
  "Pedestal interceptor that terminates the request with 401 if no
   :identity is attached by an upstream auth interceptor. Adds
   WWW-Authenticate: Bearer header per RFC 6750.

   The `:enter` delegates to `require-bearer-enter` (a top-level var) so a
   frozen route table / `defonce` connector stays hot-reloadable.

   Distinct from sandbar.util.auth/require-authentication only in the
   WWW-Authenticate response header (Bearer vs Session). Composable
   with require-authentication if both auth modes are accepted."
  (interceptor/interceptor
    {:name  ::require-bearer
     :enter (fn [context] (require-bearer-enter context))}))
