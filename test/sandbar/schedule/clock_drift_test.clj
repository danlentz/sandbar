(ns sandbar.schedule.clock-drift-test
  "The clock-drift detector re-baselines after it reports, so one wall-clock
   jump (a laptop sleep) yields ONE warning, not one per fire-loop iteration
   until the dispatcher restarts.

   Why: on 2026-09-19, 18,356 of the 36,361 SystemEvent rows in the live
   store were this warning re-firing (7,527 of them on 2026-09-17 alone).
   The detector compared wall and monotonic time against the dispatcher's
   START instants, so once the machine had slept the drift stayed above the
   threshold for the life of the fire thread.  The warning is now a log line
   with a moving baseline, not a database row.

   The detector takes explicit clocks so these tests need no sleeping and
   no real clock jump."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.dispatcher :as dispatcher]
            [sandbar.schedule.state :as state]))

(def ^:private detect! #'dispatcher/detect-clock-drift!)

(defn- clocks
  "Synthetic `{:wall-ms :mono-ns}` from two millisecond readings."
  [wall-ms mono-ms]
  {:wall-ms wall-ms :mono-ns (* mono-ms 1000000)})

(deftest drift-reports-once-per-jump-then-rebaselines
  (binding [state/*scheduler-state* (atom (state/initial-state))]
    (let [t0 (clocks 1000000 1000000)]
      (state/swap-state! assoc :clock-baseline t0)
      (testing "both clocks advance together: no drift, baseline untouched"
        (is (= 0 (detect! (clocks 1001000 1001000))))
        (is (= t0 (:clock-baseline (state/snapshot)))))
      (testing "wall time jumps 10 s past monotonic: reported once, baseline reset"
        (let [jumped (clocks 1012000 1002000)]
          (is (= 10000 (detect! jumped)))
          (is (= 10000 (:clock-drift-ms (state/snapshot))))
          (is (= jumped (:clock-baseline (state/snapshot)))
              "re-baselined at the clocks that reported the drift")))
      (testing "the next check, measured from the new baseline, sees no drift"
        (is (= 0 (detect! (clocks 1013000 1003000))))
        (is (= 0 (:clock-drift-ms (state/snapshot))))))))

(deftest drift-below-threshold-keeps-the-baseline
  (binding [state/*scheduler-state* (atom (state/initial-state))]
    (let [t0 (clocks 5000 5000)]
      (state/swap-state! assoc :clock-baseline t0)
      (is (= 4000 (detect! (clocks 10000 6000)))
          "4 s of drift is below the 5 s default threshold")
      (is (= 4000 (:clock-drift-ms (state/snapshot))))
      (is (= t0 (:clock-baseline (state/snapshot))) "not re-baselined"))))

(deftest missing-baseline-is-initialised-from-the-first-clocks
  (binding [state/*scheduler-state* (atom (state/initial-state))]
    (let [first-clocks (clocks 42000 42000)]
      (is (nil? (:clock-baseline (state/snapshot))))
      (is (= 0 (detect! first-clocks)))
      (is (= first-clocks (:clock-baseline (state/snapshot)))))))
