(ns sandbar.schedule.state
  "γ.2 — dispatcher state.

   The sandbar.schedule.* family's mutable runtime state lives here as an
   atom-inside-^:dynamic-var per Q.γ.2 of the γ.1 ADR (`:memory.decisions/
   gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_
   resolved_2026_05_27`).

   Topology — atom inside dyn-var:

     (def ^:dynamic *scheduler-state* (atom <initial>))

   Enables two scoping mechanisms simultaneously:

   1. Per-thread/per-test rebinding via `binding` — tests scope a fresh
      atom without affecting production state:

        (binding [*scheduler-state* (atom (initial-state))]
          (state/swap-state! assoc :enabled? true)
          ...)

   2. Runtime `swap!` mutation from MCP verbs / public API:

        (sandbar.schedule/enable!)   ;; → (state/swap-state! ...)

   State-machine transitions are guarded — illegal transitions throw
   ex-info rather than silently corrupt state.

   See:
     - ADR §1.2 (Q.γ.2 atom-inside-dyn-var ratified)
     - ADR §2.4 (full state-map shape)
     - sibling sandbar idioms:
        sandbar.db.datomic/**conn*
        sandbar.db.fn/*fn-base*
        sandbar.reactive/*reactive-projection-enabled?* + +callbacks+"
  (:require [sandbar.logging :as logging]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State-machine vocabulary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def scheduler-states
  "Canonical scheduler-state keyword set.

   - :scheduler.state/inactive — initial; no fire-thread; no handler-pool;
                                  queue may be empty or pre-populated from prior session
   - :scheduler.state/active   — fire-thread running + handler-pool open + accepting Schedules
   - :scheduler.state/paused   — fire-thread halted (queue preserved); no new fires;
                                  handler-pool may still drain in-flight runs
   - :scheduler.state/draining — fire-thread halted + handler-pool draining + queue snapshot
                                  preserved; transient state between paused/active and inactive"
  #{:scheduler.state/inactive
    :scheduler.state/active
    :scheduler.state/paused
    :scheduler.state/draining})

(def legal-transitions
  "Adjacency map governing the dispatcher state-machine.

   The transitions chosen reflect Q.γ.5 (default opt-in safety):

   - inactive ↔ active  : enable! / disable! while no in-flight runs
   - active   ↔ paused  : pause! / resume! preserves queue; in-flight runs allowed to finish
   - active   → draining: stop! triggers handler-pool drain
   - paused   → draining: stop! while paused
   - draining → inactive: post-drain transition; fully shut down"
  {:scheduler.state/inactive #{:scheduler.state/active}
   :scheduler.state/active   #{:scheduler.state/paused
                               :scheduler.state/draining}
   :scheduler.state/paused   #{:scheduler.state/active
                               :scheduler.state/draining}
   :scheduler.state/draining #{:scheduler.state/inactive}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queue comparator (sorted-set priority queue per Dan-directive sketch 2026-05-25)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def queue-comparator
  "Comparator for the [next-fire-at-instant schedule-eid] tuple priority queue.

   Primary sort: next-fire-at-instant ascending (earliest fire first).
   Secondary tie-break: schedule-eid ascending (deterministic ordering of
                        same-instant fires).

   Per γ.1 ADR §2.2 + Dan-directive 2026-05-25 sketch: `the event queue is
   a min-heap or priority queue. The min element determines when the next
   'wake up' should be scheduled for`."
  (fn [[^java.time.Instant a-instant a-eid] [^java.time.Instant b-instant b-eid]]
    (let [t-cmp (compare a-instant b-instant)]
      (if (zero? t-cmp)
        (compare a-eid b-eid)
        t-cmp))))

(defn empty-queue
  "Construct a fresh empty priority queue (Clojure sorted-set keyed by
   the queue-comparator above)."
  []
  (sorted-set-by queue-comparator))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn initial-state
  "Construct the initial scheduler-state map.

   Per γ.1 ADR §2.4.  All transient resources (fire-thread, handler-pool)
   nil at init; allocated on transition to :scheduler.state/active.

   Q.γ.5 ratification: `:enabled?` defaults FALSE (opt-in safety).
   `(sandbar.schedule/enable!)` OR `config.edn :scheduler/enabled? true`
   are needed to flip it.

   Q.γ.6 ratification: `:handler-pool-size` default 4."
  ([] (initial-state {}))
  ([{:keys [enabled? handler-pool-size clock-drift-threshold-ms]
     :or   {enabled?                    false
            handler-pool-size           4
            clock-drift-threshold-ms    5000}}]
   {:queue                      (empty-queue)
    :state                      :scheduler.state/inactive
    :fire-thread                nil
    :handler-pool               nil
    :enabled?                   enabled?
    :handler-pool-size          handler-pool-size
    :clock-drift-threshold-ms   clock-drift-threshold-ms
    :clock-drift-ms             0
    :fires-by-eid               {}
    :runs-by-eid                {}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The dyn-var + atom — single canonical state singleton
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *scheduler-state*
  "Atom holding the dispatcher's runtime state.

   Atom-inside-dyn-var per Q.γ.2 of γ.1 ADR — enables per-thread/per-test
   rebinding via `binding` AND runtime `swap!`-style mutation of the
   active scheduler from MCP verbs.

   Idiomatic sibling: `sandbar.db.datomic/**conn*` + `sandbar.reactive/+callbacks+`."
  (atom (initial-state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Accessors + mutators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn snapshot
  "Return the current state-map.  Pure read; no locking required since the
   atom's dereference is consistent within a single thread.

   Returns the entire state map — public API callsites typically destructure
   the keys they need: `(let [{:keys [state queue]} (state/snapshot)] ...)`."
  []
  @*scheduler-state*)

(defn swap-state!
  "Apply `f` to the current state map (atomically).  Wraps clojure.core/swap!.

   Per Q.γ.2 ADR — runtime mutation goes through this verb (not directly
   via `swap!` on the atom) so future invariant-checking / metric-tagging /
   audit-emission can land in one place.

   Returns the new state map (per swap! contract)."
  [f & args]
  (apply swap! *scheduler-state* f args))

(defn reset-state!
  "Reset the scheduler-state atom to the initial-state.  PUBLIC API for
   tests + operator-side recovery (e.g., post-crash cleanup).

   Idempotent — calling on an already-inactive scheduler is safe but
   silently drops any pending queue entries.  CALLERS in production must
   ensure (= :scheduler.state/inactive (:state (snapshot))) before invocation;
   otherwise in-flight runs may be orphaned.

   Returns the new state map."
  ([] (reset-state! {}))
  ([opts]
   (reset! *scheduler-state* (initial-state opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State-machine transitions (with guards)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn legal-transition?
  "Predicate — is the proposed `from → to` state transition allowed by the
   adjacency map?  Returns true / false."
  [from to]
  (boolean (and (contains? scheduler-states from)
                (contains? scheduler-states to)
                (contains? (get legal-transitions from #{}) to))))

(defn transition-state!
  "Atomically transition the scheduler from its current state to `to-state`,
   IF the adjacency map permits.  Throws ex-info on illegal transitions
   rather than silently corrupting state.

   The atomic CAS ensures concurrent `transition-state!` calls cannot
   double-transition.  Returns the new state map on success.

   Caller responsibility: any resource-allocation side-effects (e.g.,
   starting the fire-thread on inactive → active) are the caller's domain;
   this verb just guards + records the symbolic transition."
  [to-state & {:keys [reason]}]
  (let [result (swap-vals! *scheduler-state*
                           (fn [{:keys [state] :as s}]
                             (if (legal-transition? state to-state)
                               (assoc s :state to-state)
                               s)))
        [old new] result
        from (:state old)]
    (if (= (:state new) to-state)
      (do (logging/info ::transition
                        {:from from :to to-state :reason reason}
                        :db-only)
          new)
      (throw (ex-info (format "Illegal scheduler state transition: %s → %s"
                              from to-state)
                      {:from from
                       :to   to-state
                       :legal-targets (get legal-transitions from #{})
                       :reason reason})))))
