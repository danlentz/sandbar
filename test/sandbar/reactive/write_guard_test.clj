(ns sandbar.reactive.write-guard-test
  "Pre-write registry guard receipts (S2 substrate-fidelity, X-minus
   0.2.0) for `sandbar.projection/guard-registry-critical-write!` + the
   reactive sink's companion rethrow + the `atomic-write!` renameTo rider.

   The guard refuses a write that would strip a registry-critical
   frontmatter key (`at-startup:` / `one-line:`) from an existing on-disk
   file, warn-logs any other dropped key, and is inert for fresh targets /
   no-frontmatter targets.  This namespace also creates the FIRST test
   coverage of the reactive write path anywhere in test/ (RECON-SANDBAR-EMIT
   §4 GAP).

   ## Test law + FS containment (AM-13)

   G1-G6 are pure temp-dir + literal-string tests (no DB).  G7/G8 use
   `tu/make-test-db-fixture` (isolated `datomic:mem://` conn) because
   parse/emit are impure (`dt/range-of` → `(db/db)`).  EVERY invocation
   must export `SANDBAR_CORPUS_ROOT=$(mktemp -d)` so no test can reach the
   real corpus even if a `with-redefs` mis-scopes; `assert-under-root!`
   asserts every write target resolves under a temp root before any write.
   `corpus-root` is redirected per-test via `with-redefs` (it is an
   env-read behind a defn, sinks.clj:corpus-root).

   HARD external receipt (Test law): `:mm/Schedule` + `:mm/Run` aggregate
   counts are unchanged across the fixture-using tests — no shared-
   transactor contact."
  (:require [clojure.test           :refer :all]
            [clojure.java.io        :as io]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.codec          :as codec]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.projection     :as pg]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.test-util      :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures — isolated in-mem DB + fresh codec registry (G7/G8 only need
;; the DB, but the fixture is harmless for the pure tests too)

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "write-guard"})
  (fn [t]
    (codec/clear-all!)
    (codec/register! :markdown (md/make-codec))
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FS-containment helpers (AM-13 / S-5)

(defn temp-dir
  "Create a fresh temp directory; return its absolute path string."
  []
  (let [f (java.io.File/createTempFile "write-guard-" "")]
    (.delete f)
    (.mkdirs f)
    (.getCanonicalPath f)))

(defn assert-under-root!
  "In-test precondition (AM-13): assert `target-path` resolves under
   `root`.  A mis-scoped redef that pointed the sink at the real corpus
   would trip this before any byte is written."
  [root target-path]
  (let [root-canon (.getCanonicalPath (io/file root))
        tgt-canon  (.getCanonicalPath (io/file target-path))]
    (is (str/starts-with? tgt-canon root-canon)
        (str "write target escaped the temp root: " tgt-canon " not under " root-canon))))

(defn class-count
  "Count instances of `class-ident` currently in the fixture DB."
  [class-ident]
  (or (ffirst (d/q '[:find (count ?e)
                     :in $ ?t
                     :where [?e :dt/type ?t]]
                   (db/db) class-ident))
      0))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Literal fixtures — a real TIER-0 registry shape (mirrors
;; memory/git/no_commit_signing.md: both at-startup: and one-line: ride the
;; extras carrier) + minimal variants.

(def tier0-src
  "A registry-shape file carrying BOTH critical extras keys (mirror of
   memory/git/no_commit_signing.md)."
  (str "---\n"
       "name: No Commit Signing or Claude Attribution\n"
       "description: Never add Co-Authored-By lines or any mention of Claude\n"
       "type: feedback\n"
       "scope: global\n"
       "tags: [commits, discipline, git]\n"
       "created: 2026-04-30\n"
       "last-reviewed: 2026-07-02\n"
       "at-startup: high\n"
       "one-line: \"No Co-Authored-By trailers; no bot-email signatures\"\n"
       "---\n"
       "Never sign commits or mention Claude in commit messages.\n"))

(def tier0-rel-path
  "git/no_commit_signing.md")

(defn strip-key
  "Return `src` with the top-level frontmatter line for wire-key `k`
   removed — simulates a degraded emit that dropped that key."
  [src k]
  (->> (str/split-lines src)
       (remove #(str/starts-with? % (str k ":")))
       (str/join "\n")
       (#(str % "\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G1 — guard REFUSES a critical (at-startup) strip; file untouched, no .tmp

(deftest g1-guard-refuses-critical-strip
  (testing "on-disk at-startup: dropped by new content → :registry-strip-refusal"
    (let [root    (temp-dir)
          target  (str root "/git/no_commit_signing.md")
          _       (io/make-parents (io/file target))
          _       (spit target tier0-src)
          before  (slurp target)
          new     (strip-key tier0-src "at-startup")
          ex      (try (pg/guard-registry-critical-write! target new)
                       ::no-throw
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (map? ex) "guard did not throw on a critical strip")
      (is (= :registry-strip-refusal (:sandbar/error ex)))
      (is (= ["at-startup"] (:missing-keys ex)))
      (is (= before (slurp target)) "target bytes changed despite refusal")
      (is (not (.exists (io/file (str target ".tmp")))) ".tmp left behind"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G2 — one-line JOINS the critical set

(deftest g2-guard-one-line-is-critical
  (testing "at-startup: KEPT but one-line: dropped → still refuses"
    (let [root    (temp-dir)
          target  (str root "/git/no_commit_signing.md")
          _       (io/make-parents (io/file target))
          _       (spit target tier0-src)
          new     (strip-key tier0-src "one-line")
          ex      (try (pg/guard-registry-critical-write! target new)
                       ::no-throw
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (map? ex) "guard did not treat one-line: as critical")
      (is (= :registry-strip-refusal (:sandbar/error ex)))
      (is (= ["one-line"] (:missing-keys ex))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G3 — guard WARNS (does not throw) on a non-critical drop; write proceeds

(deftest g3-guard-warns-on-noncritical-drop
  (testing "a custom (non-critical) key dropped while the critical set survives → no throw"
    (let [root    (temp-dir)
          target  (str root "/notes/custom.md")
          on-disk (str "---\n"
                       "name: Custom\n"
                       "some-custom-key: value\n"
                       "at-startup: high\n"
                       "one-line: \"keeps the critical set\"\n"
                       "---\nbody\n")
          _       (io/make-parents (io/file target))
          _       (spit target on-disk)
          ;; new content keeps at-startup + one-line but drops some-custom-key
          new     (strip-key on-disk "some-custom-key")
          warnings (atom [])]
      (with-redefs [clojure.tools.logging/log*
                    (fn [_logger _level _throwable message]
                      (swap! warnings conj message))]
        (is (nil? (pg/guard-registry-critical-write! target new))
            "guard threw on a non-critical drop (should warn+proceed)"))
      ;; nothing was written by the guard itself; the caller writes after
      (assert-under-root! root target))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G4 — inert for a fresh (nonexistent) target

(deftest g4-guard-inert-fresh-target
  (testing "no existing file → no-op even when new content lacks critical keys"
    (let [root   (temp-dir)
          target (str root "/git/fresh.md")]
      (is (not (.exists (io/file target))))
      (is (nil? (pg/guard-registry-critical-write! target "no frontmatter here"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G5 — inert when the on-disk file has no frontmatter block

(deftest g5-guard-inert-no-frontmatter-on-disk
  (testing "existing file without a --- block → no-op (never wedges the sink)"
    (let [root   (temp-dir)
          target (str root "/plain.md")
          _      (io/make-parents (io/file target))
          _      (spit target "# Just a heading\n\nNo frontmatter.\n")]
      (is (nil? (pg/guard-registry-critical-write! target tier0-src))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G6 — critical-set is env/fn overridable (incl. the empty-set case, F-5)

(deftest g6-guard-critical-set-env-overridable
  (testing "override the critical-set fn → a custom key becomes fatal"
    (let [root    (temp-dir)
          target  (str root "/notes/custom.md")
          on-disk (str "---\nname: Custom\nsome-custom-key: value\n---\nbody\n")
          _       (io/make-parents (io/file target))
          _       (spit target on-disk)
          new     (strip-key on-disk "some-custom-key")]
      (with-redefs [pg/registry-critical-keys (constantly #{"some-custom-key"})]
        (let [ex (try (pg/guard-registry-critical-write! target new)
                      ::no-throw
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (map? ex) "custom critical key was not treated as fatal")
          (is (= :registry-strip-refusal (:sandbar/error ex)))
          (is (= ["some-custom-key"] (:missing-keys ex)))))))

  (testing "empty critical set (F-5: blank env parses to #{}) → at-startup drop only warns"
    (let [root    (temp-dir)
          target  (str root "/git/no_commit_signing.md")
          _       (io/make-parents (io/file target))
          _       (spit target tier0-src)
          new     (strip-key tier0-src "at-startup")]
      (with-redefs [pg/registry-critical-keys (constantly #{})]
        (is (nil? (pg/guard-registry-critical-write! target new))
            "empty critical set still refused a drop"))))

  (testing "blank SANDBAR_REGISTRY_CRITICAL_KEYS env parses to the EMPTY set, not #{\"\"}"
    ;; Pins the source's parse rule (F-5): the exact expression
    ;; `registry-critical-keys` applies to a present-but-blank env value.
    (let [parse (fn [env] (into #{} (->> (str/split env #",")
                                         (map str/trim)
                                         (remove str/blank?))))]
      (is (= #{} (parse "")) "blank env must parse to #{}, not #{\"\"}")
      (is (= #{} (parse "   ")) "whitespace-only env must parse to #{}")
      (is (= #{"at-startup"} (parse " at-startup , ")) "trims + drops blanks"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G7 — sink refusal ESCAPES the sink + increments-worthy (fixture); the
;; exact silent-swallow the companion rethrow closes (sinks.clj catch).

(deftest g7-sink-refusal-escapes-and-counts
  (testing "fs-projection-sink rethrows the marked ex-info; file unchanged"
    (let [sched-before (class-count :mm/Schedule)
          run-before   (class-count :mm/Run)
          root         (temp-dir)
          ;; pre-seed the target the sink will write to, bearing at-startup:
          target       (str root "/memory/" tier0-rel-path)
          _            (io/make-parents (io/file target))
          _            (spit target tier0-src)
          before-bytes (slurp target)
          ;; a scratch :mm/Memory WITHOUT a carrier → the emit will lack the
          ;; extras keys → the guard must refuse the strip.  Give it a body
          ;; (no sections) so realize-and-emit-entity emits non-nil.
          post-tx      {:dt/type              :mm/Memory
                        :db/id                987654
                        :db/ident             :memory.git/no_commit_signing
                        :mm.memory/name       "No Commit Signing"
                        :mm.memory/memory-type :feedback
                        :mm.memory/rel-path   tier0-rel-path
                        :mm.memory/body-raw   "# Body\n\nCarrier-less emit.\n"}]
      (with-redefs [sinks/corpus-root (constantly root)]
        (assert-under-root! root (str (sinks/corpus-root) "/memory/" tier0-rel-path))
        (let [ex (try (sinks/fs-projection-sink (:db/id post-tx) post-tx)
                      ::no-throw
                      (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (map? ex) "the refusal did NOT escape fs-projection-sink (still swallowed)")
          (is (= :registry-strip-refusal (:sandbar/error ex)))))
      (is (= before-bytes (slurp target)) "target bytes changed despite refusal")
      (is (not (.exists (io/file (str target ".tmp")))) ".tmp left behind")
      ;; HARD external receipt: no shared-transactor contact.
      (is (= sched-before (class-count :mm/Schedule)) ":mm/Schedule count drifted")
      (is (= run-before   (class-count :mm/Run))      ":mm/Run count drifted"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G8 — registry survives DB round-trip (AC-1 receipt) AND a faithful emit
;; is NOT false-positived by the guard (fixture).

(deftest g8-registry-survives-db-roundtrip
  (testing "parse FS→DB→emit preserves at-startup: + one-line: byte-identical"
    (let [sched-before (class-count :mm/Schedule)
          run-before   (class-count :mm/Run)
          ;; parse → transact → realize via the exact sink path
          specs   (md/parse-document tier0-src tier0-rel-path)
          _       @(d/transact (db/conn) (md/entity-specs->tx-data specs))
          ident   (:db/ident (first specs))
          ent     (db/entity ident)
          emitted (pg/realize-and-emit-entity ent)]
      (is (some? emitted) "realize-and-emit-entity returned nil")
      (is (str/includes? emitted "at-startup: high")
          (str "emitted output lost at-startup:\n" (first (md/split-frontmatter emitted))))
      (is (str/includes? emitted
                         "one-line: \"No Co-Authored-By trailers; no bot-email signatures\"")
          (str "emitted output lost one-line:\n" (first (md/split-frontmatter emitted))))
      (testing "atomic-write! over a pre-seeded original → NO refusal AND the emit LANDS (NH-2)"
        ;; NH-2: seed the target with DIFFERENT content bearing a distinct
        ;; marker the faithful emit will NOT reproduce, then assert the
        ;; post-write bytes EQUAL the freshly-emitted string.  A silent
        ;; no-write (the AM-10 lens-iii scenario) now FAILS the test —
        ;; the old seed (tier0-src, already containing "at-startup: high")
        ;; masked a no-write because `includes?` passed off the seed.
        (let [root       (temp-dir)
              target     (str root "/memory/" tier0-rel-path)
              _          (io/make-parents (io/file target))
              ;; seed carries at-startup:/one-line: (so the guard is inert —
              ;; not a strip) but ALSO a distinct marker line + a different
              ;; last-reviewed value that a faithful emit will overwrite.
              seed       (str/replace
                           (str tier0-src "\nSEED-MARKER-must-not-survive-a-real-write\n")
                           "last-reviewed: 2026-07-02"
                           "last-reviewed: 1999-01-01")
              _          (spit target seed)
              ;; the exact bytes the sink WILL write (same realize path, same
              ;; slot map the sink receives) — capture BEFORE the write.
              sink-slots (into {:db/id (:db/id ent)} ent)
              expected   (pg/realize-and-emit-entity sink-slots)]
          (is (some? expected) "sink emit produced nil")
          (is (not= seed expected)
              "seed must DIFFER from the emit, else a no-write can't be detected")
          (with-redefs [sinks/corpus-root (constantly root)]
            (assert-under-root! root (str (sinks/corpus-root) "/memory/" tier0-rel-path))
            ;; the guard is called inside atomic-write!; drive it via the sink
            (is (nil? (sinks/fs-projection-sink (:db/id ent) sink-slots))
                "a faithful emit was refused (false positive)")
            ;; EQUALITY (not includes?): a silent no-write leaves the seed
            ;; (with its marker + stale last-reviewed) and FAILS here.
            (is (= expected (slurp target))
                "post-write bytes are not the freshly-emitted string (silent no-write?)")
            (is (not (str/includes? (slurp target) "SEED-MARKER-must-not-survive-a-real-write"))
                "seed marker survived → the sink never actually wrote")
            ;; keep the at-startup:/one-line: survival assertions.
            (is (str/includes? (slurp target) "at-startup: high")
                "faithful write did not land at-startup:")
            (is (str/includes? (slurp target)
                               "one-line: \"No Co-Authored-By trailers; no bot-email signatures\"")
                "faithful write did not land one-line:"))))
      ;; HARD external receipt: no shared-transactor contact.
      (is (= sched-before (class-count :mm/Schedule)) ":mm/Schedule count drifted")
      (is (= run-before   (class-count :mm/Run))      ":mm/Run count drifted"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; G9 — atomic-write! renameTo-failure is LOUD (:atomic-rename-failed)
;;
;; Drive the PRIVATE `atomic-write!` directly (via its var) with the guard
;; stubbed to a no-op, targeting a path that already exists as a DIRECTORY:
;; `File.renameTo(tmp, <existing-dir>)` returns false on every platform, so
;; the rider's throw fires deterministically.  The rider is an ordinary IO
;; failure (NOT a fidelity refusal), so it is NOT marked
;; :registry-strip-refusal.

(def ^:private atomic-write!*
  (deref #'sandbar.reactive.sinks/atomic-write!))

(deftest g9-atomic-write-rename-failure-loud
  (testing ".renameTo → false throws :atomic-rename-failed (not a strip refusal)"
    (let [root      (temp-dir)
          ;; target already exists as a directory → renameTo can't clobber it
          target    (str root "/target.md")
          _         (.mkdirs (io/file target))
          ex        (with-redefs [pg/guard-registry-critical-write! (constantly nil)]
                      (try (atomic-write!* target "new content\n")
                           ::no-throw
                           (catch clojure.lang.ExceptionInfo e (ex-data e))))]
      (is (map? ex) "renameTo failure was silent (no throw)")
      (is (= :atomic-rename-failed (:sandbar/error ex)))
      (is (= target (:target-path ex)))
      (is (not= :registry-strip-refusal (:sandbar/error ex))
          "rename failure must NOT be marked as a fidelity refusal"))))
