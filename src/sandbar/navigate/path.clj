(ns sandbar.navigate.path
  "Sandbar Path-Grammar — Consumer-Facing API (Stage P-6 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Threads the path-grammar layers — AST (P-1) → IR (P-2) → Datomic
  compiler (P-3 + P-4) → query execution → result projection — into
  a single opts-shaped consumer verb.

  Composes with:
    - sandbar.navigate.path.ast       — EDN parsing
    - sandbar.navigate.path.ir        — algebraic canonicalization
    - sandbar.navigate.path.datomic   — Datomic compilation
    - sandbar.navigate.path.value     — path-data abstraction (P-5)

  Exposure protocols (per fulltext arc §5.2):
    - In-process Clojure: (sandbar.navigate.path/path-via opts)  (this fn)
    - MCP verb:            sandbar.navigate.path-via              (sandbar.mcp.tools)
    - REST endpoint:       GET /api/navigate/path                 (sandbar.api.navigate)

  ## Path-data surfacing

  `:include #{:paths}` is POPULATED (Phase R Stage R-7 / 2026-05-15
  per decisions/sandbar_path_data_reconstruction_option_d_policy_a_2026_05_14.md).
  When `:paths` is requested, the result's `:reachable` carries
  `{:entity ... :path <path-value>}` maps with actual path data —
  routed through `sandbar.navigate.path.evaluate`'s Clojure-side BFS
  evaluator (analogous to the `dt/graph-walk-from` precedent per
  decisions/sandbar_graph_walk_clojure_bfs_over_datomic_recursive_rules_2026_05_14.md).
  No `:path-data-deferred` flag — Option D commitment closes the
  documented-contract-vs-runtime gap permanently.

  Path-explosion policy A (one-representative-path-per-endpoint,
  Cypher shortestPath-style BFS first-arrival).  When omitting
  `:include #{:paths}`, the endpoint-only fast-path stays — Datalog
  query unchanged; no perf regression for the common case.

  Tier-2 `:NOT` / `:FILTER` / `:TEST` with `:include #{:paths}` throw
  descriptive ex-info — path-data evaluation for these lands in
  0.1.x; endpoint-only still works.  See
  `sandbar.navigate.path.evaluate` for full operator coverage."
  (:require [clojure.edn         :as edn]
            [datomic.api         :as d]
            [sandbar.db.datomic  :as db]
            [sandbar.navigate.path.ast      :as ast]
            [sandbar.navigate.path.datomic  :as compiler]
            [sandbar.navigate.path.evaluate :as evalpath]
            [sandbar.navigate.path.ir       :as ir]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal: entity projection (mirror MCP/REST layer convention so
;; results round-trip cleanly through JSON / EDN serialization).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity-projection
  "Project a Datomic entity-map to a plain map keyed by :db/id +
   :db/ident + namespaced-keyword slots.

   Note: Datomic entity-iteration does NOT include `:db/id` in the
   key-seq (special method).  We explicitly add it."
  [entity]
  (when entity
    (let [base (into {}
                     (filter (fn [[k _v]]
                               (or (= :db/ident k)
                                   (and (keyword? k) (some? (namespace k))))))
                     entity)]
      (cond-> base
        (:db/id entity) (assoc :db/id (:db/id entity))))))

(defn- parse-via
  "Accept :via as EDN-string OR pre-parsed Clojure data (vector /
  keyword).  Returns the parsed Clojure form ready for ast/parse."
  [via]
  (cond
    (string? via)
    (try
      (edn/read-string via)
      (catch Exception e
        (throw (ex-info (str "Invalid :via EDN: " (.getMessage e))
                        {:received via}))))

    (or (keyword? via) (sequential? via))
    via

    :else
    (throw (ex-info "Invalid :via — must be EDN string, keyword, or vector"
                    {:received via}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Consumer-facing path-via
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn path-via
  "Walk a path-grammar expression `:via` starting from `:from` and
  return reachable entities.

  Required opts:
    :from — seed entity ident (keyword) or eid (long)
    :via  — path-grammar expression (EDN string OR Clojure data)

  Optional opts:
    :limit   — cap on returned results (default 0 = no cap; the cap
               is applied AFTER query execution; `:total` reflects
               full result set)
    :include — collection of projection options.  `:paths` populates
               each `:reachable` entry with the path-value from seed
               to endpoint (per path.value contract — `:nodes` +
               `:edges`).  Tier-2 :NOT / :FILTER / :TEST with :paths
               throw ex-info (path-data evaluation for those lands in
               0.1.x; omit :paths to get endpoint-only reachability).

  Returns:
    ;; Without :include #{:paths} — endpoint-only fast-path
    {:reachable [<entity-map> ...]
     :total     <int>
     :returned  <int>}

    ;; With :include #{:paths} — Clojure-side evaluator route
    {:reachable [{:entity <entity-map> :path <path-value>} ...]
     :total     <int>
     :returned  <int>}

  Per fulltext arc Stage P-6 + Phase R Stage R-7.  Composes with
  cross-axis layers via the four-axis decomposition (search ∩
  aggregate ∩ navigate ∩ orient); other axes wire `:from`/`:via` as
  opts at Stage 29 (cross-axis composition)."
  [{:keys [from via limit include]
    :or   {limit 0}}]
  ;; Per ADR §D-3.2 (Option B), the `:pre` guard on `from` (a ref-arg)
  ;; is dropped — boundary layer (sandbar.entity-ref) owns boundary
  ;; validation for ref shape + existence.  Callers (MCP / REST / in-
  ;; process) call `eref/resolve-ident` on `from` before reaching this
  ;; function.  The `(when (nil? (:db/id seed-ent)) ...)` check below
  ;; remains as defense for the in-process caller that bypasses the
  ;; boundary.  Non-ref `:pre` invariants on `via` + `limit` stay.
  {:pre [(some? via)
         (or (nil? limit) (and (integer? limit) (>= limit 0)))]}
  (let [parsed-via    (parse-via via)
        ast-tree      (ast/parse parsed-via)
        canon-tree    (ir/canonicalize ast-tree)
        seed-ent      (db/entity from)
        _             (when (nil? (:db/id seed-ent))
                        (throw (ex-info (str "Seed entity not found: " from)
                                        {:from from})))
        seed-eid      (:db/id seed-ent)
        include-paths? (contains? (set include) :paths)]
    (if include-paths?
      ;; Path-data branch — Clojure-side BFS evaluator (Phase R Stage
      ;; R-7; Option D + Policy A).  Returns {:eid :path} maps; we
      ;; project entity-maps + apply :limit for the result shape.
      (let [eval-results (evalpath/evaluate-from (db/db) canon-tree seed-eid)
            enriched     (mapv (fn [{:keys [eid path]}]
                                 {:entity (entity-projection (db/entity eid))
                                  :path   path})
                               eval-results)
            total        (count enriched)
            limited      (if (zero? limit) enriched (take limit enriched))
            returned     (vec limited)]
        {:reachable returned
         :total     total
         :returned  (count returned)})
      ;; Endpoint-only fast-path — Datalog compiler unchanged (no
      ;; perf regression for the common case).
      (let [{:keys [where rules]} (compiler/compile canon-tree '?start '?end)
            q          (vec (concat '[:find [?end ...]
                                       :in $ % ?start
                                       :where] where))
            eids       (d/q q (db/db) rules seed-eid)
            entities   (mapv (comp entity-projection db/entity) eids)
            total      (count entities)
            limited    (if (zero? limit) entities (take limit entities))
            returned   (vec limited)]
        {:reachable returned
         :total     total
         :returned  (count returned)}))))
