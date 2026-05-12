(ns sandbar.mcp.prompts-test
  "Tests for the MCP prompts layer — pure tests for naming-convention
   roundtrip. DB-backed tests (workflow enumeration + prompt rendering)
   land alongside test-db fixture wiring in C.6.x.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.6."
  (:require [clojure.test        :refer :all]
            [sandbar.mcp.prompts :as prompts]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Prompt naming roundtrip

(deftest workflow-ident-to-prompt-name
  (is (= "sandbar.workflow.validation.resource"
         (prompts/workflow-ident->prompt-name :validation/resource)))
  (is (= "sandbar.workflow.export.markdown"
         (prompts/workflow-ident->prompt-name :export/markdown))))

(deftest prompt-name-to-workflow-ident
  (is (= :validation/resource
         (prompts/prompt-name->workflow-ident "sandbar.workflow.validation.resource")))
  (is (= :export/markdown
         (prompts/prompt-name->workflow-ident "sandbar.workflow.export.markdown"))))

(deftest naming-roundtrips-cleanly
  (doseq [ident [:validation/resource :export/markdown :restore/sandbar
                 :mm/Memory.export :foo/bar]]
    (is (= ident
           (-> ident
               prompts/workflow-ident->prompt-name
               prompts/prompt-name->workflow-ident))
        (str "Roundtrip failed for " ident))))

(deftest prompt-name-rejects-non-workflow-shapes
  (is (nil? (prompts/prompt-name->workflow-ident "sandbar.class.dt.Class")))
  (is (nil? (prompts/prompt-name->workflow-ident "not.a.workflow")))
  (is (nil? (prompts/prompt-name->workflow-ident "sandbar.workflow"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; handle-get failure shapes (no DB required)

(deftest handle-get-rejects-invalid-prompt-name
  (let [resp (prompts/handle-get 1 {:name "not.a.workflow.prompt"})]
    (is (= -32602 (-> resp :error :code)))
    (is (re-find #"(?i)invalid prompt name" (-> resp :error :message)))))
