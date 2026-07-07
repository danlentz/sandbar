(ns sandbar.schedule.schedule-seed-idempotency-test
  "Tests for the :mm/Schedule create-idempotency discipline (W3.B) — a stable
   content-key :db/ident prevents runtime-create proliferation, and
   prune-duplicate-schedules! self-heals DBs that already accumulated duplicates.

   Root cause: :mm/Schedule is a :mm/Spec, NOT a :mm/Memory, so the runtime
   create path (sandbar.store/create-memory!) derived NO :db/ident for it and
   dt/make minted a fresh tempid → brand-new eid on every create.  Repeated
   UNTARGETED creates (the MCP `entity.create :class :mm/Schedule` path + the
   equivalent `dt/make :mm/Schedule` in tests / dev-loops) APPENDED a new
   target-less row each time — the live target-less :mm/Schedule population.  The
   fix gives each schedule a deterministic content-key :db/ident (upsert →
   idempotent) derived from (target, recurrence, timezone), plus a surgical
   self-heal migration that collapses provable duplicates only.

   Sibling to sandbar.db.xor-constraint-seed-test (the parallel seed-idempotency
   fix for schema-seeded XorConstraint sub-entities; commit 1bc426b).  Supersedes
   the scratch repro at sandbar.schedule.schedule-seed-repro-test.

   Per the W3.B schedule-idempotency arc +
   interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.store :as store]
            [sandbar.schedule.system :as system]
            [sandbar.test-util :as tu])
  (:import [java.time Instant]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — fresh in-memory Datomic DB with the full schema the Schedule /
;; Job / Fn specs transitively need, loaded via the production-parallel test
;; load path (tu/load-schema, which fires post-reload handlers).  Binds *conn*.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def full-schema
  "Every schema keyword the system Fn/Job/Schedule specs transitively need."
  [:meta :ref :literal :fn :any :context :event :user :twit :auth :audit
   :workflow :job :mm :mm-artifact :mm-signal :mm-guidance :mm-meta :mm-verb
   :mm-temporal :mm-namespace :workflow-session])

(def ^:dynamic *conn* nil)
(def ^:dynamic *uri* nil)

(defn with-full-db [test-fn]
  (let [uri (str "datomic:mem://schedule-seed-idem-" (random-uuid))]
    (d/create-database uri)
    (let [c (d/connect uri)]
      (reset! db/**conn* c)
      (tu/load-schema c full-schema)
      (binding [*conn* c
                *uri*  uri]
        (try
          (test-fn)
          (finally
            (reset! db/**conn* nil)
            (d/delete-database uri)))))))

(use-fixtures :each with-full-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- schedule-count [dbv]
  (or (d/q '[:find (count ?e) . :where [?e :dt/type :mm/Schedule]] dbv) 0))

(defn- targetless-count [dbv]
  (or (d/q '[:find (count ?e) .
             :where [?e :dt/type :mm/Schedule]
             (not [?e :mm.schedule/target])] dbv) 0))

(defn- create-schedule!
  "Create a :mm/Schedule via the SANCTIONED runtime create path
   (sandbar.store/create-memory!) — exactly what the MCP `entity.create
   :class :mm/Schedule` verb routes through.  Returns the entity-map."
  [props]
  (store/create-memory! :mm/Schedule props {:validate? false}))

(defn- untargeted-props []
  {:mm.schedule/recurrence     "FREQ=HOURLY"
   :mm.schedule/timezone       "UTC"
   ;; dtstart re-anchored at (now) each call — the proliferation mechanism.
   :mm.schedule/dtstart        (Date/from (Instant/now))
   :mm.schedule/misfire-policy :misfire/fire-once-now})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests — recurrence prevention (create-time stable content-key :db/ident)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest untargeted-create-is-idempotent
  (testing "Repeated UNTARGETED creates of a logically-identical schedule via
            the runtime create path UPSERT onto one eid (was: N fresh
            target-less rows).  This is the direct regression fix for the
            target-less :mm/Schedule proliferation."
    (let [n 25]
      (dotimes [_ n] (create-schedule! (untargeted-props)))
      (let [dbv (d/db *conn*)]
        (is (= 1 (schedule-count dbv))
            (str "25 identical untargeted creates collapse to 1 (was " n ")"))
        (is (= 1 (targetless-count dbv))
            "the single surviving schedule is target-less (its logical identity)")))))

(deftest untargeted-create-seed-twice-count-stable
  (testing "Seed a batch of distinct schedules, then seed the SAME batch again;
            the :mm/Schedule count is unchanged on the second seed (upsert)."
    (let [batch [{:mm.schedule/recurrence "FREQ=HOURLY"   :mm.schedule/timezone "UTC"}
                 {:mm.schedule/recurrence "FREQ=DAILY"    :mm.schedule/timezone "UTC"}
                 {:mm.schedule/recurrence "FREQ=WEEKLY"   :mm.schedule/timezone "America/New_York"}]
          seed! (fn [] (doseq [p batch]
                         (create-schedule!
                          (assoc p :mm.schedule/dtstart (Date/from (Instant/now))))))]
      (seed!)
      (let [after-1 (schedule-count (d/db *conn*))]
        (is (= 3 after-1) "first seed creates 3 distinct schedules")
        (seed!)
        (let [after-2 (schedule-count (d/db *conn*))]
          (is (= 3 after-2)
              "SECOND SEED COUNT-STABLE: still 3, not 6 (the idempotency proof)")
          (is (= after-1 after-2)
              "seed-twice :mm/Schedule count is unchanged"))))))

(deftest targeted-create-is-idempotent
  (testing "Even a TARGETED create is idempotent — the target is in the
            content-key, so re-creating the same (target, recurrence, timezone)
            upserts (was: fresh tempid each call)."
    (system/seed-system-jobs!)               ; provides :sandbar.system/db-stats-job
    (let [before (schedule-count (d/db *conn*))
          n 15]
      (dotimes [_ n]
        (create-schedule!
         {:mm.schedule/target         :sandbar.system/db-stats-job
          :mm.schedule/recurrence     "FREQ=HOURLY"
          :mm.schedule/timezone       "UTC"
          :mm.schedule/dtstart        (Date/from (Instant/now))
          :mm.schedule/misfire-policy :misfire/fire-once-now}))
      (is (= (inc before) (schedule-count (d/db *conn*)))
          "15 identical targeted creates add exactly 1 schedule"))))

(deftest distinct-targets-stay-distinct
  (testing "Schedules with DIFFERENT targets do NOT collapse — target is in the
            content-key, so the legitimately-authored distinct-target population
            is preserved."
    (system/seed-system-jobs!)
    (let [before (schedule-count (d/db *conn*))]
      (create-schedule! {:mm.schedule/target     :sandbar.system/db-stats-job
                         :mm.schedule/recurrence "FREQ=HOURLY"
                         :mm.schedule/timezone   "UTC"})
      (create-schedule! {:mm.schedule/target     :sandbar.system/reactive-queue-health-job
                         :mm.schedule/recurrence "FREQ=HOURLY"
                         :mm.schedule/timezone   "UTC"})
      (is (= (+ before 2) (schedule-count (d/db *conn*)))
          "two distinct-target schedules are two distinct rows"))))

(deftest system-seed-idempotent-across-reseed
  (testing "seed-system-jobs! stays at 2 system schedules across repeated
            re-seeds (the pre-existing :db/ident upsert discipline; regression
            guard)."
    (dotimes [_ 10] (system/seed-system-jobs!))
    (let [dbv (d/db *conn*)]
      (is (= 2 (schedule-count dbv)) "exactly the 2 system schedules")
      (is (= 0 (targetless-count dbv)) "no target-less system schedules"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests — self-heal migration (prune-duplicate-schedules!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- inject-legacy-duplicate-schedules!
  "Simulate the pre-fix DB: transact `n` target-less :mm/Schedule rows sharing
   the SAME (recurrence, timezone) but with DIVERGENT dtstart — exactly the
   shape repeated untargeted `dt/make :mm/Schedule` produced (fresh tempid, no
   :db/ident, dtstart re-anchored each call)."
  [n]
  (dotimes [i n]
    @(d/transact *conn*
       [{:db/id                      (d/tempid :db.part/user)
         :dt/type                    :mm/Schedule
         :mm.schedule/recurrence     "FREQ=HOURLY"
         :mm.schedule/timezone       "UTC"
         ;; Divergent dtstart per row — proves the key EXCLUDES dtstart.
         :mm.schedule/dtstart        (Date/from (.plusSeconds (Instant/now) i))
         :mm.schedule/misfire-policy :misfire/fire-once-now}])))

(deftest prune-heals-accumulated-duplicates
  (testing "prune-duplicate-schedules! collapses target-less duplicates that
            differ ONLY by dtstart down to one, restoring a clean count."
    (inject-legacy-duplicate-schedules! 25)
    (is (= 25 (schedule-count (d/db *conn*))) "25 legacy dupes injected")
    (let [pruned (db/prune-duplicate-schedules! *uri*)]
      (is (= 24 pruned) "returns the count of pruned duplicates (25 - 1 survivor)")
      (is (= 1 (schedule-count (d/db *conn*)))
          "collapsed to the single canonical survivor")
      (is (= 1 (targetless-count (d/db *conn*)))
          "the survivor is the target-less logical schedule"))))

(deftest prune-is-idempotent-on-clean-db
  (testing "prune is a no-op on a DB with no duplicate schedules."
    ;; Only the 2 system schedules (distinct targets) — no duplicates.
    (system/seed-system-jobs!)
    (is (= 2 (schedule-count (d/db *conn*))))
    (is (= 0 (db/prune-duplicate-schedules! *uri*))
        "clean DB → prunes nothing")
    (is (= 2 (schedule-count (d/db *conn*))) "system schedules untouched")
    ;; And running prune a SECOND time after a heal is still a no-op.
    (inject-legacy-duplicate-schedules! 5)
    (is (= 4 (db/prune-duplicate-schedules! *uri*)) "first prune heals 5→1")
    (is (= 0 (db/prune-duplicate-schedules! *uri*))
        "second prune is a no-op (already healed)")))

(deftest prune-is-conservative-on-distinct-targets
  (testing "prune NEVER collapses schedules with distinct targets — only
            provable (same target/recurrence/timezone) duplicates merge."
    (system/seed-system-jobs!)
    ;; Two distinct-target schedules + 3 identical target-less dupes.
    (create-schedule! {:mm.schedule/target     :sandbar.system/db-stats-job
                       :mm.schedule/recurrence "FREQ=DAILY"
                       :mm.schedule/timezone   "UTC"})
    (create-schedule! {:mm.schedule/target     :sandbar.system/reactive-queue-health-job
                       :mm.schedule/recurrence "FREQ=DAILY"
                       :mm.schedule/timezone   "UTC"})
    (inject-legacy-duplicate-schedules! 3)
    (let [before (schedule-count (d/db *conn*))]
      (is (= 7 before) "2 system + 2 distinct-target + 3 target-less dupes")
      (let [pruned (db/prune-duplicate-schedules! *uri*)]
        (is (= 2 pruned) "only the 3 target-less dupes collapse (3 - 1 = 2)")
        (is (= 5 (schedule-count (d/db *conn*)))
            "2 system + 2 distinct-target + 1 surviving target-less all remain")))))

(deftest prune-repoints-inbound-edges-onto-survivor
  (testing "an inbound ref (e.g. :mm.schedule-event/schedule) pointing at a
            pruned duplicate is RE-POINTED onto the survivor — no edge is lost."
    (inject-legacy-duplicate-schedules! 2)
    (let [[a b] (map first
                     (d/q '[:find ?e :where [?e :dt/type :mm/Schedule]] (d/db *conn*)))
          ;; Attach a ScheduleEvent to schedule `b` (which may be pruned).
          _  @(d/transact *conn*
                [{:db/id                     (d/tempid :db.part/user)
                  :dt/type                   :mm.event/Scheduled
                  :mm.schedule-event/schedule b}])
          _  (db/prune-duplicate-schedules! *uri*)
          dbv (d/db *conn*)
          survivors (map first
                         (d/q '[:find ?e :where [?e :dt/type :mm/Schedule]] dbv))
          ;; The single surviving schedule must now carry the event's inbound ref.
          surv (first survivors)
          event-targets (map first
                             (d/q '[:find ?s
                                    :where [?ev :mm.schedule-event/schedule ?s]]
                                  dbv))]
      (is (= 1 (count survivors)) "collapsed to one survivor")
      (is (every? #(= surv %) event-targets)
          "the ScheduleEvent's :mm.schedule-event/schedule ref points at the survivor")
      (is (seq event-targets)
          "the inbound edge survived the prune (was not silently dropped)"))))
