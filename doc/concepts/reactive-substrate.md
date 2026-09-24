# Reactive projection: keep derived representations connected to accepted state

**A successful database write and an up-to-date derived representation are different milestones.** Sandbar's reactive layer connects accepted mutations to downstream work without requiring every client to rewrite files and notify subscribers itself.

The active projection path begins at the `dt/*` mutation boundary. Eligible changes call `sandbar.reactive/on-entity-changed!`, whose registered callbacks include the projection queue. The queue coalesces work by entity and dispatches it to registered sinks. File projection and notifications are downstream effects, not part of the original Datomic transaction.

## One entity can change while its projection is running

Coalescing retains the latest pending state for an entity. A newer change must remain pending when an older dispatch completes; otherwise completion of the old work could incorrectly acknowledge the new work. The queue tracks generations and ownership to distinguish these cases.

Dispatch for a given entity is serialized across worker generations and explicit drains. Shutdown retains ownership of workers that have not exited. A join timeout must be reported as incomplete shutdown, rather than permitting another drainer to overwrite newer output with old state.

The queue's buffer setting is a soft pressure threshold for dirty entities. It is not a license to drop unprojected changes. Resulting memory use and catch-up time should be measured under the deployment's write and sink workload.

## Eligibility and policy

Projection can be disabled per operation, by a dynamic binding, or by class policy. The checks exist because schema bootstrap, import, inline data, and normal authored records have different projection needs. A skipped projection should be distinguishable from one that is pending or failed.

Class memorial policy helps determine representation, but it does not by itself prove that a destination may receive a record. [Project boundaries](../firewall-and-projects.md) and [projection/export](projection.md) supply the disclosure and identity requirements.

## Observe the pipeline, then verify the output

The `sandbar_reactive_health` MCP tool reports a snapshot including running state, pending work, sink count, oldest pending age, drain counts, and failures. Useful operational questions include whether sinks are registered, whether pending age is increasing, and whether shutdown still owns a retiring worker.

A zero dirty count alone does not establish correct file contents or end-to-end durability. Acceptance should also read the intended output and exercise sink failure, a new write during dispatch, stop/start overlap, and recovery after interruption. Different derived systems may have separate queues: BM25F cache refresh is not automatically proven current by projection health.

## The boundary callers can depend on

Treat database acceptance, queued projection, successful sink completion, and client observation as separate states. A client that needs the projected result immediately must use an appropriate completion/read-back procedure. A notification tells a consumer to inspect a changed resource; it is not a full backup or a durable event replay log.

This design makes asynchronous work explicit while keeping the database authoritative for accepted state. See [operations](../operations.md) for diagnosis and [events](event-substrate.md) for the related subscription surfaces. Source: [`sandbar.reactive`](../../src/sandbar/reactive.clj), [queue](../../src/sandbar/reactive/queue.clj), and [sinks](../../src/sandbar/reactive/sinks.clj).
