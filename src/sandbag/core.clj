(ns sandbag.core
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [sandbag.db.datatype :as dt]
            [sandbag.db.datomic    :as db]
            [sandbag.server.nrepl :as nrepl]
            [sandbag.server.pedestal  :as pedestal]
            [sandbag.sys :as sys]
            [sandbag.util.common  :as util]
            [sandbag.util.edn   :as edn]))

(defn make-system
  ([]
   (make-system :config))
  ([configuraton-designator]
   (let [config (edn/resource-value configuraton-designator nil)]
     (component/system-map
       :config   config
       :pedestal (pedestal/make-pedestal-server :dev)
       :datomic  (db/make-datomic-peer (db/db-spec))
       :nrepl    (nrepl/make-nrepl-server config)
       ))))


(defn init
  "Constructs the current development system."
  []
  (alter-var-root #'sys/system (constantly (make-system))))

(defn start
  "Starts the current development system."
  []
  (alter-var-root #'sys/system component/start))

(defn stop
  "Shuts down and destroys the current development system."
  []
  (alter-var-root #'sys/system (fn [s] (when s (component/stop s)))))

(defn go
  "Initializes and starts the current development system."
  []
  (init)
  (start))


(defn -main []
  (go))
