(ns sandbar.api-aggregate-test
  "Test suite for the Aggregation REST API (Stage 15 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Three endpoints:
    GET /api/aggregate/count
    GET /api/aggregate/group-by
    GET /api/aggregate/rank-by

  Each endpoint is a thin HTTP wrapper around sandbar.aggregate's
  consumer-facing verbs; tests exercise both happy-path responses and
  query-param validation (missing required args, invalid rank-by axis,
  malformed :where EDN, missing :temporal-slot for temporal axes)."
  (:require [clojure.test :refer :all]
            [sandbar.test-util :as tu :refer [api-get-edn]]
            [sandbar.util.http-status :as http-status]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "api-aggregate-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/aggregate/count
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest count-endpoint-success
  (testing "GET /api/aggregate/count?class=:dt/Class returns positive count"
    (let [{:keys [status body]} (api-get-edn "/api/aggregate/count?class=:dt/Class")]
      (is (= http-status/success status))
      (is (contains? body :count))
      (is (pos-int? (:count body))))))

(deftest count-endpoint-accepts-bare-ident-form
  (testing "GET /api/aggregate/count?class=dt/Class (no leading colon) also works"
    (let [{:keys [status body]} (api-get-edn "/api/aggregate/count?class=dt/Class")]
      (is (= http-status/success status))
      (is (pos-int? (:count body))))))

(deftest count-endpoint-missing-class
  (testing "GET /api/aggregate/count without :class returns 400"
    (let [{:keys [status body]} (api-get-edn "/api/aggregate/count")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)class" (str (:error body)))))))

(deftest count-endpoint-malformed-where
  (testing "GET /api/aggregate/count with malformed :where EDN returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/count?class=:dt/Class&where=%5B")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i):where|EDN" (str (:error body)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/aggregate/group-by
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest group-by-endpoint-success
  (testing "GET /api/aggregate/group-by?class=:dt/Property&group-by=:db/cardinality"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/group-by?class=:dt/Property&group-by=:db/cardinality")]
      (is (= http-status/success status))
      (is (contains? body :groups))
      (is (contains? body :total))
      (is (map? (:groups body)))
      (is (pos-int? (:total body)))
      (is (= (:total body) (reduce + 0 (vals (:groups body))))
          "groups counts should sum to total"))))

(deftest group-by-endpoint-missing-class
  (testing "GET /api/aggregate/group-by without :class returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/group-by?group-by=:db/cardinality")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)class" (str (:error body)))))))

(deftest group-by-endpoint-missing-group-by
  (testing "GET /api/aggregate/group-by without :group-by returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/group-by?class=:dt/Property")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)group-by" (str (:error body)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GET /api/aggregate/rank-by
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rank-by-endpoint-success-degree
  (testing "GET /api/aggregate/rank-by?class=:dt/Class&rank-by=:degree"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?class=:dt/Class&rank-by=:degree&limit=5")]
      (is (= http-status/success status))
      (is (contains? body :hits))
      (is (contains? body :total))
      (is (contains? body :returned))
      (is (sequential? (:hits body)))
      (is (<= (:returned body) 5) "limit=5 caps returned hits")
      (when (seq (:hits body))
        (let [first-hit (first (:hits body))]
          (is (contains? first-hit :entity))
          (is (contains? first-hit :rank-score)))))))

(deftest rank-by-endpoint-success-backlink-density
  (testing "GET /api/aggregate/rank-by?class=:dt/Property&rank-by=:backlink-density"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?class=:dt/Property&rank-by=:backlink-density&limit=3")]
      (is (= http-status/success status))
      (is (sequential? (:hits body)))
      (is (<= (:returned body) 3)))))

(deftest rank-by-endpoint-missing-class
  (testing "GET /api/aggregate/rank-by without :class returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?rank-by=:degree")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)class" (str (:error body)))))))

(deftest rank-by-endpoint-missing-rank-by
  (testing "GET /api/aggregate/rank-by without :rank-by returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?class=:dt/Class")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)rank-by" (str (:error body)))))))

(deftest rank-by-endpoint-invalid-axis
  (testing "GET /api/aggregate/rank-by with unknown axis returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?class=:dt/Class&rank-by=:bogus")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)invalid|axis" (str (:error body)))))))

(deftest rank-by-endpoint-temporal-axis-needs-slot
  (testing "GET /api/aggregate/rank-by?rank-by=:recency without :temporal-slot returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?class=:dt/Class&rank-by=:recency")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)temporal-slot|recency" (str (:error body))))))

  (testing "GET /api/aggregate/rank-by?rank-by=:freshness without :temporal-slot returns 400"
    (let [{:keys [status body]}
          (api-get-edn "/api/aggregate/rank-by?class=:dt/Class&rank-by=:freshness")]
      (is (= http-status/bad-request status))
      (is (re-find #"(?i)temporal-slot|freshness" (str (:error body)))))))
