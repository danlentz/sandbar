(ns sandbar.mcp.typed-ref-coercion-test
  "JSON reference strings must reach the same typed edges as native refs.
   Routing, validation and cardinality-many updates use real disposable DBs."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is use-fixtures]]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.project.destination :as dest]
            [sandbar.search :as search]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(defn- fixture [f]
  (let [base (.toFile (Files/createTempDirectory "typed-ref-" (make-array FileAttribute 0)))
        root (.getCanonicalPath base)
        original config/value]
    (md/register!)
    @(d/transact (db/conn)
                 [{:db/id "ctx" :db/ident :context/ref-public :dt/type :mm/Context
                   :mm.memory/name "reference fixture" :mm.context/firewall-class :public-bottom}
                  {:db/id "proj" :db/ident :memory.projects/ref_project :dt/type :mm/Project
                   :mm.memory/name "reference project" :mm.project/ident :project/ref-fixture
                   :mm.project/default-visibility :public
                   :mm.project/firewall-class :public-bottom :mm.project/runs-in-context "ctx"}
                  {:db/ident :memory.observations/ref_a :dt/type :mm/Observation
                   :mm.memory/name "a" :mm.memory/visibility :public :mm.memory/owning-project "proj"}
                  {:db/ident :memory.observations/ref_b :dt/type :mm/Observation
                   :mm.memory/name "b" :mm.memory/visibility :public :mm.memory/owning-project "proj"}])
    (with-redefs [config/value (fn [k & more]
                                (if (= k :project-roots)
                                  {:project/ref-fixture (str root "/project")}
                                  (apply original k more)))
                  dest/global-root #(str root "/global")]
      (.mkdirs (io/file root "project/memory"))
      (.mkdirs (io/file root "global/memory"))
      (try (f)
           (finally
             (assert (search/await-bm25f-quiescent! 10000))
             (doseq [file (reverse (file-seq base))] (.delete file)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "typed-ref-coercion" :auth? false}) fixture)

(defn- call [verb args] (tools/handle-call 1 {:name verb :arguments args}))
(defn- success? [r] (and (:result r) (not (get-in r [:result :isError])) (not (:error r))))
(defn- payload [r] (some-> (get-in r [:result :content 0 :text]) (json/parse-string true)))
(defn- eid [ident] (:db/id (d/entity (db/db) ident)))
(defn- refs [ident attr]
  (if-let [e (eid ident)]
    (set (d/q '[:find [?target ...] :in $ ?e ?a :where [?e ?a ?target]] (db/db) e attr))
    #{}))
(defn- slots [slug owner]
  {"mm.memory/name" slug "mm.memory/description" "typed reference regression"
   "mm.memory/rel-path" (str "observations/" slug ".md")
   "mm.memory/scope" "project" "mm.memory/visibility" "public"
   "mm.memory/owning-project" owner})
(defn- create [slug owner & [extra]]
  (call "sandbar.entity.create" {"class" ":mm/Observation"
                                 "slots" (merge (slots slug owner) extra)
                                 "validation-mode" "strict"}))

(deftest typed-project-strings-and-native-refs-route-identically
  (doseq [[i owner] (map-indexed vector
                     [":memory.projects/ref_project" "memory.projects/ref_project"
                      :memory.projects/ref_project (eid :memory.projects/ref_project)
                      {:db/id (eid :memory.projects/ref_project)}
                      {:db/ident :memory.projects/ref_project}])]
    (let [slug (str "ref_owner_" i) r (create slug owner)
          ident (keyword "memory.observations" slug)]
      (is (success? r) (pr-str r))
      (is (= #{(eid :memory.projects/ref_project)} (refs ident :mm.memory/owning-project)))
      (is (some? (get-in (payload r) [:entity :mm/id]))))))

(deftest typed-many-create-additive-replace-and-clear
  (let [ident :memory.observations/ref_edges
        a (eid :memory.observations/ref_a) b (eid :memory.observations/ref_b)
        r (create "ref_edges" ":memory.projects/ref_project"
                  {"mm.memory/cites" ["memory.observations/ref_a"]})]
    (is (success? r) (pr-str r))
    (is (= #{a} (refs ident :mm.memory/cites)))
    (doseq [[value additive expected] [[":memory.observations/ref_b" true #{a b}]
                                     [[b] false #{b}]
                                     [[{:db/id a}] true #{a b}]
                                     [[] false #{}]]]
      (let [r (call "sandbar.entity.update"
                    {"entity" (str ident) "slots" {"mm.memory/cites" value}
                     "additive" additive "projection" "full" "validation-mode" "strict"})]
        (is (success? r) (pr-str r))
        (is (= expected (refs ident :mm.memory/cites)))))))

(deftest typed-reference-validation-still-refuses-wrong-class-and-unresolved
  (doseq [[i owner valid?] [[0 "memory.projects/ref_project" true]
                          [1 ":context/ref-public" false]
                          [2 ":memory.projects/not_present" false]]]
    (let [slug (str "ref_validate_" i)
          before (d/basis-t (db/db))
          r (call "sandbar.entity.validate" {"class" ":mm/Observation" "slots" (slots slug owner)})]
      (is (success? r) (pr-str r))
      (is (= valid? (:valid? (payload r))) (pr-str r))
      (is (= before (d/basis-t (db/db))))
      (when-not valid?
        (let [r (create slug owner)]
          (is (not (success? r)) (pr-str r))
          (is (= before (d/basis-t (db/db))))
          (is (nil? (eid (keyword "memory.observations" slug)))))))))
