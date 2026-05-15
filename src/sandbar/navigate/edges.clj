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
;; inbound-edges — edges pointing AT the entity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn inbound-edges
  "Return typed-edges pointing AT `:entity` — who references this
  entity, via which predicate, from which source.

  Required opts:
    :entity       — entity ident (keyword) or eid (long)

  Optional opts:
    :predicate    — keyword OR collection of keywords; restricts results
                    to edges whose attribute-ident is in the set
    :source-type  — class ident; restricts results to edges whose source
                    is an instance-of the class
    :limit        — max edges to return (default 0 = no cap)

  Returns:
    {:edges    [{:predicate <pred-ident> :source <entity-map>} ...]
     :total    <int>
     :returned <int>}

  Per fulltext arc Stage 16."
  [{:keys [entity predicate source-type limit]
    :or   {limit 0}}]
  {:pre [(some? entity)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [edges   (dt/inbound-edges-of
                  entity
                  (cond-> {}
                    predicate   (assoc :predicate   predicate)
                    source-type (assoc :source-type source-type)))
        total   (count edges)
        limited (if (zero? limit) edges (take limit edges))
        edges-v (vec limited)]
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
                    to edges whose attribute-ident is in the set
    :target-type  — class ident; restricts results to edges whose target
                    is an instance-of the class
    :limit        — max edges to return (default 0 = no cap)

  Returns:
    {:edges    [{:predicate <pred-ident> :target <entity-map>} ...]
     :total    <int>
     :returned <int>}

  Per fulltext arc Stage 16."
  [{:keys [entity predicate target-type limit]
    :or   {limit 0}}]
  {:pre [(some? entity)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [edges   (dt/outbound-edges-of
                  entity
                  (cond-> {}
                    predicate   (assoc :predicate   predicate)
                    target-type (assoc :target-type target-type)))
        total   (count edges)
        limited (if (zero? limit) edges (take limit edges))
        edges-v (vec limited)]
    {:edges    edges-v
     :total    total
     :returned (count edges-v)}))
