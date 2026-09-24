(ns sandbar.project.export-import-test
  "Credentialed guarded export -> maintenance import into the SAME store.
   Synthetic stopped-writer fixture. This is document composition, not W1.G
   store replacement or a manifest-enforced restore protocol."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.import :as import]
            [sandbar.project.destination :as destination]
            [sandbar.project.export :as export]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.security MessageDigest]
           [java.util UUID]))

(def ^:dynamic *root* nil)
(def ^:dynamic *token* nil)
(def wire-responses (atom []))
(def results (atom []))

(defn- mkdir [s]
  (let [f (io/file *root* s)]
    (.mkdirs f)
    (Files/setPosixFilePermissions (.toPath f)
      (PosixFilePermissions/fromString "rwx------"))
    (.getCanonicalPath f)))

(defn- bearer! []
  (let [secret (str (UUID/randomUUID))
        service (keyword (str "composition-" (UUID/randomUUID)))]
    (dt/make :auth/ServiceAccount
      {:auth/service-name service :auth/api-key-hash (auth/hash-password secret)
       :auth/roles [(tu/ensure-role! :read-write)]
       :auth/active? true :auth/full-clearance? true})
    (str (name service) ":" secret)))

(defn- fixture [f]
  ((tu/make-test-db-fixture {:test-name "export-import" :auth? false})
   (fn []
     (binding [*root* (.getCanonicalFile (.toFile (Files/createTempDirectory
                                "export-import-" (make-array FileAttribute 0))))]
       (let [pub (mkdir "public-source") priv (mkdir "private-source")
             global (mkdir "global") staging (mkdir "staging") audit (mkdir "audit")
             original config/value
             conf {:project-roots {:proj/pub pub :proj/priv priv}
                   :export-destinations
                   {"public" {:project :proj/pub :audience :public
                              :staging-root staging :audit-root audit}
                    "private" {:project :proj/priv :audience :private
                               :staging-root staging :audit-root audit}}}]
         (sup/seed-context! :ctx/pub :public-bottom)
         (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
         (sup/seed-context! :ctx/priv :project-isolated)
         (sup/seed-project! :proj/priv :private :ctx/priv :project-isolated)
         (sup/raw-transact!
           [{:db/id (sup/eid-of :proj/pub) :db/ident :memory.projects/pub
             :mm.memory/visibility :public}
            {:db/id (sup/eid-of :proj/priv) :db/ident :memory.projects/priv
             :mm.memory/visibility :private}])
         (md/register!)
         (binding [*token* (bearer!)]
           (with-redefs [config/value (fn [k] (if (contains? conf k)
                                              (get conf k) (original k)))
                         destination/global-root (constantly global)]
             (f))))))))
(use-fixtures :each fixture)

(defn- sha [file]
  (format "%064x" (BigInteger. 1
    (.digest (MessageDigest/getInstance "SHA-256")
             (Files/readAllBytes (.toPath (io/file file)))))))

(defn- file-hashes [root]
  (into (sorted-map)
    (for [f (file-seq (io/file root)) :when (.isFile f)]
      [(.toString (.relativize (.toPath (io/file root)) (.toPath f))) (sha f)])))

(defn- canonical-files []
  (into {} (for [s ["public-source" "private-source" "global"]]
             [s (file-hashes (io/file *root* s))])))

(defn- document!
  [slug visibility project body extra-header]
  (let [rel (str "observations/" slug ".md")
        text (str "---\nname: " slug "\ntype: observation\n"
                  "id: " (UUID/nameUUIDFromBytes (.getBytes slug "UTF-8")) "\n"
                  "visibility: " (name visibility) "\nowning-project: projects/"
                  (name project) ".md\n" extra-header "---\n" body)
        ;; Settle authoring whitespace once BEFORE seeding and taking the oracle.
        settled (md/emit-document (md/parse-document text rel))
        specs (md/parse-document settled rel)
        root (first specs)
        file (io/file *root* (if (= project :proj/pub) "public-source" "private-source")
                      "memory" rel)]
    ;; Raw setup deliberately permits the two historical bad public carriers.
    ;; Operations under test always traverse the real credentialed MCP route.
    (sup/raw-transact! (md/entity-specs->tx-data specs))
    (io/make-parents file)
    (spit file settled)
    (:db/ident root)))

(defn- facts [database eids]
  (set (for [eid eids datom (d/datoms database :eavt eid)]
         [(:e datom) (d/ident database (:a datom)) (:v datom)])))

(defn- snapshot
  "Canonical document facts and actual external incoming edges; carrier facts
   are retained separately because its generated front-matter order/mirrors may
   change on a replacement import. Held records compare both populations."
  [ident]
  (let [database (db/db) root (d/entity database ident) eid (:db/id root)
        eids (conj (import/section-tree-eids database eid) eid)
        carrier (:db/id (:mm.memory/frontmatter root))]
    {:eids eids
     :identities (into {} (for [e eids]
                           [e (d/pull database [:db/id :db/ident :mm/id :dt/type] e)]))
     :canonical-facts (set (remove #(= :mm.memory/frontmatter (second %))
                                  (facts database eids)))
     :carrier-facts (when carrier (facts database #{carrier}))
     :carrier-link carrier
     :incoming (set (remove #(contains? eids (first %))
                       (d/q '[:find ?s ?a ?target
                              :in $ [?target ...]
                              :where [?s ?a ?target] [?a :db/valueType :db.type/ref]]
                            database (vec eids))))}))

(defn- canonical [s] (dissoc s :carrier-facts :carrier-link))

(defn- request! [verb args]
  (let [id (inc (count @wire-responses))
        request {:jsonrpc "2.0" :id id :method "tools/call"
                 :params {:name verb :arguments args}}
        r (response-for tu/service :post "/mcp"
            :headers {"Content-Type" "application/json" "Accept" "application/json"
                      "Authorization" (str "Bearer " *token*)}
            :body (json/generate-string request))
        envelope (json/parse-string (:body r) true)
        result (:result envelope)
        error? (boolean (or (not= 200 (:status r)) (:error envelope)
                            (nil? result) (:isError result)))
        data (when-not error?
               (or (:structuredContent result)
                   (json/parse-string (get-in result [:content 0 :text]) true)))
        receipt {:request request :http-status (:status r)
                 :raw-response (:body r) :envelope envelope :error? error? :data data}]
    (swap! wire-responses conj receipt)
    receipt))

(defn- call! [verb args]
  (let [r (request! verb args)]
    (is (not (:error? r)) (pr-str r))
    (when (:error? r) (throw (ex-info "Unexpected MCP error" r)))
    (:data r)))

(defn- export! [destination child expected-count expected-held]
  (let [o {:project (if (= destination "public") ":memory.projects/pub"
                       ":memory.projects/priv")
           :destination destination :to (.getPath (io/file *root* "staging" child))}
        basis (db/basis-t)
        p (call! "sandbar_project_export" o)]
    (is (= "preview" (:status p)))
    (is (= expected-count (:included-count p)))
    (is (= expected-held (:held-count p)))
    (is (= basis (db/basis-t)) "preview writes private audit only")
    (is (not (.exists (io/file (:to o)))))
    (let [r (call! "sandbar_project_export"
                  (assoc o :dry-run false :expect-plan (:plan-token p)))]
      (is (true? (:complete? r)) (pr-str r))
      (is (= "complete" (:status r)))
      (is (= expected-count (:exported-count r)))
      (is (= basis (db/basis-t)) "provenance is not requested")
      {:options o :preview p :execution r
       :manifest (edn/read-string (slurp (io/file (:to o) export/manifest-name)))})))

(defn- import! [from expected-count]
  (let [before (db/basis-t)
        p (call! "sandbar_project_import" {:from from})]
    (is (= before (db/basis-t)) "preview is read-only")
    (is (= before (:basis p)))
    (is (= "unmapped" (get-in p [:source-root :kind]))
        "the export tree is a staging source, not a newly mapped owner")
    (is (= expected-count (:attempted p)))
    (is (= expected-count (count (:units p))))
    (is (zero? (:conflict-count p)) (pr-str p))
    (is (every? #(= "replace" (:mode %)) (:units p)))
    (is (every? #(false? (:identity-minted? %)) (:units p)))
    (is (every? #(zero? (:retracted-sections %)) (:units p)))
    (let [r (call! "sandbar_project_import"
                  {:from from :persist true :expect-basis (:basis p)
                   :expect-sources (:sources-sha256 p)})]
      (is (true? (:reconciled? r)))
      (is (= expected-count (:persisted-count r) (:attempted r)))
      (doseq [k [:failed-count :refused-count :conflict-count :parse-failed-count
                :skipped-count :excluded-count]]
        (is (zero? (get r k)) (str k " " (pr-str r))))
      (is (every? #(= "replace" (:mode %)) (:persisted r)))
      (is (every? #(false? (:identity-minted? %)) (:persisted r)))
      (is (= (:final-basis r) (db/basis-t)))
      {:preview p :execution r})))

(defn- audit! [from missing]
  (let [a (call! "sandbar_audit_fs-substrate-drift" {:from from})]
    (is (= "ad-hoc" (get-in a [:summary :root-attribution :kind])))
    (doseq [k [:missing-from-substrate :fs-parse-failed :content-divergence
              :ref-slot-mismatch :twins :substrate-rel-path-collisions]]
      (is (empty? (get a k)) (str k " " (pr-str a))))
    (is (set/subset? (set (map #(subs (str %) 1) missing))
                    (set (:missing-from-fs a))))
    (is (pos? (get-in a [:summary :missing-from-fs-count]))
        "partial export audit honestly reports non-exported store documents")
    a))

(defn- read! [ident]
  (:entity (call! "sandbar_entity_find" {:ident (str ident) :projection "full"})))

(defn- walk! [ident]
  (:reachable (call! "sandbar_navigate_path-via"
                     {:from (str ident) :via ":mm.memory/cites" :projection "full"})))

(defn- partial-import-control! [from selected-ids untouched-ids]
  (let [copy (mkdir "uuid-conflict-staging")
        prior (into {} (map (juxt identity snapshot) (concat selected-ids untouched-ids)))
        originals (canonical-files)
        exported (file-hashes from)]
    (doseq [rel (keys exported)]
      (let [f (io/file copy rel)]
        (io/make-parents f)
        (io/copy (io/file from rel) f)))
    (spit (io/file copy "observations/sectioned.md")
          (str/replace (slurp (io/file from "observations/sectioned.md"))
                       #"(?m)^id:.*$" (str "id: " (UUID/randomUUID))))
    (let [p (call! "sandbar_project_import" {:from copy})
          r (call! "sandbar_project_import"
                   {:from copy :persist true :expect-basis (:basis p)
                    :expect-sources (:sources-sha256 p)})
          after (into {} (map (juxt identity snapshot) (concat selected-ids untouched-ids)))]
      (is (= 1 (:conflict-count p)))
      (is (= 2 (:attempted r)))
      (is (true? (:reconciled? r)))
      (is (= 1 (:persisted-count r) (:conflict-count r)))
      (is (= ["observations/dependency.md"] (mapv :source (:persisted r))))
      (is (= ["observations/sectioned.md"] (mapv :source (:conflicts r))))
      (is (= ["identity-conflict"] (mapv :reason (mapcat :conflicts (:conflicts r)))))
      (doseq [k [:failed-count :refused-count :parse-failed-count :skipped-count]]
        (is (zero? (get r k)) (pr-str r)))
      (doseq [id selected-ids]
        (is (= (canonical (prior id)) (canonical (after id)))))
      (doseq [id untouched-ids]
        (is (= (prior id) (after id))))
      (is (= originals (canonical-files)))
      (is (= exported (file-hashes from)))
      (swap! results conj {:case :partial-import-conflict :preview p :execution r
                           :before prior :after after}))))

(deftest public-subset-preserves-existing-identities-sections-references-and-holds
  (let [dependency (document! "dependency" :public :proj/pub "Shared source.\n" "")
        body "Prologue.\n\n# First\n\nParent body.\n\n## Child\n\nChild body.\n\n# Last\n\nLast body.\n"
        selected (document! "sectioned" :public :proj/pub body
                            "cites: [observations/dependency.md]\ntags: [roundtrip, evidence]\n")
        sections (import/section-tree-eids (db/db) (sup/eid-of selected))
        ;; A UUID on an already existing section must remain exactly itself.
        _ (sup/raw-transact! (mapv (fn [eid] {:db/id eid :mm/id (UUID/randomUUID)}) sections))
        secret (document! "held-secret" :private :proj/pub "Held original.\n" "")
        bad (document! "held-reference" :public :proj/pub "Both versions retained.\n"
                       "cites: [observations/held-secret.md]\n")
        foreign (document! "foreign-citer" :private :proj/priv "Outside the selected project.\n" "")
        _ (sup/raw-transact! [{:db/ident foreign
                              :mm.memory/cites [selected (first (sort sections))]}])
        selected-ids [dependency selected]
        untouched-ids [secret bad foreign]
        before (into {} (map (juxt identity snapshot) (concat selected-ids untouched-ids)))
        bytes-before (canonical-files)
        initial-read (read! selected)
        initial-walk (walk! selected)
        exported (export! "public" "public-out" 2 2)
        from (get-in exported [:options :to])
        output-before (file-hashes from)]
    (is (= 3 (count sections)))
    (is (= #{"observations/dependency.md" "observations/sectioned.md" export/manifest-name}
           (set (keys output-before))))
    (is (= 1 (count initial-walk)))
    (is (= "memory.observations/dependency" (:db/ident (first initial-walk))))
    (doseq [pass [1 2]]
      (let [imported (import! from 2)
            after (into {} (map (juxt identity snapshot) (concat selected-ids untouched-ids)))
            reread (read! selected)
            rewalk (walk! selected)]
        (doseq [id selected-ids]
          (is (= (canonical (before id)) (canonical (after id))) (str id " pass " pass)))
        (doseq [id untouched-ids]
          (is (= (before id) (after id)) (str "held/foreign changed " id)))
        (is (= (select-keys initial-read [:db/id :db/ident :mm/id :dt/type :mm.memory/body-raw])
               (select-keys reread [:db/id :db/ident :mm/id :dt/type :mm.memory/body-raw])))
        (is (= (mapv :db/id initial-walk) (mapv :db/id rewalk)))
        (is (= bytes-before (canonical-files)) "import leaves canonical files untouched")
        (is (= output-before (file-hashes from)) "import leaves export/manifest bytes untouched")
        (swap! results conj {:case :public :pass pass :before before :after after
                             :export exported :import imported :canonical-files bytes-before
                             :output-files output-before :audit (audit! from untouched-ids)})))
    (partial-import-control! from selected-ids untouched-ids)
    ;; Exact-source refusal applies to a COPIED staging tree, leaving the valid
    ;; exported artifact and originals intact. No general restore is claimed.
    (let [copy (mkdir "edited-staging")]
      (doseq [rel (keys output-before)]
        (let [f (io/file copy rel)] (io/make-parents f) (io/copy (io/file from rel) f)))
      (let [p (call! "sandbar_project_import" {:from copy})
            basis (db/basis-t)
            prior (mapv snapshot (concat selected-ids untouched-ids))]
        (spit (io/file copy "observations/sectioned.md") "\nChanged after preview.\n" :append true)
        (let [r (request! "sandbar_project_import"
                         {:from copy :persist true :expect-basis (:basis p)
                          :expect-sources (:sources-sha256 p)})]
          (is (:error? r) (pr-str r))
          (let [error-data (json/parse-string (get-in r [:envelope :result :content 0 :text]) true)]
            (is (= ["import/sources-changed"] (get-in error-data [:details :reasons])))
            (is (= (:sources-sha256 p) (get-in error-data [:details :expected]))))
          (is (= basis (db/basis-t)))
          (is (= prior (mapv snapshot (concat selected-ids untouched-ids))))
          (is (= bytes-before (canonical-files)))
          (is (= output-before (file-hashes from)))
          (swap! results conj {:case :changed-source-refusal :preview p :response r
                               :basis-before basis :basis-after (db/basis-t)}))))))

(deftest private-document-retains-public-dependency-without-copying-or-reowning-it
  (let [pub (document! "public-dependency" :public :proj/pub "Reusable public source.\n" "")
        priv (document! "private-work" :private :proj/priv "Private reasoning.\n"
                        "cites: [observations/public-dependency.md]\n")
        public-before (snapshot pub) private-before (snapshot priv)
        bytes-before (canonical-files)
        initial (read! priv) path-before (walk! priv)
        exported (export! "private" "private-out" 1 0)
        from (get-in exported [:options :to])
        output-before (file-hashes from)
        imported (import! from 1)
        after (read! priv) path-after (walk! priv)]
    (is (= #{"observations/private-work.md" export/manifest-name} (set (keys output-before))))
    (is (some #(= pub (:entity %)) (get-in exported [:manifest :manifest/dependencies])))
    (is (= public-before (snapshot pub)) "public dependency is neither imported nor reowned")
    (is (= (canonical private-before) (canonical (snapshot priv))))
    (is (= (select-keys initial [:db/id :db/ident :mm/id :mm.memory/owning-project :mm.memory/body-raw])
           (select-keys after [:db/id :db/ident :mm/id :mm.memory/owning-project :mm.memory/body-raw])))
    (is (= 1 (count path-before) (count path-after)))
    (is (= (mapv :db/id path-before) (mapv :db/id path-after)))
    (is (= "memory.observations/public-dependency" (:db/ident (first path-after))))
    (is (= bytes-before (canonical-files)))
    (is (= output-before (file-hashes from)))
    (swap! results conj {:case :private-reuses-public :public-before public-before
                         :public-after (snapshot pub) :private-before private-before
                         :private-after (snapshot priv) :export exported :import imported
                         :canonical-files bytes-before :output-files output-before
                         :audit (audit! from [pub])})))
