(ns sandbar.security.query-e2e-test
  "DB-dependent E2E for the read-plane sanitize-where gate — the LEGIT-passes
  regression floor (a data-pattern :where must return the SAME result the
  primitive returns today) plus the full EDN-string handler-path containment
  test (a read-only-reachable verb rejects a malicious payload and writes no
  sentinel).

  These require a working test-db fixture.  As of this worktree the fixture is
  blocked by a PRE-EXISTING baseline failure (`:dt/slots` unresolved during
  schema load) that ALSO fails datatype-test / aggregate-test and is NOT a
  regression of this work — see sandbar.security.query-test (DB-free) for the
  security-critical contract + wiring proofs that run green regardless.

  Design of record: READPLANE-LAYER1-ALLOWLIST-DESIGN.md §5.2."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [sandbar.db.datatype :as dt]
            [sandbar.search :as search]
            [sandbar.mcp.tools :as tools]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "security-query-e2e"}))

(defn- make-memory-typed!
  [name memory-type]
  (dt/make :mm/Memory
           {:mm.memory/rel-path    (str "test/" name ".md")
            :mm.memory/name        name
            :mm.memory/memory-type memory-type
            :mm.memory/body-raw    "body content here"}))

(def ^:private shell-payload '[[(clojure.java.shell/sh "id") ?x]])

;; ---- count-of (datatype.clj:949) ----

(deftest e2e-count-of-legit-passes
  (testing "count-of with a data-pattern :where returns the correct count"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (make-memory-typed! "gamma" :decision)
    (is (= 2 (dt/count-of :mm/Memory '[[?e :mm.memory/memory-type :decision]])))))

;; ---- group-by-of (datatype.clj:972) ----

(deftest e2e-group-by-of-legit-passes
  (testing "group-by-of with a data-pattern :where returns correct buckets"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :decision)
    (make-memory-typed! "gamma" :plan)
    (let [res (dt/group-by-of :mm/Memory :mm.memory/memory-type
                              '[[?e :mm.memory/memory-type :decision]])]
      (is (= {:decision 2} res)))))

;; ---- where-matching-eids (search.clj:194) ----

(deftest e2e-where-matching-eids-legit-passes
  (testing "where-matching-eids with a data-pattern :where returns matching eids"
    (make-memory-typed! "alpha" :decision)
    (make-memory-typed! "beta"  :plan)
    (let [eids ((resolve 'sandbar.search/where-matching-eids)
                :mm/Memory '[[?e :mm.memory/memory-type :decision]])]
      (is (set? eids))
      (is (= 1 (count eids))))))

;; ---- containment: full EDN-string handler path (a read-only principal reaches
;;      sandbar.aggregate.count; prove the wire path rejects + writes no file) ----

(deftest e2e-handler-edn-string-containment
  (testing "the aggregate.count handler path (EDN-string :where → count-of)
            rejects a malicious payload and creates no sentinel file"
    (make-memory-typed! "alpha" :decision)
    (let [sentinel  (str (System/getProperty "java.io.tmpdir")
                         "/sanitize-where-e2e-" (System/nanoTime) ".txt")
          where-str (str "[[(clojure.core/spit \"" sentinel "\" \"PWNED\") ?_]]")
          handler   (resolve 'sandbar.mcp.tools/aggregate-count-handler)]
      (is (not (.exists (io/file sentinel))))
      (is (thrown? clojure.lang.ExceptionInfo
                   (handler {"class" ":mm/Memory" "where" where-str})))
      (is (not (.exists (io/file sentinel)))
          "sentinel must NOT exist — the read-plane verb never invoked spit")))
  (testing "the same handler path with a LEGIT :where still works"
    (make-memory-typed! "beta" :plan)
    (let [handler (resolve 'sandbar.mcp.tools/aggregate-count-handler)
          res     (handler {"class" ":mm/Memory"
                            "where" "[[?e :mm.memory/memory-type :plan]]"})]
      (is (= {:count 1} res)))))
