(ns sandbar.firewall.ep1-commit-path-test
  "S7 BU-4 / BU-7 — EP-1 author-time enforcement, exercised through the REAL
   commit primitives (dt/make, dt/update-entity!, dt/make-all*, dt/make-all,
   store/create-memory!).  These prove the WIRING, not just the pure core.

   T-1  create refuses public→private
   T-2  update refuses (adds a forbidden edge)
   T-4  import/batch floor refuses  [falsifier]
   T-5  private→public allowed
   T-7  intra-context cross-project permitted  [label-ruling falsifier]
   T-17 the guard is reached on the COMMIT path, not only entity.validate
   T-25 :validate? false STILL firewalls  [CA-1 falsifier]
   T-26 create-memory! :validate? false STILL firewalls  [CA-1 falsifier]
   T-30 same-batch forward-ref not over-refused  [CA-6 falsifier]
   T-3  validated make-all batch refuses (the VALIDATED batch leg — T-4 is the
        unvalidated make-all* leg; both share the one make-all* floor)
   T-14 unknown / novel ref slot is EXEMPT — the write is never firewall-bricked
   T-21 relocation-survivability — renaming a public target's :db/ident does not
        strand a private→public citation; EP-1 re-evaluates it PERMITTED
   T-23 unresolvable governed target on the import floor → transacts + WARN
        (skip record), NOT a refusal, NOT silent"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as sdb]
            [sandbar.firewall.enforce :as fw-enforce]
            [sandbar.firewall.support :as sup]
            [sandbar.store :as store]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-ep1" :auth? false}))

(defn- fw-violation?
  "True iff calling `thunk` throws an ex-info whose data carries a
   :firewall-violation error (the EP-1 refusal shape)."
  [thunk]
  (try
    (thunk)
    false
    (catch clojure.lang.ExceptionInfo e
      (boolean
        (some #(= :firewall-violation (:type %))
              (:errors (ex-data e)))))))

;; ── Shared compartment fixture: one public-bottom context + one public and
;;    one private project in it; plus a second private context/project for the
;;    diamond + intra-context tests. ────────────────────────────────────────

(defn- seed-world! []
  (sup/seed-context! :ctx/home :public-bottom)     ; the public bottom
  (sup/seed-context! :ctx/workA :project-isolated)  ; a private compartment A
  ;; :proj/pub carries firewall-class :public-bottom so its 4-way composition
  ;; (CA-4) is EFFECTIVELY :public: default-visibility :public ⊔ public-bottom
  ;; project firewall-class ⊔ public-bottom context.  Absent firewall-class →
  ;; :private (fail-closed) would make every "public" fixture entity actually
  ;; private and over-refuse T-5/T-12/T-13/T-30 legitimate public-into-public.
  (sup/seed-project! :proj/pub  :public  :ctx/home :public-bottom)
  (sup/seed-project! :proj/privA :private :ctx/workA)
  (sup/seed-project! :proj/privA2 :private :ctx/workA) ; 2nd project, SAME ctx
  (sup/seed-memory!  :mem/pub-target  :public  :proj/pub)
  (sup/seed-memory!  :mem/privA-target :private :proj/privA))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-1 — create refuses public→private
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-create-refuses-public->private
  (seed-world!)
  (testing "a PUBLIC memory citing a PRIVATE target is REFUSED at make"
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "leaker"
                     :mm.memory/visibility :public
                     :mm.memory/owning-project :proj/pub
                     :mm.memory/cites (sup/eid-of :mem/privA-target)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-5 — private→public ALLOWED (the whole point)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-private->public-allowed
  (seed-world!)
  (testing "a PRIVATE memory citing a PUBLIC target SUCCEEDS + commits the edge"
    (let [ent (dt/make :mm/Memory
                       {:mm.memory/name "downhill"
                        :mm.memory/visibility :private
                        :mm.memory/owning-project :proj/privA
                        :mm.memory/cites (sup/eid-of :mem/pub-target)})]
      (is (some? (:db/id ent)))
      (is (seq (:mm.memory/cites (sdb/entity (:db/id ent))))
          "the private→public citation is committed, not dropped"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-7 — intra-context cross-project PERMITTED  [label-ruling falsifier]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-intra-context-cross-project-permitted
  (seed-world!)
  (testing "two PRIVATE memories in DIFFERENT projects of the SAME context may
            cite each other (ruling 5 — would FAIL under a project-scalar label)"
    (sup/seed-memory! :mem/privA2-target :private :proj/privA2)
    (let [ent (dt/make :mm/Memory
                       {:mm.memory/name "sibling-cite"
                        :mm.memory/visibility :private
                        :mm.memory/owning-project :proj/privA
                        :mm.memory/cites (sup/eid-of :mem/privA2-target)})]
      (is (some? (:db/id ent))
          "same-context cross-project private citation must SUCCEED"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-6 — the diamond: cross-private REFUSED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-diamond-cross-private-refused
  (seed-world!)
  (sup/seed-context! :ctx/workB :project-isolated)
  (sup/seed-project! :proj/privB :private :ctx/workB)
  (sup/seed-memory!  :mem/privB-target :private :proj/privB)
  (testing "a private-ctxA memory citing a private-ctxB memory is REFUSED (diamond)"
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "diamond"
                     :mm.memory/visibility :private
                     :mm.memory/owning-project :proj/privA
                     :mm.memory/cites (sup/eid-of :mem/privB-target)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-2 / T-17 — update refuses; the guard is on the COMMIT path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-update-refuses
  (seed-world!)
  (testing "a clean PUBLIC memory that later ADDS a public→private cites is
            REFUSED at update-entity! (T-2), proving the guard rides the COMMIT
            path, not only the read-only entity.validate verb (T-17)"
    (let [clean (dt/make :mm/Memory
                         {:mm.memory/name "will-leak"
                          :mm.memory/visibility :public
                          :mm.memory/owning-project :proj/pub})]
      (is (some? (:db/id clean)))
      (is (fw-violation?
            #(dt/update-entity! (:db/id clean)
                                {:mm.memory/cites (sup/eid-of :mem/privA-target)}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-25 — :validate? false STILL firewalls  [CA-1 falsifier]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-validate-false-still-firewalls
  (seed-world!)
  (testing "make with {:validate? false} citing public→private STILL throws"
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "raw-leaker"
                     :mm.memory/visibility :public
                     :mm.memory/owning-project :proj/pub
                     :mm.memory/cites (sup/eid-of :mem/privA-target)}
                    {:validate? false}))))
  (testing "make* (the unvalidated primitive) direct call STILL refused"
    (is (fw-violation?
          #(dt/make* :mm/Memory
                     {:mm.memory/name "starred-leaker"
                      :mm.memory/visibility :public
                      :mm.memory/owning-project :proj/pub
                      :mm.memory/cites (sup/eid-of :mem/privA-target)}))))
  (testing "update-entity! with {:validate? false} adding public→private STILL throws"
    (let [clean (dt/make :mm/Memory {:mm.memory/name "raw-update"
                                     :mm.memory/visibility :public
                                     :mm.memory/owning-project :proj/pub})]
      (is (fw-violation?
            #(dt/update-entity! (:db/id clean)
                                {:mm.memory/cites (sup/eid-of :mem/privA-target)}
                                {:validate? false}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-26 — store/create-memory! :validate? false STILL firewalls  [CA-1]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-create-memory-validate-false-firewalls
  (seed-world!)
  (testing "store/create-memory! with {:validate? false} citing public→private
            STILL firewall-refuses (the documented bypass is closed)"
    (is (fw-violation?
          #(store/create-memory! :mm/Memory
                                 {:mm.memory/rel-path "leak/via-store.md"
                                  :mm.memory/name "store-leak"
                                  :mm.memory/visibility :public
                                  :mm.memory/owning-project :proj/pub
                                  :mm.memory/cites (sup/eid-of :mem/privA-target)}
                                 {:validate? false})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-4 — the import/batch floor refuses  [falsifier]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-import-floor-refuses
  (seed-world!)
  (testing "make-all* (the unvalidated batch primitive) with a public→private
            spec throws \"Firewall violation in batch\""
    (is (fw-violation?
          #(dt/make-all* [{:dt/type :mm/Memory
                           :mm.memory/name "batch-leaker"
                           :mm.memory/visibility :public
                           :mm.memory/owning-project :proj/pub
                           :mm.memory/cites (sup/eid-of :mem/privA-target)}]))))
  (testing "a CLEAN batch (private→public) transacts"
    (is (some? (dt/make-all* [{:dt/type :mm/Memory
                               :mm.memory/name "batch-ok"
                               :mm.memory/visibility :private
                               :mm.memory/owning-project :proj/privA
                               :mm.memory/cites (sup/eid-of :mem/pub-target)}])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-30 — same-batch forward-ref not over-refused  [CA-6 falsifier]
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-same-batch-forward-ref-not-over-refused
  (seed-world!)
  (testing "an UNVALIDATED make-all* with spec A citing sibling-spec B (declared
            later, both public) SUCCEEDS — the same-batch forward-ref resolves
            against its sibling, not over-refused for tx-ordering"
    (is (some?
          (dt/make-all*
            [{:dt/type :mm/Memory :db/ident :mem/batch-A
              :mm.memory/name "A" :mm.memory/visibility :public
              :mm.memory/owning-project :proj/pub
              :mm.memory/cites {:db/ident :mem/batch-B}}
             {:dt/type :mm/Memory :db/ident :mem/batch-B
              :mm.memory/name "B" :mm.memory/visibility :public
              :mm.memory/owning-project :proj/pub}]))))

  (testing "a same-batch public→PRIVATE forward-ref is STILL firewall-REFUSED
            (the spec-index resolves for the FLOW check, not just tx-ordering)"
    (is (fw-violation?
          #(dt/make-all*
             [{:dt/type :mm/Memory :db/ident :mem/batch-C
               :mm.memory/name "C" :mm.memory/visibility :public
               :mm.memory/owning-project :proj/pub
               :mm.memory/cites {:db/ident :mem/batch-D}}
              {:dt/type :mm/Memory :db/ident :mem/batch-D
               :mm.memory/name "D" :mm.memory/visibility :private
               :mm.memory/owning-project :proj/privA}])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-12 — owning-project declassification guard (§8-R8 / CA-4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-owning-project-governed
  (seed-world!)
  (testing "an EXPLICIT :public memory owned into an effectively-PRIVATE project
            is REFUSED (declassification-shaped write — the owning-project edge
            is checked with the INTRINSIC visibility, not the composed label)"
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "declassify"
                     :mm.memory/visibility :public
                     :mm.memory/owning-project :proj/privA}))))
  (testing "a memory with NO explicit visibility owned into a private project
            SUCCEEDS (absent visibility inherits the project default — no
            declassification act, so no refusal)"
    (is (some?
          (dt/make :mm/Memory
                   {:mm.memory/name "inherits-private"
                    :mm.memory/owning-project :proj/privA}))))
  (testing "a :public memory owned into a PUBLIC project SUCCEEDS"
    (is (some?
          (dt/make :mm/Memory
                   {:mm.memory/name "legit-public"
                    :mm.memory/visibility :public
                    :mm.memory/owning-project :proj/pub})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-13 — exempt edges NOT refused (the over-refusal / baseline-safety guard)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-exempt-edges-not-refused
  (seed-world!)
  (testing "a PUBLIC memory whose EXEMPT edges (part-of / created-by) touch a
            private target SUCCEEDS — exempt slots never fire the flow rule"
    (let [priv-eid (sup/eid-of :mem/privA-target)]
      (is (some?
            (dt/make :mm/Memory
                     {:mm.memory/name "exempt-ok"
                      :mm.memory/visibility :public
                      :mm.memory/owning-project :proj/pub
                      ;; part-of is EXEMPT (containment, ONT-F4) — must NOT refuse
                      :mm.memory/part-of priv-eid})))))
  (testing "a memory with NO governed edges at all (the overwhelming common case)
            is never touched by the firewall"
    (is (some?
          (dt/make :mm/Memory {:mm.memory/name "plain-memory"})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-3 — the VALIDATED make-all batch refuses (companion to T-4's make-all*)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-validated-batch-refuses
  (seed-world!)
  (testing "the VALIDATED batch verb `make-all` refuses a public→private spec
            via the SAME make-all* firewall floor (throws a :firewall-violation
            envelope naming the offending slot) — T-4 covered make-all*
            directly; this proves the validated leg inherits the one floor"
    (let [thrown (try
                   (dt/make-all [{:dt/type :mm/Memory
                                  :mm.memory/name "validated-batch-leaker"
                                  :mm.memory/visibility :public
                                  :mm.memory/owning-project :proj/pub
                                  :mm.memory/cites (sup/eid-of :mem/privA-target)}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "make-all must throw on the forbidden spec")
      (let [errs (:errors (ex-data thrown))]
        (is (some #(= :firewall-violation (:type %)) errs)
            "the envelope carries a :firewall-violation error")
        (is (some #(= :mm.memory/cites (:slot %)) errs)
            "the error names the offending governed slot (per-slot detail)"))))
  (testing "a CLEAN validated batch (private→public) transacts"
    (is (some? (dt/make-all [{:dt/type :mm/Memory
                              :mm.memory/name "validated-batch-ok"
                              :mm.memory/visibility :private
                              :mm.memory/owning-project :proj/privA
                              :mm.memory/cites (sup/eid-of :mem/pub-target)}])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-14 — an unknown / novel ref slot is EXEMPT (write never firewall-bricked)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-unknown-slot-exempt
  (seed-world!)
  (testing "a slot in NONE of the three governed sets is not governed — so a
            public source carrying it to a private target contributes NO
            violation (R10: unknown ref slot is EXEMPT + WARN, never write-
            bricking).  Asserted at the enforce body so the assertion is
            transaction-safe (a truly-undefined attribute would fail at the
            Datomic schema layer, not the firewall)"
    (let [priv-eid (sup/eid-of :mem/privA-target)
          spec     {:mm.memory/name "novel-edge"
                    :mm.memory/visibility :public
                    :mm.memory/owning-project :proj/pub
                    :some.novel/edge priv-eid}
          {:keys [violations skipped]}
          (fw-enforce/check-entity-flow (sdb/db) :mm/Memory spec nil)]
      (is (empty? violations)
          "a novel/unknown slot never yields a flow violation")
      (is (empty? skipped)
          "a novel/unknown slot is dropped BEFORE resolution — not even skipped")))
  (testing "and the guard on such a spec does NOT throw — the write proceeds
            (a memory carrying ONLY governed exempt slots + a novel edge is
            never firewall-refused)"
    (is (some?
          (dt/make :mm/Memory {:mm.memory/name "novel-edge-writes"
                               :mm.memory/visibility :public
                               :mm.memory/owning-project :proj/pub})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-21 — relocation-survivability (scope-D3)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-relocation-survivability
  (seed-world!)
  (testing "a private→public citation survives a rename of the PUBLIC target's
            :db/ident — the edge resolves by EID (stable), EP-1 re-evaluates it
            PERMITTED, and no strand/over-refusal occurs on the relocation"
    (let [pub-eid (sup/eid-of :mem/pub-target)]
      ;; rename the public target's :db/ident (its EID is unchanged)
      (sup/raw-transact! [[:db/add pub-eid :db/ident :mem/pub-target-renamed]])
      ;; the private source cites the target by its STABLE eid
      (let [ent (dt/make :mm/Memory
                         {:mm.memory/name "post-relocation-citer"
                          :mm.memory/visibility :private
                          :mm.memory/owning-project :proj/privA
                          :mm.memory/cites pub-eid})]
        (is (some? (:db/id ent))
            "the private→public citation still commits after the target rename")
        (is (seq (:mm.memory/cites (sdb/entity (:db/id ent))))
            "the citation edge is present, resolved by the stable eid")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-23 — unresolvable governed target on the import floor → skip + WARN
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ep1-unresolvable-target-import-floor-skips
  (seed-world!)
  (testing "an import-floor spec whose governed target resolves to NO live
            entity (and is not a same-batch sibling) is a :skipped record — NOT
            a violation, NOT silent — so bulk re-ingest file-ordering never
            bricks (§4.4 / R11; the S9 closure is the guarantee)"
    (let [spec {:dt/type :mm/Memory
                :mm.memory/name "dangling-citer"
                :mm.memory/visibility :public
                :mm.memory/owning-project :proj/pub
                ;; a governed ref target that is neither a sibling nor in the db
                :mm.memory/cites {:db/ident :mem/never-declared-target}}
          {:keys [violations skipped]}
          (fw-enforce/check-batch (sdb/db) [spec])]
      (is (empty? violations)
          "an unresolvable target is NOT a refusal")
      (is (= 1 (count skipped))
          "it IS recorded as a skip (loud, auditable — not silent)")
      (is (= :mm.memory/cites (:slot (first skipped))))
      (is (= :unresolved (:reason (first skipped)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-20 — tiered-closure scaffold (L-8).
;;
;; NO :internal tier ships in S7: `visibility->sensitivity` is the closed
;; public/private table and ANY unknown value (e.g. :internal) reads :private
;; (fail-closed, AP-6).  This scaffold pins that reading — an :internal chain
;; member never becomes public by reinterpretation — and is the attach point
;; for the real tiered-closure semantics the day a tier value ships.
;;
;; The plan's §7 row marks T-20 `^:xfail`; the repo has no test-selector
;; infrastructure to exclude a red var, so the scaffold asserts TODAY'S ruled
;; fail-closed semantics (green) instead of the future tier semantics (red).
;; When :internal mints, the three legs below are exactly the assertions the
;; tier flip must revisit.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tiered-closure-scaffold
  (seed-world!)
  (testing "a public source citing an :internal target is REFUSED — the
            unknown tier reads :private on the target side (nothing becomes
            public by reinterpretation)"
    (sup/seed-memory! :mem/internal-target :internal :proj/privA)
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "public-cites-internal"
                     :mm.memory/visibility :public
                     :mm.memory/owning-project :proj/pub
                     :mm.memory/cites (sup/eid-of :mem/internal-target)}))))
  (testing ":internal → :internal inside ONE compartment is PERMITTED (both
            read :private; the same-compartment subset holds) — the intra-tier
            leg the future closure keeps"
    (sup/seed-memory! :mem/internal-a :internal :proj/privA)
    (let [ent (dt/make :mm/Memory
                       {:mm.memory/name "internal-cites-internal"
                        :mm.memory/visibility :internal
                        :mm.memory/owning-project :proj/privA
                        :mm.memory/cites (sup/eid-of :mem/internal-a)})]
      (is (some? (:db/id ent)))))
  (testing ":internal → :internal ACROSS compartments is REFUSED (the L-8
            chain's forbidden hop, already refused under today's fail-closed
            collapse to :private)"
    (sup/seed-context! :ctx/workB :project-isolated)
    (sup/seed-project! :proj/privB :private :ctx/workB)
    (sup/seed-memory!  :mem/internal-b :internal :proj/privB)
    (is (fw-violation?
          #(dt/make :mm/Memory
                    {:mm.memory/name "internal-cross-compartment"
                     :mm.memory/visibility :internal
                     :mm.memory/owning-project :proj/privA
                     :mm.memory/cites (sup/eid-of :mem/internal-b)})))))
