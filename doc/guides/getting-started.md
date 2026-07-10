# Getting Started with Sandbar

> The proper onboarding path.  Reading time: 10–15 minutes.  By the end you'll have Sandbar running, you'll have made your first MCP call, you'll have created your first memorial entity, and you'll know where to read next.  For the 5-minute hands-on speed run, see [`quickstart.md`](quickstart.md); for the conceptual foundations, see [`doc/concepts/metamodel.md`](../concepts/metamodel.md).

## What is Sandbar?

Sandbar is a metamodel platform for structured memory.  At its core is a small RDFS-inspired type system — classes, properties, inheritance, slot-typed validation — implemented inside Datomic, served simultaneously through HTTP REST and the Model Context Protocol (MCP).  The type system describes itself with its own constructs: `:dt/Class` is an instance of `:dt/Class`.  Schema is data; data is queryable through the same API; new classes land via the same transaction shape as new instances.

Layered on the metamodel is `mm/*`, the memory-system schema — `:mm/Memory` (a markdown document as a typed entity), `:mm/Section` (an addressable subdivision with sibling-chain navigation), `:mm/Tag` (first-class, with structured aliasing and equivalence), `:mm/Workflow` (state machines whose runs are typed Processes), `:mm/Activity` (PROV-O-style provenance lifts), and the rest of the memorial vocabulary.  This is the substrate behind the corpus of memories at `memory/` — decisions, plans, observations, patterns, libraries — and behind the LLM memory store design more broadly.

What Sandbar is *for*: building a substrate where "find by content," "walk the typed-edge graph from this seed," "rank by structural prominence," and "describe yourself" are one-line queries against one coherent model.  Concretely: BM25F fulltext with per-field weights declared at the schema layer; `count` / `group-by` / structural-rank as first-class verbs across four ranking axes; a Wilbur-lineage path-grammar that compiles Kleene-algebra-over-binary-relations to Datomic recursive rules; bootstrap-by-discovery so every class auto-surfaces as MCP tool, MCP resource, and REST endpoint without a registration step.

## Five-minute first run

You need Java 11+, [Leiningen](https://leiningen.org), and a Datomic Peer transactor reachable at `datomic:dev://localhost:4334/`.  See [`quickstart.md`](quickstart.md) for the prerequisite walkthrough.

Clone, build, start:

```bash
git clone <repository-url> ~/src/sandbar && cd ~/src/sandbar
lein deps
bin/sandbar start
```

`bin/sandbar start` is the supported entrypoint.  It launches `lein run` in the background, polls `/mcp` until Pedestal answers (typical cold-start ≈30s), and then auto-imports `memory/` from a sibling corpus directory if the database is empty.  The script writes its PID to `~/claude/.sandbar/sandbar.pid` and the server log to `~/claude/.sandbar/sandbar.log`.

Verify:

```bash
bin/sandbar status
```

Expected output, give or take counts:

```
RUNNING — port 8080 (PID 47213)
TOKEN  — present (/Users/<you>/claude/.sandbar/token)
MEMORY — 4827 :mm/Memory entities in DB
```

If `TOKEN — MISSING` shows up, issue one:

```bash
bin/sandbar rotate-token corpus my-key
```

The token is written to `~/claude/.sandbar/token`.  Export it for shell-driven curls:

```bash
export SANDBAR_TOKEN="$(cat ~/claude/.sandbar/token)"
```

A raw HTTP sanity check confirms the surface is live:

```bash
curl -s http://localhost:8080/api/status
```

Response:

```json
{"time":"2026-05-23T12:00:00.000Z","clojure":{"major":1,"minor":12,"incremental":4}}
```

If you reached `RUNNING` and `/api/status` answered, Sandbar is serving REST on `:8080/api/*` and MCP on `:8080/mcp`, sharing the same metamodel.

## First memorial

Memorials are markdown documents with YAML frontmatter — the canonical form a corpus author works in.  Create one on disk:

```bash
cat > /tmp/hello.md <<'EOF'
---
name: My first memorial
type: idea
tags:
  - getting-started
  - sandbar
created: 2026-05-23
---

# Context

A quick thought to test the projection pipeline.  The memorial is canonical
on disk; Sandbar projects it into the typed metamodel.

# Why it matters

The filesystem is ground-truth; the database is a fast index.  Edit the
markdown, re-project, observe the change.
EOF
```

Project it into the running DB through the codec layer.  Markdown is the `:mm/Memory` class's native codec — pass `format: markdown` and the codec mediator handles the parse, the section tree, the sibling chain, the tag entity creation:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$(jq -nR --arg src "$(cat /tmp/hello.md)" '{
        jsonrpc: "2.0",
        id: 1,
        method: "tools/call",
        params: {
          name: "sandbar.entity.create",
          arguments: {
            class: ":mm/Memory",
            format: "markdown",
            source: $src
          }
        }
      }')"
```

The result envelope wraps the entity payload inside `content[0].text` per MCP spec.  Pipe through `jq` to unwrap:

```bash
... | jq -r '.result.content[0].text' | jq .
```

Expected (truncated):

```json
{
  "entity-id": 17592186045842,
  "ident": ":memory/my-first-memorial",
  "class": ":mm/Memory",
  "section-count": 2,
  "tags": [":tag/getting-started", ":tag/sandbar"]
}
```

The memorial is now in the metamodel.  Validation ran during creation; the section tree was built; tags were resolved to `:mm/Tag` entities (existing ones reused, new ones created); links and frontmatter scalars were lifted.

## First substrate query

Find the memorial you just created via BM25F fulltext.  The `sandbar.search.bm25f` verb takes a class scope, a query string, and projection options; the ranking uses the canonical Robertson-Zaragoza form with per-field weights declared on the class:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "sandbar.search.bm25f",
      "arguments": {
        "class": ":mm/Memory",
        "query": "projection pipeline ground-truth",
        "limit": 5,
        "include": ["snippets", "field-scores"]
      }
    }
  }' | jq -r '.result.content[0].text' | jq .
```

You should see the new memorial at the top of the results, with a snippet drawn from the body and a numeric BM25F score.  Try a query that's not in the memorial to confirm it ranks below others; try the exact title to see the title-weight dominate.  Per-class weights live in `schema/mm.edn` under `:dt/bm25f-weights` on `:mm/Memory`.

The same call against REST (`GET /api/store/search/bm25f?class=mm/Memory&query=projection`) returns the same shape from the same code path — projections of the same model.

## First typed-edge walk

The memorial carries typed edges: `:mm.memory/tags` to its `:mm/Tag` entities, `:mm.memory/cites` to other memorials (when the markdown body contains `[[wikilinks]]`), `:mm.memory/first-section` to the section tree, and so on.  Walk outbound edges with `sandbar.navigate.outbound-edges`:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "sandbar.navigate.outbound-edges",
      "arguments": {
        "from": ":memory/my-first-memorial",
        "predicates": [":mm.memory/tags"]
      }
    }
  }' | jq -r '.result.content[0].text' | jq .
```

You'll see two tag entities returned — `:tag/getting-started` and `:tag/sandbar`.  Drop the `:predicates` filter and you'll see every outbound edge: tags, sections, frontmatter, etc.  Walk the inverse direction with `sandbar.navigate.inbound-edges` from a tag to discover every memorial that uses it.

For multi-hop traversal — "every memorial cited from this decision's transitive citation graph, filtered to decisions only" — reach for `sandbar.navigate.path-via`, which compiles a Wilbur-style path expression to a Datomic recursive rule.  The path-grammar concept doc ([`doc/concepts/path-grammar.md`](../concepts/path-grammar.md)) covers the 21-operator vocabulary; the [`navigating-with-paths.md`](navigating-with-paths.md) guide walks worked examples.

## The metamodel in 60 seconds

The whole substrate is introspectable through the same surface.  Ask the metamodel to describe itself:

```bash
curl -s -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": 4,
    "method": "tools/call",
    "params": {
      "name": "sandbar.class.describe",
      "arguments": {"class": ":mm/Memory"}
    }
  }' | jq -r '.result.content[0].text' | jq .
```

You get back the class's ident, its ancestry up to `:dt/Resource`, its direct subclasses, the effective slot set (inherited + declared), per-slot ranges and cardinalities, the `:dt/native-codec` declaration, the `:dt/bm25f-weights` map, and the workflow definitions that consume it.

This is what *bootstrap-by-discovery* means.  The MCP tool catalog, the JSON Schema for each tool's arguments, the REST endpoint surface, the resource URIs — every protocol projection is a function of the metamodel state, recomputed per request.  Add a class to `schema/` + restart; the new class auto-surfaces through every protocol with no registration step, no mapping table, no code change.

The composition is straightforward to keep in mind:

- **`:dt/*`** is the substrate — `:dt/Class`, `:dt/Property`, `:dt/Resource`, `:dt/Literal`, `:dt/native-codec`, the introspection API.  Five core idents anchor everything.
- **`:mm/*`** is the memorial layer built on top — `:mm/Memory`, `:mm/Section`, `:mm/Tag`, `:mm/Workflow`, `:mm/Decision`, `:mm/Plan`, `:mm/Activity`, and the rest.  Every `:mm/*` class is a `:dt/Class` instance; every `:mm/*` slot is a `:dt/Property` instance.
- **Domain layers** (your application's classes, or `:zorp/*` from the tutorial, or `:order/*` from the README) compose the same way — they declare a `:dt/subclass-of` chain into `:dt/Resource` and the substrate handles validation, projection, retrieval, navigation.

That's the whole shape.  Everything else is depth on one of those three layers.

## What to read next

Pick the path that matches your goal.

- **Build intuition for the type system.** Read [`doc/concepts/metamodel.md`](../concepts/metamodel.md) — the lineage (RDFS, KL-ONE, CLOS metaobject protocol) and the metacircular core.  Then walk the [`zorp-tutorial.md`](zorp-tutorial.md) for a worked domain ontology.
- **Author your own classes.** [`defining-new-classes.md`](defining-new-classes.md) — the schema-edn shape, the `:dt/slots` declaration, the validation hook.
- **Connect Claude or another AI client.** [`writing-an-mcp-client.md`](writing-an-mcp-client.md) — initialization handshake, tool discovery, resource subscriptions, the MCP Tasks surface for long-running operations.
- **Embed Sandbar in a Clojure application.** [`writing-a-clojure-client.md`](writing-a-clojure-client.md) for the in-process `dt/*` API.  Then [`sandbar-as-substrate.md`](sandbar-as-substrate.md) for the embedding patterns (library mode vs server mode), schema evolution, and the multi-store topology choices.
- **Consume Sandbar over plain HTTP.** [`writing-a-rest-client.md`](writing-a-rest-client.md) — the REST projection of the same metamodel.
- **Master retrieval.** [`searching-the-corpus.md`](searching-the-corpus.md) for BM25F patterns; [`navigating-with-paths.md`](navigating-with-paths.md) for the path-grammar; the concept doc [`doc/concepts/aggregation.md`](../concepts/aggregation.md) for `count` / `group-by` / `rank-by`.
- **Add a new wire format.** [`implementing-a-codec.md`](implementing-a-codec.md) walks the codec protocol; [`doc/concepts/codec-layer.md`](../concepts/codec-layer.md) explains the mediator design.
- **Model long-running operations.** [`designing-workflows.md`](designing-workflows.md) for authoring `:mm/Workflow` state machines; [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) for the terminal-kind classification design.

## See also

- [`quickstart.md`](quickstart.md) — the 5-minute speed run if you want fewer words and more commands
- [`doc/concepts/`](../concepts/) — theoretical reference layer; each file leads with a thesis and shows it carried out
- [`doc/api/`](../api/) — mechanical reference for `dt/*`, REST endpoints, MCP verbs, and the codec protocol
- [`auth.md`](../auth.md) — service-account token issuance and the auth model
- [`development.md`](../development.md) — running tests, the in-memory fixture, schema-reload workflows
