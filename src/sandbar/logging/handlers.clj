(ns sandbar.logging.handlers
  "Handlers for explicitly retained logging signals.
   :first-class requests an :mm/EventLog, :db-only requests an
   :event/SystemEvent, and :inline is reserved. Unflagged signals request no
   database record. Ordinary logging filters and handler configuration still
   affect delivery.

   A thread-local reentry guard limits recursive persistence. Handler errors
   are caught and printed to stderr, so returning from a logging call does not certify
   that a database record or projected file was retained. Use a checked
   entity operation when persistence is required for correctness."
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
  "Return a source namespace string, preferring [:location :ns], then :ns,
   then unknown when neither is present."
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
