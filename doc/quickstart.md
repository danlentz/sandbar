# Quick Start Guide

This guide will get you up and running with Sandbar in minutes.

## Prerequisites

* Java 8 or later
* [Leiningen](https://leiningen.org) build tool
* [Datomic](https://www.datomic.com) transactor (dev or pro)

## Installation

Clone the repository and install dependencies:

```bash
git clone <repository-url>
cd sandbar
lein deps
```

## Starting the Datomic Transactor

Sandbar requires a running Datomic transactor. Start it in a separate terminal:

```bash
# For Datomic Dev
cd /path/to/datomic
bin/transactor config/dev-transactor.properties
```

The default configuration expects Datomic at `datomic:dev://localhost:4334/sandbar`.

## Running the Application

### Option 1: Direct Run

```bash
lein run
```

The server starts on http://localhost:8080.

### Option 2: REPL (Recommended for Development)

```bash
lein repl
```

Once in the REPL:

```clojure
;; Start the system
(go)

;; The system is now running:
;; - HTTP server on port 8080
;; - nREPL server on port 28888

;; Stop the system
(stop)

;; Restart after code changes
(stop)
(go)
```

## Verifying the Installation

### Check System Status

```bash
curl http://localhost:8080/api/status
```

Expected response:
```json
{
  "time": "2024-01-15T10:30:00.000Z",
  "clojure": {"major": 1, "minor": 12, "incremental": 4}
}
```

### List All Classes

```bash
curl http://localhost:8080/api/store/classes
```

Expected response:
```json
{
  "count": 42,
  "classes": ["db.type/bigdec", "db.type/bigint", "db.type/boolean", ...]
}
```

### Get Class Details

```bash
curl http://localhost:8080/api/store/classes/dt/Resource
```

Expected response:
```json
{
  "class": "dt/Resource",
  "description": {...},
  "abstract?": false,
  "slots": ["db/doc", "db/ident", "dt/label", "dt/namespace", "dt/type"],
  "subclasses": ["dt/Class", "dt/List", "dt/Literal", "dt/Property", "dt/Ref", ...]
}
```

## Using the REPL

Connect to the nREPL server (port 28888) from your editor, or use the command line:

```bash
lein repl :connect 28888
```

### Querying the Metamodel

```clojure
(require '[sandbar.db.datatype :as dt])

;; List all classes
(dt/all-classes)
;; => (:dt/Class :dt/Property :dt/Resource :model/User ...)

;; Get class hierarchy
(dt/ancestors-of :model/User)
;; => (:dt/Ref :dt/Resource)

(dt/subclasses-of :dt/Ref)
;; => (:dt/Any :dt/Fn :model/Twit :model/User)

;; Query slots
(dt/slots-of :model/User)
;; => #{:db/doc :db/ident :dt/label :dt/namespace :dt/type :user/login :user/secret :user/uuid}

;; Check type relationships
(dt/subclass-of? :dt/Resource :model/User)
;; => true
```

### Creating Typed Entities

```clojure
(require '[sandbar.db.datatype :as dt])

;; Create a new User instance
(def alice (dt/make :model/User {:user/login "alice"
                                  :user/secret "hashed-password"}))

;; Verify the type
(:dt/type alice)
;; => :model/User

;; Validate the entity
(dt/valid? alice)
;; => true
```

## Running Tests

```bash
# Run all tests
lein test

# Run a specific test namespace
lein test sandbar.api-store-test
```

## Next Steps

* Read the [Architecture Guide](architecture.md) to understand the system design
* Explore the [Metamodel Reference](meta.md) for the complete type hierarchy
* Check the [REST API Reference](api.md) for all available endpoints
* Review the [Development Guide](development.md) for extending the system

## Troubleshooting

### Cannot connect to Datomic

Ensure the Datomic transactor is running:
```bash
# Check if transactor is listening
nc -zv localhost 4334
```

### Port already in use

Change the HTTP port in `config/config.edn`:
```clojure
{:http-port 8081}
```

### Schema not loading

Check that all required schema files are listed in `config/config.edn`:
```clojure
{:required-schema [:meta :literal :ref :fn :any :user :twit]}
```
