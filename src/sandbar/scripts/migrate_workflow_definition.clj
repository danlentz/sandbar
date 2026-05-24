(ns sandbar.scripts.migrate-workflow-definition
  "One-shot in-place migration — renames the `:workflow/Definition` class
   ident to `:mm/Workflow` per the memorial-class naming-convention ADR
   (memory/decisions/memorial_class_naming_convention_mm_prefix_workflow_definition_to_mm_workflow_2026_05_23.md).

   PRESERVES the database — does NOT erase + reimport.  Per
   memory/authorizations/db_preservation_during_cutover_no_reset_db_without_recovery_tested_dan_directive_2026_05_23.md.

   Idempotent: if `:workflow/Definition` no longer resolves, the
   migration is a noop (safe to re-run; safe to leave in the codebase
   even after the rename has propagated).

   ## Mechanics

   The class entity's eid is unchanged; only the human-readable ident
   is swapped.  Existing `:dt/type :workflow/Definition` instance refs
   point at the eid (Datomic refs are eids, not idents) — they
   auto-resolve to the new ident after rename.  All slot declarations
   (`:dt/domain :workflow/Definition` etc.) are similarly refs-to-eid
   that auto-resolve.

   ## Usage

       cd ~/src/sandbar
       bin/sandbar stop                          # safer to migrate offline
       lein run -m sandbar.scripts.migrate-workflow-definition
       bin/sandbar start                         # schema EDN reload merges slots onto renamed entity

   The script exits 0 on success (including idempotent noop)."
  (:require [datomic.api        :as d]
            [sandbar.db.datomic :as db])
  (:gen-class))

(defn -main [& _args]
  (let [uri  (db/db-uri)]
    (println (str "Connecting to: " uri))
    (let [conn (d/connect uri)]
      (try
        (let [db  (d/db conn)
              eid (:db/id (d/entity db :workflow/Definition))]
          (if eid
            (do
              (println (str "Found :workflow/Definition (eid " eid ")"
                            "; renaming to :mm/Workflow ..."))
              @(d/transact conn
                 [[:db/retract eid :db/ident :workflow/Definition]
                  [:db/add     eid :db/ident :mm/Workflow]])
              (println "Rename complete.  Existing instances retain their"
                       ":dt/type refs (point at eid, not ident) and now"
                       "resolve as :mm/Workflow.")
              (println "Next: 'bin/sandbar start' will merge :mm/Workflow"
                       "slot declarations from schema/workflow.edn onto the"
                       "renamed entity (idempotent)."))
            (println ":workflow/Definition not found; migration already"
                     "applied (idempotent noop).  Safe.")))
        (catch Throwable t
          (binding [*out* *err*]
            (println (str "Migration FAILED: " (.getMessage t))))
          (.printStackTrace t *err*)
          (System/exit 1))
        (finally
          (d/release conn))))
    (System/exit 0)))
