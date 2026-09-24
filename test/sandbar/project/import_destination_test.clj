(ns sandbar.project.import-destination-test
  "Owner verification for imports over the operator's destination map.

   A file's `owning-project` line is the one place an import could let a
   file choose a tree.  These cases pin the rule: a unit walked from a mapped
   root must belong to that root's project; an existing document is never
   moved between trees by an import; the legacy path from a staging
   directory is retained; a conflicting unit is reported in the preview and
   in the persist and never transacted.  Same fixture shape as
   `sandbar.project.destination-test` (the lead's): one disposable store,
   temp roots, the operator map supplied through `config/value`.

   Per codex/reviews/onboarding-wave-2026-09-20/opus-import-destination.md."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.import :as import]
            [sandbar.mcp.tools :as tools]
            [sandbar.project.destination :as dest]
            [sandbar.project.import-destination :as idest]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture — temp roots, the operator map, two mapped projects and one not
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *base* nil)
(def ^:dynamic *roots* nil)

(defn- root [s] (.getCanonicalPath (io/file *base* s)))
(defn- memory-file [r p] (io/file (root r) "memory" p))

(defn- remove-tree! [f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (remove-tree! c)))
  (.delete f))

(defn- fixture [f]
  (let [base     (.toFile (Files/createTempDirectory "import-destination-test-" (make-array FileAttribute 0)))
        original config/value]
    (binding [*base* base *roots* (atom {})]
      (doseq [s ["global" "a" "b" "staging"]] (.mkdirs (memory-file s "")))
      (md/register!)
      @(d/transact (db/conn)
                   (into [{:db/id "public-context" :db/ident :context/import-dest-public :dt/type :mm/Context
                           :mm.memory/name "public" :mm.context/firewall-class :public-bottom}]
                         (for [s ["a" "b" "unmapped"]]
                           {:db/ident (keyword "memory.projects" (str "route_" s))
                            :dt/type :mm/Project :mm.memory/name s
                            :mm.project/ident (keyword "project" (str "route-" s))
                            :mm.project/default-visibility :public
                            :mm.project/firewall-class :public-bottom
                            :mm.project/runs-in-context "public-context"})))
      (reset! *roots* {:project/route-a (root "a") :project/route-b (root "b")})
      (with-redefs [config/value (fn [k & more]
                                  (if (= k :project-roots) @*roots* (apply original k more)))
                    dest/global-root #(root "global")]
        (try (f) (finally (remove-tree! base)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "import-destination" :auth? false}) fixture)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- project-ident [s] (keyword "memory.projects" (str "route_" s)))

(defn- spec
  "A parsed root spec as the codec shapes it: the owner, when present, is the
   `{:db/ident …}` upsert map `coerce-rel-path->ident-upsert` produces."
  [slug owner]
  (cond-> {:db/ident           (keyword "memory.observations" slug)
           :dt/type            :mm/Observation
           :mm.memory/name     slug
           :mm.memory/rel-path (str "observations/" slug ".md")}
    owner (assoc :mm.memory/owning-project {:db/ident owner})))

(defn- stored!
  "A live document, owned by project `s` (nil for an unowned one)."
  [slug s]
  (dt/make :mm/Observation
           (cond-> {:db/ident           (keyword "memory.observations" slug)
                    :mm.memory/name     slug
                    :mm.memory/rel-path (str "observations/" slug ".md")}
             s (assoc :mm.memory/owning-project (project-ident s)))))

(defn- conflicts
  "The destination conflicts for one unit as the handler decides them: the
   importer's plan for `specs` first (replace mode unless `mode` says
   otherwise), then `unit-conflicts` over that plan."
  ([source-dir specs] (conflicts source-dir specs :replace))
  ([source-dir specs mode]
   (let [db (db/db)]
     (idest/unit-conflicts db (idest/classify-root db source-dir)
                           (import/plan-unit db specs {:mode mode})))))

(defn- reasons [cs] (set (map :reason cs)))

(defn- project-spec
  "A parsed Project root spec as the codec shapes a projects/route_<s>.md
   file: `key` is its front matter's `ident:` line, absent when nil."
  [s key]
  (cond-> {:db/ident           (project-ident s)
           :dt/type            :mm/Project
           :mm.memory/name     s
           :mm.memory/rel-path (str "projects/route_" s ".md")}
    key (assoc :mm.project/ident key)))

(defn- key-changes [cs]
  (set (keep #(when (= :import/mapped-project-key-change (:reason %)) (:change %)) cs)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Classification of the walked root
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest classify-root-against-the-operator-map
  (let [db    (db/db)
        shape #(select-keys % [:kind :project-key :root])]
    (is (= {:kind :mapped :project-key :project/route-a :root (root "a")}
           (shape (idest/classify-root db (root "a")))))
    (is (= {:kind :mapped :project-key :project/route-a :root (root "a")}
           (shape (idest/classify-root db (str (root "a") "/memory"))))
        "a root's memory/ subtree is that root")
    (is (= {:kind :global :root (root "global")}
           (shape (idest/classify-root db (root "global")))))
    (is (= :unmapped (:kind (idest/classify-root db (root "staging"))))
        "an ad hoc directory — the maintenance procedure's staging root")
    (is (true? (:enabled? (idest/classify-root db (root "staging")))))
    (is (= (root "staging") (:dir (idest/classify-root db (root "staging")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Under a mapped root
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest mapped-root-new-document-must-name-the-roots-project
  (testing "the correct owner proceeds"
    (is (= [] (conflicts (root "a") [(spec "new_a" (project-ident "a"))]))))
  (testing "a missing owner is refused, not defaulted"
    (let [cs (conflicts (root "a") [(spec "new_none" nil)])]
      (is (= #{:import/owner-missing} (reasons cs)))
      (is (= :project/route-a (:expected (first cs))))))
  (testing "another project's owner is refused"
    (let [cs (conflicts (root "a") [(spec "new_b" (project-ident "b"))])]
      (is (= #{:import/owner-conflict} (reasons cs)))
      (is (= {:expected :project/route-a :actual :project/route-b}
             (select-keys (first cs) [:expected :actual])))))
  (testing "an owner that names nothing, or something that is not a Project, is unresolvable"
    (is (= #{:import/owner-unresolvable}
           (reasons (conflicts (root "a") [(spec "new_nope" :memory.projects/no_such_project)]))))
    (stored! "not_a_project" nil)
    (is (= #{:import/owner-unresolvable}
           (reasons (conflicts (root "a") [(spec "new_wrongkind" :memory.observations/not_a_project)]))))))

(deftest mapped-root-existing-document-keeps-its-tree
  (testing "an existing document owned by the root's project needs no owner line"
    (stored! "kept_a" "a")
    (is (= [] (conflicts (root "a") [(spec "kept_a" nil)]))))
  (testing "a document owned by another project cannot be re-owned from this tree"
    (stored! "of_b" "b")
    (let [cs (conflicts (root "a") [(spec "of_b" (project-ident "a"))])]
      (is (contains? (reasons cs) :import/file-move-requires-maintenance) (pr-str cs))
      (is (= (root "b") (:from-root (first (filter #(= :import/file-move-requires-maintenance (:reason %)) cs)))))
      (is (= (root "a") (:to-root (first (filter #(= :import/file-move-requires-maintenance (:reason %)) cs)))))))
  (testing "a document owned by another project, sitting in this tree without an owner line, is a conflict"
    (stored! "of_b_silent" "b")
    (is (= #{:import/owner-conflict} (reasons (conflicts (root "a") [(spec "of_b_silent" nil)])))))
  (testing "an existing unowned document is not adopted into the tree"
    (stored! "unowned" nil)
    (let [cs (conflicts (root "a") [(spec "unowned" (project-ident "a"))])]
      (is (contains? (reasons cs) :import/file-move-requires-maintenance) (pr-str cs))
      (is (= (root "global") (:from-root (first cs)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; From a staging directory or the global root — the legacy path, minus moves
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest staging-import-cannot-move-a-mapped-document
  (stored! "mapped_a" "a")
  (testing "re-owning a mapped document from staging is a move"
    (is (= #{:import/file-move-requires-maintenance}
           (reasons (conflicts (root "staging") [(spec "mapped_a" (project-ident "b"))])))))
  (testing "the same owner, or no owner line, is the ordinary maintenance edit"
    (is (= [] (conflicts (root "staging") [(spec "mapped_a" (project-ident "a"))])))
    (is (= [] (conflicts (root "staging") [(spec "mapped_a" nil)]))))
  (testing "moving an unowned document into a mapped tree from the global root is refused"
    (stored! "global_doc" nil)
    (is (= #{:import/file-move-requires-maintenance}
           (reasons (conflicts (root "global") [(spec "global_doc" (project-ident "a"))]))))))

(deftest staging-import-keeps-the-legacy-path-for-new-and-unmapped-documents
  (is (= [] (conflicts (root "staging") [(spec "fresh_unowned" nil)])))
  (is (= [] (conflicts (root "staging") [(spec "fresh_a" (project-ident "a"))]))
      "a new document may name a mapped owner; it will project into that tree")
  (is (= [] (conflicts (root "staging") [(spec "fresh_unmapped" (project-ident "unmapped"))])))
  (is (= #{:import/owner-unresolvable}
         (reasons (conflicts (root "staging") [(spec "fresh_nope" :memory.projects/no_such_project)])))))

(deftest without-a-map-nothing-changes
  (reset! *roots* {})
  (stored! "legacy" "a")
  (let [c (idest/classify-root (db/db) (root "a"))]
    (is (= :unmapped (:kind c)))
    (is (false? (:enabled? c)) "an empty map means routing is disabled"))
  (is (= [] (conflicts (root "a") [(spec "legacy" (project-ident "b"))]))
      "with no operator map both owners select the global tree, so no move")
  (is (= [] (conflicts (root "a") [(spec "brand_new" nil)])))
  (testing "the importer's own validation is authoritative: unknown, forward and wrong-class owner refs add nothing here"
    (is (= [] (conflicts (root "a") [(spec "no_owner_yet" :memory.projects/created_later_in_this_import)]))
        "a forward ref to a project this same import creates is the importer's business")
    (is (= [] (conflicts (root "staging") [(spec "unknown_owner" :memory.projects/no_such_project)])))
    (stored! "some_observation" nil)
    (is (= [] (conflicts (root "staging") [(spec "wrong_class_owner" :memory.observations/some_observation)])))
    (stored! "legacy_b" "b")
    (is (= [] (conflicts (root "global") [(spec "legacy_b" (project-ident "a"))]))
        "re-owning with routing disabled is the legacy replace edit, not a move"))
  (testing "no key is mapped, so a Project's key is the importer's business too"
    (is (= [] (conflicts (root "global") [(project-spec "a" :project/renamed)] :replace)))
    (is (= [] (conflicts (root "global") [(project-spec "a" nil)] :replace)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; A currently mapped Project keeps its routing key (the lead's finding,
;; 2026-09-20 20:12Z: apply-plan! bypasses assert-stable-update!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-mapped-project-keeps-its-routing-key-through-an-import
  (let [route-a (db/entity (project-ident "a"))]
    (testing "the file asserts a different key on the key's holder: a rename, in every mode"
      (doseq [mode [:replace :additive]]
        (let [cs (conflicts (root "global") [(project-spec "a" :project/renamed)] mode)
              c  (first (filter #(= :rename (:change %)) cs))]
          (is (= #{:rename} (key-changes cs)) (pr-str mode cs))
          (is (= (:db/id route-a) (:entity c)))
          (is (= :project/route-a (:key c)))
          (is (= :project/renamed (:asserted c))))))
    (testing "the file omits the key in replace mode: the importer's plan retracts it, so the unit is refused"
      (let [plan (import/plan-unit (db/db) [(project-spec "a" nil)] {:mode :replace})
            cs   (conflicts (root "global") [(project-spec "a" nil)] :replace)]
        (is (= :replace (:mode plan)))
        (is (some #(= :mm.project/ident (nth % 2 nil)) (:ops plan))
            "the finding: slot-ops retracts an omitted source-owned slot, the required unique key included")
        (is (= #{:retraction} (key-changes cs)) (pr-str cs))
        (is (= :replace (:mode (first (filter #(= :retraction (:change %)) cs)))))))
    (testing "the same omission in additive mode retracts nothing and passes"
      (let [plan (import/plan-unit (db/db) [(project-spec "a" nil)] {:mode :additive})]
        (is (= [] (:ops plan)))
        (is (= [] (conflicts (root "global") [(project-spec "a" nil)] :additive)))))
    (testing "an unchanged key passes in both modes"
      (is (= [] (conflicts (root "global") [(project-spec "a" :project/route-a)] :replace)))
      (is (= [] (conflicts (root "global") [(project-spec "a" :project/route-a)] :additive))))
    (testing "an unmapped project's key is not this guard's business"
      (is (= [] (conflicts (root "global") [(project-spec "unmapped" :project/renamed)] :replace)))
      (is (= [] (conflicts (root "global") [(project-spec "unmapped" nil)] :replace))))
    (testing "a new document asserting a mapped key another Project holds is a takeover"
      (let [cs (conflicts (root "global") [(project-spec "impostor" :project/route-a)] :replace)
            c  (first (filter #(= :takeover (:change %)) cs))]
        (is (= #{:takeover} (key-changes cs)) (pr-str cs))
        (is (= (:db/id route-a) (:holder c)))
        (is (nil? (:entity c)) "no stored document of its own")))
    (testing "from a staging directory the same guard applies"
      (is (= #{:rename} (key-changes (conflicts (root "staging") [(project-spec "a" :project/renamed)] :replace))))
      (is (= #{:retraction} (key-changes (conflicts (root "staging") [(project-spec "a" nil)] :replace)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Through the verb: preview and persist agree; a conflict transacts nothing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- write-source! [r slug owner-slug]
  (let [f (memory-file r (str "observations/" slug ".md"))]
    (io/make-parents f)
    (spit f (str "---\nname: " slug "\ntype: observation\nscope: global\n"
                 "owning-project: projects/route_" owner-slug ".md\n---\n\n# " slug "\n\nBody.\n"))
    f))

(defn- import! [from persist?]
  (let [r (tools/handle-call 1 {:name "sandbar_project_import"
                                :arguments {"from" from "persist" persist?}})]
    (is (nil? (:error r)) (pr-str r))
    (json/parse-string (get-in r [:result :content 0 :text]) true)))

(deftest a-file-under-a-mapped-root-claiming-another-project-is-refused-in-preview-and-persist
  (write-source! "a" "claims_b" "b")
  (let [basis   (d/basis-t (db/db))
        preview (import! (root "a") false)
        unit    (first (:units preview))]
    (is (= "mapped" (get-in preview [:source-root :kind])))
    (is (= "project/route-a" (get-in preview [:source-root :project-key])))
    (is (= 1 (:conflict-count preview)))
    (is (some #(= "import/owner-conflict" (:reason %)) (:conflicts unit)) (pr-str unit))
    (let [persist (import! (root "a") true)]
      (is (= 0 (:persisted-count persist)))
      (is (= 1 (:conflict-count persist)))
      (is (some #(= "import/owner-conflict" (:reason %))
                (mapcat :conflicts (:conflicts persist)))
          (pr-str (:conflicts persist)))
      (is (true? (:reconciled? persist)))
      (is (nil? (d/entid (db/db) :memory.observations/claims_b)) "nothing was transacted")
      (is (= basis (d/basis-t (db/db))) "the database did not move"))))

(deftest a-file-under-a-mapped-root-naming-its-project-persists
  (write-source! "a" "belongs_a" "a")
  (let [preview (import! (root "a") false)]
    (is (= 0 (:conflict-count preview)) (pr-str (:units preview))))
  (let [persist (import! (root "a") true)
        e       (db/entity :memory.observations/belongs_a)]
    (is (= 1 (:persisted-count persist)) (pr-str persist))
    (is (= 0 (:conflict-count persist)))
    (is (some? (:db/id e)))
    (is (= :memory.projects/route_a (:mm.memory/owning-project e)))))

(defn- write-project-source!
  "A projects/route_<s>.md file in tree `r`, its `ident:` line `key` (a
   keyword; omitted when nil), as the codec parses one for :mm/Project."
  [r s key]
  (let [f (memory-file r (str "projects/route_" s ".md"))]
    (io/make-parents f)
    (spit f (str "---\nname: " s "\ntype: project\nscope: global\n"
                 (when key (str "ident: " (subs (str key) 1) "\n"))
                 "---\n\n# " s "\n\nBody.\n"))
    f))

(defn- import-with! [from persist? args]
  (let [r (tools/handle-call 1 {:name "sandbar_project_import"
                                :arguments (merge {"from" from "persist" persist?} args)})]
    (is (nil? (:error r)) (pr-str r))
    (json/parse-string (get-in r [:result :content 0 :text]) true)))

(deftest a-mapped-project-key-is-protected-through-the-verb
  ;; The invariant, proven where its loss would surface: after each refused
  ;; persist the operator map still validates, the key is still held, and
  ;; the database did not move.
  (let [route-a (project-ident "a")
        roots-before (dest/validate-roots! (db/db) (root "global"))]
    (is (= #{:project/route-a :project/route-b} (set (keys roots-before))))
    (doseq [[label key change] [["omitted (replace mode retracts it)" nil "retraction"]
                                ["renamed" :project/renamed "rename"]]]
      (testing label
        (write-project-source! "global" "a" key)
        (let [basis   (d/basis-t (db/db))
              preview (import! (root "global") false)
              unit    (first (:units preview))
              c       (first (filter #(= "import/mapped-project-key-change" (:reason %)) (:conflicts unit)))]
          (is (= "global" (get-in preview [:source-root :kind])))
          (is (= 1 (:conflict-count preview)) (pr-str (:units preview)))
          (is (= "replace" (:mode unit)) "the stored Project is matched, so this is a replace")
          (is (= change (:change c)) (pr-str unit))
          (is (= "project/route-a" (:key c)))
          (let [persist (import! (root "global") true)]
            (is (= 0 (:persisted-count persist)))
            (is (= 1 (:conflict-count persist)))
            (is (= change (:change (first (filter #(= "import/mapped-project-key-change" (:reason %))
                                                  (mapcat :conflicts (:conflicts persist))))))
                (pr-str (:conflicts persist)))
            (is (true? (:reconciled? persist)))
            (is (= :project/route-a (:mm.project/ident (db/entity route-a))) "the key is still held")
            (is (= basis (d/basis-t (db/db))) "the database did not move")
            (is (= roots-before (dest/validate-roots! (db/db) (root "global")))
                "the operator map still validates: routing stays valid across the service")))))
    (testing "the unchanged key passes, and an additive import that omits it passes"
      (write-project-source! "global" "a" :project/route-a)
      (is (= 0 (:conflict-count (import! (root "global") false))))
      (write-project-source! "global" "a" nil)
      (let [preview (import-with! (root "global") false {"mode" "additive"})
            unit    (first (:units preview))]
        (is (= "additive" (:mode unit)))
        (is (= 0 (:conflict-count preview)) (pr-str (:units preview))))
      (let [persist (import-with! (root "global") true {"mode" "additive"})]
        (is (= 1 (:persisted-count persist)) (pr-str persist))
        (is (= :project/route-a (:mm.project/ident (db/entity route-a))))
        (is (= roots-before (dest/validate-roots! (db/db) (root "global"))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The root-owner default for genuinely new documents under a mapped root
;; (the lead's contract of 2026-09-20 21:29Z, from the legacy enrollment case)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- source-root [dir] (idest/classify-root (db/db) dir))

(defn- with-owner [dir specs] (idest/with-root-owner (db/db) (source-root dir) specs))

(defn- owner-of [specs] (:mm.memory/owning-project (first specs)))

(deftest a-new-document-under-a-mapped-root-that-names-no-owner-is-owned-by-the-roots-project
  (let [specs [(spec "brand_new" nil) {:db/ident :memory.observations/brand_new__1 :dt/type :mm/Section}]
        out   (with-owner (root "a") specs)]
    (is (= {:db/ident (project-ident "a")} (owner-of out)) "the mapped Project, as an ident upsert like the codec's own refs")
    (is (= (rest specs) (rest out)) "the sections are untouched")
    (is (= 2 (count out)))
    (testing "the guard then sees the owner: no conflict"
      (is (= [] (conflicts (root "a") out))))
    (testing "an untyped forward placeholder under the ident is still a new document"
      @(d/transact (db/conn) [{:db/ident :memory.observations/placeholder_first}])
      (is (= {:db/ident (project-ident "a")} (owner-of (with-owner (root "a") [(spec "placeholder_first" nil)])))))))

(deftest the-default-never-adopts-an-existing-document-or-overrides-an-explicit-owner
  (testing "an existing unowned document stays held — unchanged specs, and the guard still reports owner-missing"
    (stored! "held" nil)
    (let [specs [(spec "held" nil)]]
      (is (= specs (with-owner (root "a") specs)))
      (is (= #{:import/owner-missing} (reasons (conflicts (root "a") specs))))))
  (testing "an existing document owned by the root's project survives an omitted line, unchanged"
    (stored! "mine" "a")
    (let [specs [(spec "mine" nil)]]
      (is (= specs (with-owner (root "a") specs)))
      (is (= [] (conflicts (root "a") specs)))))
  (testing "an explicit owner is left alone, and its conflicts stay refused"
    (let [conflicting [(spec "claims_b_new" (project-ident "b"))]
          unresolvable [(spec "unknown_new" :memory.projects/no_such_project)]]
      (is (= conflicting (with-owner (root "a") conflicting)))
      (is (= #{:import/owner-conflict} (reasons (conflicts (root "a") conflicting))))
      (is (= unresolvable (with-owner (root "a") unresolvable)))
      (is (= #{:import/owner-unresolvable} (reasons (conflicts (root "a") unresolvable))))))
  (testing "a move and a mapped-key change are still refused after the default"
    (stored! "moving" "b")
    (let [specs [(spec "moving" (project-ident "a"))]]
      (is (= specs (with-owner (root "a") specs)))
      (is (contains? (reasons (conflicts (root "a") specs)) :import/file-move-requires-maintenance)))
    (let [specs [(project-spec "a" :project/renamed)]]
      (is (= specs (with-owner (root "global") specs)))
      (is (= #{:rename} (key-changes (conflicts (root "global") specs))))))
  (testing "a root spec that is not a document — a Tag — gets no owner"
    (let [specs [{:db/ident :tag/legacy_new :dt/type :mm/Tag :mm.tag/value "legacy-new"}]]
      (is (= specs (with-owner (root "a") specs)))))
  (testing "no default from the global root, an unmapped directory, or with routing disabled"
    (let [specs [(spec "new_global" nil)]]
      (is (= specs (with-owner (root "global") specs)))
      (is (= specs (with-owner (root "staging") specs)))
      (reset! *roots* {})
      (is (= specs (with-owner (root "a") specs))))))

(deftest handler-asserts-the-root-owner-on-a-new-file-that-names-none
  ;; Handler coverage of the planned owner assertion: passes once the import
  ;; handler calls `with-root-owner` before `plan-unit` (the lead's
  ;; integration); until then the mapped preview reports owner-missing.
  (let [f (memory-file "a" "observations/adopted_new.md")]
    (io/make-parents f)
    (spit f "---\nname: adopted_new\ntype: observation\nscope: global\n---\n\n# adopted_new\n\nBody.\n")
    (let [preview (import! (root "a") false)]
      (is (= 0 (:conflict-count preview)) (pr-str (:units preview)))
      (let [persist (import! (root "a") true)
            e       (db/entity :memory.observations/adopted_new)]
        (is (= 1 (:persisted-count persist)) (pr-str persist))
        (is (= :memory.projects/route_a (:mm.memory/owning-project e))
            "the transacted owner is the root's project, not a preview-only decoration")))))
