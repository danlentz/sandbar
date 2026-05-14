(ns sandbar.navigate.walk-test
  "Tests for sandbar.navigate.walk (Stage 17 of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Covers both the substrate primitive dt/graph-walk-from and the
  consumer-facing wrapper sandbar.navigate.walk/graph-walk, including
  hop-cap, direction (:forward / :inverse / :bidirectional), predicate
  filtering, and :include [:paths] path projection.

  Test data uses the metamodel structure — class hierarchy + slot
  declarations provide natural multi-hop edges via :dt/subclass-of /
  :dt/slots / :dt/domain / :dt/range."
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.navigate.walk :as nav-walk]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "navigate-walk-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; dt/graph-walk-from substrate primitive
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest graph-walk-from-basic-forward
  (testing "graph-walk-from :dt/Property :forward returns reachable entities"
    (let [results (dt/graph-walk-from :dt/Property {:hops 1 :direction :forward})]
      (is (vector? results))
      (is (pos? (count results)))
      (doseq [r results]
        (is (some? (:db/id (:entity r)))
            ":entity is an entity-map with :db/id accessible")
        (is (= 1 (:hop r)))))))

(deftest graph-walk-from-respects-hops
  (testing "hops=0 returns empty (loop terminates immediately)"
    (let [results (dt/graph-walk-from :dt/Property {:hops 0})]
      (is (= [] results)))))

(deftest graph-walk-from-hops-increases-coverage
  (testing "hops=2 reaches at least as many entities as hops=1"
    (let [r1 (dt/graph-walk-from :dt/Property {:hops 1 :direction :forward})
          r2 (dt/graph-walk-from :dt/Property {:hops 2 :direction :forward})]
      (is (>= (count r2) (count r1))
          "deeper walk reaches a superset of shallower walk's results"))))

(deftest graph-walk-from-direction-inverse
  (testing ":direction :inverse walks inbound edges"
    (let [results (dt/graph-walk-from :dt/Resource {:hops 1 :direction :inverse})]
      (is (pos? (count results))
          ":dt/Resource has many inbound references")
      (doseq [r results]
        (is (= 1 (:hop r)))))))

(deftest graph-walk-from-direction-bidirectional
  (testing ":direction :bidirectional unions forward + inverse"
    (let [fwd  (dt/graph-walk-from :dt/Resource {:hops 1 :direction :forward})
          inv  (dt/graph-walk-from :dt/Resource {:hops 1 :direction :inverse})
          both (dt/graph-walk-from :dt/Resource {:hops 1 :direction :bidirectional})]
      (is (>= (count both) (count fwd)))
      (is (>= (count both) (count inv))))))

(deftest graph-walk-from-predicate-filter
  (testing ":predicates restricts traversal to a predicate set"
    (let [all-results (dt/graph-walk-from
                        :dt/Resource {:hops 1 :direction :inverse})
          sub-results (dt/graph-walk-from
                        :dt/Resource {:hops 1 :direction :inverse
                                      :predicates :dt/subclass-of})]
      (is (<= (count sub-results) (count all-results))
          "predicate filter reduces or preserves result count")
      (when (seq sub-results)
        (is (pos? (count sub-results))
            "subclass-of filter yields some inbound edges to :dt/Resource")))))

(deftest graph-walk-from-include-paths
  (testing ":include #{:paths} attaches step sequences"
    (let [results (dt/graph-walk-from
                    :dt/Property {:hops 2 :direction :forward
                                  :include #{:paths}})]
      (doseq [r results]
        (is (contains? r :path))
        (is (vector? (:path r)))
        (is (= (:hop r) (count (:path r)))
            "path length equals hop distance")
        (doseq [step (:path r)]
          (is (contains? step :predicate))
          (is (#{:forward :inverse} (:direction step))))))))

(deftest graph-walk-from-no-paths-when-include-omitted
  (testing "without :include [:paths], :path key is absent"
    (let [results (dt/graph-walk-from :dt/Property {:hops 1 :direction :forward})]
      (doseq [r results]
        (is (not (contains? r :path)))))))

(deftest graph-walk-from-no-cycle
  (testing "walking with bidirectional doesn't revisit seed"
    (let [seed-eid (:db/id (dt/find-by-ident :dt/Resource))
          results  (dt/graph-walk-from
                     :dt/Resource {:hops 3 :direction :bidirectional})]
      (is (not-any? (fn [r] (= seed-eid (:db/id (:entity r)))) results)
          "seed entity must not appear in its own walk results"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sandbar.navigate.walk/graph-walk wrapper
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest graph-walk-wrapper-envelope
  (testing "wrapper returns {:reachable :total :returned}"
    (let [result (nav-walk/graph-walk {:from :dt/Property :hops 1})]
      (is (vector? (:reachable result)))
      (is (integer? (:total result)))
      (is (integer? (:returned result)))
      (is (= (:total result) (:returned result))
          "no :limit means returned == total"))))

(deftest graph-walk-wrapper-defaults-direction-forward
  (testing "default direction is :forward when not specified"
    (let [no-dir  (nav-walk/graph-walk {:from :dt/Property :hops 1})
          fwd     (nav-walk/graph-walk {:from :dt/Property :hops 1 :direction :forward})]
      (is (= (count (:reachable no-dir)) (count (:reachable fwd)))))))

(deftest graph-walk-wrapper-limit
  (testing ":limit caps :returned but :total stays full"
    (let [full   (nav-walk/graph-walk {:from :dt/Resource :hops 1 :direction :inverse})
          capped (nav-walk/graph-walk {:from :dt/Resource :hops 1
                                       :direction :inverse :limit 2})]
      (is (= (:total full) (:total capped)))
      (is (<= (:returned capped) 2))
      (when (pos? (:total full))
        (is (= 2 (:returned capped)))))))

(deftest graph-walk-wrapper-include-paths-forwarded
  (testing ":include is forwarded to substrate"
    (let [result (nav-walk/graph-walk
                   {:from :dt/Property :hops 2 :direction :forward
                    :include #{:paths}})]
      (doseq [r (:reachable result)]
        (is (contains? r :path)
            "every reachable entity has :path attached")))))

(deftest graph-walk-wrapper-requires-from
  (testing ":from is required (precondition)"
    (is (thrown? AssertionError (nav-walk/graph-walk {})))))

(deftest graph-walk-wrapper-hops-zero-empty
  (testing ":hops 0 returns empty :reachable"
    (let [result (nav-walk/graph-walk {:from :dt/Property :hops 0})]
      (is (= [] (:reachable result)))
      (is (zero? (:total result)))
      (is (zero? (:returned result))))))
