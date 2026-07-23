---
name: mm-class
description: Substrate-level tag for the meta-class concept — the rdfs:Class analog applied to consumer-domain classes (e.g., :mm/Memory, :mm/Tag, :mm/Section).  This tag covers memorials introducing, refining, or discussing class-level structure in the metamodel.
type: tag
value: mm-class
definition: A class in the metamodel — an instance of `:dt/Class` whose entities share a slot schema.  Subsumes both substrate-class memorials (e.g., `:mm/Section` introduction) and consumer-class memorials.
scope-note: Use for memorials introducing a new class, refining a class's slot schema, or discussing class-level architectural decisions.  NOT for property-level work (use `mm-property`) or meta-meta discussions (use `mm-type`).
example: An ADR introducing :mm/ThesaurusArray would carry `mm-class` because it's a class declaration.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
broader-generic:
  - mm-type
---

## See also

- `:dt/Class` — the meta-class type
- `mm-type` — broader meta-type
- `mm-property` — companion: property-level analog
