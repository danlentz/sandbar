# Changelog

All notable changes to Sandbar are documented in this file.  Format informed by [Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versioning follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html) at and after 0.1.0.

## [Unreleased] — Memory-Model Co-Evolution arc

This release is being authored as the **Memory-Model Co-Evolution arc** per Dan-directive 2026-05-20 (memory-corpus side decision at [`memory/decisions/sandbar_0_1_1_coevolution_with_memory_model_2026_05_20.md`](https://github.com/danlentz/claude/blob/master/memory/decisions/sandbar_0_1_1_coevolution_with_memory_model_2026_05_20.md); arc plan at [`memory/plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md`](https://github.com/danlentz/claude/blob/master/memory/plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md)).

Rather than the corpus immediately switching to canonical Clojars consumption of 0.1.0 (the path anticipated by the 2026-05-12 prior decision), Dan opened a co-evolution branch: corpus (memory-model client) begins integrating + consuming Sandbar 0.1.1-SNAPSHOT via local `lein install`; integration friction surfacing during memory-model consumption is analyzed at the architectural boundary; evolutions land on either the Sandbar foundation OR the memory-model per layering principles + substrate-first-friction discipline + improve-abstraction-not-bypass.

When the arc closes:

- `project.clj` bumps from `0.1.1-SNAPSHOT` → `0.1.1`
- Sandbar 0.1.1 cuts to Clojars (Dan-deploy)
- Corpus migrates to canonical Clojars consumption per the prior 2026-05-12 decision

Follow-on after 0.1.1 ships: return to the actor-context-rules deep-dive arc with sandbar-backend awareness in hand (per `authorizations/actor_context_rules_deep_dive_arc_2026_05_11.md` — standing since 2026-05-11; paused during Phase R).

### Added

- **`lein issue-mcp-token`** — bootstrap script for provisioning MCP-client service-accounts.  Creates an `auth/ServiceAccount` entity, hashes the API key via Buddy, and emits the `<service-name>:<api-key>` Bearer-token shape plus a ready-to-paste `export SANDBAR_TOKEN=...` shell line.  Resolves Friction Item #1 of the memory-corpus arc plan (`memory/plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md` §3) — the MCP client could not previously authenticate without a hand-rolled REPL session.  New namespace at `src/sandbar/scripts/issue_mcp_token.clj`; lein alias added to `project.clj`; usage documented in `doc/auth.md` §'Issuing tokens for MCP clients'.

### Changed

- _(pending)_

### Fixed

- **`sandbar.util.diff` namespace path/declaration mismatch** — `src/sandbar/util/diff.clj` previously declared its namespace as `sandbar.diff` (path was `sandbar/util/diff.clj`), which caused `lein check` to fail with `Could not locate sandbar/diff.clj on classpath`.  Re-ported from upstream `fgl.diff` ([clj-fgl](https://github.com/danlentz/clj-fgl/blob/master/src/fgl/diff.clj)) per Dan-directive 2026-05-20: copy code, no dependency.  The re-port also restores the upstream `merge*` (which the prior port dropped) + drops inert `:require [fgl.util]` and `:use [print.foo]` (neither's symbols referenced in upstream or port).  Resolves Friction Item #4 of the memory-corpus arc plan `memory/plans/sandbar_0_1_1_coevolution_arc_2026_05_20.md` §3.

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
