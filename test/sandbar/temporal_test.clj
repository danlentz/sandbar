(ns sandbar.temporal-test
  "Tests for sandbar.temporal — the Phase D Temporal Tier-2 substrate
   consumer-helpers + schema-load smoke + XOR Shape enforcement + Allen
   typed-edge slot characteristic-typing.

   Per the verification-is-tests-memorialized discipline — verification is
   tests + memorialization, not REPL verification.

   Covers pre-0.2.0 release arc β.1.B (per the β.1.B plan ratified via
   ExitPlanMode 2026-05-25)."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.shape :as shape]
            [sandbar.temporal :as temporal]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "temporal-test"}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-instant!
  "Create a :mm/Instant entity with :mm.instant/at-time; return its eid."
  [at-time]
  (:db/id (dt/make :mm/Instant {:mm.instant/at-time at-time})))

(defn- create-proper-interval!
  "Create a :mm/ProperInterval entity with the given slots; return its eid."
  [slots]
  (:db/id (dt/make :mm/ProperInterval slots)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §1 Schema-load smoke — 4 new Phase D classes declared correctly
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest phase-d-classes-loaded
  (testing "All 4 Phase D classes are present in the substrate with correct hierarchy"
    (let [db (d/db @db/**conn*)]
      (testing ":mm/TemporalEntity abstract; subclass-of :mm/Spec"
        (let [e (d/entity db :mm/TemporalEntity)]
          (is (some? e) ":mm/TemporalEntity is declared")
          (is (true? (:dt/abstract? e)) ":mm/TemporalEntity is abstract")
          (is (contains? (set (:dt/subclass-of e)) :mm/Spec)
              ":mm/TemporalEntity parented under :mm/Spec per Q.D.3")))
      (testing ":mm/Instant concrete; subclass-of :mm/TemporalEntity; first-class memorial-policy"
        (let [e (d/entity db :mm/Instant)]
          (is (some? e) ":mm/Instant is declared")
          (is (false? (:dt/abstract? e)) ":mm/Instant is concrete")
          (is (= :first-class (:dt/memorial-policy e))
              ":mm/Instant carries first-class memorial-policy")
          (is (contains? (set (:dt/subclass-of e)) :mm/TemporalEntity))))
      (testing ":mm/Interval abstract; subclass-of :mm/TemporalEntity"
        (let [e (d/entity db :mm/Interval)]
          (is (some? e) ":mm/Interval is declared")
          (is (true? (:dt/abstract? e)) ":mm/Interval is abstract")
          (is (contains? (set (:dt/subclass-of e)) :mm/TemporalEntity))))
      (testing ":mm/ProperInterval concrete; subclass-of :mm/Interval; first-class memorial-policy"
        (let [e (d/entity db :mm/ProperInterval)]
          (is (some? e) ":mm/ProperInterval is declared")
          (is (false? (:dt/abstract? e)) ":mm/ProperInterval is concrete")
          (is (= :first-class (:dt/memorial-policy e)))
          (is (contains? (set (:dt/subclass-of e)) :mm/Interval)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §2 :dt/EquivalenceRelationProperty intersection class structural check
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dt-equivalence-relation-property-declared
  (testing ":dt/EquivalenceRelationProperty intersection class added to meta.edn per Q.D.9"
    (let [db (d/db @db/**conn*)
          e  (d/entity db :dt/EquivalenceRelationProperty)
          parents (set (:dt/subclass-of e))]
      (is (some? e) ":dt/EquivalenceRelationProperty is declared")
      (is (contains? parents :dt/TransitiveProperty)
          "subclass-of :dt/TransitiveProperty (transitive characteristic)")
      (is (contains? parents :dt/SymmetricProperty)
          "subclass-of :dt/SymmetricProperty (symmetric characteristic)")
      (is (contains? parents :dt/ReflexiveProperty)
          "subclass-of :dt/ReflexiveProperty (reflexive characteristic)"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §3 Allen-relation slot characteristic-typing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The 12 directional Allen relations carry :dt/type :dt/StrictPartialOrderProperty;
;; :mm.interval/equals carries :dt/type :dt/EquivalenceRelationProperty.
;; Verifies the slot declarations have the right characteristic-class for the
;; entailment rule-runner to consume.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest allen-directional-relations-strict-partial-order
  (testing "All 12 directional Allen relations carry :dt/StrictPartialOrderProperty"
    (let [db (d/db @db/**conn*)
          directional-relations [:mm.interval/before :mm.interval/after
                                 :mm.interval/meets :mm.interval/met-by
                                 :mm.interval/overlaps :mm.interval/overlapped-by
                                 :mm.interval/during :mm.interval/contains
                                 :mm.interval/starts :mm.interval/started-by
                                 :mm.interval/finishes :mm.interval/finished-by]]
      (doseq [rel directional-relations]
        (let [e (d/entity db rel)]
          (is (some? e) (str rel " is declared"))
          (is (= :dt/StrictPartialOrderProperty (:dt/type e))
              (str rel " carries :dt/type :dt/StrictPartialOrderProperty")))))))

(deftest allen-equals-relation-equivalence
  (testing ":mm.interval/equals carries :dt/EquivalenceRelationProperty (symmetric / transitive / reflexive)"
    (let [db (d/db @db/**conn*)
          e  (d/entity db :mm.interval/equals)]
      (is (some? e) ":mm.interval/equals is declared")
      (is (= :dt/EquivalenceRelationProperty (:dt/type e)))
      (is (= :db.cardinality/many (:db/cardinality e)) "cardinality-many per Q.D.5"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §4 :dt/inverse-of declarations for the 6 Allen directional pairs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest allen-inverse-pairs-declared
  (testing "Each of the 6 Allen directional inverse-pairs has explicit :dt/inverse-of"
    (let [db (d/db @db/**conn*)
          inverse-pairs [[:mm.interval/before :mm.interval/after]
                         [:mm.interval/meets :mm.interval/met-by]
                         [:mm.interval/overlaps :mm.interval/overlapped-by]
                         [:mm.interval/during :mm.interval/contains]
                         [:mm.interval/starts :mm.interval/started-by]
                         [:mm.interval/finishes :mm.interval/finished-by]]]
      (doseq [[a b] inverse-pairs]
        (let [e (d/entity db a)]
          (is (= b (:dt/inverse-of e))
              (str a " :dt/inverse-of " b)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §5 XOR Shape enforcement (Option ε paired-property pattern)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Uses the 2 pre-declared :memory.shapes/interval-begins-at-xor +
;; :memory.shapes/interval-ends-at-xor entities (declared in mm-temporal.edn).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest xor-shapes-declared
  (testing "Both pre-declared XOR Shape entities are present + carry :violation severity"
    (let [db (d/db @db/**conn*)
          begin-shape (d/entity db :memory.shapes/interval-begins-at-xor)
          end-shape   (d/entity db :memory.shapes/interval-ends-at-xor)]
      (is (some? begin-shape) ":memory.shapes/interval-begins-at-xor declared")
      (is (some? end-shape)   ":memory.shapes/interval-ends-at-xor declared")
      (is (= :mm/Interval (:mm.shape/applies-to begin-shape)))
      (is (= :violation (:mm.shape/severity begin-shape))
          "Severity is :violation per Q.D.6"))))

(deftest xor-begins-at-ref-form-only-passes
  (testing "Interval with begins-at-instant (ref form) only — passes XOR"
    (let [instant-eid (create-instant! #inst "2026-01-01T00:00:00Z")
          interval-eid (create-proper-interval!
                        {:mm.interval/begins-at-instant instant-eid
                         :mm.interval/ends-at-time      #inst "2026-01-02T00:00:00Z"})
          db' (d/db @db/**conn*)
          result (shape/check-xor-constraints db' interval-eid
                                              (:db/id (d/entity db' :memory.shapes/interval-begins-at-xor)))]
      (is (= :pass (:status result))
          "Exactly begins-at-instant populated should pass XOR"))))

(deftest xor-begins-at-literal-form-only-passes
  (testing "Interval with begins-at-time (literal form) only — passes XOR"
    (let [interval-eid (create-proper-interval!
                        {:mm.interval/begins-at-time #inst "2026-01-01T00:00:00Z"
                         :mm.interval/ends-at-time   #inst "2026-01-02T00:00:00Z"})
          db' (d/db @db/**conn*)
          result (shape/check-xor-constraints db' interval-eid
                                              (:db/id (d/entity db' :memory.shapes/interval-begins-at-xor)))]
      (is (= :pass (:status result))
          "Exactly begins-at-time populated should pass XOR"))))

(deftest xor-begins-at-both-populated-fails
  (testing "Interval with BOTH begins-at-instant AND begins-at-time — fails XOR :both-populated"
    (let [instant-eid (create-instant! #inst "2026-01-01T00:00:00Z")
          interval-eid (create-proper-interval!
                        {:mm.interval/begins-at-instant instant-eid
                         :mm.interval/begins-at-time    #inst "2026-01-01T00:00:00Z"
                         :mm.interval/ends-at-time      #inst "2026-01-02T00:00:00Z"})
          db' (d/db @db/**conn*)
          result (shape/check-xor-constraints db' interval-eid
                                              (:db/id (d/entity db' :memory.shapes/interval-begins-at-xor)))]
      (is (= :fail (:status result))
          "Both begins-at-instant AND begins-at-time populated should fail XOR")
      (is (= :both-populated (:reason (first (:violations result))))))))

(deftest xor-begins-at-both-absent-fails
  (testing "Interval with NEITHER begins-at-instant NOR begins-at-time — fails XOR :both-absent"
    (let [interval-eid (create-proper-interval!
                        {:mm.interval/ends-at-time #inst "2026-01-02T00:00:00Z"})
          db' (d/db @db/**conn*)
          result (shape/check-xor-constraints db' interval-eid
                                              (:db/id (d/entity db' :memory.shapes/interval-begins-at-xor)))]
      (is (= :fail (:status result))
          "Neither begins-at form populated should fail XOR")
      (is (= :both-absent (:reason (first (:violations result))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §6 Consumer-helper polymorphic-read tests (sandbar.temporal)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Verifies the interval-beginning / interval-end / interval-duration-ms
;; helpers resolve transparently across the Option ε ref-form vs literal-form.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest interval-beginning-resolves-ref-form
  (testing "interval-beginning resolves from :mm.interval/begins-at-instant ref"
    (let [at #inst "2026-03-15T10:30:00Z"
          instant-eid (create-instant! at)
          interval-eid (create-proper-interval!
                        {:mm.interval/begins-at-instant instant-eid
                         :mm.interval/ends-at-time      #inst "2026-03-15T12:00:00Z"})
          db' (d/db @db/**conn*)
          interval (d/entity db' interval-eid)]
      (is (= at (temporal/interval-beginning interval))
          "interval-beginning resolves through :mm.interval/begins-at-instant → :mm.instant/at-time"))))

(deftest interval-beginning-resolves-literal-form
  (testing "interval-beginning resolves from :mm.interval/begins-at-time literal"
    (let [at #inst "2026-04-20T14:00:00Z"
          interval-eid (create-proper-interval!
                        {:mm.interval/begins-at-time at
                         :mm.interval/ends-at-time   #inst "2026-04-20T16:00:00Z"})
          db' (d/db @db/**conn*)
          interval (d/entity db' interval-eid)]
      (is (= at (temporal/interval-beginning interval))
          "interval-beginning resolves from primitive :mm.interval/begins-at-time"))))

(deftest interval-duration-ms-computes
  (testing "interval-duration-ms computes ms-difference across mixed Option ε forms"
    (let [start-instant (create-instant! #inst "2026-05-01T09:00:00Z")
          end-time      #inst "2026-05-01T09:30:00Z"
          interval-eid  (create-proper-interval!
                         {:mm.interval/begins-at-instant start-instant
                          :mm.interval/ends-at-time      end-time})
          db'           (d/db @db/**conn*)
          interval      (d/entity db' interval-eid)]
      (is (= (* 30 60 1000) (temporal/interval-duration-ms interval))
          "30-minute interval should yield 1800000 ms"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; §7 Allen-relation typed-edge basic traversal + cross-axis composition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest allen-before-direct-edge
  (testing "Direct :mm.interval/before edge is queryable via sandbar.temporal/allen-before?"
    (let [a-eid (create-proper-interval!
                  {:mm.interval/begins-at-time #inst "2026-01-01T00:00:00Z"
                   :mm.interval/ends-at-time   #inst "2026-01-02T00:00:00Z"})
          b-eid (create-proper-interval!
                  {:mm.interval/begins-at-time #inst "2026-01-03T00:00:00Z"
                   :mm.interval/ends-at-time   #inst "2026-01-04T00:00:00Z"})
          _ (d/transact @db/**conn* [{:db/id a-eid :mm.interval/before [b-eid]}])
          db' (d/db @db/**conn*)
          a (d/entity db' a-eid)
          b (d/entity db' b-eid)]
      (is (true? (temporal/allen-before? a b))
          "a :mm.interval/before b → allen-before? returns true")
      (is (false? (temporal/allen-before? b a))
          "reverse direction is not asserted → allen-before? returns false"))))

(deftest cross-axis-activity-spans-interval-slot-exists
  (testing ":mm.activity/spans-interval slot is declared on :mm/Activity with :mm/Interval range"
    (let [db (d/db @db/**conn*)
          e  (d/entity db :mm.activity/spans-interval)]
      (is (some? e) ":mm.activity/spans-interval is declared")
      (is (= :mm/Activity (:dt/domain e)))
      (is (= :mm/Interval (:dt/range e)))
      (is (= :db.cardinality/one (:db/cardinality e)) "cardinality-one per Q.D.7"))))

(deftest cross-axis-event-at-interval-slot-exists
  (testing ":mm.event/at-interval slot is declared on :mm/Event with :mm/Interval range"
    (let [db (d/db @db/**conn*)
          e  (d/entity db :mm.event/at-interval)]
      (is (some? e) ":mm.event/at-interval is declared")
      (is (= :mm/Event (:dt/domain e)))
      (is (= :mm/Interval (:dt/range e)))
      (is (= :db.cardinality/one (:db/cardinality e)) "cardinality-one per Q.D.7"))))
