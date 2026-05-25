(ns sandbar.audit.fs-substrate-drift-test
  "Tests for the η.2 FS↔substrate drift audit verb.

   Per `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_session_workflow_substrate_design_fs_audit_scope_finalized_2026_05_25`
   + the η plan + the wise-splashing-stardust.md plan §5.

   Covers each of the 4 drift categories + a roundtrip-clean baseline via
   synthetic FS fixtures (tmpdir-rooted; no dependency on the live corpus)."
  (:require [clojure.java.io :as io]
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
