# Subscribing to Events

> **Status: in-design.**  The event substrate design is ratified (keystone ADR at `memory/decisions/sandbar_event_substrate_architecture_datomic_tx_report_queue_wrapped_behind_dt_star_manifold_transport_class_hierarchical_subscription_2026_05_23.md`); Phases 1 (`sandbar.reactive.tx-source`) and 2 (`sandbar.event/subscribe` + dispatch cache) are not yet built.  This guide describes the API shape so consumers can prepare; it will become a worked example once the substrate lands.

How to subscribe to substrate events — class-hierarchical subscription over a Manifold-backed, Datomic-`tx-report-queue`-sourced event bus.  The substrate translates committed transactions into typed `:mm/Event` (or `:dt/Event` subtype) instances and fans them out to subscribers by event class.

## What's landed today

Two pieces of the event substrate are in production at 0.2.0:

- **`sandbar.reactive`** — the callsite-hook layer at the `dt/*` boundary (per `doc/concepts/reactive-substrate.md`).  Callbacks register via `register-callback!` and receive `[entity-eid post-tx-slots]` after every projection-eligible mutation.  This is the Stage A.5 dispatch point — opt-out checks, callback registry, structured logging.
- **`memorial-projection-handler`** (Stage D, landed) — the Telemere bridge that promotes `(sb-log/info ... :first-class)` signals to durable `:mm/Log` memorials projected to `memory/logs/<>.md`.

Both work today.  What's *in-design* is the unified `sandbar.event/subscribe` API that subsumes the callsite-hook into a substrate-wide event bus.

## The in-design surface

### Subscribing by event class

```clojure
(require '[sandbar.event :as event])

;; Subscribe to every :event/HttpRequest (or subclass) — receives ServerEvent instances too
(def sub (event/subscribe :event/HttpRequest
            (fn [evt]
              (println "saw http request:" (:event.http/path evt)))))

;; Or subscribe to the runtime-event root for maximum fan-in
(event/subscribe :dt/Event
  (fn [evt] (audit-sink/enqueue! evt)))

;; Cancel
(event/unsubscribe sub)
```

Class-hierarchical dispatch via `dt/type-isa?` — subscribers on `:dt/Event` see events of every subclass; subscribers on `:event/HttpRequest` see only that class and its descendants.  The dispatch cache (keyed `{class-ident → subscriber-set}`) is invalidated on hierarchy mutation; the cost of one event is one set-lookup, not an ancestor-chain walk.

### Subscribing with composed flow operators

Akka-Streams-flavored operators compose between source and subscriber:

```clojure
(-> (event/subscribe :event/HttpRequest)        ; returns a Manifold source
    (event/filter   #(= 500 (:event.http/status %)))
    (event/throttle 100)                        ; max 100/sec
    (event/batch    10)                         ; emit batches of 10
    (event/consume  (fn [batch]
                      (alerts/burst-handler batch))))
```

The pipeline shape replaces per-sink ad-hoc logic.  Each operator is a Manifold stream transform; topology is walkable (composes with `sandbar.orient.*` introspection).

### Three event class hierarchies

| Hierarchy                              | Role                                     | Typical memorial policy |
|----------------------------------------|------------------------------------------|-------------------------|
| `:mm/Event` (descendant of `:mm/Meta`) | USER-FACING MEMORIAL FACE                | `:first-class` for narrative; `:db-only` for operational; `:inline` for embedded |
| `:dt/Event` → `:event/ServerEvent` → `:event/HttpRequest` | RUNTIME SUBSTRATE       | `:db-only` typically (high-volume) |
| `:workflow/History`                    | PROCESS-MANAGER TRANSITION LOG (distinct)| `:db-only` typically    |

The three serve distinct roles; no deprecations.  Workflow transitions ALSO emit `:mm.event/WorkflowTransition` instances on the event bus so reactive subscribers see them without conflating with the durable Process-Manager log.

### Catchup-on-disconnect

```clojure
;; Subscribe and catch up from the last basis-t we processed
(event/subscribe :event/HttpRequest
                 my-handler
                 {:catchup-from last-seen-basis-t})
```

The substrate uses `d/tx-range` against the Datomic peer to replay missed transactions as typed events — no event-store-management code on the consumer side.

### Buffer policy per event class

Two populations with opposite backpressure needs:

```clojure
;; Schema declaration (per-class :mm.event/buffer-policy)
{:db/ident                :event/HttpRequest
 :dt/type                 :dt/Class
 :dt/subclass-of          :event/ServerEvent
 :mm.event/buffer-policy  :sliding-4096}      ; drops oldest under load

{:db/ident                :mm.event/AuditEntry
 :dt/type                 :dt/Class
 :dt/subclass-of          :mm/Event
 :mm.event/buffer-policy  :block}             ; blocks publisher when full
```

For non-droppable + high-volume populations, a Chronicle-Queue escape hatch (via `zalky/cues` or `mpenet/tape`) is designed in as a future sink behind the in-process bus.  Deferred until empirically warranted.

### Handler isolation

Every subscriber runs in its own isolation domain — per-sink Manifold consume, per-sink core.async go-block, or per-sink thread for blocking work.  At minimum: an exception barrier around each invocation so a handler crash never propagates to the substrate worker or to its peers.

```clojure
;; Supervision via metamodel slots (per :mm/Sink memorial)
{:db/ident                  :my.app.sink/audit-trail
 :dt/type                   :mm/Sink
 :mm.sink/restart-strategy  :permanent     ; :permanent | :transient | :temporary
 :mm.sink/intensity         5              ; max restarts
 :mm.sink/period            60}            ; in seconds
```

OTP-flavored discipline; loud-failure detection via cumulative-restart cap.

## What this looks like before Phases 1+2 land

The `sandbar.reactive` callsite-hook IS in production and IS available — it just doesn't yet expose the unified class-hierarchical subscription surface above.

To register a downstream sink TODAY:

```clojure
(require '[sandbar.reactive :as reactive])

(defn my-audit-sink [entity-eid post-tx-slots]
  (audit/record! {:eid     entity-eid
                  :ident   (:db/ident post-tx-slots)
                  :class   (:dt/type  post-tx-slots)
                  :inst    (java.time.Instant/now)}))

(reactive/register-callback! my-audit-sink)
```

Every `dt/make` / `dt/make-all` / `dt/update-entity!` mutation that passes the three-layer opt-out fires the callback.  This is the substrate point that the event-substrate work will absorb; existing `register-callback!` consumers migrate via Phase 4 of the event-substrate arc (parallel-run; old + new both fire; verify equivalence; then deprecate the callsite-hook).

## Migration sequencing

Per the keystone ADR §D.8 — eight phases:

| Phase | Work                                                                 |
|-------|----------------------------------------------------------------------|
| 0     | Architecture ratified (THIS ADR, landed)                              |
| 1     | Build `sandbar.reactive.tx-source` (wraps `d/tx-report-queue`; emits typed `:mm/Event` instances) |
| 2     | Build `sandbar.event/subscribe` + dispatch-table cache (`dt/type-isa?` keyed) |
| 3     | Author `:mm.event/*` schema (HttpRequest existing; add EntityCreated, EntityUpdated, WorkflowTransition, Log, ...) |
| 4     | Migrate existing `register-callback!` consumers onto the new substrate (parallel-run) |
| 5     | Deprecate the `dt/make` callsite-hook once new substrate proves out  |
| 6     | Wire Telemere `memorial-projection-handler` into the unified substrate (resolves logging-arc Stage D) |
| 7     | Workflow.History composes with `:mm.event/WorkflowTransition`         |
| 8     | (Future) Scheduler emits `:mm.event/Scheduled` instances             |

Phase 6 already passes through the existing memorial-projection-handler in a Stage-D-shaped form (which IS landed) — the unification work moves it onto the same fan-out as the rest of the bus.

## Subscribing today via SSE (MCP)

The MCP transport already supports resource subscriptions over SSE — this remains the user-facing surface for AI clients until the unified event substrate lands:

```bash
curl -N -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Accept: text/event-stream" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "resources/subscribe",
    "params": {"uri": "mcp://sandbar/mm/Memory/decisions/foo"}
  }'
```

Per-resource (URI) subscription.  See [`writing-an-mcp-client.md`](writing-an-mcp-client.md) for the full SSE shape.

When the event substrate lands, the MCP SSE notifier becomes ONE subscriber on the unified bus — just like the FS-projection sink and the DB-dump indexer.  No special-case wiring.

## Compositions

- **With logging** — Telemere signals flagged `:memorial :first-class` flow through the unified bus as `:mm.event/Log` instances; subscribers (fs-projection, MCP SSE) receive them per their class subscription.  See [`using-logging.md`](using-logging.md).
- **With shape validation** — every `entity.create` that fails a `:violation`-severity shape can be observed via an `:mm.event/ValidationFailure` subscription (future Phase 3 schema work).
- **With workflows** — workflow transitions emit `:mm.event/WorkflowTransition` events alongside the durable `:workflow/History` log; observers subscribe without coupling to History.

## See also

- `memory/decisions/sandbar_event_substrate_architecture_*_2026_05_23.md` — the keystone ADR (read this first for the why)
- [`doc/concepts/reactive-substrate.md`](../concepts/reactive-substrate.md) — the in-production callsite-hook layer
- [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) — `:workflow/History` vs `:mm.event/WorkflowTransition`
- [`using-logging.md`](using-logging.md) — Telemere bridge (Stage D landed; D.5 unified bridge in-design)
- [`writing-an-mcp-client.md`](writing-an-mcp-client.md) — SSE resource subscription (available today)
- [`writing-a-clojure-client.md`](writing-a-clojure-client.md) — embedding `sandbar.reactive` callbacks today
