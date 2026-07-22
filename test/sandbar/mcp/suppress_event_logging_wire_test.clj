(ns sandbar.mcp.suppress-event-logging-wire-test
  "Client-requested per-call event-row suppression + the recall-hook lean
  path, over the REAL /mcp wire (2026-07-21).

  The PreToolUse `inject_feedback_recall` hook fires on EVERY tool call;
  each of its `tools/call` POSTs previously minted a permanent
  `:event/HttpRequest` row via the /mcp route's `log-request` interceptor
  (~7k rows/day).  Per Dan's reduce-substantially events ruling
  (2026-07-21 memory-strategy docket) under the B5 proactive-cleanup
  authorization, /mcp now honors `x-sandbar-suppress-event-logging: true`
  via `event/honor-suppress-event-logging-header`, which feeds the SAME
  `:suppress-event-logging?` context flag `should-log?` already consulted
  (the pre-existing, unit-tested suppression gate —
  `api-event-test/suppress-event-logging-test`).

  This test is the CONSUMER CONTRACT for the hook's exact call shape:
  a `sandbar.search.bm25f` tools/call with `:projection \"frontmatter\"`
  + `:limit 10` + the suppress header, POSTed through the full Pedestal
  interceptor chain (content-negotiation + bearer auth + JSON-RPC dispatch
  + read-plane firewall) against a SCRATCH in-process service.  Asserts:

  1. suppressed call → ZERO `:event/HttpRequest` rows for /mcp (direct
     `d/q` — the read-plane firewall doesn't apply inside the test JVM);
  2. unsuppressed call → the row IS minted (contrast guard: proves the
     zero above is suppression, not broken logging);
  3. the frontmatter hits carry the four slots the hook consumes
     (name / description / rel-path / memory-type) and NOT the bulky
     `:mm.memory/body-raw` — the byte-compatibility precondition for the
     hook's unchanged `format-reminder`;
  4. value \"1\" also suppresses; value \"false\" does not.

  Auth note (mirrors tag-histogram-wire-test): /mcp `require-bearer` 401s
  unauthenticated requests, so a throwaway read-only ServiceAccount is
  seeded with a RUNTIME-generated api-key — no credential literal is
  committed.  The suppress interceptor sits AFTER `require-bearer`, so
  only authenticated calls can suppress (401 probes stay logged)."
  (:require [cheshire.core       :as json]
            [clojure.test        :refer :all]
            [datomic.api         :as d]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datomic  :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.search      :as search]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name    "mcp-suppress-event-wire-test"
                            :auth?        false
                            :extra-schema [:auth :event]})
  (fn [t] (search/clear-bm25f-cache!) (t)))

(defn- seed-bearer!
  "Seed a read-only ServiceAccount whose api-key is generated at RUNTIME
  (no committed credential literal); return its Bearer token."
  []
  (let [api-key (str (java.util.UUID/randomUUID))
        svc     :suppress-wire-probe
        role    (dt/make :auth/Role
                         {:auth/role-name  auth/read-only-role
                          :auth/role-label "suppress-wire read-only"}
                         {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name svc
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name svc) ":" api-key)))

(defn- seed-guidance-memory!
  "One guidance-shaped :mm/Memory carrying every hook-consumed slot + a
  body, so the frontmatter wire assertions can bite."
  []
  (dt/make :mm/Memory
           {:mm.memory/name        "use-mem-not-bb"
            :mm.memory/description "mem is the canonical CLI invocation, not bb"
            :mm.memory/rel-path    "memory/interaction/use_mem_not_bb_for_canonical_cli.md"
            :mm.memory/memory-type :feedback
            :mm.memory/body-raw    "The canonical CLI is mem; bb test is the recurring violation."}))

(defn- recall-hook-call
  "POST the hook's exact tools/call shape to /mcp.  `extra-headers` lets a
  case add (or omit) the suppress header."
  [bearer extra-headers]
  (response-for service :post "/mcp"
                :headers (merge {"Content-Type"  "application/json"
                                 "Accept"        "application/json"
                                 "Authorization" (str "Bearer " bearer)}
                                extra-headers)
                :body (json/generate-string
                        {:jsonrpc "2.0" :id 1 :method "tools/call"
                         :params  {:name      "sandbar.search.bm25f"
                                   :arguments {:query      "canonical cli mem"
                                               :class      ":mm/Memory"
                                               :limit      10
                                               :projection "frontmatter"}}})))

(defn- mcp-event-row-count
  "Direct-DB count of :event/HttpRequest rows logged for the /mcp path."
  []
  (or (d/q '[:find (count ?e) .
             :where [?e :dt/type :event/HttpRequest]
                    [?e :http/path "/mcp"]]
           (d/db (db/conn)))
      0))

(deftest suppress-header-stanches-event-row-and-lean-hits-ride-the-wire
  (let [bearer (seed-bearer!)
        _      (seed-guidance-memory!)]

    (testing "suppressed call: 200 envelope, frontmatter hits, ZERO event rows"
      (let [resp    (recall-hook-call bearer {"x-sandbar-suppress-event-logging" "true"})
            body    (json/parse-string (:body resp) true)
            payload (some-> body :result :content first :text (json/parse-string true))
            entity  (:entity (first (:hits payload)))]
        (Thread/sleep 60)
        (is (= 200 (:status resp)) (str "expected 200; body=" (:body resp)))
        (is (nil? (:error body)))
        (is (not (:isError (:result body)))
            (str "bm25f frontmatter call must not error; content="
                 (pr-str (:content (:result body)))))
        (testing "hits carry the four hook-consumed slots, body trimmed"
          (is (seq (:hits payload)) "seeded guidance memory must match")
          (is (= "use-mem-not-bb" (:mm.memory/name entity)))
          (is (= "mem is the canonical CLI invocation, not bb"
                 (:mm.memory/description entity)))
          (is (= "memory/interaction/use_mem_not_bb_for_canonical_cli.md"
                 (:mm.memory/rel-path entity)))
          (is (= "feedback" (:mm.memory/memory-type entity))
              "memory-type survives in the bare-name JSON form the hook's guidance-type-set filters on")
          (is (not (contains? entity :mm.memory/body-raw))
              "the bulky body slot must NOT ride the wire under frontmatter"))
        (is (zero? (mcp-event-row-count))
            "suppress header must stanch the :event/HttpRequest row")))

    (testing "value \"1\" also suppresses"
      (recall-hook-call bearer {"x-sandbar-suppress-event-logging" "1"})
      (Thread/sleep 60)
      (is (zero? (mcp-event-row-count))))

    (testing "contrast guard: WITHOUT the header the row IS minted"
      (recall-hook-call bearer {})
      (Thread/sleep 60)
      (is (pos? (mcp-event-row-count))
          "normal /mcp calls must still mint their event row (logging not broken)"))

    (testing "a non-truthy value does not suppress"
      (let [before (mcp-event-row-count)]
        (recall-hook-call bearer {"x-sandbar-suppress-event-logging" "false"})
        (Thread/sleep 60)
        (is (= (inc before) (mcp-event-row-count))
            "only \"true\"/\"1\" suppress; anything else logs as normal")))))
