# Designing Workflows

> How to author a workflow — declaring states + transitions, classifying terminal outcomes with `:terminal-kind`, starting processes, firing transitions, and supporting cancellation.  For the theoretical background see [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md); for the workflow MCP verbs see [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md#workflow-verbs).

## The shape of a workflow

A workflow is three things:

1. A set of **states** — nodes in the FSM.  Some are terminal.
2. A set of **transitions** — edges connecting states.
3. An **initial state** — where new processes start.

Each terminal state carries a `:workflow/terminal-kind` declaration — one of `:success`, `:failure`, or `:cancel`.  This is what gives consumers a uniform "how did this end?" answer across every workflow.

## Walkthrough — a document-review workflow

A worked example: model the lifecycle of a `:review/Review` — submitted, in-progress, approved, rejected, or cancelled by the author.

### Step 1 — Map the state space

```
        ┌─────────────┐
        │ :submitted  │  (initial)
        └──────┬──────┘
               │ :start-review
               ▼
        ┌─────────────┐
        │ :in-review  │
        └─┬─────────┬─┘
   :approve│         │:reject
          ▼          ▼
   ┌──────────┐ ┌──────────┐
   │:approved │ │:rejected │
   │(success) │ │(failure) │
   └──────────┘ └──────────┘

        (cancellable from :submitted or :in-review)
                       │
                       ▼
                 ┌──────────┐
                 │:cancelled│
                 │ (cancel) │
                 └──────────┘
```

### Step 2 — Write the workflow definition

Workflows are typically declared in EDN under `resources/workflows/`.  Create `resources/workflows/review-workflow.edn`:

```clojure
{:workflow/ident :review/document-review-workflow
 :workflow/label "Document Review"
 :workflow/doc "Lifecycle of a :review/Review from submission to decision"

 :workflow/states
 [{:workflow/state-name :submitted
   :workflow/initial? true
   :workflow/doc "Document submitted; awaiting reviewer assignment"}

  {:workflow/state-name :in-review
   :workflow/doc "Reviewer is actively reviewing"}

  {:workflow/state-name :approved
   :workflow/terminal? true
   :workflow/terminal-kind :success
   :workflow/doc "Review complete; document accepted"}

  {:workflow/state-name :rejected
   :workflow/terminal? true
   :workflow/terminal-kind :failure
   :workflow/doc "Review complete; document not accepted"}

  {:workflow/state-name :cancelled
   :workflow/terminal? true
   :workflow/terminal-kind :cancel
   :workflow/doc "Author or system cancelled the review"}]

 :workflow/transitions
 [{:workflow/transition-name :start-review
   :workflow/from :submitted
   :workflow/to :in-review
   :workflow/doc "Reviewer accepts the document"}

  {:workflow/transition-name :approve
   :workflow/from :in-review
   :workflow/to :approved}

  {:workflow/transition-name :reject
   :workflow/from :in-review
   :workflow/to :rejected}

  ;; Cancellation transitions — note both source states are valid origins
  {:workflow/transition-name :cancel-from-submitted
   :workflow/from :submitted
   :workflow/to :cancelled}

  {:workflow/transition-name :cancel-from-in-review
   :workflow/from :in-review
   :workflow/to :cancelled}]}
```

### Step 3 — Load the workflow

```clojure
(require '[sandbar.util.workflow :as wf]
         '[clojure.edn :as edn])

(defn load-workflow-def! [path]
  (let [data (edn/read-string (slurp path))]
    (wf/define-workflow! data)))

(load-workflow-def! "resources/workflows/review-workflow.edn")
```

Sandbar typically auto-loads every `.edn` file in `resources/workflows/` at startup.  Confirm by listing workflows:

```clojure
(wf/all-workflows)
;; => (... :review/document-review-workflow ...)
```

### Step 4 — Start a process

```clojure
(def process
  (wf/start-process! :review/document-review-workflow
    {:workflow.process/target-entity (:db/id some-review)
     :workflow.process/owner          (:db/id some-user)}))

(:workflow/current-state process)
;; => :submitted
```

### Step 5 — Fire transitions

```clojure
;; Reviewer accepts the document
(def p2 (wf/transition! process :start-review))
(:workflow/current-state p2)
;; => :in-review

;; Reviewer approves
(def p3 (wf/transition! p2 :approve))
(:workflow/current-state p3)
;; => :approved
(:workflow/terminal-kind p3)
;; => :success
```

If a transition isn't valid from the current state, `transition!` throws:

```clojure
(wf/transition! p3 :approve)
;; throws — :approve has no valid transition from :approved (terminal)
```

### Step 6 — Cancel a process

```clojure
(def p (wf/start-process! :review/document-review-workflow {...}))

;; Check if cancellation is valid from the current state
(wf/can-cancel? p)
;; => true (because :cancel-from-submitted is a transition from :submitted)

(def p-cancelled (wf/cancel-process! p))
(:workflow/current-state p-cancelled)
;; => :cancelled
(:workflow/terminal-kind p-cancelled)
;; => :cancel
```

`cancel-process!` looks for a transition from the current state to a terminal-`:cancel` state.  If none exists, the cancel is refused.  This is the F-B-002 design (captured in [`decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12`](../../memory/decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md)) — cancellation is a property of the state design, not per-tool code.

## History and observability

Every transition records a `:workflow/Transition-Record`:

```clojure
(wf/process-history process)
;; => [{:workflow/transition-via :start-review
;;      :workflow/transition-at #inst "..."
;;      :workflow/from-state :submitted
;;      :workflow/to-state :in-review}
;;     {:workflow/transition-via :approve
;;      ...}]
```

History is queryable as data — no separate audit log.  See [`writing-a-clojure-client.md`](writing-a-clojure-client.md#working-with-workflows) for the API.

## Patterns

### Validation-as-workflow

Wrap per-entity validation in a workflow whose terminal-kind classifies the run:

```
:starting → :running → :results-pending → :complete
                                              ├── :success (all valid)
                                              ├── :failure (any invalid)
                                              └── :cancel (consumer aborted)
```

This is the pattern Sandbar uses internally for `sandbar.validation.*` MCP verbs.  Per-instance validation runs as the `:running → :results-pending` transition's action; the eventual terminal state is determined by the accumulated errors.

### MCP Tasks composition

Any workflow process is implicitly an MCP Task — `task-id` IS `:db/id`.  No additional registration needed.  When an MCP client calls `tasks/get task-12345`, the response is computed from the corresponding process.  See [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md#mcp-tasks-composition).

### Cancellable from multiple states

If cancellation is valid from several states, declare a separate transition for each source:

```clojure
{:workflow/transition-name :cancel-from-submitted
 :workflow/from :submitted
 :workflow/to :cancelled}

{:workflow/transition-name :cancel-from-in-review
 :workflow/from :in-review
 :workflow/to :cancelled}
```

This is more verbose than a wildcard "cancel from anywhere," but it lets `can-cancel?` answer state-by-state — and it makes the cancel-able states *visible* in the workflow definition.

### Uncancellable workflows

If cancellation is not valid for a workflow, simply declare no terminal-`:cancel` state.  `can-cancel?` returns `false`; `cancel-process!` refuses.

### Action handlers

Transitions can carry actions — Clojure functions invoked during the transition:

```clojure
{:workflow/transition-name :approve
 :workflow/from :in-review
 :workflow/to :approved
 :workflow/action 'my.namespace/approve-review-action}
```

The action receives the process entity and any transition arguments; its return value is recorded in the transition record.  Actions should be **idempotent** — transitions may be retried if the surrounding transaction fails.

## Testing workflows

Workflows are pure data; testing them is straightforward:

```clojure
(ns review.workflow-test
  (:require [clojure.test :refer :all]
            [sandbar.test.fixture :as fixture]
            [sandbar.util.workflow :as wf]))

(use-fixtures :each fixture/with-memdb)

(deftest happy-path
  (let [p (wf/start-process! :review/document-review-workflow {})
        p2 (wf/transition! p :start-review)
        p3 (wf/transition! p2 :approve)]
    (is (= :approved (:workflow/current-state p3)))
    (is (= :success (:workflow/terminal-kind p3)))))

(deftest cancel-from-submitted
  (let [p (wf/start-process! :review/document-review-workflow {})]
    (is (wf/can-cancel? p))
    (let [p2 (wf/cancel-process! p)]
      (is (= :cancelled (:workflow/current-state p2)))
      (is (= :cancel (:workflow/terminal-kind p2))))))

(deftest no-cancel-from-terminal
  (let [p (-> (wf/start-process! :review/document-review-workflow {})
              (wf/transition! :start-review)
              (wf/transition! :approve))]
    (is (not (wf/can-cancel? p)))))

(deftest invalid-transition-throws
  (let [p (wf/start-process! :review/document-review-workflow {})]
    (is (thrown? Exception (wf/transition! p :approve)))))
```

## Common pitfalls

**Forgetting `:workflow/terminal-kind` on a terminal state.**  The schema validator catches this (per the F-B-002 ADR), but the error message points at the workflow's EDN, not the runtime call.  Always declare `:terminal-kind` when `:terminal?` is true.

**Using string heuristics for cancellation.**  Pre-F-B-002, cancellation was decided by substring-matching `"cancel"` in state idents.  This was replaced by explicit `:terminal-kind :cancel`.  Don't reintroduce string heuristics — let the substrate enforce the semantics.

**Treating workflow processes as ephemeral.**  Processes are first-class entities.  They survive across server restarts, can be queried by Datalog, and accumulate history.  If you want truly ephemeral state, use Clojure atoms or refs, not workflows.

**Mixing application state into workflow state.**  Workflow state is "where in the FSM is this process?"  Application state ("what data is being reviewed?") lives in the application entities, referenced by the process's `:target-entity` or equivalent slot.  Conflating the two makes both messier.

**Designing workflows around code rather than around the consumer's mental model.**  The terminal-kind classification exists *for the consumer*.  Design the state space around how a consumer (CLI, AI, dashboard) should observe and intervene; let the implementation conform.

## See also

- [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) — the theoretical foundation
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md#workflow-verbs) — full MCP verb reference for workflows
- [`writing-a-clojure-client.md`](writing-a-clojure-client.md#working-with-workflows) — Clojure API patterns
- [`defining-new-classes.md`](defining-new-classes.md) — classes whose lifecycle this workflow models
