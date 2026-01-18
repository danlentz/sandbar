(ns sandbag.service.content
  (:require [cheshire.parse :as parse]
            [clojure.data.codec.base64 :as b64]
            [clojure.string            :as str]
            [clojure.tools.logging     :as log]
            [io.pedestal.http.content-negotiation :as pcon]
            [io.pedestal.interceptor.chain :refer [terminate]]
            [io.pedestal.http.body-params  :as body-params]
            [sandbag.service.endpoint :as endpoint :refer [defbefore defafter]]
            [sandbag.util.common      :as util]
            [sandbag.util.codec       :as codec]
            [ring.util.response        :as ring-response])
  (:import [java.io EOFException InputStream InputStreamReader]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CONTENT TYPE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def +default-content-type+ "application/json")

(def +content-types+
  {"application/json"          codec/clj->json-stream
   "application-json"          codec/clj->json-stream
   "application/edn"           codec/clj->edn-stream
   "text/event-stream"         #(throw (ex-info "Not an Event Stream" {}))
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

(def +response-print-length+  10)
(def +response-print-level+   2)
(def +response-string-length+ 100)

(defafter log-response
  "Logs the first 100 characters of the pre-encoded HTTP response."
  [{:keys [response] :as context}]
  (if (:suppress-logging? context)
    (log/info :HTTP/RESPONSE :REDACTED)
    (log/info :HTTP/RESPONSE
              {:route  (-> context :route :route-name)
               :status (:status response)
               :body   (let [s (binding [*print-length* +response-print-length+
                                         *print-level*  +response-print-level+]
                                 (with-out-str (-> response :body println)))]
                         (subs s +response-string-length+))}))
  context)

(defbefore suppress-logging
  "Suppresses automatic logging of endpoint responses for sensitive endpoints."
  [context]
  (assoc-in context [:suppress-logging?] true))
