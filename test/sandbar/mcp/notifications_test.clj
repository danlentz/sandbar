(ns sandbar.mcp.notifications-test
  "Tests for the MCP notifications subscriber registry + publish/broadcast.

   Pure tests with a mock `send!` fn capturing emitted events. SSE wire-
   level tests would require a real Pedestal HTTP context and land in
   integration tests once the transport stabilizes.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4 + B.1.5 +
   B.1.10."
  (:require [clojure.test              :refer :all]
            [sandbar.mcp.notifications :as notifications]))

(use-fixtures :each
  (fn [t]
    (notifications/clear-all!)
    (try (t) (finally (notifications/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; register! / unregister! lifecycle

(deftest register-returns-id-and-tracks-subscriber
  (let [received (atom [])
        send!    (fn [evt] (swap! received conj evt))
        id       (notifications/register! {:send! send!})]
    (is (string? id) "register! returns a string id")
    (is (= 1 (notifications/subscriber-count)))
    (is (contains? (notifications/all-subscribers) id))))

(deftest unregister-is-idempotent-and-removes-subscriber
  (let [send!    (fn [_] nil)
        id       (notifications/register! {:send! send!})]
    (notifications/unregister! id)
    (is (= 0 (notifications/subscriber-count)))
    ;; Idempotent — double-unregister doesn't throw
    (is (nil? (notifications/unregister! id)))
    (is (= 0 (notifications/subscriber-count)))))

(deftest unregister-nil-id-is-no-op
  (let [send!    (fn [_] nil)]
    (notifications/register! {:send! send!})
    (notifications/unregister! nil)
    (is (= 1 (notifications/subscriber-count)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; publish! broadcast

(deftest publish-broadcasts-to-all-subscribers
  (let [r1 (atom [])
        r2 (atom [])
        _id1 (notifications/register! {:send! (fn [e] (swap! r1 conj e))})
        _id2 (notifications/register! {:send! (fn [e] (swap! r2 conj e))})
        sent (notifications/publish! "notifications/tools/list_changed" {})]
    (is (= 2 sent) "both subscribers should receive the notification")
    (is (= 1 (count @r1)))
    (is (= 1 (count @r2)))
    (testing "event shape is JSON-RPC 2.0 notification (no :id field)"
      (let [e (first @r1)]
        (is (= "2.0" (:jsonrpc e)))
        (is (= "notifications/tools/list_changed" (:method e)))
        (is (not (contains? e :id)))))))

(deftest publish-removes-failing-subscribers
  (let [r1 (atom [])
        _id1 (notifications/register!
               {:send! (fn [e] (swap! r1 conj e))})
        _id2 (notifications/register!
               {:send! (fn [_] (throw (Exception. "client disconnected")))})
        sent (notifications/publish! "notifications/tools/list_changed" {})]
    (is (= 1 sent) "only the healthy subscriber received successfully")
    (is (= 1 (count @r1)))
    (is (= 1 (notifications/subscriber-count))
        "failing subscriber should be unregistered automatically")))

(deftest publish-with-no-subscribers-returns-zero
  (is (= 0 (notifications/publish! "notifications/tools/list_changed" {})))
  (is (= 0 (notifications/subscriber-count))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Canonical notification methods

(deftest canonical-method-wrappers
  (let [received (atom [])
        send!    (fn [e] (swap! received conj e))
        _id      (notifications/register! {:send! send!})]
    (notifications/tools-list-changed!)
    (notifications/resources-list-changed!)
    (notifications/resources-updated! "mcp://sandbar/mm/Memory/abc123")
    (notifications/prompts-list-changed!)
    (notifications/message! :info "hello world")

    (let [methods (mapv :method @received)]
      (is (= "notifications/tools/list_changed"     (nth methods 0)))
      (is (= "notifications/resources/list_changed" (nth methods 1)))
      (is (= "notifications/resources/updated"      (nth methods 2)))
      (is (= "notifications/prompts/list_changed"   (nth methods 3)))
      (is (= "notifications/message"                (nth methods 4))))

    (testing "resources-updated! carries the URI"
      (is (= "mcp://sandbar/mm/Memory/abc123"
             (-> @received (nth 2) :params :uri))))

    (testing "message! carries level + data + logger"
      (let [m (nth @received 4)]
        (is (= "info"     (-> m :params :level)))
        (is (= "hello world" (-> m :params :data)))
        (is (= "sandbar" (-> m :params :logger)))))))
