# HTTP REST reference

The REST surface exposes model inspection, structural retrieval and domain operations. The route table is defined in [service/routes.clj](../../src/sandbar/service/routes.clj). It is a separate adapter from MCP: REST returns an HTTP status and representation body, rather than a JSON-RPC tool envelope.

Use the [REST client guide](../guides/writing-a-rest-client.md) for a working read. The default application port is 8389. Protected `/api` requests require authentication; service accounts send `X-API-Key: <service-name>:<api-key>`. Request `Accept: application/json` when the client expects JSON. Authentication, operation authorization and projection/visibility policy remain distinct checks; see [authentication](../auth.md) and [firewall and projects](../firewall-and-projects.md).

## Model inspection

In a path, split a keyword into its namespace and local name: `:mm/Decision` becomes `mm/Decision`. Encode each segment correctly; do not concatenate untrusted identifiers into a URL without encoding.

All routes in this table use **GET**.

| Path | Result |
| --- | --- |
| `/api/status` | Service status |
| `/api/store/schema` | Model overview |
| `/api/store/classes` | Class list |
| `/api/store/classes/:ns/:name` | Class description, slots, hierarchy and population summary |
| `/api/store/classes/:ns/:name/instances` | Instances including subclasses |
| `/api/store/classes/:ns/:name/instances/direct` | Directly typed instances |
| `/api/store/classes/:ns/:name/slots` | Effective slots, including inheritance |
| `/api/store/classes/:ns/:name/slots/direct` | Direct slots |
| `/api/store/classes/:ns/:name/slots/required` | Required slots |
| `/api/store/classes/:ns/:name/hierarchy` | Class hierarchy |
| `/api/store/classes/:ns/:name/subclasses` | Transitive subclasses |
| `/api/store/classes/:ns/:name/subclasses/direct` | Direct subclasses |
| `/api/store/classes/:ns/:name/ancestors` | Transitive ancestors |
| `/api/store/classes/:ns/:name/parents` | Direct parents |
| `/api/store/classes/:ns/:name/validate` | Validation report over instances |
| `/api/store/properties` | Property list |
| `/api/store/properties/:ns/:name` | Property descriptor |
| `/api/store/properties/:ns/:name/domain` | Domain |
| `/api/store/properties/:ns/:name/range` | Range |
| `/api/store/entities/:ns/:name` | Named entity |
| `/api/store/entities/:ns/:name/class` | Entity class |
| `/api/store/entities/:ns/:name/validate` | Existing-entity validation report |
| `/api/store/types/instance-of/:class-ns/:class-name/:entity-ns/:entity-name` | Class-first instance predicate |
| `/api/store/types/subclass-of/:parent-ns/:parent-name/:child-ns/:child-name` | Parent-first subclass predicate |

The response fields are defined in [api/store.clj](../../src/sandbar/api/store.clj). Slot inspection returns descriptor data; do not assume it has the same shape as a Clojure set of slot identifiers. Class existence, direct membership, inherited membership and validation are different questions.

There is no generic REST entity-create route in this table. Use the discovered [MCP entity operations](mcp-verbs.md) or the [Clojure store boundary](../guides/writing-a-clojure-client.md) for supported typed creation.

## Structural retrieval

All routes below use **GET** with URL-encoded query parameters. Keyword-valued parameters accept a keyword-form string such as `:mm/Decision`; structured `where`, `via`, and `axes` values are EDN strings.

| Path | Parameters |
| --- | --- |
| `/api/aggregate/count` | Required `class`; optional `where` |
| `/api/aggregate/group-by` | Required `class`, `group-by`; optional `where` |
| `/api/aggregate/rank-by` | Required `class`, `rank-by`; optional `limit`, `temporal-slot` |
| `/api/navigate/path` | Required `from`, `via`; optional `limit`, comma-separated `include` |
| `/api/navigate/siblings` | Required `entity`, `path-slot`; optional `limit` |
| `/api/orient/library-card` | Required `entity`, `axes` |

Rank axes are `degree`, `backlink-density`, `recency`, and `freshness`; temporal axes need the intended timestamp slot. Query clauses are subject to the read-plane grammar and namespace policy. Encoding an EDN expression is not permission to execute an arbitrary predicate.

Example after configuring an authorized credential:

```sh
curl --silent --show-error --fail-with-body --get \
  'http://localhost:8389/api/aggregate/count' \
  -H "X-API-Key: ${SANDBAR_TOKEN}" \
  -H 'Accept: application/json' \
  --data-urlencode 'class=:mm/Decision'
```

This route inventory contains no REST BM25F endpoint. Use [the search guide](../guides/searching-the-corpus.md) through MCP or Clojure. A similarly named route should not be invented by translating a tool's dotted name into slashes.

## Domain operations

These routes expose specific event, job and workflow handlers. Their bodies and result fields are defined in the linked handler namespaces; they do not take the MCP `tools/call` body. Mutation authorization and validation must be verified on the REST boundary itself.

| Method | Path | Operation |
| --- | --- | --- |
| GET / POST | `/api/events` | List / create event |
| POST | `/api/events/server`, `/user`, `/system`, `/http`, `/api`, `/transaction` | Create the named event kind; each suffix is under `/api/events` |
| GET | `/api/events/correlation/:uuid`, `/api/events/:id` | Correlated events / one event |
| GET / POST | `/api/jobs` | List / create scheduled job |
| GET | `/api/jobs/stats`, `/api/jobs/due`, `/api/jobs/running` | Job summaries |
| POST | `/api/jobs/triggered`, `/api/jobs/recurring` | Create the named job kind |
| GET | `/api/jobs/:id` | Inspect job |
| POST | `/api/jobs/:id/cancel`, `/pause`, `/resume`, `/execute` | Job action; each suffix is under `/api/jobs/:id` |
| GET / POST | `/api/workflows` | List / define workflow |
| GET | `/api/workflows/:ns/:name`, `/api/workflows/:ns/:name/stats` | Definition / statistics |
| GET / POST | `/api/processes` | List / start process |
| GET | `/api/processes/:id`, `/api/processes/:id/transitions`, `/api/processes/:id/history` | Process, available transitions, history |
| POST | `/api/processes/:id/transition` | Request a transition |

Handlers: [events](../../src/sandbar/api/event.clj), [jobs](../../src/sandbar/api/job.clj), [workflows and processes](../../src/sandbar/api/workflow.clj). The older job HTTP model is not automatically identical to every newer first-class Schedule operation. Choose a supported domain API and keep its lifecycle consistent.

## Authentication endpoints

Public entry points are `POST /login`, `POST /register`, and `GET /me` with authentication processing. Protected operations are `POST /api/auth/logout`, `POST /api/auth/password`, `GET /api/auth/sessions`, and `DELETE /api/auth/sessions/:id`. See [api/auth.clj](../../src/sandbar/api/auth.clj) and the [authentication guide](../auth.md).

## Errors and retries

Check HTTP status before interpreting the body, then check the endpoint's result fields. Authentication refusal, missing input, a missing entity and an internal failure are not equivalent to an empty successful result. Retain a bounded diagnostic with the route and request ID, omitting credentials and private contents.

For writes, a timeout can leave the outcome unknown. Read the affected state before retrying when the operation may already have committed. A workflow transition or job execution should not be replayed merely because the response was lost.
