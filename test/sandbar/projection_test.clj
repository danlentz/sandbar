(ns sandbar.projection-test
  "Tests for sandbar.projection — project-graph + ingest-graph
   primitives.  Uses temp directories for the round-trip write/read
   cycle.

   Stage C refactor (plans/sandbar_codex_review_remediation_arc_2026_05_13.md):
   codec.markdown now introspects keyword-typed slots via dt/range-of
   at runtime (no hardcoded known-class-keyword-slots map), so these
   tests require a real metamodel — uses tu/make-test-db-fixture to
   load schema/*.edn including schema/mm.edn."
  (:require [clojure.test          :refer :all]
            [clojure.java.io       :as io]
            [clojure.string        :as str]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl]
            [sandbar.projection :as pg]
            [sandbar.codec.markdown :as md]
            [sandbar.test-util     :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — DB with full schema (codec.markdown introspects ranges)

(use-fixtures :each (tu/make-test-db-fixture {:test-name "project-graph-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Temp-dir fixture helpers

(defn- fresh-tmp-dir
  "Create + return a fresh temp directory.  Caller cleans up."
  ^java.io.File []
  (let [parent (java.io.File/createTempFile "pg-test-" "")]
    (.delete parent)
    (.mkdirs parent)
    parent))

(defn- rm-rf!
  [^java.io.File f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (rm-rf! child)))
  (.delete f))

(defmacro with-tmp-dir
  [bindings & body]
  (assert (and (vector? bindings) (= 2 (count bindings)))
          "with-tmp-dir takes [name init] binding")
  (let [[name _] bindings]
    `(let [~name (fresh-tmp-dir)]
       (try ~@body
            (finally (rm-rf! ~name))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sample entity-spec data

(defn- simple-memory
  "A frontmatter-only mm/Decision entity (no sections).
   Post-2026-05-21: `type: decision` routes to :mm/Decision (subclass of :mm/Memory)
   via :dt/codec-type-keyword.  Codec walks ancestors for slot inheritance
   so :mm.memory/* slots remain the canonical home of name/rel-path/etc."
  []
  {:dt/type :mm/Decision
   :db/ident :decisions/foo
   :mm.memory/rel-path "decisions/foo.md"
   :mm.memory/name "Foo Decision"
   :mm.memory/memory-type :decision
   :mm.memory/body-raw ""})

(defn- memory-with-sections
  "An mm/Decision (subclass of mm/Memory) with two top-level sections.
   Post-2026-05-21: `type: decision` routes to :mm/Decision via codec."
  []
  (let [memory-ident :decisions/bar
        ctx-ident    :decisions/bar__context
        dec-ident    :decisions/bar__decision]
    [{:dt/type :mm/Decision
      :db/ident memory-ident
      :mm.memory/rel-path "decisions/bar.md"
      :mm.memory/name "Bar Decision"
      :mm.memory/memory-type :decision
      :mm.memory/first-section ctx-ident
      :mm.memory/body-raw ""}
     {:dt/type :mm/Section
      :db/ident ctx-ident
      :mm.section/heading "Context"
      :mm.section/heading-level 2
      :mm.section/parent memory-ident
      :mm.section/next-sibling dec-ident
      :mm.section/body "Context body.\n"}
     {:dt/type :mm/Section
      :db/ident dec-ident
      :mm.section/heading "Decision"
      :mm.section/heading-level 2
      :mm.section/parent memory-ident
      :mm.section/previous-sibling ctx-ident
      :mm.section/body "Decision body.\n"}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; project-graph — writes files

(deftest project-graph-writes-single-memory
  (with-tmp-dir [dir nil]
    (let [result (pg/project-graph [(simple-memory)] {:to dir})]
      (is (= [{:rel-path "decisions/foo.md" :written true}] result))
      (let [file (io/file dir "decisions/foo.md")]
        (is (.exists file))
        (is (str/includes? (slurp file) "name: Foo Decision"))))))

(deftest project-graph-creates-nested-directories
  (with-tmp-dir [dir nil]
    (let [entity (assoc (simple-memory)
                        :mm.memory/rel-path "patterns/architectural/sandbar/x.md"
                        :db/ident :patterns.architectural.sandbar/x)]
      (pg/project-graph [entity] {:to dir})
      (is (.exists (io/file dir "patterns/architectural/sandbar/x.md"))))))

(deftest project-graph-writes-memory-with-sections
  (with-tmp-dir [dir nil]
    (let [entities (memory-with-sections)]
      (pg/project-graph entities {:to dir})
      (let [content (slurp (io/file dir "decisions/bar.md"))]
        (is (str/includes? content "name: Bar Decision"))
        (is (str/includes? content "## Context"))
        (is (str/includes? content "## Decision"))
        (is (str/includes? content "Context body"))
        (is (str/includes? content "Decision body"))))))

(deftest project-graph-skips-entities-without-rel-path
  (with-tmp-dir [dir nil]
    (let [memory   (simple-memory)
          orphan   (dissoc memory :mm.memory/rel-path)
          result   (pg/project-graph [orphan] {:to dir})]
      (is (empty? result) "Entities without rel-path are skipped"))))

(deftest project-graph-rejects-missing-to-opt
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":to"
        (pg/project-graph [(simple-memory)] {}))))

(deftest project-graph-is-idempotent
  ;; Re-projecting the same entities to the same dir yields byte-
  ;; identical files per the codec's normalization invariants.
  (with-tmp-dir [dir nil]
    (let [entities (memory-with-sections)
          _        (pg/project-graph entities {:to dir})
          content1 (slurp (io/file dir "decisions/bar.md"))
          _        (pg/project-graph entities {:to dir})
          content2 (slurp (io/file dir "decisions/bar.md"))]
      (is (= content1 content2)
          "Second project produces identical byte content (idempotence)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ingest-graph — reads files back

(deftest ingest-graph-reads-single-file
  (with-tmp-dir [dir nil]
    (let [orig (simple-memory)
          _    (pg/project-graph [orig] {:to dir})
          back (pg/ingest-graph dir)]
      (is (= 1 (count back)))
      (is (= :mm/Decision (-> back first :dt/type)))
      (is (= "Foo Decision" (-> back first :mm.memory/name)))
      (is (= "decisions/foo.md" (-> back first :mm.memory/rel-path))))))

(deftest ingest-graph-reads-nested-directories
  (with-tmp-dir [dir nil]
    (let [entity (assoc (simple-memory)
                        :mm.memory/rel-path "patterns/architectural/sandbar/x.md"
                        :db/ident :patterns.architectural.sandbar/x)
          _      (pg/project-graph [entity] {:to dir})
          back   (pg/ingest-graph dir)]
      (is (= :patterns.architectural.sandbar/x
             (-> back first :db/ident))))))

(deftest ingest-graph-rejects-non-directory
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"directory"
        (pg/ingest-graph "/nonexistent-path-xxx"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip — project + ingest preserves entity-spec data

(deftest round-trip-simple-memory
  (let [result (pg/round-trip-test [(simple-memory)])]
    (is (true? (:ok? result))
        (str "round-trip diff: " (pr-str (:diff result))))))

(deftest round-trip-memory-with-sections
  (let [entities (memory-with-sections)
        result   (pg/round-trip-test entities)]
    (is (true? (:ok? result))
        (str "round-trip diff: " (pr-str (:diff result))))))

(deftest round-trip-multiple-memories
  ;; Two independent mm/Memory entities; project + ingest both
  (let [m1 (assoc (simple-memory) :mm.memory/rel-path "decisions/m1.md"
                                  :db/ident :decisions/m1)
        m2 (assoc (simple-memory) :mm.memory/rel-path "bugs/m2.md"
                                  :db/ident :bugs/m2)
        result (pg/round-trip-test [m1 m2])]
    (is (true? (:ok? result))
        (str "multi-memory round-trip diff: " (pr-str (:diff result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage G Signal 2 — Filtering on project.export + ingest.import

(deftest entity-passes-filter-class
  (let [m (simple-memory)]
    (is (true?  (pg/entity-passes-filter? m {:class :mm/Memory})))
    (is (false? (pg/entity-passes-filter? m {:class :mm/Section})))))

(deftest entity-passes-filter-classes-set
  (let [m (simple-memory)]
    (is (true?  (pg/entity-passes-filter? m {:classes #{:mm/Memory :mm/Tag}})))
    (is (false? (pg/entity-passes-filter? m {:classes #{:mm/Tag}})))))

(deftest entity-passes-filter-tree-filter
  (let [m1 (assoc (simple-memory) :mm.memory/rel-path "decisions/m1.md")
        m2 (assoc (simple-memory) :mm.memory/rel-path "bugs/m2.md")]
    (is (true?  (pg/entity-passes-filter? m1 {:tree-filter "decisions/"})))
    (is (false? (pg/entity-passes-filter? m2 {:tree-filter "decisions/"})))))

(deftest entity-passes-filter-pred
  (let [m (assoc (simple-memory) :mm.memory/memory-type :decision)]
    (is (true?  (pg/entity-passes-filter? m {:pred #(= :decision (:mm.memory/memory-type %))})))
    (is (false? (pg/entity-passes-filter? m {:pred #(= :bug (:mm.memory/memory-type %))})))))

(deftest entity-passes-filter-composes-via-and
  (let [m (assoc (simple-memory)
                 :mm.memory/rel-path "decisions/m1.md"
                 :mm.memory/memory-type :decision)]
    (is (true?  (pg/entity-passes-filter? m {:class :mm/Memory
                                              :tree-filter "decisions/"
                                              :pred #(= :decision (:mm.memory/memory-type %))})))
    (is (false? (pg/entity-passes-filter? m {:class :mm/Memory
                                              :tree-filter "bugs/"})))))

(deftest project-graph-applies-filter
  (with-tmp-dir [dir nil]
    (let [m1 (assoc (simple-memory) :mm.memory/rel-path "decisions/m1.md"
                                    :db/ident :decisions/m1)
          m2 (assoc (simple-memory) :mm.memory/rel-path "bugs/m2.md"
                                    :db/ident :bugs/m2)
          result (pg/project-graph [m1 m2] {:to dir :filter {:tree-filter "decisions/"}})]
      (is (= 1 (count result)))
      (is (= "decisions/m1.md" (-> result first :rel-path)))
      (is (.exists (io/file dir "decisions/m1.md")))
      (is (not (.exists (io/file dir "bugs/m2.md")))))))

(deftest ingest-graph-applies-tree-filter
  (with-tmp-dir [dir nil]
    (let [m1 (assoc (simple-memory) :mm.memory/rel-path "decisions/m1.md"
                                    :db/ident :decisions/m1)
          m2 (assoc (simple-memory) :mm.memory/rel-path "bugs/m2.md"
                                    :db/ident :bugs/m2)]
      (pg/project-graph [m1 m2] {:to dir})
      (let [back (pg/ingest-graph dir {:filter {:tree-filter "decisions/"}})]
        (is (= 1 (count back)))
        (is (= :decisions/m1 (-> back first :db/ident)))))))

(deftest project-graph-filter-preserves-sections-under-matching-memory
  (with-tmp-dir [dir nil]
    (let [entities (memory-with-sections)
          result (pg/project-graph entities {:to dir :filter {:class :mm/Memory}})]
      ;; Even though :class :mm/Memory filters out mm/Section entities from
      ;; the input directly, the project-graph implementation reattaches
      ;; sections of matching memories before emitting.  Result should
      ;; still contain the memory + its sections in the file.
      (is (= 1 (count result)) "one memory written")
      (let [content (slurp (io/file dir "decisions/bar.md"))]
        (is (str/includes? content "## Context"))
        (is (str/includes? content "## Decision"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Regression — import :tree-filter double-fork (walk-rel vs stored-rel)
;; Bug: bugs/project_import_tree_filter_double_fork_walk_rel_vs_stored_rel_path_2026_07_02.md
;;
;; ingest-graph applied :tree-filter at TWO points against DIFFERENT targets:
;;   fork 1 (walk-optimization) matched the WALK-relative path — `memory/…`-
;;     prefixed when :from is the corpus root;
;;   fork 2 (entity-passes-filter?) matched the entity's STORED
;;     :mm.memory/rel-path — UNPREFIXED post-D2 (parse-document strips
;;     `^memory/`, codec/markdown.clj:1847).
;; Under a corpus-root anchor no single prefix satisfied both, so a
;; subdir-scoped filter returned imported=0 SILENTLY (F12 fail-silent).

(defn- with-captured-log-warns
  "Run `body-fn` while capturing every clojure.tools.logging warn-level
   call into an atom.  Returns `[result captured]` where `captured` is a
   vector of the raw `message` args passed to `log/warn`.  Used to assert
   the F12 LOUD-empty warning fires when a non-nil tree-filter selects
   nothing (mirrors the log-appender binding in mcp/notifications_test)."
  [body-fn]
  (let [captured (atom [])
        factory  (reify clojure.tools.logging.impl/LoggerFactory
                   (name [_] "captured")
                   (get-logger [_ _logger-ns]
                     (reify clojure.tools.logging.impl/Logger
                       (enabled? [_ _] true)
                       (write! [_ level _throwable message]
                         (when (= :warn level)
                           (swap! captured conj message))))))]
    (binding [log/*logger-factory* factory]
      [(body-fn) @captured])))

(defn- project-under-memory-subdir!
  "Project `entities` into `dir/memory/…` so a walk anchored at `dir`
   yields `memory/…`-prefixed walk-rel-paths (the corpus-root-anchor
   shape).  Returns the `dir/memory` File the entities were written to."
  [dir entities]
  (let [mem-dir (io/file dir "memory")]
    (.mkdirs mem-dir)
    (pg/project-graph entities {:to mem-dir})
    mem-dir))

(deftest ingest-graph-tree-filter-matches-under-root-anchor
  ;; RED on pre-fix code: walk-rel `memory/decisions/m1.md` fails the
  ;; `(str/starts-with? rel-path "decisions/")` optimization, so the file
  ;; is never parsed and `back` is empty.  GREEN once fork 1 strips the
  ;; walk-anchor `memory/` prefix before the filter comparison, agreeing
  ;; with the stored `decisions/m1.md` form fork 2 already tests.
  (with-tmp-dir [dir nil]
    (let [m1 (assoc (simple-memory) :mm.memory/rel-path "decisions/m1.md"
                                    :db/ident :decisions/m1)
          m2 (assoc (simple-memory) :mm.memory/rel-path "bugs/m2.md"
                                    :db/ident :bugs/m2)]
      (project-under-memory-subdir! dir [m1 m2])
      ;; Anchor the WALK at `dir` (the "corpus root"): walk-rel-paths are
      ;; `memory/decisions/m1.md` + `memory/bugs/m2.md`.
      (let [back (pg/ingest-graph dir {:filter {:tree-filter "decisions/"}})]
        (is (= 1 (count back))
            "subdir tree-filter selects exactly the one file under decisions/")
        ;; Post-D2 the minted ident carries the `memory.` prefix (D2 canon
        ;; via rel-path->memory-ident; INVENTORY caveat (b)).
        (is (= :memory.decisions/m1 (-> back first :db/ident))
            "the selected file's entity is the D2 memory.-prefixed ident")
        (is (= "decisions/m1.md" (-> back first :mm.memory/rel-path))
            "stored rel-path is the unprefixed D2 form")))))

(deftest ingest-graph-tree-filter-empty-result-is-loud
  ;; F12 lens: a non-nil tree-filter that yields imported=0 must NOT be
  ;; silent.  RED on pre-fix code — the double-fork returns [] with no
  ;; signal.  GREEN once ingest-graph emits a :warn when a non-nil
  ;; tree-filter selects nothing.
  (with-tmp-dir [dir nil]
    (let [m1 (assoc (simple-memory) :mm.memory/rel-path "decisions/m1.md"
                                    :db/ident :decisions/m1)]
      (project-under-memory-subdir! dir [m1])
      (let [[back warns]
            (with-captured-log-warns
              #(pg/ingest-graph dir {:filter {:tree-filter "no-such-dir/"}}))]
        (is (empty? back) "no file matches the bogus filter")
        (is (some (fn [msg]
                    (str/includes? (str msg) "no-such-dir/"))
                  warns)
            "a warn names the tree-filter that selected nothing (F12 LOUD)")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Regression — import cannot select corpus ROOT files (MEMORY.md / README.md)
;; Bug: bugs/project_import_cannot_select_root_files_memory_md_readme_bare_ident_stubs_2026_07_02.md
;;
;; The two corpus root files were unreachable by the sanctioned import walk:
;;   (1) walk-markdown-files unconditionally skips README.md + MEMORY.md via
;;       +default-skip-basenames+, so no filter form enumerates them;
;;   (2) ingest-graph rejected a FILE :from with "requires a directory input".
;; Net: the :memory/MEMORY + :memory/README bare-ident STUBS (no rel-path,
;; no :dt/type) that D2 re-import heals everywhere else could not be reached
;; — imported=0 for every filter form; :from=<file> ERRORed.
;; Fix: a file :from parses that single file directly (basename as rel-path),
;; bypassing the walk + skip-set — the explicit MEMORY/README heal path.

(defn- write-md-file!
  "Emit `entity` (an mm/Memory entity-spec) as a single markdown file at
   `dir/rel-path` via the codec, then return that File.  Reuses
   project-graph's default hierarchy-fn (reads :mm.memory/rel-path), so the
   on-disk file is byte-identical to what the corpus stores."
  ^java.io.File [dir rel-path entity]
  (pg/project-graph [(assoc entity :mm.memory/rel-path rel-path)] {:to dir})
  (io/file dir rel-path))

(deftest ingest-graph-file-from-heals-root-level-md
  ;; RED on pre-fix code: `ingest-graph` throws "requires a directory input"
  ;; for a file :from, AND even a dir :from can never enumerate MEMORY.md
  ;; (skip-set).  GREEN once a file :from parses the single named file
  ;; directly — the explicit heal path for the corpus root files.
  (with-tmp-dir [dir nil]
    (let [root-file (write-md-file! dir "MEMORY.md"
                                    (assoc (simple-memory)
                                           :db/ident :memory/MEMORY))
          back (pg/ingest-graph root-file {})]
      (is (= 1 (count back))
          "a file :from on a root-level .md yields exactly one healed entity")
      (let [healed (first back)]
        ;; The heal target's canonical ident is the bare :memory/MEMORY (a
        ;; root-level basename mints ns=`memory`), matching the live stub
        ;; eid 17592186068437 the memorial names — NOT retracted, HEALED.
        (is (= :memory/MEMORY (:db/ident healed))
            "root basename MEMORY.md mints the canonical :memory/MEMORY ident")
        ;; The stub carried NO rel-path + NO :dt/type; the heal restores both.
        (is (= "MEMORY.md" (:mm.memory/rel-path healed))
            "heal restores the (unprefixed) stored rel-path the stub lacked")
        (is (some? (:dt/type healed))
            "heal restores the :dt/type the bare-ident stub lacked")))))

(deftest ingest-graph-file-from-uses-basename-as-rel-path
  ;; A file :from is a general single-file ingest, not a MEMORY/README
  ;; special-case.  The selector knows only the file — it has no corpus
  ;; anchor to recover a subtree prefix from — so it derives the rel-path
  ;; from the file's BASENAME (the honest contract for a bare file).  For a
  ;; root-level heal target (MEMORY.md/README.md) the basename IS the
  ;; corpus rel-path, which is exactly why this heals the two root stubs.
  (with-tmp-dir [dir nil]
    (let [f (write-md-file! dir "decisions/m1.md"
                            (assoc (simple-memory) :db/ident :decisions/m1))
          back (pg/ingest-graph f {})]
      (is (= 1 (count back)) "a file :from parses exactly the one named file")
      ;; basename `m1.md` → ns `memory` (root-level derivation).  A subtree
      ;; file passed BARE loses its subtree prefix — callers wanting the
      ;; subtree ident must pass the subtree dir as :from, not the file.
      (is (= :memory/m1 (-> back first :db/ident))
          "single-file :from derives the ident from the file basename")
      (is (= "m1.md" (-> back first :mm.memory/rel-path))
          "stored rel-path is the file basename"))))