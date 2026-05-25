# Writing a REST Client

> How to consume Sandbar over HTTP REST — the protocol shape, content negotiation, common operations, and patterns.  This is a *client-side* guide focused on practical usage; for the mechanical endpoint catalog see [`doc/api/http-rest.md`](../api/http-rest.md); for the AI-client alternative see [`writing-an-mcp-client.md`](writing-an-mcp-client.md).

## Why REST

REST is right when:

- The consumer is a traditional HTTP client (browser, mobile app, server-to-server integration).
- You want browser-rendered exploration (links / forms).
- You don't need reflection-driven invocation (the AI-client use case MCP is designed for).
- Content negotiation (EDN / JSON / Transit) matters to you.

For AI clients that prefer reflection-driven discovery, use MCP instead.  Both projections come from the same metamodel — no parallel state to keep in sync.

## Base URL

```
http://localhost:8080/api/
```

Two top-level resource groups:

- `/api/status` — health and version
- `/api/store/*` — metamodel and instance operations

## Authentication

Same bearer-token scheme as MCP:

```bash
curl http://localhost:8080/api/store/classes \
  -H "Authorization: Bearer $SANDBAR_TOKEN"
```

Endpoints that read are typically unauthenticated; endpoints that write or expose sensitive entities require a service-account token.  See [`auth.md`](../auth.md) for issuance.

## Content negotiation

```bash
# Default — EDN
curl http://localhost:8080/api/store/classes

# Request JSON
curl http://localhost:8080/api/store/classes \
  -H "Accept: application/json"

# Request Transit
curl http://localhost:8080/api/store/classes \
  -H "Accept: application/transit+json"
```

| Accept                       | Response shape                      |
|------------------------------|-------------------------------------|
| `application/edn` (default)  | Clojure EDN — preserves keywords, sets, custom types |
| `application/json`           | Standard JSON                       |
| `application/transit+json`   | Transit-encoded JSON (Cognitect)    |

## URL conventions

Clojure keywords map directly to URL paths:

| Clojure         | URL fragment   |
|-----------------|----------------|
| `:dt/Resource`  | `dt/Resource`  |
| `:model/User`   | `model/User`   |
| `:db.type/string` | `db.type/string` |

Special characters in keywords (`?`, `*`) are percent-encoded: `:user/active?` becomes `user/active%3F`.

## Walking the metamodel

```bash
# Health
curl http://localhost:8080/api/status

# All classes
curl http://localhost:8080/api/store/classes

# Schema summary
curl http://localhost:8080/api/store/schema

# One class
curl http://localhost:8080/api/store/classes/model/User

# Slots (effective — inherited + direct)
curl http://localhost:8080/api/store/classes/model/User/slots

# Direct slots only
curl http://localhost:8080/api/store/classes/model/User/slots/direct

# Required slots
curl http://localhost:8080/api/store/classes/model/User/slots/required

# Class hierarchy
curl http://localhost:8080/api/store/classes/model/User/hierarchy

# Direct subclasses
curl http://localhost:8080/api/store/classes/dt/Ref/subclasses
```

Example response for `/api/store/classes/model/User`:

```clojure
{:class :model/User
 :description {:db/doc "Application user accounts" ...}
 :abstract? false
 :context "model"
 :label "User"
 :parents [:dt/Ref]
 :ancestors [:dt/Ref :dt/Resource]
 :slots [:db/doc :db/ident :dt/label :dt/context :dt/type
         :user/login :user/secret :user/uuid]
 :direct-slots [:user/login :user/secret :user/uuid]
 :required-slots [:user/login :user/secret]
 :direct-subclasses []
 :all-subclasses []}
```

## Reading instances

```bash
# All instances of a class
curl http://localhost:8080/api/store/classes/model/User/instances

# Direct instances only (no subclass instances)
curl http://localhost:8080/api/store/classes/dt/Ref/instances/direct

# One instance by ident
curl http://localhost:8080/api/store/entities/model/user.alice
```

## Reading properties

```bash
# All properties
curl http://localhost:8080/api/store/properties

# One property
curl http://localhost:8080/api/store/properties/user/login

# Domain / range / cardinality
curl http://localhost:8080/api/store/properties/user/login/domain
curl http://localhost:8080/api/store/properties/user/login/range
curl http://localhost:8080/api/store/properties/user/login/cardinality
```

## Type predicates

```bash
# Is Child a subclass of Parent?
curl http://localhost:8080/api/store/types/subclass-of/dt/Resource/model/User
# => {:parent :dt/Resource :child :model/User :subclass-of? true}

# Is Entity an instance of Class?
curl http://localhost:8080/api/store/types/instance-of/model/User/model/user.alice
# => {:class :model/User :entity :model/user.alice :instance-of? true}
```

## Creating instances (POST)

```bash
curl -X POST http://localhost:8080/api/store/classes/event/Booking/instances \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "event.booking/title": "Weekly Sync",
    "event.booking/starts-at": "2026-05-14T15:00:00Z",
    "event.booking/ends-at": "2026-05-14T16:00:00Z",
    "event.booking/owner": "model/user.alice"
  }'
```

Successful creation returns the entity:

```json
{
  "entity": {
    "db/id": 12345,
    "event.booking/title": "Weekly Sync",
    "event.booking/starts-at": "2026-05-14T15:00:00Z",
    ...
  }
}
```

Validation failures return 422 Unprocessable Entity with structured error data:

```json
{
  "errors": [
    {"slot": "event.booking/owner", "error": "missing-required"}
  ]
}
```

## Updating instances (PATCH)

```bash
curl -X PATCH http://localhost:8080/api/store/entities/event/booking.weekly \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event.booking/location": "Conference Room A"}'
```

Only the provided slots are updated; others remain untouched.  Validation runs after the merge — including any applicable `:mm/Shape` invariants (see [`authoring-shapes.md`](authoring-shapes.md)).

## Shape validation

The metamodel surface mirrors the MCP shape verbs.

```bash
# List all shapes
curl http://localhost:8080/api/store/shapes

# Shapes that apply to a class
curl "http://localhost:8080/api/store/shapes?applies-to=mm/Decision"

# Validate one entity against its applicable shapes
curl -X POST http://localhost:8080/api/store/shapes/validate \
  -H "Content-Type: application/json" \
  -d '{"entity": ":decisions/foo", "mode": "audit"}'

# Batch conformance for a class
curl http://localhost:8080/api/store/shapes/conformance-report/mm/Decision
```

## Error handling

| HTTP status | Meaning                                                                |
|-------------|------------------------------------------------------------------------|
| `200 OK`            | Request succeeded                                              |
| `400 Bad Request`   | Malformed request (parse error, missing body, etc.)            |
| `401 Unauthorized`  | Missing or invalid bearer token                                |
| `403 Forbidden`     | Authenticated but not permitted                                |
| `404 Not Found`     | The requested class / property / entity doesn't exist          |
| `409 Conflict`      | Constraint violation (e.g., uniqueness)                        |
| `422 Unprocessable` | Validation failed — response body has structured `:errors`     |
| `500 Internal`      | Server-side error                                              |

Always inspect the response body for error details — error structure is consistent across endpoints.

## Example clients

### curl (the canonical lingua franca)

```bash
TOKEN="<your-token>"
BASE="http://localhost:8080/api"

# Walk the metamodel
curl -s "$BASE/store/classes" | jq

# Read one entity
curl -s -H "Accept: application/json" \
  "$BASE/store/entities/model/user.alice" | jq

# Create
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"event.booking/title":"Foo",...}' \
  "$BASE/store/classes/event/Booking/instances" | jq
```

### Python (httpx)

```python
import httpx

class SandbarREST:
    def __init__(self, base_url, token=None):
        self.base = base_url
        self.headers = {"Accept": "application/json"}
        if token:
            self.headers["Authorization"] = f"Bearer {token}"

    def classes(self):
        return httpx.get(f"{self.base}/store/classes",
                         headers=self.headers).json()

    def class_details(self, ns, name):
        return httpx.get(f"{self.base}/store/classes/{ns}/{name}",
                         headers=self.headers).json()

    def create(self, ns, name, attributes):
        return httpx.post(f"{self.base}/store/classes/{ns}/{name}/instances",
                          headers={**self.headers,
                                   "Content-Type": "application/json"},
                          json=attributes).json()

# Usage
s = SandbarREST("http://localhost:8080/api", token="...")
print(s.classes())
```

### TypeScript (fetch)

```typescript
class SandbarREST {
  constructor(
    private base: string,
    private token: string,
  ) {}

  private headers(): Record<string, string> {
    return {
      'Accept': 'application/json',
      'Authorization': `Bearer ${this.token}`,
    };
  }

  async classes() {
    const r = await fetch(`${this.base}/store/classes`, {
      headers: this.headers(),
    });
    return r.json();
  }

  async create(ns: string, name: string, attributes: object) {
    const r = await fetch(`${this.base}/store/classes/${ns}/${name}/instances`, {
      method: 'POST',
      headers: { ...this.headers(), 'Content-Type': 'application/json' },
      body: JSON.stringify(attributes),
    });
    if (!r.ok) {
      throw new Error(`Sandbar error ${r.status}: ${await r.text()}`);
    }
    return r.json();
  }
}
```

## Patterns

### Reflection-driven UI

Walk `/api/store/classes` and `/api/store/classes/<class>/slots` to render forms for any class.  The same pattern that works in [`writing-a-clojure-client.md`](writing-a-clojure-client.md) works through REST — Sandbar's REST surface is the metamodel projected; no separate schema artifact.

### Caching

Class metadata changes only when the schema evolves (rarely).  Aggressive client-side caching of `/api/store/classes/*` is safe — listen for `notifications/tools/list_changed` over MCP (if you maintain both connections) or use a short-TTL refresh.

### Streaming via SSE for live updates

Sandbar's MCP endpoint provides resource subscriptions over SSE; the REST surface today is request/response only.  If you need live updates, use the MCP transport for that subset.

## Comparison with MCP

| Concern                       | REST                              | MCP                              |
|-------------------------------|-----------------------------------|----------------------------------|
| Wire format                   | EDN / JSON / Transit             | JSON-RPC over HTTP+SSE          |
| Discovery                     | URL traversal                     | `tools/list`, `resources/list`   |
| Subscriptions                 | None (today)                     | Per-URI over SSE                 |
| Long-running operations       | Synchronous (or 202+polling)     | First-class Tasks                |
| Schema reflection             | Per-class endpoint                | `tools/list` returns JSON Schema |
| Ideal consumer                | Browser / traditional HTTP client | AI agent / reflective consumer   |

Both project from the same metamodel.  Pick by consumer fit.

## See also

- [`doc/api/http-rest.md`](../api/http-rest.md) — complete endpoint reference
- [`writing-an-mcp-client.md`](writing-an-mcp-client.md) — MCP alternative
- [`writing-a-clojure-client.md`](writing-a-clojure-client.md) — in-process Clojure access
- [`authoring-shapes.md`](authoring-shapes.md) — `:mm/Shape` authoring + validation
- [`auth.md`](../auth.md) — token issuance + management
- [`zorp-tutorial.md`](zorp-tutorial.md) — worked example using REST queries
