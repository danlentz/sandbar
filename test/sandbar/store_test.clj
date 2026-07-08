(ns sandbar.store-test
  "Tests for sandbar.store/create-memory! — the unified ζ-identity create path
   (2026-05-29 session-lifecycle-hardening arc, Bug-3 fix) — and the shared
   codec-md/edn-safe-ident digit-dodge."
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; it6 create-path fix — bugs/entity_create_codec_path_mints_identless_-
;; relpathless_entities_fs_projection_silently_skipped_2026_07_08
;;
;; The bug: entity.create for a :mm/Memory WITHOUT :mm.memory/rel-path minted an
;; entity with NO :db/ident and NO rel-path, which the reactive fs sink then
;; silently skipped — a DB-only orphan, FS↔DB bijection break on the PRIMARY
;; capture path.  Fix (a): when an explicit memory :db/ident is present but no
;; rel-path, DERIVE the rel-path from the ident so the entity carries a corpus
;; path (yields ident + mm/id + a projectable rel-path); when NEITHER a rel-path
;; NOR a derivable ident is present, REJECT LOUDLY rather than orphan silently.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-memory-derives-rel-path-from-explicit-ident
  (testing "explicit memory :db/ident, NO rel-path → rel-path derived from ident,
            :mm/id minted, ident preserved (now projectable instead of orphaned)"
    (let [e (store/create-memory! :mm/Memory
                                  {:db/ident              :memory.decisions/it6_from_ident
                                   :mm.memory/name        "it6 from ident"
                                   :mm.memory/memory-type :decision
                                   :mm.memory/body-raw    "body"})]
      (is (= :memory.decisions/it6_from_ident (:db/ident e)) "explicit ident preserved")
      (is (= "decisions/it6_from_ident.md" (:mm.memory/rel-path e))
          "rel-path derived from the ident via the shared inverse")
      (is (uuid? (:mm/id e)) ":mm/id minted from the ident"))))

(deftest create-memory-rejects-first-class-without-rel-path-or-ident
  (testing "first-class :mm/Memory with NEITHER rel-path NOR :db/ident → loud reject
            (the exact bug scenario: frontmatter name/type only, no corpus path)"
    (let [ex (try (store/create-memory! :mm/Memory
                                        {:mm.memory/name        "it6 orphan attempt"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "body"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "must throw (not silently mint a DB-only orphan)")
      (is (= :create-path-missing-rel-path (:sandbar/error (ex-data ex)))
          "carries the actionable :sandbar/error tag")
      (is (re-find #"rel-path" (.getMessage ^Exception ex))
          "message names the missing rel-path remedy"))))

(deftest create-memory-rejects-when-ident-not-corpus-derivable
  (testing "explicit but NON-corpus :db/ident (name that yields no rel-path), no
            rel-path → derivation impossible → loud reject"
    (let [ex (try (store/create-memory! :mm/Memory
                                        {:db/ident              :not-a-memory/garbage
                                         :mm.memory/name        "garbage ident"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "body"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "must throw when the ident cannot be mapped to a corpus path")
      (is (= :create-path-missing-rel-path (:sandbar/error (ex-data ex)))))))

(deftest corpus-document-class-predicate-gates-only-document-types
  (testing "dt/corpus-document-class? (the shared reject+WARN gate) is TRUE for
            corpus-document types and FALSE for the runtime-behavioral branches
            that are :first-class only by inheritance"
    ;; corpus documents → must carry a rel-path
    (is (dt/corpus-document-class? :mm/Decision))
    (is (dt/corpus-document-class? :mm/Plan))
    (is (dt/corpus-document-class? :mm/Bug))
    ;; runtime-behavioral (Spec / Activity) → legitimately rel-path-less
    (is (not (dt/corpus-document-class? :mm/Schedule)) ":mm/Spec branch excluded")
    (is (not (dt/corpus-document-class? :mm/Run))      ":mm/Activity branch excluded")
    ;; :db-only / :inline classes → excluded (policy is not :first-class)
    (is (not (dt/corpus-document-class? :mm/Tag))      ":inline excluded")))

(deftest create-memory-spec-branch-not-rejected
  (testing "a :mm/Spec-branch class (:mm/Schedule) without rel-path is NOT subject
            to the corpus-document loud-fail — it has its own content-key ident
            derivation and never projects to a corpus file"
    ;; :mm/Schedule with neither target nor recurrence stays identless pass-through
    ;; (see derive-schedule-ident); the point is it does NOT throw.
    (is (some? (store/create-memory! :mm/Schedule
                                     {:mm.memory/name "it6 sched no-reject"}
                                     {:validate? false}))
        "schedule create without rel-path must not hit the corpus-document loud-fail")))
