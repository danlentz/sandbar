# Find relevant records, then establish their context

This guide uses the three synthetic records from [the navigation guide](navigating-with-paths.md): a cache observation, an initial refresh decision, and its successor. Run those creation examples in a development database first. For MCP, complete [initialization](quickstart.md) and discover the advertised search tool.

## Start with the question

| Question | Operation |
| --- | --- |
| Which decisions discuss refresh? | BM25F over `:mm/Decision` |
| What concept covers these different words? | Tag lookup, definition and scope |
| Which records carry the selected concept? | Tag/theme membership query |
| Which record has this exact identifier? | Entity lookup |
| What replaced this decision? | Inbound `supersedes` navigation |
| How many decisions have this property? | Aggregation with a structural filter |
| Which names match a native fulltext query? | Single-attribute search |

Search finds a starting point. It does not determine whether the first match is the current decision.

## Use vocabulary to find a concept and its records

When terminology is uncertain, inspect the vocabulary before settling on search words. These are `tools/call` parameter objects, using a fictional `cache-policy` concept that must exist in your collection to return members:

```json
{
  "name": "sandbar_tag_lookup",
  "arguments": {
    "concept": "cache-policy",
    "limit": 5,
    "projection": "full"
  }
}
```

Lookup combines exact values and alternative/hidden labels with conceptual BM25F matches over typed Tags. Its `full` projection is a meaning summary: each match has an `eid`, value and match reason, plus its recorded definition, scope and lifecycle when present. An exact lightweight value can be found even without a Tag type or ident. Read those fields before choosing a concept; a missing `canonical?` value differs from explicit `false`.

The limit bounds the conceptual pass; additional exact matches can make `returned` larger. The reported `match-total` can overcount identities shared by the two passes, so use returned identities for inspection and a separately enumerated population for evaluation. Neither that count nor `gap?` proves a concept is absent from the collection.

For a selected match, pass its numeric `eid` to `sandbar.entity.find` as `id`, with `projection: "full"`, to read the complete entity. Then follow the two classification roles together. Replace the illustrative `42` with the selected match's eid:

```json
{
  "name": "sandbar_navigate_inbound-edges",
  "arguments": {
    "entity": 42,
    "predicate": [":mm.memory/tags", ":mm.memory/themes"],
    "source-type": ":mm/Memory",
    "projection": "metadata-only",
    "limit": 0
  }
}
```

Each edge retains its qualified `predicate` and the member under `source`. Deduplicate sources by `db/id` for reading while preserving their roles. `total` counts edges, `distinct-total` counts member identities, and `returned` counts returned edges. A member without an ident is still readable by its eid. Zero limit requests the whole selected neighborhood; use a bounded limit when exploring a large concept, while retaining the distinction between the page and total counts.

Handle a `blocked` edge before extracting its source: policy-blocked entries omit the endpoint and do not contribute to `distinct-total`.

Discover the tool schema first. Clients holding an older string-only predicate schema can pass the same array encoded as a JSON string; refresh discovery when possible. For a scalar-property summary or an explicitly named-only list, aggregation remains useful:

```json
{
  "name": "sandbar_aggregate_group-by",
  "arguments": {
    "class": ":mm/Memory",
    "group-by": ":db/ident",
    "where": "[[?e :mm.memory/tags ?t] [?t :mm.tag/value \"cache-policy\"]]"
  }
}
```

The group keys are memory identifiers to read with `entity.find`. This query covers the tags role only; substitute `:mm.memory/themes` for a separate theme summary. Grouping by ident omits unnamed records, so use the eid-based navigation above when the task is to enumerate all members. Do not infer that records with the same title are interchangeable.

Membership finds records carrying the concept even when their text uses different words. BM25F with the same `where` filter adds a lexical requirement and ranking; it does not enumerate every member.

When the target is already known, a reference filter can use its readable keyword
ident, eid, or lookup ref directly, such as
`[[?e :mm.memory/tags [:mm.tag/value "cache-policy"]]]`.
Citation, author and owning-project references use the same contract. Missing or
unreadable explicit targets return `filter-identity-unavailable`, not an empty
hit set. This checks explicit targets and BM25F checks returned entities; it does
not close the remaining [query privacy limits](../known-gaps-0.2.0.md#project-separation-has-several-boundaries).

Choose the next step according to what the vocabulary tells you:

| Observation | Next step |
| --- | --- |
| A candidate's definition and scope fit | Inspect its members, then read the relevant records and relationships. |
| Only broad candidates mention the query words | Refine the concept or inspect a known exact value before choosing a label. |
| Lookup returns no candidates | Check content search and known membership; this does not establish a new concept is needed. |
| The tool reports an error | Resolve the failure before interpreting the result as an absence. |
| Content search finds a useful record outside the member set | Read it and consider whether classification would help future readers. |

Vocabulary membership is an authored relationship. It can be incomplete, and its presence does not establish that a record is current or authoritative. A theme relationship alone also does not prove that its target has a reviewed definition. The [memory model](../concepts/memory-model.md#give-concepts-a-shared-vocabulary) explains why lightweight and curated vocabulary both have a place. Finish the journey by reading the selected evidence, not by accepting the first matching label.

## Search the declared fields

```clojure
(require '[sandbar.search :as search]
         '[sandbar.db.datatype :as dt])

(search/search-bm25f
  {:class :mm/Decision
   :query "refresh"
   :limit 10
   :projection :full})
```

On the isolated three-record fixture, both decisions match. The result contains `:hits`, `:total`, `:returned`, and `:timing`. Each hit carries `:eid`, `:entity`, and `:score`. Timing reports total elapsed search time; no per-stage timing fields are promised.

Use ordinary query words. `"refresh AND cache"` includes the word `AND`; BM25F does not parse it as a Boolean operator. See the [search concept](../concepts/fulltext-search.md) for the formula and analyzer.

The same call after MCP initialization is:

```json
{
  "jsonrpc": "2.0",
  "id": 20,
  "method": "tools/call",
  "params": {
    "name": "sandbar_search_bm25f",
    "arguments": {
      "class": ":mm/Decision",
      "query": "refresh",
      "limit": 10,
      "projection": "full"
    }
  }
}
```

Check the JSON-RPC error and tool `isError` before parsing the payload. If the result is unexpected, inspect its scope and errors before interpreting an empty hit list. The MCP default is metadata-only; this example requests the bodies explicitly.

## Restrict by a known property

```clojure
(search/search-bm25f
  {:class :mm/Decision
   :query "refresh"
   :where '[[?e :mm.memory/supersedes _]]
   :limit 10})
```

The fixture returns the successor, because it has a recorded `supersedes` relationship. The filter is a fact about the graph, rather than a word added to the query. In MCP, encode the clauses as an EDN string in `where`.

For a known reference value, resolve its entity ID first and use that numeric ID where the read-plane query policy requires it. A supported query grammar does not imply that every attribute namespace or keyword literal is exposed to every caller.

You can also combine search with a path restriction:

```clojure
(search/search-bm25f
  {:class :mm/Decision
   :query "refresh"
   :from :memory.examples/timed-refresh
   :via [:INV :mm.memory/supersedes]})
```

This asks for matching decisions reachable as recorded successors of the initial decision. `:where` and path restrictions intersect when both are supplied.

## Inspect why a result matched

```clojure
(search/search-bm25f
  {:class :mm/Decision
   :query "refresh"
   :include [:snippets :field-scores]
   :facet-by [:mm.memory/memory-type]
   :limit 1})
```

On this fixture, the total is two even though one hit is returned. The scalar facet counts two decisions. Snippets show approximate matching windows; field scores show what each field would score independently. Those diagnostics are not additive components of the canonical combined score.

Single-class facets describe the matching population before the limit. A small hit list therefore need not have the same counts as its facet map. Select a scalar facet such as memory type: a many-valued slot is currently grouped as a collection value. Multi-class faceting has a separate projection defect; keep this example single-class.

For BM25F requests carrying a read principal, that population contains only candidates readable under the current store's authority. The check precedes scores, snippets, totals, facets and limiting. Payloads, snippets and scalar facets use the current entity from the same database value as the visibility decision. Analyzed terms and frequencies remain cached, so matching and scores can lag a content change even when returned text is current. Class-wide statistics remain shared; scores do not prove isolation from unreadable records. Check the deployed build using the intended account, and inspect hits and enrichments as well as counts. A fully cleared operator's results do not establish a restricted client's view. See the [search contract](../concepts/fulltext-search.md#compose-a-question-without-changing-its-meaning).

## Change the ranking deliberately

The default weights come from the model:

```clojure
(dt/effective-bm25f-weights-of :mm/Decision)

(search/search-bm25f
  {:class :mm/Decision
   :query "refresh"
   :field-weights {:mm.memory/name 20.0
                   :mm.memory/description 8.0
                   :mm.memory/body-raw 1.0}})
```

This example reweights fields already declared for memories. Evaluate changes against queries with expected useful results; a higher score alone is not improved retrieval.

The ordinary default also considers class-declared supersession and recency: superseded hits receive half weight for ordering, with recency breaking equal ordering weights. Their reported score remains raw BM25F. Add `:rank-by :relevance` to measure score-only order. Compare both policies on current and historical questions before changing a retrieval policy.

To order matching records by time, add `:rank-by :recency` with `:temporal-slot :mm.memory/created`. `:freshness` orders the older values first, which is useful for identifying material to revisit. Choose records that have the named timestamp. Structural ordering replaces the primary score and retains BM25F as `:relevance-score`. Use one class for this operation until the multi-class temporal-comparison defect is repaired.

## Use native single-attribute search when it fits

```clojure
(dt/fulltext-indexed? :mm.memory/name)

(search/search-attribute
  {:attribute :mm.memory/name
   :query "refresh"
   :limit 10})
```

This path requires the attribute's `:db/fulltext` index and uses Datomic's native fulltext query behavior. Its Datomic/Lucene score has a different meaning from Sandbar's multi-field score. A `:db/fulltext` flag is not what makes a field participate in Sandbar's cached BM25F analyzer; the class weight declaration does that.

With a read principal bound, unreadable candidates are removed before sorting, totals and limiting, including private documents and their owned sections. A hidden match therefore does not consume a result slot. Returned entities come from the same current database value used for the visibility decision. This search rule does not supply a compartment filter for separate aggregate, list or count operations.

## Finish by reading relationships

Read the returned decisions in full. From the initial decision, follow inbound `supersedes`; from the successor, follow outbound `cites`. The [navigation guide](navigating-with-paths.md) performs both steps and shows a path witness.

Do not turn a rank or an arbitrary score cutoff into a statement that no governing record exists. Evaluate abstention separately, with questions whose answers are present, absent, superseded, and outside the search scope. Measure freshness separately from relevance quality.
