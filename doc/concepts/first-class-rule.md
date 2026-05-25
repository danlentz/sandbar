# First-Class Rules

> Rules are functions with dispatch-spec.  `:mm/Rule` is `:dt/subclass-of :mm/Fn` — every rule IS a `:mm/Fn`, inheriting the function substrate (parameters / return-type / body / lang / purpose / purity / cost-class / source-ns / source-var / version / installed-as) and adding dispatch-spec slots (actor / context / event / enforcement / bypass / priority / engine) that configure *when* the rule fires.  At the metamodel level, `:dt/Rule` is a subtype of `:dt/Fn` with constrained signature — rule bodies return inference data (derived facts) rather than arbitrary tx-data.  The rule subsystem layers into the 4-layer architecture from the layered-rule arc (Layer 0 substrate RDFS entailment; Layer 1 user-domain memorial invariants; Layer 2 codec/projection/MCP boundary; Layer 3 client-application rules).  Engines are pluggable per rule body shape (Datomic Datalog for entailment, Clara for production rules, core.logic for backtracking, Malli for schema validation, Meander for term rewriting, plain pattern for trivial cases).  For the function substrate this builds on, see [`first-class-fn.md`](first-class-fn.md); for the declarative-constraint cousin, see [`shape-validation.md`](shape-validation.md); for the metamodel underneath both, see [`metamodel.md`](metamodel.md).

## Thesis

A rule is a function that knows when to fire.

The naive shape treats rules as ad-hoc machinery — a `clojure.spec` here, a Malli schema there, a clara-rules production over there, each with its own registration ceremony, its own scope discipline, its own conflict-resolution policy.  No way to ask *"what rules apply to a `:mm/Decision` mutation in the junior-engineer context?"* without grepping multiple namespaces.  No way to author a new rule without a code change.  No way to amend a rule's scope without redeploy.

Sandbar's commitment: **a rule is a typed citizen of the substrate, addressable as data, executable through the same `:mm/Fn` substrate as every other function, scoped through declarative dispatch-spec slots that the substrate enforces.**  A `:mm/Rule` is a `:mm/Fn` — it inherits parameters, body, language, purity, source-ns — and adds the dispatch-spec axis: actor (who triggered), context (where / when), event (what happened), enforcement (audit / warn / block), bypass (exemptions), priority (within-scope ordering), engine (clara / core.logic / Datalog / Malli / Meander / plain).  Activation is hook-driven with rules retrieved by BM25F + typed-edge graph walk — rule bodies live in the corpus; hooks are thin event detectors.

This separation — rule-as-inert-data + strategy-as-pluggable-application — is the meander discipline applied at the substrate level.  The same rule deploys top-down or bottom-up; once or until-fixpoint; over the entire corpus or a filtered slice; in audit mode or block mode; through Clara TMS or core.logic relations — by varying the *strategy* parameter, not the rule.  The rule itself is data.

## Lineage

The design synthesizes ~50 prior-art systems across five lineages.  The full survey is captured at `memory/syntheses/rules_as_first_class_prior_art_synthesis_2026_05_11.md`; what follows names the load-bearing precedents.

### Production rule systems — OPS5 / CLIPS / JESS / Drools

The forward-chaining tradition — Forgy's OPS5 (1981), CLIPS (Riley 1985), JESS (Friedman-Hill 1995), Drools (JBoss 2001) — gave us the working-memory pattern: facts accumulate; rules pattern-match against them; the Rete network (Forgy 1982) compiles the pattern set into a discrimination tree that incrementally maintains match results as facts mutate.

Sandbar inherits the rules-as-data discipline directly.  A `:mm/Rule` body whose `:dt.fn/lang` is `:clara` compiles to a clara-rules `{:lhs [...] :rhs ...}` data form — zero glue.  Working memory is the substrate itself: memorials ARE facts; rule patterns walk the typed-edge graph; the Datomic query engine handles indexing.  Drools-style multi-axis scope (agenda-groups / activation-groups / ruleflow-groups + salience) inspires Sandbar's dispatch-spec slot vocabulary, though Sandbar lands on the simpler shape (actor × context × event × priority) with architectural room for Drools-style refinement when warranted.

### Logic-programming and constraint propagation

Prolog (Colmerauer & Roussel 1972), miniKanren (Friedman, Byrd & Kiselyov 2005), and core.logic (Nolen 2010) gave us the backtracking-search and unification substrate.  Sussman, Steele & Radul's propagator network (1980; revived 2009) gave us the dependency-tracking discipline — derived facts carry provenance; on premise retraction, dependency-directed backtracking surgically invalidates the dependents.

Sandbar's `:mm/Rule` body can target core.logic relations when the rule shape is bidirectional or requires unification (rather than one-way pattern matching).  The Garnet KR system (Myers et al 1990) demonstrated rule activation with auto-dependency-discovery via instrumented memory access — push-invalidate on upstream change, pull-recompute on read — which informs the (deferred) cross-session corpus-level retraction model.

### Cyc microtheories and PowerLoom

Cyc (Lenat & Guha 1990) gave us the microtheory lattice: rules apply within scoped contexts; the `ist(Mt, P)` predicate reifies the (rule, scope) tuple as a first-class assertion; `genlMt` declares the multi-parent lattice of microtheory inheritance; contradictions across sibling scopes are tolerated; lifting rules cross-context translate as explicit transformations.

Sandbar's scope discipline IS the Cyc microtheory pattern.  Rules scope to `(actor, context)` tuples; contexts form a multi-parent lattice (not a strict tree); cross-cutting disciplines are supported natively; contradictions across sibling scopes are tolerated.  Strict-tree inheritance (PowerLoom 1999) was considered and rejected — too restrictive for the cross-cutting discipline shape that the corpus actually carries.

### SHACL and constraint-rule convergence

SHACL Core (Knublauch & Kontokostas 2017) treats validation as declarative shape-as-data.  SHACL Advanced Features `sh:rule` (CONSTRUCT-style) and `sh:SPARQLFunction` (computational) extend the substrate to *production* rules — declarative shapes can fire rules that produce derived facts.

Sandbar's `:dt/Rule` (metamodel-level) is `:dt/subclass-of :dt/Fn` with constrained signature: the body returns *inference data* (derived facts) rather than arbitrary tx-data.  This matches SHACL-AF's `sh:rule` semantics — a rule is a function whose return shape is restricted.  The dispatch-spec slots (actor / context / event) on `:mm/Rule` extend SHACL's target-shape model with the activation-time discrimination that production-rule systems contribute.

### Meander — rule-strategy decoupling

Meander (Long 2019–), drawing on Visser's Stratego heritage, articulated the architectural principle that *rule is inert data; strategy is the pluggable application policy*.  Top-down, bottom-up, once, until-fixpoint, in-some, all-but-one, audit-mode, inference-mode — each is a strategy that applies the same rule differently.

Sandbar adopts the principle wholesale.  A `:mm/Rule` body is a pattern-action pair (or LHS / RHS in clara-form, or `==`/relations in core.logic-form, or schema in Malli-form, etc.).  The strategy that walks the corpus and applies the rule is a separate concern — `mem rules apply --strategy <top-down|bottom-up|once|fix>` parameterizes the same rule across the entire deployment matrix.  The rule itself does not know whether it will be fired once, until-fixpoint, or in audit-mode.

### CLOS method combinations and ContextL

CLOS's method combinations (Bobrow et al 1988) — `:before`, `:after`, `:around`, qualified primary methods, custom combinations — give the precedent for composing rule activation by qualifier.  ContextL (Costanza & Hirschfeld 2005) added context-oriented dispatch — methods active in a context layer become available without explicit predicate matching.

Sandbar's `:dt.fn/qualifier` slot (inherited by `:mm/Rule` from `:mm/Fn`) carries the CLOS method-combination semantics (`:primary` / `:before` / `:after` / `:around`).  The `:mm.rule/context` dispatch-spec slot carries the ContextL pattern — a rule active in `:contexts/sandboxed-work` becomes available when that context is the dispatch context.  Both compose with the multi-axis scope discriminator.

### Cursor rules, CLAUDE.md, and the agent-rules tradition

The modern AI-tooling generation — Cursor's `.cursor/rules/*.mdc`, Anthropic's CLAUDE.md, Claude Code's hooks and skills, ESLint's rule registry, clj-kondo's `:config-in-ns` cascade, GitHub Rulesets' target × bypass-actors × enforcement-mode shape — all converge on frontmatter-as-attachment-policy plus rule body.  Sandbar's frontmatter schema (`type: rule` + `event:` + `actor:` + `context:` + `enforcement:` + `bypass:` + `priority:`) is a direct synthesis of these precedents, normalized to fit the substrate's typed-edge memorial vocabulary.

## The substrate

### Two layers, like `:dt/Fn`

```
METAMODEL LAYER  (:dt/* namespace; substrate primitive)

  :dt/Resource (root)
  └── :dt/Fn
       └── :dt/Rule   — :dt/Fn with constrained signature
                         (returns inference data, not arbitrary tx-data)


MEMORIAL LAYER   (:mm/* namespace; first-class memorial citizens)

  :mm/Memory (root)
  └── :mm/Fn
       └── :mm/Rule   — :mm/Fn + dispatch-spec slots
                         (actor + context + event + enforcement + bypass + priority + engine)
```

The metamodel level (`:dt/Rule`) declares **what a rule is** as a substrate type — its constrained return signature.  The memorial level (`:mm/Rule`) declares **what a particular rule memorial is** — its body, its dispatch-spec, its source-ns / source-var, projected to `memory/rules/<slug>.md` for human readability and BM25F retrieval.

Subclass-of-`:mm/Fn` means **inheritance**: a `:mm/Rule` memorial carries every `:mm/Fn` slot (the full `:dt.fn/*` vocabulary from [`first-class-fn.md`](first-class-fn.md)) PLUS the rule-specific `:mm.rule/*` dispatch-spec slots.  A `:mm/Rule` memorial has `:dt/type :mm/Rule` AND transitively `:dt/type :mm/Fn` AND its function-shape conforms to `:dt/Rule` (the metamodel; constrained signature).

### The `:mm.rule/*` dispatch-spec vocabulary

The rule-specific slots that govern *when* a rule fires:

| Slot                   | Meaning                                                                                       |
|------------------------|-----------------------------------------------------------------------------------------------|
| `:mm.rule/actor`       | Ref(s) to `:mm/Actor` — who/what triggered the event (or `:any` for actor-independence)      |
| `:mm.rule/context`     | Ref(s) to `:mm/Context` — where/when the rule applies (or path globs, tool-name patterns, `:any`) |
| `:mm.rule/event`       | Keyword(s) — `:pre-grounding`, `:pre-tool-use`, `:session-start`, `:user-prompt-submit`, `:post-commit`, etc. |
| `:mm.rule/enforcement` | `:audit` (log only) / `:warn` (advisory) / `:block` (reject)                                  |
| `:mm.rule/bypass`      | Set of `(actor, context)` tuples exempt from this rule                                        |
| `:mm.rule/priority`    | Numeric utility score; within-scope ordering (Drools salience pattern)                        |
| `:mm.rule/engine`      | `:clara-rules` / `:core.logic` / `:datalog` / `:malli` / `:meander` / `:plain-pattern`        |
| `:mm.rule/lifecycle`   | `:active` / `:deactivated` / `:superseded`                                                    |

These slots are *additive* on top of the `:mm/Fn` slot vocabulary — they don't replace it.  A rule's body still lives in `:dt.fn/body`; its language in `:dt.fn/lang`; its purpose typically in `:dt.fn/purpose :assert` or `:dt.fn/purpose :infer`; its purity classification still governs retry safety.

### Why inheritance from `:mm/Fn` and not a sibling

The Datomic posture: a rule IS a function returning inference data.  PowerLoom's separation of `defrule` from `deffunction` was conceptually distinct but operationally indistinguishable — both declarations produce executable bodies with parameters and return values.  Sandbar's choice (per Stage B Fork 6 of the first-class `:dt/Fn` integration arc) is the Datomic posture: rule is fn with constrained signature.

The consequence: every `:mm/Fn` substrate operation works on `:mm/Rule` instances unchanged.  `defdbfn` authors them.  `:dt.fn/version` + `:dt.fn/superseded-by` track them.  The reactive projection sink writes them to `memory/rules/<slug>.md`.  BM25F retrieval by `:class :mm/Fn` returns them alongside non-rule functions; filtering by `:dt/type :mm/Rule` narrows the slice.  The substrate composes.

## Authoring a rule

A rule memorial at `memory/rules/no_commit_signing.md`:

```markdown
---
name: no-commit-signing
description: Block git commits that include Co-Authored-By trailers or bot signatures per Dan-directive (per memory/git/no_commit_signing.md).
type: rule
shape-id: ":sandbar.rule/no-commit-signing"

# :mm/Fn-inherited slots:
lang: plain-pattern
purpose: assert
purity: pure-total
cost-class: cheap
installed-as: classpath-fn
source-ns: sandbar.rules.git
source-var: no-commit-signing
version: "1.0.0"
status: installed

# :mm.rule/* dispatch-spec slots:
actor: :any
context: contexts/git-commit.md
event: :pre-commit
enforcement: :block
priority: 100
engine: :plain-pattern
lifecycle: :active
bypass: []
---

## Why this rule exists

Per the standing Dan-directive captured at `memory/git/no_commit_signing.md`:
no Co-Authored-By trailers; no bot-email signatures; commit messages are
plain prose.  This rule blocks the commit at pre-commit time when the
discipline is violated.

## Pattern

The rule matches commit-message text against the disallowed-trailer regex:

```clojure
;; The :dt.fn/body in :plain-pattern form:
{:match    #"(?im)^(co-authored-by:.*|signed-off-by:.*<.*\\[bot\\].*>)"
 :action   :reject
 :reason   "Commit signing disallowed per memory/git/no_commit_signing.md"}
```

## Bypass

None.  The discipline is keystone (TIER 0); no actor / context exemption.

## See also

- `memory/git/no_commit_signing.md` — the source directive
- `memory/decisions/discipline_visibility_at_startup_2026_05_07.md` — TIER 0 keystone discipline policy
```

When the file is written, the codec parses; the substrate ingests; a `:mm/Rule` entity commits with `:dt/type [:mm/Rule :mm/Fn]` and the full dispatch-spec.  The reactive sink (per [`projection.md`](projection.md)) writes the file back when the entity mutates.

Alternatively — when the rule has executable body shape — author through `defdbfn`:

```clojure
(ns sandbar.rules.git
  (:require [sandbar.db.fn :refer [defdbfn]]))

(defdbfn no-commit-signing [commit-msg]
  {:dt.fn/purpose      :assert
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Block commits with disallowed trailers."
   ;; Extended attr-map can carry :mm.rule/* slots too (macro relays):
   :mm.rule/actor       :any
   :mm.rule/context     :contexts/git-commit
   :mm.rule/event       :pre-commit
   :mm.rule/enforcement :block
   :mm.rule/priority    100
   :mm.rule/engine      :plain-pattern}
  (let [pattern #"(?im)^(co-authored-by:.*|signed-off-by:.*<.*\[bot\].*>)"]
    (if (re-find pattern commit-msg)
      {:status :reject
       :reason "Commit signing disallowed per memory/git/no_commit_signing.md"}
      {:status :pass})))
```

The macro dual-emits — the `:db/fn` schema entity (or `:classpath-fn` peer-resident binding) AND the `:mm/Rule` memorial.  Because `:mm/Rule` is `:dt/subclass-of :mm/Fn`, the macro recognizes the `:mm.rule/*` slots and includes them in the memorial entity alongside the inherited `:dt.fn/*` slots.

## Activation — the hooks-retrieve-rules model

The activation model — settled in the 2026-05-11 rules-as-first-class ADR §D7 — keeps hooks thin and rule bodies in the corpus.

The mechanism, end-to-end:

1. **Event detection**: a hook (e.g. `inject_rule_recall.bb` for `PreToolUse` events) fires at the relevant lifecycle point.  The hook is a few hundred bytes — it knows nothing about rule bodies, only about the event shape.
2. **Retrieval**: the hook issues a BM25F + typed-edge query against the corpus.  *"For event `:pre-tool-use`, actor `actors/claude-opus-4-7-1m`, context `contexts/sandboxed-work`, return applicable `:mm/Rule` instances ranked by priority + relevance."*
3. **Dispatch**: the retrieved rules are passed through `core.match`'s decision-tree compiler (Maranget's algorithm) for near-constant-time selection narrowed to the active scope.
4. **Engine invocation**: each selected rule's body invokes its declared engine (`:clara-rules` for production rules; `:core.logic` for backtracking; `:datalog` for native Datomic rules; `:malli` for schema validation; `:plain-pattern` for trivial cases).
5. **Enforcement**: per the rule's `:mm.rule/enforcement` setting, audit / warn / block decisions feed back to the calling hook, which surfaces the result to the user or returns the policy decision to the substrate.

Because rule bodies live in the corpus, rule authorship is a memory operation, not a code-edit operation.  Adding a new pre-commit rule is `mcp__sandbar__sandbar_entity_create :class :mm/Rule {...}` plus a body — the next `pre-commit` event picks it up via the BM25F retrieval, no redeploy.

### Engine pluggability

The engine fit per rule shape:

| Rule shape                                  | Engine            | Why                                                              |
|---------------------------------------------|-------------------|------------------------------------------------------------------|
| Multi-fact inference / working-memory + TMS | `:clara-rules`    | Rete network; truth-maintenance; accumulators                    |
| Bidirectional / unification / backtracking  | `:core.logic`     | miniKanren-shaped logic programming                              |
| Required-slot / cardinality / regex         | `:malli`          | Schema validation is what Malli is for                           |
| Substrate-resident derivation               | `:datalog`        | Native Datomic recursive Datalog rules; Layer 0 entailment       |
| Term rewriting / pattern transformation     | `:meander`        | Strategy-decoupled rule application                              |
| Single-pattern match (regex / equality)     | `:plain-pattern`  | No engine needed; rule body IS the match shape                   |

The substrate accepts the diversity — the rule body's `:dt.fn/lang` declares which engine consumes it.  Multiple engines coexist; each rule picks the engine that fits its body shape; the dispatch layer routes accordingly.

## Layered architecture

Rules naturally decompose into four architectural layers per the layered-rule-subsystem arc.  Each layer has distinct concerns, lifecycles, audiences, and engine fits.

### Layer 0 — substrate RDFS entailment

The foundational layer: substrate rules that derive new edges from existing edges plus class / property characteristics.  These are the RDFS entailment rules (rdfs1–rdfs13) plus a small selection of OWL 2 RL rules (subclass + sub-property + inverse-of + transitivity).

```
| RDFS rule                | Shape                                                | Datomic primitive          |
|--------------------------|------------------------------------------------------|----------------------------|
| rdfs2 (domain → type)    | (p domain C) ∧ (x p y) → (x type C)                  | Datalog rule               |
| rdfs3 (range → type)     | (p range C) ∧ (x p y) → (y type C)                  | Datalog rule               |
| rdfs5 (subPropOf trans.) | (p1 sub p2) ∧ (p2 sub p3) → (p1 sub p3)              | Recursive Datalog rule     |
| rdfs7 (sub-prop entail.) | (p sub q) ∧ (x p y) → (x q y)                        | Datalog rule               |
| rdfs9 (subCls instance)  | (C1 sub C2) ∧ (x type C1) → (x type C2)              | Recursive Datalog rule     |
| rdfs11 (subCls trans.)   | (C1 sub C2) ∧ (C2 sub C3) → (C1 sub C3)              | Recursive Datalog rule     |
| inverse-of (OWL)         | (p inv q) ∧ (x p y) → (y q x)                        | Datalog rule + transactor  |
| transitive (OWL)         | (p type Transitive) ∧ (x p y) ∧ (y p z) → (x p z)    | Recursive Datalog rule     |
| symmetric (OWL)          | (p type Symmetric) ∧ (x p y) → (y p x)               | Datalog rule               |
| inverse-functional (OWL) | (p type IFP) ∧ (x p z) ∧ (y p z) → (x sameAs y)      | Datalog rule + integrity   |
```

Architectural insight: every characteristic the corpus already declares on a predicate memorial (`characteristics: [transitive]`, `inverse-of: ...`) is information that COMPILES to a native Datomic Datalog rule.  Layer 0 rules are sandbar-internal — they live in substrate code (`sandbar.db.entailment`), not as user-authored `:mm/Rule` memorials.  These are substrate-level invariants, not behavioral rules; authoring them as memorials would conflate substrate machinery with user-domain content.

### Layer 1 — user-domain memorial invariants

Rules that constrain corpus memorials per type.  These are the natural target for `:mm/Rule` memorials.  Examples from the standing corpus:

- *"Every `:mm/AntiPattern` must `cites:` at least one `:mm/Pattern`"* — load-bearing positive-alternative discipline
- *"Every `:mm/Decision` should have a `## Alternatives` section"* — body-shape invariant
- *"Every `:mm/Plan` requires `status:` field"* — required-slot invariant
- *"`:mm/Example` requires `demonstrates:` edge to at least one `:mm/Pattern`"* — typed-edge requirement

Engine fit at Layer 1: Malli for required-slot checks; plain-pattern with markdown AST for body-section presence; Datalog query for cross-memorial relations; Clara when working-memory + TMS warrant the machinery.

Layer 1 rules compose with Layer 0 entailment: if Layer 0 derives `(x type :mm/Decision)` via subclass entailment, Layer 1 rules constraining `:mm/Decision` instances see the derived facts.  This is the architectural payoff of layering — Layer 0 changes propagate UP via entailment; Layer 1 changes don't propagate DOWN.

### Layer 2 — codec / projection / MCP boundary

Boundary rules at the layer between filesystem-canonical markdown and the substrate entity graph (codec) and between substrate and external consumers (MCP).

- *"Codec round-trip preserves byte-stable output"* — the parse → emit cycle is idempotent
- *"MCP tool-call response shape conforms to MCP spec"* — boundary validation
- *"Projection groups entities by `:mm/Memory` subclass per inheritance"* — projection invariant
- *"Frontmatter slot ordering follows class-declared canonical order"* — emit invariant

Layer 2 is mostly sandbar-internal: these are boundary-layer invariants enforced by codec / projection / MCP machinery, not authored as rule memorials.  Where rules do live (e.g. per-class emit invariants), they're typically attached to the class memorial via shape rather than authored as standalone rules.

### Layer 3 — client-application rules

Per-engagement behavior rules — the layer where consumers extend the substrate with their own discipline.  Examples:

- *"In the junior-engineer context, `:mm/Decision` requires `:mm.decision/compliance-reference` slot"* — client-extended class shape
- *"For actor `actors/dan` in `contexts/sandboxed-work`, the pre-tool-use event triggers proprietary-token firewall"* — actor-context-event tuple
- *"PreToolUse for git commit verifies authorization memorial exists"* — discipline-enforcement
- *"Session-start surfaces the keystone TIER-0 discipline rules"* — at-startup-visibility surface

Layer 3 rules ARE authored as `:mm/Rule` memorials at `memory/rules/<slug>.md`.  Engine fit: clara-rules / core.logic / Malli per body shape.  Scope: project + (actor, context) tuples.

Each layer evolves independently.  Layer 0 changes propagate UP (via entailment).  Layer 3 changes don't propagate DOWN.  Backend changes (filesystem-walker → DataScript → Datomic) don't propagate UP.  Engine changes (Clara → core.logic) don't propagate DOWN.  Architectural boundaries support scale and flexibility.

## Conflict resolution

Initial implementation: numeric `:mm.rule/priority` (manual; deterministic; the Drools salience pattern).  Within a scope tuple `(actor, context, event)`, rules fire in priority order; ties broken by lexicographic rule-name.

Architectural room left for ACT-R utility-weighted learning (rule activations log their outcomes; utility-update can layer on top of the rule registry without changing the storage substrate).  All-fire (CLOS `progn`) and first-match (core.match) were considered and rejected as initial defaults — the former conflicts across applicable rules without adjudication; the latter loses parallel applicability.

## Scope partitioning — Cyc microtheory lattice

Rules scope to `(actor, context)` tuples.  Contexts form a multi-parent lattice (not a strict tree).  Cross-cutting concerns supported.  Contradictions tolerated across sibling scopes.  Lifting rules explicitly translate cross-context applicability when needed.

This is the Cyc microtheory pattern directly.  The Cyc `ist(Mt, P)` predicate that reifies (rule, scope) as a first-class assertion corresponds to Sandbar's typed-edge representation: a `:mm/Rule` instance has `:mm.rule/context` ref edges to one or more `:mm/Context` memorials; the lattice is the transitive closure of `:mm.context/subcontext-of` edges; contradictions are tolerated because the substrate is closed-world per scope tuple, not globally.

PowerLoom's strict-tree inheritance was considered and rejected for the same reason it was rejected for the substrate microtheory model: too restrictive for cross-cutting disciplines.  A discipline like *"no commit signing"* applies across multiple Dan-context children; multi-parent lattice supports it natively.

## Selection compiles to core.match decision tree

The rule registry is a pattern matrix over `(actor, context, event)` columns.  Selection compiles at session-start via Maranget's algorithm into a near-constant-time decision tree.  Specialization narrows the matrix to the active scope; guards encode preconditions.

This is core.match (Nolen 2010, after Maranget 2008) used as the rule-dispatch compiler.  The architectural property: rule selection cost is O(log n) on the active scope, not O(n) over the full rule registry.  Adding a rule does not slow dispatch; the compiler reshapes the decision tree at session-start.

## Discovery — querying the rule substrate

Because rules are first-class memorials AND first-class functions, the standard substrate operations apply.

### Find all rules applicable to a context

```
mcp__sandbar__sandbar_class_instances
  :class :mm/Rule

mcp__sandbar__sandbar_search_attribute
  :slot :mm.rule/context
  :value :contexts/sandboxed-work
```

### Walk the bypass graph

```
mcp__sandbar__sandbar_navigate_outbound-edges
  :entity <rule-eid>
  :predicate :mm.rule/bypass
```

Returns the set of `(actor, context)` tuples exempt from this rule.  Useful when auditing whether a rule's coverage holds across the deployment matrix.

### Find rules by engine

```
mcp__sandbar__sandbar_aggregate_group-by
  :class :mm/Rule
  :slot :mm.rule/engine
```

Returns counts by engine — `:clara-rules N1 :datalog N2 :plain-pattern N3 :malli N4 :core.logic N5 :meander N6`.  Useful for engine-fit auditing.

### Walk the supersession chain

```
mcp__sandbar__sandbar_navigate_inbound-edges
  :entity <retired-rule-eid>
  :predicate :dt.fn/superseded-by
```

Returns the newer rule(s) that supersede this one.  Because `:mm/Rule` inherits `:dt.fn/version` and `:dt.fn/superseded-by` from `:mm/Fn`, rule versioning is the same shape as function versioning.

## Composition with Shape — rule fires; shape constrains

Rules and shapes compose at the workflow-transition boundary.

A `:mm/Workflow` transition can carry both:

- A `:mm/Shape` reference — declarative gate; validates the pre-transition entity state
- A `:mm/Rule` reference — procedural gate; fires custom logic at the transition point

The shape says *"does this entity look right?"*  The rule says *"given the actor, context, and event, what should happen now?"*  Together they form the gate.

This is the SHACL-AF pattern again: shapes are declarative; rules / functions are computational; they compose by reference, not by subtype.  See [`shape-validation.md`](shape-validation.md) for the shape substrate; see [`workflow-substrate.md`](workflow-substrate.md) for the transition surface.

## Comparison with adjacent approaches

### vs. Clara / OPS5 / Drools used standalone

Standalone clara-rules gives you a rule engine.  Sandbar embeds clara as one of several pluggable engines under the `:mm.rule/engine` discriminator.  Rule bodies that are clara-shaped (`{:lhs [...] :rhs ...}`) compile to clara natively; rule bodies that aren't run on the engine that fits.  The substrate-level commitment is not to clara as an engine but to *rules as first-class typed citizens* with engine routing as a deployment concern.

### vs. clojure.spec and Malli used standalone

clojure.spec and Malli give you schema-shaped declarative validation.  Sandbar treats those as engines for rule bodies whose shape is *"validate this entity against this schema"* — Malli specifically lives under `:mm.rule/engine :malli`.  The rule's dispatch-spec (actor / context / event) carries the activation policy; the body carries the Malli schema.

### vs. SHACL CONSTRUCT rules

SHACL Advanced Features `sh:rule` declares production rules over RDF graphs — given pattern, construct new triples.  Sandbar's `:dt/Rule` with constrained signature is the direct analogue — body returns inference data (the constructed triples).  The dispatch-spec slots extend SHACL's target-shape model with activation-time discrimination.

### vs. ESLint / clj-kondo rule sets

ESLint and clj-kondo treat rules as plugin-installed JavaScript / Clojure functions.  Sandbar's rules are substrate-resident — discoverable through BM25F, navigable through typed-edge graph walks, version-controlled through git, authored through MCP.  An ESLint rule lives in `node_modules`; a Sandbar rule lives in `memory/rules/`.

### vs. ContextL / CLOS method combinations

ContextL gives you context-oriented dispatch on methods; CLOS gives you `:before` / `:after` / `:around` method qualifiers.  Sandbar's `:mm.rule/context` slot is the ContextL pattern at the substrate level; `:dt.fn/qualifier` (inherited from `:mm/Fn`) carries the CLOS qualifier semantics.  Both compose with the multi-axis dispatch-spec.

### vs. Cursor rules / CLAUDE.md / Claude Code hooks

The modern AI-tooling generation provides frontmatter-as-attachment-policy with rule body.  Sandbar's `:mm/Rule` memorial is the substrate-level synthesis of that pattern, normalized to the typed-edge graph + BM25F retrieval substrate.  A Cursor rule lives in `.cursor/rules/*.mdc`; a Sandbar rule lives in `memory/rules/<slug>.md` — same frontmatter convention, broader substrate.

## Operational consequences

The substrate-level investment in first-class rule typing on top of first-class function typing pays out in six places.

**Authorability without code change.**  A new rule is a markdown commit (when body shape is declarative) or a `defdbfn` plus dispatch-spec (when body shape needs Clojure).  Either path puts a `:mm/Rule` memorial in the substrate; the next event of the matching shape picks it up.

**Discoverability via BM25F + typed-edge.**  *"What rules apply to a pre-commit event for actor `actors/dan` in context `contexts/sandboxed-work`?"* is one MCP call.  Before the arc, the same question required multi-namespace grep.

**Engine pluggability without rule rewrite.**  A rule's `:mm.rule/engine` is data; the dispatch layer routes accordingly.  Migrating a rule from `:plain-pattern` to `:clara-rules` when its complexity warrants the engine is a slot update, not a rewrite.

**Scope navigation via typed-edge graph.**  `:mm.rule/context` is a typed-edge ref; the context lattice is the transitive closure of `:mm.context/subcontext-of`; scope queries compose with standard navigation calls.

**Version-chain coherence.**  `:dt.fn/version` and `:dt.fn/superseded-by` inherited from `:mm/Fn` give rule versioning the same shape as function versioning — typed-edge chain, navigable through MCP.

**Conflict resolution as policy data.**  `:mm.rule/priority` is a slot; the within-scope ordering is computable from substrate state.  ACT-R-style utility learning can layer on top by writing the same slot from outcome telemetry — no engine refactor.

## References

**Production rule systems**

- Forgy, C.L. (1981). *OPS5 User's Manual.* Carnegie Mellon University Technical Report CMU-CS-81-135.
- Forgy, C.L. (1982). *Rete: A Fast Algorithm for the Many Pattern/Many Object Pattern Match Problem.* Artificial Intelligence, 19(1), 17–37.
- Riley, G. (1985–). *CLIPS Reference Manual.* NASA Johnson Space Center.
- Friedman-Hill, E. (2003). *Jess in Action: Java Rule-Based Systems.* Manning.
- Browne, P. (2009). *JBoss Drools Business Rules.* Packt Publishing.

**Logic programming and propagators**

- Colmerauer, A. & Roussel, P. (1972). *The Birth of Prolog.* Communications of the ACM.
- Sussman, G.J. & Steele, G.L. (1980). *CONSTRAINTS — A Language for Expressing Almost-Hierarchical Descriptions.* Artificial Intelligence, 14(1), 1–39.
- Friedman, D.P., Byrd, W.E. & Kiselyov, O. (2005). *The Reasoned Schemer.* MIT Press.
- Nolen, D. (2010). *core.logic.* https://github.com/clojure/core.logic
- Hewitt, C., Bishop, P. & Steiger, R. (1973). *A Universal Modular ACTOR Formalism for Artificial Intelligence.* IJCAI 1973.

**Cyc microtheories and PowerLoom**

- Lenat, D.B. & Guha, R.V. (1990). *Building Large Knowledge-Based Systems.* Addison-Wesley.
- Chalupsky, H. & MacGregor, R.M. (1999). *PowerLoom Manual.* USC/ISI.

**SHACL**

- Knublauch, H. & Kontokostas, D. (2017). *Shapes Constraint Language (SHACL).* W3C Recommendation.  https://www.w3.org/TR/shacl/
- Knublauch, H. & Allemang, D. (2017). *SHACL Advanced Features.* W3C Working Group Note.  https://www.w3.org/TR/shacl-af/

**Method combinations and context-oriented programming**

- Bobrow, D.G. et al (1988). *Common Lisp Object System Specification.* ANSI X3J13.
- Costanza, P. & Hirschfeld, R. (2005). *Language Constructs for Context-Oriented Programming.* DLS 2005.

**Cognitive architectures (utility-learning lineage)**

- Anderson, J.R. (1993). *Rules of the Mind.* Lawrence Erlbaum.
- Newell, A. (1990). *Unified Theories of Cognition.* Harvard University Press. (Soar)

**Rule-strategy decoupling**

- Visser, E. (2001). *Stratego: A Language for Program Transformation Based on Rewriting Strategies.* RTA 2001.
- Long, J. (2019–). *Meander — Term Rewriting for Clojure.* https://github.com/noprompt/meander

**Pattern matching compilation**

- Maranget, L. (2008). *Compiling Pattern Matching to Good Decision Trees.* ML 2008.
- Nolen, D. (2010). *core.match.* https://github.com/clojure/core.match

## See also

- [`first-class-fn.md`](first-class-fn.md) — `:mm/Rule` is `:dt/subclass-of :mm/Fn`; inherits the full function substrate
- [`shape-validation.md`](shape-validation.md) — `:mm/Shape` is the declarative-constraint cousin; shapes and rules compose at workflow-transition gates
- [`metamodel.md`](metamodel.md) — `:dt/Rule` is a subtype of `:dt/Fn` at the substrate level; constrained signature per the Datomic posture
- [`memory-model.md`](memory-model.md) — `:mm/Rule` is a memorial type; `:dt/memorial-policy :first-class` governs FS projection to `memory/rules/`
- [`workflow-substrate.md`](workflow-substrate.md) — workflow transitions can carry both shape gates (declarative) and rule gates (procedural)
- [`projection.md`](projection.md) — the reactive sink that gives `:mm/Rule` memorials their FS face
- [`mcp-protocol.md`](mcp-protocol.md) — rule operations through the MCP surface
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) — rule + workflow verb catalog
- [`doc/guides/authoring-rules.md`](../guides/authoring-rules.md) — hands-on rule-authoring walkthrough
