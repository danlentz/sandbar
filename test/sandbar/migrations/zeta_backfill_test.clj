(ns sandbar.migrations.zeta-backfill-test
  "Tests for sandbar.migrations.zeta-backfill — pure-fn per-entity backfill
  tx-data computation. No live-DB needed; tests verify correctness of the
  pref-label cascade + tx-data shape + skip-conditions per ζ Scope B ADR §7."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-uuid :as uuid]
            [sandbar.identifier :as id]
            [sandbar.migrations.zeta-backfill :as zb]))


(deftest slug-title-conversion
  (testing "slug->title splits on underscore + hyphen + title-cases"
    (is (= "Three Tier Identifier Value Hierarchy"
           (zb/slug->title "three_tier_identifier_value_hierarchy")))
    (is (= "Foo Bar"
           (zb/slug->title "foo-bar")))
    (is (= "Mixed Form Slug"
           (zb/slug->title "mixed_form-slug")))
    (is (= "Foo"
           (zb/slug->title "foo")))
    (is (= ""
           (zb/slug->title ""))))
  (testing "Multi-character words preserve interior chars (only first char upper)"
    (is (= "Foobar"
           (zb/slug->title "foobar")))
    (is (= "Foobar Baz"
           (zb/slug->title "foobar_baz")))))


(deftest truncate-fallback-behavior
  (testing "Shorter strings pass through unchanged"
    (is (= "Hello" (zb/truncate-fallback "Hello" 80)))
    (is (= "" (zb/truncate-fallback "" 80))))
  (testing "Longer strings truncate to max-chars (no ellipsis)"
    (is (= "ab" (zb/truncate-fallback "abcdef" 2)))
    (is (= 80 (count (zb/truncate-fallback (apply str (repeat 100 "x")) 80))))))


(deftest pref-label-resolution-cascade
  (testing "Name preferred when present + non-blank"
    (is (= "Foo Decision"
           (zb/resolve-pref-label {:db/ident :memory.decisions/foo
                                    :mm.memory/name "Foo Decision"
                                    :mm.memory/description "A description"}))))
  (testing "Description fallback when name blank or missing"
    (is (= "Long description text"
           (zb/resolve-pref-label {:db/ident :memory.decisions/foo
                                    :mm.memory/name nil
                                    :mm.memory/description "Long description text"})))
    (is (= "Long description text"
           (zb/resolve-pref-label {:db/ident :memory.decisions/foo
                                    :mm.memory/name ""
                                    :mm.memory/description "Long description text"})))
    (is (= "Long description text"
           (zb/resolve-pref-label {:db/ident :memory.decisions/foo
                                    :mm.memory/description "Long description text"}))))
  (testing "Description truncates at +pref-label-fallback-max-chars+"
    (let [long-desc (apply str (repeat 200 "x"))
          result (zb/resolve-pref-label {:db/ident :memory.decisions/foo
                                         :mm.memory/description long-desc})]
      (is (= zb/+pref-label-fallback-max-chars+ (count result)))))
  (testing "Slug fallback last-resort when name + description both blank/missing"
    (is (= "Three Tier Identifier Value Hierarchy"
           (zb/resolve-pref-label {:db/ident :memory.decisions/three_tier_identifier_value_hierarchy})))
    (is (= "Some Observation"
           (zb/resolve-pref-label {:db/ident :memory.observations/some_observation
                                    :mm.memory/name ""
                                    :mm.memory/description ""}))))
  (testing "Throws when no name + no description + no ident"
    (is (thrown? clojure.lang.ExceptionInfo
                 (zb/resolve-pref-label {})))))


(deftest entity-backfill-tx-unpopulated
  (testing "Entity with no :mm/id and no :mm/pref-label generates both add-assertions"
    (let [tx (zb/entity-backfill-tx {:db/id 12345
                                      :db/ident :memory.decisions/foo
                                      :mm.memory/name "Foo Decision"})]
      (is (= 2 (count tx)))
      (let [[id-tx pl-tx] tx]
        (is (= [:db/add 12345 :mm/id (id/ident-uuid :memory.decisions/foo)] id-tx))
        (is (= [:db/add 12345 :mm/pref-label "Foo Decision"] pl-tx))))))


(deftest entity-backfill-tx-partially-populated
  (testing "Entity with existing :mm/id only generates :mm/pref-label assertion"
    (let [existing-uuid (uuid/v5 uuid/+namespace-url+ "existing")
          tx (zb/entity-backfill-tx {:db/id 12345
                                      :db/ident :memory.decisions/foo
                                      :mm.memory/name "Foo Decision"
                                      :mm/id existing-uuid})]
      (is (= 1 (count tx)))
      (is (= [:db/add 12345 :mm/pref-label "Foo Decision"] (first tx)))))
  (testing "Entity with existing :mm/pref-label only generates :mm/id assertion"
    (let [tx (zb/entity-backfill-tx {:db/id 12345
                                      :db/ident :memory.decisions/foo
                                      :mm.memory/name "Foo Decision"
                                      :mm/pref-label "Already Set"})]
      (is (= 1 (count tx)))
      (is (= [:db/add 12345 :mm/id (id/ident-uuid :memory.decisions/foo)] (first tx))))))


(deftest entity-backfill-tx-fully-populated
  (testing "Entity with both :mm/id + :mm/pref-label generates no tx-data (no-op)"
    (let [tx (zb/entity-backfill-tx {:db/id 12345
                                      :db/ident :memory.decisions/foo
                                      :mm.memory/name "Foo Decision"
                                      :mm/id (uuid/v5 uuid/+namespace-url+ "existing")
                                      :mm/pref-label "Already Set"})]
      (is (= [] tx)))))


(deftest entity-backfill-tx-no-ident
  (testing "Entity without :db/ident generates no tx-data (cannot derive UUID)"
    (let [tx (zb/entity-backfill-tx {:db/id 12345
                                      :mm.memory/name "Foo"})]
      (is (= [] tx)))))


(deftest entity-backfill-tx-uses-ident-uuid-determinism
  (testing "Same :db/ident yields the same :mm/id across invocations"
    (let [tx1 (zb/entity-backfill-tx {:db/id 12345
                                       :db/ident :memory.decisions/foo
                                       :mm.memory/name "Foo Decision"})
          tx2 (zb/entity-backfill-tx {:db/id 67890  ; DIFFERENT eid
                                       :db/ident :memory.decisions/foo  ; SAME ident
                                       :mm.memory/name "Foo Decision"})
          uuid1 (nth (first tx1) 3)
          uuid2 (nth (first tx2) 3)]
      (is (= uuid1 uuid2)
          "Same ident → same :mm/id even if eids differ (eids are BRITTLE tier; ident is OPAQUE-tier-source)"))))


(deftest entities-backfill-tx-batches-correctly
  (testing "Multi-entity batch produces concatenated tx-data"
    (let [entities [{:db/id 1
                     :db/ident :memory.decisions/foo
                     :mm.memory/name "Foo"}
                    {:db/id 2
                     :db/ident :memory.decisions/bar
                     :mm.memory/name "Bar"}
                    {:db/id 3
                     :db/ident :memory.decisions/baz
                     :mm.memory/name "Baz"
                     ;; baz already fully populated → no tx
                     :mm/id (uuid/v5 uuid/+namespace-url+ "x")
                     :mm/pref-label "Baz Already"}]
          tx (zb/entities-backfill-tx entities)]
      (is (= 4 (count tx))
          "Two unpopulated entities → 2 × 2 tx-statements = 4; baz contributes 0"))))


(deftest pref-label-resolution-real-world-shapes
  (testing "Memory-prefixed ident matches expected derived title"
    (is (= "Phase B Created By Migration"
           (zb/slug->title "phase_b_created_by_migration"))))
  (testing "Multi-segment substrate ident derives sensibly"
    ;; memory.libraries.clojure/core_async → slug "core_async"
    (is (= "Core Async"
           (zb/slug->title "core_async")))))
