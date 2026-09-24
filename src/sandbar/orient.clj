(ns sandbar.orient
  "Orientation views over typed model and entity relationships.

  library-card projects caller-defined neighborhood axes; type-tree follows
  the class hierarchy; tree summarizes path-based groups. Domain-specific
  planning, queues and session policy remain the application's responsibility.
  These views reuse model/navigation primitives and return data that transport
  adapters can serialize without rebuilding the query logic."
  (:require [sandbar.api.projection :as projection]
            [sandbar.db.datatype     :as dt]
            [sandbar.db.datomic      :as db]
            [sandbar.navigate.edges  :as nav-edges]))

;; Projection helpers lifted to `sandbar.api.projection` (single source
;; of truth across navigate/edges + orient + api/aggregate + mcp/tools
;; + navigate/siblings + navigate/path).  Per Task #12 of MCP cutover
;; batch — DRY cleanup of 5+ duplicates.

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; library-card — multi-axis typed-edge neighborhood view
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn library-card
  "Return a projected neighborhood for an entity ident or eid and axis specs.
  Each axis has :name, :direction (:forward or :inverse), optional :predicates,
  :target-type or :source-type, and :limit. Qualified predicates retain their
  relationship meaning when similar local names exist on different classes.

  :projection defaults to :metadata-only; :full requests complete entities.
  Returns {:entity entity :axes {axis-name [{:predicate p :target entity} ...]}};
  inverse axes use :source instead of :target. Axis definitions belong to the
  caller, so the view does not assume a domain-specific vocabulary."
  [{:keys [entity axes projection]
    :or   {projection :metadata-only}}]
  ;; Per ADR §D-3.2 (Option B), the `:pre` guard on `entity` (a ref-arg)
  ;; is dropped — boundary layer (sandbar.entity-ref) owns boundary
  ;; validation for ref shape + existence.  Non-ref `:pre` invariant on
  ;; `axes` (sequential? shape) stays.
  {:pre [(sequential? axes)]}
  ;; Resolve bare predicate keywords (`:cites`) per-axis to slot-idents
  ;; (`:mm.memory/cites`) against the entity's class.  Same Gap 7 fix as
  ;; navigate edges; library-card was the last predicate-accepting verb
  ;; still on the silent-zero-hit footing for bare predicate forms.
  ;; Per inbox capture
  ;; memory/inbox/2026-05-22_mcp_cutover_exercise_substrate_verb_authoring_queue_10_gaps_surfaced_via_orientation_of_sandbar_as_mcp_server_arc.md.
  ;; D7b (2026-09-20): an inverse axis resolves bare predicates the inbound
  ;; way — against its `:source-type` when given, else schema-wide — since
  ;; the slot behind an inbound edge belongs to the source's class, not the
  ;; anchor's.
  (let [resolved-axes
        (mapv (fn [{:keys [predicates direction source-type] :as axis-spec}]
                (cond-> axis-spec
                  predicates
                  (assoc :predicates
                         (if (= (keyword direction) :inverse)
                           (nav-edges/resolve-predicates entity predicates :inbound source-type)
                           (nav-edges/resolve-predicates entity predicates :outbound)))))
              axes)
        {raw-entity :entity raw-axes :axes}
        (dt/library-card-of entity resolved-axes)
        project-fn (projection/projection-fn-for projection)]
    {:entity (project-fn raw-entity)
     :axes   (reduce-kv (fn [acc axis-name edges]
                          (assoc acc axis-name
                                 (mapv #(projection/project-edge % projection) edges)))
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
  "Return the class hierarchy below :root, defaulting to :dt/Resource.
  Follows direct subclasses and returns {:root ident :tree node}, with :class
  and :children on nodes. Cycles are marked; multiple inheritance can show a
  class in more than one branch."
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
  "Return a top-level directory grouping of named entities by their `:path-slot`
  value.  Output shape: `{:dirs {<dir-name> {:count N :sample [<entity-map>...]}}
                          :total N}`.

  Required opts:
    :class     — class ident whose instances to group (e.g. `:mm/Memory`)
    :path-slot — slot ident carrying the filesystem-style path
                 (e.g. `:mm.memory/rel-path`)

  Optional opts:
    :sample-size — entities sampled per directory (default 0 = none)

  Unnamed entities are excluded. Samples contain ident and the path value,
  not full entity content; use exact reads for the selected records."
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
