(ns sandbar.navigate.path.ast-test
  "Tests for sandbar.navigate.path.ast — Stage P-1 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Covers:
    1. Operator vocabulary registry shape + tier classification
    2. Parser correctness across atomic / nullary / n-ary / Tier-2 forms
    3. Arity validation + unknown-operator error paths
    4. Per-arg-shape parse semantics (target-spec / rep-bounded /
       path+substring / path+fn-name / path+string / constant-value)
    5. Round-trip parse/unparse equivalence
    6. Tier introspection helpers"
  (:require [clojure.test :refer :all]
            [sandbar.navigate.path.ast :as ast]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Operator vocabulary registry
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest vocabulary-has-20-named-operators
  (testing "vocabulary registers 20 named operators (+ implicit atomic-predicate = 21 conceptual surface per synthesis §1.2)"
    ;; 8 Tier-1 + 5 Tier-2 + 7 Tier-3 = 20 NAMED ops.
    ;; Atomic <predicate> is the parser's catch-all (any keyword not in
    ;; this registry parses as :PREDICATE), so it is NOT a registry
    ;; entry — bringing the conceptual surface to 21.
    (is (= 20 (count ast/operator-vocabulary)))))

(deftest vocabulary-canonical-8-present
  (testing "Tier-1 Canonical-8 operators present"
    (let [tier-1 (set (ast/operators-by-tier :tier-1))]
      (is (= #{:SEQ :OR :REP+ :REP* :INV :SELF :ANY :RESTRICT} tier-1)))))

(deftest vocabulary-tier-2-present
  (testing "Tier-2 operators present"
    (let [tier-2 (set (ast/operators-by-tier :tier-2))]
      (is (= #{:NOT :OPT :REP :FILTER :TEST} tier-2)))))

(deftest vocabulary-tier-3-present
  (testing "Tier-3 operators present (vocabulary registered; compilation deferred)"
    (let [tier-3 (set (ast/operators-by-tier :tier-3))]
      (is (= #{:LANG :VALUE :DAEMON :NOREWRITE :MEMBERS
               :PREDICATE-OF-SUBJECT :PREDICATE-OF-OBJECT}
             tier-3)))))

(deftest vocabulary-entries-shaped
  (testing "every operator entry carries the required keys"
    (doseq [[op spec] ast/operator-vocabulary]
      (is (contains? spec :arity)            (str op " missing :arity"))
      (is (contains? spec :tier)             (str op " missing :tier"))
      (is (contains? spec :semantics-class)  (str op " missing :semantics-class"))
      (is (contains? spec :arg-shape)        (str op " missing :arg-shape"))
      (is (string? (:doc spec))              (str op " missing :doc")))))

(deftest registered-operator-discriminator
  (testing "registered-operator? recognizes uppercase combinators"
    (is (ast/registered-operator? :SEQ))
    (is (ast/registered-operator? :REP+))
    (is (ast/registered-operator? :SELF)))
  (testing "registered-operator? rejects lowercase predicates"
    (is (not (ast/registered-operator? :cites)))
    (is (not (ast/registered-operator? :evidences)))
    (is (not (ast/registered-operator? :mm.memory/parent)))))

(deftest atomic-predicate-discriminator
  (testing "atomic-predicate? recognizes lowercase keywords not in registry"
    (is (ast/atomic-predicate? :cites))
    (is (ast/atomic-predicate? :mm.memory/parent))
    (is (not (ast/atomic-predicate? :SEQ)))
    (is (not (ast/atomic-predicate? "string")))
    (is (not (ast/atomic-predicate? 42)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — atomic predicate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-atomic-predicate
  (testing "bare lowercase keyword becomes :PREDICATE AST node"
    (is (= {:op :PREDICATE :predicate :cites}
           (ast/parse :cites)))
    (is (= {:op :PREDICATE :predicate :mm.memory/parent}
           (ast/parse :mm.memory/parent)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — nullary operators (Tier-1 + Tier-3)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-nullary-self
  (is (= {:op :SELF} (ast/parse :SELF))))

(deftest parse-nullary-any
  (is (= {:op :ANY} (ast/parse :ANY))))

(deftest parse-nullary-members
  (is (= {:op :MEMBERS} (ast/parse :MEMBERS))))

(deftest parse-nullary-predicate-of-subject
  (is (= {:op :PREDICATE-OF-SUBJECT}
         (ast/parse :PREDICATE-OF-SUBJECT))))

(deftest parse-bare-nonnullary-operator-rejected
  (testing "non-nullary operator as bare keyword raises error"
    (is (thrown? clojure.lang.ExceptionInfo (ast/parse :SEQ)))
    (is (thrown? clojure.lang.ExceptionInfo (ast/parse :REP+)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — :path-children operators (most common shape)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-seq-of-predicates
  (is (= {:op :SEQ
          :args [{:op :PREDICATE :predicate :cites}
                 {:op :PREDICATE :predicate :evidences}]}
         (ast/parse [:SEQ :cites :evidences]))))

(deftest parse-or-of-predicates
  (is (= {:op :OR
          :args [{:op :PREDICATE :predicate :cites}
                 {:op :PREDICATE :predicate :evidences}]}
         (ast/parse [:OR :cites :evidences]))))

(deftest parse-rep+
  (is (= {:op :REP+
          :args [{:op :PREDICATE :predicate :cites}]}
         (ast/parse [:REP+ :cites]))))

(deftest parse-rep-star
  (is (= {:op :REP*
          :args [{:op :PREDICATE :predicate :cites}]}
         (ast/parse [:REP* :cites]))))

(deftest parse-inv
  (is (= {:op :INV
          :args [{:op :PREDICATE :predicate :cites}]}
         (ast/parse [:INV :cites]))))

(deftest parse-nested
  (testing "deeply-nested operator forms parse correctly"
    (is (= {:op :SEQ
            :args [{:op :REP*
                    :args [{:op :OR
                            :args [{:op :PREDICATE :predicate :cites}
                                   {:op :PREDICATE :predicate :evidences}]}]}
                   {:op :PREDICATE :predicate :tested-by}]}
           (ast/parse [:SEQ [:REP* [:OR :cites :evidences]] :tested-by])))))

(deftest parse-not-variadic
  (testing ":NOT accepts variadic-1+ args (SPARQL !p and !(p1|p2) parity)"
    (is (= {:op :NOT
            :args [{:op :PREDICATE :predicate :cites}]}
           (ast/parse [:NOT :cites])))
    (is (= {:op :NOT
            :args [{:op :PREDICATE :predicate :cites}
                   {:op :PREDICATE :predicate :evidences}]}
           (ast/parse [:NOT :cites :evidences])))))

(deftest parse-opt
  (is (= {:op :OPT
          :args [{:op :PREDICATE :predicate :cites}]}
         (ast/parse [:OPT :cites]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — :target-spec (:RESTRICT)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-restrict
  (testing ":RESTRICT takes a [predicate value] 2-tuple"
    (is (= {:op :RESTRICT :target [:type :decision]}
           (ast/parse [:RESTRICT [:type :decision]])))))

(deftest parse-restrict-bad-target
  (testing ":RESTRICT rejects non-2-tuple target"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:RESTRICT :type])))
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:RESTRICT [:type :decision :extra]])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — :rep-bounded (:REP)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-rep-bounded
  (testing ":REP takes [path min max] with 0 ≤ min ≤ max"
    (is (= {:op :REP
            :child {:op :PREDICATE :predicate :cites}
            :min 1 :max 3}
           (ast/parse [:REP :cites 1 3])))))

(deftest parse-rep-bounded-validates-bounds
  (testing ":REP rejects negative min"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:REP :cites -1 3]))))
  (testing ":REP rejects min > max"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:REP :cites 5 3]))))
  (testing ":REP rejects non-integer bounds"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:REP :cites "1" 3])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — :path+substring (:FILTER), :path+fn-name (:TEST / :DAEMON),
;; :path+string (:LANG), :constant-value (:VALUE)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-filter
  (is (= {:op :FILTER
          :child {:op :PREDICATE :predicate :cites}
          :substring "decisions/"}
         (ast/parse [:FILTER :cites "decisions/"]))))

(deftest parse-filter-rejects-non-string
  (is (thrown? clojure.lang.ExceptionInfo
               (ast/parse [:FILTER :cites :not-a-string]))))

(deftest parse-test
  (is (= {:op :TEST
          :child {:op :PREDICATE :predicate :cites}
          :fn-name :my-predicate}
         (ast/parse [:TEST :cites :my-predicate]))))

(deftest parse-test-rejects-non-keyword
  (is (thrown? clojure.lang.ExceptionInfo
               (ast/parse [:TEST :cites "not-a-keyword"]))))

(deftest parse-lang
  (is (= {:op :LANG
          :child {:op :PREDICATE :predicate :label}
          :lang-tag "en"}
         (ast/parse [:LANG :label "en"]))))

(deftest parse-value
  (is (= {:op :VALUE :value "constant-string"}
         (ast/parse [:VALUE "constant-string"])))
  (is (= {:op :VALUE :value 42}
         (ast/parse [:VALUE 42]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — arity violations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-arity-violation-empty-seq
  (testing ":SEQ requires at least one argument"
    (is (thrown? clojure.lang.ExceptionInfo (ast/parse [:SEQ])))))

(deftest parse-arity-violation-rep-too-many
  (testing ":REP+ is unary; extra args rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:REP+ :cites :evidences])))))

(deftest parse-arity-violation-self-with-args
  (testing ":SELF is nullary; arg-form rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:SELF :cites])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — unknown operators + invalid forms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-unknown-operator-in-vector
  (testing "unknown uppercase operator in vector form is rejected"
    (is (thrown? clojure.lang.ExceptionInfo
                 (ast/parse [:UNKNOWN-OP :cites])))))

(deftest parse-invalid-form-rejected
  (testing "non-keyword non-vector input is rejected"
    (is (thrown? clojure.lang.ExceptionInfo (ast/parse "string")))
    (is (thrown? clojure.lang.ExceptionInfo (ast/parse 42)))
    (is (thrown? clojure.lang.ExceptionInfo (ast/parse {:not-a :path})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip parse / unparse
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest round-trip-atomic
  (is (= :cites (ast/unparse (ast/parse :cites)))))

(deftest round-trip-nullary
  (is (= :SELF (ast/unparse (ast/parse :SELF))))
  (is (= :ANY  (ast/unparse (ast/parse :ANY)))))

(deftest round-trip-seq
  (is (= [:SEQ :cites :evidences]
         (ast/unparse (ast/parse [:SEQ :cites :evidences])))))

(deftest round-trip-nested
  (let [expr [:SEQ [:REP* [:OR :cites :evidences]] :tested-by]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-restrict
  (let [expr [:RESTRICT [:type :decision]]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-rep-bounded
  (let [expr [:REP :cites 1 3]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-filter
  (let [expr [:FILTER :cites "decisions/"]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-test
  (let [expr [:TEST :cites :my-predicate]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-lang
  (let [expr [:LANG :label "en"]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-value
  (let [expr [:VALUE "constant"]]
    (is (= expr (ast/unparse (ast/parse expr))))))

(deftest round-trip-not-variadic
  (let [expr [:NOT :cites :evidences]]
    (is (= expr (ast/unparse (ast/parse expr))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Introspection helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tier-of-lookup
  (is (= :tier-1 (ast/tier-of :SEQ)))
  (is (= :tier-1 (ast/tier-of :REP+)))
  (is (= :tier-2 (ast/tier-of :OPT)))
  (is (= :tier-2 (ast/tier-of :NOT)))
  (is (= :tier-3 (ast/tier-of :LANG)))
  (is (nil? (ast/tier-of :cites))
      "atomic predicates have no tier"))

(deftest arity-of-lookup
  (is (= :variadic-1+ (ast/arity-of :SEQ)))
  (is (= 1 (ast/arity-of :REP+)))
  (is (= :nullary (ast/arity-of :SELF)))
  (is (= 3 (ast/arity-of :REP))))
