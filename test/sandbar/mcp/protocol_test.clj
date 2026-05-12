(ns sandbar.mcp.protocol-test
  "Test suite for the MCP protocol layer (sandbar.mcp.protocol).

   Stage C.1 foundation — pure tests for JSON-RPC 2.0 envelope handling,
   initialize handshake, capability negotiation, and method dispatch
   (no Datomic DB required; tools/call DB-backed tests land in C.4).

   Per decisions/sandbar_mcp_server_design_2026_05_12.md + the
   Sandbar-as-MCP-Server arc."
  (:require [clojure.test         :refer :all]
            [sandbar.mcp.protocol :as protocol]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON-RPC 2.0 envelope shape
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest jsonrpc-result-shape
  (testing "jsonrpc-result returns canonical JSON-RPC 2.0 success envelope"
    (let [r (protocol/jsonrpc-result 42 {:foo "bar"})]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 42 (:id r)))
      (is (= {:foo "bar"} (:result r)))
      (is (not (contains? r :error))))))

(deftest jsonrpc-error-shape
  (testing "jsonrpc-error without data field"
    (let [r (protocol/jsonrpc-error 7 -32601 "Method not found")]
      (is (= "2.0" (:jsonrpc r)))
      (is (= 7 (:id r)))
      (is (= -32601 (-> r :error :code)))
      (is (= "Method not found" (-> r :error :message)))
      (is (not (contains? (:error r) :data)))))

  (testing "jsonrpc-error with optional data field"
    (let [r (protocol/jsonrpc-error 8 -32602 "Invalid params" {:reason "x"})]
      (is (= -32602 (-> r :error :code)))
      (is (= {:reason "x"} (-> r :error :data))))))

(deftest jsonrpc-notification-shape
  (testing "jsonrpc-notification has no id"
    (let [n (protocol/jsonrpc-notification "notifications/tools/list_changed" nil)]
      (is (= "2.0" (:jsonrpc n)))
      (is (= "notifications/tools/list_changed" (:method n)))
      (is (not (contains? n :id))))))

(deftest valid-envelope?-discriminates
  (testing "valid request"
    (is (protocol/valid-envelope? {:jsonrpc "2.0" :id 1 :method "ping"})))
  (testing "valid notification"
    (is (protocol/valid-envelope? {:jsonrpc "2.0" :method "notifications/ready"})))
  (testing "valid success response"
    (is (protocol/valid-envelope? {:jsonrpc "2.0" :id 1 :result {}})))
  (testing "valid error response"
    (is (protocol/valid-envelope? {:jsonrpc "2.0" :id 1 :error {:code -1 :message "x"}})))
  (testing "missing :jsonrpc rejected"
    (is (not (protocol/valid-envelope? {:id 1 :method "ping"}))))
  (testing "wrong :jsonrpc version rejected"
    (is (not (protocol/valid-envelope? {:jsonrpc "1.0" :id 1 :method "ping"}))))
  (testing "non-map rejected"
    (is (not (protocol/valid-envelope? "not a map")))
    (is (not (protocol/valid-envelope? nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Initialize handshake
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest initialize-returns-canonical-response
  (let [params   {:protocolVersion "2025-11-25"
                  :capabilities    {:elicitation {}}
                  :clientInfo      {:name "test-client" :version "1.0"}}
        response (protocol/handle-initialize 1 params)
        result   (:result response)]
    (testing "response is a JSON-RPC success envelope"
      (is (= "2.0" (:jsonrpc response)))
      (is (= 1 (:id response)))
      (is (some? result))
      (is (not (contains? response :error))))

    (testing "protocolVersion declared"
      (is (= "2025-11-25" (:protocolVersion result))))

    (testing "serverInfo present"
      (is (some? (:serverInfo result)))
      (is (= "sandbar" (-> result :serverInfo :name))))

    (testing "capabilities include tools / resources / prompts / logging"
      (let [caps (:capabilities result)]
        (is (true? (-> caps :tools :listChanged)))
        (is (true? (-> caps :resources :subscribe)))
        (is (true? (-> caps :resources :listChanged)))
        (is (true? (-> caps :prompts :listChanged)))
        (is (some? (:logging caps)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Method dispatch
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest dispatch-initialize
  (let [msg {:jsonrpc "2.0"
             :id      1
             :method  "initialize"
             :params  {:protocolVersion "2025-11-25"
                       :clientInfo      {:name "tc" :version "x"}}}
        resp (protocol/dispatch msg)]
    (is (= 1 (:id resp)))
    (is (some? (-> resp :result :serverInfo)))))

(deftest dispatch-unknown-method-returns--32601
  (let [msg  {:jsonrpc "2.0" :id 9 :method "this/does/not/exist"}
        resp (protocol/dispatch msg)]
    (is (= -32601 (-> resp :error :code)))
    (is (re-find #"not found" (-> resp :error :message)))))

(deftest dispatch-invalid-envelope--32600
  (let [msg  {:jsonrpc "1.0" :id 1 :method "ping"}
        resp (protocol/dispatch msg)]
    (is (= -32600 (-> resp :error :code)))))

(deftest dispatch-missing-method--32600
  (let [msg  {:jsonrpc "2.0" :id 1 :result {}}
        resp (protocol/dispatch msg)]
    (is (= -32600 (-> resp :error :code)))))

(deftest dispatch-initialized-notification-returns-nil
  (testing "notifications/initialized is a no-response notification"
    (let [msg  {:jsonrpc "2.0" :method "notifications/initialized"}
          resp (protocol/dispatch msg)]
      (is (nil? resp)))))
