# Projection

> Sandbar's bidirectional projection primitive between database state and a filesystem hierarchy of native-format files.  The `project-graph` + `ingest-graph` operations are the verb-form pair; this `sandbar.projection` namespace is their home.  Borrows the shape (and the function names) from James Anderson's `de.setf.rdf:project-graph` — the boundary-layer primitive lives between the high-level generic consumer (filesystem tools; humans editing in their preferred text editor) and the low-level database concern (Datomic transactions and queries).  The filesystem format is **canonical ground-truth**; the database is one of multiple stores that comply with it.

## Thesis

A Sandbar instance is not the database alone.  It is the *pair* — database state and filesystem hierarchy — held in coherence by `project-graph` (DB → FS) and `ingest-graph` (FS → DB).

The filesystem hierarchy is the canonical ground-truth representation.  Any backend complies with the filesystem format.  Today the production backend is Datomic Peer; tomorrow's may be Datomic Cloud, XTDB, raw JSON-on-disk, or something not yet built.  All are equivalent if they round-trip through the canonical FS hierarchy.

This is **not** an export/import feature.  Project-graph is the *boundary layer* — the level of abstraction at which translation between in-memory entities and on-disk files is the right concern.  The codec layer is one realization within this frame; project-graph operates at the granularity of entire collections rather than individual entities.

## Lineage

### James Anderson's `de.setf.rdf`

The pattern is Anderson's.  `de.setf.rdf` was a Common Lisp CLOS-metaclass RDF graph framework developed during the Datagraph / Dydra era.  Its `project-graph` operation took the raw triple state of an RDF graph and projected it into a *native-representation hierarchy* — filesystem layouts, rendered output, structured external storage.  `ingest-graph` did the inverse — accepting a hierarchy and re-deriving the triple state.

The discipline Anderson articulated was: **representation translation is a boundary concern**, not a model concern and not a consumer concern.  Put it between the generic high-level (what consumers see) and the low-level specific (what the database stores).  Make it bidirectional.  Make the FS form be a stable shape that consumers can edit with their existing tools (vim, git, find, grep) without knowing the model.

Sandbar adopts this wholesale, one layer up: instead of triples ↔ hierarchies, it is *typed entities* ↔ *hierarchies of codec-encoded files*.  The codec layer (see [`codec-layer.md`](codec-layer.md)) operates on individual entities; project-graph operates on directory trees of entity-files.

### Filesystem-canonical discipline

The filesystem-canonical commitment is a deliberate choice with two consequences:

1. **External tooling becomes immediately available.**  `git log memory/decisions/foo.md` works because the file is the canonical form.  `grep -r 'cancel-process'` works.  Editors open files; humans review diffs; CI/CD systems detect changes.  None of this would be true if the canonical form were a Datomic transaction log.

2. **The backend becomes pluggable.**  Sandbar's contract with the backend is: *project to this filesystem shape; ingest from this filesystem shape*.  A backend that round-trips faithfully through that shape is interchangeable.  Today's Datomic Peer is one such backend; tomorrow's alternatives are first-class possibilities.

This commitment is captured in [`interaction/filesystem_native_format_is_canonical_backend_compliance_required_hybrid_backend_experimentation_essential_2026_05_13`](../../memory/interaction/filesystem_native_format_is_canonical_backend_compliance_required_hybrid_backend_experimentation_essential_2026_05_13.md) as the standing directive.

## Operations

Two primitives in `sandbar.projection`:

```clojure
(project-graph db path opts)   ; DB state → filesystem hierarchy at `path`
(ingest-graph  conn path opts) ; filesystem hierarchy at `path` → DB transactions
```

The semantics:

### `project-graph`

- Walks all instances of declared classes (`:dt/Class` entities with a non-nil `:dt/native-codec`).
- For each entity, resolves the codec, calls `codec/emit codec entity`, and writes the result to a path derived from the entity's `:db/ident` and class.
- Maintains the directory hierarchy: classes occupy top-level subdirectories; entity files within them.
- Idempotent — projecting twice produces the same filesystem state for the same DB state.
- Filterable — see "Filtering primitives" below.

### `ingest-graph`

- Walks the filesystem hierarchy at `path`.
- For each file, infers the class from the directory hierarchy and the codec from the class's `:dt/native-codec`.
- Calls `codec/parse codec source` to obtain the entity map.
- Transacts the resulting entities — creating or updating as appropriate.
- Returns the transaction report.

The pair is the bidirectional projection.

## Chunk addressability and sibling-chain navigation

Inside an individual document (an `mm/Memory` markdown file, for example), the codec produces a *section tree* — `:mm/Section` entities, each addressable, each linked via sibling-chain navigation.

The sibling chain is a deliberate departure from RDFS `rdf:List` cons-cells.  Cons-cell lists (`:dt/first` / `:dt/rest`) are awkward for editing — inserting a section in the middle requires rewriting every subsequent cell.  Pairwise siblings (`:mm.section/previous-sibling` / `:mm.section/next-sibling`) are SIOC-flavored (Breslin & Decker 2007) and allow local mutation: inserting a section updates two pointers.

The trade-off: pairwise siblings cannot represent a list as a single first-class entity (there is no "list of sections" handle; only "first section, walk siblings"); but the editing ergonomics are dramatically better, and the address — `mcp://sandbar/mm/Memory/<rel-path>#<section-path>` — points to a stable entity rather than a moving cons-cell.

See [`decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13`](../../memory/decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md) for the decision discussion that landed the pairwise design.

## Filtering primitives

`project-graph` accepts a `:filter` option — a predicate-shaped map that constrains which entities project.  Today's filter forms (see `sandbar.projection/project-graph` docstring for the canonical list):

```clojure
{:classes #{:mm/Memory :decisions/Decision}}     ; only these classes
{:idents #{:decisions/foo :decisions/bar}}       ; only these specific entities
{:where  '[?e :foo/bar ?v] [(< ?v 100)]}         ; Datalog predicate
{:since  inst}                                   ; entities modified since
```

`ingest-graph` accepts the symmetric `:filter` — limiting which files ingest.

This filterability is the precondition for **hybrid filesystem/database topology** experimentation (see [`multi-store-architecture.md`](multi-store-architecture.md)).  Some classes may be FS-canonical with full DB mirror; others may be FS-canonical with DB index only; others may be DB-resident with on-demand FS materialization.  The partition is an empirical question — filtering lets us draw the line and measure.

## Why the boundary layer matters

Without project-graph, an FS-canonical commitment leaks model concerns into every consumer.  A consumer that wants "show me all my memories as files" has to query the database, materialize entities, render markdown, write to disk — every consumer reinvents the projection.  A consumer that wants to ingest a directory of edits has to walk the directory, parse each file, validate, transact — every consumer reinvents the ingestion.

Project-graph centralizes both into a single boundary-layer primitive.  Consumers get `(project-graph db path)` and `(ingest-graph conn path)`.  The discipline of "the FS is canonical" is enforceable because the projection is mechanical.

## Relationship to the codec layer

Codecs handle *one entity at a time* at the wire-format boundary.
Project-graph handles *collections of entities* at the filesystem boundary.

The two are layered:

- `project-graph` walks the DB, selects entities, and delegates per-entity emission to the codec layer.
- `ingest-graph` walks the filesystem, parses each file via the codec layer, and accumulates a transaction.

Codec selection is per-class via `:dt/native-codec`.  Project-graph does not own codec routing; it asks the codec mediator to handle it.

## Use cases

1. **Memory-corpus mirroring.**  The corpus's `memory/` tree is itself an `ingest-graph` target.  The Sandbar instance holds the canonical entity state; the filesystem holds the canonical user-editable form.  Edits in either flow through the projection.

2. **Backup / version control.**  `project-graph` to a clean directory, commit to git.  The diff is meaningful — each file is a self-contained, human-readable entity.  Backup restoration is `ingest-graph` from the directory.

3. **Multi-instance synchronization.**  Two Sandbar instances can synchronize through a shared filesystem projection.  Each does `project-graph` to a shared location and `ingest-graph` from it.  Conflict resolution is delegated to whatever owns the filesystem (typically git, with merge semantics suited to text files).

4. **External tooling.**  `find memory/decisions -name 'sandbar_*' -mtime -1` works.  `grep -r 'project-graph' memory/` works.  These are not custom-built features of Sandbar; they are consequences of the FS being canonical.

5. **Hybrid FS/DB experimentation.**  Use filters to partition which classes live primarily on disk versus primarily in the DB; measure performance and ergonomics; revisit the partition.  This experimentation is what filters were designed for.

## Comparison with adjacent patterns

### vs. database backup/restore

A backup is a serialization of the database for the purpose of reconstruction.  Project-graph is a *projection* — the filesystem form is itself canonical, not a derivative.  An ingest-graph from the projection produces an equivalent database state; the projection is not lossy by design.

### vs. ORMs with file-backed storage

ORMs with file-backed storage (CodeIgniter Files; Rails fixtures) treat each file as a record.  Project-graph treats each file as an *entity in the model* — typed, validated, hierarchically organized.  The shape is the metamodel's, not the storage backend's.

### vs. ipfs / merkle-graph projection

IPFS-style projections (Benet 2014; IPLD content-addressing) produce content-addressed graphs where the file location is derived from the content hash.  Project-graph produces *path-addressed* projections — locations are derived from the entity's ident and class.  The two are complementary: a content-addressed projection over the path-addressed form would be straightforward to add.

### vs. RDF graph serialization (Turtle / N-Triples)

An RDF serialization produces a single (often large) file containing the graph as triples.  Project-graph produces a *hierarchy* of files, each holding one entity in the consumer's native form.  The trade-off is locality: editing one entity in an RDF serialization requires understanding the whole file; editing one entity in a project-graph hierarchy requires understanding only that file.

## References

**Anderson's `de.setf.rdf` lineage**

- Anderson, J.M. (2008–). *de.setf.rdf — CLOS-metaclass RDF graph framework for Common Lisp.*  Datagraph / Dydra-era source; see project archives and Anderson's design notes on `project-graph` / `ingest-graph` as boundary-layer primitives.

**SIOC / pairwise siblings**

- Breslin, J.G. & Decker, S. (2007). *The SIOC Project — Semantically-Interlinked Online Communities.* Linking online community sites with RDF, including the `sioc:has_next_sibling` predicate that informed Sandbar's `:mm.section/next-sibling`.

**Filesystem-canonical / external-tool integration**

- Raymond, E.S. (1999). *The Art of Unix Programming.* Pearson Education.  Particularly the "rule of composition" and "rule of separation."

**IPFS / content addressing (for contrast)**

- Benet, J. (2014). *IPFS — Content Addressed, Versioned, P2P File System.*  https://ipfs.io/ipfs/QmR7GSQM93Cx5eAg6a6yRzNde1FQv7uL6X1o4k7zrJa3LX/ipfs.draft3.pdf

**Bidirectional projection / lenses**

- Foster, J.N., Greenwald, M.B., Moore, J.T., Pierce, B.C. & Schmitt, A. (2007). *Combinators for Bidirectional Tree Transformations: A Linguistic Approach to the View-Update Problem.* ACM TOPLAS 29(3).  Theoretical underpinning for bidirectional projections like project-graph / ingest-graph.

## See also

- [`metamodel.md`](metamodel.md) — the typed entities project-graph projects
- [`codec-layer.md`](codec-layer.md) — per-entity wire-format translation that project-graph delegates to
- [`markdown-as-canonical.md`](markdown-as-canonical.md) — why markdown is the Layer-1 corpus format
- [`multi-store-architecture.md`](multi-store-architecture.md) — hybrid FS/DB topology built on project-graph filtering
- [`doc/guides/sandbar-as-substrate.md`](../guides/sandbar-as-substrate.md) — using project-graph in your own application
