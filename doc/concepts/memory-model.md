# The Memory Model

> Sandbar's domain ontology for declarative knowledge — the type hierarchy under `:mm/Memory` that turns decisions, plans, libraries, observations, logs, and runs into queryable typed-edge citizens of the same graph that holds the schema describing them.  A corpus-aligned memorial vocabulary on top of the `:dt/*` metamodel — what `mm/*` schema files declare, what `memory/<class-path>/<ident>.md` files concretize, what the MCP `entity.*` / `search.*` / `navigate.*` verbs return.  For the metacircular substrate underneath, see [`metamodel.md`](metamodel.md); for the projection mechanism that gives memorials their FS face, see [`projection.md`](projection.md).

## Thesis

Knowledge worth keeping deserves a class.

The naive shape is to treat documents as documents — files in a folder, indexed by full-text search, navigated by `find` and `grep`.  This works for small corpora and falls over the moment you want to ask anything *structural*: which decisions cite this observation; which plans were motivated by this question; which library studies share an ancestor in a particular tradition.  The structure exists in the documents; the substrate doesn't know it's there.

Sandbar's commitment: **declarative knowledge is data, with the same first-classness as schema data**.  A `:mm/Decision` is an entity.  A `:mm/Plan` is an entity.  Each carries a typed-edge neighborhood — citations, motivations, supersessions, refinements — that the substrate walks via the same `dt/*` operations that walk `:dt/Class` itself.  The markdown file at `memory/decisions/foo.md` is the same entity as the one returned by `mcp__sandbar__sandbar_entity_find :class :mm/Decision :rel-path "decisions/foo.md"`; the FS form is the canonical projection, the DB form is the queryable view, and `project-graph` / `ingest-graph` (see [`projection.md`](projection.md)) hold them in coherence.

This isn't a separate domain bolted on top.  The memory model lives *under* `:dt/Resource` alongside every other class the metamodel describes, inherits the same slot machinery, and projects through the same codec layer.  What it adds is a vocabulary — an opinionated set of supertypes and concrete classes whose names and slots reflect the actual shape of corpus-resident knowledge as it accumulates in long-running AI/human collaboration.

## The four abstract supertypes

The shape of the hierarchy under `:mm/Memory` is deliberate.  Four abstract supertypes carve the space:

| Supertype       | What it carries                                          | Example concrete subtypes                                              |
|-----------------|----------------------------------------------------------|------------------------------------------------------------------------|
| `:mm/Artifact`  | Durable outputs with memorial value beyond their trigger | `:mm/Decision`, `:mm/Plan`, `:mm/Library`, `:mm/Activity` (and subs)   |
| `:mm/Signal`    | Captured state about the world                           | `:mm/Observation`, `:mm/Question`, `:mm/Idea`, `:mm/Bug`               |
| `:mm/Guidance`  | Normative prescriptions for future behavior              | `:mm/Pattern`, `:mm/AntiPattern`, `:mm/Protocol`, `:mm/Preference`     |
| `:mm/Meta`      | Memories about the memory system itself                  | `:mm/Type`, `:mm/Fn`, `:mm/Workflow`, `:mm/Shape`, `:mm/Actor`, `:mm/Session` |

The distinction is not file-system layout (though it parallels the `memory/decisions/`, `memory/observations/`, `memory/patterns/`, `memory/types/` directory structure).  The distinction is *semantic role*: an artifact produces, a signal records, a guidance prescribes, a meta describes the apparatus.  Rules can speak in terms of these categories — *"every guidance should cite a signal that motivated it"*; *"artifacts may supersede; signals do not"* — without enumerating the concrete subtypes by hand.

### `:mm/Artifact` — durable outputs

The category for memories that *are themselves the output*.  A `:mm/Decision` IS the architectural choice; a `:mm/Plan` IS the design document; a `:mm/Library` IS the canonical study of an external library.  These are consumable on their own — read the decision to know the decision; no separate registry, no parallel changelog.  They typically carry `:mm.memory/supersedes` / `:mm.memory/superseded-by` lineage because they have *versions*; signals and guidance generally do not.

The concrete subtypes under `:mm/Artifact` cluster into two shapes:

- **Static artifacts** — `:mm/Decision`, `:mm/Plan`, `:mm/Library`, `:mm/Reference`, `:mm/Synthesis`, `:mm/Briefing`, `:mm/Memoir`, `:mm/Example`, `:mm/Codebase` — durable documents with stable content.
- **Activity artifacts** — `:mm/Activity` and its descendants `:mm/Log`, `:mm/EventLog`, `:mm/Run` — durable records of *something that happened* (a session, an event-firing, a job execution).  These get the W3C PROV-O Activity-shape slot vocabulary — `:mm.activity/started-at`, `:mm.activity/ended-at`, `:mm.activity/agent`, `:mm.activity/was-informed-by` — and inherit the interval-algebra discipline for cross-activity temporal relations.

### `:mm/Signal` — captured state

The category for *"here is what we noticed"* — observations of fact, recorded questions, proposed ideas, encountered bugs.  Signals do not prescribe action and do not produce durable output.  They are the empirical floor; guidance and artifacts cite them as motivation.

### `:mm/Guidance` — normative prescriptions

The category for *"when X happens, do / don't do / prefer / reach for Y"*.  Patterns, anti-patterns, protocols, preferences, feedback (corrections), authorizations.  These carry the at-startup-visibility apparatus — `:mm.guidance/at-startup` ∈ `#{:keystone :high :standard}` — that lets the most-load-bearing disciplines surface at session orientation without requiring the AI to discover them every time.

### `:mm/Meta` — schema-facing memories

The category for memories *about the memory system itself*.  Type definitions (`:mm/Type`), function specifications (`:mm/Fn`), workflow definitions (`:mm/Workflow`), validation shapes (`:mm/Shape`), actor identities (`:mm/Actor`), session records (`:mm/Session`).  These memories live on a different plane — they describe the apparatus rather than its contents — and validation rules treat them differently (a `:mm/Type` doesn't need a `:cites` edge to be valid; a `:mm/Decision` does).

## First-class memorialization — the `:dt/memorial-policy` axis

Not every entity in the substrate is corpus-resident.  Some are pure runtime state (a `:event/HttpRequest` instance per Pedestal request; an in-flight `:workflow/Process`); some are inline within host memorials (a `:mm/Tag` serialized into `:mm.memory/tags`); some are full first-class citizens with their own filesystem face.  The choice is declared per class via `:dt/memorial-policy`:

| Policy         | Where instances live                                                                  |
|----------------|---------------------------------------------------------------------------------------|
| `:first-class` | FS-projected to `memory/<class-path>/<ident>.md`; reactive sink fires on mutation     |
| `:db-only`     | DB-resident; covered by the DB-dump arc for disaster recovery; not in the FS corpus   |
| `:inline`      | Serialized within host memorials' slot machinery; no standalone identity              |

This is a property of the *class*, not of individual instances.  When a `:dt/Class` is registered with `:dt/memorial-policy :first-class`, the reactive substrate emits a markdown file each time an instance commits.  When it is `:db-only`, instances are queryable but never reach the filesystem.  When it is `:inline`, the class can only appear as a slot value on a host memorial — it doesn't carry independent identity.

The criterion is **specification vs state**:

- **Specification** — stable, human-relevant, edit-meaningful → `:first-class`.  Decisions, plans, workflows, type definitions.  Humans want to read these in their editor; git wants to version them; AIs want to BM25F-search them.
- **Runtime state** — high-volume, transient, machine-relevant → `:db-only`.  HTTP requests, in-flight workflow processes, raw event-substrate firings.  Important for observability; not interesting as filesystem files.
- **Subordinate component** — only-meaningful-in-context → `:inline`.  Tags as values on memorials; sections as components of a memorial's body chain.

The composition with the reactive substrate is what makes this concrete.  The SSE-projection sink subscribes to the Datomic tx-report queue (wrapped behind the `:dt/*` manifold transport — see [`workflow-substrate.md`](workflow-substrate.md) for the broader event-substrate context), filters incoming entity mutations by their class's `:dt/memorial-policy`, and emits markdown files for the `:first-class` ones.  The FS form appears without explicit `project-graph` calls; the DB form is queryable immediately.  Bijection holds.

## Naming convention

All memorial-level classes use the `:mm/<Name>` ident.  No exceptions.

Substrate-runtime hierarchies use their own domain namespace:

- `:event/*` — substrate-runtime event instances (`:event/HttpRequest`, `:event/ServerEvent`, etc.) under `:dt/Event`
- `:workflow/*` — substrate-runtime workflow primitives (`:workflow/Process`, `:workflow/History`) under `:dt/Ref`
- `:dt/*` — the metamodel itself (`:dt/Class`, `:dt/Property`, `:dt/Resource`)

The discipline distinguishes the *memorial face* from the *runtime face*.  `:mm/Workflow` is the memorial classifier — a workflow definition you might want to read, edit, version, search — projecting to `memory/workflows/<name>.md`.  `:workflow/Process` is the runtime instance — a running or terminated execution of that workflow, queryable as a `:db-only` entity.  Same conceptual domain, two layers, two namespaces.

Similarly with events: `:mm/Event` is the classifier-memorial (one per lifecycle position; `:db-only`-but-edit-meaningful); `:event/HttpRequest` is the runtime substrate; `:mm/EventLog` is the per-event-firing memorial *for events flagged worthy of corpus visibility*.  Three roles, three idents, one clean layering.

## Worked example — authoring a decision

The full round-trip.  We author a `:mm/Decision` memorial, project it to the filesystem, query it back through MCP, and walk its typed-edge neighborhood.

### Authoring

A decision memorial is a markdown file with YAML frontmatter:

```markdown
---
name: Reactive Projection Sink Subscribes to Tx-Report Queue
description: First-class memorialization fires reactively on entity mutation rather than batch-projected; substrate composes with SSE-projection arc for forward + Option C DB-dump for runtime-state coverage.
type: decision
scope: project
created: 2026-05-23
created-by: actors/claude-opus-4-7-1m.md
cites:
  - plans/sandbar_first_class_memorialization_arc_workflows_schedules_contexts_dt_memorial_policy_2026_05_23.md
  - decisions/sandbar_event_substrate_architecture_*.md
related:
  - decisions/option_b_plus_c_ratified_spec_vs_state_criterion_*.md
---

## Decision

The fs-projection sink subscribes to the Datomic tx-report queue
(wrapped behind the dt/* manifold transport), filters by the
mutated entity's class's `:dt/memorial-policy`, and projects only
`:first-class` instances.  ...
```

This file lives at `memory/decisions/reactive_projection_sink_subscribes_to_tx_report_queue_2026_05_23.md`.  When written, the codec parses it; the substrate ingests; a `:mm/Decision` entity commits.

### Projection

Or — equivalently — the entity may be authored *first* through the MCP surface, and the markdown file appears via the reactive sink:

```clojure
(mcp__sandbar__sandbar_entity_create
  :class :mm/Decision
  :slots {:mm.memory/name "Reactive Projection Sink Subscribes to Tx-Report Queue"
          :mm.memory/description "First-class memorialization fires reactively..."
          :mm.memory/rel-path "decisions/reactive_projection_sink_subscribes_to_tx_report_queue_2026_05_23.md"
          :mm.memory/created #inst "2026-05-23"
          :mm.memory/cites [<plan-eid> <event-substrate-decision-eid>]})
```

The tx-report fires.  The sink sees a `:mm/Decision` mutation.  It looks up `:mm/Decision`'s `:dt/memorial-policy` — `:first-class` — calls the markdown codec's `emit`, writes the file.  The two faces converge.

### Querying

Same entity, two surfaces.  From the MCP side:

```
mcp__sandbar__sandbar_entity_find
  :class :mm/Decision
  :rel-path "decisions/reactive_projection_sink_subscribes_to_tx_report_queue_2026_05_23.md"
```

Returns the entity with all its slots.  From the FS side, `cat memory/decisions/reactive_projection_sink_subscribes_to_tx_report_queue_2026_05_23.md` returns the same content in markdown form.

### Walking

The typed-edge neighborhood is a query.  *"What plan motivated this decision?"*:

```
mcp__sandbar__sandbar_navigate_outbound-edges
  :entity <decision-eid>
  :predicate :mm.memory/cites
```

Returns the cited plan entity.  *"What other memorials cite this decision?"*:

```
mcp__sandbar__sandbar_navigate_inbound-edges
  :entity <decision-eid>
  :predicate :mm.memory/cites
```

Returns memorials whose `cites:` frontmatter names this decision's rel-path.  The graph navigation is `O(log n)` on indexed reference attributes — no grep walk; no full-text scan.

### Searching

The BM25F surface ranks memorials by relevance across name + description + tags + body, weighted per field:

```
mcp__sandbar__sandbar_search_bm25f
  :query "tx-report queue subscription reactive projection"
  :class :mm/Decision
```

Returns the decision (and any siblings on the same topic) ranked by relevance.  Memorials' structural facets — class, scope, status, importance — compose as filter predicates in the same call.

## The `:mm/Activity` PROV-O lift

A particular case worth naming.  Logs, event-firings, scheduler-runs — these are all *activities*: things that happened, with a start time, an end time, an agent, a causal lineage, an outcome.  The W3C PROV-O ontology (Lebo et al 2013) is the standard vocabulary for this — `prov:Activity`, `prov:wasAssociatedWith`, `prov:wasInformedBy`, `prov:used`, `prov:wasGeneratedBy`.  Sandbar lifts that vocabulary into the memory model at the `:mm/Activity` supertype, and three concrete activity-shaped classes inherit it:

| Class           | Default policy | What it records                                                       |
|-----------------|----------------|-----------------------------------------------------------------------|
| `:mm/Log`       | `:first-class` | Narrative session chronicle — the `/memory-handoff` artifact          |
| `:mm/EventLog`  | `:first-class` | Per-event-firing memorial — Telemere signals flagged `:memorial :first-class` |
| `:mm/Run`       | `:db-only`     | Scheduler-job execution record — high-volume operational runs         |

Shared PROV-O slot vocabulary at the supertype level:

| Slot                            | PROV-O term                      | What it carries                          |
|---------------------------------|----------------------------------|------------------------------------------|
| `:mm.activity/started-at`       | `prov:startedAtTime`             | Begin instant                            |
| `:mm.activity/ended-at`         | `prov:endedAtTime`               | End instant                              |
| `:mm.activity/agent`            | `prov:wasAssociatedWith`         | Actor responsible (ref to `:mm/Actor`)   |
| `:mm.activity/was-informed-by`  | `prov:wasInformedBy`             | Upstream activities (causal lineage)     |
| `:mm.activity/generated`        | `prov:wasGeneratedBy` (inverse)  | Entities produced                        |
| `:mm.activity/used`             | `prov:used`                      | Entities consumed                        |
| `:mm.activity/status`           | (sandbar extension)              | `:running` / `:succeeded` / `:failed` / `:cancelled` / `:pending` |

The composition payoff lands at the cross-arc level.  Allen's 13 interval relations (Allen 1983) — `before`, `during`, `overlaps`, `meets`, etc. — are properties of intervals.  Any `:mm/Activity` instance has an interval.  A query like *"which log entries occurred during this scheduler run?"* is one Datalog walk, not three.  A query like *"what activity was informed by the event-log entry that caused that run to fail?"* traverses `:mm.activity/was-informed-by` across all three activity subclasses uniformly.

This is the dividend of getting the abstraction layer right.  PROV-O didn't have to be Sandbar's invention; it had to be Sandbar's vocabulary.

## How it composes

The memory model is not a feature; it is the surface where every other sandbar concern lands.

- **Codec layer** ([`codec-layer.md`](codec-layer.md)) — the markdown codec is the canonical native codec for `:mm/Memory` and descendants.  Frontmatter ↔ slots; body ↔ `:mm.memory/body-raw` + section chain.  Round-trip stability is a substrate invariant.
- **Projection** ([`projection.md`](projection.md)) — `project-graph` walks all classes with `:dt/native-codec`, emits per-class subdirectories under `memory/`.  `ingest-graph` is the inverse.  The reactive sink is the same operation triggered per-entity rather than per-class.
- **Path grammar** ([`path-grammar.md`](path-grammar.md)) — `:mm.memory/rel-path` is the addressable name; the path grammar walks the directory structure as a typed-namespace tree.
- **Full-text search** ([`fulltext-search.md`](fulltext-search.md)) — BM25F over the memorial body + frontmatter fields, with per-class weight profiles.
- **Workflow substrate** ([`workflow-substrate.md`](workflow-substrate.md)) — `:mm/Workflow` is the memorial classifier; `:workflow/Process` is the runtime instance; sessions, validation runs, and (future) scheduler jobs all use the substrate.
- **Metamodel** ([`metamodel.md`](metamodel.md)) — every memorial class is a `:dt/Class` instance.  Adding `:mm/Briefing` was one transaction; the MCP surface and the FS layout both became aware in the same step.

The throughline: declarative knowledge is a *first-class shape in the substrate*, not a documentation concern bolted on the side.

## Comparison with adjacent approaches

### vs. Wiki / Markdown-folder corpora

A wiki gives you full-text search over markdown.  The memory model gives you full-text search *and* typed-edge graph navigation *and* class-based polymorphic queries *and* schema-validated frontmatter *and* aggregate analytics across the corpus, all from the same store.  The wiki form is recoverable via `project-graph`; the structure that the wiki form *encodes but cannot query* is recoverable via the substrate.

### vs. Note-taking systems with backlinks (Obsidian, Roam, Tana)

These give backlinks and tags.  The memory model adds typed predicates — *cites* vs *refines* vs *supersedes* vs *contradicts* are semantically distinct, not just labeled.  A query like *"what decisions superseded this one?"* is direct.  The typed-edge vocabulary (`memory/predicates/<name>.md`) is itself part of the corpus.

### vs. RDF triple stores (Apache Jena, Stardog, GraphDB)

A triple store gives you full RDF.  The memory model is RDF-shaped (RDFS-inspired schema, named predicates, class hierarchy) but closed-world (per the metamodel's deliberate stop-short of OWL DL).  It also gives you the *projection layer* — the markdown FS form that humans edit and git versions.  A triple store with a separate document-storage layer could approximate this; the memory model just *is* both, unified.

### vs. Ontology editors (Protégé, TopBraid)

These give you ontology authoring with reasoner support.  The memory model gives you ontology authoring (`:dt/Class` is a class) and per-instance corpus content (`:mm/Decision` instances are documents) in the same store, queried through the same API.  The ontology *is* the schema; the corpus *uses* the schema; the two are one substrate.

## References

**RDFS / RDF foundations** (see [`metamodel.md`](metamodel.md) for the full lineage)

- Brickley, D. & Guha, R.V. (2014). *RDF Schema 1.1.* W3C Recommendation.

**PROV-O provenance**

- Lebo, T., Sahoo, S. & McGuinness, D. (eds.) (2013). *PROV-O: The PROV Ontology.* W3C Recommendation.  https://www.w3.org/TR/prov-o/

**Allen's interval algebra**

- Allen, J.F. (1983). *Maintaining knowledge about temporal intervals.* Communications of the ACM, 26(11), 832–843.

**Frame-system lineage (memorials as structured records)**

- Minsky, M. (1974). *A Framework for Representing Knowledge.* MIT-AI Laboratory Memo 306.
- Brachman, R.J. & Schmolze, J.G. (1985). *An Overview of the KL-ONE Knowledge Representation System.* Cognitive Science, 9(2), 171–216.

**Zettelkasten and corpus-as-graph**

- Luhmann, N. (1981). *Mitteilungen über das Zettelkasten.* (Translated as *Communicating with Slip Boxes*.)
- Ahrens, S. (2017). *How to Take Smart Notes.* (The modern operationalization of the Zettelkasten discipline that informed the memory-model typed-edge vocabulary.)

## See also

- [`metamodel.md`](metamodel.md) — the `:dt/*` substrate underneath the memory model
- [`projection.md`](projection.md) — the FS↔DB bidirectional projection that gives memorials their dual face
- [`codec-layer.md`](codec-layer.md) — the markdown codec and codec-protocol that handle per-entity translation
- [`workflow-substrate.md`](workflow-substrate.md) — `:mm/Workflow` memorial face vs. `:workflow/Process` runtime face
- [`fulltext-search.md`](fulltext-search.md) — BM25F retrieval over the memorial corpus
- [`navigation.md`](navigation.md) — typed-edge graph navigation across the memorial neighborhood
- [`aggregation.md`](aggregation.md) — corpus-level analytics over memorial populations
- [`mcp-protocol.md`](mcp-protocol.md) — the MCP surface that exposes the memory model to AI consumers
- [`doc/guides/defining-new-classes.md`](../guides/defining-new-classes.md) — hands-on guide for adding new memorial classes
