(ns sandbar.project.import-units-test
  "`project.import` transacts SOURCE UNITS — one per file — and accounts for
   every file it walked (REP-06 of Astra's 0.2.0 representation review,
   folded into D6 on 2026-09-19).

   The two seams her review demonstrated at 7fd481f:
     - `group-by-source` started a new transaction group only at a Memory,
       so two independently parsed Tag files became ONE group and a Tag
       after a Memory joined that Memory's transaction; the import filter
       likewise carried the preceding Memory's inclusion state onto the
       next file's entities.
     - `ingest-graph` logged a parse failure and returned nil for the file,
       so the import handler never saw it: an injected parser exception for
       one of two files reported `persisted-count 1`, `failed-count 0` and
       no failed-file record — a zero-failure response hiding a failure.

   What this pins, through the REAL dispatched verb against a fresh store:
     - one transaction per file, in either enumeration order (a memory
       among tags, tags among memories): a memory and its sections share
       ONE transaction, and every Tag unit is a transaction of its own —
       asserted on the transaction each entity's `:dt/type` datom landed
       in, not on basis deltas (the request log and events transact too);
     - a file whose parse throws is NAMED in `:parse-failed` with its error,
       the totals reconcile (attempted = persisted + failed + refused +
       parse-failed + skipped), and the dry run reports the same accounting
       without transacting;
     - front-matter keys a class does not declare are REPORTED per file and
       carried (the bulk path is lenient where `entity.create` is strict);
     - the filter decision is by source unit (a Tag unit passes a Tag class
       filter on its own root; the memory it sits beside is counted skipped);
     - `ingest-graph` remains the units flattened.

   The parse failure is INJECTED (a redef of `md/parse-document` for one
   rel-path) to exercise the accounting, exactly as the review did — no
   claim is made that the otherwise valid input fails on its own."
  (:require [cheshire.core          :as json]
            [clojure.java.io        :as io]
            [clojure.string         :as str]
            [clojure.test           :refer [deftest is testing use-fixtures]]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.mcp.tools      :as tools]
            [sandbar.projection     :as pg]
            [sandbar.test-util      :as tu]))

(use-fixtures :each
  (fn [f]
    (md/register!)
    ((tu/make-test-db-fixture {:test-name "import-units-test" :auth? false}) f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fresh-tmp-dir ^java.io.File [stem]
  (let [f (java.io.File/createTempFile (str "import-units-" stem "-") "")]
    (.delete f) (.mkdirs f) f))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- write-corpus!
  "Materialize `files` ({rel-path content}) under `root`; returns `root`."
  ^java.io.File [^java.io.File root files]
  (doseq [[rel content] files]
    (let [f (io/file root rel)]
      (io/make-parents f)
      (spit f content)))
  root)

(defn- call-import
  "Dispatch the REAL `sandbar.project.import` verb via `tools/handle-call`
   and return the parsed result payload; asserts the envelope is not an
   error."
  [arguments]
  (let [response (tools/handle-call 1 {:name "sandbar.project.import" :arguments arguments})
        result   (:result response)]
    (is (map? result) (str "no :result — " (pr-str response)))
    (is (not (:isError result)) (str "isError — " (-> result :content first :text)))
    (some-> result :content first :text (json/parse-string true))))

(defn- basis [] (d/basis-t (d/db (db/conn))))

(defn- tx-of
  "The transaction that asserted `e`'s `:dt/type` datom — the entity's
   birth transaction."
  [e]
  (d/q '[:find ?tx . :in $ ?e :where [?e :dt/type _ ?tx]] (d/db (db/conn)) e))

(defn- tag-eid [value]
  (d/q '[:find ?e . :in $ ?v :where [?e :mm.tag/value ?v]] (d/db (db/conn)) value))

(defn- born-in
  "Every entity whose `:dt/type` datom was asserted in transaction `tx`."
  [tx]
  (d/q '[:find [?e ...] :in $ ?tx :where [?e :dt/type _ ?tx]] (d/db (db/conn)) tx))

(defn- absent?
  "True when `ident` resolves to no entity at all."
  [ident]
  (nil? (d/entid (d/db (db/conn)) ident)))

(defn- memory-doc
  "A decision with a headingless body — ONE entity when parsed."
  [title]
  (str "---\ntype: decision\nname: " title "\ndescription: a memory file for the import-units probe\n---\n"
       title " has a body without a heading.\n"))

(deftest firewall-refusal-names-the-input-edge-without-returning-target-content
  (let [dir (fresh-tmp-dir "firewall-report")]
    (try
      @(d/transact (db/conn)
                   [{:db/id "public-context" :db/ident :context/import-report-public :dt/type :mm/Context
                     :mm.context/firewall-class :public-bottom}
                    {:db/ident :memory.projects/import_report_public :dt/type :mm/Project
                     :mm.project/default-visibility :public
                     :mm.project/firewall-class :public-bottom
                     :mm.project/runs-in-context "public-context"}
                    {:db/ident :memory.observations/private_target :dt/type :mm/Observation
                     :mm.memory/name "Private target" :mm.memory/visibility :private
                     :mm.memory/rel-path "observations/private_target.md"
                     :mm.memory/body-raw "PRIVATE TARGET CONTENT MUST NOT APPEAR"}])
      (write-corpus! dir {"observations/public_source.md"
                          "---\ntype: observation\nname: Public source\nvisibility: public\nowning-project: projects/import_report_public.md\nrelated:\n  - observations/private_target.md\n---\nPublic body.\n"})
      (let [report (call-import {"from" (.getPath dir) "persist" true})
            violation (get-in report [:refused 0 :violations 0])]
        (is (= 1 (:refused-count report)) (pr-str report))
        (is (zero? (:persisted-count report)))
        (is (zero? (:failed-count report)))
        (is (:reconciled? report))
        (is (= "firewall-violation" (:type violation)))
        (is (= "flow-forbidden" (:reason violation)))
        (is (= "mm.memory/related" (:slot violation)))
        (is (= {:db/ident "memory.observations/private_target"} (:target-ref violation)))
        (is (absent? :memory.observations/public_source))
        (is (not (str/includes? (pr-str report) "PRIVATE TARGET CONTENT MUST NOT APPEAR"))))
      (finally (rm-rf! dir)))))

(def ^:private sectioned-doc
  "A decision whose body carries a title heading and two subsections —
   the memory plus THREE sections when parsed."
  "---\ntype: decision\nname: Sectioned probe\ndescription: a sectioned memory file for the import-units probe\n---\n# Sectioned probe\n\nIntro.\n\n## First\n\nOne.\n\n## Second\n\nTwo.\n")

(def ^:private memory-doc-with-unknown-key
  "---\ntype: decision\nname: Extras probe\ndescription: carries a key its class does not declare\nbogus_key: 1\n---\nA body without a heading.\n")

(defn- tag-doc [value]
  (str "---\ntype: tag\nvalue: " value "\ndefinition: a synthetic tag for the import-units probe\n---\n"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; one transaction per file, in either order
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest each-file-is-its-own-transaction-unit-in-either-order
  (doseq [[label files memory-ident tags]
          [["two tags and a sectioned memory"
            {"tags/alpha.md"      (tag-doc "unit-alpha")
             "tags/beta.md"       (tag-doc "unit-beta")
             "decisions/probe.md" sectioned-doc}
            :memory.decisions/probe
            ["unit-alpha" "unit-beta"]]
           ["a sectioned memory and two tags"
            {"adecisions/probe.md" sectioned-doc
             "tags/gamma.md"       (tag-doc "unit-gamma")
             "tags/delta.md"       (tag-doc "unit-delta")}
            :memory.adecisions/probe
            ["unit-gamma" "unit-delta"]]]]
    (let [dir (write-corpus! (fresh-tmp-dir "order") files)]
      (try
        (testing label
          (let [report (call-import {"from" (.getPath dir) "persist" true})]
            (is (= 3 (:attempted report)) (pr-str report))
            (is (= 6 (:imported report)) "two tags + a memory with three sections")
            (is (= 3 (:persisted-count report)) (pr-str (:failed report)))
            (is (= 3 (:groups report)) "one transaction unit per file")
            (is (zero? (:failed-count report)) (pr-str (:failed report)))
            (is (zero? (:refused-count report)))
            (is (zero? (:parse-failed-count report)))
            (is (zero? (:skipped-count report)))
            (is (true? (:reconciled? report)) (pr-str (:totals report)))
            (doseq [v tags]
              (is (some? (tag-eid v)) (str "the Tag document " v " persisted on its own (REP-05)")))
            (let [mem-tx   (tx-of memory-ident)
                  tag-txs  (map (comp tx-of tag-eid) tags)]
              (is (= 4 (count (born-in mem-tx)))
                  "the memory, its title section and the two subsections share ONE transaction")
              (is (= 3 (count (distinct (cons mem-tx tag-txs))))
                  "three files, three distinct transactions"))))
        (finally (rm-rf! dir))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; a parse failure is named, and never reads as success
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-parse-failure-is-named-in-the-report-and-the-totals-reconcile
  (let [dir  (write-corpus! (fresh-tmp-dir "parsefail")
                            {"decisions/good.md"   (memory-doc "Good")
                             "decisions/broken.md" (memory-doc "Broken")
                             "tags/ok.md"          (tag-doc "unit-ok")})
        orig md/parse-document]
    (try
      (with-redefs [md/parse-document
                    (fn [source rel-path]
                      (if (= rel-path "decisions/broken.md")
                        (throw (ex-info "injected parse failure (accounting probe)" {:rel-path rel-path}))
                        (orig source rel-path)))]
        (testing "persist: the broken file is named, its neighbours land, nothing hides"
          (let [report (call-import {"from" (.getPath dir) "persist" true})]
            (is (= 3 (:attempted report)) (pr-str report))
            (is (= 2 (:persisted-count report)))
            (is (= 1 (:parse-failed-count report)))
            (is (= "decisions/broken.md" (-> report :parse-failed first :source)))
            (is (re-find #"injected parse failure" (str (-> report :parse-failed first :error))))
            (is (zero? (:failed-count report)) "a parse failure is not a transaction failure")
            (is (true? (:reconciled? report)) (pr-str (:totals report)))
            (is (some? (tag-eid "unit-ok")))
            (is (some? (tx-of :memory.decisions/good)))
            (is (absent? :memory.decisions/broken) "the broken file left nothing behind")
            (is (not= (tx-of :memory.decisions/good) (tx-of (tag-eid "unit-ok")))
                "the two files that landed did so in separate transactions")))
        (testing "dry run: the same accounting, nothing transacted"
          (let [t1  (basis)
                dry (call-import {"from" (.getPath dir)})]
            (is (= 3 (:attempted dry)))
            (is (= 1 (:parse-failed-count dry)))
            (is (= "decisions/broken.md" (-> dry :parse-failed first :source)))
            (is (= 2 (count (:entities dry))))
            (is (= t1 (basis)) "a dry run transacts nothing"))))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; unknown front-matter keys: reported per file, carried, not refused
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unknown-front-matter-keys-are-reported-per-file-and-carried
  (let [dir (write-corpus! (fresh-tmp-dir "unknown")
                           {"decisions/extras.md" memory-doc-with-unknown-key
                            "decisions/plain.md"  (memory-doc "Plain")})]
    (try
      (testing "the dry run names the file and the keys"
        (let [dry (call-import {"from" (.getPath dir)})]
          (is (= 1 (:unknown-keys-count dry)) (pr-str dry))
          (is (= "decisions/extras.md" (-> dry :unknown-keys first :source)))
          (is (= ["bogus_key"] (-> dry :unknown-keys first :unknown-keys)))))
      (testing "the bulk path carries the key rather than refusing the file"
        (let [report (call-import {"from" (.getPath dir) "persist" true})]
          (is (= 2 (:persisted-count report)) (pr-str (:failed report)))
          (is (= 1 (:unknown-keys-count report)))
          (is (= "decisions/extras.md" (-> report :unknown-keys first :source)))
          (let [e (d/entity (d/db (db/conn)) :memory.decisions/extras)]
            (is (some? (:db/id e)))
            (is (some? (:mm.memory/frontmatter e)) "the front-matter carrier rode along"))))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the filter decision is by source unit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest filter-decisions-are-made-per-source-unit
  (let [dir (write-corpus! (fresh-tmp-dir "filter")
                           {"tags/one.md"        (tag-doc "unit-one")
                            "decisions/probe.md" (memory-doc "Filter probe")
                            "tags/two.md"        (tag-doc "unit-two")})]
    (try
      (testing "a class filter keeps each Tag unit on its own root and counts the memory skipped"
        (let [report (call-import {"from" (.getPath dir) "persist" true
                                   "filter" {"class" ":mm/Tag"}})]
          (is (= 3 (:attempted report)) (pr-str report))
          (is (= 2 (:persisted-count report)))
          (is (= 1 (:skipped-count report)))
          (is (true? (:reconciled? report)) (pr-str (:totals report)))
          (is (some? (tag-eid "unit-one")))
          (is (some? (tag-eid "unit-two")))
          (is (not= (tx-of (tag-eid "unit-one")) (tx-of (tag-eid "unit-two")))
              "each Tag unit is a transaction of its own")
          (is (absent? :memory.decisions/probe) "the filtered memory never landed")))
      (testing "a tree filter keeps only the decisions subtree"
        (let [dry (call-import {"from" (.getPath dir) "filter" {"tree-filter" "decisions/"}})]
          (is (= 3 (:attempted dry)))
          (is (= 1 (count (:entities dry))))
          (is (= 2 (:skipped-count dry)))
          (is (= "memory.decisions/probe" (-> dry :entities first :ident)))))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the flat walk is the units flattened
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ingest-graph-is-the-units-flattened
  (let [dir (write-corpus! (fresh-tmp-dir "flat")
                           {"tags/flat.md"           (tag-doc "unit-flat")
                            "decisions/sectioned.md" sectioned-doc})]
    (try
      (let [units (pg/ingest-units dir)]
        (is (= 2 (count units)))
        (is (every? #(= :parsed (:status %)) units) (pr-str (map (juxt :source :status :error) units)))
        (is (= (pg/ingest-graph dir) (into [] (mapcat :entities) units)))
        (let [sectioned (first (filter #(= "decisions/sectioned.md" (:source %)) units))]
          (is (= :mm/Decision (:class sectioned)))
          (is (= :memory.decisions/sectioned (:ident sectioned)))
          (is (= 4 (count (:entities sectioned)))
              "the memory, its title section and the two subsections stay in one unit")
          (is (= [] (:unknown-keys sectioned)))))
      (finally (rm-rf! dir)))))
