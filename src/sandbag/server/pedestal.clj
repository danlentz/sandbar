(ns sandbag.server.pedestal
  (:require [clojure.tools.logging      :as log]
            [com.stuartsierra.component :as component]
            [io.pedestal.http :as server]
            [io.pedestal.http.route :as route]
            [sandbag.service.routes :as routes]
            [sandbag.service.config :as service]            ))

(defonce prod-service (server/create-server service/service))

(defonce dev-service (-> service/service ;; start with production configuration
                         (merge {:env :dev
                                 ;; do not block thread that starts web server
                                 ::server/join? false
                                 ;; Routes can be a function that resolve routes,
                                 ;;  we can use this to set the routes to be reloadable
                                 ::server/routes #(route/expand-routes (deref #'routes/routes))
                                 ;; all origins are allowed in dev mode
                                 ::server/allowed-origins {:creds true :allowed-origins (constantly true)}
                                 ;; Content Security Policy (CSP) is mostly turned off in dev mode
                                 ::server/secure-headers {:content-security-policy-settings {:object-src "'none'"}}})
                         ;; Wire up interceptor chains
                        ; server/default-interceptors
                        ; server/dev-interceptors
                         server/create-server))

(defn run-dev [& args]
  (println "\nCreating your [DEV] server...")
  (server/start dev-service))

(defn run [& args]
  (println "\nCreating your server...")
  (server/start prod-service))

(defrecord Pedestal [service server]
  component/Lifecycle
  (start [self]
    (when server (component/stop self))
    (let [s (server/start service)]
      (log/info "Pedestal started.")
      (assoc self :server s)))

  (stop [self]
    (when server
      (server/stop server)
      (log/info "Pedestal stopped")
      (assoc self :server nil))))

(defn make-pedestal-server [mode]
  (let [service (if (= mode :prod) prod-service dev-service)]
    (map->Pedestal {:service service :server nil})))
