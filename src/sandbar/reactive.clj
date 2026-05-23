(ns sandbar.reactive
  "Reactive-projection substrate hook layer.

   ## What this exists for

   Sandbar entity mutations (via the canonical dt/* primitive layer —
   `dt/make` / `dt/make-all` / `dt/update-entity!`) trigger reactive
   projection of the post-mutation entity state to parallel surfaces
   (filesystem markdown corpus; SSE-subscribed clients; audit
   substrates).  This namespace owns:

   - The HOOK DISPATCH POINT at the dt/* boundary
   - The OPT-OUT mechanism (three-layer priority resolution)
   - STRUCTURED LOGGING at the hook-fire / opt-out-skip boundary
   - The CALLBACK REGISTRY that downstream pipeline modules (codec.emit /
     fs.write / SSE.emit per Stage B; bounded queue per Stage A.6) attach
     to

   ## Decision lineage

   - `decisions/reactive_projection_hook_at_dt_make_boundary_with_opt_out_2026_05_23.md`
     (eid 17592186094347) — WHERE the hook attaches (dt/* not MCP) +
     three-layer opt-out (per-call kwarg / dynamic binding / class
     skip-list)
   - `decisions/reactive_projection_queue_bounded_buffer_and_health_observability_2026_05_23.md`
     (eid 17592186094353) — what happens AFTER the hook (bounded
     core.async sliding-buffer; `sandbar.reactive.health` MCP verb)
   - `decisions/reactive_projection_structured_logging_required_for_states_significant_actions_2026_05_23.md`
     (eid 17592186094433) — `:REACTIVE/<event-name>` log vocabulary
   - `plans/sse_reactive_corpus_projection_arc_2026_05_23.md`
     (eid 17592186094359) — the parent arc plan

   ## Stage tracking

   - Stage A.5 (THIS module): opt-out + callback dispatch + structured
     log at the hook-fire boundary.  Callbacks default empty; A.5 ships
     the dispatch point only.
   - Stage A.6 (separate module): bounded queue worker registers as a
     callback; codec.emit + fs.write + SSE.emit fire from the worker.
   - Stage B.1+: wire the pipeline sinks.

   ## Public surface

     `*reactive-projection-enabled?*` — dynamic var (default true);
       bind to false for scoped opt-out (e.g., project.import echo
       prevention, bulk-seed defer, test fixtures)

     `(on-entity-changed! class-ident entity per-call-project?)` —
       fire the hook; opt-out checks apply.  Called by dt/make /
       dt/make-all / dt/update-entity! after a successful transaction.

     `(register-callback! callback-fn)` — add a callback receiving
       `[entity-eid post-tx-slots]` on every hook fire

     `(unregister-callback! callback-fn)` — remove a previously-
       registered callback

     `(clear-callbacks!)` — test-only; reset registry"
  (:require [clojure.tools.logging :as log]
            [sandbar.db.datomic    :as db]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Opt-out — three-layer priority resolution
;;
;; Layer 1 (highest priority): per-call kwarg `:project?` (passed to
;;   `dt/make` / `dt/update-entity!`).  Explicit override at the
;;   single-mutation level.
;;
;; Layer 2: dynamic binding `*reactive-projection-enabled?*`.  Scoped
;;   bulk-bypass for nested transaction sets:
;;     (binding [reactive/*reactive-projection-enabled?* false]
;;       (dt/make-all <bulk-batch>))
;;
;; Layer 3 (lowest priority; default): class-level skip-list.  Meta-
;;   substrate classes excluded by default (schema bootstrap classes;
;;   workflow substrate classes; etc.).
;;
;; Resolution: most-specific-wins.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *reactive-projection-enabled?*
  "When false, suppresses reactive-projection for all mutations within
   the binding scope.  Default true.

   Used by:
   - `sandbar.project.import` (echo prevention — the import is reading
     FROM fs; reactively projecting back would cause an immediate
     re-write of the imported file)
   - Bulk seeds + fixtures (defer projection until batch done; avoids
     N fs-writes per tx)
   - Test setups (no fs side-effects)
   - Future federation backbone (replicated mutations already exist at
     the source; local projection would duplicate)

   Binding shape:
     (binding [sandbar.reactive/*reactive-projection-enabled?* false]
       (dt/make-all <big-batch>))"
  true)

(def ^:private +class-skip-list+
  "Class idents whose instances DO NOT trigger reactive-projection by
   default.  These are meta-substrate classes — schema bootstrap
   entities + workflow substrate entities — whose mutations are not
   user-facing corpus state and shouldn't materialize as filesystem
   .md files.

   Per `decisions/reactive_projection_hook_at_dt_make_boundary_with_opt_out_2026_05_23.md`
   §2.2 Layer 3 (class-level default).

   Future evolution: this set could become data-driven via a
   `:dt/reactive-projection?` slot on `:dt/Class` (read at hook-fire
   time).  Hardcoded for Stage A.5 MVP; data-driven shape lands in a
   follow-on if the skip-set grows."
  #{:dt/Class
    :dt/Property
    :workflow/Definition
    :workflow/Process})

(defn skip-class?
  "Returns true if the class ident is in the reactive-projection
   skip-list (Layer 3 default)."
  [class-ident]
  (contains? +class-skip-list+ class-ident))

(defn project?
  "Resolve the three-layer priority: should this mutation trigger
   reactive-projection?

   Args:
     class-ident       — entity's `:dt/type` ident
     per-call-project? — `:project?` kwarg value from the dt/* call;
                         nil means \"unspecified; fall through to
                         Layer 2/3 resolution\"

   Priority (most-specific-wins):
     1. per-call kwarg (true / false; nil falls through)
     2. dynamic binding (`*reactive-projection-enabled?*`)
     3. class-level skip-list default

   Returns true if reactive-projection should fire."
  [class-ident per-call-project?]
  (cond
    (some? per-call-project?)            per-call-project?
    (not *reactive-projection-enabled?*) false
    (skip-class? class-ident)            false
    :else                                true))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Callback registry — Stage A.5 dispatch point
;;
;; Callbacks are functions of [entity-eid post-tx-slots].  Multiple
;; callbacks may register — they're invoked in registration order; one
;; failed callback doesn't block others.
;;
;; Stage A.5 ships an EMPTY registry by default (no callbacks pre-
;; registered).  Stage A.6 registers the bounded-queue enqueue function
;; as a callback; the worker drains the queue + invokes codec.emit +
;; fs.write + SSE.emit downstream.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private +callbacks+
  (atom []))

(defn register-callback!
  "Register a reactive-projection callback.  Callbacks receive
   `[entity-eid post-tx-slots]` when a mutation passes opt-out checks.

   `post-tx-slots` is the entity-map (Datomic Entity or plain map)
   reflecting the entity's state immediately after the transaction
   that triggered the hook.  Callbacks should not assume the entity
   is still in this state at callback-invocation time (it may have
   been further mutated; reactive projection is fundamentally racy
   under concurrent writes — last-state-wins via sliding-buffer
   coalescing per A.6).

   Returns the callback fn (for unregister)."
  [callback-fn]
  (when (fn? callback-fn)
    (swap! +callbacks+ conj callback-fn))
  callback-fn)

(defn unregister-callback!
  "Remove a previously-registered callback.  Identity-equality match.
   Returns nil."
  [callback-fn]
  (swap! +callbacks+ (fn [cs] (vec (remove #(identical? % callback-fn) cs))))
  nil)

(defn clear-callbacks!
  "Test-only: drop all registered callbacks."
  []
  (reset! +callbacks+ []))

(defn callback-count
  "Diagnostic: how many callbacks are currently registered?"
  []
  (count @+callbacks+))

(defn- dispatch!
  "Fire all registered callbacks with [entity-eid post-tx-slots].

   Wraps each callback in try/catch — one failed callback doesn't block
   others; the dt/* mutation already committed.  Logged at :warn level
   per `:REACTIVE/callback-failed`.

   Returns nil."
  [entity-eid post-tx-slots]
  (doseq [callback @+callbacks+]
    (try
      (callback entity-eid post-tx-slots)
      (catch Throwable t
        (log/warn t :REACTIVE/callback-failed
                  {:eid            entity-eid
                   :callback-class (.getName (class callback))}))))
  nil)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public hook entry-point — called from dt/* primitives

(defn- opt-out-reason
  "Determine WHICH opt-out layer fired (for structured log payload)."
  [class-ident per-call-project?]
  (cond
    (false? per-call-project?)           :per-call-kwarg
    (not *reactive-projection-enabled?*) :dynamic-binding
    (skip-class? class-ident)            :class-skip-list
    :else                                :unknown))

(defn on-entity-changed!
  "Reactive-projection hook fired by dt/* substrate primitives after a
   successful mutation.

   Performs three-layer opt-out checks (`project?`); when checks pass,
   dispatches to all registered callbacks (`dispatch!`).

   Args:
     class-ident       — entity's `:dt/type` ident keyword
     entity            — post-tx entity-map (Datomic Entity OR plain map);
                         must carry `:db/id`
     per-call-project? — `:project?` kwarg value from the dt/* call; nil
                         means unspecified

   Structured logging:
     `:REACTIVE/hook-fired`   (:debug) — when opt-out checks pass
     `:REACTIVE/opt-out-skip` (:debug) — when opt-out checks suppress

   Returns nil.  Failure modes are logged + swallowed (the dt/* mutation
   already succeeded; the reactive-projection side-effect shouldn't
   crash the substrate)."
  [class-ident entity per-call-project?]
  (let [eid (:db/id entity)]
    (if (project? class-ident per-call-project?)
      (do
        (log/debug :REACTIVE/hook-fired
                   {:eid        eid
                    :class      class-ident
                    :callbacks  (count @+callbacks+)})
        (dispatch! eid entity))
      (do
        (log/debug :REACTIVE/opt-out-skip
                   {:eid    eid
                    :class  class-ident
                    :reason (opt-out-reason class-ident per-call-project?)})
        nil))))
