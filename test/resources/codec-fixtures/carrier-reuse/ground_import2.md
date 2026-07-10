---
name: Ground new concepts with `mcp__sandbar__sandbar_ground` at concept-introduction boundaries (`mem ground` is the FS-fallback)
description: When introducing a new predicate, type, ADR section, named pattern, or other novel concept into the corpus, run `mcp__sandbar__sandbar_ground` (concept) first — the Stage-7.D grounding verb surfaces tag-vocabulary prior-art + meta-vocabulary name-conflicts (classes / predicates matching the concept) + suggested-next routing; pair it with a `mcp__sandbar__sandbar_search_bm25f` sweep for textual prior-art until Stage 7.F folds BM25F into the verb (the `mem ground` FS-fallback still runs the original 4-axis type-scoped BM25F sweep in one shot) — catching name-conflicts and prior-art before the new artifact is committed. The discipline is concept-introduction-boundary-shaped: routine search-while-orienting uses `mcp__sandbar__sandbar_search_bm25f`; concept-introduction calls for `sandbar_ground` because the cost of a name-conflict or duplicated abstraction compounds across the corpus's typed-edge graph.
type: feedback
scope: global
introduced-in: "0.7.2"
tags: [interaction, mem-cli, ground, bm25f, concept-introduction, name-conflict-prevention, dogfooding, orientation, authoritative-over-approximate]
created: 2026-05-07
last-reviewed: 2026-07-02
related:
  - decisions/memory_ground_skill_2026_05_07.md
  - decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
  - observations/grounding_is_compositional_mcp_workflow_thin_client_2026_05_20.md
  - interaction/use_mem_search_as_primary_orientation_tool.md
  - interaction/lean_into_hook_surfaced_discipline.md
  - patterns/architectural/ai_collaboration/authoritative_over_approximate.md
  - protocol/session_orientation_protocol.md
at-startup: keystone
one-line: "Ground new concepts with sandbar_ground (MCP-primary) at concept-introduction boundaries"
---

<!--
NOTE: The link to decisions/memory_ground_skill_2026_05_07.md is
expressed as `:related:` (SKOS fallback) rather than a typed
edge because no current predicate's range admits the
"discipline-memory-operationalizes-ADR" relationship.  The
semantically-correct predicate would be something like
`operationalizes:` (guidance → decision) — flagged for the
parked typed-edge cleanup task
(tasks/2026-05-07_17-33-44-resolve-9-typed-edge-predicate-audit-err.md).
Once that task introduces / widens the appropriate predicate,
this `:related:` should be promoted to the typed edge.
-->


When about to introduce a new concept into the corpus —
**predicate, type, ADR section, named pattern, named axis,
new architectural component name** — run `mcp__sandbar__sandbar_ground`
(concept) *before* authoring.

The live verb (Stage 7.D MVP) composes three grounding steps —
tag-vocabulary lookup (`step-1-tag-lookup`), meta-vocabulary
discovery (`step-2-meta-vocab` — classes + predicates whose
names align with the concept; the name-conflict check), and
suggested-next routing (`step-3-suggested-next`).  Full BM25F +
path-grammar integration follows in Stage 7.F; until then, pair
`sandbar_ground` with a `sandbar_search_bm25f` sweep for
textual prior-art.  (The `mem ground` FS-fallback still runs
the original 4-axis type-scoped BM25F sweep — decisions+plans /
libraries+references / predicates / feedback+interaction — in
one shot.)  Catching these *before* the new
artifact lands is cheap; catching them *after* via audit or
mid-stage retrofit is expensive (renames cascade through
typed-edge graph).

**When to ground vs. search:**

- `sandbar_search_bm25f` is for *orientation* — surveying what
  exists, picking up next steps, finding examples; routine
  read-side discovery.
- `sandbar_ground` is for *introduction* — concept-
  introduction-boundary work, where the cost of a missed
  prior-art or name-conflict will compound across the typed-
  edge graph.

The two are complementary, not redundant.  Ground is
vocabulary-plus-meta-vocabulary examination (tags / classes /
predicates — plus the FS-fallback's cross-axis aggregation)
tuned for the "am I about to author
something that conflicts with something already authored?"
question.

**Concrete trigger boundaries** — run `sandbar_ground` when:

- Naming a new typed-edge predicate
- Authoring a new memory type (`types/<name>.md`)
- Introducing a named axis in an ADR (Stage A architectural
  appraisal § sections)
- Naming a new pattern under `patterns/`
- Naming a new architectural component (cache file, hook
  script, audit invariant) — even if the component itself
  isn't a memory artifact, its name lives in the corpus
- Introducing a new tier / category / status enum value

**Why:** The Library Card pattern arc Stage A failed exactly
here — the `applies-in:` predicate was authored as new but
already existed with opposite-direction semantics, requiring
mid-stage rename + cascading typed-edge fixes.  `sandbar_ground`
is the structural mechanization of the discipline
that catches this pre-flight.

**How to apply:**

- Default to running `mcp__sandbar__sandbar_ground` (concept) as the first
  move when starting concept-introduction work
- Treat any `step-2-meta-vocab` hit (classes-matching /
  predicates-matching) as a name-conflict signal — read the
  matched class / predicate before authoring
  (name-conflict-detour prevention is the cheapest defense)
- Treat `step-1-tag-lookup` matches as vocabulary prior-art;
  `gap? true` on a genuinely new concept suggests a
  `sandbar_tag_define` follow-up (the tag vocabulary is still
  data-starved, so absence-of-tag alone is weak evidence of
  novelty)
- Until Stage 7.F folds BM25F into the verb, pair ground with
  a `sandbar_search_bm25f` sweep so textual prior-art is not
  missed
- If both come back empty, the concept is plausibly novel —
  proceed but cite the absence in the new artifact's
  frontmatter so retrospective audits can spot-check
- FS-fallback (`mem ground <concept>`) retains the original
  4-axis output shape — there, treat 2+/4 cross-axis hits as
  strong prior-art signal, even 1/4 hits in the predicate axis
  as worth checking, and `Strongest: 0` as plausibly-novel

The complementary search-side discipline lives at
[`interaction/use_mem_search_as_primary_orientation_tool.md`](use_mem_search_as_primary_orientation_tool.md).
The ADR motivating the original (pre-cutover) `mem ground` skill lives at
[`decisions/memory_ground_skill_2026_05_07.md`](../decisions/memory_ground_skill_2026_05_07.md);
the MCP verb's semantics are locked by
[`decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md`](../decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md)
(compositional grounding per
[`observations/grounding_is_compositional_mcp_workflow_thin_client_2026_05_20.md`](../observations/grounding_is_compositional_mcp_workflow_thin_client_2026_05_20.md)).

## See also

- [`decisions/memory_ground_skill_2026_05_07.md`](../decisions/memory_ground_skill_2026_05_07.md)
  — the architectural decision establishing the skill (this
  discipline memory operationalizes that decision as ongoing
  discipline; SKOS-fallback `:related:` until a typed
  predicate exists)
- [`interaction/use_mem_search_as_primary_orientation_tool.md`](use_mem_search_as_primary_orientation_tool.md)
  — the orientation-side sibling discipline
- [`interaction/lean_into_hook_surfaced_discipline.md`](lean_into_hook_surfaced_discipline.md)
  — apply-by-default disposition when the
  `inject_feedback_recall` hook surfaces a ground-applicable
  rule
- [`patterns/architectural/ai_collaboration/authoritative_over_approximate.md`](../patterns/architectural/ai_collaboration/authoritative_over_approximate.md)
  — parent pattern (use authoritative tooling over grep-style
  search; ground IS the authoritative tooling for concept-
  introduction)
- [`protocol/session_orientation_protocol.md`](../protocol/session_orientation_protocol.md)
  — its §6.3 prescribes the same ground-at-concept-introduction
  step (via the ADR, not this file; its wording is still
  slash-command-era pending that protocol's own C8 rewrite)
