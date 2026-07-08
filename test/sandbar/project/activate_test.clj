(ns sandbar.project.activate-test
  "R-4 — `sandbar.project.activate` coverage (the confidentiality floor at the
   TOOLING layer, W1.deploy §4 / fork-5 operating model).  A confidentiality
   spine's activation surface is committed-test-worthy: the operating-model
   PRECEDENCE (env > prop > config > UNASSIGNED), the `->project-key` coercion
   exercised through that public contract, and the fail-closed
   `active-route`/`active-project` resolution (an un-activated session binds the
   private UNASSIGNED scope, never the public bottom).

   The three activation surfaces are stubbed via the redefinable config seams
   (`config/getenv`, `config/getprop`, `config/value`) so NO real env / JVM prop
   / on-disk config is read — mirroring the seam discipline the ns docstring
   promises.

   ENV REQUIREMENT (R-5): the DB-touching deftests need a throwaway
   SANDBAR_CLIENT_DIR whose `<dir>/.sandbar/config.edn` carries the 22-key
   `:required-schema` (fixture-time schema load; see IMPLEMENT-REPORT.md)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.project.activate :as activate]
            [sandbar.project.route :as route]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "activate" :auth? false}))

(defn- with-surfaces
  "Run `thunk` with the three activation surfaces stubbed.  `env`/`prop`/`cfg`
   nil ⇒ that surface is ABSENT.  Each stub answers ONLY the key
   `active-project-key` reads (SANDBAR_PROJECT / sandbar.project / :project),
   returning nil for anything else so unrelated config reads are untouched."
  [{:keys [env prop cfg]} thunk]
  (with-redefs [config/getenv (fn [k] (when (= k "SANDBAR_PROJECT") env))
                config/getprop (fn [k] (when (= k "sandbar.project") prop))
                config/value   (fn [k] (when (= k :project) cfg))]
    (thunk)))

(defn- key-with [surfaces]
  (with-surfaces surfaces #(activate/active-project-key)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ->project-key coercion (exercised through the public active-project-key).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest active-project-key-coercion
  (testing "a bare string coerces to the keyword ident"
    (is (= :myproj (key-with {:env "myproj"}))))
  (testing "a namespaced string coerces to a namespaced keyword"
    (is (= :proj/alpha (key-with {:env "proj/alpha"}))))
  (testing "a leading-colon string is coerced with the colon STRIPPED (not doubled)"
    (is (= :myproj (key-with {:env ":myproj"})))
    (is (= :proj/alpha (key-with {:env ":proj/alpha"}))))
  (testing "a keyword config value passes through unchanged"
    (is (= :cfgproj (key-with {:cfg :cfgproj}))))
  (testing "a string config value coerces to a keyword"
    (is (= :cfgstr (key-with {:cfg "cfgstr"}))))
  (testing "an EMPTY-string surface is treated as ABSENT and falls through
            (must NOT coerce to the empty keyword)"
    (is (= :fromcfg (key-with {:env "" :cfg "fromcfg"}))))
  (testing "a non-string / non-keyword surface coerces to nil ⇒ falls through
            to the fail-closed sentinel (the else branch of ->project-key)"
    (is (= :project/UNASSIGNED (key-with {:cfg 42})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Operating-model precedence: env > prop > config (most-specific-first).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest active-project-key-precedence
  (testing "the env-var WINS over BOTH the JVM prop and config"
    (is (= :fromenv (key-with {:env "fromenv" :prop "fromprop" :cfg :fromcfg}))))
  (testing "the JVM prop wins over config when the env-var is absent"
    (is (= :fromprop (key-with {:prop "fromprop" :cfg :fromcfg}))))
  (testing "config :project is used only when env AND prop are both absent"
    (is (= :fromcfg (key-with {:cfg :fromcfg}))))
  (testing "an empty env-var does NOT mask a real prop (empty ⇒ absent, so prop wins)"
    (is (= :fromprop (key-with {:env "" :prop "fromprop"})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; UNASSIGNED fail-closed fallback.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest active-project-key-unassigned-fallback
  (testing "ALL surfaces absent ⇒ :project/UNASSIGNED (fail-closed; never throws)"
    (is (= :project/UNASSIGNED (key-with {}))))
  (testing "the fallback is the shared route sentinel, not a stray keyword"
    (is (= route/unassigned-project-ident (key-with {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; active-route / active-project — fail-closed against the live DB.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest active-route-unactivated-is-fail-closed-private
  (with-surfaces {}
    (fn []
      (let [r (activate/active-route (db/db))]
        (testing "an un-activated session binds the UNASSIGNED PRIVATE scope"
          (is (false? (:routes-to-public? r)) "never routes to the public bottom")
          (is (= :project/UNASSIGNED (:project-key r)))
          (is (= [:trust-scope/private :project/UNASSIGNED] (:trust-scope r)))
          (is (= :private (:sensitivity r))))))))

(deftest active-route-honours-active-public-project
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/active-pub :public :ctx/pub :public-bottom)
  (with-surfaces {:env "proj/active-pub"}
    (fn []
      (let [r (activate/active-route (db/db))]
        (testing "the active public project binds its public route"
          (is (= :proj/active-pub (:project-key r)))
          (is (true? (:routes-to-public? r)))
          (is (= :trust-scope/public (:trust-scope r))))))))

(deftest active-project-resolves-live-entity-or-sentinel
  (sup/seed-context! :ctx/x :project-isolated)
  (sup/seed-project! :proj/live :private :ctx/x)
  (testing "active-project resolves the live entity when the key names one"
    (with-surfaces {:env "proj/live"}
      (fn []
        (is (= (sup/eid-of :proj/live)
               (:db/id (activate/active-project (db/db))))))))
  (testing "an UNKNOWN active key falls back to the UNASSIGNED sentinel entity
            (fail-closed; does not throw)"
    (with-surfaces {:env "proj/does-not-exist"}
      (fn []
        (is (= :project/UNASSIGNED
               (:db/ident (activate/active-project (db/db)))))))))
