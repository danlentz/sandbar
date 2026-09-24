(ns sandbar.navigate.edges
  "Typed inbound and outbound edge views over the model's reference properties.

  inbound-edges answers who refers to an anchor; outbound-edges answers what
  the anchor refers to. Each edge retains its qualified property and source
  or target. Eids support anchors and members without idents. The wrappers
  compose predicate resolution, type filtering, projection and limits over
  the shared dt/* primitives, with distinct entity counts kept separate from
  edge counts."
  (:require [sandbar.api.projection :as projection]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]))

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

(defn- resolve-bare-predicate-schema-wide
  "Resolve a bare predicate keyword to EVERY ref-typed property in the
  metamodel that carries its local name — the rule for an INBOUND
  predicate, whose slot belongs to the source's class and not to the
  anchor's, and for an anchor that has no class (an identless value
  carrier).  Returns the vec of qualified idents; each edge then reports
  the qualified predicate it was found through, so a name shared by
  several classes (`:tags` on memories, rules, actors, contexts) yields
  the union of those relations with the role visible per edge rather
  than a silent resolution against the wrong class.  Throws ex-info
  when no ref-typed property carries the name.  D7b, RT-14 item 4
  (2026-09-20)."
  [pred]
  (let [candidates (->> (dt/all-properties)
                        (filter #(= (name pred) (name %)))
                        (filter #(= :db.type/ref (:db/valueType (db/entity %))))
                        (sort)
                        (vec))]
    (if (seq candidates)
      candidates
      (throw (ex-info
               (str "Bare predicate `:" (name pred)
                    "` matches no ref-typed property in the schema.  Use "
                    "the fully-qualified slot ident (membership: "
                    ":mm.memory/tags, :mm.memory/themes).")
               {:bare-predicate  pred
                :resolution      :no-match
                :available-slots []})))))

(defn resolve-predicates
  "Resolve one predicate keyword or a collection into qualified slot idents.
  Qualified names pass through. Outbound bare names resolve against the
  anchor's class; a classless anchor uses every reference property with that
  local name. Inbound names resolve against owner-class when supplied,
  otherwise schema-wide. An unmatched name is refused. Multiple matches on
  a supplied class are ambiguous and refused; schema-wide resolution returns
  the union so qualified edge roles remain visible.

  The two-argument arity uses outbound semantics. Nil predicate returns nil,
  preserving the unfiltered-edge contract. For membership use qualified
  :mm.memory/tags and :mm.memory/themes explicitly."
  ([entity-ident predicate]
   (resolve-predicates entity-ident predicate :outbound nil))
  ([entity-ident predicate direction]
   (resolve-predicates entity-ident predicate direction nil))
  ([entity-ident predicate direction owner-class]
   (when (some? predicate)
     (let [preds   (if (sequential? predicate) predicate [predicate])
           bare?   (fn [p] (and (keyword? p) (nil? (namespace p))))
           needs-resolution? (some bare? preds)]
       (if-not needs-resolution?
         (vec preds)
         (let [klass (if (= direction :inbound)
                       owner-class
                       (dt/class-ident-of entity-ident))
               slots (when klass (dt/slots-of klass))]
           (into []
                 (mapcat (fn [p]
                           (cond
                             (not (bare? p)) [p]
                             (seq slots)     [(resolve-bare-predicate p klass slots)]
                             :else           (resolve-bare-predicate-schema-wide p))))
                 preds)))))))

(defn- distinct-total
  "The number of distinct entities at `role` (`:source` or `:target`)
  across ALL edges before any limit — the record count a client gets
  after deduplicating by id, kept distinguishable from the edge count
  (a record reached through two predicates is two edges and one
  record).  Blocked edges carry no entity and are not counted."
  [edges role]
  (count (distinct (keep (comp :db/id role) edges))))

;; Projection helpers lifted to `sandbar.api.projection` per Task #12 —
;; navigate/edges uses the shared `project-edge` which dispatches on
;; the `:projection` mode (`:metadata-only` default for navigation use
;; cases; `:full` available for complete-body consumers).

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
                    keywords resolve against `:source-type` when given,
                    otherwise against ref attributes across the schema
    :source-type  — class ident; restricts results to edges whose source
                    is an instance-of the class
    :limit        — max edges to return (default 0 = no cap)
    :projection   — `:metadata-only` (default) returns just
                    `:db/id`/`:db/ident`/`:dt/type` per source;
                    `:full` returns the complete source entity-map

  Returns:
    {:edges          [{:predicate <pred-ident> :source <entity-map>} ...]
     :total          <int>   ; edges, before the limit
     :distinct-total <int>   ; distinct sources, before the limit
     :returned       <int>
     :limit          <int>
     :truncated?     <bool>}

  Pass qualified predicates such as `:mm.memory/tags` and
  `:mm.memory/themes` when the role matters. One source may contribute
  several edges, so `:distinct-total` can be smaller than `:total`.
  Policy-blocked entries have :blocked and :reason but omit :source;
  they count as edges, not distinct source identities."
  [{:keys [entity predicate source-type limit projection]
    :or   {limit      0
           projection :metadata-only}}]
  {:pre [(some? entity)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [resolved-pred (resolve-predicates entity predicate :inbound source-type)
        edges   (dt/inbound-edges-of
                  entity
                  (cond-> {}
                    resolved-pred (assoc :predicate   resolved-pred)
                    source-type   (assoc :source-type source-type)))
        total   (count edges)
        limited (if (zero? limit) edges (take limit edges))
        edges-v (mapv #(projection/project-edge % projection) limited)]
    {:edges          edges-v
     :total          total
     :distinct-total (distinct-total edges :source)
     :returned       (count edges-v)
     :limit          limit
     :truncated?     (< (count edges-v) total)}))

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
                    on the entity's class, or across the schema when
                    the anchor has no class
    :target-type  — class ident; restricts results to edges whose target
                    is an instance-of the class
    :limit        — max edges to return (default 0 = no cap)
    :projection   — `:metadata-only` (default) returns just
                    `:db/id`/`:db/ident`/`:dt/type` per target;
                    `:full` returns the complete target entity-map

  Returns:
    {:edges          [{:predicate <pred-ident> :target <entity-map>} ...]
     :total          <int>   ; edges, before the limit
     :distinct-total <int>   ; distinct targets, before the limit
     :returned       <int>
     :limit          <int>
     :truncated?     <bool>}

  One target may be reached through several predicates, so
  `:distinct-total` can be smaller than `:total`. Policy-blocked entries
  have :blocked and :reason but omit :target; they count as edges,
  not distinct target identities."
  [{:keys [entity predicate target-type limit projection]
    :or   {limit      0
           projection :metadata-only}}]
  {:pre [(some? entity)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [resolved-pred (resolve-predicates entity predicate :outbound)
        edges   (dt/outbound-edges-of
                  entity
                  (cond-> {}
                    resolved-pred (assoc :predicate   resolved-pred)
                    target-type   (assoc :target-type target-type)))
        total   (count edges)
        limited (if (zero? limit) edges (take limit edges))
        edges-v (mapv #(projection/project-edge % projection) limited)]
    {:edges          edges-v
     :total          total
     :distinct-total (distinct-total edges :target)
     :returned       (count edges-v)
     :limit          limit
     :truncated?     (< (count edges-v) total)}))
