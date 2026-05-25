# Shape Validation

> `:mm/Shape` is Sandbar's SHACL-aligned declarative-constraint memorial — a first-class citizen of the memory model that declares which class to validate, which properties are required, what cardinalities apply, what regex patterns must match, what datatypes are expected, whether the entity may carry extra slots, and how severe each failure is.  The walker is implemented as a family of small `:mm/Fn` instances composed under the abstract-interpreter (Cousot & Cousot 1977) framing — declarative constraints are abstract-domain invariants; the walker fns evaluate them.  Two modes — `:strict` (rejects on `:violation`-severity failure) and `:audit` (returns all results; caller decides) — govern boundary integration.  The substrate is self-validating: meta-shapes target `:dt/Class`, `:dt/Property`, `:dt/Fn`, `:mm/Memory`, and `:mm/Shape` itself.  For the function substrate the walker is built on, see [`first-class-fn.md`](first-class-fn.md); for rules that ride the same fn substrate with dispatch-spec, see [`first-class-rule.md`](first-class-rule.md); for the metacircular metamodel underneath both, see [`metamodel.md`](metamodel.md).

## Thesis

Constraints are declarative knowledge worth keeping.

The naive shape buries validation inside the code that creates entities.  A per-class `validate-decision`, `validate-plan`, `validate-library` — each a function with hard-coded checks, each invoked at one or two specific entry points, each invisible to the rest of the substrate.  No way to ask *"what constraints apply to a `:mm/Decision`?"* without grepping the validation namespace.  No way to amend a constraint without a code change.  No way for an AI agent to discover, read, or propose new constraints through the same retrieval surface that surfaces everything else.

Sandbar's commitment: **a constraint is a typed citizen of the substrate, addressable as data, validated by first-class functions, mutable through the same MCP surface as any other memorial.**  A `:mm/Shape` instance is a memorial — readable, BM25F-searchable, FS-projected, version-controlled, hook-retrievable — that declares constraints over a target class.  The walker that interprets the shape against an entity is itself a family of `:mm/Fn` instances, composed under a Cousot-Cousot abstract-interpretation framing.  And the substrate's own primitives (`:dt/Class` / `:dt/Property` / `:dt/Fn` / `:mm/Memory` / `:mm/Shape` itself) carry shapes that validate them — the metamodel validates itself with its own machinery.

This is SHACL implemented from the substrate up, not as a wrapped external library.  Shapes are first-class memorials.  Walker fns are first-class `:mm/Fn` memorials.  Validation modes are queryable.  Conformance reports are structured data.  The closure of the self-description loop lands here.

## Lineage

### SHACL Core (W3C 2017)

The Shapes Constraint Language (Knublauch & Kontokostas 2017) is the W3C recommendation for declarative validation over RDF graphs.  Its central abstractions:

- **`sh:NodeShape`** declares which nodes to validate.
- **`sh:property`** attaches property-level constraints.
- **`sh:minCount` / `sh:maxCount`** declare cardinality.
- **`sh:datatype`** declares expected literal type.
- **`sh:pattern`** declares regex constraint on string values.
- **`sh:closed`** declares that no additional properties are allowed.
- **`sh:severity`** declares constraint severity (`sh:Violation` / `sh:Warning` / `sh:Info`).
- **Conformance reports** are themselves RDF, structured per the spec.

Sandbar mirrors SHACL Core wholesale.  The translation is direct:

| SHACL Core              | Sandbar `:mm/Shape`                            | Notes                                                  |
|-------------------------|------------------------------------------------|--------------------------------------------------------|
| `sh:NodeShape`          | `:mm/Shape`                                    | The shape memorial itself                              |
| `sh:targetClass`        | `:mm.shape/applies-to`                         | Reference to the target `:dt/Class`                    |
| `sh:property`           | `:mm.shape/required-property`                  | Cardinality-many ref to required `:dt/Property`        |
| `sh:minCount`/`sh:maxCount` | `:mm.shape/cardinality-constraints`        | Sub-entity per property                                |
| `sh:pattern`            | `:mm.shape/pattern-constraints`                | Sub-entity per property                                |
| `sh:datatype`           | `:mm.shape/datatype-constraints`               | Sub-entity per property                                |
| `sh:closed`             | `:mm.shape/closed?`                            | Boolean                                                |
| `sh:severity`           | `:mm.shape/severity`                           | `:violation` / `:warning` / `:info`                    |
| `sh:Violation`          | `:violation`                                   | Reject in `:strict` mode                               |
| `sh:Warning`            | `:warning`                                     | Log but accept                                         |
| `sh:Info`               | `:info`                                        | Informational only                                     |
| `sh:ValidationReport`   | conformance report (structured map)            | Returned by `sandbar.shape/validate`                   |

Where SHACL has it, Sandbar has it.  Where Sandbar exceeds the SHACL Core surface, the additions slot in alongside as siblings — never replacements.

### SHACL Advanced Features `sh:SPARQLFunction` (W3C 2017)

SHACL-AF (Knublauch & Allemang 2017) introduced `sh:SPARQLFunction` as the computational counterpart to declarative shapes.  Custom constraint logic that cannot be expressed via core SHACL primitives is implemented as a `sh:SPARQLFunction` and referenced from the shape.

Sandbar inherits the architectural separation directly: declarative shapes (`:mm/Shape`) reference computational functions (`:mm/Fn`) by ref, never by subtype.  The seam is the `:mm.shape/validator-fn` slot:

```clojure
{:db/ident       :mm.shape/validator-fn
 :db/valueType   :db.type/ref
 :dt/range       :mm/Fn
 :db/cardinality :db.cardinality/one
 :db/doc         "Optional ref to a :mm/Fn that implements walker logic for this
                  shape.  When present, sandbar.shape.validate dispatches through
                  this fn rather than the built-in walker.  Per SHACL-AF
                  sh:SPARQLFunction composition pattern."}
```

Plus `:mm.shape/value-constraints` (cardinality-many ref to `:mm/Fn` instances) for per-slot custom validators.  See [`first-class-fn.md`](first-class-fn.md) for the `:mm/Fn` substrate.

### Cousot & Cousot abstract interpretation (1977)

Patrick and Radhia Cousot's abstract interpretation framework (POPL 1977) gave us the formal account of static analysis: an abstract domain that approximates a concrete domain; an abstraction function that lifts concrete states to abstract states; sound and complete transfer functions that compose under the abstract interpreter to over- or under-approximate concrete-domain behavior.

The Sandbar shape walker IS an abstract interpreter in this exact sense:

- **Concrete domain** — dynamic properties of the substrate (the actual content of memorials; the actual behavior under workload; the actual retrieval semantics).
- **Abstract domain** — the entity's slot values + ref-graph viewed statically, without running it through any workload.
- **Abstraction function** — the projection `entity → {frontmatter / body / links / tags / types / outbound-edges / inbound-edges}` that loses dynamic information but preserves structural invariants.
- **Shape constraints** — invariants over the abstract domain that *soundly approximate* properties we care about in the concrete domain.
- **Walker = interpreter** — `:mm/Fn` instances evaluate each constraint against the abstract view per `(entity, shape)` pair.

The framework gives us **sound** and **complete** notions:

- `:violation`-severity shapes should be **sound** (no false negatives — never says "clean" when the concrete domain is dirty).
- `:warning`-severity shapes may admit false positives (over-approximation acceptable; the cost is human attention, not lost data).
- `:info`-severity shapes are fully informational.

This is more than a metaphor.  The walker code in `sandbar.shape` is structured as a family of per-constraint check functions composed by a top-level interpreter that aggregates results — exactly the abstract-interpretation architectural pattern.  See `memory/observations/shape_walker_is_abstract_interpreter.md` for the formal-CS framing.

### Description logics and OWL DL (for contrast)

OWL DL (Hitzler et al 2012) sits one level up in expressive power: existential restrictions, disjoint classes, full description-logic reasoning with sound and complete tableau procedures.  Sandbar deliberately stops short.  The cost of OWL DL validation is a reasoner; the value of stopping is that closed-world conformance reports are immediate and the runtime is the Datomic runtime, not a separate inference layer.

The Cousot-Cousot framing names *why* the stop is principled: Sandbar's shape walker is an abstract interpreter over the abstract domain of static frontmatter + graph + types.  Full description-logic reasoning would require a richer abstract domain (existential subgraph quantifiers, model-theoretic equality reasoning); the trade is reasoning power for predictability and Datomic-native execution.

### Malli and clojure.spec (the Clojure-side precedents)

Malli (Metosin 2019–) and clojure.spec (Hickey 2016) brought schema-shaped validation to Clojure.  Both treat schemas as data; both compose declaratively; both produce structured failure reports.  Sandbar's shape design takes the spirit but lands on substrate primitives rather than runtime-only schemas — a `:mm/Shape` is a memorial that persists, projects, and propagates through the substrate; a Malli schema typically lives in a namespace.  When custom logic is warranted, `:mm.shape/validator-fn` can reference a `:mm/Fn` whose body *is* a Malli schema; the seam is graceful.

## The substrate

### The `:mm/Shape` slot vocabulary

A shape is a memorial — under `:mm/Memory` / `:mm/Meta` — with the canonical memorial slots (`:mm.memory/name`, `:mm.memory/description`, `:mm.memory/rel-path`, `:mm.memory/created`, etc.) plus shape-specific structure:

| Slot                                  | Meaning                                                                                       |
|---------------------------------------|-----------------------------------------------------------------------------------------------|
| `:mm.shape/shape-id`                  | Stable identifier (e.g. `:sandbar.shape/decision-shape`)                                       |
| `:mm.shape/applies-to`                | Reference to the target `:dt/Class` (the class whose instances this shape validates)          |
| `:mm.shape/description`               | RDFS-comment-like prose; what this shape asserts and why                                       |
| `:mm.shape/required-property`         | Cardinality-many ref to `:dt/Property` — properties the instance MUST carry                   |
| `:mm.shape/cardinality-constraints`   | Cardinality-many ref to `:mm.shape/CardinalityConstraint` sub-entities                         |
| `:mm.shape/pattern-constraints`       | Cardinality-many ref to `:mm.shape/PatternConstraint` sub-entities                             |
| `:mm.shape/datatype-constraints`      | Cardinality-many ref to `:mm.shape/DatatypeConstraint` sub-entities                            |
| `:mm.shape/closed?`                   | Boolean — if `true`, instance may not carry properties beyond those declared by the shape     |
| `:mm.shape/severity`                  | `:violation` / `:warning` / `:info` — drives `:strict` mode rejection                          |
| `:mm.shape/scope`                     | `:abox` (default; validates instance data) / `:tbox` (validates class defs) / `:rbox` (validates property declarations) |
| `:mm.shape/validator-fn`              | Optional ref to a `:mm/Fn` that implements walker logic; SHACL-AF `sh:SPARQLFunction` analogue |
| `:mm.shape/value-constraints`         | Cardinality-many ref to `:mm/Fn` instances; per-slot value-constraint fns                     |

Three sub-entity classes carry the property-targeted constraints:

```clojure
{:db/ident :mm.shape/CardinalityConstraint
 :dt/slots [:mm.shape.cardinality/property      ; ref to :dt/Property
            :mm.shape.cardinality/min           ; integer; default 0
            :mm.shape.cardinality/max]}         ; integer; -1 = unbounded

{:db/ident :mm.shape/PatternConstraint
 :dt/slots [:mm.shape.pattern/property          ; ref to :dt/Property
            :mm.shape.pattern/regex             ; string
            :mm.shape.pattern/flags]}           ; optional Java regex flag chars

{:db/ident :mm.shape/DatatypeConstraint
 :dt/slots [:mm.shape.datatype/property         ; ref to :dt/Property
            :mm.shape.datatype/expected-datatype]}  ; :db.type/* keyword or :dt/Class ref
```

The sub-entities are themselves substrate citizens — queryable, validatable, but pinned to their parent shape via the parent's cardinality-many slots.  They have no independent identity outside the shape that references them.

### The walker family

The validation walker is implemented as a family of small `:mm/Fn` instances in the `sandbar.shape` namespace, each authored via `defdbfn` with `:dt.fn/installed-as :classpath-fn` (peer-side; needs full Clojure environment), `:dt.fn/purpose :validate`, and `:dt.fn/purity :pure-total` (pure of effects under nominal conditions).

| Check fn                  | Constraint kind                                                            |
|---------------------------|----------------------------------------------------------------------------|
| `check-required-property` | Verifies entity carries every property in `:mm.shape/required-property`    |
| `check-cardinality`       | Verifies entity respects all `:mm.shape/cardinality-constraints` (min/max) |
| `check-pattern`           | Verifies string-valued properties match `:mm.shape/pattern-constraints`    |
| `check-datatype`          | Verifies property values conform to `:mm.shape/datatype-constraints`       |
| `check-closed`            | If `:mm.shape/closed?`, verifies no slot beyond those declared             |
| `check-validator-fn`      | If `:mm.shape/validator-fn` is set, resolves + invokes the custom fn       |

Each check fn returns a uniform shape:

```clojure
;; Pass:
{:status :pass :check :required-property}

;; Fail:
{:status              :fail
 :check               :required-property
 :missing-properties  #{:mm.memory/cites}
 :severity            :violation}
```

The top-level `walk-entity` composes the per-check results into a per-(entity, shape) conformance result.  The batch `conformance-report` walks all instances of a class against all applicable shapes and aggregates the structured report.  Each composer is itself a `:dt/Fn` instance with declared `:dt.fn/purpose` (`:validate` for `walk-entity`; `:derive` for `conformance-report`).

Adding a new constraint kind is the same shape — author a `defdbfn` check, add the constraint sub-entity class, register the new check in `walk-entity`'s composition chain.  The substrate accepts the addition through the same MCP surface that accepts any new function.

### Validation modes

Three modes govern the boundary integration:

| Mode       | Behavior                                                                          |
|------------|-----------------------------------------------------------------------------------|
| `:strict`  | Throws `ex-info` on the first `:violation`-severity failure; `entity.create` / `entity.update` reject. |
| `:audit`   | Returns the full result vector; the caller decides what to do.  Default for bulk-load ops. |
| `:disabled`| Skips validation; returns `[]`.  Opt-in escape hatch.                              |

Mode is a *parameter* of the validation call, not a property of the shape.  The same shape applies in both modes — what differs is whether a failure rejects or merely reports.

## Authoring a shape

A shape memorial at `memory/shapes/decision-shape.md`:

```markdown
---
name: decision-shape
description: Shape constraints for :mm/Decision instances — required cites, required body sections, required frontmatter slots.
type: shape
shape-id: ":sandbar.shape/decision-shape"
applies-to: :mm/Decision
severity: violation
scope: abox
closed?: false
---

## What this shape asserts

Every `:mm/Decision` memorial must carry:

- At least one `:mm.memory/cites` reference (decisions sit in the citation graph;
  citation-less decisions are an orientation hazard)
- A `:mm.memory/name`, `:mm.memory/description`, and `:mm.memory/created` slot
- A `## Context`, `## Decision`, `## Alternatives`, and `## See also` section in the body

## Required properties

- `:mm.memory/cites` (cardinality min 1)
- `:mm.memory/name`
- `:mm.memory/description`
- `:mm.memory/created`

## Cardinality constraints

```edn
[{:mm.shape.cardinality/property :mm.memory/cites
  :mm.shape.cardinality/min      1
  :mm.shape.cardinality/max      -1}]
```

## Pattern constraints

```edn
[{:mm.shape.pattern/property :mm.memory/rel-path
  :mm.shape.pattern/regex    "^decisions/.*\\.md$"}]
```

## See also

- `memory/types/decision.md` — the `:mm/Decision` type description
- `memory/decisions/discipline_visibility_at_startup_2026_05_07.md` — exemplar of a conformant decision
```

This file is what humans read.  When written, the codec parses; the substrate ingests; a `:mm/Shape` entity commits.  The reactive sink (per [`projection.md`](projection.md)) writes the file back when the entity mutates — round-trip stability holds.

Alternatively, the shape may be authored directly through MCP:

```
mcp__sandbar__sandbar_shape_create
  :slots {:mm.shape/shape-id       :sandbar.shape/decision-shape
          :mm.shape/applies-to     :mm/Decision
          :mm.shape/description    "Shape constraints for :mm/Decision..."
          :mm.shape/required-property [:mm.memory/cites :mm.memory/name ...]
          :mm.shape/severity       :violation
          :mm.shape/closed?        false
          :mm.memory/rel-path      "shapes/decision-shape.md"
          :mm.memory/name          "decision-shape"}
```

Either path commits the same entity.  The reactive sink then projects the markdown file if it was DB-authored, or the codec parses if it was FS-authored.

## Validating an entity

The MCP verb surface exposes five shape operations.

### `sandbar.shape.list`

List all shapes; optional filter by target class.

```
mcp__sandbar__sandbar_shape_list
  :applies-to :mm/Decision
```

Returns shape memorials targeting `:mm/Decision`.

### `sandbar.shape.validate`

Validate a single entity against the shapes applicable to its class.

```
mcp__sandbar__sandbar_shape_validate
  :entity <decision-eid>
  :mode :audit
```

Returns a vector of `walk-entity` results — one per applicable shape.  In `:strict` mode, throws on the first `:violation`-severity failure; in `:audit` mode (the default for batch operations), returns all results for caller-side resolution.

A pass:

```clojure
[{:status        :pass
  :entity        17592186045432
  :shape         17592186045433
  :checks-passed 6}]
```

A failure:

```clojure
[{:status   :fail
  :entity   17592186045432
  :shape    17592186045433
  :failures [{:status              :fail
              :check               :required-property
              :missing-properties  #{:mm.memory/cites}
              :severity            :violation}
             {:status                 :fail
              :check                  :cardinality
              :cardinality-violations [{:property :mm.memory/cites
                                        :count    0
                                        :min      1
                                        :max      -1}]
              :severity               :violation}]}]
```

### `sandbar.shape.conformance-report`

Batch conformance over every instance of a class.

```
mcp__sandbar__sandbar_shape_conformance_report
  :class :mm/Decision
```

Returns:

```clojure
{:class           :mm/Decision
 :instance-count  142
 :shape-count     3
 :total-checks    426
 :passes          418
 :failures        8
 :error-count     5
 :warning-count   3
 :failure-details [<walk-result>...]}
```

This is the corpus-level health pass — the operational equivalent of *"are all 142 decisions structurally sound under their declared shapes?"*

### `sandbar.shape.create` and `sandbar.shape.update`

Create a new shape or amend an existing one through the MCP surface.  Both go through `sandbar.entity.create` / `update` which runs shape validation against the meta-shape that validates `:mm/Shape` itself — see *Self-validation*, below.

## Boundary integration — `entity.create` and `entity.update`

The validation seam at the substrate boundary is `sandbar.entity.create` / `update`.  After the legacy class-required-slot check, the code:

1. Resolves the target class from `:dt/type`.
2. Queries `:mm/Shape` instances with `:mm.shape/applies-to` matching the target class.
3. Invokes `walk-entity` for each applicable shape.
4. Decides — based on mode — whether to reject (`:strict`) or accept-with-report (`:audit`).

```clojure
(defn validate
  "Public entry: validate an entity against the shapes applicable to its class.
   Returns a vector of walk-entity results (one per applicable shape).

   `mode` ∈ #{:strict :audit :disabled}:
     - :strict — throw ex-info on first :violation-severity failure
     - :audit  — return all results; let caller decide
     - :disabled — skip validation entirely (no-op; returns [])"
  ([db entity-eid] (validate db entity-eid :audit))
  ([db entity-eid mode]
   (if (= mode :disabled)
     []
     (let [entity      (d/entity db entity-eid)
           class-ident (:db/ident (:dt/type entity))
           shape-eids  (when class-ident
                         (d/q '[:find [?s ...]
                                :in $ ?cls
                                :where [?s :mm.shape/applies-to ?cls]]
                              db class-ident))
           results    (when (seq shape-eids)
                        (mapv #(walk-entity db entity-eid %) shape-eids))
           violations (filter
                        (fn [r]
                          (and (= :fail (:status r))
                               (some #(= :violation (:severity %)) (:failures r))))
                        (or results []))]
       (when (and (= mode :strict) (seq violations))
         (throw (ex-info "Shape-validation failed (strict mode)"
                         {:entity     entity-eid
                          :class      class-ident
                          :violations (vec violations)})))
       (or results [])))))
```

Default mode at the `entity.create` / `update` boundary is `:strict` (substrate rejects malformed entities).  Default mode at `project.import` (bulk corpus load) is `:audit` (log violations but accept; surfaces existing-corpus drift without blocking the load).  The escape hatch `:disabled` is opt-in only.

## Self-validation — the metacircular closure

The most consequential payoff: **the substrate validates itself with its own machinery.**  Sandbar ships a seed catalog of meta-shapes that target its own primitives:

| Shape                          | Target                | What it asserts                                                          |
|--------------------------------|-----------------------|--------------------------------------------------------------------------|
| `:sandbar.shape/class-shape`   | `:dt/Class`           | Required `:dt/subclass-of` (single root `:dt/Resource`); slot coherence  |
| `:sandbar.shape/property-shape`| `:dt/Property`        | Required `:dt/domain` + `:dt/range`; type coherence                       |
| `:sandbar.shape/fn-shape`      | `:dt/Fn` / `:mm/Fn`   | Required `:dt.fn/parameters` + `:dt.fn/return-type` + `:dt.fn/body` + `:dt.fn/lang` |
| `:sandbar.shape/memory-shape`  | `:mm/Memory`          | Required `:mm.memory/name` + `:mm.memory/description`; minimum body length |
| `:sandbar.shape/shape-shape`   | `:mm/Shape`           | Required `:mm.shape/applies-to` + `:mm.shape/shape-id` — **the shape of shapes** |

The `shape-shape` is the metacircular closure: `:mm/Shape` itself is a `:dt/Class`; `:mm/Shape` instances are entities whose validation is governed by a shape that targets `:mm/Shape`.  When `sandbar.shape.create` accepts a new shape, the same walker that the new shape will eventually drive is invoked to validate the new shape's own definition.

The bootstrap order matters: the metamodel boots with its meta-shapes pre-installed; the first `conformance-report` run validates the metamodel against its own declared structure.  Any violation is a substrate bug — and surfacing it through the same conformance surface that surfaces all other violations is what makes the closure load-bearing rather than decorative.

## Comparison with adjacent approaches

### vs. clojure.spec / Malli

`clojure.spec` and Malli give you composable schema-shaped validation as data, in a single Clojure namespace.  Sandbar's shapes are *substrate-resident* — they live as entities, project to the filesystem, version through git, surface through BM25F search.  A Malli schema is a runtime value; a `:mm/Shape` is a substrate entity that survives JVM restarts, propagates across worktrees, and shows up in `mcp__sandbar__sandbar_orient_library-card`.  When custom logic is warranted, `:mm.shape/validator-fn` can reference a `:mm/Fn` whose body uses Malli or spec internally — the substrate accepts the delegation.

### vs. JSON Schema

JSON Schema is structural and acyclic — it describes the shape of a tree.  Sandbar's shapes are relational — they describe nodes in a graph, with reference to substrate-typed predicates.  An MCP `tools/list` can reflect JSON Schema from a `:mm/Shape` on demand for boundary clients; the canonical declaration is the shape memorial, not the JSON fragment.

### vs. SHACL with external reasoners (TopBraid SHACL API; Apache Jena SHACL)

External SHACL processors take RDF graphs and shape graphs, return validation reports.  Sandbar implements SHACL Core natively — the walker fns are `:mm/Fn` instances in the same store as the shapes; conformance reports are produced by Datomic Datalog queries plus per-check fn evaluation; no separate reasoner process; no shape-graph-to-validation-graph translation step.  The trade is feature scope: Sandbar v1 covers the SHACL Core baseline (cardinality / pattern / datatype / closed / severity), with SHACL property paths and SHACL-SPARQL constraints deferred to v2.

### vs. OWL DL reasoning

OWL DL gives you full description-logic inference with sound and complete reasoning.  Sandbar's shape walker is an abstract interpreter — it computes over a static abstract domain (frontmatter + graph + types), not over a model-theoretic semantics.  The Cousot-Cousot framing makes the trade explicit: shape validation is precise and predictable within its abstract domain; full DL reasoning is more powerful but requires a separate reasoner runtime.

### vs. database-level constraints (CHECK; foreign keys; unique indexes)

A relational database's CHECK constraint and a Datomic `:db/unique` declaration give you per-attribute integrity at the storage layer.  Sandbar's shapes operate one level up — at the entity-graph layer — and can express constraints that span multiple attributes (*"if `:mm.decision/status` is `:superseded`, `:mm.decision/superseded-by` must reference another decision"*) that storage-level constraints cannot.  The two compose: Datomic enforces value-type coherence at write; shapes enforce structural and semantic invariants at the substrate boundary.

## Operational consequences

The substrate-level investment in first-class shape memorials and `:mm/Fn`-implemented walkers pays out in five places.

**Discoverability.**  *"What constraints apply to a `:mm/Decision`?"* is one MCP call — `sandbar.shape.list :applies-to :mm/Decision`.  Before the arc, the same question required reading the validation namespace.

**Authorability without code change.**  Adding a new constraint kind that the existing walker covers (cardinality, pattern, datatype, required, closed) is a markdown file commit.  Adding a new constraint kind that requires custom logic is a `defdbfn` plus an `:mm.shape/validator-fn` reference — still inside the substrate, still discoverable, still propagating through the reactive sink.

**Conformance reports as substrate data.**  Conformance reports are structured maps, not strings — consumable by downstream tools, citable from memorials, queryable as data.

**Severity-driven boundary policy.**  The `:strict` / `:audit` / `:disabled` mode parameter is per-call.  A development REPL can run `entity.create` in `:audit` mode while continuous integration runs the same call in `:strict` mode — same shapes, different boundary policy, no per-class branching.

**Substrate self-audit.**  The metacircular meta-shapes mean the substrate's own integrity is queryable through the same surface as application-level integrity.  *"Does sandbar's metamodel conform to the shapes it declares?"* is `sandbar.shape.conformance-report :class :dt/Class` — and the answer should be unanimously `:pass`, every run, before any tagged release.

## References

**SHACL**

- Knublauch, H. & Kontokostas, D. (2017). *Shapes Constraint Language (SHACL).* W3C Recommendation.  https://www.w3.org/TR/shacl/
- Knublauch, H. & Allemang, D. (2017). *SHACL Advanced Features.* W3C Working Group Note.  https://www.w3.org/TR/shacl-af/
- Knublauch, H. (2011). *SPIN — Modeling Vocabulary.* W3C Member Submission.  https://www.w3.org/Submission/spin-modeling/

**Abstract interpretation**

- Cousot, P. & Cousot, R. (1977). *Abstract Interpretation: A Unified Lattice Model for Static Analysis of Programs by Construction or Approximation of Fixpoints.* Proceedings of POPL 1977, 238–252.
- Cousot, P. & Cousot, R. (1992). *Abstract Interpretation Frameworks.* Journal of Logic and Computation, 2(4), 511–547.

**Description logics and OWL (for contrast)**

- Hitzler, P., Krötzsch, M., Parsia, B., Patel-Schneider, P.F. & Rudolph, S. (2012). *OWL 2 Web Ontology Language Primer (Second Edition).* W3C Recommendation.  https://www.w3.org/TR/owl2-primer/
- Baader, F., Calvanese, D., McGuinness, D., Nardi, D. & Patel-Schneider, P.F. (eds.) (2007). *The Description Logic Handbook.* Cambridge University Press.

**Clojure-side precedents**

- Metosin (2019–). *Malli — Data-driven Schemas for Clojure.* https://github.com/metosin/malli
- Hickey, R. (2016). *clojure.spec — Rationale and Overview.* https://clojure.org/about/spec

## See also

- [`first-class-fn.md`](first-class-fn.md) — `:mm/Fn` is the substrate the walker is built on; `:mm.shape/validator-fn` is the SHACL-AF `sh:SPARQLFunction` analogue
- [`first-class-rule.md`](first-class-rule.md) — `:mm/Rule` is `:mm/Fn` + dispatch-spec; shapes are sibling-by-reference to rules
- [`metamodel.md`](metamodel.md) — `:mm/Shape` lives under `:mm/Meta` in the memory model; targets `:dt/Class` instances via `:mm.shape/applies-to`
- [`memory-model.md`](memory-model.md) — `:mm/Shape` is a `:mm/Meta` memorial; the `:dt/memorial-policy :first-class` axis governs FS projection
- [`workflow-substrate.md`](workflow-substrate.md) — workflow-transition gates are shapes that fire as pre-conditions before a transition; the shape's `:mm.shape/validator-fn` is the gate body
- [`projection.md`](projection.md) — the reactive sink that gives `:mm/Shape` memorials their FS face at `memory/shapes/`
- [`mcp-protocol.md`](mcp-protocol.md) — the `sandbar.shape.*` MCP verb surface
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) — `shape.list` / `shape.validate` / `shape.conformance-report` / `shape.create` / `shape.update` verb catalog
- [`doc/guides/authoring-shapes.md`](../guides/authoring-shapes.md) — hands-on shape-authoring guide
