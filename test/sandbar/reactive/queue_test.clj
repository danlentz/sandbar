(ns sandbar.reactive.queue-test
  "Projection-queue contract after the 2026-09-18 redesign (reliability
  sprint item 2.1).  Pure in-process tests: no database, no filesystem —
  one recording sink and the queue's own public surface.

  Pins the two verified defects of the original sliding-buffer design and
  the invariants that replace them:

   (a) COALESCING EMITS THE LATEST PAYLOAD — two enqueues before a drain
       dispatch the SECOND state (the old queue dispatched the first and
       cleared the dirty flag; bugs/reactive_queue_coalescing_discards_-
       newer_payload_drain_emits_original_snapshot_astra_finding_2026_09_18).
   (b) AN ENQUEUE DURING DISPATCH RE-PROJECTS — the entity is taken
       atomically, so a write that lands while its sinks run is drained on
       the next pass with the newer state.
   (c) NOTHING IS STRANDED PAST THE OLD CAPACITY — 4,097 distinct entities
       all drain, and a later write to the first one drains too (the old
       sliding buffer evicted it and its dirty flag pinned it forever;
       bugs/reactive_queue_sliding_buffer_overflow_strands_dirty_entities_-
       never_reenqueued_astra_finding_2026_09_18).
   (d) OLDEST-FIRST ORDER and the 13-key health snapshot are preserved.
   (e) THE WORKER drains on a wake-up without any manual drain, and
       `stop!` leaves nothing dirty.

  Astra's isolated reproductions of (a) and (c) are the acceptance
  cases; this suite is their in-repo twin."
  (:require [clojure.test :refer :all]
            [sandbar.reactive.queue :as q]))

(defn- quiesce!
  "Leave the JVM-wide queue state clean around each test."
  []
  (q/clear-sinks!)
  (q/drain-all!)
  (q/reset-metrics!))

(use-fixtures :each (fn [t] (quiesce!) (t) (quiesce!)))

(defn- recording-sink
  "A sink that records [eid slots] pairs into `seen`."
  [seen]
  (fn [eid slots] (swap! seen conj [eid slots])))

(defn- slots [version] {:db/ident (keyword "test" (str "e" version)) :version version})

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
;; (b) an enqueue that lands during dispatch re-projects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest enqueue-during-dispatch-is-drained-on-the-next-pass
  (testing "a write that lands while the entity's sinks are running is not lost"
    (let [seen     (atom [])
          reentered (atom false)]
      (q/register-sink!
        (fn [eid s]
          (swap! seen conj [eid s])
          ;; Simulate a concurrent write landing mid-dispatch (exactly once).
          (when (compare-and-set! reentered false true)
            (q/enqueue-projection! eid (slots "during-dispatch")))))
      (q/enqueue-projection! 7 (slots "initial"))
      (is (= 1 (q/drain-all!)) "first pass drains the initial entry")
      (is (= 1 (count (q/snapshot-dirty)))
          "the mid-dispatch write re-inserted the entity as dirty")
      (is (= 1 (q/drain-all!)) "second pass drains the newer state")
      (is (= [[7 (slots "initial")] [7 (slots "during-dispatch")]] @seen)
          "both states projected, in order; nothing lost")
      (is (empty? (q/snapshot-dirty))))))

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
;; (d) oldest-first order + health shape
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
  (testing "health keeps its 13 keys and the historical :buffer-size"
    (let [h (q/health)]
      (is (= #{:worker-running? :buffer-size :dirty-entity-count :oldest-pending-age-ms
               :enqueue-total :drain-total :coalesce-total :sink-error-total
               :registered-sinks :saturated? :startup-instant :last-enqueue-instant
               :last-drain-instant}
             (set (keys h))))
      (is (= q/+default-buffer-size+ (:buffer-size h)))
      (is (false? (:saturated? h)))))
  (testing "a failing sink is counted, never rethrown, and the entity is still cleared"
    (q/clear-sinks!)
    (q/register-sink! (fn [_ _] (throw (ex-info "boom" {}))))
    (q/enqueue-projection! 99 (slots "doomed"))
    (is (= 1 (q/drain-all!)))
    (is (= 1 (:sink-error-total (q/health))))
    (is (empty? (q/snapshot-dirty)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (e) the worker drains on a wake-up; stop! leaves nothing dirty
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- wait-until
  "Poll `pred` up to `timeout-ms`; true when it became truthy in time."
  [pred timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (Thread/sleep 10) (recur))))))

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
        (q/stop!)))
    (is (false? (:worker-running? (q/health))))
    (is (empty? (q/snapshot-dirty)) "stop! leaves nothing dirty")))
