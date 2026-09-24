(ns sandbar.project.export-test
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.util.auth :as auth]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.project.export :as export]
            [sandbar.project.provenance :as prov]
            [sandbar.security.visibility :as visibility]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute PosixFilePermissions]
           [java.util UUID]))

(def ^:dynamic *root* nil)
(def ^:dynamic *config* nil)
(def results (atom []))

(defn- mkdir [name]
  (let [f (io/file *root* name)]
    (.mkdirs f)
    (Files/setPosixFilePermissions (.toPath f) (PosixFilePermissions/fromString "rwx------"))
    (.getPath f)))

(defn- fixture [f]
  (binding [*root* (.getCanonicalFile (.toFile (Files/createTempDirectory "guarded-export-" (make-array FileAttribute 0))))]
    (let [pub (mkdir "pub-source") priv (mkdir "private-source") staging (mkdir "staging")
          audit (mkdir "audit")]
      ((tu/make-test-db-fixture {:test-name "guarded-export" :auth? false})
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

(defn- opts
  ([] (opts "public" "one"))
  ([dest child] {:project (if (= dest "public") :memory.projects/pub :memory.projects/priv)
                :destination dest :to (.getPath (io/file *root* "staging" child))}))
(defn- note!
  ([name visibility project] (note! name visibility project {}))
  ([name visibility project extra]
   (let [ident (keyword "memory.notes" name)]
     (sup/seed-memory! ident visibility [:mm.project/ident project]
       (merge {:mm/id (UUID/randomUUID) :mm.memory/memory-type :memory
               :mm.memory/rel-path (str "notes/" name ".md")
               :mm.memory/body-raw (str "Body " name ".\n")} extra))
     ident)))
(defn- plan [o] (export/plan (db/db) visibility/*principal* o))
(defn- execute [o]
  (let [p (export/export! o) r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))]
    (swap! results conj {:preview p :execution r}) r))
(defn- output-files [o]
  (filter #(.isFile %) (file-seq (io/file (:to o)))))
(defn- audit-text [] (str/join "\n" (map slurp (filter #(.isFile %) (file-seq (io/file *root* "audit"))))))

(deftest explicit-project-and-fresh-authorized-destination
  (note! "safe" :public :proj/pub)
  (doseq [o [(dissoc (opts) :project)
            (assoc (opts) :project :project/UNASSIGNED)
            (assoc (opts) :destination "unconfigured")
            (assoc (opts) :to (.getPath (io/file *root* "pub-source")))
            (assoc (opts) :to (str (:to (opts)) "/../one"))]]
    (is (thrown? clojure.lang.ExceptionInfo (plan o))))
  (.mkdirs (io/file (:to (opts))))
  (is (thrown? clojure.lang.ExceptionInfo (plan (opts))))
  (is (empty? (seq (.listFiles (io/file *root* "audit"))))))

(deftest project-scoped-preview-and-execution-preserve-source
  (let [id (note! "safe" :public :proj/pub)
        _ (note! "foreign" :private :proj/priv)
        before (into {} (db/entity id))
        o (opts)
        p (export/export! o)]
    (is (= :preview (:status p)))
    (is (= 1 (:included-count p)) (pr-str (plan o)))
    (is (zero? (:held-count p)))
    (is (not (.exists (io/file (:to o)))))
    (is (seq (audit-text)))
    (let [r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))
          marker (io/file (:to o) export/manifest-name)
          file (io/file (:to o) "notes/safe.md")]
      (is (= :complete (:status r)) (pr-str r))
      (is (true? (:complete? r)))
      (is (= 1 (:exported-count r)))
      (is (.isFile marker))
      (is (.isFile file))
      (when (.isFile file)
        (is (= (:mm/id before) (:mm/id (first (md/parse-document (slurp file) "notes/safe.md"))))))
    (is (= before (into {} (db/entity id)))))))

(deftest whole-document-holds-and-private-audit
  (let [secret (note! "SecretSentinel" :private :proj/pub)
        _ (note! "clean" :public :proj/pub)
        _ (note! "bad-citation" :public :proj/pub {:mm.memory/cites [secret]})
        _ (note! "bad-parent" :public :proj/pub {:mm.memory/parent secret})
        _ (note! "bad-body" :public :proj/pub {:mm.memory/body-raw "[[notes/SecretSentinel.md]]\n"})
        _ (note! "unresolved" :public :proj/pub {:mm.memory/introduced-in "notes/absent.md"})
        _ (note! "extras" :public :proj/pub
                 {:mm.memory/frontmatter {:dt/type :mm/Frontmatter
                                          :mm.frontmatter/extra (pr-str {:order ["extra"]
                                                                        :extras {"extra" {:raw "extra: SecretSentinel"}}})}})
        _ (note! "unlabeled" :public :proj/pub)
        _ (sup/raw-transact! [[:db/retract :memory.notes/unlabeled :mm.memory/visibility :public]])
        o (opts) p (plan o)]
    (is (= 1 (:included-count p)) (pr-str (:rows p)))
    (is (= 7 (:held-count p)))
    (let [r (execute o)]
      (is (true? (:complete? r)))
      (is (= 1 (:exported-count r)))
      (is (not (str/includes? (pr-str r) "SecretSentinel")))
      (is (str/includes? (audit-text) "SecretSentinel"))
      (doseq [f (output-files o)] (is (not (str/includes? (slurp f) "SecretSentinel")))))))

(deftest private-may-reference-public-without-copying-it
  (let [pub (note! "public-dependency" :public :proj/pub)
        _ (note! "private-work" :private :proj/priv {:mm.memory/cites [pub]})
        o (opts "private" "private-out") p (plan o)]
    (is (= 1 (:included-count p)) (pr-str (:rows p)))
    (is (true? (:complete? (execute o))))
    (is (.isFile (io/file (:to o) "notes/private-work.md")))
    (is (not (.exists (io/file (:to o) "notes/public-dependency.md"))))
    (let [m (edn/read-string (slurp (io/file (:to o) export/manifest-name)))]
      (is (some #(= pub (:entity %)) (:manifest/dependencies m))))))

(deftest caller-clearance-and-stale-plan
  (note! "secret" :private :proj/priv)
  (binding [visibility/*principal* {:auth/full-clearance? false}]
    (let [p (plan (opts "private" "restricted"))]
      (is (zero? (:included-count p)))
      (is (= 1 (:held-count p)))))
  (note! "safe" :public :proj/pub)
  (let [o (opts) p (export/export! o)]
    (sup/raw-transact! [{:db/ident :memory.notes/safe :mm.memory/body-raw "Changed.\n"}])
    (is (thrown? clojure.lang.ExceptionInfo
                 (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))))
    (is (not (.exists (io/file (:to o)))))))

(deftest emitted-shadow-mirrors-do-not-block-canonical-content
  (note! "canonical" :public :proj/pub
         {:mm.memory/name "Canonical" :mm.rule/name "Shadow [SecretSentinel]"})
  (let [p (plan (opts))]
    (is (= 1 (:included-count p)) (pr-str (:rows p)))
    (is (not (str/includes? (:text (first (:rows p))) "SecretSentinel")))))

(deftest audit-and-file-failures-never-complete
  (note! "safe" :public :proj/pub)
  (doseq [fail-at [:audit :file :ready :marker]]
    (let [o (opts "public" (name fail-at))
          p (export/export! o) write export/write-new!]
      (with-redefs [export/write-new!
                    (fn [file content private?]
                      (if (case fail-at
                            :audit (str/ends-with? (str file) "-prepared.edn")
                            :file (str/ends-with? (str file) "safe.md")
                            :ready (str/ends-with? (str file) "-ready.edn")
                            :marker (str/includes? (str file) export/manifest-name))
                        (throw (ex-info "SecretSentinel filesystem failure" {}))
                        (write file content private?)))]
        (let [r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))]
          (is (= :incomplete (:status r)) (pr-str r))
          (is (false? (:complete? r)))
          (is (not (.exists (io/file (:to o) export/manifest-name))))
          (is (not (str/includes? (pr-str r) "SecretSentinel"))))))))

(deftest provenance-switch-does-not-bypass-policy
  (note! "safe" :public :proj/pub)
  (note! "secret" :private :proj/pub)
  (doseq [[request? server? expected] [[false false 0] [true false 0] [false true 0] [true true 1]]]
    (let [recorded (atom []) o (assoc (opts "public" (str "p-" request? "-" server?)) :provenance request?)]
      (with-redefs [prov/recording-enabled? (constantly server?)
                    prov/record-projection-run! #(swap! recorded conj %)]
        (let [r (execute o)]
          (is (true? (:complete? r)))
          (is (= 1 (:exported-count r)))
          (is (= 1 (:held-count r)))
          (is (= expected (count @recorded))))))))

(deftest nested-sections-are-owned-and-preserved
  (let [id (note! "sections" :public :proj/pub {:mm.memory/body-raw "Prologue.\n\n# First\n\nFirst body.\n\n## Child\n\nChild body.\n\n# Last\n\nLast body.\n"})
        sections (md/parse-sections "# First\nFirst body.\n## Child\nChild body.\n# Last\nLast body.\n" id)]
    (sup/raw-transact! (mapv #(select-keys % [:db/ident :dt/type]) sections))
    (sup/raw-transact! sections)
    (sup/raw-transact! [{:db/ident id :mm.memory/first-section (:db/ident (first sections))}])
    (let [p (plan (opts)) row (first (:rows p))]
      (is (= 1 (:included-count p)) (pr-str row))
      (is (str/includes? (or (:text row) "") "Child body."))
      (is (not (str/includes? (or (:text row) "") "Stale."))))
    (let [foreign (note! "foreign-owner" :private :proj/priv)]
      (sup/raw-transact! [{:db/ident (:db/ident (first sections)) :mm.section/parent foreign}])
      (is (= :foreign-first-section (:reason (first (:rows (plan (opts))))))))))

(deftest unsupported-extras-markup-and-unrepresentable-references-are-held
  (note! "markup" :public :proj/pub {:mm.memory/body-raw "[reference][hidden]\n"})
  (note! "stale-id" :public :proj/pub
         {:mm.memory/frontmatter {:dt/type :mm/Frontmatter
                                  :mm.frontmatter/extra (pr-str {:order ["id"]
                                    :extras {"id" {:raw "id: '11111111-1111-1111-1111-111111111111'"}}})}})
  (sup/seed-memory! :project/legacy-reference :public [:mm.project/ident :proj/pub])
  (note! "legacy-ref" :public :proj/pub {:mm.memory/cites [:project/legacy-reference]})
  (let [p (plan (opts)) reasons (set (map :reason (:rows p)))]
    (is (zero? (:included-count p)))
    (is (contains? reasons :unsupported-text-markup))
    (is (contains? reasons :unknown-frontmatter))
    (is (contains? reasons :round-trip-identity-or-content))))

(deftest aliases-overlaps-and-path-collisions-refuse-before-effects
  (note! "Keep" :public :proj/pub)
  (note! "keep" :public :proj/pub)
  (is (thrown? clojure.lang.ExceptionInfo (plan (opts))))
  (sup/raw-transact! [[:db/retractEntity :memory.notes/keep]])
  (let [stage (io/file *root* "staging")
        alias (io/file stage "alias")]
    (Files/createSymbolicLink (.toPath alias) (.toPath (io/file *root* "pub-source"))
                             (make-array FileAttribute 0))
    (is (thrown? clojure.lang.ExceptionInfo (plan (assoc (opts) :to (.getPath alias))))))
  (binding [*config* (assoc-in *config* [:export-destinations "public" :audit-root]
                              (.getPath (io/file *root* "staging")))]
    (is (thrown? clojure.lang.ExceptionInfo (plan (opts)))))
  (is (not (.exists (io/file (:to (opts)))))))

(deftest snapshot-reference-and-carrier-reads-share-the-plan-basis
  (let [target (note! "target" :public :proj/pub)
        source (note! "source" :public :proj/pub {:mm.memory/cites [target]})
        carrier :memory.notes/source__frontmatter]
    (sup/raw-transact! [{:db/ident carrier :dt/type :mm/Frontmatter
                        :mm.frontmatter/extra (pr-str {:order ["name"] :extras {}})}])
    (sup/raw-transact! [{:db/ident source :mm.memory/frontmatter carrier}])
    (let [old (db/db)]
      (sup/raw-transact! [{:db/ident target :mm.memory/visibility :private}
                         {:db/ident carrier :mm.frontmatter/extra
                          (pr-str {:order ["bad"] :extras {"bad" {:raw "bad: SecretSentinel"}}})}])
      (is (= 2 (:included-count (export/plan old nil (opts)))))
      (is (zero? (:included-count (plan (opts))))))))

(deftest repeated-fresh-exports-preserve-prior-output-and-private-audit-permissions
  (note! "safe" :public :proj/pub)
  (let [one (opts "public" "one") two (opts "public" "two")]
    (is (true? (:complete? (execute one))))
    (let [before (into {} (map (fn [f] [(.getPath f) (slurp f)]) (output-files one)))]
      (is (true? (:complete? (execute two))))
      (is (= before (into {} (map (fn [f] [(.getPath f) (slurp f)]) (output-files one))))))
    (doseq [f (filter #(.isFile %) (file-seq (io/file *root* "audit")))]
      (is (= (PosixFilePermissions/fromString "rw-------")
             (Files/getPosixFilePermissions (.toPath f) (make-array java.nio.file.LinkOption 0)))))))

(deftest real-provenance-run-is-terminal-only-after-files-and-audit
  (note! "safe" :public :proj/pub)
  (note! "secret" :private :proj/pub)
  (with-redefs [prov/recording-enabled? (constantly true)]
    (let [r (execute (assoc (opts) :provenance true))
          runs (prov/projection-runs (db/db))]
      (is (true? (:complete? r)) (pr-str r))
      (is (= 1 (count runs)))
      (is (= :succeeded (:mm.activity/status (first runs))))
      (is (= (:audit-ref r) (str (:mm/id (first runs))))))))

(def wire-responses (atom []))

(defn- bearer! [role full?]
  (let [secret (str (UUID/randomUUID)) service (keyword (str "export-" (UUID/randomUUID)))]
    (dt/make :auth/ServiceAccount
      {:auth/service-name service :auth/api-key-hash (auth/hash-password secret)
       :auth/roles [(tu/ensure-role! role)] :auth/active? true :auth/full-clearance? full?})
    (str (name service) ":" secret)))

(defn- request! [token args]
  (let [r (response-for tu/service :post "/mcp"
            :headers {"Content-Type" "application/json" "Accept" "application/json"
                      "Authorization" (str "Bearer " token)}
            :body (json/generate-string {:jsonrpc "2.0" :id 1 :method "tools/call"
                    :params {:name "sandbar_project_export" :arguments args}}))
        envelope (json/parse-string (:body r) true)
        result (:result envelope)
        data (when (and result (not (:isError result)) (nil? (:error envelope)))
               (json/parse-string (get-in result [:content 0 :text]) true))]
    (swap! wire-responses conj {:http-status (:status r) :envelope envelope})
    {:http-status (:status r) :envelope envelope :data data}))

(deftest mcp-preview-execution-errors-and-role-boundary
  (note! "wire-safe" :public :proj/pub)
  (note! "WireSecretSentinel" :private :proj/pub)
  (let [token (bearer! :read-write true)
        readonly (bearer! :read-only true)
        o (update (opts) :project str)
        ro (request! readonly o)]
    (is (or (:error (:envelope ro)) (get-in ro [:envelope :result :isError])))
    (is (not (.exists (io/file (:to o)))))
    (let [preview (request! token o) p (:data preview)]
      (is (= 200 (:http-status preview)))
      (is (= "preview" (:status p)) (pr-str preview))
      (is (= 1 (:included-count p)))
      (is (= 1 (:held-count p)))
      (is (not (str/includes? (pr-str preview) "WireSecretSentinel")))
      (let [result (request! token (assoc o :dry-run false :expect-plan (:plan-token p)))]
        (is (= 200 (:http-status result)))
        (is (true? (get-in result [:data :complete?])) (pr-str result))
        (is (= 1 (get-in result [:data :exported-count])))))
    (let [r (request! token {:to (.getPath (io/file *root* "WireSecretSentinel"))})]
      (is (nil? (:data r)))
      (is (not (str/includes? (pr-str r) "WireSecretSentinel"))))))

(deftest partial-manifest-write-cannot-publish-completion
  (note! "safe" :public :proj/pub)
  (let [o (opts) p (export/export! o) original export/write-new!]
    (with-redefs [export/write-new!
                  (fn [file content private?]
                    (original file content private?)
                    (when (str/includes? (str file) export/manifest-name)
                      (throw (ex-info "Failure after bytes reached disk" {}))))]
      (let [r (export/export! (assoc o :dry-run false :expect-plan (:plan-token p)))]
        (is (= :incomplete (:status r)))
        (is (not (.exists (io/file (:to o) export/manifest-name))))))))


(deftest bare-string-carriers-and-unresolved-paths-are-held
  (let [secret (note! "CarrierSecretSentinel" :private :proj/priv)]
    (doseq [[name target] [["private-rule" "notes/CarrierSecretSentinel.md"]
                           ["missing-rule" "notes/missing.md"]]]
      (note! name :public :proj/pub {:dt/type :mm/Rule :mm.memory/memory-type :rule
                                    :mm.rule/derives-from [target]}))
    (let [p (plan (opts))]
      (is (zero? (:included-count p)))
      (is (= #{:unsafe-reference} (set (map :reason (:rows p))))))
    (let [r (execute (opts))]
      (is (true? (:complete? r)))
      (is (zero? (:exported-count r)))
      (is (not (str/includes? (str/join "\n" (map slurp (output-files (opts)))) "CarrierSecretSentinel")))
      (is (str/includes? (audit-text) "CarrierSecretSentinel")))))

(deftest code-and-unique-name-links-remain-usable
  (let [target (note! "name-target" :public :proj/pub {:mm.memory/name "Unique target"})
        ticks (str (char 96))
        text (str "Use " ticks "[?e :a ?v]" ticks ".\n\n"
                  "~~~clojure\n{:x [1 2]}\n~~~\n\n"
                  "[[Unique target]]\n")
        source (note! "code" :public :proj/pub {:mm.memory/body-raw text})]
    (let [row (first (filter #(= source (:entity %)) (:rows (plan (opts)))))]
      (is (= :included (:status row)) (pr-str row))
      (is (str/includes? (or (:text row) "") "{:x [1 2]}"))
      (is (some #(= target (:entity %)) (:references row))))
    (note! "name-twin" :private :proj/priv {:mm.memory/name "Unique target"})
    (let [row (first (filter #(= source (:entity %)) (:rows (plan (opts)))))]
      (is (= :held (:status row)))
      (is (= :unsafe-reference (:reason row))))))

(deftest ordinary-names-are-not-manifest-dependencies
  (let [target (note! "ordinary-target" :public :proj/pub {:mm.memory/name "Ordinary target"})
        source (note! "ordinary-source" :public :proj/pub
                      {:mm.memory/name "Ordinary source"
                       :mm.memory/description "Ordinary target"})
        row-of #(first (filter (fn [row] (= source (:entity row))) (:rows (plan (opts "public" "probe")))))
        row (row-of)]
    (is (= :included (:status row)))
    (is (not-any? #(= source (:entity %)) (:references row)))
    (is (not-any? #(= target (:entity %)) (:references row)))
    (is (true? (:complete? (execute (opts)))))
    (let [manifest (edn/read-string (slurp (io/file (:to (opts)) export/manifest-name)))
          parsed (first (md/parse-document
                          (slurp (io/file (:to (opts)) "notes/ordinary-source.md"))
                          "notes/ordinary-source.md"))]
      (is (not-any? #(contains? #{source target} (:entity %)) (:manifest/dependencies manifest)))
      (is (= ["Ordinary source" "Ordinary target"]
             ((juxt :mm.memory/name :mm.memory/description) parsed))))
    ;; Explicit links keep their semantics, including a deliberate self-link.
    (sup/raw-transact! [{:db/ident source
                        :mm.memory/body-raw "[[Ordinary source]] and [[Ordinary target]]\n"}])
    (let [linked (row-of)]
      (is (= :included (:status linked)))
      (is (every? (set (map :entity (:references linked))) [source target])))))

(deftest body-section-disagreement-is-never-silently-resolved
  (let [id (note! "different" :public :proj/pub {:mm.memory/body-raw "# Heading\nNew text.\n"})
        sections (md/parse-sections "# Heading\nOld text.\n" id)]
    (sup/raw-transact! (mapv #(select-keys % [:db/ident :dt/type]) sections))
    (sup/raw-transact! sections)
    (sup/raw-transact! [{:db/ident id :mm.memory/first-section (:db/ident (first sections))}])
    (let [row (first (:rows (plan (opts))))]
      (is (= :held (:status row)))
      (is (= :body-section-disagreement (:reason row))))
    (is (zero? (:exported-count (execute (opts)))))
    (is (= "# Heading\nNew text.\n" (:mm.memory/body-raw (d/entity (db/db) id))))
    (is (= "Old text.\n" (:mm.section/body (d/entity (db/db) (:db/ident (first sections))))))))

(deftest unlabeled-authors-stay-private-and-compartment-bound
  (let [actor (note! "actor" :private :proj/priv {:dt/type :mm/Actor})
        private-source (note! "private-source" :private :proj/priv {:mm.memory/created-by [actor]})
        public-source (note! "public-source" :public :proj/pub {:mm.memory/created-by [actor]})]
    (sup/raw-transact! [[:db/retract actor :mm.memory/visibility :private]])
    (let [rows (:rows (plan (opts "private" "private-check")))
          row (first (filter #(= private-source (:entity %)) rows))]
      (is (= :included (:status row)) (pr-str row)))
    (let [row (first (filter #(= public-source (:entity %)) (:rows (plan (opts)))))]
      (is (= :unsafe-reference (:reason row))))
    (sup/raw-transact! [[:db/retract actor :mm.memory/owning-project (sup/eid-of :memory.projects/priv)]])
    (let [row (first (filter #(= private-source (:entity %)) (:rows (plan (opts "private" "unowned")))))]
      (is (= :unsafe-reference (:reason row))))))

(deftest tag-values-round-trip-without-exporting-vocabulary-metadata
  (sup/raw-transact! [{:mm.tag/value "safe-tag" :dt/type :mm/Tag
                      :mm.tag/definition "TagMetadataSecretSentinel"}])
  (note! "tagged" :public :proj/pub {:mm.memory/tags [[:mm.tag/value "safe-tag"]]})
  (let [r (execute (opts)) body (slurp (io/file (:to (opts)) "notes/tagged.md"))]
    (is (true? (:complete? r)))
    (is (= 1 (:exported-count r)))
    (is (str/includes? body "safe-tag"))
    (is (not (str/includes? body "TagMetadataSecretSentinel")))))
