(ns sandbar.project.recovery-check-test
  "Isolated proof of the private export-origin receipt and the read-only
   same-store comparison (task implement_private_origin_comparison_for_
   completed_exports_2026_09_21; cases CR-01–22 and CR-24 of
   checkpoint-recovery-entry/opus-acceptance-cases.edn with the lead
   dispositions).  Every export here is a real guarded export into a
   temporary destination; every comparison runs the real check, in-process
   through `recovery-check/check` and through `tools/handle-call` with an
   explicit principal.  Constructed receipts (an edited ready audit and, where
   the association demands it, a re-serialized marker with the audit's hash
   updated) stand in for a restored or foreign store: no restore, import, git
   or live call happens."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.mcp.tools :as tools]
            [sandbar.project.export :as export]
            [sandbar.project.export-integrity :as integrity]
            [sandbar.project.provenance :as prov]
            [sandbar.project.recovery-check :as rc]
            [sandbar.security.visibility :as visibility]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util UUID]))

(def ^:dynamic *root* nil)
(def ^:dynamic *config* nil)

(def operator "A full-clearance writer." {:auth/full-clearance? true :auth/roles [{:auth/role-name :read-write}]})
(def reviewer "A full-clearance READ-ONLY principal; must succeed." {:auth/full-clearance? true :auth/roles [{:auth/role-name :read-only}]})
(def limited "Authenticated, cleared for nothing private." {:auth/full-clearance? false :auth/roles [{:auth/role-name :read-only}]})

(defn- mkdir [name]
  (let [f (io/file *root* name)]
    (.mkdirs f)
    (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rwx------"))
    (.getPath f)))

(defn- fixture [f]
  ;; A CANONICAL root: the exporter and the check accept a staging child only
  ;; when the path handed to them already equals its canonical form, and a
  ;; macOS TMPDIR under /var is an alias of /private/var.
  (binding [*root* (.getCanonicalFile (.toFile (Files/createTempDirectory "recovery-check-" (make-array FileAttribute 0))))]
    (let [pub (mkdir "pub-source") priv (mkdir "private-source") staging (mkdir "staging")
          audit (mkdir "audit")]
      ((tu/make-test-db-fixture {:test-name "recovery-check" :auth? false})
       (fn []
         (sup/seed-context! :ctx/pub :public-bottom)
         (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
         (sup/seed-context! :ctx/priv :project-isolated)
         (sup/seed-project! :proj/priv :private :ctx/priv :project-isolated)
         (sup/raw-transact! [{:db/id (sup/eid-of :proj/pub) :db/ident :memory.projects/pub :mm.memory/visibility :public}
                            {:db/id (sup/eid-of :proj/priv) :db/ident :memory.projects/priv :mm.memory/visibility :private}])
         (binding [*config* {:project-roots {:proj/pub pub :proj/priv priv}
                            :export-destinations
                            {"public" {:project :proj/pub :audience :public :staging-root staging :audit-root audit}
                             "private" {:project :proj/priv :audience :private :staging-root staging :audit-root audit}}}]
           (let [original config/value]
             (with-redefs [config/value (fn [k] (if (contains? *config* k) (get *config* k) (original k)))]
               (f)))))))))
(use-fixtures :each fixture)

(defn- note!
  ([name visibility project] (note! name visibility project {}))
  ([name visibility project extra]
   (let [ident (keyword "memory.notes" name)]
     (sup/seed-memory! ident visibility [:mm.project/ident project]
       (merge {:mm/id (UUID/randomUUID) :mm.memory/memory-type :memory
               :mm.memory/rel-path (str "notes/" name ".md")
               :mm.memory/body-raw (str "Body " name ".\n")} extra))
     ident)))

(defn- opts [dest child]
  {:project (if (= dest "public") :memory.projects/pub :memory.projects/priv)
   :destination dest :to (.getPath (io/file *root* "staging" child))})

(defn- export!
  "Preview then execute the guarded export as the operator; returns
   {:preview :execution :from :opts}."
  ([dest child] (export! dest child {}))
  ([dest child extra]
   (binding [visibility/*principal* operator]
     (let [o (merge (opts dest child) extra)
           p (export/export! o)
           r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))]
       (when-not (true? (:complete? r)) (throw (ex-info "fixture export incomplete" {:preview p :execution r})))
       {:preview p :execution r :from (:to o) :opts o}))))

(defn- check
  ([e] (check e operator))
  ([{:keys [from opts]} principal & [extra]]
   (binding [visibility/*principal* principal]
     (rc/check (merge {:project (:project opts) :destination (:destination opts) :from from} extra)))))

(defn- refusal
  "The sanitized refusal data of a check, or nil when it succeeded."
  [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo ex (ex-data ex))))

(defn- marker-file [e] (io/file (:from e) export/manifest-name))
(defn- marker-sha [e] (integrity/sha-file (.toPath (marker-file e))))
(defn- audit-file
  "The execution's audit for `phase`. Preview and execution mint separate
   audit references, so the preview audit is addressed by the preview's ref."
  [e phase]
  (let [ref (if (= :preview phase) (:audit-ref (:preview e)) (:audit-ref (:execution e)))]
    (io/file *root* "audit" (str ref "-" (name phase) ".edn"))))
(defn- read-edn [f] (edn/read-string (slurp f)))
(defn- rewrite-edn! [f g]
  (let [m (read-edn f)] (spit f (pr-str (g m)))))
(defn- rewrite-audit! [e g] (rewrite-edn! (audit-file e :ready) g))
(defn- rewrite-marker!
  "Re-serialize the marker through `g` and rebind the ready audit to the new
   bytes, so only the deliberate change is visible to the association."
  [e g]
  (rewrite-edn! (marker-file e) g)
  (rewrite-audit! e #(assoc % :audit/manifest-sha256 (marker-sha e))))
(defn- tree-snapshot [dir]
  (into {} (map (fn [f] [(.getPath f) (integrity/sha-file (.toPath f))]))
        (filter #(.isFile %) (file-seq (io/file dir)))))
(defn- live-id [] (.id ^datomic.Database (db/db)))
(defn- live-basis [] (d/basis-t (db/db)))
(def payload-keys #{:status :scope :state :file-count :audience :held-count :manifest-sha256 :import-approved?})

;;; ── CR-01 / CR-02: the identity facts ───────────────────────────────────────

(deftest source-database-identity-is-stable-in-a-store-and-differs-across-stores
  (let [id0 (live-id) t0 (live-basis)]
    (note! "one" :public :proj/pub)
    (note! "two" :public :proj/pub)
    (note! "three" :public :proj/pub)
    (is (= id0 (live-id)) "identity survives ordinary transactions")
    (is (< t0 (live-basis)) "basis advances")
    (is (= id0 (.id ^datomic.Database (d/db (d/connect "datomic:mem://recovery-check")))) "identity survives a reconnect")
    (let [uri (str "datomic:mem://recovery-check-other-" (UUID/randomUUID))]
      (d/create-database uri)
      (try
        (is (not= id0 (.id ^datomic.Database (d/db (d/connect uri)))) "an independent store has another identity")
        (finally (d/delete-database uri))))))

;;; ── CR-03 – CR-06: the private receipt and the public marker ────────────────

(deftest ready-audit-binds-store-identity-selection-and-exact-marker-bytes
  (note! "safe" :public :proj/pub)
  (let [e (export! "public" "one" {:filter {:class :mm/Memory}})
        ready (read-edn (audit-file e :ready))
        preview (read-edn (audit-file e :preview))
        m (read-edn (marker-file e))]
    (testing "CR-03: the ready audit carries the store identity, the basis and the exact marker hash"
      (is (= (live-id) (get-in ready [:audit/plan :store-id])))
      (is (= (:basis (:preview e)) (get-in ready [:audit/plan :basis]) (:manifest/basis-t m)))
      (is (= (marker-sha e) (:audit/manifest-sha256 ready)) "CR-04: the audited hash is the bytes on disk")
      (is (= {:class :mm/Memory} (get-in ready [:audit/plan :filter])) "the selection context is retained")
      (is (= (live-id) (get-in preview [:audit/plan :store-id])) "the preview audit names the store too"))
    (testing "CR-03: the public marker is unchanged in shape — no identity travels with the tree"
      (is (not (contains? m :manifest/store-id)))
      (is (not (contains? m :manifest/exporter)))
      (is (not (str/includes? (slurp (marker-file e)) (live-id))))
      (is (= :verified (:status (integrity/verify-tree (:from e) nil)))))
    (testing "CR-05: the ready audit precedes the marker; a failed marker write leaves no marker"
      (note! "second" :public :proj/pub)
      (binding [visibility/*principal* operator]
        (let [o (opts "public" "two") p (export/export! o) original export/write-new!]
          (with-redefs [export/write-new! (fn [file content private?]
                                            (when (str/includes? (str file) export/manifest-name)
                                              (throw (ex-info "marker write failed" {})))
                                            (original file content private?))]
            (let [r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))]
              (is (= :incomplete (:status r)))
              (is (not (.exists (io/file (:to o) export/manifest-name))))
              (is (.isFile (io/file *root* "audit" (str (:audit-ref r) "-ready.edn"))))
              (is (.isFile (io/file *root* "audit" (str (:audit-ref r) "-incomplete.edn"))))
              (is (= :missing-manifest (:reason (refusal #(integrity/verify-tree (:to o) nil))))))))))))

(deftest preview-token-and-execution-are-bound-to-the-store-identity
  (note! "safe" :public :proj/pub)
  (binding [visibility/*principal* operator]
    (let [o (opts "public" "one") p (export/export! o) original export/plan
          audits-before (count (.listFiles (io/file *root* "audit")))]
      (testing "CR-06: a plan whose identity is not this database's stops before any effect"
        (with-redefs [export/plan (fn [& args] (assoc (apply original args) :store-id "another-database"))]
          (let [r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))]
            ;; The token was minted by the real plan, so the token check passes
            ;; (a mismatch would have thrown :preview-changed); the basis and the
            ;; destination are unchanged, so the internal :store-changed guard is
            ;; the one that fires. It fires inside the exporter's prewrite/audit
            ;; boundary, whose PUBLIC outcome is the incomplete status — the same
            ;; contract as its :basis-changed and :destination-changed siblings.
            (is (= {:status :incomplete :complete? false :exported-count 0
                    :reason :prewrite-or-audit-failed}
                   (select-keys r [:status :complete? :exported-count :reason]))
                (pr-str r))))
        (is (not (.exists (io/file (:to o)))))
        (is (= audits-before (count (.listFiles (io/file *root* "audit")))) "nothing was audited or written")))))

;;; ── CR-07 – CR-12: the five states on real exports ──────────────────────────

(deftest same-store-same-basis-when-nothing-moved-and-nothing-is-touched
  (note! "safe" :public :proj/pub)
  (let [e (export! "public" "one")
        tree (tree-snapshot (:from e)) audits (tree-snapshot (io/file *root* "audit"))
        basis (live-basis)
        r (check e)]
    (is (= :same-store-same-basis (:state r)) (pr-str r))
    (is (= payload-keys (set (keys r))) "the fixed payload, nothing more")
    (is (= {:status :checked :scope :snapshot-comparison :file-count 1 :audience :public
            :held-count 0 :manifest-sha256 (marker-sha e) :import-approved? false}
           (dissoc r :state)))
    (testing "CR-20: no effect anywhere"
      (is (= basis (live-basis)))
      (is (= tree (tree-snapshot (:from e))))
      (is (= audits (tree-snapshot (io/file *root* "audit")))))
    (testing "the pinned form and the read-only reviewer both succeed"
      (is (= :same-store-same-basis (:state (check e operator {:expect-manifest (marker-sha e)}))))
      (is (= :same-store-same-basis (:state (check e reviewer))))
      (is (= :same-store-same-basis (:state (check e reviewer {:expect-manifest (str/upper-case (marker-sha e))})))))))

(deftest store-advanced-by-an-unrelated-write-by-the-export-s-own-record-and-by-an-edit
  (let [safe (note! "safe" :public :proj/pub)
        e (export! "public" "one")]
    (testing "CR-08: an unrelated write advances the store; no count, no claim about the files"
      (note! "unrelated" :public :proj/pub)
      (let [r (check e)]
        (is (= :store-advanced (:state r)))
        (is (not (contains? r :reason)))
        (is (= payload-keys (set (keys r))))))
    (testing "CR-09: the export's own provenance record advances the store immediately"
      (with-redefs [prov/recording-enabled? (constantly true)]
        (let [e2 (export! "public" "two" {:provenance true})]
          (is (true? (:provenance-recorded? (:execution e2))))
          (is (= :store-advanced (:state (check e2)))))))
    (testing "CR-10: an edit to an exported document is also :store-advanced, and nothing is imported"
      (let [e3 (export! "public" "three")
            before (slurp (io/file (:from e3) "notes/safe.md"))]
        (sup/raw-transact! [{:db/ident safe :mm.memory/body-raw "Body safe, edited after the export.\n"}])
        (is (= :store-advanced (:state (check e3))))
        (is (= before (slurp (io/file (:from e3) "notes/safe.md"))) "the tree keeps the exported text")
        (is (= "Body safe, edited after the export.\n" (:mm.memory/body-raw (db/entity safe))) "the store keeps the edit")))))

(deftest store-behind-export-and-different-store-from-constructed-receipts
  (note! "safe" :public :proj/pub)
  (testing "CR-11: same identity, recorded basis ahead of the live one"
    (let [e (export! "public" "one") ahead (+ (live-basis) 100)]
      (rewrite-marker! e #(assoc % :manifest/basis-t ahead))
      (rewrite-audit! e #(assoc-in % [:audit/plan :basis] ahead))
      (let [r (check e)]
        (is (= :store-behind-export (:state r)) (pr-str r))
        (is (not (contains? r :reason))))))
  (testing "CR-12: another identity; bases are not compared"
    (let [e (export! "public" "two")]
      (rewrite-audit! e #(assoc-in % [:audit/plan :store-id] "some-other-database"))
      (is (= :different-store (:state (check e))))
      (rewrite-audit! e #(-> (assoc-in % [:audit/plan :store-id] "some-other-database")
                             (assoc-in [:audit/plan :basis] 1)))
      (rewrite-marker! e #(assoc % :manifest/basis-t 1))
      (is (= :different-store (:state (check e))) "a lower basis on another identity is still :different-store"))))

;;; ── CR-13 – CR-17: origin unverified ─────────────────────────────────────────

(deftest receipts-that-are-old-missing-elsewhere-malformed-or-disagreeing-are-origin-unverified
  (note! "safe" :public :proj/pub)
  (let [e (export! "public" "one")
        unverified (fn [] (let [r (check e)] (is (= :origin-unverified (:state r)) (pr-str r)) (:reason r)))]
    (testing "CR-13: a receipt from before origin binding"
      (let [saved (slurp (audit-file e :ready))]
        (rewrite-audit! e #(-> (dissoc % :audit/manifest-sha256) (update :audit/plan dissoc :store-id)))
        (is (= :receipt-predates-origin-binding (unverified)))
        (spit (audit-file e :ready) saved)
        (is (= :same-store-same-basis (:state (check e))) "restored, the receipt verifies again")))
    (testing "CR-14: an audit beside the child is never read; only the configured root counts"
      (let [saved (slurp (audit-file e :ready))
            beside (io/file *root* "staging" (.getName (audit-file e :ready)))]
        (spit beside saved)
        (.delete (audit-file e :ready))
        (is (= :audit-missing (unverified)))
        (.delete beside)
        (spit (audit-file e :ready) saved)
        (Files/setPosixFilePermissions (.toPath (audit-file e :ready)) (PosixFilePermissions/fromString "rw-------"))))
    (testing "CR-15: the audit reference never becomes a raw path"
      (let [ref (:audit-ref (:execution e))]
        (doseq [bad ["../../x" "" "not-a-uuid" 42 nil]]
          (rewrite-marker! e #(assoc % :manifest/audit-ref bad))
          (is (= :audit-ref-malformed (unverified)) (pr-str bad)))
        ;; The loop leaves the reference nil; the canonicalization case sets
        ;; the real reference in upper case explicitly.
        (rewrite-marker! e #(assoc % :manifest/audit-ref (str/upper-case ref)))
        (is (= :same-store-same-basis (:state (check e))) "an upper-case reference resolves to the same audit")
        (rewrite-marker! e #(assoc % :manifest/audit-ref ref))))
    (testing "CR-16: association mismatches name the failing association, not the values"
      (rewrite-edn! (marker-file e) #(assoc % :manifest/note "edited after the receipt"))
      (is (= :manifest-hash-mismatch (unverified)))
      (rewrite-marker! e #(dissoc % :manifest/note))
      (doseq [[reason g] [[:audit-phase-mismatch #(assoc % :audit/phase :prepared)]
                          [:audit-ref-mismatch #(assoc % :audit/ref (str (UUID/randomUUID)))]
                          [:destination-mismatch #(assoc-in % [:audit/plan :destination :name] "other")]
                          [:audience-mismatch #(assoc-in % [:audit/plan :destination :audience] :private)]
                          [:project-mismatch #(assoc-in % [:audit/plan :destination :project-key] :proj/priv)]
                          [:file-list-mismatch #(assoc % :audit/files [])]
                          [:basis-mismatch #(update-in % [:audit/plan :basis] inc)]]]
        (let [saved (slurp (audit-file e :ready))]
          (rewrite-audit! e g)
          (is (= reason (unverified)))
          (spit (audit-file e :ready) saved))))
    (testing "CR-17: malformed identity or basis in the receipt"
      (doseq [[reason g] [[:store-id-malformed #(assoc-in % [:audit/plan :store-id] 42)]
                          [:store-id-malformed #(assoc-in % [:audit/plan :store-id] "")]
                          [:basis-malformed #(assoc-in % [:audit/plan :basis] "12")]
                          [:basis-malformed #(assoc-in % [:audit/plan :basis] -1)]
                          [:manifest-hash-malformed #(assoc % :audit/manifest-sha256 "abc")]]]
        (let [saved (slurp (audit-file e :ready))]
          (rewrite-audit! e g)
          (is (= reason (unverified)))
          (spit (audit-file e :ready) saved))))
    (testing "an audit that is a symlink, not private, or not one EDN map"
      (let [f (audit-file e :ready) saved (slurp f) copy (io/file *root* "audit" "copy.edn")]
        (spit copy saved)
        (.delete f)
        (Files/createSymbolicLink (.toPath f) (.toPath copy) (make-array FileAttribute 0))
        (is (= :audit-symlink (unverified)))
        (Files/delete (.toPath f))
        (.delete copy)
        (spit f saved)
        (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-r--r--"))
        (is (= :audit-not-private (unverified)))
        (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rw-------"))
        (spit f (str saved " {:second :form}"))
        (is (= :audit-malformed (unverified)))
        (spit f "{:audit/ref #unknown/tag 1}")
        (is (= :audit-malformed (unverified)))
        (spit f saved)
        (is (= :same-store-same-basis (:state (check e))))))))

;;; ── CR-18 – CR-21: authorization first, refusals, no leakage ─────────────────

(deftest authorization-precedes-every-read-and-keeps-its-own-envelope
  (note! "safe" :public :proj/pub)
  (let [e (export! "public" "one")]
    (testing "CR-18: nil and uncleared principals are refused before any artifact read (sentinel)"
      (with-redefs [integrity/verify-tree* (fn [& _] (throw (ex-info "an artifact was read" {:sentinel true})))]
        (doseq [[p reason] [[nil :authentication-required] [limited :private-origin-clearance-required]]]
          (let [r (refusal #(check e p))]
            (is (= :recovery-check-refusal (:sandbar/error r)) (pr-str r))
            (is (= reason (:reason r)))
            (is (= :authorization (:phase r)))
            (is (nil? (:sentinel r)) "no read happened before authorization")))))
    (testing "CR-18: a cleared read-only principal succeeds, in-process and through the dispatch gate"
      (is (= :same-store-same-basis (:state (check e reviewer))))
      (let [args {"project" ":memory.projects/pub" "destination" "public" "from" (:from e)}
            response (tools/handle-call 1 {:name "sandbar_project_recovery-check" :arguments args} reviewer)
            body (json/parse-string (get-in response [:result :content 0 :text]) true)]
        (is (nil? (:error response)) (pr-str response))
        (is (not (get-in response [:result :isError])))
        (is (= "checked" (:status body)))
        (is (= "same-store-same-basis" (:state body)))
        (is (false? (:import-approved? body)))
        (is (= (marker-sha e) (:manifest-sha256 body)))))
    (testing "through the gate, a missing principal is an isError refusal, not a JSON-RPC failure"
      (let [args {"project" ":memory.projects/pub" "destination" "public" "from" (:from e)}
            response (tools/handle-call 1 {:name "sandbar_project_recovery-check" :arguments args})
            body (json/parse-string (get-in response [:result :content 0 :text]) true)]
        (is (nil? (:error response)))
        (is (true? (get-in response [:result :isError])))
        (is (= "authentication-required" (get-in body [:details :reason])))))
    (testing "the verb classifies read-only, so the dispatch gate admits a read-only role"
      (is (tools/verb-permitted-for-read-only? "sandbar.project.recovery-check")))))

(deftest integrity-configuration-and-pin-failures-are-refusals-not-states
  (note! "safe" :public :proj/pub)
  (let [e (export! "public" "one")
        refused (fn [thunk] (let [r (refusal thunk)]
                              (is (= :recovery-check-refusal (:sandbar/error r)) (pr-str r))
                              [(:reason r) (:phase r)]))]
    (testing "CR-19: a wrong pin, a changed file, a missing marker"
      (let [real (marker-sha e)
            ;; Deterministically different: the first digit becomes another digit.
            wrong (str (if (= \0 (first real)) "1" "0") (subs real 1))]
        (is (not= real wrong))
        (is (= [:manifest-pin-mismatch :integrity] (refused #(check e operator {:expect-manifest wrong})))))
      (is (= [:invalid-manifest-pin :integrity] (refused #(check e operator {:expect-manifest "short"}))))
      (let [f (io/file (:from e) "notes/safe.md") saved (slurp f)]
        (spit f (str saved "tampered\n"))
        (is (= [:file-hash-mismatch :integrity] (refused #(check e))))
        (spit f saved))
      (let [m (marker-file e) saved (slurp m)]
        (.delete m)
        (is (= [:missing-manifest :integrity] (refused #(check e))))
        (spit m saved)))
    (testing "the child must be an existing real direct child of the configured staging root"
      (let [link (io/file *root* "staging" "link")]
        (Files/createSymbolicLink (.toPath link) (.toPath (io/file (:from e))) (make-array FileAttribute 0))
        (doseq [from [(.getPath link)
                      (.getPath (io/file *root* "staging" "absent"))
                      (.getPath (io/file *root* "pub-source"))
                      (str (:from e) "/notes")
                      "relative/child" nil 7]]
          (is (= [:existing-staging-child-required :destination]
                 (refused #(check (assoc e :from from))))
              (pr-str from)))
        (Files/delete (.toPath link))))
    (testing "the project and destination must be the enrolled, configured pair"
      (is (= [:unauthorized-destination :destination]
             (refused #(check (assoc-in e [:opts :destination] "private")))))
      (is (= [:explicit-enrolled-project-required :destination]
             (refused #(check (assoc-in e [:opts :project] :memory.projects/absent)))))
      (is (= [:unauthorized-destination :destination]
             (refused #(check (assoc-in e [:opts :destination] "unconfigured"))))))
    (testing "an unexpected failure is a sanitized request refusal"
      (with-redefs [integrity/verify-tree* (fn [& _] (throw (RuntimeException. "/private/leaky/path")))]
        (let [r (refusal #(check e))]
          (is (= [:unreadable-tree :integrity] [(:reason r) (:phase r)]))
          (is (not (str/includes? (pr-str r) "leaky"))))))))

(deftest no-identity-path-or-held-name-leaks-from-any-result
  (let [secret (note! "SecretSentinel" :private :proj/pub)
        _ (note! "clean" :public :proj/pub)
        _ (note! "cites-secret" :public :proj/pub {:mm.memory/cites [secret]})
        e (export! "public" "one")
        results (atom [])]
    (swap! results conj (check e))
    (note! "later" :public :proj/pub)
    (swap! results conj (check e))
    (rewrite-audit! e #(assoc-in % [:audit/plan :store-id] "other"))
    (swap! results conj (check e))
    (rewrite-audit! e #(dissoc % :audit/manifest-sha256))
    (swap! results conj (check e))
    (swap! results conj (refusal #(check e limited)))
    (swap! results conj (refusal #(check (assoc e :from (.getPath (io/file *root* "staging" "absent"))))))
    (let [text (pr-str @results)]
      (testing "CR-21"
        (is (not (str/includes? text (live-id))))
        (is (not (str/includes? text "SecretSentinel")))
        (is (not (str/includes? text (.getPath *root*))))
        (is (not (str/includes? text "audit-root")))
        (is (= [:same-store-same-basis :store-advanced :different-store :origin-unverified]
               (mapv :state (take 4 @results))))))))

;;; ── CR-22: coverage is the listed files at this audience ────────────────────

(deftest coverage-statements-for-public-held-and-filtered-exports
  (let [secret (note! "SecretSentinel" :private :proj/pub)
        _ (note! "clean" :public :proj/pub)
        _ (note! "cites-secret" :public :proj/pub {:mm.memory/cites [secret]})
        _ (note! "private-work" :private :proj/priv)
        _ (note! "private-second" :private :proj/priv)
        ;; Deliberately inconsistent: an :mm/Memory whose memory-type names
        ;; another class. The exporter re-parses its rendering as that class
        ;; and holds it (:round-trip-identity-or-content, class not preserved).
        _ (note! "private-odd" :private :proj/priv {:mm.memory/memory-type :observation})]
    (testing "a public export with held documents is a valid selected-document input, holds counted, never named"
      ;; The private document is held for its label; the public document that
      ;; cites it is held for the unsafe reference. One document exports.
      (let [e (export! "public" "one") r (check e)]
        (is (= 2 (:held-count (:preview e))) (pr-str (:preview e)))
        (is (= :same-store-same-basis (:state r)))
        (is (= 1 (:file-count r)))
        (is (= 2 (:held-count r)))
        (is (= :public (:audience r)))
        (is (not (str/includes? (pr-str r) "SecretSentinel")))))
    (testing "a private export covers its listed files; a filtered export covers fewer and counts only its own holds"
      (let [whole (export! "private" "two")
            narrowed (export! "private" "three" {:filter {:class :mm/Memory :tree-filter "notes/private-work"}})
            rw (check whole) rn (check narrowed)
            rows (:audit/documents (read-edn (audit-file whole :ready)))
            odd (first (filter #(= :memory.notes/private-odd (:entity %)) rows))]
        (is (= :private (:audience rw)))
        (is (= 2 (:file-count rw)) (pr-str (:preview whole)))
        (is (= 1 (:held-count rw)) "the inconsistent document is held, counted, not named")
        (is (= :held (:status odd)) (pr-str odd))
        (is (= :round-trip-identity-or-content (:reason odd)) "the hold reason, read from the private ready audit")
        (is (false? (get-in odd [:details :class-preserved?])))
        (is (= 1 (:file-count rn)) (pr-str (:preview narrowed)))
        (is (= 0 (:held-count rn)) "a hold outside the selection is not this export's hold")
        (is (not (str/includes? (pr-str [rw rn]) "private-odd")))
        (is (every? #(= :same-store-same-basis (:state %)) [rw rn]) "both were the latest write; still only a snapshot fact")))))

;;; ── CR-24: identity is the tree and the marker hash, never a corpus sha ───────

(deftest the-result-identifies-bytes-by-the-marker-hash-alone
  (note! "safe" :public :proj/pub)
  (let [e (export! "public" "one") r (check e)]
    (is (= (marker-sha e) (:manifest-sha256 r)))
    (is (not-any? #(re-find #"(?i)corpus|commit|sha\b|git" (name %)) (keys r)))
    (is (= :snapshot-comparison (:scope r)))
    (is (false? (:import-approved? r)))))
