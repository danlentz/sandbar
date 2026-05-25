# Reactive-Projection Substrate

> Every Sandbar entity mutation is a fact about state that someone — or several someones — wants to react to.  The reactive-projection substrate is the in-process pipeline that delivers those facts: a hook at the `dt/*` boundary, a three-layer opt-out, a callback registry, a bounded sliding-buffer queue with per-entity coalescing, and a set of sinks (codec-emit, atomic filesystem write, SSE notification, future event-bus publication).  It is the wiring that makes the database *visibly* alive without coupling the substrate to any single downstream concern.

## Thesis

A storage substrate that announces its mutations is qualitatively different from one that does not.  The Datomic transactor knows what changed; that information has typically been left to die at the transaction boundary, accessible only to callers who happened to be holding the connection.  Sandbar's posture is the inverse: every mutation through the `dt/*` substrate primitives fires a reactive-projection hook, and downstream consumers — filesystem mirrors, MCP-subscribed clients, audit substrates, the impending event bus — register interest at the level of the substrate, not the level of any one tool.

The pipeline composes from four pieces that each pull their weight: a per-class skip-list (so the substrate's own bookkeeping doesn't echo back through it), a per-call kwarg opt-out (so a single mutation can explicitly waive projection — bulk imports, fixtures), a dynamic-binding opt-out (so a scoped batch can defer projection until the batch settles), and a top-level opt-out predicate (the default-on switch the operator controls).  These four resolve most-specific-wins.  Past the opt-out check, dispatch is a callback registry — sinks register as functions of `[entity-eid post-tx-slots]`, the dispatcher invokes them in registration order, exceptions in one sink do not propagate to the substrate or to its peers.

The queue between dispatch and the sinks is bounded — `core.async/sliding-buffer 4096` — and coalescing.  Repeated mutations of the same entity in a tight window collapse to one effective queue slot; the worker, when it drains, sees the latest state, not the intermediate ones.  Under sustained load the substrate drops the oldest queued task; last-state-wins is semantically correct for projection (no consumer wants stale intermediate snapshots of a still-mutating entity).

## Where the hook attaches

The hook fires at the `dt/*` primitive layer — `dt/make`, `dt/make-all`, `dt/update-entity!` — *not* at the MCP verb layer above it.  This placement was deliberate: MCP is one consumer of `dt/*` among many (REPL sessions, internal bootstrap, future direct-library users), and a hook one layer up would miss the others.  By attaching at the substrate primitive itself, every path that mutates entities is announced uniformly.

```clojure
(ns sandbar.reactive
  ...)

(defn on-entity-changed!
  "Reactive-projection hook fired by dt/* substrate primitives after a
   successful mutation."
  [class-ident entity per-call-project?]
  (if (project? class-ident per-call-project?)
    (dispatch! (:db/id entity) entity)
    nil))
```

Failure mode is principled: the `dt/*` mutation has already committed by the time the hook fires.  Any exception in the dispatch path is logged and swallowed — the substrate state remains coherent regardless of the reactive-projection pipeline's health.  The substrate is the source of truth; the projections are derived.

## The three-layer opt-out

Three priorities; most-specific wins:

| Layer | Mechanism | When to use |
|---|---|---|
| 1 — per-call kwarg | `:project?` passed to `dt/make` / `dt/update-entity!` | Single-mutation override at the callsite |
| 2 — dynamic binding | `(binding [sandbar.reactive/*reactive-projection-enabled?* false] ...)` | Scoped bulk-bypass for a nested batch |
| 3 — class skip-list | `+class-skip-list+` set in `sandbar.reactive` | Meta-substrate classes whose mutations are not user-facing corpus state |

Layer 1 is the kwarg the caller passes explicitly when it knows the answer for this one mutation.  Layer 2 is the binding a bulk operation establishes around a tightly-grouped batch — `project.import` uses this so reading from the filesystem doesn't echo back to it as a write.  Layer 3 is the default-on/default-off knob keyed on the entity's `:dt/type` — schema bootstrap classes (`:dt/Class`, `:dt/Property`) and workflow substrate classes (`:mm/Workflow`, `:workflow/Process`) are skipped because their mutations are substrate-mechanical, not user-narrative.

The class skip-list is hardcoded today but is designed to migrate to a data-driven slot (`:dt/reactive-projection?` on `:dt/Class`) when the skip set grows.  The current shape is the MVP; the substrate slot is the evolution path.

## Callback registry — Stage A.5 dispatch point

The registry is an atom of functions.  Each function takes `[entity-eid post-tx-slots]` and does whatever work it does — write a file, push an SSE frame, index for full-text search, queue an audit event.

```clojure
(register-callback! my-fn)         ; add a callback
(unregister-callback! my-fn)       ; remove (identity-equality)
(clear-callbacks!)                 ; test-only reset
(callback-count)                   ; diagnostic
```

The dispatcher invokes callbacks sequentially in registration order, wraps each in `try/catch`, and logs failures at `:warn` with `:REACTIVE/callback-failed`.  A failed callback does not block its peers and does not propagate to the substrate worker.  Handler isolation is intrinsic to the pipeline shape, not a per-sink concern.

A worked example — registering an audit-log callback that records every projection-eligible mutation to an in-memory ring buffer:

```clojure
(require '[sandbar.reactive :as reactive])

(def audit-ring (atom clojure.lang.PersistentQueue/EMPTY))

(defn audit-callback [eid post-tx-slots]
  (swap! audit-ring
         (fn [q]
           (let [q' (conj q {:eid   eid
                             :ident (:db/ident post-tx-slots)
                             :class (:dt/type post-tx-slots)
                             :inst  (java.time.Instant/now)})]
             (if (> (count q') 1000) (pop q') q')))))

(reactive/register-callback! audit-callback)
```

After registration, every mutation through `dt/*` that passes the opt-out checks fires `audit-callback` with the post-tx entity state.  The ring buffer accumulates the last 1000 mutations.  Unregistering is symmetric: `(reactive/unregister-callback! audit-callback)`.

## Bounded queue, per-entity coalescing, sliding-buffer drop

The queue sits between the dispatch point and the sinks.  Producer side: every fired callback that registered the queue's enqueue function dumps a task to a `core.async` channel backed by a sliding-buffer of 4096 entries.  Consumer side: a single worker `go-loop` drains the channel, dispatches each task to the registered sinks.

Per-entity coalescing rides on an atom of `{eid → first-enqueue-instant}` — the dirty set.  When an entity is enqueued and is already dirty, the task is a coalesce: the counter increments, the channel is not put-to again.  The worker, when it reaches the entity, will pick up the *latest* state (the dirty-set entry just marks "this entity has pending projection work"; the post-tx slots are re-resolved at drain time if necessary).

Sliding-buffer drop semantics: when the channel is full, the oldest queued task is silently dropped.  This is intentional.  Reactive projection is last-state-wins — a dropped older task is one whose successor is already enqueued or about to be.  The substrate prefers freshness over completeness; the underlying database is the source of truth, and a missed projection is recoverable by reading from it.

Health metrics surface through the `sandbar.reactive.health` MCP verb:

```clojure
{:worker-running?       true
 :buffer-size           4096
 :dirty-entity-count    7
 :oldest-pending-age-ms 142
 :enqueue-total         1853
 :drain-total           1844
 :coalesce-total        211
 :sink-error-total      0
 :registered-sinks      2
 :saturated?            false}
```

When the oldest pending entity's age exceeds `+saturation-warn-threshold-ms+` (5000ms by default) the substrate emits `:REACTIVE/queue-sustained-saturation` at `:warn`.  Operators see queue pressure as a first-class signal rather than as a silent backlog.

## Sinks

Sinks are functions of `[entity-eid post-tx-slots]` registered with `register-sink!`.  Two ship today; more compose without ceremony.

### `fs-projection-sink`

For classes whose effective `:dt/memorial-policy` resolves to `:first-class` and whose entities carry a `:mm.memory/rel-path` slot value:

1. Resolves the entity's class native codec.
2. Calls `sandbar.projection/realize-and-emit-entity` (which handles section-tree walking and codec mediation).
3. Atomically writes the emitted markdown to `<corpus-root>/memory/<rel-path>` (write-to-temp; rename).

The atomic-write discipline matters: readers (other agents tailing the filesystem, editors with files open, indexers) see either the previous content or the new content, never a partial write.  This is the same hygiene `clj-xref`'s EDN writer uses (`java.nio.file.Files/move` with `ATOMIC_MOVE` + `REPLACE_EXISTING`); the reactive-projection sink inherits the discipline.

### `sse-emit-sink`

Invokes `sandbar.mcp.resources/entity-updated!`, which routes a JSON-RPC notification to MCP subscribers per the resource-update protocol.  Subscriber-id-targeted when bound to a specific subscriber; broadcast for `::broadcast` subscribers.  No-op when no subscriber exists for the entity's URI.

### Future sinks

The pipeline is open.  Sinks already on the roadmap include codec.emit-only (no filesystem write — e.g., publishing to a remote object store), full-text-search re-indexer (push entity into the BM25F index), event-bus publisher (translate the per-entity callback into a typed `:mm/Event` instance for the event substrate; see [`event-substrate.md`](event-substrate.md)).  Each is a single function added to the sink registry.

## What this enables

The reactive-projection substrate is the foundation under several capabilities that would otherwise require bespoke per-feature plumbing:

- **Filesystem mirror that stays current** — the corpus's `memory/` tree reflects database state on a few-millisecond delay because every mutation drives a projection.  Editors viewing files see fresh content; `git status` reports meaningful changes; external tools (`grep`, `find`, `fzf`) operate on near-current data.
- **MCP resource subscriptions** — clients subscribed to an entity URI receive `notifications/resources/updated` frames within the projection-latency window.  This is how the Claude Desktop client and other MCP-aware tools observe corpus changes without polling.
- **Audit substrates** — the ring-buffer example above generalizes; any consumer that wants to observe mutations registers a callback and reads from the registry it builds.  No `dt/*` consumer needs to know about it.
- **Bulk-import discipline** — `project.import` binds `*reactive-projection-enabled?* false` around its transaction batch and explicitly avoids the echo loop.  The opt-out mechanism is what makes bidirectional projection (FS → DB and DB → FS) coherent.

## Failure semantics and the source-of-truth posture

Two failure modes deserve attention.

**Dropped tasks under sustained saturation.**  The sliding-buffer's drop is silent at the queue level (the producer's `put!` always succeeds) but observable through health metrics — `dirty-entity-count` and `oldest-pending-age-ms` both reflect backlog.  Operators should treat sustained saturation as a sign that the sinks are too slow for the mutation rate; the fix is faster sinks or coarser-grained mutations, not a larger buffer.

**Sink exceptions.**  Wrapped in `try/catch` at dispatch time; one sink's failure does not block its peers, and the substrate worker continues draining.  The failed sink's `:REACTIVE/sink-failed` log entry carries the exception; the metric counter `:sink-error-total` increments.  The dirty-entity entry is cleared on drain regardless of sink outcome — future mutations of the same entity will re-enqueue and re-attempt projection.  Reactive projection is *recovering* rather than *transactional*; the database is the source of truth that lets it recover.

## Relationship to the impending event substrate

The reactive-projection substrate is in production at `0.2.0`.  The next architectural step, ratified 2026-05-23, is the event substrate (see [`event-substrate.md`](event-substrate.md)) — phases 1–8 are proposed but not yet implemented.  The event substrate replaces the `dt/*` callsite hook with a `d/tx-report-queue` subscriber wrapped behind `sandbar.reactive.tx-source`, broadens dispatch from per-entity callbacks to typed-class subscriptions over Manifold streams, and unifies the Telemere bridge under one fan-out.  The reactive-projection pipeline as documented here continues working under that architecture — the `fs-projection-sink` and `sse-emit-sink` become subscribers on the unified event bus rather than callbacks on a per-mutation hook.  The migration is parallel-run by design: existing consumers keep working through the transition.

## References

**Datomic's tx-log as event substrate**

- Hickey, R. & Halloway, S. *Datomic's transaction log and `tx-report-queue`* — the substrate-native mechanism this pipeline currently shadows at the application layer and will subscribe to directly under the event-substrate architecture.

**Atomic file write**

- *POSIX `rename(2)` atomicity guarantee on same-filesystem moves* — the underlying primitive the `atomic-write!` helper relies on; the same discipline `clj-xref`'s EDN writer uses for `.edn` updates.

**Sliding-buffer drop semantics**

- *`clojure.core.async/sliding-buffer`* — drop-oldest-on-full is the policy chosen for last-state-wins reactive projection.  Contrasted with `dropping-buffer` (drop-newest) which would be wrong here: a newer state should always supersede an older one.

**Bounded queue + handler isolation as anti-pattern lessons**

- Armstrong, J. (2013). *Programming Erlang* — OTP's `gen_event` is the cautionary tale that motivates the *handler isolation from the start* discipline (each sink wrapped in `try/catch`; one slow handler does not block peers).

## See also

- [`event-substrate.md`](event-substrate.md) — the next architectural step; subsumes this pipeline behind a typed event bus
- [`projection.md`](projection.md) — the `project-graph` / `ingest-graph` bidirectional projection primitive that `fs-projection-sink` composes with
- [`codec-layer.md`](codec-layer.md) — per-entity wire-format translation delegated by `fs-projection-sink`
- [`mcp-protocol.md`](mcp-protocol.md) — the SSE resource-update notification protocol `sse-emit-sink` drives
