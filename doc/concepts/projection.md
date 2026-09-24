# Projection

> Readable documents and queryable entities can carry the same knowledge. Projection makes the mapping explicit, including what it preserves and where another operation must take over.

## Thesis

A decision is useful both as a document someone can review and as an entity a program can find and connect to its evidence. Sandbar's projection layer connects these uses. It writes selected memory entities and their sections as Markdown files; ingestion parses files back into entity specifications.

The important contract is preservation. A reader should be able to tell which identity, text, metadata, and relationships survive this passage, which details are normalized, and which parts of a running system live outside the document representation.

## One decision, two useful views

Consider a fictional decision at `decisions/cache-refresh.md`. It records a cache-refresh policy, explains its rationale, and cites a measurement that informed it.

As a file, the decision has frontmatter and a readable body. An editor can open it, and a version-control diff can show a change to its reasoning. In Sandbar, its type identifies it as a decision, its metadata can be queried, and its citation is a relationship that a client can follow. Headings and their content can also be represented as section entities.

| Document concern | Entity concern |
| --- | --- |
| Name and description | Searchable metadata on the memory |
| A reference to the measurement | A typed citation relationship |
| A rationale heading and its text | Sections attached to their containing memory |
| The document's location | A relative path used by the projection hierarchy |
| Identity carried by the representation | A way to recognize the same knowledge after reconstruction |

The graph supports questions that a document view does not answer by itself: which decisions cite this measurement, or which sections belong to this decision? The file supports familiar reading and review. Keeping the mapping in one layer lets clients use both views without each inventing a serializer.

## The implemented boundary

The current `sandbar.projection` implementation works with memory entities and their sections. It emits Markdown documents. Its collection API is:

```clojure
;; entities is a collection of realized memory and section specifications.
(projection/project-graph entities {:to output-directory})

;; Parse documents into a flat collection of entity specifications.
(projection/ingest-graph input-directory)
```

Here `projection` is an alias for `sandbar.projection`. These expressions describe the boundary; a caller supplies the entities and directories.

For projection, the caller first selects and realizes the data, including the sections needed to reconstruct each body. `project-graph` groups the entities by their containing memory, derives a relative path, emits the document, and writes it beneath the requested directory. The default hierarchy uses `:mm.memory/rel-path`. Supported filters narrow the selected memories, with their associated sections carried along.

For ingestion, `ingest-graph` walks Markdown files and parses them into entity specifications. A directory walk skips configured basenames, including `README.md` and `MEMORY.md` by default. It can also parse a specified file directly. Validation, conflict handling, and database persistence belong to the caller that consumes those specifications.

This boundary matters when using the higher-level MCP operations: an export handler can fetch the entities before calling the projector, and an import handler can transact after parsing. Those surrounding steps have their own contracts. The [codec protocol reference](../api/codec-protocol.md) describes per-representation operations; the [Markdown concept](markdown-as-canonical.md) describes the document model.

## What a round trip establishes

For the supported document model, the intended preservation contract concerns the represented knowledge. Database-local entity IDs need not survive reconstruction. Normalized Markdown need not preserve every original byte. A location used to find a file also needs to be distinguished from the entity's durable identity: moving or renaming a document is an identity-sensitive operation, not merely a different spelling of the same path.

The current `projection/round-trip-test` writes a supplied collection to a temporary directory, ingests it, and compares normalized entity specifications. It removes `:db/id` before comparison and omits redundant `:mm.memory/body-raw` where a section tree supplies that body. A passing result therefore establishes this particular comparison for the supplied collection.

A useful fidelity check must cover the data the application actually uses: identity carriers, metadata, typed references, section structure, and supported frontmatter values. The contract must distinguish supported fields from extras carried on a best-effort basis, and state what happens when a carrier is unavailable. Fields essential to reconstruction need preservation or a visible failure. The [known boundaries](../known-gaps-0.2.0.md#documents-preserve-a-defined-model) describe the emitter's narrower configurable critical-key protection; arbitrary extra fields do not acquire a stronger guarantee merely by appearing in frontmatter.

The scope of the document representation is distinct from a complete service backup. Database history, credentials, and runtime state belong in the appropriate backup and recovery procedures. The [operator guide](../operations.md) distinguishes reconstruction of represented knowledge from recovery of the running service.

<a id="use-cases"></a>

## Files, commits, and current state

The release design uses a shared knowledge database and separate project document trees. A successfully projected tree preserves the supported document model, not every database fact. Import into the existing store for maintenance so established entity identities and incoming references remain intact. Reconstruction in an empty store needs its own identity and reference comparison; a readable export alone does not establish that it will work.

For changes accepted through the running database, file projection can occur afterward through the [reactive subsystem](reactive-substrate.md). There can be a period during which the database contains a committed change and a file still contains its earlier form. An operator needs evidence of projection completion before relying on the files as a current copy.

The default import replaces the source-owned representation of an existing document: supplied values replace previous values, omitted source-owned fields are removed, and sections and frontmatter carriers are reconciled. The host identity, incoming references and attributes owned by the store are retained. Class or durable-ID disagreement and references from outside the document to removed sections produce conflicts. Import reports each file's outcome; it does not run the interactive shape-validation path.

Basis and source-hash guards detect changes during an import attempt. They do not decide whether an unchanged file is already older than accepted database content. Stop all writers before editing canonical files, preserve before-images, review differences, and keep writers stopped through preview, import and audit. Hold ambiguous files outside the selected input. Follow the [maintenance procedure](../operations.md); there is no automatic merge or general newer-state restore guard.

Sharing a projected directory between two instances also requires a synchronization policy. File diffs help a person review changes; they do not establish which instance has accepted them or when an import is safe.

This distinction keeps the benefits of an editable representation while making freshness and recovery observable rather than assumed.

<a id="filtering-primitives"></a>

## Selection and disclosure

Selecting entities for projection is also a disclosure decision. A caller's filter expresses what is wanted. It is not a principal-clearance check or a destination disclosure policy.

The MCP project exporter plans from one immutable database value and selects only the explicitly enrolled project's memories. Caller clearance and the operator-authorized destination apply before staging output is created. All supported emitted references are checked, including author and structural references that can have different authoring-time rules. Unknown carriers, unsafe references, body/section disagreements and failed identity/content round trips hold the whole document; no field is silently redacted. The low-level `projection/project-graph` function remains a rendering primitive, not this guarded export contract.

Preview persists exact reasons in a private audit and returns counts and a plan token. Execution requires that token, fresh staging and the unchanged basis; the completion manifest is published after files, hash verification and the ready audit. Optional provenance still requires both per-call and server enablement. A failed export may leave partial staging for inspection. The supported operator procedure keeps other writers stopped throughout; it is not a concurrency lock or an automatic publication decision.

A complete accepted subset is not a database backup or a claim that all source documents were represented. Private details about holds remain audit-side. Free prose, code and external URLs retain the author's classification; automatic semantic declassification and field redaction are unbuilt. Review the files and manifest before publication, and retain historical ambiguities instead of guessing which version is authoritative.

The [project-boundary chapter](../firewall-and-projects.md) explains the policy, and the [operator guide](../operations.md) explains the export and restore procedures. Keeping selection, disclosure, provenance, and publication explicit makes the readable representation usable across appropriately scoped projects.

## Design lineage

James Anderson's [`de.setf.resource`](https://github.com/lisp/de.setf.resource) is a useful antecedent: it projects RDF repositories into CLOS object models through a mediator, and its [API](https://raw.githubusercontent.com/lisp/de.setf.resource/master/api.lisp) includes `de.setf.rdf:project-graph`. Sandbar uses a related separation between the model and its external representation. The Markdown layout, preservation rules, and synchronization behavior described here are Sandbar's own contracts.

## Where to go next

- [Markdown as canonical](markdown-as-canonical.md) explains the document representation and its preservation rules.
- [The codec layer](codec-layer.md) explains representation boundaries for individual values and entities.
- [Reactive substrate](reactive-substrate.md) explains propagation after accepted changes.
- [Sandbar as a substrate](../guides/sandbar-as-substrate.md) shows how an application uses these layers.
