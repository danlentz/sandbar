(ns sandbar.schedule.system
  "Sandbar's default-provided SYSTEM jobs — operational observability that
   ships with the platform and runs by default (per Dan-directive
   2026-05-28, renaming the prior 'demo' framing).

   Two system jobs at present, both emitting LOG LINES ONLY — no database
   row, no memorial (see MEMORIAL POLICY below):

     - db-stats              (FREQ=HOURLY;INTERVAL=1) — substrate counts
       {:memorials :classes :properties :total-entities}
     - reactive-queue-health (FREQ=HOURLY;INTERVAL=1) — the 13-key
       sandbar.reactive.queue/health snapshot

   ## Memorial policy — log lines only (Dan's retention-review ruling 2026-09-19)

   These are LOW-CONTENT operational signals, HOURLY (≈ 48/day for the
   pair).  They emit plain Telemere signals: printed to `sandbar.log` by
   the LOG handler, persisted nowhere.  History:

   - 2026-05-28: emitted `:db-only` — an `:event/SystemEvent` row per fire
     rather than a `:first-class` `:mm/EventLog` (a `:mm/Memory` subtype,
     which would have inflated the memory count, fed BM25F and been
     FS-projected).  The row was meant as queryable operational history.
   - 2026-09-19: nobody ever read those rows from the database (13,751 of
     them at the census, plus one `:mm/Run` per fire at the old ten- and
     fifteen-minute cadence), the log file carried the same numbers, and
     Dan ruled that telemetry is retained only where its value exceeds the
     code needed to keep it.  So: log lines only, hourly, and the
     accumulated rows deleted with the backlog.
   - `:mm/Run` records are still minted per fire by the job-dispatcher (the
     scheduler's audit trail); at hourly cadence that is 48 a day.

   These are the 'system jobs' umbrella — distinct from user-defined jobs.
   Future system jobs (scheduled tag.audit / Gate-2 verify-restore / SHACL
   conformance-report / project.dump-db / health-check per the γ.6 future-
   pattern docs) join the same `:sandbar.system/*` namespace.

   Exercises the full scheduler pipeline end-to-end:
     - sandbar.schedule.dispatcher (fire-thread + queue)
     - sandbar.schedule.job-dispatcher (event subscriber + Run lifecycle)
     - sandbar.event (class-hierarchical dispatch)
     - sandbar.logging (Telemere signal → the LOG handler; no memorial)
     - sandbar.db.datatype + sandbar.reactive.queue (the observed substrate)

   per γ.1 ADR §1.4 + the γ scheduler arc plan.

   ## Boot wiring (γ.5b)

   When `:scheduler/enabled?` + `:scheduler/system-jobs?` are true (the
   shipped default), `sandbar.core/start` invokes `seed-system-jobs!` →
   upserts the 2 :mm/Fn + 2 :mm/Job + 2 :mm/Schedule entities (stable
   :db/ident; idempotent across boots; dtstart re-anchored at boot) → adds
   the returned schedule eids to the live scheduler queue.

   ## See also

   - γ.1 ADR §1.4: `:memory.decisions/gamma_1_scheduler_path_a_native_min_heap_dispatcher_q_gamma_1_through_6_resolved_2026_05_27`
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
;; Job handlers

(defn log-db-stats
  "System job fn — invoked by sandbar.schedule.job-dispatcher per fire of
   the db-stats :mm/Schedule.

   Computes substrate-count stats via `db-stats` + emits ONE plain
   operational-telemetry signal via `sandbar.logging/info` carrying the
   stats map — a log line in `sandbar.log`, no database row (see ns
   MEMORIAL POLICY; log-only since 2026-09-19).

   Argument shape: `run-ctx` map carrying:
     {:run-eid      <numeric eid of the :mm/Run instance>
      :schedule-eid <numeric eid of the firing :mm/Schedule>
      :job-eid      <numeric eid of the :mm/Job being executed>}

   Returns the stats map (also carried in the log signal's :data slot)."
  [run-ctx]
  (let [stats (db-stats)]
    (logging/info ::db-stats
                  "System job: DB-stats substrate snapshot"
                  (merge stats run-ctx))
    stats))

(defn log-reactive-queue-health
  "System job fn — reactive-projection queue health snapshot.

   Same shape as `log-db-stats` but reports the 13-key health map from
   `sandbar.reactive.queue/health` (the backing fn for the
   `sandbar.reactive.health` MCP verb).  Emits ONE plain
   operational-telemetry signal per fire (a log line, no database row;
   see ns MEMORIAL POLICY) carrying:

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
    (logging/info ::reactive-queue-health
                  "System job: reactive-queue health snapshot"
                  (merge health run-ctx))
    health))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; γ.5b — system-job seeding (boot-time, idempotent)
;;
;; Per Dan-directive 2026-05-28 ('it'd be fine if sandbar by default had
;; these two jobs scheduled') — AMENDS the Q.γ.5 opt-in-safety ratification
;; for the system-jobs case.  sandbar.core/start invokes seed-system-jobs!
;; when config's :scheduler/system-jobs? is true, then adds the returned
;; schedule eids to the live scheduler queue.
;;
;; Idempotency: each entity carries a stable :db/ident, so re-seeding at
;; every boot UPSERTS (Datomic :db/ident semantics) rather than creating
;; duplicates.  :mm.schedule/dtstart is re-anchored to (now) on each seed.
;;
;; REF SLOTS + the :no-job-target fix (η.4 2026-05-28).  The ref slots
;; (:mm.job/fn, :mm.schedule/target) point at NAMED entities — each target
;; carries its own :db/ident.  We seed them with the target's :db/ident
;; KEYWORD, the idiomatic metamodel form for a ref to a named entity:
;; Datomic resolves a keyword on a :db.type/ref slot as a lookup-by-ident
;; and stores a PROPER ref (verified empirically: valueType :db.type/ref,
;; the target eid resolves).  Seed order (Fns → Jobs → Schedules) ensures
;; each ref target already exists when the referrer is transacted.
;;
;; The original production :no-job-target symptom was NOT a write problem
;; — the ref was always stored correctly.  It was a READ problem: reading
;; a ref-to-named-entity back via d/entity returns the target's :db/ident
;; KEYWORD (Datomic's enum-ref behavior), and the old
;; job-dispatcher/ref->eid did not coerce that keyword to an eid → it
;; returned nil → :no-job-target.  The fix is the keyword branch in
;; sandbar.schedule.job-dispatcher/ref->eid (see its docstring); no seed-
;; side eid pre-resolution is needed.
;;
;; The entities are :mm/Spec subtypes (Fn/Job/Schedule) with NO
;; :mm.memory/rel-path — so the reactive-projection fs-sink skips them
;; (operational substrate, not corpus-FS-projected content).

(def system-fn-specs
  "The 2 system :mm/Fn entity specs (stable idents for upsert)."
  [{:db/ident           :sandbar.system/log-db-stats-fn
    :dt/type            :mm/Fn
    :dt.fn/source-ns    "sandbar.schedule.system"
    :dt.fn/source-var   "log-db-stats"
    :dt.fn/installed-as :classpath-fn
    :dt.fn/lang         :clojure
    :dt.fn/purpose      :emit-memorial
    :dt.fn/purity       :side-effects
    :dt.fn/cost-class   :cheap
    :dt.fn/status       :production
    :dt.fn/version      "1.0.0"
    :dt.fn/description  "System job — emits :mm/EventLog with substrate DB stats"}
   {:db/ident           :sandbar.system/log-reactive-queue-health-fn
    :dt/type            :mm/Fn
    :dt.fn/source-ns    "sandbar.schedule.system"
    :dt.fn/source-var   "log-reactive-queue-health"
    :dt.fn/installed-as :classpath-fn
    :dt.fn/lang         :clojure
    :dt.fn/purpose      :emit-memorial
    :dt.fn/purity       :side-effects
    :dt.fn/cost-class   :cheap
    :dt.fn/status       :production
    :dt.fn/version      "1.0.0"
    :dt.fn/description  "System job — emits :mm/EventLog with reactive-queue health snapshot"}])

(def system-job-specs
  "The 2 system :mm/Job entity specs.  `:mm.job/fn` carries the target
   Fn's :db/ident keyword; Datomic resolves it to a proper :db.type/ref
   on transact (see ns-comment REF SLOTS)."
  [{:db/ident   :sandbar.system/db-stats-job
    :dt/type    :mm/Job
    :mm.job/fn  :sandbar.system/log-db-stats-fn}
   {:db/ident   :sandbar.system/reactive-queue-health-job
    :dt/type    :mm/Job
    :mm.job/fn  :sandbar.system/log-reactive-queue-health-fn}])

(def system-schedule-idents
  "Stable idents of the 2 system :mm/Schedule entities."
  [:sandbar.system/db-stats-schedule
   :sandbar.system/reactive-queue-health-schedule])

(defn- system-schedule-specs
  "The 2 system :mm/Schedule entity specs, dtstart anchored at `now-date`.
   db-stats fires every 15 min; reactive-queue-health every 10 min.
   `:mm.schedule/target` carries the target Job's :db/ident keyword;
   Datomic resolves it to a proper :db.type/ref on transact."
  [^Date now-date]
  [{:db/ident                   :sandbar.system/db-stats-schedule
    :dt/type                    :mm/Schedule
    :mm.schedule/target         :sandbar.system/db-stats-job
    :mm.schedule/recurrence     "FREQ=HOURLY;INTERVAL=1"
    :mm.schedule/timezone       "UTC"
    :mm.schedule/dtstart        now-date
    :mm.schedule/misfire-policy :misfire/fire-once-now
    :mm.schedule/concurrency    :concurrency/forbid}
   {:db/ident                   :sandbar.system/reactive-queue-health-schedule
    :dt/type                    :mm/Schedule
    :mm.schedule/target         :sandbar.system/reactive-queue-health-job
    :mm.schedule/recurrence     "FREQ=HOURLY;INTERVAL=1"
    :mm.schedule/timezone       "UTC"
    :mm.schedule/dtstart        now-date
    :mm.schedule/misfire-policy :misfire/fire-once-now
    :mm.schedule/concurrency    :concurrency/forbid}])

(defn- make-upsert!
  "dt/make a spec (dissoc-ing :dt/type for the class arg), returning the
   new entity's eid.  :validate? false (system entities intentionally
   omit :dt.fn/body — classpath-fns resolve via source-ns + source-var)."
  [spec]
  (:db/id (dt/make (:dt/type spec) (dissoc spec :dt/type) {:validate? false})))

(defn seed-system-jobs!
  "Idempotently seed the 2 system :mm/Fn + :mm/Job + :mm/Schedule entities
   (upsert via :db/ident).  :mm.schedule/dtstart anchored at (now).

   Returns a vec of the 2 :mm/Schedule NUMERIC eids for the caller
   (sandbar.core/start) to add to the live scheduler queue.

   Transacts in dependency order — Fns first, then Jobs (which ref the
   Fns via :mm.job/fn), then Schedules (which ref the Jobs via
   :mm.schedule/target) — so each ref's :db/ident target already exists
   when Datomic resolves the keyword→ref on transact (see ns-comment
   REF SLOTS).  No eid pre-resolution needed: the metamodel idiom is to
   reference named entities by :db/ident.

   Idempotent: re-running at every boot upserts the same entities (stable
   :db/ident → stable eids across boots)."
  []
  (let [now-date (Date/from (Instant/now))]
    (run! make-upsert! system-fn-specs)
    (run! make-upsert! system-job-specs)
    (let [schedule-eids (mapv make-upsert! (system-schedule-specs now-date))]
      (logging/info ::system-jobs-seeded
                    {:schedule-idents system-schedule-idents
                     :schedule-eids   schedule-eids
                     :dtstart         (str now-date)})
      schedule-eids)))
