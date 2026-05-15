# Legacy Documentation Archive

This directory holds documentation superseded by the layered structure landed in the 2026-05-13 documentation-architecture arc.  Files here are kept for git history continuity but are **no longer the canonical reference**.

## Where the content went

| Legacy file              | Successor in the layered structure                                                              |
|--------------------------|--------------------------------------------------------------------------------------------------|
| `api.md`                 | [`doc/api/http-rest.md`](../api/http-rest.md) — full REST endpoint catalog                       |
| `architecture.md`        | [`doc/concepts/`](../concepts/) — distributed across all 7 concept documents                     |
| `event.md`               | (no direct successor — event design lives in source code)                                        |
| `index.md`               | [`README.md`](../../README.md) — the new entry point                                             |
| `jobs-api.md`            | [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) + [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) |
| `mcp-server.md`          | [`doc/concepts/mcp-protocol.md`](../concepts/mcp-protocol.md) + [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) + [`doc/guides/writing-an-mcp-client.md`](../guides/writing-an-mcp-client.md) |
| `meta.md`                | [`doc/concepts/metamodel.md`](../concepts/metamodel.md) + [`doc/api/dt-star.md`](../api/dt-star.md) |
| `quickstart.md`          | [`doc/guides/quickstart.md`](../guides/quickstart.md)                                            |
| `storage-model.md`       | [`doc/concepts/multi-store-architecture.md`](../concepts/multi-store-architecture.md)            |
| `store-api.md`           | [`doc/api/http-rest.md`](../api/http-rest.md)                                                    |
| `tasks-api.md`           | [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) + [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) |
| `workflow.md`            | [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) + [`doc/guides/designing-workflows.md`](../guides/designing-workflows.md) |
| `zorp-example.md`        | [`doc/guides/zorp-tutorial.md`](../guides/zorp-tutorial.md)                                      |

## Pre-existing archive

`computation-model.md`, `datatype-schema.png`, `datomic-console.png`, `meta-model.md`, `toolchain.md` predate this arc and were already archived before 2026-05-13.

## Reading the new structure

Start at [`README.md`](../../README.md).  For depth follow [`doc/concepts/`](../concepts/) (theoretical reference; citation-rich); for hands-on use follow [`doc/guides/`](../guides/) (practical how-to); for the mechanical surface follow [`doc/api/`](../api/) (every function, every endpoint, every verb).
