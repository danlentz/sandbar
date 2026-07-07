(ns sandbar.aggregate-test
  "Tests for sandbar.aggregate — Phase G Stage 13 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.aggregate :as agg]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "aggregate-test"}))

(defn- make-memory-typed!
  [name memory-type]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    (str "test/" name ".md")
            :mm.memory/name        name
            :mm.memory/memory-type memory-type
            :mm.memory/body-raw    "body content here"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; count-by
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest count-by-no-where-test
  (testing "count-by returns {:count int} for class instances"
    ;; Baseline: schema-load pre-seeds 5 :mm/Memory DESCENDANTS (each reaches
    ;; :mm/Memory transitively via recursive :dt/subclass-of traversal — count-by
    ;; -> dt/count-of -> all-instances-of uses the instance-of Datalog rule) —
    ;;   2 × :mm/Shape from Phase D Temporal Tier-2 XOR Shape declarations
    ;;       (:memory.shapes/interval-begins-at-xor + …interval-ends-at-xor in
    ;;        schema/mm-temporal.edn)
    ;;   1 × :mm/Workflow from ι.2 session-workflow definition
    ;;       (:workflow/session in schema/workflow-session.edn 2026-05-25)
    ;;   1 × :mm/Project sentinel :project/UNASSIGNED (S6 keystone mint —
    ;;       :mm/Project :dt/subclass-of :mm/Artifact -> :mm/Memory; seeded in
    ;;       schema/mm-artifact.edn batch (v)).
    ;;   1 × :mm/Context sentinel :context/UNASSIGNED (S6 keystone mint, batch
    ;;       (iv)).  :mm/Context has DUAL parentage (:dt/subclass-of is card-many):
    ;;       :dt/Resource (mm.edn:1955) AND :mm/Meta (mm-meta.edn:468, the
    ;;       2026-05-21 amendment), and :mm/Meta :dt/subclass-of :mm/Memory
    ;;       (mm-meta.edn:26).  DESIGN-ONTOLOGY §1.2 certifies :mm/Context
    ;;       ancestors [:dt/Resource :mm/Meta :mm/Memory].  So :context/UNASSIGNED
    ;;       IS a :mm/Memory descendant via the :mm/Meta leg and DOES count.
    ;;   4 × :mm/Shape + 2 × :mm/Fn from the S7 BU-6 safety-shape batch
    ;;       (schema/mm-artifact.edn; :mm/Shape and :mm/Fn are :mm/Memory
    ;;       descendants via the :mm/Meta leg — the 4 :mm.shape/PatternConstraint
    ;;       sub-entities are NOT and do not count).
    ;; Baseline computed dynamically (not a hard-coded literal) so future seed
    ;; changes don't re-introduce an off-by-one.  Test creates 3 additional
    ;; :mm/Memory entities; total = baseline (11) + created-3.
    (let [baseline (:count (agg/count-by {:class :mm/Memory}))]
      (make-memory-typed! "alpha" :decision)
      (make-memory-typed! "beta"  :plan)
      (make-memory-typed! "gamma" :decision)
      (let [result (agg/count-by {:class :mm/Memory})]
        (is (= {:count (+ baseline 3)} result))
        (is (= {:count 14} result)
            "baseline 11 pre-seeded :mm/Memory descendants + 3 created")))))

(deftest count-by-with-where-test
  (testing "count-by :where restricts by predicate"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (make-memory-typed! "gamma" :decision)
    (let [decisions (agg/count-by
                      {:class :mm/Memory
                       :where '[[?e :mm.memory/memory-type :decision]]})
          plans     (agg/count-by
                      {:class :mm/Memory
                       :where '[[?e :mm.memory/memory-type :plan]]})]
      (is (= {:count 2} decisions))
      (is (= {:count 1} plans)))))

(deftest count-by-no-match-test
  (testing "count-by returns 0 when no entity matches"
    (make-memory-typed! "alpha" :decision)
    (let [result (agg/count-by
                   {:class :mm/Memory
                    :where '[[?e :mm.memory/memory-type :nonexistent-type]]})]
      (is (= {:count 0} result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; group-by
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest group-by-counts-per-value-test
  (testing "group-by produces {value count} map + total"
    ;; Baseline: schema-load adds 1 :mm/Workflow with :mm.memory/memory-type
    ;; :workflow (the :workflow/session definition per ι.2; the 2 baseline
    ;; :mm/Shape entities do NOT have :mm.memory/memory-type set so don't
    ;; contribute to grouped buckets).  Test creates 4 typed entities; total =
    ;; baseline-1 + created-4 = 5.
    (make-memory-typed! "alpha"  :decision)
    (make-memory-typed! "beta"   :decision)
    (make-memory-typed! "gamma"  :plan)
    (make-memory-typed! "delta"  :observation)
    (let [result (agg/group-by
                   {:class    :mm/Memory
                    :group-by :mm.memory/memory-type})
          {:keys [groups total]} result]
      (is (= 5 total))
      (is (= 2 (get groups :decision)))
      (is (= 1 (get groups :plan)))
      (is (= 1 (get groups :observation)))
      (is (= 1 (get groups :workflow))
          ":workflow/session contributes to :workflow bucket (ι.2 baseline)"))))

(deftest group-by-with-where-test
  (testing "group-by :where restricts the candidate set before grouping"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :decision)
    (make-memory-typed! "gamma" :plan)
    (let [result (agg/group-by
                   {:class    :mm/Memory
                    :group-by :mm.memory/memory-type
                    :where    '[[?e :mm.memory/memory-type :decision]]})
          {:keys [groups total]} result]
      (is (= 2 total))
      (is (= 2 (get groups :decision)))
      (is (nil? (get groups :plan))))))

(deftest group-by-skips-unset-slot-test
  (testing "group-by skips entities where the group slot is unset"
    ;; Baseline: :workflow/session (ι.2) has :mm.memory/memory-type :workflow
    ;; and contributes to total.  The 2 :mm/Shape baseline entities don't have
    ;; :mm.memory/memory-type so they're skipped (the test's invariant).
    ;; Insert one entity WITHOUT :mm.memory/memory-type slot
    (dt/make :mm/Memory
             {:mm.memory/rel-path "test/no-type.md"
              :mm.memory/name     "no-type"
              :mm.memory/body-raw "irrelevant"})
    (make-memory-typed! "with-type" :decision)
    (let [result (agg/group-by
                   {:class    :mm/Memory
                    :group-by :mm.memory/memory-type})]
      (is (= 2 (:total result))
          "Entities WITH :mm.memory/memory-type counted (1 created + 1 :workflow/session baseline)")
      (is (= 1 (get-in result [:groups :decision])))
      (is (= 1 (get-in result [:groups :workflow]))
          ":workflow/session baseline in :workflow bucket"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank-by — :degree
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rank-by-degree-shape-test
  (testing "rank-by :degree returns {:hits :total :returned} with rank-score"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (let [result (agg/rank-by {:class :mm/Memory :rank-by :degree})]
      (is (contains? result :hits))
      (is (contains? result :total))
      (is (contains? result :returned))
      (is (vector? (:hits result)))
      (when (seq (:hits result))
        (let [hit (first (:hits result))]
          (is (contains? hit :entity))
          (is (contains? hit :rank-score))
          (is (number? (:rank-score hit))))))))

(deftest rank-by-degree-orders-descending-test
  (testing "rank-by :degree orders by descending rank-score"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (make-memory-typed! "gamma" :observation)
    (let [result (agg/rank-by {:class :mm/Memory :rank-by :degree})
          scores (mapv :rank-score (:hits result))]
      (when (> (count scores) 1)
        (is (apply >= scores)
            (str "Hits sorted descending by rank-score, got: " scores))))))

(deftest rank-by-degree-limit-test
  (testing "rank-by :degree honors :limit"
    ;; Baseline: schema-load pre-seeds 5 :mm/Memory DESCENDANTS (2 :mm/Shape +
    ;; 1 :mm/Workflow :workflow/session per ι.2 + 1 :mm/Project sentinel
    ;; :project/UNASSIGNED + 1 :mm/Context sentinel :context/UNASSIGNED, both
    ;; per the S6 keystone mint).  The :context/UNASSIGNED :mm/Context sentinel
    ;; IS a :mm/Memory descendant: :mm/Context has DUAL parentage (:dt/Resource
    ;; at mm.edn:1955 AND :mm/Meta at mm-meta.edn:468), and :mm/Meta
    ;; :dt/subclass-of :mm/Memory (mm-meta.edn:26) — DESIGN-ONTOLOGY §1.2
    ;; certifies :mm/Context ancestors [:dt/Resource :mm/Meta :mm/Memory].
    ;; :total = (count all-instances-of :mm/Memory), which follows the recursive
    ;; instance-of rule, so it counts BOTH sentinels — see count-by-no-where-test.
    ;; Baseline computed dynamically (not hard-coded) to prevent off-by-one
    ;; recurrence.  Test creates 5 additional; total = baseline (11) + created-5
    ;; (baseline includes the S7 BU-6 4 :mm/Shape + 2 :mm/Fn seeds — see
    ;; count-by-no-where-test's census note).
    (let [baseline (:count (agg/count-by {:class :mm/Memory}))]
      (doseq [n (range 5)] (make-memory-typed! (str "mem-" n) :decision))
      (let [result (agg/rank-by {:class :mm/Memory :rank-by :degree :limit 2})]
        (is (= (+ baseline 5) (:total result)))
        (is (= 16 (:total result))
            "baseline 11 pre-seeded :mm/Memory descendants + 5 created")
        (is (= 2 (:returned result)))
        (is (= 2 (count (:hits result))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank-by — :recency / :freshness with caller-supplied temporal-slot
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory-with-timestamp!
  [name last-touched]
  (dt/make :mm/Memory
           {:mm.memory/rel-path     (str "test/" name ".md")
            :mm.memory/name         name
            :mm.memory/memory-type  :decision
            :mm.memory/body-raw     "body"
            :mm.memory/last-touched last-touched}))

(deftest rank-by-recency-orders-most-recent-first-test
  (testing "rank-by :recency orders by descending timestamp"
    (make-memory-with-timestamp! "old"     #inst "2025-01-01")
    (make-memory-with-timestamp! "newer"   #inst "2026-01-01")
    (make-memory-with-timestamp! "newest"  #inst "2026-05-01")
    (let [result (agg/rank-by {:class         :mm/Memory
                               :rank-by       :recency
                               :temporal-slot :mm.memory/last-touched})
          names  (mapv #(get-in % [:entity :mm.memory/name]) (:hits result))]
      (is (= ["newest" "newer" "old"] names)
          "Most-recent timestamp first"))))

(deftest rank-by-freshness-orders-oldest-first-test
  (testing "rank-by :freshness orders by ascending timestamp"
    (make-memory-with-timestamp! "old"     #inst "2025-01-01")
    (make-memory-with-timestamp! "newer"   #inst "2026-01-01")
    (make-memory-with-timestamp! "newest"  #inst "2026-05-01")
    (let [result (agg/rank-by {:class         :mm/Memory
                               :rank-by       :freshness
                               :temporal-slot :mm.memory/last-touched})
          names  (mapv #(get-in % [:entity :mm.memory/name]) (:hits result))]
      (is (= ["old" "newer" "newest"] names)
          "Oldest timestamp first (stalest candidates surface for review)"))))

(deftest rank-by-temporal-missing-slot-throws-test
  (testing "rank-by :recency without :temporal-slot opt throws (precondition)"
    (is (thrown? AssertionError
                 (agg/rank-by {:class :mm/Memory :rank-by :recency})))))

(deftest rank-by-invalid-axis-throws-test
  (testing "rank-by with non-recognized axis throws (precondition)"
    (is (thrown? AssertionError
                 (agg/rank-by {:class :mm/Memory :rank-by :bogus-axis})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank-by — :memorial-policy filter (lattice-driven curated-vs-operational cut)
;; Per decisions/filter_curated_memorials_by_lattice_memorial_policy_not_bespoke_type_check_2026_05_29
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rank-by-memorial-policy-filters-to-first-class-test
  (testing ":memorial-policy :first-class keeps :first-class memorials, drops :db-only"
    ;; :mm/Memory declares :first-class (inherited by the plain memory below);
    ;; :mm/Run declares :db-only.  The :mm/Run is created MORE RECENT than the
    ;; curated memorial — proving the filter is by policy, not just recency.
    (make-memory-with-timestamp! "curated-decision" #inst "2026-05-03")
    (dt/make :mm/Run {:mm.memory/rel-path     "test/run-telemetry.md"
                      :mm.memory/name         "run-telemetry"
                      :mm.memory/last-touched #inst "2026-05-04"}
             {:validate? false})
    ;; DIAGNOSTIC — confirm the lattice resolves policies as expected in the test DB.
    (is (= :first-class (dt/effective-memorial-policy-of :mm/Memory)) "DIAG mm/Memory policy")
    (is (= :db-only (dt/effective-memorial-policy-of :mm/Run)) "DIAG mm/Run policy")
    (let [result (agg/rank-by {:class           :mm/Memory
                               :rank-by         :recency
                               :temporal-slot   :mm.memory/last-touched
                               :memorial-policy :first-class})
          names  (set (keep #(get-in % [:entity :mm.memory/name]) (:hits result)))]
      (is (contains? names "curated-decision")
          ":first-class memorial is present")
      (is (not (contains? names "run-telemetry"))
          ":db-only :mm/Run excluded despite being MORE recent"))))

(deftest rank-by-memorial-policy-nil-is-unfiltered-test
  (testing "rank-by WITHOUT :memorial-policy returns all policies (back-compat)"
    (make-memory-with-timestamp! "curated" #inst "2026-05-03")
    (dt/make :mm/Run {:mm.memory/rel-path     "test/run-2.md"
                      :mm.memory/name         "run-2"
                      :mm.memory/last-touched #inst "2026-05-04"}
             {:validate? false})
    (let [result (agg/rank-by {:class         :mm/Memory
                               :rank-by       :recency
                               :temporal-slot :mm.memory/last-touched})
          names  (set (keep #(get-in % [:entity :mm.memory/name]) (:hits result)))]
      (is (contains? names "curated"))
      (is (contains? names "run-2")
          "unfiltered recency ranking still includes :db-only entities"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tag-histogram — S11/Rec-9 identless-tag regression guard
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- tx!
  "Transact `specs` directly (bypassing dt/make validation, mirroring the corpus's
   ref-slot tag upserts) and return the post-transaction db."
  [specs]
  @(d/transact (db/conn) specs)
  (db/db))

(deftest tag-histogram-enumerates-identless-tags-test
  ;; S11/Rec-9 regression guard.  Corpus :mm/Tag entities are IDENTLESS by design
  ;; — created via ref-typed-slot upserts carrying only :mm.tag/value (no
  ;; :db/ident).  The prior impl enumerated tags with dt/all-named-instances-of
  ;; (a deprecated alias for named-idents-of, whose Datalog requires
  ;; [?e :db/ident ?ident]), so it matched ZERO real tags and returned
  ;; {:histogram [] :total 0} across the whole corpus.  These tests fail on the
  ;; old impl and pass on the all-instances-of fix.
  (testing "identless tags are enumerated (not collapsed to 0) with distinct-source counts"
    (tx! [{:dt/type :mm/Memory :db/ident :test/hist-m1
           :mm.memory/name "hist-m1" :mm.memory/memory-type :decision
           :mm.memory/tags [{:dt/type :mm/Tag :mm.tag/value "alpha"}
                            {:dt/type :mm/Tag :mm.tag/value "beta"}]}
          {:dt/type :mm/Memory :db/ident :test/hist-m2
           :mm.memory/name "hist-m2" :mm.memory/memory-type :decision
           :mm.memory/tags [{:dt/type :mm/Tag :mm.tag/value "alpha"}]}])
    (let [{:keys [histogram total]} (agg/tag-histogram {})
          created  (filter #(#{"alpha" "beta"} (:value %)) histogram)
          by-value (into {} (map (juxt :value :count) created))]
      (is (pos? total)
          "histogram must enumerate identless tags, not collapse to {:histogram [] :total 0}")
      (is (= 2 (count created)) "both created tags appear as bins")
      (is (= 2 (get by-value "alpha")) "alpha is referenced by 2 distinct memories")
      (is (= 1 (get by-value "beta"))  "beta is referenced by 1 memory")
      (is (every? #(integer? (:tag %)) created)
          "identless tags surface their numeric :db/id as the :tag identifier")))

  (testing "a tag cited by ONE entity via two ref slots counts as one entity (distinct-source, not edge-count)"
    (tx! [{:dt/type :mm/Memory :db/ident :test/hist-m3
           :mm.memory/name "hist-m3" :mm.memory/memory-type :decision
           :mm.memory/tags   [{:dt/type :mm/Tag :mm.tag/value "gamma"}]
           :mm.memory/themes [{:dt/type :mm/Tag :mm.tag/value "gamma"}]}])
    (let [{:keys [histogram]} (agg/tag-histogram {})
          by-value (into {} (map (juxt :value :count) histogram))]
      (is (= 1 (get by-value "gamma"))
          "gamma cited via BOTH :mm.memory/tags and :mm.memory/themes by one memory = 1 distinct entity")))

  (testing ":limit caps returned bins without changing :total"
    (let [{:keys [histogram total]} (agg/tag-histogram {:limit 1})]
      (is (= 1 (count histogram)) ":limit 1 returns a single bin")
      (is (>= total 3) ":total reports the full tag population regardless of :limit"))))
