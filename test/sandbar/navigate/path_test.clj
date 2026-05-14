(ns sandbar.navigate.path-test
  "Tests for sandbar.navigate.path/path-via — Stage P-6 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Threads parse → canonicalize → compile → execute → project; DB-backed
  end-to-end tests use the metamodel fixture (every :dt/Class has
  :dt/subclass-of edges)."
  (:require [clojure.test :refer :all]
            [sandbar.navigate.path :as nav-path]
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

(deftest path-via-include-paths-flag
  (testing ":include #{:paths} surfaces :path-data-deferred flag (P-6 scope)"
    (let [result (nav-path/path-via
                   {:from :dt/Property
                    :via :dt/subclass-of
                    :include #{:paths}})]
      (is (true? (:path-data-deferred result)))
      (is (contains? result :reachable)))))

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
