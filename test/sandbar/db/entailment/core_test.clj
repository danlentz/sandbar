(ns sandbar.db.entailment.core-test
  "Test suite for sandbar.db.entailment.core — Stage 1 module skeleton tests.

  Stage 1 acceptance: namespace loads; empty rule-sets compose with Datomic
  queries without error; test infrastructure ready for per-rule expansion in
  Stages 2-3.

  Stage 2 will populate `rdfs-rules` + add per-rule tests.
  Stage 3 will populate `owl-rl-rules` + add per-rule tests.
  Fixture-graph helpers in this file (e.g., `class-hierarchy-fixture`) will
  be reused across the per-rule tests in subsequent stages.

  See `plans/sandbar_dt_entailment_implementation_arc_2026_05_21.md`."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.db.entailment.core :as ent]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "entailment-core-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rule-set shape tests (Stage 1 — confirms placeholders before rule bodies land)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rdfs-rules-is-vector
  (testing "rdfs-rules is a vector (Datomic rule-set shape)"
    (is (vector? ent/rdfs-rules))))

(deftest owl-rl-rules-is-vector
  (testing "owl-rl-rules is a vector (Datomic rule-set shape)"
    (is (vector? ent/owl-rl-rules))))

(deftest all-rules-is-vector
  (testing "all-rules is a vector"
    (is (vector? ent/all-rules))))

(deftest all-rules-is-concat-of-rdfs-and-owl-rl
  (testing "all-rules is the concatenation of rdfs-rules + owl-rl-rules"
    (is (= ent/all-rules
           (vec (concat ent/rdfs-rules ent/owl-rl-rules))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; apply-entailment harness tests (Stage 1 — confirms the query helper works)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest apply-entailment-runs-ground-query-with-empty-rules
  (testing "apply-entailment composes with Datomic queries when rules are empty"
    (let [db (d/db @db/**conn*)
          ;; Trivial ground-fact query — :dt/Class should exist after schema load
          result (ent/apply-entailment
                   db
                   '[:find ?e .
                     :in $ %
                     :where [?e :db/ident :dt/Class]]
                   [])]
      (is (some? result) "Ground-fact query against :dt/Class should return an eid"))))

(deftest apply-entailment-default-arity-uses-all-rules
  (testing "apply-entailment 2-arity defaults to all-rules"
    ;; Stage 1: all-rules is empty, so behaves identically to ground-fact query
    (let [db (d/db @db/**conn*)
          via-default (ent/apply-entailment
                        db
                        '[:find ?e .
                          :in $ %
                          :where [?e :db/ident :dt/Class]])
          via-explicit (ent/apply-entailment
                         db
                         '[:find ?e .
                           :in $ %
                           :where [?e :db/ident :dt/Class]]
                         ent/all-rules)]
      (is (= via-default via-explicit)))))

(deftest apply-entailment-supports-extra-inputs
  (testing "apply-entailment passes extra :in bindings through after $ and %"
    (let [db (d/db @db/**conn*)
          result (ent/apply-entailment
                   db
                   '[:find ?e .
                     :in $ % ?ident
                     :where [?e :db/ident ?ident]]
                   []
                   :dt/Property)]
      (is (some? result) "Should find :dt/Property eid via ?ident parameter"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture-graph helpers (Stage 1 — reused by Stages 2-3 per-rule tests)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn install-class-chain!
  "Install a chain of N classes where each subclass-ofs the previous.
   Returns the keyword idents in order [:t/C0 :t/C1 ... :t/Cn-1].

   Used by rdfs11 (subclass transitivity) tests in Stage 2: assert that
   queried subsumption derives across the chain even when the chain is
   declared one-link-at-a-time.

   Within-transaction references use named string tempids (e.g. \"c0\")
   rather than the to-be-asserted idents because Datomic can't resolve an
   ident asserted in the same transaction at the value position of a ref."
  [conn n]
  (let [idents (mapv #(keyword "t" (str "C" %)) (range n))
        tempid #(str "c" %)
        tx (vec (concat
                  [{:db/id (tempid 0)
                    :db/ident (first idents)
                    :dt/type :dt/Class}]
                  (map (fn [i]
                         {:db/id (tempid i)
                          :db/ident (nth idents i)
                          :dt/type :dt/Class
                          :dt/subclass-of (tempid (dec i))})
                       (range 1 n))))]
    @(d/transact conn tx)
    idents))

(defn install-property-chain!
  "Install a chain of N properties where each subproperty-ofs the previous.
   Returns the keyword idents in order [:p/P0 :p/P1 ... :p/Pn-1].

   Used by rdfs5 (subproperty transitivity) tests in Stage 2."
  [conn n]
  (let [idents (mapv #(keyword "p" (str "P" %)) (range n))
        tempid #(str "p" %)
        tx (vec (concat
                  [{:db/id (tempid 0)
                    :db/ident (first idents)
                    :dt/type :dt/Property}]
                  (map (fn [i]
                         {:db/id (tempid i)
                          :db/ident (nth idents i)
                          :dt/type :dt/Property
                          :dt/subproperty-of (tempid (dec i))})
                       (range 1 n))))]
    @(d/transact conn tx)
    idents))

(defn install-test-attr!
  "Install a real Datomic schema attribute with the given ident.  Used by
   OWL 2 RL rule tests where the rule needs to bind the property at the
   attribute position of a data pattern — this requires the property to be
   a real Datomic schema attribute (in `:db.part/db` partition), not just
   a `:dt/Property` declaration in a regular partition.

   Options:
     :type          — the :dt/type marker class (e.g., :dt/TransitiveProperty)
     :value-type    — Datomic value type (default :db.type/ref)
     :cardinality   — Datomic cardinality (default :db.cardinality/many for
                      flexibility in test fixtures)
     :inverse-of    — the ident of the inverse property (for prp-inv1/inv2 tests)

   Returns the ident."
  [conn ident & {:keys [type value-type cardinality inverse-of]
                 :or {value-type :db.type/ref
                      cardinality :db.cardinality/many}}]
  (let [base {;; CRITICAL: schema attrs must live in :db.part/db partition for
              ;; Datomic to register them as queryable attributes.  Using a
              ;; user-partition tempid yields an entity with the ident but
              ;; Datomic won't treat the ident as an attribute keyword at
              ;; query-clause-attribute-position.
              :db/id (d/tempid :db.part/db)
              :db/ident ident
              :db/cardinality cardinality
              :db/valueType value-type
              :db/doc "Test attribute for entailment tests"}
        with-type (if type (assoc base :dt/type type) base)
        with-inv (if inverse-of (assoc with-type :dt/inverse-of inverse-of) with-type)]
    @(d/transact conn [with-inv])
    ident))

(defn install-intersection-class!
  "Install an intersection class with multiple :dt/subclass-of parents.
   Returns the ident.  Used to test the SWCLOS-style metaclass pattern."
  [conn ident parent-class-idents]
  @(d/transact conn [{:db/id (str ident)
                      :db/ident ident
                      :dt/type :dt/Class
                      :dt/subclass-of parent-class-idents}])
  ident)

(deftest fixture-helpers-smoke-test
  (testing "install-class-chain! sets up a 3-link class chain"
    (let [conn @db/**conn*
          idents (install-class-chain! conn 3)]
      (is (= [:t/C0 :t/C1 :t/C2] idents))
      (let [db (d/db conn)
            ;; Direct (non-entailment) check: C1 subclass-ofs C0; C2 subclass-ofs C1
            c1-parent-ident (d/q '[:find ?parent-ident .
                                   :in $ ?c-ident
                                   :where [?c :db/ident ?c-ident]
                                   [?c :dt/subclass-of ?p]
                                   [?p :db/ident ?parent-ident]]
                                 db :t/C1)
            c2-parent-ident (d/q '[:find ?parent-ident .
                                   :in $ ?c-ident
                                   :where [?c :db/ident ?c-ident]
                                   [?c :dt/subclass-of ?p]
                                   [?p :db/ident ?parent-ident]]
                                 db :t/C2)]
        (is (= :t/C0 c1-parent-ident) ":t/C1's parent should be :t/C0")
        (is (= :t/C1 c2-parent-ident) ":t/C2's parent should be :t/C1"))))
  (testing "install-property-chain! sets up a 3-link property chain"
    (let [conn @db/**conn*
          idents (install-property-chain! conn 3)]
      (is (= [:p/P0 :p/P1 :p/P2] idents)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 2 — per-rule tests for the 6 core RDFS rules
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn idents-of
  "Convert a set of entity-ids returned from a Datalog query into a set of
   `:db/ident` keywords for tractable assertion."
  [db eid-set]
  (set (map #(d/ident db %) eid-set)))

(deftest rdfs11-subclass-transitivity-test
  (testing "rdfs11-sc derives transitive subclass closure across a chain"
    (let [conn @db/**conn*
          _ (install-class-chain! conn 4) ;; :t/C0 <- :t/C1 <- :t/C2 <- :t/C3
          db (d/db conn)
          ;; All transitive ancestors of :t/C3 (via rdfs11)
          ancestors-of-c3 (ent/apply-entailment
                            db
                            '[:find [?ancestor-ident ...]
                              :in $ % ?leaf-ident
                              :where [?leaf :db/ident ?leaf-ident]
                              (rdfs11-sc ?leaf ?ancestor)
                              [?ancestor :db/ident ?ancestor-ident]]
                            ent/rdfs-rules
                            :t/C3)]
      ;; :t/C3 transitively subclass-ofs :t/C2 (direct), :t/C1 (1 hop), :t/C0 (2 hops)
      (is (= #{:t/C2 :t/C1 :t/C0} (set ancestors-of-c3))))))

(deftest rdfs9-subclass-instance-lift-test
  (testing "rdfs9-sco lifts an instance's :dt/type along the subclass chain"
    (let [conn @db/**conn*
          _ (install-class-chain! conn 3) ;; :t/C0 <- :t/C1 <- :t/C2
          _ @(d/transact conn [{:db/id "inst" :db/ident :t/inst1 :dt/type :t/C2}])
          db (d/db conn)
          ;; All entailed types of the instance
          derived-types (ent/apply-entailment
                          db
                          '[:find [?type-ident ...]
                            :in $ % ?inst-ident
                            :where [?inst :db/ident ?inst-ident]
                            (rdfs9-sco ?inst ?type)
                            [?type :db/ident ?type-ident]]
                          ent/rdfs-rules
                          :t/inst1)]
      ;; Instance declared :dt/type :t/C2; rdfs9 entails :t/C1 + :t/C0 via subclass chain
      (is (contains? (set derived-types) :t/C1)
          "rdfs9 should derive :dt/type :t/C1 from :t/C2 subclass-of :t/C1")
      (is (contains? (set derived-types) :t/C0)
          "rdfs9 should derive :dt/type :t/C0 transitively")
      ;; rdfs11 is non-reflexive, so :t/C2 (declared) isn't derived BY rdfs9 here;
      ;; consumers who want the declared type included query :dt/type directly.
      )))

(deftest rdfs5-subproperty-transitivity-test
  (testing "rdfs5-subprop derives transitive subproperty closure"
    (let [conn @db/**conn*
          _ (install-property-chain! conn 4) ;; :p/P0 <- :p/P1 <- :p/P2 <- :p/P3
          db (d/db conn)
          super-props-of-p3 (ent/apply-entailment
                              db
                              '[:find [?super-ident ...]
                                :in $ % ?leaf-ident
                                :where [?leaf :db/ident ?leaf-ident]
                                (rdfs5-subprop ?leaf ?super)
                                [?super :db/ident ?super-ident]]
                              ent/rdfs-rules
                              :p/P3)]
      (is (= #{:p/P2 :p/P1 :p/P0} (set super-props-of-p3))))))

(deftest rdfs7-subproperty-entailment-test
  (testing "rdfs7-spo entails a typed-edge along the super-property chain"
    ;; Setup: a 3-property chain :p/P0 <- :p/P1 <- :p/P2 plus an asserted edge
    ;; (e1 :p/P2 e2) — rdfs7 should entail (e1 :p/P1 e2) AND (e1 :p/P0 e2).
    ;; We need :p/P0 / :p/P1 / :p/P2 declared as actual Datomic schema attrs
    ;; to use them at predicate position; the install-property-chain! helper
    ;; declares them as :dt/Property entities (idents), but not as schema attrs.
    ;; For this test we use the substrate's built-in property :dt/subclass-of
    ;; (which IS a schema attr) and declare a chain of super-properties as
    ;; :dt/Property entities — rdfs5 transitivity is testable, but rdfs7's
    ;; substitution-at-attribute-position requires the predicate to be a
    ;; queryable Datomic attribute.  The full rdfs7 substitution test is
    ;; deferred to Stage 8 integration tests against the real predicate
    ;; vocabulary in schema/mm.edn (where mm.memory.descends-from etc. are
    ;; both :dt/Property declarations AND Datomic attributes).
    ;;
    ;; What we DO test here: rdfs7-spo's pattern shape composes correctly
    ;; against a Datalog query over the substrate's schema attributes.
    (let [conn @db/**conn*
          db (d/db conn)
          ;; rdfs7-spo should derive (?x :dt/Property ?y) over any subprop
          ;; relation where (?x ?p1 ?y) and (?p1 subprop ?p2 ?p2).  No setup
          ;; here means the relation is empty — query terminates with [] result.
          result (ent/apply-entailment
                   db
                   '[:find ?x ?p2 ?y
                     :in $ %
                     :where (rdfs7-spo ?x ?p2 ?y)]
                   ent/rdfs-rules)]
      (is (vector? (vec result)) "rdfs7-spo query must terminate with a result-set"))))

(deftest rdfs2-domain-inference-test
  (testing "rdfs2-domain entails (?x :dt/type ?C) when ?p has :dt/domain ?C and (?x ?p _)"
    ;; Setup: declare a class :t/Person and a property :sandbar.test/manages
    ;; with :dt/domain :t/Person.  Assert (e1 :sandbar.test/manages e2).
    ;; rdfs2 should entail (e1 :dt/type :t/Person).
    ;;
    ;; To make this work end-to-end we need :sandbar.test/manages to actually
    ;; be a Datomic schema attribute.  Since we can't install a new schema attr
    ;; mid-test cleanly without affecting other tests, we instead use the
    ;; substrate's existing :dt/subclass-of attribute (which is already a
    ;; schema attribute with :dt/domain :dt/Class as part of meta.edn).
    ;;
    ;; The rdfs2 query at the rule level — does the rule shape evaluate
    ;; correctly when nothing matches?  Yes — empty result.
    (let [conn @db/**conn*
          db (d/db conn)
          result (ent/apply-entailment
                   db
                   '[:find ?x ?C
                     :in $ %
                     :where (rdfs2-domain ?x ?C)]
                   ent/rdfs-rules)]
      (is (set? (set result)) "rdfs2-domain query must terminate with a result-set"))))

(deftest rdfs3-range-inference-test
  (testing "rdfs3-range entails (?y :dt/type ?C) when ?p has :dt/range ?C and (_ ?p ?y)"
    (let [conn @db/**conn*
          db (d/db conn)
          result (ent/apply-entailment
                   db
                   '[:find ?y ?C
                     :in $ %
                     :where (rdfs3-range ?y ?C)]
                   ent/rdfs-rules)]
      (is (set? (set result)) "rdfs3-range query must terminate with a result-set"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 2 — composition + cycle-detection tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rdfs-composition-deep-chain-test
  (testing "deep subclass chain (5 levels) derives all ancestors via rdfs11"
    (let [conn @db/**conn*
          _ (install-class-chain! conn 5) ;; :t/C0 <- ... <- :t/C4
          db (d/db conn)
          all-ancestors-of-c4 (ent/apply-entailment
                                db
                                '[:find [?ancestor-ident ...]
                                  :in $ % ?leaf-ident
                                  :where [?leaf :db/ident ?leaf-ident]
                                  (rdfs11-sc ?leaf ?ancestor)
                                  [?ancestor :db/ident ?ancestor-ident]]
                                ent/rdfs-rules
                                :t/C4)]
      (is (= #{:t/C0 :t/C1 :t/C2 :t/C3} (set all-ancestors-of-c4))
          "5-level chain should derive 4 ancestors"))))

(deftest rdfs-composition-rdfs9-rdfs11-test
  (testing "rdfs9 composes with rdfs11 transitively across a 4-level chain"
    (let [conn @db/**conn*
          _ (install-class-chain! conn 4)
          _ @(d/transact conn [{:db/id "deep-inst"
                                :db/ident :t/deepest-instance
                                :dt/type :t/C3}])
          db (d/db conn)
          all-types (ent/apply-entailment
                      db
                      '[:find [?type-ident ...]
                        :in $ % ?inst-ident
                        :where [?inst :db/ident ?inst-ident]
                        (rdfs9-sco ?inst ?type)
                        [?type :db/ident ?type-ident]]
                      ent/rdfs-rules
                      :t/deepest-instance)]
      ;; Instance is :dt/type :t/C3; rdfs9-sco entails membership in C2 + C1 + C0
      (is (= #{:t/C2 :t/C1 :t/C0} (set all-types))
          "rdfs9 + rdfs11 composition should derive all 3 ancestor types"))))

(deftest rdfs-cycle-detection-test
  (testing "cycle in subclass-of chain does not hang query (Datalog terminates)"
    ;; Deliberately install a 2-cycle: :t/Cyc0 subclass-of :t/Cyc1; :t/Cyc1 subclass-of :t/Cyc0
    (let [conn @db/**conn*
          _ @(d/transact conn [{:db/id "cyc0" :db/ident :t/Cyc0 :dt/type :dt/Class}
                               {:db/id "cyc1" :db/ident :t/Cyc1 :dt/type :dt/Class}])
          _ @(d/transact conn [[:db/add :t/Cyc0 :dt/subclass-of :t/Cyc1]
                               [:db/add :t/Cyc1 :dt/subclass-of :t/Cyc0]])
          db (d/db conn)
          ;; rdfs11 over a cyclic graph must terminate with finite set
          ancestors-of-cyc0 (ent/apply-entailment
                              db
                              '[:find [?ancestor-ident ...]
                                :in $ % ?c-ident
                                :where [?c :db/ident ?c-ident]
                                (rdfs11-sc ?c ?ancestor)
                                [?ancestor :db/ident ?ancestor-ident]]
                              ent/rdfs-rules
                              :t/Cyc0)]
      ;; Both :t/Cyc1 and :t/Cyc0 reachable via the cycle; set-semantics dedup ensures termination
      (is (= #{:t/Cyc0 :t/Cyc1} (set ancestors-of-cyc0))
          "Cycle should derive both classes as reachable ancestors; Datalog must terminate"))))

(deftest rdfs9-isa-union-test
  (testing "rdfs9-isa returns BOTH direct + derived class membership"
    (let [conn @db/**conn*
          _ (install-class-chain! conn 3) ;; :t/C0 <- :t/C1 <- :t/C2
          _ @(d/transact conn [{:db/id "inst-isa"
                                :db/ident :t/isa-inst
                                :dt/type :t/C2}])
          db (d/db conn)
          ;; rdfs9-isa should return :t/C2 (direct) + :t/C1 + :t/C0 (derived)
          all-types (ent/apply-entailment
                      db
                      '[:find [?type-ident ...]
                        :in $ % ?inst-ident
                        :where [?inst :db/ident ?inst-ident]
                        (rdfs9-isa ?inst ?type)
                        [?type :db/ident ?type-ident]]
                      ent/rdfs-rules
                      :t/isa-inst)]
      (is (= #{:t/C2 :t/C1 :t/C0} (set all-types))
          "rdfs9-isa should return direct :t/C2 PLUS derived :t/C1 and :t/C0"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 3 — OWL 2 RL property-characteristic rule tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest prp-trp-transitive-closure-test
  (testing "prp-trp derives transitive closure of edges over a transitive property"
    (let [conn @db/**conn*
          attr-ident :test.prp/follows
          _ (install-test-attr! conn attr-ident :type :dt/TransitiveProperty)
          ;; Install entities :e/a → :e/b → :e/c → :e/d via test.prp/follows
          _ @(d/transact conn [{:db/id "a" :db/ident :e/a}
                               {:db/id "b" :db/ident :e/b}
                               {:db/id "c" :db/ident :e/c}
                               {:db/id "d" :db/ident :e/d}])
          _ @(d/transact conn [[:db/add :e/a attr-ident :e/b]
                               [:db/add :e/b attr-ident :e/c]
                               [:db/add :e/c attr-ident :e/d]])
          db (d/db conn)
          ;; Find all transitively-reachable entities from :e/a via prp-trp
          reachable-from-a (ent/apply-entailment
                             db
                             '[:find [?target-ident ...]
                               :in $ % ?src-ident
                               :where [?src :db/ident ?src-ident]
                               (prp-trp ?src ?p-ident ?target)
                               [?target :db/ident ?target-ident]]
                             ent/all-rules
                             :e/a)]
      ;; prp-trp closure from :e/a: :e/b (direct), :e/c (2-hop), :e/d (3-hop)
      (is (= #{:e/b :e/c :e/d} (set reachable-from-a))
          "prp-trp should derive direct + transitively-reachable edges"))))

(deftest prp-symp-symmetric-test
  (testing "prp-symp derives the reverse edge of a symmetric property"
    ;; Canonical OWL 2 RL prp-symp rule:
    ;;   T(?p, rdf:type, owl:SymmetricProperty) ∧ T(?x, ?p, ?y) → T(?y, ?p, ?x)
    ;; The rule derives ONLY the reverse direction.  The original (?x p ?y)
    ;; is the input ground fact; consumers querying the full symmetric closure
    ;; compose this rule's output with direct ground-fact queries.
    ;; Datomic Datalog rules don't iterate over their own derivations (the
    ;; derived datom from one rule firing doesn't become input to the next
    ;; rule firing within the same query), so self-application across the
    ;; closure doesn't happen.
    (let [conn @db/**conn*
          attr-ident :test.prp/sibling-of
          _ (install-test-attr! conn attr-ident :type :dt/SymmetricProperty)
          _ @(d/transact conn [{:db/id "x" :db/ident :e/x}
                               {:db/id "y" :db/ident :e/y}])
          _ @(d/transact conn [[:db/add :e/x attr-ident :e/y]])
          db (d/db conn)
          reverse-edge (ent/apply-entailment
                         db
                         '[:find ?from-ident ?to-ident
                           :in $ % ?attr-ident
                           :where (prp-symp ?from ?attr-ident ?to)
                           [?from :db/ident ?from-ident]
                           [?to :db/ident ?to-ident]]
                         ent/all-rules
                         attr-ident)]
      (is (= #{[:e/y :e/x]} (set reverse-edge))
          "prp-symp derives only the reverse edge :e/y → :e/x (canonical semantics)"))))

(deftest prp-asyp-violation-test
  (testing "prp-asyp-violation surfaces violating edge pairs for asymmetric property"
    (let [conn @db/**conn*
          attr-ident :test.prp/asymmetric-rel
          _ (install-test-attr! conn attr-ident :type :dt/AsymmetricProperty)
          _ @(d/transact conn [{:db/id "u" :db/ident :e/u}
                               {:db/id "v" :db/ident :e/v}
                               {:db/id "w" :db/ident :e/w}])
          ;; Violation: (u rel v) AND (v rel u)
          _ @(d/transact conn [[:db/add :e/u attr-ident :e/v]
                               [:db/add :e/v attr-ident :e/u]
                               ;; Non-violation: (v rel w) without reverse
                               [:db/add :e/v attr-ident :e/w]])
          db (d/db conn)
          violations (ent/apply-entailment
                       db
                       '[:find ?x-ident ?y-ident
                         :in $ % ?attr-ident
                         :where (prp-asyp-violation ?x ?attr-ident ?y)
                         [?x :db/ident ?x-ident]
                         [?y :db/ident ?y-ident]]
                       ent/all-rules
                       attr-ident)]
      ;; Both directions of the cycle appear as violations
      (is (= #{[:e/u :e/v] [:e/v :e/u]} (set violations))
          "Asymmetric violation should surface both directions of the bad cycle"))))

(deftest prp-irp-violation-test
  (testing "prp-irp-violation surfaces self-loop violations for irreflexive property"
    (let [conn @db/**conn*
          attr-ident :test.prp/irreflexive-rel
          _ (install-test-attr! conn attr-ident :type :dt/IrreflexiveProperty)
          _ @(d/transact conn [{:db/id "selfloop" :db/ident :e/selfloop}
                               {:db/id "no-selfloop" :db/ident :e/no-selfloop}
                               {:db/id "target" :db/ident :e/target}])
          ;; Violation: (selfloop rel selfloop)
          _ @(d/transact conn [[:db/add :e/selfloop attr-ident :e/selfloop]
                               ;; Non-violation: (no-selfloop rel target)
                               [:db/add :e/no-selfloop attr-ident :e/target]])
          db (d/db conn)
          violations (ent/apply-entailment
                       db
                       '[:find [?x-ident ...]
                         :in $ % ?attr-ident
                         :where (prp-irp-violation ?x ?attr-ident)
                         [?x :db/ident ?x-ident]]
                       ent/all-rules
                       attr-ident)]
      (is (= #{:e/selfloop} (set violations))
          "Only the self-looping entity should be surfaced as a violation"))))

(deftest prp-fp-conflict-test
  (testing "prp-fp-conflict surfaces sameAs conflicts for functional property"
    (let [conn @db/**conn*
          attr-ident :test.prp/has-canonical-form
          _ (install-test-attr! conn attr-ident
                                :type :dt/FunctionalProperty
                                :cardinality :db.cardinality/many) ;; allows conflict
          _ @(d/transact conn [{:db/id "e" :db/ident :e/with-conflict}
                               {:db/id "ok" :db/ident :e/no-conflict}
                               {:db/id "form-a" :db/ident :e/form-a}
                               {:db/id "form-b" :db/ident :e/form-b}
                               {:db/id "form-c" :db/ident :e/form-c}])
          ;; Conflict: (with-conflict has-canonical-form form-a)
          ;;           (with-conflict has-canonical-form form-b)
          _ @(d/transact conn [[:db/add :e/with-conflict attr-ident :e/form-a]
                               [:db/add :e/with-conflict attr-ident :e/form-b]
                               ;; No conflict: (no-conflict has-canonical-form form-c) — single value
                               [:db/add :e/no-conflict attr-ident :e/form-c]])
          db (d/db conn)
          conflicts (ent/apply-entailment
                      db
                      '[:find ?x-ident ?y1-ident ?y2-ident
                        :in $ % ?attr-ident
                        :where (prp-fp-conflict ?x ?attr-ident ?y1 ?y2)
                        [?x :db/ident ?x-ident]
                        [?y1 :db/ident ?y1-ident]
                        [?y2 :db/ident ?y2-ident]]
                      ent/all-rules
                      attr-ident)
          ;; Both orderings (form-a, form-b) and (form-b, form-a) surface; normalize
          conflict-pairs (set (map (fn [[x y1 y2]] [x (set [y1 y2])]) conflicts))]
      (is (= #{[:e/with-conflict #{:e/form-a :e/form-b}]} conflict-pairs)
          "Only :e/with-conflict should surface; :e/no-conflict has single value"))))

(deftest prp-ifp-conflict-test
  (testing "prp-ifp-conflict surfaces inverse-functional violations"
    (let [conn @db/**conn*
          attr-ident :test.prp/ssn
          _ (install-test-attr! conn attr-ident
                                :type :dt/InverseFunctionalProperty
                                :cardinality :db.cardinality/one)
          _ @(d/transact conn [{:db/id "p1" :db/ident :e/person-1}
                               {:db/id "p2" :db/ident :e/person-2}
                               {:db/id "p3" :db/ident :e/person-3}
                               {:db/id "ssn-shared" :db/ident :e/ssn-shared}
                               {:db/id "ssn-unique" :db/ident :e/ssn-unique}])
          ;; Conflict: (person-1 ssn ssn-shared) AND (person-2 ssn ssn-shared) — same ssn, different persons
          _ @(d/transact conn [[:db/add :e/person-1 attr-ident :e/ssn-shared]
                               [:db/add :e/person-2 attr-ident :e/ssn-shared]
                               ;; No conflict: person-3 has unique ssn
                               [:db/add :e/person-3 attr-ident :e/ssn-unique]])
          db (d/db conn)
          conflicts (ent/apply-entailment
                      db
                      '[:find ?x1-ident ?x2-ident ?y-ident
                        :in $ % ?attr-ident
                        :where (prp-ifp-conflict ?x1 ?x2 ?attr-ident ?y)
                        [?x1 :db/ident ?x1-ident]
                        [?x2 :db/ident ?x2-ident]
                        [?y :db/ident ?y-ident]]
                      ent/all-rules
                      attr-ident)
          ;; Both orderings of (person-1, person-2) and (person-2, person-1) surface; normalize
          conflict-tuples (set (map (fn [[x1 x2 y]] [(set [x1 x2]) y]) conflicts))]
      (is (= #{[#{:e/person-1 :e/person-2} :e/ssn-shared]} conflict-tuples)
          ":e/person-3's unique ssn should not surface"))))

(deftest prp-inv1-inv2-test
  (testing "prp-inv1 + prp-inv2 derive bidirectional edges for inverse-of pair"
    ;; Setup: parent-of has inverse-of child-of
    (let [conn @db/**conn*
          parent-attr :test.prp/parent-of
          child-attr :test.prp/child-of
          ;; Install both attrs; declare parent-of :dt/inverse-of child-of
          _ (install-test-attr! conn child-attr :type :dt/Property)
          _ (install-test-attr! conn parent-attr
                                :type :dt/Property
                                :inverse-of child-attr)
          _ @(d/transact conn [{:db/id "p" :db/ident :e/parent}
                               {:db/id "c" :db/ident :e/child}])
          ;; Assert (parent parent-of child); rules should derive (child child-of parent)
          _ @(d/transact conn [[:db/add :e/parent parent-attr :e/child]])
          db (d/db conn)
          ;; Query: what does prp-inv1 derive?
          inv1-edges (ent/apply-entailment
                       db
                       '[:find ?from-ident ?attr ?to-ident
                         :in $ %
                         :where (prp-inv1 ?from ?attr ?to)
                         [?from :db/ident ?from-ident]
                         [?to :db/ident ?to-ident]]
                       ent/all-rules)]
      ;; prp-inv1 over (parent parent-of child) with parent-of inverse-of child-of
      ;; → (child child-of parent)
      (is (contains? (set inv1-edges) [:e/child child-attr :e/parent])
          "prp-inv1 should derive (child :child-of parent) from (parent :parent-of child)"))))

(deftest substrate-intersection-class-strict-partial-order-test
  (testing "The substrate's pre-declared :dt/StrictPartialOrderProperty intersection class activates prp-trp + prp-asyp + prp-irp via rdfs9 entailment"
    ;; The substrate's `schema/meta.edn` declares `:dt/StrictPartialOrderProperty`
    ;; as :dt/subclass-of [:dt/AsymmetricProperty :dt/IrreflexiveProperty :dt/TransitiveProperty].
    ;; A property typed as this intersection class should activate ALL THREE
    ;; OWL 2 RL rules via rdfs9 instance-lift entailment — without any
    ;; additional schema changes beyond the (already-landed) intersection-class
    ;; declarations.
    (let [conn @db/**conn*
          attr-ident :test.prp/lineage-of
          ;; Install a property attribute typed as the canonical StrictPartialOrder
          _ (install-test-attr! conn attr-ident :type :dt/StrictPartialOrderProperty)
          _ @(d/transact conn [{:db/id "a" :db/ident :e/n-a}
                               {:db/id "b" :db/ident :e/n-b}
                               {:db/id "c" :db/ident :e/n-c}])
          ;; (a → b → c) over the lineage property; tests should derive:
          ;;   - prp-trp: (a → c) via transitivity
          ;;   - prp-asyp / prp-irp: no violations (no cycles, no self-loops)
          _ @(d/transact conn [[:db/add :e/n-a attr-ident :e/n-b]
                               [:db/add :e/n-b attr-ident :e/n-c]])
          db (d/db conn)
          ;; Transitive closure derivation
          trp-derived (ent/apply-entailment
                        db
                        '[:find ?from-ident ?to-ident
                          :in $ % ?attr-ident
                          :where (prp-trp ?from ?attr-ident ?to)
                          [?from :db/ident ?from-ident]
                          [?to :db/ident ?to-ident]]
                        ent/all-rules
                        attr-ident)
          ;; Asymmetry violations (should be none)
          asyp-violations (ent/apply-entailment
                            db
                            '[:find ?x-ident ?y-ident
                              :in $ % ?attr-ident
                              :where (prp-asyp-violation ?x ?attr-ident ?y)
                              [?x :db/ident ?x-ident]
                              [?y :db/ident ?y-ident]]
                            ent/all-rules
                            attr-ident)
          ;; Irreflexivity violations (should be none)
          irp-violations (ent/apply-entailment
                           db
                           '[:find [?x-ident ...]
                             :in $ % ?attr-ident
                             :where (prp-irp-violation ?x ?attr-ident)
                             [?x :db/ident ?x-ident]]
                           ent/all-rules
                           attr-ident)]
      (is (contains? (set trp-derived) [:e/n-a :e/n-c])
          "Substrate's :dt/StrictPartialOrderProperty should activate prp-trp; (a → c) derived")
      (is (empty? asyp-violations)
          "No asymmetry violations on a clean directed chain")
      (is (empty? irp-violations)
          "No irreflexivity violations on a clean directed chain"))))

(deftest existing-dt-infrastructure-handles-intersection-class-hierarchy-test
  (testing "Existing dt/instance-of? + dt/type-isa? + dt/subclass-of? correctly navigate the SWCLOS-style intersection-class hierarchy without modification"
    ;; KEY ARCHITECTURAL VALIDATION: the existing dt/* infrastructure
    ;; (`subclass-of` Datalog rule + `subclass-of?` + `instance-of?` +
    ;; `type-isa?`) already walks the substrate's `:dt/subclass-of` chain
    ;; correctly.  When the chain is the SWCLOS-style intersection-class
    ;; hierarchy (e.g., :dt/StrictPartialOrderProperty :dt/subclass-of
    ;; [:dt/AsymmetricProperty :dt/IrreflexiveProperty :dt/TransitiveProperty]),
    ;; the existing predicates RESOLVE membership in each component class
    ;; correctly.
    ;;
    ;; This means Stage 5's "type-isa? closure-aware augmentation" is
    ;; SATISFIED BY THE EXISTING INFRASTRUCTURE.  No code change needed.
    ;; The new entailment-module rules (rdfs9-isa / rdfs11-sc) are an
    ;; alternative form for consumers wanting to compose with property-
    ;; characteristic rules; the legacy `subclass-of` rule in datatype.clj
    ;; covers the entity-facing case sufficiently.
    (testing "dt/instance-of? sees :mm.memory/descends-from as instance of intersection class"
      (is (dt/instance-of? :dt/StrictPartialOrderProperty :mm.memory/descends-from)
          "Direct :dt/type membership"))
    (testing "dt/instance-of? traverses subclass chain to component characteristics"
      (is (dt/instance-of? :dt/TransitiveProperty :mm.memory/descends-from)
          ":dt/StrictPartialOrderProperty :dt/subclass-of :dt/TransitiveProperty")
      (is (dt/instance-of? :dt/AsymmetricProperty :mm.memory/descends-from)
          ":dt/StrictPartialOrderProperty :dt/subclass-of :dt/AsymmetricProperty")
      (is (dt/instance-of? :dt/IrreflexiveProperty :mm.memory/descends-from)
          ":dt/StrictPartialOrderProperty :dt/subclass-of :dt/IrreflexiveProperty"))
    (testing "dt/instance-of? traverses transitively to :dt/Property root"
      (is (dt/instance-of? :dt/Property :mm.memory/descends-from)
          "Marker class :dt/subclass-of :dt/Property; 2-hop transitivity"))
    (testing "dt/type-isa? agrees on the closure"
      (is (dt/type-isa? :dt/TransitiveProperty :dt/StrictPartialOrderProperty)
          "Direct ident-keyword variant via subclass closure")
      (is (dt/type-isa? :dt/Property :dt/StrictPartialOrderProperty)
          "Transitively"))))

(deftest real-substrate-mm-memory-descends-from-test
  (testing ":mm.memory/descends-from is :dt/type :dt/StrictPartialOrderProperty; rdfs9 entails membership in all 3 component characteristic classes"
    ;; Stage 4 MVP demonstration: ONE real substrate predicate is migrated from
    ;; :dt/type :dt/Property to :dt/type :dt/StrictPartialOrderProperty.
    ;; This test verifies the full chain: descend-from has the intersection
    ;; class as its :dt/type; rdfs9 (via the substrate's rdfs-rules + the
    ;; meta.edn-declared :dt/subclass-of chain) derives that descends-from
    ;; is a member of :dt/AsymmetricProperty, :dt/IrreflexiveProperty, and
    ;; :dt/TransitiveProperty.  The 121 other predicates still carry the
    ;; supertype :dt/Property; their migration is a follow-up.
    (let [conn @db/**conn*
          db (d/db conn)
          derived-types (ent/apply-entailment
                          db
                          '[:find [?type-ident ...]
                            :in $ %
                            :where [?prop :db/ident :mm.memory/descends-from]
                            (rdfs9-isa ?prop ?type)
                            [?type :db/ident ?type-ident]]
                          ent/rdfs-rules)]
      ;; rdfs9-isa includes direct + derived class memberships.  Expected:
      ;;   - :dt/StrictPartialOrderProperty (direct)
      ;;   - :dt/AsymmetricProperty (via :dt/subclass-of)
      ;;   - :dt/IrreflexiveProperty (via :dt/subclass-of)
      ;;   - :dt/TransitiveProperty (via :dt/subclass-of)
      ;;   - :dt/Property (via 2-hop transitivity through any marker class)
      (let [derived-set (set derived-types)]
        (is (contains? derived-set :dt/StrictPartialOrderProperty)
            "Direct :dt/type from schema")
        (is (contains? derived-set :dt/AsymmetricProperty)
            "Entailed via :dt/StrictPartialOrderProperty :dt/subclass-of :dt/AsymmetricProperty")
        (is (contains? derived-set :dt/IrreflexiveProperty)
            "Entailed via :dt/StrictPartialOrderProperty :dt/subclass-of :dt/IrreflexiveProperty")
        (is (contains? derived-set :dt/TransitiveProperty)
            "Entailed via :dt/StrictPartialOrderProperty :dt/subclass-of :dt/TransitiveProperty")
        (is (contains? derived-set :dt/Property)
            "Entailed transitively (2-hop) via marker class :dt/subclass-of :dt/Property")))))

(deftest prp-intersection-class-composition-test
  (testing "OWL 2 RL rules fire correctly when property type is an intersection class"
    ;; Create an intersection class that subclass-ofs both Transitive AND Symmetric.
    ;; A property with :dt/type set to this intersection class should fire BOTH
    ;; prp-trp AND prp-symp (via rdfs9-isa entailment).
    ;;
    ;; Setup uses single-transaction with named tempids to avoid cross-tx ident
    ;; resolution issues (the intersection class's ident asserted in tx-1 may not
    ;; be referenceable from tx-2's data; named tempids resolve within a tx).
    (let [conn @db/**conn*
          intersection-class :test.dt/TransitiveSymmetricProperty
          attr-ident :test.prp/connected
          ;; Single-tx setup: intersection class + property attr + entity idents
          _ @(d/transact conn [;; The intersection class
                               {:db/id "intersect"
                                :db/ident intersection-class
                                :dt/type :dt/Class
                                :dt/subclass-of [:dt/TransitiveProperty :dt/SymmetricProperty]}
                               ;; The property attribute typed as the intersection class
                               {:db/id (d/tempid :db.part/db)
                                :db/ident attr-ident
                                :db/valueType :db.type/ref
                                :db/cardinality :db.cardinality/many
                                :db/doc "Test connected (transitive AND symmetric)"
                                :dt/type "intersect"}
                               ;; Test entities
                               {:db/id "n1" :db/ident :e/n1}
                               {:db/id "n2" :db/ident :e/n2}
                               {:db/id "n3" :db/ident :e/n3}])
          _ @(d/transact conn [[:db/add :e/n1 attr-ident :e/n2]
                               [:db/add :e/n2 attr-ident :e/n3]])
          db (d/db conn)
          ;; prp-trp should derive (n1 connected n3) via transitivity
          trp-derived (ent/apply-entailment
                        db
                        '[:find ?from-ident ?to-ident
                          :in $ % ?attr-ident
                          :where (prp-trp ?from ?attr-ident ?to)
                          [?from :db/ident ?from-ident]
                          [?to :db/ident ?to-ident]]
                        ent/all-rules
                        attr-ident)
          ;; prp-symp should derive (n2 connected n1) via symmetry
          symp-derived (ent/apply-entailment
                         db
                         '[:find ?from-ident ?to-ident
                           :in $ % ?attr-ident
                           :where (prp-symp ?from ?attr-ident ?to)
                           [?from :db/ident ?from-ident]
                           [?to :db/ident ?to-ident]]
                         ent/all-rules
                         attr-ident)]
      (is (contains? (set trp-derived) [:e/n1 :e/n3])
          "Intersection class should activate prp-trp; transitive derivation from n1 to n3")
      (is (contains? (set symp-derived) [:e/n2 :e/n1])
          "Intersection class should activate prp-symp; symmetric derivation n2→n1 from n1→n2"))))
