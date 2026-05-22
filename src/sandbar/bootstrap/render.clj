(ns sandbar.bootstrap.render
  "Render sandbar's substrate state to corpus markdown memorials.

   Stage 2.B of plans/sandbar_bootstrap_authority_arc_2026_05_21.md (per
   the corpus side).  Implements the substrate-as-authoritative-source
   pattern: each `:mm/<Class>` declaration + each `:mm.memory/<predicate>`
   slot renders to a markdown documentation memorial in the corpus
   filesystem.

   Stage 2.B scope = minimum viable:
   - `render-class :mm/<Class>` → entity-spec map for memory/types/<class>.md
   - `render-predicate :mm.memory/<predicate>` → entity-spec map for
     memory/predicates/<predicate>.md
   - `render-all-bootstrap` → seq of {:rel-path :content} pairs

   Per-class narrative templates (Stage 2.D) are NOT implemented in this
   first pass; the body is a structured introspection report.  Templates
   are a future refinement.

   Per decisions/bootstrap_authority_q1_q5_resolutions_2026_05_21.md
   + decisions/bootstrap_source_as_first_class_class_not_keyword_enum_2026_05_21.md."
  (:require [clojure.string         :as str]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]
            [sandbar.entity-ref     :as eref]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- class-name-lc
  "Lowercase the local-name part of a class ident.
   `:mm/Memory` → 'memory'  `:mm/BootstrapSource` → 'bootstrap-source'
   `:mm/AIActor` → 'ai-actor'  (acronym-prefix handled)"
  [class-ident]
  (let [n (name class-ident)
        ;; Camel/Pascal → kebab-case.  Two passes:
        ;;   1. Acronym-followed-by-Word boundary (`AIActor` → `AI-Actor`)
        ;;   2. Standard lower-to-upper boundary (`camelCase` → `camel-Case`)
        ;; Pass 1 must precede pass 2 so the acronym group is preserved.
        kebab (-> n
                  (str/replace #"([A-Z]+)([A-Z][a-z])" "$1-$2")
                  (str/replace #"([a-z])([A-Z])" "$1-$2")
                  str/lower-case)]
    kebab))

(defn- predicate-name
  "Extract the predicate local-name from a `:mm.memory/<name>` ident."
  [slot-ident]
  (name slot-ident))

(defn- substrate-bootstrap-source-identifier
  "Compute the URN-shaped identifier for the substrate-source the current
   sandbar revision will mint when rendering.  Reads `sandbar.version`
   system property if available; otherwise uses a date stamp."
  []
  (let [version (or (System/getProperty "sandbar.version")
                    (str "rev-" (.format (java.text.SimpleDateFormat. "yyyy-MM-dd")
                                          (java.util.Date.))))]
    (str "sandbar:" version)))

(defn- load-template
  "Load a per-class or per-predicate narrative template from
   resources/bootstrap/templates/.  Returns the template content
   (string with {{placeholder}} tokens) OR nil if no template exists.

   Per interaction/bootstrap_rendering_must_preserve_narrative_human_readable_content_2026_05_21.md
   — templates carry the generic pedagogical narrative; substrate
   introspection fills the placeholders."
  [kind name-str]
  (let [resource-path (str "bootstrap/templates/" kind "/" name-str ".md.template")
        url (clojure.java.io/resource resource-path)]
    (when url (slurp url))))

(defn- substitute-placeholders
  "Substitute {{placeholder}} tokens in a template with values from
   a context map.  Missing placeholders are left as-is so they're
   visible during development."
  [template-str context]
  (reduce-kv (fn [s k v]
               (str/replace s (str "{{" (name k) "}}") (str v)))
             template-str
             context))

(defn- safe-resolve-ident
  "Like `entity-ref/resolve-ident` but swallows the ex-info raised on
   unresolvable / malformed input, returning nil instead.  Useful inside
   introspection contexts where a missing attribute is normal."
  [ref]
  (when ref
    (try (eref/resolve-ident ref)
         (catch clojure.lang.ExceptionInfo _ nil))))

(defn- ref-attr-ident
  "Read a ref-valued schema attribute as its canonical :db/ident keyword.
   Datomic returns refs as entity-maps OR direct keywords depending on
   version / wrapper layer; this normalizes via entity-ref/resolve-ident.
   Returns nil if the attribute is absent or unresolvable."
  [entity attr]
  (safe-resolve-ident (get entity attr)))

(defn- format-slot-row
  "Render one slot as a markdown table row."
  [slot-ident]
  (let [entity (db/entity slot-ident)
        rng    (or (ref-attr-ident entity :dt/range)
                   (ref-attr-ident entity :db/valueType)
                   "?")
        card   (or (some-> (ref-attr-ident entity :db/cardinality) name) "?")
        doc    (or (:db/doc entity) "")
        ;; Truncate doc to one line for the table
        doc-1  (if (> (count doc) 80)
                 (str (subs doc 0 80) "…")
                 doc)]
    (format "| `%s` | `%s` | `:%s` | %s |" slot-ident rng card doc-1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class memorial rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- direct-parent-idents-of
  "Return the sorted vec of direct parent class idents for `class-ident`,
   normalizing through `entity-ref/resolve-ident` so the result is
   canonical regardless of whether `:dt/subclass-of` returned an entity-map,
   a keyword ident, or an eid."
  [class-ident]
  (->> (dt/parents-of class-ident)
       (keep safe-resolve-ident)
       sort
       vec))

(defn- ancestor-idents-of
  "Return the sorted vec of all ancestor class idents (transitive parents),
   normalized through `entity-ref/resolve-ident`."
  [class-ident]
  (->> (dt/ancestors-of class-ident)
       (keep safe-resolve-ident)
       distinct
       sort
       vec))

(defn- introspection-context
  "Build the placeholder-substitution context for a class memorial."
  [class-ident]
  (let [entity     (db/entity class-ident)
        parents    (direct-parent-idents-of class-ident)
        ancestors  (ancestor-idents-of class-ident)
        slots      (sort (or (dt/slots-of class-ident) []))
        codec-keys (dt/codec-type-keywords-of class-ident)
        slot-order (dt/codec-slot-order-of class-ident)
        slot-tbl   (str "| Slot | Range | Cardinality | Doc |\n|---|---|---|---|\n"
                        (str/join "\n" (mapv format-slot-row slots)))
        slot-list  (if (seq slot-order)
                     (str/join "\n" (map-indexed (fn [i s] (str (inc i) ". `" s "`")) slot-order))
                     "(no canonical order declared)")]
    {:class-ident class-ident
     :class-name (or (:dt/label entity) (name class-ident))
     :class-doc (or (:db/doc entity) "")
     :parent-classes (if (seq parents)
                       (str/join ", " (map #(str "`" % "`") parents))
                       "(none — root class)")
     :ancestor-chain (if (seq ancestors)
                       (str/join " → " (map #(str "`" % "`") ancestors))
                       "(none — root class)")
     :native-codec (or (some-> entity :dt/native-codec) "(none)")
     :codec-type-keywords (if (seq codec-keys)
                            (str/join ", " (map #(str "`" % "`") codec-keys))
                            "(none — default routing)")
     :slot-count (count slots)
     :slot-table slot-tbl
     :slot-order-list slot-list}))

(defn render-class-body
  "Render the markdown body for a class documentation memorial.
   Uses per-class narrative template from
   resources/bootstrap/templates/types/<class-name>.md.template when
   present; falls back to introspection-only output when no template
   exists.

   Per interaction/bootstrap_rendering_must_preserve_narrative_human_readable_content_2026_05_21.md
   — templates carry the generic pedagogical narrative; introspection
   fills the placeholders."
  [class-ident]
  (let [class-nm (class-name-lc class-ident)
        template (load-template "types" class-nm)
        context  (introspection-context class-ident)]
    (if template
      ;; Template path — narrative + introspection-substituted placeholders
      (substitute-placeholders template context)
      ;; Fallback — introspection-only (clearly marked as such so humans
      ;; know it's not the canonical narrative)
      (str
        "*This memorial is rendered from substrate introspection only — "
        "no narrative template ships for this class yet.  Per "
        "`interaction/bootstrap_rendering_must_preserve_narrative_human_readable_content_2026_05_21.md`, "
        "a template should be authored to provide pedagogical context.*\n\n"
        "## What this class is\n\n"
        (:class-doc context) "\n\n"
        "## Substrate identity\n\n"
        "- **Class ident**: `" (:class-ident context) "`\n"
        "- **Parent class(es)**: " (:parent-classes context) "\n"
        "- **Native codec**: `" (:native-codec context) "`\n"
        "- **Codec type-keyword(s)**: " (:codec-type-keywords context) "\n\n"
        "## Slot vocabulary (" (:slot-count context) " slots)\n\n"
        (:slot-table context) "\n\n"
        "## Canonical slot-order for codec emit\n\n"
        (:slot-order-list context) "\n\n"
        "## See also\n\n"
        "- [`types/meta.md`](meta.md) — type-system root\n"
        "- Sandbar substrate: `sandbar/schema/mm.edn`\n"))))

(defn render-class
  "Render a class memorial.  Returns {:rel-path <path> :content <markdown>}."
  [class-ident]
  (let [class-nm (class-name-lc class-ident)
        rel-path (str "memory/types/" class-nm ".md")
        source-id (substrate-bootstrap-source-identifier)
        entity (db/entity class-ident)
        label  (or (:dt/label entity) (name class-ident))
        doc    (or (:db/doc entity) "")
        ;; Build the entity-spec map to feed the codec
        memorial {:dt/type :mm/Memory
                  :db/ident (keyword "memory.types" class-nm)
                  :mm.memory/identity rel-path
                  :mm.memory/rel-path rel-path
                  ;; Use `:dt/label` (compact human-readable name) alone for
                  ;; the `name:` field rather than concatenating the full
                  ;; docstring — the docstring is preserved in `description:`.
                  :mm.memory/name label
                  :mm.memory/description (or doc "")
                  :mm.memory/memory-type :type
                  :mm.memory/scope :global
                  :mm.memory/bootstrap-source {:mm.bootstrap-source/identifier source-id}
                  :mm.memory/body-raw (render-class-body class-ident)}
        content  (codec-md/emit-document [memorial])]
    {:rel-path rel-path
     :content  content}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicate memorial rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- inverse-of-slot
  "Search for a slot that names this one as its inverse via Datalog.
   Returns nil if no inverse declared."
  [slot-ident]
  (let [doc (or (some-> (db/entity slot-ident) :db/doc) "")]
    ;; Heuristic: look for 'Inverse of :mm.memory/<x>' in the doc string
    (when-let [m (re-find #"Inverse of (:mm\.memory/[a-z][a-z\-]*)" doc)]
      (keyword (subs (second m) 1)))))

(defn- predicate-introspection-context
  "Build the placeholder-substitution context for a predicate memorial."
  [slot-ident]
  (let [entity   (db/entity slot-ident)
        domain   (ref-attr-ident entity :dt/domain)
        rng      (or (ref-attr-ident entity :dt/range)
                     (ref-attr-ident entity :db/valueType))
        card     (or (some-> (ref-attr-ident entity :db/cardinality) name) "?")
        super    (->> (:dt/subproperty-of entity)
                      (keep safe-resolve-ident)
                      sort
                      vec)
        inv      (inverse-of-slot slot-ident)]
    {:predicate-ident slot-ident
     :predicate-name (predicate-name slot-ident)
     :predicate-doc (or (:db/doc entity) "")
     :domain (or domain "?")
     :range (or rng "?")
     :cardinality (str ":" card)
     :super-property (if (seq super)
                       (str/join ", " (map #(str "`" % "`") super))
                       "(none — top of hierarchy)")
     :inverse-of (if inv (str "`" inv "`") "(none declared)")}))

(defn render-predicate-body
  "Render the markdown body for a predicate documentation memorial.
   Uses per-predicate narrative template when present at
   resources/bootstrap/templates/predicates/<name>.md.template; falls
   back to introspection-only output with a clear marker when no
   template exists."
  [slot-ident]
  (let [pred-nm  (predicate-name slot-ident)
        template (load-template "predicates" pred-nm)
        context  (predicate-introspection-context slot-ident)]
    (if template
      (substitute-placeholders template context)
      (str
        "*This memorial is rendered from substrate introspection only — "
        "no narrative template ships for this predicate yet.*\n\n"
        "## What this predicate means\n\n"
        (:predicate-doc context) "\n\n"
        "## Substrate identity\n\n"
        "- **Slot ident**: `" (:predicate-ident context) "`\n"
        "- **Domain**: `" (:domain context) "`\n"
        "- **Range**: `" (:range context) "`\n"
        "- **Cardinality**: `" (:cardinality context) "`\n"
        "- **Sub-property of**: " (:super-property context) "\n"
        "- **Inverse of**: " (:inverse-of context) "\n\n"
        "## Formal signature\n\n"
        "`" (:predicate-name context) " : " (:domain context) " × "
        (:range context) " → Bool`\n\n"
        "## See also\n\n"
        "- [`types/predicate.md`](../types/predicate.md) — predicate-type root\n"
        "- Sandbar substrate: `sandbar/schema/mm.edn`\n"))))

(defn render-predicate
  "Render a predicate memorial.  Returns {:rel-path <path> :content <markdown>}."
  [slot-ident]
  (let [pred-nm  (predicate-name slot-ident)
        rel-path (str "memory/predicates/" pred-nm ".md")
        source-id (substrate-bootstrap-source-identifier)
        entity (db/entity slot-ident)
        doc    (or (:db/doc entity) "")
        memorial {:dt/type :mm/Memory
                  :db/ident (keyword "memory.predicates" pred-nm)
                  :mm.memory/identity rel-path
                  :mm.memory/rel-path rel-path
                  :mm.memory/name (str pred-nm " — substrate-declared typed-edge predicate")
                  :mm.memory/description doc
                  :mm.memory/memory-type :predicate
                  :mm.memory/scope :global
                  :mm.memory/bootstrap-source {:mm.bootstrap-source/identifier source-id}
                  :mm.memory/body-raw (render-predicate-body slot-ident)}
        content  (codec-md/emit-document [memorial])]
    {:rel-path rel-path
     :content  content}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; All-bootstrap rendering (Q1 — Medium scope: types + predicates)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn render-all-bootstrap
  "Render ALL bootstrap-managed memorials per Q1 scope (types/ + predicates/).
   Returns a seq of {:rel-path :content} pairs.

   Excludes metamodel-internal `:dt/*` classes; includes only user-domain
   classes (those in the `:mm` namespace).  Aggregates like `:dt/Resource*`,
   `:dt/Class**` are metamodel machinery and produce ugly file names
   (`any*.md`); explicit namespace filter prevents this."
  []
  (let [;; Only `:mm/*` classes — user-domain.  Excludes:
        ;; - `:dt/*` metamodel machinery
        ;; - `:dt/Resource*` / `:dt/Class**` aggregate forms (would emit `any*.md`)
        classes (->> (dt/all-classes)
                     (filter (fn [c]
                               (and (keyword? c)
                                    (= "mm" (namespace c))))))
        ;; All :mm.memory/* typed-edge predicates (excluding structural)
        predicates (->> (dt/all-properties)
                        (filter (fn [p]
                                  (and (keyword? p)
                                       (= (namespace p) "mm.memory")
                                       ;; Skip structural slots
                                       (not (#{"name" "description" "scope" "status"
                                               "created" "last-touched" "last-reviewed"
                                               "rel-path" "identity" "body-raw"
                                               "first-section" "links" "frontmatter"
                                               "introduced-in" "importance" "memory-type"
                                               "tags" "themes" "bootstrap-source"
                                               "bootstrap-override"}
                                            (name p)))))))]
    (concat
      (mapv render-class classes)
      (mapv render-predicate predicates))))
