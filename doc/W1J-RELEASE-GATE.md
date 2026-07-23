# W1.J release gate — runbook

The **standing composed release gate** for the 0.2.0 W1 provenance arc.
Enforces **G1**: a green build requires BOTH round-trip semantic
equivalence AND the firewall attack scoreboard. Round-trip green is a
*hard precondition of the first W1.F commit* — this gate is authored
**early** (before W1.F/W1.deploy exist) so that precondition is
enforceable, not aspirational.

## Invoke

```sh
# one-time local setup (config.edn is gitignored/machine-local, like every sandbar test):
cp config/config-example.edn config/config.edn      # if you don't already have one

lein w1-release-gate        # runs both checks; prints the scoreboard; exit 0 iff GREEN
lein test sandbar.gate.release-gate-test            # same gate as clojure.test (CI surface)
```

Exit `0` iff GREEN, `1` otherwise — **wire `lein w1-release-gate`'s exit
into CI as the release gate** (and, per G1, as the precondition of the
first W1.F commit).

Every check runs against ephemeral `datomic:mem` fixtures; the live store
is never touched.

## The two checks (GREEN = BOTH pass)

**CHECK 1 — round-trip semantic equivalence** (`sandbar.gate.roundtrip`
+ `…/roundtrip-contract`). `DB → FS → [git seam] → DB`, then the 2026-05-12
export ADR §D.5 8-query contract, modulo documented drift:

| query | dimension |
|-------|-----------|
| Q1 | file-backed `:mm/Memory` population |
| Q2 | `:mm.memory/memory-type` distribution |
| Q3 | `:mm.memory/scope` distribution |
| Q4 | rel-path set (corpus file-set) |
| Q5 | per-file `:mm.memory/name` |
| Q6 | per-file SHA-256 of trimmed body-raw |
| Q7 | per-file resolved cites edges |
| Q8 | tag-value vocabulary + `:mm/Section` count |

**CHECK 2 — firewall attack scoreboard** (`sandbar.gate.scoreboard`):
4 directional attacks (mechanical) + 4 absence probes (each with a
cleared-session negative control) + 1 documented disciplinary residual.

| row | what | enforcement today |
|-----|------|-------------------|
| ATK-1 | public→private cite | **live** (directional firewall) |
| ATK-2 | cross-private diamond | **live** |
| ATK-3 | `{:validate? false}` bypass | **live** (floor holds) |
| ATK-4 | EP-3 traverse of a legacy forbidden edge | **live** (`:blocked`, no `:target`) |
| ABS-1 | entity.find private slug → MISSING | seam (W1.deploy) |
| ABS-2 | aggregate count over private project → 0 | seam (W1.deploy) |
| ABS-3 | tag-histogram private-only bin absent | seam (W1.deploy) |
| ABS-4 | BM25F private-only term → zero-hit + zero-IDF | seam (W1.deploy) |
| ATK-CONTENT | content-leak via novel private terminology | **documented-disciplinary** (not mechanical) |

## Seam map — what activates when F/G/deploy land

The gate is standing NOW; three clearly-marked seams swap in the real
mechanism without touching the contract or the probe assertions:

- **git commit + clone** (CHECK 1 middle) — `roundtrip/clone-stub!` is a
  filesystem deep-copy today. W1.F replaces THIS FUNCTION ONLY (commit to
  the per-project corpus repo → `git clone`); Q1..Q8 are unchanged.
- **physical exclusion** (CHECK 2 absence probes) — the *uncleared* session
  DB is built today by importing only the public store. W1.deploy's
  closure-bounded `db-firewall-closure` build produces that same uncleared
  DB from the `context ∪ public` closure; the four probe assertions do not
  change.
- **content-semantics** (ATK-CONTENT) — remains disciplinary; W1.E's
  authorship-provenance flag + W1.H's declassification/sanitize gate are
  its named backstops. Never scored as mechanically green.

## Two-store fixture

`test/resources/w1-fixtures/{public-corpus,second-project}` (see that
dir's README) — a synthetic private second project standing in for the
real dogfood until Dan names it. Round-trips with ZERO expected drift
(pure `:mm/Memory`-subtree). Carries the private markers ABS-1..4 key on
(`zephyrite` term, `proprietary-secret-sauce` tag). The live-corpus run
passes the documented 226-item deferred-drift set via
`:allowed-drift`.
