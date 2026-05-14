(ns sandbar.util.workflow
  "Workflow state machine utilities.

   Provides functions for defining and executing workflows (state machines).
   A workflow consists of:
   - States: Named positions in the process
   - Transitions: Valid state changes with optional guards
   - Processes: Running instances attached to subject entities

   ## Quick Start

     (require '[sandbar.util.workflow :as wf])

     ;; Define a workflow
     (def order-workflow
       (wf/define-workflow! :workflow/order-fulfillment
         {:states [{:name :order/pending :initial? true}
                   {:name :order/confirmed}
                   {:name :order/shipped}
                   {:name :order/delivered :terminal? true :terminal-kind :success}
                   {:name :order/cancelled :terminal? true :terminal-kind :cancel}]
          :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
                        {:name :ship :from :order/confirmed :to :order/shipped}
                        {:name :deliver :from :order/shipped :to :order/delivered}
                        {:name :cancel :from :order/pending :to :order/cancelled}
                        {:name :cancel :from :order/confirmed :to :order/cancelled}]}))

     ;; Start a process for an order
     (def process (wf/start-process! order-workflow order-entity))

     ;; Transition the process
     (wf/transition! process :confirm {:actor user})
     (wf/transition! process :ship {:actor warehouse-user :reason \"Ready for pickup\"})

     ;; Check available transitions
     (wf/available-transitions process)
     ;; => [{:name :deliver} {:name :cancel}]

   ## Loading Workflows from Resources

     ;; Load a workflow definition from resources/workflows/
     (wf/load-workflow-from-resource! \"workflows/resource-validation.edn\")

   ## Guard Functions

   Transitions can have guard functions that control when they're allowed:

     {:name :ship
      :from :order/confirmed
      :to :order/shipped
      :guard 'myapp.guards/inventory-available?}

   Guard functions receive (process context) and return boolean."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.event :as event])
  (:import [java.net JarURLConnection]
           [java.util Date]
           [java.util.jar JarFile]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-terminal-kinds
  "Closed set of terminal-kind classifications per
   decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md.

   :success — process completed its intended purpose
   :failure — process encountered an outcome-blocking problem
   :cancel  — process was deliberately stopped"
  #{:success :failure :cancel})

(defn- validate-terminal-kind!
  "Validate :terminal-kind against the closed set + terminal? consistency.
   Throws ex-info on violation per F-B-002 ADR acceptance criterion A.4."
  [state-name terminal? terminal-kind]
  (cond
    (and terminal? (not terminal-kind))
    (throw (ex-info ":workflow/State terminal? true requires :terminal-kind"
                    {:reason  :terminal-without-kind
                     :state   state-name
                     :allowed valid-terminal-kinds}))

    (and (not terminal?) terminal-kind)
    (throw (ex-info ":workflow/State :terminal-kind only meaningful when terminal? true"
                    {:reason        :non-terminal-with-kind
                     :state         state-name
                     :terminal-kind terminal-kind}))

    (and terminal-kind (not (contains? valid-terminal-kinds terminal-kind)))
    (throw (ex-info (str ":workflow/State :terminal-kind must be one of " valid-terminal-kinds)
                    {:reason        :invalid-terminal-kind
                     :state         state-name
                     :terminal-kind terminal-kind
                     :allowed       valid-terminal-kinds}))))

(defn create-state!
  "Create a workflow state.

   Arguments:
     state-name - Keyword identifier (e.g., :order/pending)

   Options:
     :label         - Human-readable name
     :initial?      - Is this the starting state?
     :terminal?     - Is this a final state (no outgoing transitions)?
     :terminal-kind - Classification of terminal outcome: :success / :failure / :cancel.
                      REQUIRED when :terminal? is true; rejected otherwise.
     :metadata      - Additional state data (any EDN-serializable value)

   Returns the created state entity.

   Throws ex-info if :terminal? is true without :terminal-kind, or if
   :terminal-kind is not in #{:success :failure :cancel}, or if
   :terminal-kind is supplied when :terminal? is not true.  Per
   decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md."
  [state-name & {:keys [label initial? terminal? terminal-kind metadata]}]
  (validate-terminal-kind! state-name terminal? terminal-kind)
  (dt/make :workflow/State
    (cond-> {:workflow/state-name state-name
             :workflow/state-label (or label (name state-name))}
      initial?      (assoc :workflow/initial? true)
      terminal?     (assoc :workflow/terminal? true)
      terminal-kind (assoc :workflow/terminal-kind terminal-kind)
      metadata      (assoc :workflow/state-metadata (pr-str metadata)))))

(defn find-state
  "Find a state by name. Returns entity map or nil."
  [state-name]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?name
                        :where [?e :workflow/state-name ?name]]
                      (db/db) state-name)]
    (db/entity eid)))

(defn state-terminal?
  "Check if a state is terminal."
  [state]
  (boolean (:workflow/terminal? state)))

(defn state-initial?
  "Check if a state is initial."
  [state]
  (boolean (:workflow/initial? state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transition Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-transition!
  "Create a workflow transition.

   Arguments:
     transition-name - Action keyword (e.g., :confirm, :ship)
     from-state      - Source state entity or keyword
     to-state        - Target state entity or keyword

   Options:
     :guard           - Symbol of guard function (fn [process context] -> boolean)
     :on-transition   - Symbol of side-effect function (fn [process context] -> nil)
     :requires-reason - Require a reason/comment for this transition

   Returns the created transition entity."
  [transition-name from-state to-state & {:keys [guard on-transition requires-reason?]}]
  (let [from-entity (if (keyword? from-state) (find-state from-state) from-state)
        to-entity (if (keyword? to-state) (find-state to-state) to-state)]
    (when-not from-entity
      (throw (ex-info "From state not found" {:state from-state})))
    (when-not to-entity
      (throw (ex-info "To state not found" {:state to-state})))
    (dt/make :workflow/Transition
      (cond-> {:workflow/transition-name transition-name
               :workflow/from-state (:db/id from-entity)
               :workflow/to-state (:db/id to-entity)}
        guard (assoc :workflow/guard guard)
        on-transition (assoc :workflow/on-transition on-transition)
        requires-reason? (assoc :workflow/requires-reason? true)))))

(defn find-transition
  "Find a transition by name from a specific state.

   Returns the transition entity or nil."
  [transition-name from-state]
  (let [from-id (if (keyword? from-state)
                  (:db/id (find-state from-state))
                  (:db/id from-state))]
    (when-let [eid (d/q '[:find ?e .
                          :in $ ?name ?from
                          :where
                          [?e :workflow/transition-name ?name]
                          [?e :workflow/from-state ?from]]
                        (db/db) transition-name from-id)]
      (db/entity eid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn define-workflow!
  "Define a complete workflow with states and transitions.

   Arguments:
     definition-name - Keyword identifier (e.g., :workflow/order-fulfillment)
     spec            - Map containing:
                       :states      - Vector of state specs
                       :transitions - Vector of transition specs
                       :version     - Optional version number

   State spec: {:name :state-name :label \"Label\" :initial? bool :terminal? bool
                :terminal-kind :success|:failure|:cancel}
   Transition spec: {:name :action :from :state :to :state :guard 'fn :requires-reason? bool}

   Terminal state specs MUST declare :terminal-kind per
   decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md.

   Returns the created workflow definition entity."
  [definition-name {:keys [states transitions version] :or {version 1}}]
  ;; Create states first
  (let [state-entities (into {}
                             (map (fn [{:keys [name label initial? terminal? terminal-kind metadata]}]
                                    [name (create-state! name
                                                         :label label
                                                         :initial? initial?
                                                         :terminal? terminal?
                                                         :terminal-kind terminal-kind
                                                         :metadata metadata)])
                                  states))
        ;; Create transitions
        transition-entities (mapv (fn [{:keys [name from to guard on-transition requires-reason?]}]
                                    (create-transition! name
                                                        (get state-entities from)
                                                        (get state-entities to)
                                                        :guard guard
                                                        :on-transition on-transition
                                                        :requires-reason? requires-reason?))
                                  transitions)]
    ;; Create the definition (skip validation since we're passing entity IDs)
    (log/info :WORKFLOW/DEFINE {:name definition-name
                                 :states (count states)
                                 :transitions (count transitions)
                                 :version version})
    (dt/make :workflow/Definition
      {:workflow/definition-name definition-name
       :workflow/states (mapv :db/id (vals state-entities))
       :workflow/transitions (mapv :db/id transition-entities)
       :workflow/version version}
      {:validate? false})))

(defn find-workflow
  "Find a workflow definition by name. Returns entity map or nil."
  [definition-name]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?name
                        :where [?e :workflow/definition-name ?name]]
                      (db/db) definition-name)]
    (db/entity eid)))

(defn- resolve-ref
  "Resolve an entity reference to a full entity using the current database.
   Handles: numbers, {:db/id N} maps, and datomic.Entity refs.
   Always returns a fresh entity from the current database snapshot."
  [ref]
  (cond
    (nil? ref) nil
    (number? ref) (d/entity (db/db) ref)
    ;; Check for :db/id key - works for both maps AND datomic entities
    ;; (datomic entities implement ILookup, so (:db/id entity) works)
    (:db/id ref) (d/entity (db/db) (:db/id ref))
    :else ref))

(defn- get-entity-id
  "Extract entity ID from various reference types.
   Handles: numbers, {:db/id N} maps, and datomic.Entity refs."
  [ref]
  (cond
    (nil? ref) nil
    (number? ref) ref
    ;; Check for :db/id key (works for both maps and Datomic entities)
    (:db/id ref) (:db/id ref)
    :else ref))

(defn get-workflow-states
  "Get all states for a workflow definition."
  [workflow]
  (mapv resolve-ref (:workflow/states workflow)))

(defn get-workflow-transitions
  "Get all transitions for a workflow definition."
  [workflow]
  (mapv resolve-ref (:workflow/transitions workflow)))

(defn get-initial-state
  "Get the initial state for a workflow."
  [workflow]
  (first (filter state-initial? (get-workflow-states workflow))))

(defn get-terminal-states
  "Get all terminal states for a workflow."
  [workflow]
  (filter state-terminal? (get-workflow-states workflow)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Process Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-process!
  "Start a new workflow process.

   Arguments:
     workflow - Workflow definition entity or keyword
     subject  - Subject entity that this process is attached to (e.g., an order)

   Options:
     :data - Process-specific data (any EDN-serializable value)

   Returns the created process entity."
  [workflow subject & {:keys [data]}]
  (let [workflow-entity (if (keyword? workflow) (find-workflow workflow) workflow)
        initial-state (get-initial-state workflow-entity)
        now (Date.)]
    (when-not workflow-entity
      (throw (ex-info "Workflow not found" {:workflow workflow})))
    (when-not initial-state
      (throw (ex-info "No initial state defined" {:workflow (:workflow/definition-name workflow-entity)})))
    (let [process (dt/make :workflow/Process
                    (cond-> {:workflow/definition (:db/id workflow-entity)
                             :workflow/current-state (:db/id initial-state)
                             :workflow/subject (:db/id subject)
                             :workflow/started-at now}
                      data (assoc :workflow/process-data (pr-str data)))
                    {:validate? false})]
      (event/log! :info "Workflow process started"
                  {:event/kind :workflow/process-started
                   :event/target (:db/id process)
                   :event/tags #{:workflow}})
      process)))

(defn find-process
  "Find a process by entity ID. Returns entity map or nil."
  [process-id]
  (when process-id
    (let [entity (db/entity process-id)]
      ;; db/entity returns {:db/id N} even for non-existent entities
      ;; Check for an actual workflow attribute to confirm existence
      (when (:workflow/started-at entity)
        entity))))

(defn update-process-data!
  "Replace a workflow process's `:workflow/process-data` payload.

   Encapsulates the raw Datomic transact for process-data updates,
   so service-layer code can stay at the workflow boundary instead of
   reaching into Datomic directly.  Per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md
   + codex SHOULD-FIX #1 (validation-as-workflow leaks raw transact).

   The value is `pr-str`'d on write (Sandbar's current process-data
   shape; per codex DEFER #1 this is the EDN-string substrate that
   may evolve post-0.1.0 into typed slot decomposition).

   Returns the refreshed process entity after the transact completes."
  [process data]
  (let [process-id (or (:db/id process) process)]
    (when-not (number? process-id)
      (throw (ex-info "update-process-data! requires a process entity or :db/id"
                      {:process process})))
    @(d/transact (db/conn)
                 [[:db/add process-id :workflow/process-data (pr-str data)]])
    (db/entity process-id)))

(defn find-process-by-subject
  "Find all processes for a subject entity."
  [subject]
  (let [subject-id (get-entity-id subject)
        eids (d/q '[:find [?e ...]
                    :in $ ?subject
                    :where [?e :workflow/subject ?subject]]
                  (db/db) subject-id)]
    (map db/entity eids)))

(declare get-current-state)

(defn list-processes
  "Enumerate all workflow processes in the system.

   Returns: vector of process entity maps, sorted by `:workflow/started-at`
   descending (most-recently-started first).

   Per Dan-directive 2026-05-14 PM endorsing process-enumeration as a
   valuable feature — the substrate had no public primitive for listing
   workflow processes prior to this addition.

   Consumers: `sandbar.mcp.tasks/handle-list` (MCP `tasks/list` verb);
   future REST listing endpoint + admin tooling."
  []
  (let [eids (d/q '[:find [?p ...]
                    :where [?p :workflow/started-at _]]
                  (db/db))]
    (->> eids
         (map db/entity)
         (sort-by :workflow/started-at)
         reverse
         vec)))

(defn list-active-processes
  "Enumerate workflow processes whose current state is NOT terminal —
   i.e., processes still in-flight.

   Returns: vector of process entity maps, sorted by `:workflow/started-at`
   descending.  A non-active process is one whose current state has
   `:workflow/terminal? true` (per the workflow-state-machine
   convention).

   Companion to `list-processes` (which returns all processes
   regardless of terminal state).  Per Dan-directive 2026-05-14 PM."
  []
  (->> (list-processes)
       (remove #(boolean (:workflow/terminal? (get-current-state %))))
       vec))

(defn get-current-state
  "Get the current state of a process."
  [process]
  (resolve-ref (:workflow/current-state process)))

(defn get-process-workflow
  "Get the workflow definition for a process."
  [process]
  (resolve-ref (:workflow/definition process)))

(defn get-process-subject
  "Get the subject entity for a process."
  [process]
  (resolve-ref (:workflow/subject process)))

(defn get-process-data
  "Get the deserialized process data."
  [process]
  (when-let [data-str (:workflow/process-data process)]
    (read-string data-str)))

(defn process-completed?
  "Check if a process has reached a terminal state."
  [process]
  (some? (:workflow/completed-at process)))

(defn process-in-terminal-state?
  "Check if a process is in a terminal state."
  [process]
  (state-terminal? (get-current-state process)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transition Execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-transitions-from-state
  "Get all transitions that originate from a given state within a workflow."
  [workflow state]
  (let [state-id (:db/id state)
        all-transitions (get-workflow-transitions workflow)]
    (filter (fn [t]
              (let [from-id (get-entity-id (:workflow/from-state t))]
                (= from-id state-id)))
            all-transitions)))

(defn- check-guard
  "Check if a transition's guard allows the transition."
  [transition process context]
  (if-let [guard-sym (:workflow/guard transition)]
    (try
      (let [guard-fn (requiring-resolve guard-sym)]
        (if guard-fn
          (guard-fn process context)
          (do
            (log/warn "Guard function not found:" guard-sym)
            false)))
      (catch Exception e
        (log/error e "Guard function failed:" guard-sym)
        false))
    ;; No guard = always allowed
    true))

(defn- run-on-transition
  "Run the on-transition side effect if defined."
  [transition process context]
  (when-let [fn-sym (:workflow/on-transition transition)]
    (try
      (when-let [on-fn (requiring-resolve fn-sym)]
        (on-fn process context))
      (catch Exception e
        (log/error e "On-transition function failed:" fn-sym)))))

(defn available-transitions
  "Get all available transitions from the current state of a process.

   Only returns transitions whose guards pass (if any).

   Options:
     :context - Context map passed to guard functions

   Returns a sequence of transition entities."
  [process & {:keys [context]}]
  (let [workflow (get-process-workflow process)
        current-state (get-current-state process)
        all-transitions (get-transitions-from-state workflow current-state)]
    (filter #(check-guard % process (or context {})) all-transitions)))

(defn can-transition?
  "Check if a specific transition is available from the current state.

   Arguments:
     process         - The process entity
     transition-name - The action keyword

   Options:
     :context - Context map passed to guard function"
  [process transition-name & {:keys [context]}]
  (let [available (available-transitions process :context context)]
    (some #(= transition-name (:workflow/transition-name %)) available)))

(defn create-history-entry!
  "Create a history entry for a transition."
  [from-state to-state action & {:keys [actor reason]}]
  (dt/make :workflow/History
    (cond-> {:workflow/history-from (:db/id from-state)
             :workflow/history-to (:db/id to-state)
             :workflow/history-action action
             :workflow/history-timestamp (Date.)}
      actor (assoc :workflow/history-actor (:db/id actor))
      reason (assoc :workflow/history-reason reason))
    {:validate? false}))

(defn transition!
  "Execute a transition on a process.

   Arguments:
     process         - The process entity
     transition-name - The action keyword (e.g., :confirm, :ship)

   Options:
     :context - Context map passed to guard/on-transition functions
     :actor   - User/principal performing the transition
     :reason  - Reason/comment for the transition

   Returns the updated process entity.
   Throws if transition is not available or reason is required but not provided."
  [process transition-name & {:keys [context actor reason]}]
  (let [current-state (get-current-state process)
        workflow (get-process-workflow process)
        transitions (get-transitions-from-state workflow current-state)
        transition (first (filter #(= transition-name (:workflow/transition-name %)) transitions))]
    (cond
      (nil? transition)
      (do
        (log/warn :WORKFLOW/TRANSITION-NOT-FOUND {:transition transition-name
                                                    :current-state (:workflow/state-name current-state)
                                                    :process-id (:db/id process)})
        (throw (ex-info "Transition not found from current state"
                        {:transition transition-name
                         :current-state (:workflow/state-name current-state)})))

      (and (:workflow/requires-reason? transition) (not reason))
      (throw (ex-info "Transition requires a reason"
                      {:transition transition-name}))

      (not (check-guard transition process (or context {})))
      (throw (ex-info "Guard condition not met"
                      {:transition transition-name}))

      :else
      (let [to-state (resolve-ref (:workflow/to-state transition))
            history (create-history-entry! current-state to-state transition-name
                                           :actor actor :reason reason)
            now (Date.)
            is-terminal? (state-terminal? to-state)
            tx-data (cond-> [[:db/add (:db/id process) :workflow/current-state (:db/id to-state)]
                             [:db/add (:db/id process) :workflow/history (:db/id history)]]
                      is-terminal?
                      (conj [:db/add (:db/id process) :workflow/completed-at now]))]
        ;; Run on-transition hook
        (run-on-transition transition process (merge context {:actor actor :reason reason}))

        ;; Apply state change
        @(d/transact (db/conn) tx-data)

        (log/info :WORKFLOW/TRANSITION {:process-id (:db/id process)
                                         :action transition-name
                                         :from (:workflow/state-name current-state)
                                         :to (:workflow/state-name to-state)
                                         :terminal? is-terminal?})
        (event/log! :info "Workflow transition"
                    {:event/kind :workflow/transition
                     :event/target (:db/id process)
                     :event/tags #{:workflow}
                     :event/description (str (name transition-name) ": "
                                             (:workflow/state-name current-state) " -> "
                                             (:workflow/state-name to-state))})
        (db/entity (:db/id process))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; History
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-process-history
  "Get the transition history for a process.

   Returns a sequence of history entries, sorted by timestamp."
  [process]
  (let [history-refs (or (:workflow/history process) [])]
    (->> history-refs
         (map (fn [ref] (if (map? ref) ref (db/entity ref))))
         (sort-by :workflow/history-timestamp))))

(defn history-entry->map
  "Convert a history entry to a readable map."
  [history]
  (let [from-state (let [ref (:workflow/history-from history)]
                     (if (map? ref) ref (db/entity ref)))
        to-state (let [ref (:workflow/history-to history)]
                   (if (map? ref) ref (db/entity ref)))
        actor (when-let [ref (:workflow/history-actor history)]
                (if (map? ref) ref (db/entity ref)))]
    {:action (:workflow/history-action history)
     :from (:workflow/state-name from-state)
     :to (:workflow/state-name to-state)
     :timestamp (:workflow/history-timestamp history)
     :actor (when actor (:db/id actor))
     :reason (:workflow/history-reason history)}))

(defn get-readable-history
  "Get the transition history as a sequence of readable maps."
  [process]
  (map history-entry->map (get-process-history process)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn processes-in-state
  "Find all processes currently in a specific state.

   Arguments:
     state - State entity or state name keyword

   Options:
     :workflow - Filter by workflow definition"
  [state & {:keys [workflow]}]
  (let [state-id (if (keyword? state)
                   (:db/id (find-state state))
                   (:db/id state))
        base-query '[:find [?e ...]
                     :in $ ?state
                     :where [?e :workflow/current-state ?state]]
        results (if workflow
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?state ?wf
                           :where
                           [?e :workflow/current-state ?state]
                           [?e :workflow/definition ?wf]]
                         (db/db) state-id wf-id))
                  (d/q base-query (db/db) state-id))]
    (map db/entity results)))

(defn active-processes
  "Find all active (non-completed) processes.

   Options:
     :workflow - Filter by workflow definition"
  [& {:keys [workflow]}]
  (let [results (if workflow
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?wf
                           :where
                           [?e :workflow/definition ?wf]
                           (not [?e :workflow/completed-at])]
                         (db/db) wf-id))
                  (d/q '[:find [?e ...]
                         :where
                         [?e :workflow/started-at _]
                         (not [?e :workflow/completed-at])]
                       (db/db)))]
    (map db/entity results)))

(defn completed-processes
  "Find all completed processes.

   Options:
     :workflow - Filter by workflow definition
     :since    - Only include processes completed after this Date"
  [& {:keys [workflow since]}]
  (let [results (cond
                  (and workflow since)
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?wf ?since
                           :where
                           [?e :workflow/definition ?wf]
                           [?e :workflow/completed-at ?t]
                           [(>= ?t ?since)]]
                         (db/db) wf-id since))

                  workflow
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?wf
                           :where
                           [?e :workflow/definition ?wf]
                           [?e :workflow/completed-at _]]
                         (db/db) wf-id))

                  since
                  (d/q '[:find [?e ...]
                         :in $ ?since
                         :where
                         [?e :workflow/completed-at ?t]
                         [(>= ?t ?since)]]
                       (db/db) since)

                  :else
                  (d/q '[:find [?e ...]
                         :where [?e :workflow/completed-at _]]
                       (db/db)))]
    (map db/entity results)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statistics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workflow-stats
  "Get statistics for a workflow.

   Returns counts by state and completion status."
  [workflow]
  (let [wf-id (if (keyword? workflow)
                (:db/id (find-workflow workflow))
                (:db/id workflow))
        state-counts (d/q '[:find ?state-name (count ?p)
                            :in $ ?wf
                            :where
                            [?p :workflow/definition ?wf]
                            [?p :workflow/current-state ?s]
                            [?s :workflow/state-name ?state-name]]
                          (db/db) wf-id)
        completed (count (d/q '[:find ?e
                                :in $ ?wf
                                :where
                                [?e :workflow/definition ?wf]
                                [?e :workflow/completed-at _]]
                              (db/db) wf-id))
        active (count (d/q '[:find ?e
                             :in $ ?wf
                             :where
                             [?e :workflow/definition ?wf]
                             (not [?e :workflow/completed-at _])]
                           (db/db) wf-id))]
    {:by-state (into {} state-counts)
     :completed completed
     :active active
     :total (+ completed active)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resource Loading
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-workflow-spec
  "Load a workflow specification from a resource file (EDN).

   Arguments:
     resource-path - Path to the resource file (e.g., \"workflows/resource-validation.edn\")

   Returns the parsed EDN map, or nil if resource not found."
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (with-open [reader (io/reader resource)]
      (edn/read (java.io.PushbackReader. reader)))))

(defn load-workflow-from-resource!
  "Load and define a workflow from a resource file.

   Arguments:
     resource-path - Path to the resource file (e.g., \"workflows/resource-validation.edn\")

   The resource file should be an EDN map with keys:
     :name        - Keyword identifier for the workflow
     :version     - Optional version number
     :states      - Vector of state specs
     :transitions - Vector of transition specs

   Returns the created workflow definition entity, or the existing workflow
   if it was already defined."
  [resource-path]
  (if-let [spec (load-workflow-spec resource-path)]
    (let [workflow-name (:name spec)]
      (if-let [existing (find-workflow workflow-name)]
        (do
          (log/debug :WORKFLOW/ALREADY-EXISTS {:name workflow-name})
          existing)
        (do
          (log/info :WORKFLOW/LOAD-FROM-RESOURCE {:path resource-path :name workflow-name})
          (define-workflow! workflow-name spec))))
    (throw (ex-info "Workflow resource not found" {:path resource-path}))))

(defn- list-classpath-resources
  "List filenames of resources inside a classpath directory.  Works
   uniformly for both filesystem-backed (lein dev) and JAR-backed
   (uberjar / Clojars-consumed dependency) classloader URLs.

   The legacy `(file-seq (io/file (io/resource dir)))` idiom fails on
   JAR URLs (`jar:file:.../X.jar!/dir`) because `java.io.File` cannot
   traverse JAR internals.  This helper dispatches on the URL protocol:

   - `file:` (development) — use `file-seq` over the directory
   - `jar:` (production / Clojars consumer) — walk JarFile entries via
     `JarURLConnection`

   Per Phase U Stage U-2 fix for ultrareview UR-1
   (`observations/sandbar_workflow_resource_load_jar_uberjar_break_2026_05_14.md`).

   Returns: vector of filename strings (basename only, no directory
   prefix).  Empty vector when the resource directory is absent."
  [dir]
  (let [url (io/resource dir)]
    (cond
      (nil? url) []

      (= "file" (.getProtocol url))
      (->> (io/file url)
           file-seq
           (filter #(.isFile ^java.io.File %))
           (mapv #(.getName ^java.io.File %)))

      (= "jar" (.getProtocol url))
      (let [^JarURLConnection conn (.openConnection url)
            ^JarFile jar (.getJarFile conn)
            prefix (str dir "/")]
        (with-open [_ jar]
          (->> (enumeration-seq (.entries jar))
               (map #(.getName %))
               (filter #(str/starts-with? % prefix))
               (remove #(str/ends-with? % "/"))
               (map #(subs % (count prefix)))
               (remove str/blank?)
               (remove #(str/includes? % "/")) ; flat directory only — no nested
               vec)))

      :else [])))

(defn load-all-workflows-from-resources!
  "Load all workflow definitions from resources/workflows/ directory.

   Works uniformly across development (filesystem-backed classpath)
   and production (JAR-backed classpath; e.g., consumer pulling
   sandbar as a Clojars dependency).  Per Phase U Stage U-2 fix for
   ultrareview UR-1.

   Returns a map of workflow names to workflow definition entities."
  []
  (let [workflows-dir "workflows"
        filenames (list-classpath-resources workflows-dir)
        edn-files (filter #(str/ends-with? % ".edn") filenames)]
    (into {}
          (for [filename edn-files]
            (let [path (str workflows-dir "/" filename)
                  workflow (load-workflow-from-resource! path)]
              [(:workflow/definition-name workflow) workflow])))))

(defn ensure-workflow!
  "Ensure a workflow is defined, loading from resource if necessary.

   Arguments:
     workflow-name - Keyword identifier for the workflow
     resource-path - Optional path to resource file; defaults to
                     \"workflows/{name}.edn\" where name is the workflow name

   Returns the workflow definition entity."
  [workflow-name & [resource-path]]
  (or (find-workflow workflow-name)
      (let [path (or resource-path
                     (str "workflows/" (name workflow-name) ".edn"))]
        (load-workflow-from-resource! path))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cancellation
;;
;; Per the layer-targeting + improve-abstraction-not-bypass disciplines:
;; the MCP layer (sandbar.mcp.tasks/handle-cancel) needs a generic
;; cancellation primitive. Rather than each consumer reaching into raw
;; d/transact, we expose cancel-process! as a workflow-aware operation
;; that composes with transition!.
;;
;; Convention (per
;; decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md):
;; a workflow that supports cancellation declares one or more terminal
;; states with :workflow/terminal-kind :cancel and at least one
;; transition leading to such a state from a non-terminal state.
;; Cancelability is determined by modeled classification, NOT by
;; ident-name string heuristic.

(defn- cancel-transition-for
  "Find an available transition that targets a state classified
   :workflow/terminal-kind :cancel.  Returns the transition entity, or
   nil if no cancel-shaped transition is available from the process's
   current state.  Per F-B-002 ADR; replaces the prior ident-name string
   heuristic."
  [process]
  (let [transitions (available-transitions process)]
    (->> transitions
         (filter (fn [t]
                   (when-let [target (:workflow/to-state t)]
                     (= :cancel (:workflow/terminal-kind target)))))
         first)))

(defn cancel-process!
  "Cancel a running workflow process. Finds an available transition that
   targets a state classified :workflow/terminal-kind :cancel and executes
   it via transition!.

   Arguments:
     process - The process entity (or eid; resolved via find-process)

   Options:
     :actor  - User/principal performing the cancellation
     :reason - Reason/comment for the cancellation (passed to transition!)

   Returns the updated process entity.

   Throws ex-info with :reason :no-cancel-transition when the workflow
   doesn't declare a cancellation path from the current state.

   This is the discipline-correct cancellation path per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md
   — consumers call this instead of constructing raw transact data."
  [process & {:keys [actor reason]
              :or   {reason "Cancelled by consumer (via workflow/cancel-process!)"}}]
  (let [process-entity (if (number? process) (find-process process) process)
        cancel-tx      (cancel-transition-for process-entity)]
    (when-not process-entity
      (throw (ex-info "Process not found"
                      {:reason :process-not-found
                       :input  process})))
    (when-not cancel-tx
      (throw (ex-info "Workflow does not support cancellation from the current state"
                      {:reason         :no-cancel-transition
                       :process-id     (:db/id process-entity)
                       :current-state  (some-> process-entity get-current-state :db/ident)})))
    ;; Transitions don't carry :db/ident (not :db.unique/identity); the
    ;; canonical action keyword lives on :workflow/transition-name.  Keep
    ;; :db/ident as the first preference for schema-defined transitions
    ;; that might carry an explicit ident in some future setup.
    (let [transition-name (or (:db/ident cancel-tx)
                              (:workflow/transition-name cancel-tx))]
      (log/info :workflow/cancel-process
                {:process-id (:db/id process-entity)
                 :transition transition-name
                 :actor      actor})
      (transition! process-entity transition-name
                   :actor  actor
                   :reason reason))))

(defn can-cancel?
  "Predicate: is the process in a state from which cancellation is
   possible? True iff at least one available transition targets a
   cancellation-shaped state."
  [process]
  (boolean (cancel-transition-for process)))
