(ns sandbar.logging.handlers
  "Telemere handlers that bridge flagged signals into the sandbar
   substrate as memorial entities (logging arc Stage D).

   ## Memorial-flag routing

   For signals carrying `:data {:memorial <policy>}`, the
   `memorial-projection-handler` routes per policy:

   - `:first-class` → `:mm/EventLog` instance.  The reactive
     substrate's fs-projection sink writes the entity to
     `memory/event-logs/<>.md`.
   - `:db-only`     → `:event/SystemEvent` runtime instance under the
     existing `:dt/Event` substrate hierarchy.  Operational audit;
     covered by DB-dump arc; NOT FS-projected.
   - `:inline`      → deferred (rare; not implemented in Stage D MVP).
   -  nil / absent  → no-op (transient signal; handlers fire normally;
     no DB persistence).

   ## Anti-cycle defense (five-layer per logging arc plan §3.3)

   - **Layer 2 (this ns)**: `*log-substrate-active?*` thread-local
     reentry guard — set true on handler entry; checked at handler
     entry; nested re-entry early-exits.
   - **Layer 3 (Telemere)**: per-handler `:async {:mode :dropping
     :buffer-size 4096}` (here we run `:async nil` to keep the
     sandbar.reactive substrate owning async; for memorial-projection
     this is appropriate since each handler invocation creates exactly
     one entity).
   - **Layer 4 (sandbar.reactive)**: this handler's namespace is in
     `sandbar.reactive`'s class-skip-list inheritance pattern via the
     reentry guard rather than direct class match.
   - **Layer 5 (caller opt-out)**: callers may bind
     `(binding [*log-substrate-active?* true] ...)` to suppress
     memorial-projection for a scope.

   ## Per:
   - memory/decisions/mm_activity_prov_o_lift_cross_arc_unification_log_eventlog_run_2026_05_23.md
   - memory/plans/sandbar_logging_discipline_and_strategy_arc_telemere_primary_csp_signals_anti_cycle_disciplines_2026_05_23.md §4.4 + §4.5"
  (:require [sandbar.db.datatype :as dt]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Anti-cycle reentry guard (Layer 2)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *log-substrate-active?*
  "When true, the memorial-projection-handler is on-stack.  Subsequent
   logging callsites whose signals would trigger memorial-projection
   are skipped — prevents logging→reaction→logging feedback loops.

   Callers may bind this to true to suppress memorial-projection for
   a scope (e.g., bulk-import where individual log entries shouldn't
   each spawn an :mm/EventLog)."
  false)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Signal → entity-spec helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- safe-pr-str
  "Bounded pr-str — survives infinite seqs / deep nesting / cycles by
   capping `*print-length*` + `*print-level*`.  Returns a string."
  [v]
  (try
    (binding [*print-length* 100
              *print-level*  8
              *print-namespace-maps* false]
      (pr-str v))
    (catch Throwable _
      (str v))))

(defn- inst->date
  "Coerce a signal's timestamp to `java.util.Date` for Datomic
   `:db.type/instant` slots.  Telemere populates `:inst` with a
   `java.time.Instant`, but Datomic's instant type only accepts
   `java.util.Date` — writing an `Instant` directly throws
   `IllegalArgumentException: Cannot write <ts> as tag null`.

   Passes nil + existing `Date`s through unchanged; converts an
   `Instant` via `Date/from`; leaves any other value as-is (defensive)."
  [t]
  (cond
    (nil? t)                          nil
    (instance? java.util.Date t)      t
    (instance? java.time.Instant t)   (java.util.Date/from t)
    :else                             t))

(defn- resolve-signal-msg
  "Resolve the message string from a Telemere signal.  The :msg_ field
   (when present) is a delay; force it.  Falls back to :msg or empty
   string."
  [signal]
  (or (:msg signal)
      (when-let [d (:msg_ signal)] (force d))
      ""))

(defn- resolve-signal-id
  "Extract the signal's identifying keyword.  Prefer explicit `:id`;
   fall back to `:data :event` (for Datomic peer events); fall back to
   a synthetic untagged marker."
  [signal]
  (or (:id signal)
      (some-> signal :data :event)
      :sandbar.event-log/untagged))

(defn- resolve-source-ns
  "Source namespace for the signal.  For SLF4J-bridged signals the
   logger name lives at `:location :ns`; for native callsites at `:ns`.
   Per memory/observations/slf4j_telemere_signal_shape_logger_name_lives_in_location_ns_not_ns_2026_05_23.md."
  [signal]
  (str (or (get-in signal [:location :ns])
           (:ns signal)
           "unknown")))

(defn signal->event-log-spec
  "Telemere signal → `:mm/EventLog` entity-spec.

   Returns a slot-map for `(dt/make :mm/EventLog spec)`.  Populates the
   PROV-O `:mm.activity/*` slots + `:mm.event-log/*` slots.  Drops the
   `:memorial` flag from `:data` before storage (it was a routing hint;
   not part of the durable payload)."
  [signal]
  (let [inst (or (inst->date (:inst signal)) (java.util.Date.))]
    {:mm.activity/started-at  inst
     :mm.activity/ended-at    inst
     :mm.activity/status      :succeeded
     :mm.event-log/level      (or (:level signal) :info)
     :mm.event-log/signal-id  (resolve-signal-id signal)
     :mm.event-log/source-ns  (resolve-source-ns signal)
     :mm.event-log/msg        (resolve-signal-msg signal)
     :mm.event-log/data       (safe-pr-str (dissoc (:data signal) :memorial))}))

(defn signal->server-event-spec
  "Telemere signal → `:event/SystemEvent` entity-spec for `:db-only`
   policy.  Uses the existing `:dt/Event` substrate runtime hierarchy
   (compatible with `sandbar.util.event`)."
  [signal]
  {:event/timestamp  (or (inst->date (:inst signal)) (java.util.Date.))
   :event/level      (or (:level signal) :info)
   :event/name       (str (resolve-signal-id signal))
   :event/namespace  (resolve-source-ns signal)
   :event/kind       :memorial/db-only})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn memorial-projection-handler
  "Telemere `output-fn`-shaped handler:

     `([signal])` — handle one signal
     `([])`       — shutdown drain (no-op; sandbar.reactive owns lifecycle)

   For signals carrying `:data {:memorial <policy>}`, bridges into the
   sandbar substrate per the routing table (see ns docstring).
   Reentry-guarded via `*log-substrate-active?*`.

   Failures are caught + reported to `*err*` (NOT logged via Telemere
   — that would re-enter this handler) so the signal never crashes the
   handler chain.  The next signal still dispatches."
  ([] nil)
  ([signal]
   (when-not *log-substrate-active?*
     (binding [*log-substrate-active?* true]
       (try
         (when-let [policy (some-> signal :data :memorial)]
           (case policy
             :first-class
             (dt/make :mm/EventLog (signal->event-log-spec signal))

             :db-only
             (dt/make :event/SystemEvent (signal->server-event-spec signal))

             :inline
             nil ; deferred — Stage D MVP scope

             nil))
         (catch Throwable t
           (binding [*out* *err*]
             (println "[sandbar.logging.handlers/memorial-projection-handler]"
                      "failed for signal" (resolve-signal-id signal)
                      ":" (.getMessage t)))))))))
