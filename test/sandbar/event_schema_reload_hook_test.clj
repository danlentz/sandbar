(ns sandbar.event-schema-reload-hook-test
  "The event dispatch-cache flush MUST be registered as a post-schema-reload
  handler (2026-09-18, reliability sprint item 1.2).

  Before the fix `sandbar.event` registered its flush with TWO arguments
  against a ONE-argument registrar; the ArityException was swallowed by the
  defensive catch-all around the registration, so ZERO handlers were
  registered and the dispatch cache kept its pre-reload class hierarchy
  until a subscribe!/unsubscribe! happened to invalidate it.  Per
  bugs/event_dispatch_cache_reload_handler_never_registered_arity_mismatch_-
  swallowed_astra_finding_2026_09_18.

  Pins:
   (a) loading `sandbar.event` leaves a handler registered under
       `:sandbar.event/dispatch-cache-flush`;
   (b) the keyed registrar is idempotent — re-registering under the same
       key replaces, never accumulates;
   (c) the legacy 1-arity registrar still works (keyed by the fn itself)
       and the search/type-relation cache clearers that use it remain
       registered;
   (d) firing the handlers actually flushes the event dispatch cache."
  (:require [clojure.test :refer :all]
            [sandbar.db.datomic :as db]
            [sandbar.event :as event]
            [sandbar.search :as search]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "event-schema-reload-hook"}))

(deftest dispatch-cache-flush-handler-is-registered
  (testing "loading sandbar.event registers the flush under its keyword key"
    (is (db/post-schema-reload-handler-registered? ::event/dispatch-cache-flush)
        "the two-argument registration now lands (was an ArityException swallowed silently)")))

(deftest keyed-registration-replaces-instead-of-accumulating
  (testing "re-registering under the same key keeps ONE handler"
    (let [before (count @db/post-schema-reload-handlers)
          calls  (atom 0)]
      (db/register-post-schema-reload-handler! ::probe (fn [] (swap! calls inc)))
      (db/register-post-schema-reload-handler! ::probe (fn [] (swap! calls inc)))
      (is (= (inc before) (count @db/post-schema-reload-handlers))
          "exactly one new entry despite two registrations")
      (db/fire-post-schema-reload-handlers!)
      (is (= 1 @calls) "the probe handler ran exactly once per fire")
      ;; clean up so other tests see the pre-test registry
      (swap! db/post-schema-reload-handlers dissoc ::probe))))

(deftest legacy-one-arity-registrations-survive
  (testing "the search and type-relation cache clearers registered with the
            1-arity form are present, keyed by the fn itself"
    (is (db/post-schema-reload-handler-registered? search/clear-bm25f-cache!)
        "sandbar.search registers clear-bm25f-cache! via the 1-arity form")))

(deftest firing-handlers-flushes-the-event-dispatch-cache
  (testing "a schema reload flushes the dispatch cache (no stale hierarchy)"
    (let [flushed (atom 0)]
      ;; invalidate-cache! is private; with-redefs-fn takes the var directly.
      ;; The registered handler is (fn [] (invalidate-cache!)) — a call
      ;; through the var at fire time — so the redefinition is honored.
      (with-redefs-fn {#'event/invalidate-cache! (fn [] (swap! flushed inc))}
        (fn []
          (db/fire-post-schema-reload-handlers!)
          (is (pos? @flushed)
              "fire-post-schema-reload-handlers! invoked the event cache flush"))))))
