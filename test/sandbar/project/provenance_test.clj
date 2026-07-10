(ns sandbar.project.provenance-test
  "W1.E — the export/projection-run provenance recorder's FALSIFICATION battery.

   E-1  happy-path: one export mints exactly one :succeeded :mm/Run
        (`:projection`-discriminated) + its manifest; a FAILED export mints NO
        completed-status run (the negative leg — incompleteness recorded, not
        a silent projection).
   E-2  manifest basis-t == the DB basis-t READ at run time (the guard reads it
        from the manifest, never re-derives).
   CODEX-1  forged-route falsifier: the recorded :manifest/firewall-class is
        DERIVED from the DB-resolved project via the shared label core, so no
        caller-supplied :public can stamp a private export public.
   E-4/R3-1/R4-2  identifier-scrub falsifier: a :public-target COMMITTED
        manifest carries ZERO private identifiers — exclusions collapse to the
        non-linkable count+digest form + an OPAQUE per-run audit-ref, and the
        EXACT enumeration survives audit-side only.

   Fixture discipline mirrors the W1.ctx battery: `sandbar.firewall.support`
   seeds Projects/Contexts via RAW d/transact (setup never trips the guard);
   the recorder writes go through `sandbar.project.provenance`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.project.provenance :as prov]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "provenance" :auth? false}))

(defn- seed-public-project! []
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom))

(defn- seed-private-project! []
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/secret :private :ctx/priv :project-isolated))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CODEX-1 — forged-route falsifier (the held-note travelling requirement)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest forged-route-cannot-stamp-a-private-export-public
  (seed-private-project!)
  (let [db (db/db)
        base-opts {:run 12345
                   :project :proj/secret
                   :written [{:rel-path "secret/a.md" :written true}]
                   :basis-t (d/basis-t db)}]
    (testing "firewall-class is DERIVED :private for a private project"
      (is (= :private
             (:manifest/firewall-class
              (:committed (prov/manifest-for-export db base-opts))))))
    (testing "a caller-supplied forged :public / :sensitivity / :route is IGNORED
              — the held draft trusted (:sensitivity route); this re-derives"
      (doseq [forgery [{:firewall-class :public}
                       {:sensitivity :public}
                       {:route {:sensitivity :public :trust-scope :trust-scope/public}}]]
        (is (= :private
               (:manifest/firewall-class
                (:committed (prov/manifest-for-export db (merge base-opts forgery)))))
            (str "forgery " (pr-str forgery) " must NOT flip the derived class"))))
    (testing "the target's trust-scope is the per-project PRIVATE scope, never public"
      (let [tgt (:manifest/target (:committed (prov/manifest-for-export db base-opts)))]
        (is (= [:trust-scope/private :proj/secret] (:trust-scope tgt)))
        (is (= :proj/secret (:project tgt)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; E-4 / R3-1 / R4-2 — identifier-scrub falsifier (:public committed manifest)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest public-committed-manifest-carries-zero-private-identifiers
  (seed-public-project!)
  (let [db     (db/db)
        secret {:entity :memory/secret-slug :reason :private-cross-boundary}
        redact {:entity :memory/other-secret :fields [:mm.memory/body-raw] :reason :field-private}
        {:keys [committed audit]}
        (prov/manifest-for-export
          db {:run 999
              :project :proj/pub
              :written [{:rel-path "public/x.md" :written true}]
              :basis-t (d/basis-t db)
              :exclusions [secret]
              :redactions [redact]})
        dump (pr-str committed)]
    (testing "the target is genuinely :public (else the scrub is untested)"
      (is (= :public (:manifest/firewall-class committed))))
    (testing "committed exclusions/redactions are the non-linkable count+digest form"
      (is (= 1 (get-in committed [:manifest/exclusions :count])))
      (is (string? (get-in committed [:manifest/exclusions :digest])))
      (is (= 1 (get-in committed [:manifest/redactions :count])))
      (is (not (contains? (:manifest/exclusions committed) :entity))))
    (testing "NO private identifier appears ANYWHERE in the committed manifest (P-CITE-2)"
      (is (not (str/includes? dump "secret-slug")))
      (is (not (str/includes? dump "other-secret")))
      (is (not (str/includes? dump ":memory/"))))
    (testing "the audit-ref is an OPAQUE per-run UUID — no name material (R4-2)"
      (is (uuid? (:manifest/audit-ref committed)))
      (is (not (str/includes? dump "proj/secret"))))
    (testing "the EXACT enumeration survives audit-side, keyed by the same ref (G4)"
      (is (= [secret] (:audit/exclusions audit)))
      (is (= [redact] (:audit/redactions audit)))
      (is (= (:manifest/audit-ref committed) (:audit/ref audit))))))

(deftest private-committed-manifest-may-carry-exact-enumeration
  (seed-private-project!)
  (let [db     (db/db)
        secret {:entity :memory/secret-slug :reason :private-cross-boundary}
        {:keys [committed]}
        (prov/manifest-for-export
          db {:run 7 :project :proj/secret :written []
              :basis-t (d/basis-t db) :exclusions [secret]})]
    (testing "a :private target commits the exact rows (audit + committed co-reside)"
      (is (= :private (:manifest/firewall-class committed)))
      (is (= [secret] (:manifest/exclusions committed))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; E-2 — manifest basis-t == DB basis-t at run time
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest manifest-basis-t-equals-db-basis-t-at-run-time
  (seed-public-project!)
  (let [db (db/db)
        bt (d/basis-t db)
        {:keys [committed]} (prov/manifest-for-export
                              db {:run 1 :project :proj/pub :written [] :basis-t bt})]
    (is (= bt (:manifest/basis-t committed))
        "the guard reads basis-t from the manifest, never re-derives it (E-2)")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; E-1 — happy-path single-Run mint
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest happy-path-mints-exactly-one-succeeded-projection-run
  (seed-public-project!)
  (let [db (db/db)
        {:keys [written run manifest]}
        (prov/with-export-provenance
          db {:project :proj/pub}
          (fn [] [{:rel-path "public/x.md" :written true}]))
        runs (prov/projection-runs (db/db))]
    (testing "exactly one projection run is minted"
      (is (= 1 (count runs))))
    (testing "it carries the :projection discriminator + :succeeded status"
      (is (prov/projection-run? (first runs)))
      (is (= :succeeded (:mm.activity/status (first runs)))))
    (testing "the run + committed manifest are returned; file-set on the manifest"
      (is (some? (:db/id run)))
      (is (= [{:rel-path "public/x.md" :written true}] written))
      (is (= ["public/x.md"] (:manifest/file-set manifest)))
      (is (= (:db/id run) (:manifest/run manifest)))
      (is (= :public (:manifest/firewall-class manifest))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; E-1 (negative) — a FAILED export mints NO completed-status run
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest failed-export-mints-no-completed-status-run
  (seed-public-project!)
  (let [db   (db/db)
        boom (fn [] (throw (ex-info "export blew up" {:where :thunk})))]
    (testing "the export failure propagates (never swallowed)"
      (is (thrown? clojure.lang.ExceptionInfo
                   (prov/with-export-provenance db {:project :proj/pub} boom))))
    (let [runs (prov/projection-runs (db/db))]
      (testing "NO :succeeded projection run exists (E-1 negative)"
        (is (empty? (filter #(= :succeeded (:mm.activity/status %)) runs))))
      (testing "the failure IS recorded as a :failed run (incompleteness recorded — G4)"
        (is (= 1 (count runs)))
        (is (= :failed (:mm.activity/status (first runs))))
        (is (prov/projection-run? (first runs)))))))
