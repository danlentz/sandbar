(ns sandbar.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
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
  ;; Stage 5 D5 — cold-warm the BM25F search cache for every BM25F-searchable
  ;; class.  First-query post-restart drops from cold-tokenize (~5.7s at
  ;; 1500-entity scale) to <100ms.  Searchable = class has :dt/bm25f-weights
  ;; declared.  Per-class warm is independent; failure on one class doesn't
  ;; block others (per-class try/catch).
  (try
    (let [searchable (filter #(seq (dt/bm25f-weights-of %)) (dt/all-classes))]
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

(defn stop []
  (log/info :SYS/STOP "Stopping system components")
  (alter-var-root #'sys/system (fn [s] (when s (component/stop s))))
  (log/info :SYS/STOPPED "System stopped"))

(defn go []
  (init)
  (start))

(defn -main []
  (go))
