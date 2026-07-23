(ns sandbar.reactive.tx-source-test
  "Tests for sandbar.reactive.tx-source — the Phase 1 boundary primitive
   wrapping Datomic d/tx-report-queue.

   Per memory/decisions/sandbar_event_substrate_architecture_*_2026_05_23.md
   keystone ADR D.1.

   Tests cover the pure translation (`tx-report->event`); lifecycle
   (`start!` / `stop!` idempotence) tests are deferred (require a
   live test DB)."
  (:require [clojure.test :refer :all]
            [sandbar.reactive.tx-source :as tx-source]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure translation tests — tx-report->event
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- mock-datom
  "Build a mock Datomic Datom-like record (just enough to satisfy
   :e/:a/:v/:added/:t access)."
  [e a v added?]
  ;; Datomic's Datom supports both .e/.a/.v/.added accessors and
  ;; keyword access.  We use keyword access in the production code, so
  ;; a plain map is sufficient for tests.
  {:e e :a a :v v :added added? :tx 0})

(deftest tx-report->event-shape
  (testing "Returns sandbar-typed event with no Datomic types"
    (let [datoms [(mock-datom 17592186045555 :db/txInstant #inst "2026-05-24T03:00:00Z" true)
                  (mock-datom 17592186045556 :mm.memory/name "Test Decision" true)]
          report {:tx-data  datoms
                  :db-after nil ;; we'll mock the basis-t case separately
                  :tempids  {"foo" 17592186045557}}
          event  (tx-source/tx-report->event report)]
      (testing "kind is always :tx in Phase 1"
        (is (= :tx (:event/kind event))))
      (testing "timestamp extracted from :db/txInstant datom"
        (is (= #inst "2026-05-24T03:00:00Z" (:event/timestamp event))))
      (testing "datom-count matches tx-data size"
        (is (= 2 (:event/datom-count event))))
      (testing "tempids passed through"
        (is (= {"foo" 17592186045557} (:event/tempids event))))
      (testing "datoms are [e a v added?] tuples"
        (is (= [[17592186045555 :db/txInstant #inst "2026-05-24T03:00:00Z" true]
                [17592186045556 :mm.memory/name "Test Decision" true]]
               (:event/datoms event)))))))

(deftest tx-report->event-handles-empty-tempids
  (testing "Missing :tempids defaults to empty map"
    (let [event (tx-source/tx-report->event
                  {:tx-data [(mock-datom 1 :a "v" true)]
                   :db-after nil})]
      (is (= {} (:event/tempids event))))))

(deftest tx-report->event-handles-no-tx-instant
  (testing "TxReport without a :db/txInstant datom yields :event/timestamp nil"
    (let [event (tx-source/tx-report->event
                  {:tx-data [(mock-datom 1 :user/name "Alice" true)]
                   :db-after nil})]
      (is (nil? (:event/timestamp event))))))

(deftest tx-report->event-handles-retractions
  (testing "Retraction datoms (added? false) preserved"
    (let [event (tx-source/tx-report->event
                  {:tx-data [(mock-datom 17592186045555 :mm.memory/last-touched
                                          #inst "2026-05-24T03:00:00Z" true)
                             (mock-datom 17592186045555 :mm.memory/last-touched
                                          #inst "2026-05-24T02:00:00Z" false)]
                   :db-after nil})]
      (is (= [[17592186045555 :mm.memory/last-touched #inst "2026-05-24T03:00:00Z" true]
              [17592186045555 :mm.memory/last-touched #inst "2026-05-24T02:00:00Z" false]]
             (:event/datoms event))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public surface tests — no Datomic types leak past the boundary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest no-datomic-types-in-event-keys
  (testing "All event-map keys are sandbar-typed (in :event/* namespace)"
    (let [event (tx-source/tx-report->event
                  {:tx-data [(mock-datom 1 :a "v" true)]
                   :db-after nil
                   :tempids  {}})]
      (is (every? #(= "event" (namespace %)) (keys event))
          "Every key in the event must be in the :event/* namespace"))))

(deftest no-datomic-types-in-datom-tuples
  (testing "Datoms surface as plain vectors of primitive values"
    (let [event (tx-source/tx-report->event
                  {:tx-data [(mock-datom 17592186045555 :mm.memory/name "Test" true)]
                   :db-after nil
                   :tempids  {}})
          datom (first (:event/datoms event))]
      (is (vector? datom))
      (is (= 4 (count datom)))
      (is (integer? (nth datom 0)) "e is a long")
      (is (keyword? (nth datom 1)) "a is a keyword")
      (is (string?  (nth datom 2)) "v is its value-type (string here)")
      (is (boolean? (nth datom 3)) "added? is a boolean"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle tests — start!/stop! idempotence (require live DB)
;;
;; Skipped here — would require sandbar's test-db fixture to spin up a
;; real Datomic connection.  Lifecycle is exercised end-to-end when
;; sandbar.core/start integration lands.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
