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
  (is (= :missing-required-arg
         (ex-info-with-reason
          #(orchestrate/orchestrate {:workflow :workflow/session :phase :phase/orient})))))

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
