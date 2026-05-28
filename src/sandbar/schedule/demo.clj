(ns sandbar.schedule.demo
  "γ.5 — Demo DB-stats job fn.

   The demo job for the γ scheduler arc — fires every 15 minutes (per
   the operator-authored :mm/Schedule) and emits a :first-class
   :mm/EventLog memorial carrying substrate-count facts:

     {:memorials       <int>   ;; count of :mm/Memory instances
      :classes         <int>   ;; count of :dt/Class instances
      :properties      <int>   ;; count of :dt/Property instances
      :total-entities  <int>}  ;; aggregate across all classes

   This is the original 'release-quality integration test' of the
   metamodel-unification arc — it exercises:
     - sandbar.schedule.dispatcher (fire-thread + queue)
     - sandbar.schedule.job-dispatcher (event subscriber + Run lifecycle)
     - sandbar.event (class-hierarchical dispatch)
     - sandbar.logging (Telemere :first-class memorial-projection)
     - sandbar.db.datatype (substrate enumeration)

   per γ.1 ADR §1.4 + ~/.claude/plans/golden-squishing-flamingo.md Step 5.

   ## Operator wiring (γ.5 minimal MVP)

   The demo job is NOT auto-created at sandbar boot — operator wires
   it explicitly via the MCP verbs:

     1. Create :mm/Fn memorial pointing at this ns's `log-db-stats` fn:
        sandbar.entity.create :class :mm/Fn :slots {
          :dt.fn/source-ns        \"sandbar.schedule.demo\"
          :dt.fn/source-var       \"log-db-stats\"
          :dt.fn/installed-as     :classpath-fn
          :dt.fn/lang             :clojure
          :dt.fn/purpose          :emit-memorial
          :dt.fn/purity           :side-effects
          :dt.fn/cost-class       :cheap
          :dt.fn/status           :production
          :dt.fn/version          \"1.0.0\"
          :dt.fn/description      \"Emit :mm/EventLog with substrate counts\"
        }
     2. Create :mm/Job referencing the :mm/Fn:
        sandbar.entity.create :class :mm/Job :slots {
          :mm.job/fn <:mm/Fn eid from step 1>
        }
     3. Create :mm/Schedule targeting the :mm/Job:
        sandbar.entity.create :class :mm/Schedule :slots {
          :mm.schedule/target      <:mm/Job eid from step 2>
          :mm.schedule/recurrence  \"FREQ=MINUTELY;INTERVAL=15\"
          :mm.schedule/dtstart     <next 15-min boundary as Date>
          :mm.schedule/timezone    \"UTC\"
        }
     4. Add to scheduler queue:
        sandbar.schedule.add :schedule-eid <:mm/Schedule eid from step 3>
     5. (Pre-conditions) sandbar.schedule.enable + sandbar.schedule.start
        if not yet active.

   A future hardening sub-arc (γ.5b or part of γ.6) may add an
   `:scheduler/jobs` config-driven autostart that loads this demo from
   a seed-edn file at sandbar.core/start time.

   ## See also

   - γ.1 ADR §1.4 (demo-job rationale):
     `:memory.decisions/gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_resolved_2026_05_27`
   - γ scheduler arc plan:
     `:memory.plans/gamma_scheduler_live_integration_arc_path_a_native_min_heap_dispatcher_demo_db_stats_job_5_mcp_verbs_pre_0_2_0_phase_gamma_sub_plan_2026_05_27`"
  (:require [sandbar.db.datatype  :as dt]
            [sandbar.logging      :as logging]
            [sandbar.reactive.queue :as reactive-queue])
  (:import [java.time Instant]
           [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Substrate enumeration

(defn db-stats
  "Return the canonical substrate-count snapshot.  Pure read; no
   logging side effect.  Composable for tests + alternate consumers
   (operator REPL inspection, health-check endpoints, etc.).

   Returns:
     {:memorials       <int>   ;; count of :mm/Memory instances
      :classes         <int>   ;; count of :dt/Class instances
      :properties      <int>   ;; count of :dt/Property instances
      :total-entities  <int>}  ;; sum of instance counts across all classes"
  []
  (let [classes        (dt/all-classes)
        properties     (dt/all-properties)
        memorials-cnt  (try (count (dt/all-instances-of :mm/Memory))
                            (catch Throwable _ 0))
        ;; total-entities: sum of instance-counts across all classes.
        ;; Some classes are abstract OR have query-time errors — those
        ;; contribute 0 (defensive per-class try/catch).
        total-entities (->> classes
                            (map (fn [c]
                                   (try (count (dt/all-instances-of c))
                                        (catch Throwable _ 0))))
                            (reduce +))]
    {:memorials      memorials-cnt
     :classes        (count classes)
     :properties     (count properties)
     :total-entities total-entities}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job handler

(defn log-db-stats
  "Demo job fn — invoked by sandbar.schedule.job-dispatcher per fire of
   the demo :mm/Schedule.

   Computes substrate-count stats via `db-stats` + emits ONE
   `:first-class` `:mm/EventLog` memorial via
   `sandbar.logging/info` carrying the stats map.  The
   `:first-class` flag routes the signal through the memorial-
   projection handler so the firing produces a durable FS-projected
   memorial under `memory/logs/`.

   Argument shape: `run-ctx` map carrying:
     {:run-eid      <numeric eid of the :mm/Run instance>
      :schedule-eid <numeric eid of the firing :mm/Schedule>
      :job-eid      <numeric eid of the :mm/Job being executed>}

   Returns the stats map (also carried in the log signal's :data slot
   for consumers that read the EventLog memorial later)."
  [run-ctx]
  (let [stats (db-stats)]
    (logging/info ::demo-db-stats
                  "Demo DB-stats fire — substrate snapshot"
                  (merge stats run-ctx)
                  :first-class)
    stats))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sibling demo job — reactive-queue health metrics

(defn log-reactive-queue-health
  "Sibling demo job fn — reactive-projection queue health snapshot.

   Same shape as `log-db-stats` but reports the 13-key health map from
   `sandbar.reactive.queue/health` (the backing fn for the
   `sandbar.reactive.health` MCP verb).  Emits ONE `:first-class`
   `:mm/EventLog` memorial per fire carrying:

     {:worker-running?        bool
      :buffer-size            int (sliding-buffer capacity)
      :dirty-entity-count     int (entities pending projection)
      :oldest-pending-age-ms  int or nil
      :enqueue-total          int (cumulative since startup)
      :drain-total            int
      :coalesce-total         int (per-entity coalesce events)
      :sink-error-total       int (sink-fn failures)
      :registered-sinks       int
      :saturated?             bool (oldest-pending > threshold)
      :startup-instant        java.time.Instant
      :last-enqueue-instant   java.time.Instant or nil
      :last-drain-instant     java.time.Instant or nil}

   Argument shape + return contract identical to `log-db-stats`."
  [run-ctx]
  (let [health (reactive-queue/health)]
    (logging/info ::demo-reactive-queue-health
                  "Demo reactive-queue health snapshot"
                  (merge health run-ctx)
                  :first-class)
    health))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; γ.5b — default demo-job seeding (boot-time, idempotent)
;;
;; Per Dan-directive 2026-05-28 ('it'd be fine if sandbar by default had
;; these two jobs scheduled') — AMENDS the Q.γ.5 opt-in-safety ratification
;; for the demo-jobs case specifically.  sandbar.core/start invokes
;; seed-demo-jobs! when config's :scheduler/seed-demo-jobs? is true, then
;; adds the returned schedule-idents to the live scheduler queue.
;;
;; Idempotency: each entity carries a stable :db/ident, so re-seeding at
;; every boot UPSERTS (Datomic :db/ident semantics) rather than creating
;; duplicates.  :mm.schedule/dtstart is re-anchored to (now) on each seed
;; so the first fire is the next recurrence-boundary after THIS boot.
;;
;; The demo entities are :mm/Spec subtypes (Fn/Job/Schedule) with NO
;; :mm.memory/rel-path — so the reactive-projection fs-sink skips them
;; (they're operational substrate, not corpus-FS-projected content).

(def demo-fn-specs
  "The 2 demo :mm/Fn entity specs (stable idents for upsert)."
  [{:db/ident           :sandbar.demo/log-db-stats-fn
    :dt/type            :mm/Fn
    :dt.fn/source-ns    "sandbar.schedule.demo"
    :dt.fn/source-var   "log-db-stats"
    :dt.fn/installed-as :classpath-fn
    :dt.fn/lang         :clojure
    :dt.fn/purpose      :emit-memorial
    :dt.fn/purity       :side-effects
    :dt.fn/cost-class   :cheap
    :dt.fn/status       :production
    :dt.fn/version      "1.0.0"
    :dt.fn/description  "γ.5 demo job — emits :mm/EventLog with substrate DB stats"}
   {:db/ident           :sandbar.demo/log-reactive-queue-health-fn
    :dt/type            :mm/Fn
    :dt.fn/source-ns    "sandbar.schedule.demo"
    :dt.fn/source-var   "log-reactive-queue-health"
    :dt.fn/installed-as :classpath-fn
    :dt.fn/lang         :clojure
    :dt.fn/purpose      :emit-memorial
    :dt.fn/purity       :side-effects
    :dt.fn/cost-class   :cheap
    :dt.fn/status       :production
    :dt.fn/version      "1.0.0"
    :dt.fn/description  "γ.5 demo job — emits :mm/EventLog with reactive-queue health snapshot"}])

(def demo-job-specs
  "The 2 demo :mm/Job entity specs referencing the Fns by :db/ident."
  [{:db/ident   :sandbar.demo/db-stats-job
    :dt/type    :mm/Job
    :mm.job/fn  :sandbar.demo/log-db-stats-fn}
   {:db/ident   :sandbar.demo/reactive-queue-health-job
    :dt/type    :mm/Job
    :mm.job/fn  :sandbar.demo/log-reactive-queue-health-fn}])

(def demo-schedule-idents
  "Stable idents of the 2 demo :mm/Schedule entities (caller adds these
   to the scheduler queue after seed)."
  [:sandbar.demo/db-stats-schedule
   :sandbar.demo/reactive-queue-health-schedule])

(defn- demo-schedule-specs
  "The 2 demo :mm/Schedule entity specs, with :mm.schedule/dtstart
   anchored at `now-date`.  db-stats fires every 15 min; reactive-queue-
   health every 10 min (per Dan-directive 2026-05-28)."
  [^Date now-date]
  [{:db/ident                   :sandbar.demo/db-stats-schedule
    :dt/type                    :mm/Schedule
    :mm.schedule/target         :sandbar.demo/db-stats-job
    :mm.schedule/recurrence     "FREQ=MINUTELY;INTERVAL=15"
    :mm.schedule/timezone       "UTC"
    :mm.schedule/dtstart        now-date
    :mm.schedule/misfire-policy :misfire/fire-once-now
    :mm.schedule/concurrency    :concurrency/forbid}
   {:db/ident                   :sandbar.demo/reactive-queue-health-schedule
    :dt/type                    :mm/Schedule
    :mm.schedule/target         :sandbar.demo/reactive-queue-health-job
    :mm.schedule/recurrence     "FREQ=MINUTELY;INTERVAL=10"
    :mm.schedule/timezone       "UTC"
    :mm.schedule/dtstart        now-date
    :mm.schedule/misfire-policy :misfire/fire-once-now
    :mm.schedule/concurrency    :concurrency/forbid}])

(defn seed-demo-jobs!
  "Idempotently seed the 2 demo :mm/Fn + :mm/Job + :mm/Schedule entities
   (upsert via :db/ident).  :mm.schedule/dtstart anchored at (now) so the
   first fire is the next recurrence-boundary after boot.

   Returns a vec of the 2 :mm/Schedule NUMERIC eids for the caller
   (sandbar.core/start) to add to the live scheduler queue.  Numeric (not
   ident) so the dispatcher's queue-comparator secondary-sort
   (`compare a-eid b-eid`) never mixes keyword-vs-number across entries.

   Idempotent: re-running at every boot upserts the same entities (stable
   :db/ident).  Per Dan-directive 2026-05-28 amending Q.γ.5 for demo-jobs."
  []
  (let [now-date (Date/from (Instant/now))]
    ;; Fns + Jobs first (Schedules reference Jobs by ident).  Per-entity
    ;; make with :validate? false (the demo entities intentionally omit
    ;; :dt.fn/body — classpath-fns resolve via source-ns + source-var).
    (doseq [spec demo-fn-specs]
      (dt/make (:dt/type spec) (dissoc spec :dt/type) {:validate? false}))
    (doseq [spec demo-job-specs]
      (dt/make (:dt/type spec) (dissoc spec :dt/type) {:validate? false}))
    (let [schedule-eids (mapv (fn [spec]
                                (:db/id (dt/make (:dt/type spec)
                                                 (dissoc spec :dt/type)
                                                 {:validate? false})))
                              (demo-schedule-specs now-date))]
      (logging/info ::demo-jobs-seeded
                    {:schedule-idents demo-schedule-idents
                     :schedule-eids   schedule-eids
                     :dtstart         (str now-date)}
                    :db-only)
      schedule-eids)))
