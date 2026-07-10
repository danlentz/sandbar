(ns sandbar.search-class-scope-test
  "Regression suite for the `search.bm25f` `:class`-scope silent-widening
  defect cluster (2026-07-10 corpus-review board — P0, GATES-0.2.0).

  ROOT CAUSE (verified empirically before the fix): the per-class BM25F
  analyzed-entry cache was polluted with foreign-class entries by the
  transitive invalidation path (`entity-changed!` → `referencing-eids`),
  whose Datalog walk `[?e ?slot ?target]` lacked an instance-of constraint.
  Because memorial subclasses (`:mm/Verb`/`:mm/Bug`/`:mm/Plan`/`:mm/Decision`)
  INHERIT the `:mm.memory/tags` + `:mm.memory/themes` ref-slots from
  `:mm/Memory`, a single `:mm/Tag` change wrote every tag-sharing memorial
  into (e.g.) the `:mm/Verb` cache — so a `:mm/Verb`-scoped query returned
  `:mm/Plan` / `:mm/Decision` hits and a corpus-wide `:total`.  `:mm/Tag`
  was immune only because its weight slots are all string-valued (never a
  dependent class).

  FIX (two layers):
   1. `referencing-eids` now joins `(instance-of ?dep-class ?e)` — the cache
      stays a faithful per-class instance index (root cause).
   2. `search-bm25f-single` ALWAYS intersects the scoring corpus with the
      authoritative `class-instance-eids` set — a fail-closed read boundary
      independent of cache state (board's preferred candidate-assembly
      instance-filter).

  Per bugs/search_bm25f_class_scoping_silently_degrades_to_corpus_wide_for_memorial_classes_2026_07_10.md
      bugs/tools_search_axisless_returns_corpus_memorials_as_null_verb_cards_2026_07_10.md
      bugs/search_bm25f_wire_schema_requires_query_only_server_demands_class_2026_07_10.md."
  (:require [clojure.test :refer :all]
            [sandbar.search :as search]
            [sandbar.search.bm25f :as bm25f]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.test-util :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "search-class-scope"})
  (fn [t] (search/clear-bm25f-cache!) (t)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seed helpers — memorials tagged with a shared :mm/Tag are the pollution
;; vector; verbs/tags are the scoped classes.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mk-tag! [value definition]
  (dt/make :mm/Tag {:mm.tag/value value :mm.tag/definition definition}))

(defn- mk-tagged! [class name body tag-eid]
  ;; Bug/Plan/Decision/Memory all inherit :mm.memory/* (incl. :mm.memory/tags).
  (dt/make class {:mm.memory/rel-path (str "test/" name ".md")
                  :mm.memory/name     name
                  :mm.memory/body-raw body
                  :mm.memory/tags     [tag-eid]}))

(defn- mk-verb! [name title when-txt]
  (dt/make :mm/Verb {:mm.verb/name name :mm.verb/title title :mm.verb/when when-txt}))

(defn- fire-tag-invalidation!
  "Reproduce the live write-path event: a :mm/Tag mutation runs
  entity-changed!'s transitive invalidation (the pre-fix pollution vector)."
  [tag]
  (let [eid (:db/id tag)]
    (search/entity-changed! :mm/Tag (into {:db/id eid} (db/entity eid)))))

(defn- hit-types [result]
  (frequencies (map #(get-in % [:entity :dt/type]) (:hits result))))

(defn- catalog-handler [wire-name]
  (:handler (first (filter #(= wire-name (:name %)) tools/verb-catalog))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T1 — ROOT CAUSE: transitive tag-invalidation must not widen :mm/Verb scope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t1-tag-invalidation-does-not-pollute-verb-scope
  (testing "a :mm/Tag change must NOT leak tag-sharing memorials into :mm/Verb scope"
    (let [tag (mk-tag! "shared" "shared widget tag")
          teid (:db/id tag)]
      (dotimes [i 6] (mk-tagged! :mm/Memory   (str "mem" i) (str "widget base " i) teid))
      (dotimes [i 5] (mk-tagged! :mm/Bug      (str "bug" i) (str "widget defect " i) teid))
      (dotimes [i 4] (mk-tagged! :mm/Decision (str "dec" i) (str "widget choice " i) teid))
      (dotimes [i 3] (mk-verb! (str "sandbar.v" i) (str "widget verb " i) "when widget"))
      (search/clear-bm25f-cache!)
      ;; Warm + confirm clean baseline.
      (is (= 3 (:total (search/search-bm25f {:query "widget" :class :mm/Verb :limit 0})))
          "baseline: exactly 3 verbs match 'widget'")
      ;; Fire the transitive-invalidation event that pre-fix polluted the cache.
      (fire-tag-invalidation! tag)
      (let [after (search/search-bm25f {:query "widget" :class :mm/Verb :limit 0})]
        (is (= 3 (:total after))
            (str "REGRESSION: :mm/Verb scope widened after tag invalidation — got "
                 (:total after) " / " (hit-types after)))
        (is (= #{:mm/Verb} (set (map #(get-in % [:entity :dt/type]) (:hits after))))
            "every hit is a :mm/Verb — no :mm/Bug / :mm/Decision / :mm/Memory leakage")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T2 — SUBCLASS SCOPING: each memorial subclass scopes to its own instances
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t2-memorial-subclass-scopes-to-own-instances
  (testing "sibling memorial subclasses do not bleed into each other"
    (let [tag (mk-tag! "shared" "shared widget tag")
          teid (:db/id tag)]
      (dotimes [i 6] (mk-tagged! :mm/Memory   (str "mem" i) (str "widget base " i) teid))
      (dotimes [i 5] (mk-tagged! :mm/Bug      (str "bug" i) (str "widget defect " i) teid))
      (dotimes [i 4] (mk-tagged! :mm/Decision (str "dec" i) (str "widget choice " i) teid))
      (search/clear-bm25f-cache!)
      (fire-tag-invalidation! tag)
      (let [bugs (search/search-bm25f {:query "widget" :class :mm/Bug :limit 0})
            decs (search/search-bm25f {:query "widget" :class :mm/Decision :limit 0})]
        (is (= 5 (:total bugs)) "only the 5 :mm/Bug instances")
        (is (= #{:mm/Bug} (set (map #(get-in % [:entity :dt/type]) (:hits bugs)))))
        (is (= 4 (:total decs)) "only the 4 :mm/Decision instances")
        (is (= #{:mm/Decision} (set (map #(get-in % [:entity :dt/type]) (:hits decs)))))))))

(deftest t2b-read-boundary-guard-excludes-injected-foreign-entry
  (testing "candidate-assembly instance-filter drops a foreign entry even if a
            cache path re-pollutes (Fix 2 is load-bearing defense)"
    (let [m (dt/make :mm/Memory {:mm.memory/rel-path "test/foreign.md"
                                 :mm.memory/name "foreign"
                                 :mm.memory/body-raw "widget foreign memory"})
          meid (:db/id m)]
      (mk-verb! "sandbar.real" "widget real verb" "when widget")
      (search/clear-bm25f-cache!)
      ;; Warm :mm/Verb, then FORCE a foreign :mm/Memory entry into the :mm/Verb
      ;; cache (simulating any future re-pollution vector) + drop stale stats.
      (search/search-bm25f {:query "widget" :class :mm/Verb :limit 0})
      (swap! @#'search/bm25f-entry-cache assoc-in [:mm/Verb meid]
             (bm25f/analyze-entity :mm/Verb (db/entity meid)))
      (swap! @#'search/bm25f-stats-cache dissoc :mm/Verb)
      (let [after (search/search-bm25f {:query "widget" :class :mm/Verb :limit 0})]
        (is (not (contains? (set (map :eid (:hits after))) meid))
            "the injected :mm/Memory eid must be filtered out of :mm/Verb results")
        (is (= #{:mm/Verb} (set (map #(get-in % [:entity :dt/type]) (:hits after))))
            "read boundary fail-closes to instances-of :mm/Verb only")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T3 — BASE-CLASS query stays SUBCLASS-INCLUSIVE (fix must not over-restrict)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t3-base-class-query-is-subclass-inclusive
  (testing ":mm/Memory scope includes subclass (:mm/Bug / :mm/Decision) instances"
    (let [tag (mk-tag! "shared" "shared widget tag")
          teid (:db/id tag)]
      (dotimes [i 6] (mk-tagged! :mm/Memory   (str "mem" i) (str "widget base " i) teid))
      (dotimes [i 5] (mk-tagged! :mm/Bug      (str "bug" i) (str "widget defect " i) teid))
      (dotimes [i 4] (mk-tagged! :mm/Decision (str "dec" i) (str "widget choice " i) teid))
      (search/clear-bm25f-cache!)
      (fire-tag-invalidation! tag)
      (let [mem (search/search-bm25f {:query "widget" :class :mm/Memory :limit 0})
            types (hit-types mem)]
        (is (= 15 (:total mem)) "6 base + 5 Bug + 4 Decision are all instances-of :mm/Memory")
        (is (= #{:mm/Memory :mm/Bug :mm/Decision} (set (keys types)))
            (str "subclass-inclusive: base + subclasses present; got " types))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T4 — MULTI-CLASS (D7) semantics preserved AND scoped
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t4-multiclass-vec-scopes-each-member
  (testing "[:mm/Verb :mm/Tag] returns hits from BOTH, each correctly scoped,
            even after the tag-invalidation pollution vector fires"
    (let [tag (mk-tag! "widget-tag" "widget tag definition")
          teid (:db/id tag)]
      (dotimes [i 5] (mk-tagged! :mm/Bug (str "bug" i) (str "widget defect " i) teid))
      (dotimes [i 3] (mk-verb! (str "sandbar.v" i) (str "widget verb " i) "when widget"))
      (search/clear-bm25f-cache!)
      (fire-tag-invalidation! tag)
      (let [res   (search/search-bm25f {:query "widget" :class [:mm/Verb :mm/Tag] :limit 0})
            types (set (keys (hit-types res)))]
        ;; 3 verbs + 1 tag (the "widget-tag") match; the 5 Bugs must NOT appear.
        (is (= #{:mm/Verb :mm/Tag} types)
            (str "multi-class union spans exactly its members; got " (hit-types res)))
        (is (not (contains? types :mm/Bug))
            "the 5 tag-sharing :mm/Bug memorials must NOT leak into the [:mm/Verb :mm/Tag] union")
        (is (= 4 (:total res)) "3 verbs + 1 matching tag")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T5 — tools.search DISCOVERY MODE (the axis-less bug) — verb cards only
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t5-tools-search-discovery-returns-only-verb-cards
  (testing "tools.search (axis-less) returns real verb cards — never null-card
            corpus-memorial residue"
    (let [handler (catalog-handler "sandbar.tools.search")
          tag (mk-tag! "widget-tag" "widget tag definition")
          teid (:db/id tag)]
      (is (fn? handler) "sandbar.tools.search handler resolves from the catalog")
      (dotimes [i 5] (mk-tagged! :mm/Bug (str "bug" i) (str "widget defect " i) teid))
      (dotimes [i 3] (dt/make :mm/Verb {:db/ident      (keyword "sandbar.widget" (str "v" i))
                                        :mm.verb/name  (str "sandbar.widget.v" i)
                                        :mm.verb/title (str "widget verb " i)
                                        :mm.verb/when  "when you widget"
                                        :mm.verb/axis  :meta}))
      (search/clear-bm25f-cache!)
      (fire-tag-invalidation! tag)
      (testing "axis-less discovery mode"
        (let [res (handler {:query "widget" :limit 20})]
          (is (= 3 (:total res)) (str "only the 3 verbs match; got " (:total res)))
          (is (every? :verb (:matches res))
              "every match card has a non-nil :verb (no null-card residue)")
          (is (every? #(some? (:ident %)) (:matches res))
              "every card carries an :ident (not an anonymous null row)")))
      (testing "axis-filtered mode still scopes to verbs"
        (let [res (handler {:query "widget" :axis "meta" :limit 20})]
          (is (every? :verb (:matches res))
              "axis-filtered discovery also returns only verb cards"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T6 — WIRE-SCHEMA CONTRACT: schema and server agree (both require :class)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T5b — the flagship `sandbar.search.bm25f` MCP verb end-to-end (smi:1)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t5b-search-bm25f-verb-handler-scopes-to-class
  (testing "the wire `sandbar.search.bm25f` verb (metadata-only) fail-closes to
            instances-of :class after the tag-invalidation pollution vector"
    (let [handler (catalog-handler "sandbar.search.bm25f")
          tag (mk-tag! "shared" "shared widget tag")
          teid (:db/id tag)]
      (dotimes [i 5] (mk-tagged! :mm/Bug (str "bug" i) (str "widget defect " i) teid))
      (dotimes [i 3] (mk-verb! (str "sandbar.v" i) (str "widget verb " i) "when widget"))
      (search/clear-bm25f-cache!)
      (fire-tag-invalidation! tag)
      (let [res (handler {"query" "widget" "class" ":mm/Verb" "limit" 0})]
        (is (= 3 (:total res)) (str "wire verb returns only the 3 verbs; got " (:total res)))
        (is (= #{:mm/Verb} (set (map #(get-in % [:entity :dt/type]) (:hits res))))
            "no :mm/Bug leakage through the MCP boundary")))))

(deftest t6-wire-schema-and-server-agree-on-required-class
  (testing "the published inputSchema and the server handler agree: :class is REQUIRED"
    (let [entry    (first (filter #(= "sandbar.search.bm25f" (:name %)) tools/verb-catalog))
          required (set (get-in entry [:inputSchema :required]))]
      (testing "published wire schema declares BOTH query and class required"
        (is (contains? required "query") "inputSchema :required must include 'query'")
        (is (contains? required "class")
            (str "inputSchema :required must include 'class' (schema<->server contract); got " required)))
      (testing "server handler rejects a query-only call (matching the schema)"
        (let [handler (:handler entry)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)class"
                (handler {:query "anything"}))
              "a classless call is rejected — server demand matches the required-schema"))))))
