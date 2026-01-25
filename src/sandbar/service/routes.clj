(ns sandbar.service.routes
  (:require [clojure.java.io              :as io :refer [resource]]
            [clojure.tools.logging        :as log]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route       :as route]
            [sandbar.api.auth             :as auth-api]
            [sandbar.api.event            :as event]
            [sandbar.api.status           :as status]
            [sandbar.api.store            :as store]
            [sandbar.service.content      :as content]
            [sandbar.service.endpoint     :as endpoint :refer [defhandler]]
            [sandbar.service.params       :as params]
            [sandbar.util.auth            :as auth]
            [sandbar.util.event           :as event-util]
            [sandbar.util.http-status     :as http-status]))

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

      ;; Public auth endpoints (no authentication required)
      ["/login" ^:interceptors [content/data-body
                                content/accept-content
                                params/parsed-params
                                params/validated-params]
       {:post auth-api/login}]
      ["/register" ^:interceptors [content/data-body
                                   content/accept-content
                                   params/parsed-params
                                   params/validated-params]
       {:post auth-api/register}]
      ["/me" ^:interceptors [content/data-body
                             content/accept-content
                             params/parsed-params
                             params/validated-params
                             auth/authentication-interceptor]
       {:get auth-api/me}]

      ;; Protected API (authentication required)
      ["/api" ^:interceptors [event-util/log-request
                              content/data-body
                              content/log-response
                              content/accept-content
                              params/parsed-params
                              params/validated-params
                              params/log-params
                              auth/authentication-interceptor
                              auth/require-authentication]
       ["/status" {:get status/status-handler}]
       ["/auth"
        ["/logout" {:post auth-api/logout}]
        ["/password" {:post auth-api/change-password}]
        ["/sessions" {:get auth-api/list-sessions}
         ["/:id" {:delete auth-api/invalidate-session}]]]
       ["/events" {:get event/list-events :post event/create-event}
        ["/server" {:post event/create-server-event}]
        ["/user" {:post event/create-user-event}]
        ["/system" {:post event/create-system-event}]
        ["/http" {:post event/create-http-event}]
        ["/api" {:post event/create-api-event}]
        ["/transaction" {:post event/create-transaction-event}]
        ["/correlation/:uuid" {:get event/get-by-correlation}]
        ["/:id" {:get event/get-event}]]
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
