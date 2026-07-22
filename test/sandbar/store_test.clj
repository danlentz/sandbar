(ns sandbar.store-test
  "Tests for sandbar.store/create-memory! — the unified ζ-identity create path
   (2026-05-29 session-lifecycle-hardening arc, Bug-3 fix) — and the shared
   codec-md/edn-safe-ident digit-dodge."
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.reactive.sinks :as sinks]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; it7 FF-2 — class-level corpus-document coverage (Log/Fn/Workflow) + pre-transact
;; rel-path normalize / containment / collision at the create boundary.  it6
;; BOARD-MINUTE Lane-B fast-follows #1 + #2.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ff2-corpus-document-class-covers-log-fn-workflow
  (testing "the three runtime-root corpus-document classes the it6 root-ancestry
            gate silently EXCLUDED are NOW covered by class-level gating (:mm/Log
            under :mm/Activity; :mm/Fn / :mm/Workflow under :mm/Spec)"
    (is (dt/corpus-document-class? :mm/Log)      ":mm/Log (Activity) IS a corpus doc")
    (is (dt/corpus-document-class? :mm/Fn)       ":mm/Fn (Spec) IS a corpus doc")
    (is (dt/corpus-document-class? :mm/Workflow) ":mm/Workflow (Spec) IS a corpus doc")))

(deftest ff2-runtime-classes-stay-rel-path-less
  (testing "CAREFUL: :mm/Schedule + the runtime event/activity classes MUST stay
            rel-path-less (NOT corpus-document) — the inclusion override is
            MONOTONE, adding Log/Fn/Workflow only and removing nothing"
    (is (not (dt/corpus-document-class? :mm/Schedule)) ":mm/Spec content-key ident")
    (is (not (dt/corpus-document-class? :mm/EventLog)) ":mm/Activity telemetry")
    (is (not (dt/corpus-document-class? :mm/Run))      ":mm/Activity :db-only")
    (is (not (dt/corpus-document-class? :mm/Job))      ":mm/Spec runtime job")
    (is (not (dt/corpus-document-class? :mm/Event))    ":mm/Event bus primitive")))

(deftest ff2-corpus-doc-runtime-class-without-rel-path-or-ident-now-rejects
  (testing "a :mm/Log / :mm/Fn / :mm/Workflow create with NEITHER rel-path NOR a
            derivable ident now loud-rejects (was a silent DB-only orphan under
            the it6 root-ancestry gate) — derive-or-reject coverage extended"
    (doseq [cls [:mm/Log :mm/Fn :mm/Workflow]]
      (let [ex (try (store/create-memory! cls
                                          {:mm.memory/name (str "ff2 orphan " cls)}
                                          {:validate? false})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) (str cls " must loud-reject a rel-path-less create"))
        (is (= :create-path-missing-rel-path (:sandbar/error (ex-data ex)))
            (str cls " carries the actionable :sandbar/error tag"))))))

(deftest ff2-log-with-rel-path-still-creates
  (testing "the newly-covered :mm/Log still creates fine WITH a rel-path (the
            handoff path) — coverage adds a loud-fail on the anomaly only"
    (let [e (store/create-memory! :mm/Log
                                  {:mm.memory/rel-path    "logs/ff2_log_ok.md"
                                   :mm.memory/name        "ff2 log ok"
                                   :mm.memory/memory-type :log
                                   :mm.memory/body-raw    "b"}
                                  {:validate? false})]
      (is (= :memory.logs/ff2_log_ok (:db/ident e)) "ident derived from rel-path")
      (is (= "logs/ff2_log_ok.md" (:mm.memory/rel-path e))))))

(deftest ff2-runtime-activity-create-without-rel-path-not-rejected
  (testing "CAREFUL regression: a rel-path-less runtime class create still passes
            through create-memory! WITHOUT the corpus-document loud-fail
            (:mm/EventLog telemetry-shape here) — the monotone override left it"
    (is (some? (store/create-memory! :mm/EventLog
                                     {:mm.memory/name "ff2 eventlog no-reject"}
                                     {:validate? false}))
        "an :mm/EventLog create without rel-path must NOT hit the loud-fail")))

;; ---- pre-transact rel-path hardening (FF-2 #2) ----

(deftest ff2-rejects-traversal-rel-path-pre-transact
  (testing "a `..`-traversal rel-path is refused by the G2 containment sanitizer
            BEFORE dt/make (not just at the sink) — no malformed DB row commits"
    (let [ex (try (store/create-memory! :mm/Memory
                                        {:mm.memory/rel-path    "../../etc/ff2_evil.md"
                                         :mm.memory/name        "ff2 traversal"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "x"}
                                        {:validate? false})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "traversal rel-path must be refused")
      (is (= :rel-path-traversal-refusal (:sandbar/error (ex-data ex)))))))

(deftest ff2-rejects-absolute-rel-path-pre-transact
  (testing "an absolute rel-path is refused pre-transact (normalization preserves
            the leading `/` so containment still catches it)"
    (let [ex (try (store/create-memory! :mm/Memory
                                        {:mm.memory/rel-path    "/etc/ff2_absolute.md"
                                         :mm.memory/name        "ff2 absolute"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "x"}
                                        {:validate? false})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "absolute rel-path must be refused")
      (is (= :rel-path-traversal-refusal (:sandbar/error (ex-data ex)))))))

(deftest ff2-normalizes-stored-rel-path
  (testing "a leading `memory/` is stripped from the stored rel-path so ident,
            stored slot, and sink write target stay mutually consistent"
    (let [e (store/create-memory! :mm/Memory
                                  {:mm.memory/rel-path    "memory/decisions/ff2_norm.md"
                                   :mm.memory/name        "ff2 norm"
                                   :mm.memory/memory-type :decision
                                   :mm.memory/body-raw    "x"})]
      (is (= "decisions/ff2_norm.md" (:mm.memory/rel-path e)) "leading memory/ stripped")
      (is (= :memory.decisions/ff2_norm (:db/ident e))))))

(deftest ff2-rejects-rel-path-collision-with-different-entity
  (testing "a create whose rel-path is already owned by a DIFFERENT entity is
            refused (ownership/collision); an idempotent re-create of the SAME
            entity (same derived ident) is allowed (upsert)"
    ;; entity A owns decisions/ff2_collide.md (derived ident :memory.decisions/ff2_collide)
    (store/create-memory! :mm/Memory
                          {:mm.memory/rel-path    "decisions/ff2_collide.md"
                           :mm.memory/name        "ff2 collide A"
                           :mm.memory/memory-type :decision
                           :mm.memory/body-raw    "a"})
    ;; a DIFFERENT entity (explicit distinct ident) claiming the SAME rel-path → refused
    (let [ex (try (store/create-memory!
                    :mm/Memory
                    {:db/ident              :memory.decisions/ff2_collide_other
                     :mm.memory/rel-path    "decisions/ff2_collide.md"
                     :mm.memory/name        "ff2 collide B"
                     :mm.memory/memory-type :decision
                     :mm.memory/body-raw    "b"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a different entity claiming the same rel-path must be refused")
      (is (= :rel-path-collision (:sandbar/error (ex-data ex)))))
    ;; re-creating A at the same rel-path (same derived ident) is an idempotent upsert
    (is (some? (store/create-memory! :mm/Memory
                                     {:mm.memory/rel-path    "decisions/ff2_collide.md"
                                      :mm.memory/name        "ff2 collide A v2"
                                      :mm.memory/memory-type :decision
                                      :mm.memory/body-raw    "a2"}))
        "idempotent re-create of the same entity must NOT be a collision")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Emit-path filename-length guard (2026-07-21 hardening).
;;
;; The bug: a :mm/Memory whose rel-path carries a segment over the filesystem
;; per-segment name budget COMMITTED fine, then the reactive fs sink's write
;; failed ENAMETOOLONG on every drain and was warn+swallowed — a permanent
;; DB-only orphan, silent FS↔DB bijection break.  The window is wider than
;; NAME_MAX itself: atomic-write!'s `.tmp` sibling means a 252-255-byte
;; filename has a LEGAL target name whose tmp write still fails.  Fix:
;; create-time loud refusal — guard (1) of assert-corpus-rel-path-safe!, via
;; sinks/assert-rel-path-name-max! — so the sink never sees the entity.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ascii-name
  "An `n`-character (= n-UTF-8-byte) ASCII segment stub."
  [n]
  (apply str (repeat n "a")))

(defn- rel-path-owners
  "Eids of entities owning `rel-path` — empty means nothing committed, i.e.
   the reactive sink can never see the refused create (nothing to drain)."
  [rel-path]
  (d/q '[:find [?e ...] :in $ ?rp :where [?e :mm.memory/rel-path ?rp]]
       (db/db) rel-path))

(deftest emit-guard-rejects-overlong-filename-and-nothing-commits
  (testing "a filename over the emit budget → loud :rel-path-segment-too-long
            refusal PRE-transact, message naming the limit; NO DB row commits,
            so the reactive sink never sees the entity"
    (let [rel-path (str "decisions/" (ascii-name 260) ".md")   ;; 263-byte filename
          ex (try (store/create-memory! :mm/Memory
                                        {:mm.memory/rel-path    rel-path
                                         :mm.memory/name        "emit guard overlong"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "x"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "overlong filename must refuse loudly at create")
      (is (= :rel-path-segment-too-long (:sandbar/error (ex-data ex)))
          "carries the actionable :sandbar/error tag")
      (is (re-find #"251" (.getMessage ^Exception ex))
          "message names the effective filename budget")
      (is (re-find #"NAME_MAX" (.getMessage ^Exception ex))
          "message names the filesystem limit it enforces")
      (is (empty? (rel-path-owners rel-path))
          "no entity committed — the sink can never see it"))))

(deftest emit-guard-rejects-tmp-window-filename
  (testing "a 252-byte filename — LEGAL as a target name, but whose
            `<name>.tmp` atomic-write sibling exceeds NAME_MAX — is refused:
            the exact silent-orphan window (passes containment, commits, then
            the sink's tmp write dies ENAMETOOLONG and is swallowed)"
    (let [rel-path (str "decisions/" (ascii-name 249) ".md")   ;; 252-byte filename
          ex (try (store/create-memory! :mm/Memory
                                        {:mm.memory/rel-path    rel-path
                                         :mm.memory/name        "emit guard tmp window"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "x"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the tmp-window filename must be refused")
      (is (= :rel-path-segment-too-long (:sandbar/error (ex-data ex))))
      (is (true? (:final-segment? (ex-data ex))) "flagged as the FILENAME budget")
      (is (= 252 (:segment-bytes (ex-data ex))))
      (is (= sinks/filename-max-bytes (:limit-bytes (ex-data ex)))
          "refused against the tmp-reserving 251-byte budget, not raw NAME_MAX")
      (is (empty? (rel-path-owners rel-path)) "nothing committed"))))

(deftest emit-guard-boundary-251-byte-filename-creates
  (testing "a filename at EXACTLY the 251-byte budget still creates (guard is
            not over-broad): ident derived, :mm/id minted, rel-path stored"
    (let [rel-path (str "decisions/" (ascii-name 248) ".md")   ;; 251-byte filename
          e (store/create-memory! :mm/Memory
                                  {:mm.memory/rel-path    rel-path
                                   :mm.memory/name        "emit guard boundary ok"
                                   :mm.memory/memory-type :decision
                                   :mm.memory/body-raw    "x"})]
      (is (some? (:db/ident e)) "ident derived as usual")
      (is (uuid? (:mm/id e)) ":mm/id minted")
      (is (= rel-path (:mm.memory/rel-path e)) "boundary rel-path stored"))))

(deftest emit-guard-directory-segment-budget
  (testing "DIRECTORY segments get the full 255-byte NAME_MAX (no tmp suffix
            lands on them): 256 refused, 255 creates"
    (let [bad (str (ascii-name 256) "/x.md")
          ex  (try (store/create-memory! :mm/Memory
                                         {:mm.memory/rel-path    bad
                                          :mm.memory/name        "emit guard dir 256"
                                          :mm.memory/memory-type :decision
                                          :mm.memory/body-raw    "x"})
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "a 256-byte directory segment must be refused")
      (is (= :rel-path-segment-too-long (:sandbar/error (ex-data ex))))
      (is (false? (:final-segment? (ex-data ex))) "flagged as a DIRECTORY segment")
      (is (= sinks/name-max-bytes (:limit-bytes (ex-data ex)))
          "directory budget is full NAME_MAX (255), not the filename 251"))
    (let [ok (str (ascii-name 255) "/x.md")
          e  (store/create-memory! :mm/Memory
                                   {:mm.memory/rel-path    ok
                                    :mm.memory/name        "emit guard dir 255"
                                    :mm.memory/memory-type :decision
                                    :mm.memory/body-raw    "x"}
                                   {:validate? false})]
      (is (= ok (:mm.memory/rel-path e)) "a 255-byte directory segment creates"))))

(deftest emit-guard-measures-utf8-bytes-not-chars
  (testing "the budget is UTF-8 BYTES (the ext4/git portable floor), not
            characters: 90 three-byte CJK chars + `.md` = 273 bytes yet only
            93 chars — APFS would write it locally, ext4 checkout would not"
    (let [rel-path (str "decisions/" (apply str (repeat 90 "中")) ".md")
          ex (try (store/create-memory! :mm/Memory
                                        {:mm.memory/rel-path    rel-path
                                         :mm.memory/name        "emit guard multibyte"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "x"}
                                        {:validate? false})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "an over-byte-budget multibyte filename must be refused")
      (is (= :rel-path-segment-too-long (:sandbar/error (ex-data ex))))
      (is (= 273 (:segment-bytes (ex-data ex))) "measured in UTF-8 bytes"))))

(deftest emit-guard-covers-ident-derived-rel-path
  (testing "the guard runs on the FINALIZED rel-path regardless of source: an
            explicit :db/ident whose DERIVED rel-path filename busts the
            budget refuses identically (the brief's '>255-byte derived name')"
    (let [long-ident (keyword "memory.decisions" (ascii-name 270))
          ex (try (store/create-memory! :mm/Memory
                                        {:db/ident              long-ident
                                         :mm.memory/name        "emit guard from ident"
                                         :mm.memory/memory-type :decision
                                         :mm.memory/body-raw    "x"})
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "derived-from-ident overlong rel-path must be refused")
      (is (= :rel-path-segment-too-long (:sandbar/error (ex-data ex))))
      (is (empty? (rel-path-owners (str "decisions/" (ascii-name 270) ".md")))
          "nothing committed via the ident-derivation path either"))))
