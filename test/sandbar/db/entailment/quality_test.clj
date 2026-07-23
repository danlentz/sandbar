(ns sandbar.db.entailment.quality-test
  "Tests for β.2.0 pre-work — S.3 entailment cost benchmark + S.8 DAG cycle
   detection.

   Per `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_…`-style
   ratification cadence: Q.B.0.a (latency threshold) + Q.B.0.b (cycle policy
   loud-fail).  Tests verify the validate-entailment-graph! loud-fail behavior
   + the benchmark return-shape.

   Per `/Users/dan/.claude/plans/wise-splashing-stardust.md` §3 stage β.2.0."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.db.entailment.quality :as ent-q]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "ent-quality-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S.8 — DAG cycle detection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest detect-cycles-finds-no-cycle-in-clean-substrate
  (testing "detect-cycles returns nil cycles for a substrate loaded from clean schema"
    (let [report (ent-q/detect-cycles (db/db))]
      (is (nil? (:subclass-cycle report))
          "Clean substrate has no :dt/subclass-of cycle")
      (is (nil? (:subproperty-cycle report))
          "Clean substrate has no :dt/subproperty-of cycle")
      (is (pos? (:subclass-edge-count report))
          "Substrate has some :dt/subclass-of edges")
      (is (number? (:subproperty-edge-count report))))))

(deftest validate-entailment-graph-passes-clean-substrate
  (testing "validate-entailment-graph! returns :valid? true for clean substrate; doesn't throw"
    (let [report (ent-q/validate-entailment-graph! (db/db))]
      (is (true? (:valid? report)))
      (is (number? (:subclass-edge-count report)))
      (is (number? (:subproperty-edge-count report))))))

(deftest detect-cycles-detects-injected-subclass-cycle
  (testing "Cycle injected in :dt/subclass-of graph IS detected"
    ;; Inject a deliberate cycle: A → B → C → A
    (let [conn (db/conn)]
      @(d/transact conn
                   [{:db/ident :test.cycle/class-a :dt/type :dt/Class}
                    {:db/ident :test.cycle/class-b :dt/type :dt/Class}
                    {:db/ident :test.cycle/class-c :dt/type :dt/Class}])
      @(d/transact conn
                   [{:db/ident :test.cycle/class-a :dt/subclass-of :test.cycle/class-b}
                    {:db/ident :test.cycle/class-b :dt/subclass-of :test.cycle/class-c}
                    {:db/ident :test.cycle/class-c :dt/subclass-of :test.cycle/class-a}])
      (let [report (ent-q/detect-cycles (db/db))]
        (is (some? (:subclass-cycle report))
            "Cycle should be detected in :dt/subclass-of graph")
        (is (vector? (:subclass-cycle report)))
        (is (>= (count (:subclass-cycle report)) 2)
            "Cycle path has at least 2 entries (closing on itself)")))))

(deftest validate-entailment-graph-throws-on-cycle
  (testing "validate-entailment-graph! THROWS on injected subclass cycle (Q.B.0.b loud-fail)"
    (let [conn (db/conn)]
      @(d/transact conn
                   [{:db/ident :test.cycle2/x :dt/type :dt/Class}
                    {:db/ident :test.cycle2/y :dt/type :dt/Class}])
      @(d/transact conn
                   [{:db/ident :test.cycle2/x :dt/subclass-of :test.cycle2/y}
                    {:db/ident :test.cycle2/y :dt/subclass-of :test.cycle2/x}])
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"cycle"
                            (ent-q/validate-entailment-graph! (db/db)))
          "Loud-fail per Q.B.0.b ratification"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S.3 — Entailment cost benchmark
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest run-benchmark-returns-structured-report
  (testing "run-benchmark returns the latency report shape"
    (let [report (ent-q/run-benchmark {:db (db/db) :iterations 5})]
      (is (map? report))
      (is (map? (:rule-reports report)))
      (is (contains? (:rule-reports report) :rdfs9-isa))
      (is (number? (:total-duration-ms report)))
      (is (instance? java.util.Date (:benchmark-instant report)))
      (is (= 5 (:iterations report)))
      ;; Verify per-rule latency stats shape
      (let [rdfs9-rep (get-in report [:rule-reports :rdfs9-isa])
            ground-stats (:ground rdfs9-rep)
            entailed-stats (:entailed rdfs9-rep)]
        (is (number? (:median-ms ground-stats)))
        (is (number? (:p95-ms ground-stats)))
        (is (number? (:median-ms entailed-stats)))
        (is (number? (:p95-ms entailed-stats)))))))

(deftest run-benchmark-requires-db
  (testing "run-benchmark without :db throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"requires :db"
                          (ent-q/run-benchmark {})))))
