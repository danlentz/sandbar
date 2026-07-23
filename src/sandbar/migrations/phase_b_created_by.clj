(ns sandbar.migrations.phase-b-created-by
  "β.2.3 commit-1 (Phase B Class-2 identity-attribution; created-by family) +
  ζ Scope B piggyback driver — the FIRST per-family migration commit per
  β.2.2 ADR §1.3 Q.B.3 ratified order (`created-by` first as pattern-proof
  on UNIFORM format / LOWEST-RISK / canonical-pattern-proof).

  Empirical finding from substrate inspection 2026-05-26T~08:15 (post-Gate-2-
  backup): :mm.memory/created-by range is :dt/Resource (ref); cardinality-many;
  642 entities have it populated; 0 entities have STRING-valued residual.
  Conclusion: codec parsed all path-string values to refs at FS→substrate
  ingest; Phase B ref-rewrite portion is NO-OP for created-by; ζ Scope B
  backfill (`:mm/id` + `:mm/pref-label`) is the substantive per-entity work
  for this first per-family commit. Drift report records the 0-string finding
  as clean confirmation.

  Per the ratified piggyback design (Q.STRAT.2 + Q.ζ.B.10 + ζ Scope B ADR §7.1):
  per-family tx-fns piggyback ζ-backfill on per-entity touch. For created-by
  (FIRST commit per Q.B.3), this scope includes the 642 entities that
  currently have :mm.memory/created-by populated.

  Driver shape (pure-fn + transact wrapper; per build-prove-promote discipline
  applied at the migration level):
    `find-created-by-string-drift`  — Datalog query for string-valued residual
    `find-created-by-populated-entities` — Datalog query for the 642 entities
    `entity-migration-tx`           — per-entity tx-data (combines Phase B
                                       ref-rewrite [no-op for created-by] +
                                       ζ-backfill via zeta-backfill ns)
    `migrate-batch!`                — chunked driver (transacts batch-size at a time)
    `migrate-all!`                  — loop migrate-batch! until quiescent
    `migration-report`              — post-migration summary stats

  Per the η.4 discipline that emerged (restart sandbar after schema-class-
  evolution before subsequent ref-slot operations): this commit consumes
  the post-restart sandbar state (PID 61286+ with mm-namespace.edn loaded;
  schema verified live).

  See:
  - β.2.2 ADR (`memory/decisions/beta_2_2_phase_b_plus_i_resolution_rules_…_2026_05_26.md`)
    §1.2 per-family migration template + §1.4 Q.B.1 SURFACE-AS-DRIFT policy
  - ζ Scope B ADR (`memory/decisions/zeta_scope_b_stable_identifier_substrate_primitive_…_2026_05_26.md`)
    §7.1 piggyback strategy
  - β.2.1 B.1 survey (`memory/observations/beta_2_1_B_1_phase_b_ref_slot_value_distribution_survey_…_2026_05_26.md`)
    created-by 622 file-count + canonical-pattern-proof rationale
  - sandbar.migrations.zeta-backfill (the ζ-portion helpers)
  - sandbar.identifier (the v5 UUID derivation primitives)"
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
  "Compute per-entity migration tx-data for the created-by family.

   Combines:
   - Phase B ref-rewrite (no-op for created-by today; would handle
     string-valued residual if any surfaced)
   - ζ Scope B backfill (`:mm/id` + `:mm/pref-label`)

   Per β.2.2 ADR §1.2 + ζ Scope B ADR §7.1.

   `entity-map` must contain at least :db/id + :db/ident; may also contain
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
