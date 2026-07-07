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
  Cypher shortestPath-style BFS first-arrival).

  S7 EP-3 firewall routing (2026-07-06): reachability for the
  evaluator-supported operators (Canonical-8 + desugarable) runs
  through the guarded Clojure-side BFS evaluator whether or not
  `:include #{:paths}` is requested — the per-hop firewall verdict is
  applied SYMMETRICALLY to the physically-written edge direction (§8-
  R17), so a FORBIDDEN hop (public→private flow, or cross-private) is
  dropped from the frontier from EITHER traversal end and can never
  appear as an endpoint, and each dropped hop is surfaced in the
  result's `:blocked` (§5 audit, never silent — eid-FREE rows per R16).
  A PERMITTED private→public edge lists normally from either end (the
  `[:INV …]` inbound case): whether a public seed learns a private
  citer EXISTS is the inbound-existence side-channel ruled S9 physical-
  exclusion territory (§8-R17, note-not-block) — not an EP-3 concern.
  This replaces the pre-S7 raw-Datalog endpoint-only fast-path, which
  bypassed the firewall entirely (confidentiality is a safety property
  that outranks the fast-path's perf).

  Tier-2 `:NOT` / `:FILTER` / `:TEST` — the evaluator does not yet
  execute these (path-data AND the per-hop guard land in a follow-on).
  With `:include #{:paths}` they throw descriptive ex-info; endpoint-
  only compiles via Datalog under a COARSE fail-closed seed→endpoint
  firewall filter (`fw-enforce/endpoint-permitted?`) — see the fn body
  for the narrow documented residual.  See
  `sandbar.navigate.path.evaluate` for full operator coverage."
  (:require [clojure.edn         :as edn]
            [datomic.api         :as d]
            [sandbar.api.projection         :as projection]
            [sandbar.db.datomic  :as db]
            [sandbar.firewall.enforce       :as fw-enforce]
            [sandbar.navigate.path.ast      :as ast]
            [sandbar.navigate.path.datomic  :as compiler]
            [sandbar.navigate.path.evaluate :as evalpath]
            [sandbar.navigate.path.ir       :as ir]))

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
        include-paths? (contains? (set include) :paths)
        cap           (fn [xs] (if (zero? limit) xs (take limit xs)))]
    (if (evalpath/evaluable? canon-tree)
      ;; ── Guarded evaluator route (Canonical-8 + desugarable) ──────────────
      ;; S7 EP-3 leak-fix (2026-07-06): reachability runs through the Clojure-
      ;; side BFS evaluator, which applies the per-hop firewall verdict at its
      ;; single choke point — a public→private (or cross-private) hop is dropped
      ;; from the frontier, so a firewall-forbidden endpoint NEVER appears in
      ;; `:reachable`, and each dropped hop is surfaced in `:blocked` (§5 audit,
      ;; never silent).  This supersedes the old raw-Datalog endpoint-only
      ;; fast-path, which bypassed the firewall entirely (the confidentiality
      ;; property outranks the fast-path's perf; the evaluator is the SAME BFS
      ;; the `:include #{:paths}` route already used).
      (let [{:keys [endpoints blocked]} (evalpath/reachable (db/db) canon-tree seed-eid)
            enriched (if include-paths?
                       (mapv (fn [{:keys [eid path]}]
                               {:entity (projection/full-projection (db/entity eid))
                                :path   path})
                             endpoints)
                       (mapv (fn [{:keys [eid]}]
                               (projection/full-projection (db/entity eid)))
                             endpoints))
            returned (vec (cap enriched))]
        {:reachable returned
         :total     (count enriched)
         :returned  (count returned)
         :blocked   blocked})
      ;; ── Tier-2 :NOT / :FILTER / :TEST fallback ───────────────────────────
      ;; The evaluator does not yet execute these operators (path-data + the
      ;; per-hop EP-3 guard both land in a follow-on).  Endpoint-only still
      ;; compiles via Datalog, but the raw endpoint set would leak firewall-
      ;; forbidden-reachable entities — so we apply the COARSE fail-closed
      ;; seed→endpoint filter (`endpoint-permitted?`).  This drops the common
      ;; leak (public seed → private endpoint) and preserves non-governed
      ;; traversal (a metamodel walk stays within one UNASSIGNED compartment);
      ;; a narrow residual (a path dipping through a :public intermediate back
      ;; into the seed's own private compartment) is documented — the per-hop
      ;; evaluator is the complete fix.  `:include #{:paths}` for these operators
      ;; is still unsupported and throws in the evaluator (path-data deferred).
      (if include-paths?
        (evalpath/evaluate-from (db/db) canon-tree seed-eid) ; throws unsupported-op
        (let [{:keys [where rules]} (compiler/compile canon-tree '?start '?end)
              q        (vec (concat '[:find [?end ...]
                                      :in $ % ?start
                                      :where] where))
              db-now   (db/db)
              eids     (d/q q db-now rules seed-eid)
              safe     (filterv #(fw-enforce/endpoint-permitted? db-now seed-eid %) eids)
              dropped  (remove #(fw-enforce/endpoint-permitted? db-now seed-eid %) eids)
              entities (mapv (comp projection/full-projection db/entity) safe)
              returned (vec (cap entities))]
          {:reachable returned
           :total     (count entities)
           :returned  (count returned)
           ;; Coarse audit signal — one eid-FREE row per withheld endpoint
           ;; (R16: the withheld eid must NOT reach the caller — that would
           ;; disclose a forbidden-compartment entity's identity; row presence
           ;; alone preserves the audit signal, count via the vec length).
           :blocked   (mapv (fn [_] {:blocked true
                                     :reason  :firewall/flow-forbidden
                                     :coarse  true})
                            dropped)})))))
