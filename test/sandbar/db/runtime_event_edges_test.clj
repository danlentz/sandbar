(ns sandbar.db.runtime-event-edges-test
  "The boot-time guard that retracts `:dt/subclass-of :mm/Event` from the six
   runtime-event classes.

   Why: from 2026-05-24 to 2026-09-19 schema/mm-temporal.edn re-parented the
   `:event/*` family additively under `:mm/Event`, which made every runtime
   event row an `:mm/Memory` instance — 71,565 of the 91,792 entities counted
   as memories on 2026-09-19 were request, server and system event rows.
   Dan's retention-review ruling of that day retracted the six links one-off.
   The schema source no longer declares them, but a Datomic schema load only
   asserts, so a store initialized before that day (or a restored backup of
   one) keeps them until `sandbar.db.datomic/retract-runtime-event-memory-
   edges!` runs from `initialize-db!`.

   The acceptance the plan asks for: a fresh store and a store carrying the
   links end up agreeing."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]))

(def ^:private test-name "runtime-event-edges-test")
(def ^:private uri (str "datomic:mem://" test-name))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name    test-name
                            :auth?        false
                            :extra-schema [:event]}))

(defn- carrying
  "The runtime-event classes whose direct parents include :mm/Event."
  []
  (->> db/+runtime-event-classes+
       (filter #(contains? (set (dt/parents-of %)) :mm/Event))
       set))

(deftest a-fresh-store-carries-no-links-and-the-guard-is-a-no-op
  (is (empty? (carrying)) "the schema source no longer declares the links")
  (is (= [] (db/retract-runtime-event-memory-edges! uri)) "nothing to heal")
  (doseq [cls db/+runtime-event-classes+]
    (is (not (dt/subclass-of? :mm/Memory cls)) (str cls " is not under :mm/Memory"))
    (is (dt/subclass-of? :dt/Event cls) (str cls " keeps its :dt/Event ancestry"))))

(deftest a-store-carrying-the-links-is-healed-idempotently
  ;; Simulate a store initialized before 2026-09-19: assert the six links
  ;; and refresh the memoized closures as a schema reload would.
  @(d/transact (db/conn)
               (mapv (fn [cls] [:db/add cls :dt/subclass-of :mm/Event])
                     db/+runtime-event-classes+))
  (db/fire-post-schema-reload-handlers!)
  (is (= (set db/+runtime-event-classes+) (carrying)) "all six links present")
  (is (dt/subclass-of? :mm/Memory :event/HttpRequest)
      "with the links a runtime-event class IS under :mm/Memory (the pre-2026-09-19 state)")
  (let [retracted (db/retract-runtime-event-memory-edges! uri)]
    (is (= (set db/+runtime-event-classes+) (set retracted))
        "the guard reports every class it healed"))
  (is (empty? (carrying)) "no link survives")
  (is (not (dt/subclass-of? :mm/Memory :event/HttpRequest))
      "the memoized closure was refreshed by the guard")
  (is (= [] (db/retract-runtime-event-memory-edges! uri)) "a second run is a no-op")
  (testing "the healed store agrees with a fresh one"
    (doseq [cls db/+runtime-event-classes+]
      (is (not (dt/subclass-of? :mm/Event cls)))
      (is (dt/subclass-of? :dt/Event cls)))))

(deftest initialize-db-runs-the-guard
  ;; The same store, links re-asserted, then the boot path itself.
  @(d/transact (db/conn)
               (mapv (fn [cls] [:db/add cls :dt/subclass-of :mm/Event])
                     db/+runtime-event-classes+))
  (db/fire-post-schema-reload-handlers!)
  (is (= 6 (count (carrying))))
  (db/initialize-db! uri)
  (is (empty? (carrying)) "initialize-db! healed the store")
  (is (not (dt/subclass-of? :mm/Memory :event/SystemEvent))))
