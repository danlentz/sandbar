(ns sandbar.security.allowlist-single-source-test
  "it6-f5 ALLOWLIST SINGLE-SOURCE UNIFICATION — golden identical-semantics suite.

   Proves the unification (path-grammar `:TEST` resolver wired onto the single
   `sandbar.security.query` safe-operator vocabulary) is IDENTICAL-SEMANTICS:
   no live consumer's accept set changed.  Falsification-first — each pin FAILS
   against a set that has drifted from the frozen membership.

   Consumers covered:
     - `:where` plane (`sanitize-where` via `safe-query-op-allowlist`);
     - path `:TEST` plane (`compile-test` via `test-fn-registry`, now derived
       from `safe-path-test-registry`).
   The prospective CodeAct sandbox / Layer-2 DSL compiler are NOT yet built;
   the vocabulary + gate are positioned for them.

   Pure unit suite — no DB, no client-dir, no server contact."
  (:require [clojure.test :refer [deftest testing is]]
            [sandbar.security.query :as secq]
            [sandbar.navigate.path.datomic :as compiler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FROZEN membership — the golden pins.  Changing either projection's membership
;; is a security review that MUST consciously update these literals.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private expected-where-head-ops
  "The 21 `:where`-head operators (as fully-qualified symbols).  Frozen snapshot
  of the pre-it6-f5 `safe-query-op-allowlist` — my diff does not touch that def,
  so this must remain byte-identical."
  '#{clojure.core/= clojure.core/not=
     clojure.core/< clojure.core/<= clojure.core/> clojure.core/>=
     clojure.string/starts-with? clojure.string/ends-with?
     clojure.string/includes? clojure.string/blank?
     clojure.core/nil? clojure.core/some? clojure.core/string?
     clojure.core/keyword? clojure.core/number? clojure.core/int?
     clojure.core/boolean? clojure.core/contains?
     clojure.core/get clojure.core/count clojure.core/nth})

(def ^:private expected-path-test-registry
  "The 15-entry path `:TEST` unary-predicate registry.  Frozen snapshot of the
  pre-it6-f5 hand-forked `test-fn-registry` DEFAULT — the load-bearing
  byte-identity pin, since it6-f5 changed HOW the default is constructed
  (derive-from-single-source) and must NOT change WHAT it contains."
  '{:pos?      clojure.core/pos?
    :neg?      clojure.core/neg?
    :zero?     clojure.core/zero?
    :nil?      clojure.core/nil?
    :some?     clojure.core/some?
    :true?     clojure.core/true?
    :false?    clojure.core/false?
    :string?   clojure.core/string?
    :keyword?  clojure.core/keyword?
    :integer?  clojure.core/integer?
    :number?   clojure.core/number?
    :coll?     clojure.core/coll?
    :map?      clojure.core/map?
    :empty?    clojure.core/empty?
    :not-empty clojure.core/not-empty})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-consumer accept-set is UNCHANGED (identical-semantics)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest where-head-allowlist-membership-frozen
  (testing ":where-plane accept set (safe-query-op-allowlist) is byte-identical"
    (is (= expected-where-head-ops
           (into #{} (map symbol) secq/safe-query-op-allowlist))
        "safe-query-op-allowlist membership must match the frozen snapshot")
    (is (= 21 (count secq/safe-query-op-allowlist)))))

(deftest path-test-registry-membership-frozen
  (testing "path :TEST accept set (safe-path-test-registry) is byte-identical"
    (is (= expected-path-test-registry secq/safe-path-test-registry)
        "single-source safe-path-test-registry must match the frozen snapshot")
    (is (= 15 (count secq/safe-path-test-registry)))))

(deftest runtime-test-fn-registry-derives-from-single-source
  (testing "the path compiler's runtime registry default == the single source"
    (is (= secq/safe-path-test-registry @compiler/test-fn-registry)
        "test-fn-registry must be initialized FROM safe-path-test-registry")
    (is (= expected-path-test-registry @compiler/test-fn-registry)
        "runtime registry default is byte-identical to the historical default")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The union vocabulary + the gate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest vocabulary-is-union-of-both-projections
  (testing "safe-operator-vocabulary = where-head allowlist ∪ :TEST registry vars"
    (let [test-vars (into #{} (map find-var) (vals secq/safe-path-test-registry))]
      (is (= (into secq/safe-query-op-allowlist test-vars)
             secq/safe-operator-vocabulary))
      ;; 21 where-heads + 15 test-preds, with 5 overlaps (nil? some? string?
      ;; keyword? number?) ⇒ 31 distinct vars.
      (is (= 31 (count secq/safe-operator-vocabulary)))
      (is (every? secq/safe-operator-vocabulary secq/safe-query-op-allowlist))
      (is (every? secq/safe-operator-vocabulary test-vars)))))

(deftest gate-accepts-every-vetted-operator
  (testing "safe-operator-symbol? accepts every symbol both projections can yield"
    (doseq [sym (vals secq/safe-path-test-registry)]
      (is (secq/safe-operator-symbol? sym)
          (str "path :TEST default symbol must pass the gate: " sym)))
    (doseq [v secq/safe-query-op-allowlist]
      (is (secq/safe-operator-symbol? (symbol v))
          (str ":where-head allowlist symbol must pass the gate: " (symbol v))))))

(deftest gate-rejects-dangerous-and-off-vocabulary
  (testing "safe-operator-symbol? denies-by-default everything off the vocabulary"
    (doseq [sym '[clojure.java.shell/sh   ; RCE
                  clojure.core/eval       ; RCE
                  clojure.core/slurp      ; file read
                  clojure.core/spit       ; file write
                  clojure.core/deref      ; + future ⇒ code exec
                  clojure.core/apply      ; DELIBERATELY excluded (launders a symbol)
                  clojure.core/re-find    ; regex DROPPED from v1 (no ReDoS backstop)
                  clojure.core/re-matches
                  clojure.core/first      ; safe-but-unneeded ⇒ still denied
                  clojure.core/identity]]
      (is (false? (secq/safe-operator-symbol? sym))
          (str "must be denied: " sym)))))

(deftest gate-never-force-loads-and-fails-closed-on-junk
  (testing "unresolvable / non-qualified / nil ⇒ false, and no ns is loaded"
    (is (false? (secq/safe-operator-symbol? 'no.such.namespace.xyz/foo)))
    (is (nil? (find-ns 'no.such.namespace.xyz))
        "probing a symbol must NOT load its namespace (no require side-effect)")
    (is (false? (secq/safe-operator-symbol? 'bare-symbol)))
    (is (false? (secq/safe-operator-symbol? :not-a-symbol)))
    (is (false? (secq/safe-operator-symbol? nil)))))
