# Workflows: distinguish a reusable process from its execution

**A workflow makes progress inspectable by naming the states and transitions of a task.** Its definition describes what can happen. A process records one execution against a subject, including its current state and transition history.

A document-review workflow can be reused for many documents. Each document's review has its own process, history, and outcome. This avoids hiding lifecycle state in a client-local flag that other tools cannot inspect.

## Definitions, activities, and observations

Sandbar's model separates several roles:

| Role | Examples | Question |
| --- | --- | --- |
| Reusable specification | `:mm/Workflow`, `:mm/Job`, `:mm/Schedule`, `:mm/Fn` | What should be done? |
| Execution activity | `:workflow/Process`, `:mm/Run` | What execution is taking place? |
| Observation or event | Event records and emitted event maps | What happened? |
| Durable transition history | `:workflow/History` | How did this process reach its state? |

The distinction between an activity and a plan is also useful in [PROV-O](https://www.w3.org/TR/prov-o/#Activity), which provides vocabulary for describing provenance. Sandbar uses its own model and execution mechanisms; a similar vocabulary does not establish complete PROV conformance.

<a id="terminal-kind-classification"></a>

## A state machine with explicit outcomes

A workflow definition contains states, transitions, and an initial state. A transition names its source and target and may declare a guard, a required reason, and a callback. A process refers to the definition and its subject.

Terminal states carry one of three `:workflow/terminal-kind` values: `:success`, `:failure`, or `:cancel`. This gives consumers a stable outcome vocabulary without interpreting names such as “approved” or “abandoned.” A workflow supports cancellation by declaring a transition to a terminal cancel state; cancellation is not an arbitrary rewrite of the process's status.

`sandbar.util.workflow` provides definition, process, transition, cancellation, and history operations. State inspection uses `get-current-state`; the returned state contains its name and terminal classification. These properties are not flattened onto every process result.

## Effects need their own contract

A transition guard receives the process and context and decides whether the transition is available. A callback may produce transaction data associated with the state change. Arbitrary external actions performed inside a callback are outside the database transaction and need explicit retry and failure semantics.

The current transition implementation has important limits: it records history separately from the state/effects transaction, does not compare-and-set the expected state, and logs callback exceptions without necessarily refusing the transition. Competing or stale callers can therefore accept incompatible steps, and recorded history does not prove that the corresponding effects committed. Serialize calls per process and fetch fresh state; do not use this API as an atomic enforcement boundary or a guarantee of successful callback effects. Atomic acceptance and explicit callback-failure handling remain release work.

A process reaching a terminal state is evidence of its recorded lifecycle outcome. It is not independent proof that every external effect happened exactly once. Applications should attach evidence or results where completion has consequences beyond the graph.

## Client and operator use

Sandbar's experimental MCP task adapter uses workflow processes: task identity is based on the process entity rather than a second task database. It maps workflow terminal kinds into task-like status responses. This limited adapter does not establish conformance to the complete MCP Tasks extension. See [MCP](mcp-protocol.md) for the compatibility boundary.

Operators can inspect active processes and their history. Session recovery is a particular workflow policy, not the generic state machine: the current stale-session procedure reports candidates, preserves explicit maintenance holds, and rechecks eligibility when applying a close path. Review the [operations guide](../operations.md) before applying such a procedure.

Build a small working definition in [Designing workflows](../guides/designing-workflows.md). Source: [`sandbar.util.workflow`](../../src/sandbar/util/workflow.clj), [`workflow schema`](../../schema/workflow.edn), and [`temporal/activity vocabulary`](../../schema/mm-temporal.edn).
