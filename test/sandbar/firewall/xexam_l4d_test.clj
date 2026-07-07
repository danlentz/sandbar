(ns sandbar.firewall.xexam-l4d-test
  "Cross-exam probe 4: EXACT shape of the leaked cites value in full-projection."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.api.projection :as proj]
            [sandbar.db.datomic :as sdb]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "xexam-l4d" :auth? false}))

(deftest probe-exact-leak-shape
  (sup/seed-context! :ctx/pbw :public-bottom)
  (sup/seed-project! :proj/PW :public :ctx/pbw :public-bottom)
  (sup/seed-memory! :mem/AW :public :proj/PW)
  (sup/seed-memory! :mem/BW :private :proj/PW {:mm.memory/body-raw "SECRET-PRIVATE-BODY"})
  (sup/raw-transact! [{:db/ident :mem/AW :mm.memory/cites {:db/ident :mem/BW}}])
  (let [db     (sdb/db)
        a-ent  (d/entity db :mem/AW)
        b-eid  (:db/id (d/entity db :mem/BW))
        full   (proj/full-projection a-ent)
        cval   (:mm.memory/cites full)
        raw-v  (first (:mm.memory/cites a-ent))]
    (println "\n=== L4d EXACT LEAK SHAPE ===")
    (println "  B (private) eid:" b-eid " ident: :mem/BW")
    (println "  full-projection[:mm.memory/cites]:" (pr-str cval))
    (println "  class of first raw cites value:" (class raw-v))
    (println "  does projection disclose B ident :mem/BW ?:"
             (boolean (some #(= :mem/BW %)
                            (cond (coll? cval) (flatten (map (fn [x] (if (map? x) (vals x) [x])) cval))
                                  :else [cval]))))
    (println "  does projection disclose B eid" b-eid "?:"
             (boolean (some #(= b-eid %)
                            (cond (coll? cval) (flatten (map (fn [x] (if (map? x) (vals x) [x])) cval))
                                  :else [cval]))))
    ;; Also: metadata-only projection of the raw value (what edges-of would do)
    (println "  metadata-projection of raw cites value:" (pr-str (proj/metadata-projection raw-v)))
    ;; And: does entity.find-style full projection of B itself expose the body?
    ;; (not the point — the point is A leaking B's identity)
    (is true)))
