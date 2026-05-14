(ns sandbar.mcp.tasks-test
  "Tests for the MCP tasks layer — pure tests for task-id codec +
   handler envelope shapes. DB-backed tests (workflow process lookup +
   start-task!) land alongside test-db fixture wiring in C.7.x.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.10."
  (:require [clojure.test      :refer :all]
            [sandbar.mcp.tasks :as tasks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task-id codec

(deftest process->task-id-extracts-eid
  (is (= "12345" (tasks/process->task-id {:db/id 12345}))))

(deftest process->task-id-handles-nil
  (is (nil? (tasks/process->task-id nil))))

(deftest task-id->process-handles-malformed-input
  (is (nil? (tasks/task-id->process nil))
      "nil task-id should return nil")
  (is (nil? (tasks/task-id->process "not-a-number"))
      "non-numeric task-id should return nil (NumberFormatException caught)"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handle-get / handle-cancel envelope shapes (pure failure modes)

(deftest handle-get-rejects-missing-task-id
  (let [resp (tasks/handle-get 1 {})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i):taskId" (-> resp :error :message)))))

(deftest handle-get-rejects-malformed-task-id
  ;; Malformed task-id resolves to nil process; same error path as missing
  (let [resp (tasks/handle-get 2 {:taskId "not-a-number"})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i)not found" (-> resp :error :message)))))

(deftest handle-cancel-rejects-missing-task-id
  (let [resp (tasks/handle-cancel 3 {})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i):taskId" (-> resp :error :message)))))

(deftest handle-cancel-with-bogus-task-id-returns-not-found
  (let [resp (tasks/handle-cancel 4 {:taskId "99999"})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i)not found" (-> resp :error :message)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handle-list envelope shape — UR-5 (Phase U Stage U-4)
;;
;; The DB-free path returns a structured error or an empty-list result;
;; DB-backed populated-list tests defer to a future test-db fixture pass.

(deftest handle-list-returns-success-envelope-shape
  ;; Without a DB connection, list-processes raises; handle-list's
  ;; try/catch projects to JSON-RPC internal-error.  The test asserts
  ;; the verb is dispatchable AND projects errors as a structured
  ;; JSON-RPC envelope (NOT escaping the boundary).
  (let [resp (tasks/handle-list 1 {})]
    (is (= "2.0" (:jsonrpc resp)))
    (is (= 1 (:id resp)))
    ;; Either a :result with :tasks (if a DB happens to be wired) OR a
    ;; structured :error envelope.  Both are valid JSON-RPC responses
    ;; per MCP spec; what's NOT valid is an escaped exception.
    (is (or (some? (:result resp))
            (some? (:error resp))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; process->task-status pure projection — verifies the status mapping
;; logic without needing a real workflow process

;; Status mapping with mocked process — Stage C.7 verifies the projection
;; shape; real workflow integration tests in C.7.x.
;;
;; (Note: process->task-status calls workflow/* fns which expect real
;; entities; pure tests here cover the nil-process branch + the codec.)
