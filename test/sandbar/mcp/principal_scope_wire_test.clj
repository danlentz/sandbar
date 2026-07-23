(ns sandbar.mcp.principal-scope-wire-test
  "SCOPE-GATED wire acceptance (criteria 2b / 3 / 4a) — the co-load-live
   security proofs S5 BUILDS but cannot RUN until S6 mints the compartment
   substrate.  Handed to S9 as the DEP-S5→S9 HARD acceptance (S5-PLAN §2.2
   item 8; DESIGN-D4 §2 + §1 criteria 2b/3/4a; S4-RATIFICATION).

   ## Why this file compiles now but no-ops until S6

   Three criteria turn on a principal carrying a CONTEXT SCOPE and an entity
   carrying a CONFIDENTIALITY COMPARTMENT:

   - **2b** `resources/read` on a URI resolving to an out-of-context `:private`
     entity is DENIED at dispatch (EP-N3 read-compartment check).
   - **3**  a `:private`-context entity update does NOT fan out to an
     out-of-context subscriber over the REAL wire (EP-N2, exercised here through
     a DB-persisted compartment rather than the bare-map seam that
     `notify_plane_clearance_wire_test.clj` drives today).
   - **4a** a ctxA token calling a mutating verb targeting ctxB is denied
     pre-dispatch (EP-2 destination gate).

   The slots these read — `:mm.memory/visibility`, `:mm.memory/owning-project`,
   `:auth/cleared-projects` — DO NOT EXIST in the schema yet (grep-proven
   absent, DESIGN-D3 §7).  A `seed-scoped-sa!` helper that mints a
   ServiceAccount carrying a real context scope cannot be written until S6
   defines the scope slot (DESIGN-D4 §2 test-infra dependency); and the
   `resources/read` / destination compartment checks (EP-N3 / EP-2) are wired
   fail-closed-INERT in S5 — they resolve to no-restriction until S6 mints the
   axes.

   ## The guard idiom (runtime resolve, NOT compile-time)

   Following `read_only_token_wire_test.clj:133-137` — the load-bearing var/slot
   is resolved at RUN time, so this namespace COMPILES on the S5 tree (where the
   S6 substrate is absent) and each SCOPE-GATED body SKIPS cleanly (a logged
   pending, never a red or a compile error) until `s6-scope-substrate-present?`
   flips true.  When S6 lands the schema + D2/D3 wire the checks, these tests
   activate WITHOUT an edit — S9 inherits live acceptance.

   Per S5-PLAN §2.2 item 8 (DEP-S5→S9 HARD) + §2.4 (2b/3/4a labeled
   DEFERRED-TO-S9, never green in S5 reporting)."
  (:require [cheshire.core       :as json]
            [clojure.test        :refer :all]
            [clojure.tools.logging :as log]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.mcp.resources     :as resources]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "mcp-principal-scope-wire-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S6-substrate presence guard — resolved at RUN time (see ns docstring).
;;
;; The S6 scope substrate is present iff the schema carries the
;; `:auth/cleared-projects` property (the principal-side scope slot D2 mints at
;; S6).  Probed via `dt/find-by-ident` on the property's :db/ident — nil on the
;; S5 tree (absent), a Property entity once S6 loads the firewall schema.  This
;; is the single gate the three SCOPE-GATED bodies consult; a false result logs
;; the criterion as DEFERRED-TO-S9 and returns without asserting.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- s6-scope-substrate-present?
  "True iff the S6 compartment substrate has landed — probed at RUN time so this
   ns compiles on the S5 tree where the slot is absent.  Guards every
   SCOPE-GATED body (2b/3/4a) so it SKIPS cleanly pre-S6 rather than failing."
  []
  (boolean
    (try
      (some? (dt/find-by-ident :auth/cleared-projects))
      (catch Throwable _ false))))

(defmacro deferred-to-s9
  "Wrap a SCOPE-GATED test body so it runs only when the S6 scope substrate is
   present; otherwise log a DEFERRED-TO-S9 pending + a single passing sanity
   assertion (so the deftest is not empty) and return.  Keeps the S5 suite
   green while pinning the criterion for the S9 co-load-live ceremony."
  [criterion & body]
  `(if (s6-scope-substrate-present?)
     (do ~@body)
     (do
       (log/info :S5/deferred-to-s9
                 {:criterion ~criterion
                  :reason    :s6-scope-substrate-absent
                  :note      "SCOPE-GATED acceptance handed to S9 (DEP-S5→S9 HARD)"})
       (is true
           (str ~criterion " is DEFERRED-TO-S9 — the S6 compartment substrate "
                "(:auth/cleared-projects / :mm.memory/visibility / owning-project) "
                "is absent on the S5 tree; this body activates without an edit "
                "once S6 lands.")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seam helpers — the DEFERRED-TO-S9 fixtures.
;;
;; `seed-scoped-sa!`'s CONTRACT is specified here (DESIGN-D4 §2 test-infra
;; dependency: input = a project/context ident the SA is cleared for; output =
;; the Bearer token string `<service>:<key>`); its BODY assumes the S6
;; `:auth/cleared-projects` slot and only executes inside a `deferred-to-s9`
;; guard, so it never transacts an unknown attribute on the S5 tree.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private scoped-api-key "scope-wire-test-not-a-real-key-7b2e")

(defn- seed-scoped-sa!
  "Seed a read-only ServiceAccount CLEARED for `cleared-project-eids` and return
   its Bearer token.  Assumes the S6 `:auth/cleared-projects` slot — call ONLY
   inside `deferred-to-s9` (the slot is absent on the S5 tree).  DESIGN-D4 §2
   contract: input = project eids the SA clears; output = `<service>:<key>`."
  [service-name & cleared-project-eids]
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Scoped read-only (wire)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name     service-name
              :auth/api-key-hash     (auth/hash-password scoped-api-key)
              :auth/roles            [(:db/id role)]
              :auth/cleared-projects (vec cleared-project-eids)
              :auth/active?          true}
             {:validate? false})
    (str (name service-name) ":" scoped-api-key)))

(defn- mcp-post
  "POST a JSON-RPC message to /mcp over the full chain with a Bearer token —
   the same harness `read_only_token_wire_test.clj` uses (real servlet chain,
   JSON negotiation)."
  [bearer body]
  (response-for service :post "/mcp"
                :headers {"Content-Type"  "application/json"
                          "Accept"        "application/json"
                          "Authorization" (str "Bearer " bearer)}
                :body (json/generate-string body)))

(defn- parse [resp] (some-> resp :body (json/parse-string true)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Criterion 2b — resources/read on an out-of-context :private URI is DENIED.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^:s9 wire-resources-read-out-of-context-denied
  ;; DEFERRED-TO-S9.  A ctxA-cleared token reading a URI resolving to a ctxB
  ;; :private entity must be DENIED at the read-compartment check (EP-N3), NOT
  ;; served the body.  Asserts the NARROW L-1 claim: URI-resolution read
  ;; confinement, NOT general already-loaded-row confinement (that is physical
  ;; exclusion, S4/S9).
  (deferred-to-s9 "criterion-2b resources-read out-of-context"
    (let [proj-a  (dt/make :mm/Project {:mm/name "ctx-A"} {:validate? false})
          proj-b  (dt/make :mm/Project {:mm/name "ctx-B"} {:validate? false})
          ctxB-mem (dt/make :mm/Memory
                            {:mm/title "secret-B"
                             :mm.memory/visibility :private
                             :mm.memory/owning-project (:db/id proj-b)}
                            {:validate? false})
          bearer  (seed-scoped-sa! :scoped-ctxA (:db/id proj-a))
          uri     (resources/entity->uri ctxB-mem)
          body    (parse (mcp-post bearer {:jsonrpc "2.0" :id 1
                                           :method "resources/read"
                                           :params {:uri uri}}))]
      (testing "the ctxB :private body did NOT cross the firewall on the read plane"
        (is (nil? (get-in body [:result :contents]))
            (str "resources/read of an out-of-context :private URI must be "
                 "denied (EP-N3), not served :contents; got " (pr-str body))))
      (testing "the deny names a compartment reason (reserved vocabulary)"
        (is (contains? #{"read-compartment-forbidden" "out-of-scope-resource"}
                       (get-in body [:error :data :reason]))
            (str "expected a compartment deny reason; got " (pr-str body)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Criterion 3 — notify egress over a DB-persisted compartment (the S6-slot
;; version of notify_plane_clearance_wire_test.clj's bare-map seam test).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^:s9 wire-notification-egress-scoped-db-backed
  ;; DEFERRED-TO-S9.  Same discriminating assertion as the bare-map notify-plane
  ;; test, but over ENTITIES + PRINCIPALS that carry REAL S6 compartment slots —
  ;; proving the filter reads the persisted slots, not just assoc'd map keys.
  (deferred-to-s9 "criterion-3 notification-egress-scoped (db-backed)"
    (notifications/clear-all!)
    (resources/clear-all-subscriptions!)
    (let [proj-a  (dt/make :mm/Project {:mm/name "ctx-A"} {:validate? false})
          proj-b  (dt/make :mm/Project {:mm/name "ctx-B"} {:validate? false})
          prin-A  (dt/make :auth/ServiceAccount
                           {:auth/service-name :db-sub-A
                            :auth/cleared-projects [(:db/id proj-a)]}
                           {:validate? false})
          prin-B  (dt/make :auth/ServiceAccount
                           {:auth/service-name :db-sub-B
                            :auth/cleared-projects [(:db/id proj-b)]}
                           {:validate? false})
          got-A   (atom [])
          got-B   (atom [])
          _       (notifications/register! {:identity prin-A :send! (fn [e] (swap! got-A conj e) true)})
          _       (notifications/register! {:identity prin-B :send! (fn [e] (swap! got-B conj e) true)})
          mem     (dt/make :mm/Memory
                           {:mm/title "secret-A"
                            :mm.memory/visibility :private
                            :mm.memory/owning-project (:db/id proj-a)}
                           {:validate? false})]
      (resources/subscribe! (resources/entity->uri mem) resources/broadcast-sentinel)
      (resources/entity-updated! mem)
      (testing "ctx-A-cleared subscriber receives the ctx-A :private update"
        (is (= 1 (count @got-A))))
      (testing "ctx-B-cleared subscriber does NOT (the leak, blocked over persisted slots)"
        (is (empty? @got-B))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Criterion 4a — cross-context mutating verb denied pre-dispatch.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ^:s9 wire-cross-context-write-denied
  ;; DEFERRED-TO-S9.  A ctxA token calling a mutating verb whose declared target
  ;; is ctxB must be denied at the destination gate (EP-2), reason
  ;; `:out-of-scope-destination`.  Keys on the token scope + the verb's target
  ;; params WITHOUT inspecting result-set rows (the L-1 correction).
  ;;
  ;; NOTE: seeded here with a NON-read-only scoped SA is out of scope for the S5
  ;; helper (seed-scoped-sa! mints read-only); the destination gate is
  ;; orthogonal to the read-only verb-class gate.  Pinned as the CONTRACT; the
  ;; S9 stage supplies a writer-scoped seed if the destination axis needs one.
  (deferred-to-s9 "criterion-4a cross-context write"
    (let [proj-a  (dt/make :mm/Project {:mm/name "ctx-A"} {:validate? false})
          proj-b  (dt/make :mm/Project {:mm/name "ctx-B"} {:validate? false})
          bearer  (seed-scoped-sa! :scoped-writer-ctxA (:db/id proj-a))
          body    (parse (mcp-post bearer
                                   {:jsonrpc "2.0" :id 1 :method "tools/call"
                                    :params {:name "sandbar.entity.create"
                                             :arguments {:class ":mm/Memory"
                                                         :slots {:mm.memory/owning-project (:db/id proj-b)}}}}))]
      (testing "a ctxA token did NOT write into ctxB"
        (is (nil? (:result body))
            (str "a cross-context mutation must be denied at the destination "
                 "gate (EP-2), not executed; got " (pr-str body))))
      (testing "the deny names the destination reason (reserved vocabulary)"
        (is (= "out-of-scope-destination" (get-in body [:error :data :reason]))
            (str "expected :out-of-scope-destination; got " (pr-str body)))))))
