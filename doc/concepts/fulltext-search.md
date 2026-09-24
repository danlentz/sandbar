# Fulltext search: rank the fields that carry meaning

**An entity's name, explanation, body, and vocabulary can contribute differently to finding it.** Sandbar makes those choices part of the model: a class declares its searchable fields and their relative weights, and one search operation applies that declaration to its instances.

A decision with “cache freshness” in its title and supporting discussion in its body is a useful match even when neither field tells the whole story. BM25F combines that evidence while accounting for field length and repeated terms. The result is a ranked route into the graph. Identity, provenance, and supersession remain explicit relationships to inspect after discovery.

## Combine evidence before saturation

For each distinct analyzed query term, Sandbar normalizes its frequency within each field, adds those frequencies with their field weights, then applies one saturation curve. This follows the multi-stream construction in [Robertson and Zaragoza, §3.6](https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf). Adding separately saturated field scores is a different ranking function.

The implemented calculation is:

```text
combined_tf(t, d) = sum over fields f of
  weight[f] * tf(t, d[f]) / (1 - b + b * length(d[f]) / average_length[f])

idf(t) = log(1 + (N - df(t) + 0.5) / (df(t) + 0.5))

score(q, d) = sum over distinct analyzed query terms t of
  idf(t) * (k1 + 1) * combined_tf(t, d) / (k1 + combined_tf(t, d))
```

Sandbar uses `k1 = 1.2` and a shared `b = 0.75`. Document frequency counts each entity once across its analyzed fields. Statistics describe the full queried class population, including subclasses. Missing fields contribute no terms; average field lengths include entities that lack that field. Zero average length falls back to the unnormalized term frequency.

The class's effective `:dt/bm25f-weights` map selects the fields. Inherited declarations combine, with the more specific class overriding a shared field's weight. A query can override weights. Weight changes express retrieval priorities; they do not confer importance, validity, or authority on the matching records.

## Scoring and execution are separate decisions

Sandbar caches analyzed entity populations and scores them with its own analyzer and kernel. The analyzer lowercases, extracts runs of Unicode letters and numbers, and applies Porter stemming to eligible ASCII tokens. It does not perform accent folding or general Unicode normalization. For example, `running` and `runs` become `run`, while `ran` remains `ran`.

The BM25F query is a bag of words. Quoting, `AND`, wildcards, and field prefixes do not introduce a Lucene query language; they are handled as ordinary text by this analyzer. Repeated analyzed query terms contribute once.

Datomic's native fulltext index serves a separate single-attribute operation. Its results carry a Datomic/Lucene relevance score. The native query returns entity, matching value, transaction, and score; it should not be described as Sandbar's BM25F kernel. See [Datomic's fulltext contract](https://docs.datomic.com/query/query-data-reference.html#fulltext).

When a read principal is bound, Sandbar filters native fulltext candidates using current-store visibility before sorting, totals and the result limit. Returned entities use the same database value as that visibility decision. The rule covers document-owned content such as sections as well as the documents themselves; the scoring algorithm remains Datomic's.

Why not use that index to prune BM25F candidates? An initial filter must recognize every term that the scorer would match. Datomic's native analysis and Sandbar's Porter-based analysis differ; a mismatched filter can discard a valid result before scoring. The cached population provides an exhaustive scoring baseline. An indexed optimization must preserve its candidate recall as well as improve measured cost.

## Compose a question without changing its meaning

`sandbar.search/search-bm25f` accepts a map with `:class` and `:query`. The MCP operation is advertised as `sandbar_search_bm25f`; use your server's discovered wire name.

| Option | Role |
| --- | --- |
| `:where` | Restrict eligible entities with Datalog clauses using `?e` |
| `:from` and `:via` | Restrict eligible entities to a graph path's reachable set |
| `:rank-by` | Choose pure relevance, degree, backlink density, recency, or freshness ordering |
| `:temporal-slot` | Supply the timestamp field for recency or freshness ordering |
| `:facet-by` | Count field values over the matching population before the result limit |
| `:limit` | Bound returned hits; default 20, zero means no cap |
| `:projection` | Choose full entities, frontmatter, or identity/type metadata |
| `:include` | Add snippets or diagnostic field scores |

Structural and path filters intersect. They narrow the eligible hits without recalculating the class statistics. Reranking applies to all positive matches before limiting; `:score` becomes the structural value and `:relevance-score` retains BM25F.

When a read principal is bound, BM25F also checks each candidate's visibility against the current store, rather than trusting cached entity labels. Unreadable candidates are removed before hit scoring, snippets, field-score diagnostics, totals, facets and the result limit. Authorized hits carry the current entity from that decision's database value into their payloads, snippets and scalar facets, so those fields do not expose an older cached entity representation.

Analyzed terms, frequencies and class statistics remain cached. Until refresh, a hit can still match a removed term or receive a score based on earlier text. Class-wide document frequencies and average lengths also remain shared, so unreadable class members can affect scores. Current payloads and filtered hits do not establish that hidden records have no observable effect; aggregation, listing and counting have separate [open boundaries](../known-gaps-0.2.0.md#project-separation-has-several-boundaries). Verify the deployed build and its [read boundary](../firewall-and-projects.md#four-distinct-enforcement-questions).

With no `:rank-by`, class declarations can add a status and recency policy. A hit marked as superseded orders at half its raw BM25F score, then equal ordering weights are resolved by recency. The returned `:score` remains the unscaled BM25F value, and demoted hits carry `:superseded? true`. Set `:rank-by :relevance` for score-only order. This policy can improve discovery of a current successor, while a much more relevant historical record can still lead; neither status nor recency establishes authority by itself.

The MCP default projection is `metadata-only`; the in-process default is full. Request full entities for interpretation, or use returned IDs for exact reads. Snippets are approximate text windows, not evidence of an exact phrase match. Diagnostic `:field-scores` score fields separately and do not sum to the combined score.

## References and multiple populations

A weighted reference field can include primitive text selected from its declared target class's searchable fields. Expansion is bounded to one hop. At this boundary, target text is combined into the referring field; the referring field's weight applies. This does not imply recursive graph search or propagation of the target's numerical weights.

A class vector searches two to eight populations, each with its own weights and statistics, then merges results. Raw scores from different populations are only approximately comparable. Choose disjoint populations when the intended result is one hit per entity; overlapping class scopes require an explicit identity and counting policy. A relevance threshold across such populations is not a calibrated confidence estimate.

Use a single class for temporal reranking or faceting in this revision. Multi-class temporal ranking can fail when it compares date values numerically, and multi-class facets can lose fields when projection occurs before counting. A weight override is reliable only for fields the class cache already analyzes; selecting a new field through the override alone does not add it to that cache. These option-composition defects remain [release work](../known-gaps-0.2.0.md). Many-valued facets currently use the whole collection as a bucket key rather than counting each member separately.

## Freshness and cost are observable contracts

Accepted writes and analyzed search state have distinct completion boundaries. MCP search waits for pending refresh work for a bounded interval. A quiet refresh queue alone does not establish that every refresh succeeded or that every class population is complete. Read exact entities to inspect accepted state, and use operator health and release tests to establish retrieval freshness.

Measure cold analysis, warm scoring, post-write refresh, filtering, reranking, and enrichment separately. Report the class population, query set, revision, cache state, and whether authentication and transport are included. A historical small-corpus timing is not a latency guarantee for another deployment.

Try the [search guide](../guides/searching-the-corpus.md), then use [navigation](navigation.md) to inspect the relationships behind a result. Implementation: [`search.clj`](../../src/sandbar/search.clj), [`BM25F kernel`](../../src/sandbar/search/bm25f.clj), and [`analyzer`](../../src/sandbar/search/analysis.clj).
