(ns sandbar.scripts.maintenance-import-test
  "The quiescent maintenance import: refuses while the server answers; runs
   the retract verb dry run then persist on a named list; runs the import
   preview then the persist pinned to it, with exclusions; writes receipts."
  (:require [clojure.edn            :as edn]
            [clojure.java.io        :as io]
            [clojure.string         :as str]
            [clojure.test           :refer [deftest is use-fixtures]]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic     :as db]
            [sandbar.scripts.maintenance-import :as mi]
            [sandbar.store          :as store]
            [sandbar.test-util      :as tu]))

(use-fixtures :each (fn [f] (md/register!) ((tu/make-test-db-fixture {:test-name "maintenance-import-test" :auth? false}) f)))

(defn- tmp-dir [stem] (let [f (java.io.File/createTempFile (str "mi-" stem "-") "")] (.delete f) (.mkdirs f) f))
(defn- rm-rf! [^java.io.File f] (when (.isDirectory f) (doseq [c (.listFiles f)] (rm-rf! c))) (.delete f))
(defn- write! [root rel content] (let [f (io/file root rel)] (io/make-parents f) (spit f content) f))
(defn- doc [name sections]
  (str "---\ntype: decision\nname: " name "\ndescription: a maintenance probe\n---\n"
       (str/join "\n" (map (fn [[h b]] (str "## " h "\n\n" b "\n")) sections))))

(deftest it-refuses-while-the-server-answers
  (with-open [server (java.net.ServerSocket. 0)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stop it first"
          (mi/run! {:from "/nonexistent" :port (.getLocalPort server)})))))

(deftest a-dry-run-retracts-nothing-and-transacts-nothing-but-writes-every-receipt
  (let [dir (tmp-dir "dry") receipts (tmp-dir "receipts")]
    (try
      (write! dir "decisions/mi_a.md" (doc "MI A" [["One" "One body."]]))
      (let [twin (store/create-memory! :mm/Observation {:mm.memory/rel-path "observations/mi_twin.md" :mm.memory/name "a twin to retract"
                                                         :mm.memory/description "retract me" :mm.memory/memory-type :observation :mm.memory/scope :project})
            result (mi/run! {:from (.getPath dir) :retract [(str (:db/ident twin))] :receipts (.getPath receipts) :port nil})]
        (is (= 1 (count (:targets (:retract-dry-run result)))))
        (is (nil? (:retract result)))
        (is (= 1 (count (:units (:preview result)))))
        (is (nil? (:persist result)))
        (is (some? (:dt/type (d/entity (d/db (db/conn)) (:db/id twin)))) "nothing retracted")
        (is (nil? (d/entid (d/db (db/conn)) :memory.decisions/mi_a)) "nothing transacted")
        (is (= #{"retract-dry-run.edn" "import-preview.edn"} (set (.list receipts))) "receipts for the steps that ran"))
      (finally (rm-rf! dir) (rm-rf! receipts)))))

(deftest a-persist-retracts-the-named-rows-imports-pinned-to-its-preview-and-honours-exclusions
  (let [dir (tmp-dir "persist") receipts (tmp-dir "receipts2")]
    (try
      (write! dir "decisions/mi_b.md" (doc "MI B" [["One" "One body."]]))
      (write! dir "decisions/mi_deferred.md" (doc "MI deferred" [["Kept" "Kept body."]]))
      (let [twin   (store/create-memory! :mm/Observation {:mm.memory/rel-path "observations/mi_twin2.md" :mm.memory/name "a twin to retract"
                                                           :mm.memory/description "retract me" :mm.memory/memory-type :observation :mm.memory/scope :project})
            result (mi/run! {:from (.getPath dir) :persist? true :retract [(str (:db/id twin))] :exclude ["decisions/mi_deferred.md"]
                             :receipts (.getPath receipts) :port nil})]
        (is (= 1 (:retracted-count (:retract result))))
        (is (db/entity-retracted? (:db/id twin)))
        (is (= 1 (:excluded-count (:preview result))))
        (is (= 1 (:persisted-count (:persist result))) (pr-str (:persist result)))
        (is (true? (:reconciled? (:persist result))))
        (is (some? (d/entid (d/db (db/conn)) :memory.decisions/mi_b)) "the unit imported")
        (is (nil? (d/entid (d/db (db/conn)) :memory.decisions/mi_deferred)) "the excluded unit was not transacted")
        (is (= #{"retract-dry-run.edn" "retract-persist.edn" "import-preview.edn" "import-persist.edn"} (set (.list receipts))))
        (let [persisted (edn/read-string (slurp (io/file receipts "import-persist.edn")))]
          (is (= 1 (:persisted-count persisted)) "the receipt is the report")))
      (finally (rm-rf! dir) (rm-rf! receipts)))))
