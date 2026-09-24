# MCP tool reference

_Generated from `sandbar.mcp.tools/verb-catalog` through `sandbar.mcp.catalog-model`, with editorial text in `resources/catalog/reference-guide.edn`. Regenerate with `lein mcp-verbs-doc > doc/api/mcp-verbs.md` or `lein catalog-regen`; verify with `lein catalog-check`._

Sandbar tools describe operations over the model: discover a class, read an entity, search its content, follow relationships, validate data, or change state. The [operation map](../mcp-affordance-map.md) helps choose a tool; this page lists its wire name and arguments.

This reference is generated from this checkout's catalog. Use the running server's `tools/list` response as the authoritative list of advertised names and complete input schemas. A deployment, version, or client's allowed subset may expose a different set. Each operation below includes its complete advertised JSON input schema, including nested items, alternatives, and required fields. A required argument is marked with an asterisk in the summary; conditional requirements and operation outcomes are described in its catalog text. Schema descriptions use Clojure notation for some values: actual call arguments are JSON.

## Naming convention

The catalog name `sandbar.entity.find` becomes the wire name `sandbar_entity_find`. Only dots become underscores: keep hyphens in names such as `sandbar_entity_find-by-rel-path` and `sandbar_aggregate_group-by`. Use the advertised wire name in `tools/call.params.name`.

Some discovery arguments identify a catalog record rather than a callable wire name. For example, call `sandbar_tools_describe` with `{"verb":"sandbar.entity.find"}` or `{"verb":":sandbar.entity/find"}`. The dotted name is appropriate **inside that argument**.

## Arguments and identities

Argument names are ordinary JSON keys such as `"class"`, `"rel-path"`, and `"persist"`. Do not add a leading colon or a trailing question mark to these keys. Values identifying classes, properties or interned entities use keyword-shaped strings, for example `":mm/Decision"`, `":mm.memory/name"`, or `":dt/Class"`. Use identifiers returned by discovery.

| Reference | Meaning | Appropriate lookup |
| --- | --- | --- |
| `:db/ident` | An entity's interned name; not every entity has one | `entity.find` with `ident` |
| `:db/id` | Numeric Datomic entity ID within this database | `entity.find` with integer `id` |
| Relative document path | A document location interpreted by the Markdown identity convention | `entity.find-by-rel-path` with `rel-path` |
| `urn:uuid:…` | A portable identity reference resolved through `:mm/id` | `resolve` with `reference` |

For a synthetic example, `notes/example.md` resolves to `:memory.notes/example`. A path such as `notes/example.md` is not itself a Datomic ident. `find-by-rel-path` accepts a leading `memory/` and a trailing `.md`, both optional, and applies the `:mm/Memory` document convention. It is not a general filesystem reader or a lookup convention for every class.

JSON types do not express every domain constraint: a class must exist, a property must fit its use, and a mutation must satisfy the relevant accepting boundary.

## Projection and result handling

Set `"projection":"full"` when a conclusion depends on the entity's body, qualifications, or slots. `entity.find`, `entity.find-by-rel-path`, `class.instances`, search, structural ranking, edge navigation, `path-via` and `library-card` return metadata by default. Metadata identifies entities; it does not establish what they say. Projection defaults are specific to each operation: for example, `workflow.find` returns a full definition by default.

`metadata-only` contains identity/type metadata. `full` includes the entity's slots, with referenced entities represented by bounded metadata projections; it does not recursively fetch an entire graph. BM25F also advertises `frontmatter`, which omits `:mm.memory/body-raw`. Read a referenced entity separately when its content matters. A projection mode controls response shape, not authorization.

Check results in this order:

1. Transport status and whether the response can be decoded.
2. A JSON-RPC `error` at the envelope level.
3. Tool `result.isError`.
4. The decoded operation outcome, such as `missing?`, `valid?`, `failed-count`, or `refused-count`.

Current tool responses carry a JSON-encoded payload in a text item of `result.content`. Use `structuredContent` when supplied; otherwise select the JSON text content and decode it. Preserve error content for diagnosis. An HTTP success, parseable payload, or absent `isError` does not by itself mean an entity was found or every item in a batch succeeded. See [Writing an MCP client](../guides/writing-an-mcp-client.md) for initialization, transport and a tool-result decoder.

Behavioral annotations such as `readOnlyHint`, `destructiveHint` and `idempotentHint` are hints for clients. They do not grant permission or prove the complete side-effect contract. Export writes files; persisted retraction removes entities. Inspect the operation and its result before deciding whether to retry a write.

## Invoke an operation

Initialize the connection as described in [Writing an MCP client](../guides/writing-an-mcp-client.md), then send a JSON-RPC request such as:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "sandbar_entity_find",
    "arguments": {"ident": ":dt/Class", "projection": "full"}
  }
}
```

A successful response has the following envelope shape. The empty entity here illustrates decoding only; it is not a response fixture for the request above.

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{"type": "text", "text": "{\"entity\":{}}"}]
  }
}
```

The catalog's `WHICH`, `WHEN`, `HOW`, `ORDER` and `COMBINATION` sections explain use and composition. These descriptions also supply future persisted `:mm/Verb` cards; regenerating this file does not refresh a running server's catalog or seed its database. An advertised schema describes accepted wire input, not proof that every runtime boundary enforces it.

## Catalog inventory

83 operations across 22 axes. Catalog hints: `[safe]` classified read-only; `[idem]` classified idempotent write; `[unsafe]` classified mutating. These classifications are not authorization or a proof of every side effect. In particular, export writes files. The operation descriptions and [current release limits](../known-gaps-0.2.0.md) qualify use.

<a id="aggregate-4"></a>

## aggregate

### `sandbar.aggregate.count`

Count entities of a class (with optional Datalog filter). Catalog hint: `[safe]`. Wire name: `sandbar_aggregate_count`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':mm/Memory')
- `where` (string) — Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. The counted source population is not clearance-filtered; variable joins can still test hidden targets.

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':mm/Memory')",
      "type" : "string"
    },
    "where" : {
      "description" : "Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. The counted source population is not clearance-filtered; variable joins can still test hidden targets.",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns a single integer count of entities that are instances of `:class` (including subclass instances) matching an optional `:where` Datalog filter.

**WHEN:** use when you need the SIZE of a class's instance set — total count or a filtered subset.  When NOT to use: (a) you also need the entities themselves — use `sandbar.class.instances` (full set) or `sandbar.aggregate.rank-by` (top-K); (b) you need counts BROKEN DOWN BY a slot value — use `sandbar.aggregate.group-by` instead.

**HOW:** `:class` is the class ident (e.g. `:mm/Memory`).  `:where` is an EDN-STRING of Datalog clauses (JSON has no native representation for Datalog symbols `?e`, `?v`); the convention is `?e` for the entity at the head of the count walk.  Example: `"[[?e :mm.memory/memory-type :decision]]"`.

**ORDER:** no prerequisites; leaf-call.  To discover available classes first, use `sandbar.schema.classes`; to discover slot idents on a class, use `sandbar.class.slots`.

**COMBINATION:** composes with `sandbar.aggregate.group-by` (`count` for the cardinality, `group-by` for the breakdown) and with `sandbar.aggregate.rank-by` (use `count` to size the candidate population first).

Result: `{:count <int>}`.

### `sandbar.aggregate.group-by`

Group-by-count facet aggregation over a class's instances. Catalog hint: `[safe]`. Wire name: `sandbar_aggregate_group-by`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident
- `group-by`\* (string) — Slot ident to group by (e.g. ':mm.memory/memory-type')
- `where` (string) — Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. The grouped source population is not clearance-filtered; variable joins can still test hidden targets.

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident",
      "type" : "string"
    },
    "group-by" : {
      "description" : "Slot ident to group by (e.g. ':mm.memory/memory-type')",
      "type" : "string"
    },
    "where" : {
      "description" : "Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. The grouped source population is not clearance-filtered; variable joins can still test hidden targets.",
      "type" : "string"
    }
  },
  "required" : [ "class", "group-by" ],
  "type" : "object"
}
```

**WHICH:** groups instances of `:class` by `:group-by` slot value, returns `{value: count}` map + total.

**WHEN:** use for facet-style breakdowns — 'how many memories of each memory-type?', 'how many properties in each domain?'.  When NOT to use: (a) only the total count is needed — use `sandbar.aggregate.count`; (b) you need ranked instances rather than counts — use `sandbar.aggregate.rank-by`; (c) you need group-counts over a FULLTEXT match-set — use `sandbar.search.bm25f` with `:include [:facets]` opt instead (search → facet in one call).

**HOW:** `:class` is the class ident.  `:group-by` is the slot ident to group by (must be a single-valued slot — multi-cardinality slots will count each value separately).  Optional `:where` (EDN-string Datalog) constrains the candidate set BEFORE grouping.  Entities where `:group-by` is unset are skipped (not counted in any group).

**ORDER:** no prerequisites.  Discover slot idents first via `sandbar.class.slots` if uncertain.

**COMBINATION:** pairs with `sandbar.aggregate.count` (total) and `sandbar.aggregate.rank-by` (top-K within a group, via a follow-up call per group).  Use `sandbar.search.bm25f` `:facet-by` opt for the same shape over a search match-set.

Result: `{:groups {value count} :total <int>}`.

### `sandbar.aggregate.rank-by`

Structural-rank re-ordering by edge degree / backlink-density / recency / freshness. Catalog hint: `[safe]`. Wire name: `sandbar_aggregate_rank-by`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident
- `limit` (integer) — Max hits to return (default 20; 0 = no cap)
- `projection` (string) — Per-hit entity-shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-100× larger payload).  Opt to 'full' when consumers need slot bodies.
- `rank-by`\* (string) — Axis: ':degree' / ':backlink-density' / ':recency' / ':freshness'
- `temporal-slot` (string) — REQUIRED for :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max hits to return (default 20; 0 = no cap)",
      "type" : "integer"
    },
    "projection" : {
      "description" : "Per-hit entity-shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-100× larger payload).  Opt to 'full' when consumers need slot bodies.",
      "type" : "string"
    },
    "rank-by" : {
      "description" : "Axis: ':degree' / ':backlink-density' / ':recency' / ':freshness'",
      "type" : "string"
    },
    "temporal-slot" : {
      "description" : "REQUIRED for :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')",
      "type" : "string"
    }
  },
  "required" : [ "class", "rank-by" ],
  "type" : "object"
}
```

**WHICH:** re-orders instances of `:class` by one of four structural-rank axes:
  * `:degree` — total ref-attribute count (outbound + inbound by default; substrate's most-connected entities)
  * `:backlink-density` — inbound ref-attribute count (who CITES this entity?  prominence-by-citation)
  * `:recency` — descending order by `:temporal-slot` value (most-recently-touched first)
  * `:freshness` — ASCENDING order by `:temporal-slot` value (stalest first; candidates meriting attention/review)

**WHEN:** use for 'top-K' retrieval where ranking is structural, not content-based.  Compose with content-based ranking via `sandbar.search.bm25f` (bm25f scores content; rank-by re-orders structural axes).  When NOT to use: (a) content-relevance ranking — use `sandbar.search.bm25f`; (b) you only need counts — use `sandbar.aggregate.count` / `.group-by`.

**HOW:** `:class` + `:rank-by` are required.  `:rank-by` is the axis keyword.  `:limit` defaults to 20 (0 = no cap).  CRITICAL: `:temporal-slot` is REQUIRED for `:rank-by :recency` and `:freshness` — substrate is class-agnostic; you must supply the temporal-axis slot (e.g. `:mm.memory/last-touched` for memory recency, `:mm.memory/last-reviewed` for freshness).  Calling `:recency` without `:temporal-slot` raises 400.  For `:degree` / `:backlink-density`, no `:temporal-slot` needed.

**ORDER:** no prerequisites.  To discover temporal-axis slot candidates on a class, use `sandbar.class.slots`.

**COMBINATION:** ranked instances become a candidate set for downstream filtering or projection.  For summaries of a selected result set, group the returned records in the client; sandbar.aggregate.group-by has no candidate-ID argument.  This operation does not accept where or a candidate-ID array. Use sandbar.search.bm25f with rank-by for content-constrained structural ranking. Result: `{:hits [{:entity <entity-map> :rank-score <num>} ...] :total <int> :returned <int>}`.

### `sandbar.aggregate.tag-histogram`

Frequency histogram of :mm/Tag usage across the corpus. Catalog hint: `[safe]`. Wire name: `sandbar_aggregate_tag-histogram`.

**Arguments** (`*` = required):

- `limit` (integer) — Cap returned bins (default 0 = no cap)

**Complete input schema:**

```json
{
  "properties" : {
    "limit" : {
      "description" : "Cap returned bins (default 0 = no cap)",
      "type" : "integer"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns a frequency histogram of `:mm/Tag` usage across the corpus.  Each bin is `{:tag <ident|value|eid> :value <string> :count <int>}` — count is the number of entities (any class) that reference the tag via inbound edges.

**WHEN:** use for tag-vocabulary observability — 'which tags are most-used?', 'which tags are orphans (used by ≤1 entity)?'.  When NOT to use: (a) you want one tag's full citing-set — use `sandbar.navigate.inbound-edges` with the tag as `:entity`; (b) you want tag schema-introspection (not usage) — use `sandbar.tag.lookup`; (c) you want audit-shaped tag concerns (undefined-used, orphans, drift) — use `sandbar.tag.audit`.

**HOW:** optional `:limit` caps returned bins (default 0 = no cap); sorted descending by count then ascending by stringified-tag-identifier as tie-breaker.

**ORDER:** leaf-call shape.

**COMBINATION:** pairs with `sandbar.tag.audit` (qualitative tag concerns) and `sandbar.tag.lookup` (single-tag detail).

Result: `{:histogram [{:tag <ident|value|eid> :value <string> :count <int>} ...] :total <int>}`.

<a id="audit-1"></a>

## audit

### `sandbar.audit.fs-substrate-drift`

Compare document files with stored Memory entities. Catalog hint: `[safe]`. Wire name: `sandbar_audit_fs-substrate-drift`.

**Arguments** (`*` = required):

- `from`\* (string) — Corpus root directory path (matches :from arg of sandbar.project.import / .export)

**Complete input schema:**

```json
{
  "properties" : {
    "from" : {
      "description" : "Corpus root directory path (matches :from arg of sandbar.project.import / .export)",
      "type" : "string"
    }
  },
  "required" : [ "from" ],
  "type" : "object"
}
```

**WHICH:** compare Markdown files with their stored :mm/Memory entities and report explicit enrollment-preflight conditions. The report identifies missing entities, missing files, content divergence, reference-slot mismatch, files whose paths re-enter the memory root, and multiple database entities claiming one normalized path. Policy-excluded classes are reported separately from drift.

**WHEN:** use to diagnose file/database differences before and after maintenance and to inspect whether a configured project tree has cleared the reported enrollment conditions. A discrepancy is evidence to investigate, not authorization to delete either version. Keep both versions of unresolved historical ambiguities.

**HOW:** from is the project or memory-root directory. For /path/to/project with a memory child, the walk is confined to /path/to/project/memory; passing that memory directory selects the same effective root. The summary reports walk-root, root-mode and root-attribution. A configured project tree or the global tree is compared with the entities routed to that tree; an ad hoc directory uses the store-wide population and reports that limitation. Result keys include summary, missing-from-substrate, missing-from-fs, missing-from-fs-policy-excluded, content-divergence, ref-slot-mismatch, twins, substrate-rel-path-collisions, fs-parse-failed and enrollment-preflight. Content comparison normalizes nil, empty and whitespace-only bodies; inspect differences when exact bytes matter.

ENROLLMENT PREFLIGHT: :enrollment-preflight reports :files-without-uuid, :files-ambiguous-uuid, :documents-without-uuid, and :ownership rows for absent, ambiguous or mismatched file identities against stored :mm/id. It also reports :noncanonical-paths, :alias-groups resolving to the same physical target (with case folding only where the filesystem does), :invalid-owners, :files-of-other-trees and :document-collisions. Every document claiming a compared path is checked; a representative used for content comparison does not hide the other claimants. Invalid owners are a separately named store-wide population; only those with files in this tree count toward its unresolved conditions, except the global tree counts all invalid owners.

READINESS AND ERRORS: :clear-for-enrollment? is true only for a configured mapped or global tree with :unresolved-count zero and :error-count zero; an ad hoc directory is never clear. The summary mirrors these as :enrollment-preflight-clear?, :enrollment-preflight-unresolved-count and :enrollment-preflight-error-count. Unresolved counts are condition observations, not unique paths. Source parse failures are named in :fs-parse-failed and :summary/:fs-parse-failed-count. The preflight's :errors additionally names read-identity, canonicalize and parse failures by :phase; errors are unknowns, never a clean zero. Check these explicit fields: :total-drift-count does not include the enrollment or source-error counts. Clearance covers these conditions only, not complete content equivalence, a backup or a confidentiality proof. The MCP input is from only; this verb does not stamp, repair or enroll files.

**ORDER:** a read-only diagnostic. To compare a stable maintenance state, keep all writers stopped through import, any maintenance identity stamp, and the post-import audit.

**COMBINATION:** pairs with sandbar.entity.find-by-rel-path and sandbar.entity.find for investigation, and sandbar.project.import and sandbar.project.export for reviewed maintenance.

<a id="class-8"></a>

## class

### `sandbar.class.describe`

Full class description — abstract? + parents + ancestors + subclasses + slots. Catalog hint: `[safe]`. Wire name: `sandbar_class_describe`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns the comprehensive descriptor for a class — its abstract-flag, direct parents, all ancestors, direct subclasses, and effective slot set.  The 'inspect this class' verb.

**WHEN:** use after picking a class from `sandbar.schema.classes` to understand its full shape before creating instances or composing queries.  Most useful first-touch verb for a new-to-you class.  When NOT to use: (a) only the slot set is needed — `sandbar.class.slots` (lighter); (b) only the hierarchy is needed — `sandbar.class.hierarchy` (via the REST endpoint; not in MCP catalog) or compose `parents` + `subclasses`; (c) you want the INSTANCES — `sandbar.class.instances`.

**HOW:** `:class` is the class ident string (e.g. `:mm/Memory`).  Returns `{:class <ident-string> :abstract? <bool> :parents [...] :ancestors [...] :subclasses [...] :slots [...]}`.  `:slots` is sorted; `:subclasses` is in no particular order — compare it as a set.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class (a memorial, a property); earlier revisions answered an internal error or an empty descriptor.  Definitions stay introspectable for every namespace; this check adds existence and kind, not scope.

**ORDER:** typical sequence — `sandbar.schema.classes` (discover) → `sandbar.class.describe :class :foo/X` (this verb; inspect) → `sandbar.class.instances` or `sandbar.entity.create`.

**COMBINATION:** the slot list feeds `sandbar.property.{domain,range,cardinality}` for per-slot deep inspection.  Subclass list feeds polymorphic `sandbar.class.instances` calls.  Per-class typed-edge composition feeds `sandbar.orient.library-card` axis-specs.

### `sandbar.class.direct-slots`

Directly-declared slots only (no inheritance). Catalog hint: `[safe]`. Wire name: `sandbar_class_direct-slots`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns slots declared directly on `:class` — EXCLUDING inherited slots from `:dt/subclass-of` ancestors.

**WHEN:** use when you need to know what THIS class adds beyond its parents — e.g., for class-evolution analysis, schema-shape comparison between siblings, or understanding the class's own contribution to the surface.  When NOT to use: (a) you want the full effective set (inherited + direct) — `sandbar.class.slots`; (b) you want just the required subset — `sandbar.class.required-slots`.

**HOW:** `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`, sorted; an empty vector means the class declares no slots of its own.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to answer an empty vector.

**ORDER:** no prerequisites.

**COMBINATION:** subtract from `sandbar.class.slots` result to derive the INHERITED slots.  Use alongside `sandbar.class.parents` to understand how the class augments its ancestors.

### `sandbar.class.instances`

All instances of a class (incl. subclass instances). Catalog hint: `[safe]`. Wire name: `sandbar_class_instances`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')
- `projection` (string) — Per-entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies.

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    },
    "projection" : {
      "description" : "Per-entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies.",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns every entity that is an instance of `:class` — directly OR via `:dt/subclass-of` (i.e., subclass instances are included; instance-of relation is transitive through inheritance).

**WHEN:** use to enumerate a class's full instance population.  Foundational read for any class-based traversal.  When NOT to use: (a) the population is large and you only want top-K by some rank — `sandbar.aggregate.rank-by`; (b) you want only DIRECT instances (no subclass instances) — there's no MCP verb for this in the current catalog; substrate has `dt/direct-instances-of` accessible via in-process Clojure; (c) you want a count, not the entities — `sandbar.aggregate.count`; (d) you want to filter by some predicate — `sandbar.aggregate.count` / `.group-by` with `:where` Datalog, or `sandbar.search.bm25f` for fulltext-filtered instances.

**HOW:** `:class` is the class ident.  Optional `:projection` — `metadata-only` (default at MCP boundary; `:db/id` + `:db/ident` + `:dt/type` per entity; 10-300x payload reduction) or `full` (all slots; recursive ref-projection to one-hop-deep metadata-only).  Returns `{:class <ident-string> :instances [<entity-map>...]}`.

**ORDER:** typical sequence — `sandbar.schema.classes` (discover class) → `sandbar.class.describe` (inspect) → `sandbar.class.instances` (this verb; enumerate).  No strict prerequisites.

**COMBINATION:** pairs with `sandbar.aggregate.rank-by` (rank the enumerated set), `sandbar.aggregate.group-by` (faceted counts), `sandbar.search.bm25f` (fulltext-search within a class's instances).  For batch fetch across multiple classes, use `sandbar.schema.entities` (N+1 elimination) instead.

### `sandbar.class.parents`

Direct parents + transitive ancestors of a class. Catalog hint: `[safe]`. Wire name: `sandbar_class_parents`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns the class's direct `:dt/subclass-of` parents AND the full transitive-ancestor list (walks up to `:dt/Resource` typically).

**WHEN:** use to understand a class's inheritance lineage — what slots / behaviors does it inherit?  Especially useful when debugging unexpected attribute behavior (slot might be declared on an ancestor).  When NOT to use: (a) you only want the DIRECT parents — currently returned alongside ancestors in this verb's result (filter result-side); (b) you want a subclass-of predicate test — `sandbar.types.subclass-of`.

**HOW:** `:class` is the class ident.  Returns `{:class <ident-string> :parents [<direct-parents>...] :ancestors [<all-ancestors>...]}`; the root class answers two empty vectors.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to answer two empty vectors.

**ORDER:** no prerequisites.

**COMBINATION:** pairs with `sandbar.class.direct-slots` per ancestor to trace inherited slot origins.  Use alongside `sandbar.types.subclass-of` for polymorphic-dispatch decisions.

### `sandbar.class.required-slots`

Required slots of a class (`:dt/required? true`). Catalog hint: `[safe]`. Wire name: `sandbar_class_required-slots`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns the subset of effective slots flagged `:dt/required? true` on `:class`.  Required slots must be supplied when creating instances via `sandbar.entity.create`.

**WHEN:** use as a PREREQUISITE check before calling `sandbar.entity.create` — to confirm the slot map contains every required key.  Also useful for documentation generation and error-message authoring ("missing required slot X").  When NOT to use: (a) you want all slots — `sandbar.class.slots`; (b) you want validation FEEDBACK after providing a slot map — `sandbar.entity.validate` (pre-transaction validation with error details).

**HOW:** `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`, sorted.  This is PROPERTY-LEVEL requiredness only — the slots whose property carries `:dt/required? true` (for example the four on `:mm/Project`: `corpus-repo`, `default-visibility`, `ident`, `runs-in-context`).  The memorial classes (Observation, Decision, Inbox, …) declare none at that level and answer an empty vector; their requirements (a name, a relative path, …) are enforced by shapes and the strict front matter at `sandbar.entity.create`, so an empty answer here does not mean a create will accept an empty slot map — use `sandbar.entity.validate` for that question.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to escape as an internal error.

**ORDER:** a pre-step before `sandbar.entity.create` for classes that declare property-level requirements; for a memorial class, `sandbar.entity.validate` is the pre-step that answers.

**COMBINATION:** pairs with `sandbar.entity.validate` (full pre-transaction validation including required-check AND type-conformance) and `sandbar.entity.create` (the actual mutation).

### `sandbar.class.slots`

Effective slot set of a class (inherited + directly-declared). Catalog hint: `[safe]`. Wire name: `sandbar_class_slots`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns the sorted vec of slot idents that apply to instances of this class — including slots inherited from `:dt/subclass-of` ancestors PLUS slots declared directly on the class.

**WHEN:** use to enumerate the FULL attribute surface available for instances of a class — for entity creation, validation, or query composition.  This is the 'what fields does this class have' question.  When NOT to use: (a) you want only the directly-declared slots (excluding inherited) — `sandbar.class.direct-slots`; (b) you want only REQUIRED slots — `sandbar.class.required-slots`; (c) you want to know which classes a property applies to — `sandbar.property.domain`.

**HOW:** `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`, sorted.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to escape as an internal error.

**ORDER:** foundational; no prerequisites beyond knowing the class ident (discover via `sandbar.schema.classes` if needed).

**COMBINATION:** feeds `sandbar.property.range` (per-slot value type for entity construction), `sandbar.entity.create` (slot map keys), and `sandbar.aggregate.group-by` (`:group-by` candidate slots).  For each slot's typed-edge nature (is it a `:db.type/ref` for navigation purposes?), call `sandbar.property.range`.

### `sandbar.class.subclasses`

All transitive subclasses of a class. Catalog hint: `[safe]`. Wire name: `sandbar_class_subclasses`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** returns the sorted vec of every class that is a `:dt/subclass-of` descendant of `:class` (direct + transitive).

**WHEN:** use to discover the polymorphic surface of a class — what concrete classes might satisfy 'instance of `:class`'?  E.g., subclasses of `:dt/Resource` is essentially every domain class.  When NOT to use: (a) you want only DIRECT subclasses — no MCP verb in current catalog (substrate has `dt/direct-subclasses-of`); (b) you want to test 'is X a subclass of Y' specifically — `sandbar.types.subclass-of` predicate.

**HOW:** `:class` is the parent class ident.  Returns `{:class <ident-string> :subclasses [<ident-string>...]}`, sorted; an empty vector means a leaf class.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to escape as an internal error.

**ORDER:** no prerequisites.

**COMBINATION:** with `sandbar.class.instances` over each subclass for instance enumeration; with `sandbar.aggregate.group-by` (`:group-by :dt/subclass-of`) for hierarchy distribution analysis.

### `sandbar.class.validate-all-instances`

Run validation against every instance of a class; return the report. Catalog hint: `[safe]`. Wire name: `sandbar_class_validate-all-instances`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** validates every entity that is an instance of `:class` (including subclass instances) against the class's declared slot constraints (required, range, custom validators).  Returns a per-entity validation report.

**WHEN:** use for batch schema-conformance checking — e.g., after a schema change, before a migration, or for periodic substrate-health audits.  When NOT to use: (a) you want to validate ONE entity's proposed slot map without committing — `sandbar.entity.validate` (pre-transaction); (b) you want CANCELLABLE / long-running validation with workflow-backed history — `sandbar.validation.start` (the workflow-backed equivalent for large classes).

**HOW:** `:class` is the class ident.  Returns `{:class <ident-string> :report <validation-report>}`.

**ORDER:** no prerequisites.  Synchronous — for large classes this can be slow; consider `sandbar.validation.start` for the workflow-backed equivalent.

**COMBINATION:** alternative to `sandbar.validation.start` (workflow-backed; better for large classes).  Pairs with `sandbar.entity.validate` (per-entity pre-transaction check) and `sandbar.entity.update` (after fixing failures, update affected entities).

<a id="codec-1"></a>

## codec

### `sandbar.codec.list`

List registered codecs (wire-format mediator inventory). Catalog hint: `[safe]`. Wire name: `sandbar_codec_list`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the codecs currently registered with the Sandbar codec mediator — one entry per codec, each `{:format <keyword> :mime-types [<string>...]}`, sorted by format.  The `:format` value (for example `:markdown`, serialised as `"markdown"`) is exactly the spelling `sandbar.entity.create` accepts as its `:format` argument (a leading colon is tolerated there); there is no `:codec/` namespace, and the entry carries no per-codec class list — a class's codec is its own `:dt/native-codec` declaration.

**WHEN:** use to discover what wire formats Sandbar can parse / emit.  Foundational for codec-driven entity construction (`sandbar.entity.create` with `:format` + `:source`) and for projection/ingestion (`sandbar.project.export` / `.import` choose codecs per class's `:dt/native-codec`).  When NOT to use: (a) you want a specific class's declared native codec — `sandbar.entity.find` on the class ident and read its `:dt/native-codec` slot (`sandbar.class.describe` does not return it); (b) you want to register a NEW codec — not exposed via MCP; programmatic Clojure call against `sandbar.codec`.

**HOW:** no arguments.  Returns `{:codecs [<codec-info>...]}`.

**ORDER:** foundational discovery.

**COMBINATION:** pairs with `sandbar.entity.create` (use `:format` + `:source` opts with one of the listed codec keywords) and `sandbar.project.export` / `.import` (codecs underpin the bidirectional projection).

<a id="entity-6"></a>

## entity

### `sandbar.entity.create`

Create a typed entity with optional codec parsing. Catalog hint: `[unsafe]`. Wire name: `sandbar_entity_create`.

**Arguments** (`*` = required):

- `allow-unknown-keys` (boolean) — Opt out of strict front matter: carry unknown keys in the front-matter carrier instead of refusing them.  Default false.
- `class`\* (string) — Class ident (concrete, not abstract)
- `format` (string) — Optional codec format — one of the :format values sandbar.codec.list returns (e.g. markdown; a leading colon is tolerated); requires :source
- `slots` (object) — Slot map (slot-ident-string → value); optional when :source is provided
- `source` (string) — Optional raw native-representation string parsed via :format codec
- `validation-mode` (string) — Shape validation: 'audit' (default) commits and returns the shape report; 'strict' validates against the class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with nothing committed, enqueued or notified; 'disabled' skips the shapes.

**Complete input schema:**

```json
{
  "properties" : {
    "allow-unknown-keys" : {
      "description" : "Opt out of strict front matter: carry unknown keys in the front-matter carrier instead of refusing them.  Default false.",
      "type" : "boolean"
    },
    "class" : {
      "description" : "Class ident (concrete, not abstract)",
      "type" : "string"
    },
    "format" : {
      "description" : "Optional codec format — one of the :format values sandbar.codec.list returns (e.g. markdown; a leading colon is tolerated); requires :source",
      "type" : "string"
    },
    "slots" : {
      "description" : "Slot map (slot-ident-string → value); optional when :source is provided",
      "type" : "object"
    },
    "source" : {
      "description" : "Optional raw native-representation string parsed via :format codec",
      "type" : "string"
    },
    "validation-mode" : {
      "description" : "Shape validation: 'audit' (default) commits and returns the shape report; 'strict' validates against the class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with nothing committed, enqueued or notified; 'disabled' skips the shapes.",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** creates a new entity of `:class` from a slot map (or from a raw wire-format source via the codec mediator), checks its declared slot requirements, value types and cardinalities, and transacts it into the substrate.  The primary mutation verb.

**WHEN:** use to bring a new entity into the substrate — whether constructing from explicit slot values (programmatic) or from a raw representation in a registered codec format (normally Markdown for `:mm/Memory`; discover available formats with `sandbar.codec.list`).  When NOT to use: (a) updating an EXISTING entity — `sandbar.entity.update`; (b) you want to validate without committing — `sandbar.entity.validate` (pre-transaction); (c) entity already exists and you want to read it back — `sandbar.entity.find`.

**HOW:** `:class` is the target class ident (REQUIRED; abstract classes rejected).  `:slots` is a slot-ident-string → value map (optional if `:source` is provided).  `:format` + `:source` (both optional, must come together) invoke the codec mediator: `:source` is parsed as the named wire format (e.g., `:markdown`), parsed slots merge with explicit `:slots` (explicit wins on conflict).  Checks required slots, declared value types and cardinality before transacting; raises ex-info on validation failure. This pre-transaction slot check does not run class custom validators. A stored-entity audit through sandbar.class.validate-all-instances includes those validators; shape validation is a separate mechanism.  SHAPES (`:validation-mode`): `audit` (default) commits and returns the shape report; `strict` validates against the class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with nothing committed, enqueued or notified — a change accepted between the check and the commit is caught by a transactor-side basis guard and the check re-runs; `disabled` skips the shapes.  Bulk `sandbar.project.import` does not run shape validation. Use sandbar.shape.conformance-report for an explicit post-import shape audit; schema validation is a separate check.  STRICT FRONT MATTER: with `:format` markdown, an unknown front-matter key, an empty or mis-shaped value, or a value of the wrong primitive type is REFUSED before anything is built, with a verdict naming each refused key and its reason, the class's accepted keys, and the opt-out; nothing is committed, enqueued or notified.  `:allow-unknown-keys` true opts out: unknown keys ride in the front-matter carrier instead (the bulk-import behaviour).

**ORDER:** prerequisites — discover `:class` via `sandbar.schema.classes`; understand required slots via `sandbar.class.required-slots`; understand slot value types via `sandbar.property.range`.  Optional pre-check: `sandbar.entity.validate` (validates a slot map WITHOUT committing).

**COMBINATION:** paired with `sandbar.entity.validate` (pre-check), `sandbar.entity.find` (read back), `sandbar.entity.update` (subsequent mutations).  For codec-driven creation, ensure the codec is registered via `sandbar.codec.list`.

### `sandbar.entity.find`

Look up an entity by ident or eid. Catalog hint: `[safe]`. Wire name: `sandbar_entity_find`.

**Arguments** (`*` = required):

- `id` (integer) — Entity eid (numeric)
- `ident` (string) — Entity ident (keyword string, e.g. ':memory.decisions/foo' for corpus memories or ':dt/Class' for metamodel)
- `projection` (string) — Entity shape: 'full' (default; complete entity-map) or 'metadata-only' (just :db/id/:db/ident/:dt/type — for lightweight pre-check / enumeration use cases)

**Complete input schema:**

```json
{
  "properties" : {
    "id" : {
      "description" : "Entity eid (numeric)",
      "type" : "integer"
    },
    "ident" : {
      "description" : "Entity ident (keyword string, e.g. ':memory.decisions/foo' for corpus memories or ':dt/Class' for metamodel)",
      "type" : "string"
    },
    "projection" : {
      "description" : "Entity shape: 'full' (default; complete entity-map) or 'metadata-only' (just :db/id/:db/ident/:dt/type — for lightweight pre-check / enumeration use cases)",
      "type" : "string"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** looks up an entity by `:ident` (interned keyword) or `:id` (numeric eid).  Returns the entity-map projection (`:db/id`, `:db/ident` if interned, namespaced-keyword slots).

**WHEN:** use to fetch the current state of a known entity.  Most common 'read one entity' verb.  When NOT to use: (a) you want all instances of a class — `sandbar.class.instances`; (b) you want fulltext search — `sandbar.search.bm25f`; (c) you don't know the ident — discover via `sandbar.class.instances` first; (d) you have a corpus rel-path (e.g. 'decisions/foo.md') but not the ident — use `sandbar.entity.find-by-rel-path` instead.

**HOW:** provide ONE of `:ident` (keyword-form string) OR `:id` (numeric eid).  IDENT FORM: corpus :mm/Memory entities use the `memory.`-prefixed namespace convention — e.g. `":memory.decisions/foo"` (NOT `":decisions/foo"`); `":memory.notes.examples/sample"` for nested dirs.  Metamodel idents (`:dt/Class`, `:mm/Memory`, `:mm.tag/value`, etc.) use their own namespaces and don't have the memory. prefix.  Returns `{:entity <entity-map>}` if found, or `{:entity nil :missing? true :lookup <provided> :reasons #{}}` if not found.  A memory-shaped entity whose confidentiality compartment the authenticated principal does not clear answers that SAME not-found shape (no existence oracle) — the one visibility decision `resources/read`, `resources/list` and the REST entity read also apply.

**ORDER:** leaf-call; no prerequisites.

**COMBINATION:** pre-step before `sandbar.entity.update` (confirm the entity exists); after `sandbar.entity.create` (read back the created entity, though create returns the entity directly so this is rarely needed).  For RELATED entities, use `sandbar.navigate.{outbound,inbound,siblings-of}` or `sandbar.orient.library-card`.  When you have a filesystem rel-path instead of an ident, use `sandbar.entity.find-by-rel-path` to avoid ident-guessing.

### `sandbar.entity.find-by-rel-path`

Look up an :mm/Memory entity by corpus rel-path. Catalog hint: `[safe]`. Wire name: `sandbar_entity_find-by-rel-path`.

**Arguments** (`*` = required):

- `projection` (string) — Entity shape: 'full' (default; complete entity-map) or 'metadata-only' (just :db/id/:db/ident/:dt/type)
- `rel-path`\* (string) — Corpus rel-path (e.g. 'decisions/foo.md' or 'memory/decisions/foo.md'); leading 'memory/' and trailing '.md' optional

**Complete input schema:**

```json
{
  "properties" : {
    "projection" : {
      "description" : "Entity shape: 'full' (default; complete entity-map) or 'metadata-only' (just :db/id/:db/ident/:dt/type)",
      "type" : "string"
    },
    "rel-path" : {
      "description" : "Corpus rel-path (e.g. 'decisions/foo.md' or 'memory/decisions/foo.md'); leading 'memory/' and trailing '.md' optional",
      "type" : "string"
    }
  },
  "required" : [ "rel-path" ],
  "type" : "object"
}
```

**WHICH:** looks up an :mm/Memory entity by its corpus rel-path (e.g. 'notes/example.md').  Resolves the rel-path to the substrate's `:memory.<dir>/<name>` ident via the canonical codec convention, then returns the entity-map projection.

**WHEN:** use when you have a corpus filesystem path on hand and need the entity — without reverse-engineering the substrate's ident form.  The most common 'I know the file path, give me the entity' use case.  When NOT to use: (a) you already have the ident — `sandbar.entity.find` (slightly faster — skips rel-path parsing); (b) the entity isn't an :mm/Memory (e.g., :mm/Tag, :dt/Class) — those don't use the `memory.X/Y` ident convention so `sandbar.entity.find` with the appropriate ident is the right call; (c) fulltext search — `sandbar.search.bm25f`.

**HOW:** `:rel-path` is the corpus rel-path string.  Accepts forms with or without the leading 'memory/' prefix: 'decisions/foo.md' AND 'memory/decisions/foo.md' both resolve to `:memory.decisions/foo`.  The .md extension is optional but conventional.  Returns `{:entity <entity-map> :resolved-ident <ident-string>}` if found, or `{:entity nil :missing? true :lookup <rel-path> :resolved-ident <ident-or-nil> :reasons <set>}` if not found.  The `:resolved-ident` field is included on both success and miss so consumers see what ident the rel-path mapped to.  An entity whose compartment the authenticated principal does not clear answers the miss shape (no existence oracle), as `sandbar.entity.find` does.

**ORDER:** leaf-call; no prerequisites.

**COMBINATION:** pairs with `sandbar.orient.library-card` / `sandbar.navigate.*` for typed-edge exploration once the entity is in hand.

### `sandbar.entity.retract`

Retract explicit entities with a dry-run-by-default safety layer. Catalog hint: `[unsafe]`. Wire name: `sandbar_entity_retract`.

**Arguments** (`*` = required):

- `acknowledge-dangling` (boolean) — When true, PERMIT a :persist that leaves inbound citation edges (the report's :inbound-refs) dangling.  Default false ⇒ a persist over a target with nonzero :inbound-count is refused loudly (repoint the inbound refs first, or acknowledge the dangle).
- `actor` (string) — Optional actor ref recorded on the audit event.
- `cascade` (boolean) — When true, include the target's enumerated dependents (section tree + frontmatter carrier) in the retraction.  Default false — dependents survive as orphans (refs are not :db/isComponent).
- `persist` (boolean) — When true, COMMIT the retraction.  Default false = DRY-RUN (report only, transacts nothing).  Wire key MUST be `persist` (no `?`) per the MCP property-key regex.
- `reason` (string) — REQUIRED when :persist — human-readable audit string carried into the :mm.event/EntityRetracted event.
- `targets`\* (array) — Explicit targets (1..100): idents (keyword-strings, e.g. ':memory.decisions/foo') or numeric eids

**Complete input schema:**

```json
{
  "properties" : {
    "acknowledge-dangling" : {
      "description" : "When true, PERMIT a :persist that leaves inbound citation edges (the report's :inbound-refs) dangling.  Default false ⇒ a persist over a target with nonzero :inbound-count is refused loudly (repoint the inbound refs first, or acknowledge the dangle).",
      "type" : "boolean"
    },
    "actor" : {
      "description" : "Optional actor ref recorded on the audit event.",
      "type" : "string"
    },
    "cascade" : {
      "description" : "When true, include the target's enumerated dependents (section tree + frontmatter carrier) in the retraction.  Default false — dependents survive as orphans (refs are not :db/isComponent).",
      "type" : "boolean"
    },
    "persist" : {
      "description" : "When true, COMMIT the retraction.  Default false = DRY-RUN (report only, transacts nothing).  Wire key MUST be `persist` (no `?`) per the MCP property-key regex.",
      "type" : "boolean"
    },
    "reason" : {
      "description" : "REQUIRED when :persist — human-readable audit string carried into the :mm.event/EntityRetracted event.",
      "type" : "string"
    },
    "targets" : {
      "description" : "Explicit targets (1..100): idents (keyword-strings, e.g. ':memory.decisions/foo') or numeric eids",
      "items" : {
        "type" : "string"
      },
      "type" : "array"
    }
  },
  "required" : [ "targets" ],
  "type" : "object"
}
```

**WHICH:** retracts an EXPLICIT set of entities (`:targets` — idents or eids, 1..100) via `:db.fn/retractEntity` in ONE atomic transaction, wrapped in the retraction safeguards: dry-run-by-default, per-target blast-radius report, protected-namespace/class guard, cascade opt-in, required audit reason.  The first-class MCP retraction verb — replaces the nREPL-toolchain workaround.  Substrate half is `sandbar.db.datomic/retract-entity`; this verb is the MCP surface + safety layer.

**WHEN:** use to remove named entities from the substrate — cleanup packages (bulk-retract, bare-ident dups, orphan sections, anonymous carriers).  When NOT to use: (a) predicate/query-based MASS retraction — NOT supported in v1 (explicit targets only; enumerate first via `sandbar.class.instances` / `sandbar.search.bm25f`, then pass the eids); (b) you want to EDIT an entity — `sandbar.entity.update`; (c) you want to physically excise history — out of scope (this is logical retraction).

**HOW:** `:targets` (REQUIRED) is an array of idents (keyword-strings like `":memory.decisions/foo"`) or numeric eids; 1..100 (over-cap ⇒ loud error).  `:persist` (bool, default FALSE) — WITHOUT it the verb is a DRY-RUN returning the full report and transacting NOTHING (same convention as `sandbar.project.import`; wire key is `persist`, no `?`).  `:cascade` (bool, default false) — when true, the enumerated dependents (the target's `:mm/Section` tree + `:mm.memory/frontmatter` carrier) are INCLUDED in the retraction; the section refs are not `:db/isComponent` so without cascade the tree survives as ORPHANS (the report says so), while the frontmatter carrier is a component and retracts with its host either way.  THE FILE GOES WITH THE ENTITY: after a persisted retraction the projected file each target owned is removed — only when no other live entity claims the rel-path and the file's front-matter id matches the entity's `mm/id` — and reported under `:files` as `:removed`, `:kept` (with a reason), `:absent` or `:failed` (an I/O failure is reported, never hidden); the dry run carries each target's `:file-effect`.  `:reason` (string) — REQUIRED when `:persist` (carried into the `:mm.event/EntityRetracted` audit event; persist without it ⇒ loud error).  `:actor` (optional ref) — recorded on the audit event.  `:acknowledge-dangling` (bool, default false) — the report's `:inbound-refs`/`:inbound-count` per target enumerate the INBOUND citation edges (`:mm.memory/cites` / `:mm.memory/motivated-by` / any ref) that would be left DANGLING by the retraction; a `:persist` over a target with nonzero inbound refs is REFUSED unless you pass `:acknowledge-dangling true` (or repoint those inbound edges first).  Protected targets (namespace `dt`/`db`/`workflow`/`mm.event`, plus `:mm/Actor` instances + `:mm/Workflow` definitions) are SKIPPED with a reason, NOT retracted, and do NOT abort the batch.

**ORDER:** run once WITHOUT `:persist` to inspect the blast-radius report (datom-counts + outbound dependents + INBOUND refs + protected flags), then re-run WITH `:persist true` + `:reason` (and `:acknowledge-dangling true` if inbound refs exist and you accept the dangle) to commit.  Discover target eids first via `sandbar.class.instances` / `sandbar.search.bm25f` / `sandbar.entity.find`.

**COMBINATION:** pairs with `sandbar.entity.find` (confirm a target exists first); the inbound-citation check `sandbar.navigate.inbound-edges` once needed before orphaning is now BUILT IN as the report's `:inbound-refs` section.  Result: the dry-run report `{:targets [{:target :resolved-eid :exists? :ident :dt-type :datom-count :dependents :inbound-refs :inbound-count :protected? :protection-reason} ...] :cascade :dependents-note}`, augmented on `:persist` with `:retracted-eids :retracted-count :skipped :events-emitted`.

### `sandbar.entity.update`

Update slots on an existing entity. Catalog hint: `[idem]`. Wire name: `sandbar_entity_update`.

**Arguments** (`*` = required):

- `additive` (boolean) — Cardinality-many semantics.  DEFAULT false ⇒ the supplied value REPLACES the slot's prior set (members you omit are retracted).  true ⇒ legacy additive UNION (append the supplied value without retracting).  No effect on cardinality-one slots.
- `entity`\* (string) — Entity ident (keyword string) or eid (numeric).  Identless entities accepted by eid.
- `projection` (string) — Response :result entity shape — 'metadata-only' (default; :db/id + :db/ident + :dt/type) or 'full' (complete entity-map; ~10-300x larger; risks wire-limit overflow on large bodies).
- `slots`\* (object) — Slot-ident-string → new-value map
- `validation-mode` (string) — Shape validation: 'audit' (default) commits and returns the shape report; 'strict' validates the updated entity against its class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with the entity unchanged and nothing enqueued; 'disabled' skips the shapes.

**Complete input schema:**

```json
{
  "properties" : {
    "additive" : {
      "description" : "Cardinality-many semantics.  DEFAULT false ⇒ the supplied value REPLACES the slot's prior set (members you omit are retracted).  true ⇒ legacy additive UNION (append the supplied value without retracting).  No effect on cardinality-one slots.",
      "type" : "boolean"
    },
    "entity" : {
      "description" : "Entity ident (keyword string) or eid (numeric).  Identless entities accepted by eid.",
      "type" : "string"
    },
    "projection" : {
      "description" : "Response :result entity shape — 'metadata-only' (default; :db/id + :db/ident + :dt/type) or 'full' (complete entity-map; ~10-300x larger; risks wire-limit overflow on large bodies).",
      "type" : "string"
    },
    "slots" : {
      "description" : "Slot-ident-string → new-value map",
      "type" : "object"
    },
    "validation-mode" : {
      "description" : "Shape validation: 'audit' (default) commits and returns the shape report; 'strict' validates the updated entity against its class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with the entity unchanged and nothing enqueued; 'disabled' skips the shapes.",
      "type" : "string"
    }
  },
  "required" : [ "entity", "slots" ],
  "type" : "object"
}
```

**WHICH:** applies slot-value updates to an existing entity.  Validates the updated slot map against the entity's class constraints before transacting.  SHAPES (`:validation-mode`): `audit` (default) commits and returns the shape report; `strict` validates the updated entity against its class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with the entity unchanged and nothing enqueued — a change accepted between the check and the commit is caught by a transactor-side basis guard and the check re-runs; `disabled` skips the shapes.  Accepts identful AND identless entities.

**WHEN:** use to MUTATE an existing entity — change a slot value, set a previously-empty slot, etc.  When NOT to use: (a) creating a new entity — `sandbar.entity.create`; (b) you want to validate proposed updates WITHOUT committing — `sandbar.entity.validate` (against the class with the merged slot map); (c) you want to fully retract a cardinality-ONE slot — Datomic full-retraction is not exposed via MCP (note: cardinality-MANY members ARE removable now by passing a smaller set under the default replace semantics, or pass `additive: true` to only append).

**HOW:** `:entity` is the target entity ident or eid (REQUIRED).  `:slots` is a slot-ident-string → new-value map (REQUIRED; non-map values rejected).  Substrate auto-coerces JSON-shaped values via `dt/range-of` (e.g., `:db.type/keyword` slots accept either keyword strings or already-coerced keywords).  Cardinality-many slots accept either a single value (wrapped to vec) or a vec / array, and by DEFAULT the supplied value REPLACES the slot's prior set — members absent from your value are retracted in the same tx, so you can now shrink or clear a card-many slot.  Pass `additive: true` to keep the legacy additive UNION (append without retracting).  Card-one slots are unaffected.  Optional `:projection` — `metadata-only` (DEFAULT — lightweight :db/id/:db/ident/:dt/type echo; avoids MCP wire-limit overflow on large entities) or `full` (complete entity-map; opt in when you want the body echo). Whole-body edits reconcile sections in the same transaction for Memory classes whose native body is :mm.memory/body-raw. Existing document identity and unrelated slots are retained. An externally referenced removed section, a foreign section identity collision, or a simultaneous document-ident change refuses the edit. A body edit is planned once at one database basis and given one guarded transaction, with speculative preflight only in strict mode; if the database moved in between (any writer, not necessarily on this memory), the edit is refused with `isError`, `body-update/basis-moved` in its details, `retryable? true`, and the instruction to read the memory again and retry — the fixed plan is neither reused nor silently replanned. That refusal is specific to body edits; the strict-mode preflight retry for generic strict writes (a moved basis re-runs the shape check, up to three attempts) is unchanged and does not apply to a body plan. Sectioned edits require a stable document ident; identless plain-body edits remain supported. This does not decompose initial entity.create bodies or reconcile other class-specific body slots.

**ORDER:** prerequisite — `sandbar.entity.find` to confirm the entity exists.  Optional pre-check: `sandbar.entity.validate` against the FULL merged slot map (current slots ∪ updates).

**COMBINATION:** pairs with `sandbar.entity.find` (pre-confirm + post-read-back).  For bulk class-wide updates, no single-call alternative; iterate `sandbar.class.instances` and apply per-entity.

### `sandbar.entity.validate`

Pre-transaction validation of a slot map against a class. Catalog hint: `[safe]`. Wire name: `sandbar_entity_validate`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident
- `slots`\* (object) — Slot map to validate

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident",
      "type" : "string"
    },
    "slots" : {
      "description" : "Slot map to validate",
      "type" : "object"
    }
  },
  "required" : [ "class", "slots" ],
  "type" : "object"
}
```

**WHICH:** checks that `:slots` would constitute a valid instance of `:class` — required-slot presence, range-conformance, custom-validator pass — WITHOUT transacting.  Returns the validation report (errors, if any) without side effect.

**WHEN:** use as a PRE-FLIGHT CHECK before `sandbar.entity.create` (especially when constructing from external input where validation feedback drives consumer-side error messages).  Also useful for `sandbar.entity.update` proposals (validate the merged slot map before committing).  When NOT to use: (a) you want to CREATE the entity once valid — `sandbar.entity.create` (which validates internally); (b) you want to validate ALL existing instances of a class — `sandbar.class.validate-all-instances` (or `sandbar.validation.start` for workflow-backed).

**HOW:** `:class` is the target class ident.  `:slots` is the slot-ident-string → value map to validate.  Returns `{:valid? <bool> :errors <error-detail-or-nil>}`.

**ORDER:** typical use — `sandbar.class.required-slots` (discover requirements) → `sandbar.entity.validate` (pre-check) → `sandbar.entity.create` (commit).

**COMBINATION:** pairs with `sandbar.entity.create` (the actual mutation; uses the same validation under the hood) and `sandbar.class.validate-all-instances` (sibling read-only verb at the class population level).

<a id="ground-1"></a>

## ground

### `sandbar.ground`

Compositional grounding workflow — tag lookup + meta-vocab + suggested next-step. Catalog hint: `[safe]`. Wire name: `sandbar_ground`.

**Arguments** (`*` = required):

- `concept`\* (string) — Concept-string to ground

**Complete input schema:**

```json
{
  "properties" : {
    "concept" : {
      "description" : "Concept-string to ground",
      "type" : "string"
    }
  },
  "required" : [ "concept" ],
  "type" : "object"
}
```

**WHICH:** load-bearing entry point for grounding-before-action.  Composes tag-vocabulary examination (step 1) + meta-vocabulary discovery (step 2; classes / predicates aligned with concept) + suggested-next-step routing (step 3).

**WHEN:** use BEFORE introspection / planning / authoring / research to anchor the concept in the substrate's vocabulary.  The 5th retrieval axis (formal-semantic vocabulary) per doc/concepts/mcp-protocol.md  Composes with the other four retrieval axes (search / aggregation / orientation / navigation).  When NOT to use: the concept is already grounded (e.g., you have a concrete tag / class / predicate ident); skip to the specific verb.

**HOW:** `:concept` is the concept-string to ground.  Returns:
  `:step-1-tag-lookup` — the sandbar.tag.lookup result: exact label matches over every value carrier first, then conceptual matches, with `:match-reason` per candidate and the `:population` searched
  `:step-2-meta-vocab` — classes + predicates whose name aligns with the concept
  `:step-3-suggested-next` — CALLABLE suggestions, each `{:verb :args :why}` naming a catalog verb with arguments that can be passed as written: `sandbar.search.bm25f` over `:mm/Memory` for the concept in content; from the best match, `sandbar.navigate.inbound-edges` with `[":mm.memory/tags" ":mm.memory/themes"]` for the records it classifies and `sandbar.tag.lookup` for its meaning view

**ORDER:** typically the FIRST call when an LLM consumer encounters a new concept in user input.  A step-1 miss states its population and is not proof the concept is absent — content search decides before a tag is authored; a search or read-barrier failure surfaces as an error, never as an empty vocabulary.

**COMBINATION:** anchor for tag.* operations; composes with `sandbar.search.bm25f` (content) and `sandbar.navigate.inbound-edges` (membership).

<a id="namespace-1"></a>

## namespace

### `sandbar.namespace.policy`

Look up the per-namespace policy commitment statement (ARK ??-inflection). Catalog hint: `[safe]`. Wire name: `sandbar_namespace_policy`.

**Arguments** (`*` = required):

- `namespace`\* (string) — Namespace name (e.g., 'decisions' / 'libraries.clojure')

**Complete input schema:**

```json
{
  "properties" : {
    "namespace" : {
      "description" : "Namespace name (e.g., 'decisions' / 'libraries.clojure')",
      "type" : "string"
    }
  },
  "required" : [ "namespace" ],
  "type" : "object"
}
```

**WHICH:** returns the :mm.namespace/CommitmentStatement entity declaring identity-stability + content-stability + service-stability covenants + authority-UUID + first-issued instant for the named namespace.

**WHEN:** use as the ARK `??`-inflection pattern — ask 'what's the policy under this namespace?' before authoring an entity into it. Useful for federation-aware consumers to discover persistence guarantees per-namespace.  When NOT to use: (a) you want to ASSERT a new CommitmentStatement — use sandbar.entity.create with class :mm.namespace/CommitmentStatement; (b) you want to look up the namespace's UUID (computed deterministically from the deployment's authority-UUID + namespace-name; not a stored slot).

**HOW:** `:namespace` is the namespace-name string (e.g., 'decisions' / 'libraries.clojure' / 'observations').  Returns `{:namespace :commitment-statement :commitment-statement-entity-ident :ark-question-inflection-form}`.  Returns `:commitment-statement nil` + a :note if no CommitmentStatement exists for the namespace.

**ORDER:** leaf-call.  Authoring CommitmentStatements: sandbar.entity.create with :mm.namespace/CommitmentStatement class.

**COMBINATION:** pairs with sandbar.resolve (the resolution path for federation-shaped references).

<a id="navigate-4"></a>

## navigate

### `sandbar.navigate.inbound-edges`

Typed-edges pointing AT an entity (who references it). Catalog hint: `[safe]`. Wire name: `sandbar_navigate_inbound-edges`.

**Arguments** (`*` = required):

- `entity`\* (string) — Anchor entity ident or eid (an identless entity anchors by eid)
- `limit` (integer) — Max edges (default 0 = no cap)
- `predicate` (string or array) — Single predicate ident OR JSON array of idents.  Membership: pass `:mm.memory/tags` and `:mm.memory/themes` fully qualified.  Bare forms resolve against `source-type` when given, else schema-wide by local name.
- `projection` (string) — Per-edge source projection: 'metadata-only' (default; lightweight) or 'full' (complete source entity-map)
- `source-type` (string) — Class ident restricting source-instance-of

**Complete input schema:**

```json
{
  "properties" : {
    "entity" : {
      "description" : "Anchor entity ident or eid (an identless entity anchors by eid)",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max edges (default 0 = no cap)",
      "type" : "integer"
    },
    "predicate" : {
      "description" : "Single predicate ident OR JSON array of idents.  Membership: pass `:mm.memory/tags` and `:mm.memory/themes` fully qualified.  Bare forms resolve against `source-type` when given, else schema-wide by local name.",
      "oneOf" : [ {
        "type" : "string"
      }, {
        "items" : {
          "type" : "string"
        },
        "type" : "array"
      } ]
    },
    "projection" : {
      "description" : "Per-edge source projection: 'metadata-only' (default; lightweight) or 'full' (complete source entity-map)",
      "type" : "string"
    },
    "source-type" : {
      "description" : "Class ident restricting source-instance-of",
      "type" : "string"
    }
  },
  "required" : [ "entity" ],
  "type" : "object"
}
```

**WHICH:** returns typed-edges pointing at `:entity` — who references this entity, via which predicate, from which source.  Foundational inbound traversal primitive (dual of `sandbar.navigate.outbound-edges`).

**WHEN:** use for backlink discovery — 'which decisions cite this ADR?'.  When NOT to use: (a) sources-only without predicate label — use Datalog directly; (b) bounded-depth backlink walk — use `sandbar.navigate.walk` with `:inbound` flag; (c) Kleene closure — use `sandbar.navigate.path-via` with `:INV`.

**HOW:** `:entity` is the target entity (ident or eid; an identless entity — a bare tag value carrier — is a valid anchor by eid).  Optional `:predicate` is a single ident string OR an array of ident strings restricting the edge predicates.  Pass membership predicates FULLY QUALIFIED: `:mm.memory/tags` and `:mm.memory/themes` (one call covers both).  A BARE form (no namespace) resolves against `:source-type`'s class when given, else against EVERY ref-typed property in the schema with that local name — `:tags` covers memories, rules, actors and contexts at once, each edge reporting the qualified predicate it was found through; a name no ref property carries is refused.  Optional `:source-type` is a class-ident-string restricting sources to instances-of.  Optional `:limit` caps returned edges.  Optional `:projection` controls per-edge source shape — `:metadata-only` (DEFAULT) returns just `:db/id`/`:db/ident`/`:dt/type` per source; `:full` returns the complete source entity-map.

**ORDER:** leaf-call shape.  For a vocabulary journey: `sandbar.tag.lookup` (the identity), this verb from its eid with both membership predicates (the records), `sandbar.entity.find` on a selected record.

**COMBINATION:** pairs with `sandbar.navigate.outbound-edges` (the dual).  Composes with `sandbar.orient.library-card` (`:inverse` axes use the inbound shape).

Result: `{:edges [{:predicate <pred-ident> :source <entity-map>} ...] :total <edges before the limit> :distinct-total <distinct sources before the limit> :returned <int> :limit <int> :truncated? <bool>}` — clients deduplicate sources by `:db/id` without losing which relationship found them; edge totals and distinct-record totals stay distinguishable.

### `sandbar.navigate.outbound-edges`

Typed-edges originating FROM an entity. Catalog hint: `[safe]`. Wire name: `sandbar_navigate_outbound-edges`.

**Arguments** (`*` = required):

- `entity`\* (string) — Anchor entity ident or eid (an identless entity anchors by eid)
- `limit` (integer) — Max edges (default 0 = no cap)
- `predicate` (string or array) — Single predicate ident OR JSON array of idents.  Bare forms (`:cites`) resolve to slot-idents (`:mm.memory/cites`) on the entity's class, or schema-wide when the anchor has no class.
- `projection` (string) — Per-edge target projection: 'metadata-only' (default; lightweight) or 'full' (complete target entity-map)
- `target-type` (string) — Class ident restricting target-instance-of

**Complete input schema:**

```json
{
  "properties" : {
    "entity" : {
      "description" : "Anchor entity ident or eid (an identless entity anchors by eid)",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max edges (default 0 = no cap)",
      "type" : "integer"
    },
    "predicate" : {
      "description" : "Single predicate ident OR JSON array of idents.  Bare forms (`:cites`) resolve to slot-idents (`:mm.memory/cites`) on the entity's class, or schema-wide when the anchor has no class.",
      "oneOf" : [ {
        "type" : "string"
      }, {
        "items" : {
          "type" : "string"
        },
        "type" : "array"
      } ]
    },
    "projection" : {
      "description" : "Per-edge target projection: 'metadata-only' (default; lightweight) or 'full' (complete target entity-map)",
      "type" : "string"
    },
    "target-type" : {
      "description" : "Class ident restricting target-instance-of",
      "type" : "string"
    }
  },
  "required" : [ "entity" ],
  "type" : "object"
}
```

**WHICH:** returns typed-edges originating from `:entity` — what does this entity reference, via which predicate, to which target.  Foundational outbound traversal primitive.

**WHEN:** use for one-hop forward navigation when you need the predicate-and-target shape (not just the targets).  When NOT to use: (a) targets-only (no predicate label) — use a Datalog query directly; (b) recursive / Kleene-closure traversal — use `sandbar.navigate.path-via`; (c) bounded-depth BFS — use `sandbar.navigate.walk`.

**HOW:** `:entity` is the seed entity (ident or eid; an identless entity is a valid anchor by eid).  Optional `:predicate` is a single ident string OR an array of ident strings restricting the edge predicates; BARE forms (no namespace) like `:cites` resolve to the slot-ident `:mm.memory/cites` on the entity's class (one match used; none or several refused with a hint), or schema-wide when the anchor has no class.  Optional `:target-type` is a class-ident-string restricting targets to instances-of.  Optional `:limit` caps returned edges (default 0 = no cap).  Optional `:projection` controls per-edge target shape — `:metadata-only` (DEFAULT) returns just `:db/id`/`:db/ident`/`:dt/type` per target (10-300x smaller payload than `:full`); `:full` returns the complete target entity-map.

**ORDER:** leaf-call shape.  Discover candidate predicates first via `sandbar.class.slots` on the entity's class if uncertain.

**COMBINATION:** pairs with `sandbar.navigate.inbound-edges` (the dual; who references this entity).  Composes with `sandbar.orient.library-card` (one-call multi-axis breakdown).  Pre-step for `sandbar.navigate.path-via` (discover predicate vocab before authoring path expressions).

Result: `{:edges [{:predicate <pred-ident> :target <entity-map>} ...] :total <edges before the limit> :distinct-total <distinct targets before the limit> :returned <int> :limit <int> :truncated? <bool>}` — each edge keeps its `:predicate` as the role; a target reached through two predicates is two edges and one distinct record.

### `sandbar.navigate.path-via`

Walk a Wilbur-lineage path-grammar expression from a seed entity. Catalog hint: `[safe]`. Wire name: `sandbar_navigate_path-via`.

**Arguments** (`*` = required):

- `from`\* (string) — Seed entity ident (e.g. ':dt/Property') or eid
- `include` (array) — Projection options; supports 'paths' (deferred surfacing)
- `limit` (integer) — Max returned entities (default 0 = no cap)
- `projection` (string) — Per-reachable-entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  When :include includes 'paths', applies to the :entity field of each {:entity :path} entry.
- `via`\* (string) — EDN-string path expression (e.g. "[:REP* [:OR :cites :evidences]]")

**Complete input schema:**

```json
{
  "properties" : {
    "from" : {
      "description" : "Seed entity ident (e.g. ':dt/Property') or eid",
      "type" : "string"
    },
    "include" : {
      "description" : "Projection options; supports 'paths' (deferred surfacing)",
      "items" : {
        "type" : "string"
      },
      "type" : "array"
    },
    "limit" : {
      "description" : "Max returned entities (default 0 = no cap)",
      "type" : "integer"
    },
    "projection" : {
      "description" : "Per-reachable-entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  When :include includes 'paths', applies to the :entity field of each {:entity :path} entry.",
      "type" : "string"
    },
    "via" : {
      "description" : "EDN-string path expression (e.g. \"[:REP* [:OR :cites :evidences]]\")",
      "type" : "string"
    }
  },
  "required" : [ "from", "via" ],
  "type" : "object"
}
```

**WHICH:** walks a path-grammar expression (`:via`) starting from a seed entity (`:from`); returns the set of entities reachable under the binary-relation algebra denoted by the expression.  Path-grammar is Kleene-algebra-over-binary-relations — same lineage as SPARQL 1.1 property paths and ISO GQL 39075:2024.

**WHEN:** use when navigation needs more expressiveness than direct edges (`sandbar.navigate.inbound` / `.outbound`) or bounded BFS — specifically when you need Kleene closure (`:REP*` / `:REP+`), alternation (`:OR`), inverse traversal at depth, or shape-specific restrictions.  Real-world property-path queries are <0.1% of total per Bonifati 2017 — but when you need them, only path-grammar fits.  When NOT to use: (a) single hop — use `sandbar.navigate.outbound` / `.inbound` (simpler + faster); (b) bounded N-hop reachability — use `sandbar.navigate.walk` (BFS with hop-cap is more efficient than `:REP*` for known-depth walks); (c) you need the seed itself in results — `:REP*` (or `:OPT`) includes the seed via the zero-application branch.

**HOW:** `:from` is the seed entity ident or eid.  `:via` is an EDN-STRING path expression using one of 13 currently-executable operators:
  * Canonical-8 (Tier-1): `:SEQ` (n-ary sequence) / `:OR` (n-ary union) / `:REP+` (transitive closure 1+) / `:REP*` (reflexive-transitive 0+) / `:INV` (inverse — swap subject/object roles) / `:SELF` (identity) / `:RESTRICT [pred value]` (specific-node filter) / `:ANY` (wildcard predicate)
  * Tier-2: `:NOT` (atomic-predicate property-set negation) / `:OPT` (zero-or-one; desugars to `(:OR p :SELF)`) / `:REP p min max` (bounded repetition) / `:FILTER p substring` (URI-substring filter on `:db/ident`) / `:TEST p fn-name` (functional predicate via registered fn)
Casing: UPPERCASE combinators / lowercase predicates.  Examples:
  * `"[:REP+ :dt/subclass-of]"` — transitive ancestor walk
  * `"[:SEQ [:REP* [:OR :cites :evidences]] [:RESTRICT [:dt/type :mm.memory/decision]]]"` — closure-then-filter
  * `"[:INV [:REP+ :cites]]"` — entities that transitively cite this seed
Tier-3 operators (`:LANG`, `:VALUE`, `:DAEMON`, `:NOREWRITE`, `:MEMBERS`, `:PREDICATE-OF-*`) are vocabulary-registered but compilation deferred; passing them raises descriptive ex-info.  `:include ["paths"]` is accepted but path-data is not yet populated (recursive-path reconstruction lands at follow-on); result carries `:path-data-deferred true` flag when requested.

**ORDER:** no strict prerequisites.  To explore the typed-edge vocabulary available at the seed first, call `sandbar.navigate.outbound` to see what predicates emerge from the entity; to discover class-hierarchy predicates, use `sandbar.class.slots` on a class.

**COMBINATION:** composes with `sandbar.navigate.inbound`/`.outbound` (use them to discover predicate vocab before authoring path expressions) and `sandbar.navigate.walk` (use walk first if depth-bounded reachability is enough; reach for path-via only when Kleene closure adds value).  Cross-axis composition with `sandbar.search.bm25f` (`:from` + `:via` opts to restrict candidate set) and its `rank-by` option (rank within a graph-walk neighborhood) is available in search.bm25f.

Result: `{:reachable [<entity-map>...] :total <int> :returned <int>}`.

### `sandbar.navigate.siblings-of`

Same-directory peers of an entity via a filesystem-style path slot. Catalog hint: `[safe]`. Wire name: `sandbar_navigate_siblings-of`.

**Arguments** (`*` = required):

- `entity`\* (string) — Anchor entity ident (e.g. ':memory.notes/example') or numeric eid as a string
- `limit` (integer) — Max returned siblings (default 0 = no cap)
- `path-slot`\* (string) — Slot ident carrying the filesystem-style path (e.g. ':mm.memory/rel-path')

**Complete input schema:**

```json
{
  "properties" : {
    "entity" : {
      "description" : "Anchor entity ident (e.g. ':memory.notes/example') or numeric eid as a string",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max returned siblings (default 0 = no cap)",
      "type" : "integer"
    },
    "path-slot" : {
      "description" : "Slot ident carrying the filesystem-style path (e.g. ':mm.memory/rel-path')",
      "type" : "string"
    }
  },
  "required" : [ "entity", "path-slot" ],
  "type" : "object"
}
```

**WHICH:** returns entities whose `:path-slot` value shares the same directory prefix as `:entity`'s `:path-slot` value (filesystem-style — 'decisions/foo.md' is a sibling of 'decisions/bar.md' but NOT of 'decisions/sub/baz.md' or 'patterns/foo.md').

**WHEN:** use to enumerate documents stored under the same logical 'directory' as a given anchor — e.g., listing all decision memorials in `decisions/`, all guides in `doc/guides/`.  When NOT to use: (a) entities lacking a filesystem-style path slot — reach for `sandbar.navigate.inbound` with `:next-sibling` / `:previous-sibling` predicate filter for typed-edge SIOC-pairwise sibling chains (mm/Section pattern); (b) recursive descent through sub-directories — reach for `sandbar.navigate.path-via` with `:FILTER` over a directory prefix.

**HOW:** `:entity` is the anchor entity (ident or eid); `:path-slot` is the attribute ident carrying the filesystem-style path (e.g., `:mm.memory/rel-path`).  The substrate is CLASS-AGNOSTIC — the slot is caller-supplied; no hardcoded knowledge of memory-model vs other domain classes.  Optional `:limit` caps returned siblings (default 0 = no cap; cap is applied after substrate lookup so `:total` reflects the full set).

**ORDER:** prerequisite — `:entity` must have `:path-slot` populated.  If uncertain, verify first via `sandbar.entity.find` (reads the entity by ident).  No other ordering dependencies; this verb is leaf-call shape.

**COMBINATION:** pairs naturally with `sandbar.navigate.inbound` / `.outbound` (typed-edge neighbors) for complete sibling-discovery (filesystem-style + typed-edge).  For ranked sibling subsets, compose downstream with `sandbar.aggregate.rank-by` using the sibling-eid set as the candidate population.  For a graph-defined candidate set, sandbar.search.bm25f accepts from plus via; directory membership itself is a separate condition.

Result: `{:siblings [<entity-map>...] :total <int> :returned <int>}`.  Each entity-map carries `:db/id`, `:db/ident` (if interned), and namespaced-keyword slots.

<a id="orient-3"></a>

## orient

### `sandbar.orient.library-card`

Multi-axis typed-edge neighborhood view of an entity. Catalog hint: `[safe]`. Wire name: `sandbar_orient_library-card`.

**Arguments** (`*` = required):

- `axes`\* (array) — Vec of axis-spec objects; one labeled subset per axis
- `entity`\* (string) — Anchor entity ident (e.g. ':memory.decisions/foo') or eid
- `projection` (string) — Entity + edge target/source shape: 'metadata-only' (default; lightweight) or 'full' (complete entity-maps)

**Complete input schema:**

```json
{
  "properties" : {
    "axes" : {
      "description" : "Vec of axis-spec objects; one labeled subset per axis",
      "items" : {
        "description" : "Axis-spec: {name, direction:'forward'|'inverse', predicates?, target-type?, source-type?, limit?}.  predicates accept BOTH bare (':cites') and slot-ident (':mm.memory/cites') forms — bare resolves per-axis to the entity's class.",
        "type" : "object"
      },
      "type" : "array"
    },
    "entity" : {
      "description" : "Anchor entity ident (e.g. ':memory.decisions/foo') or eid",
      "type" : "string"
    },
    "projection" : {
      "description" : "Entity + edge target/source shape: 'metadata-only' (default; lightweight) or 'full' (complete entity-maps)",
      "type" : "string"
    }
  },
  "required" : [ "entity", "axes" ],
  "type" : "object"
}
```

**WHICH:** returns a labeled, multi-axis view of an entity's typed-edge neighborhood.  Each `:axis` is a labeled subset of inbound or outbound edges optionally filtered by predicate-set and target/source-type.  Substrate-correct shape of the corpus's 'library-card' pattern — Sandbar ships the composition primitive; the consumer supplies the semantics (which axes mean what).

**WHEN:** use when an AI client / consumer needs a structured overview of an entity — 'show me everything connected to this seed, broken down by relationship type'.  Especially useful for AI-orientation flows (load an unfamiliar entity; see its typed-edge surface across 10 axes at once).  When NOT to use: (a) single-predicate edge enumeration — use `sandbar.navigate.outbound` or `.inbound` directly (one call, simpler); (b) reachability across multiple hops — use `sandbar.navigate.walk` or `.path-via` instead; (c) fulltext-relevance ranking of the neighborhood — combine search with this verb's output downstream.

**HOW:** `:entity` is the anchor entity (ident or eid).  `:axes` is a JSON array of axis-spec objects; each:
  - `name` (REQUIRED) — string or keyword label for the axis in the result (e.g. "cited-by-decisions")
  - `direction` (REQUIRED) — "forward" (outbound from entity) or "inverse" (inbound to entity)
  - `predicates` (optional) — array of predicate-ident strings to restrict to.  Bare forms (`:cites`) auto-resolve per-axis to the slot-ident (`:mm.memory/cites`) on the entity's class.  Fully-qualified forms pass through unchanged.  Unresolvable bare predicates throw ex-info with a hint suggesting the canonical slot ident.
  - `target-type` (optional, for :forward axes) — class-ident string restricting target-instance-of
  - `source-type` (optional, for :inverse axes) — class-ident string restricting source-instance-of
  - `limit` (optional) — per-axis edge cap; default 0 = no cap
The substrate is CLASS-AGNOSTIC; predicate-vocabulary + axis-labels are caller-supplied.  No hardcoded knowledge of any domain class's predicate vocabulary.

Optional `:projection` — controls entity + edge target/source shape: `:metadata-only` (DEFAULT) returns just `:db/id`/`:db/ident`/`:dt/type` for the seed entity AND every edge's target/source (10-300x smaller payload — addresses 364KB+ responses on multi-axis queries); `:full` returns complete entity-maps.

**ORDER:** prerequisite — the caller must know the predicate vocabulary applicable to the entity's class.  Discover via `sandbar.navigate.outbound` (one-shot peek at outbound edges) or `sandbar.class.slots` (declared slots on the entity's class) FIRST.  No other ordering dependencies.

**COMBINATION:** composes with `sandbar.navigate.inbound` / `.outbound` (use them to DISCOVER predicate vocab first, then author library-card axis-specs covering them).  For ranked subsets within an axis, post-rank the results via `sandbar.aggregate.rank-by` (using the axis-result eids as the candidate set).  For path-shaped neighborhoods (recursive / Kleene), use `sandbar.navigate.path-via` instead — library-card is one-hop-per-axis by design.

Result: `{:entity <entity-map> :axes {<axis-name> [{:predicate ... :target/source <entity-map>}...] ...}}`.

### `sandbar.orient.tree`

Top-level directory grouping of class instances by path-slot. Catalog hint: `[safe]`. Wire name: `sandbar_orient_tree`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident whose instances to group
- `path-slot`\* (string) — Slot ident carrying filesystem-style path
- `sample-size` (integer) — Sample entities per directory (default 0 = none)

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident whose instances to group",
      "type" : "string"
    },
    "path-slot" : {
      "description" : "Slot ident carrying filesystem-style path",
      "type" : "string"
    },
    "sample-size" : {
      "description" : "Sample entities per directory (default 0 = none)",
      "type" : "integer"
    }
  },
  "required" : [ "class", "path-slot" ],
  "type" : "object"
}
```

**WHICH:** groups instances of `:class` by their `:path-slot` value's first-level directory prefix.  Returns per-directory counts + optional sample entities.

**WHEN:** use for filesystem-style overview of a corpus subtree.  When NOT to use: (a) sibling enumeration within ONE directory — use `sandbar.navigate.siblings-of`; (b) recursive descent through sub-directories — compose multiple `tree` calls or use a path-grammar walk.

**HOW:** `:class` is the class ident.  `:path-slot` is the slot carrying the filesystem-style path.  Optional `:sample-size` includes that many sample entities per directory in the result (default 0 = counts only).

**ORDER:** leaf-call shape.

**COMBINATION:** pairs with `sandbar.navigate.siblings-of` (drill into a single directory) and `sandbar.aggregate.group-by` (more general group-by-slot).

Result: `{:dirs {<dir-name> {:count N :sample [<entity-map>...]?}} :total N}`.

### `sandbar.orient.type-tree`

Class-hierarchy subtree rooted at a class (nested rendering). Catalog hint: `[safe]`. Wire name: `sandbar_orient_type-tree`.

**Arguments** (`*` = required):

- `root` (string) — Root class ident (default ':dt/Resource')

**Complete input schema:**

```json
{
  "properties" : {
    "root" : {
      "description" : "Root class ident (default ':dt/Resource')",
      "type" : "string"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the class-hierarchy subtree rooted at `:root` (default `:dt/Resource` — the metamodel root).  Recursive walk via `dt/direct-subclasses-of`; produces a nested-map tree with `:class` + `:children` per node.

**WHEN:** use to visualize the full subclass hierarchy from a root class.  When NOT to use: (a) only direct subclasses needed — use `sandbar.class.subclasses`; (b) flat list of all subclasses — use `sandbar.class.subclasses` (returns flat).

**HOW:** optional `:root` — root class ident string (default `:dt/Resource`).  Cycles in the inheritance graph are detected + flagged with `:cycle? true` (no infinite recursion).

**ORDER:** leaf-call shape.

**COMBINATION:** pairs with `sandbar.class.describe` / `.slots` (drill into individual classes) and `sandbar.types.subclass-of` (relation query).

Result: `{:root <ident> :tree {:class <ident> :children [<subtree>...]}}`.

<a id="project-3"></a>

## project

### `sandbar.project.export`

Preview or export an enrolled project's eligible documents. Catalog hint: `[safe]`. Wire name: `sandbar_project_export`.

**Arguments** (`*` = required):

- `destination`\* (string) — Operator-configured export destination name
- `dry-run` (boolean)
- `expect-plan` (string) — Preview token required for execution
- `filter` (object) — Optional narrowing class, classes or tree-filter
- `project`\* (string) — Explicit enrolled Project entity ident
- `provenance` (boolean)
- `to`\* (string) — Fresh absolute child of the authorized staging root

**Complete input schema:**

```json
{
  "additionalProperties" : false,
  "properties" : {
    "destination" : {
      "description" : "Operator-configured export destination name",
      "type" : "string"
    },
    "dry-run" : {
      "default" : true,
      "type" : "boolean"
    },
    "expect-plan" : {
      "description" : "Preview token required for execution",
      "type" : "string"
    },
    "filter" : {
      "description" : "Optional narrowing class, classes or tree-filter",
      "type" : "object"
    },
    "project" : {
      "description" : "Explicit enrolled Project entity ident",
      "type" : "string"
    },
    "provenance" : {
      "default" : false,
      "type" : "boolean"
    },
    "to" : {
      "description" : "Fresh absolute child of the authorized staging root",
      "type" : "string"
    }
  },
  "required" : [ "project", "destination", "to" ],
  "type" : "object"
}
```

**WHICH:** guarded Markdown export of source-owned Memory documents and their owned sections. Explicit project and operator-configured destination are required. The caller's clearance, destination audience, emitted references, identity round trip and fresh staging paths are checked before any output file. Unsafe documents are held whole; originals remain unchanged. This is not a complete database backup or semantic prose declassification.

**HOW:** preview is the default (dry-run true). It writes a private audit, returns counts, basis and plan-token, and creates no staging output. Inspect that audit using the operator's private audit directory. Execute with identical arguments, dry-run false and expect-plan equal to that token. A changed basis, selection or destination requires another preview. Optional filter only narrows the selected project. The to path must be a fresh direct child beneath the named destination's staging root. No overwrite or source-root export is allowed.

RESULT: status is preview, complete or incomplete; only complete? true establishes completion. Planned holds are counted separately from write failures. A complete output contains export-manifest.edn with file hashes and an opaque private audit reference. Partial output has no valid completion manifest. Exact held identities and reasons remain in the private audit. Optional provenance requests a DB Run only when server recording is also enabled; privacy checks always apply.

**ORDER:** use the attended stopped-writer procedure through preview, execution and audit. Publication, import, git and recovery are separate operator actions.

### `sandbar.project.import`

Import reviewed Markdown source units from a filesystem hierarchy. Catalog hint: `[unsafe]`. Wire name: `sandbar_project_import`.

**Arguments** (`*` = required):

- `exclude` (array) — Rel-paths of units to walk and fingerprint but neither plan nor transact — deferred rows kept with both versions held.
- `expect-basis` (number) — Pin a persist to the :basis a dry run reported; refused if the database moved since (preview again).
- `expect-source-hashes` (object) — The dry run's :sources map ({rel-path sha256}); like expect-sources but the refusal names the files that changed, appeared or disappeared.
- `expect-sources` (string) — Pin a persist to the :sources-sha256 token a dry run reported: refused before any transaction if any walked file changed, appeared or disappeared since.
- `filter` (object) — Optional filter spec (same shape as project.export): class / classes / tree-filter; decided per source file
- `from`\* (string) — Input directory path (or a single .md file)
- `mode` (string) — 'replace' (default): a file the substrate already holds replaces its source-owned representation in one transaction; 'additive': assertions only (for ingestion from independent sources).
- `persist` (boolean) — When true, transact each parsed source unit (ONE transaction per file) into the substrate.  When false / omitted, DRY RUN: parse + report, transact nothing.  Either way the response accounts for every file walked — attempted = persisted + failed + refused + parse-failed + skipped — and names every file whose parse failed.  (Wire-format key MUST be `persist` — no `?` suffix — to comply with the MCP tool-schema property-key regex; the handler accepts legacy `persist?` too.)

**Complete input schema:**

```json
{
  "properties" : {
    "exclude" : {
      "description" : "Rel-paths of units to walk and fingerprint but neither plan nor transact — deferred rows kept with both versions held.",
      "items" : {
        "type" : "string"
      },
      "type" : "array"
    },
    "expect-basis" : {
      "description" : "Pin a persist to the :basis a dry run reported; refused if the database moved since (preview again).",
      "type" : "number"
    },
    "expect-source-hashes" : {
      "description" : "The dry run's :sources map ({rel-path sha256}); like expect-sources but the refusal names the files that changed, appeared or disappeared.",
      "type" : "object"
    },
    "expect-sources" : {
      "description" : "Pin a persist to the :sources-sha256 token a dry run reported: refused before any transaction if any walked file changed, appeared or disappeared since.",
      "type" : "string"
    },
    "filter" : {
      "description" : "Optional filter spec (same shape as project.export): class / classes / tree-filter; decided per source file",
      "type" : "object"
    },
    "from" : {
      "description" : "Input directory path (or a single .md file)",
      "type" : "string"
    },
    "mode" : {
      "description" : "'replace' (default): a file the substrate already holds replaces its source-owned representation in one transaction; 'additive': assertions only (for ingestion from independent sources).",
      "type" : "string"
    },
    "persist" : {
      "description" : "When true, transact each parsed source unit (ONE transaction per file) into the substrate.  When false / omitted, DRY RUN: parse + report, transact nothing.  Either way the response accounts for every file walked — attempted = persisted + failed + refused + parse-failed + skipped — and names every file whose parse failed.  (Wire-format key MUST be `persist` — no `?` suffix — to comply with the MCP tool-schema property-key regex; the handler accepts legacy `persist?` too.)",
      "type" : "boolean"
    }
  },
  "required" : [ "from" ],
  "type" : "object"
}
```

**WHICH:** walks the `:from` directory, parses each `.md` file via the markdown codec into ONE SOURCE UNIT per file (a memory with its sections, or a Tag / other native-codec document), and — with `:persist` — transacts each parsed unit on its own, reporting every file's fate.  Document import complements sandbar.project.export. Its preservation contract depends on the document class and codec; it is not a full database restore.

**WHEN:** use for attended maintenance import or ingestion of reviewed documents. Stop every writer before editing canonical input files and keep writers stopped through preview, import and audit. Import into the existing store to preserve existing identities. Exclude ambiguous paths and retain both versions until their ownership is resolved.  When NOT to use: (a) you want to create ONE entity programmatically — `sandbar.entity.create` (STRICT on front matter: an unknown key is refused; this bulk verb is LENIENT: unknown keys ride in the front-matter carrier and are REPORTED per file); (b) you want to write TO the filesystem — `sandbar.project.export`.

**HOW:** `:from` is the input directory path (REQUIRED; a single `.md` file is accepted too).  `:filter` (optional) restricts which units ingest — `:class`, `:classes`, `:tree-filter` (same shape as `sandbar.project.export`); the decision is made per source file.  `:persist` (optional, default false) — WITHOUT it the verb is a DRY RUN: it parses, PLANS every unit and reports, but transacts nothing.  REPLACEMENT: a file the substrate already holds is REPLACED — the file's declared slots win, an omitted source-owned slot is retracted, a cardinality-many slot is set-replaced, sections the file no longer carries are retracted with their links, the carrier is replaced or retracted — in ONE transaction per file, while the host's identity, the refs into it, and the facts the substrate or another owner maintains (created, last-touched, created-by, owning-project, visibility, the id) are kept; `:mode` `additive` opts into assertion-only additive behaviour.  A unit is REFUSED as a conflict, never guessed, when the file's class differs from the stored one, its `id:` differs from the stored `mm/id`, or a section it drops is referenced by a record outside the document (`:conflicts`, with the reason).  With a nonempty operator `:project-roots` map, a mapped source tree requires its Project as effective owner; an explicit owner must resolve, existing documents cannot change physical trees, and a mapped Project key cannot be renamed, retracted or taken over. These checks apply in preview and persist. An omitted owner retains the stored owner for an existing typed document. A genuinely new document under a mapped root, including an untyped forward placeholder, receives that root's Project when the source omits owning-project; an explicit owner is never overwritten. Existing unowned documents remain held for the operator rather than being adopted. Global and staging sources retain the legacy path subject to these guards; an empty map adds no destination guard. The report includes `:source-root`. Import still permits its existing within-tree rel-path changes; the operator must reconcile old files during maintenance. Ordinary entity.update has a stricter physical-target refusal.

NEW DOCUMENT IDENTITY: an insert plan for a new Memory document with an ident supplies an absent :mm/id UUID only when neither the source nor an existing forward placeholder has one. Existing identities are retained; a conflicting source and placeholder identity refuses the unit. An existing typed document without a UUID is not silently assigned one. Preview :units and planned persist result rows carry :identity-minted?; :document-id and :owning-project are included when present on the plan's root assertion. These fields describe the plan's assertions, not a full read-back of retained store metadata. Planning a UUID in preview does not transact it.

FILE COMPLETION: this MCP verb does not stamp source files. The maintenance CLI's --stamp option, with --receipts when persisting, can add only the missing id line for a successfully imported new document at its canonical destination. It checks the import receipt, unchanged source, current stored identity and competing path claimants; changed, foreign, ambiguous or noncanonical files remain held. Staging copies at other destinations cannot be stamped as canonical files. Keep every writer stopped through stamping and audit. --stamp is not an MCP argument and does not relax ordinary file-write or delete ownership guards.

`:expect-basis` (optional) pins a persist to the basis a preview reported: if the database moved, the call is refused and the preview must be repeated; every unit reports its `:source-sha256`.  THE SOURCE PIN: `:expect-sources` (the preview's `:sources-sha256` token) or `:expect-source-hashes` (its `:sources` map) pins the persist to the preview's input set — a file that changed, appeared or disappeared since refuses the WHOLE call before anything is transacted, the map form naming the files.  THE BASIS GUARD: each plan is applied under `[:assert-basis t]` for the database value it was computed against, so a change that landed between planning and apply — a citation added to a section the plan retracts — aborts that unit's commit and is reported as a `:basis-moved-during-apply` conflict, never replanned.  An attended run advances its expected basis only through its OWN commits: a write that is not its own, landing between two units (or between the preview's pin and the first unit), refuses the remaining units as `:basis-moved-before-plan` conflicts, with the applied units and the refused remainder both reported and `:final-basis` the basis the run's last commit left.  A file that cannot be read is its own `:parse-failed` unit.  `:exclude` (an array of rel-paths) names units that are walked and fingerprinted but neither planned nor transacted — deferred rows kept with both versions held; they count as skipped and are listed under `:excluded`.  ACCOUNTING — every response carries `:attempted` (files walked), `:imported` (entities parsed), `:parse-failed-count` + `:parse-failed [{:source :error}]` (files whose parse threw — named, never silently dropped), `:unknown-keys-count` + `:unknown-keys [{:source :dt/type :ident :unknown-keys [...]}]` (files whose front matter carried keys the class does not declare), and `:skipped-count` (files the filter excluded, or that could not match the tree filter); a dry run adds `:units` (each file's planned `:mode` — `insert`, `replace` or `additive` — with the retractions a replace would make and any `:conflicts`) and `:basis`.  With `:persist` the response adds `:persisted-count` and `:persisted` (each unit's mode and retraction counts), `:failed-count` + `:failed [...]` (a unit whose transaction failed — a schema or transactor error, with `:source`, `:ident`, `:error`), `:refused-count` + `:refused [...]` (a unit the import firewall refused — a governed edge the floor forbade; firewall refusal rows include `:error` and `:violations`, a vector selecting the present `:type`, `:severity`, `:reason`, `:slot`, `:target-ref` and `:message` fields from existing `:firewall-violation` verdicts, without fetching target bodies), `:conflict-count` + `:conflicts [...]` (a unit refused for ambiguity under replacement), `:groups` (the transaction units), and `:totals` + `:reconciled?` — attempted = persisted + failed + refused + conflicts + parse-failed + skipped.  ONE transaction per file: a failing file never rolls back its neighbours.  (Wire-format key MUST be `persist` — no `?` suffix — to comply with the MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`; the handler accepts legacy `persist?` too.)

**ORDER:** follow the quiescent maintenance procedure in doc/operations.md. Preview without persist, retain the exact source manifest and report, then persist with the matching basis and source pins. Inspect every refusal, failure and conflict; earlier successful files remain committed if a later file fails. entity.validate is advisory and does not replace preview or shape auditing.

**COMBINATION:** sandbar.project.export supplies document representations; sandbar.audit.fs-substrate-drift diagnoses differences after an import. Verify content, identity and references explicitly. Registered codecs are visible via sandbar.codec.list; this import path walks Markdown files.

### `sandbar.project.recovery-check`

Compare a completed export's trusted origin with the same existing database. Catalog hint: `[safe]`. Wire name: `sandbar_project_recovery-check`.

**Arguments** (`*` = required):

- `destination`\* (string) — Operator-configured export destination name
- `expect-manifest` (string) — Optional SHA-256 pin of export-manifest.edn from trusted custody
- `from`\* (string) — Existing absolute direct child of the destination's staging root holding a completed export
- `project`\* (string) — Explicit enrolled Project entity ident

**Complete input schema:**

```json
{
  "additionalProperties" : false,
  "properties" : {
    "destination" : {
      "description" : "Operator-configured export destination name",
      "type" : "string"
    },
    "expect-manifest" : {
      "description" : "Optional SHA-256 pin of export-manifest.edn from trusted custody",
      "type" : "string"
    },
    "from" : {
      "description" : "Existing absolute direct child of the destination's staging root holding a completed export",
      "type" : "string"
    },
    "project" : {
      "description" : "Explicit enrolled Project entity ident",
      "type" : "string"
    }
  },
  "required" : [ "project", "destination", "from" ],
  "type" : "object"
}
```

**WHICH:** read-only origin comparison of one completed guarded export with the current database. It verifies the tree's exact bytes and population against export-manifest.edn (the same check as `sandbar verify-export`), locates the export's private ready audit only under the named destination's configured audit root by the manifest's opaque reference, requires that receipt to bind the exact marker hash, the recorded database identity and basis, the destination, audience, project and file rows, and then compares the recorded identity and basis with one live snapshot. Nothing is imported, written, restored, committed or approved.

**WHEN:** use before reusing a verified staging child against the existing store, to learn whether these files have a trusted origin in this database and whether the database moved since the export. When NOT to use: (a) to decide whether an import is safe — the result carries import-approved? false; use `sandbar.project.import` preview with its own pins; (b) for file integrity alone — `sandbar verify-export`; (c) for a tree from another database or a restored one — bases are not compared across identities.

**HOW:** `:project` is the enrolled Project ident, `:destination` the operator-configured export destination name and `:from` an existing direct child of that destination's staging root; optional `:expect-manifest` pins the manifest bytes to a SHA-256 from trusted custody. The caller must be an authenticated principal with full clearance (a read-only role is acceptable); the check refuses before any file is read otherwise. RESULT: `{:status :checked :scope :snapshot-comparison :state <state> :file-count N :audience public|private :held-count N :manifest-sha256 <hex> :import-approved? false}` with an optional keyword `:reason`. States: `:same-store-same-basis` (the recorded identity matches and the basis is unchanged — still preview the import); `:store-advanced` (same database, later basis: review the intervening state, which may be unrelated work or the export's own provenance record — no claim that these files changed); `:store-behind-export` (same identity, earlier basis: hold for review); `:different-store` (identities differ; bases are not ordered); `:origin-unverified` (receipt absent, from before origin binding, malformed or disagreeing; integrity was still verified). Coverage is the listed files at this audience: a public export covers its selected public documents, a filtered or held export covers less than its project. Integrity, authorization, configuration and read failures are isError refusals with a keyword reason, never a checked state. No database identity, private path, held identity or exception text appears in the result.

**ORDER:** after `sandbar verify-export` (or with the same pin) and before `sandbar.project.import` preview, inside the attended stopped-writer procedure.

**COMBINATION:** `sandbar.project.export` writes the receipt this verb consumes; `sandbar.project.import` preview and persist remain the place where intended changes are assessed and pinned.

<a id="property-3"></a>

## property

### `sandbar.property.cardinality`

Cardinality of a property (`:db.cardinality/one` or `/many`). Catalog hint: `[safe]`. Wire name: `sandbar_property_cardinality`.

**Arguments** (`*` = required):

- `property`\* (string) — Property ident

**Complete input schema:**

```json
{
  "properties" : {
    "property" : {
      "description" : "Property ident",
      "type" : "string"
    }
  },
  "required" : [ "property" ],
  "type" : "object"
}
```

**WHICH:** returns the cardinality of a property — either `:db.cardinality/one` (scalar; at most one value per entity) or `:db.cardinality/many` (set-valued; multiple values per entity).

**WHEN:** use when constructing slot maps — `:cardinality/many` slots accept vec / set; `:cardinality/one` slots accept the scalar value directly.  Critical for `sandbar.entity.create` slot construction and for authoring `:where` Datalog clauses that traverse multi-cardinality attributes.  When NOT to use: (a) you want the value type — `sandbar.property.range`; (b) you want the domain — `sandbar.property.domain`.

**HOW:** `:property` is the attribute ident.  Returns `{:property <ident-string> :cardinality <ident-string>}`; every installed attribute has a cardinality, so a `nil` here names a declared property that is not yet installed as a Datomic attribute.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a property; an unknown property used to answer `nil`.

**ORDER:** no prerequisites.

**COMBINATION:** triad with `sandbar.property.domain` + `.range`.  Many-cardinality ref properties (e.g., `:mm.memory/tags`) are natural targets for navigation verbs (`sandbar.navigate.outbound` with `:predicates [:mm.memory/tags]`).

### `sandbar.property.domain`

Domain class of a property (`:dt/domain`). Catalog hint: `[safe]`. Wire name: `sandbar_property_domain`.

**Arguments** (`*` = required):

- `property`\* (string) — Property ident

**Complete input schema:**

```json
{
  "properties" : {
    "property" : {
      "description" : "Property ident",
      "type" : "string"
    }
  },
  "required" : [ "property" ],
  "type" : "object"
}
```

**WHICH:** returns the declared domain class of a property — the class whose instances may carry this attribute (per RDFS / KL-ONE semantics).  For SPARQL-fluent readers: the `rdfs:domain` analogue.

**WHEN:** use to discover which class a property applies to — useful when authoring `:where` clauses (you need to know which entity type carries the slot) or when validating that a slot map's keys are appropriate for a target class.  When NOT to use: (a) you want the VALUE type of the property — `sandbar.property.range`; (b) you want every slot on a class — `sandbar.class.slots`.

**HOW:** `:property` is the attribute ident.  Returns `{:property <ident-string> :domain <class-ident-string-or-nil>}`.  `nil` means the property exists and declares no domain (for example a property of the bundled `twit` sample schema, whose properties declare none); it never means the property is unknown.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a property (a class, a memorial); an unknown property used to answer `nil`, indistinguishable from an undeclared domain.

**ORDER:** no prerequisites; foundational property-introspection call.

**COMBINATION:** triad with `sandbar.property.range` + `sandbar.property.cardinality` — all three together describe the property's shape.  Domain + range together let you reason about a typed-edge: 'edges of predicate :p go from class :D to class :R'.

### `sandbar.property.range`

Value-type range of a property (`:dt/range` / `:db/valueType`). Catalog hint: `[safe]`. Wire name: `sandbar_property_range`.

**Arguments** (`*` = required):

- `property`\* (string) — Property ident

**Complete input schema:**

```json
{
  "properties" : {
    "property" : {
      "description" : "Property ident",
      "type" : "string"
    }
  },
  "required" : [ "property" ],
  "type" : "object"
}
```

**WHICH:** returns the value-type range of a property — what kind of value the property holds.  For `:db.type/ref` properties, the range is a class ident (the target's class).  For primitive properties, the range is a `:db.type/*` keyword (`:db.type/string`, `:db.type/long`, etc.).

**WHEN:** use when constructing slot maps for `sandbar.entity.create` (you need to know what type each slot expects) or when authoring path-grammar expressions (typed-edge predicates have `:db.type/ref` range; primitive-valued slots don't).  When NOT to use: (a) you want which class CARRIES the property — `sandbar.property.domain`; (b) you want every value type registered — `sandbar.schema.datatypes`.

**HOW:** `:property` is the attribute ident.  Returns `{:property <ident-string> :range <class-or-datatype-ident-string>}`; `nil` means the property exists and declares no range.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a property; an unknown property used to answer `nil`.

**ORDER:** no prerequisites.  Critical pre-step for `sandbar.entity.create` slot construction.

**COMBINATION:** triad with `sandbar.property.domain` + `.cardinality`.  For ref-typed properties, the range class can be inspected via `sandbar.class.describe`.  Identifies which properties are typed-edges (range is a class) vs primitive-valued (range is `:db.type/*`).

<a id="reactive-1"></a>

## reactive

### `sandbar.reactive.health`

Reactive-projection pipeline health snapshot (queue depth, throughput, error counts). Catalog hint: `[safe]`. Wire name: `sandbar_reactive_health`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns a snapshot of the reactive-projection pipeline's health metrics — queue depth, dirty-entity count, throughput counters (enqueue / drain / coalesce), sink-error count, saturation flag, lifecycle timestamps.

**WHEN:** use for substrate-health monitoring during reactive-projection work — diagnosing queue backpressure, verifying the worker is running, checking whether the dirty-set is draining cleanly.  When NOT to use: (a) you want the per-event log timeline — read sandbar.log for `:REACTIVE/<event-name>` records; (b) you want to check the registered callback / sink count specifically — those counters are in the response but `sandbar.reactive/callback-count` + `sandbar.reactive.queue/sink-count` (in-process API) give direct access.

**HOW:** no arguments.  Returns:
  - `:worker-running?` — bool (was `(reactive-queue/start!)` called?)
  - `:buffer-size` — int (sliding-buffer capacity)
  - `:dirty-entity-count` — distinct entities currently pending projection
  - `:oldest-pending-age-ms` — int or nil (lag indicator)
  - `:enqueue-total` / `:drain-total` / `:coalesce-total` — cumulative counters since startup
  - `:sink-error-total` — cumulative sink-fn failures
  - `:registered-sinks` — sink count
  - `:saturated?` — bool (oldest-pending-age-ms exceeds threshold)
  - `:startup-instant` / `:last-enqueue-instant` / `:last-drain-instant` — ISO-8601 timestamps

**ORDER:** leaf-call; no prerequisites beyond sandbar being up.

**COMBINATION:** composes with `:REACTIVE/<event-name>` log records (timeline forensics).

<a id="resolve-1"></a>

## resolve

### `sandbar.resolve`

Resolve an entity-reference of any wire form (URN / ident / rel-path / eid). Catalog hint: `[safe]`. Wire name: `sandbar_resolve`.

**Arguments** (`*` = required):

- `reference`\* (string) — URN form (urn:uuid:...), substrate ident (memory.X/Y), rel-path (dir/slug.md), or eid (numeric string)

**Complete input schema:**

```json
{
  "properties" : {
    "reference" : {
      "description" : "URN form (urn:uuid:...), substrate ident (memory.X/Y), rel-path (dir/slug.md), or eid (numeric string)",
      "type" : "string"
    }
  },
  "required" : [ "reference" ],
  "type" : "object"
}
```

**WHICH:** resolves a reference of any wire form to its canonical entity. PURL-style indirection — federation-shaped references resolve to canonical entities regardless of which wire form was used.

**WHEN:** use to resolve federation-shaped references (URN form `urn:uuid:<v5>`) into substrate entities; sister verb to sandbar.entity.find (ident form) + sandbar.entity.find-by-rel-path (rel-path form).  When NOT to use: (a) you already know the wire form is an ident — sandbar.entity.find is more direct; (b) you have a rel-path string + know it's that form — sandbar.entity.find-by-rel-path.

**HOW:** `:reference` is the input string in any of:
  - `urn:uuid:<v5>` — federation wire form; resolves via :mm/id lookup
  - `:memory.X/Y` or `memory.X/Y` — substrate ident form; resolves via eref/resolve
  - `<dir>/<slug>.md` or `<dir>/<slug>` — corpus rel-path form; resolves via memory.<dir>/<slug> ident derivation
  - numeric string — eid form; resolves via eref/resolve

Returns `{:reference :resolved-entity :resolution-path}` where :resolution-path is one of :urn-uuid | :substrate-ident | :rel-path | :eid.  Returns :resolved-entity nil + :error string if the reference cannot be resolved.

**COMBINATION:** pairs with sandbar.namespace.policy (each resolution can be policy-checked against the namespace's CommitmentStatement).

<a id="schedule-8"></a>

## schedule

### `sandbar.schedule.add`

Add a :mm/Schedule to the priority queue. Catalog hint: `[unsafe]`. Wire name: `sandbar_schedule_add`.

**Arguments** (`*` = required):

- `schedule-eid`\* (integer) — Numeric eid of the :mm/Schedule entity

**Complete input schema:**

```json
{
  "properties" : {
    "schedule-eid" : {
      "description" : "Numeric eid of the :mm/Schedule entity",
      "type" : "integer"
    }
  },
  "required" : [ "schedule-eid" ],
  "type" : "object"
}
```

**WHICH:** invokes `sandbar.schedule/add-schedule!` — resolves the :mm/Schedule entity by eid, computes its next-fire-at from `(now)` via the RRULE iterator (honoring `:mm.schedule/exdates` + `:mm.schedule/until`), inserts a `[next-fire-at schedule-eid]` entry into the priority queue, and `.interrupts` the fire-thread to re-park on the new head if appropriate.

**WHEN:** use to bring a :mm/Schedule into the scheduler's active queue — either at boot (γ.5 demo job autostart) or via operator-initiated additions.  When NOT to use: (a) the Schedule entity doesn't exist yet — author it via `sandbar.entity.create :class :mm/Schedule` first; (b) you want to REMOVE — `sandbar.schedule.remove`.

**HOW:** `:schedule-eid` is the numeric eid of the :mm/Schedule entity.  Returns `{:schedule-eid :next-fire-at <iso-string-or-nil>}`.  Returns next-fire-at nil when the schedule has no future fires (terminated RRULE / malformed schedule) — queue unchanged in that case.  Idempotent — re-adding an already-queued schedule replaces (not duplicates) its entry.

### `sandbar.schedule.disable`

Flip the scheduler `:enabled?` flag false (does NOT stop fire-thread). Catalog hint: `[idem]`. Wire name: `sandbar_schedule_disable`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** sets the scheduler's runtime `:enabled?` flag to false.  Does NOT release the handler-pool or stop the fire-thread.  When the fire-thread fires a scheduled event during the disabled period, the subscriber silently skips it (per `handle-scheduled-event`'s enable-gate).

**WHEN:** use to SUSPEND firings without tearing down resources — e.g., maintenance windows where the scheduler stays warm but produces no Runs.  When NOT to use: (a) you want full lifecycle teardown — `sandbar.schedule.stop`.

**HOW:** no arguments.  Returns `{:outcome :disabled}`.  Idempotent.

### `sandbar.schedule.enable`

Flip the scheduler `:enabled?` flag true (does NOT start fire-thread). Catalog hint: `[idem]`. Wire name: `sandbar_schedule_enable`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** sets the scheduler's runtime `:enabled?` flag to true.  Does NOT allocate the handler-pool or spawn the fire-thread — use `sandbar.schedule.start` for full activation.  When the dispatcher is running, enabling permits scheduled fires to actually emit Run-creation events.

**WHEN:** use to TOGGLE the gate flag without lifecycle effect — e.g., to unblock an already-running scheduler that was paused via `sandbar.schedule.disable`.  When NOT to use: (a) the scheduler is not running — call `sandbar.schedule.start` (which both enables AND starts); (b) you want to STOP fires + release resources — `sandbar.schedule.stop`.

**HOW:** no arguments.  Returns `{:outcome :enabled}`.  Idempotent.

### `sandbar.schedule.inspect`

Diagnostic: full operator-facing scheduler runtime snapshot. Catalog hint: `[safe]`. Wire name: `sandbar_schedule_inspect`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the canonical operator-facing snapshot of scheduler runtime state — state-machine + enabled-flag + queue size + handler-pool allocated + fire-thread allocated + clock-drift + in-flight Runs + subscriber-registered flag.

**WHEN:** use as the one-call operator-status verb — for MCP-driven dashboards, health-check tooling, debugging session-orientation.  When NOT to use: (a) you want only the queue entries — `sandbar.schedule.list` (smaller payload); (b) you want only the state keyword — there's no smaller verb; this one's payload is bounded.

**HOW:** no arguments.  Returns the canonical inspect map keys: `:state :enabled? :queue-size :handler-pool? :fire-thread? :clock-drift-ms :in-flight-runs :subscriber-registered?`.

### `sandbar.schedule.list`

Diagnostic: list all currently-queued schedule fires. Catalog hint: `[safe]`. Wire name: `sandbar_schedule_list`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the priority queue's current entries — each entry is `[next-fire-at-instant schedule-eid]`, in priority order (earliest fire first).

**WHEN:** use for diagnostic introspection of what the scheduler will fire next.  When NOT to use: (a) you want the full operator snapshot (state-machine + handler-pool + in-flight runs) — `sandbar.schedule.inspect`.

**HOW:** no arguments.  Returns `{:queue-size :entries [{:next-fire-at <iso-string> :schedule-eid <integer>} ...]}`.

### `sandbar.schedule.remove`

Remove a :mm/Schedule from the priority queue. Catalog hint: `[unsafe]`. Wire name: `sandbar_schedule_remove`.

**Arguments** (`*` = required):

- `schedule-eid`\* (integer) — Numeric eid of the :mm/Schedule entity

**Complete input schema:**

```json
{
  "properties" : {
    "schedule-eid" : {
      "description" : "Numeric eid of the :mm/Schedule entity",
      "type" : "integer"
    }
  },
  "required" : [ "schedule-eid" ],
  "type" : "object"
}
```

**WHICH:** invokes `sandbar.schedule/remove-schedule!` — removes all queue entries for the given :mm/Schedule eid, `.interrupts` the fire-thread to re-park on the new head.

**WHEN:** use to deschedule a :mm/Schedule without retracting the entity itself — e.g., temporary suppression while keeping the Schedule available for re-add later.  When NOT to use: (a) you want to permanently retract — combine with `sandbar.entity.update` or substrate-level retract; (b) you want to disable ALL fires (gate flag) — `sandbar.schedule.disable`.

**HOW:** `:schedule-eid` is the numeric eid.  Returns `{:schedule-eid :outcome :removed}`.  Idempotent.

### `sandbar.schedule.start`

Full activation: allocate handler-pool + spawn fire-thread + register subscriber. Catalog hint: `[unsafe]`. Wire name: `sandbar_schedule_start`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** invokes `sandbar.schedule/start!` — allocates the handler-pool ExecutorService, spawns the fire-thread, transitions state-machine to `:scheduler.state/active`, AND registers the `:mm.event/Scheduled` subscriber.  Composes the two-side activation that the standalone `dispatcher.start!` + `job-dispatcher.register!` don't.

**WHEN:** use to bring the scheduler fully online from `:scheduler.state/inactive`.  Production callsite is typically `sandbar.core/start` (auto-invoked when `config.edn :scheduler/enabled? true`); operators invoke this verb to manually start outside config-controlled boot.  When NOT to use: (a) just flipping the enable flag — `sandbar.schedule.enable`; (b) suspending temporarily — `sandbar.schedule.disable`.

**HOW:** no arguments.  Returns `{:outcome :started}` on success, `{:outcome :already-active}` when already running.  Idempotent.

### `sandbar.schedule.stop`

Full deactivation: unregister subscriber + drain handler-pool + join fire-thread. Catalog hint: `[unsafe]`. Wire name: `sandbar_schedule_stop`.

**Arguments** (`*` = required):

- `drain-timeout-ms` (integer) — Max ms to wait for handler-pool drain (default 5000)

**Complete input schema:**

```json
{
  "properties" : {
    "drain-timeout-ms" : {
      "description" : "Max ms to wait for handler-pool drain (default 5000)",
      "type" : "integer"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** invokes `sandbar.schedule/stop!` — unregisters the `:mm.event/Scheduled` subscriber, transitions state-machine to `:scheduler.state/draining`, interrupts + joins the fire-thread (bounded by `:drain-timeout-ms`, default 5000), shuts down the handler-pool, transitions to `:scheduler.state/inactive`.  Composes the inverse-side deactivation of `sandbar.schedule.start`.

**WHEN:** use to fully release scheduler resources — typically at JVM shutdown via `sandbar.core/stop`, OR for operator-initiated lifecycle cycles.  When NOT to use: (a) just disabling without teardown — `sandbar.schedule.disable`; (b) restarting — call this verb then `sandbar.schedule.start` (no atomic restart verb).

**HOW:** optional `:drain-timeout-ms` (default 5000).  Returns `{:outcome :stopped}` on success, `{:outcome :already-inactive}` when not running.  Idempotent.

<a id="schema-4"></a>

## schema

### `sandbar.schema.classes`

List every class ident in the metamodel. Catalog hint: `[safe]`. Wire name: `sandbar_schema_classes`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the sorted vec of every `:dt/Class` instance ident (e.g. `:mm/Memory`, `:dt/Property`, `:auth/User`) registered in the metamodel.

**WHEN:** use as the FIRST DISCOVERY CALL when an AI client needs to understand what kinds of entities the substrate manages.  Foundational for bootstrap-by-discovery — the catalog at `tools/list` is the verb surface; this verb is the class surface.  When NOT to use: (a) you only need ONE class's details — call `sandbar.class.describe` directly; (b) you need INSTANCES rather than class idents — `sandbar.class.instances` after picking a class.

**HOW:** no arguments.  Returns `{:classes [<ident-string>...]}`.

**ORDER:** typical bootstrap sequence — `sandbar.schema.classes` (this verb; discover classes) → `sandbar.class.describe :class :foo/X` (inspect one) → `sandbar.class.instances :class :foo/X` (enumerate its entities).

**COMBINATION:** pairs with `sandbar.schema.properties` (parallel — property surface) and `sandbar.schema.datatypes` (the value-type surface beneath classes).  For a single-call batch alternative to multiple `sandbar.class.instances` invocations, use `sandbar.schema.entities` instead.

### `sandbar.schema.datatypes`

List every Datomic value type (`:db.type/*`) registered. Catalog hint: `[safe]`. Wire name: `sandbar_schema_datatypes`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the sorted vec of every `:db.type/*` value type registered in the metamodel — primitives (`:db.type/string`, `:db.type/long`, `:db.type/boolean`, `:db.type/instant`, `:db.type/keyword`, `:db.type/uuid`, `:db.type/uri`), refs (`:db.type/ref`), and Sandbar-specific extensions if present.

**WHEN:** use when introspecting the SUBSTRATE layer — what primitive types can attributes carry?  Less commonly needed than `sandbar.schema.classes` (which inspects the user-facing class surface); useful for tooling that needs to understand the underlying type system.  When NOT to use: (a) you want a specific property's value type — `sandbar.property.range :property :foo/bar`; (b) you want consumer-class types — `sandbar.schema.classes`.

**HOW:** no arguments.  Returns `{:datatypes [<ident-string>...]}`.

**ORDER:** no prerequisites; foundational discovery call.

**COMBINATION:** pairs with `sandbar.property.range` (which returns one of these datatype idents for a property).  Rarely needed at the AI-client level — most reads stay at the class / property layer.

### `sandbar.schema.entities`

Batch fetch entity-spec maps grouped by class (N+1 elimination). Catalog hint: `[safe]`. Wire name: `sandbar_schema_entities`.

**Arguments** (`*` = required):

- `classes` (array) — Optional array of class-ident strings to fetch; default fetches all non-abstract classes

**Complete input schema:**

```json
{
  "properties" : {
    "classes" : {
      "description" : "Optional array of class-ident strings to fetch; default fetches all non-abstract classes",
      "items" : {
        "type" : "string"
      },
      "type" : "array"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns entity-spec maps grouped by class — one substrate round-trip instead of N+1 separate `sandbar.class.instances` calls.  Default fetches every non-abstract class's instances; optional `:classes` filter restricts to a specified subset.

**WHEN:** use when you need to enumerate the substrate's entire entity state (or a slice across multiple classes) in one call — e.g., for bulk export, schema visualization, full-corpus reporting.  This verb exists specifically to eliminate the N+1 round-trip cost of iterating over classes and calling `sandbar.class.instances` per class.  When NOT to use: (a) you only need one class's instances — call `sandbar.class.instances` directly (smaller payload); (b) you need ranked / filtered instances — use `sandbar.aggregate.rank-by` or `sandbar.search.bm25f` instead.

**HOW:** `:classes` (optional) is a JSON array of class-ident strings.  If omitted, fetches all non-abstract classes.  Returns `{:by-class {<class-ident-string> [<entity-map>...] ...} :total-classes <int> :total-entities <int>}`.

**ORDER:** discover classes via `sandbar.schema.classes` first (if you don't already know them).  After this call, you have the full per-class entity inventory; downstream operations (per-entity inspection, projection, etc.) follow.

**COMBINATION:** alternative to N × `sandbar.class.instances`.  Composes with downstream filtering: take the result's `:by-class` map and apply consumer-side predicates.  For structured filtering at the substrate, use `sandbar.aggregate.count` / `.group-by` with a `:where` Datalog clause instead.

### `sandbar.schema.properties`

List every property ident in the metamodel. Catalog hint: `[safe]`. Wire name: `sandbar_schema_properties`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the sorted vec of every `:dt/Property` instance ident (e.g. `:mm.memory/name`, `:dt/subclass-of`, `:auth/email`).  Every attribute that has been declared at the metamodel layer — both the substrate's own (`:dt/*`) and consumer-class declarations (`:mm.memory/*` etc.).

**WHEN:** use to discover the PREDICATE vocabulary available for typed-edge navigation, structured queries, or schema introspection.  Foundational discovery call — companion to `sandbar.schema.classes`.  When NOT to use: (a) you want a SPECIFIC property's domain/range/cardinality — `sandbar.property.{domain,range,cardinality}`; (b) you want only the properties declared on ONE class — `sandbar.class.slots :class :foo/X` (returns inherited + direct); (c) you want properties whose domain IS a specific class — no direct verb; query via `sandbar.property.domain` over a candidate set.

**HOW:** no arguments.  Returns `{:properties [<ident-string>...]}`.

**ORDER:** typical sequence — `sandbar.schema.properties` (discover) → `sandbar.property.range :property :foo/bar` (inspect one's value type) → `sandbar.property.domain :property :foo/bar` (its applicable class).

**COMBINATION:** pairs with `sandbar.schema.classes` (the class surface) and `sandbar.class.slots` (per-class subset).  Predicate-set values feed `sandbar.navigate.*` verbs (path-grammar / edges / walk) as the `:predicates` opt.

<a id="search-2"></a>

## search

### `sandbar.search.attribute`

Single-attribute Lucene-syntax fulltext search (`:db.fn/fulltext-search`). Catalog hint: `[safe]`. Wire name: `sandbar_search_attribute`.

**Arguments** (`*` = required):

- `attribute`\* (string) — Slot ident with :db/fulltext true (e.g. ':mm.memory/body-raw')
- `limit` (integer) — Max hits (default 50; 0 = no cap)
- `projection` (string) — Per-hit entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies.
- `query`\* (string) — Lucene query string

**Complete input schema:**

```json
{
  "properties" : {
    "attribute" : {
      "description" : "Slot ident with :db/fulltext true (e.g. ':mm.memory/body-raw')",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max hits (default 50; 0 = no cap)",
      "type" : "integer"
    },
    "projection" : {
      "description" : "Per-hit entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies.",
      "type" : "string"
    },
    "query" : {
      "description" : "Lucene query string",
      "type" : "string"
    }
  },
  "required" : [ "attribute", "query" ],
  "type" : "object"
}
```

**WHICH:** returns entities whose `:attribute` value matches the Lucene query under Datomic's `:db.fn/fulltext-search`.  Single-slot search — unlike `sandbar.search.bm25f` which scores across multi-field weights, this verb hits ONE attribute (which must be `:db/fulltext true`) with full Lucene query-syntax support.

**WHEN:** use when the query needs Lucene operators — phrase quoting (`"exact phrase"`), boolean (`foo AND bar`, `foo OR bar`, `NOT foo`), wildcards (`foo*`), fuzzy (`foo~`), field-prefixed (`field:value`).  Also: when you want single-attribute targeted retrieval without multi-field weighting (e.g., search ONLY the description slot).  When NOT to use: (a) multi-field weighted ranking across name + description + body + tags — use `sandbar.search.bm25f`; (b) bag-of-words across the entity surface — `sandbar.search.bm25f` (which lacks Lucene syntax but covers the full weighted-field set).

**HOW:** `:attribute` is the slot ident (must be `:db/fulltext true`).  `:query` is a Lucene query string.  Optional `:limit` caps hits (default 50; 0 = no cap).

AUTHENTICATED SEARCH: unreadable matches are excluded using the current stored record before sorting, counting and limiting. Hit projection uses the same store snapshot as that decision.

**ORDER:** prerequisite — discover fulltext-indexed attributes via `sandbar.class.slots` + check `:db/fulltext` flag (or by domain knowledge of which slots are indexed).

**COMBINATION:** pairs with `sandbar.search.bm25f` (BM25F handles the multi-field bag-of-words case; this verb handles the Lucene-syntax single-slot case).  Returned entity IDs can seed sandbar.navigate.path-via or edge navigation; sandbar.aggregate.rank-by does not accept a candidate-ID array.

Result: `{:hits [{:entity <entity-map> :score <double>} ...] :total <int> :returned <int> :timing {:total-ms <int>}}`.

### `sandbar.search.bm25f`

Multi-field BM25F fulltext search over a class's instances. Catalog hint: `[safe]`. Wire name: `sandbar_search_bm25f`.

**Arguments** (`*` = required):

- `class`\* (string or array) — Class ident whose `:dt/bm25f-weights` drives field selection — a single class-ident string (e.g. ':mm/Memory'), OR a JSON array of 2..8 class-ident strings for strategic-subgroup scope (e.g. [':mm/Memory' ':mm/Tag' ':mm/Verb']).  Multi-class queries each class with its OWN declared weights, merges hits, and sorts by raw score descending.  CAVEAT: cross-class raw-score comparability is APPROXIMATE (per-class IDF + length-normalization differ); deeper score unification is out of v1.  `:field-weights` override is single-class-only (loud error with a vec).
- `facet-by` (array) — Slot-idents to facet over the full match-set
- `field-weights` (object) — Optional {slot-ident weight} map overriding class declaration
- `from` (string) — seed entity ident (e.g. ':memory.notes/example') or numeric eid as a string for `:via` graph-walk pre-filter; require :via together
- `include` (array) — Projection options: 'field-scores' / 'snippets'
- `limit` (integer) — Max hits (default 20; 0 = no cap)
- `projection` (string) — Per-hit entity-shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only), 'frontmatter' (all scalar+ref slots EXCEPT the bulky :mm.memory/body-raw — the lean middle ground for consumers that read name/description/rel-path/memory-type without bodies, for example, a summary listing), or 'full' (all slots; ~10-100× larger payload).  Opt to 'full' only when consumers need slot bodies; otherwise the leaner shapes keep payloads small.
- `query`\* (string) — Query string (bag-of-words; no Lucene query-language operators)
- `rank-by` (string) — re-rank axis — ':degree' / ':backlink-density' / ':recency' / ':freshness'; or ':relevance' to opt out of the default status- and recency-aware ordering for pure BM25F order
- `temporal-slot` (string) — required for :rank-by :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')
- `via` (string) — EDN-string path-grammar expression (same dialect as sandbar.navigate.path-via)
- `where` (string) — Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. Returned entities are clearance-checked, but variable joins can still test hidden targets.

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident whose `:dt/bm25f-weights` drives field selection — a single class-ident string (e.g. ':mm/Memory'), OR a JSON array of 2..8 class-ident strings for strategic-subgroup scope (e.g. [':mm/Memory' ':mm/Tag' ':mm/Verb']).  Multi-class queries each class with its OWN declared weights, merges hits, and sorts by raw score descending.  CAVEAT: cross-class raw-score comparability is APPROXIMATE (per-class IDF + length-normalization differ); deeper score unification is out of v1.  `:field-weights` override is single-class-only (loud error with a vec).",
      "oneOf" : [ {
        "type" : "string"
      }, {
        "items" : {
          "type" : "string"
        },
        "maxItems" : 8,
        "minItems" : 2,
        "type" : "array"
      } ]
    },
    "facet-by" : {
      "description" : "Slot-idents to facet over the full match-set",
      "items" : {
        "type" : "string"
      },
      "type" : "array"
    },
    "field-weights" : {
      "description" : "Optional {slot-ident weight} map overriding class declaration",
      "type" : "object"
    },
    "from" : {
      "description" : "seed entity ident (e.g. ':memory.notes/example') or numeric eid as a string for `:via` graph-walk pre-filter; require :via together",
      "type" : "string"
    },
    "include" : {
      "description" : "Projection options: 'field-scores' / 'snippets'",
      "items" : {
        "type" : "string"
      },
      "type" : "array"
    },
    "limit" : {
      "description" : "Max hits (default 20; 0 = no cap)",
      "type" : "integer"
    },
    "projection" : {
      "description" : "Per-hit entity-shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only), 'frontmatter' (all scalar+ref slots EXCEPT the bulky :mm.memory/body-raw — the lean middle ground for consumers that read name/description/rel-path/memory-type without bodies, for example, a summary listing), or 'full' (all slots; ~10-100× larger payload).  Opt to 'full' only when consumers need slot bodies; otherwise the leaner shapes keep payloads small.",
      "type" : "string"
    },
    "query" : {
      "description" : "Query string (bag-of-words; no Lucene query-language operators)",
      "type" : "string"
    },
    "rank-by" : {
      "description" : "re-rank axis — ':degree' / ':backlink-density' / ':recency' / ':freshness'; or ':relevance' to opt out of the default status- and recency-aware ordering for pure BM25F order",
      "type" : "string"
    },
    "temporal-slot" : {
      "description" : "required for :rank-by :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')",
      "type" : "string"
    },
    "via" : {
      "description" : "EDN-string path-grammar expression (same dialect as sandbar.navigate.path-via)",
      "type" : "string"
    },
    "where" : {
      "description" : "Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. Returned entities are clearance-checked, but variable joins can still test hidden targets.",
      "type" : "string"
    }
  },
  "required" : [ "query", "class" ],
  "type" : "object"
}
```

**WHICH:** returns the top-K instances of `:class` ranked by Robertson-Zaragoza canonical BM25F over multi-field length-normalized scoring.  Field weights are introspected from the class's `:dt/bm25f-weights` declaration unless overridden via `:field-weights` opt.  Ref-typed slots whose `:dt/range` is a class with its own `:dt/bm25f-weights` automatically resolve to the target's weighted text content — e.g. on `:mm/Memory`, `:mm.memory/tags` + `:mm.memory/themes` tokenize via their referenced `:mm/Tag` content (value + alt-label + definition + scope-note + hidden-label + example).

**WHEN:** use for content-relevance ranking — 'which memorials mention this concept'.  When NOT to use: (a) pure structural ranking with no content filter — use `sandbar.aggregate.rank-by`; (b) exact-string lookup — use `sandbar.entity.find` (by ident); (c) Lucene query-language operators (AND / OR / NOT / phrase / wildcard / fuzzy / field-prefix) — these are NOT recognized; bag-of-words only.

**HOW:** `:query` is a bag-of-words string (tokenized via Porter stemmer + lowercase + word-boundary split).  `:class` is the class ident.  Optional: `:limit` caps hits (default 20; 0 = no cap).  `:where` is a Datalog clause vec (or EDN string) restricting hits to entities matching the predicate; clauses must reference `?e` as the entity variable.  `:facet-by` is a vec of slot-idents to facet over the FULL match-set (before limit).  `:include` is a vec of projection options — `:field-scores` (per-slot scores) and `:snippets` (per-slot ~240-char window with **term** highlighting).  `:field-weights` overrides the class's declared weights.

AUTHENTICATED SEARCH: unreadable matches are excluded before ranking, snippets, field scores, facets and limiting. Clearance, hit payloads, snippets and scalar facets use the current stored record. Analyzed terms, frequencies and class-wide BM25F statistics remain cached; this does not establish lexical freshness after arbitrary writes or privacy of corpus statistics.

CROSS-AXIS COMPOSITION:
  `:from` + `:via` — graph-walk PRE-FILTER restricting candidate set to entities reachable from `:from` under path-grammar expression `:via` (same path-grammar dialect as `sandbar.navigate.path-via`; EDN-string form `"[:REP+ :cites]"`).  Composes with `:where` (intersection).
  `:rank-by` — `:degree` / `:backlink-density` / `:recency` / `:freshness` re-rank top-K by structural axis instead of by BM25F score.  BM25F score is preserved on each hit as `:relevance-score`; the primary `:score` becomes the structural rank value.  `:relevance` opts out of the default ordering (below) for pure BM25F order.
  `:temporal-slot` — REQUIRED when `:rank-by` is `:recency` or `:freshness`.

DEFAULT ORDERING (no `:rank-by`): status- and recency-aware.  A hit the class declares SUPERSEDED (`:dt/superseded-when` on the class, e.g. a `superseded-by` edge or a superseded status on `:mm/Memory`) orders as if its score were halved, so a superseded twin of comparable relevance ranks below its current successor while a far more relevant superseded record still leads (a historical question keeps its answer); among equal weights the more recent (`:dt/recency-slot`, `last-touched` for memorials) comes first.  Demoted hits carry `:superseded? true`; `:score` stays the raw relevance; hits carry `:recency` (epoch ms) when the class declares a recency slot.  Applied before `:limit`.  A class declaring neither keeps pure relevance order.

MULTI-CLASS: `:class` also accepts a JSON ARRAY of 2..8 class-ident strings (e.g. [':mm/Memory' ':mm/Tag' ':mm/Verb']).  Each class is queried with its OWN declared `:dt/bm25f-weights` (per-class field weighting is the design center of BM25F), hit lists are merged, sorted by raw score descending, and `:limit` is applied post-merge.  Each hit's `:entity` carries `:dt/type` so callers can tell classes apart in the merged list.  CAVEAT: cross-class raw-score comparability is APPROXIMATE — each class computes its own IDF (df/N over that class) and length-normalization (per-class avgdl), so raw BM25F scores are not on a unified scale across classes; deeper score-unification is a future refinement, explicitly out of v1.  `:where` / `:from`+`:via` / `:rank-by` apply per-class; `:facet-by` facets over the merged full match-set; `:field-weights` is single-class-only (loud error with a vec).

**ORDER:** prerequisite — the target class(es) must declare `:dt/bm25f-weights` (or supply `:field-weights` opt in single-class mode).  Discover via `sandbar.class.describe` if uncertain.

**COMBINATION:** replaces N+1 round-trips of `bm25f` → `aggregate.rank-by` → `navigate.path-via` filtering with one substrate-side call.  For pure-structural ranking with no content, use `sandbar.aggregate.rank-by` (skips tokenization entirely).  For path-walk without scoring, use `sandbar.navigate.path-via`.

Result: `{:hits [{:entity <entity-map> :eid <id> :score <double> :relevance-score <double>? :field-scores {<slot> <double>}? :snippets {<slot> <string>}?} ...] :total <int> :returned <int> :timing {:total-ms <int>} :facets {<slot> {<value> <count>}}?}`.

<a id="shape-5"></a>

## shape

### `sandbar.shape.conformance-report`

Batch conformance report for direct instances and exact-class shapes. Catalog hint: `[safe]`. Wire name: `sandbar_shape_conformance-report`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** walks entities whose direct :dt/type equals `:class` against every :mm/Shape whose :mm.shape/applies-to equals that same class; aggregates into a structured violation/warning report. It does not include subclass instances or inherit shapes from ancestor classes. Counts such as :total-checks, :passes and :failures are per entity/shape pair, not per individual property constraint.

**WHEN:** use for class-wide invariant audits — e.g. 'what fraction of my :mm/Decision instances satisfy the decision-shape required-property invariant?'.  Substrate-quality + governance applications.  When NOT to use: (a) single-entity check — sandbar.shape.validate; (b) the class has no applicable shapes — the call returns a zero-failure report without establishing that a constraint ran.

**HOW:** `:class` is the target class ident string (e.g. ':mm/Decision').  Returns `{:class <ident> :instance-count <int> :shape-count <int> :total-checks <int> :passes <int> :failures <int> :error-count <int> :warning-count <int> :failure-details [<walk-entity-result>...]}`.

**ORDER:** typical sequence — sandbar.shape.list (discover shapes) → sandbar.shape.conformance-report (run batch) → sandbar.shape.validate (drill into a specific violating entity).

**COMBINATION:** pairs with sandbar.class.validate-all-instances (the parallel class-level invariant runner) and sandbar.audit.* verbs (the legacy in-code audit surfaces).

### `sandbar.shape.create`

Author a new :mm/Shape entity (thin wrapper over sandbar.entity.create). Catalog hint: `[unsafe]`. Wire name: `sandbar_shape_create`.

**Arguments** (`*` = required):

- `format` (string) — Optional codec format (e.g. 'markdown')
- `slots` (object) — Slot map for :mm/Shape
- `source` (string) — Optional raw source string parsed via :format

**Complete input schema:**

```json
{
  "properties" : {
    "format" : {
      "description" : "Optional codec format (e.g. 'markdown')",
      "type" : "string"
    },
    "slots" : {
      "description" : "Slot map for :mm/Shape",
      "type" : "object"
    },
    "source" : {
      "description" : "Optional raw source string parsed via :format",
      "type" : "string"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** thin wrapper over sandbar.entity.create with :class :mm/Shape pre-bound.  Accepts the same :slots / :format / :source argument shape as entity.create.

**WHEN:** use to author new shape memorials.  When NOT to use: (a) you're authoring a non-shape entity — sandbar.entity.create directly; (b) you want to modify an existing shape — sandbar.shape.update.

**HOW:** `:slots` is the slot-map (e.g. {:mm.shape/shape-id 'foo' :mm.shape/applies-to ':mm/Decision' :mm.shape/required-property [':mm.memory/cites']}).  Returns `{:entity <projection>}`.  Optional `:format` + `:source` for codec-driven creation from markdown.

**ORDER:** same as entity.create.

**COMBINATION:** composes with sandbar.shape.list (discover post-creation), sandbar.shape.validate (test against the new shape).

### `sandbar.shape.list`

List :mm/Shape instances; optional filter by :applies-to class. Catalog hint: `[safe]`. Wire name: `sandbar_shape_list`.

**Arguments** (`*` = required):

- `applies-to` (string) — Optional class ident (e.g. ':mm/Decision') to filter shapes

**Complete input schema:**

```json
{
  "properties" : {
    "applies-to" : {
      "description" : "Optional class ident (e.g. ':mm/Decision') to filter shapes",
      "type" : "string"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns all :mm/Shape entities in the substrate, optionally filtered by :applies-to class.  When :applies-to is provided, only shapes that target that class are returned.

**WHEN:** use to discover what shape-validation invariants apply to a given class, or to enumerate the whole shape catalog.  Foundational SHACL-discovery verb.  When NOT to use: (a) you want to validate a specific entity — sandbar.shape.validate; (b) you want batch conformance over a class — sandbar.shape.conformance-report.

**HOW:** optional `:applies-to` is a class ident string (e.g. ':mm/Decision').  Returns `{:applies-to-filter <ident-or-nil> :count <int> :shapes [<entity-projection>...]}`.

**ORDER:** leaf call; no prerequisites.

**COMBINATION:** feeds sandbar.shape.validate (per-shape validation) and sandbar.shape.conformance-report (batch validation).

### `sandbar.shape.update`

Amend an existing :mm/Shape entity (thin wrapper over sandbar.entity.update). Catalog hint: `[idem]`. Wire name: `sandbar_shape_update`.

**Arguments** (`*` = required):

- `entity`\* (string) — :mm/Shape entity ident or eid
- `slots`\* (object) — Slot updates

**Complete input schema:**

```json
{
  "properties" : {
    "entity" : {
      "description" : ":mm/Shape entity ident or eid",
      "type" : "string"
    },
    "slots" : {
      "description" : "Slot updates",
      "type" : "object"
    }
  },
  "required" : [ "entity", "slots" ],
  "type" : "object"
}
```

**WHICH:** applies slot-map updates to an existing :mm/Shape entity.

**WHEN:** use when an existing shape needs a constraint added/removed/refined (e.g., add a cardinality constraint, change required-property set).  When NOT to use: (a) authoring a new shape — sandbar.shape.create; (b) modifying a non-shape entity — sandbar.entity.update.

**HOW:** `:entity` is the shape entity ident or eid.  `:slots` is the slot-map of updates.  Returns `{:entity <projection>}`.

**ORDER:** prerequisite — sandbar.shape.list or sandbar.entity.find to confirm the shape exists.

**COMBINATION:** same as sandbar.entity.update.

### `sandbar.shape.validate`

Validate a single entity against its applicable :mm/Shape instances. Catalog hint: `[safe]`. Wire name: `sandbar_shape_validate`.

**Arguments** (`*` = required):

- `entity`\* (string) — Entity ident (e.g. ':memory.decisions/foo') or numeric eid (as string)
- `mode` (string) — Validation mode: 'audit' (default) / 'strict' / 'disabled'

**Complete input schema:**

```json
{
  "properties" : {
    "entity" : {
      "description" : "Entity ident (e.g. ':memory.decisions/foo') or numeric eid (as string)",
      "type" : "string"
    },
    "mode" : {
      "description" : "Validation mode: 'audit' (default) / 'strict' / 'disabled'",
      "type" : "string"
    }
  },
  "required" : [ "entity" ],
  "type" : "object"
}
```

**WHICH:** walks the entity against every shape whose :mm.shape/applies-to exactly matches the entity's class; aggregates per-check results into a structured report.

**WHEN:** use to verify a single entity conforms to its class invariants.  Most-common SHACL-consumer call.  When NOT to use: (a) batch validation over a class — sandbar.shape.conformance-report; (b) no shape targets the entity's class — the call returns empty results; this is not evidence that any constraint ran.

**HOW:** `:entity` is the entity ident OR numeric eid.  Optional `:mode` is one of 'audit' (default; returns results), 'strict' (throws ex-info on :violation-severity failures), or 'disabled' (returns [] without checking).  Returns `{:entity <ref-string> :mode <kw> :result-count <int> :results [<walk-entity-result>...]}`.

**ORDER:** leaf call; prerequisite is the entity exists.

**COMBINATION:** paired with sandbar.entity.create (which applies its selected shape validation mode) and sandbar.shape.conformance-report (batch).

<a id="tag-9"></a>

## tag

### `sandbar.tag.align`

Declare a cross-vocabulary SKOS mapping from a tag to an external IRI. Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_align`.

**Arguments** (`*` = required):

- `external-iri`\* (string) — External concept IRI (e.g., Wikidata Q-id URL)
- `mapping-type` (string) — SKOS mapping relation — one of exact-match / close-match / broader-match / narrower-match / related-match (default exact-match)
- `tag`\* (string) — Corpus tag :value

**Complete input schema:**

```json
{
  "properties" : {
    "external-iri" : {
      "description" : "External concept IRI (e.g., Wikidata Q-id URL)",
      "type" : "string"
    },
    "mapping-type" : {
      "description" : "SKOS mapping relation — one of exact-match / close-match / broader-match / narrower-match / related-match (default exact-match)",
      "type" : "string"
    },
    "tag" : {
      "description" : "Corpus tag :value",
      "type" : "string"
    }
  },
  "required" : [ "tag", "external-iri" ],
  "type" : "object"
}
```

**WHICH:** records a cross-vocabulary mapping from :tag to :external-iri under a SKOS mapping relation (`:exact-match` / `:close-match` / `:broader-match` / `:narrower-match` / `:related-match`).

**WHEN:** use when the corpus's tag aligns with a tag in an external vocabulary (e.g., a Wikidata Q-id, a Dewey class, a Schema.org type, a SKOS concept in a referenced ontology).  Foundational for the federation backbone — VoID :mm/Linkset entities aggregate these mappings.  When NOT to use: (a) the external concept isn't actually mapped — don't fabricate; (b) you want a tag-to-tag mapping within the corpus — use :mm.tag/related instead.

**HOW:** `:tag` is the corpus tag :value.  `:external-iri` is the external concept's IRI (e.g., "http://www.wikidata.org/entity/Q12345").  `:mapping-type` is one of "exact-match" / "close-match" / "broader-match" / "narrower-match" / "related-match" (default "exact-match").

Returns `:tag`, `:external-iri`, `:mapping-type`, `:slot` (the resolved :mm.tag/<type> slot).

MVP: stores the external IRI as a :mm/Tag entity (via :mm.tag/value upsert) referenced by the mapping slot.  This does not create a complete :mm/Vocabulary or :mm/Linkset model.

**ORDER:** after the tag is defined (sandbar.tag.define).

**COMBINATION:** pairs with sandbar.tag.audit (the alignment is auditable as a SKOS-mapping relation), sandbar.tag.harmonize (local vocabulary drift proposals; it does not query external schemes).

### `sandbar.tag.audit`

Run the 7 tag-lifecycle invariants and return the violation report. Catalog hint: `[safe]`. Wire name: `sandbar_tag_audit`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** runs `sandbar.audit.tag/audit-all` — seven independent invariants over the corpus's tag vocabulary.  Returns per-invariant violations + an aggregate count.

The seven invariants:
  1. `:undefined-used`      — tags referenced via :mm.memory/tags lacking :mm.tag/definition
  2. `:defined-unused`      — tags with :mm.tag/definition but no inbound :mm.memory/tags refs
  3. `:orphan`              — tags with no :mm.tag/in-scheme membership
  4. `:date-pattern`        — tags whose :mm.tag/value matches a date pattern
  5. `:type-pattern`        — tags whose :mm.tag/value overlaps a memorial-type keyword
  6. `:drift`               — clusters of tags with same normalized form (case + plural)
  7. `:closure-consistency` — cycles on broader-* / asymmetries on :related / missing inverse pairs on :superseded-by

**WHEN:** use periodically to monitor vocabulary health.  Foundational pre-step for sandbar.tag.harmonize.  When NOT to use: (a) you want ONE invariant — call sandbar.audit.tag/<invariant-fn> via the in-process API directly (no individual MCP verb yet; aggregate-only at this stage).

**HOW:** no arguments.  Returns `{:invariants [<map per invariant>] :total-violations N :summary <string>}`.

**ORDER:** no prerequisites; foundational diagnostic.

**COMBINATION:** feeds sandbar.tag.harmonize (drift cluster reconciliation), sandbar.tag.consolidate (per-cluster merges), sandbar.tag.define (for :undefined-used findings).

### `sandbar.tag.consolidate`

Merge :from tag INTO :into tag; preserves :from as alt-label + lifecycle :superseded. Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_consolidate`.

**Arguments** (`*` = required):

- `from`\* (string) — Tag :value to merge OUT (becomes alt-label on :into)
- `into`\* (string) — Tag :value to merge INTO (canonical preserved)

**Complete input schema:**

```json
{
  "properties" : {
    "from" : {
      "description" : "Tag :value to merge OUT (becomes alt-label on :into)",
      "type" : "string"
    },
    "into" : {
      "description" : "Tag :value to merge INTO (canonical preserved)",
      "type" : "string"
    }
  },
  "required" : [ "from", "into" ],
  "type" : "object"
}
```

**WHICH:** merges two tags by adding :from's canonical :value as a :mm.tag/alt-label on :into, marking :from with :mm.tag/lifecycle-status :superseded + :mm.tag/superseded-by ref to :into, and rewriting every :mm.memory/tags ref from :from to :into.  The merge preserves history (alt-label + superseded-by) for search-recall + audit trail.

**WHEN:** use to resolve drift clusters surfaced by sandbar.tag.audit `:drift` invariant — `{tag, tags}` → consolidate "tags" into "tag".  Also use for editorial vocabulary cleanup (synonyms / variant spellings).  When NOT to use: (a) the tags are NOT synonyms — keep them separate; (b) you want a true rename (no source tag preserved) — use sandbar.tag.rename instead; (c) you want to partition a tag into narrower tags — use sandbar.tag.split.

**HOW:** `:from` is the variant being merged out; `:into` is the canonical being merged into.  Both are :mm.tag/value strings.  Returns `:from`, `:into`, `:memorials-rewritten` (count of memorials whose :tags ref was rewritten), `:alt-label-added` (the preserved-as-alt-label value), `:lifecycle-status`.

**ORDER:** after sandbar.tag.audit surfaces a drift cluster + editorial decision selects canonical.

**COMBINATION:** pairs with sandbar.tag.audit (cluster discovery), sandbar.tag.harmonize (bulk drift-cluster planner), sandbar.tag.rename (when no merge is needed).

### `sandbar.tag.consolidate-all`

Batch-merge multiple drift clusters in a single MCP call. Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_consolidate-all`.

**Arguments** (`*` = required):

- `pairs`\* (array) — Vector of {from, into} objects to consolidate

**Complete input schema:**

```json
{
  "properties" : {
    "pairs" : {
      "description" : "Vector of {from, into} objects to consolidate",
      "items" : {
        "properties" : {
          "from" : {
            "type" : "string"
          },
          "into" : {
            "type" : "string"
          }
        },
        "required" : [ "from", "into" ],
        "type" : "object"
      },
      "type" : "array"
    }
  },
  "required" : [ "pairs" ],
  "type" : "object"
}
```

**WHICH:** applies tag.consolidate semantics to a vector of `{from, into}` pairs in one MCP round-trip.  Per-pair errors collected (does NOT halt on first error); aggregate counts surface in the response.

**WHEN:** use after sandbar.tag.harmonize surfaces drift clusters + editorial decisions selecting canonicals — apply a reviewed batch with one MCP call.  When NOT to use: (a) you have a single pair — sandbar.tag.consolidate (simpler); (b) pairs need different editorial review per cluster — review then batch the auto-mergeable subset only.

**HOW:** `:pairs` is a JSON array of `{from, into}` objects.  Both fields per object are required.  Returns `{:results [{:from :into :memorials-rewritten :ok | :error} ...] :total :succeeded :failed :memorials-rewritten-total}`.  Per-pair semantics match sandbar.tag.consolidate exactly (alt-label + lifecycle :superseded + :superseded-by + memorial-rewrite).

**ORDER:** after sandbar.tag.harmonize surfaces cluster list + editorial decisions selected canonical per cluster.

**COMBINATION:** amortizes the round-trip overhead of per-cluster sandbar.tag.consolidate during vocabulary cleanup.

### `sandbar.tag.define`

Author a new canonical :mm/Tag with required documentation slots. Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_define`.

**Arguments** (`*` = required):

- `name`\* (string) — Canonical tag string (becomes :mm.tag/value)
- `slots` (object) — Optional :mm.tag/* slots (definition, scope-note, example, broader-*, etc.)
- `upgrade` (boolean) — When true, ADD the supplied :slots to an EXISTING tag with this :value (the normalization workflow for the undefined-used tags surfaced by sandbar.tag.audit).  Default false — create-only mode rejects existing tags loudly.  (Wire-format key MUST be `upgrade` — no `?` suffix — to comply with Anthropic MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`.  Handler accepts both `upgrade` and legacy `upgrade?` for back-compat.)

**Complete input schema:**

```json
{
  "properties" : {
    "name" : {
      "description" : "Canonical tag string (becomes :mm.tag/value)",
      "type" : "string"
    },
    "slots" : {
      "description" : "Optional :mm.tag/* slots (definition, scope-note, example, broader-*, etc.)",
      "type" : "object"
    },
    "upgrade" : {
      "description" : "When true, ADD the supplied :slots to an EXISTING tag with this :value (the normalization workflow for the undefined-used tags surfaced by sandbar.tag.audit).  Default false — create-only mode rejects existing tags loudly.  (Wire-format key MUST be `upgrade` — no `?` suffix — to comply with Anthropic MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`.  Handler accepts both `upgrade` and legacy `upgrade?` for back-compat.)",
      "type" : "boolean"
    }
  },
  "required" : [ "name" ],
  "type" : "object"
}
```

**WHICH:** creates a new :mm/Tag entity with the supplied canonical :value + optional documentation slots (definition / scope-note / example / broader-* / in-scheme / etc.).  Forces explicit authoring at the boundary — `sandbar.tag.audit` will surface tags without definitions as the `:undefined-used` invariant.

**WHEN:** use after vocabulary lookup and full reading establish that a new concept is warranted. A lexical gap from sandbar.tag.lookup does not establish that no canonical concept exists.  Authoring includes scope-note — the editorial boundary anchoring the canonical.  When NOT to use: (a) a canonical already exists — use sandbar.tag.consolidate to merge instead; (b) you want to rename — use sandbar.tag.rename; (c) the new tag overlaps a memorial-type — don't define (memorial-type slot already carries that information).

**HOW:** `:name` is the canonical tag string (becomes :mm.tag/value).  `:slots` (optional) is a map of additional :mm.tag/* slot values:
  `:definition`  — SKOS canonical definition
  `:scope-note`  — editorial boundary
  `:example`     — usage illustration
  `:in-scheme`   — :mm/ConceptScheme ref (e.g., `:example/concept-scheme`)
  `:canonical?`  — boolean (default true once defined)
  `:vocabulary-level` — :substrate-level / :corpus-level / etc.
  `:lifecycle-status` — :proposed / :active / :deprecated / :superseded

Returns `{:tag <tag-summary> :created true}`.  Errors when a tag with this :value already exists.

**ORDER:** sandbar.tag.lookup is a discovery step; inspect candidate definitions and content before deciding to author a tag.

**COMBINATION:** pairs with sandbar.tag.lookup (gap discovery), sandbar.tag.audit (post-define audit-check), sandbar.tag.align (cross-vocabulary mapping after defining).

### `sandbar.tag.harmonize`

Bulk-harmonization DRY-RUN report — drift clusters + auto-mergeable counts. Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_harmonize`.

**Arguments:** none.

**Complete input schema:**

```json
{
  "properties" : { },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** runs a vocabulary audit and proposes drift clusters for consolidation. This is an advisory report; it does not apply a merge.

**WHEN:** use to plan vocabulary cleanup. The auto-mergeable label is a heuristic suggestion, not evidence that two concepts have the same meaning. Read definitions and usage before choosing a canonical tag.

**HOW:** no arguments. Returns audit-report, drift-clusters, drift-cluster-count, auto-mergeable-count and an explanatory note.

**ORDER:** review each proposed cluster before applying changes.

**COMBINATION:** sandbar.tag.audit gives invariant details; sandbar.tag.consolidate applies a selected merge; sandbar.tag.split records a partition when concepts should stay separate.

### `sandbar.tag.lookup`

Tag-vocabulary primitive — find canonical tags aligned with a concept. Catalog hint: `[safe]`. Wire name: `sandbar_tag_lookup`.

**Arguments** (`*` = required):

- `concept`\* (string) — Concept-string to look up
- `limit` (integer) — Max conceptual matches returned (default 10); exact matches are always returned
- `projection` (string) — Per-match shape — 'full' (default; the meaning view with identity, lifecycle, successor, scheme, mapping and broader/related context) or 'metadata-only' (lightweight; :db/id + :db/ident + :dt/type + :eid + :value + :match-reason).  Opt to 'metadata-only' for bulk traversal (e.g., walking thousands of audit-flagged tags).

**Complete input schema:**

```json
{
  "properties" : {
    "concept" : {
      "description" : "Concept-string to look up",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max conceptual matches returned (default 10); exact matches are always returned",
      "type" : "integer"
    },
    "projection" : {
      "description" : "Per-match shape — 'full' (default; the meaning view with identity, lifecycle, successor, scheme, mapping and broader/related context) or 'metadata-only' (lightweight; :db/id + :db/ident + :dt/type + :eid + :value + :match-reason).  Opt to 'metadata-only' for bulk traversal (e.g., walking thousands of audit-flagged tags).",
      "type" : "string"
    }
  },
  "required" : [ "concept" ],
  "type" : "object"
}
```

**WHICH:** finds EXISTING vocabulary identities for a concept before any new vocabulary is proposed.  Two passes: an exact pass over every entity carrying `:mm.tag/value` (typed `:mm/Tag` instances AND bare value carriers alike) comparing the concept with the value, alt-label and hidden-label; then a conceptual pass, BM25F over typed `:mm/Tag` instances (value / alt-label / definition / scope-note / hidden-label / example).  Step 1 of the sandbar.ground compositional workflow.

**WHEN:** use to learn whether the vocabulary already has a concept, under which identity, and what it means — before authoring a tag, before a membership traversal.  Disambiguation primitive — collisions (two concepts sharing an alternative label) stay distinct candidates with their match reasons.  When NOT to use: (a) the concept is corpus-wide (try sandbar.search.bm25f over body content instead); (b) you already have the tag's eid or ident (use sandbar.entity.find).

**HOW:** `:concept` is the concept-string; `:limit` (optional) caps the conceptual pass (default 10).  Optional `:projection` — `full` (default; the MEANING VIEW: `:eid`, `:ident` when interned, `:value`, `:typed?`, definition, scope-note, example, alt/hidden labels, `:canonical?` present when asserted true OR false and absent when missing, `:lifecycle-status`, `:superseded-by` (the successor's identity), `:in-scheme`, the SKOS mapping slots, broader/related context) or `metadata-only` (lightweight; :db/id + :db/ident + :dt/type + `:eid` + `:value` per match — every projection supplies a usable identity).  Every match carries `:match-reason` (`:exact-value` / `:exact-alt-label` / `:exact-hidden-label` / `:conceptual`; an entity both passes found lists both) and conceptual matches carry `:score`; exact matches come first.  The conceptual limit does not cap exact matches. The current `:match-total` can overcount overlap when the conceptual pass is limited; do not use it as a distinct-identity denominator. Returns `:concept`, `:matches`, `:returned`, `:match-total`, `:population` (`:value-carriers`, `:typed-tags`, `:untyped-carriers`, counted at read time), `:method`, `:gap?` (true when NEITHER pass matched) and `:gap-hint`.

ERROR VERSUS ABSENCE: a read-barrier timeout or a search failure is an error through the tool envelope, never an empty result; a miss means no match in the stated population and is NOT proof the concept is absent from the corpus (untyped carriers are matched by exact value or label, without a conceptual BM25F pass) — the hint points at sandbar.search.bm25f over :mm/Memory, not at authoring a tag.

**ORDER:** step 1 of sandbar.ground.  The vocabulary journey: this verb (identity + meaning) → sandbar.navigate.inbound-edges from the match's eid with `[":mm.memory/tags" ":mm.memory/themes"]` (the records) → sandbar.entity.find on a selected record.

**COMBINATION:** pairs with sandbar.navigate.inbound-edges (membership), sandbar.search.bm25f (content), sandbar.tag.consolidate (when matches show drift), sandbar.tag.audit (lifecycle health).

### `sandbar.tag.rename`

Change a tag's canonical :value; preserves old as hidden-label. Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_rename`.

**Arguments** (`*` = required):

- `new`\* (string) — New canonical :value (becomes :mm.tag/value)
- `old`\* (string) — Current canonical :value

**Complete input schema:**

```json
{
  "properties" : {
    "new" : {
      "description" : "New canonical :value (becomes :mm.tag/value)",
      "type" : "string"
    },
    "old" : {
      "description" : "Current canonical :value",
      "type" : "string"
    }
  },
  "required" : [ "old", "new" ],
  "type" : "object"
}
```

**WHICH:** changes the canonical :mm.tag/value from :old to :new.  Preserves :old as :mm.tag/hidden-label (kept in fulltext search index for recall; not displayed as canonical or alt-label).  Refs by :db/id are unaffected — no memorial rewrite needed.

**WHEN:** use when canonical form needs to change (typo fix; convention shift; canonicalization).  When NOT to use: (a) you want to merge with an existing canonical — sandbar.tag.consolidate; (b) you want to split — sandbar.tag.split; (c) the tag should be deprecated, not renamed — use sandbar.entity.update to set :mm.tag/lifecycle-status :deprecated.

**HOW:** `:old` is the current :value; `:new` is the new canonical.  Both must be non-blank strings; must differ.  Returns `:old`, `:new`, `:hidden-label-preserved`.

Errors when `:new` is already taken by another tag — use sandbar.tag.consolidate to merge instead.

**ORDER:** no prerequisites beyond having the tag in the corpus.

**COMBINATION:** pairs with sandbar.tag.audit (post-rename verification), sandbar.tag.consolidate (alternative when merging instead of pure-rename).

### `sandbar.tag.split`

Partition a tag into narrower tags (creates :broader-generic children). Catalog hint: `[unsafe]`. Wire name: `sandbar_tag_split`.

**Arguments** (`*` = required):

- `into-tags`\* (array) — Vector of {:value :scope-note} maps for narrower tags (2+ entries)
- `tag`\* (string) — Parent tag :value to partition

**Complete input schema:**

```json
{
  "properties" : {
    "into-tags" : {
      "description" : "Vector of {:value :scope-note} maps for narrower tags (2+ entries)",
      "items" : {
        "properties" : {
          "scope-note" : {
            "type" : "string"
          },
          "value" : {
            "type" : "string"
          }
        },
        "required" : [ "value" ],
        "type" : "object"
      },
      "type" : "array"
    },
    "tag" : {
      "description" : "Parent tag :value to partition",
      "type" : "string"
    }
  },
  "required" : [ "tag", "into-tags" ],
  "type" : "object"
}
```

**WHICH:** declares that :tag is being partitioned into 2+ narrower tags (:into-tags).  Each new tag is created as :mm.tag/broader-generic :tag.  Does NOT auto-reroute existing memorial refs — surfaces the partition; per-memorial reassignment is editorial follow-on.

**WHEN:** use when scope-creep has accumulated under a single tag and the editorial decision is to partition (e.g., "audit" → "audit-corpus" + "audit-discipline" + "audit-schema").  When NOT to use: (a) you want to merge tags — sandbar.tag.consolidate; (b) you want to rename — sandbar.tag.rename; (c) the narrower tags already exist — manually wire :broader-generic via sandbar.entity.update.

**HOW:** `:tag` is the parent tag :value.  `:into-tags` is a vector of `{:value :scope-note}` maps (2+ entries).  Returns `:parent`, `:into-tags` (vec of created values), `:note` (reminder about manual memorial reassignment).

**ORDER:** after editorial decision to partition.  After this verb, manually reassign existing memorial :mm.memory/tags refs via sandbar.entity.update.

**COMBINATION:** pairs with sandbar.entity.update (for memorial reassignment), sandbar.tag.audit (post-split, audit confirms partition is wired).

<a id="tools-2"></a>

## tools

### `sandbar.tools.describe`

Full verb card + typed composition edges for a named verb. Catalog hint: `[safe]`. Wire name: `sandbar_tools_describe`.

**Arguments** (`*` = required):

- `verb`\* (string) — Verb wire name (e.g. 'sandbar.entity.create') or ident (e.g. ':sandbar.entity/create')

**Complete input schema:**

```json
{
  "properties" : {
    "verb" : {
      "description" : "Verb wire name (e.g. 'sandbar.entity.create') or ident (e.g. ':sandbar.entity/create')",
      "type" : "string"
    }
  },
  "required" : [ "verb" ],
  "type" : "object"
}
```

**WHICH:** the full card for one verb from the `:mm/Verb` catalog — title, axis, WHICH/WHEN/HOW, arg-summary, behavioral annotations (readOnly / destructive / idempotent / openWorld / transition-kind / hint-status), AND its typed composition edges: `:prerequisites` (verbs to call first), `:prerequisite-for` (verbs this one enables), `:combines-with` (verbs it composes with), `:produces-input-for` (verbs its output feeds).  Metacircular + graph-backed: the server describing its own verb, edges included.

**WHEN:** use AFTER `sandbar.tools.search` (or when you already know a verb name) to understand a verb deeply + discover what to call before / with / after it.  The describe half of retrieve-then-describe.  When NOT to use: (a) ranked discovery across verbs — `sandbar.tools.search`; (b) only the raw wire input-schema — it is already in `tools/list`.

**HOW:** `:verb` is the verb's wire name ('sandbar.entity.create') OR its ident (':sandbar.entity/create').  Returns the card map, or `{:missing? true}` if the verb is unknown.

**ORDER:** prerequisite — typically `sandbar.tools.search` (to pick the verb).

**COMBINATION:** the `:prerequisites` / `:combines-with` / `:produces-input-for` lists are themselves verb names — feed them back into `sandbar.tools.describe` to plan a multi-verb chain, or call them directly.

### `sandbar.tools.search`

Find the right verb(s) for a task — BM25F over the verb catalog. Catalog hint: `[safe]`. Wire name: `sandbar_tools_search`.

**Arguments** (`*` = required):

- `axis` (string) — Optional verb-family filter (e.g. ':navigate' / ':aggregate' / ':entity')
- `limit` (integer) — Max verb matches (default 10)
- `query`\* (string) — Natural-language task intent (bag-of-words)

**Complete input schema:**

```json
{
  "properties" : {
    "axis" : {
      "description" : "Optional verb-family filter (e.g. ':navigate' / ':aggregate' / ':entity')",
      "type" : "string"
    },
    "limit" : {
      "description" : "Max verb matches (default 10)",
      "type" : "integer"
    },
    "query" : {
      "description" : "Natural-language task intent (bag-of-words)",
      "type" : "string"
    }
  },
  "required" : [ "query" ],
  "type" : "object"
}
```

**WHICH:** ranked verb matches for a natural-language task intent — BM25F over the `:mm/Verb` catalog (sandbar's MCP surface modeled as substrate entities), returning lean verb cards (name / title / axis / transition-kind / read-only? / arg-summary / score).  Metacircular: the server searching its own tool surface.

**WHEN:** use FIRST when you know WHAT you want to do but not WHICH verb does it — rank verbs by intent instead of scanning all ~80.  The retrieve half of retrieve-then-describe.  When NOT to use: (a) you already know the verb — call it directly; (b) you want the full card + composition edges for a known verb — `sandbar.tools.describe`; (c) searching CORPUS content (memories), not verbs — `sandbar.search.bm25f` against the corpus class.

**HOW:** `:query` is a bag-of-words task intent (e.g. 'rank memories by recency', 'who cites this entity').  Optional `:limit` (default 10).  Optional `:axis` restricts to a verb family (e.g. ':navigate' / ':aggregate' / ':entity').  Returns `{:query :matches [{:verb :ident :title :axis :transition-kind :read-only? :arg-summary :score}...] :total :returned}`.

**ORDER:** leaf-call; the canonical FIRST step of verb discovery.

**COMBINATION:** feed a chosen verb into `sandbar.tools.describe` for the full card + prerequisites + combines-with neighbors, then call the verb itself.

<a id="types-2"></a>

## types

### `sandbar.types.instance-of`

Predicate — is `:entity` an instance of `:class`?. Catalog hint: `[safe]`. Wire name: `sandbar_types_instance-of`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')
- `entity`\* (string) — Entity ident or eid

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    },
    "entity" : {
      "description" : "Entity ident or eid",
      "type" : "string"
    }
  },
  "required" : [ "class", "entity" ],
  "type" : "object"
}
```

**WHICH:** returns boolean true if `:entity` is an instance of `:class` (directly via `:dt/type` OR transitively via `:dt/subclass-of` to an ancestor that's `:dt/type :class`).

**WHEN:** use for dispatch / branching decisions in consumer code — 'if this entity is a :mm/Memory, handle it as a memory; else handle generically'.  When NOT to use: (a) you want the entity's actual class — `sandbar.entity.find` then read `:dt/type`; (b) you want ALL instances of a class — `sandbar.class.instances`; (c) you want polymorphic instance enumeration — `sandbar.class.instances` already includes subclass instances.

**HOW:** `:class` is the candidate class ident; `:entity` is the entity ident or eid.  Returns `{:class :entity :instance-of? <bool>}`.

**ORDER:** no prerequisites; predicate-form leaf call.

**COMBINATION:** pairs with `sandbar.types.subclass-of` (class-level analog: is X a subclass of Y).  For filtering a candidate-set by instance-of relation, use `sandbar.aggregate.count` / `.group-by` with `:where '[[?e :dt/type :foo/X]]'` Datalog clause.

### `sandbar.types.subclass-of`

Predicate — is `:child` a (transitive) subclass of `:parent`?. Catalog hint: `[safe]`. Wire name: `sandbar_types_subclass-of`.

**Arguments** (`*` = required):

- `child`\* (string) — Child class ident
- `parent`\* (string) — Parent class ident

**Complete input schema:**

```json
{
  "properties" : {
    "child" : {
      "description" : "Child class ident",
      "type" : "string"
    },
    "parent" : {
      "description" : "Parent class ident",
      "type" : "string"
    }
  },
  "required" : [ "parent", "child" ],
  "type" : "object"
}
```

**WHICH:** returns boolean true if `:child` class is a transitive `:dt/subclass-of` descendant of `:parent` class — direct OR through any chain of ancestors.

**WHEN:** use for class-hierarchy dispatch logic — 'if this class extends :auth/User, apply auth-flavored behavior'.  Companion to `sandbar.types.instance-of` (entity-level) — this is the class-level equivalent.  When NOT to use: (a) you want the full ancestor list — `sandbar.class.parents`; (b) you want all subclasses — `sandbar.class.subclasses`.

**HOW:** `:parent` + `:child` are class ident strings.  Returns `{:parent :child :subclass-of? <bool>}`.

**ORDER:** no prerequisites.

**COMBINATION:** pairs with `sandbar.types.instance-of` (entity-of-class flavor).

<a id="validation-6"></a>

## validation

### `sandbar.validation.cancel`

Cancel an in-flight validation run. Catalog hint: `[unsafe]`. Wire name: `sandbar_validation_cancel`.

**Arguments** (`*` = required):

- `validation-id`\* (integer) — Validation process eid

**Complete input schema:**

```json
{
  "properties" : {
    "validation-id" : {
      "description" : "Validation process eid",
      "type" : "integer"
    }
  },
  "required" : [ "validation-id" ],
  "type" : "object"
}
```

**WHICH:** cancels a running validation workflow — transitions the process to a `:workflow/terminal-kind :cancel` terminal state.

**WHEN:** use to abort a long-running validation that's no longer needed or that's running against stale data.  When NOT to use: (a) the validation already finished — no-op (or rejected); (b) you want to RETRY after failure — `sandbar.validation.retry`.

**HOW:** `:validation-id` is the numeric process eid.  Returns `{:cancelled <result>}`.

**ORDER:** only meaningful for running processes (use `sandbar.workflow.process-state` to confirm state before cancelling).

**COMBINATION:** pairs with `sandbar.workflow.process-state` (confirm in-flight) and `sandbar.validation.history` (audit cancelled runs).  Cancellation follows a transition to a state with the cancel terminal kind.

### `sandbar.validation.history`

Recent validation runs (all classes or filtered). Catalog hint: `[safe]`. Wire name: `sandbar_validation_history`.

**Arguments** (`*` = required):

- `class` (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns recent validation workflow runs — class, start time, status, terminal-kind.  Optional `:class` filter restricts to one class's history.

**WHEN:** use for substrate-health audits — has class X been validated recently?  Were there failures?  When NOT to use: (a) you want one specific run's results — `sandbar.validation.results`; (b) you want all active runs across the substrate — `sandbar.workflow.active-processes :workflow :validation/Workflow` (workflow-substrate query).

**HOW:** `:class` (optional) is the class-ident filter.  Returns `{:class <ident-or-nil> :history [<run-record>...]}`.

**ORDER:** leaf-call.

**COMBINATION:** pairs with `sandbar.validation.results` (drill into one) and `sandbar.workflow.process-history` (full transition log for one run).

### `sandbar.validation.results`

Fetch the report from a completed validation run. Catalog hint: `[safe]`. Wire name: `sandbar_validation_results`.

**Arguments** (`*` = required):

- `validation-id`\* (integer) — Validation process eid

**Complete input schema:**

```json
{
  "properties" : {
    "validation-id" : {
      "description" : "Validation process eid",
      "type" : "integer"
    }
  },
  "required" : [ "validation-id" ],
  "type" : "object"
}
```

**WHICH:** returns the validation report for a completed run — per-entity validation outcomes (valid / errors).

**WHEN:** use to read the result of a `sandbar.validation.run` after it completes (or to check on a still-running process — partial results may be available).  When NOT to use: (a) you want the high-level state only — `sandbar.workflow.process-state`; (b) you want history of MULTIPLE runs — `sandbar.validation.history`.

**HOW:** `:validation-id` is the numeric process eid.  Returns `{:results <report>}`.

**ORDER:** post-`sandbar.validation.run`.  Calling on an unfinished process returns partial results.

**COMBINATION:** pairs with `sandbar.entity.update` (after reading errors, fix and update affected entities).

### `sandbar.validation.retry`

Re-run a previously-failed validation. Catalog hint: `[unsafe]`. Wire name: `sandbar_validation_retry`.

**Arguments** (`*` = required):

- `validation-id`\* (integer) — Validation process eid (must be in failed terminal state)

**Complete input schema:**

```json
{
  "properties" : {
    "validation-id" : {
      "description" : "Validation process eid (must be in failed terminal state)",
      "type" : "integer"
    }
  },
  "required" : [ "validation-id" ],
  "type" : "object"
}
```

**WHICH:** re-executes a validation workflow that previously reached a `:failure` terminal state.  Useful when the failure was due to transient causes (e.g., stale instances now corrected).

**WHEN:** use after a validation failed and you want to re-run against the (presumably now-valid) instance set.  When NOT to use: (a) the original run succeeded — no-op; (b) you want a FRESH validation — `sandbar.validation.start` (creates a new run).

**HOW:** `:validation-id` is the numeric process eid of the failed run.  Returns `{:retried <result>}`.

**ORDER:** prerequisite — the validation must be in a failed terminal state.

**COMBINATION:** pairs with `.results` (compare retry results to original failure) and `.history` (audit retry chains).

### `sandbar.validation.run`

Execute a previously-started (queued) validation run. Catalog hint: `[unsafe]`. Wire name: `sandbar_validation_run`.

**Arguments** (`*` = required):

- `validation-id`\* (integer) — Validation process eid (from .start return)

**Complete input schema:**

```json
{
  "properties" : {
    "validation-id" : {
      "description" : "Validation process eid (from .start return)",
      "type" : "integer"
    }
  },
  "required" : [ "validation-id" ],
  "type" : "object"
}
```

**WHICH:** executes a validation workflow process that was previously queued via `sandbar.validation.start`.  Advances the process through its state machine (queued → running → terminal).

**WHEN:** use after `sandbar.validation.start` to actually run the queued validation.  The start-then-run two-step lets consumers create-and-queue many validations and execute them later (rate-limiting, scheduling, batch sequencing).  When NOT to use: (a) you haven't created the validation yet — `sandbar.validation.start` first.

**HOW:** `:validation-id` is the numeric process eid returned by `start`.  Returns `{:result <validation-report>}`.

**ORDER:** PREREQUISITE — `sandbar.validation.start` to obtain the validation-id.

**COMBINATION:** pairs with `.cancel` (abort mid-run) + `.results` (post-run report read).

### `sandbar.validation.start`

Start a workflow-backed validation run against all instances of a class. Catalog hint: `[unsafe]`. Wire name: `sandbar_validation_start`.

**Arguments** (`*` = required):

- `class`\* (string) — Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')

**Complete input schema:**

```json
{
  "properties" : {
    "class" : {
      "description" : "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')",
      "type" : "string"
    }
  },
  "required" : [ "class" ],
  "type" : "object"
}
```

**WHICH:** begins a validation workflow — a tracked, cancellable, long-running process that validates every instance of `:class` against its declared constraints.  Returns a validation-id (workflow process eid) used to manage the run.

**WHEN:** use for LARGE class populations where synchronous validation (`sandbar.class.validate-all-instances`) would block too long or where cancellation / history is needed.  Workflow-backed: cancellable mid-run, retriable on failure, history preserved.  When NOT to use: (a) small class population — `sandbar.class.validate-all-instances` is synchronous and simpler; (b) single-entity proposed-slot-map check — `sandbar.entity.validate`.

**HOW:** `:class` is the target class ident.  Returns `{:validation <process-entity>}` with the eid in `:db/id`.

**ORDER:** typical sequence — `sandbar.validation.start` (this verb; create + queue) → `sandbar.validation.run :validation-id <eid>` (execute) → `sandbar.validation.results :validation-id <eid>` (read result).  Mid-flight: `sandbar.validation.cancel` to abort.

**COMBINATION:** pairs with `sandbar.validation.run` (execute), `.cancel` (abort), `.retry` (re-run on failure), `.results` (read), `.history` (recent runs).

<a id="workflow-8"></a>

## workflow

### `sandbar.workflow.active-processes`

All active (non-terminal) workflow processes; optionally filtered by workflow. Catalog hint: `[safe]`. Wire name: `sandbar_workflow_active-processes`.

**Arguments** (`*` = required):

- `projection` (string) — Per-process entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies.
- `workflow` (string) — Optional workflow ident to filter by

**Complete input schema:**

```json
{
  "properties" : {
    "projection" : {
      "description" : "Per-process entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies.",
      "type" : "string"
    },
    "workflow" : {
      "description" : "Optional workflow ident to filter by",
      "type" : "string"
    }
  },
  "required" : [ ],
  "type" : "object"
}
```

**WHICH:** returns the list of currently-active (non-terminal-state) workflow processes — every process that's currently running.  Optional `:workflow` filter restricts to processes against a specific workflow definition.

**WHEN:** use to enumerate live state-machine flows — dashboards, oncall views, 'what's currently in flight'.  When NOT to use: (a) one specific process — `sandbar.workflow.process-state`; (b) finished processes — query via `sandbar.class.instances :class :workflow/Process` + filter terminal states.

**HOW:** `:workflow` (optional) restricts to one workflow definition's processes.  Without it, returns active processes across ALL workflows.  Optional `:projection` — `metadata-only` (default at MCP boundary) or `full`.  Returns `{:workflow <ident-or-nil> :processes [<process-entity-map>...]}`.

**ORDER:** leaf-call.

**COMBINATION:** pairs with `sandbar.workflow.process-state` (drill into one) + `sandbar.workflow.transition` (advance one).

### `sandbar.workflow.define`

Register a new workflow definition (states + transitions). Catalog hint: `[unsafe]`. Wire name: `sandbar_workflow_define`.

**Arguments** (`*` = required):

- `spec`\* (object) — Workflow spec (states + transitions)

**Complete input schema:**

```json
{
  "properties" : {
    "spec" : {
      "description" : "Workflow spec (states + transitions)",
      "type" : "object"
    }
  },
  "required" : [ "spec" ],
  "type" : "object"
}
```

**WHICH:** registers a new workflow definition from a spec.  A workflow is a named state machine — states (with terminal-kind classification: `:success` / `:failure` / `:cancel` for terminal states) + transitions (named actions moving between states, optionally guarded).

**WHEN:** use to introduce a new state-machine model — order fulfillment, validation flow, approval pipeline, etc.  Workflows are entities in the substrate (queryable, evolvable).  When NOT to use: (a) inspecting an existing workflow — `sandbar.workflow.find`; (b) starting a process on an existing workflow — `sandbar.workflow.start-process`.

**HOW:** `:spec` is a JSON object describing the workflow shape — `:workflow/states` vec with `:db/ident` + `:workflow/terminal-kind` (for terminals); `:workflow/transitions` vec with `:db/ident` + source/target state refs + optional guard.

**ORDER:** PRECEDES any `sandbar.workflow.start-process` for this workflow — the workflow must exist before processes can run.  Inspect existing workflows via `sandbar.workflow.find` to avoid duplicate idents.

**COMBINATION:** pairs with `sandbar.workflow.find` (lookup), `sandbar.workflow.start-process` (instantiate process), and the validation-service verbs (`sandbar.validation.*`) which are workflow-backed.  Workflows are visible as `:mm/Workflow` instances via `sandbar.class.instances :class :mm/Workflow`.

### `sandbar.workflow.find`

Look up a workflow definition by ident. Catalog hint: `[safe]`. Wire name: `sandbar_workflow_find`.

**Arguments** (`*` = required):

- `projection` (string) — Definition entity shape — 'full' (default; complete entity-map) or 'metadata-only' (lightweight; :db/id + :db/ident + :dt/type only).
- `workflow`\* (string)

**Complete input schema:**

```json
{
  "properties" : {
    "projection" : {
      "description" : "Definition entity shape — 'full' (default; complete entity-map) or 'metadata-only' (lightweight; :db/id + :db/ident + :dt/type only).",
      "type" : "string"
    },
    "workflow" : {
      "type" : "string"
    }
  },
  "required" : [ "workflow" ],
  "type" : "object"
}
```

**WHICH:** returns the entity-map of a workflow definition (its states + transitions + metadata) given the workflow ident.

**WHEN:** use to inspect an existing workflow — discover its state-machine shape before starting a process or analyzing process histories.  When NOT to use: (a) you want all workflows — `sandbar.class.instances :class :mm/Workflow`; (b) you want process-state inspection — `sandbar.workflow.process-state`.

**HOW:** `:workflow` is the workflow ident string.  Optional `:projection` — `full` (default; single-entity lookup ships the full definition) or `metadata-only` (lightweight existence check).  Returns `{:workflow <ident-string> :definition <entity-map>}`.

**ORDER:** typical sequence — `sandbar.class.instances :class :mm/Workflow` (discover) → `sandbar.workflow.find :workflow :foo/wf` (inspect).

**COMBINATION:** pairs with `sandbar.workflow.start-process` (start a new process against this definition) and `sandbar.workflow.active-processes` (current processes against this workflow).

### `sandbar.workflow.orchestrate`

session-workflow orchestrator — drive a workflow.process through a phase of its ceremony. Catalog hint: `[unsafe]`. Wire name: `sandbar_workflow_orchestrate`.

**Arguments** (`*` = required):

- `actor` (string) — Optional actor ident/eid for the workflow.history actor slot
- `audit-on-open` (boolean) — Optional — invoke audit_fs-substrate-drift in :phase/orient (default false).  Note: wire-format key MUST be `audit-on-open` (no `?` suffix) per Anthropic MCP property-key regex; handler accepts legacy `audit-on-open?` for back-compat.
- `context` (object) — Optional context map passed to phase-work + transition guards/effects
- `phase`\* (string) — Phase keyword — one of :phase/orient :phase/initialize :phase/activate :phase/imprint (open ceremony) OR :phase/capture :phase/author :phase/link :phase/finalize (handoff ceremony)
- `process-id` (integer) — Numeric workflow.process eid
- `reason` (string) — Optional reason string for transitions whose :workflow/requires-reason? is true
- `timeouts` (object) — Optional per-phase timeout override map (else default-phase-timeouts-ms applies)
- `workflow`\* (string) — Workflow definition ident (typically ':workflow/session')

**Complete input schema:**

```json
{
  "properties" : {
    "actor" : {
      "description" : "Optional actor ident/eid for the workflow.history actor slot",
      "type" : "string"
    },
    "audit-on-open" : {
      "description" : "Optional — invoke audit_fs-substrate-drift in :phase/orient (default false).  Note: wire-format key MUST be `audit-on-open` (no `?` suffix) per Anthropic MCP property-key regex; handler accepts legacy `audit-on-open?` for back-compat.",
      "type" : "boolean"
    },
    "context" : {
      "description" : "Optional context map passed to phase-work + transition guards/effects",
      "type" : "object"
    },
    "phase" : {
      "description" : "Phase keyword — one of :phase/orient :phase/initialize :phase/activate :phase/imprint (open ceremony) OR :phase/capture :phase/author :phase/link :phase/finalize (handoff ceremony)",
      "type" : "string"
    },
    "process-id" : {
      "description" : "Numeric workflow.process eid",
      "type" : "integer"
    },
    "reason" : {
      "description" : "Optional reason string for transitions whose :workflow/requires-reason? is true",
      "type" : "string"
    },
    "timeouts" : {
      "description" : "Optional per-phase timeout override map (else default-phase-timeouts-ms applies)",
      "type" : "object"
    },
    "workflow" : {
      "description" : "Workflow definition ident (typically ':workflow/session')",
      "type" : "string"
    }
  },
  "required" : [ "workflow", "phase" ],
  "type" : "object"
}
```

**WHICH:** invokes the session-workflow orchestrator (`sandbar.workflow.orchestrate/orchestrate`) on a workflow.process — drives it through ONE phase of its ceremony per the canonical phase vocabulary.  Returns the phase outcome including the workflow.transition(s) applied, events emitted, duration, and degraded-path flag.

**WHEN:** use to advance a session-lifecycle workflow.process through its phases (orient → initialize → activate → imprint for opening a session; capture → author → link → finalize for handing off a session).  One MCP call per phase — the client iterates over `open-phases` / `handoff-phases` and invokes this verb for each.  When NOT to use: (a) direct workflow.transition application without the orchestrator's phase semantics — use `sandbar.workflow.transition`; (b) inspecting current state — `sandbar.workflow.process-state`; (c) starting a process — `sandbar.workflow.start-process` (this verb assumes the process already exists).

**HOW:** `:workflow` is the workflow definition ident (REQUIRED; typically `:workflow/session`).  `:process-id` is the numeric workflow.process eid (REQUIRED).  `:phase` is the phase keyword (REQUIRED; one of `:phase/orient` / `:phase/initialize` / `:phase/activate` / `:phase/imprint` for open OR `:phase/capture` / `:phase/author` / `:phase/link` / `:phase/finalize` for handoff).  Optional: `:context` (map passed to phase-work + transition guards/effects), `:actor` (ident or eid for workflow.history actor slot), `:reason` (string for transitions whose `:workflow/requires-reason?` is true), `:timeouts` (per-phase override map; falls back to `default-phase-timeouts-ms`), `:audit-on-open` (bool; invoke audit_fs-substrate-drift in :phase/orient — default false).  (Wire-format key MUST be `audit-on-open` — no `?` suffix — to comply with Anthropic MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`.  Handler accepts both `audit-on-open` and legacy `audit-on-open?` for back-compat.)

**ORDER:** PREREQUISITE — workflow.process must exist (created via `sandbar.workflow.start-process`).  Phases SHOULD be invoked in canonical order per ceremony (orient → initialize → activate → imprint).  No strict enforcement — caller MAY skip phases for testing or re-invoke a phase, subject to the underlying workflow.transition guard constraints.

**COMBINATION:** pairs with `sandbar.workflow.start-process` (creates the process this verb drives), `sandbar.workflow.process-state` (read current state between phase calls), `sandbar.workflow.process-history` (audit transitions after orchestrate completes).

Returns: `{:phase-completed :next-phase :transition-applied :events-emitted :duration-ms :degraded? :phase-work-result}` — see the `sandbar.workflow.orchestrate/orchestrate` Clojure fn docstring for slot semantics.

### `sandbar.workflow.process-history`

Full transition history of a workflow process. Catalog hint: `[safe]`. Wire name: `sandbar_workflow_process-history`.

**Arguments** (`*` = required):

- `process-id`\* (integer) — Process eid

**Complete input schema:**

```json
{
  "properties" : {
    "process-id" : {
      "description" : "Process eid",
      "type" : "integer"
    }
  },
  "required" : [ "process-id" ],
  "type" : "object"
}
```

**WHICH:** returns the chronological transition history of a workflow process — every state transition that's been applied, with timestamps, transition idents, and any `:reason` notes.

**WHEN:** use for audit logging, debugging unexpected process states, or rendering a process timeline for UI.  When NOT to use: (a) you only need the CURRENT state — `sandbar.workflow.process-state` (lighter); (b) you want active processes across a workflow — `sandbar.workflow.active-processes`.

**HOW:** `:process-id` is the numeric process eid.  Returns `{:process-id :history [<transition-record>...]}`.

**ORDER:** leaf-call.

**COMBINATION:** pairs with `sandbar.workflow.process-state` (current snapshot).

### `sandbar.workflow.process-state`

Current state + terminal flag + completion flag of a workflow process. Catalog hint: `[safe]`. Wire name: `sandbar_workflow_process-state`.

**Arguments** (`*` = required):

- `process-id`\* (integer) — Process eid

**Complete input schema:**

```json
{
  "properties" : {
    "process-id" : {
      "description" : "Process eid",
      "type" : "integer"
    }
  },
  "required" : [ "process-id" ],
  "type" : "object"
}
```

**WHICH:** returns the current-state ident, terminal-flag (is this a terminal state?), and completion-flag (did this process reach a `:success` terminal?) of a workflow process.

**WHEN:** use to read the live state of a process — for status displays, conditional logic, post-completion handling.  When NOT to use: (a) you want the full transition history — `sandbar.workflow.process-history`; (b) you want to ADVANCE the state — `sandbar.workflow.transition`.

**HOW:** `:process-id` is the numeric process eid.  Returns `{:process-id :state :terminal? :completed?}`.

**ORDER:** leaf-call; no prerequisites beyond knowing the process-id (from `start-process` return or from `sandbar.workflow.active-processes` enumeration).

**COMBINATION:** pairs with `sandbar.workflow.transition` (call after a transition to confirm new state); `sandbar.workflow.process-history` (full log of how we got here).

### `sandbar.workflow.start-process`

Start a new workflow process attached to a subject entity. Catalog hint: `[unsafe]`. Wire name: `sandbar_workflow_start-process`.

**Arguments** (`*` = required):

- `data` (object) — Initial process data (kwargs-merged into initial state)
- `subject`\* (string) — Subject entity ident or eid
- `workflow`\* (string) — Workflow definition ident

**Complete input schema:**

```json
{
  "properties" : {
    "data" : {
      "description" : "Initial process data (kwargs-merged into initial state)",
      "type" : "object"
    },
    "subject" : {
      "description" : "Subject entity ident or eid",
      "type" : "string"
    },
    "workflow" : {
      "description" : "Workflow definition ident",
      "type" : "string"
    }
  },
  "required" : [ "workflow", "subject" ],
  "type" : "object"
}
```

**WHICH:** instantiates a new workflow process — a running instance of a workflow definition — attached to a subject entity (the entity the workflow operates on).  Returns the new process id.

**WHEN:** use to BEGIN a state-machine flow against a target entity — e.g., start an order-fulfillment workflow for a `:order/Order`, start a validation workflow for a `:dt/Class` instance set.  When NOT to use: (a) the workflow definition doesn't exist yet — `sandbar.workflow.define` first; (b) you want to transition an EXISTING process — `sandbar.workflow.transition`.

**HOW:** `:workflow` is the workflow definition ident (REQUIRED).  `:subject` is the subject entity ident or eid (REQUIRED).  `:data` is an optional initial process-data object (kwarg-shaped; merged into the process's initial state).

**ORDER:** PREREQUISITE — workflow defined (via `sandbar.workflow.define` or pre-seed).  After this verb, the process is in its initial state; advance via `sandbar.workflow.transition`.

**COMBINATION:** pairs with `sandbar.workflow.transition` (advance state), `sandbar.workflow.process-state` (current state read), `sandbar.workflow.process-history` (transition log).  MCP Tasks (long-running operations) are workflow processes — task-id IS process-id.

### `sandbar.workflow.transition`

Apply a named transition to a workflow process. Catalog hint: `[unsafe]`. Wire name: `sandbar_workflow_transition`.

**Arguments** (`*` = required):

- `process-id`\* (integer) — Process eid
- `reason` (string) — Optional human-readable reason (carried in history)
- `transition`\* (string) — Transition ident

**Complete input schema:**

```json
{
  "properties" : {
    "process-id" : {
      "description" : "Process eid",
      "type" : "integer"
    },
    "reason" : {
      "description" : "Optional human-readable reason (carried in history)",
      "type" : "string"
    },
    "transition" : {
      "description" : "Transition ident",
      "type" : "string"
    }
  },
  "required" : [ "process-id", "transition" ],
  "type" : "object"
}
```

**WHICH:** advances a workflow process by applying a named transition — moves the process from its current state to the transition's target state (subject to guard validation).  Returns the new state + terminal flag.

**WHEN:** use to advance a process through its state machine — invoke a transition by name.  When NOT to use: (a) just reading the current state — `sandbar.workflow.process-state`; (b) starting a process — `sandbar.workflow.start-process`; (c) cancelling — there's no separate cancel verb; transitions whose target state has `:workflow/terminal-kind :cancel` are the cancellation path.

**HOW:** `:process-id` (REQUIRED) is the numeric eid of the process.  `:transition` is the transition ident (REQUIRED).  `:reason` (optional) is a human-readable rationale carried in the history.

**ORDER:** PREREQUISITE — process started via `sandbar.workflow.start-process`.  Discover the available transitions from the current state via `sandbar.workflow.process-state` + workflow-definition inspection.

**COMBINATION:** pairs with `sandbar.workflow.process-state` (current state read) + `sandbar.workflow.process-history` (history after transitions).  For validation flows specifically, the validation-service verbs (`sandbar.validation.run` etc.) are workflow-backed and call this internally.
