# Aggregation

> Sandbar treats `count` / `group-by` / structural-rank as **substrate-layer primitives**, not application-layer concerns.  Three opts-shaped verbs (`count-by` / `group-by` / `rank-by`) compose with the rest of the four-axis retrieval surface via shared `:where` Datalog clauses.  Four ranking axes — `degree`, `backlink-density`, `recency`, `freshness` — cover the structural-prominence retrieval space.  Class-agnostic substrate discipline: temporal slots are caller-supplied; no hardcoded knowledge of `:mm.memory/last-touched` or any other consumer-specific attribute.

## Thesis

A retrieval substrate ought to answer four orthogonal aggregation questions:

1. **"How many?"** — `count-by`
2. **"How many of each kind?"** — `group-by`
3. **"What's most-cited / most-edged?"** — `rank-by :degree` / `:backlink-density`
4. **"What changed recently / what's stalest?"** — `rank-by :recency` / `:freshness`

Each is reducible to the others in theory; in practice each emerges as a distinct retrieval question, deserving its own verb at the substrate.  Sandbar's aggregation surface ships them as substrate-layer primitives composing through one verb each — `count-by`, `group-by`, `rank-by`.

The structural-rank axes (`degree`, `backlink-density`, `recency`, `freshness`) are recognized retrieval-surface elements per `decisions/multi_axis_search_catalog_2026_05_08.md` axes 6-7-12.  Sandbar inherits the catalog and exposes each as a `rank-by` axis keyword.

## Lineage

### Datalog aggregation (Ullman, Garcia-Molina, Widom)

Datalog's aggregate functions (`count`, `sum`, `avg`, `min`, `max`) and group-by semantics are foundational — Ullman 1989, Garcia-Molina/Ullman/Widom *Database Systems: The Complete Book* (2008).  Datomic's Datalog dialect ships these; Sandbar's `dt/count-of` and `dt/group-by-of` are thin wrappers that supply Datomic-shaped aggregation queries.

### Structural ranking in IR + graph systems

PageRank (Brin & Page 1998), HITS (Kleinberg 1999), and their successors established degree-centrality and link-density as retrieval signals.  Sandbar's `:degree` and `:backlink-density` axes are simpler — direct edge counts, not eigenvector-derived — but the principle is shared: structural prominence is information.

### Temporal recency / freshness in retrieval

Temporal axes are recognized retrieval-surface elements — "most-recently-updated" and "stalest" are distinct ranking questions.  Sandbar's `:recency` axis returns entities ordered by descending temporal-slot value (most-recent first); `:freshness` returns ascending order (stalest first) for "candidates meriting attention or review."

## The verb surface

Three consumer-facing verbs in `sandbar.aggregate`:

### `count-by`

```clojure
(sandbar.aggregate/count-by
  {:class :mm/Memory
   :where '[[?e :mm.memory/memory-type :decision]]})  ; optional Datalog filter
;; => {:count 312}
```

Count entities of a class matching an optional `:where` filter.  Single integer result wrapped in `:count` key for projection consistency.

### `group-by`

```clojure
(sandbar.aggregate/group-by
  {:class    :mm/Memory
   :group-by :mm.memory/memory-type})
;; => {:groups {:decision 87 :observation 42 :plan 31 :pattern 18 ...}
;;     :total  312}
```

Group instances by slot value; count per group.  Includes `:total` for cross-check.  Optional `:where` Datalog filter constrains the candidate set before grouping.

### `rank-by`

```clojure
(sandbar.aggregate/rank-by
  {:class :mm/Memory
   :rank-by :backlink-density
   :limit 20})
;; => {:hits [{:entity <entity-map> :rank-score 142}
;;            {:entity <entity-map> :rank-score 89}
;;            ...]
;;     :total 312
;;     :returned 20}
```

Re-order instances by a structural-rank axis.  Four axes:

- **`:degree`** — total ref-attribute count (outbound + inbound by default; `:direction :forward` for outbound-only, `:inverse` for inbound-only)
- **`:backlink-density`** — inbound ref-attribute count (a distinct retrieval axis per `decisions/multi_axis_search_catalog_2026_05_08.md` axes 6 vs 7; what cites this entity?)
- **`:recency`** — descending order by temporal-slot value (most-recent first)
- **`:freshness`** — ascending order by temporal-slot value (stalest first)

For `:recency` / `:freshness`, `:temporal-slot` is REQUIRED — substrate does not hardcode class-specific temporal axes.  Caller passes e.g. `:mm.memory/last-touched`.

## Substrate-quality discipline

Per `interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md` and `interaction/improve_abstraction_not_bypass.md` — substrate primitives do NOT hardcode consumer-specific attribute knowledge.  Concretely:

- The aggregate namespace knows about `:dt/Class` and `:db.type/ref` (substrate vocabulary).
- It does NOT know about `:mm.memory/memory-type`, `:mm.memory/last-touched`, or any other `:mm/*` attribute.
- All consumer-specific slot references are caller-supplied via opts.

This makes the aggregate surface composable with future class hierarchies the substrate doesn't yet know about — including consumer hierarchies that don't exist yet.

## The dt/* primitive surface

Substrate primitives in `sandbar.db.datatype`:

| Primitive                  | What it does                                                          |
|----------------------------|-----------------------------------------------------------------------|
| `dt/count-of`              | Count instances of class with optional Datalog filter                 |
| `dt/group-by-of`           | Group-by-count facet aggregation; returns `{value count}` map         |
| `dt/degree-of`             | Total ref-attribute count for an entity (direction-configurable)      |
| `dt/backlink-density-of`   | Inbound ref-attribute count (named separately per retrieval axis)     |
| `dt/recency-rank-of`       | Class instances ordered by temporal-slot value descending             |
| `dt/freshness-rank-of`     | Class instances ordered by temporal-slot value ascending              |

The `sandbar.aggregate` namespace composes these primitives into opts-shaped verbs with result-shape projection.

## Composition with the rest of the retrieval surface

Aggregation is one axis of four (search / aggregate / navigate / orient).  Composition patterns:

- **Aggregate ∩ Search** — `:facet-by` opt on `search-bm25f` emits per-slot value counts over the BM25F match set (search-then-aggregate).
- **Aggregate ∩ Filter** — `:where` Datalog clauses on `count-by` / `group-by` constrain the candidate set before aggregation.
- **Aggregate ∩ Navigate** — (deferred to Stage 29 cross-axis composition) `:from` + `:via` will accept path-grammar to restrict the candidate set to a graph-walk neighborhood.

## What aggregation is NOT for

- **Numeric arithmetic over slot values** — `sum`, `avg`, `min`, `max` on a slot are outside the current verb surface; reach for Datomic Datalog directly (composing with `:where`).  Future extension is straightforward but not yet shipped.
- **Cross-class aggregation** — `count-by` / `group-by` / `rank-by` operate over instances of one class.  Cross-class aggregation requires composing multiple calls.
- **Eigenvector-based ranking** (PageRank, HITS) — the structural-rank axes use direct edge counts, not iterative centrality measures.  Not in scope.

## Performance characteristics

- **`count-by`** — O(matching instances) via Datalog `count` aggregate; near-zero overhead beyond the `:where` filter.
- **`group-by`** — O(matching instances) via Datalog `count` + group; one pass.
- **`rank-by :degree` / `:backlink-density`** — O(matching instances × average degree); the `dt/degree-of` per-instance query is two Datalog queries (outbound + inbound).  Profile if the matching set is large.
- **`rank-by :recency` / `:freshness`** — O(matching instances × log(matching instances)) — one Datalog query for `[?e ?slot ?t]` then sort.

For large corpora, the `:limit` opt is applied AFTER ranking — the full sort runs.  If post-sort top-K projection is the only concern, Datomic's pull-with-limit form can be composed at a follow-on optimization stage.

## References

- Ullman, Jeffrey D.  *"Principles of Database and Knowledge-Base Systems"*, Vol. 1 + 2.  Computer Science Press, 1989.  Datalog aggregation foundations.
- Garcia-Molina, Hector, Ullman, Jeffrey D. & Widom, Jennifer.  *"Database Systems: The Complete Book"* (2nd ed.).  Prentice Hall, 2008.
- Brin, Sergey & Page, Lawrence.  *"The anatomy of a large-scale hypertextual Web search engine"*.  WWW7 1998.  PageRank; degree-as-prominence lineage.
- Kleinberg, Jon.  *"Authoritative sources in a hyperlinked environment"*.  JACM 46(5), 1999.  HITS; backlink-density signal.

## See also

- `doc/concepts/fulltext-search.md` — the sibling axis that `:facet-by` composes with
- `doc/concepts/navigation.md` — the sibling axis that future `:from` + `:via` composition will compose with
- `doc/api/mcp-verbs.md` — `sandbar.aggregate.count` / `.group-by` / `.rank-by` MCP entries
- `doc/api/http-rest.md` — `GET /api/aggregate/count` / `/group-by` / `/rank-by` REST endpoints
- `doc/api/dt-star.md` — the six `dt/*` aggregation primitives
- The corpus's `decisions/multi_axis_search_catalog_2026_05_08.md` — 12-axis retrieval-surface catalog
