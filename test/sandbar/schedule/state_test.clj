(ns sandbar.schedule.state-test
  "Unit tests for sandbar.schedule.state — γ.2 state-machine + dyn-var idiom.

   Pure-Clojure tests; no DB fixture required (state.clj has no Datomic
   dependency).  Uses `binding` to scope a fresh test-atom per test
   so concurrent tests don't share state."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.state :as state])
  (:import [java.time Instant]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; queue-comparator + empty-queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest queue-comparator-orders-by-instant-primary
  (testing "Earlier instant comes first in the queue"
    (let [t1 (Instant/parse "2026-05-27T15:00:00Z")
          t2 (Instant/parse "2026-05-27T15:15:00Z")
          q  (-> (state/empty-queue)
                 (conj [t2 100])
                 (conj [t1 100]))]
      (is (= [t1 100] (first q))
          "Earliest fire-time appears at queue head"))))

(deftest queue-comparator-tie-breaks-by-eid
  (testing "Same instant: smaller eid wins (deterministic ordering)"
    (let [t (Instant/parse "2026-05-27T15:00:00Z")
          q (-> (state/empty-queue)
                (conj [t 200])
                (conj [t 100])
                (conj [t 150]))]
      (is (= [t 100] (first q)) "Lowest eid is first")
      (is (= 3 (count q))       "All three preserved (not deduplicated by instant alone)"))))

(deftest queue-supports-disj
  (testing "Queue entries can be removed via disj"
    (let [t1 (Instant/parse "2026-05-27T15:00:00Z")
          t2 (Instant/parse "2026-05-27T16:00:00Z")
          q1 (-> (state/empty-queue) (conj [t1 100]) (conj [t2 200]))
          q2 (disj q1 [t1 100])]
      (is (= 2 (count q1)) "Pre-disj has both")
      (is (= 1 (count q2)) "Post-disj has only the surviving entry")
      (is (= [t2 200] (first q2)) "Surviving entry is the unremoved one"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; initial-state
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest initial-state-defaults
  (testing "Bare (initial-state) honors Q.γ.5 + Q.γ.6 + R.γ.2 ratifications"
    (let [s (state/initial-state)]
      (is (= :scheduler.state/inactive (:state s))                 "Starts inactive")
      (is (= false                     (:enabled? s))              "Q.γ.5 default: opt-in OFF")
      (is (= 4                         (:handler-pool-size s))     "Q.γ.6 default pool size")
      (is (= 5000                      (:clock-drift-threshold-ms s))
                                                                   "R.γ.2 default threshold")
      (is (= 0                         (:clock-drift-ms s))        "No drift observed at boot")
      (is (nil?                        (:fire-thread s))           "No fire-thread until :active")
      (is (nil?                        (:handler-pool s))          "No handler-pool until :active")
      (is (empty?                      (:queue s))                 "Empty queue")
      (is (= {}                        (:fires-by-eid s))          "No prior fires")
      (is (= {}                        (:runs-by-eid s))           "No in-flight runs"))))

(deftest initial-state-overrides
  (testing "Caller can override defaults"
    (let [s (state/initial-state {:enabled?                true
                                  :handler-pool-size       8
                                  :clock-drift-threshold-ms 10000})]
      (is (true? (:enabled? s)))
      (is (= 8 (:handler-pool-size s)))
      (is (= 10000 (:clock-drift-threshold-ms s))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; snapshot / swap-state! / reset-state! — dyn-var + atom semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest snapshot-returns-current-state
  (testing "snapshot is a pure read of the atom"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (let [s (state/snapshot)]
        (is (map? s))
        (is (= :scheduler.state/inactive (:state s)))))))

(deftest swap-state-mutates-via-atom
  (testing "swap-state! applies the fn atomically + returns the new state"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (let [new (state/swap-state! assoc :clock-drift-ms 42)]
        (is (= 42 (:clock-drift-ms new)))
        (is (= 42 (:clock-drift-ms (state/snapshot))))))))

(deftest reset-state-restores-fresh-initial
  (testing "reset-state! discards any swap! changes"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (state/swap-state! assoc :clock-drift-ms 999 :enabled? true)
      (is (= 999  (:clock-drift-ms (state/snapshot))))
      (state/reset-state!)
      (is (= 0     (:clock-drift-ms (state/snapshot))))
      (is (false?  (:enabled? (state/snapshot)))))))

(deftest binding-isolates-per-thread-state
  (testing "Q.γ.2 ratification: `binding` rebinds the var per-thread"
    (let [outer-atom (atom (state/initial-state {:handler-pool-size 4}))]
      (binding [state/*scheduler-state* outer-atom]
        (binding [state/*scheduler-state* (atom (state/initial-state {:handler-pool-size 99}))]
          (state/swap-state! assoc :clock-drift-ms 7)
          (is (= 7  (:clock-drift-ms (state/snapshot))))
          (is (= 99 (:handler-pool-size (state/snapshot)))))
        ;; outer scope unchanged
        (is (= 0 (:clock-drift-ms (state/snapshot))))
        (is (= 4 (:handler-pool-size (state/snapshot))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; state-machine transitions + guards
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest legal-transition-predicate
  (testing "legal-transition? enforces the adjacency map"
    (is (state/legal-transition? :scheduler.state/inactive :scheduler.state/active))
    (is (state/legal-transition? :scheduler.state/active   :scheduler.state/paused))
    (is (state/legal-transition? :scheduler.state/active   :scheduler.state/draining))
    (is (state/legal-transition? :scheduler.state/paused   :scheduler.state/active))
    (is (state/legal-transition? :scheduler.state/paused   :scheduler.state/draining))
    (is (state/legal-transition? :scheduler.state/draining :scheduler.state/inactive))
    (testing "and rejects illegal shortcuts"
      (is (not (state/legal-transition? :scheduler.state/inactive :scheduler.state/paused))
          "Can't pause from inactive — must go through active")
      (is (not (state/legal-transition? :scheduler.state/inactive :scheduler.state/draining))
          "Can't drain from inactive — nothing to drain")
      (is (not (state/legal-transition? :scheduler.state/draining :scheduler.state/active))
          "Can't resume from draining — drain-then-fresh-start required")
      (is (not (state/legal-transition? :foo/bar :scheduler.state/active))
          "Unknown source state rejected")
      (is (not (state/legal-transition? :scheduler.state/active :foo/bar))
          "Unknown target state rejected"))))

(deftest transition-state-success
  (testing "transition-state! advances + records the new state"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (let [new (state/transition-state! :scheduler.state/active :reason "test")]
        (is (= :scheduler.state/active (:state new)))
        (is (= :scheduler.state/active (:state (state/snapshot))))))))

(deftest transition-state-rejects-illegal
  (testing "transition-state! throws ex-info on illegal transition"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (state/transition-state! :scheduler.state/paused))
          "Illegal inactive → paused throws"))))

(deftest transition-state-illegal-preserves-state
  (testing "Illegal transition leaves the scheduler-state untouched"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (try
        (state/transition-state! :scheduler.state/paused)
        (catch clojure.lang.ExceptionInfo _ nil))
      (is (= :scheduler.state/inactive (:state (state/snapshot)))
          "State unchanged after illegal attempt"))))

(deftest full-lifecycle-walk
  (testing "End-to-end legal walk: inactive → active → paused → active → draining → inactive"
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (state/transition-state! :scheduler.state/active   :reason "start")
      (is (= :scheduler.state/active   (:state (state/snapshot))))

      (state/transition-state! :scheduler.state/paused   :reason "operator-pause")
      (is (= :scheduler.state/paused   (:state (state/snapshot))))

      (state/transition-state! :scheduler.state/active   :reason "operator-resume")
      (is (= :scheduler.state/active   (:state (state/snapshot))))

      (state/transition-state! :scheduler.state/draining :reason "shutdown")
      (is (= :scheduler.state/draining (:state (state/snapshot))))

      (state/transition-state! :scheduler.state/inactive :reason "drain-complete")
      (is (= :scheduler.state/inactive (:state (state/snapshot)))))))
