# Event Substrate

> Sandbar's reactive-projection pipeline (see [`reactive-substrate.md`](reactive-substrate.md)) was the first step.  The event substrate is the next: a typed in-process event bus rooted in Datomic's own `tx-report-queue`, wrapped behind the `sandbar.reactive.tx-source` boundary primitive so no Datomic shape leaks past it; transported on Manifold streams; dispatched by class hierarchy via `dt/type-isa?` with a mandatory dispatch cache; structured around three distinct event class hierarchies — `:mm/Event` for memorial-significant events, `:dt/Event` for substrate-runtime, `:workflow/History` for the Process Manager — each with its own role.  The architecture was ratified 2026-05-23 after three-agent research convergence (event sourcing + CQRS, Erlang/Akka/Pony, core.async/Manifold/Flow API); implementation is **partially landed** — Phases 1, 2, and 8 are in the tree (`sandbar.reactive.tx-source`, `sandbar.event`, and the γ-scheduler's event integration respectively); Phases 4 and 5 (sink migration + callsite-hook deprecation) are not started; Phases 3, 6, and 7 are partial.  See the phase-by-phase status below.

## Thesis

A database is already an event log if it is honest about it.  Datomic is — `tx-report-queue` is the substrate-native event mechanism, an unbounded `BlockingQueue` of `{:db-before :db-after :tx-data :tempids :t}` maps, one per committed transaction.  The reactive-projection pipeline at `0.2.0` shadows this from outside, hooking the `dt/*` boundary because the `dt/*` boundary is what application code calls.  That works, but it misses tx-fn-generated effects (transactor-side functions whose effects appear only in `tx-data`), provides no catch-up-on-disconnect, and parallel-implements machinery the substrate already gives away for free.

The event substrate exploits `tx-report-queue` directly, but does so **behind** `sandbar.reactive.tx-source`.  This is the first concrete test of sandbar's architectural-boundary discipline: a future retarget to XTDB, Datalevin, Asami, or in-memory test fixtures changes only the boundary primitive's internals.  Consumers see a `(sandbar.event/subscribe ...)` surface that returns typed event values and opaque subscription handles; they see no Manifold types, no Datomic types, no transport-layer surface at all.

Dispatch is class-hierarchical.  A subscriber registers interest in `:event/ServerEvent` and receives events from every descendant — `:event/HttpRequest`, `:event/WebSocketFrame`, anything derived later — automatically.  This composes naturally with the metamodel's existing class machinery; sandbar already has typed inheritance, and the event bus reuses it rather than inventing topic strings.  The cost — re-walking the class chain on every event — is paid once and cached: a `defmulti`-style dispatch table keyed `{class-ident → subscriber-set}`, invalidated when the class hierarchy mutates via the same `clear-type-relation-cache!` registry that handles other type-relation memoization.

Three event class hierarchies harmonize into distinct roles rather than collapsing into one:

| Hierarchy | Role | Memorial policy |
|---|---|---|
| `:mm/Event` (memorial, descends from `:mm/Meta`) | User-facing memorial face — events that earn promotion to corpus-FS | `:first-class` for narrative events; `:db-only` for operational; `:inline` for embedded |
| `:dt/Event → :event/ServerEvent → :event/HttpRequest` (substrate-runtime) | Runtime substrate — high-volume in-process events flowing through `sandbar.reactive.tx-source` | `:db-only` typically |
| `:workflow/History` (Process Manager log) | Durable workflow transition record — Vernon's Process Manager pattern, retained distinct | `:db-only` typically |

The three are not redundant.  `:mm/Event` is the *memorial face* — what gets projected to `memory/` for human review.  `:dt/Event` is the *runtime substrate* — what the in-process bus actually carries.  `:workflow/History` is the *Process Manager* — the durable transition log distinct from the bus.  Workflow transitions emit *both* — a `:workflow/History` entry (durable, queryable, the ground truth of "this process moved") *and* a `:mm.event/WorkflowTransition` instance on the bus (so reactive subscribers see the transition without coupling to the Process Manager's storage shape).

Telemere bridges in at exactly one point.  Signals flagged `:data {:memorial <policy>}` flow through the Telemere `memorial-projection-handler`, which republishes them as typed `:mm/Event` (or appropriate subclass) instances on the event substrate.  One source of truth; one fan-out; the two pipelines that would otherwise have evolved in parallel collapse into one.

Per-class buffer policy lets the substrate carry two populations with opposite requirements.  High-volume entity-mutation events tolerate drop semantics — the substrate is the source of truth and dropped projections recover from re-reads.  Log and audit events do not tolerate drops — the event *is* the truth, and loss is silent data corruption.  The metamodel slot `:mm.event/buffer-policy` (`:sliding-N` / `:dropping-N` / `:block` / `:unbuffered`) declares the per-class policy; the substrate dispatches the appropriate buffer at subscriber-registration time.  For genuinely non-droppable high-volume events that exceed memory-only backpressure, Chronicle Queue (via `zalky/cues` or `mpenet/tape`) is the escape-hatch sink — memory-mapped disk-backed persistence sized by available disk rather than RAM.  Deferred until empirically warranted.

Handler isolation is intrinsic, not optional.  The Erlang/OTP `gen_event` anti-lesson is explicit in the architecture: same-process sequential dispatch is wrong because one slow handler blocks all others.  Every sink runs in its own isolation domain — per-sink Manifold `consume`, per-sink core.async go-block, or at minimum an exception barrier around each invocation.  Supervision discipline borrows from OTP: each sink declares restart strategy (`:permanent` / `:transient` / `:temporary`), intensity (max-restarts), and period (in seconds).  Cumulative-restart-cap detects loud failures.

Property Sourcing is forbidden.  Events carry business meaning.  `:mm.event/WorkflowTransition` (carrying `from-state`, `to-state`, `actor`, `reason`) is correct.  `:mm.event/AttrChanged` (raw attribute deltas as primary payload) is forbidden per Mageed (event-driven.io): mechanical attr-deltas drift away from domain semantics over time and are uninformative to consumers.  Reviewer rejects PRs that violate.

CQRS is explicitly **not** adopted.  Sandbar is single-actor and low-throughput; read/write loads do not diverge enough to justify separate models.  Event sourcing is adopted *implicitly* — Datomic is the event store, `:mm/Event` is the typed event surface — but commands remain sandbar's existing `entity.create` / `entity.update` MCP verbs.

## Status — phase-by-phase (verified against the tree)

The eight phases are sequenced for parallel-run safety.  Status as verified against the source tree:

- **Phase 0** — Architecture ratified.  **Done** — this concept doc and the keystone ADR are its durable form.
- **Phase 1** — Build `sandbar.reactive.tx-source` (wraps `d/tx-report-queue`).  **Landed** — `src/sandbar/reactive/tx_source.clj` implements the boundary primitive: `start!` / `stop!` / `stream` / `tx-report->event`, a daemon polling worker over the `tx-report-queue` `BlockingQueue`, and a 1024-event Manifold stream; Datomic types are strictly confined to the namespace.  Two caveats: events currently carry `:event/kind :tx` with `[e a v added?]` datom tuples (the typed `:mm.event/*` classification is deferred to Phase 3), and `catchup-from` via `d/tx-range` (Phase 1.5) is not implemented.  The tx-source is also not started at server boot — no production caller invokes `start!` yet; the Phase-1→Phase-2 bridge (`ms/consume` of the stream into the dispatcher) is unwired.
- **Phase 2** — Build the `sandbar.event` boundary verb + dispatch-table cache.  **Landed** — `src/sandbar/event.clj` implements `subscribe!` / `unsubscribe!` / `fire!` / `dispatch-set` with the mandatory dispatch cache (keyed `{event-class-ident → subscriber-set}`, invalidated on subscriber change and via the post-schema-reload registry) and per-handler exception isolation.  Dispatch is synchronous within the caller's thread; per-subscriber async isolation domains and the Akka-Streams-flavored Flow operators (`filter` / `map` / `batch` / `throttle`) are **not yet built**.
- **Phase 3** — Author `:mm.event/*` schema and the `:mm.event/buffer-policy` slot.  **Partial** — `schema/mm-temporal.edn` authors the `:mm.event/WorkflowTransition` hierarchy (abstract umbrella + five `:mm.event/WorkflowSession*` subtypes, W4.1 Increment A) and the LODE-quadrant `:mm.event/*` slots on the abstract `:mm/Event` root; the scheduler event family (`:mm.event/Scheduled`, `:mm.event/Job{Started,Completed,Failed,Rejected,Cancelled}`, `:mm.event/ScheduleConcurrencyViolation`) is live on the bus; `:mm.event/EntityRetracted` is live only as an `:event/kind` on `dt/make`-created audit event entities (`sandbar.util.event/log-event!`), not bus-fired — a `subscribe!` on it receives nothing.  `:mm.event/EntityCreated`, `:mm.event/EntityUpdated`, `:mm.event/Log`, and the `:mm.event/buffer-policy` slot are **not yet authored**.
- **Phase 4** — Migrate existing sink consumers onto the new substrate (parallel-run).  **Not started** — the reactive-projection pipeline (`fs-projection-sink`, `sse-emit-sink`) still runs on the `dt/*` callsite hook + `sandbar.reactive` callback registry, wired at boot in `sandbar.core`.
- **Phase 5** — Deprecate the `dt/make` callsite hook once Phase 4 demonstrates equivalence.  **Not started** — the callsite hook remains the live production path.
- **Phase 6** — Wire the Telemere `memorial-projection-handler` into the unified substrate.  **Partial** — the handler exists (`sandbar.logging.handlers`) and is registered at boot (`sandbar.logging.init/start!` adds it as the `:sandbar/memorial-projection` Telemere handler), resolving the logging arc's Stage D; but it bridges via direct `dt/make` (creating `:mm/EventLog` / `:event/SystemEvent` entities) rather than publishing typed events onto the unified bus — the D.5 one-fan-out unification is pending.
- **Phase 7** — Compose `:workflow/History` with `:mm.event/WorkflowTransition`.  **Partial** — the ι.3 orchestrator (`sandbar.workflow.orchestrate`) emits `:mm.event/WorkflowSession{Opened,HandoffAuthored,Closed,Degraded,Failed}` at phase boundaries and the hierarchy is schema-authored, but emission goes through `sandbar.util.event/log-event!` (typed event *entities* via `dt/make`), not through `sandbar.event/fire!` on the bus.
- **Phase 8** — Scheduler integration (`:mm.event/Scheduled`, `:mm.event/JobStarted`, `:mm.event/JobCompleted`).  **Landed** (ahead of Phases 4–7) — the γ-scheduler's fire-thread emits `:mm.event/Scheduled` via `sandbar.event/fire!`; `sandbar.schedule.job-dispatcher` subscribes via `subscribe!` and emits the full `:mm.event/Job*` lifecycle family on the bus.  This is the event bus's first production consumer.

**Also existing today (at `0.2.0`).**  The reactive-projection substrate documented in [`reactive-substrate.md`](reactive-substrate.md) — `dt/*` callsite hook, three-layer opt-out, bounded sliding-buffer queue with per-entity coalescing, `fs-projection-sink` and `sse-emit-sink`.  This is what the event substrate *replaces* under the parallel-run migration (Phases 4–5, not started); it is not in any sense *missing*.

The remainder of this document describes the ratified design — citing the keystone ADR — including the pieces above that have not yet landed.  Where a section describes machinery beyond Phases 1/2/8's landed surface (Flow operators, catch-up, buffer policy, the unified Telemere fan-out), treat it as design, not as running behavior.

## The boundary primitive — `sandbar.reactive.tx-source`

The namespace `src/sandbar/reactive/tx_source.clj` (landed — Phase 1) owns the Datomic interaction.  It is sandbar.db.* family — it may use `datomic.api/*` directly; no consumer outside `sandbar.reactive.*` should require it.  Its responsibilities:

- Subscribe to `d/tx-report-queue` on the connection.
- Translate each `{db-before db-after tx-data tempids t}` map into one or more typed sandbar event values — never raw datom-shaped maps; never `TxReport` shapes.
- Publish to the Manifold event-bus stream that backs `sandbar.event`.
- Support `(catchup-from conn basis-t)` — replay missed events via `d/tx-range` from a subscriber's last-seen checkpoint.  (Design — deferred to Phase 1.5; not yet implemented.)

The translation is where the boundary discipline is enforced.  A `{:a 42 :e 12345 :v "foo" :tx 100 :added true}` datom does not appear in any consumer signature; instead, `tx-source` recognizes the entity's `:dt/type`, constructs a typed `:mm/Event` (or subclass) instance carrying business-meaning slots, and publishes that.  A future retarget to a non-Datomic backend rewrites this translation; nothing else changes.

## The boundary verb — `sandbar.event/subscribe`

The namespace `src/sandbar/event.clj` (landed — Phase 2) is THE BOUNDARY.  Consumers see sandbar-typed handles and sandbar-typed events; Manifold and Datomic do not surface.  The landed surface is `subscribe!` / `unsubscribe!` / `fire!` / `dispatch-set` / `subscribers-of`:

```clojure
(ns my.module
  (:require [sandbar.event :as event]))

;; Subscribe to a single event class (the fn is the unsubscribe handle)
(def sub1
  (event/subscribe! :event/HttpRequest
                    (fn [evt]
                      (println "got request" (:event.http/path evt)))))

;; Subscribe to an entire branch — every :mm/Event descendant
(def sub2
  (event/subscribe! :mm/Event
                    (fn [evt]
                      (audit-log-callback evt))))

(event/unsubscribe! :event/HttpRequest sub1)
```

Per-subscriber opts (`:buffer-policy` et al.) and the Flow-operator composition surface (`event/filter` / `event/map` / `event/batch` / `event/throttle`) are design — not yet built; today's dispatch is synchronous with per-handler exception isolation.

Behind the surface: the dispatch cache resolves `{class-ident → subscriber-set}` once per event, hashing the event's `:dt/type` and union-ing the resolved set.  Hierarchy mutations (a new subclass derived at runtime) flush the cache via `clear-type-relation-cache!`; subscribers registered before the new subclass appears receive its events automatically after the cache rebuilds.

Test acceptance criteria from the keystone ADR:

- A subscriber registered to `:mm/Event` receives events from any `:mm/Event` subclass.
- A subscriber registered to `:event/HttpRequest` receives only that leaf class's events.
- Cache flushes on schema reload; new subclasses propagate to existing subscribers.
- Flow operators compose: `(filter pred (map f (subscribe :mm/Event handler {})))`.
- N×M predicate cost avoided: dispatch is O(1) per event after cache warm.

## The unified Telemere bridge

Two pipelines almost evolved in parallel.  One is the reactive-projection substrate at `0.2.0`, projecting entities to filesystem.  The other was a Telemere handler arc projecting signals to filesystem on the `:memorial` flag.  Either alone is coherent; together, they overlap, race, and duplicate the projection logic.

The unification (D.5 in the keystone ADR) is one fan-out:

1. Telemere handlers fire as usual on signals.
2. The `memorial-projection-handler` watches Telemere signals.
3. For signals flagged `:data {:memorial <policy>}`, it publishes a typed `:mm.event/Log` (or appropriate subclass) instance to `sandbar.reactive.tx-source`.
4. The event substrate's normal dispatch delivers to interested subscribers — `fs-projection-sink` for `:first-class`, the DB-dump indexer for `:db-only`, MCP SSE notifier for subscribers' interests.

One source of truth; one fan-out.  The signal does not exist in two storage shapes; the projection is not run twice; the substrate's class-hierarchical dispatch handles the routing the per-sink wiring would otherwise have to.

The anti-cycle five-layer defense applies (inherited from the logging arc Stage E):

1. Async decoupling at the publisher boundary (Telemere `:async {:mode :dropping}`).
2. Thread-local reentry guard (`*log-substrate-active*`).
3. Bounded buffer with drop-on-overflow.
4. Class-skip-list inheritance (publisher namespaces in `sandbar.reactive`'s skip-list).
5. Per-publisher dynamic-binding opt-out.

A handler that logs from inside its own delivery path does not recurse; the substrate detects the reentry and short-circuits.

## What this enables

The event substrate is the foundation under several capabilities that depend on it:

- **Reactive corpus projection** — the `fs-projection-sink` becomes a subscriber on the unified bus rather than a callback on a per-mutation hook.  The behavior is the same; the wiring is principled.
- **MCP SSE subscriptions** — `sse-emit-sink` similarly becomes a subscriber.  Notifications fire from event delivery, not from a `dt/*` callback.
- **Workflow transition observability** — `:mm.event/WorkflowTransition` events on the bus let metrics collectors, audit substrates, alert handlers, and downstream workflow processes react to transitions without coupling to `:workflow/History`'s storage shape.
- **Logging unification** — the Telemere `memorial-projection-handler` lands here (Phase 6), resolving the logging arc Stage D.
- **Future scheduler arc** — `:mm.event/Scheduled` / `:mm.event/JobStarted` / `:mm.event/JobCompleted` / `:mm.event/JobFailed` events let scheduler-aware code (retry handlers, dashboards, alerting) react to job state via the same `sandbar.event/subscribe` surface.
- **Catch-up on disconnect** — a subscriber registered after some events have fired calls `(sandbar.event/catchup-from sub basis-t)` and replays from the last-seen checkpoint via `d/tx-range`.

## What is explicitly **not** adopted

- **Full CQRS** — separate write and read models.  Sandbar is single-actor; the complexity payoff does not apply.
- **Property Sourcing** — `:mm.event/Attr*Changed` raw-delta events.  Forbidden per Mageed; events must carry business meaning.
- **Akka Typed compile-time refs** — typed actor refs leak implementation.  Sandbar's class-hierarchical subscription stays behind the substrate.
- **Sagas as a separate primitive** — `:workflow/Process` is the Process Manager.  Sagas may be added later if a use case appears.
- **Distributed pub/sub** (Kafka / Redis Streams / NATS) — in-process Manifold suffices for now.  Cross-process is deferrable; Manifold composes cleanly with these substrates when needed.

## Composition with adjacent architecture

The event substrate composes within several disciplines:

- **Architectural-boundary ADR** — this is the first concrete test.  `sandbar.reactive.tx-source` is purely-internal; `sandbar.event/subscribe` is the boundary; consumers see typed events only.
- **Mediator pattern** — sandbar mediates `(actor, context, event)` tuples through rules + dispatch + authorization.  The event substrate is the event half of that mediator surface.
- **Reactive-projection substrate** (existing) — subsumed under parallel-run migration.  The sinks become subscribers.
- **Logging arc** — Stage D resolves under D.5 unified bridge.
- **Workflow substrate** — `:workflow/Process` stays as Process Manager; transitions also emit `:mm.event/WorkflowTransition` events on the bus.
- **DB-dump arc** — `:mm.event/*` instances with `:db-only` policy are covered by the dump (when the ident-readability question resolves).
- **SSE reactive corpus projection arc** — subsumed; the bespoke fs-projection mechanism becomes a subscriber on the unified substrate.
- **Sandbar transformation axis** — the broader arc from passive memory model toward alive AI substrate; the event substrate is one of the opt-in capabilities (alongside scheduling, health, first-class workflows) that drives that transformation.

## References

**Datomic listen and tx-report-queue**

- Hickey, R. & Halloway, S. *Datomic — `tx-report-queue`.*  The substrate-native pub/sub mechanism — an unbounded `BlockingQueue` populated by every committed transaction.  Wrapped behind `sandbar.reactive.tx-source` to preserve the architectural-boundary discipline.
- Vauquelin, B. *"Datomic gives you all in synchrony an expressive Command language (transaction requests), actionable Events (transactions as sets of Datoms), and a powerful, relational default Aggregate (Database Values)."*  The framing that motivates Datomic-as-event-store.

**Manifold streams**

- Tellman, Z. *Manifold — a compatibility layer for event-driven abstractions.*  Streams as in-process transport; first-class deferred-based backpressure; walkable topology via `connect` / `connect-via`.  Now maintained by clj-commons.
- Aleph and Lacinia-Pedestal — production deployments of Manifold at HTTP-and-subscription scale.

**Class-hierarchical dispatch**

- *Clojure Multimethods and Hierarchies* — `derive` / `isa?` / `descendants` form the in-language pattern that sandbar's class-hierarchical subscription generalizes over typed metamodel classes.
- Steele, G.L. *Common Lisp: The Language, 2nd Edition* — CLOS method combination is the deeper lineage.

**Event sourcing + CQRS**

- Fowler, M. *Event Sourcing* — the canonical pattern this architecture adopts *implicitly* (Datomic IS the event store; `:mm/Event` is the typed surface) without committing to the full CQRS write/read separation.
- Mageed, J. (event-driven.io). *Property Sourcing considered harmful.*  Raw-delta events drift away from domain semantics.  Sandbar's `:mm.event/*` subclasses must carry business meaning.

**Handler isolation — gen_event anti-lesson**

- Armstrong, J. (2013). *Programming Erlang.*  OTP's `gen_event` is the cautionary tale: same-process sequential dispatch is wrong because one slow handler blocks all others.  Sandbar requires handler isolation from the start.

**Chronicle Queue escape hatch**

- *Chronicle Queue (OpenHFT)* — memory-mapped disk-backed persistent queues.
- `zalky/cues` and `mpenet/tape` — Clojure wrappers used as the escape-hatch sink for non-droppable high-volume event populations.  Deferred until empirically warranted.

**Flow-stage operators**

- *Akka Streams* — the lesson behind the `filter` / `map` / `batch` / `throttle` operator set on the Manifold transport.  Per-sink ad-hoc logic disappears; pipeline composition replaces it.

## See also

- [`reactive-substrate.md`](reactive-substrate.md) — the existing reactive-projection pipeline at `0.2.0` that this architecture replaces under parallel-run migration
- [`workflow-substrate.md`](workflow-substrate.md) — `:workflow/Process` as Process Manager; transitions emit `:mm.event/WorkflowTransition` on the bus
- [`projection.md`](projection.md) — the `project-graph` boundary primitive that `fs-projection-sink` (now a subscriber) composes with
- [`metamodel.md`](metamodel.md) — the typed class machinery that backs class-hierarchical dispatch
- [`mcp-protocol.md`](mcp-protocol.md) — the SSE resource-update notification protocol driven from sink subscribers
- Keystone ADR (in memory corpus): `decisions/sandbar_event_substrate_architecture_datomic_tx_report_queue_wrapped_behind_dt_star_manifold_transport_class_hierarchical_subscription_2026_05_23.md`
- Implementation plan (in memory corpus): `plans/sandbar_event_substrate_implementation_arc_phases_1_8_2026_05_23.md`
