(ns sandbar.identifier-test
  "Tests for ζ Scope B stable-identifier substrate primitive
  (`sandbar.identifier` namespace). Validates determinism + edge-cases of
  authority-UUID / namespace-UUID / entity-UUID / ident-UUID derivation
  chain per ζ Scope B ADR §3."
  (:require [clojure.test :refer [deftest is testing]]
            [clj-uuid :as uuid]
            [sandbar.identifier :as id]))


(deftest authority-uuid-stable
  (testing "Authority-UUID is deterministically computed from the seed"
    (is (uuid? id/+authority+))
    (is (= id/+authority+
           (uuid/v5 uuid/+namespace-url+ id/+default-authority-seed+))
        "Re-deriving from the same seed yields the same UUID")))


(deftest namespace-uuid-deterministic
  (testing "namespace-uuid produces stable v5 UUIDs"
    (let [u1 (id/namespace-uuid "decisions")
          u2 (id/namespace-uuid "decisions")
          u3 (id/namespace-uuid "plans")]
      (is (uuid? u1))
      (is (= u1 u2) "Same input yields same UUID")
      (is (not= u1 u3) "Different namespaces yield different UUIDs")
      (is (= u1 (uuid/v5 id/+authority+ "decisions"))
          "Derivation chain is uuid/v5(authority, namespace-name)")))
  (testing "namespace-uuid pre-conditions"
    (is (thrown? java.lang.AssertionError (id/namespace-uuid nil)))
    (is (thrown? java.lang.AssertionError (id/namespace-uuid "")))
    (is (thrown? java.lang.AssertionError (id/namespace-uuid "   ")))))


(deftest entity-uuid-deterministic
  (testing "entity-uuid produces stable v5 UUIDs from (namespace-name, slug)"
    (let [u1 (id/entity-uuid "decisions" "three_tier_identifier_value_hierarchy")
          u2 (id/entity-uuid "decisions" "three_tier_identifier_value_hierarchy")
          u3 (id/entity-uuid "decisions" "different_slug")
          u4 (id/entity-uuid "plans" "three_tier_identifier_value_hierarchy")]
      (is (uuid? u1))
      (is (= u1 u2) "Same inputs yield same entity-UUID")
      (is (not= u1 u3) "Different slugs yield different entity-UUIDs")
      (is (not= u1 u4) "Different namespaces yield different entity-UUIDs")
      (is (= u1 (uuid/v5 (id/namespace-uuid "decisions")
                         "three_tier_identifier_value_hierarchy"))
          "Derivation chain is uuid/v5(namespace-UUID, slug)")))
  (testing "entity-uuid pre-conditions"
    (is (thrown? java.lang.AssertionError (id/entity-uuid nil "slug")))
    (is (thrown? java.lang.AssertionError (id/entity-uuid "ns" nil)))
    (is (thrown? java.lang.AssertionError (id/entity-uuid "" "slug")))
    (is (thrown? java.lang.AssertionError (id/entity-uuid "ns" "")))))


(deftest ident-namespace-name-strips-memory-prefix
  (testing "memory.-prefixed idents have the substrate prefix stripped"
    (is (= "decisions"
           (id/ident->namespace-name :memory.decisions/foo)))
    (is (= "observations"
           (id/ident->namespace-name :memory.observations/bar)))
    (is (= "libraries.clojure"
           (id/ident->namespace-name :memory.libraries.clojure/core_async)))
    (is (= "libraries.synthesis"
           (id/ident->namespace-name :memory.libraries.synthesis/some_synthesis_2026_05_25))))
  (testing "non-memory.-prefixed idents pass through unchanged"
    (is (= "dt" (id/ident->namespace-name :dt/Class)))
    (is (= "mm" (id/ident->namespace-name :mm/Memory)))
    (is (= "workflow" (id/ident->namespace-name :workflow/session)))))


(deftest ident-uuid-composes-namespace-name-with-entity-uuid
  (testing "ident-uuid is equivalent to entity-uuid (ident->namespace-name, name)"
    (let [ident :memory.decisions/three_tier_identifier_value_hierarchy
          via-ident-uuid     (id/ident-uuid ident)
          via-explicit-chain (id/entity-uuid
                              (id/ident->namespace-name ident)
                              (name ident))]
      (is (= via-ident-uuid via-explicit-chain)
          "ident-uuid composes ident->namespace-name + entity-uuid"))
    (let [ident :memory.libraries.clojure/core_async]
      (is (= (id/ident-uuid ident)
             (id/entity-uuid "libraries.clojure" "core_async"))))))


(deftest cross-deployment-federation-property
  (testing "Two deployments with the same authority compute the same entity-UUIDs"
    ;; Simulate two deployments by rebinding +authority+ to two values, then
    ;; rebinding back to verify the property holds at the abstract level.
    (let [authority-A (uuid/v5 uuid/+namespace-url+ "deployment-A")
          authority-B (uuid/v5 uuid/+namespace-url+ "deployment-B")
          uuid-A-decisions-foo (with-redefs [id/+authority+ authority-A]
                                 (id/entity-uuid "decisions" "foo"))
          uuid-A-decisions-foo-bis (with-redefs [id/+authority+ authority-A]
                                     (id/entity-uuid "decisions" "foo"))
          uuid-B-decisions-foo (with-redefs [id/+authority+ authority-B]
                                 (id/entity-uuid "decisions" "foo"))]
      (is (= uuid-A-decisions-foo uuid-A-decisions-foo-bis)
          "Same authority + same inputs → same UUID (federation property)")
      (is (not= uuid-A-decisions-foo uuid-B-decisions-foo)
          "Different authorities → different UUIDs (deployment isolation)"))))


(deftest claude-corpus-deployment-seed-is-the-default
  (testing "The default +authority+ corresponds to the claude-corpus deployment seed"
    (is (= "tag:danlentz.github.io,2026:claude-memory"
           id/+default-authority-seed+)
        "Per ζ Scope B ADR §3.1 example for the claude-corpus deployment.")
    (is (= id/+authority+
           (uuid/v5 uuid/+namespace-url+
                    "tag:danlentz.github.io,2026:claude-memory")))))
