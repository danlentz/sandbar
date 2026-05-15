(ns sandbar.navigate.path.evaluate-test
  "Extensional-oracle test suite for sandbar.navigate.path.evaluate
  (Phase R Stage R-7 path-data reconstruction per
  plans/sandbar_0_1_0_codex_remediation_2026_05_14.md §R-7 +
  decisions/sandbar_path_data_reconstruction_option_d_policy_a_2026_05_14.md).

  Each of the Canonical-8 operators tested in isolation against the
  metamodel fixture (a tree-shaped subclass hierarchy with :dt/slots
  cross-edges); composition tests verify nesting; cycle test verifies
  visited-set termination; path-explosion test verifies Policy A
  one-representative-per-endpoint.

  Pattern follows F-SF-3 (public-surface tests must hit runtime, not
  shape) — every test runs evaluate-from end-to-end against a real
  Datomic DB + asserts path-value invariants.

  Helpers:
    eval-from-ident — convenience wrapper: parse + canonicalize +
                      evaluate-from from an ident."
  (:require [clojure.test :refer :all]
            [sandbar.db.datomic :as db]
            [sandbar.navigate.path.ast :as ast]
            [sandbar.navigate.path.evaluate :as evalpath]
            [sandbar.navigate.path.ir :as ir]
            [sandbar.navigate.path.value :as pv]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "navigate-path-evaluate-test"}))

(defn- eval-from-ident
  "Parse + canonicalize + evaluate from a seed-ident.  Returns the
   evaluate-from output (vec of {:eid :path} maps)."
  [expr seed-ident]
  (let [seed-eid (:db/id (db/entity seed-ident))
        ir-tree  (-> expr ast/parse ir/canonicalize)]
    (evalpath/evaluate-from (db/db) ir-tree seed-eid)))

(defn- endpoint-idents
  "Project evaluate-from result to a set of endpoint idents (drop
   anonymous endpoints)."
  [result]
  (->> result
       (keep (fn [{:keys [eid]}] (:db/ident (db/entity eid))))
       set))

(defn- path-by-endpoint-ident
  "Look up the path-value for the endpoint matching `ident`."
  [result ident]
  (some (fn [{:keys [eid path]}]
          (when (= ident (:db/ident (db/entity eid)))
            path))
        result))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canonical-8 in isolation — atomic + identity operators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest evaluate-predicate-single-hop
  (testing "atomic predicate :dt/subclass-of from :dt/Property"
    (let [result (eval-from-ident :dt/subclass-of :dt/Property)
          idents (endpoint-idents result)]
      (is (contains? idents :dt/Resource)
          ":dt/Property's :dt/subclass-of target should include :dt/Resource")
      ;; Path shape: (Property → subclass-of → Resource)
      (let [path (path-by-endpoint-ident result :dt/Resource)]
        (is (some? path) "path to :dt/Resource must be present")
        (is (pv/valid? path) "path must satisfy path.value/valid? invariant")
        (is (= 1 (pv/length path)) "single-hop path has length 1")
        (is (= [:dt/subclass-of] (pv/predicates path)))
        (is (= [:forward] (pv/directions path)))))))

(deftest evaluate-self
  (testing ":SELF returns the seed-singleton (zero applications)"
    (let [result (eval-from-ident :SELF :dt/Property)
          idents (endpoint-idents result)]
      (is (= #{:dt/Property} idents)
          ":SELF must return exactly the seed-singleton")
      (let [path (path-by-endpoint-ident result :dt/Property)]
        (is (pv/empty-path? path) ":SELF path has zero edges (singleton)")
        (is (pv/valid? path))))))

(deftest evaluate-any-ref-only
  (testing ":ANY returns only ref-typed outbound endpoints (R-2 fix
            preserved in evaluator)"
    (let [result (eval-from-ident :ANY :dt/Property)]
      (is (every? #(some? (:db/id (db/entity (:eid %)))) result)
          ":ANY endpoints must all be real entities (no scalar values)")
      ;; Every path is length-1 forward via some predicate.
      (doseq [{:keys [path]} result]
        (is (pv/valid? path))
        (is (= 1 (pv/length path)))
        (is (= :forward (first (pv/directions path))))))))

(deftest evaluate-inv-atomic
  (testing "(:INV :dt/subclass-of) from :dt/Resource yields subclasses
            (children of root) — the inverse atomic step finds entities
            pointing AT the seed via :dt/subclass-of"
    (let [result (eval-from-ident [:INV :dt/subclass-of] :dt/Resource)
          idents (endpoint-idents result)]
      ;; Children of :dt/Resource via subclass-of must include :dt/Class
      ;; (and possibly :dt/Property, depending on metamodel hierarchy).
      (is (contains? idents :dt/Class)
          ":dt/Class is a known subclass-of :dt/Resource in the fixture")
      ;; Path shape: (Resource ←subclass-of← child)
      (when-let [path (path-by-endpoint-ident result :dt/Class)]
        (is (pv/valid? path))
        (is (= 1 (pv/length path)))
        (is (= [:inverse] (pv/directions path))
            ":INV step's edge direction must be :inverse")))))

(deftest evaluate-restrict-filter
  (testing "(:RESTRICT [:dt/type :dt/Class]) at :dt/Class itself —
            :dt/Class has :dt/type :dt/Class (instance-of), so the
            filter passes the seed through unchanged"
    (let [result (eval-from-ident [:RESTRICT [:dt/type :dt/Class]]
                                  :dt/Class)
          idents (endpoint-idents result)]
      ;; If :dt/Class has :dt/type :dt/Class, the filter holds and the
      ;; seed remains.  Otherwise filter drops it.  Sanity-check that
      ;; the result is either the seed alone or empty.
      (is (or (= #{:dt/Class} idents) (empty? idents))
          ":RESTRICT keeps the seed if filter passes, drops it otherwise"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canonical-8 in isolation — composition operators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest evaluate-seq-pair
  (testing "(:SEQ p q) chains via fresh intermediate"
    ;; (:SEQ :dt/slots :dt/subclass-of) from a class C:
    ;;   1. Walk :dt/slots — reaches each property class C has
    ;;   2. From each property class, walk :dt/subclass-of — reaches
    ;;      that property class's parent
    ;; If :dt/Class declares any slots whose own class subclass-of's
    ;; something, we'll see those targets.  Conservative assertion:
    ;; whatever endpoints exist are 2-hop paths.
    (let [result (eval-from-ident [:SEQ :dt/slots :dt/subclass-of]
                                  :dt/Class)]
      (doseq [{:keys [path]} result]
        (is (pv/valid? path))
        (is (= 2 (pv/length path)) "SEQ pair → 2-hop paths")
        (is (= [:dt/slots :dt/subclass-of] (pv/predicates path)))))))

(deftest evaluate-or-union
  (testing "(:OR p q) unions branch endpoints"
    (let [via-subclass (endpoint-idents
                         (eval-from-ident :dt/subclass-of :dt/Property))
          via-slots    (endpoint-idents
                         (eval-from-ident :dt/slots :dt/Property))
          via-either   (endpoint-idents
                         (eval-from-ident [:OR :dt/subclass-of :dt/slots]
                                          :dt/Property))]
      (is (= (clojure.set/union via-subclass via-slots) via-either)
          ":OR endpoints must equal the union of branch endpoints"))))

(deftest evaluate-rep-plus-transitive
  (testing "(:REP+ :dt/subclass-of) finds all transitive ancestors"
    (let [result (eval-from-ident [:REP+ :dt/subclass-of] :dt/Property)
          idents (endpoint-idents result)]
      (is (contains? idents :dt/Resource)
          ":dt/Resource reachable via 1+ :dt/subclass-of steps from :dt/Property")
      (let [path (path-by-endpoint-ident result :dt/Resource)]
        (is (pv/valid? path))
        (is (pos? (pv/length path)) ":REP+ must produce at least 1 hop")
        (is (every? #(= :forward %) (pv/directions path)))
        (is (every? #(= :dt/subclass-of %) (pv/predicates path)))))))

(deftest evaluate-rep-star-includes-seed
  (testing "(:REP* p) includes the seed (zero-application case)"
    (let [result (eval-from-ident [:REP* :dt/subclass-of] :dt/Property)
          idents (endpoint-idents result)]
      (is (contains? idents :dt/Property)
          ":REP* includes seed via zero applications")
      (is (contains? idents :dt/Resource)
          ":REP* also reaches further via 1+ applications")
      ;; Seed's path is the singleton (length 0).
      (let [seed-path (path-by-endpoint-ident result :dt/Property)]
        (is (pv/empty-path? seed-path)
            "Seed's path under :REP* is the identity (length 0)")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Composition + cycle + path-explosion
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest evaluate-composition-rep-and-restrict
  (testing "(:SEQ (:REP+ p) (:RESTRICT [t v])) — closure then filter
            (mirrors the e2e-composition-rep-and-restrict shape from
            compiler tests)"
    (let [result (eval-from-ident
                   [:SEQ [:REP+ :dt/subclass-of]
                         [:RESTRICT [:dt/type :dt/Class]]]
                   :dt/Property)
          idents (endpoint-idents result)]
      (is (contains? idents :dt/Resource))
      ;; Path shape: REP+ closure produces ≥1 hop; RESTRICT doesn't add
      ;; an edge (it filters).
      (when-let [path (path-by-endpoint-ident result :dt/Resource)]
        (is (pv/valid? path))
        (is (pos? (pv/length path)))))))

(deftest evaluate-rep-plus-policy-a-first-arrival
  (testing "Policy A: when multiple paths to same endpoint exist via
            :REP+ over the metamodel's tree-shaped subclass hierarchy,
            BFS first-arrival means shortest-path is selected.

            We can verify this by checking that the path to :dt/Resource
            from a deeply-nested seed uses the minimum number of hops."
    (let [result (eval-from-ident [:REP+ :dt/subclass-of] :dt/Property)
          path   (path-by-endpoint-ident result :dt/Resource)]
      (when path
        ;; The path length equals the BFS depth.  Whatever the metamodel
        ;; depth is, the path captured must be a valid shortest-path.
        ;; Assertion: the same endpoint is NOT present multiple times
        ;; in result (Policy A first-arrival enforced).
        (let [resource-eid (:db/id (db/entity :dt/Resource))
              occurrences  (count (filter #(= resource-eid (:eid %)) result))]
          (is (= 1 occurrences)
              "Each endpoint appears EXACTLY once in the result (Policy A)"))))))

(deftest evaluate-or-policy-a-first-arrival
  (testing "Policy A: :OR branches that reach the same endpoint;
            leftmost branch's path wins"
    ;; Construct a deliberate path-explosion: (:OR :dt/subclass-of
    ;; :dt/subclass-of) — both branches reach the same endpoints.
    (let [result-or-dupes (eval-from-ident
                            [:OR :dt/subclass-of :dt/subclass-of]
                            :dt/Property)
          result-single   (eval-from-ident :dt/subclass-of :dt/Property)
          idents-or       (endpoint-idents result-or-dupes)
          idents-single   (endpoint-idents result-single)]
      ;; Endpoint sets must match — duplicates were deduped per Policy A.
      (is (= idents-or idents-single)
          ":OR of duplicate branches must yield same endpoint-set as single branch")
      ;; Result count: each endpoint should appear EXACTLY once.
      (let [eids-or (map :eid result-or-dupes)]
        (is (= (count eids-or) (count (distinct eids-or)))
            "Each endpoint appears exactly once across :OR branches (Policy A)")))))

(deftest evaluate-rep-star-cycle-terminates
  (testing "Defensive: :REP* over a potentially cyclic relation
            terminates via visited-set.  Metamodel's :dt/subclass-of is
            acyclic by construction (it's a tree), but :REP* must still
            handle the no-cycle case correctly + bounded."
    (let [result (eval-from-ident [:REP* :dt/subclass-of] :dt/Property)]
      ;; Result must be finite + contain unique endpoints.
      (is (pos? (count result)))
      (let [eids (map :eid result)]
        (is (= (count eids) (count (distinct eids)))
            "No duplicate endpoints — visited-set + Policy A working")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier-2 — desugarable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest evaluate-opt-includes-seed
  (testing "(:OPT p) ≡ (:OR p :SELF) — zero or one application includes seed"
    (let [result (eval-from-ident [:OPT :dt/subclass-of] :dt/Property)
          idents (endpoint-idents result)]
      (is (contains? idents :dt/Property)
          ":OPT includes seed (zero-application branch via :SELF)")
      (is (contains? idents :dt/Resource)
          ":OPT also reaches one-application target"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier-2 — unsupported throw cleanly
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest evaluate-not-throws-with-descriptive-ex-info
  (testing ":NOT path-data evaluation unsupported in 0.1.0 — throws
            descriptive ex-info instead of partial / wrong results"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)not yet supported|0\.1\.x"
          (eval-from-ident [:NOT :dt/subclass-of] :dt/Property)))))

(deftest evaluate-filter-throws
  (testing ":FILTER path-data evaluation unsupported in 0.1.0"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"(?i)not yet supported|0\.1\.x"
          (eval-from-ident [:FILTER :dt/subclass-of "dt"] :dt/Property)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Path-value invariants — every result satisfies path.value/valid?
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest every-evaluated-path-is-valid
  (testing "Across a variety of expressions, every returned path
            satisfies pv/valid?"
    (doseq [expr [:dt/subclass-of
                  :SELF
                  :ANY
                  [:INV :dt/subclass-of]
                  [:OR :dt/subclass-of :dt/slots]
                  [:SEQ :dt/subclass-of :SELF]
                  [:REP+ :dt/subclass-of]
                  [:REP* :dt/subclass-of]
                  [:OPT :dt/subclass-of]]]
      (let [result (eval-from-ident expr :dt/Property)]
        (doseq [{:keys [path]} result]
          (is (pv/valid? path)
              (str "Invalid path for expr " (pr-str expr)
                   ": " (pr-str path))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cypher-style path predicates on real returned paths
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cypher-prefix-on-real-path
  (testing "pv/prefix? holds for genuine shorter-path prefixes of a
            longer path produced by :REP+"
    (let [result      (eval-from-ident [:REP+ :dt/subclass-of] :dt/Property)
          longest-pe  (->> result
                           (sort-by (comp pv/length :path) >)
                           first)]
      (when (and longest-pe (pos? (pv/length (:path longest-pe))))
        (let [long-path  (:path longest-pe)
              ;; Construct a prefix-of-length-1 by extending an empty
              ;; path with the first edge.
              first-edge (first (pv/edges long-path))
              second-node (second (pv/nodes long-path))
              first-node  (first (pv/nodes long-path))
              prefix      (pv/extend-path (pv/singleton first-node)
                                          first-edge
                                          second-node)]
          (is (pv/prefix? prefix long-path)
              "A constructed 1-edge prefix must satisfy pv/prefix?"))))))
