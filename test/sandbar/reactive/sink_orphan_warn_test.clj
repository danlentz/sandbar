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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; it7 FF-2 — class-level coverage.  :mm/Log / :mm/Fn / :mm/Workflow ARE corpus
;; documents (Log re-parented under :mm/Activity; Fn/Workflow under :mm/Spec) that
;; the it6 root-ancestry gate SILENTLY EXCLUDED.  The sink must now orphan-WARN on
;; their rel-path-less skip; the true runtime classes (:mm/EventLog / :mm/Event)
;; must stay a routine debug skip.  it6 BOARD-MINUTE Lane-B fast-follow #1.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- warns-orphan?
  "True iff running the sink for `entity` (a synthetic post-tx-slots map with NO
   rel-path) fires the `no-rel-path-first-class-orphan` WARN."
  [eid entity]
  (->> (capture-logs #(sinks/fs-projection-sink eid entity))
       (some (fn [[lvl msg]] (and (= :warn lvl)
                                  (str/includes? msg "no-rel-path-first-class-orphan"))))
       boolean))

(deftest sink-warns-on-corpus-document-runtime-classes
  (testing "FF-2: :mm/Log / :mm/Fn / :mm/Workflow (corpus documents under runtime
            roots) with no rel-path → orphan WARN, now that class-level gating
            covers them (was a silent debug skip under the it6 root-ancestry gate)"
    (is (warns-orphan? 111 {:dt/type :mm/Log      :db/ident :memory.logs/ff2_probe})
        ":mm/Log (Activity) must orphan-WARN on rel-path-less skip")
    (is (warns-orphan? 222 {:dt/type :mm/Fn       :db/ident :memory.fns/ff2_probe})
        ":mm/Fn (Spec) must orphan-WARN on rel-path-less skip")
    (is (warns-orphan? 333 {:dt/type :mm/Workflow :db/ident :memory.workflows/ff2_probe})
        ":mm/Workflow (Spec) must orphan-WARN on rel-path-less skip")))

(deftest sink-no-orphan-warn-for-runtime-activity-event-classes
  (testing "FF-2 CAREFUL: the true runtime event/activity classes (:mm/EventLog
            telemetry, :mm/Event bus primitive) stay a routine debug skip — NOT
            the orphan WARN — so the monotone override adds no telemetry noise"
    (is (not (warns-orphan? 444 {:dt/type  :mm/EventLog
                                 :db/ident :memory.event-logs/ff2_probe}))
        ":mm/EventLog (Activity telemetry) must NOT orphan-WARN")
    (is (not (warns-orphan? 555 {:dt/type  :mm/Event
                                 :db/ident :event/ff2_probe}))
        ":mm/Event (bus primitive) must NOT orphan-WARN")))
