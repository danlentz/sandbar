(ns sandbar.reactive.create-path-fs-projection-e2e-test
  "it6 END-TO-END: the create → reactive-fs-sink → corpus FILE bijection the bug
   broke.  Drives the CANONICAL create path (sandbar.store/create-memory!) then
   the REAL sink callback (sandbar.reactive.sinks/fs-projection-sink — the exact
   `[eid post-tx-slots]` fn the queue drain invokes) against a SCRATCH
   corpus-root, so an actual `.md` file is observed on disk.  The live corpus is
   never touched (`corpus-root` is redef'd to an isolated tmp dir).

   Three cases:
     A. create WITHOUT rel-path but WITH an explicit memory :db/ident →
        rel-path derived from the ident, :mm/id minted, FS file materializes.
     B. create with NEITHER rel-path NOR ident → loud reject, NO orphan.
     C. create WITH rel-path (baseline) → FS file materializes at that path.

   Per bugs/entity_create_codec_path_mints_identless_relpathless_entities_fs_-
   projection_silently_skipped_2026_07_08."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools :as tools]
            [sandbar.reactive.sinks :as sinks]
            [sandbar.store :as store]
            [sandbar.test-util :as tu])
  (:import [java.io File]))

(def ^:dynamic *root* nil)

(defn- delete-tree! [^File f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (delete-tree! c)))
  (.delete f))

(defn scratch-root-fixture [f]
  (let [root (io/file (System/getProperty "java.io.tmpdir")
                      (str "it6-e2e-" (System/currentTimeMillis) "-" (rand-int 1000000)))]
    (.mkdirs (io/file root "memory"))
    ;; The reactive sink emits via the codec mediator; register the markdown
    ;; codec (server startup does this in production).  Idempotent.
    (codec-md/register!)
    (binding [*root* root]
      (with-redefs [sinks/corpus-root (constantly (.getPath root))]
        (try (f) (finally (delete-tree! root)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "it6-e2e"}) scratch-root-fixture)

(defn- project!
  "Create `class`/`props` via the canonical store path, then invoke the REAL
   fs-projection-sink on the read-back entity — exactly the `[eid post-tx-slots]`
   contract the reactive drain uses.  Returns the created entity map."
  [class props]
  (let [e     (store/create-memory! class props)
        eid   (:db/id e)
        slots (into {:db/id eid} (db/entity eid))]
    (sinks/fs-projection-sink eid slots)
    e))

(defn- corpus-file ^File [rel-path]
  (io/file *root* "memory" rel-path))

(deftest a-create-without-rel-path-with-ident-projects-a-file
  (testing "explicit memory :db/ident, NO rel-path → ident preserved + rel-path
            derived + :mm/id minted + a corpus .md FILE appears on disk"
    (let [e (project! :mm/Memory
                      {:db/ident              :memory.decisions/it6_e2e_from_ident
                       :mm.memory/name        "it6 e2e from ident"
                       :mm.memory/memory-type :decision
                       :mm.memory/body-raw    "e2e body from ident"})]
      (is (= :memory.decisions/it6_e2e_from_ident (:db/ident e)))
      (is (= "decisions/it6_e2e_from_ident.md" (:mm.memory/rel-path e)))
      (is (uuid? (:mm/id e)))
      (is (.exists (corpus-file "decisions/it6_e2e_from_ident.md"))
          "the reactive sink wrote the derived corpus file (bijection restored)"))))

(deftest b-create-with-neither-rel-path-nor-ident-is-rejected-no-orphan
  (testing "NEITHER rel-path NOR ident → loud reject; no entity, no file"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"rel-path"
          (store/create-memory! :mm/Memory
                                {:mm.memory/name        "it6 e2e orphan attempt"
                                 :mm.memory/memory-type :decision
                                 :mm.memory/body-raw    "should never land"})))
    (is (not (.exists (corpus-file "decisions/it6_e2e_orphan_attempt.md")))
        "no corpus file for a rejected create")))

(deftest c-create-with-rel-path-projects-a-file
  (testing "explicit rel-path (baseline) → ident derived + a corpus .md FILE appears"
    (let [e (project! :mm/Memory
                      {:mm.memory/rel-path    "decisions/it6_e2e_with_relpath.md"
                       :mm.memory/name        "it6 e2e with relpath"
                       :mm.memory/memory-type :decision
                       :mm.memory/body-raw    "e2e body with relpath"})]
      (is (= :memory.decisions/it6_e2e_with_relpath (:db/ident e)))
      (is (uuid? (:mm/id e)))
      (is (.exists (corpus-file "decisions/it6_e2e_with_relpath.md"))
          "the reactive sink wrote the corpus file at the supplied rel-path"))))

(deftest d-mcp-entity-create-handler-echo-carries-db-ident
  (testing "the REAL MCP entity.create handler echo carries :db/ident + a
            rel-path (derived from the ident when the slot is absent), and
            loud-rejects a create with neither"
    ;; ident supplied, NO rel-path slot → echo carries db/ident + derived rel-path
    (let [resp (#'tools/entity-create-handler
                {"class" ":mm/Memory"
                 "slots" {"db/ident"          ":memory.decisions/it6_mcp_echo"
                          "mm.memory/name"    "it6 mcp echo"
                          "mm.memory/body-raw" "handler echo body"}})
          ent  (:entity resp)]
      (is (= :memory.decisions/it6_mcp_echo (:db/ident ent))
          "MCP echo carries :db/ident (not identless)")
      (is (= "decisions/it6_mcp_echo.md" (:mm.memory/rel-path ent))
          "MCP echo carries the ident-derived rel-path")
      (is (uuid? (:mm/id ent)) "MCP echo carries the minted :mm/id"))
    ;; neither rel-path nor ident → the handler loud-rejects (surfaces to envelope)
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo #"rel-path"
          (#'tools/entity-create-handler
           {"class" ":mm/Memory"
            "slots" {"mm.memory/name" "it6 mcp orphan attempt"}})))))
