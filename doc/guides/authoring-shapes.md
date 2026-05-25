# Authoring Shapes

> How to author `:mm/Shape` invariants over your domain classes and validate entities against them.  Shapes are first-class memorials — searchable, version-controlled, FS-projected — and the walker is a family of `:mm/Fn` instances composed into a conformance report.  This guide is the practical companion to [`doc/concepts/shape-validation.md`](../concepts/shape-validation.md) (theoretical thesis) and the implementation arc at `memory/plans/sandbar_shacl_deeply_incorporated_capstone_activation_arc_2026_05_23.md`.

## What a shape is

A `:mm/Shape` is a memorial entity that declares **invariants** over a target class — which properties must be present, what cardinality each may carry, what regex pattern (or datatype) values must satisfy, whether the entity is closed to extra slots, and which custom validator function (if any) supplies the rest.  Each constraint type maps directly to a SHACL primitive:

| Sandbar slot                                 | SHACL analog                              |
|----------------------------------------------|-------------------------------------------|
| `:mm.shape/applies-to`                       | `sh:targetClass`                          |
| `:mm.shape/required-property`                | `sh:property` + `sh:minCount 1`           |
| `:mm.shape/cardinality-constraints`          | `sh:minCount` / `sh:maxCount`             |
| `:mm.shape/pattern-constraints`              | `sh:pattern` / `sh:flags`                 |
| `:mm.shape/datatype-constraints`             | `sh:datatype`                             |
| `:mm.shape/closed?`                          | `sh:closed`                               |
| `:mm.shape/severity`                         | `sh:Violation` / `sh:Warning` / `sh:Info` |
| `:mm.shape/validator-fn`                     | `sh:SPARQLFunction` (sandbar `:mm/Fn` ref)|

Shapes compose `:mm/Fn` instances by **reference, not subtype** — the SHACL-AF separation of `sh:NodeShape` from `sh:SPARQLFunction` is the precedent.

## A first shape, in markdown

Shapes are codec-routed through `:codec/markdown` like any other `:mm/*` memorial.  Author one at `memory/shapes/event-booking-required.md`:

```markdown
---
name: Event Booking Required Slots
type: shape
description: Every :event/Booking must carry a title, start instant, end instant, and owner.
mm.shape/shape-id: :event-booking/required
mm.shape/applies-to: :event/Booking
mm.shape/required-property:
  - :event.booking/title
  - :event.booking/starts-at
  - :event.booking/ends-at
  - :event.booking/owner
mm.shape/severity: :violation
---

## Context

The booking entity is the unit of work for the scheduling subsystem.  Without all four
required slots the downstream calendar projection has nothing to render.

## See also

- [`doc/concepts/shape-validation.md`](../doc/concepts/shape-validation.md)
- [`doc/guides/authoring-shapes.md`](../doc/guides/authoring-shapes.md)
```

Project the file into the substrate:

```bash
mem project import memory/shapes/event-booking-required.md
```

Or via MCP — `sandbar.shape.create` with `:format "markdown"`:

```json
{
  "name": "sandbar.shape.create",
  "arguments": {
    "format": "markdown",
    "source": "---\nname: ...\n---\n..."
  }
}
```

## A first shape, in EDN

For inline construction (REPL, fixture, programmatic seeding):

```clojure
(require '[sandbar.db.datatype :as dt])

(dt/make :mm/Shape
  {:mm.shape/shape-id          :event-booking/required
   :mm.shape/applies-to        :event/Booking
   :mm.shape/description       "Every booking carries title, starts-at, ends-at, owner"
   :mm.shape/required-property [:event.booking/title
                                :event.booking/starts-at
                                :event.booking/ends-at
                                :event.booking/owner]
   :mm.shape/severity          :violation})
```

`dt/make` validates the shape itself (the metacircular check: `:mm/Shape` has a shape-of-shapes) and transacts.

## Cardinality + pattern + datatype constraints

These three slots take **sub-entities** — first-class instances of `:mm.shape/CardinalityConstraint`, `:mm.shape/PatternConstraint`, and `:mm.shape/DatatypeConstraint`.  Each names the target property plus the constraint specifics.

```clojure
(dt/make :mm/Shape
  {:mm.shape/shape-id          :user/profile
   :mm.shape/applies-to        :model/User
   :mm.shape/description       "User profile invariants"
   :mm.shape/required-property [:user/login]
   :mm.shape/cardinality-constraints
   [{:mm.shape.cardinality/property :user/login
     :mm.shape.cardinality/min      1
     :mm.shape.cardinality/max      1}
    {:mm.shape.cardinality/property :user/email
     :mm.shape.cardinality/min      0
     :mm.shape.cardinality/max      -1}]    ; -1 means unbounded
   :mm.shape/pattern-constraints
   [{:mm.shape.pattern/property :user/login
     :mm.shape.pattern/regex    "^[a-z][a-z0-9_-]{2,31}$"
     :mm.shape.pattern/flags    ""}]
   :mm.shape/datatype-constraints
   [{:mm.shape.datatype/property          :user/login
     :mm.shape.datatype/expected-datatype :db.type/string}]
   :mm.shape/severity :violation})
```

Pattern flag chars are the standard Java regex flags: `i` (case-insensitive), `m` (multiline), `s` (dotall), `x` (comments).

## Closed shapes (no extra slots permitted)

```clojure
(dt/make :mm/Shape
  {:mm.shape/shape-id   :ontology/strictly-closed
   :mm.shape/applies-to :ontology/Term
   :mm.shape/required-property [:ontology.term/preferred-label]
   :mm.shape/closed?    true
   :mm.shape/severity   :warning})
```

With `:mm.shape/closed? true`, instances may carry only the declared properties plus the substrate-permitted slots (`:db/id`, `:db/ident`, `:dt/type`, `:dt/context`, `:dt/label`).  Anything else surfaces as a `:closed` check failure.

## Custom validator functions

When the constraint requires logic beyond the declarative primitives, attach a `:mm.shape/validator-fn` pointing at a `:mm/Fn` memorial whose `:dt.fn/source-ns` + `:dt.fn/source-var` resolve to a peer-side function:

```clojure
;; First, register the validator fn as a :mm/Fn memorial
(require '[sandbar.db.fn :refer [defdbfn]])

(defdbfn check-booking-starts-before-ends [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Booking starts-at must precede ends-at."
   :dt.fn/version      "1.0.0"}
  (let [e   (datomic.api/entity db entity-eid)
        sa  (:event.booking/starts-at e)
        ea  (:event.booking/ends-at   e)]
    (if (and sa ea (.before sa ea))
      {:status :pass :check :starts-before-ends}
      {:status :fail :check :starts-before-ends
       :starts-at sa :ends-at ea
       :severity  :violation})))

;; Then attach it to a shape
(dt/make :mm/Shape
  {:mm.shape/shape-id     :event-booking/temporal-coherence
   :mm.shape/applies-to   :event/Booking
   :mm.shape/validator-fn (dt/find-by :db/ident
                                      :my.app.shape/check-booking-starts-before-ends)
   :mm.shape/severity     :violation})
```

The `defdbfn` macro dual-emits — the Clojure function is callable from peer-side code AND a `:mm/Fn` memorial entity is transacted so the shape can reference it by `:db/ident`.  See `memory/decisions/dt_fn_existing_state_reconciliation_dual_emit_defdbfn_legacy_migration_2026_05_23.md` for the dual-emit design.

## Validating one entity

```clojure
(require '[sandbar.shape :as shape]
         '[datomic.api :as d]
         '[sandbar.db :as db])

(def conn (db/conn))

;; Audit mode (default) — returns a vector of per-shape walk results
(shape/validate (d/db conn) [:db/ident :decisions/example])
;; =>
;; [{:status :pass :entity 17592186 :shape 17592345 :checks-passed 6}
;;  {:status :fail :entity 17592186 :shape 17592355
;;   :failures [{:status :fail :check :pattern
;;               :pattern-violations [{:property :decisions/title
;;                                     :regex "^[A-Z]"
;;                                     :non-matching-values ["lowercase title"]}]
;;               :severity :violation}]}]

;; Strict mode — throws ex-info on any :violation-severity failure
(shape/validate (d/db conn) [:db/ident :decisions/example] :strict)
```

## Validating one entity via MCP

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
      "name": "sandbar.shape.validate",
      "arguments": {"entity": ":decisions/example", "mode": "audit"}
    }
  }'
```

The response (unwrap `content[0].text`):

```json
{
  "entity": ":decisions/example",
  "mode": "audit",
  "result-count": 2,
  "results": [
    {"status": "pass", "entity": 17592186, "shape": 17592345, "checks-passed": 6},
    {"status": "fail", "entity": 17592186, "shape": 17592355,
     "failures": [{"status": "fail", "check": "pattern",
                   "pattern-violations": [...],
                   "severity": "violation"}]}
  ]
}
```

## Batch conformance for a whole class

```clojure
(require '[sandbar.shape :as shape]
         '[datomic.api :as d]
         '[sandbar.db :as db])

;; Walks every instance of :mm/Decision against every applicable :mm/Shape
(shape/conformance-report (d/db (db/conn)) :mm/Decision)
;; =>
;; {:class           :mm/Decision
;;  :instance-count  847
;;  :shape-count     3
;;  :total-checks    2541
;;  :passes          2533
;;  :failures        8
;;  :error-count     2
;;  :warning-count   6
;;  :failure-details [<walk-entity-result> ...]}
```

Or via MCP:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "sandbar.shape.conformance-report",
      "arguments": {"class": ":mm/Decision"}
    }
  }'
```

The conformance report is the canonical surface for class-wide invariant audits — substrate-quality, governance, drift detection.

## Validation modes

Three modes resolved at the call boundary:

| Mode        | Behavior                                                                |
|-------------|-------------------------------------------------------------------------|
| `:audit`    | Default. Returns all results; caller decides what to do.                |
| `:strict`   | Throws `ex-info` on any `:violation`-severity failure.                  |
| `:disabled` | Skips validation; returns `[]`.  Escape hatch; opt-in only.             |

`sandbar.entity.create` and `sandbar.entity.update` auto-invoke shape validation as a post-step after class-required-slot checks; the create-call's `:mode` argument controls how failures are handled (Stage E of the SHACL arc).

## Severity discipline

A SOUND `:violation`-severity shape never produces a false negative — if a check passes, the entity really conforms (per the Cousot-Cousot abstract-interpretation framing; the walker IS abstract interpretation).

A `:warning`-severity shape may admit false positives — useful for "tightening over time" workflows where you want to surface candidates without rejecting them at create-time.

`:info`-severity is fully informational; never rejects, never warns aggressively.

When in doubt, start at `:warning` and promote to `:violation` once the false-positive rate is zero against the existing corpus.

## A note on metacircularity

`:mm/Shape` itself has a shape — a `:mm/Shape` memorial called `:sandbar.shape/shape-shape` that targets `:mm/Shape` and declares its own invariants.  This is the metacircular closure: the substrate validates the validators by its own substrate primitives.  Run `(shape/conformance-report (d/db (db/conn)) :mm/Shape)` to confirm the shape-of-shapes passes.

## See also

- [`doc/concepts/shape-validation.md`](../concepts/shape-validation.md) — thesis + abstract-interpretation framing
- [`doc/concepts/metamodel.md`](../concepts/metamodel.md) — the `:dt/*` substrate the shape vocabulary builds on
- [`writing-a-clojure-client.md`](writing-a-clojure-client.md) — embedding the shape API in a Clojure consumer
- [`writing-an-mcp-client.md`](writing-an-mcp-client.md) — invoking shape verbs over MCP
- [`writing-a-rest-client.md`](writing-a-rest-client.md) — invoking shape endpoints over REST
- [`doc/api/dt-star.md`](../api/dt-star.md) — `sandbar.shape` namespace reference
