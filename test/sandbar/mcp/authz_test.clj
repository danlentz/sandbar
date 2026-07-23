(ns sandbar.mcp.authz-test
  "Decision-table tests for the S5 principal-check-at-dispatch gate core
   (`sandbar.mcp.authz`).

   Most of the suite is DB-FREE — every principal is a plain projection map
   shaped like the wire projection (`:auth/roles` is a vector of maps carrying
   `:auth/role-name`).  It pins the three load-bearing rulings:

   - EXEMPT-FIRST ordering (S5-PLAN §2.2): `initialize` +
     `notifications/initialized` allow BEFORE any scope reasoning — even for an
     unscoped principal that every other method denies.
   - AP-2 UNSCOPED-DENY (S5-PLAN §1.3): an authenticated but role-less
     principal is denied every non-exempt method with
     `:unscoped-principal-denied`.
   - `tasks/cancel` MUTATING classification (CX2-CONCERN-7 / AP-9): it is
     denied for a read-only principal while `tasks/list`/`tasks/get` are reads.

   PLUS one DB-BACKED projection-shape group (`projection-shape-*`).  The pure
   fixtures are literal Clojure maps where `map?` is TRUE; the LIVE wire hands
   `authenticate-api-key` → `find-service-account`'s `(db/entity eid)`, whose
   ref-many `:auth/roles` are `datomic.query.EntityMap` objects for which
   `map?` is FALSE.  A literal-map-only suite gives false confidence in exactly
   the fixture≠wire dimension the ceremony-#4 breach was about — so a seeded
   ServiceAccount authenticated the way the wire mints it must yield
   `roles->capabilities => #{:read-only}` and a SCOPED (not unscoped) scope.
   This closes the coverage gap the revision-round finding named.

   Per S5-PLAN.md §2.2 item 2 + §6 build sequence."
  (:require [clojure.test        :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.authz   :as authz]
            [sandbar.test-util   :as tu]
            [sandbar.util.auth   :as auth]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Principal projections — wire-shaped, DB-free.
;;
;; `authenticate-api-key` returns an entity map whose `:auth/roles` are entity
;; maps carrying `:auth/role-name`.  These literals reproduce that shape so the
;; pure classifier can be exercised without a database.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private read-only-principal
  "A ServiceAccount carrying the `:read-only` capability role."
  {:db/id             -10
   :auth/service-name :codex-review
   :auth/active?      true
   :auth/roles        [{:auth/role-name auth/read-only-role
                        :auth/role-label "read-only"}]})

(def ^:private scoped-principal
  "A ServiceAccount carrying a capability-bearing NON-read-only role (a fully
   scoped writer) — role-name is illustrative; only its presence (non-empty
   capability set) matters to the gate."
  {:db/id             -11
   :auth/service-name :ops-writer
   :auth/active?      true
   :auth/roles        [{:auth/role-name  :operator
                        :auth/role-label "operator"}]})

(def ^:private unscoped-principal
  "An authenticated ServiceAccount with NO roles — the AP-2 fail-closed
   subject."
  {:db/id             -12
   :auth/service-name :legacy-integration
   :auth/active?      true
   :auth/roles        []})

(def ^:private unscoped-principal-nil-roles
  "Authenticated principal whose `:auth/roles` key is absent entirely — must
   classify unscoped exactly like the empty-vector case."
  {:db/id             -13
   :auth/service-name :no-roles-at-all
   :auth/active?      true})

;; Every method name in `protocol/method-handlers` (the 13-method census).
(def ^:private all-methods
  ["initialize" "notifications/initialized"
   "tools/list" "tools/call"
   "resources/list" "resources/read" "resources/subscribe"
   "resources/unsubscribe"
   "prompts/list" "prompts/get"
   "tasks/list" "tasks/get" "tasks/cancel"])

(def ^:private exempt-methods
  ["initialize" "notifications/initialized"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; roles->capabilities — pure, DB-free derivation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest roles->capabilities-derives-role-names
  (testing "read-only principal's capability set contains :read-only"
    (is (= #{auth/read-only-role} (authz/roles->capabilities read-only-principal))))
  (testing "scoped writer's capability set contains its role-name"
    (is (= #{:operator} (authz/roles->capabilities scoped-principal))))
  (testing "role-less principal derives the EMPTY capability set"
    (is (= #{} (authz/roles->capabilities unscoped-principal)))
    (is (= #{} (authz/roles->capabilities unscoped-principal-nil-roles))))
  (testing "nil principal derives the empty set (no NPE)"
    (is (= #{} (authz/roles->capabilities nil))))
  (testing "bare-eid roles contribute nothing — the keep-based derivation reads
            :auth/role-name by keyword lookup, and (:auth/role-name <long>) is
            nil, so a bare eid drops out with no map? guard needed"
    (is (= #{} (authz/roles->capabilities {:auth/roles [42 99]}))))
  (testing "mixed entity + bare-eid roles yield only the resolvable role-names"
    (is (= #{:read-only}
           (authz/roles->capabilities
             {:auth/roles [{:auth/role-name :read-only} 42]})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; principal->scope — nil → ::unrestricted; role-less → :scope/unscoped? true
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest principal->scope-nil-is-unrestricted
  (testing "nil principal → ::unrestricted (the legacy/local full-access path)"
    (is (= ::authz/unrestricted (authz/principal->scope nil)))))

(deftest principal->scope-read-only
  (let [scope (authz/principal->scope read-only-principal)]
    (testing "read-only principal is scoped, read-only, NOT unscoped"
      (is (map? scope))
      (is (true?  (:scope/read-only? scope)))
      (is (false? (:scope/unscoped?  scope)))
      (is (contains? (:scope/capabilities scope) auth/read-only-role)))
    (testing "contexts default to #{:ctx/public} (inert until S6)"
      (is (= #{:ctx/public} (:scope/contexts scope))))))

(deftest principal->scope-scoped-writer
  (let [scope (authz/principal->scope scoped-principal)]
    (testing "a role-bearing non-read-only principal is scoped and writable"
      (is (false? (:scope/read-only? scope)))
      (is (false? (:scope/unscoped?  scope))))))

(deftest principal->scope-unscoped-ap2
  (testing "AP-2: an authenticated role-less principal is :scope/unscoped? true"
    (doseq [p [unscoped-principal unscoped-principal-nil-roles]]
      (let [scope (authz/principal->scope p)]
        (is (true?  (:scope/unscoped? scope)) (str "unscoped for " (:auth/service-name p)))
        (is (false? (:scope/read-only? scope)))
        (is (= #{} (:scope/capabilities scope)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; method->family — the 13-method census; tasks/cancel MUTATING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest method->family-census
  (testing "lifecycle methods are :exempt"
    (is (= :exempt (authz/method->family "initialize")))
    (is (= :exempt (authz/method->family "notifications/initialized"))))
  (testing "tools/call is the delegated :tools-call sentinel (Shape A′)"
    (is (= :tools-call (authz/method->family "tools/call"))))
  (testing "read/introspection methods are :read"
    (doseq [m ["tools/list" "resources/list" "resources/read"
               "resources/subscribe" "resources/unsubscribe"
               "prompts/list" "prompts/get" "tasks/list" "tasks/get"]]
      (is (= :read (authz/method->family m)) (str m " must be :read"))))
  (testing "tasks/cancel is MUTATING (CX2-CONCERN-7 / AP-9)"
    (is (= :mutating (authz/method->family "tasks/cancel"))))
  (testing "an UNKNOWN method is :mutating (deny-by-default)"
    (is (= :mutating (authz/method->family "resources/frobnicate")))
    (is (= :mutating (authz/method->family "totally/unknown")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; method-scope-decision — the decision table, exempt-first order
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- decide
  "Decide `method` for `principal` via the full principal->scope → decision
   pipeline (the composition the dispatch gate runs)."
  [principal method]
  (authz/method-scope-decision method (authz/principal->scope principal)))

(deftest exempt-methods-allow-for-every-scope-FIRST
  (testing "exempt methods ALLOW regardless of scope — even for an unscoped
            principal every other method denies (exempt-first ordering)"
    (doseq [m exempt-methods
            p [nil read-only-principal scoped-principal
               unscoped-principal unscoped-principal-nil-roles]]
      (is (authz/allow? (decide p m))
          (str m " must allow for principal " (:auth/service-name p)))
      (is (not (authz/deny? (decide p m)))))))

(deftest nil-principal-unrestricted-allows-everything
  (testing "::unrestricted (nil-principal in-process path) allows every method,
            including tasks/cancel and unknown methods"
    (doseq [m (conj all-methods "some/unknown-method")]
      (is (authz/allow? (decide nil m))
          (str m " must allow on the nil-principal path")))))

(deftest ap2-unscoped-principal-denies-every-non-exempt-method
  (testing "AP-2: an authenticated role-less principal is denied EVERY
            non-exempt method with :unscoped-principal-denied — checked BEFORE
            family policy so the deny is uniform, not per-family"
    (doseq [p [unscoped-principal unscoped-principal-nil-roles]
            m (remove (set exempt-methods) all-methods)]
      (let [d (decide p m)]
        (is (authz/deny? d) (str m " must deny for the unscoped principal"))
        (is (= authz/reason-unscoped-denied (:reason d))
            (str m " deny reason must be :unscoped-principal-denied; got "
                 (pr-str d))))))
  (testing "the unscoped deny reason keyword is exactly :unscoped-principal-denied"
    (is (= :unscoped-principal-denied authz/reason-unscoped-denied))))

(deftest ap2-unscoped-deny-does-not-fire-for-exempt
  (testing "exempt methods are NOT unscoped-denied (order proof: exempt clause
            precedes the unscoped clause)"
    (doseq [m exempt-methods]
      (let [d (decide unscoped-principal m)]
        (is (authz/allow? d))
        (is (not= authz/reason-unscoped-denied (:reason d)))))))

(deftest read-only-principal-family-policy
  (testing "read-only principal: READ-family methods ALLOW"
    (doseq [m ["tools/list" "resources/list" "resources/read"
               "resources/subscribe" "resources/unsubscribe"
               "prompts/list" "prompts/get" "tasks/list" "tasks/get"]]
      (is (authz/allow? (decide read-only-principal m))
          (str m " must allow for a read-only principal"))))
  (testing "read-only principal: tasks/cancel (MUTATING) is DENIED with the
            read-only-forbidden-mutation reason"
    (let [d (decide read-only-principal "tasks/cancel")]
      (is (authz/deny? d))
      (is (= authz/reason-read-only-forbidden-mutation (:reason d))
          (str "got " (pr-str d)))))
  (testing "read-only principal: tools/call is NOT decided here — the dispatch
            gate delegates its verb-class check to handle-call (Shape A′), so
            the decision core ALLOWS it (handle-call denies mutations)"
    (is (authz/allow? (decide read-only-principal "tools/call")))))

(deftest scoped-writer-family-policy
  (testing "a scoped non-read-only principal may run MUTATING methods
            (tasks/cancel) and every read"
    (is (authz/allow? (decide scoped-principal "tasks/cancel")))
    (is (authz/allow? (decide scoped-principal "resources/read")))
    (is (authz/allow? (decide scoped-principal "tools/call")))))

(deftest unknown-method-denied-for-scoped-non-writer-is-mutating
  (testing "an unknown method classifies :mutating, so a read-only principal is
            denied it (deny-by-default) while a scoped writer may run it"
    (is (authz/deny?  (decide read-only-principal "future/unknown-method")))
    (is (= authz/reason-read-only-forbidden-mutation
           (:reason (decide read-only-principal "future/unknown-method"))))
    (is (authz/allow? (decide scoped-principal "future/unknown-method")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Inert-until-S6 reason keywords are RESERVED but NEVER fired by S5
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest inert-s6-reasons-reserved-with-exact-keywords
  (testing "the five compartment/destination reasons exist with exact keywords
            (S9 compile-guarded tests pin these)"
    (is (= :out-of-scope-destination      authz/reason-out-of-scope-destination))
    (is (= :out-of-scope-resource         authz/reason-out-of-scope-resource))
    (is (= :out-of-scope-subscribe        authz/reason-out-of-scope-subscribe))
    (is (= :subscribe-compartment-forbidden authz/reason-subscribe-compartment-forbidden))
    (is (= :read-compartment-forbidden    authz/reason-read-compartment-forbidden))))

(deftest inert-s6-reasons-never-returned-by-s5-decisions
  (testing "no method × principal-scope combination returns any inert S6 reason
            (they are reserved, never fired in S5)"
    (let [inert #{authz/reason-out-of-scope-destination
                  authz/reason-out-of-scope-resource
                  authz/reason-out-of-scope-subscribe
                  authz/reason-subscribe-compartment-forbidden
                  authz/reason-read-compartment-forbidden}]
      (doseq [p [nil read-only-principal scoped-principal
                 unscoped-principal unscoped-principal-nil-roles]
              m (conj all-methods "unknown/method")]
        (let [d (decide p m)]
          (when (authz/deny? d)
            (is (not (contains? inert (:reason d)))
                (str "S5 must never fire an inert S6 reason; " m
                     " / " (:auth/service-name p) " → " (pr-str d)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; deny->jsonrpc-error — envelope shape + notification nil-return
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest deny->jsonrpc-error-request-shape
  (testing "a REQUEST deny mirrors read-only-denied's :data keys
            (:reason + :method, plus :role when scoped) as invalid-params"
    (let [scope (authz/principal->scope unscoped-principal)
          d     (authz/method-scope-decision "tasks/cancel" scope)
          resp  (authz/deny->jsonrpc-error 7 "tasks/cancel" d scope)]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 7 (:id resp)))
      (is (= -32602 (-> resp :error :code)) "invalid-params, like the tools-path deny")
      (is (string? (-> resp :error :message)))
      (is (= :unscoped-principal-denied (-> resp :error :data :reason)))
      (is (= "tasks/cancel" (-> resp :error :data :method)))))
  (testing "a read-only mutation deny carries the role in :data"
    (let [scope (authz/principal->scope read-only-principal)
          d     (authz/method-scope-decision "tasks/cancel" scope)
          resp  (authz/deny->jsonrpc-error 9 "tasks/cancel" d scope)]
      (is (= :read-only-principal-forbidden-mutation (-> resp :error :data :reason)))
      (is (= auth/read-only-role (-> resp :error :data :role))))))

(deftest deny->jsonrpc-error-notification-returns-nil
  (testing "a NOTIFICATION deny (nil id) returns nil — JSON-RPC notifications
            get no response; the gate swallows, never answers"
    (let [scope (authz/principal->scope unscoped-principal)
          d     (authz/method-scope-decision "tasks/cancel" scope)]
      (is (nil? (authz/deny->jsonrpc-error nil "tasks/cancel" d scope))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB-BACKED projection-shape — the fixture≠wire coverage gap (revision round 1)
;;
;; The literal-map fixtures above are `map?`-true; the LIVE wire projection is a
;; `datomic.query.EntityMap` whose ref-many `:auth/roles` are ALSO EntityMaps
;; for which `map?` is FALSE.  A pre-fix `(filter map?)` derivation passed every
;; literal-map assertion above while projecting every real read-only
;; ServiceAccount as unscoped (fail-CLOSED over-denial → codex-review offline).
;; These tests mint a principal EXACTLY the way the wire does
;; (`authenticate-api-key` → `find-service-account` → `(db/entity eid)`) so the
;; two shapes cannot silently diverge — the same lesson the ceremony-#4 breach
;; taught, applied at the unit layer.
;;
;; The in-memory DB fixture wraps every test in this ns; the pure tests above
;; never touch it, so an empty per-test DB is harmless to them.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "mcp-authz-projection-shape-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

(def ^:private db-service-name :codex-review-authz)
(def ^:private db-api-key "authz-projection-shape-not-a-real-key-7d2e")

(defn- seed-read-only-sa!
  "Seed a `:read-only` role + an ACTIVE ServiceAccount whose api-key hashes to
   `db-api-key`, exactly as the wire fixture does — so `authenticate-api-key`
   returns the REAL `EntityMap` projection (not a literal map).  Returns nothing;
   the SA is looked up by `db-service-name`."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Fixture read-only (authz projection)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name db-service-name
              :auth/api-key-hash (auth/hash-password db-api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})))

(defn- wire-principal
  "The principal projection the LIVE dispatch path receives — the `:principal`
   of a successful `authenticate-api-key`, i.e. a `datomic.query.EntityMap`."
  []
  (let [{:keys [success principal]} (auth/authenticate-api-key db-service-name db-api-key)]
    (is (true? success) "fixture SA must authenticate")
    principal))

(deftest projection-shape-entity-map-roles-are-not-map?
  (testing "the live projection is the EntityMap shape that map? REJECTS — the
            exact reason the pre-fix (filter map?) derivation silently failed"
    (seed-read-only-sa!)
    (let [principal (wire-principal)
          roles     (:auth/roles principal)
          a-role    (first roles)]
      (is (false? (map? principal))
          "principal is a datomic.query.EntityMap, not an IPersistentMap")
      (is (some? a-role) "the SA carries at least one role")
      (is (false? (map? a-role))
          "each :auth/roles element is an EntityMap — map? is FALSE for it")
      (is (= auth/read-only-role (:auth/role-name a-role))
          "yet keyword lookup resolves :auth/role-name on the EntityMap"))))

(deftest projection-shape-roles->capabilities-derives-read-only
  (testing "roles->capabilities on the REAL wire principal yields #{:read-only}
            (pre-fix it yielded #{} because map? rejected every EntityMap role)"
    (seed-read-only-sa!)
    (is (= #{auth/read-only-role}
           (authz/roles->capabilities (wire-principal))))))

(deftest projection-shape-principal->scope-is-scoped-read-only
  (testing "principal->scope on the REAL wire principal is SCOPED + read-only,
            NOT unscoped — so the dispatch gate permits its reads and denies
            only its mutations (pre-fix it was :scope/unscoped? true ⇒ every
            method incl. reads denied :unscoped-principal-denied)"
    (seed-read-only-sa!)
    (let [scope (authz/principal->scope (wire-principal))]
      (is (map? scope))
      (is (true?  (:scope/read-only? scope)))
      (is (false? (:scope/unscoped?  scope)))
      (is (contains? (:scope/capabilities scope) auth/read-only-role))))
  (testing "and the gate decision matches: a READ allows, tasks/cancel denies
            with read-only-forbidden-mutation (NOT unscoped-principal-denied)"
    (seed-read-only-sa!)
    (let [scope (authz/principal->scope (wire-principal))]
      (is (authz/allow? (authz/method-scope-decision "resources/read" scope)))
      (let [d (authz/method-scope-decision "tasks/cancel" scope)]
        (is (authz/deny? d))
        (is (= authz/reason-read-only-forbidden-mutation (:reason d))
            (str "read-only mutation must be read-only-denied, not unscoped; got "
                 (pr-str d)))))))
