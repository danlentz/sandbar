(ns sandbar.navigate.edges
  "Sandbar Navigation — Edges Subsurface (Stage 16 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Consumer-facing wrappers around the dt/* inbound/outbound edge
  primitives.  Two public verbs:

    inbound-edges   — typed-edges pointing AT the subject
                      (who cites this entity?)
    outbound-edges  — typed-edges originating FROM the subject
                      (what does this entity reference?)

  Edge = a `(predicate-attribute, entity)` pair where the
  predicate-attribute is a `:db.type/ref`-typed slot.  Substrate-quality
  preserved per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
  wrappers route through `sandbar.db.datatype/*-edges-of` primitives,
  never raw `datomic.api`."
  (:require [sandbar.db.datatype :as dt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicate resolution (Gap 7 — surfaced via MCP cutover exercise 2026-05-22)
;;
;; The dt/* edge primitives compare against slot-idents like
;; `:mm.memory/cites` (the Datomic attribute-ident on the ref slot).
;; Consumer ergonomics often want the bare predicate form `:cites` — both
;; verb descriptions and the corpus's predicate-memorial convention
;; (memory/predicates/cites.md) name predicates without the class
;; namespace.  Without resolution, passing `:cites` silently returns
;; zero hits — the worst kind of bug because the empty result looks
;; valid.  Resolution makes both forms work: bare predicates are looked
;; up against the entity's class slots; namespaced keywords pass through
;; unchanged.  Unresolvable bare predicates throw ex-info with a hint.
;;
;; Per the inbox capture
;; memory/inbox/2026-05-22_mcp_cutover_exercise_substrate_verb_authoring_queue_10_gaps_surfaced_via_orientation_of_sandbar_as_mcp_server_arc.md.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-bare-predicate
  "Resolve a single bare predicate keyword (no namespace) to its
  slot-ident on `class-ident`.  Returns the slot-ident when exactly
  one slot has a matching local-name; throws ex-info otherwise."
  [pred class-ident slots]
  (let [matches (filterv #(= (name pred) (name %)) slots)]
    (case (count matches)
      1 (first matches)
      0 (throw (ex-info
                 (str "Bare predicate `:" (name pred)
                      "` does not match any slot on `" class-ident
                      "`.  Use the fully-qualified slot ident.")
                 {:bare-predicate  pred
                  :class-ident     class-ident
                  :available-slots (vec slots)
                  :resolution      :no-match}))
      (throw (ex-info
               (str "Bare predicate `:" (name pred)
                    "` is ambiguous on `" class-ident
                    "` — matches " (count matches) " slots: "
                    matches ".  Use the fully-qualified slot ident.")
               {:bare-predicate pred
                :class-ident    class-ident
                :matching-slots matches
                :resolution     :ambiguous})))))

(defn resolve-predicates
  "Resolve a predicate spec (single keyword OR collection) to a vec of
  slot-idents suitable for dt/*-edges-of comparison.  Bare keywords
  (no namespace) are resolved against the entity's class slots;
  namespaced keywords pass through unchanged.  When `entity-ident`
  doesn't resolve to an entity (no `:dt/type`), resolution is skipped
  and the input is returned as-is — the dt/* layer will return its
  usual empty result and the missing-entity surfaces there.

  Returns nil when `predicate` is nil (preserves dt/*'s no-filter
  semantic).

  Public so consumer-wrapper namespaces (e.g., `sandbar.orient` for
  library-card per-axis resolution) can apply the same Gap 7 fix.
  Per inbox capture
  memory/inbox/2026-05-22_mcp_cutover_exercise_substrate_verb_authoring_queue_10_gaps_surfaced_via_orientation_of_sandbar_as_mcp_server_arc.md."
  [entity-ident predicate]
  (when (some? predicate)
    (let [preds   (if (sequential? predicate) predicate [predicate])
          bare?   (fn [p] (and (keyword? p) (nil? (namespace p))))
          needs-resolution? (some bare? preds)]
      (if-not needs-resolution?
        (vec preds)
        (let [klass (dt/class-ident-of entity-ident)
              slots (when klass (dt/slots-of klass))]
          (if (or (nil? klass) (empty? slots))
            ;; Entity doesn't resolve or class has no slots — pass through;
            ;; dt/* will return empty and the missing-entity surfaces there.
            (vec preds)
            (mapv (fn [p]
                    (if (bare? p)
                      (resolve-bare-predicate p klass slots)
                      p))
                  preds)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Edge-target/source projection (Gap 3 — response-size friction fix)
;;
;; The dt/* edge primitives return FULL entity-maps for each edge's
;; target/source.  For exploration use cases (the typical reach for
;; navigate/library-card), this produces 10-300x more bytes than needed
;; — a single navigate call on a popular entity can exceed 200KB of
;; full entity bodies.  The `:projection` opt restricts the per-edge
;; target/source projection to a minimal-metadata shape suitable for
;; orientation, with `:full` available when the consumer actually wants
;; bodies.
;;
;; Class-agnostic: the metadata projection includes only `:db/id` +
;; `:db/ident` + `:dt/type` — truly substrate-universal fields.  An
;; `:include-slots` opt (future) could add caller-specified slots
;; without hardcoding consumer-class knowledge.
;;
;; Per inbox capture
;; memory/inbox/2026-05-22_mcp_cutover_exercise_substrate_verb_authoring_queue_10_gaps_surfaced_via_orientation_of_sandbar_as_mcp_server_arc.md
;; — Gap 3 addresses Gap 4 (tags-slot-projection pattern extended) +
;; Gap 5 (limit doesn't bound bytes) + Gap 6 (file-fallback friction)
;; in one fix.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- metadata-projection
  "Project an entity-map to substrate-universal metadata only:
  `:db/id` + `:db/ident` (if interned) + `:dt/type` (if set).
  Class-agnostic — no consumer-specific slot inclusion.

  Returns nil when entity is nil.  Returns an empty map when entity
  has none of the three universal fields populated."
  [entity]
  (when entity
    (cond-> {}
      (:db/id entity)    (assoc :db/id    (:db/id entity))
      (:db/ident entity) (assoc :db/ident (:db/ident entity))
      (:dt/type entity)  (assoc :dt/type  (:dt/type entity)))))

(defn- full-projection
  "Project a Datomic EntityMap to a regular Clojure map with `:db/id`
  made explicit.  EntityMap iteration doesn't include `:db/id` in its
  key-seq (it's accessed via a special method), but `(:db/id entity)`
  works — this projection adds the field so JSON/EDN serialization
  carries it.

  Mirrors `sandbar.mcp.tools/entity-projection` shape (the existing
  duplicate; see also navigate/siblings, navigate/path, api/aggregate,
  orient — DRY cleanup is a separate task)."
  [entity]
  (when entity
    (cond-> (into {} entity)
      (:db/id entity) (assoc :db/id (:db/id entity)))))

(defn- project-edge
  "Apply the projection mode to the target/source entity of an edge.
  Both `:full` and `:metadata-only` produce regular Clojure maps with
  explicit `:db/id` (EntityMap iteration alone omits `:db/id`).
  `:full` carries all slots; `:metadata-only` restricts to
  substrate-universal metadata."
  [edge projection-mode]
  (let [project-fn (case projection-mode
                     :full          full-projection
                     :metadata-only metadata-projection
                     (throw (ex-info (str "Unknown :projection mode `" projection-mode
                                          "`.  Valid: :full, :metadata-only.")
                                     {:projection-mode projection-mode
                                      :valid-modes #{:full :metadata-only}})))]
    (cond-> edge
      (contains? edge :target) (update :target project-fn)
      (contains? edge :source) (update :source project-fn))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; inbound-edges — edges pointing AT the entity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn inbound-edges
  "Return typed-edges pointing AT `:entity` — who references this
  entity, via which predicate, from which source.

  Required opts:
    :entity       — entity ident (keyword) or eid (long)

  Optional opts:
    :predicate    — keyword OR collection of keywords; restricts results
                    to edges whose attribute-ident is in the set.  Bare
                    keywords (no namespace) auto-resolve to slot-idents
                    on the entity's class — `:cites` becomes
                    `:mm.memory/cites` when the entity is an :mm/Memory
    :source-type  — class ident; restricts results to edges whose source
                    is an instance-of the class
    :limit        — max edges to return (default 0 = no cap)
    :projection   — `:metadata-only` (default) returns just
                    `:db/id`/`:db/ident`/`:dt/type` per source;
                    `:full` returns the complete source entity-map

  Returns:
    {:edges    [{:predicate <pred-ident> :source <entity-map>} ...]
     :total    <int>
     :returned <int>}

  Per fulltext arc Stage 16; `:projection` opt per Gap 3 of MCP cutover
  exercise 2026-05-22."
  [{:keys [entity predicate source-type limit projection]
    :or   {limit      0
           projection :metadata-only}}]
  {:pre [(some? entity)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [resolved-pred (resolve-predicates entity predicate)
        edges   (dt/inbound-edges-of
                  entity
                  (cond-> {}
                    resolved-pred (assoc :predicate   resolved-pred)
                    source-type   (assoc :source-type source-type)))
        total   (count edges)
        limited (if (zero? limit) edges (take limit edges))
        edges-v (mapv #(project-edge % projection) limited)]
    {:edges    edges-v
     :total    total
     :returned (count edges-v)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; outbound-edges — edges originating FROM the entity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn outbound-edges
  "Return typed-edges originating FROM `:entity` — what does this
  entity reference, via which predicate, to which target.

  Required opts:
    :entity       — entity ident (keyword) or eid (long)

  Optional opts:
    :predicate    — keyword OR collection of keywords; restricts results
                    to edges whose attribute-ident is in the set.  Bare
                    keywords (no namespace) auto-resolve to slot-idents
                    on the entity's class
    :target-type  — class ident; restricts results to edges whose target
                    is an instance-of the class
    :limit        — max edges to return (default 0 = no cap)
    :projection   — `:metadata-only` (default) returns just
                    `:db/id`/`:db/ident`/`:dt/type` per target;
                    `:full` returns the complete target entity-map

  Returns:
    {:edges    [{:predicate <pred-ident> :target <entity-map>} ...]
     :total    <int>
     :returned <int>}

  Per fulltext arc Stage 16; `:projection` opt per Gap 3 of MCP cutover
  exercise 2026-05-22."
  [{:keys [entity predicate target-type limit projection]
    :or   {limit      0
           projection :metadata-only}}]
  {:pre [(some? entity)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [resolved-pred (resolve-predicates entity predicate)
        edges   (dt/outbound-edges-of
                  entity
                  (cond-> {}
                    resolved-pred (assoc :predicate   resolved-pred)
                    target-type   (assoc :target-type target-type)))
        total   (count edges)
        limited (if (zero? limit) edges (take limit edges))
        edges-v (mapv #(project-edge % projection) limited)]
    {:edges    edges-v
     :total    total
     :returned (count edges-v)}))
