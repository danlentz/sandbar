(ns sandbar.job-test
  "Test suite for the Job domain model.

   Background jobs: the unsung heroes that send emails, process payments,
   and clean up after everyone else. This suite tests scheduled tasks,
   triggered jobs, recurring cron-style jobs, and execution tracking.

   If these tests fail, your nightly backup might not run. No pressure.

   This test suite validates:
   - Task hierarchy (Scheduled, Triggered, Recurring)
   - Job lifecycle and status tracking
   - Execution history and results
   - Retry and failure handling properties

   Schema defined in schema/job.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu])
  (:import [java.util Date UUID]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "job-test"
                                              :extra-schema [:job]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;
;; Three flavors of background work: scheduled (run once at time X),
;; triggered (run when event Y happens), and recurring (run on schedule Z).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-class-hierarchy-test
  (testing "Task is abstract base class"
    (is (some? (db/entity :job/Task)) "Task class should exist")
    (is (dt/abstract? :job/Task) "Task should be abstract"))

  (testing "Task subtypes exist and are concrete"
    (is (some? (db/entity :job/Scheduled)) "Scheduled class should exist")
    (is (some? (db/entity :job/Triggered)) "Triggered class should exist")
    (is (some? (db/entity :job/Recurring)) "Recurring class should exist")

    (is (not (dt/abstract? :job/Scheduled)) "Scheduled should be concrete")
    (is (not (dt/abstract? :job/Triggered)) "Triggered should be concrete")
    (is (not (dt/abstract? :job/Recurring)) "Recurring should be concrete"))

  (testing "Task inheritance"
    (is (dt/subclass-of? :job/Task :job/Scheduled) "Scheduled extends Task")
    (is (dt/subclass-of? :job/Task :job/Triggered) "Triggered extends Task")
    (is (dt/subclass-of? :job/Task :job/Recurring) "Recurring extends Task"))

  (testing "Execution is a separate tracking entity"
    (is (some? (db/entity :job/Execution)) "Execution class should exist")
    (is (not (dt/abstract? :job/Execution)) "Execution should be concrete")
    (is (not (dt/subclass-of? :job/Task :job/Execution)) "Execution is not a Task")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Inheritance Tests
;;
;; All tasks share common properties. Each type adds its own special sauce.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-property-inheritance-test
  (testing "Common task properties inherited by all types"
    (let [common-slots #{:job/name :job/handler :job/payload :job/status
                         :job/priority :job/queue :job/max-attempts
                         :job/attempt-count :job/timeout-ms :job/created-at
                         :job/created-by :job/tags :job/executions}]
      (doseq [task-type [:job/Scheduled :job/Triggered :job/Recurring]]
        (let [slots (dt/slots-of task-type)]
          (doseq [slot common-slots]
            (is (contains? slots slot)
                (str slot " should be inherited by " task-type)))))))

  (testing "Scheduled-specific properties"
    (let [slots (dt/slots-of :job/Scheduled)]
      (is (contains? slots :job/run-at) "Scheduled jobs have run-at time")
      (is (contains? slots :job/delay-ms) "Or can specify delay from creation")))

  (testing "Triggered-specific properties"
    (let [slots (dt/slots-of :job/Triggered)]
      (is (contains? slots :job/trigger-event) "Triggered knows what event fires it")
      (is (contains? slots :job/trigger-source) "Triggered knows the source entity")
      (is (contains? slots :job/trigger-condition) "Triggered can have conditions")))

  (testing "Recurring-specific properties"
    (let [slots (dt/slots-of :job/Recurring)]
      (is (contains? slots :job/cron) "Recurring uses cron expressions")
      (is (contains? slots :job/timezone) "Cron needs timezone context")
      (is (contains? slots :job/next-run) "Tracks computed next run time")
      (is (contains? slots :job/last-run) "Tracks when it last ran")
      (is (contains? slots :job/run-count) "Counts total executions")
      (is (contains? slots :job/skip-if-running?) "Can skip overlapping runs")))

  (testing "Execution properties"
    (let [slots (dt/slots-of :job/Execution)]
      (is (contains? slots :job/execution-id) "Unique execution ID")
      (is (contains? slots :job/started-at) "When it started")
      (is (contains? slots :job/finished-at) "When it finished")
      (is (contains? slots :job/duration-ms) "How long it took")
      (is (contains? slots :job/execution-status) "Success or failure")
      (is (contains? slots :job/result) "Return value")
      (is (contains? slots :job/error) "Error message if failed")
      (is (contains? slots :job/stacktrace) "Stack trace if failed")
      (is (contains? slots :job/worker) "Which worker processed it"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scheduled Job Tests
;;
;; "Run this thing at this time." Simple, straightforward, reliable.
;; Like an alarm clock, but for code.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-scheduled-job-test
  (testing "Cannot instantiate abstract Task"
    (is (thrown? Exception
          (dt/make :job/Task {:job/name "Generic Task"}))))

  (testing "Create a scheduled job with run-at"
    (let [future-time (Date. (+ (System/currentTimeMillis) 3600000))  ; 1 hour from now
          job (dt/make :job/Scheduled
                {:job/name "Send welcome email"
                 :job/handler 'myapp.jobs/send-email
                 :job/payload "{:user-id 123 :template :welcome}"
                 :job/run-at future-time
                 :job/status :pending
                 :job/priority 0
                 :job/queue :default
                 :job/max-attempts 3
                 :job/attempt-count 0
                 :job/created-at (Date.)})]
      (is (some? job) "Job created")
      (is (dt/instance-of? :job/Scheduled job) "Is a Scheduled job")
      (is (dt/instance-of? :job/Task job) "Is also a Task")
      (is (= :pending (:job/status job)))
      (is (= 'myapp.jobs/send-email (:job/handler job)))))

  (testing "Create a scheduled job with delay"
    (let [job (dt/make :job/Scheduled
                {:job/name "Process payment"
                 :job/handler 'myapp.jobs/process-payment
                 :job/payload "{:order-id 456}"
                 :job/delay-ms 30000  ; 30 seconds from now
                 :job/status :pending
                 :job/queue :critical})]
      (is (= 30000 (:job/delay-ms job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Triggered Job Tests
;;
;; Event-driven jobs. "When X happens, do Y."
;; The reactive programming of background work.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-triggered-job-test
  (testing "Create an event-triggered job"
    (let [job (dt/make :job/Triggered
                {:job/name "Send order confirmation"
                 :job/handler 'myapp.jobs/send-order-confirmation
                 :job/trigger-event :order/created
                 :job/status :pending
                 :job/queue :default})]
      (is (some? job) "Triggered job created")
      (is (dt/instance-of? :job/Triggered job))
      (is (= :order/created (:job/trigger-event job)))))

  (testing "Create a triggered job with condition"
    ;; Only send premium notifications to premium customers
    (let [job (dt/make :job/Triggered
                {:job/name "Premium notification"
                 :job/handler 'myapp.jobs/notify-premium
                 :job/trigger-event :user/upgraded
                 :job/trigger-condition 'myapp.conditions/is-premium?
                 :job/status :pending})]
      (is (= 'myapp.conditions/is-premium? (:job/trigger-condition job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recurring Job Tests
;;
;; Cron jobs for the modern age. "Run every day at 2 AM."
;; Someone has to clean up the database.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-recurring-job-test
  (testing "Create a daily recurring job"
    (let [job (dt/make :job/Recurring
                {:job/name "Daily backup"
                 :job/handler 'myapp.jobs/backup-database
                 :job/cron "0 2 * * *"  ; 2 AM daily
                 :job/timezone "America/New_York"
                 :job/status :pending
                 :job/queue :maintenance
                 :job/run-count 0
                 :job/skip-if-running? true})]
      (is (some? job) "Recurring job created")
      (is (dt/instance-of? :job/Recurring job))
      (is (= "0 2 * * *" (:job/cron job)))
      (is (true? (:job/skip-if-running? job)) "Won't overlap with itself")))

  (testing "Create a recurring job with max runs"
    ;; A job that runs 10 times and stops
    (let [job (dt/make :job/Recurring
                {:job/name "Limited promo campaign"
                 :job/handler 'myapp.jobs/send-promo
                 :job/cron "0 9 * * MON"  ; Every Monday at 9 AM
                 :job/status :pending
                 :job/run-count 0
                 :job/max-runs 10})]
      (is (= 10 (:job/max-runs job)) "Will stop after 10 runs"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Execution Tracking Tests
;;
;; Every run of a job creates an Execution record. Success or failure,
;; we track it all. Useful for debugging and metrics.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest execution-tracking-test
  (testing "Create a successful execution"
    (let [exec (dt/make :job/Execution
                 {:job/execution-id (UUID/randomUUID)
                  :job/started-at (Date. (- (System/currentTimeMillis) 5000))
                  :job/finished-at (Date.)
                  :job/duration-ms 5000
                  :job/execution-status :success
                  :job/result "{:emails-sent 42}"
                  :job/attempt 1
                  :job/worker "worker-1"})]
      (is (some? exec) "Execution created")
      (is (dt/instance-of? :job/Execution exec))
      (is (= :success (:job/execution-status exec)))
      (is (= 5000 (:job/duration-ms exec)))))

  (testing "Create a failed execution"
    ;; The dark side of job processing
    (let [exec (dt/make :job/Execution
                 {:job/execution-id (UUID/randomUUID)
                  :job/started-at (Date.)
                  :job/finished-at (Date.)
                  :job/duration-ms 150
                  :job/execution-status :failure
                  :job/error "Connection refused"
                  :job/stacktrace "java.net.ConnectException: Connection refused\n  at ..."
                  :job/attempt 1
                  :job/worker "worker-2"})]
      (is (= :failure (:job/execution-status exec)))
      (is (some? (:job/error exec)))
      (is (some? (:job/stacktrace exec)))))

  (testing "Create a timeout execution"
    (let [exec (dt/make :job/Execution
                 {:job/execution-id (UUID/randomUUID)
                  :job/started-at (Date.)
                  :job/finished-at (Date.)
                  :job/duration-ms 30000
                  :job/execution-status :timeout
                  :job/error "Execution exceeded timeout of 30000ms"
                  :job/attempt 2
                  :job/worker "worker-1"})]
      (is (= :timeout (:job/execution-status exec))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job Lifecycle Tests
;;
;; Jobs go through states: pending -> running -> completed/failed.
;; Failed jobs may retry. This tests the status tracking.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-lifecycle-test
  (testing "Job starts as pending"
    (let [job (dt/make :job/Scheduled
                {:job/name "Test job"
                 :job/handler 'test/handler
                 :job/status :pending
                 :job/run-at (Date.)
                 :job/attempt-count 0
                 :job/max-attempts 3})]
      (is (= :pending (:job/status job)))
      (is (= 0 (:job/attempt-count job)))))

  (testing "Jobs can have various statuses"
    ;; Testing that we can set different statuses
    (doseq [status [:pending :running :completed :failed :cancelled :paused]]
      (let [job (dt/make :job/Scheduled
                  {:job/name (str "Job with status " status)
                   :job/handler 'test/handler
                   :job/status status
                   :job/run-at (Date.)})]
        (is (= status (:job/status job))
            (str "Can create job with status " status))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Priority and Queue Tests
;;
;; Not all jobs are created equal. Some are urgent. Some can wait.
;; Queues let you route jobs to appropriate workers.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest priority-queue-test
  (testing "Create jobs with different priorities"
    (let [low (dt/make :job/Scheduled
                {:job/name "Low priority"
                 :job/handler 'test/handler
                 :job/priority -10
                 :job/run-at (Date.)})
          normal (dt/make :job/Scheduled
                   {:job/name "Normal priority"
                    :job/handler 'test/handler
                    :job/priority 0
                    :job/run-at (Date.)})
          high (dt/make :job/Scheduled
                 {:job/name "High priority"
                  :job/handler 'test/handler
                  :job/priority 100
                  :job/run-at (Date.)})]
      (is (< (:job/priority low) (:job/priority normal)))
      (is (< (:job/priority normal) (:job/priority high)))))

  (testing "Create jobs on different queues"
    (let [default-job (dt/make :job/Scheduled
                        {:job/name "Default queue job"
                         :job/handler 'test/handler
                         :job/queue :default
                         :job/run-at (Date.)})
          critical-job (dt/make :job/Scheduled
                         {:job/name "Critical queue job"
                          :job/handler 'test/handler
                          :job/queue :critical
                          :job/run-at (Date.)})
          bulk-job (dt/make :job/Scheduled
                     {:job/name "Bulk queue job"
                      :job/handler 'test/handler
                      :job/queue :bulk
                      :job/run-at (Date.)})]
      (is (= :default (:job/queue default-job)))
      (is (= :critical (:job/queue critical-job)))
      (is (= :bulk (:job/queue bulk-job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest query-job-entities-test
  (let [_ (dt/make :job/Scheduled {:job/name "Job 1" :job/handler 'h1 :job/run-at (Date.) :job/status :pending})
        _ (dt/make :job/Scheduled {:job/name "Job 2" :job/handler 'h2 :job/run-at (Date.) :job/status :completed})
        _ (dt/make :job/Recurring {:job/name "Job 3" :job/handler 'h3 :job/cron "* * * * *" :job/status :pending})]

    (testing "Find all tasks"
      (let [all-tasks (dt/all-instances-of :job/Task)]
        (is (= 3 (count all-tasks)) "Should find all 3 tasks")))

    (testing "Find by type"
      (let [scheduled (dt/all-instances-of :job/Scheduled)
            recurring (dt/all-instances-of :job/Recurring)]
        (is (= 2 (count scheduled)) "2 scheduled jobs")
        (is (= 1 (count recurring)) "1 recurring job")))

    (testing "Query by status"
      (let [pending (d/q '[:find ?e
                           :where [?e :job/status :pending]]
                         (db/db))]
        (is (= 2 (count pending)) "2 pending jobs")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REST API Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-api-test
  (testing "Task class visible via API"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/job/Task")]
      (is (= 200 status))
      (is (= :job/Task (:class body)))
      (is (true? (:abstract? body)))))

  (testing "Task subclasses visible"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/job/Task/subclasses")]
      (is (= 200 status))
      (is (contains? (set (:subclasses body)) :job/Scheduled))
      (is (contains? (set (:subclasses body)) :job/Triggered))
      (is (contains? (set (:subclasses body)) :job/Recurring))))

  (testing "Recurring job has cron property"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/job/cron")]
      (is (= 200 status))
      (is (= :job/Recurring (:domain body))))))
