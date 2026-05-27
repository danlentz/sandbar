(ns sandbar.schedule.dispatcher-test
  "γ.2 Step 4 — dispatcher tests.

   Coverage:
   - Pure helpers (no DB):  queue ops, misfire detection
   - DB-aware helpers:      resolve-schedule, compute-next-fire-at, add/remove-schedule!
   - Event emission:        fire-schedule! → :mm.event/Scheduled subscriber
   - Lifecycle:             start!/stop!/pause!/resume! state transitions
   - Recovery:              recompute-queue-from-db!
   - Integration smoke:     start! → fire-loop fires → subscriber observes → stop!

   The fire-loop is the only path that uses Thread/sleep + .interrupt; tests
   for it use controllable-clock binding + a very-short scheduled time so the
   loop fires within ~1s of test invocation.  Tests that don't exercise the
   loop use the public helpers directly + verify state mutations + event
   emissions in-process.

   Per `~/.claude/plans/golden-squishing-flamingo.md` Step 4."
  (:require [clojure.test :refer :all]
            [sandbar.schedule.state      :as state]
            [sandbar.schedule.dispatcher :as dispatcher]
            [sandbar.schedule.recurrence :as recurrence]
            [sandbar.event               :as event]
            [sandbar.db.datatype         :as dt]
            [sandbar.test-util           :as tu])
  (:import [java.time Instant Duration]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(use-fixtures :each (tu/make-test-db-fixture {:test-name "dispatcher-test"
                                              :auth?     false}))

(use-fixtures :each
  (fn [f]
    ;; Scope a fresh scheduler-state per test so concurrent runs don't
    ;; share queue/handler-pool/fire-thread.  Per Q.γ.2 atom-inside-
    ;; dyn-var idiom — binding gives per-test isolation.
    (binding [state/*scheduler-state* (atom (state/initial-state))]
      (event/clear!)
      (try (f)
           (finally
             ;; Ensure no stray threads survive into the next test.
             (try (dispatcher/stop! {:drain-timeout-ms 500})
                  (catch Exception _ nil))
             (event/clear!))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — schedule construction + clock binding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reference-dtstart
  "Anchor instant for test schedules — Mon 2026-05-27T15:00:00Z."
  (Instant/parse "2026-05-27T15:00:00Z"))

(defn- ^java.util.Date inst->date
  "Coerce a java.time.Instant to java.util.Date for Datomic
   `:db.type/instant` slot values."
  [^Instant inst]
  (when inst (java.util.Date/from inst)))

(defn- make-schedule!
  "Persist a `:mm/Schedule` entity via dt/make + return its eid.

   `:rrule`             — RFC 5545 RRULE string (REQUIRED)
   `:dtstart`           — DTSTART anchor instant (default: reference-dtstart)
                          Coerced to java.util.Date for Datomic.
   `:timezone`          — IANA TZ (default: \"UTC\")
   `:misfire-policy`    — `:misfire/fire-once-now` (default) | `:misfire/ignore` | `:misfire/reschedule`
   `:exdates`           — vec of Instants to exclude (coerced to Dates)
   `:until`             — UNTIL terminator instant (coerced to Date)"
  [{:keys [rrule dtstart timezone misfire-policy exdates until]
    :or   {dtstart        reference-dtstart
           timezone       "UTC"
           misfire-policy :misfire/fire-once-now}}]
  (let [base-slots {:mm.schedule/recurrence     rrule
                    :mm.schedule/dtstart        (inst->date dtstart)
                    :mm.schedule/timezone       timezone
                    :mm.schedule/misfire-policy misfire-policy}
        slots (cond-> base-slots
                exdates (assoc :mm.schedule/exdates (mapv inst->date exdates))
                until   (assoc :mm.schedule/until   (inst->date until)))
        ent   (dt/make :mm/Schedule slots {:validate? false})]
    (:db/id ent)))

(defmacro with-fixed-now
  "Bind dispatcher's clock seam to a constant Instant for the body."
  [^Instant inst & body]
  `(binding [dispatcher/*now-fn* (fn [] ~inst)]
     ~@body))

(defn- collect-events!
  "Subscribe a collector handler to `event-class`; return a 2-tuple
   `[!events unsubscribe]` — deref `!events` to read captured events;
   call `unsubscribe` to remove the handler."
  [event-class]
  (let [!events (atom [])
        h       (fn [ev] (swap! !events conj ev))]
    (event/subscribe! event-class h)
    [!events (fn [] (event/unsubscribe! event-class h))]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schedule resolution + recurrence translation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest compute-next-fire-at-honors-recurrence
  (testing "Hourly RRULE produces next fire 1h after anchor"
    (let [eid      (make-schedule! {:rrule "FREQ=HOURLY;INTERVAL=1"})
          schedule (#'dispatcher/resolve-schedule eid)
          base     reference-dtstart
          ;; Anchor "now" slightly after dtstart so the next fire is dtstart+1h
          now-inst (.plusSeconds base 60)
          next-at  (#'dispatcher/compute-next-fire-at schedule now-inst)]
      (is (= (.plusSeconds base 3600) next-at)
          "Next fire is one hour after the dtstart anchor"))))

(deftest compute-next-fire-at-honors-exdates
  (testing "Exdate excludes a candidate fire-instant"
    (let [base     reference-dtstart
          excluded (.plusSeconds base 3600)
          included (.plusSeconds base 7200)
          eid      (make-schedule! {:rrule   "FREQ=HOURLY"
                                    :exdates [excluded]})
          schedule (#'dispatcher/resolve-schedule eid)
          next-at  (#'dispatcher/compute-next-fire-at schedule
                                                       (.plusSeconds base 60))]
      (is (= included next-at)
          "Excluded instant is skipped; next non-exdate is returned"))))

(deftest compute-next-fire-at-returns-nil-on-terminated-rrule
  (testing "RRULE with COUNT=1 returns nil after the single fire"
    (let [eid      (make-schedule! {:rrule "FREQ=HOURLY;COUNT=1"})
          schedule (#'dispatcher/resolve-schedule eid)
          past-end (.plusSeconds reference-dtstart 7200)
          next-at  (#'dispatcher/compute-next-fire-at schedule past-end)]
      (is (nil? next-at)
          "No future fire when COUNT terminator is exhausted"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; add-schedule! / remove-schedule!
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest add-schedule-inserts-queue-entry
  (testing "add-schedule! returns next-fire-at + the queue has one entry"
    (with-fixed-now (.minusSeconds reference-dtstart 60)
      (let [eid     (make-schedule! {:rrule "FREQ=HOURLY"})
            next-at (dispatcher/add-schedule! eid)
            q       (dispatcher/snapshot-queue)]
        (is (= reference-dtstart next-at) "Next fire is dtstart (the first occurrence)")
        (is (= 1 (count q))                "Queue has exactly one entry")
        (is (= [reference-dtstart eid] (first q))
            "Entry is [next-fire-at schedule-eid]")))))

(deftest add-schedule-is-idempotent
  (testing "Re-adding the same schedule replaces (not duplicates) its entry"
    (with-fixed-now (.minusSeconds reference-dtstart 60)
      (let [eid (make-schedule! {:rrule "FREQ=HOURLY"})]
        (dispatcher/add-schedule! eid)
        (dispatcher/add-schedule! eid)
        (let [q (dispatcher/snapshot-queue)]
          (is (= 1 (count q))
              "Queue has exactly one entry even after two adds"))))))

(deftest add-schedule-returns-nil-for-missing-eid
  (testing "Missing schedule-eid yields nil + queue unchanged"
    (let [pre-q   (dispatcher/snapshot-queue)
          result  (dispatcher/add-schedule! 99999999999999)
          post-q  (dispatcher/snapshot-queue)]
      (is (nil? result) "Returns nil when schedule eid doesn't resolve")
      (is (= pre-q post-q) "Queue is unchanged"))))

(deftest add-schedule-returns-nil-for-terminated-rrule
  (testing "Schedule with no future fires returns nil + queue unchanged"
    (with-fixed-now (.plusSeconds reference-dtstart 7200)
      (let [eid    (make-schedule! {:rrule "FREQ=HOURLY;COUNT=1"})
            result (dispatcher/add-schedule! eid)
            q      (dispatcher/snapshot-queue)]
        (is (nil? result)        "No future fires → nil return")
        (is (zero? (count q))    "Queue is empty")))))

(deftest remove-schedule-drops-entry
  (testing "remove-schedule! drops the entry from the queue"
    (with-fixed-now (.minusSeconds reference-dtstart 60)
      (let [eid (make-schedule! {:rrule "FREQ=HOURLY"})]
        (dispatcher/add-schedule! eid)
        (is (= 1 (count (dispatcher/snapshot-queue))) "Pre-condition: queued")
        (dispatcher/remove-schedule! eid)
        (is (zero? (count (dispatcher/snapshot-queue)))
            "Post-condition: removed")))))

(deftest remove-schedule-is-idempotent
  (testing "Removing a non-queued schedule is a no-op"
    (is (nil? (dispatcher/remove-schedule! 99999999999999))
        "Removing missing entry doesn't throw")
    (is (zero? (count (dispatcher/snapshot-queue)))
        "Queue remains empty")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; fire-schedule! — event emission + misfire policy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest fire-schedule-emits-scheduled-event-on-clean-fire
  (testing "Clean fire (fire-at ≈ now) emits one :mm.event/Scheduled event"
    (let [[!events unsub] (collect-events! :mm.event/Scheduled)
          eid             (make-schedule! {:rrule "FREQ=HOURLY"})]
      (try
        (with-fixed-now reference-dtstart
          (let [result (dispatcher/fire-schedule! reference-dtstart eid)]
            (is (= :fired result) "fire-schedule! returns :fired")
            (is (= 1 (count @!events)) "Exactly one event emitted")
            (let [ev (first @!events)]
              (is (= :mm.event/Scheduled (:event/class ev))     "Event has correct class")
              (is (= eid (:mm.schedule-event/schedule ev))     "Carries schedule ref")
              (is (= false (:misfire? ev))                      "Marked as clean fire"))))
        (finally (unsub))))))

(deftest fire-schedule-detects-misfire-and-fires-once-now
  (testing ":misfire/fire-once-now (default): emits one catch-up event"
    (let [[!events unsub] (collect-events! :mm.event/Scheduled)
          eid             (make-schedule! {:rrule "FREQ=HOURLY"})
          ;; Schedule "now" 60 seconds AFTER scheduled fire-at — qualifies as misfire (>5s)
          now-inst        (.plusSeconds reference-dtstart 60)]
      (try
        (with-fixed-now now-inst
          (let [result (dispatcher/fire-schedule! reference-dtstart eid)]
            (is (= :fired result) "Fires per misfire-once-now policy")
            (is (= 1 (count @!events)) "One catch-up event")
            (let [ev (first @!events)]
              (is (= true (:misfire? ev))                         "Marked as misfire")
              (is (= :misfire/fire-once-now (:misfire-policy ev)) "Policy in event payload"))))
        (finally (unsub))))))

(deftest fire-schedule-honors-misfire-ignore-policy
  (testing ":misfire/ignore: skips the fire; emits NO event"
    (let [[!events unsub] (collect-events! :mm.event/Scheduled)
          eid             (make-schedule! {:rrule          "FREQ=HOURLY"
                                           :misfire-policy :misfire/ignore})
          now-inst        (.plusSeconds reference-dtstart 60)]
      (try
        (with-fixed-now now-inst
          (let [result (dispatcher/fire-schedule! reference-dtstart eid)]
            (is (= :skipped result)  "Returns :skipped")
            (is (empty? @!events)    "NO event emitted")))
        (finally (unsub))))))

(deftest fire-schedule-rejects-stale-eid
  (testing "fire-schedule! on a retracted schedule returns :rejected + emits no event"
    (let [[!events unsub] (collect-events! :mm.event/Scheduled)]
      (try
        (with-fixed-now reference-dtstart
          (let [result (dispatcher/fire-schedule! reference-dtstart 99999999999999)]
            (is (= :rejected result) "Returns :rejected")
            (is (empty? @!events)    "NO event emitted")))
        (finally (unsub))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; advance-queue-after-fire! semantics
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest advance-queue-after-fire-conjes-next-fire-at
  (testing "After firing, the queue's entry advances to the next recurrence"
    (with-fixed-now reference-dtstart
      (let [eid (make-schedule! {:rrule "FREQ=HOURLY"})]
        (dispatcher/add-schedule! eid)
        (#'dispatcher/advance-queue-after-fire!
          reference-dtstart eid :misfire/fire-once-now)
        (let [q (dispatcher/snapshot-queue)]
          (is (= 1 (count q))                            "Still one entry")
          (is (= (.plusSeconds reference-dtstart 3600) (ffirst q))
              "Entry is now the NEXT hourly fire (+3600s)"))))))

(deftest advance-queue-after-fire-drops-entry-on-terminated-rrule
  (testing "When RRULE has no further fires, entry is dropped"
    (with-fixed-now reference-dtstart
      (let [eid (make-schedule! {:rrule "FREQ=HOURLY;COUNT=1"})]
        (dispatcher/add-schedule! eid)
        (#'dispatcher/advance-queue-after-fire!
          reference-dtstart eid :misfire/fire-once-now)
        (is (zero? (count (dispatcher/snapshot-queue)))
            "Queue has no entry for a single-fire schedule after its fire")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle — start! / stop! / pause! / resume!
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest start-transitions-to-active-and-allocates-resources
  (testing "start! transitions :inactive → :active + creates fire-thread + handler-pool"
    (is (= :scheduler.state/inactive (:state (state/snapshot)))
        "Pre: inactive")
    (let [result (dispatcher/start!)]
      (is (= :started result)                                "Returns :started")
      (is (= :scheduler.state/active (:state (state/snapshot)))
          "Post: active")
      (is (some? (:fire-thread (state/snapshot)))           "Fire-thread allocated")
      (is (some? (:handler-pool (state/snapshot)))          "Handler-pool allocated"))))

(deftest start-is-idempotent
  (testing "Calling start! when already active is a no-op"
    (dispatcher/start!)
    (is (= :already-active (dispatcher/start!))
        "Second start! returns :already-active")
    (is (= :scheduler.state/active (:state (state/snapshot)))
        "State remains active")))

(deftest stop-drains-and-transitions-to-inactive
  (testing "stop! transitions :active → :draining → :inactive + nulls fire-thread + pool"
    (dispatcher/start!)
    (is (= :stopped (dispatcher/stop!))                     "Returns :stopped")
    (is (= :scheduler.state/inactive (:state (state/snapshot)))
        "Post: inactive")
    (is (nil? (:fire-thread (state/snapshot)))             "Fire-thread cleared")
    (is (nil? (:handler-pool (state/snapshot)))            "Handler-pool cleared")))

(deftest stop-when-already-inactive-is-no-op
  (testing "Calling stop! on inactive scheduler is idempotent"
    (is (= :already-inactive (dispatcher/stop!))
        "Returns :already-inactive when no scheduler is running")))

(deftest pause-and-resume-preserve-queue
  (testing "Pause preserves queue + handler-pool; resume re-allocates fire-thread"
    (with-fixed-now (.minusSeconds reference-dtstart 60)
      (let [eid (make-schedule! {:rrule "FREQ=HOURLY"})]
        (dispatcher/add-schedule! eid)
        (dispatcher/start!)
        (let [queued-pre (count (dispatcher/snapshot-queue))]
          (is (pos? queued-pre) "Queue has at least one entry pre-pause")
          (is (= :paused (dispatcher/pause!)))
          (is (= :scheduler.state/paused (:state (state/snapshot))))
          (is (= queued-pre (count (dispatcher/snapshot-queue)))
              "Queue preserved across pause")
          (is (= :resumed (dispatcher/resume!)))
          (is (= :scheduler.state/active (:state (state/snapshot))))
          (is (some? (:fire-thread (state/snapshot)))
              "New fire-thread allocated on resume"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recovery
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest recompute-queue-from-db-rebuilds-from-all-live-schedules
  (testing "recompute-queue-from-db! walks all :mm/Schedule entities + queues each;
            COUNT-terminated schedules are excluded"
    ;; The test DB may already contain pre-seeded :mm/Schedule examples loaded
    ;; via the required-schema fixture.  Test is RELATIVE: pre-count + 2 valid
    ;; - 1 terminated (which adds 0 to queue) = pre-count + 2 added.
    (with-fixed-now (.minusSeconds reference-dtstart 60)
      (let [pre-count (dispatcher/recompute-queue-from-db!)
            _  (make-schedule! {:rrule "FREQ=HOURLY"})
            _  (make-schedule! {:rrule "FREQ=DAILY"})
            _  (make-schedule! {:rrule   "FREQ=HOURLY;COUNT=1"
                                ;; in the past relative to "now" so no future fire
                                :dtstart (Instant/parse "2026-05-26T15:00:00Z")})
            post-count (dispatcher/recompute-queue-from-db!)]
        (is (= (+ pre-count 2) post-count)
            "Two firing schedules added; terminated COUNT=1 schedule excluded")
        (is (= post-count (count (dispatcher/snapshot-queue))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration smoke — start! → fire-loop fires → subscriber observes → stop!
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Real fire-thread + real Thread/sleep (bounded to ~1.5s).  Verifies end-
;; to-end that a started dispatcher with one schedule whose next-fire-at
;; is ~500ms from now actually emits a :mm.event/Scheduled event within
;; bounded wall-time.

(deftest integration-fires-emit-events
  (testing "Real fire-thread fires a near-term schedule + emits :mm.event/Scheduled"
    (let [[!events unsub] (collect-events! :mm.event/Scheduled)
          ;; Build a schedule whose first fire is ~600ms from now.  Use a
          ;; secondly RRULE anchored at "now + 1s" so the first fire is bounded.
          now-real        (Instant/now)
          dtstart         (.plusMillis now-real 600)
          eid             (make-schedule! {:rrule   "FREQ=SECONDLY;COUNT=1"
                                           :dtstart dtstart})]
      (try
        (dispatcher/add-schedule! eid)
        (dispatcher/start!)
        ;; Bounded wait: poll up to 3s for the event to appear.
        (let [deadline (.plusMillis (Instant/now) 3000)]
          (while (and (.isBefore (Instant/now) deadline)
                      (empty? @!events))
            (Thread/sleep 100)))
        (is (pos? (count @!events))
            "Within 3s wall-time the fire-thread emits the scheduled event")
        (when (pos? (count @!events))
          (let [ev (first @!events)]
            (is (= :mm.event/Scheduled (:event/class ev)))
            (is (= eid (:mm.schedule-event/schedule ev)))))
        (finally
          (dispatcher/stop! {:drain-timeout-ms 1000})
          (unsub))))))
