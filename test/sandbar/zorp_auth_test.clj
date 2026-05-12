(ns sandbar.zorp-auth-test
  "Complete authentication lifecycle test for Zorp the Merchant.

   This test exercises the full auth system from registration through
   administrative session management. Zorp starts as a regular user,
   gets promoted to admin, and exercises all authentication features.

   Test Scenario:
   1. Zorp registers a new account
   2. Zorp logs in with username
   3. Zorp checks their identity via /me
   4. Zorp logs out
   5. Zorp logs in with email
   6. Zorp changes their password
   7. Zorp logs in with new password
   8. Zorp gets promoted to admin
   9. Zorp lists all active sessions
   10. Zorp creates a second session and invalidates it
   11. Zorp's minion service account authenticates via API key
   12. Failed login attempts trigger account lockout
   13. Zorp recovers from lockout"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.auth :as auth]
            [sandbar.test-util :as tu :refer [service]])
  (:import [java.util Date UUID]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "zorp-auth-test"
                                              :extra-schema [:auth :event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn post-edn
  "POST EDN data to an endpoint"
  [path body]
  (response-for service :post path
                :headers {"Content-Type" "application/edn"}
                :body (pr-str body)))

(defn get-with-session
  "GET with session header"
  [path session-id]
  (response-for service :get path
                :headers {"X-Session-ID" session-id}))

(defn post-with-session
  "POST with session header"
  [path session-id body]
  (response-for service :post path
                :headers {"Content-Type" "application/edn"
                          "X-Session-ID" session-id}
                :body (pr-str body)))

(defn delete-with-session
  "DELETE with session header"
  [path session-id]
  (response-for service :delete path
                :headers {"X-Session-ID" session-id}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The Epic Journey of Zorp's Authentication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest zorp-complete-auth-lifecycle-test
  (testing "CHAPTER 1: Zorp Registers for an Account"
    (let [response (post-edn "/register"
                             {:username "zorp"
                              :email "zorp@pluto.net"
                              :password "dark-side-secret"
                              :name "Zorp the Merchant"})
          body (tu/parse-edn-body response)]
      (is (= 201 (:status response)) "Registration should return 201 Created")
      (is (:success body) "Registration should succeed")
      (is (= "zorp" (get-in body [:user :username])))
      (is (= "zorp@pluto.net" (get-in body [:user :email])))
      (is (= "Zorp the Merchant" (get-in body [:user :name])))))

  (testing "CHAPTER 1b: Duplicate registration fails"
    (let [response (post-edn "/register"
                             {:username "zorp"
                              :email "another@pluto.net"
                              :password "doesntmatter"})
          body (tu/parse-edn-body response)]
      (is (= 409 (:status response)) "Duplicate username should return 409")
      (is (= "Username already exists" (:error body)))))

  (testing "CHAPTER 2: Zorp Logs In with Username"
    (let [response (post-edn "/login"
                             {:username "zorp"
                              :password "dark-side-secret"})
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)) "Login should succeed")
      (is (some? (:session-id body)) "Should return session ID")
      (is (= "zorp" (get-in body [:principal :username])))
      (is (= "Zorp the Merchant" (get-in body [:principal :name])))

      ;; Store session for next tests
      (def zorp-session-1 (:session-id body))))

  (testing "CHAPTER 3: Zorp Checks Identity via /me"
    (let [response (get-with-session "/me" zorp-session-1)
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)))
      (is (true? (:authenticated body)) "Should be authenticated")
      (is (= "zorp" (get-in body [:principal :username])))
      (is (= :auth/User (get-in body [:principal :type])))
      (is (some? (get-in body [:session :id])) "Should include session info")
      (is (some? (get-in body [:session :expires])) "Should include expiration")))

  (testing "CHAPTER 4: Zorp Logs Out"
    (let [response (post-with-session "/api/auth/logout" zorp-session-1 {})
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)))
      (is (true? (:success body))))

    ;; Verify session is now invalid
    (let [response (get-with-session "/me" zorp-session-1)
          body (tu/parse-edn-body response)]
      (is (false? (:authenticated body)) "Session should be invalidated")))

  (testing "CHAPTER 5: Zorp Logs In with Email"
    (let [response (post-edn "/login"
                             {:email "zorp@pluto.net"
                              :password "dark-side-secret"})
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)) "Login with email should succeed")
      (is (some? (:session-id body)))
      (def zorp-session-2 (:session-id body))))

  (testing "CHAPTER 6: Zorp Changes Password"
    (let [response (post-with-session "/api/auth/password" zorp-session-2
                                      {:current-password "dark-side-secret"
                                       :new-password "plutonian-ice-key"})
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)))
      (is (true? (:success body)) "Password change should succeed"))

    ;; Verify old password no longer works
    (let [response (post-edn "/login"
                             {:username "zorp"
                              :password "dark-side-secret"})
          body (tu/parse-edn-body response)]
      (is (= 401 (:status response)) "Old password should fail")
      (is (= :invalid-password (:reason body)))))

  (testing "CHAPTER 7: Zorp Logs In with New Password"
    (let [response (post-edn "/login"
                             {:username "zorp"
                              :password "plutonian-ice-key"})
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)) "New password should work")
      (def zorp-session-3 (:session-id body))))

  (testing "CHAPTER 8: Zorp Gets Promoted to Admin"
    ;; Create admin permission and role
    (let [admin-perm (dt/make :auth/Permission
                       {:auth/permission-name :permission/admin-sessions
                        :auth/permission-label "Manage Sessions"
                        :auth/resource-type :session
                        :auth/action :admin})
          admin-role (dt/make :auth/Role
                       {:auth/role-name :role/admin
                        :auth/role-label "Administrator"
                        :auth/permissions [(:db/id admin-perm)]}
                       {:validate? false})
          ;; Find Zorp and assign admin role
          zorp-user (auth/find-user-by-username "zorp")]
      @(d/transact (db/conn)
         [[:db/add (:db/id zorp-user) :auth/roles (:db/id admin-role)]])

      ;; Verify Zorp now has admin permission
      (let [updated-zorp (db/entity (:db/id zorp-user))]
        (is (auth/has-permission? updated-zorp :permission/admin-sessions)
            "Zorp should now have admin-sessions permission")
        (is (auth/has-role? updated-zorp :role/admin)
            "Zorp should have admin role"))))

  (testing "CHAPTER 9: Zorp Lists Active Sessions (as Admin)"
    ;; Need a fresh session to pick up new roles
    (let [login-response (post-edn "/login"
                                   {:username "zorp"
                                    :password "plutonian-ice-key"})
          login-body (tu/parse-edn-body login-response)
          admin-session (:session-id login-body)

          ;; Now list sessions
          response (get-with-session "/api/auth/sessions" admin-session)
          body (tu/parse-edn-body response)]
      (is (= 200 (:status response)) "Admin should access sessions")
      (is (number? (:count body)) "Should return count")
      (is (vector? (:sessions body)) "Should return sessions list")
      (is (pos? (:count body)) "Should have at least one session")

      ;; Store admin session for later
      (def zorp-admin-session admin-session)))

  (testing "CHAPTER 10: Zorp Creates and Invalidates a Session"
    ;; Create another session
    (let [login-response (post-edn "/login"
                                   {:username "zorp"
                                    :password "plutonian-ice-key"})
          temp-session (:session-id (tu/parse-edn-body login-response))]

      ;; Verify the new session works
      (let [me-response (get-with-session "/me" temp-session)]
        (is (true? (:authenticated (tu/parse-edn-body me-response)))))

      ;; Admin invalidates the session
      (let [response (delete-with-session
                       (str "/api/auth/sessions/" temp-session)
                       zorp-admin-session)
            body (tu/parse-edn-body response)]
        (is (= 200 (:status response)))
        (is (true? (:success body)) "Should successfully invalidate"))

      ;; Verify the session is now invalid
      (let [me-response (get-with-session "/me" temp-session)]
        (is (false? (:authenticated (tu/parse-edn-body me-response)))
            "Invalidated session should not work"))))

  (testing "CHAPTER 11: Zorp's Minion Service Account"
    ;; Create a service account for Zorp's inventory sync
    (let [api-key "minion-secret-key-12345"
          zorp-user (auth/find-user-by-username "zorp")
          minion (dt/make :auth/ServiceAccount
                   {:auth/service-name :service/zorp-minion
                    :auth/principal-name "Zorp's Inventory Minion"
                    :auth/api-key-hash (auth/hash-password api-key)
                    :auth/owner (:db/id zorp-user)
                    :auth/active? true})]

      ;; Authenticate with API key
      (let [result (auth/authenticate-api-key :service/zorp-minion api-key)]
        (is (:success result) "API key auth should succeed")
        (is (= "Zorp's Inventory Minion"
               (:auth/principal-name (:principal result)))))

      ;; Wrong API key should fail
      (let [result (auth/authenticate-api-key :service/zorp-minion "wrong-key")]
        (is (not (:success result)))
        (is (= :invalid-api-key (:reason result))))

      ;; Unknown service should fail
      (let [result (auth/authenticate-api-key :service/unknown "any-key")]
        (is (not (:success result)))
        (is (= :unknown-service (:reason result))))))

  (testing "CHAPTER 12: Account Lockout After Failed Attempts"
    ;; Create a test user for lockout testing (don't lock out Zorp!)
    (dt/make :auth/User
      {:auth/username "locktest"
       :auth/email "locktest@pluto.net"
       :auth/password-hash (auth/hash-password "testpass")
       :auth/active? true
       :auth/failed-logins 0})

    ;; Use a lower threshold for testing
    (binding [auth/*max-failed-logins* 3]
      ;; Three failed attempts
      (dotimes [i 3]
        (let [result (auth/authenticate-user "locktest" "wrongpassword")]
          (is (not (:success result)))
          (is (= :invalid-password (:reason result)))))

      ;; Account should now be locked
      (let [result (auth/authenticate-user "locktest" "testpass")]
        (is (not (:success result)) "Correct password should fail when locked")
        (is (= :account-locked (:reason result))))))

  (testing "CHAPTER 13: Lockout Recovery"
    ;; Manually clear the lockout
    (let [user (auth/find-user-by-username "locktest")]
      @(d/transact (db/conn)
         [[:db/retract (:db/id user) :auth/locked-until (:auth/locked-until user)]
          [:db/add (:db/id user) :auth/failed-logins 0]]))

    ;; Should be able to login again
    (let [result (auth/authenticate-user "locktest" "testpass")]
      (is (:success result) "Should login after lockout cleared"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Additional Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest zorp-password-validation-test
  (testing "Password must be at least 8 characters"
    (let [response (post-edn "/register"
                             {:username "shortpass"
                              :email "short@pluto.net"
                              :password "short"})
          body (tu/parse-edn-body response)]
      (is (= 400 (:status response)))
      (is (= "Password must be at least 8 characters" (:error body)))))

  (testing "New password must be at least 8 characters"
    ;; First register a user
    (post-edn "/register"
              {:username "passtest"
               :email "passtest@pluto.net"
               :password "validpassword"})
    (let [login-response (post-edn "/login"
                                   {:username "passtest"
                                    :password "validpassword"})
          session-id (:session-id (tu/parse-edn-body login-response))
          response (post-with-session "/api/auth/password" session-id
                                      {:current-password "validpassword"
                                       :new-password "short"})
          body (tu/parse-edn-body response)]
      (is (= 400 (:status response)))
      (is (= "New password must be at least 8 characters" (:error body))))))

(deftest zorp-inactive-account-test
  (testing "Inactive account cannot authenticate"
    (dt/make :auth/User
      {:auth/username "inactive"
       :auth/email "inactive@pluto.net"
       :auth/password-hash (auth/hash-password "password123")
       :auth/active? false})

    (let [response (post-edn "/login"
                             {:username "inactive"
                              :password "password123"})
          body (tu/parse-edn-body response)]
      (is (= 401 (:status response)))
      (is (= :account-inactive (:reason body))))))

(deftest zorp-unauthenticated-password-change-test
  (testing "Password change requires authentication"
    (let [response (post-edn "/api/auth/password"
                             {:current-password "anything"
                              :new-password "newpassword123"})
          body (tu/parse-edn-body response)]
      (is (= 401 (:status response)))
      (is (= "Authentication required" (:error body))))))

(deftest zorp-non-admin-session-access-test
  (testing "Non-admin cannot list sessions"
    ;; Register a regular user
    (post-edn "/register"
              {:username "regular"
               :email "regular@pluto.net"
               :password "regularpass"})
    (let [login-response (post-edn "/login"
                                   {:username "regular"
                                    :password "regularpass"})
          session-id (:session-id (tu/parse-edn-body login-response))
          response (get-with-session "/api/auth/sessions" session-id)
          body (tu/parse-edn-body response)]
      (is (= 403 (:status response)))
      (is (= "Admin permission required" (:error body))))))

(deftest zorp-role-inheritance-test
  (testing "Permissions are inherited through role hierarchy"
    ;; Create base role with read permission
    (let [read-perm (dt/make :auth/Permission
                      {:auth/permission-name :permission/read-footwear
                       :auth/resource-type :footwear
                       :auth/action :read})
          write-perm (dt/make :auth/Permission
                       {:auth/permission-name :permission/write-footwear
                        :auth/resource-type :footwear
                        :auth/action :write})
          viewer-role (dt/make :auth/Role
                        {:auth/role-name :role/footwear-viewer
                         :auth/role-label "Footwear Viewer"
                         :auth/permissions [(:db/id read-perm)]}
                        {:validate? false})
          ;; Editor inherits from viewer
          editor-role (dt/make :auth/Role
                        {:auth/role-name :role/footwear-editor
                         :auth/role-label "Footwear Editor"
                         :auth/permissions [(:db/id write-perm)]
                         :auth/inherits-from [(:db/id viewer-role)]}
                        {:validate? false})
          ;; User with editor role
          editor-user (dt/make :auth/User
                        {:auth/username "editor"
                         :auth/email "editor@pluto.net"
                         :auth/password-hash (auth/hash-password "editorpass")
                         :auth/active? true
                         :auth/roles [(:db/id editor-role)]}
                        {:validate? false})
          editor-entity (db/entity (:db/id editor-user))]

      ;; Editor should have both read (inherited) and write (direct)
      (is (auth/has-permission? editor-entity :permission/read-footwear)
          "Should inherit read permission from viewer role")
      (is (auth/has-permission? editor-entity :permission/write-footwear)
          "Should have direct write permission")

      ;; Verify all permissions are returned
      (let [perms (auth/get-principal-permissions editor-entity)
            perm-names (set (map :auth/permission-name perms))]
        (is (contains? perm-names :permission/read-footwear))
        (is (contains? perm-names :permission/write-footwear))))))

(deftest zorp-group-permissions-test
  (testing "Permissions flow through group membership"
    ;; Create permission and role
    (let [manage-perm (dt/make :auth/Permission
                        {:auth/permission-name :permission/manage-inventory
                         :auth/resource-type :inventory
                         :auth/action :manage})
          manager-role (dt/make :auth/Role
                         {:auth/role-name :role/inventory-manager
                          :auth/role-label "Inventory Manager"
                          :auth/permissions [(:db/id manage-perm)]}
                         {:validate? false})
          ;; Create group with the role
          managers-group (dt/make :auth/Group
                           {:auth/group-name :group/managers
                            :auth/principal-name "Managers Group"
                            :auth/roles [(:db/id manager-role)]}
                           {:validate? false})
          ;; Create user in the group
          group-user (dt/make :auth/User
                       {:auth/username "groupmember"
                        :auth/email "member@pluto.net"
                        :auth/password-hash (auth/hash-password "memberpass")
                        :auth/active? true
                        :auth/groups [(:db/id managers-group)]}
                       {:validate? false})
          user-entity (db/entity (:db/id group-user))]

      ;; User should have permission through group
      (is (auth/has-permission? user-entity :permission/manage-inventory)
          "Should have permission via group membership"))))

(deftest zorp-session-expiration-test
  (testing "Expired sessions are not valid"
    ;; Create a user
    (let [user (dt/make :auth/User
                 {:auth/username "expiretest"
                  :auth/email "expire@pluto.net"
                  :auth/password-hash (auth/hash-password "password123")
                  :auth/active? true})
          ;; Create an already-expired session
          past-date (Date. (- (System/currentTimeMillis) 1000))
          session (dt/make :auth/Session
                    {:auth/session-id (UUID/randomUUID)
                     :auth/session-principal (:db/id user)
                     :auth/session-created past-date
                     :auth/session-expires past-date  ; Already expired
                     :auth/session-last-access past-date
                     :auth/session-active? true})]

      (is (not (auth/session-valid? session))
          "Expired session should not be valid"))))

(deftest zorp-session-idle-timeout-test
  (testing "Idle sessions are not valid"
    (let [user (dt/make :auth/User
                 {:auth/username "idletest"
                  :auth/email "idle@pluto.net"
                  :auth/password-hash (auth/hash-password "password123")
                  :auth/active? true})
          now (Date.)
          ;; Session not expired but last access is too old
          old-access (Date. (- (System/currentTimeMillis)
                               (+ auth/*session-idle-timeout-ms* 1000)))
          future-expire (Date. (+ (System/currentTimeMillis)
                                  auth/*session-duration-ms*))
          session (dt/make :auth/Session
                    {:auth/session-id (UUID/randomUUID)
                     :auth/session-principal (:db/id user)
                     :auth/session-created old-access
                     :auth/session-expires future-expire
                     :auth/session-last-access old-access  ; Too old
                     :auth/session-active? true})]

      (is (not (auth/session-valid? session))
          "Idle session should not be valid"))))

(deftest zorp-service-account-expiration-test
  (testing "Expired service account cannot authenticate"
    (let [api-key "expired-key"
          past-date (Date. (- (System/currentTimeMillis) 1000))
          sa (dt/make :auth/ServiceAccount
               {:auth/service-name :service/expired
                :auth/principal-name "Expired Service"
                :auth/api-key-hash (auth/hash-password api-key)
                :auth/active? true
                :auth/expires-at past-date})]

      (let [result (auth/authenticate-api-key :service/expired api-key)]
        (is (not (:success result)))
        (is (= :account-expired (:reason result)))))))
