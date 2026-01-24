(ns sandbar.test-util
  "Common test utilities and fixtures for sandbar tests"
  (:require [cheshire.core :as json]
            [cognitect.transit :as transit]
            [datomic.api :as d]
            [io.pedestal.http :as http]
            [io.pedestal.test :refer [response-for]]
            [sandbar.db.datomic :as db]
            [sandbar.service.config :as config]
            [sandbar.util.edn :as edn])
  (:import [java.io ByteArrayInputStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn load-schema
  "Load schema files into database.
   schema-names can be a keyword or collection of keywords."
  [conn schema-names]
  (let [names (if (keyword? schema-names) [schema-names] schema-names)]
    (doseq [schema-name names]
      (doseq [stmt (edn/resource-value schema-name nil)]
        @(d/transact conn stmt)))))

(defn load-required-schema
  "Load all schema files specified in :required-schema config"
  [conn]
  (load-schema conn (edn/config-value :required-schema)))

(defn make-test-db-fixture
  "Create a test fixture that sets up an in-memory Datomic database.

   Options:
     :test-name    - Name for the test database URI (default: \"test\")
     :extra-schema - Additional schema keywords to load after required-schema

   Usage:
     (use-fixtures :each (make-test-db-fixture {:test-name \"my-test\"}))
     (use-fixtures :each (make-test-db-fixture {:extra-schema [:zorp]}))"
  ([] (make-test-db-fixture {}))
  ([{:keys [test-name extra-schema]
     :or {test-name "test"}}]
   (fn [f]
     (let [test-uri (str "datomic:mem://" test-name)]
       (d/create-database test-uri)
       (let [conn (d/connect test-uri)]
         (reset! db/**conn* conn)
         (try
           (load-required-schema conn)
           (when extra-schema
             (load-schema conn extra-schema))
           (f)
           (finally
             (reset! db/**conn* nil)
             (d/delete-database test-uri))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP Service
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def service
  "Pedestal test service instance"
  (::http/service-fn (http/create-servlet config/service)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Response Parsing Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-edn-body
  "Parse EDN response body"
  [response]
  (when-let [body (:body response)]
    (read-string body)))

(defn parse-json-body
  "Parse JSON response body"
  [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn parse-transit-body
  "Parse Transit+JSON response body"
  [response]
  (when-let [body (:body response)]
    (let [in (ByteArrayInputStream. (.getBytes body "UTF-8"))
          reader (transit/reader in :json)]
      (transit/read reader))))

(defn get-header
  "Get a response header (case-insensitive)"
  [response header-name]
  (get (:headers response) header-name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API Request Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn api-get
  "Make GET request to API (uses default EDN content type)"
  [path]
  (response-for service :get path))

(defn api-get-edn
  "Make GET request and parse EDN response"
  [path]
  (let [response (api-get path)]
    {:status (:status response)
     :body (parse-edn-body response)}))

(defn api-get-json
  "Make GET request with JSON Accept header and parse response"
  [path]
  (let [response (response-for service :get path
                               :headers {"Accept" "application/json"})]
    {:status (:status response)
     :body (parse-json-body response)}))

(defn api-get-transit
  "Make GET request with Transit+JSON Accept header and parse response"
  [path]
  (let [response (response-for service :get path
                               :headers {"Accept" "application/transit+json"})]
    {:status (:status response)
     :body (parse-transit-body response)}))

(defn api-get-csv
  "Make GET request with CSV Accept header"
  [path]
  (response-for service :get path
                :headers {"Accept" "text/csv"}))
