# Sandbar Documentation

## Overview

Sandbar is a Clojure web service that implements an RDFS-inspired type system layered on top of Datomic. It provides a metamodel for defining typed entities with class hierarchies, properties with domain/range constraints, and a comprehensive REST API for querying the type system.

### Key Features

* **RDFS-like Type System** - Classes, properties, inheritance hierarchies
* **Datomic Integration** - Immutable, time-aware database with powerful query capabilities
* **REST API** - Full HTTP API for querying classes, properties, entities, and type relationships
* **Component Architecture** - Clean lifecycle management with Stuart Sierra's Component library
* **REPL-Driven Development** - nREPL server with CIDER support

## Documentation

| Document | Description |
|----------|-------------|
| [Quick Start](quickstart.md) | Get up and running in minutes |
| [Architecture](architecture.md) | System design, components, and code organization |
| [Metamodel Reference](meta.md) | Complete class hierarchy and type system documentation |
| [REST API Reference](api.md) | HTTP endpoints for querying the metamodel |
| [Development Guide](development.md) | REPL workflow, testing, and extending the system |
| [Zorp's Footwear Emporium](zorp-example.md) | A lighthearted tutorial featuring an alien sneaker salesman on Pluto |

## Quick Links

### Build & Run

```bash
# Start the application
lein run

# Start REPL for development
lein repl

# Run tests
lein test

# Build uberjar
lein uberjar
```

### REPL Commands

```clojure
;; In the REPL
(require '[sandbar.core :refer [go stop]])

(go)    ;; Start the system
(stop)  ;; Stop the system
```

### API Endpoints

```bash
# System status
curl http://localhost:8080/api/status

# List all classes
curl http://localhost:8080/api/store/classes

# Get class details
curl http://localhost:8080/api/store/classes/dt/Resource

# List properties
curl http://localhost:8080/api/store/properties
```

## Project Structure

```
sandbar/
├── config/           # EDN configuration files
├── doc/              # Documentation (you are here)
├── schema/           # Datomic schema definitions (EDN)
├── src/sandbar/
│   ├── api/          # REST API handlers
│   ├── db/           # Database layer (Datomic, datatype)
│   ├── server/       # Server components (Pedestal, nREPL)
│   ├── service/      # HTTP service layer
│   └── util/         # Utilities
└── test/             # Test suites
```

### Historical Notes

These documents contain design notes from the original development:

* [Metamodel Concepts](meta-model.md) - Original type system design rationale
* [Computation Model](computation-model.md) - Datalog rules and recursion
* [Storage Model](storage-model.md) - Fressian tags and value types
* [Toolchain](toolchain.md) - Historical development tools

## Technology Stack

* [Clojure](https://clojure.org) - Functional programming language
* [Datomic](https://www.datomic.com) - Immutable database with time-travel queries
* [Pedestal](https://pedestal.io) - High-performance HTTP server
* [Component](https://github.com/stuartsierra/component) - Lifecycle management
* [nREPL](https://nrepl.org) - Network REPL for interactive development

## License

Copyright (C) Dan Lentz
