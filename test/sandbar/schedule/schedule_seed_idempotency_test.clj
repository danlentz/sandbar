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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tests — DISTINCTNESS ON NON-TARGET AXES (W3.B REVISE data-loss fix)
;;
;; The original content-key spanned only (target, recurrence, timezone), so two
;; schedules identical on those three but differing on a first-class,
;; dispatcher-read slot (:until / :count / :exdates / :misfire-policy /
;; :concurrency) collapsed onto ONE eid — silently deleting a legitimately-
;; distinct schedule (create-path upsert) or retracting it (prune).  These tests
;; lock each such axis as identity-distinguishing, at BOTH create and prune.
;; Mirrors `distinct-targets-stay-distinct`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private base-until-props
  "A fixed (recurrence, timezone) base — the axes the OLD key spanned besides
   target.  Intentionally TARGET-LESS so these tests need no seed-system-jobs!
   (a target :db.type/ref requires the ident to already exist); target-lessness
   is orthogonal to the non-target-axis distinctness under test.  Each test
   perturbs exactly ONE non-target axis on top of this base."
  {:mm.schedule/recurrence "FREQ=DAILY"
   :mm.schedule/timezone   "UTC"})

(defn- iso->date [iso] (Date/from (Instant/parse iso)))

(deftest distinct-on-until-stays-distinct-at-create
  (testing ":until 2027 vs 2099 — identical on (target, recurrence, timezone)
            but a DIFFERENT terminator instant — MUST remain two distinct rows
            at create (the reported data-loss repro: the two collapsed to one)."
    (system/seed-system-jobs!)
    (let [before (schedule-count (d/db *conn*))]
      (create-schedule! (assoc base-until-props
                               :mm.schedule/until (iso->date "2027-01-01T00:00:00Z")))
      (create-schedule! (assoc base-until-props
                               :mm.schedule/until (iso->date "2099-01-01T00:00:00Z")))
      (is (= (+ before 2) (schedule-count (d/db *conn*)))
          "two schedules differing only by :until are two distinct rows")
      ;; And their derived idents differ (the mechanism, proven directly).
      (is (not= (store/derive-schedule-ident
                 :mm/Schedule (assoc base-until-props
                                     :mm.schedule/until (iso->date "2027-01-01T00:00:00Z")))
                (store/derive-schedule-ident
                 :mm/Schedule (assoc base-until-props
                                     :mm.schedule/until (iso->date "2099-01-01T00:00:00Z"))))
          ":until is in the content-key → distinct idents"))))

(deftest distinct-on-count-stays-distinct-at-create
  (testing ":count 5 vs 500 — a different bounded recurrence count — stays
            distinct at create."
    (system/seed-system-jobs!)
    (let [before (schedule-count (d/db *conn*))]
      (create-schedule! (assoc base-until-props :mm.schedule/count 5))
      (create-schedule! (assoc base-until-props :mm.schedule/count 500))
      (is (= (+ before 2) (schedule-count (d/db *conn*)))
          "two schedules differing only by :count are two distinct rows"))))

(deftest distinct-on-exdates-stays-distinct-at-create
  (testing ":exdates {A} vs {A B} — a different exclusion set — stays distinct
            at create (order-independent set membership drives the key)."
    (system/seed-system-jobs!)
    (let [before (schedule-count (d/db *conn*))]
      (create-schedule! (assoc base-until-props
                               :mm.schedule/exdates #{(iso->date "2027-01-01T00:00:00Z")}))
      (create-schedule! (assoc base-until-props
                               :mm.schedule/exdates #{(iso->date "2027-01-01T00:00:00Z")
                                                      (iso->date "2027-02-01T00:00:00Z")}))
      (is (= (+ before 2) (schedule-count (d/db *conn*)))
          "two schedules differing only by :exdates are two distinct rows")
      ;; Order-independence proof: {A B} and {B A} key IDENTICALLY.
      (is (= (store/derive-schedule-ident
              :mm/Schedule (assoc base-until-props
                                  :mm.schedule/exdates [(iso->date "2027-01-01T00:00:00Z")
                                                        (iso->date "2027-02-01T00:00:00Z")]))
             (store/derive-schedule-ident
              :mm/Schedule (assoc base-until-props
                                  :mm.schedule/exdates [(iso->date "2027-02-01T00:00:00Z")
                                                        (iso->date "2027-01-01T00:00:00Z")])))
          ":exdates key is order-independent (same set → same ident)"))))

(deftest distinct-on-misfire-policy-stays-distinct-at-create
  (testing ":misfire-policy :fire-once-now vs :ignore — LEAD RULING (a): policy
            slots are folded into the key, so operationally-different schedules
            stay distinct at create."
    (system/seed-system-jobs!)
    (let [before (schedule-count (d/db *conn*))]
      (create-schedule! (assoc base-until-props
                               :mm.schedule/misfire-policy :misfire/fire-once-now))
      (create-schedule! (assoc base-until-props
                               :mm.schedule/misfire-policy :misfire/ignore))
      (is (= (+ before 2) (schedule-count (d/db *conn*)))
          "two schedules differing only by :misfire-policy are two distinct rows"))))

(deftest distinct-on-concurrency-stays-distinct-at-create
  (testing ":concurrency :forbid vs :allow — LEAD RULING (a): folded into the
            key, so distinct concurrency behavior → distinct rows at create."
    (system/seed-system-jobs!)
    (let [before (schedule-count (d/db *conn*))]
      (create-schedule! (assoc base-until-props
                               :mm.schedule/concurrency :concurrency/forbid))
      (create-schedule! (assoc base-until-props
                               :mm.schedule/concurrency :concurrency/allow))
      (is (= (+ before 2) (schedule-count (d/db *conn*)))
          "two schedules differing only by :concurrency are two distinct rows"))))

(deftest distinct-on-non-target-axes-survive-prune
  (testing "PRUNE must NOT collapse schedules that differ on a non-target axis.
            Inject 5 schedules that share (target, recurrence, timezone) but each
            differ on a DIFFERENT semantic slot (:until / :count / :exdates /
            :misfire-policy / :concurrency) — the widened key groups each ALONE,
            so an :apply prune retracts NOTHING.  This is the direct data-loss
            guard: the OLD key grouped all 5 together and would have destroyed 4."
    (let [variants
          [(assoc base-until-props :mm.schedule/until   (iso->date "2030-01-01T00:00:00Z"))
           (assoc base-until-props :mm.schedule/count   7)
           (assoc base-until-props :mm.schedule/exdates #{(iso->date "2030-06-01T00:00:00Z")})
           (assoc base-until-props :mm.schedule/misfire-policy :misfire/ignore)
           (assoc base-until-props :mm.schedule/concurrency    :concurrency/replace)]]
      (doseq [v variants]
        ;; transact each with a DIVERGENT dtstart — the only excluded axis — to
        ;; prove dtstart divergence alone never forces a collapse either way.
        (create-schedule! (assoc v :mm.schedule/dtstart (Date/from (Instant/now)))))
      (is (= 5 (schedule-count (d/db *conn*)))
          "5 create-distinct schedules (each differs on one non-target axis)")
      ;; Dry-run: nothing would collapse.
      (let [{:keys [would-prune groups]} (db/prune-duplicate-schedules! *uri*)]
        (is (= 0 would-prune) "dry-run: no group has >1 member → 0 would-prune")
        (is (empty? groups) "no collapsing groups reported"))
      ;; Apply: still nothing pruned, all 5 survive.
      (let [{:keys [pruned]} (db/prune-duplicate-schedules! *uri* :apply? true)]
        (is (= 0 pruned) "apply prune retracts nothing — every axis is distinct")
        (is (= 5 (schedule-count (d/db *conn*)))
            "all 5 semantically-distinct schedules survive the prune")))))

(deftest same-non-target-axes-still-collapse
  (testing "Positive control: schedules IDENTICAL on every keyed slot (including
            the newly-added ones) but differing ONLY on dtstart still collapse —
            widening the key did not break the intended upsert for true dupes."
    (let [props (assoc base-until-props
                       :mm.schedule/until          (iso->date "2040-01-01T00:00:00Z")
                       :mm.schedule/misfire-policy :misfire/fire-once-now
                       :mm.schedule/concurrency    :concurrency/forbid)]
      (dotimes [_ 8]
        (create-schedule! (assoc props :mm.schedule/dtstart (Date/from (Instant/now)))))
      (is (= 1 (schedule-count (d/db *conn*)))
          "8 creates identical on every keyed slot upsert to 1 (dtstart excluded)"))))

(deftest idented-target-eid-vs-keyword-keys-identically
  (testing "must-fix #2 — an idented target passed as its RAW NUMERIC EID keys
            identically to the same target passed as its :db/ident KEYWORD, so a
            targeted create is idempotent regardless of the target's passed form
            (the create-key is canonicalized to match the prune-key's read-back
            :db/ident rendering)."
    (system/seed-system-jobs!)
    (let [eid (:db/id (d/entity (d/db *conn*) :sandbar.system/db-stats-job))
          base {:mm.schedule/recurrence "FREQ=DAILY" :mm.schedule/timezone "UTC"}
          before (schedule-count (d/db *conn*))]
      (is (some? eid) "resolved the system target's numeric eid")
      ;; Create with the KEYWORD target, then with the RAW EID target.
      (create-schedule! (assoc base :mm.schedule/target :sandbar.system/db-stats-job
                               :mm.schedule/dtstart (Date/from (Instant/now))))
      (create-schedule! (assoc base :mm.schedule/target eid
                               :mm.schedule/dtstart (Date/from (Instant/now))))
      (is (= (inc before) (schedule-count (d/db *conn*)))
          "eid-form and keyword-form creates UPSERT onto ONE row (added exactly 1)"))))

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

;; NB: prune-duplicate-schedules! is now a GATED migration (W3.B REVISE
;; must-fix #3) — DRY-RUN by default, mutation ONLY under :apply? true, and it
;; returns a REPORT MAP ({:mode … :pruned/:would-prune n :groups […]}), not a
;; bare int.  The apply-mode tests pass :apply? true and read :pruned.

(deftest prune-heals-accumulated-duplicates
  (testing "prune-duplicate-schedules! (apply mode) collapses target-less
            duplicates that differ ONLY by dtstart down to one, restoring a
            clean count."
    (inject-legacy-duplicate-schedules! 25)
    (is (= 25 (schedule-count (d/db *conn*))) "25 legacy dupes injected")
    (let [{:keys [pruned mode]} (db/prune-duplicate-schedules! *uri* :apply? true)]
      (is (= :apply mode) "explicit :apply? true selects mutation mode")
      (is (= 24 pruned) "returns the count of pruned duplicates (25 - 1 survivor)")
      (is (= 1 (schedule-count (d/db *conn*)))
          "collapsed to the single canonical survivor")
      (is (= 1 (targetless-count (d/db *conn*)))
          "the survivor is the target-less logical schedule"))))

(deftest prune-keeps-idented-survivor-so-create-stays-idempotent
  (testing "MIXED-GROUP guard (W3.B REVISE round-2 must-fix): when a collapsing
            group holds legacy IDENTLESS dups (lower eids — they predate the fix)
            alongside a post-fix content-key-IDENTED create-path row (higher eid),
            prune MUST keep the idented row as survivor.  A bare lowest-eid
            tiebreak would retain the identless legacy row and retract the idented
            one — then the next create-path create APPENDS instead of upserting,
            reintroducing the very proliferation W3.B exists to kill.  A count-only
            check passes either way; this test locks the survivor's :db/ident AND
            post-heal create idempotency."
    ;; 5 legacy identless dups first (lower eids), then a create-path create that
    ;; shares the SAME content-key (target-less FREQ=HOURLY/UTC/fire-once-now) and
    ;; mints an IDENTED row at a higher eid.
    (inject-legacy-duplicate-schedules! 5)
    (create-schedule! (untargeted-props))
    (is (= 6 (schedule-count (d/db *conn*))) "5 legacy + 1 idented create = 6")
    (is (= 5 (:pruned (db/prune-duplicate-schedules! *uri* :apply? true)))
        "5 dupes collapse onto 1 survivor")
    (is (= 1 (schedule-count (d/db *conn*))) "one survivor remains")
    ;; (a) the survivor is the IDENTED create-path row, not an identless legacy dup
    (let [survivor (d/q '[:find (pull ?e [:db/id :db/ident]) .
                          :where [?e :dt/type :mm/Schedule]] (d/db *conn*))]
      (is (some? (:db/ident survivor))
          "survivor carries a content-key :db/ident (idented-preference tiebreak)"))
    ;; (b) a further create-path create UPSERTS onto the survivor — no append.
    ;; Under the bare lowest-eid regression the survivor would be identless and
    ;; this would append, taking the count to 2.
    (create-schedule! (untargeted-props))
    (is (= 1 (schedule-count (d/db *conn*)))
        "post-heal create stays idempotent (upsert, delta 0) — proliferation
         does NOT return")))

(deftest prune-dry-run-is-default-and-non-mutating
  (testing "DEFAULT invocation is a DRY-RUN: it REPORTS the collapse plan
            (would-prune count + per-group survivor/dupe snapshots) WITHOUT
            mutating the DB.  The gate that prevents boot-time data loss."
    (inject-legacy-duplicate-schedules! 6)
    (is (= 6 (schedule-count (d/db *conn*))) "6 legacy dupes injected")
    (let [{:keys [mode would-prune groups]} (db/prune-duplicate-schedules! *uri*)]
      (is (= :dry-run mode) "default mode is dry-run (no :apply?)")
      (is (= 5 would-prune) "dry-run reports 5 would collapse (6 - 1 survivor)")
      (is (= 1 (count groups)) "one collapsing group")
      (let [{:keys [survivor dupes dropped-dtstarts]} (first groups)]
        (is (some? (:db/id survivor)) "report names the concrete survivor eid")
        (is (= 5 (count dupes)) "report enumerates the 5 dupes")
        (is (= 5 (count dropped-dtstarts))
            "report lists the per-dupe dtstart values the phase-shift discards"))
      ;; The DB is UNCHANGED — dry-run never mutates.
      (is (= 6 (schedule-count (d/db *conn*)))
          "dry-run left all 6 rows in place (no retraction)"))))

(deftest prune-is-idempotent-on-clean-db
  (testing "prune (apply) is a no-op on a DB with no duplicate schedules."
    ;; Only the 2 system schedules (distinct targets) — no duplicates.
    (system/seed-system-jobs!)
    (is (= 2 (schedule-count (d/db *conn*))))
    (is (= 0 (:pruned (db/prune-duplicate-schedules! *uri* :apply? true)))
        "clean DB → prunes nothing")
    (is (= 2 (schedule-count (d/db *conn*))) "system schedules untouched")
    ;; And running prune a SECOND time after a heal is still a no-op.
    (inject-legacy-duplicate-schedules! 5)
    (is (= 4 (:pruned (db/prune-duplicate-schedules! *uri* :apply? true)))
        "first prune heals 5→1")
    (is (= 0 (:pruned (db/prune-duplicate-schedules! *uri* :apply? true)))
        "second prune is a no-op (already healed)")))

(deftest prune-is-conservative-on-distinct-targets
  (testing "prune NEVER collapses schedules with distinct targets — only
            provable (identical on every keyed slot) duplicates merge."
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
      (let [{:keys [pruned]} (db/prune-duplicate-schedules! *uri* :apply? true)]
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
          _  (db/prune-duplicate-schedules! *uri* :apply? true)
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
