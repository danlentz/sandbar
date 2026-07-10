# The Temporal Substrate

> Sandbar's memory model for temporal knowledge — recurrence patterns, time intervals, and provenance lineage — aligned with three formal ontologies (RFC 5545 RRULE, OWL-Time, PROV-O) and projected through one JSON-LD interchange shape (Schema.org Schedule).  `:mm/Schedule` carries recurrence; `:mm/Job` carries the plan; `:mm/Run` carries the activity; `:workflow/Process` carries multi-step state.  Scheduling is one application of the substrate — periodic DB-dumps, health-checks, audit cadences, and Tufte profile snapshots are others.  The dispatcher engine (native loop vs. Cronut/Quartz) is replaceable behind the boundary; the ontology layer is permanent.  For the activity supertype these subclasses inherit from, see [`memory-model.md`](memory-model.md); for the workflow substrate that handles multi-step coordination, see [`workflow-substrate.md`](workflow-substrate.md); for the event bus that carries trigger notifications, see [`event-substrate.md`](event-substrate.md).

## Thesis

Schedulers are the symptom; temporal knowledge is the substrate.

The naive shape is to ship a scheduler — a cron-like dispatcher with job tables, trigger times, and a worker loop.  Every system grows one.  Each grows its own status model, its own retry semantics, its own audit trail; each speaks cron, or quartz-XML, or some bespoke DSL.  The temporal *meaning* — that this job recurs weekly on Tuesdays at 3pm New York time, that it ran during a maintenance window, that it was caused by an upstream activity completing — lives nowhere except in operator memory and tribal lore.

Sandbar's commitment: **temporal knowledge — recurrence, intervals, provenance — is modeled in the corpus as first-class typed entities aligned with formal ontologies, and scheduling is what we do with that knowledge.**  A `:mm/Schedule` IS the recurrence pattern (RFC 5545 RRULE, IANA timezone, optional natural-language source).  A `:mm/Run` IS the PROV-O Activity that records *something happened*.  Allen's thirteen interval relations apply between any two activities, not just two schedules.  When the implementation layer needs to ship — when an actual dispatcher has to compute next-fire-at and invoke a handler — that's an engineering choice we can make later and revisit, because the corpus expresses what the system *means* independently of which Java library happens to drive the clock.

This reframes a familiar problem.  Scheduler arcs typically begin with "pick Quartz or roll our own"; this one begins with "model the temporal vocabulary, then pick a dispatcher."  The implementation differential between Quartz and a 200-line native loop matters less than the difference between *having* `:mm/Schedule` entities your AI assistant can introspect, edit, and reason about — and *not* having them.

The scheduler implementation is live (γ arc, closed 2026-05-27): the native min-heap dispatcher (`sandbar.schedule.dispatcher` + `sandbar.schedule.job-dispatcher`, with `recurrence` / `state` / `system` siblings) fires `:mm.event/Scheduled` on the event bus, and the `sandbar.schedule.*` MCP verb axis (8 verbs: `enable` / `disable` / `start` / `stop` / `add` / `remove` / `list` / `inspect`) exposes it; boot autostart is config-opt-in (`:scheduler {:enabled? true}`).  The arc plan ([`memory.plans/sandbar_temporal_substrate_arc_owl_time_rrule_prov_o_aligned_schedule_job_run_2026_05_23`](../../memory/plans/sandbar_temporal_substrate_arc_owl_time_rrule_prov_o_aligned_schedule_job_run_2026_05_23.md)) lays out ten stages from schema authoring through dispatch substrate through cross-tool JSON-LD projection.  The PROV-O Activity lift — the supertype the temporal substrate depends on for its provenance semantics — *is* landed; `:mm/Activity` exists in the schema today alongside its three concrete subclasses (`:mm/Log`, `:mm/EventLog`, `:mm/Run`), per the D+++ ADR ([`memory.decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23`](../../memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md)).

## Four ontology alignments

The substrate composes four formal vocabularies, each load-bearing in a distinct dimension.

### RFC 5545 RRULE — recurrence (IETF Internet Standard, 2009)

The de-facto universal recurrence codec.  Every calendar application, every natural-language scheduling parser, every operating-system scheduler with non-trivial recurrence semantics normalizes its temporal patterns *to* RRULE.  The grammar is compact and the semantics are unambiguous:

```
FREQ=WEEKLY;BYDAY=TU;BYHOUR=15
```

— *every Tuesday at 15:00 local time*, anchored at a `DTSTART` instant in an IANA-timezone-bound calendar.  RRULE composes additional clauses (`COUNT`, `UNTIL`, `BYMONTHDAY`, `BYSETPOS`) and exception-date lists (`RDATE` / `EXDATE`) for irregularities.  RSCALE (RFC 7529) extends it for non-Gregorian calendars.

Sandbar stores the RRULE string directly on `:mm.schedule/rrule` as the canonical recurrence form.  Iteration is delegated to [lib-recur](https://github.com/dmfs/lib-recur) — layered behind the `sandbar.schedule.recurrence` namespace per the boundary-pragmatism discipline; consumers see Clojure lazy sequences of `java.time.Instant`, never `org.dmfs.*` types.

What RRULE doesn't model: temporal *topology* — the relationships between intervals.  OWL-Time fills that gap.

### OWL-Time — intervals and Allen relations (W3C Recommendation, 2017)

At `http://www.w3.org/2006/time#`.  The W3C ontology for temporal entities: instants, intervals, durations, and the thirteen interval relations identified by Allen (1983) as the complete and mutually-exclusive vocabulary for describing how two intervals on a linear time relate.

| Relation        | Meaning                                                                          |
|-----------------|----------------------------------------------------------------------------------|
| `before`        | A's end precedes B's start (no overlap)                                          |
| `after`         | inverse of `before`                                                              |
| `meets`         | A's end equals B's start                                                         |
| `met-by`        | inverse                                                                          |
| `overlaps`      | A's interval overlaps B's start                                                  |
| `overlapped-by` | inverse                                                                          |
| `during`        | A is strictly contained in B                                                     |
| `contains`      | inverse                                                                          |
| `starts`        | A's start equals B's start; A ends earlier                                       |
| `started-by`    | inverse                                                                          |
| `finishes`      | A's end equals B's end; A starts later                                           |
| `finished-by`   | inverse                                                                          |
| `equals`        | symmetric; A's interval equals B's                                               |

Sandbar adopts these as first-class typed-edge predicates between intervals.  Per the D5 clause of the PROV-O activity-lift ADR, the predicates apply at the `:mm/Activity` level (which all carry an interval via `:mm.activity/started-at` + `:mm.activity/ended-at`), not solely at the `:mm/Schedule` level.  The arc plan authors them as `:schedule.before/`, `:schedule.during/`, etc. in Stage B; future work generalizes to `:activity.*` predicates over arbitrary intervals.

What this enables is a vocabulary for cross-arc topology questions — *which event-log entries occurred during this scheduler run?  what activity was informed by the log entry that caused the failed run?  which two schedules meet back-to-back?* — answerable as one Datalog walk over typed edges, not as out-of-band computation against timestamps.

### PROV-O — provenance (W3C Recommendation, 2013)

At `http://www.w3.org/ns/prov#`.  The W3C ontology for provenance: the Entity/Activity/Agent trinity that captures *who did what to which entities when*.

A `prov:Activity` is something that occurred over a period of time and acted upon or with entities; it has start and end times, it was associated with an agent, and it was informed by upstream activities.  `prov:wasInformedBy` is the causal-lineage predicate; `prov:used` records inputs; `prov:wasGeneratedBy` records outputs.

The substrate's `:mm/Activity` supertype (already landed) declares the PROV-O slot vocabulary at the abstract level:

| Slot                            | PROV-O term                      |
|---------------------------------|----------------------------------|
| `:mm.activity/started-at`       | `prov:startedAtTime`             |
| `:mm.activity/ended-at`         | `prov:endedAtTime`               |
| `:mm.activity/agent`            | `prov:wasAssociatedWith`         |
| `:mm.activity/was-informed-by`  | `prov:wasInformedBy`             |
| `:mm.activity/generated`        | `prov:wasGeneratedBy` (inverse)  |
| `:mm.activity/used`             | `prov:used`                      |
| `:mm.activity/status`           | (sandbar extension)              |

`:mm/Run` inherits these.  When the dispatcher fires a job, the `:mm/Run` instance carries the start instant, the end instant, the agent (the dispatcher actor), the upstream activities (the schedule that triggered it; the parent run if a sub-run), and the entities it consumed and generated.  Querying *"what runs were caused by this maintenance event?"* is a `:mm.activity/was-informed-by` traversal.  Querying *"what runs produced this output entity?"* is a `:mm.activity/generated` inverse traversal.

`:mm/Job` is `prov:Plan`-shaped — a static description of intended actions.  `prov:hadPlan` is implicit via the `:mm.run/job` reference.

There is no Clojure-native PROV-O library; sandbar adopts the vocabulary directly via sandbar-typed slots that mirror PROV-O semantics.  This is sanctioned by the ontology-alignment ADR ([`memory.decisions/sandbar_ontology_alignment_is_the_goal_implementation_is_replaceable_*`](../../memory/decisions/sandbar_ontology_alignment_is_the_goal_implementation_is_replaceable_boundary_pragmatism_when_layered_or_seamless_2026_05_23.md)): sandbar-typed vocabulary that mirrors a formal ontology may pass through the architectural boundary freely, because the slot semantics are sandbar's by construction even though the meaning aligns with the published W3C vocabulary.

### Schema.org Schedule — JSON-LD projection (Schema.org, ongoing)

At `http://schema.org/Schedule`.  A flattened JSON-LD shape covering the common RRULE-like vocabulary — `repeatFrequency`, `byDay`, `byMonth`, `byMonthDay`, `byMonthWeek`, `startDate`, `endDate`, `exceptDate`, `repeatCount`.

This is not sandbar's primary representation — RRULE remains canonical on `:mm.schedule/rrule` — but it is the natural interchange shape when a schedule needs to round-trip to a calendar application, an embedded JSON-LD island in a web page, or a tool consuming Schema.org structured data.  The arc plan's Stage I authors a `sandbar.schedule.json-ld` codec that converts in both directions.

### Why four

Each ontology covers a dimension the others leave open.

| Ontology              | What it models                       | What it leaves out                          |
|-----------------------|--------------------------------------|---------------------------------------------|
| RFC 5545 RRULE        | recurrence patterns                  | topology between intervals; provenance      |
| OWL-Time              | intervals + Allen relations          | recurrence (defers to RRULE)                |
| PROV-O                | activities + agents + lineage        | recurrence; topology                        |
| Schema.org Schedule   | JSON-LD interchange                  | not a semantic model — projection only      |

The substrate composes all four.  A `:mm/Schedule` carries an RRULE for recurrence and a `:mm.schedule/timezone` for IANA-bound interpretation; the `:mm/Run` instances it triggers carry PROV-O lineage; the Allen predicates relate any two intervals (schedule windows, completed runs, log periods) uniformly; and any of these can project to Schema.org JSON-LD when external tools need it.

## The four first-class entities

Four classes anchor the temporal substrate.  `:mm/Schedule` and `:mm/Job` were new in the temporal arc (authored in `schema/mm-temporal.edn`); `:mm/Run` predates it (the PROV-O Activity lift in `schema/mm-artifact.edn`, with the temporal arc adding new `:mm.run/*` slots); one is reused.

### `:mm/Schedule` — three-layer expression

A recurrence pattern with anchor, timezone, and policies.  Memorial-classifier under `:mm/Meta → :mm/Memory`.  Default policy `:first-class` (FS-projected; humans + AI can read + author them).

```clojure
{:db/ident :mm/Schedule
 ;; INPUT (optional originals; codec normalizes)
 :mm.schedule/source-nl        "every Tuesday at 3pm starting next Friday"
 :mm.schedule/source-cron      "0 15 * * 2"
 ;; NORMALIZED (always present)
 :mm.schedule/rrule            "FREQ=WEEKLY;BYDAY=TU;BYHOUR=15"
 :mm.schedule/dtstart          #inst "2026-05-29T15:00:00Z"
 :mm.schedule/until            nil
 :mm.schedule/timezone         "America/New_York"
 :mm.schedule/rdates           []
 :mm.schedule/exdates          []
 ;; PROJECTED (precomputed; refreshable from RRULE)
 :mm.schedule/next-fire-at     #inst "..."
 :mm.schedule/preview-instants [#inst "..." ...]
 ;; Policy
 :mm.schedule/misfire-policy   :misfire/fire-once-now
 :mm.schedule/concurrency      :concurrency/forbid}
```

Three layers per schedule.  The *input* layer accepts a natural-language phrase, a cron expression, or a direct RRULE — humans choose the expression mode that fits their mental model.  The *normalized* layer holds the canonical RRULE string + anchor + IANA timezone, always present, computed by the codec when only NL or cron was supplied.  The *projected* layer carries precomputed next-fire-at and a preview list, refreshable from the RRULE on demand or on entity mutation.

The IANA timezone is required at schema-validate time, not advisory.  Naive cron rules silently break across DST transitions; the substrate refuses to express a schedule without a zoneid.

### `:mm/Job` — the plan being scheduled

The handler reference and runtime arguments — what *runs* when a schedule fires.  Default policy `:first-class`.

```clojure
{:db/ident :mm/Job
 :mm.job/handler        :sandbar.handler/dump-db-only
 :mm.job/data-map       {...}
 :mm.job/schedules      [<schedule-ref> ...]            ; M:N
 :mm.job/depends-on     [<job-ref> ...]                 ; DAG predecessors
 :mm.job/timeout-ms     30000
 :mm.job/retries        3
 :mm.job/retry-backoff  :backoff/exponential
 :mm.job/idempotency-key-fn nil}
```

Many-to-many between jobs and schedules: one job can fire on multiple schedules (a backup runs nightly *and* on demand); one schedule can drive multiple jobs (a maintenance window triggers a cluster of audits).  The DAG predecessor slot enables job-chains where one completion gates another's start.

This separation — schedule from job, matching Quartz's Trigger/JobDetail distinction — is deliberate.  Per Vernon's bounded-context discipline, recurrence patterns are reusable across actions; conflating them collapses that reuse.

### `:mm/Run` — PROV-O Activity-shaped

A record of a single execution.  Concrete subclass of `:mm/Activity` (landed today); inherits the PROV-O slot family.  Default policy `:db-only` for high-volume operational runs; narrative runs (a quarterly audit producing a memorial) can opt into `:first-class` per-instance.

```clojure
{:db/ident :mm/Run
 ;; Inherited from :mm/Activity (PROV-O)
 :mm.activity/started-at      #inst "..."
 :mm.activity/ended-at        #inst "..."
 :mm.activity/agent           <ref to :mm/Actor>
 :mm.activity/was-informed-by [<activity-ref> ...]
 :mm.activity/generated       [<entity-ref> ...]
 :mm.activity/used            [<entity-ref> ...]
 :mm.activity/status          :running
 ;; Run-specific
 :mm.run/job                  <ref to :mm/Job>
 :mm.run/triggered-by         <ref to :mm/Schedule>
 :mm.run/attempt-number       1
 :mm.run/output               "..."
 :mm.run/parent-run           <ref to :mm/Run>}
```

The PROV-O inheritance is what makes a `:mm/Run` queryable in the same shape as a `:mm/Log` (session chronicle) or a `:mm/EventLog` (event-firing memorial).  *"What activities ran during this maintenance window?"* is one query against `:mm/Activity` filtered by interval, returning all three subclasses uniformly.  This composition payoff is the whole reason `:mm/Activity` was lifted as a supertype rather than `:mm/Run` being a standalone direct-child of `:mm/Artifact`.

### `:workflow/Process` — the Process Manager (reused)

Multi-step jobs that need state-machine semantics — *do A, wait for X, then B if Y else C* — reference an existing `:mm/Workflow` definition through `:mm/Job`.  The `:mm/Run` carries `:mm.run/workflow-process` pointing at the in-flight `:workflow/Process`.  No new primitive; the workflow substrate is the Process Manager.

See [`workflow-substrate.md`](workflow-substrate.md) for the workflow concept's full account.  The composition is bidirectional: scheduled jobs that need state-machine coordination drive workflow processes; workflow transitions can themselves trigger scheduled follow-up jobs.

## Allen's relations as typed-edge predicates

The thirteen relations earn their first-class status because they unlock cross-arc queries that would otherwise require ad-hoc timestamp arithmetic.

Consider a corpus with:

- A `:mm/Schedule` named `maintenance-window` covering the third Sunday of each month, 02:00–06:00 America/New_York
- A `:mm/Job` `schema-migration` scheduled by a one-off `:mm/Schedule` for a specific Sunday in that month
- Several `:mm/Run` instances from prior months recording the migration's execution history
- A `:mm/EventLog` stream of cluster-health events recorded during each window

The question *"does the upcoming schema-migration run fall during a maintenance window?"* is one `:schedule.during/` traversal.  The question *"which event-log entries were recorded during the most-recent successful migration run?"* is a `:schedule.during/` traversal at the `:mm/Activity` level, returning all `:mm/EventLog` instances whose interval is contained within the run's interval.  The question *"do these two jobs' schedules meet back-to-back?"* is `:schedule.meets/`.

The predicates also compose into constraints.  A rule asserting *"schema migrations must run only during maintenance windows"* becomes a Datalog query that flags any `:mm/Schedule` for a migration whose interval is *not* `:schedule.during/` a `maintenance-window` interval.  The substrate has the predicates; the rules arc consumes them; the operator sees a structural violation rather than a runtime surprise.

Allen relations form a constraint algebra: thirteen exhaustive and mutually-exclusive labels over any pair of proper intervals, with composition tables giving the relations that hold transitively across three intervals.  Path-consistency reasoning becomes possible — given a partial set of asserted Allen relations, infer the closure.  Apache Jena does not provide OWL-Time-specific tooling; sandbar's plan implements the inference via `defrule` Datalog rules composed with the existing `dt/*` substrate (Stage G of the arc plan).

The future generalization to `:activity.*` predicates (per the D5 clause of the PROV-O activity-lift ADR) takes this further: Allen relations between any two activities, regardless of whether they're schedules, runs, logs, or event-logs.  Not done in the current arc — the scheduler arc authors `:schedule.*` first — but the abstraction layer is set up for it.

## The replaceable engine

Per the ontology-alignment ADR, the dispatcher engine is replaceable behind the substrate boundary.  Two paths are viable; the arc plan recommends Path A for MVP.

### Path A — native dispatcher atop sandbar.event

A ~200-LOC scheduler walking `:mm/Schedule` entities, computing next-fire-at via `sandbar.schedule.recurrence` (lib-recur wrapper), and driving a priority queue.  On fire, the dispatcher emits a `:mm.event/Scheduled` event into the event bus; reactive subscribers (`sandbar.schedule.job-dispatcher`) handle the actual job invocation, `:mm/Run` lifecycle, retry logic, and concurrency policy.

What this requires sandbar to own: misfire policies (the choices when a fire instant was missed due to JVM downtime), clock-drift detection (comparing wall-clock against monotonic; alerting on drift beyond a threshold), startup recovery (walking schedules and populating the priority queue), and shutdown drain (persisting current next-fire-at per schedule).  Each is bounded — ~50 LOC each — and composes naturally with the sandbar.event substrate.

### Path B — Cronut wrapping Quartz

Add [factorhouse/cronut](https://github.com/factorhouse/cronut), which wraps the Quartz Scheduler (the canonical JVM scheduler, mature since 2001).  Translate `:mm/Schedule` + `:mm/Job` entities into Cronut's job-map at startup and on entity-mutation.  Quartz fires; a sandbar handler creates the `:mm/Run` instance and emits the corresponding `:mm.event/Scheduled` event onto the bus.

What this trades: vendor dependency on Quartz; an unused parallel data model (Datomic is the source of truth; Quartz's internal job store is empty); a translation layer on startup and per-mutation.  What it gains: battle-tested misfire policies, cluster-safe scheduling across multiple sandbar instances, decades of Quartz-specific operational knowledge.

### The ontology-alignment dividend

Either path is viable because the ontology layer is what makes sandbar valuable.  The OWL-Time / RFC 5545 / PROV-O alignment + the memorial-resident control plane is permanent; the engine driving the clock is part of the substrate boundary.  Swapping Quartz for a native loop, or vice versa, is internal — the corpus expression layer, the `:mm/Schedule` entities, the `:mm/Run` history, the typed-edge Allen predicates, none of these change.

The boundary discipline ensures the swap stays internal.  No `org.quartz.*` types appear in MCP handler signatures; no `org.dmfs.*` types appear in the public `sandbar.schedule.*` API.  Consumers see Clojure data + sandbar-typed slots + `java.time` instants; the library underneath is invisible.

## NL → RRULE pipeline

Schedules expressed in natural language — *"every Tuesday at 3pm"*, *"three weeks from next Friday"*, *"first Monday of each quarter"* — are how humans think about recurrence.  The pipeline normalizes them into the canonical RRULE form.

Three stages, layered behind `sandbar.schedule.nl-parser`:

1. **Duckling-Clojure** ([dpom/clj-duckling](https://github.com/dpom/clj-duckling) or [org.msync/duckling](https://github.com/msync/duckling)) parses the NL string into Facebook's structured time-value representation — a tagged value covering instants, intervals, durations, and recurrence patterns
2. **In-house Duckling → RRULE codec** translates that structured value into RFC 5545 RRULE + DTSTART + IANA timezone (the gap in the Clojure ecosystem)
3. **lib-recur** (via `sandbar.schedule.recurrence`) iterates the RRULE for projected previews — *"if I scheduled this, when would it next fire?"*

The pipeline is lossy by design.  Not every NL phrase has a clean RRULE expansion (English is richer than the RRULE grammar); when lossy, `:mm.schedule/source-nl` preserves the original phrase and `:mm.schedule/rrule` records the closest RRULE.  Humans can edit either form; the canonical iteration always uses the RRULE.

The codec gap matters.  Duckling exists; lib-recur exists; the *translation between them* doesn't exist in the Clojure ecosystem.  Sandbar fills that gap in-house — bounded scope (English-first; common patterns like every-weekday, first-Monday-of-month, weekly-on-X), comprehensive round-trip test fixture, documented lossy cases.  Per Dan-directive 2026-05-23 in the arc plan's §11 lean: start with English + common patterns; defer multi-lingual + esoteric phrases.

## How it composes

The temporal substrate is foundational — Phase 8 of the event-substrate implementation arc — and what composes with it spans almost every other in-flight arc.

### With the event substrate

The dispatcher emits `:mm.event/Scheduled` / `:mm.event/JobStarted` / `:mm.event/JobCompleted` / `:mm.event/JobFailed` / `:mm.event/JobCancelled` onto the event bus.  Subscribers handle the cross-cutting concerns: the fs-projection sink materializes `:first-class`-flagged events as `:mm/EventLog` memorials; the metrics handler increments counters via Tufte; the retry handler drives retry logic and dead-letter promotion; the DB-dump indexer covers `:db-only` events.

This subsumes Quartz's Listener pattern (JobListener/TriggerListener/SchedulerListener) entirely — no bespoke listener wiring; the event substrate is the listener mechanism, with sandbar's typed-edge predicate subscription on top.

### With the logging arc

Tufte profile-result persistence — a scheduled job in the logging arc Stage F — writes performance snapshots to `:mm/Log :db-only` instances on a weekly RRULE.  The scheduler is the trigger; the logging arc owns the handler; the substrate stays clean.

### With the DB-dump arc

The disaster-recovery DB-dump runs as a `:mm/Schedule` with `RRULE FREQ=DAILY;BYHOUR=3` driving a `:mm/Job` whose handler is `sandbar.project.dump/dump-db-only`.  No bespoke cron; no separate scheduler.  The DB-dump arc is one consumer of the temporal substrate.

### With the rules arc

Rule firings are conceptually PROV-O activities (per the cross-arc alignment synthesis G.3).  When the rules arc Layer 3 implementation lands, rule firings can themselves be modeled as `:mm/Activity` instances — or a new `:mm/RuleFiring` subclass — with `:mm.activity/was-informed-by` pointing at the entity mutation that triggered the rule.  The activity hierarchy is the convergence point.

### With future health-check capability

Health-checks are a future capability per the transformation-axis ADR.  The scheduler arc provides the temporal substrate (*"check this every 5 minutes"*); the health-check capability adds `:mm/HealthCheck` entities that USE schedules.  No bespoke wiring; the layering is clean.

### With the workflow substrate

Multi-step jobs reference `:mm/Workflow` definitions through their handler implementation, and the `:workflow/Process` instance is the substrate-runtime coordinator.  Workflow transitions can themselves trigger scheduled follow-up jobs.  See [`workflow-substrate.md`](workflow-substrate.md) for the workflow concept.

## Comparison with adjacent approaches

### vs. cron + crontab

Cron expresses recurrence and triggers a command.  It does not model the *meaning* of the recurrence (is this a maintenance window or a heartbeat?), the *agent* responsible, the *causal lineage* between runs, or the *interval topology* between schedules.  Cron's expression vocabulary is also weaker than RRULE — no IANA timezone awareness, no second-precision, no exception-date lists, no end-conditions, no DST-safe recurrence.  The temporal substrate uses RRULE where cron uses cron-syntax, and adds the modeled vocabulary cron never had.

### vs. Quartz Scheduler

Quartz is a JVM scheduler with Job/JobDetail/Trigger separation, misfire policies, and Calendar exclusions.  It is mature, battle-tested, and operationally sound.  What Quartz does not do: model the temporal vocabulary as queryable typed entities; expose Allen-relation topology between schedules; record PROV-O lineage on runs in a structurally-queryable way.  Quartz is a dispatcher; sandbar's substrate is the modeled vocabulary the dispatcher serves.  Path B of the arc plan uses Quartz as the engine behind the substrate, exactly because the boundary is well-defined.

### vs. iCalendar + .ics files

iCalendar (RFC 5545) is the wire format the temporal substrate borrows from for its recurrence codec.  An .ics file is a serialized VEVENT/VTODO calendar item with embedded RRULE.  Sandbar's substrate uses the same RRULE codec, but the entities are first-class in a typed graph rather than scattered as text files in a calendar share.  Schedule.org Schedule JSON-LD is the modern web-shaped projection of the same vocabulary; the substrate emits to it on demand for cross-tool interchange.

### vs. Schema.org Schedule directly

Schema.org Schedule is a JSON-LD shape — useful for embedding scheduled-event metadata in web pages and consuming structured data from external tools, less useful as a working in-memory representation.  Sandbar uses Schema.org as a projection (Stage I of the arc plan) rather than as the canonical form; RRULE on `:mm.schedule/rrule` is canonical; the JSON-LD shape is one of several emit codecs.

### vs. Apache Airflow / Prefect / Dagster

These are workflow orchestrators with DAG-based job composition, retry semantics, and operational dashboards.  Their value is operational tooling around batch-job pipelines.  Sandbar's substrate is one level beneath this — the modeled vocabulary of *what a schedule is*, *what a run is*, *how they relate* — that an orchestrator like Prefect could consume but does not provide.  A future arc could bridge sandbar to one of these as a dispatcher; the substrate stays the same.

### vs. AWS EventBridge / Cloud Scheduler

Cloud-native schedulers offer managed recurrence triggering with cron or rate expressions and a fixed set of target invocations (Lambda, SNS, etc.).  They are operationally convenient; they do not model PROV-O lineage on the invocations or expose Allen-relation topology between schedules.  Sandbar's substrate could compose with these — a `:mm/Job` handler that publishes to EventBridge — but the corpus modeling stays on the sandbar side.

## References

**Allen's interval algebra**

- Allen, J.F. (1983). *Maintaining knowledge about temporal intervals.* Communications of the ACM, 26(11), 832–843.

**Formal temporal ontologies**

- Cox, S. & Little, C. (eds.) (2017). *Time Ontology in OWL.* W3C Recommendation.  https://www.w3.org/TR/owl-time/
- Hobbs, J.R. & Pan, F. (2004). *An ontology of time for the Semantic Web.* ACM Transactions on Asian Language Information Processing, 3(1), 66–85.

**Calendar interchange and recurrence**

- Desruisseaux, B. (ed.) (2009). *Internet Calendaring and Scheduling Core Object Specification (iCalendar).* IETF RFC 5545.
- Daboo, C. & Douglass, M. (2015). *Non-Gregorian Recurrence Rules in the Internet Calendaring and Scheduling Core Object Specification (iCalendar).* IETF RFC 7529.

**Provenance**

- Lebo, T., Sahoo, S. & McGuinness, D. (eds.) (2013). *PROV-O: The PROV Ontology.* W3C Recommendation.  https://www.w3.org/TR/prov-o/
- Moreau, L. et al (2013). *The PROV Data Model.* W3C Recommendation.  https://www.w3.org/TR/prov-dm/

**Web-shaped projection**

- Schema.org community (ongoing). *Schedule.* https://schema.org/Schedule

**Natural-language temporal parsing**

- Facebook AI (2014–2018, archived). *Duckling — probabilistic CFG-based parser for structured time, distance, currency, etc.* https://github.com/facebook/duckling
- Coreference for the Haskell rewrite + Clojure community forks ([dpom/clj-duckling](https://github.com/dpom/clj-duckling), [org.msync/duckling](https://github.com/msync/duckling)).

**Scheduler engines (for comparison)**

- Cain, J. (2001–). *Quartz Scheduler.* https://www.quartz-scheduler.org/
- Müller, M. (2014–). *lib-recur — Apache-licensed Java RRULE iterator.* https://github.com/dmfs/lib-recur
- Factor House (2019–). *Cronut — data-first Clojure wrapper over Quartz.* https://github.com/factorhouse/cronut

**Saga and long-running operations (composes with workflow substrate)**

- Garcia-Molina, H. & Salem, K. (1987). *Sagas.* ACM SIGMOD Conference 1987.

## See also

- [`memory-model.md`](memory-model.md) — the `:mm/Activity` PROV-O supertype + the memorial-policy axis the substrate composes with
- [`workflow-substrate.md`](workflow-substrate.md) — `:mm/Workflow` + `:workflow/Process` for multi-step coordination; jobs that need state-machine semantics reference workflow definitions
- [`metamodel.md`](metamodel.md) — `:mm/Schedule` + `:mm/Job` + `:mm/Run` are `:dt/Class` instances; the metamodel describes them like any other class
- [`projection.md`](projection.md) — `:first-class` schedules + jobs FS-project to `memory/schedules/` and `memory/jobs/`; `:db-only` runs are covered by the DB-dump arc
- [`fulltext-search.md`](fulltext-search.md) — schedules and jobs are BM25F-searchable corpus citizens
- [`mcp-protocol.md`](mcp-protocol.md) — the MCP surface exposing the temporal substrate to AI consumers
- [`memory/plans/sandbar_temporal_substrate_arc_owl_time_rrule_prov_o_aligned_schedule_job_run_2026_05_23.md`](../../memory/plans/sandbar_temporal_substrate_arc_owl_time_rrule_prov_o_aligned_schedule_job_run_2026_05_23.md) — the implementation arc plan
- [`memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md`](../../memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md) — the activity-lift ADR (landed; `:mm/Activity` is in the schema today)
- [`memory/decisions/sandbar_ontology_alignment_is_the_goal_implementation_is_replaceable_*.md`](../../memory/decisions/sandbar_ontology_alignment_is_the_goal_implementation_is_replaceable_boundary_pragmatism_when_layered_or_seamless_2026_05_23.md) — the keystone ADR governing engine-replaceability
