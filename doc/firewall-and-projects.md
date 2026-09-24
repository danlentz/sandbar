# Projects and directional information flow

**Shared knowledge is useful when information can move in permitted directions without silently widening its audience.** Sandbar models project ownership and trust contexts, then uses those labels to check governed relationships. Caller permissions, read disclosure, and export destinations are related boundaries with their own enforcement points.

## Context, project, and memory

| Entity or field | Responsibility |
| --- | --- |
| `:mm/Context` | A compartment in which projects may be visible together |
| `:mm/Project` | A routing and ownership tie between code, knowledge, contexts, and identity authority |
| `:mm.memory/owning-project` | The specific project that owns a memory |
| `:mm.memory/scope` | Routing category, such as global or project |
| `:mm.memory/visibility` | The memory's confidentiality designation |

Project declarations include a stable `:mm.project/ident`, an authority UUID, code and corpus repository references, context membership, and disclosure-related metadata. A readable project name is not its stable identity, and a relative document path is not sufficient to distinguish two projects.

Context membership is model data. Deployment must also bind that logical route to the intended physical directories, service configuration, and credentials. The route resolver alone does not provision separate database processes or establish an operating-system boundary.

The directional rule permits a private repository and its private memories to use public knowledge. Public repositories and public memories may interlink; public memories must not refer to private ones. Choosing a private project therefore does not create a separate public knowledge universe. Repository-local AI use has been demonstrated through manual MCP enrollment and three fresh Codex sessions, and a thin client installer now binds a repository checkout to an already-enrolled Project ([Use Sandbar from a project repository](https://github.com/danlentz/claude/blob/master/doc/project-onboarding.md)). The installer verifies the Project's identity and declared privacy and writes the client's local MCP binding; it does not enroll the Project, select or change its publication posture, grant a destination, or provision a restricted account. Those remain operator work, described in this chapter and in [operations](operations.md#install-or-change-a-project-destination).

## Enroll a project and declare its privacy

Enrollment is two records written through the ordinary creation path, in order, with every value explicit. Inspect the live schema first (`sandbar_class_describe` on `:mm/Context` and `:mm/Project`). The table describes manual enrollment of a **public** project using the current schema. The September 2026 rehearsal seeded these records before the service started, then demonstrated AI capture, edit and reopen against the retained synthetic store. It did not reconstruct these metadata records from files.

| Record | Values and why they are explicit |
| --- | --- |
| Context | `dt/type :mm/Context`; `mm.context/context-type :context`; `mm.memory/visibility :public`; `mm.context/firewall-class :public-bottom`. The compartment the project will be visible in. |
| Project | `dt/type :mm/Project`; `mm.memory/memory-type :project`; the stable `mm.project/ident` (for example `:project/clj-figlet`); `mm.memory/visibility` and `mm.project/default-visibility :public`; `mm.project/firewall-class :public-bottom`; `mm.project/runs-in-context` naming the Context; a portable `mm.project/corpus-repo` handle such as `clj-figlet-memory`. Set `mm.project/code-ref-public? true` only when public references to that project are authorized. The absolute destination belongs in the operator's `:project-roots` configuration. |
| Membership | After the Project exists, set the Context's `mm.context/visible-projects` to the Project's document ident. Membership is model data that defines labels; it is written last because it refers to both records. |

Use strict validation and check its error envelope before proceeding. Live Project shapes reject absolute paths and parent traversal in `corpus-repo`; the public-reference shape also requires explicit consent for any populated public-project handle, including a relative handle. The initial synthetic enrollment rehearsal did not establish this strict-shape contract. The live FIGlet enrollment on 21 September 2026 passed after using the portable handle and its authorized public-reference setting. Optional `mm.project/code-repo` is a reference to a `mm/Codebase` record, so a filesystem path is not a valid value.

A private project uses the private designations instead (`:private` visibility and default visibility; a firewall class such as `:project-isolated` for its own context). Supply each class's effective `type:` discriminator explicitly: Context uses `mm.context/context-type :context`, while Project uses `mm.memory/memory-type :project`. Setting only the inherited `mm.memory/memory-type` on a Context emits `memory-type: context`, which does not select the Context class on import. Without a usable `type:` field, import falls back to generic Memory. The corrected Context value follows the current codec declaration; a reconstruction round trip has not been demonstrated.

The Project's **document ident** (`:memory.projects/clj_figlet`, derived from its path) and its **stable key** (`:project/clj-figlet`, the value of `mm.project/ident`) are different identities with different jobs: the document ident names the record, the stable key survives renames and a database rebuild and is what configuration refers to.

Privacy declarations feed separate checks. Directional write checks compose memory visibility with project and context posture. Document read clearance currently uses the memory's own `mm.memory/visibility` and owning project, with absent visibility treated as private; it does not inherit the composed project label. Set the intended visibility as well as the owner on each capture. Changing the Project's posture does not tighten read clearance for existing memories that still explicitly say public.

These declarations do not bind an AI client's requests to the project: a client supplies `mm.memory/owning-project` on each capture after reading the enrolled record in full and checking its class, stable key and declared privacy (see [getting started](guides/getting-started.md#working-from-a-project-repository)). The client installer records that binding locally (`.sandbar/config.edn` carries the Project's stable key, document ident, UUID and the declared `--privacy`, which must equal the Project's default visibility) and instructs the client to full-read the Project before capture; each capture still names its owner and intended visibility explicitly. A separate operator-owned `:project-roots` map grants the physical destination; `corpus-repo` remains descriptive metadata (see [operations](operations.md#install-or-change-a-project-destination)). Changing a visibility label does not move or publish files. With mappings enabled, an ordinary owner or path edit that would move an existing file refuses and requires maintenance. A fully cleared account reading the store proves nothing about what a public destination may contain. The public-project AI rehearsal and the installer runs used such an account, so they do not establish private-principal enrollment or public-output refusal behavior; `--privacy private` verifies a declaration and neither provisions a restricted account nor proves private-account isolation.

## Resolve the label conservatively

The directional flow core receives resolved labels with sensitivity, context IDs, and project identity. Memory sensitivity combines its own visibility, the project's default visibility and firewall class, and all of the project's contexts. The most restrictive participating designation wins.

Absent memory visibility inherits the project posture. An absent or unresolved project does not create public data: the resolver uses `:project/UNASSIGNED` and `:context/UNASSIGNED`, whose effective posture is private. Missing, empty, or unresolved context membership must not accidentally become a universally visible empty context set.

Only the explicit public designation produces the shared public case. A project participating in a private context remains effectively private even when another part of its metadata says public. Caller-clearance predicates are separate from this principal-independent label calculation; do not assume that their fallback rules are interchangeable.

## Check the direction written

For a governed edge from source to target, the core permits flow when the target is public, or when the source is private and every source context is also a target context.

| Source | Target | Result |
| --- | --- | --- |
| Public | Public | Permit |
| Private | Public | Permit |
| Public | Private | Refuse |
| Private in A | Private in A | Permit |
| Private in A | Private in B | Refuse when the context sets are disjoint |
| Private in A | Private in A and B | Permit |
| Private in A and B | Private in A | Refuse |

The edge itself can disclose the target's existence. An inverse-named predicate such as `superseded-by` is therefore checked in the direction in which it is stored, not by interpreting its English name.

The core also has a separate membership rule: a public context cannot contain a private project. Membership defines labels; applying the citation rule recursively to the membership declaration would be circular.

## Governed relationships and coverage

The explicit census covers intellectual-dependence and provenance references such as `cites`, `related`, `refines`, `supersedes`, and `owning-project`; certain string reference carriers; and project/context membership ties. Structural, vocabulary, actor, and codec-carrier relationships have explicit exemptions with different obligations.

A newly introduced reference slot needs a deliberate census disposition. The current unknown-slot posture is visible warning/audit rather than universal refusal. String carriers can be unresolved at author time and require later closure checks. These are reasons to test the complete model and publication path, rather than infer that one pure predicate covers every possible disclosure.

Ordinary prose, paraphrases, filenames, external URLs, and copies into other applications are not all typed graph edges. The graph policy does not detect arbitrary semantic disclosure in text. Review of publication content remains necessary.

## Four distinct enforcement questions

| Question | Boundary |
| --- | --- |
| May this caller perform this operation? | Authentication and action authorization |
| May this source record refer to that target? | Directional flow checks on governed relationships |
| May this caller receive this entity or its metadata? | Read and subscription clearance |
| May this content enter this destination? | Export selection, disclosure policy, and filesystem containment |

A read-only credential restricts actions; it does not by itself define what confidential data a caller may read. A tool allowlist controls one client's exposure, not all server entry points. A refused resource read must not become an allowed body or metadata read through another adapter with the same authority.

The current operation gate shares its read-only and unscoped-principal decision between MCP and REST. Read adapters also apply entity visibility and remove credential values from projections. These mechanisms are narrower than complete project-scoped access: `:auth/full-clearance?` is available for a deliberately trusted operator, but the principal's per-project clearance set is not yet in the schema. Do not enroll mutually restricted clients into a shared store on the assumption that project labels supply that missing access configuration.

The confidentiality compartment check applies to Memory descendants and their document-owned content. A section (`:mm/Section`) inherits authority by following its actual parent chain to the root memory; a frontmatter carrier (`:mm/Frontmatter`) uses its unique owning memory. Copied labels and path-derived names do not grant access. Missing, cyclic, wrong-kind or ambiguous ownership refuses authenticated reads, including full-clearance reads. Subscription and notification checks use the same ownership rule while retaining their public-only policy for a missing principal. The metamodel, tag vocabulary and other entities outside this model use the namespace policy; they are not automatically partitioned by a memory's owning project.

Before restricted accounts use a store containing private material, verify that the deployed build includes this ownership-aware read decision. Older builds applied the compartment check only to Memory descendants and could disclose private document text through section reads. The isolated adapter tests establish the repair's behavior; they do not verify an older running service or supply the missing per-project credential provisioning.

For a deployed access claim, test full content, names, references, counts, nested projections, notifications and errors across the actual adapters. Service-account credential fields require safe projections independently of whether the operation mutates data. See [authentication](auth.md) and [known release boundaries](known-gaps-0.2.0.md).

## Export and deliberate publication

The guarded MCP exporter requires an explicitly enrolled source project and an operator-authorized destination. It checks the caller, effective labels and supported emitted references before creating staging output. Private may reuse public; public references cannot reveal private targets. A document with an unsafe or unresolved carrier is held whole, with its original preserved and exact reasons written to a private audit. The output manifest discloses only the accepted set and safe dependency metadata, plus hold counts and an opaque audit reference.

This bounded path supplies whole-document withholding, not automatic field redaction or semantic declassification. Free prose and code remain author-classified. Preview and execution use a stopped-writer procedure and fresh staging; failures can retain partial files and must not be published. Review the full accepted set before release. The [operator procedure](operations.md#export-into-a-staging-destination) describes the configuration, plan token, audit and completion marker.

The manifest binds files to a database basis, but a git checkpoint layer and guarded restore remain unbuilt. Per-project persistence roots alone do not apply this export filter: pointing a canonical root at a public repository does not make all its files safe to publish. [Known gaps](known-gaps-0.2.0.md#provisioning-and-routing-remain-explicit) preserves the remaining limits.

Moving private knowledge into a public project is a deliberate publication decision. The current graph core has no general declassification escape hatch. Prepare a suitable public representation and review its references, identity and destination before publishing it. A visibility edit alone is not proof that all inherited labels and output paths permit release.

Bulk import is supported as maintenance into the existing store, with all writers stopped before canonical edits and through the final audit. Compare the proposed files with accepted database state before importing; there is no general automatic merge or historical-authority oracle. The [operations guide](operations.md) gives the procedure, and the [projection chapter](concepts/projection.md) explains the representation contract.

## Verify the deployed boundary

Use synthetic projects with the same relative document path and distinct identities. Exercise public, project A, project B, unassigned, and fully cleared operator cases through the actual exposed adapters. Check both expected permissions and useful requests that should remain allowed. Repeat at the chosen release revision and deployment topology.

Source: [`firewall core`](../src/sandbar/firewall/core.clj), [`label resolution`](../src/sandbar/firewall/label.clj), [`enforcement`](../src/sandbar/firewall/enforce.clj), [`project routing`](../src/sandbar/project/route.clj), and [`principal clearance`](../src/sandbar/mcp/clearance.clj).
