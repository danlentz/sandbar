# The firewall and `:mm/Project` — multi-project memory with directional isolation

> The 0.2.0 centerpiece, in one document: what `:mm/Project` and
> `:mm/Context` are, how visibility composes, what the directional
> firewall permits and refuses, and where the deliberate 0.2.0
> completeness boundary lies.  This is the operator's mental model —
> for the mechanical verb surface see [`doc/api/mcp-verbs.md`](api/mcp-verbs.md);
> for the riding exceptions see [`doc/known-gaps-0.2.0.md`](known-gaps-0.2.0.md).

## Why this exists

Sandbar's memory model becomes usable for real work — often **private**
work — in 0.2.0: each project gets its own corpus, projected to its own
git repo, with provenance, while a shared public corpus (the
disciplines, patterns, and libraries every project wants) stays
co-loaded and citable.  The risk that design creates is obvious: one
substrate serving several trust domains is one substrate that can leak
across them.  The directional firewall is the answer — a deterministic,
physical flow control that makes "private stays private" a property of
the data, enforced at the write and traversal boundaries, rather than a
discipline the AI client is trusted to observe.

Two commitments shape everything below:

- **The firewall is deterministic and physical; the LLM is untrusted
  in the enforcement path.**  Every decision is a pure predicate over
  *labels* — slot values on the entities at each end of an edge.  No
  model judgement, no content inference, no per-caller trust.  (What a
  deterministic edge-checker cannot do — detect a *paraphrase* of
  private content written into public prose — remains a named
  disciplinary residual, not a mechanical guarantee.  See
  [the boundary](#what-020-deliberately-defers-the-completeness-boundary).)
- **Composition only narrows.**  Wherever two confidentiality signals
  meet, the composed result is the *most restrictive* of them.  Nothing
  becomes public by combination, absence, or reinterpretation.

## The three classes

### `:mm/Context` — the compartment

A `:mm/Context` names a trust environment where sessions run — a
machine, a workstation posture, an engagement boundary.  It is the
**compartment coordinate** of the whole system: the unit of co-loading.
Two slots matter here:

- **`:mm.context/firewall-class`** — the context's regime designation.
  Current values: `:none`, `:proprietary-tokens-advisory`,
  `:project-isolated`, and `:public-bottom`.  Every value maps to
  `:private` sensitivity except the explicit `:public-bottom`
  designation; an unknown or absent class reads `:private`
  (fail-closed).  New values are additive data, never a schema change.
- **`:mm.context/visible-projects`** — the compartment's membership
  authority: which `:mm/Project` entities co-load in this context.
  This lives *in the corpus* — git-tracked, PR-reviewable — not in a
  per-machine registry file.  A project present only in some local
  registry but absent from `visible-projects` is not a member.

### `:mm/Project` — the routing tie

A `:mm/Project` is deliberately thin: the tie between a codebase, the
context(s) it runs in, and the corpus repo its memories project to.
Ten `:mm.project/*` slots in three groups:

| Group | Slots | Role |
|---|---|---|
| Identity | `ident`, `authority-uuid` | Stable rename-proof routing key (`:db.unique/identity`); per-project ID root |
| Tie | `runs-in-context` (card-many), `code-repo`, `corpus-repo` | Which compartment(s) it runs in; which git repos carry its code and its corpus |
| Firewall | `default-visibility`, `firewall-class`, `code-ref-public?`, `push-allowlist`, `corpus-layout` | The project's confidentiality posture and projection policy |

A memory joins a project through **`:mm.memory/owning-project`** — the
routing anchor.  Note the division of labor among the memory-side
slots: `:mm.memory/scope` answers *where does this route*
(`:global` / `:project`); `owning-project` names *which specific
project*; `:mm.memory/visibility` answers *how confidential is this
row* — three different questions, three slots.

### The sentinels — fail-closed by construction

`:project/UNASSIGNED` and `:context/UNASSIGNED` are schema-seeded
sentinel entities.  A memory with no resolvable `owning-project`
resolves to the UNASSIGNED project (whose `default-visibility` is
`:private`); a source that composes no real compartment gets the
UNASSIGNED context singleton.  The consequence is the system's
foundational floor: **an unassigned or unresolvable row is private and
compartment-isolated until someone says otherwise.**  The entire
pre-multi-project corpus keeps working under this floor — everything
resolves `:private` via UNASSIGNED, refusing across every real
compartment, until it is deliberately assigned.

## The visibility lattice and most-restrictive composition

Sensitivity in 0.2.0 is a two-point lattice — `:private` below
`:public` — and every composition takes the **meet** (the
most-restrictive element): `compose(xs) = :private` if *any* axis is
`:private`, else `:public`.  Never the join; a union-of-scopes operator
would *widen*, which is the exact leak-by-composition footgun the
design refuses.  Tiers beyond the binary are additive values reserved
for later releases.

A memory's **effective sensitivity** composes four axes
(`sandbar.firewall.label/effective-sensitivity`):

1. its intrinsic `:mm.memory/visibility` — **absent means
   inherit-from-project** (see the next section);
2. the owning-project's `:mm.project/default-visibility`
   (absent ⇒ `:private`);
3. the owning-project's `:mm.project/firewall-class`
   (only `:public-bottom` reads public);
4. the firewall-class of **every** context the owning-project
   `runs-in-context` (card-many: private in *any* context composes
   `:private`; an empty context set composes `:private`; an
   unresolvable member composes `:private`).

Axes 2–4 alone are the project-level form
(`project-effective-sensitivity`) — the same meet consumed everywhere a
project's own posture is needed, so there is exactly one label
resolver, not several that can drift.

The compartment coordinate rides along: a memory's `:contexts` set is
its owning-project's resolvable `runs-in-context` set — and when that
composes empty, it becomes the UNASSIGNED *singleton*, never the empty
set.  (An empty source-compartment set would make the flow predicate's
subset clause vacuously true and turn the fail-closed default into a
fail-open one; the singleton is what makes UNASSIGNED refuse.)

### Absent visibility inherits from the project (the blessed semantics)

A memory that carries **no** `:mm.memory/visibility` takes its
owning-project's effective sensitivity — a `:public`-composing
project's unmarked memories are public; a private project's unmarked
memories are private.  This *inherit-from-project* reading is the live,
Dan-blessed semantics (ruling B1, 2026-07-10).  An earlier draft of the
firewall predicate spec said absent-implies-private unconditionally;
that wording was **rejected** and is being amended wherever it still
appears — if you meet a spec or docstring that teaches
absent ⇒ private for the *label composition*, the spec text is stale,
not the code.

Two boundary notes keep this honest:

- The fail-closed floor is undisturbed: absent visibility on a row with
  no real project anchor still resolves `:private`, because the
  inherited project is UNASSIGNED.  Inheritance only ever *follows* an
  explicit project posture; it never conjures publicness from absence.
- The subscriber-facing notify/read delivery gate
  (`sandbar.mcp.clearance`) is a separate, also-fail-closed surface: it
  clears *principals* against compartments and defaults an absent
  visibility to `:private` for delivery decisions.  That per-principal
  gate and the principal-independent flow firewall answer different
  questions and both refuse when unsure.

## The directional flow predicate

The firewall governs **edges** — the ref-typed
intellectual-dependence/provenance slots (`cites`, `related`,
`refines`, `supersedes`/`superseded-by`, `informs`/`informed-by`,
`evidences`/`evidenced-by`, `documents`/`documented-by`,
`triggered-by`, `touches`, … plus `owning-project` itself), three
string rel-path carrier slots (`:mm.context/cites`,
`:mm.context/related`, `:mm.memory/introduced-in`), and the two
compartment-tie slots.  Structural, vocabulary, and actor edges
(`parent`, `has-part`, `tags`, `themes`, `created-by`, the codec
carriers, the metamodel edges) are exempt — a shared tag term is not an
intellectual dependence.  An edge is checked in the direction *written*
regardless of the predicate's semantic arrow, because the edge on the
source is the leak surface: writing `superseded-by` reveals the
target's existence and identity on the source just as `cites` does.

The point predicate (`sandbar.firewall.core/firewall-permits?`) is
pure — Label × Label → boolean:

```
PERMIT iff  target is :public
        or  (source is :private
             and source.contexts ⊆ target.contexts)
```

Which yields the charter's directional table:

| Edge | Verdict | Why |
|---|---|---|
| public → public | permit | lateral within the shared bottom |
| private → public | **permit** | *the point*: private work may cite the shared corpus |
| public → private | **refuse** | a public row must not depend on private material |
| private{H} → private{H} | permit | intra-compartment, across projects |
| private{A} → private{B} | **refuse** | the cross-compartment diamond |
| private{h} → private{h,w} | permit | target at least as visible as source |
| private{h,w} → private{h} | **refuse** | a wider-visible source may not depend on narrower material |
| UNASSIGNED → UNASSIGNED | permit | one private compartment — the pre-multi-project corpus keeps working |
| UNASSIGNED → any real private compartment | **refuse** | fail-closed until assigned |

The two compartment-tie slots (`:mm.project/runs-in-context`,
`:mm.context/visible-projects`) get a different predicate
(`tie-permits?`) — membership *definition* is not citation flow, and
running the flow predicate on the edges that define the label would
deadlock legitimate multi-context declarations.  The tie predicate
refuses exactly one shape: a **public compartment containing a private
member**, checked from whichever end carries the write.  That is the
catastrophic co-load fail-open — a private project listed by the public
bottom would co-load private rows into every session — refused at
author time.

Enforcement is bound at three points, all consuming the same core and
producing the same error shape (`:type :firewall-violation`, reason
`:flow-forbidden` or `:tie-forbidden`):

- **EP-1, author time** — `entity.create` / `entity.update` /
  batch ingest check every governed edge in the spec; a violation
  refuses the write.  String carriers are best-effort at EP-1
  (resolvable ⇒ checked; unresolvable ⇒ skip + WARN + audit record).
- **EP-3, traverse time** — edge listings and graph walks rewrite a
  forbidden hop as `{:blocked true}` (no target disclosed).  The
  path-grammar's endpoint-only Tier-2 operators use a coarser
  seed→endpoint label check until the per-hop evaluator covers them.
- **Principal-independent throughout** — no caller, token, or identity
  enters the decision; the same edge gets the same verdict for every
  principal.  (Per-principal *delivery* filtering is the separate
  clearance gate above.)

## The public bottom

The shared global corpus is made co-loadable by **designation**, not by
default: during Ceremony #8 (2026-07-08) the global corpus context
(`memory.contexts/unsandboxed-home-laptop`) was stamped
`:mm.context/firewall-class :public-bottom` in a live DB transaction.
Three properties of that stamp are worth internalizing:

1. **It arms the baseline without publishing anything.**  The stamp
   establishes the public co-load *root*; it makes no memorial public
   on its own, because every memorial's sensitivity still resolves
   through its owning-project — and the pre-existing corpus resolves
   `:private` via `:project/UNASSIGNED`.  Publicness is an explicit,
   per-project (or per-memory) posture, never a side effect.
2. **The tie check keys on the designation, not the composed
   sensitivity.**  A `:public-bottom` context that is itself masked
   `:mm.memory/visibility :private` composes `:private` sensitivity for
   flow purposes — but it is still the public co-load root, so the
   membership predicate reads the designation directly.  Otherwise the
   mask would defeat the one refusal the tie clause exists to make.
3. **Only `:public-bottom` is ever public.**  The firewall-class table
   is closed and fail-closed: every other value, and any unknown future
   value, reads `:private`.

## Graduation — private memory becoming public

The ruled direction (Dan, 2026-07-21): graduation is a **sanitized
copy**, not a move and not an edge.  A private memory that has earned a
public life is *copied* into the public corpus in sanitized form, and
the record tying the copy to its origin **must not leak**: nothing on
the public side may carry a private path, name, or project-revealing
string.  The linkage is either an opaque identifier resolvable only
inside the private store, or is stored on the private side only (the
original records "has public copy X"; the copy says nothing about
where it came from).

There is **no mechanized promotion path in 0.2.0**.  The firewall core
reserves an F7 declassification carve-out (an explicitly-declassified
public→private edge permission) — it is named so later stages know
where it will attach, but no clause consults it.  Promotion today is a
manual, human-judgement act, and the firewall constrains it like any
other write: the sanitized copy must stand on its own as a public row
(its governed edges checked as public-source edges), which is exactly
what forces the sanitization to be real.

For readers who knew the pre-firewall design vocabulary, the old
theses map onto shipped mechanisms like this: *graduation* (a memory
"earning" global status) = declassification by sanitized copy, above;
the old *`memory-local/` directory* = the per-project corpus repo
(`:mm.project/corpus-repo`); the old *symlinked shared subset* =
public-bottom co-load — the shared corpus is now composed into a
session by compartment membership, not by filesystem links.

## What 0.2.0 deliberately defers (the completeness boundary)

The multi-project build ships **full-depth on the enforcement spine,
deliberately thin on breadth** — the arc plan's G3 boundary.  Thinning
is breadth control only; Dan's 2026-07-10 A1 ruling explicitly rejected
the "spine now, projection later" reading, so the projection half (git
export, guarded restore, the dogfood project) is *in* 0.2.0.  What is
deferred:

- **Two stores, binary labels.**  0.2.0 operates the public bottom plus
  one private project store; sensitivity is the `:public`/`:private`
  binary.  N-store orchestration and additional tiers are additive
  later work.
- **One hand-provisioned private repo.**  Repo provisioning is manual
  in 0.2.0; there is no `project.create`-style provisioning verb.
- **Config-driven routing, not verb-driven.**  The active project binds
  from the launch environment (`SANDBAR_PROJECT` env / JVM prop / the
  project's own `.sandbar/config.edn`), fail-closed to UNASSIGNED.
  Standalone list/route MCP verbs (W1.K) arrive in 0.2.1.
- **Semantic, not byte-strict, round-trip** for the export/restore
  cycle; meta-class emitter coverage widens later.
- **A cold-start note, not a tested cold-start ceremony.**  The
  supported new-project path for 0.2.0 is the manual, memorialized
  bring-up (proof before automation — the dogfood produces the
  runbook); the setup tool is rebuilt from that proof afterward.

Two caveats ride with the boundary rather than inside it: the
**R3 disciplinary residual** (content-semantics confinement — the
firewall checks edges and labels, never prose, so a paraphrase of
private content is caught by discipline and audit, not mechanics) and
the **F7 reserved carve-out** (above).  The riding exceptions to the
enforcement story itself — including the CA-6 same-batch window and the
public-bottom-edit refusal — are individually documented in
[`doc/known-gaps-0.2.0.md`](known-gaps-0.2.0.md); read that ledger
before relying on an edge case.

## The carrier-degradation fidelity floor

One paragraph every operator projecting a corpus — especially a
*private* project's corpus — should read.  The DB→FS emit path
preserves unknown frontmatter keys through a best-effort extras
carrier, and its protection floor is deliberately narrow: exactly
**two** frontmatter keys are refuse-protected — `at-startup` and
`one-line`, the discipline-visibility registry pair
(`sandbar.projection/registry-critical-keys`, overridable via
`SANDBAR_REGISTRY_CRITICAL_KEYS`; setting it empty disables the fatal
tier entirely).  A write that would strip either is refused with
`:registry-strip-refusal` before any bytes change.  Every *other*
dropped key survives only best-effort: the drop is warn-logged
(`:REACTIVE/frontmatter-keys-dropped`) and the write proceeds — and an
unresolvable-carrier fall-through at the codec layer degrades
silently.  If specific frontmatter keys are load-bearing for your
project, either add them to the critical-keys set or treat the
warn-log as an alarm you actually watch.

## Verifying your scoping (operator quick-checks)

- **Which project is this session?**  The active binding resolves
  env → JVM prop → project config → `:project/UNASSIGNED`
  (`sandbar.project.activate/active-project-key`).  An un-activated
  session binds the private UNASSIGNED scope — never the public bottom.
- **Does my project route where I think?**  `route-of` for any memory
  or project yields `{:project-key :sensitivity :trust-scope :contexts
  :corpus-repo :routes-to-public?}`.  A private project must show
  `:routes-to-public? false` and its *own* corpus repo.
- **Is the compartment membership right?**  Check the context's
  `:mm.context/visible-projects` in the corpus (git-reviewable), not a
  local registry.
- **Do refusals fire?**  Author a deliberate public→private citation in
  a scratch entity and confirm the `:firewall-violation` /
  `flow-forbidden` refusal; walk to a private target from a public seed
  and confirm the `{:blocked true}` hop.
- **Watch the seams.**  WARN-level `:FIREWALL/skipped-unresolved-target`
  (best-effort carriers) and `:REACTIVE/frontmatter-keys-dropped`
  (carrier degradation) are the two log lines that mean "the best-effort
  tier is active here."

## See also

- [`doc/known-gaps-0.2.0.md`](known-gaps-0.2.0.md) — the riding-exceptions ledger (read before relying on edge cases)
- [`doc/concepts/memory-model.md`](concepts/memory-model.md) — the `mm/*` layer the firewall governs
- [`doc/concepts/mcp-protocol.md`](concepts/mcp-protocol.md) — the verb surface enforcement rides on
- [`doc/auth.md`](auth.md) — service accounts and the token model (authn; the firewall is orthogonal to and independent of the caller)
- `src/sandbar/firewall/{core,label,enforce}.clj` — the pure core, the label resolver, the binding sites (the docstrings are the spec)
- `src/sandbar/project/{route,activate}.clj` — routing + per-session activation
