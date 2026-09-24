# Storage: give each representation a clear responsibility

**Readable durability and efficient query access can coexist when ownership and synchronization are explicit.** Sandbar uses a typed database, document projections, and derived indexes for different purposes. The architecture is useful only if a caller can tell which state has been accepted, which copies are current, and what a recovery operation reconstructs.

## Three responsibilities

| Representation | Purpose | What must be established |
| --- | --- | --- |
| Project document trees | Readable, versionable knowledge | Supported fidelity, identity, destination policy, and successful projection |
| Datomic database | Accepted transactions, typed relationships, queryable state and history | Valid accepting boundaries, backups, schema compatibility, and recovery |
| Derived in-process state | Search analysis, type-relation caches, queued projection work | Completeness, invalidation, bounded lifecycle, and rebuilding |

These are responsibilities, not three interchangeable backends. Datomic Peer is the implemented database. A document codec does not by itself make another database a compatible replacement. Search analysis is already a derived representation; it need not wait for a hypothetical external vector store to make freshness an architectural concern.

## Durable knowledge and runtime state differ

A decision's rationale is worth retaining as readable knowledge. An in-flight subscriber, a verification cache entry, or a worker handle belongs to a running process. Some database-resident histories and executions may also be worth backing up even though they are not projected as ordinary prose.

The metamodel's `:dt/memorial-policy` helps express projection intent. `:first-class` identifies separately retained knowledge; `:db-only` identifies database retention; `:inline` describes an intended embedded representation where a consumer implements it. A policy value does not create its own sink or guarantee that all consumers implement the same behavior. For example, the logging path treats inline persistence as reserved.

Runtime event families are separate from the memory hierarchy. An event becoming useful narrative evidence is an explicit retention choice. Avoid automatically indexing and projecting high-volume telemetry as human-authored knowledge; doing so changes search populations, storage growth, and operational cost.

## Follow an accepted change

1. A mutation resolves identity and validates the proposed change at the accepting boundary.
2. The database commits accepted state.
3. Derived-state mechanisms refresh search and enqueue relevant projections.
4. A file sink writes the selected document to its approved destination.
5. A later publication or backup operation records what it actually captured.

Failure at one step must not be confused with another. A failed projection does not mean the database rejected the write. A drained queue does not prove the output contains the intended version. A file appearing on disk does not establish that it is approved for publication.

Search and filesystem projection currently have separate refresh mechanisms. Their completion and failure observations should be interpreted separately. See [reactive behavior](reactive-substrate.md) and [search](fulltext-search.md).

## Define recovery by outcome

Reconstructing represented documents in an empty database differs from restoring a database backup. Document reconstruction must preserve supported identities, references, metadata, and sections; it also needs complete reporting of rejected files. Database recovery includes the captured database's state and history under the backup mechanism's own contract.

For maintenance, import the reviewed subset into the existing store. Default source-owned replacement removes omitted source-owned attributes and sections while preserving the host identity, incoming references and store-owned attributes. The importer refuses specified identity/section conflicts and supports basis and source-hash guards. It does not determine that an otherwise unchanged input file is older than the target's accepted content.

Stop writers before editing canonical files, compare both versions, and retain quiescence through preview, import and audit. Exclude ambiguous paths and preserve both versions for later disposition. Native backup restoration and reconstruction in an empty database remain separate recovery procedures.

Run the [operator procedure](../operations.md) against a separate recovery target before relying on it. A readable export is valuable evidence, but it is not a complete recovery test.

## Projects and deployment topology

A project connects ownership, a document destination, identity authority, and trust contexts. A shared database with separate project document trees still needs disclosure checks on every exposed read and export path. Conversely, a logical project route does not provision separate transactors or operating-system credentials.

The [project-boundary chapter](../firewall-and-projects.md) describes labels and enforcement scope. Multi-writer synchronization, alternative database engines, and external specialized indexes are extension directions, not implied features of 0.2.0. Add one when a concrete workload justifies it and its acceptance contract is testable.
