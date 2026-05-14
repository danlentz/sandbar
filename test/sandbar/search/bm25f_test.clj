(ns sandbar.search.bm25f-test
  "Tests for sandbar.search.bm25f — Stage 4b of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Validates the ported Robertson-Zaragoza BM25F canonical-form scoring:
  - analyze-entity is metamodel-driven (reads :dt/bm25f-weights from class)
  - corpus-stats N + df + avgdl reflect the analyzed corpus
  - score is non-negative double; 0.0 for no-match; monotonic in tf
  - score-explain returns the per-term breakdown
  - 4-arity field-weights override semantics."
  (:require [clojure.test :refer :all]
            [sandbar.search.bm25f :as bm25f]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "bm25f-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; analyze-entity — metamodel-driven field extraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory-map
  "Build a plain entity-map shaped like a :mm/Memory for analyze-entity input.
  Avoids dt/make / transact round-trip; bm25f operates on entity maps, so the
  unit tests can use plain maps without the test-db fixture's overhead."
  [name desc body]
  {:mm.memory/name        name
   :mm.memory/description desc
   :mm.memory/body-raw    body})

(deftest analyze-entity-shape-test
  (testing "analyze-entity returns the canonical analyzed-entity shape"
    (let [entity (make-memory-map "Datomic memorial"
                                  "Anderson lineage notes"
                                  "datomic project graph realizes anderson")
          analyzed (bm25f/analyze-entity :mm/Memory entity)]
      (is (contains? analyzed :entity))
      (is (contains? analyzed :eid))
      (is (contains? analyzed :fields))
      (is (= entity (:entity analyzed))
          "Original entity-map preserved as back-reference")
      (testing "fields includes the class's :dt/bm25f-weights slots"
        (is (contains? (:fields analyzed) :mm.memory/name))
        (is (contains? (:fields analyzed) :mm.memory/description))
        (is (contains? (:fields analyzed) :mm.memory/body-raw)))
      (testing "each field carries {:tf {} :len int}"
        (let [name-field (get-in analyzed [:fields :mm.memory/name])]
          (is (map? (:tf name-field)))
          (is (integer? (:len name-field)))
          (is (pos? (:len name-field))))))))

(deftest analyze-entity-tokenization-test
  (testing "analyze-entity tokenizes each slot value via sandbar.search.analysis"
    (let [entity (make-memory-map "datomic graph" "anderson lineage" "")
          analyzed (bm25f/analyze-entity :mm/Memory entity)]
      ;; 'datomic' stems to 'datom' (step-4 'ic' removal at m>1? actually datomic:
      ;; d-a-t-o-m-i-c, measure: d-cons started, a-vowel vc=0, t-cons vc=1, o-vowel vc=1,
      ;; m-cons vc=2, i-vowel vc=2, c-cons vc=3. m=3>1. step-4 ic->ε => 'datom').
      (is (contains? (get-in analyzed [:fields :mm.memory/name :tf]) "datom"))
      ;; 'graph' has no Porter-stripable suffix; stem stays 'graph'.
      (is (contains? (get-in analyzed [:fields :mm.memory/name :tf]) "graph"))
      ;; 'anderson' is ASCII; stem applies: anderson -> step-4 nothing matches.
      (is (contains? (get-in analyzed [:fields :mm.memory/description :tf]) "anderson")))))

(deftest analyze-entity-empty-slot-test
  (testing "analyze-entity handles missing or non-string slot values"
    (let [entity   (make-memory-map "name only" nil nil)
          analyzed (bm25f/analyze-entity :mm/Memory entity)]
      (testing "missing slot yields empty :tf + :len 0"
        (is (= 0 (get-in analyzed [:fields :mm.memory/description :len])))
        (is (= {} (get-in analyzed [:fields :mm.memory/description :tf])))
        (is (= 0 (get-in analyzed [:fields :mm.memory/body-raw :len]))))
      (testing "present slot still analyzed"
        (is (pos? (get-in analyzed [:fields :mm.memory/name :len])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; corpus-stats — N + df + avgdl aggregation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest corpus-stats-counts-entities-test
  (testing "corpus-stats :N counts analyzed entities"
    (let [a1 (bm25f/analyze-entity :mm/Memory (make-memory-map "one" "" ""))
          a2 (bm25f/analyze-entity :mm/Memory (make-memory-map "two" "" ""))
          stats (bm25f/corpus-stats [a1 a2])]
      (is (= 2 (:N stats)))))
  (testing "corpus-stats :N is 0 for empty input"
    (is (= 0 (:N (bm25f/corpus-stats []))))))

(deftest corpus-stats-df-test
  (testing "df counts DOCUMENT frequency (entities containing the term)"
    (let [a1 (bm25f/analyze-entity :mm/Memory
               (make-memory-map "datomic" "" "datomic datomic"))    ; 1 entity contains 'datom'
          a2 (bm25f/analyze-entity :mm/Memory
               (make-memory-map "lucene" "" "lucene"))               ; 1 entity contains 'lucen'
          stats (bm25f/corpus-stats [a1 a2])]
      (is (= 1 (get (:df stats) "datom"))
          "df is per-entity-occurrence, not per-mention-occurrence")
      (is (= 1 (get (:df stats) "lucen")))
      (is (nil? (get (:df stats) "missing"))))))

(deftest corpus-stats-avgdl-test
  (testing ":avgdl is per-slot average token count across entities"
    (let [a1 (bm25f/analyze-entity :mm/Memory
               (make-memory-map "foo bar baz" "" ""))    ; name: 3 tokens
          a2 (bm25f/analyze-entity :mm/Memory
               (make-memory-map "x" "" ""))               ; name: 1 token
          stats (bm25f/corpus-stats [a1 a2])]
      (is (= 2.0 (get (:avgdl stats) :mm.memory/name))
          "avg of 3 + 1 = 2.0")
      (is (= 0.0 (get (:avgdl stats) :mm.memory/description))
          "avg of empty slots is 0.0"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; score — non-negative, zero-on-no-match, monotonic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest score-non-negative-test
  (testing "score is non-negative for matching + non-matching queries"
    (let [a1      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic graph" "" "datomic graph datomic"))
          a2      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "lucene" "" "lucene"))
          stats   (bm25f/corpus-stats [a1 a2])
          weights (dt/bm25f-weights-of :mm/Memory)]
      (is (>= (bm25f/score ["datom"] a1 stats weights) 0.0))
      (is (>= (bm25f/score ["lucen"] a1 stats weights) 0.0)))))

(deftest score-zero-on-no-match-test
  (testing "score returns 0.0 when no query term appears in the entity"
    (let [a1      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic" "" "datomic"))
          stats   (bm25f/corpus-stats [a1])
          weights (dt/bm25f-weights-of :mm/Memory)]
      (is (= 0.0 (bm25f/score ["nonexistent"] a1 stats weights))))))

(deftest score-positive-on-match-test
  (testing "score is strictly positive when a query term appears in a weighted slot"
    (let [a1      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic graph" "" "graph datomic"))
          a2      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "lucene" "" "lucene"))
          stats   (bm25f/corpus-stats [a1 a2])
          weights (dt/bm25f-weights-of :mm/Memory)]
      (is (pos? (bm25f/score ["datom"] a1 stats weights))))))

(deftest score-name-outweighs-body-test
  (testing "matching :name should outscore matching :body alone (weight 12 vs 1)"
    (let [a-name  (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic" "" "lorem ipsum"))
          a-body  (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "lorem ipsum" "" "datomic"))
          stats   (bm25f/corpus-stats [a-name a-body])
          weights (dt/bm25f-weights-of :mm/Memory)
          score-n (bm25f/score ["datom"] a-name stats weights)
          score-b (bm25f/score ["datom"] a-body stats weights)]
      (is (> score-n score-b)
          "Higher weight on :name (12) means hits there outscore body (1)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; score-explain — per-term breakdown
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest score-explain-shape-test
  (testing "score-explain returns {:score :terms} with per-term breakdown"
    (let [a1      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic graph" "" "datomic"))
          stats   (bm25f/corpus-stats [a1])
          weights (dt/bm25f-weights-of :mm/Memory)
          result  (bm25f/score-explain ["datom" "graph"] a1 stats weights)]
      (is (contains? result :score))
      (is (contains? result :terms))
      (is (number? (:score result)))
      (is (vector? (:terms result)))
      (is (= 2 (count (:terms result))))
      (testing "each term entry has the canonical breakdown"
        (let [t0 (first (:terms result))]
          (is (contains? t0 :term))
          (is (contains? t0 :df))
          (is (contains? t0 :idf))
          (is (contains? t0 :tilde-tf))
          (is (contains? t0 :b-tf))
          (is (contains? t0 :contribution)))))))

(deftest score-explain-score-equals-sum-of-contributions-test
  (testing "score-explain's :score matches sum of per-term :contribution"
    (let [a1      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic graph anderson" "" ""))
          stats   (bm25f/corpus-stats [a1])
          weights (dt/bm25f-weights-of :mm/Memory)
          result  (bm25f/score-explain ["datom" "graph" "anderson"] a1 stats weights)]
      (is (= (:score result)
             (reduce + 0.0 (map :contribution (:terms result))))))))

(deftest score-explain-equals-score-test
  (testing "score-explain's :score matches score for the same inputs"
    (let [a1      (bm25f/analyze-entity :mm/Memory
                    (make-memory-map "datomic" "" "graph datomic"))
          stats   (bm25f/corpus-stats [a1])
          weights (dt/bm25f-weights-of :mm/Memory)]
      (is (= (bm25f/score ["datom"] a1 stats weights)
             (:score (bm25f/score-explain ["datom"] a1 stats weights)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; field-weights override — restrict scoring to one or more slots
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest score-field-weights-override-test
  (testing "scoring with a restricted weight map ignores other slots"
    (let [a1            (bm25f/analyze-entity :mm/Memory
                          (make-memory-map "name-only" "" "body-only"))
          stats         (bm25f/corpus-stats [a1])
          name-only-w   {:mm.memory/name 12.0}
          body-only-w   {:mm.memory/body-raw 1.0}
          score-name    (bm25f/score ["name"] a1 stats name-only-w)
          score-body    (bm25f/score ["name"] a1 stats body-only-w)]
      (is (pos? score-name)
          "Term in :name yields positive score when name is weighted")
      (is (zero? score-body)
          "Term in :name yields zero score when only :body is weighted")))
  (testing "empty field-weights yields zero score"
    (let [a1    (bm25f/analyze-entity :mm/Memory
                  (make-memory-map "datomic" "" ""))
          stats (bm25f/corpus-stats [a1])]
      (is (zero? (bm25f/score ["datom"] a1 stats {}))))))
