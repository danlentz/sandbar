# Event System

Sandbar includes a comprehensive event logging system that persists events to Datomic. Events are first-class entities with full metamodel support, enabling powerful queries, correlation tracking, and historical analysis.

## Overview

The event system provides:

- **Typed events** - Events are instances of metamodel classes with validated properties
- **Automatic HTTP logging** - Pedestal interceptor logs all API requests
- **Correlation tracking** - Link related events across a request or workflow
- **Programmatic API** - Simple functions for logging from anywhere in your code
- **REST API** - Query and create events via HTTP
- **Queryable history** - Events are Datomic entities, queryable with Datalog

## Event Class Hierarchy

```
                     dt/Event [abstract]
                         |
         ________________|________________
        |                |                |
  event/ServerEvent  event/UserEvent  event/SystemEvent
        |
    ____|____________________
   |           |             |
event/      event/       event/
HttpRequest  ApiCall     Transaction
```

### Event Types

| Class | Description |
|-------|-------------|
| `dt/Event` | Abstract base class for all events |
| `event/ServerEvent` | Server-side processing events (logs, metrics) |
| `event/UserEvent` | User-triggered actions (login, logout, profile updates) |
| `event/SystemEvent` | System lifecycle events (startup, shutdown, scheduled tasks) |
| `event/HttpRequest` | HTTP request/response cycles |
| `event/ApiCall` | API endpoint invocations |
| `event/Transaction` | Datomic transaction events |

## Event Properties

### Common Properties (dt/Event)

| Property | Type | Description |
|----------|------|-------------|
| `:event/timestamp` | instant | When the event occurred |
| `:event/name` | string | Human-readable name or title |
| `:event/description` | string | Detailed description |
| `:event/kind` | keyword | Event type (e.g., `:http/request`, `:user/login`) |
| `:event/actor` | ref | Entity that triggered the event |
| `:event/target` | ref | Entity affected by the event |
| `:event/duration` | long | Duration in milliseconds |
| `:event/status` | keyword | Outcome (`:success`, `:failure`, `:pending`) |
| `:event/tags` | keyword* | Tags for categorization (cardinality many) |
| `:event/correlation-id` | uuid | UUID for correlating related events |
| `:event/parent` | ref | Parent event for hierarchical tracking |

### Server Event Properties (event/ServerEvent)

| Property | Type | Description |
|----------|------|-------------|
| `:event/level` | keyword | Severity (`:trace`, `:debug`, `:info`, `:warn`, `:error`, `:fatal`) |
| `:event/namespace` | string | Source namespace or component |
| `:event/thread` | string | Thread name |
| `:event/exception` | string | Exception message for errors |
| `:event/stacktrace` | string | Stack trace for errors |

### HTTP Request Properties (event/HttpRequest)

| Property | Type | Description |
|----------|------|-------------|
| `:http/method` | keyword | HTTP method (`:get`, `:post`, `:put`, `:delete`) |
| `:http/path` | string | Request URI path |
| `:http/query-string` | string | Query parameters |
| `:http/status-code` | long | Response status code |
| `:http/content-type` | string | Content type |
| `:http/remote-addr` | string | Client IP address |
| `:http/user-agent` | string | Client user agent |
| `:http/request-size` | long | Request body size in bytes |
| `:http/response-size` | long | Response body size in bytes |

### API Call Properties (event/ApiCall)

| Property | Type | Description |
|----------|------|-------------|
| `:api/endpoint` | keyword | API endpoint identifier |
| `:api/handler` | symbol | Handler function |
| `:api/params` | string | Request parameters (EDN string) |

### Transaction Properties (event/Transaction)

| Property | Type | Description |
|----------|------|-------------|
| `:tx/id` | long | Datomic transaction ID |
| `:tx/datom-count` | long | Number of datoms |
| `:tx/entities-affected` | long | Number of entities modified |

## Programmatic Logging API

The `sandbar.util.event` namespace provides functions for logging events from your code.

```clojure
(require '[sandbar.util.event :as event])
```

### Basic Logging

```clojure
;; Log a simple server event
(event/log! :info "User logged in")
(event/log! :warn "Cache miss" {:event/tags #{:performance}})
(event/log! :error "Database timeout" {:event/status :failure})
```

### Typed Event Logging

```clojure
;; Log any event type with full control
(event/log-event! :event/ServerEvent
  {:event/name "Batch job completed"
   :event/level :info
   :event/duration 5000
   :event/status :success})

;; Convenience functions for specific event types
(event/log-http! {:http/method :get
                  :http/path "/api/users"
                  :http/status-code 200
                  :event/duration 42})

(event/log-error! "Operation failed" ex)  ; Captures exception details

(event/log-api! {:api/endpoint :store/get-class
                 :api/handler 'sandbar.api.store/get-class
                 :event/duration 15})

(event/log-transaction! {:tx/id 12345
                         :tx/datom-count 10
                         :tx/entities-affected 3})

(event/log-user! {:event/name "Profile updated"
                  :event/kind :user/profile-update
                  :event/actor user-id})

(event/log-system! {:event/name "Server startup"
                    :event/kind :system/startup
                    :event/status :success})
```

## Pedestal Interceptors

### log-request

Logs all HTTP requests to the event database. Records timing, status codes, and request details.

```clojure
(require '[sandbar.util.event :as event])

;; In your routes
(def routes
  `[[["/" ^:interceptors [event/log-request ...]
      ["/api" ...]
      ...]]])
```

The interceptor:
- Records start time on enter
- Generates a correlation ID if not present
- Logs an `event/HttpRequest` on leave with full request/response details
- Captures exceptions with stack traces on error

### log-request-minimal

Lightweight alternative that only logs errors and slow requests:

```clojure
["/high-traffic" ^:interceptors [event/log-request-minimal]
  ...]
```

Logs requests that:
- Result in 5xx status codes
- Take longer than 1000ms

### suppress-event-logging

Suppresses logging for specific routes:

```clojure
["/health" {:get health-check} ^:interceptors [event/suppress-event-logging]]
```

Note: Requests to `/api/events` are automatically excluded from logging to prevent infinite loops.

## Correlation ID

Correlation IDs link related events across a request lifecycle or distributed workflow. This is essential for debugging and tracing.

### Automatic Correlation

The `log-request` interceptor automatically generates a UUID correlation ID for each request. All events logged during request processing share this ID.

```clojure
;; Query all events from a single request
(d/q '[:find ?e
       :in $ ?cid
       :where [?e :event/correlation-id ?cid]]
     (db) #uuid "550e8400-e29b-41d4-a716-446655440000")
```

### Manual Correlation

For background jobs or workflows spanning multiple requests, use explicit correlation:

```clojure
(require '[sandbar.util.event :as event])
(import '[java.util UUID])

;; Option 1: Include correlation ID in event data
(let [workflow-id (UUID/randomUUID)]
  (event/log! :info "Starting batch import" {:event/correlation-id workflow-id})
  (doseq [record records]
    (process-record record)
    (event/log! :debug "Processed record" {:event/correlation-id workflow-id}))
  (event/log! :info "Batch import complete" {:event/correlation-id workflow-id}))

;; Option 2: Use dynamic binding
(event/with-correlation (UUID/randomUUID)
  (event/log-with-correlation! :info "Step 1")
  (do-something)
  (event/log-with-correlation! :info "Step 2")
  (do-something-else)
  (event/log-with-correlation! :info "Step 3"))
```

### Querying Correlated Events

Via REST API:

```bash
GET /api/events/correlation/550e8400-e29b-41d4-a716-446655440000
```

Via Datalog:

```clojure
;; Find all events in a correlation chain, ordered by time
(d/q '[:find ?e ?ts ?name
       :in $ ?cid
       :where
       [?e :event/correlation-id ?cid]
       [?e :event/timestamp ?ts]
       [?e :event/name ?name]]
     (db) correlation-id)
```

### Hierarchical Events

For nested operations, use `:event/parent` to create event trees:

```clojure
(let [parent (event/log! :info "Processing order")
      parent-id (:db/id parent)]
  (event/log! :debug "Validating items" {:event/parent parent-id})
  (event/log! :debug "Calculating totals" {:event/parent parent-id})
  (event/log! :debug "Charging payment" {:event/parent parent-id}))
```

## REST API

### Create Events

```bash
# Create event with type in body
POST /api/events
Content-Type: application/edn

{:dt/type :event/ServerEvent
 :event/name "Manual event"
 :event/level :info}

# Create typed events directly
POST /api/events/server   # ServerEvent
POST /api/events/user     # UserEvent
POST /api/events/system   # SystemEvent
POST /api/events/http     # HttpRequest
POST /api/events/api      # ApiCall
POST /api/events/transaction  # Transaction
```

### Query Events

```bash
# List recent events
GET /api/events

# With filters
GET /api/events?level=error
GET /api/events?type=event/HttpRequest
GET /api/events?status=failure
GET /api/events?since=2024-01-01T00:00:00Z
GET /api/events?until=2024-01-02T00:00:00Z
GET /api/events?limit=50

# Get specific event
GET /api/events/17592186045678

# Get correlated events
GET /api/events/correlation/550e8400-e29b-41d4-a716-446655440000
```

### Example Response

```clojure
{:count 3
 :limit 100
 :filters {:level :error}
 :events [{:db/id 17592186045678
           :dt/type :event/ServerEvent
           :event/name "Database connection failed"
           :event/level :error
           :event/status :failure
           :event/timestamp #inst "2024-01-15T10:30:00.000Z"
           :event/namespace "sandbar.db.datomic"
           :event/exception "java.sql.SQLException: Connection refused"}
          ...]}
```

## Schema

The event schema is defined in `schema/event.edn` and loaded automatically when `:event` is included in the `:required-schema` configuration.

```clojure
;; config/config.edn
{:datomic {:uri "datomic:dev://localhost:4334/sandbar"}
 :required-schema [:meta :literal :ref :user :event]}
```
