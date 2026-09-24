# Markdown as a durable knowledge representation

**Knowledge should remain readable and reviewable outside the process that helps manage it.** Sandbar's document representation gives prose a durable home in ordinary files while exposing its metadata, sections, and relationships to typed queries.

This is the useful meaning of canonical: a supported document tree can carry the knowledge an application has chosen to preserve. It is a representation contract, not a claim that every part of a running service fits in Markdown or that every file is already synchronized with the database.

## One document carries text and structure

A synthetic decision can be written as:

```markdown
---
name: Refresh after accepted writes
type: decision
scope: project
description: Keep discovery aligned with accepted knowledge
---

# Rationale

Readers should be able to find a newly accepted decision.

## Evidence

Measure retrieval after a controlled write.
```

Frontmatter maps supported keys to model slots. The body remains prose. Document parsing can create a host memory and section entities with headings, bodies, parent links, and sibling links. The document's first-section reference and the sibling chains preserve the represented order.

These links make sections addressable within the graph. They are not an assertion that every Markdown renderer's URL fragment names the same entity. Use the server's returned identifiers and resource URIs; filesystem paths and browser anchors serve their own addressing conventions.

## The supported grammar is deliberate

The current frontmatter parser recognizes a flat, line-based convention: `key: value`, block lists, simple inline lists, booleans, and quoted scalar forms. It splits a scalar line at the first colon, allowing prose values with later colons. Slot declarations then guide coercion into keywords, instants, numbers, booleans, or references.

This convention is narrower than YAML 1.2. Nested mappings, general escape processing, multiline scalars, and arbitrary YAML features require an explicit supported grammar and tests. Similarly, section decomposition recognizes a bounded heading syntax and preserves body text; it does not construct a complete CommonMark syntax tree. The [CommonMark specification](https://spec.commonmark.org/) and [YAML specification](https://yaml.org/spec/1.2.2/) are useful external references, not conformance claims for this codec.

Use the declared subset for portable documents. Interactive Markdown creation rejects unknown or invalid frontmatter by default; its explicit opt-out carries unknown keys. Bulk import is lenient and reports frontmatter issues per file. Neither path makes unsupported YAML syntax portable. Inspect the parsed result and retain the original when migrating unfamiliar source.

Both Markdown modes refuse a document when a generated path identity, reference identity, or landed keyword value cannot round-trip through the EDN reader. An import reports the affected source file and offending field/value instead of persisting part of that document; other valid files can still import. Correct the source deliberately rather than stripping commentary into a guessed reference. This guard does not repair historical stored values.

Explicit MCP slot maps have a separate check before persistence: unreadable keyword values, explicit identities and reference identities are refused with the slot and supplied value named in the error. A refused update preserves the existing entity, including other fields supplied in that request. This covers direct slot arguments; it does not establish equivalent validation for every codec or internal database writer.

The same path conversion serves `sandbar.entity.find-by-rel-path`: an argument that would generate an unreadable keyword is an error, while a valid path with no visible match still returns the normal missing result.

## Preservation, normalization, and extras

| Concern | Representation contract |
| --- | --- |
| Declared metadata | Map through class aliases and typed slots |
| Body and section order | Preserve the supported document structure |
| Durable identity | Carry the supported identity value across reconstruction |
| Database-local entity IDs | Reassign in the target database |
| Relative location | Supply through collection context; distinguish moves from identity changes |
| Unknown metadata | Use an explicit extras carrier and its supported fidelity policy |
| Formatting | Normalize only under the codec's stated rules |

The extras mechanism retains otherwise unmapped frontmatter under a `:mm/Frontmatter` carrier. That is useful for extending a document without silently discarding a field, but parse, persistence, emission, and degraded-carrier behavior must all preserve the same contract. Configurable critical-key protection covers specified essential keys; it does not prove arbitrary metadata is safe under every failure.

Normalization includes line endings and controlled body whitespace. Code blocks, hard line breaks, quoting, Unicode text, lists, and repeated headings need representative tests. A normalized round trip is different from byte-for-byte archival preservation. Keep original files or a versioned backup when exact source bytes matter.

## Identity needs more than a path

Sandbar distinguishes a database entity ID, a symbolic `:db/ident`, and a durable `:mm/id`. Document paths and heading paths help derive or locate symbolic identities. They are sensitive to moves, renames, repeated headings, and project boundaries.

Applications should preserve the durable identity when moving a record and keep cross-project identity roots distinct. Two projects can both contain `decisions/cache-refresh.md`; the shared relative spelling must not merge their knowledge. A path convention alone is insufficient evidence of collision-free project reconstruction.

Pairwise sibling links make predecessor and successor navigation direct. Their usefulness does not depend on claiming that another linked-list representation would require rewriting every later node. The important contract is that the emitter and importer agree on structure, order, and section identity.

## Canonical does not mean instantly current

A running service may accept a database write and project its document afterward. Before treating files as a current durable copy, establish that projection completed successfully. For sectioned documents, check the realized section tree as well as the raw-body slot: the emitter can derive the document from sections, so changing a mirrored raw body alone does not establish that a reopened document will show the change.

Whole-body updates through `entity.update` reconcile the section tree atomically for Memory classes whose native body is `:mm.memory/body-raw`. The document keeps its identity and unrelated metadata; removing an externally referenced section refuses the edit. This contract requires a stable document ident for sectioned edits. Initial entity creation can still store a raw body without decomposing it, and classes with a different native body slot need their own supported edit path. Historical disagreements are not automatically merged or repaired by this update behavior.

Editing canonical files is a maintenance operation. Stop writers before the edits, preserve both file and database before-images, preview the selected input, import into the existing store, and audit before resuming. Default import replaces source-owned fields and section structure while preserving the host identity and incoming references; ambiguous identities or outside references to removed sections require disposition. Bulk import does not run the interactive shape checks, so validate the imported collection explicitly.

The [projection chapter](projection.md) describes that passage. [Storage responsibilities](multi-store-architecture.md) distinguishes durable documents, database state, and derived indexes. A complete service recovery also needs the [operator procedures](../operations.md); Markdown alone does not contain authentication state, every runtime record, or the database's transaction history.
