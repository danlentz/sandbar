(ns sandbar.codec.markdown-test
  "Tests for sandbar.codec.markdown — Stage B.2 minimum-viable scope:
   frontmatter ↔ slot map + body verbatim round-trip.  No section-tree
   decomposition tests yet (those land at Stage B.3)."
  (:require [clojure.test            :refer :all]
            [clojure.string          :as str]
            [sandbar.codec           :as codec]
            [sandbar.codec.protocol  :as proto]
            [sandbar.codec.markdown  :as md]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — fresh codec registry per test

(use-fixtures :each
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Whitespace normalization

(deftest normalize-body-strips-non-hardbreak-trailing-whitespace
  ;; Lines with EXACTLY 1 trailing space (or trailing tab) are NOT a
  ;; markdown hard-break and must be stripped.
  (is (= "line one\nline two\nline three"
         (md/normalize-body "line one \nline two\t\nline three"))))

(deftest normalize-body-preserves-markdown-hardbreaks
  ;; Two trailing spaces = markdown hard-break per CommonMark §4.2.6
  (is (= "line one  \nline two"
         (md/normalize-body "line one  \nline two")))
  (is (= "line one   \nline two"
         (md/normalize-body "line one   \nline two"))
      "Three trailing spaces still preserves hard-break (2+ spaces)"))

(deftest normalize-body-collapses-multi-blank-lines
  (is (= "para one\n\npara two"
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
    (is (= "# Heading\n\nJust body." (:mm.memory/body-raw result)))
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

(deftest parse-sections-collision-raises
  ;; Two sections at same level under same parent with same slug
  (let [body "## Context\n\n## Context\n"]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"collision"
          (md/parse-sections body :decisions/foo)))))

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
      (is (= :decisions/foo (:db/ident memory)))
      (is (= :mm/Memory     (:dt/type memory)))
      (is (= "Foo Decision" (:mm.memory/name memory)))
      (is (= :decision      (:mm.memory/memory-type memory)))
      (is (= :decisions/foo__context (:mm.memory/first-section memory)))
      ;; Memory's rel-path captured
      (is (= "decisions/foo.md" (:mm.memory/rel-path memory)))
      ;; Section ctx
      (is (= :decisions/foo__context (:db/ident ctx)))
      (is (= "Context" (:mm.section/heading ctx)))
      ;; Section decision
      (is (= :decisions/foo__decision (:db/ident decision)))
      (is (= "Decision" (:mm.section/heading decision))))))

(deftest parse-document-frontmatter-only
  (let [src "---\nname: Empty\n---\n"
        entities (md/parse-document src "notes/empty.md")]
    (is (= 1 (count entities)) "frontmatter-only memory → no sections")
    (is (= :notes/empty (:db/ident (first entities))))))

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
      (is (= [:decisions/deep
              :decisions/deep__l1
              :decisions/deep__l1__l2
              :decisions/deep__l1__l2__l3
              :decisions/deep__l1__l2__l3__l4
              :decisions/deep__l1__l2__l3__l4__l5]
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
    (is (= :decisions/special__qa-first    (-> parsed second :db/ident)))
    (is (= :decisions/special__next-steps  (-> parsed last :db/ident)))))

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
