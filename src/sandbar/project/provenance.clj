(ns sandbar.project.provenance
  "W1.E — the export/projection-run PROVENANCE RECORDER: the per-run `:mm/Run`
  PROV-O record (fork-3), the per-run MANIFEST (with the G4 audience-split), and
  the emitter touchpoint that mints one run per export.

  ── Fork-3 (Dan re-ruled 2026-07-08) ────────────────────────────────────────
  Export/projection runs REUSE the existing concrete `:mm/Run` `:mm/Activity`
  subclass + a `:projection` DISCRIMINATOR carried on the ceremony-8-minted
  `:mm.activity/activity-type` slot — NO `:mm/ProjectionRun` subclass, NO
  net-new type, NO net-new slot, NO additive schema mint (maximal reuse; the
  same instinct Dan applied to `:mm/Project`).  A \"projection run\" is queried
  as `:mm/Run` filtered on `:mm.activity/activity-type = :projection`.
  (`memory/decisions/dan_rules_export_runs_reuse_mm_run_plus_discriminator_...`.)

  Two surfaces (W1.E §2/§3 — do NOT conflate):

    (1) the `:mm/Run` ENTITY  — the PROV-O subject.  agent (wasAssociatedWith,
        ref→:mm/Actor), generated (wasGeneratedBy, refs→:mm/Memory produced),
        used (refs→:mm/Memory consumed), started/ended-at, status, +
        `:mm.activity/activity-type :projection`; visibility-from/to for
        promotion runs.  Built by `projection-run-spec`.
    (2) the MANIFEST  — a plain EDN map (NOT a schema entity): the machine-
        readable index W1.F commits + W1.G's newer-DB guard reads.  Carries the
        :manifest/basis-t <long> + :manifest/file-set [<rel-path>…] the :mm/Run
        entity does NOT (its generated/used are memorial REFS, not paths/basis-t).
        Built by `export-manifest`; assembled from an export by `manifest-for-export`.

  ── CODEX-1 fix (manifest forgery, held-note travelling requirement) ─────────
  `manifest-for-export` DERIVES `:manifest/firewall-class` from the DB-resolved
  project via the shared label core (`sandbar.project.route/route-of`, which
  composes `sandbar.firewall.label/project-effective-sensitivity`), NOT from a
  caller-supplied route map.  The held draft trusted `(:sensitivity route)`, so a
  forged `{:sensitivity :public}` could stamp a private export `:public`.  The
  audit-integrity property is now structural: the recorded firewall-class is
  recomputed from the same DB the routing/enforcement reads — the caller has
  nothing to forge.  NB the `project.export` path itself applies NO content
  filter yet (the G4/H exclusion filter is W1.H, unbuilt); until it lands, the
  recorded firewall-class is the DERIVED target class AND the manifest boundary
  ITSELF enforces class/content consistency by REFUSING a written-set that does
  not resolve to that class (see the whole-file-set gate below) — so the class
  the manifest records is the class its file-set is verified to satisfy.

  ── WHOLE-MANIFEST class/content consistency (P-CITE-2 over the file-set) ────
  The audience-split scrubs the exclusions/redactions ENUMERATION, but a private
  identifier can also ride the `:manifest/file-set` itself — a private-project
  row smuggled into a `:public`-derived export.  So the manifest boundary
  ADDITIONALLY VERIFIES the whole written set against the DERIVED route via the
  shared label core (`verify-written-against-route!`): a `:public`-derived
  target REFUSES (throws a marker-tagged `:sandbar/error`, no committed
  manifest) if ANY written row resolves non-`:public`; a `:private`-derived
  target refuses any row whose trust-scope is neither its own nor `:public`.
  REFUSE-not-filter (mirrors `sandbar.projection/guard-registry-critical-write!`
  / the S7 refuse-at-boundary precedent): a mis-scoped export must ABORT the
  future W1.F commit path loudly, not silently drop the offending rows.

  Each row is routed EXACTLY when it carries its source entity: `project-graph`
  stamps every written row with `:entity` (the projected entity's routing
  descriptor), so the gate routes THAT entity rather than re-resolving the
  rel-path — closing the R3-flagged fail-open where a rel-path shared by a
  `:public` and a `:private` entity resolved to the public twin while the private
  twin's file was what landed.  A row WITHOUT `:entity` (hand-built / legacy)
  fail-closes over the SET of ALL entities carrying its rel-path (admitted only
  if EVERY one is admissible); a rel-path resolving to zero live entities routes
  `:private`.

  RESIDUAL (not an absolute): on the wired emitter path this gate runs AFTER the
  thunk has written the file-set to disk, so it keeps a COMMITTED (W1.F-published)
  manifest class-consistent but does NOT unwrite refused files at a scratch `:to`
  — the on-disk content filter is W1.H, the newer-DB restore guard W1.G.

  ── G4 audience-split (E-4 / R3-1 / W1.H P-CITE-2) ──────────────────────────
  A COMMITTED manifest for a `:public`-target run MUST NOT carry any private
  entity identifier: its exclusions/redactions collapse to NON-LINKABLE forms
  (a count + a per-run-SALTED digest, the salt discarded) plus an OPAQUE
  run-scoped `:manifest/audit-ref` (the run's own `:mm/id` UUID — resolvable to
  the audit-side `:mm/Run`, never name-bearing).  Because the salt is DISCARDED,
  the digest is NOT independently auditor-verifiable — it is a NON-LINKABLE
  opaque form (it hides the count-preserving shape + defeats cross-run
  correlation), NOT an attestation; the AUTHORITATIVE verification rides the
  audit-side EXACT record (the `:audit` value `manifest-for-export` returns
  alongside `:committed`, keyed by the same opaque ref).  A `:private`-target
  run MAY commit the exact enumeration (committed manifest + audit ledger
  co-reside in its private repo).  The scrub is applied at the lowest level
  (`export-manifest`), so a `:public` committed manifest carrying an exact
  private id IN ITS EXCLUSIONS/REDACTIONS ENUMERATION is unconstructible by
  construction.  (The other channel a private id could ride — the
  `:manifest/file-set` — is closed by the whole-manifest gate above, which
  REFUSES rather than scrubs; that gate is a boundary refusal, not an
  unconstructible-by-construction property.)

  ── AUDIT-LEDGER persistence (E-4(a) — DEFERRED E/F/G seam) ──────────────────
  `manifest-for-export` RETURNS the audit-side EXACT enumeration (`:audit`,
  keyed by the run's `:mm/id`).  Persisting that enumeration as slots on a
  private audit sink is the W1.H/E-F-G seam and is NOT built here (no-new-schema
  fork-3 + W1.H produces no real exclusions yet — today the export path applies
  NO filter).  What IS load-bearing now: `:manifest/audit-ref` is the run's
  `:mm/id`, so it resolves to the persisted (`:db-only`) `:mm/Run` rather than
  pointing at nothing (the auditor looks the run up by `[:mm/id audit-ref]`).

  Spec: W1.E-manifest-contract §2/§3 (fork-3 amended), arc-plan W1.E + G4,
  HELD-NOTE.md (CODEX-1 travelling requirement)."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.ref :as ref]
            [sandbar.project.route :as route]))

;;; ===========================================================================
;;; The fork-3 discriminator
;;; ===========================================================================

(def projection-activity-type
  "The fork-3 `:mm.activity/activity-type` VALUE that marks a `:mm/Run` as an
  export/projection run (vs. the `:declassification` graduation value the slot
  doc names, or an ordinary scheduler run with no activity-type).  An additive
  VALUE on the open keyword slot — NOT a schema change."
  :projection)

(defn projection-run?
  "Is `run-ent` a projection run — a `:mm/Run` whose `:mm.activity/activity-type`
  is `:projection`?  The fork-3 query predicate (`:mm/Run` + discriminator),
  replacing the rejected named-subclass membership test."
  [run-ent]
  (= projection-activity-type (:mm.activity/activity-type run-ent)))

;;; ===========================================================================
;;; (1) The :mm/Run entity spec (the PROV-O subject)
;;; ===========================================================================

(defn projection-run-spec
  "PURE.  The `:mm/Run` entity-spec for an export/projection run (fork-3), with
  the `:mm.activity/activity-type :projection` discriminator + the PROV-O tuple:

    :agent            ref → :mm/Actor            (prov:wasAssociatedWith)
    :generated        coll of refs → :mm/Memory  (prov:wasGeneratedBy — produced)
    :used             coll of refs → :mm/Memory  (prov:used — consumed)
    :started-at       inst                       (prov:startedAtTime)
    :ended-at         inst                       (prov:endedAtTime)
    :status           kw (default :succeeded)    (:mm.activity/status)
    :name             string                     (:mm.memory/name, for FS-first-class runs)
    :visibility-from  kw / :visibility-to kw      (declassification/promotion runs only)

  The basis-t (<long>) and the projected file-set (rel-path strings) are NOT on
  this entity — they live on the MANIFEST (`export-manifest`), because the
  entity's generated/used are memorial REFS, not paths/basis-t.

  `:id` (a UUID) is stored on the inherited `:db.unique/identity` `:mm/id` slot
  — `dt/make` does NOT auto-mint `:mm/id` for a `:mm/Run`, so the recorder
  supplies one so the manifest's `:manifest/run` can be a REBUILD-STABLE
  `[:mm/id …]` lookup-ref rather than a volatile `:db/id` eid."
  [{:keys [id agent generated used started-at ended-at status name
           visibility-from visibility-to]
    :or   {status :succeeded}}]
  (cond-> {:dt/type                   :mm/Run
           :mm.activity/activity-type projection-activity-type
           :mm.activity/status        status}
    id               (assoc :mm/id                    id)
    name             (assoc :mm.memory/name           name)
    agent            (assoc :mm.activity/agent        agent)
    (seq generated)  (assoc :mm.activity/generated    (vec generated))
    (seq used)       (assoc :mm.activity/used         (vec used))
    started-at       (assoc :mm.activity/started-at   started-at)
    ended-at         (assoc :mm.activity/ended-at     ended-at)
    visibility-from  (assoc :mm.activity/visibility-from visibility-from)
    visibility-to    (assoc :mm.activity/visibility-to   visibility-to)))

(defn record-projection-run!
  "Persist a projection-run `:mm/Run` from `opts` (see `projection-run-spec`)
  via the substrate write primitive (`dt/make`), returning the created entity.

  The recorded run is `:dt/memorial-policy :db-only` (schema §659-670) — it is
  NEVER FS-projected or git-pushed, so it is an AUDIT-SIDE record by
  construction (the G4 investigability surface), not a leak surface.  Its
  PROV-O edges (`:mm.activity/generated`/`used`/`agent`) are NON-governed slots
  (firewall `governed-flow/carrier/tie-slots` cover `:mm.memory/*` citations +
  the tie legs only), so recording a run that references private memories is
  firewall-clean — the recorder MUST be able to name what was withheld (G4)."
  [opts]
  (dt/make :mm/Run (projection-run-spec opts)))

(defn projection-runs
  "All projection runs under `db` — every `:mm/Run` whose
  `:mm.activity/activity-type` is `:projection` (the fork-3 query: `:mm/Run` +
  discriminator, ONE predicate, no new class).  Returns a seq of entities."
  [db]
  (->> (d/q '[:find [?e ...]
              :in $ ?atype
              :where
              [?e :dt/type :mm/Run]
              [?e :mm.activity/activity-type ?atype]]
            db projection-activity-type)
       (map #(d/entity db %))))

;;; ===========================================================================
;;; (2a) The G4 audience-split primitives (E-4 / R3-1 / R4-2)
;;; ===========================================================================

(defn- per-run-salt
  "A fresh per-run salt (a random UUID string).  Used ONCE to compute the
  public-manifest digest, then DISCARDED (never stored on the manifest or the
  audit record), so public digests are not cross-run-linkable (R3-1)."
  []
  (str (java.util.UUID/randomUUID)))

(defn- salted-digest
  "The hex SHA-256 of `salt` ++ the canonical `pr-str` of `rows` — a
  NON-LINKABLE opaque form of a withheld-entity enumeration, carrying NO entity
  identifier.  Because `salt` is per-run and DISCARDED, the digest is NOT
  independently verifiable by an auditor (no one can recompute it — the salt is
  gone), so it is NOT an attestation: its ONLY properties are (a) it leaks no
  identifier and (b) it is not comparable across runs (defeats cross-run
  correlation).  The AUTHORITATIVE record of exactly-what-was-withheld is the
  audit-side EXACT enumeration (`:audit`), NOT this digest."
  [salt rows]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str salt (pr-str rows)) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn- non-linkable-form
  "The NON-LINKABLE public-committed form of an exact withheld-entity `rows`
  enumeration: `{:count <n> :digest <hex>}` (or nil for an empty enumeration).
  Carries NO `:entity` id / slug / namespaced private ref — only a count and a
  per-run-salted (hence non-auditor-verifiable, non-cross-run-linkable) digest
  (R3-1).  The AUTHORITATIVE exact rows live audit-side (`:audit`)."
  [salt rows]
  (when (seq rows)
    {:count  (count rows)
     :digest (salted-digest salt rows)}))

;;; ===========================================================================
;;; (2b) The manifest shape (W1.E §2) + the emitter touchpoint
;;; ===========================================================================

(defn export-manifest
  "PURE.  The per-run COMMITTED MANIFEST map (W1.E §2) — the machine-readable
  index W1.F commits into the run's TARGET repo and W1.G's newer-DB guard reads.

  The G4 AUDIENCE-SPLIT is applied HERE (the lowest level) keyed on
  `:firewall-class`, so a `:public` committed manifest carrying an exact private
  identifier IN ITS EXCLUSIONS/REDACTIONS is UNCONSTRUCTIBLE (the `:manifest/file-
  set` channel is closed separately by the whole-manifest gate, which REFUSES):

    :public target ⇒ `:manifest/exclusions`/`:manifest/redactions` collapse to
                     the non-linkable `{:count :digest}` form (salted via
                     `:salt`), and `:manifest/audit-ref` MUST be the opaque
                     run-scoped id (a per-run UUID — the caller supplies it).
    :private target ⇒ the EXACT `[{:entity <id> …} …]` rows are committed
                      directly (committed manifest + audit ledger co-reside).

  Fields (`:or` shown):

    :run            <ref>       the :mm/Run entity id (§1), :projection-disc.
    :basis-t        <long>      DB basis-t at run time (W1.G reads THIS)
    :started-at / :ended-at / :agent
    :target         {:project <key> :corpus-repo <string> :trust-scope <kw>}
    :firewall-class <kw>        the filter APPLIED = the scope's effective
                                sensitivity (the audience-split key)
    :file-set       [<rel-path> …]   the projected file-set
    :corpus-sha     <string>    the git tree/commit sha W1.F produced
    :exclusions     [{:entity <id> :reason <kw>} …]        (EXACT; split by class)
    :redactions     [{:entity <id> :fields [<kw>…] :reason <kw>} …] (EXACT; split)
    :audit-ref      <opaque>    points at the audit-side exact enumeration
    :authorship-flag <boolean>  §1.3 truth 1 (caller-supplied; W1.H fills it)
    :salt           <string>    per-run salt for the :public digest (NOT stored)
    :status         #{:complete :refused :partial}"
  [{:keys [run basis-t started-at ended-at agent target firewall-class
           file-set corpus-sha exclusions redactions audit-ref authorship-flag
           salt status]
    :or   {status :complete}}]
  (let [public?    (= :public firewall-class)
        excl       (if public? (non-linkable-form salt exclusions) (when (seq exclusions) (vec exclusions)))
        redact     (if public? (non-linkable-form salt redactions) (when (seq redactions) (vec redactions)))]
    (cond-> {:manifest/run            run
             :manifest/basis-t        basis-t
             :manifest/target         target
             :manifest/firewall-class firewall-class
             :manifest/file-set       (vec file-set)
             :manifest/status         status}
      started-at       (assoc :manifest/started-at      started-at)
      ended-at         (assoc :manifest/ended-at        ended-at)
      agent            (assoc :manifest/agent           agent)
      corpus-sha       (assoc :manifest/corpus-sha      corpus-sha)
      excl             (assoc :manifest/exclusions      excl)
      redact           (assoc :manifest/redactions      redact)
      audit-ref        (assoc :manifest/audit-ref       audit-ref)
      (some? authorship-flag) (assoc :manifest/authorship-flag (boolean authorship-flag)))))

(defn- verified-route
  "Re-derive the routing decision for `project` (any ref shape) from `db` via
  the shared label core (`route/route-of` over the DB-resolved project entity).
  The CODEX-1 forgery fix: the sensitivity the manifest records is COMPUTED from
  the live DB, not read off a caller-supplied map.  Fail-closed — an
  unresolvable project resolves through `route-of`'s UNASSIGNED leg to
  `:private` / a private trust-scope, so a bogus project can never yield a
  `:public` firewall-class."
  [db project]
  (let [proj-ent (when-let [eid (ref/ref->eid db project)] (d/entity db eid))]
    (route/route-of db proj-ent)))

;;; ===========================================================================
;;; (2c) The whole-manifest class/content-consistency gate (P-CITE-2 over the
;;;      WHOLE file-set — REFUSE, not filter).  A `:public`-derived export whose
;;;      written set contains a private-project row (or any unresolvable one)
;;;      must ABORT loudly, mirroring guard-registry-critical-write!'s
;;;      refuse-at-boundary precedent; a `:private`-derived export refuses a
;;;      FOREIGN private scope.  The manifest boundary itself enforces that the
;;;      recorded firewall-class equals the sensitivity of the content — until
;;;      W1.H/W1.F land, the export path applies NO filter, so THIS check is what
;;;      keeps a committed manifest class-consistent.
;;;
;;;      ROW ROUTING — EXACT when the row carries its source entity, else
;;;      COLLISION-SAFE.  `sandbar.projection/project-graph` stamps each written
;;;      row with `:entity` (a routing descriptor of the ACTUAL projected
;;;      entity), so the gate routes THAT entity — never re-resolving a rel-path,
;;;      which is NOT unique and could resolve to a `:public` twin while a
;;;      colliding `:private` twin's file is what actually landed (the fail-open
;;;      the R3 board flagged).  A row WITHOUT `:entity` (hand-built / legacy) is
;;;      resolved over the SET of ALL live entities carrying its rel-path and is
;;;      admitted only if EVERY one is admissible (fail-closed on zero matches
;;;      and on ANY inadmissible candidate).
;;; ===========================================================================

(defn- rel-path->entities
  "EVERY live entity whose `:mm.memory/rel-path` is `rel-path` — a collision-safe
  SET find (`:find [?e ...]`, NOT a scalar `:find ?e .`).  The FALLBACK resolver
  for a written row carrying no source `:entity`: a rel-path is NOT unique (two
  differently-scoped memories can share one — confirmed), so resolving to ONE
  arbitrary entity could pick a `:public` twin and fail open on a colliding
  `:private` twin.  Queries the `:mm.memory/rel-path` SLOT directly (not a derived
  ident) so it resolves regardless of how each entity's `:db/ident` was minted.
  Empty ⇒ the caller fail-closes the row to the UNASSIGNED `:private` route (an
  unresolvable exported path is never assumed public)."
  [db rel-path]
  (when rel-path
    (map #(d/entity db %)
         (d/q '[:find [?e ...] :in $ ?rp :where [?e :mm.memory/rel-path ?rp]]
              db rel-path))))

(defn- row-source-routes
  "The route(s) whose admissibility governs a written `row` — a VECTOR (usually
  one).

  PREFERRED (exact): the row carries its SOURCE entity descriptor under `:entity`
  (stamped by `sandbar.projection/project-graph`) — route THAT actual projected
  entity, ONE route, no rel-path re-resolution.  `route/route-of` over the carried
  descriptor equals route-of over the live entity (same two routing slots, then
  owning-project resolved via db), so this is exact without a db round-trip on the
  descriptor's identity.

  FALLBACK (collision-safe): a row WITHOUT `:entity` (hand-built / legacy) is
  resolved by rel-path to ALL live entities carrying it (`rel-path->entities`) —
  each contributes a route, and the row is admitted only if EVERY one is
  admissible (see `verify-written-against-route!`).  Zero matches ⇒ the single
  UNASSIGNED `:private` route (`route-of` of nil), so an unresolvable path
  fail-closes."
  [db row]
  (if (contains? row :entity)
    [(route/route-of db (:entity row))]
    (if-let [ents (seq (rel-path->entities db (:rel-path row)))]
      (mapv #(route/route-of db %) ents)
      [(route/route-of db nil)])))

(defn- route-admissible?
  "Is a resolved `row-route` admissible into a target whose trust-scope is
  `target-scope`?  A route rides iff it is PUBLIC (a subset of every scope) OR is
  the SAME private scope as the target.  A foreign private scope — including the
  fail-closed UNASSIGNED route — is INADMISSIBLE.  For a `:public` target
  (`target-scope` `:trust-scope/public`) this reduces to \"the route is public\";
  a `:private` target additionally admits its own private rows."
  [target-scope row-route]
  (or (= :public (:sensitivity row-route))
      (= target-scope (:trust-scope row-route))))

(defn verify-written-against-route!
  "REFUSE (throw a marker-tagged `:sandbar/error`) when any `written` row is
  INADMISSIBLE into `route` — the DERIVED target route.  This is the manifest
  boundary's class/content-consistency gate: it binds the whole `:manifest/file-
  set` to the derived firewall-class so a `:public`-derived export cannot carry a
  private-project row (P-CITE-2 over the file-set) and a `:private`-derived export
  cannot carry a FOREIGN private scope's rows.  REFUSE-not-filter (mirrors
  `sandbar.projection/guard-registry-critical-write!`): a mis-scoped export ABORTS
  the manifest assembly (and the future W1.F commit path) loudly, naming each
  offending (rel-path, resolved sensitivity/trust-scope) pair, rather than
  silently dropping rows.

  Each row is routed by `row-source-routes`: EXACT when the row carries its source
  `:entity` (project-graph rows), else COLLISION-SAFE over the SET of all entities
  at its rel-path — admitted only if EVERY candidate is admissible, so an
  ambiguous rel-path surfaces ONE offender entry per inadmissible candidate.
  Fail-closed — a rel-path resolving to zero live entities routes `:private` (the
  UNASSIGNED leg of `route-of`).  Returns nil when every row is admissible.

  RESIDUAL (wired path): on the emitter touchpoint this gate runs AFTER the
  projection thunk has already written the file-set to `:to` (see
  `with-export-provenance`) — it aborts the manifest + `:succeeded` run + the
  future W1.F commit path, NOT the on-disk files.  The on-disk content filter is
  W1.H; the newer-DB restore guard is W1.G.  So a COMMITTED (published) manifest
  is never class-inconsistent, but a scratch `:to` may hold refused files until
  W1.G/W1.H land."
  [db route written]
  (let [target-scope (:trust-scope route)
        public?      (= :public (:sensitivity route))
        offenders    (into []
                           (mapcat
                             (fn [row]
                               (->> (row-source-routes db row)
                                    (remove #(route-admissible? target-scope %))
                                    (map (fn [rr]
                                           {:rel-path    (:rel-path row)
                                            :sensitivity (:sensitivity rr)
                                            :trust-scope (:trust-scope rr)})))))
                           written)]
    (when (seq offenders)
      (throw (ex-info (str "export written-set contains rows that do not resolve "
                           "to the derived target firewall-class; refusing to "
                           "build a class-inconsistent manifest")
                      {:sandbar/error         (if public?
                                                :public-manifest-contains-private-rows
                                                :private-manifest-contains-foreign-rows)
                       :target-firewall-class (:sensitivity route)
                       :target-trust-scope    target-scope
                       :offending-rows        offenders})))
    nil))

(defn manifest-for-export
  "Emitter TOUCHPOINT — assemble BOTH the COMMITTED manifest and the audit-side
  exact record for a completed export, returning
  `{:committed <manifest> :audit <record>}`.

    :run        the recorded :mm/Run entity id/ref (from `record-projection-run!`)
    :project    the routing anchor — a :mm/Project ref (eid/ident/entity); the
                firewall-class is RE-DERIVED from THIS via the DB, never trusted
                from the caller (CODEX-1 fix)
    :written    the `sandbar.projection/project-graph` result
                ([{:rel-path \"…\" :written true :entity <descriptor>} …]) —
                supplies :manifest/file-set; each row's `:entity` (a source
                routing descriptor) is what the whole-file-set gate routes
                EXACTLY (a row lacking it fail-closes over all entities at its
                rel-path — see `verify-written-against-route!`)
    :basis-t    the DB basis-t at run time
    :exclusions / :redactions   the EXACT G4 enumeration (audience-split applied)
    :audit-ref  the OPAQUE run-scoped id ties the committed manifest to the
                audit-side exact record — the caller passes the run's own
                `:mm/id` (so the ref RESOLVES to the persisted `:mm/Run`);
                absent ⇒ a fresh per-run UUID (the direct-call case).
    :started-at :ended-at :agent :corpus-sha :authorship-flag  — optional PROV fields

  The `:firewall-class`, `:target` project-key/corpus-repo/trust-scope ALL come
  from the VERIFIED route (DB-derived), never a caller map.  BEFORE assembling
  anything it VERIFIES the whole `:written` set against that derived route
  (`verify-written-against-route!`) — a class-inconsistent file-set THROWS a
  marker-tagged refusal (no manifest is returned).  For a `:public` target the
  committed manifest's withheld-entity rows are scrubbed to the non-linkable
  form + an OPAQUE `audit-ref`; the `:audit` record carries the EXACT rows keyed
  by that same ref (audit-side only, never committed to the public repo).  A
  `:private` target commits the exact rows directly."
  [db {:keys [run project written basis-t started-at ended-at agent corpus-sha
              exclusions redactions authorship-flag audit-ref status]
       :or   {status :complete}}]
  (let [route      (verified-route db project)
        fw-class   (:sensitivity route)
        public?    (= :public fw-class)
        ;; WHOLE-MANIFEST class/content-consistency gate (P-CITE-2 over the
        ;; file-set): a mis-scoped written row ABORTS here (refuse-not-filter),
        ;; BEFORE any manifest is constructed, so a class-inconsistent committed
        ;; manifest is unconstructible.  Fail-closed on unresolvable rel-paths.
        _          (verify-written-against-route! db route written)
        salt       (per-run-salt)
        ;; Opaque run-scoped audit ref (R4-2) — the run's own `:mm/id` when the
        ;; caller supplies it (so the ref RESOLVES to the persisted audit-side
        ;; `:mm/Run` rather than pointing at nothing), else a fresh per-run UUID.
        ;; NEVER name-bearing either way.
        audit-ref  (or audit-ref (java.util.UUID/randomUUID))
        committed  (export-manifest
                     {:run             run
                      :basis-t         basis-t
                      :started-at      started-at
                      :ended-at        ended-at
                      :agent           agent
                      :target          {:project     (:project-key route)
                                        :corpus-repo (:corpus-repo route)
                                        :trust-scope (:trust-scope route)}
                      :firewall-class  fw-class
                      :file-set        (mapv :rel-path written)
                      :corpus-sha      corpus-sha
                      :exclusions      exclusions
                      :redactions      redactions
                      ;; The audit-ref rides the committed manifest ONLY when
                      ;; the split hid something (public target with withheld
                      ;; rows); a private target co-resides its exact rows.
                      :audit-ref       (when (and public? (or (seq exclusions) (seq redactions)))
                                         audit-ref)
                      :authorship-flag authorship-flag
                      :salt            salt
                      :status          status})]
    {:committed committed
     ;; Audit-side ledger — the EXACT enumeration, NEVER committed to a public
     ;; repo.  Keyed by the SAME opaque ref the public committed manifest
     ;; carries, so an auditor with private-store access reconciles the two.
     :audit     {:audit/ref        audit-ref
                 :audit/run        run
                 :audit/exclusions (vec exclusions)
                 :audit/redactions (vec redactions)}}))

;;; ===========================================================================
;;; (3) The export-path recorder — one :mm/Run per export (W1.E wiring)
;;; ===========================================================================

(defn with-export-provenance
  "Run `export-thunk` (a 0-arg fn performing the projection) wrapped in one
  projection-run PROVENANCE record — the W1.E recorder the `project.export`
  handler delegates to.  Returns
  `{:written <project-graph-result> :run <:mm/Run> :manifest <committed> :audit <record>}`.

  basis-t + started-at are captured BEFORE the export (so `:manifest/basis-t`
  is the basis the file-set reflects, READ once — E-2, never re-derived
  downstream).  On SUCCESS: exactly ONE `:mm/Run` with `:status :succeeded` +
  the `:projection` discriminator, plus its manifest.

  ORDERING (load-bearing): the manifest is built+VERIFIED (`manifest-for-export`,
  which throws a marker-tagged refusal on a class-inconsistent written-set)
  BEFORE the `:succeeded` run is minted — so a mis-scoped OR failed export
  records ONLY a `:failed` run and produces NO committed manifest (E-1 negative
  + the refuse-not-filter contract).  On any throw a `:failed` run is recorded
  for audit (best-effort, never masking the error) and the original exception
  re-throws.

  SPILL SEAM (residual, W1.F unbuilt): `export-thunk` performs the projection —
  it has ALREADY WRITTEN the file-set to disk at the caller's `:to` by the time
  the gate verifies.  A refusal therefore aborts the manifest + the `:succeeded`
  run + the future W1.F COMMIT/publish path, but does NOT unwrite the refused
  files from a scratch `:to`.  The on-disk mechanisms are W1.G (newer-DB restore
  guard) and W1.H (content filter); until they land, nothing PUBLISHES a scratch
  `:to`, so the refused files are un-published local artifacts, not a leak.  A
  future pre-write entity-level check at the handler could close this seam by
  refusing before the thunk runs (deferred — W1.F is unbuilt this round).

  The run is minted with a caller-generated `:mm/id` UUID; `:manifest/run` is
  the REBUILD-STABLE `[:mm/id …]` lookup-ref (not a volatile `:db/id` eid) and
  that same `:mm/id` is the `:manifest/audit-ref`, so the committed manifest's
  run pointer + audit pointer both resolve to the persisted `:mm/Run` after a
  Tempo-C DB rebuild.  (NB `:mm/Run` is `:db-only`, so it survives a db-dump /
  restore but NOT a pure FS→DB rebuild — the full run-durability contract is the
  E/F/G handshake item; this makes the REFERENCE portable now.)

  `ctx` keys: `:project` (routing anchor ref — the firewall-class is DERIVED
  from it, CODEX-1), `:agent` (ref → :mm/Actor), and the optional G4
  `:exclusions`/`:redactions`/`:authorship-flag`/`:corpus-sha`.

  NB the mint is a live-DB write; the `project.export` handler DOUBLE-gates this
  recorder (a server-side config/env flag ANDed with the per-call opt, both
  default OFF — see `recording-enabled?`) so a bare MCP caller cannot mint live
  `:mm/Run` rows until the E/F/G handshake ratifies the recorder.  This fn
  itself is ungated (library-level; the deployment gate lives at the wiring
  point) so the falsification battery + the eventual ratified path both use it."
  [db {:keys [project agent exclusions redactions authorship-flag corpus-sha]} export-thunk]
  (let [started (java.util.Date.)
        basis-t (d/basis-t db)
        ;; The run's rebuild-stable `:mm/id` — generated up front so the manifest
        ;; (built BEFORE the succeeded mint, to keep a refusal from leaving a
        ;; :succeeded run) can carry a `[:mm/id …]` ref the mint then fulfils.
        run-id  (java.util.UUID/randomUUID)]
    (try
      (let [written (export-thunk)
            ended   (java.util.Date.)
            ;; VERIFY + assemble the manifest FIRST.  `manifest-for-export`
            ;; re-derives the route and refuses a class-inconsistent written-set
            ;; (marker-tagged throw) — reaching the succeeded mint below ONLY
            ;; when every written row is admissible.
            {:keys [committed audit]}
            (manifest-for-export db {:run             [:mm/id run-id]
                                     :audit-ref       run-id
                                     :project         project
                                     :written         written
                                     :basis-t         basis-t
                                     :started-at      started
                                     :ended-at        ended
                                     :agent           agent
                                     :corpus-sha      corpus-sha
                                     :exclusions      exclusions
                                     :redactions      redactions
                                     :authorship-flag authorship-flag
                                     :status          :complete})
            run     (record-projection-run! {:id         run-id
                                             :agent      agent
                                             :started-at started
                                             :ended-at   ended
                                             :status     :succeeded})]
        {:written written :run run :manifest committed :audit audit})
      (catch Throwable t
        ;; Record the FAILURE (audit-side; incompleteness recorded, never
        ;; silent — G4) but NEVER let a recorder hiccup mask the export error.
        ;; A FRESH `:mm/id` (not run-id, which the succeeded mint never reached)
        ;; keeps the unique-identity slot collision-free.
        (try
          (record-projection-run! {:id         (java.util.UUID/randomUUID)
                                   :agent      agent
                                   :started-at started
                                   :ended-at   (java.util.Date.)
                                   :status     :failed})
          (catch Throwable _ nil))
        (throw t)))))

;;; ===========================================================================
;;; (4) The live-write DOUBLE-GATE (deployment posture — fix 6)
;;; ===========================================================================

(defn recording-enabled?
  "Is the projection-run recorder RATIFIED as a live gate on THIS server?  The
  server-side half of the double-gate: it reads an OPERATOR-controlled flag most-
  specific-first — `SANDBAR_PROVENANCE_RECORD` env-var → `sandbar.provenance.record`
  JVM prop → the `:provenance-record?` config key — and is ABSENT/OFF by default.

  The `project.export` handler ANDs this with the per-call `:provenance` opt, so
  minting a live `:mm/Run` requires BOTH a caller asking for it AND an operator
  having turned the recorder on — a bare MCP caller cannot mint provenance rows
  before Dan's E/F/G ratification flips the flag.  (Env/prop reads route through
  the `sandbar.config` redefinable seams so a test can stub them.)"
  []
  (letfn [(truthy? [v] (contains? #{"1" "true" "yes" "on"}
                                  (some-> v str str/lower-case)))]
    (boolean (or (truthy? (config/getenv "SANDBAR_PROVENANCE_RECORD"))
                 (truthy? (config/getprop "sandbar.provenance.record"))
                 (true?   (config/value :provenance-record?))))))
