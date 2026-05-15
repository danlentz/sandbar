(ns sandbar.mcp.notifications-test
  "Tests for the MCP notifications subscriber registry + publish/broadcast.

   Pure tests with a mock `send!` fn capturing emitted events. SSE wire-
   level tests would require a real Pedestal HTTP context and land in
   integration tests once the transport stabilizes.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4 + B.1.5 +
   B.1.10.

   Closed-channel adversarial coverage: see `publish-removes-subscribers-
   on-closed-channel-falsey-return` + companions — exercises ultrareview
   UR-13 fix (observation
   sandbar_sse_subscriber_async_put_closed_channel_silent_drop_2026_05_14).
   The transport-layer `send!` closure returns the `async/put!` value,
   which is `false` on a closed core.async channel WITHOUT throwing;
   pre-UR-13 code only caught exceptions, leaking dead registry entries."
  (:require [clojure.test              :refer :all]
            [clojure.tools.logging     :as log]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Closed-channel adversarial coverage (ultrareview UR-13)
;;
;; The transport-layer `send!` closure wraps `async/put!` against an SSE
;; core.async channel.  Per core.async semantics, `put!` to a CLOSED
;; channel returns `false` WITHOUT throwing.  Pre-UR-13 the notifications
;; module only caught exceptions — falsey returns slipped through, so
;; disconnected SSE clients were retained in `+subscribers+` indefinitely
;; (memory leak) and their notifications were silently dropped (data loss).
;;
;; These tests simulate the closed-channel shape with `send!` returning
;; falsey, and assert:
;;   (a) the count returned by publish! reflects the real delivery
;;   (b) the offending subscriber is evicted from the registry
;;   (c) a structured warn log is emitted (captured via a log-appender
;;       binding around the call)
;;   (d) registry size stays bounded across many connect-disconnect cycles

(defn- with-captured-log-warns
  "Run `body-fn` while capturing every clojure.tools.logging warn-level
   call into an atom.  Returns a vector pair `[result captured-logs]`
   where `captured-logs` is a vector of vectors matching what was passed
   to `log/warn`.  Used to assert the structured warn-log shape from
   the closed-channel path."
  [body-fn]
  (let [captured (atom [])
        orig     log/*logger-factory*
        factory  (reify clojure.tools.logging.impl/LoggerFactory
                   (name [_] "captured")
                   (get-logger [_ logger-ns]
                     (reify clojure.tools.logging.impl/Logger
                       (enabled? [_ _] true)
                       (write! [_ level throwable message]
                         (when (= :warn level)
                           (swap! captured conj
                                  {:ns        logger-ns
                                   :level     level
                                   :throwable throwable
                                   :message   message}))))))]
    (binding [log/*logger-factory* factory]
      (let [result (body-fn)]
        [result @captured]))))

(deftest publish-removes-subscribers-on-closed-channel-falsey-return
  (testing "send! returning false (closed core.async channel) evicts subscriber"
    (let [r1 (atom [])
          _id1 (notifications/register!
                 {:send! (fn [e] (swap! r1 conj e) true)})
          ;; Simulates a transport whose underlying async/put! channel
          ;; was closed by the client disconnecting — put! returns false,
          ;; the send! closure faithfully propagates that false back up.
          _id2 (notifications/register! {:send! (fn [_] false)})
          sent (notifications/publish! "notifications/tools/list_changed" {})]
      (is (= 1 sent) "only the healthy subscriber was counted as delivered")
      (is (= 1 (count @r1)))
      (is (= 1 (notifications/subscriber-count))
          "closed-channel subscriber evicted from registry"))))

(deftest publish-evicts-on-nil-return-too
  (testing "send! returning nil is treated as a failure (defensive)"
    (let [_id1 (notifications/register! {:send! (fn [_] nil)})
          sent (notifications/publish! "notifications/tools/list_changed" {})]
      (is (= 0 sent))
      (is (= 0 (notifications/subscriber-count))
          "nil-return subscriber also evicted"))))

(deftest publish-emits-warn-log-on-closed-channel
  (testing "structured warn log fires with :reason :closed-channel"
    ;; clojure.tools.logging formats `(log/warn k m)` into a single
    ;; stringified message before handing it to the underlying logger
    ;; impl.  Rather than coupling these tests to the formatter's exact
    ;; print shape (which can drift across tools.logging versions), we
    ;; assert the stringified message CONTAINS the relevant field-name
    ;; substrings — this still distinguishes the closed-channel path
    ;; from the exception path.
    (let [id1                    (notifications/register!
                                   {:send! (fn [_] false)})
          [sent captured-warns]  (with-captured-log-warns
                                   (fn []
                                     (notifications/publish!
                                       "notifications/tools/list_changed" {})))]
      (is (= 0 sent))
      (is (= 0 (notifications/subscriber-count)))
      (is (seq captured-warns) "at least one warn-log fired")
      (let [warn-text (-> captured-warns first :message str)]
        (is (re-find #":MCP/notification-send-failed" warn-text)
            "warn-log carries the structured event key")
        (is (re-find (re-pattern id1) warn-text)
            "warn-log carries the subscriber-id of the evicted subscriber")
        (is (re-find #"notifications/tools/list_changed" warn-text)
            "warn-log carries the method name")
        (is (re-find #":reason :closed-channel" warn-text)
            "warn-log distinguishes closed-channel reason from exception")))))

(deftest publish-to-evicts-on-closed-channel-falsey-return
  (testing "publish-to! also detects closed-channel falsey return"
    (let [id1  (notifications/register! {:send! (fn [_] false)})
          id2  (notifications/register! {:send! (fn [_] true)})
          sent (notifications/publish-to! [id1 id2]
                                          "notifications/resources/updated"
                                          {:uri "mcp://sandbar/x"})]
      (is (= 1 sent) "only the healthy subscriber counted as delivered")
      (is (= 1 (notifications/subscriber-count))
          "closed-channel subscriber evicted under publish-to! path too"))))

(deftest registry-stays-bounded-across-connect-disconnect-cycles
  (testing "subscriber registry does not leak across simulated SSE disconnect cycles"
    ;; Each iteration: register a subscriber whose send! always returns
    ;; false (simulating a client that disconnected before the first
    ;; publish).  After publish!, the registry must be empty — otherwise
    ;; long-running deployments accumulate dead entries (the UR-13 leak).
    (let [cycles 100]
      (dotimes [_ cycles]
        (notifications/register! {:send! (fn [_] false)})
        (notifications/publish! "notifications/message"
                                {:level "info" :data "ping"}))
      (is (= 0 (notifications/subscriber-count))
          (str "After " cycles
               " connect-disconnect-publish cycles, registry must be empty")))))

(deftest mixed-healthy-and-closed-subscribers-only-evicts-dead-ones
  (testing "healthy subscribers survive a publish that evicts closed-channel peers"
    (let [healthy-received (atom [])
          healthy-id       (notifications/register!
                             {:send! (fn [e]
                                       (swap! healthy-received conj e)
                                       true)})
          _closed1         (notifications/register! {:send! (fn [_] false)})
          _closed2         (notifications/register! {:send! (fn [_] false)})
          sent             (notifications/publish!
                             "notifications/tools/list_changed" {})]
      (is (= 1 sent))
      (is (= 1 (count @healthy-received)))
      (is (= 1 (notifications/subscriber-count)))
      (is (contains? (notifications/all-subscribers) healthy-id)
          "the healthy subscriber survived"))))
