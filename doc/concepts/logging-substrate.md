# The Logging Substrate

> Sandbar's observability foundation — one sandbar-authored callsite API (`sandbar.logging`, six macros) over Taoensso Telemere as internal engine; signal-as-data end-to-end; per-handler async with bounded buffer; SLF4J v2 bridge for upstream Java callers; curated formatter middleware that restores real source attribution and tames the periodic floods; a memorial-projection handler that bridges flagged signals into the substrate as `:mm/EventLog` (`:first-class`) or `:event/SystemEvent` (`:db-only`) entities; a five-layer anti-cycle defense.  For the memorial classes this handler lands into, see [`memory-model.md`](memory-model.md); for the in-process event bus it composes with, see [`event-substrate.md`](event-substrate.md); for the typed-edge reactive sink that picks up `:first-class` memorials, see [`projection.md`](projection.md).

## Thesis

A logging substrate has three duties, in this order:

1. **One callsite shape** — developers see one sandbar-authored namespace, never the vendor library's macro zoo.
2. **Signal-as-data end-to-end** — the atomic unit is a rich Clojure map; no premature stringification; rendering is a handler concern.
3. **Memorial promotion as a flag, not a fork** — operational audit and corpus-resident chronicle reach the substrate through the same callsite, with one keyword distinguishing them.

The naive shape is to expose the vendor library directly — `tel/log!` for structured calls, `tel/event!` for named events, `tel/error!` for errors, `tufte/p` for profiling, `clojure.tools.logging/info` for the legacy callsites — and let developers learn the surface for each.  No team can keep up with that complexity at every callsite.  The substrate exists *to absorb that complexity*; if it doesn't, it isn't a substrate, it's an aggregation of vendor docs.

Sandbar's commitment: **`sandbar.logging` is the single public observability API; Telemere and Tufte are internal engine, never exposed at callsites.**  Six macros cover everything — `info` / `warn` / `error` / `debug` / `trace` / `profile` — with an optional final-positional keyword that promotes a signal from transient to durable.  The vendor underneath is replaceable; the callsites don't know it exists.

Underneath that surface, curated formatter middleware restores the source attribution that the SLF4J bridge throws away, drops the periodic-metrics floods that contribute volume without information, and truncates HTTP response bodies that would otherwise drown the log.  A memorial-projection handler observes signals carrying `:data {:memorial <policy>}` and creates the appropriate substrate entity — `:mm/EventLog` for corpus-resident narrative events, `:event/SystemEvent` for operational audit.  A five-layer anti-cycle defense ensures that a signal projected into the substrate cannot loop back through the logging pipeline.

This document describes that surface, that middleware, that handler, and that defense.

## The six callsite macros

The whole public surface is in `sandbar.logging` (aliased throughout as `sb-log`).  The shape is deliberately small:

```clojure
(require '[sandbar.logging :as sb-log])

;; --- structured form (preferred) ---
(sb-log/info ::sys-init)
(sb-log/info ::sys-init {:duration-ms 42})
(sb-log/info ::sys-init {:duration-ms 42} :db-only)
(sb-log/info ::sys-init "booted in 42ms" {:duration-ms 42})
(sb-log/info ::sys-init "booted in 42ms" {:duration-ms 42} :first-class)

;; --- human-string shorthand ---
(sb-log/info "Datomic transactor reconnected")
(sb-log/info "User logged in" {:user-id 42})

;; --- typed error (Throwable in the 2-arg slot) ---
(sb-log/error ::tx-failed ex)
(sb-log/error ::tx-failed ex {:tx-id 17592186})
(sb-log/error ::tx-failed ex {:tx-id 17592186} :db-only)

;; --- Tufte profiling span (no memorial flag) ---
(sb-log/profile :search-hot-path
  (do-the-search query))
```

The five level-keyed macros (`info` / `warn` / `debug` / `trace` / `error`) each accept four arities.  The matrix:

| Arity | Form | Macroexpansion shape |
|-------|------|----------------------|
| 1 | `(info ::id)` | bare structured marker — `{:level :info :id ::id}` |
| 1 | `(info "msg")` | human shorthand — `{:level :info :id <synthetic> :msg "msg"}` |
| 2 | `(info ::id data-map)` | structured + data |
| 2 | `(info ::id "msg")` | structured + message |
| 2 | `(info ::id :memorial-flag)` | flag with no other payload |
| 2 | `(info "msg" data-map)` | human shorthand + data |
| 3 | `(info ::id data :memorial)` | data with flag |
| 3 | `(info ::id "msg" data)` | message + data |
| 4 | `(info ::id "msg" data :flag)` | full form |

The `error` macro reserves its 2-arg slot for a `Throwable` (the Telemere convention for typed `:error`-kind signals) — `(sb-log/error ::tx-failed ex)` dispatches to `tel/error!` so error-aware handlers can distinguish it from `:log`-kind signals.

The `profile` macro is the Tufte hook.  Its body's value is returned untouched; profile reports are emitted as Telemere signals through the same handler chain, so per-span timings appear in the same log file as every other event.  No memorial flag — profile spans are pure measurement.

### The memorial flag

Three keyword values carry meaning when supplied as the final positional argument:

| Flag           | Routing                                                                                    |
|----------------|--------------------------------------------------------------------------------------------|
| `:db-only`     | Operational audit — creates a `:event/SystemEvent` runtime entity under `:dt/Event`        |
| `:first-class` | Narrative event — creates a `:mm/EventLog` memorial; reactive sink projects to FS          |
| `:inline`      | Embedded in a host memorial's frontmatter (deferred; not in MVP)                           |

Absent flag means *transient* — handlers fire, console + file emit, but no DB persistence.  This is the majority case.  Memorial promotion is opt-in at the callsite; the substrate stays out of the way otherwise.

The rationale for the final-positional form is in [`decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md`](../../memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md): the keyword is semantically separate from the payload data; it stands out at callsites; it future-proofs against new policies without a new macro.

### Human-string shorthand and synthetic event-ids

`(sb-log/info "Datomic transactor reconnected")` is allowed and ergonomically preferred for opportunistic debug-style callsites.  At macroexpansion time, the form's `*ns*` and line metadata combine with a hash of the message string to derive a synthetic stable event-id of the shape `::sandbar.ns.foo/line-42-a8c91f3b`.  Anonymous-at-callsite events remain rankable and facetable post-hoc — the substrate compensates for the ergonomic shorthand by manufacturing the structure the consumer didn't bother to supply.

This is the load-bearing posture: **the substrate absorbs complexity so consumers don't have to.**  Logging is one instance; the same posture applies to `sandbar.entity-ref`, to the `dt/*` primitives, to the MCP verb surface — the substrate gives up internal simplicity to keep callsites uncluttered.

## Initialization — `sandbar.logging.init/start!`

Telemere is reconfigured at exactly one site: `sandbar.logging.init/start!`.  This is the canonical reconfiguration point, called as the first action of `sandbar.core/start` before any other startup work.  Configuration drift — the failure mode where multiple places in the codebase set Telemere state at different times — is foreclosed structurally rather than by convention.

The startup sequence:

```clojure
(defn start! []
  (logging-config/load!)         ; (1) read resources/logging.edn; apply via Telemere setters
  (install-noise-filter!)        ; (2) install sandbar.logging.format/middleware as :xfn
  (let [path (install-handlers!)] ; (3) install the three sandbar handlers
    (log/info :SANDBAR.LOGGING/STARTED
              {:log-file  path
               :handlers  (vec (keys (tel/get-handlers)))
               :substrate "telemere v1.2.1 + slf4j-telemere v1.0.0-beta21"})
    path))
```

The three sandbar handlers are:

| Handler                          | Async policy                                  | Output                                     |
|----------------------------------|-----------------------------------------------|--------------------------------------------|
| `:sandbar/console`               | sync (`:async nil`)                           | stdout, captured to `.sandbar/sandbar.log` |
| `:sandbar/file`                  | dropping buffer of 4096                       | `.sandbar/logs/sandbar.log` (rolling, gzip)|
| `:sandbar/memorial-projection`   | sync — `sandbar.reactive` owns async          | substrate entity creation                  |

The file handler does daily rotation with 4 MiB part-cap, eight parts per interval, six retained intervals, gzip on archive — `taoensso.telemere/handler:file` provides this out of the box; sandbar configures the policy.  The log file path is read from `SANDBAR_LOG_FILE` env-var or computed as `<SANDBAR_CLIENT_DIR or $HOME/claude>/.sandbar/logs/sandbar.log`.

Each handler shares the same output function — a Telemere `format-signal-fn` built around the kiwi preamble but with the short timestamp formatter slotted in.  Console and file see the same rendered text; the file is the durable longitudinal archive.

The EDN config wrapper (`sandbar.logging.config`) reads `resources/logging.edn` (or `$SANDBAR_LOGGING_CONFIG` for ops override) and applies the parsed spec via Telemere's setters — `set-min-level!`, `set-ns-filter!`, `set-id-filter!`, `set-kind-filter!`, `set-ctx!`.  The schema is lispy-clojurey by design; no XML.

## Formatter curation — `sandbar.logging.format/middleware`

Telemere's default output is verbose-in-a-helpful-way for an empty project; in a real deployment with Datomic, Pedestal, and SLF4J-bridged Java callers, the defaults produce paragraphs of noise around every interesting event.  The `sandbar.logging.format/middleware` namespace installs as Telemere's `:xfn` — a signal-level transformation that runs once before any handler dispatch — and applies four curations.

### 1. Short timestamps

Telemere's default `format-inst-fn` produces full ISO with microseconds (`2026-05-24T01:32:18.883135Z`).  The microsecond tail contributes noise without operational value at the human-scan timescale.  `short-inst-fn` produces ISO 8601 with millisecond precision (`2026-05-23T22:20:16.711Z`) — full date and UTC suffix preserved, microsecond noise dropped.

### 2. SLF4J source attribution restoration

The SLF4J bridge (`slf4j-telemere`) routes Java-side callers — Datomic peer, Jetty, Pedestal — into Telemere.  The original logger name (e.g., `"datomic.peer"`, `"sandbar.core"`) lives in the signal's `:location {:ns logger-name}` field; the top-level `:ns` is the compile-time capture from where the bridge's own `signal!` expanded, *always* `"taoensso.telemere.slf4j"` for bridge-routed signals.

The kiwi preamble reads `:ns` for source attribution by default — which, for bridge-routed signals, displays `taoensso.telemere.slf4j[115,3]` instead of the real logger name.  The middleware's `restore-slf4j-source` fn rewrites `:ns ← :location :ns` for `:kind :slf4j` signals and clears `:coords` and `:kind` to avoid the per-line `SLF4J` token repetition and the bridge-code line/column.  The result: `[datomic.peer]` where there used to be `[taoensso.telemere.slf4j[115,3]]`.

The signal-shape provenance is documented in [`observations/slf4j_telemere_signal_shape_logger_name_lives_in_location_ns_not_ns_2026_05_23.md`](../../memory/observations/slf4j_telemere_signal_shape_logger_name_lives_in_location_ns_not_ns_2026_05_23.md) — found empirically by reading the `slf4j-telemere` v1.0.0-beta21 source from the `~/.m2/` jar.

### 3. Periodic-metrics drop

Datomic emits a `:MetricsReport` signal every second carrying gauges like `:AvailableMB`, `:ObjectCacheCount`, etc.  At normal operating tempo, this is one in five lines of log output; in 99% of sessions the report contributes nothing the operator wanted to know.  The middleware drops it via a narrow predicate that only matches signals carrying `:MetricsReport` in `:data` — other Datomic events still flow through, per the operator preference to keep general Datomic logging.

The `:ctx` and `:host` slots are also stripped from every signal.  The SLF4J bridge stamps MDC `{"pid" "17511"}` into every bridged signal's `:ctx`; Telemere stamps the local hostname (`"kiwi"`) into every signal's `:host`.  Neither contributes information the operator couldn't infer from the process they started; both add visual mass to every line.

### 4. HTTP response body truncation

The `:HTTP/RESPONSE` callsite in `sandbar.service.content` logs the full response payload as structured data.  An MCP `entity.find` call can return a 50 KiB memorial body; logging it verbatim drowns the line.  Pre-truncating at the callsite with `*print-level* 2` (the prior policy) reduced the body to a meaningless `#` — too aggressive in the other direction.

The middle ground: leave `:route` and `:status` intact; replace `:msg` with a length-bounded preview (500 chars) plus a `…(+N ch)` suffix that names the overflow size.  Response shape stays identifiable; the bulk goes away.

## Memorial projection — `sandbar.logging.handlers/memorial-projection-handler`

Stage D of the logging arc.  Implemented and live as of 2026-05-23.

The handler is installed by `start!` alongside `:sandbar/console` and `:sandbar/file`.  Its responsibility: observe signals carrying `:data {:memorial <policy>}` and create the appropriate substrate entity per the routing table.

```clojure
(case policy
  :first-class  (dt/make :mm/EventLog       (signal->event-log-spec signal))
  :db-only      (dt/make :event/SystemEvent (signal->server-event-spec signal))
  :inline       nil  ; deferred — Stage D MVP scope
  nil)             ; absent flag — no-op
```

The signal-to-entity translation populates the PROV-O Activity slot family on `:mm/EventLog` — `:mm.activity/started-at`, `:mm.activity/ended-at`, `:mm.activity/status` — plus the event-log-specific slots — `:mm.event-log/level`, `:mm.event-log/signal-id`, `:mm.event-log/source-ns`, `:mm.event-log/msg`, `:mm.event-log/data`.  The `:memorial` flag is dropped from `:data` before storage (it was a routing hint; not part of the durable payload).

The `:event/SystemEvent` translation uses the existing `:dt/Event` substrate-runtime hierarchy — compatible with `sandbar.util.event` — and stamps `:event/kind :memorial/db-only` so consumers can distinguish memorial-flagged signals from other system events.

The composition with the reactive substrate is what makes `:first-class` concrete.  `:mm/EventLog` carries `:dt/memorial-policy :first-class` (per [`decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md`](../../memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md) §4.4); the existing tx-report-queue-subscribing sink sees the `:mm/EventLog` mutation, looks up the class's memorial-policy, and writes `memory/event-logs/<>.md`.  The logging handler doesn't know about projection; projection doesn't know about logging.  Both compose through the `:dt/memorial-policy` axis and the reactive substrate's existing operation.

The PROV-O lift means that *logs, scheduled-job runs, and session chronicles are the same shape*.  All three are `:mm/Activity` subclasses with shared interval semantics (`:mm.activity/started-at` + `:mm.activity/ended-at`).  A query like *"what activity was informed by this failed scheduler run?"* walks `:mm.activity/was-informed-by` uniformly across `:mm/Log`, `:mm/EventLog`, and `:mm/Run`.  Cross-arc unification at the type system level.

## The anti-cycle five-layer defense

Logging that triggers reactions that trigger logging that triggers reactions is a system that has been built before.  Sandbar prevents the loop structurally, in five layers, each independently sufficient for the cases it covers.

### Layer 1 — Async decoupling at the publisher boundary

The `:sandbar/file` handler runs with `:async {:mode :dropping :buffer-size 4096}`.  The callsite returns in <500ns regardless of handler load; a dedicated background thread drains the ring buffer.  The logger's call stack is never re-entered by the handler.  The shape is the same one Telemere uses by default for its bundled handlers; sandbar just keeps the explicit buffer size visible.

### Layer 2 — Thread-local reentry guard

`sandbar.logging.handlers/*log-substrate-active?*` is a dynamic var set to `true` on memorial-projection-handler entry; the handler checks it at the top of its body and early-exits if already set.  Same pattern as log4j2's `LOG4J2-2738` recursion guard.  The guard protects against intra-thread recursion that bypasses the async boundary.

### Layer 3 — Bounded buffer with drop-on-overflow

Per-handler `:async` policy declares the buffer size and overflow mode.  The file handler's `:dropping` mode drops the oldest signal on buffer full; alternatives are `:blocking` (back-pressure into the callsite) and `:sliding` (drop the newest, keep the oldest).  Sandbar substrate cannot grow memory unboundedly under sustained backpressure; if the disk is slow or the consumer is paused, the log loses events rather than the process losing memory.

### Layer 4 — Class-skip-list inheritance

The reactive substrate (`sandbar.reactive`) maintains a skip-list of classes whose mutations should not retrigger reactive projection.  `sandbar.logging.handlers` is in that skip-list via the reentry guard — when the memorial-projection-handler is on-stack, the reactive sink that picks up its own newly-created `:mm/EventLog` knows not to re-emit any further events from that fan-out.

### Layer 5 — Caller dynamic-binding opt-out

Callers may suppress memorial-projection for a scope:

```clojure
(binding [sandbar.logging.handlers/*log-substrate-active?* true]
  ;; Any sb-log/info calls in this scope with :memorial flags
  ;; are logged + emitted to file but NOT projected to substrate
  (bulk-import-thousands-of-entities!))
```

The intended use is bulk import or replay scenarios where each underlying operation would otherwise spawn a `:mm/EventLog` and the cumulative substrate cost would be operationally unhelpful.  The op-out is explicit and lexically scoped; nothing depends on global toggle state.

The industry parallel is OpenTelemetry's *no-instrument marker* on the current span/context — nested instrumentation under that marker returns no-op.  Same pattern, different tradition.

## How it composes

The logging substrate is not a feature; it is the observability floor every other sandbar subsystem stands on.

- **Memory model** ([`memory-model.md`](memory-model.md)) — `:mm/EventLog` and `:mm/Log` are concrete `:mm/Activity` subclasses; the PROV-O slot vocabulary they inherit comes from the activity-lift ADR.  Memorial promotion *is* the bridge into the memory model.
- **Projection** ([`projection.md`](projection.md)) — the reactive sink subscribes to the Datomic tx-report queue; `:mm/EventLog` instances carry `:first-class` policy and reach the filesystem automatically.  The handler doesn't know about projection; projection doesn't know about the handler.
- **Event substrate** ([`event-substrate.md`](event-substrate.md)) — the `:db-only` path uses `:event/SystemEvent` under `:dt/Event`, compatible with `sandbar.util.event/log-event!` and the existing per-Pedestal-request `:event/HttpRequest` machinery.  Two memorial-policy faces, one event substrate.
- **First-class function substrate** — Tufte profile reports compose through the same handler chain as every other signal, so per-span timings appear inline with the events that produced them.  Profile spans of Datomic `:tx-fn` invocations are captured uniformly with profile spans of `sandbar.search/search-bm25f`.
- **Workflow substrate** ([`workflow-substrate.md`](workflow-substrate.md)) — workflow transitions emit Telemere signals; flagged `:memorial :db-only`, they materialize as `:event/SystemEvent` instances correlated with the transition's `:workflow/History` entry.

The throughline: **structured signals are the operational language; the substrate gives them durable face when worth keeping.**

## A note on humility

The substrate compensates for ergonomic shorthand by manufacturing event-ids, restoring source attribution, dropping noise, truncating bodies, projecting flagged signals — but the substrate cannot synthesize meaning the callsite never provided.  A signal of the shape `:sinks-succeeded 2` is not high-signal even with the perfect formatter.  Authors retain craft responsibility for the message body: *who did what to what, and how did it go.*  See [`interaction/logs_must_be_self_explanatory_high_signal_not_kv_dump_2026_05_23.md`](../../memory/interaction/logs_must_be_self_explanatory_high_signal_not_kv_dump_2026_05_23.md) for the standing rule.

## References

**Telemere — the engine**

- Taoussanis, P. (2024–). *Telemere — pure-Clojure signal-as-data observability.* https://github.com/taoensso/telemere.  v1.2.1 as of 2025-12-16.  EPL-1.0.
- Taoussanis, P. (2024–). *slf4j-telemere — SLF4J v2 bridge.* https://github.com/taoensso/slf4j-telemere.  v1.0.0-beta21 as of 2026.
- Taoussanis, P. (2024–). *Tufte — profiling, Telemere-integrated.* https://github.com/taoensso/tufte.  v3.0.x.

**PROV-O provenance ontology** (the `:mm/Activity` vocabulary)

- Lebo, T., Sahoo, S. & McGuinness, D. (eds.) (2013). *PROV-O: The PROV Ontology.* W3C Recommendation.  https://www.w3.org/TR/prov-o/

**OpenTelemetry — sibling discipline for no-instrument markers**

- Cloud Native Computing Foundation (2019–). *OpenTelemetry specification.*  https://opentelemetry.io/docs/specs/

**SLF4J — the upstream Java bridge surface**

- Gülcü, C. (2004–). *Simple Logging Facade for Java (SLF4J).*  https://www.slf4j.org/.  v2.0 introduced fluent + event-builder APIs the bridge consumes.

**Allen's interval algebra** — composes over `:mm.activity/*` intervals

- Allen, J.F. (1983). *Maintaining knowledge about temporal intervals.* Communications of the ACM, 26(11), 832–843.

## See also

- [`memory-model.md`](memory-model.md) — the `:mm/Activity` PROV-O hierarchy this handler lands into; `:mm/EventLog` vs `:mm/Log` vs `:mm/Run`
- [`projection.md`](projection.md) — the reactive sink that gives `:first-class` memorials their filesystem face
- [`event-substrate.md`](event-substrate.md) — the in-process event bus that `:event/SystemEvent` flows through
- [`workflow-substrate.md`](workflow-substrate.md) — workflow transitions emit signals through this substrate
- [`metamodel.md`](metamodel.md) — `:dt/memorial-policy` is the class-level axis that distinguishes `:first-class` from `:db-only` from `:inline`
- [`memory/plans/sandbar_logging_discipline_and_strategy_arc_telemere_primary_csp_signals_anti_cycle_disciplines_2026_05_23.md`](../../memory/plans/sandbar_logging_discipline_and_strategy_arc_telemere_primary_csp_signals_anti_cycle_disciplines_2026_05_23.md) — the comprehensive arc plan
- [`memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md`](../../memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md) — the ratified 6-macro callsite shape
- [`memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md`](../../memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md) — the cross-arc PROV-O lift that unified `:mm/Log` / `:mm/EventLog` / `:mm/Run`
- [`memory/interaction/observability_callsite_api_must_be_one_convenience_layer_not_multiple_vendor_macros_2026_05_23.md`](../../memory/interaction/observability_callsite_api_must_be_one_convenience_layer_not_multiple_vendor_macros_2026_05_23.md) — the Dan-correction that drove the single-namespace shape
- [`memory/interaction/logs_must_be_self_explanatory_high_signal_not_kv_dump_2026_05_23.md`](../../memory/interaction/logs_must_be_self_explanatory_high_signal_not_kv_dump_2026_05_23.md) — the standing rule on callsite craft
- [`memory/observations/slf4j_telemere_signal_shape_logger_name_lives_in_location_ns_not_ns_2026_05_23.md`](../../memory/observations/slf4j_telemere_signal_shape_logger_name_lives_in_location_ns_not_ns_2026_05_23.md) — the empirical signal-shape finding underneath the SLF4J source restoration
- [`memory/libraries/clojure/taoensso/telemere.md`](../../memory/libraries/clojure/taoensso/telemere.md) — the Telemere library study
