(ns sandbar.navigate.siblings
  "Sandbar Navigation — Same-Directory Peers (Stage 22 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Consumer-facing wrapper around `sandbar.db.datatype/siblings-of` —
  the substrate primitive that returns entities sharing the same
  directory prefix as a given anchor entity.

  ## When to use vs. alternatives

  - `sandbar.navigate.siblings/siblings-of` — filesystem-style
    same-directory peers (this namespace).  E.g., all decision
    memorials under `decisions/`.
  - `sandbar.navigate.edges/inbound-edges` (with `:next-sibling` /
    `:previous-sibling` predicate filter) — typed-edge sibling-chain
    navigation (mm/Section's pairwise SIOC sibling chain).
  - `sandbar.navigate.path/path-via` with `:FILTER` over a directory
    prefix — broader same-tree-prefix queries with recursive descent.

  Substrate-quality discipline preserved per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
  routes through `dt/siblings-of`, never raw `datomic.api`."
  (:require [sandbar.db.datatype :as dt]))

(defn- entity-projection
  "Project a Datomic entity-map to a plain map for JSON / EDN
   serialization across protocol boundaries.

   Note: Datomic entity-iteration does NOT include `:db/id` in the
   key-seq (it's accessed via a special method).  We explicitly add
   `:db/id` to the projection."
  [entity]
  (when entity
    (let [base (into {}
                     (filter (fn [[k _v]]
                               (or (= :db/ident k)
                                   (and (keyword? k) (some? (namespace k))))))
                     entity)]
      (cond-> base
        (:db/id entity) (assoc :db/id (:db/id entity))))))

(defn siblings-of
  "Return same-directory peers of `:entity` via `:path-slot`.

  Required opts:
    :entity    — entity ident (keyword) or eid (long); must have
                 `:path-slot` value populated
    :path-slot — slot ident (e.g., `:mm.memory/rel-path`) carrying
                 the filesystem-style path string

  Optional opts:
    :limit — cap on returned siblings (default 0 = no cap; the cap
             is applied AFTER substrate lookup, so `:total` reflects
             the full sibling set)

  Returns:
    {:siblings [<entity-map> ...]
     :total    <int>
     :returned <int>}

  Substrate-quality: class-agnostic; `:path-slot` is caller-supplied.
  Per fulltext arc Stage 22."
  [{:keys [entity path-slot limit]
    :or   {limit 0}}]
  ;; Per ADR §D-3.2 (Option B), the `:pre` guard on `entity` (a ref-arg)
  ;; is dropped — boundary layer (sandbar.entity-ref) owns boundary
  ;; validation for ref shape + existence.  Non-ref `:pre` invariants
  ;; on `path-slot` (keyword? shape) + `limit` stay.
  {:pre [(keyword? path-slot)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [raw      (dt/siblings-of entity path-slot)
        total    (count raw)
        limited  (if (zero? limit) raw (take limit raw))
        projected (mapv entity-projection limited)]
    {:siblings projected
     :total    total
     :returned (count projected)}))
