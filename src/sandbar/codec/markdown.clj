(ns sandbar.codec.markdown
  "Markdown + YAML frontmatter codec implementation per
   decisions/sandbar_codec_layer_owns_wire_format_concerns_consumer_native_representation_2026_05_12.md §2.4
   and decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md.

   ## Wire format

   A markdown document with optional YAML frontmatter delimited by
   `---` lines:

       ---
       name: Foo
       description: ...
       type: decision
       ---
       # Heading

       Body text.

   ## Parse contract

   Parse takes a markdown string + opts (must include `:class
   class-ident`) and returns an entity-spec map:

       {:dt/type   :mm/Memory
        :mm.memory/name        \"Foo\"
        :mm.memory/description \"...\"
        :mm.memory/memory-type :decision
        :mm.memory/body-raw    \"# Heading\\n\\nBody text.\\n\"}

   Slot mapping rules:
     - Frontmatter keys are mapped to slot idents using the class's
       property-namespace convention (`<class-ns>.<lowercase-class-local>/<key>`)
     - Per-class aliases (read at runtime via `dt/codec-aliases-of`
       from the `:dt/codec-aliases` schema attribute on the class)
       handle reserved-word collisions (e.g., `type` →
       `:mm.memory/memory-type`)
     - Unknown frontmatter keys for which the class has no matching slot
       are passed through under `:frontmatter-extra` (the consumer can
       choose to store them via `mm/Frontmatter`)
     - Body text is assigned to the class's `body` slot
       (`:mm.memory/body-raw` for mm/Memory; `:mm.section/body` for
       mm/Section)

   ## Emit contract

   Emit takes an entity map + opts and returns a markdown string:
     - Frontmatter slots are emitted as YAML key-value pairs (block style)
     - Body slot is emitted verbatim after the closing `---`
     - Whitespace normalized per
       decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md
       §4 (LF line endings; trailing-whitespace stripped except hard-breaks;
       single blank between paragraphs; final newline)

   ## Stage B.2 scope

   This stage implements the FRONTMATTER + BODY round-trip layer.
   Section-tree decomposition (markdown headings → mm/Section entities
   with sibling-chain wiring) lands at Stage B.3.  For Stage B.2,
   mm/Memory's body is parsed/emitted as a single string (`:body-raw`);
   the section structure is preserved as-is in the body text without
   decomposition.

   ## Layer-targeting discipline

   Codec operates at the MODEL layer (slot idents, class metadata via
   `dt/*`) — never at the Datomic-schema layer (`:db.*` attributes).
   Per
   interaction/export_format_must_be_neutral_and_database_agnostic_2026_05_12.md
   the wire format is portable across model-equivalent backends."
  (:require [clj-yaml.core           :as yaml]
            [clojure.string          :as str]
            [clojure.tools.logging   :as log]
            [sandbar.codec.ordered-map :as om]
            [sandbar.codec.protocol  :as proto]
            [sandbar.db.datatype     :as dt]))

;; Extend clj-yaml's YAMLCodec protocol to handle the codec's ordered-map
;; backend (currently `java.util.LinkedHashMap` per
;; `sandbar.codec.ordered-map`).  SnakeYAML handles java.util.Map natively
;; but clj-yaml's dispatch protocol only ships with Clojure-map types.
;; Without this extension, passing an ordered-map to `yaml/generate-string`
;; throws.
;;
;; The codec needs an insertion-order-preserving map at emit-time so the
;; YAML dumper writes slots in the order built by `emit-frontmatter` per
;; the class-declared `:dt/codec-slot-order` (per
;; `decisions/slot_order_declared_by_class_introspectable_2026_05_20.md`).
;; Without this, clj-yaml falls back to whatever map shape Clojure provides,
;; losing the introspected canonical order.
;;
;; FUTURE — when dco-dev/ordered-collections ships an `insertion-ordered-map`
;; type (per `~/claude/memory/ideas/ordered_collections_insertion_ordered_map_addition_2026_05_20.md`),
;; the backend swap happens in `sandbar.codec.ordered-map` alone.  At that
;; point this protocol extension can be retargeted to the new type OR
;; removed (the new type will be a Clojure IPersistentMap; clj-yaml
;; dispatches natively).
(extend-protocol yaml/YAMLCodec
  java.util.LinkedHashMap
  (encode [data]
    (let [out (java.util.LinkedHashMap.)]
      (doseq [[k v] data]
        (.put out (yaml/encode k) (yaml/encode v)))
      out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontmatter / body split + whitespace normalization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const frontmatter-delim
  "Markdown frontmatter fence — a line consisting of exactly `---`."
  "---")

(defn- normalize-line-endings
  "Normalize CRLF → LF.  Preserves trailing newlines per §4.2."
  [s]
  (when s (str/replace s #"\r\n?" "\n")))

(defn split-frontmatter
  "Split a markdown source string into `[frontmatter-text body-text]`.
   When no frontmatter is present, returns `[nil source]`.

   Frontmatter is detected by an opening `---` line at position 0 +
   a closing `---` line later.  The frontmatter-text is the content
   BETWEEN those fences (exclusive); body-text is everything after
   the closing fence + its trailing newline.

   Phase U Stage U-2 fix (UR-12): the closing-marker position is read
   directly from the regex matcher via `.start()` instead of doing a
   secondary `str/index-of` substring search.  The prior pattern was
   vulnerable to `---` recurring inside the body (e.g., markdown
   horizontal-rule lines, fenced-code-block delimiters), which could
   make the substring search find an earlier match than the regex did
   and corrupt the split.  Using the matcher's match state is
   position-correct."
  [source]
  (let [s (normalize-line-endings source)]
    (if (and s (str/starts-with? s (str frontmatter-delim "\n")))
      (let [after-open (subs s (inc (count frontmatter-delim)))
            close-pattern #"(?m)^---\n?"
            matcher (re-matcher close-pattern after-open)]
        (if (.find matcher)
          (let [close-idx   (.start matcher)
                close-match (.group matcher)
                fm   (subs after-open 0 close-idx)
                body (subs after-open (+ close-idx (count close-match)))]
            [fm body])
          ;; Unterminated frontmatter — treat whole thing as body
          [nil s]))
      [nil s])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lenient line-based frontmatter parser (corpus-convention compatible)
;;
;; Ported from etc/lib/memory.clj/parse-frontmatter in the danlentz/claude
;; memory corpus per Friction Item #12 of plans/sandbar_0_1_1_coevolution_-
;; arc_2026_05_20.md (2026-05-20).  The corpus convention universally
;; uses unquoted descriptions containing colons (e.g.
;; `description: Dan's 2026-05-13 directive ...: ...`), which strict
;; clj-yaml rejects with "mapping values are not allowed here".  The
;; lenient line-based parser splits on the FIRST `:` per line — the
;; rest of the line is the value, colons and all.
;;
;; Handles: scalar keys, list values (YAML `-` items), `[a, b, c]`
;; inline-array values (common for :tags), and minimal scalar coercion
;; (boolean true/false + double-/single-quoted strings).  Per the
;; "filesystem is canonical; any backend must comply" discipline in
;; memory/interaction/filesystem_native_format_is_canonical_backend_-
;; compliance_required_hybrid_backend_experimentation_essential_2026_05_13.md
;; — sandbar (the backend) adapts to the corpus's established
;; frontmatter shape.

(defn- coerce-scalar
  "Minimal YAML scalar coercion for the lenient parser:
   - bare `true` / `false` → real booleans
   - double-quoted `\"...\"` → unquoted string contents (with `\\\"` → `\"`)
   - single-quoted `'...'`   → unquoted string contents (with `''` → `'`)
   Everything else passes through as the original trimmed string.

   Per YAML 1.1/1.2 escape rules + round-trip-stability requirement
   (decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md):
   the parser MUST unescape single-quote escapes so emitted form
   `'Anthropic''s'` parses back to the literal string `Anthropic's`,
   not `Anthropic''s` (which would re-escape to `Anthropic''''s` on the
   next emit and grow without bound)."
  [v]
  (cond
    (= v "true")  true
    (= v "false") false
    (and (>= (count v) 2)
         (str/starts-with? v "\"")
         (str/ends-with?   v "\""))
    (-> (subs v 1 (dec (count v)))
        (str/replace "\\\"" "\""))

    (and (>= (count v) 2)
         (str/starts-with? v "'")
         (str/ends-with?   v "'"))
    (-> (subs v 1 (dec (count v)))
        (str/replace "''" "'"))

    :else v))

(defn parse-frontmatter-text
  "Parse the frontmatter text (already split from `---` delimiters by
   `split-frontmatter`) into a Clojure map with keyword keys.  Lenient
   line-based parser — splits on the FIRST `:` per line, so values
   containing additional colons are preserved as-is.

   Public so tests + tooling can call directly.  See ns-block comment
   above for rationale + the corpus-convention compatibility story."
  [fm-text]
  ;; Accumulator is an ordered-map (insertion-order-preserving) so emit
  ;; can reproduce source frontmatter key order.  See sandbar.codec.ordered-map
  ;; for backend details.
  (let [lines (str/split-lines (str/trim fm-text))
        acc   (om/create)
        flush-buf!
        (fn [cur-key cur-buf]
          ;; Preserve vec shape — DO NOT collapse single-element block-list
          ;; to scalar.  YAML semantics distinguish `key: [foo]` / `key:\n- foo`
          ;; (a one-element list) from `key: foo` (a scalar), and round-trip
          ;; stability requires we preserve this per
          ;; decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md.
          (when cur-key
            (om/put! acc cur-key cur-buf)))]
    (loop [[line & rest] lines
           cur-key nil
           cur-buf []]
      (cond
        (nil? line)
        (do (flush-buf! cur-key cur-buf)
            acc)

        ;; List item continuation — apply coerce-scalar so YAML escape
        ;; sequences (`''` → `'`; `\"` → `"`) + outer quotes are normalized
        ;; per round-trip-stability requirement.
        (str/starts-with? (str/triml line) "-")
        (recur rest cur-key
               (conj cur-buf (-> line str/triml (subs 1) str/triml coerce-scalar)))

        ;; New key — line doesn't start with whitespace + contains `:`
        (and (not (str/starts-with? line " "))
             (str/includes? line ":"))
        (let [[k v] (str/split line #":" 2)
              k     (keyword (str/trim k))
              v     (str/trim v)]
          (flush-buf! cur-key cur-buf)
          (cond
            ;; Inline-array: tags: [a, b, c]; tags: ["a", "b"] — apply
            ;; coerce-scalar per element so quoted elements unquote
            ;; (round-trip stability per
            ;; decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md).
            (and (str/starts-with? v "[") (str/ends-with? v "]"))
            (do (om/put! acc k
                         (->> (subs v 1 (dec (count v)))
                              (#(str/split % #","))
                              (map str/trim)
                              (remove str/blank?)
                              (mapv coerce-scalar)))
                (recur rest nil []))

            ;; Inline scalar
            (seq v)
            (do (om/put! acc k (coerce-scalar v))
                (recur rest nil []))

            ;; List follows (YAML block under this key)
            :else
            (recur rest k [])))

        ;; Unrecognized line — skip
        :else
        (recur rest cur-key cur-buf)))))

(defn- strip-trailing-non-hardbreak-whitespace
  "Strip trailing whitespace from each line UNLESS it's a markdown
   hard-break (line ending with 2+ trailing spaces per CommonMark §4.2.6).
   Per
   decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md
   §4.2."
  [body]
  (when body
    (str/join "\n"
              (for [line (str/split body #"\n" -1)]
                (cond
                  ;; Hard-break: 2+ trailing spaces — preserve
                  (re-find #"  +$" line) line
                  ;; Other trailing whitespace: strip
                  :else (str/replace line #"[ \t]+$" ""))))))

(defn- collapse-multi-blank-lines
  "Collapse runs of 2+ blank lines into a single blank line per §4.2."
  [body]
  (when body
    (str/replace body #"\n{3,}" "\n\n")))

(defn normalize-body
  "Apply the whitespace normalization rules per
   decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md §4.2:
     - CRLF → LF
     - trailing whitespace stripped (except markdown hard-breaks)
     - 2+ consecutive blank lines collapsed to 1
     - leading + trailing blank lines stripped (canonical form:
       non-empty bodies end with exactly ONE trailing newline; empty
       bodies remain the empty string)
   Per §4.3, this transformation is applied on PARSE for canonical
   storage; emission produces normalized output too — round-trip is
   idempotent at the second parse, not byte-exact with the first parse's
   source.  Code-block interiors are CURRENTLY normalized (Stage B.2
   minimum-viable); preserving code-block interiors verbatim is a Stage
   B.3 follow-up requiring fenced-block awareness."
  [body]
  (when body
    (let [s (-> body
                normalize-line-endings
                strip-trailing-non-hardbreak-whitespace
                collapse-multi-blank-lines
                str/trim)]
      (if (empty? s) "" (str s "\n")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class-aware frontmatter ↔ slot mapping
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Per-class frontmatter-key→slot aliases live on the class entity itself
;; via the `:dt/codec-aliases` schema attribute, read at runtime via
;; `dt/codec-aliases-of`.  Keyword-typed slot detection comes from the
;; metamodel via `(= :db.type/keyword (dt/range-of slot))`.
;;
;; The prior shape (hardcoded `known-class-slot-aliases` +
;; `known-class-keyword-slots` maps keyed on :mm/Memory) was a substrate-
;; layering violation — substrate code carrying consumer-class-specific
;; knowledge — per
;; interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md
;; + interaction/never_minimum_viable_in_substrate_without_explicit_authorization_2026_05_13.md.
;; Stage C of plans/sandbar_codex_review_remediation_arc_2026_05_13.md
;; replaced those hardcoded maps with the introspection-driven shape
;; below, using the new `dt/codec-aliases-of` primitive from Stage A.

(defn- class-slot-namespace
  "Derive the property-namespace for a class.  Convention is
   `<class-namespace>.<lowercase-class-local>`.

   Examples:
     :mm/Memory  → \"mm.memory\"
     :mm/Section → \"mm.section\"
     :auth/User  → \"auth.user\""
  [class-ident]
  (str (namespace class-ident) "." (str/lower-case (name class-ident))))

(defn- body-slot-for
  "The body slot ident for a class.  Convention: `<class-prop-ns>/body-raw`
   for memory-level classes (which preserve the raw whole-document body);
   `<class-prop-ns>/body` for section-level classes.

   Memory-level classes use `body-raw` so the slot name signals 'preserved
   verbatim, before section decomposition'.  Section-level classes use
   `body` (no `-raw` suffix) because section content has already been
   decomposed into the heading-bounded slice."
  [class-ident]
  ;; Walk the class chain — Memory-subclasses (:mm/Decision, :mm/Plan, etc.)
  ;; inherit :mm.memory/body-raw via :dt/subclass-of :mm/Memory.  Substrate-
  ;; pure: NO hardcoded consumer-class knowledge per
  ;; interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md.
  ;; Resolution: walk ancestors, find first class that declares its own
  ;; `<ns>/body-raw` slot; fall back to `<ns>/body` (Section convention).
  ;; Per plans/codec_subclass_routing_follow_up_arc_2026_05_21.md Stage 1.5.
  (let [chain           (cons class-ident (dt/ancestors-of class-ident))
        effective-slots (dt/slots-of class-ident)]
    (or
      ;; Any ancestor that declares its own <ns>/body-raw slot (e.g.,
      ;; :mm/Memory declares :mm.memory/body-raw; :mm/Actor declares
      ;; :mm.actor/body-raw).  Walk leaf-to-root; first hit wins.
      (some (fn [c]
              (let [candidate (keyword (class-slot-namespace c) "body-raw")]
                (when (contains? effective-slots candidate)
                  candidate)))
            chain)
      ;; Else build <leaf-ns>/body (Section convention; sections
      ;; carry decomposed content under :mm.section/body).
      (keyword (class-slot-namespace class-ident) "body"))))

(defn frontmatter-key->slot
  "Map a YAML frontmatter key (keyword) to the slot ident for the given
   class.  Resolution:

     1. Look up class-declared alias via `dt/codec-aliases-of`
        (reads the `:dt/codec-aliases` schema attribute on the class).
     2. Fall back to the namespace-prefixing convention:
        `:<class-namespace>.<lowercase-class-local>/<frontmatter-key>`.

   Example — `:mm/Memory` declares
   `:dt/codec-aliases [[:type :mm.memory/memory-type]]` in
   `schema/mm.edn`; this function reads that declaration via
   `dt/codec-aliases-of :mm/Memory` and resolves `:type` →
   `:mm.memory/memory-type` (avoiding the :dt/type system-attribute
   collision)."
  [class-ident yaml-key]
  (or
   ;; (1) Class-declared alias — walks the hierarchy so Memory-subclasses
   ;;     (e.g. :mm/Decision) inherit :mm/Memory's `:type → :mm.memory/memory-type`.
   (get (dt/effective-codec-aliases-of class-ident) yaml-key)
   ;; (2) Walk ancestor chain; for each ancestor build :<ancestor-prop-ns>/<key>
   ;;     and check if it's a declared (effective) slot.  Restricted to
   ;;     mm-class-prefixed candidates (not system attributes like :dt/type
   ;;     / :db/*) — the candidate is constructed under each class's own
   ;;     slot-namespace.  Leaf wins for collisions.  Lets :mm/Decision
   ;;     pick up :mm.memory/name (inherited) without hijacking :dt/type.
   (let [effective-slots (dt/slots-of class-ident)
         chain           (cons class-ident (dt/ancestors-of class-ident))]
     (some (fn [c]
             (let [candidate (keyword (class-slot-namespace c) (name yaml-key))]
               (when (contains? effective-slots candidate)
                 candidate)))
           chain))
   ;; (3) Fallback — leaf-class namespacing (for novel keys not yet
   ;;     declared as slots; supports incremental class expansion).
   (keyword (class-slot-namespace class-ident) (name yaml-key))))

(defn- coerce-string->keyword
  "Coerce a YAML-parsed string to a keyword for a keyword-typed slot.
   Lists of strings → vectors of keywords.  Pass-through otherwise."
  [v]
  (cond
    (keyword? v) v
    (string?  v) (keyword v)
    (sequential? v) (mapv coerce-string->keyword v)
    :else v))

(defn- coerce-string->instant
  "Coerce a YAML-parsed string to a java.util.Date for an instant-typed
   slot.  Accepts ISO-8601 forms understood by `clojure.instant/read-
   instant-date` (date-only YYYY-MM-DD; datetime with offset; etc.).
   Lists of strings → vectors of Dates.  Date / Instant pass-through.
   Falls back to the original value on parse failure (the transact
   layer will surface a loud type error if the shape doesn't fit —
   the right discipline per F-S-002 fail-loud).  Added 2026-05-20 per
   F#15 of memory/plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md +
   Stage 2.A of the bootstrap-memory-substrate sub-arc."
  [v]
  (cond
    (instance? java.util.Date v) v
    (string? v)     (try (clojure.instant/read-instant-date v)
                         (catch Exception _ v))
    (sequential? v) (mapv coerce-string->instant v)
    :else v))

(defn- keyword-typed-slot?
  "Returns true if `slot-ident`'s declared `:dt/range` is `:db.type/keyword`.

   Replaces the prior hardcoded `known-class-keyword-slots` map.  Reads
   the slot's range via `dt/range-of` — the metamodel introspection
   path."
  [slot-ident]
  (= :db.type/keyword (dt/range-of slot-ident)))

(defn- instant-typed-slot?
  "Returns true if `slot-ident`'s declared `:dt/range` is
   `:db.type/instant`.  Reads the slot's range via `dt/range-of`."
  [slot-ident]
  (= :db.type/instant (dt/range-of slot-ident)))

(defn- long-typed-slot?
  "Returns true if `slot-ident`'s declared `:dt/range` is
   `:db.type/long`.  Reads the slot's range via `dt/range-of`."
  [slot-ident]
  (= :db.type/long (dt/range-of slot-ident)))

(defn- boolean-typed-slot?
  "Returns true if `slot-ident`'s declared `:dt/range` is
   `:db.type/boolean`."
  [slot-ident]
  (= :db.type/boolean (dt/range-of slot-ident)))

(defn- coerce-string->long
  "Parse a string to a Long.  Pass-through for non-string values
   (clj-yaml may already return native longs from numeric YAML
   literals; this only coerces if the value arrived as a string)."
  [v]
  (cond
    (integer? v) (long v)
    (string? v)  (Long/parseLong (str/trim v))
    :else v))

(defn- coerce-string->boolean
  "Parse a string to a Boolean.  Pass-through for non-string values."
  [v]
  (cond
    (boolean? v) v
    (string? v)  (case (str/lower-case (str/trim v))
                   ("true" "yes" "y" "on")  true
                   ("false" "no" "n" "off") false
                   v)
    :else v))

(defn- slot-declared?
  "Returns true when `slot-ident` is a declared attribute on the
   metamodel (i.e., `dt/range-of` returns a non-nil range).  Used by
   `frontmatter->slots` to drop unknown frontmatter keys rather than
   transacting them as non-existent attributes — per F#16 of the
   0.1.1 co-evolution arc (sandbar adapts to the corpus's long-tail
   without failing on unfamiliar slots).  A future refinement may
   route unknowns into `:mm.memory/frontmatter` ref (:mm/Frontmatter
   entity with :mm.frontmatter/extra carrying the EDN map) rather
   than dropping."
  [slot-ident]
  (some? (dt/range-of slot-ident)))

;; F#18 resolution: look up the unique-identity slot for ref-target
;; classes via metamodel introspection (`dt/unique-identity-slot-of`)
;; rather than hardcoding a consumer-class → slot map.  Per the TIER-0
;; substrate-quality rule
;; `interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md`
;; — sandbar's codec must not carry hardcoded knowledge of corpus
;; classes (`:mm/Tag`).  The metamodel knows; query it.

(defn- ref-slot-target-class
  "Returns the target class ident for a ref-typed slot, or nil if the
   slot isn't a ref (i.e., `:dt/range` is `:db.type/*`)."
  [slot-ident]
  (let [range (dt/range-of slot-ident)]
    (when (and (keyword? range) (not= "db.type" (namespace range)))
      range)))

(defn- coerce-string->upsert-map
  "Wrap a string (or vec of strings) as a unique-identity upsert map.
   `\"foo\"` + unique-attr `:mm.tag/value` → `{:mm.tag/value \"foo\"}`.
   Vec → mapv.  Pass-through for non-strings.  Per F#18."
  [v unique-attr]
  (cond
    (string? v)     {unique-attr v}
    (sequential? v) (mapv (fn [x]
                            (if (string? x) {unique-attr x} x))
                          v)
    :else v))

(defn rel-path->memory-ident
  "Convert a corpus rel-path string to an :mm/Memory :db/ident keyword.
   The corpus convention has frontmatter typed-edges write rel-paths
   relative to the memory/ subtree (e.g., `cites: decisions/foo.md`),
   but the ingested entities have idents prefixed `:memory.<dir>/<name>`
   because the project.import path passes the full `memory/<dir>/<name>.md`
   rel-path through `memory-ident-from-rel-path`.  Reconcile by adding
   the `memory/` prefix if absent before keyword derivation.

   'decisions/foo.md'         → :memory.decisions/foo
   'memory/decisions/foo.md'  → :memory.decisions/foo
   'patterns/x/y.md'          → :memory.patterns.x/y

   ALSO HANDLES THE IDENT-STRING FORM (per η.4 Target #1 fix 2026-05-28):
   Some FS frontmatter ref-slots historically were authored in ident-
   string form (`memory.<dotted-ns>/<name>` — dots in namespace, single
   `/` separating namespace from name, NO .md extension).  The naive
   path-form parse on this input produces a SPURIOUS DOUBLE-PREFIX
   ident (`:memory.memory.<dotted-ns>/<name>`) because it prepends
   `memory/` then splits on `/`.

   The fix: detect the ident-string form via regex (`^memory\\.[^/]+/.+`)
   and parse it directly as a keyword.  Other path-form inputs fall
   through to the original split-on-`/` logic unchanged.

   'memory.actors/foo'                      → :memory.actors/foo (NEW; was :memory.memory.actors/foo)
   'memory.libraries.patterns/scheduler'    → :memory.libraries.patterns/scheduler (NEW)

   Returns nil for unparseable input.

   Public per Gap 1 — consumers (MCP `sandbar.entity.find-by-rel-path`
   verb + others) need the canonical rel-path→ident conversion to avoid
   re-implementing it.  Per MCP cutover exercise 2026-05-22 (inbox
   capture)."
  [rel-path]
  (let [no-ext (str/replace rel-path #"\.md$" "")
        ;; η.4 Target #1 fix (2026-05-28): detect ident-string form to
        ;; avoid the double-prefix bug that surfaced 233 entities / 464
        ;; ref-slot-mismatches in the η.3 audit.  Ident-string form is
        ;; `memory.<dotted-ns>/<name>` where the namespace has DOTS not
        ;; slashes between segments.  Detection: starts with `memory.`
        ;; (NOT `memory/`) AND has exactly one `/` separating ns from name.
        ident-form (re-matches #"^(memory(?:\.[^/]+)+)/([^/]+)$" no-ext)]
    (if ident-form
      ;; Ident-string form: parse directly as keyword (no path-form
      ;; reconciliation).
      (let [[_ ns-str nm-str] ident-form]
        (keyword ns-str nm-str))
      ;; Path form: prepend `memory/` if absent + derive keyword.
      (let [with-mem   (if (str/starts-with? no-ext "memory/")
                         no-ext
                         (str "memory/" no-ext))
            parts      (str/split with-mem #"/")
            ns-parts   (butlast parts)
            local-name (last parts)]
        (when (and (seq ns-parts) local-name)
          (keyword (str/join "." ns-parts) local-name))))))

(defn- coerce-rel-path->ident-upsert
  "Coerce a rel-path string (or vec) to a `:db/ident` upsert map for
   cross-tx ref resolution.  Datomic's natural `:db/ident` upsert
   semantics handle the target-not-yet-loaded case: if `:memory.decisions/foo`
   doesn't exist yet, the transact creates a STUB entity with just
   `:db/ident :memory.decisions/foo`; when the actual memorial loads
   later, it upserts via the same ident.

   'decisions/foo.md'         → {:db/ident :memory.decisions/foo}
   Vec of strings             → mapv

   Per decisions/mm_memory_typed_edge_migration_string_to_ref_2026_05_21.md."
  [v]
  (letfn [(coerce-one [s]
            (if-let [ident (rel-path->memory-ident s)]
              {:db/ident ident}
              s))]
    (cond
      (string? v)     (coerce-one v)
      (sequential? v) (mapv (fn [x] (if (string? x) (coerce-one x) x)) v)
      :else v)))

(defn frontmatter->slots
  "Transform a YAML-parsed frontmatter map into a slot map for the given
   class.  Each key is run through `frontmatter-key->slot`; values are
   coerced per slot type:

   - string → keyword for keyword-typed slots
   - string → java.util.Date for instant-typed slots
   - string → `{unique-attr string}` upsert-map for ref-typed slots
     whose target class has a known `:db.unique/identity` attr (per
     `class->unique-identity`)

   Unknown slots (no `dt/range-of`) are DROPPED with a debug-log; this
   prevents the codec from emitting non-existent-attribute idents that
   would fail at transact.

   Returns an ordered-map (per `sandbar.codec.ordered-map`) preserving
   the frontmatter-map's insertion order — emit-side uses this to
   round-trip source frontmatter key ordering."
  [class-ident frontmatter-map]
  (let [out (om/create)]
    (doseq [[k v] frontmatter-map
            :let [slot (frontmatter-key->slot class-ident k)]
            :when (slot-declared? slot)
            ;; Stage 5 Phase A.6 — drop frontmatter string values destined
            ;; for :uuid-typed slots (e.g., the corpus's legacy
            ;; `identity: memory/types/concept-group.md` strings in type
            ;; memorials).  Schema declares the slot as :db.type/uuid;
            ;; passing a path-string crashes Datomic.  Architectural
            ;; identity scheme (deterministic v5 UUIDs per
            ;; `questions/identity_provenance_contexts_partition_firewall_for_uuid_scheme_2026_05_22.md`)
            ;; is the proper resolution; this guard stops the ingest
            ;; crash while that arc is designed.  :db/ident sufficies
            ;; for upsert in the meantime.
            :when (not (and (string? v)
                            (= :db.type/uuid (dt/range-of slot))))
            ;; Stage 5 Phase A.6 — drop empty values (empty string or empty
            ;; vec).  Corpus convention uses `parent: []` or
            ;; `primary-parent: ""` to express "no parent set"; the codec
            ;; would otherwise coerce `""` to an empty tempid (`{:db/ident :}`)
            ;; which Datomic rejects ("tempid '' used only as value").
            ;; Dropping the slot at parse-time = same semantic as "not set".
            :when (not (or (and (string? v) (clojure.string/blank? v))
                           (and (sequential? v) (empty? v))))
            ;; Stage 5 Phase A.6 — drop vec value into cardinality-one slot
            ;; (corpus has YAML lists for slots the schema declares scalar;
            ;; e.g., `affects:\n  - etc/lib/memory.clj:144-220` into
            ;; :mm.bug/affects which is :db.cardinality/one :db.type/string).
            ;; Until the schema is reconciled OR the corpus normalized,
            ;; drop these at the codec level to prevent the crash.
            :when (not (and (sequential? v)
                            (not (string? v))
                            (dt/cardinality-one? slot)))
            ;; Stage 5 Phase A.6 — drop non-date string into :instant slot.
            ;; Corpus convention sometimes uses natural-language session
            ;; labels (e.g., `applies-in-session-from:
            ;; 2026-05-07-session-after-this-authorization-was-granted`)
            ;; in :inst-typed slots.  coerce-string->instant fails-loud by
            ;; passing through unchanged; transact then crashes.  Skip the
            ;; slot when the string can't be parsed as an ISO date.
            :when (not (and (string? v)
                            (= :db.type/instant (dt/range-of slot))
                            (try (clojure.instant/read-instant-date v) false
                                 (catch Exception _ true))))
            :let [target-class (ref-slot-target-class slot)
                  unique-attr  (when target-class (dt/unique-identity-slot-of target-class))
                  v' (cond
                       (keyword-typed-slot? slot)
                       (coerce-string->keyword v)

                       (instant-typed-slot? slot)
                       (coerce-string->instant v)

                       (long-typed-slot? slot)
                       (coerce-string->long v)

                       (boolean-typed-slot? slot)
                       (coerce-string->boolean v)

                       ;; Ref-slot with string values + CLASS-SPECIFIC unique
                       ;; attr (e.g., :mm/Tag → :mm.tag/value): wrap as upsert
                       ;; map.  Per F#18.  Skip when:
                       ;;  (a) unique-attr resolves to :db/ident — that case
                       ;;      wants ident-upsert (next branch), not string-
                       ;;      upsert (would produce {:db/ident <string>}
                       ;;      which Datomic rejects).
                       ;;  (b) unique-attr is :db.type/uuid-typed (e.g.,
                       ;;      :mm.memory/identity on :mm/Memory subclasses) —
                       ;;      string values can't go directly into a uuid
                       ;;      slot.  Fall through to the :db/ident-based
                       ;;      upsert (next branch) which produces
                       ;;      {:db/ident :memory.predicates/consumes} —
                       ;;      already-proven path for :mm/Memory subclass
                       ;;      refs per
                       ;;      `decisions/mm_memory_typed_edge_migration_string_to_ref_2026_05_21.md`.
                       ;;      Per Dan-directive 2026-05-22 the v5-UUID
                       ;;      derivation (per
                       ;;      `decisions/clj_uuid_based_urn_scheme_for_memory_model_2026_05_12.md`)
                       ;;      will land as a separate enhancement that
                       ;;      sets :mm.memory/identity ON every entity at
                       ;;      parse-time; this branch just stops the
                       ;;      string-into-uuid-slot crash.
                       (and unique-attr
                            (not= :db/ident unique-attr)
                            (not= :db.type/uuid (dt/range-of unique-attr))
                            (or (string? v)
                                (and (sequential? v) (every? string? v))))
                       (coerce-string->upsert-map v unique-attr)

                       ;; Ref-slot WITHOUT class-specific unique attr (e.g.,
                       ;; :dt/Resource, :mm/Memory, :mm/Actor — classes whose
                       ;; instances are addressed by :db/ident): coerce
                       ;; rel-path strings to :db/ident upsert maps via the
                       ;; corpus's memory-ident convention.  Per
                       ;; decisions/mm_memory_typed_edge_migration_string_to_ref_2026_05_21.md.
                       (and target-class
                            (or (string? v)
                                (and (sequential? v) (every? string? v))))
                       (coerce-rel-path->ident-upsert v)

                       :else v)]]
      (om/put! out slot v'))
    out))

(defn- coerce-keyword->string
  "Coerce a keyword value to its full-form string for YAML emission
   (`:decisions/foo` → `\"decisions/foo\"`; `:decision` → `\"decision\"`).
   Uses `subs (str ...) 1` to strip the leading `:` rather than `name`
   so namespaced keywords preserve their namespace.  Lists of keywords
   → vectors of strings.  Pass-through otherwise."
  [v]
  (cond
    (keyword? v) (subs (str v) 1)
    (sequential? v) (mapv coerce-keyword->string v)
    :else v))

(defn- invert-aliases
  "Invert the class's codec-aliases map for emission — slot-ident → yaml-key.
   Reads aliases via `dt/effective-codec-aliases-of` (walks the class
   hierarchy so emit recognizes inherited aliases — e.g., :mm/Decision
   inherits :mm/Memory's `:type → :mm.memory/memory-type` mapping and
   emits `type:` for the :mm.memory/memory-type slot).  Per the Stage 1.5
   codec slot-inheritance fix."
  [class-ident]
  (into {}
        (for [[k v] (dt/effective-codec-aliases-of class-ident)]
          [v k])))

(defn slot->frontmatter-key
  "Map a slot ident back to a YAML frontmatter key.  Inverse of
   `frontmatter-key->slot`.  Resolution: class-declared alias (via
   `dt/codec-aliases-of`) first; fall back to `(keyword (name slot-ident))`."
  [class-ident slot-ident]
  (or (get (invert-aliases class-ident) slot-ident)
      (keyword (name slot-ident))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; YAML emission helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- memory-ident->rel-path
  "Inverse of `rel-path->memory-ident` — convert a memorial :db/ident
   keyword back to a corpus rel-path string.

   :memory.types/task         → 'types/task.md'
   :memory.patterns.x/y       → 'patterns/x/y.md'

   Returns nil for keywords whose namespace doesn't start with 'memory.'
   (those weren't derived from rel-paths)."
  [ident]
  (let [ns-part (some-> ident namespace)
        nm      (some-> ident name)]
    (when (and ns-part nm (str/starts-with? ns-part "memory."))
      (let [dirs (-> ns-part
                     (subs (count "memory."))
                     (str/split #"\."))]
        (str (str/join "/" dirs) "/" nm ".md")))))

(defn- unwrap-upsert-map
  "Reverse the parse-side `{unique-attr value}` wrapping that
   `coerce-string->upsert-map` (tag case) or `coerce-rel-path->ident-upsert`
   (ref-via-:db/ident case) applies to ref-slot values.  Used at emit
   time so the YAML wire-form shows plain strings instead of nested maps.

   Three reverse shapes:
   - `{:mm.tag/value 'foo'}` → 'foo' (unwrap tag value)
   - `{:db/ident :memory.types/foo}` → 'types/foo.md' (rel-path via ident)
   - `{<other-keyword> <string>}` → <string> (generic single-key upsert)

   Single map → string.
   Vector of maps → vector of strings.
   Pass-through for non-upsert shapes."
  [v]
  (letfn [(ident-upsert? [x]
            ;; {:db/ident <keyword>} — produced by coerce-rel-path->ident-upsert
            (and (map? x) (= 1 (count x))
                 (= :db/ident (first (keys x)))
                 (keyword? (get x :db/ident))))

          (string-upsert? [x]
            ;; {<keyword> <string>} — produced by coerce-string->upsert-map (tag case)
            (and (map? x) (= 1 (count x))
                 (let [k (first (keys x))]
                   (and (keyword? k) (string? (get x k))))))

          (unwrap-one [x]
            (cond
              (ident-upsert? x)
              (or (memory-ident->rel-path (:db/ident x))
                  ;; Fall back to keyword name if the ident doesn't fit
                  ;; the memory.<dirs>/<name> shape.
                  (subs (str (:db/ident x)) 1))

              (string-upsert? x)
              (first (vals x))

              :else x))]
    (cond
      ;; Single upsert-map (either string or ident shape)
      (or (ident-upsert? v) (string-upsert? v))
      (unwrap-one v)

      ;; Vector of upsert-maps
      (and (sequential? v)
           (every? (some-fn ident-upsert? string-upsert?) v))
      (mapv unwrap-one v)

      :else v)))

(defn- midnight-utc?
  "True iff `^java.util.Date d` represents 00:00:00.000 UTC — i.e., the
   parse-side coerced a date-only ISO string (`YYYY-MM-DD`) without a
   time portion.  Used by `emit-frontmatter` to detect when a Date value
   should serialize back as date-only (preserving source byte-form per
   the canonical-export ADR's round-trip-clean goal)."
  [^java.util.Date d]
  (let [cal (doto (java.util.Calendar/getInstance (java.util.TimeZone/getTimeZone "UTC"))
              (.setTime d))]
    (and (zero? (.get cal java.util.Calendar/HOUR_OF_DAY))
         (zero? (.get cal java.util.Calendar/MINUTE))
         (zero? (.get cal java.util.Calendar/SECOND))
         (zero? (.get cal java.util.Calendar/MILLISECOND)))))

(defn- coerce-instant->string
  "Inverse of `coerce-string->instant` — emit Date values as ISO strings.
   Date-only (midnight-UTC) → `YYYY-MM-DD`; full datetime → ISO-8601 with
   `T...Z`.  Preserves the corpus's predominant date-only convention for
   `created:` / `last-touched:` / `last-reviewed:` slots while still
   supporting timestamped values when present.

   Per `decisions/markdown_as_canonical_sandbar_export_format_2026_05_12.md`
   M.3 (per-entity markdown shape mirrors corpus memorial shape) +
   Dan-directive (cleanly round-trippable across meta-types)."
  [v]
  (cond
    (instance? java.util.Date v)
    (if (midnight-utc? v)
      (let [fmt (java.text.SimpleDateFormat. "yyyy-MM-dd")]
        (.setTimeZone fmt (java.util.TimeZone/getTimeZone "UTC"))
        (.format fmt ^java.util.Date v))
      (let [fmt (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'")]
        (.setTimeZone fmt (java.util.TimeZone/getTimeZone "UTC"))
        (.format fmt ^java.util.Date v)))

    (sequential? v) (mapv coerce-instant->string v)
    :else v))

(defn- emit-frontmatter
  "Emit a slot map as YAML frontmatter text (without the `---` fences).
   Uses block-style YAML for readability.  Empty map → empty string.

   Value transformations applied (in order):
   - Ref-slot upsert-maps unwrapped back to plain strings (reverses
     parse-side `coerce-string->upsert-map`).  Without this, emitted YAML
     would show `tags: [{mm.tag/value: x}]` instead of `tags: [x]`.
   - Instant-typed slots' Date values serialized via `coerce-instant->string`
     — date-only ISO (`YYYY-MM-DD`) when the time-portion is midnight UTC,
     full ISO-8601 otherwise.  Mirrors the corpus's predominant convention
     where `created:` / `last-touched:` / `last-reviewed:` use date-only.
   - Keyword-typed slot values coerced keyword → bare-name string so YAML
     emits idiomatic bare names (e.g., `type: decision` instead of `type:
     :decision`).

   Slot ordering is INTROSPECTED from the class's `:dt/codec-slot-order`
   schema attribute (via `dt/codec-slot-order-of`).  Slots present in
   the entity but absent from the class declaration are appended at the
   end in entity-key iteration order — they still emit, just after the
   declared canonical slots.

   Per `decisions/markdown_as_canonical_sandbar_export_format_2026_05_12.md`
   M.3 + `decisions/slot_order_declared_by_class_introspectable_2026_05_20.md`
   (Dan-directive 2026-05-20: 'slot order should be declared by the class
   and introspectable').  Replaces the prior `:codec/key-order` metadata
   threading pattern."
  [slot-map class-ident]
  (if (empty? slot-map)
    ""
    (let [;; Introspect the class-declared canonical slot order.  Filter
          ;; to slots present in this entity (the class may declare more
          ;; slots than any individual entity carries).  Then append any
          ;; entity slots not in the class declaration so unknown / extra
          ;; slots still emit.
          class-order     (dt/effective-codec-slot-order-of class-ident)
          declared-here   (filterv (fn [k] (contains? slot-map k)) class-order)
          declared-set    (set declared-here)
          extras          (filterv (fn [k] (not (contains? declared-set k)))
                                   (keys slot-map))
          effective-order (into declared-here extras)
          ;; Build ordered yaml-map via the sandbar.codec.ordered-map
          ;; abstraction (preserves order at any size; backend swap to
          ;; ordered-collections insertion-ordered-map per Dan-directive
          ;; 2026-05-20).
          yaml-map (om/create)
          _        (doseq [slot effective-order
                           :when (contains? slot-map slot)]
                     (let [v        (get slot-map slot)
                           yaml-key (slot->frontmatter-key class-ident slot)
                           yaml-val (cond->> v
                                      true                         unwrap-upsert-map
                                      (instant-typed-slot? slot)   coerce-instant->string
                                      (keyword-typed-slot? slot)   coerce-keyword->string)]
                       (om/put! yaml-map yaml-key yaml-val)))
          raw      (yaml/generate-string yaml-map
                                          :dumper-options {:flow-style :block
                                                           :default-scalar-style :plain})]
      ;; clj-yaml conservatively single-quotes date-shaped strings (YAML 1.1
      ;; ambiguity with the implicit date type).  The corpus convention is
      ;; UNQUOTED dates (`created: 2026-05-11`); the lenient parser handles
      ;; either.  Post-process to strip surrounding quotes from date-shaped
      ;; values so the wire form matches the corpus's canonical convention.
      ;; Matches: 'YYYY-MM-DD' + 'YYYY-MM-DDTHH:MM:SSZ' + 'YYYY-MM-DDTHH:MM:SS.sssZ'.
      (str/replace raw
                   #"'(\d{4}-\d{2}-\d{2}(?:T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z?)?)'"
                   "$1"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MarkdownCodec record — implements proto/Codec
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord MarkdownCodec []
  proto/Codec

  (parse [_ input opts]
    (let [class-ident (or (:class opts)
                          (throw (ex-info "sandbar.codec.markdown/parse requires :class opt"
                                          {:opts opts})))
          [fm-text body-text] (split-frontmatter input)
          fm-map      (when (and fm-text (not (str/blank? fm-text)))
                        (parse-frontmatter-text fm-text))
          slot-map-ordered (frontmatter->slots class-ident fm-map)
          slot-map    (om/->clojure-map slot-map-ordered)
          normalized  (normalize-body body-text)]
      ;; Slot-order is NOT carried on the entity.  Emit introspects the
      ;; class-declared canonical order via `:dt/codec-slot-order` per
      ;; decisions/slot_order_declared_by_class_introspectable_2026_05_20.md.
      (merge {:dt/type class-ident
              (body-slot-for class-ident) (or normalized "")}
             slot-map)))

  (emit [_ entity opts]
    (let [class-ident   (or (:dt/type entity)
                            (throw (ex-info "sandbar.codec.markdown/emit requires :dt/type on entity"
                                            {:entity entity})))
          body-slot     (body-slot-for class-ident)
          body-text     (get entity body-slot "")
          ;; Frontmatter slots = all keys except :dt/type + body-slot,
          ;; PLUS exclude Datomic-internal + entity-locator namespaces
          ;; so persisted entities don't leak :db/id / :db/ident /
          ;; :mm.memory/rel-path into wire format.  Phase U Stage U-2
          ;; UR-6 + UR-7 fix per
          ;; observations/sandbar_codec_emit_leaks_db_internal_attrs_wire_format_2026_05_14.md
          fm-slots      (into {}
                              (remove (fn [[k _]]
                                        (or (= :dt/type k)
                                            (= body-slot k)
                                            (and (keyword? k)
                                                 (when-let [ns (namespace k)]
                                                   (or (= "db" ns)
                                                       (str/starts-with? ns "db.")
                                                       (= :mm.memory/rel-path k)))))))
                              entity)
          fm-yaml       (emit-frontmatter fm-slots class-ident)
          normalized    (normalize-body body-text)
          ;; Empty body emits no trailing newline; non-empty body
          ;; already ends with exactly one trailing newline per
          ;; normalize-body's canonical-form invariant.
          body-final    (or normalized "")]
      (if (str/blank? fm-yaml)
        body-final
        (str frontmatter-delim "\n"
             fm-yaml
             frontmatter-delim "\n"
             body-final))))

  (mime-types [_]
    ["text/markdown"
     "text/x-markdown"])

  (supports? [_ _class-ident]
    ;; Generic codec — works with any class.  Class-aware slot mapping
    ;; handled via runtime `dt/codec-aliases-of` introspection + the
    ;; namespace-prefix convention rule.
    true)

  (round-trip-test [self entity]
    (let [emitted  (proto/emit self entity {})
          reparsed (proto/parse self emitted {:class (:dt/type entity)})]
      {:ok?      (= entity reparsed)
       :emitted  emitted
       :reparsed reparsed
       :diff     (when (not= entity reparsed)
                   {:original entity
                    :reparsed reparsed
                    :missing-from-reparsed
                    (into {}
                          (for [[k v] entity
                                :when (not= v (get reparsed k))]
                            [k {:original v :reparsed (get reparsed k)}]))})})))

(defn make-codec
  "Construct a MarkdownCodec instance.  Stateless — singleton-friendly."
  []
  (->MarkdownCodec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage B.3 — Section-tree parse + emit
;;
;; Per B.0 ADR §2 (path-derived idents) + §3 (sibling-chain semantics).
;; Walks markdown headings; produces mm/Section entity-specs with
;; :parent / :next-sibling / :previous-sibling refs set bidirectionally.
;;
;; The parse + emit functions in this section are exposed publicly so
;; callers can reach them via `sandbar.codec.markdown/parse-document` +
;; `sandbar.codec.markdown/emit-document` (the coll-shape variants), in
;; addition to the standard codec/parse + codec/emit calls.

(def ^:const max-heading-level
  "ATX heading depth cap.  Per CommonMark §4.2 + B.0 ADR §1.2."
  6)

(defn- slugify
  "Produce a URL-safe slug from a heading text.  Lowercase, spaces → hyphens,
   strip non-alphanumeric-hyphen.  Per B.0 ADR §2.2."
  [s]
  (-> (or s "")
      str/lower-case
      (str/replace #"\s+" "-")
      (str/replace #"[^a-z0-9-]+" "")
      (str/replace #"-+" "-")
      (str/replace #"^-|-$" "")))

(defn memory-ident-from-rel-path
  "Compute the mm/Memory entity ident from a corpus rel-path.  Per B.0
   ADR §2.2.

   Examples:
     'decisions/foo.md' → :decisions/foo
     'patterns/architectural/sandbar/x.md' → :patterns.architectural.sandbar/x"
  [rel-path]
  (let [no-ext (str/replace rel-path #"\.md$" "")
        parts  (str/split no-ext #"/")
        ns-parts   (butlast parts)
        local-name (last parts)]
    (when (and (seq ns-parts) local-name)
      (keyword (str/join "." ns-parts) local-name))))

(defn section-ident
  "Compute path-derived ident for a section.  Per B.0 ADR §2.2.

   memory-ident  — :decisions/foo
   heading-chain — vector of ancestor heading titles, e.g. [\"Context\" \"Decision\"]
   → :decisions/foo__context__decision"
  [memory-ident heading-chain]
  (when (and memory-ident (seq heading-chain))
    (let [ns-part   (namespace memory-ident)
          name-part (name memory-ident)
          slug-chain (str/join "__" (map slugify heading-chain))]
      (keyword ns-part (str name-part "__" slug-chain)))))

(defn- parse-heading-line
  "Match an ATX heading line.  Returns `{:level N :title \"...\"}` or nil.
   Accepts headings with up to 3 leading spaces per CommonMark §4.2."
  [line]
  (when-let [m (re-matches #"^[ ]{0,3}(#{1,6})\s+(.+?)(?:\s*#*)?\s*$" line)]
    (let [level (count (nth m 1))
          title (str/trim (nth m 2))]
      (when (and (<= level max-heading-level)
                 (seq title))
        {:level level :title title}))))

(defn- extract-prologue
  "Return the prologue content from a markdown body — everything before
   the first heading line, including any trailing newline that connects
   the prologue to the first heading.

   Returns the prologue string (possibly empty) — never nil.

   `parse-sections` discards prologue content because there is no
   section to attach it to (the body buffer flush is a no-op until
   the first section opens).  `body-raw` on the memory entity retains
   the original full body, so `emit-document` extracts the prologue
   here and prepends it to the section-reconstructed body — preserving
   round-trip fidelity for documents that have content before the
   first heading.

   Per ultrareview finding #7 (codec/markdown.clj:614) — `emit-document
   drops prologue content (body before first heading)`."
  [body-raw]
  (if (str/blank? body-raw)
    ""
    (let [lines        (str/split body-raw #"\n" -1)
          prologue-lns (take-while (complement parse-heading-line) lines)]
      (cond
        ;; No heading found — whole body is prologue.  Emit as-is.
        (= (count prologue-lns) (count lines))
        body-raw

        ;; No prologue (first line is a heading)
        (empty? prologue-lns)
        ""

        ;; Prologue followed by heading — emit prologue lines + trailing \n
        :else
        (str (str/join "\n" prologue-lns) "\n")))))

(defn parse-sections
  "Walk a markdown body + produce a vector of mm/Section entity-specs in
   document order, with :parent / :next-sibling / :previous-sibling refs
   set bidirectionally per B.0 ADR §3.

   Inputs:
     body         — markdown body string (post-frontmatter)
     memory-ident — the host mm/Memory's :db/ident (e.g., :decisions/foo)

   Returns: vector of section maps; empty when body has no headings.

   On slug-collision (two sections at same level under same parent
   producing the same slug), throws ex-info per B.0 ADR §2.3."
  [body memory-ident]
  (when-not memory-ident
    (throw (ex-info "parse-sections requires memory-ident" {})))
  (let [lines             (str/split (or body "") #"\n" -1)
        sections          (atom [])
        path-stack        (atom [])           ; ancestor chain: vec of {:level :ident :title}
        sibling-tracker   (atom {})           ; {[parent-ident level] → last-sibling-ident}
        body-buf          (atom (StringBuilder.))
        ident->index      (atom {})           ; for O(1) sibling pointer rewrite
        ;; Code-fence tracking — lines inside ``` ... ``` blocks must
        ;; NOT be heading-parsed.  Round-trip stability requirement:
        ;; banner ASCII art, shell session captures, and other code-
        ;; fenced content can contain lines that LOOK like headings
        ;; (e.g., ` ## ##` from banner output) but are literal content.
        in-fence?         (atom false)
        fence-line?       (fn [line]
                            ;; Per CommonMark §4.5: code fence is 3+
                            ;; backticks (or 3+ tildes) at line start
                            ;; with optional leading 0-3 spaces.
                            (boolean (re-find #"^[ ]{0,3}(```|~~~)" line)))

        flush-body!
        (fn []
          (let [text (.toString ^StringBuilder @body-buf)]
            (reset! body-buf (StringBuilder.))
            (when (pos? (count @sections))
              (let [last-idx (dec (count @sections))]
                (swap! sections assoc-in [last-idx :mm.section/body]
                       (or (normalize-body text) ""))))))

        set-next-sibling-on!
        (fn [prev-ident new-ident]
          (when-let [idx (get @ident->index prev-ident)]
            (swap! sections assoc-in [idx :mm.section/next-sibling] new-ident)))]

    (doseq [line lines]
      ;; Toggle fence state on fence-delimiter lines.  The fence line
      ;; ITSELF is content, not a heading.  Lines INSIDE a fence are
      ;; literal content (banner ASCII art, code samples, etc.) and
      ;; MUST NOT be heading-parsed.
      (when (fence-line? line)
        (swap! in-fence? not))
      (if-let [{:keys [level title]} (and (not @in-fence?)
                                          (parse-heading-line line))]
        (do
          ;; Flush body buffer to the CURRENT-LAST section before opening
          ;; a new one (this section's body is the text between its
          ;; heading and the next heading at ANY level — per B.0 §1.2
          ;; exclusive semantics).
          (flush-body!)
          ;; Pop path-stack to (level - 1) — closes deeper sections.
          (swap! path-stack
                 (fn [stk]
                   (vec (take-while #(< (:level %) level) stk))))
          (let [parent        (or (some-> @path-stack last :ident) memory-ident)
                heading-chain (conj (mapv :title @path-stack) title)
                base-ident    (section-ident memory-ident heading-chain)
                ;; Auto-disambiguate slug collisions by appending -2 / -3 /
                ;; ... per round-trip-stability requirement.  The HEADING
                ;; TEXT is preserved verbatim on emit; only the IDENT
                ;; carries the suffix.  Round-trip-stable because slug
                ;; derivation is deterministic — same source produces same
                ;; disambiguated idents every time.  Per
                ;; decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md
                ;; (previously raised "Section ident collision" exception
                ;; rejecting the parse).
                ident         (loop [candidate base-ident
                                     n         1]
                                (if (contains? @ident->index candidate)
                                  (recur (keyword (namespace base-ident)
                                                  (str (name base-ident) "-" (inc n)))
                                         (inc n))
                                  candidate))
                tracker-key   [parent level]
                prev-sibling  (get @sibling-tracker tracker-key)]
            (let [section (cond-> {:dt/type           :mm/Section
                                   :db/ident          ident
                                   :mm.section/heading       title
                                   :mm.section/heading-level level
                                   :mm.section/parent        parent
                                   :mm.section/body          ""}
                            prev-sibling (assoc :mm.section/previous-sibling prev-sibling))]
              (swap! sections conj section)
              (swap! ident->index assoc ident (dec (count @sections)))
              ;; Wire previous sibling's :next-sibling backward
              (when prev-sibling
                (set-next-sibling-on! prev-sibling ident))
              ;; Update sibling tracker + path stack
              (swap! sibling-tracker assoc tracker-key ident)
              (swap! path-stack conj {:level level :ident ident :title title}))))
        ;; Non-heading line — append to body buffer
        (do
          (.append ^StringBuilder @body-buf ^String line)
          (.append ^StringBuilder @body-buf "\n"))))
    ;; Flush final accumulated body to the last section
    (flush-body!)
    @sections))

(defn first-section-of
  "Find the first section in the chain — the section whose :parent is
   `memory-ident` AND has no :previous-sibling.  Returns the section map
   or nil."
  [sections memory-ident]
  (first (filter (fn [s]
                   (and (= memory-ident (:mm.section/parent s))
                        (not (:mm.section/previous-sibling s))))
                 sections)))

(defn- peek-type-keyword
  "Peek at the frontmatter to extract the `type:` keyword value WITHOUT
   doing full class-specific slot mapping.  Used by `parse-document` for
   class-routing — the codec needs to know which class to parse AS before
   the class-aware frontmatter→slot pass runs.

   Returns the keyword form of the type value (`type: tag` → `:tag`), or
   nil when frontmatter is absent / `type:` is absent / value is unparseable.

   Per Stage 7.C of
   decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
   class-routing — the type-keyword maps to a class via
   `dt/class-for-codec-type-keyword`."
  [source]
  (let [[fm-text _body] (split-frontmatter source)
        fm-map          (when (and fm-text (not (str/blank? fm-text)))
                          (parse-frontmatter-text fm-text))
        t               (:type fm-map)]
    (cond
      (keyword? t) t
      (string?  t) (keyword t)
      :else        nil)))

(defn resolve-document-class
  "Resolve the codec target class for a markdown document based on its
   frontmatter `type:` value, via metamodel introspection (NOT hardcoded
   class knowledge per
   interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md).

   Resolution:
     1. Peek frontmatter `type:` value.
     2. Look up the class via `dt/class-for-codec-type-keyword` —
        returns the class whose `:dt/codec-type-keyword` declaration
        matches.
     3. Fall back to `:mm/Memory` when no class claims the type-keyword
        (the default for the corpus's universe of memorial documents).

   Examples:
     `type: tag`      → :mm/Tag  (when :mm/Tag declares :dt/codec-type-keyword :tag)
     `type: decision` → :mm/Memory  (no class claims :decision; :mm/Memory's
                                     :dt/codec-aliases consumes :type into
                                     :mm.memory/memory-type instead)
     no `type:`       → :mm/Memory  (default)

   Public so tests + tooling can dispatch on the resolved class
   independently."
  [source]
  (let [type-kw (peek-type-keyword source)]
    (or (try (dt/class-for-codec-type-keyword type-kw)
             (catch Exception _ nil))
        :mm/Memory)))

(defn- memory-class?
  "Returns true if `class-ident` is :mm/Memory or transitively a subclass of :mm/Memory.
   Per the section-decomposition contract — only Memory + its subclasses (e.g.,
   :mm/Decision, :mm/Plan, :mm/Observation, :mm/Pattern) decompose into section
   sub-entities; parallel classes like :mm/Tag do not.  Uses dt/subclass-of? to
   walk the :dt/subclass-of chain in the substrate."
  [class-ident]
  (or (= class-ident :mm/Memory)
      (try (dt/subclass-of? :mm/Memory class-ident)
           (catch Exception _ false))))

(defn parse-document
  "Full markdown document parse: split frontmatter + body, resolve the
   target class via `:dt/codec-type-keyword` routing, decompose into
   sections when appropriate, return a vector of entity-specs.

   Class routing (Stage 7.C — per
   decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md):
   The codec peeks the frontmatter's `type:` value + resolves a class
   via `dt/class-for-codec-type-keyword`.  Default routing target is
   :mm/Memory.  For :mm/Tag (and other classes lacking a section-tree
   convention), no section decomposition runs — the document is a
   single entity.

   Inputs:
     source   — markdown source text
     rel-path — corpus rel-path (e.g., 'decisions/foo.md' or
                'tags/audit.md') — REQUIRED for path-derived idents

   Returns:
     - For :mm/Memory: vector starting with the memory entity (carrying
       :mm.memory/rel-path + :mm.memory/first-section when sections present)
       followed by section entities in document order.
     - For non-:mm/Memory classes (e.g., :mm/Tag): single-element vector
       with the class entity.  No section decomposition; no rel-path slot
       (path is derivable from `memory/<plural>/<name>.md` convention)."
  [source rel-path]
  (let [memory-ident   (or (memory-ident-from-rel-path rel-path)
                           (throw (ex-info "parse-document requires a rel-path that yields a valid memory ident"
                                           {:rel-path rel-path})))
        resolved-class (resolve-document-class source)
        c              (make-codec)
        entity         (proto/parse c source {:class resolved-class})
        entity         (cond-> (assoc entity :db/ident memory-ident)
                         (memory-class? resolved-class)
                         (assoc :mm.memory/rel-path rel-path))]
    (if (memory-class? resolved-class)
      (let [body-raw (:mm.memory/body-raw entity)
            sections (parse-sections body-raw memory-ident)]
        (if (empty? sections)
          [entity]
          (let [first-sec (first-section-of sections memory-ident)]
            (into [(assoc entity :mm.memory/first-section (:db/ident first-sec))]
                  sections))))
      ;; Non-:mm/Memory class — single-entity vector; no section decomposition.
      [entity])))

(defn group-by-source
  "Walk a flat entity-spec vector from `sandbar.projection/ingest-graph` +
   group by source file.  Each `:mm/Memory` starts a new group; subsequent
   `:mm/Section` entities join that group until the next `:mm/Memory`.
   Returns a seq of vectors, each a complete one-file unit suitable for
   a single atomic Datomic transaction (via `entity-specs->tx-data`).

   Per F#17 of plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md — per-entity
   transactions can't resolve same-tx forward refs (e.g.,
   `:mm.memory/first-section` to an in-tx section).  Per-file atomic
   transactions resolve cross-entity refs via tempid translation.

   Promoted to public + codec-layer at 2026-05-20 consolidation per
   observations/sandbar_codec_md_entity_specs_to_tx_data_duplicates_mcp_prep_temp_ids_2026_05_20.md."
  [entities]
  (loop [acc [] cur [] [e & rst] entities]
    (cond
      (nil? e)
      (cond-> acc (seq cur) (conj cur))

      (memory-class? (:dt/type e))
      (recur (cond-> acc (seq cur) (conj cur)) [e] rst)

      :else
      (recur acc (conj cur e) rst))))

(defn- ref-slot?
  "Returns true if `slot-ident` is a `:db.type/ref`-typed attribute per
   the metamodel — sandbar's convention is `:dt/range` carries the target
   class keyword (e.g., `:mm/Tag`) for refs, vs `:db.type/*` primitives
   for scalars.

   Used by `entity-specs->tx-data` to identify ref slots whose values
   may need tempid translation.  Metamodel-driven; NO hardcoded slot
   knowledge per
   interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md."
  [slot-ident]
  (let [range (dt/range-of slot-ident)]
    (and (keyword? range)
         (not= "db.type" (namespace range)))))

(defn entity-specs->tx-data
  "Convert an entity-spec collection (e.g., from `parse-document` OR any
   other source that emits ident-form refs) into Datomic tx-data with
   TEMPID-based refs so a single `d/transact` resolves in-tx references
   atomically.

   Per bugs/sandbar_parse_document_tx_ordering_section_ident_resolution_2026_05_20.md
   + F#17 of plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md:
   Datomic's `:db/ident` resolution fires before tx-data is fully
   processed, so an entity's keyword-ident ref to a SIBLING in-tx entity
   fails (`:db.error/not-an-entity`).  Solution: assign string tempids
   to every entity (via `:db/id`) + translate every ref-slot value whose
   target is an in-tx sibling to the corresponding tempid.  Each entity's
   `:db/ident` assertion stays intact — that's the canonical post-tx
   addressing handle.

   GENERALIZED — works for ANY class, not just :mm/Memory + :mm/Section.
   Ref slots are identified via `ref-slot?` (metamodel-driven; reads
   `:dt/range` from the slot's :dt/Property definition).  Per the
   no-hardcoded-consumer-class-knowledge-in-substrate rule.

   Single-source-of-truth at the codec layer per
   decisions/sandbar_codec_layer_owns_wire_format_concerns_consumer_native_representation_2026_05_12.md
   — tx-shape IS a wire-format concern.  Consolidates the previously
   duplicated `sandbar.mcp.tools/prep-temp-ids` (F#17) into a single
   helper at the codec layer.

   Call this immediately before `d/transact`:

       @(d/transact conn (entity-specs->tx-data (parse-document src rel-path)))

   `parse-document` keeps the ident-form output so existing test fixtures
   + round-trip comparisons stay unaffected."
  [entity-specs]
  (let [ident->tempid (->> entity-specs
                           (map-indexed (fn [i e]
                                          (when-let [id (:db/ident e)]
                                            [id (str "tempid-" i)])))
                           (remove nil?)
                           (into {}))
        translate-value
        (fn [v]
          (cond
            (and (keyword? v) (contains? ident->tempid v))
            (get ident->tempid v)

            (sequential? v)
            (mapv (fn [x]
                    (if (and (keyword? x) (contains? ident->tempid x))
                      (get ident->tempid x)
                      x))
                  v)

            :else v))]
    (mapv (fn [e]
            (let [tid (get ident->tempid (:db/ident e))
                  e'  (reduce-kv
                        (fn [acc k v]
                          (assoc acc k
                                 (if (and (try (ref-slot? k) (catch Exception _ false)))
                                   (translate-value v)
                                   v)))
                        {}
                        e)]
              (cond-> e' tid (assoc :db/id tid))))
          entity-specs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section-tree emit — reconstruct markdown body from section chain

(defn- ^String emit-section-body
  "Build the section's emitted text — heading line + body + children (via
   chain walk).  Recursively emits sub-sections in chain order."
  [section-by-ident memory-ident section sb]
  (let [^StringBuilder sb sb
        heading-prefix    (apply str (repeat (:mm.section/heading-level section) "#"))
        title             (:mm.section/heading section)
        body              (:mm.section/body section)]
    (.append sb heading-prefix)
    (.append sb " ")
    (.append sb (or title ""))
    (.append sb "\n")
    (when (and body (not (str/blank? body)))
      (.append sb "\n")
      (.append sb body)
      (when-not (str/ends-with? body "\n")
        (.append sb "\n")))
    ;; Find first child + walk down via :next-sibling chain
    (let [this-ident (:db/ident section)]
      (loop [child (first (filter (fn [s]
                                    (and (= this-ident (:mm.section/parent s))
                                         (not (:mm.section/previous-sibling s))))
                                  (vals section-by-ident)))]
        (when child
          (.append sb "\n")
          (emit-section-body section-by-ident memory-ident child sb)
          (recur (some->> (:mm.section/next-sibling child)
                          (get section-by-ident))))))
    sb))

(defn emit-sections-body
  "Reconstruct the markdown body text from a vector of mm/Section entity-
   specs + the host memory-ident.  Walks the top-level chain via
   :first-section + recurses through sibling + parent links."
  [sections memory-ident first-section-ident]
  (let [section-by-ident (into {} (for [s sections] [(:db/ident s) s]))
        sb               (StringBuilder.)]
    (loop [section (get section-by-ident first-section-ident)
           first?  true]
      (when section
        (when-not first? (.append sb "\n"))
        (emit-section-body section-by-ident memory-ident section sb)
        (recur (some->> (:mm.section/next-sibling section)
                        (get section-by-ident))
               false)))
    (.toString sb)))

(def ^:private derived-memory-attrs
  "mm/Memory slots that are derived from the file's filesystem path or
   from the section chain — these MUST be stripped before emit so they
   don't leak into YAML frontmatter (where parse would interpret them
   as regular slots).  Re-derived on ingest from rel-path + heading
   walk."
  #{:db/ident
    :db/id
    :mm.memory/rel-path
    :mm.memory/first-section})

(defn emit-document
  "Full mm/Memory document emit: takes a vector of entity-specs (memory +
   sections); reconstructs frontmatter + body via section-tree walk;
   returns the markdown source string.

   Derived attributes (`:db/ident`, `:mm.memory/rel-path`,
   `:mm.memory/first-section`) are stripped before serialization — they
   re-derive from the file's filesystem path + the heading walk on
   ingest.

   Prologue preservation: any content in the memory's `:mm.memory/body-raw`
   that appears BEFORE the first heading line (which `parse-sections`
   intentionally discards because there is no section to attach it to)
   is extracted and prepended to the section-reconstructed body.  Per
   ultrareview finding #7.

   When the input is a single-entity vector (mm/Memory only, no sections),
   delegates to MarkdownCodec/emit (frontmatter + body-raw)."
  [entities]
  (let [memory   (first entities)
        sections (rest entities)
        memory-stripped (apply dissoc memory derived-memory-attrs)
        c        (make-codec)]
    (if (empty? sections)
      (proto/emit c memory-stripped {})
      (let [first-sec-ident (:mm.memory/first-section memory)
            section-body    (emit-sections-body sections (:db/ident memory) first-sec-ident)
            prologue        (extract-prologue (:mm.memory/body-raw memory))
            full-body       (if (str/blank? prologue) section-body (str prologue section-body))
            ;; Build a memory-with-body-from-sections+prologue for the codec's emit
            memory-for-emit (assoc memory-stripped :mm.memory/body-raw full-body)]
        (proto/emit c memory-for-emit {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience registration
;;
;; Consumers may call this at component-startup time to make the
;; markdown codec discoverable via `sandbar.codec/codec-for :markdown`.
;; Not invoked automatically on namespace load (side-effect-on-load is
;; an anti-pattern); explicit registration is the design.

(defn register!
  "Register a fresh MarkdownCodec instance with the codec mediator under
   the `:markdown` format keyword.  Idempotent — re-registering replaces
   the existing entry."
  []
  ;; Require here to avoid the cycle sandbar.codec → sandbar.codec.markdown.
  (let [register-fn (requiring-resolve 'sandbar.codec/register!)]
    (register-fn :markdown (make-codec))
    (log/info :SANDBAR/CODEC-MARKDOWN-REGISTERED)
    :markdown))
