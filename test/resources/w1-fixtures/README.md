# W1.J release-gate fixtures — two-store corpus

Synthetic two-store corpus for the W1.J standing release gate
(`sandbar.scripts.w1-release-gate`). Stands in for the real dogfood
second-project until Dan names it (W1 arc plan G3: "exactly two stores,
public + one private").

## Stores

- `public-corpus/` — the public store (stands in for the live
  `~/claude` corpus). Free of any private terminology.
- `second-project/` — the private store (the centerpiece "second
  private project, its own repo, firewalled, round-tripped"). Carries
  the private markers the CHECK-2 absence probes rely on.

## Private markers (load-bearing for the absence-probe battery)

| marker kind        | value                       | probe                              |
|--------------------|-----------------------------|------------------------------------|
| private-only term  | `zephyrite`                 | BM25F zero-hit + zero-IDF (ABS-4)  |
| private-only tag   | `proprietary-secret-sauce`  | tag-histogram bin absent (ABS-3)   |
| private slug       | a `second-project/` memory  | entity.find MISSING (ABS-1)        |
| private doc count  | 2 private memories          | aggregate count → 0 (ABS-2)        |

The physical-exclusion model: an **uncleared** (public-scope) session DB
is built from the public store ONLY, so every private marker is
genuinely ABSENT. The **cleared** negative-control DB is built from both
stores, so every marker is PRESENT. Until W1.deploy's closure-bounded
build lands, the harness constructs the uncleared DB by importing only
the public store — a clearly-marked seam; the probe assertions do not
change when the real closure build replaces the seam.

## Round-trip (CHECK 1)

Both stores are pure `:mm/Memory`-subtree markdown, chosen to be inside
the projecting subtree, so the DB→FS→DB round-trip has ZERO expected
drift (the documented 226-item deferred drift applies only to the live
full corpus, not to this fixture).
