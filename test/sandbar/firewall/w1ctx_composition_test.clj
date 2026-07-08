(ns sandbar.firewall.w1ctx-composition-test
  "W1.ctx SPINE — the shared-label-resolution-core composition property suite.

   The W1.ctx §5 consuming-stage gate: the firewall-class→sensitivity pure fn +
   the most-restrictive `compose` operator + the memory-level `effective-
   sensitivity` (4-axis) + the project-level `project-effective-sensitivity`
   (3-axis) — landed as the ONE shared core at the head of the serial spine —
   with the composition property tests GREEN.  Falsification-first: each test
   FAILS against a wrong (widening / axis-dropping / pick-one) implementation.

   Coverage map (design → test):
     CTX-1        firewall-class→sensitivity fail-closed TOTAL (property over
                  range ∪ nil ∪ arbitrary future keywords).
     CTX-2        no public-bottom presumed before Dan's stamp (absent
                  firewall-class ⇒ :private).
     CTX-3        a private (:project-isolated) context resolves :private.
     CTX-4        `visible-projects` is the SOLE membership authority — a project
                  present via the runs-in-context reverse-tie (a poisoned-registry
                  shape) but ABSENT from `:mm.context/visible-projects` is NOT a
                  member (must not enter the private closure).  FAILS against an
                  impl that unions a per-machine registry / the reverse-tie.
     CTX-5        Context STRING-carrier fail-closed backstop (unresolvable
                  carrier toward public ⇒ REFUSE, not silent-permit).
     CTX-6        intra-context cross-project PERMIT vs cross-private REFUSE
                  (the core predicate; maps to live ep1 T-7 / P3).
     P-COMPOSE-1  intrinsic-:private-under-:public-gate ⇒ :private (clause (a)
                  core resolution; (c) edge refusal via the live directional core).
     P-COMPOSE-2  no composition WIDENS clearance (compose = meet; exhaustive
                  over {:public :private}^n) — FAILS against union/join.
     P-COMPOSE-3  each container axis INDEPENDENTLY gates (default-visibility,
                  project firewall-class, context-leg) — FAILS against axis-drop.
     P-COMPOSE-4  multi-context MEET over the card-many runs-in-context SET
                  (private in ANY context ⇒ :private; ∅ ⇒ :private) — FAILS
                  against a pick-one / first-context / scalar leg.
     A-1          mixed resolvable+UNRESOLVABLE runs-in-context ⇒ :private
                  (fail-close; R-3).  A dangling ident member must WIDEN the meet
                  toward :private, NOT be silently dropped by `keep` (CODEX-1
                  HIGH).  FAILS against the bare `(keep ref->eid)` idiom.

   Fresh datomic:mem per test — never the live store (mirrors the S7 battery).

   ENV REQUIREMENT (R-5): these suites need a throwaway SANDBAR_CLIENT_DIR whose
   `<dir>/.sandbar/config.edn` carries the 22-key `:required-schema` (the bare
   worktree's classpath `config/config.edn` is gitignored, so `:required-schema`
   is otherwise nil and the fixture errors ENVIRONMENTALLY — not a code defect).
   The exact key list + the differential-vs-pinned-14 battery protocol are in
   `audit-results/loop-2026-07-08/it5-w1ctx/IMPLEMENT-REPORT.md`."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.core :as fw]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.project.route :as route]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "w1ctx-composition" :auth? false}))

(defn- proj-sens [ident]
  (label/project-effective-sensitivity (db/db) (d/entity (db/db) (sup/eid-of ident))))

(defn- mem-sens [ident]
  (label/effective-sensitivity (db/db) (d/entity (db/db) (sup/eid-of ident))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CTX-1 — the firewall-class → sensitivity mapping is fail-closed TOTAL.
;;   sensitivity-of(v) = :public  ⟺  v = :public-bottom   (the load-bearing invariant)
;;   Property over: the range enum ∪ nil ∪ arbitrary FUTURE keywords (gensym'd).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ctx1-sensitivity-of-fail-closed-total
  (testing "the SOLE :public-yielding input is :public-bottom"
    (is (= :public (label/sensitivity-of-firewall-class :public-bottom))))
  (testing "every non-:public-bottom input (range enum ∪ nil ∪ arbitrary future
            keywords) ⇒ :private — the total, deny-by-default table"
    (let [domain (concat [:none :proprietary-tokens-advisory :project-isolated
                          nil :some-future-regime :public :private ::qualified
                          :publicBottom :public-bottomx :bottom]
                         ;; arbitrary future keywords — nothing becomes public
                         ;; by reinterpretation
                         (repeatedly 200 #(keyword (str (gensym "fw")))))]
      (doseq [v domain]
        (is (= :private (label/sensitivity-of-firewall-class v))
            (str "non-:public-bottom input " (pr-str v) " must fail-closed :private")))))
  (testing "the invariant BOTH directions: sensitivity-of(v)=:public ⟺ v=:public-bottom"
    (doseq [v (concat [:public-bottom :none nil :project-isolated :whatever]
                      (repeatedly 50 #(keyword (str (gensym "k")))))]
      (is (= (= v :public-bottom)
             (= :public (label/sensitivity-of-firewall-class v)))
          (str "the iff must hold for " (pr-str v))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CTX-2 — no public-bottom PRESUMED before Dan's stamp.  A context with
;;   firewall-class ABSENT (the global/public corpus context before Decision-1
;;   stamps :public-bottom) resolves :private.  FAILS against public-by-default.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ctx2-no-public-bottom-presumed
  (testing "a context with NO firewall-class stamp resolves :private (not public-by-default)"
    (sup/raw-transact! [{:db/ident :ctx/unstamped :dt/type :mm/Context
                         :mm.memory/name "unstamped-global"}])
    (let [lbl (label/label-of-eid (db/db) (sup/eid-of :ctx/unstamped))]
      (is (= :private (:sensitivity lbl))
          "absent firewall-class must NOT presume the public bottom")
      (is (= :private (:tie-designation lbl))
          "and the tie-designation (public-bottom co-load key) is fail-closed :private")))
  (testing "a context explicitly :none is likewise :private (no-regime ≠ public)"
    (sup/seed-context! :ctx/none :none)
    (is (= :private (:sensitivity (label/label-of-eid (db/db) (sup/eid-of :ctx/none)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CTX-3 — a private (:project-isolated) context, with visible-projects [P2],
;;   resolves :private.  FAILS against a leak of :public for a project-isolated
;;   context.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ctx3-private-context-resolves-private
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/p2 :private :ctx/priv)
  ;; visible-projects is the corpus-canonical membership authority (W1.ctx §3)
  (sup/raw-transact! [{:db/ident :ctx/priv
                       :mm.context/visible-projects (sup/eid-of :proj/p2)}])
  (testing "the :project-isolated context itself resolves :private"
    (is (= :private (:sensitivity (label/label-of-eid (db/db) (sup/eid-of :ctx/priv))))))
  (testing "its member project P2 resolves :private (project-effective-sensitivity)"
    (is (= :private (proj-sens :proj/p2))))
  (testing "visible-projects is the membership authority carrying P2 (W1.ctx §3)"
    (is (= #{(sup/eid-of :proj/p2)}
           (route/visible-projects (db/db) (d/entity (db/db) (sup/eid-of :ctx/priv)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CTX-4 — `visible-projects` is the SOLE, corpus-canonical membership authority
;;   (W1.ctx §3).  A project enrolled ONLY via the runs-in-context reverse-tie
;;   (the shape a poisoned per-machine registry would surface as a "member") but
;;   ABSENT from `:mm.context/visible-projects` must NOT be a member — it must
;;   not enter the context's private closure.  FAILS against an impl that unions
;;   the runs-in-context reverse-tie or merges a gitignored registry file.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ctx4-visible-projects-is-sole-membership-authority
  ;; a private context; its CANONICAL membership authority (visible-projects) is
  ;; enrolled with EXACTLY :proj/enrolled.  :proj/poison ties itself to the SAME
  ;; context via runs-in-context but is deliberately NOT enrolled.
  (sup/seed-context! :ctx/closed :project-isolated)
  (sup/seed-project! :proj/enrolled :private :ctx/closed)
  (sup/seed-project! :proj/poison   :private :ctx/closed)   ; runs-in-context, NOT enrolled
  (sup/raw-transact! [{:db/ident :ctx/closed
                       :mm.context/visible-projects (sup/eid-of :proj/enrolled)}])
  (let [db      (db/db)
        ctx     (d/entity db (sup/eid-of :ctx/closed))
        members (route/visible-projects db ctx)]
    (testing "sanity — the poison project GENUINELY runs-in the context (so the
              exclusion is the authority's doing, not a missing reverse-tie)"
      (is (contains? (label/project-context-eids db (d/entity db (sup/eid-of :proj/poison)))
                     (sup/eid-of :ctx/closed))
          ":proj/poison's runs-in-context really includes :ctx/closed"))
    (testing "visible-projects returns EXACTLY the enrolled member (sole authority)"
      (is (= #{(sup/eid-of :proj/enrolled)} members)
          "only the git-tracked :mm.context/visible-projects member is present"))
    (testing "the poisoned project is NOT a member — it must not enter the private
              closure (visible-projects is the sole membership authority)"
      (is (not (contains? members (sup/eid-of :proj/poison)))
          "a project absent from visible-projects is NOT a member even though its
           runs-in-context ties it to the context — a reverse-tie / registry
           forgery must not confer membership (FAILS against a union impl)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CTX-5 — Context STRING-carrier FAIL-CLOSED backstop (W1.ctx §4).  An
;;   UNRESOLVABLE :mm.context/cites carrier toward a public source is REFUSED
;;   (treated :private), not silently permitted.  FAILS against a best-effort-
;;   only impl that permits on resolution failure.  (The citation-aware WALK is
;;   W1.H; the ctx wiring supplies the fail-closed RESOLUTION primitive.)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ctx5-context-string-carrier-fail-closed
  (sup/seed-context! :ctx/pub-bottom :public-bottom)
  (sup/seed-context! :ctx/work :project-isolated)
  (sup/seed-project! :proj/pub  :public  :ctx/pub-bottom :public-bottom)
  (sup/seed-project! :proj/priv :private :ctx/work)
  (sup/seed-memory!  :mem/pub-target  :public  :proj/pub)
  (sup/seed-memory!  :mem/priv-target :private :proj/priv)
  (let [db          (db/db)
        pub-src     (label/label-of-eid db (sup/eid-of :mem/pub-target))
        ;; a best-effort rel-path→entity resolver over the two seeded memories
        resolver    (fn [rel-path]
                      (case rel-path
                        "pub.md"  (d/entity db (sup/eid-of :mem/pub-target))
                        "priv.md" (d/entity db (sup/eid-of :mem/priv-target))
                        nil))]
    (testing "an UNRESOLVABLE carrier resolves FAIL-CLOSED :private (backstop)"
      (let [lbl (route/resolve-carrier-label db "ghost/dangling.md" resolver)]
        (is (= :private (:sensitivity lbl))
            "unresolvable carrier must be treated :private, never permissively skipped")))
    (testing "a PUBLIC-source Context citing the UNRESOLVABLE carrier is REFUSED
              (fail-closed) — NOT silently permitted"
      (is (false? (fw/firewall-permits?
                    pub-src
                    (route/resolve-carrier-label db "ghost/dangling.md" resolver)))
          "public → (fail-closed :private) crossing must REFUSE"))
    (testing "a RESOLVABLE public carrier target is PERMITTED (best-effort half works)"
      (is (true? (fw/firewall-permits?
                   pub-src
                   (route/resolve-carrier-label db "pub.md" resolver)))))
    (testing "a RESOLVABLE private carrier target from a public source is REFUSED
              (the resolved-label half is directional too)"
      (is (false? (fw/firewall-permits?
                    pub-src
                    (route/resolve-carrier-label db "priv.md" resolver)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CTX-6 — intra-context cross-project PERMIT vs cross-private REFUSE, over the
;;   live core predicate (maps to ep1-intra-context-cross-project-permitted /
;;   ep1-diamond-cross-private-refused).  FAILS against a flat model.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ctx6-intra-vs-cross-context
  (sup/seed-context! :ctx/A :project-isolated)
  (sup/seed-context! :ctx/B :project-isolated)
  (sup/seed-project! :proj/a1 :private :ctx/A)
  (sup/seed-project! :proj/a2 :private :ctx/A)          ; same context as a1
  (sup/seed-project! :proj/b1 :private :ctx/B)          ; different private context
  (sup/seed-memory!  :mem/a1 :private :proj/a1)
  (sup/seed-memory!  :mem/a2 :private :proj/a2)
  (sup/seed-memory!  :mem/b1 :private :proj/b1)
  (let [db  (db/db)
        la1 (label/label-of-eid db (sup/eid-of :mem/a1))
        la2 (label/label-of-eid db (sup/eid-of :mem/a2))
        lb1 (label/label-of-eid db (sup/eid-of :mem/b1))]
    (testing "intra-context cross-project (a1 → a2, same context A) PERMITTED (T-7)"
      (is (true? (fw/firewall-permits? la1 la2))))
    (testing "cross-private (a1 → b1, contexts A vs B) REFUSED (the diamond, P3)"
      (is (false? (fw/firewall-permits? la1 lb1))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P-COMPOSE-1 — an intrinsically-:private memory under an all-:public container
;;   resolves :private (the intrinsic label is NEVER widened by a more-public
;;   container).  Clause (a) core resolution; clause (c) edge refusal via the
;;   live directional predicate.  (Clause (b) export-withholding EXECUTES at
;;   W1.H — OUT here.)  FAILS against an override-by-container widening leak.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest pcompose1-intrinsic-private-under-public-gate
  ;; an ALL-public container: default-visibility :public, project firewall-class
  ;; :public-bottom, runs-in-context :public-bottom.
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/allpub :public :ctx/pub :public-bottom)
  ;; the memory's OWN visibility is :private
  (sup/seed-memory!  :mem/intrinsic-priv :private :proj/allpub)
  (sup/seed-memory!  :mem/pub-sibling    :public  :proj/allpub)
  (testing "(a) core resolution — effective-sensitivity(M) = :private despite the
            all-:public container (most-restrictive; intrinsic never widened)"
    (is (= :public (proj-sens :proj/allpub))
        "sanity: the container project itself is :public (all axes public)")
    (is (= :private (mem-sens :mem/intrinsic-priv))
        "the intrinsically-:private memory resolves :private under the :public gate"))
  (testing "(c) edge refusal — a public sibling citing the intrinsic-private
            memory is REFUSED at the live directional core (firewall-permits?)"
    (let [db  (db/db)
          pub (label/label-of-eid db (sup/eid-of :mem/pub-sibling))
          prv (label/label-of-eid db (sup/eid-of :mem/intrinsic-priv))]
      (is (false? (fw/firewall-permits? pub prv))
          "public → intrinsic-private is REFUSED (P1)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P-COMPOSE-2 — composition NEVER widens clearance.  `compose` is the MEET in
;;   the :private ⊑ :public lattice — EXHAUSTIVE over {:public :private}^n
;;   (n = 1..5).  FAILS against a union-of-scopes / join impl.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- all-vectors
  "Every vector of length n over {:public :private}."
  [n]
  (if (zero? n)
    [[]]
    (for [head [:public :private]
          tail (all-vectors (dec n))]
      (cons head tail))))

(defn- more-public? [a b] (and (= a :public) (= b :private)))  ; a strictly wider than b

(deftest pcompose2-no-composition-widens-clearance
  (testing "compose(xs) = :private ⟺ some axis :private (the meet law), exhaustively"
    (doseq [n (range 1 6), xs (all-vectors n)]
      (is (= (boolean (some #(= :private %) xs))
             (= :private (label/compose xs)))
          (str "meet law must hold for " (pr-str xs)))))
  (testing "adding ANY label y never REDUCES restrictiveness (never widens)"
    (doseq [n (range 1 5), xs (all-vectors n), y [:public :private]]
      (is (not (more-public? (label/compose (conj (vec xs) y))
                             (label/compose xs)))
          (str "compose(xs ∪ {y}) must not be more-public than compose(xs): "
               (pr-str [xs y])))))
  (testing "adding :public to a :private-composing set NEVER flips it to :public
            (the union-of-scopes footgun)"
    (doseq [n (range 1 5), xs (all-vectors n) :when (= :private (label/compose xs))]
      (is (= :private (label/compose (conj (vec xs) :public)))
          (str "adding :public must not widen a :private composition: " (pr-str xs)))))
  (testing "compose is the MEET / lower-bound — never more public than any single input"
    (doseq [n (range 1 5), xs (all-vectors n)]
      (let [r (label/compose xs)]
        (doseq [x xs]
          (is (not (more-public? r x))
              (str "compose result " r " must not be more-public than input " x)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P-COMPOSE-3 — each CONTAINER axis independently gates.  For each of
;;   {default-visibility, project firewall-class, context-leg} set to :private
;;   with ALL others :public, project-effective-sensitivity ⇒ :private.  FAILS
;;   against an impl that DROPS any single axis from the meet.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest pcompose3-each-container-axis-independently-gates
  (sup/seed-context! :ctx/pub  :public-bottom)     ; a :public context
  (sup/seed-context! :ctx/priv :project-isolated)  ; a :private context
  ;; axis (2) default-visibility :private, others :public
  (sup/seed-project! :proj/axis-defvis  :private :ctx/pub  :public-bottom)
  ;; axis (3) project firewall-class :private (:project-isolated), others :public
  (sup/seed-project! :proj/axis-fwclass :public  :ctx/pub  :project-isolated)
  ;; axis (4) context-leg :private (runs-in a :private context), others :public
  (sup/seed-project! :proj/axis-ctxleg  :public  :ctx/priv :public-bottom)
  ;; positive control: ALL axes :public
  (sup/seed-project! :proj/allpub       :public  :ctx/pub  :public-bottom)
  (testing "default-visibility = :private under all-:public other gates ⇒ :private"
    (is (= :private (proj-sens :proj/axis-defvis))))
  (testing "project firewall-class = :private under all-:public other gates ⇒ :private"
    (is (= :private (proj-sens :proj/axis-fwclass))))
  (testing "context-leg = :private (any private context) under all-:public ⇒ :private"
    (is (= :private (proj-sens :proj/axis-ctxleg))))
  (testing "positive control — ALL axes :public ⇒ :public (not spuriously private)"
    (is (= :public (proj-sens :proj/allpub))
        "an axis-drop impl might pass the negatives but must still yield :public here")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P-COMPOSE-4 — multi-context MEET over the card-MANY runs-in-context SET.  A
;;   project running in {C_pub, C_priv} resolves :private (private in ANY
;;   context).  The empty / unresolvable set ⇒ :private (fail-closed).  FAILS
;;   against a pick-one / first-context / scalar leg (which would pick C_pub
;;   and widen the dual-context project to :public).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest pcompose4-multi-context-any-private-resolves-private
  (sup/seed-context! :ctx/pub  :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  ;; a DUAL-context project: runs in BOTH a public AND a private context, ALL
  ;; other axes :public (default-visibility :public, firewall-class :public-bottom).
  ;; seed-project! sets a single runs-in-context, so raw-transact the card-many set.
  (sup/raw-transact! [{:db/ident                      :proj/dual
                       :dt/type                       :mm/Project
                       :mm.memory/name                "dual-context"
                       :mm.project/ident              :proj/dual
                       :mm.project/corpus-repo        "test-repo"
                       :mm.project/default-visibility :public
                       :mm.project/firewall-class     :public-bottom
                       :mm.project/runs-in-context    [(sup/eid-of :ctx/pub)
                                                       (sup/eid-of :ctx/priv)]}])
  (sup/seed-memory! :mem/dual :public :proj/dual)
  (testing "dual-context {C_pub, C_priv} + all other axes :public ⇒ :private
            (the MEET over the SET; private in ANY context wins — fork-1)"
    (is (= 2 (count (:mm.project/runs-in-context
                      (d/entity (db/db) (sup/eid-of :proj/dual)))))
        "sanity: the project really runs in TWO contexts (the card-many case)")
    (is (= :private (proj-sens :proj/dual))
        "a pick-one/first-context leg would widen this to :public — FAILS")
    (is (= :private (mem-sens :mem/dual))
        "and a memory owned by the dual-context project also resolves :private"))
  (testing "EMPTY / unresolvable runs-in-context set ⇒ :private (fail-closed)"
    ;; raw-transact a project with NO runs-in-context but otherwise all-:public
    (sup/raw-transact! [{:db/ident                      :proj/no-ctx
                         :dt/type                       :mm/Project
                         :mm.memory/name                "no-context"
                         :mm.project/ident              :proj/no-ctx
                         :mm.project/corpus-repo        "test-repo"
                         :mm.project/default-visibility :public
                         :mm.project/firewall-class     :public-bottom}])
    (is (= :private (proj-sens :proj/no-ctx))
        "empty runs-in-context must fail-closed :private, NOT compose :public off
         the neutral-element context leg (W1.deploy §6.1 fail-closed)")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A-1 (R-3 / CODEX-1 HIGH) — a MIXED resolvable+UNRESOLVABLE runs-in-context
;;   composes :private (fail-close).  The bare `(keep ref->eid)` idiom silently
;;   DROPPED an unresolvable member, so `{public-ctx, dangling}` narrowed the
;;   meet to {public} ⇒ :public (a WIDEN, violating most-restrictive).  The fix:
;;   any member that resolves to no live entity contributes :private to the leg.
;;
;;   Shape notes (load-bearing):
;;     • PRE-COMMIT spec, because a COMMITTED Datomic ref cannot dangle (the
;;       label core sees pre-commit specs via firewall-guard! / label-from-props).
;;     • the dangling member is an UNSEEDED IDENT keyword — `ref->eid` returns
;;       nil for it, the exact shape `keep` dropped.  A bare bogus long does NOT
;;       reproduce the leak: Datomic resolves a bare long to an empty entity
;;       whose nil firewall-class ALREADY fail-closes :private inside the leg.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a1-mixed-resolvable-unresolvable-context-fails-closed
  (sup/seed-context! :ctx/pub  :public-bottom)
  (sup/seed-context! :ctx/pub2 :public-bottom)              ; second resolvable public ctx
  (let [db          (db/db)                                 ; snapshot AFTER both seeds
        pub-ctx-eid (sup/eid-of :ctx/pub)
        pub2-eid    (sup/eid-of :ctx/pub2)
        dangling    :ctx/never-seeded-ghost          ; unseeded ident ⇒ ref->eid nil
        base-spec   {:dt/type                       :mm/Project
                     :mm.project/ident              :proj/precommit-mixed
                     :mm.project/corpus-repo        "test-repo"
                     :mm.project/default-visibility :public
                     :mm.project/firewall-class     :public-bottom}]
    (testing "sanity — the dangling ident really resolves to NO live entity"
      (is (nil? (ref/ref->eid db dangling))
          "the unseeded ident must be unresolvable for this to falsify the keep-drop"))
    (testing "control — the SAME spec with ONLY the resolvable public member is :public"
      (is (= :public (label/project-effective-sensitivity
                       db (assoc base-spec :mm.project/runs-in-context [pub-ctx-eid])))
          "an all-public single-context project composes :public (baseline)"))
    (testing "MIXED {resolvable-public, dangling} ⇒ :private (the fail-close)"
      (let [spec (assoc base-spec :mm.project/runs-in-context [pub-ctx-eid dangling])]
        (is (= :private (label/project-effective-sensitivity db spec))
            "an unresolvable member must WIDEN the meet toward :private, NOT be
             dropped — the bare (keep ref->eid) idiom would compose :public here")
        (is (= :private (:sensitivity (label/label-of db spec)))
            "the front-door label-of over the pre-commit spec agrees")
        (is (false? (:routes-to-public? (route/project-route db spec)))
            "and the route derived from the SAME shared core is not public (R-3 mirror)")))
    (testing "member ORDER is irrelevant — {dangling, resolvable-public} also :private"
      (is (= :private (label/project-effective-sensitivity
                        db (assoc base-spec :mm.project/runs-in-context [dangling pub-ctx-eid])))))
    (testing "TWO resolvable-public members (no dangling) still compose :public
              (guards against an over-broad fix that fail-closes any multi-member set)"
      (is (= :public (label/project-effective-sensitivity
                       db (assoc base-spec :mm.project/runs-in-context
                                 [pub-ctx-eid pub2-eid])))
          "two resolvable public contexts must remain :public — the fail-close is
           for UNRESOLVABLE members only, not for cardinality > 1"))))
