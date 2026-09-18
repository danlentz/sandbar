(ns sandbar.search-ancestor-refresh-test
  "Ancestor-class BM25F cache refresh contract (2026-09-18, reliability
  sprint item 1.1).

  Per-class analyzed-entry caches are SUBCLASS-INCLUSIVE at warm
  (`warm-bm25f-cache!` walks `dt/all-instances-of`), so the `:mm/Memory`
  cache holds every `:mm/Decision` / `:mm/Bug` instance.  Before the fix,
  `entity-changed!` refreshed only `[concrete-class eid]`: a memorial
  written after the warm was found by its own class and INVISIBLE to
  `:mm/Memory`-scoped search — the default scope of the recall hook and
  the orientation ceremony — until the next restart.  Confirmed live on
  2026-09-18 by both collaborators (Astra: the recovery briefing present
  in a :mm/Briefing search, absent from all 202 hits of the :mm/Memory
  search; Claude: its own decision memorial ranked first by :mm/Decision,
  absent from :mm/Memory).  Per
  bugs/bm25f_entity_changed_refreshes_concrete_class_index_only_ancestor_-
  class_searches_miss_new_entities_until_restart_2026_09_18.

  Pins:
   (a) after a subclass CREATE + refresh, both the subclass cache and the
       ancestor cache serve the entity;
   (b) after a subclass UPDATE through the real MCP write path (async
       refresh + quiescence barrier), the ancestor cache serves the NEW
       content — no stale text;
   (c) a refresh never creates a PARTIAL cache for a class whose cache is
       not built (Astra's static risk: an `assoc-in` on an unwarmed class
       would later masquerade as the complete corpus);
   (d) removal drops the entity from every cache that held it."
  (:require [clojure.test :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.search :as search]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "search-ancestor-refresh"
                            :auth?     false})
  (fn [t]
    ;; Drain any refresh enqueued by a prior test (the executor is a
    ;; JVM-wide defonce) and start from an empty cache.
    (search/await-bm25f-quiescent!)
    (search/clear-bm25f-cache!)
    (t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk!
  "Create a memorial of `class` via dt/make (Bug/Decision/Memory all
  inherit :mm.memory/*).  Returns the eid."
  [class name body]
  (:db/id (dt/make class {:mm.memory/rel-path (str "test/" name ".md")
                          :mm.memory/name     name
                          :mm.memory/body-raw body})))

(defn- entity-map [eid] (into {:db/id eid} (db/entity eid)))

(defn- hit-eids [query class]
  (set (map :eid (:hits (search/search-bm25f {:query query :class class :limit 0})))))

(defn- warm! [class]
  ;; Any query builds the class's cache lazily (analyzed-corpus-for →
  ;; warm-bm25f-cache!) — the same path the startup sweep primes.
  (search/search-bm25f {:query "seed" :class class :limit 0}))

(defn- call [tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments}))

(defn- success? [response]
  (and (some? (-> response :result :content))
       (not (-> response :result :isError))
       (nil? (:error response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) CREATE after warm: both the subclass and the ancestor cache see it
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest subclass-create-refreshes-ancestor-cache
  (testing "a :mm/Decision written after the :mm/Memory cache is warm is
            visible to BOTH :mm/Decision and :mm/Memory scoped search"
    (mk! :mm/Memory   "seed-mem" "plain seed body")
    (mk! :mm/Decision "seed-dec" "plain seed decision")
    (warm! :mm/Memory)
    (warm! :mm/Decision)
    (is (= #{:mm/Memory :mm/Decision} (search/bm25f-cache-classes))
        "both caches are built before the probe write")
    (is (empty? (hit-eids "quantumfrob" :mm/Memory)) "probe token absent pre-write")
    (let [eid (mk! :mm/Decision "late-dec" "a decision about quantumfrob routing")]
      ;; The sync refresh — exactly what the MCP write path enqueues.
      (search/entity-changed! :mm/Decision (entity-map eid))
      (is (contains? (hit-eids "quantumfrob" :mm/Decision) eid)
          "concrete-class scope sees the new entity")
      (is (contains? (hit-eids "quantumfrob" :mm/Memory) eid)
          "ancestor scope sees the new entity WITHOUT a restart (the 2026-09-18 live symptom)")
      (is (= #{:mm/Memory :mm/Decision} (search/bm25f-cache-classes))
          "no additional (partial) caches were created for unbuilt ancestors"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) UPDATE through the real MCP write path: ancestor cache serves new text
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest subclass-update-refreshes-ancestor-cache-via-mcp-write-path
  (testing "entity.update on a :mm/Bug (async refresh + quiescence barrier)
            makes the NEW body searchable under :mm/Memory scope"
    (mk! :mm/Memory "seed-mem" "plain seed body")
    (let [eid (mk! :mm/Bug "mutable-bug" "plain seed bug body")]
      (warm! :mm/Memory)
      (warm! :mm/Bug)
      (is (empty? (hit-eids "gronkulated" :mm/Memory)) "new token absent pre-update")
      (is (success? (call "sandbar.entity.update"
                          {"entity" eid
                           "slots"  {":mm.memory/body-raw"
                                     "a bug about gronkulated widgets"}})))
      (is (true? (search/await-bm25f-quiescent! 10000))
          "the async refresh drained")
      (is (contains? (hit-eids "gronkulated" :mm/Bug) eid)
          "concrete-class scope serves the updated body")
      (is (contains? (hit-eids "gronkulated" :mm/Memory) eid)
          "ancestor scope serves the updated body (was stale before the fix)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) a refresh never creates a partial cache for an unbuilt class
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest refresh-never-creates-a-partial-cache
  (testing "entity-changed! on a class whose cache is not built leaves it
            unbuilt; the first query then builds the COMPLETE cache"
    (mk! :mm/Memory "seed-mem" "plain seed body")
    (mk! :mm/Bug    "seed-bug" "plain seed bug")
    (is (empty? (search/bm25f-cache-classes)) "clean slate")
    (let [eid (mk! :mm/Bug "late-bug" "a bug about quantumfrob")]
      (search/entity-changed! :mm/Bug (entity-map eid))
      (is (empty? (search/bm25f-cache-classes))
          "no cache was created by the refresh (a one-entry map would masquerade as the corpus)")
      (let [res (search/search-bm25f {:query "bug" :class :mm/Bug :limit 0})]
        (is (= 2 (:total res))
            (str "lazy warm builds the complete :mm/Bug cache (both bugs), got " (:total res)))
        (is (contains? (set (map :eid (:hits res))) eid)
            "the late entity is present in the fully-built cache")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (d) removal drops the entity from every cache that held it
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest removal-drops-entity-from-ancestor-cache
  (testing "entity-removed! drops the eid from the subclass AND ancestor caches"
    (mk! :mm/Memory "seed-mem" "plain seed body")
    (let [eid (mk! :mm/Decision "doomed-dec" "a decision about quantumfrob")]
      (is (contains? (hit-eids "quantumfrob" :mm/Memory) eid) "served by :mm/Memory pre-removal")
      (is (contains? (hit-eids "quantumfrob" :mm/Decision) eid) "served by :mm/Decision pre-removal")
      (search/entity-removed! :mm/Decision eid)
      (is (not (contains? (hit-eids "quantumfrob" :mm/Memory) eid))
          "ancestor cache no longer serves the removed entity")
      (is (not (contains? (hit-eids "quantumfrob" :mm/Decision) eid))
          "concrete-class cache no longer serves the removed entity"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cache-classes-for: the helper's contract in isolation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cache-classes-for-lists-only-built-caches
  (testing "the concrete class and its built ancestors, nearest-first; unbuilt
            classes are excluded"
    (mk! :mm/Memory   "seed-mem" "plain seed body")
    (mk! :mm/Decision "seed-dec" "plain seed decision")
    (is (= [] (search/cache-classes-for :mm/Decision)) "nothing built yet")
    (warm! :mm/Memory)
    (is (= [:mm/Memory] (search/cache-classes-for :mm/Decision))
        "only the built ancestor")
    (warm! :mm/Decision)
    (is (= [:mm/Decision :mm/Memory]
           (filterv #{:mm/Decision :mm/Memory} (search/cache-classes-for :mm/Decision)))
        "concrete class first, then the ancestor")))
