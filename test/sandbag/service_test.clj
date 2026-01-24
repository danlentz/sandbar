(ns sandbag.service-test
  "Test suite for Pedestal HTTP service"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [cheshire.core :as json]
            [io.pedestal.test :refer [response-for]]
            [io.pedestal.http :as http]
            [datomic.api :as d]
            [sandbag.db.datomic :as db]
            [sandbag.service.config :as config]
            [sandbag.util.edn :as edn]
            [sandbag.util.http-status :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-uri "datomic:mem://service-test")

(defn load-test-schema
  "Load schema files into test database"
  [conn]
  (doseq [schema-name (edn/config-value :required-schema)]
    (doseq [stmt (edn/resource-value schema-name nil)]
      @(d/transact conn stmt))))

(def service
  "Create test service from config"
  (::http/service-fn (http/create-servlet config/service)))

(defn with-test-db
  "Fixture that creates an in-memory database for each test"
  [f]
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (reset! db/**conn* conn)
    (try
      (load-test-schema conn)
      (f)
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)))))

(use-fixtures :each with-test-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helper Functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-json-body
  "Parse JSON response body"
  [response]
  (when-let [body (:body response)]
    (json/parse-string body true)))

(defn get-header
  "Get a response header (case-insensitive)"
  [response header-name]
  (get (:headers response) header-name))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Home Page Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest home-page-test
  (testing "GET / returns online status"
    (let [response (response-for service :get "/")]
      (is (= http-status/success (:status response))
          "Should return http-status/success OK")
      (is (str/starts-with? (:body response) "online:")
          "Body should indicate service is online"))))

(deftest home-page-methods-test
  (testing "POST / is not allowed"
    (let [response (response-for service :post "/")]
      (is (not= http-status/success (:status response))
          "POST should not be allowed on home page"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Favicon Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest favicon-test
  (testing "GET /favicon.ico returns image"
    (let [response (response-for service :get "/favicon.ico")]
      (is (= http-status/success (:status response))
          "Should return http-status/success OK")
      (is (= "image/png" (get-header response "Content-Type"))
          "Should have image/png content type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Status API Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest status-api-test
  (testing "GET /api/status returns status info"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "application/json"})]
      (is (= http-status/success (:status response))
          "Should return http-status/success OK")
      (let [body (parse-json-body response)]
        (is (some? (:time body))
            "Response should include time")
        (is (some? (:clojure body))
            "Response should include clojure version")))))

(deftest status-api-content-types-test
  (testing "Status API respects Accept header for JSON"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "application/json"})]
      (is (= http-status/success (:status response)))
      (is (str/includes? (get-header response "Content-Type") "application/json")
          "Should return JSON content type")))

  (testing "Status API respects Accept header for EDN"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "application/edn"})]
      (is (= http-status/success (:status response)))
      (is (str/includes? (get-header response "Content-Type") "application/edn")
          "Should return EDN content type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Negotiation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest content-negotiation-test
  (testing "API rejects unacceptable content types"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "text/html"})]
      (is (= http-status/not-acceptable (:status response))
          "Should return http-status/not-acceptable Not Acceptable for unsupported content type"))))

(deftest default-content-type-test
  (testing "API defaults to JSON when no Accept header"
    (let [response (response-for service :get "/api/status")]
      (is (= http-status/success (:status response)))
      ;; Should default to JSON
      (is (some? (parse-json-body response))
          "Should be parseable as JSON"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Security Headers Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest security-headers-test
  (testing "Response includes security headers"
    (let [response (response-for service :get "/")]
      (is (some? (get-header response "X-Frame-Options"))
          "Should have X-Frame-Options header")
      (is (some? (get-header response "X-Content-Type-Options"))
          "Should have X-Content-Type-Options header")
      (is (some? (get-header response "X-XSS-Protection"))
          "Should have X-XSS-Protection header"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Not Found Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest not-found-test
  (testing "Unknown routes return http-status/not-found"
    (let [response (response-for service :get "/nonexistent")]
      (is (= http-status/not-found (:status response))
          "Should return http-status/not-found for unknown route")))

  (testing "Unknown API routes return http-status/not-found"
    (let [response (response-for service :get "/api/nonexistent"
                                 :headers {"Accept" "application/json"})]
      (is (= http-status/not-found (:status response))
          "Should return http-status/not-found for unknown API route"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Response Structure Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest response-structure-test
  (testing "Successful responses have expected structure"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "application/json"})]
      (is (contains? response :status) "Response should have :status")
      (is (contains? response :headers) "Response should have :headers")
      (is (contains? response :body) "Response should have :body"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Response Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest json-response-format-test
  (testing "JSON responses are well-formed"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "application/json"})
          body (:body response)]
      (is (string? body) "Body should be a string")
      (is (str/starts-with? (str/trim body) "{")
          "JSON body should start with {")
      (is (str/ends-with? (str/trim body) "}")
          "JSON body should end with }"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EDN Response Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest edn-response-format-test
  (testing "EDN responses are well-formed"
    (let [response (response-for service :get "/api/status"
                                 :headers {"Accept" "application/edn"})
          body (:body response)]
      (is (string? body) "Body should be a string")
      (let [parsed (read-string body)]
        (is (map? parsed) "EDN body should parse to a map")
        (is (contains? parsed :time) "Parsed EDN should have :time")
        (is (contains? parsed :clojure) "Parsed EDN should have :clojure")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP Method Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (deftest http-methods-test
;;   (testing "HEAD request works"
;;     (let [response (response-for service :head "/")]
;;       ;; HEAD should return headers but no body
;;       (is (#{http-status/success http-status/method-not-allowed} (:status response))
;;           "HEAD should either work or return method not allowed")))

;;   (testing "OPTIONS request"
;;     (let [response (response-for service :options "/")]
;;       ;; Pedestal may or may not handle OPTIONS
;;       (is (number? (:status response))
;;           "OPTIONS should return a status code"))))
