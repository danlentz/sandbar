(ns sandbar.mcp.readable-keyword-write-test
  "Raw-slot writes must not mint keywords that cannot round-trip EDN.
   entity.create, entity.update and entity.validate refuse such a value before
   any transaction, naming the slot and the original value; valid keyword
   spellings, references and cardinality-many replace semantics are unchanged.
   Real tool handlers on a disposable DB; the Markdown codec path is not used."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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

;;;; fixture — the routed project, a context and two reference targets, as in
;;;; typed_ref_coercion_test -------------------------------------------------------

(defn- fixture [f]
  (let [base (.toFile (Files/createTempDirectory "readable-kw-" (make-array FileAttribute 0)))
        root (.getCanonicalPath base)
        original config/value]
    (md/register!)
    @(d/transact (db/conn)
                 [{:db/id "ctx" :db/ident :context/kw-public :dt/type :mm/Context
                   :mm.memory/name "keyword fixture" :mm.context/firewall-class :public-bottom}
                  {:db/id "proj" :db/ident :memory.projects/kw_project :dt/type :mm/Project
                   :mm.memory/name "keyword project" :mm.project/ident :project/kw-fixture
                   :mm.project/default-visibility :public
                   :mm.project/firewall-class :public-bottom :mm.project/runs-in-context "ctx"}
                  {:db/ident :memory.observations/kw_ref_a :dt/type :mm/Observation
                   :mm.memory/name "a" :mm.memory/visibility :public :mm.memory/owning-project "proj"}
                  {:db/ident :memory.observations/kw_ref_b :dt/type :mm/Observation
                   :mm.memory/name "b" :mm.memory/visibility :public :mm.memory/owning-project "proj"}])
    (with-redefs [config/value (fn [k & more]
                                (if (= k :project-roots)
                                  {:project/kw-fixture (str root "/project")}
                                  (apply original k more)))
                  dest/global-root #(str root "/global")]
      (.mkdirs (io/file root "project/memory"))
      (.mkdirs (io/file root "global/memory"))
      (try (f)
           (finally
             (assert (search/await-bm25f-quiescent! 10000))
             (doseq [file (reverse (file-seq base))] (.delete file)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "readable-keyword-write" :auth? false}) fixture)

;;;; helpers -----------------------------------------------------------------------

(def owner ":memory.projects/kw_project")
(defn- call [verb args] (tools/handle-call 1 {:name verb :arguments args}))
(defn- success? [r] (and (:result r) (not (get-in r [:result :isError])) (not (:error r))))
(defn- refused? [r] (and (:result r) (true? (get-in r [:result :isError]))))
(defn- payload [r] (some-> (get-in r [:result :content 0 :text]) (json/parse-string true)))
(defn- details [r] (:details (payload r)))
(defn- eid [ident] (:db/id (d/entity (db/db) ident)))
(defn- basis [] (d/basis-t (db/db)))
(defn- refs [ident attr]
  (if-let [e (eid ident)]
    (set (d/q '[:find [?target ...] :in $ ?e ?a :where [?e ?a ?target]] (db/db) e attr))
    #{}))
(defn- slots [slug & [extra]]
  (merge {"mm.memory/name" slug "mm.memory/description" "readable keyword regression"
          "mm.memory/rel-path" (str "observations/" slug ".md")
          "mm.memory/scope" "project" "mm.memory/visibility" "public"
          "mm.memory/owning-project" owner}
         extra))
(defn- create [slug & [extra mode]]
  (call "sandbar.entity.create" {"class" ":mm/Observation" "slots" (slots slug extra)
                                 "validation-mode" (or mode "strict")}))
(defn- readable? [k] (= [k] (edn/read-string (pr-str [k]))))
(defn- refusal-checks
  "The refusal contract every unreadable-keyword write shares: an isError
   result whose details name the type, the slot and the original value, whose
   message carries the original value as `pr-str` prints it, and whose data
   reads back as EDN.  A missing message fails the assertion rather than
   throwing, so a pre-guard run reports failures, not errors."
  [r slot value]
  (let [d (details r)]
    (is (refused? r) (pr-str r))
    (is (= "mcp/unreadable-keyword" (:type d)) (pr-str d))
    (is (= slot (:slot d)))
    (is (= value (:value d)))
    (is (string? (:keyword-text d)))
    (is (str/includes? (or (:message (payload r)) "") (pr-str value))
        (pr-str (:message (payload r))))
    (is (= d (edn/read-string (pr-str d))) "diagnostic data must itself round-trip")))

;;;; create --------------------------------------------------------------------------

(deftest create-refuses-unreadable-keyword-slot-values
  (doseq [[i value] (map-indexed vector ["when public fern library emerges" "soon;ish"
                                         "future — gated on metrics" "a,b" "x\"y"])]
    (let [slug (str "kw_create_" i) before (basis)
          r (create slug {"mm.memory/scope" value})]
      (refusal-checks r "mm.memory/scope" value)
      (is (= before (basis)) "nothing transacted")
      (is (nil? (eid (keyword "memory.observations" slug)))))))

(deftest create-refuses-unreadable-explicit-ident
  (let [before (basis)
        bad (keyword "memory.observations" "bad name")
        r (create "kw_ident" {"db/ident" ":memory.observations/bad name"})]
    (refusal-checks r "db/ident" ":memory.observations/bad name")
    (is (= before (basis)))
    (is (nil? (eid bad)))
    (is (nil? (eid :memory.observations/kw_ident)) "the well-formed derived ident is not minted either")))

(deftest create-accepts-valid-keyword-spellings
  (doseq [[i value] (map-indexed vector ["global" "2026-Q3" "future—gated" "café" "horizon/near"])]
    (let [slug (str "kw_ok_" i) ident (keyword "memory.observations" slug)
          ;; shapes disabled: this asserts the keyword guard alone, not a scope enum
          r (create slug {"mm.memory/scope" value} "disabled")]
      (is (success? r) (pr-str r))
      (let [stored (:mm.memory/scope (d/entity (db/db) ident))]
        (is (= (keyword value) stored))
        (is (readable? stored)))))
  (testing "a readable unicode explicit ident is accepted and addressable"
    (let [ident (keyword "memory.observations" "naïve→ok")
          ;; the rel-path pairs with the explicit ident, as the corpus convention derives it
          r (create "kw_unicode" {"db/ident" ":memory.observations/naïve→ok"
                                  "mm.memory/rel-path" "observations/naïve→ok.md"} "disabled")]
      (is (success? r) (pr-str r))
      (is (some? (eid ident)))
      (is (readable? ident))
      (is (some? (get (payload (call "sandbar.entity.find" {"ident" ":memory.observations/naïve→ok"})) :entity))))))

;;;; update ---------------------------------------------------------------------------

(def ^:private projection
  '[:db/id :mm/id :mm.memory/name :mm.memory/description :mm.memory/scope
    :mm.memory/body-raw {:mm.memory/cites [:db/ident]}])

(deftest update-refusal-preserves-the-entity-and-writes-no-valid-field
  (let [ident :memory.observations/kw_target
        r (create "kw_target" {"mm.memory/body-raw" "Body to keep.\n"
                               "mm.memory/cites" [":memory.observations/kw_ref_a"]})]
    (is (success? r) (pr-str r))
    (let [before (d/pull (db/db) projection ident) basis-before (basis)]
      (is (some? (:mm/id before)))
      (testing "a card-one keyword slot beside a valid scalar edit"
        (let [r (call "sandbar.entity.update"
                      {"entity" (str ident) "projection" "full" "validation-mode" "strict"
                       "slots" {"mm.memory/description" "changed" "mm.memory/scope" "soon;ish"}})]
          (refusal-checks r "mm.memory/scope" "soon;ish")
          (is (= before (d/pull (db/db) projection ident)) "uuid, eid, body, references and the valid field untouched")
          (is (= basis-before (basis)))))
      (testing "a card-many reference list with one unreadable member writes nothing"
        (let [r (call "sandbar.entity.update"
                      {"entity" (str ident) "projection" "full" "validation-mode" "strict"
                       "slots" {"mm.memory/cites" [":memory.observations/kw_ref_b"
                                                   ":memory.observations/two words"]}})]
          (refusal-checks r "mm.memory/cites" ":memory.observations/two words")
          (is (= 1 (:index (details r))) "the offending member is named by index")
          (is (= #{(eid :memory.observations/kw_ref_a)} (refs ident :mm.memory/cites)) "no partial replace")
          (is (= before (d/pull (db/db) projection ident)))
          (is (= basis-before (basis)))))
      (testing "the same valid edits succeed once the bad value is gone"
        (let [r (call "sandbar.entity.update"
                      {"entity" (str ident) "projection" "full" "validation-mode" "strict"
                       "slots" {"mm.memory/description" "changed" "mm.memory/scope" "global"
                                "mm.memory/cites" [":memory.observations/kw_ref_b"]}})]
          (is (success? r) (pr-str r))
          (let [after (d/pull (db/db) projection ident)]
            (is (= (:mm/id before) (:mm/id after)))
            (is (= (:db/id before) (:db/id after)))
            (is (= "changed" (:mm.memory/description after)))
            (is (= :global (:mm.memory/scope after)))
            (is (= (:mm.memory/body-raw before) (:mm.memory/body-raw after)))
            (is (= #{(eid :memory.observations/kw_ref_b)} (refs ident :mm.memory/cites)) "replace semantics unchanged")))))))

;;;; validate -----------------------------------------------------------------------

(deftest validate-refuses-unreadable-keywords-and-never-writes
  (let [before (basis)]
    (let [r (call "sandbar.entity.validate" {"class" ":mm/Observation"
                                              "slots" (slots "kw_validate" {"mm.memory/scope" "soon;ish"})})]
      (refusal-checks r "mm.memory/scope" "soon;ish"))
    (let [r (call "sandbar.entity.validate" {"class" ":mm/Observation"
                                              "slots" (slots "kw_validate" {"mm.memory/scope" "2026-Q3"})})]
      (is (success? r) (pr-str r))
      (is (true? (:valid? (payload r)))))
    (is (= before (basis)) "validate never transacts")
    (is (nil? (eid :memory.observations/kw_validate)))))

;;;; reference forms at this boundary ----------------------------------------------

(deftest reference-forms-are-refused-or-unchanged-as-declared
  (let [bad (keyword "memory.observations" "two words")
        a (eid :memory.observations/kw_ref_a)]
    (testing "a reference STRING that would mint an unreadable ident is refused"
      (let [before (basis)
            r (create "kw_ref_string" {"mm.memory/cites" [":memory.observations/two words"]})]
        (refusal-checks r "mm.memory/cites" ":memory.observations/two words")
        (is (= 0 (:index (details r))))
        (is (= before (basis)))
        (is (nil? (eid bad)))))
    (testing "an in-process upsert map carrying an unreadable ident keyword is refused, not minted"
      (let [before (basis)
            r (create "kw_ref_map" {"mm.memory/cites" [{:db/ident bad}]})]
        (refusal-checks r "mm.memory/cites" (str {:db/ident bad}))
        (is (= before (basis)))
        (is (nil? (eid bad)))))
    (testing "the JSON-shaped map form (ident STRING under :db/ident) is refused by the same contract and mints neither host nor target"
      ;; the transactor coerces a string at :db/ident to the keyword and mints
      ;; the target when no entity carries it, so this form is a minting path
      (doseq [[i m] (map-indexed vector [{:db/ident ":memory.observations/two words"}
                                         {:db/ident "memory.observations/two words"}])]
        (let [before (basis) slug (str "kw_ref_map_string_" i)
              r (create slug {"mm.memory/cites" [{:db/ident ":memory.observations/kw_ref_a"} m]})]
          (refusal-checks r "mm.memory/cites" (str m))
          (is (= 1 (:index (details r))))
          (is (= before (basis)) "no partial transaction")
          (is (nil? (eid bad)))
          (is (nil? (eid (keyword "memory.observations" slug)))))))
    (testing "a reference map at a cardinality-one slot is checked the same way"
      (let [before (basis)
            r (create "kw_ref_map_one" {"mm.memory/owning-project" {:db/ident ":memory.projects/two words"}})]
        (refusal-checks r "mm.memory/owning-project" (str {:db/ident ":memory.projects/two words"}))
        (is (nil? (:index (details r))))
        (is (= before (basis)))
        (is (nil? (eid (keyword "memory.projects" "two words"))))
        (is (nil? (eid :memory.observations/kw_ref_map_one)))))
    (testing "existing typed-reference forms are unchanged"
      (doseq [[i value] (map-indexed vector [[":memory.observations/kw_ref_a"] [a] [{:db/id a}]
                                             [{:db/ident ":memory.observations/kw_ref_a"}]
                                             [{:db/ident :memory.observations/kw_ref_a}]])]
        (let [slug (str "kw_ref_ok_" i) r (create slug {"mm.memory/cites" value})]
          (is (success? r) (pr-str r))
          (is (= #{a} (refs (keyword "memory.observations" slug) :mm.memory/cites))))))))
