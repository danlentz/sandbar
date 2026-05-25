# First-Class Functions

> Sandbar promotes functions to first-class memorial citizens.  `:dt/Fn` is the substrate-level metamodel primitive — a peer of `:dt/Class` and `:dt/Property` under `:dt/Resource` — and `:mm/Fn` is its memorial-level companion under `:mm/Memory` / `:mm/Meta`.  The `defdbfn` macro dual-emits a Datomic `:db/fn` schema entity *and* a `:mm/Fn` memorial from one declaration, capturing `:dt.fn/source-ns` / `:dt.fn/source-var` / `:dt.fn/purpose` / `:dt.fn/purity` at macro-expansion time.  `:mm/Rule` and `:mm/Shape` ride this substrate — rules are a `:mm/Fn` subclass with dispatch-spec slots; shapes are siblings that *reference* `:mm/Fn` walker instances.  For the metamodel-level foundation see [`metamodel.md`](metamodel.md); for the SHACL-aligned validation that consumes this see [`shape-validation.md`](shape-validation.md); for the dispatch-spec layer that specializes it see [`first-class-rule.md`](first-class-rule.md).

## Thesis

Functions are knowledge worth keeping.

The naive shape treats Datomic transactor functions as opaque side-channel — strings of code transacted into `:db/fn` schema entities, invoked through `[:my/fn args...]` in tx-data, discoverable only by reading the schema EDN file that installed them.  No metadata.  No purpose-tagging.  No version chain.  No memorial face.  No way to ask "show me every transformer function whose `:dt.fn/purity` is `:pure-total`" — because the function does not know what it is.

Sandbar's commitment: **a function is a typed citizen of the substrate, with the same first-classness as `:dt/Class` and `:dt/Property`.**  `:dt/Fn` lives in the metamodel alongside its peers.  `:mm/Fn` lives in the memory model alongside `:mm/Decision` and `:mm/Plan`.  Each function carries parameters, return type, body, language, purpose, purity, cost class, install surface, version chain, and source-namespace metadata — and the Clojure namespace var that authored it is canonical source-of-truth.  Both representations (the substrate-level `:db/fn` entity that the transactor invokes; the memorial-level `:mm/Fn` document that humans read and AIs search) are projections from the same `defdbfn` declaration.

This is metacircularity at the function layer.  The substrate's own state-transition primitives (the run-state CAS; the migration tx-fn; the SHACL walker check fns) are themselves `:mm/Fn` instances — the substrate uses what it provides.  And when the substrate needs to migrate legacy function instances to first-class typing, it does so with a `:mm/Fn` instance that migrates `:mm/Fn` instances.  The function-defining-function is itself a function.

## Lineage

The design draws on five decades of prior art on functions-as-first-class citizens in declarative-knowledge systems.

### Datomic transactor functions (Hickey 2012–)

Datomic stores transactor functions as `:db/fn` schema entities, invoked atomically inside the transactor's tx-data position.  The original mechanism — `(d/function {:lang :clojure :params [...] :code '...'})` — gives Datomic's atomicity and serialization guarantees to user-authored code.  The community has documented an anti-pattern catalog around it: AOT-compiled body classes producing `ClassNotFoundException`; Client-API surface diverging from Peer-API; transactor serialization bottleneck; untrusted-code-execution surface; versioning and audit-trail gaps.

Sandbar inherits the substrate (a `:db/fn` entity *is* what the transactor invokes) but pushes past the metadata gap.  The community convention has been to author bodies as strings inside schema EDN files, losing IDE / REPL / test / lint support.  Sandbar's `defdbfn` macro inverts that — the Clojure namespace is canonical source; both the schema entity and the memorial are projections from source.  This is the **gen-fn pattern** (per the gen-fn library — author-as-defn, project-as-schema-entity, sidestep AOT-hell), generalized to the substrate level.

### CLOS Metaobject Protocol (Kiczales, des Rivières & Bobrow 1991)

The metaobject protocol's design discipline: the language's function system is itself implemented in the function system, and that implementation is a stable extension point.  Generic functions are instances of `standard-generic-function`; methods are instances of `standard-method`; `compute-applicable-methods` and `compute-effective-method` are hooks on the metaclass that anyone may specialize.  This is the architectural ancestor of Sandbar's framing — `:dt/Fn` is to fn what `:dt/Class` is to class.

Sandbar does not implement a full method-combination protocol — there is no `compute-effective-method` analogue — but it inherits the discipline.  The introspection API (`mcp__sandbar__sandbar_class_instances :dt/Fn`, `mcp__sandbar__sandbar_entity_find :class :mm/Fn`) walks `:mm/Fn` entities by their declared slots, the same way it walks `:mm/Decision`.

### PowerLoom and KRL (declarative-language ancestor)

PowerLoom (Chalupsky & MacGregor 1999) — heir to LOOM and KL-ONE — gave us the `defconcept` / `defrelation` / `deffunction` / `defrule` uniform-primitive family: every kind of knowledge is a `def<thing>` declaration whose form is interpreted by the substrate.  KRL (Bobrow & Winograd 1977) gave us the procedural-attachment pattern: slots could carry computational triggers (`if-needed` / `if-added` / `if-removed`) that fired when the slot was read or written.

These are the **declarative-language precedent** for treating functions as substrate primitives.  Sandbar's `:dt.fn/purpose` enum (`:derive` / `:assert` / `:retract` / `:transform` / `:validate` / `:infer`) is the FRL daemon-trinity (if-needed / if-added / if-removed) extended with the SHACL validation/inference split.  A `:dt.fn/purpose :derive` function on a property *is* a procedural attachment in the KRL sense; sandbar makes that an addressable, queryable substrate concept rather than a parser feature.

### SHACL Advanced Features `sh:SPARQLFunction` (W3C 2017)

SHACL-AF (Knublauch & Allemang 2017) added `sh:SPARQLFunction` as the current-standard analogue of a first-class function in a graph-typed substrate.  Its required-slot vocabulary — `sh:parameter` / `sh:returnType` / body via embedded SPARQL — is the structure Sandbar mirrors directly for `:dt/Fn`.

The mirror is verbatim where the spec lines up, supplemented where Sandbar needs more:

| Sandbar slot              | SHACL-AF analogue   | Notes                                                                  |
|---------------------------|---------------------|------------------------------------------------------------------------|
| `:dt.fn/parameters`       | `sh:parameter`      | Ordered list of `:dt.fn/Parameter` entities                            |
| `:dt.fn/return-type`      | `sh:returnType`     | `:dt/Class` or `:dt/Datatype` ref                                      |
| `:dt.fn/body`             | `sh:ask` / `sh:select` | Language-typed expression                                            |
| `:dt.fn/lang`             | (implicit SPARQL)   | `:clojure` / `:datalog` / `:sparql` / `:js` / `:sql` / `:native-substrate` |
| `:dt.fn/description`      | `sh:description`    | RDFS-comment-like string                                               |
| `:dt.fn/purpose`          | (Sandbar; from Cyc) | 6-value enum                                                           |
| `:dt.fn/purity`           | (Sandbar; from Datomic) | 3-value enum                                                       |
| `:dt.fn/cost-class`       | (Sandbar)           | `:cheap` / `:moderate` / `:expensive`; informs in-tx vs out-of-tx routing |
| `:dt.fn/source-ns`        | (Sandbar; gen-fn)   | Bridges memorial back to Clojure namespace                             |
| `:dt.fn/source-var`       | (Sandbar; gen-fn)   | Bridges memorial back to Clojure var                                   |
| `:dt.fn/installed-as`     | (Sandbar)           | `:db-fn` / `:classpath-fn` / `:ion` / `:entity-pred` / `:attr-pred`    |
| `:dt.fn/version`          | (Sandbar)           | Explicit version-chain as graph structure                              |
| `:dt.fn/superseded-by`    | (Sandbar)           | Companion to `:version`                                                |

SHACL-AF's separation of `sh:NodeShape` (declarative; constrains structure) from `sh:SPARQLFunction` (computational; *is* the constraint check) is the architectural precedent for Sandbar's separation of `:mm/Shape` from `:mm/Fn` — see [`shape-validation.md`](shape-validation.md).

### Cyc HL modules and Cyc evaluatable predicates (Lenat & Guha 1990)

Cyc's High-Level system gave us purpose-tagged predicates — every Cyc-evaluatable predicate carries microtheory scope, decidability classification, and computational cost characteristics.  Sandbar's `:dt.fn/decidable?` boolean and `:dt.fn/purity` enum follow the same discipline: surface the safety-and-cost properties at the metadata layer so dispatch and routing can decide whether a function is safe to retry, replay, or invoke inside a transaction.

## The substrate

### Two layers, one source of truth

```
METAMODEL LAYER  (:dt/* namespace; foundational substrate primitive)

  :dt/Resource (root)
  ├── :dt/Class      — metamodel concept type
  ├── :dt/Property   — metamodel relation type
  ├── :dt/Ref        — metamodel reference type
  └── :dt/Fn         — metamodel function type
       └── :dt/Rule  — fn with constrained signature (returns inference data, not arbitrary tx-data)


MEMORIAL LAYER   (:mm/* namespace; instances of the metamodel)

  :mm/Memory (root of memorial types)
  ├── :mm/Decision, :mm/Plan, :mm/Library, ...
  ├── :mm/Shape     — declarative constraint memorial (references :mm/Fn validators by ref)
  └── :mm/Fn        — first-class function memorial
       └── :mm/Rule — :mm/Fn + dispatch-spec slots (actor + context + event + enforcement)
```

The metamodel level (`:dt/Fn`) declares **what a function is** as a substrate type.  The memorial level (`:mm/Fn`) declares **what a particular function memorial is** — name, body, purpose, source-ns / source-var, installed-as, version — projected to `memory/fns/<slug>.md` for human readability and BM25F retrieval.

Source-of-truth is the Clojure namespace var.  The `defdbfn` macro at declaration time captures both representations:

- The `:db/fn` schema entity (compiled Datomic function; the actual transactor-side or peer-side executable)
- The `:mm/Fn` memorial entity (carries `:dt.fn/*` metadata; FS-projected; BM25F-searchable; hook-retrievable)

Both are projections.  Re-evaluating the `defdbfn` form in the REPL updates both.  This is the gen-fn pattern applied at the substrate level: instead of authoring `:db/fn` bodies as strings inside schema EDN (the AOT-hell antipattern), they are authored as ordinary `defn`-shaped Clojure with full IDE / REPL / test / lint support.

### The `:dt/Fn` slot vocabulary

Five required slots — the SHACL-AF baseline:

```clojure
{:dt.fn/parameters    [<Parameter-entity> ...]   ; ordered; each carries :path / :datatype / :order / :optional?
 :dt.fn/return-type   :dt/TxData                 ; :dt/Class or :dt/Datatype ref
 :dt.fn/body          <body-expression>          ; language-typed
 :dt.fn/lang          :clojure                   ; :clojure / :datalog / :sparql / :js / :sql / :native-substrate
 :dt.fn/description   "..."}
```

Plus optional canonical-axis slots — each captures one cross-system invariant from the 24-system foundational synthesis:

| Slot                  | Enum / values                                                  | Substrate use                                                |
|-----------------------|----------------------------------------------------------------|--------------------------------------------------------------|
| `:dt.fn/purpose`      | `:derive :assert :retract :transform :validate :infer`         | Dispatch routing (validator path vs transformer path); from FRL daemon-trinity + SHACL split |
| `:dt.fn/purity`       | `:pure-total :pure-partial :side-effecting`                    | Retry / replay safety; from Datomic/Cyc divide               |
| `:dt.fn/cost-class`   | `:cheap :moderate :expensive`                                  | In-tx vs out-of-tx routing; from the datofu `:datalog-tx` anti-pattern |
| `:dt.fn/status`       | `:draft :validated :installed :retired`                        | Install-lifecycle gating                                     |
| `:dt.fn/exec-order`   | integer                                                        | Deterministic rule application order (SHACL-AF)              |
| `:dt.fn/decidable?`   | boolean                                                        | Meta-reasoning safety (SWRL / RIF discipline)                |
| `:dt.fn/qualifier`    | `:primary :before :after :around`                              | Method-combination semantics (CLOS MOP heritage)             |
| `:dt.fn/source-ns`    | string ns-name                                                 | Gen-fn pattern; sidesteps AOT-hell                           |
| `:dt.fn/source-var`   | string var-name                                                | Gen-fn pattern; binds memorial → Clojure source              |
| `:dt.fn/version`      | semver string                                                  | Explicit version-chain                                       |
| `:dt.fn/superseded-by`| ref to newer `:dt/Fn`                                          | Companion to `:version`                                      |
| `:dt.fn/installed-as` | `:db-fn :classpath-fn :ion :entity-pred :attr-pred`            | Disambiguates which Datomic surface hosts the implementation |

The `:dt.fn/installed-as` axis is load-bearing.  `:db-fn` is the classical transactor-side install (Datomic compiles + serializes the body into the database).  `:classpath-fn` is a peer-side install — the function lives in the JVM classpath, resolved via `:dt.fn/source-ns` + `:dt.fn/source-var`, with only the `:mm/Fn` memorial transacted.  The SHACL walker fns (which need full Clojure environment + classpath libraries) are `:classpath-fn`; substrate-mutation fns (the run-state CAS) are `:db-fn`.  The same `defdbfn` macro handles both via this discriminator.

## The `defdbfn` macro — dual emission

The Clojure `defdbfn` macro is the authoring surface.  It accepts the standard `defn` shape plus an optional attr-map between params and body for the `:dt.fn/*` metadata:

```clojure
(ns sandbar.scheduler.run-state
  (:require [sandbar.db.fn :refer [defdbfn]]
            [datomic.api]))

(defdbfn run-state-advance [db run-eid expected-state new-state]
  {:dt.fn/purpose     :transform
   :dt.fn/purity      :pure-total
   :dt.fn/cost-class  :cheap
   :dt.fn/description "Atomic compare-and-set on :run/state; throws on mismatch."
   :dt.fn/installed-as :db-fn
   :dt.fn/version     "1.0.0"}
  (let [current-state (:run/state (datomic.api/entity db run-eid))]
    (if (= current-state expected-state)
      [[:db/add run-eid :run/state new-state]
       [:db/add run-eid :run/state-transitioned-at (java.util.Date.)]]
      (throw (ex-info ":run/state CAS failed"
                      {:run-eid  run-eid
                       :expected expected-state
                       :actual   current-state})))))
```

At macro-expansion time:

1. The ordinary `defn` is emitted — `run-state-advance` is callable directly in the REPL like any function (this is what enables tests, REPL exploration, type hints, lint coverage).
2. The `:db/fn` schema entity is queued in `*fn-base*` for installation at substrate startup (legacy path; preserved for backwards compatibility with the pre-arc substrate).
3. The `:mm/Fn` memorial is queued in `*mm-fn-memorial-base*` — a map carrying `:dt/type :mm/Fn`, the captured `:dt.fn/source-ns` (here `"sandbar.scheduler.run-state"`), `:dt.fn/source-var` (`"run-state-advance"`), the metadata from the attr-map merged over default values, and `:mm.memory/rel-path "fns/run-state-advance.md"` for FS projection.

The two representations are kept coherent by a single source declaration.  Re-evaluating the form replaces both.  At startup, `sandbar.core/start` invokes `sandbar.db.fn/load-all-dbfn` (transacts the schema entities) and `sandbar.db.fn/load-all-mm-fn-memorials` (transacts the memorials) — both compose with the broader substrate lifecycle.

### Backwards compatibility

The macro is backwards-compatible.  A `defdbfn` declaration without an attr-map (the pre-arc form) still works:

```clojure
;; Old form — still valid:
(defdbfn set-doc! [db e doc]
  [[:db/add e :db/doc doc]])
```

Defaults apply: `:dt.fn/purpose :transform` / `:dt.fn/purity :pure-total` / `:dt.fn/cost-class :cheap` / `:dt.fn/installed-as :db-fn` / `:dt.fn/lang :clojure` / `:dt.fn/status :draft` / `:dt.fn/version "1.0.0"`.  The macro still dual-emits — the memorial just inherits sensible defaults rather than explicit declarations.

## The memorial face

A `:mm/Fn` memorial at `memory/fns/run-state-advance.md` carries the function as a substrate-resident artifact:

```markdown
---
name: run-state-advance
description: Atomic compare-and-set on :run/state; throws on mismatch.
type: fn
lang: clojure
purpose: transform
purity: pure-total
cost-class: cheap
installed-as: db-fn
source-ns: sandbar.scheduler.run-state
source-var: run-state-advance
version: "1.0.0"
status: installed
---

## Purpose

Atomic compare-and-set on a scheduled run's `:run/state` slot.  Used by the
scheduler's job-run lifecycle to enforce single-writer + valid-transition
semantics for state advances; mismatches throw `ex-info` with the observed
vs expected states for caller-side resolution.

## Parameters

- `db` (implicit) — the current Datomic db value (transactor-injected)
- `run-eid` — the `:run` entity to transition
- `expected-state` — keyword; pre-state expected (CAS pre-condition)
- `new-state` — keyword; post-state to assert (CAS post-value)

## Returns

`:dt/TxData` — vector of `[:db/add ...]` operations, or throws `ex-info` on mismatch.

## Body

```clojure
(let [current-state (:run/state (datomic.api/entity db run-eid))]
  (if (= current-state expected-state)
    [[:db/add run-eid :run/state new-state]
     [:db/add run-eid :run/state-transitioned-at (java.util.Date.)]]
    (throw (ex-info ":run/state CAS failed"
                    {:run-eid run-eid
                     :expected expected-state
                     :actual current-state}))))
```

## See also

- `memory/workflows/run-state.md` — the state machine this advances
```

This file is what humans read in their editor, what git versions, what BM25F searches.  It is the same `:mm/Fn` entity returned by `mcp__sandbar__sandbar_entity_find :class :mm/Fn :rel-path "fns/run-state-advance.md"` and the same one referenced by `:mm.shape/validator-fn` slots on shapes that validate `:run` entities.

The reactive projection sink (per [`memory-model.md`](memory-model.md) and [`projection.md`](projection.md)) emits this file each time a `:mm/Fn` instance commits.  No explicit `project-graph` call required.

## Self-application — the substrate uses what it provides

The substrate's own machinery is `:dt/Fn` instances.  Three concrete demonstrations:

### The SHACL walker

`sandbar.shape` declares the family of validation check fns — `check-required-property`, `check-cardinality`, `check-pattern`, `check-datatype`, `check-closed`, `check-validator-fn`, plus the top-level `walk-entity` and batch `conformance-report`.  Each is authored via `defdbfn` with `:dt.fn/installed-as :classpath-fn` and `:dt.fn/purpose :validate`.  The walker IS a family of first-class `:mm/Fn` memorials, composed under the abstract-interpreter (Cousot-Cousot 1977) framing.  See [`shape-validation.md`](shape-validation.md) for the framing in full.

### The legacy-instance migration

Pre-arc, sandbar's existing `:dt/dt :fn`-tagged Datomic functions had no first-class typing.  The migration is itself a `:dt/Fn` instance:

```clojure
(defdbfn migrate-legacy-fn-instance [db legacy-eid]
  {:dt.fn/purpose     :transform
   :dt.fn/purity      :pure-total
   :dt.fn/cost-class  :cheap
   :dt.fn/description "Migrate a legacy :dt/dt :fn instance to first-class typing."}
  (let [entity            (datomic.api/entity db legacy-eid)
        already-migrated? (some #{:dt/Fn :mm/Fn} (:dt/type entity))]
    (if already-migrated?
      []                                                  ; idempotent no-op
      [[:db/add legacy-eid :dt/type :dt/Fn]
       [:db/add legacy-eid :dt/type :mm/Fn]])))
```

The substrate migrates itself using its own foundational primitive.  Metacircularity is load-bearing.

### Rules as a subclass

`:mm/Rule` (the first-class rule type from the 2026-05-11 ADR) is now `:dt/subclass-of :mm/Fn`.  Every rule IS a function — its body is the rule body, its parameters are the dispatch context, its `:dt.fn/purpose` is `:assert` or `:infer`.  The dispatch-spec slots (`:mm.rule/actor` / `:mm.rule/context` / `:mm.rule/event` / `:mm.rule/enforcement` / `:mm.rule/priority`) are additions on top of the fn shape, not replacements.  See [`first-class-rule.md`](first-class-rule.md) for the dispatch-spec layer.

## Discovery — querying the function substrate

Because functions are first-class, the standard substrate operations apply.

### Find all transformer functions

```
mcp__sandbar__sandbar_class_instances
  :class :mm/Fn
```

Returns every `:mm/Fn` memorial.  Add a filter:

```
mcp__sandbar__sandbar_search_bm25f
  :query "atomic compare-and-set state transition"
  :class :mm/Fn
```

Returns ranked candidates.  Filter further by predicate-attribute query — `sandbar.search.attribute` over `:dt.fn/purity = :pure-total` returns only retry-safe functions.

### Walk the validator graph

```
mcp__sandbar__sandbar_navigate_inbound-edges
  :entity <fn-eid>
  :predicate :mm.shape/validator-fn
```

Returns every `:mm/Shape` that uses this function as a walker.  The typed-edge graph composes function authorship with shape consumption — humans (and AI agents) can answer "what shape would break if I retired this function?" via one navigation call.

### Audit the cost surface

```
mcp__sandbar__sandbar_aggregate_group-by
  :class :mm/Fn
  :slot :dt.fn/cost-class
```

Returns counts by cost class — `:cheap N1 :moderate N2 :expensive N3`.  Useful before a substrate optimization pass to confirm that no `:expensive` functions are accidentally in the transactor-side install set (where they would block tx throughput).

## Comparison with adjacent systems

### vs. Datomic transactor functions alone

Datomic gives you the substrate (`:db/fn` schema entities, transactor invocation, atomicity).  It does not give you metadata, version chains, purpose-tagging, source-of-truth discipline, or memorial-level retrieval.  Sandbar adds those *on top* of Datomic without replacing anything — every `:dt/Fn` instance is also a Datomic `:db/fn` (or peer-side `:classpath-fn`); the substrate machinery composes.

### vs. SHACL-AF `sh:SPARQLFunction`

SHACL-AF defines functions as SPARQL bodies invocable from SHACL shape constraints.  Sandbar mirrors the schema shape (parameters / return-type / body / lang / description) but generalizes the body language to a `:dt.fn/lang` enum and supports multiple execution surfaces via `:dt.fn/installed-as`.  A function whose `:dt.fn/lang` is `:datalog` is a Datomic Datalog rule.  A function whose `:dt.fn/lang` is `:clojure` and `:dt.fn/installed-as` is `:db-fn` is a Datomic transactor function.  The substrate routes via the discriminators.

### vs. SPIN rules (W3C Submission 2011)

SPIN (Knublauch 2011) added rule attachment to OWL classes via SPARQL bodies.  Its `spin:rule` / `spin:constructor` / `spin:constraint` slot vocabulary is the historical precedent for Sandbar's `:dt.class/rule` / `:dt.class/constructor` / `:dt.class/constraint` (deferred to Stage I of the integration arc).  The slot points from the class to a `:mm/Fn` instance; the function is the constraint or rule body.

### vs. PowerLoom `deffunction`

PowerLoom merges functions and relations under a uniform substrate.  Sandbar separates them — `:dt/Fn` and `:dt/Property` are siblings, not subtypes.  The rationale (per the Stage B class-hierarchy ADR): functions *compute* and relations *describe* — distinct kinds.  PowerLoom's merge is conceptually elegant but harder to reason about; sandbar's separation matches the SHACL-AF current-standard precedent.

### vs. external function libraries (Apache Jena ARQ; SPARQLer functions)

ARQ functions are extension points registered with the SPARQL query engine.  Sandbar's functions are *substrate-resident* — they live as entities in the same store as everything else, queryable through the same API, projectable to the filesystem.  The substrate's own function machinery is queryable as data.

## Operational consequences

The substrate-level investment in first-class function typing pays out in five places.

**Audit and inventory.**  Querying for *every function whose `:dt.fn/purity` is `:side-effecting`* is one call — `sandbar_class_instances :mm/Fn` filtered by attribute.  Before the arc, the same question required reading every schema EDN file and inferring purity from the body.

**Version-chain walking.**  `:dt.fn/version` + `:dt.fn/superseded-by` form a typed-edge chain.  *"What was the prior version of this transformer?"* is one navigation call, not git archaeology.

**Cost-aware routing.**  The scheduler can route `:cost-class :cheap` functions inside transactions and `:cost-class :expensive` functions to out-of-tx batch jobs based on substrate metadata — no per-fn special-casing in the scheduler code.

**Source-of-truth coherence.**  The Clojure namespace var is canonical.  Re-evaluating `defdbfn` in the REPL updates both the schema entity and the memorial.  The two faces cannot drift because they have one source.

**BM25F retrieval over function bodies.**  Hooks that surface relevant functions at action time (the planned `inject_fn_recall.bb` parallel to the existing `inject_rule_recall.bb`) are one BM25F call against `:class :mm/Fn`.  *"What validator functions are relevant to a `:mm/Decision` mutation?"* is the same retrieval shape as *"what rules are relevant to this commit?"*.

## References

**Datomic transactor functions**

- Hickey, R. (2012). *Datomic — Database Functions.* https://docs.datomic.com/transactions/transaction-processing.html#database-functions
- Halloway, S. (2013–). *Day of Datomic Examples — Transaction Functions.* https://github.com/Datomic/day-of-datomic

**KRL and procedural attachment**

- Bobrow, D.G. & Winograd, T. (1977). *An Overview of KRL, a Knowledge Representation Language.* Cognitive Science, 1(1), 3–46.
- Minsky, M. (1974). *A Framework for Representing Knowledge.* MIT-AI Laboratory Memo 306.

**Metaobject protocols**

- Kiczales, G., des Rivières, J. & Bobrow, D.G. (1991). *The Art of the Metaobject Protocol.* MIT Press.

**Declarative-language families**

- Bobrow, D.G., DeMichiel, L.G., Gabriel, R.P., Keene, S.E., Kiczales, G. & Moon, D.A. (1988). *Common Lisp Object System Specification.* ANSI X3J13.
- Chalupsky, H. & MacGregor, R.M. (1999). *PowerLoom Manual.* USC/ISI.

**SHACL Advanced Features**

- Knublauch, H. & Allemang, D. (2017). *SHACL Advanced Features.* W3C Working Group Note.  https://www.w3.org/TR/shacl-af/
- Knublauch, H. (2011). *SPIN — Modeling Vocabulary.* W3C Member Submission.  https://www.w3.org/Submission/spin-modeling/

**Cyc**

- Lenat, D.B. & Guha, R.V. (1990). *Building Large Knowledge-Based Systems: Representation and Inference in the Cyc Project.* Addison-Wesley.

## See also

- [`metamodel.md`](metamodel.md) — `:dt/Fn` sits alongside `:dt/Class` and `:dt/Property` in the metamodel hierarchy
- [`memory-model.md`](memory-model.md) — `:mm/Fn` is a memorial type under `:mm/Meta`; the `:dt/memorial-policy :first-class` axis governs FS projection
- [`shape-validation.md`](shape-validation.md) — `:mm/Shape` references `:mm/Fn` walker instances by ref; the SHACL-aligned consumer of this substrate
- [`first-class-rule.md`](first-class-rule.md) — `:mm/Rule` extends `:mm/Fn` with dispatch-spec slots; the rule subsystem's authoring surface
- [`workflow-substrate.md`](workflow-substrate.md) — workflow transitions can carry `:dt/Fn` references for transition-gate validation
- [`projection.md`](projection.md) — the reactive sink that gives `:mm/Fn` memorials their FS face
- [`codec-layer.md`](codec-layer.md) — the markdown codec that handles `:mm/Fn` memorial round-trips
- [`doc/api/dt-star.md`](../api/dt-star.md) — mechanical API reference; `dt/Fn` access patterns
- [`doc/guides/authoring-fns.md`](../guides/authoring-fns.md) — hands-on `defdbfn` walkthrough
