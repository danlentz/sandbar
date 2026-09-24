# Sandbar

> Typed knowledge and durable memory, built on Datomic.

Sandbar stores information together with a model of what it means: classes, properties, relationships, and rules. Applications can inspect that model, query across it, and extend it as their knowledge grows. People can work with readable documents; programs can work with typed entities. Clojure, HTTP, and Model Context Protocol clients reach the same underlying system.

A memory application gives this design a practical purpose. Keep a decision with its rationale. Connect it to the observation that informed it. Record the decision that later replaces it. Months afterward, recover the useful context and follow the relationships that explain how it changed.

## A small example of the problem

Suppose a team changes how a cache is refreshed. Three records tell the story:

| Record | Relationship | What it lets a reader discover |
| --- | --- | --- |
| A measurement of stale reads | Evidence for a decision | Why the change was considered |
| The original refresh policy | Superseded by a later decision | Which policy was previously in use |
| The revised refresh policy | Cites the measurement and supersedes the original | The current proposal and its rationale |

A text search can find the discussion. Typed relationships distinguish the evidence from the decision and the successor from the predecessor. A query over decisions can include specialized decision classes through inheritance. A document view makes the reasoning easy to read and review.

The application still decides what counts as adequate evidence and which changes to accept. Sandbar supplies ways to preserve those judgments, connect them to their basis, and recover them without rebuilding the context from scratch.

## The model can describe itself

Classes and properties are stored as entities. A client can ask which slots a class has, which classes inherit from it, and which entities are its instances. This is the practical meaning of Sandbar's metacircular metamodel: the vocabulary used to describe application data can also describe the vocabulary itself.

Inference is part of this machinery. Recursive Datalog rules compute inherited membership and slots; a query for memories includes instances of specialized memory classes. Additional rule sets express selected RDFS and OWL property semantics, including subproperties, transitivity, symmetry, and inverses. Datomic evaluates the rules. The [metamodel](doc/concepts/metamodel.md) and [inference chapter](doc/concepts/rdfs-entailment.md) explain what follows automatically and where a query explicitly selects a rule set.

Functions, constraints, rules, workflows, and schedules can also be represented in the model. Their declarations can be inspected alongside the knowledge they operate on. Each execution surface defines how those declarations become work and what happens on failure.

## Retrieval starts with the question

| What you need | Operation family | Read next |
| --- | --- | --- |
| A relevant passage or document | Field-weighted BM25F search | [Searching a knowledge collection](doc/guides/searching-the-corpus.md) |
| A count, distribution, or ranked population | Structural and temporal aggregation | [Aggregation](doc/concepts/aggregation.md) |
| Evidence, dependencies, ancestors, or successors | Typed edges and path expressions | [Navigation](doc/concepts/navigation.md) |
| A useful view of an unfamiliar entity or collection | Library cards and trees | [MCP verb reference](doc/api/mcp-verbs.md) |
| A known entity and its complete contents | Exact lookup and result projection | [MCP client guide](doc/guides/writing-an-mcp-client.md) |

These operations compose. Find a promising decision, read it in full, follow its evidence and supersession links, then inspect the relevant population. A search score answers a relevance question; the model supplies the relationships needed to ask the next one.

## A useful division of work for AI clients

An AI client can use Sandbar for persistence, structured retrieval, declared checks, and rule-based inference while concentrating on interpretation and synthesis. MCP provides the tool boundary between them. The same operations are useful to ordinary programs; the stored knowledge and its model remain independent of any particular conversation.

Sandbar exposes a stable catalog of operational verbs. Class names and other model elements are arguments to those verbs. Adding a class makes it available through schema and class inspection; it does not require another MCP tool for every operation on that class.

For example, after initializing an MCP connection, a client can inspect a decision's effective slots:

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "sandbar_class_slots",
    "arguments": {"class": ":mm/Decision"}
  }
}
```

See [Sandbar's MCP surface](doc/concepts/mcp-protocol.md) for discovery and response handling, and the [client guide](doc/guides/writing-an-mcp-client.md) for connection setup.

## Readable knowledge and controlled change

Markdown with frontmatter gives document-shaped knowledge a representation people can edit and review with familiar tools. The codec and projection layers connect that representation to entities and sections. Their contract states what is preserved, what is normalized, and how identity survives reconstruction. The [projection chapter](doc/concepts/projection.md) explains the boundary between files, accepted database changes, and synchronization.

Constraints express what an accepted entity must satisfy. Interactive writes in strict mode check the implemented shape constraints before committing; bulk import has a separate validation contract. Reactive processing carries accepted changes into derived views, such as searchable content and projected files. Workflows and schedules give longer-running work explicit state and outcomes.

Projects and contexts describe the scope in which knowledge is used. Separate document trees can share a queryable database, with explicit ownership and directional reference rules. Import is a maintenance operation: stop writers, reconcile the selected files with accepted state, import into the existing store, and audit before resuming. Export currently produces a local working copy that needs disclosure review before publication; it is not an automatic public/private filter. [Projects and boundaries](doc/firewall-and-projects.md) and [operations](doc/operations.md) explain the supported procedures and remaining release work.

## Start here

- [Quickstart](doc/guides/quickstart.md): connect and inspect the model through MCP.
- [Getting started](doc/guides/getting-started.md): build a small knowledge collection and retrieve from it.
- [Zorp's Galactic Footwear Emporium](doc/guides/zorp-tutorial.md): learn the model through a complete, slightly less terrestrial application.
- [Clojure](doc/guides/writing-a-clojure-client.md), [MCP](doc/guides/writing-an-mcp-client.md), or [REST](doc/guides/writing-a-rest-client.md): connect an application.

## Documentation map

Concepts explain a thesis and the mechanisms that support it. Tutorials provide a learning sequence; how-to guides accomplish a task. API pages specify exact names, arguments, results, and errors.

| Area | Concepts | Practical guides |
| --- | --- | --- |
| Modeling knowledge | [Metamodel](doc/concepts/metamodel.md), [memory model](doc/concepts/memory-model.md), [inference](doc/concepts/rdfs-entailment.md), [shapes](doc/concepts/shape-validation.md) | [Define a class](doc/guides/defining-new-classes.md), [author shapes](doc/guides/authoring-shapes.md), [Zorp tutorial](doc/guides/zorp-tutorial.md) |
| Retrieving context | [Fulltext search](doc/concepts/fulltext-search.md), [aggregation](doc/concepts/aggregation.md), [navigation](doc/concepts/navigation.md), [path grammar](doc/concepts/path-grammar.md) | [Search](doc/guides/searching-the-corpus.md), [navigate with paths](doc/guides/navigating-with-paths.md) |
| Representations and storage | [Codecs](doc/concepts/codec-layer.md), [Markdown](doc/concepts/markdown-as-canonical.md), [projection](doc/concepts/projection.md), [store architecture](doc/concepts/multi-store-architecture.md) | [Implement a codec](doc/guides/implementing-a-codec.md), [use Sandbar as a substrate](doc/guides/sandbar-as-substrate.md) |
| Computation and execution | [Functions](doc/concepts/first-class-fn.md), [rules](doc/concepts/first-class-rule.md), [workflows](doc/concepts/workflow-substrate.md), [time and scheduling](doc/concepts/temporal-substrate.md) | [Design workflows](doc/guides/designing-workflows.md) |
| Changes and observation | [Events](doc/concepts/event-substrate.md), [reactivity](doc/concepts/reactive-substrate.md), [logging](doc/concepts/logging-substrate.md) | [Subscribe to events](doc/guides/subscribing-to-events.md), [use logging](doc/guides/using-logging.md) |
| Clients and access | [MCP](doc/concepts/mcp-protocol.md), [projects and boundaries](doc/firewall-and-projects.md) | Client guides above; [authentication](doc/auth.md) |

Reference: [`dt/*`](doc/api/dt-star.md), [MCP verbs](doc/api/mcp-verbs.md), [REST](doc/api/http-rest.md), [codec protocol](doc/api/codec-protocol.md), and the [MCP composition map](doc/mcp-affordance-map.md).

Operations and development: [operator guide](doc/operations.md), [development](doc/development.md), [benchmark discipline](doc/BENCH.md), [release checks](doc/W1J-RELEASE-GATE.md), [release notes](CHANGELOG.md), and [known limitations](doc/known-gaps-0.2.0.md). Older designs are retained in the [historical archive](doc/old/README.md).

## Working on Sandbar

The implementation is Clojure. Model declarations live under `schema/`; `src/sandbar/db/` contains the metamodel and inference support. Retrieval, codecs, projection, execution, and protocol adapters have their own namespaces under `src/sandbar/`. Tests live under `test/`.

Use the [development guide](doc/development.md) for environment setup and isolated test databases. The usual suite command is `lein test`.

## License

Copyright (C) Dan Lentz
