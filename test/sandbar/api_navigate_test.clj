(ns sandbar.api-navigate-test
  "Test suite for the Navigation REST API (Stage P-6 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Endpoint:
    GET /api/navigate/path?from=:dt/...&via=<EDN>[&limit=N&include=paths]"
  (:require [clojure.test :refer :all]
            [sandbar.test-util :as tu :refer [api-get-edn]]
            [sandbar.util.http-status :as http-status]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "api-navigate-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/navigate/path — happy paths
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest path-via-endpoint-atomic
  (testing "GET /api/navigate/path?from=:dt/Property&via=:dt/subclass-of"
    (let [{:keys [status body]}
          (api-get-edn "/api/navigate/path?from=:dt/Property&via=:dt/subclass-of")]
      (is (= http-status/success status))
      (is (contains? body :reachable))
      (is (contains? body :total))
      (is (contains? body :returned))
      (is (pos? (:total body))))))

(deftest path-via-endpoint-rep-plus
  (testing "GET /api/navigate/path with :REP+ transitive closure"
    (let [{:keys [status body]}
          (api-get-edn (str "/api/navigate/path"
                            "?from=:dt/Property"
                            "&via=" "[:REP%2B%20:dt/subclass-of]"))]
      (is (= http-status/success status))
      (is (pos? (:total body))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/navigate/path — boundary conditions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest path-via-endpoint-missing-from
  (testing "missing :from returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/navigate/path?via=:dt/subclass-of")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)from" (str (:error body)))))))

(deftest path-via-endpoint-missing-via
  (testing "missing :via returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/navigate/path?from=:dt/Property")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)via" (str (:error body)))))))

(deftest path-via-endpoint-blank-via
  (testing "blank :via returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/navigate/path?from=:dt/Property&via=")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)via" (str (:error body)))))))

(deftest path-via-endpoint-malformed-via-returns-400
  (testing "malformed :via EDN returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/navigate/path?from=:dt/Property&via=%5B")]
      (is (= http-status/bad-request status)))))

(deftest path-via-endpoint-respects-limit
  (testing ":limit caps returned count"
    (let [{:keys [body]}
          (api-get-edn (str "/api/navigate/path"
                            "?from=:dt/Resource"
                            "&via=" "[:INV%20:dt/subclass-of]"
                            "&limit=2"))]
      (is (<= (:returned body) 2)))))

(deftest path-via-endpoint-include-paths-deferred-flag
  (testing ":include=paths surfaces :path-data-deferred flag"
    (let [{:keys [status body]}
          (api-get-edn (str "/api/navigate/path"
                            "?from=:dt/Property"
                            "&via=:dt/subclass-of"
                            "&include=paths"))]
      (is (= http-status/success status))
      (is (true? (:path-data-deferred body))))))
