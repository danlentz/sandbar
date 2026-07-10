---
name: Pre-Action Memory Protocol
description: Consult memory BEFORE significant actions, not after — a set of hard filters applied at decision points, each expressed as a named tool invocation so the check is mechanical rather than discretionary (post-MCP-cutover the `mcp__sandbar__*` substrate verbs are the primary surface for the corpus-retrieval/authoring checks; the `mem` CLI invocations named in the body are the FS-fallback, and remain canonical for FS-native ops like arc / validate / audit / index)
type: protocol
scope: global
tags: [protocol]
created: 2026-04-30
last-reviewed: 2026-07-02
atomicity-exempt: true
atomicity-exempt-rationale: "14 numbered decision classes (§§0-13) × one-paragraph-each is already atomic-by-section; the protocol IS the consult-this-before-action list and splitting by section would mean the reader has to traverse 14 files to load the same check-list.  Each section is an already-atomic entry within a coherent outer frame (when-to-check + how-to-apply + why-it-matters).  The long §See also trailer is the navigable bottom of the protocol, not accretion."
related:
  - protocol/self_audit_cadence.md
  - protocol/learn_behavior_protocol.md
  - protocol/memory_system_evolution.md
  - protocol/audit_artifact_protocol.md
  - protocol/plan_hierarchy_protocol.md
  - protocol/memory_system_versioning_protocol.md
  - patterns/architectural/ai_collaboration/authoritative_over_approximate.md
at-startup: keystone
one-line: "Consult memory before significant actions; hard filters apply at decision points"
---

The recurring failure mode I diagnosed in the /btw retrospective:
*"Memory ends up describing what I wish I did, not governing what I
actually do."* The fix is to make memory consultative **before**
significant actions, as pre-action filters, not as post-hoc audits.

**Each check below names the tool that makes it mechanical.**
*Don't* just *"go read a memory file"* — the tooling is the
authoritative primitive; indexed search beats grep-the-memory-dir,
a structured entity view beats cat-the-frontmatter. See
[Authoritative Over Approximate](../patterns/architectural/ai_collaboration/authoritative_over_approximate.md).

> **NOTE (post-MCP-cutover, 2026-05-23 posture):** the checks below
> were written in the pre-cutover `mem`-CLI era; the decision
> classes and cited memories remain current, and the highest-traffic
> invocations are updated in place.  Read every remaining
> corpus-retrieval invocation through the substrate-primary
> mapping:
>
> - `mem search <q>` → `mcp__sandbar__sandbar_search_bm25f`
>   (`:class`-scoped); `mem search` is the FS-fallback.
> - `mem show <rel-path>` → `mcp__sandbar__sandbar_entity_find-by-rel-path`
>   (+ `sandbar_navigate_inbound-edges` for the inbound-link list);
>   `mem show` is the FS-fallback.
> - New / edited memories → `mcp__sandbar__sandbar_entity_create` /
>   `sandbar_entity_update` (`mem new` and raw FS writes to
>   `memory/**/*.md` are both ask-gated by
>   `block_fs_authoring_of_memory_corpus`); `mem new` is the
>   FS-fallback scaffold.
> - FS-native ops stay `mem`-CLI-canonical: `mem arc *`,
>   `mem validate`, `mem audit`, `mem index --check`/`--check-coverage`,
>   `mem audit-report`, `mem regen`, `mem version`.

## The protocol

Before each of these actions, pause and run the named tool.
Name the check out loud (terse) so Dan has a visible signal the
discipline is being applied.

### 0. At session start, and when arc context is unclear

**Run:** `mem arc forest` — shows the active arc hierarchy with
status + blockers + staleness markers.

**Supplementary:**

- `mem arc ready` — arcs ready to work on (unblocked + non-
  terminal + ordered by last-touched oldest-first).
- `mem arc stale --days 30` — arcs not touched recently.
- `mem arc show <slug>` — node detail + children + blockers.

**Why:** the forest is the durable navigation surface for
in-flight work.  Without loading it at session start, every
session rediscovers the hierarchy manually.  Once loaded, the
structure persists in conversation context and subsequent
decisions benefit.

**When to re-run within a session:** whenever uncertainty
surfaces about "what was I working on?" / "where does this
fit?" / "is there a parent arc I should be citing?"  Cheap to
invoke; high leverage.

Related: [`protocol/plan_hierarchy_protocol.md`](plan_hierarchy_protocol.md)
— the discipline for maintaining the arc forest (setting
`parent:`, touching on advancement, transitioning statuses).

### 1. Before proposing a commit

**Run:**
- `mem search commit` — finds the commit-discipline memories.
- `mem show git/no_commit_without_auth.md` — the authorization rule.

**Check the diff for:**
- TODO markers of any kind
- Hex literals outside the token primitive tier
- AI-authored signatures
- Mixed-concern commits that should be split

If any of those are present, resolve or escalate **before**
proposing the commit. Don't propose and explain-away.

### 2. Before typing any TODO comment

**Run:** `mem show git/no_todo_markers.md`. Three options —
resolve, consolidate, or escalate. "Inline with a marker" is not a
fourth option.

The moment my fingers are about to type `TODO-` is the signal to
stop.

### 3. Before making a wiring claim about code

Examples: *"X isn't called anywhere"*, *"this rename is safe"*,
*"only these three files reference Y."*

**Run:** `lein xref` (or the project's xref invocation; for repos
registered as code-meta-types, `mem xref`) + query.
Rebuild xref if the database is stale from prior edits.

**Consult:** `mem show testing/xref_for_verification.md` and
`mem show testing/xref_every_iteration.md`.

Query authoritatively — grep is approximate for code. (For
*memory*-wiring questions — *"which patterns cite kernel-surface
separation?"* — use `mcp__sandbar__sandbar_navigate_inbound-edges`
(MCP-primary; `mem show <path>` is the FS-fallback) instead; both
report inbound edges broken out by kind.)

### 4. Before a second tactical guess in a debug loop

**Run:** `mem show interaction/diagnostic_loop_discipline.md` and
`mem show interaction/strategic_over_tactical.md`.

After 2–3 iterations of fix-verify-still-broken, the bug is not
where I think it is, OR the feedback mechanism is inadequate.
Improve the mechanism before another tactic.

### 5. Before invoking a platform facility

Logging, scope-capture, xref, test-execution, page-level enable
conventions.

**Run:** `mem search <facility-name>` — finds the reference
memory. Then `mem show references/<matching>.md` for the full
convention.

Use the convention. Don't invent a console hack or a `log-always`
or a direct `lein test`.

### 6. Before an architectural decision

Examples: adding mutable state, changing a shared invariant,
adopting a new tool, choosing between alternatives.

**Run:**
- `mem show interaction/ask_at_design_decisions.md`
- `mem show interaction/no_race_highest_quality.md`

Frame the tradeoff, stop, ask Dan. Don't silently pick.

### 7. Before "done" declarations

**Run:** `mem show interaction/review_completed_work.md`.

Summarize what changed, flag decisions and tradeoffs, note
verification status. Don't wait to be asked.

### 8. Before proposing a new arc or large multi-commit effort

**Run:**
- `mem show patterns/process/plan_doc_shape.md`
- `mem show patterns/process/stage_plan_with_checkpoints.md`

Write the plan doc (`plans/<ticket>-<topic>.md`) in the 8-section
template. Propose the stage breakdown. Get plan approval *before*
writing code.

### 9. Before creating a new primitive, namespace, or component

**Run:**
- `mem show patterns/architectural/layering/topical_namespaces.md`
- `mem show patterns/architectural/layering/escape_hatches_over_inheritance.md`
- For re-frame projects only:
  `mem show patterns/architectural/layering/subscription_typed_primitives.md`

Does it belong in an existing topical namespace or earn a split?
Does its API design a tight common path with narrow escape
hatches, not a deep config surface? (If re-frame: does it take
subscriptions as `:*foo` props and dispatch vectors as events?)

### 10. Before claiming a refactor is "done"

**Run:**
- `mem show patterns/process/pixel_identical_refactor.md`
- `mem show patterns/process/retirement_discipline.md`

Is the UI pixel-identical to pre-refactor state (if that's the
intent)? Are deliberate drifts called out in the commit message?
If this refactor retires a helper, is the retirement in this
commit or explicitly deferred?

### 11. Before editing the memory database itself

Adding a memory, renaming one, restructuring a subdirectory,
editing cross-references — all special-case because they can
damage the integrity of the memory system.

**Run:**
- **Before adding a new memory:** `mcp__sandbar__sandbar_entity_create`
  with an explicit `:db/ident` + rel-path (MCP-primary post-cutover;
  the reactive sink writes the FS file back).  FS-fallback:
  `mem new <type> <rel-path>` — scaffold from the appropriate
  template (`mem new` and raw FS writes to `memory/**/*.md` are
  both ask-gated by `block_fs_authoring_of_memory_corpus`).
  Either way, don't hand-write the frontmatter freehand; the
  template / schema ensures required fields, correct type value,
  and ISO dates.
- **Before adding a memory of unfamiliar type:** `mem show
  types/<type>.md` — read what the type requires (canonical
  section structure, required frontmatter, pitfalls).
- **Before renaming or moving a memory:**
  `mcp__sandbar__sandbar_navigate_inbound-edges` (MCP-primary) or
  `mem search <old-name>` + `mem show <old-path>` (FS-fallback) to
  enumerate every citation that needs updating. The inbound-edge
  list is authoritative.
- **Before committing any memory edit:** `mem validate` (schema +
  link compliance), `mem audit` (meta-invariants — gated on NEW
  violations per the next bullet), `mem index --check-coverage`
  (MEMORY.md coverage; the strict `--check` applies only when
  regenerating the full index — MEMORY.md is hand-curated, per
  [Self-Audit Cadence](self_audit_cadence.md), the governing
  cadence). `mem validate` and the index check must pass.
- **If `mem audit` flags new violations introduced by the edit:**
  resolve or explicitly defer with rationale in the commit
  message.

See [Self-Audit Cadence](self_audit_cadence.md) for the full
discipline around when to run the audit tools.

### 12. Before writing output-formatting code

Before typing `format`, `printf`, `println` of a padded string,
or hand-drawn ASCII-table borders — **pause and consult the
clj-format reference**.

**Run:** `mem show references/clj_format_dsl_reference.md`

**Check:**

- Is this a project where `clj-format` is a declared dependency?
  (Every Dan-ecosystem project is. Check `deps.edn` / `bb.edn`
  if uncertain.)
- Is the output **structured** — columns, tables, aligned
  numbers, multi-line reports with headers? If yes, use
  clj-format, not `format`.
- Is there a project-level output-library (`etc/lib/report.clj`
  or equivalent)? Use it. If it doesn't cover the shape,
  **extend the helper** — don't inline format code in the
  script.
- Is this a single-token println? `(println "Done.")` is fine
  without DSL ceremony.

**Why this check exists (2026-05-02 feedback from Dan):**
tactical `format` calls each pass the local smell test but
compound into unmaintainable one-off spaghetti. The
[use-clj-format-not-format feedback](../interaction/use_clj_format_not_format.md)
and [reinventing-formatting anti-pattern](../anti-patterns/reinventing_formatting.md)
exist because this specific trigger was missing from the
protocol.

### 13. Before responding to any message that names a decision, finding, or correction

**Run:** scan the incoming user message for state-change signals
BEFORE composing a response. Consult
[`protocol/state_change_detection.md`](state_change_detection.md)
for the signal taxonomy and capture-shape mapping.

**Signal classes to scan for** (see the state-change-detection
protocol for full phrasing examples):

- Plan decisions (*"let's pause X"*, *"I'll work on Y next"*)
- Authorization changes (*"you can stop asking me every time"*)
- Observation-worthy findings (*"huh, that's the second time"*)
- Question-worthy confusions (*"I'm not sure what X means"*)
- Feedback / corrections (*"don't do that"*, *"yes exactly"*)
- Forward-state changes (*"I'll come back later"*, *"blocked on X"*)
- Recognition of reusable output (*"that was useful"*)
- Cross-cutting pattern noticed (*"this is the same shape as..."*)

**When a signal fires:** capture first (write the memory), THEN
compose the response referencing the captured artifact by path
where natural. The capture is the NEXT action, not a trailing
question.

**When nothing fires:** proceed normally. Most messages don't
fire anything; the discipline is ordering, not ceremony.

**Why this check exists (2026-05-04 trigger-discipline plan):**
the existing §§1-12 fire on discrete actions (before a commit,
before a claim, before a design decision). State-changes embedded
in conversational messages don't match any action; they were
being caught only when Dan prompted *"would that have been useful
to note?"*. The
[state-change-detection protocol](state_change_detection.md)
and the [plan memory](../plans/trigger_discipline_improvements.md)
together name the signals and the discipline; this §13 is the
enforcement hook in the pre-action sequence.

## How to apply

- **Pre-action, not post-action.** The discipline is a filter at
  decision time, not an audit after the fact.
- **Name the tool invocation.** When a check fires, name the
  invocation briefly — substrate verb or `mem` command: *"running
  `sandbar_search_bm25f` on commit discipline before proposing"*.
  Visible discipline builds trust that the rule landed.
- **Not performative.** Only name a consultation when it actually
  changed my behavior. Verbose narration of every check is noise.
- **Extend the protocol as new memories land.** Each new
  feedback, pattern, or reference memory earns a spot in this
  list if it applies to a decision class. Keep this file current.
- **When a check's tool doesn't yet exist, name the gap.** If a
  decision class has no substrate verb or `mem` primitive that
  supports it (today: "which patterns should I consider before
  writing code?" — nothing beats BM25F search at that yet), flag
  the gap as future tooling work rather than falling back to grep
  silently.

## Why this matters

From my own /btw self-assessment: *"Each time I repeat a lesson
you've already taught me, the compound value of the collaboration
drops. Fixing that is probably the highest-leverage improvement
available."*

Dan's follow-up: *"I'll push back as much as needed so we
accomplish our goals."*

The pre-action protocol exists so Dan doesn't have to push back on
the same issue twice. Each rule added here represents a past push
he shouldn't have to repeat.

The **tool-invocation form** of the protocol exists so that
consulting memory is as cheap as consulting it *wants* to be.
`sandbar_search_bm25f` / `mem search` is faster than grep;
`sandbar_entity_find-by-rel-path` / `mem show` is faster than
reading raw frontmatter. If the discipline still feels expensive,
the tool is probably missing — not the discipline failing.

## See also

**Companion protocols:**

- [Self-Audit Cadence](self_audit_cadence.md) — when to run `mem
  validate` / `mem audit` / `mem index --check`
- [Learn Behavior Protocol](learn_behavior_protocol.md) — when Dan
  says *"learn behavior: X"*
- [Memory System Evolution Protocol](memory_system_evolution.md)
  — how to compound value on the tooling + corpus itself
  (audit-first, design-before-execution, tiered proposals)
- [Audit-Artifact Protocol](audit_artifact_protocol.md) — the
  EDN-artifact convention; §7 "done" declarations should call
  `mem audit-report` for regression check, not just `mem audit`

**Governing patterns:**

- [Authoritative Over Approximate](../patterns/architectural/ai_collaboration/authoritative_over_approximate.md)
  — why every check names an authoritative tool invocation
- [Authored Assistant Protocol](../patterns/architectural/ai_collaboration/authored_assistant_protocol.md)
  — the three-artifact shape this protocol embodies

**Memories consulted by the numbered decision classes:**

- §0 (session start / arc context): [Plan Hierarchy Protocol](plan_hierarchy_protocol.md),
  [Memory System Versioning Protocol](memory_system_versioning_protocol.md),
  [Claude Context Architecture Reference](../references/claude_context_architecture_reference.md)
  — run `mem arc forest` + `mem version` at session start.
- §1 (commits): [Never Commit Without Authorization](../git/no_commit_without_auth.md),
  [Semantic Commits](../git/semantic_commits.md),
  [Don't Ship TODO Markers](../git/no_todo_markers.md),
  [No Commit Signing](../git/no_commit_signing.md),
  [Scoped Git Push](../git/scoped_git_push.md),
  [Dependency Comment Hygiene](../git/dep_comment_hygiene.md)
- §2 (TODO): [Don't Ship TODO Markers](../git/no_todo_markers.md)
- §3 (wiring claims): [Use clj-xref Before Grep](../testing/xref_for_verification.md),
  [Rebuild xref Every Iteration](../testing/xref_every_iteration.md)
- §4 (debug loops): [Diagnostic Loop Discipline](../interaction/diagnostic_loop_discipline.md),
  [Strategic Over Tactical](../interaction/strategic_over_tactical.md),
  [Relay Blocked Commands](../interaction/relay_blocked_commands.md)
  *(when a shell command fails from sandbox, relay rather than
  hunt for a workaround)*
- §5 (platform facilities): [Scoped Per-Module Logging](../references/reference_logging_convention.md),
  [Diagnostic Triad](../references/decision_diagnostic_triad.md)
- §6 (design decisions): [Ask at Design Decision Points](../interaction/ask_at_design_decisions.md),
  [No Race, Highest Quality](../interaction/no_race_highest_quality.md)
- §7 ("done" declarations): [Review Completed Work](../interaction/review_completed_work.md)
- §8 (new arcs): [Plan Doc Shape](../patterns/process/plan_doc_shape.md),
  [Stage Plan with Checkpoints](../patterns/process/stage_plan_with_checkpoints.md)
- §9 (new primitives): [Topical Namespaces Over Monolithic](../patterns/architectural/layering/topical_namespaces.md),
  [Escape Hatches Over Inheritance](../patterns/architectural/layering/escape_hatches_over_inheritance.md),
  [Subscription-Typed Primitives](../patterns/architectural/layering/subscription_typed_primitives.md)
  *(re-frame projects only)*
- §10 (refactor "done"): [Pixel-Identical Refactor](../patterns/process/pixel_identical_refactor.md),
  [Retirement Discipline](../patterns/process/retirement_discipline.md)
- §11 (memory edits): [Self-Audit Cadence](self_audit_cadence.md)
- §12 (output-formatting code): [clj-format DSL Reference](../references/clj_format_dsl_reference.md),
  [Reinventing Formatting (anti-pattern)](../anti-patterns/reinventing_formatting.md),
  [Use clj-format, not format (feedback)](../interaction/use_clj_format_not_format.md)
- §13 (state-change signals in messages): [State-Change Detection Protocol](state_change_detection.md),
  [Observation Capture authorization](../authorizations/observation_capture.md),
  [Trigger-Discipline Improvements plan](../plans/trigger_discipline_improvements.md)
