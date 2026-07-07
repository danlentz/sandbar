(ns sandbar.firewall.support
  "S7 BU-7 — shared seeding helpers for the firewall integration battery.

   Every helper transacts via RAW `d/transact` (NOT `dt/make`/`make-all*`) so
   the SETUP itself never trips the firewall guard under test — the tests
   exercise the guard on the SUBJECT write, against a cleanly-seeded fixture.
   Seeds public/private Projects, Contexts, and Memories with the S6 firewall
   slots (`:mm.project/default-visibility`, `:mm.context/firewall-class`,
   `:mm.memory/visibility`, `:mm.memory/owning-project`,
   `:mm.project/runs-in-context`)."
  (:require [datomic.api :as d]
            [sandbar.db.datomic :as db]))

(defn raw-transact!
  "Transact `tx` via raw Datomic (bypassing the firewall guard) and return the
   tx-result.  For SETUP only — the write under test goes through dt/make etc."
  [tx]
  @(d/transact (db/conn) tx))

(defn eid-of
  "The live `:db/id` of `ident` under the current db."
  [ident]
  (:db/id (d/entity (db/db) ident)))

(defn seed-context!
  "Seed a :mm/Context with `firewall-class` (default `:none` = private).  Pass
   `:public-bottom` for the public compartment.  Returns the context eid."
  ([ident] (seed-context! ident :none))
  ([ident firewall-class]
   (raw-transact! [{:db/ident              ident
                    :dt/type               :mm/Context
                    :mm.memory/name        (name ident)
                    :mm.context/firewall-class firewall-class}])
   (eid-of ident)))

(defn seed-project!
  "Seed a :mm/Project with `default-visibility` (:public / :private), tied to
   `context-ident` via runs-in-context.  Optional `firewall-class` composes
   most-restrictive.  Returns the project eid."
  ([ident visibility context-ident]
   (seed-project! ident visibility context-ident nil))
  ([ident visibility context-ident firewall-class]
   (raw-transact! [(cond-> {:db/ident                      ident
                            :dt/type                       :mm/Project
                            :mm.memory/name                (name ident)
                            :mm.project/ident              ident
                            :mm.project/corpus-repo        "test-repo"
                            :mm.project/default-visibility visibility
                            :mm.project/runs-in-context    context-ident}
                     firewall-class (assoc :mm.project/firewall-class firewall-class))])
   (eid-of ident)))

(defn seed-memory!
  "Seed a :mm/Memory with explicit `visibility` owned by `project-ident`.
   Returns the memory eid.  Extra slots merge in (e.g. governed edges for
   legacy-bad-edge seeding)."
  ([ident visibility project-ident]
   (seed-memory! ident visibility project-ident {}))
  ([ident visibility project-ident extra]
   (raw-transact! [(merge {:db/ident                   ident
                           :dt/type                    :mm/Memory
                           :mm.memory/name             (name ident)
                           :mm.memory/visibility       visibility
                           :mm.memory/owning-project   project-ident}
                          extra)])
   (eid-of ident)))
