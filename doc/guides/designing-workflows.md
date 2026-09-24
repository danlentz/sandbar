# Design and run a document-review workflow

This guide creates a reusable state machine and two processes: one succeeds, and the other is cancelled. Use a fresh development database with the memory and workflow schemas loaded. The example makes sequential calls without effect callbacks. Concurrent transition acceptance and atomic history/effects are not yet guaranteed; see [Workflows](../concepts/workflow-substrate.md#effects-need-their-own-contract). See [Getting started](getting-started.md) for connection setup.

## Define the states and transitions

State names are unique identifiers. Namespace them for your application; names such as `:review/submitted` are safer than a shared `:pending`.

```clojure
(require '[sandbar.db.datatype :as dt]
         '[sandbar.util.workflow :as wf])

(def definition
  (wf/define-workflow! :review/document
    {:states [{:name :review/submitted :initial? true}
              {:name :review/approved
               :terminal? true :terminal-kind :success}
              {:name :review/cancelled
               :terminal? true :terminal-kind :cancel}]
     :transitions [{:name :approve
                    :from :review/submitted :to :review/approved}
                   {:name :cancel
                    :from :review/submitted :to :review/cancelled}]}))
```

`define-workflow!` takes the definition name and a specification map. State specs use `:name`; transition specs use `:name`, `:from`, and `:to`. These authoring keys differ from the installed entity's `:workflow/*` properties. A terminal state must declare `success`, `failure`, or `cancel`.

## Start a process for a subject

```clojure
(def document
  (dt/make :mm/Observation
    {:db/ident :memory.examples/document
     :mm.memory/name "A document to review"
     :mm.memory/memory-type :observation
     :mm.memory/scope :project}))

(def process (wf/start-process! definition document))

(:workflow/state-name (wf/get-current-state process))
;; => :review/submitted
```

The second argument is the subject entity. Optional process data uses the keyword argument `:data`; it is separate from the subject and is stored as an EDN payload.

## Accept a transition and inspect the result

```clojure
(def approved (wf/transition! process :approve))

(:workflow/state-name (wf/get-current-state approved))
;; => :review/approved

(:workflow/terminal-kind (wf/get-current-state approved))
;; => :success

(count (wf/get-process-history approved))
;; => 1
```

Keep the returned process or fetch it again with `find-process` before the next operation. Entity values describe the state from which they were read. A retained object is not a live subscription.

Attempting `:approve` from the returned terminal process fails because no such transition is available. A client must handle that failure as an operation error, not success with an empty result.

## Cancel through the declared path

```clojure
(def another-process (wf/start-process! definition document))
(wf/can-cancel? another-process)
;; => true

(def cancelled
  (wf/cancel-process! another-process :reason "Review withdrawn"))

(:workflow/terminal-kind (wf/get-current-state cancelled))
;; => :cancel
```

Cancellation uses a transition to a cancel-classified terminal state. If the definition has no such path from the current state, cancellation is refused.

## Add policy at the transition boundary

A transition can name a guard function with signature `[process context]`, returning whether it permits the transition. Supply runtime context with `:context`. A transition can also require a reason or name an `:on-transition` callback.

Treat these functions as application code with explicit failure and effect contracts. The current transition path can log a callback exception and still advance state, and history is written separately from database effects. Use a separately checked operation when success of an effect must govern acceptance. An external request or file write also needs its own idempotence and recovery design; workflow state cannot roll it back.

Before deploying a definition, test invalid transitions, denied guards, missing reasons, repeated requests, stale clients, failed effects, and cancellation. See [operations](../operations.md) for inspecting running processes and [MCP reference](../api/mcp-verbs.md) for remote workflow/task operations.
