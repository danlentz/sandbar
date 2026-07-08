(ns sandbar.mcp.tag-histogram-wire-test
  "S11/Rec-9 + LC1 over-the-wire receipt — a `tools/call` for the
   tag-histogram verb POSTed to /mcp through the FULL Pedestal interceptor
   chain (`io.pedestal.test/response-for`: content-negotiation + JSON-RPC
   envelope + dispatch + read-plane firewall) against a SCRATCH in-process
   service (isolated in-mem DB, NOT the live server on 8389) must return, in
   the serialized response body, a NON-EMPTY histogram with :total > 0 and
   distinct-source counts — proving the identless-tag fix (Rec-9: enumerate
   via `dt/all-instances-of`, canonical :tag = ident→value→id) AND the LC1
   `keep`-over-nil-source count hardening are correct across the real wire
   path, not just at the function boundary (`aggregate-test`).

   Coverage gap this closes: the tag-histogram verb previously had NO
   wire-level test — the pre-fix impl returned {:histogram [] :total 0} over
   this exact path against the whole (identless) corpus and nothing caught it.

   Wire name: the verb dispatches as the NEW underscore form
   `sandbar_aggregate_tag-histogram` (dots→underscores; leaf hyphen preserved).

   Auth note: /mcp `require-bearer` 401s an unauthenticated request, so we seed
   a throwaway read-only ServiceAccount whose api-key is generated at RUNTIME —
   NO credential literal is committed (mirrors initialize-instructions-wire-test).
   tag-histogram is a read-only/safe verb, so the read-only role authorizes it.
   `Accept: application/json` pins the deterministic-JSON response encoder so the
   assertions read a stable serialized body."
  (:require [cheshire.core       :as json]
            [clojure.test        :refer :all]
            [datomic.api         :as d]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datomic  :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "mcp-tag-histogram-wire-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

(defn- seed-bearer!
  "Seed a read-only ServiceAccount whose api-key is generated at RUNTIME (no
   committed credential literal); return its Bearer token `<service>:<key>`."
  []
  (let [api-key (str (java.util.UUID/randomUUID))
        svc     :s11-tag-hist-wire-probe
        role    (dt/make :auth/Role
                         {:auth/role-name  auth/read-only-role
                          :auth/role-label "S11 tag-hist-wire read-only"}
                         {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name svc
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name svc) ":" api-key)))

(defn- seed-identless-tags!
  "Transact two :mm/Memory hosts carrying IDENTLESS :mm/Tag ref-slot upserts
   (only :mm.tag/value — the corpus shape).  `alpha` is cited by 2 distinct
   memories, `beta` by 1 — so a correct histogram reports counts {alpha 2,
   beta 1} and :total >= 2."
  []
  @(d/transact (db/conn)
     [{:dt/type :mm/Memory :db/ident :test/wire-hist-m1
       :mm.memory/name "wire-hist-m1" :mm.memory/memory-type :decision
       :mm.memory/tags [{:dt/type :mm/Tag :mm.tag/value "alpha"}
                        {:dt/type :mm/Tag :mm.tag/value "beta"}]}
      {:dt/type :mm/Memory :db/ident :test/wire-hist-m2
       :mm.memory/name "wire-hist-m2" :mm.memory/memory-type :decision
       :mm.memory/tags [{:dt/type :mm/Tag :mm.tag/value "alpha"}]}]))

(deftest tag-histogram-returns-nonzero-buckets-over-the-wire
  (let [bearer (seed-bearer!)
        _      (seed-identless-tags!)
        resp   (response-for service :post "/mcp"
                             :headers {"Content-Type"  "application/json"
                                       "Accept"        "application/json"
                                       "Authorization" (str "Bearer " bearer)}
                             :body (json/generate-string
                                     {:jsonrpc "2.0" :id 1 :method "tools/call"
                                      :params  {:name      "sandbar_aggregate_tag-histogram"
                                                :arguments {}}}))
        body   (json/parse-string (:body resp) true)
        result (:result body)
        ;; tools/call success envelope: {:content [{:type "text" :text <pretty-json>}]}
        payload (some-> result :content first :text (json/parse-string true))
        by-value (into {} (map (juxt :value :count) (:histogram payload)))]
    (testing "tools/call returns HTTP 200 + a JSON-RPC success envelope (not isError)"
      (is (= 200 (:status resp)) (str "expected 200; got " (:status resp) " body=" (:body resp)))
      (is (= "2.0" (:jsonrpc body)))
      (is (some? result))
      (is (nil? (:error body)))
      (is (not (:isError result))
          (str "tag-histogram must not error over the wire; content=" (pr-str (:content result)))))
    (testing "the histogram is NON-EMPTY with :total > 0 over the wire (the Rec-9 fix)"
      (is (some? payload) "tools/call result content must carry the serialized histogram payload")
      (is (pos? (:total payload))
          "wire histogram must enumerate identless tags, NOT collapse to {:histogram [] :total 0}")
      (is (seq (:histogram payload)) "wire histogram bins must be non-empty"))
    (testing "distinct-source counts survive the wire (alpha=2, beta=1)"
      (is (= 2 (get by-value "alpha")) "alpha is referenced by 2 distinct memories")
      (is (= 1 (get by-value "beta"))  "beta is referenced by 1 memory"))
    (testing "identless-but-valued tags surface :mm.tag/value as :tag (canonical fallback)"
      (let [created (filter #(#{"alpha" "beta"} (:value %)) (:histogram payload))]
        (is (= #{"alpha" "beta"} (set (map :tag created)))
            "the value-string fallback (:db/ident → :mm.tag/value → :db/id) rides the wire")))))
