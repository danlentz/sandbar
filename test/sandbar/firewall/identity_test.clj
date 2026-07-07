(ns sandbar.firewall.identity-test
  "S7 BU-7 — T-19 SEMANTIC IDENTITY of the injected-resolver core.

   The pure `sandbar.firewall.core/violating-governed-edges` is consumed by
   FIVE resolver instances (§1.4) — EP-1 interactive (`label-of-ref`), EP-1
   import (spec-index-first), EP-3 (`label-of-eid`), the S9 closure (same as
   EP-3 over the built DB), and the tests' pure map.  T-19 asserts the core
   returns BYTE-IDENTICAL Verdicts across resolvers for the SAME logical edge —
   the property that lets EP-1 / EP-3 / S9 / S10 reuse ONE core with no second
   detection path (R19: S10 records the EP-1 error shape, never re-detects).

   SCOPE (BU-7, this worktree): the three resolvers whose production code is
   PRESENT are proven `=` here — the pure-map resolver, the EP-1 DB resolver
   (`label/label-of-ref`), and the EP-3 DB resolver (`label/label-of-eid`).
   The S9-closure + S10-emitter legs are DEFERRED: their production namespaces
   (the `db-firewall-closure` and the class-a emitter) are LATER STAGES not in
   this worktree, so binding a test against them now would abort the whole
   `lein test` run at compile-time (missing-require).  The S9 closure resolver
   is definitionally `label-of-eid` over the built DB — i.e. the EXACT EP-3 leg
   proven here — so the deferred leg reuses an already-anchored resolver, not a
   new code path; the S10 record shape is `enforce/verdict->error` of THIS
   Verdict, asserted in enforce-shape coverage.  Per
   interaction/verification_is_tests_memorialized_not_repl_verification."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [sandbar.firewall.core :as fw]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.enforce :as fw-enforce]
            [sandbar.firewall.support :as sup]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-identity" :auth? false}))

(defn- seed! []
  (sup/seed-context! :ctx/home :public-bottom)
  (sup/seed-context! :ctx/work :project-isolated)
  (sup/seed-project! :proj/pub  :public  :ctx/home :public-bottom)
  (sup/seed-project! :proj/priv :private :ctx/work)
  (sup/seed-memory!  :mem/pub-source  :public  :proj/pub)
  (sup/seed-memory!  :mem/priv-target :private :proj/priv))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-19 — the SAME src-label × the SAME target-ref × three resolvers → the SAME
;;         Verdict (byte-identical).  A public source citing a private target is
;;         the forbidden edge; all three resolvers must produce the one refusal.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest semantic-identity-across-resolvers
  (seed!)
  (let [db          (db/db)
        src-eid     (sup/eid-of :mem/pub-source)
        tgt-eid     (sup/eid-of :mem/priv-target)
        src-label   (label/label-of-eid db src-eid)
        ;; the ONE governed edge, keyed by the SAME raw target-ref (the eid) for
        ;; every resolver, so the Verdict's :target-ref is identical across all
        edges       [[:mm.memory/cites tgt-eid]]

        ;; resolver 1 — the tests' pure map (§1.4 row 5)
        pure-label  (label/label-of-eid db tgt-eid)
        pure-res    (fn [ref] (get {tgt-eid pure-label} ref))
        ;; resolver 2 — EP-1 interactive (label-of-ref over the db)
        ep1-res     (fn [ref] (label/label-of-ref db ref))
        ;; resolver 3 — EP-3 (label-of-eid over the db)
        ep3-res     (fn [ref] (label/label-of-eid db ref))

        v-pure (fw/violating-governed-edges src-label edges pure-res)
        v-ep1  (fw/violating-governed-edges src-label edges ep1-res)
        v-ep3  (fw/violating-governed-edges src-label edges ep3-res)]

    (testing "each resolver produces exactly ONE flow-forbidden violation"
      (doseq [[nm v] [["pure-map" v-pure] ["ep1" v-ep1] ["ep3" v-ep3]]]
        (is (= 1 (count (:violations v))) (str nm " → one violation"))
        (is (= :flow-forbidden (:reason (first (:violations v)))) nm)))

    (testing "the Verdicts are BYTE-IDENTICAL across the three resolvers —
              same src-label, same tgt-label, same slot, same target-ref"
      (is (= v-pure v-ep1) "pure-map resolver ≡ EP-1 resolver")
      (is (= v-ep1  v-ep3) "EP-1 resolver ≡ EP-3 resolver"))

    (testing "the S10 record shape is the enforce/verdict->error of THIS Verdict
              (R19: one detection path — S10 records, never re-detects)"
      (let [verdict (first (:violations v-ep1))
            error   (fw-enforce/verdict->error verdict)]
        (is (= :firewall-violation (:type error)))
        (is (= :flow-forbidden (:reason error)))
        (is (= :mm.memory/cites (:slot error)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The PERMITTED direction is also identical across resolvers (no false split —
;; a private→public edge yields the empty violation set from every resolver).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest semantic-identity-permitted-direction
  (seed!)
  (let [db        (db/db)
        priv-lbl  (label/label-of-eid db (sup/eid-of :mem/priv-target))
        pub-eid   (sup/eid-of :mem/pub-source)
        edges     [[:mm.memory/cites pub-eid]]
        ep1-res   (fn [ref] (label/label-of-ref db ref))
        ep3-res   (fn [ref] (label/label-of-eid db ref))]
    (testing "a private→public edge is PERMITTED under both DB resolvers,
              identically (empty violations, empty skipped)"
      (is (= {:violations [] :skipped []}
             (fw/violating-governed-edges priv-lbl edges ep1-res)))
      (is (= {:violations [] :skipped []}
             (fw/violating-governed-edges priv-lbl edges ep3-res))))))
