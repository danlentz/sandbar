(ns sandbar.project.recovery-check-http-test
  "Independent authenticated Pedestal proof of the private origin comparison.
   Every export, audit edit and store is disposable. No live server is contacted."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.test :refer [response-for]]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.project.destination :as destination]
            [sandbar.project.export :as export]
            [sandbar.project.export-integrity :as integrity]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth])
  (:import [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util UUID]))

(def ^:dynamic *root* nil)
(def ^:dynamic *writer* nil)
(def ^:dynamic *reader* nil)
(def ^:dynamic *limited* nil)
(def results (atom []))
(def wire-responses (atom []))
(def nofollow (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- mkdir [name]
  (let [f (io/file *root* name)]
    (.mkdirs f)
    (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rwx------"))
    (.getCanonicalPath f)))

(defn- seed-projects! []
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/priv :private :ctx/priv :project-isolated)
  (sup/raw-transact!
    [{:db/id (sup/eid-of :proj/pub) :db/ident :memory.projects/pub :mm.memory/visibility :public}
     {:db/id (sup/eid-of :proj/priv) :db/ident :memory.projects/priv :mm.memory/visibility :private}]))

(defn- token! [role full?]
  (let [secret (str (UUID/randomUUID)) service (keyword (str "origin-" (UUID/randomUUID)))]
    (dt/make :auth/ServiceAccount
      {:auth/service-name service :auth/api-key-hash (auth/hash-password secret)
       :auth/roles [(tu/ensure-role! role)] :auth/active? true :auth/full-clearance? full?})
    (str (name service) ":" secret)))

(defn- fixture [f]
  ((tu/make-test-db-fixture {:test-name (str "origin-http-" (UUID/randomUUID)) :auth? false})
   (fn []
     (binding [*root* (.getCanonicalFile (.toFile (Files/createTempDirectory "origin-http-" (make-array FileAttribute 0))))]
       (let [pub (mkdir "public-source") priv (mkdir "private-source")
             global (mkdir "global") staging (mkdir "staging") audit (mkdir "audit")
             conf {:project-roots {:proj/pub pub :proj/priv priv}
                   :export-destinations
                   {"public" {:project :proj/pub :audience :public :staging-root staging :audit-root audit}
                    "private" {:project :proj/priv :audience :private :staging-root staging :audit-root audit}}}
             original config/value]
         (seed-projects!)
         (md/register!)
         (binding [*writer* (token! :read-write true)
                   *reader* (token! :read-only true)
                   *limited* (token! :read-only false)]
           (with-redefs [config/value (fn [k] (if (contains? conf k) (get conf k) (original k)))
                         destination/global-root (constantly global)]
             (f))))))))
(use-fixtures :each fixture)

(defn- sha [f]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                         (Files/readAllBytes (.toPath (io/file f)))))))
(defn- filesystem []
  (into (sorted-map)
    (for [f (file-seq *root*) :let [p (.toPath f)]]
      [(.toString (.relativize (.toPath *root*) p))
       {:kind (cond (Files/isSymbolicLink p) :symlink (.isDirectory f) :directory :else :file)
        :hash (when (and (.isFile f) (not (Files/isSymbolicLink p))) (sha f))
        :modified (str (Files/getLastModifiedTime p nofollow))
        :permissions (str (Files/getPosixFilePermissions p nofollow))}])))

(defn- note!
  ([slug visibility project] (note! slug visibility project {}))
  ([slug visibility project extra]
   (let [id (keyword "memory.notes" slug)]
     (sup/seed-memory! id visibility [:mm.project/ident project]
       (merge {:mm/id (UUID/randomUUID) :mm.memory/memory-type :memory
               :mm.memory/rel-path (str "notes/" slug ".md")
               :mm.memory/body-raw (str "Content " slug ".\n")} extra))
     id)))

(defn- request! [token verb args]
  (let [request {:jsonrpc "2.0" :id (inc (count @wire-responses)) :method "tools/call"
                 :params {:name verb :arguments args}}
        response (response-for tu/service :post "/mcp"
                   :headers (cond-> {"Content-Type" "application/json" "Accept" "application/json"}
                              token (assoc "Authorization" (str "Bearer " token)))
                   :body (json/generate-string request))
        envelope (json/parse-string (:body response) true)
        result (:result envelope)
        failed? (boolean (or (not= 200 (:status response)) (:error envelope)
                             (nil? result) (:isError result)))
        data (when-not failed?
               (or (:structuredContent result)
                   (json/parse-string (get-in result [:content 0 :text]) true)))
        receipt {:request request :http-status (:status response) :raw-response (:body response)
                 :error? failed? :envelope envelope :data data}]
    (swap! wire-responses conj receipt)
    receipt))

(defn- call! [token verb args]
  (let [r (request! token verb args)]
    (is (not (:error? r)) (pr-str r))
    (when (:error? r) (throw (ex-info "Unexpected fixture response" r)))
    (:data r)))

(defn- export!
  ([dest child] (export! dest child {}))
  ([dest child extra]
   (let [opts (merge {:project (if (= "public" dest) ":memory.projects/pub" ":memory.projects/priv")
                      :destination dest :to (.getPath (io/file *root* "staging" child))} extra)
         p (call! *writer* "sandbar_project_export" opts)
         r (call! *writer* "sandbar_project_export"
                  (assoc opts :dry-run false :expect-plan (:plan-token p)))
         marker (io/file (:to opts) export/manifest-name)
         manifest (edn/read-string (slurp marker))
         audit (io/file *root* "audit" (str (:manifest/audit-ref manifest) "-ready.edn"))]
     (is (true? (:complete? r)))
     {:opts opts :marker marker :manifest manifest :audit audit
      :audit-value (edn/read-string (slurp audit)) :execution r})))

(defn- check! [token exported & [extra]]
  (let [before (filesystem) basis (db/basis-t)
        opts (:opts exported)
        args (merge {:project (:project opts) :destination (:destination opts) :from (:to opts)} extra)
        r (request! token "sandbar_project_recovery-check" args)
        raw (:raw-response r)]
    (is (= basis (db/basis-t)) "Comparison never transacts")
    (is (= before (filesystem)) "Comparison changes no file bytes, paths, mtime or permissions")
    (doseq [secret [(.getPath *root*) (.id ^datomic.Database (db/db)) "HeldPrivateSentinel"]]
      (is (not (str/includes? raw secret)) "No private origin, root or held identity in response"))
    r))

(defn- state! [token exported state & [extra]]
  (let [r (check! token exported extra) data (:data r)]
    (is (not (:error? r)) (pr-str r))
    (is (= state (:state data)))
    (is (= "checked" (:status data)))
    (is (= "snapshot-comparison" (:scope data)))
    (is (false? (:import-approved? data)))
    (is (= (sha (:marker exported)) (:manifest-sha256 data)))
    (swap! results conj {:case state :data data})
    data))

(deftest real-origin-read-only-route-and-pre-read-authorization
  (note! "selected" :public :proj/pub)
  (note! "HeldPrivateSentinel" :private :proj/pub)
  (let [e (export! "public" "real-public")
        a (:audit-value e)
        m (:manifest e)
        id (.id ^datomic.Database (db/db))]
    (is (= id (get-in a [:audit/plan :store-id])))
    (is (= (:manifest/basis-t m) (get-in a [:audit/plan :basis])))
    (is (= (sha (:marker e)) (:audit/manifest-sha256 a)))
    (is (contains? (:audit/plan a) :filter))
    (is (not (str/includes? (slurp (:marker e)) id)))
    (is (= 1 (:file-count (state! *reader* e "same-store-same-basis"))))
    (is (= 1 (:held-count (state! *reader* e "same-store-same-basis"
                                  {:expect-manifest (sha (:marker e))}))))
    ;; Even a full-clearance read-only credential must still be unable to
    ;; perform the export (whose preview writes a private audit).
    (let [before (filesystem) basis (db/basis-t)
          denied (request! *reader* "sandbar_project_export"
                           (assoc (:opts e) :to (.getPath (io/file *root* "staging" "denied"))))]
      (is (:error? denied))
      (is (= before (filesystem)))
      (is (= basis (db/basis-t))))
    ;; Sentinels make the pre-read order observable, rather than inferring it
    ;; from a status label. Neither destination traversal nor tree read may run.
    (let [reads (atom 0)]
      (with-redefs [export/destination! (fn [& _] (swap! reads inc) (throw (ex-info "SECRET" {})))
                    integrity/verify-tree* (fn [& _] (swap! reads inc) (throw (ex-info "SECRET" {})))]
        (let [limited (check! *limited* e) missing (check! nil e)]
          (is (true? (get-in limited [:envelope :result :isError])))
          (is (= 401 (:http-status missing)))
          (is (zero? @reads)))))
    (sup/raw-transact! [{:db/ident :memory.notes/unrelated :dt/type :mm/Memory
                         :mm.memory/body-raw "Unrelated transaction."}])
    (state! *reader* e "store-advanced")
    (sup/raw-transact! [{:db/ident :memory.notes/selected :mm.memory/body-raw "Edited since export."}])
    (state! *reader* e "store-advanced")))

(deftest old-missing-hostile-audits-integrity-refusals-and-constructed-lower-basis
  (note! "selected" :public :proj/pub)
  (let [e (export! "public" "audit-controls")
        af (:audit e) original (slurp af) marker (:marker e) marker-original (slurp marker)
        rewrite! (fn [value] (spit af (pr-str value)))
        restore! (fn [] (spit af original)
                         (Files/setPosixFilePermissions (.toPath af)
                           (PosixFilePermissions/fromString "rw-------"))
                         (spit marker marker-original))]
    (try
      (rewrite! (-> (:audit-value e) (dissoc :audit/manifest-sha256)
                    (update :audit/plan dissoc :store-id)))
      (state! *reader* e "origin-unverified")
      (restore!)
      (Files/delete (.toPath af))
      (state! *reader* e "origin-unverified")
      (restore!)
      (spit af "{} {}")
      (state! *reader* e "origin-unverified")
      (restore!)
      (rewrite! (assoc (:audit-value e) :audit/manifest-sha256 (apply str (repeat 64 "0"))))
      (state! *reader* e "origin-unverified")
      (restore!)
      (let [outside (io/file *root* "outside-ready.edn")]
        (spit outside original)
        (Files/delete (.toPath af))
        (Files/createSymbolicLink (.toPath af) (.toPath outside) (make-array FileAttribute 0))
        (state! *reader* e "origin-unverified")
        (Files/delete (.toPath af))
        (restore!))
      (doseq [ref ["../../HeldPrivateSentinel" "" "not-a-uuid"]]
        (spit marker (pr-str (assoc (:manifest e) :manifest/audit-ref ref)))
        (state! *reader* e "origin-unverified")
        (restore!))
      ;; Construct a consistent future receipt solely to exercise the lower
      ;; current-basis branch. This is NOT a restore or replica experiment.
      (let [future-basis (+ 7 (db/basis-t))]
        (spit marker (pr-str (assoc (:manifest e) :manifest/basis-t future-basis)))
        (rewrite! (-> (:audit-value e)
                       (assoc-in [:audit/plan :basis] future-basis)
                       (assoc :audit/manifest-sha256 (sha marker))))
        (state! *reader* e "store-behind-export")
        (restore!))
      (let [r (check! *reader* e {:expect-manifest (apply str (repeat 64 "f"))})]
        (is (true? (get-in r [:envelope :result :isError])))
        (is (nil? (:data r))))
      (let [file (io/file (get-in e [:opts :to]) "notes/selected.md")]
        (spit file "Changed bytes.")
        (let [r (check! *reader* e)]
          (is (true? (get-in r [:envelope :result :isError])))
          (is (nil? (:data r)))))
      (finally (restore!)))))

(deftest independent-store-and-filtered-private-coverage
  (note! "selected-private" :private :proj/priv
         {:dt/type :mm/Observation :mm.memory/memory-type :observation})
  (note! "not-selected-by-class" :private :proj/priv)
  (let [e (export! "private" "private-filter" {:filter {:class ":mm/Observation"}})
        origin (.id ^datomic.Database (db/db))]
    (is (= {:class :mm/Observation} (get-in e [:audit-value :audit/plan :filter])))
    (let [r (state! *reader* e "same-store-same-basis")]
      (is (= "private" (:audience r)))
      (is (= 1 (:file-count r))) (is (= 0 (:held-count r))))
    ;; Separate actual in-memory DB with its own schema/project and credential.
    ;; Preserve the outer store's connection binding; no identity is fabricated.
    (binding [db/**conn* (atom nil)]
      ((tu/make-test-db-fixture {:test-name (str "origin-http-other-" (UUID/randomUUID))
                                :auth? false})
       (fn []
         (seed-projects!)
         (let [reader (token! :read-only true)]
           (is (not= origin (.id ^datomic.Database (db/db))))
           (state! reader e "different-store")))))))
