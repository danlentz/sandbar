(ns sandbar.search.frontmatter-projection-test
  "search-bm25f `:projection :frontmatter` admission — the recall-hook lean
  path (2026-07-21).

  `sandbar.api.projection/projection-fn-for` has dispatched `:frontmatter`
  (all scalar+ref slots EXCEPT the bulky `:mm.memory/body-raw`) since the
  mode was introduced, but `search-bm25f-single`'s precondition predated it
  and admitted only `#{:full :metadata-only}` — so the ONE consumer that
  wants exactly the frontmatter shape (the PreToolUse
  `inject_feedback_recall` hook, which reads ONLY name / description /
  rel-path / memory-type per hit and previously fetched+discarded full
  bodies) could not request it.  These tests pin the widened guard:

  - `:frontmatter` is accepted and hits carry the four hook-consumed slots
    but NOT `:mm.memory/body-raw` (the payload the hook trims);
  - `:full` still carries the body (guards the contrast — proves the
    frontmatter assertion is not vacuous);
  - an unknown mode still fails the precondition (guard stays loud);
  - the D7 multi-class vec forwards `:frontmatter` per-class."
  (:require [clojure.test :refer :all]
            [sandbar.search :as search]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "frontmatter-projection-test"})
  ;; Per search-test: per-class BM25F analyzed-corpus cache keys by
  ;; (class, basis-t); clear between fixtures so a fresh DB can't surface
  ;; stale analyzed entries.
  (fn [t] (search/clear-bm25f-cache!) (t)))

(defn- seed-guidance-memory!
  "Insert a guidance-shaped :mm/Memory carrying every slot the recall hook
  consumes (name / description / rel-path / memory-type) PLUS a body — so
  the projection assertions can distinguish frontmatter from full."
  []
  (dt/make :mm/Memory
           {:mm.memory/name        "use-mem-not-bb"
            :mm.memory/description "mem is the canonical CLI invocation, not bb"
            :mm.memory/rel-path    "memory/interaction/use_mem_not_bb_for_canonical_cli.md"
            :mm.memory/memory-type :feedback
            :mm.memory/body-raw    "The canonical CLI is mem; bb test is the recurring violation."}))

(deftest frontmatter-projection-accepted-and-lean
  (testing ":projection :frontmatter is admitted and trims the body"
    (seed-guidance-memory!)
    (let [result (search/search-bm25f {:query      "canonical cli mem"
                                       :class      :mm/Memory
                                       :limit      10
                                       :projection :frontmatter})
          hit    (first (:hits result))
          entity (:entity hit)]
      (is (seq (:hits result)) "seeded memory should match the query")
      (testing "the four hook-consumed slots survive"
        (is (= "use-mem-not-bb" (:mm.memory/name entity)))
        (is (= "mem is the canonical CLI invocation, not bb"
               (:mm.memory/description entity)))
        (is (= "memory/interaction/use_mem_not_bb_for_canonical_cli.md"
               (:mm.memory/rel-path entity)))
        (is (= :feedback (:mm.memory/memory-type entity))))
      (testing "the bulky body slot is trimmed"
        (is (not (contains? entity :mm.memory/body-raw))))
      (testing "score + eid hit shape unchanged"
        (is (double? (:score hit)))
        (is (some? (:eid hit)))))))

(deftest full-projection-still-carries-body
  (testing "contrast guard: :full keeps :mm.memory/body-raw"
    (seed-guidance-memory!)
    (let [result (search/search-bm25f {:query      "canonical cli mem"
                                       :class      :mm/Memory
                                       :limit      10
                                       :projection :full})
          entity (:entity (first (:hits result)))]
      (is (seq (:hits result)))
      (is (contains? entity :mm.memory/body-raw)))))

(deftest unknown-projection-still-rejected
  (testing "the precondition stays loud for unknown modes"
    (seed-guidance-memory!)
    (is (thrown? AssertionError
                 (search/search-bm25f {:query      "canonical cli mem"
                                       :class      :mm/Memory
                                       :projection :bogus})))))

(deftest multiclass-vec-forwards-frontmatter
  (testing "D7 multi-class :class vec forwards :projection :frontmatter per-class"
    (seed-guidance-memory!)
    (dt/make :mm/Tag {:mm.tag/value "canonical"} {:validate? false})
    (let [result (search/search-bm25f {:query      "canonical cli mem"
                                       :class      [:mm/Memory :mm/Tag]
                                       :limit      10
                                       :projection :frontmatter})]
      (is (seq (:hits result)))
      (is (not-any? #(contains? (:entity %) :mm.memory/body-raw)
                    (:hits result))
          "no merged hit may carry the body slot under :frontmatter"))))
