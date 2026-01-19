# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Start the application
lein run

# Start REPL for development
lein repl

# Run tests
lein test

# Build uberjar
lein uberjar

# Check for outdated dependencies
lein ancient
```

### REPL Development

From the REPL, use these functions in `sandbag.core`:
- `(go)` - Initialize and start the system
- `(stop)` - Stop the running system
- `(init)` - Initialize system without starting
- `(start)` - Start an initialized system

The system starts an nREPL server on port 28888 (configured in `config/config.edn`).

## Architecture

This is a Clojure web service built with Pedestal, Datomic, and Stuart Sierra's Component library.

### System Components

The application uses Component for lifecycle management (`sandbag.core`). The system consists of:
- **Pedestal** (`sandbag.server.pedestal`) - HTTP server on port 8080
- **Datomic Peer** (`sandbag.db.datomic`) - Database connection
- **nREPL** (`sandbag.server.nrepl`) - Development REPL server with CIDER support

Global system state is held in `sandbag.sys/system`. The active Datomic connection is available via `sandbag.db.datomic/*conn*`.

### Service Layer

Routes are defined in `sandbag.service.routes` using Pedestal's routing syntax. The `defhandler` macro in `sandbag.service.endpoint` provides a standard pattern for defining handlers that receive `[request db-conn valid-params]`.

Interceptor chains handle:
- Body parsing (`sandbag.service.content`)
- Parameter validation (`sandbag.service.params`)
- Response formatting

### Database

Datomic Peer connects to `datomic:dev://localhost:4334/sandbag` (requires a running Datomic transactor).

Schema files live in `schema/*.edn` and are loaded based on `:required-schema` in `config/config.edn`. Database functions are in `sandbag.db.fn`.

### Configuration

EDN configuration files in `config/` are accessed via `sandbag.util.edn`:
- `(config-value :key)` - Get a config value
- `(resource-value :schema-name nil)` - Load an EDN resource file
