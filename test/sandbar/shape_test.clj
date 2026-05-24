(ns sandbar.shape-test
  "Tests for sandbar.shape — the SHACL-style abstract-interpreter walker.

   Per interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md
   — verification is tests + memorialization, not REPL verification.

   Covers SHACL arc Stage C of plans/shacl_deeply_incorporated_capstone_activation_arc_2026_05_23.md.

   Tests are arranged per-check-fn (each constraint kind has pass + fail
   scenarios), then `walk-entity` composition, then the `validate` public
   entry with `:strict` / `:audit` / `:disabled` modes."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.shape :as shape]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "shape-test"}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — author test shapes + target entities via dt/make
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-shape!
  "Create a :mm/Shape entity with the given slot map; return its eid."
  [slots]
  (:db/id (dt/make :mm/Shape (merge {:mm.memory/scope :project
                                     :mm.memory/memory-type :shape}
                                    slots))))

(defn- create-test-memory!
  "Create a generic :mm/Memory test entity with the given slots."
  [slots]
  (:db/id (dt/make :mm/Memory (merge {:mm.memory/scope :project
                                      :mm.memory/memory-type :memory}
                                     slots))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; check-required-property
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check-required-property-passes-when-present
  (testing "Entity carries all required properties — pass"
    (let [db        (d/db @db/**conn*)
          shape-eid (create-shape!
                      {:mm.shape/shape-id     "test-required-pass"
                       :mm.shape/applies-to   :mm/Memory
                       :mm.shape/description  "test"
                       :mm.shape/required-property [:mm.memory/name
                                                    :mm.memory/description]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path     "test/required-pass.md"
                        :mm.memory/name         "Required Pass"
                        :mm.memory/description  "All required props present"})
          db'       (d/db @db/**conn*)
          result    (shape/check-required-property db' entity-eid shape-eid)]
      (is (= :pass (:status result))))))

(deftest check-required-property-fails-when-missing
  (testing "Entity missing a required property — fail with severity"
    (let [_         (d/db @db/**conn*)
          shape-eid (create-shape!
                      {:mm.shape/shape-id     "test-required-fail"
                       :mm.shape/applies-to   :mm/Memory
                       :mm.shape/description  "test"
                       :mm.shape/required-property [:mm.memory/name
                                                    :mm.memory/description]
                       :mm.shape/severity     :violation})
          ;; Note: omitting :mm.memory/description
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/required-fail.md"
                        :mm.memory/name     "Missing Description"})
          db'       (d/db @db/**conn*)
          result    (shape/check-required-property db' entity-eid shape-eid)]
      (is (= :fail (:status result)))
      (is (contains? (set (:missing-properties result)) :mm.memory/description))
      (is (= :violation (:severity result))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; check-cardinality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check-cardinality-min-bound-passes
  (testing "Entity carries >= min values — pass"
    (let [card-eid (:db/id
                     (dt/make :mm.shape/CardinalityConstraint
                       {:mm.shape.cardinality/property :mm.memory/name
                        :mm.shape.cardinality/min      1
                        :mm.shape.cardinality/max      -1}))
          shape-eid (create-shape!
                      {:mm.shape/shape-id              "test-card-min-pass"
                       :mm.shape/applies-to            :mm/Memory
                       :mm.shape/description           "test"
                       :mm.shape/cardinality-constraints [card-eid]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/card-min-pass.md"
                        :mm.memory/name     "Name Present"})
          db'       (d/db @db/**conn*)
          result    (shape/check-cardinality db' entity-eid shape-eid)]
      (is (= :pass (:status result))))))

(deftest check-cardinality-min-bound-fails
  (testing "Entity carries < min values — fail"
    (let [card-eid (:db/id
                     (dt/make :mm.shape/CardinalityConstraint
                       {:mm.shape.cardinality/property :mm.memory/name
                        :mm.shape.cardinality/min      1
                        :mm.shape.cardinality/max      -1}))
          shape-eid (create-shape!
                      {:mm.shape/shape-id              "test-card-min-fail"
                       :mm.shape/applies-to            :mm/Memory
                       :mm.shape/description           "test"
                       :mm.shape/cardinality-constraints [card-eid]})
          ;; Omit :mm.memory/name → count = 0 < min 1
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/card-min-fail.md"})
          db'       (d/db @db/**conn*)
          result    (shape/check-cardinality db' entity-eid shape-eid)]
      (is (= :fail (:status result)))
      (let [violation (first (:cardinality-violations result))]
        (is (= :mm.memory/name (:property violation)))
        (is (= 0 (:count violation)))
        (is (= 1 (:min violation)))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; check-pattern (regex)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check-pattern-passes-when-matching
  (testing "String value matches regex — pass"
    (let [patt-eid (:db/id
                     (dt/make :mm.shape/PatternConstraint
                       {:mm.shape.pattern/property :mm.memory/rel-path
                        :mm.shape.pattern/regex    "^[a-z]+/[a-z-]+\\.md$"}))
          shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-pattern-pass"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/pattern-constraints [patt-eid]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "decisions/foo-bar.md"
                        :mm.memory/name     "Pattern Match"})
          db'       (d/db @db/**conn*)
          result    (shape/check-pattern db' entity-eid shape-eid)]
      (is (= :pass (:status result))))))

(deftest check-pattern-fails-when-not-matching
  (testing "String value violates regex — fail"
    (let [patt-eid (:db/id
                     (dt/make :mm.shape/PatternConstraint
                       {:mm.shape.pattern/property :mm.memory/rel-path
                        :mm.shape.pattern/regex    "^decisions/.*$"}))
          shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-pattern-fail"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/pattern-constraints [patt-eid]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "WRONG_PATH.md"
                        :mm.memory/name     "Pattern Miss"})
          db'       (d/db @db/**conn*)
          result    (shape/check-pattern db' entity-eid shape-eid)]
      (is (= :fail (:status result)))
      (is (seq (:pattern-violations result))))))

(deftest check-pattern-case-insensitive-flag
  (testing "Pattern with 'i' flag matches case-insensitively"
    (let [patt-eid (:db/id
                     (dt/make :mm.shape/PatternConstraint
                       {:mm.shape.pattern/property :mm.memory/name
                        :mm.shape.pattern/regex    "^HELLO"
                        :mm.shape.pattern/flags    "i"}))
          shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-pattern-i"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/pattern-constraints [patt-eid]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/pattern-i.md"
                        :mm.memory/name     "hello world"})
          db'       (d/db @db/**conn*)
          result    (shape/check-pattern db' entity-eid shape-eid)]
      (is (= :pass (:status result))
          "Lowercase 'hello' matches '^HELLO' under 'i' flag"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; check-closed
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest check-closed-pass-when-not-closed
  (testing "Shape with :closed? not true — pass regardless of extra properties"
    (let [shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-closed-off"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path    "test/closed-off.md"
                        :mm.memory/name        "Open Shape"
                        :mm.memory/description "extra unconstrained slot is fine"})
          db'       (d/db @db/**conn*)
          result    (shape/check-closed db' entity-eid shape-eid)]
      (is (= :pass (:status result))))))

(deftest check-closed-fails-when-closed-and-extra-properties
  (testing "Shape with :closed? true — fail when entity carries extra slots"
    (let [shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-closed-fail"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "closed shape"
                       :mm.shape/closed?           true
                       :mm.shape/required-property [:mm.memory/name]})
          ;; rel-path + description are NOT declared by the shape
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path    "test/closed-fail.md"
                        :mm.memory/name        "Has Extras"
                        :mm.memory/description "extra"})
          db'       (d/db @db/**conn*)
          result    (shape/check-closed db' entity-eid shape-eid)]
      (is (= :fail (:status result)))
      (is (contains? (set (:extra-properties result)) :mm.memory/description)
          "Extra :mm.memory/description should be reported"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; walk-entity composition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest walk-entity-aggregates-pass-when-all-checks-pass
  (testing "All checks pass — walk-entity returns :pass with checks-passed count"
    (let [shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-walk-pass"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/walk-pass.md"
                        :mm.memory/name     "Walk Pass"})
          db'       (d/db @db/**conn*)
          result    (shape/walk-entity db' entity-eid shape-eid)]
      (is (= :pass (:status result)))
      (is (= entity-eid (:entity result)))
      (is (= shape-eid (:shape result)))
      (is (pos? (:checks-passed result))))))

(deftest walk-entity-aggregates-fail-with-per-check-details
  (testing "Required-property fails — walk-entity returns :fail with per-check details"
    (let [shape-eid (create-shape!
                      {:mm.shape/shape-id          "test-walk-fail"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name
                                                    :mm.memory/description]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/walk-fail.md"
                        ;; Missing both required properties
                        })
          db'       (d/db @db/**conn*)
          result    (shape/walk-entity db' entity-eid shape-eid)]
      (is (= :fail (:status result)))
      (is (seq (:failures result)))
      (is (some #(= :required-property (:check %)) (:failures result))
          "Required-property check failure should appear in :failures"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; validate public entry — :strict / :audit / :disabled modes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-strict-throws-on-violation
  (testing ":strict mode throws ex-info when violation-severity check fails"
    (let [_         (create-shape!
                      {:mm.shape/shape-id          "test-strict-throw"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name]
                       :mm.shape/severity          :violation})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/strict-throw.md"})
          db'       (d/db @db/**conn*)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (shape/validate db' entity-eid :strict))))))

(deftest validate-audit-returns-violations
  (testing ":audit mode returns results without throwing"
    (let [_         (create-shape!
                      {:mm.shape/shape-id          "test-audit-return"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name]
                       :mm.shape/severity          :violation})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/audit-return.md"})
          db'       (d/db @db/**conn*)
          results   (shape/validate db' entity-eid :audit)]
      (is (sequential? results))
      (is (seq results))
      (is (some #(= :fail (:status %)) results)))))

(deftest validate-disabled-returns-empty
  (testing ":disabled mode returns [] without invoking any check"
    (let [_         (create-shape!
                      {:mm.shape/shape-id          "test-disabled-noop"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name]})
          entity-eid (create-test-memory!
                       {:mm.memory/rel-path "test/disabled-noop.md"})
          db'       (d/db @db/**conn*)]
      (is (= [] (shape/validate db' entity-eid :disabled))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; conformance-report — batch aggregation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest conformance-report-aggregates-class-instances
  (testing "Batch report covers all instances of a class against all applicable shapes"
    (let [_         (create-shape!
                      {:mm.shape/shape-id          "test-conformance-batch"
                       :mm.shape/applies-to        :mm/Memory
                       :mm.shape/description       "test"
                       :mm.shape/required-property [:mm.memory/name]
                       :mm.shape/severity          :violation})
          ;; 2 instances pass (have :name); 1 fails (missing :name)
          _         (create-test-memory! {:mm.memory/rel-path "test/c1.md"
                                          :mm.memory/name     "C1"})
          _         (create-test-memory! {:mm.memory/rel-path "test/c2.md"
                                          :mm.memory/name     "C2"})
          _         (create-test-memory! {:mm.memory/rel-path "test/c3.md"
                                          ;; no name
                                          })
          db'       (d/db @db/**conn*)
          report    (shape/conformance-report db' :mm/Memory)]
      (is (= :mm/Memory (:class report)))
      (is (>= (:instance-count report) 3))
      (is (>= (:shape-count report) 1))
      (is (pos? (:passes report)))
      (is (pos? (:failures report))))))
