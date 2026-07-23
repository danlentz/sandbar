# Navigation

> Sandbar's navigation axis exposes typed-edge graph traversal at three levels of expressiveness — direct edge enumeration (`inbound-edges` / `outbound-edges`), bounded BFS reachability (`graph-walk`), and full Wilbur-lineage path-grammar (`path-via`).  Each composes through opts-shaped verbs that share the four-axis composition contract.  Where `sandbar.search` answers "find by content" and `sandbar.aggregate` answers "count / group / rank," `sandbar.navigate` answers "walk the typed-edge graph from here."

## Thesis

A knowledge graph wants three navigation primitives:

1. **Edge enumeration** — *"what predicates emerge from this entity?"*  Answer at one hop.  Direct.
2. **Bounded reachability** — *"what's reachable within N hops?"*  BFS with hop-cap.  The common-case answer.
3. **Path expressions** — *"what's reachable via this specific shape?"*  Kleene-algebra-over-relations.  The complete answer.

The three are not redundant — they trade expressiveness against substrate cost.  Edge enumeration is one Datalog query; graph-walk is N queries iterating a frontier; path-grammar may compile to recursive rules with unbounded fixpoint search.  Sandbar ships all three so consumers pick the right tool for the question.

The navigation axis is **the fourth wall of the four-axis retrieval surface**.  Search ranks by content; aggregate summarizes the candidate set; orient summarizes session state; navigate walks the typed-edge graph.  Each emerged independently in the retrieval-surface catalog (`decisions/multi_axis_search_catalog_2026_05_08.md`) as a distinct retrieval question.  None subsumes the others.

## The three primitives

### `sandbar.navigate.edges` — direct edge enumeration

```clojure
(sandbar.navigate.edges/outbound-edges
  {:entity      :dt/Property
   :predicate   [:dt/subclass-of :dt/slots]      ; optional
   :target-type :dt/Class                         ; optional
   :limit       0})                                ; default: no cap
;; => {:edges    [{:predicate :dt/subclass-of :target <entity-map>}
;;                {:predicate :dt/slots         :target <entity-map>}
;;                ...]
;;     :total    <int>
;;     :returned <int>}

(sandbar.navigate.edges/inbound-edges
  {:entity      :dt/Resource
   :predicate   :dt/subclass-of                   ; optional
   :source-type :dt/Class})                       ; optional
;; => {:edges    [{:predicate :dt/subclass-of :source <entity-map>} ...]
;;     :total    <int>
;;     :returned <int>}
```

One Datalog query per direction.  Restriction by predicate set (single keyword or collection) + target/source-type (applied via the `instance-of` rule).

**Use when:** the question is shape-local — what does this entity reference, or who references it?  Library-card-style 10-axis composition uses inbound-edges heavily.

### `sandbar.navigate.walk` — bounded BFS reachability

```clojure
(sandbar.navigate.walk/graph-walk
  {:from        :decisions/foundation
   :hops        4
   :direction   :forward                          ; :inverse / :bidirectional
   :predicates  [:cites :evidences :informs]      ; optional restriction
   :include     [:paths]                          ; optional path projection
   :limit       0})
;; => {:reachable [{:entity <entity-map> :hop <int>
;;                  :path  [{:predicate <pred-ident> :direction :forward}
;;                          {:predicate <pred-ident> :direction :forward}]}
;;                 ...]
;;     :total    <int>
;;     :returned <int>}
```

Clojure-side iterative BFS.  One Datalog query per hop, expanding the frontier in Clojure data structures with explicit visited-set deduplication.  First-arrival shortest-path is guaranteed by BFS hop ordering; `:include [:paths]` attaches the shortest-path step sequence.

Per `decisions/sandbar_graph_walk_clojure_bfs_over_datomic_recursive_rules_2026_05_14.md` — Clojure-side BFS chosen over Datomic recursive rules because (a) hop-cap semantics don't naturally express in Datalog recursive rules (unbounded transitive closure), (b) per-hop path tracking is cleaner when we control the traversal step, (c) first-arrival shortest-path is naturally BFS-ordered.

**Use when:** the question is "neighborhood-shaped" — what's within N hops?  This is the most-common navigation case.

### `sandbar.navigate.path` — Wilbur path-grammar

```clojure
(sandbar.navigate.path/path-via
  {:from  :decisions/foundation
   :via   [:SEQ [:REP* [:OR :cites :evidences]]
                [:RESTRICT [:dt/type :mm.memory/decision]]]
   :limit 50})
;; => {:reachable [<entity-map> ...] :total <int> :returned <int>}
```

EDN path expression → parse → canonicalize via algebraic identities → compile to Datomic recursive rules + reverse-attribute syntax → execute → project.  Thirteen executable operators (Canonical-8 + Tier-2); thirteen more vocabulary-registered for parse-time recognition + future-proofing.

**Use when:** the question requires expressiveness BFS doesn't provide — Kleene closure (`:REP+` / `:REP*`), inverse traversal at depth (`:INV` over a recursive operator), branching alternation (`:OR`), shape-specific restriction (`:RESTRICT`).  Per Bonifati 2017's empirical SPARQL log analysis, real-world property-path queries are <0.1% of total — but when you need them, you really need them.

→ `doc/concepts/path-grammar.md` for the algebra, operator surface, and three-layer architecture

## Composition + cross-axis interaction

Navigation is one axis of four (search / aggregate / navigate / orient).  The cross-axis composition is wired: `:from` + `:via` opts are accepted on the search and aggregate verbs so a graph-walk neighborhood becomes the candidate set for ranking / counting.  Path expressions composed at the search / aggregate boundary use the same path-grammar dialect as `navigate.path-via`.

The composition contract per `decisions/multi_axis_search_composition_2026_05_08.md`:

- **Categorical axes filter** — type restriction, predicate restriction
- **Numeric axes rank** — BM25F, structural-rank (`:degree` / `:backlink-density` / `:recency` / `:freshness`)
- **Graph-walk is candidate-set generation** — `:from` + `:hops` (or `:via`) restricts the population for downstream filter + rank

This is the hybrid filter-then-rank model — same shape as SPARQL's `WHERE` clauses chained with `ORDER BY`.

## The dt/* primitive surface

Substrate primitives in `sandbar.db.datatype`:

| Primitive                  | What it does                                                      |
|----------------------------|-------------------------------------------------------------------|
| `dt/outbound-edges-of`     | Ref-attribute pairs FROM an entity; predicate + target-type filter |
| `dt/inbound-edges-of`      | Ref-attribute pairs TO an entity; predicate + source-type filter   |
| `dt/graph-walk-from`       | BFS reachable-neighborhood with hop-cap + direction + paths       |

The path-grammar surface (parse → IR → compile → execute) lives in `sandbar.navigate.path.*` sub-namespaces; `dt/*` is the lower-level substrate.

## What navigation is NOT for

- **Fulltext relevance** — use `sandbar.search`.
- **Count / group / rank over a navigation neighborhood** — use `sandbar.aggregate`, composing via cross-axis `:from` + `:via` opts on the search and aggregate verbs.
- **Library-card style multi-axis neighborhood view** — use `sandbar.orient.library-card` (the substrate-shipped axis of the orient surface).
- **Eigenvector centrality (PageRank-style)** — outside scope; structural-rank uses direct edge counts.

## Performance characteristics

- **Edge enumeration** — O(matching edges) per query; one Datalog query per direction.
- **BFS graph-walk** — O(hops × frontier-edges-per-hop); each hop is one Datalog query.  Hop-cap bounds the worst case.  At `:hops 4` over a corpus of ~1000 memories, sub-second.
- **Path-grammar (recursive)** — Datomic recursive-rule planner-cost-dependent; bounded transitive closures (≤6 hops typical) handle well, deep closures over high-fanout predicates can hit planner cliffs.  Mitigation: bounded `(:REP p min max)` is the production-safe variant.  Per Bonifati 2017, real workloads rarely traverse beyond modest depths.

For the rare cases that hit planner cliffs, Sandbar's three-layer architecture admits a future NFA × graph-product backend (`sandbar.navigate.path.fsa`) per cornerstone D10; deferred until query mix justifies.

## References

- Wilbur (Lassila, Nokia 2001-2009) — the navigation-axis lineage.  See `doc/concepts/path-grammar.md` §Lineage.
- Harris, Steve & Seaborne, Andy.  *"SPARQL 1.1 Query Language"*, §9 Property Paths.  W3C Recommendation, 2013.
- Bonifati, Angela, Martens, Wim & Timm, Thomas.  *"An analytical study of large SPARQL query logs"*.  VLDB Journal 26(2), 2017.  Empirical analysis of property-path usage in real-world SPARQL.
- Brin, Sergey & Page, Lawrence.  *"The anatomy of a large-scale hypertextual Web search engine"*.  WWW7 1998.  PageRank — the eigenvector-based ranking sandbar does NOT do (degree-based is enough for the substrate).

## See also

- `doc/concepts/path-grammar.md` — the deep-dive on path expressions (~21-operator vocabulary, three-layer architecture, algebraic identities)
- `doc/concepts/fulltext-search.md` — the sibling search axis
- `doc/concepts/aggregation.md` — the sibling aggregate axis
- `doc/guides/navigating-with-paths.md` — task-oriented worked examples
- `doc/api/mcp-verbs.md` — `sandbar.navigate.path-via` MCP entry
- `doc/api/http-rest.md` — `GET /api/navigate/path` REST endpoint
- `doc/api/dt-star.md` — `dt/outbound-edges-of` / `dt/inbound-edges-of` / `dt/graph-walk-from` substrate primitives
