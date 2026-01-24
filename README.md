# Sandbar

**Classes for your Clojure data.** Sandbar adds RDFS-style typing, inheritance, and validation to Datomic — because sometimes "it's just a map" isn't enough.

```clojure
;; Define a class with properties
{:db/ident :model/User
 :dt/type :dt/Class
 :dt/subclass-of :dt/Ref
 :dt/slots [:user/login :user/email]}

;; Create validated instances
(dt/make :model/User {:user/login "zorp" :user/email "zorp@pluto.net"})

;; Query your type system like any other data
(d/q '[:find ?class :where [?class :dt/subclass-of :dt/Ref]] (db))
```

## Quick Start

```bash
# Prerequisites: Java 11+, Leiningen, running Datomic transactor
git clone <repository-url> && cd sandbar
lein deps
lein repl

# In the REPL
(require '[sandbar.core :refer [go stop]])
(go)  ; HTTP on :8080, nREPL on :28888
```

Then visit `http://localhost:8080/api/store/classes` to see your type system.

## Why Bother?

Datomic gives you flexible, schema-on-read attributes. Sandbar groups them into *classes* with inheritance, so you get:

| Without Sandbar | With Sandbar |
|-----------------|--------------|
| "Does this entity have all the fields it needs?" | `(dt/valid? entity)` |
| "What properties can a User have?" | `(dt/slots-of :model/User)` |
| "Is AdminUser a kind of User?" | `(dt/subclass-of? :model/User :model/AdminUser)` |
| "Find all Users (including subclasses)" | `(dt/all-instances-of :model/User)` |
| "Create a User with validation" | `(dt/make :model/User {...})` |

The metamodel is itself stored as Datomic entities. It's turtles all the way down.

## The Zorp Tutorial

The best way to learn Sandbar is through Zorp, an alien sneaker salesman on Pluto who needs to manage his galactic footwear inventory.

```
                  zorp/Footwear [abstract]
          ________________|________________
         |                |                |
    zorp/Sneaker     zorp/Boot       zorp/Sandal
    _____|_____       ____|____          |
   |           |     |         |    zorp/FlipFlop
zorp/HighTop  zorp/LowTop     |
                    zorp/SpaceBoot
```

Zorp's ontology demonstrates class hierarchies, property inheritance, abstract classes, and validation — all while selling vacuum-rated moon boots to beings with variable tentacle counts.

- **[doc/zorp-example.md](doc/zorp-example.md)** — Full walkthrough
- **[test/sandbar/zorp_test.clj](test/sandbar/zorp_test.clj)** — Executable examples

## Core API

```clojure
(require '[sandbar.db.datatype :as dt])

;; Classes
(dt/all-classes)                         ; List all classes
(dt/parents-of :model/User)              ; => (:dt/Ref)
(dt/ancestors-of :model/User)            ; => (:dt/Ref :dt/Resource)
(dt/subclasses-of :dt/Resource)          ; All descendants
(dt/subclass-of? :dt/Resource :model/User) ; => true
(dt/abstract? :zorp/Footwear)            ; => true

;; Properties
(dt/all-properties)                      ; List all properties
(dt/slots-of :model/User)                ; All slots (inherited + direct)
(dt/direct-slots-of :model/User)         ; Only declared on this class
(dt/domain-of :user/login)               ; => :model/User
(dt/range-of :user/login)                ; => :db.type/string

;; Instances
(dt/make :model/User {:user/login "zorp"})  ; Create with validation
(dt/class-of some-entity)                   ; => :model/User
(dt/instance-of? :model/User some-entity)   ; => true
(dt/all-instances-of :model/User)           ; Includes subclass instances
(dt/valid? some-entity)                     ; Validate against class
```

## REST API

The metamodel is fully exposed via HTTP. Default format is EDN; request JSON with `Accept: application/json`.

```bash
# Schema overview
curl http://localhost:8080/api/store/schema

# Class introspection
curl http://localhost:8080/api/store/classes
curl http://localhost:8080/api/store/classes/model/User
curl http://localhost:8080/api/store/classes/model/User/slots
curl http://localhost:8080/api/store/classes/model/User/hierarchy

# Properties
curl http://localhost:8080/api/store/properties
curl http://localhost:8080/api/store/properties/user/login

# Type checks
curl http://localhost:8080/api/store/types/instance-of/dt/Class/model/User
curl http://localhost:8080/api/store/types/subclass-of/dt/Resource/model/User
```

<details>
<summary>Full endpoint reference</summary>

| Endpoint | Description |
|----------|-------------|
| `GET /api/status` | System status |
| `GET /api/store/schema` | Schema overview |
| `GET /api/store/classes` | List classes |
| `GET /api/store/classes/:ns/:name` | Class details |
| `GET /api/store/classes/:ns/:name/slots` | All slots |
| `GET /api/store/classes/:ns/:name/slots/direct` | Direct slots only |
| `GET /api/store/classes/:ns/:name/slots/required` | Required slots |
| `GET /api/store/classes/:ns/:name/instances` | All instances |
| `GET /api/store/classes/:ns/:name/instances/direct` | Direct instances |
| `GET /api/store/classes/:ns/:name/hierarchy` | Full hierarchy |
| `GET /api/store/classes/:ns/:name/subclasses` | All subclasses |
| `GET /api/store/classes/:ns/:name/ancestors` | All ancestors |
| `GET /api/store/classes/:ns/:name/parents` | Direct parents |
| `GET /api/store/properties` | List properties |
| `GET /api/store/properties/:ns/:name` | Property details |
| `GET /api/store/properties/:ns/:name/domain` | Property domain |
| `GET /api/store/properties/:ns/:name/range` | Property range |
| `GET /api/store/entities/:ns/:name` | Entity by ident |
| `GET /api/store/entities/:ns/:name/class` | Entity's class |
| `GET /api/store/entities/:ns/:name/validate` | Validate entity |
| `GET /api/store/types/instance-of/:class/:entity` | Instance check |
| `GET /api/store/types/subclass-of/:parent/:child` | Subclass check |

</details>

### JSON Example

```bash
curl -H "Accept: application/json" http://localhost:8080/api/store/classes/dt/Resource
```

```json
{
  "class": "dt/Resource",
  "abstract?": false,
  "slots": ["db/doc", "db/ident", "dt/label", "dt/namespace", "dt/type"],
  "parents": [],
  "subclasses": ["dt/Class", "dt/List", "dt/Literal", "dt/Property", "dt/Ref"],
  "instance-count": 85
}
```

## Event Logging

Sandbar includes a built-in event system that persists to Datomic. HTTP requests are logged automatically; you can also log programmatically.

```clojure
(require '[sandbar.util.event :as event])

;; Simple logging
(event/log! :info "User logged in")
(event/log! :error "Payment failed" {:event/status :failure})

;; Typed events
(event/log-http! {:http/method :get :http/path "/api/users" :http/status-code 200})
(event/log-error! "Oops" ex)  ; Captures exception + stacktrace

;; Query via API
;; GET /api/events?level=error&limit=50
;; GET /api/events/correlation/550e8400-e29b-41d4-a716-446655440000
```

Events support correlation IDs for distributed tracing. See [doc/event.md](doc/event.md) for details.

## Project Structure

```
sandbar/
├── config/             # EDN configuration
├── schema/             # Type definitions
│   ├── meta.edn        # Core metamodel (Class, Property, etc.)
│   ├── event.edn       # Event types
│   └── zorp.edn        # Example: Galactic Footwear Emporium
├── src/sandbar/
│   ├── api/            # REST handlers
│   ├── db/
│   │   ├── datatype.clj   # The good stuff (dt/make, dt/slots-of, etc.)
│   │   └── datomic.clj    # Database connection
│   ├── server/         # HTTP + nREPL
│   ├── service/        # Routing, interceptors
│   └── util/
│       └── event.clj   # Event logging
└── test/               # 200+ tests, because we're not animals
```

## Documentation

| Document | What You'll Learn |
|----------|-------------------|
| [Quick Start](doc/quickstart.md) | Zero to running in 5 minutes |
| [Architecture](doc/architecture.md) | How the pieces fit together |
| [Metamodel](doc/meta-model.md) | Classes, properties, inheritance |
| [Event System](doc/event.md) | Logging, correlation, interceptors |
| [Zorp Tutorial](doc/zorp-example.md) | Learn by selling alien footwear |

## Running Tests

```bash
lein test                                    # All 200+ tests
lein test sandbar.zorp-test                  # Just the fun ones
lein test :only sandbar.datatype-test/make-test  # Specific test
```

## FAQ

**Q: Why not just use Datomic's schema?**
A: Datomic schemas define attributes, not types. You can say "there's an attribute called `:user/login`" but not "a User has login, email, and inherits from Person." Sandbar adds that layer.

**Q: Is this like OWL/RDF?**
A: Inspired by RDFS, but simpler. No open-world assumption, no inference engine, no PhD required. Just classes, properties, and inheritance.

**Q: What's with the turtle jokes?**
A: The metamodel describes itself using its own constructs. `dt/Class` is an instance of `dt/Class`. It's self-referential. Turtles. All the way down. We're very sorry.

**Q: Can I use this in production?**
A: Zorp has been selling moon boots on Pluto for years with zero incidents.*

<sub>*Incidents involving sentient footwear are tracked separately.</sub>

## License

Copyright (C) Dan Lentz
