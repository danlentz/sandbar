# Measure performance against a stated workload

A performance result is useful when another person can tell what work was measured, what state was warm, and whether the answers remained correct. Sandbar's committed structural benchmark is a small repeatable baseline. It is not a full search-quality evaluation or a service latency guarantee.

## Structural benchmark

The harness creates a fresh in-memory database, loads the required schema and builds a synthetic class graph. It measures:

| Axis | Operation |
| --- | --- |
| Degree | `sandbar.aggregate/rank-by` over `:dt/Class`, `:rank-by :degree` |
| Backlink density | The same class population, `:rank-by :backlink-density` |
| Transitive path | `sandbar.navigate.path/path-via` with `[:REP+ :dt/subclass-of]` from the synthetic root |

The default population ladder is 10, 100, 1,000 and 10,000, with three warmups and ten measured iterations per operation. The synthetic chain depth is three. Report that shape as well as population: two graphs of equal size can have very different traversal cost.

The path case follows outgoing `:dt/subclass-of` edges from `ChainRoot`, so it visits that root's ancestors. It does not walk the growing descendant chain and leaf population. Use a different start/direction when the question is traversal cost over that larger graph.

```sh
lein bench 100
lein bench 10 100 1000 10000
```

Both commands write `bench-results/baseline.edn`. Save any previous result before running; a smoke run also overwrites this output. Review a baseline change as evidence, rather than automatically committing whichever run happened last. The harness's version fallback is not a substitute for recording the measured Git revision and dependency versions.

The result retains individual samples and reports `:median-ms` and `:p95-ms` using a floor-based order statistic, without interpolation. With ten observations, those are the fifth and ninth sorted samples. A tail percentile from so few observations is a small-sample description. Repeat measurements when variation could reverse the decision. Keep machine/JDK, heap, dependency versions, source revision, fixture and background workload comparable, and investigate a regression rather than inferring its cause from a single number.

## Retrieval performance and quality

The structural harness does not measure the complete BM25F path. For a retrieval change, distinguish:

1. Cold analysis and cache construction, including referenced text expansion.
2. Warm queries over a complete cache.
3. Updates and time until relevant subsequent queries see the accepted change.
4. Filtering, path restriction, ranking, facets and output projection.
5. Multi-class merge behavior where the caller uses it.

Use questions with independently judged relevant results for quality. Measure useful ranking, missing results and abstention separately from elapsed time. Record the class population, query analyzer, field declarations and statistics scope. Changes that merely raise all scores do not establish better retrieval.

## Evaluate the application surface

A real application trial needs more than search scores. Begin with the advertised operation catalog and give each relevant operation an explicit disposition: exercised, deliberately excluded, or unsupported. Record invocation and error counts alongside independent checks of returned identities, bodies, graph paths, tag lookups, aggregate populations and expected refusals. Invocation counts show use; they do not show that the answer was useful or correct.

Keep the task rubric and judged examples fixed across baseline and changed runs. Measure task completion, incorrect decisions, recovery from missing information, elapsed time and client cost as well as retrieval quality. Retain raw requests and bounded responses under the trial's privacy policy. Separate an operator-reported outcome from an independently checked result, and distinguish cold, warm and post-update conditions.

The committed structural benchmark does not provide this complete evaluation harness. Use it for its three stated operations and assemble the additional protocol and application checks before drawing a whole-system conclusion. A synthetic release gate also supplies only the assertions it actually exercises; see [the gate's scope](W1J-RELEASE-GATE.md).

Check the [canonical BM25F contract](concepts/fulltext-search.md) before optimizing candidate selection. A faster candidate path is only equivalent if it preserves recall under the actual analyzer and field/reference semantics. A cache that returns promptly but omits accepted records is not a performance improvement.

## Compare a proposed change

Capture before/after results under the same workload and retain raw samples. Verify relevant semantic invariants first, then compare elapsed time, allocations or memory use according to the problem. State the tradeoff: a small increase in warm latency may be justified by correctness or a large reduction in update cost. No universal rule requires every metric to improve simultaneously.

The harness lives in [bench/sandbar/bench](../bench/sandbar/bench). For service behavior, supplement it with an authenticated client journey and distinguish transport/authentication overhead from the underlying operation. See [development](development.md) and [operations](operations.md).
