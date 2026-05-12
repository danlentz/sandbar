(ns sandbar.service.routes
  (:require [clojure.java.io              :as io :refer [resource]]
            [clojure.tools.logging        :as log]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route       :as route]
            [sandbar.api.auth             :as auth-api]
            [sandbar.api.event            :as event]
            [sandbar.api.job              :as job-api]
            [sandbar.api.status           :as status]
            [sandbar.api.store            :as store]
            [sandbar.api.workflow         :as workflow-api]
            [sandbar.mcp.auth             :as mcp-auth]
            [sandbar.mcp.transport        :as mcp-transport]
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
          ["/parents" {:get store/list-parents}]
          ["/validate" {:get store/validate-instances}]]]
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

       ;; Jobs API
       ["/jobs" {:get job-api/list-jobs :post job-api/create-scheduled-job}
        ["/stats" {:get job-api/job-stats}]
        ["/due" {:get job-api/due-jobs}]
        ["/running" {:get job-api/running-jobs}]
        ["/triggered" {:post job-api/create-triggered-job}]
        ["/recurring" {:post job-api/create-recurring-job}]
        ["/:id" {:get job-api/get-job}
         ["/cancel" {:post job-api/cancel-job}]
         ["/pause" {:post job-api/pause-job}]
         ["/resume" {:post job-api/resume-job}]
         ["/execute" {:post job-api/execute-job}]]]

       ;; Workflows API
       ["/workflows" {:get workflow-api/list-workflows :post workflow-api/create-workflow}
        ["/:ns/:name" {:get workflow-api/get-workflow}
         ["/stats" {:get workflow-api/workflow-stats}]]]

       ;; Processes API
       ["/processes" {:get workflow-api/list-processes :post workflow-api/start-process}
        ["/:id" {:get workflow-api/get-process}
         ["/transitions" {:get workflow-api/get-available-transitions}]
         ["/transition" {:post workflow-api/execute-transition}]
         ["/history" {:get workflow-api/get-process-history}]]]
       ]

      ;; MCP (Model Context Protocol) endpoint — per
      ;; decisions/sandbar_mcp_server_design_2026_05_12.md B.1.1 + B.1.2
      ;; Streamable HTTP transport at /mcp; JSON-RPC 2.0 envelope.
      ;; Bearer-token auth (Stage C.2) — mcp-auth/bearer-interceptor extracts
      ;; Authorization: Bearer <token> + delegates to sandbar.util.auth/authenticate-api-key;
      ;; mcp-auth/require-bearer terminates with 401 + WWW-Authenticate: Bearer if
      ;; no :identity attached.
      ["/mcp" ^:interceptors [event-util/log-request
                              content/data-body
                              content/log-response
                              content/accept-content
                              params/parsed-params
                              mcp-auth/bearer-interceptor
                              mcp-auth/require-bearer]
       {:post mcp-transport/mcp-handler}
       ;; SSE channel for server → client notifications (Stage C.3)
       ;; per ADR B.1.1 + B.1.4 + B.1.5. Subscribers managed by
       ;; sandbar.mcp.notifications.
       ["/sse" {:get mcp-transport/sse-handler}]]

      ]]])
