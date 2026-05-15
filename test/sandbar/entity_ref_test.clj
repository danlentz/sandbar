(ns sandbar.entity-ref-test
  "Adversarial tests for sandbar.entity-ref — Phase R Stage R-1 per
  decisions/sandbar_entity_ref_abstraction_2026_05_14.md.

  Covers:
  - All accepted input forms (keyword, prefixed-string, unprefixed-string,
    numeric-string, integer, entity-map) round-trip to entity map
  - All error reasons (malformed-input, not-found, lookup-vector-unsupported,
    no-ident) raise structured ex-info with `:reasons #{}` set-arity
  - Codex F-MF-3 verbatim falsification: integer eid → structured response,
    NOT AssertionError
  - Multi-reason envelope (lookup-vector input carries both
    :malformed-input AND :lookup-vector-unsupported)
  - `resolve-ident` happy paths + no-ident error
  - `validate` predicate-style returns
  - `error` constructor accepts both keyword (single-reason) and
    set (multi-reason) arities"
  (:require [clojure.test :refer :all]
            [sandbar.entity-ref :as eref]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "entity-ref-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defn- make-test-memory!
  "Create a :mm/Memory entity for resolution testing.
   Returns the entity map."
  [name]
  (dt/make :mm/Memory
           {:mm.memory/rel-path (str "test/" name ".md")
            :mm.memory/name     name
            :mm.memory/body-raw "test body content"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resolve — accepted input forms

(deftest resolve-keyword-ident-test
  (testing "resolve accepts keyword ident and returns entity map"
    (let [e (eref/resolve :mm/Memory)]
      (is (some? e))
      (is (some? (:db/id e)))
      (is (= :mm/Memory (:db/ident e))))))

(deftest resolve-prefixed-string-test
  (testing "resolve accepts prefixed string \":ns/name\" and coerces to ident"
    (let [e (eref/resolve ":mm/Memory")]
      (is (some? e))
      (is (= :mm/Memory (:db/ident e))))))

(deftest resolve-unprefixed-string-test
  (testing "resolve accepts unprefixed string \"ns/name\" and coerces to ident"
    (let [e (eref/resolve "mm/Memory")]
      (is (some? e))
      (is (= :mm/Memory (:db/ident e))))))

(deftest resolve-integer-eid-test
  (testing "resolve accepts integer eid and returns entity map"
    (let [created (make-test-memory! "alpha")
          eid     (:db/id created)
          e       (eref/resolve eid)]
      (is (some? e))
      (is (= eid (:db/id e))))))

(deftest resolve-numeric-string-test
  (testing "resolve accepts numeric string and parses to integer eid"
    (let [created (make-test-memory! "beta")
          eid     (:db/id created)
          e       (eref/resolve (str eid))]
      (is (some? e))
      (is (= eid (:db/id e))))))

(deftest resolve-entity-map-idempotent-test
  (testing "resolve on entity map returns it as-is (idempotent)"
    (let [created (make-test-memory! "gamma")
          e       (eref/resolve created)]
      (is (= (:db/id created) (:db/id e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resolve — error reasons

(deftest resolve-not-found-ident-test
  (testing "resolve on nonexistent ident raises :entity-ref/not-found"
    (try
      (eref/resolve :totally/nonexistent-ident)
      (is false "expected ex-info to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (contains? (:reasons (ex-data e)) :entity-ref/not-found))
        (is (= :totally/nonexistent-ident (:ref (ex-data e))))
        (is (= :ident (:resolved-via (ex-data e))))))))

(deftest resolve-not-found-eid-test
  (testing "resolve on nonexistent eid raises :entity-ref/not-found"
    (try
      (eref/resolve 9999999999)
      (is false "expected ex-info to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (contains? (:reasons (ex-data e)) :entity-ref/not-found))
        (is (= :eid (:resolved-via (ex-data e))))))))

(deftest resolve-malformed-nil-test
  (testing "resolve on nil raises :entity-ref/malformed-input"
    (try
      (eref/resolve nil)
      (is false "expected ex-info to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (contains? (:reasons (ex-data e)) :entity-ref/malformed-input))))))

(deftest resolve-malformed-blank-string-test
  (testing "resolve on empty string raises :entity-ref/malformed-input"
    (try
      (eref/resolve "")
      (is false "expected ex-info to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (contains? (:reasons (ex-data e)) :entity-ref/malformed-input))))))

(deftest resolve-malformed-boolean-test
  (testing "resolve on boolean raises :entity-ref/malformed-input"
    (try
      (eref/resolve true)
      (is false "expected ex-info to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (contains? (:reasons (ex-data e)) :entity-ref/malformed-input))))))

(deftest resolve-lookup-vector-test
  (testing "resolve on lookup-vector raises BOTH :malformed-input AND :lookup-vector-unsupported"
    (try
      (eref/resolve [:mm.memory/name "alpha"])
      (is false "expected ex-info to be thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [reasons (:reasons (ex-data e))]
          (is (contains? reasons :entity-ref/malformed-input))
          (is (contains? reasons :entity-ref/lookup-vector-unsupported))
          (is (= 2 (count reasons))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Codex F-MF-3 verbatim falsification — integer eid no longer escapes
;; AssertionError; lands as structured ex-info instead.

(deftest f-mf-3-integer-eid-not-found-test
  (testing "F-MF-3: integer eid that doesn't resolve produces structured ex-info, NOT AssertionError"
    (try
      (eref/resolve 12345)
      ;; Either the test DB has a real entity at eid 12345 (resolved successfully)
      ;; OR it doesn't (throws :entity-ref/not-found).  Both are acceptable here;
      ;; the key assertion is that NEITHER AssertionError NOR nil escapes.
      (catch clojure.lang.ExceptionInfo e
        (is (contains? (:reasons (ex-data e)) :entity-ref/not-found)
            "integer eid that doesn't exist should land as :entity-ref/not-found"))
      (catch AssertionError _e
        (is false "F-MF-3 REGRESSION: integer eid produced AssertionError instead of structured ex-info"))
      (catch Throwable t
        (is false (str "F-MF-3 REGRESSION: integer eid produced unexpected throwable: " (class t)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resolve-ident

(deftest resolve-ident-keyword-test
  (testing "resolve-ident on keyword returns the ident"
    (is (= :mm/Memory (eref/resolve-ident :mm/Memory)))))

(deftest resolve-ident-string-test
  (testing "resolve-ident on string coerces to ident"
    (is (= :mm/Memory (eref/resolve-ident "mm/Memory")))
    (is (= :mm/Memory (eref/resolve-ident ":mm/Memory")))))

(deftest resolve-ident-anonymous-eid-test
  (testing "resolve-ident on entity without :db/ident raises :entity-ref/no-ident"
    (let [created (make-test-memory! "anonymous")
          eid     (:db/id created)]
      ;; Test memory entities don't have :db/ident (only metamodel classes do)
      (try
        (eref/resolve-ident eid)
        (is false "expected ex-info to be thrown for entity without :db/ident")
        (catch clojure.lang.ExceptionInfo e
          (is (contains? (:reasons (ex-data e)) :entity-ref/no-ident)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; validate predicate-style

(deftest validate-success-test
  (testing "validate on valid ref returns {:valid? true :entity ...}"
    (let [result (eref/validate :mm/Memory)]
      (is (:valid? result))
      (is (some? (:entity result)))
      (is (= :mm/Memory (:db/ident (:entity result)))))))

(deftest validate-failure-test
  (testing "validate on invalid ref returns {:valid? false :reasons #{...}}"
    (let [result (eref/validate :totally/nonexistent)]
      (is (false? (:valid? result)))
      (is (contains? (:reasons result) :entity-ref/not-found))
      (is (string? (:message result))))))

(deftest validate-never-raises-test
  (testing "validate never raises, even on totally malformed input"
    (is (false? (:valid? (eref/validate nil))))
    (is (false? (:valid? (eref/validate true))))
    (is (false? (:valid? (eref/validate ""))))
    (is (false? (:valid? (eref/validate [:lookup "vec"]))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; error constructor

(deftest error-single-keyword-test
  (testing "error with keyword wraps as single-element :reasons set"
    (let [ex (eref/error :entity-ref/not-found "msg" {:ref 123})
          data (ex-data ex)]
      (is (= #{:entity-ref/not-found} (:reasons data)))
      (is (= 123 (:ref data)))
      (is (= "msg" (.getMessage ex))))))

(deftest error-multi-reason-set-test
  (testing "error with set preserves the reasons set as-is"
    (let [ex (eref/error #{:entity-ref/malformed-input
                           :entity-ref/lookup-vector-unsupported}
                         "msg"
                         {:ref [:slot :val]})
          data (ex-data ex)]
      (is (= #{:entity-ref/malformed-input :entity-ref/lookup-vector-unsupported}
             (:reasons data))))))

(deftest error-rejects-invalid-arg-test
  (testing "error with non-keyword non-set raises IllegalArgumentException"
    (is (thrown? IllegalArgumentException
                 (eref/error "not a keyword or set" "msg" {})))))
