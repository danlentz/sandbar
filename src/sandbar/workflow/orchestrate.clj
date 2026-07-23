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
   - `:phase/link`       — entity.update :mm.session/log ONLY (ended-at moved
                          to :phase/finalize per Bug-1 leak-coupling 2026-05-29)
   - `:phase/finalize`   — workflow.transition :session.transition/close then
                          :session.transition/finalize, THEN (only on reaching
                          a TERMINAL state) entity.update :mm.session/ended-at —
                          coupling ended-at to a successful close so it can never
                          be set while the process is non-terminal
                          (:session.state/active → :session.state/closing →
                           :session.state/closed)

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
            [sandbar.aggregate :as aggregate]
            [sandbar.api.projection :as projection]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.store :as store]
            [sandbar.util.event :as event]
            [sandbar.util.workflow :as wf])
  (:import [java.util Date]))

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

(defn- emit-failed-event!
  "Emit :mm.event/WorkflowSessionFailed when a hard-fail criterion is
   detected (per `hard-fail-criteria`).  Returns the event entity-map.
   Per Q.ι.3.4 + Q.ι.3.9."
  [{:keys [process-id phase reason]}]
  (try-emit! :mm.event/WorkflowSessionFailed
             {:mm.workflow-event/process        process-id
              :mm.workflow-event/phase          phase
              :mm.workflow-event/failure-reason (or reason :phase-timeout)
              :event/name                       "WorkflowSessionFailed"
              :event/level                      :error
              :event/kind                       :workflow/failed
              :event/status                     :failure}))

(def ^:private current-state-to-fail-transition
  "Per the :workflow/session lifecycle, the fail-shaped transition canonical
   for a given current state.  Used by `try-transition-to-failed!` to pick
   the right transition based on where the process is in its lifecycle."
  {:session/opening :session/fail-from-opening
   :session/active  :session/fail
   :session/paused  :session/fail-from-paused})

(defn- try-transition-to-failed!
  "Best-effort: advance `process-id` to `:session/failed` via the
   state-appropriate fail-transition.  Returns true on success, false
   if no fail-transition is reachable from the current state OR the
   transition itself fails for any reason.

   The orchestrator emits `:mm.event/WorkflowSessionFailed` BEFORE
   calling this — observability is preserved even when the underlying
   transition fails.  Per κ P8 + the principle that hard-fail
   detection is independent of the substrate's ability to model the
   failed state.

   `reason-str` is the human-readable rationale carried in the
   workflow.history (the underlying `:session.transition/fail*` has
   `:workflow/requires-reason? true`)."
  [process-id reason-str]
  (try
    (let [process (wf/find-process process-id)]
      (when process
        (let [current-state-name (:workflow/state-name (wf/get-current-state process))
              fail-transition    (get current-state-to-fail-transition current-state-name)]
          (if fail-transition
            (do (wf/transition! process fail-transition :reason reason-str)
                true)
            (do (log/warn :ORCHESTRATE/FAIL-TRANSITION-NOT-REACHABLE
                          {:process-id    process-id
                           :current-state current-state-name
                           :reason        reason-str})
                false)))))
    (catch Exception e
      (log/warn e :ORCHESTRATE/FAIL-TRANSITION-FAILED
                {:process-id process-id
                 :reason     reason-str})
      false)))

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
;; phase-work :phase/orient — FIRST multimethod method (Increment G)
;;
;; Encapsulates the orientation queries currently performed imperatively in
;; the /memory-open skill body Steps 1-3:
;;   1. Latest :mm/Session by :mm.session/started-at descending → prior session
;;   2. Prior session's :mm.session/log ref → handoff log
;;   3. :mm/Memory corpus count + group-by :dt/type → memorial histogram
;;   4. Top-5 :mm/Plan by :mm.memory/last-touched → active arcs
;;   5. Top-5 :mm/Task by :mm.memory/last-touched → ready queue
;;   6. All active (non-terminal) workflow.processes
;;
;; The returned map is bubbled up through orchestrate's `:phase-work-result`
;; slot — the future thin-wrapper /memory-open invocation extracts orient-
;; state from there for banner composition.
;;
;; This is the FIRST step in the multi-increment phase-work multimethod
;; migration arc.  When all 8 phase-work methods land + the skills are
;; rewritten as thin wrappers, /memory-open + /memory-handoff collapse to
;; ~10 lines per Q.ι.3.12.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- orient-prior-session
  "Look up the chronologically-latest :mm/Session entity.  Returns the
   entity-map (or nil if no sessions exist yet — first-session edge case)."
  []
  (let [{:keys [hits]} (aggregate/rank-by {:class         :mm/Session
                                           :rank-by       :recency
                                           :temporal-slot :mm.session/started-at
                                           :limit         1
                                           :projection    :full})]
    (some-> hits first :entity)))

(defn- pull-full
  "Pull a full-projection entity-map for `ref` (eid, ident, or map carrying
   :db/id).  Returns nil when `ref` doesn't resolve.

   Used by orient phase to deepen `:prior-log` and `:in-flight-plan` beyond
   the metadata-only nested-ref shape that `:full` top-level projection
   bounds nested ref-slots to (per sandbar.api.projection
   `project-nested-value` one-hop-deep recursion rule).  Body-raw +
   :mm.plan/stage + :mm.memory/description live on the realized full
   entity-map, which `:phase/imprint` needs for trajectory extraction."
  [ref]
  (let [eid (cond
              (number? ref)  ref
              (keyword? ref) ref                    ;; ident — db/entity resolves
              (map? ref)     (:db/id ref)
              :else          nil)]
    (when eid
      (some-> (db/entity eid) projection/full-projection))))

(defn- orient-prior-log
  "Resolve the prior session's :mm.session/log ref to a FULL-projection
   :mm/Log entity-map.  The prior session is fetched at :full top-level
   projection — but per the one-hop-deep recursion rule, its :mm.session/log
   ref-slot ships as metadata-only ({:db/id N :dt/type :mm/Log}).  We
   re-pull at :full here so :phase/imprint can extract the §\"next move\"
   narrative + .claude plan-file pointer from the log's body-raw.

   Returns nil if the prior session has no log (closed-without-handoff
   case OR first-session edge)."
  [prior-session]
  (when-let [log-ref (:mm.session/log prior-session)]
    (pull-full log-ref)))

(defn- orient-in-flight-plan
  "Return the #1 most-recently-touched :mm/Plan at :full projection.

   The in-flight plan is the head of the active-plans list (which the rest
   of orient pulls at :metadata-only for bandwidth economy).  Surfaced at
   :full so :phase/imprint can render arc trajectory: plan name +
   :mm.plan/stage + .claude plan-file path (regex-extracted from body-raw)
   + 'next move' (regex-extracted from prior-log body-raw).

   Returns nil when no plans exist (first-session-ever edge).

   Bandwidth bound: ~5-10KB per typical in-flight plan (body-raw).  Well
   under the wire-limit overflow case Gap #1 surfaced; bounded to top-1
   only, not top-N."
  []
  (let [{:keys [hits]} (aggregate/rank-by
                         {:class         :mm/Plan
                          :rank-by       :recency
                          :temporal-slot :mm.memory/last-touched
                          :limit         1
                          :projection    :full})]
    (some-> hits first :entity)))

(defn- resolve-type-histogram
  "Resolve a group-by-:dt/type histogram's keys from ref-eids (or idents) to
   class-ident keywords, so the banner renders human-readable class names
   instead of raw entity-ids.  Keys already keyword-shaped pass through."
  [hist]
  (into {}
        (map (fn [[k v]]
               [(cond
                  (keyword? k) k
                  (number? k)  (or (some-> (db/entity k) :db/ident) k)
                  :else        k)
                v]))
        hist))

(defn- orient-corpus-stats
  "Returns {:memory-count <int> :type-histogram {<class-ident> <count>}}.
   The histogram keys are resolved to class idents (the raw group-by keys are
   :dt/type ref-eids).  Per the imperative /memory-open Steps 3.1 + 3.2."
  []
  (let [count-result   (aggregate/count-by {:class :mm/Memory})
        group-result   (aggregate/group-by {:class    :mm/Memory
                                            :group-by :dt/type})]
    {:memory-count   (:count count-result)
     :type-histogram (resolve-type-histogram (:groups group-result))}))

(defn- orient-type-lattice
  "Per-branch instance counts for the direct subclasses of :mm/Memory — the
   top-level type-lattice summary (Artifact / Guidance / Meta / Signal / Spec
   / …), EACH enriched with its own direct subclasses + counts (surface B of
   the deep-grounding ceremony — the type-STRUCTURE existence resident at
   orientation, not just top-level totals).  Every count INCLUDES its subtree
   (count-by counts instances-of recursively).  Returns a vec of
   [branch-ident count subclasses] triples (count-desc), where subclasses is a
   vec of [subclass-ident count] pairs (count-desc)."
  []
  (->> (dt/direct-subclasses-of :mm/Memory)
       (map (fn [branch]
              (let [n    (:count (aggregate/count-by {:class branch}))
                    subs (->> (dt/direct-subclasses-of branch)
                              (map (fn [s] [s (:count (aggregate/count-by {:class s}))]))
                              (sort-by second >)
                              vec)]
                [branch n subs])))
       (sort-by second >)
       vec))

(defn- arc-plan-node
  "Lazy-read the forest-relevant slots for a plan eid (cheap attribute access
   on a Datomic entity — no full projection).  Parent resolves to
   :mm.plan/primary-parent first, then the general :mm.memory/parent, and is
   normalized to a numeric eid so build-arc-tree can match parent→child.  A
   ref may surface as a numeric eid, an entity map, OR a :db/ident keyword
   (the shape a ref set via the MCP entity.update boundary reads back as) —
   all three normalize to the eid; a keyword that doesn't resolve degrades to
   nil (node stays a root), never throws."
  [eid]
  (let [e      (db/entity eid)
        parent (or (:mm.plan/primary-parent e) (:mm.memory/parent e))]
    {:eid      eid
     :ident    (:db/ident e)
     :name     (:mm.memory/name e)
     :stage    (:mm.plan/stage e)
     :status   (:mm.plan/status e)
     :rel-path (:mm.memory/rel-path e)
     :parent   (cond (nil? parent)         nil
                     (number? parent)      parent
                     (keyword? parent)     (some-> (db/entity parent) :db/id)
                     (associative? parent) (:db/id parent)
                     :else                 nil)}))

(defn- build-arc-tree
  "Build a round-trip-safe nested parent→child forest from a recency-ordered
   vec of arc nodes.  Roots are arcs whose parent is absent OR not itself an
   active arc; each node carries its children inline under :children (so the
   tree survives JSON round-trip through :phase/imprint without integer-keyed
   maps).  Returns a vec of root nodes (recency order preserved)."
  [nodes]
  (let [active-eids (set (map :eid nodes))
        children-of (group-by (fn [n]
                                (let [p (:parent n)]
                                  (when (contains? active-eids p) p)))
                              nodes)
        attach (fn attach [node]
                 (-> node
                     (dissoc :parent)
                     (assoc :children (mapv attach (get children-of (:eid node) [])))))]
    (mapv attach (get children-of nil []))))

(defn- orient-active-arc-forest
  "All active :mm/Plan arcs as a parent→child forest.  Returns
   {:count <int> :tree [<root-node>...]} where each node carries :name :stage
   :status :rel-path + nested :children.  Roots ordered by recency.  Replaces
   the prior top-5 flat list per Dan-directive (orient on ALL open arcs +
   child-arcs)."
  []
  (let [{:keys [hits]} (aggregate/rank-by {:class         :mm/Plan
                                           :rank-by       :recency
                                           :temporal-slot :mm.memory/last-touched
                                           :limit         0
                                           :projection    :metadata-only})
        nodes  (->> hits
                    (map (comp arc-plan-node :db/id :entity))
                    (filter #(= :active (:status %)))
                    vec)]
    {:count (count nodes)
     :tree  (build-arc-tree nodes)}))

(defn- orient-top-recent
  "Return top-N most-recently-touched entities of `class` by
   :mm.memory/last-touched, enriched with :mm.memory/name + :mm.memory/rel-path
   (lazy-read) so the banner renders human-readable names rather than bare
   idents/eids.  N is small (≈5) so the per-entity enrichment is cheap."
  [class n]
  (let [{:keys [hits]} (aggregate/rank-by {:class         class
                                           :rank-by       :recency
                                           :temporal-slot :mm.memory/last-touched
                                           :limit         n
                                           :projection    :metadata-only})]
    (mapv (fn [{:keys [entity]}]
            (let [e (db/entity (:db/id entity))]
              {:db/id              (:db/id entity)
               :db/ident           (:db/ident entity)
               :mm.memory/name     (:mm.memory/name e)
               :mm.memory/rel-path (:mm.memory/rel-path e)}))
          hits)))

;; Forward-declared so phase-work :phase/orient can compose the banner
;; server-side (the banner helpers + compose-banner are defined below in the
;; :phase/imprint section).  Composing in orient lets the skill display the
;; banner directly from orient's :phase-work-result :banner slot WITHOUT
;; round-tripping the full orient-state through :phase/imprint — the round-trip
;; keywordizes keys + risks the AI trimming the payload (observed 2026-05-28).
(declare ^:private compose-banner)

(defmethod phase-work :phase/orient
  [_args]
  (let [prior-session  (orient-prior-session)
        prior-log      (orient-prior-log prior-session)
        in-flight-plan (orient-in-flight-plan)
        stats          (orient-corpus-stats)
        type-lattice   (orient-type-lattice)
        arc-forest     (orient-active-arc-forest)
        active-plans   (orient-top-recent :mm/Plan 5)
        active-tasks   (orient-top-recent :mm/Task 5)
        active-procs   (wf/list-active-processes)
        state          {:prior-session     prior-session
                        :prior-log         prior-log
                        :in-flight-plan    in-flight-plan
                        :memory-count      (:memory-count stats)
                        :type-histogram    (:type-histogram stats)
                        :type-lattice      type-lattice
                        :active-arc-forest arc-forest
                        :active-plans      active-plans
                        :active-tasks      active-tasks
                        :active-processes  active-procs}]
    (assoc state :banner (compose-banner state))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/imprint — SECOND multimethod method (Increment H)
;;
;; Pure data formatting — composes the orientation banner string from the
;; orient-state collected at :phase/orient (carried via `(:context args)
;; :orient-state`).  Returns the markdown-formatted banner via
;; `:phase-work-result` for the caller (skill body) to display.
;;
;; No DB queries, no transitions — banner composition only.  This is the
;; SECOND step in the multi-increment phase-work multimethod migration arc
;; toward Q.ι.3.12 thin-wrapper realization.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- banner-prior-session-line
  "Compose the 'Last session' banner line.  Returns the string, or nil if
   no prior-session in orient-state."
  [orient-state]
  (when-let [prior (:prior-session orient-state)]
    (let [n        (:mm.memory/name prior)
          ended    (:mm.session/ended-at prior)
          log-ref  (:prior-log orient-state)
          log-name (some-> log-ref :mm.memory/name)]
      (str "- **Last session**: " (or n "<unnamed>")
           (when ended (str " (closed " ended ")"))
           (if log-name
             (str " → handoff log `" log-name "`")
             " — ⚠ no handoff log (prior session closed without /memory-handoff)")))))

(defn- banner-corpus-state-line
  "Compose the 'Corpus state' banner line — total + top-3 CURATED memorial
   types rendered by class name (not raw eid).  The :dt/Event runtime-event
   subtree is excluded from 'top types' (it's operational telemetry, not
   curated content — mirrors sandbar.core/bm25f-warmable-class?); its volume
   is summarized separately so the count isn't silently misleading."
  [orient-state]
  (let [cnt     (:memory-count orient-state)
        hist    (:type-histogram orient-state)
        event?  (fn [k] (and (keyword? k) (dt/type-isa? :dt/Event k)))
        evt-n   (reduce + 0 (map val (filter (comp event? key) hist)))
        curated (remove (comp event? key) hist)
        top-3   (->> curated
                     (sort-by val >)
                     (take 3)
                     (map (fn [[k v]]
                            (str (if (keyword? k) (name k) (str k)) " " v)))
                     (str/join ", "))]
    (str "- **Corpus state**: " (or cnt 0) " :mm/Memory entities"
         (when (seq top-3) (str " (top curated types: " top-3 ")"))
         (when (pos? evt-n) (str " · " evt-n " runtime events")))))

(defn- entity-name
  "Extract a human-readable name from an entity-map.  Falls back to :db/ident
   string form, then :mm.memory/rel-path, then :db/id."
  [entity]
  (or (:mm.memory/name entity)
      (some-> (:db/ident entity) str)
      (:mm.memory/rel-path entity)
      (some-> (:db/id entity) str)
      "<unnamed>"))

(defn- banner-type-lattice-line
  "Compose the 'Type lattice' banner block — the :mm/Memory top-level branches
   with per-subtree counts, EACH expanded to its direct subclasses + counts
   (surface B: the type-structure existence resident at orientation; deepen
   further via `class.subclasses` / `types.subclass-of` / `class.describe`).
   Tolerates both the enriched [branch n subs] triple shape and the legacy
   [branch n] pair shape (a pair simply renders without a subclass tail)."
  [orient-state]
  (when-let [lattice (seq (:type-lattice orient-state))]
    (let [nm (fn [k] (if (keyword? k) (name k) (str k)))]
      (str "- **Type lattice** (`:mm/Memory` branches → subclasses; deepen via `class.subclasses` / `types.subclass-of`):\n"
           (str/join "\n"
                     (map (fn [[branch n subs]]
                            (str "  - " (nm branch) " " n
                                 (when (seq subs)
                                   (str " — "
                                        (str/join " · "
                                                  (map (fn [[s sc]] (str (nm s) " " sc))
                                                       (take 6 subs)))))))
                           lattice))))))

(defn- truncate-stage
  "Trim a stage string to its first line / ~70 chars for compact rendering."
  [stage]
  (when stage
    (let [s (-> (str stage) (str/split #"\n" 2) first str/trim)]
      (if (> (count s) 70) (str (subs s 0 70) "…") s))))

(defn- arc-node-line
  "One forest line for an arc node at `depth` (2 spaces per indent level)."
  [node depth]
  (let [indent (apply str (repeat depth "  "))
        nm     (or (:name node) (some-> (:ident node) str) (:rel-path node)
                   (some-> (:eid node) str) "<unnamed>")
        stage  (truncate-stage (:stage node))]
    (str indent "- " nm
         (when stage (str " — `" stage "`")))))

(defn- arc-tree-lines
  "Recursively render a forest (vec of nested nodes) to indented bullet lines,
   capped at `max-depth`."
  [tree depth max-depth]
  (mapcat (fn [node]
            (cons (arc-node-line node depth)
                  (when (and (< depth max-depth) (seq (:children node)))
                    (arc-tree-lines (:children node) (inc depth) max-depth))))
          tree))

(defn- banner-arc-forest-line
  "Compose the 'Open arcs' forest block — all active arcs as a parent→child
   tree.  Returns nil when the forest slot is absent/empty (banner then falls
   back to the flat top-5 active-plans line)."
  [orient-state]
  (when-let [forest (:active-arc-forest orient-state)]
    (when (pos? (or (:count forest) 0))
      (str "- **Open arcs** (" (:count forest) " active):\n"
           (str/join "\n" (arc-tree-lines (:tree forest) 1 3))))))

(defn- banner-active-plans-line
  [orient-state]
  (when-let [plans (seq (:active-plans orient-state))]
    (str "- **Active arcs** (top " (count plans) "): "
         (str/join "; " (map entity-name plans)))))

(defn- banner-ready-queue-line
  [orient-state]
  (when-let [tasks (seq (:active-tasks orient-state))]
    (str "- **Ready queue** (top " (count tasks) "): "
         (str/join "; " (map entity-name tasks)))))

(defn- banner-active-processes-line
  [orient-state]
  (let [procs (:active-processes orient-state)
        n     (count procs)]
    (when (pos? n)
      (str "- **In-flight workflows**: " n " active process(es)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mid-arc trajectory extractors (per plans/precious-whistling-toast Stage B)
;;
;; The banner needs to surface arc trajectory when a session is mid-flight
;; through an active arc — NOT just the metadata-only top-5 plan names.
;; These extractors read body-raw from the in-flight-plan + prior-log
;; (both pulled at :full projection by phase-work :phase/orient) and
;; surface:
;;
;;   - The .claude implementation-plan file path (resolves to the active
;;     plan-mode artifact for the current sub-stage)
;;   - The 'Next move' narrative from the prior log's §5 (or equivalent
;;     heading) — what the prior session said to pick up here
;;
;; Failure mode that motivated this — `:memory.interaction/orientation_must_
;; surface_arc_trajectory_when_mid_flight_not_canned_top_5_lists_dan_correction_2026_05_27`:
;; the orient phase returns metadata-only top-5 plans (names only); the
;; banner formats names only; mid-arc trajectory invisible.  After this
;; landing the banner surfaces stage + plan-file + next-move when the
;; in-flight plan + prior log carry the corresponding markers.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private claude-plan-file-re
  "Matches `/Users/dan/.claude/plans/<slug>.md` paths referenced in
   body-raw (the canonical convention for plan-mode artifact pointers
   across the corpus)."
  #"/Users/dan/\.claude/plans/[\w-]+\.md")

(def ^:private next-move-section-re
  "Matches a markdown heading like '## §5 The next move' or '## Next
   move' (case-insensitive; optional '§N ' prefix; optional 'The ')
   followed by the section body up to the NEXT heading or end-of-string.
   Group 1 captures the section body."
  #"(?im)^##\s+(?:§\d+\s+)?(?:The\s+)?[Nn]ext\s+[Mm]ove\s*$\s*([\s\S]+?)(?=^##\s|\z)")

(defn- extract-claude-plan-file
  "Search the in-flight-plan body-raw FIRST, then the prior-log body-raw,
   for a `/Users/dan/.claude/plans/<slug>.md` path.  Returns the first
   match (a string) or nil if neither source carries one.

   The in-flight-plan body sometimes carries a 'Harness plan-file synthesis
   pointer' line; the prior-log §5 'next move' often cites the plan-mode
   artifact.  Either source is acceptable."
  [orient-state]
  (let [in-flight-body (some-> orient-state :in-flight-plan :mm.memory/body-raw)
        prior-log-body (some-> orient-state :prior-log :mm.memory/body-raw)]
    (some #(when % (re-find claude-plan-file-re %))
          [in-flight-body prior-log-body])))

(defn- extract-next-move
  "Extract the 'Next move' narrative from the prior log's body-raw.  The
   regex matches §5 'The next move' (the conventional handoff-log shape
   per the /memory-handoff skill's authoring template) OR variants like
   '## Next move' / '## §3 Next Move'.

   Returns the first PARAGRAPH (text up to the first blank line) of the
   matched section, trimmed.  Returns nil when no matching heading is
   present in the prior-log body-raw OR when prior-log is absent."
  [orient-state]
  (when-let [body (some-> orient-state :prior-log :mm.memory/body-raw)]
    (when-let [[_ block] (re-find next-move-section-re body)]
      (let [trimmed (str/trim block)
            first-para (-> trimmed (str/split #"\n\n" 2) first str/trim)]
        (when-not (str/blank? first-para)
          first-para)))))

(defn- banner-in-flight-arc-line
  "Compose the 'In-flight arc' banner block.  Renders a multi-line
   markdown bullet with sub-bullets for stage / implementation plan /
   next move.  Returns nil when the in-flight plan is absent — banner
   composer filters nils.

   Per `:memory.interaction/orientation_must_surface_arc_trajectory_when_
   mid_flight_not_canned_top_5_lists_dan_correction_2026_05_27` — the
   core mid-arc-deepening behavior the banner now carries."
  [orient-state]
  (when-let [plan (:in-flight-plan orient-state)]
    (let [n     (or (:mm.memory/name plan) (some-> (:db/ident plan) str) "<unnamed>")
          stage (:mm.plan/stage plan)
          file  (extract-claude-plan-file orient-state)
          nxt   (extract-next-move orient-state)
          lines (filter some?
                        [(str "- **In-flight arc**: " n)
                         (when stage (str "  - Stage: `" stage "`"))
                         (when file  (str "  - Implementation plan: `" file "`"))
                         (when nxt   (str "  - Next move: " nxt))])]
      (str/join "\n" lines))))

(defn- compose-banner
  "Compose the full orientation banner from orient-state.  Returns a
   newline-joined markdown string.  Each section line is included only
   when its source data is present (graceful degradation).

   Section order:
     1. Last session
     2. Corpus state (total + top-3 types by name)
     3. Type lattice (:mm/Memory top-level branches + subtree counts)
     4. In-flight arc (mid-arc trajectory)
     5. Open arcs (full active-arc parent→child forest; falls back to top-5)
     6. Ready queue (top-5 tasks)
     7. In-flight workflows (count of active workflow.processes)"
  [orient-state]
  (let [lines (filter some?
                      [(banner-prior-session-line orient-state)
                       (banner-corpus-state-line orient-state)
                       (banner-type-lattice-line orient-state)
                       (banner-in-flight-arc-line orient-state)
                       (or (banner-arc-forest-line orient-state)
                           (banner-active-plans-line orient-state))
                       (banner-ready-queue-line orient-state)
                       (banner-active-processes-line orient-state)])]
    (if (seq lines)
      (str/join "\n" lines)
      "(No orientation data available.)")))

(defmethod phase-work :phase/imprint
  [args]
  (let [orient-state (get-in args [:context :orient-state])]
    (if orient-state
      (compose-banner orient-state)
      ;; No orient-state supplied — return a placeholder banner so the
      ;; caller can detect the gap + supply orient-state via :context.
      (str "## Session orientation\n\n"
           "(Banner cannot be composed — :context :orient-state was not "
           "supplied.  Caller should invoke `:phase/orient` first and pass "
           "the result map as `(:context args) :orient-state` to this phase.)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/capture — THIRD multimethod method (Increment I)
;;
;; Handoff-side counterpart to :phase/orient.  Captures the active session's
;; workflow.process history + recent memorial activity for the handoff log's
;; narrative-state summary.
;;
;; Inputs:
;;   :process-id  (required; the active workflow.process)
;;   :context     (optional) carrying :memorial-limit override (default 20)
;;
;; Outputs (via :phase-work-result):
;;   :process-history          vec of readable history entries (from wf/get-readable-history)
;;   :process-current-state    keyword (current :workflow/state-name)
;;   :process-completed?       boolean (true when terminal)
;;   :recent-memorials         vec of top-N :mm/Memory by :last-touched
;;                             (defaults to 20; bounds the payload)
;;
;; This is the THIRD step in the phase-work multimethod migration arc.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private capture-default-memorial-limit
  "Default cap on :recent-memorials count for the capture-state map.
   Bounded so the handoff narrative author has manageable scope.  Per-call
   override via `(:context args) :memorial-limit`."
  20)

(defn- capture-process-state
  "Extract workflow.process state info (history + current state + terminal)
   for the capture-state.  Returns {:process-history :process-current-state
   :process-completed?} or nil if the process doesn't exist."
  [process-id]
  (when-let [process (wf/find-process process-id)]
    {:process-history       (vec (wf/get-readable-history process))
     :process-current-state (some-> process wf/get-current-state :workflow/state-name)
     :process-completed?    (boolean (wf/process-completed? process))}))

(defn- capture-recent-memorials
  "Return top-N most-recently-touched CURATED (:first-class memorial-policy)
   :mm/Memory entities, each carrying a resolvable :mm.memory/name.

   Two corrections over the prior impl (2026-05-29 lifecycle-hardening arc,
   per decisions/filter_curated_memorials_by_lattice_memorial_policy_...):

   1. Lattice-driven curated filter — `:memorial-policy :first-class` on
      rank-by excludes :db-only runtime telemetry (the :event/* subtree +
      :mm/Run) via the substrate primitive `dt/effective-memorial-policy-of`,
      rather than a bespoke :dt/Event check (which would wrongly KEEP :mm/Run).
      The recency axis is otherwise event-dominated (~1300 recent events),
      which is why the prior unfiltered capture surfaced telemetry noise.
   2. Name re-hydration — the metadata-only projection drops
      :mm.memory/name, so re-fetch each entity (mirroring `orient-top-recent`)
      and resolve a human-readable name via `entity-name` (never bare-nil).
      The prior `(mapv :entity hits)` shipped name-less metadata maps, which
      the MCP wire-view then rendered as {:name null} ×N (the reported bug)."
  [limit]
  (let [{:keys [hits]} (aggregate/rank-by {:class           :mm/Memory
                                           :rank-by         :recency
                                           :temporal-slot   :mm.memory/last-touched
                                           :memorial-policy :first-class
                                           :limit           limit
                                           :projection      :metadata-only})]
    (mapv (fn [{:keys [entity]}]
            (let [e (db/entity (:db/id entity))]
              {:db/id          (:db/id entity)
               :db/ident       (:db/ident entity)
               :mm.memory/name (entity-name e)}))
          hits)))

(defmethod phase-work :phase/capture
  [args]
  (let [process-id      (:process-id args)
        context         (:context args)
        memorial-limit  (or (:memorial-limit context) capture-default-memorial-limit)
        process-state   (capture-process-state process-id)
        recent-memos    (capture-recent-memorials memorial-limit)]
    (merge process-state
           {:recent-memorials recent-memos})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/initialize — FOURTH multimethod method (Increment J)
;;
;; Bootstraps a new :mm/Session entity + starts a workflow.process attached
;; to it.  This is the ONLY phase that CREATES the process (rather than
;; advancing an existing one), so it accepts no :process-id (the value is
;; emitted as the result).
;;
;; Inputs (via `(:context args)`):
;;   :rel-path             — :mm.memory/rel-path for the new :mm/Session
;;                           (REQUIRED; e.g., "sessions/<YYYY-MM-DD>T<HHMM>_<slug>.md")
;;   :name                 — :mm.memory/name (REQUIRED)
;;   :description          — :mm.memory/description (REQUIRED)
;;   :focus                — :mm.memory/description shorthand (alternate)
;;   :previous-session     — prior session :db/ident or eid (optional; sets
;;                           :mm.session/previous-session)
;;   :actor-ident          — :memory.actors/* ident (optional; sets
;;                           :mm.memory/created-by [single-element vec])
;;   :workflow             — workflow definition ident (optional; defaults to
;;                           the workflow already in args)
;;
;; Outputs (via :phase-work-result):
;;   :session-eid          — eid of the created :mm/Session
;;   :session-ident        — :db/ident of the created :mm/Session
;;   :process-id           — eid of the started workflow.process
;;
;; The orchestrate fn body surfaces :process-id at the top level of the
;; result map as :created-process-id so subsequent phases can extract it
;; without digging into :phase-work-result.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-session-entity!
  "Create a new :mm/Session entity from context-supplied bootstrap data via the
   unified `sandbar.store/create-memory!` path, so the session gets a stable,
   EDN-safe :db/ident + a minted :mm/id (NOT identless — Bug-3 fix 2026-05-29).
   Returns the entity-map (with :db/id + :db/ident + :mm/id)."
  [{:keys [rel-path name description focus previous-session actor-ident]}]
  (let [now (Date.)]
    (store/create-memory!
      :mm/Session
      (cond-> {:mm.memory/rel-path        rel-path
               :mm.memory/name            name
               :mm.memory/description     (or description focus)
               :mm.memory/memory-type     :session
               :mm.memory/scope           :project
               :mm.session/started-at     now}
        actor-ident      (assoc :mm.memory/created-by [actor-ident])
        previous-session (assoc :mm.session/previous-session previous-session)))))

(defmethod phase-work :phase/initialize
  [args]
  (let [context        (:context args)
        workflow-ident (or (:workflow args) :workflow/session)
        session-entity (create-session-entity! context)
        session-eid    (:db/id session-entity)
        process        (wf/start-process! workflow-ident session-entity)]
    {:session-eid    session-eid
     :session-ident  (:db/ident session-entity)
     :session-entity session-entity
     :process-id     (:db/id process)
     :process-entity process}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/author — FIFTH multimethod method (Increment J)
;;
;; Creates the handoff :mm/Log entity from context-supplied narrative.
;; The narrative itself is composed by the caller (LLM-side) from the
;; capture-state collected at :phase/capture — passed through context.
;;
;; Inputs (via `(:context args)`):
;;   :narrative    — :mm.memory/body-raw content (REQUIRED; the
;;                   markdown-formatted handoff narrative)
;;   :rel-path     — :mm.memory/rel-path (REQUIRED; e.g.,
;;                   "logs/<YYYY-MM-DD>T<HHMM>_<slug>.md")
;;   :name         — :mm.memory/name (REQUIRED)
;;   :description  — :mm.memory/description (REQUIRED)
;;   :cites        — vec of memory-idents this log cites (optional)
;;   :actor-ident  — :memory.actors/* ident (optional)
;;
;; Outputs (via :phase-work-result):
;;   :log-eid    — eid of the created :mm/Log
;;   :log-ident  — :db/ident of the created :mm/Log
;;   :log-entity — the full entity-map
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- create-log-entity!
  "Create a new :mm/Log entity from context-supplied handoff data via the
   unified `sandbar.store/create-memory!` path (stable EDN-safe :db/ident +
   minted :mm/id; Bug-3 fix 2026-05-29).
   Returns the entity-map (with :db/id + :db/ident + :mm/id)."
  [{:keys [narrative rel-path name description cites actor-ident]}]
  (let [now (Date.)]
    (store/create-memory!
      :mm/Log
      (cond-> {:mm.memory/rel-path      rel-path
               :mm.memory/name          name
               :mm.memory/description   description
               :mm.memory/body-raw      narrative
               :mm.memory/memory-type   :log
               :mm.memory/scope         :global
               :mm.memory/last-touched  now}
        actor-ident (assoc :mm.memory/created-by [actor-ident])
        (seq cites) (assoc :mm.memory/cites cites)))))

(defmethod phase-work :phase/author
  [args]
  (let [context    (:context args)
        log-entity (create-log-entity! context)]
    {:log-eid    (:db/id log-entity)
     :log-ident  (:db/ident log-entity)
     :log-entity log-entity}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; phase-work :phase/link — SIXTH multimethod method (Increment J)
;;
;; Updates the session entity to reference the handoff :mm/Log.  Per the Bug-1
;; leak-coupling fix (2026-05-29), :mm.session/ended-at is NO LONGER written
;; here — it is written by the orchestrate fn body ONLY after a terminal
;; :phase/finalize, so ended-at can never be set while the workflow.process is
;; still non-terminal (the leak vector that orphaned ended-but-open sessions).
;;
;; Inputs (via `(:context args)`):
;;   :session-eid       — eid (or :db/ident) of the session to update
;;                        (REQUIRED)
;;   :log-eid           — eid (or :db/ident) of the handoff :mm/Log
;;                        (REQUIRED; comes from :phase/author's
;;                        :phase-work-result)
;;
;; Outputs (via :phase-work-result):
;;   :session-entity    — the refreshed session entity post-update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmethod phase-work :phase/link
  [args]
  (let [context     (:context args)
        session-ref (or (:session-eid context) (:session-ident context))
        log-ref     (or (:log-eid context) (:log-ident context))]
    (when-not session-ref
      (throw (ex-info "phase-work :phase/link missing :session-eid (or :session-ident)"
                      {:reason :missing-required-arg :key :session-eid :context context})))
    (when-not log-ref
      (throw (ex-info "phase-work :phase/link missing :log-eid (or :log-ident)"
                      {:reason :missing-required-arg :key :log-eid :context context})))
    ;; Bug-1 leak-coupling (2026-05-29): :mm.session/ended-at is NO LONGER
    ;; written here.  It is written by the orchestrate fn body ONLY after a
    ;; terminal :phase/finalize, so ended-at can never be set while the
    ;; workflow.process is still non-terminal (the leak vector).  :phase/link
    ;; now records only the handoff :mm/Log ref.
    (let [updated (dt/update-entity! session-ref
                                     {:mm.session/log log-ref})]
      {:session-entity updated})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orchestrator entry point — W4.1 dispatcher loop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private phases-not-requiring-process-id
  "Phases that do NOT require an existing :workflow/Process — pure-read or
   bootstrap phases.  These can be invoked before a process exists OR with
   no process at all:

   - `:phase/orient` (Increment G) — pure-read; queries corpus state for
     the orientation banner; no process needed
   - `:phase/initialize` (Increment J) — bootstrap; CREATES the workflow.process
     itself via phase-work; emits :created-process-id in the orchestrate
     result map for downstream phases to consume

   Other phases (`:activate` / `:imprint` / `:capture` / `:author` / `:link`
   / `:finalize`) require `:process-id` because they reference / mutate the
   active workflow.process."
  #{:phase/orient :phase/initialize})

(defn- validate-args!
  "Validate required orchestrate args.  Throws ex-info on missing keys or
   unknown phase.

   Per Increment G — `:process-id` is OPTIONAL for phases in
   `phases-not-requiring-process-id` (currently just `:phase/orient`).
   This unblocks the eventual thin-wrapper rewrite where the skill can
   call orchestrate `:phase/orient` BEFORE a workflow.process exists."
  [{:keys [workflow process-id phase] :as args}]
  (when-not workflow
    (throw (ex-info "Missing required arg :workflow"
                    {:reason :missing-required-arg :key :workflow :args args})))
  (when-not phase
    (throw (ex-info "Missing required arg :phase"
                    {:reason :missing-required-arg :key :phase :args args})))
  (when-not (ceremony-of phase)
    (throw (ex-info (str "Unknown phase " phase " — not a member of open-phases or handoff-phases")
                    {:reason :unknown-phase
                     :phase  phase
                     :known  {:open-phases    open-phases
                              :handoff-phases handoff-phases}})))
  ;; :process-id required for all phases EXCEPT those in phases-not-requiring-process-id
  (when (and (not process-id)
             (not (contains? phases-not-requiring-process-id phase)))
    (throw (ex-info (str "Missing required arg :process-id (required for phase " phase ")")
                    {:reason          :missing-required-arg
                     :key             :process-id
                     :phase           phase
                     :phases-exempt   phases-not-requiring-process-id
                     :args            args}))))

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
        start-instant (System/currentTimeMillis)
        timeout-ms    (effective-timeout-ms phase timeouts)
        ;; Wrap the phase-work + transition-application in a future + timeout.
        ;; On TimeoutException → emit :mm.event/WorkflowSessionFailed +
        ;; try-transition-to-failed + throw ex-info :reason :phase-timeout
        ;; per κ P8 hard-fail.  Per Q.ι.3.4 + Q.ι.3.9.
        ;;
        ;; Test injection: `(:test/sleep-ms context)` triggers a Thread/sleep
        ;; before phase-work — lets tests deterministically exercise the
        ;; timeout path with a tiny override (`:timeouts {phase 50}`).  This
        ;; hook is test-only; production phase-work does not check it.
        work-future   (future
                        (when-let [sleep-ms (get context :test/sleep-ms)]
                          (Thread/sleep ^long sleep-ms))
                        (let [phase-result (phase-work args)
                              transitions  (get phase-transitions phase [])
                              outcomes     (reduce (fn [acc tname]
                                                     (conj acc
                                                           (safe-transition!
                                                             (:process-id args) tname
                                                             :context context
                                                             :actor   actor
                                                             :reason  reason)))
                                                   []
                                                   transitions)]
                          {:phase-result phase-result :outcomes outcomes}))
        work-result   (try
                        (.get ^java.util.concurrent.Future work-future
                              ^long timeout-ms
                              java.util.concurrent.TimeUnit/MILLISECONDS)
                        (catch java.util.concurrent.TimeoutException _
                          (.cancel ^java.util.concurrent.Future work-future true)
                          ::phase-timeout)
                        (catch java.util.concurrent.ExecutionException e
                          ;; Unwrap to surface the underlying cause to the caller
                          (throw (or (.getCause e) e))))]
    (cond
      ;; ---- Hard-fail: :phase-timeout ----
      (= work-result ::phase-timeout)
      (let [reason-str (str "Phase timeout — " phase " exceeded " timeout-ms "ms")
            emitted    (emit-failed-event!
                         {:process-id (:process-id args)
                          :phase      phase
                          :reason     :phase-timeout})]
        (try-transition-to-failed! (:process-id args) reason-str)
        (log/error :ORCHESTRATE/PHASE-TIMEOUT
                   {:phase       phase
                    :timeout-ms  timeout-ms
                    :process-id  (:process-id args)
                    :event-eid   (:db/id emitted)})
        (throw (ex-info reason-str
                        {:reason        :phase-timeout
                         :phase         phase
                         :timeout-ms    timeout-ms
                         :process-id    (:process-id args)
                         :event-emitted (:db/id emitted)})))

      ;; ---- Normal path: success or κ P18 degraded ----
      :else
      (let [{:keys [phase-result outcomes]} work-result
            applied        (->> outcomes (keep :applied) vec)
            degraded?      (boolean (some :degraded? outcomes))
            ;; Per Increment J — :phase/initialize creates the process via
            ;; phase-work; surface :created-process-id at the top level of
            ;; the result map so downstream phases (skill body iteration)
            ;; can extract it without digging into :phase-work-result.
            created-process-id (when (and (= phase :phase/initialize)
                                          (map? phase-result))
                                 (:process-id phase-result))
            ;; Effective process-id for emission slots: caller-supplied OR
            ;; just-created (for :phase/initialize bootstrap path).
            effective-process-id (or (:process-id args) created-process-id)
            ;; Q.ι.3.9 event emission — one event per phase boundary:
            ;;   degraded? true  → :mm.event/WorkflowSessionDegraded
            ;;   degraded? false → phase-completion event if registered
            emitted-event  (if degraded?
                             (let [degraded-outcome (some #(when (:degraded? %) %) outcomes)]
                               (emit-degraded-event!
                                {:process-id effective-process-id
                                 :phase      phase
                                 :transition (:transition degraded-outcome)
                                 :reason     (:reason degraded-outcome)}))
                             (emit-phase-completion-event!
                              phase
                              {:process-id effective-process-id
                               :transition (last applied)}))
            events-emitted (if emitted-event
                             [(:db/id emitted-event)]
                             [])
            duration-ms    (- (System/currentTimeMillis) start-instant)]
        ;; Bug-1 leak-coupling (2026-05-29 lifecycle-hardening arc): write
        ;; :mm.session/ended-at ONLY when :phase/finalize left the process in a
        ;; TERMINAL state.  ended-at was moved OUT of :phase/link so the invariant
        ;; "ended-at set ⟹ process terminal" always holds — closing both leak
        ;; vectors (interrupted ceremony + silent κ-P18 degrade).  Brick-safe per
        ;; Dan (couple+sweep+log-not-throw): a non-terminal finalize logs WARN +
        ;; emits the degraded event (above) but does NOT write ended-at and does
        ;; NOT throw — the session stays cleanly re-finalizable and the reconcile
        ;; sweep (sandbar.util.workflow/close-leaked-sessions!) mops it up.
        (when (= phase :phase/finalize)
          (let [proc      (wf/find-process (:process-id args))
                terminal? (boolean (and proc (wf/process-in-terminal-state? proc)))]
            (if terminal?
              (when-let [subj-eid (some-> proc wf/get-process-subject :db/id)]
                (dt/update-entity! subj-eid {:mm.session/ended-at (Date.)}))
              (log/warn :ORCHESTRATE/FINALIZE-INCOMPLETE
                        {:process-id (:process-id args)
                         :degraded?  degraded?
                         :note "session NOT marked ended — finalize did not reach a terminal state; re-finalizable; reconcile sweep will close it"}))))
        (log/info :ORCHESTRATE/PHASE-COMPLETE
                  {:phase              phase
                   :ceremony           (ceremony-of phase)
                   :transitions        applied
                   :degraded?          degraded?
                   :events-emitted     events-emitted
                   :duration-ms        duration-ms
                   :process-id         effective-process-id
                   :created-process-id created-process-id
                   :workflow           (:workflow args)})
        (cond-> {:phase-completed    phase
                 :next-phase         (next-phase-of phase)
                 :transition-applied applied
                 :events-emitted     events-emitted
                 :duration-ms        duration-ms
                 :degraded?          degraded?
                 :phase-work-result  phase-result}
          ;; Surface :created-process-id only when :phase/initialize bootstrapped
          ;; a new process — caller extracts it for downstream phase calls
          created-process-id (assoc :created-process-id created-process-id))))))
