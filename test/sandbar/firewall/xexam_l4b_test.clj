(ns sandbar.firewall.xexam-l4b-test
  "Cross-exam probe 2: WHY is the cites edge not stored? Is it a firewall
   artifact, a coerce drop, or a datomic upsert issue?"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as sdb]
            [sandbar.firewall.enforce :as fw-enforce]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "xexam-l4b" :auth? false}))

(defn- classify [thunk]
  (try (thunk) [:committed]
    (catch clojure.lang.ExceptionInfo e
      (if (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))
        [:firewall-refused] [:other-error (.getMessage e)]))
    (catch Throwable t [:other-error (.getMessage t)])))

(deftest probe-cites-edge-storage
  ;; Control 1: two PUBLIC memories in a batch, A cites B — does cites store
  ;; via make-all* with the {:db/ident} upsert shape at all?
  (sup/seed-context! :ctx/pbx :public-bottom)
  (let [batch [{:db/ident :proj/PX :dt/type :mm/Project
                :mm.memory/name "PX" :mm.project/ident :proj/PX
                :mm.project/corpus-repo "r"
                :mm.project/default-visibility :public
                :mm.project/firewall-class :public-bottom
                :mm.project/runs-in-context {:db/ident :ctx/pbx}}
               {:db/ident :mem/AX :dt/type :mm/Memory
                :mm.memory/name "AX" :mm.memory/visibility :public
                :mm.memory/owning-project {:db/ident :proj/PX}
                :mm.memory/cites {:db/ident :mem/BX}}
               {:db/ident :mem/BX :dt/type :mm/Memory
                :mm.memory/name "BX" :mm.memory/visibility :public
                :mm.memory/owning-project {:db/ident :proj/PX}}]
        outcome (classify #(dt/make-all* batch))]
    (println "\n=== CONTROL both-public make-all* outcome:" outcome "===")
    (let [db (sdb/db)
          a  (d/entity db :mem/AX)
          bx (:db/id (d/entity db :mem/BX))
          cites (set (map :db/id (:mm.memory/cites a)))
          raw-datoms (d/q '[:find ?t :in $ ?a :where [?a :mm.memory/cites ?t]]
                          db (:db/id a))]
      (println "  BOTH-PUBLIC cites edge stored (via entity):" (contains? cites bx))
      (println "  raw cites datoms on AX:" (vec raw-datoms) " BX eid:" bx))))

(deftest probe-cites-via-raw-transact-no-firewall
  ;; Control 2: seed A (public) + B (private) via RAW transact (bypassing
  ;; firewall entirely), then raw-transact the cites edge.  Confirms whether
  ;; cites-as-{:db/ident} upsert stores at all, independent of firewall.
  (sup/seed-context! :ctx/pby :public-bottom)
  (sup/seed-project! :proj/PY :public :ctx/pby :public-bottom)
  (sup/seed-memory! :mem/AY :public :proj/PY)
  (sup/seed-memory! :mem/BY :private :proj/PY)
  (sup/raw-transact! [{:db/ident :mem/AY :mm.memory/cites {:db/ident :mem/BY}}])
  (let [db (sdb/db)
        a  (d/entity db :mem/AY)
        by (:db/id (d/entity db :mem/BY))
        cites (set (map :db/id (:mm.memory/cites a)))]
    (println "\n=== CONTROL raw-transact cites (public->private) stored:" (contains? cites by) "===")
    (println "  A label:" (:sensitivity (label/label-of-eid db (:db/id a)))
             " B label:" (:sensitivity (label/label-of-eid db by)))
    ;; Now: EP-3 over this genuinely-stored bad edge — is it caught?
    (println "  EP-3 verdict on genuine stored bad edge:"
             (mapv :reason (fw-enforce/governed-edge-verdicts
                             db (:db/id a) [[:mm.memory/cites by]])))))
