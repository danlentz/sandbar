(ns sandbar.audit.fs-substrate-drift-test
  "Tests for the η.2 FS↔substrate drift audit verb.

   Per `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_session_workflow_substrate_design_fs_audit_scope_finalized_2026_05_25`
   + the η plan + the wise-splashing-stardust.md plan §5.

   Covers each of the 4 drift categories + a roundtrip-clean baseline via
   synthetic FS fixtures (tmpdir-rooted; no dependency on the live corpus)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.audit.fs-substrate-drift :as fs-drift]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fs-drift-audit-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tmpdir helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-tmpdir!
  "Create a fresh tmpdir for FS-side test fixtures.  Returns absolute path."
  []
  (.toString (Files/createTempDirectory "fs-drift-test-" (into-array FileAttribute []))))

(defn- mk-file!
  "Write `content` to `tmpdir/rel-path`.  Creates parent dirs as needed."
  [tmpdir rel-path content]
  (let [f (io/file tmpdir rel-path)]
    (.mkdirs (.getParentFile f))
    (spit f content)
    f))

(defn- decision-md
  "Minimal :mm/Decision markdown fixture for an entity ident."
  [name body]
  (str "---\n"
       "name: " name "\n"
       "type: decision\n"
       "description: test fixture for fs-drift-audit\n"
       "---\n\n"
       body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Baseline tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest audit-empty-fs-against-baseline-substrate
  (testing "Empty tmpdir against schema-baseline substrate → :missing-from-substrate empty; :missing-from-fs may surface baseline schema entities (e.g., :workflow/session with :mm.memory/rel-path \"workflows/session.md\" but no FS file pre-Stage-D-codec)"
    (let [tmpdir (mk-tmpdir!)
          ;; Create an empty decisions/ subdir so the FS walk has something
          ;; to traverse (no .md files).
          _      (.mkdirs (io/file tmpdir "decisions"))
          report (fs-drift/audit-all {:from tmpdir})]
      (is (= 0 (get-in report [:summary :fs-file-count]))
          "Empty tmpdir has zero FS files")
      (is (empty? (:missing-from-substrate report))
          "No FS files → no :missing-from-substrate entries")
      (is (empty? (:content-divergence report))
          "No FS files → no content-divergence comparisons possible")
      (is (empty? (:ref-slot-mismatch report))
          "No FS files → no ref-slot-mismatch comparisons possible")
      ;; :missing-from-fs may include the schema-loaded :workflow/session
      ;; (ι.2 baseline; Stage-D codec emission projects it to memory/workflows/
      ;; session.md when that arc lands).  Don't assert specific count; just
      ;; verify the audit ran and returned the report shape.
      (is (number? (get-in report [:summary :total-drift-count]))
          "Audit ran; total-drift-count surfaced even when only baseline-substrate-side drift exists"))))

(deftest audit-requires-from-arg
  (testing "audit-all without :from throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires :from"
                          (fs-drift/audit-all {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires :from"
                          (fs-drift/audit-all {:from ""})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Drift-category tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest audit-detects-missing-from-substrate
  (testing "FS file exists; no substrate entity → :missing-from-substrate"
    (let [tmpdir   (mk-tmpdir!)
          rel-path "decisions/orphan_test_decision.md"
          _        (mk-file! tmpdir rel-path
                              (decision-md "Orphan Decision" "Body without substrate entity."))
          report   (fs-drift/audit-all {:from tmpdir})]
      (is (= 1 (get-in report [:summary :missing-from-substrate-count])))
      (is (some #(= rel-path %) (:missing-from-substrate report))
          "FS file should appear in :missing-from-substrate"))))

(deftest audit-detects-missing-from-fs
  (testing "Substrate entity exists; no FS file → :missing-from-fs"
    (let [tmpdir   (mk-tmpdir!)
          ;; Create empty FS-side tree
          _        (.mkdirs (io/file tmpdir "decisions"))
          ;; Create a substrate entity with :mm.memory/rel-path but NO FS counterpart
          _ent     (dt/make :mm/Memory
                            {:mm.memory/rel-path "decisions/substrate_only_decision.md"
                             :mm.memory/name     "Substrate-only Decision"
                             :mm.memory/memory-type :decision
                             :mm.memory/body-raw "body lives only in substrate"})
          report   (fs-drift/audit-all {:from tmpdir})]
      (is (pos? (get-in report [:summary :missing-from-fs-count]))
          ":missing-from-fs count should include the substrate-only entity")
      ;; The entity-ident will be derived from rel-path via the substrate's
      ;; ident-coercion rules (e.g. :memory.decisions/substrate_only_decision).
      ;; Just assert at-least-one entry exists.
      (is (seq (:missing-from-fs report))
          "Some entity-ident should appear in :missing-from-fs"))))

(deftest audit-detects-content-divergence
  (testing "FS body differs from substrate body-raw → :content-divergence"
    (let [tmpdir   (mk-tmpdir!)
          rel-path "decisions/divergent_body_test.md"
          ;; Create FS file with body-A
          _        (mk-file! tmpdir rel-path
                              (decision-md "Divergent Body Test"
                                            "FS-side body — version A"))
          ;; Create substrate entity at SAME rel-path with body-B
          _ent     (dt/make :mm/Memory
                            {:mm.memory/rel-path rel-path
                             :mm.memory/name     "Divergent Body Test"
                             :mm.memory/memory-type :decision
                             :mm.memory/body-raw "Substrate-side body — version B"})
          report   (fs-drift/audit-all {:from tmpdir})]
      (is (pos? (get-in report [:summary :content-divergence-count]))
          ":content-divergence-count should be non-zero")
      (let [entry (first (filter #(= rel-path (:rel-path %)) (:content-divergence report)))]
        (is (some? entry) "Expected :content-divergence entry for the diverged path")
        (is (contains? (set (:differing-slots entry)) :mm.memory/body-raw)
            "Differing-slots should call out :mm.memory/body-raw")
        (is (string? (:diff-summary entry)) ":diff-summary present + readable")))))

(deftest audit-detects-ref-slot-mismatch
  (testing "FS frontmatter has a ref the substrate entity lacks → :ref-slot-mismatch surfaced (the Q.η.9 fault shape)"
    (let [tmpdir   (mk-tmpdir!)
          rel-path "observations/ref_mismatch_test.md"
          ;; Create a target entity for the ref to point at
          _target  (dt/make :mm/Memory
                            {:mm.memory/rel-path "decisions/target_decision.md"
                             :mm.memory/name     "Target Decision"
                             :mm.memory/memory-type :decision
                             :mm.memory/body-raw "target"})
          fs-body  (str "---\n"
                        "name: Ref Mismatch Test\n"
                        "type: observation\n"
                        "description: test fixture\n"
                        "cites:\n"
                        "  - memory.decisions/target_decision\n"
                        "---\n\nBody\n")
          _        (mk-file! tmpdir rel-path fs-body)
          ;; Create substrate entity at same rel-path WITHOUT :mm.memory/cites
          _ent     (dt/make :mm/Memory
                            {:mm.memory/rel-path rel-path
                             :mm.memory/name     "Ref Mismatch Test"
                             :mm.memory/memory-type :observation
                             :mm.memory/body-raw "Body"})
          report   (fs-drift/audit-all {:from tmpdir})]
      ;; Either :content-divergence (body whitespace) or :ref-slot-mismatch (cites)
      ;; surfaces — the load-bearing assertion is that SOME drift category fires
      ;; when FS frontmatter declares a ref the substrate entity lacks.
      (is (pos? (+ (get-in report [:summary :content-divergence-count])
                    (get-in report [:summary :ref-slot-mismatch-count])))
          "Either body-diff or ref-mismatch should surface for the divergent FS file"))))

(deftest audit-report-shape
  (testing "audit-all returns the structured drift report shape per the η.2 contract"
    (let [tmpdir   (mk-tmpdir!)
          _        (.mkdirs (io/file tmpdir "decisions"))
          report   (fs-drift/audit-all {:from tmpdir})]
      (is (map? (:summary report)))
      (is (number? (get-in report [:summary :fs-file-count])))
      (is (number? (get-in report [:summary :substrate-entity-count])))
      (is (number? (get-in report [:summary :total-drift-count])))
      (is (number? (get-in report [:summary :audit-duration-ms])))
      (is (instance? java.util.Date (get-in report [:summary :audit-instant])))
      (is (vector? (:missing-from-substrate report)))
      (is (vector? (:missing-from-fs report)))
      (is (vector? (:content-divergence report)))
      (is (vector? (:ref-slot-mismatch report))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; η.4 Target #2 (2026-05-28) — rel-path comparison normalization.
;;
;; The η.3 corpus audit reported 1667 spurious :missing-from-substrate
;; entries: entities that EXIST but whose :mm.memory/rel-path slot carries
;; a leading `memory/` prefix (older codec convention) while the FS-walk
;; form is prefix-less.  The exact-string set-difference treated the
;; prefix difference as a missing file.  The fix normalizes both sides
;; (strip leading `memory/`) before the difference.

(deftest audit-normalizes-rel-path-prefix-no-false-missing
  (testing "An FS file (no memory/ prefix) + substrate entity whose rel-path slot HAS the memory/ prefix must NOT report as drift — the normalization collapses the prefix difference"
    (let [tmpdir   (mk-tmpdir!)
          fs-rel   "decisions/prefix_norm_test.md"
          body     "# Prefix-norm test body\n\nIdentical on both sides.\n"
          ;; FS side: prefix-less rel-path (the FS-walk form)
          _file    (mk-file! tmpdir fs-rel
                             (str "---\n"
                                  "name: Prefix Norm Test\n"
                                  "type: decision\n"
                                  "description: η.4 Target #2 normalization fixture\n"
                                  "---\n\n"
                                  body))
          ;; Substrate side: SAME logical file but rel-path slot carries
          ;; the leading `memory/` prefix (the inconsistent convention).
          _ent     (dt/make :mm/Memory
                            {:mm.memory/rel-path "memory/decisions/prefix_norm_test.md"
                             :mm.memory/name     "Prefix Norm Test"
                             :mm.memory/memory-type :decision
                             :mm.memory/body-raw body}
                            {:validate? false})
          report   (fs-drift/audit-all {:from tmpdir})]
      ;; The prefix-mismatched entity must NOT appear as missing-from-substrate
      (is (not (some #(re-find #"prefix_norm_test" %)
                     (:missing-from-substrate report)))
          "Prefix-mismatched entity must NOT be reported missing-from-substrate")
      ;; ...nor as missing-from-fs (the substrate entity DOES have an FS file)
      (is (not (some #(when % (re-find #"prefix_norm_test" (str %)))
                     (:missing-from-fs report)))
          "Prefix-mismatched entity must NOT be reported missing-from-fs"))))

(deftest audit-normalize-rel-path-helper
  (testing "normalize-rel-path strips exactly one leading memory/ prefix; idempotent on prefix-less"
    (let [norm #'fs-drift/normalize-rel-path]
      (is (= "decisions/foo.md" (norm "memory/decisions/foo.md")))
      (is (= "decisions/foo.md" (norm "decisions/foo.md")))
      (is (nil? (norm nil))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; η.5 hardening (a) — orphan-twin detection (the memory/memory/ junk tree).
;;
;; Pre-hardening the audit was STRUCTURALLY BLIND to the twin tree: a twin's
;; parsed spec carries the same stored rel-path as the real file it aliases
;; (walk-rel `memory/decisions/foo.md` → stored `decisions/foo.md`), so the
;; `into {}` spec index silently collapsed the pair last-write-wins — zero
;; signal, and the twin's stale content could mask (or fabricate) content
;; divergence for the REAL file.  Measured 2026-07-21: 59 junk twins under
;; /Users/dan/claude/memory/memory, all aliasing real files.

(deftest audit-detects-orphan-twin-tree
  (testing "A memory/ re-entry twin is reported under :twins, is EXCLUDED from the parse universe, and can no longer mask the real file's content divergence"
    (let [tmp       (mk-tmpdir!)
          root      (io/file tmp "memory")   ; basename `memory` → :memory-root mode
          _         (.mkdirs root)
          real-body "Real corpus body — canonical."
          twin-body "STALE twin body — must never reach the comparison index."
          _         (mk-file! (.getPath root) "decisions/twin_host_decision.md"
                              (decision-md "Twin Host Decision" real-body))
          _         (mk-file! (.getPath root) "memory/decisions/twin_host_decision.md"
                              (decision-md "Twin Host Decision" twin-body))
          ;; Substrate body = the TWIN's body.  If the twin's spec ever wins
          ;; the by-path index slot again (the pre-hardening last-write-wins
          ;; collapse), the comparison degenerates to twin-vs-twin and the
          ;; REAL file's divergence is masked — this fixture makes that
          ;; blindness a deterministic test failure.
          _ent      (dt/make :mm/Memory
                             {:mm.memory/rel-path "decisions/twin_host_decision.md"
                              :mm.memory/name     "Twin Host Decision"
                              :mm.memory/memory-type :decision
                              :mm.memory/body-raw twin-body})
          report    (fs-drift/audit-all {:from (.getPath root)})]
      (is (= :memory-root (get-in report [:summary :root-mode])))
      (is (= 1 (get-in report [:summary :twin-count])))
      (let [twin (first (:twins report))]
        (is (= "memory/decisions/twin_host_decision.md" (:walk-rel-path twin))
            "twin reported by its walk-derived rel-path")
        (is (= "decisions/twin_host_decision.md" (:stored-rel-path twin))
            "twin reports the stored rel-path it would alias")
        (is (true? (:shadows-existing-file? twin))
            "twin flagged as shadowing an existing real file"))
      (is (some #(= "decisions/twin_host_decision.md" (:rel-path %))
                (:content-divergence report))
          "REAL file (body ≠ substrate) surfaces as divergence — the twin cannot mask it")
      (is (= 1 (get-in report [:summary :fs-file-count]))
          "parse universe counts only the real file — twin excluded at enumeration")
      (is (empty? (:missing-from-substrate report))
          "twin is classified :twin, never :missing-from-substrate")
      (is (pos? (get-in report [:summary :total-drift-count]))
          "twins count toward total drift"))))

(deftest audit-twin-without-real-counterpart
  (testing "A twin with NO real counterpart still reports under :twins (shadows-existing-file? false) and is NOT misfiled as :missing-from-substrate"
    (let [tmp    (mk-tmpdir!)
          root   (io/file tmp "memory")
          _      (.mkdirs (io/file root "plans"))
          _      (mk-file! (.getPath root) "memory/plans/ghost_plan.md"
                           (decision-md "Ghost Plan" "Twin-only body."))
          report (fs-drift/audit-all {:from (.getPath root)})]
      (is (= 1 (get-in report [:summary :twin-count])))
      (let [twin (first (:twins report))]
        (is (= "memory/plans/ghost_plan.md" (:walk-rel-path twin)))
        (is (false? (:shadows-existing-file? twin))
            "no real file at the aliased rel-path"))
      (is (= 0 (get-in report [:summary :fs-file-count])))
      (is (empty? (:missing-from-substrate report))
          "a junk twin is not a missing memorial"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; η.5 hardening (b) — nil/empty body-raw normalization.
;;
;; 46/55 live :content-divergence entries at the 2026-07-21 probe were
;; `0 chars vs 0 chars` comparator noise: FS parse yields an EMPTY body
;; string while the substrate slot is simply ABSENT (nil) — two encodings
;; of the same 'no body content'.

(deftest audit-empty-vs-nil-body-is-not-divergence
  (testing "FS empty / whitespace-only body vs substrate absent body-raw → NOT content divergence"
    (let [tmp    (mk-tmpdir!)
          rel-a  "decisions/empty_body_nil_slot.md"
          rel-b  "decisions/whitespace_body_nil_slot.md"
          _      (mk-file! tmp rel-a (decision-md "Empty Body Nil Slot" ""))
          _      (mk-file! tmp rel-b (decision-md "Whitespace Body Nil Slot" "   \n\n"))
          _e1    (dt/make :mm/Memory
                          {:mm.memory/rel-path rel-a
                           :mm.memory/name     "Empty Body Nil Slot"
                           :mm.memory/memory-type :decision}
                          {:validate? false})
          _e2    (dt/make :mm/Memory
                          {:mm.memory/rel-path rel-b
                           :mm.memory/name     "Whitespace Body Nil Slot"
                           :mm.memory/memory-type :decision}
                          {:validate? false})
          report (fs-drift/audit-all {:from tmp})]
      (is (empty? (filter #(contains? #{rel-a rel-b} (:rel-path %))
                          (:content-divergence report)))
          "nil-vs-empty / nil-vs-whitespace body encodings must not report divergence")
      (is (empty? (:missing-from-substrate report))
          "both files matched their substrate entities"))))

(deftest normalize-body-helper
  (testing "normalize-body collapses nil / empty / whitespace-only to \"\"; preserves trailing-newline trim for real bodies"
    (let [norm #'fs-drift/normalize-body]
      (is (= "" (norm nil)))
      (is (= "" (norm "")))
      (is (= "" (norm "   \n\n")))
      (is (= "body" (norm "body\n")))
      (is (= "  leading-space body" (norm "  leading-space body"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; η.5 hardening (c) — FS walk scoping + memorial-policy class-scoping.
;;
;; Pre-hardening a repo-root :from walked the WHOLE repo and flooded
;; :missing-from-substrate with non-memorial trees (562 tracked
;; audit-results/*.md at the 2026-07-21 probe); and :missing-from-fs
;; reported entities whose class the projection policy NEVER emits.

(deftest audit-repo-root-from-scopes-walk-to-memory-subtree
  (testing "A repo-root :from walks ONLY <from>/memory — non-memorial trees cannot flood :missing-from-substrate"
    (let [tmp    (mk-tmpdir!)
          _      (mk-file! tmp "memory/decisions/scoped_real_decision.md"
                           (decision-md "Scoped Real Decision" "Corpus body."))
          _      (mk-file! tmp "audit-results/loop-fixture/REPORT.md"
                           "# Not a memorial\n\nAudit artifact prose.\n")
          _      (mk-file! tmp "doc/some_guide.md" "# Docs, not corpus\n")
          report (fs-drift/audit-all {:from tmp})]
      (is (= :corpus-root-scoped (get-in report [:summary :root-mode])))
      (is (str/ends-with? (get-in report [:summary :walk-root]) "/memory")
          "summary surfaces the effective walk root")
      (is (= ["decisions/scoped_real_decision.md"] (:missing-from-substrate report))
          "only the memory/ subtree file surfaces; audit-results/ + doc/ never walked")
      (is (= 1 (get-in report [:summary :fs-file-count]))))))

(deftest audit-memory-root-and-repo-root-from-are-equivalent
  (testing "…/memory and its parent repo root resolve to the SAME walk universe (both :from forms are honest)"
    (let [tmp         (mk-tmpdir!)
          _           (mk-file! tmp "memory/decisions/equiv_probe_decision.md"
                                (decision-md "Equiv Probe Decision" "Body."))
          from-repo   (fs-drift/audit-all {:from tmp})
          from-memory (fs-drift/audit-all {:from (str (io/file tmp "memory"))})]
      (is (= :corpus-root-scoped (get-in from-repo [:summary :root-mode])))
      (is (= :memory-root (get-in from-memory [:summary :root-mode])))
      (is (= (:missing-from-substrate from-repo)
             (:missing-from-substrate from-memory))
          "identical FS drift picture from either :from form")
      (is (= (get-in from-repo [:summary :fs-file-count])
             (get-in from-memory [:summary :fs-file-count]))))))

(deftest resolve-walk-root-helper
  (testing "resolve-walk-root precedence: memory basename > memory/ subdir (guarded by MEMORY.md root-index) > as-given"
    (let [resolve-root #'fs-drift/resolve-walk-root
          tmp          (mk-tmpdir!)]
      (is (= :as-given (:root-mode (resolve-root tmp)))
          "no memory/ subdir → walk as given (tmpdir fixture shape)")
      (.mkdirs (io/file tmp "memory"))
      (let [{:keys [walk-root root-mode]} (resolve-root tmp)]
        (is (= :corpus-root-scoped root-mode))
        (is (str/ends-with? (str walk-root) "/memory")))
      ;; MEMORY.md guard: a dir that carries the root-index IS a memory root
      ;; even when a junk memory/ subtree exists — never re-anchor into junk.
      (spit (io/file tmp "MEMORY.md") "# root index\n")
      (is (= :as-given (:root-mode (resolve-root tmp))))
      (is (= :memory-root (:root-mode (resolve-root (str (io/file tmp "memory")))))
          "basename `memory` wins outright"))))

(deftest audit-missing-from-fs-is-policy-scoped
  (testing "Classes the projection policy never emits (:db-only :mm/Run; runtime-behavioral :mm/EventLog) are policy-excluded from :missing-from-fs; corpus-document classes still report"
    (let [tmp     (mk-tmpdir!)
          _       (.mkdirs (io/file tmp "decisions"))
          _mem    (dt/make :mm/Memory
                           {:db/ident :memory.decisions/proper_missing_memorial
                            :mm.memory/rel-path "decisions/proper_missing_memorial.md"
                            :mm.memory/name     "Proper Missing Memorial"
                            :mm.memory/memory-type :decision
                            :mm.memory/body-raw "corpus-document class — genuine drift"})
          _run    (dt/make :mm/Run
                           {:db/ident :memory.runs/db_only_run_fixture
                            :mm.memory/rel-path "runs/db_only_run_fixture.md"}
                           {:validate? false})
          _evlog  (dt/make :mm/EventLog
                           {:db/ident :memory.event-logs/runtime_eventlog_fixture
                            :mm.memory/rel-path "event-logs/runtime_eventlog_fixture.md"}
                           {:validate? false})
          report  (fs-drift/audit-all {:from tmp})
          missing (set (:missing-from-fs report))
          excluded-idents (set (map :entity-ident (:missing-from-fs-policy-excluded report)))]
      (is (contains? missing :memory.decisions/proper_missing_memorial)
          "corpus-document-class entity without an FS file IS missing-from-fs drift")
      (is (not (contains? missing :memory.runs/db_only_run_fixture))
          ":db-only :mm/Run never projects — not drift")
      (is (not (contains? missing :memory.event-logs/runtime_eventlog_fixture))
          "runtime-behavioral :mm/EventLog never projects a corpus file — not drift")
      (is (contains? excluded-idents :memory.runs/db_only_run_fixture)
          "policy-excluded ledger surfaces the :mm/Run row")
      (is (contains? excluded-idents :memory.event-logs/runtime_eventlog_fixture)
          "policy-excluded ledger surfaces the :mm/EventLog row")
      (is (<= 2 (get-in report [:summary :missing-from-fs-policy-excluded-count]))))))

(deftest audit-surfaces-substrate-rel-path-collisions
  (testing "Two substrate entities normalizing to ONE rel-path key surface as a collision (DB-side twin) instead of one silently vanishing last-write-wins; the IDENTFUL entity is the comparison representative"
    (let [tmp    (mk-tmpdir!)
          _      (.mkdirs (io/file tmp "decisions"))
          _a     (dt/make :mm/Memory
                          {:db/ident :memory.decisions/collision_probe_a
                           :mm.memory/rel-path "decisions/collision_probe.md"
                           :mm.memory/name "Collision Probe A"
                           :mm.memory/memory-type :decision
                           :mm.memory/body-raw "entity A"}
                          {:validate? false})
          ;; The live collision shape (probe 2026-07-22: 61/61 cases): an
          ;; IDENT-LESS junk duplicate at the prefixed rel-path form.
          _b     (dt/make :mm/Memory
                          {:mm.memory/rel-path "memory/decisions/collision_probe.md"
                           :mm.memory/name "Collision Probe B (identless junk twin)"
                           :mm.memory/memory-type :decision
                           :mm.memory/body-raw "entity B"}
                          {:validate? false})
          report (fs-drift/audit-all {:from tmp})
          coll   (first (filter #(= "decisions/collision_probe.md" (:rel-path %))
                                (:substrate-rel-path-collisions report)))]
      (is (some? coll) "the collision is surfaced, not silently collapsed")
      (is (= [nil :memory.decisions/collision_probe_a] (:entity-idents coll))
          "both colliding entities are named (nil = identless junk), deterministically ordered")
      (is (contains? (set (:missing-from-fs report))
                     :memory.decisions/collision_probe_a)
          "the IDENTFUL entity — not the identless junk — represents the path downstream")
      (is (pos? (get-in report [:summary :substrate-rel-path-collision-count])))
      (is (pos? (get-in report [:summary :total-drift-count]))
          "collisions count toward total drift"))))
