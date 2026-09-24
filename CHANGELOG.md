# Changelog

This log describes user-visible changes. Unreleased entries describe work in the development line; a version string in an endpoint is not evidence that a package has been published.

## [0.2.0] — Unreleased

The development line expands Sandbar's use as a substrate for structured, revisable knowledge. The principal additions concern declared computation, document representation, provenance, and the operational contracts between clients and persisted state.

### Model and computation

- First-class function and rule metadata make declared behavior discoverable alongside classes and properties.
- Workflow definitions, process state and history represent application progress. The scheduler adds recurrence, schedules, jobs and run records.
- Temporal classes and relationships provide a vocabulary for instants, intervals and recorded temporal facts.
- Recursive type reasoning remains part of normal model introspection. Selected property-rule queries provide additional entailment without claiming full OWL conformance.

### Representation and project context

- Reactive projection connects accepted database changes to configured file sinks and notifications; mutation handlers separately maintain derived retrieval state.
- The Markdown codec carries known model fields, unknown frontmatter and section structure, with explicit semantic normalization boundaries.
- Project/context vocabulary, sensitivity composition and directional flow checks establish mechanisms for project-aware storage and disclosure.
- Stable document identity and relative-path handling support reconstruction and cross-representation references.

These mechanisms require integrated release verification. In particular, document interchange is distinct from native database recovery, and directional flow checks are distinct from principal-visible isolation across all interfaces. See [projection](doc/concepts/projection.md) and [firewall and projects](doc/firewall-and-projects.md).

### Client and operational behavior

- MCP exposes a stable operational tool catalog with schema discovery, typed retrieval and domain operations. Tool descriptions and composition metadata help clients choose useful calls.
- Resource reads label the representation actually returned, including Markdown descendants and EDN fallback, and reject a URI whose class does not match the entity.
- Service-account authentication, operation restrictions and read-query validation provide distinct boundaries for access.
- Citation, author, project and tag filters accept readable target identities in count, group-by and BM25F queries. Missing or unreadable explicit targets refuse consistently; aggregate source counts and variable joins retain the documented privacy limits.
- Derived-state maintenance, startup ordering and runtime retention have received reliability work. Release acceptance includes failure, concurrency and restart behavior as well as ordinary examples.
- Documentation is organized around concepts, task guides and references, with examples grounded in the implemented interfaces.

The repository's artifact coordinate remains a development snapshot until the release is cut. Verify the package/tag you intend to consume; do not infer a published `0.2.0` artifact from this section. See [development](doc/development.md) and [known scope boundaries](doc/known-gaps-0.2.0.md).

## [0.1.0] — 2026-05-15 — First public release

The first release established Sandbar's reflective class/property model over Datomic, recursive type queries, validation and entity operations, and its Clojure, HTTP and MCP interfaces. Retrieval combined BM25F search, structural aggregation, typed navigation and orientation. Markdown codecs and projection connected structured knowledge with human-readable documents.

For the full change history, consult the [repository commits](https://github.com/danlentz/sandbar/commits/).
