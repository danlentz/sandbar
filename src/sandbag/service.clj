(ns sandbag.service
  (:require [clojure.java.io              :as io :refer [resource]]
            [clojure.tools.logging        :as log]
            [io.pedestal.http             :as http]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route       :as route]
            [sandbag.api.status           :as status]
            [sandbag.service.content      :as content]
            [sandbag.service.endpoint     :as endpoint :refer [defhandler]]
            [sandbag.service.params       :as params]
            [sandbag.util.http-status     :as http-status]))

(defn home-page [request]
  (endpoint/return (str "online: " (java.util.Date.))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; favicon.ico
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn favicon []
  (io/input-stream (io/resource "favicon.ico")))

(defhandler favicon-ico [_ _ _]
  (endpoint/return {"Content-Type" "image/png"} http-status/success (favicon)))

;; [(body-params/body-params (content/body-parsers)) params/url-decode-path-params]

(def routes
  `[[["/" {:get home-page} ^:interceptors [(body-params/body-params (content/body-parsers)) params/url-decode-path-params]
      ["/favicon.ico" {:get favicon-ico}]
;      ["/login" {:get identity}]
;      ["/logout"]
      ["/api" ^:interceptors [content/data-body
                              content/log-response
                              content/accept-content
                              params/parsed-params
                              params/validated-params
                              params/log-params]
       ["/status" {:get status/status-handler}]
       ]

      ]]])

;; See http/default-interceptors for additional options you can configure

(def service {:env :prod
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
              ::http/port 8080
              ::http/container-options {:h2c? true
                                        :h2? false
                                        ;:keystore "test/hp/keystore.jks"
                                        ;:key-password "password"
                                        ;:ssl-port 8443
                                        :ssl? false
                                        ;; Alternatively, You can specify your own Jetty HTTPConfiguration
                                        ;; via the `:io.pedestal.http.jetty/http-configuration` container option.
                                        ;:io.pedestal.http.jetty/http-configuration (org.eclipse.jetty.server.HttpConfiguration.)
                                        }})


(comment

  ;; [ring.handler.dump                         :refer [handle-dump]]

  )
