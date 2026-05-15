# MCP Verb Catalog

> Layer 4 — mechanical reference for every verb in Sandbar's MCP `tools/list` catalog.  Organized by group.  For practical client patterns see [`doc/guides/writing-an-mcp-client.md`](../guides/writing-an-mcp-client.md); for the design rationale see [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md).

All verbs are invoked via JSON-RPC 2.0 over HTTP at `/mcp`:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {"name": "<verb-name>", "arguments": {...}}
}
```

Results are wrapped in the MCP content envelope:

```json
{"result": {"content": [{"type": "text", "text": "<JSON-stringified payload>"}]}}
```

Clients must `JSON.parse(result.content[0].text)` to extract the actual return value.

## Naming convention

Verbs follow `sandbar.<group>.<verb>` — one verb per *operation kind*, parameterized by class via arguments (not per-class verb names).  This is the F-B-001 resolution (see [`mcp-protocol.md`](../concepts/mcp-protocol.md#the-verb-catalog-operational-not-per-class)).

## Schema introspection verbs

### `sandbar.schema.classes`
**Arguments:** none
Returns every `:dt/Class` instance ident in the metamodel.

### `sandbar.schema.properties`
**Arguments:** none
Returns every `:dt/Property` instance ident in the metamodel.

### `sandbar.schema.datatypes`
**Arguments:** none
Returns every `:db.type/*` value type.

### `sandbar.schema.entities`
**Arguments:** `{classes?: string[]}`
Batch fetch entities across classes.  Single round-trip alternative to N+1 `sandbar.class.instances` calls.

Returns `{by-class: {<class-ident>: [<entity-map>...]}, total-classes: int, total-entities: int}`.

If `classes` is omitted, fetches all non-abstract classes.  Per Stage G Signal 8 (corpus-side friction discovery).

## Class introspection verbs

### `sandbar.class.describe`
**Arguments:** `{class: string}`
Returns `{abstract?, parents, ancestors, subclasses, slots}` for the class.

### `sandbar.class.slots`
**Arguments:** `{class: string}`
Returns the effective slot set (inherited + direct).

### `sandbar.class.direct-slots`
**Arguments:** `{class: string}`
Returns only the directly-declared slots.

### `sandbar.class.required-slots`
**Arguments:** `{class: string}`
Returns the subset of slots that are required (`:dt/required? true`).

### `sandbar.class.instances`
**Arguments:** `{class: string}`
Returns every instance of the class (including subclass instances).

For multi-class fetch, prefer `sandbar.schema.entities` for the single-round-trip path.

### `sandbar.class.subclasses`
**Arguments:** `{class: string}`
Returns every transitive subclass.

### `sandbar.class.parents`
**Arguments:** `{class: string}`
Returns direct parents and all ancestor classes.

### `sandbar.class.validate-all-instances`
**Arguments:** `{class: string}`
Synchronously validates every instance of the class.

For large classes, prefer the workflow-backed `sandbar.validation.start` — it's cancellable and produces a queryable history.

## Type predicate verbs

### `sandbar.types.instance-of`
**Arguments:** `{class: string, entity: string}`
Returns `{instance-of?: boolean}`.

### `sandbar.types.subclass-of`
**Arguments:** `{parent: string, child: string}`
Returns `{subclass-of?: boolean}`.

## Property introspection verbs

### `sandbar.property.domain`
**Arguments:** `{property: string}`
Returns the property's `:dt/domain` class.

### `sandbar.property.range`
**Arguments:** `{property: string}`
Returns the property's `:dt/range` value type.

### `sandbar.property.cardinality`
**Arguments:** `{property: string}`
Returns `:db.cardinality/one` or `:db.cardinality/many`.

## Entity operation verbs

### `sandbar.entity.create`
**Arguments:**
```json
{
  "class":  "<class-ident>",
  "slots":  {...},                     // optional when source+format provided
  "format": "<codec-format-keyword>",  // optional; requires :source
  "source": "<native-representation>"  // optional; requires :format
}
```

Creates a new entity.  When `format` + `source` are present, the codec mediator parses `source` and merges with explicit `slots` (explicit slots win).  Validated via `dt/make`.

Returns the new entity's `:db/id` + slot snapshot.

Codec routing is per the class's `:dt/native-codec` (see [`codec-protocol.md`](codec-protocol.md)).

### `sandbar.entity.find`
**Arguments:** `{ident?: string, id?: integer}`
Returns an entity by ident or eid.

### `sandbar.entity.update`
**Arguments:** `{entity: string, slots: object}`

Updates slot values on an existing entity.  Currently *not yet implemented* — pending the `dt/update-entity` primitive.  Returns an error code until that lands.

### `sandbar.entity.validate`
**Arguments:** `{class: string, slots: object}`

Pre-transaction validation — checks that `slots` would be valid for `class` without writing.  Returns `nil` if valid or `{errors: [...]}`.

## Workflow operation verbs

### `sandbar.workflow.define`
**Arguments:** `{spec: object}`
Registers a new workflow definition.  `spec` is the workflow definition map (see [`designing-workflows.md`](../guides/designing-workflows.md#step-2--write-the-workflow-definition)).

### `sandbar.workflow.find`
**Arguments:** `{workflow: string}`
Looks up a workflow definition by ident.

### `sandbar.workflow.start-process`
**Arguments:** `{workflow: string, subject: string, data?: object}`
Creates a new process attached to a subject entity.  Returns the new process id.

### `sandbar.workflow.transition`
**Arguments:** `{process-id: integer, transition: string, reason?: string}`
Applies a named transition to a process.  Returns the new state.

### `sandbar.workflow.process-state`
**Arguments:** `{process-id: integer}`
Returns `{current-state, terminal?, terminal-kind, completed?}`.

### `sandbar.workflow.process-history`
**Arguments:** `{process-id: integer}`
Returns the full transition history of a process.

### `sandbar.workflow.active-processes`
**Arguments:** `{workflow?: string}`
Returns active (non-terminal) processes; optionally filtered by workflow ident.

## Validation service verbs

These verbs back long-running validation runs as workflow processes.

### `sandbar.validation.start`
**Arguments:** `{class: string}`
Begins validating every instance of `class`.  Returns a *task envelope*:

```json
{"task-id": <integer>, "status": "pending"}
```

The task is queryable via `tasks/get task-id`; cancellable via `tasks/cancel task-id`.

### `sandbar.validation.run`
**Arguments:** `{validation-id: integer}`
Executes a previously-started (queued) validation.

### `sandbar.validation.cancel`
**Arguments:** `{validation-id: integer}`
Cancels an in-flight validation.

### `sandbar.validation.retry`
**Arguments:** `{validation-id: integer}`
Re-runs a failed validation.

### `sandbar.validation.results`
**Arguments:** `{validation-id: integer}`
Fetches the results of a completed validation run.

### `sandbar.validation.history`
**Arguments:** `{class?: string}`
Recent validation runs — all classes or filtered by class.

## Codec + project-graph verbs

### `sandbar.codec.list`
**Arguments:** none
Returns the set of registered codecs:

```json
[
  {"name": ":codec/markdown", "mime-types": ["text/markdown"], "supports": ["mm/Memory"]},
  {"name": ":codec/json", "mime-types": ["application/json"], "supports": "any"}
]
```

### `sandbar.project.export`
**Arguments:**
```json
{
  "to": "<output-directory>",
  "filter": {
    "class": "<ident>",
    "classes": ["<ident>", ...],
    "tree-filter": "<rel-path-prefix>"
  }
}
```

Projects entities to a filesystem hierarchy at `to`.  Each entity emits via its class's `:dt/native-codec`.  Per Anderson `de.setf.rdf:project-graph` lineage — see [`projection.md`](../concepts/projection.md).

The `filter` is optional; when present, narrows which entities project.

### `sandbar.project.import`
**Arguments:**
```json
{
  "from": "<input-directory>",
  "filter": {...}
}
```

Walks the directory, parses each file via the appropriate codec, returns the entity-spec maps.  Inverse of `project.export`.

## Aggregation verbs

The four-axis retrieval surface's aggregation axis.  See [`aggregation.md`](../concepts/aggregation.md) for theory.

### `sandbar.aggregate.count`
**Arguments:**
```json
{
  "class": "<ident>",
  "where": "<EDN-string Datalog clauses>"
}
```
Count entities of `class` matching optional `:where` filter.  Returns `{:count <int>}`.  `:where` arrives as EDN string because JSON has no native representation for Datalog symbols.

### `sandbar.aggregate.group-by`
**Arguments:**
```json
{
  "class": "<ident>",
  "group-by": "<slot-ident>",
  "where": "<EDN-string Datalog clauses>"
}
```
Group instances by slot value; count per group.  Returns `{:groups {value count} :total <int>}`.

### `sandbar.aggregate.rank-by`
**Arguments:**
```json
{
  "class": "<ident>",
  "rank-by": ":degree | :backlink-density | :recency | :freshness",
  "limit": 20,
  "temporal-slot": "<slot-ident>"
}
```
Re-order instances by structural-rank axis.  `:temporal-slot` REQUIRED for `:recency` / `:freshness` (substrate does not hardcode class-specific temporal axes).  Returns `{:hits [{:entity ... :rank-score ...}] :total <int> :returned <int>}`.

## Navigation verbs

The four-axis retrieval surface's navigation axis.  See [`navigation.md`](../concepts/navigation.md) for the surface overview and [`path-grammar.md`](../concepts/path-grammar.md) for the algebra.

### `sandbar.navigate.path-via`
**Arguments:**
```json
{
  "from":    "<seed-ident>",
  "via":     "<EDN-string path expression>",
  "limit":   0,
  "include": ["paths"]
}
```
Walk a Wilbur-lineage path-grammar expression starting from `from`.  Returns `{:reachable [<entity-map>...] :total <int> :returned <int>}`.

`via` is an EDN-string path expression using Canonical-8 + Tier-2 operators (13 executable; Tier-3 vocabulary-registered, compilation deferred):
- Canonical-8: `:SEQ` `:OR` `:REP+` `:REP*` `:INV` `:SELF` `:RESTRICT` `:ANY`
- Tier-2: `:NOT` `:OPT` `:REP` (bounded) `:FILTER` `:TEST`

`:include ["paths"]` is accepted but path-data is NOT YET POPULATED — result carries `:path-data-deferred true` flag when requested.  Full path-data surfacing lands at a follow-on stage.

Example:
```json
{
  "from": ":decisions/foundation",
  "via":  "[:SEQ [:REP* [:OR :cites :evidences]] [:RESTRICT [:dt/type :mm.memory/decision]]]",
  "limit": 50
}
```

## Discovery and protocol verbs

These verbs are part of MCP standard, not Sandbar-specific extensions.

### `initialize`
First call on a new session.  Negotiates protocol version + capabilities.

```json
{"params": {
  "protocolVersion": "2025-11-25",
  "clientInfo": {"name": "<client-name>", "version": "<client-version>"},
  "capabilities": {}
}}
```

### `tools/list`
Returns the verb catalog with input schemas.

### `tools/call`
Invokes a verb.  Used for every verb above.

### `resources/list`
Returns the resource catalog — entities of classes with declared `:dt/native-codec`.

### `resources/read`
**Arguments:** `{uri: string}`
Reads a resource's content in its native representation.

### `resources/subscribe`
**Arguments:** `{uri: string}`
Subscribes to update notifications for a URI.  Server pushes `notifications/resources/updated` over SSE.

### `resources/unsubscribe`
**Arguments:** `{uri: string}`
Cancels a subscription.

### `prompts/list`
Returns prompt templates.  (Today: empty; reserved for future use.)

### `prompts/get`
Fetches a prompt with parameter substitution.

### `tasks/get`
**Arguments:** `{task-id: integer}`
Returns the task's current status + kind + result (if terminal).

### `tasks/list`
Returns active tasks.

### `tasks/cancel`
**Arguments:** `{task-id: integer}`
Cancels a running task.  Honored only if the underlying workflow process allows cancellation from its current state.

## Notifications

Server-pushed notifications (no `id` field):

| Method                                  | Payload                                                |
|-----------------------------------------|--------------------------------------------------------|
| `notifications/initialized`             | `{}`                                                   |
| `notifications/tools/list_changed`      | `{}`                                                   |
| `notifications/resources/list_changed`  | `{}`                                                   |
| `notifications/resources/updated`       | `{uri: string}`                                        |
| `notifications/tasks/status`            | `{task-id: int, status: string, kind?: string}`        |

Subscribed clients receive notifications over their SSE channel; non-subscribed clients see only request/response.

## Error codes

Per JSON-RPC 2.0:

| Code     | Meaning                                                       |
|----------|---------------------------------------------------------------|
| `-32700` | Parse error                                                   |
| `-32600` | Invalid request                                               |
| `-32601` | Method not found                                              |
| `-32602` | Invalid params — including unknown tool name, can't cancel    |
| `-32603` | Internal error                                                |
| `-32000` | Application-defined (auth failure, validation, business logic)|

`error.data` carries structured details — for unknown tools, it includes `available-tools`; for validation failures, it includes the `:errors` shape from `dt/validate`.

## See also

- [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md) — design rationale
- [`doc/guides/writing-an-mcp-client.md`](../guides/writing-an-mcp-client.md) — practical client patterns
- [`doc/api/dt-star.md`](dt-star.md) — the in-process equivalents
- [`doc/api/http-rest.md`](http-rest.md) — the REST alternative
