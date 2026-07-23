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
   B.  RPAF read-plane regression (the 639705e plane survives the merge) —
       incl. DISPATCH-LEVEL entity.find refusal, by ident (b7) + by numeric
       :db/id (b8), driven through tools/handle-call end-to-end.
   C.  CROSS-PLANE COMPOSITION (the genuinely-new merged-tree behaviour) — the
       edge-forbidden write (c2) is driven through the REAL dispatch boundary.
   D.  S5 principal-gate regression (the dispatch gate is orthogonal to both)

   Every probe runs against a fresh datomic:mem fixture — never the live store.
   Seeding uses RAW d/transact (sandbar.firewall.support) so fixtures land
   without tripping the guard under test; the SUBJECT operation then goes through
   the real primitive AND, for the composition-critical cases, the real MCP
   dispatch boundary (tools/handle-call — b1/b7/b8/c1/c2/c5)."
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

(deftest b4-output-scrub-and-groupby-attr-guard-still-fire
  (testing "the read-plane OUTPUT / projection scrub (secq/read-plane-scrub-projection,
            query.clj:343) REDACTS a firewalled-class entity WHOLESALE to the marker
            — no value leaks.  NB: this is the projection-layer EXIT scrub; the
            group-by result-KEY scrub (dt/read-plane-group-key-firewalled? at
            aggregate.clj:87) is a DIFFERENT function — b6 covers that one."
    (is (= secq/read-plane-redaction-marker
           (secq/read-plane-scrub-projection
             {:dt/type :auth/User :db/id 5
              :auth/api-key-hash "<fake-nonsecret-placeholder>"}))))
  (testing "an allowed entity keeps allowed slots but drops firewalled-ns slots"
    (let [scrubbed (secq/read-plane-scrub-projection
                     {:dt/type :mm/Memory :mm.memory/name "ok" :http/raw "internal"})]
      (is (= "ok" (:mm.memory/name scrubbed)))
      (is (not (contains? scrubbed :http/raw)))))
  (testing "aggregate.group-by on a firewalled group-by SLOT is refused by the
            :group-by ATTRIBUTE guard (secq/assert-attribute-allowed!, aggregate.clj:80)"
    (is (rpaf-reject? #(agg/group-by {:class :mm/Memory :group-by :auth/api-key-hash})))))

(deftest b5-numeric-eid-where-firewall-refuses-auth-selector
  ;; RPAF v3.1 NUMERIC-EID :where firewall — dt/assert-where-eids-allowed!
  ;; (datatype.clj:1074), wired into aggregate.count/group-by (aggregate.clj:50/82).
  ;; The keyword-only namespace guard (secq/assert-where-namespaces!) inspects only
  ;; keywords, so a caller could smuggle a firewalled :auth/* selector past it as a
  ;; raw numeric EID in ATTRIBUTE position ([[?e <auth-attr-eid> ?h]]) or VALUE
  ;; position ([[?e :dt/type <auth-class-eid>]]).  The db-aware eid guard resolves
  ;; every integer to its :db/ident and refuses a firewalled one.  (Distinct from
  ;; the operator-allowlist sanitize-where guard: that fires :reason
  ;; :operator-not-allowlisted on a call-form head symbol; THIS fires :reason
  ;; :namespace-not-read-plane-allowed on a resolved firewalled ident.)
  (seed-world!)
  (let [auth-attr-eid  (sup/eid-of :auth/api-key-hash)   ; firewalled ATTRIBUTE eid
        auth-class-eid (sup/eid-of :auth/User)            ; firewalled CLASS eid
        ok-class-eid   (sup/eid-of :mm/Memory)]           ; allowed CLASS eid (control)
    (testing "unit: the eid guard resolves an attribute-position firewalled eid and
              refuses via the read-plane namespace firewall (NOT the operator allowlist)"
      (let [d (try (dt/assert-where-eids-allowed! [['?e auth-attr-eid '?h]]) ::no-throw
                   (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :namespace-not-read-plane-allowed (:reason d))
            "the refusing party is assert-ident-allowed! → read-plane-firewall")
        (is (= :auth/api-key-hash (:offending d))
            "and it names the resolved firewalled attribute ident")))
    (testing "wired: agg/count-by refuses an attribute-position firewalled eid (aggregate.clj:50)"
      (is (rpaf-reject? #(agg/count-by {:class :mm/Memory
                                        :where [['?e auth-attr-eid '?h]]}))))
    (testing "wired: agg/count-by refuses a VALUE-position firewalled class-selector eid"
      (is (rpaf-reject? #(agg/count-by {:class :mm/Memory
                                        :where [['?e :dt/type auth-class-eid]]}))))
    (testing "an ALLOWED class-selector eid in the SAME value position PASSES — the
              guard discriminates by resolved ident, it is not a blanket integer ban"
      (is (= [['?e :dt/type ok-class-eid]]
             (dt/assert-where-eids-allowed! [['?e :dt/type ok-class-eid]]))))))

(deftest b6-group-by-result-key-scrub-drops-firewalled-bucket
  ;; RPAF v3.1 result-KEY scrub — dt/read-plane-group-key-firewalled? (datatype.clj:1099),
  ;; applied at aggregate.clj:87.  A :group-by :dt/type over an ALLOWED superclass
  ;; (:dt/Ref, the allowed ancestor of :auth/User via :auth/Principal) would leak
  ;; per-:auth/*-class instance COUNTS as {<auth-class-eid> N}; the result-key scrub
  ;; drops those buckets AFTER the raw grouping, keeping allowed buckets.
  (seed-world!)
  ;; Seed two raw :auth/User instances (firewalled :dt/type; reached from :dt/Ref via
  ;; the recursive instance-of rule, User → Principal → Ref).  raw-transact! bypasses
  ;; the write guards — SETUP, not the subject under test.
  (sup/raw-transact! [{:db/ident :ceremony8/probe-auth-1 :dt/type :auth/User}
                      {:db/ident :ceremony8/probe-auth-2 :dt/type :auth/User}])
  (let [auth-class-eid (sup/eid-of :auth/User)
        mem-class-eid  (sup/eid-of :mm/Memory)]
    (testing "the predicate discriminates: a firewalled class-eid key → true, allowed → false"
      (is (true?  (dt/read-plane-group-key-firewalled? auth-class-eid)))
      (is (false? (dt/read-plane-group-key-firewalled? mem-class-eid))))
    (testing "the RAW group-by-of (pre-scrub) DOES surface the firewalled :auth/User
              bucket — the per-class instance-count leak exists at the query layer"
      (let [raw (dt/group-by-of :dt/Ref :dt/type)]
        (is (contains? raw auth-class-eid)
            "raw group-by-of over the allowed superclass leaks the :auth/User count")
        (is (<= 2 (get raw auth-class-eid 0)))))
    (testing "agg/group-by (aggregate.clj:87 result-key scrub) DROPS the firewalled bucket"
      (let [result (agg/group-by {:class :dt/Ref :group-by :dt/type})]
        (is (not (contains? (:groups result) auth-class-eid))
            "the :auth/User count bucket is scrubbed from the read-plane result")))
    (testing "an ALLOWED bucket SURVIVES the scrub (selective, not blanket-empty)"
      (let [result (agg/group-by {:class :mm/Memory :group-by :dt/type})]
        (is (contains? (:groups result) mem-class-eid)
            "the :mm/Memory bucket is kept — only firewalled keys are dropped")))))

(deftest b7-entity-find-dispatch-refuses-firewalled-instance-by-ident
  ;; R9 — DISPATCH-LEVEL entity.find RPAF probe (by IDENT).  The dispatch INPUT
  ;; guard (assert-read-plane-call!) inspects only :class/:group-by args, so
  ;; entity.find — which takes an ident/id, NOT a class — is not caught there.
  ;; The refusing party is the entity-find-handler OUTPUT guard
  ;; (secq/assert-entity-allowed!, tools.clj:605): it resolves the fetched
  ;; entity's :dt/type and refuses a firewalled-class INSTANCE.  (The :auth/User
  ;; CLASS DEFINITION itself stays visible as metamodel per
  ;; read-plane-entity-visible?; only an INSTANCE carries credential values — so
  ;; this probe targets the raw-seeded :auth/User INSTANCE, not the class.)
  (seed-world!)
  ;; Raw-seed a firewalled :auth/User INSTANCE (bypasses the write guards — SETUP,
  ;; not the subject under test).  The placeholder hash is a non-secret fixture.
  (sup/raw-transact! [{:db/ident :ceremony8/probe-auth-1 :dt/type :auth/User
                       :auth/api-key-hash "<fake-nonsecret-placeholder>"}])
  (testing "entity.find on the :auth/* INSTANCE by ident is refused end-to-end at
            the dispatch boundary (isError with the read-plane signature)"
    (let [resp (call "sandbar.entity.find" {"ident" ":ceremony8/probe-auth-1"})]
      (is (dispatch-error? resp)
          "the composed dispatch refuses the firewalled-instance find")
      (is (re-find #"(?i)read.?plane|firewall" (error-text resp))
          (str "the refusal carries the read-plane signature; got "
               (pr-str (error-text resp))))
      (is (not (re-find #"fake-nonsecret-placeholder" (error-text resp)))
          "no slot VALUE from the firewalled instance leaks into the refusal envelope")))
  (testing "positive control — entity.find on an ALLOWED :mm/Memory instance is
            NOT read-plane-refused (the guard is selective, not a blanket ban)"
    (let [resp (call "sandbar.entity.find" {"ident" ":mem/pub-target"})]
      (is (dispatch-ok? resp)
          (str "an allowed-class entity.find must not be refused; got "
               (pr-str (error-text resp)))))))

(deftest b8-entity-find-dispatch-refuses-firewalled-instance-by-numeric-eid
  ;; R9 companion — the SAME firewalled instance fetched by its NUMERIC :db/id.
  ;; A caller who holds only the integer eid (never the ident) must not thereby
  ;; smuggle the credential row past the output guard: the entity-find-handler
  ;; resolves the eid to the same entity and assert-entity-allowed! refuses it
  ;; identically.  (Closes the "numeric-eid form dodges the keyword guard"
  ;; read-plane concern for the entity-return surface.)
  (seed-world!)
  (sup/raw-transact! [{:db/ident :ceremony8/probe-auth-1 :dt/type :auth/User
                       :auth/api-key-hash "<fake-nonsecret-placeholder>"}])
  (let [auth-eid (sup/eid-of :ceremony8/probe-auth-1)]
    (testing "entity.find by NUMERIC :db/id is refused at the dispatch boundary too"
      (let [resp (call "sandbar.entity.find" {"id" auth-eid})]
        (is (dispatch-error? resp)
            "the numeric-eid find is refused end-to-end, same as the ident form")
        (is (re-find #"(?i)read.?plane|firewall" (error-text resp))
            (str "the refusal carries the read-plane signature; got "
                 (pr-str (error-text resp))))
        (is (not (re-find #"fake-nonsecret-placeholder" (error-text resp)))
            "no slot VALUE leaks through the numeric-eid path either")))))

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
  ;; R10 — the SUBJECT write is driven through the REAL dispatch boundary
  ;; (entity.create → store/create-memory! → dt/make → EP-1 firewall-guard!), not
  ;; only the primitive.  The composition thesis is about the real boundary: RPAF
  ;; PERMITS the :mm/Memory class at dispatch, the handler runs, and EP-1 refuses
  ;; the forbidden public→private edge — surfaced as a dispatch isError carrying
  ;; the "Firewall violation" text (handle-call ExceptionInfo arm, tools.clj:3570).
  (seed-world!)
  (let [priv-eid (sup/eid-of :mem/privA-target)]
    (testing "RPAF class guard PERMITS :mm/Memory at dispatch (the class is not firewalled)"
      (is (nil? (#'tools/assert-read-plane-call! "sandbar.entity.create"
                                                 {"class" ":mm/Memory"}))))
    (testing "PRIMARY — the edge-forbidden write through the REAL dispatch boundary is
              refused by the directional EP-1 and surfaced as a dispatch isError"
      (let [resp (call "sandbar.entity.create"
                       {"class" ":mm/Memory"
                        ;; rel-path REQUIRED post-it6: store/create-memory! loud-fails a
                        ;; corpus-document memorial with no rel-path BEFORE dt/make, which
                        ;; would preempt the firewall with a rel-path error.  Supplying a
                        ;; rel-path (as the sibling ep1 firewall tests do) lets the create
                        ;; proceed to dt/make so EP-1 is the refusing party the test asserts.
                        ;; Per bugs/entity_create_codec_path_mints_identless_relpathless_-
                        ;; entities_fs_projection_silently_skipped_2026_07_08.
                        "slots" {"mm.memory/name" "class-ok-edge-bad"
                                 "mm.memory/rel-path" "leak/c2-edge-bad.md"
                                 "mm.memory/visibility" ":public"
                                 "mm.memory/owning-project" ":proj/pub"
                                 "mm.memory/cites" priv-eid}})]
        (is (dispatch-error? resp)
            "the composed dispatch refuses the class-permitted / edge-forbidden write")
        (is (re-find #"(?i)firewall" (error-text resp))
            (str "EP-1 refusal must surface through the create handler; got "
                 (pr-str (error-text resp))))))
    (testing "SECONDARY (primitive layer) — the same edge at dt/make throws the
              directional :firewall-violation directly (the underlying floor)"
      (is (fw-violation?
            #(dt/make :mm/Memory
                      {:mm.memory/name "class-ok-edge-bad-prim"
                       :mm.memory/visibility :public
                       :mm.memory/owning-project :proj/pub
                       :mm.memory/cites priv-eid}))
          "the DIRECTIONAL firewall refuses the forbidden edge at the primitive"))))

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
