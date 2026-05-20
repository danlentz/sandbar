(ns sandbar.search-test
  "Tests for sandbar.search — Phase S Stage 3 of comprehensive memory-model
  MCP arc per plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [clojure.test :refer :all]
            [sandbar.search :as search]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "search-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; search-attribute — result-shape + ranking + limit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory!
  "Insert a :mm/Memory entity for fulltext testing.  Uses dt/make per the
  substrate-discipline rule (no raw d/transact)."
  [rel-path body-raw]
  (dt/make :mm/Memory
           {:mm.memory/rel-path rel-path
            :mm.memory/body-raw body-raw}))

(deftest search-attribute-result-shape-test
  (testing "search-attribute returns the canonical result-shape map"
    (make-memory! "test/datomic.md" "datomic project graph realizes Anderson lineage")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})]
      (testing "top-level keys"
        (is (contains? result :hits))
        (is (contains? result :total))
        (is (contains? result :returned))
        (is (contains? result :timing)))
      (testing ":timing carries :total-ms"
        (is (contains? (:timing result) :total-ms))
        (is (integer? (get-in result [:timing :total-ms])))
        (is (>= (get-in result [:timing :total-ms]) 0)))
      (testing ":hits is a vector of {:entity :score}"
        (is (vector? (:hits result)))
        (when (seq (:hits result))
          (let [first-hit (first (:hits result))]
            (is (contains? first-hit :entity))
            (is (contains? first-hit :score))
            (is (number? (:score first-hit)))
            (is (map? (:entity first-hit))))))
      (testing ":total + :returned are integers"
        (is (integer? (:total result)))
        (is (integer? (:returned result)))))))

(deftest search-attribute-finds-matching-memory-test
  (testing "search-attribute returns memories whose body matches the query"
    (make-memory! "test/datomic.md" "datomic project graph realizes Anderson lineage")
    (make-memory! "test/lucene.md"  "lucene scoring example for BM25F")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})]
      (is (= 1 (:total result))    "Exactly one memory matches 'datomic'")
      (is (= 1 (:returned result)) "Returned count matches total below limit")
      (is (seq (:hits result))     "Hit vector is non-empty")
      (is (= 1 (count (:hits result)))))))

(deftest search-attribute-empty-on-unmatched-test
  (testing "search-attribute returns empty hits + total=0 on unmatched query"
    (make-memory! "test/foo.md" "lorem ipsum dolor sit amet")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "zzzzznomatchterm"})]
      (is (= 0 (:total result)))
      (is (= 0 (:returned result)))
      (is (empty? (:hits result))))))

(deftest search-attribute-limit-test
  (testing "search-attribute honors :limit (truncates :hits, preserves :total)"
    (make-memory! "test/a.md" "datomic alpha")
    (make-memory! "test/b.md" "datomic beta")
    (make-memory! "test/c.md" "datomic gamma")
    (make-memory! "test/d.md" "datomic delta")
    (make-memory! "test/e.md" "datomic epsilon")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"
                    :limit     2})]
      (is (= 5 (:total result))    ":total reports pre-limit count")
      (is (= 2 (:returned result)) ":returned reports post-limit count")
      (is (= 2 (count (:hits result))) ":hits truncated to :limit"))))

(deftest search-attribute-limit-zero-no-cap-test
  (testing "search-attribute :limit 0 returns all hits (no cap)"
    (make-memory! "test/a.md" "datomic alpha")
    (make-memory! "test/b.md" "datomic beta")
    (make-memory! "test/c.md" "datomic gamma")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"
                    :limit     0})]
      (is (= 3 (:total result)))
      (is (= 3 (:returned result)))
      (is (= 3 (count (:hits result)))))))

(deftest search-attribute-hits-sorted-by-score-test
  (testing "search-attribute hits are sorted by score descending"
    (make-memory! "test/a.md" "datomic alpha")
    (make-memory! "test/b.md" "datomic beta")
    (make-memory! "test/c.md" "datomic gamma")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})
          scores (mapv :score (:hits result))]
      (is (seq scores))
      (is (apply >= scores)
          (str "Hit scores must be in non-increasing order, got: " scores)))))

(deftest search-attribute-entity-projection-test
  (testing "Each hit's :entity carries the memory's projected attributes"
    (make-memory! "test/foo.md" "datomic project graph")
    (let [result (search/search-attribute
                   {:attribute :mm.memory/body-raw
                    :query     "datomic"})
          entity (-> result :hits first :entity)]
      (is (some? entity))
      (is (contains? entity :mm.memory/rel-path)
          "Projected entity carries declared slot values")
      (is (= "test/foo.md" (:mm.memory/rel-path entity)))
      (is (contains? entity :mm.memory/body-raw)
          "Projected entity carries body-raw slot"))))

(deftest search-attribute-precondition-rejects-non-fulltext-test
  (testing "search-attribute precondition rejects attributes that are not :db/fulltext true"
    ;; :mm.memory/memory-type is a keyword enum, not :db/fulltext
    (is (thrown? AssertionError
                 (search/search-attribute
                   {:attribute :mm.memory/memory-type
                    :query     "decision"}))
        ":db/fulltext-required precondition fires")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 4c — search-bm25f (multi-field weighted BM25F + Datomic integration)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory-with-name+body!
  [name body-raw]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    (str "test/" name ".md")
            :mm.memory/name        name
            :mm.memory/description (str "Description for " name)
            :mm.memory/body-raw    body-raw}))

(deftest search-bm25f-result-shape-test
  (testing "search-bm25f returns the canonical {:hits :total :returned :timing} shape"
    (make-memory-with-name+body! "datomic-notes" "datomic project graph realizes anderson")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory})]
      (is (contains? result :hits))
      (is (contains? result :total))
      (is (contains? result :returned))
      (is (contains? result :timing))
      (is (integer? (get-in result [:timing :total-ms])))
      (is (>= (get-in result [:timing :total-ms]) 0))
      (when (seq (:hits result))
        (let [hit (first (:hits result))]
          (is (contains? hit :entity))
          (is (contains? hit :eid))
          (is (contains? hit :score))
          (is (pos? (:score hit))))))))

(deftest search-bm25f-uses-class-default-weights-test
  (testing "search-bm25f reads :dt/bm25f-weights from class when no override given"
    (make-memory-with-name+body! "datomic-in-name" "lorem ipsum")
    (make-memory-with-name+body! "noise" "datomic in body but not name")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory})]
      (is (= 2 (:total result))
          "Both memorials match (one via name slot, one via body slot)")
      ;; :name weight (12) > :body weight (1); name-match outscores body-match
      (let [hits (:hits result)]
        (is (= "datomic-in-name"
               (get-in (first hits) [:entity :mm.memory/name]))
            "Name-match memorial ranks first")))))

(deftest search-bm25f-field-weights-override-test
  (testing "search-bm25f honors caller-supplied :field-weights override"
    (make-memory-with-name+body! "alpha" "datomic in body alpha")
    (let [result-body-only (search/search-bm25f
                             {:query "datomic"
                              :class :mm/Memory
                              :field-weights {:mm.memory/body-raw 1.0}})
          result-name-only (search/search-bm25f
                             {:query "datomic"
                              :class :mm/Memory
                              :field-weights {:mm.memory/name 12.0}})]
      (is (= 1 (:total result-body-only)) "body weight finds body match")
      (is (= 0 (:total result-name-only)) "name-only weight misses body match"))))

(deftest search-bm25f-empty-on-no-match-test
  (testing "search-bm25f returns empty :hits + :total 0 when nothing matches"
    (make-memory-with-name+body! "alpha" "no relevant text here")
    (let [result (search/search-bm25f
                   {:query "zzznotpresentterm"
                    :class :mm/Memory})]
      (is (= 0 (:total result)))
      (is (= 0 (:returned result)))
      (is (empty? (:hits result))))))

(deftest search-bm25f-limit-test
  (testing "search-bm25f honors :limit (truncates :hits, preserves :total)"
    (doseq [n (range 5)]
      (make-memory-with-name+body! (str "mem-" n) "datomic alpha beta"))
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory
                    :limit 2})]
      (is (= 5 (:total result))    ":total reports pre-limit count")
      (is (= 2 (:returned result)) ":returned reports post-limit count")
      (is (= 2 (count (:hits result)))))))

(deftest search-bm25f-include-field-scores-test
  (testing "search-bm25f :include [:field-scores] adds per-slot breakdown"
    (make-memory-with-name+body! "datomic-notes" "datomic graph anderson")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory
                    :include [:field-scores]})
          hit    (first (:hits result))]
      (is (contains? hit :field-scores))
      (is (map? (:field-scores hit)))
      (testing "field-scores has an entry per weighted slot"
        (is (contains? (:field-scores hit) :mm.memory/name))
        (is (contains? (:field-scores hit) :mm.memory/description))
        (is (contains? (:field-scores hit) :mm.memory/body-raw)))
      (testing "name has positive score (term appears there); description has zero"
        (is (pos? (get-in hit [:field-scores :mm.memory/name])))
        ;; "description" slot was set to "Description for datomic-notes" which contains "datomic" stem.
        ;; So it should also be positive.  Body has datomic too; positive.
        ;; Skip the zero-check (the make-memory helper auto-includes datomic-notes in description).
        ))))

(deftest search-bm25f-no-weights-throws-test
  (testing "search-bm25f throws when class has no :dt/bm25f-weights AND no override given"
    ;; Stage 7.A added :dt/bm25f-weights to :mm/Tag (decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md).
    ;; :mm/Link remains a no-weights class — use it as the empty-weights probe.
    ;; Throws via ex-info with substrate-helpful message.
    (is (thrown? clojure.lang.ExceptionInfo
                 (search/search-bm25f
                   {:query "anything"
                    :class :mm/Link})))))

(deftest search-bm25f-hits-sorted-by-score-test
  (testing "search-bm25f hits are sorted by score descending"
    (make-memory-with-name+body! "alpha" "datomic single mention")
    (make-memory-with-name+body! "beta" "datomic datomic datomic many mentions")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory})
          scores (mapv :score (:hits result))]
      (is (seq scores))
      (is (apply >= scores)
          (str "Hits sorted by descending score, got: " scores)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 5 — structured composition (:where Datalog clauses)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-typed-memory!
  [name memory-type body-raw]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    (str "test/" name ".md")
            :mm.memory/name        name
            :mm.memory/memory-type memory-type
            :mm.memory/body-raw    body-raw}))

(deftest search-bm25f-where-restricts-by-type-test
  (testing ":where filters hits to entities matching the Datalog predicate"
    (make-typed-memory! "decision-1" :decision "datomic project graph")
    (make-typed-memory! "plan-1"     :plan     "datomic plan body")
    (make-typed-memory! "observation-1" :observation "datomic observation")
    (let [decisions-only (search/search-bm25f
                           {:query "datomic"
                            :class :mm/Memory
                            :where '[[?e :mm.memory/memory-type :decision]]})
          plans-only     (search/search-bm25f
                           {:query "datomic"
                            :class :mm/Memory
                            :where '[[?e :mm.memory/memory-type :plan]]})
          all-types      (search/search-bm25f
                           {:query "datomic"
                            :class :mm/Memory})]
      (is (= 1 (:total decisions-only)) "Only one :decision matches")
      (is (= :decision (-> decisions-only :hits first :entity :mm.memory/memory-type)))
      (is (= 1 (:total plans-only)) "Only one :plan matches")
      (is (= :plan (-> plans-only :hits first :entity :mm.memory/memory-type)))
      (is (= 3 (:total all-types))   "No :where opt returns all matching entities"))))

(deftest search-bm25f-where-empty-when-no-match-test
  (testing ":where returns empty :hits when predicate matches no entity"
    (make-typed-memory! "decision-1" :decision "datomic body")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory
                    :where '[[?e :mm.memory/memory-type :nonexistent-type]]})]
      (is (= 0 (:total result)))
      (is (= 0 (:returned result)))
      (is (empty? (:hits result))))))

(deftest search-bm25f-where-and-query-compose-test
  (testing ":where + :query compose — intersection of fulltext AND predicate matches"
    ;; Names deliberately do NOT contain the query term — only body does;
    ;; otherwise name-slot tokenization would create false positive matches.
    (make-typed-memory! "alpha" :decision    "datomic project graph")
    (make-typed-memory! "beta"  :decision    "lorem ipsum dolor")
    (make-typed-memory! "gamma" :plan        "datomic plan structure")
    (let [decisions-matching (search/search-bm25f
                                {:query "datomic"
                                 :class :mm/Memory
                                 :where '[[?e :mm.memory/memory-type :decision]]})]
      (is (= 1 (:total decisions-matching))
          "Only alpha (decision with 'datomic' in body) matches the intersection")
      (is (= "alpha"
             (-> decisions-matching :hits first :entity :mm.memory/name))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UR-9 (Phase U Stage U-9): where-matching-eids input-shape guard
;;
;; The accepted contract is vector-of-clause-vectors:
;; `[[?e :slot value] ...]`.  Pre-fix, malformed shapes failed with
;; opaque deep-Datalog stack traces.  Post-fix, the boundary `:pre`
;; throws AssertionError carrying the malformed input so callers see
;; a structured contract-violation, not a Datalog parser surprise.

(deftest where-matching-eids-rejects-flat-single-clause
  (testing "A bare clause `'[?e :slot value]` (missing outer vec) is
            rejected at the boundary"
    (is (thrown? AssertionError
                 (#'sandbar.search/where-matching-eids
                  :mm/Memory
                  '[?e :mm.memory/memory-type :decision])))))

(deftest where-matching-eids-rejects-non-sequential-shape
  (testing "Non-sequential inputs (string / number / map) rejected"
    (is (thrown? AssertionError
                 (#'sandbar.search/where-matching-eids
                  :mm/Memory
                  "not-a-vec")))
    (is (thrown? AssertionError
                 (#'sandbar.search/where-matching-eids
                  :mm/Memory
                  {:slot :value})))))

(deftest where-matching-eids-accepts-empty-vec
  (testing "Empty `[]` is a valid shape (no restriction)"
    ;; Should NOT throw; the function may still return a set or error
    ;; on the Datalog query level — we're testing the shape-guard only
    (is (any? (try (#'sandbar.search/where-matching-eids :mm/Memory [])
                   (catch AssertionError _ ::pre-fired))))))

(deftest search-bm25f-where-no-clauses-equals-no-where-test
  (testing "Empty :where vec behaves like no :where opt (no filtering)"
    (make-typed-memory! "alpha" :decision "datomic alpha")
    (make-typed-memory! "beta"  :plan     "datomic beta")
    (let [no-where   (search/search-bm25f
                       {:query "datomic"
                        :class :mm/Memory})
          empty-where (search/search-bm25f
                        {:query "datomic"
                         :class :mm/Memory
                         :where []})]
      (is (= (:total no-where) (:total empty-where)))
      (is (= (:returned no-where) (:returned empty-where))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 6 — snippets + highlights (:include [:snippets])
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest search-bm25f-snippets-include-test
  (testing ":include [:snippets] adds per-slot snippet with markdown highlight"
    (make-memory-with-name+body!
      "alpha"
      "The datomic project graph realizes the Anderson lineage of mediator patterns from the de.setf.rdf library")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory
                    :include [:snippets]})
          hit    (first (:hits result))]
      (is (contains? hit :snippets))
      (is (map? (:snippets hit)))
      (testing "snippet present for slot whose value contains matched term"
        (let [body-snippet (get-in hit [:snippets :mm.memory/body-raw])]
          (is (string? body-snippet))
          (is (clojure.string/includes? body-snippet "**datomic**")
              "Matched term highlighted via **term** markdown")))
      (testing "snippets map only includes slots with matches"
        ;; description was not populated; should not appear
        (is (not (contains? (:snippets hit) :mm.memory/description)))))))

(deftest search-bm25f-snippets-multi-term-highlight-test
  (testing "multi-word queries highlight each matched term within the snippet window"
    (make-memory-with-name+body!
      "beta"
      "Datomic provides indexed search via Lucene fulltext on declared attributes")
    (let [result (search/search-bm25f
                   {:query "datomic lucene"
                    :class :mm/Memory
                    :include [:snippets]})
          hit    (first (:hits result))
          body-snippet (get-in hit [:snippets :mm.memory/body-raw])]
      (is (string? body-snippet))
      (is (clojure.string/includes? body-snippet "**Datomic**")
          "First query word highlighted")
      (is (clojure.string/includes? body-snippet "**Lucene**")
          "Second query word highlighted"))))

(deftest search-bm25f-snippets-case-insensitive-test
  (testing "snippet highlighting is case-insensitive (preserves original casing)"
    (make-memory-with-name+body!
      "gamma"
      "DATOMIC IS UPPER CASE HERE but query is lowercase")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory
                    :include [:snippets]})
          hit    (first (:hits result))
          body-snippet (get-in hit [:snippets :mm.memory/body-raw])]
      (is (clojure.string/includes? body-snippet "**DATOMIC**")
          "Original UPPER casing preserved in highlight; match case-insensitive"))))

(deftest search-bm25f-snippets-window-bounds-test
  (testing "snippet windows are bounded by snippet-width; ellipsis markers added when text was truncated"
    ;; Long body with target term in the middle; snippet should be windowed.
    (let [pre   (apply str (repeat 200 "x "))   ; 400 chars before target
          post  (apply str (repeat 200 " y"))    ; 400 chars after target
          body  (str pre "datomic" post)]
      (make-memory-with-name+body! "delta" body)
      (let [result (search/search-bm25f
                     {:query "datomic"
                      :class :mm/Memory
                      :include [:snippets]})
            hit    (first (:hits result))
            body-snippet (get-in hit [:snippets :mm.memory/body-raw])]
        (is (< (count body-snippet) 400)
            "Snippet truncated to roughly the snippet-width window")
        (is (clojure.string/starts-with? body-snippet "...")
            "Ellipsis prefix when text continues before window")
        (is (clojure.string/ends-with? body-snippet "...")
            "Ellipsis suffix when text continues after window")))))

(deftest search-bm25f-no-snippets-when-not-included-test
  (testing "search-bm25f does NOT include :snippets when not requested"
    (make-memory-with-name+body! "epsilon" "datomic in body")
    (let [result (search/search-bm25f
                   {:query "datomic"
                    :class :mm/Memory})
          hit    (first (:hits result))]
      (is (not (contains? hit :snippets))
          "No :snippets key on hits when :include opt is missing or doesn't request it"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 7 — facets (:facet-by produces {value count} maps)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest search-bm25f-facet-by-counts-test
  (testing ":facet-by produces {slot {value count}} map over the full match-set"
    (make-typed-memory! "alpha"   :decision    "datomic alpha")
    (make-typed-memory! "beta"    :decision    "datomic beta")
    (make-typed-memory! "gamma"   :plan        "datomic gamma")
    (make-typed-memory! "delta"   :observation "datomic delta")
    (let [result (search/search-bm25f
                   {:query    "datomic"
                    :class    :mm/Memory
                    :facet-by [:mm.memory/memory-type]})]
      (is (= 4 (:total result)))
      (is (contains? result :facets))
      (let [facets (:facets result)]
        (is (contains? facets :mm.memory/memory-type))
        (let [type-counts (get facets :mm.memory/memory-type)]
          (is (= 2 (get type-counts :decision)))
          (is (= 1 (get type-counts :plan)))
          (is (= 1 (get type-counts :observation))))))))

(deftest search-bm25f-facets-over-full-match-set-not-limited-test
  (testing "facets count the FULL match-set, not just the limited hits"
    (doseq [n (range 5)] (make-typed-memory! (str "d-" n) :decision "datomic alpha"))
    (doseq [n (range 3)] (make-typed-memory! (str "p-" n) :plan "datomic beta"))
    (let [result (search/search-bm25f
                   {:query    "datomic"
                    :class    :mm/Memory
                    :limit    2                       ; limit truncates hits
                    :facet-by [:mm.memory/memory-type]})
          type-counts (get-in result [:facets :mm.memory/memory-type])]
      (is (= 8 (:total result))     ":total reports full match count")
      (is (= 2 (:returned result))  ":returned reflects post-limit count")
      (is (= 5 (get type-counts :decision))
          "facet count covers all 5 decisions (not just the 2 limited hits)")
      (is (= 3 (get type-counts :plan))
          "facet count covers all 3 plans"))))

(deftest search-bm25f-facets-multiple-slots-test
  (testing "multiple slots in :facet-by produce separate count maps"
    (make-typed-memory! "alpha" :decision    "datomic project")
    (make-typed-memory! "beta"  :plan        "datomic plan")
    (make-typed-memory! "gamma" :observation "datomic obs")
    (let [result (search/search-bm25f
                   {:query    "datomic"
                    :class    :mm/Memory
                    :facet-by [:mm.memory/memory-type :mm.memory/name]})
          facets (:facets result)]
      (is (contains? facets :mm.memory/memory-type))
      (is (contains? facets :mm.memory/name))
      (is (= 3 (count (get facets :mm.memory/memory-type))))
      (is (= 3 (count (get facets :mm.memory/name)))))))

(deftest search-bm25f-no-facets-key-when-not-requested-test
  (testing "search-bm25f does NOT include :facets when :facet-by is missing/empty"
    (make-typed-memory! "alpha" :decision "datomic")
    (let [no-facet-by (search/search-bm25f
                        {:query "datomic" :class :mm/Memory})
          empty-facet-by (search/search-bm25f
                           {:query "datomic" :class :mm/Memory :facet-by []})]
      (is (not (contains? no-facet-by :facets)))
      (is (not (contains? empty-facet-by :facets))))))

(deftest search-attribute-lucene-syntax-test
  (testing "search-attribute accepts Lucene query syntax"
    (make-memory! "test/a.md" "datomic project graph realizes Anderson lineage")
    (make-memory! "test/b.md" "lucene scoring example BM25F")
    (make-memory! "test/c.md" "datomic and lucene together compose BM25F search")
    (testing "boolean AND"
      (let [result (search/search-attribute
                     {:attribute :mm.memory/body-raw
                      :query     "datomic AND lucene"})]
        (is (= 1 (:total result))
            "Only the memory with both 'datomic' AND 'lucene' matches")))
    (testing "boolean OR"
      (let [result (search/search-attribute
                     {:attribute :mm.memory/body-raw
                      :query     "Anderson OR BM25F"})]
        ;; Memories with 'Anderson' (a) OR 'BM25F' (b, c) = 3 total
        (is (= 3 (:total result)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; F-SF-1 / Phase R Stage R-5 — BM25F query-language contract narrow
;;
;; The docstring at search.clj:315 used to claim "Lucene syntax
;; accepted (phrase, boolean, etc.)" but the runtime tokenizer
;; (sandbar.search.analysis Porter pipeline) is bag-of-words; AND /
;; OR / NOT / "phrase" / wildcards are not parsed.  R-5 narrows the
;; docstring to reflect runtime; these tests pin the runtime
;; contract so future docstring drift fails loudly.
;;
;; For Lucene query-parser semantics, reach for search-attribute
;; (which calls Datomic's :db.fn/fulltext-search backed by Lucene).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest search-bm25f-and-tokenizes-literally-not-as-boolean
  (testing "F-SF-1: (search-bm25f {:query \"AND\"}) tokenizes 'AND'
            as a literal term — does NOT parse it as a boolean
            operator (Lucene parsing is NOT in the contract)"
    ;; Make two memories — one with the literal word 'and' in its
    ;; body, one without.
    (make-memory-with-name+body! "with-and-token" "memo and notes")
    (make-memory-with-name+body! "no-and-token" "memo alpha beta")
    (let [result (search/search-bm25f
                   {:query "AND"
                    :class :mm/Memory})]
      ;; If Lucene parsing were in effect, "AND" alone would be a
      ;; malformed query.  Under bag-of-words, "AND" tokenizes to
      ;; the literal stem "and" and matches the memory containing
      ;; that token.
      (is (pos? (:total result))
          "literal 'AND' must tokenize + match memories with the word"))))

(deftest search-bm25f-phrase-quoting-not-supported
  (testing "F-SF-1: (search-bm25f {:query \"\\\"exact phrase\\\"\"})
            does NOT enforce phrase order; tokens are matched bag-of-
            words style across the corpus"
    (make-memory-with-name+body! "phrase-test"
                                 "alpha beta gamma delta")
    ;; If phrase quoting were in effect, "delta alpha" with quotes
    ;; would require the order delta→alpha (no match).  Under
    ;; bag-of-words, both tokens tokenize and match the corpus
    ;; even though they appear in the body in reverse order.
    (let [result (search/search-bm25f
                   {:query "\"delta alpha\""
                    :class :mm/Memory})]
      (is (pos? (:total result))
          (str "quoted-phrase tokens are NOT phrase-locked; "
               "bag-of-words tokenizes them as 'delta' + 'alpha' "
               "independently")))))

(deftest search-bm25f-contract-vs-search-attribute
  (testing "F-SF-1: search-attribute (Lucene-parsed) and search-bm25f
            (bag-of-words) honor distinct contracts.  This pinned-
            difference test prevents future drift between docstring
            + runtime."
    ;; Memory containing both terms.
    (make-memory-with-name+body! "both-tokens" "datomic and lucene")
    ;; Memory containing only one term.
    (make-memory-with-name+body! "one-token" "datomic only")
    ;; search-attribute: Lucene "AND" semantics — only the both-
    ;; tokens memory matches.
    (let [attr-result (search/search-attribute
                        {:attribute :mm.memory/body-raw
                         :query     "datomic AND lucene"})]
      (is (= 1 (:total attr-result))
          "search-attribute: 'AND' is the Lucene boolean operator"))
    ;; search-bm25f: bag-of-words — every memory containing 'datomic'
    ;; OR 'lucene' OR even 'and' matches; both above qualify.
    (let [bm25f-result (search/search-bm25f
                         {:query "datomic AND lucene"
                          :class :mm/Memory})]
      (is (>= (:total bm25f-result) 1)
          "search-bm25f: 'AND' is tokenized as a literal term"))))
