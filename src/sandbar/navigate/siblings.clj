(ns sandbar.navigate.siblings
  "Same-directory peers based on a caller-selected path property.

  Wraps dt/siblings-of to find entities whose paths share the anchor's
  directory prefix. This differs from a document section's next-sibling edge
  or a recursive subtree traversal. A path is representation metadata; use
  typed relationships when the question concerns semantic containment."
  (:require [sandbar.api.projection :as projection]
            [sandbar.db.datatype    :as dt]))

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
        projected (mapv projection/full-projection limited)]
    {:siblings projected
     :total    total
     :returned (count projected)}))
