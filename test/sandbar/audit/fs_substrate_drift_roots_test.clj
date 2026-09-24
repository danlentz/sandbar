(ns sandbar.audit.fs-substrate-drift-roots-test
  "Per-root selection in the FS↔substrate drift audit.

   With an operator `:project-roots` map, auditing one project's tree must
   compare that tree with that project's documents only: the regression this
   guards is a mapped tree reported as missing every document of every other
   tree.  The global root compares with the unmapped population; an ad hoc
   directory keeps the store-wide comparison and says so.  The test store
   is not empty: the bootstrapped `:workflow/session` definition is a real
   document (a rel-path, no owner), so it is counted in every population the
   audit compares — asserted by name, never filtered (the lead's correction
   on the first combined run, 2026-09-20 20:26Z).  Same fixture shape as
   `sandbar.project.destination-test`.

   Per codex/reviews/onboarding-wave-2026-09-20/opus-import-destination.md."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [sandbar.audit.fs-substrate-drift :as drift]
            [sandbar.codec.markdown :as md]
            [sandbar.config :as config]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.project.destination :as dest]
            [sandbar.projection :as pg]
            [sandbar.test-util :as tu])
  (:import [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]))

(def ^:dynamic *base* nil)
(def ^:dynamic *roots* nil)

(defn- root [s] (.getCanonicalPath (io/file *base* s)))
(defn- memory-file [r p] (io/file (root r) "memory" p))

(defn- remove-tree! [f]
  (when (.isDirectory f) (doseq [c (.listFiles f)] (remove-tree! c)))
  (.delete f))

(defn- fixture [f]
  (let [base     (.toFile (Files/createTempDirectory "drift-roots-test-" (make-array FileAttribute 0)))
        original config/value]
    (binding [*base* base *roots* (atom {})]
      (doseq [s ["global" "a" "adhoc"]] (.mkdirs (memory-file s "")))
      (md/register!)
      @(d/transact (db/conn)
                   [{:db/id "public-context" :db/ident :context/drift-roots-public :dt/type :mm/Context
                     :mm.memory/name "public" :mm.context/firewall-class :public-bottom}
                    {:db/ident :memory.projects/drift_a
                     :dt/type :mm/Project :mm.memory/name "a"
                     :mm.project/ident :project/drift-a
                     :mm.project/default-visibility :public
                     :mm.project/firewall-class :public-bottom
                     :mm.project/runs-in-context "public-context"}])
      (reset! *roots* {:project/drift-a (root "a")})
      (with-redefs [config/value (fn [k & more]
                                  (if (= k :project-roots) @*roots* (apply original k more)))
                    dest/global-root #(root "global")]
        (try (f) (finally (remove-tree! base)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "drift-roots" :auth? false}) fixture)

(def seeded-documents
  "The documents the test store holds before a case adds its own: the
   bootstrapped :workflow/session workflow definition carries a rel-path and
   no owner, so it belongs to the global tree and to every store-wide
   comparison.  Counted, never filtered — a fixture that hid it would hide
   the population the audit compares in production too."
  #{:workflow/session})

(defn- documents
  "Every document the audit compares — each entity with a rel-path — by ident
   (`fs-substrate-drift/substrate-memory-entities`' criterion)."
  []
  (set (d/q '[:find [?i ...] :where [?e :mm.memory/rel-path] [?e :db/ident ?i]] (db/db))))

(defn- baseline
  "The seeded population, asserted by name so a bootstrap change fails here
   with the new document named rather than as a count off by one."
  []
  (let [docs (documents)]
    (is (= seeded-documents docs)
        (str "the seeded document population changed; update seeded-documents: " (pr-str docs)))
    docs))

(defn- document!
  "A live document, owned by the mapped project when `owned?`, with its
   canonical file written into tree `r` exactly as the sink would write it."
  [slug r owned?]
  (let [ident (keyword "memory.observations" slug)
        rel   (str "observations/" slug ".md")]
    (dt/make :mm/Observation
             (cond-> {:db/ident ident :mm.memory/name slug :mm.memory/rel-path rel
                      :mm.memory/body-raw "Body.\n"}
               owned? (assoc :mm.memory/owning-project :memory.projects/drift_a)))
    (let [f (memory-file r rel)]
      (io/make-parents f)
      (spit f (pg/realize-and-emit-entity (db/entity ident))))
    ident))

(deftest a-mapped-tree-is-compared-with-its-own-documents-only
  (let [base    (baseline)
        owned   (document! "in_a" "a" true)
        unowned (document! "in_global" "global" false)
        report  (drift/audit-all {:from (root "a")})
        summary (:summary report)]
    (testing "attribution names the tree and its project"
      (is (= :mapped (get-in summary [:root-attribution :kind])))
      (is (= :project/drift-a (get-in summary [:root-attribution :project-key])))
      (is (= (root "a") (get-in summary [:root-attribution :root]))))
    (testing "only the tree's document is compared; the store-wide count, the seeded population included, is still reported"
      (is (= 1 (:substrate-entity-count summary)))
      (is (= (+ 2 (count base)) (:substrate-entity-count-store-wide summary))))
    (testing "neither the global tree's document nor the seeded global document is reported missing from this tree"
      (is (= [] (:missing-from-fs report)) (pr-str (:missing-from-fs report)))
      (is (not (contains? (set (:missing-from-fs report)) unowned)))
      (is (empty? (filter base (:missing-from-fs report)))))
    (testing "the tree's own file is present and its document is present"
      (is (= [] (:missing-from-substrate report)))
      (is (= 0 (:missing-from-fs-count summary))))
    (is (some? owned))))

(deftest the-global-tree-is-compared-with-the-unmapped-population
  (let [base    (baseline)
        _       (document! "in_a2" "a" true)
        unowned (document! "in_global2" "global" false)
        report  (drift/audit-all {:from (root "global")})
        summary (:summary report)]
    (is (= :global (get-in summary [:root-attribution :kind])))
    (is (= (+ 1 (count base)) (:substrate-entity-count summary))
        "the unowned document and the seeded global documents; not the mapped project's")
    (is (= base (set (:missing-from-fs report)))
        "the seeded global document's file is not in this temporary tree, so it is reported; the mapped project's document is not this tree's business")
    (is (= [] (:missing-from-substrate report)))
    (is (some? unowned))))

(deftest an-ad-hoc-directory-keeps-the-store-wide-comparison-and-says-so
  (let [base    (baseline)
        owned   (document! "in_a3" "a" true)
        unowned (document! "in_global3" "global" false)
        report  (drift/audit-all {:from (root "adhoc")})
        summary (:summary report)]
    (is (= :ad-hoc (get-in summary [:root-attribution :kind])))
    (is (string? (get-in summary [:root-attribution :limitation])))
    (is (= (+ 2 (count base)) (:substrate-entity-count summary)) "store-wide, the seeded population included, as before")
    (is (= (into #{owned unowned} base) (set (:missing-from-fs report)))
        "an empty ad hoc directory reports every document missing — the documented meaning, now labelled")))

(deftest a-subdirectory-of-a-mapped-tree-is-a-labelled-partial-diagnostic-not-a-whole-tree-finding
  ;; A walk of <root a>/memory/observations covers part of tree a.  Comparing
  ;; that part with every document of the tree would report the unwalked
  ;; sibling (a decision under decisions/) as missing from the filesystem —
  ;; a false whole-tree finding.  The attribution must therefore not claim
  ;; the mapped tree; it keeps the store-wide diagnostic and says so.
  (let [in-obs   (document! "in_a_obs" "a" true)
        sibling  (keyword "memory.decisions" "in_a_decision")]
    (dt/make :mm/Decision {:db/ident sibling :mm.memory/name "sibling"
                           :mm.memory/rel-path "decisions/in_a_decision.md"
                           :mm.memory/body-raw "Body.\n"
                           :mm.memory/owning-project :memory.projects/drift_a})
    (let [f (memory-file "a" "decisions/in_a_decision.md")]
      (io/make-parents f)
      (spit f (pg/realize-and-emit-entity (db/entity sibling))))
    (let [report  (drift/audit-all {:from (str (root "a") "/memory/observations")})
          summary (:summary report)]
      (is (= :ad-hoc (get-in summary [:root-attribution :kind]))
          "a deeper directory of a configured tree is not the tree")
      (is (= :project/drift-a (get-in summary [:root-attribution :project-key]))
          "the attribution still says which tree the directory belongs to")
      (is (string? (get-in summary [:root-attribution :limitation])))
      (is (contains? (set (:missing-from-fs report)) sibling)
          "the sibling's absence is reported as the store-wide diagnostic the label warns about — not as a whole-tree finding")
      (is (some? in-obs)))))

(deftest without-a-map-the-audit-is-unchanged
  (reset! *roots* {})
  (let [base    (baseline)
        owned   (document! "in_a4" "global" true)
        unowned (document! "in_global4" "global" false)
        report  (drift/audit-all {:from (root "global")})
        summary (:summary report)]
    (is (= :global (get-in summary [:root-attribution :kind])))
    (is (= (+ 2 (count base)) (:substrate-entity-count summary)) "with no map every owner selects the global tree, the seeded population included")
    (is (= base (set (:missing-from-fs report))) "only the seeded document, whose file is not in this temporary tree")
    (is (some? owned))
    (is (some? unowned))))
