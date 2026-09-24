(ns sandbar.scripts.list-dbs
  "List databases visible through the configured transactor URI.
   Usage: bin/sandbar list-dbs
   Resolve :db :url through the layered config and enumerate directly,
   without starting the HTTP component system. A shared transactor may list
   databases belonging to more than one consumer."
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
