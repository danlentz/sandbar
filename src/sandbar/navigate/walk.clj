(ns sandbar.navigate.walk
  "Bounded neighborhood traversal over dt/graph-walk-from.

  graph-walk uses breadth-first discovery with a hop cap, predicate filter and
  direction (:forward, :inverse or :bidirectional). include [:paths] adds a
  shortest discovery witness for each endpoint. The seed is excluded. A
  response limit applies after traversal, so use hops and predicates to
  constrain work as well as response size."
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
  include opts are caller-supplied."
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
