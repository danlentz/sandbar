(ns sandbar.search-test
  "Tests for sandbar.search — Phase S Stage 3 of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [clojure.test :refer :all]
            [sandbar.search :as search]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "search-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; search-attribute — result-shape + ranking + limit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory!
  "Insert a :mm/Memory entity for fulltext testing.  Uses dt/make per the
  substrate-discipline rule (no raw d/transact)."
  [rel-path body-raw]
  (dt/make :mm/Memory
           {:mm.memory/rel-path rel-path
            :mm.memory/body-raw body-raw}))

(deftest search-attribute-result-shape-test
  (testing "search-attribute returns the canonical result-shape map"
    (make-memory! "test/datomic.md" "datomic project graph realizes Anderson lineage")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})]
      (testing "top-level keys"
        (is (contains? result :hits))
        (is (contains? result :total))
        (is (contains? result :returned))
        (is (contains? result :timing)))
      (testing ":timing carries :total-ms"
        (is (contains? (:timing result) :total-ms))
        (is (integer? (get-in result [:timing :total-ms])))
        (is (>= (get-in result [:timing :total-ms]) 0)))
      (testing ":hits is a vector of {:entity :score}"
        (is (vector? (:hits result)))
        (when (seq (:hits result))
          (let [first-hit (first (:hits result))]
            (is (contains? first-hit :entity))
            (is (contains? first-hit :score))
            (is (number? (:score first-hit)))
            (is (map? (:entity first-hit))))))
      (testing ":total + :returned are integers"
        (is (integer? (:total result)))
        (is (integer? (:returned result)))))))

(deftest search-attribute-finds-matching-memory-test
  (testing "search-attribute returns memories whose body matches the query"
    (make-memory! "test/datomic.md" "datomic project graph realizes Anderson lineage")
    (make-memory! "test/lucene.md"  "lucene scoring example for BM25F")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})]
      (is (= 1 (:total result))    "Exactly one memory matches 'datomic'")
      (is (= 1 (:returned result)) "Returned count matches total below limit")
      (is (seq (:hits result))     "Hit vector is non-empty")
      (is (= 1 (count (:hits result)))))))

(deftest search-attribute-empty-on-unmatched-test
  (testing "search-attribute returns empty hits + total=0 on unmatched query"
    (make-memory! "test/foo.md" "lorem ipsum dolor sit amet")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "zzzzznomatchterm"})]
      (is (= 0 (:total result)))
      (is (= 0 (:returned result)))
      (is (empty? (:hits result))))))

(deftest search-attribute-limit-test
  (testing "search-attribute honors :limit (truncates :hits, preserves :total)"
    (make-memory! "test/a.md" "datomic alpha")
    (make-memory! "test/b.md" "datomic beta")
    (make-memory! "test/c.md" "datomic gamma")
    (make-memory! "test/d.md" "datomic delta")
    (make-memory! "test/e.md" "datomic epsilon")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"
                    :limit     2})]
      (is (= 5 (:total result))    ":total reports pre-limit count")
      (is (= 2 (:returned result)) ":returned reports post-limit count")
      (is (= 2 (count (:hits result))) ":hits truncated to :limit"))))

(deftest search-attribute-limit-zero-no-cap-test
  (testing "search-attribute :limit 0 returns all hits (no cap)"
    (make-memory! "test/a.md" "datomic alpha")
    (make-memory! "test/b.md" "datomic beta")
    (make-memory! "test/c.md" "datomic gamma")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"
                    :limit     0})]
      (is (= 3 (:total result)))
      (is (= 3 (:returned result)))
      (is (= 3 (count (:hits result)))))))

(deftest search-attribute-hits-sorted-by-score-test
  (testing "search-attribute hits are sorted by score descending"
    (make-memory! "test/a.md" "datomic alpha")
    (make-memory! "test/b.md" "datomic beta")
    (make-memory! "test/c.md" "datomic gamma")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})
          scores (mapv :score (:hits result))]
      (is (seq scores))
      (is (apply >= scores)
          (str "Hit scores must be in non-increasing order, got: " scores)))))

(deftest search-attribute-entity-projection-test
  (testing "Each hit's :entity carries the memory's projected attributes"
    (make-memory! "test/foo.md" "datomic project graph")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})
          entity (-> result :hits first :entity)]
      (is (some? entity))
      (is (contains? entity :mm.memory/rel-path)
          "Projected entity carries declared slot values")
      (is (= "test/foo.md" (:mm.memory/rel-path entity)))
      (is (contains? entity :mm.memory/body-raw)
          "Projected entity carries body-raw slot"))))

(deftest search-attribute-precondition-rejects-non-fulltext-test
  (testing "search-attribute precondition rejects attributes that are not :db/fulltext true"
    ;; :mm.memory/memory-type is a keyword enum, not :db/fulltext
    (is (thrown? AssertionError
                 (search/search-attribute
                   {:attribute :mm.memory/memory-type
                    :query     "decision"}))
        ":db/fulltext-required precondition fires")))

(deftest search-attribute-lucene-syntax-test
  (testing "search-attribute accepts Lucene query syntax"
    (make-memory! "test/a.md" "datomic project graph realizes Anderson lineage")
    (make-memory! "test/b.md" "lucene scoring example BM25F")
    (make-memory! "test/c.md" "datomic and lucene together compose BM25F search")
    (testing "boolean AND"
      (let [result (search/search-attribute
                     {:attribute :mm.memory/body-raw
                      :query     "datomic AND lucene"})]
        (is (= 1 (:total result))
            "Only the memory with both 'datomic' AND 'lucene' matches")))
    (testing "boolean OR"
      (let [result (search/search-attribute
                     {:attribute :mm.memory/body-raw
                      :query     "Anderson OR BM25F"})]
        ;; Memories with 'Anderson' (a) OR 'BM25F' (b, c) = 3 total
        (is (= 3 (:total result)))))))
