(ns sandbar.migrations.zeta-sweep
  "ζ Scope B corpus-wide sweep — backfill `:mm/id` v5 UUID + `:mm/pref-label`
  on ALL :mm/Memory entities that lack them.

  Strategic pivot rationale (per the empirical finding 2026-05-26T~08:35
  documented in `memory/observations/phase_b_ref_rewrite_is_no_op_across_all_families_codec_already_resolves_path_strings_to_refs_at_ingest_zeta_sweep_pivot_2026_05_26.md`):
  the β.2.2 ADR's per-family migration ordering was an OPTIMIZATION
  (piggyback ζ-backfill on Phase B ref-rewrite per-entity touch). Empirical
  inspection of substrate state for created-by / related / cites /
  motivated-by / part-of / evidences / parent ALL report 0 string-valued
  drift — the codec resolves path-strings to refs at FS→substrate ingest;
  Phase B ref-rewrite is empirically NO-OP across the corpus.

  With Phase B NO-OP, the per-family commit ordering loses its optimization
  rationale. The cleaner path: ζ-only sweep over ALL entities lacking
  :mm/id. One Gate-2 backup-cycle; one migration commit; covers the full
  remaining 5306+ entities.

  Composes WITH:
  - `sandbar.migrations.zeta-backfill` (per-entity tx-data helpers; reused)
  - `sandbar.identifier` (v5 UUID derivation)
  - The β.2.2 ADR §1.4 Q.B.1 SURFACE-AS-DRIFT diagnostic (recorded as
    0 across-the-board; clean Phase B state confirmed for the whole corpus)
  - The 627-entity prior backfill from β.2.3.1 (already-populated entities
    are no-op skipped via entity-backfill-tx's skip-condition)

  Per the build-prove-promote discipline (Dan-directive 2026-05-26): the
  PROVE phase happened in β.2.3.1; this sweep is the PROVEN-PATTERN
  applied broadly (still consuming sandbar.identifier locally; still no
  clj-uuid library modification)."
  (:require [datomic.api :as d]
            [sandbar.migrations.zeta-backfill :as zb]))


(defn find-unmigrated-entity-eids
  "Find ALL :mm/Memory-or-subclass entity eids that lack :mm/id (regardless
   of which slots are populated). The corpus-wide ζ-sweep candidate set.

   Strategy: every :mm/Memory entity carries :mm.memory/rel-path (a slot
   declared on :mm/Memory + inherited by all subclasses). Filter by
   rel-path-populated + no-:mm/id. Simpler than transitive class-hierarchy
   traversal in Datalog (which requires rule syntax)."
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
