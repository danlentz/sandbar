(ns sandbar.migrations.phase-b-created-by
  "Backfill missing identity metadata on entities carrying created-by.
   The drift query reports string-valued created-by data; this driver does
   not rewrite those references. entity-migration-tx delegates only to
   zeta-backfill. migrate-batch! and migrate-all! select entities with
   created-by but no mm/id, then apply identity/preferred-label backfill.
   Review the selected cohort and retain a backup before mutation."
  (:require [datomic.api :as d]
            [sandbar.identifier :as id]
            [sandbar.migrations.zeta-backfill :as zb]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Drift discovery (Phase B portion; Q.B.1 SURFACE-AS-DRIFT)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-created-by-string-drift
  "Find entities where :mm.memory/created-by value is a STRING (rather than
   an entity-ref). Per β.2.2 ADR §1.2 query pattern.

   Returns a vec of [eid string-value] tuples. Empty vec indicates clean
   Phase B state for this slot (no drift)."
  [db]
  (vec (d/q '[:find ?e ?v
              :where [?e :mm.memory/created-by ?v]
                     [(string? ?v)]]
            db)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-entity migration tx-data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per the ratified design: each per-family commit handles BOTH (a) the
;; per-family Phase B ref-rewrite (for entities with string-valued slot
;; values) AND (b) the ζ Scope B backfill piggyback on each per-entity
;; touch. For created-by, the (a) portion is empirically 0 entities; the
;; (b) portion is the substantive work.

(defn entity-migration-tx
  "Compute :mm/id and :mm/pref-label backfill transaction data.
   Does not repair string-valued created-by references.
   entity-map must contain at least :db/id + :db/ident; may also contain
   :mm.memory/name, :mm.memory/description, :mm/id, :mm/pref-label."
  [entity-map]
  {:pre [(map? entity-map) (some? (:db/id entity-map))]}
  ;; For created-by today: 0 string-valued; entire tx is ζ-backfill
  (zb/entity-backfill-tx entity-map))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity enumeration + chunked driver
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; The driver queries for entities that have :mm.memory/created-by populated
;; but NOT yet :mm/id (i.e., not-yet-backfilled). Chunks to avoid single-tx
;; size limits. Per β.2.2 ADR + the chunked pattern from sandbar.migrations.
;; zeta-backfill.

(defn find-unmigrated-entity-eids
  "Find entity eids that have :mm.memory/created-by populated but do NOT
   yet have :mm/id populated. These are the candidates for backfill."
  [db]
  (vec (d/q '[:find [?e ...]
              :where [?e :mm.memory/created-by _]
                     (not [?e :mm/id _])]
            db)))


(defn entity-map-for-backfill
  "Read the slot map needed by `entity-migration-tx` for entity `eid`.
   Returns a plain map (not a Datomic Entity) with the relevant slots."
  [db eid]
  (let [ent (d/entity db eid)]
    {:db/id                 (:db/id ent)
     :db/ident              (:db/ident ent)
     :mm.memory/name        (:mm.memory/name ent)
     :mm.memory/description (:mm.memory/description ent)
     :mm/id                 (:mm/id ent)
     :mm/pref-label         (:mm/pref-label ent)}))


(defn migrate-batch!
  "Migrate one batch of entities (size up to `batch-size`).

   Returns a map with:
     :batch-size       — number of entities targeted
     :tx-data-count    — number of tx-statements transacted
     :transacted?      — true if a tx ran; false if batch was empty (quiescent)

   Caller invokes repeatedly until `:transacted? false` to migrate all."
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
        {:batch-size    batch-size
         :tx-data-count (count tx-data)
         :transacted?   (seq tx-data)
         :entities-touched (count candidates)}))))


(defn migrate-all!
  "Loop `migrate-batch!` until quiescent. Returns a summary map:
     :batches-run     — number of batches transacted
     :total-tx-count  — total tx-statements across all batches
     :total-entities  — total entities touched"
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
         {:batches-run batches
          :total-tx-count total-tx
          :total-entities total-ent
          :quiescent? true})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Migration report (pre + post)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migration-report
  "Generate a structured report of the current migration state for the
   created-by family. Returns a map with counts + drift entries for
   operator review.

   Includes:
   - :phase-b-drift  — vec of [eid string-value] tuples; empty = clean
   - :populated-count — entities with :mm.memory/created-by populated
   - :backfilled-count — entities with :mm/id populated (ζ backfill done)
   - :unmigrated-count — populated but not backfilled (next-batch candidates)"
  [db]
  (let [drift           (find-created-by-string-drift db)
        unmigrated      (find-unmigrated-entity-eids db)
        populated-count (or (ffirst (d/q '[:find (count ?e)
                                           :where [?e :mm.memory/created-by _]]
                                         db))
                            0)
        backfilled-count (or (ffirst (d/q '[:find (count ?e)
                                            :where [?e :mm/id _]]
                                          db))
                             0)]
    {:phase-b-drift     drift
     :phase-b-drift-count (count drift)
     :populated-count   populated-count
     :backfilled-count  backfilled-count
     :unmigrated-count  (count unmigrated)
     :migration-clean?  (and (empty? drift) (empty? unmigrated))}))


(comment
  ;; REPL usage:
  (require '[datomic.api :as d]
           '[sandbar.db.datomic :as sdb])
  (def conn (d/connect (sdb/db-uri)))

  (migration-report (d/db conn))
  ;; → pre-migration baseline

  (migrate-all! conn 100)
  ;; → run the migration in 100-entity batches

  (migration-report (d/db conn))
  ;; → post-migration verification
  )
