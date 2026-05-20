---
name: controlled-vocabulary
description: Substrate-level tag for the canonical-vocabulary concept — covers memorials about controlled term sets (taxonomies, thesauri, ontologies, SKOS concept schemes, IPTC NewsCodes, DCAT themes, etc.) as opposed to free-text folksonomies.
type: tag
value: controlled-vocabulary
definition: A canonical term set with explicit boundaries — every term is defined (canonical-form + scope-note); membership decisions are explicit; lifecycle (proposed / active / deprecated / superseded) is tracked.  Contrasts with folksonomy / free-text-tag patterns where no canonical-form exists.
scope-note: Use for memorials introducing or discussing controlled vocabularies and the discipline of authoring them.  NOT for individual vocabulary memorials themselves (e.g., a SKOS thesaurus memorial uses its own canonical tags).
example: A library study of SKOS or ISO 25964 would carry `controlled-vocabulary`.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
related:
  - tag-governance
  - predicate
exact-match:
  - http://www.w3.org/2004/02/skos/core#ConceptScheme
---
