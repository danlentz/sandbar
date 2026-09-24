(ns sandbar.api.projection
  "Entity projection shared by navigation, orientation, aggregation and MCP.

  :full realizes an entity's attributes, retains its namespaced slots and
  identifiers, and projects nested reference values to :metadata-only.
  :metadata-only contains :db/id, :db/ident when present, and :dt/type
  when set. It does not require full attribute realization.

  Limiting nested references to identification avoids unbounded recursion
  through cycles and avoids returning raw Datomic Entity values to JSON
  encoders. Consumers request deeper graph traversal explicitly through
  navigation operations. These helpers describe result shape; callers must
  separately enforce authorization and disclosure policy."
  (:require [clojure.string :as str]
            [datomic.api    :as d]
            [sandbar.security.query :as secq]
            [sandbar.security.visibility :as visibility])
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
    ;; SECURITY (read-plane OUTPUT firewall): a firewalled entity (:auth/* etc.)
    ;; — INCLUDING a nested ref reached via project-nested-value — is redacted;
    ;; a visible entity keeps only non-firewalled slots.  metadata carries no
    ;; firewalled slot, so a visible entity is unchanged; a firewalled one
    ;; collapses to the redaction marker (no :db/ident / :dt/type enumeration).
    ;; COMPARTMENT backstop (D4b / CT-03): when a principal is bound for the
    ;; read plane, an entity it is not cleared for collapses to the compartment
    ;; marker — including a nested ref reached via project-nested-value.
    (visibility/compartment-scrub
      entity
      (secq/read-plane-scrub-projection
        (cond-> {}
          (:db/id entity)    (assoc :db/id    (:db/id entity))
          (:db/ident entity) (assoc :db/ident (:db/ident entity))
          (:dt/type entity)  (assoc :dt/type  (:dt/type entity)))))))

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
      ;; SECURITY (read-plane OUTPUT firewall): redact a firewalled entity to the
      ;; marker; strip any firewalled-namespace SLOT (e.g. :http/* / :event/* on
      ;; an otherwise-allowed entity) from a visible one.  Applied to EVERY
      ;; full-projected entity so navigate/rank-by/class.instances/search/etc.
      ;; cannot leak firewalled data through their RETURNED entities.
      ;; COMPARTMENT backstop (D4b / CT-03) — see metadata-projection.
      (visibility/compartment-scrub
        touched
        (secq/read-plane-scrub-projection
          (cond-> slots
            (:db/id touched) (assoc :db/id (:db/id touched))))))))

(defn frontmatter-projection
  "Project a Datomic Entity to its FRONTMATTER — all scalar + ref slots
  EXCEPT the bulky `:mm.memory/body-raw`.  Refs project to metadata-only
  (idents/eids), same as `:full`.  The lean MIDDLE GROUND between
  `:metadata-only` (idents only — no stage/status/dates/edges) and `:full`
  (entire body — risks MCP wire-limit / transcript brick on large memorials
  like arc plans with multi-KB stage-logs; a single arc plan full-projected
  hit 65KB + offloaded, the brick vector).  Use to survey
  stage/status/last-touched/edges across many entities (curation,
  orientation, arc-forest) without hydrating bodies."
  [entity]
  (some-> (full-projection entity) (dissoc :mm.memory/body-raw)))

(defn projection-fn-for
  "Return the projection function for a `:projection` mode keyword.
  Fails loud on unknown modes rather than silently misshaping output."
  [projection-mode]
  (case projection-mode
    :full          full-projection
    :frontmatter   frontmatter-projection
    :metadata-only metadata-projection
    (throw (ex-info (str "Unknown :projection mode `" projection-mode
                         "`.  Valid: :full, :frontmatter, :metadata-only.")
                    {:projection-mode projection-mode
                     :valid-modes #{:full :frontmatter :metadata-only}}))))

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
