;; W1.D READ-ONLY dump of the live DB's (rel-path -> :mm/id) map.
;; STRICTLY read-only: d/connect + d/db + d/q only. NO d/transact, NO
;; create-database, NO mutation of any kind. Per constraint (e) — live-store
;; contact is dry-run/read-only. Writes one EDN artifact to scratchpad.
(require '[datomic.api :as d]
         '[clojure.edn :as edn]
         '[clojure.pprint :as pp])

(def uri "datomic:dev://localhost:4334/sandbar")
(def out-path
  "/private/tmp/claude-501/-Users-dan-claude/0d71181d-485f-44de-838f-71c18aec0495/scratchpad/w1d-wt/db-id-map.edn")

(let [conn (d/connect uri)                 ; READ connection
      db   (d/db conn)
      ;; document entities carrying a rel-path AND an :mm/id
      id-rows (d/q '[:find ?rel ?id ?ident
                     :where
                     [?e :mm.memory/rel-path ?rel]
                     [?e :mm/id ?id]
                     [?e :db/ident ?ident]]
                   db)
      ;; ALL rel-path-bearing entities (to compute the no-:mm/id skip set)
      all-rows (d/q '[:find ?rel ?ident
                      :where
                      [?e :mm.memory/rel-path ?rel]
                      [?e :db/ident ?ident]]
                    db)
      id-map    (into {} (map (fn [[rel id _]] [rel (str id)]) id-rows))
      id-idents (into {} (map (fn [[rel _ ident]] [rel (str ident)]) id-rows))
      all-rels  (into #{} (map first all-rows))
      no-id     (sort (remove id-map all-rels))]
  (spit out-path
        (with-out-str
          (pp/pprint {:uri uri
                      :generated (str (java.time.Instant/now))
                      :count-with-id (count id-map)
                      :count-all-relpath (count all-rels)
                      :count-no-id (count no-id)
                      :id-map id-map
                      :id-idents id-idents
                      :no-id-relpaths (vec no-id)})))
  (println "READ-ONLY dump complete (no writes to DB).")
  (println "  rel-path entities total :" (count all-rels))
  (println "  with :mm/id             :" (count id-map))
  (println "  WITHOUT :mm/id (skip)   :" (count no-id))
  (println "  wrote:" out-path)
  ;; sample a few no-id rel-paths to characterize the skip set
  (println "  no-id sample:" (vec (take 12 no-id))))
(shutdown-agents)
