# Find a decision's successor and its evidence

This guide builds a small graph, then answers a question that a text match alone cannot settle: what replaced this decision, and what did its successor cite?

Use a fresh development database with Sandbar's memory schema loaded, as described in [Getting started](getting-started.md). The examples below create three synthetic records through the Clojure API. They do not describe any existing project's decisions.

## Create the three records

```clojure
(require '[sandbar.db.datatype :as dt]
         '[sandbar.navigate.edges :as edges]
         '[sandbar.navigate.path :as path]
         '[sandbar.navigate.walk :as walk]
         '[sandbar.orient :as orient])

(dt/make :mm/Observation
  {:db/ident :memory.examples/cache-measurement
   :mm.memory/name "Measure cache freshness"
   :mm.memory/memory-type :observation
   :mm.memory/scope :project})

(dt/make :mm/Decision
  {:db/ident :memory.examples/timed-refresh
   :mm.memory/name "Refresh on a timer"
   :mm.memory/memory-type :decision
   :mm.memory/scope :project})

(dt/make :mm/Decision
  {:db/ident :memory.examples/write-refresh
   :mm.memory/name "Refresh after accepted writes"
   :mm.memory/memory-type :decision
   :mm.memory/scope :project
   :mm.memory/cites [:memory.examples/cache-measurement]
   :mm.memory/supersedes [:memory.examples/timed-refresh]})
```

The successor points to the older decision. It also points to the observation it cites. Neither relationship is inferred from the English names.

## Find the recorded successor

```clojure
(edges/inbound-edges
  {:entity :memory.examples/timed-refresh
   :predicate :mm.memory/supersedes})
```

The `source` of the returned edge is `:memory.examples/write-refresh`. An outbound query from the old decision would ask a different question: what did the old decision supersede?

The edge wrapper also accepts a numeric eid, including for an entity without an ident. Its `total` counts relationships; `distinct-total` counts distinct source entities before limiting. If one record reaches a concept through both tags and themes, keep both edge roles but read that source once. The [vocabulary search example](searching-the-corpus.md#use-vocabulary-to-find-a-concept-and-its-records) demonstrates that union.

## Follow the route to evidence

```clojure
(path/path-via
  {:from :memory.examples/timed-refresh
   :via [:SEQ [:INV :mm.memory/supersedes] :mm.memory/cites]
   :include [:paths]})
```

The endpoint is `:memory.examples/cache-measurement`. The two-edge witness first traverses `supersedes` inversely, then `cites` forward. Read the endpoint and the successor in full before interpreting the measurement or the scope of the replacement.

After MCP initialization, the corresponding `tools/call` request is:

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "sandbar_navigate_path-via",
    "arguments": {
      "from": ":memory.examples/timed-refresh",
      "via": "[:SEQ [:INV :mm.memory/supersedes] :mm.memory/cites]",
      "include": ["paths"]
    }
  }
}
```

Use the wire name returned by your server's `tools/list`. Check protocol errors and `isError` before reading the tool payload; a parseable error is not an empty result. See [Writing an MCP client](writing-an-mcp-client.md).

## Bound a broader exploration

```clojure
(walk/graph-walk
  {:from :memory.examples/write-refresh
   :hops 1
   :predicates [:mm.memory/cites :mm.memory/supersedes]
   :include [:paths]
   :limit 1})
```

Two records are reachable, but only one is returned: `total` is 2 and `returned` is 1. The limit is applied after the walk. Do not depend on which equally distant record is first. Walk results carry entity references and step witnesses; use the returned `db/id` for a full lookup when needed.

For repeated supersession, `[:REP :mm.memory/supersedes 1 3]` follows one to three replacements. `[:REP* ...]` also includes the seed. These choices change the question, not just performance.

## Build a small orientation card

```clojure
(orient/library-card
  {:entity :memory.examples/write-refresh
   :axes [{:name :evidence
           :direction :forward
           :predicates [:mm.memory/cites]}
          {:name :replaces
           :direction :forward
           :predicates [:mm.memory/supersedes]}]})
```

The card contains the two named axes, with one edge in each. An application can add inbound citations or other domain relationships without changing the navigation primitive.

For operator support and endpoint/witness differences, use the [path grammar](../concepts/path-grammar.md). For discovering an initial record by content, continue with [Searching the corpus](searching-the-corpus.md).
