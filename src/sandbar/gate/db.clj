(ns sandbar.gate.db
  "Fresh `datomic:mem` lifecycle primitive for the W1.J release gate.

   Every gate check runs against an EPHEMERAL in-memory Datomic database,
   never the live store (HARD-CONSTRAINT-e: new code runs against
   datomic:mem test fixtures; anything touching the live store is
   dry-run/read-only).  `with-fresh-db*` creates a uniquely-named mem DB,
   loads the required schema, rebinds `sandbar.db.datomic/**conn*` for the
   dynamic extent of `body-fn`, then unconditionally restores the prior
   connection and deletes the mem DB — so a gate check can spin up several
   independent databases in sequence (round-trip needs a source DB and a
   reconstructed DB; the absence battery needs an uncleared DB and a
   cleared DB) without disturbing any ambient connection a caller (e.g. a
   `make-test-db-fixture` test) already established.

   Mirrors the self-contained mem-DB pattern of
   `sandbar.scripts.full-corpus-ingest` (create → connect → reset **conn*
   → load-required-schema → finally reset+delete)."
  (:require [datomic.api        :as d]
            [sandbar.db.datomic :as db]
            [sandbar.test-util  :as tu]))

(defn with-fresh-db*
  "Run `body-fn` with `**conn*` bound to a fresh, schema-loaded mem DB.

   `opts` keys:
     :name         — stem for the mem URI (a nanotime suffix guarantees
                     uniqueness across sequential calls; default \"w1j-gate\")
     :extra-schema — additional schema keywords loaded after the required
                     set (default none)

   Returns whatever `body-fn` returns.  The mem DB is deleted and the
   prior `**conn*` restored in a `finally`, even on throw."
  ([body-fn] (with-fresh-db* {} body-fn))
  ([{:keys [name extra-schema] :or {name "w1j-gate"}} body-fn]
   (let [prior-conn @db/**conn*
         uri        (str "datomic:mem://" name "-" (System/nanoTime))]
     (d/create-database uri)
     (let [conn (d/connect uri)]
       (reset! db/**conn* conn)
       (try
         (tu/load-required-schema conn)
         (when extra-schema
           (tu/load-schema conn extra-schema))
         (body-fn)
         (finally
           (reset! db/**conn* prior-conn)
           (d/delete-database uri)))))))
