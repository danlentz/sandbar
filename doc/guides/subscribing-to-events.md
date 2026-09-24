# Subscribe to an in-process event

This guide exercises the class-based bus in a development JVM with Sandbar's schema loaded. An in-process subscription observes explicit calls to `fire!`; it is separate from MCP resource subscriptions and the reactive file-projection queue. See [Events](../concepts/event-substrate.md) for those boundaries.

## Register, publish, and remove a handler

```clojure
(require '[sandbar.event :as event])

(def received (atom []))

(def subscription
  (event/subscribe! :dt/Event
    (fn [message]
      (swap! received conj (:example/message message)))))

(try
  (event/fire! {:event/class :dt/Event
               :example/message "received"})
  (assert (= ["received"] @received))
  (finally
    (event/unsubscribe! :dt/Event subscription)))
```

The returned function is the handle used for removal. Registering the same function twice for the same class is idempotent. Separately created function values are separate subscribers.

In a real application, publish the concrete event class and subscribe to that class or a useful parent. Dispatch follows the model's class ancestry. The payload is an event map; publication itself does not persist that map as an entity.

## Keep the handler's work bounded

Handlers run synchronously in the publishing thread. Exceptions are caught and logged so that other matching handlers are still attempted. A slow handler still delays the publisher. For asynchronous work, enqueue into a consumer-owned queue with an explicit capacity, failure, shutdown, and retry policy.

Do not rely on subscriber iteration order. If one action depends on another's completion, model the sequence explicitly rather than using two independent subscriptions as an ordering mechanism.

## Choose the right observation surface

For accepted entity changes that drive projection, the existing callback surface is `sandbar.reactive/register-callback!`. Its callback receives `[entity-eid post-tx-slots]` after an eligible mutation. Registering such a callback is an application lifecycle action and does not subscribe to every raw Datomic transaction.

For a remote client observing a resource, use the subscription operations described in [Writing an MCP client](writing-an-mcp-client.md). For durable process progress, inspect [workflow history](designing-workflows.md).

The current bus has no built-in durable replay or disconnect cursor. A consumer requiring recovery after downtime must use a separately established source and replay contract. Verify that contract before depending on it for correctness.
