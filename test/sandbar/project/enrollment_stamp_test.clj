(ns sandbar.project.enrollment-stamp-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.db.ref :as ref]
            [sandbar.identifier :as identifier]
            [sandbar.import :as import]
            [sandbar.mcp.tools :as tools]
            [sandbar.project.destination :as destination]
            [sandbar.project.enrollment-stamp :as stamp]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.scripts.maintenance-import :as maintenance]
            [sandbar.test-util :as tu]))

(def ^:dynamic *root* nil)
(defn- path [& parts] (apply io/file *root* parts))
(defn- remove-tree! [f]
  (doseq [child (reverse (file-seq f))] (io/delete-file child true)))

(defn- fixture [f]
  (let [base (.toFile (java.nio.file.Files/createTempDirectory "enrollment-stamp-" (make-array java.nio.file.attribute.FileAttribute 0)))
        original config/value]
    (binding [*root* (.getCanonicalFile base)]
      (doseq [folder ["global/memory" "project/memory" "staged/memory" "receipts"]] (.mkdirs (path folder)))
      @(d/transact (db/conn)
                    [{:db/id "context" :db/ident :test.context/enrollment :dt/type :mm/Context
                     :mm.memory/name "Enrollment context" :mm.context/firewall-class :public-bottom}
                    {:db/id "project" :db/ident :memory.projects/enrollment :dt/type :mm/Project
                     :mm.project/ident :project/enrollment :mm.project/default-visibility :public
                     :mm.project/firewall-class :public-bottom :mm.project/runs-in-context "context"}])
      (with-redefs [config/value (fn [k & more]
                                  (if (= k :project-roots) {:project/enrollment (.getPath (path "project"))}
                                      (apply original k more)))
                    destination/global-root #(.getPath (path "global"))]
        (try (f) (finally (remove-tree! base)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "enrollment-stamp" :auth? false}) fixture)

(def id #uuid "98769e42-f70d-491e-970f-95a0d2898eab")
(defn- doc [newline]
  (str/join newline ["---" "# retain this comment" "type: observation" "name: Legacy café"
                     "description: Keep the source bytes" "created: 2026-04-30"
                     "old-key: keep this  " "---" "## Body" "" "Content with two spaces  " "---" "Final text." ""]))
(defn- write-doc! [folder rel text]
  (let [f (path folder "memory" rel)] (io/make-parents f) (spit f text :encoding "UTF-8") f))
(defn- run-import! [more]
  (maintenance/run! (merge {:from (.getPath (path "project/memory")) :persist? true :port nil
                           :receipts (.getPath (path "receipts"))} more)))

(deftest the-edit-preserves-source-formatting-and-is-repeatable
  (doseq [nl ["\n" "\r\n"]]
    (let [source (doc nl)
          result (stamp/stamp-source source id)
          expected (str/replace-first source (str "old-key: keep this  " nl "---")
                                      (str "old-key: keep this  " nl "id: '" id "'" nl "---"))]
      (is (= :planned (:outcome result)))
      (is (= expected (:content result)) "only one id line inserted; body fences and CRLF retained")
      (is (= {:outcome :already-present :unstamped-source source}
             (stamp/stamp-source expected id))))))

(deftest existing-or-ambiguous-identity-is-not-rewritten
  (doseq [line [(str "id: '" (random-uuid) "'\n") "id:\n" (str "id: '" id "'\nid: '" id "'\n")]]
    (let [source (str "---\n" line "---\nbody\n")]
      (is (= {:outcome :held :reason :foreign-or-ambiguous-id} (stamp/stamp-source source id)))))
  (is (= :frontmatter-required (:reason (stamp/stamp-source "body only" id)))))

(deftest new-identities-use-the-existing-authority-and-existing-identities-survive
  (let [spec {:db/ident :memory.observations/enrollment :dt/type :mm/Observation
              :mm.memory/rel-path "observations/enrollment.md"}
        plan (import/plan-unit (db/db) [spec] {})
        minted (:mm/id (first (:specs plan)))]
    (is (= (identifier/rel-path-uuid "observations/enrollment.md") minted))
    (is (true? (:identity-minted? plan)))
    (is (= id (:mm/id (first (:specs (import/plan-unit (db/db) [(assoc spec :mm/id id)] {}))))))
    @(d/transact (db/conn) [(assoc spec :db/id "existing" :mm/id id)])
    (let [repeat-plan (import/plan-unit (db/db) [spec] {})]
      (is (= :replace (:mode repeat-plan)))
      (is (not (:identity-minted? repeat-plan)))
      (is (nil? (:mm/id (first (:specs repeat-plan)))) "omission preserves the store; no replacement UUID asserted")
      (is (= :identity-conflict (get-in (import/plan-unit (db/db) [(assoc spec :mm/id (random-uuid))] {}) [:conflicts 0 :reason])))))
  (let [spec {:db/ident :memory.observations/old_unidentified :dt/type :mm/Observation
              :mm.memory/rel-path "observations/old_unidentified.md"}]
    @(d/transact (db/conn) [(assoc spec :db/id "old")])
    (is (nil? (get-in (import/plan-unit (db/db) [spec] {}) [:specs 0 :mm/id])))))

(deftest a-forward-placeholder-retains-its-assigned-identity
  @(d/transact (db/conn) [{:db/id "placeholder" :db/ident :memory.observations/forward :mm/id id}])
  (let [spec {:db/ident :memory.observations/forward :dt/type :mm/Observation
              :mm.memory/rel-path "observations/forward.md"}
        plan (import/plan-unit (db/db) [spec] {})]
    (is (= id (get-in plan [:specs 0 :mm/id])))
    (is (false? (:identity-minted? plan)))
    (is (= :identity-conflict (get-in (import/plan-unit (db/db) [(assoc spec :mm/id (random-uuid))] {}) [:conflicts 0 :reason])))))

(deftest maintenance-enrollment-stamps-the-canonical-file-and-enables-strict-projection
  (let [before (doc "\r\n")
        file (write-doc! "project" "observations/enrollment.md" before)
        result (run-import! {:stamp? true})
        ent (db/entity :memory.observations/enrollment)
        eid (:db/id ent)
        uuid (:mm/id ent)
        after (slurp file :encoding "UTF-8")]
    (is (= 1 (get-in result [:persist :persisted-count])) (pr-str result))
    (is (= 1 (get-in result [:stamp :stamped-count])) (pr-str (:stamp result)))
    (is (maintenance/complete? result))
    (is (= uuid (identifier/rel-path-uuid "observations/enrollment.md")))
    (is (= (d/entid (db/db) :memory.projects/enrollment) (ref/ref->eid (db/db) (:mm.memory/owning-project ent))))
    (is (= (:content (stamp/stamp-source before uuid)) after))
    (is (= (:stamp result) (edn/read-string (slurp (path "receipts/identity-stamp.edn")))))
    (let [repeat-stamp (stamp/stamp! (db/db) (.getPath (path "project/memory")) (:persist result))]
      (is (:complete? repeat-stamp))
      (is (= 1 (:already-present-count repeat-stamp)))
      (is (= after (slurp file :encoding "UTF-8"))))
    (let [repeat-import (run-import! {:stamp? true})]
      (is (maintenance/complete? repeat-import) (pr-str repeat-import))
      (is (= eid (:db/id (db/entity :memory.observations/enrollment))))
      (is (= uuid (:mm/id (db/entity :memory.observations/enrollment))))
      (is (= after (slurp file :encoding "UTF-8"))))
    ;; Exercise the first actual body edit, not only a same-content projection.
    @(d/transact (db/conn) [{:db/ident :memory.observations/enrollment_citer
                            :dt/type :mm/Observation :mm.memory/cites eid}])
    (let [body "## Body\n\nEdited after enrollment.\n"
          response (tools/handle-call 1 {:name "sandbar_entity_update"
                                         :arguments {:entity (str eid)
                                                     :slots {"mm.memory/body-raw" body}}})
          current (db/entity eid)]
      (is (map? (:result response)) (pr-str response))
      (is (not (get-in response [:result :isError])) (pr-str response))
      (is (= body (:mm.memory/body-raw current)))
      (is (= uuid (:mm/id current)))
      (is (= eid (d/entid (db/db) :memory.observations/enrollment)))
      (is (= #{eid} (set (map #(ref/ref->eid (db/db) %)
                              (:mm.memory/cites (db/entity :memory.observations/enrollment_citer)))))))
    (sinks/fs-projection-sink eid (into {:db/id eid} (db/entity eid)))
    (is (str/includes? (slurp file) "Edited after enrollment."))
    (is (= (str uuid) (sinks/projected-file-id file)))
    (is (not (.exists (path "global/memory/observations/enrollment.md"))))))

(deftest changed-sources-and-moved-databases-are-held
  (let [file (write-doc! "project" "observations/changed.md" (doc "\n"))
        result (run-import! {})
        changed (str (slurp file) "An operator edit.\n")]
    (spit file changed)
    (let [r (stamp/stamp! (db/db) (.getPath (path "project/memory")) (:persist result))]
      (is (= :source-changed (get-in r [:results 0 :reason])))
      (is (false? (:complete? r)))
      (is (= changed (slurp file))))
    @(d/transact (db/conn) [[:db/add :memory.observations/changed :mm.memory/description "New database state"]])
    (let [r (stamp/stamp! (db/db) (.getPath (path "project/memory")) (:persist result))]
      (is (= :database-basis-moved (get-in r [:results 0 :reason])))
      (is (= changed (slurp file))))))

(deftest a-staging-copy-is-not-stamped-as-the-canonical-destination
  (let [file (write-doc! "staged" "observations/staged.md" (doc "\n"))
        before (slurp file)
        result (run-import! {:from (.getPath (path "staged/memory")) :stamp? true})]
    (is (= 1 (get-in result [:persist :persisted-count])))
    (is (= :source-is-not-canonical-destination (get-in result [:stamp :results 0 :reason])))
    (is (false? (maintenance/complete? result)))
    (is (= before (slurp file)))))

(deftest stamping-requires-a-receipt-directory-before-any-import
  (write-doc! "project" "observations/no_receipt.md" (doc "\n"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires --receipts"
                       (run-import! {:stamp? true :receipts nil})))
  (is (nil? (d/entid (db/db) :memory.observations/no_receipt))))
