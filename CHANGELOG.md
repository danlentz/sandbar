# Changelog

All notable changes to Sandbar are documented in this file.  Format informed by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) at and after 0.1.0.

## [0.2.0] — unreleased (tag Dan-gated)

> **Version-narrative note (pending Dan's confirmation).**  The prior `[Unreleased]`
> section of this file narrated a **0.1.1** cut to Clojars — the "Memory-Model
> Co-Evolution arc" opened 2026-05-20 (`12a2a5c`) as `0.1.1-SNAPSHOT`, with the
> plan to bump `0.1.1-SNAPSHOT → 0.1.1` and deploy at arc close.  **That 0.1.1
> Clojars cut never happened.**  The arc instead grew well past a point release:
> a read-plane security firewall, a provenance/isolation centerpiece (`:mm/Project`
> schema + directional firewall), a reactive DB↔FS projection, the scheduler
> substrate, the workflow/session lifecycle, and an MCP hardening pass.  Per the
> 0.2.0 co-release plan of record (`memory/plans/sandbar_0_2_0_co_release_plan_of_record_2026_07_08.md`)
> the release is **renumbered 0.2.0**.  The only version bump landed so far is the
> **wire-visible** one — MCP `server-info.version` and the project-export catalog
> both read `0.2.0` (`bb4b5d6`) — while `project.clj` still carries
> `0.1.1-SNAPSHOT`.  The `project.clj` coordinate bump and the git tag are **both
> Dan-gated** (`memory/authorizations/dan_v0_2_0_tag_is_hard_dan_gated_do_not_tag_without_explicit_approval_other_tasks_precede_2026_07_08.md`)
> and are intentionally NOT part of this changelog change.  This heading and the
> "0.1.1 → 0.2.0" renumbering await Dan's version-narrative confirmation.

Everything in this section is traceable to the git history since `v0.1.0`
(`877c9e1..3ab31a8`, 233 commits) or to a dated landing record under
`memory/decisions/`.  Organized by theme, not commit order.

### The provenance + isolation firewall (release centerpiece)

The 0.2.0 headline: the memory model becomes usable for real, often **private**
projects — each with its own corpus projected to its own git repo, with
provenance and a proprietary-isolation firewall.

- **`:mm/Project` schema mint (S6)** — new `:mm/Project` class (parent `:mm/Artifact`,
  inheriting `name` / `description` / `scope` / `:mm/id`; 10 `:mm.project/*` slots),
  the `:project/UNASSIGNED` and `:context/UNASSIGNED` sentinels (default-visibility
  `:private`), and 4 `:mm/Shape` entities (project-layout-safety,
  no-absolute-path + no-absolute-path-codebase, repo-handle-url-safety).  Schema EDN
  landed at `b081096`; minted **live** during Ceremony #8 (additive schema loaded at
  restart), MCP-verified.  Per `memory/decisions/ceremony_8_COMPLETE_directional_firewall_live_project_schema_minted_public_bottom_stamped_2026_07_08.md`.
- **Directional read/write firewall (S7)** — deny-by-default cross-boundary flow
  control; a write that would move a public entity's reference toward a
  `:private`/unassigned target is refused (`flow-forbidden`).  Built + twice-hardened
  at `204c3f6`, remediated over a codex+opus+fable review round at `b72fd99`.
- **Public-bottom baseline** — the global corpus context
  (`memory.contexts/unsandboxed-home-laptop`) is stamped
  `:mm.context/firewall-class :public-bottom`, establishing the public co-load ROOT.
  This is a **live DB transaction** performed during Ceremony #8 (not a code commit);
  it arms the public baseline but makes NO corpus memorial public on its own
  (memorial sensitivity resolves via owning-project → `:project/UNASSIGNED` =
  `:private`).  Ceremony #8 completed on the second attempt via a
  stamp-before-enforcement re-order (zero code change); attempt 1's firewall-refused
  stamp and clean rollback are recorded in
  `memory/bugs/ceremony_8_public_bottom_stamp_blocked_by_live_firewall_bootstrapping_ordering_fail_closed_before_baseline_2026_07_08.md`.
- **W1 Phase-0 — traversal sanitizer + `:mm/id` covenant** (`7f3a788` → merged `3140634`).
  G2 rel-path traversal sanitizer in `reactive/sinks.clj` (survived a codex bypass
  corpus; the sole VULNERABLE-class TOCTOU adjudicated pre-existing → S9 lane) and D1
  `:mm/id` covenant read-back in `codec/markdown.clj`, with new `mm_id_covenant_test`
  + `sinks_g2_test` suites.  Condition **C2**: the emit identity-preference must flip
  to `:mm/id`-first (over legacy `:mm.memory/identity`) BEFORE any ζ-backfill or
  Tempo-C rebuild (byte-identical on base today; dual-slot cohort empty on live).
  Per `memory/decisions/w1_phase0_LANDED_live_3140634_...md`.
- **W1.ctx spine — most-restrictive label core** (`5757e55` → merged `1725cf5`, after a
  revise round).  ONE shared label core (`compose` / `effective-sensitivity` /
  `project-effective-sensitivity`, MOST-RESTRICTIVE per Dan's fork ruling), project
  routing (`route-of`, subclass-aware), per-project activation (`active-project-key`,
  env > prop > config precedence, DB fail-closed), and a CTX/P-COMPOSE battery (17
  tests / 569 assertions incl. two proven falsifiers).  Zero schema delta
  (`:projection` is a data value on a pre-existing open slot).  The W1.E provenance
  recorder was **severed** to held branch `arc/w1e-provenance-held-20260708` (out of
  spine scope).  Live-path delta = two leak-safe fail-closes (empty-context ⇒
  `:private`; unresolvable-member ⇒ `:private`), both verified no-ops on committed
  data.  Per `memory/decisions/w1_ctx_spine_LANDED_live_1725cf5_...md`.

### Security hardening (read-plane)

- **Read-plane query law + allowlist single-source (F5).**  The read-plane query
  surface is closed against parse-time reader-eval and against arbitrary Datalog
  predicate injection.  Landed incrementally: read-eval closed on `search :where`
  (`c7d836c`); the F1 global `*read-eval* false` backstop reverted after it broke
  fresh-start ns init (`6c2d679`); `sanitize-where` deny-by-default allowlist
  (`9f8f983`) with a contract + splice-site wiring battery (`b85a38b`); map/set-literal
  launder bypass closed + built-ins passlist + bare-preds + regex-drop (`c1884af`,
  `c55fad8`).  **Allowlist unification (It-6, `a1284f2` → merged `ca09994`):** one
  `safe-operator-vocabulary` (31 reviewed pure operators) becomes the single source;
  the lane **discovered a live 4th consumer** — the path-grammar `:TEST` compiler
  (`navigate/path/datomic.clj`) had a forked `test-fn-registry` splicing caller-named
  symbols into `d/q` since 2026-05-14, which the F5 design's "path plane is clean"
  premise had missed — now gated through the same vocabulary.  Accept sets
  byte-identical for every live consumer; one disclosed deny-more delta
  (off-vocabulary registration refused; zero production callers).  Per
  `memory/decisions/it6_LANDED_live_3ab31a8_...md`.
- **Read-plane namespace firewall (RPAF v1–v3.1).**  Deny-by-default class / attribute /
  entity / `:where` read-scope so a read-only MCP client cannot exfiltrate `:auth/*`
  credential values.  v1 entry guards (`6ef43b7`); v2 projection-layer OUTPUT scrub —
  sanitizes RETURNED entities + slots, closing traversal-output relocation, **credential
  VALUES closed here and stay closed** (`40707bf`); v3 central dispatch-boundary
  class-arg guard + registry-accept + multi-class fix (`a65c593`); v3.1 numeric-eid
  `:where` firewall + group-by result-key scrub (`639705e`).  Dan **accepted the 0.2.0
  logical firewall as the posture**, with physical auth-store separation as the durable
  post-0.2.0 close (`memory/decisions/dan_accepts_0_2_0_logical_readplane_firewall_..._2026_07_07.md`).
- **Read-only MCP token gate** — centralized `handle-call` deny-by-default for the
  read-only service-account posture (`ff176c2`, ceremony #4).
- **Reactive pre-write registry guard** — refuses frontmatter-key strips at the write
  boundary (`eeac6b4`, S2).

### Substrate features

- **Reactive DB→FS projection** — a `sandbar.reactive` hook at the `dt/*` substrate
  boundary with a three-layer opt-out (per-call > binding > class-skip-list) (`5cb7db6`);
  a bounded core.async queue with per-entity coalescing + a `sandbar.reactive.health`
  MCP verb (`dd020e3`); forward DB→FS projection live, closing the one-way-ingest gap on
  `entity.create :format :markdown` (`85cf2bd`).  A `:dt/memorial-policy` substrate
  primitive classifies each class `:first-class` / `:db-only` / `:inline` and the sink
  enforces it (`4e91d6a`, `39a4d3f`).  This is the mechanism CLAUDE.md calls the
  "reactive projection maintaining the FS↔DB bijection."
- **Workflow + session lifecycle (ι.3 / W4.1)** — a `sandbar.workflow.orchestrate`
  namespace with a canonical phase vocabulary, a `:mm.event/WorkflowTransition` event
  class hierarchy (`423f27a`), event emission wired into the dispatcher (`703382d`), the
  `sandbar.workflow.orchestrate` MCP verb (`7e5cd89`), `:phase-timeout` hard-fail
  detection (`ded4a67`), a 6-method `phase-work` multimethod (`f3ed495`…`3258e9d`), and
  end-to-end open+handoff integration tests (`837bf12`).  Server-side banner composition
  with lean MCP wire payloads (`32c973a`); session-lifecycle hardening P1–P5 (`499a6b9`).
  This is the substrate under `/memory-open` and `/memory-handoff`.
- **γ scheduler** — a `:mm.event/ScheduleEvent` family + `sandbar.schedule.state`
  (`f990dff`), RFC-5545 RRULE recurrence boundary (`8160025`), a native min-heap
  fire-thread (`7313031`), a `:mm.event/Scheduled` subscriber + Run lifecycle (`aa8d17e`),
  the `sandbar.schedule` public facade (`40b8a37`), 8 `sandbar.schedule.*` MCP verbs
  (`0582aed`), core-lifecycle wiring (`3d4dcf6`), and boot-time demo/system jobs
  (`f6ca7b8`, `0b7e15f`).
- **BM25F async indexing** — the BM25F refresh rides a dedicated single-thread executor
  (`entity-changed-async!` + `await-bm25f-quiescent!`, `095ee28`); MCP writes enqueue the
  refresh and reads await quiescence bounded (`9e894b2`); contract tests for the
  inline-free write path, flush hook, failure isolation, and read barrier (`54644f1`).
- **F6 — single-source verb catalog + CI drift gate** (`93a9b7f` → merged `7ddaf55`).
  One pure DB-free `catalog-model` is the single source; affordance-map + edges
  generators retarget onto it; a `catalog-check` gate fails CI on catalog↔code drift.
  82 verbs / 22 axes, wire parity green.  Gates every consolidation move
  (drift-gate-before-collapse).  Per
  `memory/decisions/f6_verb_catalog_drift_gate_LANDED_live_7ddaf55_..._2026_07_08.md`.
- **MCP tool-name wire rename — dots → underscores + one-release dotted alias**
  (`7e26853` → merged `7a47122`).  A wire-boundary projection: canonical dotted names
  (`sandbar.entity.find`) stay internal; the underscore form (`sandbar_entity_find`) is
  emitted at `tools/list` and reversed at call entry, so authz classification is provably
  name-independent (asserted identical for all 82 verbs).  Dotted names stay accepted for
  ONE release via a never-advertised deprecation-WARN alias (tools AND prompts);
  `catalog-check` gained wire-name injectivity / round-trip / pattern invariants.  This
  is a Dan-ruled 0.2.0 release gate.  Alias retirement is a KNOWN GAP (see below).  Per
  `memory/decisions/mcp_tool_name_rename_LANDED_live_7a47122_..._2026_07_08.md`.
- **Create-path loud-reject** (`008e56b`/`d578211` → merged `3ab31a8`).  `entity.create`
  without a resolvable rel-path no longer silently mints a DB-only orphan the FS sink
  skips: rel-path present ⇒ unchanged; absent + explicit ident ⇒ derived from ident;
  absent + underivable ⇒ **rejected loudly** (`:create-path-missing-rel-path`).  The
  reactive sink logs WARN (corpus docs) / debug (runtime); a `corpus-document-class?`
  predicate exempts the `:mm/Spec`/`:mm/Activity`/`:mm/Event` runtime roots so
  `:mm/Schedule` creates keep working.  Live probe confirms the loud rejection.  Fixes
  `memory/bugs/entity_create_codec_path_mints_identless_relpathless_entities_..._2026_07_08.md`.
- **Emit-path filename-length guard**.  A rel-path with a path segment over the
  filesystem's per-segment name limit committed fine and could then NEVER be
  projected: the sink's write fails ENAMETOOLONG and is warn+swallowed on every
  drain, stranding the entity as a DB-only orphan (silent FS↔DB bijection break) —
  and the window is wider than NAME_MAX itself, since `atomic-write!`'s `.tmp`
  sibling makes 252-255-byte filenames fail despite a legal target name
  (probe-confirmed on APFS).  Now guard (1) of the pre-transact rel-path hardening:
  `sinks/assert-rel-path-name-max!` **rejects loudly** (`:rel-path-segment-too-long`,
  message naming the limit) at create time — 255 UTF-8 bytes per directory segment,
  251 for the filename (the `.tmp` reservation, single-sourced from the write
  protocol) — bytes, not chars, as the ext4/APFS portable floor for the git-tracked
  corpus.  Runs before containment (OS canonicalization raw-throws on ≥256-byte
  segments).  New `sinks_name_max_test` receipts (incl. an OS premise canary) + 6
  store-test regressions; the sink never sees a fresh over-budget entity.
- **Foundational co-evolution + tag-modeling substrate** (rolled in from the retired
  `[Unreleased]` narrative).  `lein issue-mcp-token` service-account bootstrap
  (`2d90d04`); the `:mm/Tag` first-class enrichment + 7 supporting SKOS/DCAT classes +
  `sandbar.audit.tag` invariants + codec class-routing + 9 tag/ground MCP verbs
  (`9d5f8f4`, `d6df72f`); 22 typed-edge attributes on `:mm/Memory` (`7fbdd51`); the
  `sandbar.util.diff` re-port (`3912525`); full-corpus ingest + codec fidelity work
  (Friction items #4/#8/#9/#11/#12/#14/#17).

### Fixed

- **Schedule seed idempotency (W3.B)** — stable content-key `:db/ident` at create +
  `prune-duplicate-schedules!` self-heal + seed-twice test (`fe7cacd`); content-key
  WIDENING + prune gating to fix silent data-loss on non-target axes (`320b103`,
  `e4b5fc7`).
- **`XorConstraint` seed idempotency** — stable `:db/idents` on the interval XOR
  sub-entities (upsert-on-reload instead of ~40/shape append-proliferation) +
  `prune-duplicate-seed-constraint-subentities!` self-heal migration (`1bc426b`,
  Dan-approved 2026-07-03).
- **Tag-histogram = 0 across all 118 tags** — root cause was enumeration via
  `dt/all-named-instances-of` (requires `[?e :db/ident ?ident]`) while all 118 corpus
  `:mm/Tag` are identless ref-slot upserts; re-pointed to `dt/all-instances-of` (`bb4b5d6`),
  with a `:tag` canonical fallback ident→value→db/id (`fb26020`) and nil-source hardening
  (`8fe9466`, S11).
- **SSE encoder** — real SSE encoder for POST `/mcp` content negotiation, fixing the
  SSE-first `Accept` ArityException that blocked Codex MCP clients (`463d24e`); SSE
  encoder later consolidated into `util.codec/clj->sse-stream` (`e759468`).
- **Codec shadow-slot + digit-dodge fixes** — strip-shadow-collisions on emit so legacy
  doubly-declared shadow slots no longer clobber canonical YAML keys (`2ce81a7`);
  EDN-safe ident round-trip (`cac6570`).

### Known gaps

See `doc/known-gaps-0.2.0.md` for the full riding-gaps ledger (each with what / why it
rides / authorizing record / planned close): the read-plane structure-only oracles
(`resources/list` `:auth/*` metadata enumeration, `entity.update` RPAF dispatch gap,
aggregate/registry structure oracles); the OPEN public-bottom-edit firewall bug; the 14
pinned baseline codec test vars (posture pending Dan); the dotted-alias retirement
prerequisites (621 in-card refs); and physical auth-store separation (post-0.2.0).

## [0.1.0] — 2026-05-15 — first public release

First public release of Sandbar.  Foundational substrate: metacircular RDFS-style metamodel on Datomic, the codec layer, the bidirectional projection primitive, the workflow substrate, the MCP server, and the comprehensive four-axis retrieval surface (search / aggregate / navigate / orient).

### Added

#### Metamodel + protocol surface

- **Metacircular RDFS-style metamodel** stored inside Datomic — `:dt/Class`, `:dt/Property`, `:dt/subclass-of`, `:dt/type`, `:dt/domain`, `:dt/range` — the type system describes itself.  Lineage: RDFS 1.1 (Brickley & Guha 2014) + KL-ONE / frames (Brachman & Schmolze 1985) + CLOS metaobject protocol (Kiczales et al 1991) + Datomic schema-on-read (Hickey 2012-present).
- **`dt/*` API** — class introspection (`dt/all-classes`, `dt/all-instances-of`, `dt/slots-of`, `dt/instance-of?`, `dt/ancestors-of`, `dt/subclasses-of`), property introspection (`dt/domain-of`, `dt/range-of`, `dt/cardinality-of`), entity construction (`dt/make`), validation (`dt/validate`).  Explicit `*-ident-of` / `*-entity-of` helper split — return-shape exposed in fn names.
- **MCP server** (Model Context Protocol; JSON-RPC 2.0 over streamable HTTP + SSE notifications) — Bearer-token auth via Buddy-hashers; 43-verb operational catalog with comprehensive LLM-consumable descriptions per the WHICH / WHEN / HOW / ORDER / COMBINATION 5-dimension discipline.
- **REST API** at `/api/*` — schema / class / property / entity / workflow / event / job / status endpoints; mirrors the MCP surface for HTTP consumers.
- **mm/Memory schema** — corpus markdown documents as Sandbar entities with `mm/Section` chunking + SIOC-pairwise sibling-chain navigation.

#### Four-axis retrieval surface (this release's foundational expansion)

- **Search axis** (`sandbar.search`):
  - `dt/search-fulltext` — single-attribute Lucene-backed search via Datomic `:db/fulltext`
  - `dt/bm25f-weights-of` / `dt/fulltext-indexed?` — substrate primitives
  - `sandbar.search/search-attribute` — single-field BM25 opts-shaped wrapper
  - `sandbar.search/search-bm25f` — canonical Robertson-Zaragoza BM25F multi-field weighted scoring, ported byte-for-byte from the corpus reference implementation
  - `sandbar.search.analysis` — Unicode-aware tokenizer + Porter stemmer (Porter 1980); ported from corpus
  - Per-class `:dt/bm25f-weights` declaration at schema layer; metamodel-driven analyzer
  - `:include [:snippets :scores :facets]` projection options; `:where` Datalog composition; `:facet-by` aggregation

- **Aggregation axis** (`sandbar.aggregate`):
  - `dt/count-of` / `dt/group-by-of` substrate primitives
  - `dt/degree-of` / `dt/backlink-density-of` / `dt/recency-rank-of` / `dt/freshness-rank-of` — the four structural-rank axes
  - `sandbar.aggregate.count-by` / `.group-by` / `.rank-by` opts-shaped wrappers
  - MCP verbs `sandbar.aggregate.count` / `.group-by` / `.rank-by`
  - REST endpoints `GET /api/aggregate/count` / `/group-by` / `/rank-by`

- **Navigation axis** (`sandbar.navigate`):
  - `sandbar.navigate.edges` — `dt/inbound-edges-of` / `dt/outbound-edges-of` substrate + opts-shaped wrappers
  - `sandbar.navigate.walk` — BFS reachable-neighborhood walk with hop-cap + direction + path projection; per [`decisions/sandbar_graph_walk_clojure_bfs_over_datomic_recursive_rules_2026_05_14.md`](memory/decisions/sandbar_graph_walk_clojure_bfs_over_datomic_recursive_rules_2026_05_14.md) (corpus-side) — Clojure-side iterative BFS chosen over Datomic recursive rules for hop-cap semantics + path tracking + shortest-path guarantees
  - `sandbar.navigate.siblings` — same-directory peers via caller-supplied path-slot
  - `sandbar.navigate.path` — Wilbur-lineage path-grammar; Kleene-algebra-over-binary-relations; 13 of 21 operators executable today (Canonical-8 + Tier-2: `:SEQ` / `:OR` / `:REP+` / `:REP*` / `:INV` / `:SELF` / `:RESTRICT` / `:ANY` / `:NOT` / `:OPT` / `:REP` bounded / `:FILTER` / `:TEST`); Tier-3 (`:LANG` / `:VALUE` / `:DAEMON` / `:NOREWRITE` / `:MEMBERS` / `:PREDICATE-OF-*`) vocabulary-registered, compilation deferred
  - Three-layer DSL/IR/Backend architecture (`sandbar.navigate.path.{ast,ir,datomic,value}`): EDN parser + algebraic-identity rewriter + Datomic compiler + path-as-first-class-value abstraction
  - Paths as first-class values: `length` / `prefix?` / `suffix?` / `subpath?` / `extend-path` / `concat-paths` / `reverse`
  - MCP verbs `sandbar.navigate.path-via` / `.siblings-of`
  - REST endpoints `GET /api/navigate/path` / `/siblings`

- **Orientation axis** (`sandbar.orient`):
  - `dt/library-card-of` substrate primitive — multi-axis typed-edge composition with caller-supplied axis-specs (class-agnostic per [`decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md`](memory/decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md))
  - `sandbar.orient.library-card` opts-shaped wrapper
  - MCP verb `sandbar.orient.library-card`; REST endpoint `GET /api/orient/library-card`
  - Note: `arc-forest` / `ready-queue` / `session-state` / `index-snapshot` defer to consumer layer (corpus-specific orchestration; would violate substrate-quality discipline at the Sandbar layer)

#### Codec + projection layer

- **Codec mediator** (`sandbar.codec`) — modular extensible codecs (markdown + JSON; TTL + EDN/TTL-hybrid follow post-0.1.0); per-class `:dt/native-codec` declaration; consumer-native representation discipline
- **`sandbar.projection`** (renamed from `sandbar.project-graph` per Dan-directive 2026-05-14) — bidirectional `project-graph` / `ingest-graph` per James Anderson's `de.setf.rdf:project-graph` lineage; DB-state ↔ filesystem-hierarchy projection with `:filter` partition flexibility for hybrid FS/DB experimentation

#### Public-API boundary discipline

- **`sandbar.entity-ref` namespace** — canonical boundary abstraction for entity-reference resolution across MCP / REST / in-process boundaries.  Accepts keyword ident / prefixed-string / unprefixed-string / numeric-string / integer eid / entity map; returns canonical entity map (`resolve`) or ident keyword (`resolve-ident`); produces structured `ex-info` with `:reasons #{}` set-arity envelope (`:entity-ref/malformed-input` / `:entity-ref/not-found` / `:entity-ref/lookup-vector-unsupported` / `:entity-ref/no-ident`) for rejected inputs.  Predicate-style `validate` returns `{:valid? :entity :reasons :message}` without raising.  Resolves the F-MF-3 codex finding (integer eid `AssertionError` escape from the MCP error envelope) per the corpus-side ADR at `decisions/sandbar_entity_ref_abstraction_2026_05_14.md`.
- **`sandbar.util.jsonrpc-status` namespace** — semantic named constants for JSON-RPC 2.0 + MCP error codes (parallel to `sandbar.util.http-status`); eliminates opaque negative-integer literals (`-32603` etc.) at boundary surfaces per corpus-side ADR at `decisions/sandbar_jsonrpc_status_semantic_constants_namespace_2026_05_14.md`.
- **`handle-call` catch widening** — MCP's `tools/call` dispatcher catches `AssertionError` + `Exception` separately (not `Throwable` — preserves JVM-error propagation discipline for `OutOfMemoryError` / `StackOverflowError` / etc.); residual precondition failures project to structured JSON-RPC `internal-error` envelope rather than escaping the boundary.
- **Helper-vs-handler discipline** — MCP layer's shape-coercion helpers (`class-arg` / `property-arg` / `workflow-arg`) stay DB-independent (testable without fixture); handlers call `eref/resolve` / `eref/resolve-ident` directly on ref args where validation matters.  Architectural distinction documented at corpus-side `observations/sandbar_entity_ref_shape_helpers_vs_handler_validation_2026_05_14.md`.

#### Workflow substrate

- **First-class workflow definitions** as Datomic entities — states + transitions + `:workflow/terminal-kind` classification (`:success` / `:failure` / `:cancel`)
- **MCP Tasks** as workflow processes — `task-id` IS `:db/id` (no parallel registry)
- **Cancellation as workflow substrate** — not per-tool plumbing; cancel transitions move processes to `:cancel`-terminal states

#### Documentation

- **README** with value-frame intro + 3 concrete examples (in-process Clojure, MCP/JSON-RPC, four-axis retrieval composition) + linked documentation map at the top + reading-order suggestions pinned to consumer roles
- **Layer-2 concept docs** (theoretical reference, citation-rich): metamodel, codec-layer, projection, fulltext-search, aggregation, navigation, path-grammar (Wilbur algebra + 21-operator vocabulary + three-layer architecture + 12 algebraic identities + 7 academic references), workflow-substrate, mcp-protocol, multi-store-architecture, markdown-as-canonical
- **Layer-3 guides** (hands-on how-to): quickstart, zorp-tutorial, writing-a-clojure-client, writing-an-mcp-client, writing-a-rest-client, searching-the-corpus, navigating-with-paths, implementing-a-codec, defining-new-classes, designing-workflows, sandbar-as-substrate
- **Layer-4 API references** (mechanical): dt-star, http-rest, mcp-verbs, codec-protocol

### Architectural decisions captured at 0.1.0

- **Three-layer DSL/IR/Backend architecture** for the query / path-grammar engine — scale-up is backend swap, not DSL rewrite
- **Datomic as primary backend** at 0.1.0 (per `decisions/datomic_primary_backend_elevation_2026_05_11.md` corpus-side) — Apache-2.0 license, native Lucene `:db/fulltext`, schema-as-data, time-as-first-class
- **Canonical Robertson-Zaragoza BM25F** scoring (not Lucene Similarity composition approximation)
- **Clojure-side BFS for graph-walk** vs Datomic recursive rules — hop-cap semantics + path tracking + shortest-path guarantees
- **Phase O scope-narrowing** — substrate-correct library-card only; corpus-specific orientation views (arc-forest / ready-queue / session-state / index-snapshot) live at the consumer layer
- **`projection` rename** — top-level Sandbar namespaces should read unambiguously as nouns; `project-graph` / `ingest-graph` function names preserved as verbs at call site
- **MCP LLM-consumability discipline** — every catalog verb description covers WHICH / WHEN / HOW / ORDER / COMBINATION; the catalog teaches its own use

### Pre-release stabilization (Phase R + Phase U remediation, 2026-05-14 → 2026-05-15)

Two parallel review-driven remediation arcs closed before 0.1.0 tag, joint-gating the release per `plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md`:

**Phase R** (codex `--effort xhigh` review remediation; 9 findings across 9 stages) closed across two days:

- **R-1** (F-MF-3, 2026-05-14): `sandbar.entity-ref` boundary abstraction + 11 MCP handler migrations + REST handler `db/entity → eref/validate` migration + Pedestal `entity-ref-error-interceptor` projecting structured ex-info to HTTP 400 / 404 + `handle-call` catch widening (AssertionError + Exception separately) + ref-arg `:pre` drops at navigate/orient surfaces.
- **R-2** (F-MF-1, 2026-05-14): `:ANY` path-grammar operator constrained to ref-typed attributes in the compiler (`compile-any` emits `[?pred :db/valueType :db.type/ref]` guard); restores the typed-edge algebra invariant.  Adversarial tests at compiler / wrapper / REST / MCP layers.
- **R-3** (F-MF-2 / cross-source-confirmed UR-3, 2026-05-15): `dt/degree-of` inverse-row destructure fix — aligned `:find ?a ?s` so the shared `match?` predicate destructures the attribute correctly under `:direction :inverse` / `:bidirectional` with `:predicates` filter (pre-fix silently returned 0).
- **R-4** (F-SF-2, 2026-05-15): `log-error!` stacktrace capture without stderr leak — replaced `(with-out-str (.printStackTrace ex))` with PrintWriter/StringWriter capture; the stacktrace lands in `:event/stacktrace` data field and stderr stays clean.
- **R-5** (F-SF-1, 2026-05-15): `search-bm25f` docstring narrowed to bag-of-words contract; `search-attribute` distinguished as the Lucene-query-syntax surface.  Pinned-difference contract tests prevent future docstring drift.
- **R-6** (F-DF-1 Phase 1+2, 2026-05-15): bench scaffolding — new `bench/sandbar/bench/` namespace tree (harness + synthetic-graph factory + orchestrator) + `lein bench` alias + `doc/BENCH.md` discipline + initial `bench-results/baseline.edn` (10/100/1k/10k ladder).  Phase 3 (optimization) deferred post-0.1.0 per triage decision D-2.
- **R-7** (F-DB-1 Option D + Policy A, 2026-05-15): path-data reconstruction — new `sandbar.navigate.path.evaluate` namespace (Clojure-side BFS IR evaluator over all 8 Canonical-8 operators with frontier-as-map + Policy A first-arrival + path.value substrate); `:include #{:paths}` surface now populates real path data through wrapper + REST + MCP layers (`:path-data-deferred` placeholder permanently dropped).
- **R-8** (release-readiness audit, 2026-05-15): full `lein test` green + namespace-load smoke + F-SF-3 audit + CHANGELOG entry + version + gpg-key verification.

**Phase U** (parallel ultrareview remediation; 13 findings) — closed via:

- **UR-1**: workflow loading works under `lein uberjar` packaging via `JarURLConnection` dispatch
- **UR-2** (CRITICAL): JSON codec reads class-specific knowledge from metamodel at runtime (`dt/codec-aliases-of` + `dt/range-of`); no hardcoded consumer-class table
- **UR-4**: MCP protocol-version doc/code reconciled (4 occurrences updated to canonical `2025-11-25`)
- **UR-5**: MCP `tasks/list` dispatch entry registered; new `workflow/list-processes` + `workflow/list-active-processes` substrate primitives
- **UR-6 + UR-7**: codec emit excludes `:db/*` / `:db.*` / `:mm.memory/rel-path` from both markdown and JSON wire formats
- **UR-8**: Bearer scheme detection is RFC-compliant case-insensitive (`Bearer`, `bearer`, `BEARER`, `BeArEr` all accepted)
- **UR-9**: `search/where-matching-eids` boundary `:pre` guard replaces opaque deep-Datalog failure with structured AssertionError
- **UR-10**: MCP `server-info` version aligned to `project.clj` (`0.1.0`)
- **UR-11**: `sandbar.navigate.path.datomic` dedupes structurally-identical recursive rules across nested REP / OR / SEQ compositions
- **UR-12**: `split-frontmatter` reads regex match position via `re-matcher` (handles `---` recurring inside body)
- **UR-13**: SSE `publish!` detects closed-channel return + evicts dead subscribers from the registry; bounded across connect-disconnect cycles
- **UR-14**: `doc/guides/sandbar-as-substrate.md` Configuration section reconciled to actual `config.edn` schema (`:db {:url :sid}` / `:nrepl {:port}` nested shape)

Cumulative test delta from the remediation arcs: net +78 tests / +245 assertions over the start-of-2026-05-14 baseline (+38/+83 through Phase U closure end-of-2026-05-14; additional +40/+162 through Phase R Stages R-2/R-3/R-4/R-5/R-7 by end-of-2026-05-15).

### Tests

- **927 tests / 4611 assertions, all green** (final end-of-cycle verification at 2026-05-15)
- Namespace-load smoke test covers every public-API namespace shipping at 0.1.0 (release-gate per F-M-005)
- Per-operator path-grammar compilation tests (structural + end-to-end against metamodel fixture); nested REP composition rule-dedupe regression guards (Phase U Stage U-6)
- Round-trip codec tests (markdown + JSON); persisted-entity emit excludes `:db/*` regression guards (Phase U Stage U-2)
- Workflow state-machine tests including Zorp's Galactic Footwear Emporium narrative coverage
- Bearer-token auth case-insensitivity adversarial coverage (Phase U Stage U-5)
- SSE notifications channel-close + bounded-registry adversarial coverage (Phase U Stage U-7)
- **`sandbar.mcp.tools-db-test`** — DB-backed handler-dispatch acceptance suite (sibling to the shape-only `tools-test`): F-MF-3 verbatim falsification calls (integer eid + bogus ref + lookup-vector) + 5-shape roundtrip (keyword / prefixed-string / unprefixed-string / integer eid / entity-map) for the F-MF-3 surfaces (`path-via` + `library-card`) + per-handler error-projection sanity for every migrated handler family (Navigate/Orient + Entity + Aggregate + Type-predicates)

### Internal

- gpg signing key configured in `project.clj` for Clojars publication

### Lineage + further reading

The substrate draws deliberately from settled algebras + decades-old design traditions:

- **RDFS 1.1** (Brickley & Guha 2014) — class / property / domain / range vocabulary
- **KL-ONE / frame systems** (Brachman & Schmolze 1985; Minsky 1974) — structured inheritance with slots
- **CLOS metaobject protocol** (Kiczales, des Rivières & Bobrow 1991) — self-implementing object system
- **Datomic** (Hickey 2012-present) — schema-on-read, first-class time, native Lucene
- **Wilbur** (Lassila / Nokia 1989-2009) — paths-as-EDN; Kleene-algebra-over-binary-relations
- **SPARQL 1.1** (Harris & Seaborne 2013) — property paths; algebraic convergence
- **BM25 / BM25F** (Robertson, Walker, Beaulieu 1995-1998 / Robertson, Zaragoza & Taylor 2004) — canonical IR relevance scoring
- **`de.setf.rdf`** (James Anderson; Datagraph/Dydra-era) — `project-graph` boundary-layer primitive
