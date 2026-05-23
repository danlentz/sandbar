(ns sandbar.reactive.queue
  "Bounded core.async sliding-buffer queue + worker for the reactive-
   projection pipeline.

   ## What this exists for

   `sandbar.reactive` ships the hook entry-point + opt-out mechanism at
   the dt/* substrate primitive boundary.  This namespace ships what
   happens AFTER the hook fires:

   - A BOUNDED queue (core.async sliding-buffer) absorbing the
     per-mutation event stream; producer-side never blocks
   - PER-ENTITY COALESCING (dirty-entities atom) so repeated mutations
     to the same entity collapse to one effective queue slot
   - A WORKER go-loop draining the channel + invoking each registered
     SINK (Stage B.1 wires codec.emit + fs.write + SSE.emit as sinks)
   - HEALTH METRICS (queue-depth, dirty-entity-count, throughput, error
     rate, oldest-pending-age-ms) exposed via the `sandbar.reactive.health`
     MCP verb

   ## Decision lineage

   - `decisions/reactive_projection_queue_bounded_buffer_and_health_observability_2026_05_23.md`
     (eid 17592186094353) — sliding-buffer + per-entity coalescing +
     metric surface
   - `decisions/reactive_projection_structured_logging_required_for_states_significant_actions_2026_05_23.md`
     (eid 17592186094433) — `:REACTIVE/<event-name>` log vocabulary

   ## Stage tracking

   - Stage A.6 (THIS module): queue + worker + metrics + health verb;
     sinks default empty (no codec.emit / fs.write / SSE.emit yet)
   - Stage B.1+: register sinks for codec.emit / fs.write / SSE.emit

   ## Public surface

   - `(start! opts)` — start the worker go-loop (idempotent; safe on
     hot-reload).  Returns the worker channel.
   - `(stop!)` — halt the worker; drains pending tasks (best-effort)
   - `(enqueue-projection! eid post-tx-slots)` — the callback fn that
     registers via `(sandbar.reactive/register-callback! enqueue-projection!)`
   - `(register-sink! sink-fn)` — add a per-drain sink; sinks receive
     `[eid post-tx-slots]`; Stage B wires codec/fs/SSE here
   - `(unregister-sink! sink-fn)` — identity-equality removal
   - `(clear-sinks!)` — test-only reset
   - `(health)` — snapshot of metric map (the backing fn for the
     `sandbar.reactive.health` MCP verb)"
  (:require [clojure.core.async    :as a]
            [clojure.tools.logging :as log]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +default-buffer-size+
  "Sliding-buffer size.  4096 chosen as a safe headroom for typical
   bulk operations (today's corpus has ~1700 :mm/Memory entities so a
   full-corpus bulk-create or import-from-fs comfortably fits).
   Bench refinement lands in Stage E."
  4096)

(def ^:const +saturation-warn-threshold-ms+
  "When the oldest pending entity has been waiting longer than this
   threshold, emit :REACTIVE/queue-sustained-saturation at warn."
  5000)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State
;;
;; Three coordinated state pieces:
;;   1. +projection-chan+   — core.async channel with sliding-buffer
;;   2. +dirty-entities+    — atom of {eid → first-enqueue-instant};
;;                            per-entity coalescing key
;;   3. +metrics+           — atom of running counters + last-event
;;                            timestamps
;;
;; defonce guarantees these survive hot-reload of the namespace (the
;; channel + worker keep running across `:reload` boundaries).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private +projection-chan+
  (a/chan (a/sliding-buffer +default-buffer-size+)))

(defonce ^:private +dirty-entities+
  ;; Map of {eid → first-enqueue-instant}; an entity stays in the dirty
  ;; map between its enqueue and its drain.  Re-enqueue of an already-
  ;; dirty entity does NOT update the timestamp (preserves oldest-
  ;; pending-age semantics — we want to know how long the OLDEST queued
  ;; entity has been waiting).
  (atom {}))

(defonce ^:private +metrics+
  (atom {:enqueue-total        0
         :drain-total          0
         :coalesce-total       0
         :sink-error-total     0
         :last-enqueue-instant nil
         :last-drain-instant   nil
         :startup-instant      (java.time.Instant/now)}))

(defonce ^:private +sinks+
  ;; Vector of sink fns; each invoked per drained task with
  ;; [eid post-tx-slots].  Stage B wires codec.emit, fs.write, SSE.emit
  ;; as separate sinks.
  (atom []))

(defonce ^:private +worker-state+
  ;; {:running? bool :worker-chan <go-block-channel>}
  ;; The `:worker-chan` is the channel returned by `(a/go-loop ...)`
  ;; which closes when the loop exits.  Stop signal flows via setting
  ;; `:running?` false.
  (atom {:running? false :worker-chan nil}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sink registry — Stage B.1+ register codec.emit / fs.write / SSE.emit here
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn register-sink!
  "Register a per-drain sink.  Sinks receive `[entity-eid post-tx-slots]`
   for each drained task.

   Multiple sinks may register; they're invoked sequentially in
   registration order.  One failed sink doesn't block others (each
   wrapped in try/catch).

   Returns the sink fn (for unregister)."
  [sink-fn]
  (when (fn? sink-fn)
    (swap! +sinks+ conj sink-fn))
  sink-fn)

(defn unregister-sink!
  "Remove a previously-registered sink.  Identity-equality match."
  [sink-fn]
  (swap! +sinks+ (fn [ss] (vec (remove #(identical? % sink-fn) ss))))
  nil)

(defn clear-sinks!
  "Test-only: drop all registered sinks."
  []
  (reset! +sinks+ []))

(defn sink-count
  "Diagnostic: how many sinks currently registered?"
  []
  (count @+sinks+))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enqueue path — registered as a callback via sandbar.reactive
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn enqueue-projection!
  "Enqueue a reactive-projection task for `[eid post-tx-slots]`.

   Per-entity coalescing: if the entity is already in the dirty-set,
   marks the enqueue as a COALESCE (counter increments; no duplicate
   put to channel — the worker will pick up the latest state at drain
   time via the dirty-entities map).

   When the channel buffer is full, core.async's sliding-buffer drops
   the OLDEST queued task — semantically correct for last-state-wins
   projection (older task superseded by newer state of same entity).

   Per `decisions/reactive_projection_structured_logging...` §2.1:
   - `:REACTIVE/enqueue`  (debug) on fresh enqueue
   - `:REACTIVE/coalesce` (debug) on already-dirty entity

   This fn is the callback registered with sandbar.reactive via
   `(sandbar.reactive/register-callback! enqueue-projection!)`.

   Returns nil."
  [eid post-tx-slots]
  (let [now (java.time.Instant/now)
        already-dirty? (contains? @+dirty-entities+ eid)]
    (if already-dirty?
      (do
        (swap! +metrics+ update :coalesce-total inc)
        (log/debug :REACTIVE/coalesce
                   {:eid                eid
                    :class              (:dt/type post-tx-slots)
                    :first-enqueue-at   (get @+dirty-entities+ eid)
                    :coalesce-total     (:coalesce-total @+metrics+)})
        nil)
      (do
        (swap! +dirty-entities+ assoc eid now)
        (swap! +metrics+ (fn [m] (-> m
                                     (update :enqueue-total inc)
                                     (assoc :last-enqueue-instant now))))
        (a/put! +projection-chan+ {:eid eid :slots post-tx-slots :enqueued-at now})
        (log/debug :REACTIVE/enqueue
                   {:eid                eid
                    :class              (:dt/type post-tx-slots)
                    :dirty-entity-count (count @+dirty-entities+)
                    :enqueue-total      (:enqueue-total @+metrics+)})
        nil))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker — drains the channel + invokes sinks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- dispatch-sinks!
  "Invoke all registered sinks with [eid post-tx-slots].  Returns the
   set of failed sinks (empty if all succeeded)."
  [eid post-tx-slots]
  (let [failed (atom #{})]
    (doseq [sink @+sinks+]
      (try
        (sink eid post-tx-slots)
        (catch Throwable t
          (swap! failed conj sink)
          (swap! +metrics+ update :sink-error-total inc)
          (log/warn t :REACTIVE/sink-failed
                    {:eid             eid
                     :sink-class      (.getName (class sink))
                     :sink-error-total (:sink-error-total @+metrics+)}))))
    @failed))

(defn- drain-task!
  "Process one drained projection task.  Invokes all sinks; updates
   dirty-entities + metrics; emits structured logs."
  [{:keys [eid slots enqueued-at]}]
  (let [start-instant (java.time.Instant/now)
        wait-ms       (- (.toEpochMilli start-instant)
                         (.toEpochMilli enqueued-at))]
    (log/debug :REACTIVE/drain-start
               {:eid eid :class (:dt/type slots) :wait-ms wait-ms})
    (let [failed-sinks (dispatch-sinks! eid slots)
          end-instant  (java.time.Instant/now)
          total-ms     (- (.toEpochMilli end-instant)
                          (.toEpochMilli enqueued-at))
          sinks-attempted (count @+sinks+)
          sinks-failed    (count failed-sinks)
          sinks-succeeded (- sinks-attempted sinks-failed)]
      ;; Remove from dirty-set regardless of sink failure — the entity
      ;; was projected (or attempted); future mutations will re-enqueue
      (swap! +dirty-entities+ dissoc eid)
      (swap! +metrics+ (fn [m] (-> m
                                   (update :drain-total inc)
                                   (assoc :last-drain-instant end-instant))))
      (cond
        (zero? sinks-attempted)
        (log/debug :REACTIVE/projection-noop
                   {:eid eid :class (:dt/type slots) :total-ms total-ms
                    :reason :no-sinks-registered})

        (zero? sinks-failed)
        (log/info :REACTIVE/projection-success
                  {:eid eid :class (:dt/type slots) :total-ms total-ms
                   :sinks-succeeded sinks-succeeded})

        :else
        (log/warn :REACTIVE/projection-partial
                  {:eid eid :class (:dt/type slots) :total-ms total-ms
                   :sinks-attempted sinks-attempted
                   :sinks-succeeded sinks-succeeded
                   :sinks-failed    sinks-failed})))))

(defn- worker-loop
  "The worker go-loop body.  Drains +projection-chan+ until stopped.
   Exits when the channel closes OR :running? flips to false."
  []
  (a/go-loop []
    (when (:running? @+worker-state+)
      (when-let [task (a/<! +projection-chan+)]
        (try
          (drain-task! task)
          (catch Throwable t
            (log/error t :REACTIVE/worker-iteration-failed
                       {:eid (:eid task)})))
        (recur)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start!
  "Start the projection worker go-loop.  Idempotent — calling on an
   already-running worker is a no-op (returns the existing worker
   channel).  Returns the worker channel."
  []
  (if (:running? @+worker-state+)
    (do
      (log/debug :REACTIVE/worker-already-running)
      (:worker-chan @+worker-state+))
    (let [_ (swap! +worker-state+ assoc :running? true)
          wc (worker-loop)]
      (swap! +worker-state+ assoc :worker-chan wc)
      (log/info :REACTIVE/worker-started
                {:buffer-size +default-buffer-size+})
      wc)))

(defn stop!
  "Halt the projection worker.  Sets :running? false; the worker loop
   exits on its next iteration.  Returns nil."
  []
  (swap! +worker-state+ assoc :running? false)
  (log/info :REACTIVE/worker-stopped
            {:final-enqueue-total (:enqueue-total @+metrics+)
             :final-drain-total   (:drain-total @+metrics+)
             :remaining-dirty     (count @+dirty-entities+)})
  nil)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Health surface
;;
;; Returns a snapshot of the metric state.  Backing fn for the
;; sandbar.reactive.health MCP verb.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- oldest-pending-age-ms
  "Age in milliseconds of the oldest entity in the dirty-set.  nil
   when dirty-set is empty."
  []
  (let [dirty @+dirty-entities+]
    (when (seq dirty)
      (let [oldest-instant (apply min-key #(.toEpochMilli %) (vals dirty))]
        (- (.toEpochMilli (java.time.Instant/now))
           (.toEpochMilli oldest-instant))))))

(defn health
  "Return a snapshot of reactive-projection pipeline health.

   Backing fn for the `sandbar.reactive.health` MCP verb.

   Returns a map with:
     :worker-running?        bool
     :buffer-size            int (the sliding-buffer capacity)
     :dirty-entity-count     int (distinct entities pending projection)
     :oldest-pending-age-ms  int or nil
     :enqueue-total          int (cumulative since startup)
     :drain-total            int
     :coalesce-total         int (per-entity coalesce events)
     :sink-error-total       int (sink-fn failures)
     :registered-sinks       int (count of currently-registered sinks)
     :saturated?             bool (oldest-pending > threshold)
     :startup-instant        java.time.Instant
     :last-enqueue-instant   java.time.Instant or nil
     :last-drain-instant     java.time.Instant or nil"
  []
  (let [m @+metrics+
        oldest (oldest-pending-age-ms)]
    {:worker-running?       (:running? @+worker-state+)
     :buffer-size           +default-buffer-size+
     :dirty-entity-count    (count @+dirty-entities+)
     :oldest-pending-age-ms oldest
     :enqueue-total         (:enqueue-total m)
     :drain-total           (:drain-total m)
     :coalesce-total        (:coalesce-total m)
     :sink-error-total      (:sink-error-total m)
     :registered-sinks      (count @+sinks+)
     :saturated?            (boolean (and oldest (> oldest +saturation-warn-threshold-ms+)))
     :startup-instant       (:startup-instant m)
     :last-enqueue-instant  (:last-enqueue-instant m)
     :last-drain-instant    (:last-drain-instant m)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Diagnostic surface
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn snapshot-dirty
  "Return a snapshot of the dirty-entities map.  Diagnostic only."
  []
  @+dirty-entities+)

(defn reset-metrics!
  "Test-only: reset cumulative counters (preserves running worker +
   dirty-set)."
  []
  (swap! +metrics+ (fn [m]
                     {:enqueue-total        0
                      :drain-total          0
                      :coalesce-total       0
                      :sink-error-total     0
                      :last-enqueue-instant nil
                      :last-drain-instant   nil
                      :startup-instant      (:startup-instant m)})))
