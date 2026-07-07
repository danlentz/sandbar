(ns sandbar.security.query-test
  "Tests for the read-plane query-layer security gate
  `sandbar.security.query/sanitize-where` — the Layer-1 deny-by-default
  allowlist that closes AP-S3-6 vector A (query-time fn-resolution).

  This namespace is DELIBERATELY DB-FREE (no test-db fixture).  It proves:
    - the sanitize-where CONTRACT: accept legit, reject every dangerous class,
      rejection quality, side-effect-freedom;
    - the WIRING at each of the three splice-site primitives (count-of /
      group-by-of / where-matching-eids): a malicious :where throws BEFORE d/q.
      Crucially, each primitive calls sanitize-where in its `let` binding
      *before* `(db/db)` / `d/q` are evaluated, so the rejection path needs no
      database at all — proving the gate fires ahead of any query.

  The DB-dependent LEGIT-passes regression floor (same d/q result as today)
  lives in `sandbar.security.query-e2e-test`, which requires the test-db
  fixture (currently blocked by a pre-existing baseline fixture failure —
  `:dt/slots` unresolved — that also fails datatype-test / aggregate-test and
  is NOT a regression of this work).

  Design of record:
  audit-results/xminus-build-2026-07-04/READPLANE-LAYER1-ALLOWLIST-DESIGN.md §5."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [sandbar.security.query :as secq]
            [sandbar.db.datatype :as dt]
            [sandbar.search :as search]
            [sandbar.navigate.path.evaluate :as peval]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UNIT — sanitize-where contract
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest accept-empty-and-nil
  (testing "nil / [] return as-is (nothing to check)"
    (is (nil? (secq/sanitize-where nil)))
    (is (= [] (secq/sanitize-where [])))))

(deftest accept-data-pattern-triples
  (testing "single data-pattern triple passes unchanged"
    (let [w '[[?e :mm.memory/scope :global]]]
      (is (= w (secq/sanitize-where w)))))
  (testing "multiple data-pattern triples pass unchanged"
    (let [w '[[?e :mm.memory/memory-type :decision]
              [?e :mm.memory/scope :project]]]
      (is (= w (secq/sanitize-where w))))))

(deftest accept-allowlisted-expression-clauses
  (testing "allowlisted string predicate (the demonstrated S3-census case)"
    (let [w '[[(clojure.string/starts-with? ?n "auth")]]]
      (is (= w (secq/sanitize-where w)))))
  (testing "allowlisted comparison"
    (let [w '[[(>= ?count 3)]]]
      (is (= w (secq/sanitize-where w)))))
  (testing "allowlisted type predicate"
    (let [w '[[(keyword? ?v)]]]
      (is (= w (secq/sanitize-where w))))))

(deftest accept-aliased-namespace-resolves-by-var-identity
  (testing "str/starts-with? (aliased) resolves to the allowlisted var — proves
            resolution-by-var-identity, not string-name matching"
    (let [w '[[(str/starts-with? ?n "auth")]]]
      (is (= w (secq/sanitize-where w))))))

(defn- rejected?
  "Run sanitize-where on `w`; return the ex-data if it throws ExceptionInfo,
  else ::not-rejected."
  [w]
  (try
    (secq/sanitize-where w)
    ::not-rejected
    (catch clojure.lang.ExceptionInfo e
      (ex-data e))))

(deftest reject-shell-exec
  (testing "the canonical RCE payload is rejected"
    (let [d (rejected? '[[(clojure.java.shell/sh "id") ?x]])]
      (is (map? d))
      (is (= 'clojure.java.shell/sh (:offending-symbol d))))))

(deftest reject-file-write-spit
  (testing "the proven integrity-violation payload (spit) is rejected"
    (let [d (rejected? '[[(clojure.core/spit "/tmp/probe" "x") ?_]])]
      (is (map? d))
      (is (= 'clojure.core/spit (:offending-symbol d))))))

(deftest reject-file-read-slurp
  (testing "the proven exfiltration payload (slurp) is rejected"
    (let [d (rejected? '[[(clojure.core/slurp "/etc/passwd") ?x]])]
      (is (map? d))
      (is (= 'clojure.core/slurp (:offending-symbol d))))))

(deftest reject-host-state-getproperty
  (testing "System/getProperty host-state read is rejected"
    (let [d (rejected? '[[(System/getProperty "user.home") ?home]])]
      (is (map? d))
      (is (= 'System/getProperty (:offending-symbol d))))))

(deftest reject-thread-spawn-deref-future
  (testing "the proven thread-reachability payload (deref+future) is rejected
            at the deref head — the nested future/getProperty need not be reached"
    (let [d (rejected? '[[(deref (future (System/getProperty "user.home"))) ?home]])]
      (is (map? d))
      (is (= 'deref (:offending-symbol d))))))

(deftest reject-eval-reader
  (testing "eval/read-string is rejected"
    (let [d (rejected? '[[(clojure.core/eval (read-string "(+ 1 1)")) ?x]])]
      (is (map? d))
      (is (= 'clojure.core/eval (:offending-symbol d))))))

(deftest reject-datomic-reentrancy
  (testing "datomic.api/q re-entrancy is rejected"
    (let [d (rejected? '[[(datomic.api/q [:find ?e :where [?e :db/ident]]) ?x]])]
      (is (map? d))
      (is (= 'datomic.api/q (:offending-symbol d))))))

(deftest reject-apply-laundering
  (testing "apply-laundering is rejected at the apply head (apply not on list —
            it would otherwise indirect to any fn)"
    (let [d (rejected? '[[(apply clojure.java.shell/sh ["id"]) ?x]])]
      (is (map? d))
      (is (= 'apply (:offending-symbol d))))))

(deftest reject-argument-position-laundering
  (testing "a forbidden symbol laundered into ARGUMENT position of an
            allowlisted head is still rejected (recursion catches it)"
    (let [d (rejected? '[[(= ?a (clojure.java.shell/sh "id"))]])]
      (is (map? d))
      (is (= 'clojure.java.shell/sh (:offending-symbol d))))))

(deftest reject-nested-logic-v1
  (testing "v1 conservative default: nested logic (or/...) is rejected with the
            distinct :nested-logic-unsupported-v1 reason"
    (let [d (rejected? '[(or [(clojure.java.shell/sh "id") ?x])])]
      (is (map? d))
      (is (= :nested-logic-unsupported-v1 (:reason d)))
      (is (= 'or (:offending-symbol d))))))

(deftest reject-unknown-symbol-deny-by-default
  (testing "an unknown/typo symbol is rejected — not blocklisted, simply absent"
    (let [d (rejected? '[[(my.ns/whatever ?x)]])]
      (is (map? d))
      (is (= 'my.ns/whatever (:offending-symbol d))))))

(deftest rejection-quality
  (testing "ex-data carries actionable, machine-loggable fields"
    (let [d (rejected? '[[(clojure.java.shell/sh "id") ?x]])]
      (is (= '[(clojure.java.shell/sh "id") ?x] (:rejected-clause d)))
      (is (= 'clojure.java.shell/sh (:offending-symbol d)))
      (is (seq (:allowed d)) "the sorted allowed-op set is present + non-empty")
      (is (= 'sandbar.security.query/sanitize-where (:sanitizer d))))))

(deftest sanitize-is-side-effect-free
  (testing "naming spit must NOT call spit — rejection happens before any
            resolution/invocation, so no file is created"
    (let [sentinel (str (System/getProperty "java.io.tmpdir")
                        "/sanitize-where-sentinel-" (System/nanoTime) ".txt")
          payload  (list (list 'clojure.core/spit sentinel "PWNED") '?_)
          w        (vector payload)]
      (is (not (.exists (io/file sentinel))) "precondition: sentinel absent")
      (is (= ::rejected
             (try (secq/sanitize-where w) ::not-rejected
                  (catch clojure.lang.ExceptionInfo _ ::rejected))))
      (is (not (.exists (io/file sentinel)))
          "sentinel must NOT exist — sanitize-where never invoked spit"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; WIRING — the gate fires INSIDE each splice-site primitive, before d/q.
;; No DB needed: each primitive calls sanitize-where in a `let` binding that is
;; evaluated before `(db/db)` / `d/q`, so a malicious :where throws first.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private shell-payload '[[(clojure.java.shell/sh "id") ?x]])

(deftest wiring-count-of-rejects-before-d-q
  (testing "db.datatype/count-of (splice site datatype.clj:949) rejects the
            shell payload from sanitize-where before ever touching (db/db)/d/q"
    (is (thrown? clojure.lang.ExceptionInfo
                 (dt/count-of :mm/Memory shell-payload)))))

(deftest wiring-group-by-of-rejects-before-d-q
  (testing "db.datatype/group-by-of (splice site datatype.clj:972) rejects the
            shell payload before d/q"
    (is (thrown? clojure.lang.ExceptionInfo
                 (dt/group-by-of :mm/Memory :mm.memory/memory-type shell-payload)))))

(deftest wiring-where-matching-eids-rejects-before-d-q
  (testing "search/where-matching-eids (splice site search.clj:194) rejects the
            shell payload before d/q (private fn reached via var)"
    (let [wme (resolve 'sandbar.search/where-matching-eids)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (wme :mm/Memory shell-payload))))))

(deftest wiring-no-file-written-on-primitive-reject
  (testing "a spit payload routed through count-of throws before d/q AND writes
            no file — proving the read-plane verb never invokes spit"
    (let [sentinel (str (System/getProperty "java.io.tmpdir")
                        "/sanitize-where-wiring-" (System/nanoTime) ".txt")
          payload  (vector (vector (list 'clojure.core/spit sentinel "PWNED") '?_))]
      (is (not (.exists (io/file sentinel))))
      (is (thrown? clojure.lang.ExceptionInfo (dt/count-of :mm/Memory payload)))
      (is (not (.exists (io/file sentinel)))
          "sentinel must NOT exist"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TRIPWIRE — the prospective 4th consumer (path :TEST/:FILTER fn-resolver)
;; MUST route through the SAME allowlist when it is built.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tripwire-path-test-filter-not-yet-compiled
  (testing "path :TEST / :FILTER are NOT yet compiled — they throw
            unsupported-op today, so the path plane splices no user expression
            clause.  This is the tripwire: when the :TEST fn-name resolver IS
            built (navigate/path/evaluate.clj), it MUST resolve names through
            sandbar.security.query/safe-query-op-allowlist — the SAME shared
            constant this suite validates — or the path plane rejoins the
            AP-S3-6 vector-A vulnerable set.  When that lands, replace this
            assertion with a :TEST-naming-a-non-allowlisted-fn rejection test."
    (let [evaluate-node (resolve 'sandbar.navigate.path.evaluate/evaluate-node)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (evaluate-node {:op :TEST} nil nil)))
      (is (thrown? clojure.lang.ExceptionInfo
                   (evaluate-node {:op :FILTER} nil nil))))
    ;; The shared constant the 4th consumer must depend on exists and is non-empty.
    (is (set? secq/safe-query-op-allowlist))
    (is (seq secq/safe-query-op-allowlist))))
