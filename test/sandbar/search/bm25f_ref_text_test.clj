(ns sandbar.search.bm25f-ref-text-test
  "Named and anonymous references must contribute their declared one-hop text.
  Real Datomic reads are essential: an ident-bearing ref is returned as a
  keyword, so plain-map-only tests do not exercise the ordinary stored shape."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.search :as search]
            [sandbar.search.bm25f :as bm]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "bm25f-ref-text" :auth? false})
  (fn [f] (search/clear-bm25f-cache!) (f)))

(defn- seed! []
  (let [named (d/tempid :db.part/user) anonymous (d/tempid :db.part/user)]
    @(d/transact (db/conn)
       [{:db/id named :db/ident :ref-test/named-tag :dt/type :mm/Tag
         :mm.tag/value "cobaltmarker"}
        ;; Different unique values prevent Datomic from upserting these two
        ;; targets into one entity, which would invalidate the anonymous control.
        {:db/id anonymous :dt/type :mm/Tag :mm.tag/value "rubymarker"}
        {:db/ident :ref-test/named-memory :dt/type :mm/Observation
         :mm.memory/name "Named reference" :mm.memory/visibility :public
         :mm.memory/tags [named]}
        {:db/ident :ref-test/anonymous-memory :dt/type :mm/Observation
         :mm.memory/name "Anonymous reference" :mm.memory/visibility :public
         :mm.memory/tags [anonymous]}])))

(defn- find-ids [class query]
  (set (map #(get-in % [:entity :db/ident])
            (:hits (search/search-bm25f {:class class :query query
                                        :projection :metadata-only :limit 0})))))

(deftest stored-named-and-anonymous-targets-contribute-text
  (seed!)
  (let [named-ref (first (:mm.memory/tags (db/entity :ref-test/named-memory)))
        anon-ref (first (:mm.memory/tags (db/entity :ref-test/anonymous-memory)))]
    (is (= :ref-test/named-tag named-ref) "actual keyword reference")
    (is (not (keyword? anon-ref)) "genuinely anonymous target")
    (is (not= (:db/id (db/entity named-ref)) (:db/id anon-ref))))
  (doseq [class [:mm/Observation :mm/Memory]]
    (is (= #{:ref-test/named-memory} (find-ids class "cobaltmarker")))
    (is (= #{:ref-test/anonymous-memory} (find-ids class "rubymarker")))))

(deftest named-reference-field-score-explains-the-hit
  (seed!)
  (let [r (search/search-bm25f {:class :mm/Observation :query "cobaltmarker"
                               :projection :metadata-only
                               :include #{:field-scores}})
        hit (first (:hits r))]
    (is (= 1 (:total r)))
    (is (= :ref-test/named-memory (get-in hit [:entity :db/ident])))
    (is (pos? (get-in hit [:field-scores :mm.memory/tags] 0)))
    (is (zero? (get-in hit [:field-scores :mm.memory/body-raw] 0)))))

(deftest changing-a-named-target-refreshes-warmed-referrers
  (seed!)
  (doseq [class [:mm/Observation :mm/Memory]]
    (is (= #{:ref-test/named-memory} (find-ids class "cobaltmarker"))))
  @(d/transact (db/conn) [[:db/add :ref-test/named-tag :mm.tag/value "sapphiremarker"]])
  (let [tag (db/entity :ref-test/named-tag)]
    (search/entity-changed! :mm/Tag (into {:db/id (:db/id tag)} tag)))
  (doseq [class [:mm/Observation :mm/Memory]]
    (is (= #{} (find-ids class "cobaltmarker")))
    (is (= #{:ref-test/named-memory} (find-ids class "sapphiremarker")))
    (is (= #{:ref-test/anonymous-memory} (find-ids class "rubymarker")))))

(deftest existing-reference-and-primitive-shapes-remain-bounded
  (seed!)
  (let [tag (db/entity :ref-test/named-tag)
        slot :mm.memory/tags
        raw (fn [value] (#'bm/raw-field {slot value} slot))]
    (doseq [value [:ref-test/named-tag (:db/id tag) tag (into {:db/id (:db/id tag)} tag)
                   #{:ref-test/named-tag} #{(:db/id tag)} #{tag}]]
      (is (= "cobaltmarker" (raw value)) (pr-str (type value))))
    (is (nil? (raw :ref-test/absent)))
    (is (nil? (raw #{:ref-test/absent})))
    (is (nil? (raw nil)))
    (is (= "direct text" (#'bm/raw-field {:mm.memory/body-raw "direct text"}
                                        :mm.memory/body-raw)))
    (is (= #{"first" "second"}
           (set (str/split (#'bm/raw-field {:mm.tag/alt-label #{"first" "second"}}
                                         :mm.tag/alt-label) #" "))))))
