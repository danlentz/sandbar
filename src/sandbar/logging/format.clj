(ns sandbar.logging.format
  "Telemere signal formatting middleware.
   Remove the bridged MDC context footer and drop signals carrying the
   periodic Datomic MetricsReport payload, while retaining other Datomic
   signals. For SLF4J signals, use :location/:ns as the logger attribution
   instead of the bridge's macro-expansion location. Clear bridge coordinates
   and kind where appropriate. short-inst-fn supplies an ISO 8601 UTC
   timestamp with milliseconds. These are output choices, not persistence policy."
  (:import [java.time Instant ZoneId LocalDateTime]
           [java.time.format DateTimeFormatter]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Noise predicate — what to drop entirely
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn metrics-noise?
  "True when the signal is Datomic's per-second metrics-report flood.
   Narrow predicate — only matches signals carrying :MetricsReport in
   :data.  Other Datomic events pass through."
  [signal]
  (boolean (some-> signal :data :MetricsReport)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SLF4J source-attribution restore
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn restore-slf4j-source
  "For `:kind :slf4j` signals, rewrite `:ns` to the original logger
   name from `:location :ns` so the formatter displays `datomic.peer`
   instead of `taoensso.telemere.slf4j[115,3]`.  Also strips
   `:coords` (bridge-code line/col, never useful for bridge-routed
   signals) and `:kind` (avoid the per-line `SLF4J` token repetition).
   Returns the signal unchanged for non-:slf4j signals."
  [signal]
  (if (= :slf4j (:kind signal))
    (let [logger-ns (get-in signal [:location :ns])]
      (cond-> signal
        true       (dissoc :coords :kind)
        logger-ns  (assoc :ns logger-ns)))
    signal))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The middleware — composes drop-noise + strip-ctx + restore-source
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP response body truncation — middle ground between `#`-elision and full dump
;;
;; The `:HTTP/RESPONSE` callsite (`sandbar.service.content`) emits the
;; full response body as structured data.  An MCP `entity.find` returns
;; a 50KB+ memorial body; logging it verbatim drowns the line.  But
;; pre-truncating with `*print-level* 2` (the prior callsite policy)
;; reduced `:result {:resources #}` to a meaningless `#` mark.
;;
;; Middle: keep `:route` + `:status` intact; replace `:data :body` with
;; a length-bounded pr-str preview that shows enough structure to
;; identify the response shape without dumping the whole payload.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +http-body-max-chars+ 500)

(defn- truncate-string
  "Truncate `s` to at most `max-chars`; append `…(+N ch)` suffix
   indicating overflow size when truncation occurs."
  [^String s max-chars]
  (if (<= (count s) max-chars)
    s
    (str (subs s 0 max-chars) " …(+" (- (count s) max-chars) "ch)")))

(def ^:private +http-response-prefixes+
  "Message-prefix markers indicating an HTTP-response log line.  The
   tools.logging → SLF4J path stringifies `(log/info :HTTP/RESPONSE
   {...})` into a `:msg_` delay starting with these tokens."
  [":HTTP/RESPONSE" "HTTP/RESPONSE"])

(defn- http-response-msg? [^String s]
  (and (string? s)
       (some #(.startsWith s ^String %) +http-response-prefixes+)))

(defn truncate-http-response-body
  "For signals whose `:msg_` (or `:msg`) carries an `:HTTP/RESPONSE`
   stringified payload (the tools.logging → SLF4J path), truncate the
   message string to `+http-body-max-chars+` so 50KB MCP-response bodies
   don't drown the log.  Preserves `:route` + `:status` visibility (those
   appear at the start of the truncation window).

   No-op for non-HTTP-response signals."
  [signal]
  (let [m (or (when-let [d (:msg_ signal)] (force d))
              (:msg signal))]
    (if (http-response-msg? m)
      (let [truncated (truncate-string m +http-body-max-chars+)]
        (-> signal
            (assoc :msg  truncated)
            (assoc :msg_ (delay truncated))))
      signal)))

(defn middleware
  "Telemere `:xfn` middleware fn.

   - Returns `nil` for noise signals (drops them — Telemere convention)
   - Returns the signal with `:ctx` + `:host` stripped + (when
     `:kind :slf4j`) `:ns` rewritten to the original logger name from
     `:location :ns` + (when `:HTTP/RESPONSE`) `:data :body`
     length-truncated."
  [signal]
  (cond
    (not (map? signal)) signal
    (metrics-noise? signal) nil
    :else (-> signal
              ;; :ctx — drops the SLF4J MDC `{pid X}` footer
              ;; :host — drops the auto-injected local hostname (e.g. "kiwi")
              ;;         that Telemere stamps into every signal
              (dissoc :ctx :host)
              restore-slf4j-source
              truncate-http-response-body)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Short timestamp — HH:mm:ss.SSS (local; date implicit in file rotation)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private +iso8601-millis-fmt+
  "ISO 8601 instant with millisecond precision — `2026-05-23T22:20:16.711Z`.
   Drops the microsecond noise from Telemere's default (`...883135Z`)
   while keeping full date + UTC suffix per Dan-directive 2026-05-23."
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      (.withZone (ZoneId/of "UTC"))))

(defn short-inst-fn
  "Custom `:format-inst-fn` for `taoensso.telemere.utils/signal-preamble-fn`.
   Returns ISO 8601 with milliseconds — full date + UTC suffix preserved;
   microseconds dropped (the noise Dan flagged)."
  [inst]
  (cond
    (nil? inst) "????-??-??T??:??:??.???Z"
    (instance? Instant inst)
    (.format +iso8601-millis-fmt+ ^Instant inst)
    (instance? java.util.Date inst)
    (.format +iso8601-millis-fmt+ (.toInstant ^java.util.Date inst))
    :else (str inst)))
