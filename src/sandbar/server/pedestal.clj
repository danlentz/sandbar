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

(defn- with-sandbar-interceptors
  "Sandbar-customized version of `io.pedestal.connector/with-default-interceptors`
   that swaps the default body-params interceptor (JSON-only) for one configured
   with sandbar's full `content/body-parsers` map (JSON + EDN + CSV + form-encoded).

   Mirrors the upstream interceptor stack (per pedestal.service 0.8.1
   `io.pedestal.connector/with-default-interceptors`) verbatim with EDN-capable
   body-params substituted at the body-parsing position.  Required because the
   upstream default-interceptors uses `(body-params)` with no parser-map, which
   handles only JSON — EDN requests (e.g., auth API POSTs sending application/edn)
   reach handlers with `:edn-params nil`.

   Per F#9 follow-on resolution (memory/bugs/sandbar_arc_0_1_2_branch_test_regression_pre_tag_modeling_stage_7_2026_05_20.md):
   F#9 commit 032e22f correctly removed the duplicate route-level body-params,
   but the parallel substrate fix — replacing the default with our custom body-params
   — wasn't landed at the same time, leaving 36+5 failures on this branch."
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
  "Create a connector map from service config.  Fallback port 8389 per
   decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md
   D.E (non-conflicting; off the universal-dev-default 8080)."
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
