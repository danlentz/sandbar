(ns sandbar.server.pedestal
  (:require [clojure.tools.logging              :as log]
            [com.stuartsierra.component         :as component]
            [io.pedestal.connector              :as conn]
            [io.pedestal.http                   :as http]
            [io.pedestal.http.body-params       :as body-params]
            [io.pedestal.http.jetty             :as jetty]
            [io.pedestal.http.ring-middlewares  :as ring-middlewares]
            [io.pedestal.http.route             :as route]
            [io.pedestal.http.secure-headers    :as secure-headers]
            [io.pedestal.http.tracing           :as tracing]
            [io.pedestal.service.interceptors   :as interceptors]
            [sandbar.service.content            :as content]
            [sandbar.service.routes             :as routes]
            [sandbar.service.config             :as service]))

(defn- validate-destinations! []
  (when (seq ((requiring-resolve 'sandbar.project.destination/configured-roots)))
    ((requiring-resolve 'sandbar.project.destination/validate-roots!)
     ((requiring-resolve 'sandbar.db.datomic/db))
     ((requiring-resolve 'sandbar.project.destination/global-root)))))

(defn- with-sandbar-interceptors
  "Install the Pedestal interceptor stack with Sandbar's body-parser map
   at the body-parsing position. JSON, EDN, CSV, and form-encoded bodies can
   then reach handlers through the appropriate parsed-params key. This mirrors
   the Pedestal 0.8.1 connector stack with the custom parser substitution;
   adding a second route-level body parser would duplicate that work."
  [connector-map]
  (conn/with-interceptors connector-map
                          [(tracing/request-tracing-interceptor)
                           interceptors/log-request
                           interceptors/not-found
                           (ring-middlewares/content-type {})
                           route/query-params
                           (body-params/body-params (content/body-parsers))
                           (secure-headers/secure-headers)]))

(defn- create-connector-map
  "Create a connector map from the service config, defaulting to port 8389."
  [service-config]
  (let [port (::http/port service-config 8389)]
    (-> (conn/default-connector-map port)
        (with-sandbar-interceptors)
        (conn/with-routes routes/routes))))

(defonce prod-connector
  (-> (create-connector-map service/service)
      (jetty/create-connector nil)))

(defonce dev-connector
  (-> (create-connector-map (merge service/service {:env :dev}))
      (jetty/create-connector nil)))

(defn run-dev [& args]
  (println "\nCreating your [DEV] server...")
  (validate-destinations!)
  (conn/start! dev-connector))

(defn run [& args]
  (println "\nCreating your server...")
  (validate-destinations!)
  (conn/start! prod-connector))

(defrecord Pedestal [connector server]
  component/Lifecycle
  (start [self]
    (when server (component/stop self))
    (validate-destinations!)
    (let [s (conn/start! connector)]
      (log/info "Pedestal started.")
      (assoc self :server s)))

  (stop [self]
    (when server
      (conn/stop! server)
      (log/info "Pedestal stopped")
      (assoc self :server nil))))

(defn make-pedestal-server
  "The HTTP component.  `(make-pedestal-server mode)` wraps the process-wide
   `defonce` connector for `mode` (`:prod` / `:dev`) on the configured port.
   `(make-pedestal-server mode port)` builds a FRESH connector on `port` over
   the same connector map (the full sandbar interceptor stack and
   `service.routes/routes`), so the production system graph can be booted on
   a free port — what `sandbar.core-boot-order-acceptance-test` does."
  ([mode]
   (let [connector (if (= mode :prod) prod-connector dev-connector)]
     (map->Pedestal {:connector connector :server nil})))
  ([mode port]
   (let [connector (-> (create-connector-map (merge service/service
                                                    (when (= mode :dev) {:env :dev})
                                                    {::http/port port}))
                       (jetty/create-connector nil))]
     (map->Pedestal {:connector connector :server nil}))))
