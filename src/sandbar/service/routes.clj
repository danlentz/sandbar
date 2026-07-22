(ns sandbar.service.routes
  (:require [clojure.java.io              :as io :refer [resource]]
            [clojure.tools.logging        :as log]
            [io.pedestal.http.body-params :as body-params]
            [io.pedestal.http.route       :as route]
            [sandbar.api.aggregate        :as aggregate-api]
            [sandbar.api.auth             :as auth-api]
            [sandbar.api.event            :as event]
            [sandbar.api.job              :as job-api]
            [sandbar.api.navigate         :as navigate-api]
            [sandbar.api.orient           :as orient-api]
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
  ;; NOTE: body-params is ALREADY added by `conn/with-default-interceptors`
  ;; in sandbar.server.pedestal/create-connector-map.  Adding it again at the
  ;; route level would cause it to run TWICE — the first run consumes the
  ;; body stream + populates :json-params; the second run reads from the now-
  ;; empty stream + OVERWRITES :json-params with nil.  This was Friction
  ;; Item #9 of the 0.1.1 co-evolution arc, surfaced 2026-05-20.
  ;;
  ;; If/when sandbar wants its CSV / EDN-with-tagged-literal extensions, the
  ;; right move is to replace `with-default-interceptors` with a custom
  ;; interceptor stack that uses `(body-params/body-params (content/body-parsers))`
  ;; instead of the default — NOT to layer a second body-params on top.
  `[[["/" {:get home-page} ^:interceptors [params/url-decode-path-params]
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
                              auth/require-authentication
                              endpoint/entity-ref-error-interceptor]
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

       ;; Aggregation API (Stage 15 — fulltext arc Phase G)
       ;; Per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.
       ;; Thin HTTP wrappers around sandbar.aggregate's three public verbs.
       ["/aggregate"
        ["/count"    {:get aggregate-api/count}]
        ["/group-by" {:get aggregate-api/group-by}]
        ["/rank-by"  {:get aggregate-api/rank-by}]]

       ;; Navigation API (Stage P-6 + Stage 22 — fulltext arc Phase N / Stage P)
       ;; Path-grammar walker + same-directory peers.
       ["/navigate"
        ["/path"     {:get navigate-api/path-via}]
        ["/siblings" {:get navigate-api/siblings-of}]]

       ;; Orientation API (Phase O — fulltext arc; library-card only)
       ;; Per decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md.
       ["/orient"
        ["/library-card" {:get orient-api/library-card}]]
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
                              params/validated-params
                              mcp-auth/bearer-interceptor
                              mcp-auth/require-bearer
                              ;; AFTER require-bearer: only AUTHENTICATED
                              ;; callers may suppress their own event row
                              ;; (401 probes stay logged).  Serves the
                              ;; PreToolUse recall hook's per-call header
                              ;; (~7k rows/day stanched) per the 2026-07-21
                              ;; reduce-substantially events ruling.
                              event-util/honor-suppress-event-logging-header]
       {:post mcp-transport/mcp-handler}
       ;; SSE channel for server → client notifications (Stage C.3)
       ;; per ADR B.1.1 + B.1.4 + B.1.5. Subscribers managed by
       ;; sandbar.mcp.notifications.
       ["/sse" {:get mcp-transport/sse-handler}]]

      ]]])
