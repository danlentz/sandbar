(ns sandbar.scripts.reset-db
  "Delete the configured Datomic database (per config.edn).  Use during
   schema-evolution work to force a fresh init on next sandbar startup.

   Usage:

       lein reset-db [--confirm]

   The `--confirm` flag is required (without it, the script aborts) —
   prevents accidental destructive runs in dev.  After deletion, the next
   `lein run` of sandbar will create + initialize a fresh DB with the
   currently-loaded schema files.

   Companion to the bootstrap-memory-substrate sub-arc's Stage 2.E
   (`--force-overwrite` semantics) — until the formal `init` modes land,
   this script provides the destructive-reset capability for development
   testing.

   The corpus's service-account token + any ingested entities are lost on
   reset.  Re-issue via `lein issue-mcp-token <service-name>` after the
   first post-reset sandbar startup."
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
