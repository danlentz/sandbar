(ns sandbar.db.datomic-set-replace-test
  "Tests for the schema-EDN-load set-replace mechanism for class-level
   cardinality-many meta-slots — :dt/slots, :dt/codec-slot-order,
   :dt/bm25f-weights.

   Per observations/schema_edn_reload_is_additive_not_set_replace_for_cardinality_many_class_meta_slots_2026_05_26.md
   + interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21.md.

   Exercises sandbar.db.datomic/with-class-meta-slot-set-replace +
   class-meta-slot-retracts against a controlled in-memory Datomic DB."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture: minimal in-memory Datomic DB with a single :dt/Class and its
;; required meta-slot declarations.  Each test installs its own initial
;; state + runs against it.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *conn* nil)
(def ^:dynamic *db-uri* nil)

(defn with-fresh-in-mem-db [test-fn]
  (let [uri (str "datomic:mem://datomic-set-replace-test-"
                 (random-uuid))]
    (d/create-database uri)
    (let [c (d/connect uri)]
      ;; Install minimal meta-schema needed for the test scenario:
      ;; - :db/ident (Datomic built-in; pre-installed)
      ;; - :dt/slots — :db.type/ref cardinality-many
      ;; - :dt/codec-slot-order — :db.type/tuple cardinality-many
      ;; - :dt/bm25f-weights — :db.type/tuple cardinality-many
      ;; - test attribute :test/Class — a placeholder class entity ident
      @(d/transact c
        [{:db/ident :dt/slots
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/many}
         {:db/ident :dt/codec-slot-order
          :db/valueType :db.type/tuple
          :db/tupleTypes [:db.type/keyword :db.type/long]
          :db/cardinality :db.cardinality/many}
         {:db/ident :dt/bm25f-weights
          :db/valueType :db.type/tuple
          :db/tupleTypes [:db.type/keyword :db.type/double]
          :db/cardinality :db.cardinality/many}
         ;; some slot idents that :dt/slots will reference
         {:db/ident :test/slot-a}
         {:db/ident :test/slot-b}
         {:db/ident :test/slot-c}
         {:db/ident :test/slot-d}
         ;; the test class entity (ident only; no other attributes needed)
         {:db/ident :test/Class}])
      (binding [*conn* c
                *db-uri* uri]
        (try
          (test-fn)
          (finally
            (d/release c)
            (d/delete-database uri)))))))

(use-fixtures :each with-fresh-in-mem-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest first-load-no-retracts
  (testing "First-time entity declaration emits NO retracts (nothing prior to retract)"
    (let [stmt [{:db/ident :test/Class
                 :dt/slots [:test/slot-a :test/slot-b]
                 :dt/codec-slot-order [[:test/slot-a 0]
                                       [:test/slot-b 1]]
                 :dt/bm25f-weights [[:test/slot-a 5.0]
                                    [:test/slot-b 2.0]]}]
          retracts (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %) stmt)]
      ;; Class :db/ident exists but no slot values yet → no retracts
      (is (empty? retracts)))))

(deftest reload-with-fewer-slots-retracts-missing
  (testing "Re-load with FEWER :dt/slots entries retracts the missing ones"
    ;; Initial load: 4 slots
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/slots [:test/slot-a :test/slot-b :test/slot-c :test/slot-d]}])
    ;; Reload-stmt: only 2 slots
    (let [new-stmt [{:db/ident :test/Class
                     :dt/slots [:test/slot-a :test/slot-b]}]
          retracts (vec (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                                new-stmt))]
      (is (= 2 (count retracts))
          "Should emit retract ops for slot-c + slot-d")
      (let [retracted-slots (set (map (fn [[_ _ _ v]] v) retracts))]
        ;; Retracted values are :db/id refs; resolve to idents
        (let [retracted-idents (set (map #(:db/ident (d/entity (d/db *conn*) %))
                                         retracted-slots))]
          (is (= #{:test/slot-c :test/slot-d} retracted-idents)
              "Retracts target the slots that are missing in the new declaration"))))))

(deftest reload-with-more-slots-no-retracts
  (testing "Re-load with MORE :dt/slots entries emits no retracts (existing slots still in new declaration)"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/slots [:test/slot-a :test/slot-b]}])
    (let [new-stmt [{:db/ident :test/Class
                     :dt/slots [:test/slot-a :test/slot-b :test/slot-c]}]
          retracts (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                           new-stmt)]
      (is (empty? retracts)))))

(deftest reload-with-identical-slots-no-retracts
  (testing "Re-load with identical :dt/slots emits no retracts (idempotency)"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/slots [:test/slot-a :test/slot-b]}])
    (let [new-stmt [{:db/ident :test/Class
                     :dt/slots [:test/slot-a :test/slot-b]}]
          retracts (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                           new-stmt)]
      (is (empty? retracts)))))

(deftest reload-without-mentioning-meta-slot-no-retracts
  (testing "Re-load that does NOT mention a meta-slot leaves it alone (no retracts)"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/slots [:test/slot-a :test/slot-b]
        :dt/codec-slot-order [[:test/slot-a 0]]}])
    ;; New stmt only declares :dt/slots, omitting :dt/codec-slot-order
    (let [new-stmt [{:db/ident :test/Class
                     :dt/slots [:test/slot-a :test/slot-b]}]
          retracts (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                           new-stmt)]
      (is (empty? retracts)
          "Omitted slots are not touched"))))

(deftest tuple-typed-codec-slot-order-set-replace
  (testing "Tuple-typed :dt/codec-slot-order retracts old tuples not in new declaration"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/codec-slot-order [[:test/slot-a 0]
                              [:test/slot-b 1]
                              [:test/slot-c 2]]}])
    (let [new-stmt [{:db/ident :test/Class
                     :dt/codec-slot-order [[:test/slot-a 0]
                                           [:test/slot-b 1]]}]
          retracts (vec (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                                new-stmt))]
      (is (= 1 (count retracts)))
      (let [[op _ slot v] (first retracts)]
        (is (= :db/retract op))
        (is (= :dt/codec-slot-order slot))
        (is (= [:test/slot-c 2] v))))))

(deftest tuple-typed-bm25f-weights-set-replace
  (testing "Tuple-typed :dt/bm25f-weights retracts old tuples not in new declaration"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/bm25f-weights [[:test/slot-a 10.0]
                           [:test/slot-b 5.0]]}])
    (let [new-stmt [{:db/ident :test/Class
                     :dt/bm25f-weights [[:test/slot-a 12.0]
                                        [:test/slot-b 8.0]]}]
          retracts (vec (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                                new-stmt))]
      ;; Both tuples differ in weight → both should retract
      (is (= 2 (count retracts)))
      (let [retracted-tuples (set (map (fn [[_ _ _ v]] v) retracts))]
        (is (= #{[:test/slot-a 10.0] [:test/slot-b 5.0]} retracted-tuples))))))

(deftest non-class-set-replace-slots-not-affected
  (testing "Slots NOT in class-set-replace-meta-slots are unaffected (no retracts)"
    ;; Install an additional cardinality-many ref slot
    @(d/transact *conn*
      [{:db/ident :test/other-slot
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/many}])
    @(d/transact *conn*
      [{:db/ident :test/Class
        :test/other-slot [:test/slot-a :test/slot-b]}])
    (let [new-stmt [{:db/ident :test/Class
                     :test/other-slot [:test/slot-a]}]
          retracts (mapcat #(db/class-meta-slot-retracts (d/db *conn*) %)
                           new-stmt)]
      (is (empty? retracts)
          ":test/other-slot is not in class-set-replace-meta-slots so no retracts"))))

(deftest with-class-meta-slot-set-replace-prepends-retracts
  (testing "with-class-meta-slot-set-replace prepends retracts to the stmt"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/slots [:test/slot-a :test/slot-b :test/slot-c]}])
    (let [new-stmt [{:db/ident :test/Class
                     :dt/slots [:test/slot-a]}]
          augmented (db/with-class-meta-slot-set-replace (d/db *conn*) new-stmt)]
      (is (= 3 (count augmented))
          "2 retracts (for slot-b + slot-c) + 1 original stmt entry")
      ;; First two should be retracts
      (is (every? #(= :db/retract (first %)) (take 2 augmented)))
      ;; Last should be the original entity-map
      (is (= (first new-stmt) (last augmented))))))

(deftest end-to-end-load-schema-replacement
  (testing "End-to-end: transact stmt with set-replace augmentation results in cleaned DB state"
    @(d/transact *conn*
      [{:db/ident :test/Class
        :dt/slots [:test/slot-a :test/slot-b :test/slot-c]
        :dt/codec-slot-order [[:test/slot-a 0]
                              [:test/slot-b 1]
                              [:test/slot-c 2]]}])
    ;; Re-declare with set-replace augmentation
    (let [new-stmt [{:db/ident :test/Class
                     :dt/slots [:test/slot-a]
                     :dt/codec-slot-order [[:test/slot-a 0]]}]
          augmented (db/with-class-meta-slot-set-replace (d/db *conn*) new-stmt)]
      @(d/transact *conn* augmented)
      (let [post-db (d/db *conn*)
            ;; Use Datalog query for slot enumeration — more explicit
            ;; than Entity-map ref traversal (which has lazy-realization
            ;; quirks across some Datomic releases).
            slot-idents (set (d/q '[:find [?ident ...]
                                    :in $ ?cls
                                    :where [?cls :dt/slots ?e]
                                           [?e :db/ident ?ident]]
                                  post-db :test/Class))
            tuples (set (d/q '[:find [?tup ...]
                               :in $ ?cls
                               :where [?cls :dt/codec-slot-order ?tup]]
                             post-db :test/Class))]
        (is (= #{:test/slot-a} slot-idents)
            ":dt/slots is now exactly {slot-a} (b + c retracted)")
        (is (= #{[:test/slot-a 0]} tuples)
            ":dt/codec-slot-order is now exactly {[slot-a 0]} (others retracted)")))))
