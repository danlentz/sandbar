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
  (:require [clj-yaml.core          :as yaml]
            [clojure.string         :as str]
            [clojure.tools.logging  :as log]
            [sandbar.codec.protocol :as proto]
            [sandbar.db.datatype    :as dt]))

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
   the closing fence + its trailing newline."
  [source]
  (let [s (normalize-line-endings source)]
    (if (and s (str/starts-with? s (str frontmatter-delim "\n")))
      (let [after-open (subs s (inc (count frontmatter-delim)))
            ;; Find the closing `---` line — must be at line-start
            close-pattern #"(?m)^---\n?"
            close-match (re-find close-pattern after-open)]
        (if close-match
          (let [close-idx (str/index-of after-open close-match)
                fm (subs after-open 0 close-idx)
                body (subs after-open (+ close-idx (count close-match)))]
            [fm body])
          ;; Unterminated frontmatter — treat whole thing as body
          [nil s]))
      [nil s])))

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
   for memory-level classes (which preserve raw body); `<class-prop-ns>/body`
   for section-level classes."
  [class-ident]
  (let [prop-ns (class-slot-namespace class-ident)]
    (case class-ident
      :mm/Memory  :mm.memory/body-raw
      :mm/Section :mm.section/body
      (keyword prop-ns "body"))))

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
  (or (get (dt/codec-aliases-of class-ident) yaml-key)
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

(defn- keyword-typed-slot?
  "Returns true if `slot-ident`'s declared `:dt/range` is `:db.type/keyword`.

   Replaces the prior hardcoded `known-class-keyword-slots` map.  Reads
   the slot's range via `dt/range-of` — the metamodel introspection
   path."
  [slot-ident]
  (= :db.type/keyword (dt/range-of slot-ident)))

(defn frontmatter->slots
  "Transform a YAML-parsed frontmatter map into a slot map for the given
   class.  Each key is run through `frontmatter-key->slot`; values of
   keyword-typed slots (detected via `dt/range-of`) are coerced
   string → keyword.

   No per-class hardcoding — the codec reads the keyword-typed property
   set from the metamodel at runtime."
  [class-ident frontmatter-map]
  (into {}
        (for [[k v] frontmatter-map
              :let [slot (frontmatter-key->slot class-ident k)
                    v'   (if (keyword-typed-slot? slot)
                           (coerce-string->keyword v)
                           v)]]
          [slot v'])))

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
   Reads aliases via `dt/codec-aliases-of` (the runtime metamodel
   introspection path; no hardcoded substrate-side map)."
  [class-ident]
  (into {}
        (for [[k v] (dt/codec-aliases-of class-ident)]
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

(defn- emit-frontmatter
  "Emit a slot map as YAML frontmatter text (without the `---` fences).
   Uses block-style YAML for readability.  Empty map → empty string.
   Keyword-typed slot values (detected via `dt/range-of` →
   `:db.type/keyword`) are coerced keyword → bare-name string so YAML
   emits idiomatic bare names (e.g., `type: decision` instead of
   `type: :decision`)."
  [slot-map class-ident]
  (if (empty? slot-map)
    ""
    (let [yaml-map (into {}
                         (for [[slot v] slot-map
                               :let [yaml-key (slot->frontmatter-key class-ident slot)
                                     yaml-val (if (keyword-typed-slot? slot)
                                                (coerce-keyword->string v)
                                                v)]]
                           [yaml-key yaml-val]))]
      (yaml/generate-string yaml-map :dumper-options {:flow-style :block}))))

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
                        (yaml/parse-string fm-text :keywords true))
          slot-map    (frontmatter->slots class-ident fm-map)
          normalized  (normalize-body body-text)]
      (merge {:dt/type class-ident
              (body-slot-for class-ident) (or normalized "")}
             slot-map)))

  (emit [_ entity opts]
    (let [class-ident   (or (:dt/type entity)
                            (throw (ex-info "sandbar.codec.markdown/emit requires :dt/type on entity"
                                            {:entity entity})))
          body-slot     (body-slot-for class-ident)
          body-text     (get entity body-slot "")
          ;; Frontmatter slots = all keys except :dt/type + body-slot
          fm-slots      (dissoc entity :dt/type body-slot)
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
      (if-let [{:keys [level title]} (parse-heading-line line)]
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
                ident         (section-ident memory-ident heading-chain)
                tracker-key   [parent level]
                prev-sibling  (get @sibling-tracker tracker-key)]
            ;; Collision check per B.0 ADR §2.3
            (when (contains? @ident->index ident)
              (throw (ex-info "Section ident collision under same parent — slug conflict"
                              {:memory-ident memory-ident
                               :colliding-ident ident
                               :heading-chain heading-chain})))
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

(defn parse-document
  "Full mm/Memory document parse: split frontmatter + body, build the
   section tree, return a vector of entity-specs.

   First element is the mm/Memory entity (with :first-section ref to the
   chain head); subsequent elements are mm/Section entities in document
   order with full sibling-chain wiring.

   Inputs:
     source   — markdown source text
     rel-path — corpus rel-path (e.g., 'decisions/foo.md') — REQUIRED for
                path-derived idents

   When body has no headings, returns a single-element vector with just
   the mm/Memory entity."
  [source rel-path]
  (let [memory-ident (or (memory-ident-from-rel-path rel-path)
                         (throw (ex-info "parse-document requires a rel-path that yields a valid memory ident"
                                         {:rel-path rel-path})))
        c          (make-codec)
        memory-ent (proto/parse c source {:class :mm/Memory})
        memory-ent (assoc memory-ent :db/ident memory-ident
                                     :mm.memory/rel-path rel-path)
        body-raw   (:mm.memory/body-raw memory-ent)
        sections   (parse-sections body-raw memory-ident)]
    (if (empty? sections)
      [memory-ent]
      (let [first-sec (first-section-of sections memory-ident)]
        (into [(assoc memory-ent :mm.memory/first-section (:db/ident first-sec))]
              sections)))))

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
