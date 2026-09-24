(ns sandbar.mcp.tasks
  "Experimental task adapter backed by workflow processes.
   Task IDs are string forms of process eids. List/get project the stored
   lifecycle state; cancel delegates to the workflow's declared cancellation
   path. start-task! creates a process and emits a progress-style notification.
   This adapter does not implement the full standard MCP Tasks contract or
   automatically convert long-running tool calls into standard tasks.
   See doc/concepts/mcp-protocol.md for compatibility limits."
  (:require [clojure.tools.logging  :as log]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]
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
;; Map a workflow process state to this adapter's task status using the
;; current state's :workflow/terminal-kind classification:
;;
;;   :workflow/terminal-kind :success → "completed"
;;   :workflow/terminal-kind :failure → "failed"
;;   :workflow/terminal-kind :cancel  → "cancelled"
;;   (terminal? true, no kind)        → "completed" (graceful degradation + warn)
;;   (terminal? false)                → "running"
;;
;; The adapter distinguishes terminal outcomes instead of collapsing them.

(def ^:private terminal-kind->task-status
  {:success "completed"
   :failure "failed"
   :cancel  "cancelled"})

(defn process->task-status
  "Project a workflow process to this adapter's task status map. Reads
   :workflow/terminal-kind on the current state to distinguish
   success, failure and cancellation outcomes.

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
;; tasks/list handler

(defn handle-list
  "Enumerate workflow processes through the experimental tasks/list adapter.

   Adapter response shape:
   - `:tasks` is a vector of `{:taskId :status :state}` maps; terminal
     tasks additionally carry `:content`.

   Optional `:active-only` arg filters out terminal-state processes
   (via `workflow/list-active-processes`); default lists all processes
   (via `workflow/list-processes`). This is not the full standard MCP
   Tasks result schema; see doc/concepts/mcp-protocol.md."
  [id params]
  (try
    (let [active-only? (boolean (:active-only params))
          processes (if active-only?
                      (workflow/list-active-processes)
                      (workflow/list-processes))
          terminal-statuses #{"completed" "failed" "cancelled"}
          tasks (mapv (fn [process]
                        (let [task-id (process->task-id process)
                              {:keys [status state]} (process->task-status process)
                              base    {:taskId task-id :status status :state state}]
                          (if (contains? terminal-statuses status)
                            (assoc base :content (task-result-data process))
                            base)))
                      processes)]
      {:jsonrpc "2.0"
       :id      id
       :result  {:tasks tasks
                 :total (count tasks)
                 :active-only active-only?}})
    (catch Exception e
      (log/error e :MCP/tasks-list-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    jsonrpc-status/internal-error
                 :message "Tasks/list failed"
                 :data    {:exception-message (.getMessage e)}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tasks/get handler

(defn handle-get
  "Return current adapter status and terminal content for a task-id.

   Adapter result shape:
   - Running: {:taskId :status \"running\" :state <workflow-state>}
   - Terminal: {:taskId :status <terminal-status> :state :content [...]}
   - Missing: error -32602"
  [id params]
  (try
    (let [task-id (:taskId params)
          process (task-id->process task-id)]
      (cond
        (nil? task-id)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code jsonrpc-status/invalid-params
                   :message "tasks/get requires :taskId parameter"}}

        (nil? process)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    jsonrpc-status/invalid-params
                   :message (str "Task not found: " task-id)
                   :data    {:received-task-id task-id}}}

        :else
        (let [status (process->task-status process)
              ;; All terminal states return :content (success / failure /
              ;; cancelled) so clients can read the final process data
              ;; for each terminal kind.  Per ultrareview #8 at
              ;; tasks.clj:155 — the prior shape only attached :content
              ;; when status was "completed", silently dropping content
              ;; for "failed" and "cancelled" terminal kinds; downstream
              ;; clients couldn't distinguish "I have an error to read"
              ;; from "I have no information."
              terminal-statuses #{"completed" "failed" "cancelled"}
              base    {:taskId task-id
                       :status (:status status)
                       :state  (:state status)}
              result  (if (contains? terminal-statuses (:status status))
                        (assoc base :content (task-result-data process))
                        base)]
          {:jsonrpc "2.0"
           :id      id
           :result  result})))
    (catch Exception e
      (log/error e :MCP/tasks-get-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    jsonrpc-status/internal-error
                 :message "Tasks/get failed"
                 :data    {:exception-message (.getMessage e)}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tasks/cancel handler
;;
;; Cancellation delegates to the workflow's modeled cancellation path.
;; It does not interrupt an arbitrary running function or thread.

(defn handle-cancel
  "Request cancellation through workflow/cancel-process!.
   A missing task is a JSON-RPC invalid-params error. A workflow refusal
   returns result.isError with explanatory content; other failures return
   an internal-error response. A successful response reports the resulting
   workflow state, not proof that external work has been interrupted."
  [id params]
  (let [task-id (:taskId params)
        process (task-id->process task-id)]
    (cond
      (nil? task-id)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code jsonrpc-status/invalid-params
                 :message "tasks/cancel requires :taskId parameter"}}

      (nil? process)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    jsonrpc-status/invalid-params
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
           :error   {:code    jsonrpc-status/internal-error
                     :message "Tasks/cancel failed"
                     :data    {:exception-message (.getMessage e)}}})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task initiator — helper for tools that start long-running ops
;;
;; Explicit callers may start a workflow process and return its task-id.
;; Long-running tools are not automatically routed through this helper.

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
