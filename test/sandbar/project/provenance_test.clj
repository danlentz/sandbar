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
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.mcp.tools]                            ; #'project-export-handler (double-gate)
            [sandbar.project.provenance :as prov]
            [sandbar.projection :as pg]
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

(defn- seed-emittable-memory!
  "Like `seed-memory-at!` but carries an empty `:mm.memory/body-raw` so
   `project-graph`'s markdown emit produces a real file (the fix-14 real
   file-writing thunk)."
  [ident visibility project-ident rel-path]
  (sup/seed-memory! ident visibility project-ident
                    {:mm.memory/rel-path rel-path :mm.memory/body-raw ""}))

(defn- scratch-to
  "A fresh scratch output directory path for a project-graph write."
  [label]
  (let [d (io/file (System/getProperty "java.io.tmpdir")
                   (str "w1e-" label "-" (System/nanoTime)))]
    (.mkdirs d)
    (.getPath d)))

;; ── The server-side recording flag (double-gate half).  ON via the config
;; value seam; OFF with every seam falsey (env/prop/config), matching how a
;; deployed server would read `prov/recording-enabled?`. ────────────────────
(defn- with-recording-on* [f]
  (with-redefs [config/getenv  (constantly nil)
                config/getprop (constantly nil)
                config/value   (fn [k] (when (= k :provenance-record?) true))]
    (f)))

(defn- with-recording-off* [f]
  (with-redefs [config/getenv  (constantly nil)
                config/getprop (constantly nil)
                config/value   (constantly nil)]
    (f)))

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
  ;; fix-3/14: a REAL file-writing thunk (`project-graph` to a scratch :to), not a
  ;; hand-built row-vector.  A :proj/secret-owned memory projected into a :proj/pub
  ;; export — the gate routes the row's CARRIED :entity (proj/secret, private) and
  ;; REFUSES for the public target.  Only a :failed run is recorded; the refused
  ;; file IS on disk (the documented spill seam: the thunk wrote before the gate).
  (seed-public-project!)
  (seed-private-project!)
  (seed-emittable-memory! :memory/leak :private :proj/secret "secret/leak.md")
  (let [db    (db/db)
        to    (scratch-to "refusal")
        ;; realize → project-graph, exactly as the export handler does; the
        ;; written rows carry :entity (source-descriptor → owning-project
        ;; :proj/secret), so the gate routes the ACTUAL projected entity.
        thunk (fn [] (pg/project-graph (dt/realize-with :memory/leak pg/mm-walker)
                                       {:to to}))
        ex    (try (prov/with-export-provenance db {:project :proj/pub} thunk)
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
    (testing "the boundary refusal propagates (aborting the future W1.F commit path)"
      (is (some? ex))
      (is (= :public-manifest-contains-private-rows (:sandbar/error (ex-data ex))))
      (is (some #(= "secret/leak.md" (:rel-path %)) (:offending-rows (ex-data ex)))))
    (let [runs (prov/projection-runs (db/db))]
      (testing "NO :succeeded run exists — a refused export is not a projection"
        (is (empty? (filter #(= :succeeded (:mm.activity/status %)) runs))))
      (testing "the refusal IS recorded as a :failed run (incompleteness recorded — G4)"
        (is (= 1 (count runs)))
        (is (= :failed (:mm.activity/status (first runs)))))
      (testing "no run's manifest/file-set could carry the private path — none was built"
        (is (not (str/includes? (pr-str (mapv d/touch runs)) "secret/leak.md")))))
    (testing "SPILL SEAM (documented, W1.F unbuilt): the projection file WAS
              written to :to before the gate fired — the gate aborts the
              manifest/:succeeded-run/future-commit, NOT the on-disk files"
      (is (.exists (io/file to "secret/leak.md"))
          "project-graph wrote the file before verify-written-against-route! refused"))))

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
;; REL-PATH COLLISION (fix-1/2/5/6/10/11) — a rel-path is NOT unique; a :public
;; twin and a :private twin can share one.  A row WITHOUT a carried :entity is
;; resolved over the SET of ALL entities at the rel-path (fail-closed), so the
;; refusal is INDEPENDENT of eid/seed order — a scalar find could fail open.
;; The two rel-paths seed the twins in OPPOSITE orders to prove that.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest public-export-refuses-a-colliding-private-twin-both-seed-orders
  (seed-public-project!)
  (seed-private-project!)
  ;; rel-path A: the PUBLIC twin is seeded FIRST, then the private twin.
  (seed-memory-at! :memory/twin-pub-a :public  :proj/pub    "collide/a.md")
  (seed-memory-at! :memory/twin-sec-a :private :proj/secret "collide/a.md")
  ;; rel-path B: the PRIVATE twin is seeded FIRST, then the public twin.
  (seed-memory-at! :memory/twin-sec-b :private :proj/secret "collide/b.md")
  (seed-memory-at! :memory/twin-pub-b :public  :proj/pub    "collide/b.md")
  (let [db     (db/db)
        refuse (fn [rel-path]
                 (try (prov/manifest-for-export
                        db {:run 1 :project :proj/pub
                            :written [{:rel-path rel-path :written true}] ; NO :entity ⇒ set-find
                            :basis-t (d/basis-t db)})
                      nil
                      (catch clojure.lang.ExceptionInfo e e)))]
    (doseq [[rel-path order] [["collide/a.md" "public-twin-seeded-first"]
                              ["collide/b.md" "private-twin-seeded-first"]]]
      (let [ex (refuse rel-path)]
        (testing (str "a :public export REFUSES the colliding private twin (" order ")")
          (is (some? ex) "the collision must refuse — never fail open on eid/seed order")
          (is (= :public-manifest-contains-private-rows (:sandbar/error (ex-data ex))))
          (is (some #(and (= rel-path (:rel-path %)) (= :private (:sensitivity %)))
                    (:offending-rows (ex-data ex)))
              "the refusal names the colliding rel-path + its :private candidate"))))))

(deftest private-export-refuses-a-colliding-foreign-twin-both-seed-orders
  (seed-private-project!)                                  ; :proj/secret — the TARGET
  (seed-other-private-project!)                            ; :proj/other  — FOREIGN
  ;; rel-path P: the TARGET-scope twin first, then the foreign twin.
  (seed-memory-at! :memory/pin-sec-p :private :proj/secret "collide/p.md")
  (seed-memory-at! :memory/pin-oth-p :private :proj/other  "collide/p.md")
  ;; rel-path Q: the FOREIGN twin first, then the target-scope twin.
  (seed-memory-at! :memory/pin-oth-q :private :proj/other  "collide/q.md")
  (seed-memory-at! :memory/pin-sec-q :private :proj/secret "collide/q.md")
  (let [db     (db/db)
        refuse (fn [rel-path]
                 (try (prov/manifest-for-export
                        db {:run 1 :project :proj/secret                 ; DERIVES [:private :proj/secret]
                            :written [{:rel-path rel-path :written true}]
                            :basis-t (d/basis-t db)})
                      nil
                      (catch clojure.lang.ExceptionInfo e e)))]
    (doseq [[rel-path order] [["collide/p.md" "target-twin-seeded-first"]
                              ["collide/q.md" "foreign-twin-seeded-first"]]]
      (let [ex (refuse rel-path)]
        (testing (str "a :private export REFUSES the colliding FOREIGN twin (" order ")")
          (is (some? ex) "the foreign twin must refuse regardless of seed order")
          (is (= :private-manifest-contains-foreign-rows (:sandbar/error (ex-data ex))))
          (is (some #(and (= rel-path (:rel-path %))
                          (= [:trust-scope/private :proj/other] (:trust-scope %)))
                    (:offending-rows (ex-data ex)))
              "the refusal names the colliding rel-path + the foreign scope"))))))

(deftest carried-source-entity-routes-the-exact-projected-twin
  ;; fix-1/10 PREFERRED path: with the source entity carried on the row
  ;; (project-graph stamps :entity), the gate routes the ACTUAL projected entity
  ;; — so a :public twin colliding at a rel-path with a :private twin is admitted
  ;; on its OWN identity (no over-refusal) while a row carrying the PRIVATE twin
  ;; is refused (no fail-open), even though BOTH share the rel-path.
  (seed-public-project!)
  (seed-private-project!)
  (seed-memory-at! :memory/twp :public  :proj/pub    "collide/x.md")
  (seed-memory-at! :memory/tws :private :proj/secret "collide/x.md")
  (let [db       (db/db)
        ;; the exact descriptor shape project-graph stamps (source-descriptor)
        pub-desc {:dt/type :mm/Memory :mm.memory/owning-project :proj/pub    :db/ident :memory/twp}
        sec-desc {:dt/type :mm/Memory :mm.memory/owning-project :proj/secret :db/ident :memory/tws}
        export   (fn [entity-desc]
                   (prov/manifest-for-export
                     db {:run 1 :project :proj/pub
                         :written [{:rel-path "collide/x.md" :written true :entity entity-desc}]
                         :basis-t (d/basis-t db)}))]
    (testing "a row carrying the PUBLIC twin is ADMITTED (exact — no set-find over-refusal)"
      (is (= :public
             (:manifest/firewall-class (:committed (export pub-desc))))))
    (testing "a row carrying the PRIVATE twin is REFUSED (exact — no fail-open)"
      (let [ex (try (export sec-desc) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :public-manifest-contains-private-rows (:sandbar/error (ex-data ex))))
        (is (some #(and (= "collide/x.md" (:rel-path %)) (= :private (:sensitivity %)))
                  (:offending-rows (ex-data ex))))))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; fix-4/8/13 — the DOUBLE-GATE at the WIRING POINT (project-export-handler).
;; A live :mm/Run mints ONLY when BOTH the per-call `:provenance` opt AND the
;; server-side `recording-enabled?` flag are open — driven end-to-end through
;; the (private) handler var against a mem fixture + scratch :to.  This pins the
;; AND so a regression defaulting `want-record?` true cannot ship silently.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private export-handler #'sandbar.mcp.tools/project-export-handler)

(deftest handler-double-gate-both-locks-open-mints-one-succeeded-run
  ;; The fixture corpus's only rel-path memory is UNASSIGNED, admissible into the
  ;; default :project/UNASSIGNED target — so with BOTH locks open the export
  ;; succeeds and records exactly one :succeeded projection run + a manifest.
  (let [to (scratch-to "gate-on")]
    (with-recording-on*
      (fn []
        (let [result (export-handler {"to" to "provenance" true})
              runs   (prov/projection-runs (db/db))]
          (testing "both locks open ⇒ exactly one projection run is minted"
            (is (= 1 (count runs))))
          (testing "it is a :succeeded projection run"
            (is (= :succeeded (:mm.activity/status (first runs))))
            (is (prov/projection-run? (first runs))))
          (testing "the committed manifest is returned under :provenance, run-linked"
            (is (contains? result :provenance))
            (is (= [:mm/id (:mm/id (first runs))]
                   (:manifest/run (:provenance result))))))))))

(deftest handler-double-gate-any-lock-closed-mints-no-run
  ;; The three CLOSED cells share ONE fresh mem fixture precisely because none of
  ;; them may write a run: after each, ZERO :mm/Run rows must exist and the
  ;; result must carry NO :provenance manifest (a plain read-only projection).
  (let [check
        (fn [label args recording-on?]
          (let [to     (scratch-to (str "gate-" label))
                runner (fn [] (export-handler (assoc args "to" to)))
                result (if recording-on?
                         (with-recording-on* runner)
                         (with-recording-off* runner))]
            (testing (str label " ⇒ no :mm/Run minted")
              (is (empty? (prov/projection-runs (db/db)))))
            (testing (str label " ⇒ no committed manifest surfaced")
              (is (not (contains? result :provenance)))
              (is (contains? result :files)))))]         ; still a real read-only export
    (check "opt-true--flag-OFF"  {"provenance" true}  false)
    (check "opt-FALSE-flag-on"   {"provenance" false} true)
    (check "opt-absent-flag-on"  {}                   true)))
