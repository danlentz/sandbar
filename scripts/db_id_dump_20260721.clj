;; Seat D:backfill-dryrun (2026-07-21 fleet) — READ-ONLY dump of the live DB's
;; (rel-path -> :mm/id) map.  Adapted from scripts/db_id_dump.clj (w1d seat,
;; merged @b4e6b1b) with this seat's output path + basis stamps.
;; STRICTLY read-only: d/connect + d/db + d/q only.  NO d/transact, NO
;; create-database, NO mutation of any kind.  Writes one EDN artifact to the
;; seat scratchpad worktree only.
(require '[datomic.api :as d]
         '[clojure.pprint :as pp])

(def uri "datomic:dev://localhost:4334/sandbar")
(def out-path
  "/private/tmp/claude-501/-Users-dan-claude/f6032830-453a-4550-a1ed-5d52b1d035da/scratchpad/d-backfill-dryrun/out/db-id-map.edn")

(let [t0   (java.time.Instant/now)
      conn (d/connect uri)                 ; READ connection
      db   (d/db conn)
      basis-t (d/basis-t db)
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
                      :generated (str t0)
                      :db-basis-t basis-t
                      :count-with-id (count id-map)
                      :count-all-relpath (count all-rels)
                      :count-no-id (count no-id)
                      :id-map id-map
                      :id-idents id-idents
                      :no-id-relpaths (vec no-id)})))
  (println "READ-ONLY dump complete (no writes to DB).")
  (println "  probe instant           :" (str t0))
  (println "  db basis-t              :" basis-t)
  (println "  rel-path entities total :" (count all-rels))
  (println "  with :mm/id             :" (count id-map))
  (println "  WITHOUT :mm/id (skip)   :" (count no-id))
  (println "  wrote:" out-path)
  (println "  no-id sample:" (vec (take 12 no-id))))
(shutdown-agents)
