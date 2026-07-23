# Sandbar

> A metacircular metamodel platform on Datomic — and a substrate
> for memory systems that AI clients can reach into through the
> Model Context Protocol.

Sandbar is a graph data store built on RDFS-style classes,
properties, and inheritance, equipped with a four-axis retrieval
surface (BM25F fulltext, structural + temporal aggregation,
Wilbur-lineage path-grammar navigation, and library-card
orientation), exposed simultaneously through HTTP REST and MCP.

The type system is data, queryable and evolvable at runtime
through the same API you use to query your application's entities
— `:dt/Class` is itself an instance of `:dt/Class`. RDFS
entailment is a first-class substrate concern, layered cleanly so
the boundary toward OWL 2 RL is one composable extension rather
than a rewrite. Functions, shapes, rules, workflows, schedules,
and events are first-class memorial entities subject to the same
retrieval surface as the corpus they help describe.

The wire-format layer is substrate, not application concern.
Long-running operations have history, cancellation, and outcome
classification baked in. Datomic-specific behavior stays behind
the `dt/*` boundary so the model is retarget-capable.

This README is a 5-minute elevator. For depth, follow the
pointers into `doc/concepts/` (theoretical reference,
citation-rich) and `doc/guides/` (hands-on how-to).

## The offload thesis

Sandbar exists to **offload from the LLM what LLMs do poorly**.  A
language model is a probabilistic engine: superb at synthesis,
unreliable at durable memory, deterministic structure, exhaustive
retrieval, and enforcement.  Sandbar moves exactly those concerns
into a local, deterministic substrate — ontological knowledge
representation, Datalog/SHACL-style validation and entailment, a
multi-axis retrieval surface, durable indexed storage — and hands
the model a *tool boundary* (MCP) instead of a context window to
lose things in.  The alignment is three-cornered: the LLM
(probabilistic) does judgement and synthesis; MCP (the tool-use
boundary) is the seam they meet at; the substrate (traditional
KR / symbolic AI, deterministic) does memory, structure, and law.

The 0.2.0 release extends the same offload to *trust*: with
`:mm/Project` and the directional firewall, confidentiality is no
longer a discipline the AI client is trusted to observe but a
property of the data — deterministic label composition and
refusal at the write and traversal boundaries, so one substrate
can serve several projects (often private ones) without leaking
across them.  See
[`doc/firewall-and-projects.md`](doc/firewall-and-projects.md).

## Documentation map

Documentation is layered. Every entry below is a link; pick the
layer that matches your goal.

- **[`doc/firewall-and-projects.md`](doc/firewall-and-projects.md)** — The 0.2.0 centerpiece: `:mm/Project`, contexts, the visibility lattice, and the directional proprietary-isolation firewall
- **`doc/concepts/`** — Layer 2: theoretical reference (citation-rich)
  - [`metamodel.md`](doc/concepts/metamodel.md) — The `dt/*` primitives; RDFS / KL-ONE / CLOS-MOP lineage
  - [`memory-model.md`](doc/concepts/memory-model.md) — The `mm/*` user-domain layered atop `dt/*`; memorial-policy classification; PROV-O activity-lift
  - [`rdfs-entailment.md`](doc/concepts/rdfs-entailment.md) — RDFS entailment rules + OWL 2 RL extensions; SWCLOS-style metaclass intersection
  - [`first-class-fn.md`](doc/concepts/first-class-fn.md) — `:dt/Fn` + `:mm/Fn`; SHACL-AF `sh:SPARQLFunction` lineage; mathematical-mapping + stored-procedure bridge
  - [`shape-validation.md`](doc/concepts/shape-validation.md) — `:mm/Shape` + SHACL-deeply-incorporated; metacircular self-validation closure
  - [`event-substrate.md`](doc/concepts/event-substrate.md) — Datomic `tx-report-queue` behind `sandbar.reactive.tx-source`; Manifold transport; class-hierarchical subscription
  - [`reactive-substrate.md`](doc/concepts/reactive-substrate.md) — Reactive sinks; memorial-projection handler; FS↔DB reactivity
  - [`logging-substrate.md`](doc/concepts/logging-substrate.md) — Telemere-backed `sandbar.logging` six-macro API; memorial-projection on flagged signals
  - [`temporal-substrate.md`](doc/concepts/temporal-substrate.md) — OWL-Time + RFC 5545 RRULE + PROV-O alignment; `:mm/Schedule` + `:mm/Job` + `:mm/Run` (γ scheduler live since 0.2.0)
  - [`codec-layer.md`](doc/concepts/codec-layer.md) — Boundary-layer abstraction; per-class `:dt/native-codec`
  - [`projection.md`](doc/concepts/projection.md) — Bidirectional FS↔DB projection (Anderson lineage)
  - [`fulltext-search.md`](doc/concepts/fulltext-search.md) — BM25F multi-field weighted scoring; analyzer
  - [`aggregation.md`](doc/concepts/aggregation.md) — count / group-by / structural-rank; 4 ranking axes
  - [`navigation.md`](doc/concepts/navigation.md) — Edges / walk / path-grammar overview
  - [`path-grammar.md`](doc/concepts/path-grammar.md) — Wilbur algebra; 21-operator vocabulary
  - [`workflow-substrate.md`](doc/concepts/workflow-substrate.md) — `:mm/Workflow` + `:workflow/Process`; terminal-kind classification
  - [`mcp-protocol.md`](doc/concepts/mcp-protocol.md) — MCP; bootstrap-by-discovery; operational verb catalog
  - [`multi-store-architecture.md`](doc/concepts/multi-store-architecture.md) — Multi-store topology; hybrid FS/DB experimentation
  - [`markdown-as-canonical.md`](doc/concepts/markdown-as-canonical.md) — Markdown as canonical Layer-1 corpus format

- **`doc/guides/`** — Layer 3: hands-on how-to
  - [`getting-started.md`](doc/guides/getting-started.md) — Start here: install, REPL, first entity, first query
  - [`quickstart.md`](doc/guides/quickstart.md) — Five-minute hands-on tour
  - [`zorp-tutorial.md`](doc/guides/zorp-tutorial.md) — Worked example — classes + validation + queries
  - [`writing-a-clojure-client.md`](doc/guides/writing-a-clojure-client.md) — Embed Sandbar in your Clojure code
  - [`writing-an-mcp-client.md`](doc/guides/writing-an-mcp-client.md) — Connect Claude or other AI client via MCP
  - [`writing-a-rest-client.md`](doc/guides/writing-a-rest-client.md) — Consume Sandbar over HTTP REST
  - [`searching-the-corpus.md`](doc/guides/searching-the-corpus.md) — BM25F fulltext patterns; `:where` + `:facet-by` composition
  - [`navigating-with-paths.md`](doc/guides/navigating-with-paths.md) — Path-grammar worked examples; Canonical-8 + Tier-2
  - [`implementing-a-codec.md`](doc/guides/implementing-a-codec.md) — Author a codec for a new wire format
  - [`defining-new-classes.md`](doc/guides/defining-new-classes.md) — Extend the schema with new `mm/*` or domain classes
  - [`designing-workflows.md`](doc/guides/designing-workflows.md) — Author state machines with terminal-kind
  - [`authoring-shapes.md`](doc/guides/authoring-shapes.md) — Write `:mm/Shape` validators against your classes
  - [`subscribing-to-events.md`](doc/guides/subscribing-to-events.md) — Class-hierarchical subscription via `sandbar.event/subscribe`
  - [`using-logging.md`](doc/guides/using-logging.md) — The `sandbar.logging/*` callsite API + memorial flagging
  - [`sandbar-as-substrate.md`](doc/guides/sandbar-as-substrate.md) — Embed Sandbar in your own application

- **`doc/api/`** — Layer 4: mechanical reference
  - [`dt-star.md`](doc/api/dt-star.md) — Every `dt/*` function signature
  - [`http-rest.md`](doc/api/http-rest.md) — Every REST endpoint
  - [`mcp-verbs.md`](doc/api/mcp-verbs.md) — Every MCP verb in the catalog
  - [`codec-protocol.md`](doc/api/codec-protocol.md) — The Codec defprotocol

**Reading order suggestions:**

- **New here, evaluating Sandbar:** [`doc/concepts/metamodel.md`](doc/concepts/metamodel.md) → [`doc/concepts/memory-model.md`](doc/concepts/memory-model.md) → ["What makes Sandbar interesting"](#what-makes-sandbar-interesting) below → [`doc/guides/getting-started.md`](doc/guides/getting-started.md)
- **AI / MCP client author:** [`doc/concepts/mcp-protocol.md`](doc/concepts/mcp-protocol.md) → [`doc/guides/writing-an-mcp-client.md`](doc/guides/writing-an-mcp-client.md) → [`doc/api/mcp-verbs.md`](doc/api/mcp-verbs.md)
- **Building a retrieval-heavy consumer:** [`doc/concepts/fulltext-search.md`](doc/concepts/fulltext-search.md) + [`doc/concepts/navigation.md`](doc/concepts/navigation.md) + [`doc/concepts/aggregation.md`](doc/concepts/aggregation.md) → guides in `doc/guides/searching-the-corpus.md` + `navigating-with-paths.md`
- **Building reactive consumers:** [`doc/concepts/event-substrate.md`](doc/concepts/event-substrate.md) → [`doc/concepts/reactive-substrate.md`](doc/concepts/reactive-substrate.md) → [`doc/guides/subscribing-to-events.md`](doc/guides/subscribing-to-events.md)
- **Embedding in a Clojure application:** [`doc/guides/sandbar-as-substrate.md`](doc/guides/sandbar-as-substrate.md) → [`doc/api/dt-star.md`](doc/api/dt-star.md)
- **Adding a new wire format:** [`doc/concepts/codec-layer.md`](doc/concepts/codec-layer.md) → [`doc/guides/implementing-a-codec.md`](doc/guides/implementing-a-codec.md) → [`doc/api/codec-protocol.md`](doc/api/codec-protocol.md)
- **Authoring validation:** [`doc/concepts/shape-validation.md`](doc/concepts/shape-validation.md) → [`doc/guides/authoring-shapes.md`](doc/guides/authoring-shapes.md)

## What makes Sandbar interesting

Sandbar's individual ingredients exist elsewhere. The unique
value is in the *synthesis* — how these ingredients combine into
one substrate with a consistent discipline.

### Metacircular RDFS on Datomic

RDFS gives a clean vocabulary for classes, properties,
inheritance, and predicates. Datomic gives schema-on-read,
first-class time, and expressive query. Sandbar stores its own
type system inside Datomic using its own type system — `:dt/Class`
is itself an instance of `:dt/Class`. Adding a class is a
transaction; introspecting the schema is a query. Application
data and metadata flow through the same `dt/*` API.

→ `doc/concepts/metamodel.md` for theory + citations

### Memorial model (`:mm/*`) layered atop `:dt/*`

The corpus-shaped user domain — decisions, plans, observations,
tags, syntheses, libraries, sessions, logs — lives in `:mm/*`
classes that descend from `:mm/Memory`, all governed by
`:dt/memorial-policy` (which classes get FS-projected, which stay
DB-only, which are transient). Activities (chronicles, event
firings, scheduled runs) share a PROV-O-aligned vocabulary via
`:mm/Activity` and its descendants `:mm/Log`, `:mm/EventLog`,
`:mm/Run`. The memorial layer is the application; the metamodel
layer is the substrate; both speak the same query language.

→ `doc/concepts/memory-model.md`

### Multi-project memory behind a directional firewall (0.2.0 centerpiece)

`:mm/Project` ties a codebase to the context(s) it runs in and the
corpus repo its memories project to; `:mm/Context` is the
compartment — the co-load boundary.  Confidentiality is a
deterministic label composed *most-restrictive* across four axes
(memory visibility, project default, project firewall-class, every
context's firewall-class), and the directional firewall refuses
forbidden flows on the edge, at author time and at traverse time:
private may cite public; public may never depend on private;
cross-compartment private stays apart.  Absent visibility inherits
the owning project's posture; absent *project* fail-closes to a
private sentinel.  The enforcement path is principal-independent
and LLM-untrusted — no model judgement anywhere in the decision.
The shared global corpus is stamped as the public *bottom* every
project can cite without leaking into it.

→ `doc/firewall-and-projects.md` · known gaps: `doc/known-gaps-0.2.0.md`

### RDFS entailment as a substrate concern

`sandbar.db.entailment` provides RDFS rules + selected OWL 2 RL
extensions in a layered architecture: entailment compiles to
Datalog rules that compose with sandbar's existing query surface;
no external reasoner; no PhD required to use it. The 4-layer
rules organization (`:dt/*` substrate / `:mm/*` user-domain /
codec-projection / client-application) keeps the boundaries
explicit so each layer's invariants are checked where they
belong.

→ `doc/concepts/rdfs-entailment.md`

### Layer-targeting discipline + multi-protocol surface

The same metamodel is exposed simultaneously through HTTP REST,
the Model Context Protocol (JSON-RPC + SSE for AI clients), and
(incrementally) RDF / TTL. Every protocol layer projects from the
same `dt/*` API — there are no parallel schemas to keep in sync.
Adding a new protocol means adding a translator, not duplicating
the model.

→ `doc/concepts/mcp-protocol.md` · `doc/guides/writing-an-mcp-client.md` · `doc/guides/writing-a-rest-client.md`

### Codec layer absorbs wire-format complexity

Consumers talk in their native representation. The memory-corpus
consumer passes markdown; a future RDF consumer will pass
Turtle; an MCP client passes JSON. Sandbar's codec layer absorbs
the parse/emit and binds the result to the model — same
architectural shape as `dt/*` absorbing Datomic. Per-class
`:dt/native-codec` declares the default; the mediator resolves at
call time.

→ `doc/concepts/codec-layer.md` · `doc/guides/implementing-a-codec.md`

### Fulltext search via Datomic + Lucene + BM25F

`:db/fulltext` slots are queryable through Datomic's native
Lucene integration; Sandbar layers BM25F multi-field weighted
scoring on top — same canonical Robertson-Zaragoza form as the
corpus's reference implementation, with per-class
`:dt/bm25f-weights` declared at the schema layer. The analyzer
(Unicode-aware tokenizer + Porter stemmer) is metamodel-driven;
no consumer hardcoding. Result projection composes with the rest
of the retrieval surface — `:where` Datalog clauses, snippets,
facets, structural composition — all opts on one verb.

→ `doc/concepts/fulltext-search.md` · `doc/guides/searching-the-corpus.md`

### Aggregation primitives as first-class retrieval

`count` / `group-by` / structural-rank are substrate, not
application-layer. `degree`, `backlink-density`, `recency`, and
`freshness` are the four ranking axes — the substrate is
class-agnostic (temporal slots are caller-supplied; no hardcoded
knowledge of `:mm.memory/last-touched` etc.).
`sandbar.aggregate/{count-by,group-by,rank-by}` opts-shaped API +
MCP verbs + REST endpoints.

→ `doc/concepts/aggregation.md`

### Path-grammar navigation (Wilbur lineage)

Sandbar speaks Kleene-algebra-over-binary-relations as a
first-class navigation surface. EDN path expressions like
`[:SEQ [:REP* [:OR :cites :evidences]] [:RESTRICT [:type :decision]]]`
parse → canonicalize → compile to Datomic recursive rules.
Twenty-one operators committed (eighteen Wilbur-derived from
Nokia's 1989-2009 lineage + three SPARQL 1.1 parity additions);
the executable Canonical-8 + Tier-2 = thirteen operators today.
Paths are first-class values: `length`, `prefix`, `subpath`
compose. Three-layer DSL/IR/Backend architecture means a future
Asami or NFA backend is a translator, not a rewrite.

→ `doc/concepts/path-grammar.md` · `doc/concepts/navigation.md` · `doc/guides/navigating-with-paths.md`

### Bootstrap-by-discovery

Every non-abstract class is automatically discoverable through
every protocol. MCP `tools/list` walks `dt/all-classes`; JSON
Schema is reflected from `dt/range-of`. Add a class to the schema
and it auto-surfaces as a tool, a resource, a REST endpoint — no
hand-curated registries, no mapping tables, no server restart.

→ `doc/concepts/mcp-protocol.md`

### First-class functions and shapes

`:dt/Fn` lifts Datomic database functions into the metamodel as
typed citizens with mathematical-mapping signatures
(`:fn/domain` → `:fn/range`) and stored-procedure bodies
(`defdbfn`-emitted). `:mm/Fn` is the memorial-face — fns that
warrant corpus-FS visibility. Slot vocabulary mirrors SHACL-AF's
`sh:SPARQLFunction`. `:mm/Shape` provides SHACL-deeply-
incorporated validation: shapes declare invariants over classes
via `:mm.shape/applies-to`, with `:mm.shape/validator-fn` and
`:mm.shape/value-constraints` as the two composable check
surfaces. The metacircular closure: there is a shape that
validates shapes.

→ `doc/concepts/first-class-fn.md` · `doc/concepts/shape-validation.md` · `doc/guides/authoring-shapes.md`

### Workflows as first-class substrate

State machines are entities. `:mm/Workflow` is the
spec-classifier (memorial); `:workflow/Process` is a running
instance; MCP Tasks are workflow processes — `task-id` IS
`:db/id` (no parallel registry). Terminal states carry an outcome
classification (`:success` / `:failure` / `:cancel`) so consumers
don't reinvent the "what kind of done is this" projection.
Cancellation is workflow-substrate, not per-tool plumbing.

→ `doc/concepts/workflow-substrate.md` · `doc/guides/designing-workflows.md`

### Event substrate + reactive sinks

Datomic's `tx-report-queue` is the substrate-native source of
truth for change events; sandbar wraps it behind
`sandbar.reactive.tx-source` and translates each transaction into
typed `:dt/Event` instances. Subscribers register interest by
event class via `sandbar.event/subscribe`; class-hierarchical
dispatch via `dt/type-isa?` fans out — a subscriber on
`:event/ServerEvent` sees every `:event/HttpRequest`. Manifold is
the in-process transport, layered behind the dt/* boundary.
Memorial-flagged signals (`:data {:memorial :first-class}`)
project into `:mm/EventLog` corpus entries; transient runtime
events stay in the in-process bus.

→ `doc/concepts/event-substrate.md` · `doc/concepts/reactive-substrate.md` · `doc/guides/subscribing-to-events.md`

### Telemere-backed logging

`sandbar.logging` exposes six callsite macros (`info` / `warn` /
`error` / `debug` / `trace` / `profile`) backed by Telemere, with
compile-time elision, per-handler async buffers, and a curated
`:xfn` middleware that normalizes signal shape. The
memorial-projection handler bridges flagged signals onto the same
event substrate that fans out to reactive sinks — one unified
event flow, three audiences (operator console, structured
handlers, corpus memorialization).

→ `doc/concepts/logging-substrate.md` · `doc/guides/using-logging.md`

### Substrate boundary + retarget-capability

Datomic-specific behavior is encapsulated behind `dt/*` (and its
boundary siblings `sandbar.reactive.tx-source`,
`sandbar.schedule.recurrence`, etc.). The application-shape model
is therefore portable: a future XTDB or Asami backend is a
translator, not a rewrite. The boundary is a design discipline,
not an artifact — every consumer touchpoint that reaches for a
backend feature is a boundary violation and a refactor target.

→ `doc/concepts/multi-store-architecture.md`

### Temporal substrate (γ scheduler live)

Schedules, jobs, and runs as ontology-aligned memorial entities:
`:mm/Schedule` carries RFC 5545 RRULE recurrence; `:mm/Job` is
PROV-O `prov:Plan`-shaped; `:mm/Run` is `prov:Activity`-shaped
with `prov:startedAtTime` / `prov:endedAtTime` /
`prov:wasInformedBy`. Allen's 13 interval relations from OWL-Time
become first-class typed-edge predicates. The γ scheduler runs
live as of 0.2.0 — RRULE recurrence boundary, native min-heap
fire-thread, Run lifecycle, eight `sandbar_schedule_*` MCP verbs,
and boot-time system jobs. The four ontology alignments (OWL-Time
+ RFC 5545 + PROV-O + Schema.org Schedule JSON-LD) are the
load-bearing layer; the runtime dispatcher is replaceable.

→ `doc/concepts/temporal-substrate.md`

### Filesystem-canonical projection (Anderson lineage)

The filesystem format is the canonical ground-truth. Sandbar's
`sandbar.projection/project-graph` + `ingest-graph` primitives
are bidirectional — DB state ↔ filesystem hierarchy of
native-format files. Any backend complies with the filesystem
format. Document chunks are addressable entities with their own
URIs and sibling-chain navigation (`:next-sibling` /
`:previous-sibling`, RDFS-inspired). The pattern borrows from
James Anderson's `de.setf.rdf:project-graph` (Datagraph/Dydra-era
CL CLOS-metaclass framework) and applies it to filesystem
hierarchies as the native projection target.

→ `doc/concepts/projection.md`

### Hybrid filesystem/database topology (experimental)

The partition between what lives on disk and what lives in the
runtime DB is an open architectural question we're actively
exploring. Filtering primitives on `project.export` /
`project.import` exist precisely to enable this experimentation.
Today, both sides are first-class. Tomorrow's answer depends on
what measurement reveals.

→ `doc/concepts/multi-store-architecture.md`

### Opt-in capabilities — passive memory model first, alive substrate when called for

Reactivity, scheduling, workflows, validation, logging,
projection — each is a layered capability driven by first-class
memorial entities. A deployment without `:mm/Schedule` instances
has no scheduler running. A consumer that only reads memorials
never has a reactive sink fire. The passive baseline (graph
store + retrieval surface + MCP server) is preserved; the
capabilities compose above it. The AI client can introspect and
adjust the runtime control plane through the same MCP verbs it
uses to read the corpus.

## Three concrete examples

### Example 1 — Clojure, in-process

Define a class hierarchy, create a validated instance, query the
metamodel:

```clojure
(require '[sandbar.db.datatype :as dt])

;; Classes describe themselves
(dt/make :dt/Class
  {:db/ident :order/Order
   :dt/subclass-of :dt/Resource
   :dt/slots [:order/customer :order/total :order/status]})

;; Create a validated instance
(dt/make :order/Order
  {:order/customer customer-entity
   :order/total    299.99M
   :order/status   :order/pending})
;; => entity; validation passed; transacted

;; Introspect at runtime
(dt/slots-of      :order/Order)        ; #{:order/customer :order/total ...}
(dt/instance-of?  :order/Order order)  ; true
(dt/all-instances-of :dt/Resource)     ; every entity, including order
```

### Example 2 — AI client (Claude or other MCP consumer)

Discover the surface; create an entity from markdown source; read
it back:

```bash
export SANDBAR_TOKEN="<your-service-account-token>"

# 1. Discover available tools (bootstrap-by-discovery)
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 2. Create an mm/Memory entity by passing markdown source —
#    codec layer absorbs the parse + class-binding
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":
        {"name":"sandbar_entity_create",
         "arguments":{"class":"mm/Memory",
                       "format":"markdown",
                       "source":"---\nname: Foo\n---\n# Context\n..."}}}'

# 3. Read it back as markdown (full section tree reconstructed)
curl -X POST http://localhost:8389/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"resources/read",
        "params":{"uri":"mcp://sandbar/mm/Memory/decisions/foo"}}'
```

→ `doc/guides/writing-an-mcp-client.md` for full client patterns
→ `doc/guides/zorp-tutorial.md` for a complete worked example

### Example 3 — The four-axis retrieval surface

Walk a typed-edge graph; rank the result; project paths. Three
composable axes in one short example:

```clojure
(require '[sandbar.search    :as search]
         '[sandbar.aggregate :as agg]
         '[sandbar.navigate.path :as path])

;; Fulltext — BM25F across :mm/Memory's weighted slots
(search/search-bm25f
  {:class :mm/Memory
   :query "datomic recursive rules"
   :limit 10
   :include [:snippets :scores]})

;; Aggregation — group memories by type, rank-by backlink-density
(agg/group-by {:class :mm/Memory :group-by :mm.memory/memory-type})
(agg/rank-by  {:class :mm/Memory :rank-by :backlink-density :limit 10})

;; Path-grammar — walk the typed-edge graph with Kleene closure
(path/path-via
  {:from :decisions/some-anchor
   :via  [:SEQ [:REP* [:OR :cites :evidences]]
              [:RESTRICT [:dt/type :mm.memory/decision]]]})
```

Each axis is also a stable MCP verb (wire names
`sandbar_search_bm25f`, `sandbar_aggregate_rank-by`,
`sandbar_navigate_path-via` — dots project to underscores at the
wire; the dotted forms remain accepted for one release as a
deprecation alias) and a REST endpoint
(`GET /api/aggregate/rank-by`, `GET /api/navigate/path`). Same
model, three projections.

→ `doc/concepts/path-grammar.md` for the algebra · `doc/guides/navigating-with-paths.md` for worked patterns

## Quick start

```bash
# Prerequisites: Java 11+, Leiningen, Datomic transactor running
git clone <repository-url> && cd sandbar
lein deps && lein repl

# In the REPL
(require '[sandbar.core :refer [go]])
(go)  ; HTTP on :8389; nREPL on :28888

# Sanity check (in another shell)
curl http://localhost:8389/api/status
```

`bin/sandbar start` is the supported lifecycle entrypoint (start /
stop / status / token rotation / backup); the REPL path above is
the development-loop equivalent.

→ `doc/guides/getting-started.md` for the full onboarding path
→ `doc/guides/quickstart.md` for the five-minute hands-on tour


## Project layout

```
sandbar/
├── schema/             EDN class + property definitions
├── src/sandbar/
│   ├── codec.clj       Mediator + per-class :dt/native-codec resolution
│   ├── codec/          Codec protocol + markdown + JSON
│   ├── projection.clj  Anderson-style FS↔DB projection
│   ├── search.clj      BM25F + multi-field weighted scoring
│   ├── search/         search.analysis (Porter + Unicode) + search.bm25f
│   ├── aggregate.clj   count-by / group-by / rank-by (4 structural axes)
│   ├── navigate/       edges / walk / path (Wilbur path-grammar)
│   │   └── path/       ast / ir / datomic / value
│   ├── shape.clj       :mm/Shape validation; SHACL-deeply-incorporated
│   ├── reactive.clj    Reactive substrate facade
│   ├── reactive/       queue + sinks (tx-source bridge layered behind the boundary)
│   ├── logging.clj     sandbar.logging six-macro callsite API
│   ├── logging/        Telemere bridge: config / format / handlers / init
│   ├── db/             dt/* model API + Datomic peer connection
│   │   ├── datatype.clj  Core dt/* primitives (Class / Property / Ref / Fn / Event)
│   │   ├── entailment/   RDFS entailment + OWL 2 RL extensions
│   │   ├── fn.clj        defdbfn + :dt/Fn first-class bridge
│   │   └── rules.clj     Datalog rules supporting subsumption + entailment
│   ├── mcp/            MCP server (transport / protocol / tools / resources / prompts / tasks)
│   ├── api/            REST handlers (store / aggregate / navigate / workflow / event / job / auth)
│   ├── service/        Routing + validation-as-workflow
│   └── util/           Auth (Buddy-hashers) / events / workflow lifecycle
└── doc/                Layered documentation (Layer 2 + 3 + 4)
```

## Running tests

```bash
lein test                                              # full suite
lein test :only sandbar.codec.markdown-test            # one namespace
lein test :only sandbar.datatype-test/make-test        # one deftest
```

## FAQ

**Q: Is this OWL/RDF?**
A: Inspired by RDFS, with selected OWL 2 RL entailments layered
in via `sandbar.db.entailment`. Closed-world by default; no
external reasoner; no PhD required. The metamodel is closer to
KL-ONE-shaped frames-with-inheritance than to OWL DL.

**Q: Why both REST and MCP?**
A: Different consumers; same metamodel. Traditional HTTP clients
want REST. AI clients want JSON-RPC with reflective tool
discovery + push notifications. Both projections come from the
same `dt/*` introspection — no parallel models to keep in sync.

**Q: How does the codec layer relate to Datomic's serialization?**
A: It doesn't. Datomic handles in-store representation; codecs
handle wire format at the protocol boundary. The codec layer
absorbs format complexity from consumers, the same way `dt/*`
absorbs Datomic query complexity.

**Q: How does path-grammar compare to SPARQL property paths or Cypher relationship patterns?**
A: Sandbar's path-grammar shares the same Kleene-algebra-over-
binary-relations spine. Wilbur (Lassila 1989, Nokia 2001-2009) is
the source-of-truth lineage; SPARQL 1.1 (2013) formalized the
same algebra independently; Cypher's variable-length paths
converge on the same surface. Sandbar inherits the algebra,
ships subset-first (Canonical-8 + Tier-2 = 13 operators executable
today; Tier-3 vocabulary-registered but compilation deferred), and
exposes paths as EDN-native first-class values (`length`, `prefix`,
`subpath`). The three-layer DSL/IR/Backend architecture means a
future Asami or NFA × graph-product backend is a translator, not
a rewrite.

**Q: Why BM25F instead of plain BM25 or Lucene's default Similarity?**
A: BM25F is the multi-field weighted form that Lucene's
single-field BM25 doesn't natively express. Per-class
`:dt/bm25f-weights` declare slot weights at the metamodel layer
(e.g., `:mm.memory/name` 12.0 vs `:mm.memory/body-raw` 1.0); the
analyzer (Unicode tokenizer + Porter stemmer) is metamodel-driven
and matches the corpus's reference implementation byte-for-byte.

**Q: Is the event substrate a job queue?**
A: No. It's a typed-event in-process bus backed by Datomic's
`tx-report-queue` and Manifold streams. Subscribers register by
event CLASS (not topic), and class-hierarchical dispatch fans out
through `dt/type-isa?`. The scheduler / job system (the γ
scheduler, live as of 0.2.0) uses the event substrate as its
emission surface but isn't itself the event substrate.

**Q: What's a memorial?**
A: A first-class entity that participates in the corpus —
decisions, plans, observations, syntheses, libraries, logs, runs,
shapes, fns. Memorials descend from `:mm/Memory`; their
`:dt/memorial-policy` declares whether instances project to the
filesystem (`:first-class`), stay DB-only (`:db-only`), embed
inline (`:inline`), or remain transient (nil). The `mm/*`
classes are the user-facing layer; `dt/*` is the substrate that
defines them.

## License

Copyright (C) Dan Lentz
