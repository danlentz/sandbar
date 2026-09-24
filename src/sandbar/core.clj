(ns sandbar.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.logging.init :as logging-init]
            [sandbar.mcp.tools :as tools]
            [sandbar.reactive :as reactive]
            [sandbar.reactive.queue :as reactive-queue]
            [sandbar.reactive.sinks :as reactive-sinks]
            [sandbar.schedule :as sched]
            [sandbar.schedule.system :as sched-sys]
            [sandbar.search :as search]
            [sandbar.server.nrepl :as nrepl]
            [sandbar.server.pedestal :as pedestal]
            [sandbar.sys :as sys]
            [sandbar.util.common :as util]
            [sandbar.util.edn :as edn]))

;; γ.3 — forward-declared so `start` can refer to it (definition follows
;; `start` because it logically belongs to the lifecycle section).
(declare start-scheduler-if-enabled!)

;; NOTE (2026-07-07): a global `(alter-var-root #'*read-eval* (constantly false))`
;; backstop was tried here (F1 read-plane hardening) and REMOVED — it broke a
;; legitimate load-time `#=(...)` reader-eval on a FRESH server start
;; (ExceptionInInitializerError in sandbar.util.event / sandbar.service.content,
;; poisoning the /mcp response-encoding interceptors -> HTTP 500 on every MCP
;; request).  The "zero #= uses in src/" premise was FALSE (a dependency or
;; resource on the load path uses reader-eval; it only survived originally
;; because F1 was hot-loaded AFTER everything had already initialized).  The
;; real parse-time vector-B closure lives in mcp/tools.clj (search :where routed
;; through the edn/read-string-based ->where-clauses) and does NOT depend on
;; this global.  If a *read-eval* backstop is wanted, it must be scoped to the
;; request-handling thread pool via a `binding`, never a process-wide root
;; change during/after load.
;;
;; The query-time fn-resolution vector (AP-S3-6 vector A) is closed separately
;; by the Layer-1 deny-by-default allowlist in
;; sandbar.security.query/sanitize-where, wired into the three read-plane splice
;; sites (db.datatype/count-of, db.datatype/group-by-of,
;; search/where-matching-eids) — see that namespace.  No process-wide state.

(defn make-system
  "Construct the unstarted component system map.

   The default :config designator uses `sandbar.config/config`: bundled
   defaults (with the committed config-example.edn fallback), client
   .sandbar/config.edn, and environment overrides. Other designators read a
   raw classpath EDN resource.

   A map argument supplies optional :config, :db-spec, :port, and :nrepl?
   overrides while retaining the production component types and dependencies.
   Tests can therefore boot the same graph on an isolated store and free port.

   The Pedestal component depends on Datomic, so HTTP starts only after the
   database component initializes the schema and connection. Projection sinks
   are registered by `start` before component startup and consult the database
   when they drain, preventing a startup window that accepts unprojected writes."
  ([] (make-system :config))
  ([designator-or-overrides]
   (let [overrides  (when (map? designator-or-overrides) designator-or-overrides)
         designator (if overrides :config designator-or-overrides)
         config     (or (:config overrides)
                        (if (= :config designator)
                          (edn/config-value)
                          (edn/resource-value designator nil)))
         db-spec    (or (:db-spec overrides) (db/db-spec))
         pedestal   (if-let [port (:port overrides)]
                      (pedestal/make-pedestal-server :dev port)
                      (pedestal/make-pedestal-server :dev))
         nrepl?     (:nrepl? overrides true)]
     (cond-> (component/system-map
               :config   config
               :pedestal (component/using pedestal [:datomic])
               :datomic  (db/make-datomic-peer db-spec))
       nrepl? (assoc :nrepl (nrepl/make-nrepl-server config))))))

(defn init []
  (log/info :SYS/INIT "Initializing system")
  (alter-var-root #'sys/system (constantly (make-system))))

(defn- bm25f-warmable-class?
  "True when `class-ident` has effective BM25F weights and is outside the
   :dt/Event runtime-event hierarchy. Runtime telemetry can grow independently
   of the corpus and is excluded from the startup warm sweep. Memory-model
   events under :mm/Event remain eligible corpus content."
  [class-ident]
  (and (seq (dt/effective-bm25f-weights-of class-ident))
       (not (dt/type-isa? :dt/Event class-ident))))

(defn- register-codecs!
  "Register codecs with the mediator so MCP entity.create + project.import/export
   can codec-mediate via :format opt.  Per `sandbar.codec.markdown/register!`
   the explicit-registration model is deliberate (side-effect-on-load is an
   anti-pattern); without this call, the codec mediator stays empty and
   entity.create with :format :markdown fails with \"No codec registered for
   format :markdown\".  Surfaced 2026-05-22 during MCP cutover work — the
   verb-catalog advertised codec-mediated authoring but the codec was never
   registered at startup.

   Needs no database and no port: runs BEFORE the component system opens
   the HTTP port so the first request can never see an empty mediator."
  []
  (try
    (codec-md/register!)
    (catch Exception e
      (log/warn e :SYS/CODEC-MARKDOWN-REGISTER-FAILED
                "Markdown codec registration failed; entity.create with :format :markdown will reject"))))

(defn- start-reactive-projection!
  "Start the projection worker and register the reactive callback plus
   filesystem and SSE sinks before the HTTP component accepts requests.
   Registration needs no database connection; sinks consult the database at
   drain time. This order avoids accepting writes before their sinks exist."
  []
  (try
    (reactive-queue/start!)
    (reactive/register-callback! reactive-queue/enqueue-projection!)
    (reactive-sinks/register-all!)
    (log/info :SYS/REACTIVE-PROJECTION-STARTED
              {:callbacks   (reactive/callback-count)
               :sinks       (reactive-queue/sink-count)
               :buffer-size reactive-queue/+default-buffer-size+
               :corpus-root (reactive-sinks/corpus-root)})
    (catch Exception e
      (log/warn e :SYS/REACTIVE-PROJECTION-STARTUP-FAILED
                "Reactive-projection worker failed to start; dt/* mutations will skip the hook"))))

(defn verb-catalog-readiness
  "How the persisted `:mm/Verb` catalog compares, BY COUNT ONLY, with the
   source catalog `sandbar.mcp.tools/verb-catalog`. Read-only; query failures
   return :error. The zero-argument database lookup may throw; `start`
   supplies the outer startup failure boundary.

   `sandbar.tools.search` and `sandbar.tools.describe` answer from the
   persisted cards, while `tools/list` and the `initialize` instructions come
   from source — so a store that was never seeded advertises the discovery
   verbs and then answers no matches and a miss for every verb (the failed
   HTTP rehearsal 15294480403161084913, 2026-09-20).  Nothing on the start
   path seeds the catalog; `lein seed-verb-catalog` does, in a stopped-server
   window (doc/operations.md § Seed the verb catalog for a fresh store).

   Returns `{:status :empty | :count-mismatch | :present | :error
             :persisted <n or nil> :source <n> [:error <message>]}`.
   `:present` means the two counts agree and nothing more: name and card
   parity is not checked here, and an equal count is not freshness proof."
  ([] (verb-catalog-readiness (db/db)))
  ([database]
   (let [source (count tools/verb-catalog)]
     (try
       (let [persisted (count (d/q '[:find [?name ...] :where [_ :mm.verb/name ?name]]
                                   database))]
         {:status    (cond (zero? persisted)        :empty
                           (not= persisted source) :count-mismatch
                           :else                   :present)
          :persisted persisted
          :source    source})
       (catch Throwable t
         {:status :error :persisted nil :source source :error (ex-message t)})))))

(defn check-verb-catalog!
  "Startup step, after the database is up: log the persisted verb catalog's
   readiness. Never seeds or mutates. Returns the `verb-catalog-readiness`
   map; `start` catches failures from this step and continues.

   An empty catalog is logged as an actionable warning naming the effect
   (discovery blind while the catalog is advertised) and the remedy (seed in a
   stopped-server window, then start).  A count mismatch warns likewise: the
   discovery verbs answer from cards that may be stale.  A matching count is
   logged as information and explicitly as a count, not as parity.  A read
   failure warns and continues."
  []
  (let [{:keys [status persisted source error] :as readiness} (verb-catalog-readiness)
        remedy "stop the server, run `lein seed-verb-catalog` from this checkout with the same configuration, start again — doc/operations.md § Seed the verb catalog for a fresh store"]
    (case status
      :empty
      (log/warn :SYS/VERB-CATALOG-EMPTY
                {:persisted persisted :source source
                 :effect "sandbar.tools.search answers no matches and sandbar.tools.describe answers a miss for every verb, while tools/list and the initialize instructions still advertise the source catalog"
                 :remedy remedy})
      :count-mismatch
      (log/warn :SYS/VERB-CATALOG-COUNT-MISMATCH
                {:persisted persisted :source source
                 :effect "the discovery verbs answer from persisted cards that may be stale relative to this checkout's source catalog"
                 :remedy remedy})
      :present
      (log/info :SYS/VERB-CATALOG-PRESENT
                {:persisted persisted :source source
                 :note "count only — name and card parity is not checked at startup"})
      :error
      (log/warn :SYS/VERB-CATALOG-CHECK-FAILED
                {:error error :source source
                 :effect "the persisted verb catalog could not be read; the server continues"
                 :remedy "check the schema load in this log, then sandbar.tools.describe of one known verb through an authenticated client"}))
    readiness))

(defn- warm-bm25f-caches!
  "Warm startup BM25F caches for eligible classes after database startup.
   Runtime-event classes are excluded by `bm25f-warmable-class?`. Each class
   has its own error boundary, so one failure does not stop the sweep.
   Requests arriving before a class is warmed build its cache on demand."
  []
  (try
    (let [searchable (filter bm25f-warmable-class? (dt/all-classes))]
      (doseq [class searchable]
        (try
          (let [{:keys [count ms]} (search/warm-bm25f-cache! class)]
            (log/info :SYS/BM25F-CACHE-WARMED
                      {:class class :count count :ms ms}))
          (catch Exception e
            (log/warn e :SYS/BM25F-CACHE-WARM-FAILED {:class class})))))
    (catch Exception e
      (log/warn e :SYS/BM25F-CACHE-WARM-SWEEP-FAILED
                "BM25F cache cold-warm sweep failed; queries will lazy-build on first access"))))

(defn start []
  ;; FOUNDATION FIRST — initialize Telemere handlers BEFORE any (log/info ...)
  ;; callsite has a chance to fire silently into a no-handler void.  Per
  ;; Dan-directive 2026-05-23 + memory/interaction/verify_foundational_subsystem_health_after_substrate_edits_2026_05_23.md
  ;; — substrate restart leaving handlers unconfigured was the failure mode
  ;; that motivated the logging-foundation-first authorization.
  (logging-init/start!)
  ;; BEFORE THE PORT OPENS (sprint 2.2): codecs + the projection pipeline.
  ;; Both are database-free; registering them first closes the post-boot
  ;; window in which writes were accepted but never projected.
  (register-codecs!)
  (start-reactive-projection!)
  (log/info :SYS/START "Starting system components")
  (alter-var-root #'sys/system component/start)
  (log/info :SYS/STARTED "System started successfully")
  ;; AFTER the database is up: the persisted verb catalog's readiness — a
  ;; read and a log line, never a seed (an unseeded store leaves the
  ;; discovery verbs blind while tools/list advertises them; 2026-09-20).
  (try
    (check-verb-catalog!)
    (catch Exception e
      (log/warn e :SYS/VERB-CATALOG-CHECK-FAILED
                "Verb catalog readiness check failed; the server continues")))
  ;; Then the search warm sweep (needs dt/all-classes).
  (warm-bm25f-caches!)
  ;; γ.3 — autostart the scheduler if config opts in (:scheduler {:enabled? true}).
  ;; Default is :enabled? false (per Q.γ.5 opt-in safety) so this is a no-op
  ;; in the standard dev workflow until a project explicitly turns it on via
  ;; .sandbar/config.edn override.  When enabled, allocates the handler-pool +
  ;; spawns the fire-thread + registers the :mm.event/Scheduled subscriber.
  ;; Schedule auto-loading from :jobs is handled separately in γ.5+ (system
  ;; DB-stats + reactive-queue-health jobs).
  (try
    (start-scheduler-if-enabled!)
    (catch Exception e
      (log/warn e :SYS/SCHEDULER-STARTUP-FAILED
                "Scheduler autostart failed; sandbar.schedule/start! can be called manually"))))

(defn- start-scheduler-if-enabled!
  "Read `:scheduler` config; if `:enabled?` is true, enable + start the
   scheduler via sandbar.schedule/enable! + start!.  Returns the
   outcome keyword (`:scheduler-started` | `:scheduler-disabled-by-config`).

   Extracted from `start` for testability — tests can bind a fake
   `:config` system value + call this fn directly."
  []
  (let [config           (get-in sys/system [:config])
        scheduler-config (get config :scheduler {})
        enabled?         (boolean (:enabled? scheduler-config))
        seed-system?     (boolean (:system-jobs? scheduler-config))]
    (if enabled?
      (do (sched/enable!)
          (sched/start!)
          ;; γ.5b — seed + schedule the two system jobs at boot when
          ;; :scheduler/system-jobs? is true.  Per Dan-directive
          ;; 2026-05-28 (amends Q.γ.5 opt-in-safety for the system-jobs
          ;; case).  seed-system-jobs! upserts the 2 Fn + 2 Job + 2
          ;; Schedule entities idempotently (stable :db/ident); the
          ;; returned schedule eids are added to the live queue.
          (when seed-system?
            (try
              (let [schedule-eids (sched-sys/seed-system-jobs!)]
                (doseq [eid schedule-eids]
                  (sched/add-schedule! eid))
                (log/info :SYS/SCHEDULER-SYSTEM-JOBS-SEEDED
                          {:count (count schedule-eids)
                           :schedule-eids schedule-eids}))
              (catch Exception e
                (log/warn e :SYS/SCHEDULER-SYSTEM-SEED-FAILED
                          "System-job seeding failed; scheduler still running, system jobs not scheduled"))))
          (log/info :SYS/SCHEDULER-STARTED
                    {:enabled? true
                     :system-jobs? seed-system?
                     :jobs     (count (get scheduler-config :jobs []))})
          :scheduler-started)
      (do (log/info :SYS/SCHEDULER-DISABLED-BY-CONFIG)
          :scheduler-disabled-by-config))))

(defn stop []
  (log/info :SYS/STOP "Stopping system components")
  ;; γ.3 — drain + stop the scheduler BEFORE component/stop tears down
  ;; sandbar.db.datomic (the scheduler's :handler-pool jobs may still
  ;; write to Datomic during drain).  Idempotent — no-op when scheduler
  ;; is not running.
  (try
    (sched/stop! {:drain-timeout-ms 5000})
    (catch Exception e
      (log/warn e :SYS/SCHEDULER-STOP-FAILED
                "Scheduler stop failed; proceeding with component shutdown")))
  (alter-var-root #'sys/system (fn [s] (when s (component/stop s))))
  (log/info :SYS/STOPPED "System stopped"))

(defn go []
  (init)
  (start))

(defn -main []
  (go))
