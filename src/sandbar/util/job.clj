(ns sandbar.util.job
  "Background job scheduling and execution utilities.

   Provides functions for creating, scheduling, and managing background jobs.
   Supports three job types:
   - Scheduled: One-time jobs for future execution
   - Triggered: Jobs triggered by events
   - Recurring: Jobs that run on a cron-like schedule

   ## Quick Start

     (require '[sandbar.util.job :as job])

     ;; Schedule a one-time job
     (job/schedule! 'myapp.jobs/send-email
       {:user-id 123 :template :welcome}
       :run-at (-> (Instant/now) (.plusSeconds 60)))

     ;; Create a recurring job
     (job/create-recurring! 'myapp.jobs/daily-backup
       {:db-name \"production\"}
       :cron \"0 2 * * *\"
       :name \"Daily backup\")

     ;; Poll for and execute a job
     (when-let [job (job/claim-job! :default)]
       (job/execute! job))

   ## Job Status Lifecycle

     :pending -> :running -> :completed
                    |
                    +-----> :failed
                    |
                    +-----> :cancelled

     :paused (can be resumed to :pending)"
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.event :as event])
  (:import [java.util Date UUID]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *default-max-attempts*
  "Default maximum retry attempts"
  3)

(def ^:dynamic *default-timeout-ms*
  "Default job timeout: 5 minutes"
  (* 5 60 1000))

(def ^:dynamic *default-queue*
  "Default queue name"
  :default)

(def ^:dynamic *default-priority*
  "Default job priority"
  0)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job Creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- serialize-payload [payload]
  (when payload (pr-str payload)))

(defn- deserialize-payload [payload-str]
  (when payload-str (read-string payload-str)))

(defn schedule!
  "Schedule a one-time job for future execution.

   Arguments:
     handler  - Fully-qualified symbol of handler function (fn [payload] -> result)
     payload  - Job parameters (any EDN-serializable data)

   Options:
     :name         - Human-readable name (default: handler name)
     :run-at       - Instant/Date to execute (required, or use :delay-ms)
     :delay-ms     - Milliseconds from now to execute (alternative to :run-at)
     :priority     - Higher = more urgent (default: 0)
     :queue        - Queue name keyword (default: :default)
     :max-attempts - Max retries (default: 3)
     :timeout-ms   - Max execution time (default: 5 minutes)
     :tags         - Set of keywords for filtering
     :created-by   - Entity ID of creator

   Returns the created job entity."
  [handler payload & {:keys [name run-at delay-ms priority queue max-attempts
                              timeout-ms tags created-by]
                       :or {priority *default-priority*
                            queue *default-queue*
                            max-attempts *default-max-attempts*
                            timeout-ms *default-timeout-ms*}}]
  (let [now (Date.)
        run-time (cond
                   run-at (if (instance? Date run-at) run-at (Date/from run-at))
                   delay-ms (Date. (+ (.getTime now) delay-ms))
                   :else (throw (ex-info "Either :run-at or :delay-ms required" {})))
        job-data (cond-> {:job/name (or name (str handler))
                          :job/handler handler
                          :job/status :pending
                          :job/priority priority
                          :job/queue queue
                          :job/max-attempts max-attempts
                          :job/attempt-count 0
                          :job/timeout-ms timeout-ms
                          :job/created-at now
                          :job/run-at run-time}
                   payload (assoc :job/payload (serialize-payload payload))
                   tags (assoc :job/tags (set tags))
                   created-by (assoc :job/created-by created-by))]
    (log/info :JOB/SCHEDULED {:name (or name (str handler)) :queue queue :run-at run-time})
    (dt/make :job/Scheduled job-data)))

(defn create-triggered!
  "Create a job that will be triggered by an event.

   Arguments:
     handler       - Fully-qualified symbol of handler function
     trigger-event - Event type keyword that triggers this job (e.g., :user/created)
     payload       - Job parameters

   Options:
     :name              - Human-readable name
     :trigger-source    - Entity ID that will trigger this job (optional filter)
     :trigger-condition - Symbol of predicate fn (fn [event] -> boolean)
     :priority          - Higher = more urgent (default: 0)
     :queue             - Queue name keyword
     :max-attempts      - Max retries
     :timeout-ms        - Max execution time
     :tags              - Set of keywords
     :created-by        - Entity ID of creator

   Returns the created job entity."
  [handler trigger-event payload & {:keys [name trigger-source trigger-condition
                                            priority queue max-attempts timeout-ms
                                            tags created-by]
                                     :or {priority *default-priority*
                                          queue *default-queue*
                                          max-attempts *default-max-attempts*
                                          timeout-ms *default-timeout-ms*}}]
  (let [now (Date.)
        job-data (cond-> {:job/name (or name (str handler))
                          :job/handler handler
                          :job/trigger-event trigger-event
                          :job/status :pending
                          :job/priority priority
                          :job/queue queue
                          :job/max-attempts max-attempts
                          :job/attempt-count 0
                          :job/timeout-ms timeout-ms
                          :job/created-at now}
                   payload (assoc :job/payload (serialize-payload payload))
                   trigger-source (assoc :job/trigger-source trigger-source)
                   trigger-condition (assoc :job/trigger-condition trigger-condition)
                   tags (assoc :job/tags (set tags))
                   created-by (assoc :job/created-by created-by))]
    (dt/make :job/Triggered job-data)))

(defn create-recurring!
  "Create a job that runs on a recurring schedule.

   Arguments:
     handler  - Fully-qualified symbol of handler function
     payload  - Job parameters

   Options:
     :name            - Human-readable name
     :cron            - Cron expression (e.g., \"0 2 * * *\" for 2 AM daily)
     :timezone        - Timezone for cron (default: system timezone)
     :interval-ms     - Alternative: run every N milliseconds
     :max-runs        - Stop after N executions (optional)
     :skip-if-running - Skip if previous run still active
     :priority        - Higher = more urgent
     :queue           - Queue name keyword
     :max-attempts    - Max retries per execution
     :timeout-ms      - Max execution time per run
     :tags            - Set of keywords
     :created-by      - Entity ID of creator

   Returns the created job entity."
  [handler payload & {:keys [name cron timezone interval-ms max-runs skip-if-running?
                              priority queue max-attempts timeout-ms tags created-by]
                       :or {priority *default-priority*
                            queue *default-queue*
                            max-attempts *default-max-attempts*
                            timeout-ms *default-timeout-ms*
                            skip-if-running? false}}]
  (when (and (nil? cron) (nil? interval-ms))
    (throw (ex-info "Either :cron or :interval-ms required" {})))
  (let [now (Date.)
        next-run (if interval-ms
                   (Date. (+ (.getTime now) interval-ms))
                   ;; For cron, we'd need a cron parser - simplified for now
                   (Date. (+ (.getTime now) (* 60 1000)))) ; 1 minute from now
        job-data (cond-> {:job/name (or name (str handler))
                          :job/handler handler
                          :job/status :pending
                          :job/priority priority
                          :job/queue queue
                          :job/max-attempts max-attempts
                          :job/attempt-count 0
                          :job/timeout-ms timeout-ms
                          :job/created-at now
                          :job/next-run next-run
                          :job/run-count 0
                          :job/skip-if-running? skip-if-running?}
                   payload (assoc :job/payload (serialize-payload payload))
                   cron (assoc :job/cron cron)
                   timezone (assoc :job/timezone timezone)
                   interval-ms (assoc :job/interval-ms interval-ms)
                   max-runs (assoc :job/max-runs max-runs)
                   tags (assoc :job/tags (set tags))
                   created-by (assoc :job/created-by created-by))]
    (log/info :JOB/RECURRING-CREATED {:name (or name (str handler))
                                       :queue queue
                                       :cron cron
                                       :interval-ms interval-ms})
    (dt/make :job/Recurring job-data)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-job
  "Find a job by entity ID. Returns the entity map or nil."
  [job-id]
  (when job-id
    (let [entity (db/entity job-id)]
      ;; Only return if the entity actually exists (has attributes beyond :db/id)
      (when (:job/name entity)
        entity))))

(defn find-job-by-name
  "Find jobs by name. Returns a sequence of entity maps."
  [name]
  (let [eids (d/q '[:find [?e ...]
                    :in $ ?name
                    :where [?e :job/name ?name]]
                  (db/db) name)]
    (map db/entity eids)))

(defn jobs-by-status
  "Find all jobs with the given status.

   Status values: :pending, :running, :completed, :failed, :cancelled, :paused"
  [status]
  (let [eids (d/q '[:find [?e ...]
                    :in $ ?status
                    :where [?e :job/status ?status]]
                  (db/db) status)]
    (map db/entity eids)))

(defn jobs-by-queue
  "Find all pending jobs in a specific queue, ordered by priority (descending)."
  [queue-name]
  (let [results (d/q '[:find ?e ?priority
                       :in $ ?queue
                       :where
                       [?e :job/queue ?queue]
                       [?e :job/status :pending]
                       [?e :job/priority ?priority]]
                     (db/db) queue-name)]
    (->> results
         (sort-by second >)  ; Sort by priority descending
         (map (comp db/entity first)))))

(defn jobs-by-tags
  "Find all jobs with any of the given tags."
  [tags]
  (let [tag-set (set tags)
        eids (d/q '[:find [?e ...]
                    :in $ ?tags
                    :where
                    [?e :job/tags ?tag]
                    [(contains? ?tags ?tag)]]
                  (db/db) tag-set)]
    (map db/entity eids)))

(defn pending-jobs
  "Find all pending jobs, optionally filtered by queue.

   Options:
     :queue  - Filter by queue name
     :limit  - Maximum jobs to return"
  [& {:keys [queue limit]}]
  (let [jobs (if queue
               (jobs-by-queue queue)
               (jobs-by-status :pending))]
    (if limit
      (take limit jobs)
      jobs)))

(defn due-scheduled-jobs
  "Find scheduled jobs that are due for execution (run-at <= now)."
  []
  (let [now (Date.)
        eids (d/q '[:find [?e ...]
                    :in $ ?now
                    :where
                    [?e :job/status :pending]
                    [?e :job/run-at ?run-at]
                    [(<= ?run-at ?now)]]
                  (db/db) now)]
    (map db/entity eids)))

(defn due-recurring-jobs
  "Find recurring jobs that are due for execution (next-run <= now)."
  []
  (let [now (Date.)
        eids (d/q '[:find [?e ...]
                    :in $ ?now
                    :where
                    [?e :job/status :pending]
                    [?e :job/next-run ?next-run]
                    [(<= ?next-run ?now)]]
                  (db/db) now)]
    (map db/entity eids)))

(defn running-jobs
  "Find all currently running jobs."
  []
  (jobs-by-status :running))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job Status Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn update-status!
  "Update job status. Returns updated job entity."
  [job new-status]
  @(d/transact (db/conn) [[:db/add (:db/id job) :job/status new-status]])
  (db/entity (:db/id job)))

(defn start!
  "Mark a job as running. Increments attempt count."
  [job]
  (let [new-attempt (inc (or (:job/attempt-count job) 0))]
    @(d/transact (db/conn)
       [[:db/add (:db/id job) :job/status :running]
        [:db/add (:db/id job) :job/attempt-count new-attempt]])
    (event/log! :info "Job started"
                {:event/kind :job/started
                 :event/target (:db/id job)
                 :event/tags #{:job}})
    (db/entity (:db/id job))))

(defn complete!
  "Mark a job as completed successfully."
  [job & {:keys [result]}]
  (let [tx-data (cond-> [[:db/add (:db/id job) :job/status :completed]]
                  result (conj [:db/add (:db/id job) :job/result (pr-str result)]))]
    @(d/transact (db/conn) tx-data)
    (event/log! :info "Job completed"
                {:event/kind :job/completed
                 :event/target (:db/id job)
                 :event/tags #{:job}})
    (db/entity (:db/id job))))

(defn fail!
  "Mark a job as failed.

   If max-attempts not reached, the job remains in :pending status for retry."
  [job & {:keys [error stacktrace retry?]}]
  ;; Reload job to get current attempt count (in case start! was called)
  (let [current-job (db/entity (:db/id job))
        attempts (or (:job/attempt-count current-job) 0)
        max-attempts (or (:job/max-attempts current-job) *default-max-attempts*)
        should-retry? (and (not (false? retry?))
                           (< attempts max-attempts))
        new-status (if should-retry? :pending :failed)
        tx-data (cond-> [[:db/add (:db/id job) :job/status new-status]]
                  error (conj [:db/add (:db/id job) :job/error error])
                  stacktrace (conj [:db/add (:db/id job) :job/stacktrace stacktrace]))]
    @(d/transact (db/conn) tx-data)
    (event/log! (if should-retry? :warn :error)
                (if should-retry? "Job failed, will retry" "Job failed permanently")
                {:event/kind :job/failed
                 :event/target (:db/id job)
                 :event/tags #{:job}})
    (db/entity (:db/id job))))

(defn cancel!
  "Cancel a pending job."
  [job]
  ;; Reload job to get current status
  (let [current-job (db/entity (:db/id job))]
    (when (= :pending (:job/status current-job))
      @(d/transact (db/conn) [[:db/add (:db/id job) :job/status :cancelled]])
      (event/log! :info "Job cancelled"
                  {:event/kind :job/cancelled
                   :event/target (:db/id job)
                   :event/tags #{:job}})
      (db/entity (:db/id job)))))

(defn pause!
  "Pause a pending or running job."
  [job]
  (when (#{:pending :running} (:job/status job))
    @(d/transact (db/conn) [[:db/add (:db/id job) :job/status :paused]])
    (event/log! :info "Job paused"
                {:event/kind :job/paused
                 :event/target (:db/id job)
                 :event/tags #{:job}})
    (db/entity (:db/id job))))

(defn resume!
  "Resume a paused job."
  [job]
  (when (= :paused (:job/status job))
    @(d/transact (db/conn) [[:db/add (:db/id job) :job/status :pending]])
    (event/log! :info "Job resumed"
                {:event/kind :job/resumed
                 :event/target (:db/id job)
                 :event/tags #{:job}})
    (db/entity (:db/id job))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job Execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-execution!
  "Create an execution record for a job run."
  [job & {:keys [worker]}]
  (let [now (Date.)
        execution-id (UUID/randomUUID)
        attempt (or (:job/attempt-count job) 1)]
    (dt/make :job/Execution
      (cond-> {:job/execution-id execution-id
               :job/started-at now
               :job/attempt attempt}
        worker (assoc :job/worker worker)))))

(defn complete-execution!
  "Mark an execution as completed successfully."
  [execution & {:keys [result]}]
  (let [now (Date.)
        started (:job/started-at execution)
        duration (when started (- (.getTime now) (.getTime started)))]
    @(d/transact (db/conn)
       (cond-> [[:db/add (:db/id execution) :job/finished-at now]
                [:db/add (:db/id execution) :job/execution-status :success]]
         duration (conj [:db/add (:db/id execution) :job/duration-ms duration])
         result (conj [:db/add (:db/id execution) :job/result (pr-str result)])))
    (db/entity (:db/id execution))))

(defn fail-execution!
  "Mark an execution as failed."
  [execution & {:keys [error stacktrace status]}]
  (let [now (Date.)
        started (:job/started-at execution)
        duration (when started (- (.getTime now) (.getTime started)))]
    @(d/transact (db/conn)
       (cond-> [[:db/add (:db/id execution) :job/finished-at now]
                [:db/add (:db/id execution) :job/execution-status (or status :failure)]]
         duration (conj [:db/add (:db/id execution) :job/duration-ms duration])
         error (conj [:db/add (:db/id execution) :job/error error])
         stacktrace (conj [:db/add (:db/id execution) :job/stacktrace stacktrace])))
    (db/entity (:db/id execution))))

(defn link-execution!
  "Link an execution to its job."
  [job execution]
  @(d/transact (db/conn)
     [[:db/add (:db/id job) :job/executions (:db/id execution)]])
  (db/entity (:db/id job)))

(defn get-job-payload
  "Get the deserialized payload for a job."
  [job]
  (deserialize-payload (:job/payload job)))

(defn execute!
  "Execute a job by resolving and calling its handler.

   Arguments:
     job - The job entity to execute

   Options:
     :worker - Worker identifier string

   Returns:
     {:success true :result ...} or {:success false :error ... :stacktrace ...}"
  [job & {:keys [worker]}]
  (let [handler-sym (:job/handler job)
        payload (get-job-payload job)
        execution (create-execution! job :worker worker)]
    (log/debug :JOB/EXECUTE-START {:job-id (:db/id job)
                                    :name (:job/name job)
                                    :handler handler-sym
                                    :worker worker})
    (try
      ;; Mark job as running
      (start! job)
      (link-execution! job execution)

      ;; Resolve and call handler
      (let [handler-fn (try
                         (requiring-resolve handler-sym)
                         (catch Exception _
                           nil))]
        (if handler-fn
          (let [result (handler-fn payload)]
            (complete-execution! execution :result result)
            (complete! job :result result)
            (log/info :JOB/EXECUTE-SUCCESS {:job-id (:db/id job) :name (:job/name job)})
            {:success true :result result})
          (let [error-msg (str "Handler not found: " handler-sym)]
            (log/error :JOB/HANDLER-NOT-FOUND {:job-id (:db/id job) :handler handler-sym})
            (fail-execution! execution :error error-msg)
            (fail! job :error error-msg :retry? false)
            {:success false :error error-msg})))

      (catch Exception e
        (let [error-msg (.getMessage e)
              stacktrace (with-out-str (.printStackTrace e))]
          (log/error e :JOB/EXECUTE-FAILED {:job-id (:db/id job)
                                             :name (:job/name job)
                                             :error error-msg})
          (fail-execution! execution :error error-msg :stacktrace stacktrace)
          (fail! job :error error-msg :stacktrace stacktrace)
          {:success false :error error-msg :stacktrace stacktrace})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker Support
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- try-claim-job
  "Attempt to claim a single job. Returns the claimed job or nil."
  [job]
  (when (and job (= :pending (:job/status job)))
    (try
      @(d/transact (db/conn)
         [[:db.fn/cas (:db/id job) :job/status :pending :running]
          [:db/add (:db/id job) :job/attempt-count
           (inc (or (:job/attempt-count job) 0))]])
      ;; Success - return updated job
      (event/log! :debug "Job claimed"
                  {:event/kind :job/claimed
                   :event/target (:db/id job)
                   :event/tags #{:job}})
      (db/entity (:db/id job))
      (catch Exception _
        ;; CAS failed - job was claimed by another worker
        nil))))

(defn claim-job!
  "Atomically claim a job for processing.

   Finds the highest-priority pending job in the queue and marks it as running.
   Uses optimistic locking to prevent race conditions.

   Arguments:
     queue - Queue name keyword (default: :default)

   Options:
     :worker - Worker identifier string

   Returns the claimed job entity, or nil if no jobs available."
  ([queue] (claim-job! queue {}))
  ([queue {:keys [worker]}]
   ;; Find candidates and try to claim first available
   (let [candidates (take 10 (jobs-by-queue queue))]
     (loop [jobs candidates]
       (if-let [job (first jobs)]
         (if-let [claimed (try-claim-job job)]
           claimed
           (recur (rest jobs)))
         nil)))))

(defn poll-queue
  "Poll a queue for due jobs and return them.

   Arguments:
     queue - Queue name keyword

   Options:
     :limit - Maximum number of jobs to return (default: 10)

   Returns a sequence of pending jobs."
  [queue & {:keys [limit] :or {limit 10}}]
  (take limit (jobs-by-queue queue)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recurring Job Support
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schedule-next-run!
  "Schedule the next run for a recurring job after completion.

   Updates next-run based on cron/interval, increments run-count,
   and resets status to :pending (unless max-runs reached)."
  [job & {:keys [interval-ms]}]
  (let [interval (or interval-ms
                     ;; Try to get from job metadata or use default
                     (* 60 1000)) ; Default 1 minute
        now (Date.)
        next-run (Date. (+ (.getTime now) interval))
        new-run-count (inc (or (:job/run-count job) 0))
        max-runs (:job/max-runs job)
        should-pause? (and max-runs (>= new-run-count max-runs))]
    @(d/transact (db/conn)
       (cond-> [[:db/add (:db/id job) :job/last-run now]
                [:db/add (:db/id job) :job/next-run next-run]
                [:db/add (:db/id job) :job/run-count new-run-count]
                [:db/add (:db/id job) :job/attempt-count 0]]
         should-pause?
         (conj [:db/add (:db/id job) :job/status :paused])
         (not should-pause?)
         (conj [:db/add (:db/id job) :job/status :pending])))
    (db/entity (:db/id job))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statistics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn job-stats
  "Get statistics about jobs.

   Returns a map with counts by status and queue."
  []
  (let [status-counts (d/q '[:find ?status (count ?e)
                             :where [?e :job/status ?status]]
                           (db/db))
        queue-counts (d/q '[:find ?queue (count ?e)
                            :where
                            [?e :job/queue ?queue]
                            [?e :job/status :pending]]
                          (db/db))]
    {:by-status (into {} status-counts)
     :pending-by-queue (into {} queue-counts)
     :total (reduce + (map second status-counts))}))

(defn execution-stats
  "Get statistics about job executions.

   Options:
     :since - Only count executions after this Date

   Returns a map with counts and timing info."
  [& {:keys [since]}]
  (let [base-query '[:find ?status (count ?e) (avg ?duration)
                     :where
                     [?e :job/execution-status ?status]
                     [?e :job/duration-ms ?duration]]
        results (if since
                  (d/q (concat base-query '[[?e :job/started-at ?t]
                                            [(>= ?t ?since)]])
                       (db/db) since)
                  (d/q base-query (db/db)))]
    {:by-status (into {}
                      (map (fn [[status count avg-dur]]
                             [status {:count count :avg-duration-ms avg-dur}])
                           results))
     :total (reduce + (map second results))}))
