---
name: tag-governance
description: Substrate-level tag for the tag-vocabulary-lifecycle discipline — covers memorials about how tags are defined, deprecated, consolidated, split, renamed, aligned with external vocabularies, or otherwise governed.
type: tag
value: tag-governance
definition: The discipline of managing tag-vocabulary lifecycle — define / deprecate / consolidate / split / rename / align / harmonize.  Embodied operationally in the `sandbar.tag.*` MCP verbs and audited by `sandbar.audit.tag/audit-all` over 7 invariants.
scope-note: Use for memorials about tag-vocabulary lifecycle policy, audit invariants, or migration phases (M.1-M.5).  NOT for individual tag definitions (those are tag memorials themselves, not memorials ABOUT tag-governance).
example: An ADR that introduces a new audit invariant (e.g., `:tag-namespace-pollution`) would carry `tag-governance`.
vocabulary-level: substrate-level
lifecycle-status: active
canonical?: true
introduced-in: 2026-05-20
in-scheme: memory-system-meta-vocabulary
related:
  - controlled-vocabulary
  - tag-modeling-arc
---
