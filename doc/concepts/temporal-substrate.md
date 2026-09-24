# Time: describe occurrence, duration, and recurrence separately

**Useful temporal knowledge distinguishes when something happened, how long it lasted, and when work should happen again.** A date on a record cannot answer all three questions. Sandbar provides instants and intervals for description, schedules for recurrence, and runs or processes for execution.

The temporal vocabulary draws on [OWL-Time](https://www.w3.org/TR/owl-time/), which describes instants, intervals, durations, and temporal relations. Recurrence uses RRULE expressions through the [`sandbar.schedule.recurrence`](../../src/sandbar/schedule/recurrence.clj) adapter. These are specific modeling and execution choices, not a general temporal theorem prover.

## An instant can be a value or an entity

An interval endpoint can be stored as a primitive instant or as a reference to an `:mm/Instant` entity. The reference form is useful when the instant needs its own identity or descriptive relationships. The primitive form is compact when only the time matters.

The paired properties are `begins-at-time` / `begins-at-instant` and `ends-at-time` / `ends-at-instant` in the `mm.interval` namespace. A shape expresses the intended exclusive choice. The helpers in `sandbar.temporal` read either form:

```clojure
(require '[sandbar.temporal :as temporal])

(temporal/interval-duration-ms
  {:mm.interval/begins-at-time #inst "2026-01-01T00:00:00Z"
   :mm.interval/ends-at-time #inst "2026-01-01T00:00:01Z"})
;; => 1000
```

These are readers, not validators. A missing endpoint produces no duration; callers must validate the intended interval shape and ordering separately.

The Allen-relation helpers, such as `allen-before?`, inspect recorded relationship membership. They do not calculate every relation from endpoint arithmetic. Entailed relations also require the [appropriate inference query](rdfs-entailment.md); an ordinary projected entity does not automatically contain every entailed edge.

## A schedule describes future work

A schedule describes recurrence and a target. A job specifies a function to invoke; each invocation is represented by a run. The scheduler maintains the next fire times in a priority queue and dispatches work when due. It does not need a separate polling loop for every schedule.

`sandbar.schedule` exposes lifecycle and management operations. `enable!` changes the execution gate; `start!` allocates and starts the runtime. `disable!` is not equivalent to stopping and draining the runtime. `add-schedule!` puts an existing schedule into the queue, and `inspect`/`state` expose runtime diagnostics.

The current job dispatcher resolves a classpath function using the job's function record. It supplies a run-context map. A schedule's declared target and concurrency policy need an implemented execution path; schema enumeration alone does not establish one. Overlap admission is not atomic, replacement does not reliably terminate the older work, and a timeout can release admission while an interrupt-resistant function is still running. Do not use these policies as mutual exclusion for correctness-critical effects. Such jobs need their own serialized execution boundary and outcome checks until scheduler ownership is repaired.

The example service configuration enables the scheduler and system jobs. The current system jobs report database and reactive-queue health hourly as log output; ordinary operational activity is not automatically authored memory. Configuration and installed schedule state must be inspected when diagnosing a deployment.

## Use the right clock for the question

Authored timestamps describe an application's claim about time. Database transaction time describes when an assertion was accepted. Scheduler clocks drive due work, and elapsed-time measurements describe duration. A stale-record review, a transaction catch-up, and a timeout should not silently substitute one for another.

Interactive memory creation/update rejects authored timestamps beyond the configured future-skew allowance. Import has a distinct preservation contract and needs its own validation/reporting. Neither path makes an authored timestamp proof of authority.

See [workflows](workflow-substrate.md), [events](event-substrate.md), and [operations](../operations.md). The temporal model is declared in [`schema/mm-temporal.edn`](../../schema/mm-temporal.edn); runtime policy belongs to the schedule implementation and its tests.
