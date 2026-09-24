;; Seat D:backfill-dryrun — decompose the no-db-id sets (READ-ONLY set math
;; over the two dry-run artifacts; no corpus/DB contact).
(require '[clojure.edn :as edn]
         '[clojure.set :as set]
         '[clojure.pprint :as pp])
(def out "/private/tmp/claude-501/-Users-dan-claude/f6032830-453a-4550-a1ed-5d52b1d035da/scratchpad/d-backfill-dryrun/out")
(def dump  (edn/read-string (slurp (str out "/db-id-map.edn"))))
(def plans (edn/read-string (slurp (str out "/id-backfill.plans.edn"))))
(def fs-nodb (into #{} (map :rel) (filter #(= :no-db-id (:action %)) plans)))
(def db-noid (into #{} (:no-id-relpaths dump)))
(def db-all  (into (set (keys (:id-map dump))) db-noid))
(def both        (set/intersection fs-nodb db-noid))   ; in DB, id-less
(def fs-only     (set/difference fs-nodb db-all))      ; on FS, absent from DB
(def db-noid-only (set/difference db-noid fs-nodb))    ; DB id-less, no FS plan
(println "FS no-db-id plans          :" (count fs-nodb))
(println "DB rel-path entities no id :" (count db-noid))
(println "  in-DB-but-id-less (∩)    :" (count both))
(println "  FS-only (not in DB)      :" (count fs-only))
(println "  DB-only (excluded/gone)  :" (count db-noid-only))
(println "\nFS-only files (not in DB at all):")
(doseq [r (sort fs-only)] (println "   " r))
(println "\nDB-only id-less (top-dir tally):")
(pp/pprint (frequencies (map #(first (clojure.string/split % #"/")) db-noid-only)))
(spit (str out "/no-db-id-decomposition.edn")
      (with-out-str (pp/pprint {:fs-no-db-id (count fs-nodb)
                                :db-no-id (count db-noid)
                                :in-db-but-idless (count both)
                                :fs-only (vec (sort fs-only))
                                :db-only-count (count db-noid-only)
                                :db-only-top-dirs (frequencies (map #(first (clojure.string/split % #"/")) db-noid-only))})))
