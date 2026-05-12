(ns sandbar.mcp.resources-test
  "Tests for the MCP resources layer — pure tests for URI codec + the
   subscription registry. DB-backed tests for resolve-entity +
   handle-read + handle-list land in C.5.x alongside test-db fixture
   wiring.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.5."
  (:require [clojure.test          :refer :all]
            [sandbar.mcp.resources :as resources]))

(use-fixtures :each
  (fn [t]
    (resources/clear-all-subscriptions!)
    (try (t) (finally (resources/clear-all-subscriptions!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URI codec

(deftest parse-uri-shapes
  (testing "named-ident URI"
    (let [r (resources/parse-uri "mcp://sandbar/dt/Class/mm/Memory")]
      (is (= :dt/Class (:class-ident r)))
      (is (= :mm/Memory (:entity-ident r)))
      (is (nil? (:entity-eid r)))))

  (testing "eid URI"
    (let [r (resources/parse-uri "mcp://sandbar/dt/Class/12345")]
      (is (= :dt/Class (:class-ident r)))
      (is (= 12345 (:entity-eid r)))
      (is (nil? (:entity-ident r)))))

  (testing "namespaced-ident URI"
    (let [r (resources/parse-uri "mcp://sandbar/mm/Memory/decisions/foo")]
      (is (= :mm/Memory (:class-ident r)))
      (is (= :decisions/foo (:entity-ident r))))))

(deftest parse-uri-rejects-non-mcp-scheme
  (is (nil? (resources/parse-uri "http://example.com/x")))
  (is (nil? (resources/parse-uri nil)))
  (is (nil? (resources/parse-uri "")))
  (is (nil? (resources/parse-uri "mcp://other-server/x/y/z"))))

(deftest parse-uri-rejects-too-few-parts
  (is (nil? (resources/parse-uri "mcp://sandbar/"))
      "single segment should be rejected")
  (is (nil? (resources/parse-uri "mcp://sandbar/dt"))
      "two-part path should be rejected (no class-name)"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscription registry

(deftest subscribe-then-unsubscribe
  (resources/subscribe! "mcp://sandbar/mm/Memory/abc" "sub-1")
  (is (= 1 (resources/subscriber-count "mcp://sandbar/mm/Memory/abc")))
  (resources/unsubscribe! "mcp://sandbar/mm/Memory/abc" "sub-1")
  (is (= 0 (resources/subscriber-count "mcp://sandbar/mm/Memory/abc"))))

(deftest multiple-subscribers-on-same-uri
  (resources/subscribe! "mcp://sandbar/mm/Memory/x" "sub-1")
  (resources/subscribe! "mcp://sandbar/mm/Memory/x" "sub-2")
  (resources/subscribe! "mcp://sandbar/mm/Memory/x" "sub-3")
  (is (= 3 (resources/subscriber-count "mcp://sandbar/mm/Memory/x")))
  (resources/unsubscribe! "mcp://sandbar/mm/Memory/x" "sub-2")
  (is (= 2 (resources/subscriber-count "mcp://sandbar/mm/Memory/x"))))

(deftest unsubscribe-removes-uri-when-empty
  (resources/subscribe! "mcp://sandbar/mm/Memory/x" "sub-1")
  (resources/unsubscribe! "mcp://sandbar/mm/Memory/x" "sub-1")
  (is (not (contains? (resources/all-subscriptions) "mcp://sandbar/mm/Memory/x"))
      "URI entry should be dropped when last subscriber leaves"))

(deftest unsubscribe-is-idempotent
  (resources/subscribe! "mcp://sandbar/mm/Memory/x" "sub-1")
  (resources/unsubscribe! "mcp://sandbar/mm/Memory/x" "sub-1")
  ;; double-unsubscribe doesn't throw
  (is (nil? (resources/unsubscribe! "mcp://sandbar/mm/Memory/x" "sub-1")))
  (is (= 0 (resources/subscriber-count "mcp://sandbar/mm/Memory/x"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handle-subscribe / handle-unsubscribe — JSON-RPC envelope

(deftest handle-subscribe-attaches-subscription
  (let [resp (resources/handle-subscribe 7 {:uri "mcp://sandbar/mm/Memory/q"})]
    (is (= 7 (:id resp)))
    (is (= "2.0" (:jsonrpc resp)))
    (is (= {} (:result resp)))
    (is (= 1 (resources/subscriber-count "mcp://sandbar/mm/Memory/q")))))

(deftest handle-subscribe-rejects-missing-uri
  (let [resp (resources/handle-subscribe 8 {})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i):uri" (-> resp :error :message)))))

(deftest handle-unsubscribe-removes-subscription
  (resources/subscribe! "mcp://sandbar/mm/Memory/q" ::resources/broadcast)
  (let [resp (resources/handle-unsubscribe 9 {:uri "mcp://sandbar/mm/Memory/q"})]
    (is (= 9 (:id resp)))
    (is (= {} (:result resp)))
    (is (= 0 (resources/subscriber-count "mcp://sandbar/mm/Memory/q")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handle-read URI parsing failure modes

(deftest handle-read-rejects-invalid-uri
  (let [resp (resources/handle-read 1 {:uri "not-a-mcp-uri"})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i)invalid" (-> resp :error :message)))))
