(ns sandbar.firewall.placeholder-test
  "Author-time forward placeholders stay unresolved until filled; stored
   content and every read/traverse label remain fail-closed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.enforce :as enforce]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-placeholder" :auth? false}))

(defn- seed-public-source! []
  (sup/seed-context! :ctx/placeholder-public :public-bottom)
  (sup/seed-project! :proj/placeholder-public :public :ctx/placeholder-public :public-bottom)
  (sup/seed-memory! :mem/placeholder-source :public :proj/placeholder-public))

(defn- verdict [target]
  (enforce/check-entity-flow
    (db/db) :mm/Memory
    {:mm.memory/visibility :public
     :mm.memory/owning-project :proj/placeholder-public
     :mm.memory/cites [target]}))

(deftest a-forward-placeholder-is-unresolved-for-authoring-and-private-for-reading
  (let [source (seed-public-source!)
        uuid (java.util.UUID/randomUUID)]
    (sup/raw-transact! [{:db/ident :mem/ident-only}
                       {:db/ident :mem/identity-with-uuid :mm/id uuid}])
    (doseq [target [:mem/ident-only :mem/identity-with-uuid]]
      (testing (str target)
        (let [eid (sup/eid-of target)
              database (db/db)]
          (doseq [reference [target eid {:db/ident target} [:db/ident target]]]
            (let [{:keys [violations skipped]} (verdict reference)]
              (is (empty? violations))
              (is (= [{:slot :mm.memory/cites :target-ref reference :reason :unresolved}]
                     skipped))))
          (is (= :private (:sensitivity (label/label-of-eid database eid))))
          (is (some? (enforce/hop-forbidden? database source :mm.memory/cites eid))))))
    (testing "filling the same identity with a private document immediately restores author-time refusal"
      (sup/raw-transact! [{:db/ident :mem/identity-with-uuid
                          :dt/type :mm/Memory :mm.memory/visibility :private
                          :mm.memory/owning-project :proj/placeholder-public}])
      (is (= uuid (:mm/id (d/entity (db/db) :mem/identity-with-uuid))))
      (let [{:keys [violations skipped]} (verdict :mem/identity-with-uuid)]
        (is (= [:flow-forbidden] (mapv :reason violations)))
        (is (empty? skipped))))))

(deftest a-missing-class-does-not-erase-existing-content-or-classification
  (seed-public-source!)
  (doseq [[slug facts] [["private" {:mm.memory/visibility :private}]
                       ["public-but-untyped" {:mm.memory/visibility :public}]
                       ["body" {:mm.memory/body-raw "Unclassified content"}]
                       ["name" {:mm.memory/name "Incomplete existing record"}]
                       ["owner" {:mm.memory/owning-project :proj/placeholder-public}]
                       ["unknown-class" {:dt/type :dt/Resource}]]]
    (let [ident (keyword "mem" (str "incomplete-" slug))]
      (sup/raw-transact! [(assoc facts :db/ident ident)])
      (testing slug
        (is (= :private (:sensitivity (label/label-of-ref (db/db) ident))))
        (let [{:keys [violations skipped]} (verdict ident)]
          (is (= [:flow-forbidden] (mapv :reason violations)))
          (is (empty? skipped)))))))
