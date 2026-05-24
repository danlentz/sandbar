(ns sandbar.config-test
  "Tests for sandbar.config — the 3-layer config loader (bundled defaults
   + client-project override + env vars).

   Tests stub `getenv` + `getprop` + the layer-read functions via
   `with-redefs` so they don't depend on the host's actual env / FS state.

   Per memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md
   Per memory/interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md."
  (:require [clojure.test  :refer [deftest is testing use-fixtures]]
            [sandbar.config :as cfg]))

;; Wave 0 W.0.4 fix per metamodel-unification arc:
;; This test file uses with-redefs to stub `cfg/read-bundled-defaults` etc. to
;; minimal maps that DO NOT include `:required-schema`.  Tests call `cfg/reload!`
;; inside with-redefs to populate `cfg/config-state` from the stubbed sources.
;; When with-redefs exits the stubs revert, but `cfg/config-state` retains the
;; stubbed values — so subsequent tests in the SAME JVM see a config-state with
;; no `:required-schema`, causing test fixtures (sandbar.test-util/load-schema)
;; to load NO schemas → cascade failures with `:db.error/not-an-entity` for
;; substrate slots like `:dt/slots` (declared in schema/meta.edn).
;;
;; Fix: per-test fixture that calls `cfg/reload!` AFTER each test (with real
;; read-* fns restored) so the config-state is restored to bundled defaults
;; for any subsequent test in the JVM.
(use-fixtures :each
  (fn [t]
    (try (t)
         (finally (cfg/reload!)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; deep-merge

(deftest deep-merge-basic
  (testing "trivial merges"
    (is (= {:a 1 :b 2} (cfg/deep-merge {:a 1} {:b 2})))
    (is (= {:a 2}      (cfg/deep-merge {:a 1} {:a 2}))))
  (testing "nested merge"
    (is (= {:db {:url "x" :sid "y"}}
           (cfg/deep-merge {:db {:url "x"}} {:db {:sid "y"}}))))
  (testing "deep nested merge"
    (is (= {:a {:b {:c 1 :d 2}}}
           (cfg/deep-merge {:a {:b {:c 1}}} {:a {:b {:d 2}}}))))
  (testing "right-side non-map replaces left-side map"
    (is (= {:a 5}
           (cfg/deep-merge {:a {:nested true}} {:a 5}))))
  (testing "nil values in b don't replace values in a"
    (is (= {:a 1}
           (cfg/deep-merge {:a 1} {:a nil})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; client-dir resolution

(deftest client-dir-env-var
  (testing "$SANDBAR_CLIENT_DIR wins"
    (with-redefs [cfg/getenv  (fn [k] (when (= k "SANDBAR_CLIENT_DIR") "/foo/env"))
                  cfg/getprop (fn [_] "/foo/cwd")]
      (is (= "/foo/env" (cfg/client-dir))))))

(deftest client-dir-jvm-prop-fallback
  (testing "JVM property is fallback when env unset"
    (with-redefs [cfg/getenv  (constantly nil)
                  cfg/getprop (fn [k] (case k
                                        "sandbar.client-dir" "/foo/jvm"
                                        "user.dir"           "/foo/cwd"))]
      (is (= "/foo/jvm" (cfg/client-dir))))))

(deftest client-dir-cwd-final-fallback
  (testing "CWD is fallback when env + jvm-prop unset"
    (with-redefs [cfg/getenv  (constantly nil)
                  cfg/getprop (fn [k] (when (= k "user.dir") "/foo/cwd"))]
      (is (= "/foo/cwd" (cfg/client-dir))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; read-env-overrides

(deftest read-env-overrides-empty
  (testing "no env vars → empty map"
    (with-redefs [cfg/getenv (constantly nil)]
      (is (= {} (cfg/read-env-overrides))))))

(deftest read-env-overrides-port
  (testing "SANDBAR_PORT → {:port N} (coerced to Long)"
    (with-redefs [cfg/getenv (fn [k] (when (= k "SANDBAR_PORT") "9000"))]
      (is (= {:port 9000} (cfg/read-env-overrides))))))

(deftest read-env-overrides-nested
  (testing "SANDBAR_NREPL_PORT → {:nrepl {:port N}}"
    (with-redefs [cfg/getenv (fn [k] (when (= k "SANDBAR_NREPL_PORT") "29999"))]
      (is (= {:nrepl {:port 29999}} (cfg/read-env-overrides))))))

(deftest read-env-overrides-db-nested
  (testing "SANDBAR_DB_SID → {:db {:sid ...}}"
    (with-redefs [cfg/getenv (fn [k] (when (= k "SANDBAR_DB_SID") "test-db"))]
      (is (= {:db {:sid "test-db"}} (cfg/read-env-overrides))))))

(deftest read-env-overrides-multiple
  (testing "multiple env vars build nested map"
    (with-redefs [cfg/getenv (fn [k]
                               (case k
                                 "SANDBAR_PORT"    "8389"
                                 "SANDBAR_DB_SID"  "alpha"
                                 "SANDBAR_DB_URL"  "datomic:dev://x:4334/"
                                 nil))]
      (is (= {:port 8389
              :db   {:sid "alpha"
                     :url "datomic:dev://x:4334/"}}
             (cfg/read-env-overrides))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Full 3-layer composition

(deftest layered-merge-env-overrides-client-overrides-defaults
  (testing "env-var > client-override > bundled-defaults"
    (with-redefs [cfg/read-bundled-defaults (constantly
                                             {:port 8080
                                              :db {:url "datomic:dev://localhost:4334/"
                                                   :sid "sandbar"}})
                  cfg/read-client-override  (constantly
                                             {:port 8389
                                              :db {:sid "global"}})
                  cfg/read-env-overrides    (constantly
                                             {:db {:sid "ops-override"}})]
      (cfg/reload!)
      (let [c (cfg/config)]
        ;; port: layer 2 overrides layer 1
        (is (= 8389 (:port c)))
        ;; db url: layer 1 preserved (layer 2 didn't touch it)
        (is (= "datomic:dev://localhost:4334/" (-> c :db :url)))
        ;; db sid: layer 3 overrides layer 2 overrides layer 1
        (is (= "ops-override" (-> c :db :sid))))
      (cfg/reload!))))

(deftest layered-merge-defaults-only
  (testing "layers 2+3 absent → defaults shine through"
    (with-redefs [cfg/read-bundled-defaults (constantly
                                             {:port 8080
                                              :db {:url "u" :sid "s"}})
                  cfg/read-client-override  (constantly {})
                  cfg/read-env-overrides    (constantly {})]
      (cfg/reload!)
      (is (= {:port 8080 :db {:url "u" :sid "s"}}
             (cfg/config)))
      (cfg/reload!))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; value + get-in*

(deftest value-single-key
  (with-redefs [cfg/read-bundled-defaults (constantly {:port 8389})
                cfg/read-client-override  (constantly {})
                cfg/read-env-overrides    (constantly {})]
    (cfg/reload!)
    (is (= 8389 (cfg/value :port)))
    (cfg/reload!)))

(deftest value-path
  (with-redefs [cfg/read-bundled-defaults (constantly {:db {:sid "x"}})
                cfg/read-client-override  (constantly {})
                cfg/read-env-overrides    (constantly {})]
    (cfg/reload!)
    (is (= "x" (cfg/value :db :sid)))
    (cfg/reload!)))

(deftest get-in*-default
  (with-redefs [cfg/read-bundled-defaults (constantly {})
                cfg/read-client-override  (constantly {})
                cfg/read-env-overrides    (constantly {})]
    (cfg/reload!)
    (is (= :missing (cfg/get-in* [:nonexistent] :missing)))
    (cfg/reload!)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; provenance

(deftest provenance-keys
  (testing "provenance returns expected diagnostic keys"
    (with-redefs [cfg/read-bundled-defaults (constantly {:port 1})
                  cfg/read-client-override  (constantly {})
                  cfg/read-env-overrides    (constantly {})]
      (cfg/reload!)
      (let [p (cfg/provenance)]
        (is (contains? p :client-dir))
        (is (contains? p :layer-1-defaults))
        (is (contains? p :layer-2-override))
        (is (contains? p :layer-3-env))
        (is (contains? p :resolved))
        (is (= {:port 1} (:layer-1-defaults p))))
      (cfg/reload!))))
