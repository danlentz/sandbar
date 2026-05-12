(ns sandbar.server.pedestal
  (:require [clojure.tools.logging      :as log]
            [com.stuartsierra.component :as component]
            [io.pedestal.connector :as conn]
            [io.pedestal.http :as http]
            [io.pedestal.http.jetty :as jetty]
            [sandbar.service.routes :as routes]
            [sandbar.service.config :as service]))

(defn- create-connector-map
  "Create a connector map from service config"
  [service-config]
  (let [port (::http/port service-config 8080)]
    (-> (conn/default-connector-map port)
        (conn/with-default-interceptors)
        (conn/with-routes routes/routes))))

(defonce prod-connector
  (-> (create-connector-map service/service)
      (jetty/create-connector nil)))

(defonce dev-connector
  (-> (create-connector-map (merge service/service {:env :dev}))
      (jetty/create-connector nil)))

(defn run-dev [& args]
  (println "\nCreating your [DEV] server...")
  (conn/start! dev-connector))

(defn run [& args]
  (println "\nCreating your server...")
  (conn/start! prod-connector))

(defrecord Pedestal [connector server]
  component/Lifecycle
  (start [self]
    (when server (component/stop self))
    (let [s (conn/start! connector)]
      (log/info "Pedestal started.")
      (assoc self :server s)))

  (stop [self]
    (when server
      (conn/stop! server)
      (log/info "Pedestal stopped")
      (assoc self :server nil))))

(defn make-pedestal-server [mode]
  (let [connector (if (= mode :prod) prod-connector dev-connector)]
    (map->Pedestal {:connector connector :server nil})))
