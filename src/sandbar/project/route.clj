(ns sandbar.project.route
  "W1.ctx SPINE — the `:mm/Project ↔ :mm/Codebase ↔ :mm/Context` routing tie
  made REAL: `scope:project` routing resolution.

  `:mm.memory/scope` answers WHERE-does-this-route (`:global` / `:project`) and
  `:mm.memory/owning-project` is the ROUTING ANCHOR (the specific :mm/Project
  instance) — but until this ns NOTHING consumed the anchor for routing.  This
  resolver closes that gap: given a source memory (or a project directly), it
  resolves the LOGICAL routing decision —

    {:project-key       <:mm.project/ident>       ; the stable rename-proof key
     :sensitivity       :public | :private        ; project-effective-sensitivity
     :trust-scope       :trust-scope/public | [:trust-scope/private <key>]
     :contexts          #{<:mm/Context eid> ...}  ; runs-in-context set
     :corpus-repo       <string> | nil            ; the project's OWN corpus repo
     :routes-to-public? boolean}

  It consumes the ONE shared label-resolution core
  (`sandbar.firewall.label/project-effective-sensitivity`, W1.ctx §5 /
  W1.deploy §6.1) — never a divergent second label-resolver (central-guard
  discipline).  MOST-RESTRICTIVE composition is therefore enforced AT the wiring
  point: a project running in ANY private context resolves `:private` and its
  memories route to the project's own private scope, never the public bottom.

  ── SCOPE BOUNDARY (W1.ctx spine; W1.deploy is a LATER phase, OUT here) ──────
  This resolver stops at the LOGICAL trust-scope key + the corpus-repo STRING.
  The per-machine store-registry that maps that logical key → a PHYSICAL
  transactor endpoint / local disk path, the per-scope transactor processes,
  and the credential air-gap are all W1.deploy (physical topology) — NOT built
  here.  The git push that a route ultimately feeds is W1.F — NOT built here.

  Fail-closed by construction: an absent/unresolvable `owning-project` resolves
  to the `:project/UNASSIGNED` sentinel — `:private`, a private trust-scope,
  and `:routes-to-public? false` — so an unrouted memory can NEVER be sent to
  the public bottom.

  Spec: W1.ctx-mapping-table §3 (visible-projects membership authority),
  W1.B (the routing tie), arc-plan §1.2 (Context-is-compartment; :mm/Project a
  thin routing tie), W1.deploy §6.1 (project-level entry point)."
  (:require [datomic.api :as d]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.label :as label]))

;;; ===========================================================================
;;; Sentinels + resolution helpers
;;; ===========================================================================

(def unassigned-project-ident
  "The seeded `:mm/Project` sentinel an absent `:mm.memory/owning-project`
  resolves to (a sentinel VALUE, fail-closed :private — mm_project_schema §4)."
  :project/UNASSIGNED)

(defn- entity-of
  "The live entity named by any ref shape `ref-val` (eid / ident / entity /
  lookup-ref), `ref->eid`-normalized first so an ident-bearing ref never
  collapses to nil (S7 BU-0).  nil when it names no live entity."
  [db ref-val]
  (when-let [eid (ref/ref->eid db ref-val)]
    (d/entity db eid)))

(defn owning-project
  "The owning `:mm/Project` entity of memory entity `ent`.  Resolves
  `:mm.memory/owning-project` through the substrate ref canon; ABSENT (or
  unresolvable) ⇒ the `:project/UNASSIGNED` sentinel entity (the fail-closed
  routing anchor).  Returns nil ONLY if the schema has not seeded the sentinel
  (an un-loaded corpus)."
  [db ent]
  (or (entity-of db (:mm.memory/owning-project ent))
      (entity-of db unassigned-project-ident)))

(defn project-key
  "The stable `:mm.project/ident` routing key of project entity `proj`
  (`:db.unique/identity`, rename-proof), or `:project/UNASSIGNED` when the
  slot is absent (fail-closed key)."
  [proj]
  (or (:mm.project/ident proj) unassigned-project-ident))

;;; ===========================================================================
;;; LOGICAL trust-scope derivation (W1.deploy keys its PHYSICAL registry on this)
;;; ===========================================================================

(defn trust-scope-of
  "PURE.  The LOGICAL trust-scope for a resolved `sensitivity` + `project-key`.

    :public ⇒ :trust-scope/public                    ; the shared public bottom
    else    ⇒ [:trust-scope/private <project-key>]    ; a per-project private scope

  Fail-closed: only an explicit `:public` sensitivity yields the public scope;
  `:private`, nil, or any unknown value routes to the per-project private scope.
  This is the logical selector W1.deploy's per-machine store-registry keys its
  PHYSICAL transactor endpoint / disk path on — the physical topology is
  W1.deploy (OUT of the W1.ctx spine); this resolver stops at the logical key."
  [sensitivity project-key]
  (if (= :public sensitivity)
    :trust-scope/public
    [:trust-scope/private (or project-key unassigned-project-ident)]))

;;; ===========================================================================
;;; The routing decision
;;; ===========================================================================

(defn project-route
  "The routing decision for a :mm/Project entity `proj`, consuming the ONE
  shared core `label/project-effective-sensitivity` (most-restrictive 3-axis).

  `:corpus-repo` is the project's OWN `:mm.project/corpus-repo` string.  For a
  non-public (`:private`) project this is BY DATA its own private repo, never
  the public corpus repo (W1.deploy DEP-4 is the physical enforcement of that
  invariant; here the resolver simply surfaces the project's own string and
  `:routes-to-public? false`, so a private project can never be routed to the
  public bottom by this decision)."
  [db proj]
  (let [sens (label/project-effective-sensitivity db proj)
        key  (project-key proj)]
    {:project-key       key
     :sensitivity       sens
     :trust-scope       (trust-scope-of sens key)
     :contexts          (into #{}
                              (keep #(ref/ref->eid db %))
                              (let [raw (:mm.project/runs-in-context proj)]
                                (cond (nil? raw) nil (coll? raw) raw :else [raw])))
     :corpus-repo       (:mm.project/corpus-repo proj)
     :routes-to-public? (= :public sens)}))

(defn route-of
  "The routing decision for ANY source `ent` — a :mm/Memory (its
  `:mm.memory/owning-project` is resolved first) OR a :mm/Project directly.

  This is what makes `scope:project` REAL: a project-scoped memory routes to
  ITS OWNING PROJECT's scope + corpus-repo, not the global corpus.  Fail-closed:
  an absent/unresolvable owning-project routes to the `:project/UNASSIGNED`
  private scope (`:routes-to-public? false`) — an unrouted memory is never sent
  to the public bottom."
  [db ent]
  (let [cls (let [t (:dt/type ent)] (if (map? t) (:db/ident t) t))
        proj (if (= :mm/Project cls) ent (owning-project db ent))]
    (project-route db proj)))

;;; ===========================================================================
;;; Membership authority (W1.ctx §3 — corpus-canonical, NOT a gitignored registry)
;;; ===========================================================================

(defn visible-projects
  "The `:mm.context/visible-projects` set of context entity `ctx` — the
  corpus-canonical, git-tracked / PR-reviewable intra-context membership
  AUTHORITY (W1.ctx §3 / CTX-4).  Returns a set of `:mm/Project` eids.

  This is the membership authority routing + closure construction read — NOT a
  gitignored per-machine `contexts.edn` registry (that store-registry, W1.deploy,
  is the PHYSICAL path resolver, never the membership authority).  A project
  present ONLY in a registry but absent from `visible-projects` is NOT a member."
  [db ctx]
  (into #{}
        (keep #(ref/ref->eid db %))
        (let [raw (:mm.context/visible-projects ctx)]
          (cond (nil? raw) nil (coll? raw) raw :else [raw]))))

;;; ===========================================================================
;;; Context STRING-carrier resolution — best-effort + FAIL-CLOSED backstop
;;; (W1.ctx §1 item 3 / §4).  `:mm.context/cites` / `:mm.context/related` are
;;; STRING rel-path carriers (the carrier→ref re-mint is DEFERRED past 0.2.0);
;;; the citation-aware WALK that consumes this is W1.H (a LATER phase, OUT here)
;;; — the ctx wiring supplies the fail-closed RESOLUTION primitive it feeds.
;;; ===========================================================================

(defn resolve-carrier-label
  "Best-effort resolve a Context STRING-carrier value `carrier` (a
  `:mm.context/cites` / `:mm.context/related` rel-path) to the target's firewall
  LABEL, with a FAIL-CLOSED backstop (W1.ctx §4).  `resolve-fn` is an INJECTED
  best-effort rel-path → entity resolver (mirrors the enforce.clj injected-
  resolver discipline — the full mechanical rel-path→ref resolution is the
  DEFERRED-past-0.2.0 carrier re-mint, so it is injected, not hard-wired):

    resolvable   ⇒ `label/label-of` of the resolved entity (feed to W1.H's walk)
    UNRESOLVABLE ⇒ `label/unassigned-label` (:private, UNASSIGNED compartment)

  The fail-closed :private on unresolvable is the backstop that makes a
  toward-public crossing REFUSE rather than silently permit: a `:public`-source
  Context citing an unresolvable carrier hits `firewall-permits?(:public,
  :private)` = false.  This is a KNOWN SEAM, not a claimed-airtight boundary
  (W1.ctx §4): full citation-leak defense on Context string carriers is NOT
  mechanical for 0.2.0 and leans on the physical-exclusion backstop (W1.deploy).
  This primitive is what makes the seam FAIL-CLOSED rather than fail-open."
  [db carrier resolve-fn]
  (if-let [ent (when (and carrier resolve-fn) (resolve-fn carrier))]
    (label/label-of db ent)
    (label/unassigned-label db)))

(defn context-membership-sensitivity
  "The most-restrictive sensitivity over the project-level effective
  sensitivities of a context's `visible-projects` members — the label a
  W1.deploy closure-construction check (DEP-7, a LATER phase) would consume to
  refuse a label-incompatible membership.  Empty membership ⇒ `:public` neutral
  (a context with no members constrains nothing); each member is evaluated via
  the SAME shared core (`label/project-effective-sensitivity`), never a second
  resolver.  Surfaced here as the membership-authority read; the refuse-to-serve
  ENFORCEMENT is W1.deploy (OUT of the W1.ctx spine)."
  [db ctx]
  (label/most-restrictive
    (map (fn [proj-eid]
           (label/project-effective-sensitivity db (d/entity db proj-eid)))
         (visible-projects db ctx))))
