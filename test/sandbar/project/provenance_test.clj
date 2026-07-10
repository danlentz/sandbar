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
   P-CITE-2 (whole-file-set)  the manifest boundary REFUSES a class-inconsistent
        written-set (refuse-not-filter): a :public-derived target with ANY
        private/unresolvable row aborts loudly; a :private-derived target with a
        FOREIGN private-scope row aborts loudly.  No committed manifest, a
        :failed run.
   FAIL-CLOSE  the derived route fail-closes to :private for :project/UNASSIGNED
        (the handler default), an unresolvable project ref, and nil.
   E-4/R3-1/R4-2  identifier-scrub falsifier: a :public-target COMMITTED
        manifest carries ZERO private identifiers — exclusions collapse to the
        non-linkable count+digest form + an OPAQUE per-run audit-ref (the run's
        own :mm/id), and the EXACT enumeration survives audit-side only.
   fix-6  the live-write recorder is DOUBLE-gated (server flag AND per-call opt),
        OFF by default.

   Fixture discipline mirrors the W1.ctx battery: `sandbar.firewall.support`
   seeds Projects/Contexts/Memories via RAW d/transact (setup never trips the
   guard) in SEPARATE non-co-batched steps (CA-6 window discipline); the
   recorder writes go through `sandbar.project.provenance`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.project.provenance :as prov]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "provenance" :auth? false}))

;; ── Seeding helpers.  Each is a SEPARATE raw d/transact (CA-6: no live-DB
;; co-batch of a new Project/Context with member entities via make-all*). ──────

(defn- seed-public-project! []
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom))

(defn- seed-private-project! []
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/secret :private :ctx/priv :project-isolated))

(defn- seed-other-private-project! []
  (sup/seed-context! :ctx/other :project-isolated)
  (sup/seed-project! :proj/other :private :ctx/other :project-isolated))

(defn- seed-memory-at!
  "Seed a :mm/Memory carrying `rel-path` (so the recorder's file-set verifier
   resolves it) with `visibility`, owned by `project-ident`.  Returns the eid."
  [ident visibility project-ident rel-path]
  (sup/seed-memory! ident visibility project-ident
                    {:mm.memory/rel-path rel-path}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CODEX-1 — forged-route falsifier (the held-note travelling requirement)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest forged-route-cannot-stamp-a-private-export-public
  (seed-private-project!)
  ;; A private row in the SAME private scope as the target is admissible — seed
  ;; it so the whole-file-set verifier passes and we exercise the class DERIVE.
  (seed-memory-at! :memory/sec-a :private :proj/secret "secret/a.md")
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
;; P-CITE-2 (whole-file-set) — a :public export REFUSES a private-project row
;; (CODEX-B adversarial falsifier; refuse-not-filter).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest public-export-refuses-a-private-row-in-the-file-set
  (seed-public-project!)
  (seed-private-project!)
  ;; A private memory whose rel-path is smuggled into a :public export.
  (seed-memory-at! :memory/leak :private :proj/secret "secret/leak.md")
  (let [db (db/db)
        opts {:run 1
              :project :proj/pub                       ; DERIVES :public
              :written [{:rel-path "secret/leak.md" :written true}] ; but a PRIVATE row
              :basis-t (d/basis-t db)}]
    (testing "manifest-for-export REFUSES with the marker-tagged error, naming the row"
      (let [ex (try (prov/manifest-for-export db opts) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "a class-inconsistent :public export must THROW, not build a manifest")
        (is (= :public-manifest-contains-private-rows (:sandbar/error (ex-data ex))))
        (is (= :public (:target-firewall-class (ex-data ex))))
        (is (some #(= "secret/leak.md" (:rel-path %)) (:offending-rows (ex-data ex)))
            "the refusal names the offending rel-path")
        (is (= :private (some->> (:offending-rows (ex-data ex))
                                 (some #(when (= "secret/leak.md" (:rel-path %)) %))
                                 :sensitivity)))))
    (testing "the private rel-path is UNCONSTRUCTIBLE in any committed artifact
              — the refusal produces no manifest at all"
      (is (nil? (try (prov/manifest-for-export db opts)
                     (catch clojure.lang.ExceptionInfo _ nil)))))
    (testing "an UNRESOLVABLE written rel-path is FAIL-CLOSED :private ⇒ a :public
              target refuses it too (fix-7 'fails to resolve at all' leg)"
      (let [ex (try (prov/manifest-for-export
                      db (assoc opts :written [{:rel-path "nowhere/ghost.md" :written true}]))
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex) "an unresolvable row must NOT be assumed public")
        (is (= :public-manifest-contains-private-rows (:sandbar/error (ex-data ex))))
        (is (some #(= "nowhere/ghost.md" (:rel-path %)) (:offending-rows (ex-data ex))))))))

(deftest with-export-provenance-refusal-records-a-failed-run-only
  (seed-public-project!)
  (seed-private-project!)
  (seed-memory-at! :memory/leak :private :proj/secret "secret/leak.md")
  (let [db (db/db)
        ex (try (prov/with-export-provenance
                  db {:project :proj/pub}
                  (fn [] [{:rel-path "secret/leak.md" :written true}]))
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (testing "the boundary refusal propagates (aborting the future W1.F commit path)"
      (is (some? ex))
      (is (= :public-manifest-contains-private-rows (:sandbar/error (ex-data ex)))))
    (let [runs (prov/projection-runs (db/db))]
      (testing "NO :succeeded run exists — a refused export is not a projection"
        (is (empty? (filter #(= :succeeded (:mm.activity/status %)) runs))))
      (testing "the refusal IS recorded as a :failed run (incompleteness recorded — G4)"
        (is (= 1 (count runs)))
        (is (= :failed (:mm.activity/status (first runs)))))
      (testing "no run's manifest/file-set could carry the private path — none was built"
        (is (not (str/includes? (pr-str (mapv d/touch runs)) "secret/leak.md")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; P-CITE-2 (whole-file-set) — a :private export REFUSES a FOREIGN private scope
;; (fix-1 second clause: a row must be the target scope or :public).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest private-export-refuses-a-foreign-private-scope-row
  (seed-private-project!)
  (seed-other-private-project!)
  (seed-memory-at! :memory/foreign :private :proj/other "other/x.md")
  (let [db (db/db)
        opts {:run 1
              :project :proj/secret                    ; DERIVES [:private :proj/secret]
              :written [{:rel-path "other/x.md" :written true}] ; a DIFFERENT private scope
              :basis-t (d/basis-t db)}
        ex   (try (prov/manifest-for-export db opts) nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (testing "a foreign private-scope row is refused (not silently co-committed)"
      (is (some? ex))
      (is (= :private-manifest-contains-foreign-rows (:sandbar/error (ex-data ex))))
      (is (some #(= "other/x.md" (:rel-path %)) (:offending-rows (ex-data ex)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FAIL-CLOSE — the derived route defaults to :private (locks the handler
;; default + the unresolvable/nil legs the OPUS examiner verified only
;; empirically).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest derivation-fail-closes-to-private-for-unassigned-unresolvable-and-nil
  (let [db (db/db)
        derived-class (fn [project]
                        (:manifest/firewall-class
                         (:committed
                          (prov/manifest-for-export
                            db {:run 1 :project project :written []
                                :basis-t (d/basis-t db)}))))
        derived-scope (fn [project]
                        (:trust-scope
                         (:manifest/target
                          (:committed
                           (prov/manifest-for-export
                             db {:run 1 :project project :written []
                                 :basis-t (d/basis-t db)})))))]
    (testing ":project/UNASSIGNED (the handler default) derives :private"
      (is (= :private (derived-class :project/UNASSIGNED)))
      (is (= [:trust-scope/private :project/UNASSIGNED] (derived-scope :project/UNASSIGNED))))
    (testing "an unresolvable project ref derives :private"
      (is (= :private (derived-class :project/does-not-exist))))
    (testing "a nil project ref derives :private (never widens to public)"
      (is (= :private (derived-class nil))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; E-4 / R3-1 / R4-2 — identifier-scrub falsifier (:public committed manifest)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest public-committed-manifest-carries-zero-private-identifiers
  (seed-public-project!)
  ;; The written row must resolve :public (else the file-set verifier refuses
  ;; BEFORE the audience-split is exercised).
  (seed-memory-at! :memory/pub-x :public :proj/pub "public/x.md")
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
    (testing "the public file-set rides the manifest (adversarial-sweep target)"
      (is (= ["public/x.md"] (:manifest/file-set committed))))
    (testing "NO private identifier appears ANYWHERE in the committed manifest —
              including :manifest/file-set (P-CITE-2 whole-map sweep)"
      (is (not (str/includes? dump "secret-slug")))
      (is (not (str/includes? dump "other-secret")))
      (is (not (str/includes? dump ":memory/")))
      (is (not (str/includes? dump "proj/secret"))))
    (testing "the audit-ref is an OPAQUE run-scoped UUID — no name material (R4-2)"
      (is (uuid? (:manifest/audit-ref committed))))
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
;; E-1 — happy-path single-Run mint + REBUILD-STABLE :manifest/run
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest happy-path-mints-exactly-one-succeeded-projection-run
  (seed-public-project!)
  (seed-memory-at! :memory/pub-x :public :proj/pub "public/x.md")
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
    (testing "the run carries a :mm/id (dt/make does not auto-mint one for :mm/Run)"
      (is (uuid? (:mm/id run)))
      (is (= (:mm/id run) (:mm/id (first runs)))))
    (testing "the run + committed manifest are returned; file-set on the manifest"
      (is (= [{:rel-path "public/x.md" :written true}] written))
      (is (= ["public/x.md"] (:manifest/file-set manifest)))
      (is (= :public (:manifest/firewall-class manifest))))
    (testing ":manifest/run is a REBUILD-STABLE [:mm/id …] lookup-ref (fix-5/11) —
              NOT a volatile :db/id eid — and resolves to the minted run"
      (is (= [:mm/id (:mm/id run)] (:manifest/run manifest)))
      (is (= (:db/id run)
             (:db/id (d/entity (db/db) (:manifest/run manifest))))))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; fix-6 — the live-write recorder is DOUBLE-gated, OFF by default
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest recording-enabled-defaults-off-and-double-gates
  (testing "absent all flags ⇒ OFF (the default posture; a bare caller cannot mint)"
    (with-redefs [config/getenv  (constantly nil)
                  config/getprop (constantly nil)
                  config/value   (constantly nil)]
      (is (false? (prov/recording-enabled?)))))
  (testing "SANDBAR_PROVENANCE_RECORD env truthy ⇒ ON"
    (with-redefs [config/getenv  (fn [k] (when (= k "SANDBAR_PROVENANCE_RECORD") "true"))
                  config/getprop (constantly nil)
                  config/value   (constantly nil)]
      (is (true? (prov/recording-enabled?)))))
  (testing "sandbar.provenance.record JVM prop truthy ⇒ ON"
    (with-redefs [config/getenv  (constantly nil)
                  config/getprop (fn [k] (when (= k "sandbar.provenance.record") "1"))
                  config/value   (constantly nil)]
      (is (true? (prov/recording-enabled?)))))
  (testing ":provenance-record? config key true ⇒ ON"
    (with-redefs [config/getenv  (constantly nil)
                  config/getprop (constantly nil)
                  config/value   (constantly true)]
      (is (true? (prov/recording-enabled?)))))
  (testing "a FALSEY env value stays OFF (presence alone is not enablement)"
    (with-redefs [config/getenv  (fn [k] (when (= k "SANDBAR_PROVENANCE_RECORD") "false"))
                  config/getprop (constantly nil)
                  config/value   (constantly nil)]
      (is (false? (prov/recording-enabled?))))))
