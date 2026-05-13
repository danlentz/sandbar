# Defining New Classes

> How to extend Sandbar's metamodel with new domain classes — `:dt/Property` declarations, `:dt/Class` definitions, slot composition, validation, and registration with `:required-schema`.  For the worked example, see [`zorp-tutorial.md`](zorp-tutorial.md); for the theoretical background see [`doc/concepts/metamodel.md`](../concepts/metamodel.md).

## When to add a class

Add a class when:

- You have a stable noun in your domain (`:order/Order`, `:inventory/Item`, `:event/Booking`).
- The noun has multiple properties that travel together.
- Instances should be discoverable, validatable, or queryable as a group.

Do **not** add a class for one-off computed values, ephemeral per-request shapes, or anything that maps better to an attribute on an existing class.

## The shape of a class definition

A class is an entity satisfying these constraints:

```clojure
{:db/ident       :your-ns/YourClass
 :dt/type        :dt/Class
 :dt/subclass-of :dt/Resource   ; or any other :dt/Class
 :dt/abstract?   false           ; optional — true forbids direct instantiation
 :dt/slots       [:your-ns/slot1 :your-ns/slot2 ...]
 :dt/context     "your-ns"       ; namespace label
 :dt/label       "Human-Readable Name"
 :db/doc         "Short description of the class."}
```

A property is also an entity:

```clojure
{:db/ident       :your-ns/slot1
 :db/valueType   :db.type/string
 :db/cardinality :db.cardinality/one
 :dt/type        :dt/Property
 :dt/domain      :your-ns/YourClass
 :dt/range       :db.type/string
 :dt/required?   true            ; optional — required slots must be present on instances
 :db/doc         "Documentation for the slot."}
```

## Walkthrough — an `:event/Booking` class

A worked example: model a calendar booking with required fields and validation.

### Step 1 — Plan the slots

```
:event/Booking
  ├── :event.booking/title       (string, required)
  ├── :event.booking/starts-at   (instant, required)
  ├── :event.booking/ends-at     (instant, required)
  ├── :event.booking/owner       (ref → :model/User, required)
  ├── :event.booking/location    (string, optional)
  └── :event.booking/description (string, optional)
```

### Step 2 — Write the EDN

Create `schema/event.edn`:

```clojure
[
 ;; Forward declaration (avoids order-of-load issues)
 [{:db/id #db/id[:db.part/user -1]
   :db/ident :event/Booking}]

 ;; Properties
 [{:db/ident :event.booking/title
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :event/Booking
   :dt/range :db.type/string
   :dt/required? true
   :db/doc "Short title for the booking"
   :db.install/_attribute :db.part/db}

  {:db/ident :event.booking/starts-at
   :db/valueType :db.type/instant
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :event/Booking
   :dt/range :db.type/instant
   :dt/required? true
   :db/doc "Booking start time (UTC)"
   :db.install/_attribute :db.part/db}

  {:db/ident :event.booking/ends-at
   :db/valueType :db.type/instant
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :event/Booking
   :dt/range :db.type/instant
   :dt/required? true
   :db/doc "Booking end time (UTC)"
   :db.install/_attribute :db.part/db}

  {:db/ident :event.booking/owner
   :db/valueType :db.type/ref
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :event/Booking
   :dt/range :model/User
   :dt/required? true
   :db/doc "The user who owns this booking"
   :db.install/_attribute :db.part/db}

  {:db/ident :event.booking/location
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :event/Booking
   :dt/range :db.type/string
   :db/doc "Optional location string"
   :db.install/_attribute :db.part/db}

  {:db/ident :event.booking/description
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :event/Booking
   :dt/range :db.type/string
   :db/doc "Optional free-form description"
   :db.install/_attribute :db.part/db}]

 ;; Class definition (after properties so :dt/slots can reference them)
 [{:db/ident :event/Booking
   :dt/type :dt/Class
   :dt/subclass-of :dt/Resource
   :dt/context "event"
   :dt/label "Booking"
   :db/doc "A calendar booking owned by a user"
   :dt/slots [:event.booking/title
              :event.booking/starts-at
              :event.booking/ends-at
              :event.booking/owner
              :event.booking/location
              :event.booking/description]}]
]
```

### Step 3 — Register the schema

In `config/config.edn`, add `:event` to `:required-schema`:

```clojure
{:datomic-uri "datomic:dev://localhost:4334/sandbar"
 :http-port 8080
 :required-schema [:meta :literal :ref :fn :any :workflow :mm :user :event]}
```

### Step 4 — Reload

In the REPL:

```clojure
(stop)
(go)
```

The schema loads as part of startup.  Verify:

```clojure
(dt/all-classes)
;; => (... :event/Booking ...)

(dt/slots-of :event/Booking)
;; => #{:event.booking/title :event.booking/starts-at ...}

(dt/required-slots-of :event/Booking)
;; => #{:event.booking/title :event.booking/starts-at
;;      :event.booking/ends-at :event.booking/owner}
```

### Step 5 — Create an instance

```clojure
(dt/make :event/Booking
  {:event.booking/title       "Weekly Sync"
   :event.booking/starts-at   #inst "2026-05-14T15:00:00.000Z"
   :event.booking/ends-at     #inst "2026-05-14T16:00:00.000Z"
   :event.booking/owner       (dt/find-by :user/login "alice")
   :event.booking/location    "Conference Room B"})
;; => entity; validation passed; transacted
```

Try omitting a required slot:

```clojure
(dt/make :event/Booking
  {:event.booking/title "Weekly Sync"})
;; throws — :missing-required for starts-at, ends-at, owner
```

### Step 6 — Query

The class is immediately surfaced through every projection:

```clojure
;; Clojure
(dt/all-instances-of :event/Booking)

;; REST
;; GET /api/store/classes/event/Booking/instances

;; MCP
;; tools/call sandbar.class.instances {class: "event/Booking"}
```

No code change, no restart, no registration table.  The class is in the metamodel; the surface reflects it.

## Patterns

### Inheritance

A new class that *subtypes* an existing class declares it via `:dt/subclass-of`:

```clojure
{:db/ident :event/RecurringBooking
 :dt/type :dt/Class
 :dt/subclass-of :event/Booking
 :dt/context "event"
 :dt/label "RecurringBooking"
 :dt/slots [:event.recurrence/rule]}
```

Slot inheritance is transitive: `:event/RecurringBooking` automatically inherits every slot from `:event/Booking` (and every ancestor up to `:dt/Resource`).

### Abstract classes

Mark a class abstract when it exists to share slots/structure but should never be directly instantiated:

```clojure
{:db/ident :event/AbstractBooking
 :dt/type :dt/Class
 :dt/subclass-of :dt/Resource
 :dt/abstract? true
 :dt/slots [...]}
```

`dt/make :event/AbstractBooking {...}` throws `:abstract-class`.

### Custom validators

For class-specific validation logic that the slot system doesn't express:

```clojure
;; In your Clojure code
(defn validate-booking [entity]
  (when (and (:event.booking/starts-at entity)
             (:event.booking/ends-at entity)
             (.before (:event.booking/ends-at entity)
                      (:event.booking/starts-at entity)))
    {:slot :event.booking/ends-at
     :error :ends-before-starts
     :message "Booking end must be after start"}))

;; In schema
{:db/ident :event/Booking
 :dt/type :dt/Class
 :dt/validator 'my.namespace/validate-booking
 ...}
```

The validator is a fully-qualified symbol; `dt/validate-data` invokes it after structural checks.

### Path-derived idents (for mm/* classes)

If your class produces hierarchical content (sections, sub-resources), consider path-derived idents as `:mm/Section` uses.  See [`doc/concepts/markdown-as-canonical.md`](../concepts/markdown-as-canonical.md) for the discipline.

### Codec binding

If your class has a canonical wire format (markdown? RDF? custom IDL?), bind a codec via `:dt/native-codec`:

```clojure
{:db/ident :event/Booking
 :dt/type :dt/Class
 :dt/native-codec :codec/json
 ...}
```

Then `sandbar.entity.create :event/Booking {format: "json", source: "..."}` routes through `sandbar.codec.json`.  Authoring a new codec is covered in [`implementing-a-codec.md`](implementing-a-codec.md).

## Common pitfalls

**Forgetting `:db.install/_attribute :db.part/db`.**  Properties need this transaction-time directive to install as Datomic attributes; missing it produces silent non-installation.

**Forward-declaring without backfilling.**  If you declare an ident first and then reference it in `:dt/domain` / `:dt/range` of another property, that's fine — but you must transact the *full* class definition in a later transaction.  Half-declared classes pass schema loading but fail validation.

**Skipping `:dt/context` and `:dt/label`.**  These power presentational projections (REST descriptions, MCP titles).  They're optional but most consumers expect them.

**Required ref-typed slots without resolution.**  If `:event.booking/owner` requires a `:model/User`, ensure your `dt/make` call passes a fully-resolved user entity (looked up by `dt/find-by`) rather than a bare ident or string login.

**Cardinality-many slots stored as scalars.**  If a slot is `:db.cardinality/many`, the value must be a vector or set, even with one element: `{:event.booking/tags ["work"]}`, not `{:event.booking/tags "work"}`.

## See also

- [`zorp-tutorial.md`](zorp-tutorial.md) — a full worked example with inheritance
- [`doc/concepts/metamodel.md`](../concepts/metamodel.md) — the theoretical foundations
- [`implementing-a-codec.md`](implementing-a-codec.md) — binding a wire format to a new class
- [`designing-workflows.md`](designing-workflows.md) — when your class's lifecycle is workflow-shaped
- [`doc/api/dt-star.md`](../api/dt-star.md) — every `dt/*` function for introspection + creation
