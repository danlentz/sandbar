(ns sandbar.search-canonical-order-test
  "D4c (reliability sprint, 2026-09-19): the search surface's default
  ordering is status- and recency-aware, declared per class in the schema
  and read through the same ancestor walk as the BM25F weights.

   (a) A SUPERSEDED TWIN YIELDS TO ITS CURRENT SUCCESSOR — with identical
       text (equal raw relevance) the current memorial ranks first; the
       superseded one carries `:superseded? true` and its `:score` is the
       raw BM25F score, unchanged.
   (a2) THE DEMOTION IS PROPORTIONAL, NOT AN ABSOLUTE BUCKET — a superseded
       record far more relevant than any current hit still leads, so a
       historical question keeps its answer (Astra's caution, 17:28Z).
   (b) RECENCY BREAKS RELEVANCE TIES — among current hits of equal score the
       more recently touched comes first.
   (c) `:rank-by :relevance` OPTS OUT — pure BM25F order, nothing demoted,
       no `:superseded?` key.
   (d) THE DECLARATIONS ARE INHERITED — a subclass of :mm/Memory sees the
       same declarations through the ancestor walk; a class declaring
       nothing (:mm/Tag) keeps pure relevance order.
   (e) THE MULTI-CLASS MERGE HONORS THE SAME ORDER — the superseded twin
       still ranks after its current successor in a merged list.
   (f) THE MARKER SURVIVES PROJECTION — `:superseded?` is a hit-level key,
       present under metadata-only projection too.
   (g) THE POLICY APPLIES BEFORE :limit — with :limit 1 the current
       successor is the one hit returned, not the higher-scoring twin.

  Proof outside this namespace: the quality harness's supersession-aware
  category (three of seven correct before D4c) on the live corpus, with
  recall, MRR and NDCG held.

  Fixtures follow sandbar.search-multiclass-test — in-memory Datomic +
  BM25F cache clear; seeds via dt/make."
  (:require [clojure.test :refer :all]
            [sandbar.search :as search]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "search-canonical-order-test"})
  (fn [t] (search/clear-bm25f-cache!) (t)))

(def ^:private older #inst "2026-09-01T00:00:00.000-00:00")
(def ^:private newer #inst "2026-09-10T00:00:00.000-00:00")

(defn- make-memory!
  "A memorial of `class` whose name and body carry `text`; `extra` slots
   merged in (a superseded-by edge, a status, a last-touched stamp)."
  [class name text extra]
  (dt/make class
           (merge {:mm.memory/rel-path     (str "test/" name ".md")
                   :mm.memory/name         name
                   :mm.memory/body-raw     text
                   :mm.memory/last-touched older}
                  extra)))

(defn- hit-names [result]
  (mapv #(get-in % [:entity :mm.memory/name]) (:hits result)))

(defn- seed-twins!
  "A current memorial and its superseded twin with IDENTICAL text; the twin
   carries the superseded-by edge.  Returns the canonical's eid."
  [text]
  (let [canonical (make-memory! :mm/Memory "canonical" text {})]
    (make-memory! :mm/Memory "superseded" text {:mm.memory/superseded-by [(:db/id canonical)]})
    (:db/id canonical)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (d) the declarations, read through the ancestor walk
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest declarations-are-read-with-inheritance
  (testing ":mm/Memory declares both; :mm/Decision inherits them; :mm/Tag declares neither"
    (is (= #{[:mm.memory/superseded-by :some] [:mm.memory/status :superseded]}
           (dt/effective-superseded-when-of :mm/Memory)))
    (is (= :mm.memory/last-touched (dt/effective-recency-slot-of :mm/Memory)))
    (is (= (dt/effective-superseded-when-of :mm/Memory)
           (dt/effective-superseded-when-of :mm/Decision))
        "a memorial subclass inherits the parent's declaration")
    (is (= :mm.memory/last-touched (dt/effective-recency-slot-of :mm/Decision)))
    (is (empty? (dt/effective-superseded-when-of :mm/Tag)))
    (is (nil? (dt/effective-recency-slot-of :mm/Tag)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) a superseded twin yields to its current successor
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest superseded-by-edge-yields-to-the-current-successor
  (testing "identical text: equal raw scores, the current memorial first, the twin marked"
    (seed-twins! "quasarium release plan")
    (let [result (search/search-bm25f {:query "quasarium" :class :mm/Memory :projection :full})
          [first-hit second-hit] (:hits result)]
      (is (= ["canonical" "superseded"] (hit-names result)))
      (is (= (:score first-hit) (:score second-hit)) "the raw BM25F scores are equal and unscaled")
      (is (nil? (:superseded? first-hit)) "the current hit carries no marker")
      (is (true? (:superseded? second-hit)) "the demoted hit is marked"))))

(deftest superseded-status-yields-like-the-edge
  (testing "a superseded status demotes exactly like the edge"
    (make-memory! :mm/Memory "current-note" "zorblatt notes" {})
    (make-memory! :mm/Memory "stale-note" "zorblatt notes" {:mm.memory/status :superseded})
    (let [result (search/search-bm25f {:query "zorblatt" :class :mm/Memory :projection :full})]
      (is (= ["current-note" "stale-note"] (hit-names result)))
      (is (true? (:superseded? (second (:hits result))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a2) the demotion is proportional, not an absolute bucket
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-far-more-relevant-superseded-record-still-leads
  (testing "a historical question keeps its answer: the exactly relevant superseded record outranks a barely relevant current one"
    (let [canonical (make-memory! :mm/Memory "current-mention" "one wimbledorf mention" {})]
      (make-memory! :mm/Memory "historical-record"
                    "wimbledorf wimbledorf wimbledorf wimbledorf history wimbledorf ledger wimbledorf"
                    {:mm.memory/superseded-by [(:db/id canonical)]})
      (let [result (search/search-bm25f {:query "wimbledorf ledger" :class :mm/Memory :projection :full})
            [first-hit second-hit] (:hits result)]
        (is (= ["historical-record" "current-mention"] (hit-names result)))
        (is (true? (:superseded? first-hit)) "it is still marked as superseded")
        (is (> (:score first-hit) (* 2 (:score second-hit)))
            "more than twice as relevant, so the halved weight still leads")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) recency breaks relevance ties
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest recency-breaks-ties-among-current-hits
  (testing "two current hits with identical text: the more recently touched comes first"
    (make-memory! :mm/Memory "touched-earlier" "vexillography survey" {:mm.memory/last-touched older})
    (make-memory! :mm/Memory "touched-later"   "vexillography survey" {:mm.memory/last-touched newer})
    (let [result (search/search-bm25f {:query "vexillography" :class :mm/Memory :projection :full})
          [a b]  (:hits result)]
      (is (= (:score a) (:score b)) "equal relevance")
      (is (= ["touched-later" "touched-earlier"] (hit-names result)))
      (is (every? #(pos? (:recency %)) (:hits result)) "each hit carries its recency in epoch ms"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) :rank-by :relevance opts out
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rank-by-relevance-opts-out-of-the-default-order
  (testing "pure BM25F order: the higher-scoring superseded twin comes first and nothing is marked"
    (let [canonical (make-memory! :mm/Memory "canonical-rule" "glimmerwick rule" {})]
      (make-memory! :mm/Memory "superseded-rule" "glimmerwick glimmerwick rule"
                    {:mm.memory/superseded-by [(:db/id canonical)]})
      (let [result (search/search-bm25f {:query "glimmerwick" :class :mm/Memory
                                         :rank-by :relevance :projection :full})]
        (is (= ["superseded-rule" "canonical-rule"] (hit-names result)))
        (is (every? #(nil? (:superseded? %)) (:hits result)))
        (is (every? #(nil? (:recency %)) (:hits result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (e) the multi-class merge honors the same order
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest multi-class-merge-keeps-the-twin-behind-its-successor
  (testing "in a merged list the superseded twin still ranks after its current successor and is marked"
    (seed-twins! "brontosaurian memo")
    (dt/make :mm/Tag {:mm.tag/value "brontosaurian" :mm.tag/definition "a brontosaurian tag"})
    (let [result (search/search-bm25f {:query "brontosaurian" :class [:mm/Memory :mm/Tag]
                                       :projection :full})
          names  (mapv (fn [h] (or (get-in h [:entity :mm.memory/name])
                                   (get-in h [:entity :mm.tag/value])))
                       (:hits result))
          pos    (fn [n] (.indexOf ^java.util.List names n))]
      (is (= 3 (count names)))
      (is (< (pos "canonical") (pos "superseded")) "the successor precedes the twin across the merge")
      (is (true? (:superseded? (nth (:hits result) (pos "superseded"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (f) the marker survives projection; (g) the policy applies before :limit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest marker-survives-metadata-only-projection
  (testing "the MCP default projection keeps :superseded? on the hit"
    (seed-twins! "ptarmigan census")
    (let [result (search/search-bm25f {:query "ptarmigan" :class :mm/Memory :projection :metadata-only})
          [a b]  (:hits result)]
      (is (every? #{:db/id :db/ident :dt/type} (keys (:entity a)))
          "metadata-only entity shape: no name, no body (the fixture's entities carry no ident)")
      (is (nil? (:superseded? a)))
      (is (true? (:superseded? b))))))

(deftest policy-applies-before-limit
  (testing "with :limit 1 the current successor is the one hit returned"
    (let [canonical (make-memory! :mm/Memory "current-limit" "ocelot policy" {})]
      (make-memory! :mm/Memory "superseded-limit" "ocelot ocelot policy"
                    {:mm.memory/superseded-by [(:db/id canonical)]})
      (let [result (search/search-bm25f {:query "ocelot" :class :mm/Memory :limit 1 :projection :full})]
        (is (= 2 (:total result)))
        (is (= ["current-limit"] (hit-names result)))))))
