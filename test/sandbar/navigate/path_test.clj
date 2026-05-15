(ns sandbar.navigate.path-test
  "Tests for sandbar.navigate.path/path-via — Stage P-6 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Threads parse → canonicalize → compile → execute → project; DB-backed
  end-to-end tests use the metamodel fixture (every :dt/Class has
  :dt/subclass-of edges)."
  (:require [clojure.test :refer :all]
            [sandbar.navigate.path :as nav-path]
            [sandbar.navigate.path.value :as pv]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "navigate-path-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; path-via — opts validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; `path-via-requires-from` removed 2026-05-14: per
;; decisions/sandbar_entity_ref_abstraction_2026_05_14.md §D-3.2
;; (Option B), the `:pre` guard on `from` (a ref-arg) is dropped —
;; boundary owns boundary validation.  The nil-from path now flows
;; through the inline `(when (nil? (:db/id seed-ent)) ...)` check and
;; produces the same `:Seed entity not found:` ExceptionInfo as
;; `path-via-rejects-missing-seed` below.  Test removed as redundant
;; with that one + obsolete in its AssertionError assertion.

(deftest path-via-requires-via
  ;; `via` is a path-grammar expression, not a ref-arg — `(some? via)`
  ;; :pre invariant retained per ADR §D-3.2 (internal invariants stay).
  (is (thrown? AssertionError (nav-path/path-via {:from :dt/Property}))))

(deftest path-via-rejects-malformed-via-string
  (is (thrown? clojure.lang.ExceptionInfo
               (nav-path/path-via {:from :dt/Property :via "[malformed"}))))

(deftest path-via-rejects-bad-via-shape
  (is (thrown? clojure.lang.ExceptionInfo
               (nav-path/path-via {:from :dt/Property :via 42}))))

(deftest path-via-rejects-missing-seed
  (is (thrown-with-msg?
        clojure.lang.ExceptionInfo #"(?i)seed entity not found"
        (nav-path/path-via {:from :nonexistent/Entity :via :cites}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; path-via — end-to-end against metamodel
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest path-via-atomic-predicate
  (testing "atomic-predicate :via walks one hop"
    (let [result (nav-path/path-via
                   {:from :dt/Property :via :dt/subclass-of})]
      (is (contains? result :reachable))
      (is (contains? result :total))
      (is (contains? result :returned))
      (is (pos? (:total result)))
      ;; :dt/Property's :dt/subclass-of edges should reach :dt/Resource
      (let [idents (set (map :db/ident (:reachable result)))]
        (is (contains? idents :dt/Resource))))))

(deftest path-via-rep-plus-transitive
  (testing "(:REP+ :dt/subclass-of) finds all ancestors"
    (let [result (nav-path/path-via
                   {:from :dt/Property :via [:REP+ :dt/subclass-of]})
          idents (set (map :db/ident (:reachable result)))]
      (is (contains? idents :dt/Resource)))))

(deftest path-via-edn-string-form
  (testing ":via accepted as EDN string"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via "[:REP+ :dt/subclass-of]"})]
      (is (pos? (:total result))))))

(deftest path-via-limit-cap
  (testing ":limit caps :returned; :total stays full"
    (let [unlimited (nav-path/path-via
                      {:from :dt/Resource
                       :via [:INV :dt/subclass-of]})
          capped    (nav-path/path-via
                      {:from :dt/Resource
                       :via  [:INV :dt/subclass-of]
                       :limit 2})]
      (is (= (:total unlimited) (:total capped)))
      (is (<= (:returned capped) 2))
      (when (pos? (:total unlimited))
        (is (= 2 (:returned capped)))))))

(deftest path-via-include-paths-populates-real-path-data
  (testing "Phase R Stage R-7: :include #{:paths} now POPULATES path
            data — no more :path-data-deferred flag.  Each :reachable
            entry is {:entity ... :path <path-value>} with a real
            path satisfying path.value/valid?.

            (Replaces the prior path-via-include-paths-flag test
            which asserted the placeholder :path-data-deferred flag
            per the pre-R-7 P-6 scope.)"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via :dt/subclass-of
                    :include #{:paths}})]
      ;; Post-R-7: no deferred flag (Option D closed the placeholder).
      (is (not (contains? result :path-data-deferred))
          ":path-data-deferred must NOT appear in R-7 result shape")
      (is (contains? result :reachable))
      (is (contains? result :total))
      (is (contains? result :returned))
      (is (pos? (:total result))
          ":dt/Property →:dt/subclass-of→ should reach :dt/Resource")
      ;; New shape: each entry has :entity + :path
      (doseq [entry (:reachable result)]
        (is (contains? entry :entity)
            "each :reachable entry must carry :entity")
        (is (contains? entry :path)
            "each :reachable entry must carry :path (Option D)")
        (is (pv/valid? (:path entry))
            "every path must satisfy path.value/valid?")
        ;; Single-hop :dt/subclass-of → path length 1
        (is (= 1 (pv/length (:path entry))))
        (is (= [:dt/subclass-of] (pv/predicates (:path entry))))
        (is (= [:forward] (pv/directions (:path entry))))))))

(deftest path-via-no-include-no-deferred-flag
  (testing "without :include #{:paths}, no :path-data-deferred flag"
    (let [result (nav-path/path-via
                   {:from :dt/Property :via :dt/subclass-of})]
      (is (not (contains? result :path-data-deferred))))))

(deftest path-via-composition
  (testing "complex composed expression: (:SEQ (:REP+ p) (:RESTRICT [...]))"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via  [:SEQ [:REP+ :dt/subclass-of]
                                [:RESTRICT [:dt/type :dt/Class]]]})
          idents (set (map :db/ident (:reachable result)))]
      (is (contains? idents :dt/Resource)))))

(deftest path-via-tier-2-not
  (testing "Tier-2 :NOT works through the full pipeline"
    (let [result (nav-path/path-via
                   {:from :dt/Property :via [:NOT :dt/subclass-of]})]
      (is (pos? (:total result))))))

(deftest path-via-tier-2-opt
  (testing "Tier-2 :OPT works (zero-or-one)"
    (let [result (nav-path/path-via
                   {:from :dt/Property :via [:OPT :dt/subclass-of]})
          idents (set (map :db/ident (:reachable result)))]
      (is (contains? idents :dt/Property)
          ":OPT includes seed (zero applications)")
      (is (contains? idents :dt/Resource)
          ":OPT also reaches one-application target"))))

(deftest path-via-any-returns-structured-response
  (testing "F-MF-1 anti-regression: (path-via {:from :dt/Property :via :ANY})
            returns a structured response without crashing on
            :db.error/not-a-keyword.

            Pre-fix: :ANY emitted [?start ?p ?end] without constraining ?p
            to ref-typed attributes; scalar values like :db/doc strings
            were treated as entity ids and db/entity'd, crashing the call.
            Post-fix: the compile-time ref-type guard ensures every
            endpoint is a ref-typed entity."
    (let [result (nav-path/path-via {:from :dt/Property :via :ANY})]
      (is (contains? result :reachable))
      (is (contains? result :total))
      (is (contains? result :returned))
      ;; Endpoints are projected entities — every entry should be a map
      ;; (entity projection), not a raw scalar value.
      (is (every? map? (:reachable result))
          ":ANY endpoints must be entity-maps, not raw scalars"))))

(deftest path-via-any-as-edn-string
  (testing ":ANY also works through the EDN-string :via path"
    (let [result (nav-path/path-via {:from :dt/Property :via ":ANY"})]
      (is (contains? result :reachable))
      (is (every? map? (:reachable result))))))

(deftest path-via-rep-plus-with-paths-multi-hop
  (testing "Phase R Stage R-7: (:REP+ p) with :include #{:paths} returns
            multi-hop path data; every path is a valid forward chain
            of :dt/subclass-of edges from seed to endpoint"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via  [:REP+ :dt/subclass-of]
                    :include #{:paths}})]
      (is (not (contains? result :path-data-deferred)))
      (is (pos? (:total result)))
      (doseq [entry (:reachable result)]
        (let [path (:path entry)]
          (is (pv/valid? path))
          (is (pos? (pv/length path)) ":REP+ paths must have ≥1 edge")
          (is (every? #(= :dt/subclass-of %) (pv/predicates path)))
          (is (every? #(= :forward %) (pv/directions path))))))))

(deftest path-via-rep-star-includes-seed-with-paths
  (testing "Phase R Stage R-7: (:REP* p) with :include #{:paths} —
            zero-application branch produces the seed-singleton path
            (length 0); one-or-more branch produces longer paths"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via  [:REP* :dt/subclass-of]
                    :include #{:paths}})
          paths  (mapv :path (:reachable result))
          lengths (set (map pv/length paths))]
      ;; :REP* always includes the seed (length 0)
      (is (contains? lengths 0)
          ":REP* must include the seed-singleton via zero applications")
      ;; And reaches at least one transitive ancestor (length ≥ 1)
      (is (some pos? lengths)
          ":REP* must also produce ≥1-edge paths via one+ applications"))))

(deftest path-via-paths-cypher-prefix-on-real-path
  (testing "Phase R Stage R-7: pv/prefix? holds for genuine prefixes
            of real returned paths (Cypher-style path predicate
            verification per ADR §Acceptance criterion 8)"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via  [:REP+ :dt/subclass-of]
                    :include #{:paths}})
          longest (->> (:reachable result)
                       (sort-by (comp pv/length :path) >)
                       first)]
      (when (and longest (>= (pv/length (:path longest)) 1))
        (let [long-path (:path longest)
              first-edge (first (pv/edges long-path))
              first-node (first (pv/nodes long-path))
              second-node (second (pv/nodes long-path))
              prefix      (pv/extend-path (pv/singleton first-node)
                                          first-edge
                                          second-node)]
          (is (pv/prefix? prefix long-path)
              "Constructed 1-edge prefix must satisfy pv/prefix? on real path"))))))

(deftest path-via-endpoint-only-no-perf-regression-shape
  (testing "Phase R Stage R-7: endpoint-only fast-path (no :include
            #{:paths}) preserves the original :reachable shape
            (entity-maps, NOT wrapped in {:entity :path})"
    (let [result (nav-path/path-via
                   {:from :dt/Property :via :dt/subclass-of})]
      (is (not (contains? result :path-data-deferred)))
      (doseq [entry (:reachable result)]
        ;; Entry is a raw entity-map — must NOT have an :entity key
        ;; pointing to ANOTHER entity-map (that would indicate the
        ;; paths-wrapper was applied incorrectly to the fast-path).
        (is (not (contains? entry :path))
            "endpoint-only result must NOT carry :path")
        ;; Must look like an entity-map (has :db/id or :db/ident).
        (is (or (:db/id entry) (:db/ident entry))
            "endpoint-only :reachable entries are raw entity-maps")))))
