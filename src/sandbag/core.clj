(ns sandbag.core
  (:gen-class)
  (:require [com.stuartsierra.component :as component]
            [sandbag.server  :as server]
            [sandbag.util.nrepl :as nrepl]
            [sandbag.sys :as sys]
            [sandbag.util.edn   :as dedn]
            [sandbag.db    :as db]
            [sandbag.db.datatype :as dt]
                                        ;            [sandbag.db.datatype]
                                        ;            [sandbag.db.schema]
            [sandbag.util  :as util]))

(defn make-system
  ([]
   (make-system :config))
  ([configuraton-designator]
   (let [config (dedn/resource-value configuraton-designator nil)]
     (component/system-map
       :pedestal (server/make-pedestal-server :dev)
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
  (alter-var-root #'sys/system (fn [s]
                             (when s (component/stop s)))))

(defn go
  "Initializes and starts the current development system."
  []
  (init)
  (start))


(defn -main []
  (go))


(comment

  (stop)
  (go)


  )
