# Quick Start

> A 5-minute hands-on tour of Sandbar.  Gets a server running, makes a few requests, exercises the metamodel in the REPL.  For the next level of detail, follow the [zorp-tutorial](zorp-tutorial.md) (worked example) or one of the client guides ([Clojure](writing-a-clojure-client.md) / [MCP](writing-an-mcp-client.md) / [REST](writing-a-rest-client.md)).

## Prerequisites

- **Java 11 or later** (`java -version`)
- **Leiningen** — https://leiningen.org
- **Datomic Peer** — `datomic-pro` jars; transactor running locally on `:4334`

> A vanilla `datomic-pro` developer edition is sufficient.  The default config expects `datomic:dev://localhost:4334/sandbar`.

## Clone and install

```bash
git clone <repository-url> && cd sandbar
lein deps
```

## Start the transactor

In a separate terminal:

```bash
cd /path/to/datomic-pro
bin/transactor config/dev-transactor.properties
```

Verify it's up:

```bash
nc -zv localhost 4334
```

## Start Sandbar

```bash
lein repl
```

Once at the REPL prompt:

```clojure
(require '[sandbar.core :refer [go stop]])
(go)
```

You should see startup logs.  The system is now serving:

- **HTTP** on `:8080` (REST + MCP endpoints)
- **nREPL** on `:28888` (connect from your editor)

## Sanity check

In another shell:

```bash
curl http://localhost:8080/api/status
```

```json
{"time":"2026-05-13T16:30:00.000Z","clojure":{"major":1,"minor":12,"incremental":4}}
```

## Walk the metamodel

```clojure
(require '[sandbar.db.datatype :as dt])

;; All classes registered in the running metamodel
(dt/all-classes)
;; => (:dt/Class :dt/Property :dt/Resource :mm/Memory :mm/Section ...)

;; Class ancestry — :model/User → :dt/Ref → :dt/Resource
(dt/ancestors-of :model/User)
;; => (:dt/Ref :dt/Resource)

;; Effective slots — including inherited
(dt/slots-of :model/User)
;; => #{:db/doc :db/ident :user/login :user/secret :user/uuid ...}

;; Type predicates
(dt/instance-of? :dt/Class :model/User)
;; => true (User is an instance of Class)

(dt/subclass-of? :dt/Resource :model/User)
;; => true (User is a subclass of Resource)
```

## Create a validated entity

```clojure
(def alice
  (dt/make :model/User
    {:user/login "alice"
     :user/secret "$2a$10$..."}))

;; The entity is transacted — validation passed
(:dt/type alice)
;; => :model/User

;; You can find it again by ident or query
(dt/find-by :user/login "alice")
;; => entity map
```

## Try the MCP surface

Sandbar serves MCP on the same port at `/mcp`.  Get a service-account token (see [auth.md](../auth.md)) and:

```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer $SANDBAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

You should see the verb catalog — `sandbar.entity.create`, `sandbar.schema.classes`, …

## Try the REST surface

```bash
# List all classes
curl http://localhost:8080/api/store/classes

# One class's details
curl http://localhost:8080/api/store/classes/model/User
```

Both projections — MCP and REST — surface the same metamodel; the protocol differs.  See [`mcp-protocol.md`](../concepts/mcp-protocol.md) for the discipline.

## Stop the system

```clojure
(stop)
```

## What's next

| Goal                                            | Read                                                     |
|-------------------------------------------------|----------------------------------------------------------|
| See a complete worked example                   | [`zorp-tutorial.md`](zorp-tutorial.md)                    |
| Add a new domain class                          | [`defining-new-classes.md`](defining-new-classes.md)      |
| Embed Sandbar in your Clojure code              | [`writing-a-clojure-client.md`](writing-a-clojure-client.md) |
| Connect Claude or another AI client             | [`writing-an-mcp-client.md`](writing-an-mcp-client.md)    |
| Consume Sandbar over HTTP                       | [`writing-a-rest-client.md`](writing-a-rest-client.md)    |
| Understand the design                           | [`doc/concepts/`](../concepts/)                          |

## Troubleshooting

**Cannot connect to Datomic.**  Confirm the transactor is up (`nc -zv localhost 4334`).  If your transactor is on a different host or port, override `:datomic-uri` in `config/config.edn`.

**Port 8080 already in use.**  Change `:http-port` in `config/config.edn`.

**Schema not loaded.**  Verify `:required-schema` in `config/config.edn` lists every schema file you need (`:meta :literal :ref :fn :any :workflow :mm :user :twit`).
