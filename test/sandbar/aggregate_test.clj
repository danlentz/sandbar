(ns sandbar.aggregate-test
  "Tests for sandbar.aggregate — Phase G Stage 13 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [clojure.test :refer :all]
            [sandbar.aggregate :as agg]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "aggregate-test"}))

(defn- make-memory-typed!
  [name memory-type]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    (str "test/" name ".md")
            :mm.memory/name        name
            :mm.memory/memory-type memory-type
            :mm.memory/body-raw    "body content here"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; count-by
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest count-by-no-where-test
  (testing "count-by returns {:count int} for class instances"
    ;; Baseline: schema-load adds 2 :mm/Shape entities (transitively :mm/Memory
    ;; via :mm/Meta) per Phase D Temporal Tier-2 XOR Shape declarations in
    ;; schema/mm-temporal.edn (:memory.shapes/interval-begins-at-xor +
    ;; :memory.shapes/interval-ends-at-xor).  Test creates 3 additional
    ;; :mm/Memory entities; total = baseline-2 + created-3 = 5.
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (make-memory-typed! "gamma" :decision)
    (let [result (agg/count-by {:class :mm/Memory})]
      (is (= {:count 5} result)))))

(deftest count-by-with-where-test
  (testing "count-by :where restricts by predicate"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (make-memory-typed! "gamma" :decision)
    (let [decisions (agg/count-by
                      {:class :mm/Memory
                       :where '[[?e :mm.memory/memory-type :decision]]})
          plans     (agg/count-by
                      {:class :mm/Memory
                       :where '[[?e :mm.memory/memory-type :plan]]})]
      (is (= {:count 2} decisions))
      (is (= {:count 1} plans)))))

(deftest count-by-no-match-test
  (testing "count-by returns 0 when no entity matches"
    (make-memory-typed! "alpha" :decision)
    (let [result (agg/count-by
                   {:class :mm/Memory
                    :where '[[?e :mm.memory/memory-type :nonexistent-type]]})]
      (is (= {:count 0} result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; group-by
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest group-by-counts-per-value-test
  (testing "group-by produces {value count} map + total"
    (make-memory-typed! "alpha"  :decision)
    (make-memory-typed! "beta"   :decision)
    (make-memory-typed! "gamma"  :plan)
    (make-memory-typed! "delta"  :observation)
    (let [result (agg/group-by
                   {:class    :mm/Memory
                    :group-by :mm.memory/memory-type})
          {:keys [groups total]} result]
      (is (= 4 total))
      (is (= 2 (get groups :decision)))
      (is (= 1 (get groups :plan)))
      (is (= 1 (get groups :observation))))))

(deftest group-by-with-where-test
  (testing "group-by :where restricts the candidate set before grouping"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :decision)
    (make-memory-typed! "gamma" :plan)
    (let [result (agg/group-by
                   {:class    :mm/Memory
                    :group-by :mm.memory/memory-type
                    :where    '[[?e :mm.memory/memory-type :decision]]})
          {:keys [groups total]} result]
      (is (= 2 total))
      (is (= 2 (get groups :decision)))
      (is (nil? (get groups :plan))))))

(deftest group-by-skips-unset-slot-test
  (testing "group-by skips entities where the group slot is unset"
    ;; Insert one entity WITHOUT :mm.memory/memory-type slot
    (dt/make :mm/Memory
             {:mm.memory/rel-path "test/no-type.md"
              :mm.memory/name     "no-type"
              :mm.memory/body-raw "irrelevant"})
    (make-memory-typed! "with-type" :decision)
    (let [result (agg/group-by
                   {:class    :mm/Memory
                    :group-by :mm.memory/memory-type})]
      (is (= 1 (:total result))
          "Only the entity WITH :mm.memory/memory-type counted in any bucket")
      (is (= 1 (get-in result [:groups :decision]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank-by — :degree
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rank-by-degree-shape-test
  (testing "rank-by :degree returns {:hits :total :returned} with rank-score"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (let [result (agg/rank-by {:class :mm/Memory :rank-by :degree})]
      (is (contains? result :hits))
      (is (contains? result :total))
      (is (contains? result :returned))
      (is (vector? (:hits result)))
      (when (seq (:hits result))
        (let [hit (first (:hits result))]
          (is (contains? hit :entity))
          (is (contains? hit :rank-score))
          (is (number? (:rank-score hit))))))))

(deftest rank-by-degree-orders-descending-test
  (testing "rank-by :degree orders by descending rank-score"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (make-memory-typed! "gamma" :observation)
    (let [result (agg/rank-by {:class :mm/Memory :rank-by :degree})
          scores (mapv :rank-score (:hits result))]
      (when (> (count scores) 1)
        (is (apply >= scores)
            (str "Hits sorted descending by rank-score, got: " scores))))))

(deftest rank-by-degree-limit-test
  (testing "rank-by :degree honors :limit"
    ;; Baseline: schema-load adds 2 :mm/Shape entities (transitively :mm/Memory)
    ;; per Phase D Temporal Tier-2 — see count-by-no-where-test for context.
    ;; Test creates 5 additional; total = baseline-2 + created-5 = 7.
    (doseq [n (range 5)] (make-memory-typed! (str "mem-" n) :decision))
    (let [result (agg/rank-by {:class :mm/Memory :rank-by :degree :limit 2})]
      (is (= 7 (:total result)))
      (is (= 2 (:returned result)))
      (is (= 2 (count (:hits result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank-by — :recency / :freshness with caller-supplied temporal-slot
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory-with-timestamp!
  [name last-touched]
  (dt/make :mm/Memory
           {:mm.memory/rel-path     (str "test/" name ".md")
            :mm.memory/name         name
            :mm.memory/memory-type  :decision
            :mm.memory/body-raw     "body"
            :mm.memory/last-touched last-touched}))

(deftest rank-by-recency-orders-most-recent-first-test
  (testing "rank-by :recency orders by descending timestamp"
    (make-memory-with-timestamp! "old"     #inst "2025-01-01")
    (make-memory-with-timestamp! "newer"   #inst "2026-01-01")
    (make-memory-with-timestamp! "newest"  #inst "2026-05-01")
    (let [result (agg/rank-by {:class         :mm/Memory
                               :rank-by       :recency
                               :temporal-slot :mm.memory/last-touched})
          names  (mapv #(get-in % [:entity :mm.memory/name]) (:hits result))]
      (is (= ["newest" "newer" "old"] names)
          "Most-recent timestamp first"))))

(deftest rank-by-freshness-orders-oldest-first-test
  (testing "rank-by :freshness orders by ascending timestamp"
    (make-memory-with-timestamp! "old"     #inst "2025-01-01")
    (make-memory-with-timestamp! "newer"   #inst "2026-01-01")
    (make-memory-with-timestamp! "newest"  #inst "2026-05-01")
    (let [result (agg/rank-by {:class         :mm/Memory
                               :rank-by       :freshness
                               :temporal-slot :mm.memory/last-touched})
          names  (mapv #(get-in % [:entity :mm.memory/name]) (:hits result))]
      (is (= ["old" "newer" "newest"] names)
          "Oldest timestamp first (stalest candidates surface for review)"))))

(deftest rank-by-temporal-missing-slot-throws-test
  (testing "rank-by :recency without :temporal-slot opt throws (precondition)"
    (is (thrown? AssertionError
                 (agg/rank-by {:class :mm/Memory :rank-by :recency})))))

(deftest rank-by-invalid-axis-throws-test
  (testing "rank-by with non-recognized axis throws (precondition)"
    (is (thrown? AssertionError
                 (agg/rank-by {:class :mm/Memory :rank-by :bogus-axis})))))
