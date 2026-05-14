(ns sandbar.navigate.path.ir-test
  "Tests for sandbar.navigate.path.ir — Stage P-2 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Covers all four rewrite categories per §6 P-2 spec:
    1. Associative-flatten   — :SEQ + :OR
    2. Idempotent-collapse   — degenerate / dedupe-or / closure
    3. Inverse-double-eliminate — :INV simplification + push-inward
    4. Distributive-rewrite  — opt-in, sequence-over-union

  Plus canonicalize idempotence + composition + round-trip through
  ast/unparse.

  Test ASTs are built via ast/parse so they're readable as EDN."
  (:require [clojure.test :refer :all]
            [sandbar.navigate.path.ast :as ast]
            [sandbar.navigate.path.ir  :as ir]))

(defn- canon
  "Convenience: parse → canonicalize → unparse."
  [expr]
  (ast/unparse (ir/canonicalize (ast/parse expr))))

(defn- dist
  "Convenience: parse → canonicalize → distribute → unparse."
  [expr]
  (ast/unparse (ir/distribute (ir/canonicalize (ast/parse expr)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Associative-flatten
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest flatten-seq-binary-nesting
  (testing "(:SEQ p (:SEQ q r)) → (:SEQ p q r)"
    (is (= [:SEQ :a :b :c]
           (canon [:SEQ :a [:SEQ :b :c]])))))

(deftest flatten-seq-left-nested
  (testing "(:SEQ (:SEQ a b) c) → (:SEQ a b c)"
    (is (= [:SEQ :a :b :c]
           (canon [:SEQ [:SEQ :a :b] :c])))))

(deftest flatten-seq-deeply-nested
  (testing "(:SEQ a (:SEQ b (:SEQ c d))) → (:SEQ a b c d)"
    (is (= [:SEQ :a :b :c :d]
           (canon [:SEQ :a [:SEQ :b [:SEQ :c :d]]])))))

(deftest flatten-or-binary-nesting
  (testing "(:OR p (:OR q r)) → (:OR p q r)"
    (is (= [:OR :a :b :c]
           (canon [:OR :a [:OR :b :c]])))))

(deftest flatten-or-deeply-nested
  (testing "(:OR a (:OR b (:OR c d))) → (:OR a b c d)"
    (is (= [:OR :a :b :c :d]
           (canon [:OR :a [:OR :b [:OR :c :d]]])))))

(deftest no-flatten-across-different-ops
  (testing "(:SEQ p (:OR q r)) does NOT flatten — different ops"
    (is (= [:SEQ :a [:OR :b :c]]
           (canon [:SEQ :a [:OR :b :c]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Idempotent-collapse
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest collapse-degenerate-seq
  (testing "single-arg :SEQ collapses to its child"
    (is (= :cites (canon [:SEQ :cites])))))

(deftest collapse-degenerate-or
  (testing "single-arg :OR collapses to its child"
    (is (= :cites (canon [:OR :cites])))))

(deftest dedupe-or
  (testing "(:OR p p) → p (via dedupe + degenerate)"
    (is (= :cites (canon [:OR :cites :cites])))))

(deftest dedupe-or-preserves-distinct
  (testing "(:OR p q p) → (:OR p q)"
    (is (= [:OR :cites :evidences]
           (canon [:OR :cites :evidences :cites])))))

(deftest collapse-rep-star-of-star
  (testing "(:REP* (:REP* p)) → (:REP* p)"
    (is (= [:REP* :cites]
           (canon [:REP* [:REP* :cites]])))))

(deftest collapse-rep-star-of-plus
  (testing "(:REP* (:REP+ p)) → (:REP* p)"
    (is (= [:REP* :cites]
           (canon [:REP* [:REP+ :cites]])))))

(deftest collapse-rep-plus-of-plus
  (testing "(:REP+ (:REP+ p)) → (:REP+ p)"
    (is (= [:REP+ :cites]
           (canon [:REP+ [:REP+ :cites]])))))

(deftest collapse-rep-plus-of-star
  (testing "(:REP+ (:REP* p)) → (:REP* p)"
    (is (= [:REP* :cites]
           (canon [:REP+ [:REP* :cites]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Inverse-double-eliminate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest eliminate-double-inv
  (testing "(:INV (:INV p)) → p"
    (is (= :cites (canon [:INV [:INV :cites]])))))

(deftest inv-of-seq-reverses
  (testing "(:INV (:SEQ a b)) → (:SEQ (:INV b) (:INV a))"
    (is (= [:SEQ [:INV :b] [:INV :a]]
           (canon [:INV [:SEQ :a :b]])))))

(deftest inv-of-seq-three
  (testing "(:INV (:SEQ a b c)) → (:SEQ (:INV c) (:INV b) (:INV a))"
    (is (= [:SEQ [:INV :c] [:INV :b] [:INV :a]]
           (canon [:INV [:SEQ :a :b :c]])))))

(deftest inv-of-or-distributes
  (testing "(:INV (:OR a b)) → (:OR (:INV a) (:INV b))"
    (is (= [:OR [:INV :a] [:INV :b]]
           (canon [:INV [:OR :a :b]])))))

(deftest inv-of-self
  (testing "(:INV :SELF) → :SELF"
    (is (= :SELF (canon [:INV :SELF])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Composition — multiple rewrites apply together
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest combined-flatten-and-collapse
  (testing "(:SEQ (:SEQ a) (:SEQ b c)) → (:SEQ a b c)"
    (is (= [:SEQ :a :b :c]
           (canon [:SEQ [:SEQ :a] [:SEQ :b :c]])))))

(deftest combined-inv-and-flatten
  (testing "(:INV (:SEQ a (:SEQ b c))) flattens then inverts"
    (is (= [:SEQ [:INV :c] [:INV :b] [:INV :a]]
           (canon [:INV [:SEQ :a [:SEQ :b :c]]])))))

(deftest combined-deep-nested
  (testing "deeply-nested example canonicalizes fully"
    ;; (:SEQ (:REP* (:REP* :cites)) (:OR :evidences :evidences))
    ;;   →  (:SEQ (:REP* :cites) :evidences)
    (is (= [:SEQ [:REP* :cites] :evidences]
           (canon [:SEQ [:REP* [:REP* :cites]]
                        [:OR :evidences :evidences]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canonicalize properties
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest canonicalize-idempotent
  (testing "applying canonicalize twice equals applying once"
    (let [exprs [[:SEQ :a [:SEQ :b :c]]
                 [:OR :a [:OR :b :c]]
                 [:REP* [:REP* :a]]
                 [:INV [:INV :a]]
                 [:INV [:SEQ :a :b]]
                 [:SEQ [:SEQ [:SEQ :a :b] :c] [:OR :d :d]]
                 [:OR :cites :cites :evidences]
                 [:RESTRICT [:type :decision]]
                 [:REP :cites 1 3]
                 [:FILTER :cites "decisions/"]]]
      (doseq [expr exprs]
        (let [once  (ir/canonicalize (ast/parse expr))
              twice (ir/canonicalize once)]
          (is (= once twice)
              (str "non-idempotent: " expr)))))))

(deftest canonicalize-preserves-atomic
  (is (= :cites (canon :cites))))

(deftest canonicalize-preserves-nullary
  (is (= :SELF (canon :SELF)))
  (is (= :ANY (canon :ANY))))

(deftest canonicalize-preserves-restrict
  (testing ":RESTRICT target tuple is not a sub-expression — passes through"
    (is (= [:RESTRICT [:type :decision]]
           (canon [:RESTRICT [:type :decision]])))))

(deftest canonicalize-preserves-rep-bounded
  (is (= [:REP :cites 1 3]
         (canon [:REP :cites 1 3]))))

(deftest canonicalize-recurses-into-rep-bounded-child
  (testing ":REP child sub-expression is canonicalized"
    (is (= [:REP [:REP* :cites] 1 3]
           (canon [:REP [:REP* [:REP* :cites]] 1 3])))))

(deftest canonicalize-recurses-into-filter-child
  (is (= [:FILTER :cites "decisions/"]
         (canon [:FILTER [:SEQ :cites] "decisions/"]))))

(deftest canonicalize-preserves-tier-3-value
  (is (= [:VALUE "constant"]
         (canon [:VALUE "constant"]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Distribution (opt-in)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest distribute-seq-over-or-right
  (testing "(:SEQ p (:OR q r)) → (:OR (:SEQ p q) (:SEQ p r))"
    (is (= [:OR [:SEQ :a :b] [:SEQ :a :c]]
           (dist [:SEQ :a [:OR :b :c]])))))

(deftest distribute-seq-over-or-left
  (testing "(:SEQ (:OR p q) r) → (:OR (:SEQ p r) (:SEQ q r))"
    (is (= [:OR [:SEQ :a :c] [:SEQ :b :c]]
           (dist [:SEQ [:OR :a :b] :c])))))

(deftest distribute-no-or-no-op
  (testing "expressions without :OR inside :SEQ pass through unchanged"
    (is (= [:SEQ :a :b :c]
           (dist [:SEQ :a :b :c])))))

(deftest distribute-multiple-ors-iterates
  (testing "(:SEQ (:OR a b) (:OR c d)) → 4-branch union of pure sequences"
    (let [result (dist [:SEQ [:OR :a :b] [:OR :c :d]])]
      (is (= :OR (first result)))
      ;; 4 branches: [:SEQ a c] [:SEQ a d] [:SEQ b c] [:SEQ b d]
      (is (= 4 (count (rest result))))
      (is (every? #(= :SEQ (first %)) (rest result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip via ast/unparse
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest already-canonical-is-fixed-point
  (testing "expressions already in canonical form survive canonicalize unchanged"
    (let [canonical-exprs [:cites
                           :SELF
                           [:SEQ :a :b :c]
                           [:OR :a :b]
                           [:REP* :cites]
                           [:REP+ :cites]
                           [:INV :cites]
                           [:RESTRICT [:type :decision]]
                           [:REP :cites 1 3]
                           [:FILTER :cites "decisions/"]
                           [:TEST :cites :my-fn]]]
      (doseq [expr canonical-exprs]
        (is (= expr (canon expr))
            (str "canonical form mutated: " expr))))))
