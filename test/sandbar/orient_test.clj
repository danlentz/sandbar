(ns sandbar.orient-test
  "Tests for sandbar.orient — Phase O of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Scope-narrowed to library-card-only per
  decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md.

  Tests use the metamodel fixture's :dt/Class entities for axis-spec
  coverage (every class has :dt/subclass-of inbound from subclasses
  + :dt/slots outbound to property entities)."
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.orient :as orient]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "orient-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; dt/library-card-of substrate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest library-card-of-single-axis
  (testing "single-axis library-card returns labeled result"
    (let [{:keys [entity axes]}
          (dt/library-card-of :dt/Property
                              [{:name      "slots"
                                :direction :forward
                                :predicates [:dt/slots]}])]
      (is (some? entity))
      (is (contains? axes "slots")))))

(deftest library-card-of-multi-axis
  (testing "multi-axis library-card returns all labeled axes"
    (let [{:keys [axes]}
          (dt/library-card-of :dt/Resource
                              [{:name      "subclasses"
                                :direction :inverse
                                :predicates [:dt/subclass-of]}
                               {:name      "slots"
                                :direction :forward
                                :predicates [:dt/slots]}])]
      (is (contains? axes "subclasses"))
      (is (contains? axes "slots")))))

(deftest library-card-of-respects-limit
  (testing "axis :limit caps per-axis edge count"
    (let [{:keys [axes]}
          (dt/library-card-of :dt/Resource
                              [{:name      "subclasses"
                                :direction :inverse
                                :predicates [:dt/subclass-of]
                                :limit     2}])]
      (is (<= (count (get axes "subclasses")) 2)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sandbar.orient/library-card wrapper
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest library-card-wrapper-envelope
  (testing "wrapper returns {:entity :axes} envelope"
    (let [result (orient/library-card
                   {:entity :dt/Property
                    :axes   [{:name      "slots"
                              :direction :forward
                              :predicates [:dt/slots]}]})]
      (is (contains? result :entity))
      (is (contains? result :axes))
      (is (map? (:entity result)))
      (is (some? (:db/id (:entity result)))
          "projected entity-map includes :db/id"))))

(deftest library-card-wrapper-projects-edges
  (testing "each axis's edges have projected entity-maps for target/source"
    (let [result (orient/library-card
                   {:entity :dt/Resource
                    :axes   [{:name      "subclasses"
                              :direction :inverse
                              :predicates [:dt/subclass-of]}]})
          edges (get (:axes result) "subclasses")]
      (when (seq edges)
        (let [first-edge (first edges)]
          (is (contains? first-edge :predicate))
          (is (contains? first-edge :source))
          (is (map? (:source first-edge))))))))

(deftest library-card-wrapper-requires-entity
  (is (thrown? AssertionError
               (orient/library-card {:axes []}))))

(deftest library-card-wrapper-requires-axes
  (is (thrown? AssertionError
               (orient/library-card {:entity :dt/Resource}))))

(deftest library-card-wrapper-axes-must-be-sequential
  (is (thrown? AssertionError
               (orient/library-card {:entity :dt/Resource :axes "not-a-vec"}))))

(deftest library-card-wrapper-empty-axes-yields-empty-axes-map
  (testing "empty axes vec yields empty :axes map (no axis requested = nothing returned)"
    (let [result (orient/library-card {:entity :dt/Resource :axes []})]
      (is (= {} (:axes result)))
      (is (some? (:entity result))))))
