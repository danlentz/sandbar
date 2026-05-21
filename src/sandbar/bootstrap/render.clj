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
            [sandbar.db.datomic     :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- class-name-lc
  "Lowercase the local-name part of a class ident.
   `:mm/Memory` → 'memory'  `:mm/BootstrapSource` → 'bootstrap-source'"
  [class-ident]
  (let [n (name class-ident)
        ;; Camel/Pascal → kebab-case
        kebab (-> n
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

(defn- format-slot-row
  "Render one slot as a markdown table row."
  [slot-ident]
  (let [entity (db/entity slot-ident)
        rng    (or (:dt/range entity) "?")
        card   (or (some-> entity :db/cardinality :db/ident name) "?")
        doc    (or (:db/doc entity) "")
        ;; Truncate doc to one line for the table
        doc-1  (if (> (count doc) 80)
                 (str (subs doc 0 80) "…")
                 doc)]
    (format "| `%s` | `%s` | `:%s` | %s |" slot-ident rng card doc-1)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class memorial rendering
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- introspection-context
  "Build the placeholder-substitution context for a class memorial."
  [class-ident]
  (let [entity     (db/entity class-ident)
        parents    (sort (mapv :db/ident (or (:dt/subclass-of entity) [])))
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
                  :mm.memory/name (str label " — " (or doc "") )
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

(defn render-predicate-body
  "Render the markdown body for a predicate documentation memorial."
  [slot-ident]
  (let [entity   (db/entity slot-ident)
        doc      (or (:db/doc entity) "")
        domain   (or (:dt/domain entity) "?")
        domain-i (some-> domain :db/ident)
        rng      (or (:dt/range entity) "?")
        rng-i    (some-> rng :db/ident)
        card     (or (some-> entity :db/cardinality :db/ident name) "?")
        super    (when (some-> entity :dt/subproperty-of seq)
                   (mapv :db/ident (:dt/subproperty-of entity)))
        inv      (inverse-of-slot slot-ident)]
    (str
      "## What this predicate means\n\n"
      doc "\n\n"
      "## Substrate identity\n\n"
      "- **Slot ident**: `" slot-ident "`\n"
      "- **Domain**: `" (or domain-i domain) "`\n"
      "- **Range**: `" (or rng-i rng) "`\n"
      "- **Cardinality**: `:" card "`\n"
      (when (seq super)
        (str "- **Sub-property of**: " (str/join ", " (map #(str "`" % "`") super)) "\n"))
      (when inv
        (str "- **Inverse of**: `" inv "`\n"))
      "\n## Formal signature\n\n"
      "`" (predicate-name slot-ident) " : " (or domain-i "?") " × "
      (or rng-i "?") " → Bool`\n\n"
      "## See also\n\n"
      "- [`types/predicate.md`](../types/predicate.md) — predicate-type root\n"
      "- Sandbar substrate: `sandbar/schema/mm.edn`\n")))

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
