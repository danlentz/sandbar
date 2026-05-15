# Sandbar as Substrate

> How to embed Sandbar inside your own application as the typed-model + multi-protocol substrate.  Covers the substrate-first commitment, how to layer your domain on top of Sandbar's primitives, the corpus consumer as canonical case study, multi-store topology choices, and operational considerations.  For the architectural background see [`doc/concepts/`](../concepts/); for adding domain classes see [`defining-new-classes.md`](defining-new-classes.md).

## What "substrate" means here

Sandbar is designed as a **substrate** — a foundation layer your application builds on, not a finished product you compose alongside.  When you embed Sandbar:

- Your domain classes register with Sandbar's metamodel.
- Your wire formats become Sandbar codecs.
- Your long-running operations become Sandbar workflows.
- Your filesystem layout uses Sandbar's project-graph.
- Your consumers reach your service through Sandbar's REST or MCP projections.

The contract is bi-directional: your application gets typed-model + multi-protocol surface for free; Sandbar gets the friction signal of real-world domain use, which informs its evolution (see the **substrate-first** discipline in [`interaction/substrate_first_friction_corpus_unmet_needs_signal_sandbar_evolution_2026_05_13`](../../memory/interaction/substrate_first_friction_corpus_unmet_needs_signal_sandbar_evolution_2026_05_13.md)).

## When to embed Sandbar

Embed Sandbar when:

- Your domain has a rich typed model — multiple noun-shaped classes with structured properties.
- You need multi-protocol exposure (REST + MCP + future RDF) and don't want to maintain parallel schemas.
- You want canonical filesystem representation for human-editable content (markdown notes, decisions, drafts).
- You have long-running operations that need history, cancellation, and outcome classification.
- You want bootstrap-by-discovery for AI clients (Claude, GPT, others) to operate on your domain.

Do **not** embed Sandbar when:

- Your application has no domain model beyond simple records.
- You don't need multi-protocol exposure.
- Your data shape doesn't fit typed-class-with-slots (e.g., heavy unstructured documents; raw time-series).

## Embedding patterns

### Library mode

Pull Sandbar in as a `project.clj` dependency:

```clojure
:dependencies [[com.danlentz/sandbar "0.1.0"]]
```

Use the in-process API:

```clojure
(require '[sandbar.core :as sb]
         '[sandbar.db.datatype :as dt])

;; Init schema + connect to DB; no HTTP server
(sb/init-no-server)

;; Use dt/* directly
(dt/make :order/Order {...})
```

This is right when your application is the *only* consumer of the data — no external clients need REST/MCP access.

### Server mode

Run Sandbar's full HTTP server alongside your application:

```clojure
(sb/go)  ; starts Pedestal on :8080 with REST + MCP routes
```

Your domain classes + workflows + codecs auto-surface through every protocol.  External clients reach your application through Sandbar's endpoints; in-process code reaches the same data through `dt/*`.

This is the typical embedding — your domain operations call `dt/make` and `wf/start-process!`; external clients reach the same operations through MCP `tools/call` or REST POST.

### Hybrid

Run Sandbar's HTTP server for some classes but write directly to `dt/*` for in-process-only classes.  Sandbar doesn't enforce which classes are externally visible — that's a function of the auth + routing layer (see [`auth.md`](../auth.md) for service-account permissions).

## Layering your domain

The recommended layering, from bottom up:

```
┌───────────────────────────────────────────────────┐
│  Application logic                                │
│  (your domain code; calls dt/* + wf/*)            │
└───────────────────────────────────────────────────┘
                       │
                       ▼
┌───────────────────────────────────────────────────┐
│  Sandbar substrate                                │
│  ├── dt/*         (typed model + introspection)   │
│  ├── workflow/*   (state-machine processes)       │
│  ├── codec/*      (wire-format boundary)          │
│  ├── project-graph (FS↔DB projection)             │
│  └── mcp / api    (multi-protocol surfaces)       │
└───────────────────────────────────────────────────┘
                       │
                       ▼
┌───────────────────────────────────────────────────┐
│  Datomic                                          │
│  (storage + transactions + query)                 │
└───────────────────────────────────────────────────┘
```

Your application should call `dt/*` and `wf/*`; **avoid** calling `datomic.api/*` directly except for ad-hoc Datalog.  This is the *layer-targeting* discipline — the same idea that makes `dt/*` valuable becomes valuable one layer up when your application talks to Sandbar instead of Datomic.

## Case study — the memory-corpus consumer

The first consumer of Sandbar (and the empirical driver of its design) is a memory-system corpus authored at `memory/` in a parallel project.  How the corpus consumes Sandbar:

| Sandbar primitive    | Corpus use                                                            |
|----------------------|-----------------------------------------------------------------------|
| `:dt/Class`          | `:mm/Memory`, `:mm/Section`, `:decisions/Decision`, `:patterns/Pattern`, … |
| `:dt/native-codec`   | `:codec/markdown` declared on all human-authored classes              |
| `project-graph`      | Bidirectional sync between `memory/*.md` and Datomic state            |
| MCP `tools/call`     | The corpus's slash commands ultimately reach Sandbar through MCP      |
| `workflow/*`         | Validation runs, migration tasks, search reindexing                   |
| Cancellation         | Long-running validations cancellable by the user                      |

The corpus is the canonical pattern — markdown is canonical, the FS is ground-truth, the DB is a fast index, the MCP surface is reflection-driven.  Other consumers (e.g., a CRM system, a workflow management tool, a documentation portal) would follow the same shape with different class hierarchies.

## Multi-store topology choices

When you embed Sandbar, you implicitly choose a storage topology.  See [`doc/concepts/multi-store-architecture.md`](../concepts/multi-store-architecture.md) for the full frame.  In practice today's choices:

### Tier-1-only (FS-only) classes

Some classes you may want to *only* live on disk — Datomic mirrors them for query, but the DB is authoritatively reconstructible from FS.  Example: documentation drafts, where the author works in their editor and Sandbar indexes for cross-referencing.

```clojure
;; Project everything to FS, including this class
(pg/project-graph (d/db (db/conn)) "/tmp/sandbar-export"
  {:classes #{:docs/Draft :docs/Published}})
```

### Tier-2-only (DB-only) classes

Some classes are *ephemeral runtime state* — workflow processes, MCP subscriptions, validation results.  Don't FS-mirror these.  Use filter to exclude:

```clojure
(pg/project-graph (d/db (db/conn)) "/tmp/sandbar-export"
  {:exclude-classes #{:workflow/Process :mcp/Subscription}})
```

### Tier-1+Tier-2 (FS-canonical with DB index)

The default for human-authored content.  FS is canonical; DB is the index.  No filter needed — `project-graph` covers it by default.

## Schema evolution

When your domain evolves:

1. **Add a new class** — append to `:required-schema`; restart picks it up; new MCP/REST endpoints surface automatically.
2. **Add a slot** — extend the class's `:dt/slots`; instances without the slot remain valid (slot is optional).
3. **Add a required slot** — careful here.  Existing instances become invalid.  Either:
   - Run a backfill before declaring the slot required.
   - Declare the slot optional initially, then run a migration workflow to populate, then mark required.
4. **Rename a class** — Datomic supports `:db/ident` renaming via transaction.  Update FS paths via project-graph after the rename.
5. **Delete a class** — retract the `:dt/Class` entity (Datomic retraction).  Remove from `:required-schema`.  Run project-graph with filter to drop the FS subtree.

Schema migrations are themselves a candidate for workflow-substrate modeling — see [`designing-workflows.md`](designing-workflows.md).

## Operational concerns

### Configuration

Sandbar reads `config/config.edn`:

```clojure
{:db {:url "datomic:dev://localhost:4334/"
      :sid "your-app"}                      ; Datomic database name; matches the scope name per
                                            ; decisions/sandbar_sid_reconciliation_db_name_matches_scope_name_2026_05_12.md
 :required-schema [:meta :ref :literal :fn :any :context :event :user
                   :auth :audit :workflow :job :mm
                   :your-domain-1 :your-domain-2]
 :nrepl {:port 28888}}
```

Config is read via `sandbar.util.edn/config-value` with path access — e.g., `(config-value :db :sid)` returns the database name; `(config-value :required-schema)` returns the schema vector.  Per
`decisions/sandbar_sid_reconciliation_db_name_matches_scope_name_2026_05_12.md`, the current
shape is INTERIM; a late-bound registry-driven config is the target.  Override per-environment via standard 12-factor patterns (env vars, environment-specific config files).

### Lifecycle

```clojure
(require '[sandbar.core :as sb])

(sb/go)    ; start
(sb/stop)  ; stop
```

For production, wire Sandbar's lifecycle into your application's lifecycle (Component, Integrant, Mount — your choice).

### Health checks

```bash
curl http://localhost:8080/api/status
```

Returns 200 with a JSON body when Sandbar is alive.

### Logging

Sandbar uses `clojure.tools.logging` — configure your preferred logging backend.  Sandbar emits structured logs for transactions, codec routing, workflow transitions.

### Metrics (forward-looking)

Sandbar does not today emit Prometheus/StatsD metrics; this is a deliberate omission until the production workload reveals which metrics matter most.  Open issue if your deployment needs them.

### Backup

The simplest backup is `project-graph` to a checkpointed directory + git commit.  Combined with Datomic's own backup story (transaction log archival), this provides recovery to any point in time.

### Multi-tenancy

Sandbar does not natively partition the metamodel for multiple tenants.  The two common patterns:

1. **Database-per-tenant** — separate Datomic database; each tenant runs their own Sandbar instance.  Strongest isolation; highest operational cost.
2. **Tenant-tagged entities** — every class has a `:tenant/id` slot; query filters by tenant.  Lower isolation but easier to operate.  Auth layer enforces the filter.

For substantial multi-tenancy, pattern 1 is currently recommended.

## Substrate-first discipline

When your application has an unmet need, the first question is: *should the substrate provide this?*

- "I need to send notifications when an entity changes" → likely substrate (Sandbar's resource subscriptions).
- "I need to validate every entity nightly" → likely substrate (workflow + validation verb).
- "I need to import data from a CSV" → likely application (CSV is a tool-specific format; the codec layer handles wire formats but not data-source ingestion specifics).
- "I need to compute the average order value" → application (a domain analytic, not a substrate concern).

The discipline cuts both ways.  When in doubt, file the unmet need against Sandbar; the maintainers can route it (substrate vs application).  Tactical workarounds inside an application accumulate; substrate-level evolution stays clean and benefits every consumer.

## See also

- [`doc/concepts/`](../concepts/) — full theoretical context for the substrate
- [`defining-new-classes.md`](defining-new-classes.md) — adding domain classes
- [`designing-workflows.md`](designing-workflows.md) — modeling long-running operations
- [`implementing-a-codec.md`](implementing-a-codec.md) — adding wire formats
- [`doc/concepts/multi-store-architecture.md`](../concepts/multi-store-architecture.md) — multi-tier topology design
- [`writing-a-clojure-client.md`](writing-a-clojure-client.md) — the in-process API
