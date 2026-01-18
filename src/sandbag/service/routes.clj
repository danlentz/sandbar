(ns sandbag.service.routes
  (:require [clojure.java.io              :as io :refer [resource]]
            [clojure.tools.logging        :as log]
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
