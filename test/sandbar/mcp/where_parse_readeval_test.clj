(ns sandbar.mcp.where-parse-readeval-test
  "S11/Rec-1 (R3b) — pinned parse-time reader-eval (`#=`) rejection regression.

   Closes the regression-test leg of Dan's 1A acceptance (see
   `decisions/dan_rules_seven_open_points_readplane_ceremony_copayloads_mcp_strategy_2026_07_06.md`,
   Decision 1: acceptance = live probe + regression test).  The live-probe leg was
   observed read-only against the running server; THIS pins the parse-time closure
   as a durable unit regression so a future edit that re-opens `#=` on the `:where`
   wire path fails the suite.

   TEST-LAYER ONLY — asserts existing product behavior; it introduces NO product
   code and is NOT a re-implementation of 1A (tools.clj is frozen this lane).  The
   E2 request-thread-scoped `*read-eval*` binding remains a 0.2.x follow-up and is
   deliberately NOT implemented here.

   Target: the private parser `#'sandbar.mcp.tools/->where-clauses` (tools.clj:280),
   which routes MCP `:where` strings through `clojure.edn/read-string` — a reader
   that cannot evaluate `#=(...)` by construction — and re-throws `Invalid :where
   EDN` on any read failure.  Pure EDN parsing: no DB / fixture / transactor."
  (:require [clojure.test :refer :all]
            [sandbar.mcp.tools]))

;; Reach the private parser via its var (defn-).
(def ^:private ->where-clauses #'sandbar.mcp.tools/->where-clauses)

(deftest where-parse-rejects-reader-eval-form
  (testing "a #=(...) reader-eval payload in a :where EDN string is REJECTED at parse time"
    (let [malicious "[[?e :mm.memory/memory-type #=(+ 1 1)]]"]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid :where EDN"
                            (->where-clauses malicious))
          "clojure.edn refuses the #= dispatch; ->where-clauses re-throws Invalid :where EDN — the payload is never evaluated")
      ;; The rejected input is echoed inert in ex-data, proving no evaluation.
      (try
        (->where-clauses malicious)
        (is false "->where-clauses must throw on a #= payload, not return")
        (catch clojure.lang.ExceptionInfo e
          (is (= malicious (:received (ex-data e)))
              "the rejected input is carried inert in ex-data :received, never evaluated"))))))

(deftest where-parse-accepts-normal-string
  (testing "a normal :where EDN string parses to the expected Datalog clause vector (unaffected by the #= firewall)"
    (is (= '[[?e :mm.memory/memory-type :decision]]
           (->where-clauses "[[?e :mm.memory/memory-type :decision]]"))
        "a benign :where string reads as ordinary EDN Datalog clauses")))
