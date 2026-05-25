# Using `sandbar.logging`

> How to use Sandbar's single public observability API — six macros (`info` / `warn` / `error` / `debug` / `trace` / `profile`), the memorial-flag positional, and the human-string shorthand.  The vendor surface (Telemere / Tufte) is internal engine; consumers reach for `sandbar.logging` exclusively.  Companion to the ergonomics-first ADR at `memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md`.

## The six macros

```clojure
(require '[sandbar.logging :as sb-log])

(sb-log/info    ::event-id  data  [memorial-flag])
(sb-log/warn    ::event-id  data  [memorial-flag])
(sb-log/error   ::event-id  ex    data  [memorial-flag])
(sb-log/debug   ::event-id  data  [memorial-flag])
(sb-log/trace   ::event-id  data  [memorial-flag])
(sb-log/profile :span-id    body)
```

That's the whole public surface.  No `sb-log/audit`, no `sb-log/memorialize`, no per-vendor convenience macros.  One callsite layer that maps to Telemere-signal-shaped payloads under the hood.

## Structured form (preferred)

```clojure
(sb-log/info ::session-started)
;; bare marker — useful for "this code path executed" probes

(sb-log/info ::session-started {:session-id 42 :user-id 17})
;; with structured data

(sb-log/info ::session-started "session 42 opened" {:session-id 42})
;; with message + data

(sb-log/error ::tx-failed ex)
;; typed error — the throwable rides in the second slot

(sb-log/error ::tx-failed ex {:tx-id 17592186})
;; error + data

(sb-log/profile :search-hot-path
  (search/run query))
;; Tufte profiling span; returns the body's value
```

Event-ids are namespaced keywords — `::session-started` resolves to `:my.app.events/session-started` per Clojure's standard reader.  This is the stable handle that downstream filters, dashboards, and post-hoc analyses key on.

## Human-string shorthand

When inventing a stable `::id` would be friction (one-off debug; opportunistic migration from `(log/info "...")`), the first-arg-string form is allowed:

```clojure
(sb-log/info "Datomic transactor reconnected")
(sb-log/info "User logged in" {:user-id 42})
```

The macro auto-derives a synthetic stable event-id from `*ns* + line + hash(message)` — e.g., `:my.app.handlers/line-87-abc123`.  Anonymous-at-callsite events stay rankable and facetable post-hoc; the substrate compensates for the callsite ergonomics.

When the same shorthand callsite recurs (you see `line-87-abc123` show up in dashboards), promoting it to a real `::id` is a one-line refactor.  No instrumentation churn at the consumer; the substrate keeps the signal coherent across the transition.

## The memorial flag

The optional final positional keyword on `info` / `warn` / `error` / `debug` / `trace` (not on `profile`) flags the signal for the memorial-projection handler:

```clojure
(sb-log/info ::workflow-transition {:from :pending :to :active})              ; transient
(sb-log/info ::workflow-transition {:from :pending :to :active} :db-only)     ; → :mm/Log audit
(sb-log/info ::session-handoff     {:summary "..."}             :first-class) ; → memory/logs/<>.md
(sb-log/info ::tag-stamped         {:tag :foo}                  :inline)      ; → host frontmatter
```

| Flag           | What happens                                                                |
|----------------|-----------------------------------------------------------------------------|
| (absent)       | **Transient** — handlers fire (file, stdout, etc.); no DB persistence.       |
| `:db-only`     | **Durable `:mm/Log`** entity in Datomic; no FS projection.                   |
| `:first-class` | Durable **+ FS-projected** to `memory/logs/<>.md` as a `:mm/Log` memorial.  |
| `:inline`      | Embedded in the **host memorial's frontmatter** (no separate memorial).      |

The flag is META about the signal — it stays positional and separate from the data payload, where the data IS the signal.  Spot-readable at callsites; symmetric with idiomatic Clojure trailing-kw patterns.

### When to use which

- **(absent)** — operational chatter that file handlers + dashboards consume; nothing the corpus needs to remember.  Default.
- **`:db-only`** — the event MATTERS as a fact but doesn't need a markdown narrative.  Audit trails, workflow-transition logs, validation outcomes.  Searchable via the substrate, not the filesystem.
- **`:first-class`** — the event is a NARRATIVE that future sessions should be able to read.  Session handoffs, design-decision moments, milestone observations.  Lands as `memory/logs/<>.md` per the Stage D projection.
- **`:inline`** — the signal annotates an existing memorial.  Tag-stamping, cross-cut metadata; no new entity warranted.

## When to use `audit` vs `memorialize` (shape-sugar)

`sb-log/audit` and `sb-log/memorialize` ARE NOT macros in the 0.2.0 surface.  The ADR considered them and deferred:

> Alternative sugar-macro forms `(sb-log/audit ::id data)` + `(sb-log/memorialize ::id data)` can be added later AS additive convenience if a real pattern of repetition emerges — not in MVP.

The reasoning: an extra two macros for what's already expressed by the trailing keyword is surface inflation.  If you see yourself repeating `(sb-log/info ::id data :db-only)` over and over, name a project-local helper (one wrapper fn in your namespace) and move on.  If a pattern of repetition emerges across consumers, file an issue and `audit` / `memorialize` will land as additive sugar.

Until then: `:db-only` IS the audit form; `:first-class` IS the memorialize form.  The trailing keyword is the shape-sugar.

## Error handling

The 2-arg slot on `sb-log/error` is reserved for a `Throwable` (unless it's a literal string or memorial-flag):

```clojure
(try
  (datomic.api/transact conn tx-data)
  (catch Exception ex
    (sb-log/error ::tx-failed ex {:tx-data tx-data})
    (throw ex)))
```

The macro routes through Telemere's typed `:error` kind so error-aware handlers can distinguish from `:log` kind.  Stack traces are captured automatically.

## Profile spans

```clojure
(sb-log/profile :search-hot-path
  (let [hits (search/run query)
        ranked (ranker/rerank hits)]
    (take 10 ranked)))
```

`profile` wraps the body in a Tufte span keyed by `:search-hot-path`, returning the body's value.  Aggregation across spans (median, p95, count) is the Tufte handler's job; the consumer just names the span at the callsite.

There is no memorial flag on `profile` — profiling spans are sampling artifacts; if you want a durable artifact, emit a separate `:db-only` log signal carrying the timing data.

## What the structured payload looks like

Every signal — bare-marker, ergonomic-shorthand, or fully-structured — produces the same Telemere-signal-shape internally:

```clojure
{:level    :info
 :id       :my.app.events/session-started   ; literal or synthetic
 :msg      "session 42 opened"              ; optional
 :data     {:session-id 42 :user-id 17
            :memorial   :db-only}           ; flag lifted into data when present
 :ns       'my.app.handlers
 :line     87
 :inst     #inst "2026-05-23T15:00:00Z"}
```

The file handler emits structured records carrying every field; even the human-shorthand callsite gets `{:ns :line :id :msg :data}` — the substrate compensates for callsite minimalism per the ergonomics ADR.

## Initialization

```clojure
(require '[sandbar.logging.init :as log-init])

(log-init/start!)
```

`sandbar.core/start` (the system-level startup) calls this BEFORE any other startup work, so anything that logs during boot is captured.  In test fixtures or repl setup where you want logging without the full system, call `start!` directly.

Handler configuration lives in `config/config.edn` under `:logging` — file handler path, log-rotation strategy, structured-event format.  See `sandbar.logging.config` for the full schema.

## A worked example — instrumenting a small handler

```clojure
(ns my.app.booking
  (:require [sandbar.logging :as sb-log]
            [sandbar.db.datatype :as dt]))

(defn create-booking [req]
  (sb-log/info ::create-booking-attempt {:request-id (:req-id req)})
  (try
    (let [b (sb-log/profile :booking-validate
              (dt/make :event/Booking (:body req)))]
      (sb-log/info ::booking-created
                   {:booking-id (:db/id b)
                    :owner      (get-in b [:event.booking/owner :db/ident])}
                   :db-only)                                       ; promote to :mm/Log
      {:status 201 :body b})
    (catch Exception ex
      (sb-log/error ::booking-failed ex {:request-id (:req-id req)} :db-only)
      {:status 422 :body {:error (.getMessage ex)}})))
```

Three signals per request: the attempt (transient — operator chatter), the durable `:booking-created` audit entry (`:db-only`), and on failure a durable `:booking-failed` audit entry (`:db-only`).  The profile span gives you booking-creation latency in your dashboards without any extra wiring.

## The cross-cutting posture

The shape of `sandbar.logging` instantiates a broader posture for ALL Sandbar substrate APIs:

> **Sandbar substrate APIs prioritize CONSUMER ERGONOMICS at the cost of more elaborate internal structure.  The substrate absorbs complexity so consumers don't have to.**

Examples:

- `sandbar.entity-ref` — one `resolve` fn accepts ident / eid / entity-map / kw-vector; consumer doesn't pick the dispatch.
- `dt/effective-memorial-policy-of` — walks ancestry transparently.
- `sandbar.reactive/register-callback!` — one fn; substrate handles queue + worker + back-pressure + opt-out.
- The ~60-verb MCP tool surface — simple args + rich projections; the Datalog / pull-syntax / projection-shape machinery stays internal.

`sandbar.logging` is the next instance.  Future arcs inherit this posture.

## See also

- `memory/decisions/observability_api_ergonomics_first_human_string_shorthand_allowed_internal_design_compensates_with_structure_2026_05_23.md` — the ratified callsite shape (read this for the why)
- [`subscribing-to-events.md`](subscribing-to-events.md) — the event substrate that signals flagged `:first-class` flow through (in-design)
- [`doc/concepts/reactive-substrate.md`](../concepts/reactive-substrate.md) — the callsite-hook layer that the memorial-projection-handler lives on
- [`writing-a-clojure-client.md`](writing-a-clojure-client.md) — embedding `sandbar.logging` in a Clojure consumer
- [`authoring-shapes.md`](authoring-shapes.md) — `:mm/Shape` validation outcomes are themselves loggable as `:db-only` audit entries
