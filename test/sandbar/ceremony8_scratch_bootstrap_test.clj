(ns sandbar.ceremony8-scratch-bootstrap-test
  "R8 — pre-create + schema-load the scratch CONFIG-STORE DB so the full suite can
   close the exactly-14/0 gate IN STAGING.

   ── Why the 29 residual errors happened ───────────────────────────────────
   Under the mandated scratch isolation the config `:db` resolves to
   `datomic:mem://ceremony8-scratch` (sandbar.db.datomic/db-uri).  Each
   firewall/codec test gets its OWN `datomic:mem://<test-name>` DB via
   `make-test-db-fixture`, which resets `sandbar.db.datomic/**conn*` and, on
   teardown, resets it back to nil.  The scheduler/job/system tests, however,
   spawn BACKGROUND dispatcher/job-dispatcher threads; those threads call
   `(db/conn)` — and when they run after their fixture has torn **conn* back to
   nil, `(db/conn)` falls back to `(d/connect (db-uri))` = the CONFIG store.
   Nothing ever created `datomic:mem://ceremony8-scratch`, so that connect throws
   `:db.error/db-not-found` — 29 vars ERROR (not assertion-fail) on the missing
   config store before their assertions run.  The frozen-tree baseline never saw
   this because ITS config pointed at a PERSISTENT store that already existed and
   carried schema; the mem scratch store must be created explicitly.

   ── What this bootstrap does ───────────────────────────────────────────────
   Runs at REQUIRE time (a top-level `defonce`).  `lein test` requires ALL test
   namespaces before running ANY deftest, so this side effect executes before
   every scheduler test — regardless of namespace ordering.  It pre-creates the
   config-store DB and loads the config's `:required-schema` into it, restoring
   the persistent-store precondition the baseline had for free.  `d/create-database`
   is idempotent and the mem DB persists for the JVM lifetime.

   ── Safety: MEM-ONLY guard ─────────────────────────────────────────────────
   The create + schema-load fire ONLY when the resolved URI is a `datomic:mem://`
   scratch store.  Against ANY live/persistent config (`datomic:dev://…`,
   `datomic:free://…`, `datomic:sql://…`) this namespace is a NO-OP — it never
   creates or transacts against a real store.  So it is harmless if the ceremony
   staging branch is FF-merged into coevolution: on the live config it does
   nothing, and the 29 tests then connect to the populated live store directly."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]))

(defonce ^:private scratch-config-store-bootstrap
  (let [uri (db/db-uri)]
    (if (str/starts-with? (str uri) "datomic:mem://")
      (do
        (d/create-database uri)                 ; idempotent; empty mem store
        (try
          (tu/load-required-schema (d/connect uri))  ; give it the config schema set
          {:uri uri :schema-loaded true}
          (catch Throwable t
            ;; A schema hiccup must not abort the whole suite's require phase — the
            ;; DB now EXISTS regardless, which is what stops the db-not-found error.
            (println "R8 scratch-config-store bootstrap: schema-load warning:"
                     (.getMessage t))
            {:uri uri :schema-loaded false :warn (.getMessage t)})))
      ;; Live / persistent config — do nothing.
      {:uri uri :skipped :not-a-mem-store})))

(deftest scratch-config-store-precreated
  ;; A trivial assertion so the bootstrap surfaces as a green var in the suite,
  ;; documenting that R8's precondition was established for this run.
  (is (map? scratch-config-store-bootstrap)
      "R8: the scratch config-store bootstrap ran at require time")
  (when (str/starts-with? (str (db/db-uri)) "datomic:mem://")
    (is (true? (:schema-loaded scratch-config-store-bootstrap))
        "R8: the scratch config store was pre-created + schema-loaded before the
         scheduler/job/system tests connect (closes the db-not-found env-artifact)")))
