(ns sandbar.util.codec
  (:require [cheshire.factory          :as factory]
            [cheshire.generate         :as cheshire :refer [add-encoder encode-str]]
            [cheshire.parse            :as parse]
            [cheshire.core             :as json]
            [clojure.data.csv          :as csv]
            [clojure.java.io           :as io]
            [clojure.pprint            :as pp]
            [clojure.walk              :as walk :refer [postwalk]]
            [cognitect.transit         :as transit])
  (:import  [java.io OutputStream InputStream ByteArrayOutputStream BufferedOutputStream
             ByteArrayInputStream BufferedInputStream OutputStreamWriter StringReader StringWriter
             BufferedWriter BufferedReader InputStreamReader File Reader]))

(cheshire/add-encoder clojure.lang.Var encode-str)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; Readers and Writers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (def utf-8 "UTF-8")

  (defn slurp-bytes [path]
    (with-open [out (ByteArrayOutputStream.)]
      (io/copy (io/input-stream path) out)
      (.toByteArray out)))

  (defn buffered-writer [^OutputStream output-stream & [charset]]
    (-> output-stream (OutputStreamWriter. (or charset utf-8))
        (BufferedWriter.)))

  (defn buffered-reader [^InputStream input-stream & [charset]]
    (-> input-stream (InputStreamReader. (or charset utf-8))
        (BufferedReader.)))

  (defprotocol ReadableFrom
    (find-reader [this] [this opts]))

  (defprotocol WritableTo
    (find-writer [this] [this opts]))

  (extend-protocol ReadableFrom
    String
    (find-reader
      ([this]
       (find-reader this nil))
      ([this opts]
       (StringReader. this)))
    Reader
    (find-reader
      ([this]
       (find-reader this nil))
      ([this opts]
       this))
    InputStream
    (find-reader
      ([this]
       (find-reader this nil))
      ([this opts]
       (buffered-reader this (:encoding opts))))
    File
    (find-reader
      ([this]
       (find-reader [this nil]))
      ([this opts]
       (io/reader this :encoding (or (:encoding opts) utf-8)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; application/json
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-strict-json-stream
  "See json/parse-stream."
  ([rdr] (parse-strict-json-stream rdr nil nil))
  ([rdr key-fn] (parse-strict-json-stream rdr key-fn nil))
  ([^BufferedReader rdr key-fn array-coerce-fn]
   (when rdr
     (parse/parse-strict
      (.createParser ^JsonFactory (or factory/*json-factory*
                                      factory/json-factory)
                     ^Reader rdr)
      key-fn nil array-coerce-fn))))

(defn json-read
  "See body-params/json-read"
  [reader & options]
  (let [{:keys [bigdec key-fn array-coerce-fn]
         :or {bigdec false
              key-fn keyword
              array-coerce-fn nil}} options]
    (binding [parse/*use-bigdecimals?* bigdec]
      (parse-strict-json-stream (java.io.PushbackReader. reader) key-fn array-coerce-fn))))

(defn json->clj
  "JSON decode an object."
  ([x] (json->clj x nil))
  ([x key-fn]
   (json/parse-string x (or key-fn keyword))))

(defn clj->json-stream [body output-stream]
  (with-open [writer (buffered-writer output-stream)]
    (json/generate-stream body writer)))

(defn clj->json
  "JSON encode into a string."
          ([x] (clj->json x nil))
  ([x opts] (json/generate-string x opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; application/edn
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn as-edn [body]
  (let [normalize (fn [data]
                    (cond
                      (sequential? data)  (vec data)
                      (associative? data) (into {} data)
                      true                data))]
    (postwalk normalize body)))

(defn clj->edn-stream [body output-stream]
  (with-open [writer (buffered-writer output-stream)]
    (print-dup (as-edn body) writer)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; text/csv
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; opts
;;
;;   :encoding          - character set name
;;   :value-fn          - serialized value of a clojure datum
;;   :column-exclusions - columns to exclude from processing

;;; CSV Encoding

(def default-column-exclusions #{})

(defn default-tabular-value [x]
  (cond
    (number?  x) x
    (string?  x) x
    (symbol?  x) x
    (keyword? x) x
    (instance? java.util.Date x) x
    (not (associative? x)) (pr-str x)
    (:name x) (:name x)
    true      (pr-str x)))

(defn tabular-row [opts]
  (let [value-fn   (or (:value-fn          opts) default-tabular-value)
        exclusions (or (:column-exclusions opts) default-column-exclusions)]
    (fn [x]
      (reduce-kv (fn [acc k v]
                   (if (exclusions k)
                     acc (->> v value-fn (assoc acc k))))
                 {} x))))


(defn- ensure-seq [x]
  (if (sequential? x)
    x
    (list x)))

(defn clj-csv-xform [data & [opts]]
  (let [xf   (fn [ks row] (map row ks))
        body (ensure-seq data)
        cols (->> body first keys)
        rows (->> body (map (tabular-row opts)) (map (partial xf cols)))]
    [cols rows]))

(defn clj->csv-stream [data output-stream & [opts]]
  (let [[cols rows] (clj-csv-xform data opts)]
    (with-open [writer (buffered-writer output-stream (or (:encoding opts) utf-8))]
      (csv/write-csv writer (cons (map name cols) rows)))))

(defn clj->csv-string [data & [opts]]
  (let [[cols rows] (clj-csv-xform data opts)
        out         (StringWriter.)]
    (with-open [writer out]
      (csv/write-csv writer (cons (map name cols) rows)))
    (str out)))

;;; CSV Decoding

(defn csv-clj-xform [[cols & body] & [opts]]
  (let [key-cols (map keyword cols)]
    (map (partial zipmap key-cols) body)))

(defn csv->clj [thing & [opts]]
  (with-open [reader (find-reader thing opts)]
    (csv-clj-xform (->> reader csv/read-csv doall) opts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tabular: "text/plain"
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clj->text-stream [data output-stream & [opts]]
  (let [rows (->> data ensure-seq (map (tabular-row opts)))]
    (with-open [writer (buffered-writer output-stream (or (:encoding opts) utf-8))]
      (binding [*out* writer]
        (pp/print-table rows)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; application/transit+json
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clj->transit-json-stream [body ^OutputStream output-stream]
  (let [writer (transit/writer output-stream :json)]
    (transit/write writer (as-edn body))))

(defn transit-json->clj [^InputStream input-stream]
  (let [reader (transit/reader input-stream :json)]
    (transit/read reader)))

