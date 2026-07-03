# REST API Reference

> Layer 4 — mechanical reference for every REST endpoint Sandbar exposes.  Organized by resource group.  For practical client patterns see [`doc/guides/writing-a-rest-client.md`](../guides/writing-a-rest-client.md).

## Base URL

```
http://<host>:<port>/api
```

Default port is `:8080`; configurable via `:http-port` in `config/config.edn`.

## Content negotiation

| `Accept` header              | Response shape                                  |
|------------------------------|-------------------------------------------------|
| `application/edn` (default)  | Clojure EDN — keywords, sets, custom types     |
| `application/json`           | Standard JSON                                   |
| `application/transit+json`   | Transit-encoded JSON                            |

Request bodies (for POST / PATCH) use the same negotiation via `Content-Type`.

## URL conventions

Clojure keywords map directly to URL paths via slash.  Special characters are percent-encoded:

| Keyword              | URL fragment            |
|----------------------|-------------------------|
| `:dt/Resource`       | `dt/Resource`           |
| `:model/User`        | `model/User`            |
| `:db.type/string`    | `db.type/string`        |
| `:user/active?`      | `user/active%3F`        |

## Authentication

```
Authorization: Bearer <service-account-token>
```

Read endpoints are typically open; write and sensitive-read endpoints require a token.  See [`auth.md`](../auth.md) for token issuance.

## HTTP status codes

| Status                | Meaning                                                  |
|-----------------------|----------------------------------------------------------|
| `200 OK`              | Successful read or update                                |
| `201 Created`         | Successful resource creation                             |
| `204 No Content`      | Successful delete or transition                          |
| `400 Bad Request`     | Malformed input                                          |
| `401 Unauthorized`    | Missing or invalid bearer token                          |
| `403 Forbidden`       | Authenticated but not permitted                          |
| `404 Not Found`       | Target class / property / entity doesn't exist           |
| `409 Conflict`        | Constraint violation (uniqueness, etc.)                  |
| `422 Unprocessable`   | Validation failed; response body has structured `:errors`|
| `500 Internal Error`  | Server-side error                                        |

## Status endpoints

### `GET /api/status`

Returns system health and version.

```clojure
{:time #inst "2026-05-13T16:30:00.000Z"
 :clojure {:major 1 :minor 12 :incremental 4}}
```

## Schema endpoints

### `GET /api/store/schema`

Returns an overview of the entire schema.

```clojure
{:schema {:class-count 42
          :property-count 58
          :classes [:db.type/bigdec ... :model/User ...]
          :properties [:db/cardinality ... :user/login ...]}}
```

## Class endpoints

### `GET /api/store/classes`

Lists every class ident.

```clojure
{:count 42
 :classes [:dt/Class :dt/Property :dt/Resource ...]}
```

### `GET /api/store/classes/:ns/:name`

Full class description.

```clojure
{:class :model/User
 :description {:db/doc "..." :db/ident :model/User ...}
 :abstract? false
 :context "model"
 :label "User"
 :parents [:dt/Ref]
 :ancestors [:dt/Ref :dt/Resource]
 :slots [...]
 :direct-slots [...]
 :required-slots [...]
 :direct-subclasses []
 :all-subclasses []}
```

### `GET /api/store/classes/:ns/:name/instances`

All instances (including subclass instances).

### `GET /api/store/classes/:ns/:name/instances/direct`

Direct instances only — no subclass instances.

### `GET /api/store/classes/:ns/:name/slots`

All effective slots (inherited + direct).

### `GET /api/store/classes/:ns/:name/slots/direct`

Direct slots only.

### `GET /api/store/classes/:ns/:name/slots/required`

Required slots only.

### `GET /api/store/classes/:ns/:name/hierarchy`

Full class hierarchy — parents + ancestors + direct subclasses + transitive subclasses.

### `GET /api/store/classes/:ns/:name/subclasses`

All transitive subclasses.

### `GET /api/store/classes/:ns/:name/subclasses/direct`

Direct subclasses only.

### `GET /api/store/classes/:ns/:name/ancestors`

All ancestor classes.

### `GET /api/store/classes/:ns/:name/parents`

Direct parents only.

### `GET /api/store/classes/:ns/:name/validate`

Runs validation against every instance.

```clojure
{:class :model/User
 :total 1247
 :valid 1240
 :invalid 7
 :errors [{:entity 12345 :errors [...]} ...]}
```

For large classes, prefer the workflow-backed MCP verb `sandbar.validation.start` — it's cancellable and produces a queryable history.

### `POST /api/store/classes/:ns/:name/instances`

Create a new instance of the named class.

Request body:

```json
{
  "event.booking/title": "Weekly Sync",
  "event.booking/starts-at": "2026-05-14T15:00:00Z",
  "event.booking/owner": "model/user.alice"
}
```

Successful response (`201 Created`):

```clojure
{:entity {:db/id 12345
          :dt/type :event/Booking
          ...}}
```

Validation failure (`422`):

```clojure
{:errors [{:slot :event.booking/owner :error :missing-required}
          ...]}
```

## Property endpoints

### `GET /api/store/properties`

Lists every property ident.

### `GET /api/store/properties/:ns/:name`

Full property description.

```clojure
{:property :user/login
 :description {:db/ident :user/login
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one
               :dt/type :dt/Property
               :dt/domain :model/User
               :dt/range :db.type/string
               :db/doc "User login identifier"}
 :domain :model/User
 :range :db.type/string
 :cardinality :db.cardinality/one
 :cardinality-one? true
 :cardinality-many? false}
```

### `GET /api/store/properties/:ns/:name/domain`

Returns just the property's domain class.

### `GET /api/store/properties/:ns/:name/range`

Returns just the property's range type.

## Entity endpoints

### `GET /api/store/entities/:ns/:name`

Look up an entity by namespaced `:db/ident`.

```clojure
{:entity {:db/id 12345
          :db/ident :model/user.alice
          :dt/type :model/User
          :user/login "alice"
          ...}}
```

### `GET /api/store/entities/:ns/:name/validate`

Validates a single entity.  Returns `nil` (200) if valid, or the error map (422).

### `PATCH /api/store/entities/:ns/:name`

Partial update — only the provided slots are written; others remain untouched.

Request body:

```json
{"event.booking/location": "Conference Room A"}
```

Validation runs after the merge; returns `200` with the updated entity, or `422` with errors.

## Type predicate endpoints

### `GET /api/store/types/instance-of/:class-ns/:class-name/:entity-ns/:entity-name`

Tests whether the named entity is an instance of the named class.

```clojure
{:class :model/User
 :entity :model/user.alice
 :instance-of? true}
```

### `GET /api/store/types/subclass-of/:parent-ns/:parent-name/:child-ns/:child-name`

Tests whether `:child-ns/:child-name` is a subclass of `:parent-ns/:parent-name`.

```clojure
{:parent :dt/Resource
 :child :model/User
 :subclass-of? true}
```

## Aggregation endpoints

The four-axis retrieval surface's aggregation axis.  See [`aggregation.md`](../concepts/aggregation.md) for theory.

### `GET /api/aggregate/count`
Query params:
- `class` (required) — class ident (e.g., `:mm/Memory`)
- `where` (optional) — EDN-string Datalog clauses

Returns `{:count <int>}`.

```http
GET /api/aggregate/count?class=:mm/Memory&where=%5B%5B%3Fe%20%3Amm.memory%2Fmemory-type%20%3Adecision%5D%5D
```

### `GET /api/aggregate/group-by`
Query params:
- `class` (required) — class ident
- `group-by` (required) — slot ident to group by
- `where` (optional) — EDN-string Datalog clauses

Returns `{:groups {value count} :total <int>}`.

### `GET /api/aggregate/rank-by`
Query params:
- `class` (required) — class ident
- `rank-by` (required) — `:degree` / `:backlink-density` / `:recency` / `:freshness`
- `limit` (optional, default 20) — max returned hits
- `temporal-slot` (required for `:recency` / `:freshness`) — slot ident carrying the temporal value

Returns `{:hits [{:entity ... :rank-score ...}] :total <int> :returned <int>}`.

## Navigation endpoints

The four-axis retrieval surface's navigation axis — path-grammar walker.  See [`navigation.md`](../concepts/navigation.md) for the surface and [`path-grammar.md`](../concepts/path-grammar.md) for the algebra.

### `GET /api/navigate/path`
Query params:
- `from` (required) — seed entity ident
- `via` (required) — EDN-string path expression
- `limit` (optional, default 0 = no cap) — max returned entities
- `include` (optional) — comma-separated projection opts; supports `paths` (deferred surfacing)

Returns `{:reachable [<entity-map>...] :total <int> :returned <int>}`.

**Important — URL-encoding `+` in path expressions:** `+` in a query string decodes as space (per `application/x-www-form-urlencoded`).  Use `%2B` for the literal `+` in `:REP+`.

Example:
```http
GET /api/navigate/path?from=:dt/Property&via=%5B:REP%2B%20:dt/subclass-of%5D&limit=50
```

decodes to `via=[:REP+ :dt/subclass-of]`.

## MCP endpoints

The MCP transport is served at `/mcp` (not under `/api/store/*`).  See [`doc/api/mcp-verbs.md`](mcp-verbs.md) for the full MCP verb catalog.

| Method                          | Endpoint    | Description                                |
|---------------------------------|-------------|--------------------------------------------|
| `POST`                          | `/mcp`      | JSON-RPC envelope; reads result            |
| `POST` (with `Accept: text/event-stream`) | `/mcp` | Returns the JSON-RPC response as a single SSE `data:` frame (when SSE is negotiated). Notifications subscribe via `GET /mcp/sse`. |

## Pagination

For endpoints returning collections (`/instances`, `/properties`), pagination is via standard query parameters:

| Param              | Meaning                                    |
|--------------------|--------------------------------------------|
| `?limit=N`         | Return at most N items                     |
| `?offset=N`        | Skip the first N items                     |
| `?after=<ident>`   | Cursor-style pagination (preferred over offset) |

Without parameters, endpoints return all items.  For very large classes, always paginate.

## Filtering

Some endpoints accept filter parameters.  When present, the response shape is the same but filtered:

| Param                  | Endpoint                                              | Meaning                              |
|------------------------|-------------------------------------------------------|--------------------------------------|
| `?since=<inst>`        | `/api/store/classes/:ns/:name/instances`              | Only modifications since timestamp   |
| `?type=<keyword>`      | various                                                | Filter by specific subclass          |

## Error response shape

All error responses (4xx and 5xx) carry a structured body:

```clojure
{:error {:code "validation-failed"   ; stable string code
         :message "Required slot missing"
         :data {:slot :user/login :error :missing-required}}}
```

The `:code` is stable across releases and suitable for branching client logic.  The `:message` is human-readable and may change.

## Versioning

The REST surface is versioned implicitly — there is no `/v1/` prefix today.  Breaking changes will introduce a `/v2/` namespace alongside `/api/`.  Until 1.0.0, treat the surface as evolving; pin to a release tag if stability matters.

## See also

- [`doc/guides/writing-a-rest-client.md`](../guides/writing-a-rest-client.md) — practical patterns
- [`doc/api/mcp-verbs.md`](mcp-verbs.md) — the MCP alternative
- [`doc/api/dt-star.md`](dt-star.md) — the in-process API
- [`auth.md`](../auth.md) — bearer-token issuance
