# Writing an MCP client

An MCP client can discover Sandbar's operations, inspect the model, and retrieve or change typed knowledge without embedding the database. This guide builds a small shell client to make the exchange visible, then explains the contracts a longer-lived client must preserve.

## What you'll need

Use the endpoint and service-account token supplied by the operator. The usual local endpoint is `http://127.0.0.1:8389/mcp`. The token must have permission for the operations you intend to call; successful authentication alone does not grant that permission. See [authentication](../auth.md) for account setup.

The examples use `curl`, a POSIX-compatible shell, and `jq` for inspecting tool results. Set the token through your credential setup, without putting its value in source code:

```sh
: "${SANDBAR_TOKEN:?Set SANDBAR_TOKEN to your service-account token}"
SANDBAR_MCP_URL="http://127.0.0.1:8389/mcp"

sandbar_http() {
  curl --silent --show-error --fail-with-body "$SANDBAR_MCP_URL" \
    --header "Authorization: Bearer $SANDBAR_TOKEN" \
    --header 'Content-Type: application/json' \
    --header 'Accept: application/json, text/event-stream;q=0.9' \
    --data-binary @- "$@"
}
```

This helper sends one JSON-RPC message from standard input. Both response media types are accepted, with JSON preferred for these shell examples. A general client must also handle an SSE response according to its `Content-Type`. [MCP HTTP transport](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).

## Initialization handshake

Initialize before calling tools:

```sh
sandbar_http <<'JSON'
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "initialize",
  "params": {
    "protocolVersion": "2025-11-25",
    "capabilities": {},
    "clientInfo": {"name": "sandbar-example", "version": "1"}
  }
}
JSON
```

Check the outer response for an `error`. On success, inspect `result.protocolVersion`, `result.capabilities`, and `result.serverInfo`. Continue only if the returned protocol version is supported by your client. The current Sandbar implementation returns `2025-11-25`. Capabilities describe the optional protocol facilities available on this connection; do not infer them from a server version string. [MCP lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle).

After checking that response, retain the returned version and send the initialized notification:

```sh
SANDBAR_PROTOCOL_VERSION="2025-11-25"

sandbar_mcp() {
  sandbar_http --header "MCP-Protocol-Version: $SANDBAR_PROTOCOL_VERSION"
}

sandbar_mcp <<'JSON'
{"jsonrpc":"2.0","method":"notifications/initialized"}
JSON
```

A notification has no `id` and no JSON-RPC response to parse. The current Sandbar handler returns HTTP **204 with an empty body**; the protocol's specified acknowledgment is **202 with an empty body**. The client should distinguish this empty acknowledgment from a request that requires a response. Notification status alignment is part of the 0.2.0 transport contract.

Send the negotiated `MCP-Protocol-Version` on subsequent HTTP requests. If a server issues an `MCP-Session-Id`, retain and return it too. Sandbar's current handler does not issue that session header. Each request needs its own ID so that a client can associate responses with outstanding work.

## Discovering tools

```sh
sandbar_mcp <<'JSON'
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
JSON
```

Use the returned `result.tools` names and `inputSchema` values. Do not hard-code a tool count or translate a displayed host function name back into a guessed wire name.

| Catalog name | MCP wire name |
| --- | --- |
| `sandbar.class.describe` | `sandbar_class_describe` |
| `sandbar.entity.find-by-rel-path` | `sandbar_entity_find-by-rel-path` |
| `sandbar.aggregate.group-by` | `sandbar_aggregate_group-by` |

Underscores separate catalog components; hyphens within a component stay intact. Model names such as `:mm/Decision` are arguments to these operations. Adding a model class makes it available to the existing class operations; it does not generate a new family of tools.

`sandbar_tools_search` helps find a relevant operation, and `sandbar_tools_describe` explains a known operation. The latter's `verb` argument takes a catalog reference such as `"sandbar.entity.find"`, even though the enclosing `tools/call` uses the underscore wire name `sandbar_tools_describe`. Use their advertised argument schemas. The [verb reference](../api/mcp-verbs.md) is useful for browsing; the running server establishes what this connection can call.

## Calling a tool

Inspect the decision class:

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

This reads schema, so it does not depend on an imported example collection. The result describes the class and its effective slots, including inherited properties.

When looking up a known entity, ask for `"projection": "full"` if you need its body. Compact discovery results are useful for choosing a record; they are not a substitute for reading it. The [getting-started guide](getting-started.md) creates a small observation and reads it back by its actual relative path.

## Error handling

Treat success as a sequence of checks:

1. Check the HTTP status and response media type. A connection failure is not an empty search result.
2. Check the outer JSON-RPC `error` and match the response ID to the request.
3. For `tools/call`, check `result.isError` before using its content.
4. Use `result.structuredContent` when present. For Sandbar tools returning JSON in a text content block, parse that JSON as the fallback.

For the JSON tool responses in this guide, this helper makes failures visible:

```sh
sandbar_tool_payload() {
  jq -e '
    if .error then error(.error | tojson)
    elif .result.isError == true then error(.result | tojson)
    elif .result.structuredContent != null then .result.structuredContent
    else ([.result.content[]? | select(.type == "text")][0].text | fromjson)
    end'
}

sandbar_mcp <<'JSON' | sandbar_tool_payload
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"sandbar_class_slots","arguments":{"class":":mm/Decision"}}}
JSON
```

The helper is for tool results, not initialization, resource results, or SSE framing. Preserve the complete error envelope in application diagnostics, with credentials and sensitive content redacted. An exact lookup may return a successful payload saying the entity is missing; interpret that according to the operation's contract.

After a lost response to a write, reconcile the intended entity or operation before retrying. A timeout does not establish that the write failed, and a JSON-RPC request ID is not a general idempotency key.

Explicit slot writes refuse keyword spellings that cannot be read back as the same EDN keyword. This includes reference identities supplied as strings or in a `{"db/ident": "..."}` map. For this refusal, `result.isError` is true and the tool payload names the offending slot, value and, for a list, member index. No part of that request is written; correct the value deliberately and resubmit. `entity.validate` applies the same check without writing. Unicode and punctuation that survive the reader are allowed; this is not a rule to strip punctuation or guess a replacement identity.

## Listing and reading resources

Resources provide URI-addressed content. Discover the URI instead of constructing it from a filename:

```sh
sandbar_mcp <<'JSON'
{"jsonrpc":"2.0","id":5,"method":"resources/list","params":{}}
JSON
```

Choose a returned `uri` and send `resources/read` with `params: {"uri": "<the advertised URI>"}`. Its success payload is `result.contents`, whose entries carry resource content and media information; it is not a `tools/call` content envelope. Authorization still applies to the read. Prompts similarly have their own `prompts/list` and `prompts/get` result shapes.

## Subscribing to resource updates

Sandbar currently provides a separate notification stream at `GET /mcp/sse`. Its first event is `notifications/sandbar/sse-ready`, with a `params["subscriber-id"]`. A subscription request then supplies `resources/subscribe` with the resource's `uri` and that value as **`subscriberId`**. Subscription requests are ordinary POST exchanges; their response is not the ongoing event stream.

This endpoint and subscriber identifier are Sandbar-specific transport behavior. They should not be presented as the standard Streamable HTTP session mechanism. Use a client adapter that explicitly supports this deployment's behavior; the 0.2.0 transport work must establish the supported standard transport before claiming generic streaming-client compatibility.

Treat an update notification as a reason to refresh an authorized resource. It does not replace the resource body or prove that every intermediate change was delivered. Reconnect and refresh deliberately after a broken stream, and unsubscribe when the view no longer needs updates.

## Long-running operations

Some tools start workflow processes. Follow the tool's returned process identity and documented observation contract; a returned process is different from a completed business operation. See [workflows](../concepts/workflow-substrate.md).

The current server also implements `tasks/get`, `tasks/list` and `tasks/cancel` compatibility methods; `tasks/get` and `tasks/cancel` use a camel-case `taskId`. Their presence does not establish support for the standard MCP Tasks feature: the initialization capabilities do not currently advertise it. Clients should require the appropriate capability and result contract before using a portable Tasks implementation.

## Permissions and client policy

Keep three decisions separate. Server authentication establishes the principal, server roles and permissions govern operations, and disclosure policy governs the data the principal may receive. A host's allowed-tool list adds a client-side restriction; it does not change the server account's role or establish a project-data boundary.

Tool annotations are planning hints. Confirm an operation's actual effects before offering it as a harmless preview: an export, for example, can write files. Use [authentication](../auth.md) and [projects and boundaries](../firewall-and-projects.md) when designing access, and the [MCP concept](../concepts/mcp-protocol.md) for the relationship between discovery and model semantics.
