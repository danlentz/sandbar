(ns sandbar.service.config
  (:require [io.pedestal.http              :as http]
            [io.pedestal.http.body-params  :as body-params]
            [sandbar.config                :as cfg]
            [sandbar.service.content       :as content]
            [sandbar.service.routes        :refer [routes]]))

(defn- inject-body-params
  "Insert sandbar's full-body-parser interceptor (`content/body-parsers`)
   immediately before the `router` interceptor in the service-map's
   `::http/interceptors` stack.  Per F#9 follow-on (memory/bugs/sandbar_arc_0_1_2_branch_test_regression_pre_tag_modeling_stage_7_2026_05_20.md):
   F#9 commit 032e22f removed the duplicate route-level body-params, but
   Pedestal's `default-interceptors` doesn't add body-params unless CSRF
   is enabled — the test code-path then had no body-parsing at all.
   The production connector-API path is handled symmetrically in
   `sandbar.server.pedestal/with-sandbar-interceptors`."
  [service-map]
  (let [bp        (body-params/body-params (content/body-parsers))
        intercpts (::http/interceptors service-map)
        ;; Insert body-params right before the router interceptor (always
        ;; near the end of the default stack).  The router is identified
        ;; by its name or by being the second-to-last entry (path-params-
        ;; decoder is typically last).
        router-pos (or (some (fn [[i ic]]
                               (let [n (some-> ic :name str)]
                                 (when (and n (clojure.string/includes? n "router"))
                                   i)))
                             (map-indexed vector intercpts))
                       (max 0 (dec (count intercpts))))
        [before after] (split-at router-pos intercpts)]
    (assoc service-map ::http/interceptors
           (vec (concat before [bp] after)))))

(def service
  (-> {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::http/interceptors []
              ::http/routes routes

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::http/allowed-origins ["scheme://host:port"]

              ;; Tune the Secure Headers
              ;; and specifically the Content Security Policy appropriate to your service/application
              ;; For more information, see: https://content-security-policy.com/
              ;;   See also: https://github.com/pedestal/pedestal/issues/499
              ;;::http/secure-headers {:content-security-policy-settings {:object-src "'none'"
              ;;                                                          :script-src "'unsafe-inline' 'unsafe-eval' 'strict-dynamic' https: http:"
              ;;                                                          :frame-ancestors "'none'"}}

              ::http/resource-path "/public"
              ::http/type :jetty
              ;;::http/host "localhost"
              ;; Port is resolved via the 3-layer config loader per
              ;; decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md
              ;; D.E — bundled default 8389 (non-conflicting; off 8080
              ;; universal-dev-default); client-project override via
              ;; <SANDBAR_CLIENT_DIR>/.sandbar/config.edn :port; env-var
              ;; override via SANDBAR_PORT.  Hardcoded fallback 8389
              ;; matches the bundled default in case config.edn is
              ;; missing (defensive).
              ::http/port (or (cfg/value :port) 8389)
              ::http/container-options {:h2c? true
                                        :h2? false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false
                                        ;; Alternatively, You can specify your own Jetty HTTPConfiguration
                                        ;; via the `:io.pedestal.http.jetty/http-configuration` container option.
                                        ;:io.pedestal.http.jetty/http-configuration (org.eclipse.jetty.server.HttpConfiguration.)
                                        }}
      ;; Populate ::http/interceptors with Pedestal defaults, then inject our
      ;; full body-parsers interceptor before the router.  Per F#9 follow-on.
      http/default-interceptors
      inject-body-params))
