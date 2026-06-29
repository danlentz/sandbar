(ns sandbar.store-test
  "Tests for sandbar.store/create-memory! — the unified ζ-identity create path
   (2026-05-29 session-lifecycle-hardening arc, Bug-3 fix) — and the shared
   codec-md/edn-safe-ident digit-dodge."
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.store :as store]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "store-test"}))

(deftest edn-safe-ident-dodges-digit-leading-names
  (testing "digit-leading ident name gets a semantic singularized prefix; non-digit unchanged; idempotent"
    ;; NB: the raw digit-leading idents must be CONSTRUCTED via `keyword` — they
    ;; cannot be written as source literals because the Clojure reader rejects
    ;; them (which is precisely the bug edn-safe-ident exists to dodge).
    (let [raw-session (keyword "memory.sessions" "2026-05-29T0713_x")
          raw-log     (keyword "memory.logs" "2026-05-28_y")]
      (is (= :memory.sessions/session-2026-05-29T0713_x
             (codec-md/edn-safe-ident raw-session)))
      (is (= :memory.logs/log-2026-05-28_y
             (codec-md/edn-safe-ident raw-log)))
      (is (= :memory.decisions/foo
             (codec-md/edn-safe-ident :memory.decisions/foo))
          "non-digit-leading unchanged")
      (is (= :memory.sessions/session-2026-05-29T0713_x
             (codec-md/edn-safe-ident (codec-md/edn-safe-ident raw-session)))
          "idempotent")
      ;; the dodged form round-trips through the EDN reader; the raw form does NOT
      (is (keyword? (edn/read-string (pr-str :memory.sessions/session-2026-05-29T0713_x)))
          "dodged ident is EDN-readable")
      (is (thrown? Exception (edn/read-string ":memory.sessions/2026-05-29T0713_x"))
          "digit-leading ident name is NOT EDN-readable"))))

(deftest create-memory-derives-ident-and-mints-mm-id
  (testing "create-memory! derives :db/ident from rel-path + mints :mm/id"
    (let [e (store/create-memory! :mm/Memory
                                  {:mm.memory/rel-path    "decisions/store_test_foo.md"
                                   :mm.memory/name        "store test foo"
                                   :mm.memory/memory-type :decision
                                   :mm.memory/body-raw    "body"})]
      (is (= :memory.decisions/store_test_foo (:db/ident e)) "derived semantic ident")
      (is (uuid? (:mm/id e)) ":mm/id minted (clj-uuid v5)"))))

(deftest create-memory-digit-leading-rel-path-is-dodged
  (testing "create-memory! dodges a digit-leading derived ident so it is EDN-safe"
    (let [e (store/create-memory! :mm/Session
                                  {:mm.memory/rel-path    "sessions/2026-05-29T0713_store_test.md"
                                   :mm.memory/name        "store test session"
                                   :mm.memory/memory-type :session}
                                  {:validate? false})]
      (is (= :memory.sessions/session-2026-05-29T0713_store_test (:db/ident e))
          "digit-leading slug dodged with a semantic prefix")
      (is (uuid? (:mm/id e)) ":mm/id minted")
      (is (keyword? (edn/read-string (pr-str (:db/ident e))))
          "the session's :db/ident round-trips through the EDN reader"))))

(deftest create-memory-explicit-ident-wins
  (testing "an explicitly-supplied :db/ident is preserved (not overridden by derivation)"
    (let [e (store/create-memory! :mm/Memory
                                  {:db/ident              :memory.decisions/explicit_store_test
                                   :mm.memory/rel-path    "decisions/something_else.md"
                                   :mm.memory/name        "explicit"
                                   :mm.memory/memory-type :decision
                                   :mm.memory/body-raw    "b"})]
      (is (= :memory.decisions/explicit_store_test (:db/ident e)) "explicit ident wins")
      (is (uuid? (:mm/id e)) ":mm/id still minted from the explicit ident"))))
