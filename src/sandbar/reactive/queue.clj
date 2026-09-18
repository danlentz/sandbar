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
   - PER-ENTITY OWNERSHIP: an entity is owned by exactly one drainer from
     the moment it is taken until its sink dispatch completes, so two
     generations of the same entity can never be projected concurrently
     or out of order (see below).
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
   ATOMICALLY (`swap-vals!` + `dissoc`); and there is no bounded buffer to
   overflow.  `+default-buffer-size+` survives as a SOFT CAP that only
   logs when exceeded, so the health snapshot keeps its `:buffer-size` key.

   ## Ownership correction 2026-09-18 (Astra's P1 on the redesign)

   Taking an entry atomically stops two drainers taking the SAME entry; it
   did not serialize GENERATIONS of the same entity.  While one drainer's
   sink was still projecting the old payload, a new enqueue reinstated the
   eid and a second drainer (a concurrent `drain-all!`, or `stop!` draining
   on the calling thread after its join timeout) projected the new payload
   FIRST; the old sink finished last, so the final sink value was the old
   state with the dirty map empty — a stale file on disk under the
   filesystem-canonical ruling (codex/to-claude/2026-09-18T181049Z_-
   reliability-review-queue-race.md; probe codex/review-probes/queue-
   2026-09-18.clj).

   The correction: an entity is OWNED by the drainer that takes it, for the
   whole sink dispatch (`+in-flight+`).  A competing drainer that finds the
   entity in flight takes nothing; an enqueue that lands while it is in
   flight is recorded in the dirty map as usual and RE-DRAINED BY THE OWNER
   right after its dispatch completes (bounded by `+max-owner-redrains+` so
   one hot entity cannot starve the pass).  The take / release transitions
   are the only code that holds `+ownership-lock+`, for microseconds, never
   while a sink runs; the enqueue path stays a single lock-free atom swap.
   `stop!` no longer starts a competing drain after a join timeout: it
   retains the live worker handle, reports the incomplete shutdown (return
   value + `:REACTIVE/worker-stop-join-timeout` warn), and a later `stop!`
   joins the same handle.

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
   - `(stop!)` / `(stop! {:join-timeout-ms n})` — halt the worker
     synchronously; best-effort drains what is pending once the worker has
     exited.  Returns `{:stopped? bool :drained-on-stop n :remaining-dirty n
     :in-flight n}`; `:stopped? false` means the join timed out and the
     worker handle was retained (no competing drain was started).
   - `(enqueue-projection! eid post-tx-slots)` — the callback fn that
     registers via `(sandbar.reactive/register-callback! enqueue-projection!)`
   - `(drain-all!)` — drain every currently-dirty entity now, oldest-first
     (one pass).  Test / diagnostic / shutdown surface; safe alongside the
     worker (each entity is owned by exactly one drainer at a time).
   - `(register-sink! sink-fn)` — add a per-drain sink; sinks receive
     `[eid post-tx-slots]`
   - `(unregister-sink! sink-fn)` — identity-equality removal
   - `(clear-sinks!)` — test-only reset
   - `(health)` — snapshot of metric map (the backing fn for the
     `sandbar.reactive.health` MCP verb); 13 keys, shape unchanged
   - `(snapshot-dirty)` / `(snapshot-dirty-full)` / `(snapshot-in-flight)`
     — diagnostics"
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

(def ^:const +max-owner-redrains+
  "Fairness bound on the owner re-drain loop.  Each re-drain means a write
   landed while the owner's previous dispatch of the SAME entity was
   running; after this many consecutive re-drains within one ownership
   the owner releases the entity and leaves its newest state dirty for
   the next pass, so one hot entity cannot starve the rest of the dirty
   map.  Releasing there is safe: the previous dispatch has fully
   completed, so whoever takes the entity next projects strictly after
   it.  In ordinary operation a write rarely lands during a dispatch at
   all; this bound only bites under an in-process bulk loop hammering one
   entity."
  16)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State
;;
;; Coordinated state pieces:
;;   1. +dirty-entities+ — atom of {eid → {:first-enqueue Instant
;;                                         :slots post-tx-slots
;;                                         :gen long}}
;;                          THE work queue; the coalescing key AND the
;;                          payload store
;;   2. +in-flight+      — atom of #{eid}: entities whose sinks are running
;;                          RIGHT NOW, each owned by exactly one drainer
;;   3. +ownership-lock+ — monitor for the take / release transitions (the
;;                          only place both 1 and 2 are read+written together)
;;   4. +wake-chan+      — core.async channel, sliding-buffer 1; a pure
;;                          wake-up signal (carries no work)
;;   5. +metrics+        — atom of running counters + last-event timestamps
;;
;; defonce guarantees these survive hot-reload of the namespace (the
;; worker keeps running across `:reload` boundaries).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private +wake-chan+
  (a/chan (a/sliding-buffer 1)))

(defonce ^:private +dirty-entities+
  ;; {eid → {:first-enqueue Instant :slots post-tx-slots :gen long}}.
  ;; An entity stays here between its first enqueue and its take.
  ;; Re-enqueue of a dirty entity REPLACES :slots with the newer state and
  ;; bumps :gen, but does NOT touch :first-enqueue (preserves oldest-
  ;; pending-age semantics — how long the OLDEST entity has been waiting).
  (atom {}))

(defonce ^:private +in-flight+
  ;; #{eid} — entities whose sinks are running right now.  An eid is added
  ;; when a drainer takes it (`take-dirty!`) and removed when that SAME
  ;; drainer finishes (`release-or-retake!` / `release!`).  Membership
  ;; changes ONLY under +ownership-lock+, in the same critical section as
  ;; the dirty-map read that decides it.
  (atom #{}))

(defonce ^:private +ownership-lock+
  ;; Held for microseconds by the take / release transitions; NEVER held
  ;; while a sink runs.  The enqueue path does not take it.
  (Object.))

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
  ;; {:running?    bool  — the public flag (`health` :worker-running?);
  ;;                       true from start! until stop! is REQUESTED
  ;;  :worker-chan ch    — the channel `(a/thread ...)` returned; it yields
  ;;                       :worker-exited and closes when the loop exits.
  ;;                       RETAINED across a stop! whose join timed out, so
  ;;                       the next stop! / start! can still join it
  ;;  :live        atom  — THIS worker's own stop signal.  The loop reads
  ;;                       its own atom (not the shared flag), so a start!
  ;;                       after an incomplete stop! can never revive a
  ;;                       worker that is on its way out}
  (atom {:running? false :worker-chan nil :live nil}))


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

   If the entity is IN FLIGHT (its sinks are running right now), the fresh
   entry is recorded exactly the same way and the drainer that owns the
   entity re-drains it as soon as its current dispatch completes — never
   a competing drainer, so generations are always projected in order.

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
;; Ownership — take / release transitions (the only holders of the lock)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- take-dirty!
  "Take OWNERSHIP of `eid`: under the ownership lock, if the entity is
   dirty and NOT already in flight, remove its entry from the dirty map,
   mark it in flight, and return the entry.  The caller now owns the
   entity for the whole sink dispatch and MUST finish with
   `release-or-retake!` (or `release!` on the failure path).

   Returns nil when the entity is clean, or when another drainer owns it
   — that owner will re-drain anything that lands meanwhile, so the
   caller has nothing to do for this entity.

   Because removal and read happen in one swap, an enqueue that landed
   BEFORE this point is included in the returned :slots, and one that
   lands AFTER re-inserts the entity — to be re-drained by THIS owner at
   release time, never by a competing drainer (the in-flight mark refuses
   them).  No update can be lost either way."
  [eid]
  (locking +ownership-lock+
    (when-not (contains? @+in-flight+ eid)
      (let [[old _] (swap-vals! +dirty-entities+ dissoc eid)]
        (when-let [entry (get old eid)]
          (swap! +in-flight+ conj eid)
          entry)))))

(defn- release-or-retake!
  "The owner's step after a completed dispatch of `eid`.  Under the
   ownership lock:

   - when `retake?` and a write landed while the sinks ran (the entity is
     dirty again): take the newer entry and KEEP ownership — returns it
     for the owner to dispatch next;
   - otherwise: release ownership and return nil.  Anything dirty stays
     dirty for the next pass (the yield case).

   Atomic with the dirty-map read, so no write can slip between 'not
   dirty' and 'released' unobserved: after the release the entity is
   either clean, or dirty AND unowned — exactly the state the next pass
   takes."
  [eid retake?]
  (locking +ownership-lock+
    (or (when retake?
          (let [[old _] (swap-vals! +dirty-entities+ dissoc eid)]
            (get old eid)))
        (do (swap! +in-flight+ disj eid) nil))))

(defn- release!
  "Release ownership of `eid` without re-taking (the failure path)."
  [eid]
  (locking +ownership-lock+
    (swap! +in-flight+ disj eid))
  nil)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker — drains the dirty map + invokes sinks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- dispatch-sinks!
  "Invoke all registered sinks with [eid post-tx-slots].  Returns the
   set of failed sinks (empty if all succeeded).  A sink that throws
   (including the security refusals `sandbar.reactive.sinks` deliberately
   rethrows) is counted in `:sink-error-total` and logged; it never
   escapes this fn."
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

(defn- dispatch-entry!
  "Dispatch one taken entry's LATEST slots to every sink; updates metrics;
   emits structured logs.  The caller owns `eid`."
  [eid {:keys [first-enqueue slots gen]}]
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
      nil)))

(defn- drain-eid!
  "Own and drain `eid`: take ownership (nil → 0 dispatches: the entity is
   clean, or another drainer owns it and will re-drain whatever lands),
   dispatch its latest slots, then — if a write landed while the sinks
   ran — dispatch again as the SAME owner, up to `+max-owner-redrains+`
   times before yielding the newest state to the next pass.  Ownership is
   released in a `finally`, so a failure can never strand the entity in
   flight.

   Returns the number of dispatches performed (0 when not taken)."
  [eid]
  (if-let [entry (take-dirty! eid)]
    (let [owned? (volatile! true)]
      (try
        (loop [entry entry n 1]
          (dispatch-entry! eid entry)
          (let [retake? (<= n +max-owner-redrains+)]
            (if-let [next-entry (release-or-retake! eid retake?)]
              (do (log/debug :REACTIVE/owner-redrain
                             {:ident (:db/ident (:slots next-entry)) :eid eid
                              :redrain n :gen (:gen next-entry)})
                  (recur next-entry (inc n)))
              (do (vreset! owned? false)
                  (when-not retake?
                    (log/debug :REACTIVE/owner-redrain-yield
                               {:eid eid :dispatches n
                                :max-owner-redrains +max-owner-redrains+
                                :note "hot entity; its newest state waits for the next pass"}))
                  n))))
        (finally
          (when @owned? (release! eid)))))
    0))

(defn drain-all!
  "Drain every currently-dirty entity, oldest-first, in ONE pass over a
   snapshot of the dirty map.  Entities that become dirty during the pass
   are left for the next pass — EXCEPT a write to an entity this pass is
   dispatching, which the owning drainer re-drains itself before moving
   on — the worker loops until the map is empty, and any caller can
   invoke this directly.

   Safe to call concurrently with the running worker (or another caller):
   an entity is OWNED by exactly one drainer from take through the end of
   its sink dispatch, so two generations of one entity are never
   projected concurrently or out of order; a drainer that meets an owned
   entity skips it (its owner re-drains).  A sink failure is logged +
   counted, never rethrown; a drainer-level failure on one entity is
   logged and the pass continues.

   Returns the number of projections dispatched in this pass (an entity
   re-drained by its owner counts once per dispatch, matching the
   `:drain-total` increment).

   Test / diagnostic / shutdown surface (the worker calls it on every
   wake-up and idle tick)."
  []
  (let [snapshot @+dirty-entities+
        ordered  (sort-by (fn [[_ e]] (.toEpochMilli ^Instant (:first-enqueue e)))
                          snapshot)]
    (reduce (fn [n [eid _]]
              (try
                (+ n (drain-eid! eid))
                (catch Throwable t
                  (log/error t :REACTIVE/worker-iteration-failed {:eid eid})
                  n)))
            0
            ordered)))

(defn- worker-loop
  "The worker thread body: block until woken (or the idle sweep fires),
   then drain everything dirty; repeat until THIS worker's `live?` flips
   to false, then run one final sweep and exit.  Runs on a real thread
   (`a/thread`), not a go block — sinks do filesystem IO.

   The final sweep is the exiting worker flushing whatever landed during
   its last pass, so a `stop!` whose join timed out (and which therefore
   drained nothing on the calling thread) still leaves nothing behind
   once this thread gets there."
  [live?]
  (a/thread
    (loop []
      (if @live?
        (do
          (a/alts!! [+wake-chan+ (a/timeout +idle-sweep-ms+)])
          (try
            (drain-all!)
            (catch Throwable t
              (log/error t :REACTIVE/worker-pass-failed {})))
          (recur))
        (try
          (drain-all!)
          (catch Throwable t
            (log/error t :REACTIVE/worker-final-sweep-failed {})))))
    :worker-exited))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start!
  "Start the projection worker thread.  Idempotent — calling on an
   already-running worker is a no-op (returns the existing worker
   channel).  Returns the worker channel.

   After a `stop!` whose join timed out, the previous worker may still be
   finishing its last dispatch; it can never resume (it reads its own
   `live?` signal, already false), so a fresh worker is started alongside
   it and the overlap is logged (`:REACTIVE/worker-previous-still-exiting`).
   Per-entity ownership keeps the brief overlap correct."
  []
  (let [live?     (atom true)
        [old _]   (swap-vals! +worker-state+
                              (fn [s] (if (:running? s)
                                        s
                                        (assoc s :running? true :live live?))))]
    (if (:running? old)
      (do
        (log/debug :REACTIVE/worker-already-running)
        (:worker-chan old))
      (let [retained (:worker-chan old)]
        (when (and retained (nil? (a/poll! retained)))
          (log/warn :REACTIVE/worker-previous-still-exiting
                    {:note "a worker whose stop! join timed out is still finishing its last dispatch; starting a fresh one"}))
        (let [wc (worker-loop live?)]
          (swap! +worker-state+ assoc :worker-chan wc)
          (log/info :REACTIVE/worker-started
                    {:buffer-size   +default-buffer-size+
                     :idle-sweep-ms +idle-sweep-ms+})
          wc)))))

(def ^:const +stop-join-timeout-ms+
  "How long `stop!` waits for the worker thread to exit before reporting
   an incomplete shutdown (it always exits within +idle-sweep-ms+ once
   woken unless a sink is still running; this is a backstop)."
  5000)

(defn stop!
  "Halt the projection worker SYNCHRONOUSLY: flips :running? false,
   signals the loop so it notices at once, waits (bounded by
   `:join-timeout-ms`, default +stop-join-timeout-ms+) for the thread to
   exit, then best-effort drains whatever is still dirty on the calling
   thread so a clean shutdown leaves nothing unprojected.  Idempotent.

   Returns `{:stopped? bool :drained-on-stop n :remaining-dirty n
   :in-flight n}`.

   If the join TIMES OUT (a sink is still running), `stop!` does NOT drain
   on the calling thread — that was a competing drainer, and it projected
   the newer generation of an in-flight entity ahead of the older one
   (Astra's P1, 2026-09-18).  Instead it RETAINS the worker handle,
   returns `:stopped? false`, and warns `:REACTIVE/worker-stop-join-timeout`;
   the worker finishes its dispatch, re-drains anything that landed for the
   entity it owns, runs a final sweep, and exits on its own.  A later
   `stop!` joins the retained handle and completes the shutdown.

   Synchronous on purpose: a stop that returned while the thread was
   still draining let a just-stopped worker take entities out from under
   the next caller (observed in the 2026-09-18 test suite)."
  ([] (stop! {}))
  ([{:keys [join-timeout-ms] :or {join-timeout-ms +stop-join-timeout-ms+}}]
   (let [{:keys [worker-chan live]} @+worker-state+]
     (swap! +worker-state+ assoc :running? false)
     (when live (reset! live false))
     (a/offer! +wake-chan+ :stop)
     (let [exited? (or (nil? worker-chan)
                       (let [[_ ch] (a/alts!! [worker-chan (a/timeout join-timeout-ms)])]
                         (identical? ch worker-chan)))]
       (if exited?
         (do
           (swap! +worker-state+ assoc :worker-chan nil :live nil)
           (let [drained (try (drain-all!) (catch Throwable _ 0))
                 result  {:stopped?        true
                          :drained-on-stop drained
                          :remaining-dirty (count @+dirty-entities+)
                          :in-flight       (count @+in-flight+)}]
             (log/info :REACTIVE/worker-stopped
                       (merge {:final-enqueue-total (:enqueue-total @+metrics+)
                               :final-drain-total   (:drain-total @+metrics+)}
                              result))
             result))
         (let [result {:stopped?        false
                       :drained-on-stop 0
                       :remaining-dirty (count @+dirty-entities+)
                       :in-flight       (count @+in-flight+)}]
           (log/warn :REACTIVE/worker-stop-join-timeout
                     (merge {:timeout-ms join-timeout-ms
                             :note       (str "worker handle retained; no competing drain started;"
                                              " the worker finishes and exits on its own —"
                                              " call stop! again to join it")}
                            result))
           result))))))


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
     :worker-running?        bool (start! called and stop! not yet requested)
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

(defn snapshot-in-flight
  "Return the set of eids whose sinks are running right now, each owned
   by exactly one drainer.  Empty whenever no dispatch is in progress.
   Diagnostic only (not one of `health`'s 13 keys)."
  []
  @+in-flight+)

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
