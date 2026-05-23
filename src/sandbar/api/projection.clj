(ns sandbar.api.projection
  "Entity-projection helpers — single source of truth shared across
  `sandbar.navigate.*`, `sandbar.orient`, `sandbar.api.aggregate`, and
  `sandbar.mcp.tools` handlers.  Lifted from 5+ duplicate definitions
  per Task #12 of the MCP cutover-friction batch (2026-05-22) — the
  Gap 3 :projection-opt work surfaced the DRY violation across:

  - sandbar.mcp.tools/entity-projection
  - sandbar.orient/{full,metadata}-projection + project-edge + projection-fn-for
  - sandbar.navigate.edges/{full,metadata}-projection + project-edge
  - sandbar.navigate.siblings/entity-projection
  - sandbar.navigate.path/entity-projection
  - sandbar.api.aggregate/entity-projection

  All five did the same work; the substrate now ships one canonical
  shape with `:metadata-only` and `:full` modes.

  ## Modes

  - `:full` — preserves all namespaced-keyword slots + `:db/ident` +
    explicit `:db/id` (the existing entity-projection shape).
    Datomic EntityMap iteration omits `:db/id` from key-seq; we
    explicitly add it so JSON/EDN serialization carries the field.

  - `:metadata-only` — substrate-universal fields only: `:db/id` +
    `:db/ident` (if interned) + `:dt/type` (if set).  Class-agnostic;
    no consumer-specific slot inclusion.  10-300x smaller payload than
    `:full` for exploration use cases (navigate/library-card defaults).

  ## Why class-agnostic?

  Per `interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md`
  — substrate helpers don't carry knowledge of consumer-class-specific
  slots.  `:dt/type` is the universal class-membership slot every
  entity carries; `:db/id` + `:db/ident` are substrate-level fields.
  These three together suffice for orientation use cases without
  coupling the substrate to any specific consumer schema."
  (:require [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projection functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn full-projection
  "Project a Datomic EntityMap to a plain Clojure map preserving all
  namespaced-keyword slots + explicit `:db/id` (EntityMap iteration
  omits `:db/id` from key-seq; we add it explicitly so serialization
  carries the field).  Returns nil when entity is nil."
  [entity]
  (when entity
    (let [base (into {}
                     (filter (fn [[k _v]]
                               (or (= :db/ident k)
                                   (and (keyword? k) (some? (namespace k))))))
                     entity)]
      (cond-> base
        (:db/id entity) (assoc :db/id (:db/id entity))))))

(defn metadata-projection
  "Project a Datomic EntityMap to substrate-universal metadata only:
  `:db/id` + `:db/ident` (if interned) + `:dt/type` (if set).
  Class-agnostic — no consumer-specific slot inclusion.  Returns nil
  when entity is nil; returns an empty map when entity carries none
  of the three universal fields."
  [entity]
  (when entity
    (cond-> {}
      (:db/id entity)    (assoc :db/id    (:db/id entity))
      (:db/ident entity) (assoc :db/ident (:db/ident entity))
      (:dt/type entity)  (assoc :dt/type  (:dt/type entity)))))

(defn projection-fn-for
  "Return the projection function for a `:projection` mode keyword.
  Fails loud on unknown modes rather than silently misshaping output."
  [projection-mode]
  (case projection-mode
    :full          full-projection
    :metadata-only metadata-projection
    (throw (ex-info (str "Unknown :projection mode `" projection-mode
                         "`.  Valid: :full, :metadata-only.")
                    {:projection-mode projection-mode
                     :valid-modes #{:full :metadata-only}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge projection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn project-edge
  "Apply the projection mode to an edge-record's `:target` or `:source`.
  Preserves the `:predicate` keyword as-is.  Used by navigate edges +
  library-card consumer wrappers."
  [edge projection-mode]
  (let [project-fn (projection-fn-for projection-mode)]
    (cond-> edge
      (contains? edge :target) (update :target project-fn)
      (contains? edge :source) (update :source project-fn))))

(defn apply-projection
  "Apply a projection mode to a single entity-map.  Default mode is
  `:full` when `projection-mode` is nil (preserves the legacy
  entity-projection contract — single-entity lookups default to full
  body)."
  [entity projection-mode]
  ((projection-fn-for (or projection-mode :full)) entity))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON-input coercion (MCP-handler boundary)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ->projection-mode
  "Coerce a JSON-string `:projection` arg to the keyword form the
  substrate wrappers expect.  Accepts 'metadata-only', 'full',
  ':metadata-only', ':full', or already-coerced keywords.  Returns
  nil when arg is nil (lets the wrapper apply its own default)."
  [raw]
  (cond
    (nil? raw)     nil
    (keyword? raw) raw
    (string? raw)  (keyword (str/replace raw #"^:" ""))
    :else          (throw (ex-info (str "Unparseable :projection arg `" raw "`")
                                   {:projection raw}))))
