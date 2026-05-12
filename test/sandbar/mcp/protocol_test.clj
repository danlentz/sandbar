(ns sandbar.mcp.protocol-test
  "Test suite for the MCP protocol layer (sandbar.mcp.protocol).

   Stage C.1 foundation — pure tests for the initialize handshake,
   capability negotiation, and method dispatch (no Datomic DB required;
   tools/call DB-backed tests land in C.4).

   JSON-RPC envelope builder/validator tests live in
   sandbar.mcp.envelope-test (extracted as part of the F-M-001
   cycle-break — envelope lives in a leaf namespace).

   Per decisions/sandbar_mcp_server_design_2026_05_12.md + the
   Sandbar-as-MCP-Server arc."
  (:require [clojure.test         :refer :all]
            [sandbar.mcp.protocol :as protocol]))

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
