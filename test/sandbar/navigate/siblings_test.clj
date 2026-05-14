(ns sandbar.navigate.siblings-test
  "Tests for sandbar.navigate.siblings — Stage 22 of comprehensive
  memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.

  DB-backed tests use mm/Memory entities with :mm.memory/rel-path
  to exercise the filesystem-style sibling semantics."
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.navigate.siblings :as siblings]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "navigate-siblings-test"}))

(defn- mk-memory!
  "Create a mm/Memory with the given rel-path; minimal slot set."
  [rel-path]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    rel-path
            :mm.memory/name        (str "memory-" rel-path)
            :mm.memory/memory-type :decision
            :mm.memory/body-raw    "content"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; dt/siblings-of substrate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest siblings-of-finds-same-dir-peers
  (testing "siblings under decisions/ find each other; not children/cousins"
    (let [m1 (mk-memory! "decisions/foo.md")
          m2 (mk-memory! "decisions/bar.md")
          m3 (mk-memory! "decisions/sub/nested.md")
          m4 (mk-memory! "patterns/other.md")
          rel-paths (set (mapv :mm.memory/rel-path
                               (dt/siblings-of (:db/id m1)
                                                :mm.memory/rel-path)))]
      (is (contains? rel-paths "decisions/bar.md")
          "same-dir peer found")
      (is (not (contains? rel-paths "decisions/sub/nested.md"))
          "deeper-nested NOT a sibling")
      (is (not (contains? rel-paths "patterns/other.md"))
          "different-dir NOT a sibling")
      (is (not (contains? rel-paths "decisions/foo.md"))
          "self NOT a sibling"))))

(deftest siblings-of-empty-for-no-peers
  (testing "entity with no same-dir peers returns empty vec"
    (let [m1 (mk-memory! "lonely/only-one.md")
          siblings (dt/siblings-of (:db/id m1) :mm.memory/rel-path)]
      (is (= [] siblings)))))

(deftest siblings-of-root-no-dir
  (testing "root-level entity (no '/' in path) finds other root entities"
    (let [m1 (mk-memory! "root-a.md")
          m2 (mk-memory! "root-b.md")
          m3 (mk-memory! "decisions/foo.md")
          rel-paths (set (mapv :mm.memory/rel-path
                               (dt/siblings-of (:db/id m1)
                                                :mm.memory/rel-path)))]
      (is (contains? rel-paths "root-b.md"))
      (is (not (contains? rel-paths "decisions/foo.md"))
          "nested entity NOT a sibling of root"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; sandbar.navigate.siblings/siblings-of wrapper
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest wrapper-envelope-shape
  (testing "wrapper returns {:siblings :total :returned}"
    (let [m1 (mk-memory! "decisions/foo.md")
          m2 (mk-memory! "decisions/bar.md")
          result (siblings/siblings-of {:entity (:db/id m1)
                                        :path-slot :mm.memory/rel-path})]
      (is (= 1 (:total result)))
      (is (= 1 (:returned result)))
      (is (vector? (:siblings result))))))

(deftest wrapper-respects-limit
  (testing ":limit caps :returned but :total reflects full set"
    (let [m1 (mk-memory! "decisions/foo.md")
          _ (mk-memory! "decisions/bar.md")
          _ (mk-memory! "decisions/baz.md")
          _ (mk-memory! "decisions/qux.md")
          full   (siblings/siblings-of {:entity (:db/id m1)
                                        :path-slot :mm.memory/rel-path})
          capped (siblings/siblings-of {:entity (:db/id m1)
                                        :path-slot :mm.memory/rel-path
                                        :limit 2})]
      (is (= 3 (:total full)))
      (is (= 3 (:total capped)))
      (is (= 2 (:returned capped))))))

(deftest wrapper-requires-entity
  (is (thrown? AssertionError
               (siblings/siblings-of {:path-slot :mm.memory/rel-path}))))

(deftest wrapper-requires-path-slot
  (testing ":path-slot is required and must be a keyword"
    (let [m1 (mk-memory! "decisions/foo.md")]
      (is (thrown? AssertionError
                   (siblings/siblings-of {:entity (:db/id m1)})))
      (is (thrown? AssertionError
                   (siblings/siblings-of {:entity (:db/id m1)
                                          :path-slot "not-a-keyword"}))))))

(deftest wrapper-entities-projected-as-maps
  (testing "result :siblings entries are entity-maps with :db/id + namespaced keys"
    (let [m1 (mk-memory! "decisions/foo.md")
          _ (mk-memory! "decisions/bar.md")
          result (siblings/siblings-of {:entity (:db/id m1)
                                        :path-slot :mm.memory/rel-path})
          first-sibling (first (:siblings result))]
      (is (contains? first-sibling :db/id))
      (is (some? (:mm.memory/rel-path first-sibling))))))
