(ns sandbar.scripts.list-dbs
  "Enumerate Datomic databases on the configured transactor.

   Usage:

       bin/sandbar list-dbs

   Shows ALL consumers' databases on the shared transactor — the
   foundational view for the F2 cohabitability model (one transactor;
   N consumer DBs per ADR D.F).

   Reads the transactor URI from the layered config's `:db :url` —
   `(sandbar.config/value :db :url)` resolved via 3-layer merge.
   Connects without instantiating sandbar.core, so this script doesn't
   stand up the full sandbar stack just to enumerate.

   Per memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md D.G."
  (:require [datomic.api    :as d]
            [sandbar.config :as cfg])
  (:gen-class))

(defn -main [& _args]
  (let [base-url (cfg/value :db :url)
        ;; d/get-database-names takes URL with trailing `/*` to list all
        list-url (if (re-find #"/\*$" base-url) base-url (str base-url "*"))]
    (println (str "Transactor: " base-url))
    (println (str "(query: " list-url ")"))
    (println)
    (try
      (let [names (d/get-database-names list-url)]
        (if (seq names)
          (do
            (println (format "%-30s %s" "DATABASE" "URI"))
            (println (apply str (repeat 80 "-")))
            (doseq [n (sort names)]
              (println (format "%-30s %s%s" n base-url n))))
          (println "(no databases found)")))
      (catch Throwable t
        (binding [*out* *err*]
          (println (str "ERROR connecting to " base-url ": " (.getMessage t)))
          (println "  Is the transactor running?  Try: lsof -i :4334"))
        (System/exit 2))))
  (System/exit 0))
