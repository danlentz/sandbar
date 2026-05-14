(ns sandbar.api.workflow
  "REST API for managing workflows and processes.

   ## Workflow Definitions
     GET  /api/workflows              - List all workflow definitions
     GET  /api/workflows/:name        - Get a workflow definition
     POST /api/workflows              - Create a workflow definition
     GET  /api/workflows/:name/stats  - Get workflow statistics

   ## Processes
     GET  /api/processes                    - List processes (with filters)
     GET  /api/processes/:id                - Get a specific process
     POST /api/processes                    - Start a new process
     GET  /api/processes/:id/transitions    - Get available transitions
     POST /api/processes/:id/transition     - Execute a transition
     GET  /api/processes/:id/history        - Get process history

   ## Filters (query params for GET /api/processes)
     ?workflow=:workflow/order    - Filter by workflow name
     ?state=:order/pending        - Filter by current state
     ?active=true                 - Only active (non-completed) processes
     ?completed=true              - Only completed processes
     ?subject=12345               - Filter by subject entity ID
     ?limit=100                   - Max results"
  (:require [clojure.string :as str]
            [sandbar.entity-ref :as eref]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.service.endpoint :as endpoint :refer [defhandler return]]
            [sandbar.util.http-status :as http-status]
            [sandbar.util.workflow :as wf])
  (:import [java.util Date]))

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

(defn- str->bool [s]
  (when s
    (case (str/lower-case s)
      ("true" "1" "yes") true
      ("false" "0" "no") false
      nil)))

(defn- entity->map
  "Convert a Datomic entity to a plain map for serialization."
  [e]
  (when e
    (into {:db/id (:db/id e)} (d/touch e))))

(defn- state->response
  "Convert a state entity to an API response map."
  [state]
  (when state
    {:id            (:db/id state)
     :name          (:workflow/state-name state)
     :label         (:workflow/state-label state)
     :initial?      (:workflow/initial? state)
     :terminal?     (:workflow/terminal? state)
     :terminal-kind (:workflow/terminal-kind state)}))

(defn- transition->response
  "Convert a transition entity to an API response map."
  [transition]
  (when transition
    {:id (:db/id transition)
     :name (:workflow/transition-name transition)
     :from-state (get-in transition [:workflow/from-state :db/id])
     :to-state (get-in transition [:workflow/to-state :db/id])
     :guard (when-let [g (:workflow/guard transition)] (str g))
     :requires-reason? (:workflow/requires-reason? transition)}))

(defn- workflow->response
  "Convert a workflow definition to an API response map."
  [workflow]
  (when workflow
    (let [states (wf/get-workflow-states workflow)
          transitions (wf/get-workflow-transitions workflow)]
      {:id (:db/id workflow)
       :name (:workflow/definition-name workflow)
       :version (:workflow/version workflow)
       :states (mapv state->response states)
       :transitions (mapv transition->response transitions)
       :initial-state (state->response (wf/get-initial-state workflow))
       :terminal-states (mapv state->response (wf/get-terminal-states workflow))})))

(defn- process->response
  "Convert a process entity to an API response map."
  [process]
  (when process
    (let [current-state (wf/get-current-state process)
          workflow (wf/get-process-workflow process)]
      {:id (:db/id process)
       :workflow {:id (:db/id workflow)
                  :name (:workflow/definition-name workflow)}
       :subject-id (get-in process [:workflow/subject :db/id])
       :current-state (state->response current-state)
       :started-at (str (:workflow/started-at process))
       :completed-at (when-let [t (:workflow/completed-at process)] (str t))
       :completed? (wf/process-completed? process)
       :in-terminal-state? (wf/process-in-terminal-state? process)
       :data (wf/get-process-data process)})))

(defn- history->response
  "Convert a history entry to an API response map."
  [history]
  (wf/history-entry->map history))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Definition Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-workflows
  "GET /api/workflows - List all workflow definitions."
  [request _ _]
  (let [workflows (d/q '[:find [?e ...]
                         :where [?e :workflow/definition-name _]]
                       (db/db))
        results (mapv #(workflow->response (db/entity %)) workflows)]
    {:count (count results)
     :workflows results}))

(defhandler get-workflow
  "GET /api/workflows/:ns/:name - Get a specific workflow definition."
  [request _ {:keys [ns name]}]
  (let [wf-name (keyword ns name)]
    (if-let [workflow (wf/find-workflow wf-name)]
      {:workflow (workflow->response workflow)}
      (return http-status/not-found {:error "Workflow not found" :name wf-name}))))

(defhandler create-workflow
  "POST /api/workflows - Create a workflow definition.

   Request body:
     {:name :workflow/order-fulfillment
      :version 1
      :states [{:name :order/pending :label \"Pending\" :initial? true}
               {:name :order/confirmed :label \"Confirmed\"}
               {:name :order/shipped :label \"Shipped\"}
               {:name :order/delivered :label \"Delivered\" :terminal? true :terminal-kind :success}
               {:name :order/cancelled :label \"Cancelled\" :terminal? true :terminal-kind :cancel}]
      :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
                    {:name :ship :from :order/confirmed :to :order/shipped}
                    {:name :deliver :from :order/shipped :to :order/delivered}
                    {:name :cancel :from :order/pending :to :order/cancelled}
                    {:name :cancel :from :order/confirmed :to :order/cancelled}]}"
  [request _ params]
  (let [{:keys [name version states transitions]} params
        wf-name (if (keyword? name) name (str->keyword name))]
    (cond
      (nil? wf-name)
      (return http-status/bad-request {:error "Workflow name required"})

      (empty? states)
      (return http-status/bad-request {:error "At least one state required"})

      (empty? transitions)
      (return http-status/bad-request {:error "At least one transition required"})

      (wf/find-workflow wf-name)
      (return http-status/conflict {:error "Workflow already exists" :name wf-name})

      :else
      (try
        (let [;; Coerce state/transition names to keywords
              coerced-states (mapv (fn [s]
                                     (-> s
                                         (update :name str->keyword)
                                         (cond-> (:from s) (update :from str->keyword))
                                         (cond-> (:to s) (update :to str->keyword))))
                                   states)
              coerced-transitions (mapv (fn [t]
                                          (-> t
                                              (update :name str->keyword)
                                              (update :from str->keyword)
                                              (update :to str->keyword)))
                                        transitions)
              workflow (wf/define-workflow! wf-name
                         {:states coerced-states
                          :transitions coerced-transitions
                          :version (or version 1)})]
          (log/info :API/WORKFLOW-CREATED {:name wf-name :states (count states)})
          (return http-status/created
                  {:created true
                   :workflow (workflow->response workflow)}))
        (catch Exception e
          (return http-status/bad-request {:error (.getMessage e)}))))))

(defhandler workflow-stats
  "GET /api/workflows/:ns/:name/stats - Get workflow statistics."
  [request _ {:keys [ns name]}]
  (let [wf-name (keyword ns name)]
    (if-let [workflow (wf/find-workflow wf-name)]
      {:workflow wf-name
       :stats (wf/workflow-stats workflow)}
      (return http-status/not-found {:error "Workflow not found" :name wf-name}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Process Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-processes
  "GET /api/processes - List processes with optional filters.

   Query params:
     ?workflow=workflow/order  - Filter by workflow name
     ?state=order/pending      - Filter by current state
     ?active=true              - Only active (non-completed) processes
     ?completed=true           - Only completed processes
     ?subject=12345            - Filter by subject entity ID
     ?limit=100                - Maximum results"
  [request _ params]
  (let [workflow-filter (some-> (:workflow params) str->keyword)
        state-filter (some-> (:state params) str->keyword)
        active-filter (some-> (:active params) str->bool)
        completed-filter (some-> (:completed params) str->bool)
        subject-filter (some-> (:subject params) str->long)
        limit (or (some-> (:limit params) str->long) 100)

        processes (cond
                    ;; Filter by subject
                    subject-filter
                    (wf/find-process-by-subject subject-filter)

                    ;; Filter by state
                    state-filter
                    (wf/processes-in-state state-filter :workflow workflow-filter)

                    ;; Active only
                    active-filter
                    (wf/active-processes :workflow workflow-filter)

                    ;; Completed only
                    completed-filter
                    (wf/completed-processes :workflow workflow-filter)

                    ;; All processes for workflow
                    workflow-filter
                    (concat (wf/active-processes :workflow workflow-filter)
                            (wf/completed-processes :workflow workflow-filter))

                    ;; Default: all active
                    :else
                    (wf/active-processes))

        results (->> processes
                     (take limit)
                     (mapv process->response))]
    {:count (count results)
     :limit limit
     :filters (cond-> {}
                workflow-filter (assoc :workflow workflow-filter)
                state-filter (assoc :state state-filter)
                active-filter (assoc :active active-filter)
                completed-filter (assoc :completed completed-filter)
                subject-filter (assoc :subject subject-filter))
     :processes results}))

(defhandler get-process
  "GET /api/processes/:id - Get a specific process."
  [request _ {:keys [id]}]
  (let [process-id (str->long id)]
    (if-not process-id
      (return http-status/bad-request {:error "Invalid process ID" :id id})
      (if-let [process (wf/find-process process-id)]
        {:process (process->response process)
         :available-transitions (mapv (fn [t]
                                        {:name (:workflow/transition-name t)
                                         :requires-reason? (:workflow/requires-reason? t)})
                                      (wf/available-transitions process))}
        (return http-status/not-found {:error "Process not found" :id process-id})))))

(defhandler start-process
  "POST /api/processes - Start a new workflow process.

   Request body:
     {:workflow :workflow/order-fulfillment
      :subject 12345
      :data {:order-number \"ORD-001\"}}"
  [request _ params]
  (let [{:keys [workflow subject data]} params
        wf-name (if (keyword? workflow) workflow (str->keyword workflow))
        subject-id (if (number? subject) subject (str->long subject))]
    (cond
      (nil? wf-name)
      (return http-status/bad-request {:error "Workflow name required"})

      (nil? subject-id)
      (return http-status/bad-request {:error "Subject entity ID required"})

      :else
      (if-let [workflow-def (wf/find-workflow wf-name)]
        (let [{:keys [valid? entity reasons message]} (eref/validate subject-id)]
          (if valid?
            (try
              (let [process (wf/start-process! workflow-def entity :data data)]
                (log/info :API/PROCESS-STARTED {:process-id (:db/id process)
                                                 :workflow wf-name
                                                 :subject subject-id})
                (return http-status/created
                        {:created true
                         :process (process->response process)}))
              (catch Exception e
                (return http-status/bad-request {:error (.getMessage e)})))
            (return http-status/not-found
                    {:error "Subject entity not found"
                     :id subject-id
                     :reasons reasons
                     :message message})))
        (return http-status/not-found {:error "Workflow not found" :name wf-name})))))

(defhandler get-available-transitions
  "GET /api/processes/:id/transitions - Get available transitions for a process."
  [request _ {:keys [id]}]
  (let [process-id (str->long id)]
    (if-not process-id
      (return http-status/bad-request {:error "Invalid process ID" :id id})
      (if-let [process (wf/find-process process-id)]
        (let [transitions (wf/available-transitions process)]
          {:process-id process-id
           :current-state (state->response (wf/get-current-state process))
           :transitions (mapv (fn [t]
                                {:name (:workflow/transition-name t)
                                 :to-state (state->response (db/entity (get-in t [:workflow/to-state :db/id])))
                                 :requires-reason? (:workflow/requires-reason? t)
                                 :has-guard? (some? (:workflow/guard t))})
                              transitions)})
        (return http-status/not-found {:error "Process not found" :id process-id})))))

(defhandler execute-transition
  "POST /api/processes/:id/transition - Execute a transition.

   Request body:
     {:transition :confirm
      :reason \"Customer approved order\"
      :context {:approval-code \"ABC123\"}}"
  [request _ params]
  (let [{:keys [id transition reason context]} params
        process-id (str->long id)
        transition-name (if (keyword? transition) transition (str->keyword transition))
        actor (:identity request)]
    (cond
      (nil? process-id)
      (return http-status/bad-request {:error "Invalid process ID" :id id})

      (nil? transition-name)
      (return http-status/bad-request {:error "Transition name required"})

      :else
      (if-let [process (wf/find-process process-id)]
        (if (wf/process-completed? process)
          (return http-status/conflict {:error "Process already completed"})
          (if (wf/can-transition? process transition-name :context context)
            (try
              (let [updated-process (wf/transition! process transition-name
                                      :context context
                                      :actor actor
                                      :reason reason)]
                (log/info :API/TRANSITION-EXECUTED {:process-id process-id
                                                     :transition transition-name})
                {:success true
                 :process (process->response updated-process)
                 :new-state (state->response (wf/get-current-state updated-process))
                 :completed? (wf/process-completed? updated-process)})
              (catch clojure.lang.ExceptionInfo e
                (return http-status/bad-request {:error (.getMessage e)
                                                  :details (ex-data e)})))
            (return http-status/conflict
                    {:error "Transition not available"
                     :transition transition-name
                     :current-state (:workflow/state-name (wf/get-current-state process))
                     :available (mapv :workflow/transition-name
                                      (wf/available-transitions process :context context))})))
        (return http-status/not-found {:error "Process not found" :id process-id})))))

(defhandler get-process-history
  "GET /api/processes/:id/history - Get process transition history."
  [request _ {:keys [id]}]
  (let [process-id (str->long id)]
    (if-not process-id
      (return http-status/bad-request {:error "Invalid process ID" :id id})
      (if-let [process (wf/find-process process-id)]
        (let [history (wf/get-readable-history process)]
          {:process-id process-id
           :count (count history)
           :history (vec history)})
        (return http-status/not-found {:error "Process not found" :id process-id})))))
