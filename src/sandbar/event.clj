(ns sandbar.event
  "Boundary verb for substrate-event subscription with class-hierarchical
   dispatch.

   ## Thesis (per event-substrate keystone ADR D.2 + D.3)

   Subscribers register interest by EVENT CLASS.  The dispatcher fans
   out per event to all subscribers whose registered class is
   `:dt/subclass-of` (or equal to) the event's class — via
   `sandbar.db.datatype/ancestors-of` (which uses the existing memoized
   type-relation cache).  A MANDATORY dispatch cache keyed
   `{event-class-ident → subscriber-set}` is rebuilt on demand +
   invalidated on subscriber-change or schema-reload.

   ## Phase status

   Phase 2 of the event-substrate keystone ADR.  Implements the
   subscribe/unsubscribe boundary verbs + class-hierarchical dispatch
   cache.

   Composes with Phase 1 (`sandbar.reactive.tx-source` — the
   transactional event publisher).  Phase 1's Manifold stream can be
   bridged to this dispatcher via a `(ms/consume #'fire! stream)` call.

   ## Not yet implemented (deferred to follow-on phases)

   - Per-subscriber `:async` policy (sliding-buffer / dropping / etc.) — Phase 6
   - Per-subscriber handler-isolation thread (gen_event anti-lesson) — Phase 7
   - Per-class buffer policy slot (`:mm.event/buffer-policy`) — Phase 6
   - Chronicle Queue escape hatch for non-droppable + high-volume — Phase 6

   ## Boundary discipline

   Public surface:
     `subscribe!`     — register a handler fn against an event class
     `unsubscribe!`   — remove a registered handler
     `subscribers-of` — diagnostic: what subscribers are registered for a class
     `dispatch-set`   — the set of subscribers matched for a given event-class
                        (computed via class-hierarchy walk; memoized)
     `fire!`          — publish an event to all matching subscribers
     `clear!`         — test-only; reset all subscriber state

   Per `memory/decisions/sandbar_event_substrate_architecture_datomic_tx_report_queue_wrapped_behind_dt_star_manifold_transport_class_hierarchical_subscription_2026_05_23.md`."
  (:require [clojure.tools.logging :as log]
            [sandbar.db.datatype   :as dt]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscriber registry + dispatch cache
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private +subscribers+
  ;; {class-ident → #{subscriber-fn ...}}
  (atom {}))

(defonce ^:private +dispatch-cache+
  ;; {event-class-ident → #{matched-subscriber-fn ...}}
  ;; Computed by walking the class hierarchy + accumulating subscribers
  ;; from each ancestor.  Invalidated on subscriber-change + schema-reload.
  (atom {}))

(defn- invalidate-cache!
  "Drop the dispatch cache (forcing recomputation on next event)."
  []
  (reset! +dispatch-cache+ {}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public subscribe / unsubscribe API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn subscribe!
  "Register `subscriber-fn` to receive events of class `class-ident` or
   any subclass thereof.

   `subscriber-fn` shape: `(fn [event] ...)` — receives the event map.
   Returned for use as the unsubscribe handle (set-equality matching).

   Idempotent — registering the same fn twice for the same class is a
   noop (set semantics).  Multiple distinct fns for the same class all
   receive each matching event."
  [class-ident subscriber-fn]
  (swap! +subscribers+ update class-ident (fnil conj #{}) subscriber-fn)
  (invalidate-cache!)
  subscriber-fn)

(defn unsubscribe!
  "Remove a previously-registered subscriber.  Idempotent."
  [class-ident subscriber-fn]
  (swap! +subscribers+ update class-ident (fnil disj #{}) subscriber-fn)
  (invalidate-cache!)
  nil)

(defn subscribers-of
  "Diagnostic: return the set of subscriber fns registered DIRECTLY
   for `class-ident` (does NOT include subclass-inherited subscribers
   — for the fan-out set use `dispatch-set`)."
  [class-ident]
  (get @+subscribers+ class-ident #{}))

(defn subscriber-count
  "Diagnostic: total number of registered (class, fn) pairs across
   all classes."
  []
  (reduce + (map count (vals @+subscribers+))))

(defn clear!
  "Test-only — clear all subscribers + dispatch cache."
  []
  (reset! +subscribers+ {})
  (invalidate-cache!))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class-hierarchical dispatch via dt/ancestors-of
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- compute-dispatch-set
  "For event-class `event-class-ident`, walk up the class hierarchy
   (via `dt/ancestors-of` — uses the existing memoized substrate
   cache) + accumulate every subscriber registered against the class
   itself OR any of its ancestors.

   Per the build-on-the-type-system discipline
   (memory/interaction/build_on_type_system_reflectively_and_prospectively_dont_reinvent_in_parallel_due_to_tactical_concerns_2026_05_23.md)
   — uses the substrate's existing ancestor-walk rather than
   reimplementing class traversal here."
  [event-class-ident]
  (let [registry @+subscribers+
        ;; The event-class itself + all ancestors (transitive)
        candidate-classes (cons event-class-ident
                                (try (dt/ancestors-of event-class-ident)
                                     (catch Throwable _ [])))]
    (->> candidate-classes
         (mapcat (fn [c] (get registry c)))
         (into #{}))))

(defn dispatch-set
  "Return the set of subscribers that should receive an event of class
   `event-class-ident`.  Memoized via the dispatch cache."
  [event-class-ident]
  (if-let [cached (get @+dispatch-cache+ event-class-ident)]
    cached
    (let [computed (compute-dispatch-set event-class-ident)]
      (swap! +dispatch-cache+ assoc event-class-ident computed)
      computed)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dispatch — fire! invokes all matching subscribers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn event-class
  "Resolve an event's class.  Prefers explicit `:event/class` slot;
   falls back to `:event/kind` (Phase 1 tx-source events carry
   `:event/kind :tx`)."
  [event]
  (or (:event/class event)
      (:event/kind event)))

(defn fire!
  "Publish `event` to all subscribers matched by the event's class
   (per `dispatch-set`).

   Each subscriber is invoked in isolation — handler errors are
   caught + logged at `:warn`; one failed handler does NOT prevent
   sibling handlers from receiving the event (per the gen_event
   anti-lesson — handler isolation from the start).

   Phase 2 dispatch is SYNCHRONOUS within the caller's thread.
   Phase 7 will add per-subscriber async + thread isolation domains
   (the publisher continues regardless; subscribers process in their
   own contexts)."
  [event]
  (when-let [class-ident (event-class event)]
    (doseq [subscriber-fn (dispatch-set class-ident)]
      (try
        (subscriber-fn event)
        (catch Throwable t
          (log/warn t :EVENT/handler-failed
                    {:class   class-ident
                     :handler (.getName (class subscriber-fn))}))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema-reload hook — invalidate cache when class hierarchy mutates
;;
;; The dispatch cache is computed against the current class hierarchy.
;; When schema reloads (which may add new subclasses), the cache must
;; flush so subsequent fires walk the updated hierarchy.
;;
;; Per ADR D.3 (mandatory dispatch cache):
;;   "Cache invalidation: hooks into the existing
;;    clear-type-relation-cache! post-schema-reload registry per
;;    memory.decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.
;;    When schema reloads, dispatch cache flushes."
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(try
  ;; Defensive — the registration verb is in sandbar.db.datomic; if not
  ;; available (load order issue), skip + the cache stays manually-
  ;; invalidated via subscribe!/unsubscribe! only.
  (when-let [reg-handler (requiring-resolve 'sandbar.db.datomic/register-post-schema-reload-handler!)]
    (reg-handler ::dispatch-cache-flush
                 (fn [] (invalidate-cache!))))
  (catch Throwable _ nil))
