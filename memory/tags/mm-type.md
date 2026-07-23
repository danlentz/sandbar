---
name: mm-type
description: Substrate-level tag for the meta-type concept itself — the rdfs:Class analog at the highest level of the metamodel (a "type" is a kind of thing the substrate knows about; e.g., :dt/Class, :dt/Property, :dt/Resource, plus consumer extensions like :mm/Memory).  This tag covers memorials that discuss / introduce / refine the meta-type concept.
type: tag
value: mm-type
definition: The metamodel's meta-type — the rdfs:Class analog at the top of the metamodel hierarchy.  Instances are the named "kinds of things" the substrate models (`:dt/Class`, `:dt/Property`, `:dt/Resource`, plus consumer extensions like `:mm/Memory`, `:mm/Tag`, `:mm/Section`).
scope-note: Use for memorials discussing the meta-type concept itself — bootstrap design choices, metamodel evolution, type/class/property distinction.  NOT for ordinary class-introduction memorials (those use `mm-class` instead).
example: A decision memorial that introduces a new abstract meta-type to the substrate would carry this tag.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
---

## See also

- `:dt/Class` — the canonical class meta-type in the metamodel
- [`schema/meta.edn`](../../schema/meta.edn) — substrate metamodel declarations
- `mm-class`, `mm-property` — companion substrate-level tags
