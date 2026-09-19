(ns sandbar.workflow.stale-session-sweep-test
  "Tests for the crashed-session staleness rule of the leaked-session sweep
   (`sandbar.util.workflow/close-leaked-sessions!`) — reliability sprint
   2026-09-18 item 3.4.

   Before 3.4 the sweep closed only active processes whose :mm/Session carried
   :mm.session/ended-at; a crashed session never writes one, so its process
   lived forever.  Now a :workflow/session process with no ended-at and no
   recorded activity for longer than a window (default 12h) is driven to its
   terminal through the existing transition machinery, with the reason in the
   history + :mm.session-process/failure-reason, and ended-at stamped with
   server time only once the process is terminal.

   Covers: stale :active → :session/failed with reason + ended-at; recent
   activity (any evidence slot) leaves a session alone; the ended-at kind is
   unchanged and takes precedence; dry-run transacts nothing; :opening and
   :closing take their own paths; :paused is held; other workflows are never
   candidates; window + clock are configurable; the activity definition is the
   max over all evidence."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]
            [sandbar.util.workflow :as wf]
            ;; on-fail / on-pause-maintenance effects are resolved by symbol
            ;; from the :workflow/session transitions — keep the ns loaded.
            [sandbar.workflow.session])
  (:import [java.util Date]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "stale-session-sweep-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private hour-ms 3600000)

(defn- hours-ago ^Date [h]
  (Date. (- (System/currentTimeMillis) (long (* h hour-ms)))))

(defn- plus-hours ^Date [^Date d h]
  (Date. (+ (.getTime d) (long (* h hour-ms)))))

(defn- make-session!
  "A minimal :mm/Session memorial whose every temporal slot is `started-at`."
  [^Date started-at]
  (dt/make :mm/Session
           {:mm.memory/rel-path     (str "sessions/stale_sweep_test_" (System/nanoTime) ".md")
            :mm.memory/name         "stale-sweep test session"
            :mm.memory/memory-type  :session
            :mm.memory/scope        :project
            :mm.session/started-at  started-at
            :mm.memory/created      started-at
            :mm.memory/last-touched started-at}
           {:validate? false}))

(defn- backdate-process!
  "Set the process's :workflow/started-at and every history timestamp to `t`
   (start-process! / transition! stamp wall-clock now; tests need the past)."
  [pid ^Date t]
  (let [history (wf/get-process-history (wf/find-process pid))]
    @(d/transact (db/conn)
                 (into [[:db/add pid :workflow/started-at t]]
                       (map (fn [h] [:db/add (:db/id h) :workflow/history-timestamp t]))
                       history))))

(defn- start-session-process!
  "Create an :mm/Session started at `started-at`, start a :workflow/session
   process on it, apply `transitions` ([name reason] pairs), then backdate
   the process + its history to `started-at`.  Returns [pid session-eid]."
  [{:keys [started-at transitions] :or {transitions []}}]
  (let [session (make-session! started-at)
        process (wf/start-process! :workflow/session session)
        pid     (:db/id process)]
    (doseq [[t reason] transitions]
      (wf/transition! (wf/find-process pid) t :reason reason))
    (backdate-process! pid started-at)
    [pid (:db/id session)]))

(defn- state-of [pid]
  (:workflow/state-name (wf/get-current-state (wf/find-process pid))))

(defn- ended-at-of [sid]
  (:mm.session/ended-at (db/entity sid)))

(defn- last-history-reason [pid]
  (:reason (last (wf/get-readable-history (wf/find-process pid)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The rule
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stale-active-session-is-failed-with-reason-and-ended-at
  (testing "an :active session silent for 13h (> 12h default) is closed as stale: :session/failed, reason recorded, ended-at = server time"
    (let [[pid sid] (start-session-process! {:started-at  (hours-ago 13)
                                             :transitions [[:session/start nil]]})]
      (is (= :session/active (state-of pid)) "precondition: active")
      (is (nil? (ended-at-of sid)) "precondition: a crashed session never wrote ended-at")
      (let [before  (Date.)
            summary (wf/close-leaked-sessions!)
            after   (Date.)]
        (is (= :apply (:mode summary)))
        (is (= 1 (:stale summary)) "one stale candidate")
        (is (zero? (:leaks summary)) "not an ended-but-open leak")
        (is (= [pid] (:closed summary)))
        (is (empty? (:skipped summary)))
        (is (= :session/failed (state-of pid))
            "stale active → :session/failed via the existing :session/fail transition")
        (is (wf/process-in-terminal-state? (wf/find-process pid)))
        (let [p (wf/find-process pid)]
          (is (re-find #"closed as stale by the leaked-session sweep"
                       (:mm.session-process/failure-reason p))
              "on-fail effect recorded the stale reason in the failure-reason slot")
          (is (some? (:mm.session-process/failure-instant p))))
        (is (re-find #"closed as stale by the leaked-session sweep" (last-history-reason pid))
            "the history entry carries the stale reason")
        (let [ended (ended-at-of sid)]
          (is (some? ended) "ended-at stamped")
          (is (<= (.getTime before) (.getTime ended) (.getTime after))
              "ended-at is server time at the sweep, not the last-activity instant"))
        (let [r (first (:receipts summary))]
          (is (= :stale (:kind r)))
          (is (= :session/active (:state r)))
          (is (= :session/failed (:terminal-state r)))
          (is (= (ended-at-of sid) (:ended-at r)))
          (is (nil? (:ended-at-error r))))))))

(deftest recently-active-session-is-left-alone
  (testing "a process started 13h ago whose latest history entry is fresh is NOT stale (activity is the max over all evidence)"
    (let [[pid sid] (start-session-process! {:started-at (hours-ago 13)})]
      ;; a fresh transition = fresh history timestamp = recent activity
      (wf/transition! (wf/find-process pid) :session/start)
      (let [la (wf/session-process-last-activity (wf/find-process pid))]
        (is (< (- (System/currentTimeMillis) (.getTime ^Date la)) hour-ms)
            "last activity is the fresh :session/start"))
      (let [summary (wf/close-leaked-sessions!)]
        (is (zero? (:stale summary)))
        (is (empty? (:closed summary)))
        (is (= :session/active (state-of pid)))
        (is (nil? (ended-at-of sid))))))
  (testing "a session started 1h ago is not stale"
    (let [[pid sid] (start-session-process! {:started-at  (hours-ago 1)
                                             :transitions [[:session/start nil]]})
          summary   (wf/close-leaked-sessions!)]
      (is (not (some #{pid} (:closed summary))))
      (is (= :session/active (state-of pid)))
      (is (nil? (ended-at-of sid))))))

(deftest ended-but-open-session-behaves-as-before
  (testing "an :active process whose session carries ended-at closes via :session/close → :session/finalize to :session/closed (Bug-1 path, unchanged)"
    (let [[pid sid] (start-session-process! {:started-at  (hours-ago 1)
                                             :transitions [[:session/start nil]]})
          ended     (Date.)]
      (dt/update-entity! sid {:mm.session/ended-at ended})
      (let [summary (wf/close-leaked-sessions!)]
        (is (= 1 (:leaks summary)))
        (is (zero? (:stale summary)))
        (is (= [pid] (:closed summary)))
        (is (= :session/closed (state-of pid)))
        (is (= ended (ended-at-of sid)) "ended-at is not re-stamped for the :ended kind")
        (let [r (first (:receipts summary))]
          (is (= :ended (:kind r)))
          (is (= :session/closed (:terminal-state r))))
        (is (re-find #"Bug-1" (last-history-reason pid)) "the original reconcile reason"))
      (is (empty? (:closed (wf/close-leaked-sessions!))) "idempotent")))
  (testing "ended-at takes precedence over staleness: a 13h-silent session WITH ended-at closes as :ended → :session/closed, not :failed"
    (let [[pid sid] (start-session-process! {:started-at  (hours-ago 13)
                                             :transitions [[:session/start nil]]})]
      (dt/update-entity! sid {:mm.session/ended-at (Date.)})
      (let [plan (wf/leaked-session-plan)
            c    (first (filter #(= pid (:process-id %)) (:candidates plan)))]
        (is (= :ended (:kind c))))
      (wf/close-leaked-sessions!)
      (is (= :session/closed (state-of pid))))))

(deftest dry-run-reports-without-mutating
  (let [[pid sid] (start-session-process! {:started-at  (hours-ago 13)
                                           :transitions [[:session/start nil]]})
        dry       (wf/close-leaked-sessions! :dry-run? true)]
    (is (= :dry-run (:mode dry)))
    (is (= [pid] (:would-close dry)))
    (is (= 1 (:stale dry)))
    (is (empty? (:closed dry)))
    (is (empty? (:receipts dry)))
    (is (= :session/active (state-of pid)) "dry-run transacts nothing")
    (is (nil? (ended-at-of sid)))
    (let [c (first (:candidates dry))]
      (is (= :stale (:kind c)))
      (is (= :session/active (:state c)))
      (is (= [:session/fail] (:path c)))
      (is (= sid (:subject-id c)))
      (is (> (:idle-ms c) (* 12 hour-ms)))
      (is (re-find #"idle 1[23]h" (:reason c)))
      (is (re-find #"window 12h00m" (:reason c))))
    (let [applied (wf/close-leaked-sessions!)]
      (is (= :apply (:mode applied)))
      (is (= [pid] (:closed applied)))
      (is (= :session/failed (state-of pid))))
    (let [again (wf/close-leaked-sessions!)]
      (is (empty? (:closed again)) "idempotent — nothing left to close")
      (is (zero? (:stale again)))
      (is (zero? (:leaks again))))))

(deftest stale-opening-and-closing-processes-take-their-own-paths
  (let [[opening-pid opening-sid] (start-session-process! {:started-at (hours-ago 13)})
        [closing-pid closing-sid] (start-session-process! {:started-at  (hours-ago 13)
                                                           :transitions [[:session/start nil]
                                                                         [:session/close nil]]})]
    (is (= :session/opening (state-of opening-pid)))
    (is (= :session/closing (state-of closing-pid)))
    (let [summary (wf/close-leaked-sessions!)]
      (is (= 2 (:stale summary)))
      (is (= #{opening-pid closing-pid} (set (:closed summary))))
      (is (empty? (:skipped summary)))
      (is (= :session/failed (state-of opening-pid))
          "opening → :session/fail-from-opening (crashed during the open ceremony)")
      (is (= :session/closed (state-of closing-pid))
          "closing → :session/finalize (the session had already asked to close)")
      (is (re-find #"stale" (:mm.session-process/failure-reason (wf/find-process opening-pid))))
      (is (re-find #"stale" (last-history-reason closing-pid)))
      (is (some? (ended-at-of opening-sid)))
      (is (some? (ended-at-of closing-sid))))))

(deftest stale-paused-session-is-held-not-closed
  (let [[pid sid] (start-session-process! {:started-at  (hours-ago 13)
                                           :transitions [[:session/start nil]
                                                         [:session/pause-maintenance "maintenance"]]})]
    (is (= :session/paused (state-of pid)) "precondition: maintenance-mode pause")
    (let [summary (wf/close-leaked-sessions!)]
      (is (= 1 (:held summary)))
      (is (zero? (:stale summary)))
      (is (empty? (:closed summary)))
      (is (= :session/paused (state-of pid)) "an operator hold is never auto-failed")
      (is (nil? (ended-at-of sid)))
      (let [c (first (filter :held? (:candidates summary)))]
        (is (= pid (:process-id c)))
        (is (= :stale (:kind c)))
        (is (re-find #"maintenance-mode hold" (:note c)))))))

(deftest processes-of-other-workflows-are-never-stale-candidates
  (wf/define-workflow! :workflow/stale-sweep-other
    {:states      [{:name :other/pending :label "Pending" :initial? true}
                   {:name :other/done :label "Done" :terminal? true :terminal-kind :success}]
     :transitions [{:name :finish :from :other/pending :to :other/done}]})
  (let [subject (make-session! (hours-ago 13))     ; even an old :mm/Session subject
        process (wf/start-process! :workflow/stale-sweep-other subject)
        pid     (:db/id process)]
    (backdate-process! pid (hours-ago 13))
    (let [summary (wf/close-leaked-sessions!)]
      (is (= 1 (:scanned summary)))
      (is (zero? (:stale summary)))
      (is (empty? (:candidates summary)))
      (is (empty? (:closed summary)))
      (is (= :other/pending (state-of pid)) "the rule keys off :workflow/session, not the subject")
      (is (nil? (:mm.session/ended-at (db/entity (:db/id subject))))))))

(deftest window-and-clock-are-configurable
  (let [[pid sid] (start-session-process! {:started-at  (hours-ago 13)
                                           :transitions [[:session/start nil]]})]
    (testing "the default window is twelve hours"
      (is (= (* 12 hour-ms) wf/default-stale-session-window-ms))
      (is (= (* 12 hour-ms) (:window-ms (wf/leaked-session-plan)))))
    (testing "a 24h window leaves a 13h-silent session alone"
      (let [s (wf/close-leaked-sessions! :stale-after-ms (* 24 hour-ms))]
        (is (= (* 24 hour-ms) (:window-ms s)))
        (is (zero? (:stale s)))
        (is (= :session/active (state-of pid)))))
    (testing "an injected :now moves the verdict and is what ended-at is stamped with"
      (let [now (plus-hours (Date.) 12)             ; idle ≈ 25h > 24h window
            s   (wf/close-leaked-sessions! :stale-after-ms (* 24 hour-ms) :now now)]
        (is (= now (:now s)))
        (is (= [pid] (:closed s)))
        (is (= :session/failed (state-of pid)))
        (is (= now (ended-at-of sid)))))))

(deftest last-activity-is-the-max-over-all-evidence
  (let [t0        (hours-ago 13)
        [pid sid] (start-session-process! {:started-at  t0
                                           :transitions [[:session/start nil]]})]
    (is (= t0 (wf/session-process-last-activity (wf/find-process pid)))
        "every evidence slot backdated to t0 ⇒ last activity is t0")
    (let [t1 (hours-ago 2)]
      @(d/transact (db/conn) [[:db/add sid :mm.memory/last-touched t1]])
      (is (= t1 (wf/session-process-last-activity (wf/find-process pid)))
          "a touch of the session memorial counts as activity"))
    (let [t2 (hours-ago 1)
          h  (last (wf/get-process-history (wf/find-process pid)))]
      @(d/transact (db/conn) [[:db/add (:db/id h) :workflow/history-timestamp t2]])
      (is (= t2 (wf/session-process-last-activity (wf/find-process pid)))
          "a later history entry counts as activity"))
    (is (zero? (:stale (wf/leaked-session-plan)))
        "and a session active 1h ago is not stale")))
