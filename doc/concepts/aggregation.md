# Aggregation: ask about the population

**A knowledge graph should answer questions about its contents without making a client download every entity.** Counts, groups, and structural rankings describe a population from different angles. They complement text relevance: “how many decisions cite evidence?” is a structural question even when none of those decisions contains the word *evidence*.

Sandbar's aggregation operations take class and property identifiers from the [metamodel](metamodel.md). Class populations include subclass instances. This lets an application use the same operations for decisions, library records, or its own classes.

## Choose the question before the operation

| Question | Operation | Result |
| --- | --- | --- |
| How many instances satisfy these clauses? | `sandbar.aggregate.count` | `count` |
| How are those instances distributed by a property? | `sandbar.aggregate.group-by` | `groups`, `total` |
| Which instances have the most connections or newest dates? | `sandbar.aggregate.rank-by` | `hits`, `total`, `returned` |
| Which tags have the most referring entities? | `sandbar.aggregate.tag-histogram` | `histogram`, `total` |
| How are text matches distributed? | `sandbar.search.bm25f` with `facet-by` | Search hits and facets |

These operations have different option sets. Count and group accept `where` clauses. Structural rank accepts its ranking and projection options; it is not a general filtered-query wrapper. The tag histogram enumerates typed `:mm/Tag` instances and counts distinct referring entities across predicates and classes. It does not accept an arbitrary class population, and a histogram of typed tags is not a census of every tag value introduced by imports. Use an explicit membership filter when the question concerns `:mm.memory/tags` or `:mm.memory/themes` specifically. Consult [MCP reference](../api/mcp-verbs.md) for the exact schema.

For membership around one concept, [inbound navigation](navigation.md) accepts its eid and both qualified predicates. It reports edge count and distinct member count separately, including members without idents. Keep that population distinct from tag lookup's lexical candidate count; the latter currently has an overlapping-total defect and is unsuitable as an evaluation denominator.

## Count and group

In Clojure, clauses are data and `?e` denotes the candidate entity:

```clojure
(require '[sandbar.aggregate :as aggregate])

(aggregate/count-by
  {:class :mm/Decision
   :where '[[?e :mm.memory/cites _]]})

(aggregate/group-by
  {:class :mm/Memory
   :group-by :mm.memory/memory-type
   :where '[[?e :mm.memory/scope :project]]})
```

The MCP form carries `where` as an EDN string. It is a restricted query fragment, not arbitrary executable Clojure. Use known property identifiers and keep the entity variable consistent.

For a reference property, name a readable target by its keyword ident, eid or
lookup ref. For example, `[[?e :mm.memory/owning-project [:mm.project/ident :proj/demo]]]`
selects members of an existing readable project. The same rule applies to citation,
author and tag references. A missing or unreadable target produces
`filter-identity-unavailable`; it is not an empty result. Attribute names and
non-reference values retain the namespace restrictions. A keyword under
`db/ident` stays a literal keyword; the filter does not convert scalar values.

Readability of an explicit target does **not** make the counted population
private: count and group-by can still include unreadable source records, and
variable joins can test a hidden target's attributes. Use these operations only
within the [documented project boundaries](../known-gaps-0.2.0.md#project-separation-has-several-boundaries).

A missing grouping value contributes no bucket. A property with several values contributes to several buckets. Consequently, `group-by`'s `total` is the sum of visible bucket counts; it need not equal the number of distinct entities. Use count when that distinction matters.

## Ranking gives an order, not a judgment

`degree` ranks by graph connections; `backlink-density` ranks by incoming connections. These are counts of relationships in the graph, not measures of truth, importance, or independent supporting sources. A frequently referenced obsolete decision can rank highly.

`recency` puts newer temporal values first. `freshness` puts older values first, useful for finding records that may need attention. Both require an explicit `temporal-slot`; the caller decides which date matters. An authored date and a database transaction time answer different questions.

```clojure
(aggregate/rank-by
  {:class :mm/Decision
   :rank-by :recency
   :temporal-slot :mm.memory/created
   :limit 10
   :projection :metadata-only})
```

Each hit contains `entity` and `rank-score`. A temporal score is a temporal value, so consumers should not assume every ranking produces a floating-point relevance score. `limit` defaults to 20; zero returns the full ranked population. A result limit bounds the response, not the amount of work needed to construct the ranking.

The optional `memorial-policy` filter uses the class's effective policy, inherited through the metamodel. This is useful when selecting first-class records separately from operational or inline data. It does not confer authority on a result.

## Combine the answers deliberately

For a text query with facets, start with [single-class BM25F search](fulltext-search.md) and a scalar facet: it covers positive matches before the result limit. Many-valued facets use a collection bucket, and multi-class projection can remove facet fields before counting in this revision. For relationships around a specific result, use [navigation](navigation.md). To decide whether a returned decision still governs a question, read its body and inspect its successor relationships. Aggregation supplies evidence about the graph; application policy supplies the interpretation.

Implementation: [`sandbar.aggregate`](../../src/sandbar/aggregate.clj), with model primitives in [`sandbar.db.datatype`](../../src/sandbar/db/datatype.clj).
