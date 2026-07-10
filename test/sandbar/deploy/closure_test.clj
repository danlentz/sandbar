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
