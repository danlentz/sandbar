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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; γ.5b — seed-demo-jobs! (boot-time idempotent seeding)

(deftest seed-demo-jobs-creates-entities-and-returns-eids
  (testing "seed-demo-jobs! upserts 2 Fn + 2 Job + 2 Schedule + returns 2 numeric eids"
    (let [eids (demo/seed-demo-jobs!)]
      (is (= 2 (count eids))               "Returns 2 schedule eids")
      (is (every? integer? eids)           "Eids are numeric (not idents — queue-comparator safety)")
      ;; Verify the entities resolve by their stable idents
      (let [db (datomic.api/db (sandbar.db.datomic/conn))]
        (is (some? (datomic.api/entity db :sandbar.demo/log-db-stats-fn))
            ":mm/Fn db-stats upserted")
        (is (some? (datomic.api/entity db :sandbar.demo/log-reactive-queue-health-fn))
            ":mm/Fn reactive-queue-health upserted")
        (is (some? (datomic.api/entity db :sandbar.demo/db-stats-job))
            ":mm/Job db-stats upserted")
        (is (some? (datomic.api/entity db :sandbar.demo/reactive-queue-health-job))
            ":mm/Job reactive-queue-health upserted")
        ;; Schedule references its Job + carries the right RRULE
        (let [sched (datomic.api/entity db :sandbar.demo/db-stats-schedule)]
          (is (= "FREQ=MINUTELY;INTERVAL=15" (:mm.schedule/recurrence sched))
              "db-stats schedule has 15-min RRULE")
          (is (some? (:mm.schedule/dtstart sched)) "dtstart anchored"))
        (let [sched (datomic.api/entity db :sandbar.demo/reactive-queue-health-schedule)]
          (is (= "FREQ=MINUTELY;INTERVAL=10" (:mm.schedule/recurrence sched))
              "reactive-queue-health schedule has 10-min RRULE"))))))

(deftest seed-demo-jobs-is-idempotent
  (testing "Re-seeding upserts (does NOT duplicate) — same schedule entities by ident"
    (demo/seed-demo-jobs!)
    (let [eids2 (demo/seed-demo-jobs!)
          db    (datomic.api/db (sandbar.db.datomic/conn))
          ;; Count :mm/Schedule entities with the demo idents — must be exactly 2
          demo-scheds (->> demo/demo-schedule-idents
                           (map #(datomic.api/entity db %))
                           (filter some?))]
      (is (= 2 (count eids2))           "Second seed still returns 2 eids")
      (is (= 2 (count demo-scheds))     "Exactly 2 demo schedule entities (no duplication)"))))
