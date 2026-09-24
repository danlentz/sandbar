# Events: let consumers observe change through a shared vocabulary

**A producer should be able to describe an occurrence without knowing every consumer interested in it.** Sandbar's event bus uses the class hierarchy for dispatch: a subscriber to a parent class can receive events of its subclasses. Producers and subscribers share the model rather than a growing list of special-case callbacks.

## Dispatch is explicit

`sandbar.event/subscribe!` registers a function for a class and returns that function as the unsubscribe handle. `fire!` dispatches an event map. `:event/class` selects the class; `:event/kind` is the fallback. Dispatch collects matching subscribers through the model's ancestry and caches the result.

Current dispatch is synchronous in the publisher's thread. Each handler has an exception barrier, so one throwing handler does not prevent the remaining handlers from being attempted. This does not isolate slow handlers or make delivery durable. A subscriber doing blocking work must arrange a suitable execution boundary and capacity policy.

## Several change paths coexist

| Mechanism | Current role |
| --- | --- |
| `sandbar.event` | Explicit class-based event publication and subscription |
| `sandbar.reactive` | Post-mutation callbacks used by projection |
| `sandbar.reactive.tx-source` | Datomic transaction-report stream adapter |
| MCP resource subscriptions | Client-facing resource-change notifications |
| Workflow history | Durable records of process transitions |

These mechanisms are related, but creating a database entity does not automatically publish every corresponding event through all of them. The transaction-report source is not a blanket boot-wired replacement for the mutation callback path. MCP subscription also does not subscribe a client to every in-process event.

The scheduler is an event-bus consumer: schedule firings reach its registered job dispatcher. Its lifecycle publication depends on the configured policy. Applications should trace the actual producer and consumer connection for an event they need.

## Observation and retention are different decisions

A transient event map need not become a stored entity. A stored runtime event need not be a first-class narrative memory. A workflow history row is retained because it explains a process transition. These choices serve different purposes and have different volume and recovery implications.

Runtime event families are not all subclasses of `:mm/Memory`. Class hierarchy, retention, publication, and authorization each carry a separate part of the contract. Using one broad memory count as a measure of every operational event would blur those distinctions.

## What a durable consumer must establish

An in-memory subscription has no automatic replay cursor or exactly-once guarantee. A consumer that must survive downtime needs an explicitly supported durable source, checkpoint, replay and duplicate-handling procedure. Flow operators, per-class buffer declarations, and supervision metadata do not establish such a runtime merely by existing in a design or schema.

This boundary keeps the current bus useful and understandable. Use it for explicit in-process observation; use [reactive projection](reactive-substrate.md) for maintained representations and [workflow history](workflow-substrate.md) for lifecycle evidence. See [Subscribing to events](../guides/subscribing-to-events.md) for an executable example and [`sandbar.event`](../../src/sandbar/event.clj) for the dispatch contract.
