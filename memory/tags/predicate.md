---
name: predicate
description: Substrate-level tag for the typed-edge predicate concept — a property whose `:dt/range` is `:db.type/ref` (or a class), used to declare typed relationships between entities (e.g., `:cites`, `:composes-with`, `:supersedes`, `:broader-generic`).  Predicates form the typed-edge vocabulary of the corpus's reference graph.
type: tag
value: predicate
definition: A typed-edge property — a `:dt/Property` whose `:dt/range` is a ref-type, used to declare relationships between memorial entities.  Predicates carry semantic constraints: irreflexivity, symmetry, transitivity, inverse-pairing.
scope-note: Use for memorials introducing or refining typed-edge predicates.  Memorials about `:related:` fallback should NOT carry this tag (related is the SKOS-fallback when no stronger predicate fits — `predicate` is for the typed alternatives).
example: A memorial introducing `:supersedes`/`:superseded-by` as inverse-paired predicates would carry `predicate`.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
broader-generic:
  - mm-property
---

## See also

- `mm-property` — broader: predicates ARE properties
- `controlled-vocabulary` — companion concept
