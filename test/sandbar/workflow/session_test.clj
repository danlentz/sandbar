(ns sandbar.workflow.session-test
  "Tests for the ι.2 session-workflow schema extension.

   Verifies:
   - 6 states exist after schema-load (4 pre-existing + 2 new)
   - 4 new transitions registered on :workflow/session
   - Guard fn `can-pause-maintenance?` returns expected boolean per context
   - Effect fns `on-pause-maintenance` / `on-resume-maintenance` / `on-fail`
     return well-shaped tx-data

   Per `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_session_workflow_substrate_design_fs_audit_scope_finalized_2026_05_25`."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]
            [sandbar.util.workflow :as wf]
            [sandbar.workflow.session :as session]))

;; Test-fixture loads :required-schema from config (which includes
;; :workflow-session per the ι.2 config.edn edit), populating the test DB with
;; :workflow/session + its 6 states + 9 transitions (5 existing semantically-
;; preserved + 4 new).  The fixture's :auth? false suppresses auth setup
;; (this test namespace doesn't exercise the auth path).
(use-fixtures :each (tu/make-test-db-fixture {:test-name "workflow-session-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema presence assertions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest workflow-session-has-six-states-after-extension
  (testing ":workflow/session declares 6 states after schema-load (Q.ι.6)"
    (let [paused-state (wf/find-state :session/paused)
          failed-state (wf/find-state :session/failed)]
      (is (some? paused-state)
          ":session/paused state should be present after schema-load")
      (is (some? failed-state)
          ":session/failed state should be present after schema-load")

      (is (false? (boolean (:workflow/terminal? paused-state)))
          ":session/paused is non-terminal (maintenance-mode pause)")

      (is (true? (:workflow/terminal? failed-state))
          ":session/failed is terminal (κ P8 hard-fail)")
      (is (= :failure (:workflow/terminal-kind failed-state))
          ":session/failed has terminal-kind :failure"))))

(deftest workflow-session-has-four-new-transitions
  (testing "4 new transitions reachable on :workflow/session"
    (let [pause-maintenance (wf/find-transition :session/pause-maintenance :session/active)
          resume-maintenance (wf/find-transition :session/resume-maintenance :session/paused)
          fail-from-active (wf/find-transition :session/fail :session/active)
          fail-from-paused (wf/find-transition :session/fail-from-paused :session/paused)]
      (is (some? pause-maintenance)
          ":session/pause-maintenance (active → paused) should exist")
      (is (some? resume-maintenance)
          ":session/resume-maintenance (paused → active) should exist")
      (is (some? fail-from-active)
          ":session/fail (active → failed) should exist")
      (is (some? fail-from-paused)
          ":session/fail-from-paused (paused → failed) should exist")

      (is (= :session/paused
             (:workflow/state-name (:workflow/to-state pause-maintenance)))
          ":session/pause-maintenance to-state is :session/paused")
      (is (= :session/active
             (:workflow/state-name (:workflow/to-state resume-maintenance)))
          ":session/resume-maintenance to-state is :session/active")
      (is (= :session/failed
             (:workflow/state-name (:workflow/to-state fail-from-active)))
          ":session/fail to-state is :session/failed"))))

(deftest workflow-session-version-bumped
  (testing ":workflow/session version is 2 after ι.2 extension"
    (let [wf-entity (db/entity :workflow/session)]
      (is (= 2 (:workflow/version wf-entity))
          ":workflow/version should be 2 after schema-load"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Guard fn behavior assertions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest can-pause-maintenance-permissive-when-no-actor
  (testing "can-pause-maintenance? allows transition when no actor in context (pre-actor-context-substrate)"
    (let [fake-process {:db/id 42 :workflow/subject {:db/id 99}}
          context {}]
      (is (true? (session/can-pause-maintenance? fake-process context))
          "Permissive default — allow pause when no actor supplied"))))

(deftest can-pause-maintenance-allows-session-owner
  (testing "can-pause-maintenance? allows transition when actor IS the session owner"
    (let [owner {:db/id 99}
          fake-process {:db/id 42 :workflow/subject owner}
          context {:actor owner}]
      (is (true? (session/can-pause-maintenance? fake-process context))
          "Session owner can always pause"))))

(deftest can-pause-maintenance-allows-actor-with-maintenance-scope
  (testing "can-pause-maintenance? allows transition when actor has :authorization/maintenance-mode"
    (let [fake-process {:db/id 42 :workflow/subject {:db/id 99}}
          actor {:db/id 77 :mm.actor/authorizations [:authorization/maintenance-mode]}
          context {:actor actor}]
      (is (true? (session/can-pause-maintenance? fake-process context))
          "Actor with maintenance-scope can pause any session"))))

(deftest can-pause-maintenance-denies-unauthorized-actor
  (testing "can-pause-maintenance? denies transition for unauthorized non-owner actor"
    (let [fake-process {:db/id 42 :workflow/subject {:db/id 99}}
          unauth-actor {:db/id 88 :mm.actor/authorizations []}
          context {:actor unauth-actor}]
      (is (false? (session/can-pause-maintenance? fake-process context))
          "Unauthorized non-owner is denied"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Effect fn shape assertions (return tx-data)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest on-pause-maintenance-returns-tx-data-recording-from-state
  (testing "on-pause-maintenance returns tx-data recording the from-state"
    (let [fake-process {:db/id 42
                        :workflow/current-state {:workflow/state-name :session/active}}
          context {:reason "scheduled maintenance window"}
          tx-data (session/on-pause-maintenance fake-process context)]
      (is (vector? tx-data) "Effect returns tx-data vector")
      (is (= 1 (count tx-data)) "Single tx-data assertion")
      (let [[op eid attr val] (first tx-data)]
        (is (= :db/add op))
        (is (= 42 eid))
        (is (= :mm.session-process/paused-from-state attr))
        (is (= :session/active val))))))

(deftest on-resume-maintenance-retracts-paused-from-state
  (testing "on-resume-maintenance returns tx-data retracting :mm.session-process/paused-from-state"
    (let [fake-process {:db/id 42 :mm.session-process/paused-from-state :session/active}
          context {}
          tx-data (session/on-resume-maintenance fake-process context)]
      (is (some? tx-data))
      (let [[op eid attr val] (first tx-data)]
        (is (= :db/retract op))
        (is (= 42 eid))
        (is (= :mm.session-process/paused-from-state attr))
        (is (= :session/active val))))))

(deftest on-fail-returns-tx-data-with-reason-and-instant
  (testing "on-fail returns tx-data recording failure-reason + failure-instant"
    (let [fake-process {:db/id 42
                        :workflow/current-state {:workflow/state-name :session/active}}
          context {:reason "irrecoverable error during workflow processing"}
          tx-data (session/on-fail fake-process context)]
      (is (vector? tx-data))
      (is (= 2 (count tx-data)) "Two assertions: reason + instant")
      (let [[reason-tx instant-tx] tx-data]
        (is (= :mm.session-process/failure-reason (nth reason-tx 2)))
        (is (= "irrecoverable error during workflow processing" (nth reason-tx 3)))
        (is (= :mm.session-process/failure-instant (nth instant-tx 2)))
        (is (instance? java.util.Date (nth instant-tx 3)))))))
