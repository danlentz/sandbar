---
name: mm-property
description: Substrate-level tag for the meta-property concept — the rdf:Property analog applied to attributes declared at the metamodel layer (e.g., :mm.memory/name, :mm.tag/value, :dt/subclass-of).  Covers memorials introducing, refining, or discussing property-level structure.
type: tag
value: mm-property
definition: A property in the metamodel — an instance of `:dt/Property` with declared `:dt/domain` (which class it applies to) + `:dt/range` (its value type, either a Datomic primitive or a class for refs).
scope-note: Use for memorials introducing a new property, refining a property's domain/range/cardinality, or discussing property-level architectural decisions.  NOT for class-level work (`mm-class`) or typed-edge predicate authoring (`predicate`).
example: An ADR adding `:mm.tag/codec-type-keyword` to the metamodel carries `mm-property` because it's a property declaration.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
broader-generic:
  - mm-type
related:
  - mm-class
  - predicate
---

## See also

- `:dt/Property` — the meta-property type
- `mm-class` — companion: class-level analog
- `predicate` — narrower: typed-edge subset of properties
