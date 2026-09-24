(ns sandbar.project.destination-test
  "One disposable store, separate operator-approved trees, real store/sink/retract
   boundaries. The map is persistence authority, never publication permission."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [datomic.api :as d]
            [io.pedestal.connector :as connector]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.project.destination :as dest]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.retract :as retract]
            [sandbar.server.pedestal :as pedestal]
            [sandbar.store :as store]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.util UUID]))

(def ^:dynamic *base* nil)
(def ^:dynamic *roots* nil)
(defn- root [s] (.getCanonicalPath (io/file *base* s)))
(defn- file [r p] (io/file (root r) "memory" p))
(defn- remove-tree! [f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (remove-tree! c)))
  (.delete f))

(defn- fixture [f]
  (let [base (.toFile (Files/createTempDirectory "destination-test-" (make-array FileAttribute 0)))
        original config/value]
    (binding [*base* base *roots* (atom {})]
      (doseq [s ["global" "a" "b" "elsewhere"]] (.mkdirs (file s "")))
      (md/register!)
      @(d/transact (db/conn)
                   (into [{:db/id "public-context" :db/ident :context/route-public :dt/type :mm/Context
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

(use-fixtures :each (tu/make-test-db-fixture {:test-name "project-destination" :auth? false}) fixture)

(defn- props [slug owner path]
  (cond-> {:db/ident (keyword "memory.observations" slug)
           ;; Roots are not identity namespaces. These distinct UUIDs are explicit.
           :mm/id (UUID/nameUUIDFromBytes (.getBytes slug "UTF-8"))
           :mm.memory/rel-path path :mm.memory/name slug
           :mm.memory/description "destination fixture" :mm.memory/memory-type :observation
           :mm.memory/scope :project :mm.memory/visibility (if owner :public :private)
           :mm.memory/body-raw (str "# " slug "\n\nOriginal content.\n")}
    owner (assoc :mm.memory/owning-project owner)))

(defn- create! [slug owner path]
  (store/create-memory! :mm/Observation (props slug owner path)))
(defn- project! [e]
  (let [eid (:db/id e)] (sinks/fs-projection-sink eid (into {:db/id eid} (db/entity eid)))))
(defn- retract! [e]
  (retract/retract! [(:db/id e)] {:persist true :cascade true :reason "isolated destination fixture"}))
(defn- refusal [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest mapped-write-edit-and-retraction-preserve-identity
  (let [path "observations/owned.md"
        e (create! "route_owned" :memory.projects/route_a path)]
    (project! e)
    (is (.isFile (file "a" path)))
    (is (not (.exists (file "global" path))))
    (is (= (str (:mm/id e)) (sinks/projected-file-id (file "a" path))))
    (dt/update-entity! (:db/id e) {:mm.memory/body-raw "# Revised\n\nNew content.\n"})
    (project! e)
    (is (str/includes? (slurp (file "a" path)) "New content."))
    (is (= (:mm/id e) (:mm/id (db/entity (:db/id e)))))
    (let [r (retract! e)]
      (is (= 1 (:files-removed r)))
      (is (not (.exists (file "a" path)))))))

(deftest different-roots-can-hold-the-same-rel-path
  (let [p "observations/same.md"
        a (create! "route_same_a" :memory.projects/route_a p)
        b (create! "route_same_b" :memory.projects/route_b p)]
    (project! a) (project! b)
    (is (.exists (file "a" p))) (is (.exists (file "b" p)))
    (is (= 1 (:files-removed (retract! a))))
    (is (.exists (file "b" p)))
    (is (= (str (:mm/id b)) (sinks/projected-file-id (file "b" p))))))

(deftest global-fallback-and-shared-root-claimants
  (doseq [[slug owner path] [["route_unassigned" nil "observations/unassigned.md"]
                            ["route_unmapped" :memory.projects/route_unmapped "observations/unmapped.md"]]]
    (let [e (create! slug owner path)]
      (project! e)
      (is (.exists (file "global" path)))
      (is (= 1 (:files-removed (retract! e))))))
  (let [p "observations/legacy_collision.md"
        e (create! "route_legacy_owner" :memory.projects/route_unmapped p)]
    (project! e)
    (is (= :rel-path-collision
           (:sandbar/error (refusal #(create! "route_legacy_other" nil p)))))
    ;; Historical unassigned twin: distinct logical key, SAME physical path.
    @(d/transact (db/conn) [{:db/id "twin" :dt/type :mm/Observation
                            :mm.memory/name "twin" :mm.memory/rel-path p}])
    (let [r (retract! e)]
      (is (= :another-entity-claims-the-path (get-in r [:files 0 :reason])))
      (is (.exists (file "global" p))))))

(deftest project-metadata-is-not-filesystem-authority
  (let [p "observations/metadata.md" e (create! "route_metadata" :memory.projects/route_a p)]
    (dt/update-entity! :memory.projects/route_a {:mm.project/corpus-repo (root "elsewhere")})
    (project! e)
    (is (.exists (file "a" p)))
    (is (not (.exists (file "elsewhere" p))))))

(deftest mapped-destination-validation-precedes-port-start
  (let [started (atom false)]
    (doseq [roots [{:project/missing (root "a")}
                  {:project/route-a "relative/path"}
                  {:project/route-a (root "global/nested")}
                  {:project/route-a (root "a") :project/route-b (root "a")}
                  {:project/route-a (root "a") :project/route-b (root "a/nested")}
                  {:project/route-a (.getCanonicalPath *base*)}]]
      (reset! *roots* roots)
      (with-redefs [connector/start! (fn [_] (reset! started true))]
        (is (= :project-destination-refusal
               (:sandbar/error (refusal #(component/start (pedestal/map->Pedestal {:connector :unused}))))))))
    (is (false? @started))))

(deftest traversal-and-wrong-owner-refuse-before-create
  (doseq [p ["../../escape.md" "/absolute.md"]]
    (let [ident (keyword "memory.observations" (str "unsafe_" (count p)))
          attempted (assoc (props "unsafe" :memory.projects/route_a p) :db/ident ident)]
      (is (some? (refusal #(store/create-memory! :mm/Observation attempted)))))
    (is (not (.exists (file "global" "escape.md")))))
  (let [before (d/basis-t (db/db))]
    (is (= :project-destination-refusal
           (:sandbar/error (refusal #(create! "route_bad_owner" :context/route-public "observations/bad.md")))))
    (is (= before (d/basis-t (db/db))))))

(deftest target-changing-edits-and-upserts-refuse-without-changing-files
  (let [p "observations/stay.md" e (create! "route_stay" :memory.projects/route_a p)]
    (project! e)
    (let [original (slurp (file "a" p))
          before (d/basis-t (db/db))]
      (doseq [updates [{:mm.memory/owning-project :memory.projects/route_b}
                       {:mm.memory/rel-path "observations/moved.md"}
                       {:mm/id (UUID/randomUUID)}]]
        (is (= :project-destination-refusal
               (:sandbar/error (refusal #(dt/update-entity! (:db/id e) updates {:validate? false}))))))
      (is (= :project-destination-refusal
             (:sandbar/error (refusal #(store/create-memory! :mm/Observation
                                       (assoc (props "route_stay" :memory.projects/route_b p)
                                              :mm/id (:mm/id e)))))))
      (is (= before (d/basis-t (db/db))))
      (is (= original (slurp (file "a" p))))
      (is (not (.exists (file "b" p)))))))

(deftest mapped-project-key-cannot-be-renamed-by-an-ordinary-edit
  (is (= :mapped-project-key-change
         (:reason (refusal #(dt/update-entity! :memory.projects/route_a
                                             {:mm.project/ident :project/changed} {:validate? false}))))))

(deftest mapped-project-cannot-be-retracted-by-an-ordinary-request
  (let [before (d/basis-t (db/db))
        r (retract/retract! [:memory.projects/route_a]
                           {:persist true :cascade true :reason "isolated protection fixture"
                            :acknowledge-dangling true})]
    (is (zero? (:retracted-count r)))
    (is (true? (get-in r [:targets 0 :protected?])))
    (is (= before (d/basis-t (db/db))))))

(deftest internal-path-aliases-refuse-before-create
  (let [before (d/basis-t (db/db))]
    (doseq [p ["observations/../alias.md" "observations//alias.md" "observations/./alias.md"]]
      (is (= :noncanonical-rel-path
             (:reason (refusal #(create! "route_alias" :memory.projects/route_a p))))))
    (is (= before (d/basis-t (db/db)))))
  (let [folder (file "a" "observations") link (file "a" "alias")]
    (.mkdirs folder)
    (Files/createSymbolicLink (.toPath link) (.toPath folder) (make-array FileAttribute 0))
    (try
      (is (= :noncanonical-rel-path
             (:reason (refusal #(create! "route_alias_link" :memory.projects/route_a "alias/x.md")))))
      (finally (Files/delete (.toPath link))))))

(deftest stale-owner-snapshot-cannot-write-under-the-old-root
  (let [p "observations/stale.md" e (create! "route_stale" :memory.projects/route_a p)
        eid (:db/id e) old (into {:db/id eid} (db/entity eid))]
    ;; Simulate an administrative transaction outside the ordinary edit API.
    @(d/transact (db/conn) [[:db/add eid :mm.memory/owning-project :memory.projects/route_b]])
    (sinks/fs-projection-sink eid old)
    (is (not (.exists (file "a" p))))
    (is (not (.exists (file "b" p))))))

(deftest uncertain-file-ownership-never-overwrites-or-deletes
  (let [p "observations/foreign.md" e (create! "route_foreign" :memory.projects/route_a p)
        f (file "a" p)]
    (.mkdirs (.getParentFile f))
    (doseq [content ["No identity here.\n"
                     "---\nid: 'other'\n---\nForeign\n"
                     (str "---\nid: '" (:mm/id e) "'\nid: '" (:mm/id e) "'\n---\nAmbiguous\n")]]
      (spit f content)
      (is (= :project-file-ownership-refusal (:sandbar/error (refusal #(project! e)))))
      (is (= content (slurp f))))
    (let [r (retract! e)]
      (is (= :file-id-ambiguous (get-in r [:files 0 :reason])))
      (is (.exists f)))))

(deftest symlink-escape-and-config-change-refuse
  (let [link (file "a" "escape")]
    (Files/createSymbolicLink (.toPath link) (.toPath (io/file (root "elsewhere")))
                             (make-array FileAttribute 0))
    (is (= :rel-path-traversal-refusal
           (:sandbar/error (refusal #(create! "route_symlink" :memory.projects/route_a "escape/x.md")))))
    (Files/delete (.toPath link)))
  (let [e (create! "route_changed_map" :memory.projects/route_a "observations/map.md")
        captured (sinks/destination-for (db/db) e)]
    (swap! *roots* assoc :project/route-a (root "elsewhere"))
    (is (= :destination-changed
           (:reason (refusal #(dest/assert-current! captured (root "global"))))))))
