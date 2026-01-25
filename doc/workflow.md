# Workflow, Process, and Job API

This document describes the REST API for managing workflows, processes, and background jobs in Sandbar.

## Table of Contents

- [Overview](#overview)
- [Workflows API](#workflows-api)
- [Processes API](#processes-api)
- [Jobs API](#jobs-api)

---

## Overview

Sandbar provides a workflow engine for managing state machines and a job system for background task execution.

### Concepts

- **Workflow Definition**: A state machine template with states and transitions
- **Process**: A running instance of a workflow attached to a subject entity
- **State**: A named position in the workflow (can be initial, terminal, or intermediate)
- **Transition**: A valid state change with optional guards and side effects
- **Job**: A background task scheduled for execution

### Authentication

All endpoints require authentication. Include your session cookie or authentication header with each request.

---

## Workflows API

Workflow definitions describe state machines that can be instantiated as processes.

### List Workflow Definitions

```
GET /api/workflows
```

**Response:**

```json
{
  "count": 2,
  "workflows": [
    {
      "id": 17592186045421,
      "name": ":workflow/order-fulfillment",
      "version": 1,
      "states": [
        {"id": 17592186045422, "name": ":order/pending", "label": "Pending", "initial?": true, "terminal?": false},
        {"id": 17592186045423, "name": ":order/confirmed", "label": "Confirmed", "initial?": false, "terminal?": false},
        {"id": 17592186045424, "name": ":order/shipped", "label": "Shipped", "initial?": false, "terminal?": false},
        {"id": 17592186045425, "name": ":order/delivered", "label": "Delivered", "initial?": false, "terminal?": true},
        {"id": 17592186045426, "name": ":order/cancelled", "label": "Cancelled", "initial?": false, "terminal?": true}
      ],
      "transitions": [
        {"id": 17592186045427, "name": ":confirm", "from-state": 17592186045422, "to-state": 17592186045423, "requires-reason?": false},
        {"id": 17592186045428, "name": ":ship", "from-state": 17592186045423, "to-state": 17592186045424, "requires-reason?": false},
        {"id": 17592186045429, "name": ":deliver", "from-state": 17592186045424, "to-state": 17592186045425, "requires-reason?": false},
        {"id": 17592186045430, "name": ":cancel", "from-state": 17592186045422, "to-state": 17592186045426, "requires-reason?": true}
      ],
      "initial-state": {"id": 17592186045422, "name": ":order/pending", "label": "Pending", "initial?": true, "terminal?": false},
      "terminal-states": [
        {"id": 17592186045425, "name": ":order/delivered", "label": "Delivered", "initial?": false, "terminal?": true},
        {"id": 17592186045426, "name": ":order/cancelled", "label": "Cancelled", "initial?": false, "terminal?": true}
      ]
    }
  ]
}
```

### Get Workflow Definition

```
GET /api/workflows/:ns/:name
```

**Example:**

```
GET /api/workflows/workflow/order-fulfillment
```

**Response:**

```json
{
  "workflow": {
    "id": 17592186045421,
    "name": ":workflow/order-fulfillment",
    "version": 1,
    "states": [...],
    "transitions": [...],
    "initial-state": {...},
    "terminal-states": [...]
  }
}
```

### Create Workflow Definition

```
POST /api/workflows
Content-Type: application/edn
```

**Request Body:**

```clojure
{:name :workflow/order-fulfillment
 :version 1
 :states [{:name :order/pending :label "Pending" :initial? true}
          {:name :order/confirmed :label "Confirmed"}
          {:name :order/shipped :label "Shipped"}
          {:name :order/delivered :label "Delivered" :terminal? true}
          {:name :order/cancelled :label "Cancelled" :terminal? true}]
 :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
               {:name :ship :from :order/confirmed :to :order/shipped}
               {:name :deliver :from :order/shipped :to :order/delivered}
               {:name :cancel :from :order/pending :to :order/cancelled :requires-reason? true}
               {:name :cancel :from :order/confirmed :to :order/cancelled :requires-reason? true}]}
```

**Response (201 Created):**

```json
{
  "created": true,
  "workflow": {
    "id": 17592186045421,
    "name": ":workflow/order-fulfillment",
    "version": 1,
    ...
  }
}
```

### Get Workflow Statistics

```
GET /api/workflows/:ns/:name/stats
```

**Example:**

```
GET /api/workflows/workflow/order-fulfillment/stats
```

**Response:**

```json
{
  "workflow": ":workflow/order-fulfillment",
  "stats": {
    "by-state": {
      ":order/pending": 5,
      ":order/confirmed": 3,
      ":order/shipped": 2,
      ":order/delivered": 15,
      ":order/cancelled": 1
    },
    "completed": 16,
    "active": 10,
    "total": 26
  }
}
```

---

## Processes API

Processes are running instances of workflows attached to subject entities.

### List Processes

```
GET /api/processes
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `workflow` | keyword | Filter by workflow name (e.g., `workflow/order-fulfillment`) |
| `state` | keyword | Filter by current state (e.g., `order/pending`) |
| `active` | boolean | Only active (non-completed) processes |
| `completed` | boolean | Only completed processes |
| `subject` | long | Filter by subject entity ID |
| `limit` | long | Maximum results (default: 100) |

**Example:**

```
GET /api/processes?workflow=workflow/order-fulfillment&active=true&limit=50
```

**Response:**

```json
{
  "count": 10,
  "limit": 50,
  "filters": {
    "workflow": ":workflow/order-fulfillment",
    "active": true
  },
  "processes": [
    {
      "id": 17592186045500,
      "workflow": {
        "id": 17592186045421,
        "name": ":workflow/order-fulfillment"
      },
      "subject-id": 17592186045450,
      "current-state": {
        "id": 17592186045423,
        "name": ":order/confirmed",
        "label": "Confirmed",
        "initial?": false,
        "terminal?": false
      },
      "started-at": "2024-01-15T10:30:00.000Z",
      "completed-at": null,
      "completed?": false,
      "in-terminal-state?": false,
      "data": {"order-number": "ORD-001"}
    }
  ]
}
```

### Get Process

```
GET /api/processes/:id
```

**Example:**

```
GET /api/processes/17592186045500
```

**Response:**

```json
{
  "process": {
    "id": 17592186045500,
    "workflow": {
      "id": 17592186045421,
      "name": ":workflow/order-fulfillment"
    },
    "subject-id": 17592186045450,
    "current-state": {
      "id": 17592186045423,
      "name": ":order/confirmed",
      "label": "Confirmed",
      "initial?": false,
      "terminal?": false
    },
    "started-at": "2024-01-15T10:30:00.000Z",
    "completed-at": null,
    "completed?": false,
    "in-terminal-state?": false,
    "data": {"order-number": "ORD-001"}
  },
  "available-transitions": [
    {"name": ":ship", "requires-reason?": false},
    {"name": ":cancel", "requires-reason?": true}
  ]
}
```

### Start Process

```
POST /api/processes
Content-Type: application/edn
```

**Request Body:**

```clojure
{:workflow :workflow/order-fulfillment
 :subject 17592186045450
 :data {:order-number "ORD-001"
        :customer-name "Zorp"}}
```

**Response (201 Created):**

```json
{
  "created": true,
  "process": {
    "id": 17592186045500,
    "workflow": {
      "id": 17592186045421,
      "name": ":workflow/order-fulfillment"
    },
    "subject-id": 17592186045450,
    "current-state": {
      "id": 17592186045422,
      "name": ":order/pending",
      "label": "Pending",
      "initial?": true,
      "terminal?": false
    },
    "started-at": "2024-01-15T10:30:00.000Z",
    "completed-at": null,
    "completed?": false,
    "in-terminal-state?": false,
    "data": {"order-number": "ORD-001", "customer-name": "Zorp"}
  }
}
```

### Get Available Transitions

```
GET /api/processes/:id/transitions
```

**Example:**

```
GET /api/processes/17592186045500/transitions
```

**Response:**

```json
{
  "process-id": 17592186045500,
  "current-state": {
    "id": 17592186045423,
    "name": ":order/confirmed",
    "label": "Confirmed",
    "initial?": false,
    "terminal?": false
  },
  "transitions": [
    {
      "name": ":ship",
      "to-state": {
        "id": 17592186045424,
        "name": ":order/shipped",
        "label": "Shipped",
        "initial?": false,
        "terminal?": false
      },
      "requires-reason?": false,
      "has-guard?": false
    },
    {
      "name": ":cancel",
      "to-state": {
        "id": 17592186045426,
        "name": ":order/cancelled",
        "label": "Cancelled",
        "initial?": false,
        "terminal?": true
      },
      "requires-reason?": true,
      "has-guard?": false
    }
  ]
}
```

### Execute Transition

```
POST /api/processes/:id/transition
Content-Type: application/edn
```

**Request Body:**

```clojure
{:transition :ship
 :reason "Package ready for carrier pickup"
 :context {:tracking-number "1Z999AA10123456784"}}
```

**Response:**

```json
{
  "success": true,
  "process": {
    "id": 17592186045500,
    "workflow": {
      "id": 17592186045421,
      "name": ":workflow/order-fulfillment"
    },
    "subject-id": 17592186045450,
    "current-state": {
      "id": 17592186045424,
      "name": ":order/shipped",
      "label": "Shipped",
      "initial?": false,
      "terminal?": false
    },
    "started-at": "2024-01-15T10:30:00.000Z",
    "completed-at": null,
    "completed?": false,
    "in-terminal-state?": false,
    "data": {"order-number": "ORD-001"}
  },
  "new-state": {
    "id": 17592186045424,
    "name": ":order/shipped",
    "label": "Shipped",
    "initial?": false,
    "terminal?": false
  },
  "completed?": false
}
```

**Error Response (transition not available):**

```json
{
  "error": "Transition not available",
  "transition": ":deliver",
  "current-state": ":order/confirmed",
  "available": [":ship", ":cancel"]
}
```

### Get Process History

```
GET /api/processes/:id/history
```

**Example:**

```
GET /api/processes/17592186045500/history
```

**Response:**

```json
{
  "process-id": 17592186045500,
  "count": 3,
  "history": [
    {
      "action": ":confirm",
      "from": ":order/pending",
      "to": ":order/confirmed",
      "timestamp": "2024-01-15T10:35:00.000Z",
      "actor": 17592186045100,
      "reason": null
    },
    {
      "action": ":ship",
      "from": ":order/confirmed",
      "to": ":order/shipped",
      "timestamp": "2024-01-16T14:20:00.000Z",
      "actor": 17592186045100,
      "reason": "Package ready for carrier pickup"
    },
    {
      "action": ":deliver",
      "from": ":order/shipped",
      "to": ":order/delivered",
      "timestamp": "2024-01-18T09:15:00.000Z",
      "actor": 17592186045100,
      "reason": "Delivered to customer"
    }
  ]
}
```

---

## Jobs API

Background jobs for scheduled, triggered, and recurring task execution.

### Job Status Lifecycle

```
:pending -> :running -> :completed
               |
               +-----> :failed
               |
               +-----> :cancelled

:paused (can be resumed to :pending)
```

### List Jobs

```
GET /api/jobs
```

**Query Parameters:**

| Parameter | Type | Description |
|-----------|------|-------------|
| `status` | keyword | Filter by status (`pending`, `running`, `completed`, `failed`, `cancelled`, `paused`) |
| `queue` | keyword | Filter by queue name |
| `tags` | string | Filter by tags (comma-separated) |
| `limit` | long | Maximum results (default: 100) |

**Example:**

```
GET /api/jobs?status=pending&queue=email&limit=50
```

**Response:**

```json
{
  "count": 5,
  "limit": 50,
  "filters": {
    "status": ":pending",
    "queue": ":email"
  },
  "jobs": [
    {
      "id": 17592186045600,
      "name": "Send welcome email",
      "handler": "myapp.jobs/send-email",
      "status": ":pending",
      "priority": 5,
      "queue": ":email",
      "attempt-count": 0,
      "max-attempts": 3,
      "created-at": "2024-01-15T10:00:00.000Z",
      "run-at": "2024-01-15T10:05:00.000Z",
      "next-run": null,
      "last-run": null,
      "run-count": null,
      "tags": [":email", ":welcome"],
      "error": null
    }
  ]
}
```

### Get Job

```
GET /api/jobs/:id
```

**Example:**

```
GET /api/jobs/17592186045600
```

**Response:**

```json
{
  "job": {
    "id": 17592186045600,
    "name": "Send welcome email",
    "handler": "myapp.jobs/send-email",
    "status": ":pending",
    "priority": 5,
    "queue": ":email",
    "attempt-count": 0,
    "max-attempts": 3,
    "created-at": "2024-01-15T10:00:00.000Z",
    "run-at": "2024-01-15T10:05:00.000Z",
    "next-run": null,
    "last-run": null,
    "run-count": null,
    "tags": [":email", ":welcome"],
    "error": null
  },
  "payload": {
    "user-id": 123,
    "template": ":welcome"
  }
}
```

### Get Job Statistics

```
GET /api/jobs/stats
```

**Response:**

```json
{
  "jobs": {
    "by-status": {
      ":pending": 12,
      ":running": 2,
      ":completed": 156,
      ":failed": 3
    },
    "pending-by-queue": {
      ":default": 5,
      ":email": 4,
      ":reports": 3
    },
    "total": 173
  },
  "executions": {
    "by-status": {
      ":success": {"count": 156, "avg-duration-ms": 245.5},
      ":failure": {"count": 3, "avg-duration-ms": 1523.2}
    },
    "total": 159
  }
}
```

### Get Due Jobs

```
GET /api/jobs/due
```

Returns scheduled jobs that are ready for execution (run-at <= now).

**Response:**

```json
{
  "count": 3,
  "jobs": [
    {
      "id": 17592186045600,
      "name": "Send welcome email",
      "handler": "myapp.jobs/send-email",
      "status": ":pending",
      "run-at": "2024-01-15T10:05:00.000Z",
      ...
    }
  ]
}
```

### Get Running Jobs

```
GET /api/jobs/running
```

**Response:**

```json
{
  "count": 2,
  "jobs": [
    {
      "id": 17592186045601,
      "name": "Generate monthly report",
      "handler": "myapp.jobs/generate-report",
      "status": ":running",
      "attempt-count": 1,
      ...
    }
  ]
}
```

### Create Scheduled Job

```
POST /api/jobs
Content-Type: application/edn
```

**Request Body:**

```clojure
{:handler "myapp.jobs/send-email"
 :payload {:user-id 123 :template :welcome}
 :run-at "2024-01-15T10:05:00Z"   ; ISO-8601 timestamp
 :name "Send welcome email"
 :priority 5
 :queue :email
 :max-attempts 3
 :tags [:email :welcome]}
```

Or with delay instead of absolute time:

```clojure
{:handler "myapp.jobs/send-reminder"
 :payload {:user-id 123}
 :delay-ms 300000   ; 5 minutes from now
 :name "Send reminder"
 :queue :email}
```

**Response (201 Created):**

```json
{
  "created": true,
  "job": {
    "id": 17592186045600,
    "name": "Send welcome email",
    "handler": "myapp.jobs/send-email",
    "status": ":pending",
    "priority": 5,
    "queue": ":email",
    "attempt-count": 0,
    "max-attempts": 3,
    "created-at": "2024-01-15T10:00:00.000Z",
    "run-at": "2024-01-15T10:05:00.000Z",
    "tags": [":email", ":welcome"],
    "error": null
  }
}
```

### Create Triggered Job

```
POST /api/jobs/triggered
Content-Type: application/edn
```

Triggered jobs execute in response to events.

**Request Body:**

```clojure
{:handler "myapp.jobs/on-user-created"
 :trigger-event :user/created
 :payload {}
 :name "Handle new user registration"
 :queue :users}
```

**Response (201 Created):**

```json
{
  "created": true,
  "job": {
    "id": 17592186045610,
    "name": "Handle new user registration",
    "handler": "myapp.jobs/on-user-created",
    "status": ":pending",
    ...
  }
}
```

### Create Recurring Job

```
POST /api/jobs/recurring
Content-Type: application/edn
```

Recurring jobs run on a schedule.

**Request Body (with cron):**

```clojure
{:handler "myapp.jobs/daily-backup"
 :payload {:database "production"}
 :cron "0 2 * * *"   ; 2 AM daily
 :name "Daily database backup"
 :queue :maintenance
 :max-runs 365}
```

**Request Body (with interval):**

```clojure
{:handler "myapp.jobs/health-check"
 :payload {}
 :interval-ms 60000   ; Every minute
 :name "Health check"
 :queue :monitoring}
```

**Response (201 Created):**

```json
{
  "created": true,
  "job": {
    "id": 17592186045620,
    "name": "Daily database backup",
    "handler": "myapp.jobs/daily-backup",
    "status": ":pending",
    "next-run": "2024-01-16T02:00:00.000Z",
    "run-count": 0,
    ...
  }
}
```

### Cancel Job

```
POST /api/jobs/:id/cancel
```

Cancel a pending job. Only jobs in `:pending` status can be cancelled.

**Example:**

```
POST /api/jobs/17592186045600/cancel
```

**Response:**

```json
{
  "success": true,
  "job": {
    "id": 17592186045600,
    "name": "Send welcome email",
    "status": ":cancelled",
    ...
  }
}
```

**Error Response:**

```json
{
  "error": "Can only cancel pending jobs",
  "status": ":running"
}
```

### Pause Job

```
POST /api/jobs/:id/pause
```

Pause a pending or running job.

**Example:**

```
POST /api/jobs/17592186045620/pause
```

**Response:**

```json
{
  "success": true,
  "job": {
    "id": 17592186045620,
    "name": "Daily database backup",
    "status": ":paused",
    ...
  }
}
```

### Resume Job

```
POST /api/jobs/:id/resume
```

Resume a paused job.

**Example:**

```
POST /api/jobs/17592186045620/resume
```

**Response:**

```json
{
  "success": true,
  "job": {
    "id": 17592186045620,
    "name": "Daily database backup",
    "status": ":pending",
    ...
  }
}
```

### Execute Job Immediately

```
POST /api/jobs/:id/execute
```

Execute a job immediately (for testing/debugging). Job must be in `:pending` status.

**Example:**

```
POST /api/jobs/17592186045600/execute
```

**Response:**

```json
{
  "executed": true,
  "success": true,
  "result": {"emails-sent": 1},
  "error": null,
  "job": {
    "id": 17592186045600,
    "name": "Send welcome email",
    "status": ":completed",
    ...
  }
}
```

**Error Response (execution failed):**

```json
{
  "executed": true,
  "success": false,
  "result": null,
  "error": "SMTP connection refused",
  "job": {
    "id": 17592186045600,
    "name": "Send welcome email",
    "status": ":pending",
    "attempt-count": 1,
    "error": "SMTP connection refused",
    ...
  }
}
```

---

## Error Responses

All endpoints return standard HTTP status codes:

| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created |
| 400 | Bad Request (invalid parameters) |
| 401 | Unauthorized (not authenticated) |
| 403 | Forbidden (insufficient permissions) |
| 404 | Not Found |
| 409 | Conflict (e.g., workflow already exists, invalid state transition) |

Error response format:

```json
{
  "error": "Description of the error",
  "details": {...}
}
```

---

## Usage Examples

### Complete Order Workflow Example

```bash
# 1. Create the workflow definition
curl -X POST http://localhost:8080/api/workflows \
  -H "Content-Type: application/edn" \
  -d '{:name :workflow/order
       :states [{:name :order/pending :initial? true}
                {:name :order/confirmed}
                {:name :order/shipped}
                {:name :order/delivered :terminal? true}]
       :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
                     {:name :ship :from :order/confirmed :to :order/shipped}
                     {:name :deliver :from :order/shipped :to :order/delivered}]}'

# 2. Start a process for an order
curl -X POST http://localhost:8080/api/processes \
  -H "Content-Type: application/edn" \
  -d '{:workflow :workflow/order
       :subject 17592186045450
       :data {:order-number "ORD-001"}}'

# 3. Check available transitions
curl http://localhost:8080/api/processes/17592186045500/transitions

# 4. Execute transitions
curl -X POST http://localhost:8080/api/processes/17592186045500/transition \
  -H "Content-Type: application/edn" \
  -d '{:transition :confirm}'

curl -X POST http://localhost:8080/api/processes/17592186045500/transition \
  -H "Content-Type: application/edn" \
  -d '{:transition :ship :reason "Handed to carrier"}'

curl -X POST http://localhost:8080/api/processes/17592186045500/transition \
  -H "Content-Type: application/edn" \
  -d '{:transition :deliver}'

# 5. View process history
curl http://localhost:8080/api/processes/17592186045500/history
```

### Scheduled Job Example

```bash
# Schedule an email to be sent in 5 minutes
curl -X POST http://localhost:8080/api/jobs \
  -H "Content-Type: application/edn" \
  -d '{:handler "myapp.email/send-newsletter"
       :payload {:campaign-id 42}
       :delay-ms 300000
       :name "Send newsletter"
       :queue :email
       :priority 10}'

# Check job status
curl http://localhost:8080/api/jobs/17592186045600

# View all pending email jobs
curl "http://localhost:8080/api/jobs?queue=email&status=pending"
```
