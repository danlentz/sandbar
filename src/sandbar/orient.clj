(ns sandbar.orient
  "Sandbar Orientation — entity-neighborhood + session-state views.

  Phase O of the comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  Per scope-narrowing ADR
  decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md,
  Sandbar substrate ships `library-card` as the single substrate-correct
  orientation verb.  The corpus-specific orientation surfaces (arc-forest /
  ready-queue / session-state / index-snapshot) live at the corpus
  orchestration layer where corpus-domain knowledge (`:mm.memory/memory-type
  :plan`, blocker conventions, MEMORY.md format, git inspection) belongs.

  This namespace consumes `sandbar.db.datatype/library-card-of` and projects
  the result to a JSON / EDN-friendly shape across protocol boundaries.

  Substrate-quality discipline preserved: class-agnostic; axis-specs are
  caller-supplied."
  (:require [sandbar.db.datatype :as dt]
            [sandbar.db.datomic  :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity projection helpers (mirror navigate.edges projection shape)
;;
;; Per Gap 3 of MCP cutover exercise 2026-05-22 (inbox capture
;; memory/inbox/2026-05-22_mcp_cutover_exercise_substrate_verb_authoring_queue_10_gaps_surfaced_via_orientation_of_sandbar_as_mcp_server_arc.md)
;; — library-card returns the BIGGEST payload of any orientation verb
;; (364KB for a 4-axis query in the cutover exercise) because every
;; edge's target/source carries the full entity body.  `:projection`
;; opt switches to metadata-only mode (substrate-universal fields only)
;; for orientation use cases.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- full-projection
  "Project a Datomic entity-map to a plain Clojure map preserving all
  namespaced-keyword slots + explicit `:db/id` (EntityMap iteration
  omits `:db/id`)."
  [entity]
  (when entity
    (let [base (into {}
                     (filter (fn [[k _v]]
                               (or (= :db/ident k)
                                   (and (keyword? k) (some? (namespace k))))))
                     entity)]
      (cond-> base
        (:db/id entity) (assoc :db/id (:db/id entity))))))

(defn- metadata-projection
  "Project a Datomic entity-map to substrate-universal metadata only:
  `:db/id` + `:db/ident` (if interned) + `:dt/type` (if set).
  Class-agnostic — no consumer-specific slot inclusion."
  [entity]
  (when entity
    (cond-> {}
      (:db/id entity)    (assoc :db/id    (:db/id entity))
      (:db/ident entity) (assoc :db/ident (:db/ident entity))
      (:dt/type entity)  (assoc :dt/type  (:dt/type entity)))))

(defn- projection-fn-for
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

(defn- project-edge
  "Project an edge-record's `:target` or `:source` via `project-fn`.
  Preserves the `:predicate` keyword as-is."
  [edge project-fn]
  (cond-> edge
    (:target edge) (update :target project-fn)
    (:source edge) (update :source project-fn)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; library-card — multi-axis typed-edge neighborhood view
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn library-card
  "Return a multi-axis typed-edge neighborhood view of `:entity`.

  Required opts:
    :entity     — entity ident (keyword) or eid (long)
    :axes       — vec of axis-spec maps; each:
                    {:name       <string-or-keyword>     ; label for the axis
                     :direction  :forward | :inverse     ; outbound / inbound
                     :predicates [<pred-ident>...]       ; optional predicate restriction
                     :target-type <class-ident>           ; optional for :forward axes
                     :source-type <class-ident>           ; optional for :inverse axes
                     :limit      <int>}                   ; optional per-axis cap

  Optional opts:
    :projection — `:metadata-only` (default) returns just
                  `:db/id`/`:db/ident`/`:dt/type` per entity + edge
                  target/source; `:full` returns complete entity-maps

  Returns:
    {:entity <entity-map>
     :axes   {<axis-name> [{:predicate ... :target/source <entity-map>} ...] ...}}

  Substrate-quality: class-agnostic; axis-specs are caller-supplied.  No
  hardcoded knowledge of any domain class's predicate vocabulary.  Per
  fulltext arc Phase O of plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.
  `:projection` opt per Gap 3 of MCP cutover exercise 2026-05-22."
  [{:keys [entity axes projection]
    :or   {projection :metadata-only}}]
  ;; Per ADR §D-3.2 (Option B), the `:pre` guard on `entity` (a ref-arg)
  ;; is dropped — boundary layer (sandbar.entity-ref) owns boundary
  ;; validation for ref shape + existence.  Non-ref `:pre` invariant on
  ;; `axes` (sequential? shape) stays.
  {:pre [(sequential? axes)]}
  (let [{raw-entity :entity raw-axes :axes}
        (dt/library-card-of entity axes)
        project-fn (projection-fn-for projection)]
    {:entity (project-fn raw-entity)
     :axes   (reduce-kv (fn [acc axis-name edges]
                          (assoc acc axis-name
                                 (mapv #(project-edge % project-fn) edges)))
                        {}
                        raw-axes)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; type-tree — class-hierarchy nested rendering (Stage 5.B-pre #3)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- subtree
  "Recursively build a nested-map of class subtree rooted at `class-ident`.
   Returns `{:class <ident> :children [<subtree>...]}`.  Cycles are
   prevented via the `visited` set."
  [class-ident visited]
  (if (contains? visited class-ident)
    {:class class-ident :cycle? true :children []}
    (let [direct-children (sort (dt/direct-subclasses-of class-ident))
          visited'        (conj visited class-ident)]
      {:class    class-ident
       :children (mapv #(subtree % visited') direct-children)})))

(defn type-tree
  "Return the class-hierarchy subtree rooted at `:root` (default
  `:dt/Resource` — the metamodel root).  Recursive walk via
  `dt/direct-subclasses-of`; produces a nested-map tree with `:class` +
  `:children` per node.

  Required opts: none — `:root` defaults to `:dt/Resource`.

  Optional opts:
    :root — root class ident (default `:dt/Resource`)

  Returns:
    {:root <ident> :tree {<nested-tree>}}

  Per Stage 5.B-pre #3 of decisions/stage_5_mcp_verb_authoring_sub_arc_2026_05_21.md."
  [{:keys [root] :or {root :dt/Resource}}]
  {:pre [(keyword? root)]}
  {:root root
   :tree (subtree root #{})})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tree — corpus rel-path directory tree (Stage 5.B-pre #3)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- group-by-dir
  "Group entity-maps by their `:path-slot` value's directory prefix
   (one level deep).  Returns `{dir-string [entity-map ...]}`."
  [entities path-slot]
  (reduce
    (fn [acc e]
      (let [path (get e path-slot)
            dir  (if (and path (clojure.string/includes? path "/"))
                   (subs path 0 (clojure.string/index-of path "/"))
                   "")]
        (update acc dir (fnil conj []) e)))
    {}
    entities))

(defn tree
  "Return a top-level directory grouping of entities by their `:path-slot`
  value.  Output shape: `{:dirs {<dir-name> {:count N :sample [<entity-map>...]}}
                          :total N}`.

  Required opts:
    :class     — class ident whose instances to group (e.g. `:mm/Memory`)
    :path-slot — slot ident carrying the filesystem-style path
                 (e.g. `:mm.memory/rel-path`)

  Optional opts:
    :sample-size — entities sampled per directory (default 0 = none)

  Per Stage 5.B-pre #3."
  [{:keys [class path-slot sample-size]
    :or   {sample-size 0}}]
  {:pre [(keyword? class)
         (keyword? path-slot)]}
  (let [instances (dt/all-named-instances-of class)
        entities  (mapv (fn [ident]
                          (let [e (db/entity ident)]
                            (cond-> {:db/ident ident}
                              (get e path-slot)
                              (assoc path-slot (get e path-slot)))))
                        instances)
        by-dir    (group-by-dir entities path-slot)]
    {:dirs (reduce-kv
             (fn [acc dir es]
               (assoc acc dir
                      (cond-> {:count (count es)}
                        (pos? sample-size)
                        (assoc :sample (vec (take sample-size es))))))
             {}
             by-dir)
     :total (count entities)}))
