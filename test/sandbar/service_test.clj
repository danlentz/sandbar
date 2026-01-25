(ns sandbar.service-test
  "Test suite for Pedestal HTTP service"
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [io.pedestal.test :refer [response-for]]
            [sandbar.test-util :as tu :refer [service parse-json-body get-header
                                               with-auth-headers]]
            [sandbar.util.http-status :as http-status]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "service-test"}))

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
                                 :headers (with-auth-headers {"Accept" "application/json"}))]
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
                                 :headers (with-auth-headers {"Accept" "application/json"}))]
      (is (= http-status/success (:status response)))
      (is (str/includes? (get-header response "Content-Type") "application/json")
          "Should return JSON content type")))

  (testing "Status API respects Accept header for EDN"
    (let [response (response-for service :get "/api/status"
                                 :headers (with-auth-headers {"Accept" "application/edn"}))]
      (is (= http-status/success (:status response)))
      (is (str/includes? (get-header response "Content-Type") "application/edn")
          "Should return EDN content type"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Content Negotiation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest content-negotiation-test
  (testing "API rejects unacceptable content types"
    (let [response (response-for service :get "/api/status"
                                 :headers (with-auth-headers {"Accept" "text/html"}))]
      (is (= http-status/not-acceptable (:status response))
          "Should return http-status/not-acceptable Not Acceptable for unsupported content type"))))

(deftest default-content-type-test
  (testing "API defaults to EDN when no Accept header"
    (let [response (response-for service :get "/api/status"
                                 :headers (with-auth-headers {}))]
      (is (= http-status/success (:status response)))
      ;; Should default to EDN
      (let [parsed (read-string (:body response))]
        (is (map? parsed) "Should be parseable as EDN")
        (is (contains? parsed :time) "Should have :time key")))))

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
                                 :headers (with-auth-headers {"Accept" "application/json"}))]
      (is (= http-status/not-found (:status response))
          "Should return http-status/not-found for unknown API route"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Response Structure Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest response-structure-test
  (testing "Successful responses have expected structure"
    (let [response (response-for service :get "/api/status"
                                 :headers (with-auth-headers {"Accept" "application/json"}))]
      (is (contains? response :status) "Response should have :status")
      (is (contains? response :headers) "Response should have :headers")
      (is (contains? response :body) "Response should have :body"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Response Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest json-response-format-test
  (testing "JSON responses are well-formed"
    (let [response (response-for service :get "/api/status"
                                 :headers (with-auth-headers {"Accept" "application/json"}))
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
                                 :headers (with-auth-headers {"Accept" "application/edn"}))
          body (:body response)]
      (is (string? body) "Body should be a string")
      (let [parsed (read-string body)]
        (is (map? parsed) "EDN body should parse to a map")
        (is (contains? parsed :time) "Parsed EDN should have :time")
        (is (contains? parsed :clojure) "Parsed EDN should have :clojure")))))
