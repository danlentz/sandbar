(ns sandbar.util.auth
  "Authentication and authorization utilities.

   Provides Pedestal interceptors for authentication using Geheimtur,
   integrated with the Auth domain model and Event logging system.

   ## Quick Start

     (require '[sandbar.util.auth :as auth])

     ;; In your routes, add the authentication interceptor chain
     [[\"/api\" ^:interceptors [auth/session-interceptor
                                auth/authentication-interceptor]
       [\"/protected\" ^:interceptors [auth/require-authentication]
        ...]]]

   ## Authentication Methods

   Supports:
   - Form-based login (username/password)
   - API key authentication (for service accounts)
   - Session-based authentication (cookies)

   ## Events

   All authentication events are logged:
   - :auth/login-success
   - :auth/login-failure
   - :auth/logout
   - :auth/session-expired
   - :auth/access-denied"
  (:require [buddy.hashers :as hashers]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [io.pedestal.interceptor :as interceptor]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.event :as event])
  (:import [java.util Date UUID]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *session-duration-ms*
  "Default session duration: 24 hours"
  (* 24 60 60 1000))

(def ^:dynamic *session-idle-timeout-ms*
  "Idle timeout: 2 hours"
  (* 2 60 60 1000))

(def ^:dynamic *max-failed-logins*
  "Maximum failed login attempts before lockout"
  5)

(def ^:dynamic *lockout-duration-ms*
  "Lockout duration: 15 minutes"
  (* 15 60 1000))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Password Utilities
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn hash-password
  "Hash a password using bcrypt."
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn verify-password
  "Verify a password against a hash. Returns true if valid."
  [password hash]
  (try
    (:valid (hashers/verify password hash))
    (catch Exception _
      false)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Lookup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-user-by-username
  "Find a user by username. Returns entity map or nil."
  [username]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?username
                        :where [?e :auth/username ?username]]
                      (db/db) username)]
    (db/entity eid)))

(defn find-user-by-email
  "Find a user by email. Returns entity map or nil."
  [email]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?email
                        :where [?e :auth/email ?email]]
                      (db/db) email)]
    (db/entity eid)))

(defn find-service-account
  "Find a service account by name. Returns entity map or nil."
  [service-name]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?name
                        :where [?e :auth/service-name ?name]]
                      (db/db) service-name)]
    (db/entity eid)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Account Status
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn account-locked?
  "Check if an account is locked due to failed login attempts."
  [user]
  (when-let [locked-until (:auth/locked-until user)]
    (.after locked-until (Date.))))

(defn account-active?
  "Check if an account is active."
  [principal]
  (and (not (nil? principal))
       (:auth/active? principal true)))

(defn- increment-failed-logins!
  "Increment failed login counter, potentially locking the account."
  [user]
  (let [failed-count (inc (or (:auth/failed-logins user) 0))
        lock-account? (>= failed-count *max-failed-logins*)
        tx-data (cond-> [[:db/add (:db/id user) :auth/failed-logins failed-count]]
                  lock-account?
                  (conj [:db/add (:db/id user) :auth/locked-until
                         (Date. (+ (System/currentTimeMillis) *lockout-duration-ms*))]))]
    (when lock-account?
      (log/warn :AUTH/ACCOUNT-LOCKED {:user (:auth/username user)
                                       :failed-attempts failed-count
                                       :lockout-minutes (/ *lockout-duration-ms* 60000)}))
    @(d/transact (db/conn) tx-data)))

(defn- reset-failed-logins!
  "Reset failed login counter on successful login."
  [user]
  (when (and (:auth/failed-logins user) (pos? (:auth/failed-logins user)))
    @(d/transact (db/conn) [[:db/add (:db/id user) :auth/failed-logins 0]])))

(defn- update-last-login!
  "Update last login timestamp."
  [user]
  @(d/transact (db/conn) [[:db/add (:db/id user) :auth/last-login (Date.)]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Session Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-session!
  "Create a new session for a principal. Returns the session entity."
  [principal & {:keys [ip user-agent]}]
  (let [now (Date.)
        session-id (UUID/randomUUID)
        expires (Date. (+ (.getTime now) *session-duration-ms*))]
    (log/debug :AUTH/SESSION-CREATE {:principal (:db/id principal) :ip ip :session-id session-id})
    (dt/make :auth/Session
      {:auth/session-id session-id
       :auth/session-principal (:db/id principal)
       :auth/session-created now
       :auth/session-expires expires
       :auth/session-last-access now
       :auth/session-ip (or ip "unknown")
       :auth/session-user-agent (or user-agent "unknown")
       :auth/session-active? true})))

(defn find-session
  "Find an active session by session ID. Returns entity map or nil."
  [session-id]
  (when session-id
    (let [uuid (if (uuid? session-id) session-id (UUID/fromString (str session-id)))]
      (when-let [eid (d/q '[:find ?e .
                            :in $ ?sid
                            :where
                            [?e :auth/session-id ?sid]
                            [?e :auth/session-active? true]]
                          (db/db) uuid)]
        (db/entity eid)))))

(defn session-valid?
  "Check if a session is valid (not expired, not idle-timed-out)."
  [session]
  (when session
    (let [now (Date.)
          expires (:auth/session-expires session)
          last-access (:auth/session-last-access session)
          idle-limit (Date. (+ (.getTime last-access) *session-idle-timeout-ms*))]
      (and (:auth/session-active? session)
           (.before now expires)
           (.before now idle-limit)))))

(defn touch-session!
  "Update last access time for a session."
  [session]
  (when session
    @(d/transact (db/conn) [[:db/add (:db/id session) :auth/session-last-access (Date.)]])))

(defn invalidate-session!
  "Invalidate a session (logout)."
  [session]
  (when session
    (log/debug :AUTH/SESSION-INVALIDATE {:session-id (:auth/session-id session)})
    @(d/transact (db/conn) [[:db/add (:db/id session) :auth/session-active? false]])))

(defn get-session-principal
  "Get the principal associated with a session."
  [session]
  (when-let [principal-ref (:auth/session-principal session)]
    (db/entity (if (map? principal-ref) (:db/id principal-ref) principal-ref))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authentication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn authenticate-user
  "Authenticate a user with username/email and password.
   Returns {:success true :principal user} or {:success false :reason ...}"
  [identifier password & {:keys [ip user-agent]}]
  (let [user (or (find-user-by-username identifier)
                 (find-user-by-email identifier))]
    (cond
      (nil? user)
      (do
        (event/log! :warn "Login attempt for unknown user"
                    {:event/kind :auth/login-failure
                     :event/tags #{:auth :security}})
        {:success false :reason :unknown-user})

      (not (account-active? user))
      (do
        (event/log! :warn "Login attempt for inactive account"
                    {:event/kind :auth/login-failure
                     :event/tags #{:auth :security}})
        {:success false :reason :account-inactive})

      (account-locked? user)
      (do
        (event/log! :warn "Login attempt for locked account"
                    {:event/kind :auth/login-failure
                     :event/actor (:db/id user)
                     :event/tags #{:auth :security}})
        {:success false :reason :account-locked})

      (not (verify-password password (:auth/password-hash user)))
      (do
        (increment-failed-logins! user)
        (event/log! :warn "Failed login attempt - bad password"
                    {:event/kind :auth/login-failure
                     :event/actor (:db/id user)
                     :event/tags #{:auth :security}})
        {:success false :reason :invalid-password})

      :else
      (do
        (reset-failed-logins! user)
        (update-last-login! user)
        (log/info :AUTH/LOGIN-SUCCESS {:user (:auth/username user)})
        (event/log! :info "Successful login"
                    {:event/kind :auth/login-success
                     :event/actor (:db/id user)
                     :event/tags #{:auth}})
        {:success true :principal user}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API-key verification cache (reliability sprint D4, 2026-09-19)
;;
;; `verify-password` is bcrypt+sha512: about 280 ms per call on the dev
;; machine, and it ran on EVERY authenticated request.  That floor dwarfed
;; the work behind it (a warm search over all memories is 140 to 175 ms; a
;; count is 2 ms) and spent more than half of the recall hook's 500 ms
;; budget before any search ran (the D4 idle baseline, 2026-09-19).
;;
;; A presented key that has verified against an account's stored hash
;; stays verified for as long as that stored hash is unchanged, so the
;; verdict is cached under [service-name, SHA-256 of the presented key,
;; the stored hash it verified against].  A rotated key changes the stored
;; hash and misses the cache by construction; account activity and expiry
;; are re-checked on every request BEFORE the cache is consulted; entries
;; expire after `*api-key-verification-ttl-ms*`; the cache is bounded and
;; drops everything when full; failed verifications are never cached.  The
;; presented key itself is never stored, only its SHA-256.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *api-key-verification-ttl-ms*
  "How long a verified [service, key, stored-hash] triple stays verified
   without re-running bcrypt.  Ten minutes."
  (* 10 60 1000))

(def ^:private api-key-verification-cache-max
  "Entries kept before the cache is dropped wholesale (a handful of
   service accounts exist; this is a safety bound, not a working set)."
  1024)

(defonce ^:private api-key-verification-cache (atom {}))

(defn clear-api-key-verification-cache!
  "Forget every cached verification (tests; operator use after a key
   compromise, though rotating the key already invalidates its entries)."
  []
  (reset! api-key-verification-cache {}))

(defn- sha256-hex
  [^String s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes s "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn verify-api-key-cached
  "`verify-password` for API keys with the verification cache in front of
   it.  Returns true when `api-key` verifies against `stored-hash` for
   `service-name`, from the cache when the same triple verified within the
   TTL, else by running bcrypt and caching a success."
  [service-name api-key stored-hash]
  (if (or (nil? api-key) (nil? stored-hash))
    false
    (let [k   [service-name (sha256-hex api-key) stored-hash]
          now (System/currentTimeMillis)
          at  (get @api-key-verification-cache k)]
      (if (and at (< (- now at) *api-key-verification-ttl-ms*))
        true
        (let [ok? (verify-password api-key stored-hash)]
          (when ok?
            (swap! api-key-verification-cache
                   (fn [m]
                     (let [m (if (>= (count m) api-key-verification-cache-max) {} m)]
                       (assoc m k now)))))
          ok?)))))

(defn authenticate-api-key
  "Authenticate a service account with API key.
   Returns {:success true :principal sa} or {:success false :reason ...}

   The bcrypt verification is cached per [service, presented key, stored
   hash] for `*api-key-verification-ttl-ms*` (see the cache block above);
   activity and expiry are checked on every call regardless."
  [service-name api-key]
  (let [sa (find-service-account service-name)]
    (cond
      (nil? sa)
      {:success false :reason :unknown-service}

      (not (account-active? sa))
      {:success false :reason :account-inactive}

      ;; Check expiration
      (and (:auth/expires-at sa)
           (.after (Date.) (:auth/expires-at sa)))
      {:success false :reason :account-expired}

      (not (verify-api-key-cached service-name api-key (:auth/api-key-hash sa)))
      (do
        (event/log! :warn "Failed API key authentication"
                    {:event/kind :auth/login-failure
                     :event/tags #{:auth :security :api}})
        {:success false :reason :invalid-api-key})

      :else
      (do
        ;; A log line, not a database row.  One `:event/ServerEvent` row per
        ;; authenticated request was 94% of that class (17,675 of 18,775 on
        ;; 2026-09-19) with no consumer; the failure branch above keeps its
        ;; row.  Dan's retention-review ruling, 2026-09-19.
        (log/debug "Successful API key authentication"
                   {:event/kind :auth/login-success :event/actor (:db/id sa)})
        {:success true :principal sa}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authorization - Permissions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-role-permissions
  "Get all permissions for a role, including inherited ones."
  [role]
  (let [direct-perms (or (:auth/permissions role) [])
        inherited-roles (or (:auth/inherits-from role) [])
        inherited-perms (mapcat get-role-permissions inherited-roles)]
    (concat direct-perms inherited-perms)))

(defn get-principal-permissions
  "Get all permissions for a principal (from all roles)."
  [principal]
  (let [direct-roles (or (:auth/roles principal) [])
        ;; If principal is in groups, get group roles too
        groups (or (:auth/groups principal) [])
        group-roles (mapcat :auth/roles groups)
        all-roles (concat direct-roles group-roles)]
    (->> all-roles
         (mapcat get-role-permissions)
         (map (fn [p] (if (map? p) p (db/entity p))))
         (distinct))))

(defn has-permission?
  "Check if a principal has a specific permission."
  [principal permission-name]
  (let [perms (get-principal-permissions principal)]
    (some #(= permission-name (:auth/permission-name %)) perms)))

(defn has-role?
  "Check if a principal has a specific role."
  [principal role-name]
  (let [roles (or (:auth/roles principal) [])]
    (some #(= role-name (:auth/role-name (if (map? %) % (db/entity %)))) roles)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read-only principal — the restricted-reviewer role
;;
;; The `:read-only` role is a CAPABILITY MARKER, not a permission bag: it
;; carries no verb allowlist of its own.  Its meaning is enforced at the MCP
;; dispatch choke point (`sandbar.mcp.tools/handle-call`), which classifies
;; each verb's mutation-ness and rejects the mutating ones for a read-only
;; principal.  Keeping the allowlist OUT of the role (derived at the gate from
;; the live verb registry) is deny-by-default for future verbs — a new verb is
;; rejected until it is proven read-only, never silently permitted.
;; Per decisions/review_gate_runbook_wave1_ratification_fable_rulings_2026_07_02.md
;; ruling 8.

(def read-only-role
  "The role-name keyword marking a principal as read-only (the codex-review
   reviewer role).  The MCP gate treats a principal carrying this role as
   permitted to call read/introspection verbs only."
  :read-only)

(defn read-only-principal?
  "True iff `principal` carries the `:read-only` role.  Returns false for nil
   (no authenticated principal — the legacy/local full-access path), so the
   MCP gate is a no-op unless a restricted principal is actually present."
  [principal]
  (and (some? principal)
       (boolean (has-role? principal read-only-role))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pedestal Interceptors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def session-interceptor
  "Interceptor that loads session from cookie/header and attaches to context.

   Looks for session ID in:
   - Cookie: 'sandbar-session'
   - Header: 'X-Session-ID'

   Attaches :session and :identity to context if valid."
  (interceptor/interceptor
    {:name ::session
     :enter (fn [context]
              (let [request (:request context)
                    ;; Try cookie first, then header
                    session-id (or (get-in request [:cookies "sandbar-session" :value])
                                   (get-in request [:headers "x-session-id"]))
                    session (when session-id (find-session session-id))]
                (if (and session (session-valid? session))
                  (let [principal (get-session-principal session)]
                    (touch-session! session)
                    (-> context
                        (assoc :session session)
                        (assoc :identity principal)
                        (assoc-in [:request :session] session)
                        (assoc-in [:request :identity] principal)))
                  context)))}))

(def api-key-interceptor
  "Interceptor that authenticates via API key header.

   Looks for:
   - Header: 'X-API-Key' with format 'service-name:api-key'

   Attaches :identity to context if valid."
  (interceptor/interceptor
    {:name ::api-key
     :enter (fn [context]
              (let [request (:request context)
                    api-key-header (get-in request [:headers "x-api-key"])]
                (if (and api-key-header (not (:identity context)))
                  (let [[service-name api-key] (clojure.string/split api-key-header #":" 2)]
                    (if (and service-name api-key)
                      (let [result (authenticate-api-key (keyword service-name) api-key)]
                        (if (:success result)
                          (-> context
                              (assoc :identity (:principal result))
                              (assoc-in [:request :identity] (:principal result)))
                          context))
                      context))
                  context)))}))

(def authentication-interceptor
  "Combined interceptor that tries session auth, then API key auth."
  (interceptor/interceptor
    {:name ::authentication
     :enter (fn [context]
              ;; Try session first
              (let [ctx1 ((:enter session-interceptor) context)]
                (if (:identity ctx1)
                  ctx1
                  ;; Fall back to API key
                  ((:enter api-key-interceptor) ctx1))))}))

(def require-authentication
  "Interceptor that requires authentication. Returns 401 if not authenticated."
  (interceptor/interceptor
    {:name ::require-authentication
     :enter (fn [context]
              (if (:identity context)
                context
                (do
                  (event/log! :warn "Unauthenticated access attempt"
                              {:event/kind :auth/access-denied
                               :event/tags #{:auth :security}})
                  (assoc context :response
                         {:status 401
                          :headers {"Content-Type" "application/edn"
                                    "WWW-Authenticate" "Bearer realm=\"sandbar\""}
                          :body (pr-str {:error "Authentication required"})}))))}))

(defn require-permission
  "Create an interceptor that requires a specific permission."
  [permission-name]
  (interceptor/interceptor
    {:name ::require-permission
     :enter (fn [context]
              (let [principal (:identity context)]
                (if (and principal (has-permission? principal permission-name))
                  context
                  (do
                    (event/log! :warn "Permission denied"
                                {:event/kind :auth/access-denied
                                 :event/tags #{:auth :security}
                                 :event/description (str "Required permission: " permission-name)})
                    (assoc context :response
                           {:status 403
                            :headers {"Content-Type" "application/edn"}
                            :body (pr-str {:error "Permission denied"
                                           :required-permission permission-name})})))))}))

(defn require-role
  "Create an interceptor that requires a specific role."
  [role-name]
  (interceptor/interceptor
    {:name ::require-role
     :enter (fn [context]
              (let [principal (:identity context)]
                (if (and principal (has-role? principal role-name))
                  context
                  (do
                    (event/log! :warn "Role required"
                                {:event/kind :auth/access-denied
                                 :event/tags #{:auth :security}
                                 :event/description (str "Required role: " role-name)})
                    (assoc context :response
                           {:status 403
                            :headers {"Content-Type" "application/edn"}
                            :body (pr-str {:error "Insufficient privileges"
                                           :required-role role-name})})))))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience: Geheimtur Integration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn credentials-fn
  "Geheimtur-compatible credentials function for form-based login."
  [{:keys [username password] :as credentials}]
  (let [result (authenticate-user username password)]
    (when (:success result)
      (let [principal (:principal result)]
        {:identity (:db/id principal)
         :username (:auth/username principal)
         :email (:auth/email principal)
         :name (:auth/principal-name principal)
         :roles (set (map :auth/role-name (:auth/roles principal)))}))))

(defn unauthorized-fn
  "Geheimtur-compatible unauthorized handler."
  [request]
  {:status 401
   :headers {"Content-Type" "application/edn"
             "WWW-Authenticate" "Bearer realm=\"sandbar\""}
   :body (pr-str {:error "Authentication required"
                  :login-url "/api/auth/login"})})

(defn unauthenticated-fn
  "Geheimtur-compatible unauthenticated handler."
  [request]
  {:status 403
   :headers {"Content-Type" "application/edn"}
   :body (pr-str {:error "Access denied"})})
