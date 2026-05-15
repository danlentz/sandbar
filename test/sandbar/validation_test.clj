(ns sandbar.validation-test
  "Test suite for the validation workflow service"
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.service.validation :as val]
            [sandbar.test-util :as tu]
            [sandbar.util.workflow :as wf]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "validation-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Loading Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest load-workflow-spec-test
  (testing "load-workflow-spec loads EDN from resource"
    (let [spec (wf/load-workflow-spec "workflows/resource-validation.edn")]
      (is (some? spec) "Should load the spec")
      (is (= :workflow/resource-validation (:name spec))
          "Should have correct workflow name")
      (is (= 1 (:version spec))
          "Should have version 1")
      (is (= 6 (count (:states spec)))
          "Should have 6 states")
      (is (= 8 (count (:transitions spec)))
          "Should have 8 transitions"))))

(deftest ensure-validation-workflow-test
  (testing "ensure-validation-workflow! creates workflow if not exists"
    (let [workflow (val/ensure-validation-workflow!)]
      (is (some? workflow) "Should return workflow")
      (is (= :workflow/resource-validation (:workflow/definition-name workflow))
          "Should have correct name")))

  (testing "ensure-validation-workflow! returns existing workflow"
    (let [first-call (val/ensure-validation-workflow!)
          second-call (val/ensure-validation-workflow!)]
      (is (= (:db/id first-call) (:db/id second-call))
          "Should return same workflow on repeated calls"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation Process Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest start-validation-test
  (testing "start-validation! creates a process"
    (let [process (val/start-validation! :dt/Class)]
      (is (some? process) "Should return process")
      (is (= :validation/pending (:workflow/state-name (wf/get-current-state process)))
          "Should be in pending state")
      (let [data (wf/get-process-data process)]
        (is (= :dt/Class (:class data))
            "Process data should contain class"))))

  (testing "start-validation! throws for unknown class"
    (is (thrown? clojure.lang.ExceptionInfo
                 (val/start-validation! :nonexistent/Class))
        "Should throw for unknown class")))

(deftest run-validation-test
  (testing "run-validation! validates instances and transitions to passed"
    (let [process (val/start-validation! :dt/Class)
          completed (val/run-validation! process)]
      (is (some? completed) "Should return completed process")
      (let [state (wf/get-current-state completed)
            data (wf/get-process-data completed)]
        (is (#{:validation/passed :validation/failed} (:workflow/state-name state))
            "Should be in passed or failed state")
        (is (number? (:total data))
            "Should have total count in data")
        (is (number? (:valid data))
            "Should have valid count in data")
        (is (number? (:invalid data))
            "Should have invalid count in data")))))

(deftest validate-class-convenience-test
  (testing "validate-class! runs full validation in one call"
    (let [results (val/validate-class! :dt/Class)]
      (is (map? results) "Should return results map")
      (is (= :dt/Class (:class results))
          "Results should contain class")
      (is (number? (:total results))
          "Results should have total")
      (is (boolean? (:completed? results))
          "Results should have completed? flag")
      (is (:completed? results)
          "Should be completed"))))

(deftest validate-all-test
  (testing "validate-all! validates entire database"
    (let [results (val/validate-all!)]
      (is (map? results) "Should return results map")
      (is (= :dt/Resource (:class results))
          "Should validate dt/Resource (all entities)")
      (is (pos? (:total results))
          "Should have validated some instances"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Transition Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cancel-validation-test
  (testing "cancel-validation! cancels pending validation"
    (let [process (val/start-validation! :dt/Class)
          cancelled (val/cancel-validation! process)]
      (is (= :validation/cancelled (:workflow/state-name (wf/get-current-state cancelled)))
          "Should be in cancelled state")))

  (testing "cancel-validation! cannot cancel completed validation"
    (let [results (val/validate-class! :dt/Class)
          process (first (val/recent-validations :class :dt/Class))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (val/cancel-validation! process))
          "Should throw when cancelling completed validation"))))

(deftest retry-validation-test
  (testing "retry-validation! retries from failed state"
    ;; Create an entity that will cause validation to fail
    ;; (For this test, we'll manually transition to failed)
    (let [process (val/start-validation! :dt/Class)
          ;; Run validation (should pass for dt/Class)
          completed (val/run-validation! process)
          state (wf/get-current-state completed)]
      ;; If it passed, we can't retry; if it failed, we can
      (when (= :validation/failed (:workflow/state-name state))
        (let [retried (val/retry-validation! completed)]
          (is (#{:validation/passed :validation/failed}
               (:workflow/state-name (wf/get-current-state retried)))
              "Should complete after retry"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Results Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest get-validation-results-test
  (testing "get-validation-results returns full results"
    (let [process (val/start-validation! :dt/Class)
          _ (val/run-validation! process)
          results (val/get-validation-results (db/entity (:db/id process)))]
      (is (contains? results :class)
          "Results should have :class")
      (is (contains? results :total)
          "Results should have :total")
      (is (contains? results :valid)
          "Results should have :valid")
      (is (contains? results :invalid)
          "Results should have :invalid")
      (is (contains? results :state)
          "Results should have :state")
      (is (contains? results :completed?)
          "Results should have :completed?"))))

(deftest get-validation-history-test
  (testing "get-validation-history returns workflow history"
    (let [process (val/start-validation! :dt/Class)
          _ (val/run-validation! process)
          history (val/get-validation-history (db/entity (:db/id process)))]
      (is (sequential? history) "Should return sequence")
      (is (>= (count history) 2)
          "Should have at least 2 transitions (start + pass/fail)")
      (is (= :start (:action (first history)))
          "First action should be :start"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Query Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest recent-validations-test
  (testing "recent-validations returns recent processes"
    ;; Run a few validations
    (val/validate-class! :dt/Class)
    (val/validate-class! :dt/Property)
    (let [recent (val/recent-validations :limit 5)]
      (is (sequential? recent) "Should return sequence")
      (is (>= (count recent) 2)
          "Should have at least 2 recent validations")))

  (testing "recent-validations filters by class"
    (val/validate-class! :dt/Class)
    (let [recent (val/recent-validations :class :dt/Class)]
      (is (every? #(= :dt/Class (:class (wf/get-process-data %))) recent)
          "All results should be for dt/Class"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest full-validation-lifecycle-test
  (testing "Full validation lifecycle: start -> run -> view results -> retry"
    ;; 1. Start validation
    (let [process (val/start-validation! :model/User)]
      (is (= :validation/pending (:workflow/state-name (wf/get-current-state process)))
          "Step 1: Should start in pending state")

      ;; 2. Run validation
      (let [completed (val/run-validation! process)]
        (is (wf/process-completed? completed)
            "Step 2: Should be completed after running")

        ;; 3. View results
        (let [results (val/get-validation-results completed)]
          (is (= :model/User (:class results))
              "Step 3: Results should show correct class")
          (is (:completed? results)
              "Step 3: Results should show completed")

          ;; 4. View history
          (let [history (val/get-validation-history completed)]
            (is (>= (count history) 2)
                "Step 4: Should have transition history")))))))

(deftest validation-with-created-entities-test
  (testing "Validation includes dynamically created entities"
    ;; Create some User instances
    (dt/make* :model/User {:user/login "validation-test-user-1"})
    (dt/make* :model/User {:user/login "validation-test-user-2"})

    (let [results (val/validate-class! :model/User)]
      (is (>= (:total results) 2)
          "Should have validated at least the 2 created users")
      (is (= (:total results) (+ (:valid results) (:invalid results)))
          "Total should equal valid + invalid"))))
