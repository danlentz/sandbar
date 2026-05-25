(ns sandbar.reactive.tx-source
  "Boundary primitive wrapping Datomic `d/tx-report-queue` as a typed-event
   stream.

   ## Thesis (per event-substrate keystone ADR D.1)

   Datomic's tx-report-queue IS the substrate-native reactivity
   mechanism — every committed transaction surfaces as a TxReport.
   This namespace wraps it behind a sandbar-typed boundary primitive:

   - Captures EVERY committed tx (not just `dt/make` callsites — gets
     transactor-side fns, REST API writes, MCP writes, …all sources)
   - Multi-peer fan-out is Datomic-native (each peer connection gets
     its own queue)
   - NO Datomic types leak past the boundary — consumers see
     sandbar-typed event maps via a Manifold stream

   ## Phase status

   Phase 1 of the event-substrate keystone ADR.  Implements the
   tx-source boundary primitive itself.  Phase 2 (`sandbar.event/subscribe`
   + class-hierarchical dispatch cache via `dt/type-isa?`) is the
   sibling consumer-facing layer.

   ## Not yet implemented (deferred to follow-on phases)

   - Catchup-on-disconnect via `d/tx-range` with last-basis-t checkpoint
     (Phase 1.5)
   - Typed `:mm.event/*` subclass classification (Phase 3 — needs the
     event schema authored)
   - Buffer-policy slot on subscriptions (Phase 6)

   ## Boundary discipline

   Public surface (this namespace's exports):
     `start!`      — begin polling the tx-report-queue; returns a stream
     `stop!`       — stop polling; close the stream; idempotent
     `stream`      — accessor for the public Manifold stream
     `tx-report->event` — pure translation (testable independently)

   Datomic types (`datomic.Datom`, `TxReport`, `Connection`) are
   STRICTLY confined to this namespace.  Consumers never see them.

   Per `memory/decisions/sandbar_event_substrate_architecture_datomic_tx_report_queue_wrapped_behind_dt_star_manifold_transport_class_hierarchical_subscription_2026_05_23.md`."
  (:require [clojure.tools.logging :as log]
            [datomic.api           :as d]
            [manifold.stream       :as ms]
            [sandbar.db.datomic    :as db]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal state — atom holding the running worker + stream
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private +state+
  (atom {:running?      false
         :stream        nil
         :worker        nil
         :running?-atom nil}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TxReport → sandbar-typed event translation (pure; testable)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- datom->vec
  "Translate a `datomic.Datom` → sandbar-typed `[e a v added?]` tuple.
   The 5th `:t` element is dropped (per-datom basis-t is rarely needed
   downstream; the tx's basis-t lives at the event level)."
  [datom]
  [(:e datom) (:a datom) (:v datom) (:added datom)])

(defn- find-tx-instant
  "Extract `:db/txInstant` from a tx's datom vec (the tx entity asserts
   its own commit timestamp).  Returns the instant or nil."
  [datoms]
  (some (fn [[_ a v _]]
          (when (= a :db/txInstant) v))
        datoms))

(defn tx-report->event
  "Translate a Datomic TxReport map → sandbar-typed event value.

   The TxReport carries:
     `:tx-data`    — Iterable of `Datom`s transacted
     `:db-before`  — db value before commit
     `:db-after`   — db value after commit
     `:tempids`    — tempid→eid resolution map

   Returns a sandbar-typed map with NO Datomic-specific types:
     `:event/kind`        always `:tx` (Phase 1 — broader kind taxonomy lands in Phase 3)
     `:event/timestamp`   `#inst` commit time (from `:db/txInstant`)
     `:event/tx-id`       long basis-t after commit (stable across peers)
     `:event/datom-count` count of datoms transacted
     `:event/tempids`     `{tempid eid}` map (Long keys + values)
     `:event/datoms`      vec of `[e a v added?]` tuples

   The db-before / db-after values are NOT exposed in the public event
   shape (they're Datomic-specific connection-bound values).  Phase 2
   subscribers compose their own queries against the current `db` if
   they need post-tx state."
  [{:keys [tx-data db-after tempids] :as _tx-report}]
  (let [datoms  (mapv datom->vec tx-data)
        tx-inst (find-tx-instant datoms)]
    {:event/kind        :tx
     :event/timestamp   tx-inst
     :event/tx-id       (when db-after (d/basis-t db-after))
     :event/datom-count (count datoms)
     :event/tempids     (or tempids {})
     :event/datoms      datoms}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Worker — background thread polling the tx-report-queue
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- run-worker!
  "Background thread loop: poll the tx-report-queue with a 1-second
   timeout; translate each TxReport to a sandbar event; publish to the
   Manifold stream.  Loops while `running?-atom` is true.

   Exception safety:
   - Per-tx translation failures are caught + logged at `:warn` (the
     individual tx's event is dropped; the worker continues)
   - Worker crash (anything that escapes the inner try/catch) is
     logged at `:error`; the worker thread exits.  Restart via
     `(stop!)` then `(start!)`."
  [tx-queue stream running?-atom]
  (try
    (loop []
      (when @running?-atom
        (when-let [tx-report (.poll ^java.util.concurrent.BlockingQueue tx-queue
                                    1000
                                    java.util.concurrent.TimeUnit/MILLISECONDS)]
          (try
            (let [event (tx-report->event tx-report)]
              (ms/put! stream event))
            (catch Throwable t
              (log/warn t :REACTIVE.TX-SOURCE/translate-failed
                        {:tx-id (some-> tx-report :db-after d/basis-t)}))))
        (recur)))
    (catch Throwable t
      (log/error t :REACTIVE.TX-SOURCE/worker-crashed))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public lifecycle API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const +default-buffer-size+ 1024)

(defn start!
  "Start the tx-source.  Idempotent — second call returns the existing
   stream without spawning another worker.

   Returns the public Manifold stream that Phase 2 subscribers attach
   to.  The stream's buffer is 1024 events; under back-pressure new
   events are dropped (Phase 6 will add per-class buffer policy)."
  []
  (let [{:keys [running? stream]} @+state+]
    (if running?
      stream
      (let [conn          (db/conn)
            tx-queue      (d/tx-report-queue conn)
            new-stream    (ms/stream +default-buffer-size+)
            running?-atom (atom true)
            worker        (doto (Thread. #(run-worker! tx-queue new-stream running?-atom))
                            (.setName "sandbar.reactive.tx-source-worker")
                            (.setDaemon true)
                            (.start))]
        (reset! +state+ {:running?      true
                         :stream        new-stream
                         :worker        worker
                         :running?-atom running?-atom})
        (log/info :REACTIVE.TX-SOURCE/STARTED
                  {:buffer-size +default-buffer-size+
                   :thread-name "sandbar.reactive.tx-source-worker"})
        new-stream))))

(defn stop!
  "Stop the tx-source.  Idempotent — second call returns nil cleanly.
   Closes the public stream (subscribers receive end-of-stream); signals
   the worker thread to exit at its next poll interval (within ~1s)."
  []
  (let [{:keys [running? stream running?-atom]} @+state+]
    (when running?
      (when running?-atom (reset! running?-atom false))
      (when stream (ms/close! stream))
      (log/info :REACTIVE.TX-SOURCE/STOPPED)))
  (reset! +state+ {:running? false :stream nil :worker nil :running?-atom nil})
  nil)

(defn stream
  "Accessor for the public Manifold stream.  Returns nil if the
   tx-source has not been started (or has been stopped)."
  []
  (:stream @+state+))

(defn running?
  "Diagnostic: is the tx-source currently running?"
  []
  (boolean (:running? @+state+)))
