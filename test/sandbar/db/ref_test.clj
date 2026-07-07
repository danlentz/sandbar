(ns sandbar.db.ref-test
  "S7 BU-7 — falsification battery for the true-leaf ref→eid normalizer (BU-0).

   `sandbar.db.ref/ref->eid` is THE canonical `:db.type/ref` resolver shared
   by the card-many replace diff, `make`'s pre-transact coercion,
   `value-matches-range?`, the schema-shape GC, and — new in S7 — the firewall
   label plane + the clearance compartment reads.  Two properties are
   load-bearing and falsified here:

   - T-15 (SENTINEL-COLLAPSE CURE, S6-review #1): an ident KEYWORD resolves
     through `(:db/id (d/entity db kw))`, never `(:db/id kw)`.  A sentinel or
     interned ident-bearing ref (e.g. `:project/UNASSIGNED`) must resolve to
     its REAL eid, NEVER nil — a nil there would VOID ownership and let a
     sentinel-owned private memory read as unassigned/public.  This test FAILS
     against the pre-cure `(:db/id keyword)` implementation.
   - CA-5 (LEAF PURITY, ruling R14): `sandbar.db.ref`'s ns deps must contain
     NEITHER `sandbar.db.datatype` NOR `sandbar.db.datomic`.  A convenience
     arity pulling either would reintroduce the `ref → datatype → enforce →
     firewall.core` load cycle that BU-4 makes acyclic only because this ns is
     a true leaf.  The assertion parses the ns form statically (no load), so a
     future convenience arity fails the build here.

   Per bugs/clearance_helper_collapses_ident_bearing_project_refs_to_nil_confirmed_failclosed_latent_failopen_s6_mustfix_2026_07_06.md
   and bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md.

   Per interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.java.io :as io]
            [clojure.tools.namespace.parse :as ns-parse]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.ref :as ref]
            [sandbar.mcp.clearance :as clearance]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "ref-test" :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-15 — the sentinel-collapse cure (falsifier for `(:db/id keyword)`)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ref-eid-sentinel-no-collapse
  (testing "an ident keyword resolves to the sentinel's REAL eid, not nil"
    (let [db  (db/db)
          eid (ref/ref->eid db :project/UNASSIGNED)]
      (is (some? eid) ":project/UNASSIGNED must resolve to a live eid, not nil")
      (is (integer? eid))
      ;; The resolved eid IS the ident-bearing entity: round-trips back to ident.
      (is (= :project/UNASSIGNED (:db/ident (d/entity db eid))))))

  (testing "the :context/UNASSIGNED sentinel keyword likewise never collapses"
    (let [db  (db/db)
          eid (ref/ref->eid db :context/UNASSIGNED)]
      (is (some? eid))
      (is (= :context/UNASSIGNED (:db/ident (d/entity db eid))))))

  (testing "the naive (:db/id keyword) collapse this cures WOULD have been nil —
            proving the fix is load-bearing (a keyword carries no :db/id)"
    (is (nil? (:db/id :project/UNASSIGNED)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Every ref shape a caller can hand a :db.type/ref slot
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ref-eid-all-shapes
  (let [db  (db/db)
        eid (ref/ref->eid db :project/UNASSIGNED)]

    (testing "nil → nil (a non-edge)"
      (is (nil? (ref/ref->eid db nil))))

    (testing "a raw eid Long → itself (round-trips through d/entity)"
      (is (= eid (ref/ref->eid db eid))))

    (testing "a {:db/id eid} map → the :db/id read directly"
      (is (= eid (ref/ref->eid db {:db/id eid}))))

    (testing "a Datomic Entity → its :db/id"
      (is (= eid (ref/ref->eid db (d/entity db :project/UNASSIGNED)))))

    (testing "a single-key {:db/ident kw} upsert map → resolved via lookup-ref"
      (is (= eid (ref/ref->eid db {:db/ident :project/UNASSIGNED}))))

    (testing "a lookup-ref vector [:db/ident kw] → resolved via d/entity"
      (is (= eid (ref/ref->eid db [:db/ident :project/UNASSIGNED]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Total + exception-safe: unresolvable inputs → nil, never a throw / fabrication
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ref-eid-unresolvable-is-nil-never-throws
  (let [db (db/db)]
    (testing "an ident keyword that names no live entity → nil (not a throw)"
      (is (nil? (ref/ref->eid db :project/DOES-NOT-EXIST))))

    (testing "a bogus lookup-ref → nil (d/entity throw is caught)"
      ;; A lookup-ref whose unique-attr value names no live entity: d/entity
      ;; returns nil for the vector form, so ref->eid yields nil.
      (is (nil? (ref/ref->eid db [:db/ident :nope/nonexistent]))))

    (testing "an upsert map on a non-existent unique attr value → nil"
      (is (nil? (ref/ref->eid db {:db/ident :missing/thing}))))

    (testing "a lookup-ref on a NON-unique attribute → nil (d/entity THROWS
              IllegalArgumentException here — 'attribute should be marked
              :db.unique'; the try/catch totality contract catches it so a
              malformed carrier ref yields the §4.4 :skipped record instead of
              propagating an exception out of an EP-1 write path)"
      (is (nil? (ref/ref->eid db [:mm.memory/name "no-such-name"]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-16 — never-clear-nil (the clearance consumer of the canon; rode BU-0)
;;
;; The nil-aliasing fail-OPEN this falsifies: a cleared-projects member that
;; normalizes to nil, checked against a nil project compartment, satisfies
;; (contains? #{nil} nil) — clearing EVERY collapsed private compartment.
;; The guard: nil `project-eid` is NEVER positively cleared (short-circuit),
;; and nil-collapsing members strip via `keep`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest never-clear-nil
  (let [db (db/db)]
    (testing "a nil project-eid is NEVER positively cleared by a restricted
              principal, even when the cleared set carries a nil-collapsing
              member (the alias fail-OPEN)"
      (let [prin {:auth/cleared-projects #{:project/DOES-NOT-EXIST}}]
        (is (false? (clearance/principal-clears-project? db prin nil)))
        (is (false? (clearance/principal-clears-project? prin nil))
            "the pure db-free arity is equally never-clear-nil")))

    (testing "a keyword ident member resolves THROUGH the db (the T-15 cure at
              the clearance surface) and clears the REAL eid — never nil"
      (let [proj-eid (ref/ref->eid db :project/UNASSIGNED)
            prin     {:auth/cleared-projects #{:project/UNASSIGNED}}]
        (is (true?  (clearance/principal-clears-project? db prin proj-eid)))
        (is (false? (clearance/principal-clears-project? db prin nil)))))

    (testing "entity-compartment recovers sentinel ownership through the db
              arity — an ident-bearing owning-project ref never voids to nil"
      (let [proj-eid (ref/ref->eid db :project/UNASSIGNED)
            comp     (clearance/entity-compartment
                       db {:mm.memory/visibility :private
                           :mm.memory/owning-project :project/UNASSIGNED})]
        (is (= proj-eid (:project comp)))
        (is (= :private (:visibility comp)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CA-5 — the LEAF-PURITY ns-deps assertion (static parse, no load)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ns-deps-of
  "The set of namespace symbols `resource-path`'s ns form depends on, read
   statically from the source (never loaded).  Uses tools.namespace's ns-decl
   reader so :require / :use / :import shapes are handled uniformly."
  [resource-path]
  (with-open [rdr (java.io.PushbackReader. (io/reader (io/resource resource-path)))]
    (let [decl (ns-parse/read-ns-decl rdr)]
      (set (ns-parse/deps-from-ns-decl decl)))))

(deftest ref-ns-is-a-true-leaf
  (testing "sandbar.db.ref requires NEITHER datatype NOR datomic (ruling R14 /
            CA-5) — the leaf purity that keeps the post-BU-4
            datatype→enforce→firewall.core chain acyclic"
    (let [deps (ns-deps-of "sandbar/db/ref.clj")]
      (is (not (contains? deps 'sandbar.db.datatype))
          "leaf violation: sandbar.db.ref must NOT require sandbar.db.datatype")
      (is (not (contains? deps 'sandbar.db.datomic))
          "leaf violation: sandbar.db.ref must NOT require sandbar.db.datomic")
      ;; positive anchor: it DOES depend on datomic.api (its only allowed dep)
      (is (contains? deps 'datomic.api)))))
