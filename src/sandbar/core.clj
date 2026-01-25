(ns sandbar.core
  (:gen-class)
  (:require [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
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
  (log/info :SYS/STARTED "System started successfully"))

(defn stop []
  (log/info :SYS/STOP "Stopping system components")
  (alter-var-root #'sys/system (fn [s] (when s (component/stop s))))
  (log/info :SYS/STOPPED "System stopped"))

(defn go []
  (init)
  (start))

(defn -main []
  (go))
