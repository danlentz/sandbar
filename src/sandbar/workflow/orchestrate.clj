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

   ## Status: W4.1 dispatcher loop landed 2026-05-26 — orchestrator namespace
   declared; public signature established; phase vocabulary canonicalized;
   per-phase workflow.transition application via `phase-transitions` lookup;
   κ P18 bootstrap-robustness fallback via `safe-transition!` (catches
   `:reason :transition-not-found` from sandbar.util.workflow/transition! →
   returns `:degraded? true`); phase-work multimethod (default no-op; per-
   phase methods extend incrementally as caller-side work migrates).
   Pending for follow-on commits: per-phase :mm/Fn entry/exit fn lookup
   (κ P4 — currently caller-side); migration of caller-side phase work into
   the phase-work multimethod; :mm.event/Workflow* event subclass authoring
   (`sandbar.util.event/log!` inside `sandbar.util.workflow/transition!`
   already emits `:workflow/transition` events — sufficient for ι.3 W4.1;
   the richer subclass hierarchy lands when `:mm.event/Workflow*` schema is
   authored); hard-fail criteria detection beyond `:transition-not-found`;
   `audit_fs-substrate-drift` integration in `:phase/orient` (behind
   `:audit-on-open? false` default per Q.ι.3.5); MCP verb registration as
   `sandbar.workflow.orchestrate`."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [sandbar.util.event :as event]
            [sandbar.util.workflow :as wf]))

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

(def phase-transitions
  "Map of phase keyword → ordered vec of workflow.transition names to apply
   for that phase.  Empty vec = no transition (pure-read or caller-side
   mutation phase).  Multi-element vec = sequential chain applied in order
   via sandbar.util.workflow/transition!.

   Canonical per the ι.3 design ratification:
   - `:phase/orient`     — no transition (pure read)
   - `:phase/initialize` — no transition (caller creates the :mm/Session +
                          workflow.start-process; process starts in
                          `:session/opening` initial state)
   - `:phase/activate`   — `:session/start` (opening → active)
   - `:phase/imprint`    — no transition (banner + discipline imprint)
   - `:phase/capture`    — no transition (pure read)
   - `:phase/author`     — no transition (caller authors :mm/Log)
   - `:phase/link`       — no transition (caller updates :mm.session/log)
   - `:phase/finalize`   — `:session/close` then `:session/finalize`
                          (active → closing → closed)"
  {:phase/orient     []
   :phase/initialize []
   :phase/activate   [:session/start]
   :phase/imprint    []
   :phase/capture    []
   :phase/author     []
   :phase/link       []
   :phase/finalize   [:session/close :session/finalize]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Phase-classification helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn ceremony-of
  "Classify `phase` as `:open` (member of `open-phases`), `:handoff` (member
   of `handoff-phases`), or `nil` (unknown phase).  Pure classification —
   no side effects."
  [phase]
  (cond
    (some #{phase} open-phases)    :open
    (some #{phase} handoff-phases) :handoff
    :else                          nil))

(defn next-phase-of
  "Return the next phase in the canonical-ordered vector for `phase`'s
   ceremony, or `nil` if `phase` is the terminal phase of its ceremony
   (`:phase/imprint` for open, `:phase/finalize` for handoff) or an
   unknown phase."
  [phase]
  (let [phases (case (ceremony-of phase)
                 :open    open-phases
                 :handoff handoff-phases
                 nil)]
    (when phases
      (let [idx (.indexOf ^java.util.List phases phase)]
        (when (and (>= idx 0) (< (inc idx) (count phases)))
          (nth phases (inc idx)))))))

(defn effective-timeout-ms
  "Resolve the timeout (in ms) for `phase`.  `timeouts` is an optional
   per-call override map; falls back to `default-phase-timeouts-ms`.

   Throws ex-info with `:reason :unknown-phase` if `phase` is not a member
   of either ceremony."
  [phase timeouts]
  (or (get timeouts phase)
      (get default-phase-timeouts-ms phase)
      (throw (ex-info "Unknown phase — no timeout default registered"
                      {:reason :unknown-phase
                       :phase  phase
                       :known  (set (keys default-phase-timeouts-ms))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow.transition application with κ P18 bootstrap-robustness fallback
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn safe-transition!
  "Apply a single workflow.transition with κ P18 bootstrap-robustness
   fallback.  Resolves the process via `wf/find-process` (handles eid OR
   entity input), then calls `wf/transition!`.

   Returns:
     {:applied <transition-name> :degraded? false}             — success
     {:applied nil :degraded? true :reason :transition-not-found
      :transition <name>}                                       — κ P18 fallback

   Other ex-info reasons (`:guard-not-met` / `:requires-reason` /
   `:process-not-found`) PROPAGATE as hard failures — they're not bootstrap-
   degraded states, they're workflow-semantics violations the caller should
   surface.

   Per the ι.3 design ratification Q.ι.3.2 (detect-and-fallback per κ P18,
   NOT detect-and-fail per ι authorization Dan-directive 1 'we don't want
   to brick')."
  [process-or-id transition-name & {:keys [context actor reason]}]
  (try
    (let [process (if (number? process-or-id)
                    (or (wf/find-process process-or-id)
                        (throw (ex-info "Process not found"
                                        {:reason :process-not-found
                                         :process-id process-or-id})))
                    process-or-id)]
      (wf/transition! process transition-name
                      :context context
                      :actor   actor
                      :reason  reason)
      {:applied transition-name :degraded? false})
    (catch clojure.lang.ExceptionInfo e
      (let [exception-reason (-> e ex-data :reason)]
        (if (= exception-reason :transition-not-found)
          ;; Recoverable: degrade gracefully per κ P18.
          (do
            (log/warn :ORCHESTRATE/BOOTSTRAP-DEGRADED
                      {:transition       transition-name
                       :process-id       (if (number? process-or-id)
                                           process-or-id
                                           (:db/id process-or-id))
                       :reason           :transition-not-found
                       :substrate-cite   :memory.observations/workflow_transition_verb_identless_unreachable_2026_05_26
                       :fallback-cite    :kappa/P18})
            {:applied nil :degraded? true :reason :transition-not-found
             :transition transition-name})
          ;; Non-recoverable: propagate (hard failure).
          (throw e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Event emission (Q.ι.3.9 STRICT Event Substrate ADR compliance)
;;
;; The orchestrator emits one :mm.event/Workflow* event per phase boundary:
;;   - Successful phase completion → emit the phase-completion event registered
;;     in `phase-completion-event-class` (no entry → no emission for that phase)
;;   - κ P18 bootstrap-robustness fallback engaged → emit :mm.event/WorkflowSessionDegraded
;;     carrying :mm.workflow-event/degraded-reason
;;
;; Emission happens via `sandbar.util.event/log-event!` which creates a typed-
;; :dt/Event subclass instance via `dt/make` + applies the substrate event-
;; logging pipeline (timestamp, level, etc.).  Emission is best-effort
;; observability — failure to emit does NOT prevent the orchestrate fn from
;; returning its result map; emission errors are logged but swallowed.
;;
;; Event subclasses are declared in `schema/mm-temporal.edn` per the
;; :mm.event/WorkflowTransition hierarchy authored in W4.1 Increment A.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def phase-completion-event-class
  "Map of phase keyword → :mm.event/Workflow* event class to emit on successful
   (non-degraded) phase completion.  Phases NOT in this map don't emit a
   phase-completion event from orchestrate — caller-side work emits its own
   events (or the phase is pure-read with no emission needed).

   Canonical per the ι.3 design ratification §Q.ι.3.9:
   - `:phase/activate` → `:mm.event/WorkflowSessionOpened`
   - `:phase/author`   → `:mm.event/WorkflowSessionHandoffAuthored`
   - `:phase/finalize` → `:mm.event/WorkflowSessionClosed`

   The other 5 phases (:orient, :initialize, :imprint, :capture, :link) do not
   currently emit phase-completion events from orchestrate — they fold into
   the lifecycle envelope of the 3 emission-bearing phases above.  Future
   evolution may add per-phase events for richer observability."
  {:phase/activate :mm.event/WorkflowSessionOpened
   :phase/author   :mm.event/WorkflowSessionHandoffAuthored
   :phase/finalize :mm.event/WorkflowSessionClosed})

(defn- try-emit!
  "Wrap an event emission in try/catch — emission failure logs but does NOT
   propagate.  Returns the event entity-map on success, nil on failure.
   Per `no_race_highest_quality` + κ P18 spirit: observability is best-effort,
   not blocking."
  [event-class slot-map]
  (try
    (event/log-event! event-class slot-map)
    (catch Exception e
      (log/warn e :ORCHESTRATE/EVENT-EMIT-FAILED
                {:event-class event-class
                 :slot-map    slot-map})
      nil)))

(defn- emit-phase-completion-event!
  "Emit the phase-completion event for `phase` (when registered in
   `phase-completion-event-class`).  Returns the event entity-map, or nil
   if the phase doesn't register an emission class.  `transition` may be
   nil for phases that don't bear transitions."
  [phase {:keys [process-id transition]}]
  (when-let [event-class (get phase-completion-event-class phase)]
    (try-emit! event-class
               (cond-> {:mm.workflow-event/process process-id
                        :mm.workflow-event/phase   phase
                        :event/name                (str (name event-class))
                        :event/level               :info
                        :event/kind                :workflow/phase-completion}
                 transition (assoc :mm.workflow-event/transition transition)))))

(defn- emit-degraded-event!
  "Emit :mm.event/WorkflowSessionDegraded when κ P18 bootstrap-robustness
   fallback engages.  Returns the event entity-map."
  [{:keys [process-id phase transition reason]}]
  (try-emit! :mm.event/WorkflowSessionDegraded
             (cond-> {:mm.workflow-event/process         process-id
                      :mm.workflow-event/phase           phase
                      :mm.workflow-event/degraded-reason (or reason :transition-not-found)
                      :event/name                        "WorkflowSessionDegraded"
                      :event/level                       :warn
                      :event/kind                        :workflow/degraded}
               transition (assoc :mm.workflow-event/transition transition))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Phase-work multimethod (extensible via :mm/Fn entry/exit per κ P4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti phase-work
  "Multimethod dispatching phase-specific work, keyed by `:phase` in the
   args map.  Default method is a no-op — most phase work currently lives
   caller-side (in slash command bodies); methods are added incrementally
   as caller-side work migrates per the ι.4 + ι.6 skill-rewrite arcs.

   Future evolution (κ P4): phase methods MAY resolve to `:mm/Fn` entries
   stored in the substrate, allowing operator-replaceable phase semantics
   without recompilation.  ι.3 W4.1 keeps the multimethod surface +
   default-no-op shape stable; the :mm/Fn lookup hook lands when caller-
   side work begins migrating in.

   Returns: optional map of phase-work outputs (caller-visible; merged
   into the orchestrate result map under `:phase-work-result`).  May
   return `nil` (default no-op).  May throw ex-info with `:reason` in
   `hard-fail-criteria` for non-recoverable phase failures."
  :phase)

(defmethod phase-work :default [_args] nil)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orchestrator entry point — W4.1 dispatcher loop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- validate-args!
  "Validate required orchestrate args.  Throws ex-info on missing keys or
   unknown phase."
  [{:keys [workflow process-id phase] :as args}]
  (when-not workflow
    (throw (ex-info "Missing required arg :workflow"
                    {:reason :missing-required-arg :key :workflow :args args})))
  (when-not process-id
    (throw (ex-info "Missing required arg :process-id"
                    {:reason :missing-required-arg :key :process-id :args args})))
  (when-not phase
    (throw (ex-info "Missing required arg :phase"
                    {:reason :missing-required-arg :key :phase :args args})))
  (when-not (ceremony-of phase)
    (throw (ex-info (str "Unknown phase " phase " — not a member of open-phases or handoff-phases")
                    {:reason :unknown-phase
                     :phase  phase
                     :known  {:open-phases    open-phases
                              :handoff-phases handoff-phases}}))))

(defn orchestrate
  "ι.3 substrate orchestrator entry-point.  Drives a workflow.process through
   its phases via workflow.transition (one phase per call; caller invokes
   sequentially across the ceremony's phases per `open-phases` /
   `handoff-phases`).

   ## Arguments
     args - map with required keys:
       :workflow       Workflow definition ident (e.g., `:workflow/session`)
       :process-id     Numeric workflow.process eid
       :phase          Phase keyword (one of `open-phases` or `handoff-phases`)
     Optional:
       :context        Map of context data passed to phase work + transitions
       :actor          Entity ref for the workflow.transition history actor slot
       :reason         Reason string for transitions whose
                       `:workflow/requires-reason?` is true
       :timeouts       Per-phase timeout override map (else `default-phase-timeouts-ms`)
       :audit-on-open? Boolean — invoke `audit_fs-substrate-drift` in :phase/orient
                       (default false per Q.ι.3.5; not yet wired in W4.1)

   ## Returns
     Map with keys:
       :phase-completed     The phase that just completed (= args :phase)
       :next-phase          The next phase to invoke (nil if at terminal phase
                            of this ceremony)
       :transition-applied  Vec of workflow.transition names applied this
                            phase (empty vec for pure-read / caller-side-
                            mutation phases; one or more for transition-
                            bearing phases per `phase-transitions`)
       :events-emitted      Vec of event eids emitted by THIS orchestrator
                            invocation.  Empty in W4.1 — events emit
                            transitively via `sandbar.util.workflow/transition!`
                            calling `sandbar.util.event/log!` on the
                            `:workflow/transition` channel.  Richer
                            `:mm.event/Workflow*` subclass emission lands
                            when those event classes are authored.
       :duration-ms         Phase duration in milliseconds
       :degraded?           True if κ P18 bootstrap-robustness fallback
                            engaged (i.e., at least one transition was
                            :reason :transition-not-found and degraded
                            instead of throwing)
       :phase-work-result   Whatever the `phase-work` multimethod returned
                            (often nil for the default no-op method)

   ## Failure modes
     Throws `ex-info` with `:reason` :missing-required-arg / :unknown-phase
     for argument-validation failures.  Propagates workflow-semantics
     exceptions (`:guard-not-met`, `:requires-reason`, `:process-not-found`)
     from `wf/transition!`.  Catches `:transition-not-found` (κ P18
     fallback) and surfaces via `:degraded? true` in the result map.

   ## Per κ P18 (bootstrap-robustness)
     `:transition-not-found` is the canonical recoverable failure shape —
     resolves via sandbar restart (compiled-cache rebuild).  The orchestrator
     does NOT brick on this failure; it degrades gracefully so the calling
     skill (e.g., /memory-open) can fall back to its prior text-protocol
     behavior.  See `:memory.observations/workflow_transition_verb_identless_unreachable_2026_05_26`
     for the empirical reproduction this design is grounded in.

   ## See also
     - ι.3 design ratification ADR: `:memory.decisions/iota_3_substrate_orchestrator_design_ratification_2026_05_26`
     - κ library synthesis: `:memory.libraries.synthesis/stateful_workflow_substrate_design_foundations_…_2026_05_25`
     - Wave-2 strategic plan §6.W4.1: `:memory.plans/sandbar_0_2_0_release_comprehensive_strategic_re_plan_wave_2_revision_2026_05_26`"
  [args]
  (validate-args! args)
  (let [{:keys [phase context actor reason timeouts]} args
        start-instant   (System/currentTimeMillis)
        _timeout-ms     (effective-timeout-ms phase timeouts)  ;; reserved for future timeout wiring
        ;; Run phase-work multimethod (default no-op; per-phase methods extend).
        phase-result    (phase-work args)
        ;; Apply the per-phase workflow.transition chain (often empty).
        transitions     (get phase-transitions phase [])
        outcomes        (reduce (fn [acc tname]
                                  (conj acc
                                        (safe-transition! (:process-id args) tname
                                                          :context context
                                                          :actor   actor
                                                          :reason  reason)))
                                []
                                transitions)
        applied         (->> outcomes (keep :applied) vec)
        degraded?       (boolean (some :degraded? outcomes))
        ;; Q.ι.3.9 event emission — one event per phase boundary:
        ;;   degraded? true  → :mm.event/WorkflowSessionDegraded (carries reason)
        ;;   degraded? false → phase-completion event if registered (else nothing)
        emitted-event   (if degraded?
                          (let [degraded-outcome (some #(when (:degraded? %) %) outcomes)]
                            (emit-degraded-event!
                             {:process-id (:process-id args)
                              :phase      phase
                              :transition (:transition degraded-outcome)
                              :reason     (:reason degraded-outcome)}))
                          (emit-phase-completion-event!
                           phase
                           {:process-id (:process-id args)
                            :transition (last applied)}))
        events-emitted  (if emitted-event
                          [(:db/id emitted-event)]
                          [])
        duration-ms     (- (System/currentTimeMillis) start-instant)]
    (log/info :ORCHESTRATE/PHASE-COMPLETE
              {:phase             phase
               :ceremony          (ceremony-of phase)
               :transitions       applied
               :degraded?         degraded?
               :events-emitted    events-emitted
               :duration-ms       duration-ms
               :process-id        (:process-id args)
               :workflow          (:workflow args)})
    {:phase-completed    phase
     :next-phase         (next-phase-of phase)
     :transition-applied applied
     :events-emitted     events-emitted
     :duration-ms        duration-ms
     :degraded?          degraded?
     :phase-work-result  phase-result}))
