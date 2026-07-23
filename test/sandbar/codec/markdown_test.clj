(ns sandbar.codec.markdown-test
  "Tests for sandbar.codec.markdown — frontmatter ↔ slot map + body
   verbatim round-trip + section tree.

   Stage C refactor (plans/sandbar_codex_review_remediation_arc_2026_05_13.md):
   the codec now reads :dt/codec-aliases from class entities at runtime
   via dt/codec-aliases-of (no hardcoded substrate-side maps), so these
   tests require a real metamodel — uses tu/make-test-db-fixture to
   load schema/*.edn including schema/mm.edn."
  (:require [clojure.test            :refer :all]
            [clojure.string          :as str]
            [sandbar.codec           :as codec]
            [sandbar.codec.protocol  :as proto]
            [sandbar.codec.markdown  :as md]
            [sandbar.test-util       :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures — DB with full schema + fresh codec registry per test

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "codec-markdown-test"})
  (fn [t]
    (codec/clear-all!)
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter split helpers

(deftest split-frontmatter-with-fence
  (let [src "---\nname: Foo\ntype: decision\n---\n# Heading\n\nBody."]
    (let [[fm body] (md/split-frontmatter src)]
      (is (= "name: Foo\ntype: decision\n" fm))
      (is (= "# Heading\n\nBody." body)))))

(deftest split-frontmatter-without-fence
  (let [src "# Heading\n\nNo frontmatter here."]
    (let [[fm body] (md/split-frontmatter src)]
      (is (nil? fm))
      (is (= "# Heading\n\nNo frontmatter here." body)))))

(deftest split-frontmatter-unterminated-fence
  ;; Source starts with `---` but never closes — treat as body
  (let [src "---\nname: Foo\nno closing fence"
        [fm body] (md/split-frontmatter src)]
    (is (nil? fm))
    (is (= "---\nname: Foo\nno closing fence" body))))

(deftest split-frontmatter-normalizes-crlf
  (let [src "---\r\nname: Foo\r\n---\r\n# Body\r\n"
        [fm body] (md/split-frontmatter src)]
    (is (= "name: Foo\n" fm))
    (is (= "# Body\n" body))))

(deftest split-frontmatter-empty-frontmatter
  ;; --- followed immediately by --- = empty frontmatter
  (let [src "---\n---\n# Body\n"
        [fm body] (md/split-frontmatter src)]
    (is (= "" fm))
    (is (= "# Body\n" body))))

(deftest split-frontmatter-with-horizontal-rule-in-body
  ;; UR-12 (Phase U Stage U-2): when the body contains a `---`
  ;; horizontal-rule line, the prior implementation's
  ;; (str/index-of after-open close-match) substring search could
  ;; locate the body's `---` before the regex-matched closing marker
  ;; and split at the wrong offset.  The re-matcher fix uses
  ;; .start() to read the correct position directly from the matcher.
  (let [src "---\nname: test\n---\n\nbody line one\n\n---\n\nbody line two\n"
        [fm body] (md/split-frontmatter src)]
    (is (= "name: test\n" fm)
        "frontmatter is everything between the FIRST opening and closing `---`")
    (is (= "\nbody line one\n\n---\n\nbody line two\n" body)
        "body contains the recurring `---` as a horizontal-rule line")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Whitespace normalization

(deftest normalize-body-strips-non-hardbreak-trailing-whitespace
  ;; Lines with EXACTLY 1 trailing space (or trailing tab) are NOT a
  ;; markdown hard-break and must be stripped.  Canonical form adds a
  ;; single trailing newline.
  (is (= "line one\nline two\nline three\n"
         (md/normalize-body "line one \nline two\t\nline three"))))

(deftest normalize-body-preserves-markdown-hardbreaks
  ;; Two trailing spaces = markdown hard-break per CommonMark §4.2.6.
  ;; Canonical form adds a single trailing newline.
  (is (= "line one  \nline two\n"
         (md/normalize-body "line one  \nline two")))
  (is (= "line one   \nline two\n"
         (md/normalize-body "line one   \nline two"))
      "Three trailing spaces still preserves hard-break (2+ spaces)"))

(deftest normalize-body-collapses-multi-blank-lines
  ;; Canonical form adds a single trailing newline; multi-blank
  ;; collapsed between paragraphs.
  (is (= "para one\n\npara two\n"
         (md/normalize-body "para one\n\n\n\npara two"))))

(deftest normalize-body-handles-crlf
  (is (= "line one\nline two\n"
         (md/normalize-body "line one\r\nline two\r\n"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter ↔ slot mapping

(deftest frontmatter-key-to-slot-default-rule
  (is (= :mm.memory/name        (md/frontmatter-key->slot :mm/Memory :name)))
  (is (= :mm.memory/description (md/frontmatter-key->slot :mm/Memory :description)))
  (is (= :mm.memory/scope       (md/frontmatter-key->slot :mm/Memory :scope))))

(deftest frontmatter-key-to-slot-respects-aliases
  ;; mm/Memory's `type` → `memory-type` to avoid Clojure reserved-word
  ;; collision per known-class-slot-aliases
  (is (= :mm.memory/memory-type (md/frontmatter-key->slot :mm/Memory :type))))

(deftest frontmatter-key-to-slot-for-section
  (is (= :mm.section/heading       (md/frontmatter-key->slot :mm/Section :heading)))
  (is (= :mm.section/heading-level (md/frontmatter-key->slot :mm/Section :heading-level))))

(deftest slot-to-frontmatter-key-inverse-of-aliases
  (is (= :type (md/slot->frontmatter-key :mm/Memory :mm.memory/memory-type)))
  (is (= :name (md/slot->frontmatter-key :mm/Memory :mm.memory/name))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Codec parse — direct

(deftest parse-mm-memory-with-frontmatter
  (let [c (md/make-codec)
        src "---\nname: Foo\ntype: decision\nscope: global\n---\n# Heading\n\nBody.\n"
        result (proto/parse c src {:class :mm/Memory})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "Foo"     (:mm.memory/name result)))
    (is (= :decision (:mm.memory/memory-type result)))
    (is (= :global   (:mm.memory/scope result)))
    (is (= "# Heading\n\nBody.\n" (:mm.memory/body-raw result)))))

(deftest parse-without-frontmatter
  (let [c (md/make-codec)
        src "# Heading\n\nJust body."
        result (proto/parse c src {:class :mm/Memory})]
    (is (= :mm/Memory (:dt/type result)))
    ;; normalize-body adds the canonical single trailing newline.
    (is (= "# Heading\n\nJust body.\n" (:mm.memory/body-raw result)))
    (is (nil? (:mm.memory/name result)))))

(deftest parse-rejects-without-class-opt
  (let [c (md/make-codec)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :class"
          (proto/parse c "# Body" {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Codec emit — direct

(deftest emit-mm-memory-with-frontmatter
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/name "Foo"
                :mm.memory/memory-type :decision
                :mm.memory/body-raw "# Heading\n\nBody."}
        emitted (proto/emit c entity {})]
    (is (str/starts-with? emitted "---\n"))
    (is (str/includes? emitted "name: Foo"))
    (is (str/includes? emitted "type: decision")
        "memory-type slot emits back as `type` frontmatter key")
    (is (str/includes? emitted "# Heading"))
    (is (str/ends-with? emitted "\n"))))

(deftest emit-without-frontmatter-when-empty
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/body-raw "# Just body\n"}
        emitted (proto/emit c entity {})]
    (is (not (str/starts-with? emitted "---")))
    (is (= "# Just body\n" emitted))))

(deftest emit-rejects-without-dt-type
  (let [c (md/make-codec)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :dt/type"
          (proto/emit c {:body "..."} {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip — parse(emit(x)) = x

(deftest round-trip-mm-memory-frontmatter-plus-body
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/name "Foo"
                :mm.memory/memory-type :decision
                :mm.memory/scope :global
                :mm.memory/body-raw "# Heading\n\nBody text here.\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "round-trip mismatch — diff: " (pr-str (:diff result))))))

(deftest round-trip-body-only
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/body-raw "# Just body\n\nNo frontmatter.\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "round-trip mismatch — diff: " (pr-str (:diff result))))))

(deftest round-trip-with-markdown-hardbreak
  ;; Two trailing spaces should survive normalization-on-parse + round-trip
  (let [c (md/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/body-raw "line one  \nline two\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "hard-break preservation mismatch — diff: " (pr-str (:diff result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mediator integration — register! + dispatch by :format and :dt/native-codec

(deftest register-and-parse-via-mediator
  (md/register!)
  (let [src "---\nname: Foo\n---\n# Body\n"
        result (codec/parse src {:format :markdown :class :mm/Memory})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "Foo"      (:mm.memory/name result)))))

(deftest mime-types-include-text-markdown
  (let [c (md/make-codec)
        mts (proto/mime-types c)]
    (is (some #{"text/markdown"} mts))))

(deftest mediator-dispatches-via-mime
  (md/register!)
  (let [src "---\nname: Bar\n---\n# Body\n"
        [fmt _codec] (codec/codec-for-mime "text/markdown")]
    (is (= :markdown fmt))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; B.3 — Section-tree parsing
;;
;; Per decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md §2 + §3.

(deftest slugify-edge-cases
  ;; via the public memory-ident-from-rel-path / section-ident
  (is (= :decisions/foo (md/memory-ident-from-rel-path "decisions/foo.md")))
  (is (= :decisions/foo__context (md/section-ident :decisions/foo ["Context"])))
  (is (= :decisions/foo__context__decision
         (md/section-ident :decisions/foo ["Context" "Decision"])))
  (is (= :patterns.architectural.sandbar/x
         (md/memory-ident-from-rel-path "patterns/architectural/sandbar/x.md"))))

(deftest parse-sections-simple
  (let [body "## Context\n\nFirst para.\n\n## Decision\n\nSecond para.\n"
        sections (md/parse-sections body :decisions/foo)]
    (is (= 2 (count sections)))
    (let [[a b] sections]
      (is (= :decisions/foo__context (:db/ident a)))
      (is (= :decisions/foo__decision (:db/ident b)))
      (is (= "Context"  (:mm.section/heading a)))
      (is (= "Decision" (:mm.section/heading b)))
      (is (= 2 (:mm.section/heading-level a)))
      (is (= :decisions/foo (:mm.section/parent a)))
      (is (= :decisions/foo (:mm.section/parent b)))
      ;; Sibling chain: a → b
      (is (= :decisions/foo__decision (:mm.section/next-sibling a)))
      (is (nil? (:mm.section/previous-sibling a)))
      (is (= :decisions/foo__context (:mm.section/previous-sibling b)))
      (is (nil? (:mm.section/next-sibling b))))))

(deftest parse-sections-nested
  (let [body "## Context\n\nIntro.\n\n### Sub\n\nDeeper.\n\n## Decision\n\nNext.\n"
        sections (md/parse-sections body :decisions/foo)]
    (is (= 3 (count sections)))
    (let [[ctx sub dec] sections]
      (is (= :decisions/foo__context (:db/ident ctx)))
      (is (= :decisions/foo__context__sub (:db/ident sub)))
      (is (= :decisions/foo__decision (:db/ident dec)))
      ;; Parent links
      (is (= :decisions/foo (:mm.section/parent ctx)))
      (is (= :decisions/foo__context (:mm.section/parent sub)))
      (is (= :decisions/foo (:mm.section/parent dec)))
      ;; Sibling chain at top-level: ctx → dec; sub has no top-level
      ;; sibling chain entry (different parent).
      (is (= :decisions/foo__decision (:mm.section/next-sibling ctx)))
      (is (= :decisions/foo__context  (:mm.section/previous-sibling dec)))
      ;; Sub is the only child of ctx — no siblings at level 3 yet.
      (is (nil? (:mm.section/next-sibling sub)))
      (is (nil? (:mm.section/previous-sibling sub))))))

(deftest parse-sections-collision-auto-disambiguates
  ;; Two sections at same level under same parent with same slug now
  ;; auto-disambiguate via `-2` / `-3` / ... ident suffix.  Heading
  ;; text is preserved verbatim on emit; only the ident carries the
  ;; suffix.  Round-trip-stable because disambiguation is
  ;; deterministic.  Per
  ;; decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md
  ;; (previously raised "Section ident collision" exception).
  (let [body "## Context\n\n## Context\n"
        [s1 s2] (md/parse-sections body :decisions/foo)]
    (is (= "Context" (:mm.section/heading s1))
        "first section keeps heading text")
    (is (= "Context" (:mm.section/heading s2))
        "second section also keeps heading text")
    (is (not= (:db/ident s1) (:db/ident s2))
        "but their idents must differ")
    (is (str/ends-with? (name (:db/ident s2)) "-2")
        (str "second ident gets `-2` suffix; got " (:db/ident s2)))))

(deftest parse-sections-bidirectional-consistency
  (let [body "## A\n\n## B\n\n## C\n"
        [a b c] (md/parse-sections body :decisions/foo)]
    ;; Forward + backward chain must agree
    (is (= (:db/ident b) (:mm.section/next-sibling a)))
    (is (= (:db/ident a) (:mm.section/previous-sibling b)))
    (is (= (:db/ident c) (:mm.section/next-sibling b)))
    (is (= (:db/ident b) (:mm.section/previous-sibling c)))
    (is (nil? (:mm.section/next-sibling c)))
    (is (nil? (:mm.section/previous-sibling a)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; B.3 — Full-document round-trip via parse-document + emit-document

(deftest parse-document-simple
  (let [src (str "---\n"
                 "name: Foo Decision\n"
                 "type: decision\n"
                 "---\n"
                 "## Context\n"
                 "\n"
                 "Context body.\n"
                 "\n"
                 "## Decision\n"
                 "\n"
                 "Decision body.\n")
        entities (md/parse-document src "decisions/foo.md")]
    (is (= 3 (count entities)) "memory + 2 sections")
    (let [[memory ctx decision] entities]
      ;; D2 routing fix (2026-07-02): parse-document now derives the CANONICAL
      ;; `memory.`-prefixed ident via rel-path->memory-ident (was the bare
      ;; :decisions/foo — the bug per project_import_drops_memory_namespace_-
      ;; prefix_bare_ident_2026_07_01).
      (is (= :memory.decisions/foo (:db/ident memory)))
      ;; Post-2026-05-21: type: decision routes to :mm/Decision (subclass of :mm/Memory)
      ;; via :dt/codec-type-keyword.  Codec walks :dt/subclass-of for slot inheritance.
      (is (= :mm/Decision   (:dt/type memory)))
      (is (= "Foo Decision" (:mm.memory/name memory)))
      (is (= :decision      (:mm.memory/memory-type memory)))
      (is (= :memory.decisions/foo__context (:mm.memory/first-section memory)))
      ;; Memory's rel-path captured — UNPREFIXED form (D2 normalization); the
      ;; input `decisions/foo.md` had no `memory/` prefix so it is unchanged.
      (is (= "decisions/foo.md" (:mm.memory/rel-path memory)))
      ;; Section ctx
      (is (= :memory.decisions/foo__context (:db/ident ctx)))
      (is (= "Context" (:mm.section/heading ctx)))
      ;; Section decision
      (is (= :memory.decisions/foo__decision (:db/ident decision)))
      (is (= "Decision" (:mm.section/heading decision))))))

(deftest parse-document-frontmatter-only
  (let [src "---\nname: Empty\n---\n"
        entities (md/parse-document src "notes/empty.md")]
    (is (= 1 (count entities)) "frontmatter-only memory → no sections")
    ;; D2 routing fix (2026-07-02): canonical prefixed ident (was :notes/empty).
    (is (= :memory.notes/empty (:db/ident (first entities))))))

(deftest round-trip-document-sections
  (let [src (str "---\n"
                 "name: Foo\n"
                 "type: decision\n"
                 "---\n"
                 "## Context\n"
                 "\n"
                 "Para one.\n"
                 "\n"
                 "## Decision\n"
                 "\n"
                 "Para two.\n")
        parsed   (md/parse-document src "decisions/foo.md")
        emitted  (md/emit-document parsed)
        reparsed (md/parse-document emitted "decisions/foo.md")]
    ;; Same number of entities
    (is (= (count parsed) (count reparsed)))
    ;; Sibling refs survive round-trip
    (is (= (-> parsed   second :db/ident)
           (-> reparsed second :db/ident)))
    (is (= (-> parsed   second :mm.section/next-sibling)
           (-> reparsed second :mm.section/next-sibling)))
    ;; Headings survive
    (is (= (-> parsed   second :mm.section/heading)
           (-> reparsed second :mm.section/heading)))))

(deftest round-trip-document-nested-sections
  (let [src (str "---\n"
                 "name: Nested\n"
                 "---\n"
                 "## Outer\n"
                 "\n"
                 "Outer para.\n"
                 "\n"
                 "### Inner\n"
                 "\n"
                 "Inner para.\n"
                 "\n"
                 "## Sibling\n"
                 "\n"
                 "Sibling para.\n")
        parsed   (md/parse-document src "decisions/nested.md")
        emitted  (md/emit-document parsed)
        reparsed (md/parse-document emitted "decisions/nested.md")]
    (is (= (count parsed) (count reparsed)) "4 entities (memory + 3 sections)")
    ;; Section idents preserved
    (is (= (mapv :db/ident parsed) (mapv :db/ident reparsed)))
    ;; Parent links preserved
    (is (= (mapv :mm.section/parent (rest parsed))
           (mapv :mm.section/parent (rest reparsed))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; B.4 — Adversarial round-trip + performance baseline

(deftest round-trip-deep-nesting
  (let [src (str "---\n"
                 "name: Deep\n"
                 "---\n"
                 "# L1\nL1 text.\n\n"
                 "## L2\nL2 text.\n\n"
                 "### L3\nL3 text.\n\n"
                 "#### L4\nL4 text.\n\n"
                 "##### L5\nL5 text.\n\n"
                 "###### L6\nL6 text.\n")
        parsed   (md/parse-document src "decisions/deep.md")
        emitted  (md/emit-document parsed)
        reparsed (md/parse-document emitted "decisions/deep.md")]
    (is (= 7 (count parsed)) "memory + 6 sections (one per level)")
    ;; Each level's parent walks up properly
    (let [sections (rest parsed)]
      ;; D2 routing fix (2026-07-02): parent idents are the canonical
      ;; `memory.`-prefixed form (were bare :decisions/deep…).
      (is (= [:memory.decisions/deep
              :memory.decisions/deep__l1
              :memory.decisions/deep__l1__l2
              :memory.decisions/deep__l1__l2__l3
              :memory.decisions/deep__l1__l2__l3__l4
              :memory.decisions/deep__l1__l2__l3__l4__l5]
             (mapv :mm.section/parent sections))))
    ;; Round-trip preserves structure
    (is (= (mapv :db/ident parsed) (mapv :db/ident reparsed)))))

(deftest round-trip-multi-paragraph-bodies
  (let [src (str "---\nname: Multi\n---\n"
                 "## A\n\n"
                 "First paragraph.\n\n"
                 "Second paragraph.\n\n"
                 "Third paragraph.\n\n"
                 "## B\n\n"
                 "B body.\n")
        parsed   (md/parse-document src "decisions/multi.md")
        emitted  (md/emit-document parsed)
        reparsed (md/parse-document emitted "decisions/multi.md")]
    (is (= 3 (count parsed)))
    ;; Body content survives — A has 3 paragraphs
    (let [a-body (-> parsed second :mm.section/body)]
      (is (str/includes? a-body "First paragraph"))
      (is (str/includes? a-body "Second paragraph"))
      (is (str/includes? a-body "Third paragraph")))
    ;; Round-trip idempotent
    (is (= (-> parsed   second :mm.section/body)
           (-> reparsed second :mm.section/body)))))

(deftest round-trip-mixed-frontmatter-types
  (let [src (str "---\n"
                 "name: Mixed\n"
                 "type: decision\n"
                 "scope: global\n"
                 "status: active\n"
                 "importance: high\n"
                 "---\n"
                 "## Body\n\nContent.\n")
        parsed   (md/parse-document src "decisions/mixed.md")
        emitted  (md/emit-document parsed)
        reparsed (md/parse-document emitted "decisions/mixed.md")
        memory   (first parsed)
        re-memory (first reparsed)]
    ;; Keyword slots round-trip as keywords
    (is (= :decision (:mm.memory/memory-type memory)))
    (is (= :decision (:mm.memory/memory-type re-memory)))
    (is (= :global   (:mm.memory/scope memory)))
    (is (= :global   (:mm.memory/scope re-memory)))
    (is (= :active   (:mm.memory/status memory)))
    (is (= :active   (:mm.memory/status re-memory)))
    ;; Non-keyword scalar round-trips as authored
    (is (= (:mm.memory/name memory) (:mm.memory/name re-memory)))))

(deftest round-trip-empty-section-body
  (let [src (str "---\nname: Empty\n---\n"
                 "## A\n\n"
                 "## B\n\nB body.\n")
        parsed   (md/parse-document src "decisions/empty.md")
        emitted  (md/emit-document parsed)
        reparsed (md/parse-document emitted "decisions/empty.md")]
    ;; A has empty body; B has content
    (is (str/blank? (-> parsed second :mm.section/body)))
    (is (not (str/blank? (-> parsed last :mm.section/body))))
    ;; Round-trip preserves empty body
    (is (str/blank? (-> reparsed second :mm.section/body)))))

(deftest round-trip-crlf-line-endings
  ;; Source has CRLF; codec normalizes to LF; round-trip stable thereafter
  (let [src "---\r\nname: CR\r\n---\r\n## Body\r\n\r\nText.\r\n"
        parsed   (md/parse-document src "decisions/cr.md")
        emitted  (md/emit-document parsed)]
    (is (not (str/includes? emitted "\r")) "emit uses LF only")
    ;; Second-parse idempotent
    (let [reparsed (md/parse-document emitted "decisions/cr.md")]
      (is (= (mapv :db/ident parsed) (mapv :db/ident reparsed))))))

(deftest round-trip-headings-with-special-chars
  ;; Slug strips non-alphanumeric; check the round-trip works
  (let [src (str "---\nname: Special\n---\n"
                 "## Q&A: First!\n\nA.\n\n"
                 "## Next-Steps\n\nB.\n")
        parsed   (md/parse-document src "decisions/special.md")]
    (is (= 3 (count parsed)))
    ;; D2 routing fix (2026-07-02): canonical prefixed section idents.
    (is (= :memory.decisions/special__qa-first    (-> parsed second :db/ident)))
    (is (= :memory.decisions/special__next-steps  (-> parsed last :db/ident)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 7.C — class-routing via :dt/codec-type-keyword
;;
;; Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
;; Stage 7.C — codec resolves the target class from frontmatter `type:`
;; via metamodel introspection (no hardcoded class knowledge per
;; interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md).

(deftest resolve-document-class-routes-decision-to-mm-decision
  ;; Post-2026-05-21: type: decision routes to :mm/Decision (subclass of :mm/Memory)
  ;; per its :dt/codec-type-keyword :decision declaration in schema/mm-artifact.edn.
  (let [src "---\nname: Foo\ntype: decision\n---\n# Body\n"]
    (is (= :mm/Decision (md/resolve-document-class src)))))

(deftest resolve-document-class-without-frontmatter
  ;; No frontmatter → default :mm/Memory
  (let [src "# Just body\n\nNo frontmatter here."]
    (is (= :mm/Memory (md/resolve-document-class src)))))

(deftest resolve-document-class-without-type-field
  ;; Frontmatter present but no `type:` key → default :mm/Memory
  (let [src "---\nname: Foo\n---\n# Body\n"]
    (is (= :mm/Memory (md/resolve-document-class src)))))

(deftest resolve-document-class-routes-tag-via-metamodel
  ;; type: tag → :mm/Tag because :mm/Tag declares :dt/codec-type-keyword :tag
  (let [src (str "---\n"
                 "name: Audit\n"
                 "type: tag\n"
                 "definition: A discipline-checking pass over the corpus.\n"
                 "---\n"
                 "Body of the tag memorial.\n")]
    (is (= :mm/Tag (md/resolve-document-class src)))))

(deftest parse-document-routes-tag-files-to-mm-tag-class
  ;; type: tag → entity-spec has :dt/type :mm/Tag (not :mm/Memory)
  (let [src (str "---\n"
                 "name: Audit\n"
                 "type: tag\n"
                 "value: audit\n"
                 "definition: A discipline-checking pass over the corpus.\n"
                 "scope-note: Applies when verifying capture-discipline gaps.\n"
                 "---\n"
                 "Body narrative.\n")
        entities (md/parse-document src "tags/audit.md")]
    (is (= 1 (count entities)) "tag document = single entity; no section decomposition")
    (let [tag (first entities)]
      (is (= :mm/Tag    (:dt/type tag)) "routes to :mm/Tag class")
      ;; D2 routing fix (2026-07-02): canonical prefixed ident (was :tags/audit).
      (is (= :memory.tags/audit (:db/ident tag)) "ident derives from rel-path")
      (is (= "audit"    (:mm.tag/value tag)) "value slot populated from frontmatter")
      (is (= "A discipline-checking pass over the corpus."
             (:mm.tag/definition tag)) "definition slot populated")
      (is (= "Applies when verifying capture-discipline gaps."
             (:mm.tag/scope-note tag)) "scope-note slot populated")
      ;; The :type field is consumed by routing — not assigned as a slot
      ;; (no :mm.tag/type slot exists; the class IS the type-signal).
      (is (not (contains? tag :mm.tag/type))
          ":type frontmatter consumed by routing; not slotted on :mm/Tag")
      ;; :mm.memory/rel-path is NOT set on tag entities (rel-path is derived
      ;; from the memory/tags/<canonical-name>.md convention).
      (is (not (contains? tag :mm.memory/rel-path))
          "tag entities don't carry :mm.memory/rel-path"))))

(deftest parse-document-tag-files-do-not-decompose-into-sections
  ;; Even when a tag memorial has markdown headings in its body, no section
  ;; decomposition runs — :mm/Tag has no section-tree convention.
  (let [src (str "---\n"
                 "name: Audit\n"
                 "type: tag\n"
                 "value: audit\n"
                 "---\n"
                 "## Why this exists\n\nNarrative.\n\n"
                 "## Examples\n\nMore narrative.\n")
        entities (md/parse-document src "tags/audit.md")]
    (is (= 1 (count entities)) "tag document = single entity even with headings")
    (is (= :mm/Tag (:dt/type (first entities))))))

(deftest parse-document-memory-files-still-decompose-into-sections
  ;; Regression guard — Memory-subclasses (post-2026-05-21 codec slot-inheritance fix)
  ;; must continue to decompose into sections via the memory-class? helper.
  ;; type: decision routes to :mm/Decision (subclass of :mm/Memory); the codec's
  ;; memory-class? helper walks dt/subclass-of? and fires section decomposition.
  (let [src (str "---\n"
                 "name: Test\n"
                 "type: decision\n"
                 "---\n"
                 "## Section A\n\nBody A.\n\n"
                 "## Section B\n\nBody B.\n")
        entities (md/parse-document src "decisions/test.md")]
    (is (= 3 (count entities)) "memory + 2 sections — section decomposition intact")
    (is (= :mm/Decision (:dt/type (first entities))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; B.4 — Performance baseline (informational; assertions soft)
;;
;; Target per B.0 ADR §7.4: ~30ms per typical 10KB file.  The corpus
;; ~1000-file round-trip should complete in ≤30s budget.

(defn- gen-sample-doc
  "Generate a synthetic mm/Memory markdown source approximating corpus
   structure — frontmatter + 5 top-level sections, each ~2KB."
  []
  (let [section-body (apply str (repeat 30 "Lorem ipsum dolor sit amet, consectetur adipiscing elit.\n"))
        sections     (for [i (range 1 6)]
                       (str "## Section " i "\n\n" section-body "\n"))]
    (str "---\nname: Bench Sample\ntype: decision\nscope: global\n---\n"
         (apply str sections))))

(deftest perf-baseline-10kb-document
  ;; Soft assertion — informational; reports timing but doesn't fail
  ;; CI on environmental variation.
  (let [src (gen-sample-doc)
        n-runs 20]
    (is (> (count src) 8000) "Sample doc is ~10KB")
    (let [start (System/nanoTime)
          _     (dotimes [_ n-runs]
                  (let [parsed  (md/parse-document src "decisions/bench.md")
                        emitted (md/emit-document parsed)]
                    (when (str/blank? emitted) (throw (ex-info "emit empty" {})))))
          elapsed-ns (- (System/nanoTime) start)
          per-run-ms (/ elapsed-ns 1e6 n-runs)]
      (println (format "B.4 perf baseline: %.2f ms/round-trip (n=%d, ~%dKB doc)"
                       per-run-ms n-runs (int (/ (count src) 1024))))
      ;; Soft assertion — fail only on egregious regression (>500ms/file)
      (is (< per-run-ms 500.0)
          (format "Performance baseline severely exceeded: %.2f ms/run > 500 ms target"
                  per-run-ms)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UR-6 + UR-7 (Phase U Stage U-2): emit must not leak internal-ns keys
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest emit-excludes-db-internal-and-rel-path-keys
  ;; A persisted entity carries :db/id + :db/ident + :mm.memory/rel-path
  ;; (from parse-document materialization).  Pre-fix, these survived the
  ;; emit dissoc-filter and landed in YAML frontmatter; post-fix, the
  ;; internal-key? filter strips them.
  (let [c (md/make-codec)
        persisted-entity {:dt/type :mm/Memory
                          :db/id 17592186045511
                          :db/ident :decisions/example
                          :mm.memory/name "Example"
                          :mm.memory/memory-type :decision
                          :mm.memory/rel-path "decisions/example.md"
                          :mm.memory/body-raw "# Body\n"}
        emitted (proto/emit c persisted-entity {})]
    (is (not (str/includes? emitted "db/id"))
        (str "emit must not leak :db/id; got:\n" emitted))
    (is (not (str/includes? emitted "db/ident"))
        (str "emit must not leak :db/ident; got:\n" emitted))
    (is (not (str/includes? emitted "rel-path"))
        (str "emit must not leak :mm.memory/rel-path; got:\n" emitted))
    (is (str/includes? emitted "name")
        "non-internal frontmatter keys should still emit")
    (is (str/includes? emitted "# Body")
        "body should still emit")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip stability — emit must be a FIXED POINT under further
;; parse+emit, per
;; decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md
;; (Dan-directive 2026-05-20).  These cover the three nondeterminism
;; bug classes fixed in the same commit:
;;   1. Single-quote escape ('' → ') missing from lenient parser
;;   2. coerce-scalar not applied to list-items / inline-array elements
;;   3. flush-buf! collapsed single-element block-list to scalar
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- round-trip-stable?
  "Returns true if emit(parse(src)) == emit(parse(emit(parse(src))))."
  [src rel-path]
  (let [emit1 (md/emit-document (md/parse-document src rel-path))
        emit2 (md/emit-document (md/parse-document emit1 rel-path))]
    (= emit1 emit2)))

(deftest round-trip-stable-apostrophe-in-description
  ;; Regression: source `description: Anthropic's foo` — YAML emits as
  ;; single-quoted `'Anthropic''s foo'`.  Without `''` → `'` unescape on
  ;; re-parse, the apostrophe escapes double on each round-trip without
  ;; bound (`''s` → `''''s` → `''''''''s`).
  (let [src "---\nname: Test\ndescription: Anthropic's foo with Model-family: claude.\ntype: decision\n---\n# Body\n"]
    (is (round-trip-stable? src "memory/decisions/test.md")
        "single-quote escapes must unescape so apostrophes don't double on round-trip")))

(deftest round-trip-stable-inline-array-quoted-elements
  ;; Regression: source `tags: ["task", "codex"]` — lenient parser
  ;; didn't strip the inner quotes; emit then wrapped each in single
  ;; quotes; on re-parse it kept growing.
  (let [src "---\nname: Test\ntype: task\ntags: [\"task\", \"codex\", \"audit\"]\n---\n# Body\n"]
    (is (round-trip-stable? src "memory/tasks/test.md")
        "inline-array elements with inner quotes must unquote via coerce-scalar")))

(deftest round-trip-stable-single-element-block-list
  ;; Regression: source `parent:\n- foo` (cardinality-many block list
  ;; with one element).  Lenient parser's flush-buf! collapsed to scalar,
  ;; so emit1 was block-list but emit2 was scalar.  Removed the collapse;
  ;; both round-trips now produce block-list.
  (let [src "---\nname: Test\ntype: task\nrelated:\n  - types/task.md\n---\n# Body\n"]
    (is (round-trip-stable? src "memory/tasks/test.md")
        "single-element block-list must not collapse to scalar on parse")))

(deftest round-trip-stable-code-fence-hash-content
  ;; Regression: source body contains a code fence whose contents
  ;; include lines starting with `## ` (e.g., banner ASCII art).
  ;; Without code-fence tracking, parse-sections treated those as
  ;; markdown headings and chopped one `#` per round-trip
  ;; (`## ##` → `## #` → `## ` → empty).
  (let [src (str "---\nname: Test\ntype: library\n---\n"
                 "# Body heading\n\n"
                 "Code-fence example:\n\n"
                 "```\n"
                 " ##  ##  #####\n"
                 " ##  ##    ###\n"
                 " ######    ###\n"
                 "```\n\n"
                 "After the fence.\n")]
    (is (round-trip-stable? src "memory/libraries/test.md")
        "code-fence contents must NOT be heading-parsed")))

(deftest round-trip-stable-section-slug-collision
  ;; Regression: two H2 sections with the same heading text — previously
  ;; raised a "collision" exception; now auto-disambiguates with `-2`
  ;; ident suffix.  Heading text preserved verbatim on emit.
  (let [src "---\nname: Test\ntype: predicate\n---\n## Self-referential note\n\nA.\n\n## Self-referential note\n\nB.\n"]
    (is (round-trip-stable? src "memory/predicates/test.md")
        "duplicate heading idents must auto-disambiguate stably")))

(deftest round-trip-stable-combined-bug-classes
  ;; All three bug classes in one realistic actor-shaped fixture.
  (let [src (str "---\n"
                 "name: Test Actor\n"
                 "description: Anthropic's actor with `runs-in-context:` and Model-family: claude.\n"
                 "type: ai-actor\n"
                 "runs-in-context:\n"
                 "  - contexts/unsandboxed-home-laptop.md\n"
                 "tags: [\"actor\", \"ai-actor\", \"claude\"]\n"
                 "---\n# Body\n")]
    (is (round-trip-stable? src "memory/actors/test-actor.md")
        "combined-bug-class fixture must round-trip stably")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; η.4 Target #1 (2026-05-28) — rel-path->memory-ident handles ident-string
;; form correctly + does NOT produce the double-prefix bug.
;;
;; Pre-fix behavior: input `"memory.actors/foo"` → `:memory.memory.actors/foo`
;; (double-prefix bug) because the path-form parse prepended `memory/` then
;; split on `/`.  Post-fix: ident-string form is detected via regex + parsed
;; directly → `:memory.actors/foo`.
;;
;; The η.3 audit surfaced 233 entities / 464 occurrences affected by this
;; bug across 8 ref-slot families (created-by, cites, related, composes-with,
;; superseded-by, motivated-by, evidences, parent).

(deftest rel-path->memory-ident-handles-path-form
  (testing "Path-form (FS rel-path) inputs parse correctly"
    (is (= :memory.actors/claude-opus-4-7-1m
           (md/rel-path->memory-ident "actors/claude-opus-4-7-1m.md")))
    (is (= :memory.actors/claude-opus-4-7-1m
           (md/rel-path->memory-ident "memory/actors/claude-opus-4-7-1m.md")))
    (is (= :memory.decisions/foo
           (md/rel-path->memory-ident "decisions/foo.md")))
    (is (= :memory.patterns.architectural.sandbar/x
           (md/rel-path->memory-ident "patterns/architectural/sandbar/x.md")))))

(deftest rel-path->memory-ident-handles-ident-string-form
  (testing "Ident-string form (memory.dotted-ns/name) parses directly without double-prefix"
    ;; The η.4 Target #1 regression case
    (is (= :memory.actors/claude-opus-4-7-1m
           (md/rel-path->memory-ident "memory.actors/claude-opus-4-7-1m"))
        "ident-string form must NOT become :memory.memory.actors/...")
    (is (= :memory.libraries.patterns/scheduler_substrate_synthesis
           (md/rel-path->memory-ident
            "memory.libraries.patterns/scheduler_substrate_synthesis")))
    (is (= :memory.decisions/foo
           (md/rel-path->memory-ident "memory.decisions/foo")))
    (is (= :memory.patterns.architectural.sandbar/x
           (md/rel-path->memory-ident "memory.patterns.architectural.sandbar/x")))))

(deftest rel-path->memory-ident-ident-form-with-md-extension
  (testing "Defensive: ident-string form with stray .md extension still parses correctly"
    (is (= :memory.actors/foo
           (md/rel-path->memory-ident "memory.actors/foo.md")))))

;; P6 digit-dodge (2026-06-30) — rel-path->memory-ident dodges digit-leading
;; names so idents round-trip through the EDN reader; memory-ident->rel-path
;; un-dodges to recover the TRUE filename; section-ident dodges composed idents.
;; Per observations/p6_dry_run_actual_scope_1040_idents_eid_stable_refs_2026_06_30
;; + the 2026-05-23 digit-dodge origin observation.

(deftest rel-path->memory-ident-dodges-digit-leading-names
  (testing "digit-leading names get the singularized-namespace dodge prefix"
    (is (= :memory.logs/log-2026-05-03_capstone
           (md/rel-path->memory-ident "logs/2026-05-03_capstone.md")))
    (is (= :memory.sessions/session-2026-05-29T0713_x
           (md/rel-path->memory-ident "sessions/2026-05-29T0713_x.md")))
    (is (= :memory.inbox/inbox-2026-05-08_00-02-44
           (md/rel-path->memory-ident "inbox/2026-05-08_00-02-44.md")))
    (is (= :memory.audit-results/audit-result-2026-05-07_x
           (md/rel-path->memory-ident "audit-results/2026-05-07_x.md"))))
  (testing "non-digit names are unaffected (dodge is a no-op)"
    (is (= :memory.decisions/foo (md/rel-path->memory-ident "decisions/foo.md")))))

(deftest edn-safe-unsafe-ident-are-inverse
  ;; NB: digit-leading keyword LITERALS are unreadable (the very bug being
  ;; fixed), so the original forms are built via `keyword`, not literal syntax.
  (testing "edn-unsafe-ident recovers the original digit-leading name"
    (are [orig] (= orig (md/edn-unsafe-ident (md/edn-safe-ident orig)))
      (keyword "memory.logs" "2026-05-03_x")
      (keyword "memory.sessions" "2026-05-29T0713_x")
      (keyword "memory.inbox" "2026-05-08_00-02-44")
      (keyword "memory.audit-results" "2026-05-07_x")
      :memory.decisions/foo))
  (testing "both are idempotent"
    (let [d (keyword "memory.logs" "2026-x")]
      (is (= (md/edn-safe-ident d)
             (md/edn-safe-ident (md/edn-safe-ident d)))))
    (is (= (md/edn-unsafe-ident :memory.logs/log-2026-x)
           (md/edn-unsafe-ident (md/edn-unsafe-ident :memory.logs/log-2026-x))))))

(deftest rel-path-ident-round-trip-through-dodge
  (testing "rel-path -> dodged ident -> rel-path recovers the ORIGINAL filename"
    ;; memory-ident->rel-path is private — call via the var
    (are [rp] (= rp (#'md/memory-ident->rel-path (md/rel-path->memory-ident rp)))
      "logs/2026-05-03_capstone.md"
      "sessions/2026-05-29T0713_x.md"
      "inbox/2026-05-08_00-02-44.md"
      "decisions/foo.md"
      "patterns/architectural/sandbar/x.md")))

(deftest section-ident-dodges-digit-leading-parent
  (testing "sections inherit + dodge a digit-leading parent name"
    (is (= :memory.logs/log-2026-05-03_x__context
           (md/section-ident :memory.logs/log-2026-05-03_x ["Context"])))
    ;; even when the parent ident is still un-dodged, the composed section
    ;; ident is dodged so it is EDN-safe (both forms converge).  The un-dodged
    ;; parent is built via `keyword` (unreadable as a literal).
    (is (= :memory.logs/log-2026-05-03_x__context
           (md/section-ident (keyword "memory.logs" "2026-05-03_x") ["Context"]))))
  (testing "non-digit parent unaffected"
    (is (= :memory.decisions/foo__context
           (md/section-ident :memory.decisions/foo ["Context"])))))

(deftest rel-path->memory-ident-edge-cases
  (testing "Empty input → nil (no name + no namespace derivable)"
    (is (nil? (md/rel-path->memory-ident ""))))
  (testing "Slashless input parses as single-segment :memory/<name> (existing behavior preserved)"
    (is (= :memory/foo (md/rel-path->memory-ident "foo")))))

(deftest rel-path->memory-ident-non-memory-prefix-path-form-still-works
  (testing "Path-form inputs not starting with memory/ get the memory/ prefix prepended (existing behavior preserved)"
    (is (= :memory.actors/foo
           (md/rel-path->memory-ident "actors/foo.md")))
    ;; A path that LOOKS like an ident-string but doesn't start with memory.
    ;; falls through to path-form: "auth.X/foo" doesn't match the ident-form
    ;; regex (which requires `^memory\.`), so it parses as a path.
    (is (= :memory.auth.X/foo
           (md/rel-path->memory-ident "auth.X/foo")))))
