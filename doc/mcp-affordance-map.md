# Choose an MCP operation by the question

_Generated from `sandbar.mcp.tools/verb-catalog` through `sandbar.mcp.catalog-model`, with editorial text in `resources/catalog/operation-guide.edn`. Regenerate with `lein affordance-map > doc/mcp-affordance-map.md` or `lein catalog-regen`; verify with `lein catalog-check`._

Sandbar exposes a stable vocabulary of operations over a changing model. Start with the question you need to answer, discover the relevant class or property, and then choose a tool. Creating a new class does not require creating another family of transport endpoints.

The [wire reference](api/mcp-verbs.md) lists the tool names and arguments. The running server's `tools/list` is authoritative for its advertised schemas. `sandbar.tools.search` and `sandbar.tools.describe` help a client discover purpose and composition; these dotted names are catalog names, while tools are called by the advertised wire names.

## Find a starting point

| Question | Catalog operation | Useful next step |
| --- | --- | --- |
| What classes are available? | `sandbar.schema.classes` | `class.describe` for one class |
| What belongs on this class? | `sandbar.class.slots`, `required-slots` | Inspect a property's range/cardinality |
| What does this known record say? | `sandbar.entity.find`, `find-by-rel-path` | Read related records through typed edges |
| Which records discuss these words? | `sandbar.search.bm25f` | Full read and relationship inspection |
| Which entities meet a structural condition? | `sandbar.aggregate.count`, `group-by` | Narrow the population before fetching detail |
| What cites or supersedes this record? | `sandbar.navigate.inbound-edges` | Read the returned source entities |
| What is reachable by this relationship pattern? | `sandbar.navigate.path-via` | Inspect path witnesses and endpoints |
| How is a record situated? | `sandbar.orient.library-card` | Select named axes worth following |
| Where does a class sit in the hierarchy? | `sandbar.orient.type-tree` | Inspect parents, slots and instances |
| What vocabulary fits this concept? | `sandbar.tag.lookup`, `sandbar.ground` | Read its definition, scope, alternatives and lifecycle |
| Which records use this concept? | `sandbar.navigate.inbound-edges` with tag/theme predicates | Read the returned records and their relationships |

Schema/entity listings can be large. Prefer counts, narrow classes and explicit projection to an unrestricted whole-store fetch. Metadata-only is useful for selecting identifiers; it is insufficient when an answer depends on a record's body or qualifications.

## Compose a useful read

Suppose a search finds an old cache decision. Read the decision in full, follow inbound `supersedes`, read its successor, then follow the successor's outbound `cites`. Each operation answers a different question. The highest search score does not settle which decision governs, and a citation does not prove that its author interpreted the evidence correctly.

For unfamiliar terminology, start with vocabulary lookup, read the selected concept, and query its tag/theme membership. This can find records whose text uses different wording. Then establish their context in the same way. The [search guide](guides/searching-the-corpus.md) and [navigation guide](guides/navigating-with-paths.md) carry these routes through concrete calls. [Aggregation](concepts/aggregation.md) explains structural summaries; [path grammar](concepts/path-grammar.md) explains route composition.

## Change data through its accepting boundary

| Intent | Operation family | Contract to establish |
| --- | --- | --- |
| Create or amend a typed entity | `entity.create`, `entity.update` | Class, required slots, identity, validation mode and persistence result |
| Examine conformance | `entity.validate`, `shape.validate`, `shape.conformance-report` | Which checks run, against what candidate or committed state |
| Maintain vocabulary | `tag.define`, `rename`, `split`, `consolidate`, `align` | Typed targets, reference preservation and retrieval freshness |
| Record application progress | `workflow.define`, `start-process`, `transition` | Accepted state/history/effect outcome |
| Run a validation workflow | `validation.start`, `run`, `results` | Run identity and completion state |
| Interchange native documents | `project.export`, `project.import` | Destination, disclosure, representation fidelity and conflict policy |
| Retire explicit entities | `entity.retract` | Reviewed targets, references, dry-run result and recoverability |
| Manage scheduler execution | `schedule.start`, `stop`, `add`, `remove` | Runtime ownership, enrollment and in-flight outcome |

Inspect the full tool card and current schema before a write. Behavioral annotations help clients present an operation, but they are hints, not authorization or proof that all side effects were classified correctly. An export writes files even when it does not change database entities.

`schedule.enable` and `schedule.start` answer different questions: an enabled flag does not allocate a worker or enroll every persisted schedule. Likewise, a domain validation run is not automatically a standards-compliant MCP Task. Use the advertised protocol capabilities and domain lifecycle deliberately.

## Check the answer at the right layer

Check transport status, JSON-RPC errors and tool `isError` before parsing a payload. Then inspect the operation's own outcome. A committed entity, a queued projection and a settled file are different milestones. An absent search hit, an inaccessible entity and an unknown identifier are also different results.

For a client implementation, follow [Writing an MCP client](guides/writing-an-mcp-client.md). For the model behind the operations, start with [the metamodel](concepts/metamodel.md). For confidentiality, read [firewall and projects](firewall-and-projects.md).

## Complete operation inventory

83 operations across 22 axes. These rows come from the source catalog, not a database seed. Catalog hints: `[safe]` classified read-only; `[idem]` classified idempotent write; `[unsafe]` classified mutating. A hint does not grant permission or establish all side effects. Use the [complete reference](api/mcp-verbs.md) for schemas and behavior.

### aggregate (4)

- `sandbar.aggregate.count` — Count entities of a class (with optional Datalog filter) [safe]
- `sandbar.aggregate.group-by` — Group-by-count facet aggregation over a class's instances [safe]
- `sandbar.aggregate.rank-by` — Structural-rank re-ordering by edge degree / backlink-density / recency / freshness [safe]
- `sandbar.aggregate.tag-histogram` — Frequency histogram of :mm/Tag usage across the corpus [safe]

### audit (1)

- `sandbar.audit.fs-substrate-drift` — Compare document files with stored Memory entities [safe]

### class (8)

- `sandbar.class.describe` — Full class description — abstract? + parents + ancestors + subclasses + slots [safe]
- `sandbar.class.direct-slots` — Directly-declared slots only (no inheritance) [safe]
- `sandbar.class.instances` — All instances of a class (incl. subclass instances) [safe]
- `sandbar.class.parents` — Direct parents + transitive ancestors of a class [safe]
- `sandbar.class.required-slots` — Required slots of a class (`:dt/required? true`) [safe]
- `sandbar.class.slots` — Effective slot set of a class (inherited + directly-declared) [safe]
- `sandbar.class.subclasses` — All transitive subclasses of a class [safe]
- `sandbar.class.validate-all-instances` — Run validation against every instance of a class; return the report [safe]

### codec (1)

- `sandbar.codec.list` — List registered codecs (wire-format mediator inventory) [safe]

### entity (6)

- `sandbar.entity.create` — Create a typed entity with optional codec parsing [unsafe]
- `sandbar.entity.find` — Look up an entity by ident or eid [safe]
- `sandbar.entity.find-by-rel-path` — Look up an :mm/Memory entity by corpus rel-path [safe]
- `sandbar.entity.retract` — Retract explicit entities with a dry-run-by-default safety layer [unsafe]
- `sandbar.entity.update` — Update slots on an existing entity [idem]
- `sandbar.entity.validate` — Pre-transaction validation of a slot map against a class [safe]

### ground (1)

- `sandbar.ground` — Compositional grounding workflow — tag lookup + meta-vocab + suggested next-step [safe]

### namespace (1)

- `sandbar.namespace.policy` — Look up the per-namespace policy commitment statement (ARK ??-inflection) [safe]

### navigate (4)

- `sandbar.navigate.inbound-edges` — Typed-edges pointing AT an entity (who references it) [safe]
- `sandbar.navigate.outbound-edges` — Typed-edges originating FROM an entity [safe]
- `sandbar.navigate.path-via` — Walk a Wilbur-lineage path-grammar expression from a seed entity [safe]
- `sandbar.navigate.siblings-of` — Same-directory peers of an entity via a filesystem-style path slot [safe]

### orient (3)

- `sandbar.orient.library-card` — Multi-axis typed-edge neighborhood view of an entity [safe]
- `sandbar.orient.tree` — Top-level directory grouping of class instances by path-slot [safe]
- `sandbar.orient.type-tree` — Class-hierarchy subtree rooted at a class (nested rendering) [safe]

### project (3)

- `sandbar.project.export` — Preview or export an enrolled project's eligible documents [safe]
- `sandbar.project.import` — Import reviewed Markdown source units from a filesystem hierarchy [unsafe]
- `sandbar.project.recovery-check` — Compare a completed export's trusted origin with the same existing database [safe]

### property (3)

- `sandbar.property.cardinality` — Cardinality of a property (`:db.cardinality/one` or `/many`) [safe]
- `sandbar.property.domain` — Domain class of a property (`:dt/domain`) [safe]
- `sandbar.property.range` — Value-type range of a property (`:dt/range` / `:db/valueType`) [safe]

### reactive (1)

- `sandbar.reactive.health` — Reactive-projection pipeline health snapshot (queue depth, throughput, error counts) [safe]

### resolve (1)

- `sandbar.resolve` — Resolve an entity-reference of any wire form (URN / ident / rel-path / eid) [safe]

### schedule (8)

- `sandbar.schedule.add` — Add a :mm/Schedule to the priority queue [unsafe]
- `sandbar.schedule.disable` — Flip the scheduler `:enabled?` flag false (does NOT stop fire-thread) [idem]
- `sandbar.schedule.enable` — Flip the scheduler `:enabled?` flag true (does NOT start fire-thread) [idem]
- `sandbar.schedule.inspect` — Diagnostic: full operator-facing scheduler runtime snapshot [safe]
- `sandbar.schedule.list` — Diagnostic: list all currently-queued schedule fires [safe]
- `sandbar.schedule.remove` — Remove a :mm/Schedule from the priority queue [unsafe]
- `sandbar.schedule.start` — Full activation: allocate handler-pool + spawn fire-thread + register subscriber [unsafe]
- `sandbar.schedule.stop` — Full deactivation: unregister subscriber + drain handler-pool + join fire-thread [unsafe]

### schema (4)

- `sandbar.schema.classes` — List every class ident in the metamodel [safe]
- `sandbar.schema.datatypes` — List every Datomic value type (`:db.type/*`) registered [safe]
- `sandbar.schema.entities` — Batch fetch entity-spec maps grouped by class (N+1 elimination) [safe]
- `sandbar.schema.properties` — List every property ident in the metamodel [safe]

### search (2)

- `sandbar.search.attribute` — Single-attribute Lucene-syntax fulltext search (`:db.fn/fulltext-search`) [safe]
- `sandbar.search.bm25f` — Multi-field BM25F fulltext search over a class's instances [safe]

### shape (5)

- `sandbar.shape.conformance-report` — Batch conformance report for direct instances and exact-class shapes [safe]
- `sandbar.shape.create` — Author a new :mm/Shape entity (thin wrapper over sandbar.entity.create) [unsafe]
- `sandbar.shape.list` — List :mm/Shape instances; optional filter by :applies-to class [safe]
- `sandbar.shape.update` — Amend an existing :mm/Shape entity (thin wrapper over sandbar.entity.update) [idem]
- `sandbar.shape.validate` — Validate a single entity against its applicable :mm/Shape instances [safe]

### tag (9)

- `sandbar.tag.align` — Declare a cross-vocabulary SKOS mapping from a tag to an external IRI [unsafe]
- `sandbar.tag.audit` — Run the 7 tag-lifecycle invariants and return the violation report [safe]
- `sandbar.tag.consolidate` — Merge :from tag INTO :into tag; preserves :from as alt-label + lifecycle :superseded [unsafe]
- `sandbar.tag.consolidate-all` — Batch-merge multiple drift clusters in a single MCP call [unsafe]
- `sandbar.tag.define` — Author a new canonical :mm/Tag with required documentation slots [unsafe]
- `sandbar.tag.harmonize` — Bulk-harmonization DRY-RUN report — drift clusters + auto-mergeable counts [unsafe]
- `sandbar.tag.lookup` — Tag-vocabulary primitive — find canonical tags aligned with a concept [safe]
- `sandbar.tag.rename` — Change a tag's canonical :value; preserves old as hidden-label [unsafe]
- `sandbar.tag.split` — Partition a tag into narrower tags (creates :broader-generic children) [unsafe]

### tools (2)

- `sandbar.tools.describe` — Full verb card + typed composition edges for a named verb [safe]
- `sandbar.tools.search` — Find the right verb(s) for a task — BM25F over the verb catalog [safe]

### types (2)

- `sandbar.types.instance-of` — Predicate — is `:entity` an instance of `:class`? [safe]
- `sandbar.types.subclass-of` — Predicate — is `:child` a (transitive) subclass of `:parent`? [safe]

### validation (6)

- `sandbar.validation.cancel` — Cancel an in-flight validation run [unsafe]
- `sandbar.validation.history` — Recent validation runs (all classes or filtered) [safe]
- `sandbar.validation.results` — Fetch the report from a completed validation run [safe]
- `sandbar.validation.retry` — Re-run a previously-failed validation [unsafe]
- `sandbar.validation.run` — Execute a previously-started (queued) validation run [unsafe]
- `sandbar.validation.start` — Start a workflow-backed validation run against all instances of a class [unsafe]

### workflow (8)

- `sandbar.workflow.active-processes` — All active (non-terminal) workflow processes; optionally filtered by workflow [safe]
- `sandbar.workflow.define` — Register a new workflow definition (states + transitions) [unsafe]
- `sandbar.workflow.find` — Look up a workflow definition by ident [safe]
- `sandbar.workflow.orchestrate` — session-workflow orchestrator — drive a workflow.process through a phase of its ceremony [unsafe]
- `sandbar.workflow.process-history` — Full transition history of a workflow process [safe]
- `sandbar.workflow.process-state` — Current state + terminal flag + completion flag of a workflow process [safe]
- `sandbar.workflow.start-process` — Start a new workflow process attached to a subject entity [unsafe]
- `sandbar.workflow.transition` — Apply a named transition to a workflow process [unsafe]

