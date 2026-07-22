# Writing an MCP Client

> How to connect Claude (or any MCP-capable AI client) to Sandbar — initialize handshake, discover tools, invoke them, read resources, subscribe to updates, work with long-running Tasks.  This is a *client-side* guide; for the *server-side* design see [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md); for the full verb catalog see [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md).

## What you'll need

- A running Sandbar instance (see [`quickstart.md`](quickstart.md)).
- A service-account bearer token — see [`auth.md`](../auth.md) for issuance.
- An HTTP client capable of:
  - POSTing JSON
  - reading a Server-Sent Events stream (for notifications + subscriptions)

The transport is *Streamable HTTP* — POST opens the request channel; the server's chunked-streaming response delivers SSE-framed notifications back.  Single endpoint: `/mcp`.

## The protocol envelope

Every message is a JSON-RPC 2.0 envelope:

```json
{"jsonrpc": "2.0", "id": <number-or-string>, "method": "<method-name>", "params": {...}}
```

Responses pair via `id`:

```json
{"jsonrpc": "2.0", "id": <same>, "result": {...}}
{"jsonrpc": "2.0", "id": <same>, "error": {"code": <number>, "message": "...", "data": {...}}}
```

Notifications omit `id` (server pushes; no client response):

```json
{"jsonrpc": "2.0", "method": "notifications/tasks/status", "params": {...}}
```

## Initialization handshake

The first call on any new session is `initialize`:

```bash
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-11-25",
      "clientInfo": {"name": "my-client", "version": "0.2.0"},
      "capabilities": {}
    }
  }'
```

Response:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-11-25",
    "serverInfo": {"name": "sandbar", "version": "0.2.0"},
    "capabilities": {
      "tools": {},
      "resources": {"subscribe": true},
      "prompts": {},
      "tasks": {}
    }
  }
}
```

After the response, send `notifications/initialized` (a notification, no `id`):

```json
{"jsonrpc": "2.0", "method": "notifications/initialized"}
```

The session is now ready.

## Discovering tools

```bash
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
```

Response contains the operational verb catalog with input schemas:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "sandbar_schema_classes",
        "title": "List classes",
        "description": "Enumerate every class registered in the metamodel.",
        "inputSchema": {"type": "object", "properties": {}}
      },
      {
        "name": "sandbar_entity_create",
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
          "required": ["class"]
        }
      },
      ...
    ]
  }
}
```

The verb catalog is **operational, not per-class**.  `sandbar_entity_create` works for any class; pass `{"class": "..."}` as an argument.  See [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md#the-verb-catalog-operational-not-per-class) for the rationale.

**Tool naming — the wire contract.**  Wire names are underscore-joined
(`sandbar_entity_create`); dots in the internal catalog identity project to
underscores at the wire boundary, while hyphens *inside* leaf tokens are
preserved (`sandbar_navigate_path-via`, `sandbar_class_validate-all-instances`).
`tools/list` advertises only the underscore names.  The older dotted spellings
(`sandbar.entity.create`) are still accepted by `tools/call` for **one release**
as a never-advertised deprecation alias — new clients must send the underscore
form.

The 0.2.0 catalog (82 verbs) includes among others:

- **Schema introspection** — `sandbar_schema_classes`, `sandbar_class_describe`, `sandbar_class_slots`, `sandbar_property_domain`, `sandbar_property_range`
- **Entity CRUD** — `sandbar_entity_create`, `sandbar_entity_update`, `sandbar_entity_find`, `sandbar_entity_validate`
- **Navigation** — `sandbar_navigate_outbound-edges`, `sandbar_navigate_inbound-edges`, `sandbar_navigate_path-via`, `sandbar_navigate_siblings-of`
- **Search + aggregation** — `sandbar_search_bm25f`, `sandbar_aggregate_count`, `sandbar_aggregate_group-by`, `sandbar_aggregate_rank-by`, `sandbar_aggregate_tag-histogram`
- **Shape validation** — `sandbar_shape_list`, `sandbar_shape_validate`, `sandbar_shape_conformance-report`, `sandbar_shape_create`, `sandbar_shape_update`
- **Workflow** — `sandbar_workflow_define`, `sandbar_workflow_start-process`, `sandbar_workflow_transition`, `sandbar_workflow_process-state` (workflow *definitions* are `:mm/Workflow` memorials; runs are `:workflow/Process` substrate-runtime instances)
- **Reactive** — `sandbar_reactive_health`

## Calling a tool

```bash
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "sandbar_entity_create",
      "arguments": {
        "class": "mm/Memory",
        "format": "markdown",
        "source": "---\nname: Foo\ntype: idea\n---\n# Context\n\nA quick thought.\n"
      }
    }
  }'
```

Response is wrapped per the MCP spec — the payload is in the `content` array as a stringified JSON:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {"type": "text", "text": "{\"entity-id\":12345,\"ident\":\"mm/foo\",...}"}
    ]
  }
}
```

**Important — unwrap the content.**  Clients must extract `content[0].text` and parse it as JSON to get the tool's actual payload.  This is a spec compliance discipline; clients that don't unwrap receive a string when they expect an object.  See the discussion in [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md#wire-shape--concrete) for the asymmetry that surfaced during corpus migration.

## Listing and reading resources

```bash
# List all resources surfaced from the metamodel
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{"jsonrpc":"2.0","id":4,"method":"resources/list"}'
```

Resources are addressable read-only content.  Sandbar surfaces every instance of every class that has `:dt/native-codec` declared:

```json
{
  "result": {
    "resources": [
      {
        "uri": "mcp://sandbar/mm/Memory/decisions/foo",
        "name": "decisions/foo",
        "mimeType": "text/markdown",
        "description": "..."
      },
      ...
    ]
  }
}
```

Read one:

```bash
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 5,
    "method": "resources/read",
    "params": {"uri": "mcp://sandbar/mm/Memory/decisions/foo"}
  }'
```

The response includes the rendered native-format content:

```json
{
  "result": {
    "contents": [
      {
        "uri": "mcp://sandbar/mm/Memory/decisions/foo",
        "mimeType": "text/markdown",
        "text": "---\nname: Foo\n...\n---\n# Context\n..."
      }
    ]
  }
}
```

## Subscribing to resource updates

Open a long-lived connection and subscribe:

```bash
# In one connection — subscribe to a URI
curl -N -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 6,
    "method": "resources/subscribe",
    "params": {"uri": "mcp://sandbar/mm/Memory/decisions/foo"}
  }'
```

The server replies, then keeps the connection open to push SSE-framed notifications when the resource changes:

```
data: {"jsonrpc":"2.0","method":"notifications/resources/updated","params":{"uri":"mcp://sandbar/mm/Memory/decisions/foo"}}
```

When you no longer want the updates:

```json
{"jsonrpc":"2.0","id":7,"method":"resources/unsubscribe","params":{"uri":"..."}}
```

## Long-running operations (Tasks)

Some tools — `sandbar_validation_start`, `sandbar_workflow_start-process` — kick off long-running operations and return a *task envelope*:

```json
{
  "result": {
    "task-id": "12345",
    "status": "pending"
  }
}
```

Poll status:

```bash
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 8,
    "method": "tasks/get",
    "params": {"task-id": "12345"}
  }'
```

Response:

```json
{
  "result": {
    "task-id": "12345",
    "status": "complete",
    "kind": "success",
    "result": {...}
  }
}
```

`kind` is one of `"success"` / `"failure"` / `"cancel"` — the terminal classification.  See [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md#terminal-kind-classification) for the design.

Or subscribe to status notifications via SSE:

```
data: {"jsonrpc":"2.0","method":"notifications/tasks/status","params":{"task-id":"12345","status":"running"}}
data: {"jsonrpc":"2.0","method":"notifications/tasks/status","params":{"task-id":"12345","status":"complete","kind":"success"}}
```

Cancel a running task:

```json
{"jsonrpc":"2.0","id":9,"method":"tasks/cancel","params":{"task-id":"12345"}}
```

The cancel is honored only if the workflow's current state allows it (see workflow design).  If not, the response is a JSON-RPC error with `:code -32602`.

## Error handling

Errors follow JSON-RPC 2.0 conventions:

| Code      | Meaning                                                                   |
|-----------|---------------------------------------------------------------------------|
| `-32700`  | Parse error — malformed JSON                                              |
| `-32600`  | Invalid request — missing `jsonrpc`, `method`, etc.                       |
| `-32601`  | Method not found                                                          |
| `-32602`  | Invalid params (including: unknown tool name, bad arguments, can't cancel)|
| `-32603`  | Internal error                                                            |
| `-32000`  | Application-defined — auth failure, validation failure, business logic    |

Inspect `error.data` for structured details.  For example, an unknown tool:

```json
{
  "error": {
    "code": -32602,
    "message": "Unknown tool: foo_bar_baz",
    "data": {"available-tools": ["sandbar_entity_create", ...]}
  }
}
```

## Example clients

### Python (anthropic SDK + httpx)

```python
import httpx
import json

class SandbarMCP:
    def __init__(self, url, token):
        self.url = url
        self.token = token
        self._id = 0

    def _next_id(self):
        self._id += 1
        return self._id

    def call(self, method, params=None):
        body = {"jsonrpc": "2.0", "id": self._next_id(), "method": method}
        if params is not None:
            body["params"] = params
        r = httpx.post(self.url,
                       headers={"Authorization": f"Bearer {self.token}"},
                       json=body)
        r.raise_for_status()
        return r.json()

    def tool_call(self, name, **arguments):
        resp = self.call("tools/call", {"name": name, "arguments": arguments})
        if "error" in resp:
            raise RuntimeError(resp["error"])
        # Unwrap the MCP content envelope
        return json.loads(resp["result"]["content"][0]["text"])

# Usage
mcp = SandbarMCP("http://localhost:8389/mcp", token)
mcp.call("initialize", {"protocolVersion": "2025-11-25",
                        "clientInfo": {"name": "py-client", "version": "0.2.0"},
                        "capabilities": {}})
mcp.call("notifications/initialized")
print(mcp.call("tools/list"))
print(mcp.tool_call("sandbar_entity_create",
                    **{"class": "mm/Memory",
                       "format": "markdown",
                       "source": "---\nname: Foo\n---\n..."}))
```

### Clojure (clj-http)

```clojure
(require '[clj-http.client :as http])

(defn mcp-call [token method params]
  (-> (http/post "http://localhost:8389/mcp"
        {:headers {"Authorization" (str "Bearer " token)}
         :content-type :json
         :as :json
         :form-params {:jsonrpc "2.0"
                       :id 1
                       :method method
                       :params params}})
      :body))

(mcp-call token "tools/list" {})
```

### Claude Code, end to end

The complete connect flow — server up, token minted, token exported in the
*launching* shell, project-scoped `.mcp.json` with env-expansion.  Four steps,
in this order:

**1. Start the server.**

```bash
bin/sandbar start     # launches the JVM, polls /mcp until ready (~30s cold)
bin/sandbar status    # expect: RUNNING — port 8389 (PID ...)
```

**2. Mint a service-account token** (once per client identity; `rotate-token`
also persists it to `~/claude/.sandbar/token`, mode 600):

```bash
bin/sandbar rotate-token corpus my-key
# under the hood: lein issue-mcp-token corpus my-key --rotate
```

**3. Export the token in the shell that will launch Claude Code.**  This is
the classic footgun: `.mcp.json` env-expansion resolves `${SANDBAR_TOKEN}`
from the environment of the `claude` *process* — so the export must happen in
the launching shell **before** you start Claude Code, and a token rotated
mid-session is not picked up until you relaunch.

```bash
export SANDBAR_TOKEN="$(cat ~/claude/.sandbar/token)"
claude   # launch from the project directory, with the export in effect
```

**4. Register the server in the project's `.mcp.json`** (committed to the
repo, so every consumer of the project shares the registration; the token
itself never enters version control — only the env reference does):

```json
{
  "mcpServers": {
    "sandbar": {
      "type": "http",
      "url": "http://localhost:8389/mcp",
      "headers": {
        "Authorization": "Bearer ${SANDBAR_TOKEN:-disabled}"
      }
    }
  }
}
```

The `:-disabled` default keeps the registration inert (auth simply fails
closed) when the env-var is absent, instead of breaking client startup.
Claude Code discovers Sandbar's tools and resources on session start and
surfaces them as `mcp__sandbar__*` capabilities — e.g. the
`sandbar_search_bm25f` verb appears as `mcp__sandbar__sandbar_search_bm25f`.

For Claude Desktop the same `url` + `headers` block goes in
`claude_desktop_config.json`; the launching-shell rule applies to however the
desktop app inherits its environment.

## Patterns

### Discovery-driven invocation

The AI client should call `tools/list` once per session, cache the schema, and use it to validate arguments before invoking.  This is the reflection-driven discipline MCP was designed for.

### Idempotent retries

`sandbar_entity_create` returns `{:entity-id ...}` on success.  If a retry is needed (network failure, etc.), use the entity's `:db/ident` (if provided) for idempotency — re-creating with the same ident is a no-op.

### Subscription cleanup

Always `resources/unsubscribe` when you no longer need updates.  Server-side subscription state grows with active subscriptions; cleanup is the client's responsibility.

### Task polling cadence

For tasks expected to complete in seconds, poll every 500ms.  For minute-scale tasks, every 5s.  For longer, prefer SSE subscription over polling.

### Shape-driven validation as a tool-call

```bash
# Validate one entity against its applicable shapes (audit mode)
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 10,
    "method": "tools/call",
    "params": {
      "name": "sandbar_shape_validate",
      "arguments": {"entity": ":decisions/foo", "mode": "audit"}
    }
  }'
```

The response (unwrapped from `content[0].text`):

```json
{
  "entity": ":decisions/foo",
  "mode": "audit",
  "result-count": 1,
  "results": [
    {"status": "pass", "entity": 17592186, "shape": 17592345, "checks-passed": 6}
  ]
}
```

For batch class-wide conformance use `sandbar_shape_conformance-report`.  For authoring shapes (`sandbar_shape_create` / `sandbar_shape_update`) see [`authoring-shapes.md`](authoring-shapes.md).

## See also

- [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md) — server-side design and rationale
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) — complete verb reference
- [`writing-a-rest-client.md`](writing-a-rest-client.md) — REST alternative for non-AI consumers
- [`authoring-shapes.md`](authoring-shapes.md) — author `:mm/Shape` constraints + invoke shape verbs
- [`subscribing-to-events.md`](subscribing-to-events.md) — event substrate subscription API (in-design)
- [`auth.md`](../auth.md) — issuing service-account tokens
