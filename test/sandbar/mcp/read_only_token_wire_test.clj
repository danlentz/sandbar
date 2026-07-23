(ns sandbar.mcp.read-only-token-wire-test
  "WIRE-contract falsification for the read-only MCP token gate — the layer the
   fixture suite in `read_only_token_test.clj` does NOT exercise.

   That suite calls `tools/handle-call` with the principal INJECTED directly.
   That is exactly the blind spot that shipped the ceremony-#4 wire breach
   (bugs/readonly_token_gate_not_enforced_on_live_wire_path_principal_not_threaded_2026_07_03.md):
   the gate enforces when handle-call receives a read-only principal, but over
   the real HTTP path the principal never reached the gate, so mutations ran
   fail-open.

   These tests drive the FULL interceptor chain the connector serves — the
   principal is minted the way the wire mints it (authenticate-api-key via a
   `Authorization: Bearer` header, NOT find-service-account), threaded by the
   real bearer-interceptor → mcp-handler → protocol/dispatch → handle-call:

   1. `wire-enforcement-contract` — over `io.pedestal.test/response-for` (the
      real chain built from `service.routes/routes`): a mutation is DENIED, a
      read is PERMITTED.  Documents the contract end-to-end.

   2. `frozen-connector-stays-reloadable` — the regression guard that would
      have CAUGHT the breach.  Pedestal freezes the interceptor VALUE into the
      routing table at route-expansion (the `defonce` Jetty connector expands
      it once), so a running-JVM `require :reload` only reaches the live path
      if the interceptor's `:enter` calls THROUGH a var.  This test captures
      the interceptor value (as the connector does), redefines the
      identity-threading impl (as a hot-reload does), and asserts the captured
      value observes it.  On HEAD the `:enter` inlines its body → the captured
      value ignores the reload → the wire keeps threading the pre-gate shape
      (context-only `:identity`, no `[:request :identity]`) → FAILS.  With the
      fix (`bearer-enter` split out + `:enter` delegating to it) → PASSES.

   Per decisions/review_gate_runbook_wave1_ratification_fable_rulings_2026_07_02.md
   ruling 8 + the ceremony-#4 breach ADR."
  (:require [cheshire.core       :as json]
            [clojure.test        :refer :all]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic  :as db]
            [sandbar.mcp.auth    :as mcp-auth]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "mcp-read-only-token-wire-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture seed — mint the principal the way the wire does.
;;
;; The Bearer token authenticates through authenticate-api-key (NOT
;; find-service-account), so the projection is the exact one the running server
;; hands the dispatch path.  api-key is a fixture constant; its bcrypt hash is
;; stored so authenticate-api-key's verify-password succeeds.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private api-key "wire-test-not-a-real-key-9f3c")
(def ^:private service-name :codex-review-wire)

(defn- seed-read-only-sa!
  "Seed a :read-only role + a codex-review-style ServiceAccount whose api-key
   hashes to `api-key`.  Returns the Bearer token string `<service>:<key>`."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Fixture read-only (wire)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name service-name
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name service-name) ":" api-key)))

;; ---- AP-2 + fail-closed seeds (T-5b / T-2a) -------------------------------

(def ^:private unroled-service :unroled-sa-wire)
(def ^:private unroled-api-key "wire-test-unroled-key-4a1d")

(defn- seed-unroled-sa!
  "Seed an ACTIVE ServiceAccount carrying NO roles — the authenticated-but-
   role-less principal AP-2 denies at DISPATCH (`roles->capabilities` = ∅ ⇒
   `:scope/unscoped?` ⇒ every non-exempt method refused with
   `:unscoped-principal-denied`).  Authenticates cleanly (require-bearer sees
   `:identity`), so the deny is a dispatch-layer JSON-RPC error, NOT the auth-
   layer 401 — the distinction CX2-CONCERN-8 asked T-5b to pin.  Returns its
   Bearer token."
  []
  (dt/make :auth/ServiceAccount
           {:auth/service-name unroled-service
            :auth/api-key-hash (auth/hash-password unroled-api-key)
            :auth/roles        []
            :auth/active?      true}
           {:validate? false})
  (str (name unroled-service) ":" unroled-api-key))

(def ^:private revoked-service :revoked-sa-wire)
(def ^:private revoked-api-key "wire-test-revoked-key-8c2f")

(defn- seed-inactive-sa!
  "Seed an INACTIVE (`:auth/active? false`) ServiceAccount — its token never
   authenticates (`authenticate-api-key` rejects an inactive SA), so
   require-bearer sees no `:identity` and terminates with 401 BEFORE dispatch.
   Returns its Bearer token (which must fail closed)."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Fixture revoked (wire)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name revoked-service
              :auth/api-key-hash (auth/hash-password revoked-api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      false}
             {:validate? false})
    (str (name revoked-service) ":" revoked-api-key)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 1 — the wire enforcement contract over the FULL interceptor chain
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mcp-post
  "POST a JSON-RPC message to /mcp over the full chain with a Bearer token.
   `Accept: application/json` so the response body is JSON we can parse (the
   default negotiation is EDN)."
  [bearer body]
  (response-for service :post "/mcp"
                :headers {"Content-Type"  "application/json"
                          "Accept"        "application/json"
                          "Authorization" (str "Bearer " bearer)}
                :body (json/generate-string body)))

(defn- tools-call [tool-name arguments]
  {:jsonrpc "2.0" :id 1 :method "tools/call"
   :params {:name tool-name :arguments arguments}})

(defn- parse [resp] (some-> resp :body (json/parse-string true)))

(deftest wire-enforcement-contract
  (let [bearer (seed-read-only-sa!)]
    (testing "the minted principal authenticates as read-only (wire projection)"
      (let [r (auth/authenticate-api-key service-name api-key)]
        (is (:success r) "Bearer token must authenticate")
        (is (true? (auth/read-only-principal? (:principal r)))
            "authenticate-api-key's projection must carry the :read-only role")))

    (testing "a READ verb is PERMITTED over the wire (gate does not deny it)"
      (let [resp (mcp-post bearer (tools-call "sandbar.entity.find" {"ident" ":dt/Class"}))
            body (parse resp)]
        (is (= 200 (:status resp)))
        (is (some? (get-in body [:result :content]))
            (str "entity.find must return a result over the wire; got " (pr-str body)))
        (is (not= "read-only-principal-forbidden-mutation"
                  (get-in body [:error :data :reason]))
            "a read verb must not be gate-denied")))

    (testing "a MUTATION verb is DENIED over the wire (the security contract)"
      (let [resp (mcp-post bearer (tools-call "sandbar.entity.create" {}))
            body (parse resp)]
        (is (= "read-only-principal-forbidden-mutation"
               (get-in body [:error :data :reason]))
            (str "entity.create MUST be gate-denied over the wire; got " (pr-str body)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 2 — the regression guard: a frozen connector must stay hot-reloadable.
;;
;; This is the test that would have caught the ceremony-#4 breach.  It does NOT
;; assert the CURRENT interceptor's behavior (HEAD's committed bearer-interceptor
;; already threads [:request :identity], so a current-behavior test passes on
;; HEAD too).  It asserts the RELOADABILITY invariant the breach violated: an
;; interceptor value already frozen into a route table (the defonce connector)
;; must pick up a hot `require :reload` of its identity-threading impl.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The identity-threading impl var is resolved at RUN time (not compile time)
;; so this namespace COMPILES on HEAD 2ce81a7 — where the var does not exist —
;; and the reloadability assertion then fails cleanly (nil var) rather than the
;; whole namespace failing to load.  On the fix it resolves to #'bearer-enter.
(def ^:private bearer-enter-var (resolve 'sandbar.mcp.auth/bearer-enter))

(defn- with-redefd-root
  "Temporarily rebind `v`'s root to `f`, run `thunk`, restore.  A runtime
   analog of with-redefs that takes an already-resolved var (with-redefs needs
   a compile-time symbol, which would not compile on HEAD)."
  [v f thunk]
  (let [orig @v]
    (try (alter-var-root v (constantly f)) (thunk)
         (finally (alter-var-root v (constantly orig))))))

(deftest frozen-connector-stays-reloadable
  (testing "a captured bearer-interceptor value observes a hot-reload of its :enter impl"
    ;; Capture the interceptor VALUE once — exactly what Pedestal's
    ;; expand-routes bakes into the (defonce) connector's routing table.
    (let [enter-fn (:enter mcp-auth/bearer-interceptor)
          ;; The reloaded impl stamps a sentinel principal onto [:request
          ;; :identity] — standing in for "the reloaded bearer-enter now threads
          ;; identity to the request map (what the gate reads)."
          sentinel {:db/id -1 :auth/service-name :sentinel}
          ctx      {:request {:headers {"authorization" "Bearer x:y"}}}]
      (is (some? bearer-enter-var)
          (str "the reloadability seam `sandbar.mcp.auth/bearer-enter` must exist: "
               "the interceptor's :enter must delegate to a top-level var so a "
               "running-JVM `require :reload` reaches the value frozen into the "
               "(defonce) connector.  ABSENT ON HEAD — that IS the ceremony-#4 "
               "wire breach: the reload rebinds #'bearer-interceptor but the "
               "serving connector keeps the pre-threading value → [:request "
               ":identity] never threads → gate no-ops → fail-open."))
      (when bearer-enter-var
        ;; Redefine the impl AFTER the value was captured — the running-JVM
        ;; `require :reload`.  On the fix the captured :enter routes THROUGH the
        ;; var and observes this; an inlined :enter would not.
        (with-redefd-root bearer-enter-var
          (fn [c] (assoc-in c [:request :identity] sentinel))
          (fn []
            (let [result (enter-fn ctx)]
              (is (= sentinel (get-in result [:request :identity]))
                  (str "the frozen bearer-interceptor value must observe the "
                       "hot-reload and thread identity onto [:request :identity]; "
                       "got " (pr-str (get-in result [:request :identity])))))))))))

(deftest bearer-enter-threads-identity-onto-request
  ;; A direct, non-frozen assertion of the load-bearing behavior: the
  ;; identity-threading impl must attach the principal to [:request :identity]
  ;; (what the gate reads), not the context alone.  DB-backed via a real
  ;; authenticate-api-key.  Guarded so it fails cleanly on HEAD (no var).
  (let [bearer (seed-read-only-sa!)
        ctx    {:request {:headers {"authorization" (str "Bearer " bearer)}}}]
    (is (some? bearer-enter-var)
        "sandbar.mcp.auth/bearer-enter must exist (the reloadable :enter impl)")
    (when bearer-enter-var
      (let [result (@bearer-enter-var ctx)]
        (testing "principal is attached to the CONTEXT"
          (is (= service-name (get-in result [:identity :auth/service-name]))))
        (testing "principal is ALSO attached to [:request :identity] (the gate reads this)"
          (is (= service-name (get-in result [:request :identity :auth/service-name]))
              "the request-map attach is what threads the principal into the gate"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 3 — S5 dispatch-gate acceptance over the wire (LIVE-NOW criteria).
;;
;; These extend the delivered contract to the criteria the S5 dispatch-gate lift
;; makes testable TODAY (S5-PLAN §2.4 / DESIGN-D4 §1): fail-closed no-auth
;; (T-5, criterion 5), authenticated-but-unscoped dispatch deny (T-5b, AP-2 /
;; criterion 5 scope-half made LIVE-NOW by the AP-2 ruling), read-only
;; subsumption after the lift (T-6, criterion 6), and revoked-principal
;; resources/read 401 (T-2a, criterion 2a).  The SCOPE-GATED criteria (2b/3/4a)
;; live in principal_scope_wire_test.clj, DEFERRED-TO-S9.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- no-auth-post
  "POST a JSON-RPC message to /mcp with NO Authorization header — the fail-open
   falsification for the deny-by-default rail (require-bearer must 401)."
  [body]
  (response-for service :post "/mcp"
                :headers {"Content-Type" "application/json"
                          "Accept"       "application/json"}
                :body (json/generate-string body)))

(deftest wire-fail-closed
  ;; Criterion 5 — an unknown/unscoped/absent principal is denied by default at
  ;; the auth layer: require-bearer 401s before any verb runs.  A 200 with a
  ;; :result here is the FAIL-OPEN shape (an unauthenticated caller ran a verb).
  (testing "no Authorization header → 401, no verb runs"
    (let [resp (no-auth-post (tools-call "sandbar.entity.find" {"ident" ":dt/Class"}))]
      (is (= 401 (:status resp))
          (str "a request with no Bearer token must 401 (require-bearer), not "
               "reach a verb; got status " (:status resp)))))
  (testing "malformed bearer (no colon separator) → 401"
    ;; parse-token returns nil → bearer-enter passes through → require-bearer 401.
    (let [resp (mcp-post "garbage-no-colon" (tools-call "sandbar.entity.find" {"ident" ":dt/Class"}))]
      (is (= 401 (:status resp)))))
  (testing "valid-shape bearer, unknown service → 401 (deny-by-default, no fall-through)"
    ;; authenticate-api-key → {:success false :reason :unknown-service} →
    ;; bearer-enter passes through → require-bearer 401.
    (let [resp (mcp-post "no-such-service:whatever" (tools-call "sandbar.entity.find" {"ident" ":dt/Class"}))]
      (is (= 401 (:status resp))))))

(deftest wire-authenticated-unscoped-denied-at-dispatch
  ;; T-5b (AP-2) — an authenticated principal carrying NO capability-bearing
  ;; role is `:scope/unscoped?` and the dispatch gate refuses EVERY non-exempt
  ;; method with `:unscoped-principal-denied`.  This is the DISPATCH-layer deny
  ;; (a JSON-RPC error for an authenticated principal), DISTINCT from the auth-
  ;; layer 401 for a no-/bad-token request (CX2-CONCERN-8).  It is a deliberate
  ;; BEHAVIOR CHANGE from the pre-S5 permissive wire, where a role-less SA
  ;; reached every non-tools/call method.
  (let [bearer (seed-unroled-sa!)]
    (testing "the unroled principal AUTHENTICATES (require-bearer does NOT 401 it)"
      ;; It reaches dispatch (status 200 with a JSON-RPC deny body) — proving the
      ;; deny is a dispatch decision, not an auth-layer rejection.
      (let [resp (mcp-post bearer (tools-call "sandbar.entity.find" {"ident" ":dt/Class"}))]
        (is (= 200 (:status resp))
            "an active unroled SA authenticates; the deny is a JSON-RPC body, not a 401")))
    (testing "a READ tools/call is denied at dispatch (unscoped, not verb-class)"
      (let [body (parse (mcp-post bearer (tools-call "sandbar.entity.find" {"ident" ":dt/Class"})))]
        (is (= "unscoped-principal-denied" (get-in body [:error :data :reason]))
            (str "an authenticated role-less principal must be denied at dispatch "
                 "with :unscoped-principal-denied even on a READ verb (AP-2 fail-"
                 "closed); got " (pr-str body)))
        (is (= "tools/call" (get-in body [:error :data :method]))
            "the deny envelope names the offending method")))
    (testing "a non-tools method (resources/list) is ALSO denied for the unscoped principal"
      ;; Proves the lift covers the previously principal-BLIND families, not just
      ;; tools/call — the exact ceremony-#4 blind spot.
      (let [body (parse (mcp-post bearer {:jsonrpc "2.0" :id 1 :method "resources/list" :params {}}))]
        (is (= "unscoped-principal-denied" (get-in body [:error :data :reason]))
            (str "resources/list must be denied for an unscoped principal at "
                 "dispatch (it was principal-blind pre-S5); got " (pr-str body)))))
    (testing "the exempt lifecycle method (initialize) is NOT denied even when unscoped"
      ;; A client cannot present a scoped principal before initialize completes;
      ;; the exempt family must pass regardless of scope.
      (let [body (parse (mcp-post bearer {:jsonrpc "2.0" :id 1 :method "initialize"
                                          :params {:protocolVersion "2025-11-25"}}))]
        (is (some? (:result body))
            (str "initialize is exempt and must run for an unscoped principal; "
                 "got " (pr-str body)))
        (is (nil? (:error body)))))))

(deftest wire-read-only-subsumption
  ;; T-6 (criterion 6) — the KEYSTONE non-regression: after the dispatch-gate
  ;; lift, the pre-existing read-only verb-class denials STILL fire from
  ;; handle-call (Shape A′, byte-identical clause), and read verbs STILL pass.
  ;; The biggest risk of generalizing the gate is dropping/reordering this case;
  ;; T-6 pins it over the real wire.  project.export is the subtle one: a read-
  ;; SHAPED verb explicitly DENIED to read-only principals (curated override,
  ;; never export to repo root) — it must STILL deny after the lift.
  (let [bearer (seed-read-only-sa!)]
    (doseq [verb ["sandbar.entity.create" "sandbar.entity.update"
                  "sandbar.entity.retract" "sandbar.project.export"]]
      (testing (str verb " stays DENIED for read-only after the dispatch-gate lift")
        (let [body (parse (mcp-post bearer (tools-call verb {})))]
          (is (= "read-only-principal-forbidden-mutation"
                 (get-in body [:error :data :reason]))
              (str verb " must be gate-denied with the read-only verb-class reason "
                   "(handle-call, Shape A′); a :result here is a regression of the "
                   "ff176c2 gate.  got " (pr-str body))))))
    (doseq [[verb args] {"sandbar.entity.find"  {"ident" ":dt/Class"}
                         "sandbar.search.bm25f" {"query" "memory" "class" ":mm/Memory"}}]
      (testing (str verb " stays PERMITTED for read-only (a read verb)")
        (let [resp (mcp-post bearer (tools-call verb args))
              body (parse resp)]
          ;; A read verb must be genuinely SERVED, not merely "not denied":
          ;; pin the POSITIVE shape (HTTP 200 + no :error + a :result) with
          ;; valid per-verb args, so a method-not-found / invalid-params /
          ;; novel deny reason cannot pass this KEYSTONE non-regression test as
          ;; a false green (per the T-6 spec 'find/bm25f PERMITTED'; round-3
          ;; codex+opus+judge must_fix — weak "not-denied-only" pin hardened).
          (is (= 200 (:status resp))
              (str verb " must return HTTP 200 for a read-only SA; got " (pr-str resp)))
          (is (nil? (:error body))
              (str verb " must not error for a read-only SA; got " (pr-str body)))
          (is (some? (:result body))
              (str verb " must return a :result for a read-only SA; got " (pr-str body)))
          ;; secondary: specifically NOT gate-denied by either gate (the read-
          ;; only SA is SCOPED — it carries :read-only — so not unscoped-denied).
          (is (not= "read-only-principal-forbidden-mutation"
                    (get-in body [:error :data :reason]))
              (str verb " must not be verb-class denied for read-only; got " (pr-str body)))
          (is (not= "unscoped-principal-denied"
                    (get-in body [:error :data :reason]))
              (str verb " must not be unscoped-denied — a read-only SA is scoped; got "
                   (pr-str body))))))))

(deftest wire-resources-read-revoked-denied
  ;; T-2a (criterion 2a) — a REVOKED (`:auth/active? false`) principal's
  ;; resources/read is denied at the AUTH layer: authenticate-api-key rejects
  ;; the inactive SA → no :identity → require-bearer 401, BEFORE the principal-
  ;; blind handle-read can serve the resource body.  A 200 with :result :contents
  ;; is the FAIL-OPEN shape (a revoked token read a resource).
  (let [revoked (seed-inactive-sa!)]
    (let [resp (mcp-post revoked {:jsonrpc "2.0" :id 1 :method "resources/read"
                                  :params {:uri "mcp://sandbar/dt/Class/mm/Memory"}})]
      (is (= 401 (:status resp))
          (str "a revoked (inactive) SA's resources/read must 401 at require-"
               "bearer, not reach handle-read; got status " (:status resp)
               " body " (pr-str (parse resp)))))))
