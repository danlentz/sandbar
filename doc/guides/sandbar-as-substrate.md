# Build an application on Sandbar

An application supplies its vocabulary and workflow policy. Sandbar supplies typed entities, model inspection, retrieval, representations, and supported accepting operations. Keep that boundary explicit so a new client can use the same knowledge without reimplementing the domain in its transport adapter.

## Choose the integration boundary

| Integration | Appropriate use | Responsibility retained by the application |
| --- | --- | --- |
| MCP client | Discover and invoke the server's operational surface | Connection lifecycle, permissions, errors, result interpretation |
| REST client | Use supported HTTP resources and CRUD contracts | Exact route/payload contracts and deployment access policy |
| In-process Clojure | Compose typed model operations and local functions | Database/component lifecycle, transaction policy, effect handling |

Begin with the [MCP quickstart](quickstart.md) for inspection or [Clojure guide](writing-a-clojure-client.md) for an embedded example. Do not import a server entry point merely to call a pure helper; starting components should be a deliberate lifecycle operation.

## Model a useful question first

Suppose an application records design reviews. It needs to identify the proposal, record evidence, track an outcome, and later explain why a decision changed. Start with those relationships. Reuse the memory and workflow vocabulary where it fits; add a class or slot for a meaningful domain distinction, not for every UI widget.

The [class guide](defining-new-classes.md) and [Zorp tutorial](zorp-tutorial.md) show schema extension. Class declarations can supply effective slots, inheritance, aliases, search weights, and a native codec. [Inference](../concepts/rdfs-entailment.md) makes declared relationships useful to common operations; [shapes](../concepts/shape-validation.md) express constraints under their supported enforcement contract.

A new class uses the existing class/entity/search/navigation verbs. It does not require one new MCP tool per CRUD operation. Author a new domain operation when there is a genuine reusable action with an invariant, such as accepting a transition or changing a vocabulary consistently.

## Reuse semantics across clients

Keep parsing, normalization, identity, validation, commit, and derived effects in clear reusable layers. An MCP handler should adapt inputs and results rather than contain a second implementation of a domain algorithm. The same applies to batch operations: define whether a batch is atomic or accumulates independent outcomes, and reuse the per-item semantics accordingly.

Use ordinary Clojure functions and data maps when they express the operation. A new generic framework needs several real consumers with the same policy, not merely similar syntax. Put application-specific recovery rules in a topical namespace that depends on the reusable mechanism.

## Choose what becomes durable knowledge

A human-authored review should remain readable outside the application. Use the [Markdown representation](../concepts/markdown-as-canonical.md) and [projection](../concepts/projection.md) when that is the chosen durable form. A runtime event or subscriber handle has a different lifecycle. Record useful results and rationale deliberately rather than turning every instrumentation event into a memory.

Define a recovery journey before declaring the data portable: reconstruct a supported document collection in an empty target, resolve references, compare identities and values, and account for every rejected document. Separately test database backup/restore if service state and history matter. Re-import over existing accepted data requires a conflict and deletion policy.

## Establish project boundaries before sharing

Enroll the project's ownership, context, identity authority, and document destination. A string naming a project does not establish physical isolation or caller permission. Test the actual read and write surfaces the application exposes, including metadata and exports, under its deployed credentials.

The [project-boundary chapter](../firewall-and-projects.md) explains the distinction between directional graph flow, principal clearance, operation permission, and publication. Reuse those mechanisms; do not compensate for a missing server boundary with a client convention that another caller can bypass.

## Operate the assembled application

Use an explicit client directory and configuration. Observe accepted database state, search freshness, projection health, and process lifecycle independently. Capture a backup before a destructive maintenance operation, inspect its dry run, and restore into a separate target before trusting the recovery plan.

The [operations guide](../operations.md) supplies the deployment procedure. The [development guide](../development.md) describes isolated tests. Keep test stores separate from a running application's connection, and use synthetic fixtures when demonstrating failures.

The result should be a small application layer that names its domain clearly, with reusable knowledge and consistent behavior through each supported client.
