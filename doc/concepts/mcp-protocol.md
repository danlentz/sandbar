# Sandbar's MCP Surface

> Sandbar exposes its metamodel through the Model Context Protocol (MCP) — an Anthropic-originated JSON-RPC 2.0 protocol designed for AI clients consuming structured services.  This document explains how Sandbar's MCP surface bootstraps from the metamodel (`tools/list` walks `dt/all-classes`), why the verb catalog is *operational* (one verb per operation kind) rather than *per-class* (one verb per class), and how MCP Tasks compose with Sandbar's workflow substrate.  For the mechanical verb catalog see [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md); for client patterns see [`doc/guides/writing-an-mcp-client.md`](../guides/writing-an-mcp-client.md).

## Thesis

MCP is a protocol surface designed for AI clients that want to reflect over a service before invoking it.  Its discovery primitives — `tools/list`, `resources/list`, `prompts/list` — return rich JSON Schema fragments that let an AI client understand the available operations without out-of-band documentation.

Sandbar's commitment: **the MCP surface is a function of the metamodel state**.  No hand-curated tool registry.  No mapping table between MCP names and Sandbar internals.  Adding a class to the metamodel adds the class to the MCP surface; removing a class removes it; modifying a slot changes the JSON Schema fragment that `tools/list` reports.

This is *bootstrap-by-discovery*.  The MCP surface and the metamodel evolve together because they are the same surface.

## Lineage

### Model Context Protocol (Anthropic, 2024)

MCP (Anthropic 2024) is the AI-client-facing protocol Sandbar targets.  Its design borrows from Language Server Protocol (Microsoft 2016) — both use JSON-RPC 2.0 (Bauer 2010) over a long-lived stream — but adapts it for AI consumers rather than text editors.  The three primary surfaces are:

- **Tools** — invocable operations with JSON Schema-described inputs and outputs.  An AI client examines `tools/list`, plans which tool to invoke, calls `tools/call`, and reads the result.
- **Resources** — addressable read-only content.  An AI client examines `resources/list`, fetches via `resources/read uri`, optionally subscribes to changes.
- **Prompts** — parameterizable prompt templates.  An AI client examines `prompts/list`, fetches a specific prompt with arguments, and uses the result as context.

The protocol's discovery primitives are heavily JSON-Schema-shaped — every tool, every resource, every prompt carries a schema fragment that describes its interface.

### JSON-RPC 2.0

JSON-RPC 2.0 (Bauer et al, 2010) is the wire envelope: numbered requests, paired responses, error codes from a defined enumeration (`-32600` to `-32699` reserved for protocol-level errors; `-32000` to `-32099` reserved for application-level).  Sandbar conforms strictly — every MCP message is a JSON-RPC envelope; every error is a numbered code with a structured `:data` payload.

### Server-Sent Events (SSE)

MCP's transport for long-lived connections is the *Streamable HTTP* binding: an HTTP POST establishes the request channel; a server-pushed SSE stream (Hickson 2009; HTML Living Standard) carries notifications back to the client.  Sandbar's MCP transport is implemented on top of Pedestal's chunked-streaming response, with SSE framing.

### Language Server Protocol (sibling)

LSP (Microsoft 2016–) is the closest sibling protocol — designed for IDE clients consuming language analysis services.  LSP's discovery primitives (`textDocument/hover`, `workspace/symbol`) are method-style rather than tool-style, but the architectural intent matches: the server reflects its capability surface; the client adapts.  MCP's tools/resources/prompts taxonomy is roughly equivalent to LSP's request/notification distinction adapted for the AI-consumer use case.

## The verb catalog — operational, not per-class

A naive bootstrap would emit one tool per class: `sandbar.order.create`, `sandbar.order.find`, `sandbar.order.update`, … `sandbar.user.create`, `sandbar.user.find`, `sandbar.user.update`, …  This is the *per-class* shape.

Sandbar deliberately rejects this for an *operational verb catalog* — one verb per *operation kind*, parameterized by class.  The catalog (declared in `sandbar.mcp.tools`):

| Group         | Verbs                                                                                  |
|---------------|----------------------------------------------------------------------------------------|
| `schema.*`    | `classes`, `properties`, `datatypes`, `entities` (batch)                              |
| `class.*`     | `describe`, `slots`, `direct-slots`, `required-slots`, `instances`, `subclasses`, `parents`, `validate-all-instances` |
| `types.*`     | `instance-of`, `subclass-of`                                                          |
| `property.*`  | `domain`, `range`, `cardinality`                                                      |
| `entity.*`    | `create`, `find`, `update`, `validate`                                                |
| `workflow.*`  | `define`, `find`, `start-process`, `transition`, `process-state`, `process-history`, `active-processes` |
| `validation.*`| `start`, `run`, `cancel`, `retry`, `results`, `history`                              |
| `codec.*`     | `list`                                                                                |
| `project.*`   | `export`, `import`                                                                    |

A consumer calling `sandbar.entity.create` provides `{class, format, source}` (or `{class, attributes}`) — the class is an argument, not part of the verb name.  This is the resolution recorded in [`decisions/sandbar_mcp_tool_surface_resolution_operational_verb_catalog_per_adr_b13_2026_05_12`](../../memory/decisions/sandbar_mcp_tool_surface_resolution_operational_verb_catalog_per_adr_b13_2026_05_12.md) — captured as F-B-001's design decision.

### Why operational, not per-class

1. **Catalog stability.**  A per-class catalog grows linearly with the number of classes.  Adding a domain class adds N new tools.  AI clients with limited tool budgets would have to enumerate or filter.  An operational catalog grows in O(1) with respect to class count.

2. **Schema-as-argument vs schema-as-name.**  When the class is an argument, the JSON Schema for `sandbar.entity.create.arguments.class` reflects `dt/all-classes` and the operation's per-class shape is reflected from `dt/range-of` on the class's slots.  The schema is *runtime-computable* rather than statically baked into the catalog.

3. **Composition with reflection.**  A consumer that wants to discover *what classes exist* uses `sandbar.schema.classes`; a consumer that wants to *operate on a class* uses `sandbar.entity.create class=...`.  The two compose: walk the classes, decide which to act on, call the operational verb.  Per-class catalogs force the same composition implicitly but with less visibility.

4. **Matches the metamodel's shape.**  The metamodel itself is "Classes have slots; slots have ranges; operations are kinds applied to a class."  The verb catalog mirrors this structure.

### When per-class verbs would be right

The operational shape is right when operations are *uniform across classes* — every class can be created, found, updated, validated.  When operations are *class-specific* (e.g., `sandbar.order.refund`, which only makes sense for `:order/Order`), the per-class shape returns.  Sandbar's substrate verbs are uniform; domain extensions may add per-class verbs as needed.

## Bootstrap-by-discovery

The flow:

```
Client                          Sandbar
  |                               |
  |--- initialize -------------> |
  |                               |  (auth check; protocol version)
  |<-- initialize result -------- |
  |                               |
  |--- tools/list ------------->  |
  |                               |  walk verb-catalog
  |                               |  for each verb, reflect JSON Schema
  |                               |   from class slots if class-parameterized
  |<-- tools list ---------------|
  |                               |
  |--- resources/list --------->  |
  |                               |  walk dt/all-classes with :dt/native-codec
  |                               |  for each instance, derive uri + mime
  |<-- resources list -----------|
  |                               |
  |--- tools/call entity.create  |
  |    {class, source, format}-> |
  |                               |  resolve class; route to codec
  |                               |  parse + dt/make; transact
  |<-- result -------------------|
```

The key moves:

1. **`tools/list` is computed at request time.**  No precomputed registry; the response reflects the live metamodel.
2. **JSON Schema is reflected from `:dt/range`.**  For each slot on a relevant class, the schema fragment is computed via `tools/datomic-type->json-schema`.  Datomic value-types map to canonical JSON Schema (`:db.type/long` → `"integer"`; `:db.type/instant` → `{type: string, format: date-time}`; etc.).
3. **`resources/list` walks classes with a codec declared.**  Any class with `:dt/native-codec` is exposed as a resource collection; instances are walked, URIs derived, MIME types computed from the codec.
4. **`tools/call` routes through the codec mediator when `:format` is present.**  See [`codec-layer.md`](codec-layer.md) for the mediator design.

## MCP Tasks composition

Long-running tools (`sandbar.validation.start`, `sandbar.workflow.start-process`, etc.) return a *task* envelope — a typed Process from Sandbar's workflow substrate:

```json
{
  "task-id": "12345",
  "status": "pending",
  "kind": null
}
```

The MCP client uses `tasks/get task-id` to poll status; `tasks/cancel task-id` to abort.  Status transitions are driven by the underlying workflow process — when the process reaches a terminal state, the task's `status` becomes `complete` and `kind` becomes one of `:success` / `:failure` / `:cancel`.

The composition is direct:

- `task-id` IS `:db/id` of the `:workflow/Process` entity.
- `status` is a projection of `:workflow/current-state`.
- `kind` is the state's `:workflow/terminal-kind`.

No parallel registry.  No mapping.  See [`workflow-substrate.md`](workflow-substrate.md) for the full discipline.

## Resource subscriptions

MCP supports *resource subscriptions* — a client subscribes to a URI; the server pushes update notifications when the resource changes.  Sandbar implements this via:

1. The MCP `resources/subscribe` handler records the subscription against the URI.
2. On entity transactions (via the `dt/*` API), `sandbar.mcp.resources/entity-updated!` fires.
3. The notification routes to subscribed clients over their SSE channel.

Subscriptions are **per-session**, not per-client-instance — when the SSE connection closes, the subscriptions associated with that session are cleaned up.

## Authentication

Sandbar's MCP transport accepts a Bearer token (`Authorization: Bearer <token>`).  Tokens are verified against service-account records using buddy-hashers (BCrypt-shaped); see [`auth.md`](../auth.md) for the auth scheme details.

Failed auth produces a JSON-RPC error with the standard `-32000` application-error code; the response carries no internal-state details.

A planned future is to model service-account issuance as a workflow process — see `ideas/service_account_issuance_rotation_should_be_first_class_workflow` — which would integrate auth lifecycle with the workflow substrate.

## Notifications

MCP supports server-pushed notifications for several event types:

| Notification                       | Trigger                                                |
|------------------------------------|--------------------------------------------------------|
| `notifications/initialized`        | Connection setup complete                              |
| `notifications/tools/list_changed` | Tool catalog changed (e.g., new class added)           |
| `notifications/resources/list_changed` | Resource catalog changed                            |
| `notifications/resources/updated`  | A specific subscribed resource changed                 |
| `notifications/tasks/status`       | A task transitioned to a new status                    |

Notifications are JSON-RPC notification envelopes (no `id` field) delivered over the SSE channel.  Subscribing clients receive them in real time; non-subscribing clients see only request/response.

## Wire shape — concrete

A minimal `tools/list` exchange:

```http
POST /mcp HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json

{"jsonrpc":"2.0","id":1,"method":"tools/list"}
```

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "tools": [
      {
        "name": "sandbar.entity.create",
        "title": "Create entity",
        "description": "Create a new entity of the given class.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "class": {"type": "string"},
            "format": {"type": "string", "enum": ["markdown", "json"]},
            "source": {"type": "string"},
            "attributes": {"type": "object"}
          },
          "required": ["class"],
          "oneOf": [{"required": ["source", "format"]}, {"required": ["attributes"]}]
        }
      },
      ...
    ]
  }
}
```

A `tools/call` for entity creation:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "sandbar.entity.create",
    "arguments": {
      "class": "mm/Memory",
      "format": "markdown",
      "source": "---\nname: Foo\n---\n# Context\n..."
    }
  }
}
```

The result envelope wraps the payload in the MCP `content` array as required by spec:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [{"type": "text", "text": "{\"entity-id\":12345,...}"}]
  }
}
```

This wrapping is a spec compliance discipline — see Stage G Signal 9 (the wrap-vs-unwrap asymmetry that surfaced during corpus migration).  Clients that don't unwrap will receive the inner JSON as a string; the correct read is to unwrap the `content` array and parse the inner JSON.

## Comparison with adjacent protocols

### vs. REST

REST clients enumerate URIs; MCP clients enumerate operations.  REST is well-suited to resource-oriented consumers (browsers, mobile apps); MCP is well-suited to reflection-oriented consumers (AI agents that compose operations dynamically).  Sandbar exposes both — see [`doc/api/http-rest.md`](../api/http-rest.md) for the REST surface — projected from the same metamodel.

### vs. GraphQL

GraphQL clients submit ad-hoc queries against a typed schema.  MCP clients invoke predeclared operations with typed arguments.  GraphQL is more flexible at the query-shape level; MCP is more predictable at the operation level.  Both are reflection-oriented; both could project from Sandbar's metamodel.  Sandbar implements MCP first because the consumer (AI agents) better matches MCP's invocation model.

### vs. gRPC

gRPC uses Protocol Buffers for IDL and HTTP/2 for transport.  Schema is compiled into client stubs; reflection is a separate gRPC reflection service.  MCP uses JSON Schema with live runtime reflection; no client stubs.  The trade-off is tooling (gRPC has rich tooling and codegen; MCP is younger and tooling is emerging) versus runtime adaptability (MCP can describe a new operation without a code change in the client).

### vs. LSP

LSP and MCP are sibling protocols for different consumer kinds.  LSP serves editor clients with text-document-shaped operations; MCP serves AI clients with arbitrary tool-shaped operations.  Both use JSON-RPC 2.0; both rely on dynamic capability negotiation.  Both have a similar shape — `tools/list` is to MCP what `capabilities` are to LSP.

## References

**Model Context Protocol**

- Anthropic (2024–). *Model Context Protocol Specification.*  https://modelcontextprotocol.io/
- Anthropic (2024–). *MCP Concepts — Tools, Resources, Prompts.*  https://modelcontextprotocol.io/docs/concepts/

**JSON-RPC**

- Bauer, A. & contributors (2010). *JSON-RPC 2.0 Specification.*  https://www.jsonrpc.org/specification

**Server-Sent Events**

- Hickson, I. (2009). *Server-Sent Events — HTML Living Standard.*  https://html.spec.whatwg.org/multipage/server-sent-events.html

**Language Server Protocol (sibling)**

- Microsoft (2016–). *Language Server Protocol Specification.*  https://microsoft.github.io/language-server-protocol/

**JSON Schema**

- Wright, A., Andrews, H., Hutton, B. & Dennis, G. (2020–). *JSON Schema 2020-12.*  https://json-schema.org/draft/2020-12/

**Pedestal architecture (Clojure HTTP server)**

- Cognitect (2013–). *Pedestal — A Clojure-based platform for building services.*  https://pedestal.io/

## See also

- [`metamodel.md`](metamodel.md) — what the MCP surface bootstraps from
- [`codec-layer.md`](codec-layer.md) — how MCP `tools/call` routes through codecs
- [`workflow-substrate.md`](workflow-substrate.md) — how MCP Tasks compose with workflow processes
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) — every MCP verb's mechanical reference
- [`doc/guides/writing-an-mcp-client.md`](../guides/writing-an-mcp-client.md) — hands-on client patterns
