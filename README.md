# Sandbar

**An integrated metamodel platform for typed Clojure data over Datomic — with first-class codecs, workflow substrate, and protocol surfaces (REST + MCP + RDF) derived from the same metacircular type system.**

Sandbar lets you define classes, properties, and inheritance once; expose them through whatever wire protocol your consumer prefers; and trust the substrate to handle the translation. The type system describes itself using its own constructs (`:dt/Class` is itself an instance of `:dt/Class`), so the metamodel is queryable, evolvable, and introspectable at runtime — through Clojure, HTTP, or the Model Context Protocol — with no protocol-specific schema duplication.

## The conceptual basis

Three ideas hold the platform together:

### 1. Metacircular metamodel

The type system is data, stored in Datomic alongside the entities it describes. `:dt/Class` is itself a class, `:dt/Property` is itself a property. Adding a class is a transaction; introspecting the schema is a query. Evolution at runtime is a first-class case rather than a redeploy. The Clojure REPL, the REST API, and the MCP server all reach the metamodel through the same `dt/*` introspection layer (`dt/all-classes`, `dt/slots-of`, `dt/instance-of?`, `dt/make`).

This is the *turtles all the way down* property: every layer above the kernel is expressed in terms of constructs the kernel already understands.

### 2. Boundary-layer abstraction (consumer talks native)

Sandbar absorbs substrate complexity at each layer so consumers can talk in their natural representation:

| Consumer's native form | Boundary layer that absorbs it | Substrate underneath |
|------------------------|-------------------------------|---------------------|
| Markdown documents (`mm/Memory` consumer) | `sandbar.codec.markdown` | `dt/*` model |
| TTL / RDF triples (graph-query clients) | `sandbar.codec.ttl` *(arc-pending)* | `dt/*` model |
| EDN/TTL hybrid (Clojure-side Lisp) | `sandbar.codec.edn-ttl-hybrid` *(arc-pending)* | `dt/*` model |
| JSON-RPC tool calls (AI clients) | `sandbar.mcp.*` (MCP server) | `dt/*` model |
| HTTP REST (traditional clients) | `sandbar.api.*` | `dt/*` model |
| Datalog queries | `dt/*` introspection API | `datomic.api` (peer / cloud) |

Each layer adds expressivity without pushing complexity up the stack. The `dt/*` API abstracts Datomic so the MCP server never touches `d/q` / `d/transact`; the codec layer abstracts wire formats so the memory-corpus consumer never hand-rolls a YAML parser; the protocol layers abstract transport so the metamodel author never writes per-protocol glue.

### 3. Multi-surface protocol exposure (bootstrap-by-discovery)

The same metamodel is exposed through multiple wire protocols simultaneously. Every non-abstract `dt/Class` is automatically an MCP tool, a REST resource, and (when the codec arc completes) a TTL graph node — with no hand-curated mapping table. `tools/list` walks `dt/all-classes`; JSON Schema is reflected from `dt/range-of` + `dt/cardinality-many?`; resource URIs are computed from class metadata. New classes auto-surface across every protocol the moment they're transacted.

The corollary: there's exactly one schema, one validation pass, one type system. Protocols are derived projections, not parallel models to keep in sync.

## Architecture

```
                  ┌──────────────────────────────────────────────────────────────┐
  Consumer        │  Claude (MCP client)   curl  bb  REPL  external RDF tools    │
  surface         └──┬──────────────┬──────────┬─────┬──────────────┬────────────┘
                     │              │          │     │              │
                  ┌──▼─────┐   ┌────▼────┐ ┌───▼─┐ ┌─▼──┐    ┌──────▼─────┐
  Protocol        │  MCP   │   │ REST API│ │ CLI │ │REPL│    │  TTL / EDN │
  layer           │ server │   │  HTTP+  │ │     │ │    │    │   (codec   │
                  │ JSON-  │   │ content-│ │     │ │    │    │   arc      │
                  │ RPC +  │   │ negot.  │ │     │ │    │    │   pending) │
                  │  SSE   │   └─────────┘ └─────┘ └────┘    └────────────┘
                  └────┬───┘
                       │
                  ┌────▼──────────────────────────────────────────────────────────┐
  Codec          │  sandbar.codec — mediator + per-class :dt/native-codec        │
  layer          │  ┌──────────────────────────────────────────────────────────┐ │
                 │  │  codec.markdown   codec.ttl*   codec.edn-ttl-hybrid*     │ │
                 │  │  codec.json*                                             │ │
                 │  └──────────────────────────────────────────────────────────┘ │
                 │       (*arc-pending)                                          │
                 └────┬──────────────────────────────────────────────────────────┘
                      │
                  ┌───▼────────────────────────────────────────────────────────┐
  Model         │  sandbar.db.datatype  (dt/*)                                 │
  layer         │  classes • properties • inheritance • validation • introsp.  │
                │  workflow substrate • tasks • auth • events • jobs           │
                └───┬──────────────────────────────────────────────────────────┘
                    │
                  ┌─▼─────────────────────────────────────────────────────────┐
  Backend       │  Datomic (peer / cloud)                                     │
  layer         └─────────────────────────────────────────────────────────────┘
```

Each layer composes through the layer below it. The protocol layer NEVER touches Datomic directly (per the layer-targeting discipline); the codec layer NEVER bypasses `dt/*`; the model layer is the only place that knows about transactions and peer connections. This invariant keeps protocols swappable and the model layer authoritative.

## What's in the platform

| Surface | Provides |
|---------|----------|
| **`dt/*` model API** | classes, properties, inheritance, validation, introspection, instance ops |
| **REST API** | HTTP endpoints for class / property / entity introspection with content-type negotiation (EDN / JSON) |
| **MCP server** | JSON-RPC 2.0 + SSE transport; bootstrap-by-discovery tool catalog; resources, prompts, tasks; bearer-token auth |
| **Codec layer** | Modular extensible codecs (markdown, TTL\*, EDN-TTL-hybrid\*, JSON\*); per-class `:dt/native-codec` declares default format |
| **Workflow substrate** | First-class state machines with transitions, guards, history, cancellation, terminal-kind classification |
| **MCP Tasks** | Long-running operations as workflow processes — task-id-as-process-id correspondence; durable execution |
| **Auth** | User / Group / ServiceAccount / Role / Permission with Buddy-hashers + bearer-token interceptor |
| **Events** | Structured event log with correlation IDs + interceptor integration |
| **Jobs** | Scheduled, triggered, and recurring background jobs |
| **Context** | Hierarchical namespacing substrate for resource scoping |

*Asterisk-marked surfaces are arc-pending; see the codec arc plan in the corpus.*

## Quick start

```bash
# Prerequisites: Java 11+, Leiningen, running Datomic transactor
git clone <repository-url> && cd sandbar
lein deps
lein repl
```

```clojure
;; In the REPL
(require '[sandbar.core :refer [go stop]])
(go)  ; HTTP on :8080, nREPL on :28888
```

Then:

- **REST**: `curl http://localhost:8080/api/store/classes` lists every class
- **MCP**: issue a service-account token (`(sandbar.util.auth/issue-api-key! ...)` in the REPL), then `POST /mcp` with a JSON-RPC body
- **REPL**: `(require '[sandbar.db.datatype :as dt])` and explore via `dt/all-classes`, `dt/slots-of`, etc.

## A worked example — Zorp's footwear

Zorp runs the Galactic Footwear Emporium from a crater on the dark side of Pluto. His inventory was a mess of untyped maps:

```clojure
{:name "Moon Boot Pro" :price 299.99 :tentacles 4}
;; Wait — is `tentacles` required? Can boots have tentacles?
```

With Sandbar, Zorp defines a proper type hierarchy:

```
                  zorp/Footwear [abstract]
          ________________|________________
         |                |                |
    zorp/Sneaker     zorp/Boot       zorp/Sandal
                    ____|____             |
                   |         |       zorp/FlipFlop
              zorp/HighTop  zorp/SpaceBoot
```

```clojure
;; Define a class
{:db/ident :zorp/Boot
 :dt/type :dt/Class
 :dt/subclass-of :zorp/Footwear
 :dt/slots [:boot/vacuum-rated? :boot/temperature-range]}

;; Create + validate an instance
(dt/make :zorp/SpaceBoot
  {:footwear/name "Moon Boot Pro"
   :footwear/price 299.99M
   :boot/vacuum-rated? true})
;; => entity with :dt/type :zorp/SpaceBoot

;; Abstract instantiation rejected
(dt/make :zorp/Footwear {...})
;; => throws "Cannot instantiate abstract class"

;; Introspect at runtime
(dt/slots-of :zorp/SpaceBoot)
;; => #{:footwear/name :footwear/price :boot/vacuum-rated? ...}
```

The full tutorial walks Zorp through hierarchy design, validation, and the surfaces above — see [doc/zorp-example.md](doc/zorp-example.md).

## Core API (dt/*)

```clojure
(require '[sandbar.db.datatype :as dt])

;; Classes
(dt/all-classes)                          ; List all classes
(dt/parents-of :model/User)               ; Direct parents
(dt/ancestors-of :model/User)             ; Full chain
(dt/subclasses-of :dt/Resource)           ; All descendants
(dt/abstract? :zorp/Footwear)             ; true | false

;; Properties
(dt/all-properties)
(dt/slots-of :model/User)                 ; inherited + direct
(dt/direct-slots-of :model/User)          ; declared only
(dt/domain-of :user/login)                ; => :model/User
(dt/range-of :user/login)                 ; => :db.type/string

;; Instances
(dt/make :model/User {:user/login "zorp"})  ; create with validation
(dt/class-of some-entity)                   ; => :model/User
(dt/instance-of? :model/User some-entity)   ; true | false
(dt/all-instances-of :model/User)           ; includes subclass instances
(dt/valid? some-entity)                     ; validate against class
```

## MCP server

Sandbar speaks the [Model Context Protocol](https://modelcontextprotocol.io/) for AI-client integration. The MCP surface is a peer of the REST API, not a wrapper — both protocols project the same metamodel through different wire formats, derived reflectively from `dt/*`.

| Route | Method | Purpose |
|-------|--------|---------|
| `/mcp` | POST | JSON-RPC 2.0 request endpoint |
| `/mcp/sse` | GET | Server-sent-events channel for notifications |

**Capabilities declared at `initialize`**:

- **tools** — operational verb catalog of stable Sandbar operations (`schema.classes`, `class.describe`, `class.instances`, `entity.create`, `entity.find`, `workflow.start-process`, `validation.start`, etc.) plus `listChanged` notifications for schema evolution
- **resources** — every entity addressable at `mcp://sandbar/<class-ns>/<class-name>/<ident-or-eid>`; `subscribe` + per-subscriber routing
- **prompts** — workflow definitions exposed as MCP prompts (workflows-as-prompts pattern)
- **tasks** — long-running operations via the workflow substrate (task-id-as-process-id)
- **logging** — reserved for per-tool log hooks

```bash
# Get a service-account token from the REPL first
export SANDBAR_TOKEN="claude:..."

# Discover the surface
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

For the full MCP surface (lifecycle, all methods, configuration, failure modes) see [doc/mcp-server.md](doc/mcp-server.md). For long-running operations see [doc/tasks-api.md](doc/tasks-api.md).

## REST API

The metamodel is fully exposed via HTTP for traditional clients. Content-type negotiation picks EDN by default; request JSON with `Accept: application/json`.

```bash
curl http://localhost:8080/api/store/schema                              # overview
curl http://localhost:8080/api/store/classes                             # list
curl http://localhost:8080/api/store/classes/model/User                  # details
curl http://localhost:8080/api/store/classes/model/User/slots            # all slots
curl http://localhost:8080/api/store/classes/model/User/hierarchy        # tree
curl http://localhost:8080/api/store/properties/user/login/range         # property range
curl http://localhost:8080/api/store/types/instance-of/dt/Class/model/User
```

Full endpoint table at [doc/store-api.md](doc/store-api.md).

## Codec layer

The codec layer is the wire-format boundary. Consumers pass native representation (markdown documents, TTL triples, JSON payloads, EDN values); the codec absorbs parse + class-binding + emit. Each class declares `:dt/native-codec :markdown` (or similar) and the mediator resolves the default at call time.

```clojure
(require '[sandbar.codec :as codec]
         '[sandbar.codec.markdown :as md])

(md/register!)                            ; one-time registration

;; Parse markdown → entity-spec
(codec/parse markdown-source
             {:format :markdown :class :mm/Memory})

;; Emit entity → markdown
(codec/emit entity {:format :markdown})

;; Mime-type-driven dispatch
(codec/parse-mime "text/markdown" source)
```

Document chunks are first-class addressable entities — for `mm/Memory`, the markdown codec decomposes headings into `mm/Section` entities with bidirectional sibling-chain navigation (`:next-sibling` / `:previous-sibling`), parent-child nesting, and path-derived idents (e.g., `:decisions/foo__context__decision`). The chain shape gives O(1) point-local neighbor lookup; at the TTL emission boundary it can dual-emit as canonical `rdf:List`.

## Workflows + Tasks

Workflows are first-class entities. A workflow definition has states + transitions + guards + history. A process is a running instance attached to a subject entity. Terminal states carry a `:workflow/terminal-kind` classification (`:success` / `:failure` / `:cancel`) — the workflow substrate knows the outcome shape, not just "process ended."

```clojure
(require '[sandbar.util.workflow :as wf])

(wf/define-workflow! :order/fulfillment
  {:states [{:name :order/pending :initial? true}
            {:name :order/shipped}
            {:name :order/delivered :terminal? true :terminal-kind :success}
            {:name :order/cancelled :terminal? true :terminal-kind :cancel}]
   :transitions [{:name :ship   :from :order/pending  :to :order/shipped}
                 {:name :deliver :from :order/shipped :to :order/delivered}
                 {:name :cancel :from :order/pending  :to :order/cancelled}]})

(def proc (wf/start-process! :order/fulfillment order-entity))
(wf/transition! proc :ship)
(wf/can-cancel? proc)         ; checks for a :terminal-kind :cancel transition
(wf/cancel-process! proc)     ; deliberate stop with full history
```

MCP Tasks build on this substrate — every long-running tool-call becomes a workflow process, the task-id IS the process's `:db/id` (no parallel registry), and `tasks/cancel` is just `workflow/cancel-process!`. The validation service `validation/start` / `validation/run` / `validation/cancel` follows the same shape — see [doc/workflow.md](doc/workflow.md).

## Project layout

```
sandbar/
├── config/             # EDN configuration
├── schema/             # Type definitions
│   ├── meta.edn        # Core metamodel (Class, Property, Resource, ...)
│   ├── auth.edn        # User / Group / ServiceAccount / Role / Permission
│   ├── event.edn       # Event types
│   ├── workflow.edn    # Workflow state machines + terminal-kind
│   ├── context.edn     # Hierarchical scoping
│   ├── job.edn         # Background-job schema
│   └── zorp.edn        # Example: Galactic Footwear Emporium
├── src/sandbar/
│   ├── codec.clj            # Mediator + format resolution
│   ├── codec/
│   │   ├── protocol.clj     # Codec defprotocol
│   │   └── markdown.clj     # Markdown + YAML frontmatter codec
│   ├── db/
│   │   ├── datatype.clj     # dt/* API — model layer
│   │   ├── datomic.clj      # peer connection
│   │   └── rules.clj        # recursive Datalog rules
│   ├── mcp/                 # Model Context Protocol server
│   │   ├── envelope.clj     # JSON-RPC 2.0 envelope (leaf ns)
│   │   ├── protocol.clj     # initialize + dispatch table
│   │   ├── transport.clj    # POST /mcp + GET /mcp/sse
│   │   ├── auth.clj         # bearer-token Pedestal interceptor
│   │   ├── tools.clj        # operational verb catalog
│   │   ├── resources.clj    # URI codec + list/read/subscribe (per-sub routing)
│   │   ├── prompts.clj      # workflows-as-prompts
│   │   ├── tasks.clj        # workflow-backed Tasks primitive
│   │   └── notifications.clj # subscriber registry + SSE notifications
│   ├── api/                 # REST handlers (store, auth, event, job, workflow)
│   ├── server/              # HTTP + nREPL component
│   ├── service/             # routing, interceptors, validation-as-workflow
│   └── util/
│       ├── auth.clj         # Buddy-hashers + service-account lifecycle
│       ├── event.clj        # event logging + correlation
│       └── workflow.clj     # process lifecycle, transition!, cancel-process!
└── test/                    # comprehensive test suite (~470 tests)
```

## Documentation

| Document | Topic |
|----------|-------|
| [Quick Start](doc/quickstart.md) | Zero to running in 5 minutes |
| [Architecture](doc/architecture.md) | How the layers compose |
| [Metamodel](doc/meta.md) | Classes, properties, inheritance, validation |
| [MCP Server](doc/mcp-server.md) | Tools, resources, prompts, tasks; lifecycle + configuration |
| [MCP Tasks API](doc/tasks-api.md) | Long-running operations via workflow-backed tasks |
| [Store API](doc/store-api.md) | REST surface for classes / properties / entities |
| [Authentication](doc/auth.md) | Users, sessions, API keys, bearer tokens |
| [Workflow API](doc/workflow.md) | State machines, transitions, cancellation, terminal-kind |
| [Background Jobs](doc/jobs-api.md) | Scheduled / triggered / recurring jobs |
| [Event System](doc/event.md) | Logging, correlation, interceptors |
| [Zorp Tutorial](doc/zorp-example.md) | Worked example — alien footwear inventory |

## Running tests

```bash
lein test                                       # full suite
lein test sandbar.codec.markdown-test           # one namespace
lein test :only sandbar.datatype-test/make-test # one deftest
```

## FAQ

**Q: Why not just use Datomic's schema?**
A: Datomic schemas define attributes, not types. You can say "there's an attribute called `:user/login`" but not "a User has login, email, and inherits from Person." Sandbar adds the class layer + the inheritance + the validation, and stores the type system as data so it's queryable like everything else.

**Q: Is this RDFS / OWL?**
A: Inspired by RDFS, but simpler. Closed-world (no open-world assumption), no inference engine, no PhD required. Just classes, properties, inheritance, and a small set of metacircular primitives.

**Q: Why both REST and MCP?**
A: Different consumers, same metamodel. Traditional HTTP clients want REST; AI clients want JSON-RPC with reflective tool discovery + push notifications for schema evolution. Both projections come for free from the same `dt/*` introspection — no parallel models to keep in sync.

**Q: What's the codec layer for?**
A: So consumers can talk to Sandbar in their native representation. A memory-corpus consumer passes markdown; an RDF tool passes Turtle; an MCP client passes JSON. Sandbar absorbs the wire format and binds to the model. Same architectural shape as `dt/*` absorbing Datomic.

**Q: What's with the turtle jokes?**
A: The metamodel describes itself using its own constructs. `dt/Class` is an instance of `dt/Class`. It's self-referential. Turtles, all the way down. We're not sorry.

**Q: Can I use this in production?**
A: Zorp has been selling moon boots on Pluto for years with zero incidents.\*

<sub>\* Incidents involving sentient footwear are tracked separately.</sub>

## License

Copyright (C) Dan Lentz
