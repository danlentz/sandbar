# Sandbar's MCP surface

> A client needs to discover both what it can do and what the data means. Sandbar exposes operational tools alongside an inspectable model, so those two kinds of discovery can work together.

## Thesis

A useful client should be able to approach an unfamiliar knowledge collection in stages: establish a connection, discover the available operations, inspect the relevant classes, and retrieve enough context to act. Sandbar's MCP surface supports that sequence through stable verbs whose arguments refer to the live model.

The operation catalog and the application schema change for different reasons. A new decision class extends the model; the existing class-inspection and entity tools can work with it. A new kind of operation requires a tool implementation and a catalog entry. Keeping those distinctions explicit makes discovery manageable as the knowledge model grows.

## Two complementary kinds of discovery

`tools/list` returns Sandbar's operational catalog, including input schemas and tool annotations. Its entries describe operations such as inspecting a class, finding an entity, searching, counting, or following relationships. The catalog is authored with the implementation.

Schema discovery happens through tools in that catalog:

| Step | Tool | Question |
| --- | --- | --- |
| Find the vocabulary | `sandbar_schema_classes` | Which classes are available? |
| Understand a class | `sandbar_class_describe` | What are its parents, descendants, and effective slots? |
| Inspect a property | `sandbar_property_range` and related tools | What values does this slot accept? |
| Find instances | `sandbar_class_instances` | Which entities belong to this class? |
| Read one entity | `sandbar_entity_find` | What does this known entity contain? |

Class membership and effective slots use the metamodel's inference rules. That is why discovering a specialized class can reveal inherited properties, and why broader class queries can include specialized instances. See [Inference and entailment](rdfs-entailment.md) for the precise boundaries.

Sandbar also provides `sandbar_tools_search` and `sandbar_tools_describe` to help select a tool and inspect its usage and composition information. These are ordinary tools exposed by the server. They are useful after protocol discovery has established which names and schemas are actually available.

## Connection lifecycle

Sandbar's implemented protocol version is `2025-11-25`. A client begins with `initialize`, checks the returned version and capabilities, then sends `notifications/initialized` before ordinary operation. The server advertises its identity, capabilities, and a short orientation in the initialization result. Use the negotiated capabilities when deciding which optional operations to call. [MCP lifecycle specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle).

This is the logical sequence; the [client guide](../guides/writing-an-mcp-client.md) supplies the transport and authentication details:

```text
client → initialize
server → protocol version, capabilities, server information
client → notifications/initialized
client → tools/list
server → tool names, descriptions, input schemas, annotations
client → tools/call: inspect a class
client → tools/call: retrieve or operate on its instances
```

The normal HTTP endpoint is `/mcp` on the configured server port. An authenticated connection carries the service-account token in the `Authorization` header. Authentication establishes the caller; authorization still determines which operations and data that caller can use.

## Wire names and model names

Use the tool name returned by `tools/list`. Sandbar's wire names use underscores between the catalog's dotted components, while hyphens within an operation name remain intact:

| Catalog name | Wire tool name |
| --- | --- |
| `sandbar.entity.find` | `sandbar_entity_find` |
| `sandbar.class.describe` | `sandbar_class_describe` |
| `sandbar.aggregate.group-by` | `sandbar_aggregate_group-by` |
| `sandbar.navigate.path-via` | `sandbar_navigate_path-via` |

An MCP host may display or normalize these names further in its own programming interface. The server's advertised wire name is the one to use in a raw `tools/call` request.

Model identifiers are arguments. For example, `":mm/Decision"` identifies a class; it is not a tool name. Use namespaced slot names in JSON objects when supplying attributes so that their meaning is unambiguous.

## Inspect, then read

After initializing a connection, inspect the decision class:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "sandbar_class_describe",
    "arguments": {"class": ":mm/Decision"}
  }
}
```

For the fictional entity created in the [metamodel example](metamodel.md), request its complete contents explicitly:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "sandbar_entity_find",
    "arguments": {
      "ident": ":memory.examples/cache-refresh",
      "projection": "full"
    }
  }
}
```

Discovery and search often need a compact result. A decision about the content usually needs a full read. Projection options belong to each tool's contract; inspect that contract instead of assuming every operation returns the same default shape.

For larger collections, choose a question before enumerating everything. Search finds relevant content, aggregation describes a population, navigation follows relationships, and orientation produces a useful overview. The [retrieval map](../../README.md#retrieval-starts-with-the-question) helps select among them.

<a id="wire-shape--concrete"></a>

## Responses have two levels of success

JSON-RPC supplies the outer response envelope. MCP tool results carry their content inside `result`, and a tool can report failure with `result.isError` even when the surrounding protocol exchange succeeded.

A client should:

1. Check transport status and the outer JSON-RPC `error` field.
2. Check `result.isError` before interpreting tool content as successful data.
3. Consume `structuredContent` when supplied; otherwise parse the JSON in the text content for tools that return a JSON payload.
4. Interpret an empty or missing result according to that tool's contract.

An error payload can be perfectly valid JSON. Parsing it successfully does not turn it into a successful query with no matches. This distinction matters for writes as well as reads: a client should preserve the server's failure information rather than continue on a guessed success path.

## Tools, resources, and prompts

Tools perform named operations. Resources provide URI-addressed content through the resource methods the server advertises. Prompts supply templates for a client to use. These protocol surfaces serve different purposes, even when they refer to the same underlying entities.

Use the URI returned by `resources/list` when reading a resource. Its class component must match the resolved entity; a mismatch receives the same not-found response as an absent or inaccessible resource. The catalog's MIME type describes the class's declared default. Use the MIME type in `resources/read` for the returned content: it follows the representation actually produced, including `application/edn` when rendering falls back to EDN. A custom codec without a declared MIME type leaves that optional field absent.

A model change does not necessarily change the tool list. An entity change may affect a resource's content without adding a new operation. Clients should respond to the notification type they actually receive and use the relevant method to refresh their view.

Some work also has an execution lifecycle, represented by Sandbar's workflow and task facilities. Discovery and the operation's result contract determine how a client observes or cancels it. The [workflow chapter](workflow-substrate.md) explains the underlying model; the reference specifies the supported protocol methods and payloads.

## A tool description is useful evidence, not a permission grant

Tool annotations help a client plan calls, but permissions come from enforcement. A tool described as read-only with respect to the database can still produce output elsewhere; an export is an important example. The caller also needs the operation's effect and disclosure contract.

Likewise, access to a schema or a tool does not establish permission to receive every entity. Project scope, resource access, and export policy have distinct responsibilities. See [authentication](../auth.md) and [projects and boundaries](../firewall-and-projects.md) for those contracts.

## Where to go next

- [Write an MCP client](../guides/writing-an-mcp-client.md): initialization, transport, authentication, calls, and errors.
- [MCP verb reference](../api/mcp-verbs.md): exact inputs and results from the catalog.
- [MCP composition map](../mcp-affordance-map.md): relationships among operations.
- [Metamodel](metamodel.md): the vocabulary exposed through inspection.
- [Memory model](memory-model.md): the knowledge an application stores and retrieves.

Protocol reference: [Model Context Protocol, 2025-11-25](https://modelcontextprotocol.io/specification/2025-11-25).
