# Quickstart: inspect Sandbar through MCP

This guide takes you from a running Sandbar service to a first useful model query. You will initialize an MCP connection, discover the server's tools, and inspect the vocabulary of a decision. The requests read schema, so they work without importing an example knowledge collection.

## Before you begin

You need a configured Sandbar service, a service-account token that permits the inspection tools, and `curl`. The usual local endpoint is `http://127.0.0.1:8389/mcp`. Use the address and port of your deployment.

For a new service, begin with the [operator guide](../operations.md) and [authentication setup](../auth.md). For a source checkout, use the [development guide](../development.md). Sandbar's repository is [danlentz/sandbar](https://github.com/danlentz/sandbar).

The examples below use a POSIX-compatible shell. Set `SANDBAR_TOKEN` through your deployment's credential setup, then check that it is present without displaying it:

```sh
: "${SANDBAR_TOKEN:?Set SANDBAR_TOKEN to your service-account token}"
SANDBAR_MCP_URL="http://127.0.0.1:8389/mcp"
```

A token establishes the caller. Its permissions and the read surface's exposure rules still determine which operations and data are available.

## Initialize the connection

Send the protocol version and a small client identity:

```sh
curl --silent --show-error --fail-with-body "$SANDBAR_MCP_URL" \
  --header "Authorization: Bearer $SANDBAR_TOKEN" \
  --header 'Content-Type: application/json' \
  --header 'Accept: application/json, text/event-stream;q=0.9' \
  --data-binary @- <<'JSON'
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {"name": "sandbar-quickstart", "version": "1"}
  }
}
JSON
```

Check that the response has a `result` containing `protocolVersion`, `serverInfo`, and `capabilities`. Sandbar's implemented version is `2025-11-25`; continue only if your client supports the returned version. An outer `error` is a failed exchange.

The following helper keeps the same address, token, and protocol headers on subsequent requests. It reads a JSON message from standard input:

```sh
sandbar_mcp() {
  curl --silent --show-error --fail-with-body "$SANDBAR_MCP_URL" \
    --header "Authorization: Bearer $SANDBAR_TOKEN" \
    --header 'Content-Type: application/json' \
    --header 'Accept: application/json, text/event-stream;q=0.9' \
    --header 'MCP-Protocol-Version: 2025-11-25' \
    --data-binary @-
}

sandbar_mcp <<'JSON'
{"jsonrpc":"2.0","method":"notifications/initialized"}
JSON
```

The initialized notification has no request ID and no JSON-RPC response. Sandbar's HTTP handler returns no content for it. The server's ordinary request/response path returns JSON; an application that consumes streaming notifications also needs the corresponding streaming client behavior.

## Discover the operation

Ask for the advertised tools:

```sh
sandbar_mcp <<'JSON'
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
JSON
```

Find `sandbar_class_describe` in `result.tools`. Its `inputSchema` describes the arguments. Use advertised wire names exactly: underscores separate name components, and hyphens within a leaf name remain intact. For example, the grouping tool is `sandbar_aggregate_group-by`.

The catalog contains operations that accept class identifiers. Adding an application class does not create a new family of tools for that class.

## Inspect a decision

Describe the built-in decision class:

```sh
sandbar_mcp <<'JSON'
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "sandbar_class_describe",
    "arguments": {"class": ":mm/Decision"}
  }
}
JSON
```

A successful tool result contains the class description, including its effective slots. Those slots include properties inherited from the memory hierarchy. You have inspected both an application concept and a consequence of Sandbar's inference rules.

Tool calls have an additional failure boundary. Check the outer JSON-RPC `error`, then `result.isError`. Only after both checks should you read `result.structuredContent`, when supplied, or parse the JSON payload in the text content. A valid JSON error payload is still an error.

To request just the effective slots:

```sh
sandbar_mcp <<'JSON'
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "sandbar_class_slots",
    "arguments": {"class": ":mm/Decision"}
  }
}
JSON
```

## Diagnose a failed first request

| Observation | Check |
| --- | --- |
| Connection refused | The process, listening address, and configured HTTP port |
| HTTP 401 | The token and the service account that issued it |
| An authorization refusal | Whether that account can call the requested operation |
| Unknown method or tool | The negotiated protocol and the exact name advertised by `tools/list` |
| A tool error despite successful HTTP | `result.isError` and the tool's returned error details |
| A class or property is absent | The loaded schema and the read surface's exposure policy |

For configuration problems, the primary deployment override is `<client-directory>/.sandbar/config.edn`. The relevant keys are `:port` and `:db {:url ... :sid ...}`. Environment overrides include `SANDBAR_PORT`, `SANDBAR_DB_URL`, and `SANDBAR_DB_SID`. Set `SANDBAR_CLIENT_DIR` explicitly for the deployment you intend to operate; see the [operator guide](../operations.md) for precedence and lifecycle commands.

## Continue with a useful task

- [Getting started](getting-started.md) builds and retrieves a small collection.
- [The metamodel example](../concepts/metamodel.md#a-class-extension-in-practice) introduces a specialized decision class.
- [Writing an MCP client](writing-an-mcp-client.md) develops response handling and client integration.
- [Zorp's Galactic Footwear Emporium](zorp-tutorial.md) teaches modeling through a complete application.

For the conceptual account, read [Sandbar's MCP surface](../concepts/mcp-protocol.md). The [MCP lifecycle specification](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle) defines the initialization sequence.
