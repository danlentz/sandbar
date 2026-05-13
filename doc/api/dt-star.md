# `dt/*` API Reference

> Layer 4 — mechanical reference for every public function in `sandbar.db.datatype`.  Organized by concern, not by source order.  For practical usage see [`doc/guides/writing-a-clojure-client.md`](../guides/writing-a-clojure-client.md); for theoretical background see [`doc/concepts/metamodel.md`](../concepts/metamodel.md).

All functions live in `sandbar.db.datatype`; the conventional alias is `dt`.

```clojure
(require '[sandbar.db.datatype :as dt])
```

## Class introspection

### `(dt/all-classes)`

Returns the `:db/ident` keywords of all classes in the metamodel.

```clojure
(dt/all-classes)
;; => (:dt/Class :dt/Property :dt/Resource :mm/Memory :model/User ...)
```

### `(dt/all-properties)`

Returns the `:db/ident` keywords of all properties in the metamodel.

```clojure
(dt/all-properties)
;; => (:db/doc :db/ident :dt/domain :dt/range :user/login ...)
```

### `(dt/all-datatypes)`

Returns a sequence of all class idents in the database (entities where `:dt/type` is `:dt/Class`).

### `(dt/class-of e)`

Returns the class (`:dt/type`) of entity `e`.  Works with entity maps, entity IDs, or idents.

```clojure
(dt/class-of :model/user.alice)
;; => :model/User
```

### `(dt/abstract? dt)`

Returns `true` if class `dt` is marked abstract.

```clojure
(dt/abstract? :dt/Literal)
;; => true
```

## Hierarchy navigation

### `(dt/parents-of dt)`

Returns the direct parent classes of class `dt` (immediate `:dt/subclass-of` values).

### `(dt/ancestors-of dt)`

Returns all ancestor classes of class `dt` (parents, grandparents, …).  Recursive traversal.

```clojure
(dt/ancestors-of :model/User)
;; => (:dt/Ref :dt/Resource)
```

### `(dt/direct-subclasses-of dt)`

Returns idents of classes that directly extend `dt`.  Only immediate children.

### `(dt/subclasses-of dt)`

Returns idents of all transitive subclasses of `dt`.

```clojure
(dt/subclasses-of :dt/Resource)
;; => (:dt/Class :dt/Property :dt/List ...)
```

### `(dt/subclass-of? dt c)`

Returns `true` if `c` is a subclass of `dt` (direct or transitive).

```clojure
(dt/subclass-of? :dt/Resource :model/User)
;; => true
```

### `(dt/instance-of? dt e)`

Returns `true` if entity `e` is an instance of class `dt` (transitive — counts subclass instances).

```clojure
(dt/instance-of? :dt/Class :model/User)
;; => true (User is an instance of Class)
```

## Instance enumeration

### `(dt/direct-instances-of dt)`

Returns entities whose `:dt/type` is exactly `dt` — no subclass instances.

### `(dt/all-instances-of dt)`

Returns entities that are instances of `dt` or any subclass.  Uses the Datalog `instance-of` rule for recursive subclass traversal.

### `(dt/all-named-instances-of dt)`

Returns the `:db/ident` keywords of all named entities that are instances of `dt` (or subclass).  Useful for finding class and property idents.

## Slot queries

### `(dt/slots-of dt)`

Returns all effective slots for class `dt` (inherited + direct).

```clojure
(dt/slots-of :model/User)
;; => #{:db/doc :db/ident :user/login :user/secret ...}
```

### `(dt/direct-slots-of dt)`

Returns properties directly declared on class `dt` — no inherited slots.

### `(dt/required-slots-of dt)`

Returns all required slots for class `dt` (including inherited).  A required slot has `:dt/required? true`.

## Property queries

### `(dt/domain-of prop)`

Returns the domain class of property `prop` — the class instances may carry it.

### `(dt/range-of prop)`

Returns the range type of property `prop` — the allowed type of its values.

### `(dt/cardinality-of prop)`

Returns the property's Datomic cardinality (`:db.cardinality/one` or `:db.cardinality/many`).

### `(dt/cardinality-one? prop)`

Returns `true` if `prop` is cardinality-one.

### `(dt/cardinality-many? prop)`

Returns `true` if `prop` is cardinality-many.

### `(dt/required? prop)`

Returns `true` if property `prop` has `:dt/required?` set to `true`.

### `(dt/validator-of dt)`

Returns the custom validator function symbol for class `dt`, or `nil` if none.

### `(dt/properties-with-domain dt)`

Returns idents of all properties whose domain is `dt` or an ancestor of `dt`.  Useful for finding all properties applicable to instances of a class.

## Entity creation

### `(dt/make class attrs)` / `(dt/make class attrs opts)`

Creates a typed instance with pre-transaction validation.

- `class` — class ident (e.g., `:model/User`)
- `attrs` — a map of slot values, or a `{:format ... :source ...}` codec input
- `opts` — optional map; supports `:validate?` (default `true`)

Returns the transacted entity.  Throws on validation failure.

```clojure
(dt/make :model/User
  {:user/login "alice"
   :user/secret "$2a$10$..."})

;; With codec-mediated source
(dt/make :mm/Memory
  {:format "markdown"
   :source "---\nname: Foo\n---\n# Context\n..."})
```

### `(dt/make* class attrs)`

Creates a typed instance **without validation**.  Escape hatch — use sparingly.

### `(dt/realize-with seed walk-fn)`

General-purpose entity realization helper.  Given a seed entity and a `walk-fn`, returns a vector of entity-spec maps including the seed and any related entities the walker produces.  Used by codecs for tree-shaped entity realization (e.g., `mm/Memory` + its section tree).

### `(dt/emit-entity entity)` / `(dt/emit-entity entity opts)`

Emits an entity in its native representation via the codec mediator.  Resolves the class's `:dt/native-codec`, delegates to `sandbar.codec/emit`.

## Validation

### `(dt/validate e)`

Validates entity `e` against its class.

Returns `nil` if valid, or a map of validation errors:

```clojure
{:entity entity-id
 :errors [{:slot :user/login
           :error :missing-required
           :message "..."}
          ...]}
```

Error types:

| `:error` value           | Meaning                                                |
|--------------------------|--------------------------------------------------------|
| `:no-class`              | Entity has no `:dt/type` attribute                     |
| `:abstract-class`        | Attempted to instantiate an abstract class             |
| `:missing-required`      | Required slot has no value                             |
| `:invalid-type`          | Slot value doesn't match expected `:dt/range`          |
| `:custom-validator-failed` | Class's `:dt/validator` returned errors              |

### `(dt/valid? e)`

Returns `true` if entity passes validation.

### `(dt/validate-data class props)`

Pre-transaction validation.  Takes a class and a props map; returns `nil` if valid or an error map.  Useful for validating *before* calling `dt/make`.

```clojure
(dt/validate-data :model/User {:user/login "alice"})
;; => nil (valid) or {:errors [...]}
```

### `(dt/validate-all-instances dt)`

Validates all instances of class `dt` (including subclass instances).

Returns:

```clojure
{:class dt
 :total <count>
 :valid <count>
 :invalid <count>
 :errors [{:entity <id> :errors [...]} ...]}
```

For large classes, prefer the workflow-backed `sandbar.validation.start` MCP verb — it's cancellable and produces a queryable history.

## Error and exception conventions

`dt/make` throws `ex-info` with `:validation/errors` on validation failure.  The exception's `ex-data` contains the same shape `dt/validate` returns.

`dt/*` functions never silently coerce — passing a non-existent class to `dt/instance-of?` throws.  Passing a non-existent ident to `dt/slots-of` returns `nil`.

## Performance notes

- `dt/all-classes` / `dt/all-properties` / `dt/all-instances-of` are Datalog queries.  For hot paths, cache the result.
- `dt/slots-of` walks the inheritance chain.  Caching is generally safe (schema rarely changes).
- `dt/validate` is N-slot-typed checks plus an optional custom validator invocation.  Validation of large entities is O(slot count); for very wide entities, prefer `dt/valid?` if you only need the boolean.

## See also

- [`doc/concepts/metamodel.md`](../concepts/metamodel.md) — theoretical background
- [`doc/guides/writing-a-clojure-client.md`](../guides/writing-a-clojure-client.md) — practical patterns
- [`doc/guides/defining-new-classes.md`](../guides/defining-new-classes.md) — schema authoring
- [`doc/api/codec-protocol.md`](codec-protocol.md) — codec mediator that `dt/emit-entity` delegates to
