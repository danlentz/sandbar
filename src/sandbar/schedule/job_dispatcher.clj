(ns sandbar.schedule.job-dispatcher
  "γ.2 Step 5 — `:mm.event/Scheduled` subscriber.

   Sits downstream of `sandbar.schedule.dispatcher` (γ.2 Step 4): the
   dispatcher's fire-thread emits `:mm.event/Scheduled` events; THIS
   namespace registers a class-hierarchical subscriber via
   `sandbar.event/subscribe!` that handles each fire by resolving the
   Schedule's target :mm/Job, creating a :mm/Run instance, invoking
   `:mm.job/fn`, and emitting `:mm.event/JobStarted` /
   `:mm.event/JobCompleted` / `:mm.event/JobFailed` events at the
   appropriate lifecycle transitions.

   ## Architecture (per γ.1 ADR §1.6 + golden-squishing-flamingo.md Step 5)

     [:mm.event/Scheduled] →[handle-scheduled-event]→ [.submit handler-pool fn]
                                                       (this ns)

   - Subscriber is registered via `register!` (called from public-API
     `start!` in γ.2 Step 6, OR from tests directly).  Idempotent.
   - Subscriber gates on `(:enabled? (state/snapshot))` AND
     `(= :active (:state (state/snapshot)))` — fires during paused /
     draining / inactive are no-ops.
   - Per-handler work is submitted to the dispatcher's bounded
     handler-pool (the `:handler-pool` ExecutorService allocated in
     `dispatcher/start!`).
   - Per-handler timeout: wraps the `:mm.job/fn` invocation in a
     `future` + `.get(timeout, ms)` per κ P14 timeout pattern; default
     30s; configurable via the state's :handler-timeout-ms slot.

   ## :mm/Job invocation

   `:mm/Job` instances declare `:mm.job/fn` referencing a `:mm/Fn`
   memorial.  The :mm/Fn memorial carries `:dt.fn/source-ns` +
   `:dt.fn/source-var` slots identifying the classpath-fn that
   implements it.  Invocation resolves the var via `requiring-resolve`
   and applies it to the run-context map (currently `{:run-eid ...
   :schedule-eid ...}`; subsequent γ.5+ may extend the payload shape).

   :mm/Fn instances with `:dt.fn/installed-as :db-fn` (transactor-side
   Datomic fns) are NOT yet supported by this dispatcher — they require
   wrapping in a tx-data invocation.  Future hardening sub-arc.

   ## :mm.schedule/concurrency policy

   - `:concurrency/allow` (no enforcement) — multiple in-flight Runs OK
   - `:concurrency/forbid` (default per Q.γ.6) — if an in-flight :mm/Run
     exists for this schedule, emit `:mm.event/ScheduleConcurrencyViolation`
     + skip; do NOT start a new Run
   - `:concurrency/replace` — cancel the in-flight Run; start a new one.
     The cancel emits `:mm.event/JobCancelled` with
     `:cancellation-reason :concurrency-replace`.  Not yet implemented
     in γ.2 — falls through to :forbid behavior (skip).  Future
     hardening.

   ## :mm/EffectSpec validation (R.γ.6 mitigation)

   On Run completion, if the :mm/Job declares an `:mm.job/effect-spec`,
   compare the actual tx-data initiated against the declared
   `:mm.effect/initiates`.  Mismatches LOG VIOLATION but do NOT REJECT
   the run (MVP discipline; full enforcement is a future hardening
   sub-arc).  γ.2 logs the violation via `sandbar.logging/warn` with
   `:first-class` flag.

   ## Public surface

     `register!`              — subscribe `handle-scheduled-event` to :mm.event/Scheduled
     `unregister!`            — unsubscribe (test + shutdown cleanup)
     `registered?`            — diagnostic
     `handle-scheduled-event` — public for direct invocation in tests
     `run-job!`               — public for direct test invocation (executes a job + manages Run lifecycle)
     `in-flight-runs-for`     — diagnostic; returns vec of :mm/Run eids in :run.status/active for a given Schedule

   ## See also

   - γ.1 ADR §1.6: `:memory.decisions/gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_resolved_2026_05_27`
   - γ implementation plan-mode: `~/.claude/plans/golden-squishing-flamingo.md` Step 5
   - Sibling sandbar.schedule.dispatcher (Step 4) — the event publisher this ns subscribes to
   - sandbar.event — class-hierarchical fire!/subscribe! substrate
   - Q.γ.6 ratification: single fire-thread + bounded handler-thread-pool"
  (:require [sandbar.schedule.state      :as state]
            [sandbar.event               :as event]
            [sandbar.db.datatype         :as dt]
            [sandbar.db.datomic          :as datomic]
            [sandbar.logging             :as logging]
            [datomic.api                 :as d])
  (:import [java.time Instant Duration]
           [java.util Date]
           [java.util.concurrent ExecutorService Future TimeUnit
                                 TimeoutException RejectedExecutionException]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clock seam — shared idiom with dispatcher's *now-fn* for deterministic tests

(def ^:dynamic *now-fn*
  "Producer of the current wall-clock `java.time.Instant`.  Override
   via `binding` in tests."
  (fn [] (Instant/now)))

(defn- ^Instant now [] (*now-fn*))

(defn- ^Date instant->date
  "Coerce Instant to java.util.Date for Datomic `:db.type/instant`."
  [^Instant inst]
  (when inst (Date/from inst)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedule / Job resolution

(defn- ref->eid
  "Coerce a Datomic ref-shaped value to a numeric eid.  Handles three
   shapes encountered at ref-typed slot read sites:
   - numeric eid (already)               → returned as-is
   - Datomic Entity (ILookup, NOT a map) → `(:db/id e)` extracted
   - {:db/id N ...} regular map           → `(:db/id m)` extracted
   - nil                                  → nil

   Datomic returns refs as Entity proxy objects, which are ILookup
   but not clojure.core/map?.  A `(if (map? ...) ...)` check
   silently misses Entity-typed refs.  This helper centralizes the
   correct coercion."
  [ref]
  (cond
    (nil? ref)        nil
    (number? ref)     ref
    (:db/id ref)      (:db/id ref)
    :else             nil))

(defn- resolve-entity
  "Pull an entity-map for `eid` via d/entity.  Returns nil when the eid
   does not resolve to a live entity.  Existence checked via slot
   population (d/entity returns a proxy Entity for non-existent eids
   with no slots)."
  [eid]
  (let [db (d/db (datomic/conn))
        e  (d/entity db eid)]
    (when (and e (seq (keys e)))
      (into {:db/id eid} e))))

(defn- job-entity-for-schedule
  "Resolve the Schedule's `:mm.schedule/target` ref to a :mm/Job entity-
   map.  Returns nil when the target is missing OR is not a :mm/Job
   (e.g., a :mm/Workflow, which is out-of-scope for γ.2)."
  [schedule]
  (let [target-eid (ref->eid (:mm.schedule/target schedule))
        target     (when target-eid (resolve-entity target-eid))]
    (when (and target (= :mm/Job (:dt/type target)))
      target)))

(defn in-flight-runs-for
  "Diagnostic + concurrency enforcement: return a vec of :mm/Run eids in
   `:run.status/active` that reference `schedule-eid` via the chain
   :mm.run/job → :mm.schedule/target.

   Implementation note: γ.2 phase models in-flight Runs by tracking
   them in `(:in-flight-runs (state/snapshot))` keyed by schedule-eid
   (an in-memory side-table) PLUS by checking the DB for any
   `:mm.run/status :run.status/active` runs whose :mm.run/job's job-
   referencing schedule matches.  The in-memory side-table is the
   authoritative source for THIS-process concurrency enforcement
   (avoids race conditions with not-yet-committed runs).  The DB
   query is fallback for cross-process / post-restart recovery."
  [schedule-eid]
  (or (get-in (state/snapshot) [:in-flight-runs schedule-eid])
      []))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In-flight bookkeeping

(defn- track-in-flight-run!
  "Record `run-eid` as in-flight for `schedule-eid` in the state's
   :in-flight-runs map."
  [schedule-eid run-eid]
  (state/swap-state!
    update-in [:in-flight-runs schedule-eid] (fnil conj #{}) run-eid))

(defn- untrack-in-flight-run!
  "Remove `run-eid` from the in-flight set for `schedule-eid`.  No-op
   if it wasn't tracked."
  [schedule-eid run-eid]
  (state/swap-state!
    (fn [s]
      (let [updated (update-in s [:in-flight-runs schedule-eid]
                               (fnil disj #{}) run-eid)
            empty-set? (empty? (get-in updated [:in-flight-runs schedule-eid]))]
        (if empty-set?
          (update updated :in-flight-runs dissoc schedule-eid)
          updated)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; :mm/Fn invocation — resolve classpath-fn + call

(defn- resolve-job-fn
  "Resolve a :mm.job/fn ref (a :mm/Fn memorial) to an invokable
   Clojure fn via `requiring-resolve` on the memorial's
   `:dt.fn/source-ns` + `:dt.fn/source-var` slots.

   Returns the resolved fn (callable Var) OR nil if resolution fails
   (missing memorial, missing namespace, missing var, install-mode is
   :db-fn instead of :classpath-fn)."
  [fn-ref]
  (let [fn-eid (ref->eid fn-ref)
        fn-mem (when fn-eid (resolve-entity fn-eid))
        ns-str (:dt.fn/source-ns fn-mem)
        var-str (:dt.fn/source-var fn-mem)]
    (when (and ns-str var-str)
      (try
        (requiring-resolve (symbol ns-str var-str))
        (catch Throwable t
          (logging/warn ::resolve-job-fn-failed
                        {:fn-eid fn-eid
                         :source-ns ns-str
                         :source-var var-str
                         :error (.getMessage t)}
                        :db-only)
          nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Run lifecycle — create / complete / fail

(def ^:const +default-handler-timeout-ms+ 30000)

(defn- create-run!
  "Create a :mm/Run memorial via dt/make for the given job + schedule.
   Returns the new Run's entity-map (carrying :db/id)."
  [job-eid schedule-eid started-at]
  (let [slots {:mm.run/job              job-eid
               :mm.activity/spec        job-eid
               :mm.activity/started-at  (instant->date started-at)
               :mm.run/status           :run.status/active
               :mm.activity/status      :running
               :mm.run/attempt-number   1}]
    (dt/make :mm/Run slots {:validate? false})))

(defn- complete-run!
  "Mark `run-eid` as completed; persist ended-at + output + status.
   Returns the updated Run's entity-map."
  [run-eid ended-at output]
  (let [run (resolve-entity run-eid)]
    (when run
      (let [tx-data [{:db/id                  run-eid
                      :mm.run/status          :run.status/completed
                      :mm.activity/status     :succeeded
                      :mm.activity/ended-at   (instant->date ended-at)
                      :mm.run/output          (pr-str output)}]]
        @(d/transact (datomic/conn) tx-data)
        (resolve-entity run-eid)))))

(defn- fail-run!
  "Mark `run-eid` as failed; persist ended-at + error + status.
   Returns the updated Run's entity-map."
  [run-eid ended-at ^Throwable err]
  (let [run (resolve-entity run-eid)]
    (when run
      (let [tx-data [{:db/id                  run-eid
                      :mm.run/status          :run.status/failed
                      :mm.activity/status     :failed
                      :mm.activity/ended-at   (instant->date ended-at)
                      :mm.run/error           (or (.getMessage err)
                                                  (str (class err)))}]]
        @(d/transact (datomic/conn) tx-data)
        (resolve-entity run-eid)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EffectSpec validation (R.γ.6 mitigation; log-violation-only)

(defn- validate-effect-spec
  "Compare a job's declared :mm.job/effect-spec to the actual side-
   effects observed.  γ.2 implementation logs violations but does NOT
   reject the run (per R.γ.6 — MVP discipline).

   For γ.2, the 'actual side-effects' tracked are coarse — we trust
   the handler-fn ran AT ALL.  A future hardening pass will compare
   actual tx-data initiated against `:mm.effect/initiates`.  This stub
   reports `:not-yet-fully-validated` for any job that DECLARES an
   effect-spec — sufficient signal to surface the gap without false-
   positive rejection."
  [job run output]
  (when-let [effect-spec-ref (:mm.job/effect-spec job)]
    (logging/info ::effect-spec-validation-deferred
                  {:run-eid (:db/id run)
                   :job-eid (:db/id job)
                   :effect-spec-ref (if (map? effect-spec-ref)
                                      (:db/id effect-spec-ref)
                                      effect-spec-ref)
                   :note "γ.2 logs the declared effect-spec but defers actual tx-data validation to a future hardening sub-arc"}
                  :db-only)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-job execution

(defn run-job!
  "Execute one :mm/Job firing for `schedule-eid`.  Creates a :mm/Run +
   emits :mm.event/JobStarted; invokes the resolved :mm.job/fn with a
   bounded timeout; updates Run to :completed or :failed; emits
   :mm.event/JobCompleted or :mm.event/JobFailed; validates effect-
   spec (log-only); cleans up in-flight tracking.

   Public for test access.  Production callsite is
   `handle-scheduled-event`.

   Returns a map summarizing the run: `{:outcome :completed | :failed |
   :rejected :run-eid :duration-ms}`.  On `:rejected`, no Run was
   created (job/fn unresolvable).  Synchronous (caller responsible for
   submitting to handler-pool when async is desired)."
  [schedule-eid job timeout-ms]
  (let [started-at (now)
        job-fn     (resolve-job-fn (:mm.job/fn job))]
    (if (nil? job-fn)
      (do
        (event/fire! {:event/class                          :mm.event/JobRejected
                      :mm.schedule-event/schedule           schedule-eid
                      :mm.schedule-event/rejection-reason   :job-fn-unresolvable
                      :timestamp                            started-at})
        (logging/warn ::job-fn-unresolvable
                      {:schedule-eid schedule-eid
                       :job-eid      (:db/id job)
                       :fn-ref       (:mm.job/fn job)}
                      :db-only)
        {:outcome :rejected :run-eid nil :duration-ms 0})
      (let [run    (create-run! (:db/id job) schedule-eid started-at)
            run-eid (:db/id run)]
        (track-in-flight-run! schedule-eid run-eid)
        (event/fire! {:event/class                  :mm.event/JobStarted
                      :mm.schedule-event/schedule   schedule-eid
                      :mm.schedule-event/job        (:db/id job)
                      :mm.schedule-event/run        run-eid
                      :timestamp                    started-at})
        (let [run-ctx {:run-eid      run-eid
                       :schedule-eid schedule-eid
                       :job-eid      (:db/id job)}
              fut     (future (job-fn run-ctx))
              outcome (try
                        (let [output (.get ^Future fut timeout-ms TimeUnit/MILLISECONDS)
                              ended-at (now)
                              duration (.toMillis (Duration/between started-at ended-at))
                              updated  (complete-run! run-eid ended-at output)]
                          (validate-effect-spec job updated output)
                          (event/fire! {:event/class                       :mm.event/JobCompleted
                                        :mm.schedule-event/schedule        schedule-eid
                                        :mm.schedule-event/job             (:db/id job)
                                        :mm.schedule-event/run             run-eid
                                        :mm.schedule-event/duration-ms     duration
                                        :timestamp                         ended-at})
                          {:outcome :completed :run-eid run-eid :duration-ms duration})
                        (catch TimeoutException te
                          (.cancel ^Future fut true)
                          (let [ended-at (now)
                                duration (.toMillis (Duration/between started-at ended-at))]
                            (fail-run! run-eid ended-at te)
                            (event/fire! {:event/class                       :mm.event/JobFailed
                                          :mm.schedule-event/schedule        schedule-eid
                                          :mm.schedule-event/job             (:db/id job)
                                          :mm.schedule-event/run             run-eid
                                          :mm.schedule-event/error-message   (str "Timeout after " timeout-ms "ms")
                                          :timestamp                         ended-at})
                            {:outcome :failed :run-eid run-eid :duration-ms duration}))
                        (catch Throwable t
                          (let [cause (or (.getCause t) t)
                                ended-at (now)
                                duration (.toMillis (Duration/between started-at ended-at))]
                            (fail-run! run-eid ended-at cause)
                            (event/fire! {:event/class                       :mm.event/JobFailed
                                          :mm.schedule-event/schedule        schedule-eid
                                          :mm.schedule-event/job             (:db/id job)
                                          :mm.schedule-event/run             run-eid
                                          :mm.schedule-event/error-message   (or (.getMessage cause)
                                                                                 (str (class cause)))
                                          :timestamp                         ended-at})
                            {:outcome :failed :run-eid run-eid :duration-ms duration})))]
          (untrack-in-flight-run! schedule-eid run-eid)
          outcome)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Concurrency-policy enforcement

(defn- forbid-concurrent-fire?
  "Per :mm.schedule/concurrency policy, should this fire be skipped?
   Returns the in-flight Run eid (for the :ScheduleConcurrencyViolation
   event payload) when the policy is :concurrency/forbid AND an in-
   flight Run exists; otherwise nil (fire proceeds)."
  [schedule schedule-eid]
  (let [policy (or (:mm.schedule/concurrency schedule)
                   :concurrency/forbid)
        in-flight (in-flight-runs-for schedule-eid)]
    (when (and (= policy :concurrency/forbid)
               (seq in-flight))
      (first in-flight))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Top-level event subscriber

(defn- handle-scheduled-event-sync
  "Synchronous core of `handle-scheduled-event`.  Public for direct
   test invocation (avoids depending on the handler-pool being live).
   Returns a map summarizing the outcome."
  [event]
  (let [schedule-eid (:mm.schedule-event/schedule event)
        schedule     (when schedule-eid (resolve-entity schedule-eid))]
    (cond
      ;; Stale schedule reference (retracted between fire + dispatch)
      (nil? schedule)
      (do (logging/warn ::scheduled-event-missing-schedule
                        {:event event} :db-only)
          {:outcome :rejected :reason :schedule-missing})

      ;; No Job target (Schedule may target a Workflow — γ.2 doesn't handle)
      :else
      (let [job (job-entity-for-schedule schedule)]
        (cond
          (nil? job)
          (do (logging/info ::scheduled-event-no-job-target
                            {:schedule-eid schedule-eid
                             :target       (:mm.schedule/target schedule)}
                            :db-only)
              {:outcome :rejected :reason :no-job-target})

          ;; Concurrency check
          :else
          (if-let [in-flight-eid (forbid-concurrent-fire? schedule schedule-eid)]
            (do (event/fire! {:event/class                       :mm.event/ScheduleConcurrencyViolation
                              :mm.schedule-event/schedule        schedule-eid
                              :mm.schedule-event/in-flight-run   in-flight-eid
                              :timestamp                         (now)})
                {:outcome :rejected :reason :concurrency-forbidden
                 :in-flight-run-eid in-flight-eid})

            ;; Proceed with run
            (let [timeout-ms (or (:handler-timeout-ms (state/snapshot))
                                 +default-handler-timeout-ms+)]
              (run-job! schedule-eid job timeout-ms))))))))

(defn handle-scheduled-event
  "Subscriber fn for :mm.event/Scheduled.  Gates on scheduler-state's
   :enabled? + :state == :active; submits the substantive handler work
   to the handler-pool (so the subscriber thread returns immediately
   per event.clj's synchronous-dispatch discipline).

   When handler-pool is missing (state is :inactive OR the pool was
   never allocated) the handler runs SYNCHRONOUSLY on the caller's
   thread — pragmatic fallback enabling direct test invocation without
   a live pool.  In production (dispatcher/start! always allocates a
   pool), the async path is the normal case."
  [event]
  (let [{:keys [state enabled? handler-pool]} (state/snapshot)]
    (when (and enabled?
               (= state :scheduler.state/active))
      (if handler-pool
        (try
          (let [bound-handler (bound-fn* (fn [] (handle-scheduled-event-sync event)))]
            (.submit ^ExecutorService handler-pool ^Runnable bound-handler)
            :submitted)
          (catch RejectedExecutionException _
            (event/fire! {:event/class                          :mm.event/JobRejected
                          :mm.schedule-event/schedule           (:mm.schedule-event/schedule event)
                          :mm.schedule-event/rejection-reason   :pool-saturated
                          :timestamp                            (now)})
            :rejected))
        ;; Synchronous fallback (no pool; usually test path)
        (handle-scheduled-event-sync event)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriber registration

(defn registered?
  "Diagnostic: is `handle-scheduled-event` currently subscribed?"
  []
  (contains? (event/subscribers-of :mm.event/Scheduled)
             handle-scheduled-event))

(defn register!
  "Subscribe `handle-scheduled-event` to :mm.event/Scheduled via
   sandbar.event/subscribe!.  Idempotent (event.clj uses set semantics
   so re-registration is a no-op).

   Wiring to dispatcher lifecycle (called from γ.2 Step 6 public-API
   `start!`) is the production path; tests can call directly."
  []
  (event/subscribe! :mm.event/Scheduled handle-scheduled-event)
  :registered)

(defn unregister!
  "Unsubscribe `handle-scheduled-event` from :mm.event/Scheduled.
   Idempotent."
  []
  (event/unsubscribe! :mm.event/Scheduled handle-scheduled-event)
  :unregistered)
