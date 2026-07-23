(ns sandbar.db.entailment.core
  "RDFS + OWL 2 RL property-characteristic entailment via Datomic-native Datalog rules.

  This namespace implements the substrate-layer entailment described in the
  2026-05-21 ADR (`decisions/sandbar_rdfs_entailment_via_datomic_native_
  datalog_rules_with_swclos_style_metaclass_property_characteristics_2026_05_21.md`).

  ## Architecture

  Six RDFS entailment rules (rdfs2 / rdfs3 / rdfs5 / rdfs7 / rdfs9 / rdfs11)
  plus selected OWL 2 RL property-characteristic rules (prp-trp / prp-symp /
  prp-asyp / prp-irp / prp-fp / prp-ifp / prp-inv1 / prp-inv2) are encoded as
  static Datomic Datalog rule-sets — `rdfs-rules` and `owl-rl-rules`.  The
  composite `all-rules` is the concatenation; consumers pass it (or a
  subset) to Datomic queries via the `%` binding.

  Property characteristics follow the SWCLOS-style metaclass pattern (ADR D3):
  characteristics are classes (e.g., `:dt/TransitiveProperty`).  Orthogonal
  combinations (e.g., transitive + asymmetric + irreflexive) compose via
  intersection classes whose `:dt/subclass-of` chain declares all
  components (ADR D4).  RDFS rdfs9 then entails membership in each component
  class from the property's single declared `:dt/type`.

  ## Substrate-transparency

  Per ADR D1 + synthesis §3.6, this module is OPAQUE to codec / projection /
  MCP / render — they continue to query Datomic with their existing patterns
  and see derived facts as ordinary facts when they pass `all-rules`.  The
  bridge for the dt/type-isa? subsumption check is in
  `sandbar.db.datatype` (augmented in Stage 5).

  ## Query-time default

  Per ADR D6, query-time inference is the default execution mode.  Selective
  materialization (Stage 6) is opt-in for hot-path closures via
  `sandbar.db.entailment.materialize` (not yet implemented).

  ## Stage 1 status

  This is the Stage 1 module skeleton — rule-set placeholders are empty
  vectors; `apply-entailment` is the consumer helper.  Stages 2-3 land the
  actual rule bodies; Stage 4 declares the intersection classes; Stage 5
  augments `dt/type-isa?`.  See `plans/sandbar_dt_entailment_implementation_
  arc_2026_05_21.md` for the full stage breakdown."
  (:require [datomic.api :as d]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rule sets — static data; consumers pass via the `%` rule-binding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def rdfs-rules
  "The 6 core RDFS entailment rules as Datomic Datalog rule-set data.

   - rdfs2  : domain inference          (p domain C) ∧ (x p y) → (x type C)
   - rdfs3  : range inference           (p range C)  ∧ (x p y) → (y type C)
   - rdfs5  : subPropertyOf transitive  (p1 sub p2) ∧ (p2 sub p3) → (p1 sub p3)
   - rdfs7  : sub-property entailment   (p sub q)   ∧ (x p y) → (x q y)
   - rdfs9  : subClassOf instance lift  (C1 sub C2) ∧ (x type C1) → (x type C2)
   - rdfs11 : subClassOf transitive     (C1 sub C2) ∧ (C2 sub C3) → (C1 sub C3)

   ## Encoding notes

   - **rdfs11 + rdfs5 are recursive** — base case is a single edge; inductive
     case chains via the same rule.  Datomic terminates correctly on cyclic
     graphs (set-semantics dedup); see cycle-detection test in
     `sandbar.db.entailment.core-test`.
   - **rdfs7 invokes rdfs5** — sub-property entailment composes with the
     transitive closure, so multi-step subprop chains entail correctly.
   - **rdfs9 invokes rdfs11** — analogous; subclass instance lift over
     transitively-closed subClassOf.
   - **Non-reflexive** — `(rdfs11-sc ?C ?C)` does NOT hold by default
     (canonical RDFS rdfs10 reflexivity is out of scope here).  Consumers
     wanting reflexive subsumption (`type-isa? X X` for any X) handle the
     identity case explicitly.
   - **Anonymous `_` in rdfs2 / rdfs3** — the object (rdfs2) or subject
     (rdfs3) is irrelevant to the inference; only the property's domain /
     range constraint matters.

   See `references/rdfs_entailment_rules_datalog_compilation_reference.md`
   and the synthesis at `syntheses/rdfs_entailment_strategy_synthesis_for_
   sandbar_substrate_2026_05_21.md` §5."
  '[;; rdfs2 — domain inference
    [(rdfs2-domain ?x ?C)
     [?p :dt/domain ?C]
     [?x ?p _]]

    ;; rdfs3 — range inference
    [(rdfs3-range ?y ?C)
     [?p :dt/range ?C]
     [_ ?p ?y]]

    ;; rdfs11 — subClassOf transitivity (recursive)
    ;; Base: a single declared edge.
    [(rdfs11-sc ?C1 ?C2)
     [?C1 :dt/subclass-of ?C2]]
    ;; Inductive: chain via an intermediate class.
    [(rdfs11-sc ?C1 ?C3)
     [?C1 :dt/subclass-of ?C2]
     (rdfs11-sc ?C2 ?C3)]

    ;; rdfs9 — subClassOf instance lift (uses rdfs11)
    ;; DERIVED-ONLY: produces only entailed (non-declared) type memberships.
    [(rdfs9-sco ?x ?C2)
     [?x :dt/type ?C1]
     (rdfs11-sc ?C1 ?C2)]

    ;; rdfs9-isa — convenience: UNION of direct :dt/type + derived (via rdfs9-sco)
    ;; Used by OWL 2 RL rules (which check property class membership) and by
    ;; Stage 5's dt/type-isa? augmentation.  Returns true for both direct and
    ;; inherited class membership.
    [(rdfs9-isa ?x ?C) [?x :dt/type ?C]]
    [(rdfs9-isa ?x ?C) (rdfs9-sco ?x ?C)]

    ;; rdfs5 — subPropertyOf transitivity (recursive)
    [(rdfs5-subprop ?p1 ?p2)
     [?p1 :dt/subproperty-of ?p2]]
    [(rdfs5-subprop ?p1 ?p3)
     [?p1 :dt/subproperty-of ?p2]
     (rdfs5-subprop ?p2 ?p3)]

    ;; rdfs7 — sub-property entailment (uses rdfs5)
    [(rdfs7-spo ?x ?p2 ?y)
     (rdfs5-subprop ?p1 ?p2)
     [?x ?p1 ?y]]])

(def owl-rl-rules
  "The 8 selected OWL 2 RL property-characteristic entailment rules as
   Datomic Datalog rule-set data.

   Inference rules (derive new facts):
   - prp-trp   : transitive            (p type Transitive)         ∧ (x p y) ∧ (y p z) → (x p z)
   - prp-symp  : symmetric             (p type Symmetric)          ∧ (x p y)            → (y p x)
   - prp-inv1  : inverse-of forward    (p inverse-of q)            ∧ (x p y)            → (y q x)
   - prp-inv2  : inverse-of backward   (p inverse-of q)            ∧ (x q y)            → (y p x)

   Integrity-check rules (surface violations as query results — per ADR D2;
   audit-invariant integration in a follow-up, no transactor-function
   gatekeeping at this stage):
   - prp-asyp-violation : asymmetric   (p type Asymmetric)         ∧ (x p y) ∧ (y p x) → violation
   - prp-irp-violation  : irreflexive  (p type Irreflexive)        ∧ (x p x)            → violation

   Conflict-surfacing rules (surface owl:sameAs implications as query results —
   per ADR D3; NO auto-merge):
   - prp-fp-conflict  : functional         (p type Functional)         ∧ (x p y1) ∧ (x p y2) → conflict (y1 ≠ y2)
   - prp-ifp-conflict : inverse-functional (p type InverseFunctional)  ∧ (x1 p y) ∧ (x2 p y) → conflict (x1 ≠ x2)

   ## Encoding notes

   - All rules use `(rdfs9-isa ?p :dt/SomeMarkerClass)` for property-class
     membership — this composes with the SWCLOS-style intersection-class
     pattern (ADR D3 + D4).  A property declared with `:dt/type`
     `:dt/TransitiveAsymmetricIrreflexiveProperty` (intersection class
     subclass-of'ing all three marker classes) satisfies `rdfs9-isa` for
     each of `:dt/TransitiveProperty` / `:dt/AsymmetricProperty` /
     `:dt/IrreflexiveProperty`.
   - The `[?p :db/ident ?p-ident]` lookup binds the property's keyword
     ident; the rule then uses `?p-ident` at attribute position of a triple
     pattern (e.g., `[?x ?p-ident ?y]`).  This requires the property's
     `:db/ident` to be a real Datomic schema attribute — for predicates in
     `schema/mm.edn` etc., this is the case.
   - prp-trp is RECURSIVE for full transitive closure (not just 2-hop);
     the recursive case extends a single hop through the closure.
   - prp-inv1 + prp-inv2 are two distinct rules because the substrate's
     `:dt/inverse-of` is declared one-way per pair; the rules together
     express the symmetric semantics of inverse-of."
  '[;; ========== prp-trp — transitive closure ==========
    ;; Base case: a direct edge over a transitive property.
    ;; NOTE: data patterns use ?p (the eid) at the attribute position rather
    ;; than ?p-ident (a keyword via :db/ident lookup) — keyword-bound variables
    ;; at attribute position can produce spurious matches in Datomic.  Use the
    ;; eid for the data pattern; derive ?p-ident from :db/ident for the output.
    [(prp-trp ?x ?p-ident ?y)
     (rdfs9-isa ?p :dt/TransitiveProperty)
     [?x ?p ?y]
     [?p :db/ident ?p-ident]]
    ;; Recursive case: chain the base edge through the closure.
    [(prp-trp ?x ?p-ident ?z)
     (rdfs9-isa ?p :dt/TransitiveProperty)
     [?x ?p ?y]
     (prp-trp ?y ?p-ident ?z)
     [?p :db/ident ?p-ident]]

    ;; ========== prp-symp — symmetric ==========
    ;; Derives ONLY the reverse direction; the original (x p y) is the input
    ;; ground fact.  Consumers wanting the full symmetric closure (both
    ;; directions) compose this rule with direct ground-fact queries.
    [(prp-symp ?y ?p-ident ?x)
     (rdfs9-isa ?p :dt/SymmetricProperty)
     [?x ?p ?y]
     [?p :db/ident ?p-ident]]

    ;; ========== prp-asyp-violation — asymmetric integrity check ==========
    ;; Surfaces (x, p, y) where both (x p y) and (y p x) hold for an asymmetric p.
    [(prp-asyp-violation ?x ?p-ident ?y)
     (rdfs9-isa ?p :dt/AsymmetricProperty)
     [?x ?p ?y]
     [?y ?p ?x]
     [?p :db/ident ?p-ident]]

    ;; ========== prp-irp-violation — irreflexive integrity check ==========
    ;; Surfaces (x, p) where a self-loop (x p x) exists for an irreflexive p.
    ;; Uses explicit (= ?x ?y) constraint rather than [?x ?p ?x] same-variable
    ;; pattern — within a rule body, Datomic's same-variable-in-data-pattern
    ;; constraint can fail to fire (verified empirically against this rule's
    ;; earlier `[?x ?p ?x]` formulation which over-matched).
    [(prp-irp-violation ?x ?p-ident)
     (rdfs9-isa ?p :dt/IrreflexiveProperty)
     [?x ?p ?y]
     [(= ?x ?y)]
     [?p :db/ident ?p-ident]]

    ;; ========== prp-fp-conflict — functional sameAs ==========
    ;; Surfaces conflicting (y1, y2) values for a functional property
    ;; (where (x p y1) and (x p y2) with y1 ≠ y2).
    ;; The y1 ≠ y2 constraint avoids surfacing reflexive identities.
    [(prp-fp-conflict ?x ?p-ident ?y1 ?y2)
     (rdfs9-isa ?p :dt/FunctionalProperty)
     [?x ?p ?y1]
     [?x ?p ?y2]
     [(not= ?y1 ?y2)]
     [?p :db/ident ?p-ident]]

    ;; ========== prp-ifp-conflict — inverse-functional sameAs ==========
    ;; Surfaces conflicting (x1, x2) subjects for an inverse-functional property
    ;; (where (x1 p y) and (x2 p y) with x1 ≠ x2).
    [(prp-ifp-conflict ?x1 ?x2 ?p-ident ?y)
     (rdfs9-isa ?p :dt/InverseFunctionalProperty)
     [?x1 ?p ?y]
     [?x2 ?p ?y]
     [(not= ?x1 ?x2)]
     [?p :db/ident ?p-ident]]

    ;; ========== prp-inv1 — inverse-of forward ==========
    ;; If (p inverse-of q) and (x p y) holds, then (y q x) holds.
    [(prp-inv1 ?y ?q-ident ?x)
     [?p :dt/inverse-of ?q]
     [?x ?p ?y]
     [?q :db/ident ?q-ident]]

    ;; ========== prp-inv2 — inverse-of backward ==========
    ;; If (p inverse-of q) and (x q y) holds, then (y p x) holds.
    ;; Symmetric counterpart to prp-inv1: substrate declarations of
    ;; :dt/inverse-of are one-way, but OWL semantics is symmetric.
    [(prp-inv2 ?y ?p-ident ?x)
     [?p :dt/inverse-of ?q]
     [?x ?q ?y]
     [?p :db/ident ?p-ident]]])

(def all-rules
  "Composite rule-set — RDFS + OWL 2 RL.

   Consumers pass this (or a subset) to Datomic queries via the `%` rule-
   binding to enable entailment.  Selecting a subset is useful for tests
   that want to isolate one rule's behavior."
  (vec (concat rdfs-rules owl-rl-rules)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; apply-entailment — consumer helper
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn apply-entailment
  "Run a Datomic Datalog query with entailment rules.

   Three arities:
     (apply-entailment db query)                — uses `all-rules`
     (apply-entailment db query rules)          — caller-supplied rule-set
     (apply-entailment db query rules & inputs) — extra :in bindings after $ and %

   The query must declare `:in $ %` (database + rules) at minimum.  Any
   additional `:in` bindings pass through as `inputs`.

   Returns the result set per `datomic.api/q`.

   Example:
     (apply-entailment db
       '[:find ?e ?c
         :in $ %
         :where (rdfs9-sco ?e ?c)]
       rdfs-rules)

   When `rules` is empty, no entailment fires — useful for ground-fact-only
   queries that nonetheless declare `:in $ %` for uniformity.

   Per ADR D6 — query-time inference default.  Per synthesis §6 — module is
   substrate-transparent to consumers."
  ([db query]
   (apply-entailment db query all-rules))
  ([db query rules]
   (d/q query db rules))
  ([db query rules & inputs]
   (apply d/q query db rules inputs)))
