(ns sandbar.api.job
  "REST API for managing background jobs.

   ## Query Jobs
     GET /api/jobs                - List jobs with filters
     GET /api/jobs/stats          - Get job statistics
     GET /api/jobs/due            - Get due scheduled jobs
     GET /api/jobs/running        - Get currently running jobs
     GET /api/jobs/:id            - Get job by ID

   ## Create Jobs
     POST /api/jobs               - Create a scheduled job
     POST /api/jobs/triggered     - Create a triggered job
     POST /api/jobs/recurring     - Create a recurring job

   ## Manage Jobs
     POST /api/jobs/:id/cancel    - Cancel a pending job
     POST /api/jobs/:id/pause     - Pause a job
     POST /api/jobs/:id/resume    - Resume a paused job
     POST /api/jobs/:id/execute   - Execute a job immediately

   ## Filters (query params for GET /api/jobs)
     ?status=pending              - Filter by status
     ?queue=default               - Filter by queue
     ?tags=email,notification     - Filter by tags (comma-separated)
     ?limit=100                   - Max results"
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.service.endpoint :as endpoint :refer [defhandler return]]
            [sandbar.util.http-status :as http-status]
            [sandbar.util.job :as job])
  (:import [java.time Instant]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- str->long [s]
  (when s
    (try (Long/parseLong s)
         (catch Exception _ nil))))

(defn- str->keyword [s]
  (when s
    (if (keyword? s) s (keyword s))))

(defn- str->symbol [s]
  (when s
    (if (symbol? s) s (symbol s))))

(defn- str->instant [s]
  (when s
    (try (Date/from (Instant/parse s))
         (catch Exception _ nil))))

(defn- str->tags [s]
  (when s
    (->> (str/split s #",")
         (map str/trim)
         (map keyword)
         set)))

(defn- entity->map
  "Convert a Datomic entity to a plain map for serialization."
  [e]
  (when e
    (into {:db/id (:db/id e)} (d/touch e))))

(defn- job->response
  "Convert a job entity to an API response map."
  [job]
  (when job
    {:id (:db/id job)
     :name (:job/name job)
     :handler (str (:job/handler job))
     :status (:job/status job)
     :priority (:job/priority job)
     :queue (:job/queue job)
     :attempt-count (:job/attempt-count job)
     :max-attempts (:job/max-attempts job)
     :created-at (str (:job/created-at job))
     :run-at (when-let [t (:job/run-at job)] (str t))
     :next-run (when-let [t (:job/next-run job)] (str t))
     :last-run (when-let [t (:job/last-run job)] (str t))
     :run-count (:job/run-count job)
     :tags (:job/tags job)
     :error (:job/error job)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-jobs
  "GET /api/jobs - List jobs with optional filters.

   Query params:
     ?status=pending     - Filter by status (pending, running, completed, failed, cancelled, paused)
     ?queue=default      - Filter by queue name
     ?tags=email,urgent  - Filter by tags (comma-separated)
     ?limit=100          - Maximum results (default 100)"
  [request _ params]
  (let [status-filter (some-> (:status params) str->keyword)
        queue-filter (some-> (:queue params) str->keyword)
        tags-filter (some-> (:tags params) str->tags)
        limit (or (some-> (:limit params) str->long) 100)

        jobs (cond
               status-filter (job/jobs-by-status status-filter)
               queue-filter (job/jobs-by-queue queue-filter)
               tags-filter (job/jobs-by-tags tags-filter)
               :else (concat (job/jobs-by-status :pending)
                            (job/jobs-by-status :running)))

        results (->> jobs
                     (take limit)
                     (mapv job->response))]
    {:count (count results)
     :limit limit
     :filters (cond-> {}
                status-filter (assoc :status status-filter)
                queue-filter (assoc :queue queue-filter)
                tags-filter (assoc :tags tags-filter))
     :jobs results}))

(defhandler get-job
  "GET /api/jobs/:id - Get a specific job by ID."
  [request _ {:keys [id]}]
  (let [job-id (str->long id)]
    (if-not job-id
      (return http-status/bad-request {:error "Invalid job ID" :id id})
      (if-let [job (job/find-job job-id)]
        {:job (job->response job)
         :payload (job/get-job-payload job)}
        (return http-status/not-found {:error "Job not found" :id job-id})))))

(defhandler job-stats
  "GET /api/jobs/stats - Get job statistics."
  [request _ _]
  (let [stats (job/job-stats)
        exec-stats (job/execution-stats)]
    {:jobs stats
     :executions exec-stats}))

(defhandler due-jobs
  "GET /api/jobs/due - Get scheduled jobs that are due for execution."
  [request _ _]
  (let [jobs (job/due-scheduled-jobs)]
    {:count (count jobs)
     :jobs (mapv job->response jobs)}))

(defhandler running-jobs
  "GET /api/jobs/running - Get currently running jobs."
  [request _ _]
  (let [jobs (job/running-jobs)]
    {:count (count jobs)
     :jobs (mapv job->response jobs)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Create Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler create-scheduled-job
  "POST /api/jobs - Create a scheduled job.

   Request body:
     {:handler \"myapp.jobs/send-email\"
      :payload {:user-id 123}
      :run-at \"2024-01-15T10:00:00Z\"  ; or :delay-ms 60000
      :name \"Send welcome email\"
      :priority 5
      :queue :email
      :max-attempts 3
      :tags [:email :welcome]}"
  [request _ params]
  (let [{:keys [handler payload run-at delay-ms name priority queue max-attempts tags]} params
        handler-sym (str->symbol handler)
        run-time (when run-at (str->instant run-at))
        delay (when delay-ms (str->long delay-ms))
        tags-set (when tags (if (set? tags) tags (set (map str->keyword tags))))]
    (cond
      (nil? handler-sym)
      (return http-status/bad-request {:error "Handler required"})

      (and (nil? run-time) (nil? delay))
      (return http-status/bad-request {:error "Either run-at or delay-ms required"})

      :else
      (try
        (let [job (job/schedule! handler-sym payload
                    :name name
                    :run-at run-time
                    :delay-ms delay
                    :priority (or priority 0)
                    :queue (or (str->keyword queue) :default)
                    :max-attempts (or max-attempts 3)
                    :tags tags-set
                    :created-by (get-in request [:identity :db/id]))]
          (log/info :API/JOB-CREATED {:job-id (:db/id job) :name (:job/name job)})
          (return http-status/created
                  {:created true
                   :job (job->response job)}))
        (catch Exception e
          (return http-status/bad-request {:error (.getMessage e)}))))))

(defhandler create-triggered-job
  "POST /api/jobs/triggered - Create a triggered job.

   Request body:
     {:handler \"myapp.jobs/on-user-created\"
      :trigger-event :user/created
      :payload {}
      :name \"Handle new user\"}"
  [request _ params]
  (let [{:keys [handler trigger-event payload name priority queue]} params
        handler-sym (str->symbol handler)
        trigger (str->keyword trigger-event)]
    (cond
      (nil? handler-sym)
      (return http-status/bad-request {:error "Handler required"})

      (nil? trigger)
      (return http-status/bad-request {:error "Trigger event required"})

      :else
      (try
        (let [job (job/create-triggered! handler-sym trigger payload
                    :name name
                    :priority (or priority 0)
                    :queue (or (str->keyword queue) :default)
                    :created-by (get-in request [:identity :db/id]))]
          (log/info :API/TRIGGERED-JOB-CREATED {:job-id (:db/id job) :trigger trigger})
          (return http-status/created
                  {:created true
                   :job (job->response job)}))
        (catch Exception e
          (return http-status/bad-request {:error (.getMessage e)}))))))

(defhandler create-recurring-job
  "POST /api/jobs/recurring - Create a recurring job.

   Request body:
     {:handler \"myapp.jobs/daily-backup\"
      :payload {:db \"production\"}
      :cron \"0 2 * * *\"      ; or :interval-ms 3600000
      :name \"Daily backup\"
      :max-runs 100}"
  [request _ params]
  (let [{:keys [handler payload cron interval-ms name max-runs priority queue]} params
        handler-sym (str->symbol handler)
        interval (when interval-ms (str->long interval-ms))]
    (cond
      (nil? handler-sym)
      (return http-status/bad-request {:error "Handler required"})

      (and (nil? cron) (nil? interval))
      (return http-status/bad-request {:error "Either cron or interval-ms required"})

      :else
      (try
        (let [job (job/create-recurring! handler-sym payload
                    :name name
                    :cron cron
                    :interval-ms interval
                    :max-runs (when max-runs (str->long max-runs))
                    :priority (or priority 0)
                    :queue (or (str->keyword queue) :default)
                    :created-by (get-in request [:identity :db/id]))]
          (log/info :API/RECURRING-JOB-CREATED {:job-id (:db/id job) :cron cron :interval interval})
          (return http-status/created
                  {:created true
                   :job (job->response job)}))
        (catch Exception e
          (return http-status/bad-request {:error (.getMessage e)}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Management Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler cancel-job
  "POST /api/jobs/:id/cancel - Cancel a pending job."
  [request _ {:keys [id]}]
  (let [job-id (str->long id)]
    (if-not job-id
      (return http-status/bad-request {:error "Invalid job ID" :id id})
      (if-let [job (job/find-job job-id)]
        (if (= :pending (:job/status job))
          (do
            (job/cancel! job)
            (log/info :API/JOB-CANCELLED {:job-id job-id})
            {:success true :job (job->response (job/find-job job-id))})
          (return http-status/conflict
                  {:error "Can only cancel pending jobs"
                   :status (:job/status job)}))
        (return http-status/not-found {:error "Job not found" :id job-id})))))

(defhandler pause-job
  "POST /api/jobs/:id/pause - Pause a pending or running job."
  [request _ {:keys [id]}]
  (let [job-id (str->long id)]
    (if-not job-id
      (return http-status/bad-request {:error "Invalid job ID" :id id})
      (if-let [job (job/find-job job-id)]
        (if (#{:pending :running} (:job/status job))
          (do
            (job/pause! job)
            (log/info :API/JOB-PAUSED {:job-id job-id})
            {:success true :job (job->response (job/find-job job-id))})
          (return http-status/conflict
                  {:error "Can only pause pending or running jobs"
                   :status (:job/status job)}))
        (return http-status/not-found {:error "Job not found" :id job-id})))))

(defhandler resume-job
  "POST /api/jobs/:id/resume - Resume a paused job."
  [request _ {:keys [id]}]
  (let [job-id (str->long id)]
    (if-not job-id
      (return http-status/bad-request {:error "Invalid job ID" :id id})
      (if-let [job (job/find-job job-id)]
        (if (= :paused (:job/status job))
          (do
            (job/resume! job)
            (log/info :API/JOB-RESUMED {:job-id job-id})
            {:success true :job (job->response (job/find-job job-id))})
          (return http-status/conflict
                  {:error "Can only resume paused jobs"
                   :status (:job/status job)}))
        (return http-status/not-found {:error "Job not found" :id job-id})))))

(defhandler execute-job
  "POST /api/jobs/:id/execute - Execute a job immediately.

   This is primarily for testing/debugging. The job must be in pending status."
  [request _ {:keys [id]}]
  (let [job-id (str->long id)]
    (if-not job-id
      (return http-status/bad-request {:error "Invalid job ID" :id id})
      (if-let [job (job/find-job job-id)]
        (if (= :pending (:job/status job))
          (let [result (job/execute! job :worker "api-manual")]
            (log/info :API/JOB-EXECUTED {:job-id job-id :success (:success result)})
            {:executed true
             :success (:success result)
             :result (:result result)
             :error (:error result)
             :job (job->response (job/find-job job-id))})
          (return http-status/conflict
                  {:error "Can only execute pending jobs"
                   :status (:job/status job)}))
        (return http-status/not-found {:error "Job not found" :id job-id})))))
