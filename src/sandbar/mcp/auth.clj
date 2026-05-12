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

(defn extract-bearer-token
  "Extract the bearer token string from an HTTP request's Authorization
   header. Returns the token (without the 'Bearer ' prefix) or nil if
   the header is missing or malformed.

   Header keys are case-insensitive per the HTTP spec; this checks the
   lowercase form Pedestal normalizes to."
  [request]
  (when-let [hdr (or (get-in request [:headers "authorization"])
                     (get-in request [:headers "Authorization"]))]
    (let [trimmed (str/trim hdr)]
      (cond
        (str/starts-with? trimmed "Bearer ")
        (str/trim (subs trimmed 7))

        (str/starts-with? trimmed "bearer ")
        (str/trim (subs trimmed 7))

        :else nil))))

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

(def bearer-interceptor
  "Pedestal interceptor that extracts a Bearer token from the request's
   Authorization header + validates via authenticate-api-key.
   Attaches :identity to context on success.

   Failure modes:
   - Missing Authorization header → context unchanged; downstream
     require-authentication returns 401
   - Malformed token → context unchanged (same path)
   - authenticate-api-key returns {:success false} → logs the reason +
     leaves context unchanged

   Per ADR B.1.2: this is the canonical MCP auth path; OAuth 2.0 is a
   future extension."
  (interceptor/interceptor
    {:name  ::bearer
     :enter (fn [context]
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
                        (assoc context :identity (:principal result)))
                      (do
                        (log/warn :MCP/bearer-rejected
                                  {:reason (:reason result)
                                   :service-key service-key})
                        context))))))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Require-bearer interceptor
;;
;; Terminates the request with HTTP 401 if :identity isn't attached by
;; the bearer-interceptor (or another upstream auth interceptor).
;; Adds WWW-Authenticate: Bearer header per RFC 6750.

(def require-bearer
  "Pedestal interceptor that terminates the request with 401 if no
   :identity is attached by an upstream auth interceptor. Adds
   WWW-Authenticate: Bearer header per RFC 6750.

   Distinct from sandbar.util.auth/require-authentication only in the
   WWW-Authenticate response header (Bearer vs Session). Composable
   with require-authentication if both auth modes are accepted."
  (interceptor/interceptor
    {:name  ::require-bearer
     :enter (fn [context]
              (if (:identity context)
                context
                (assoc context
                  :response {:status  http-status/not-authorized
                             :headers {"WWW-Authenticate" "Bearer realm=\"sandbar-mcp\""
                                       "Content-Type"     "application/json"}
                             :body    "{\"error\":\"Bearer token required\"}"})))}))
