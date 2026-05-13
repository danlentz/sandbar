# Multi-Store Architecture

> An **active research direction**, not a settled design.  The question of how Sandbar's storage tiers — filesystem-canonical hierarchy, runtime DB, optional secondary indices — should partition workload is open.  This document explains the *frame* the question lives inside, the primitives Sandbar exposes to enable empirical investigation, and the trade-off axes the answer must navigate.  For the design rationale on filesystem-canonical commitment see [`project-graph.md`](project-graph.md); for the codec-layer boundary see [`codec-layer.md`](codec-layer.md).

## Thesis

Sandbar's storage layer is not a single store — it is a **topology** of stores held in coherence by `project-graph` / `ingest-graph` (see [`project-graph.md`](project-graph.md)).  The filesystem hierarchy is canonical ground-truth; the runtime database is a projected view; auxiliary indices (full-text, vector embeddings, link graphs) may exist as additional projections.

Today, the production deployment is a single Datomic Peer paired with one filesystem hierarchy.  That is the *simplest* point in the design space, not the *settled* one.  The frame Sandbar is built around — codec layer absorbs wire format, project-graph absorbs FS↔DB translation, filters constrain projection — is deliberately constructed to make multi-store experimentation cheap.

This document explains the frame and the open questions.  It does not claim answers; the answers come from measurement.

## Lineage

### Polyglot persistence

Sadalage & Fowler (2012, *NoSQL Distilled*) argued that no single storage paradigm is right for every kind of data; applications increasingly mix relational, document, key-value, graph, and search stores per workload.  Sandbar adopts the mindset — but with discipline: the *model* is unified through the metamodel; the *stores* are projections of that model.  No store has its own schema; all share the `:dt/Class` definitions.

### Federated query

SPARQL 1.1 Federated Query (Buil-Aranda et al, 2013) standardized the *query a graph that spans multiple endpoints* pattern.  Sandbar's federated-query story is younger and less developed — today queries hit one store at a time — but the model permits the generalization: a query that says "find me decisions newer than X and their cited memories' embeddings nearer than ε" could in principle span three stores (Datomic for the metadata; filesystem for the content; vector store for the embeddings).  This is a planned exploration, not a current capability.

### CAP theorem

Brewer (2000) and the subsequent CAP-theorem literature established that distributed stores must trade off consistency, availability, and partition-tolerance.  Sandbar's tiered storage faces a milder version: the *coherence* between FS and DB is eventually consistent (a `project-graph` happens at specific moments; the FS and DB may diverge between projections); the *availability* properties are different per store (filesystem is locally-available; Datomic peer is locally-available; remote stores would not be).

Sandbar's coherence model is *transactional inside a single store*; *eventual across stores*.  The discipline is to make the divergence intervals short and visible.

### Datomic's history-as-database

Datomic's central insight (Hickey, 2012) — that a database is a *value*; that history is *first-class*; that time-travel queries are natural — is what makes the tiered story work.  When a `project-graph` operation produces FS state from DB state, the operation is *deterministic for a given DB value*.  Two `project-graph` operations against the same DB value produce identical filesystems.  This determinism is the precondition for diff-based coherence checks across stores.

## The frame

Three storage tiers, each with a defined role:

```
┌─────────────────────────────────────────────────────────────────┐
│  Tier 1 — Filesystem-Canonical Hierarchy                        │
│  ────────────────────────────────────────                        │
│  Source-of-truth for human-authored content.                     │
│  External tooling (vim, git, grep) operates directly.            │
│  Permanent; survives all backend turnover.                       │
└─────────────────────────────────────────────────────────────────┘
                 ↕  project-graph / ingest-graph
┌─────────────────────────────────────────────────────────────────┐
│  Tier 2 — Runtime Database (Datomic Peer)                       │
│  ────────────────────────────────────                            │
│  Indexed for query — schema, ancestry, slot lookup, type checks. │
│  Holds workflow processes, validation history, MCP subscriptions.│
│  Ephemeral; reconstructible from Tier 1 + history.               │
└─────────────────────────────────────────────────────────────────┘
                 ↕  (planned) projection / mirror
┌─────────────────────────────────────────────────────────────────┐
│  Tier 3 — Auxiliary Stores (planned)                            │
│  ──────────────────────────                                      │
│  Full-text indices, vector embeddings, link graphs, …            │
│  Each projected from Tier 2 (or directly from Tier 1).           │
│  Independently rebuildable; not authoritative for any class.     │
└─────────────────────────────────────────────────────────────────┘
```

The discipline: **no class is authoritatively owned by Tier 2 alone**.  Every class's ground-truth lives in Tier 1.  Tier 2 is fast indexed access; Tier 3 is specialized indexed access.  Both are *derivable*.

The exception that proves the rule: workflow processes and MCP subscriptions.  Both are *ephemeral runtime state*, not human-authored content.  These legitimately live in Tier 2 only — there is no canonical filesystem form for a half-running process or an active subscription.  This is a deliberate boundary: ephemeral state lives in Tier 2; content lives in Tier 1.

## What the filters enable

`project-graph`'s `:filter` option (see [`project-graph.md`](project-graph.md#filtering-primitives)) is the experimentation surface.  The questions it lets us answer empirically:

1. **Which classes deserve full FS-mirror?**  Test by projecting only those classes and measuring developer ergonomics — does external tooling produce useful results?  Does git diff stay readable?
2. **Which classes deserve DB-resident-only?**  Test by projecting *without* those classes and measuring functionality — does the corpus still work?  Do consumers notice the absence?
3. **Which classes deserve auxiliary-index-only (Tier 3) without DB mirror?**  Test by holding entries in Tier 1 + Tier 3, skipping Tier 2 for that class, and measuring query latency.

These experiments are cheap because the substrate makes them cheap.  The filters are a research tool.

## The substrate-first commitment

This frame is the load-bearing answer to a deeper question: *should Sandbar evolve to meet the corpus's needs, or should the corpus work around Sandbar's limitations?*

The standing directive (captured in [`interaction/substrate_first_friction_corpus_unmet_needs_signal_sandbar_evolution_2026_05_13`](../../memory/interaction/substrate_first_friction_corpus_unmet_needs_signal_sandbar_evolution_2026_05_13.md)) is **substrate-first**: when the corpus has unmet needs, look at the substrate.  Tactical workarounds inside the corpus accumulate and become indistinguishable from the corpus's *real* shape; substrate-level evolution stays clean and benefits every consumer.

This commitment is why multi-store is a substrate concern, not an application concern.  When the corpus eventually needs vector search (it will), the right move is to add a Tier 3 vector-store *as a substrate-level projection* — every other consumer benefits — rather than adding a vector index *inside the corpus* that no other application can use.

## Open questions

Stated openly, with no claim of resolution:

1. **Sync semantics — push vs pull.**  Today, `project-graph` is invoked explicitly.  Should it run continuously?  On every transaction?  On a debounced timer?  The right answer depends on the workload (high-volume writes → debounced; low-volume edits → on-transaction).
2. **Conflict resolution at the FS layer.**  If two processes write to the same FS path simultaneously, who wins?  Git's `merge`-with-conflict-markers model is one option; last-writer-wins is another; CRDT-shaped reconciliation a third.  No current production has hit this case; design is deferred until it does.
3. **Schema versioning across stores.**  When the metamodel evolves (a slot's `:dt/range` changes; a class is renamed), how do older FS files load?  Today, the answer is "they don't, until migrated"; a future answer might be lazy migration on ingest.
4. **Backend pluggability.**  The frame says any backend that round-trips through the FS canonical form is interchangeable.  Has this been *tested*?  No.  XTDB, raw JSON-on-disk, in-memory hashmap — these would all be valid backends per the contract, but the contract has not been exercised against alternates.
5. **Distributed deployment.**  Today's deployment is single-node.  A multi-node deployment with shared FS and separate Datomic peers raises coherence questions (which peer applies the ingest?  how do they avoid double-applying?).  Sandbar's current design does not address distributed deployment; whether it should is a future call.
6. **Federated query.**  A query that spans stores — Datomic + filesystem grep + vector similarity — would need a query planner that can decompose, route, and recombine.  This is a substantial design problem; today it is unaddressed.

These are not promised features.  They are the questions the frame opens.  Some will be answered; some will be deferred; some may turn out to be wrong questions.

## Trade-off axes the answer must navigate

When the answer to "which classes belong in which tier?" is investigated, these are the dimensions that matter:

- **Query frequency.**  Classes queried per request belong in Tier 2.  Classes queried per session belong wherever is cheapest to load on demand.
- **Authorability frequency.**  Classes edited often belong in Tier 1 (FS-canonical, editor-friendly).  Classes rarely edited can live elsewhere.
- **Cardinality.**  Tier 1 (filesystem) scales to ~millions of files but loses convenience at that scale (`ls` is slow; git operations are slow).  Tier 2 (Datomic) handles much larger entity counts but loses external-tool integration.  The crossover point is a measurement, not a guess.
- **Coherence requirements.**  Classes whose state must be transactionally consistent with other classes belong in the same tier as those classes.  Classes whose state is independent can live separately.
- **Tooling compatibility.**  Classes that consumers want to grep / git diff / etc. belong in Tier 1.  Classes whose external tooling story is poor (graph traversals, statistical queries) belong in Tier 2 or Tier 3.

These axes don't yield a single right answer — they yield a research program.

## Comparison with adjacent architectures

### vs. monolithic database

A monolithic store (everything in PostgreSQL, everything in Datomic) trades simplicity for tier-specialization.  Sandbar deliberately rejects the monolithic shape because the filesystem-canonical commitment requires Tier 1 to be first-class — once that's first-class, the question of what *else* benefits from being multi-tiered opens naturally.

### vs. lambda architecture / kappa architecture

Lambda (Marz 2011) and kappa (Kreps 2014) architectures separate batch and stream processing tiers, with a serving layer reconciling them.  Sandbar's multi-tier story is structurally similar — Tier 1 is the *batch* (durable, canonical, eventual); Tier 2 is the *speed* (fresh, indexed, ephemeral) — but the consumer-visible API is the metamodel, not the tiers.  Consumers query `dt/*`; the substrate routes.

### vs. caching layers (Memcached / Redis)

Caches are read-through projections of an authoritative store.  Sandbar's Tier 2 is *not a cache* — it is an independently-typed, queryable, write-capable substrate that happens to be derivable from Tier 1.  Caching invalidation does not apply; project-graph coherence does.

### vs. Notion / Roam / Obsidian (peer projects)

Notion and Roam are SaaS knowledge stores; Obsidian is a local-file knowledge store.  Obsidian is the closest peer to Sandbar's FS-canonical model — files are first-class; the runtime index is derived; consumers can edit with any tool.  Obsidian's runtime index is in-memory (Lucene-shaped); Sandbar's is Datomic-shaped with a richer typed model.  The shapes converge from different starting points: Obsidian started with files and added structure; Sandbar started with structure and made files canonical.

## What this means for users of Sandbar today

The multi-store frame is forward-looking.  Today's user gets:

- Tier 1: an FS hierarchy under whatever path they choose.
- Tier 2: Datomic Peer, paired with the FS via explicit `project-graph` calls.
- No Tier 3 (yet).
- No distributed deployment (yet).
- A clean substrate where adding Tier 3 or distributed deployment is a substrate problem, not an application problem.

Most consumers don't need to reason about the multi-store frame.  They write to the FS or the DB through the API surface, and the substrate handles the projection.  The frame matters when:

- You need to experiment with which classes live where (use `:filter`).
- You need to swap backends (the contract is project-graph round-trip).
- You contribute to Sandbar's evolution (the substrate-first directive applies).

## References

**Polyglot persistence**

- Sadalage, P.J. & Fowler, M. (2012). *NoSQL Distilled: A Brief Guide to the Emerging World of Polyglot Persistence.* Addison-Wesley.

**Federated query**

- Buil-Aranda, C., Arenas, M., Corcho, O. & Polleres, A. (2013). *Federating Queries in SPARQL 1.1: Syntax, Semantics and Evaluation.* Journal of Web Semantics, 18, 1–17.

**CAP theorem and distributed-systems trade-offs**

- Brewer, E. (2000). *Towards Robust Distributed Systems.* PODC keynote.
- Gilbert, S. & Lynch, N. (2002). *Brewer's Conjecture and the Feasibility of Consistent, Available, Partition-Tolerant Web Services.* ACM SIGACT News, 33(2), 51–59.

**Lambda / kappa architectures**

- Marz, N. & Warren, J. (2015). *Big Data: Principles and Best Practices of Scalable Real-Time Data Systems.* Manning.
- Kreps, J. (2014). *Questioning the Lambda Architecture.* O'Reilly Radar. https://www.oreilly.com/radar/questioning-the-lambda-architecture/

**Datomic's tier model**

- Hickey, R. (2012). *The Datomic Information Model.* https://docs.datomic.com/cloud/whatis/data-model.html
- Hickey, R. (2013). *Datomic — Database as a Value.* Strange Loop / InfoQ talk.

**Peer projects (for context)**

- Inkdrop / Obsidian / Logseq / Foam — the FS-canonical-knowledge-store ecosystem.

## See also

- [`project-graph.md`](project-graph.md) — the FS↔DB boundary-layer primitive
- [`codec-layer.md`](codec-layer.md) — per-entity wire format
- [`markdown-as-canonical.md`](markdown-as-canonical.md) — the canonical form Tier 1 holds
- [`metamodel.md`](metamodel.md) — the model the tiers project
- [`doc/guides/sandbar-as-substrate.md`](../guides/sandbar-as-substrate.md) — embedding Sandbar in your own application
