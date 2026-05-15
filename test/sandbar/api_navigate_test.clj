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

(deftest path-via-endpoint-include-paths-populates-real-path-data
  (testing "Phase R Stage R-7: GET /api/navigate/path?include=paths
            now returns real path data through the REST layer — no
            more :path-data-deferred flag.  Each :reachable entry
            carries {:entity ... :path {:nodes [...] :edges [...]}}.

            (Replaces the prior
            path-via-endpoint-include-paths-deferred-flag test which
            asserted the pre-R-7 placeholder flag.)"
    (let [{:keys [status body]}
          (api-get-edn (str "/api/navigate/path"
                            "?from=:dt/Property"
                            "&via=:dt/subclass-of"
                            "&include=paths"))]
      (is (= http-status/success status))
      ;; Post-R-7: no deferred flag.
      (is (not (contains? body :path-data-deferred))
          ":path-data-deferred must NOT appear in R-7 REST response")
      (is (pos? (:total body)))
      ;; Each entry has :entity + :path; path has :nodes + :edges.
      (doseq [entry (:reachable body)]
        (is (contains? entry :entity)
            "each REST :reachable entry must carry :entity")
        (is (contains? entry :path)
            "each REST :reachable entry must carry :path")
        (is (vector? (:nodes (:path entry)))
            ":path :nodes is a vec (JSON-friendly through pedestal)")
        (is (vector? (:edges (:path entry)))
            ":path :edges is a vec (JSON-friendly through pedestal)")
        ;; Invariant: (count :nodes) = (count :edges) + 1
        (is (= (count (:nodes (:path entry)))
               (inc (count (:edges (:path entry))))))))))

(deftest path-via-endpoint-any-returns-structured-response
  (testing "F-MF-1 anti-regression: GET /api/navigate/path?via=%3AANY
            returns 200 + structured response; does NOT propagate the
            pre-fix :db.error/not-a-keyword crash through the REST
            envelope.

            URL-encoded :ANY = %3AANY (colon = %3A)."
    (let [{:keys [status body]}
          (api-get-edn "/api/navigate/path?from=:dt/Property&via=%3AANY")]
      (is (= http-status/success status)
          (str "REST endpoint must return 200 for :ANY, not propagate the "
               "F-MF-1 :db.error crash; got " status " body: " (pr-str body)))
      (is (contains? body :reachable))
      (is (contains? body :total))
      (is (contains? body :returned))
      (is (every? map? (:reachable body))
          ":ANY endpoints projected through REST must be entity-maps"))))

(deftest path-via-endpoint-rep-plus-with-paths-multi-hop
  (testing "Phase R Stage R-7: GET /api/navigate/path with :REP+ +
            include=paths returns multi-hop path data via REST"
    (let [{:keys [status body]}
          (api-get-edn (str "/api/navigate/path"
                            "?from=:dt/Property"
                            "&via=" "[:REP%2B%20:dt/subclass-of]"
                            "&include=paths"))]
      (is (= http-status/success status))
      (is (not (contains? body :path-data-deferred)))
      (is (pos? (:total body)))
      (doseq [entry (:reachable body)]
        (is (contains? entry :entity))
        (is (contains? entry :path))
        ;; REST returns EDN here (per api-get-edn helper); :path's
        ;; :nodes + :edges must be vectors with the correct invariant.
        (let [{:keys [nodes edges]} (:path entry)]
          (is (vector? nodes))
          (is (vector? edges))
          (is (= (count nodes) (inc (count edges))))
          (is (pos? (count edges)) ":REP+ paths must have ≥1 edge")
          ;; Every edge must have :predicate + :direction
          (doseq [edge edges]
            (is (contains? edge :predicate))
            (is (contains? edge :direction))))))))
