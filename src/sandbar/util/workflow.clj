(ns sandbar.util.workflow
  "Workflow state machine utilities.

   Provides functions for defining and executing workflows (state machines).
   A workflow consists of:
   - States: Named positions in the process
   - Transitions: Valid state changes with optional guards
   - Processes: Running instances attached to subject entities

   ## Quick Start

     (require '[sandbar.util.workflow :as wf])

     ;; Define a workflow
     (def order-workflow
       (wf/define-workflow! :workflow/order-fulfillment
         {:states [{:name :order/pending :initial? true}
                   {:name :order/confirmed}
                   {:name :order/shipped}
                   {:name :order/delivered :terminal? true :terminal-kind :success}
                   {:name :order/cancelled :terminal? true :terminal-kind :cancel}]
          :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
                        {:name :ship :from :order/confirmed :to :order/shipped}
                        {:name :deliver :from :order/shipped :to :order/delivered}
                        {:name :cancel :from :order/pending :to :order/cancelled}
                        {:name :cancel :from :order/confirmed :to :order/cancelled}]}))

     ;; Start a process for an order
     (def process (wf/start-process! order-workflow order-entity))

     ;; Transition the process
     (wf/transition! process :confirm {:actor user})
     (wf/transition! process :ship {:actor warehouse-user :reason \"Ready for pickup\"})

     ;; Check available transitions
     (wf/available-transitions process)
     ;; => [{:name :deliver} {:name :cancel}]

   ## Loading Workflows from Resources

     ;; Load a workflow definition from resources/workflows/
     (wf/load-workflow-from-resource! \"workflows/resource-validation.edn\")

   ## Guard Functions

   Transitions can have guard functions that control when they're allowed:

     {:name :ship
      :from :order/confirmed
      :to :order/shipped
      :guard 'myapp.guards/inventory-available?}

   Guard functions receive (process context) and return boolean."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.util.edn :as cfg]
            [sandbar.util.event :as event])
  (:import [java.net JarURLConnection]
           [java.util Date]
           [java.util.jar JarFile]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; State Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def valid-terminal-kinds
  "Terminal outcome classifications: :success for the intended outcome,
   :failure for an outcome-blocking problem, and :cancel for deliberate stop."
  #{:success :failure :cancel})

(defn- validate-terminal-kind!
  "Validate :terminal-kind against the closed set + terminal? consistency.
   Throws ex-info on violation per F-B-002 ADR acceptance criterion A.4."
  [state-name terminal? terminal-kind]
  (cond
    (and terminal? (not terminal-kind))
    (throw (ex-info ":workflow/State terminal? true requires :terminal-kind"
                    {:reason  :terminal-without-kind
                     :state   state-name
                     :allowed valid-terminal-kinds}))

    (and (not terminal?) terminal-kind)
    (throw (ex-info ":workflow/State :terminal-kind only meaningful when terminal? true"
                    {:reason        :non-terminal-with-kind
                     :state         state-name
                     :terminal-kind terminal-kind}))

    (and terminal-kind (not (contains? valid-terminal-kinds terminal-kind)))
    (throw (ex-info (str ":workflow/State :terminal-kind must be one of " valid-terminal-kinds)
                    {:reason        :invalid-terminal-kind
                     :state         state-name
                     :terminal-kind terminal-kind
                     :allowed       valid-terminal-kinds}))))

(defn create-state!
  "Create a named workflow state and return its entity.
   Options: :label, :initial?, :terminal?, :terminal-kind and EDN :metadata.
   A terminal state requires :terminal-kind in #{:success :failure :cancel};
   a nonterminal state may not declare one. Invalid combinations throw
   ExceptionInfo before creating the state."
  [state-name & {:keys [label initial? terminal? terminal-kind metadata]}]
  (validate-terminal-kind! state-name terminal? terminal-kind)
  (dt/make :workflow/State
    (cond-> {:workflow/state-name state-name
             :workflow/state-label (or label (name state-name))}
      initial?      (assoc :workflow/initial? true)
      terminal?     (assoc :workflow/terminal? true)
      terminal-kind (assoc :workflow/terminal-kind terminal-kind)
      metadata      (assoc :workflow/state-metadata (pr-str metadata)))))

(defn find-state
  "Find a state by name. Returns entity map or nil."
  [state-name]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?name
                        :where [?e :workflow/state-name ?name]]
                      (db/db) state-name)]
    (db/entity eid)))

(defn state-terminal?
  "Check if a state is terminal."
  [state]
  (boolean (:workflow/terminal? state)))

(defn state-initial?
  "Check if a state is initial."
  [state]
  (boolean (:workflow/initial? state)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transition Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-transition!
  "Create a workflow transition.

   Arguments:
     transition-name - Action keyword (e.g., :confirm, :ship)
     from-state      - Source state entity or keyword
     to-state        - Target state entity or keyword

   Options:
     :guard           - Symbol of guard function (fn [process context] -> boolean)
     :on-transition   - Symbol of side-effect function (fn [process context] -> nil)
     :requires-reason - Require a reason/comment for this transition

   Returns the created transition entity."
  [transition-name from-state to-state & {:keys [guard on-transition requires-reason?]}]
  (let [from-entity (if (keyword? from-state) (find-state from-state) from-state)
        to-entity (if (keyword? to-state) (find-state to-state) to-state)]
    (when-not from-entity
      (throw (ex-info "From state not found" {:state from-state})))
    (when-not to-entity
      (throw (ex-info "To state not found" {:state to-state})))
    (dt/make :workflow/Transition
      (cond-> {:workflow/transition-name transition-name
               :workflow/from-state (:db/id from-entity)
               :workflow/to-state (:db/id to-entity)}
        guard (assoc :workflow/guard guard)
        on-transition (assoc :workflow/on-transition on-transition)
        requires-reason? (assoc :workflow/requires-reason? true)))))

(defn find-transition
  "Find a transition by name from a specific state.

   Returns the transition entity or nil."
  [transition-name from-state]
  (let [from-id (if (keyword? from-state)
                  (:db/id (find-state from-state))
                  (:db/id from-state))]
    (when-let [eid (d/q '[:find ?e .
                          :in $ ?name ?from
                          :where
                          [?e :workflow/transition-name ?name]
                          [?e :workflow/from-state ?from]]
                        (db/db) transition-name from-id)]
      (db/entity eid))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Definition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn define-workflow!
  "Define a reusable workflow from :states and :transitions vectors, with
   optional :version. States use :name, :label, :initial?, :terminal? and
   :terminal-kind; terminal states require success, failure or cancel.
   Transitions use :name, :from, :to, optional :guard and :requires-reason?.
   Returns the created definition entity. Definition installation does not
   add concurrency guarantees to transition execution."
  [definition-name {:keys [states transitions version] :or {version 1}}]
  ;; Create states first
  (let [state-entities (into {}
                             (map (fn [{:keys [name label initial? terminal? terminal-kind metadata]}]
                                    [name (create-state! name
                                                         :label label
                                                         :initial? initial?
                                                         :terminal? terminal?
                                                         :terminal-kind terminal-kind
                                                         :metadata metadata)])
                                  states))
        ;; Create transitions
        transition-entities (mapv (fn [{:keys [name from to guard on-transition requires-reason?]}]
                                    (create-transition! name
                                                        (get state-entities from)
                                                        (get state-entities to)
                                                        :guard guard
                                                        :on-transition on-transition
                                                        :requires-reason? requires-reason?))
                                  transitions)]
    ;; Create the definition (skip validation since we're passing entity IDs)
    (log/info :WORKFLOW/DEFINE {:name definition-name
                                 :states (count states)
                                 :transitions (count transitions)
                                 :version version})
    (dt/make :mm/Workflow
      {:workflow/definition-name definition-name
       :workflow/states (mapv :db/id (vals state-entities))
       :workflow/transitions (mapv :db/id transition-entities)
       :workflow/version version}
      {:validate? false})))

(defn find-workflow
  "Find a workflow definition by name. Returns entity map or nil."
  [definition-name]
  (when-let [eid (d/q '[:find ?e .
                        :in $ ?name
                        :where [?e :workflow/definition-name ?name]]
                      (db/db) definition-name)]
    (db/entity eid)))

(defn- resolve-ref
  "Resolve an entity reference to a full entity using the current database.
   Handles: numbers, keywords (`:db/ident` refs), {:db/id N} maps, and datomic.Entity refs.
   Always returns a fresh entity from the current database snapshot.

   Per Q.ι.3.11 follow-on fix 2026-05-26 — also handles keyword inputs (the dt/ wrapper
   layer returns ref-slot values as the target's `:db/ident` keyword when available; prior
   to this fix, those keyword values fell through `:else` and returned as-is, causing
   downstream `(:workflow/state-name current-state)` to return nil because keywords don't
   carry slot data. Symptom: workflow.transition reported `current-state: null` despite
   workflow.process-state returning the correct state-ident.)"
  [ref]
  (cond
    (nil? ref) nil
    (number? ref) (d/entity (db/db) ref)
    ;; NEW (Q.ι.3.11 follow-on): keyword refs are :db/ident lookups
    (keyword? ref) (d/entity (db/db) ref)
    ;; Check for :db/id key - works for both maps AND datomic entities
    ;; (datomic entities implement ILookup, so (:db/id entity) works)
    (:db/id ref) (d/entity (db/db) (:db/id ref))
    :else ref))

(defn- get-entity-id
  "Extract entity ID from various reference types.
   Handles: numbers, keywords (`:db/ident` refs), {:db/id N} maps, and datomic.Entity refs.

   Per Q.ι.3.11 follow-on fix 2026-05-26 — also handles keyword inputs (the dt/ wrapper
   layer returns ref-slot values as the target's `:db/ident` keyword when available;
   prior to this fix, keyword inputs fell through `:else` and were returned as-is,
   causing the `get-transitions-from-state` filter to compare a keyword against an eid
   `(= :session.state/opening 17592186093092)` → false → no transitions returned)."
  [ref]
  (cond
    (nil? ref) nil
    (number? ref) ref
    ;; NEW (Q.ι.3.11 follow-on): keyword refs are :db/ident lookups
    (keyword? ref) (:db/id (d/entity (db/db) ref))
    ;; Check for :db/id key (works for both maps and Datomic entities)
    (:db/id ref) (:db/id ref)
    :else ref))

(defn get-workflow-states
  "Get all states for a workflow definition."
  [workflow]
  (mapv resolve-ref (:workflow/states workflow)))

(defn get-workflow-transitions
  "Get all transitions for a workflow definition."
  [workflow]
  (mapv resolve-ref (:workflow/transitions workflow)))

(defn get-initial-state
  "Get the initial state for a workflow."
  [workflow]
  (first (filter state-initial? (get-workflow-states workflow))))

(defn get-terminal-states
  "Get all terminal states for a workflow."
  [workflow]
  (filter state-terminal? (get-workflow-states workflow)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Process Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-process!
  "Start a new workflow process.

   Arguments:
     workflow - Workflow definition entity or keyword
     subject  - Subject entity that this process is attached to (e.g., an order)

   Options:
     :data - Process-specific data (any EDN-serializable value)

   Returns the created process entity."
  [workflow subject & {:keys [data]}]
  (let [workflow-entity (if (keyword? workflow) (find-workflow workflow) workflow)
        initial-state (get-initial-state workflow-entity)
        now (Date.)]
    (when-not workflow-entity
      (throw (ex-info "Workflow not found" {:workflow workflow})))
    (when-not initial-state
      (throw (ex-info "No initial state defined" {:workflow (:workflow/definition-name workflow-entity)})))
    (let [process (dt/make :workflow/Process
                    (cond-> {:workflow/definition (:db/id workflow-entity)
                             :workflow/current-state (:db/id initial-state)
                             :workflow/subject (:db/id subject)
                             :workflow/started-at now}
                      data (assoc :workflow/process-data (pr-str data)))
                    {:validate? false})]
      (event/log! :info "Workflow process started"
                  {:event/kind :workflow/process-started
                   :event/target (:db/id process)
                   :event/tags #{:workflow}})
      process)))

(defn find-process
  "Find a process by entity ID. Returns entity map or nil."
  [process-id]
  (when process-id
    (let [entity (db/entity process-id)]
      ;; db/entity returns {:db/id N} even for non-existent entities
      ;; Check for an actual workflow attribute to confirm existence
      (when (:workflow/started-at entity)
        entity))))

(defn update-process-data!
  "Replace :workflow/process-data with the pr-str encoding of the value.
   Encapsulate the transaction behind the workflow API and return the
   refreshed process entity after completion. This EDN payload is distinct
   from typed process slots and must follow the application's trust policy."
  [process data]
  (let [process-id (or (:db/id process) process)]
    (when-not (number? process-id)
      (throw (ex-info "update-process-data! requires a process entity or :db/id"
                      {:process process})))
    @(d/transact (db/conn)
                 [[:db/add process-id :workflow/process-data (pr-str data)]])
    (db/entity process-id)))

(defn find-process-by-subject
  "Find all processes for a subject entity."
  [subject]
  (let [subject-id (get-entity-id subject)
        eids (d/q '[:find [?e ...]
                    :in $ ?subject
                    :where [?e :workflow/subject ?subject]]
                  (db/db) subject-id)]
    (map db/entity eids)))

(declare get-current-state)

(defn list-processes
  "Enumerate all workflow processes in the system.

   Returns: vector of process entity maps, sorted by `:workflow/started-at`
   descending (most-recently-started first).

   Per Dan-directive 2026-05-14 PM endorsing process-enumeration as a
   valuable feature — the substrate had no public primitive for listing
   workflow processes prior to this addition.

   Consumers: `sandbar.mcp.tasks/handle-list` (MCP `tasks/list` verb);
   future REST listing endpoint + admin tooling."
  []
  (let [eids (d/q '[:find [?p ...]
                    :where [?p :workflow/started-at _]]
                  (db/db))]
    (->> eids
         (map db/entity)
         (sort-by :workflow/started-at)
         reverse
         vec)))

(defn list-active-processes
  "Enumerate workflow processes whose current state is NOT terminal —
   i.e., processes still in-flight.

   Returns: vector of process entity maps, sorted by `:workflow/started-at`
   descending.  A non-active process is one whose current state has
   `:workflow/terminal? true` (per the workflow-state-machine
   convention).

   Companion to `list-processes` (which returns all processes
   regardless of terminal state).  Per Dan-directive 2026-05-14 PM."
  []
  (->> (list-processes)
       (remove #(boolean (:workflow/terminal? (get-current-state %))))
       vec))

(defn get-current-state
  "Get the current state of a process."
  [process]
  (resolve-ref (:workflow/current-state process)))

(defn get-process-workflow
  "Get the workflow definition for a process."
  [process]
  (resolve-ref (:workflow/definition process)))

(defn get-process-subject
  "Get the subject entity for a process."
  [process]
  (resolve-ref (:workflow/subject process)))

(defn get-process-data
  "Get the deserialized process data."
  [process]
  (when-let [data-str (:workflow/process-data process)]
    (read-string data-str)))

(defn process-completed?
  "Check if a process has reached a terminal state."
  [process]
  (some? (:workflow/completed-at process)))

(defn process-in-terminal-state?
  "Check if a process is in a terminal state."
  [process]
  (state-terminal? (get-current-state process)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transition Execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-transitions-from-state
  "Get all transitions that originate from a given state within a workflow."
  [workflow state]
  (let [state-id (:db/id state)
        all-transitions (get-workflow-transitions workflow)]
    (filter (fn [t]
              (let [from-id (get-entity-id (:workflow/from-state t))]
                (= from-id state-id)))
            all-transitions)))

(defn- check-guard
  "Check if a transition's guard allows the transition."
  [transition process context]
  (if-let [guard-sym (:workflow/guard transition)]
    (try
      (let [guard-fn (requiring-resolve guard-sym)]
        (if guard-fn
          (guard-fn process context)
          (do
            (log/warn "Guard function not found:" guard-sym)
            false)))
      (catch Exception e
        (log/error e "Guard function failed:" guard-sym)
        false))
    ;; No guard = always allowed
    true))

(defn- run-on-transition
  "Run the on-transition side effect if defined."
  [transition process context]
  (when-let [fn-sym (:workflow/on-transition transition)]
    (try
      (when-let [on-fn (requiring-resolve fn-sym)]
        (on-fn process context))
      (catch Exception e
        (log/error e "On-transition function failed:" fn-sym)))))

(defn available-transitions
  "Get all available transitions from the current state of a process.

   Only returns transitions whose guards pass (if any).

   Options:
     :context - Context map passed to guard functions

   Returns a sequence of transition entities."
  [process & {:keys [context]}]
  (let [workflow (get-process-workflow process)
        current-state (get-current-state process)
        all-transitions (get-transitions-from-state workflow current-state)]
    (filter #(check-guard % process (or context {})) all-transitions)))

(defn can-transition?
  "Check if a specific transition is available from the current state.

   Arguments:
     process         - The process entity
     transition-name - The action keyword

   Options:
     :context - Context map passed to guard function"
  [process transition-name & {:keys [context]}]
  (let [available (available-transitions process :context context)]
    (some #(= transition-name (:workflow/transition-name %)) available)))

(defn create-history-entry!
  "Create a history entry for a transition."
  [from-state to-state action & {:keys [actor reason]}]
  (dt/make :workflow/History
    (cond-> {:workflow/history-from (:db/id from-state)
             :workflow/history-to (:db/id to-state)
             :workflow/history-action action
             :workflow/history-timestamp (Date.)}
      actor (assoc :workflow/history-actor (:db/id actor))
      reason (assoc :workflow/history-reason reason))
    {:validate? false}))

(defn transition!
  "Execute a transition on a process.

   Arguments:
     process         - The process entity
     transition-name - The action keyword (e.g., :confirm, :ship)

   Options:
     :context - Context map passed to guard/on-transition functions
     :actor   - User/principal performing the transition
     :reason  - Reason/comment for the transition

   Returns the updated process entity.
   Throws if transition is not available or reason is required but not provided.
   History is created in a separate transaction. The state update has no
   compare-and-set guard, and hook exceptions are logged without necessarily
   preventing that update. Use serialized callers and re-fetch between steps;
   this function does not provide exactly-once effects."
  [process transition-name & {:keys [context actor reason]}]
  (let [current-state (get-current-state process)
        workflow (get-process-workflow process)
        transitions (get-transitions-from-state workflow current-state)
        ;; Q.ι.3.11 ratified 2026-05-26 — match transition-name against BOTH
        ;; :workflow/transition-name (legacy string form) AND :db/ident (post-κ-additions
        ;; keyword form).  MCP boundary normalizes input to keyword via tools.clj/->ident,
        ;; but stored :workflow/transition-name is a string — the type mismatch caused
        ;; the 4-phase substrate-gap reproduction documented in
        ;; observations/workflow_transition_verb_identless_unreachable_2026_05_26.md.
        ;; Fallback chain: try :db/ident (canonical post-κ-additions) → :workflow/transition-name
        ;; (legacy string) with proper string-coercion of the input keyword.
        tname-str  (cond
                     (string? transition-name) transition-name
                     (keyword? transition-name) (if (namespace transition-name)
                                                  (str (namespace transition-name) "/" (name transition-name))
                                                  (name transition-name))
                     :else (str transition-name))
        tname-kw   (cond
                     (keyword? transition-name) transition-name
                     (string? transition-name) (if (str/starts-with? transition-name ":")
                                                 (keyword (subs transition-name 1))
                                                 (keyword transition-name))
                     :else nil)
        transition (first (filter #(or (= transition-name (:workflow/transition-name %))
                                       (and tname-kw (= tname-kw (:db/ident %)))
                                       (and tname-kw (= tname-kw (:workflow/transition-name %)))
                                       (= tname-str (:workflow/transition-name %)))
                                  transitions))]
    (cond
      (nil? transition)
      (do
        (log/warn :WORKFLOW/TRANSITION-NOT-FOUND {:transition transition-name
                                                    :current-state (:workflow/state-name current-state)
                                                    :process-id (:db/id process)})
        ;; :reason :transition-not-found added 2026-05-26 for ι.3 orchestrator
        ;; κ P18 bootstrap-robustness fallback — orchestrator distinguishes this
        ;; (recoverable; compiled-cache-stale shape) from non-recoverable failures
        ;; (:guard-not-met, :requires-reason).  Per the ι.3 design ratification
        ;; ADR + the empirical reproduction at
        ;; observations/workflow_transition_verb_identless_unreachable_2026_05_26.md.
        (throw (ex-info "Transition not found from current state"
                        {:reason        :transition-not-found
                         :transition    transition-name
                         :current-state (:workflow/state-name current-state)})))

      (and (:workflow/requires-reason? transition) (not reason))
      (throw (ex-info "Transition requires a reason"
                      {:reason     :requires-reason
                       :transition transition-name}))

      (not (check-guard transition process (or context {})))
      (throw (ex-info "Guard condition not met"
                      {:reason     :guard-not-met
                       :transition transition-name}))

      :else
      (let [to-state (resolve-ref (:workflow/to-state transition))
            history (create-history-entry! current-state to-state transition-name
                                           :actor actor :reason reason)
            now (Date.)
            is-terminal? (state-terminal? to-state)
            tx-data (cond-> [[:db/add (:db/id process) :workflow/current-state (:db/id to-state)]
                             [:db/add (:db/id process) :workflow/history (:db/id history)]]
                      is-terminal?
                      (conj [:db/add (:db/id process) :workflow/completed-at now]))
            ;; Include a nonempty sequential collection of tx-forms returned
            ;; by the hook in the state-update transaction. This is a plain
            ;; assertion, not a state CAS. The history entity above has already
            ;; been committed separately. Non-tx returns are ignored; the hook
            ;; wrapper logs exceptions and returns nil on failure.
            effect-tx (run-on-transition transition process
                                         (merge context {:actor actor :reason reason}))
            all-tx    (cond-> tx-data
                        (and (sequential? effect-tx)
                             (seq effect-tx)
                             (every? sequential? effect-tx))
                        (into effect-tx))]
        ;; Apply state change + on-transition effect tx-data atomically
        @(d/transact (db/conn) all-tx)

        (log/info :WORKFLOW/TRANSITION {:process-id (:db/id process)
                                         :action transition-name
                                         :from (:workflow/state-name current-state)
                                         :to (:workflow/state-name to-state)
                                         :terminal? is-terminal?})
        (event/log! :info "Workflow transition"
                    {:event/kind :workflow/transition
                     :event/target (:db/id process)
                     :event/tags #{:workflow}
                     :event/description (str (name transition-name) ": "
                                             (:workflow/state-name current-state) " -> "
                                             (:workflow/state-name to-state))})
        (db/entity (:db/id process))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Leaked-session reconciliation (Bug-1 defense-in-depth + crashed-session
;; staleness rule — reliability sprint 2026-09-18 item 3.4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private leaked-close-path
  "Transition path that drives a non-terminal :workflow/session process to the
   :session/closed terminal, keyed by current state-name.  Used by
   `close-leaked-sessions!` for the ENDED-but-open leak kind (the subject
   already carries :mm.session/ended-at, so the session's own handoff asked
   for a close — finishing that close is the truthful terminal)."
  {:session/opening [:session/start :session/close :session/finalize]
   :session/active  [:session/close :session/finalize]
   :session/closing [:session/finalize]
   :session/paused  [:session/resume-maintenance :session/close :session/finalize]})

(def default-stale-session-window-ms
  "Default inactivity window for the crashed-session staleness rule
   (reliability sprint 2026-09-18, item 3.4): twelve hours, in milliseconds.
   Override per call via `:stale-after-ms`, or per deployment via the config
   key `[:workflow :stale-session-window-ms]`."
  (* 12 60 60 1000))

(def ^:private stale-close-path
  "Transition path that drives a STALE (crashed) :workflow/session process to a
   terminal state, keyed by current state-name.

   A stale session never handed off, so from :session/opening and
   :session/active it is driven to the :session/failed terminal through the
   κ-P8 fail-shaped transitions — the same ones the orchestrator uses for its
   own hard-fail conditions (`try-transition-to-failed!`) — whose `on-fail`
   effect records the reason in :mm.session-process/failure-reason (+
   failure-instant).  From :session/closing the session itself already asked
   to close (its handoff crashed between :session/close and
   :session/finalize), so it is finalized to :session/closed exactly like the
   ended-but-open kind.

   :session/paused is DELIBERATELY absent: maintenance-mode pause is an
   explicit operator hold (κ P6), never auto-failed on inactivity.  A stale
   paused process is reported under :held and left alone."
  {:session/opening [:session/fail-from-opening]
   :session/active  [:session/fail]
   :session/closing [:session/finalize]})

(defn- instant-ms
  [^Date d]
  (when d (.getTime d)))

(declare get-process-history)

(defn session-process-last-activity
  "Latest activity instant the substrate records for a workflow process, or
   nil when it records none.

   ACTIVITY DEFINITION (crashed-session staleness rule, sprint 3.4) — the max
   over these explicit slot values:
     - the process's :workflow/started-at;
     - every :workflow/history-timestamp on the process's history (one per
       transition a ceremony applied — open, pause/resume, close, finalize);
     - on the subject :mm/Session: :mm.session/started-at, :mm.memory/created
       and :mm.memory/last-touched (the last is written by any explicit touch
       of the session memorial, e.g. the handoff's :phase/link update).

   Why slots and not Datomic transaction time: slot values are deterministic,
   backdatable in tests, and survive a DB restore.  Why not the events linked
   to the process (:event/target): the orchestrator emits them at the same
   transitions the history already records, and they are retention-bound
   (Phase 4.2 rollup-and-prune) — a verdict that flips when telemetry is
   pruned is not a rule.

   Trade-off, stated plainly: a live session that applies no transition and
   touches nothing for longer than the window is indistinguishable from a
   crashed one by this evidence.  The window (default 12h) is where that line
   is drawn; a live session that long is itself a hand-off that never
   happened."
  [process]
  (let [subject  (get-process-subject process)
        history  (get-process-history process)
        instants (concat [(:workflow/started-at process)]
                         (map :workflow/history-timestamp history)
                         (when subject
                           [(:mm.session/started-at subject)
                            (:mm.memory/created subject)
                            (:mm.memory/last-touched subject)]))]
    (when-let [ds (seq (remove nil? instants))]
      (apply max-key instant-ms ds))))

(defn- session-process?
  "True when `process` runs the :workflow/session definition.  The staleness
   rule applies ONLY to session processes — a long-running process of any
   other workflow is not a crashed session."
  [process]
  (= :workflow/session
     (:workflow/definition-name (get-process-workflow process))))

(defn- humanize-ms
  [ms]
  (let [mins (quot (long ms) 60000)
        h    (quot mins 60)
        m    (rem mins 60)]
    (if (pos? h) (format "%dh%02dm" h m) (format "%dm" m))))

(def ^:private ended-reason
  "reconcile: close leaked ended-but-open session (Bug-1)")

(defn- stale-reason
  [^Date last-activity idle-ms window-ms]
  (str "reconcile: closed as stale by the leaked-session sweep — no activity since "
       (.toInstant last-activity)
       " (idle " (humanize-ms idle-ms) " > window " (humanize-ms window-ms)
       "); the session never wrote :mm.session/ended-at"
       " (crashed-session staleness rule, sprint 3.4)"))

(defn- resolve-stale-window-ms
  "Explicit arg → config [:workflow :stale-session-window-ms] → default."
  [stale-after-ms]
  (or stale-after-ms
      (try (cfg/config-value :workflow :stale-session-window-ms)
           (catch Exception _ nil))
      default-stale-session-window-ms))

(defn- classify-process
  "Classify one active (non-terminal) process for the sweep.  Returns a
   candidate map with :kind :ended or :kind :stale, or nil when the process is
   left alone.  A stale process whose state has no registered close-path
   (:session/paused) comes back with :held? true."
  [process ^Date now window-ms]
  (let [pid      (:db/id process)
        subject  (get-process-subject process)
        state-kw (:workflow/state-name (get-current-state process))
        base     {:process-id    pid
                  :state         state-kw
                  :subject-id    (:db/id subject)
                  :subject-ident (:db/ident subject)}
        ended-at (:mm.session/ended-at subject)]
    (cond
      (some? ended-at)
      (assoc base
             :kind     :ended
             :ended-at ended-at
             :path     (get leaked-close-path state-kw)
             :reason   ended-reason)

      (not (session-process? process))
      nil

      :else
      (let [last-activity (session-process-last-activity process)
            idle-ms       (when last-activity
                            (- (instant-ms now) (instant-ms last-activity)))]
        (when (and idle-ms (> idle-ms window-ms))
          (let [path (get stale-close-path state-kw)]
            (cond-> (assoc base
                           :kind          :stale
                           :last-activity last-activity
                           :idle-ms       idle-ms
                           :reason        (stale-reason last-activity idle-ms window-ms))
              path       (assoc :path path)
              (not path) (assoc :held? true
                                :note (if (= state-kw :session/paused)
                                        "maintenance-mode hold (κ P6): not auto-closed on inactivity"
                                        "no stale close-path registered for this state")))))))))

(defn leaked-session-plan
  "READ-ONLY classification of every active (non-terminal) workflow process
   for `close-leaked-sessions!` — the sweep's dry-run report.  Transacts
   nothing.

   Two leak kinds:
     :ended — the subject :mm/Session carries :mm.session/ended-at (a Bug-1
              orphan: the handoff marked the session ended but the process
              was never finalized).  Closed via `leaked-close-path`.
     :stale — a :workflow/session process whose subject has NO ended-at and
              no recorded activity (`session-process-last-activity`) for
              longer than the window: a crashed session, which can never
              write ended-at itself (sprint 3.4).  Closed via
              `stale-close-path`; a stale :session/paused process is HELD
              (reported, never closed).

   Processes of other workflows are never :stale candidates.  Live sessions
   (activity inside the window, no ended-at) are left alone.

   Options:
     :now            — java.util.Date; default server time.
     :stale-after-ms — inactivity window; default
                       `default-stale-session-window-ms` (12h), or the config
                       key [:workflow :stale-session-window-ms].

   Returns {:now :window-ms :scanned :leaks :stale :held
            :candidates [{:process-id :state :kind :path :reason …} …]
            :would-close [pid …]}."
  [& {:keys [now stale-after-ms]}]
  (let [now        (or now (Date.))
        window-ms  (resolve-stale-window-ms stale-after-ms)
        actives    (list-active-processes)
        candidates (into [] (keep #(classify-process % now window-ms)) actives)
        closable   (remove :held? candidates)]
    {:now         now
     :window-ms   window-ms
     :scanned     (count actives)
     :leaks       (count (filter #(= :ended (:kind %)) candidates))
     :stale       (count (filter #(and (= :stale (:kind %)) (not (:held? %))) candidates))
     :held        (count (filter :held? candidates))
     :candidates  candidates
     :would-close (mapv :process-id closable)}))

(defn- apply-close-path!
  "Drive process `pid` along `path` (transition names), re-fetching the
   process before each step because its state advances between transitions.
   Throws on the first transition that fails."
  [pid path reason]
  (doseq [t path]
    (transition! (find-process pid) t :reason reason)))

(defn- stamp-session-ended-at!
  "Once a stale process has reached a terminal state, stamp the subject's
   :mm.session/ended-at with `now` (server time).  Runs strictly AFTER the
   terminal transition so the Bug-1 invariant 'ended-at set ⟹ process
   terminal' holds at every instant.  Returns nil, or the error message when
   the stamp failed (the process is terminal regardless)."
  [subject-id ^Date now]
  (when subject-id
    (try
      (dt/update-entity! subject-id {:mm.session/ended-at now})
      nil
      (catch Exception e
        (log/warn :WORKFLOW/STALE-SESSION-ENDED-AT-STAMP-FAILED
                  {:subject-id subject-id :error (.getMessage e)})
        (.getMessage e)))))

(defn close-leaked-sessions!
  "Reconcile LEAKED session-processes.  Two leak kinds (see
   `leaked-session-plan`):

     :ended — any active (non-terminal) process whose subject :mm/Session has
              :mm.session/ended-at set — a Bug-1 orphan (the handoff marked
              the session ended but the process was never finalized).  Driven
              to :session/closed via the reachable path for its current state
              (`leaked-close-path`).  Behavior unchanged since the 2026-05-29
              session-lifecycle-hardening arc.
     :stale — a :workflow/session process whose subject has NO ended-at and
              no recorded activity for longer than the window (default 12h).
              A crashed session never writes ended-at, so before sprint 3.4
              the sweep could not reach it and the process lived forever.
              Driven to its terminal via `stale-close-path` (:session/failed
              from opening/active — the reason lands in
              :mm.session-process/failure-reason through the on-fail effect;
              :session/closed from closing), every transition's history entry
              carrying the stale reason; then, only once the process is
              terminal, the subject's :mm.session/ended-at is stamped with
              server time.  A stale :session/paused process is held, not
              closed.

   A failing transition leaves that process and its session untouched
   (recorded under :skipped), so the sweep can never manufacture a new
   ended-but-open leak.  Idempotent — a second run after all leaks are closed
   changes nothing.  Live sessions are SKIPPED: the current session is never
   closed out from under itself unless it has been silent past the window.

   The orchestrator's Bug-1 fix couples ended-at to a terminal finalize, which
   PREVENTS new :ended leaks via the ceremony; this sweep cleans up
   pre-existing orphans, guards any non-orchestrator close path, and (3.4)
   retires crashed sessions.

   ACTIVITY BETWEEN PLANNING AND CLOSURE (D3, 2026-09-19): the sweep
   classifies every active process first and closes afterwards.  Immediately
   before closing a candidate it re-fetches the process and classifies it
   AGAIN against the same window; a candidate that is no longer a candidate
   — fresh activity on its session, a transition that moved it, a hold —
   is WITHDRAWN, not closed, and reported under :withdrawn.  A live session
   that shows activity in that gap is therefore never failed on a stale
   plan.  A transition that still fails after the re-check is recorded
   under :skipped as before.

   Options (all keyword args; the zero-arg call applies with defaults):
     :dry-run?       — true ⇒ classify and report WITHOUT transacting
                       (:mode :dry-run, :would-close listed).  Default false:
                       the sweep has always applied by default.
     :now            — server time (java.util.Date); default (Date.).
     :stale-after-ms — inactivity window; default
                       `default-stale-session-window-ms` (12h) or the config
                       key [:workflow :stale-session-window-ms].
     :before-apply   — TEST SEAM: a fn of the candidate map, called after
                       planning and before the apply-time re-check, so a
                       test can inject activity into exactly that gap.
                       nil (the default) does nothing.

   Returns the plan map (:now :window-ms :scanned :leaks :stale :held
   :candidates :would-close) plus
     :mode      :dry-run | :apply
     :closed    [pid …]  — processes driven to a terminal state
     :withdrawn [{:process-id :state :kind :note} …] — candidates that were
                no longer candidates at apply time (left untouched)
     :skipped   [{:process-id :state :kind :error} …]
     :receipts  [{:process-id :kind :state :terminal-state :ended-at
                  (:ended-at-error)} …] — one per closed process."
  [& {:keys [dry-run? now stale-after-ms before-apply]}]
  (let [plan      (leaked-session-plan :now now :stale-after-ms stale-after-ms)
        now       ^Date (:now plan)
        window-ms (:window-ms plan)]
    (if dry-run?
      (do
        (log/info :WORKFLOW/LEAKED-SESSION-SWEEP-DRY-RUN (dissoc plan :candidates))
        (assoc plan :mode :dry-run :closed [] :withdrawn [] :skipped [] :receipts []))
      (reduce
        (fn [acc {:keys [process-id state kind path reason subject-id held?] :as c}]
          (if held?
            acc
            (try
              (when before-apply (before-apply c))
              ;; Apply-time re-check against the live process: the plan was
              ;; computed a moment ago and activity may have arrived since.
              (let [fresh (some-> (find-process process-id)
                                  (classify-process now window-ms))
                    withdrawn-note
                    (cond
                      (nil? fresh)              "no longer a candidate at apply time (activity arrived after planning)"
                      (:held? fresh)            "held at apply time (the process moved to a held state after planning)"
                      (not= (:kind fresh) kind) "candidate kind changed after planning"
                      (not= (:state fresh) state) "the process changed state after planning")]
                (if withdrawn-note
                  (do
                    (log/info :WORKFLOW/LEAKED-SESSION-CANDIDATE-WITHDRAWN
                              {:process-id process-id :kind kind :state state
                               :note withdrawn-note})
                    (update acc :withdrawn conj {:process-id process-id
                                                 :state      state
                                                 :kind       kind
                                                 :note       withdrawn-note}))
                  (do
                    (when-not (seq path)
                      (throw (ex-info "No close-path registered for current state"
                                      {:state state :process-id process-id :kind kind})))
                    (apply-close-path! process-id path reason)
                    (let [terminal  (:workflow/state-name
                                     (get-current-state (find-process process-id)))
                          ended-err (when (= kind :stale)
                                      (stamp-session-ended-at! subject-id now))]
                      (log/info :WORKFLOW/LEAKED-SESSION-CLOSED
                                {:process-id    process-id
                                 :kind          kind
                                 :from          state
                                 :to            terminal
                                 :last-activity (:last-activity c)
                                 :idle-ms       (:idle-ms c)})
                      (-> acc
                          (update :closed conj process-id)
                          (update :receipts conj
                                  (cond-> {:process-id     process-id
                                           :kind           kind
                                           :state          state
                                           :terminal-state terminal
                                           :ended-at       (if (= kind :stale) now (:ended-at c))}
                                    ended-err (assoc :ended-at-error ended-err))))))))
              (catch Exception e
                (log/warn :WORKFLOW/LEAKED-SESSION-CLOSE-FAILED
                          {:process-id process-id :kind kind :state state
                           :error (.getMessage e)})
                (update acc :skipped conj {:process-id process-id
                                           :state      state
                                           :kind       kind
                                           :error      (.getMessage e)})))))
        (assoc plan :mode :apply :closed [] :withdrawn [] :skipped [] :receipts [])
        (:candidates plan)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; History
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-process-history
  "Get the transition history for a process.

   Returns a sequence of history entries, sorted by timestamp."
  [process]
  (let [history-refs (or (:workflow/history process) [])]
    (->> history-refs
         (map (fn [ref] (if (map? ref) ref (db/entity ref))))
         (sort-by :workflow/history-timestamp))))

(defn history-entry->map
  "Convert a history entry to a readable map."
  [history]
  (let [from-state (let [ref (:workflow/history-from history)]
                     (if (map? ref) ref (db/entity ref)))
        to-state (let [ref (:workflow/history-to history)]
                   (if (map? ref) ref (db/entity ref)))
        actor (when-let [ref (:workflow/history-actor history)]
                (if (map? ref) ref (db/entity ref)))]
    {:action (:workflow/history-action history)
     :from (:workflow/state-name from-state)
     :to (:workflow/state-name to-state)
     :timestamp (:workflow/history-timestamp history)
     :actor (when actor (:db/id actor))
     :reason (:workflow/history-reason history)}))

(defn get-readable-history
  "Get the transition history as a sequence of readable maps."
  [process]
  (map history-entry->map (get-process-history process)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn processes-in-state
  "Find all processes currently in a specific state.

   Arguments:
     state - State entity or state name keyword

   Options:
     :workflow - Filter by workflow definition"
  [state & {:keys [workflow]}]
  (let [state-id (if (keyword? state)
                   (:db/id (find-state state))
                   (:db/id state))
        base-query '[:find [?e ...]
                     :in $ ?state
                     :where [?e :workflow/current-state ?state]]
        results (if workflow
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?state ?wf
                           :where
                           [?e :workflow/current-state ?state]
                           [?e :workflow/definition ?wf]]
                         (db/db) state-id wf-id))
                  (d/q base-query (db/db) state-id))]
    (map db/entity results)))

(defn active-processes
  "Find all active (non-completed) processes.

   Options:
     :workflow - Filter by workflow definition"
  [& {:keys [workflow]}]
  (let [results (if workflow
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?wf
                           :where
                           [?e :workflow/definition ?wf]
                           (not [?e :workflow/completed-at])]
                         (db/db) wf-id))
                  (d/q '[:find [?e ...]
                         :where
                         [?e :workflow/started-at _]
                         (not [?e :workflow/completed-at])]
                       (db/db)))]
    (map db/entity results)))

(defn completed-processes
  "Find all completed processes.

   Options:
     :workflow - Filter by workflow definition
     :since    - Only include processes completed after this Date"
  [& {:keys [workflow since]}]
  (let [results (cond
                  (and workflow since)
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?wf ?since
                           :where
                           [?e :workflow/definition ?wf]
                           [?e :workflow/completed-at ?t]
                           [(>= ?t ?since)]]
                         (db/db) wf-id since))

                  workflow
                  (let [wf-id (if (keyword? workflow)
                                (:db/id (find-workflow workflow))
                                (:db/id workflow))]
                    (d/q '[:find [?e ...]
                           :in $ ?wf
                           :where
                           [?e :workflow/definition ?wf]
                           [?e :workflow/completed-at _]]
                         (db/db) wf-id))

                  since
                  (d/q '[:find [?e ...]
                         :in $ ?since
                         :where
                         [?e :workflow/completed-at ?t]
                         [(>= ?t ?since)]]
                       (db/db) since)

                  :else
                  (d/q '[:find [?e ...]
                         :where [?e :workflow/completed-at _]]
                       (db/db)))]
    (map db/entity results)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Statistics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn workflow-stats
  "Get statistics for a workflow.

   Returns counts by state and completion status."
  [workflow]
  (let [wf-id (if (keyword? workflow)
                (:db/id (find-workflow workflow))
                (:db/id workflow))
        state-counts (d/q '[:find ?state-name (count ?p)
                            :in $ ?wf
                            :where
                            [?p :workflow/definition ?wf]
                            [?p :workflow/current-state ?s]
                            [?s :workflow/state-name ?state-name]]
                          (db/db) wf-id)
        completed (count (d/q '[:find ?e
                                :in $ ?wf
                                :where
                                [?e :workflow/definition ?wf]
                                [?e :workflow/completed-at _]]
                              (db/db) wf-id))
        active (count (d/q '[:find ?e
                             :in $ ?wf
                             :where
                             [?e :workflow/definition ?wf]
                             (not [?e :workflow/completed-at _])]
                           (db/db) wf-id))]
    {:by-state (into {} state-counts)
     :completed completed
     :active active
     :total (+ completed active)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resource Loading
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-workflow-spec
  "Load a workflow specification from a resource file (EDN).

   Arguments:
     resource-path - Path to the resource file (e.g., \"workflows/resource-validation.edn\")

   Returns the parsed EDN map, or nil if resource not found."
  [resource-path]
  (when-let [resource (io/resource resource-path)]
    (with-open [reader (io/reader resource)]
      (edn/read (java.io.PushbackReader. reader)))))

(defn load-workflow-from-resource!
  "Load and define a workflow from a resource file.

   Arguments:
     resource-path - Path to the resource file (e.g., \"workflows/resource-validation.edn\")

   The resource file should be an EDN map with keys:
     :name        - Keyword identifier for the workflow
     :version     - Optional version number
     :states      - Vector of state specs
     :transitions - Vector of transition specs

   Returns the created workflow definition entity, or the existing workflow
   if it was already defined."
  [resource-path]
  (if-let [spec (load-workflow-spec resource-path)]
    (let [workflow-name (:name spec)]
      (if-let [existing (find-workflow workflow-name)]
        (do
          (log/debug :WORKFLOW/ALREADY-EXISTS {:name workflow-name})
          existing)
        (do
          (log/info :WORKFLOW/LOAD-FROM-RESOURCE {:path resource-path :name workflow-name})
          (define-workflow! workflow-name spec))))
    (throw (ex-info "Workflow resource not found" {:path resource-path}))))

(defn- list-classpath-resources
  "List resource basenames from a classpath directory.
   For file URLs walk the directory; for jar URLs inspect JarFile entries
   through JarURLConnection. Return a vector, or an empty vector when the
   directory is absent. This avoids treating a JAR URL as a filesystem path."
  [dir]
  (let [url (io/resource dir)]
    (cond
      (nil? url) []

      (= "file" (.getProtocol url))
      (->> (io/file url)
           file-seq
           (filter #(.isFile ^java.io.File %))
           (mapv #(.getName ^java.io.File %)))

      (= "jar" (.getProtocol url))
      (let [^JarURLConnection conn (.openConnection url)
            ^JarFile jar (.getJarFile conn)
            prefix (str dir "/")]
        (with-open [_ jar]
          (->> (enumeration-seq (.entries jar))
               (map #(.getName ^java.util.jar.JarEntry %))
               (filter #(str/starts-with? % prefix))
               (remove #(str/ends-with? % "/"))
               (map #(subs % (count prefix)))
               (remove str/blank?)
               (remove #(str/includes? % "/")) ; flat directory only — no nested
               vec)))

      :else [])))

(defn load-all-workflows-from-resources!
  "Load all workflow definitions from resources/workflows/ directory.

   Works uniformly across development (filesystem-backed classpath)
   and production (JAR-backed classpath; e.g., consumer pulling
   sandbar as a Clojars dependency).  Per Phase U Stage U-2 fix for
   ultrareview UR-1.

   Returns a map of workflow names to workflow definition entities."
  []
  (let [workflows-dir "workflows"
        filenames (list-classpath-resources workflows-dir)
        edn-files (filter #(str/ends-with? % ".edn") filenames)]
    (into {}
          (for [filename edn-files]
            (let [path (str workflows-dir "/" filename)
                  workflow (load-workflow-from-resource! path)]
              [(:workflow/definition-name workflow) workflow])))))

(defn ensure-workflow!
  "Ensure a workflow is defined, loading from resource if necessary.

   Arguments:
     workflow-name - Keyword identifier for the workflow
     resource-path - Optional path to resource file; defaults to
                     \"workflows/{name}.edn\" where name is the workflow name

   Returns the workflow definition entity."
  [workflow-name & [resource-path]]
  (or (find-workflow workflow-name)
      (let [path (or resource-path
                     (str "workflows/" (name workflow-name) ".edn"))]
        (load-workflow-from-resource! path))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cancellation
;;
;; Per the layer-targeting + improve-abstraction-not-bypass disciplines:
;; the MCP layer (sandbar.mcp.tasks/handle-cancel) needs a generic
;; cancellation primitive. Rather than each consumer reaching into raw
;; d/transact, we expose cancel-process! as a workflow-aware operation
;; that composes with transition!.
;;
;; Convention (per
;; decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md):
;; a workflow that supports cancellation declares one or more terminal
;; states with :workflow/terminal-kind :cancel and at least one
;; transition leading to such a state from a non-terminal state.
;; Cancelability is determined by modeled classification, NOT by
;; ident-name string heuristic.

(defn- cancel-transition-for
  "Find an available transition that targets a state classified
   :workflow/terminal-kind :cancel.  Returns the transition entity, or
   nil if no cancel-shaped transition is available from the process's
   current state.  Per F-B-002 ADR; replaces the prior ident-name string
   heuristic."
  [process]
  (let [transitions (available-transitions process)]
    (->> transitions
         (filter (fn [t]
                   (when-let [target (:workflow/to-state t)]
                     (= :cancel (:workflow/terminal-kind target)))))
         first)))

(defn cancel-process!
  "Cancel through an available transition to a :cancel terminal state.
   Accept a process entity or eid; optional :actor and :reason pass through
   to transition!. Return the updated process. Throw ExceptionInfo with
   :reason :no-cancel-transition when no path is available. Cancellation
   retains the transition implementation's state/effect limitations."
  [process & {:keys [actor reason]
              :or   {reason "Cancelled by consumer (via workflow/cancel-process!)"}}]
  (let [process-entity (if (number? process) (find-process process) process)
        cancel-tx      (cancel-transition-for process-entity)]
    (when-not process-entity
      (throw (ex-info "Process not found"
                      {:reason :process-not-found
                       :input  process})))
    (when-not cancel-tx
      (throw (ex-info "Workflow does not support cancellation from the current state"
                      {:reason         :no-cancel-transition
                       :process-id     (:db/id process-entity)
                       :current-state  (some-> process-entity get-current-state :db/ident)})))
    ;; Transitions don't carry :db/ident (not :db.unique/identity); the
    ;; canonical action keyword lives on :workflow/transition-name.  Keep
    ;; :db/ident as the first preference for schema-defined transitions
    ;; that might carry an explicit ident in some future setup.
    (let [transition-name (or (:db/ident cancel-tx)
                              (:workflow/transition-name cancel-tx))]
      (log/info :workflow/cancel-process
                {:process-id (:db/id process-entity)
                 :transition transition-name
                 :actor      actor})
      (transition! process-entity transition-name
                   :actor  actor
                   :reason reason))))

(defn can-cancel?
  "Predicate: is the process in a state from which cancellation is
   possible? True iff at least one available transition targets a
   cancellation-shaped state."
  [process]
  (boolean (cancel-transition-for process)))
