# Workflows as Substrate

> Sandbar treats workflows — state machines, running processes, terminal outcomes, cancellation — as **first-class entities in the metamodel**, not as ad-hoc plumbing per long-running operation.  State machines are `:workflow/Workflow` instances; running processes are `:workflow/Process` instances; cancellation is encoded as a terminal-kind on the workflow's state nodes.  MCP Tasks (long-running operations in the Model Context Protocol) are workflow processes — `task-id` IS `:db/id`, no parallel registry.

## Thesis

Long-running operations need three things consistently:

1. **History** — what happened, in what order, when.
2. **Cancellation** — the ability to abort a running operation cleanly.
3. **Outcome classification** — terminal states with a clear "kind of done" — success, failure, or cancel.

The naive shape is to reinvent each of these per tool — every long-running operation grows its own status table, its own cancellation flag, its own ad-hoc completion semantics.  Eventually consumers reinvent classification logic too: "this tool returned `:done`, but does that mean success or failure?"  The pattern repeats per operation; the inconsistency multiplies per consumer.

Sandbar's commitment: **workflow state machines are a substrate, not a plumbing.**  They are stored as data using the same metamodel; running instances are typed entities; cancellation is a property of the state design, not the per-tool code.  Every long-running operation inherits the discipline.  Consumers learn one shape and reuse it everywhere.

## Lineage

### Finite-state machines

The substrate is a directed graph of states linked by named transitions.  This is the classical Mealy/Moore machine (Mealy 1955; Moore 1956) extended with action handlers attached to transitions.  Workflows in this sense are FSMs whose state space and transition relation are recorded in the database.

Petri nets (Petri 1962) generalize FSMs with concurrent token-flow semantics; Sandbar's substrate stops short of full Petri net expressiveness — each process holds a single current state, not a marking — but the Petri-net mindset (states as places, transitions as named events) informs the vocabulary.

### Harel statecharts

Statecharts (Harel 1987) extend FSMs with hierarchy, orthogonal regions, and history pseudostates.  Sandbar's workflow substrate is flat — no nested states, no parallel regions — but the *vocabulary* (state, transition, action, terminal) is statechart-shaped, and a future hierarchical extension would compose naturally.

### Pi-calculus and process algebras

Process algebras (Milner 1980, *A Calculus of Communicating Systems*; Hoare 1985, *Communicating Sequential Processes*) give the formal account of concurrent processes communicating over channels.  Sandbar's workflow substrate is sequential — each process is a single chain of state transitions — but the concept of a *process* as a first-class runtime artifact derives from this tradition.

### Saga pattern

Garcia-Molina & Salem (1987) introduced the *saga* for long-running database transactions: a sequence of local operations, each with a compensating reverse operation, providing eventual consistency in lieu of distributed transactions.  Sandbar's workflow substrate is saga-shaped at a higher level — transitions can carry compensating actions; terminal states distinguish completion modes (`:success` continues forward; `:failure` may trigger compensation; `:cancel` indicates the consumer pulled the plug).

### BPMN 2.0

Business Process Model and Notation (OMG 2011) standardizes process modeling for enterprise workflows.  Sandbar does not target BPMN as a wire format but borrows its discipline: terminal events are *categorized* (end event, error end event, cancel end event); the cancel end event in particular is recognized as a first-class shape.  Sandbar's `terminal-kind :cancel` is the BPMN cancel-end-event idea, recorded as data.

## The substrate

Three classes anchor the workflow substrate.

### `:workflow/Workflow`

A workflow definition — a named state machine.  Its slots:

| Slot                       | Meaning                                                                                        |
|----------------------------|------------------------------------------------------------------------------------------------|
| `:workflow/states`         | Set of `:workflow/State` entities (the nodes of the FSM).                                      |
| `:workflow/initial-state`  | Reference to the state where a fresh process starts.                                           |
| `:workflow/transitions`    | Set of `:workflow/Transition` entities (the edges of the FSM).                                 |

### `:workflow/State`

A node in the workflow graph.  Its slots:

| Slot                       | Meaning                                                                                        |
|----------------------------|------------------------------------------------------------------------------------------------|
| `:workflow/state-name`     | The state's name (keyword or string, namespaced under the workflow).                          |
| `:workflow/terminal?`      | Boolean — is this an accepting (final) state?                                                 |
| `:workflow/terminal-kind`  | If terminal, one of `:success` / `:failure` / `:cancel` — the *kind of done* classification.   |

### `:workflow/Process`

A running (or terminated) instance of a workflow.  Its slots:

| Slot                       | Meaning                                                                                        |
|----------------------------|------------------------------------------------------------------------------------------------|
| `:workflow/process-of`     | Reference to the `:workflow/Workflow` this process instantiates.                              |
| `:workflow/current-state`  | Reference to the `:workflow/State` the process currently occupies.                            |
| `:workflow/history`        | Ordered sequence of state-transition records — what happened, in what order, when.            |

The history is itself an entity sequence (each transition record is a `:workflow/Transition-Record` with `:workflow/transition-at` timestamp and `:workflow/transition-via` reference to the transition that fired).  The full history is queryable as data — no parallel log, no out-of-band telemetry.

## Terminal-kind classification

Every terminal state declares its kind.  The three valid values:

| Kind         | Meaning                                                                                  |
|--------------|------------------------------------------------------------------------------------------|
| `:success`   | The operation completed and produced its intended result.                                |
| `:failure`   | The operation completed but did not produce its intended result (error, validation, etc).|
| `:cancel`    | The operation was aborted by a consumer before reaching success or failure.              |

This classification is **not derived** from per-tool conventions like "the result map had `:error`" or "the response was 4xx."  It is a property of the workflow's state design — at the time a workflow is authored, the author decides which terminal states are which kind, and the substrate enforces the classification.

The consequence: consumers don't need to interpret tool-specific result conventions to know whether an operation succeeded.  `process->task-status` reads `:workflow/terminal-kind` directly.  The MCP `tasks/get` response includes the kind verbatim.  Cross-tool consumers learn one shape.

### Why three kinds and not two

The distinction between failure and cancel is operationally meaningful.  A failure represents a problem with the operation — an error, a validation mismatch, an unreachable dependency.  A cancel represents the consumer's choice — "I no longer want this; stop."  These produce different downstream behaviors: failures often warrant retry or escalation; cancels typically do not.  Collapsing them into one "not success" bucket loses information that consumers need.

This is the BPMN insight (OMG 2011): cancel-end-events are distinct from error-end-events.  Sandbar adopts the same distinction.

## Cancellation as a property of the state design

Cancellation is not a per-tool flag.  It is implemented as: *a transition exists from the current state to a terminal state whose `:terminal-kind` is `:cancel`*.

When a consumer requests cancellation:

1. The substrate looks up whether the current state has an outbound transition to a terminal `:cancel` state.
2. If yes, the substrate fires that transition.  The process terminates.  History records the cancellation.
3. If no, the cancellation is refused — the workflow's author did not declare cancellation valid at this state.

This is the F-B-002 design — captured in [`decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12`](../../memory/decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md) — and it is what `cancel-process!` and `can-cancel?` in `sandbar.util.workflow` resolve to.

The consequence: cancellation semantics are **workflow-substrate-level**.  Authoring a workflow with an unconditional `:cancel` terminal makes that workflow cancellable from any state; authoring without one makes it uncancellable; authoring with a mid-workflow `:cancel` makes it cancellable only at specific states.  The author makes the call; the substrate enforces.

## MCP Tasks composition

MCP (Model Context Protocol) defines a *Task* surface for long-running operations: `tasks/list`, `tasks/get`, `tasks/cancel`, with per-task status and outcome.

In Sandbar, **MCP Tasks ARE workflow Processes**.  `task-id` IS `:db/id`.  There is no parallel registry, no translation table between MCP-tasks and workflow-processes.  When an MCP client calls `tasks/get task-123`:

1. The handler resolves `task-123` as a `:workflow/Process` entity.
2. It reads the process's `:workflow/current-state` and `:workflow/terminal-kind`.
3. It projects those into the MCP Task status shape (`pending` / `running` / `success` / `failure` / `canceled`).

When the client calls `tasks/cancel task-123`, the handler calls `cancel-process! task-123` on the substrate.

This composition is the operational consequence of "workflows as substrate."  Without it, MCP Tasks would need their own status table, their own cancellation flag, their own classification — duplicating what the workflow substrate already provides.  With it, the MCP surface is a thin projection.

## Validation as workflow

A separate-but-symmetric application of the substrate: bulk validation of every instance of a class is modeled as a workflow.

The pattern:

- `:validation/Workflow` — defines states like `:starting → :running → :results-pending → :complete`.
- `:validation/Process` — an instance, started when an MCP client calls `sandbar.validation.start`.
- Per-instance validation runs as a state transition; errors accumulate into the process's history.
- Terminal states classify the run: `:success` (all instances valid), `:failure` (any instance invalid), `:cancel` (consumer aborted mid-run).

The consumer gets:

- A task they can poll (`sandbar.validation.run` → status).
- A task they can cancel (`sandbar.validation.cancel`).
- A task whose terminal kind tells them what kind of done it was.
- A task whose history tells them which instances were checked and what was found.

All inherited from the substrate.  No per-tool plumbing.

## Cross-tool consistency dividend

The substrate produces a consistency dividend across all long-running tools.  A consumer learns one shape — *workflows have states; running processes have current-state and history; terminal states have a kind* — and applies it everywhere:

- Bulk validation
- MCP Tasks
- Service-account issuance (planned, see `ideas/service_account_issuance_rotation_should_be_first_class_workflow`)
- Background indexing
- Schema migration

Each is a workflow.  Each gets cancellation by declaring a `:cancel` terminal.  Each gets history by virtue of being a Process.  Each gets outcome classification by virtue of typed terminal states.

The cost of *not* using the substrate would be 5 ad-hoc status/cancellation/history implementations, each subtly different.  The cost of using it is one substrate to learn.

## Comparison with adjacent patterns

### vs. Promises / Futures

Promises encapsulate an eventual single value.  Workflows encapsulate a *trajectory through states* with intermediate observable status, cancellation, and history.  Promises are the right shape for "compute this and return the result"; workflows are the right shape for "run this for a while and let consumers observe progress and intervene."

### vs. Job queues (Sidekiq / Resque / Celery)

Job queues handle scheduling, retry, and worker dispatch.  They typically expose minimal status — "queued / running / done / failed" — with no formal state model and no first-class cancellation.  Sandbar's workflow substrate is one level above: a job-queue's "running" state could be modeled as a workflow process, and the substrate would give it the typed state space the queue lacks.

### vs. Step Functions / Cadence / Temporal

AWS Step Functions, Cadence, and Temporal (Uber → io.temporal) are workflow engines proper — they have the state-machine vocabulary, persistence, and replay semantics.  Sandbar's workflow substrate is similar in shape but smaller in scope: no distributed coordination, no time-skewed replay, no built-in retry logic.  Sandbar's value-add is the *integration with the metamodel* — workflows are typed entities; processes can carry domain references; the same `dt/*` API queries them.  A workflow engine like Temporal could be the execution backend; Sandbar would be the modeling and observation surface.

### vs. Actor models (Erlang / Akka)

Actors encapsulate state and process messages sequentially; the actor's behavior may be modeled as an FSM.  Sandbar's workflows are *observable from outside* in a way actors typically aren't: the process's current state and history are queryable directly via Datalog, no message-passing required.  This is the price of explicit state-as-data — visibility is high; encapsulation is lower.

## References

**Finite-state machines and statecharts**

- Mealy, G.H. (1955). *A Method for Synthesizing Sequential Circuits.* Bell System Technical Journal, 34(5), 1045–1079.
- Moore, E.F. (1956). *Gedanken-experiments on Sequential Machines.* Automata Studies, Princeton.
- Harel, D. (1987). *Statecharts: A Visual Formalism for Complex Systems.* Science of Computer Programming, 8(3), 231–274.

**Petri nets and process modeling**

- Petri, C.A. (1962). *Kommunikation mit Automaten.* Doctoral dissertation, University of Hamburg.

**Process algebras**

- Milner, R. (1980). *A Calculus of Communicating Systems.* Lecture Notes in Computer Science, Springer.
- Hoare, C.A.R. (1985). *Communicating Sequential Processes.* Prentice-Hall.

**Saga pattern**

- Garcia-Molina, H. & Salem, K. (1987). *Sagas.* ACM SIGMOD Conference 1987.

**BPMN and enterprise workflow modeling**

- Object Management Group (2011). *Business Process Model and Notation (BPMN) Version 2.0.*  https://www.omg.org/spec/BPMN/2.0/

**Modern workflow engines (for comparison)**

- Hightower, K. & contributors (2018–). *Temporal — open-source workflow orchestration.* (Forked from Uber Cadence.)
- AWS (2016–). *AWS Step Functions Developer Guide.*

**Temporal logic of programs (for verification of workflows)**

- Pnueli, A. (1977). *The Temporal Logic of Programs.* 18th Annual Symposium on Foundations of Computer Science (FOCS).

## See also

- [`metamodel.md`](metamodel.md) — workflows + states + processes are typed metamodel entities
- [`mcp-protocol.md`](mcp-protocol.md) — how MCP Tasks compose with workflow processes
- [`doc/api/mcp-verbs.md`](../api/mcp-verbs.md) — the workflow + task verb catalog
- [`doc/guides/designing-workflows.md`](../guides/designing-workflows.md) — hands-on authoring guide
