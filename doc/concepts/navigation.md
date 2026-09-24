# Navigation: recover the relationships that explain a result

**An entity becomes more useful when a reader can follow its evidence, dependencies, and successors.** Text search may find a promising decision. Navigation reveals what supports it, what depends on it, and whether another decision replaces it.

Sandbar represents these connections as typed reference properties. The same navigation operations work over the metamodel and application classes; an application supplies the relationship vocabulary.

## Direction is part of the question

Suppose a newer decision has `:mm.memory/supersedes` pointing to an older one. An outbound lookup from the newer record finds what it replaces. An inbound lookup from the older record finds its recorded successors. Neither lookup invents the inverse assertion in storage.

```clojure
(require '[sandbar.navigate.edges :as edges])

(edges/inbound-edges
  {:entity :memory.examples/timed-refresh
   :predicate :mm.memory/supersedes})
```

Inbound results contain `edges` with `predicate` and `source`; outbound results use `target`. The envelope contains `total` and `returned` edge counts, plus `distinct-total` for distinct endpoint entities before limiting. One record reached through two predicates contributes two edges and one distinct entity. An eid is a usable anchor even when it has no ident, and returned eids can be passed to a full entity read.

A policy-blocked edge carries `blocked: true` and a reason, with no endpoint entity. Handle that result before reading `source` or `target`. It contributes to the edge count but not the distinct endpoint count.

The predicate filter accepts one predicate or an array. Qualified properties such as `:mm.memory/tags` and `:mm.memory/themes` retain the classification role on each edge. An inbound bare name resolves against the supplied `source-type`, or to every ref property with that local name when no source type is given. An outbound bare name resolves against the anchor's class; an untyped anchor uses schema-wide resolution. Prefer qualified properties for a stable membership question. Optional source/target type filters narrow the other end of the relationship.

An absent visible edge is an observation about this query and its access context. It does not establish that the wider world contains no successor or evidence.

## Explore a neighborhood or describe a route

`graph-walk` explores within a hop bound. It accepts direction and predicate restrictions and returns each discovered entity with its hop distance. With `include: ["paths"]`, it also returns a shortest witness as a sequence of predicate/direction steps. The seed is excluded. This is useful for “what is nearby?”; it does not enumerate every possible route between two entities.

`path-via` instead describes the route itself. For example, follow an inverse supersession edge and then a citation:

```clojure
(require '[sandbar.navigate.path :as path])

(path/path-via
  {:from :memory.examples/timed-refresh
   :via [:SEQ [:INV :mm.memory/supersedes] :mm.memory/cites]
   :include [:paths]})
```

Without `paths`, `reachable` contains projected endpoint entities. With it, each entry contains `entity` and a `path` value with nodes and directed edges. This witness explains the connection returned by the query. It is not a promise to enumerate every route. See the [path grammar](path-grammar.md) for operators and capability boundaries.

Both operations apply their result limit after traversal. Use a hop bound or bounded repetition to constrain exploration; a small output limit alone is not a computational budget. Cycles and branching make this distinction important.

## Orientation is a view over those primitives

`sandbar.orient.library-card` collects several named relationship views around one entity. The caller defines each axis: its direction, predicates, optional type filter, and limit. For example, a decision card might show its evidence, decisions it replaces, and records that cite it. The substrate does not hard-code one domain's card layout.

Two other orientation operations answer simpler questions:

- `type-tree` renders the class hierarchy below a root, marking cycles. A class with multiple parents may appear in more than one branch.
- `tree` groups named entities by the first directory segment of a caller-selected path property. It is a directory summary, not a recursive rendering of the graph.

Projection is part of the cost and meaning of these views. A lightweight identity result is enough to choose what to inspect next; use an explicit full entity lookup to read the selected record. Do not infer its rationale from a title or a path witness alone.

## Relationships preserve questions worth asking

A citation records a connection; it does not prove that the cited observation supports the conclusion. Supersession records replacement; its scope still matters. A class edge records an assertion; the [inference API](rdfs-entailment.md) determines which additional relationships can be queried as entailments.

This distinction lets navigation remain a reusable mechanism. Applications can construct review, planning, provenance, and recovery workflows from typed relationships without embedding every judgment into the traversal engine.

Continue with [Navigating with paths](../guides/navigating-with-paths.md). Implementation lives in [`sandbar.navigate`](../../src/sandbar/navigate/) and [`sandbar.orient`](../../src/sandbar/orient.clj).
