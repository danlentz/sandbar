(ns sandbar.navigate.path.datomic-test
  "Tests for sandbar.navigate.path.datomic — Stage P-3 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Two test classes:
    1. Structural tests — verify compile output shape per operator;
       check :where + :rules vec composition.  Pure tests (no DB).
    2. End-to-end tests — compile + run against the metamodel fixture
       (every :dt/Class has :dt/subclass-of edges to ancestors) +
       verify reachability matches expected ancestor set."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.navigate.path.ast :as ast]
            [sandbar.navigate.path.datomic :as compiler]
            [sandbar.navigate.path.ir :as ir]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "navigate-path-datomic-test"}))

(defn- compile-expr
  "Convenience: parse → canonicalize → compile."
  [expr]
  (-> expr ast/parse ir/canonicalize (compiler/compile '?start '?end)))

(defn- run-path
  "End-to-end: compile + run query starting from seed-ident; return
   the set of reachable :db/ident keywords (filtering nil-ident
   anonymous entities)."
  [expr seed-ident]
  (let [seed-eid (:db/id (db/entity seed-ident))
        {:keys [where rules]} (compile-expr expr)
        q (vec (concat '[:find [?end ...] :in $ % ?start :where] where))
        eids (d/q q (db/db) rules seed-eid)]
    (set (keep (fn [eid] (:db/ident (db/entity eid))) eids))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Structural tests — per-operator compile output shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest compile-atomic-predicate
  (testing "atomic predicate compiles to a single attribute clause"
    (let [{:keys [where rules]} (compile-expr :cites)]
      (is (= [['?start :cites '?end]] where))
      (is (= [] rules)))))

(deftest compile-seq-pair
  (testing "(:SEQ p q) chains via fresh intermediate"
    (let [{:keys [where rules]} (compile-expr [:SEQ :cites :evidences])]
      (is (= 2 (count where)))
      (is (= [] rules))
      ;; First clause: ?start :cites ?int-1
      (is (= '?start (first (first where))))
      (is (= :cites  (second (first where))))
      ;; Last clause: ?int-1 :evidences ?end
      (is (= :evidences (second (second where))))
      (is (= '?end (last (second where))))
      ;; Intermediate var of first = subject of second
      (is (= (last (first where)) (first (second where)))))))

(deftest compile-seq-triple
  (testing "(:SEQ p q r) chains via two fresh intermediates"
    (let [{:keys [where]} (compile-expr [:SEQ :a :b :c])]
      (is (= 3 (count where))))))

(deftest compile-or-pair
  (testing "(:OR p q) wraps in or-join with from/to vars"
    (let [{:keys [where rules]} (compile-expr [:OR :cites :evidences])]
      (is (= 1 (count where)))
      (is (= [] rules))
      (let [or-clause (first where)]
        (is (= 'or-join (first or-clause)))
        (is (= '[?start ?end] (second or-clause)))))))

(deftest compile-rep-plus
  (testing "(:REP+ p) emits 2-rule recursive definition"
    (let [{:keys [where rules]} (compile-expr [:REP+ :cites])]
      (is (= 1 (count where)) ":where invokes the rule")
      (is (= 2 (count rules)) "base + recursive case")
      ;; Head clause uses ?start ?end (via the rule invocation)
      (let [head-call (first where)]
        (is (= '?start (second head-call)))
        (is (= '?end (last head-call)))))))

(deftest compile-rep-star
  (testing "(:REP* p) emits identity-base + recursive 2-rule definition"
    (let [{:keys [rules]} (compile-expr [:REP* :cites])]
      (is (= 2 (count rules))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UR-11 (Phase U Stage U-6): nested REP compositions must NOT emit
;; structurally-duplicate recursive-rule definitions.  `rule-name` is
;; content-derived; same child AST → same rule symbol AND same body.
;; Without dedupe, multiple sub-compile passes emit identical rules and
;; Datomic rejects the query at execution time with a duplicate-rule
;; error.
;;
;; Note: two rules with the SAME HEAD but DIFFERENT bodies is the
;; normal recursive-rule shape (base case + recursive case both define
;; the head as UNION).  The defect is specifically structurally-
;; identical rules — same head AND same body — repeated.
;;
;; The IR canonicalizer's `collapse-rep` reduces direct
;; `(:REP+ (:REP+ p))` to `(:REP+ p)`, so the worst-case duplicate path
;; is composite children with repeated subterms — e.g.,
;; `(:OR (:REP+ a) (:REP+ a))` (a manual duplicate that survives IR),
;; or `(:SEQ (:REP+ a) (:REP+ a))`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest compile-rep-plus-of-rep-plus-no-duplicate-rules
  (testing "(:REP+ (:REP+ p)) compiles without structurally-identical
            rule emissions even if the IR doesn't collapse the outer wrap"
    (let [{:keys [rules]} (compile-expr [:REP+ [:REP+ :cites]])]
      (is (= (count rules) (count (distinct rules)))
          (str "structurally-duplicate rules emitted: " (vec rules))))))

(deftest compile-rep-star-of-rep-plus-no-duplicate-rules
  (testing "(:REP* (:REP+ p)) compiles without structurally-duplicate rules"
    (let [{:keys [rules]} (compile-expr [:REP* [:REP+ :cites]])]
      (is (= (count rules) (count (distinct rules)))
          (str "structurally-duplicate rules emitted: " (vec rules))))))

(deftest compile-or-of-rep-plus-twice-no-duplicate-rules
  (testing "(:OR (:REP+ p) (:REP+ p)) — both branches emit the same
            recursive rule; the OR-compile dedupes structurally"
    (let [{:keys [rules]} (compile-expr [:OR [:REP+ :cites] [:REP+ :cites]])]
      (is (= (count rules) (count (distinct rules)))
          (str "structurally-duplicate rules emitted: " (vec rules))))))

(deftest compile-or-of-different-rep-plus-keeps-distinct-rules
  (testing "(:OR (:REP+ p) (:REP+ q)) — distinct branches produce
            distinct rules; dedupe must NOT collapse them"
    (let [{:keys [rules]} (compile-expr [:OR [:REP+ :cites] [:REP+ :slots]])]
      (is (= (count rules) (count (distinct rules)))
          (str "structurally-duplicate rules emitted: " (vec rules)))
      ;; 2 rules per branch (base + recursive) × 2 distinct branches = 4
      (is (= 4 (count rules))
          "two branches with distinct predicates = 4 distinct rules"))))

(deftest compile-seq-of-rep-plus-twice-no-duplicate-rules
  (testing "(:SEQ (:REP+ p) (:REP+ p)) — same recursive rule reached
            through two chain steps; sub-compilers dedupe via the
            SEQ propagation"
    (let [{:keys [rules]} (compile-expr [:SEQ [:REP+ :cites] [:REP+ :cites]])]
      (is (= (count rules) (count (distinct rules)))
          (str "structurally-duplicate rules emitted: " (vec rules))))))

(deftest compile-inv-of-predicate
  (testing "(:INV p) swaps from/to vars — compiles as inverse pattern"
    (let [{:keys [where]} (compile-expr [:INV :cites])]
      ;; Compiles child :cites with from=?end to=?start (swap)
      (is (= [['?end :cites '?start]] where)))))

(deftest compile-inv-canonicalized-through-seq
  (testing "(:INV (:SEQ a b)) canonicalizes to (:SEQ (:INV b) (:INV a))
            then compiles"
    (let [{:keys [where]} (compile-expr [:INV [:SEQ :a :b]])]
      ;; After IR: (:SEQ (:INV :b) (:INV :a))
      ;; Compile: chain through intermediate; each :INV swaps its var pair
      ;; First step: (:INV :b) from ?start to ?int — compile :b from ?int to ?start
      ;; Second step: (:INV :a) from ?int to ?end — compile :a from ?end to ?int
      (is (= 2 (count where))))))

(deftest compile-self
  (testing ":SELF emits identity-binding clause"
    (let [{:keys [where]} (compile-expr :SELF)]
      (is (= 1 (count where)))
      ;; [(identity ?start) ?end]
      (is (= '?end (last (first where))))
      (is (= 'identity (first (first (first where))))))))

(deftest compile-any
  (testing ":ANY emits variable in predicate position"
    (let [{:keys [where]} (compile-expr :ANY)]
      (is (= 1 (count where)))
      (let [clause (first where)]
        (is (= '?start (first clause)))
        (is (= '?end (last clause)))
        ;; Middle is a fresh ?int- var
        (is (symbol? (second clause)))))))

(deftest compile-restrict
  (testing "(:RESTRICT [pred value]) emits constraint at from-var + binds to-var=from-var"
    (let [{:keys [where]} (compile-expr [:RESTRICT [:dt/type :dt/Class]])]
      ;; Two clauses now: the constraint at ?start (from-var) + identity-bind to ?end
      (is (= 2 (count where)))
      ;; First clause: [?start :dt/type :dt/Class]  (constraint at current node)
      (is (= ['?start :dt/type :dt/Class] (first where)))
      ;; Second clause: [(identity ?start) ?end]  (bind ?end = ?start; filter doesn't move)
      (is (= '?end (last (second where)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; End-to-end tests against the metamodel fixture
;;
;; The test-db has dt/Class instances with :dt/subclass-of edges.
;; :dt/Class inherits from :dt/Resource which is the root.
;; Property is a class too.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest e2e-atomic-predicate
  (testing "atomic predicate :dt/subclass-of finds direct parents"
    (let [reachable (run-path :dt/subclass-of :dt/Property)]
      ;; :dt/Property's direct :dt/subclass-of target should include :dt/Resource
      (is (contains? reachable :dt/Resource)))))

(deftest e2e-rep-plus-transitive-closure
  (testing "(:REP+ :dt/subclass-of) finds all ancestors transitively"
    (let [reachable (run-path [:REP+ :dt/subclass-of] :dt/Property)]
      (is (contains? reachable :dt/Resource)
          ":dt/Resource is reachable via 1+ :dt/subclass-of steps"))))

(deftest e2e-rep-star-includes-self
  (testing "(:REP* p) includes the seed itself (0 applications)"
    (let [reachable (run-path [:REP* :dt/subclass-of] :dt/Property)]
      (is (contains? reachable :dt/Property)
          "REP* includes the seed via 0 applications")
      (is (contains? reachable :dt/Resource)
          "REP* also reaches further via 1+ applications"))))

(deftest e2e-or-alternation
  (testing "(:OR p q) finds entities reachable via either predicate"
    (let [via-subclass    (run-path :dt/subclass-of :dt/Property)
          via-slots       (run-path :dt/slots :dt/Property)
          via-either      (run-path [:OR :dt/subclass-of :dt/slots] :dt/Property)]
      ;; The :OR set should be the union of the individual sets
      (is (= (clojure.set/union via-subclass via-slots) via-either)))))

(deftest e2e-seq-composition
  (testing "(:SEQ :dt/slots :dt/range) walks class → its property slots → range types"
    ;; From a class, :dt/slots gives its property slots (1 hop).
    ;; Then :dt/range gives each property's range type (1 hop).
    ;; The 2-hop walk should yield range-type entities reachable through
    ;; the class's slot declarations.
    (let [reachable (run-path [:SEQ :dt/slots :dt/range] :dt/Class)]
      (is (set? reachable))
      (is (pos? (count reachable))
          ":dt/Class has slots, each with a declared range"))))

(deftest e2e-inv
  (testing "(:INV p) traverses edges backward"
    ;; :dt/Resource has many inbound :dt/subclass-of edges (from
    ;; subclasses).  (:INV :dt/subclass-of) from :dt/Resource finds
    ;; those subclasses.
    (let [reachable (run-path [:INV :dt/subclass-of] :dt/Resource)]
      (is (pos? (count reachable)))
      (is (contains? reachable :dt/Property)
          ":dt/Property is a direct subclass-of :dt/Resource"))))

(deftest e2e-restrict
  (testing "(:SEQ p (:RESTRICT [pred val])) filters result by attribute"
    ;; (:INV :dt/subclass-of) from :dt/Resource finds all subclasses.
    ;; Adding (:RESTRICT [:dt/type :dt/Class]) filters to only :dt/Class
    ;; instances — which all the subclasses already are.
    (let [unrestricted (run-path [:INV :dt/subclass-of] :dt/Resource)
          restricted   (run-path [:SEQ [:INV :dt/subclass-of]
                                       [:RESTRICT [:dt/type :dt/Class]]]
                                 :dt/Resource)]
      (is (= unrestricted restricted)
          "all subclasses of :dt/Resource are :dt/Class instances"))))

(deftest e2e-composition-rep-and-restrict
  (testing "(:SEQ (:REP+ :dt/subclass-of) (:RESTRICT [...]))
           composes transitive walk + filter"
    (let [reachable (run-path
                      [:SEQ [:REP+ :dt/subclass-of]
                            [:RESTRICT [:dt/type :dt/Class]]]
                      :dt/Property)]
      (is (contains? reachable :dt/Resource)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier-2 operators (Stage P-4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ---------- :NOT (negated property set) ----------

(deftest compile-not-structural
  (testing ":NOT emits predicate-var clause + ref-type restriction + not= constraints"
    (let [{:keys [where rules]} (compile-expr [:NOT :cites :evidences])]
      (is (= [] rules))
      ;; Base clause: [?start ?pred-var ?end]
      (is (= '?start (first (first where))))
      (is (= '?end (last (first where))))
      ;; Ref-type restriction at the predicate var
      (is (= :db/valueType (second (second where))))
      (is (= :db.type/ref  (last (second where))))
      ;; Two not= constraints
      (is (= 4 (count where)))
      (is (re-find #"not=" (str (nth where 2))))
      (is (re-find #"not=" (str (nth where 3)))))))

(deftest compile-not-rejects-compound-child
  (testing ":NOT requires atomic-predicate children (Wilbur/SPARQL semantics)"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)atomic predicates"
          (compile-expr [:NOT [:SEQ :a :b]])))))

(deftest e2e-not
  (testing "(:NOT :dt/subclass-of) from :dt/Property finds edges via OTHER predicates"
    (let [reachable (run-path [:NOT :dt/subclass-of] :dt/Property)]
      ;; :dt/Property has :dt/slots edges to its property entities;
      ;; those should be reachable via :NOT :dt/subclass-of (any
      ;; non-:dt/subclass-of edge).  Result is non-empty.
      (is (pos? (count reachable))))))

;; ---------- :OPT (zero-or-one) ----------

(deftest compile-opt-structural
  (testing ":OPT desugars to (:OR p :SELF) — compiles as or-join with SELF branch"
    (let [{:keys [where]} (compile-expr [:OPT :cites])]
      (is (= 1 (count where)))
      (is (= 'or-join (first (first where)))))))

(deftest e2e-opt
  (testing "(:OPT :dt/subclass-of) from :dt/Property includes :dt/Property itself + its parent"
    (let [reachable (run-path [:OPT :dt/subclass-of] :dt/Property)]
      (is (contains? reachable :dt/Property)
          ":OPT includes the seed (zero applications)")
      (is (contains? reachable :dt/Resource)
          ":OPT also includes one-application target"))))

;; ---------- (:REP p min max) bounded repetition ----------

(deftest compile-rep-bounded-structural
  (testing "(:REP p 1 3) unfolds to (:OR p (:SEQ p p) (:SEQ p p p)) — 3-branch or-join"
    (let [{:keys [where]} (compile-expr [:REP :cites 1 3])]
      (is (= 1 (count where)))
      (is (= 'or-join (first (first where)))))))

(deftest compile-rep-bounded-degenerate-min-eq-max-1
  (testing "(:REP p 1 1) — single alternative = just the child"
    (let [{:keys [where]} (compile-expr [:REP :cites 1 1])]
      (is (= [['?start :cites '?end]] where)))))

(deftest compile-rep-bounded-includes-zero-app
  (testing "(:REP p 0 2) includes :SELF as the zero-application alternative"
    (let [{:keys [where]} (compile-expr [:REP :cites 0 2])]
      (is (= 1 (count where)))
      ;; Or-join clause; SELF appears as identity-bind in one branch
      (is (re-find #"identity" (str (first where)))))))

(deftest e2e-rep-bounded
  (testing "(:REP :dt/subclass-of 1 2) finds entities 1-or-2 hops via :dt/subclass-of"
    (let [reachable (run-path [:REP :dt/subclass-of 1 2] :dt/Property)]
      (is (contains? reachable :dt/Resource)
          ":dt/Resource reachable in 1 hop"))))

;; ---------- :FILTER (URI-substring on :db/ident) ----------

(deftest compile-filter-structural
  (testing ":FILTER emits child clauses + :db/ident lookup + str bind + includes? predicate"
    (let [{:keys [where]} (compile-expr [:FILTER :dt/subclass-of "dt"])]
      ;; 1 child + ident lookup + str-bind + includes? predicate = 4 clauses
      (is (= 4 (count where)))
      (is (re-find #"clojure.string/includes\?"
                   (str (nth where 3)))))))

(deftest e2e-filter
  (testing "(:FILTER (:INV :dt/subclass-of) \"dt\") finds subclasses-of :dt/Resource
            whose idents contain 'dt'"
    (let [all-subs       (run-path [:INV :dt/subclass-of] :dt/Resource)
          dt-prefixed    (run-path [:FILTER [:INV :dt/subclass-of] "dt"]
                                   :dt/Resource)]
      (is (every? (fn [k] (re-find #"^:dt" (str k))) dt-prefixed)
          "all results have idents starting with :dt")
      (is (<= (count dt-prefixed) (count all-subs))
          "filter reduces or preserves result count"))))

;; ---------- :TEST (functional predicate via registry) ----------

(deftest test-fn-registry-has-defaults
  (testing "default registry includes common predicates"
    (is (contains? @compiler/test-fn-registry :pos?))
    (is (contains? @compiler/test-fn-registry :keyword?))))

(deftest compile-test-rejects-unregistered-fn-name
  (testing "unknown fn-name in :TEST raises descriptive error"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)not registered"
          (compile-expr [:TEST :cites :no-such-fn])))))

(deftest compile-test-structural
  (testing ":TEST emits child clauses + (registered-fn ?to) predicate"
    (let [{:keys [where]} (compile-expr [:TEST :dt/subclass-of :keyword?])]
      ;; 1 child clause + 1 predicate clause
      (is (= 2 (count where)))
      (is (re-find #"keyword\?" (str (second where)))))))

(deftest register-test-fn-rejects-bad-args
  (testing "register-test-fn! validates fn-name keyword + fully-qualified symbol"
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/register-test-fn! "not-a-keyword" 'foo/bar)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (compiler/register-test-fn! :ok-keyword 'bare-symbol)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier-3 operators still reject (vocabulary registered; compilation deferred)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tier-3-value-not-supported
  (testing ":VALUE (Tier-3) raises descriptive ex-info"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)not yet supported"
          (compile-expr [:VALUE "constant"])))))

(deftest tier-3-lang-not-supported
  (testing ":LANG (Tier-3) raises"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"(?i)not yet supported"
          (compile-expr [:LANG :dt/subclass-of "en"])))))
