(ns sandbar.search-async-refresh-test
  "Async BM25F index-refresh contract (2026-07-07, arc/bm25f-async-index).

  The MCP write handlers (entity.create / entity.update / tag.define)
  historically ran `search/entity-changed!` INLINE on the write-response
  path — ~144ms for a 6.5KB body, scaling with body size, the dominant
  per-write cost and the root cause of MCP-client 'Request timed out' on
  larger writes (per decisions/mcp_write_timeout_working_theory_bm25f_-
  refresh_on_write_path_verify_readiness_workaround_2026_07_07).

  This suite pins the replacement contract:

   (a) a write returns WITHOUT running the refresh inline — the refresh
       is enqueued onto the dedicated single-thread executor
   (b) after `await-bm25f-quiescent!` (the sync-flush hook) the index
       reflects the new/updated entity
   (c) an async-refresh failure does NOT fail the write (logged +
       counted, never surfaced)
   (d) create-then-search is deterministic — both via the explicit flush
       hook and via the read barrier inside the MCP search.bm25f handler
       (which also covers tag.lookup: same barrier call).

  Uses :mm/Memory (in required-schema, carries :dt/bm25f-weights); the
  :mm/Tag schema is not loadable under the bare test fixture (pre-
  existing baseline gap — the tools-db-test tag suite fails identically
  at HEAD when run via :only)."
  (:require [cheshire.core     :as json]
            [clojure.test      :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.tools :as tools]
            [sandbar.search    :as search]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "search-async-refresh-test"
                            :auth?     false})
  (fn [t]
    ;; Drain any refresh enqueued by a prior test (the executor is a
    ;; JVM-wide defonce) and start from an empty cache.
    (search/await-bm25f-quiescent!)
    (search/clear-bm25f-cache!)
    (t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers (mirrors sandbar.mcp.tools-db-test)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- call
  [tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments}))

(defn- success?
  [response]
  (and (some? (-> response :result :content))
       (not (-> response :result :isError))
       (nil? (:error response))))

(defn- mk-mem!
  "Create an :mm/Memory via dt/make (the store path needs schema slots
  the bare test fixture lacks — same pattern as search-test's helper).
  Returns {:eid <id>}."
  [nm rel & [slots]]
  (let [ent (dt/make :mm/Memory
                     (merge {:mm.memory/name nm
                             :mm.memory/rel-path rel}
                            slots))]
    {:eid (:db/id ent)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) write returns without running the refresh inline
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest write-returns-before-refresh-runs
  (testing "entity.update returns while the refresh worker is parked —
            the entity-changed! hook is enqueued, never run inline"
    (let [{:keys [eid]} (mk-mem! "async probe" "test/async-refresh-probe"
                                   {:mm.memory/body-raw "initial body"})
          gate (promise)
          ran  (atom [])]
      (with-redefs [search/entity-changed!
                    (fn [class entity-map]
                      @gate ; park until the test releases the worker
                      (swap! ran conj [class (:db/id entity-map)]))]
        ;; Park the single worker thread behind a gate job.
        (search/entity-changed-async! :mm/Memory {:db/id -1})
        ;; The write must return NOW, with zero refreshes having run.
        (let [resp (call "sandbar.entity.update"
                         {"entity" eid
                          "slots"  {":mm.memory/body-raw"
                                    "updated body while worker parked"}})]
          (is (success? resp)
              "write succeeds while its refresh is still queued")
          (is (empty? @ran)
              "entity-changed! must NOT have run inline on the write path"))
        ;; Release the worker; both queued refreshes drain in FIFO order.
        (deliver gate :go)
        (is (true? (search/await-bm25f-quiescent! 10000))
            "queue drains after the gate opens")
        (is (= 2 (count @ran))
            "both the gate job and the write's refresh ran on the worker")
        (is (= :mm/Memory (ffirst @ran)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) sync-flush hook makes write-then-search deterministic
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest await-quiescent-makes-write-then-search-deterministic
  (testing "after await-bm25f-quiescent! a direct search-bm25f reflects
            the update even against a pre-warmed (pinned) cache"
    (let [{:keys [eid]} (mk-mem! "flush probe" "test/flush-probe"
                                   {:mm.memory/body-raw "plain seed body"})]
      ;; Pin the :mm/Memory cache so the lazy-warm-on-miss fallback
      ;; can't mask a stale-cache race.
      (is (pos? (:total (search/search-bm25f {:query "seed" :class :mm/Memory
                                              :limit 5})))
          "cache warm + serving before the probe write")
      (is (zero? (:total (search/search-bm25f {:query "zorbtron"
                                               :class :mm/Memory :limit 5})))
          "probe token absent pre-write")
      ;; The probe write (MCP handler → async enqueue) + flush + search.
      (is (success? (call "sandbar.entity.update"
                          {"entity" eid
                           "slots"  {":mm.memory/body-raw"
                                     "a body about zorbtron dynamics"}})))
      (is (true? (search/await-bm25f-quiescent! 10000))
          "sync-flush hook reports quiescence")
      (is (pos? (:total (search/search-bm25f {:query "zorbtron"
                                              :class :mm/Memory :limit 5})))
          "post-flush search sees the updated body (index caught up)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (d) the MCP search.bm25f handler's read barrier covers write-then-read
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest search-handler-read-barrier-preserves-write-then-read
  (testing "an MCP search issued immediately after an MCP write sees the
            write even when the refresh is artificially slow — the read
            handler awaits quiescence (same barrier as tag.lookup)"
    (let [{:keys [eid]} (mk-mem! "barrier probe" "test/barrier-probe"
                                   {:mm.memory/body-raw "plain seed body"})]
      ;; Pin the cache (same masking concern as above).
      (search/search-bm25f {:query "seed" :class :mm/Memory :limit 5})
      (let [orig search/entity-changed!]
        (with-redefs [search/entity-changed!
                      (fn [class entity-map]
                        (Thread/sleep 400) ; force a visible refresh lag
                        (orig class entity-map))]
          (is (success? (call "sandbar.entity.update"
                              {"entity" eid
                               "slots"  {":mm.memory/body-raw"
                                         "a body about gronkulated widgets"}})))
          ;; NO explicit flush — the handler's own barrier must cover it.
          (let [resp    (call "sandbar.search.bm25f"
                              {"query" "gronkulated"
                               "class" ":mm/Memory"})
                payload (-> resp :result :content first :text
                            (json/parse-string true))]
            (is (success? resp))
            (is (pos? (:total payload))
                "read-your-writes across the MCP surface (read barrier)")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) refresh failure never fails (or follows) the write
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest refresh-failure-does-not-fail-write
  (testing "a throwing refresh is logged + counted; the write succeeds
            and the queue stays healthy"
    (let [{:keys [eid]} (mk-mem! "doomed probe" "test/doomed-refresh-probe"
                                   {:mm.memory/body-raw "initial"})
          failed-before   (:failed @search/bm25f-refresh-stats)]
      (with-redefs [search/entity-changed!
                    (fn [_ _]
                      (throw (ex-info "boom — simulated refresh failure" {})))]
        (let [resp (call "sandbar.entity.update"
                         {"entity" eid
                          "slots"  {":mm.memory/body-raw" "post-failure body"}})]
          (is (success? resp)
              "write succeeds even though its refresh job will throw"))
        (is (true? (search/await-bm25f-quiescent! 10000))
            "a failed job does not wedge the worker"))
      (is (< failed-before (:failed @search/bm25f-refresh-stats))
          "failure was counted in bm25f-refresh-stats (and logged)"))))
