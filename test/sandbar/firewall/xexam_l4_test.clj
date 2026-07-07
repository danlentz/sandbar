(ns sandbar.firewall.xexam-l4-test
  "Cross-exam probe: the L4 CA-6 same-batch public->private OVER-PERMIT claim."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as sdb]
            [sandbar.firewall.enforce :as fw-enforce]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "xexam-l4" :auth? false}))

(defn- classify
  "Run thunk; return [:firewall-refused] / [:committed] / [:other-error msg]."
  [thunk]
  (try (thunk) [:committed]
    (catch clojure.lang.ExceptionInfo e
      (if (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))
        [:firewall-refused]
        [:other-error (.getMessage e)]))
    (catch Throwable t [:other-error (.getMessage t)])))

;; Use the codec upsert-map ref shape {:db/ident kw} for cross-batch refs so
;; raw Datomic resolves same-batch siblings (what the real codec produces).
(deftest l4-validated-make-all-public-A-cites-private-B
  (sup/seed-context! :ctx/pb2 :public-bottom)
  (let [batch [{:db/ident :proj/P2 :dt/type :mm/Project
                :mm.memory/name "P2" :mm.project/ident :proj/P2
                :mm.project/corpus-repo "test-repo"
                :mm.project/default-visibility :public
                :mm.project/firewall-class :public-bottom
                :mm.project/runs-in-context {:db/ident :ctx/pb2}}
               {:db/ident :mem/A2 :dt/type :mm/Memory
                :mm.memory/name "A2" :mm.memory/visibility :public
                :mm.memory/owning-project {:db/ident :proj/P2}
                :mm.memory/cites {:db/ident :mem/B2}}
               {:db/ident :mem/B2 :dt/type :mm/Memory
                :mm.memory/name "B2" :mm.memory/visibility :private
                :mm.memory/owning-project {:db/ident :proj/P2}}]
        outcome (classify #(dt/make-all batch))]
    (println "\n=== L4 validated make-all outcome:" outcome "===")
    (when (= :committed (first outcome))
      (let [db    (sdb/db)
            a-ent (d/entity db :mem/A2)  b-ent (d/entity db :mem/B2)
            a-eid (:db/id a-ent)         b-eid (:db/id b-ent)]
        (when (and a-eid b-eid)
          (let [a-lbl (label/label-of-eid db a-eid)
                b-lbl (label/label-of-eid db b-eid)
                cites (set (map :db/id (:mm.memory/cites a-ent)))]
            (println "  A2 sens:" (:sensitivity a-lbl) " B2 sens:" (:sensitivity b-lbl)
                     " edge-stored:" (contains? cites b-eid))
            (println "  >>> STORED FORBIDDEN public->private EDGE:"
                     (and (= :public (:sensitivity a-lbl))
                          (= :private (:sensitivity b-lbl))
                          (contains? cites b-eid)))
            (println "  EP-3 recheck (non-empty=caught at traverse):"
                     (mapv :reason (fw-enforce/governed-edge-verdicts
                                     db a-eid [[:mm.memory/cites b-eid]])))))))
    (is (or (= :committed (first outcome)) (= :firewall-refused (first outcome))))))

(deftest l4-unvalidated-make-all-star-public-A-cites-private-B
  (sup/seed-context! :ctx/pb1 :public-bottom)
  (let [batch [{:db/ident :proj/P1 :dt/type :mm/Project
                :mm.memory/name "P1" :mm.project/ident :proj/P1
                :mm.project/corpus-repo "test-repo"
                :mm.project/default-visibility :public
                :mm.project/firewall-class :public-bottom
                :mm.project/runs-in-context {:db/ident :ctx/pb1}}
               {:db/ident :mem/A1 :dt/type :mm/Memory
                :mm.memory/name "A1" :mm.memory/visibility :public
                :mm.memory/owning-project {:db/ident :proj/P1}
                :mm.memory/cites {:db/ident :mem/B1}}
               {:db/ident :mem/B1 :dt/type :mm/Memory
                :mm.memory/name "B1" :mm.memory/visibility :private
                :mm.memory/owning-project {:db/ident :proj/P1}}]
        outcome (classify #(dt/make-all* batch))]
    (println "\n=== L4 unvalidated make-all* outcome:" outcome "===")
    (when (= :committed (first outcome))
      (let [db    (sdb/db)
            a-ent (d/entity db :mem/A1)  b-ent (d/entity db :mem/B1)
            a-eid (:db/id a-ent)         b-eid (:db/id b-ent)]
        (when (and a-eid b-eid)
          (let [a-lbl (label/label-of-eid db a-eid)
                b-lbl (label/label-of-eid db b-eid)
                cites (set (map :db/id (:mm.memory/cites a-ent)))]
            (println "  A1 sens:" (:sensitivity a-lbl) " B1 sens:" (:sensitivity b-lbl)
                     " edge-stored:" (contains? cites b-eid))
            (println "  >>> STORED FORBIDDEN public->private EDGE:"
                     (and (= :public (:sensitivity a-lbl))
                          (= :private (:sensitivity b-lbl))
                          (contains? cites b-eid)))
            (println "  EP-3 recheck (non-empty=caught at traverse):"
                     (mapv :reason (fw-enforce/governed-edge-verdicts
                                     db a-eid [[:mm.memory/cites b-eid]])))))))
    (is (or (= :committed (first outcome)) (= :firewall-refused (first outcome))))))

(deftest l4-sequential-baseline-refuses
  (sup/seed-context! :ctx/pb3 :public-bottom)
  (dt/make :mm/Project {:db/ident :proj/P3 :mm.memory/name "P3"
                        :mm.project/ident :proj/P3 :mm.project/corpus-repo "r"
                        :mm.project/default-visibility :public
                        :mm.project/firewall-class :public-bottom
                        :mm.project/runs-in-context :ctx/pb3})
  (dt/make :mm/Memory {:db/ident :mem/B3 :mm.memory/name "B3"
                       :mm.memory/visibility :private :mm.memory/owning-project :proj/P3})
  (let [outcome (classify
                  #(dt/make :mm/Memory {:db/ident :mem/A3 :mm.memory/name "A3"
                                        :mm.memory/visibility :public
                                        :mm.memory/owning-project :proj/P3
                                        :mm.memory/cites :mem/B3}))]
    (println "\n=== L4 sequential (resolvable owner) outcome:" outcome "(want :firewall-refused) ===")
    (is (= :firewall-refused (first outcome)))))
