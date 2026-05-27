(ns sandbar.schedule-test
  "γ.2 Step 6 — public API facade tests.

   Coverage:
   - enable!/disable!/enabled? gate-flag lifecycle
   - start!/stop! composition (dispatcher + job-dispatcher both lifecycle-managed)
   - add-schedule!/remove-schedule!/list-schedules delegation
   - inspect snapshot shape
   - state diagnostic

   Per `~/.claude/plans/golden-squishing-flamingo.md` Step 6."
  (:require [clojure.test :refer :all]
            [sandbar.schedule                :as sched]
            [sandbar.schedule.state          :as state]
            [sandbar.schedule.dispatcher     :as dispatcher]
            [sandbar.schedule.job-dispatcher :as jd]
            [sandbar.event                   :as event]
            [sandbar.db.datatype             :as dt]
            [sandbar.test-util               :as tu])
  (:import [java.time Instant]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures

(use-fixtures :each (tu/make-test-db-fixture {:test-name "schedule-test"
                                              :auth?     false}))

(use-fixtures :each
  (fn [f]
    (binding [state/*scheduler-state*
              (atom (state/initial-state))]
      (event/clear!)
      (try (f)
           (finally
             (try (sched/stop! {:drain-timeout-ms 300})
                  (catch Exception _ nil))
             (event/clear!))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers

(defn- make-schedule!
  [{:keys [rrule dtstart] :or {rrule "FREQ=HOURLY"
                               dtstart (.plusSeconds (Instant/now) 3600)}}]
  (let [ent (dt/make :mm/Schedule
                     {:mm.schedule/recurrence     rrule
                      :mm.schedule/dtstart        (java.util.Date/from dtstart)
                      :mm.schedule/timezone       "UTC"
                      :mm.schedule/misfire-policy :misfire/fire-once-now}
                     {:validate? false})]
    (:db/id ent)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enable / disable gate

(deftest enable-disable-flips-flag
  (testing "enable!/disable! toggle the :enabled? slot"
    (is (not (sched/enabled?)) "Disabled by default per Q.γ.5")
    (sched/enable!)
    (is (sched/enabled?))
    (sched/disable!)
    (is (not (sched/enabled?)))))

(deftest enable-disable-idempotent
  (testing "Repeat enable!/disable! calls don't accumulate side effects"
    (sched/enable!)
    (sched/enable!)
    (is (sched/enabled?))
    (sched/disable!)
    (sched/disable!)
    (is (not (sched/enabled?)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; start! / stop! lifecycle composition

(deftest start-composes-dispatcher-and-subscriber-registration
  (testing "start! activates the dispatcher AND registers the job-dispatcher subscriber"
    (is (= :scheduler.state/inactive (sched/state)) "Inactive pre-start")
    (is (not (jd/registered?))                       "Subscriber not registered pre-start")
    (let [outcome (sched/start!)]
      (is (= :started outcome))
      (is (= :scheduler.state/active (sched/state)) "Dispatcher active post-start")
      (is (jd/registered?)                           "Subscriber registered post-start"))))

(deftest stop-composes-subscriber-unregistration-and-dispatcher
  (testing "stop! unregisters subscriber AND deactivates dispatcher"
    (sched/start!)
    (is (jd/registered?))
    (is (= :scheduler.state/active (sched/state)))
    (let [outcome (sched/stop! {:drain-timeout-ms 300})]
      (is (= :stopped outcome))
      (is (not (jd/registered?))                          "Subscriber unregistered post-stop")
      (is (= :scheduler.state/inactive (sched/state))     "Dispatcher inactive post-stop"))))

(deftest start-is-idempotent-via-dispatcher-delegation
  (testing "Calling start! twice returns :already-active on the second call"
    (sched/start!)
    (is (= :already-active (sched/start!)))
    (is (= :scheduler.state/active (sched/state)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedule management delegation

(deftest add-schedule-delegates-to-dispatcher
  (testing "add-schedule! computes next-fire-at + inserts queue entry"
    (let [eid (make-schedule! {})]
      (is (some? (sched/add-schedule! eid)) "Returns next-fire-at Instant")
      (is (= 1 (count (sched/list-schedules))) "Queue has the entry"))))

(deftest remove-schedule-delegates-to-dispatcher
  (testing "remove-schedule! drops the entry"
    (let [eid (make-schedule! {})]
      (sched/add-schedule! eid)
      (is (= 1 (count (sched/list-schedules))))
      (sched/remove-schedule! eid)
      (is (zero? (count (sched/list-schedules)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; inspect snapshot shape

(deftest inspect-returns-canonical-snapshot
  (testing "inspect returns a map carrying the documented keys"
    (let [snap (sched/inspect)]
      (is (contains? snap :state))
      (is (contains? snap :enabled?))
      (is (contains? snap :queue-size))
      (is (contains? snap :handler-pool?))
      (is (contains? snap :fire-thread?))
      (is (contains? snap :clock-drift-ms))
      (is (contains? snap :in-flight-runs))
      (is (contains? snap :subscriber-registered?))
      (is (= :scheduler.state/inactive (:state snap)))
      (is (false? (:enabled? snap)))
      (is (zero? (:queue-size snap)))
      (is (false? (:handler-pool? snap)))
      (is (false? (:fire-thread? snap)))
      (is (false? (:subscriber-registered? snap))))))

(deftest inspect-reflects-active-state-after-start
  (testing "After start! + add-schedule!, inspect surfaces the active state + queue entry"
    (let [eid (make-schedule! {})]
      (sched/enable!)
      (sched/add-schedule! eid)
      (sched/start!)
      (let [snap (sched/inspect)]
        (is (= :scheduler.state/active (:state snap)))
        (is (true?  (:enabled? snap)))
        (is (pos?   (:queue-size snap)))
        (is (true?  (:handler-pool? snap)))
        (is (true?  (:fire-thread? snap)))
        (is (true?  (:subscriber-registered? snap)))))))
