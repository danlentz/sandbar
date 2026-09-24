(ns sandbar.event
  "Class-hierarchical in-process event subscription and dispatch.
   Subscribers register for a class; an event reaches subscribers to that
   class or its ancestors. Dispatch caches are invalidated by subscription
   changes and schema reload callbacks.

   Delivery is synchronous in the publishing thread, with exception barriers
   around individual handlers. Slow handlers still delay the publisher.
   The bus does not provide durable replay, isolated subscriber threads or
   per-subscriber backpressure. The transaction-report source can be bridged
   explicitly; it is not automatically wired by subscribing here.
   See doc/concepts/event-substrate.md."
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
  "Collect distinct subscribers registered on the event class or any ancestor.
   If ancestry lookup fails, only the event class's direct subscribers are used.
   This computes a set; dispatch-set handles caching the result."
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
  ;;
  ;; Keyed registration (`::dispatch-cache-flush`): re-registration on
  ;; namespace reload REPLACES the handler.  History (2026-09-18, sprint
  ;; item 1.2): this two-argument call ran against a ONE-argument
  ;; registrar for months; the ArityException was swallowed by the silent
  ;; catch-all below, so NO handler was ever registered and the dispatch
  ;; cache kept its pre-reload hierarchy until a subscribe!/unsubscribe!
  ;; happened to flush it.  The registrar now has the keyed arity, and the
  ;; catch is LOUD.  Pinned by test/sandbar/event_schema_reload_hook_test.clj.
  (when-let [reg-handler (requiring-resolve 'sandbar.db.datomic/register-post-schema-reload-handler!)]
    (reg-handler ::dispatch-cache-flush
                 (fn [] (invalidate-cache!))))
  (catch Throwable t
    (log/warn t :EVENT/DISPATCH-CACHE-RELOAD-HOOK-REGISTRATION-FAILED
              "post-schema-reload flush for the event dispatch cache was NOT registered; the cache will only flush on subscribe!/unsubscribe!")))
