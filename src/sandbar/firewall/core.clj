(ns sandbar.firewall.core
  "S7 — THE PURE DIRECTIONAL FIREWALL CORE (BU-1).

  The deterministic, physical, principal-INDEPENDENT flow-core of the
  X-minus firewall.  Label × Label → verdict.  This namespace is
  DB-QUERY-FREE: no `d/q`, no db reads, no entity resolution.  Its ONLY
  dependency is `clojure.set` (for the compartment subset test).  All DB
  touching lives in `sandbar.firewall.label` (label-of) and
  `sandbar.firewall.enforce` (the resolver-binding sites); this core is
  consumed IDENTICALLY by EP-1 (author-time write), EP-3 (traverse-time),
  the S9 db-closure, and S10's audit emitter — five callers, one core
  (semantic-identity proven by T-19).

  TRUST MODEL (non-negotiable, `decisions/firewall_trust_model_deterministic_physical_enforcement_llm_untrusted_audit_orthogonal_optin_dan_2026_07_06.md`):
  the firewall is DETERMINISTIC + PHYSICAL; the LLM is UNTRUSTED in the
  enforcement path.  The point predicates are PURE over LABELS (slot
  values) — reject on the EDGE, never on the caller/principal.  No model
  judgement, no content inference, no paraphrase detection (that residual
  stays disciplinary + named, never mechanical — the trust-model boundary).

  ── The Label contract (a PLAIN MAP, not a record — §1.1) ──────────────
    Label ::= {:sensitivity  :public | :private   ; X-minus binary; tiers
                                                   ;   are additive VALUES later
               :contexts     #{eid ...}           ; the compartment coordinate —
                                                   ;   a SET of :mm/Context eids
                                                   ;   (card-many
                                                   ;    :mm.project/runs-in-context, R2)
               :project      eid | nil}           ; the owning-project eid
                                                   ;   (routing anchor; nil for a
                                                   ;   bare Context)
  All eids are `sandbar.db.ref/ref->eid`-normalized upstream — a sentinel
  or interned ident-bearing ref NEVER collapses to nil (S6-review #1).
  TOTAL + fail-closed: an unrecognized/unresolvable source is labelled
  {:sensitivity :private :contexts #{<:context/UNASSIGNED eid>}
   :project <:project/UNASSIGNED eid>} by `label-of` (BU-2), so it falls to
  the subset clause with the UNASSIGNED singleton and refuses across
  compartments.  `:sensitivity` is computed by `label-of` as the
  MOST-RESTRICTIVE composition of all four axes present (CA-4/BU-2); this
  core simply CONSUMES the already-composed `:sensitivity`.

  ── The Verdict contract (§1.4) ────────────────────────────────────────
    Verdict ::= {:permitted? false
                 :reason     :flow-forbidden | :tie-forbidden
                 :slot       <kw>          ; the governed slot the edge is on
                 :target-ref <raw>         ; the raw ref as written (pre-resolution)
                 :src-label  <Label>
                 :tgt-label  <Label>}
  A refusal Verdict is the ONE shape `enforce/verdict->error` adapts to the
  `validate-data` error map ({:type :firewall-violation :severity :violation
  ...}) that EP-1 throws, EP-3 projects as a blocked hop, and S10 records —
  no second detection path anywhere.

  ── F7 declassification (RESERVED, unimplemented — §10 scope exclusion) ─
  A future `declassified-handle?` carve-out (the ratified F7 clause) would
  permit an explicitly-declassified public→private edge.  It is NOT in S7:
  no clause here consults it; it is named only so S9/S10 know where it will
  attach."
  (:require [clojure.set :as set]))

;;; ===========================================================================
;;; §2 — THE GOVERNED-EDGE CENSUS (RESOLVED — every slot dispositioned)
;;;
;;; These sets are the ruled superset (S7-PLAN §2, R6).  They are DERIVED
;;; FROM / ASSERTED AGAINST the LIVE EFFECTIVE slot surface (CA-3): BU-7
;;; asserts `governed-flow-slots` equals the effective `slots-of`
;;; ref/flow surface for :mm/Context ∪ :mm/Project ∪ :mm/Memory after the
;;; FULL schema load (no drift).  The flow set MUST include the ref-typed
;;; :mm.memory/* edges INHERITED by :mm/Context (and every :mm/Meta
;;; subclass) via :mm/Context → :mm/Meta → :mm/Memory — the census is a
;;; property of the label's OWNING SLOT, principal-independent, so a single
;;; :mm.memory/* keyword governs the edge whether it sits on a plain Memory
;;; or an inheriting Context.
;;; ===========================================================================

(def governed-flow-slots
  "GOVERNED by the citation-flow predicate (`firewall-permits?`) — §2.1.
  Every ref-typed intellectual-dependence/provenance edge, PLUS
  `:mm.memory/owning-project`.  A written edge src→tgt reveals the
  target's existence + identity on the source, so ALL are governed in the
  direction WRITTEN — the inverse-named predicates (`superseded-by`,
  `informed-by`, `documented-by`, `evidenced-by`, `triggered-by`,
  `motivated-by`) included, because the edge ON THE SOURCE is the leak
  surface regardless of the predicate's semantic arrow.  These :mm.memory/*
  idents are inherited by :mm/Context via :mm/Meta (CA-3), so the SAME
  set governs Context's inherited refs."
  #{:mm.memory/cites
    :mm.memory/related
    :mm.memory/refines
    :mm.memory/motivated-by
    :mm.memory/motivates
    :mm.memory/composes-with
    :mm.memory/supersedes
    :mm.memory/superseded-by
    :mm.memory/informs
    :mm.memory/informed-by
    :mm.memory/implements
    :mm.memory/conforms-to
    :mm.memory/descends-from
    :mm.memory/evidenced-by
    :mm.memory/evidences
    :mm.memory/documented-by
    :mm.memory/documents
    :mm.memory/triggered-by
    :mm.memory/touches
    ;; owning-project (ref → :mm/Project): governed; the only refusable
    ;; case is an EXPLICIT :public-visibility memory owned into an
    ;; effectively-:private project (§8-R8, composed 4-way per CA-4).
    :mm.memory/owning-project})

(def governed-carrier-slots
  "GOVERNED by the citation-flow predicate, but STRING carriers (rel-path
  identity, not real refs) — §2.2.  Best-effort at EP-1 (resolvable →
  flow-checked; unresolvable → skip + WARN + audit record), closure-
  guaranteed at S9.  `:mm.context/cites`/`related` are :mm/Context's OWN
  string legs (mm.edn:1836-1837); `:mm.memory/introduced-in` is a
  :db.type/string carrier (mm.edn:1423 — RECLASSIFIED out of the ref
  allowlist, which was wrong)."
  #{:mm.context/cites
    :mm.context/related
    :mm.memory/introduced-in})

(def governed-tie-slots
  "GOVERNED by the compartment-membership predicate (`tie-permits?`) —
  §2.3.  The SAME tie checked from whichever end carries the write:
  `:mm.project/runs-in-context` (Project end) and
  `:mm.context/visible-projects` (Context end).  Refuses exactly: a PUBLIC
  compartment containing a PRIVATE member — the catastrophic co-load
  fail-open (a private project listed by the public bottom would co-load
  private rows into EVERY session) this clause exists to refuse at
  author-time."
  #{:mm.project/runs-in-context
    :mm.context/visible-projects})

(def exempt-slots
  "EXEMPT — structural / component / vocabulary / actor edges (§2.4),
  both-sides tested (T-13 over-refusal guard).  Containment (`parent`,
  `part-of`, `has-part`) is smeared (ONT-F4); codec carriers
  (`first-section`, `frontmatter`, `links`); shared :mm/Tag vocabulary
  (`tags`, `themes` — a TERM, not a dependence); actor provenance
  (`created-by`); the metamodel edges (`:dt/type`, `:dt/subclass-of`);
  and `:mm.project/code-repo` (R9 — every :mm/Codebase labels
  :private/UNASSIGNED today, so governing this leg bricks the keystone
  tie acceptance-(d); the exposure concern is single-entity and carried by
  the repo-handle-url-safety Shape, and a memorial `cites` → a Codebase
  stays flow-checked via §2.1)."
  #{;; :mm/Memory structural / component / vocabulary / actor
    :mm.memory/parent
    :mm.memory/part-of
    :mm.memory/has-part
    :mm.memory/first-section
    :mm.memory/frontmatter
    :mm.memory/links
    :mm.memory/tags
    :mm.memory/themes
    :mm.memory/created-by
    ;; :mm/Context structural / vocabulary carriers
    :mm.context/tags
    :mm.context/first-section
    :mm.context/links
    :mm.context/frontmatter
    ;; metamodel
    :dt/type
    :dt/subclass-of
    ;; :mm/Project — code-repo leg (R9)
    :mm.project/code-repo})

(defn firewall-governed-ref?
  "Is `slot` governed by SOME firewall predicate?  Membership across the
  THREE governed sets (flow, carrier, tie).  §2.5: an UNKNOWN ref slot
  (in none of the governed sets and not explicitly EXEMPT) is EXEMPT +
  WARN-once + audit record (R10) — that WARN/audit posture is the
  enforce/traverse caller's job (fail-visible, census-forcing, never
  write-bricking); this predicate answers only the membership question."
  [slot]
  (boolean
    (or (contains? governed-flow-slots slot)
        (contains? governed-carrier-slots slot)
        (contains? governed-tie-slots slot))))

;;; ===========================================================================
;;; §1 — THE PURE POINT PREDICATES
;;; ===========================================================================

(defn- public?
  "A label whose :sensitivity is exactly :public (the shared public
  bottom).  Fail-closed: anything else — :private, an unknown value, nil,
  or a missing key — is NOT public."
  [label]
  (= :public (:sensitivity label)))

(defn- private?
  "The fail-closed complement of `public?`: any label NOT at the public
  bottom.  Explicit :private, an unrecognized sensitivity, and a
  malformed/absent label all read private, so an unresolvable source can
  never masquerade as public."
  [label]
  (not (public? label)))

(defn firewall-permits?
  "THE point predicate.  Pure: Label × Label → boolean.  No DB, no
  principal, no content — §1.2.

  PERMIT iff:
    (= :public (:sensitivity tgt))                      ; down-lattice to the
                                                        ;   shared public bottom
    OR (and (= :private (:sensitivity src))
            (set/subset? (:contexts src) (:contexts tgt)))
                                                        ; every compartment that
                                                        ;   can see the source can
                                                        ;   also see the target

  REFUSE otherwise.  public→private (src :public, tgt :private) and
  cross-private (contexts disjoint / not-subset — the diamond) fail the
  SAME clause pair, which is why ONE predicate covers all three charter
  directions:

    public       → public          PERMIT  (lateral within the bottom)
    private any  → public          PERMIT  (private→public ALLOWED — the point)
    public       → private         REFUSE  (clause 1 fails; clause 2 needs private src)
    private{H}   → private{H}      PERMIT  (intra-context, ACROSS projects — T-7)
    private{A}   → private{B}      REFUSE  (the diamond, held out)
    private{h}   → private{h,w}    PERMIT  (subset — tgt at least as visible)
    private{h,w} → private{h}      REFUSE  (a work-visible source may not depend
                                            on home-only material — R2)
    UNASSIGNED   → UNASSIGNED       PERMIT  (one private compartment; pre-migration
                                            corpus keeps working)
    UNASSIGNED   → private{H}       REFUSE  (fail-closed until assigned)

  Fail-closed: an unknown sensitivity on EITHER side is not :public, so it
  falls to the subset clause with the UNASSIGNED singleton and refuses
  across compartments."
  [src-label tgt-label]
  (boolean
    (or (public? tgt-label)
        (and (private? src-label)
             (set/subset? (set (:contexts src-label))
                          (set (:contexts tgt-label)))))))

(defn tie-permits?
  "The membership-safety predicate for the two compartment-TIE legs
  (`:mm.project/runs-in-context` on the Project end,
  `:mm.context/visible-projects` on the Context end) — §1.3.

  This is NOT the citation-flow predicate: applying `firewall-permits?` to
  the edges that DEFINE the label is circular and deadlocks legitimate
  multi-context declarations (a private project could never add a second
  context under the subset clause; §8-R7).

  REFUSE iff (public? context-side-label) AND (private? project-side-label)
  — the PUBLIC compartment must not contain a PRIVATE member, checked from
  WHICHEVER end carries the write (closes S7-DESIGN-B OQ-2 symmetrically).
  Everything else PERMITS:
    private ↔ private (any contexts)   membership definition, not flow
    public-project ↔ private-context   the co-load shape (T-11d)
    public ↔ public

  Fail-closed: an unresolvable/unknown label on either side reads
  `private?` (not public); a private member in a private-or-unknown
  compartment PERMITS (both ends private) — the refusal fires ONLY on the
  affirmatively-public compartment, which is exactly the leak this clause
  exists to catch, never on an ambiguous one.

  PUBLIC-BOTTOM DESIGNATION (S7 must-fix #3): whether the context end is the
  PUBLIC co-load root is its `:mm.context/firewall-class :public-bottom`
  DESIGNATION — carried on the Context label as `:tie-designation` — NOT its
  visibility-composed `:sensitivity`.  A `:public-bottom` context masked with
  `:mm.memory/visibility :private` composes `:sensitivity :private`; keying the
  tie on `:sensitivity` would let it admit a private project into the public
  root.  Fall back to `:sensitivity` only when no designation is present (a
  non-Context / hand-built label — fail-closed: absent designation reads
  private, so the refusal never fires spuriously)."
  [context-side-label project-side-label]
  (let [ctx-designation (or (:tie-designation context-side-label)
                            (:sensitivity context-side-label))]
    (not (and (= :public ctx-designation)
              (private? project-side-label)))))

;;; ===========================================================================
;;; §1.4 — THE CLOSURE FINDER (pure over a supplied edge-seq + injected resolver)
;;; ===========================================================================

(defn- tie-slot?
  "Does `slot` belong to the compartment-tie set (so `tie-permits?`, not
  `firewall-permits?`, is its applicable predicate)?"
  [slot]
  (contains? governed-tie-slots slot))

(defn- edge-verdict
  "The applicable-predicate dispatch for ONE resolved governed edge.
  Returns a refusal Verdict when the edge is FORBIDDEN, else nil.

  - Tie slots use `tie-permits?` with the src-label as the WRITING end's
    label and the resolved target as the other end.  Which physical end is
    the context vs the project is carried by the two slot idents:
    `:mm.project/runs-in-context` is written FROM a Project (src) TO a
    Context (tgt); `:mm.context/visible-projects` is written FROM a Context
    (src) TO a Project (tgt).  `tie-permits?` takes
    (context-side-label project-side-label) — so we orient by slot.
  - Every other governed slot (flow + carrier) uses `firewall-permits?`
    src→tgt directly."
  [slot src-label tgt-label target-ref]
  (cond
    (tie-slot? slot)
    (let [[ctx-side proj-side]
          (case slot
            :mm.project/runs-in-context   [tgt-label src-label] ; src=Project, tgt=Context
            :mm.context/visible-projects  [src-label tgt-label] ; src=Context, tgt=Project
            ;; defensive: unreachable given tie-slot? gate, but keep total
            [src-label tgt-label])]
      (when-not (tie-permits? ctx-side proj-side)
        {:permitted? false
         :reason     :tie-forbidden
         :slot       slot
         :target-ref target-ref
         :src-label  src-label
         :tgt-label  tgt-label}))

    :else
    (when-not (firewall-permits? src-label tgt-label)
      {:permitted? false
       :reason     :flow-forbidden
       :slot       slot
       :target-ref target-ref
       :src-label  src-label
       :tgt-label  tgt-label})))

(defn violating-governed-edges
  "Pure over a supplied edge-seq + an INJECTED resolver — §1.4.

  Given a source `src-label`, a seq of `[slot target-ref]` pairs
  (`governed-edges`), and `resolve-label` (a fn ref → Label-or-nil),
  return:

    {:violations [Verdict ...]   ; every governed edge whose applicable
                                 ;   predicate REFUSES
     :skipped    [{:slot <kw> :target-ref <raw> :reason :unresolved} ...]}
                                 ; every governed target `resolve-label`
                                 ;   returns nil for (the best-effort
                                 ;   carrier/stub case — §4.4; the S9
                                 ;   closure is the guarantee)

  Only GOVERNED slots are examined; a non-governed (exempt / unknown) slot
  contributes NOTHING here (its WARN/audit disposition is the caller's, per
  §2.5 / R10).  The applicable predicate is `tie-permits?` for the two tie
  slots and `firewall-permits?` for the flow + carrier slots.

  The resolver is the ONLY impurity, and it is INJECTED — five instances,
  ONE core (T-19 proves semantic IDENTITY):
    EP-1 interactive : (fn [ref] (label-of-ref db ref))          ; DB
    EP-1 import      : spec-index-first, then DB, else nil         ; batch forward refs
    EP-3             : (fn [eid] (label-of-eid db eid))            ; DB
    S9 closure       : same as EP-3 over the built DB
    tests            : (fn [ref] (get label-map ref))             ; pure map"
  [src-label governed-edges resolve-label]
  (reduce
    (fn [acc [slot target-ref]]
      (if-not (firewall-governed-ref? slot)
        acc                                   ; non-governed slot: nothing fires
        (let [tgt-label (resolve-label target-ref)]
          (if (nil? tgt-label)
            (update acc :skipped conj
                    {:slot slot :target-ref target-ref :reason :unresolved})
            (if-let [v (edge-verdict slot src-label tgt-label target-ref)]
              (update acc :violations conj v)
              acc)))))
    {:violations [] :skipped []}
    governed-edges))
