(ns sandbar.reactive.queue
  "Dirty-map work queue + worker for the reactive-projection pipeline.

   ## What this exists for

   `sandbar.reactive` ships the hook entry-point + opt-out mechanism at
   the dt/* substrate primitive boundary.  This namespace ships what
   happens AFTER the hook fires:

   - A DIRTY MAP that IS the work queue: `{eid {:first-enqueue instant
     :slots latest-post-tx-slots :gen n}}`.  Producer-side never blocks
     (one atom swap), nothing is ever dropped, and repeated mutations to
     the same entity collapse to ONE entry carrying the LATEST state.
   - A WAKE-UP CHANNEL (core.async, sliding-buffer 1) that only signals
     the worker; it carries no work, so a lost signal loses nothing — the
     worker also sweeps the dirty map on an idle tick.
   - A WORKER thread draining the dirty map oldest-first + invoking each
     registered SINK (codec.emit + fs.write + SSE.emit) with the latest
     slots.
   - HEALTH METRICS (dirty-entity-count, throughput, error rate,
     oldest-pending-age-ms) exposed via the `sandbar.reactive.health`
     MCP verb.

   ## Redesign 2026-09-18 (reliability sprint, item 2.1)

   The original design (Stage A.6, 2026-05-23) was a bounded sliding-
   buffer channel of TASKS plus a separate dirty map of timestamps.  Two
   defects, both found by Astra's review and verified at source:

   1. COALESCING LOST THE NEWER PAYLOAD.  An enqueue for an already-dirty
      entity returned without storing the new slots; the worker then
      dispatched the FIRST-queued snapshot and cleared the dirty flag, so
      the file on disk carried the older state while the database carried
      the newer one (bugs/reactive_queue_coalescing_discards_newer_payload_-
      drain_emits_original_snapshot_astra_finding_2026_09_18).
   2. OVERFLOW STRANDED ENTITIES.  Past 4,096 distinct dirty entities the
      sliding buffer evicted the OLDEST task silently while its dirty flag
      stayed; every later write to that entity coalesced against the stale
      flag and never projected again until restart (bugs/reactive_queue_-
      sliding_buffer_overflow_strands_dirty_entities_never_reenqueued_-
      astra_finding_2026_09_18).

   Under the same-day filesystem-canonical ruling both were data loss.
   The fix makes the dirty map the single source of truth: enqueue stores
   the latest slots (and bumps a generation counter); drain takes an entry
   ATOMICALLY (`swap-vals!` + `dissoc`), so an enqueue that lands during
   dispatch re-inserts the entity and it is drained on the next pass; and
   there is no bounded buffer to overflow.  `+default-buffer-size+`
   survives as a SOFT CAP that only logs when exceeded, so the health
   snapshot keeps its `:buffer-size` key.

   ## Decision lineage

   - `decisions/reactive_projection_queue_bounded_buffer_and_health_observability_2026_05_23.md`
     (eid 17592186094353) — per-entity coalescing + metric surface (the
     bounded-buffer half is superseded by the 2026-09-18 redesign above)
   - `decisions/reactive_projection_structured_logging_required_for_states_significant_actions_2026_05_23.md`
     (eid 17592186094433) — `:REACTIVE/<event-name>` log vocabulary
   - `plans/reliability_sprint_2026_09_18.md` item 2.1 — the redesign

   ## Public surface

   - `(start!)` — start the worker thread (idempotent; safe on hot-reload).
     Returns the worker channel (closes when the thread exits).
   - `(stop!)` — halt the worker; best-effort drains what is pending.
   - `(enqueue-projection! eid post-tx-slots)` — the callback fn that
     registers via `(sandbar.reactive/register-callback! enqueue-projection!)`
   - `(drain-all!)` — drain every currently-dirty entity now, oldest-first
     (one pass).  Test / diagnostic / shutdown surface; safe alongside the
     worker (each entry is taken by exactly one drainer).
   - `(register-sink! sink-fn)` — add a per-drain sink; sinks receive
     `[eid post-tx-slots]`
   - `(unregister-sink! sink-fn)` — identity-equality removal
   - `(clear-sinks!)` — test-only reset
   - `(health)` — snapshot of metric map (the backing fn for the
     `sandbar.reactive.health` MCP verb); 13 keys, shape unchanged
   - `(snapshot-dirty)` / `(snapshot-dirty-full)` — diagnostics"
  (:require [clojure.core.async    :as a]
            [clojure.tools.logging :as log])
  (:import [java.time Instant]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +default-buffer-size+
  "SOFT CAP on the dirty-set size.  Exceeding it logs
   `:REACTIVE/dirty-set-over-cap` at warn — NOTHING IS DROPPED; it means
   the worker is behind a bulk operation.  Historically the sliding-
   buffer capacity (and the reason overflow could strand entities);
   kept under this name because `health` reports it as `:buffer-size`
   and `sandbar.core/start` logs it."
  4096)

(def ^:const +saturation-warn-threshold-ms+
  "When the oldest pending entity has been waiting longer than this
   threshold, `health` reports `:saturated? true`."
  5000)

(def ^:const +idle-sweep-ms+
  "The worker re-checks the dirty map at least this often even without a
   wake-up signal, so a missed signal can never strand work."
  1000)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State
;;
;; Three coordinated state pieces:
;;   1. +dirty-entities+ — atom of {eid → {:first-enqueue Instant
;;                                         :slots post-tx-slots
;;                                         :gen long}}
;;                          THE work queue; the coalescing key AND the
;;                          payload store
;;   2. +wake-chan+      — core.async channel, sliding-buffer 1; a pure
;;                          wake-up signal (carries no work)
;;   3. +metrics+        — atom of running counters + last-event timestamps
;;
;; defonce guarantees these survive hot-reload of the namespace (the
;; worker keeps running across `:reload` boundaries).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private +wake-chan+
  (a/chan (a/sliding-buffer 1)))

(defonce ^:private +dirty-entities+
  ;; {eid → {:first-enqueue Instant :slots post-tx-slots :gen long}}.
  ;; An entity stays here between its first enqueue and its drain.
  ;; Re-enqueue of a dirty entity REPLACES :slots with the newer state and
  ;; bumps :gen, but does NOT touch :first-enqueue (preserves oldest-
  ;; pending-age semantics — how long the OLDEST entity has been waiting).
  (atom {}))

(defonce ^:private +metrics+
  (atom {:enqueue-total        0
         :drain-total          0
         :coalesce-total       0
         :sink-error-total     0
         :last-enqueue-instant nil
         :last-drain-instant   nil
         :startup-instant      (Instant/now)}))

(defonce ^:private +sinks+
  ;; Vector of sink fns; each invoked per drained entity with
  ;; [eid post-tx-slots] (the LATEST slots at drain time).
  (atom []))

(defonce ^:private +worker-state+
  ;; {:running? bool :worker-chan <thread-channel>}
  ;; The `:worker-chan` is the channel returned by `(a/thread ...)`,
  ;; which closes when the loop exits.  Stop signal flows via setting
  ;; `:running?` false (the loop notices within +idle-sweep-ms+).
  (atom {:running? false :worker-chan nil}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sink registry — codec.emit / fs.write / SSE.emit register here
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn register-sink!
  "Register a per-drain sink.  Sinks receive `[entity-eid post-tx-slots]`
   for each drained entity, with the LATEST slots enqueued for it.

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
  "Record that `eid` needs (re)projection with `post-tx-slots` as its
   latest state, and wake the worker.

   Per-entity coalescing: if the entity is already dirty, its stored
   slots are REPLACED by `post-tx-slots` and its generation counter is
   bumped (counter `:coalesce-total` increments); the drain that follows
   dispatches the latest state.  Otherwise a fresh entry is created
   (counter `:enqueue-total` increments).  Either way the worker is woken
   through the sliding-buffer-1 signal channel — a non-blocking `offer!`
   that never fails and never blocks the caller (this fn runs on the
   substrate write path).

   Nothing is ever dropped: the dirty map is unbounded; past
   `+default-buffer-size+` entries a warn is logged (`:REACTIVE/dirty-
   set-over-cap`) so a worker falling behind a bulk operation is visible.

   Per `decisions/reactive_projection_structured_logging...` §2.1:
   - `:REACTIVE/enqueue`  (debug) on fresh enqueue
   - `:REACTIVE/coalesce` (debug) on already-dirty entity

   This fn is the callback registered with sandbar.reactive via
   `(sandbar.reactive/register-callback! enqueue-projection!)`.

   Returns nil."
  [eid post-tx-slots]
  (let [now         (Instant/now)
        ident       (:db/ident post-tx-slots)
        class-ident (:dt/type post-tx-slots)
        [old new]   (swap-vals! +dirty-entities+
                                (fn [m]
                                  (if-let [e (get m eid)]
                                    (assoc m eid (-> e
                                                     (assoc :slots post-tx-slots)
                                                     (update :gen inc)))
                                    (assoc m eid {:first-enqueue now
                                                  :slots         post-tx-slots
                                                  :gen           0}))))
        coalesced?  (contains? old eid)
        dirty-count (count new)]
    (if coalesced?
      (do
        (swap! +metrics+ update :coalesce-total inc)
        (log/debug :REACTIVE/coalesce
                   {:ident            ident
                    :eid              eid
                    :class            class-ident
                    :first-enqueue-at (get-in old [eid :first-enqueue])
                    :gen              (get-in new [eid :gen])
                    :coalesce-total   (:coalesce-total @+metrics+)}))
      (do
        (swap! +metrics+ (fn [m] (-> m
                                     (update :enqueue-total inc)
                                     (assoc :last-enqueue-instant now))))
        (log/debug :REACTIVE/enqueue
                   {:ident              ident
                    :eid                eid
                    :class              class-ident
                    :dirty-entity-count dirty-count
                    :enqueue-total      (:enqueue-total @+metrics+)})))
    (when (= dirty-count (inc +default-buffer-size+))
      (log/warn :REACTIVE/dirty-set-over-cap
                {:dirty-entity-count dirty-count
                 :cap                +default-buffer-size+
                 :note               "nothing dropped; the worker is behind a bulk operation"}))
    ;; Wake the worker.  The channel is a signal, not the queue: a
    ;; sliding-buffer of 1 means offer! never blocks and never fails, and a
    ;; signal collapsed with an earlier one loses nothing because the
    ;; worker drains the whole dirty map on every wake-up.
    (a/offer! +wake-chan+ :wake)
    nil))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker — drains the dirty map + invokes sinks
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
                    {:eid              eid
                     :sink-class       (.getName (class sink))
                     :sink-error-total (:sink-error-total @+metrics+)}))))
    @failed))

(defn- take-dirty!
  "ATOMICALLY remove and return `eid`'s dirty entry (nil when not dirty).
   Because removal and read happen in one swap, an enqueue that lands
   AFTER this point sees the entity as clean and re-inserts it (to be
   drained on the next pass), while one that landed BEFORE is included in
   the returned :slots.  No update can be lost either way."
  [eid]
  (let [[old _] (swap-vals! +dirty-entities+ dissoc eid)]
    (get old eid)))

(defn- drain-eid!
  "Take `eid`'s dirty entry and dispatch its LATEST slots to every sink;
   updates metrics; emits structured logs.  Returns true when an entry was
   drained, nil when the entity was not dirty (already taken by a
   concurrent drainer)."
  [eid]
  (when-let [{:keys [first-enqueue slots gen]} (take-dirty! eid)]
    (let [start-instant (Instant/now)
          wait-ms       (- (.toEpochMilli start-instant)
                           (.toEpochMilli ^Instant first-enqueue))
          ident         (:db/ident slots)
          class-ident   (:dt/type slots)]
      (log/debug :REACTIVE/drain-start
                 {:ident ident :eid eid :class class-ident :wait-ms wait-ms :gen gen})
      (let [failed-sinks    (dispatch-sinks! eid slots)
            end-instant     (Instant/now)
            total-ms        (- (.toEpochMilli end-instant)
                               (.toEpochMilli ^Instant first-enqueue))
            sinks-attempted (count @+sinks+)
            sinks-failed    (count failed-sinks)
            sinks-succeeded (- sinks-attempted sinks-failed)]
        (swap! +metrics+ (fn [m] (-> m
                                     (update :drain-total inc)
                                     (assoc :last-drain-instant end-instant))))
        (cond
          (zero? sinks-attempted)
          (log/debug :REACTIVE/projection-noop
                     {:ident ident :eid eid :class class-ident :total-ms total-ms
                      :gen gen :reason :no-sinks-registered})

          (zero? sinks-failed)
          (log/info :REACTIVE/projection-success
                    {:ident ident :eid eid :class class-ident :total-ms total-ms
                     :gen gen :sinks-succeeded sinks-succeeded})

          :else
          (log/warn :REACTIVE/projection-partial
                    {:ident ident :eid eid :class class-ident :total-ms total-ms
                     :gen gen
                     :sinks-attempted sinks-attempted
                     :sinks-succeeded sinks-succeeded
                     :sinks-failed    sinks-failed}))
        true))))

(defn drain-all!
  "Drain every currently-dirty entity, oldest-first, in ONE pass over a
   snapshot of the dirty map.  Entities that become (or become again)
   dirty during the pass are left for the next pass — the worker loops
   until the map is empty, and any caller can invoke this directly.

   Safe to call concurrently with the running worker: each entry is taken
   atomically (`take-dirty!`), so an entity is dispatched by exactly one
   drainer.  A sink failure is logged + counted, never rethrown; a
   drainer-level failure on one entity is logged and the pass continues.

   Returns the number of entities drained in this pass.

   Test / diagnostic / shutdown surface (the worker calls it on every
   wake-up and idle tick)."
  []
  (let [snapshot @+dirty-entities+
        ordered  (sort-by (fn [[_ e]] (.toEpochMilli ^Instant (:first-enqueue e)))
                          snapshot)]
    (reduce (fn [n [eid _]]
              (try
                (if (drain-eid! eid) (inc n) n)
                (catch Throwable t
                  (log/error t :REACTIVE/worker-iteration-failed {:eid eid})
                  n)))
            0
            ordered)))

(defn- worker-loop
  "The worker thread body: block until woken (or the idle sweep fires),
   then drain everything dirty; repeat until `:running?` flips to false.
   Runs on a real thread (`a/thread`), not a go block — sinks do
   filesystem IO."
  []
  (a/thread
    (loop []
      (when (:running? @+worker-state+)
        (a/alts!! [+wake-chan+ (a/timeout +idle-sweep-ms+)])
        (try
          (drain-all!)
          (catch Throwable t
            (log/error t :REACTIVE/worker-pass-failed {})))
        (recur)))
    :worker-exited))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start!
  "Start the projection worker thread.  Idempotent — calling on an
   already-running worker is a no-op (returns the existing worker
   channel).  Returns the worker channel."
  []
  (if (:running? @+worker-state+)
    (do
      (log/debug :REACTIVE/worker-already-running)
      (:worker-chan @+worker-state+))
    (let [_  (swap! +worker-state+ assoc :running? true)
          wc (worker-loop)]
      (swap! +worker-state+ assoc :worker-chan wc)
      (log/info :REACTIVE/worker-started
                {:buffer-size   +default-buffer-size+
                 :idle-sweep-ms +idle-sweep-ms+})
      wc)))

(def ^:const +stop-join-timeout-ms+
  "How long `stop!` waits for the worker thread to exit before giving up
   (it always exits within +idle-sweep-ms+ once woken; this is a backstop)."
  5000)

(defn stop!
  "Halt the projection worker SYNCHRONOUSLY: flips :running? false, wakes
   the loop so it notices at once, waits (bounded by
   +stop-join-timeout-ms+) for the thread to exit, then best-effort drains
   whatever is still dirty on the calling thread so a clean shutdown
   leaves nothing unprojected.  Idempotent.  Returns nil.

   Synchronous on purpose: a stop that returned while the thread was
   still draining let a just-stopped worker take entities out from under
   the next caller (observed in the 2026-09-18 test suite)."
  []
  (let [wc (:worker-chan @+worker-state+)]
    (swap! +worker-state+ assoc :running? false)
    (a/offer! +wake-chan+ :stop)
    (when wc
      (let [[_ ch] (a/alts!! [wc (a/timeout +stop-join-timeout-ms+)])]
        (when-not (identical? ch wc)
          (log/warn :REACTIVE/worker-stop-join-timeout
                    {:timeout-ms +stop-join-timeout-ms+}))))
    (swap! +worker-state+ assoc :worker-chan nil)
    (let [drained (try (drain-all!) (catch Throwable _ 0))]
      (log/info :REACTIVE/worker-stopped
                {:final-enqueue-total (:enqueue-total @+metrics+)
                 :final-drain-total   (:drain-total @+metrics+)
                 :drained-on-stop     drained
                 :remaining-dirty     (count @+dirty-entities+)})))
  nil)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Health surface
;;
;; Returns a snapshot of the metric state.  Backing fn for the
;; sandbar.reactive.health MCP verb.  13 keys; shape unchanged by the
;; 2026-09-18 redesign (`:buffer-size` now means the soft cap).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- oldest-pending-age-ms
  "Age in milliseconds of the oldest entity in the dirty map.  nil
   when the map is empty."
  []
  (let [dirty @+dirty-entities+]
    (when (seq dirty)
      (let [oldest ^Instant (apply min-key #(.toEpochMilli ^Instant %)
                                   (map :first-enqueue (vals dirty)))]
        (- (.toEpochMilli (Instant/now))
           (.toEpochMilli oldest))))))

(defn health
  "Return a snapshot of reactive-projection pipeline health.

   Backing fn for the `sandbar.reactive.health` MCP verb.

   Returns a map with:
     :worker-running?        bool
     :buffer-size            int (the dirty-set SOFT cap; nothing is dropped)
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
  (let [m      @+metrics+
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
  "Return `{eid first-enqueue-instant}` for every dirty entity — the
   historical diagnostic shape.  See `snapshot-dirty-full` for the
   entries themselves."
  []
  (into {} (map (fn [[eid e]] [eid (:first-enqueue e)])) @+dirty-entities+))

(defn snapshot-dirty-full
  "Return the dirty map as-is: `{eid {:first-enqueue :slots :gen}}`.
   Diagnostic only."
  []
  @+dirty-entities+)

(defn reset-metrics!
  "Test-only: reset cumulative counters (preserves running worker +
   dirty map)."
  []
  (swap! +metrics+ (fn [m]
                     {:enqueue-total        0
                      :drain-total          0
                      :coalesce-total       0
                      :sink-error-total     0
                      :last-enqueue-instant nil
                      :last-drain-instant   nil
                      :startup-instant      (:startup-instant m)})))
