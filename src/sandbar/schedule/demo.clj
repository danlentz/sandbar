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
  (:require [sandbar.db.datatype :as dt]
            [sandbar.logging     :as logging]))

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
