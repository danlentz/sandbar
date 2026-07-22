(ns sandbar.deploy.closure-test
  "W1.deploy — the `db-firewall-closure` refuse-to-serve acceptance suite
  (falsification-first).  Covers the §6 gate rows DEP-2 (fault-injected
  refuse-to-serve), DEP-7 (closure label-compatibility — the poisoned/stale
  public-membership leak), and DEP-8 (closure scope-identity / private↔private
  reciprocity).  Each test FAILS against a wrong implementation — an impl that
  FILTERS the out-of-closure row instead of REFUSING the build, one that trusts
  `visible-projects` membership without re-checking each member's resolved label
  via the shared core, or one that trusts the declared `visible-projects`
  inverse without verifying the reciprocal `runs-in-context` edge.

  Fresh datomic:mem per test — never the live store (mirrors the S7 battery).
  Seeds via `sandbar.firewall.support` (RAW transact, so setup never trips a
  guard).  ENV: the fixture loads `:required-schema` from the classpath
  `config/config.edn`."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.deploy.closure :as closure]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.project.route :as route]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "w1deploy-closure" :auth? false}))

(defn- refusal-marker
  "Run `thunk`; return the `:sandbar/error` marker of any ex-info thrown, or
  `::no-throw` when it returns normally — so a test asserts the SPECIFIC
  refuse-to-serve marker fired, not merely that something threw."
  [thunk]
  (try (thunk) ::no-throw
       (catch clojure.lang.ExceptionInfo e
         (:sandbar/error (ex-data e) ::threw-unmarked))))

(defn- enroll-visible!
  "Set context `ctx-ident`'s `:mm.context/visible-projects` to the given
  `project-idents` (the corpus-canonical membership authority)."
  [ctx-ident & project-idents]
  (sup/raw-transact! [{:db/ident ctx-ident
                       :mm.context/visible-projects (mapv sup/eid-of project-idents)}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; public-bottom-context — the ∪ public leg anchor; exactly-one / none / many.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest public-bottom-context-cardinality
  (testing "NO stamp yet ⇒ nil (a pre-migration corpus; caller fails closed)"
    (is (nil? (closure/public-bottom-context (db/db)))))
  (testing "exactly ONE :public-bottom stamp ⇒ its eid"
    (sup/seed-context! :ctx/pub :public-bottom)
    (is (= (sup/eid-of :ctx/pub) (closure/public-bottom-context (db/db)))))
  (testing "TWO :public-bottom stamps ⇒ REFUSE (:ambiguous-public-bottom) — two
            public roots is a confidentiality integrity failure, not a pick"
    (sup/seed-context! :ctx/pub2 :public-bottom)
    (is (= :ambiguous-public-bottom
           (refusal-marker #(closure/public-bottom-context (db/db)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; label-compatible? / scope-context-sensitivity — the pure DEP-7 relation.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest label-compat-relation
  (testing "a PUBLIC scope admits ONLY :public members (a :private member is
            label-incompatible — the DEP-7 relation)"
    (is (true?  (closure/label-compatible? :public  :public)))
    (is (false? (closure/label-compatible? :private :public))))
  (testing "a PRIVATE scope admits any member (public leg validated separately)"
    (is (true? (closure/label-compatible? :private :private)))
    (is (true? (closure/label-compatible? :public  :private))))
  (testing "scope sensitivity derives from the context firewall-class (stamp)"
    (sup/seed-context! :ctx/pub :public-bottom)
    (sup/seed-context! :ctx/priv :project-isolated)
    (is (= :public  (closure/scope-context-sensitivity (d/entity (db/db) (sup/eid-of :ctx/pub)))))
    (is (= :private (closure/scope-context-sensitivity (d/entity (db/db) (sup/eid-of :ctx/priv)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-2 — FAULT INJECTION: a private row in a public-scope build ⇒ REFUSE to
;;   serve (not filter).  The heart of the phase.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep2-out-of-closure-row-refuses-to-serve
  ;; a clean public scope: public-bottom context, one public project enrolled,
  ;; one public row owned by it.
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (enroll-visible! :ctx/pub :proj/pub)
  (sup/seed-memory! :mem/pub :public :proj/pub)
  (let [pub-ctx (sup/eid-of :ctx/pub)]
    (testing "sanity — the public project resolves :public and is the sole member"
      (is (= #{(sup/eid-of :proj/pub)}
             (route/visible-projects (db/db) (d/entity (db/db) pub-ctx)))))
    (testing "NEGATIVE CONTROL — a clean public build (only public rows) SERVES
              (closure holds; guard returns nil; assert-serves! returns the db)"
      (let [cl (closure/closure-of (db/db) pub-ctx)]
        (is (contains? cl (sup/eid-of :proj/pub)))
        (is (nil? (closure/guard-session-db-closure! (db/db) cl))))
      (is (some? (closure/assert-serves! (db/db) pub-ctx))))
    ;; FAULT INJECTION — a private project + a private row leak into the build.
    (sup/seed-context! :ctx/priv :project-isolated)
    (sup/seed-project! :proj/priv :private :ctx/priv)
    (sup/seed-memory! :mem/priv :private :proj/priv)   ; ← out-of-closure row
    (testing "the closure for the public scope is UNCHANGED (still only proj/pub
              — the private project was never enrolled in the public context)"
      (is (= #{(sup/eid-of :proj/pub)} (closure/closure-of (db/db) pub-ctx))))
    (testing "out-of-closure-rows names the injected private row (the pure check)"
      (let [oob (closure/out-of-closure-rows (db/db) (closure/closure-of (db/db) pub-ctx))]
        (is (= 1 (count oob)))
        (is (= (sup/eid-of :mem/priv) (:row (first oob))))
        (is (= (sup/eid-of :proj/priv) (:owning-project (first oob))))))
    (testing "guard-session-db-closure! REFUSES the build (:db-firewall-closure-
              violation) — NOT a filtered serve.  FAILS against an impl that
              drops the row and serves"
      (is (= :db-firewall-closure-violation
             (refusal-marker
               #(closure/guard-session-db-closure! (db/db) (closure/closure-of (db/db) pub-ctx))))))
    (testing "assert-serves! (the wiring point) likewise REFUSES"
      (is (= :db-firewall-closure-violation
             (refusal-marker #(closure/assert-serves! (db/db) pub-ctx)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; content-class? — the taxonomy the DEP-2 row sweep ranges over.  Content
;;   (must-carry-owning-project) vs SUBSTRATE / container (exempt).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest content-class-taxonomy
  (testing "CONTENT classes (must carry owning-project): :mm/Memory + its
            Artifact / Signal / Guidance content branches"
    (is (true? (closure/content-class? (db/db) :mm/Memory)))
    (is (true? (closure/content-class? (db/db) :mm/Artifact)))
    (is (true? (closure/content-class? (db/db) :mm/Signal)))
    (is (true? (closure/content-class? (db/db) :mm/Guidance))))
  (testing "SUBSTRATE / container classes are EXEMPT from the content sweep:
            :mm/Meta descendants (incl. :mm/Context, :mm/Shape), :mm/Project
            (container/closure-member), :mm/Tag (:dt/Resource, NOT :mm/Memory),
            schema meta-classes, and nil"
    (is (false? (closure/content-class? (db/db) :mm/Meta)))
    (is (false? (closure/content-class? (db/db) :mm/Context)))
    (is (false? (closure/content-class? (db/db) :mm/Shape)))
    (is (false? (closure/content-class? (db/db) :mm/Project)))
    (is (false? (closure/content-class? (db/db) :mm/Tag)))
    (is (false? (closure/content-class? (db/db) :dt/Class)))
    (is (false? (closure/content-class? (db/db) nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-2 dimension (b) — a MEMORY-CONTENT row with NO owning-project datom is
;;   :project/UNASSIGNED (fail-closed) ⇒ in NO closure ⇒ REFUSE.  Closes the
;;   "leaked row with no owner slips past the owning-project sweep" hole.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep2-unassigned-content-row-refuses-to-serve
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (enroll-visible! :ctx/pub :proj/pub)
  (sup/seed-memory! :mem/pub :public :proj/pub)
  (let [pub-ctx (sup/eid-of :ctx/pub)]
    (testing "NEGATIVE CONTROL — a clean public build has NO unassigned content
              rows and SERVES (the shipped :mm/Shape seeds are :mm/Meta, exempt)"
      (is (empty? (closure/unassigned-content-rows (db/db))))
      (is (some? (closure/assert-serves! (db/db) pub-ctx))))
    ;; FAULT INJECTION — a :mm/Memory CONTENT row with NO owning-project datom.
    ;; Even flagged :public, an UNOWNED content row is :project/UNASSIGNED
    ;; (fail-closed private) — routable to no compartment, in no closure.
    (sup/raw-transact! [{:db/ident             :mem/orphan
                         :dt/type              :mm/Memory
                         :mm.memory/name       "orphan"
                         :mm.memory/visibility :public}])   ; ← NO :mm.memory/owning-project
    (testing "the orphan content row resolves to the :project/UNASSIGNED sentinel"
      (let [rows (closure/unassigned-content-rows (db/db))]
        (is (= 1 (count rows)))
        (is (= (sup/eid-of :mem/orphan)        (ffirst rows)))
        (is (= (sup/eid-of :project/UNASSIGNED) (second (first rows))))))
    (testing "guard-session-db-closure! REFUSES (:db-firewall-closure-violation) —
              an unowned content row is in NO closure.  FAILS against the
              (a)-only sweep that ignored no-owner rows"
      (is (= :db-firewall-closure-violation
             (refusal-marker
               #(closure/guard-session-db-closure!
                  (db/db) (closure/closure-of (db/db) pub-ctx))))))
    (testing "assert-serves! likewise REFUSES"
      (is (= :db-firewall-closure-violation
             (refusal-marker #(closure/assert-serves! (db/db) pub-ctx)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-2 dimension (c) — a bare PRIVATE :mm/Context injected into a public build
;;   carries no owning-project (invisible to the row sweep) ⇒ caught by the
;;   served-context check ⇒ REFUSE.  The bare-private-Context bypass.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep2-served-context-refuses-foreign-private-context
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (enroll-visible! :ctx/pub :proj/pub)
  (sup/seed-memory! :mem/pub :public :proj/pub)
  (let [pub-ctx (sup/eid-of :ctx/pub)]
    (testing "sanity — a fresh public build's served STAMPED contexts are just
              the public bottom; the shipped :context/UNASSIGNED sentinel (no
              firewall-class stamp) is exempt"
      (is (= #{pub-ctx} (set (map first (closure/served-contexts (db/db)))))))
    (testing "NEGATIVE CONTROL — the clean public build's context check PASSES"
      (is (nil? (closure/guard-served-contexts!
                  (db/db) pub-ctx (closure/closure-of (db/db) pub-ctx))))
      (is (some? (closure/assert-serves! (db/db) pub-ctx))))
    ;; FAULT INJECTION — a bare PRIVATE :mm/Context (:project-isolated), enrolled
    ;; in NOTHING, owned by NO project.  It carries no owning-project, so the
    ;; (a)/(b) row sweep cannot see it — only the (c) context check catches it.
    (sup/seed-context! :ctx/leak :project-isolated)
    (testing "the leaked private context is now a served STAMPED context"
      (is (contains? (set (map first (closure/served-contexts (db/db))))
                     (sup/eid-of :ctx/leak))))
    (testing "out-of-closure-contexts names it — private, not the scope's own,
              not the public bottom, not a runs-in-context of any in-closure
              public project"
      (let [oob (closure/out-of-closure-contexts
                  (db/db) pub-ctx (closure/closure-of (db/db) pub-ctx))]
        (is (= 1 (count oob)))
        (is (= (sup/eid-of :ctx/leak) (:context (first oob))))
        (is (= :private (:context-sensitivity (first oob))))))
    (testing "the ROW sweep stays CLEAN — a bare context carries no owning-project,
              so ONLY the (c) context check is load-bearing here"
      (is (empty? (closure/out-of-closure-rows
                    (db/db) (closure/closure-of (db/db) pub-ctx)))))
    (testing "guard-served-contexts! REFUSES (:db-firewall-closure-violation,
              :out-of-closure-context).  FAILS against a check that only sweeps
              owning-project rows"
      (is (= :db-firewall-closure-violation
             (refusal-marker
               #(closure/guard-served-contexts!
                  (db/db) pub-ctx (closure/closure-of (db/db) pub-ctx))))))
    (testing "assert-serves! likewise REFUSES (the composite runs the context
              check even though the row sweep is clean)"
      (is (= :db-firewall-closure-violation
             (refusal-marker #(closure/assert-serves! (db/db) pub-ctx)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-7 — a :private-resolving project poisoned into the PUBLIC context's
;;   visible-projects ⇒ REFUSE at closure CONSTRUCTION (before ingest).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep7-label-incompatible-member-refuses-build
  (sup/seed-context! :ctx/pub  :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  ;; a genuinely public project (positive control member)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  ;; a DUAL-context project: runs in BOTH the public bottom AND a private
  ;; context ⇒ project-effective-sensitivity :private (most-restrictive).  It
  ;; RECIPROCATES ctx/pub (so DEP-8 passes and DEP-7 is the check that fires).
  (sup/raw-transact! [{:db/ident :proj/sneaky
                       :dt/type :mm/Project
                       :mm.memory/name "sneaky"
                       :mm.project/ident :proj/sneaky
                       :mm.project/corpus-repo "test-repo"
                       :mm.project/default-visibility :public
                       :mm.project/firewall-class :public-bottom
                       :mm.project/runs-in-context [(sup/eid-of :ctx/pub)
                                                    (sup/eid-of :ctx/priv)]}])
  (testing "sanity — sneaky resolves :private (dual-context) yet reciprocates ctx/pub"
    (is (= :private (label/project-effective-sensitivity
                      (db/db) (d/entity (db/db) (sup/eid-of :proj/sneaky)))))
    (is (contains? (label/project-context-eids
                     (db/db) (d/entity (db/db) (sup/eid-of :proj/sneaky)))
                   (sup/eid-of :ctx/pub))))
  (testing "POSITIVE CONTROL — with only the genuinely-public member enrolled,
            closure-of the public scope succeeds"
    (enroll-visible! :ctx/pub :proj/pub)
    (is (= #{(sup/eid-of :proj/pub)} (closure/closure-of (db/db) (sup/eid-of :ctx/pub)))))
  (testing "POISON — enrolling the :private-resolving project in the PUBLIC
            context's visible-projects REFUSES at construction
            (:closure-label-incompatible).  FAILS against an impl that trusts
            membership without re-checking each member's project-level label"
    (enroll-visible! :ctx/pub :proj/pub :proj/sneaky)
    (is (= :closure-label-incompatible
           (refusal-marker #(closure/closure-of (db/db) (sup/eid-of :ctx/pub)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-8 — a project belonging to a DIFFERENT private context poisoned into
;;   context C1's visible-projects (both :private, so DEP-7 passes) ⇒ REFUSE at
;;   construction on the missing reciprocal runs-in-context edge.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep8-scope-identity-reciprocity-refuses-build
  (sup/seed-context! :ctx/c1 :project-isolated)
  (sup/seed-context! :ctx/c2 :project-isolated)
  ;; a project that runs in C1 (reciprocates) and one that runs ONLY in C2.
  (sup/seed-project! :proj/in-c1 :private :ctx/c1)
  (sup/seed-project! :proj/in-c2 :private :ctx/c2)
  (testing "sanity — both contexts are :private, so the DEP-7 label check would
            PASS for either member (isolating DEP-8 as the firing check)"
    (is (= :private (closure/scope-context-sensitivity (d/entity (db/db) (sup/eid-of :ctx/c1)))))
    (is (= :private (closure/scope-context-sensitivity (d/entity (db/db) (sup/eid-of :ctx/c2))))))
  (testing "POSITIVE CONTROL — C1 enrolling its OWN member (reciprocal edge
            present) constructs a closure"
    (enroll-visible! :ctx/c1 :proj/in-c1)
    (is (= #{(sup/eid-of :proj/in-c1)} (closure/closure-of (db/db) (sup/eid-of :ctx/c1)))))
  (testing "POISON — C1 naming proj/in-c2 (which belongs to C2, NOT C1) REFUSES
            at construction (:closure-scope-identity-violation) on the missing
            reciprocal C1 ∈ runs-in-context(proj/in-c2).  FAILS against an impl
            that trusts the declared visible-projects inverse — the cross-private
            (P3) ingest path DEP-7's public-only falsifier misses"
    (enroll-visible! :ctx/c1 :proj/in-c1 :proj/in-c2)
    (is (= :closure-scope-identity-violation
           (refusal-marker #(closure/closure-of (db/db) (sup/eid-of :ctx/c1)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Composition — a PRIVATE scope's closure is (its members) ∪ (public leg), and a
;;   poisoned PUBLIC bottom refuses even a private scope's build.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest private-closure-unions-public-leg
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-context! :ctx/work :project-isolated)
  (sup/seed-project! :proj/pub  :public  :ctx/pub  :public-bottom)
  (sup/seed-project! :proj/work :private :ctx/work)
  (enroll-visible! :ctx/pub :proj/pub)
  (enroll-visible! :ctx/work :proj/work)
  (testing "a private scope's closure = its own member ∪ the public leg (a
            private session may read public material — private→public permitted)"
    (let [cl (closure/closure-of (db/db) (sup/eid-of :ctx/work))]
      (is (contains? cl (sup/eid-of :proj/work)) "its own private member")
      (is (contains? cl (sup/eid-of :proj/pub))  "∪ the public leg")))
  (testing "a POISONED public bottom (a :private-resolving project enrolled in
            the public context) REFUSES even a PRIVATE scope's build — the public
            leg's own LABEL integrity is re-checked when pulled into any closure.
            The poison RECIPROCATES ctx/pub (so DEP-8 passes) but is dual-context
            ⇒ :private, so the DEP-7 label check is what fires"
    (sup/raw-transact! [{:db/ident :proj/sneaky
                         :dt/type :mm/Project
                         :mm.memory/name "sneaky"
                         :mm.project/ident :proj/sneaky
                         :mm.project/corpus-repo "test-repo"
                         :mm.project/default-visibility :public
                         :mm.project/firewall-class :public-bottom
                         :mm.project/runs-in-context [(sup/eid-of :ctx/pub)
                                                      (sup/eid-of :ctx/work)]}])
    (enroll-visible! :ctx/pub :proj/pub :proj/sneaky)   ; :private-resolving, reciprocal ⇒ DEP-7 fires
    (is (= :closure-label-incompatible
           (refusal-marker #(closure/closure-of (db/db) (sup/eid-of :ctx/work)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DEP-7 at the POST-BUILD WIRING POINT — closure MEMBERSHIP alone must never
;;   bless a poisoned closure (W1.deploy §3's ∀-row check vs §6.1: membership
;;   authority is NOT label-license).  The poisoned member's OWN row passes a
;;   membership-only row sweep (owning-project ∈ declared visible-projects), so
;;   the ONLY thing standing between that row and a served public build is the
;;   label gate inside the closure constructor assert-serves! runs.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dep7-post-build-wiring-point-refuses-poisoned-membership
  (sup/seed-context! :ctx/pub  :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (enroll-visible! :ctx/pub :proj/pub)
  (sup/seed-memory! :mem/pub :public :proj/pub)
  (let [pub-ctx (sup/eid-of :ctx/pub)]
    (testing "POSITIVE CONTROL — the clean public build SERVES at the wiring point"
      (is (some? (closure/assert-serves! (db/db) pub-ctx))))
    ;; POISON — a :private-resolving DUAL-context project (reciprocates ctx/pub,
    ;; so DEP-8 passes and DEP-7 is the firing gate) is enrolled in the PUBLIC
    ;; context's visible-projects, AND a row it OWNS is present in the build.
    ;; (:ctx/priv is seeded HERE, as part of the poison arm — a bare private
    ;; context is itself (c)-inadmissible, which would mask the DEP-7 control.)
    (sup/seed-context! :ctx/priv :project-isolated)
    (sup/raw-transact! [{:db/ident :proj/sneaky
                         :dt/type :mm/Project
                         :mm.memory/name "sneaky"
                         :mm.project/ident :proj/sneaky
                         :mm.project/corpus-repo "test-repo"
                         :mm.project/default-visibility :public
                         :mm.project/firewall-class :public-bottom
                         :mm.project/runs-in-context [(sup/eid-of :ctx/pub)
                                                      (sup/eid-of :ctx/priv)]}])
    (enroll-visible! :ctx/pub :proj/pub :proj/sneaky)
    (sup/seed-memory! :mem/sneaky :private :proj/sneaky)
    (testing "the trap is armed: the poisoned row's owning-project IS a declared
              member, so a MEMBERSHIP-ONLY closure (raw visible-projects,
              unchecked) BLESSES it — the row sweep over that naive closure
              finds NOTHING wrong.  This is the exact hole DEP-7 closes"
      (let [membership-only (route/visible-projects (db/db) (d/entity (db/db) pub-ctx))]
        (is (contains? membership-only (sup/eid-of :proj/sneaky))
            "the poisoned project is a declared member")
        (is (empty? (closure/out-of-closure-rows (db/db) membership-only))
            "membership alone blesses the private row — the ∀-row check cannot
             refuse it, so the label gate must")))
    (testing "assert-serves! — the post-build wiring point — REFUSES with the
              DEP-7 marker at closure CONSTRUCTION, before the row sweep could
              bless.  FAILS against an impl whose wiring point builds its
              closure from membership alone (no per-member label re-check via
              the shared core)"
      (is (= :closure-label-incompatible
             (refusal-marker #(closure/assert-serves! (db/db) pub-ctx)))))))
