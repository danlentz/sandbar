(ns sandbar.service.validation
  "Validation service using the resource-validation workflow.

   Provides functions to run batch validation of all instances of a class
   using a workflow to track progress and history.

   ## Usage

     (require '[sandbar.service.validation :as val])

     ;; Start a validation run for all resources
     (def process (val/start-validation! :dt/Resource))

     ;; Or for a specific class
     (def process (val/start-validation! :model/User))

     ;; Run the validation (synchronous)
     (val/run-validation! process)

     ;; Get results
     (val/get-validation-results process)
     ;; => {:class :dt/Resource :total 150 :valid 148 :invalid 2 :errors [...]}

   ## Workflow States

     :validation/pending      - Validation requested
     :validation/in-progress  - Validation running
     :validation/passed       - All instances valid
     :validation/failed       - Some instances invalid
     :validation/error        - Validation error occurred
     :validation/cancelled    - Validation was cancelled"
  (:require [clojure.tools.logging :as log]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.workflow :as wf])
  (:import [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def workflow-name :workflow/resource-validation)
(def workflow-resource "workflows/resource-validation.edn")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Setup
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ensure-validation-workflow!
  "Ensure the validation workflow is defined in the database.

   Loads from resources/workflows/resource-validation.edn if not already defined."
  []
  (wf/ensure-workflow! workflow-name workflow-resource))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation Process
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-validation!
  "Start a new validation process for a class.

   Arguments:
     class-kw - The class to validate (e.g., :dt/Resource for all entities)

   Options:
     :requested-by - User entity who requested validation

   Returns the validation process entity."
  [class-kw & {:keys [requested-by]}]
  (let [workflow (ensure-validation-workflow!)
        ;; Use the class entity as the subject
        subject (db/entity class-kw)
        process-data {:class class-kw
                      :requested-by (when requested-by (:db/id requested-by))
                      :requested-at (Date.)}]
    (when-not subject
      (throw (ex-info "Class not found" {:class class-kw})))
    (log/info :VALIDATION/START {:class class-kw})
    (wf/start-process! workflow subject :data process-data)))

(defn run-validation!
  "Run the validation for a process.

   This transitions through the workflow states:
   1. pending -> in-progress (start)
   2. in-progress -> passed/failed/error (based on results)

   Returns the updated process with results in process data."
  [process & {:keys [actor]}]
  (let [current-state (wf/get-current-state process)
        state-name (:workflow/state-name current-state)
        process-data (wf/get-process-data process)]
    (case state-name
      :validation/pending
      (do
        ;; Transition to in-progress
        (let [updated (wf/transition! process :start :actor actor)]
          ;; Immediately run the validation
          (run-validation! updated :actor actor)))

      :validation/in-progress
      (let [class-kw (:class process-data)
            started-at (Date.)]
        (try
          ;; Run the actual validation
          (log/info :VALIDATION/RUNNING {:class class-kw :process-id (:db/id process)})
          (let [results (dt/validate-all-instances class-kw)
                completed-at (Date.)
                new-data (merge process-data
                                {:total (:total results)
                                 :valid (:valid results)
                                 :invalid (:invalid results)
                                 :errors (:errors results)
                                 :started-at started-at
                                 :completed-at completed-at})
                ;; Update process data via the workflow primitive
                ;; (no more raw datomic.api/transact at the service
                ;; layer; codex SHOULD-FIX #1).
                updated-process (wf/update-process-data! process new-data)
                ;; Determine outcome
                transition-name (if (zero? (:invalid results)) :pass :fail)]
            (log/info :VALIDATION/COMPLETE {:class class-kw
                                             :total (:total results)
                                             :valid (:valid results)
                                             :invalid (:invalid results)})
            (wf/transition! updated-process transition-name :actor actor))
          (catch Exception e
            (log/error e :VALIDATION/ERROR {:class class-kw :process-id (:db/id process)})
            (let [error-data     (merge process-data
                                        {:error (.getMessage e)
                                         :error-at (Date.)})
                  updated-process (wf/update-process-data! process error-data)]
              (wf/transition! updated-process :error
                              :actor actor
                              :reason (.getMessage e))))))

      ;; Already in terminal state
      (do
        (log/debug :VALIDATION/ALREADY-COMPLETE {:state state-name})
        process))))

(defn cancel-validation!
  "Cancel a pending or in-progress validation.

   Arguments:
     process - The validation process

   Options:
     :actor  - User cancelling the validation
     :reason - Reason for cancellation (required if in-progress)"
  [process & {:keys [actor reason]}]
  (let [current-state (wf/get-current-state process)
        state-name (:workflow/state-name current-state)]
    (case state-name
      :validation/pending
      (wf/transition! process :cancel :actor actor)

      :validation/in-progress
      (wf/transition! process :cancel :actor actor :reason (or reason "Cancelled by user"))

      ;; Already complete
      (throw (ex-info "Cannot cancel completed validation"
                      {:state state-name})))))

(defn retry-validation!
  "Retry a failed or errored validation.

   Arguments:
     process - The validation process

   Options:
     :actor - User retrying the validation"
  [process & {:keys [actor]}]
  (let [current-state (wf/get-current-state process)
        state-name (:workflow/state-name current-state)]
    (case state-name
      (:validation/failed :validation/error)
      (let [updated (wf/transition! process :retry :actor actor)]
        ;; Immediately start the retry
        (run-validation! updated :actor actor))

      ;; Can't retry from other states
      (throw (ex-info "Cannot retry from current state"
                      {:state state-name})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Results
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-validation-results
  "Get the validation results from a process.

   Returns a map with:
     :class      - The class that was validated
     :total      - Total instances validated
     :valid      - Number of valid instances
     :invalid    - Number of invalid instances
     :errors     - List of validation errors
     :state      - Current workflow state
     :completed? - Whether validation is complete"
  [process]
  (let [current-state (wf/get-current-state process)
        process-data (wf/get-process-data process)]
    (merge process-data
           {:state (:workflow/state-name current-state)
            :completed? (wf/process-completed? process)})))

(defn get-validation-history
  "Get the history of a validation process."
  [process]
  (wf/get-readable-history process))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Convenience Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn validate-class!
  "Convenience function to validate a class in one call.

   Arguments:
     class-kw - The class to validate

   Options:
     :actor - User performing the validation

   Returns the validation results map."
  [class-kw & {:keys [actor]}]
  (let [process (start-validation! class-kw :requested-by actor)
        completed (run-validation! process :actor actor)]
    (get-validation-results completed)))

(defn validate-all!
  "Validate all resources (entire database).

   Options:
     :actor - User performing the validation

   Returns the validation results map."
  [& {:keys [actor]}]
  (validate-class! :dt/Resource :actor actor))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn recent-validations
  "Get recent validation processes.

   Options:
     :class  - Filter by validated class
     :status - Filter by current state (:passed, :failed, :error, etc.)
     :limit  - Maximum number of results (default 10)"
  [& {:keys [class status limit] :or {limit 10}}]
  (let [workflow (wf/find-workflow workflow-name)
        processes (if status
                    (wf/processes-in-state (keyword "validation" (name status))
                                           :workflow workflow)
                    (concat (wf/active-processes :workflow workflow)
                            (wf/completed-processes :workflow workflow)))]
    (->> processes
         (filter (fn [p]
                   (if class
                     (= class (:class (wf/get-process-data p)))
                     true)))
         (sort-by :workflow/started-at #(compare %2 %1))
         (take limit))))
