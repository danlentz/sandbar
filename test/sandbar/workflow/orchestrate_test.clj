(ns sandbar.workflow.orchestrate-test
  "Tests for the ι.3 substrate orchestrator dispatcher loop (W4.1).

   Two test categories:

   1. **Pure-Clojure helpers** (no DB needed) — `ceremony-of`,
      `next-phase-of`, `effective-timeout-ms`, `phase-transitions` structure,
      `validate-args!` (via `orchestrate`).

   2. **DB-required dispatcher** (uses `tu/make-test-db-fixture`) —
      verifies `orchestrate` drives real workflow.process transitions
      against the `:workflow/session` workflow loaded by the fixture.
      Covers: pure-read phase (no transition); transition-bearing phase
      (`:phase/activate` → `:session/start`); chained transition phase
      (`:phase/finalize` → `:session/close` + `:session/finalize`); and
      κ P18 bootstrap-robustness fallback for `:transition-not-found`.

   Per `:memory.decisions/iota_3_substrate_orchestrator_design_ratification_2026_05_26`
   + the W4.1 entry in `:memory.plans/sandbar_0_2_0_release_comprehensive_strategic_re_plan_wave_2_revision_2026_05_26`."
  (:require [clojure.test :refer :all]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]
            [sandbar.util.workflow :as wf]
            [sandbar.workflow.orchestrate :as orchestrate]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "orchestrate-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure-Clojure helper tests — ceremony-of
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ceremony-of-classifies-open-phases
  (testing "ceremony-of returns :open for all 4 open-phases"
    (is (= :open (orchestrate/ceremony-of :phase/orient)))
    (is (= :open (orchestrate/ceremony-of :phase/initialize)))
    (is (= :open (orchestrate/ceremony-of :phase/activate)))
    (is (= :open (orchestrate/ceremony-of :phase/imprint)))))

(deftest ceremony-of-classifies-handoff-phases
  (testing "ceremony-of returns :handoff for all 4 handoff-phases"
    (is (= :handoff (orchestrate/ceremony-of :phase/capture)))
    (is (= :handoff (orchestrate/ceremony-of :phase/author)))
    (is (= :handoff (orchestrate/ceremony-of :phase/link)))
    (is (= :handoff (orchestrate/ceremony-of :phase/finalize)))))

(deftest ceremony-of-returns-nil-for-unknown-phase
  (testing "ceremony-of returns nil for unknown phase keywords"
    (is (nil? (orchestrate/ceremony-of :phase/bogus)))
    (is (nil? (orchestrate/ceremony-of :not-a-phase)))
    (is (nil? (orchestrate/ceremony-of nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure-Clojure helper tests — next-phase-of
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest next-phase-of-walks-open-ceremony
  (testing "next-phase-of advances through open-phases canonically"
    (is (= :phase/initialize (orchestrate/next-phase-of :phase/orient)))
    (is (= :phase/activate   (orchestrate/next-phase-of :phase/initialize)))
    (is (= :phase/imprint    (orchestrate/next-phase-of :phase/activate)))))

(deftest next-phase-of-walks-handoff-ceremony
  (testing "next-phase-of advances through handoff-phases canonically"
    (is (= :phase/author   (orchestrate/next-phase-of :phase/capture)))
    (is (= :phase/link     (orchestrate/next-phase-of :phase/author)))
    (is (= :phase/finalize (orchestrate/next-phase-of :phase/link)))))

(deftest next-phase-of-returns-nil-at-terminal-phase
  (testing "next-phase-of returns nil at the terminal phase of each ceremony"
    (is (nil? (orchestrate/next-phase-of :phase/imprint))
        ":phase/imprint is terminal for the open ceremony")
    (is (nil? (orchestrate/next-phase-of :phase/finalize))
        ":phase/finalize is terminal for the handoff ceremony")))

(deftest next-phase-of-returns-nil-for-unknown-phase
  (testing "next-phase-of returns nil for unknown phase keywords"
    (is (nil? (orchestrate/next-phase-of :phase/bogus)))
    (is (nil? (orchestrate/next-phase-of nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure-Clojure helper tests — effective-timeout-ms
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest effective-timeout-ms-uses-override-when-provided
  (testing "Per-call override takes precedence over default"
    (is (= 5000 (orchestrate/effective-timeout-ms :phase/orient {:phase/orient 5000})))
    (is (= 1   (orchestrate/effective-timeout-ms :phase/activate {:phase/activate 1})))))

(deftest effective-timeout-ms-falls-back-to-default
  (testing "Falls back to default-phase-timeouts-ms when no override"
    (is (= 90000 (orchestrate/effective-timeout-ms :phase/orient nil)))
    (is (= 30000 (orchestrate/effective-timeout-ms :phase/initialize nil)))
    (is (= 10000 (orchestrate/effective-timeout-ms :phase/activate {})))
    (is (= 60000 (orchestrate/effective-timeout-ms :phase/imprint {})))
    (is (= 90000 (orchestrate/effective-timeout-ms :phase/capture nil)))
    (is (= 60000 (orchestrate/effective-timeout-ms :phase/author nil)))
    (is (= 10000 (orchestrate/effective-timeout-ms :phase/link nil)))
    (is (= 10000 (orchestrate/effective-timeout-ms :phase/finalize nil)))))

(deftest effective-timeout-ms-throws-on-unknown-phase
  (testing "Throws ex-info :reason :unknown-phase for unknown phase"
    (let [thrown (try (orchestrate/effective-timeout-ms :phase/bogus nil)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "Should throw")
      (is (= :unknown-phase (:reason (ex-data thrown)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure-Clojure helper tests — phase-transitions structural invariants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest phase-transitions-covers-all-eight-phases
  (testing "phase-transitions has an entry for every phase in both ceremonies"
    (let [all-phases (set (concat orchestrate/open-phases orchestrate/handoff-phases))
          covered    (set (keys orchestrate/phase-transitions))]
      (is (= all-phases covered)
          "phase-transitions must cover all 8 phases")
      (is (= 8 (count covered))
          "Exactly 8 phases total"))))

(deftest phase-transitions-canonical-mappings
  (testing "Per ι.3 design ratification — only :phase/activate + :phase/finalize bear transitions"
    (is (= []                              (:phase/orient     orchestrate/phase-transitions)))
    (is (= []                              (:phase/initialize orchestrate/phase-transitions)))
    (is (= [:session/start]                (:phase/activate   orchestrate/phase-transitions)))
    (is (= []                              (:phase/imprint    orchestrate/phase-transitions)))
    (is (= []                              (:phase/capture    orchestrate/phase-transitions)))
    (is (= []                              (:phase/author     orchestrate/phase-transitions)))
    (is (= []                              (:phase/link       orchestrate/phase-transitions)))
    (is (= [:session/close :session/finalize] (:phase/finalize orchestrate/phase-transitions)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Argument validation tests — orchestrate refuses incomplete inputs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ex-info-with-reason
  "Run thunk; capture the ex-info if thrown; return its :reason or nil."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:reason (ex-data e)))))

(deftest orchestrate-rejects-missing-workflow
  (is (= :missing-required-arg
         (ex-info-with-reason
          #(orchestrate/orchestrate {:process-id 1 :phase :phase/orient})))))

(deftest orchestrate-rejects-missing-process-id
  ;; :phase/activate REQUIRES :process-id (per Increment G, only :phase/orient is exempt).
  (is (= :missing-required-arg
         (ex-info-with-reason
          #(orchestrate/orchestrate {:workflow :workflow/session :phase :phase/activate})))))

(deftest orchestrate-rejects-missing-phase
  (is (= :missing-required-arg
         (ex-info-with-reason
          #(orchestrate/orchestrate {:workflow :workflow/session :process-id 1})))))

(deftest orchestrate-rejects-unknown-phase
  (is (= :unknown-phase
         (ex-info-with-reason
          #(orchestrate/orchestrate {:workflow   :workflow/session
                                     :process-id 1
                                     :phase      :phase/bogus})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB-required dispatcher tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-test-session-process!
  "Create a placeholder subject + start a :workflow/session process against it.
   Returns the process entity (in initial state :session/opening)."
  []
  (let [subject (tu/create-test-user! {:username (str "orch-test-" (System/nanoTime))
                                       :email    (str "orch-" (System/nanoTime) "@sandbar.test")})]
    (wf/start-process! :workflow/session subject)))

(deftest orchestrate-phase-orient-is-pure-read
  (testing ":phase/orient applies no transition; returns expected shape"
    (let [process (start-test-session-process!)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/orient})]
      (is (= :phase/orient (:phase-completed result)))
      (is (= :phase/initialize (:next-phase result)))
      (is (= [] (:transition-applied result)) "No transitions applied for :phase/orient")
      (is (= [] (:events-emitted result)))
      (is (false? (:degraded? result)))
      (is (>= (:duration-ms result) 0))
      ;; Process should still be in :session/opening (the initial state)
      (let [process-after (wf/find-process (:db/id process))]
        (is (= :session/opening
               (:workflow/state-name (wf/get-current-state process-after))))))))

(deftest orchestrate-phase-activate-applies-session-start
  (testing ":phase/activate applies :session/start (opening → active)"
    (let [process (start-test-session-process!)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/activate})]
      (is (= :phase/activate (:phase-completed result)))
      (is (= :phase/imprint (:next-phase result)))
      (is (= [:session/start] (:transition-applied result)))
      (is (false? (:degraded? result)))
      ;; Process should now be in :session/active
      (let [process-after (wf/find-process (:db/id process))]
        (is (= :session/active
               (:workflow/state-name (wf/get-current-state process-after))))))))

(deftest orchestrate-phase-finalize-applies-close-then-finalize-chain
  (testing ":phase/finalize applies :session/close then :session/finalize (active → closing → closed)"
    (let [process (start-test-session-process!)
          ;; First advance the process to :session/active
          _       (wf/transition! process :session/start)
          process (wf/find-process (:db/id process))
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/finalize})]
      (is (= :phase/finalize (:phase-completed result)))
      (is (nil? (:next-phase result)) ":phase/finalize is terminal for the handoff ceremony")
      (is (= [:session/close :session/finalize] (:transition-applied result)))
      (is (false? (:degraded? result)))
      ;; Process should now be in :session/closed (terminal :success)
      (let [process-after (wf/find-process (:db/id process))
            current-state (wf/get-current-state process-after)]
        (is (= :session/closed (:workflow/state-name current-state)))
        (is (true? (:workflow/terminal? current-state)))
        (is (= :success (:workflow/terminal-kind current-state)))))))

(deftest orchestrate-degrades-on-transition-not-found
  (testing "κ P18 fallback: :transition-not-found surfaces as :degraded? true (does NOT throw)"
    (let [process (start-test-session-process!)
          ;; In :session/opening, :session/close + :session/finalize aren't reachable.
          ;; orchestrate's :phase/finalize tries them and should degrade gracefully.
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/finalize})]
      (is (= :phase/finalize (:phase-completed result)))
      (is (true? (:degraded? result))
          "Degraded-path fallback engaged when transitions not reachable from current state")
      (is (= [] (:transition-applied result))
          "No transitions successfully applied (all degraded)")
      ;; Process should still be in :session/opening (no state change occurred)
      (let [process-after (wf/find-process (:db/id process))]
        (is (= :session/opening
               (:workflow/state-name (wf/get-current-state process-after))))))))

(deftest orchestrate-result-map-has-all-six-canonical-keys
  (testing "Every successful orchestrate call returns the full canonical result shape"
    (let [process (start-test-session-process!)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/orient})]
      (is (contains? result :phase-completed))
      (is (contains? result :next-phase))
      (is (contains? result :transition-applied))
      (is (contains? result :events-emitted))
      (is (contains? result :duration-ms))
      (is (contains? result :degraded?))
      (is (contains? result :phase-work-result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Substrate-side regression — :reason key on wf/transition! ex-info throws
;;
;; Per the Q.ι.3.11 follow-on improvement landed alongside this commit: the
;; 3 ex-info throws in sandbar.util.workflow/transition! now carry a :reason
;; key so the orchestrator's κ P18 fallback can cleanly distinguish recoverable
;; (:transition-not-found) from non-recoverable failures.  These tests
;; regression-cover that ABI surface.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest wf-transition-throws-with-reason-transition-not-found
  (testing "wf/transition! throws ex-info {:reason :transition-not-found ...} for unavailable transitions"
    (let [process (start-test-session-process!)  ;; in :session/opening
          ;; :session/close is not reachable from :session/opening
          reason  (ex-info-with-reason
                   #(wf/transition! process :session/close))]
      (is (= :transition-not-found reason)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; :mm.event/WorkflowTransition hierarchy schema invariants — Q.ι.3.9
;;
;; Per the ι.3 design ratification ADR §Q.ι.3.9: 1 abstract umbrella
;; (:mm.event/WorkflowTransition) + 5 concrete subtypes
;; (:mm.event/WorkflowSessionOpened / -HandoffAuthored / -Closed / -Degraded /
;; -Failed).  These tests verify the schema authored in schema/mm-temporal.edn
;; loads with correct hierarchy + abstract flags + slot domain declarations.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private workflow-event-concrete-subclasses
  [:mm.event/WorkflowSessionOpened
   :mm.event/WorkflowSessionHandoffAuthored
   :mm.event/WorkflowSessionClosed
   :mm.event/WorkflowSessionDegraded
   :mm.event/WorkflowSessionFailed])

(defn- ident-set
  "Coerce a slot value to a set of keyword idents.  Handles both shapes Datomic
   returns for :db.type/ref slots: (a) collection of entity-maps with :db/ident
   keys; (b) collection of keyword idents directly (sandbar's class accessors
   return idents for some ref slots).  Returns #{} for nil input."
  [v]
  (cond
    (nil? v) #{}
    (keyword? v) #{v}
    (map? v) (if-let [k (:db/ident v)] #{k} #{})
    (coll? v) (into #{}
                    (keep (fn [x]
                            (cond
                              (keyword? x) x
                              (map? x)     (:db/ident x))))
                    v)
    :else #{}))

(deftest mm-event-workflow-transition-abstract-base-loads
  (testing ":mm.event/WorkflowTransition class loads with abstract? true + :mm/Event parent"
    (let [cls (db/entity :mm.event/WorkflowTransition)]
      (is (some? cls) ":mm.event/WorkflowTransition class entity should exist after schema-load")
      (is (true? (:dt/abstract? cls))
          ":mm.event/WorkflowTransition is the abstract umbrella (no direct instances)")
      (let [parent-idents (ident-set (:dt/subclass-of cls))]
        (is (contains? parent-idents :mm/Event)
            ":mm.event/WorkflowTransition is a subclass of :mm/Event")))))

(deftest mm-event-workflow-concrete-subclasses-load
  (testing "All 5 concrete :mm.event/WorkflowSession* classes load with correct shape"
    (doseq [ident workflow-event-concrete-subclasses]
      (let [cls (db/entity ident)]
        (is (some? cls) (str ident " should exist after schema-load"))
        (is (false? (boolean (:dt/abstract? cls)))
            (str ident " is concrete (not abstract)"))
        (let [parent-idents (ident-set (:dt/subclass-of cls))]
          (is (contains? parent-idents :mm.event/WorkflowTransition)
              (str ident " is a subclass of :mm.event/WorkflowTransition")))))))

(deftest mm-event-workflow-degraded-has-degraded-reason-slot
  (testing ":mm.event/WorkflowSessionDegraded carries :mm.workflow-event/degraded-reason"
    (let [cls   (db/entity :mm.event/WorkflowSessionDegraded)
          slots (ident-set (:dt/slots cls))]
      (is (contains? slots :mm.workflow-event/degraded-reason)
          ":mm.event/WorkflowSessionDegraded declares :mm.workflow-event/degraded-reason in its slot set"))))

(deftest mm-event-workflow-failed-has-failure-reason-slot
  (testing ":mm.event/WorkflowSessionFailed carries :mm.workflow-event/failure-reason"
    (let [cls   (db/entity :mm.event/WorkflowSessionFailed)
          slots (ident-set (:dt/slots cls))]
      (is (contains? slots :mm.workflow-event/failure-reason)
          ":mm.event/WorkflowSessionFailed declares :mm.workflow-event/failure-reason in its slot set"))))

(deftest mm-workflow-event-process-slot-domain-correct
  (testing ":mm.workflow-event/process slot has :dt/domain :mm.event/WorkflowTransition + :db.type/ref valueType"
    (let [slot         (db/entity :mm.workflow-event/process)
          domain-idents (ident-set (:dt/domain slot))]
      (is (some? slot))
      (is (contains? domain-idents :mm.event/WorkflowTransition)
          "domain includes :mm.event/WorkflowTransition")
      (is (= :db.type/ref (:db/valueType slot))))))

(deftest mm-workflow-event-phase-slot-loads
  (testing ":mm.workflow-event/phase slot loads with correct shape"
    (let [slot (db/entity :mm.workflow-event/phase)]
      (is (some? slot))
      (is (= :db.type/keyword (:db/valueType slot)))
      ;; :db/cardinality slot returns the keyword directly (not an entity wrapping)
      (is (= :db.cardinality/one (:db/cardinality slot))))))

(deftest mm-event-workflow-transition-base-carries-common-slots
  (testing ":mm.event/WorkflowTransition abstract base declares common slots (:process, :phase, :transition)"
    (let [cls   (db/entity :mm.event/WorkflowTransition)
          slots (ident-set (:dt/slots cls))]
      (is (contains? slots :mm.workflow-event/process))
      (is (contains? slots :mm.workflow-event/phase))
      (is (contains? slots :mm.workflow-event/transition)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Event emission tests (Q.ι.3.9; W4.1 Increment B)
;;
;; Verify that orchestrate populates :events-emitted with real eids pointing
;; at correctly-shaped :mm.event/Workflow* entities matching the phase context.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest orchestrate-phase-activate-emits-workflow-session-opened
  (testing ":phase/activate emits :mm.event/WorkflowSessionOpened with correct slots"
    (let [process (start-test-session-process!)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/activate})]
      (is (= 1 (count (:events-emitted result)))
          ":phase/activate emits exactly 1 event")
      (let [event-eid    (first (:events-emitted result))
            event-entity (db/entity event-eid)
            event-type   (let [t (:dt/type event-entity)]
                           (cond (keyword? t) t
                                 :else (:db/ident t)))]
        (is (= :mm.event/WorkflowSessionOpened event-type)
            "Emitted event is a :mm.event/WorkflowSessionOpened")
        (is (= :phase/activate (:mm.workflow-event/phase event-entity))
            ":mm.workflow-event/phase slot populated correctly")
        (is (= :session/start (:mm.workflow-event/transition event-entity))
            ":mm.workflow-event/transition slot populated correctly")
        (let [event-process (:mm.workflow-event/process event-entity)
              process-eid   (or (:db/id event-process)  ;; works for Datomic Entity OR map
                                (when (number? event-process) event-process))]
          (is (= (:db/id process) process-eid)
              ":mm.workflow-event/process slot points at the right workflow.process"))))))

(deftest orchestrate-phase-finalize-emits-workflow-session-closed
  (testing ":phase/finalize emits :mm.event/WorkflowSessionClosed"
    (let [process (start-test-session-process!)
          ;; Advance to :session/active first
          _       (wf/transition! process :session/start)
          process (wf/find-process (:db/id process))
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/finalize})]
      (is (= 1 (count (:events-emitted result))))
      (let [event-entity (db/entity (first (:events-emitted result)))
            event-type   (let [t (:dt/type event-entity)]
                           (cond (keyword? t) t :else (:db/ident t)))]
        (is (= :mm.event/WorkflowSessionClosed event-type))
        (is (= :phase/finalize (:mm.workflow-event/phase event-entity)))
        ;; :transition should be the LAST applied transition (:session/finalize)
        (is (= :session/finalize (:mm.workflow-event/transition event-entity)))))))

(deftest orchestrate-phase-orient-emits-no-event
  (testing ":phase/orient is pure-read with no registered emission class → empty :events-emitted"
    (let [process (start-test-session-process!)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/orient})]
      (is (= [] (:events-emitted result))
          ":phase/orient does not emit a phase-completion event"))))

(deftest orchestrate-degraded-emits-workflow-session-degraded
  (testing "κ P18 fallback emits :mm.event/WorkflowSessionDegraded with :degraded-reason"
    (let [process (start-test-session-process!)  ;; in :session/opening
          ;; :phase/finalize tries :session/close from opening → not found → degraded
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/finalize})]
      (is (true? (:degraded? result)))
      (is (= 1 (count (:events-emitted result)))
          "Degraded path emits exactly 1 :Degraded event")
      (let [event-entity (db/entity (first (:events-emitted result)))
            event-type   (let [t (:dt/type event-entity)]
                           (cond (keyword? t) t :else (:db/ident t)))]
        (is (= :mm.event/WorkflowSessionDegraded event-type))
        (is (= :phase/finalize (:mm.workflow-event/phase event-entity)))
        (is (= :transition-not-found
               (:mm.workflow-event/degraded-reason event-entity))
            ":mm.workflow-event/degraded-reason carries the recoverable failure mode")))))

(deftest phase-completion-event-class-canonical-mapping
  (testing "phase-completion-event-class maps only the 3 emission-bearing phases"
    (is (= :mm.event/WorkflowSessionOpened
           (get orchestrate/phase-completion-event-class :phase/activate)))
    (is (= :mm.event/WorkflowSessionHandoffAuthored
           (get orchestrate/phase-completion-event-class :phase/author)))
    (is (= :mm.event/WorkflowSessionClosed
           (get orchestrate/phase-completion-event-class :phase/finalize)))
    (is (nil? (get orchestrate/phase-completion-event-class :phase/orient))
        ":phase/orient is pure-read; no emission registered")
    (is (nil? (get orchestrate/phase-completion-event-class :phase/imprint))
        ":phase/imprint is pure-write banner; no emission registered")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema regression — :session.transition/fail-from-opening (W4.1 Increment E)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest workflow-session-has-fail-from-opening-transition
  (testing ":session.transition/fail-from-opening exists with opening → failed shape"
    (let [fail-tx (wf/find-transition :session/fail-from-opening :session/opening)]
      (is (some? fail-tx)
          ":session/fail-from-opening should be reachable from :session/opening")
      (is (= :session/failed
             (:workflow/state-name (:workflow/to-state fail-tx)))
          "to-state is :session/failed"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Phase-timeout hard-fail tests (κ P8; Q.ι.3.4; W4.1 Increment E)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest orchestrate-completes-within-timeout
  (testing "Normal-fast phase completes well under the default timeout"
    (let [process (start-test-session-process!)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/orient})]
      (is (= :phase/orient (:phase-completed result)))
      (is (< (:duration-ms result) 10000)
          ":phase/orient default 90s timeout — fast phase completes well under it"))))

(deftest orchestrate-phase-timeout-fires-on-slow-phase-work
  (testing "Slow phase-work triggers :reason :phase-timeout hard-fail"
    (let [process (start-test-session-process!)
          thrown  (try
                    (orchestrate/orchestrate {:workflow   :workflow/session
                                              :process-id (:db/id process)
                                              :phase      :phase/activate
                                              :timeouts   {:phase/activate 50}
                                              :context    {:test/sleep-ms 500}})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
      (is (some? thrown) "Should throw on timeout")
      (is (= :phase-timeout (:reason (ex-data thrown))))
      (is (= :phase/activate (:phase (ex-data thrown))))
      (is (= 50 (:timeout-ms (ex-data thrown))))
      ;; :event-emitted carries the :mm.event/WorkflowSessionFailed eid
      (is (some? (:event-emitted (ex-data thrown)))
          ":mm.event/WorkflowSessionFailed event was emitted before throw"))))

(deftest orchestrate-phase-timeout-emits-failed-event
  (testing ":phase-timeout fires :mm.event/WorkflowSessionFailed with :failure-reason"
    (let [process    (start-test-session-process!)
          thrown     (try
                       (orchestrate/orchestrate {:workflow   :workflow/session
                                                 :process-id (:db/id process)
                                                 :phase      :phase/activate
                                                 :timeouts   {:phase/activate 50}
                                                 :context    {:test/sleep-ms 500}})
                       nil
                       (catch clojure.lang.ExceptionInfo e e))
          event-eid  (:event-emitted (ex-data thrown))
          event-ent  (db/entity event-eid)
          event-type (let [t (:dt/type event-ent)]
                       (cond (keyword? t) t :else (:db/ident t)))]
      (is (= :mm.event/WorkflowSessionFailed event-type))
      (is (= :phase/activate (:mm.workflow-event/phase event-ent)))
      (is (= :phase-timeout (:mm.workflow-event/failure-reason event-ent))))))

(deftest orchestrate-phase-timeout-advances-process-to-failed
  (testing ":phase-timeout best-effort transitions process to :session/failed when reachable"
    (let [process (start-test-session-process!)  ;; in :session/opening
          _       (try
                    (orchestrate/orchestrate {:workflow   :workflow/session
                                              :process-id (:db/id process)
                                              :phase      :phase/activate
                                              :timeouts   {:phase/activate 50}
                                              :context    {:test/sleep-ms 500}})
                    (catch clojure.lang.ExceptionInfo _ nil))
          process-after (wf/find-process (:db/id process))
          current-state (:workflow/state-name (wf/get-current-state process-after))]
      (is (= :session/failed current-state)
          "fail-from-opening transition advances process to :session/failed terminal state"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/orient tests (W4.1 Increment G — first multimethod method)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest orchestrate-phase-orient-accepts-no-process-id
  (testing ":phase/orient succeeds WITHOUT a :process-id arg per Increment G"
    (let [result (orchestrate/orchestrate {:workflow :workflow/session
                                           :phase    :phase/orient})]
      (is (= :phase/orient (:phase-completed result)))
      (is (= :phase/initialize (:next-phase result)))
      (is (= [] (:transition-applied result)) "Pure-read; no transitions")
      (is (= [] (:events-emitted result)) "No phase-completion event for :orient")
      (is (false? (:degraded? result)))
      (is (map? (:phase-work-result result))
          ":phase-work-result carries the orient-state map from phase-work :phase/orient"))))

(deftest orchestrate-rejects-missing-process-id-for-non-orient-phase
  (testing "Other phases STILL reject missing :process-id"
    (is (= :missing-required-arg
           (ex-info-with-reason
            #(orchestrate/orchestrate {:workflow :workflow/session
                                       :phase    :phase/activate}))))))

(deftest phase-work-orient-returns-canonical-shape
  (testing "phase-work :phase/orient returns map with all expected keys"
    (let [orient-state (orchestrate/phase-work {:phase :phase/orient})]
      (is (contains? orient-state :prior-session))
      (is (contains? orient-state :prior-log))
      (is (contains? orient-state :memory-count))
      (is (contains? orient-state :type-histogram))
      (is (contains? orient-state :active-plans))
      (is (contains? orient-state :active-tasks))
      (is (contains? orient-state :active-processes)))))

(deftest phase-work-orient-corpus-stats-populated
  (testing "Corpus stats slots are well-shaped"
    (let [orient-state (orchestrate/phase-work {:phase :phase/orient})]
      (is (integer? (:memory-count orient-state)))
      (is (>= (:memory-count orient-state) 0)
          "Memory count is non-negative")
      (is (map? (:type-histogram orient-state))
          ":type-histogram is a map of class→count"))))

(deftest phase-work-orient-active-collections-are-vecs
  (testing "Active arcs / tasks / processes return vecs (queryable JSON-safe)"
    (let [orient-state (orchestrate/phase-work {:phase :phase/orient})]
      (is (vector? (:active-plans orient-state)))
      (is (vector? (:active-tasks orient-state)))
      (is (sequential? (:active-processes orient-state)))
      (is (<= (count (:active-plans orient-state)) 5)
          "Top-5 cap on active-plans")
      (is (<= (count (:active-tasks orient-state)) 5)
          "Top-5 cap on active-tasks"))))

(deftest phase-work-no-remaining-default-fallthroughs
  (testing "All 6 phase-work methods landed (Increment J completes the migration)"
    ;; All open + handoff phases now have registered methods
    (is (some? (.getMethod ^clojure.lang.MultiFn @#'orchestrate/phase-work :phase/orient)))
    (is (some? (.getMethod ^clojure.lang.MultiFn @#'orchestrate/phase-work :phase/initialize)))
    (is (some? (.getMethod ^clojure.lang.MultiFn @#'orchestrate/phase-work :phase/imprint)))
    (is (some? (.getMethod ^clojure.lang.MultiFn @#'orchestrate/phase-work :phase/capture)))
    (is (some? (.getMethod ^clojure.lang.MultiFn @#'orchestrate/phase-work :phase/author)))
    (is (some? (.getMethod ^clojure.lang.MultiFn @#'orchestrate/phase-work :phase/link)))
    ;; :phase/activate + :phase/finalize have NO phase-work method — they're pure-transition phases
    ;; where the orchestrate body's transition application is the entirety of the work.
    ;; These fall through to :default (nil) — by design.
    (is (nil? (orchestrate/phase-work {:phase :phase/activate})))
    (is (nil? (orchestrate/phase-work {:phase :phase/finalize})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/imprint tests (W4.1 Increment H — banner composition)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest phase-work-imprint-returns-string
  (testing "phase-work :phase/imprint always returns a string"
    (let [result (orchestrate/phase-work {:phase :phase/imprint})]
      (is (string? result)))
    (let [result (orchestrate/phase-work {:phase   :phase/imprint
                                          :context {:orient-state {:memory-count 100}}})]
      (is (string? result)))))

(deftest phase-work-imprint-handles-missing-orient-state
  (testing "Missing :orient-state in context → graceful placeholder banner"
    (let [result (orchestrate/phase-work {:phase :phase/imprint})]
      (is (re-find #"(?i)orient-state was not supplied" result)
          "Banner mentions missing orient-state when context is absent"))
    (let [result (orchestrate/phase-work {:phase   :phase/imprint
                                          :context {}})]
      (is (re-find #"(?i)orient-state was not supplied" result)))))

(deftest phase-work-imprint-includes-memory-count
  (testing "Banner includes the corpus memory count when orient-state supplied"
    (let [result (orchestrate/phase-work
                   {:phase   :phase/imprint
                    :context {:orient-state {:memory-count   12345
                                             :type-histogram {}}}})]
      (is (re-find #"12345" result)
          "Memory count appears in banner"))))

(deftest phase-work-imprint-includes-prior-session-name
  (testing "Banner includes prior-session name when present"
    (let [result (orchestrate/phase-work
                   {:phase   :phase/imprint
                    :context {:orient-state
                              {:prior-session  {:mm.memory/name "Session 2026-05-26T1430"}
                               :memory-count   100
                               :type-histogram {}}}})]
      (is (re-find #"Session 2026-05-26T1430" result)))))

(deftest phase-work-imprint-includes-active-plans
  (testing "Banner includes active-plans names when present"
    (let [result (orchestrate/phase-work
                   {:phase   :phase/imprint
                    :context {:orient-state
                              {:memory-count   100
                               :type-histogram {}
                               :active-plans   [{:mm.memory/name "Plan Alpha"}
                                                {:mm.memory/name "Plan Beta"}]}}})]
      (is (re-find #"Active arcs" result))
      (is (re-find #"Plan Alpha" result))
      (is (re-find #"Plan Beta" result)))))

(deftest phase-work-imprint-handles-empty-active-collections
  (testing "Empty active-plans / active-tasks → those banner lines omitted"
    (let [result (orchestrate/phase-work
                   {:phase   :phase/imprint
                    :context {:orient-state
                              {:memory-count     100
                               :type-histogram   {}
                               :active-plans     []
                               :active-tasks     []
                               :active-processes []}}})]
      (is (string? result))
      (is (not (re-find #"Active arcs" result))
          "No 'Active arcs' line when active-plans is empty")
      (is (not (re-find #"Ready queue" result))
          "No 'Ready queue' line when active-tasks is empty")
      (is (not (re-find #"In-flight workflows" result))
          "No 'In-flight workflows' line when active-processes is empty"))))

(deftest phase-work-imprint-integrates-with-orchestrate
  (testing "orchestrate :phase/imprint with orient-state in context returns banner via :phase-work-result"
    (let [process (start-test-session-process!)
          orient-state {:memory-count 100
                        :type-histogram {}
                        :active-plans [{:mm.memory/name "Test Plan"}]}
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/imprint
                                            :context    {:orient-state orient-state}})]
      (is (= :phase/imprint (:phase-completed result)))
      (is (nil? (:next-phase result)) ":phase/imprint is terminal in open ceremony")
      (is (string? (:phase-work-result result)))
      (is (re-find #"Test Plan" (:phase-work-result result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/capture tests (W4.1 Increment I — handoff-side queries)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest phase-work-capture-returns-canonical-shape
  (testing "phase-work :phase/capture returns map with expected keys"
    (let [process       (start-test-session-process!)
          capture-state (orchestrate/phase-work {:phase      :phase/capture
                                                 :process-id (:db/id process)})]
      (is (map? capture-state))
      (is (contains? capture-state :process-history))
      (is (contains? capture-state :process-current-state))
      (is (contains? capture-state :process-completed?))
      (is (contains? capture-state :recent-memorials)))))

(deftest phase-work-capture-process-history-shape
  (testing ":process-history is sequential + :process-current-state is a keyword"
    (let [process       (start-test-session-process!)
          ;; Advance one transition to populate history
          _             (wf/transition! process :session/start)
          capture-state (orchestrate/phase-work {:phase      :phase/capture
                                                 :process-id (:db/id process)})]
      (is (sequential? (:process-history capture-state)))
      (is (= :session/active (:process-current-state capture-state))
          "Current state reflects post-transition state")
      (is (false? (:process-completed? capture-state))
          ":process-completed? false for non-terminal state"))))

(deftest phase-work-capture-recent-memorials-bounded
  (testing ":recent-memorials is bounded by limit (default 20)"
    (let [process       (start-test-session-process!)
          capture-state (orchestrate/phase-work {:phase      :phase/capture
                                                 :process-id (:db/id process)})]
      (is (vector? (:recent-memorials capture-state)))
      (is (<= (count (:recent-memorials capture-state)) 20)
          "Default cap is 20"))))

(deftest phase-work-capture-memorial-limit-override
  (testing ":context :memorial-limit overrides the default cap"
    (let [process       (start-test-session-process!)
          capture-state (orchestrate/phase-work {:phase      :phase/capture
                                                 :process-id (:db/id process)
                                                 :context    {:memorial-limit 5}})]
      (is (<= (count (:recent-memorials capture-state)) 5)
          "Override cap respected"))))

(deftest phase-work-capture-integrates-with-orchestrate
  (testing "orchestrate :phase/capture with valid process-id returns capture-state via :phase-work-result"
    (let [process (start-test-session-process!)
          ;; Advance to :session/active so :phase/capture isn't operating on a stuck process
          _       (wf/transition! process :session/start)
          result  (orchestrate/orchestrate {:workflow   :workflow/session
                                            :process-id (:db/id process)
                                            :phase      :phase/capture})]
      (is (= :phase/capture (:phase-completed result)))
      (is (= :phase/author (:next-phase result))
          ":phase/capture → :phase/author is the canonical handoff progression")
      (is (= [] (:transition-applied result))
          "No transitions on :phase/capture (pure read)")
      (is (map? (:phase-work-result result)))
      (is (contains? (:phase-work-result result) :process-history)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/initialize tests (W4.1 Increment J — bootstrap)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- bootstrap-context
  "Minimal :context for :phase/initialize tests — supplies required slots."
  []
  (let [stamp (System/nanoTime)]
    {:rel-path    (str "sessions/2026-05-27-init-test-" stamp ".md")
     :name        (str "Init test " stamp)
     :description "Phase-work :phase/initialize unit-test session"}))

(deftest phase-work-initialize-creates-session-and-process
  (testing "phase-work :phase/initialize creates :mm/Session + workflow.process; returns ids"
    (let [result (orchestrate/phase-work {:phase    :phase/initialize
                                          :workflow :workflow/session
                                          :context  (bootstrap-context)})]
      (is (map? result))
      (is (number? (:session-eid result)))
      (is (number? (:process-id result)))
      (is (associative? (:session-entity result))
          ":session-entity is a Datomic Entity (implements ILookup; not map?)")
      (is (associative? (:process-entity result))))))

(deftest orchestrate-phase-initialize-accepts-no-process-id
  (testing "orchestrate :phase/initialize succeeds WITHOUT a :process-id arg"
    (let [result (orchestrate/orchestrate {:workflow :workflow/session
                                           :phase    :phase/initialize
                                           :context  (bootstrap-context)})]
      (is (= :phase/initialize (:phase-completed result)))
      (is (= :phase/activate (:next-phase result)))
      (is (number? (:created-process-id result))
          ":created-process-id surfaces at top level of result map for downstream phases")
      (is (= (:created-process-id result)
             (-> result :phase-work-result :process-id))
          ":created-process-id matches the :process-id in :phase-work-result"))))

(deftest orchestrate-phase-initialize-creates-process-in-session-opening
  (testing "Created process starts in :session/opening (initial state)"
    (let [result        (orchestrate/orchestrate {:workflow :workflow/session
                                                  :phase    :phase/initialize
                                                  :context  (bootstrap-context)})
          process-id    (:created-process-id result)
          process       (wf/find-process process-id)
          current-state (wf/get-current-state process)]
      (is (= :session/opening (:workflow/state-name current-state))
          "Bootstrap produces a process in :session/opening; :phase/activate then advances"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/author tests (W4.1 Increment J — log creation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- author-context
  "Minimal :context for :phase/author tests — supplies required slots."
  []
  (let [stamp (System/nanoTime)]
    {:narrative    (str "# Test handoff log " stamp "\n\nNarrative body.")
     :rel-path     (str "logs/2026-05-27-author-test-" stamp ".md")
     :name         (str "Author test " stamp)
     :description  "Phase-work :phase/author unit-test handoff log"}))

(deftest phase-work-author-creates-mm-log
  (testing "phase-work :phase/author creates :mm/Log entity; returns log-eid + entity"
    (let [process (start-test-session-process!)
          result  (orchestrate/phase-work {:phase      :phase/author
                                           :process-id (:db/id process)
                                           :context    (author-context)})]
      (is (map? result))
      (is (number? (:log-eid result)))
      (is (associative? (:log-entity result)))
      (let [log-entity (db/entity (:log-eid result))
            log-type   (let [t (:dt/type log-entity)]
                         (cond (keyword? t) t :else (:db/ident t)))]
        (is (= :mm/Log log-type) "Entity is a :mm/Log")
        (is (string? (:mm.memory/body-raw log-entity)))
        (is (re-find #"Narrative body" (:mm.memory/body-raw log-entity))
            ":mm.memory/body-raw carries the supplied narrative")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/link tests (W4.1 Increment J — session linkage)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest phase-work-link-updates-session-log-and-ended-at
  (testing "phase-work :phase/link sets :mm.session/log + :mm.session/ended-at on the session"
    (let [;; Bootstrap a session + process to be linked
          init-result (orchestrate/phase-work {:phase    :phase/initialize
                                               :workflow :workflow/session
                                               :context  (bootstrap-context)})
          session-eid (:session-eid init-result)
          ;; Author a :mm/Log
          author-result (orchestrate/phase-work {:phase      :phase/author
                                                 :process-id (:process-id init-result)
                                                 :context    (author-context)})
          log-eid       (:log-eid author-result)
          ;; Link them
          link-result   (orchestrate/phase-work {:phase      :phase/link
                                                 :process-id (:process-id init-result)
                                                 :context    {:session-eid session-eid
                                                              :log-eid     log-eid}})]
      (is (associative? (:session-entity link-result)))
      ;; Re-read the session and verify the slots are set
      (let [session (db/entity session-eid)
            log-ref (:mm.session/log session)
            log-id  (or (:db/id log-ref)
                        (when (number? log-ref) log-ref))]
        (is (= log-eid log-id) ":mm.session/log points at the new :mm/Log")
        (is (some? (:mm.session/ended-at session))
            ":mm.session/ended-at is set")))))

(deftest phase-work-link-rejects-missing-args
  (testing "phase-work :phase/link rejects missing :session-eid + :log-eid"
    (is (thrown? clojure.lang.ExceptionInfo
                 (orchestrate/phase-work {:phase      :phase/link
                                          :process-id 1
                                          :context    {}}))
        "Missing both → throws")
    (is (thrown? clojure.lang.ExceptionInfo
                 (orchestrate/phase-work {:phase      :phase/link
                                          :process-id 1
                                          :context    {:session-eid 42}}))
        "Missing :log-eid → throws")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; End-to-end integration tests — full open + handoff ceremonies
;;
;; These exercise the EXACT flow the rewritten /memory-open + /memory-handoff
;; skills will execute via the MCP verb in the next session.  Close the
;; A6 acceptance criterion of the Dan-directive 2026-05-27 (W4.1 readiness):
;; the NEXT session can confidently boot through the orchestrator-driven
;; flow without bricking.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest end-to-end-open-ceremony-orient-initialize-activate-imprint
  (testing "Full /memory-open ceremony: orient → initialize → activate → imprint"
    ;; Phase 1: :phase/orient (no process-id)
    (let [orient-result (orchestrate/orchestrate
                          {:workflow :workflow/session
                           :phase    :phase/orient})]
      (is (= :phase/orient (:phase-completed orient-result)))
      (is (= :phase/initialize (:next-phase orient-result)))
      (is (map? (:phase-work-result orient-result)))
      (let [orient-state (:phase-work-result orient-result)]
        ;; Phase 2: :phase/initialize (no process-id; CREATES the process)
        (let [init-result (orchestrate/orchestrate
                            {:workflow :workflow/session
                             :phase    :phase/initialize
                             :context  {:rel-path    (str "sessions/e2e-open-" (System/nanoTime) ".md")
                                        :name        "E2E open test session"
                                        :description "End-to-end /memory-open ceremony test"}})]
          (is (= :phase/initialize (:phase-completed init-result)))
          (is (number? (:created-process-id init-result))
              ":phase/initialize surfaces :created-process-id at top level")
          (let [process-id (:created-process-id init-result)]
            ;; Phase 3: :phase/activate (consumes process-id from initialize)
            (let [activate-result (orchestrate/orchestrate
                                    {:workflow   :workflow/session
                                     :process-id process-id
                                     :phase      :phase/activate
                                     :reason     "E2E test orientation complete"})]
              (is (= :phase/activate (:phase-completed activate-result)))
              (is (= [:session/start] (:transition-applied activate-result)))
              (is (false? (:degraded? activate-result)))
              (is (= 1 (count (:events-emitted activate-result)))
                  ":WorkflowSessionOpened event emitted")
              ;; Verify process advanced to :session/active
              (let [process       (wf/find-process process-id)
                    current-state (:workflow/state-name (wf/get-current-state process))]
                (is (= :session/active current-state)
                    "Process advances to :session/active after :phase/activate"))
              ;; Phase 4: :phase/imprint (composes banner from orient-state)
              (let [imprint-result (orchestrate/orchestrate
                                     {:workflow   :workflow/session
                                      :process-id process-id
                                      :phase      :phase/imprint
                                      :context    {:orient-state orient-state}})]
                (is (= :phase/imprint (:phase-completed imprint-result)))
                (is (nil? (:next-phase imprint-result)) "imprint is terminal in open ceremony")
                (is (string? (:phase-work-result imprint-result))
                    "Banner returned via :phase-work-result")))))))))

(deftest end-to-end-handoff-ceremony-capture-author-link-finalize
  (testing "Full /memory-handoff ceremony: capture → author → link → finalize"
    ;; Setup: bootstrap a session + activate it (so handoff has a real session to close)
    (let [init-result (orchestrate/orchestrate
                        {:workflow :workflow/session
                         :phase    :phase/initialize
                         :context  {:rel-path    (str "sessions/e2e-handoff-" (System/nanoTime) ".md")
                                    :name        "E2E handoff test session"
                                    :description "End-to-end /memory-handoff ceremony test"}})
          process-id  (:created-process-id init-result)
          session-eid (-> init-result :phase-work-result :session-eid)
          _           (orchestrate/orchestrate
                        {:workflow   :workflow/session
                         :process-id process-id
                         :phase      :phase/activate
                         :reason     "E2E setup"})]
      ;; Phase 1: :phase/capture
      (let [capture-result (orchestrate/orchestrate
                             {:workflow   :workflow/session
                              :process-id process-id
                              :phase      :phase/capture})]
        (is (= :phase/capture (:phase-completed capture-result)))
        (is (= :phase/author (:next-phase capture-result)))
        ;; Phase 2: :phase/author
        (let [author-result (orchestrate/orchestrate
                              {:workflow   :workflow/session
                               :process-id process-id
                               :phase      :phase/author
                               :context    {:narrative    "# E2E test handoff\n\nNarrative body."
                                            :rel-path     (str "logs/e2e-handoff-" (System/nanoTime) ".md")
                                            :name         "E2E handoff log"
                                            :description  "End-to-end handoff test log"}})
              log-eid       (-> author-result :phase-work-result :log-eid)]
          (is (= :phase/author (:phase-completed author-result)))
          (is (number? log-eid) ":mm/Log created with eid")
          ;; Phase 3: :phase/link
          (let [link-result (orchestrate/orchestrate
                              {:workflow   :workflow/session
                               :process-id process-id
                               :phase      :phase/link
                               :context    {:session-eid session-eid
                                            :log-eid     log-eid}})]
            (is (= :phase/link (:phase-completed link-result)))
            ;; Verify the session entity has :mm.session/log + :mm.session/ended-at set
            (let [session (db/entity session-eid)
                  log-ref (:mm.session/log session)
                  log-id  (or (:db/id log-ref)
                              (when (number? log-ref) log-ref))]
              (is (= log-eid log-id) ":mm.session/log links to the new :mm/Log")
              (is (some? (:mm.session/ended-at session))))
            ;; Phase 4: :phase/finalize
            (let [finalize-result (orchestrate/orchestrate
                                    {:workflow   :workflow/session
                                     :process-id process-id
                                     :phase      :phase/finalize
                                     :reason     "E2E test handoff complete"})]
              (is (= :phase/finalize (:phase-completed finalize-result)))
              (is (nil? (:next-phase finalize-result)) "finalize is terminal in handoff ceremony")
              (is (= [:session/close :session/finalize] (:transition-applied finalize-result)))
              (is (false? (:degraded? finalize-result)))
              ;; Verify process reached :session/closed terminal :success
              (let [process       (wf/find-process process-id)
                    current-state (wf/get-current-state process)]
                (is (= :session/closed (:workflow/state-name current-state)))
                (is (true? (:workflow/terminal? current-state)))
                (is (= :success (:workflow/terminal-kind current-state)))))))))))
