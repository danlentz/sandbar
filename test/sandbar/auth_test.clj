(ns sandbar.auth-test
  "Test suite for the Auth domain model.

   Who are you? What are you allowed to do? These are the eternal questions
   of access control. This suite tests users, groups, service accounts, roles,
   and permissions - the cast of characters in our security theater.

   This test suite validates:
   - Principal hierarchy (User, Group, ServiceAccount)
   - Role and Permission definitions
   - Role inheritance
   - Group membership

   Schema defined in schema/auth.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu])
  (:import [java.util Date]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "auth-test"
                                              :extra-schema [:auth]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;
;; The principal family tree: User, Group, and ServiceAccount all inherit
;; from the abstract Principal. It's like a monarchy, but with passwords.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest auth-class-hierarchy-test
  (testing "Principal is abstract"
    ;; You can't just be "a principal" - that's an identity crisis
    (is (some? (db/entity :auth/Principal)) "Principal class should exist")
    (is (dt/abstract? :auth/Principal) "Principal should be abstract"))

  (testing "Principal subtypes exist and are concrete"
    (is (some? (db/entity :auth/User)) "User class should exist")
    (is (some? (db/entity :auth/Group)) "Group class should exist")
    (is (some? (db/entity :auth/ServiceAccount)) "ServiceAccount class should exist")

    ;; These are real, instantiable things
    (is (not (dt/abstract? :auth/User)) "User should be concrete")
    (is (not (dt/abstract? :auth/Group)) "Group should be concrete")
    (is (not (dt/abstract? :auth/ServiceAccount)) "ServiceAccount should be concrete"))

  (testing "Principal inheritance"
    (is (dt/subclass-of? :auth/Principal :auth/User) "User extends Principal")
    (is (dt/subclass-of? :auth/Principal :auth/Group) "Group extends Principal")
    (is (dt/subclass-of? :auth/Principal :auth/ServiceAccount) "ServiceAccount extends Principal"))

  (testing "Role and Permission are separate concerns"
    (is (some? (db/entity :auth/Role)) "Role class should exist")
    (is (some? (db/entity :auth/Permission)) "Permission class should exist")
    (is (not (dt/subclass-of? :auth/Principal :auth/Role)) "Role is not a Principal")
    (is (not (dt/abstract? :auth/Role)) "Role is concrete")
    (is (not (dt/abstract? :auth/Permission)) "Permission is concrete")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Inheritance Tests
;;
;; Common properties flow down from Principal. Special properties stay
;; with their owners. It's all very proper.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest auth-property-inheritance-test
  (testing "Principal properties inherited by subtypes"
    (let [common-slots #{:auth/principal-name :auth/roles :auth/active?
                         :auth/created-at :auth/metadata}]
      (doseq [principal-type [:auth/User :auth/Group :auth/ServiceAccount]]
        (let [slots (dt/slots-of principal-type)]
          (doseq [slot common-slots]
            (is (contains? slots slot)
                (str slot " should be inherited by " principal-type)))))))

  (testing "User-specific properties"
    (let [user-slots (dt/slots-of :auth/User)]
      (is (contains? user-slots :auth/username) "User has username")
      (is (contains? user-slots :auth/email) "User has email")
      (is (contains? user-slots :auth/password-hash) "User has password hash")
      (is (contains? user-slots :auth/last-login) "User tracks last login")
      (is (contains? user-slots :auth/failed-logins) "User tracks failed logins")
      (is (contains? user-slots :auth/groups) "User can be in groups")))

  (testing "Group-specific properties"
    (let [group-slots (dt/slots-of :auth/Group)]
      (is (contains? group-slots :auth/group-name) "Group has name")
      (is (contains? group-slots :auth/members) "Group has members")
      (is (contains? group-slots :auth/parent-group) "Group can be nested")))

  (testing "ServiceAccount-specific properties"
    (let [sa-slots (dt/slots-of :auth/ServiceAccount)]
      (is (contains? sa-slots :auth/service-name) "SA has service name")
      (is (contains? sa-slots :auth/api-key-hash) "SA has API key")
      (is (contains? sa-slots :auth/owner) "SA has an owner")
      (is (contains? sa-slots :auth/expires-at) "SA can expire")))

  (testing "Role properties"
    (let [role-slots (dt/slots-of :auth/Role)]
      (is (contains? role-slots :auth/role-name) "Role has name")
      (is (contains? role-slots :auth/role-label) "Role has label")
      (is (contains? role-slots :auth/permissions) "Role has permissions")
      (is (contains? role-slots :auth/inherits-from) "Role can inherit")))

  (testing "Permission properties"
    (let [perm-slots (dt/slots-of :auth/Permission)]
      (is (contains? perm-slots :auth/permission-name) "Permission has name")
      (is (contains? perm-slots :auth/resource-type) "Permission has resource type")
      (is (contains? perm-slots :auth/action) "Permission has action"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User Creation Tests
;;
;; Users: the humans who will inevitably forget their passwords.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-user-test
  (testing "Cannot instantiate abstract Principal"
    (is (thrown? Exception
          (dt/make :auth/Principal {:auth/principal-name "Nobody"}))))

  (testing "Create a basic user"
    (let [user (dt/make :auth/User
                 {:auth/username "zorp"
                  :auth/email "zorp@pluto.net"
                  :auth/principal-name "Zorp the Footwear Merchant"
                  :auth/password-hash "bcrypt$verysecure"
                  :auth/active? true
                  :auth/created-at (Date.)})]
      (is (some? user) "User created")
      (is (dt/instance-of? :auth/User user) "Is a User")
      (is (dt/instance-of? :auth/Principal user) "Is also a Principal")
      (is (= "zorp" (:auth/username user)))
      (is (true? (:auth/active? user)))))

  (testing "Create a user with failed login tracking"
    ;; For when someone's trying to brute force their way into Zorp's account
    (let [locked-user (dt/make :auth/User
                        {:auth/username "suspicious"
                         :auth/email "hacker@definitely-not-suspicious.com"
                         :auth/password-hash "bcrypt$whatever"
                         :auth/active? true
                         :auth/failed-logins 5
                         :auth/locked-until (Date. (+ (System/currentTimeMillis) 3600000))})]
      (is (= 5 (:auth/failed-logins locked-user)))
      (is (some? (:auth/locked-until locked-user)) "Account is locked"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Group Tests
;;
;; Groups: because managing permissions one user at a time is madness.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-group-test
  (testing "Create a group"
    (let [group (dt/make :auth/Group
                  {:auth/group-name :group/warehouse-staff
                   :auth/principal-name "Warehouse Staff"
                   :auth/active? true
                   :auth/created-at (Date.)})]
      (is (some? group) "Group created")
      (is (dt/instance-of? :auth/Group group) "Is a Group")
      (is (dt/instance-of? :auth/Principal group) "Groups are Principals too")))

  (testing "Create a group with members"
    ;; Note: members are added via ref, validation is skipped for many-valued refs
    (let [user1 (dt/make :auth/User
                  {:auth/username "worker1"
                   :auth/email "worker1@pluto.net"
                   :auth/password-hash "hash1"})
          user2 (dt/make :auth/User
                  {:auth/username "worker2"
                   :auth/email "worker2@pluto.net"
                   :auth/password-hash "hash2"})
          group (dt/make :auth/Group
                  {:auth/group-name :group/team-alpha
                   :auth/principal-name "Team Alpha"
                   :auth/members [(:db/id user1) (:db/id user2)]}
                  {:validate? false})]
      (is (= 2 (count (:auth/members group))) "Group has 2 members")))

  (testing "Create nested groups"
    ;; Groups within groups - it's hierarchies all the way down
    (let [parent (dt/make :auth/Group
                   {:auth/group-name :group/all-staff
                    :auth/principal-name "All Staff"})
          child (dt/make :auth/Group
                  {:auth/group-name :group/sales-team
                   :auth/principal-name "Sales Team"
                   :auth/parent-group (:db/id parent)})]
      ;; Note: refs come back as {:db/id ...} maps
      (is (= (:db/id parent) (:db/id (:auth/parent-group child)))
          "Child knows its parent"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ServiceAccount Tests
;;
;; For when robots need access too. No password resets, just API keys.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-service-account-test
  (let [owner (dt/make :auth/User
                {:auth/username "admin"
                 :auth/email "admin@pluto.net"
                 :auth/password-hash "adminhash"})]

    (testing "Create a service account"
      (let [sa (dt/make :auth/ServiceAccount
                 {:auth/service-name :service/inventory-sync
                  :auth/principal-name "Inventory Sync Service"
                  :auth/api-key-hash "sha256$longhash"
                  :auth/owner (:db/id owner)
                  :auth/active? true
                  :auth/created-at (Date.)})]
        (is (some? sa) "Service account created")
        (is (dt/instance-of? :auth/ServiceAccount sa))
        (is (dt/instance-of? :auth/Principal sa) "SAs are Principals")))

    (testing "Create an expiring service account"
      (let [temp-sa (dt/make :auth/ServiceAccount
                      {:auth/service-name :service/temp-migration
                       :auth/principal-name "Temporary Migration Script"
                       :auth/api-key-hash "sha256$tempkey"
                       :auth/owner (:db/id owner)
                       :auth/expires-at (Date. (+ (System/currentTimeMillis) 86400000))})]
        (is (some? (:auth/expires-at temp-sa)) "Has expiration date")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Role and Permission Tests
;;
;; Roles bundle permissions. Permissions grant specific actions.
;; Together, they answer "can this user do this thing?"
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest role-permission-test
  (testing "Create permissions"
    (let [read-orders (dt/make :auth/Permission
                        {:auth/permission-name :permission/read-orders
                         :auth/permission-label "Read Orders"
                         :auth/resource-type :order
                         :auth/action :read})
          write-orders (dt/make :auth/Permission
                         {:auth/permission-name :permission/write-orders
                          :auth/permission-label "Write Orders"
                          :auth/resource-type :order
                          :auth/action :write})
          delete-orders (dt/make :auth/Permission
                          {:auth/permission-name :permission/delete-orders
                           :auth/permission-label "Delete Orders"
                           :auth/resource-type :order
                           :auth/action :delete})]
      (is (some? read-orders))
      (is (= :read (:auth/action read-orders)))
      (is (= :order (:auth/resource-type read-orders)))))

  (testing "Create a role with permissions"
    (let [read-perm (dt/make :auth/Permission
                      {:auth/permission-name :permission/view-inventory
                       :auth/resource-type :inventory
                       :auth/action :read})
          write-perm (dt/make :auth/Permission
                       {:auth/permission-name :permission/edit-inventory
                        :auth/resource-type :inventory
                        :auth/action :write})
          role (dt/make :auth/Role
                 {:auth/role-name :role/inventory-manager
                  :auth/role-label "Inventory Manager"
                  :auth/permissions [(:db/id read-perm) (:db/id write-perm)]}
                 {:validate? false})]
      (is (some? role) "Role created")
      (is (= 2 (count (:auth/permissions role))) "Role has 2 permissions")))

  (testing "Create roles with inheritance"
    ;; Admin inherits from viewer, because admins can do everything viewers can
    (let [view-perm (dt/make :auth/Permission
                      {:auth/permission-name :permission/view-reports
                       :auth/resource-type :report
                       :auth/action :read})
          admin-perm (dt/make :auth/Permission
                       {:auth/permission-name :permission/admin-reports
                        :auth/resource-type :report
                        :auth/action :admin})
          viewer-role (dt/make :auth/Role
                        {:auth/role-name :role/report-viewer
                         :auth/role-label "Report Viewer"
                         :auth/permissions [(:db/id view-perm)]}
                        {:validate? false})
          admin-role (dt/make :auth/Role
                       {:auth/role-name :role/report-admin
                        :auth/role-label "Report Admin"
                        :auth/permissions [(:db/id admin-perm)]
                        :auth/inherits-from [(:db/id viewer-role)]}
                       {:validate? false})]
      (is (= 1 (count (:auth/inherits-from admin-role)))
          "Admin inherits from viewer"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; User-Role Assignment Tests
;;
;; Putting it together: users get roles, roles have permissions.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest user-role-assignment-test
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
        editor-role (dt/make :auth/Role
                      {:auth/role-name :role/footwear-editor
                       :auth/role-label "Footwear Editor"
                       :auth/permissions [(:db/id read-perm) (:db/id write-perm)]}
                      {:validate? false})]

    (testing "Create a user with roles"
      (let [user (dt/make :auth/User
                   {:auth/username "zorp"
                    :auth/email "zorp@pluto.net"
                    :auth/password-hash "hash"
                    :auth/roles [(:db/id viewer-role) (:db/id editor-role)]}
                   {:validate? false})]
        (is (= 2 (count (:auth/roles user))) "User has 2 roles")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest query-auth-entities-test
  ;; Note: The test fixture creates a test user automatically for API auth
  (let [initial-user-count (count (dt/all-instances-of :auth/User))
        _ (dt/make :auth/User {:auth/username "user1" :auth/email "u1@test.com" :auth/password-hash "h"})
        _ (dt/make :auth/User {:auth/username "user2" :auth/email "u2@test.com" :auth/password-hash "h"})
        _ (dt/make :auth/ServiceAccount {:auth/service-name :service/test :auth/api-key-hash "k"})]

    (testing "Find all principals"
      (let [all-principals (dt/all-instances-of :auth/Principal)]
        (is (= (+ initial-user-count 2 1) (count all-principals))
            "Should find initial users + 2 new users + 1 SA")))

    (testing "Find only users"
      (let [users (dt/all-instances-of :auth/User)]
        (is (= (+ initial-user-count 2) (count users))
            "Should find initial users + 2 new users")))

    (testing "Find service accounts"
      (let [sas (dt/all-instances-of :auth/ServiceAccount)]
        (is (= 1 (count sas)) "Should find 1 SA")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST API Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest auth-api-test
  (testing "Principal class visible via API"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/auth/Principal")]
      (is (= 200 status))
      (is (= :auth/Principal (:class body)))
      (is (true? (:abstract? body)))))

  (testing "Principal subclasses visible"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/auth/Principal/subclasses")]
      (is (= 200 status))
      (is (contains? (set (:subclasses body)) :auth/User))
      (is (contains? (set (:subclasses body)) :auth/Group))
      (is (contains? (set (:subclasses body)) :auth/ServiceAccount))))

  (testing "User slots include inherited and direct"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/auth/User/slots")]
      (is (= 200 status))
      ;; API returns slot objects with :ident key
      (let [slot-idents (set (map :ident (:slots body)))]
        ;; Inherited from Principal
        (is (contains? slot-idents :auth/roles))
        ;; Direct on User
        (is (contains? slot-idents :auth/username))))))
