(ns sandbar.firewall.label
  "S7 — THE LABEL RESOLVER (BU-2).

  Computes the `sandbar.firewall.core` Label — `{:sensitivity :contexts
  :project}` — for a :mm/Memory ∪ :mm/Context ∪ :mm/Project source, from
  either a live db entity (`label-of-eid` / `label-of-ref`) or a pre-commit
  entity-spec (`label-from-props`, EP-1's author-time case where the row is
  NOT yet in the db).  `label-of` is the class-dispatching front door; the
  three resolver arities are what `sandbar.firewall.enforce` injects into the
  pure core's `violating-governed-edges`.

  TOTAL + fail-closed: an unrecognized / unresolvable source is labelled
  `{:sensitivity :private :contexts #{<:context/UNASSIGNED eid>} :project
  <:project/UNASSIGNED eid>}`, so the core's subset clause refuses it across
  every real compartment until it is assigned.

  ── CA-4: `:sensitivity` is the MOST-RESTRICTIVE 4-way composition ─────────
  `:private` DOMINATES `:public`.  A label is `:public` ONLY when EVERY axis
  present is `:public`/`:public-bottom`; ANY axis `:private` (including an
  unknown/absent `firewall-class`, which the closed table maps `:private`)
  forces `:private`.  The four axes:
    1. memory intrinsic `:mm.memory/visibility` (or the owning-project
       `:mm.project/default-visibility` when the memory carries none)
    2. the owning-project `:mm.project/default-visibility`
    3. `sensitivity-of-firewall-class` of the project `:mm.project/firewall-class`
    4. `sensitivity-of-firewall-class` of EACH `:mm.project/runs-in-context`
       Context's `:mm.context/firewall-class` (card-MANY — private in ANY
       context composes `:private`, the leak-sound direction).
  The schema itself MANDATES this composition (`mm-artifact.edn:759-765`: the
  project firewall-class 'COMPOSES most-restrictive with the Context's').

  ── R14 / CA-5: ACYCLICITY (build-critical) ────────────────────────────────
  This ns MUST NOT require `sandbar.db.datatype` (datatype requires
  `sandbar.firewall.enforce` after BU-4 — a reverse require would be a load
  cycle).  It does its OWN ~10-line `:dt/subclass-of` walk over an INJECTED
  db handle via `datomic.api` + `sandbar.db.ref` (eid normalization), never
  reaching for datatype's `type-isa?` / `slots-of`.  The ns requires ONLY
  `clojure.set` + `datomic.api` + `sandbar.db.ref` — no `sandbar.db.datomic`,
  no `sandbar.db.datatype` — the tightest R14-acyclic posture.

  Spec: S7-PLAN §1.1 / BU-2, CA-3 (Context dual parentage), CA-4."
  (:require [clojure.set :as set]
            [datomic.api :as d]
            [sandbar.db.ref :as ref]))

;;; ===========================================================================
;;; SENTINELS — resolved lazily against the live db (schema-seeded idents).
;;; ===========================================================================

(defn- sentinel-eid
  "The `:db/id` of a schema-seeded sentinel ident (`:project/UNASSIGNED` /
  `:context/UNASSIGNED`) under `db`, or nil if the schema has not loaded it.
  Resolved THROUGH the db (never `(:db/id kw)`) so an ident-bearing sentinel
  never collapses to nil (S6-review #1)."
  [db ident]
  (ref/ref->eid db ident))

;;; ===========================================================================
;;; THE `:dt/subclass-of` WALK (own implementation — R14 acyclicity)
;;; ===========================================================================

(defn- ancestor-idents
  "The SET of class idents `class-ident` transitively `:dt/subclass-of`
  (INCLUSIVE of `class-ident` itself), walked over the live db.  `:dt/subclass-of`
  is card-MANY (`meta.edn:162-168`), so a class can have multiple parents (e.g.
  :mm/Context is BOTH :dt/Resource-descended AND :mm/Meta-descended — CA-3);
  the walk unions every reachable parent.  Cycle-safe via a visited set.

  Own implementation (NOT datatype's `ancestors-of` / rules) so `firewall.*`
  never requires `sandbar.db.datatype` (R14)."
  [db class-ident]
  (loop [seen     #{}
         frontier #{class-ident}]
    (if (empty? frontier)
      seen
      (let [seen'   (set/union seen frontier)
            parents (into #{}
                          (comp
                            (map #(d/entity db %))
                            (mapcat (fn [e]
                                      ;; :dt/subclass-of ref values render as
                                      ;; ident keywords or entity maps — read
                                      ;; the ident of each parent.
                                      (keep (fn [p]
                                              (if (keyword? p)
                                                p
                                                (:db/ident (d/entity db p))))
                                            (let [raw (:dt/subclass-of e)]
                                              (cond
                                                (nil? raw)        nil
                                                (keyword? raw)    [raw]
                                                (map? raw)        [raw]
                                                (coll? raw)       raw
                                                :else             [raw]))))))
                          frontier)]
        (recur seen' (set/difference parents seen'))))))

(defn- class-isa?
  "Is `class-ident` the SAME class as, or a `:dt/subclass-of` descendant of,
  `ancestor` — walked over `db`?  The firewall's total, datatype-free
  `type-isa?` analog."
  [db ancestor class-ident]
  (contains? (ancestor-idents db class-ident) ancestor))

(defn- class-ident-of
  "The `:dt/type` class IDENT of entity map `e` (a keyword), or nil."
  [e]
  (let [t (:dt/type e)]
    (cond
      (keyword? t) t
      (map? t)     (:db/ident t)
      :else        nil)))

;;; ===========================================================================
;;; SENSITIVITY — the CA-4 most-restrictive 4-way composition
;;; ===========================================================================

(def firewall-class->sensitivity
  "The closed `firewall-class` → sensitivity table (§8-R12, applied to BOTH
  the project AND the context firewall-class axes per CA-4).  Every CURRENT
  value maps `:private`; the public bottom is recognized ONLY via the explicit
  additive `:public-bottom` VALUE (values are additive, never a schema change).
  Unknown / absent is handled by `sensitivity-of-firewall-class` (fail-closed
  `:private`), never a table miss reading `:public`."
  {:none                        :private
   :proprietary-tokens-advisory :private
   :project-isolated            :private
   :public-bottom               :public})

(defn sensitivity-of-firewall-class
  "The `firewall-class` keyword → `:public`/`:private`, fail-closed.  ANY value
  not EXPLICITLY `:public-bottom` — including nil/absent and any unknown future
  value — reads `:private` (nothing becomes public by reinterpretation; only
  the explicit `:public-bottom` designation is public).  §8-R12 / CA-4."
  [firewall-class]
  (get firewall-class->sensitivity firewall-class :private))

(defn- most-restrictive
  "Fold a seq of `:public`/`:private` sensitivities to the most-restrictive
  (`:private` dominates).  An EMPTY seq (no axis present) is `:public` — the
  neutral element — so a source with no confidentiality axis at all is not
  spuriously stamped private by the fold itself (the fail-closed default is
  supplied by the caller's UNASSIGNED path, not here)."
  [sensitivities]
  (if (some #(= :private %) sensitivities)
    :private
    :public))

(defn- visibility->sensitivity
  "A `:mm.memory/visibility` / `:mm.project/default-visibility` keyword →
  `:public`/`:private`, fail-closed: ONLY `:public` reads public; `:private`,
  any unknown value, and nil all read `:private` (default-deny, AP-6)."
  [visibility]
  (if (= :public visibility) :public :private))

;;; ===========================================================================
;;; PROJECT-AXIS COMPOSITION (shared by the Project + Memory branches)
;;; ===========================================================================

(defn- project-context-eids
  "The SET of `:mm.project/runs-in-context` Context eids of project entity
  `proj` (card-MANY), each normalized via `ref->eid`.  Empty when the project
  ties to no context."
  [db proj]
  (into #{}
        (keep #(ref/ref->eid db %))
        (let [raw (:mm.project/runs-in-context proj)]
          (cond
            (nil? raw)  nil
            (coll? raw) raw
            :else       [raw]))))

(defn- context-firewall-sensitivity
  "The most-restrictive `sensitivity-of-firewall-class` over EVERY context in
  `context-eids` (card-MANY runs-in-context — private in ANY context composes
  `:private`, CA-4).  Empty set ⇒ `:public` (no context axis to restrict)."
  [db context-eids]
  (most-restrictive
    (map (fn [ctx-eid]
           (sensitivity-of-firewall-class
             (:mm.context/firewall-class (d/entity db ctx-eid))))
         context-eids)))

(defn- project-sensitivity
  "The composed sensitivity of a :mm/Project entity `proj` — the most-
  restrictive of (a) `:mm.project/default-visibility`, (b) the project
  `:mm.project/firewall-class`, and (c) every `runs-in-context` Context's
  `:mm.context/firewall-class` (CA-4 axes 2/3/4)."
  [db proj]
  (most-restrictive
    [(visibility->sensitivity (:mm.project/default-visibility proj))
     (sensitivity-of-firewall-class (:mm.project/firewall-class proj))
     (context-firewall-sensitivity db (project-context-eids db proj))]))

;;; ===========================================================================
;;; UNASSIGNED — the fail-closed label
;;; ===========================================================================

(defn- unassigned-contexts
  "The compartment coordinate for a source that composed NO real context —
  the `:context/UNASSIGNED` singleton `#{<eid>}` (a non-empty private
  compartment that is a SUBSET of no real compartment, so `firewall-permits?`
  REFUSES it across every real target — `UNASSIGNED -> private{H}` REFUSE).

  This is the load-bearing fail-closed choice: an EMPTY `:contexts` set makes
  the core's `(set/subset? #{} tgt)` clause VACUOUSLY TRUE — so an
  empty-source-context private label would PERMIT into ANY private compartment
  (a LEAK).  Every label branch (memory / context / project) MUST route an
  empty composed context set through here so the fail-closed direction holds
  on the SOURCE side of the subset clause.

  Degrades to `#{}` ONLY if the schema has not seeded the `:context/UNASSIGNED`
  sentinel (an un-loaded corpus).  That degenerate `#{}` is NOT fail-closed on
  the source side (it would permit); it is the unavoidable pre-seed floor and
  never reached once the schema is loaded."
  [db]
  (if-let [ctx-un (sentinel-eid db :context/UNASSIGNED)]
    #{ctx-un}
    #{}))

(defn unassigned-label
  "The fail-closed label for an unrecognized / unresolvable source:
  `:private`, the `:context/UNASSIGNED` singleton compartment, the
  `:project/UNASSIGNED` routing anchor.  Resolves the sentinels against `db`.

  The `:contexts` is the `:context/UNASSIGNED` SINGLETON (never `#{}` once the
  schema is seeded) — a NON-EMPTY private compartment, so the core's subset
  clause refuses it across every real compartment (`UNASSIGNED -> private{H}`
  REFUSE).  An EMPTY set would make the source side of the subset clause
  VACUOUSLY TRUE and PERMIT the leak; the singleton is what makes UNASSIGNED
  fail-closed on the SOURCE side.  (Only if the sentinel is un-seeded does
  `:contexts` degrade to `#{}`, the unavoidable pre-schema-load floor.)"
  [db]
  {:sensitivity :private
   :contexts    (unassigned-contexts db)
   :project     (sentinel-eid db :project/UNASSIGNED)})

;;; ===========================================================================
;;; label-of — CLASS-DISPATCHING FRONT DOOR (over an entity map)
;;; ===========================================================================

(defn- owning-project-entity
  "The owning-project entity of a memory entity map `ent` under `db` — the
  `:mm.memory/owning-project` ref resolved to an entity, or the
  `:project/UNASSIGNED` sentinel entity when the slot is absent (ABSENT =>
  the sentinel VALUE, DESIGN-ONTOLOGY §4.2)."
  [db ent]
  (let [proj-eid (or (ref/ref->eid db (:mm.memory/owning-project ent))
                     (sentinel-eid db :project/UNASSIGNED))]
    (when proj-eid (d/entity db proj-eid))))

(defn- context-label
  "Label a :mm/Context source.  Its compartment is ITSELF (the context eid);
  its sensitivity is the most-restrictive of its OWN `:mm.context/firewall-class`
  and any intrinsic `:mm.memory/visibility` it inherited (Context ⊑ :mm/Meta ⊑
  :mm/Memory carries the visibility slot).  `:project` is nil — a bare Context
  is not owned by a project (§1.1)."
  [db ent]
  (let [ctx-eid  (:db/id ent)
        sens     (most-restrictive
                   [(sensitivity-of-firewall-class (:mm.context/firewall-class ent))
                    ;; A Context MAY carry an intrinsic visibility (inherited
                    ;; slot); fold it if present, otherwise it is a no-op
                    ;; (nil => the visibility->sensitivity :private only when
                    ;; the KEY is present — absent is not an axis here).
                    (if (contains? ent :mm.memory/visibility)
                      (visibility->sensitivity (:mm.memory/visibility ent))
                      :public)])]
    {:sensitivity sens
     ;; A PRE-COMMIT Context spec has no `:db/id` yet — so its compartment
     ;; composes EMPTY.  Route empty through the UNASSIGNED singleton (NOT
     ;; `#{}`): an empty source-context set makes `firewall-permits?`'s subset
     ;; clause vacuously TRUE and would PERMIT a brand-new private Context
     ;; authoring a cross-compartment governed edge (UNASSIGNED->private{H}
     ;; must REFUSE).  Uniform with memory-label / project-label (EP-1 gap fix).
     :contexts    (if ctx-eid #{ctx-eid} (unassigned-contexts db))
     :project     nil}))

(defn- project-label
  "Label a :mm/Project source.  Its compartment is its `runs-in-context` set;
  its sensitivity is the composed `project-sensitivity` (CA-4 axes 2/3/4); its
  `:project` anchor is itself."
  [db ent]
  (let [proj-eid (:db/id ent)
        contexts (project-context-eids db ent)]
    {:sensitivity (project-sensitivity db ent)
     ;; A context-less Project (a PRE-COMMIT spec with no `runs-in-context`
     ;; yet, or a project that ties to no context) composes an EMPTY
     ;; compartment.  Route empty through the UNASSIGNED singleton (NOT `#{}`):
     ;; the core's subset clause is vacuously TRUE on an empty SOURCE set, so a
     ;; `#{}`-context private project would PERMIT a cross-compartment governed
     ;; edge into ANY private compartment (UNASSIGNED->private{H} must REFUSE).
     ;; Uniform with memory-label / context-label (EP-1 authoring gap fix).
     :contexts    (if (seq contexts) contexts (unassigned-contexts db))
     :project     proj-eid}))

(defn- memory-label
  "Label a generic :mm/Memory source.  Composes (CA-4): the memory's intrinsic
  `:mm.memory/visibility` (or the owning-project default when absent) with the
  owning-project's FULL composed sensitivity (default-visibility ⊔ project
  firewall-class ⊔ context firewall-classes).  Its compartment is the owning-
  project's `runs-in-context` set; its `:project` anchor is the owning-project
  eid."
  [db ent]
  (let [proj      (owning-project-entity db ent)
        proj-sens (if proj (project-sensitivity db proj) :private)
        intrinsic (if (contains? ent :mm.memory/visibility)
                    (visibility->sensitivity (:mm.memory/visibility ent))
                    ;; No intrinsic visibility → inherit the project default
                    ;; (already folded into proj-sens, so :public here is a
                    ;; no-op that lets proj-sens drive).
                    :public)
        contexts  (if proj (project-context-eids db proj) #{})]
    {:sensitivity (most-restrictive [intrinsic proj-sens])
     ;; No resolvable context → the UNASSIGNED singleton, so an unassigned
     ;; private memory refuses across real compartments (fail-closed on the
     ;; SOURCE side of the subset clause — an empty set would permit).  Uniform
     ;; with context-label / project-label via the shared helper.
     :contexts    (if (seq contexts) contexts (unassigned-contexts db))
     :project     (:db/id proj)}))

(defn label-of
  "THE class-dispatching front door — Label for a source entity map `ent`
  under db `db`.  Dispatches Context, THEN Project, THEN generic Memory
  (`:mm/Project` ⊑ `:mm/Artifact` ⊑ `:mm/Memory` and `:mm/Context` ⊑
  `:mm/Meta` ⊑ `:mm/Memory`, so the MOST-SPECIFIC class must be tried first —
  order is load-bearing).  A source whose class is none of the three, or whose
  class cannot be resolved, gets the fail-closed `unassigned-label`.

  `ent` is a live-entity map OR a pre-commit entity-spec (both associative);
  the class is read from `:dt/type`."
  [db ent]
  (let [cls (class-ident-of ent)]
    (cond
      (nil? cls)                     (unassigned-label db)
      (class-isa? db :mm/Context cls) (context-label db ent)
      (class-isa? db :mm/Project cls) (project-label db ent)
      (class-isa? db :mm/Memory cls)  (memory-label db ent)
      :else                          (unassigned-label db))))

;;; ===========================================================================
;;; RESOLVERS — what enforce injects into the core (§1.4)
;;; ===========================================================================

(defn label-of-eid
  "Label for the live entity named by `eid` under `db`, or the fail-closed
  `unassigned-label` when `eid` resolves to no entity.  The EP-3 / S9 resolver
  instance."
  [db eid]
  (if-let [ent (and eid (d/entity db eid))]
    (label-of db ent)
    (unassigned-label db)))

(defn label-of-ref
  "Label for whatever ref shape `ref-val` names under `db` (eid / ident / entity
  / lookup-ref / upsert map) — `ref->eid`-normalized first, then `label-of-eid`.
  Returns nil when the ref resolves to NO live entity, so the core's
  `violating-governed-edges` records it as `:skipped` (the best-effort carrier /
  stub case, §4.4) rather than fabricating a label.  The EP-1 interactive
  resolver instance."
  [db ref-val]
  (when-let [eid (ref/ref->eid db ref-val)]
    (label-of-eid db eid)))

(defn label-from-props
  "Label for a pre-commit entity-spec `props` (the EP-1 author-time case: the
  row is NOT yet in the db, so there is no eid to resolve).  Reads the class +
  the confidentiality axes straight off the spec, composing exactly as
  `label-of` would for a live entity — its owning-project / runs-in-context
  refs ARE resolved against `db` (they point at ALREADY-committed entities, or
  at same-batch siblings the caller's resolver handles separately).  This is
  the SOURCE label EP-1 checks a not-yet-written edge FROM."
  [db props]
  (label-of db props))

(defn intrinsic-visibility-label
  "The SOURCE label for the `:mm.memory/owning-project` DECLASSIFICATION check
  (§8-R8 / T-12) — identical to `label-of` EXCEPT `:sensitivity` reflects the
  memory's INTRINSIC `:mm.memory/visibility` ALONE, NOT composed with the
  owning-project's sensitivity.  Composing owning-project INTO the source
  (as `label-of` does for the general flow check) would swallow this specific
  guard: an explicit `:public` memory owned into a `:private` project would be
  re-labelled `:private` and its owning-project edge would then permit.  For
  THIS edge only, an EXPLICIT `:public` visibility must be measured against the
  project's effective sensitivity so a declassification-shaped write refuses.
  A memory with NO intrinsic visibility inherits the project default (fail-
  closed `:private`), so absent-visibility never trips this guard (T-12's
  absent-owning-project = no-check case is preserved by the enforce arm)."
  [db props]
  (let [base (label-of db props)]
    (if (and (contains? props :mm.memory/visibility)
             (class-isa? db :mm/Memory (class-ident-of props)))
      (assoc base :sensitivity
             (visibility->sensitivity (:mm.memory/visibility props)))
      base)))
