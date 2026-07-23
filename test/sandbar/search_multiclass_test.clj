(ns sandbar.search-multiclass-test
  "D7 multi-class :class-vec tests for sandbar.search/search-bm25f.

  Per D7 of decisions/c8_ratification_batch_d1_d9_plus_defaults_all_approved_
  2026_07_02 (Dan-ratified): `:class` accepts a single class-ident (unchanged)
  OR a vec of 2..8 class idents (strategic-subgroup scope; closes the C12 gap).

  Test map (spec §Tests):
   - T1 back-compat: single-class string behaves byte-identically to HEAD.
   - T2 multi-class union: seed entities in 2-3 classes; vec query returns
     hits from all classes, sorted by score, :limit applied post-merge;
     each hit's class distinguishable via :dt/type.
   - T3 caps + errors: empty vec, single-element vec, >8 classes → loud
     errors; :field-weights + vec → loud error.
   - T4 :where with vec (per-class application) + :facet-by over merged set.
   - T5 cache neutrality: repeated multi-class calls hit the per-class caches;
     no cache-key collision between single- and multi-class calls for the
     same class.

  Fixtures follow sandbar.search-test — in-memory Datomic + BM25F cache
  clear between fixtures.  Seeds via dt/make (substrate-discipline; no raw
  d/transact)."
  (:require [clojure.test :refer :all]
            [sandbar.search :as search]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "search-multiclass-test"})
  (fn [t] (search/clear-bm25f-cache!) (t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seed helpers — three BM25F-weighted classes with distinctive tokens
;;
;; :mm/Memory  — weights on :mm.memory/name + :description + :body-raw
;; :mm/Tag     — weights on :mm.tag/value + :alt-label + :definition + ...
;; :mm/Verb    — weights on :mm.verb/name + :title + :when + ...
;;
;; All three are in the config :required-schema set (:mm + :mm-verb), so
;; the test fixture loads them.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory!
  [name body-raw]
  (dt/make :mm/Memory
           {:mm.memory/rel-path (str "test/" name ".md")
            :mm.memory/name     name
            :mm.memory/body-raw body-raw}))

(defn- make-tag!
  [value definition]
  (dt/make :mm/Tag
           {:mm.tag/value      value
            :mm.tag/definition definition}))

(defn- make-verb!
  [name title]
  (dt/make :mm/Verb
           {:mm.verb/name  name
            :mm.verb/title title}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T1 — back-compat: single-class string byte-identical to HEAD behavior
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t1-single-class-back-compat-result-shape
  (testing "T1: single-class :class keyword returns the unchanged canonical shape"
    (make-memory! "datomic-notes" "datomic project graph realizes anderson lineage")
    (let [result (search/search-bm25f {:query "datomic" :class :mm/Memory})]
      (is (contains? result :hits))
      (is (contains? result :total))
      (is (contains? result :returned))
      (is (contains? result :timing))
      (is (= 1 (:total result)))
      (is (= 1 (:returned result)))
      (let [hit (first (:hits result))]
        (is (contains? hit :entity))
        (is (contains? hit :eid))
        (is (contains? hit :score))
        (is (pos? (:score hit)))))))

(deftest t1-single-class-identical-hits-and-scores
  (testing "T1: single-class dispatch produces the SAME hits + scores as
            the single-class pipeline (no regression from the dispatcher)"
    (make-memory! "alpha" "datomic single mention")
    (make-memory! "beta"  "datomic datomic datomic many mentions")
    (make-memory! "gamma" "unrelated body text")
    (let [result (search/search-bm25f {:query "datomic" :class :mm/Memory})
          names  (mapv #(get-in % [:entity :mm.memory/name]) (:hits result))
          scores (mapv :score (:hits result))]
      (is (= 2 (:total result)) "Two memories mention 'datomic'")
      (is (= ["beta" "alpha"] names)
          "beta (3 mentions) outscores alpha (1 mention); descending order")
      (is (apply >= scores) "Scores in non-increasing order"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T2 — multi-class union: hits from all classes, sorted, limit post-merge,
;;       class distinguishable
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t2-multi-class-union-across-classes
  (testing "T2: a vec :class returns hits from ALL classes matching the token"
    ;; Shared token 'orchestration' seeded into one entity of each class.
    (make-memory! "mem-orch" "orchestration of workflow phases in the substrate")
    (make-tag!    "orchestration" "the act of coordinating workflow phases")
    (make-verb!   "sandbar.workflow.orchestrate" "orchestration of a workflow process")
    ;; Noise entities (no shared token) to confirm filtering.
    (make-memory! "mem-noise" "totally unrelated content here")
    (make-tag!    "noise-tag" "nothing to do with the query")
    (let [result (search/search-bm25f
                   {:query "orchestration"
                    :class [:mm/Memory :mm/Tag :mm/Verb]})
          types  (into #{} (map #(get-in % [:entity :dt/type])) (:hits result))]
      (is (= 3 (:total result))
          "One hit from each of the three classes matches 'orchestration'")
      (is (= 3 (:returned result)))
      (testing "each hit's class is distinguishable via :dt/type (metadata-only projection)"
        (is (= #{:mm/Memory :mm/Tag :mm/Verb} types)
            (str "Merged hits span all three classes; got: " types)))
      (testing "every hit carries :dt/type, :eid, :score"
        (doseq [hit (:hits result)]
          (is (some? (get-in hit [:entity :dt/type])))
          (is (contains? hit :eid))
          (is (pos? (:score hit))))))))

(deftest t2-dt-type-survives-metadata-only-projection
  (testing "T2 / decision-token-3: :dt/type survives the METADATA-ONLY
            projection specifically (not just the default full projection),
            so callers can tell merged classes apart even under the leanest
            per-hit shape.  Locks the projection.clj metadata-projection
            :dt/type-retention against future projection regressions."
    (make-memory! "mem-orch" "orchestration of workflow phases in the substrate")
    (make-tag!    "orchestration" "the act of coordinating workflow phases")
    (make-verb!   "sandbar.workflow.orchestrate" "orchestration of a workflow process")
    (let [result (search/search-bm25f
                   {:query      "orchestration"
                    :class      [:mm/Memory :mm/Tag :mm/Verb]
                    :projection :metadata-only})
          entities (mapv :entity (:hits result))
          types    (into #{} (map :dt/type) entities)]
      (is (= 3 (:total result))
          "One hit per class matches 'orchestration' under metadata-only projection")
      (testing "every hit's :entity carries :dt/type under :metadata-only"
        (doseq [e entities]
          (is (contains? e :dt/type)
              (str "metadata-only entity must retain :dt/type; got keys: " (keys e)))))
      (testing "the metadata-only projection is genuinely lean (no full-body slots)"
        ;; A metadata-only Memory hit must NOT carry the heavy :mm.memory/body-raw
        ;; slot — proving this is the metadata-only shape, not the full one.
        (let [mem-entity (first (filter #(= :mm/Memory (:dt/type %)) entities))]
          (is (some? mem-entity))
          (is (not (contains? mem-entity :mm.memory/body-raw))
              "metadata-only projection strips :mm.memory/body-raw")))
      (is (= #{:mm/Memory :mm/Tag :mm/Verb} types)
          (str "All three classes distinguishable via :dt/type under metadata-only; got: " types)))))

(deftest t2-multi-class-hits-sorted-by-score-descending
  (testing "T2: merged hits are sorted by raw score descending across classes"
    (make-memory! "m1" "widget widget widget widget many widget mentions")
    (make-memory! "m2" "widget single mention")
    (make-tag!    "widget-tag" "a widget concept")
    (make-verb!   "sandbar.widget.make" "make a widget entity")
    (let [result (search/search-bm25f
                   {:query "widget"
                    :class [:mm/Memory :mm/Tag :mm/Verb]})
          scores (mapv :score (:hits result))]
      (is (>= (:total result) 2))
      (is (apply >= scores)
          (str "Merged scores must be non-increasing, got: " scores)))))

(deftest t2-multi-class-limit-applied-post-merge
  (testing "T2: :limit is applied AFTER the merge (truncates the merged list,
            :total reflects full merged match-set)"
    (dotimes [n 3] (make-memory! (str "m-" n) "convergence in the memory body"))
    (dotimes [n 3] (make-tag! (str "convergence-tag-" n) "convergence definition text"))
    (let [result (search/search-bm25f
                   {:query "convergence"
                    :class [:mm/Memory :mm/Tag]
                    :limit 2})]
      (is (= 6 (:total result))    ":total is the full merged match count (3+3)")
      (is (= 2 (:returned result)) ":returned is post-merge limit")
      (is (= 2 (count (:hits result)))))))

(deftest t2-multi-class-limit-zero-no-cap
  (testing "T2: :limit 0 returns every merged hit (no cap)"
    (dotimes [n 3] (make-memory! (str "z-" n) "zephyr in the body"))
    (make-tag! "zephyr-tag" "a zephyr definition")
    (let [result (search/search-bm25f
                   {:query "zephyr"
                    :class [:mm/Memory :mm/Tag]
                    :limit 0})]
      (is (= 4 (:total result)))
      (is (= 4 (:returned result)))
      (is (= 4 (count (:hits result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T3 — caps + errors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t3-empty-vec-loud-error
  (testing "T3: empty :class vec → loud error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (search/search-bm25f {:query "x" :class []})))))

(deftest t3-single-element-vec-loud-error
  (testing "T3: single-element :class vec → loud error (use a bare ident instead)"
    (is (thrown? clojure.lang.ExceptionInfo
                 (search/search-bm25f {:query "x" :class [:mm/Memory]})))))

(deftest t3-over-cap-vec-loud-error
  (testing "T3: >8 classes → loud error (a subgroup is a curated handful)"
    (let [nine (vec (repeat 9 :mm/Memory))]
      (is (thrown? clojure.lang.ExceptionInfo
                   (search/search-bm25f {:query "x" :class nine}))))))

(deftest t3-non-keyword-member-loud-error
  (testing "T3: a non-keyword vec member → loud error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (search/search-bm25f {:query "x" :class [:mm/Memory "mm/Tag"]})))))

(deftest t3-field-weights-with-vec-loud-error
  (testing "T3: :field-weights + a :class vec → loud error (per-class
            overrides are out of v1)"
    (make-memory! "m" "datomic body")
    (make-tag! "t" "datomic definition")
    (is (thrown? clojure.lang.ExceptionInfo
                 (search/search-bm25f
                   {:query "datomic"
                    :class [:mm/Memory :mm/Tag]
                    :field-weights {:mm.memory/body-raw 1.0}})))))

(deftest t3-eight-classes-at-cap-ok
  (testing "T3: exactly 8 classes is at the cap (no error); dedup not required —
            the cap counts vec length"
    ;; 8 identical idents exercises the cap boundary without needing 8
    ;; distinct weighted classes.  Should not throw the cap error.
    (make-memory! "m" "boundary token here")
    (let [eight (vec (repeat 8 :mm/Memory))
          result (search/search-bm25f {:query "boundary" :class eight})]
      (is (contains? result :hits))
      (is (integer? (:total result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T4 — :where (per-class) + :facet-by (over merged set)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-typed-memory!
  [name memory-type body-raw]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    (str "test/" name ".md")
            :mm.memory/name        name
            :mm.memory/memory-type memory-type
            :mm.memory/body-raw    body-raw}))

(deftest t4-where-applies-per-class
  (testing "T4: :where applies per-class — a Memory-scoped predicate filters
            Memory hits but does not exclude Tag hits (Tag has no such slot)"
    (make-typed-memory! "dec-1" :decision "cartography in a decision")
    (make-typed-memory! "plan-1" :plan    "cartography in a plan")
    (make-tag! "cartography" "the study of maps")
    (let [;; :where restricts Memory hits to :decision; Tag has no
          ;; :mm.memory/memory-type slot, so the clause matches zero Tag
          ;; instances — Tag contributes nothing under this predicate.
          result (search/search-bm25f
                   {:query "cartography"
                    :class [:mm/Memory :mm/Tag]
                    :where '[[?e :mm.memory/memory-type :decision]]})
          types  (mapv #(get-in % [:entity :dt/type]) (:hits result))]
      (is (= 1 (:total result))
          "Only the :decision Memory survives the per-class :where; the plan
           Memory is filtered and the Tag matches zero (no such slot)")
      (is (= [:mm/Memory] types))
      (is (= :decision (-> result :hits first :entity :mm.memory/memory-type))))))

(deftest t4-facet-by-over-merged-set
  (testing "T4: :facet-by facets over the MERGED full match-set (spanning
            all classes), computed before :limit truncation"
    (make-memory! "m1" "topology in the body")
    (make-memory! "m2" "topology again")
    (make-tag!    "topology-a" "topology definition a")
    (make-tag!    "topology-b" "topology definition b")
    (make-tag!    "topology-c" "topology definition c")
    (let [result (search/search-bm25f
                   {:query    "topology"
                    :class    [:mm/Memory :mm/Tag]
                    :limit    1                          ; truncate hits
                    :facet-by [:dt/type]})
          type-counts (get-in result [:facets :dt/type])]
      (is (= 5 (:total result))    ":total is the full merged match count")
      (is (= 1 (:returned result)) ":returned reflects post-merge limit")
      (is (contains? (:facets result) :dt/type))
      (is (= 2 (get type-counts :mm/Memory))
          "facet counts span the FULL merged set, not just the 1 limited hit")
      (is (= 3 (get type-counts :mm/Tag))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T5 — cache neutrality
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t5-repeated-multi-class-calls-stable
  (testing "T5: repeated multi-class calls return identical results (per-class
            caches serve the same analyzed corpus on each call)"
    (make-memory! "m1" "resonance in the body")
    (make-tag!    "resonance-tag" "resonance definition")
    (let [call #(search/search-bm25f
                  {:query "resonance" :class [:mm/Memory :mm/Tag]})
          r1 (call)
          r2 (call)
          r3 (call)]
      (is (= (:total r1) (:total r2) (:total r3)))
      (is (= (mapv :eid (:hits r1))
             (mapv :eid (:hits r2))
             (mapv :eid (:hits r3)))
          "Merged hit-eid order is stable across repeated calls")
      (is (= (mapv :score (:hits r1))
             (mapv :score (:hits r2)))
          "Scores are stable across repeated calls (cache-served, not recomputed differently)"))))

(deftest t5-no-cache-collision-single-vs-multi
  (testing "T5: a single-class call and a multi-class call over the SAME class
            agree on that class's hits (no cache-key collision between the two
            call shapes — both hit the same per-class cache)"
    (make-memory! "m-alpha" "quasar in the memory body")
    (make-memory! "m-beta"  "quasar quasar quasar strong memory signal")
    (make-tag!    "quasar-tag" "quasar definition text")
    (let [single (search/search-bm25f {:query "quasar" :class :mm/Memory})
          multi  (search/search-bm25f {:query "quasar" :class [:mm/Memory :mm/Tag]})
          ;; Extract just the Memory hits from the merged multi-class result.
          multi-memory-hits (filterv #(= :mm/Memory (get-in % [:entity :dt/type]))
                                     (:hits multi))]
      (is (= 2 (:total single)) "Two Memory instances mention 'quasar'")
      (is (= (mapv :eid (:hits single))
             (mapv :eid multi-memory-hits))
          "The Memory subset of the multi-class merge matches the single-class
           call's hit-eid order exactly — same per-class cache, no collision")
      (is (= (mapv :score (:hits single))
             (mapv :score multi-memory-hits))
          "Per-class scores are identical whether called single- or multi-class"))))
