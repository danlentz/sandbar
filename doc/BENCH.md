# Sandbar bench discipline (F-DF-1 Phase 1+2 / Phase R Stage R-6)

This document describes the Sandbar 0.1.0 bench scaffolding for
the structural-rank + path-grammar hot paths.

The scaffolding is **timing-only** at 0.1.0 — its purpose is to
establish baselines that future optimization passes (Phase 3,
post-0.1.0) will regress against.

## What gets timed

Three hot paths at the size ladder `10 / 100 / 1k / 10k`:

| Axis | Function | Notes |
| --- | --- | --- |
| `rank-by :degree` | `sandbar.aggregate/rank-by {:class :dt/Class :rank-by :degree}` | Loads every instance + counts ref-typed edges per entity |
| `rank-by :backlink-density` | `sandbar.aggregate/rank-by {:class :dt/Class :rank-by :backlink-density}` | Same shape; counts inbound ref edges |
| `path-via :REP+` | `sandbar.navigate.path/path-via {:via [:REP+ :dt/subclass-of]}` | Transitive closure over the synthetic subclass chain |

Each axis × size yields a result map of the form:

```clojure
{:axis :rank-by/degree
 :size 100
 :median-ms 4.2
 :p95-ms    7.1
 :iterations 10
 :warmup 3
 :samples-ms [4.0 4.2 4.3 ...]}
```

## How to run

```sh
# Full ladder + emit bench-results/baseline.edn:
lein bench

# Smoke run at one size (faster iteration, exploratory work):
lein bench 100

# Custom ladder:
lein bench 50 500 5000
```

## What the baseline is for

`bench-results/baseline.edn` is the committed regression bar.
The discipline:

1. **Before any optimization PR**, capture a fresh local
   `bench-results/<timestamp>.edn` (the timestamped files are
   `.gitignore`d so they don't pollute commit diffs).
2. **Apply the optimization**.
3. **Capture a post-optimization fresh local result.**
4. **Compare** the post-optimization median-ms + p95-ms against
   the baseline.  An optimization that fails to improve median
   (or significantly worsens p95) is a non-improvement — do not
   merge.
5. **Update the committed baseline** only when the optimization
   PR explicitly intends to ratchet the regression bar; PR
   description must justify the new baseline.

Phase 3 (the actual optimization work) is out of scope for 0.1.0.
The scaffolding is what 0.1.0 ships; future PRs do the
optimization with this scaffolding as their oracle.

## File layout

```
bench/
  sandbar/
    bench/
      harness.clj    — timing primitives (median + p95 over n iterations)
      synthetic.clj  — synthetic-graph factory (chain + leaves at size)
      run.clj        — orchestrator entry; emits bench-results/baseline.edn
bench-results/
  baseline.edn       — committed regression bar (newest emitted at .clojars-release time)
  *.edn              — transient timestamped runs (.gitignore'd)
```

## See also

- `memory/plans/sandbar_0_1_0_codex_remediation_2026_05_14.md` §R-6
- `memory/ideas/sandbar_structural_rank_hot_paths_need_scale_baseline_2026_05_14.md`
- `memory/decisions/sandbar_0_1_0_codex_review_triage_outcome_2026_05_14.md` §D-2
