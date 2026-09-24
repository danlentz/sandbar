# Writing a REST client

Sandbar's REST API is useful for applications that already speak HTTP and want direct JSON or EDN responses. Its store routes expose model inspection, instances and type predicates; other route families expose aggregation, navigation, orientation, events and workflows. It shares the underlying model with MCP, but the two APIs have different routes, authentication headers and response envelopes.

## Base URL and authentication

The usual local base URL is `http://127.0.0.1:8389/api`. Use your deployment's address and port. The `/api` routes require authentication, including `/api/status`.

A service account uses the **`X-API-Key`** header with the credential in `service-name:api-key` form. MCP uses that credential as a Bearer token instead. Obtain the credential and permissions through the [authentication setup](../auth.md).

```sh
: "${SANDBAR_TOKEN:?Set SANDBAR_TOKEN to your service-account credential}"
SANDBAR_API_URL="http://127.0.0.1:8389/api"

sandbar_get() {
  curl --silent --show-error --fail-with-body \
    --header "X-API-Key: $SANDBAR_TOKEN" \
    --header 'Accept: application/json' \
    "$SANDBAR_API_URL$1"
}

sandbar_get /store/classes/mm/Decision/slots
```

This request needs no example instances. It returns the decision class's effective slots, including inherited properties. The JSON object has `class`, `count`, and `slots`; each slot description contains `ident`, `domain`, `range`, `cardinality`, and `required?`.

The authentication layer also supports login sessions. For an unattended integration, prefer the deployment's service-account mechanism rather than placing a human password in the client. A successful authenticated read is not proof that the same account has the intended restrictions on every route; deployment access policy must cover each exposed API.

## Content negotiation

Set `Accept` explicitly. The service supports JSON and EDN, with EDN as its fallback encoding; JSON examples should request `application/json`. A request with a body also needs a matching `Content-Type`.

REST responses contain the endpoint payload directly. They have neither a JSON-RPC envelope nor an MCP `result.content` wrapper. JSON encodes Clojure keyword values such as `:mm/Decision` as strings such as `"mm/Decision"`; EDN retains keyword syntax. Do not make one decoder guess both representations.

## URL conventions

The store API splits namespaced identifiers into two path segments:

| Model identifier | Path fragment |
| --- | --- |
| `:mm/Decision` | `mm/Decision` |
| `:mm.memory/name` | `mm.memory/name` |
| `:db.type/string` | `db.type/string` |

Keep case, dots and hyphens intact, omit the leading colon, and URL-encode each segment separately. The slash between namespace and name belongs to the route. Numeric database IDs and model identifiers are different reference forms; use the form the endpoint declares.

## Walking the metamodel

Start with a class, then follow the question you need to answer:

```sh
sandbar_get /store/classes/mm/Decision
sandbar_get /store/classes/mm/Decision/slots
sandbar_get /store/classes/mm/Decision/slots/required
sandbar_get /store/classes/mm/Decision/ancestors
sandbar_get /store/properties/mm.memory/name/range
```

| Route under `/api` | Result |
| --- | --- |
| `/store/schema` | Overview of classes, properties and types |
| `/store/classes` | Class descriptions |
| `/store/classes/:ns/:name` | One class description |
| `/store/classes/:ns/:name/slots` | Effective slot descriptions |
| `/store/classes/:ns/:name/slots/direct` | Direct slot identifiers |
| `/store/classes/:ns/:name/instances` | Inherited class population |
| `/store/classes/:ns/:name/instances/direct` | Directly asserted instances |
| `/store/properties/:ns/:name` | One property description |
| `/store/properties/:ns/:name/domain` | Declared domain |
| `/store/properties/:ns/:name/range` | Declared range |

The distinction between effective and direct results matters. A specialized decision inherits memory properties; an instance of a specialized class can belong to the broader class population through the inference rules. The effective-slots route returns description maps, while the direct-slots route returns identifiers. See the [metamodel](../concepts/metamodel.md) and [API reference](../api/http-rest.md).

## Reading instances

Class instance routes are useful for small, known populations. They currently realize the class population in one response; do not assume that adding a `limit` parameter supplies pagination. For broad discovery, use a supported aggregate or focused navigation operation before loading entities.

The store route family is an inspection API. It does not currently define generic `POST` entity creation or `PATCH` entity update endpoints. Use the documented [MCP entity tools](writing-an-mcp-client.md) or [embedded Clojure API](writing-a-clojure-client.md) for those operations. BM25F search is exposed through MCP, not through a REST BM25F route. Route-specific workflow, job and event mutations have their own contracts.

## Error handling

Check the HTTP status before treating a body as the requested value, then decode the declared content type. Keep the status and error body together in diagnostics. A valid JSON error object is not a successful query returning no instances.

Missing or invalid authentication produces an authentication failure. Unknown model entities can produce `404`, and unsupported `Accept` values can produce `406`. Do not assign one universal validation or conflict status to all route families; follow the endpoint's documented contract. Retry reads according to your connection policy, and reconcile an uncertain mutation before repeating it.

## Building a client around these routes

Keep the base URL, credential injection, status checking, and decoding in one small HTTP boundary. Keep model-specific operations above it: a function for decision slots should return slot data, rather than requiring every caller to assemble headers and parse HTTP errors.

A reflection-driven editor can use class slots to label fields and show declared ranges and cardinalities. That metadata helps construct a request; server-side validation remains authoritative. Refresh model metadata when the model changes. A stable MCP tool list is not proof that class definitions stayed unchanged.

Choose MCP when you want advertised tool schemas, content search, resources or prompts. Choose REST when an explicit route and ordinary HTTP payload fit the application. See [MCP clients](writing-an-mcp-client.md), [the REST reference](../api/http-rest.md), and [projects and boundaries](../firewall-and-projects.md) for the surrounding contracts.
