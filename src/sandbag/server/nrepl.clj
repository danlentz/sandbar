(ns sandbag.server.nrepl
  (:require [nrepl.server :as nrepl-server]
            [clojure.tools.logging      :as log]
            [com.stuartsierra.component :as component]
            [cider.nrepl :refer [cider-nrepl-handler]]))


(def +nrepl-handler+ cider-nrepl-handler)

(defrecord NRepl [port server]
  component/Lifecycle

  (start [self]
    (when server (component/stop self))
    (let [s (nrepl-server/start-server
             :port port
             :handler +nrepl-handler+)]
      (log/info "NREPL component started @" port)
      (assoc self :port port :server s)))

  (stop [self]
    (when server
      (nrepl-server/stop-server server)
      (log/info "NREPL component stopped")
      (assoc self :port nil :server nil))))

(defn make-nrepl-server [config]
  (map->NRepl {:port (-> config :nrepl :port)}))
