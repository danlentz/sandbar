# MCP Tasks API

The MCP **Tasks** primitive (experimental in the MCP 2025-11-25 spec) lets a client kick off a long-running operation, receive a handle immediately, and poll for status. Sandbar implements Tasks by composing them with the workflow substrate (`sandbar.util.workflow`) — every task is a workflow process and every progress / completion / cancellation event maps to a workflow state transition.

This document describes the MCP-side surface. For workflow definitions, transitions, guards, and the underlying lifecycle see [doc/workflow.md](workflow.md).

## Table of Contents

- [Overview](#overview)
- [Model](#model)
- [API Methods](#api-methods)
- [Task Status Mapping](#task-status-mapping)
- [Starting a Task](#starting-a-task)
- [Polling — `tasks/get`](#polling--tasksget)
- [Cancelling — `tasks/cancel`](#cancelling--taskscancel)
- [Notifications](#notifications)
- [Composition with Tools](#composition-with-tools)
- [Failure Modes](#failure-modes)
- [Design Rationale](#design-rationale)
- [See Also](#see-also)

## Overview

A long-running tool call (export-all, validate-all-instances, bulk-ingest, schema migration, anything that takes longer than a request-response cycle) returns a **task handle** rather than blocking. The client then:

1. Receives `taskId` immediately
2. Polls `tasks/get <taskId>` for status, OR subscribes to `notifications/progress` for push updates
3. Receives the terminal result when the workflow process reaches a terminal state
4. Optionally cancels via `tasks/cancel <taskId>` if needed

The task-id IS the workflow process's `:db/id` (stringified for JSON transport). There is no parallel registry — the workflow process is the durable source of truth.

## Model

```
MCP Client                                Sandbar
   │                                         │
   │  tools/call (long-running)              │
   │ ───────────────────────────────────────►│
   │                                         │ start-task! ─►
   │                                         │   workflow/start-process!
   │                                         │
   │  ◄─ { content: [...], taskId: "42" } ───│
   │                                         │
   │  notifications/progress (taskId 42)     │
   │  ◄──────────────────────────────────────│
   │                                         │ ... workflow advances
   │                                         │     through states
   │  tasks/get { taskId: "42" }             │
   │ ───────────────────────────────────────►│
   │  ◄─ { status: "running", state: ... } ──│
   │                                         │
   │  tasks/get { taskId: "42" }             │
   │ ───────────────────────────────────────►│
   │  ◄─ { status: "completed", content: ...}│
```

### Task ↔ Workflow correspondence

| Workflow concept | MCP Task concept |
|------------------|------------------|
| Workflow process `:db/id` (as string) | `taskId` |
| Current state ident | `state` field on status |
| `process-completed?` true | `status: "completed"` |
| `process-in-terminal-state?` true (non-completed terminal) | `status: "completed"` (refined in C.7.2) |
| Non-terminal state | `status: "running"` |
| Process not found | `status: "missing"` |
| `cancel-process!` invoked | Terminal cancelled state |

## API Methods

| Method | Direction | Purpose |
|--------|-----------|---------|
| `tasks/get` | Client → Server | Fetch current status + (if terminal) result content |
| `tasks/cancel` | Client → Server | Request cancellation of an in-flight task |
| `notifications/progress` | Server → Client | Push progress signal (fired by `start-task!` + workflow transitions) |

All methods use the JSON-RPC 2.0 envelope shape from [doc/mcp-server.md](mcp-server.md#protocol).

## Task Status Mapping

`sandbar.mcp.tasks/process->task-status` projects a workflow process to an MCP task status object:

```clojure
;; Running task
{:status "running"
 :state  :validation/in-progress}

;; Successful completion
{:status "completed"
 :state  :validation/passed}

;; Terminal but non-success (e.g., :validation/failed)
{:status "completed"
 :state  :validation/failed}

;; Process not found
{:status "missing"}
```

**Note**: The current Stage C.7 implementation maps both successful and non-successful terminal states to `"completed"`. Stage C.7.2 will refine this to distinguish `"completed"` (success) from `"failed"` (error) based on the workflow's terminal-state classification.

## Starting a Task

### From within a tool implementation

When a tool wants to start a long-running operation, it calls `start-task!` and returns the task handle to the client:

```clojure
(require '[sandbar.mcp.tasks :as tasks])

(defn handle-bulk-validate
  "MCP tool handler that validates all instances of a class.
   Long-running for large class hierarchies."
  [id params]
  (let [class-ident (:class params)
        task-id     (tasks/start-task! :validation/all-instances
                                       class-ident
                                       {:requested-by (-> params :_identity :db/id)})]
    {:jsonrpc "2.0"
     :id      id
     :result  {:content [{:type "text"
                          :text (str "Validation started. Task: " task-id)}]
               :taskId  task-id}}))
```

### `start-task!` signature

```clojure
(start-task! workflow-ident subject data) ;; → task-id (string)
```

| Argument | Description |
|----------|-------------|
| `workflow-ident` | `:db/ident` of the workflow definition (e.g., `:validation/all-instances`) |
| `subject` | Entity (or `:db/id`) the process operates on |
| `data` | Initial process data map (becomes the workflow's `:process/data`) |

### What `start-task!` does

1. Calls `workflow/start-process!` — creates a workflow process in the initial state
2. Stringifies the process's `:db/id` as the task-id
3. Pushes `notifications/progress` so subscribed clients learn of the new task immediately
4. Returns the task-id string

Per the layer-targeting discipline (see [doc/mcp-server.md](mcp-server.md#layer-targeting-discipline)), `start-task!` never touches `datomic.api` — only the `workflow/*` abstraction.

## Polling — `tasks/get`

Fetch the current status (and terminal result when complete) for a task.

### Request

```http
POST /mcp
Authorization: Bearer <service>:<key>
Content-Type: application/json
```

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "method": "tasks/get",
  "params": {
    "taskId": "17592186045700"
  }
}
```

### Response — running

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "result": {
    "taskId": "17592186045700",
    "status": "running",
    "state":  ":validation/in-progress"
  }
}
```

### Response — completed

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "result": {
    "taskId": "17592186045700",
    "status": "completed",
    "state":  ":validation/passed",
    "content": [
      {"type": "text",
       "text": "Task in state: :validation/passed\nProcess data: {:instances-validated 142, :failures 0}"}
    ]
  }
}
```

The `content` field is populated only when the task reaches a terminal state. It carries:

- The final workflow state ident
- The accumulated `:process/data` (whatever the workflow's terminal step recorded)

### Response — missing

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "error": {
    "code":    -32602,
    "message": "Task not found: 17592186045700",
    "data":    {"received-task-id": "17592186045700"}
  }
}
```

### Response — missing `taskId` parameter

```json
{
  "jsonrpc": "2.0",
  "id": 42,
  "error": {
    "code":    -32602,
    "message": "tasks/get requires :taskId parameter"
  }
}
```

### Error response — invalid task-id format

If the `taskId` isn't a parseable long, `tasks/get` treats it as not-found (returns the same `-32602` shape).

## Cancelling — `tasks/cancel`

Request termination of an in-flight task.

### Request

```http
POST /mcp
```

```json
{
  "jsonrpc": "2.0",
  "id": 43,
  "method": "tasks/cancel",
  "params": {
    "taskId": "17592186045700"
  }
}
```

### Success response

```json
{
  "jsonrpc": "2.0",
  "id": 43,
  "result": {
    "taskId": "17592186045700",
    "status": "completed",
    "state":  ":validation/cancelled",
    "content": [
      {"type": "text",
       "text": "Task cancelled. Workflow state: :validation/cancelled"}
    ]
  }
}
```

### Cancellation guard

`workflow/cancel-process!` checks `workflow/can-cancel?` before transitioning. If the process can't be cancelled (already terminal, or the workflow declares it non-cancellable), the response carries `isError: true`:

```json
{
  "jsonrpc": "2.0",
  "id": 43,
  "result": {
    "content": [
      {"type": "text",
       "text": "Cannot cancel task: process already in terminal state (reason: :already-completed)"}
    ],
    "isError": true
  }
}
```

Possible `:reason` values:

| Reason | Meaning |
|--------|---------|
| `:already-completed` | Process is already in a terminal state |
| `:not-cancellable` | The workflow definition disallows cancellation from the current state |
| `:no-cancel-transition` | No transition exists to a `:cancelled`-type terminal state |

### Error response — missing or unknown task

Same shapes as `tasks/get`'s `Task not found` and missing-parameter errors.

## Notifications

### `notifications/progress`

Fired automatically when:

1. `start-task!` creates a new workflow process (initial broadcast)
2. (Future C.7.1) workflow process transitions through intermediate states

```json
{
  "jsonrpc": "2.0",
  "method": "notifications/progress",
  "params": {
    "taskId":   "17592186045700",
    "status":   "running",
    "workflow": ":validation/all-instances"
  }
}
```

### Subscribing

Clients subscribe by connecting to `GET /mcp/sse` (see [doc/mcp-server.md](mcp-server.md#http-endpoints)). All notification methods route through the same SSE channel; clients filter client-side by `method` and `params.taskId`.

## Composition with Tools

The Tasks primitive doesn't replace `tools/call` — it composes with it. Tools have two response shapes:

| Shape | When to use |
|-------|-------------|
| Inline `content` array | Sub-second operations; fits within a single request |
| `taskId` handle + start-task! | Operations expected to exceed ~5 seconds |

Stage C.7.3 (future) will auto-dispatch `tools/call` to a Task when the tool declares (or the runtime measures) expected duration over a threshold. For now, tools opt in explicitly by calling `start-task!`.

### Pattern — task-emitting tool

```clojure
(defn handle-export-all
  "MCP tool: export every memory entity to markdown. Long-running."
  [id params]
  (let [scope    (:scope params)
        task-id  (tasks/start-task! :export/all-memories
                                    scope
                                    {:format :markdown
                                     :requested-at (java.util.Date.)})]
    {:jsonrpc "2.0"
     :id      id
     :result  {:content [{:type "text"
                          :text (str "Exporting " scope " memories to markdown.\n"
                                     "Track progress: tasks/get { taskId: \"" task-id "\" }")}]
               :taskId  task-id}}))
```

The MCP client UI typically renders `taskId`-bearing responses with a progress indicator and an explicit "cancel" button.

## Failure Modes

| Failure | Symptom | Recovery |
|---------|---------|----------|
| `taskId` from older session (process garbage-collected) | `Task not found` | Tasks aren't durable beyond workflow-process lifetime; start a new task |
| Workflow process exists but `:db/ident`-less state | `tasks/get` returns `state: nil` | Verify workflow definition has named states |
| `cancel-process!` fails because workflow has no `:cancelled` terminal | `isError: true` with `:reason :no-cancel-transition` | Add a `:cancelled` terminal + `:cancel` transition to the workflow definition |
| Cancellation race (cancel arrives while process completes) | One of `:already-completed` or successful cancel wins | Clients should treat both terminal outcomes as "task ended" |
| SSE channel dropped mid-progress | Subscriber removed from registry; `notifications/progress` no longer reaches client | Reconnect to `/mcp/sse`; poll `tasks/get` for the missed state |
| `taskId` not a valid long | Treated as not-found | Use task-ids as opaque strings; don't synthesize them |

## Design Rationale

### Why workflows back tasks?

Sandbar already has a workflow substrate with:

- Durable state machines via Datomic entities
- Transition guards + reasons + history
- Cancellation semantics (via Stage C.7.4's `workflow/cancel-process!` extension)
- A `validation-as-workflow` pattern proving the substrate scales to interesting use cases

Building a parallel "task" registry would have duplicated this. Instead, Tasks delegates: the workflow IS the task, the process-id IS the task-id, the state IS the status.

### Per ADR B.1.10

The Sandbar MCP Server Design ADR ([decision B.1.10](https://github.com/danlentz/claude/blob/master/memory/decisions/sandbar_mcp_server_design_2026_05_12.md)) names this composition explicitly:

> The Tasks primitive composes naturally with Sandbar's workflow substrate. A long-running tool-call returns a TASK HANDLE instead of immediate content; the client polls `tasks/get <handle>` for status; receives the terminal result when the workflow process reaches a terminal state.

### Layer-targeting discipline

`sandbar.mcp.tasks` follows the same discipline as the rest of `sandbar.mcp.*` — only `workflow/*` abstractions; never raw `datomic.api`. When `handle-cancel` first needed cancellation semantics, the abstraction didn't expose them. Rather than bypass with raw `d/transact`, Stage C.7.4 landed `workflow/cancel-process!` and `workflow/can-cancel?` upstream. The MCP layer composes with the (now-richer) workflow abstraction.

### What's not yet here

| Capability | Status | Stage |
|------------|--------|-------|
| Progress notifications on state transitions | Initial broadcast only | C.7.1 (planned) |
| Terminal-success vs terminal-failure status refinement | Both map to `"completed"` | C.7.2 (planned) |
| Auto-dispatch `tools/call` → Tasks based on expected duration | Manual opt-in via `start-task!` | C.7.3 (planned) |
| Task pause/resume | Not modeled | Future |
| Task chaining (one task triggers another) | Manual via workflow design | Future |

## See Also

- **[doc/mcp-server.md](mcp-server.md)** — overall MCP server surface; Tasks slot into the larger model
- **[doc/workflow.md](workflow.md)** — workflow definitions, processes, transitions, `cancel-process!`
- **[doc/auth.md](auth.md)** — authentication (Bearer tokens reuse the same path as REST API keys)
- **[Sandbar MCP Server Design ADR](https://github.com/danlentz/claude/blob/master/memory/decisions/sandbar_mcp_server_design_2026_05_12.md)** — the Stage B architectural decisions, including B.1.10 Tasks composition
- **[Layer-targeting discipline](https://github.com/danlentz/claude/blob/master/memory/interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md)** — why Tasks compose with `workflow/*` rather than touching Datomic directly
- **[MCP Tasks spec](https://modelcontextprotocol.io/specification/server/tasks)** — experimental Tasks primitive in the MCP 2025-11-25 specification
