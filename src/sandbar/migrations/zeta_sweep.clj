(ns sandbar.migrations.zeta-sweep
  "Backfill entities carrying a memory rel-path but no durable ID.
   Reuse zeta-backfill's per-entity transaction-data helpers rather than
   coupling identity backfill to a particular reference-field migration.
   The helper also supplies a missing preferred label for that cohort;
   entities with an ID but no preferred label are not selected. This is a
   mutating maintenance driver; current cohort inspection, backup and
   explicit operator authorization are separate prerequisites."
  (:require [datomic.api :as d]
            [sandbar.migrations.zeta-backfill :as zb]))


(defn find-unmigrated-entity-eids
  "Find eids carrying :mm.memory/rel-path and lacking :mm/id.
   This population is selected by slot presence, not a class-hierarchy query;
   pathless memories and records already carrying an ID are excluded."
  [db]
  (vec (d/q '[:find [?e ...]
              :where [?e :mm.memory/rel-path _]
                     (not [?e :mm/id _])]
            db)))


(defn entity-map-for-backfill
  "Read the slot map needed by `entity-backfill-tx` for entity `eid`."
  [db eid]
  (let [ent (d/entity db eid)]
    {:db/id                 (:db/id ent)
     :db/ident              (:db/ident ent)
     :mm.memory/name        (:mm.memory/name ent)
     :mm.memory/description (:mm.memory/description ent)
     :mm/id                 (:mm/id ent)
     :mm/pref-label         (:mm/pref-label ent)}))


(defn migrate-batch!
  "Migrate one batch of entities (size up to `batch-size`)."
  [conn batch-size]
  {:pre [(pos-int? batch-size)]}
  (let [db          (d/db conn)
        candidates  (take batch-size (find-unmigrated-entity-eids db))]
    (if (empty? candidates)
      {:batch-size batch-size :tx-data-count 0 :transacted? false}
      (let [entity-maps (mapv #(entity-map-for-backfill db %) candidates)
            tx-data     (zb/entities-backfill-tx entity-maps)]
        (when (seq tx-data)
          @(d/transact conn tx-data))
        {:batch-size       batch-size
         :tx-data-count   (count tx-data)
         :transacted?     (boolean (seq tx-data))
         :entities-touched (count candidates)}))))


(defn migrate-all!
  "Loop `migrate-batch!` until quiescent."
  ([conn] (migrate-all! conn 100))
  ([conn batch-size]
   (loop [batches 0
          total-tx 0
          total-ent 0]
     (let [result (migrate-batch! conn batch-size)]
       (if (:transacted? result)
         (recur (inc batches)
                (+ total-tx (:tx-data-count result))
                (+ total-ent (:entities-touched result 0)))
         {:batches-run    batches
          :total-tx-count total-tx
          :total-entities total-ent
          :quiescent?     true})))))


(defn migration-report
  "Pre/post migration state summary for the ζ-sweep."
  [db]
  (let [unmigrated      (find-unmigrated-entity-eids db)
        backfilled-count (or (ffirst (d/q '[:find (count ?e)
                                            :where [?e :mm/id _]]
                                          db))
                             0)]
    {:unmigrated-count   (count unmigrated)
     :backfilled-count   backfilled-count
     :sweep-complete?    (zero? (count unmigrated))}))


(comment
  ;; REPL usage:
  (require '[datomic.api :as d]
           '[sandbar.db.datomic :as sdb])
  (def conn (d/connect (sdb/db-uri)))

  (migration-report (d/db conn))
  ;; → pre-migration baseline

  (migrate-all! conn 100)
  ;; → run the sweep

  (migration-report (d/db conn))
  ;; → post-migration verification
  )
