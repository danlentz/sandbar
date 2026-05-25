# Writing a Clojure Client

> How to embed Sandbar in your Clojure application — `dt/*` idioms for introspection, creation, validation, and query.  This guide assumes you're consuming Sandbar in-process (same JVM); for an HTTP-based Clojure client, see [`writing-a-rest-client.md`](writing-a-rest-client.md); for embedding Sandbar as a substrate inside your own application, see [`sandbar-as-substrate.md`](sandbar-as-substrate.md).

## Adding Sandbar to your project

Sandbar publishes to Clojars.  In your `project.clj`:

```clojure
:dependencies [[org.clojure/clojure "1.12.0"]
               [com.danlentz/sandbar "0.2.0"]]
```

Or `deps.edn`:

```clojure
{:deps {com.danlentz/sandbar {:mvn/version "0.2.0"}}}
```

You will also need a Datomic Peer (transactor + library).  Sandbar does not bundle Datomic; bring your own per its license terms.

## The principal namespaces

```clojure
(ns my.app
  (:require [sandbar.db.datatype :as dt]
            [sandbar.codec       :as codec]
            [sandbar.projection  :as projection]
            [sandbar.util.workflow :as wf]
            [sandbar.shape       :as shape]
            [sandbar.logging     :as sb-log]
            [sandbar.reactive    :as reactive]))
```

| Namespace                  | Role                                                                |
|----------------------------|---------------------------------------------------------------------|
| `sandbar.db.datatype`      | The `dt/*` API — introspection, creation, validation, queries.      |
| `sandbar.codec`            | Codec mediator — parse + emit between wire format and entity.       |
| `sandbar.projection`       | Bidirectional projection between DB state and a filesystem hierarchy.|
| `sandbar.util.workflow`    | Workflow + process operations (`:mm/Workflow` definitions; `:workflow/Process` runs). |
| `sandbar.shape`            | SHACL-style shape validation — `validate`, `walk-entity`, `conformance-report`. |
| `sandbar.logging`          | Six-macro observability API (`info` / `warn` / `error` / `debug` / `trace` / `profile`). |
| `sandbar.reactive`         | Reactive-projection substrate (hook + callback registry for downstream sinks). |

For a complete API surface see [`doc/api/dt-star.md`](../api/dt-star.md).

## Connecting

If you embed Sandbar in-process, the database connection is created by Sandbar's core during startup:

```clojure
(require '[sandbar.core :as sb])

(sb/go)                ; starts the HTTP + nREPL servers + schema load
;; or
(sb/init-no-server)   ; schema-only — no HTTP, no nREPL (suitable for batch jobs)
```

After this, `dt/*` operations work against the running system.  All `dt/*` functions implicitly use the connection registered by `sb/go`.

For tests, use the test fixture in `sandbar.test.fixture` — it sets up an in-memory database with schema loaded.

## Reading the metamodel

```clojure
;; All registered classes
(dt/all-classes)
;; => (:dt/Class :dt/Property :dt/Resource :mm/Memory ...)

;; All properties
(dt/all-properties)
;; => (:db/doc :db/ident :dt/domain ...)

;; Effective slots (inherited + direct)
(dt/slots-of :mm/Memory)
;; => #{:db/ident :dt/type :mm.memory/title ...}

;; Direct slots only (without ancestors)
(dt/direct-slots-of :mm/Memory)
;; => ({:db/ident :mm.memory/title ...} ...)

;; Required slots
(dt/required-slots-of :event/Booking)
;; => #{:event.booking/title :event.booking/starts-at ...}

;; Class ancestry
(dt/ancestors-of :event/Booking)
;; => (:dt/Resource)

;; Direct subclasses
(dt/direct-subclasses-of :event/Booking)
;; => (:event/RecurringBooking ...)

;; All subclasses (transitive)
(dt/subclasses-of :dt/Resource)
;; => every class
```

## Reading a property

```clojure
;; Where this property is defined
(dt/domain-of :event.booking/owner)
;; => :event/Booking

;; What value type the property holds
(dt/range-of :event.booking/owner)
;; => :model/User

;; Cardinality
(dt/cardinality-of :event.booking/owner)
;; => :db.cardinality/one

(dt/cardinality-many? :event.booking/tags)
;; => true

;; Is it required?
(dt/required? :event.booking/title)
;; => true
```

## Type predicates

```clojure
;; Is X an instance of Class?
(dt/instance-of? :event/Booking my-booking)
;; => true

;; Is Child a subclass of Parent?
(dt/subclass-of? :dt/Resource :event/Booking)
;; => true
```

## Creating entities

```clojure
;; Create with validation
(def b
  (dt/make :event/Booking
    {:event.booking/title     "Weekly Sync"
     :event.booking/starts-at #inst "2026-05-14T15:00:00Z"
     :event.booking/ends-at   #inst "2026-05-14T16:00:00Z"
     :event.booking/owner     (dt/find-by :user/login "alice")}))

;; Without validation (escape hatch — use sparingly)
(dt/make* :event/Booking {...})

;; With validation override
(dt/make :event/Booking {...} {:validate? false})
```

`dt/make` returns the entity map (including `:db/id`) after transacting.

### Codec-mediated creation

If the source-of-truth is a string in a codec format (markdown, JSON, ...):

```clojure
(dt/make :mm/Memory
  {:format "markdown"
   :source "---\nname: Foo\n---\n# Context\n..."})
```

The codec mediator routes through `:dt/native-codec` on the class.  See [`implementing-a-codec.md`](implementing-a-codec.md) for adding a new codec.

## Validation

```clojure
;; Validate an entity already in the DB
(dt/validate some-entity)
;; => nil (valid) or {:errors [{:slot :foo :error :missing-required ...}]}

(dt/valid? some-entity)
;; => true / false

;; Validate a candidate map (before transacting)
(dt/validate-data :event/Booking {:event.booking/title "Foo"})
;; => {:errors [...]}  (because required slots are missing)
```

Validation results are pure data; consumers can inspect, summarize, or render them.

## Queries

`dt/*` queries are projections of Datomic Datalog queries against typed entities.  For ad-hoc queries, drop to Datomic directly:

```clojure
(require '[datomic.api :as d])
(require '[sandbar.db :as db])

;; A typical query — bookings owned by alice, ordered by start
(d/q '[:find ?b ?starts
       :in $ ?login
       :where
       [?u :user/login ?login]
       [?b :event.booking/owner ?u]
       [?b :event.booking/starts-at ?starts]]
     (d/db (db/conn))
     "alice")
```

The convention: use `dt/*` for typed operations against the metamodel; drop to `d/q` for application-specific predicate queries.

## Working with workflows

```clojure
;; Start a process — :validation/Workflow is a :mm/Workflow definition
(def p
  (wf/start-process! :validation/Workflow
    {:target-class :event/Booking}))

;; Check status
(:workflow/current-state p)
;; => :running

;; Fire a transition
(wf/transition! p :workflow.transition/complete)

;; Cancel (if the workflow allows it at the current state)
(when (wf/can-cancel? p)
  (wf/cancel-process! p))
```

Workflow definitions are `:mm/Workflow` memorials (per the 2026-05-23 naming-convention ADR); running processes are `:workflow/Process` substrate-runtime entities.  For the full design, see [`doc/concepts/workflow-substrate.md`](../concepts/workflow-substrate.md) and [`designing-workflows.md`](designing-workflows.md).

## Shape validation

`sandbar.shape` provides SHACL-style validation against `:mm/Shape` memorials:

```clojure
(require '[sandbar.shape :as shape]
         '[datomic.api :as d]
         '[sandbar.db :as db])

(def db (d/db (db/conn)))

;; Validate one entity against all applicable shapes (audit mode — never throws)
(shape/validate db [:db/ident :decisions/example])
;; => [{:status :pass :entity 1234 :shape 5678 :checks-passed 6}]

;; Strict mode throws ex-info on any :violation-severity failure
(shape/validate db [:db/ident :decisions/example] :strict)
```

For authoring shape memorials and a guided walk through the validator types (required-property, cardinality, pattern, datatype, closed, validator-fn), see [`authoring-shapes.md`](authoring-shapes.md).

## Logging

`sandbar.logging` is Sandbar's single public observability API — six macros built over Telemere + Tufte:

```clojure
(require '[sandbar.logging :as sb-log])

(sb-log/info ::booking-created {:booking-id 42})
(sb-log/warn "queue depth high" {:depth 4096})        ; human-string shorthand
(sb-log/error ::tx-failed ex {:tx-id 17592186})       ; typed error
(sb-log/info ::session-handoff {:summary "..."} :first-class)  ; memorial-flag

(sb-log/profile :search-hot-path
  (do-the-search ...))
```

See [`using-logging.md`](using-logging.md) for the full surface, the memorial-flag positional, and the discipline.

## Projection operations

```clojure
(require '[sandbar.projection :as projection])

;; Project all entities to a filesystem hierarchy
(projection/project-graph (d/db (db/conn)) "/tmp/sandbar-export"
  {:classes #{:mm/Memory :mm/Decision}})

;; Ingest a hierarchy back into the DB
(projection/ingest-graph (db/conn) "/tmp/sandbar-export")
```

See [`doc/concepts/projection.md`](../concepts/projection.md) for the design and [`sandbar-as-substrate.md`](sandbar-as-substrate.md) for the embedding pattern.

## Testing

Sandbar provides a test fixture that creates a fresh in-memory database with schema loaded:

```clojure
(ns my.test
  (:require [clojure.test :refer :all]
            [sandbar.test.fixture :as fixture]
            [sandbar.db.datatype :as dt]))

(use-fixtures :each fixture/with-memdb)

(deftest booking-validation
  (testing "missing required slot is caught"
    (is (some? (:errors
                 (dt/validate-data :event/Booking
                   {:event.booking/title "Foo"}))))))
```

For tests that need a transactor (rare — most tests should use the memdb), see [`doc/development.md`](../development.md).

## Patterns

### Reflection-driven UI

Walk `dt/all-classes` and `dt/slots-of` to build a generic admin/CRUD interface that adapts to any class:

```clojure
(defn render-class-form [class-ident]
  (for [slot (dt/slots-of class-ident)
        :let [range (dt/range-of slot)]]
    (render-input-for-range slot range)))
```

This pattern works because the metamodel introspects itself; the UI reflects the model state.

### Idents as stable references

Always reference entities by `:db/ident` for cross-instance stability:

```clojure
;; Brittle — :db/id is instance-local
(my-config :booking-id 12345)

;; Stable — :db/ident survives database recreation
(my-config :booking-id :event/canonical-weekly-sync)
```

### Schema-on-startup

Register your application's schema with Sandbar's `:required-schema`:

```clojure
;; config/config.edn
{:required-schema [:meta :literal :ref :fn :any :workflow :mm :event]}
```

`:event` here refers to `schema/event.edn`.  Sandbar loads it at startup.

## See also

- [`quickstart.md`](quickstart.md) — getting Sandbar running locally
- [`zorp-tutorial.md`](zorp-tutorial.md) — worked example using `dt/*`
- [`defining-new-classes.md`](defining-new-classes.md) — adding your domain's schema
- [`designing-workflows.md`](designing-workflows.md) — workflow + process API
- [`authoring-shapes.md`](authoring-shapes.md) — declarative validation via `:mm/Shape`
- [`using-logging.md`](using-logging.md) — the `sandbar.logging` six-macro API
- [`subscribing-to-events.md`](subscribing-to-events.md) — event substrate API (in-design)
- [`sandbar-as-substrate.md`](sandbar-as-substrate.md) — embedding Sandbar in your own application
- [`doc/api/dt-star.md`](../api/dt-star.md) — full `dt/*` reference
