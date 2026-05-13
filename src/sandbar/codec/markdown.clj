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
     - Per-class aliases (see `known-class-slot-aliases`) handle
       reserved-word collisions (e.g., `type` → `:mm.memory/memory-type`)
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
            [sandbar.codec.protocol :as proto]))

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
   Per §4.3, this transformation is applied on PARSE for canonical
   storage; emission produces normalized output too — round-trip is
   idempotent at the second parse, not byte-exact with the first parse's
   source.  Code-block interiors are CURRENTLY normalized (Stage B.2
   minimum-viable); preserving code-block interiors verbatim is a Stage
   B.3 follow-up requiring fenced-block awareness."
  [body]
  (when body
    (-> body
        normalize-line-endings
        strip-trailing-non-hardbreak-whitespace
        collapse-multi-blank-lines)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class-aware frontmatter ↔ slot mapping
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def known-class-slot-aliases
  "Per-class frontmatter-key → slot-ident alias map.  Handles
   collisions between YAML's natural key names and Clojure / dt/*
   reserved-word constraints.  Default rule (when no alias exists):
   `:<class-namespace>.<lowercase-class-local>/<frontmatter-key>`.

   Example: mm/Memory's frontmatter `type:` maps to slot
   `:mm.memory/memory-type` because `type` is a reserved word in
   Clojure / would shadow the `:dt/type` system attribute."
  {:mm/Memory  {:type :mm.memory/memory-type}})

(def known-class-keyword-slots
  "Per-class set of slot idents whose values must be coerced
   string ↔ keyword across the YAML wire format.

   YAML has no native keyword type — bare names parse as strings.
   For keyword-typed slots (e.g., :mm.memory/memory-type :decision),
   the codec coerces on parse + emit so the entity-spec map round-trips
   correctly through `dt/make` (which requires properly-typed values).

   Stage B.2 minimum-viable hardcoding — the corpus's mm/Memory has a
   small fixed set of keyword-scalar slots.  Stage B.3+ generalizes via
   `dt/range-of` introspection (which requires a live DB; deferred until
   the codec runs in a DB-backed context)."
  {:mm/Memory #{:mm.memory/memory-type
                :mm.memory/scope
                :mm.memory/status}})

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
   class.  Looks up class-specific aliases first; falls back to the
   namespace-prefixing convention."
  [class-ident yaml-key]
  (or (get-in known-class-slot-aliases [class-ident yaml-key])
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

(defn frontmatter->slots
  "Transform a YAML-parsed frontmatter map into a slot map for the given
   class.  Each key is run through `frontmatter-key->slot`; values of
   keyword-typed slots (per `known-class-keyword-slots`) are coerced
   string → keyword."
  [class-ident frontmatter-map]
  (let [kw-slots (get known-class-keyword-slots class-ident #{})]
    (into {}
          (for [[k v] frontmatter-map
                :let [slot (frontmatter-key->slot class-ident k)
                      v'   (if (contains? kw-slots slot)
                             (coerce-string->keyword v)
                             v)]]
            [slot v']))))

(defn- coerce-keyword->string
  "Coerce a keyword value to its bare string form for YAML emission.
   Lists of keywords → vectors of strings.  Pass-through otherwise."
  [v]
  (cond
    (keyword? v) (name v)
    (sequential? v) (mapv coerce-keyword->string v)
    :else v))

(defn- invert-aliases
  "Invert the class-aliases map for emission — slot-ident → yaml-key."
  [class-ident]
  (into {}
        (for [[k v] (get known-class-slot-aliases class-ident {})]
          [v k])))

(defn slot->frontmatter-key
  "Map a slot ident back to a YAML frontmatter key.  Inverse of
   `frontmatter-key->slot`."
  [class-ident slot-ident]
  (or (get (invert-aliases class-ident) slot-ident)
      (keyword (name slot-ident))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; YAML emission helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- emit-frontmatter
  "Emit a slot map as YAML frontmatter text (without the `---` fences).
   Uses block-style YAML for readability.  Empty map → empty string.
   Keyword-typed slot values (per `known-class-keyword-slots`) are
   coerced keyword → bare-name string so YAML emits idiomatic bare names
   (e.g., `type: decision` instead of `type: :decision`)."
  [slot-map class-ident]
  (if (empty? slot-map)
    ""
    (let [kw-slots (get known-class-keyword-slots class-ident #{})
          yaml-map (into {}
                         (for [[slot v] slot-map
                               :let [yaml-key (slot->frontmatter-key class-ident slot)
                                     yaml-val (if (contains? kw-slots slot)
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
          ;; Ensure final newline
          body-final    (if (str/ends-with? (or normalized "") "\n")
                          normalized
                          (str normalized "\n"))]
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
    ;; handled via known-class-slot-aliases + the convention rule.
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
