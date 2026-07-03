(ns sandbar.service.content
  (:require [cheshire.core :as json]
            [cheshire.parse :as parse]
            [clojure.data.codec.base64 :as b64]
            [clojure.string            :as str]
            [clojure.tools.logging     :as log]
            [io.pedestal.http.content-negotiation :as pcon]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [io.pedestal.http.body-params  :as body-params]
            [sandbar.service.endpoint :as endpoint :refer [defbefore defafter]]
            [sandbar.util.common      :as util]
            [sandbar.util.codec       :as codec]
            [ring.util.response        :as ring-response])
  (:import [java.io EOFException InputStream InputStreamReader]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONTENT TYPE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def +default-content-type+ "application/edn")

(defn- clj->event-stream
  "Encode `body` as a single Server-Sent-Events frame onto `output-stream`.

   Matches the neighbor stream-encoder contract ([body output-stream]) so
   `data-body`'s `(with-handle-encoding-errors encoder)` wrapper can invoke it
   the same way it invokes `codec/clj->json-stream` et al.  Replaces the former
   zero-arity throw-placeholder that crashed with an ArityException when content
   negotiation selected \"text/event-stream\" on POST /mcp (Codex-diagnosed,
   Dan-relayed 2026-07-03 — see
   memory/bugs/mcp_post_sse_first_accept_arity_crash_content_types_placeholder_encoder_2026_07_03.md).

   Per MCP streamable-http semantics + the SSE spec, the JSON-RPC response is
   carried as the frame's `data:` field.  JSON (not EDN — cf. the F-M-004
   pr-str regression) via the same cheshire encoding the \"application/json\"
   entry uses, for a consistent wire body.  Compact single-line JSON keeps the
   frame to one `data:` line; the trailing blank line terminates the event.

   NB: consolidation into `sandbar.util.codec` (where `clj->sse-stream` already
   lives) is deferred — that file is under concurrent edit by the
   validation-hardening fleet; this local encoder is the narrow fix and folds
   into util.codec at ceremony cleanup."
  [body output-stream]
  (with-open [writer (codec/buffered-writer output-stream)]
    (.write writer "event: message\n")
    (.write writer "data: ")
    (.write writer ^String (json/generate-string body))
    (.write writer "\n\n")))

(def +content-types+
  {"application/json"          codec/clj->json-stream
   "application-json"          codec/clj->json-stream
   "application/edn"           codec/clj->edn-stream
   "application/transit+json"  codec/clj->transit-json-stream
   "text/event-stream"         clj->event-stream
   "text/csv"                  codec/clj->csv-stream
   "text/plain"                codec/clj->text-stream})

(def ^:private fix-content-type
  {"application-json" "application/json"})

(def request-accept-headers
  [[:request :headers "accept"]
   [:request :headers "Accept"]
   [:request :headers :accept]
   [:request :headers :Accept]])

(def accept-content
  (let [reqd (fn [ctx] (some #(get-in ctx %) request-accept-headers))
        fail (fn [ctx] (assoc ctx :response (endpoint/not-acceptable (reqd ctx))))]
    (pcon/negotiate-content (keys +content-types+) {:no-match-fn (comp terminate fail)
                                                    :content-param-paths request-accept-headers})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DECODING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn csv-parser [opts]
  (fn [{:keys [body] :as request}]
    (if (zero? (.available ^InputStream body))
      request
      (assoc request :csv-params (codec/csv->clj body opts)))))

(defn strict-json-parser
  "see body-params/custom-json-parser"
  [& options]
  (fn [{:keys [body character-encoding] :as request}]
    (let [encoding (or character-encoding "UTF-8")]
      (assoc request :json-params (apply codec/json-read
                                         (InputStreamReader.
                                           ^InputStream body
                                           ^String encoding)
                                         options)))))

(defn body-parsers [& parser-options]
  (let [{:keys [edn-options json-options csv-options]} (apply hash-map parser-options)
        edn-options-vec (apply concat edn-options)
        json-options-vec (apply concat json-options)]
    {#"^application/edn" (apply body-params/custom-edn-parser edn-options-vec)
     #"^text/csv"         (csv-parser csv-options)
     #"^application/json" (apply strict-json-parser json-options-vec)
     #"^application-json" (apply strict-json-parser json-options-vec)
     #"^application/x-www-form-urlencoded" body-params/form-parser}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ENCODING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-handle-encoding-errors [f]
  (fn [body output-stream]
    (try (f body output-stream)
      (catch Throwable e
        (if (or (instance? EOFException e)
                (and (instance? RuntimeException e) (->> e .getCause (instance? EOFException))))
            (log/trace e "EOFException in encoding; probably stream closed because client was disconnected")
            (log/info e "Unhandled error in encoding"))))))

(defafter data-body
  "Set the Content-Type and encode a Clojure data body if the body is not encoded."
  [{:keys [request response] :as context}]
  (let [{:keys [body headers]} response
        accepted (-> request :accept :field)]
    (if (and (coll? body) (not (get headers "Content-Type")))
      (let [content-type (or accepted +default-content-type+)
            encoder      (+content-types+ content-type)
            new-response (-> response
                             (ring-response/content-type (fix-content-type content-type content-type))
                             (ring-response/charset      codec/utf-8)
                             (assoc :body (partial (with-handle-encoding-errors encoder) body)))]
        (assoc context :response new-response))
      context)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LOGGING
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defafter log-response
  "Logs the HTTP response as structured data.

   Per memory/interaction/logs_must_be_self_explanatory_high_signal_not_kv_dump_2026_05_23.md
   — passes the full response body through unchanged.  The formatter
   (sandbar.logging.format) decides any truncation policy at display time,
   not the callsite.  This restores response-content visibility that was
   previously elided to `#` by *print-level* 2 + *print-length* 10."
  [{:keys [response] :as context}]
  (if (:suppress-logging? context)
    (log/info :HTTP/RESPONSE :REDACTED)
    (log/info :HTTP/RESPONSE
              {:route  (-> context :route :route-name)
               :status (:status response)
               :body   (:body response)}))
  context)

(defbefore suppress-logging
  "Suppresses automatic logging of endpoint responses for sensitive endpoints."
  [context]
  (assoc-in context [:suppress-logging?] true))
