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
  (:require [clojure.set :as set]
            [clojure.test :refer :all]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Gap 7 — bare predicate resolution (silent-zero-hit fix)
;;
;; The dt/* edge primitives compare against slot-idents (`:dt/subclass-of`).
;; Bare predicate forms (`:subclass-of`) should resolve to the matching
;; slot on the entity's class.  Namespaced keywords pass through.
;; Unresolvable bare predicates throw ex-info with a helpful hint.
;;
;; Per MCP cutover exercise 2026-05-22 — inbox capture
;; memory/inbox/2026-05-22_mcp_cutover_exercise_substrate_verb_authoring_queue_10_gaps_surfaced_via_orientation_of_sandbar_as_mcp_server_arc.md.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest outbound-edges-wrapper-resolves-bare-predicate
  (testing "bare predicate :subclass-of resolves to :dt/subclass-of on :dt/Class instances"
    (let [result (nav-edges/outbound-edges
                   {:entity :dt/Property :predicate :subclass-of})
          preds  (set (map :predicate (:edges result)))]
      (is (= #{:dt/subclass-of} preds)
          "bare predicate resolved to slot-ident; filter applied correctly"))))

(deftest inbound-edges-wrapper-resolves-bare-predicate
  (testing "bare predicate :subclass-of resolves to :dt/subclass-of on :dt/Class instances"
    (let [result (nav-edges/inbound-edges
                   {:entity :dt/Resource :predicate :subclass-of})
          preds  (set (map :predicate (:edges result)))]
      (is (= #{:dt/subclass-of} preds)
          "bare predicate resolved; inbound filter applied correctly"))))

(deftest outbound-edges-wrapper-bare-predicate-vec
  (testing "vec of bare predicates resolves each independently"
    (let [result (nav-edges/outbound-edges
                   {:entity :dt/Property :predicate [:subclass-of :slots]})
          preds  (set (map :predicate (:edges result)))]
      (is (every? #{:dt/subclass-of :dt/slots} preds)
          "every returned predicate is in the resolved set")
      (is (contains? preds :dt/subclass-of))
      (is (contains? preds :dt/slots)))))

(deftest outbound-edges-wrapper-namespaced-predicate-passes-through
  (testing "namespaced predicates pass through resolution unchanged"
    (let [bare   (nav-edges/outbound-edges
                   {:entity :dt/Property :predicate :subclass-of})
          quali  (nav-edges/outbound-edges
                   {:entity :dt/Property :predicate :dt/subclass-of})]
      (is (= (set (map :predicate (:edges bare)))
             (set (map :predicate (:edges quali))))
          "bare-form and qualified-form produce equivalent edge sets")
      (is (= (:total bare) (:total quali))
          "totals match"))))

(deftest outbound-edges-wrapper-unresolvable-bare-throws
  (testing "bare predicate with no matching slot throws ex-info with hint"
    (let [thrown (try
                   (nav-edges/outbound-edges
                     {:entity :dt/Property :predicate :no-such-predicate})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))
          data   (ex-data thrown)]
      (is (some? thrown) "throws on unresolvable bare predicate")
      (is (= :no-match (:resolution data)))
      (is (= :no-such-predicate (:bare-predicate data)))
      (is (vector? (:available-slots data))
          "error data includes available slots for hint")
      (is (clojure.string/includes?
            (.getMessage ^Exception thrown)
            ":no-such-predicate")
          "error message names the offending predicate"))))

(deftest inbound-edges-wrapper-unresolvable-bare-throws
  (testing "inbound-edges also rejects unresolvable bare predicates"
    (let [thrown (try
                   (nav-edges/inbound-edges
                     {:entity :dt/Resource :predicate :no-such-predicate})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown))
      (is (= :no-match (:resolution (ex-data thrown)))))))

(deftest outbound-edges-wrapper-no-predicate-still-works
  (testing "nil predicate (no filter) still works — resolution is opt-in"
    (let [result (nav-edges/outbound-edges {:entity :dt/Property})]
      (is (pos? (:total result))
          "no-predicate path returns the full edge set unchanged"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Gap 3 — :projection opt (metadata-only vs full)
;;
;; Default is :metadata-only — restricts target/source to substrate-universal
;; metadata (:db/id, :db/ident, :dt/type).  :full returns the complete
;; entity-map.  Addresses the 10-300x response-size friction surfaced in
;; the MCP cutover exercise.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest outbound-edges-projection-metadata-only-default
  (testing "default projection :metadata-only restricts target to db/id+ident+dt/type"
    (let [result (nav-edges/outbound-edges {:entity :dt/Property :limit 1})
          edge   (first (:edges result))
          target (:target edge)]
      (is (some? target) "edge still has a :target")
      (is (set/subset? (set (keys target)) #{:db/id :db/ident :dt/type})
          "target keys restricted to substrate-universal metadata only")
      (is (contains? target :db/id) "metadata-only includes :db/id"))))

(deftest outbound-edges-projection-full-returns-complete-target
  (testing ":projection :full returns the complete target entity-map"
    (let [metadata (nav-edges/outbound-edges
                     {:entity :dt/Property :limit 1 :projection :metadata-only})
          full     (nav-edges/outbound-edges
                     {:entity :dt/Property :limit 1 :projection :full})
          mt-keys  (set (keys (:target (first (:edges metadata)))))
          ft-keys  (set (keys (:target (first (:edges full)))))]
      (is (set/subset? mt-keys ft-keys)
          "metadata-only keys are a subset of full keys")
      (is (> (count ft-keys) (count mt-keys))
          "full target carries more slots than metadata-only"))))

(deftest inbound-edges-projection-metadata-only-default
  (testing "default :metadata-only restricts source on inbound edges too"
    (let [result (nav-edges/inbound-edges {:entity :dt/Resource :limit 1})
          edge   (first (:edges result))
          source (:source edge)]
      (is (some? source))
      (is (set/subset? (set (keys source)) #{:db/id :db/ident :dt/type})))))

(deftest projection-unknown-mode-throws
  (testing "unknown :projection mode fails loud rather than silently misshaping result"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #":projection"
          (nav-edges/outbound-edges
            {:entity :dt/Property :projection :bogus-mode})))))

(deftest projection-preserves-predicate-and-counts
  (testing ":projection only affects target/source shape; :predicate + counts unchanged"
    (let [metadata (nav-edges/outbound-edges {:entity :dt/Property})
          full     (nav-edges/outbound-edges {:entity :dt/Property :projection :full})]
      (is (= (:total metadata) (:total full))
          ":total is projection-independent")
      (is (= (:returned metadata) (:returned full))
          ":returned is projection-independent")
      (is (= (map :predicate (:edges metadata))
             (map :predicate (:edges full)))
          ":predicate sequence is projection-independent"))))
