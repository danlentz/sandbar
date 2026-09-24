(ns sandbar.project.body-update-test
  "Interactive body edits must survive section projection and preserve refs."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.import :as imp]
            [sandbar.mcp.tools :as tools]
            [sandbar.projection :as pg]
            [sandbar.reactive :as reactive]
            [sandbar.shape :as shape]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "body-update" :auth? false}))

(def ident :memory.observations/body_update)
(def before "Preamble.\n\n## Kept\n\nold payload\n\n## Removed\n\nobsolete payload\n")
(def after "New preamble.\n\n## Kept\n\nnew payload\n\n### Added\n\nnested payload\n")

(defn seed! []
  (md/register!)
  (dt/make-all* (md/entity-specs->tx-data
                 (md/parse-document (str "---\nname: Body update\ntype: observation\nstatus: keep-this\n---\n" before)
                                    "observations/body_update.md")))
  (dt/update-entity! ident {:mm/id #uuid "73d7d91b-c805-4e4f-a264-a6379f5ebf8a"}))

(defn call-update [body & [extra]]
  (tools/handle-call 1 {:name "sandbar_entity_update"
                        :arguments (merge {:entity (str ident)
                                           :slots {"mm.memory/body-raw" body}}
                                          extra)}))
(defn failure? [response] (true? (get-in response [:result :isError])))
(defn sections [] (imp/section-tree-eids (db/db) (:db/id (db/entity ident))))
(defn emitted [] (pg/realize-and-emit-entity (db/entity ident)))

(deftest body-edit-reconciles-sections-and-keeps-identity-and-unrelated-slots
  (seed!)
  (let [eid (:db/id (db/entity ident))
        kept-eid (:db/id (db/entity :memory.observations/body_update__kept))
        uuid (:mm/id (db/entity ident))
        r (call-update after)]
    (is (not (failure? r)) (pr-str r))
    (is (= eid (:db/id (db/entity ident))))
    (is (= uuid (:mm/id (db/entity ident))))
    (is (= :keep-this (:mm.memory/status (db/entity ident))))
    (is (= after (:mm.memory/body-raw (db/entity ident))))
    (is (= kept-eid (:db/id (db/entity :memory.observations/body_update__kept))))
    (is (nil? (:dt/type (db/entity :memory.observations/body_update__removed))))
    (is (= 2 (count (sections))))
    (is (str/includes? (emitted) "new payload"))
    (is (str/includes? (emitted) "nested payload"))
    (is (not (str/includes? (emitted) "old payload")))
    (is (not (str/includes? (emitted) "obsolete payload")))))

(deftest deleting-all-headings-removes-derived-links
  (seed!)
  (let [r (call-update "Plain replacement without headings.\n")]
    (is (not (failure? r)) (pr-str r))
    (is (empty? (sections)))
    (is (nil? (:mm.memory/first-section (db/entity ident))))
    (is (str/includes? (emitted) "Plain replacement without headings."))
    (is (not (str/includes? (emitted) "obsolete payload")))))

(deftest referenced-section-removal-refuses-the-whole-edit
  (seed!)
  (dt/make :mm/Memory {:db/ident :memory.observations/citing
                       :mm.memory/cites :memory.observations/body_update__removed})
  (let [basis (d/basis-t (db/db))
        emitted-before (emitted)
        r (call-update after)]
    (is (failure? r))
    (is (str/includes? (get-in r [:result :content 0 :text]) "externally-referenced-section"))
    (is (= basis (d/basis-t (db/db))))
    (is (= before (:mm.memory/body-raw (db/entity ident))))
    (is (= emitted-before (emitted)))
    (is (= :memory.observations/body_update__removed
           (first (:mm.memory/cites (db/entity :memory.observations/citing)))))))

(deftest repeated-body-edit-does-not-accumulate-sections
  (seed!)
  (is (not (failure? (call-update after))))
  (let [first-eids (sections)]
    (is (not (failure? (call-update after))))
    (is (= first-eids (sections)))))

(deftest strict-preflight-still-sees-one-complete-proposed-document
  (seed!)
  (let [basis (d/basis-t (db/db))
        seen (atom nil)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"test refusal"
          (dt/update-entity! ident {:mm.memory/body-raw after}
            {:pre-commit (fn [proposed eid]
                           (reset! seen {:body (:mm.memory/body-raw (d/entity proposed eid))
                                         :sections (count (imp/section-tree-eids proposed eid))})
                           (throw (ex-info "test refusal" {})))})))
    (is (= {:body after :sections 2} @seen))
    (is (= basis (d/basis-t (db/db))))
    (is (= before (:mm.memory/body-raw (db/entity ident))))))

(deftest identless-plain-body-remains-updatable-without-adopting-an-identity
  (let [e (dt/make :mm/Memory {:mm.memory/body-raw "original"})
        updated (dt/update-entity! (:db/id e) {:mm.memory/body-raw "replacement"})]
    (is (= (:db/id e) (:db/id updated)))
    (is (= "replacement" (:mm.memory/body-raw updated)))
    (is (nil? (:db/ident updated)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"identified memory"
          (dt/update-entity! (:db/id e) {:mm.memory/body-raw "## Heading\nContent\n"})))
    (is (= "replacement" (:mm.memory/body-raw (db/entity (:db/id e)))))))

(deftest canonical-rule-body-wins-over-a-stored-legacy-shadow
  (md/register!)
  (dt/make-all* (md/entity-specs->tx-data
                 (md/parse-document (str "---\nname: Canonical rule\ntype: rule\n---\n" before)
                                    "rules/body_update.md")))
  ;; Existing historical rows can still contain a shadow demoted from the
  ;; effective slots. Seed that history; the edit under test uses datatype.
  @(d/transact (db/conn) [{:db/ident :memory.rules/body_update
                          :mm.rule/body-raw "stale legacy shadow"}])
  (dt/update-entity! :memory.rules/body_update {:mm.memory/body-raw after})
  (let [e (db/entity :memory.rules/body_update)
        text (pg/realize-and-emit-entity e)]
    (is (= after (:mm.memory/body-raw e)))
    (is (= "stale legacy shadow" (:mm.rule/body-raw e)))
    (is (str/includes? text "new payload"))
    (is (not (str/includes? text "stale legacy shadow")))))

(deftest changing-identity-with-the-body-refuses-without-changing-either
  (seed!)
  (let [basis (d/basis-t (db/db))
        host (into {} (db/entity ident))
        emitted-before (emitted)
        r (call-update after {:slots {"db/ident" ":memory.observations/renamed"
                                      "mm.memory/body-raw" after}})]
    (is (failure? r) (pr-str r))
    (is (str/includes? (str (get-in r [:result :content 0 :text]))
                       "body-update/identity-change"))
    (is (= basis (d/basis-t (db/db))))
    (is (= host (into {} (db/entity ident))))
    (is (nil? (:dt/type (db/entity :memory.observations/renamed))))
    (is (= emitted-before (emitted)))))

(deftest foreign-section-identity-refuses-with-both-documents-unchanged
  (seed!)
  (let [new-body "## Foreign\nNew content\n"
        section-ident (:db/ident (first (md/parse-sections new-body ident)))
        other :memory.observations/other_owner]
    ;; A historical section retains an ident under this document's path but
    ;; belongs to another document. The editor must not adopt it by upsert.
    @(d/transact (db/conn)
       [{:db/id "other" :db/ident other :dt/type :mm/Observation
         :mm.memory/body-raw "Other original body" :mm.memory/first-section "foreign"}
        {:db/id "foreign" :db/ident section-ident :dt/type :mm/Section
         :mm.section/parent "other" :mm.section/body "Other original section"}])
    (let [basis (d/basis-t (db/db))
          originals (mapv #(into {} (db/entity %)) [ident other section-ident])
          original-output (emitted)
          r (call-update new-body)]
      (is (failure? r) (pr-str r))
      (is (str/includes? (str (get-in r [:result :content 0 :text]))
                         "section-identity-owned-elsewhere"))
      (is (= basis (d/basis-t (db/db))))
      (is (= originals (mapv #(into {} (db/entity %)) [ident other section-ident])))
      (is (= original-output (emitted))))))

(deftest moved-basis-before-body-commit-is-an-actionable-refusal-in-every-mode
  (seed!)
  (let [plan imp/plan-body-update
        other (dt/make :mm/Memory {:mm.memory/name "Independent writer"})
        original-output (emitted)]
    (doseq [mode ["audit" "strict" "disabled"]]
      (testing mode
        (let [committed-basis (atom nil)
              notifications (atom 0)
              r (with-redefs [imp/plan-body-update
                              (fn [& args]
                                (let [p (apply plan args)]
                                  @(d/transact (db/conn) [{:db/id (:db/id other)
                                                         :mm.memory/description mode}])
                                  (reset! committed-basis (d/basis-t (db/db)))
                                  p))
                              reactive/on-entity-changed! (fn [& _] (swap! notifications inc))]
                  (call-update after {:validation-mode mode}))]
          (is (failure? r) (pr-str r))
          (is (nil? (:error r)) (pr-str r))
          (is (str/includes? (str (get-in r [:result :content 0 :text]))
                             "body-update/basis-moved"))
          (is (= @committed-basis (d/basis-t (db/db))))
          (is (= before (:mm.memory/body-raw (db/entity ident))))
          (is (= original-output (emitted)))
          (is (zero? @notifications)))))))

(deftest basis-moved-after-strict-preflight-refuses-the-pinned-plan-once
  (seed!)
  (let [other (dt/make :mm/Memory {:mm.memory/name "Independent writer"})
        original-output (emitted)
        seen (atom [])
        committed-basis (atom nil)
        notifications (atom 0)
        r (with-redefs [shape/validate
                        (fn [proposed eid mode]
                          (swap! seen conj {:body (:mm.memory/body-raw (d/entity proposed eid))
                                            :sections (count (imp/section-tree-eids proposed eid))
                                            :mode mode})
                          @(d/transact (db/conn) [{:db/id (:db/id other)
                                                 :mm.memory/description "Moved after preflight"}])
                          (reset! committed-basis (d/basis-t (db/db)))
                          [])
                        reactive/on-entity-changed! (fn [& _] (swap! notifications inc))]
            (call-update after {:validation-mode "strict"}))]
    (is (failure? r) (pr-str r))
    (is (nil? (:error r)) (pr-str r))
    (is (str/includes? (str (get-in r [:result :content 0 :text])) "body-update/basis-moved"))
    (is (= [{:body after :sections 2 :mode :strict}] @seen))
    (is (= @committed-basis (d/basis-t (db/db))))
    (is (= before (:mm.memory/body-raw (db/entity ident))))
    (is (= original-output (emitted)))
    (is (zero? @notifications))))
