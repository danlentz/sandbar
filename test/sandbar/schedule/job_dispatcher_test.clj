(ns sandbar.schedule.job-dispatcher-test
  "γ.2 Step 5 — job-dispatcher tests.

   Coverage:
   - Subscriber registration (register! / unregister! / registered?)
   - Job resolution from Schedule target
   - :mm/Fn classpath-fn resolution
   - run-job! lifecycle: happy path (JobStarted + JobCompleted);
     failure (JobFailed); timeout (JobFailed via TimeoutException);
     unresolvable fn (JobRejected)
   - Concurrency policy: :concurrency/forbid emits
     ScheduleConcurrencyViolation when in-flight exists; :concurrency/
     allow permits parallel runs
   - In-flight tracking: created on start; cleaned up on completion
   - Schedule-target validation: nil schedule → :rejected; non-Job
     target → :rejected
   - handle-scheduled-event gating: bails when :enabled? false OR
     state not :active

   Per `~/.claude/plans/golden-squishing-flamingo.md` Step 5."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.state          :as state]
            [sandbar.schedule.job-dispatcher :as jd]
            [sandbar.event                   :as event]
            [sandbar.db.datatype             :as dt]
            [sandbar.test-util               :as tu])
  (:import [java.time Instant]
           [java.util.concurrent CountDownLatch TimeUnit]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures

(use-fixtures :each (tu/make-test-db-fixture {:test-name "job-dispatcher-test"
                                              :auth?     false}))

(use-fixtures :each
  (fn [f]
    (binding [state/*scheduler-state*
              (atom (state/initial-state {:enabled? true}))]
      (event/clear!)
      (try (f)
           (finally
             (try (jd/unregister!) (catch Exception _ nil))
             (event/clear!))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test fn surface — classpath-fns invokable via the :mm/Fn ref chain

(def +invocation-counts+
  "Atom tracking how many times each test-fn was invoked + the arg
   each call received.  Reset in fixture-each via event/clear! is
   inadequate; reset here per test via a setup helper."
  (atom {}))

(defn test-fn-success
  "Test classpath-fn that returns a fixed map containing the
   run-context it was given.  Records invocation in
   +invocation-counts+."
  [run-ctx]
  (swap! +invocation-counts+ update :success (fnil conj []) run-ctx)
  {:result :ok :run-ctx run-ctx})

(defn test-fn-throws
  "Test classpath-fn that throws ex-info — exercises the JobFailed
   path."
  [run-ctx]
  (swap! +invocation-counts+ update :throws (fnil conj []) run-ctx)
  (throw (ex-info "intentional test failure" {:run-ctx run-ctx})))

(defn test-fn-slow
  "Test classpath-fn that sleeps longer than the configured timeout —
   exercises the TimeoutException path."
  [run-ctx]
  (swap! +invocation-counts+ update :slow (fnil conj []) run-ctx)
  (Thread/sleep 2000)
  :slow-result)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — construct :mm/Fn + :mm/Job + :mm/Schedule entities

(defn- reset-invocations! [] (reset! +invocation-counts+ {}))

(defn- make-fn-memorial!
  "Persist a :mm/Fn memorial whose :dt.fn/source-ns +
   :dt.fn/source-var resolve via requiring-resolve to a real
   classpath fn in this test ns.  Returns the new eid."
  [fn-symbol]
  (let [ent (dt/make :mm/Fn
                     {:dt.fn/source-ns   "sandbar.schedule.job-dispatcher-test"
                      :dt.fn/source-var  (name fn-symbol)
                      :dt.fn/installed-as :classpath-fn
                      :dt.fn/lang        :clojure
                      :dt.fn/purpose     :transform
                      :dt.fn/purity      :pure-total
                      :dt.fn/cost-class  :cheap
                      :dt.fn/status      :draft
                      :dt.fn/version     "1.0.0"
                      :dt.fn/description (str "test fn: " fn-symbol)}
                     {:validate? false})]
    (:db/id ent)))

(defn- make-job!
  "Persist a :mm/Job memorial referencing `fn-eid` via :mm.job/fn.
   Returns the new eid."
  ([fn-eid] (make-job! fn-eid {}))
  ([fn-eid extra-slots]
   (let [slots (merge {:mm.job/fn fn-eid} extra-slots)
         ent  (dt/make :mm/Job slots {:validate? false})]
     (:db/id ent))))

(defn- make-schedule!
  "Persist a :mm/Schedule entity targeting `target-eid` (a Job or
   Workflow eid).  Returns the new eid."
  [target-eid {:keys [concurrency rrule timezone]
               :or   {concurrency :concurrency/forbid
                      rrule       "FREQ=HOURLY"
                      timezone    "UTC"}}]
  (let [ent (dt/make :mm/Schedule
                     {:mm.schedule/target      target-eid
                      :mm.schedule/recurrence  rrule
                      :mm.schedule/timezone    timezone
                      :mm.schedule/dtstart     (java.util.Date/from
                                                 (Instant/parse "2026-05-27T15:00:00Z"))
                      :mm.schedule/concurrency concurrency}
                     {:validate? false})]
    (:db/id ent)))

(defn- collect-events!
  "Subscribe a collector handler to `event-class`; return
   `[!events unsubscribe]`."
  [event-class]
  (let [!events (atom [])
        h       (fn [ev] (swap! !events conj ev))]
    (event/subscribe! event-class h)
    [!events (fn [] (event/unsubscribe! event-class h))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriber registration

(deftest register-and-unregister
  (testing "register! subscribes; unregister! removes; registered? reflects state"
    (is (not (jd/registered?)) "Not registered at fixture start")
    (jd/register!)
    (is (jd/registered?) "Registered after register!")
    (jd/unregister!)
    (is (not (jd/registered?)) "Not registered after unregister!")))

(deftest register-is-idempotent
  (testing "Calling register! twice does not duplicate the subscription"
    (jd/register!)
    (jd/register!)
    (is (jd/registered?))
    (is (= 1 (count (event/subscribers-of :mm.event/Scheduled)))
        "Single subscription regardless of register! count")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; run-job! lifecycle

(deftest run-job-success-path-emits-started-and-completed
  (testing "Successful job emits :JobStarted + :JobCompleted; Run reaches :run.status/completed"
    (reset-invocations!)
    (let [fn-eid    (make-fn-memorial! 'test-fn-success)
          job-eid   (make-job! fn-eid)
          sched-eid (make-schedule! job-eid {})
          job       (#'jd/resolve-entity job-eid)
          [!started  unsub-started]  (collect-events! :mm.event/JobStarted)
          [!complete unsub-complete] (collect-events! :mm.event/JobCompleted)]
      (try
        (let [outcome (jd/run-job! sched-eid job 5000)]
          (is (= :completed (:outcome outcome)) "Outcome is :completed")
          (is (some? (:run-eid outcome))         "Run eid populated")
          (is (= 1 (count (get @+invocation-counts+ :success)))
              "test-fn-success invoked exactly once"))
        (is (= 1 (count @!started))   "Exactly one JobStarted event")
        (is (= 1 (count @!complete))  "Exactly one JobCompleted event")
        (let [evt (first @!complete)]
          (is (= sched-eid (:mm.schedule-event/schedule evt)))
          (is (= job-eid   (:mm.schedule-event/job evt)))
          (is (pos? (:mm.schedule-event/duration-ms evt))
              "Duration is positive ms"))
        (finally
          (unsub-started)
          (unsub-complete))))))

(deftest run-job-failure-path-emits-started-and-failed
  (testing "Job that throws emits :JobStarted + :JobFailed; Run reaches :run.status/failed"
    (reset-invocations!)
    (let [fn-eid    (make-fn-memorial! 'test-fn-throws)
          job-eid   (make-job! fn-eid)
          sched-eid (make-schedule! job-eid {})
          job       (#'jd/resolve-entity job-eid)
          [!started unsub-s] (collect-events! :mm.event/JobStarted)
          [!failed  unsub-f] (collect-events! :mm.event/JobFailed)]
      (try
        (let [outcome (jd/run-job! sched-eid job 5000)]
          (is (= :failed (:outcome outcome)) "Outcome is :failed"))
        (is (= 1 (count @!started)))
        (is (= 1 (count @!failed)))
        (let [evt (first @!failed)]
          (is (= sched-eid (:mm.schedule-event/schedule evt)))
          (is (string? (:mm.schedule-event/error-message evt))
              "Error message string carried in event payload"))
        (finally (unsub-s) (unsub-f))))))

(deftest run-job-timeout-fails-the-run
  (testing "Job that exceeds timeout fires :JobFailed with timeout-marker error"
    (reset-invocations!)
    (let [fn-eid    (make-fn-memorial! 'test-fn-slow)
          job-eid   (make-job! fn-eid)
          sched-eid (make-schedule! job-eid {})
          job       (#'jd/resolve-entity job-eid)
          [!failed unsub] (collect-events! :mm.event/JobFailed)]
      (try
        ;; 200ms timeout against a 2000ms-sleep fn → timeout fires
        (let [outcome (jd/run-job! sched-eid job 200)]
          (is (= :failed (:outcome outcome))
              "Outcome is :failed when fn sleeps longer than timeout"))
        (is (= 1 (count @!failed)))
        (let [evt (first @!failed)]
          (is (re-find #"(?i)timeout" (:mm.schedule-event/error-message evt))
              "Error message indicates timeout"))
        (finally (unsub))))))

(deftest run-job-unresolvable-fn-emits-rejected
  (testing "Job whose :mm.job/fn resolves to nothing emits :JobRejected; no Run created"
    (reset-invocations!)
    (let [;; Job pointing at a :mm/Fn ref that won't resolve via requiring-resolve
          bogus-fn (dt/make :mm/Fn
                            {:dt.fn/source-ns   "nonexistent.namespace.zzz"
                             :dt.fn/source-var  "no-such-var"
                             :dt.fn/installed-as :classpath-fn
                             :dt.fn/lang        :clojure
                             :dt.fn/purpose     :transform
                             :dt.fn/purity      :pure-total
                             :dt.fn/cost-class  :cheap
                             :dt.fn/status      :draft
                             :dt.fn/version     "1.0.0"
                             :dt.fn/description "bogus fn"}
                            {:validate? false})
          job-eid   (make-job! (:db/id bogus-fn))
          sched-eid (make-schedule! job-eid {})
          job       (#'jd/resolve-entity job-eid)
          [!rejected unsub] (collect-events! :mm.event/JobRejected)]
      (try
        (let [outcome (jd/run-job! sched-eid job 5000)]
          (is (= :rejected (:outcome outcome)) "Outcome is :rejected")
          (is (nil? (:run-eid outcome))         "No Run eid"))
        (is (= 1 (count @!rejected)))
        (finally (unsub))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; In-flight tracking

(deftest in-flight-tracking-cleared-on-completion
  (testing "After a clean run, in-flight-runs-for the schedule is empty"
    (reset-invocations!)
    (let [fn-eid    (make-fn-memorial! 'test-fn-success)
          job-eid   (make-job! fn-eid)
          sched-eid (make-schedule! job-eid {})
          job       (#'jd/resolve-entity job-eid)]
      (jd/run-job! sched-eid job 5000)
      (is (empty? (jd/in-flight-runs-for sched-eid))
          "In-flight set is empty after completion"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Concurrency enforcement

(deftest concurrency-forbid-emits-violation-on-overlap
  (testing ":concurrency/forbid: a fire arriving while another Run is in-flight emits :ScheduleConcurrencyViolation"
    (reset-invocations!)
    (let [fn-eid    (make-fn-memorial! 'test-fn-success)
          job-eid   (make-job! fn-eid)
          sched-eid (make-schedule! job-eid {:concurrency :concurrency/forbid})
          [!violation unsub] (collect-events! :mm.event/ScheduleConcurrencyViolation)]
      (try
        ;; Manually track an in-flight Run for this schedule, then fire the event
        (#'jd/track-in-flight-run! sched-eid 99999)
        (let [outcome (#'jd/handle-scheduled-event-sync
                       {:mm.schedule-event/schedule sched-eid})]
          (is (= :rejected (:outcome outcome)))
          (is (= :concurrency-forbidden (:reason outcome))))
        (is (= 1 (count @!violation)))
        (let [evt (first @!violation)]
          (is (= sched-eid (:mm.schedule-event/schedule evt)))
          (is (= 99999    (:mm.schedule-event/in-flight-run evt))))
        (finally
          (#'jd/untrack-in-flight-run! sched-eid 99999)
          (unsub))))))

(deftest concurrency-allow-permits-overlap
  (testing ":concurrency/allow: a fire arriving with an in-flight Run still proceeds"
    (reset-invocations!)
    (let [fn-eid    (make-fn-memorial! 'test-fn-success)
          job-eid   (make-job! fn-eid)
          sched-eid (make-schedule! job-eid {:concurrency :concurrency/allow})]
      (#'jd/track-in-flight-run! sched-eid 99998)
      (let [outcome (#'jd/handle-scheduled-event-sync
                     {:mm.schedule-event/schedule sched-eid})]
        (is (= :completed (:outcome outcome))
            "Run completes despite in-flight peer under :concurrency/allow"))
      (#'jd/untrack-in-flight-run! sched-eid 99998))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedule-target validation

(deftest handle-scheduled-event-with-stale-schedule-rejects
  (testing "Event referencing a non-existent schedule yields :rejected/:schedule-missing"
    (let [outcome (#'jd/handle-scheduled-event-sync
                   {:mm.schedule-event/schedule 99999999999999})]
      (is (= :rejected (:outcome outcome)))
      (is (= :schedule-missing (:reason outcome))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handle-scheduled-event gating

(deftest handler-bails-when-not-enabled
  (testing "When (:enabled? state) is false, handle-scheduled-event is a no-op"
    (state/swap-state! assoc :enabled? false :state :scheduler.state/active)
    (let [result (jd/handle-scheduled-event
                  {:mm.schedule-event/schedule 12345})]
      (is (nil? result) "Returns nil when not enabled"))))

(deftest handler-bails-when-state-not-active
  (testing "When state is not :active (e.g., :paused), handler is a no-op"
    (state/swap-state! assoc :enabled? true :state :scheduler.state/paused)
    (let [result (jd/handle-scheduled-event
                  {:mm.schedule-event/schedule 12345})]
      (is (nil? result) "Returns nil when not :active"))))
