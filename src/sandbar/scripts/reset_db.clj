(ns sandbar.scripts.reset-db
  "Delete the selected Datomic database for an intentional development reset.
   Usage: lein reset-db --confirm

   Without --confirm the script aborts. A later server startup initializes a
   fresh store from schema; all former content, identities, and service-account
   credentials are lost. This is not an import or routine repair procedure.
   Verify the selected target and an adequate recovery path before invoking it.
   See doc/operations.md for maintenance that retains the existing store."
  (:require [datomic.api        :as d]
            [sandbar.db.datomic :as db])
  (:gen-class))

(defn -main [& args]
  (let [confirm? (some #{"--confirm"} args)
        uri      (db/db-uri)]
    (when-not confirm?
      (binding [*out* *err*]
        (println (str "ABORT: --confirm flag required to delete DB at " uri))
        (println "       Use: lein reset-db --confirm"))
      (System/exit 2))
    (println (str "Deleting Datomic DB: " uri))
    (try
      (let [deleted? (d/delete-database uri)]
        (println (str "Deleted: " deleted?))
        (println "Next `lein run` will create + initialize a fresh DB."))
      (catch Throwable e
        (binding [*out* *err*]
          (println (str "Delete failed: " (.getMessage e))))
        (System/exit 1)))
    (System/exit 0)))
