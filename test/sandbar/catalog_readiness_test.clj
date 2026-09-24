(ns sandbar.catalog-readiness-test
  "The persisted verb catalog's readiness at startup (2026-09-20, from the
   failed HTTP rehearsal 15294480403161084913: a fresh store advertised the
   discovery verbs through tools/list and the initialize instructions, then
   answered no matches and a miss for every verb, because nothing on the
   start path seeds `:mm/Verb`).

   The contract under test: a fresh store is reported EMPTY with an
   actionable warning naming the stopped-server seed; a seeded store is
   reported PRESENT by count only, never as parity; a store missing a card
   is a COUNT MISMATCH; a read failure is reported and the server continues.
   Nothing here seeds or reads the live store — every database is a fresh
   in-memory one, and `start` runs against a stub system without a port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.logging :as log]
            [clojure.tools.logging.impl :as impl]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [sandbar.core :as core]
            [sandbar.db.datomic :as db]
            [sandbar.gate.db :as gdb]
            [sandbar.mcp.tools :as tools]
            [sandbar.reactive :as reactive]
            [sandbar.reactive.queue :as queue]
            [sandbar.scripts.seed-verb-catalog :as verb-seed]
            [sandbar.sys :as sys]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn- with-captured-log
  "Run `body-fn` while every clojure.tools.logging call on this thread is
   captured as `{:level :message}` (the idiom of projection_test and
   mcp/notifications_test).  Returns `[result captured]`."
  [body-fn]
  (let [captured (atom [])
        factory  (reify impl/LoggerFactory
                   (name [_] "captured")
                   (get-logger [_ _logger-ns]
                     (reify impl/Logger
                       (enabled? [_ _level] true)
                       (write! [_ level _throwable message]
                         (swap! captured conj {:level level :message (str message)})))))]
    (binding [log/*logger-factory* factory]
      [(body-fn) @captured])))

(defn- at-level [captured level]
  (filterv #(= level (:level %)) captured))

(defn- query-failure [& _]
  (throw (ex-info "fixture: the catalog query failed" {:fixture true})))

;;; ---------------------------------------------------------------------------
;;; verb-catalog-readiness — the read
;;; ---------------------------------------------------------------------------

(deftest a-fresh-store-reports-an-empty-catalog
  (gdb/with-fresh-db* {:name "catalog-readiness-empty"}
    (fn []
      (let [r (core/verb-catalog-readiness (db/db))]
        (is (= :empty (:status r)))
        (is (= 0 (:persisted r)))
        (is (= (count tools/verb-catalog) (:source r))
            "the source side is the in-process catalog tools/list serves")
        (is (pos? (:source r)))))))

(deftest a-seeded-store-reports-the-catalog-present-by-count-only
  (gdb/with-fresh-db* {:name "catalog-readiness-seeded"}
    (fn []
      (let [seeded (verb-seed/seed!)
            r      (core/verb-catalog-readiness (db/db))]
        (is (= (count tools/verb-catalog) (:seeded seeded))
            "the existing seed script persists one card per source verb")
        (is (= :present (:status r)))
        (is (= (:source r) (:persisted r)))
        (testing "the readiness map claims a count and nothing more"
          (is (= #{:status :persisted :source} (set (keys r)))))))))

(deftest a-store-missing-a-card-is-a-count-mismatch
  ;; A store seeded before a verb was added to source: the discovery verbs
  ;; answer from cards that no longer match this checkout.
  (gdb/with-fresh-db* {:name "catalog-readiness-mismatch"}
    (fn []
      (verb-seed/seed!)
      (let [verb-name (:name (first tools/verb-catalog))
            eid       (d/q '[:find ?e . :in $ ?n :where [?e :mm.verb/name ?n]] (db/db) verb-name)]
        (is (some? eid) "the first source verb was seeded")
        @(d/transact (db/conn) [[:db/retract eid :mm.verb/name verb-name]])
        (let [r (core/verb-catalog-readiness (db/db))]
          (is (= :count-mismatch (:status r)))
          (is (= (dec (:source r)) (:persisted r))))))))

(deftest a-read-failure-is-reported-and-not-thrown
  (with-redefs [d/q query-failure]
    (let [r (core/verb-catalog-readiness nil)]
      (is (= :error (:status r)))
      (is (nil? (:persisted r)))
      (is (= (count tools/verb-catalog) (:source r)))
      (is (= "fixture: the catalog query failed" (:error r))))))

;;; ---------------------------------------------------------------------------
;;; check-verb-catalog! — the startup log line
;;; ---------------------------------------------------------------------------

(deftest the-startup-check-warns-on-an-empty-catalog-with-the-remedy
  (gdb/with-fresh-db* {:name "catalog-readiness-warn"}
    (fn []
      (let [[r captured] (with-captured-log core/check-verb-catalog!)
            warns        (at-level captured :warn)]
        (is (= :empty (:status r)) "the readiness map is returned")
        (is (= 1 (count warns)) "exactly one warning")
        (let [msg (:message (first warns))]
          (is (str/includes? msg "VERB-CATALOG-EMPTY"))
          (testing "the effect is named"
            (is (str/includes? msg "sandbar.tools.search"))
            (is (str/includes? msg "sandbar.tools.describe")))
          (testing "the remedy is the stopped-server seed, not a startup mutation"
            (is (str/includes? msg "stop the server"))
            (is (str/includes? msg "lein seed-verb-catalog"))
            (is (str/includes? msg "doc/operations.md"))))
        (is (= :empty (:status (core/verb-catalog-readiness (db/db))))
            "the check seeded nothing")))))

(deftest the-startup-check-reports-a-seeded-catalog-as-a-count-not-parity
  (gdb/with-fresh-db* {:name "catalog-readiness-info"}
    (fn []
      (verb-seed/seed!)
      (let [[r captured] (with-captured-log core/check-verb-catalog!)
            infos        (at-level captured :info)]
        (is (= :present (:status r)))
        (is (empty? (at-level captured :warn)) "a present catalog is not a warning")
        (is (= 1 (count infos)))
        (let [msg (:message (first infos))]
          (is (str/includes? msg "VERB-CATALOG-PRESENT"))
          (is (str/includes? msg "count only")
              "an equal count is reported as a count, never as parity or freshness"))))))

(deftest the-startup-check-warns-on-a-count-mismatch
  (gdb/with-fresh-db* {:name "catalog-readiness-mismatch-warn"}
    (fn []
      (verb-seed/seed!)
      (let [verb-name (:name (first tools/verb-catalog))
            eid       (d/q '[:find ?e . :in $ ?n :where [?e :mm.verb/name ?n]] (db/db) verb-name)]
        @(d/transact (db/conn) [[:db/retract eid :mm.verb/name verb-name]]))
      (let [[r captured] (with-captured-log core/check-verb-catalog!)
            warns        (at-level captured :warn)]
        (is (= :count-mismatch (:status r)))
        (is (= 1 (count warns)))
        (is (str/includes? (:message (first warns)) "VERB-CATALOG-COUNT-MISMATCH"))
        (is (str/includes? (:message (first warns)) "lein seed-verb-catalog"))))))

(deftest the-startup-check-warns-on-a-read-failure-and-continues
  (gdb/with-fresh-db* {:name "catalog-readiness-error"}
    (fn []
      (with-redefs [d/q query-failure]
        (let [[r captured] (with-captured-log core/check-verb-catalog!)
              warns        (at-level captured :warn)]
          (is (= :error (:status r)))
          (is (= "fixture: the catalog query failed" (:error r)))
          (is (= 1 (count warns)))
          (is (str/includes? (:message (first warns)) "VERB-CATALOG-CHECK-FAILED"))
          (is (str/includes? (:message (first warns)) "continues")))))))

;;; ---------------------------------------------------------------------------
;;; start — the check cannot stop the server
;;; ---------------------------------------------------------------------------

(defn- clean-pipeline! []
  (queue/stop!)
  (queue/clear-sinks!)
  (reactive/clear-callbacks!))

(deftest start-continues-when-the-catalog-check-itself-throws
  ;; component/start is stubbed as in core-boot-order-test: no components,
  ;; no database, no port; an empty config keeps the scheduler disabled.
  ;; The check is forced to throw where `start` calls it.
  (let [prior sys/system]
    (clean-pipeline!)
    (try
      (alter-var-root #'sys/system (constantly {:config {}}))
      (let [[_ captured]
            (with-redefs [component/start identity
                          core/check-verb-catalog! (fn [] (throw (ex-info "fixture: the check exploded" {})))]
              (with-captured-log core/start))
            warns (at-level captured :warn)]
        (is (some #(str/includes? (:message %) "VERB-CATALOG-CHECK-FAILED") warns)
            "the failure is logged as a warning by start's own boundary")
        (is (some #(str/includes? (:message %) "the server continues") warns)))
      (finally
        (clean-pipeline!)
        (alter-var-root #'sys/system (constantly prior))))))
