(ns sandbar.scripts.migrate-mm-event-to-hook-event
  "One-shot in-place migration — lifts `:mm/Event` from concrete to
   abstract; re-types the 6 existing hook-event-kind instances from
   `:mm/Event` to `:mm/HookEvent`; copies per-instance values from
   `:mm.event/{lifecycle-position, default-severity, triggered-by-hook,
   applicable-actor-classes, substrate-correctness-criticality}` to the
   `:mm.hook-event/*` equivalents declared in `schema/mm-temporal.edn`.

   Per the 2026-05-24 collision-resolution ADR
   (memory/decisions/mm_event_collision_resolution_lift_existing_to_abstract_root_dan_directive_2026_05_24.md)
   + Phase 2 type-lattice ADR D.7
   (memory/decisions/unified_mm_type_lattice_workflow_process_activity_run_job_schedule_event_phase_2_2026_05_24.md).

   PRESERVES the database — does NOT erase + reimport.  Per the
   no-reset-db-with-Gate-2-safety-net discipline lifted 2026-05-24 via
   memory/observations/gate_2_restore_path_verified_end_to_end_lifts_no_reset_db_2026_05_24.md.

   Idempotent: if no direct `:dt/type :mm/Event` instances remain (i.e.,
   migration has been applied), the script reports noop + exits 0.

   ## Mechanics

   Atomic single-transaction (Datomic atomicity guarantees no
   intermediate-state observability):

     (1) For each instance whose `:dt/type` is `:mm/Event`:
         - Retract `[:dt/type :mm/Event]`; assert `[:dt/type :mm/HookEvent]`
         - For each of the 5 slot-mapping pairs:
           - Read current value(s) from the `:mm.event/*` slot
           - Assert to the corresponding `:mm.hook-event/*` slot
           - Retract from the old `:mm.event/*` slot
     (2) Mark `:mm/Event` abstract: `[:db/add :mm/Event :dt/abstract? true]`

   Slot-mapping:

     :mm.event/lifecycle-position                → :mm.hook-event/lifecycle-position
     :mm.event/default-severity                  → :mm.hook-event/default-severity
     :mm.event/triggered-by-hook                 → :mm.hook-event/triggered-by-hook
     :mm.event/applicable-actor-classes          → :mm.hook-event/applicable-actor-classes  (cardinality-many)
     :mm.event/substrate-correctness-criticality → :mm.hook-event/substrate-correctness-criticality

   NOTE on slot lifecycle: the OLD `:mm.event/*` slot ENTITIES are NOT
   retracted from the schema.  They become orphan-shaped (no instances
   carry them post-migration; their `:dt/domain` still references
   `:mm/Event`).  Leaving them in place preserves :db/id continuity for
   any historical-query consumers; a future cleanup pass can retract
   them if needed.

   ## Prerequisites

   1. `schema/mm-temporal.edn` MUST be loaded (declares `:mm/HookEvent`
      class + `:mm.hook-event/*` slots).  Wire via `:required-schema`
      in `config/config.edn` and restart sandbar before running this.
   2. Backup + Gate-1 verify-backup BEFORE running (defensive baseline
      per the no-reset-db-with-provisos pattern).

   ## Usage

       cd ~/src/sandbar
       bin/sandbar backup                              # defensive baseline
       bin/sandbar verify-backup                       # Gate 1
       bin/sandbar stop                                # offline migration is safer
       # Ensure :mm-temporal in :required-schema, then:
       lein run -m sandbar.scripts.migrate-mm-event-to-hook-event
       bin/sandbar start                               # schema reload (idempotent)
       bin/sandbar verify-restore                      # Gate 2 — proves recoverability

   The script exits 0 on success (including idempotent noop)."
  (:require [datomic.api        :as d]
            [sandbar.db.datomic :as db])
  (:gen-class))

(def ^:private slot-mapping
  "Old `:mm.event/*` slot ident → New `:mm.hook-event/*` slot ident.
   Defined by the 2026-05-24 collision-resolution ADR §2 +
   schema/mm-temporal.edn.

   NOTE — `:mm.event/applicable-actor-classes` is INTENTIONALLY EXCLUDED
   from this mapping.  The original slot is `:db.type/keyword`
   (cardinality-many) but the new `:mm.hook-event/applicable-actor-classes`
   was authored in schema/mm-temporal.edn as `:db.type/ref` (range
   `:mm/Actor`) — Datomic rejects copying keyword values into a ref slot
   AND `:db/valueType` is immutable post-installation, so in-substrate
   correction requires a separate retraction-and-recreation pass.
   Pragmatic move: skip the migration for THIS slot in THIS pass; legacy
   values stay on `:mm.event/applicable-actor-classes` and remain
   accessible via inheritance from the (post-migration) abstract
   `:mm/Event` ancestor.  Future follow-up arc will fix the slot-type
   discrepancy + complete the migration."
  {:mm.event/lifecycle-position                :mm.hook-event/lifecycle-position
   :mm.event/default-severity                  :mm.hook-event/default-severity
   :mm.event/triggered-by-hook                 :mm.hook-event/triggered-by-hook
   :mm.event/substrate-correctness-criticality :mm.hook-event/substrate-correctness-criticality})

(defn- ref-eid
  "Unwrap a slot value to an eid if it's a ref-shaped entity-map; otherwise
   return the value as-is.  Datomic's d/entity returns Entity records for
   ref slots; we need their :db/id when building tx-data."
  [v]
  (if (map? v) (:db/id v) v))

(defn- instance-slot-values
  "Return a map of `{old-ident new-ident values-coll}` capturing each
   migrating slot's current value(s) on `ent`.  Cardinality-one ⇒
   single-element coll; cardinality-many ⇒ multi-element coll.  Skipped
   when slot is absent on the instance."
  [ent]
  (into {}
        (for [[old new] slot-mapping
              :let      [v (get ent old)]
              :when     (some? v)]
          [old {:new new
                ;; Normalize to coll for uniform tx-data construction:
                :values (if (or (set? v) (sequential? v))
                          (map ref-eid v)
                          [v])}])))

(defn- find-mm-event-instances
  "Discover direct instances of `:mm/Event` (NOT polymorphic — uses literal
   `:dt/type :mm/Event` match; subclass instances are excluded).  Returns a
   seq of `{:eid :ident :slots}` records."
  [db]
  (let [event-eid (:db/id (d/entity db :mm/Event))
        eids      (d/q '[:find [?e ...]
                         :in $ ?event-eid
                         :where [?e :dt/type ?event-eid]]
                       db event-eid)]
    (for [eid eids
          :let [ent (d/entity db eid)]]
      {:eid   eid
       :ident (:db/ident ent)
       :slots (instance-slot-values ent)})))

(defn- per-instance-tx-data
  "Build the tx-data ops for ONE instance: re-type + per-slot copy."
  [{:keys [eid slots]}]
  (concat
    ;; (1) Re-type :dt/type :mm/Event -> :mm/HookEvent
    [[:db/retract eid :dt/type :mm/Event]
     [:db/add     eid :dt/type :mm/HookEvent]]
    ;; (2) Per-slot copy: retract old, assert new, value-by-value
    (mapcat (fn [[old {:keys [new values]}]]
              (concat
                (for [v values] [:db/retract eid old v])
                (for [v values] [:db/add     eid new v])))
            slots)))

(defn- abstract-flip-ops
  "Tx-data marking :mm/Event as abstract.  Datomic :db/add of an
   existing-equal value is a no-op, so this is idempotent."
  []
  [[:db/add :mm/Event :dt/abstract? true]])

(defn -main [& _args]
  (let [uri (db/db-uri)]
    (println (str "Connecting to: " uri))
    (let [conn (d/connect uri)]
      (try
        (let [db        (d/db conn)
              instances (find-mm-event-instances db)
              n         (count instances)]
          (cond
            (zero? n)
            (println "No direct :dt/type :mm/Event instances found; migration"
                     "already applied (idempotent noop).  Safe.")

            :else
            (do
              (println (str "Found " n " :mm/Event instance(s) to re-type:"))
              (doseq [{:keys [ident eid slots]} instances]
                (println (str "  - " (or ident eid) " (eid " eid ")"))
                (doseq [[old {:keys [new values]}] slots]
                  (println (str "      " old " (" (count values) " value(s))"
                                " → " new))))
              (let [per-instance-ops (mapcat per-instance-tx-data instances)
                    abstract-ops     (abstract-flip-ops)
                    tx-data          (vec (concat per-instance-ops abstract-ops))]
                (println (str "Transacting " (count tx-data) " ops"
                              " (re-type + slot-copy + abstract-flip) ..."))
                @(d/transact conn tx-data)
                (println "Migration complete.")
                (println (str "  - " n " instance(s) re-typed to :mm/HookEvent"))
                (println (str "  - " (count slot-mapping)
                              " slot family migrated per-instance"))
                (println "  - :mm/Event marked abstract")
                (println)
                (println "Next: 'bin/sandbar verify-restore' to confirm Gate-2"
                         "safety + memorialize migration outcome.")))))
        (catch Throwable t
          (binding [*out* *err*]
            (println (str "Migration FAILED: " (.getMessage t))))
          (.printStackTrace t *err*)
          (System/exit 1))
        (finally
          (d/release conn))))
    (System/exit 0)))
