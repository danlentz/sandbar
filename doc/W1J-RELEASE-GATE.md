# Representation and isolation release gate

The `w1-release-gate` command composes two checks: semantic document round trips and directional/visibility checks over synthetic project fixtures. It provides a reproducible baseline for the provenance work. Its green result has the scope of those fixtures and mechanisms; release acceptance also needs the deployed paths and the supported edge cases.

## Run the committed gate

From a configured development checkout:

```sh
lein w1-release-gate
```

The test-runner surface is:

```sh
lein test sandbar.gate.release-gate-test
```

The gate manages ephemeral `datomic:mem` databases and temporary document stores. Both composed checks must pass for the script to exit zero. Run it in a process with a test configuration; fixtures that manage an ambient connection are not a reason to share a running production JVM.

## What round-trip equivalence checks

The [contract implementation](../src/sandbar/gate/roundtrip_contract.clj) compares these dimensions:

| Query | Dimension |
| --- | --- |
| Q1 | File-backed Memory population |
| Q2 | Memory-type distribution |
| Q3 | Scope distribution |
| Q4 | Relative-path set |
| Q5 | Names by file |
| Q6 | Hash of trimmed raw body by file |
| Q7 | Resolved citation edges by file |
| Q8 | Tag vocabulary and Section count |

The committed fixtures expect no allowed drift. An exception list for another input needs a reason and an independent expected result; broadening it to make a regression green defeats the check.

These dimensions are useful but not a complete representation oracle. Counts alone do not establish section ancestry, order or heading/body association. Extend acceptance with nested and mixed-depth sections, multiline typed fields, native non-Memory classes, unknown metadata, identity, and edits that remove previously imported values. Check first-pass semantic preservation as well as repeated normalization.

## What the isolation scoreboard checks

Directional cases exercise forbidden cross-boundary references and traversal. Absence cases check that private material is absent from an uncleared fixture while present in its cleared control. A content-level information leak remains a separate authorship/disclosure problem; vocabulary novelty cannot be certified by a structural edge check.

Two seams in the committed harness limit the interpretation. The middle “clone” step is a filesystem copy, not an exercised Git publish/clone path. The uncleared database is built by loading only the public fixture, not by proving that every response over a mixed database enforces principal clearance. The report's historical “live” label means that a mechanism is called by the fixture; it does not mean a production instance was tested.

Before making the corresponding release claims, test the actual store-routing/export path and the principal-visible MCP, resource and REST surfaces. Include listing metadata and aggregate/search side channels, with both positive and negative controls. Tie every result to a revision and configuration.

## Interpret the result

A failed gate is actionable evidence about its named check. A passed gate establishes those assertions under the fixture conditions. Neither substitutes for a native database recovery test, transaction acceptance tests, cache freshness tests, transport conformance, or a complete project deployment rehearsal.

See [projection](concepts/projection.md), [firewall and projects](firewall-and-projects.md), [operations](operations.md), and the [gate source](../src/sandbar/scripts/w1_release_gate.clj).
