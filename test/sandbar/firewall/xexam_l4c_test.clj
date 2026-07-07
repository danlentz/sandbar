(ns sandbar.firewall.xexam-l4c-test
  "Cross-exam probe 3: does full-projection of public A expose private B's eid
   through the stored cites datom? (the L4-finding-3 leak surface)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.api.projection :as proj]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as sdb]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "xexam-l4c" :auth? false}))

(deftest probe-projection-exposes-stored-bad-edge
  ;; Seed via raw transact (bypass firewall): public A, private B, A cites B.
  ;; Then full-project A and check whether B's eid/ident appears.
  (sup/seed-context! :ctx/pbz :public-bottom)
  (sup/seed-project! :proj/PZ :public :ctx/pbz :public-bottom)
  (sup/seed-memory! :mem/AZ :public :proj/PZ)
  (sup/seed-memory! :mem/BZ :private :proj/PZ)
  (sup/raw-transact! [{:db/ident :mem/AZ :mm.memory/cites {:db/ident :mem/BZ}}])
  (let [db     (sdb/db)
        a-ent  (d/entity db :mem/AZ)
        b-eid  (:db/id (d/entity db :mem/BZ))
        ;; how does the entity API surface cites?
        cites-vals (:mm.memory/cites a-ent)
        full   (proj/full-projection a-ent)
        raw    (d/q '[:find ?t :in $ ?a :where [?a :mm.memory/cites ?t]] db (:db/id a-ent))]
    (println "\n=== L4c PROJECTION LEAK PROBE ===")
    (println "  A label:" (:sensitivity (label/label-of-eid db (:db/id a-ent)))
             " B label:" (:sensitivity (label/label-of-eid db b-eid)) " B eid:" b-eid)
    (println "  raw stored cites datoms:" (vec raw))
    (println "  (:mm.memory/cites a-ent) count:" (count cites-vals)
             " vals:" (mapv :db/id cites-vals))
    (println "  full-projection :mm.memory/cites key:" (:mm.memory/cites full))
    (println "  >>> B eid PRESENT in full-projection of public A?:"
             (boolean (some #(= b-eid (:db/id %)) (when (coll? (:mm.memory/cites full))
                                                    (:mm.memory/cites full)))))
    ;; also check: is :mm.memory/cites even in :mm/Memory :dt/slots (MF-1)?
    (let [slots (:dt/slots (d/entity db :mm/Memory))
          slot-idents (set (map #(:db/ident %) slots))]
      (println "  :mm.memory/cites in :mm/Memory :dt/slots (MF-1 materialized)?:"
               (contains? slot-idents :mm.memory/cites)))))
