(ns sandbar.firewall.ceremony8-composition-test
  "CEREMONY #8 — COMPOSED TWO-FIREWALL RUNTIME PROBE SUITE.

   This namespace exists ONLY on the ceremony-8 staging MERGE (639705e RPAF
   read-plane firewall  +  b72fd99 S7 directional firewall).  Neither parent
   could run these probes: 639705e has no directional firewall, b72fd99 has no
   RPAF.  The suite proves the two planes COMPOSE as independent AND-gates —
   an operation must pass BOTH; neither masks the other — and re-confirms each
   plane's own enforcement survives the merge unmodified.

   ── Plane map ────────────────────────────────────────────────────────────
   RPAF  (read plane, 639705e):  namespace firewall over class / group-by /
         attribute / :where / entity-return / anchor — refuses `:auth/*` etc.
         Entry: secq/assert-class-allowed! · assert-attribute-allowed! ·
         assert-entity-allowed! · sanitize-where; dispatch: tools/assert-read-
         plane-call!; exit: read-plane-scrub-projection.
   DIR   (write+traverse plane, b72fd99):  directional citation-flow firewall —
         public→private REFUSED, private→public PERMITTED, cross-private
         REFUSED.  EP-1 fw-enforce/check-entity-flow inside dt/make*/make/
         update-entity!/make-all*; EP-3 blocked-hop in outbound/inbound-edges-of
         + graph-walk-from.

   ── Probe groups ─────────────────────────────────────────────────────────
   A.  4 PINNED directional-flow probes — S7-PLAN.md §7  (T-1/T-5/T-6/T-25)
   B.  RPAF read-plane regression (the 639705e plane survives the merge)
   C.  CROSS-PLANE COMPOSITION (the genuinely-new merged-tree behaviour)
   D.  S5 principal-gate regression (the dispatch gate is orthogonal to both)

   Every probe runs against a fresh datomic:mem fixture — never the live store.
   Seeding uses RAW d/transact (sandbar.firewall.support) so fixtures land
   without tripping the guard under test; the SUBJECT write goes through the
   real primitive."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [sandbar.aggregate :as agg]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as sdb]
            [sandbar.firewall.support :as sup]
            [sandbar.mcp.authz :as authz]
            [sandbar.mcp.tools :as tools]
            [sandbar.security.query :as secq]
            [sandbar.test-util :as tu]))

;; Fresh datomic:mem DB per test — never the live store (mirrors the firewall
;; battery's ep1_commit_path_test wiring).
(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-ceremony8" :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fw-violation?
  "True iff `thunk` throws the DIRECTIONAL firewall (EP-1) refusal — an ex-info
   whose :errors carry a :firewall-violation.  Mirrors ep1_commit_path_test."
  [thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (some #(= :firewall-violation (:type %))
                        (:errors (ex-data e)))))))

(defn- rpaf-reject?
  "True iff `thunk` throws the RPAF read-plane refusal — an ex-info whose
   ex-data carries the sanitizer/namespace-firewall signature (never a
   directional :firewall-violation)."
  [thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (let [d (ex-data e)]
           (boolean (or (:sanitizer d)
                        (= :namespace-not-read-plane-allowed (:reason d))
                        (:offending-symbol d)))))))

(defn- call
  "Invoke the REAL MCP dispatch boundary (tools/handle-call) — this is where
   RPAF (assert-read-plane-call!) and the write handler (which reaches the
   directional EP-1) COMPOSE in one call."
  [tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments}))

(defn- dispatch-error? [response] (true? (-> response :result :isError)))
(defn- dispatch-ok?    [response] (and (some? (-> response :result :content))
                                       (not (-> response :result :isError))
                                       (nil? (:error response))))
(defn- error-text [response] (str (-> response :result :content first :text)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared compartment fixture — one public-bottom context + a public and a
;; private project in it, plus a private target.  (CA-4: :proj/pub carries
;; firewall-class :public-bottom so its 4-way composition is EFFECTIVELY
;; :public; absent → :private fail-closed would over-refuse the public legs.)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-world! []
  (sup/seed-context! :ctx/home  :public-bottom)
  (sup/seed-context! :ctx/workA :project-isolated)
  (sup/seed-project! :proj/pub   :public  :ctx/home :public-bottom)
  (sup/seed-project! :proj/privA :private :ctx/workA)
  (sup/seed-project! :proj/privA2 :private :ctx/workA)
  (sup/seed-memory!  :mem/pub-target   :public  :proj/pub)
  (sup/seed-memory!  :mem/privA-target :private :proj/privA))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A.  THE 4 PINNED DIRECTIONAL-FLOW PROBES — S7-PLAN.md §7
;;     (default expectation: public→private REFUSED / private→public PERMITTED /
;;      cross-private REFUSED / :validate? false still firewalls)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a1-public->private-REFUSED            ; S7-PLAN §7 T-1
  (seed-world!)
  (is (fw-violation?
        #(dt/make :mm/Memory
                  {:mm.memory/name "leaker"
                   :mm.memory/visibility :public
                   :mm.memory/owning-project :proj/pub
                   :mm.memory/cites (sup/eid-of :mem/privA-target)}))
      "a PUBLIC memory citing a PRIVATE target is refused at EP-1"))

(deftest a2-private->public-PERMITTED          ; S7-PLAN §7 T-5
  (seed-world!)
  (let [ent (dt/make :mm/Memory
                     {:mm.memory/name "downhill"
                      :mm.memory/visibility :private
                      :mm.memory/owning-project :proj/privA
                      :mm.memory/cites (sup/eid-of :mem/pub-target)})]
    (is (some? (:db/id ent)) "private→public write SUCCEEDS")
    (is (seq (:mm.memory/cites (sdb/entity (:db/id ent))))
        "the private→public citation is committed, not dropped")))

(deftest a3-cross-private-REFUSED              ; S7-PLAN §7 T-6 (the diamond)
  (seed-world!)
  (sup/seed-context! :ctx/workB :project-isolated)
  (sup/seed-project! :proj/privB :private :ctx/workB)
  (sup/seed-memory!  :mem/privB-target :private :proj/privB)
  (is (fw-violation?
        #(dt/make :mm/Memory
                  {:mm.memory/name "diamond"
                   :mm.memory/visibility :private
                   :mm.memory/owning-project :proj/privA
                   :mm.memory/cites (sup/eid-of :mem/privB-target)}))
      "a private-ctxA memory citing a private-ctxB memory is refused"))

(deftest a4-validate-false-STILL-FIREWALLS     ; S7-PLAN §7 T-25 (CA-1 falsifier)
  (seed-world!)
  (is (fw-violation?
        #(dt/make :mm/Memory
                  {:mm.memory/name "raw-leaker"
                   :mm.memory/visibility :public
                   :mm.memory/owning-project :proj/pub
                   :mm.memory/cites (sup/eid-of :mem/privA-target)}
                  {:validate? false}))
      "{:validate? false} skips SCHEMA validation only — the firewall floor STILL fires"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; B.  RPAF READ-PLANE REGRESSION — the 639705e plane survives the merge intact
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest b1-auth-refused-via-read-verbs
  (seed-world!)
  (testing "aggregate.count on :auth/* is refused (class-arg guard)"
    (is (rpaf-reject? #(agg/count-by {:class :auth/User}))))
  (testing "aggregate.count via DISPATCH on :auth/* → isError"
    (is (dispatch-error? (call "sandbar.aggregate.count" {"class" ":auth/User"}))))
  (testing "class.instances via DISPATCH on :auth/* → isError"
    (is (dispatch-error? (call "sandbar.class.instances" {"class" ":auth/User"}))))
  (testing "entity.find's entity-return guard refuses an :auth/* instance"
    (is (rpaf-reject? #(secq/assert-entity-allowed! {:dt/type :auth/User :db/id 1})))))

(deftest b2-sanitize-where-rejects-non-allowlisted-ops
  (testing "a non-allowlisted call form in :where is refused loudly"
    (is (thrown? clojure.lang.ExceptionInfo
                 (secq/sanitize-where '[[(slurp ?e)]]))))
  (testing "a pure data-pattern clause passes unchanged"
    (is (= '[[?e :mm.memory/name ?n]]
           (secq/sanitize-where '[[?e :mm.memory/name ?n]])))))

(deftest b3-assert-read-plane-call-rejects-firewalled-class-arg-at-dispatch
  (testing "a firewalled :class arg is refused at the central dispatch guard"
    (is (rpaf-reject?
          #(#'tools/assert-read-plane-call! "sandbar.aggregate.count"
                                            {"class" ":auth/User"}))))
  (testing "a permitted :class arg passes the dispatch guard (no throw)"
    (is (nil? (#'tools/assert-read-plane-call! "sandbar.aggregate.count"
                                               {"class" ":mm/Memory"}))))
  (testing "a firewalled :group-by attribute is refused at the dispatch guard"
    (is (rpaf-reject?
          #(#'tools/assert-read-plane-call! "sandbar.aggregate.group-by"
                                            {"class" ":mm/Memory"
                                             "group-by" ":auth/api-key-hash"})))))

(deftest b4-group-by-scrub-and-output-scrub-still-fire
  (testing "the output scrub REDACTS a firewalled-class entity wholesale (the
            group-by result-key / projection scrub — no value leaks)"
    (is (= secq/read-plane-redaction-marker
           (secq/read-plane-scrub-projection
             {:dt/type :auth/User :db/id 5
              :auth/api-key-hash "<fake-nonsecret-placeholder>"}))))
  (testing "an allowed entity keeps allowed slots but drops firewalled-ns slots"
    (let [scrubbed (secq/read-plane-scrub-projection
                     {:dt/type :mm/Memory :mm.memory/name "ok" :http/raw "internal"})]
      (is (= "ok" (:mm.memory/name scrubbed)))
      (is (not (contains? scrubbed :http/raw)))))
  (testing "aggregate.group-by on a firewalled group-by slot is refused"
    (is (rpaf-reject? #(agg/group-by {:class :mm/Memory :group-by :auth/api-key-hash})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; C.  CROSS-PLANE COMPOSITION — the genuinely-new merged-tree behaviour.
;;     Both firewalls compose as independent AND-gates; neither masks the other.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest c1-write-refused-at-RPAF-dispatch-even-when-edge-permits
  (seed-world!)
  (testing "creating an :auth/* entity carries NO forbidden citation edge (the
            directional plane would permit it) — yet RPAF refuses it at the
            dispatch class-arg guard, BEFORE the write handler runs"
    (is (rpaf-reject?
          #(#'tools/assert-read-plane-call! "sandbar.entity.create"
                                            {"class" ":auth/User"}))
        "the RPAF dispatch guard is the refusing party")
    (let [resp (call "sandbar.entity.create"
                     {"class" ":auth/User"
                      "slots" {"mm.memory/name" "shadow-acct"}})]
      (is (dispatch-error? resp) "the composed dispatch refuses the write")
      (is (re-find #"(?i)read.?plane|firewall" (error-text resp))))))

(deftest c2-edge-forbidden-write-refused-even-when-class-permitted
  (seed-world!)
  (testing "a :mm/Memory write (RPAF-PERMITTED class) that cites public→private
            passes the RPAF class guard but is REFUSED by the directional EP-1"
    (is (nil? (#'tools/assert-read-plane-call! "sandbar.entity.create"
                                               {"class" ":mm/Memory"}))
        "RPAF class guard PERMITS :mm/Memory")
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "class-ok-edge-bad"
                     :mm.memory/visibility :public
                     :mm.memory/owning-project :proj/pub
                     :mm.memory/cites (sup/eid-of :mem/privA-target)}))
        "the DIRECTIONAL firewall refuses the forbidden edge")))

(deftest c3-navigate-anchor-allowed-hop-forbidden
  (seed-world!)
  ;; Seed a LEGACY forbidden edge directly (bypassing EP-1), as if it predated
  ;; the firewall: public source --cites--> private target.
  (sup/raw-transact! [{:db/ident :mem/legacy-pub-src
                       :dt/type :mm/Memory :mm.memory/name "legacy"
                       :mm.memory/visibility :public
                       :mm.memory/owning-project :proj/pub
                       :mm.memory/cites (sup/eid-of :mem/privA-target)}])
  (testing "EP-3: from the PERMITTED public anchor, the forbidden public→private
            hop is BLOCKED — surfaced as a row with :blocked, NO :target leaked"
    (let [edges (dt/outbound-edges-of (sup/eid-of :mem/legacy-pub-src))
          cites (filter #(= :mm.memory/cites (:predicate %)) edges)]
      (is (seq cites) "the cites hop is surfaced as a row (S10 audit signal preserved)")
      (is (every? :blocked cites) "the forbidden hop is marked :blocked")
      (is (not-any? #(contains? % :target) cites)
          "NO :target key on a blocked hop — clients feeding :target onward get nil, never a fake entity"))))

(deftest c4-validate-false-composition-floor
  (seed-world!)
  (testing "under {:validate? false}: the DIRECTIONAL floor still fires AND the
            RPAF plane remains orthogonal (no :validate? knob reaches it)"
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "raw2"
                     :mm.memory/visibility :public
                     :mm.memory/owning-project :proj/pub
                     :mm.memory/cites (sup/eid-of :mem/privA-target)}
                    {:validate? false})))
    (is (rpaf-reject? #(agg/count-by {:class :auth/User})))))

(deftest c5-entity-validate-advisory-coexists-with-RPAF
  (seed-world!)
  (let [priv-eid (sup/eid-of :mem/privA-target)
        resp (call "sandbar.entity.validate"
                   {"class" ":mm/Memory"
                    "slots" {"mm.memory/name" "leaker"
                             "mm.memory/visibility" ":public"
                             "mm.memory/owning-project" ":proj/pub"
                             "mm.memory/cites" priv-eid}})
        txt  (error-text resp)]
    (testing ":mm/Memory is RPAF-permitted — the read-plane guard does NOT block the validate"
      (is (not (re-find #"(?i)read.?plane" txt))))
    (testing "the directional firewall verdict IS surfaced by the advisory arm"
      (is (re-find #"(?i)firewall" txt)
          (str "entity.validate must surface the directional verdict; got " (pr-str txt))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; D.  S5 PRINCIPAL-GATE REGRESSION — the dispatch gate (orthogonal to BOTH
;;     firewalls) still decides method × principal.  3-principal subset.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest d1-nil-principal-unrestricted-allows-everything
  (let [scope (authz/principal->scope nil)]
    (is (= :sandbar.mcp.authz/unrestricted scope))
    (is (authz/allow? (authz/method-scope-decision "tools/call" scope)))
    (is (authz/allow? (authz/method-scope-decision "resources/read" scope)))))

(deftest d2-read-only-principal-denies-mutation-allows-read
  (let [ro {:scope/read-only? true :scope/unscoped? false
            :scope/capabilities #{:read-only} :scope/contexts #{:ctx/public}}]
    (is (authz/allow? (authz/method-scope-decision "resources/read" ro))
        "a read-only principal MAY read")
    (is (authz/deny?  (authz/method-scope-decision "tasks/cancel" ro))
        "a read-only principal may NOT invoke a mutating method")))

(deftest d3-unscoped-principal-denies-every-non-exempt
  (let [uns {:scope/read-only? false :scope/unscoped? true
             :scope/capabilities #{} :scope/contexts #{:ctx/public}}]
    (is (authz/deny?  (authz/method-scope-decision "tools/call" uns)))
    (is (authz/deny?  (authz/method-scope-decision "resources/read" uns)))
    (is (authz/allow? (authz/method-scope-decision "initialize" uns))
        "the lifecycle-exempt methods are allowed even for an unscoped principal")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; C6.  THE TWO PLANES ARE DISTINCT GUARDS — the load-bearing premise of the
;;      composition thesis.  A directional refusal is NOT caught by the RPAF
;;      predicate, and an RPAF refusal is NOT caught by the directional
;;      predicate.  (Falsifies the "one guard wearing two hats" reading — if the
;;      two planes were the same mechanism, one of the negative arms would fail.)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest c6-two-planes-are-distinct-guards
  (seed-world!)
  (testing "a DIRECTIONAL (EP-1) refusal is the directional plane, NOT RPAF"
    (let [dir #(dt/make :mm/Memory
                        {:mm.memory/name "dir-only"
                         :mm.memory/visibility :public
                         :mm.memory/owning-project :proj/pub
                         :mm.memory/cites (sup/eid-of :mem/privA-target)})]
      (is (fw-violation? dir)        "directional plane fires")
      (is (not (rpaf-reject? dir))   "and it is NOT an RPAF refusal")))
  (testing "an RPAF refusal is the read plane, NOT the directional firewall"
    (let [rp #(agg/count-by {:class :auth/User})]
      (is (rpaf-reject? rp)          "RPAF plane fires")
      (is (not (fw-violation? rp))   "and it is NOT a directional refusal"))))
