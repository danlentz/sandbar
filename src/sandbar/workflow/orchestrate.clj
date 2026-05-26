(ns sandbar.workflow.orchestrate
  "ι.3 substrate orchestrator — workflow-driven dispatcher for session lifecycle.

   Authored per the ι.3 substrate orchestrator design ratification:
   `:memory.decisions/iota_3_substrate_orchestrator_design_ratification_2026_05_26`
   (12 Q-checkpoints resolved). Composes κ patterns P3 (CAS atomicity) + P4
   (transition-fns-as-:mm/Fn) + P5 (schema-as-data) + P7 (step/jump/resume
   verbs) + P8 (hard-fail) + P10 (dual-source history) + P14 (per-phase
   timeout) + P18 (bootstrap robustness with degraded-path fallback).

   ## Phase decomposition

   ### /memory-open ceremony (4 phases)
   - `:phase/orient`     — pure-read: aggregate.rank-by prior :mm/Session +
                          entity.find prior handoff log + corpus stats +
                          workflow.active-processes (no workflow transition)
   - `:phase/initialize` — entity.create new :mm/Session + workflow.start-process
                          (creates new workflow.process in :session.state/opening)
   - `:phase/activate`   — entity.update :mm.session/workflow-process +
                          workflow.transition :session.transition/start
                          (:session.state/opening → :session.state/active)
   - `:phase/imprint`    — banner output + discipline imprint
                          (no workflow transition; pure write)

   ### /memory-handoff ceremony (4 phases; refined at ι.6)
   - `:phase/capture`    — workflow.process-history + recent entity.find
                          snapshots + commits queried (pure read)
   - `:phase/author`     — entity.create :class :mm/Log with body-raw drafted
   - `:phase/link`       — entity.update :mm.session/log + :mm.session/ended-at
   - `:phase/finalize`   — workflow.transition :session.transition/close then
                          workflow.transition :session.transition/finalize
                          (:session.state/active → :session.state/closing →
                           :session.state/finalized)

   ## Hard-fail criteria (κ P8)

   Workflow.process advances to `:session.state/failed` (terminal) on:
   - `:schema-corrupt`                    — workflow definition load failed
   - `:substrate-unreachable-persistent`  — ≥3 reconnect attempts failed
   - `:phase-timeout`                     — per-phase timeout exceeded
   - `:consecutive-transition-rejects-n`  — N consecutive transition rejections
                                            (default N=3)

   ## Per-phase timeout policy (κ P14)

   Defaults (operator-configurable via `:workflow.phase/timeout-ms` slot):

   | Phase                 | Default timeout (ms) | Rationale                              |
   |-----------------------|----------------------|----------------------------------------|
   | `:phase/orient`       | 90,000  (90s)        | Multi-MCP read; slow corpus tolerated  |
   | `:phase/initialize`   | 30,000  (30s)        | Single entity.create + start-process   |
   | `:phase/activate`     | 10,000  (10s)        | Single entity.update + transition      |
   | `:phase/imprint`      | 60,000  (60s)        | Banner generation; AI-side latency     |
   | `:phase/capture`      | 90,000  (90s)        | Multi-source delta capture             |
   | `:phase/author`       | 60,000  (60s)        | Body-raw generation                    |
   | `:phase/link`         | 10,000  (10s)        | Two entity.updates                     |
   | `:phase/finalize`     | 10,000  (10s)        | Two workflow.transitions               |

   ## Bootstrap-robustness fallback (κ P18) — empirically grounded

   Per the workflow.transition substrate-gap reproduction (resolved this
   session via Q.ι.3.11 substrate-code fix in sandbar.util.workflow):
   `:memory.observations/workflow_transition_verb_identless_unreachable_2026_05_26`.
   The orchestrator handles 3 failure modes gracefully:

   1. `:transition-not-found`       — compiled-cache stale → degraded text-protocol fallback
   2. `:substrate-unreachable`      — MCP connection lost → degraded text-protocol fallback
   3. `:schema-corrupt`             — workflow definition load failed → hard-fail

   ## STRICT Event Substrate compliance (Q.ι.15)

   Every phase transition emits `:mm.event/WorkflowTransition` per the Keystone
   Event Substrate ADR D.4. NO parallel event bus; NO CQRS; class-hierarchical
   subscription via `:dt/type-isa?` cache.

   Specific event subclasses introduced in ι.3:
   - `:mm.event/WorkflowSessionOpened`           — emitted at `:phase/activate` completion
   - `:mm.event/WorkflowSessionHandoffAuthored`  — emitted at `:phase/author` completion
   - `:mm.event/WorkflowSessionClosed`           — emitted at `:phase/finalize` completion
   - `:mm.event/WorkflowSessionDegraded`         — emitted on bootstrap-fallback engagement
   - `:mm.event/WorkflowSessionFailed`           — emitted on hard-fail

   ## Status: W4.1 scaffold landed 2026-05-26 — orchestrator namespace declared;
   public signature established; phase vocabulary canonicalized. Implementation
   of the phase entry/exit fns (composing κ P4 :mm/Fn pattern) + the dispatcher
   loop + the degraded-path fallback + the MCP verb registration follow in
   subsequent commits."
  (:require [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Phase vocabulary (canonical per the ι.3 design ratification)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def open-phases
  "Canonical ordered phases for the /memory-open ceremony."
  [:phase/orient
   :phase/initialize
   :phase/activate
   :phase/imprint])

(def handoff-phases
  "Canonical ordered phases for the /memory-handoff ceremony.
   Refined at ι.6 implementation."
  [:phase/capture
   :phase/author
   :phase/link
   :phase/finalize])

(def default-phase-timeouts-ms
  "Default per-phase timeouts in milliseconds. Operator-configurable via
   `:workflow.phase/timeout-ms` slot in workflow definition. Per κ P14."
  {:phase/orient     90000
   :phase/initialize 30000
   :phase/activate   10000
   :phase/imprint    60000
   :phase/capture    90000
   :phase/author     60000
   :phase/link       10000
   :phase/finalize   10000})

(def hard-fail-criteria
  "Canonical hard-fail conditions per κ P8. When any of these fire, the
   orchestrator advances the workflow.process to `:session.state/failed`
   (terminal-kind :failure) and emits `:mm.event/WorkflowSessionFailed`."
  #{:schema-corrupt
    :substrate-unreachable-persistent
    :phase-timeout
    :consecutive-transition-rejects-n})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orchestrator entry point — public signature (implementation pending W4.1)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn orchestrate
  "ι.3 substrate orchestrator entry-point. Drives a workflow.process through
   its phases via workflow.transition.

   ## Arguments
     args - map with required keys:
       :workflow       Workflow definition ident (e.g., `:workflow/session`)
       :process-id     Numeric workflow.process eid
       :phase          Phase keyword (one of `open-phases` or `handoff-phases`)
     Optional:
       :context        Map of context data passed to phase entry/exit fns
       :timeouts       Per-phase timeout override map (else `default-phase-timeouts-ms`)
       :audit-on-open? Boolean — invoke `audit_fs-substrate-drift` in :phase/orient
                       (default false per Q.ι.3.5)

   ## Returns
     Map with keys:
       :phase-completed     The phase that just completed
       :next-phase          The next phase to invoke (nil if at terminal)
       :transition-applied  The workflow.transition applied (nil if no transition)
       :events-emitted      Vec of `:mm.event/Workflow*` event eids emitted
       :duration-ms         Phase duration in milliseconds
       :degraded?           True if degraded-path fallback engaged

   ## Failure modes
     Throws `ex-info` with `:reason` keyed by `hard-fail-criteria` for
     non-recoverable conditions. Recoverable conditions (e.g., transient
     transition-not-found per compiled-cache stale) trigger degraded-path
     fallback per κ P18.

   ## Implementation status
     W4.1 scaffold — signature declared; dispatcher loop + phase entry/exit fns
     + degraded-path fallback land in subsequent commits per the ι.3 design
     ratification verification criteria (ι.3 design plan §verification)."
  [args]
  (throw (ex-info "ι.3 orchestrator implementation pending — W4.1 scaffold only"
                  {:reason      :not-yet-implemented
                   :args        args
                   :see-also    [:memory.decisions/iota_3_substrate_orchestrator_design_ratification_2026_05_26
                                 :memory.plans/sandbar_0_2_0_release_comprehensive_strategic_re_plan_wave_2_revision_2026_05_26]
                   :commit-pending "subsequent W4.1 commits will land the phase entry/exit fns + dispatcher loop"})))
