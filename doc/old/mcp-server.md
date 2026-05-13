# MCP Server

Sandbar speaks the [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) — Anthropic's open standard for AI clients to consume tools, resources, and prompts from a server. With the MCP layer enabled, Claude (or any MCP-compatible client) discovers Sandbar's metamodel reflectively and consumes every `dt/Class` as a first-class tool.

## Table of Contents

- [Overview](#overview)
- [Quick Start](#quick-start)
- [Protocol](#protocol)
- [HTTP Endpoints](#http-endpoints)
- [Authentication](#authentication)
- [Capabilities](#capabilities)
- [Tools — `tools/list` and `tools/call`](#tools--toolslist-and-toolscall)
- [Resources — `resources/list`, `resources/read`, `resources/subscribe`](#resources--resourceslist-resourcesread-resourcessubscribe)
- [Prompts — `prompts/list` and `prompts/get`](#prompts--promptslist-and-promptsget)
- [Tasks — long-running operations](#tasks--long-running-operations)
- [Notifications — server → client push](#notifications--server--client-push)
- [Configuration](#configuration)
- [Failure Modes](#failure-modes)
- [Architecture](#architecture)
- [Layer-targeting Discipline](#layer-targeting-discipline)
- [See Also](#see-also)

## Overview

The MCP server runs alongside Sandbar's existing REST API on the same Pedestal HTTP service. Two new top-level routes appear next to `/api`:

| Route | Method | Purpose |
|-------|--------|---------|
| `/mcp` | POST | JSON-RPC 2.0 request endpoint |
| `/mcp/sse` | GET | Server-sent-events channel for notifications |

The MCP layer derives its surface **reflectively** from the metamodel:

- **Tools** are auto-generated from `dt/all-classes` — every non-abstract class becomes a tool. New classes appear after a `notifications/tools/list_changed` push, no server restart required.
- **Resources** are entities addressable at stable URIs (`mcp://sandbar/<class-ns>/<class-name>/<ident-or-eid>`).
- **Prompts** are workflow templates exposed via `dt/all-named-instances-of :workflow/Definition`.
- **Tasks** wrap long-running workflows so clients can poll their state and cancel them.

Because the metamodel is metacircular (`dt/Class` is itself a `dt/Class`), schema-evolution operations like `dt/make` against `:dt/Class` automatically expand the MCP surface.

## Quick Start

### 1. Start Sandbar

```bash
cd ~/src/sandbar
lein repl
```

```clojure
(require '[sandbar.core :refer [go]])
(go)  ; HTTP on :8080
```

### 2. Issue an API key for the MCP client

Bearer tokens reuse Sandbar's existing service-account / API-key infrastructure (`sandbar.util.auth`). Create a service account and capture its API key:

```clojure
(require '[sandbar.db.datatype :as dt])
(require '[sandbar.util.auth   :as auth])

(def api-key "super-secret-mcp-key")

(dt/make :auth/ServiceAccount
  {:auth/service-name   :service/claude
   :auth/principal-name "Claude MCP Client"
   :auth/api-key-hash   (auth/hash-password api-key)
   :auth/active?        true})
```

### 3. Verify the MCP endpoint

```bash
export SANDBAR_TOKEN="claude:super-secret-mcp-key"

curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "initialize",
    "params": {
      "protocolVersion": "2025-11-25",
      "clientInfo": {"name": "curl", "version": "1.0"}
    }
  }'
```

Expected response:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "protocolVersion": "2025-11-25",
    "capabilities": {
      "tools":     {"listChanged": true},
      "resources": {"subscribe": true, "listChanged": true},
      "prompts":   {"listChanged": true},
      "logging":   {}
    },
    "serverInfo": {
      "name":    "sandbar",
      "title":   "Sandbar",
      "version": "0.0.1-SNAPSHOT"
    }
  }
}
```

### 4. Register Sandbar as an MCP server in Claude Code

Add to your project's `.mcp.json`:

```json
{
  "mcpServers": {
    "sandbar": {
      "type": "http",
      "url":  "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${SANDBAR_TOKEN}"
      }
    }
  }
}
```

Then export `SANDBAR_TOKEN` in your shell environment and Claude will pick up the server on next session start.

## Protocol

- **Specification**: MCP 2025-11-25 (returned in `initialize` responses)
- **Envelope**: JSON-RPC 2.0
- **Content type**: `application/json`
- **Transport**: Streamable HTTP (POST `/mcp` for requests; GET `/mcp/sse` for server-push notifications)

### JSON-RPC error codes

| Code | Meaning |
|------|---------|
| `-32700` | Parse error |
| `-32600` | Invalid Request (missing `jsonrpc` / `method` / malformed envelope) |
| `-32601` | Method not found |
| `-32602` | Invalid params (unknown tool name, missing required field, etc.) |
| `-32603` | Internal error (handler exception) |

### Lifecycle

1. Client → `initialize` with its `protocolVersion` + `clientInfo`. Server responds with its `protocolVersion`, declared `capabilities`, and `serverInfo`.
2. Client → `notifications/initialized` (no response expected) to signal readiness.
3. Normal request flow — `tools/list`, `tools/call`, `resources/list`, etc.
4. Server pushes notifications via SSE when state changes (`tools/list_changed`, `resources/updated`, `prompts/list_changed`, `progress`).

## HTTP Endpoints

### `POST /mcp`

Synchronous JSON-RPC request endpoint. Interceptor chain:

1. `event-util/log-request` — request logging into Sandbar's event store
2. `content/data-body` + `content/accept-content` — content negotiation
3. `params/parsed-params` — body parsing
4. `mcp-auth/bearer-interceptor` — extracts Bearer token, validates, attaches `:identity`
5. `mcp-auth/require-bearer` — terminates with 401 if `:identity` is missing

The handler (`sandbar.mcp.transport/mcp-handler`) decodes the JSON-RPC envelope, dispatches via `sandbar.mcp.protocol/dispatch`, and returns the response (or HTTP 204 for pure notifications with no response).

### `GET /mcp/sse`

Server-sent-events channel. Clients subscribe here to receive notifications without polling. Notifications are JSON-RPC 2.0 notification envelopes (no `id` field).

The handler (`sandbar.mcp.transport/sse-handler`) opens an event stream via `io.pedestal.http.sse/start-event-stream` and registers the client in the in-memory subscriber registry. Failing subscribers are auto-unregistered on send error.

## Authentication

MCP requests authenticate via **Bearer token**, where the token is Sandbar's existing `<service-name>:<api-key>` format:

```http
Authorization: Bearer <service-name>:<api-key>
```

Example:

```http
Authorization: Bearer claude:super-secret-mcp-key
```

Token validation delegates to `sandbar.util.auth/authenticate-api-key` — the same path used for the `X-API-Key` header on REST endpoints. Both paths verify against the bcrypt+sha512 hash stored on the `auth/ServiceAccount`.

### Interceptors

| Interceptor | Behavior |
|-------------|----------|
| `bearer-interceptor` | Extracts the Bearer token, parses `service:key`, calls `authenticate-api-key`. Attaches `:identity` on success. Pass-through on missing/malformed token (allowing other auth interceptors a shot first). |
| `require-bearer` | Terminates with HTTP 401 + `WWW-Authenticate: Bearer realm="sandbar-mcp"` (RFC 6750) if `:identity` is not attached. |

### Failure-mode responses

| Scenario | Response |
|----------|----------|
| Missing Authorization header | 401 + `WWW-Authenticate: Bearer realm="sandbar-mcp"` + `{"error":"Bearer token required"}` |
| Malformed token (no colon separator) | 401 (same shape; warning logged with `:reason :token-missing-colon-separator`) |
| Unknown service name | 401 (`authenticate-api-key` returns `{:success false :reason :unknown-service}`) |
| API key mismatch | 401 (`{:success false :reason :invalid-key}`) |
| Service account inactive | 401 (`{:success false :reason :account-inactive}`) |

For broader authentication detail (sessions, role/permission model, account lockout, etc.) see [doc/auth.md](auth.md).

## Capabilities

The server declares the following capabilities during `initialize`:

```json
{
  "tools":     {"listChanged": true},
  "resources": {"subscribe": true, "listChanged": true},
  "prompts":   {"listChanged": true},
  "logging":   {}
}
```

| Capability | Notes |
|------------|-------|
| `tools.listChanged` | Server pushes `notifications/tools/list_changed` when new `dt/Class` / `dt/Property` instances land |
| `resources.subscribe` | Clients can subscribe to per-resource updates via `resources/subscribe`; server pushes `notifications/resources/updated` |
| `resources.listChanged` | Server pushes `notifications/resources/list_changed` when the resource catalog changes |
| `prompts.listChanged` | Server pushes `notifications/prompts/list_changed` when workflow definitions change |
| `logging` | Reserved; per-tool logging hooks are planned (no methods declared yet) |

## Tools — `tools/list` and `tools/call`

### Bootstrap-by-discovery

`tools/list` walks `dt/all-classes`, drops abstract classes, and emits one tool description per remaining class. No hand-maintained catalog — the metamodel IS the catalog.

### Naming convention

Tool names map class idents to dotted strings:

| Class ident | Tool name |
|-------------|-----------|
| `:zorp/Footwear` | `sandbar.class.zorp.Footwear` |
| `:auth/User` | `sandbar.class.auth.User` |
| `:dt/Class` | `sandbar.class.dt.Class` |
| `:mm/Memory` | `sandbar.class.mm.Memory` |

The codec is bidirectional (`class->tool-name` / `tool-name->class-ident`).

### Input schema generation

Each tool's `inputSchema` is derived from the class's slots:

- `dt/slots-of cls` → all inherited + direct slots
- `dt/range-of slot` → Datomic value type → JSON Schema type
- `dt/cardinality-many? slot` → JSON Schema `array` wrapper
- `dt/required-slots-of cls` → JSON Schema `required` array

Datomic-to-JSON-Schema mapping:

| Datomic type | JSON Schema |
|--------------|-------------|
| `:db.type/string` | `{"type": "string"}` |
| `:db.type/long` | `{"type": "integer"}` |
| `:db.type/double` | `{"type": "number"}` |
| `:db.type/boolean` | `{"type": "boolean"}` |
| `:db.type/instant` | `{"type": "string", "format": "date-time"}` |
| `:db.type/keyword` | `{"type": "string", "description": "Clojure keyword string"}` |
| `:db.type/uuid` | `{"type": "string", "format": "uuid"}` |
| `:db.type/uri` | `{"type": "string", "format": "uri"}` |
| `:db.type/ref` | `{"type": "string", "description": "Reference to another entity (ident or eid)"}` |

### `tools/list` example

```http
POST /mcp
{"jsonrpc": "2.0", "id": 2, "method": "tools/list"}
```

Response (truncated):

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "sandbar.class.auth.ServiceAccount",
        "title": "ServiceAccount operations",
        "description": "Sandbar operations on :auth/ServiceAccount instances",
        "inputSchema": {
          "type": "object",
          "properties": {
            "auth/service-name": {"type": "string", "description": "Clojure keyword string"},
            "auth/principal-name": {"type": "string"},
            "auth/api-key-hash": {"type": "string"},
            "auth/active?": {"type": "boolean"}
          },
          "required": ["auth/service-name"]
        }
      },
      ...
    ]
  }
}
```

### `tools/call` dispatch

`tools/call` resolves the tool name to a class ident, coerces arguments via slot metadata, and creates the entity with `dt/make`:

1. `tool-name->class-ident` recovers the class keyword
2. `dt/class-of` retrieves the class entity (404 → `-32602` Invalid params)
3. `dt/abstract?` rejects abstract-class instantiation (returns `isError: true`)
4. `coerce-arguments` walks `dt/slots-of` and converts each JSON value per `dt/range-of` + `dt/cardinality-many?`
5. `dt/make` transacts with validation (never `{:validate? false}`)
6. If the new entity is a `:dt/Class` or `:dt/Property`, `notifications/tools/list_changed` fires automatically (B.1.4 schema evolution)
7. The entity's projected JSON-friendly data returns inside an MCP `content` array

### `tools/call` example

```http
POST /mcp
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "sandbar.class.zorp.SpaceBoot",
    "arguments": {
      "footwear/name":           "Moon Boot Pro",
      "footwear/price":          299.99,
      "footwear/tentacle-count": 4,
      "boot/vacuum-rated?":      true
    }
  }
}
```

Response:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {"type": "text",
       "text": "{:db/id 17592186045712, :dt/type :zorp/SpaceBoot, ...}"}
    ]
  }
}
```

Validation failure response:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {"type": "text",
       "text": "Validation failed: Missing required slot — {:missing #{:footwear/name}}"}
    ],
    "isError": true
  }
}
```

### Argument coercion notes

- **Keywords**: string `"foo/bar"` or `":foo/bar"` → keyword `:foo/bar`
- **Instants**: ISO-8601 string → `java.util.Date`
- **UUIDs**: UUID string → `java.util.UUID`
- **Refs**: ident-string `:ns/name` → keyword `:ns/name`
- **Cardinality-many**: scalar value auto-wrapped to vector; existing vectors preserved

## Resources — `resources/list`, `resources/read`, `resources/subscribe`

### URI scheme

```
mcp://sandbar/<class-ns>/<class-name>/<ident-or-eid>
```

Examples:

| URI | Refers to |
|-----|-----------|
| `mcp://sandbar/dt/Class/zorp.Footwear` | The `:zorp/Footwear` class entity |
| `mcp://sandbar/auth/User/zorp.user.42` | A specific user with eid 42 |
| `mcp://sandbar/mm/Memory/decisions.sandbar_mcp_server_design_2026_05_12` | A memory entity |

The codec round-trips: `parse-uri` → `resource-info` map; `format-uri` → URI string.

### `resources/list`

Walks `dt/all-named-instances-of :dt/Resource` and projects each entity to an MCP resource descriptor (URI + name + optional description + MIME type).

### `resources/read`

Projects entity content into MCP `content` blocks. Different classes project differently:

| Class | Projection |
|-------|------------|
| `:mm/Memory` | Reconstructed Markdown (per markdown-export ADR M.3 composition) |
| `:dt/Class` / `:dt/Property` | EDN representation of metamodel slots |
| Other | EDN representation of all `dt/`-namespace + user-namespace slot values |

### `resources/subscribe` / `resources/unsubscribe`

Per-resource subscriptions managed by an in-memory registry. When an entity changes, `notifications/resources/updated` fires to subscribers (broadcast via the tx-report-queue listener — see Stage C.5.3 follow-up in [the arc plan](#see-also)).

## Prompts — `prompts/list` and `prompts/get`

**Workflows-as-prompts pattern**: Sandbar's workflow definitions (`workflow/Definition` instances declared in `schema/workflow.edn`) become MCP prompts.

### `prompts/list`

Walks `dt/all-named-instances-of :workflow/Definition` and emits one prompt per workflow.

### `prompts/get`

Renders the workflow as Markdown-shaped MCP prompt text:

- Workflow name + version
- State diagram (states + transitions)
- Invocation hints (how a client would `start-process!` this workflow)

Name codec: keyword `:validation/resource` ↔ string `sandbar.workflow.validation.resource`.

### Examples of mapped workflows

- `:validation/resource` — validation-as-workflow per `sandbar.service.validation`
- `:order/fulfillment` — order lifecycle (pending → confirmed → shipped → delivered)
- Future: `:export/restore` — markdown export/restore round-trip workflow

For workflow modeling (states, transitions, guards, history) see [doc/workflow.md](workflow.md).

## Tasks — long-running operations

MCP's **Tasks** primitive (experimental) wraps long-running tool calls so clients can poll for status rather than block. Sandbar implements Tasks by backing each task with a workflow process (`sandbar.util.workflow`).

| Task method | Sandbar mapping |
|-------------|-----------------|
| `tasks/get` | `workflow/find-process` + `workflow/get-current-state` → MCP status |
| `tasks/cancel` | `workflow/cancel-process!` (per `workflow/can-cancel?` guard) |

The task-id IS the workflow process's `:db/id` (stringified) — no parallel registry. Process state maps to MCP task status:

| Workflow state | MCP status |
|----------------|------------|
| Non-terminal | `running` |
| `process-completed?` true | `completed` |
| Terminal (other) | `completed` (refined in C.7.2) |
| Process not found | `missing` |

For the full Tasks API (request/response shapes, error handling, the `start-task!` helper) see [doc/tasks-api.md](tasks-api.md).

## Notifications — server → client push

Clients subscribe to `/mcp/sse` to receive notifications. All notifications are JSON-RPC 2.0 envelopes with no `id`:

| Method | Fires when |
|--------|------------|
| `notifications/tools/list_changed` | New `:dt/Class` or `:dt/Property` instance lands |
| `notifications/resources/list_changed` | Resource catalog changes |
| `notifications/resources/updated` | A subscribed resource is updated |
| `notifications/prompts/list_changed` | A workflow definition is added or modified |
| `notifications/progress` | Long-running task makes progress (`tasks/*`-related) |
| `notifications/message` | Server emits a log/status message |

### Subscriber registry

`sandbar.mcp.notifications` maintains an in-memory map of subscriber channels keyed by session. `publish!` broadcasts to all; subscribers that fail to receive are auto-unregistered.

### Canonical helpers

```clojure
(notifications/tools-list-changed!)
(notifications/resources-updated! uri)
(notifications/prompts-list-changed!)
(notifications/message! :info "Server starting...")
```

## Configuration

### Sandbar side

The MCP server runs whenever Sandbar's Pedestal HTTP server is up — no extra configuration. Service-account API keys are managed via the standard `auth/ServiceAccount` flow (see [doc/auth.md](auth.md)).

### Claude Code (client) side

Project-scoped registration in `.mcp.json` at the project root:

```json
{
  "mcpServers": {
    "sandbar": {
      "type": "http",
      "url":  "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${SANDBAR_TOKEN:-disabled}"
      }
    }
  }
}
```

The `:-disabled` fallback means the registration is committed-but-inert until the operator sets `SANDBAR_TOKEN` in their shell. Once set, the next Claude Code session activates the server.

### Multi-machine deployment

Streamable HTTP (this server's transport) supports multi-client and cross-machine consumers. For laptop / desktop split (Sandbar on machine A, Claude on machine B), expose `/mcp` and `/mcp/sse` over a TLS-terminated reverse proxy and update the `url` field accordingly. Bearer tokens are scoped per service account; rotate via the `auth/ServiceAccount` lifecycle.

stdio transport is not supported (rejected during design per ADR B.1.1 — stdio doesn't support multi-machine or multi-client postures).

## Failure Modes

| Failure | Symptom | Recovery |
|---------|---------|----------|
| Sandbar JVM not running | Client sees connection refused / timeout | Start Sandbar; verify HTTP up at `:8080` |
| `SANDBAR_TOKEN` unset (Claude side) | Registration inert; no tools listed | Export the token + restart Claude Code session |
| Token doesn't match a service account | 401 + WWW-Authenticate header | Verify `auth/ServiceAccount` exists and is `:auth/active?` |
| Bearer token malformed (missing `:`) | 401 + warning log `:reason :token-missing-colon-separator` | Use `<service>:<key>` format |
| `dt/make` validation fails on `tools/call` | Response includes `isError: true` + validation details | Inspect required slots via `tools/list`'s `inputSchema` |
| Abstract-class instantiation attempted | `isError: true` with "Cannot instantiate abstract class" | Use a concrete subclass |
| MCP method unknown | JSON-RPC `-32601 Method not found` | Verify spec compliance; consult `method-handlers` table in `sandbar.mcp.protocol` |
| Task not found | `-32602 Task not found` | Tasks are not persistent beyond workflow-process lifetime; verify the process still exists |

## Architecture

### Namespace layout

```
sandbar.mcp/
├── protocol.clj       — JSON-RPC envelope + initialize + dispatch table
├── transport.clj      — POST /mcp + GET /mcp/sse handlers
├── auth.clj           — Bearer-token interceptor
├── tools.clj          — tools/list bootstrap-by-discovery; tools/call
├── resources.clj      — URI codec + list/read/subscribe
├── prompts.clj        — Workflows-as-prompts
├── tasks.clj          — Workflow-backed Tasks primitive
└── notifications.clj  — Subscriber registry + canonical helpers
```

### Layer composition

```
Claude / MCP client
        │   HTTP (Bearer auth)
        ▼
┌──────────────────────────────────────────────────┐
│  sandbar.mcp.transport   (Pedestal handlers)     │
└──────────────────────────────────────────────────┘
        │
┌──────────────────────────────────────────────────┐
│  sandbar.mcp.auth        (Bearer interceptor)    │
└──────────────────────────────────────────────────┘
        │
┌──────────────────────────────────────────────────┐
│  sandbar.mcp.protocol    (JSON-RPC dispatch)     │
└──────────────────────────────────────────────────┘
        │
┌──────────────────────────────────────────────────┐
│  sandbar.mcp.{tools, resources, prompts, tasks}  │
│  sandbar.mcp.notifications                       │
└──────────────────────────────────────────────────┘
        │
┌──────────────────────────────────────────────────┐
│  Higher-layer Sandbar abstractions:              │
│    sandbar.db.datatype (dt/*)                    │
│    sandbar.util.auth   (authenticate-api-key)    │
│    sandbar.util.workflow                         │
│    sandbar.service.validation                    │
└──────────────────────────────────────────────────┘
        │
┌──────────────────────────────────────────────────┐
│  sandbar.db.datomic + Datomic peer-api           │
└──────────────────────────────────────────────────┘
```

## Layer-targeting Discipline

The MCP layer **never** calls `datomic.api` directly. Every tool implementation routes through Sandbar's higher-abstraction APIs:

| Concern | Use this | Never use |
|---------|----------|-----------|
| Class introspection | `dt/all-classes`, `dt/slots-of`, `dt/range-of`, `dt/cardinality-many?` | `d/q` over `:dt/Class` entities |
| Instance creation | `dt/make` | `d/transact` |
| Instance retrieval | `dt/class-of`, `dt/all-instances-of` | `d/pull` |
| Authentication | `sandbar.util.auth/authenticate-api-key` | bcrypt hashes directly |
| Workflow process state | `sandbar.util.workflow/*` | `:workflow/Process` entities directly |

When an abstraction lacks expressivity, **improve the abstraction** rather than bypass it. Stage C.7.4 of this arc landed `workflow/cancel-process!` and `workflow/can-cancel?` upstream because `sandbar.mcp.tasks/handle-cancel` needed cancellation semantics that didn't yet exist at the abstraction layer.

Empirical verification: `clj-xref` over `sandbar.mcp.*` namespaces reports **zero** `datomic.api` references. The discipline is mechanically enforceable, not just aspirational.

## See Also

- **[doc/auth.md](auth.md)** — full authentication / authorization model (sessions, RBAC, account lockout)
- **[doc/tasks-api.md](tasks-api.md)** — MCP Tasks primitive in detail
- **[doc/workflow.md](workflow.md)** — workflow definitions, processes, transitions, cancel-process!
- **[doc/store-api.md](store-api.md)** — REST surface that MCP composes with (same Sandbar abstractions, different protocol)
- **[doc/jobs-api.md](jobs-api.md)** — background job system
- **[doc/architecture.md](architecture.md)** — overall Sandbar architecture
- **[Model Context Protocol Specification](https://modelcontextprotocol.io/)** — the protocol Sandbar implements
