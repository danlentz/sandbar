(ns sandbar.deploy.closure
  "W1.deploy — the `db-firewall-closure` REFUSE-TO-SERVE invariant: the physical
  half of the firewall, made concrete as a post-build guard that refuses to
  serve a session whose DB contains an out-of-closure row (W1.deploy §3).

  ── WHY refuse, not filter (the security property) ─────────────────────────
  Every session DB is built bounded by its closure —
  `(⋃ scope.context.visible-projects) ∪ public` — and a post-build check
  asserts NO row owned outside that closure is present.  A public-scope
  session's DB is built WITHOUT any private row: the private row is ABSENT, not
  filtered.  That absence is what structurally closes the side channels
  (cross-scope aggregate / tag-histogram / zero-IDF BM25F / navigate) that a
  logical query-time filter leaks through — the row is not in the built DB, so
  no surface can reach it (arc-plan §4.1).  This guard is the BACKSTOP that
  refuses to serve if a row ever escaped the scope-bounded reconstruct: the
  degraded forms (query-filtering, report-only closure, shared transactor) are
  named gate-failures, reachable only by an explicit Dan-gated ADR (S7 E2).

  This is the standing physical lock the CA-6 write-side deferral rests on
  (two-lock collapse rule, W1.deploy §CA-6 obligation): if this refuse-to-serve
  invariant is weakened or bypassed, CA-6 reverts to fix-now.  Every check here
  is written to refuse-closed.

  ── CONSUMES the ONE shared label-resolution core (never re-derives) ────────
  DEP-7's per-member label check keys on
  `sandbar.firewall.label/project-effective-sensitivity` — the PROJECT-LEVEL
  entry point of the W1.ctx-built shared core (W1.ctx §5 / W1.deploy §6.1) — and
  membership is read via `sandbar.project.route/visible-projects` (the
  corpus-canonical authority, W1.ctx §3).  This ns builds NO second
  label-resolver; a divergent re-derivation here is the exact footgun route.clj
  deleted its DEP-7 scaffolding to avoid (route.clj tail note).

  ── REFUSE-TO-SERVE precedent ──────────────────────────────────────────────
  Mirrors `sandbar.projection/guard-registry-critical-write!` (projection.clj
  §235): a single shared refusal primitive, marker-tagged ex-info
  (`:sandbar/error`), thrown at the guarded boundary so a bring-up lets it abort
  the session rather than degrade it.

  ── WIRING (this increment builds the CHECK; live-boot insertion is DEFERRED) ─
  `assert-serves!` is the wiring-point function a session-build / DatomicPeer
  start WOULD call.  It is NOT yet inserted into the live boot path: the live
  corpus is UNASSIGNED-heavy and its public closure is not yet fully stamped, so
  wiring this into live boot today would (correctly, fail-closed) REFUSE to serve
  the live store.  Live insertion composes with the W1.G scope-bounded
  reconstruct + a fully-stamped public-bottom closure — an explicit `what
  remains` item.  This round exercises the guard only against datomic:mem
  fixtures.

  Spec: W1.deploy-topology.md §3 (the invariant), §6 DEP-2 (fault-injection
  refuse-to-serve), §6.1 DEP-7 (closure label-compatibility), DEP-8 (closure
  scope-identity / private↔private reciprocity)."
  (:require [datomic.api :as d]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.label :as label]
            [sandbar.project.route :as route]))

;;; ===========================================================================
;;; The public-bottom Context (the ∪ public leg's anchor)
;;; ===========================================================================

(defn public-bottom-context
  "The eid of the single Context stamped `:mm.context/firewall-class
  :public-bottom` (ceremony-8 Decision-1) — the public leg's anchor.  Returns:

    • the eid                       when EXACTLY ONE public bottom is stamped;
    • nil                           when NONE is (a pre-stamp / un-migrated
                                    corpus) — the caller then builds a
                                    public-leg-less, fail-closed closure;
    • THROWS `:sandbar/error :ambiguous-public-bottom` when MORE THAN ONE is
      stamped — two public roots is a confidentiality integrity failure (which
      one bounds `∪ public`?), so it refuses rather than pick.

  Read via the firewall-class DESIGNATION alone (not a visibility-composed
  label), mirroring label.clj's `:tie-designation` precedent: the public-bottom
  co-load floor keys on the stamp, not the composed sensitivity."
  [db]
  (let [eids (map first
                  (d/q '[:find ?c
                         :where [?c :mm.context/firewall-class :public-bottom]]
                       db))]
    (cond
      (empty? eids)       nil
      (= 1 (count eids))  (first eids)
      :else               (throw (ex-info (str "more than one :public-bottom "
                                               "context is stamped; refusing "
                                               "(ambiguous public root)")
                                          {:sandbar/error :ambiguous-public-bottom
                                           :public-bottom-eids (vec eids)})))))

;;; ===========================================================================
;;; Scope sensitivity + the DEP-7 label-compatibility relation
;;; ===========================================================================

(defn scope-context-sensitivity
  "The `:public`/`:private` sensitivity of a SCOPE'S context entity `scope-ctx`
  — derived from its `:mm.context/firewall-class` via the shared table
  (`label/sensitivity-of-firewall-class`, fail-closed :private).  trust-scope is
  DERIVED from this value (W1.deploy §1); keyed on the firewall-class stamp, not
  a visibility-composed label (the `:tie-designation` precedent)."
  [scope-ctx]
  (label/sensitivity-of-firewall-class (:mm.context/firewall-class scope-ctx)))

(defn label-compatible?
  "DEP-7 relation: may a member project whose project-level effective
  sensitivity is `member-sens` appear in the `visible-projects` of a scope whose
  sensitivity is `scope-sens`?

    scope-sens :public   ⇒ ONLY member-sens :public   (a :private-resolving
                          project in a PUBLIC scope's visible-projects is a
                          label-incompatible membership — the poisoned/stale
                          membership cross-scope leak DEP-7 closes);
    scope-sens :private  ⇒ any member                 (a private scope's own
                          compartment admits private members; the public leg is
                          validated separately).

  PURE.  Fail-closed: an unrecognized `scope-sens` is treated as `:private`'s
  permissive branch ONLY for its own members — the public leg is always checked
  against an explicit `:public` scope-sens, so nothing widens a public closure."
  [member-sens scope-sens]
  (if (= :public scope-sens)
    (= :public member-sens)
    true))

;;; ===========================================================================
;;; Closure construction — DEP-7 + DEP-8 refuse-points (BEFORE ingest)
;;; ===========================================================================

(defn- check-label-compat!
  "DEP-7 refuse-at-construction: assert every project eid in `members` is
  `label-compatible?` with `scope-sens`, evaluated via the shared core
  `label/project-effective-sensitivity`.  THROWS `:sandbar/error
  :closure-label-incompatible` naming every violator; returns nil on pass.
  Evaluated at closure-construction time, BEFORE ingest (the DEP-2 primitive
  moved early), so a label-incompatible member never even bounds the build."
  [db scope-sens members]
  (when-let [bad (seq (for [p     members
                            :let  [ent  (d/entity db p)
                                   sens (label/project-effective-sensitivity db ent)]
                            :when (not (label-compatible? sens scope-sens))]
                        {:project p
                         :project-ident (:mm.project/ident ent)
                         :member-sensitivity sens}))]
    (throw (ex-info (str "a label-incompatible project is in the scope's "
                         "visible-projects; refusing the build (DEP-7)")
                    {:sandbar/error    :closure-label-incompatible
                     :scope-sensitivity scope-sens
                     :incompatible     (vec bad)}))))

(defn- check-scope-identity!
  "DEP-8 refuse-at-construction: assert every project eid in `members` (declared
  as `scope-ctx-eid`'s `visible-projects`) RECIPROCATES the scope identity —
  `scope-ctx-eid ∈ project-context-eids(P)`.  `visible-projects` is the DECLARED
  inverse of `runs-in-context`; this VERIFIES the inverse rather than trusting
  it, closing the cross-private (P3) path where a poisoned/stale
  `visible-projects` on context C1 names a project that actually belongs to a
  different private context C2 (both :private, so DEP-7's label check passes,
  yet C2's rows would ingest into C1's DB).  THROWS `:sandbar/error
  :closure-scope-identity-violation` naming every non-reciprocating member;
  returns nil on pass."
  [db scope-ctx-eid members]
  (when-let [bad (seq (for [p     members
                            :let  [ctxs (label/project-context-eids db (d/entity db p))]
                            :when (not (contains? ctxs scope-ctx-eid))]
                        {:project p
                         :project-ident (:mm.project/ident (d/entity db p))
                         :runs-in-context (vec ctxs)}))]
    (throw (ex-info (str "a visible-projects member does not reciprocate the "
                         "scope's runs-in-context edge; refusing the build "
                         "(DEP-8 scope identity)")
                    {:sandbar/error   :closure-scope-identity-violation
                     :scope-context   scope-ctx-eid
                     :non-reciprocating (vec bad)}))))

(defn closure-of
  "Construct the `db-firewall-closure` for the scope whose context is
  `scope-ctx-eid` — the SET of owning-project eids a session bound to that scope
  may serve: `(⋃ scope.visible-projects) ∪ public` (W1.deploy §3).

  Enforces the two closure-construction refuse-points BEFORE returning (so an
  ill-formed closure never bounds an ingest):
    • DEP-8 scope-identity/reciprocity over the scope's own members
      (`check-scope-identity!`);
    • DEP-7 label-compatibility of the scope's own members against the scope
      sensitivity (`check-label-compat!`).
  The `∪ public` leg is the public-bottom context's `visible-projects`, itself
  re-validated as ALL-`:public` + reciprocal (a poisoned public bottom must
  refuse even when a PRIVATE scope's build pulls in the public leg).  For the
  public scope itself `scope-ctx-eid` IS the public bottom, so the union is
  idempotent.  A corpus with NO stamped public bottom yields a public-leg-less
  closure (fail-closed: nothing is admitted by an unstamped public root).

  Returns the closure set of project eids.  THROWS the DEP-7/DEP-8 markers on a
  poisoned/stale membership."
  [db scope-ctx-eid]
  (let [scope-ctx  (d/entity db scope-ctx-eid)
        scope-sens (scope-context-sensitivity scope-ctx)
        members    (route/visible-projects db scope-ctx)]
    (check-scope-identity! db scope-ctx-eid members)
    (check-label-compat!   db scope-sens    members)
    (let [pub-ctx-eid (public-bottom-context db)
          pub-members (when (and pub-ctx-eid (not= pub-ctx-eid scope-ctx-eid))
                        (let [pms (route/visible-projects db (d/entity db pub-ctx-eid))]
                          ;; The public leg's OWN integrity — a poisoned public
                          ;; bottom refuses even for a private scope's build.
                          (check-scope-identity! db pub-ctx-eid pms)
                          (check-label-compat!   db :public     pms)
                          pms))]
      (into (set members) pub-members))))

;;; ===========================================================================
;;; POST-BUILD closure check — DEP-2 refuse-to-serve
;;; ===========================================================================

(defn owning-project-rows
  "Every `[row-eid owning-project-eid]` pair in `db` — one per asserted
  `:mm.memory/owning-project` datom.  The row-set the closure check ranges over:
  a row is IN-closure iff its owning project is in the closure.  (Rows with NO
  owning-project — schema/type/tag substrate, bare contexts — are not
  project-owned CONTENT and are out of this check's scope; hardening that seam
  is a `what remains` item.)"
  [db]
  (d/q '[:find ?e ?p
         :where [?e :mm.memory/owning-project ?p]]
       db))

(defn out-of-closure-rows
  "The seq of `{:row … :owning-project …}` in `db` whose owning project is NOT
  in `closure` — the rows that make a build unservable for the scope `closure`
  bounds.  PURE over the passed row-set (defaults to `owning-project-rows`), so
  a fault-injection test can pass a synthesized row-set.  Empty ⇒ the build is
  within closure."
  ([db closure] (out-of-closure-rows db closure (owning-project-rows db)))
  ([_db closure rows]
   (for [[e p] rows
         :when (not (contains? closure p))]
     {:row e :owning-project p})))

(defn guard-session-db-closure!
  "The POST-BUILD DEP-2 refuse-to-serve check.  If `db` contains ANY row whose
  owning project is outside `closure`, THROW `:sandbar/error
  :db-firewall-closure-violation` (naming the offending rows) — REFUSE TO SERVE
  the session, NOT filter the row.  Returns nil (serve) when every row is
  in-closure.  This is the backstop the scope-bounded reconstruct (W1.G) sits
  in front of: absence is the primary mechanism; this refuses to serve if
  absence ever failed.  Mirrors `guard-registry-critical-write!`."
  [db closure]
  (when-let [violations (seq (out-of-closure-rows db closure))]
    (throw (ex-info (str "session DB contains " (count violations) " row(s) "
                         "owned outside the scope closure; refusing to serve")
                    {:sandbar/error       :db-firewall-closure-violation
                     :closure-project-count (count closure)
                     :out-of-closure-rows (vec (take 50 violations))
                     :out-of-closure-total (count violations)})))
  nil)

(defn assert-serves!
  "The WIRING-POINT composite: construct the closure for the scope whose context
  is `scope-ctx-eid` (DEP-7/DEP-8 refuse at construction) then run the
  POST-BUILD DEP-2 closure check over `db`.  Returns `db` unchanged on success
  (so a caller can thread `(-> (build-session-db …) (assert-serves! ctx))`);
  throws the first marker-tagged violation otherwise.

  This is the function a session-build / DatomicPeer start WOULD call to enforce
  refuse-to-serve.  It is NOT wired into the live boot path this round — see the
  ns docstring's WIRING note: live insertion composes with W1.G's scope-bounded
  reconstruct + a fully-stamped closure and is an explicit `what remains` item."
  [db scope-ctx-eid]
  (guard-session-db-closure! db (closure-of db scope-ctx-eid))
  db)
