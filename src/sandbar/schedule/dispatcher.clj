(ns sandbar.schedule.dispatcher
  "γ.2 Step 4 — native min-heap dispatcher fire-thread.

   The dispatcher is the heart of the γ scheduler arc.  A single fire-thread
   parks until the head of a sorted-set priority queue (keyed by
   `[next-fire-at-instant schedule-eid]`); when its sleep elapses, it
   resolves the head schedule, emits a `:mm.event/Scheduled` event via
   `sandbar.event/fire!`, computes the schedule's next-fire-at via
   `sandbar.schedule.recurrence/iterate-from`, advances the queue, and
   re-parks.

   The subscriber side (sandbar.schedule.job-dispatcher; γ.2 Step 5) is
   NOT in this namespace — it lives separately + subscribes to
   `:mm.event/Scheduled` via `sandbar.event/subscribe!`.  This namespace's
   sole emission discipline is the `:mm.event/Scheduled` fire; everything
   else (Run lifecycle, effect-spec validation, retries) is a downstream
   concern.

   ## Public surface

     `start!`             — allocate handler-pool + start fire-thread + transition to :active
     `stop!`              — transition to :draining; .interrupt fire-thread; join; shutdown pool; transition to :inactive
     `pause!` / `resume!` — fire-thread halts but queue preserved (paused);
                            resume re-parks on the head
     `add-schedule!`      — compute next-fire-at from RRULE; conj queue; .interrupt re-park
     `remove-schedule!`   — disj from queue; .interrupt re-park
     `fire-schedule!`     — public for test access; usually called by fire-loop! only
     `snapshot-queue`     — diagnostic; returns the queue's current contents (sorted)
     `recompute-queue-from-db!` — rebuild queue from all live `:mm/Schedule` entities (recovery / startup)

   ## Architecture (per γ.1 ADR §1.6, §2.4)

     [fire-thread] →[event/fire! :mm.event/Scheduled]→ [job-dispatcher subscribers]
                                                       (γ.2 Step 5; NOT this ns)

     queue = sorted-set-by [next-fire-at-instant schedule-eid]
     park  = Thread/sleep until (max 0 (Duration/between now next-fire-at))
     wake  = either Thread/sleep elapsed (fire!) OR .interrupt (re-evaluate queue)

   ## Q-checkpoint ratifications applied

   - Q.γ.4 default misfire policy = `:misfire/fire-once-now` (conservative catch-up)
   - Q.γ.5 default `:enabled?` = false (state ns initial-state)
   - Q.γ.6 single fire-thread + bounded handler-thread-pool (handler-pool
           is owned by THIS ns; subscribers run on it via job-dispatcher
           in Step 5; for Step 4 the pool is initialized + held in state
           but unused by the dispatcher itself)

   ## Clock-drift detection (R.γ.2 mitigation)

   Each loop iteration compares wall-clock elapsed (`System/currentTimeMillis`
   delta) to monotonic-clock elapsed (`System/nanoTime` delta).  If wall
   exceeds monotonic by more than `:clock-drift-threshold-ms` (default
   5000ms), emit `:mm.event/SchedulerClockDrift` event with magnitude.
   The drift magnitude is also persisted in `:clock-drift-ms` of state.

   ## Misfire policies (R.γ.1 mitigation)

   Applied when a fire is detected as 'missed' (e.g., scheduler-paused
   period covered one or more scheduled fires; the next park-until would
   sleep zero time because next-fire-at is in the past):

   - `:misfire/fire-once-now` — fire one catch-up + advance to next-future
   - `:misfire/ignore`        — skip the missed fire; advance to next-future
   - `:misfire/reschedule`    — re-anchor schedule from now; emit one fire-event

   Default = `:misfire/fire-once-now` per Q.γ.4.

   ## See also

   - γ.1 ADR: `:memory.decisions/gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_resolved_2026_05_27`
   - γ implementation plan-mode artifact: `~/.claude/plans/golden-squishing-flamingo.md` Step 4
   - sandbar.schedule.state — state machine + queue comparator + dyn-var
   - sandbar.schedule.recurrence — lib-recur boundary for RRULE iteration
   - sandbar.event — class-hierarchical fire!/subscribe!"
  (:require [sandbar.schedule.state      :as state]
            [sandbar.schedule.recurrence :as recurrence]
            [sandbar.event               :as event]
            [sandbar.db.datatype         :as dt]
            [sandbar.db.datomic          :as datomic]
            [sandbar.logging             :as logging]
            [datomic.api                 :as d])
  (:import [java.time Instant Duration]
           [java.util Date]
           [java.util.concurrent Executors ExecutorService TimeUnit]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Date ↔ Instant coercion (Datomic stores :db.type/instant as java.util.Date;
;; lib-recur uses java.time.Instant; this namespace bridges via private helpers)

(defn- ^Instant ->instant
  "Coerce a temporal value to java.time.Instant.  Accepts java.util.Date
   (Datomic's wire form) OR Instant (pass-through).  Returns nil for nil."
  [v]
  (cond
    (nil? v)              nil
    (instance? Instant v) v
    (instance? Date v)    (.toInstant ^Date v)
    :else
    (throw (ex-info "Cannot coerce to java.time.Instant"
                    {:value v :type (class v)}))))

(defn- ^Date instant->date
  "Coerce an Instant to java.util.Date for Datomic transactions."
  [^Instant inst]
  (when inst (Date/from inst)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clock seam — controllable from tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; All wall-clock reads in this namespace go through `*now-fn*` so tests
;; can bind a deterministic clock.  Production binding defaults to
;; `Instant/now`.  Idiom matches other Sandbar test seams (e.g.
;; sandbar.workflow.orchestrate's `*phase-clock*`).

(def ^:dynamic *now-fn*
  "Producer of the current wall-clock `java.time.Instant`.  Override
   via `binding` in tests to inject a controllable clock."
  (fn [] (Instant/now)))

(defn- ^Instant now [] (*now-fn*))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedule entity resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-schedule
  "Pull the `:mm/Schedule` entity-map (slot keys) for `schedule-eid`.
   Returns nil when the eid does not resolve to a live :mm/Schedule
   (either retracted OR never existed OR points at a different
   :dt/type).

   Datomic's `d/entity` returns a non-nil Entity object for non-
   existent eids (with no slots); the substantive existence check is
   on the `:mm.schedule/recurrence` slot which every :mm/Schedule
   carries (per the schema)."
  [schedule-eid]
  (let [db (d/db (datomic/conn))
        e  (d/entity db schedule-eid)]
    (when (and e (:mm.schedule/recurrence e))
      ;; Materialize the entity-map shape callers expect; entity returns
      ;; a lazy view but downstream needs realized slot reads.
      (into {:db/id schedule-eid} e))))

(defn- schedule->recurrence-data
  "Translate a `:mm/Schedule` entity-map to the schedule-data shape that
   `sandbar.schedule.recurrence/iterate-from` expects.  Returns nil when
   the schedule lacks the required `:mm.schedule/recurrence` or
   `:mm.schedule/dtstart` slots — the caller should drop these from
   the queue (and may emit a `:mm.event/JobRejected` event with
   `:rejection-reason :schedule-malformed` in a future hardening pass).

   Coerces `:mm.schedule/dtstart` from java.util.Date (Datomic wire form)
   to java.time.Instant (lib-recur expectation) via `->instant`."
  [schedule]
  (let [rrule    (:mm.schedule/recurrence schedule)
        dtstart  (->instant (:mm.schedule/dtstart schedule))
        timezone (or (:mm.schedule/timezone schedule) "UTC")]
    (when (and rrule dtstart)
      (recurrence/parse-rrule rrule dtstart timezone))))

(defn- compute-next-fire-at
  "Compute the next scheduled fire-instant at or after `from-instant`
   for the schedule, walking `:mm.schedule/exdates` to skip excluded
   instants.  Returns nil if the schedule's RRULE has terminated
   (UNTIL / COUNT exhausted past `from-instant`).

   Coerces Datomic-wire Date values in `:mm.schedule/exdates` and
   `:mm.schedule/until` to java.time.Instant via `->instant`."
  [schedule ^Instant from-instant]
  (when-let [sched-data (schedule->recurrence-data schedule)]
    (let [exdates (->> (:mm.schedule/exdates schedule)
                       (map ->instant)
                       (into #{}))
          until   (->instant (:mm.schedule/until schedule))]
      ;; iterate-from is a lazy seq — find the next-fire NOT in exdates.
      ;; Bounded by until / count terminators built into the RRULE itself
      ;; (lib-recur honors those automatically).
      (some (fn [^Instant inst]
              (when (and (not (contains? exdates inst))
                         (or (nil? until)
                             (.isBefore inst until)))
                inst))
            (take 1024  ;; bound on consecutive exdate skips; defensive
                  (recurrence/iterate-from sched-data from-instant))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queue ops — pure helpers over the sorted-set queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- queue-conj-entry
  "Add `[fire-at schedule-eid]` to the queue (returns new queue)."
  [queue ^Instant fire-at schedule-eid]
  (conj queue [fire-at schedule-eid]))

(defn- queue-disj-by-eid
  "Remove ALL entries for `schedule-eid` from `queue` (returns new
   queue).  Multiple entries can exist if `add-schedule!` was called
   without `remove-schedule!` between fires (defensive)."
  [queue schedule-eid]
  (->> queue
       (remove (fn [[_ eid]] (= eid schedule-eid)))
       (into (state/empty-queue))))

(defn- queue-head
  "Return the head entry `[fire-at schedule-eid]` or nil if queue empty."
  [queue]
  (first queue))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public-API helpers for queue mutation via the state atom
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- interrupt-fire-thread!
  "If a fire-thread is registered in state, .interrupt it so it
   re-evaluates the queue head.  Idempotent — no-op if no thread."
  []
  (when-let [^Thread t (:fire-thread (state/snapshot))]
    (.interrupt t)))

(defn snapshot-queue
  "Diagnostic: return the queue's current entries (as a sorted-set,
   in priority order).  Per-call snapshot; not a live view."
  []
  (:queue (state/snapshot)))

(defn add-schedule!
  "Compute next-fire-at for `schedule-eid` (from `(now)`); insert
   `[next-fire-at schedule-eid]` into the queue; .interrupt the
   fire-thread so it re-parks on the new head if appropriate.

   Returns the computed next-fire-at Instant, or nil if the schedule
   has no future fires (terminated RRULE / malformed schedule).
   On nil return, the queue is NOT modified.

   Idempotent on schedule-eid — if the schedule already has an entry
   in the queue, the existing entry is removed first (so callers can
   re-add to refresh the next-fire-at after an exdates change without
   doing the remove+add dance themselves)."
  [schedule-eid]
  (let [now-inst (now)
        schedule (resolve-schedule schedule-eid)]
    (if (nil? schedule)
      (do (logging/warn ::add-schedule-missing
                        {:schedule-eid schedule-eid}
                        :db-only)
          nil)
      (let [next-at (compute-next-fire-at schedule now-inst)]
        (if (nil? next-at)
          (do (logging/info ::add-schedule-no-future-fires
                            {:schedule-eid schedule-eid}
                            :db-only)
              nil)
          (do (state/swap-state!
                update :queue
                (fn [q]
                  (-> q
                      (queue-disj-by-eid schedule-eid)
                      (queue-conj-entry next-at schedule-eid))))
              (interrupt-fire-thread!)
              next-at))))))

(defn remove-schedule!
  "Remove all queue entries for `schedule-eid`; .interrupt the fire-
   thread so it re-parks on the new head.  Idempotent."
  [schedule-eid]
  (state/swap-state! update :queue queue-disj-by-eid schedule-eid)
  (interrupt-fire-thread!)
  nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clock-drift detection (R.γ.2 mitigation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- detect-clock-drift!
  "Compute wall-vs-monotonic drift over the interval bounded by
   `wall-start-ms` / `mono-start-ns` (the loop's last reference
   timestamps) and now.  Update `:clock-drift-ms` in state; emit
   `:mm.event/SchedulerClockDrift` if magnitude exceeds the
   configured threshold."
  [wall-start-ms mono-start-ns]
  (let [wall-now-ms (System/currentTimeMillis)
        mono-now-ns (System/nanoTime)
        wall-elapsed  (- wall-now-ms wall-start-ms)
        mono-elapsed  (long (/ (- mono-now-ns mono-start-ns) 1000000))
        drift-ms      (- wall-elapsed mono-elapsed)
        threshold-ms  (or (:clock-drift-threshold-ms (state/snapshot)) 5000)]
    (state/swap-state! assoc :clock-drift-ms drift-ms)
    (when (>= (Math/abs drift-ms) threshold-ms)
      (event/fire! {:event/class             :mm.event/SchedulerClockDrift
                    :mm.schedule-event/drift-ms     drift-ms
                    :mm.schedule-event/threshold-ms threshold-ms
                    :timestamp                       (now)})
      (logging/warn ::clock-drift
                    {:drift-ms drift-ms :threshold-ms threshold-ms}
                    :db-only))
    drift-ms))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fire-emission + misfire-policy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +default-misfire-policy+ :misfire/fire-once-now)

(defn- misfire?
  "Has the scheduled `fire-at` already elapsed by more than ~5s past
   `now-inst`?  The threshold avoids treating tiny park overshoot as
   misfire (a clean fire arriving 100ms late is just a normal fire)."
  [^Instant fire-at ^Instant now-inst]
  (let [drift-ms (.toMillis (Duration/between fire-at now-inst))]
    (> drift-ms 5000)))

(defn fire-schedule!
  "Emit one `:mm.event/Scheduled` event for `schedule-eid` at scheduled
   `fire-at`.  Records the fire-attempt in `:fires-by-eid`.  Returns
   `:fired` (event emitted) OR `:skipped` (misfire-policy chose skip)
   OR `:rejected` (schedule no longer resolves; queue entry stale).

   Public for test access; production callers use `fire-loop!`."
  [^Instant fire-at schedule-eid]
  (let [now-inst (now)
        schedule (resolve-schedule schedule-eid)]
    (cond
      ;; Schedule was retracted between queue-insert and fire-time
      (nil? schedule)
      (do (logging/warn ::fire-schedule-rejected-missing
                        {:schedule-eid schedule-eid :fire-at fire-at}
                        :db-only)
          :rejected)

      ;; Misfire detected — consult policy
      (misfire? fire-at now-inst)
      (let [policy (or (:mm.schedule/misfire-policy schedule)
                       +default-misfire-policy+)]
        (case policy
          :misfire/ignore
          (do (logging/info ::misfire-ignored
                            {:schedule-eid schedule-eid
                             :fire-at      fire-at
                             :delta-ms     (.toMillis
                                            (Duration/between fire-at now-inst))}
                            :db-only)
              :skipped)

          ;; :misfire/fire-once-now AND :misfire/reschedule both fire
          ;; one catch-up event at now-time; the difference between
          ;; them only matters at next-fire-at computation, handled by
          ;; the loop after this fn returns.
          (:misfire/fire-once-now :misfire/reschedule)
          (do (event/fire! {:event/class                 :mm.event/Scheduled
                            :mm.schedule-event/schedule  schedule-eid
                            :scheduled-fire-at           fire-at
                            :actual-fire-at              now-inst
                            :misfire?                    true
                            :misfire-policy              policy
                            :timestamp                   now-inst})
              (state/swap-state! update :fires-by-eid
                                 (fnil conj []) {:schedule-eid schedule-eid
                                                 :fire-at      now-inst
                                                 :misfire?     true})
              :fired)

          ;; Unknown policy — log + fire conservatively
          (do (logging/warn ::unknown-misfire-policy
                            {:policy policy :schedule-eid schedule-eid}
                            :db-only)
              (event/fire! {:event/class                 :mm.event/Scheduled
                            :mm.schedule-event/schedule  schedule-eid
                            :scheduled-fire-at           fire-at
                            :actual-fire-at              now-inst
                            :timestamp                   now-inst})
              :fired)))

      ;; Clean fire — emit the event
      :else
      (do (event/fire! {:event/class                 :mm.event/Scheduled
                       :mm.schedule-event/schedule  schedule-eid
                       :scheduled-fire-at           fire-at
                       :actual-fire-at              now-inst
                       :misfire?                    false
                       :timestamp                   now-inst})
          (state/swap-state! update :fires-by-eid
                             (fnil conj []) {:schedule-eid schedule-eid
                                             :fire-at      now-inst
                                             :misfire?     false})
          :fired))))

(defn- advance-queue-after-fire!
  "After a successful fire, dissoc the fired entry + compute the
   schedule's next-fire-at STRICTLY AFTER `prev-fire-at` + conj the
   new entry.  Honors `:misfire/reschedule` by anchoring at `(now)`
   instead of `prev-fire-at`.

   Uses explicit `.isAfter prev-fire-at` filter rather than
   `(.plusMillis prev-fire-at 1)` because lib-recur's fastForward
   semantics around the dtstart boundary can return the dtstart
   instant even for marginally-greater anchors.  The explicit
   strict-after filter is robust regardless of lib-recur's internal
   precision behavior."
  [^Instant prev-fire-at schedule-eid policy-was]
  (when-let [schedule (resolve-schedule schedule-eid)]
    (when-let [sched-data (schedule->recurrence-data schedule)]
      (let [exdates (->> (:mm.schedule/exdates schedule)
                         (map ->instant)
                         (into #{}))
            until   (->instant (:mm.schedule/until schedule))
            anchor  (if (= policy-was :misfire/reschedule)
                      (now)
                      prev-fire-at)
            next-at (->> (recurrence/iterate-from sched-data anchor)
                         (take 1024)  ;; defensive bound on exdate skips
                         (some (fn [^Instant inst]
                                 (when (and (.isAfter inst prev-fire-at)
                                            (not (contains? exdates inst))
                                            (or (nil? until)
                                                (.isBefore inst until)))
                                   inst))))]
        (state/swap-state!
          update :queue
          (fn [q]
            (let [stripped (queue-disj-by-eid q schedule-eid)]
              (if next-at
                (queue-conj-entry stripped next-at schedule-eid)
                stripped))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fire-thread loop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +empty-queue-park-ms+
  "Sleep duration when the queue is empty — woken by .interrupt on
   add-schedule!.  60s is a balance: short enough that lost interrupts
   don't strand the dispatcher (defense in depth), long enough that
   the loop doesn't busy-cycle."
  60000)

(defn- park-until!
  "Park the fire-thread until `target` Instant (or, when target is
   nil, for `+empty-queue-park-ms+`).  Returns :elapsed if the sleep
   completed naturally; :interrupted if .interrupt fired.  Clears the
   thread's interrupted-status either way."
  [^Instant target]
  (let [park-ms (if target
                  (max 0 (.toMillis (Duration/between (now) target)))
                  +empty-queue-park-ms+)]
    (try
      (when (pos? park-ms)
        (Thread/sleep park-ms))
      :elapsed
      (catch InterruptedException _
        ;; Per Java best-practice — clear the interrupted-flag the
        ;; .interrupt set on us (we've handled it by waking up).
        (.. Thread currentThread interrupted)
        :interrupted))))

(defn- fire-loop!
  "The fire-thread's body.  Loops while state is `:active`:
     1. Snapshot queue head
     2. Park until head's fire-at (OR +empty-queue-park-ms+ when empty)
     3. On wake (clean): fire-schedule! + advance-queue-after-fire!
     4. On wake (interrupted): re-evaluate head; the queue may have
        a new head from add-schedule! / remove-schedule!
     5. Check clock-drift every loop iteration
     6. Repeat

   Termination: when state transitions away from `:active`, the loop
   exits.  The state machine guards transitions; this fn just polls
   `:state` per iteration (cooperative shutdown)."
  []
  (try
    (let [wall-start-ms (System/currentTimeMillis)
          mono-start-ns (System/nanoTime)]
      (loop []
        (when (= :scheduler.state/active (:state (state/snapshot)))
          (detect-clock-drift! wall-start-ms mono-start-ns)
          (let [head (queue-head (:queue (state/snapshot)))]
            (cond
              ;; Empty queue — long park; woken by add-schedule!'s .interrupt
              (nil? head)
              (do (park-until! nil)
                  (recur))

              ;; Head present — park until its fire-at
              :else
              (let [[fire-at schedule-eid] head
                    park-result (park-until! fire-at)]
                (case park-result
                  :interrupted
                  (recur)  ;; queue mutated; re-evaluate head

                  :elapsed
                  (let [schedule (resolve-schedule schedule-eid)
                        policy   (or (:mm.schedule/misfire-policy schedule)
                                     +default-misfire-policy+)
                        outcome  (fire-schedule! fire-at schedule-eid)]
                    (case outcome
                      :fired
                      (do (advance-queue-after-fire! fire-at schedule-eid policy)
                          (recur))

                      :skipped
                      ;; Misfire policy = :ignore; still need to advance
                      (do (advance-queue-after-fire! fire-at schedule-eid policy)
                          (recur))

                      :rejected
                      ;; Stale queue entry; drop it + continue
                      (do (state/swap-state! update :queue
                                             queue-disj-by-eid schedule-eid)
                          (recur)))))))))))
    (catch Throwable t
      (logging/error ::fire-thread-died t {} :first-class)
      ;; Ensure state reflects the death — flip to :draining (legal
      ;; from :active) then :inactive so operator-facing `enabled?`
      ;; reads false.  Defensive — illegal-transition exceptions
      ;; here are caught + swallowed (best-effort).
      (try (state/transition-state! :scheduler.state/draining
                                    :reason "fire-thread died")
           (state/transition-state! :scheduler.state/inactive
                                    :reason "fire-thread died (drain complete)")
           (catch Exception _ nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle — start! / stop! / pause! / resume!
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-handler-pool
  "Allocate a fixed-size ExecutorService for handler-pool subscribers
   (job-dispatcher in γ.2 Step 5)."
  ^ExecutorService [^long size]
  (Executors/newFixedThreadPool size))

(defn- new-fire-thread
  "Spawn a dedicated Thread for the fire-loop.  Daemon so JVM exit
   doesn't wait on it.  Returns the started Thread.

   Uses `bound-fn*` to convey the spawn-thread's dynamic bindings
   (notably `*scheduler-state*` + `*now-fn*`) into the fire-thread.
   Without this, test fixtures that bind these dyn-vars would be
   invisible to the fire-thread (which would see root bindings),
   producing 'queue mysteriously empty in fire-thread' bugs."
  ^Thread []
  (let [bound-loop (bound-fn* fire-loop!)]
    (doto (Thread. ^Runnable bound-loop "sandbar.schedule.dispatcher.fire-thread")
      (.setDaemon true)
      (.start))))

(defn start!
  "Transition `:inactive → :active` + allocate handler-pool + start
   fire-thread + recompute queue from DB.  Idempotent — returns
   `:already-active` (and does nothing) when state is already
   `:active`.

   Throws ex-info on illegal transition (e.g., from `:draining`)."
  []
  (let [{:keys [state]} (state/snapshot)]
    (case state
      :scheduler.state/active
      :already-active

      ;; Legal-transition path: :inactive → :active OR :paused → :active
      (do (state/transition-state! :scheduler.state/active
                                   :reason "dispatcher start!")
          (let [pool-size (or (:handler-pool-size (state/snapshot)) 4)
                pool      (new-handler-pool pool-size)
                t         (new-fire-thread)]
            (state/swap-state! assoc
                               :handler-pool pool
                               :fire-thread  t))
          (logging/info ::started
                        {:handler-pool-size (or (:handler-pool-size (state/snapshot)) 4)}
                        :db-only)
          :started))))

(defn pause!
  "Transition `:active → :paused`.  Fire-thread will see the state
   change on its next loop iteration + exit.  Queue is preserved.
   Idempotent."
  []
  (let [{:keys [state]} (state/snapshot)]
    (case state
      :scheduler.state/paused :already-paused
      (do (state/transition-state! :scheduler.state/paused
                                   :reason "dispatcher pause!")
          (interrupt-fire-thread!)
          :paused))))

(defn resume!
  "Transition `:paused → :active`.  Spawns a NEW fire-thread (the
   prior one terminated when state left `:active`).  Idempotent."
  []
  (let [{:keys [state]} (state/snapshot)]
    (case state
      :scheduler.state/active :already-active
      (do (state/transition-state! :scheduler.state/active
                                   :reason "dispatcher resume!")
          (let [t (new-fire-thread)]
            (state/swap-state! assoc :fire-thread t))
          :resumed))))

(defn stop!
  "Cooperative shutdown: transition `:active/:paused → :draining`,
   .interrupt + .join the fire-thread, shutdown the handler-pool
   (with bounded await), finally transition `:draining → :inactive`.
   Idempotent.

   `:drain-timeout-ms` (default 5000) bounds the wait for handler-pool
   tasks to finish.  After timeout, `.shutdownNow` is called."
  ([] (stop! {:drain-timeout-ms 5000}))
  ([{:keys [drain-timeout-ms] :or {drain-timeout-ms 5000}}]
   (let [{:keys [state fire-thread handler-pool]} (state/snapshot)]
     (case state
       :scheduler.state/inactive :already-inactive

       (do (state/transition-state! :scheduler.state/draining
                                    :reason "dispatcher stop!")
           ;; Wake the fire-thread so it observes the state change
           (when fire-thread
             (.interrupt ^Thread fire-thread)
             (try (.join ^Thread fire-thread drain-timeout-ms)
                  (catch InterruptedException _ nil)))
           ;; Drain handler-pool
           (when handler-pool
             (.shutdown ^ExecutorService handler-pool)
             (try (when-not (.awaitTermination ^ExecutorService handler-pool
                                               drain-timeout-ms
                                               TimeUnit/MILLISECONDS)
                    (.shutdownNow ^ExecutorService handler-pool))
                  (catch InterruptedException _
                    (.shutdownNow ^ExecutorService handler-pool))))
           (state/swap-state! assoc :fire-thread nil :handler-pool nil)
           (state/transition-state! :scheduler.state/inactive
                                    :reason "dispatcher stop! (drain complete)")
           (logging/info ::stopped {} :db-only)
           :stopped)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recovery: rebuild queue from DB
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recompute-queue-from-db!
  "Walk all `:mm/Schedule` entities in the DB; compute each one's
   next-fire-at from `(now)`; rebuild the priority queue from
   scratch.  Returns the count of schedules placed in the queue.

   Called by `start!` (when wired by γ.3 lifecycle) OR by operators
   after a manual exdates / RRULE edit batch.  Replaces the queue
   atomically (the previous queue is discarded — callers that need
   to preserve pending fires must complete them BEFORE calling)."
  []
  (let [db   (d/db (datomic/conn))
        eids (->> (d/q '[:find ?e
                         :where [?e :dt/type :mm/Schedule]]
                       db)
                  (map first))
        now-inst (now)
        entries (->> eids
                     (keep (fn [eid]
                             (let [s (resolve-schedule eid)
                                   nfa (when s
                                         (compute-next-fire-at s now-inst))]
                               (when nfa [nfa eid])))))
        new-queue (reduce (fn [q [fa eid]]
                            (queue-conj-entry q fa eid))
                          (state/empty-queue)
                          entries)]
    (state/swap-state! assoc :queue new-queue)
    (interrupt-fire-thread!)
    (count new-queue)))
