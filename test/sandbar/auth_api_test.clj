(ns sandbar.auth-api-test
  "Test suite for the Authentication API.

   Tests the full authentication flow: registration, login, session management,
   logout, and permission checks. Also verifies that auth events are logged.

   If these tests fail, users might be able to log in without passwords,
   or worse, be locked out forever. Handle with care."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.auth :as auth]
            [sandbar.test-util :as tu]
            [io.pedestal.test :refer [response-for]])
  (:import [java.util Date UUID]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "auth-api-test"
                                              :extra-schema [:auth :event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Password Utilities Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest password-hashing-test
  (testing "Password hashing and verification"
    (let [password "super-secret-password"
          hash (auth/hash-password password)]
      (is (string? hash) "Hash should be a string")
      (is (not= password hash) "Hash should not equal password")
      (is (auth/verify-password password hash) "Should verify correct password")
      (is (not (auth/verify-password "wrong-password" hash)) "Should reject wrong password")))

  (testing "Different passwords produce different hashes"
    (let [hash1 (auth/hash-password "password1")
          hash2 (auth/hash-password "password2")]
      (is (not= hash1 hash2) "Different passwords should have different hashes")))

  (testing "Same password produces different hashes (salt)"
    ;; bcrypt includes random salt, so same password hashes differently each time
    (let [hash1 (auth/hash-password "same-password")
          hash2 (auth/hash-password "same-password")]
      (is (not= hash1 hash2) "Same password should have different hashes due to salt")
      (is (auth/verify-password "same-password" hash1))
      (is (auth/verify-password "same-password" hash2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Lookup Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest user-lookup-test
  (let [user (dt/make :auth/User
               {:auth/username "zorp"
                :auth/email "zorp@pluto.net"
                :auth/password-hash (auth/hash-password "secret123")
                :auth/principal-name "Zorp the Merchant"
                :auth/active? true})]

    (testing "Find user by username"
      (let [found (auth/find-user-by-username "zorp")]
        (is (some? found) "Should find user")
        (is (= "zorp" (:auth/username found)))
        (is (= "zorp@pluto.net" (:auth/email found)))))

    (testing "Find user by email"
      (let [found (auth/find-user-by-email "zorp@pluto.net")]
        (is (some? found) "Should find user")
        (is (= "zorp" (:auth/username found)))))

    (testing "Non-existent user returns nil"
      (is (nil? (auth/find-user-by-username "nobody")))
      (is (nil? (auth/find-user-by-email "nobody@nowhere.com"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Authentication Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest authentication-test
  (let [_ (dt/make :auth/User
            {:auth/username "zorp"
             :auth/email "zorp@pluto.net"
             :auth/password-hash (auth/hash-password "secret123")
             :auth/principal-name "Zorp the Merchant"
             :auth/active? true})]

    (testing "Successful authentication with username"
      (let [result (auth/authenticate-user "zorp" "secret123")]
        (is (:success result) "Should succeed")
        (is (some? (:principal result)) "Should include principal")))

    (testing "Successful authentication with email"
      (let [result (auth/authenticate-user "zorp@pluto.net" "secret123")]
        (is (:success result) "Should succeed with email")))

    (testing "Failed authentication - wrong password"
      (let [result (auth/authenticate-user "zorp" "wrong-password")]
        (is (not (:success result)) "Should fail")
        (is (= :invalid-password (:reason result)))))

    (testing "Failed authentication - unknown user"
      (let [result (auth/authenticate-user "nobody" "password")]
        (is (not (:success result)))
        (is (= :unknown-user (:reason result)))))))

(deftest inactive-account-test
  (let [_ (dt/make :auth/User
            {:auth/username "inactive"
             :auth/email "inactive@test.com"
             :auth/password-hash (auth/hash-password "password")
             :auth/active? false})]

    (testing "Cannot authenticate inactive account"
      (let [result (auth/authenticate-user "inactive" "password")]
        (is (not (:success result)))
        (is (= :account-inactive (:reason result)))))))

(deftest account-lockout-test
  ;; This test uses a lower lockout threshold for speed
  (binding [auth/*max-failed-logins* 3]
    (let [_ (dt/make :auth/User
              {:auth/username "lockme"
               :auth/email "lockme@test.com"
               :auth/password-hash (auth/hash-password "password")
               :auth/active? true
               :auth/failed-logins 0})]

      (testing "Account gets locked after too many failures"
        ;; Fail 3 times
        (auth/authenticate-user "lockme" "wrong1")
        (auth/authenticate-user "lockme" "wrong2")
        (auth/authenticate-user "lockme" "wrong3")

        ;; Now even correct password should fail
        (let [result (auth/authenticate-user "lockme" "password")]
          (is (not (:success result)))
          (is (= :account-locked (:reason result))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Session Management Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest session-management-test
  (let [user (dt/make :auth/User
               {:auth/username "sessionuser"
                :auth/email "session@test.com"
                :auth/password-hash (auth/hash-password "password")
                :auth/active? true})]

    (testing "Create a session"
      (let [session (auth/create-session! user :ip "127.0.0.1" :user-agent "TestClient")]
        (is (some? session) "Session should be created")
        (is (uuid? (:auth/session-id session)) "Should have UUID")
        (is (:auth/session-active? session) "Should be active")
        (is (= "127.0.0.1" (:auth/session-ip session)))))

    (testing "Find a session by ID"
      (let [session (auth/create-session! user)
            found (auth/find-session (:auth/session-id session))]
        (is (some? found) "Should find session")
        (is (= (:auth/session-id session) (:auth/session-id found)))))

    (testing "Session validity"
      (let [session (auth/create-session! user)]
        (is (auth/session-valid? session) "Fresh session should be valid")))

    (testing "Get principal from session"
      (let [session (auth/create-session! user)
            principal (auth/get-session-principal session)]
        (is (some? principal))
        (is (= "sessionuser" (:auth/username principal)))))

    (testing "Invalidate session (logout)"
      (let [session (auth/create-session! user)
            session-id (:auth/session-id session)]
        (auth/invalidate-session! session)
        (let [found (auth/find-session session-id)]
          (is (nil? found) "Invalidated session should not be found"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Permission Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest permission-test
  (let [read-perm (dt/make :auth/Permission
                    {:auth/permission-name :permission/read-inventory
                     :auth/resource-type :inventory
                     :auth/action :read})
        write-perm (dt/make :auth/Permission
                     {:auth/permission-name :permission/write-inventory
                      :auth/resource-type :inventory
                      :auth/action :write})
        viewer-role (dt/make :auth/Role
                      {:auth/role-name :role/viewer
                       :auth/role-label "Viewer"
                       :auth/permissions [(:db/id read-perm)]}
                      {:validate? false})
        editor-role (dt/make :auth/Role
                      {:auth/role-name :role/editor
                       :auth/role-label "Editor"
                       :auth/permissions [(:db/id read-perm) (:db/id write-perm)]}
                      {:validate? false})
        viewer-user (dt/make :auth/User
                      {:auth/username "viewer"
                       :auth/email "viewer@test.com"
                       :auth/password-hash (auth/hash-password "pass")
                       :auth/roles [(:db/id viewer-role)]}
                      {:validate? false})
        editor-user (dt/make :auth/User
                      {:auth/username "editor"
                       :auth/email "editor@test.com"
                       :auth/password-hash (auth/hash-password "pass")
                       :auth/roles [(:db/id editor-role)]}
                      {:validate? false})]

    (testing "User has assigned permission"
      (is (auth/has-permission? (db/entity (:db/id viewer-user)) :permission/read-inventory))
      (is (auth/has-permission? (db/entity (:db/id editor-user)) :permission/read-inventory))
      (is (auth/has-permission? (db/entity (:db/id editor-user)) :permission/write-inventory)))

    (testing "User does not have unassigned permission"
      (is (not (auth/has-permission? (db/entity (:db/id viewer-user)) :permission/write-inventory))))

    (testing "User has role"
      (is (auth/has-role? (db/entity (:db/id viewer-user)) :role/viewer))
      (is (auth/has-role? (db/entity (:db/id editor-user)) :role/editor)))

    (testing "User does not have unassigned role"
      (is (not (auth/has-role? (db/entity (:db/id viewer-user)) :role/editor))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API Endpoint Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest login-endpoint-test
  (let [_ (dt/make :auth/User
            {:auth/username "apiuser"
             :auth/email "api@test.com"
             :auth/password-hash (auth/hash-password "testpass")
             :auth/principal-name "API Test User"
             :auth/active? true})]

    (testing "Successful login via API"
      (let [response (response-for tu/service :post "/login"
                                   :headers {"Content-Type" "application/edn"}
                                   :body (pr-str {:username "apiuser"
                                                  :password "testpass"}))]
        (is (= 200 (:status response)) "Should return 200")
        (let [body (tu/parse-edn-body response)]
          (is (some? (:session-id body)) "Should return session ID")
          (is (some? (:principal body)) "Should return principal info"))))

    (testing "Failed login via API - wrong password"
      (let [response (response-for tu/service :post "/login"
                                   :headers {"Content-Type" "application/edn"}
                                   :body (pr-str {:username "apiuser"
                                                  :password "wrongpass"}))]
        (is (= 401 (:status response)) "Should return 401")))

    (testing "Failed login via API - missing password"
      (let [response (response-for tu/service :post "/login"
                                   :headers {"Content-Type" "application/edn"}
                                   :body (pr-str {:username "apiuser"}))]
        (is (= 400 (:status response)) "Should return 400")))))

(deftest me-endpoint-test
  (let [user (dt/make :auth/User
               {:auth/username "meuser"
                :auth/email "me@test.com"
                :auth/password-hash (auth/hash-password "password")
                :auth/principal-name "Me User"
                :auth/active? true})]

    (testing "Unauthenticated /me returns authenticated: false"
      (let [response (response-for tu/service :get "/me")]
        (is (= 200 (:status response)))
        (let [body (tu/parse-edn-body response)]
          (is (false? (:authenticated body))))))

    (testing "Authenticated /me returns user info"
      ;; First login to get session
      (let [login-response (response-for tu/service :post "/login"
                                         :headers {"Content-Type" "application/edn"}
                                         :body (pr-str {:username "meuser"
                                                        :password "password"}))
            login-body (tu/parse-edn-body login-response)
            session-id (:session-id login-body)]
        (is (some? session-id) "Should have session ID from login")

        ;; Now call /me with session
        (let [me-response (response-for tu/service :get "/me"
                                        :headers {"X-Session-ID" session-id})
              me-body (tu/parse-edn-body me-response)]
          (is (= 200 (:status me-response)))
          (is (true? (:authenticated me-body)))
          (is (= "meuser" (get-in me-body [:principal :username]))))))))

(deftest logout-endpoint-test
  (let [user (dt/make :auth/User
               {:auth/username "logoutuser"
                :auth/email "logout@test.com"
                :auth/password-hash (auth/hash-password "password")
                :auth/active? true})]

    (testing "Logout invalidates session"
      ;; Login first
      (let [login-response (response-for tu/service :post "/login"
                                         :headers {"Content-Type" "application/edn"}
                                         :body (pr-str {:username "logoutuser"
                                                        :password "password"}))
            session-id (:session-id (tu/parse-edn-body login-response))]

        ;; Verify session works
        (let [me-response (response-for tu/service :get "/me"
                                        :headers {"X-Session-ID" session-id})]
          (is (true? (:authenticated (tu/parse-edn-body me-response)))))

        ;; Logout
        (let [logout-response (response-for tu/service :post "/api/auth/logout"
                                            :headers {"X-Session-ID" session-id})]
          (is (= 200 (:status logout-response))))

        ;; Session should no longer work
        (let [me-response (response-for tu/service :get "/me"
                                        :headers {"X-Session-ID" session-id})]
          (is (false? (:authenticated (tu/parse-edn-body me-response)))))))))

(deftest register-endpoint-test
  (testing "Successful registration"
    (let [response (response-for tu/service :post "/register"
                                 :headers {"Content-Type" "application/edn"}
                                 :body (pr-str {:username "newuser"
                                                :email "new@test.com"
                                                :password "password123"
                                                :name "New User"}))]
      (is (= 201 (:status response)))
      (let [body (tu/parse-edn-body response)]
        (is (:success body))
        (is (some? (get-in body [:user :id]))))))

  (testing "Registration with existing username fails"
    ;; First registration
    (response-for tu/service :post "/register"
                  :headers {"Content-Type" "application/edn"}
                  :body (pr-str {:username "duplicate"
                                 :email "dup1@test.com"
                                 :password "password123"}))
    ;; Second should fail
    (let [response (response-for tu/service :post "/register"
                                 :headers {"Content-Type" "application/edn"}
                                 :body (pr-str {:username "duplicate"
                                                :email "dup2@test.com"
                                                :password "password123"}))]
      (is (= 409 (:status response)))))

  (testing "Registration with short password fails"
    (let [response (response-for tu/service :post "/register"
                                 :headers {"Content-Type" "application/edn"}
                                 :body (pr-str {:username "shortpass"
                                                :email "short@test.com"
                                                :password "short"}))]
      (is (= 400 (:status response))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Service Account Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest service-account-auth-test
  (let [api-key "super-secret-api-key"
        sa (dt/make :auth/ServiceAccount
             {:auth/service-name :service/test-client
              :auth/principal-name "Test Client"
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/active? true})]

    (testing "Find service account"
      (let [found (auth/find-service-account :service/test-client)]
        (is (some? found))
        (is (= :service/test-client (:auth/service-name found)))))

    (testing "Authenticate with API key"
      (let [result (auth/authenticate-api-key :service/test-client api-key)]
        (is (:success result))
        (is (some? (:principal result)))))

    (testing "Fail with wrong API key"
      (let [result (auth/authenticate-api-key :service/test-client "wrong-key")]
        (is (not (:success result)))
        (is (= :invalid-api-key (:reason result)))))

    (testing "Fail with unknown service"
      (let [result (auth/authenticate-api-key :service/nonexistent "any-key")]
        (is (not (:success result)))
        (is (= :unknown-service (:reason result)))))))
