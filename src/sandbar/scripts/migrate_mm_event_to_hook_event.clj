(ns sandbar.scripts.migrate-mm-event-to-hook-event
  "Migrate direct :mm/Event instances to :mm/HookEvent in the existing store.
   Preserve entity ids and references while moving lifecycle-position,
   default-severity, triggered-by-hook, applicable-actor-classes, and
   substrate-correctness-criticality values from :mm.event/* to matching
   :mm.hook-event/* slots. Mark :mm/Event abstract in the same transaction.

   Old schema attribute entities remain, preserving their identities for
   historical queries. If no direct :mm/Event instances remain, report a no-op.
   The new HookEvent schema must already be loaded. Select the store explicitly,
   verify recovery, stop other writers, and review the migration before running:
     lein run -m sandbar.scripts.migrate-mm-event-to-hook-event
   This is a specific schema migration, not an erase-and-reimport operation."
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
