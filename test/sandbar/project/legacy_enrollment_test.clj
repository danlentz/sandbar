(ns sandbar.project.legacy-enrollment-test
  "Enrolling an existing corpus tree as it stands — the disposable legacy
   case (onboarding wave, 2026-09-20).

   The release is measured against Old Bob's field result: a file-based
   memory of 288 Markdown files with no `id` lines and no `owning-project`
   lines.  These cases take such a tree through the current ingest, the
   maintenance import and the first ordinary projection, and state exactly
   what happens: where UUIDs are absent, which reference identities survive,
   and what blocks first use.  Detection only — nothing here proposes a
   repair; the report the case produces is the evidence for choosing one.

   Two fixtures: a three-file tree in Old Bob's exact front-matter shape
   (always runs), and the pinned 288-file copy when it is present
   (`SANDBAR_LEGACY_CORPUS`, default the lead's pinned extraction of
   a3089c7; skipped with a message otherwise).  Same fixture shape as the
   destination and import tests: one disposable store, temp roots, the
   operator map through `config/value`, nothing live."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.audit.fs-substrate-drift :as drift]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.db.ref :as ref]
            [sandbar.project.destination :as dest]
            [sandbar.projection :as pg]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.scripts.maintenance-import :as maintenance]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *base* nil)
(def ^:dynamic *roots* nil)

(defn- root [s] (.getCanonicalPath (io/file *base* s)))
(defn- memory-dir [r] (io/file (root r) "memory"))
(defn- memory-file [r p] (io/file (memory-dir r) p))

(defn- remove-tree! [f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (remove-tree! c)))
  (.delete f))

(defn- fixture [f]
  (let [base     (.toFile (Files/createTempDirectory "legacy-enrollment-test-" (make-array FileAttribute 0)))
        original config/value]
    (binding [*base* base *roots* (atom {})]
      (doseq [s ["global" "a"]] (.mkdirs (memory-file s "")))
      (md/register!)
      @(d/transact (db/conn)
                   [{:db/id "public-context" :db/ident :context/legacy-public :dt/type :mm/Context
                     :mm.memory/name "public" :mm.context/firewall-class :public-bottom}
                    {:db/ident :memory.projects/legacy
                     :dt/type :mm/Project :mm.memory/name "legacy"
                     :mm.project/ident :project/legacy
                     :mm.project/default-visibility :public
                     :mm.project/firewall-class :public-bottom
                     :mm.project/runs-in-context "public-context"}])
      (with-redefs [config/value (fn [k & more]
                                  (if (= k :project-roots) @*roots* (apply original k more)))
                    dest/global-root #(root "global")]
        (try (f) (finally (remove-tree! base)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "legacy-enrollment" :auth? false}) fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- map-root! [] (reset! *roots* {:project/legacy (root "a")}))

(defn- stored-id [ident] (str (:mm/id (db/entity ident))))

(defn- documents
  "Every imported document — a stored document with a rel-path whose ident
   is in the corpus namespace (`memory.*`), which leaves out the store's
   seeded `:workflow/session` — as `{:eid :ident :class :entity}`."
  []
  (let [db (db/db)]
    (for [[e] (d/q '[:find ?e :where [?e :mm.memory/rel-path]] db)
          :let [ent (d/entity db e)]
          :when (some-> (:db/ident ent) namespace (str/starts-with? "memory."))]
      {:eid e :ident (:db/ident ent) :class (dt/class-ident-of ent) :entity ent})))

(def ^:private ref-slots
  "The reference slots a legacy file's front matter can carry."
  [:mm.memory/related :mm.memory/cites :mm.memory/evidences :mm.memory/triggered-by
   :mm.memory/composes-with :mm.memory/superseded-by :mm.memory/supersedes])

(defn- ref-survival
  "How the documents' references came through the import: `:resolved` —
   targets that are real documents (an eid with a `:dt/type`); `:placeholder`
   — targets that exist only as an ident the upsert created (a forward or
   external reference the import never filled), listed by ident under
   `:placeholders` (the first twenty); `:unresolvable` — a stored value the
   resolver cannot turn into an eid at all; `:edges` — the total.  A stored
   reference on a Datomic entity is an entity map, or the ident KEYWORD when
   the target is interned, so every value goes through the canonical
   resolver (`sandbar.db.ref/ref->eid`), never `:db/id` alone — the lead's
   diagnosis of the first run (21:29Z)."
  [docs]
  (let [db (db/db)]
    (reduce (fn [acc {:keys [entity]}]
              (reduce (fn [acc slot]
                        (let [vs (get entity slot)
                              vs (cond (nil? vs) [] (set? vs) vs (sequential? vs) vs :else [vs])]
                          (reduce (fn [acc v]
                                    (let [eid (ref/ref->eid db v)
                                          t   (when eid (d/entity db eid))]
                                      (cond-> (update acc :edges inc)
                                        (nil? eid)      (update :unresolvable inc)
                                        (and eid (:dt/type t)) (update :resolved inc)
                                        (and eid (not (:dt/type t)))
                                        (-> (update :placeholder inc)
                                            (update :placeholders #(if (< (count %) 20) (conj % (or (:db/ident t) eid)) %))))))
                                  acc vs)))
                      acc ref-slots))
            {:edges 0 :resolved 0 :placeholder 0 :unresolvable 0 :placeholders []}
            docs)))

(defn- project!
  "One ordinary projection of `e`, as the sink performs it after an edit."
  [e]
  (let [eid (:db/id e)] (sinks/fs-projection-sink eid (into {:db/id eid} (db/entity eid)))))

(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn- census
  "The enrollment facts the cases report: UUIDs, owners, classes, references."
  [docs]
  {:documents      (count docs)
   :with-uuid      (count (filter #(:mm/id (:entity %)) docs))
   :with-owner     (count (filter #(:mm.memory/owning-project (:entity %)) docs))
   :classes        (into (sorted-map) (frequencies (map :class docs)))
   :references     (ref-survival docs)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The representative tiny tree — Old Bob's shape exactly
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- legacy-file!
  "A file in Old Bob's front-matter shape: no `id`, no `owning-project`, a
   date-only `created`, `related:` as rel-paths.  Tags are left to the
   288-file case so the three blockers stand alone here."
  [r rel {:keys [name type related]}]
  (let [f (memory-file r rel)]
    (io/make-parents f)
    (spit f (str "---\nname: " name "\ndescription: " name " — described\n"
                 "type: " type "\nscope: global\ncreated: 2026-04-30\nlast-reviewed: 2026-05-02\n"
                 (when (seq related) (str "related:\n" (apply str (map #(str "  - " % "\n") related))))
                 "---\n## The idea\n\nBody of " name ".\n"))
    f))

(defn- tiny-tree! [r]
  (legacy-file! r "patterns/first.md"      {:name "first" :type "pattern" :related ["patterns/second.md" "observations/third.md"]})
  (legacy-file! r "patterns/second.md"     {:name "second" :type "pattern" :related ["patterns/first.md"]})
  (legacy-file! r "observations/third.md"  {:name "third" :type "observation" :related ["patterns/first.md" "protocol/not_in_this_tree.md"]}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The desired path: preview -> pinned persist -> stamp -> enrollment
;; preflight -> first strict projection -> repeat.  The baseline (what
;; blocked before the owner default, the minting and the stamp) is recorded
;; in codex/reviews/onboarding-wave-2026-09-20/opus-legacy-enrollment.md.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- enroll!
  "One attended maintenance window over the mapped tree: preview, pinned
   persist and identity stamp, receipts in the fixture."
  [& [opts]]
  (maintenance/run! (merge {:from (str (memory-dir "a")) :persist? true :stamp? true
                            :receipts (str (io/file *base* "receipts")) :port nil}
                           opts)))

(defn- id-line-removed
  "`text` without the one `id: '<uuid>'` line the stamp inserts, or `text`
   itself when the line is absent."
  [text id]
  (str/replace-first text (str "id: '" id "'\n") ""))

(defn- stamped-only-with-its-id?
  "The file at `f` is `original` plus exactly the stored id line."
  [f original id]
  (let [now (slurp f)]
    (and (not= now original)
         (= (id-line-removed now id) original)
         (= id (sinks/projected-file-id f)))))

(deftest a-legacy-tree-enrolls-in-one-attended-window
  (map-root!)
  (tiny-tree! "a")
  (let [rels      ["patterns/first.md" "patterns/second.md" "observations/third.md"]
        originals (into {} (map (fn [rel] [rel (slurp (memory-file "a" rel))])) rels)
        {:keys [preview persist stamp]} (enroll!)]
    (testing "preview: nothing refused; every unit plans a minted identity and the root's owner"
      (is (= 3 (:attempted preview)))
      (is (= 0 (:conflict-count preview)) (pr-str (:units preview)))
      (is (every? :identity-minted? (:units preview)))
      (is (every? :document-id (:units preview)))
      (is (every? :owning-project (:units preview))))
    (testing "pinned persist"
      (is (= 3 (:persisted-count persist)) (pr-str persist))
      (is (true? (:reconciled? persist))))
    (testing "stamp: every new document's file receives its id line and nothing else"
      (is (= 3 (:stamped-count stamp)) (pr-str stamp))
      (is (= 0 (:held-count stamp)))
      (is (true? (:complete? stamp)))
      (doseq [rel rels]
        ;; the ident is memory.<dir>/<name>, rebuilt here from the rel-path
        (let [[dir nm] (str/split (subs rel 0 (- (count rel) 3)) #"/")
              ident    (keyword (str "memory." dir) nm)
              id       (stored-id ident)]
          (is (stamped-only-with-its-id? (memory-file "a" rel) (originals rel) id) rel))))
    (testing "the documents: owned by the mapped project, identified, references resolved"
      (let [docs (documents) c (census docs)]
        (is (= 3 (:documents c)))
        (is (= 3 (:with-uuid c)))
        (is (= 3 (:with-owner c)))
        (is (every? #(= :memory.projects/legacy (:mm.memory/owning-project (:entity %))) docs))
        (is (= {:mm/Observation 1 :mm/Pattern 2} (:classes c)))
        (is (= {:edges 5 :resolved 4 :placeholder 1 :unresolvable 0 :placeholders [:memory.protocol/not_in_this_tree]}
               (:references c)))))
    (testing "the enrollment preflight reads clear"
      (let [report (drift/audit-all {:from (root "a")})
            pf     (:enrollment-preflight report)]
        (is (true? (:clear-for-enrollment? pf)) (pr-str (dissoc pf :excluded)))
        (is (= 3 (:ownership-matched-count pf)))))
    (testing "the first strict projection is accepted into the enrolled tree, identity kept, nothing in the global tree"
      (let [e  (db/entity :memory.patterns/first)
            f  (memory-file "a" "patterns/first.md")
            id (stored-id :memory.patterns/first)]
        (is (nil? (refusal #(project! e))))
        (is (= id (sinks/projected-file-id f)) "the canonical rewrite keeps the stamped identity")
        (is (not (.exists (memory-file "global" "patterns/first.md"))))))
    (testing "repeat: a second attended window changes nothing — every stamp already present"
      (let [again (enroll!)]
        (is (= 3 (:persisted-count (:persist again))))
        (is (= 0 (:conflict-count (:persist again))))
        (is (= 0 (:stamped-count (:stamp again))))
        (is (= 3 (:already-present-count (:stamp again))))
        (is (true? (:complete? (:stamp again))))
        (is (= 3 (:with-uuid (census (documents)))))))))

(deftest a-document-owned-before-its-file-is-stamped-is-still-refused-by-the-strict-sink
  ;; The ordinary sink stays strict: identity in the store without the id
  ;; line in the file is refused until the attended stamp.  (The baseline's
  ;; B3', kept as the boundary the stamp exists for.)
  (map-root!)
  (tiny-tree! "a")
  (let [{:keys [persist]} (enroll! {:stamp? false})]
    (is (= 3 (:persisted-count persist)))
    (let [e (db/entity :memory.patterns/second)
          r (refusal #(project! e))]
      (is (some? (:mm/id e)) "identified in the store")
      (is (nil? (sinks/projected-file-id (memory-file "a" "patterns/second.md"))) "not yet in the file")
      (is (= :project-file-ownership-refusal (:sandbar/error r)) (pr-str r)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The forward-reference blocker the 288-file run exposed (the lead's
;; receipt 2861686681841758498: 35 accepted, 231 refused :flow-forbidden).
;; Source: a unit's reference to a target that does not exist yet is
;; `:skipped` (label/label-of-ref returns nil, enforce.clj §4.4) and the
;; unit's upsert creates an ident-only placeholder; the NEXT unit that
;; references that placeholder resolves it (ref->eid finds the eid), and
;; label-of gives an entity without :dt/type the fail-closed
;; unassigned-label, so a public source is refused public -> private — for a
;; document its own later unit would enroll as public.  The oracle below
;; expects the desired outcome.  It does not rely on the filesystem's walk
;; order (the lead's first run showed it grants none): the fixture's real
;; parsed units are handed to the import in an explicit order — first,
;; second, then shared — through a narrowly scoped wrapper of
;; `pg/ingest-units` that reorders what the original returns and changes
;; nothing else (parser, maintenance flow, dispatch and pins are the
;; originals), and both actual enumeration results are asserted.  The
;; identity-only placeholder is seeded as well, so `shared` is a placeholder
;; at both referencing checks and is filled by its own unit last.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- with-unit-order
  "Run `f` with `pg/ingest-units` returning the units the original returns,
   reordered so the sources in `order` come first, in that order; every
   other unit keeps its place after them."
  [order f]
  (let [original pg/ingest-units
        observed (atom [])
        rank     (into {} (map-indexed (fn [i src] [src i])) order)]
    (with-redefs [pg/ingest-units (fn [& args]
                                    (let [units (->> (apply original args)
                                                     (sort-by #(get rank (:source %) (count order)))
                                                     vec)]
                                      (swap! observed conj (mapv :source units))
                                      units))]
      (assoc (f) :fixture-unit-orders @observed))))

(def ^:private forward-order
  ["observations/first.md" "observations/second.md" "patterns/shared.md"])

(defn- refusal-reasons
  "The firewall reasons behind a persist report's refused units, by source."
  [persist]
  (into {} (map (fn [r] [(:source r) (mapv :reason (:violations r))]))
        (:refused persist)))

(deftest a-shared-target-known-only-by-ident-must-not-refuse-the-units-that-reference-it
  (map-root!)
  (legacy-file! "a" "observations/first.md"  {:name "first"  :type "observation" :related ["patterns/shared.md"]})
  (legacy-file! "a" "observations/second.md" {:name "second" :type "observation" :related ["patterns/shared.md" "protocol/outside.md"]})
  (legacy-file! "a" "patterns/shared.md"     {:name "shared" :type "pattern"     :related []})
  ;; the identity-only placeholder an earlier unit would have left behind,
  ;; the explicit unit order below keeps it unresolved until its own unit
  @(d/transact (db/conn) [{:db/ident :memory.patterns/shared}])
  (let [placeholder (db/entity :memory.patterns/shared)]
    (is (some? (:db/id placeholder)))
    (is (nil? (:dt/type placeholder)) "identity only: no class")
    (is (nil? (:mm/id placeholder)))
    (is (= 1 (count (seq (d/datoms (db/db) :eavt (:db/id placeholder))))) "exactly the ident datom"))
  (let [{:keys [preview persist stamp fixture-unit-orders]} (with-unit-order forward-order enroll!)]
    (is (= 0 (:conflict-count preview)))
    (testing "the units ran in the chosen order: both references before the shared unit"
      (is (= forward-order (mapv :source (:units preview))))
      (is (<= 2 (count fixture-unit-orders)) "both preview and persist enumerated the fixture")
      (is (every? #(= forward-order %) fixture-unit-orders) (pr-str fixture-unit-orders)))
    (testing "the desired outcome: all three public documents persist; the shared target is enrolled public by its own unit, over the placeholder"
      (is (= 3 (:persisted-count persist))
          (str "refused: " (pr-str (refusal-reasons persist))))
      (is (= 0 (:refused-count persist)))
      (is (= 3 (:stamped-count stamp)))
      (let [shared (db/entity :memory.patterns/shared)]
        (is (= :mm/Pattern (dt/class-ident-of shared)) "the placeholder became the document")
        (is (some? (:mm/id shared)))
        (is (= :memory.projects/legacy (:mm.memory/owning-project shared)))))
    (testing "what survives: both references to shared resolve to the enrolled document; the outside reference stays a skipped placeholder"
      (let [c (census (documents))]
        (is (= {:edges 3 :resolved 2 :placeholder 1 :unresolvable 0 :placeholders [:memory.protocol/outside]}
               (:references c)))))))

(deftest a-genuinely-private-target-is-still-refused-and-an-unknown-one-is-still-skipped
  ;; the two cases the blocker's fix must not touch
  (map-root!)
  (dt/make :mm/Observation {:db/ident :memory.observations/secret :mm.memory/name "secret"
                            :mm.memory/rel-path "observations/secret.md" :mm.memory/body-raw "Body.\n"
                            :mm/id (java.util.UUID/randomUUID)
                            :mm.memory/owning-project :memory.projects/legacy
                            :mm.memory/visibility :private})
  (legacy-file! "a" "observations/cites_secret.md" {:name "cites_secret" :type "observation" :related ["observations/secret.md"]})
  (legacy-file! "a" "observations/cites_nothing.md" {:name "cites_nothing" :type "observation" :related ["elsewhere/never_imported.md"]})
  (let [{:keys [persist]} (enroll! {:stamp? false})
        reasons (refusal-reasons persist)]
    (is (= 1 (:refused-count persist)) (pr-str reasons))
    (is (some #{:flow-forbidden} (get reasons "observations/cites_secret.md"))
        "a public document may not reference a private one — the genuine refusal stays")
    (is (= 1 (:persisted-count persist)) "the unknown target is skipped, never refused")
    (is (some? (:db/id (db/entity :memory.observations/cites_nothing))))))

(def ^:private pinned-corpus
  (or (System/getenv "SANDBAR_LEGACY_CORPUS")
      "/private/tmp/sandbar-old-bob-enrollment-15226424473085850202"))

(defn- copy-tree! [^java.io.File from ^java.io.File to]
  (doseq [^java.io.File f (file-seq from) :when (.isFile f)]
    (let [rel    (subs (.getPath f) (inc (count (.getPath from))))
          target (io/file to rel)]
      (io/make-parents target)
      (io/copy f target))))


(def ^:private body-only-docs
  "Old Bob's seven docs/*.md have no front matter: the stamp holds them as
   :frontmatter-required.  The operator path excludes them as an explicit
   disposition; what importing them would make is measured separately."
  ["docs/growth_discipline.md" "docs/introspection_protocol.md" "docs/memory_system.md"
   "docs/project_extension_protocol.md" "docs/restructure_authorization.md" "docs/schema.md"
   "docs/writing_good_memory.md"])

(deftest old-bob-enrolls-with-the-index-and-body-only-files-as-explicit-dispositions
  (let [src (io/file pinned-corpus "memory")]
    (if-not (.isDirectory src)
      (println "legacy-enrollment-test: pinned corpus not present at" pinned-corpus "— the 288-file case is skipped (set SANDBAR_LEGACY_CORPUS)")
      (do
        (map-root!)
        (copy-tree! src (memory-dir "a"))
        (let [md-files  (filter #(and (.isFile %) (str/ends-with? (.getName %) ".md")) (file-seq (memory-dir "a")))
              files     (count md-files)
              rel-of    #(subs (.getPath %) (inc (count (.getPath (memory-dir "a")))))
              omitted   (->> md-files (filter #(contains? pg/+default-skip-basenames+ (.getName %))) (map rel-of) sort vec)
              originals (into {} (map (fn [f] [(rel-of f) (slurp f)])) md-files)
              report    (atom {:files files :omitted-by-skip-basenames omitted :excluded-body-only body-only-docs})]
          (is (= 288 files))
          (is (= 15 (count omitted)))
          (testing "what the body-only files would become if imported (measured, not done)"
            (let [units (pg/ingest-units (io/file (memory-dir "a") "docs") {})
                  roots (map #(first (:entities %)) (filter #(= :parsed (:status %)) units))]
              (swap! report assoc :body-only-if-imported
                     (mapv (fn [m] {:rel-path (:mm.memory/rel-path m) :class (:dt/type m) :name (:mm.memory/name m)
                                    :body-head (some-> (:mm.memory/body-raw m) (subs 0 (min 40 (count (:mm.memory/body-raw m)))))})
                           roots))))
          (testing "one attended window with the seven body-only files excluded"
            (let [{:keys [preview persist stamp]} (enroll! {:exclude body-only-docs})
                  docs (documents)
                  c    (census docs)]
              (swap! report assoc
                     :preview (select-keys preview [:attempted :conflict-count :parse-failed-count :unknown-keys-count :excluded-count :skipped-count])
                     :persist (select-keys persist [:attempted :persisted-count :failed-count :refused-count :conflict-count :reconciled?])
                     :failed (:failed persist) :refused (:refused persist) :conflicts (:conflicts persist)
                     :stamp (select-keys stamp [:attempted :stamped-count :already-present-count :held-count :complete?])
                     :held (filterv #(= :held (:outcome %)) (:results stamp))
                     :census c
                     :unknown-keys (take 12 (:unknown-keys preview)))
              ;; 288 files − 15 index files never walked = 273 attempted; − 7 excluded = 266 persisted
              (is (= (- files 15) (:attempted preview)) (pr-str (:preview @report)))
              (is (= 7 (:excluded-count preview)))
              (is (= (- files 15 7) (:persisted-count persist)) (pr-str (:persist @report)))
              (is (= 0 (:conflict-count preview)) (pr-str (take 5 (filter (comp seq :conflicts) (:units preview)))))
              (is (true? (:reconciled? persist)))
              (is (= (:persisted-count persist) (:stamped-count stamp)) (pr-str (:held @report)))
              (is (= 0 (:held-count stamp)))
              (is (true? (:complete? stamp)))
              (is (= (:persisted-count persist) (:documents c) (:with-uuid c) (:with-owner c)))
              (is (= 0 (get-in c [:references :unresolvable])))
              (testing "every stamped file is its original plus exactly its id line"
                (let [bad (for [{:keys [entity ident]} docs
                                :let [rel (:mm.memory/rel-path entity) f (memory-file "a" rel)]
                                :when (not (stamped-only-with-its-id? f (originals rel) (stored-id ident)))]
                            rel)]
                  (is (= [] (vec bad)))))))
          (testing "the enrollment preflight reads clear with the body-only files as the one explicit disposition"
            (let [rep (drift/audit-all {:from (root "a") :exclude body-only-docs})
                  pf  (:enrollment-preflight rep)]
              (swap! report assoc :preflight (select-keys pf [:unresolved-count :error-count :clear-for-enrollment? :ownership-matched-count :invalid-owners-store-wide-count])
                     :preflight-excluded (keys (:excluded pf)))
              (is (true? (:clear-for-enrollment? pf)) (pr-str (dissoc pf :excluded :ownership)))
              (is (= (set body-only-docs) (set (keys (:excluded pf)))))))
          (testing "the ordinary, unfiltered audit — the one the CLI and the verb run — names exactly the seven excluded files and nothing else"
            (let [rep (drift/audit-all {:from (root "a")})
                  pf  (:enrollment-preflight rep)
                  unresolved-paths (set (concat (map :rel-path (:files-without-uuid pf))
                                                (map :rel-path (:ownership pf))
                                                (map :rel-path (:documents-without-uuid pf))
                                                (map :rel-path (:noncanonical-paths pf))
                                                (map :rel-path (:files-of-other-trees pf))
                                                (map :rel-path (:errors pf))))]
              (swap! report assoc :unfiltered-preflight (select-keys pf [:unresolved-count :error-count :clear-for-enrollment?])
                     :unfiltered-unresolved-paths (vec (sort unresolved-paths)))
              (is (false? (:clear-for-enrollment? pf)) "not clear — and it must say why, by name")
              (is (= (set body-only-docs) unresolved-paths) "every remaining finding is one of the seven")
              (is (= [] (:alias-groups pf)))
              (is (= [] (:invalid-owners pf)))
              (is (= [] (:document-collisions pf)))
              (doseq [rel body-only-docs]
                (is (= (originals rel) (slurp (memory-file "a" rel))) (str rel " untouched"))
                (is (empty? (filter #(= rel (:mm.memory/rel-path (:entity %))) (documents))) (str rel " backed by no document")))))
          (testing "first strict projection of one document lands in the enrolled tree with its identity"
            (let [{:keys [entity ident]} (first (filter #(= :mm/Pattern (:class %)) (documents)))
                  rel (:mm.memory/rel-path entity)]
              (is (nil? (refusal #(project! entity))))
              (is (= (stored-id ident) (sinks/projected-file-id (memory-file "a" rel))))
              (is (not (.exists (memory-file "global" rel))))
              (swap! report assoc :first-projection {:rel-path rel :landed-in :enrolled-tree})))
          (println "legacy-enrollment-test — Old Bob enrolled:")
          (println (with-out-str (pprint/pprint @report))))))))
