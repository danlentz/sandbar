(ns sandbar.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.reactive :as reactive]
            [sandbar.reactive.queue :as reactive-queue]
            [sandbar.reactive.sinks :as reactive-sinks]
            [sandbar.search :as search]
            [sandbar.server.nrepl :as nrepl]
            [sandbar.server.pedestal :as pedestal]
            [sandbar.sys :as sys]
            [sandbar.util.common :as util]
            [sandbar.util.edn :as edn]))

(defn make-system
  ([] (make-system :config))
  ([configuraton-designator]
   (let [config (edn/resource-value configuraton-designator nil)]
     (component/system-map
       :config   config
       :pedestal (pedestal/make-pedestal-server :dev)
       :datomic  (db/make-datomic-peer (db/db-spec))
       :nrepl    (nrepl/make-nrepl-server config)))))

(defn init []
  (log/info :SYS/INIT "Initializing system")
  (alter-var-root #'sys/system (constantly (make-system))))

(defn start []
  (log/info :SYS/START "Starting system components")
  (alter-var-root #'sys/system component/start)
  (log/info :SYS/STARTED "System started successfully")
  ;; Register codecs with the mediator so MCP entity.create + project.import/export
  ;; can codec-mediate via :format opt.  Per `sandbar.codec.markdown/register!`
  ;; the explicit-registration model is deliberate (side-effect-on-load is an
  ;; anti-pattern); without this call, the codec mediator stays empty and
  ;; entity.create with :format :markdown fails with "No codec registered for
  ;; format :markdown".  Surfaced 2026-05-22 during MCP cutover work — the
  ;; verb-catalog advertised codec-mediated authoring but the codec was never
  ;; registered at startup.
  (try
    (codec-md/register!)
    (catch Exception e
      (log/warn e :SYS/CODEC-MARKDOWN-REGISTER-FAILED
                "Markdown codec registration failed; entity.create with :format :markdown will reject")))
  ;; Stage 5 D5 — cold-warm the BM25F search cache for every BM25F-searchable
  ;; class.  First-query post-restart drops from cold-tokenize (~5.7s at
  ;; 1500-entity scale) to <100ms.  Searchable = class has :dt/bm25f-weights
  ;; declared.  Per-class warm is independent; failure on one class doesn't
  ;; block others (per-class try/catch).
  (try
    (let [searchable (filter #(seq (dt/effective-bm25f-weights-of %)) (dt/all-classes))]
      (doseq [class searchable]
        (try
          (let [{:keys [count ms]} (search/warm-bm25f-cache! class)]
            (log/info :SYS/BM25F-CACHE-WARMED
                      {:class class :count count :ms ms}))
          (catch Exception e
            (log/warn e :SYS/BM25F-CACHE-WARM-FAILED {:class class})))))
    (catch Exception e
      (log/warn e :SYS/BM25F-CACHE-WARM-SWEEP-FAILED
                "BM25F cache cold-warm sweep failed; queries will lazy-build on first access")))
  ;; Stage A.6 of SSE-reactive-projection arc: start the bounded queue
  ;; worker + register `enqueue-projection!` as the reactive callback.
  ;; Per decisions/reactive_projection_queue_bounded_buffer_and_health_observability_2026_05_23.md
  ;; (eid 17592186094353) + plans/sse_reactive_corpus_projection_arc_2026_05_23.md
  ;; (eid 17592186094359).  The worker drains the projection-task channel
  ;; + invokes registered sinks per drain; sinks default empty until
  ;; Stage B.1+ wires codec.emit / fs.write / SSE.emit.
  (try
    (reactive-queue/start!)
    (reactive/register-callback! reactive-queue/enqueue-projection!)
    ;; Stage B.1: register the codec.emit + fs.write + SSE.emit sinks.
    ;; Per plans/sse_reactive_corpus_projection_arc_2026_05_23.md Stage B.
    ;; Closes gap #2 (entity.create :format :markdown one-way ingest) by
    ;; making the forward DB→FS projection live.
    (reactive-sinks/register-all!)
    (log/info :SYS/REACTIVE-PROJECTION-STARTED
              {:callbacks   (reactive/callback-count)
               :sinks       (reactive-queue/sink-count)
               :buffer-size reactive-queue/+default-buffer-size+
               :corpus-root (reactive-sinks/corpus-root)})
    (catch Exception e
      (log/warn e :SYS/REACTIVE-PROJECTION-STARTUP-FAILED
                "Reactive-projection worker failed to start; dt/* mutations will skip the hook"))))

(defn stop []
  (log/info :SYS/STOP "Stopping system components")
  (alter-var-root #'sys/system (fn [s] (when s (component/stop s))))
  (log/info :SYS/STOPPED "System stopped"))

(defn go []
  (init)
  (start))

(defn -main []
  (go))
