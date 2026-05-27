(ns sandbar.core-test
  "Tests for system lifecycle management"
  (:require [clojure.test :refer :all]
            [com.stuartsierra.component :as component]
            [sandbar.core :as core]
            [sandbar.sys :as sys]
            [sandbar.db.datomic :as db]
            [sandbar.schedule :as sched]
            [sandbar.schedule.state :as sched-state]
            [sandbar.event :as event]
            [sandbar.server.pedestal :as pedestal]
            [sandbar.server.nrepl :as nrepl]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn reset-system-fixture
  "Ensures sys/system is reset to nil before and after each test"
  [f]
  (alter-var-root #'sys/system (constantly nil))
  (try
    (f)
    (finally
      (when sys/system
        (try
          (component/stop sys/system)
          (catch Exception _)))
      (alter-var-root #'sys/system (constantly nil)))))

(use-fixtures :each reset-system-fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; make-system Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest make-system-test
  (testing "make-system creates a system map"
    (let [system (core/make-system)]
      (is (map? system)
          "System should be a map")
      (is (satisfies? component/Lifecycle system)
          "System should satisfy Lifecycle protocol")))

  (testing "make-system includes required components"
    (let [system (core/make-system)]
      (is (contains? system :config)
          "System should have :config component")
      (is (contains? system :pedestal)
          "System should have :pedestal component")
      (is (contains? system :datomic)
          "System should have :datomic component")
      (is (contains? system :nrepl)
          "System should have :nrepl component")))

  (testing "make-system components have correct types"
    (let [system (core/make-system)]
      (is (instance? sandbar.server.pedestal.Pedestal (:pedestal system))
          "Pedestal component should be a Pedestal record")
      (is (instance? sandbar.db.datomic.DatomicPeer (:datomic system))
          "Datomic component should be a DatomicPeer record")
      (is (instance? sandbar.server.nrepl.NRepl (:nrepl system))
          "nREPL component should be an NRepl record")))

  (testing "make-system accepts custom configuration designator"
    (let [system (core/make-system :config)]
      (is (map? system)
          "System should be created with explicit :config designator"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; init Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest init-test
  (testing "init sets sys/system to a system map"
    (is (nil? sys/system)
        "sys/system should be nil before init")
    (core/init)
    (is (some? sys/system)
        "sys/system should not be nil after init")
    (is (map? sys/system)
        "sys/system should be a map after init"))

  (testing "init creates system with all components"
    (core/init)
    (is (contains? sys/system :config)
        "Initialized system should have :config")
    (is (contains? sys/system :pedestal)
        "Initialized system should have :pedestal")
    (is (contains? sys/system :datomic)
        "Initialized system should have :datomic")
    (is (contains? sys/system :nrepl)
        "Initialized system should have :nrepl"))

  (testing "init replaces existing system"
    (core/init)
    (let [first-system sys/system]
      (core/init)
      (is (not (identical? first-system sys/system))
          "init should create a new system instance"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; stop Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest stop-test
  (testing "stop handles nil system gracefully"
    (is (nil? sys/system)
        "sys/system should be nil")
    (is (nil? (core/stop))
        "stop should return nil when system is nil"))

  (testing "stop with initialized-only system"
    (core/init)
    (is (some? sys/system)
        "System should exist after init")
    (core/stop)
    (is (some? sys/system)
        "System map should still exist after stop (but components stopped)")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Component Record Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest pedestal-component-test
  (testing "make-pedestal-server creates Pedestal record"
    (let [pedestal (pedestal/make-pedestal-server :dev)]
      (is (instance? sandbar.server.pedestal.Pedestal pedestal)
          "Should create Pedestal record")
      (is (some? (:connector pedestal))
          "Pedestal should have connector")
      (is (nil? (:server pedestal))
          "Pedestal should have nil server before start")))

  (testing "make-pedestal-server accepts :prod mode"
    (let [pedestal (pedestal/make-pedestal-server :prod)]
      (is (instance? sandbar.server.pedestal.Pedestal pedestal)
          "Should create Pedestal record for prod mode"))))

(deftest datomic-component-test
  (testing "make-datomic-peer creates DatomicPeer record"
    (let [spec {:url "datomic:mem://" :sid "test-db"}
          peer (db/make-datomic-peer spec)]
      (is (instance? sandbar.db.datomic.DatomicPeer peer)
          "Should create DatomicPeer record")
      (is (= spec (:spec peer))
          "DatomicPeer should have spec")
      (is (= "datomic:mem://test-db" (:uri peer))
          "DatomicPeer should have constructed URI")
      (is (nil? (:c peer))
          "DatomicPeer should have nil connection before start"))))

(deftest nrepl-component-test
  (testing "make-nrepl-server creates NRepl record"
    (let [config {:nrepl {:port 12345}}
          nrepl (nrepl/make-nrepl-server config)]
      (is (instance? sandbar.server.nrepl.NRepl nrepl)
          "Should create NRepl record")
      (is (= 12345 (:port nrepl))
          "NRepl should have configured port")
      (is (nil? (:server nrepl))
          "NRepl should have nil server before start")))

  (testing "make-nrepl-server handles missing port"
    (let [config {}
          nrepl (nrepl/make-nrepl-server config)]
      (is (instance? sandbar.server.nrepl.NRepl nrepl)
          "Should create NRepl record even without port config")
      (is (nil? (:port nrepl))
          "NRepl port should be nil when not configured"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; db-uri Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest db-uri-test
  (testing "db-uri constructs URI from spec"
    (let [spec {:url "datomic:dev://localhost:4334/" :sid "mydb"}]
      (is (= "datomic:dev://localhost:4334/mydb" (db/db-uri spec))
          "Should concatenate url and sid")))

  (testing "db-uri handles mem database"
    (let [spec {:url "datomic:mem://" :sid "testdb"}]
      (is (= "datomic:mem://testdb" (db/db-uri spec))
          "Should work with mem database"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Lifecycle Protocol Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest lifecycle-protocol-test
  (testing "System map satisfies Lifecycle"
    (let [system (core/make-system)]
      (is (satisfies? component/Lifecycle system)
          "System should satisfy Lifecycle protocol")))

  (testing "Pedestal component satisfies Lifecycle"
    (let [pedestal (pedestal/make-pedestal-server :dev)]
      (is (satisfies? component/Lifecycle pedestal)
          "Pedestal should satisfy Lifecycle protocol")))

  (testing "DatomicPeer component satisfies Lifecycle"
    (let [peer (db/make-datomic-peer {:url "datomic:mem://" :sid "test"})]
      (is (satisfies? component/Lifecycle peer)
          "DatomicPeer should satisfy Lifecycle protocol")))

  (testing "NRepl component satisfies Lifecycle"
    (let [nrepl (nrepl/make-nrepl-server {:nrepl {:port 0}})]
      (is (satisfies? component/Lifecycle nrepl)
          "NRepl should satisfy Lifecycle protocol"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests (with in-memory Datomic)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest datomic-peer-lifecycle-test
  (testing "DatomicPeer can start and stop with in-memory database"
    (let [spec {:url "datomic:mem://" :sid (str "test-" (System/currentTimeMillis))}
          peer (db/make-datomic-peer spec)
          started-peer (component/start peer)]
      (try
        (is (some? (:c started-peer))
            "Started peer should have connection")
        (let [stopped-peer (component/stop started-peer)]
          (is (nil? (:c stopped-peer))
              "Stopped peer should have nil connection"))
        (finally
          (try
            (db/delete-db (:uri peer))
            (catch Exception _)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; γ.3 — start-scheduler-if-enabled! lifecycle wiring tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Tests the conditional wiring of sandbar.schedule into sandbar.core/start
;; per γ.3.  Uses fresh scheduler-state binding (atom-inside-dyn-var) +
;; faked sys/system carrying a :config slot with the relevant :scheduler
;; sub-map.  Verifies the conditional outcome WITHOUT exercising the full
;; sandbar.core/start (which is a heavyweight integration test).

(defn scheduler-isolation-fixture
  "Fresh scheduler-state per test + tear-down."
  [f]
  (binding [sched-state/*scheduler-state* (atom (sched-state/initial-state))]
    (event/clear!)
    (try (f)
         (finally
           (try (sched/stop! {:drain-timeout-ms 200}) (catch Exception _ nil))
           (event/clear!)))))

(deftest start-scheduler-if-enabled-respects-disabled-config
  (scheduler-isolation-fixture
    (fn []
      (testing "When :scheduler {:enabled? false}, no scheduler activation"
        (alter-var-root #'sys/system
                        (constantly {:config {:scheduler {:enabled? false}}}))
        (let [outcome ((resolve 'sandbar.core/start-scheduler-if-enabled!))]
          (is (= :scheduler-disabled-by-config outcome))
          (is (false? (sched/enabled?)))
          (is (= :scheduler.state/inactive (sched/state))))))))

(deftest start-scheduler-if-enabled-activates-when-config-true
  (scheduler-isolation-fixture
    (fn []
      (testing "When :scheduler {:enabled? true}, scheduler enables + starts"
        (alter-var-root #'sys/system
                        (constantly {:config {:scheduler {:enabled? true
                                                          :jobs []}}}))
        (let [outcome ((resolve 'sandbar.core/start-scheduler-if-enabled!))]
          (is (= :scheduler-started outcome))
          (is (sched/enabled?))
          (is (= :scheduler.state/active (sched/state))))))))

(deftest start-scheduler-if-enabled-default-disabled-when-no-config
  (scheduler-isolation-fixture
    (fn []
      (testing "Missing :scheduler config defaults to disabled (opt-in safety per Q.γ.5)"
        (alter-var-root #'sys/system (constantly {:config {}}))
        (let [outcome ((resolve 'sandbar.core/start-scheduler-if-enabled!))]
          (is (= :scheduler-disabled-by-config outcome))
          (is (false? (sched/enabled?))))))))
