---
name: decision
description: Substrate-level tag for the ADR (Architectural Decision Record) memorial type — memorials that capture a non-trivial decision in the canonical Context / Decision / Alternatives / Acceptance shape.  This tag covers DECISION-NESS as a memorial-type concept; specific decision memorials use `memory-type :decision`.
type: tag
value: decision
definition: An ADR-shaped memorial — captures Context (problem), Decision (chosen approach), Alternatives (rejected paths), Acceptance criteria, and See-also crosslinks.  Records non-trivial architectural / design decisions with the reasoning preserved.
scope-note: Use for memorials ABOUT the decision concept itself (e.g., "how should ADRs be structured?").  NOT for individual decisions — those memorials carry `memory-type :decision` in their frontmatter instead.
example: A memorial that introduces the ADR shape convention to the corpus would carry the `decision` tag.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
related:
  - reference
  - library
---
