(ns sandbar.navigate.edges-test
  "Tests for sandbar.navigate.edges (Stage 16 of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Covers both the consumer-facing namespace wrappers (inbound-edges /
  outbound-edges) and indirectly exercises the substrate dt/* primitives
  (inbound-edges-of / outbound-edges-of).

  Test data uses the metamodel's existing ref-typed structure (every
  :dt/Class has :dt/subclass-of + :dt/slots edges, every :dt/Property has
  :dt/domain + :dt/range edges) — guaranteed populated in the test
  fixture without additional scaffolding."
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.navigate.edges :as nav-edges]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "navigate-edges-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; dt/outbound-edges-of substrate primitive
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest outbound-edges-of-returns-ref-attr-pairs
  (testing "outbound-edges-of finds :dt/subclass-of + :dt/slots edges on a class"
    (let [edges (dt/outbound-edges-of :dt/Property)
          preds (set (map :predicate edges))]
      (is (vector? edges))
      (is (contains? preds :dt/subclass-of)
          "metamodel classes have :dt/subclass-of edges")
      (is (contains? preds :dt/slots)
          "metamodel classes have :dt/slots edges")
      (doseq [e edges]
        (is (some? (:target e)) "every edge has a target entity-map")))))

(deftest outbound-edges-of-predicate-filter
  (testing ":predicate restricts to specific attribute"
    (let [edges (dt/outbound-edges-of :dt/Property {:predicate :dt/subclass-of})
          preds (set (map :predicate edges))]
      (is (= #{:dt/subclass-of} preds)
          "every returned edge has :dt/subclass-of as predicate"))))

(deftest outbound-edges-of-multi-predicate-filter
  (testing ":predicate as collection restricts to the set"
    (let [edges (dt/outbound-edges-of
                  :dt/Property {:predicate [:dt/subclass-of :dt/slots]})
          preds (set (map :predicate edges))]
      (is (every? #{:dt/subclass-of :dt/slots} preds))
      (is (contains? preds :dt/subclass-of))
      (is (contains? preds :dt/slots)))))

(deftest outbound-edges-of-bare-form-no-opts
  (testing "outbound-edges-of with bare entity arg returns full edge set"
    (let [bare-edges (dt/outbound-edges-of :dt/Property)
          nil-edges  (dt/outbound-edges-of :dt/Property nil)]
      (is (= (set bare-edges) (set nil-edges))
          "bare form and nil-opts form return equivalent results"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; dt/inbound-edges-of substrate primitive
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest inbound-edges-of-returns-ref-attr-pairs
  (testing "inbound-edges-of :dt/Resource finds inbound :dt/subclass-of from subclasses"
    (let [edges (dt/inbound-edges-of :dt/Resource)
          preds (set (map :predicate edges))]
      (is (vector? edges))
      (is (pos? (count edges))
          ":dt/Resource is the universal root — should have many inbound edges")
      (is (contains? preds :dt/subclass-of)
          "subclasses cite :dt/Resource via :dt/subclass-of")
      (doseq [e edges]
        (is (some? (:source e)) "every edge has a source entity-map")))))

(deftest inbound-edges-of-predicate-filter
  (testing ":predicate restricts to specific attribute"
    (let [edges (dt/inbound-edges-of :dt/Resource {:predicate :dt/subclass-of})
          preds (set (map :predicate edges))]
      (is (= #{:dt/subclass-of} preds)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sandbar.navigate.edges/outbound-edges (opts-shaped wrapper)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest outbound-edges-wrapper-shape
  (testing "outbound-edges returns {:edges :total :returned} envelope"
    (let [result (nav-edges/outbound-edges {:entity :dt/Property})]
      (is (vector? (:edges result)))
      (is (integer? (:total result)))
      (is (integer? (:returned result)))
      (is (= (:total result) (:returned result))
          "no :limit means returned == total"))))

(deftest outbound-edges-wrapper-respects-limit
  (testing ":limit caps :returned but :total reflects full edge count"
    (let [full   (nav-edges/outbound-edges {:entity :dt/Property})
          capped (nav-edges/outbound-edges {:entity :dt/Property :limit 1})]
      (is (= (:total full) (:total capped))
          ":total is independent of :limit")
      (is (<= (:returned capped) 1))
      (when (pos? (:total full))
        (is (= 1 (:returned capped)))))))

(deftest outbound-edges-wrapper-predicate-filter
  (testing ":predicate is forwarded to substrate"
    (let [result (nav-edges/outbound-edges
                   {:entity :dt/Property :predicate :dt/subclass-of})
          preds  (set (map :predicate (:edges result)))]
      (is (or (empty? preds) (= #{:dt/subclass-of} preds))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sandbar.navigate.edges/inbound-edges (opts-shaped wrapper)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest inbound-edges-wrapper-shape
  (testing "inbound-edges returns {:edges :total :returned} envelope"
    (let [result (nav-edges/inbound-edges {:entity :dt/Resource})]
      (is (vector? (:edges result)))
      (is (pos-int? (:total result)))
      (is (integer? (:returned result)))
      (doseq [e (:edges result)]
        (is (contains? e :predicate))
        (is (contains? e :source))))))

(deftest inbound-edges-wrapper-predicate-filter
  (testing ":predicate is forwarded to substrate"
    (let [result (nav-edges/inbound-edges
                   {:entity :dt/Resource :predicate :dt/subclass-of})
          preds  (set (map :predicate (:edges result)))]
      (is (= #{:dt/subclass-of} preds)
          "all inbound edges with :dt/subclass-of filter have that predicate"))))

(deftest inbound-edges-wrapper-respects-limit
  (testing ":limit caps :returned but :total reflects full edge count"
    (let [full   (nav-edges/inbound-edges {:entity :dt/Resource})
          capped (nav-edges/inbound-edges {:entity :dt/Resource :limit 1})]
      (is (= (:total full) (:total capped)))
      (is (<= (:returned capped) 1))
      (when (pos? (:total full))
        (is (= 1 (:returned capped)))))))

(deftest inbound-edges-wrapper-requires-entity
  (testing ":entity is required (precondition)"
    (is (thrown? AssertionError (nav-edges/inbound-edges {})))))

(deftest outbound-edges-wrapper-requires-entity
  (testing ":entity is required (precondition)"
    (is (thrown? AssertionError (nav-edges/outbound-edges {})))))
