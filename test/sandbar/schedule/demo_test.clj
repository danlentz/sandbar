(ns sandbar.schedule.demo-test
  "γ.5 — Demo job fn tests.

   Coverage:
   - db-stats returns the canonical 4-key shape with non-negative ints
   - log-db-stats invokes db-stats + returns the stats map; suitable
     for direct invocation by sandbar.schedule.job-dispatcher.run-job!

   Per `~/.claude/plans/golden-squishing-flamingo.md` Step 5 + γ.1 ADR
   §1.4."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.demo :as demo]
            [sandbar.test-util     :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "demo-test"
                                              :auth?     false}))

(deftest db-stats-returns-canonical-shape
  (testing "db-stats produces the 4-key snapshot with non-negative ints"
    (let [snap (demo/db-stats)]
      (is (contains? snap :memorials))
      (is (contains? snap :classes))
      (is (contains? snap :properties))
      (is (contains? snap :total-entities))
      (is (nat-int? (:memorials snap))
          ":memorials is a non-negative int")
      (is (nat-int? (:classes snap))
          ":classes is a non-negative int")
      (is (nat-int? (:properties snap))
          ":properties is a non-negative int")
      (is (nat-int? (:total-entities snap))
          ":total-entities is a non-negative int")
      (is (pos? (:classes snap))
          "Test DB has at least one class loaded via required-schema")
      (is (pos? (:properties snap))
          "Test DB has at least one property loaded via required-schema"))))

(deftest log-db-stats-invokes-db-stats-and-returns-snapshot
  (testing "log-db-stats fires the memorial emit + returns the stats map"
    (let [run-ctx {:run-eid 12345 :schedule-eid 67890 :job-eid 11111}
          result (demo/log-db-stats run-ctx)]
      (is (map? result))
      (is (contains? result :memorials))
      (is (contains? result :classes))
      (is (contains? result :properties))
      (is (contains? result :total-entities))
      ;; The fn's job-dispatcher contract: returns its computed value
      ;; (the stats map) which gets stored in :mm.run/output via dt/make
      ;; → :mm.run/status :run.status/completed
      (is (= result (demo/db-stats))
          "Returned snapshot matches a direct db-stats call (deterministic)"))))

(deftest log-reactive-queue-health-returns-canonical-snapshot
  (testing "log-reactive-queue-health returns the 13-key reactive-queue/health snapshot"
    (let [run-ctx {:run-eid 22222 :schedule-eid 33333 :job-eid 44444}
          result (demo/log-reactive-queue-health run-ctx)]
      (is (map? result))
      ;; 13 documented keys per reactive-queue/health docstring
      (is (contains? result :worker-running?))
      (is (contains? result :buffer-size))
      (is (contains? result :dirty-entity-count))
      (is (contains? result :enqueue-total))
      (is (contains? result :drain-total))
      (is (contains? result :coalesce-total))
      (is (contains? result :sink-error-total))
      (is (contains? result :registered-sinks))
      (is (contains? result :saturated?))
      (is (contains? result :startup-instant))
      (is (boolean? (:worker-running? result)))
      (is (nat-int? (:buffer-size result)))
      (is (boolean? (:saturated? result))))))
