# Developing Sandbar

Sandbar is a Clojure application with a Datomic-backed model, protocol adapters and managed runtime services. Start with a disposable database when changing model behavior; start the full service when the change needs its HTTP or worker lifecycle. The distinction makes experiments easier to reproduce and keeps their effects clear.

## Development environment

Install a JDK compatible with the dependencies in the checkout's `project.clj`, and [Leiningen](https://leiningen.org/). That file declares the Clojure and Datomic Peer versions, resource paths and build profiles. An external transactor is needed for a configured `datomic:dev` database; the in-memory examples and isolated unit tests do not need one.

```sh
git clone https://github.com/danlentz/sandbar.git
cd sandbar
lein deps
lein repl
```

Begin with the complete [in-memory Clojure example](guides/writing-a-clojure-client.md#connecting). It creates a unique database, binds its connection, loads schema and cleans up. Requiring a namespace is different from starting the service.

For a persistent development service, choose a separate database and client directory before starting it. Follow [operations](operations.md) for the directory layout and configuration. `SANDBAR_CLIENT_DIR` selects the client directory; `SANDBAR_DB_URL`, `SANDBAR_DB_SID`, `SANDBAR_PORT`, and `SANDBAR_NREPL_PORT` override the corresponding settings. Retain the required schema list unless the change deliberately defines another complete model.

## REPL-driven development

Use the REPL to inspect a value, exercise a small function and read back the result. Pass explicit database values to queries and use a bounded connection binding for embedded operations. See the [Clojure client guide](guides/writing-a-clojure-client.md) for the difference between a Datomic snapshot and the current connection.

For a development service whose configuration you have selected:

```clojure
(require '[sandbar.core :as sandbar])

(sandbar/go)
;; Exercise the configured service.
(sandbar/stop)
```

`go` initializes and starts the configured component system and runtime facilities. It can start HTTP, nREPL and workers and can write to the configured database or projection destinations. It is not the initializer for a disposable model test. The configured nREPL port is distinct from the editor's `lein repl` connection; use the port reported by the service you intend to inspect.

Reload pure namespaces selectively. Changes to stateful components, queues, callbacks or registrations need an explicit stop/restart and recovery check; re-evaluating a Var does not prove that an old worker stopped or that a callback was re-registered. Keep lifecycle ownership visible in the experiment.

## Testing

Run the relevant namespace while developing, then the checks appropriate to the change:

```sh
lein test sandbar.db.test-fence-test
lein test
```

The test profile sets `sandbar.db.uri` to an in-memory fallback. That fence protects code that reaches the default database URI; explicit connection URIs, file destinations and external services still need their own isolation. Review fixtures before running tests with effects. Preserve complete failure output rather than piping it through a command that discards the end or masks the test process's exit status.

The test helper is `sandbar.test-util/make-test-db-fixture`. Bind a fresh connection atom around it so its resets remain local to the test, and use a unique database name. For example:

```clojure
(ns sandbar.example-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.reactive :as reactive]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (fn [f]
    (binding [db/**conn* (atom nil)
              reactive/*reactive-projection-enabled?* false]
      ((tu/make-test-db-fixture
         {:test-name (str "example-" (java.util.UUID/randomUUID))
          :auth? false})
       f))))

(deftest invalid-tag-does-not-commit
  (let [before (count (dt/all-instances-of :mm/Tag))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (dt/make :mm/Tag {:mm.tag/value 42})))
    (is (= before (count (dt/all-instances-of :mm/Tag))))))
```

This checks both the rejected call and the absence of an entity afterward. Compose fixtures in a single `use-fixtures` registration for a given scope; a second registration replaces the first. Wait for asynchronous work before deleting its database. Tests of workers also need deterministic start, completion and shutdown observations.

Choose tests by the contract at risk. A codec change needs round-trip cases, including the structures it claims to preserve. A mutation needs read-back of its committed state and relevant derived views. An authorization change needs comparable requests across the supported adapters. A concurrency change needs a controlled interleaving and an independent final-state assertion; repeating a timing-sensitive test is weaker evidence.

## Where changes belong

| Change | Starting point |
| --- | --- |
| Class or property definition | `schema/`; [defining classes](guides/defining-new-classes.md) |
| Type inference and typed operations | `sandbar.db.datatype`; [`dt/*` reference](api/dt-star.md) |
| Memory identity and creation | `sandbar.store` |
| Representation parsing and emission | `sandbar.codec`; [implementing a codec](guides/implementing-a-codec.md) |
| MCP operation and discovery | `sandbar.mcp.tools`, `sandbar.mcp.protocol` |
| REST adapter | `sandbar.api.*`, `sandbar.service.routes` |
| Workflow behavior | `sandbar.util.workflow`; [workflow guide](guides/designing-workflows.md) |
| Projection, queues and notifications | `sandbar.reactive.*`, the relevant lifecycle owner |

Follow a real caller through the boundary before adding an abstraction. Put an invariant where every supported caller can preserve it, and keep transport decoding and error envelopes in the adapters. Adding a field to the metamodel does not by itself make every path enforce its meaning.

Database schema and runtime functions need their own initialization and migration discipline. Requiring a namespace does not install schema in an existing database. Changes that affect stored data should describe the precondition, transformation, verification and recovery path. Use the [operations guide](operations.md) for backup and reconstruction procedures.

## Code style and review

Write ordinary, expert-readable Clojure: small functions with useful names, explicit data transformations and clear effect boundaries. Use predicates ending in `?`, and mark mutating operations with `!` when that matches the surrounding API. Dynamic Vars use earmuffs; do not give an ordinary constant a name that implies dynamic binding.

Public docstrings should state inputs, return shape, absence/error behavior and effects. Place them before the argument vector so REPL help can find them. Distinguish a validation function returning nil on success from a boolean predicate, and distinguish an entity collection from a set of identifiers.

Reuse a shared domain operation when callers need the same policy. Keep intentional differences explicit: single-entity creation, bulk import and wire serialization may have different contracts. Human-facing prose and tables can use the project's formatting libraries; JSON, EDN and other wire formats need their designated serializers. A readable Java interop path can be appropriate for a measured hot loop.

Review the design as well as the diff: trace real callers, ask which state can change, inspect failure and retry behavior, and compare the proposed abstraction with keeping the current code. More indirection is worthwhile when it removes duplicated knowledge or gives a clear boundary to an existing responsibility. It is not a goal by itself.

## Documentation and builds

Keep examples aligned with source signatures, advertised tool schemas and actual routes. A successful response is not enough to establish a write contract; verify the state and effects the example describes. Use synthetic data and isolated destinations for executable documentation checks.

The MCP verb reference and related catalogs have generation/check aliases in `project.clj`. Inspect an alias's behavior and output paths before running it; some tools connect to a configured database or rewrite documentation. Review generated changes alongside their source declarations.

```sh
lein uberjar
```

Use the artifact path reported by the build. The server's advertised protocol-facing version and the project's artifact version have different purposes; neither establishes that a dependency has been published. Production configuration, startup and service health belong in [operations](operations.md).
