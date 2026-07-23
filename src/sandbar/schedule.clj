(ns sandbar.schedule
  "γ.2 Step 6 — public API facade for the sandbar scheduler.

   Thin wrapper over the four internal namespaces:
     - `sandbar.schedule.state`         — runtime state + state-machine
     - `sandbar.schedule.recurrence`    — lib-recur RRULE boundary
     - `sandbar.schedule.dispatcher`    — fire-thread + queue + emission
     - `sandbar.schedule.job-dispatcher` — :mm.event/Scheduled subscriber

   Consumers (γ.4 MCP verbs; γ.3 lifecycle wiring; operators at the
   REPL) should reach for THIS namespace + only fall through to the
   internal namespaces for deep introspection.

   ## Public surface

     `enable!`         — flip :enabled? true (does NOT start fire-thread)
     `disable!`        — flip :enabled? false (does NOT stop fire-thread)
     `enabled?`        — read :enabled? flag
     `start!`          — full activation: dispatcher.start! + job-dispatcher.register!
     `stop!`           — full deactivation: job-dispatcher.unregister! + dispatcher.stop!
     `add-schedule!`   — schedule a :mm/Schedule eid for firing
     `remove-schedule!`— deschedule
     `list-schedules`  — diagnostic; returns vec of queued [fire-at eid] entries
     `inspect`         — full state snapshot for operator + MCP verb consumption
     `state`           — diagnostic; returns the current state-machine keyword

   ## Lifecycle composition (per γ.1 ADR §1.6)

   `start!` composes the two-side activation:
   1. `dispatcher/start!` — allocates handler-pool + spawns fire-thread + transitions to :active
   2. `job-dispatcher/register!` — subscribes the :mm.event/Scheduled handler

   `stop!` composes the inverse:
   1. `job-dispatcher/unregister!` — removes the subscriber (so the
      dispatcher's final-drain fires don't trigger Run creation)
   2. `dispatcher/stop!` — drains handler-pool + joins fire-thread +
      transitions to :inactive

   The two halves are coupled at this layer (not at the dispatcher or
   job-dispatcher layer) so each internal namespace stays single-
   responsibility.

   ## Enable/disable vs start/stop

   `enable!` / `disable!` flip the gate flag; the dispatcher's fire-
   thread + job-dispatcher's subscriber both read `:enabled?` at fire/
   handle time to decide whether to act.

   `start!` / `stop!` allocate / release the actual threads + pool +
   subscriber.

   Typical production lifecycle (per γ.3 sandbar.core wiring; not yet
   landed): `start!` at JVM boot when config's `:scheduler/enabled?`
   is true; `stop!` at JVM shutdown.  Operators may `disable!` to
   suspend firings without tearing down resources.

   ## See also

   - γ.1 ADR: `:memory.decisions/gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_resolved_2026_05_27`
   - γ implementation plan-mode: `~/.claude/plans/golden-squishing-flamingo.md` Step 6
   - sibling sandbar.reactive (shape precedent for public-API facade
     fronting per-thread + atom-inside-dyn-var idiom)"
  (:require [sandbar.schedule.state          :as state]
            [sandbar.schedule.dispatcher     :as dispatcher]
            [sandbar.schedule.job-dispatcher :as jd]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enable flag

(defn enable!
  "Flip the scheduler's `:enabled?` flag to true.  Does NOT start the
   fire-thread or allocate the handler-pool — see `start!` for the
   full lifecycle.  When the dispatcher is running, enabling permits
   fires to actually emit events + Runs to actually execute.

   Idempotent."
  []
  (state/swap-state! assoc :enabled? true)
  :enabled)

(defn disable!
  "Flip the scheduler's `:enabled?` flag to false.  Does NOT stop the
   fire-thread or release the handler-pool — see `stop!` for the full
   lifecycle.  When false, the dispatcher's fire-thread will still
   wake on scheduled fires but the job-dispatcher subscriber will
   silently skip them (per `handle-scheduled-event`'s enable-gate).

   Idempotent."
  []
  (state/swap-state! assoc :enabled? false)
  :disabled)

(defn enabled?
  "Diagnostic — is the `:enabled?` flag set?"
  []
  (boolean (:enabled? (state/snapshot))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle

(defn start!
  "Full activation: allocate the handler-pool + spawn the fire-thread
   (via `dispatcher/start!`) + register the :mm.event/Scheduled
   subscriber (via `job-dispatcher/register!`).

   Order matters: the dispatcher must transition to :active BEFORE
   the subscriber registers — otherwise the very first fire would hit
   a subscriber that bails on the gate check.

   Returns `:started` on success, `:already-active` when already
   running (delegates idempotency to `dispatcher/start!`)."
  []
  (let [outcome (dispatcher/start!)]
    (jd/register!)
    outcome))

(defn stop!
  "Full deactivation: unregister the :mm.event/Scheduled subscriber
   (via `job-dispatcher/unregister!`) THEN drain + stop the
   dispatcher (via `dispatcher/stop!`).

   Order matters: unsubscribe FIRST so the dispatcher's drain-time
   final fires (if any) don't trigger Run creation in the
   subscriber.

   Returns the outcome from `dispatcher/stop!`
   (`:stopped` / `:already-inactive`)."
  ([] (stop! {}))
  ([opts]
   (jd/unregister!)
   (dispatcher/stop! opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedule management — facade over dispatcher

(defn add-schedule!
  "Add a :mm/Schedule entity to the priority queue.  Computes the
   schedule's next-fire-at from `(now)`; inserts a queue entry;
   .interrupts the fire-thread to re-park on the new head if
   appropriate.

   Returns the computed next-fire-at Instant on success, nil when the
   schedule has no future fires (terminated RRULE / malformed
   schedule) — queue unchanged in the nil case."
  [schedule-eid]
  (dispatcher/add-schedule! schedule-eid))

(defn remove-schedule!
  "Remove all queue entries for the given :mm/Schedule eid.  .interrupts
   the fire-thread to re-park.  Idempotent."
  [schedule-eid]
  (dispatcher/remove-schedule! schedule-eid))

(defn list-schedules
  "Diagnostic — return the current queue as a sorted-set of
   `[next-fire-at-instant schedule-eid]` tuples in priority order."
  []
  (dispatcher/snapshot-queue))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Introspection

(defn inspect
  "Full operator-facing snapshot of scheduler runtime state.  Suitable
   for surfacing to MCP verbs (γ.4) + REPL diagnostic use.

   Returns a map with:
     :state            — state-machine keyword (:inactive | :active | :paused | :draining)
     :enabled?         — boolean
     :queue-size       — count of queued schedule fires
     :handler-pool?    — boolean (pool allocated?)
     :fire-thread?     — boolean (thread allocated?)
     :clock-drift-ms   — latest observed wall-vs-monotonic drift
     :in-flight-runs   — map of {schedule-eid #{run-eid ...}} from job-dispatcher
     :subscriber-registered? — boolean (job-dispatcher subscriber active?)"
  []
  (let [s (state/snapshot)]
    {:state                   (:state s)
     :enabled?                (boolean (:enabled? s))
     :queue-size              (count (:queue s))
     :handler-pool?           (some? (:handler-pool s))
     :fire-thread?            (some? (:fire-thread s))
     :clock-drift-ms          (:clock-drift-ms s 0)
     :in-flight-runs          (or (:in-flight-runs s) {})
     :subscriber-registered?  (jd/registered?)}))

(defn state
  "Diagnostic — return the current state-machine keyword."
  []
  (:state (state/snapshot)))
