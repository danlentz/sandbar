(ns sandbar.mcp.tasks
  "MCP Tasks primitive (experimental) — durable execution wrappers for
   long-running operations. Per
   decisions/sandbar_mcp_server_design_2026_05_12.md B.1.10:

   MCP's Tasks primitive composes naturally with Sandbar's workflow
   substrate (validation-as-workflow pattern from sandbar.service.validation).
   A long-running tool-call returns a TASK HANDLE instead of immediate
   content; the client polls `tasks/get <handle>` for status; receives
   the terminal result when the workflow process reaches a terminal state.

   Discipline per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
   targets `sandbar.util.workflow/*` abstraction — start-process!,
   find-process, get-current-state, process-completed?,
   process-in-terminal-state?, get-process-data, get-process-history.
   NEVER raw datomic.api.

   Stage C.7 foundation:
   - tasks/get returns current workflow process state
   - tasks/cancel terminates a running process
   - start-task! helper for tools that initiate long-running ops

   Subsequent stages:
   - C.7.1 progress notifications via notifications/progress
   - C.7.2 result projection (terminal-state outputs → MCP task result)
   - C.7.3 tools/call auto-dispatching to Task when expected duration
     exceeds threshold"
  (:require [clojure.tools.logging  :as log]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.util.workflow  :as workflow]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task ↔ workflow process correspondence
;;
;; The task-id IS the workflow process's :db/id (as a string for JSON
;; transport). Eliminates a parallel registry; the workflow process is
;; the durable source of truth.

(defn process->task-id
  "Convert a workflow process entity to a task-id string."
  [process]
  (when process
    (str (:db/id process))))

(defn task-id->process
  "Look up the workflow process entity for a given task-id. Returns nil
   if not found."
  [task-id]
  (when task-id
    (try
      (let [eid (Long/parseLong task-id)]
        (workflow/find-process eid))
      (catch NumberFormatException _ nil)
      (catch Exception _ nil))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task status projection
;;
;; Per the MCP Tasks (experimental) shape — map a workflow process state
;; to a Task status string by reading the current state's
;; :workflow/terminal-kind classification per
;; decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md:
;;
;;   :workflow/terminal-kind :success → "completed"
;;   :workflow/terminal-kind :failure → "failed"
;;   :workflow/terminal-kind :cancel  → "cancelled"
;;   (terminal? true, no kind)        → "completed" (graceful degradation + warn)
;;   (terminal? false)                → "running"
;;
;; Resolves F-S-003 (task status projection previously collapsed all
;; terminal outcomes into "completed").

(def ^:private terminal-kind->task-status
  {:success "completed"
   :failure "failed"
   :cancel  "cancelled"})

(defn process->task-status
  "Project a workflow process to an MCP Task status map.  Reads
   :workflow/terminal-kind on the current state to distinguish
   success / failure / cancellation outcomes per F-B-002 ADR.

   Returns a map with :status (one of \"missing\" / \"running\" /
   \"completed\" / \"failed\" / \"cancelled\") + :state (the current
   state's :db/ident) when a current state exists."
  [process]
  (let [current-state (when process (workflow/get-current-state process))
        ;; Schema-loaded states carry :db/ident; runtime-defined states (via
        ;; wf/define-workflow!) carry only :workflow/state-name.  Fall back so
        ;; consumers get a usable keyword in both cases.
        state-ident   (or (:db/ident current-state)
                          (:workflow/state-name current-state))
        terminal?     (boolean (:workflow/terminal? current-state))
        kind          (:workflow/terminal-kind current-state)]
    (cond
      (nil? process)
      {:status "missing"}

      (not terminal?)
      {:status "running"
       :state  state-ident}

      (contains? terminal-kind->task-status kind)
      {:status (terminal-kind->task-status kind)
       :state  state-ident}

      :else
      (do
        (log/warn :process->task-status/terminal-without-kind
                  {:process-id (:db/id process)
                   :state      state-ident})
        {:status "completed"
         :state  state-ident}))))

(defn- task-result-data
  "Project the workflow process's data + final state for the MCP task
   result. Returns a content-array-shaped value."
  [process]
  (let [state-ident (some-> process workflow/get-current-state :db/ident)
        data        (workflow/get-process-data process)]
    [{:type "text"
      :text (str "Task in state: " state-ident "\n"
                 "Process data: " (pr-str data))}]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tasks/get handler

(defn handle-get
  "MCP `tasks/get` — return current status (+ result if terminal) for
   a task-id.

   Response shape per MCP Tasks (experimental):
   - Running: {:taskId :status \"running\" :state <workflow-state>}
   - Completed: {:taskId :status \"completed\" :content [...]}
   - Missing: error -32602"
  [id params]
  (try
    (let [task-id (:taskId params)
          process (task-id->process task-id)]
      (cond
        (nil? task-id)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code -32602
                   :message "tasks/get requires :taskId parameter"}}

        (nil? process)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    -32602
                   :message (str "Task not found: " task-id)
                   :data    {:received-task-id task-id}}}

        :else
        (let [status (process->task-status process)
              base   {:taskId task-id
                      :status (:status status)
                      :state  (:state status)}
              result (if (= "completed" (:status status))
                       (assoc base :content (task-result-data process))
                       base)]
          {:jsonrpc "2.0"
           :id      id
           :result  result})))
    (catch Exception e
      (log/error e :MCP/tasks-get-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32603
                 :message "Tasks/get failed"
                 :data    {:exception-message (.getMessage e)}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tasks/cancel handler
;;
;; Stage C.7: cancellation maps to terminating the underlying workflow
;; process. The workflow substrate doesn't yet expose a direct
;; cancel-process! — Sandbar's service.validation/cancel-validation!
;; pattern is class-specific. Stage C.7.4 follow-up: extend the workflow
;; abstraction with `workflow/cancel-process!` per layer-targeting
;; discipline (improve-abstraction-not-bypass).

(defn handle-cancel
  "MCP `tasks/cancel` — terminate a running task. Stage C.7 returns a
   not-yet-implemented error pointing at the workflow-abstraction gap
   per the layer-targeting discipline; C.7.4 lands the
   workflow/cancel-process! function upstream."
  [id params]
  (let [task-id (:taskId params)
        process (task-id->process task-id)]
    (cond
      (nil? task-id)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code -32602
                 :message "tasks/cancel requires :taskId parameter"}}

      (nil? process)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32602
                 :message (str "Task not found: " task-id)}}

      :else
      (try
        (let [cancelled (workflow/cancel-process! process
                                                  :reason "Cancelled via MCP tasks/cancel")
              status    (process->task-status cancelled)]
          {:jsonrpc "2.0"
           :id      id
           :result  {:taskId  task-id
                     :status  (:status status)
                     :state   (:state status)
                     :content [{:type "text"
                                :text (str "Task cancelled. Workflow state: "
                                           (:state status))}]}})
        (catch clojure.lang.ExceptionInfo e
          (let [ex-reason (:reason (ex-data e))]
            {:jsonrpc "2.0"
             :id      id
             :result  {:content [{:type "text"
                                  :text (str "Cannot cancel task: " (.getMessage e)
                                             " (reason: " ex-reason ")")}]
                       :isError true}}))
        (catch Exception e
          (log/error e :MCP/tasks-cancel-error)
          {:jsonrpc "2.0"
           :id      id
           :error   {:code    -32603
                     :message "Tasks/cancel failed"
                     :data    {:exception-message (.getMessage e)}}})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task initiator — helper for tools that start long-running ops
;;
;; Per B.1.10: tools that operate on potentially-large entity sets
;; (export-all, validate-all-instances, bulk-ingest) call this to start
;; a workflow process + return a task-id immediately.

(defn start-task!
  "Initiate a long-running task backed by a workflow process. Returns
   a task-id (string).

   Arguments:
   - workflow-ident — the workflow definition's :db/ident
   - subject        — the entity (or eid) the process operates on
   - data           — initial process data map

   Per layer-targeting discipline: calls workflow/start-process! —
   does NOT touch datomic.api directly."
  [workflow-ident subject data]
  (let [process (workflow/start-process! workflow-ident subject data)
        task-id (process->task-id process)]
    (log/info :MCP/task-started
              {:task-id        task-id
               :workflow-ident workflow-ident
               :subject        subject})
    ;; Push a progress notification so subscribed clients learn of the
    ;; new task immediately.
    (notifications/publish! "notifications/progress"
                            {:taskId task-id
                             :status "running"
                             :workflow workflow-ident})
    task-id))
