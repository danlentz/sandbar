(ns sandbar.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.logging.init :as logging-init]
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
  "Construct the (unstarted) component system map.

   The default `:config` designator resolves through the LAYERED config
   loader (`edn/config-value` → `sandbar.config/config`): bundled
   defaults — including the committed `config-example.edn`
   fresh-checkout fallback — deep-merged with the client
   `.sandbar/config.edn` override and env-var overrides.

   Fresh-checkout portability (2026-07-22, closes the make-system
   residual of the 2026-07-21 config-example fallback): this path
   previously read the raw `config.edn` classpath resource directly via
   `edn/resource-value`, which (a) threw `Cannot open <nil> as a
   Reader` on a fresh checkout — `config/config.edn` is gitignored, so
   `io/resource` resolves nil — BYPASSING the
   `sandbar.config/read-bundled-defaults` fallback, and (b) ignored the
   client-override + env layers that the sibling boot-path config
   consumer `db/db-spec` already honors (per the deployment-strategy
   ADR D.B, `memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md`).

   A NON-`:config` designator is still read as a raw classpath EDN
   resource via `edn/resource-value` — an extension seam for booting
   from an alternate bundled config; no production caller uses it.

   Overrides (2026-09-19, reliability sprint D1): a MAP argument builds the
   SAME graph, same component types and same dependency declarations, on
   test resources — `{:config <map> :db-spec <spec> :port <port>
   :nrepl? <bool>}`, each optional.  `sandbar.core-boot-order-acceptance-test`
   boots this graph on a free port and an in-memory store, fresh and
   pre-initialized, through the unmodified `start`.

   Dependency: `:pedestal` USES `:datomic`, so the HTTP port opens only after
   the database component has run `initialize-db!` and set `db/**conn*`.
   `component/system-map` is insertion-ordered and `start-system` keeps that
   order for components without a declared dependency, so before this
   declaration `:pedestal` started BEFORE `:datomic`: the port answered
   before schema was loaded and before the connection was set.  On an
   existing store the connection fallback hid it; on a fresh store the first
   requests failed.  The projection pipeline needs no such dependency — it
   is registered in `start` before `component/start` runs at all."
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
  "True if `class-ident` should be cold-warmed in the startup BM25F sweep:
   it declares effective bm25f-weights AND is NOT a runtime-event class.

   The `:dt/Event` subtree (`:event/SystemEvent`, `:event/ServerEvent`,
   `:event/UserEvent`, …) carries bm25f-weights but is operational
   telemetry — NOT corpus-retrieval content.  The `:db-only` system jobs
   emit `:event/SystemEvent` (~240/day), so warming that subtree bloats
   startup unboundedly for zero corpus-search value.  Per Dan-directive
   2026-05-28.

   NB: the `:mm.event/*` memory-model events are a DIFFERENT hierarchy
   (`:mm.event/* → :mm/Event → :mm/Meta → :mm/Memory`) — genuine corpus
   members — and remain warmable.  BM25F is a corpus-retrieval tool;
   `:dt/Event` runtime events have no business in the startup warm."
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
  "Start the projection worker, register `enqueue-projection!` as the
   reactive callback, and register the fs + SSE sinks.

   Per decisions/reactive_projection_queue_bounded_buffer_and_health_observability_2026_05_23.md
   (eid 17592186094353) + plans/sse_reactive_corpus_projection_arc_2026_05_23.md
   (eid 17592186094359); sinks per that plan's Stage B (closes gap #2, the
   one-way :format :markdown ingest, by making DB→FS projection live).

   ORDER MATTERS (2026-09-18, reliability sprint item 2.2): this runs
   BEFORE the component system opens the HTTP port.  It used to run AFTER
   the port opened AND after the BM25F warm sweep, so for the ~50 s the
   warm took on the live corpus the server accepted writes that reached
   the database and were never projected to files (observed on the
   2026-09-18 restart: `reactive_health` reported 0 sinks three minutes
   after boot).  Under the filesystem-canonical ruling that window was
   data loss.  None of this needs the database: the worker is a thread,
   the callback and sinks are fns; the fs sink consults the database only
   at drain time, and nothing can be enqueued before the database
   component exists because every enqueue comes from a dt/* mutation."
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

(defn- warm-bm25f-caches!
  "Stage 5 D5 — cold-warm the BM25F search cache for every BM25F-searchable
   class.  First-query post-restart drops from cold-tokenize (~5.7s at
   1500-entity scale) to <100ms.  Searchable = class has :dt/bm25f-weights
   declared.  Per-class warm is independent; failure on one class doesn't
   block others (per-class try/catch).  Needs the database, so it runs
   after the component system starts; queries arriving during the sweep
   lazy-build the class they need (slower, never wrong)."
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
  ;; AFTER the database is up: the search warm sweep (needs dt/all-classes).
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
