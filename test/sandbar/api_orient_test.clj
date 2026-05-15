(ns sandbar.api-orient-test
  "Test suite for the Orientation REST API (Phase O of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Endpoint:
    GET /api/orient/library-card?entity=:slug&axes=<EDN>"
  (:require [clojure.test :refer :all]
            [sandbar.test-util :as tu :refer [api-get-edn]]
            [sandbar.util.http-status :as http-status]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "api-orient-test"}))

(deftest library-card-endpoint-happy-path
  (testing "GET /api/orient/library-card returns the multi-axis envelope"
    (let [axes "[{:name%20\"slots\"%20:direction%20:forward%20:predicates%20[:dt/slots]}]"
          {:keys [status body]}
          (api-get-edn (str "/api/orient/library-card"
                            "?entity=:dt/Property"
                            "&axes=" axes))]
      (is (= http-status/success status))
      (is (contains? body :entity))
      (is (contains? body :axes))
      (is (contains? (:axes body) "slots")))))

(deftest library-card-endpoint-missing-entity
  (let [{:keys [status body]}
        (api-get-edn "/api/orient/library-card?axes=%5B%5D")]
    (is (= http-status/bad-request status))
    (is (re-find #"(?i)entity" (str (:error body))))))

(deftest library-card-endpoint-missing-axes
  (let [{:keys [status body]}
        (api-get-edn "/api/orient/library-card?entity=:dt/Property")]
    (is (= http-status/bad-request status))
    (is (re-find #"(?i)axes" (str (:error body))))))

(deftest library-card-endpoint-malformed-axes
  (let [{:keys [status body]}
        (api-get-edn "/api/orient/library-card?entity=:dt/Property&axes=%5B")]
    (is (= http-status/bad-request status))
    (is (re-find #"(?i):axes|EDN" (str (:error body))))))

(deftest library-card-endpoint-non-sequential-axes
  (testing "axes must be a vec / list, not a map or scalar"
    (let [{:keys [status body]}
          (api-get-edn "/api/orient/library-card?entity=:dt/Property&axes=%7B%7D")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)axes|sequential" (str (:error body)))))))
