# Metamodel Concepts (Historical)

> **Note:** This document contains historical design notes from the original development. For current documentation, see [Metamodel Reference](meta.md).

## Datatype System Evolution

This section describes the conceptual evolution of the datatype system.

### The Self-Referential Type

The metamodel's core insight is that `:dt/type` (the "type" property) is itself a typed entity. This creates a self-referential foundation:

```clojure
;; :dt/type is both:
;; 1. An attribute (with :db/valueType, :db/cardinality)
;; 2. An instance of :dt/Property
;; 3. A slot of :dt/Resource

{:db/ident :dt/type
 :db/valueType :db.type/ref
 :db/cardinality :db.cardinality/one
 :dt/type :dt/Property          ;; Self-reference!
 :dt/domain :dt/Resource
 :dt/range :dt/Class}
```

### Fn Types

Database functions in Datomic are compiled Clojure code stored in the database. The metamodel embraces this by defining `:dt/Fn` as a first-class type:

```
F(x): D → R
```

Where:

* `D` is the domain type (`:fn/domain`)
* `R` is the range type (`:fn/range`)

This allows functions to be typed members of the metamodel, enabling:

* Version-controlled code in the database
* Type-checked function composition
* First-class workflow/pipeline definitions

### The "Any" Variant Type

The `:dt/Any` type solves the literal/reference bifurcation in Datomic's data model. While Datomic requires attributes to be either literal values OR references, `dt/Any` can hold either:

| Slot | Description |
|------|-------------|
| `:value/string` | A literal string value |
| `:value/long` | A 64-bit integer |
| `:value/ref` | A reference to another entity |
| `:value/instant` | A point in time |
| ... | (other literal types) |

**Important:** `dt/Any` is a *variant* type, not a *top* type. It doesn't participate in inheritance like `dt/Resource`.

### Aggregate References

For any type `T`, the metamodel provides:

* `T*` - 1D aggregate (array of T)
* `T**` - 2D aggregate (matrix of T)

Accessed via the `:dt/list` property:

```clojure
(-> (entity :model/User) :dt/list)       ;; => :model/User*
(-> (entity :model/User) :dt/list :dt/list)  ;; => :model/User**
```

This enables typed collection handling within the metamodel.

## Original Schema Example

```clojure
;; Historical Datatype Definition
[
  {:db/id #db/id[:db.part/db]
   :db/ident :dt/type
   :db/valueType :db.type/ref
   :db/cardinality :db.cardinality/one
   :db/doc "A reference to the data type of an entity"
   :db.install/_attribute :db.part/db}

  {:db/id :dt/type
   :dt/type :dt/Class
   :dt/context "system"
   :dt/name "Datatype"
   :dt/slots [:dt/context
              :dt/name
              :dt/parent
              :dt/list
              :dt/component
              :dt/slots]}
]
```

See [Metamodel Reference](meta.md) for current schema documentation.
