(ns sandbar.mcp.envelope-test
  "Test suite for the JSON-RPC 2.0 envelope builder/validator
   (sandbar.mcp.envelope).

   Extracted from sandbar.mcp.protocol-test as part of the F-M-001
   cycle-break — envelope construction lives in a leaf namespace so
   both sandbar.mcp.protocol and sandbar.mcp.notifications can
   require it without inducing a cycle.

   Pure tests — no Datomic DB required."
  (:require [clojure.test         :refer :all]
            [sandbar.mcp.envelope :as envelope]))

(deftest jsonrpc-result-shape
  (testing "jsonrpc-result returns canonical JSON-RPC 2.0 success envelope"
    (let [r (envelope/jsonrpc-result 42 {:foo "bar"})]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 42 (:id r)))
      (is (= {:foo "bar"} (:result r)))
      (is (not (contains? r :error))))))

(deftest jsonrpc-error-shape
  (testing "jsonrpc-error without data field"
    (let [r (envelope/jsonrpc-error 7 -32601 "Method not found")]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 7 (:id r)))
      (is (= -32601 (-> r :error :code)))
      (is (= "Method not found" (-> r :error :message)))
      (is (not (contains? (:error r) :data)))))

  (testing "jsonrpc-error with optional data field"
    (let [r (envelope/jsonrpc-error 8 -32602 "Invalid params" {:reason "x"})]
      (is (= -32602 (-> r :error :code)))
      (is (= {:reason "x"} (-> r :error :data))))))

(deftest jsonrpc-notification-shape
  (testing "jsonrpc-notification has no id"
    (let [n (envelope/jsonrpc-notification "notifications/tools/list_changed" nil)]
      (is (= "2.0" (:jsonrpc n)))
      (is (= "notifications/tools/list_changed" (:method n)))
      (is (not (contains? n :id))))))

(deftest valid-envelope?-discriminates
  (testing "valid request"
    (is (envelope/valid-envelope? {:jsonrpc "2.0" :id 1 :method "ping"})))
  (testing "valid notification"
    (is (envelope/valid-envelope? {:jsonrpc "2.0" :method "notifications/ready"})))
  (testing "valid success response"
    (is (envelope/valid-envelope? {:jsonrpc "2.0" :id 1 :result {}})))
  (testing "valid error response"
    (is (envelope/valid-envelope? {:jsonrpc "2.0" :id 1 :error {:code -1 :message "x"}})))
  (testing "missing :jsonrpc rejected"
    (is (not (envelope/valid-envelope? {:id 1 :method "ping"}))))
  (testing "wrong :jsonrpc version rejected"
    (is (not (envelope/valid-envelope? {:jsonrpc "1.0" :id 1 :method "ping"}))))
  (testing "non-map rejected"
    (is (not (envelope/valid-envelope? "not a map")))
    (is (not (envelope/valid-envelope? nil)))))
