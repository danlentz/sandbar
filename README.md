# Sandbar

> A metacircular metamodel platform — RDFS-style classes + properties + inheritance on Datomic, exposed simultaneously through HTTP, MCP, and (incrementally) other protocols.  Equipped with a four-axis retrieval surface (fulltext search with BM25F, structural + temporal aggregation, typed-edge navigation with Wilbur-lineage path-grammar, and orientation) built on the same metamodel primitives.  The type system is data, queryable + evolvable at runtime through the same API you use to query your application's entities.  The wire-format layer is substrate, not application concern.  Long-running operations have history, cancellation, and outcome classification baked in.

Sandbar exists because the things that make a *database* powerful (transactions, time, expressive query) and the things that make a *type system* powerful (classes, inheritance, validation) and the things that make a *retrieval engine* powerful (relevance ranking, graph traversal, aggregation) and the things that make a *protocol surface* powerful (reflection, content negotiation, push notifications) keep wanting to be the same things.  Each layer in Sandbar is the previous layer talking to itself.

The value: a substrate where "find by content," "walk the typed-edge graph from this seed," "rank by structural prominence," and "describe yourself" are all one-line queries against a single coherent model — not four separate libraries glued together.

This README is a 5-minute elevator.  For depth, follow the pointers into `doc/concepts/` (theoretical reference, citation-rich) and `doc/guides/` (hands-on how-to).

## Documentation map

Documentation is layered.  Every entry below is a link; pick the layer that matches your goal.

- **`doc/concepts/`** — Layer 2: theoretical reference (citation-rich)
  - [`metamodel.md`](doc/concepts/metamodel.md) — The dt/* primitives; RDFS / KL-ONE / CLOS-MOP lineage
  - [`codec-layer.md`](doc/concepts/codec-layer.md) — Boundary-layer abstraction; per-class `:dt/native-codec`
  - [`projection.md`](doc/concepts/projection.md) — Bidirectional FS↔DB projection (Anderson lineage)
  - [`fulltext-search.md`](doc/concepts/fulltext-search.md) — BM25F multi-field weighted scoring; analyzer
  - [`aggregation.md`](doc/concepts/aggregation.md) — count / group-by / structural-rank; 4 ranking axes
  - [`navigation.md`](doc/concepts/navigation.md) — Edges / walk / path-grammar overview
  - [`path-grammar.md`](doc/concepts/path-grammar.md) — Wilbur algebra; 21-operator vocabulary
  - [`workflow-substrate.md`](doc/concepts/workflow-substrate.md) — First-class workflows; terminal-kind classification
  - [`mcp-protocol.md`](doc/concepts/mcp-protocol.md) — MCP; bootstrap-by-discovery; operational verb catalog
  - [`multi-store-architecture.md`](doc/concepts/multi-store-architecture.md) — Multi-store topology; hybrid FS/DB experimentation
  - [`markdown-as-canonical.md`](doc/concepts/markdown-as-canonical.md) — Markdown as canonical Layer-1 corpus format

- **`doc/guides/`** — Layer 3: hands-on how-to
  - [`quickstart.md`](doc/guides/quickstart.md) — Get Sandbar running in 5 minutes
  - [`zorp-tutorial.md`](doc/guides/zorp-tutorial.md) — Worked example — classes + validation + queries
  - [`writing-a-clojure-client.md`](doc/guides/writing-a-clojure-client.md) — Embed Sandbar in your Clojure code
  - [`writing-an-mcp-client.md`](doc/guides/writing-an-mcp-client.md) — Connect Claude or other AI client via MCP
  - [`writing-a-rest-client.md`](doc/guides/writing-a-rest-client.md) — Consume Sandbar over HTTP REST
  - [`searching-the-corpus.md`](doc/guides/searching-the-corpus.md) — BM25F fulltext patterns; `:where` + `:facet-by` composition
  - [`navigating-with-paths.md`](doc/guides/navigating-with-paths.md) — Path-grammar worked examples; Canonical-8 + Tier-2
  - [`implementing-a-codec.md`](doc/guides/implementing-a-codec.md) — Author a codec for a new wire format
  - [`defining-new-classes.md`](doc/guides/defining-new-classes.md) — Extend the schema with new mm/* or domain classes
  - [`designing-workflows.md`](doc/guides/designing-workflows.md) — Author state machines with terminal-kind
  - [`sandbar-as-substrate.md`](doc/guides/sandbar-as-substrate.md) — Embed Sandbar in your own application

- **`doc/api/`** — Layer 4: mechanical reference
  - [`dt-star.md`](doc/api/dt-star.md) — Every dt/* function signature
  - [`http-rest.md`](doc/api/http-rest.md) — Every REST endpoint
  - [`mcp-verbs.md`](doc/api/mcp-verbs.md) — Every MCP verb in the catalog
  - [`codec-protocol.md`](doc/api/codec-protocol.md) — The Codec defprotocol

**Reading order suggestions:**

- **New here, evaluating Sandbar:** [`doc/concepts/metamodel.md`](doc/concepts/metamodel.md) → ["What makes Sandbar interesting"](#what-makes-sandbar-interesting) below → [`doc/guides/quickstart.md`](doc/guides/quickstart.md)
- **AI / MCP client author:** [`doc/concepts/mcp-protocol.md`](doc/concepts/mcp-protocol.md) → [`doc/guides/writing-an-mcp-client.md`](doc/guides/writing-an-mcp-client.md) → [`doc/api/mcp-verbs.md`](doc/api/mcp-verbs.md)
- **Building a retrieval-heavy consumer:** [`doc/concepts/fulltext-search.md`](doc/concepts/fulltext-search.md) + [`doc/concepts/navigation.md`](doc/concepts/navigation.md) + [`doc/concepts/aggregation.md`](doc/concepts/aggregation.md) → guides in `doc/guides/searching-the-corpus.md` + `navigating-with-paths.md`
- **Embedding in a Clojure application:** [`doc/guides/sandbar-as-substrate.md`](doc/guides/sandbar-as-substrate.md) → [`doc/api/dt-star.md`](doc/api/dt-star.md)
- **Adding a new wire format:** [`doc/concepts/codec-layer.md`](doc/concepts/codec-layer.md) → [`doc/guides/implementing-a-codec.md`](doc/guides/implementing-a-codec.md) → [`doc/api/codec-protocol.md`](doc/api/codec-protocol.md)

## What makes Sandbar interesting

Sandbar's individual ingredients exist elsewhere. The unique value is in the *synthesis* — how these ingredients combine into one substrate with a consistent discipline.

### Metacircular RDFS on Datomic

RDFS gave us a clean vocabulary for classes, properties, inheritance, and predicates. Datomic gave us schema-on-read, first-class time, and expressive query. Sandbar stores its own type system inside Datomic using its own type system — `:dt/Class` is itself an instance of `:dt/Class`. Adding a class is a transaction; introspecting the schema is a query. Application data and metadata flow through the same `dt/*` API.

→ `doc/concepts/metamodel.md` for theory + citations

### Layer-targeting discipline + multi-protocol surface

The same metamodel is exposed simultaneously through HTTP REST, the Model Context Protocol (JSON-RPC + SSE for AI clients), and (incrementally) RDF / TTL. Every protocol layer projects from the same `dt/*` API — there are no parallel schemas to keep in sync. Adding a new protocol means adding a translator, not duplicating the model.

→ `doc/concepts/mcp-protocol.md` · `doc/guides/writing-an-mcp-client.md` · `doc/guides/writing-a-rest-client.md`

### Codec layer absorbs wire-format complexity

Consumers talk in their native representation. The memory-corpus consumer passes markdown; a future RDF consumer will pass Turtle; an MCP client passes JSON. Sandbar's codec layer absorbs the parse/emit and binds the result to the model — same architectural shape as `dt/*` absorbing Datomic. Per-class `:dt/native-codec` declares the default; the mediator resolves at call time.

→ `doc/concepts/codec-layer.md` · `doc/guides/implementing-a-codec.md`

### Fulltext search via Datomic + Lucene + BM25F

`:db/fulltext` slots are queryable through Datomic's native Lucene integration; Sandbar layers BM25F multi-field weighted scoring on top — same canonical Robertson-Zaragoza form as the corpus's reference implementation, with per-class `:dt/bm25f-weights` declared at the schema layer.  The analyzer (Unicode-aware tokenizer + Porter stemmer) is metamodel-driven; no consumer hardcoding.  Result projection composes with the rest of the retrieval surface — `:where` Datalog clauses, snippets, facets, structural composition — all opts on one verb.

→ `doc/concepts/fulltext-search.md` · `doc/guides/searching-the-corpus.md`

### Aggregation primitives as first-class retrieval

`count` / `group-by` / structural-rank are substrate, not application-layer.  `degree`, `backlink-density`, `recency`, and `freshness` are the four ranking axes — the substrate is class-agnostic (temporal slots are caller-supplied; no hardcoded knowledge of `:mm.memory/last-touched` etc.).  `sandbar.aggregate/{count-by,group-by,rank-by}` opts-shaped API + MCP verbs + REST endpoints.

→ `doc/concepts/aggregation.md`

### Path-grammar navigation (Wilbur lineage)

Sandbar speaks Kleene-algebra-over-binary-relations as a first-class navigation surface.  EDN path expressions like `[:SEQ [:REP* [:OR :cites :evidences]] [:RESTRICT [:type :decision]]]` parse → canonicalize → compile to Datomic recursive rules.  Twenty-one operators committed (eighteen Wilbur-derived from Nokia's 1989-2009 lineage + three SPARQL 1.1 parity additions); the executable Canonical-8 + Tier-2 = thirteen operators today.  Paths are first-class values: `length`, `prefix`, `subpath` compose.  Three-layer DSL/IR/Backend architecture means a future Asami or NFA backend is a translator, not a rewrite.

→ `doc/concepts/path-grammar.md` · `doc/concepts/navigation.md` · `doc/guides/navigating-with-paths.md`

### Bootstrap-by-discovery

Every non-abstract class is automatically discoverable through every protocol. MCP `tools/list` walks `dt/all-classes`; JSON Schema is reflected from `dt/range-of`. Add a class to the schema and it auto-surfaces as a tool, a resource, a REST endpoint — no hand-curated registries, no mapping tables, no server restart.

→ `doc/concepts/mcp-protocol.md`

### Workflows as first-class substrate

State machines are entities. Processes are running instances. MCP Tasks are workflow processes — `task-id` IS `:db/id` (no parallel registry). Terminal states carry an outcome classification (`:success` / `:failure` / `:cancel`) so consumers don't reinvent the "what kind of done is this" projection. Cancellation is workflow-substrate, not per-tool plumbing.

→ `doc/concepts/workflow-substrate.md` · `doc/guides/designing-workflows.md`

### Filesystem-canonical projection (Anderson lineage)

The filesystem format is the canonical ground-truth.  Sandbar's `sandbar.projection/project-graph` + `ingest-graph` primitives are bidirectional — DB state ↔ filesystem hierarchy of native-format files.  Any backend complies with the filesystem format.  Document chunks are addressable entities with their own URIs and sibling-chain navigation (`:next-sibling` / `:previous-sibling`, RDFS-inspired).  The pattern borrows from James Anderson's `de.setf.rdf:project-graph` (Datagraph/Dydra-era CL CLOS-metaclass framework) and applies it to filesystem hierarchies as the native projection target.

→ `doc/concepts/projection.md`

### Hybrid filesystem/database topology (experimental)

The partition between what lives on disk and what lives in the runtime DB is an open architectural question we're actively exploring. Filtering primitives on `project.export` / `project.import` exist precisely to enable this experimentation. Today, both sides are first-class. Tomorrow's answer depends on what measurement reveals.

→ `doc/concepts/multi-store-architecture.md`

## Three concrete examples

### Example 1 — Clojure, in-process

Define a class hierarchy, create a validated instance, query the metamodel:

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

Discover the surface; create an entity from markdown source; read it back:

```bash
export SANDBAR_TOKEN="<your-service-account-token>"

# 1. Discover available tools (bootstrap-by-discovery)
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# 2. Create an mm/Memory entity by passing markdown source —
#    codec layer absorbs the parse + class-binding
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":
        {"name":"sandbar.entity.create",
         "arguments":{"class":"mm/Memory",
                       "format":"markdown",
                       "source":"---\nname: Foo\n---\n# Context\n..."}}}'

# 3. Read it back as markdown (full section tree reconstructed)
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"resources/read",
        "params":{"uri":"mcp://sandbar/mm/Memory/decisions/foo"}}'
```

→ `doc/guides/writing-an-mcp-client.md` for full client patterns
→ `doc/guides/zorp-tutorial.md` for a complete worked example

### Example 3 — The four-axis retrieval surface

Walk a typed-edge graph; rank the result; project paths.  Three composable axes in one short example:

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

Each axis is also a stable MCP verb (`sandbar.search.bm25f`, `sandbar.aggregate.rank-by`, `sandbar.navigate.path-via`) and a REST endpoint (`GET /api/aggregate/rank-by`, `GET /api/navigate/path`).  Same model, three projections.

→ `doc/concepts/path-grammar.md` for the algebra · `doc/guides/navigating-with-paths.md` for worked patterns

## Quick start

```bash
# Prerequisites: Java 11+, Leiningen, Datomic transactor running
git clone <repository-url> && cd sandbar
lein deps && lein repl

# In the REPL
(require '[sandbar.core :refer [go]])
(go)  ; HTTP on :8080; nREPL on :28888

# Sanity check (in another shell)
curl http://localhost:8080/api/status
```

→ `doc/guides/quickstart.md` for the 5-minute hands-on tour

## Where to learn more

Documentation follows a four-layer structure. This README is Layer 1. Pick the layer that matches your goal.

### Layer 2 — Concepts (theoretical reference)

Citation-rich documents explaining how Sandbar works, with references to the source papers and standards.

| Document | What you'll learn |
|----------|-------------------|
| `doc/concepts/metamodel.md` | The dt/* primitives; RDFS lineage; metacircularity |
| `doc/concepts/codec-layer.md` | Boundary-layer abstraction; consumer-native representation; per-class `:dt/native-codec` |
| `doc/concepts/projection.md` | Bidirectional FS↔DB projection; Anderson lineage; chunk addressability |
| `doc/concepts/fulltext-search.md` | BM25F multi-field weighted scoring; Unicode tokenizer + Porter stemmer; metamodel-driven analyzer |
| `doc/concepts/aggregation.md` | `count` / `group-by` / structural-rank; the four ranking axes (degree / backlink-density / recency / freshness) |
| `doc/concepts/navigation.md` | Inbound / outbound edges; BFS graph-walk with hop-cap + path projection; path-grammar pointer |
| `doc/concepts/path-grammar.md` | Wilbur algebra; Kleene-over-relations; 21-operator vocabulary; three-layer DSL/IR/Backend |
| `doc/concepts/workflow-substrate.md` | First-class workflows; terminal-kind classification; MCP Tasks composition |
| `doc/concepts/mcp-protocol.md` | Model Context Protocol; bootstrap-by-discovery; operational verb catalog |
| `doc/concepts/multi-store-architecture.md` | Multi-store topology; federated query; hybrid FS/DB experimentation |
| `doc/concepts/markdown-as-canonical.md` | Markdown as canonical Layer-1 corpus format; FS-format-is-ground-truth |

### Layer 3 — Guides (practical how-to)

Hands-on documents with runnable examples.

| Document | What you'll learn |
|----------|-------------------|
| `doc/guides/quickstart.md` | Get Sandbar running in 5 minutes |
| `doc/guides/zorp-tutorial.md` | Worked example — class hierarchy + validation + queries |
| `doc/guides/writing-a-clojure-client.md` | Embed Sandbar in your Clojure code; dt/* idioms |
| `doc/guides/writing-an-mcp-client.md` | Connect Claude or other AI client via MCP |
| `doc/guides/writing-a-rest-client.md` | Consume Sandbar over HTTP REST |
| `doc/guides/searching-the-corpus.md` | BM25F fulltext queries; per-class weights; snippets + facets |
| `doc/guides/navigating-with-paths.md` | Author path-grammar expressions; Canonical-8 + Tier-2 worked examples |
| `doc/guides/implementing-a-codec.md` | Author a codec for a new wire format |
| `doc/guides/defining-new-classes.md` | Extend the schema with new mm/* or domain classes |
| `doc/guides/designing-workflows.md` | Author state machines with terminal-kind |
| `doc/guides/sandbar-as-substrate.md` | Embed Sandbar in your own application |

### Layer 4 — API Reference (mechanical)

| Document | Coverage |
|----------|----------|
| `doc/api/dt-star.md` | Every `dt/*` function signature |
| `doc/api/http-rest.md` | Every REST endpoint |
| `doc/api/mcp-verbs.md` | Every MCP verb in the catalog |
| `doc/api/codec-protocol.md` | The Codec defprotocol |

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
│   ├── db/             dt/* model API + Datomic peer connection
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
A: Inspired by RDFS, but simpler. Closed-world; no inference engine; no PhD required. The metamodel is closer to KL-ONE-shaped frames-with-inheritance than to OWL DL.

**Q: Why both REST and MCP?**
A: Different consumers; same metamodel. Traditional HTTP clients want REST. AI clients want JSON-RPC with reflective tool discovery + push notifications. Both projections come from the same `dt/*` introspection — no parallel models to keep in sync.

**Q: How does the codec layer relate to Datomic's serialization?**
A: It doesn't. Datomic handles in-store representation; codecs handle wire format at the protocol boundary. The codec layer absorbs format complexity from consumers, the same way `dt/*` absorbs Datomic query complexity.

**Q: What's the relationship between Sandbar and the memory-model corpus?**
A: The corpus (this codebase's `memory/` tree, mirroring its own development history) is the first consumer.  The corpus's markdown shape informs Sandbar's `codec.markdown` + `mm/*` classes.  Per the filesystem-canonical directive, the FS shape is ground-truth — Sandbar serves it.  Sandbar's four-axis retrieval surface (search / aggregate / navigate / orient) is built to absorb the corpus's `/memory-*` tooling concerns into the substrate.

**Q: How does path-grammar compare to SPARQL property paths or Cypher relationship patterns?**
A: Sandbar's path-grammar shares the same Kleene-algebra-over-binary-relations spine.  Wilbur (Lassila 1989, Nokia 2001-2009) is the source-of-truth lineage; SPARQL 1.1 (2013) formalized the same algebra independently; Cypher's variable-length paths converge on the same surface.  Sandbar inherits the algebra, ships subset-first (Canonical-8 + Tier-2 = 13 operators executable today; Tier-3 vocabulary-registered but compilation deferred), and exposes paths as EDN-native first-class values (`length`, `prefix`, `subpath`).  The three-layer DSL/IR/Backend architecture means a future Asami or NFA × graph-product backend is a translator, not a rewrite.

**Q: Why BM25F instead of plain BM25 or Lucene's default Similarity?**
A: BM25F is the multi-field weighted form that Lucene's single-field BM25 doesn't natively express.  Per-class `:dt/bm25f-weights` declare slot weights at the metamodel layer (e.g., `:mm.memory/name` 12.0 vs `:mm.memory/body-raw` 1.0); the analyzer (Unicode tokenizer + Porter stemmer) is metamodel-driven and matches the corpus's reference implementation byte-for-byte.

**Q: Can I use this in production today?**
A: 0.1.0 is the first published release.  It's an early library; expect ergonomic churn.  The substrate design is stabilizing through the codec arc + comprehensive memory-model surface work; production-readiness is the focus of 0.x → 0.y iteration.

## License

Copyright (C) Dan Lentz
