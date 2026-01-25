(ns sandbar.api.auth
  "REST API handlers for authentication.

   Endpoints:
   - POST /api/auth/login      - Authenticate with username/password
   - POST /api/auth/logout     - End current session
   - GET  /api/auth/me         - Get current user info
   - GET  /api/auth/sessions   - List active sessions (admin)
   - DELETE /api/auth/sessions/:id - Invalidate a session (admin)"
  (:require [clojure.tools.logging :as log]
            [sandbar.service.endpoint :as endpoint :refer [defhandler return]]
            [sandbar.util.auth :as auth]
            [sandbar.util.event :as event]
            [sandbar.util.http-status :as http-status])
  (:import [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Login
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler login
  "Authenticate with username/password. Returns session token on success.

   Request body:
     {:username \"zorp\" :password \"secret\"}
   or
     {:email \"zorp@pluto.net\" :password \"secret\"}

   Response:
     {:session-id \"uuid\" :expires \"iso-date\" :principal {...}}"
  [request _ params]
  (let [identifier (or (:username params) (:email params))
        password (:password params)
        ip (:remote-addr request)
        user-agent (get-in request [:headers "user-agent"])]
    (cond
      (nil? identifier)
      (return http-status/bad-request {:error "Username or email required"})

      (nil? password)
      (return http-status/bad-request {:error "Password required"})

      :else
      (let [result (auth/authenticate-user identifier password :ip ip :user-agent user-agent)]
        (if (:success result)
          (let [principal (:principal result)
                session (auth/create-session! principal :ip ip :user-agent user-agent)]
            (return {"Set-Cookie" (str "sandbar-session=" (:auth/session-id session)
                                       "; Path=/; HttpOnly; SameSite=Strict")}
                    http-status/success
                    {:session-id (str (:auth/session-id session))
                     :expires (str (:auth/session-expires session))
                     :principal {:id (:db/id principal)
                                 :username (:auth/username principal)
                                 :email (:auth/email principal)
                                 :name (:auth/principal-name principal)}}))
          (do
            (log/info :API/LOGIN-FAILED {:identifier identifier :reason (:reason result) :ip ip})
            (return http-status/not-authorized
                    {:error "Authentication failed"
                     :reason (:reason result)})))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logout
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler logout
  "End the current session.

   Response:
     {:success true}"
  [request _ _]
  (let [session (:session request)]
    (when session
      (auth/invalidate-session! session)
      (event/log! :info "User logged out"
                  {:event/kind :auth/logout
                   :event/actor (:db/id (auth/get-session-principal session))
                   :event/tags #{:auth}}))
    (return {"Set-Cookie" "sandbar-session=; Path=/; HttpOnly; Max-Age=0"}
            http-status/success
            {:success true})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Current User
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler me
  "Get the current authenticated user's information.

   Response:
     {:authenticated true
      :principal {...}
      :session {...}
      :permissions [...]}
   or
     {:authenticated false}"
  [request _ _]
  (let [identity (:identity request)
        session (:session request)]
    (if identity
      {:authenticated true
       :principal {:id (:db/id identity)
                   :type (or (:dt/type identity) :auth/User)
                   :username (:auth/username identity)
                   :email (:auth/email identity)
                   :name (:auth/principal-name identity)
                   :roles (mapv (fn [r]
                                  (let [role (if (map? r) r (sandbar.db.datomic/entity r))]
                                    {:name (:auth/role-name role)
                                     :label (:auth/role-label role)}))
                                (:auth/roles identity))}
       :session (when session
                  {:id (str (:auth/session-id session))
                   :created (str (:auth/session-created session))
                   :expires (str (:auth/session-expires session))
                   :last-access (str (:auth/session-last-access session))})
       :permissions (mapv (fn [p]
                            {:name (:auth/permission-name p)
                             :resource (:auth/resource-type p)
                             :action (:auth/action p)})
                          (auth/get-principal-permissions identity))}
      {:authenticated false})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Session Management (Admin)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-sessions
  "List all active sessions. Requires admin permission.

   Query params:
     ?user=username - Filter by username

   Response:
     {:count N :sessions [...]}"
  [request _ {:keys [user]}]
  (let [identity (:identity request)]
    (if (and identity (auth/has-permission? identity :permission/admin-sessions))
      (let [sessions (if user
                       ;; Query sessions for specific user
                       (let [user-entity (auth/find-user-by-username user)]
                         (when user-entity
                           (datomic.api/q '[:find [(pull ?s [*]) ...]
                                            :in $ ?uid
                                            :where
                                            [?s :auth/session-active? true]
                                            [?s :auth/session-principal ?uid]]
                                          (sandbar.db.datomic/db) (:db/id user-entity))))
                       ;; All active sessions
                       (datomic.api/q '[:find [(pull ?s [*]) ...]
                                        :where
                                        [?s :auth/session-active? true]]
                                      (sandbar.db.datomic/db)))]
        {:count (count sessions)
         :sessions (mapv (fn [s]
                           {:id (str (:auth/session-id s))
                            :principal-id (:db/id (:auth/session-principal s))
                            :created (str (:auth/session-created s))
                            :expires (str (:auth/session-expires s))
                            :last-access (str (:auth/session-last-access s))
                            :ip (:auth/session-ip s)
                            :user-agent (:auth/session-user-agent s)})
                         sessions)})
      (return http-status/forbidden {:error "Admin permission required"}))))

(defhandler invalidate-session
  "Invalidate a specific session by ID. Requires admin permission.

   Response:
     {:success true}"
  [request _ {:keys [id]}]
  (let [identity (:identity request)]
    (if (and identity (auth/has-permission? identity :permission/admin-sessions))
      (if-let [session (auth/find-session id)]
        (do
          (auth/invalidate-session! session)
          (event/log! :info "Admin invalidated session"
                      {:event/kind :auth/session-invalidated
                       :event/actor (:db/id identity)
                       :event/target (:db/id session)
                       :event/tags #{:auth :admin}})
          {:success true})
        (return http-status/not-found {:error "Session not found"}))
      (return http-status/forbidden {:error "Admin permission required"}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Registration (Optional)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler register
  "Register a new user account.

   Request body:
     {:username \"zorp\"
      :email \"zorp@pluto.net\"
      :password \"secret\"
      :name \"Zorp the Merchant\"}

   Response:
     {:success true :user {...}}"
  [request _ params]
  (let [{:keys [username email password name]} params]
    (cond
      (nil? username)
      (return http-status/bad-request {:error "Username required"})

      (nil? email)
      (return http-status/bad-request {:error "Email required"})

      (nil? password)
      (return http-status/bad-request {:error "Password required"})

      (< (count password) 8)
      (return http-status/bad-request {:error "Password must be at least 8 characters"})

      (auth/find-user-by-username username)
      (return http-status/conflict {:error "Username already exists"})

      (auth/find-user-by-email email)
      (return http-status/conflict {:error "Email already exists"})

      :else
      (let [user (sandbar.db.datatype/make :auth/User
                   {:auth/username username
                    :auth/email email
                    :auth/password-hash (auth/hash-password password)
                    :auth/principal-name (or name username)
                    :auth/active? true
                    :auth/created-at (Date.)})]
        (log/info :API/USER-REGISTERED {:username username :email email :user-id (:db/id user)})
        (event/log! :info "New user registered"
                    {:event/kind :auth/registration
                     :event/actor (:db/id user)
                     :event/tags #{:auth}})
        (return http-status/created
                {:success true
                 :user {:id (:db/id user)
                        :username username
                        :email email
                        :name (:auth/principal-name user)}})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Password Change
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler change-password
  "Change the current user's password.

   Request body:
     {:current-password \"old\"
      :new-password \"new\"}

   Response:
     {:success true}"
  [request _ params]
  (let [identity (:identity request)
        {:keys [current-password new-password]} params]
    (cond
      (nil? identity)
      (return http-status/not-authorized {:error "Authentication required"})

      (nil? current-password)
      (return http-status/bad-request {:error "Current password required"})

      (nil? new-password)
      (return http-status/bad-request {:error "New password required"})

      (< (count new-password) 8)
      (return http-status/bad-request {:error "New password must be at least 8 characters"})

      (not (auth/verify-password current-password (:auth/password-hash identity)))
      (return http-status/bad-request {:error "Current password is incorrect"})

      :else
      (do
        @(datomic.api/transact (sandbar.db.datomic/conn)
           [[:db/add (:db/id identity) :auth/password-hash (auth/hash-password new-password)]])
        (event/log! :info "Password changed"
                    {:event/kind :auth/password-change
                     :event/actor (:db/id identity)
                     :event/tags #{:auth :security}})
        {:success true}))))
