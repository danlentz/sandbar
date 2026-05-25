(ns sandbar.workflow.session
  "Session-orientation workflow transition guards + effects (ι.2 substrate).

   Companion to `schema/workflow-session.edn`.  Implements the 4 new transition
   guards/effects added by ι.2 per Q.ι.6 (states :session/paused + :session/failed)
   + Q.ι.13 (transitions as first-class :mm/Fn entities).

   ## Why plain `defn` instead of `defdbfn`?

   The existing sandbar.workflow runtime invokes guards/effects with signature
   `(fn [process context] -> boolean|nil)` — application-space Clojure fns whose
   value is consumed by sandbar.util.workflow/transition!  (line ~285+, the
   guard-evaluation code path).  `defdbfn` emits a Datomic `:db/fn` schema entity
   whose body MUST be transactor-shaped `(fn [db ...] -> tx-data)` — wrong shape
   for workflow guards/effects.

   Q.ι.13 ratified \"transition guards + effects ARE first-class :mm/Fn entities.\"
   These plain `defn`s satisfy Q.ι.13 via post-commit `:mm/Fn` memorial authoring
   (the closure observation memorial captures the fns as `:mm/Fn` instances with
   `:dt.fn/source-ns \"sandbar.workflow.session\"` + `:dt.fn/source-var <name>`).
   Future arc may extend `defdbfn` with `:dt.fn/installed-as :clojure-fn-only`
   flag to suppress `:db/fn` emission + auto-author `:mm/Fn` memorials — but
   that's substrate-extension out-of-scope for ι.2.

   ## Composition

   - sandbar.util.workflow runtime invokes these via `(resolve symbol)` →
     `(apply f [process context])` (the symbol is stored in `:workflow/guard`
     or `:workflow/on-transition` slot per workflow.edn's `:db.type/symbol`
     range).
   - Effect functions LOG via clojure.tools.logging (the metrics-vocabulary
     event-firing per Q.ι.12 + Keystone Event Substrate ADR is deferred to a
     follow-on commit — requires authoring `:mm.event/Session-*` event classes
     first, which is substrate-extension).
   - State-change tx-data is RETURNED by effects (sandbar.util.workflow runtime
     applies the tx-data alongside the standard state-transition CAS).

   ## See also

   - `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_session_workflow_substrate_design_fs_audit_scope_finalized_2026_05_25`
     — the ratification ADR this code implements
   - `:memory.libraries.synthesis/stateful_workflow_substrate_design_foundations_…_kappa_sub_arc_2026_05_25`
     — κ pattern catalog (P6 maintenance-mode + P7 verb-family + P8 hard-fail)
   - `schema/workflow-session.edn` — the workflow definition this namespace's
     fns are referenced from"
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transition guards
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn can-pause-maintenance?
  "Guard for :session/pause-maintenance transition (active → paused).

   Per κ P6 maintenance-mode: allow the transition if the requesting actor
   owns the session OR has explicit `:authorization/maintenance-mode` scope.
   This is the minimum policy for the 0.2.0 release; richer authorization
   modeling (via :mm/Actor + :mm/Context cross-axis) lands in a follow-on
   when the actor-context substrate matures.

   Arguments:
     process — :workflow/Process entity (the session-process being paused)
     context — optional context map; if it carries :actor, that actor is
               checked.  Defaults to permissive (any caller can pause) when
               no actor is supplied — appropriate during early 0.2.0 rollout.

   Returns boolean."
  [process context]
  (let [actor (:actor context)]
    (cond
      ;; No actor in context — permissive (pre-actor-context-substrate).
      (nil? actor)
      (do (log/info :SESSION/PAUSE-MAINTENANCE-GUARD :allow :reason :no-actor-supplied)
          true)

      ;; Actor IS the session's subject — owner can always pause.
      (= (:db/id actor) (:db/id (:workflow/subject process)))
      (do (log/info :SESSION/PAUSE-MAINTENANCE-GUARD :allow :reason :actor-is-session-owner)
          true)

      ;; Actor has maintenance-mode authorization scope.
      (some #{:authorization/maintenance-mode} (:mm.actor/authorizations actor))
      (do (log/info :SESSION/PAUSE-MAINTENANCE-GUARD :allow :reason :actor-has-maintenance-scope)
          true)

      ;; Default deny.
      :else
      (do (log/info :SESSION/PAUSE-MAINTENANCE-GUARD :deny :actor (:db/id actor))
          false))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transition effects (side-effect functions returning tx-data for the runtime)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-pause-maintenance
  "Effect for :session/pause-maintenance (active → paused).

   Records the pre-pause state on the process entity via
   `:mm.session-process/paused-from-state` slot (declared in schema/workflow.edn
   by ι.2) so `:session/resume-maintenance` can restore intent.  Emits a
   structured-log event per Q.ι.12 metrics vocabulary.

   Event-class authoring (`:mm.event/Session-paused-maintenance-engaged`) is
   deferred to a follow-on commit; for ι.2, log-only.

   Returns tx-data vector for the workflow runtime to merge with the standard
   state-transition CAS."
  [process context]
  (let [from-state-keyword (:workflow/state-name (:workflow/current-state process))
        reason (:reason context)]
    (log/info :SESSION/PAUSE-MAINTENANCE-ENGAGED
              {:process-eid (:db/id process)
               :from-state from-state-keyword
               :reason reason
               :actor-eid (some-> (:actor context) :db/id)})
    [[:db/add (:db/id process) :mm.session-process/paused-from-state from-state-keyword]]))

(defn on-resume-maintenance
  "Effect for :session/resume-maintenance (paused → active).

   Retracts the `:mm.session-process/paused-from-state` slot (the resume-target
   marker) and emits a structured-log event.

   Returns tx-data vector."
  [process context]
  (let [paused-from (:mm.session-process/paused-from-state process)
        reason (:reason context)]
    (log/info :SESSION/RESUME-MAINTENANCE-COMPLETE
              {:process-eid (:db/id process)
               :paused-from-state paused-from
               :reason reason
               :actor-eid (some-> (:actor context) :db/id)})
    (when paused-from
      [[:db/retract (:db/id process) :mm.session-process/paused-from-state paused-from]])))

(defn on-fail
  "Effect for both :session/fail (active → failed) and :session/fail-from-paused
   (paused → failed).  Per κ P8 hard-fail: records the failure rationale +
   instant on the process entity; emits a structured-log event.

   Both transitions require a non-empty reason (per the `:workflow/requires-reason?`
   slot in workflow-session.edn).  The reason is captured in the
   `:mm.session-process/failure-reason` slot for audit.

   Returns tx-data vector."
  [process context]
  (let [reason (:reason context)
        failure-instant (java.util.Date.)
        from-state-keyword (:workflow/state-name (:workflow/current-state process))]
    (log/warn :SESSION/FAIL
              {:process-eid (:db/id process)
               :from-state from-state-keyword
               :reason reason
               :failure-instant failure-instant
               :actor-eid (some-> (:actor context) :db/id)})
    [[:db/add (:db/id process) :mm.session-process/failure-reason (str reason)]
     [:db/add (:db/id process) :mm.session-process/failure-instant failure-instant]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comment block — sanity checks for REPL use
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  ;; After schema-load:
  ;;   - 6 states queryable: :session/{opening,active,paused,closing,closed,failed}
  ;;   - 9 transitions on :workflow/session (5 pre-existing + 4 new)
  ;;
  ;; Verify via:
  ;;   (require '[sandbar.util.workflow :as wf])
  ;;   (wf/find-state :session/paused)
  ;;   (wf/find-transition :session/pause-maintenance :session/active)
  ;;
  ;; The 4 transition-fn :mm/Fn memorials get authored post-commit by the
  ;; closure observation memorial (entity_create with :dt/type :mm/Fn +
  ;; :dt.fn/source-ns "sandbar.workflow.session" + :dt.fn/source-var
  ;; <name>).
  )
