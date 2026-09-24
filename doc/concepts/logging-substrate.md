# Logging: make operations visible without making every signal a memory

**Observability should explain what the system is doing while preserving a deliberate boundary around durable knowledge.** A routine request, a failed operation, and a decision worth remembering need different treatment even when all can be described as events.

`sandbar.logging` is the public callsite API. Its `info`, `warn`, `error`, `debug`, and `trace` macros emit signals through Telemere. `profile` wraps a Tufte span and returns the body's result. Callers name the occurrence and provide structured data; handlers determine output.

## Stable identities make signals useful

A namespaced event keyword is a stable handle for filtering and interpretation. Structured fields preserve identifiers and measurements without requiring a log reader to parse prose. An optional human message explains context.

Literal-string shorthand is available for simple messages. Its synthetic identifier depends on the callsite and message, so use an explicit keyword for a signal whose identity must survive source movement or wording changes.

Log only the fields needed to explain the operation. Credential values and complete private payloads do not become appropriate output merely because a signal is marked as diagnostic. Logging is an output path with its own readers and retention.

## Persistence is an explicit request

The optional trailing memorial flag asks the installed handler to route a signal:

| Flag | Implemented handler route |
| --- | --- |
| None | Ordinary configured log handlers; no requested database record |
| `:first-class` | Create an `:mm/EventLog`, eligible for ordinary projection |
| `:db-only` | Create an `:event/SystemEvent` |
| `:inline` | Reserved; no host-annotation implementation |

The handler catches and reports its own persistence failures to avoid recursive logging failures. Therefore a returned logging call is not an acknowledgment that a database record or file was successfully persisted. Use a direct, checked domain operation when the durable record is part of the application's correctness contract.

The current system health jobs emit ordinary log signals rather than retaining every health sample as authored memory. This keeps operational measurements available without making high-volume telemetry dominate knowledge retrieval.

## Configuration and lifecycle

Filter configuration comes from `resources/logging.edn`, with a `SANDBAR_LOGGING_CONFIG` file override. `sandbar.logging.init/start!` applies it and installs the service's console, rolling-file, and memorial handlers. Handler minimum levels and asynchronous buffering also affect delivery; a flag on a filtered-out debug signal cannot force persistence.

Service startup initializes logging before the rest of the system so boot failures can be observed. An embedded application should deliberately configure its handlers and destinations. Reinitialization changes process-wide handler configuration and belongs at the application lifecycle boundary.

Continue with [Using logging](../guides/using-logging.md). Source: [public macros](../../src/sandbar/logging.clj), [handler routing](../../src/sandbar/logging/handlers.clj), and [initialization](../../src/sandbar/logging/init.clj).
