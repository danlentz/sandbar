# Add a useful operational signal

Use `sandbar.logging` at application callsites. The service configures the underlying handlers during startup; an embedded application should configure logging once at its lifecycle boundary. This guide assumes that setup is complete.

## Give recurring events a stable identity

```clojure
(require '[sandbar.logging :as log])

(log/info ::review-started "Review started" {:document-id 42})
(log/warn ::review-delayed {:document-id 42 :elapsed-ms 1200})
```

Use a namespaced keyword for a recurring signal and structured fields for values a reader will filter or compare. A literal message alone is also accepted, but its synthetic identity depends on the callsite. Avoid logging secrets or entire document bodies when a small identifier explains the operation.

## Keep failure visible to the caller

```clojure
(defn record-review [save-review! review]
  (try
    (save-review! review)
    (catch Exception ex
      (log/error ::review-save-failed ex {:review-id (:id review)})
      (throw ex))))
```

`error` carries the throwable separately from structured data. Logging the exception does not handle the failed operation; this example rethrows it so the caller still observes failure.

## Request a retained observation deliberately

```clojure
(log/info ::review-completed {:document-id 42} :db-only)
(log/info ::review-milestone "Review policy adopted" {:policy-id "v2"} :first-class)
```

With the memorial handler installed, `:db-only` creates an `:event/SystemEvent`; `:first-class` creates an `:mm/EventLog` eligible for projection. The handler reports persistence failures, so the logging call's return is not a durable-write acknowledgment. Use an ordinary checked entity operation if the record is required for the transaction's correctness.

The reserved `:inline` flag does not currently annotate a host entity. Do not use it to promise retained metadata. Normal level filters still apply to flagged signals.

## Measure a named span

```clojure
(defn measured-review [review-fn input]
  (log/profile :document-review
    (review-fn input)))
```

The result is the body's result. A profiling span needs the application's profiling handlers to produce an aggregate report; it does not automatically become a stored memory.

## Find configuration and output

Logging filters are defined in [`resources/logging.edn`](../../resources/logging.edn), with the `SANDBAR_LOGGING_CONFIG` file override. Handler installation and file output are managed by [`sandbar.logging.init`](../../src/sandbar/logging/init.clj). Inspect both the global filters and handler levels when a signal appears to be missing.

See [Logging](../concepts/logging-substrate.md) for the routing and retention model and [Operations](../operations.md) for service diagnosis.
