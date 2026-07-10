# Searching the corpus

> Hands-on walkthrough of Sandbar's fulltext-search surface — BM25F multi-field weighted scoring over Datomic + Lucene.  When to use which verb.  Worked examples for `:where` composition, `:facet-by` aggregation, and `:include` projection options.  For the algebraic theory + lineage, see [`doc/concepts/fulltext-search.md`](../concepts/fulltext-search.md).

## Pick the right primitive

| Question                                                  | Primitive                              |
|-----------------------------------------------------------|----------------------------------------|
| "Single-field search; return matches"                     | `sandbar.search/search-attribute`      |
| "Multi-field weighted search across a class's slots"      | `sandbar.search/search-bm25f`          |
| "Facet counts over a search result set"                   | `:facet-by` on `search-bm25f` |
| "Snippets / highlights"                                   | `:include [:snippets]` on `search-bm25f` |
| "Combine fulltext + structured filter"                    | `:where` opt on `search-bm25f`         |

The substrate primitives live in `sandbar.db.datatype`:

- `dt/search-fulltext` — single Lucene-backed attribute query
- `dt/bm25f-weights-of` — read declared weights from class entity
- `dt/fulltext-indexed?` — predicate; is this attribute `:db/fulltext true`?

## Declaring `:db/fulltext` slots

A class's string slots that should be searchable need `:db/fulltext true` in the schema:

```edn
;; schema/mm.edn excerpt
{:db/ident :mm.memory/name
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/fulltext true                ; ← required for fulltext indexing
 :dt/required? true}

{:db/ident :mm.memory/body-raw
 :db/valueType :db.type/string
 :db/cardinality :db.cardinality/one
 :db/fulltext true}
```

Datomic builds the Lucene index at transact time for `:db/fulltext` slots.  Without this flag, `(fulltext ...)` queries return empty.  Use `dt/fulltext-indexed?` to assert at runtime.

## Declaring per-class BM25F weights

Per-class slot weights live in the schema layer:

```edn
;; schema/mm.edn
{:db/ident   :mm/Memory
 :dt/type    :dt/Class
 :dt/subclass-of :dt/Resource
 :dt/slots   [:mm.memory/name
              :mm.memory/description
              :mm.memory/body-raw
              :mm.memory/tags]
 :dt/bm25f-weights [[:mm.memory/name        12.0]    ; ← high weight on titles
                    [:mm.memory/description  8.0]
                    [:mm.memory/body-raw     1.0]    ; ← unit weight on body
                    [:mm.memory/tags         6.0]]}
```

At query time, the substrate reads weights from the class entity via `dt/bm25f-weights-of`.  Callers may override via the `:field-weights` opt, but the schema declaration is the default.

## Pattern 1 — Single-attribute search

The simplest form.  One `:db/fulltext` attribute, ranked by Lucene's single-field BM25:

```clojure
(require '[sandbar.search :as search])

(search/search-attribute
  {:attribute :mm.memory/name
   :query     "datomic"
   :limit     20})
;; => {:hits [{:entity <entity-map> :score 5.42} ...]
;;     :total <int>}
```

**Use when:** the search is field-specific (titles only, body only) and per-field weighting isn't needed.

## Pattern 2 — Multi-field BM25F

The general form.  Walks all `:dt/bm25f-weights` slots, scores per field, combines via the Robertson-Zaragoza canonical formula:

```clojure
(search/search-bm25f
  {:class :mm/Memory
   :query "datomic recursive rules"
   :limit 20})
;; => {:hits [{:entity <entity-map> :score 12.34} ...]
;;     :total <int>
;;     :timing {:tokenize-ms 1 :score-ms 12 :total-ms 14}}
```

Override declared weights:

```clojure
(search/search-bm25f
  {:class :mm/Memory
   :query "datomic"
   :field-weights {:mm.memory/name 20.0     ; boost titles further
                   :mm.memory/body-raw 0.5}  ; dampen body
   :limit 10})
```

**Use when:** ranking quality matters across multiple slots — titles + body + tags weighted distinctly.

## Pattern 3 — Compose with `:where` Datalog

Fulltext ∩ structured filter.  The `:where` clauses constrain the candidate set BEFORE BM25F scoring:

```clojure
;; Search only :decision-typed memories
(search/search-bm25f
  {:class :mm/Memory
   :query "datomic recursive rules"
   :where '[[?e :mm.memory/memory-type :decision]]
   :limit 20})

;; Search only memories tagged "architecture"
(search/search-bm25f
  {:class :mm/Memory
   :query "datomic"
   :where '[[?e :mm.memory/tags ?tag]
            [?tag :mm.tag/value "architecture"]]
   :limit 20})

;; Combine multiple filters
(search/search-bm25f
  {:class :mm/Memory
   :query "BM25F"
   :where '[[?e :mm.memory/memory-type :decision]
            [?e :mm.memory/scope :global]]
   :limit 20})
```

`?e` is the conventional variable name for the entity at the head of the BM25F walk; bind to it in your `:where` clauses to filter the candidate set.

## Pattern 4 — Snippets + highlights

`:include [:snippets]` emits per-slot snippet windows centered on the first query-term hit:

```clojure
(search/search-bm25f
  {:class   :mm/Memory
   :query   "datomic recursive rules"
   :limit   10
   :include [:snippets]})
;; => {:hits [{:entity <entity-map>
;;             :score  12.34
;;             :snippets {:mm.memory/name "...**datomic** **recursive** **rules**..."
;;                        :mm.memory/body-raw "...the **datomic** layer handles **recursive** ..."}}
;;            ...]}
```

Highlighting is `**term**` markdown syntax.  Snippet window ~240 chars centered on the first match, with ellipsis pre/suffix when text continues beyond edges.  Approximate (regex-based); Lucene's native positional highlighter is a Phase-2 optimization.

## Pattern 5 — Facets

`:facet-by` emits per-slot value counts over the full BM25F match set (before `:limit`):

```clojure
(search/search-bm25f
  {:class    :mm/Memory
   :query    "datomic"
   :limit    20
   :facet-by [:mm.memory/memory-type :mm.memory/scope]})
;; => {:hits   [...]
;;     :facets {:mm.memory/memory-type {:decision 12 :plan 7 :observation 4 :pattern 2}
;;              :mm.memory/scope       {:global 18 :scoped 7}}}
```

**Use when:** the consumer needs both ranked results AND aggregated counts over the same query — saves a round-trip vs separate `search` + `aggregate` calls.

## Pattern 6 — Per-field score breakdown

`:include [:field-scores]` exposes the per-field score contributions for debugging or relevance tuning:

```clojure
(search/search-bm25f
  {:class :mm/Memory
   :query "datomic"
   :limit 5
   :include [:field-scores]})
;; => {:hits [{:entity <entity-map>
;;             :score 12.34
;;             :field-scores {:mm.memory/name        8.2
;;                            :mm.memory/description 3.1
;;                            :mm.memory/body-raw    1.04
;;                            :mm.memory/tags        0.0}}
;;            ...]}
```

**Use when:** tuning weights or diagnosing why a result ranked unexpectedly.

## Through MCP

The `sandbar.search.bm25f` MCP verb is live — it is the corpus's primary retrieval verb.  It accepts the same opts as the in-process form: `query` + `class` (required; `class` also accepts a JSON array of 2–8 class idents for multi-class strategic-subgroup retrieval), plus optional `limit`, `where` (EDN-string Datalog clauses over `?e`), `facet-by`, `include` (`"snippets"` / `"field-scores"`), `field-weights`, the Stage-29 composition opts `from` + `via` / `rank-by` / `temporal-slot`, and `projection` (defaults to `metadata-only` at the MCP boundary — opt into `"full"` when you need slot bodies):

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{
        "name":"sandbar.search.bm25f",
        "arguments":{
          "class":":mm/Memory",
          "query":"datomic recursive rules",
          "limit":20,
          "facet-by":[":mm.memory/memory-type"],
          "include":["snippets"]}}}'
```

## Cross-axis composition

Search composes with aggregation directly via `:facet-by`.  Stage 29 composition with navigation is live: `:from` + `:via` (path-grammar restriction) makes a graph-walk neighborhood the candidate set for BM25F ranking, and `:rank-by` (`:degree` / `:backlink-density` / `:recency` / `:freshness`, the latter two requiring `:temporal-slot`) re-ranks the top-K by a structural axis while preserving the BM25F score as `:relevance-score`.

## Performance notes

- **Indexing** — Lucene segment-based at transact time; segment-merge cost amortized across transactions.
- **Query at small corpora (≤10k memories)** — sub-millisecond per single-field query; ~10-20ms for multi-field BM25F.
- **Query at large corpora** — Lucene's inverted-list traversal dominates; per-field BM25F adds linear cost in number of weighted slots.  Stop-word filtering at index time is the standard mitigation for high-frequency-token sets (enable per consumer demand).
- **Snippet generation** — O(slot-text-length) per slot in result set; bounded by `:limit`.

## See also

- [`doc/concepts/fulltext-search.md`](../concepts/fulltext-search.md) — lineage, theory, references
- [`doc/concepts/aggregation.md`](../concepts/aggregation.md) — the `:facet-by` composition pattern
- [`doc/guides/navigating-with-paths.md`](navigating-with-paths.md) — composing search + path-grammar (Stage 29, live on the search axis)
- [`doc/api/dt-star.md`](../api/dt-star.md) — `dt/search-fulltext` / `dt/bm25f-weights-of` / `dt/fulltext-indexed?` substrate primitives
- The corpus's `decisions/bm25f_canonical_robertson_zaragoza_form.md` — ADR locking the canonical form
