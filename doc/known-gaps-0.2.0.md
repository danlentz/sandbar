# Supported boundaries and known gaps in 0.2.0

This page describes boundaries that affect how Sandbar can be used. Individual operation contracts live in the API reference; [release notes](../CHANGELOG.md) record resolved defects. The [operator guide](operations.md) supplies setup, export, and recovery procedures.

## Inference covers a specified fragment

Sandbar uses recursive Datalog rules for inherited membership and slots, with additional rules for selected RDFS and OWL property semantics. It does not claim complete RDFS or OWL conformance. A raw query does not automatically include every entailment rule. See [Inference and entailment](concepts/rdfs-entailment.md) for the supported families and query boundaries.

Likewise, shape support has its own implemented constraint vocabulary and applicability rules. A SHACL reference explains the design lineage; it is not a claim that every SHACL feature is available.

## Documents preserve a defined model

The supported round trip preserves specified knowledge in normalized form. It does not promise identical Markdown bytes, complete YAML/CommonMark coverage, or reconstruction of every database entity class. The [codec](concepts/codec-layer.md) and [projection](concepts/projection.md) contracts define supported representations.

Normal startup registers Markdown for document authoring. The optional JSON entity codec is outside the default 0.2.0 profile; use `sandbar.codec.list` to discover a server's registered formats. This limit does not concern the JSON encoding of MCP requests and responses.

Database history, credentials, and runtime state need their own backup and recovery arrangements. Before reconstructing a knowledge store from files, reconcile missing projections and failed writes; an unprojected database entity has no durable document carrier.

Extra frontmatter fields need particular care. The emitter's configured critical-key set refuses a write that would remove one of those keys. The default set is `at-startup` and `one-line`; `SANDBAR_REGISTRY_CRITICAL_KEYS` replaces it, and an empty value disables that refusal tier. Other extra fields have weaker preservation behavior: detected drops can be warned about while emission proceeds, and an unresolved carrier can lose extras without that warning. Applications that rely on extra fields need a preservation fixture and an explicit policy for those fields.

## Project separation has several boundaries

The release architecture uses a shared knowledge database and separate project document trees. Labels and directional checks govern permitted graph flows. Private knowledge may refer to public knowledge; public knowledge must not refer to private knowledge. Separate directories alone do not establish database isolation or prove that outputs are safe to publish.

The guarded project exporter now checks caller clearance and the configured destination before staging output, with whole-document holds and a separate private audit. Its bounded carrier support intentionally holds unknown extras, ambiguous links, body/section disagreements and unrepresentable content. Missing ownership, UUIDs or compatible author labels can prevent older records from exporting; the command does not perform a backfill. Automatic field redaction, semantic declassification, git checkpoints and guarded restore remain unbuilt. Follow the stopped-writer preview/execute/audit procedure and publish only the separately reviewed subset. Project-specific credentials still require explicit provisioning; full-clearance access is not a project-isolation guarantee.

The logical read surface also has residual information channels. Some aggregation, listing and counting operations can reveal the existence or count of unreadable records; search filtering does not close those paths. Other operations can reveal registry definitions, redaction structure, or account metadata even where credential values are scrubbed. In particular, resource enumeration and the principal-gated update surface need to be considered separately from ordinary entity reads. Do not interpret value scrubbing as complete metadata confidentiality or as a blanket prohibition on reaching an authentication entity through a write operation. Physical separation of authentication data is outside the 0.2.0 boundary.

Count, group-by and BM25F authorize explicit identities in their `where`
filters. This fixes ordinary citation, author, project and tag selectors without
making arbitrary Datalog joins private. A variable join can still test a hidden
target's scalar attributes and affect the result; count and group-by source
populations are not clearance-filtered. Keywords and eids can identify a constant
entity, while a lookup vector leading a literal clause remains unsupported query
syntax. Lookup refs work as reference-property values. None of these filters
rewrites a `db/ident` scalar into another representation.

Permission labels cannot recognize a paraphrase of private information embedded in public prose. Applications remain responsible for the content they submit, and logs, hooks, editor integrations, and client-side files need their own output policy. These paths can disclose information without invoking a database operation.

There is no general automatic promotion of private material to public knowledge. A sanitized copy requires a deliberate content decision, the ordinary write checks, and provenance that is itself safe for its destination. See [projects and boundaries](firewall-and-projects.md).

## Supported write sequences matter

Create a project before importing its members. Co-batching a new project definition with public and private members has a known source-label resolution gap: a forbidden edge can be accepted when the source's owning project is not yet committed. Export must independently enforce disclosure, and a filtered importer must prove its own exclusion behavior before it is used across project boundaries. Neither a later scrub nor the presence of a label makes this batch sequence safe.

Conversely, an existing public entity that references private or unassigned targets can be over-restricted: a change to its prose may be refused because the write check evaluates the entity's existing relationships. Resolve the scope or relationships through the supported procedure. A refusal is not an invitation to bypass the mutation boundary.

For ordinary card-many updates, replacement is the default; request `additive: true` when adding to the existing set. This is an API contract rather than a defect. Consult the [MCP verb reference](api/mcp-verbs.md) before constructing an update.

## Import requires a maintenance window

Stop every writer before editing canonical files and retain quiescence through preview, import and audit. Import the evidence-backed subset into the existing store to preserve established identities. The default mode replaces source-owned representation; `additive` is an explicit alternative. Basis and source hashes protect an import attempt from intervening changes, but do not identify a file that was already stale when preview began. Keep ambiguous identities, ownership transfers and unresolved duplicate files outside the selected input with both versions retained. See the [operator sequence](operations.md).

Bulk import does not run interactive shape validation. A per-file success is not a report that all shapes passed, and several successful files do not make the whole batch atomic.

With configured project roots, import preview and persist enforce source-tree owner agreement, refuse cross-tree owner moves or adoption, and protect mapped Project keys from renaming, replacement-mode omission and takeover. Additive omission retains the key. Staging is allowed under those same guards; an empty map leaves the existing importer validation unchanged. The owner guard does not forbid an imported relative-path change within the same tree. Follow the [operator procedure](operations.md#maintenance-import-into-the-existing-store) for old/new path reconciliation and exact-root audits; deeper or ad hoc walks are labelled store-wide diagnostics, not evidence of whole-tree cleanliness.

## Filesystem projection assumes one writer

One Sandbar process owns a projection tree. Path validation does not provide confinement against another process changing the filesystem between validation and the eventual write. A deployment with concurrent or untrusted writers needs stronger filesystem controls and a new assessment of that path.

Two instances sharing a directory also need an explicit synchronization and conflict policy. Export, version control, and import are useful components, but do not by themselves constitute a multi-writer replication protocol.

## Provisioning and routing remain explicit

Provisioning uses the supported operator procedure: the operator enrolls the Context and Project and installs an authorized destination during maintenance. A thin client installer, `mem onboard-project` in the memory corpus ([Use Sandbar from a project repository](https://github.com/danlentz/claude/blob/master/doc/project-onboarding.md)), then previews and applies a Codex or Claude Code binding to that already-enrolled Project from the repository's own worktree. It full-reads the Project over MCP, matches its class, stable key, document ident and default visibility to the requested binding (including the required `--privacy public|private`), and records its UUID for subsequent client checks.

The installer writes only repository-local files: `.sandbar/config.edn`, `.sandbar/README.md`, and either `.codex/config.toml` with a managed block in `AGENTS.md` or `.mcp.json` with a managed block in `CLAUDE.md`. It does not create a Context or Project, grant a filesystem destination or a restricted credential, import, export, restore, change client trust or global settings, or restart a service. Conflicting existing MCP configuration or a changed managed block requires manual reconciliation. General routing/listing verbs and arbitrary orchestration across many scopes remain outside the release's supported breadth. The operator must still verify client configuration, credentials, projection destinations, and any client-side logging for each deployment.

The operator-owned `:project-roots` map supports distinct persistence trees in one store. Existing-file migration remains stopped-writer maintenance; ordinary edits cannot move a file or rename a mapped project's stable key. Enrollment requires evidence of matching store/file UUIDs, canonical paths without unresolved physical aliases, and valid owners. The drift audit reports those findings under `:enrollment-preflight`; the client installer is used only after the operator's destination is in place. Operators may retain explicitly excluded paths and both unresolved versions, but an import exclusion alone does not stop projection. Separate roots do not provide separate identity namespaces or public-output filtering. The [operator procedure](operations.md#install-or-change-a-project-destination) describes the preconditions and exclusion limits; it supplies no blanket export or automatic repair.

An isolated rehearsal on 20 September 2026 passed 62 AI checks, 32 Babashka client checks and 26 runner checks. Three fresh Codex sessions used native MCP to capture, edit and reopen a synthetic memory across two checkouts against one retained Datomic in-memory store. They preserved identity, owner and citation, found the memory through BM25F, followed its citation through graph navigation, and projected its content into the configured project root. Enrollment intentionally merged the MCP configuration into each checkout's existing Codex configuration, retained its original contents, and left settings unchanged through the subsequent sessions. This exercised a public project with a fully cleared account and predates the client installer. A later isolated run exercised the Codex binding end to end: preview, apply and repeat install, then fresh Codex sessions capturing, editing and reopening against one retained store. The Claude Code binding is implemented and passes the same local installation checks, but a native Claude Code session has not yet authenticated and used it. None of these results proves private-principal enrollment, existing-file migration, service restart or deployment, whole-surface evaluation, isolation of a scored client, or the FIGfont trial.

An earlier combined regression run covered routing, import/audit and the initial BM25F candidate filter in isolated in-memory fixtures: 150 tests and 931 assertions passed with zero failures or errors. Source review accepted routing and the initial candidate gate, then identified two search follow-ups: native single-attribute filtering and current payloads for authorized BM25F hits.

The expanded reproduction failed 13 assertions before those fixes: ten across native fulltext document/section cases and three for cached BM25F payloads and facets. After the fixes, a focused search/privacy and owned-content regression run passed 85 tests and 707 assertions with zero failures or errors. Independent review of these follow-ups remains pending. These are separate isolated results, not proof of live deployment or readiness of an existing tree whose enrollment preconditions have not been checked.

Binary public/private visibility is the supported starting point. Additional label tiers and broader automation require their own semantics and verification. Neither manual setup nor the client installer relaxes the export, identity, or restore contracts.

## Tool-name migration

Use the underscore wire names advertised by `tools/list`, such as `sandbar_entity_find`. Dotted names have a compatibility window but are not advertised. Clients and tool-card consumers should migrate before that window closes. Do not infer an operation's raw wire name from a host's normalized programming identifier.

## Retrieval and validation need bounded interpretations

BM25F removes candidates unreadable by the bound principal using current-store authority before scoring, snippets, totals, facets and limiting. Authorized payloads, snippets and scalar facets use the current entity from the same database value as the read decision. Native single-attribute search also filters unreadable document and owned-content candidates before totals and limiting, and projects from its authorization snapshot. The isolated regressions cover hidden-hit suppression and current BM25F payloads after a visibility/content change without cache refresh.

BM25F's analyzed terms, frequencies and class statistics remain cached; matching and scores can lag content changes, and shared statistics can still depend on unreadable records. Aggregate/list/count disclosure remains a separate open boundary. These search repairs do not establish complete information isolation, deployment readiness or broad private-account acceptance. See the [search contract](concepts/fulltext-search.md#compose-a-question-without-changing-its-meaning).

Tag lookup can return several entities sharing a label or alias. Resolve the intended entity by its identity and metadata before following edges. Its current `match-total` can double-count overlap between exact and text matches outside a limited result page; use explicit distinct entity sets for evaluation denominators.

Shapes currently target their declared classes directly. Inherited applicability, some datatype/custom-callback checks and type-relation cache invalidation after dynamic model changes remain incomplete. Strict mode prevents effects from checks that report a violation; it does not add checks that the walker does not implement. See [shapes](concepts/shape-validation.md) and [the metamodel](concepts/metamodel.md) for the supported cases.

## Workflow and scheduler effects need independent checks

Workflow history, accepted state and callback effects are not one atomic transition. Concurrent or stale calls can accept incompatible steps, and a logged callback failure need not prevent state advancement. Scheduler overlap and timeout policies do not guarantee that old external work has stopped. Use sequential process calls and independently checked effects for now; [workflows](concepts/workflow-substrate.md) and [scheduling](concepts/temporal-substrate.md) describe these repairable limits.

## Remaining implementation debt

Some interior workflow/job/config readers still use the Clojure reader. Treat those inputs as trusted configuration or system-authored data; the restrictions on wire query input do not make an interior reader safe for arbitrary external text.

Some audit emitters carry actor information in a structured description rather than the typed actor slot. Consumers should use the emitter's actual contract instead of assuming all events expose the same relationship.

These boundaries complement the precise contracts in the [API references](../README.md#documentation-map). Repairs to the named gaps remain release work; documenting a supported procedure does not mark an unimplemented guarantee complete.
