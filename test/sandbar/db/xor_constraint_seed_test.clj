(ns sandbar.db.xor-constraint-seed-test
  "Tests for the schema-seeded :mm.shape/XorConstraint sub-entity discipline —
   stable :db/idents prevent reload proliferation, and
   prune-duplicate-seed-constraint-subentities! self-heals DBs that already
   accumulated duplicates.

   Root cause: :mm.shape/xor-constraints is :db.type/ref cardinality-many
   WITHOUT :db/isComponent, and initialize-db! retransacts schema on every
   boot.  A tempid-only seed sub-entity resolved to a NEW eid each reload +
   APPENDED to the slot — the sub-entities proliferated (~40/shape observed;
   aggregate.count = 80 vs 2) and each seed shape's slot accumulated duplicate
   refs.  The fix gives the 2 seed sub-entities stable :db/idents (upsert →
   idempotent) + a surgical self-heal migration.

   Per observations/schema_seeded_ref_subentities_without_db_ident_proliferate_on_reload
   + interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21.
   Sibling to sandbar.db.datomic-set-replace-test (the parallel additive-
   accumulation fix for cardinality-many class meta-slots)."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture: fresh in-memory Datomic DB with the full required-schema loaded
;; via the production load path (db/load-all-schema!, which applies the
;; set-replace pre-pass + fires post-reload handlers).  Binds *conn* + *uri*.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *conn* nil)
(def ^:dynamic *uri* nil)

(defn with-schema-loaded-db [test-fn]
  (let [uri (str "datomic:mem://xor-constraint-seed-test-" (random-uuid))]
    (d/create-database uri)
    (let [c (d/connect uri)]
      (reset! db/**conn* c)
      (db/load-all-schema! uri)
      (binding [*conn* c
                *uri*  uri]
        (try
          (test-fn)
          (finally
            (reset! db/**conn* nil)
            (d/release c)
            (d/delete-database uri)))))))

(use-fixtures :each with-schema-loaded-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- xor-count
  "Total :mm.shape/XorConstraint entities in the DB (mirrors the runtime
  `aggregate.count :mm.shape/XorConstraint` verb)."
  [db]
  (or (d/q '[:find (count ?e) .
             :where [?e :dt/type :mm.shape/XorConstraint]]
           db)
      0))

(defn- slot-count
  "Number of xor-constraints on a seed shape (mirrors `shape.list`)."
  [db shape-ident]
  (count (:mm.shape/xor-constraints (d/entity db shape-ident))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests — recurrence prevention (stable :db/ident)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest single-load-seeds-exactly-2-xor-constraints
  (testing "A single schema load seeds exactly 2 XorConstraints, 1 per seed shape"
    (let [dbv (d/db *conn*)]
      (is (= 2 (xor-count dbv)))
      (is (= 1 (slot-count dbv :memory.shapes/interval-begins-at-xor)))
      (is (= 1 (slot-count dbv :memory.shapes/interval-ends-at-xor))))))

(deftest seed-xor-shapes-idempotent-across-reload
  (testing "Reloading schema (simulating initialize-db! restarts) does NOT
            proliferate seed XorConstraints — the :db/ident upsert keeps them
            at 2 and each seed shape's slot at 1"
    ;; Two more schema loads on top of the fixture's initial load = 3 boots.
    (db/load-all-schema! *uri*)
    (db/load-all-schema! *uri*)
    (let [dbv (d/db *conn*)]
      (is (= 2 (xor-count dbv))
          "XorConstraint count stays 2 across reloads (regression: was 4, 6, ...)")
      (is (= 1 (slot-count dbv :memory.shapes/interval-begins-at-xor))
          "begins-at seed shape carries exactly 1 xor-constraint")
      (is (= 1 (slot-count dbv :memory.shapes/interval-ends-at-xor))
          "ends-at seed shape carries exactly 1 xor-constraint"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests — self-heal migration (prune-duplicate-seed-constraint-subentities!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- inject-duplicate-xor-constraints!
  "Simulate a pre-:db/ident-fix DB: attach `n` extra (tempid-only, non-idented)
  XorConstraint sub-entities to a seed shape's slot, exactly as the buggy
  reload path did."
  [shape-ident n]
  @(d/transact *conn*
     (let [tempids (repeatedly n #(d/tempid :db.part/user))]
       (conj
        (mapv (fn [t]
                {:db/id t
                 :dt/type :mm.shape/XorConstraint
                 :mm.shape.xor/slot-a :mm.interval/begins-at-instant
                 :mm.shape.xor/slot-b :mm.interval/begins-at-time})
              tempids)
        {:db/id shape-ident
         :mm.shape/xor-constraints (vec tempids)}))))

(deftest prune-heals-accumulated-duplicates
  (testing "prune-duplicate-seed-constraint-subentities! retracts + GCs the
            duplicate sub-entities, restoring count 2 / slot 1"
    (inject-duplicate-xor-constraints! :memory.shapes/interval-begins-at-xor 38)
    (let [dbv (d/db *conn*)]
      (is (= 40 (xor-count dbv)) "38 dupes injected on top of the 2 canonical")
      (is (= 39 (slot-count dbv :memory.shapes/interval-begins-at-xor))))
    (let [pruned (db/prune-duplicate-seed-constraint-subentities! *uri*)
          dbv    (d/db *conn*)]
      (is (= 38 pruned) "returns the count of pruned duplicates")
      (is (= 2 (xor-count dbv)) "count restored to 2")
      (is (= 1 (slot-count dbv :memory.shapes/interval-begins-at-xor))
          "begins-at slot restored to its canonical sub-entity")
      (is (= 1 (slot-count dbv :memory.shapes/interval-ends-at-xor))
          "ends-at slot untouched (still 1)")
      ;; The surviving begins-at constraint is the canonical idented one.  The
      ;; Datomic Entity API renders the idented ref as its :db/ident keyword.
      (is (= #{:memory.shapes.xor/interval-begins-at}
             (:mm.shape/xor-constraints
              (d/entity dbv :memory.shapes/interval-begins-at-xor)))))))

(deftest prune-is-idempotent-and-surgical
  (testing "prune is a no-op on a healed DB, and never touches client-authored
            (non-seed) constraint sub-entities"
    ;; No dupes → prunes nothing.
    (is (= 0 (db/prune-duplicate-seed-constraint-subentities! *uri*)))
    (is (= 2 (xor-count (d/db *conn*))))
    ;; A client-authored shape carrying its own (legitimately non-idented)
    ;; XorConstraint must survive prune.
    (let [t-con   (d/tempid :db.part/user)
          t-shape (d/tempid :db.part/user)]
      @(d/transact *conn*
         [{:db/id t-con
           :dt/type :mm.shape/XorConstraint
           :mm.shape.xor/slot-a :mm.memory/name
           :mm.shape.xor/slot-b :mm.memory/description}
          {:db/id t-shape
           :dt/type :mm/Shape
           :mm.shape/shape-id "client-authored-xor"
           :mm.shape/applies-to :mm/Memory
           :mm.shape/description "client shape, not schema-seeded"
           :mm.shape/xor-constraints [t-con]}]))
    (is (= 3 (xor-count (d/db *conn*))) "client constraint present")
    (is (= 0 (db/prune-duplicate-seed-constraint-subentities! *uri*))
        "prune leaves the client-authored constraint alone")
    (is (= 3 (xor-count (d/db *conn*))) "client constraint survives")))
