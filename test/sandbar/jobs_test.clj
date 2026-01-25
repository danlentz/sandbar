(ns sandbar.jobs-test
  "Comprehensive tests for the job scheduling and execution system.

   Tests cover:
   - Job creation (scheduled, triggered, recurring)
   - Job status lifecycle
   - Job execution with handlers
   - Worker queue operations
   - Retry and failure handling
   - Statistics"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]
            [sandbar.util.job :as job])
  (:import [java.util Date]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "jobs-test"
                                               :extra-schema [:job]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; These are simple handlers for testing job execution

(defn successful-handler
  "Handler that always succeeds"
  [payload]
  {:result "success" :input payload})

(defn failing-handler
  "Handler that always throws"
  [payload]
  (throw (ex-info "Intentional failure" {:payload payload})))

(defn slow-handler
  "Handler that takes time to complete"
  [payload]
  (Thread/sleep (:delay-ms payload 100))
  {:result "completed" :delay (:delay-ms payload)})

(defn counting-handler
  "Handler that tracks invocations via an atom"
  [payload]
  (when-let [counter (:counter payload)]
    (swap! counter inc))
  {:count @(:counter payload)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 1: Scheduled Job Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest scheduled-job-creation-test
  (testing "Create a scheduled job with run-at"
    (let [run-time (Date. (+ (System/currentTimeMillis) 60000))
          job (job/schedule! 'sandbar.jobs-test/successful-handler
                             {:user-id 123}
                             :run-at run-time
                             :name "Test Job"
                             :priority 5
                             :queue :high-priority)]
      (is (some? job) "Job should be created")
      (is (= "Test Job" (:job/name job)))
      (is (= 'sandbar.jobs-test/successful-handler (:job/handler job)))
      (is (= :pending (:job/status job)))
      (is (= 5 (:job/priority job)))
      (is (= :high-priority (:job/queue job)))
      (is (= 0 (:job/attempt-count job)))
      (is (some? (:job/payload job)) "Payload should be serialized")
      (is (= run-time (:job/run-at job)))))

  (testing "Create a scheduled job with delay-ms"
    (let [before (System/currentTimeMillis)
          job (job/schedule! 'sandbar.jobs-test/successful-handler
                             {:data "test"}
                             :delay-ms 5000)
          after (System/currentTimeMillis)
          run-at (.getTime (:job/run-at job))]
      (is (>= run-at (+ before 5000)))
      (is (<= run-at (+ after 5000)))))

  (testing "Scheduled job requires run-at or delay-ms"
    (is (thrown? Exception
                 (job/schedule! 'sandbar.jobs-test/successful-handler
                                {:data "test"})))))

(deftest scheduled-job-with-tags-test
  (testing "Create job with tags"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler
                             {}
                             :delay-ms 1000
                             :tags #{:email :notifications :urgent})]
      (is (= #{:email :notifications :urgent} (:job/tags job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 2: Triggered Job Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest triggered-job-creation-test
  (testing "Create a triggered job"
    (let [job (job/create-triggered! 'sandbar.jobs-test/successful-handler
                                      :user/created
                                      {:template :welcome}
                                      :name "Welcome Email")]
      (is (some? job))
      (is (= :user/created (:job/trigger-event job)))
      (is (= "Welcome Email" (:job/name job)))
      (is (= :pending (:job/status job)))))

  (testing "Create triggered job with condition"
    (let [job (job/create-triggered! 'sandbar.jobs-test/successful-handler
                                      :order/placed
                                      {:action :notify-warehouse}
                                      :trigger-condition 'myapp/high-value-order?)]
      (is (= 'myapp/high-value-order? (:job/trigger-condition job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 3: Recurring Job Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest recurring-job-creation-test
  (testing "Create a recurring job with cron"
    (let [job (job/create-recurring! 'sandbar.jobs-test/successful-handler
                                      {:db-name "production"}
                                      :name "Daily Backup"
                                      :cron "0 2 * * *"
                                      :timezone "America/New_York")]
      (is (some? job))
      (is (= "Daily Backup" (:job/name job)))
      (is (= "0 2 * * *" (:job/cron job)))
      (is (= "America/New_York" (:job/timezone job)))
      (is (= 0 (:job/run-count job)))
      (is (some? (:job/next-run job)))))

  (testing "Create recurring job with max-runs"
    (let [job (job/create-recurring! 'sandbar.jobs-test/successful-handler
                                      {}
                                      :cron "*/5 * * * *"
                                      :max-runs 10)]
      (is (= 10 (:job/max-runs job)))))

  (testing "Create recurring job with skip-if-running"
    (let [job (job/create-recurring! 'sandbar.jobs-test/successful-handler
                                      {}
                                      :cron "* * * * *"
                                      :skip-if-running? true)]
      (is (true? (:job/skip-if-running? job)))))

  (testing "Recurring job requires cron or interval-ms"
    (is (thrown? Exception
                 (job/create-recurring! 'sandbar.jobs-test/successful-handler
                                         {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 4: Job Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-queries-test
  (testing "Find jobs by status"
    ;; Create some jobs
    (job/schedule! 'sandbar.jobs-test/successful-handler {} :delay-ms 1000)
    (job/schedule! 'sandbar.jobs-test/successful-handler {} :delay-ms 1000)
    (let [job3 (job/schedule! 'sandbar.jobs-test/successful-handler {} :delay-ms 1000)]
      ;; Cancel one
      (job/cancel! job3)

      (let [pending (job/jobs-by-status :pending)
            cancelled (job/jobs-by-status :cancelled)]
        (is (= 2 (count pending)))
        (is (= 1 (count cancelled))))))

  (testing "Find jobs by queue with priority ordering"
    (let [low (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000 :queue :test-queue :priority 1)
          high (job/schedule! 'sandbar.jobs-test/successful-handler {}
                              :delay-ms 1000 :queue :test-queue :priority 10)
          med (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000 :queue :test-queue :priority 5)
          jobs (job/jobs-by-queue :test-queue)]
      (is (= 3 (count jobs)))
      ;; Should be ordered by priority descending
      (is (= (:db/id high) (:db/id (first jobs))))
      (is (= (:db/id med) (:db/id (second jobs))))
      (is (= (:db/id low) (:db/id (nth jobs 2))))))

  (testing "Find jobs by tags"
    (job/schedule! 'sandbar.jobs-test/successful-handler {}
                   :delay-ms 1000 :tags #{:email :urgent})
    (job/schedule! 'sandbar.jobs-test/successful-handler {}
                   :delay-ms 1000 :tags #{:sms :urgent})
    (job/schedule! 'sandbar.jobs-test/successful-handler {}
                   :delay-ms 1000 :tags #{:email})

    (let [urgent-jobs (job/jobs-by-tags #{:urgent})
          email-jobs (job/jobs-by-tags #{:email})]
      (is (= 2 (count urgent-jobs)))
      (is (= 2 (count email-jobs))))))

(deftest due-jobs-test
  (testing "Find due scheduled jobs"
    ;; Create a job that's already due
    (let [past (Date. (- (System/currentTimeMillis) 1000))
          future (Date. (+ (System/currentTimeMillis) 60000))]
      (job/schedule! 'sandbar.jobs-test/successful-handler {}
                     :run-at past :name "Due Job")
      (job/schedule! 'sandbar.jobs-test/successful-handler {}
                     :run-at future :name "Future Job")

      (let [due (job/due-scheduled-jobs)]
        (is (= 1 (count due)))
        (is (= "Due Job" (:job/name (first due))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 5: Job Status Lifecycle
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-status-lifecycle-test
  (testing "Start job transitions to running"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000)
          started (job/start! job)]
      (is (= :running (:job/status started)))
      (is (= 1 (:job/attempt-count started)))))

  (testing "Complete job transitions to completed"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000)
          _ (job/start! job)
          completed (job/complete! job :result {:foo "bar"})]
      (is (= :completed (:job/status completed)))))

  (testing "Fail job with retries remaining stays pending"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000 :max-attempts 3)
          _ (job/start! job)
          failed (job/fail! job :error "Something went wrong")]
      (is (= :pending (:job/status failed)))
      (is (= "Something went wrong" (:job/error failed)))))

  (testing "Fail job without retries transitions to failed"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000 :max-attempts 1)
          _ (job/start! job)
          failed (job/fail! job :error "Final failure")]
      (is (= :failed (:job/status failed)))))

  (testing "Cancel pending job"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000)
          cancelled (job/cancel! job)]
      (is (= :cancelled (:job/status cancelled)))))

  (testing "Cannot cancel running job"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000)
          _ (job/start! job)]
      (is (nil? (job/cancel! job)))))

  (testing "Pause and resume job"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000)
          paused (job/pause! job)]
      (is (= :paused (:job/status paused)))

      (let [resumed (job/resume! paused)]
        (is (= :pending (:job/status resumed)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 6: Job Execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-execution-test
  (testing "Execute successful job"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler
                             {:user-id 456}
                             :delay-ms 0)
          result (job/execute! job)]
      (is (:success result))
      (is (= {:result "success" :input {:user-id 456}} (:result result)))

      ;; Verify job status updated
      (let [updated-job (job/find-job (:db/id job))]
        (is (= :completed (:job/status updated-job))))))

  (testing "Execute failing job"
    (let [job (job/schedule! 'sandbar.jobs-test/failing-handler
                             {:data "test"}
                             :delay-ms 0
                             :max-attempts 1)
          result (job/execute! job)]
      (is (not (:success result)))
      (is (some? (:error result)))
      (is (some? (:stacktrace result)))

      ;; Verify job status updated
      (let [updated-job (job/find-job (:db/id job))]
        (is (= :failed (:job/status updated-job))))))

  (testing "Execute job with missing handler"
    (let [job (job/schedule! 'nonexistent.ns/handler
                             {}
                             :delay-ms 0
                             :max-attempts 1)
          result (job/execute! job)]
      (is (not (:success result)))
      (is (re-find #"Handler not found" (:error result)))))

  (testing "Job execution creates execution record"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler
                             {:x 1}
                             :delay-ms 0
                             :name "Execution Test")
          _ (job/execute! job :worker "worker-1")
          updated-job (job/find-job (:db/id job))
          executions (:job/executions updated-job)]
      (is (= 1 (count executions)))
      (let [exec (db/entity (first executions))]
        (is (some? (:job/execution-id exec)))
        (is (some? (:job/started-at exec)))
        (is (some? (:job/finished-at exec)))
        (is (= :success (:job/execution-status exec)))
        (is (= "worker-1" (:job/worker exec)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 7: Retry Behavior
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest retry-behavior-test
  (testing "Job retries on failure until max attempts"
    (let [job (job/schedule! 'sandbar.jobs-test/failing-handler
                             {}
                             :delay-ms 0
                             :max-attempts 3)
          ;; First attempt
          result1 (job/execute! job)
          job1 (job/find-job (:db/id job))]
      (is (not (:success result1)))
      (is (= :pending (:job/status job1)))
      (is (= 1 (:job/attempt-count job1)))

      ;; Second attempt
      (let [result2 (job/execute! job1)
            job2 (job/find-job (:db/id job))]
        (is (not (:success result2)))
        (is (= :pending (:job/status job2)))
        (is (= 2 (:job/attempt-count job2)))

        ;; Third attempt (final)
        (let [result3 (job/execute! job2)
              job3 (job/find-job (:db/id job))]
          (is (not (:success result3)))
          (is (= :failed (:job/status job3)))
          (is (= 3 (:job/attempt-count job3))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 8: Worker Queue Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest worker-queue-operations-test
  (testing "Claim job from queue"
    (let [_low (job/schedule! 'sandbar.jobs-test/successful-handler {}
                              :delay-ms 0 :queue :worker-test :priority 1)
          high (job/schedule! 'sandbar.jobs-test/successful-handler {}
                              :delay-ms 0 :queue :worker-test :priority 10)
          claimed (job/claim-job! :worker-test)]
      ;; Should claim highest priority job
      (is (some? claimed))
      (is (= (:db/id high) (:db/id claimed)))
      (is (= :running (:job/status claimed)))))

  (testing "Claim from empty queue returns nil"
    (let [claimed (job/claim-job! :empty-queue)]
      (is (nil? claimed))))

  (testing "Poll queue returns pending jobs"
    (job/schedule! 'sandbar.jobs-test/successful-handler {}
                   :delay-ms 0 :queue :poll-test)
    (job/schedule! 'sandbar.jobs-test/successful-handler {}
                   :delay-ms 0 :queue :poll-test)
    (job/schedule! 'sandbar.jobs-test/successful-handler {}
                   :delay-ms 0 :queue :poll-test)

    (let [jobs (job/poll-queue :poll-test :limit 2)]
      (is (= 2 (count jobs)))
      (is (every? #(= :pending (:job/status %)) jobs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 9: Recurring Job Support
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest recurring-job-support-test
  (testing "Schedule next run after completion"
    (let [job (job/create-recurring! 'sandbar.jobs-test/successful-handler
                                      {:iteration 1}
                                      :cron "* * * * *")
          _ (job/execute! job)
          updated (job/schedule-next-run! (job/find-job (:db/id job))
                                          :interval-ms 60000)]
      (is (= :pending (:job/status updated)))
      (is (= 1 (:job/run-count updated)))
      (is (= 0 (:job/attempt-count updated)))
      (is (some? (:job/last-run updated)))
      (is (some? (:job/next-run updated)))
      ;; next-run should be in the future
      (is (.after (:job/next-run updated) (Date.)))))

  (testing "Recurring job pauses after max runs"
    (let [job (job/create-recurring! 'sandbar.jobs-test/successful-handler
                                      {}
                                      :cron "* * * * *"
                                      :max-runs 2)]
      ;; Simulate two runs
      (job/execute! job)
      (let [after-first (job/schedule-next-run! (job/find-job (:db/id job))
                                                 :interval-ms 1)]
        (is (= :pending (:job/status after-first)))
        (is (= 1 (:job/run-count after-first)))

        (Thread/sleep 10) ; Ensure next-run is in the past
        (job/execute! after-first)
        (let [after-second (job/schedule-next-run! (job/find-job (:db/id job))
                                                    :interval-ms 1)]
          (is (= :paused (:job/status after-second)))
          (is (= 2 (:job/run-count after-second))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 10: Job Payload Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-payload-handling-test
  (testing "Payload serialization and deserialization"
    (let [complex-payload {:user {:id 123 :name "Test"}
                           :items [{:sku "A1" :qty 2}
                                   {:sku "B2" :qty 1}]
                           :metadata {:priority :high
                                      :timestamp (Date.)}}
          job (job/schedule! 'sandbar.jobs-test/successful-handler
                             complex-payload
                             :delay-ms 1000)
          retrieved-payload (job/get-job-payload job)]
      (is (= (:user complex-payload) (:user retrieved-payload)))
      (is (= (:items complex-payload) (:items retrieved-payload)))
      (is (= :high (get-in retrieved-payload [:metadata :priority])))))

  (testing "Nil payload is handled"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler
                             nil
                             :delay-ms 1000)]
      (is (nil? (:job/payload job)))
      (is (nil? (job/get-job-payload job))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 11: Job Statistics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest job-statistics-test
  (testing "Job stats returns counts by status"
    ;; Create various jobs
    (let [j1 (job/schedule! 'sandbar.jobs-test/successful-handler {}
                            :delay-ms 0 :queue :stats-test)
          j2 (job/schedule! 'sandbar.jobs-test/successful-handler {}
                            :delay-ms 0 :queue :stats-test)
          j3 (job/schedule! 'sandbar.jobs-test/successful-handler {}
                            :delay-ms 0 :queue :stats-test)]
      ;; Execute one, cancel one, leave one pending
      (job/execute! j1)
      (job/cancel! j3)

      (let [stats (job/job-stats)]
        (is (map? stats))
        (is (some? (:by-status stats)))
        (is (some? (:pending-by-queue stats)))
        (is (pos? (:total stats)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 12: Concurrent Claim Test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest concurrent-claim-test
  (testing "Only one worker can claim a job"
    (let [job (job/schedule! 'sandbar.jobs-test/slow-handler
                             {:delay-ms 1000}
                             :delay-ms 0
                             :queue :concurrent-test)
          results (atom [])
          ;; Simulate multiple workers trying to claim simultaneously
          workers (doall
                    (for [i (range 5)]
                      (future
                        (Thread/sleep (rand-int 10))
                        (when-let [claimed (job/claim-job! :concurrent-test)]
                          (swap! results conj {:worker i :job (:db/id claimed)})))))]
      ;; Wait for all workers
      (doseq [w workers] @w)

      ;; Only one worker should have claimed the job
      (is (= 1 (count @results))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Chapter 13: Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edge-cases-test
  (testing "Find non-existent job returns nil"
    (is (nil? (job/find-job 999999))))

  (testing "Cannot resume non-paused job"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 1000)]
      (is (nil? (job/resume! job)))))

  (testing "Cannot pause completed job"
    (let [job (job/schedule! 'sandbar.jobs-test/successful-handler {}
                             :delay-ms 0)]
      (job/execute! job)
      (let [completed (job/find-job (:db/id job))]
        (is (nil? (job/pause! completed))))))

  (testing "Jobs with same name are separate entities"
    (let [j1 (job/schedule! 'sandbar.jobs-test/successful-handler {}
                            :delay-ms 1000 :name "Duplicate Name")
          j2 (job/schedule! 'sandbar.jobs-test/successful-handler {}
                            :delay-ms 1000 :name "Duplicate Name")]
      (is (not= (:db/id j1) (:db/id j2)))
      (let [found (job/find-job-by-name "Duplicate Name")]
        (is (= 2 (count found)))))))
