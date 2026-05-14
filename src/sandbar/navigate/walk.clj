(ns sandbar.navigate.walk
  "Sandbar Navigation — Graph-Walk Subsurface (Stage 17 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Consumer-facing wrapper around the dt/graph-walk-from substrate
  primitive.  Provides one verb:

    graph-walk — BFS reachable-neighborhood traversal with hop-cap,
                 direction selection (`:forward` / `:inverse` /
                 `:bidirectional`), predicate-set filtering, and
                 optional path projection (`:include [:paths]`).

  Substrate-quality preserved per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
  routes through `sandbar.db.datatype/graph-walk-from`, never raw
  `datomic.api`."
  (:require [sandbar.db.datatype :as dt]))

(defn graph-walk
  "BFS-reachable neighborhood from `:from` within `:hops` levels.

  Required opts:
    :from — entity ident (keyword) or eid (long); the seed

  Optional opts:
    :hops       — max distance to walk (default 4)
    :predicates — keyword OR collection of keywords; restricts edges
                  to a predicate set
    :direction  — :forward (outbound only; default) / :inverse (inbound
                  only) / :bidirectional (union)
    :include    — collection of projection options.  `:paths` attaches
                  a shortest-path step-sequence to each result entity:
                  `[{:predicate <pred-ident> :direction :forward|:inverse} ...]`
    :limit      — cap on returned results (default 0 = no cap; the cap
                  is applied AFTER traversal completes, so `:total`
                  always reflects the full reachable set)

  Returns:
    {:reachable [{:entity <entity-map> :hop <int> [:path [...] ]} ...]
     :total    <int>
     :returned <int>}

  Substrate-quality: class-agnostic; predicate-set + direction +
  include opts are caller-supplied.  Per fulltext arc Stage 17."
  [{:keys [from hops predicates direction include limit]
    :or   {limit 0}}]
  {:pre [(some? from)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [primitive-opts (cond-> {}
                         hops       (assoc :hops hops)
                         predicates (assoc :predicates predicates)
                         direction  (assoc :direction direction)
                         include    (assoc :include include))
        results        (if (seq primitive-opts)
                         (dt/graph-walk-from from primitive-opts)
                         (dt/graph-walk-from from))
        total          (count results)
        limited        (if (zero? limit) results (take limit results))
        out            (vec limited)]
    {:reachable out
     :total     total
     :returned  (count out)}))
