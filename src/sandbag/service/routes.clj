(ns sandbag.service.routes
  (:require [clojure.java.io              :as io :refer [resource]]
            [clojure.tools.logging        :as log]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route       :as route]
            [sandbag.api.status           :as status]
            [sandbag.api.store            :as store]
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

(def routes
  `[[["/" {:get home-page} ^:interceptors [(body-params/body-params (content/body-parsers))
                                           params/url-decode-path-params]
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
       ["/store"
        ["/schema" {:get store/schema-overview}]
        ["/classes" {:get store/list-classes}
         ["/:ns/:name" {:get store/get-class}
          ["/instances" {:get store/list-instances}
           ["/direct" {:get store/list-direct-instances}]]
          ["/slots" {:get store/list-slots}
           ["/direct" {:get store/list-direct-slots}]
           ["/required" {:get store/list-required-slots}]]
          ["/hierarchy" {:get store/class-hierarchy}]
          ["/subclasses" {:get store/list-subclasses}
           ["/direct" {:get store/list-direct-subclasses}]]
          ["/ancestors" {:get store/list-ancestors}]
          ["/parents" {:get store/list-parents}]]]
        ["/properties" {:get store/list-properties}
         ["/:ns/:name" {:get store/get-property}
          ["/domain" {:get store/property-domain}]
          ["/range" {:get store/property-range}]]]
        ["/entities/:ns/:name" {:get store/get-entity}
         ["/validate" {:get store/validate-entity}]
         ["/class" {:get store/entity-class}]]
        ["/types"
         ["/instance-of/:class-ns/:class-name/:entity-ns/:entity-name" {:get store/check-instance-of}]
         ["/subclass-of/:parent-ns/:parent-name/:child-ns/:child-name" {:get store/check-subclass-of}]]]
       ]

      ]]])
