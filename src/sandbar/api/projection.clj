(ns sandbar.api.projection
  "Entity-projection helpers — single source of truth shared across
  `sandbar.navigate.*`, `sandbar.orient`, `sandbar.api.aggregate`, and
  `sandbar.mcp.tools` handlers.  Lifted from 5+ duplicate definitions
  per Task #12 of the MCP cutover-friction batch (2026-05-22).

  ## Modes

  - `:full` — preserves all namespaced-keyword slots + `:db/ident` +
    explicit `:db/id`.  Calls `d/touch` first to realize all slot
    values (Datomic Entity iteration via `seq` only enumerates
    already-realized attrs; fresh-from-transact entities are sparse
    without touch — see Bug C4 in `audit-results/mcp_e2e_correctness_audit_2026_05_22.md`).
    Ref-slot values (Datomic Entity instances) recursively project to
    `:metadata-only` shape — enough to identify the target without
    unbounded recursion or JSON-serialization failure on raw
    EntityMap (see Bug C1).

  - `:metadata-only` — substrate-universal fields only: `:db/id` +
    `:db/ident` (if interned) + `:dt/type` (if set).  Class-agnostic;
    no consumer-specific slot inclusion.  Doesn't `d/touch` — these
    three attrs are accessible without realization.  10-300x smaller
    payload than `:full` for exploration use cases (navigate /
    library-card defaults).

  ## Recursive projection bound

  `:full` projection of a top-level entity recurses to `:metadata-only`
  for nested ref-slot values (one hop deep).  Avoids:
  - infinite recursion on circular ref graphs (corpus has many
    cites / related / composes-with cycles)
  - exponential payload blow-up on deeply-connected entities
  - JSON-serialization failure on unprocessed Datomic Entity objects

  Consumers wanting deeper traversal use `sandbar.navigate.*` or
  `sandbar.orient.library-card` explicitly with their own `:projection`
  opts per axis.

  ## Why class-agnostic?

  Per `interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md`
  — substrate helpers don't carry knowledge of consumer-class-specific
  slots.  `:dt/type` is the universal class-membership slot every
  entity carries; `:db/id` + `:db/ident` are substrate-level fields.
  These three together suffice for orientation without coupling the
  substrate to any specific consumer schema.

  ## Stage B.1 of substrate-stabilization arc

  Per `plans/sandbar_mcp_end_to_end_correctness_pass_substrate_stabilization_arc_2026_05_22.md`
  Stage B.1.  Unified fix for:
  - Bug C1 (entity.create / write-verb response fails JSON serialization
    on nested Datomic Entity values)
  - Bug C4 (fresh-from-transact entity returns sparse projection
    because seq iteration only surfaces realized attrs)"
  (:require [clojure.string :as str]
            [datomic.api    :as d])
  (:import (datomic Entity)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity-shape predicate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- datomic-entity?
  "True iff `v` is a Datomic Entity instance (EntityMap implements the
  `datomic.Entity` interface).  Used by `full-projection` to detect
  nested ref-slot values that need recursive projection rather than
  pass-through (which would fail at the JSON serialization boundary)."
  [v]
  (instance? Entity v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projection functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn metadata-projection
  "Project a Datomic Entity (or entity-shaped map) to substrate-universal
  metadata only: `:db/id` + `:db/ident` (if interned) + `:dt/type`
  (if set).  Class-agnostic — no consumer-specific slot inclusion.
  Returns nil when entity is nil; returns an empty map when entity
  carries none of the three universal fields.

  Doesn't call `d/touch` — these three attrs are accessible without
  realization."
  [entity]
  (when entity
    (cond-> {}
      (:db/id entity)    (assoc :db/id    (:db/id entity))
      (:db/ident entity) (assoc :db/ident (:db/ident entity))
      (:dt/type entity)  (assoc :dt/type  (:dt/type entity)))))

(defn- project-nested-value
  "Project a slot value for inclusion in `:full` projection output.
  Datomic Entity values (cardinality-one ref slots) → `metadata-projection`.
  Clojure collections of Entity values (cardinality-many ref slots →
  Datomic Peer returns `PersistentHashSet`) → mapv project.
  Primitive values (string / keyword / number / inst / etc.) → pass-through.

  One-hop-deep — nested entities project to metadata-only, not full.
  Prevents JSON-serialization failure on raw EntityMap + unbounded
  recursion on circular ref graphs + exponential payload blow-up."
  [v]
  (cond
    (datomic-entity? v)
    (metadata-projection v)

    (or (set? v) (sequential? v))
    (mapv (fn [x] (if (datomic-entity? x) (metadata-projection x) x)) v)

    :else v))

(defn full-projection
  "Project a Datomic Entity to a plain Clojure map preserving all
  namespaced-keyword slots + explicit `:db/id`.

  Calls `d/touch` to realize all slot values before iteration —
  EntityMap iteration via `seq` only enumerates already-realized
  attrs; without touch, fresh-from-transact entities return sparse
  projections (Bug C4 — entity.create returned only `:dt/type` +
  `:db/id` of a just-created entity even though name + description
  were transacted).

  Nested ref-slot values (Datomic Entity instances) recursively
  project to `:metadata-only` shape via `project-nested-value`.
  Prevents JSON-serialization failure on raw EntityMap (Bug C1 — the
  PersistentVector-class-object error from cheshire on nested
  Entities) + unbounded recursion on circular ref graphs.

  Returns nil when entity is nil.  Falls back gracefully if `d/touch`
  fails (some non-Entity inputs may not support touch)."
  [entity]
  (when entity
    (let [touched (try (d/touch entity) (catch Throwable _ entity))
          slots   (->> touched
                       (filter (fn [[k _]]
                                 (or (= :db/ident k)
                                     (and (keyword? k) (some? (namespace k))))))
                       (map (fn [[k v]]
                              [k (project-nested-value v)]))
                       (into {}))]
      (cond-> slots
        (:db/id touched) (assoc :db/id (:db/id touched))))))

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
