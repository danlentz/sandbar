(ns sandbar.navigate.path
  "Consumer-facing path queries from EDN expressions to projected endpoints.

  path-via composes parsing, normalization, execution and projection. It is
  available in-process, through the MCP catalog and through the REST path
  adapter. With :include [:paths], reachable entries contain :entity and a
  representative :path witness; otherwise they contain endpoint entities.

  Core and desugarable operators run through the Clojure evaluator, with
  physically directed edge-policy checks at each hop, whether or not a
  witness was requested. Inverse traversal applies the policy to the stored
  edge direction. Rejected hops are reported as blocked entries.

  NOT, FILTER and TEST use an endpoint-only Datomic route with a coarser
  seed-to-endpoint check; requesting witnesses for them throws. Those checks
  do not establish identical interior-path visibility guarantees. A permitted
  private-to-public edge can also reveal an inbound source's existence to a
  caller unless the broader read boundary excludes it. See
  doc/firewall-and-projects.md before relying on navigation for isolation.

  Witness selection retains one representative per endpoint, not all paths.
  Limit applies after traversal; depth and branching determine execution cost."
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
               throw ex-info; omit :paths for their endpoint-only route.
    :projection — entity projection mode (default :full). Applied once while
                  each endpoint still carries its database and ownership.

  Returns:
    ;; Without :include #{:paths} — endpoint-only fast-path
    {:reachable [<entity-map> ...]
     :total     <int>
     :returned  <int>}

    ;; With :include #{:paths} — Clojure-side evaluator route
    {:reachable [{:entity <entity-map> :path <path-value>} ...]
     :total     <int>
     :returned  <int>}

  Search accepts the same :from/:via restriction to intersect lexical hits
  with a reachable population. A returned endpoint or witness has the policy
  coverage of its execution route; see this namespace's boundary notes."
  [{:keys [from via limit include projection]
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
        project       (projection/projection-fn-for (or projection :full))
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
                               {:entity (project (db/entity eid))
                                :path   path})
                             endpoints)
                       (mapv (fn [{:keys [eid]}]
                               (project (db/entity eid)))
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
              entities (mapv (comp project db/entity) safe)
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
