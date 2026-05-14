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
  "A frontmatter-only mm/Memory entity (no sections)."
  []
  {:dt/type :mm/Memory
   :db/ident :decisions/foo
   :mm.memory/rel-path "decisions/foo.md"
   :mm.memory/name "Foo Decision"
   :mm.memory/memory-type :decision
   :mm.memory/body-raw ""})

(defn- memory-with-sections
  "An mm/Memory with two top-level sections."
  []
  (let [memory-ident :decisions/bar
        ctx-ident    :decisions/bar__context
        dec-ident    :decisions/bar__decision]
    [{:dt/type :mm/Memory
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
      (is (= :mm/Memory (-> back first :dt/type)))
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