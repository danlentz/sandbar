# Sandbag

A metamodel layer for Datomic that brings RDFS-style typing, inheritance, and validation to your domain models.

## Why a Metamodel?

Datomic is a powerful immutable database with a flexible, schema-on-read data model. You define attributes (properties), but there's no built-in concept of *classes* or *types* that group those attributes together. This flexibility is a strength, but as your domain grows, you may find yourself wanting:

- **Type Safety**: Know that an entity has a specific set of properties
- **Inheritance**: Share common properties across related entity types
- **Validation**: Ensure entities conform to their type's constraints
- **Introspection**: Query the schema itself - "What properties does a User have?"
- **Documentation**: Self-describing types that encode domain knowledge

Sandbag provides these capabilities while preserving Datomic's flexibility. It's complementary, not replacement - your Datomic attributes remain first-class, and the metamodel is itself stored as Datomic data.

## The Approach

The metamodel is defined in EDN schema files (like [`schema/meta.edn`](schema/meta.edn)) and uses Datomic's own entity model to represent types:

```clojure
;; A Class is an entity with :dt/type :dt/Class
{:db/ident :model/User
 :dt/type :dt/Class
 :dt/subclass-of :dt/Ref
 :dt/slots [:user/login :user/secret :user/uuid]}

;; A Property is an entity with :dt/type :dt/Property
{:db/ident :user/login
 :dt/type :dt/Property
 :dt/domain :model/User
 :dt/range :db.type/string}
```

This self-describing approach means you can query your type system with the same Datalog you use for your data:

```clojure
;; Find all classes that inherit from dt/Ref
(d/q '[:find ?class
       :where [?class :dt/subclass-of :dt/Ref]]
     (db))
```

## Quick Start

```bash
# Prerequisites: Java 8+, Leiningen, running Datomic transactor

# Clone and install dependencies
git clone <repository-url>
cd sandbag
lein deps

# Start the REPL
lein repl

# In the REPL
(require '[sandbag.core :refer [go stop]])
(go)  ; Starts HTTP server on :8080, nREPL on :28888
```

## Example: Zorp's Galactic Footwear Emporium

The best way to understand the metamodel is through example. See the complete tutorial featuring Zorp, an alien sneaker salesman on Pluto:

- **[doc/zorp-example.adoc](doc/zorp-example.adoc)** - Full walkthrough with ontology design, `dt/make` examples, and REST API queries
- **[test/sandbag/zorp_test.clj](test/sandbag/zorp_test.clj)** - Comprehensive test suite demonstrating all features

The footwear ontology illustrates class hierarchies, property inheritance, and type-safe instance creation:

```
                  zorp/Footwear [abstract]
          ________________|________________
         |                |                |
    zorp/Sneaker     zorp/Boot       zorp/Sandal
    _____|_____       ____|____          |
   |           |     |         |    zorp/FlipFlop
zorp/HighTop  zorp/LowTop  |    |
                zorp/SpaceBoot  zorp/MoonBoot
```

## Datatype API

The `sandbag.db.datatype` namespace provides the core metamodel operations:

### Class Introspection

```clojure
(require '[sandbag.db.datatype :as dt])

;; List all classes
(dt/all-classes)
;; => (:dt/Class :dt/Property :model/User ...)

;; Get parent classes
(dt/parents-of :model/User)
;; => (:dt/Ref)

;; Get all ancestors (transitive)
(dt/ancestors-of :model/User)
;; => (:dt/Ref :dt/Resource)

;; Get subclasses
(dt/subclasses-of :dt/Resource)
;; => (:dt/Class :dt/Property :dt/Ref :model/User ...)

;; Check relationships
(dt/subclass-of? :dt/Resource :model/User)  ;; => true
(dt/abstract? :dt/Literal)                   ;; => true
```

### Property Introspection

```clojure
;; List all properties
(dt/all-properties)
;; => (:dt/type :dt/domain :dt/range :user/login ...)

;; Get property metadata
(dt/domain-of :user/login)      ;; => :model/User
(dt/range-of :user/login)       ;; => :db.type/string
(dt/cardinality-of :user/login) ;; => :db.cardinality/one

;; Get slots for a class (inherited + direct)
(dt/slots-of :model/User)
;; => #{:dt/type :db/ident :user/login :user/secret ...}

;; Get only directly declared slots
(dt/direct-slots-of :model/User)
;; => [17592186045521 17592186045522 ...]  ; entity refs
```

### Instance Operations

```clojure
;; Get the class of an entity
(dt/class-of :model/User)  ;; => :dt/Class (User is itself a class)
(dt/class-of some-user-entity)  ;; => :model/User

;; Check instance relationships
(dt/instance-of? :model/User some-user-entity)  ;; => true
(dt/instance-of? :dt/Resource some-user-entity) ;; => true (inherited)

;; Query instances
(dt/all-instances-of :model/User)     ;; includes subclass instances
(dt/direct-instances-of :model/User)  ;; only direct instances
```

### Instance Creation

```clojure
;; Create a validated, typed instance
(dt/make :model/User
  {:user/login "alice"
   :user/secret "hashed-password"})
;; => {:db/id 123, :dt/type :model/User, :user/login "alice", ...}

;; Validation fails for abstract classes
(dt/make :dt/Literal {:value/string "test"})
;; => throws ExceptionInfo "Validation failed"

;; Skip validation if needed
(dt/make :model/User {:user/login "bob"} {:validate? false})

;; Pre-transaction validation
(dt/validate-data :model/User {:user/login "charlie"})
;; => nil (valid) or {:errors [...]}
```

### Validation

```clojure
;; Validate an existing entity
(dt/validate some-entity)
;; => nil (valid) or {:entity 123 :errors [{:type :no-class ...}]}

;; Check validity
(dt/valid? some-entity)  ;; => true/false

;; Error types: :no-class, :abstract-class, :invalid-type, :missing-required
```

## REST API

Sandbag exposes the metamodel through a REST API at `http://localhost:8080/api/store`.

### Classes

```bash
# List all classes
GET /api/store/classes

# Get class details
GET /api/store/classes/model/User

# Get class hierarchy
GET /api/store/classes/model/User/hierarchy
GET /api/store/classes/model/User/ancestors
GET /api/store/classes/model/User/parents
GET /api/store/classes/dt/Resource/subclasses
GET /api/store/classes/dt/Resource/subclasses/direct

# Get class slots
GET /api/store/classes/model/User/slots
GET /api/store/classes/model/User/slots/direct
GET /api/store/classes/model/User/slots/required

# Get instances
GET /api/store/classes/model/User/instances
GET /api/store/classes/model/User/instances/direct
```

### Properties

```bash
# List all properties
GET /api/store/properties

# Get property details
GET /api/store/properties/user/login

# Get domain/range
GET /api/store/properties/user/login/domain
GET /api/store/properties/user/login/range
```

### Entities

```bash
# Get entity by ident
GET /api/store/entities/model/User

# Get entity's class
GET /api/store/entities/model/User/class

# Validate entity
GET /api/store/entities/model/User/validate
```

### Type Predicates

```bash
# Check instance-of relationship
GET /api/store/types/instance-of/dt/Class/model/User
# => {"class": "dt/Class", "entity": "model/User", "instance-of?": true}

# Check subclass-of relationship
GET /api/store/types/subclass-of/dt/Resource/model/User
# => {"parent": "dt/Resource", "child": "model/User", "subclass-of?": true}
```

### Content Negotiation

The API supports multiple formats via the `Accept` header:
- `application/json` (default)
- `application/edn`
- `application/transit+json`

## Documentation

| Document | Description |
|----------|-------------|
| [Quick Start](doc/quickstart.adoc) | Get up and running |
| [Architecture](doc/architecture.adoc) | System design and components |
| [Metamodel Reference](doc/meta.adoc) | Complete type system documentation |
| [REST API Reference](doc/api.adoc) | Full HTTP endpoint documentation |
| [Development Guide](doc/development.adoc) | REPL workflow and extending the system |
| [Zorp's Footwear Example](doc/zorp-example.adoc) | Fun tutorial with an alien sneaker salesman |

## Project Structure

```
sandbag/
├── config/           # EDN configuration
├── doc/              # AsciiDoc documentation
├── schema/           # Metamodel schema definitions
│   ├── meta.edn      # Core metamodel (Class, Property, etc.)
│   ├── literal.edn   # Literal types
│   ├── ref.edn       # Reference types
│   ├── user.edn      # Example domain model
│   └── zorp.edn      # Zorp's Footwear Emporium (example)
├── src/sandbag/
│   ├── api/          # REST API handlers
│   ├── db/
│   │   ├── datatype.clj  # Metamodel API (dt/make, dt/slots-of, etc.)
│   │   └── datomic.clj   # Database connection
│   ├── server/       # HTTP and nREPL servers
│   └── service/      # Routing and interceptors
└── test/
    ├── sandbag/datatype_test.clj  # Core API tests
    └── sandbag/zorp_test.clj      # Example ontology tests
```

## Running Tests

```bash
# Run all tests
lein test

# Run specific test namespace
lein test sandbag.zorp-test

# Run with pattern matching
lein test :only sandbag.datatype-test/slots-of-test
```

## License

Copyright (C) Dan Lentz
