(ns sandbar.reactive.sink-orphan-warn-test
  "it6 create-path fix (b): the reactive fs-projection sink must WARN (not
   silent-debug) when a :first-class memorial reaches transact with NO
   :mm.memory/rel-path — the DB-only-orphan anomaly.  Per
   bugs/entity_create_codec_path_mints_identless_relpathless_entities_fs_-
   projection_silently_skipped_2026_07_08.

   The nil-rel-path skip branch returns BEFORE any `realize-and-emit-entity`
   or filesystem IO, so these tests never touch the corpus.  A `datomic:mem://`
   fixture is required only so `dt/effective-memorial-policy-of` can resolve the
   class policy the sink gates on."
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clojure.tools.logging]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "sink-orphan-warn"}))

(defn- capture-logs
  "Run `thunk` capturing every clojure.tools.logging call as [level message]."
  [thunk]
  (let [entries (atom [])]
    (with-redefs [clojure.tools.logging/log*
                  (fn [_logger level _throwable message]
                    (swap! entries conj [level (str message)]))]
      (thunk))
    @entries))

(deftest sink-warns-on-first-class-no-rel-path
  (testing ":first-class :mm/Memory with nil rel-path → WARN naming the orphan skip"
    (let [entries (capture-logs
                   #(sinks/fs-projection-sink
                     12345
                     ;; NO :mm.memory/rel-path — the orphan shape
                     {:dt/type  :mm/Memory
                      :db/ident :memory.decisions/it6_orphan_probe}))
          warns   (filter (fn [[lvl _]] (= :warn lvl)) entries)]
      (is (seq warns) "a WARN must fire (not a silent debug skip)")
      (is (some (fn [[_ msg]] (str/includes? msg "no-rel-path-first-class-orphan"))
                warns)
          (str "WARN must name the orphan reason; captured: " (pr-str entries))))))

(deftest sink-no-orphan-warn-for-non-first-class-class
  (testing "a non-:first-class class (:mm/Tag, :inline) with no rel-path → routine
            debug skip, NOT the orphan WARN"
    (let [entries (capture-logs
                   #(sinks/fs-projection-sink
                     67890
                     {:dt/type  :mm/Tag
                      :db/ident :mm.tag/it6-probe}))
          warns   (filter (fn [[lvl _]] (= :warn lvl)) entries)]
      (is (not-any? (fn [[_ msg]] (str/includes? msg "no-rel-path-first-class-orphan"))
                    warns)
          (str "no orphan WARN expected for an :inline class; captured: "
               (pr-str entries))))))

(deftest sink-no-orphan-warn-for-runtime-spec-class
  (testing "a runtime-behavioral class (:mm/Schedule, :first-class only by
            inheritance, legitimately rel-path-less) with no rel-path → routine
            debug skip, NOT the orphan WARN — avoids telemetry/schedule noise"
    (let [entries (capture-logs
                   #(sinks/fs-projection-sink
                     24680
                     {:dt/type  :mm/Schedule
                      :db/ident :sandbar.schedule/s-it6-probe}))
          warns   (filter (fn [[lvl _]] (= :warn lvl)) entries)]
      (is (not-any? (fn [[_ msg]] (str/includes? msg "no-rel-path-first-class-orphan"))
                    warns)
          (str "no orphan WARN expected for a :mm/Spec-branch class; captured: "
               (pr-str entries))))))
