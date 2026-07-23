(ns sandbar.schedule.system-test
  "γ.5 — System job fn tests.

   Coverage:
   - db-stats returns the canonical 4-key shape with non-negative ints
   - log-db-stats invokes db-stats + returns the stats map; suitable
     for direct invocation by sandbar.schedule.job-dispatcher.run-job!
   - log-reactive-queue-health returns the canonical health snapshot
   - seed-system-jobs! upserts 2 Fn + 2 Job + 2 Schedule idempotently
   - REGRESSION: a boot-seeded schedule, when fired, resolves its Job
     (the no-job-target seed-path bug — keyword idents on :db.type/ref
     slots were stored literally instead of as resolved eid refs;
     seed-system-jobs! now resolves idents → eids before transacting).

   Per `~/.claude/plans/golden-squishing-flamingo.md` Step 5 + γ.1 ADR
   §1.4."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.system :as system]
            [sandbar.schedule.state :as sched-state]
            [sandbar.schedule.job-dispatcher :as jd]
            [sandbar.event :as event]
            [sandbar.test-util     :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "system-test"
                                              :auth?     false}))

(deftest db-stats-returns-canonical-shape
  (testing "db-stats produces the 4-key snapshot with non-negative ints"
    (let [snap (system/db-stats)]
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
          ;; Snapshot BEFORE the emit.  log-db-stats computes its own
          ;; db-stats first, THEN emits — and the emit itself persists an
          ;; entity (a :db-only :event/SystemEvent), which bumps
          ;; :total-entities.  Comparing `result` to a db-stats call taken
          ;; AFTER the emit would be off-by-one (the very feedback-loop
          ;; reason these jobs are :db-only, not :first-class :mm/Memory).
          ;; So compare against the pre-emit snapshot: nothing mutates the
          ;; DB between `before` and log-db-stats's internal computation.
          before (system/db-stats)
          result (system/log-db-stats run-ctx)]
      (is (map? result))
      (is (contains? result :memorials))
      (is (contains? result :classes))
      (is (contains? result :properties))
      (is (contains? result :total-entities))
      ;; The fn's job-dispatcher contract: returns its computed value
      ;; (the stats map) which gets stored in :mm.run/output via dt/make
      ;; → :mm.run/status :run.status/completed
      (is (= result before)
          "Returned snapshot matches the db-stats computed just before the emit"))))

(deftest log-reactive-queue-health-returns-canonical-snapshot
  (testing "log-reactive-queue-health returns the 13-key reactive-queue/health snapshot"
    (let [run-ctx {:run-eid 22222 :schedule-eid 33333 :job-eid 44444}
          result (system/log-reactive-queue-health run-ctx)]
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
;; γ.5b — seed-system-jobs! (boot-time idempotent seeding)

(deftest seed-system-jobs-creates-entities-and-returns-eids
  (testing "seed-system-jobs! upserts 2 Fn + 2 Job + 2 Schedule + returns 2 numeric eids"
    (let [eids (system/seed-system-jobs!)]
      (is (= 2 (count eids))               "Returns 2 schedule eids")
      (is (every? integer? eids)           "Eids are numeric (not idents — queue-comparator safety)")
      ;; Verify the entities resolve by their stable idents
      (let [db (datomic.api/db (sandbar.db.datomic/conn))]
        (is (some? (datomic.api/entity db :sandbar.system/log-db-stats-fn))
            ":mm/Fn db-stats upserted")
        (is (some? (datomic.api/entity db :sandbar.system/log-reactive-queue-health-fn))
            ":mm/Fn reactive-queue-health upserted")
        (is (some? (datomic.api/entity db :sandbar.system/db-stats-job))
            ":mm/Job db-stats upserted")
        (is (some? (datomic.api/entity db :sandbar.system/reactive-queue-health-job))
            ":mm/Job reactive-queue-health upserted")
        ;; REGRESSION (no-job-target bug): the ref slots must RESOLVE to the
        ;; correct target entity.  NOTE: reading a :db.type/ref to a NAMED
        ;; entity back via d/entity returns the target's :db/ident KEYWORD
        ;; (Datomic's enum-ref behavior), not an eid proxy — so we assert
        ;; the slot resolves to an entity of the right :dt/type rather than
        ;; that it is a bare integer eid.  d/entity accepts both an eid and
        ;; a :db/ident keyword, so this is representation-agnostic.
        (let [job    (datomic.api/entity db :sandbar.system/db-stats-job)
              fn-ref (:mm.job/fn job)]
          (is (some? fn-ref) ":mm.job/fn ref present")
          (is (= :mm/Fn (:dt/type (datomic.api/entity db fn-ref)))
              ":mm.job/fn resolves to a :mm/Fn entity"))
        ;; Schedule references its Job + carries the right RRULE
        (let [sched (datomic.api/entity db :sandbar.system/db-stats-schedule)]
          (is (= "FREQ=MINUTELY;INTERVAL=15" (:mm.schedule/recurrence sched))
              "db-stats schedule has 15-min RRULE")
          (is (some? (:mm.schedule/dtstart sched)) "dtstart anchored")
          (is (= :mm/Job (:dt/type (datomic.api/entity db (:mm.schedule/target sched))))
              ":mm.schedule/target resolves to a :mm/Job entity"))
        (let [sched (datomic.api/entity db :sandbar.system/reactive-queue-health-schedule)]
          (is (= "FREQ=MINUTELY;INTERVAL=10" (:mm.schedule/recurrence sched))
              "reactive-queue-health schedule has 10-min RRULE"))))))

(deftest seed-system-jobs-is-idempotent
  (testing "Re-seeding upserts (does NOT duplicate) — same schedule entities by ident"
    (system/seed-system-jobs!)
    (let [eids2 (system/seed-system-jobs!)
          db    (datomic.api/db (sandbar.db.datomic/conn))
          ;; Count :mm/Schedule entities with the system idents — must be exactly 2
          system-scheds (->> system/system-schedule-idents
                             (map #(datomic.api/entity db %))
                             (filter some?))]
      (is (= 2 (count eids2))           "Second seed still returns 2 eids")
      (is (= 2 (count system-scheds))   "Exactly 2 system schedule entities (no duplication)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; REGRESSION — seed-path no-job-target bug (2026-05-28)
;;
;; Production symptom: when the boot-seeded schedules fire, the job-dispatcher
;; logged :scheduled-event-no-job-target — it couldn't resolve the :mm/Job
;; from the fired Schedule's :mm.schedule/target.  Root cause: the seed path
;; used KEYWORD :db/ident refs (:mm.schedule/target :sandbar.system/X-job) and
;; dt/make stored the keyword LITERALLY on the :db.type/ref slot rather than
;; resolving it to a :db/ident ref.  Fix: seed-system-jobs! resolves the Fn
;; idents → eids (for :mm.job/fn) and Job idents → eids (for
;; :mm.schedule/target) BEFORE transacting, storing proper numeric-eid refs.
;; This test exercises the EXACT seed path end-to-end.

(deftest seed-then-handle-scheduled-event-resolves-job
  (testing "A boot-seeded schedule, when fired, resolves its Job (NOT :no-job-target)"
    (binding [sched-state/*scheduler-state*
              (atom (sched-state/initial-state {:enabled? true}))]
      ;; Drive state to :active so handle-scheduled-event-sync proceeds
      (sched-state/swap-state! assoc :state :scheduler.state/active)
      (event/clear!)
      (let [schedule-eids (system/seed-system-jobs!)
            first-eid     (first schedule-eids)
            outcome       (#'jd/handle-scheduled-event-sync
                           {:mm.schedule-event/schedule first-eid})]
        (is (not= :no-job-target (:reason outcome))
            (str "Seeded schedule must resolve its Job via :mm.schedule/target "
                 "eid ref; got outcome: " (pr-str outcome)))
        ;; Positive: the job fn should have run to completion (it's a
        ;; classpath-fn that resolves + returns the stats/health map)
        (is (= :completed (:outcome outcome))
            (str "Expected :completed; got: " (pr-str outcome)))))))
