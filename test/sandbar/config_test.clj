(ns sandbar.config-test
  "Tests for sandbar.config — the 3-layer config loader (bundled defaults
   + client-project override + env vars).

   Tests stub `getenv` + `getprop` + the layer-read functions via
   `with-redefs` so they don't depend on the host's actual env / FS state.

   Per memory/decisions/sandbar_deployment_consumption_cohabitability_strategy_2026_05_24.md
   Per memory/interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md."
  (:require [clojure.java.io :as io]
            [clojure.test  :refer [deftest is testing use-fixtures]]
            [sandbar.config :as cfg]
            [sandbar.db.datomic :as db]
            [sandbar.test-util  :as tu]))

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
        (is (contains? p :defaults-fallback?))
        (is (contains? p :layer-1-defaults))
        (is (contains? p :layer-2-override))
        (is (contains? p :layer-3-env))
        (is (contains? p :resolved))
        (is (= {:port 1} (:layer-1-defaults p))))
      (cfg/reload!))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fresh-checkout fallback — config.edn is GITIGNORED, so a fresh checkout
;; has no primary bundled-defaults resource.  `read-bundled-defaults` must
;; fall back to the COMMITTED config-example.edn (portability gap,
;; 2026-07-21) instead of silently resolving {} → nil :required-schema →
;; a schema-less acceptance run failing far from the cause.
;;
;; Tests stub the `bundled-resource` seam (never the host classpath), so
;; they pass identically on a dev checkout (config.edn present) and on a
;; fresh checkout / CI worktree (config.edn absent).

(defn- tmp-edn-file
  "Write `content` to a fresh temp .edn file; return the File."
  ^java.io.File [content]
  (doto (java.io.File/createTempFile "cfg-test-" ".edn")
    (.deleteOnExit)
    (spit content)))

(deftest bundled-defaults-primary-wins-when-present
  (testing "config.edn present → served; example NOT consulted"
    (let [primary (tmp-edn-file "{:port 1111 :marker :primary}")]
      (with-redefs [cfg/bundled-resource
                    (fn [n] (cond (= n cfg/default-resource-name) primary
                                  (= n cfg/example-resource-name)
                                  (throw (AssertionError. "example must not be consulted when primary present"))))]
        (is (= {:port 1111 :marker :primary} (cfg/read-bundled-defaults)))))))

(deftest bundled-defaults-fall-back-to-example-when-primary-absent
  (testing "config.edn absent → committed config-example.edn serves layer 1"
    (with-redefs [cfg/bundled-resource
                  (fn [n] (when (= n cfg/example-resource-name)
                            (io/resource cfg/example-resource-name)))]
      (let [defaults (cfg/read-bundled-defaults)]
        (is (map? defaults))
        (is (seq (:required-schema defaults))
            "fallback defaults must carry a NON-EMPTY :required-schema — the whole point of the fallback")
        (is (= "example" (get-in defaults [:db :sid]))
            "fallback serves the example's sentinel :db values")))))

(deftest bundled-defaults-corrupt-primary-fails-closed
  (testing "config.edn present but unparseable → {} (example does NOT mask corruption)"
    (let [corrupt (tmp-edn-file "{:port 1111 ") ]
      (with-redefs [cfg/bundled-resource
                    (fn [n] (cond (= n cfg/default-resource-name) corrupt
                                  (= n cfg/example-resource-name) (io/resource cfg/example-resource-name)))]
        (is (= {} (cfg/read-bundled-defaults))
            "a corrupt primary is fail-closed — never silently replaced by example values")))))

(deftest bundled-defaults-both-absent-yields-empty
  (testing "neither resource present → {} (packaging error; downstream guards refuse loudly)"
    (with-redefs [cfg/bundled-resource (constantly nil)]
      (is (= {} (cfg/read-bundled-defaults))))))

(deftest example-required-schema-matches-primary
  (testing "the committed example cannot drift from the real config's :required-schema"
    (let [example (some-> (io/resource cfg/example-resource-name) slurp read-string)]
      (is (some? example) "config-example.edn must be on the classpath (it is committed)")
      (is (seq (:required-schema example))
          "config-example.edn must carry a non-empty :required-schema")
      (is (every? keyword? (:required-schema example)))
      ;; On a dev checkout the gitignored config.edn is also present —
      ;; assert lockstep.  On a fresh checkout there is no primary to
      ;; compare against (the example IS the config); skip the comparison.
      (when-let [primary (some-> (io/resource cfg/default-resource-name) slurp read-string)]
        (is (= (:required-schema primary) (:required-schema example))
            "config-example.edn :required-schema must stay in lockstep with config.edn")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Empty-:required-schema loud guards — the acceptance-run (test-util) and
;; boot (db.datomic) schema loaders must REFUSE an empty schema set rather
;; than silently loading nothing.

(deftest load-required-schema-refuses-empty-schema-set
  (testing "tu/load-required-schema throws loudly when :required-schema resolves empty"
    (with-redefs [cfg/read-bundled-defaults (constantly {})
                  cfg/read-client-override  (constantly {})
                  cfg/read-env-overrides    (constantly {})]
      (cfg/reload!)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No :required-schema resolved"
                            (tu/load-required-schema nil))
          "guard must fire BEFORE any conn use (conn nil here)"))))

(deftest load-all-schema-refuses-empty-schema-set
  (testing "db/load-all-schema! throws loudly when :required-schema resolves empty"
    (with-redefs [cfg/read-bundled-defaults (constantly {})
                  cfg/read-client-override  (constantly {})
                  cfg/read-env-overrides    (constantly {})]
      (cfg/reload!)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"No :required-schema resolved"
                            (db/load-all-schema! "datomic:mem://never-reached"))))))
