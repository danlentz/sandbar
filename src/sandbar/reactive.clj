(ns sandbar.reactive
  "Post-mutation callback layer for derived projection.
   Eligible datatype mutations call on-entity-changed! with class, the accepted
   entity and the per-call projection option. Registered callbacks receive
   the eid and entity. Per-call options, dynamic bindings and class policy can suppress
   projection. Callbacks are registered/unregistered explicitly; the queue
   is one consumer. This hook is not a subscription to every raw Datomic
   transaction. See doc/concepts/reactive-substrate.md."
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
  "Class idents whose instances skip reactive projection by default.
   These include schema/bootstrap and runtime records that should not become
   ordinary collection documents. The mutation path also consults its other
   opt-out and class-policy controls; membership here is not authorization."
  #{:dt/Class
    :dt/Property
    :mm/Workflow
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
  (let [eid   (:db/id entity)
        ident (:db/ident entity)]
    (if (project? class-ident per-call-project?)
      (do
        (log/debug :REACTIVE/hook-fired
                   {:ident      ident
                    :eid        eid
                    :class      class-ident
                    :callbacks  (count @+callbacks+)})
        (dispatch! eid entity))
      (do
        (log/debug :REACTIVE/opt-out-skip
                   {:ident  ident
                    :eid    eid
                    :class  class-ident
                    :reason (opt-out-reason class-ident per-call-project?)})
        nil))))
