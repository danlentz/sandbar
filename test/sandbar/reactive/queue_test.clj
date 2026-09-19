(ns sandbar.reactive.queue-test
  "Projection-queue contract after the 2026-09-18 redesign (reliability
  sprint item 2.1) and the same-day ownership correction (Astra's P1 on
  the redesign).  Pure in-process tests: no database, no filesystem —
  one recording sink and the queue's own public surface.

  Pins the two verified defects of the original sliding-buffer design,
  the concurrency defect of the first redesign, and the invariants that
  replace them:

   (a) COALESCING EMITS THE LATEST PAYLOAD — two enqueues before a drain
       dispatch the SECOND state (the old queue dispatched the first and
       cleared the dirty flag; bugs/reactive_queue_coalescing_discards_-
       newer_payload_drain_emits_original_snapshot_astra_finding_2026_09_18).
   (b) AN ENQUEUE DURING DISPATCH IS RE-DRAINED BY THE OWNER — a write that
       lands while the entity's sinks run is projected by the SAME drainer
       right after its dispatch completes, in order, before the pass
       returns (never by a competing drainer).
   (c) NOTHING IS STRANDED PAST THE OLD CAPACITY — 4,097 distinct entities
       all drain, and a later write to the first one drains too (the old
       sliding buffer evicted it and its dirty flag pinned it forever;
       bugs/reactive_queue_sliding_buffer_overflow_strands_dirty_entities_-
       never_reenqueued_astra_finding_2026_09_18).
   (d) OLDEST-FIRST ORDER and the 14-key health snapshot are preserved;
       ownership is released after a failing sink.
   (e) THE WORKER drains on a wake-up without any manual drain, and
       `stop!` leaves nothing dirty.
   (f) OVERLAPPING DRAINS SERIALIZE GENERATIONS — while one drainer's sink
       is still projecting the old state, a concurrent `drain-all!` takes
       nothing for that entity; the owner projects the new state afterwards,
       so the final sink value is the newest (the first redesign let the
       competing drainer project new-then-old: codex/to-claude/2026-09-18T-
       181049Z_reliability-review-queue-race.md).
   (g) STOP JOIN TIMEOUT RETAINS THE WORKER — `stop!` whose join times out
       reports `:stopped? false`, starts NO competing drain on the calling
       thread, and the worker finishes old-then-new on its own; a second
       `stop!` joins the retained handle.
   (h) A RESTART AFTER AN INCOMPLETE STOP KEEPS THE RETIRED WORKER JOINABLE —
       `start!` after a timed-out `stop!` moves the outgoing worker to a
       retired set instead of overwriting its handle; the next `stop!` joins
       every worker and stays incomplete while any is inside its sink
       (Astra's P2, codex/to-claude/2026-09-19T102851Z_queue-rereview-
       edf1a9b.md).
   (i) A START DURING A PENDING STOP KEEPS THE REPLACEMENT WORKER OWNED —
       an older `stop!` whose join is still pending when `start!` publishes a
       fresh worker reconciles the state by identity when it returns: the
       replacement keeps its handle and stop signal, a later `stop!` joins it
       and stays incomplete while its sink is blocked, and nothing dispatches
       after a completed shutdown (Astra's overlapping-lifecycle review of
       b3e5162, codex/reviews/2026-09-19T115111Z_queue-b3e5162-review.md:
       the timeout cleanup used to erase the replacement).

  Astra's isolated reproductions (codex/review-probes/queue-2026-09-18.clj,
  queue-rereview-2026-09-19.clj and the overlapping-lifecycle probe in her
  b3e5162 review) are the acceptance cases; (a), (c), (f), (g), (h), (i)
  are their in-repo twins."
  (:require [clojure.core.async :as a]
            [clojure.test :refer :all]
            [sandbar.reactive.queue :as q]))

(defn- quiesce!
  "Leave the JVM-wide queue state clean around each test."
  []
  (q/stop!)
  (q/clear-sinks!)
  (q/drain-all!)
  (q/reset-metrics!))

(use-fixtures :each (fn [t] (quiesce!) (t) (quiesce!)))

(defn- recording-sink
  "A sink that records [eid slots] pairs into `seen`."
  [seen]
  (fn [eid slots] (swap! seen conj [eid slots])))

(defn- slots [version] {:db/ident (keyword "test" (str "e" version)) :version version})

(defn- wait-until
  "Poll `pred` up to `timeout-ms`; true when it became truthy in time."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

(defn- blocking-old-sink
  "A sink that records each payload's :version into `seen`, but BLOCKS on
   the \"old\" version until `release-old` is delivered (delivering
   `entered` when it gets there).  The probe's shape: it holds one
   generation of an entity in flight while the test enqueues the next."
  [seen entered release-old]
  (fn [_ {:keys [version]}]
    (when (= version "old")
      (deliver entered true)
      (deref release-old 10000 :timeout))
    (swap! seen conj version)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) coalescing emits the latest payload
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest coalescing-dispatches-the-latest-state
  (testing "two enqueues before a drain: the sink sees the SECOND payload once"
    (let [seen (atom [])]
      (q/register-sink! (recording-sink seen))
      (q/enqueue-projection! 1 (slots "first"))
      (q/enqueue-projection! 1 (slots "second"))
      (is (= 1 (count (q/snapshot-dirty))) "one dirty entry for the entity")
      (is (= 1 (get-in (q/snapshot-dirty-full) [1 :gen])) "generation bumped by the coalesce")
      (is (= 1 (q/drain-all!)) "one entity drained")
      (is (= [[1 (slots "second")]] @seen)
          "the LATEST state was dispatched (the old queue emitted \"first\")")
      (is (empty? (q/snapshot-dirty)) "dirty map empty after the drain")
      (let [h (q/health)]
        (is (= 1 (:enqueue-total h)))
        (is (= 1 (:coalesce-total h)))
        (is (= 1 (:drain-total h)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) an enqueue that lands during dispatch is re-drained by the owner
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest enqueue-during-dispatch-is-redrained-by-the-owner
  (testing "a write that lands while the entity's sinks are running is projected by the same drainer, right after, before the pass returns"
    (let [seen      (atom [])
          reentered (atom false)]
      (q/register-sink!
        (fn [eid s]
          (swap! seen conj [eid s])
          ;; Simulate a concurrent write landing mid-dispatch (exactly once).
          (when (compare-and-set! reentered false true)
            (q/enqueue-projection! eid (slots "during-dispatch"))
            (is (= #{7} (q/snapshot-in-flight)) "the entity is owned while its sink runs")
            (is (= 1 (count (q/snapshot-dirty))) "the mid-dispatch write is recorded as dirty"))))
      (q/enqueue-projection! 7 (slots "initial"))
      (is (= 2 (q/drain-all!))
          "ONE pass, TWO dispatches: the owner re-drained the mid-dispatch write itself")
      (is (= [[7 (slots "initial")] [7 (slots "during-dispatch")]] @seen)
          "both states projected, in order; nothing lost")
      (is (empty? (q/snapshot-dirty)) "nothing left for a later pass")
      (is (empty? (q/snapshot-in-flight)) "ownership released")
      (is (= 2 (:drain-total (q/health))) "each dispatch counted")
      (is (= 0 (q/drain-all!)) "a further pass has nothing to do"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) nothing is stranded past the old sliding-buffer capacity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest overflow-past-the-old-capacity-strands-nothing
  (testing "4,097 distinct entities all drain, and the first one still projects afterwards"
    (let [seen (atom [])
          n    (inc q/+default-buffer-size+)]
      (q/register-sink! (recording-sink seen))
      (doseq [eid (range 1 (inc n))]
        (q/enqueue-projection! eid (slots "initial")))
      (is (= n (count (q/snapshot-dirty))) "every entity is dirty (nothing dropped)")
      (is (= n (q/drain-all!)) "every entity drained in one pass")
      (is (= n (count @seen)))
      (is (some #(= 1 (first %)) @seen)
          "the FIRST entity (the one the old sliding buffer evicted) was projected")
      (reset! seen [])
      (q/enqueue-projection! 1 (slots "retry"))
      (is (= 1 (count (q/snapshot-dirty))) "a later write to entity 1 is accepted as dirty")
      (is (= 1 (q/drain-all!)))
      (is (= [[1 (slots "retry")]] @seen)
          "entity 1 projects again (was stranded forever before the fix)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (d) oldest-first order + health shape + ownership released on failure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest drain-order-is-oldest-first-and-health-shape-is-preserved
  (testing "entities drain in first-enqueue order; a coalesce does not move an entity later"
    (let [seen (atom [])]
      (q/register-sink! (recording-sink seen))
      (q/enqueue-projection! 10 (slots "a"))
      (Thread/sleep 2)
      (q/enqueue-projection! 20 (slots "b"))
      (Thread/sleep 2)
      (q/enqueue-projection! 30 (slots "c"))
      (q/enqueue-projection! 10 (slots "a2")) ; coalesce keeps 10 at the front
      (q/drain-all!)
      (is (= [10 20 30] (mapv first @seen)) "oldest first")
      (is (= (slots "a2") (second (first @seen))) "and with its latest state")))
  (testing "health keeps its 14 keys and the historical :buffer-size"
    (let [h (q/health)]
      (is (= #{:worker-running? :buffer-size :dirty-entity-count :oldest-pending-age-ms
               :enqueue-total :drain-total :coalesce-total :sink-error-total
               :registered-sinks :saturated? :startup-instant :last-enqueue-instant
               :last-drain-instant :retired-workers}
             (set (keys h))))
      (is (= 0 (:retired-workers h)))
      (is (= q/+default-buffer-size+ (:buffer-size h)))
      (is (false? (:saturated? h)))))
  (testing "a failing sink is counted, never rethrown, the entity is still cleared, and ownership is released"
    (q/clear-sinks!)
    (q/register-sink! (fn [_ _] (throw (ex-info "boom" {}))))
    (q/enqueue-projection! 99 (slots "doomed"))
    (is (= 1 (q/drain-all!)))
    (is (= 1 (:sink-error-total (q/health))))
    (is (empty? (q/snapshot-dirty)))
    (is (empty? (q/snapshot-in-flight)) "a failed dispatch does not strand the entity in flight")
    (q/enqueue-projection! 99 (slots "again"))
    (is (= 1 (q/drain-all!)) "and the entity can be taken again")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (e) the worker drains on a wake-up; stop! leaves nothing dirty
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest worker-drains-on-wakeup-and-stop-flushes
  (let [seen (atom [])]
    (q/register-sink! (recording-sink seen))
    (try
      (q/start!)
      (is (true? (:worker-running? (q/health))))
      (q/enqueue-projection! 42 (slots "woken"))
      (is (wait-until #(= 1 (count @seen)) 3000)
          "the worker drained the entity without any manual drain")
      (is (= [[42 (slots "woken")]] @seen))
      (is (wait-until #(empty? (q/snapshot-dirty)) 1000))
      (finally
        (let [result (q/stop!)]
          (is (true? (:stopped? result)) "a worker idle in its loop stops within the join timeout")
          (is (= 0 (:in-flight result))))))
    (is (false? (:worker-running? (q/health))))
    (is (empty? (q/snapshot-dirty)) "stop! leaves nothing dirty")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (f) overlapping drains serialize generations of one entity
;;
;; Port of the probe's "overlapping drains" case.  Before the ownership
;; correction the second drain-all! took the re-inserted entry and
;; projected "new" while "old" was still in its sink: completion order
;; ["new" "old"], final sink value "old", dirty map empty.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest overlapping-drains-serialize-generations-of-one-entity
  (testing "while one drainer's sink projects the old state, a concurrent drain-all! takes nothing for that entity; the owner projects the new state afterwards"
    (let [entered     (promise)
          release-old (promise)
          seen        (atom [])]
      (q/register-sink! (blocking-old-sink seen entered release-old))
      (q/enqueue-projection! 7 {:version "old"})
      (let [first-drain (future (q/drain-all!))]
        (try
          (is (deref entered 3000 false) "the first drain reached the sink")
          (q/enqueue-projection! 7 {:version "new"})
          (is (= 1 (count (q/snapshot-dirty))) "the new state is dirty while the old one is in flight")
          (is (= #{7} (q/snapshot-in-flight)) "and the entity is owned by the first drainer")
          (let [second-drain  (future (q/drain-all!))
                second-result (deref second-drain 1500 :pending)]
            (is (= 0 second-result)
                "the competing drainer completes at once having taken NOTHING: the entity is owned")
            (is (= [] @seen) "nothing was projected while the old sink is still running")
            (is (= 1 (count (q/snapshot-dirty))) "the new state is still queued for the owner")
            (deliver release-old :release)
            (is (= 2 (deref first-drain 3000 :timeout))
                "the owner dispatched old, then re-drained new, within its own pass")
            (is (= ["old" "new"] @seen) "generations in order; the final sink value is the newest")
            (is (empty? (q/snapshot-dirty)))
            (is (empty? (q/snapshot-in-flight)))
            (is (= 2 (:drain-total (q/health)))))
          (finally
            (deliver release-old :release)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (g) a stop! whose join times out retains the worker and never competes
;;
;; Port of the probe's "stop timeout" case.  Before the correction, stop!
;; cleared the worker handle after its join timeout and drained on the
;; calling thread while the worker was still inside its sink: "new" was
;; projected before "old" finished, final sink value "old".  A short join
;; timeout keeps the test fast; the default is +stop-join-timeout-ms+.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stop-join-timeout-retains-the-worker-and-never-competes
  (testing "stop! reports an incomplete shutdown, drains nothing itself, and the worker finishes old then new"
    (let [entered     (promise)
          release-old (promise)
          seen        (atom [])]
      (q/register-sink! (blocking-old-sink seen entered release-old))
      (try
        (q/start!)
        (q/enqueue-projection! 8 {:version "old"})
        (is (deref entered 3000 false) "the worker reached the sink")
        (q/enqueue-projection! 8 {:version "new"})
        (let [started (System/currentTimeMillis)
              result  (q/stop! {:join-timeout-ms 200})
              elapsed (- (System/currentTimeMillis) started)]
          (is (false? (:stopped? result)) "the join timed out: incomplete shutdown reported")
          (is (< elapsed 3000) "stop! returned after its bounded join, not after the sink")
          (is (= 0 (:drained-on-stop result)) "NO competing drain on the calling thread")
          (is (= 1 (:remaining-dirty result)) "the new state is still dirty ...")
          (is (= 1 (:in-flight result)) "... and the entity is still owned by the worker")
          (is (= [] @seen) "the new state was NOT projected ahead of the old one"))
        (is (false? (:worker-running? (q/health))) "the stop was requested")
        (deliver release-old :release)
        (is (wait-until #(= ["old" "new"] @seen) 3000)
            "the worker finished old, then re-drained new as the owner, on its own")
        (let [result (q/stop!)]
          (is (true? (:stopped? result)) "the second stop! joins the retained worker handle")
          (is (= 0 (:drained-on-stop result)) "nothing was left for the calling thread")
          (is (= 0 (:remaining-dirty result)))
          (is (= 0 (:in-flight result))))
        (is (empty? (q/snapshot-dirty)))
        (is (empty? (q/snapshot-in-flight)))
        (is (= 2 (:drain-total (q/health))))
        (finally
          (deliver release-old :release)
          (q/stop!))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (h) a restart after an incomplete stop! keeps the retired worker joinable
;;
;; Port of Astra's re-review probe (codex/review-probes/queue-rereview-
;; 2026-09-19.clj, "restarting-after-incomplete-stop-retains-all-worker-
;; ownership").  Before the correction, start! overwrote the sole stored
;; worker channel with the fresh worker, so the next stop! joined only that
;; one and reported {:stopped? true ... :in-flight 1} while the old worker
;; was still inside its sink; a filesystem write could then land after a
;; caller believed shutdown complete.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest restart-after-incomplete-stop-keeps-the-retired-worker-joinable
  (testing "stop! stays incomplete while any worker that can still dispatch is inside its sink, across a start!"
    (let [entered     (promise)
          release-old (promise)
          seen        (atom [])]
      (q/register-sink! (blocking-old-sink seen entered release-old))
      (try
        (let [first-worker (q/start!)]
          (q/enqueue-projection! 9 {:version "old"})
          (is (deref entered 3000 false) "the first worker reached the sink")
          (is (false? (:stopped? (q/stop! {:join-timeout-ms 100})))
              "incomplete: the first worker is inside its sink")
          (let [second-worker (q/start!)]
            (is (not (identical? first-worker second-worker)) "a fresh worker was started")
            (is (true? (:worker-running? (q/health))))
            (is (= 1 (:retired-workers (q/health))) "the first worker is retired, not forgotten")
            (let [result (q/stop! {:join-timeout-ms 200})]
              (is (false? (:stopped? result))
                  "shutdown must remain incomplete while a retired worker is inside its sink")
              (is (= 0 (:drained-on-stop result)) "no competing drain on the calling thread")
              (is (= 1 (:in-flight result)) "the entity is still owned by the retired worker")
              (is (= 1 (:retired-workers result)))
              (is (= [] @seen) "nothing was projected ahead of the old state"))))
        (is (false? (:worker-running? (q/health))))
        (deliver release-old :release)
        (is (wait-until #(= ["old"] @seen) 3000) "the retired worker finished its dispatch on its own")
        (let [result (q/stop!)]
          (is (true? (:stopped? result)) "the third stop! joins the retired worker")
          (is (= 0 (:in-flight result)))
          (is (= 0 (:remaining-dirty result)))
          (is (= 0 (:retired-workers result))))
        (is (= 0 (:retired-workers (q/health))))
        (is (empty? (q/snapshot-dirty)))
        (is (empty? (q/snapshot-in-flight)))
        (finally
          (deliver release-old :release)
          (q/stop!))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (i) a start! during a pending stop! keeps the replacement worker owned
;;
;; Port of Astra's overlapping-lifecycle probe (codex/reviews/2026-09-19T-
;; 115111Z_queue-b3e5162-review.md).  At b3e5162 the older stop!'s timeout
;; cleanup set :worker-chan nil :live nil :retired remaining from its OWN
;; snapshot, discarding the join handle and stop signal of a worker that
;; start! had published during the join; a later stop! then joined only the
;; old worker and reported {:stopped? true :in-flight 1 :retired-workers 0}
;; while the replacement was still inside its sink, and the replacement went
;; on dispatching new work after "complete" shutdowns.  The base (9bad7a8)
;; passed this probe because its timeout branch left the current handle
;; alone.  The state is now reconciled by identity after every join.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- exited?
  "True once a worker channel has yielded or closed (non-blocking)."
  [ch]
  (let [[_ c] (a/alts!! [ch] :default ::pending)]
    (not= c :default)))

(deftest start-during-a-pending-stop-keeps-the-replacement-worker-owned
  (testing "an older stop!'s timeout cleanup must not erase a worker started during its join"
    (let [old-entered (promise) release-old (promise)
          new-entered (promise) release-new (promise)
          after-stop  (promise)]
      (q/register-sink!
        (fn [eid _]
          (case (long eid)
            1 (do (deliver old-entered true) (deref release-old 10000 :timeout))
            2 (do (deliver new-entered true) (deref release-new 10000 :timeout))
            3 (deliver after-stop true)
            nil)))
      (try
        (let [old-worker (q/start!)]
          (q/enqueue-projection! 1 {})
          (is (deref old-entered 3000 false) "the old worker reached its sink")
          (let [first-stop (future (q/stop! {:join-timeout-ms 600}))]
            ;; The old stop! has claimed its worker and is waiting on its sink.
            (is (wait-until #(false? (:worker-running? (q/health))) 2000)
                "the stop was requested")
            (let [new-worker (q/start!)]
              (is (not (identical? old-worker new-worker)) "a fresh worker was started during the join")
              (q/enqueue-projection! 2 {})
              (is (deref new-entered 3000 false) "the replacement worker reached its sink")
              (let [r (deref first-stop 2500 :timeout)]
                (is (map? r) "the older stop! returned")
                (is (false? (:stopped? r)) "it timed out on its own blocked worker")
                (is (true? (:superseded? r)) "and reports that a start! superseded it"))
              (is (true? (:worker-running? (q/health))) "the replacement is still the running worker")
              ;; Finish the old worker before examining the replacement.
              (deliver release-old true)
              (is (wait-until #(exited? old-worker) 3000) "the old worker exited on its own")
              (let [r (q/stop! {:join-timeout-ms 100})]
                (is (false? (:stopped? r))
                    "a stop must remain incomplete while the replacement sink is blocked")
                (is (= 1 (:retired-workers r)) "the replacement is retired and joinable, not forgotten")
                (is (= 0 (:drained-on-stop r)) "no competing drain on the calling thread"))
              (deliver release-new true)
              (is (true? (:stopped? (q/stop! {:join-timeout-ms 2000})))
                  "the next stop! joins the replacement and completes the shutdown")
              ;; Every stop! completed.  A forgotten worker must not keep running.
              (q/enqueue-projection! 3 {})
              (is (false? (deref after-stop 1200 false))
                  "nothing dispatches after a completed shutdown")
              (is (exited? new-worker) "the replacement channel is closed")
              (is (= 0 (:retired-workers (q/health)))))))
        (finally
          (deliver release-old true)
          (deliver release-new true)
          (q/stop!))))))
