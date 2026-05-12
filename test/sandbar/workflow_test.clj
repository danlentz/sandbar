(ns sandbar.workflow-test
  "Comprehensive tests for the workflow state machine system.

   Tests cover:
   - Schema and class hierarchy validation
   - State and transition definitions
   - Workflow definition assembly
   - Process creation and state tracking
   - Transition execution with guards
   - History tracking
   - State queries and statistics

   Schema defined in schema/workflow.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tasks :as mcp-tasks]
            [sandbar.test-util :as tu]
            [sandbar.util.workflow :as wf])
  (:import [java.util Date]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "workflow-test"
                                               :extra-schema [:workflow]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Guards and Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn payment-received?
  "Guard: check if payment has been received"
  [process context]
  (boolean (:payment-confirmed? context)))

(defn inventory-available?
  "Guard: check if inventory is available"
  [process context]
  (boolean (:in-stock? context)))

(defn high-value-order?
  "Guard: check if order value exceeds threshold"
  [process context]
  (> (or (:order-value context) 0) 100))

(def notification-log (atom []))

(defn send-notification
  "Side effect: log a notification"
  [process context]
  (swap! notification-log conj
         {:process (:db/id process)
          :action (:action context)
          :actor (:db/id (:actor context))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures: Create Standard Workflows
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-order-workflow!
  "Create the standard order fulfillment workflow for testing"
  []
  (wf/define-workflow! :workflow/order-fulfillment
    {:states [{:name :order/pending :label "Pending" :initial? true}
              {:name :order/confirmed :label "Confirmed"}
              {:name :order/paid :label "Paid"}
              {:name :order/shipped :label "Shipped"}
              {:name :order/delivered :label "Delivered" :terminal? true :terminal-kind :success}
              {:name :order/cancelled :label "Cancelled" :terminal? true :terminal-kind :cancel}
              {:name :order/refunded :label "Refunded" :terminal? true :terminal-kind :cancel}]
     :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
                   {:name :pay :from :order/confirmed :to :order/paid
                    :guard 'sandbar.workflow-test/payment-received?}
                   {:name :ship :from :order/paid :to :order/shipped
                    :guard 'sandbar.workflow-test/inventory-available?
                    :on-transition 'sandbar.workflow-test/send-notification}
                   {:name :deliver :from :order/shipped :to :order/delivered}
                   {:name :cancel :from :order/pending :to :order/cancelled}
                   {:name :cancel :from :order/confirmed :to :order/cancelled
                    :requires-reason? true}
                   {:name :refund :from :order/paid :to :order/refunded
                    :requires-reason? true}]
     :version 1}))

(defn create-ticket-workflow!
  "Create a support ticket workflow for testing"
  []
  (wf/define-workflow! :workflow/support-ticket
    {:states [{:name :ticket/new :label "New" :initial? true}
              {:name :ticket/open :label "Open"}
              {:name :ticket/in-progress :label "In Progress"}
              {:name :ticket/resolved :label "Resolved" :terminal? true :terminal-kind :success}
              {:name :ticket/closed :label "Closed" :terminal? true :terminal-kind :success}]
     :transitions [{:name :triage :from :ticket/new :to :ticket/open}
                   {:name :assign :from :ticket/open :to :ticket/in-progress}
                   {:name :resolve :from :ticket/in-progress :to :ticket/resolved}
                   {:name :reopen :from :ticket/resolved :to :ticket/open}
                   {:name :close :from :ticket/resolved :to :ticket/closed}
                   {:name :close :from :ticket/open :to :ticket/closed
                    :requires-reason? true}]}))

(defn create-test-subject!
  "Create a simple entity to serve as workflow subject"
  []
  ;; Use a User entity as the subject since we have auth schema loaded
  (dt/make :auth/User
    {:auth/username (str "order-" (System/currentTimeMillis) "-" (rand-int 10000))
     :auth/email (str (System/currentTimeMillis) "-" (rand-int 10000) "@test.com")
     :auth/password-hash "test"
     :auth/principal-name "Test Order"
     :auth/active? true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 1: Schema and Class Hierarchy Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest workflow-class-hierarchy-test
  (testing "Core workflow classes exist"
    (is (some? (db/entity :workflow/State)) "State class should exist")
    (is (some? (db/entity :workflow/Transition)) "Transition class should exist")
    (is (some? (db/entity :workflow/Definition)) "Definition class should exist")
    (is (some? (db/entity :workflow/Process)) "Process class should exist")
    (is (some? (db/entity :workflow/History)) "History class should exist"))

  (testing "All workflow classes are concrete"
    (doseq [cls [:workflow/State :workflow/Transition :workflow/Definition
                 :workflow/Process :workflow/History]]
      (is (not (dt/abstract? cls)) (str cls " should be concrete"))))

  (testing "Classes inherit from dt/Ref"
    (doseq [cls [:workflow/State :workflow/Transition :workflow/Definition
                 :workflow/Process :workflow/History]]
      (is (dt/subclass-of? :dt/Ref cls) (str cls " should extend dt/Ref")))))

(deftest workflow-property-test
  (testing "State properties"
    (let [slots (dt/slots-of :workflow/State)]
      (is (contains? slots :workflow/state-name) "States have names")
      (is (contains? slots :workflow/state-label) "States have human labels")
      (is (contains? slots :workflow/terminal?) "States know if they're terminal")
      (is (contains? slots :workflow/initial?) "States know if they're initial")))

  (testing "Transition properties"
    (let [slots (dt/slots-of :workflow/Transition)]
      (is (contains? slots :workflow/transition-name) "Transitions have action names")
      (is (contains? slots :workflow/from-state) "Transitions have origin")
      (is (contains? slots :workflow/to-state) "Transitions have destination")
      (is (contains? slots :workflow/guard) "Transitions can have guards")
      (is (contains? slots :workflow/on-transition) "Transitions can have side effects")))

  (testing "Definition properties"
    (let [slots (dt/slots-of :workflow/Definition)]
      (is (contains? slots :workflow/definition-name) "Definitions have unique names")
      (is (contains? slots :workflow/states) "Definitions contain states")
      (is (contains? slots :workflow/transitions) "Definitions contain transitions")
      (is (contains? slots :workflow/version) "Definitions are versioned")))

  (testing "Process properties"
    (let [slots (dt/slots-of :workflow/Process)]
      (is (contains? slots :workflow/definition) "Process knows its workflow")
      (is (contains? slots :workflow/current-state) "Process tracks current state")
      (is (contains? slots :workflow/subject) "Process attached to subject entity")
      (is (contains? slots :workflow/history) "Process maintains transition history")))

  (testing "History properties"
    (let [slots (dt/slots-of :workflow/History)]
      (is (contains? slots :workflow/history-from) "History records origin state")
      (is (contains? slots :workflow/history-to) "History records destination state")
      (is (contains? slots :workflow/history-action) "History records the action")
      (is (contains? slots :workflow/history-timestamp) "History records when")
      (is (contains? slots :workflow/history-actor) "History records who"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 2: State Creation Tests (Using dt/make directly)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-states-direct-test
  (testing "Create an initial state directly"
    (let [state (dt/make :workflow/State
                  {:workflow/state-name :order/pending
                   :workflow/state-label "Pending"
                   :workflow/initial? true
                   :workflow/terminal? false})]
      (is (some? state) "State created")
      (is (dt/instance-of? :workflow/State state))
      (is (= :order/pending (:workflow/state-name state)))
      (is (true? (:workflow/initial? state)) "Marked as initial")))

  (testing "Create a terminal state directly"
    (let [state (dt/make :workflow/State
                  {:workflow/state-name :order/delivered
                   :workflow/state-label "Delivered"
                   :workflow/initial? false
                   :workflow/terminal? true})]
      (is (true? (:workflow/terminal? state)) "Marked as terminal"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 3: State Creation via API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest state-creation-api-test
  (testing "Create a basic state via API"
    (let [state (wf/create-state! :test/state-1 :label "Test State")]
      (is (some? state))
      (is (= :test/state-1 (:workflow/state-name state)))
      (is (= "Test State" (:workflow/state-label state)))
      (is (nil? (:workflow/initial? state)))
      (is (nil? (:workflow/terminal? state)))))

  (testing "Create an initial state via API"
    (let [state (wf/create-state! :test/initial :initial? true)]
      (is (true? (:workflow/initial? state)))
      (is (true? (wf/state-initial? state)))))

  (testing "Create a terminal state via API"
    (let [state (wf/create-state! :test/terminal :terminal? true :terminal-kind :success)]
      (is (true? (:workflow/terminal? state)))
      (is (true? (wf/state-terminal? state)))
      (is (= :success (:workflow/terminal-kind state)))))

  (testing "Create state with metadata"
    (let [state (wf/create-state! :test/with-meta
                                   :metadata {:color "green" :priority 1})]
      (is (some? (:workflow/state-metadata state)))
      (let [meta-data (read-string (:workflow/state-metadata state))]
        (is (= "green" (:color meta-data)))))))

(deftest find-state-test
  (testing "Find existing state"
    (wf/create-state! :test/findable :label "Findable")
    (let [found (wf/find-state :test/findable)]
      (is (some? found))
      (is (= "Findable" (:workflow/state-label found)))))

  (testing "Find non-existent state returns nil"
    (is (nil? (wf/find-state :test/nonexistent)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 4: Transition Creation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest transition-creation-test
  (testing "Create a basic transition"
    (let [from (wf/create-state! :trans-test/from)
          to (wf/create-state! :trans-test/to)
          transition (wf/create-transition! :approve from to)]
      (is (some? transition))
      (is (= :approve (:workflow/transition-name transition)))))

  (testing "Create transition with guard"
    (let [from (wf/create-state! :guard-test/from)
          to (wf/create-state! :guard-test/to)
          transition (wf/create-transition! :proceed from to
                                             :guard 'sandbar.workflow-test/payment-received?)]
      (is (= 'sandbar.workflow-test/payment-received? (:workflow/guard transition)))))

  (testing "Create transition with requires-reason"
    (let [from (wf/create-state! :reason-test/from)
          to (wf/create-state! :reason-test/to)
          transition (wf/create-transition! :cancel from to
                                             :requires-reason? true)]
      (is (true? (:workflow/requires-reason? transition)))))

  (testing "Create transition using state keywords"
    (wf/create-state! :keyword-test/start)
    (wf/create-state! :keyword-test/end)
    (let [transition (wf/create-transition! :go
                                             :keyword-test/start
                                             :keyword-test/end)]
      (is (some? transition))))

  (testing "Transition fails for non-existent state"
    (is (thrown? Exception
                 (wf/create-transition! :fail
                                         :nonexistent/state
                                         :nonexistent/other)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 5: Workflow Definition Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest workflow-definition-test
  (testing "Define complete workflow"
    (let [workflow (create-order-workflow!)]
      (is (some? workflow))
      (is (= :workflow/order-fulfillment (:workflow/definition-name workflow)))
      (is (= 1 (:workflow/version workflow)))

      ;; Check states
      (let [states (wf/get-workflow-states workflow)]
        (is (= 7 (count states)))
        (is (some #(= :order/pending (:workflow/state-name %)) states))
        (is (some #(= :order/delivered (:workflow/state-name %)) states)))

      ;; Check transitions
      (let [transitions (wf/get-workflow-transitions workflow)]
        (is (= 7 (count transitions)))
        (is (some #(= :confirm (:workflow/transition-name %)) transitions))
        (is (some #(= :ship (:workflow/transition-name %)) transitions)))))

  (testing "Get initial state"
    (let [workflow (wf/find-workflow :workflow/order-fulfillment)
          initial (wf/get-initial-state workflow)]
      (is (some? initial))
      (is (= :order/pending (:workflow/state-name initial)))))

  (testing "Get terminal states"
    (let [workflow (wf/find-workflow :workflow/order-fulfillment)
          terminals (wf/get-terminal-states workflow)]
      (is (= 3 (count terminals)))
      (is (some #(= :order/delivered (:workflow/state-name %)) terminals))
      (is (some #(= :order/cancelled (:workflow/state-name %)) terminals))
      (is (some #(= :order/refunded (:workflow/state-name %)) terminals)))))

(deftest find-workflow-test
  (testing "Find existing workflow"
    (create-order-workflow!)
    (let [found (wf/find-workflow :workflow/order-fulfillment)]
      (is (some? found))
      (is (= 1 (:workflow/version found)))))

  (testing "Find non-existent workflow returns nil"
    (is (nil? (wf/find-workflow :workflow/nonexistent)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 6: Process Lifecycle Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest process-creation-test
  (testing "Start a new process"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      (is (some? process))
      (is (some? (:workflow/started-at process)))
      (is (nil? (:workflow/completed-at process)))

      ;; Should start in initial state
      (let [current (wf/get-current-state process)]
        (is (= :order/pending (:workflow/state-name current))))))

  (testing "Start process with workflow keyword"
    (create-order-workflow!)
    (let [subject (create-test-subject!)
          process (wf/start-process! :workflow/order-fulfillment subject)]
      (is (some? process))))

  (testing "Start process with data"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject
                                      :data {:order-value 250 :items 3})]
      (let [data (wf/get-process-data process)]
        (is (= 250 (:order-value data)))
        (is (= 3 (:items data))))))

  (testing "Cannot start process without initial state"
    (let [bad-workflow (wf/define-workflow! :workflow/no-initial
                         {:states [{:name :bad/state-1}
                                   {:name :bad/state-2}]
                          :transitions [{:name :go :from :bad/state-1 :to :bad/state-2}]})
          subject (create-test-subject!)]
      (is (thrown? Exception
                   (wf/start-process! bad-workflow subject))))))

(deftest process-queries-test
  (testing "Find process by subject"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          found (wf/find-process-by-subject subject)]
      (is (= 1 (count found)))
      (is (= (:db/id process) (:db/id (first found))))))

  (testing "Multiple processes for same subject"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)]
      (wf/start-process! workflow subject)
      (wf/start-process! workflow subject)
      (let [found (wf/find-process-by-subject subject)]
        (is (= 2 (count found)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 7: Basic Transition Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest basic-transition-test
  (testing "Execute a simple transition"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          ;; Confirm the order
          updated (wf/transition! process :confirm)]
      (is (some? updated))
      (let [current (wf/get-current-state updated)]
        (is (= :order/confirmed (:workflow/state-name current))))))

  (testing "Multiple transitions"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      ;; pending -> confirmed -> paid (with guard context) -> shipped
      (let [p1 (wf/transition! process :confirm)
            p2 (wf/transition! p1 :pay :context {:payment-confirmed? true})
            p3 (wf/transition! p2 :ship :context {:in-stock? true})]
        (let [current (wf/get-current-state p3)]
          (is (= :order/shipped (:workflow/state-name current)))))))

  (testing "Transition to terminal state completes process"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      (let [updated (wf/transition! process :cancel)]
        (is (some? (:workflow/completed-at updated)))
        (is (wf/process-completed? updated))
        (is (wf/process-in-terminal-state? updated))))))

(deftest invalid-transition-test
  (testing "Cannot transition with invalid action"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      (is (thrown-with-msg? Exception #"Transition not found"
                            (wf/transition! process :ship)))))

  (testing "Cannot skip states"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      ;; Try to ship without confirming first
      (is (thrown? Exception
                   (wf/transition! process :deliver))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 8: Guard Function Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest guard-function-test
  (testing "Transition blocked when guard fails"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm)]
      ;; Try to pay without payment confirmation
      (is (thrown-with-msg? Exception #"Guard condition not met"
                            (wf/transition! confirmed :pay)))))

  (testing "Transition allowed when guard passes"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm)]
      ;; Pay with payment confirmation
      (let [paid (wf/transition! confirmed :pay
                                  :context {:payment-confirmed? true})]
        (is (= :order/paid (:workflow/state-name (wf/get-current-state paid)))))))

  (testing "Available transitions respects guards"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm)]
      ;; Without payment context, :pay should not be available
      (let [available (wf/available-transitions confirmed)]
        (is (not (some #(= :pay (:workflow/transition-name %)) available))))

      ;; With payment context, :pay should be available
      (let [available (wf/available-transitions confirmed
                                                  :context {:payment-confirmed? true})]
        (is (some #(= :pay (:workflow/transition-name %)) available))))))

(deftest can-transition-test
  (testing "can-transition? returns true for valid transition"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      (is (wf/can-transition? process :confirm))
      (is (wf/can-transition? process :cancel))
      (is (not (wf/can-transition? process :ship)))))

  (testing "can-transition? respects guards"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm)]
      (is (not (wf/can-transition? confirmed :pay)))
      (is (wf/can-transition? confirmed :pay :context {:payment-confirmed? true})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 9: Requires Reason Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest requires-reason-test
  (testing "Transition fails without required reason"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm)]
      ;; Cancel from confirmed requires reason
      (is (thrown-with-msg? Exception #"requires a reason"
                            (wf/transition! confirmed :cancel)))))

  (testing "Transition succeeds with required reason"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm)]
      (let [cancelled (wf/transition! confirmed :cancel
                                       :reason "Customer requested cancellation")]
        (is (= :order/cancelled (:workflow/state-name (wf/get-current-state cancelled))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 10: On-Transition Side Effects
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest on-transition-test
  (testing "on-transition function is called"
    (reset! notification-log [])
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          user (create-test-subject!)
          process (wf/start-process! workflow subject)]
      ;; Go through the workflow to trigger ship transition
      (let [confirmed (wf/transition! process :confirm)
            paid (wf/transition! confirmed :pay :context {:payment-confirmed? true})]
        (wf/transition! paid :ship
                        :context {:in-stock? true}
                        :actor user)
        ;; Check notification was logged
        (is (= 1 (count @notification-log)))
        (let [notif (first @notification-log)]
          (is (= (:db/id user) (:actor notif))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 11: History Tracking Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest history-tracking-test
  (testing "History is recorded for each transition"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          p1 (wf/transition! process :confirm)
          p2 (wf/transition! p1 :pay :context {:payment-confirmed? true})
          p3 (wf/transition! p2 :ship :context {:in-stock? true})
          history (wf/get-process-history p3)]
      (is (= 3 (count history)))))

  (testing "History contains transition details"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          actor (create-test-subject!)
          process (wf/start-process! workflow subject)
          confirmed (wf/transition! process :confirm :actor actor)
          history (wf/get-process-history confirmed)
          entry (first history)]
      (is (some? (:workflow/history-timestamp entry)))
      (is (= :confirm (:workflow/history-action entry)))))

  (testing "Get readable history"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)
          p1 (wf/transition! process :confirm :reason "Order looks good")
          readable (wf/get-readable-history p1)]
      (is (= 1 (count readable)))
      (let [entry (first readable)]
        (is (= :confirm (:action entry)))
        (is (= :order/pending (:from entry)))
        (is (= :order/confirmed (:to entry)))
        (is (= "Order looks good" (:reason entry)))))))

(deftest history-direct-creation-test
  (let [open (dt/make :workflow/State
               {:workflow/state-name :ticket/open
                :workflow/state-label "Open"
                :workflow/initial? true})
        in-progress (dt/make :workflow/State
                      {:workflow/state-name :ticket/in-progress
                       :workflow/state-label "In Progress"})
        resolved (dt/make :workflow/State
                   {:workflow/state-name :ticket/resolved
                    :workflow/state-label "Resolved"
                    :workflow/terminal? true})]

    (testing "Create history entries directly"
      (let [history1 (dt/make :workflow/History
                       {:workflow/history-from (:db/id open)
                        :workflow/history-to (:db/id in-progress)
                        :workflow/history-action :start-work
                        :workflow/history-timestamp (Date.)
                        :workflow/history-reason "Starting investigation"})
            history2 (dt/make :workflow/History
                       {:workflow/history-from (:db/id in-progress)
                        :workflow/history-to (:db/id resolved)
                        :workflow/history-action :resolve
                        :workflow/history-timestamp (Date.)
                        :workflow/history-reason "Fixed by turning it off and on again"})]
        (is (some? history1))
        (is (some? history2))
        (is (= :start-work (:workflow/history-action history1)))
        (is (= :resolve (:workflow/history-action history2)))
        (is (string? (:workflow/history-reason history2)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 12: Process State Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest process-state-queries-test
  (testing "Find processes in specific state"
    (let [workflow (create-order-workflow!)]
      ;; Create processes in different states
      (let [p1 (wf/start-process! workflow (create-test-subject!))
            p2 (wf/start-process! workflow (create-test-subject!))
            p3 (wf/start-process! workflow (create-test-subject!))]
        (wf/transition! p1 :confirm)
        (wf/transition! p2 :confirm)

        (let [pending (wf/processes-in-state :order/pending)
              confirmed (wf/processes-in-state :order/confirmed)]
          (is (= 1 (count pending)))
          (is (= 2 (count confirmed)))))))

  (testing "Find active processes"
    ;; Create a new workflow to isolate this test's processes
    (let [workflow (wf/define-workflow! :workflow/active-test
                     {:states [{:name :active-test/pending :initial? true}
                               {:name :active-test/done :terminal? true :terminal-kind :success}]
                      :transitions [{:name :finish :from :active-test/pending :to :active-test/done}]})]
      (let [p1 (wf/start-process! workflow (create-test-subject!))
            p2 (wf/start-process! workflow (create-test-subject!))]
        ;; Complete one process
        (wf/transition! p1 :finish)

        (let [active (wf/active-processes :workflow :workflow/active-test)]
          (is (= 1 (count active)))
          (is (= (:db/id p2) (:db/id (first active))))))))

  (testing "Find completed processes"
    ;; Create a new workflow to isolate this test's processes
    (let [workflow (wf/define-workflow! :workflow/completed-test
                     {:states [{:name :completed-test/pending :initial? true}
                               {:name :completed-test/done :terminal? true :terminal-kind :success}]
                      :transitions [{:name :finish :from :completed-test/pending :to :completed-test/done}]})]
      (let [p1 (wf/start-process! workflow (create-test-subject!))
            p2 (wf/start-process! workflow (create-test-subject!))]
        (wf/transition! p1 :finish)
        (wf/transition! p2 :finish)

        (let [completed (wf/completed-processes :workflow :workflow/completed-test)]
          (is (= 2 (count completed))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 13: Multiple Workflows
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest multiple-workflows-test
  (testing "Multiple workflows can coexist"
    (let [order-wf (create-order-workflow!)
          ticket-wf (create-ticket-workflow!)
          order-subject (create-test-subject!)
          ticket-subject (create-test-subject!)
          order-process (wf/start-process! order-wf order-subject)
          ticket-process (wf/start-process! ticket-wf ticket-subject)]
      ;; Order workflow transitions
      (let [order-confirmed (wf/transition! order-process :confirm)]
        (is (= :order/confirmed
               (:workflow/state-name (wf/get-current-state order-confirmed)))))

      ;; Ticket workflow transitions
      (let [ticket-triaged (wf/transition! ticket-process :triage)]
        (is (= :ticket/open
               (:workflow/state-name (wf/get-current-state ticket-triaged)))))))

  (testing "Query processes by workflow"
    ;; Create unique workflows for this test to avoid interference from other tests
    (let [order-wf (wf/define-workflow! :workflow/multi-test-order
                     {:states [{:name :mto/pending :initial? true}
                               {:name :mto/done :terminal? true :terminal-kind :success}]
                      :transitions [{:name :finish :from :mto/pending :to :mto/done}]})
          ticket-wf (wf/define-workflow! :workflow/multi-test-ticket
                      {:states [{:name :mtt/new :initial? true}
                                {:name :mtt/closed :terminal? true :terminal-kind :success}]
                       :transitions [{:name :close :from :mtt/new :to :mtt/closed}]})]
      (wf/start-process! order-wf (create-test-subject!))
      (wf/start-process! order-wf (create-test-subject!))
      (wf/start-process! ticket-wf (create-test-subject!))

      (let [order-processes (wf/active-processes :workflow :workflow/multi-test-order)
            ticket-processes (wf/active-processes :workflow :workflow/multi-test-ticket)]
        (is (= 2 (count order-processes)))
        (is (= 1 (count ticket-processes)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 14: Statistics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest workflow-statistics-test
  (testing "Get workflow statistics"
    (let [workflow (create-order-workflow!)]
      ;; Create processes in various states
      (let [p1 (wf/start-process! workflow (create-test-subject!))
            p2 (wf/start-process! workflow (create-test-subject!))
            p3 (wf/start-process! workflow (create-test-subject!))
            p4 (wf/start-process! workflow (create-test-subject!))]
        (wf/transition! p1 :confirm)
        (wf/transition! p2 :confirm)
        (wf/transition! p3 :cancel)

        (let [stats (wf/workflow-stats :workflow/order-fulfillment)]
          (is (= 4 (:total stats)))
          (is (= 1 (:completed stats)))
          (is (= 3 (:active stats)))
          (is (map? (:by-state stats))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 15: Same-Named Transitions from Different States
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest same-named-transitions-test
  (testing "Same action name from different states"
    (let [workflow (create-order-workflow!)
          process (wf/start-process! workflow (create-test-subject!))]
      ;; Cancel from pending (doesn't require reason)
      (let [cancelled (wf/transition! process :cancel)]
        (is (= :order/cancelled
               (:workflow/state-name (wf/get-current-state cancelled))))))

    (let [workflow (wf/find-workflow :workflow/order-fulfillment)
          process (wf/start-process! workflow (create-test-subject!))
          confirmed (wf/transition! process :confirm)]
      ;; Cancel from confirmed (requires reason)
      (is (thrown? Exception
                   (wf/transition! confirmed :cancel)))
      (let [cancelled (wf/transition! confirmed :cancel
                                       :reason "Changed mind")]
        (is (= :order/cancelled
               (:workflow/state-name (wf/get-current-state cancelled))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 16: Edge Cases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edge-cases-test
  (testing "Cannot transition completed process"
    (let [workflow (create-order-workflow!)
          process (wf/start-process! workflow (create-test-subject!))
          cancelled (wf/transition! process :cancel)]
      ;; Try to do something with completed process
      (is (thrown? Exception
                   (wf/transition! cancelled :confirm)))))

  (testing "Get subject from process"
    (let [workflow (create-order-workflow!)
          subject (create-test-subject!)
          process (wf/start-process! workflow subject)]
      (let [retrieved (wf/get-process-subject process)]
        (is (= (:db/id subject) (:db/id retrieved))))))

  (testing "Get workflow from process"
    (let [workflow (create-order-workflow!)
          process (wf/start-process! workflow (create-test-subject!))]
      (let [retrieved (wf/get-process-workflow process)]
        (is (= :workflow/order-fulfillment (:workflow/definition-name retrieved))))))

  (testing "Find process by id"
    (let [workflow (create-order-workflow!)
          process (wf/start-process! workflow (create-test-subject!))
          found (wf/find-process (:db/id process))]
      (is (some? found))
      (is (= (:db/id process) (:db/id found)))))

  (testing "Find non-existent process returns nil"
    (is (nil? (wf/find-process 999999)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 17: Complete Order Workflow Integration Test
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest complete-order-workflow-test
  (testing "Complete happy path: pending -> confirmed -> paid -> shipped -> delivered"
    (reset! notification-log [])
    (let [workflow (create-order-workflow!)
          order (create-test-subject!)
          warehouse-user (create-test-subject!)
          delivery-user (create-test-subject!)
          process (wf/start-process! workflow order
                                      :data {:order-value 150 :items 3})]
      ;; Verify initial state
      (is (= :order/pending (:workflow/state-name (wf/get-current-state process))))

      ;; Confirm order
      (let [confirmed (wf/transition! process :confirm)]
        (is (= :order/confirmed (:workflow/state-name (wf/get-current-state confirmed))))

        ;; Pay for order
        (let [paid (wf/transition! confirmed :pay
                                    :context {:payment-confirmed? true})]
          (is (= :order/paid (:workflow/state-name (wf/get-current-state paid))))

          ;; Ship order (triggers notification)
          (let [shipped (wf/transition! paid :ship
                                         :context {:in-stock? true}
                                         :actor warehouse-user
                                         :reason "Package ready for carrier")]
            (is (= :order/shipped (:workflow/state-name (wf/get-current-state shipped))))
            (is (= 1 (count @notification-log)))

            ;; Deliver order
            (let [delivered (wf/transition! shipped :deliver
                                             :actor delivery-user)]
              (is (= :order/delivered (:workflow/state-name (wf/get-current-state delivered))))
              (is (wf/process-completed? delivered))

              ;; Check history
              (let [history (wf/get-readable-history delivered)]
                (is (= 4 (count history)))
                (is (= [:confirm :pay :ship :deliver]
                       (mapv :action history)))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 18: API Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest workflow-api-test
  (testing "Workflow classes visible via API"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/workflow/State")]
      (is (= 200 status))
      (is (= :workflow/State (:class body)))))

  (testing "Definition slots include states and transitions"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/workflow/Definition/slots")]
      (is (= 200 status))
      ;; API returns slot objects with :ident key
      (let [slot-idents (set (map :ident (:slots body)))]
        (is (contains? slot-idents :workflow/states))
        (is (contains? slot-idents :workflow/transitions))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Part 19: F-B-002 ADR Acceptance Criteria — Workflow Cancellation +
;; Terminal-Outcome Semantics as :workflow/terminal-kind
;;
;; Per decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md
;; Validates E.1 (cancel-detection independent of ident name), E.2
;; (process->task-status projection for each kind), E.3 (validator
;; rejection paths).

(deftest terminal-kind-validator-rejects-terminal-without-kind
  (testing "create-state! throws when :terminal? true without :terminal-kind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"terminal\? true requires :terminal-kind"
          (wf/create-state! :fb002/bad-terminal :terminal? true))))

  (testing "create-state! throws when :terminal-kind is not a valid kind"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":terminal-kind must be one of"
          (wf/create-state! :fb002/bad-kind :terminal? true :terminal-kind :timeout))))

  (testing "create-state! throws when :terminal-kind set on non-terminal state"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":terminal-kind only meaningful"
          (wf/create-state! :fb002/non-terminal-with-kind :terminal-kind :success))))

  (testing "create-state! accepts each valid terminal-kind"
    (doseq [kind [:success :failure :cancel]]
      (let [state-name (keyword "fb002" (str "valid-" (name kind)))
            state      (wf/create-state! state-name :terminal? true :terminal-kind kind)]
        (is (= kind (:workflow/terminal-kind state)))
        (is (true? (:workflow/terminal? state)))))))

(deftest cancel-detection-independent-of-ident-name
  (testing "can-cancel? uses :workflow/terminal-kind :cancel, not 'cancel' string heuristic"
    ;; Workflow whose abort state ident does NOT contain "cancel" — would
    ;; have been uncancellable under the string-heuristic implementation;
    ;; the modeled :terminal-kind :cancel makes it cancellable.
    (let [workflow (wf/define-workflow! :workflow/fb002-aborted
                     {:states [{:name :fb002a/pending :initial? true}
                               {:name :fb002a/in-progress}
                               {:name :fb002a/done :terminal? true :terminal-kind :success}
                               {:name :fb002a/aborted :terminal? true :terminal-kind :cancel}]
                      :transitions [{:name :start  :from :fb002a/pending     :to :fb002a/in-progress}
                                    {:name :finish :from :fb002a/in-progress :to :fb002a/done}
                                    {:name :abort  :from :fb002a/in-progress :to :fb002a/aborted}]})
          subject  (create-test-subject!)
          process  (wf/start-process! workflow subject)
          started  (wf/transition! process :start)]
      (is (true? (wf/can-cancel? started))
          ":fb002a/aborted lacks 'cancel' substring; modeled terminal-kind makes it cancellable")
      (let [cancelled (wf/cancel-process! started)]
        (is (= :fb002a/aborted (:workflow/state-name (wf/get-current-state cancelled))))))))

(deftest cancel-detection-respects-terminal-kind-only
  (testing "can-cancel? returns false when no transition leads to a :cancel terminal"
    ;; Workflow with terminals named :cancel-like but classified differently —
    ;; ensures the lookup is on terminal-kind, not state name.
    (let [workflow (wf/define-workflow! :workflow/fb002-noncancel
                     {:states [{:name :fb002n/pending :initial? true}
                               {:name :fb002n/cancellation-info :terminal? true :terminal-kind :success}]
                      :transitions [{:name :go :from :fb002n/pending :to :fb002n/cancellation-info}]})
          subject  (create-test-subject!)
          process  (wf/start-process! workflow subject)]
      (is (false? (wf/can-cancel? process))
          ":fb002n/cancellation-info has 'cancel' in name but :terminal-kind :success — not cancellable")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support cancellation"
            (wf/cancel-process! process))))))

(deftest process->task-status-projects-each-terminal-kind
  ;; Reuses the order workflow (delivered=:success, cancelled=:cancel,
  ;; refunded=:cancel) + adds a failure-classified workflow.
  (let [success-wf  (wf/define-workflow! :workflow/fb002-success
                      {:states [{:name :fb002s/pending :initial? true}
                                {:name :fb002s/done :terminal? true :terminal-kind :success}]
                       :transitions [{:name :finish :from :fb002s/pending :to :fb002s/done}]})
        failure-wf  (wf/define-workflow! :workflow/fb002-failure
                      {:states [{:name :fb002f/pending :initial? true}
                                {:name :fb002f/errored :terminal? true :terminal-kind :failure}]
                       :transitions [{:name :fail :from :fb002f/pending :to :fb002f/errored}]})
        cancel-wf   (wf/define-workflow! :workflow/fb002-cancel
                      {:states [{:name :fb002c/pending :initial? true}
                                {:name :fb002c/stopped :terminal? true :terminal-kind :cancel}]
                       :transitions [{:name :stop :from :fb002c/pending :to :fb002c/stopped}]})
        success-proc (wf/transition! (wf/start-process! success-wf (create-test-subject!))
                                     :finish)
        failure-proc (wf/transition! (wf/start-process! failure-wf (create-test-subject!))
                                     :fail)
        cancel-proc  (wf/transition! (wf/start-process! cancel-wf (create-test-subject!))
                                     :stop)
        running-proc (wf/start-process! success-wf (create-test-subject!))]

    (testing ":success → \"completed\""
      (is (= "completed" (:status (mcp-tasks/process->task-status success-proc)))))

    (testing ":failure → \"failed\""
      (is (= "failed" (:status (mcp-tasks/process->task-status failure-proc)))))

    (testing ":cancel → \"cancelled\""
      (is (= "cancelled" (:status (mcp-tasks/process->task-status cancel-proc)))))

    (testing "non-terminal → \"running\""
      (is (= "running" (:status (mcp-tasks/process->task-status running-proc)))))

    (testing "nil process → \"missing\""
      (is (= "missing" (:status (mcp-tasks/process->task-status nil)))))

    (testing ":state ident preserved"
      (is (= :fb002s/done    (:state (mcp-tasks/process->task-status success-proc))))
      (is (= :fb002f/errored (:state (mcp-tasks/process->task-status failure-proc))))
      (is (= :fb002c/stopped (:state (mcp-tasks/process->task-status cancel-proc)))))))
