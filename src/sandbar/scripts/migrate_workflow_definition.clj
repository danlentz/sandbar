(ns sandbar.scripts.migrate-workflow-definition
  "Rename the class ident :workflow/Definition to :mm/Workflow in place.
   The class keeps its eid, so instance types and property-domain references
   continue to point to it. If the old ident no longer resolves, report a no-op.

   Select the existing store explicitly, verify recovery, and stop other
   writers before invoking:
     lein run -m sandbar.scripts.migrate-workflow-definition
   Audit the renamed entity and its references before restarting the server.
   This migration preserves database identity; it does not rebuild the store."
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
