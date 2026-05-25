(ns sandbar.db.fn-migration
  "Stage D D3 — one-shot migration of legacy `:dt/dt :fn` instances to
   first-class `:dt/type :dt/Fn` + `:dt/type :mm/Fn`.

   Metacircular: the migration is performed by a :dt/Fn instance (the
   `migrate-legacy-fn-instance` transactor function) authored via the
   new dual-emit `defdbfn` macro.  The substrate migrates itself using
   its own foundational primitive.

   Per `decisions/dt_fn_existing_state_reconciliation_dual_emit_defdbfn_
   legacy_migration_2026_05_23.md` §D3.

   ## Usage

   ```clojure
   ;; After schema migration + load-all-dbfn + load-all-mm-fn-memorials:
   (require '[sandbar.db.fn-migration :as fn-mig])
   (fn-mig/migrate-all-legacy-fn-instances! my-conn)
   ```

   Idempotent: re-running is safe (the migration tx-fn checks
   `already-migrated?` and no-ops on previously-migrated entities)."
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.fn :refer [defdbfn]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The migration tx-fn — itself a :dt/Fn instance (metacircular)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defdbfn migrate-legacy-fn-instance [db legacy-eid]
  {:dt.fn/purpose      :transform
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/description  "Migrate a legacy :dt/dt :fn instance to first-class typing.  Adds :dt/type :dt/Fn AND :dt/type :mm/Fn to the entity; preserves :dt/dt :fn for backwards compatibility during transition period.  Idempotent: no-ops on already-migrated entities."
   :dt.fn/version      "1.0.0"
   :dt.fn/installed-as :db-fn}
  (let [entity            (datomic.api/entity db legacy-eid)
        existing-types    (set (map :db/ident (:dt/type entity)))
        already-migrated? (or (contains? existing-types :dt/Fn)
                              (contains? existing-types :mm/Fn))]
    (if already-migrated?
      []                                       ; idempotent no-op
      [[:db/add legacy-eid :dt/type :dt/Fn]
       [:db/add legacy-eid :dt/type :mm/Fn]])))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Driver — walks legacy-tag set + invokes the migration tx-fn for each
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn migrate-all-legacy-fn-instances!
  "Walk all entities with `:dt/dt :fn` tag; invoke `migrate-legacy-fn-instance`
   for each.  Single-pass; atomic per-entity tx; idempotent across re-invocations.

   Returns a summary map `{:found <n> :migrated <n>}`."
  [conn]
  (let [db          (d/db conn)
        legacy-eids (d/q '[:find [?e ...]
                           :where [?e :dt/dt :fn]]
                         db)
        n-found     (count legacy-eids)]
    (log/info :MM-FN/migrate "Found" n-found "legacy :dt/dt :fn instances; migrating ...")
    (when (seq legacy-eids)
      @(d/transact conn
         (mapv (fn [eid] [:migrate-legacy-fn-instance eid])
               legacy-eids)))
    {:found    n-found
     :migrated n-found}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Audit helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn audit-legacy-fn-instances
  "Return count of entities still carrying ONLY the legacy `:dt/dt :fn` tag
   (without :dt/type :dt/Fn).  Should be 0 after migration completes.

   Returns `{:legacy-only <n> :first-class <n> :both <n>}` summary."
  [db]
  (let [all-fn-eids (d/q '[:find [?e ...]
                           :where [?e :dt/dt :fn]]
                         db)
        first-class-eids (set (d/q '[:find [?e ...]
                                     :where [?e :dt/type :dt/Fn]]
                                   db))
        legacy-set       (set all-fn-eids)
        legacy-only      (count (clojure.set/difference legacy-set first-class-eids))
        both             (count (clojure.set/intersection legacy-set first-class-eids))
        first-class-only (count (clojure.set/difference first-class-eids legacy-set))]
    {:legacy-only      legacy-only
     :first-class-only first-class-only
     :both             both
     :total            (+ legacy-only first-class-only both)}))


(comment
  ;; REPL workflow (after schema + macro changes have loaded into a live db):

  ;; 1. Inspect pre-migration state:
  (audit-legacy-fn-instances (d/db @sandbar.core/conn))
  ;; => {:legacy-only N :first-class-only 0 :both 0 :total N}

  ;; 2. Run migration:
  (migrate-all-legacy-fn-instances! @sandbar.core/conn)
  ;; => {:found N :migrated N}

  ;; 3. Verify post-migration:
  (audit-legacy-fn-instances (d/db @sandbar.core/conn))
  ;; => {:legacy-only 0 :first-class-only 0 :both N :total N}

  ;; 4. Sanity-check: a migrated entity has both type assertions:
  (d/q '[:find ?e ?t
         :where [?e :dt/dt :fn] [?e :dt/type ?t]]
       (d/db @sandbar.core/conn))
  )
