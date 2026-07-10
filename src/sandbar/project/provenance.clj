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
  audit-integrity property is now structural: the recorded firewall-class equals
  the filter the export actually enforced, because it is recomputed from the same
  DB the enforcement reads — the caller has nothing to forge.

  ── G4 audience-split (E-4 / R3-1 / W1.H P-CITE-2) ──────────────────────────
  A COMMITTED manifest for a `:public`-target run MUST NOT carry any private
  entity identifier: its exclusions/redactions collapse to NON-LINKABLE forms
  (a count + a per-run-SALTED digest, the salt discarded so digests are not
  cross-run-linkable) plus an OPAQUE run-scoped `:manifest/audit-ref` (a per-run
  UUID, never name-bearing).  The EXACT `{:entity <id> …}` enumeration lives
  audit-side ONLY (the `:audit` record `manifest-for-export` returns alongside
  the committed manifest, keyed by the same opaque ref).  A `:private`-target
  run MAY commit the exact enumeration (committed manifest + audit ledger
  co-reside in its private repo).  The scrub is applied at the lowest level
  (`export-manifest`), so a `:public` committed manifest carrying an exact
  private id is unconstructible by design.

  Spec: W1.E-manifest-contract §2/§3 (fork-3 amended), arc-plan W1.E + G4,
  HELD-NOTE.md (CODEX-1 travelling requirement)."
  (:require [datomic.api :as d]
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
  entity's generated/used are memorial REFS, not paths/basis-t."
  [{:keys [agent generated used started-at ended-at status name
           visibility-from visibility-to]
    :or   {status :succeeded}}]
  (cond-> {:dt/type                   :mm/Run
           :mm.activity/activity-type projection-activity-type
           :mm.activity/status        status}
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
  NON-LINKABLE fingerprint of a withheld-entity enumeration.  Because `salt` is
  per-run and discarded, the digest attests \"these exact rows were withheld\"
  to an auditor holding the audit-side record WITHOUT itself carrying any entity
  identifier or being comparable across runs."
  [salt rows]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str salt (pr-str rows)) "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 0xff)) bs))))

(defn- non-linkable-form
  "The NON-LINKABLE public-committed form of an exact withheld-entity `rows`
  enumeration: `{:count <n> :digest <hex>}` (or nil for an empty enumeration).
  Carries NO `:entity` id / slug / namespaced private ref — only a count and a
  per-run-salted digest (R3-1).  The exact rows live audit-side."
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
  identifier is UNCONSTRUCTIBLE:

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

(defn manifest-for-export
  "Emitter TOUCHPOINT — assemble BOTH the COMMITTED manifest and the audit-side
  exact record for a completed export, returning
  `{:committed <manifest> :audit <record>}`.

    :run        the recorded :mm/Run entity id/ref (from `record-projection-run!`)
    :project    the routing anchor — a :mm/Project ref (eid/ident/entity); the
                firewall-class is RE-DERIVED from THIS via the DB, never trusted
                from the caller (CODEX-1 fix)
    :written    the `sandbar.projection/project-graph` result
                ([{:rel-path \"…\" :written true} …]) — supplies :manifest/file-set
    :basis-t    the DB basis-t at run time
    :exclusions / :redactions   the EXACT G4 enumeration (audience-split applied)
    :started-at :ended-at :agent :corpus-sha :authorship-flag  — optional PROV fields

  The `:firewall-class`, `:target` project-key/corpus-repo/trust-scope ALL come
  from the VERIFIED route (DB-derived), never a caller map.  For a `:public`
  target the committed manifest's withheld-entity rows are scrubbed to the
  non-linkable form + an OPAQUE per-run `audit-ref`; the `:audit` record carries
  the EXACT rows keyed by that same ref (audit-side only, never committed to the
  public repo).  A `:private` target commits the exact rows directly."
  [db {:keys [run project written basis-t started-at ended-at agent corpus-sha
              exclusions redactions authorship-flag status]
       :or   {status :complete}}]
  (let [route      (verified-route db project)
        fw-class   (:sensitivity route)
        public?    (= :public fw-class)
        salt       (per-run-salt)
        ;; Opaque run-scoped audit ref — a per-run UUID, NEVER name-bearing
        ;; (R4-2).  Ties the committed manifest to its audit-side exact record.
        audit-ref  (java.util.UUID/randomUUID)
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
  the `:projection` discriminator, plus its manifest.  On FAILURE: a `:failed`
  run is recorded for audit (best-effort, never masking the export error) and
  the original exception re-throws — so a failed export mints NO
  completed-status run (E-1 negative).

  `ctx` keys: `:project` (routing anchor ref — the firewall-class is DERIVED
  from it, CODEX-1), `:agent` (ref → :mm/Actor), and the optional G4
  `:exclusions`/`:redactions`/`:authorship-flag`/`:corpus-sha`.

  NB the mint is a live-DB write; the `project.export` handler gates this
  recorder behind an explicit opt (default OFF / dry-run) so a routine live
  export stays read-only until the E/F/G handshake ratifies the recorder."
  [db {:keys [project agent exclusions redactions authorship-flag corpus-sha]} export-thunk]
  (let [started (java.util.Date.)
        basis-t (d/basis-t db)]
    (try
      (let [written (export-thunk)
            ended   (java.util.Date.)
            run     (record-projection-run! {:agent      agent
                                             :started-at started
                                             :ended-at   ended
                                             :status     :succeeded})
            {:keys [committed audit]}
            (manifest-for-export db {:run             (:db/id run)
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
                                     :status          :complete})]
        {:written written :run run :manifest committed :audit audit})
      (catch Throwable t
        ;; Record the FAILURE (audit-side; incompleteness recorded, never
        ;; silent — G4) but NEVER let a recorder hiccup mask the export error.
        (try
          (record-projection-run! {:agent      agent
                                   :started-at started
                                   :ended-at   (java.util.Date.)
                                   :status     :failed})
          (catch Throwable _ nil))
        (throw t)))))
