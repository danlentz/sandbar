(ns sandbar.search
  "Sandbar Search Namespace — Phase S of comprehensive memory-model MCP arc

  Consumer-facing wrappers around the dt/* fulltext primitives.  Composes
  per-field BM25 results into BM25F multi-field weighted scoring, projects
  results into the canonical result-shape (`:hits` / `:total` / `:returned`
  / `:timing` / optional `:snippets` / `:facets` / `:field-scores`), and
  handles result-set concerns (limit, sort-order, timing).

  Three layers in this namespace as scoped stages land:
  - search-attribute  (Stage 3 — LANDED) — single-field BM25 search
  - search-bm25f      (Stage 4)          — multi-field weighted BM25F
  - search-with-snippets / -facets       (Stages 6-7)

  Higher-level concerns (path-grammar + aggregation + orientation) live
  in sibling namespaces sandbar.{navigate,aggregate,orient}.

  Per fulltext arc plan §1.1, §1.6, §6.5, Stage 3 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Result-shape projection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- hit-map
  "Project an [eid score] tuple into the canonical hit-map shape.

  Resolves the entity via dt/* substrate (entity-map view).  Per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md,
  uses the introspectable API layer rather than raw Datomic d/pull."
  [[eid score]]
  {:entity (into {:db/id eid} (db/entity eid))
   :score  score})

(defn- now-ms []
  (System/currentTimeMillis))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; search-attribute — single-field BM25 search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn search-attribute
  "Single-field fulltext search over a `:db/fulltext true` attribute.

  Wraps `dt/search-fulltext` with result-shape projection, limit handling,
  and timing.  Returns a map with `:hits` (ranked descending by Lucene's
  BM25 single-field score), `:total` (total hits before limit), `:returned`
  (count after limit), and `:timing`.

  Required opts:
    :attribute   — slot/property ident with :db/fulltext true
    :query       — Lucene query string (phrase, boolean, wildcard, etc.)

  Optional opts:
    :limit       — max hits to return (default 50; 0 = no limit)

  Returns:
    {:hits     [{:entity <entity-map> :score <double>} ...]
     :total    <integer>
     :returned <integer>
     :timing   {:total-ms <int>}}

  Throws (via precondition) if attribute is not `:db/fulltext true`.

  Per fulltext arc Stage 3 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [{:keys [attribute query limit]
    :or   {limit 50}}]
  {:pre [(keyword? attribute)
         (string? query)
         (integer? limit)
         (>= limit 0)
         (dt/fulltext-indexed? attribute)]}
  (let [t-start    (now-ms)
        raw-hits   (dt/search-fulltext attribute query)
        sorted     (sort-by (fn [[_ score]] (- score)) raw-hits)
        total      (count sorted)
        limited    (if (zero? limit) sorted (take limit sorted))
        hits       (mapv hit-map limited)
        t-end      (now-ms)]
    {:hits     hits
     :total    total
     :returned (count hits)
     :timing   {:total-ms (- t-end t-start)}}))
