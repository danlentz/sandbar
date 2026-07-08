(ns sandbar.project.provenance
  "W1.ctx SPINE — the export/projection-run PROVENANCE RECORD SHAPE (fork-3) +
  the per-run MANIFEST shape + the emitter TOUCHPOINT.

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

  ── SCOPE BOUNDARY (W1.ctx spine; W1.E / W1.H are LATER phases, OUT here) ────
  This ns ships the RECORD SHAPE + the MANIFEST shape (spine subset) + the
  emitter touchpoint fn.  It does NOT build the W1.E RECORDER's G4 apparatus —
  the `:manifest/exclusions` / `:manifest/redactions` enumeration, the R3-1
  public-vs-audit audience-split + non-linkable `:manifest/audit-ref`, the
  `:manifest/authorship-flag`, and the declassification ledger are W1.E / W1.H,
  DEFERRED (their manifest keys are intentionally ABSENT from `export-manifest`).
  This ns also does NOT auto-wire recording into the reactive sink / project-
  graph write path — that recorder wiring is W1.E; here the touchpoint is a
  pure fn the recorder will call.

  Spec: W1.E-manifest-contract §2/§3 (fork-3 amended), arc-plan W1.E, the
  fork-3 ruling + AMENDMENT-LOG."
  (:require [datomic.api :as d]
            [sandbar.db.datatype :as dt]))

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
  The MINIMAL schema-level recorder the spine ships — it records the fork-3
  record SHAPE.  The full W1.E recorder (G4 exclusion/redaction enumeration,
  the audience-split manifest, the authorship-flag, the declassification ledger)
  is a LATER phase and is NOT built here."
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
;;; (2) The manifest shape (W1.E §2 — spine subset) + the emitter touchpoint
;;; ===========================================================================

(defn export-manifest
  "PURE.  The per-run MANIFEST map (W1.E §2 — the SPINE subset).  The machine-
  readable index W1.F commits into the run's target repo and W1.G's newer-DB
  guard compares against.  Fields:

    :manifest/run            <ref>       the :mm/Run entity (§1), :projection-disc.
    :manifest/basis-t        <long>      DB basis-t at run time (W1.G reads THIS)
    :manifest/started-at / :manifest/ended-at / :manifest/agent
    :manifest/target         {:project <key> :corpus-repo <string> :trust-scope <kw>}
    :manifest/firewall-class <kw>        the filter APPLIED = the scope's effective
                                         sensitivity (W1.ctx table)
    :manifest/file-set       [<rel-path> …]   the projected file-set
    :manifest/corpus-sha     <string>    the git tree/commit sha W1.F produced
    :manifest/status         #{:complete :refused :partial}

  DEFERRED to W1.E / W1.H (the G4 apparatus — intentionally ABSENT here, the
  spine boundary): :manifest/exclusions, :manifest/redactions,
  :manifest/audit-ref, :manifest/authorship-flag."
  [{:keys [run basis-t started-at ended-at agent target firewall-class
           file-set corpus-sha status]
    :or   {status :complete}}]
  (cond-> {:manifest/run            run
           :manifest/basis-t        basis-t
           :manifest/target         target
           :manifest/firewall-class firewall-class
           :manifest/file-set       (vec file-set)
           :manifest/status         status}
    started-at (assoc :manifest/started-at started-at)
    ended-at   (assoc :manifest/ended-at   ended-at)
    agent      (assoc :manifest/agent      agent)
    corpus-sha (assoc :manifest/corpus-sha corpus-sha)))

(defn manifest-for-export
  "Emitter TOUCHPOINT — assemble the export-run manifest from a completed export.

    :run        the recorded :mm/Run entity/ref (from `record-projection-run!`)
    :route      a `sandbar.project.route/route-of` decision (the routing target
                — supplies :manifest/target + :manifest/firewall-class)
    :written    the `sandbar.projection/project-graph` result
                ([{:rel-path \"…\" :written true} …]) — supplies :manifest/file-set
    :basis-t    the DB basis-t at run time
    :started-at :ended-at :agent :corpus-sha  — optional PROV fields

  The `:manifest/firewall-class` is the route's `:sensitivity` — the scope's
  effective sensitivity (the W1.ctx table's output), i.e. the filter APPLIED.
  This is the pure fn a W1.E recorder calls at the export boundary; the spine
  ships the touchpoint, not the auto-wiring into the reactive/export path."
  [{:keys [run route written basis-t started-at ended-at agent corpus-sha]}]
  (export-manifest
    {:run            run
     :basis-t        basis-t
     :started-at     started-at
     :ended-at       ended-at
     :agent          agent
     :target         {:project     (:project-key route)
                      :corpus-repo (:corpus-repo route)
                      :trust-scope (:trust-scope route)}
     :firewall-class (:sensitivity route)
     :file-set       (mapv :rel-path written)
     :corpus-sha     corpus-sha
     :status         :complete}))
