(ns sandbar.project.import-replace-test
  "A re-imported managed file ASSERTS its current content (REP-03 of Astra's
   0.2.0 representation review, folded into D7 on 2026-09-20, on the terms of
   her 2026-09-20 answer): the source-owned representation is replaced,
   obsolete sections, links and carrier contents are retracted in the same
   per-file transaction, while the host's identity, the refs into it, and the
   facts the substrate or another owner maintains are kept; additive stays an
   explicit mode; ambiguity is refused, never guessed.

   Her reproduction at 7fd481f: first import a Decision with tags A and B, an
   unknown `custom-key` and sections One and Two; edit the file to keep only
   tag A and section One and drop the key; import reported one persisted
   group and zero failures, but the rendering still carried B, `custom-key`
   and section Two; a third import of a headingless replacement kept both old
   sections.

   What this pins, through the REAL dispatched verb against a fresh store:
     - her edit-and-remove case: the dropped tag membership, section and
       carrier are gone after the re-import; the shared tag target survives;
     - reorder and rename of sections: the new order emits, the renamed
       section's old entity is retracted, the first-section link follows;
     - a scalar slot edited replaces, an omitted authored slot is retracted,
       an omitted substrate-owned slot (created-by) is kept;
     - a headingless replacement retracts every section;
     - a section another record references refuses the unit as a conflict,
       and nothing about it changes;
     - a file whose `id:` differs from the stored `mm/id` refuses the unit;
     - repeated unchanged imports change no datom of the entity;
     - additive mode keeps the pre-D7 behaviour;
     - the dry run plans each unit and reports the same modes and conflicts;
     - a persist pinned to a stale basis is refused."
  (:require [cheshire.core          :as json]
            [clojure.java.io        :as io]
            [clojure.string         :as str]
            [clojure.test           :refer [deftest is testing use-fixtures]]
            [datomic.api            :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]
            [sandbar.import         :as import]
            [sandbar.mcp.tools      :as tools]
            [sandbar.projection     :as pg]
            [sandbar.test-util      :as tu]))

(use-fixtures :each
  (fn [f]
    (md/register!)
    ((tu/make-test-db-fixture {:test-name "import-replace-test" :auth? false}) f)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fresh-tmp-dir ^java.io.File [stem]
  (let [f (java.io.File/createTempFile (str "import-replace-" stem "-") "")]
    (.delete f) (.mkdirs f) f))

(defn- rm-rf! [^java.io.File f]
  (when (.isDirectory f)
    (doseq [c (.listFiles f)] (rm-rf! c)))
  (.delete f))

(defn- write! [^java.io.File root rel content]
  (let [f (io/file root rel)] (io/make-parents f) (spit f content) f))

(defn- call-import [arguments]
  (let [response (tools/handle-call 1 {:name "sandbar.project.import" :arguments arguments})
        result   (:result response)]
    (is (map? result) (str "no :result — " (pr-str response)))
    (is (not (:isError result)) (str "isError — " (-> result :content first :text)))
    (some-> result :content first :text (json/parse-string true))))

(defn- import! [dir & [extra]]
  (call-import (merge {"from" (.getPath dir) "persist" true} extra)))

(defn- dry-run [dir & [extra]]
  (call-import (merge {"from" (.getPath dir)} extra)))

(def ^:private rel-path "decisions/replace_probe.md")
(def ^:private ident :memory.decisions/replace_probe)

(defn- doc
  "A decision document from parts: `tags` (vec of strings), `extra` (an
   unknown key line or nil), `sections` (vec of [heading body]), `slots`
   (extra front-matter lines)."
  [{:keys [tags extra sections slots description]
    :or   {tags ["alpha" "beta"] sections [["One" "One body."] ["Two" "Two body."]] description "the replacement probe"}}]
  (str "---\ntype: decision\nname: Replacement probe\ndescription: " description "\n"
       (when (seq tags) (str "tags:\n" (str/join (map #(str "  - " % "\n") tags))))
       (when extra (str extra "\n"))
       (when slots (str slots "\n"))
       "---\n"
       (str/join "\n" (map (fn [[h b]] (str "## " h "\n\n" b "\n")) sections))))

(defn- entity [] (d/entity (d/db (db/conn)) ident))

(defn- tag-values []
  (set (map #(:mm.tag/value (d/entity (d/db (db/conn)) (if (keyword? %) (d/entid (d/db (db/conn)) %) (:db/id %))))
            (:mm.memory/tags (entity)))))

(defn- section-headings []
  (->> (d/q '[:find [?h ...] :in $ ?m :where [?s :mm.section/parent ?m] [?s :mm.section/heading ?h]]
            (d/db (db/conn)) ident)
       set))

(defn- rendered [] (pg/realize-and-emit-entity (entity)))

(defn- emitted-headings [text]
  (->> (str/split-lines text) (filter #(re-find #"^#{1,6} " %)) vec))

(defn- tag-entity-exists? [value]
  (some? (d/q '[:find ?t . :in $ ?v :where [?t :mm.tag/value ?v]] (d/db (db/conn)) value)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; her edit-and-remove case
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-re-import-drops-what-the-file-dropped-and-keeps-shared-targets
  (let [dir (fresh-tmp-dir "edit")]
    (try
      (write! dir rel-path (doc {:tags ["alpha" "beta"] :extra "custom-key: kept for now" :sections [["One" "One body."] ["Two" "Two body."]]}))
      (let [first-report (import! dir)]
        (is (= "insert" (-> first-report :persisted first :mode)))
        (is (= #{"alpha" "beta"} (tag-values)))
        (is (= #{"One" "Two"} (section-headings)))
        (is (some? (:mm.memory/frontmatter (entity))) "the unknown key rode in the carrier"))
      (write! dir rel-path (doc {:tags ["alpha"] :sections [["One" "One body."]]}))
      (let [report (import! dir)
            rec    (-> report :persisted first)]
        (is (= 1 (:persisted-count report)) (pr-str report))
        (is (= "replace" (:mode rec)))
        (is (= 1 (:retracted-sections rec)))
        (is (true? (:retracted-carrier? rec)))
        (is (= #{"alpha"} (tag-values)) "the dropped membership is gone")
        (is (tag-entity-exists? "beta") "the shared tag target survives")
        (is (= #{"One"} (section-headings)) "the dropped section is gone")
        (is (nil? (:mm.memory/frontmatter (entity))) "the carrier is gone with its last key")
        (is (= ["## One"] (emitted-headings (rendered))) "the rendering shows only what the file holds"))
      (finally (rm-rf! dir)))))

(deftest reorder-and-rename-follow-the-file
  (let [dir (fresh-tmp-dir "reorder")]
    (try
      (write! dir rel-path (doc {:sections [["One" "One body."] ["Two" "Two body."]]}))
      (import! dir)
      (write! dir rel-path (doc {:sections [["Two" "Two body."] ["Three" "Three body."]]}))
      (let [one-eid (d/entid (d/db (db/conn)) :memory.decisions/replace_probe__one)
            report  (import! dir)]
        (is (= "replace" (-> report :persisted first :mode)))
        (is (= 1 (-> report :persisted first :retracted-sections)) "One is gone")
        (is (= #{"Two" "Three"} (section-headings)))
        (is (= ["## Two" "## Three"] (emitted-headings (rendered))) "the new order emits")
        (is (db/entity-retracted? one-eid)
            "the retracted section's entity is gone (Datomic keeps resolving its ident to the old eid)"))
      (finally (rm-rf! dir)))))

(deftest scalars-replace-authored-omissions-retract-and-substrate-owned-facts-stay
  (let [dir (fresh-tmp-dir "scalars")]
    (try
      (write! dir rel-path (doc {:description "first description" :slots "importance: high"}))
      (import! dir)
      (let [created-by (:mm.memory/created-by (entity))]
        ;; give the entity a substrate-owned fact the file never carries
        @(d/transact (db/conn) [{:db/id (d/entid (d/db (db/conn)) ident) :mm.memory/last-touched (java.util.Date.)}])
        (is (= :high (:mm.memory/importance (entity))))
        (write! dir rel-path (doc {:description "second description"}))
        (let [report (import! dir)
              e      (entity)]
          (is (= "replace" (-> report :persisted first :mode)))
          (is (= "second description" (:mm.memory/description e)) "the edited scalar replaced")
          (is (nil? (:mm.memory/importance e)) "the omitted authored slot is retracted")
          (is (some? (:mm.memory/last-touched e)) "the substrate-owned stamp is kept")
          (is (= created-by (:mm.memory/created-by e)) "provenance is kept")
          (is (pos? (-> report :persisted first :retracted-slots)))
          (is (= ["mm.memory/importance"] (-> report :persisted first :retracted-slot-attrs))
              "the report names the attribute behind the count (keywords cross the wire as strings)")))
      (finally (rm-rf! dir)))))

(deftest a-headingless-replacement-retracts-every-section
  (let [dir (fresh-tmp-dir "headingless")]
    (try
      (write! dir rel-path (doc {:sections [["One" "One body."] ["Two" "Two body."]]}))
      (import! dir)
      (write! dir rel-path (str "---\ntype: decision\nname: Replacement probe\ndescription: now headingless\ntags:\n  - alpha\n---\nJust a body.\n"))
      (let [report (import! dir)
            e      (entity)]
        (is (= 2 (-> report :persisted first :retracted-sections)))
        (is (= #{} (section-headings)))
        (is (nil? (:mm.memory/first-section e)) "the first-section link is gone")
        (is (str/includes? (rendered) "Just a body.")))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; refusals
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-section-another-record-references-refuses-the-unit
  (let [dir (fresh-tmp-dir "referenced")]
    (try
      (write! dir rel-path (doc {:sections [["One" "One body."] ["Two" "Two body."]]}))
      (import! dir)
      ;; another record cites section Two
      (let [two (d/entid (d/db (db/conn)) :memory.decisions/replace_probe__two)]
        (is (some? two))
        @(d/transact (db/conn) [{:db/id "citer" :dt/type :mm/Observation :db/ident :memory.observations/citer
                                 :mm.memory/rel-path "observations/citer.md" :mm.memory/name "citer"
                                 :mm.memory/memory-type :observation :mm.memory/cites two}]))
      (write! dir rel-path (doc {:sections [["One" "One body."]]}))
      (let [t0     (d/basis-t (d/db (db/conn)))
            report (import! dir)]
        (is (= 1 (:conflict-count report)) (pr-str report))
        (is (zero? (:persisted-count report)))
        (is (= "externally-referenced-section" (-> report :conflicts first :conflicts first :reason)))
        (is (true? (:reconciled? report)))
        (is (= #{"One" "Two"} (section-headings)) "nothing about the document changed")
        (is (some? (d/entid (d/db (db/conn)) :memory.decisions/replace_probe__two))))
      (finally (rm-rf! dir)))))

(deftest a-file-whose-id-differs-from-the-stored-one-refuses-the-unit
  (let [dir (fresh-tmp-dir "identity")]
    (try
      ;; A supplied identity survives import; only new, unidentified roots
      ;; receive an absent-only UUID default.
      (write! dir rel-path (doc {:slots "id: '11111111-1111-5111-8111-111111111111'"}))
      (import! dir)
      (let [stored-id (:mm/id (entity))]
        (is (= #uuid "11111111-1111-5111-8111-111111111111" stored-id))
        (write! dir rel-path (doc {:slots "id: '00000000-0000-4000-8000-000000000000'"}))
        (let [report (import! dir)]
          (is (= 1 (:conflict-count report)) (pr-str report))
          (is (= "identity-conflict" (-> report :conflicts first :conflicts first :reason)))
          (is (= stored-id (:mm/id (entity))) "the stored identity stands")))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; idempotence, additive mode, the preview and the basis pin
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity-snapshot []
  (let [e (entity)]
    (into {} (for [[k v] (into {} e)] [k (if (set? v) (set (map #(if (map? %) (:db/id %) %) v)) (if (map? v) (:db/id v) v))]))))

(deftest repeated-unchanged-imports-change-nothing
  (let [dir (fresh-tmp-dir "idempotent")]
    (try
      (write! dir rel-path (doc {}))
      (import! dir)
      (let [before (entity-snapshot)
            report (import! dir)]
        (is (= "replace" (-> report :persisted first :mode)))
        (is (zero? (-> report :persisted first :retracted-sections)))
        (is (zero? (-> report :persisted first :retracted-slots)))
        (is (= before (entity-snapshot)) "no datom of the entity changed"))
      (finally (rm-rf! dir)))))

(deftest additive-mode-keeps-the-pre-d7-behaviour
  (let [dir (fresh-tmp-dir "additive")]
    (try
      (write! dir rel-path (doc {:tags ["alpha" "beta"] :sections [["One" "One body."] ["Two" "Two body."]]}))
      (import! dir)
      (write! dir rel-path (doc {:tags ["alpha"] :sections [["One" "One body."]]}))
      (let [report (import! dir {"mode" "additive"})]
        (is (= "additive" (-> report :persisted first :mode)))
        (is (= #{"alpha" "beta"} (tag-values)) "additive keeps the old membership")
        (is (= #{"One" "Two"} (section-headings)) "additive keeps the old section"))
      (finally (rm-rf! dir)))))

(deftest additive-mode-cannot-reassign-document-identity-or-class
  (let [dir (fresh-tmp-dir "additive-identity")
        original (doc {:slots "id: '11111111-1111-5111-8111-111111111111'"})]
    (try
      (write! dir rel-path original)
      (import! dir)
      (let [before (entity-snapshot)]
        (doseq [[changed reason]
                [[(str/replace original "11111111-1111-5111-8111-111111111111"
                                        "00000000-0000-4000-8000-000000000000") "identity-conflict"]
                 [(str/replace original "type: decision" "type: observation") "class-changed"]]]
          (write! dir rel-path changed)
          (let [preview (dry-run dir {"mode" "additive"})
                report (import! dir {"mode" "additive"})]
            (is (= 1 (:conflict-count preview)) (pr-str preview))
            (is (= reason (get-in preview [:units 0 :conflicts 0 :reason])))
            (is (= 1 (:conflict-count report)) (pr-str report))
            (is (zero? (:persisted-count report)))
            (is (= reason (get-in report [:conflicts 0 :conflicts 0 :reason])))
            (is (= before (entity-snapshot)) "refusal preserves every stored root value"))))
      (finally (rm-rf! dir)))))

(deftest the-dry-run-plans-each-unit-and-a-stale-basis-is-refused
  (let [dir (fresh-tmp-dir "preview")]
    (try
      (write! dir rel-path (doc {:tags ["alpha" "beta"] :sections [["One" "One body."] ["Two" "Two body."]]}))
      (let [preview (dry-run dir)]
        (is (= "insert" (-> preview :units first :mode)))
        (is (some? (:basis preview)))
        (is (string? (-> preview :units first :source-sha256))))
      (import! dir)
      (write! dir rel-path (doc {:tags ["alpha"] :sections [["One" "One body."]]}))
      (let [preview (dry-run dir)]
        (is (= "replace" (-> preview :units first :mode)))
        (is (= 1 (-> preview :units first :retracted-sections)))
        (is (zero? (:conflict-count preview)))
        ;; the database moves between the preview and the persist
        @(d/transact (db/conn) [{:db/id "bystander" :dt/type :mm/Observation :mm.memory/rel-path "observations/bystander.md"
                                 :mm.memory/name "bystander" :mm.memory/memory-type :observation}])
        (let [response (tools/handle-call 1 {:name "sandbar.project.import"
                                             :arguments {"from" (.getPath dir) "persist" true "expect-basis" (:basis preview)}})]
          (is (true? (-> response :result :isError)) "a persist pinned to a stale basis is refused")
          (is (str/includes? (-> response :result :content first :text) "moved since the preview")))
        (is (= #{"One" "Two"} (section-headings)) "the refused persist changed nothing")
        (let [fresh (dry-run dir)
              report (import! dir {"expect-basis" (:basis fresh)})]
          (is (= 1 (:persisted-count report)))
          (is (= #{"One"} (section-headings)))))
      (finally (rm-rf! dir)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Astra's review of increment 2 (2026-09-20 12:51Z): D7-R3, D7-R4, D7-R5, and
;; the ownership of the display label
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-citation-added-between-planning-and-apply-refuses-the-unit
  ;; D7-R3: the plan was conflict-free; between the plan and its apply another
  ;; record cites the section the plan retracts.  The transaction carries the
  ;; plan's basis, the transactor aborts it, and the unit is reported as a
  ;; conflict; both the document and the late citer keep every fact.
  (let [dir (fresh-tmp-dir "late-citation")]
    (try
      (write! dir rel-path (doc {:sections [["One" "One body."] ["Two" "Two body."]]}))
      (import! dir)
      (write! dir rel-path (doc {:sections [["One" "One body."]]}))
      (let [two      (d/entid (d/db (db/conn)) :memory.decisions/replace_probe__two)
            original @#'import/apply-plan!]
        (with-redefs-fn
          {#'import/apply-plan!
           (fn [plan]
             @(d/transact (db/conn) [{:db/id "late" :dt/type :mm/Observation :db/ident :memory.observations/late_citer
                                      :mm.memory/rel-path "observations/late_citer.md" :mm.memory/name "late citer"
                                      :mm.memory/memory-type :observation :mm.memory/cites two}])
             (original plan))}
          (fn []
            (let [report (import! dir)]
              (is (= 1 (:conflict-count report)) (pr-str report))
              (is (= "basis-moved-during-apply" (-> report :conflicts first :conflicts first :reason)))
              (is (zero? (:persisted-count report)))
              (is (true? (:reconciled? report)))
              (is (= #{"One" "Two"} (section-headings)) "nothing about the document changed")
              (let [db*   (d/db (db/conn))
                    citer (d/entity db* :memory.observations/late_citer)]
                (is (some? (:dt/type citer)) "the late citer's facts are preserved")
                (is (= two (d/q '[:find ?c . :in $ ?e :where [?e :mm.memory/cites ?c]] db* (:db/id citer)))
                    "its citation stands"))))))
      (finally (rm-rf! dir)))))

(deftest an-unreadable-file-is-its-own-failure-and-the-rest-persists
  ;; D7-R4: one file the process cannot read is reported as a parse failure
  ;; with its source and error; the readable file persists; the totals
  ;; reconcile.  (Increment 2 had moved the read outside the unit boundary.)
  (let [dir (fresh-tmp-dir "unreadable")
        bad (io/file dir "decisions/unreadable_probe.md")]
    (try
      (write! dir rel-path (doc {}))
      (write! dir "decisions/unreadable_probe.md" (doc {}))
      (.setReadable bad false)
      (let [report (import! dir)]
        (is (= 2 (:attempted report)) (pr-str report))
        (is (= 1 (:parse-failed-count report)))
        (is (= "decisions/unreadable_probe.md" (-> report :parse-failed first :source)))
        (is (string? (-> report :parse-failed first :error)))
        (is (= 1 (:persisted-count report)))
        (is (true? (:reconciled? report))))
      (finally (.setReadable bad true) (rm-rf! dir)))))

(deftest a-file-edited-after-the-preview-refuses-the-persist-before-anything-applies
  ;; D7-R5: the preview reports each source's hash and one token over them
  ;; all; a persist pinned to either is refused, before any transaction, when
  ;; a file changed since — the map form names the file.
  (let [dir (fresh-tmp-dir "sources-pin")]
    (try
      (write! dir rel-path (doc {:sections [["One" "One body."] ["Two" "Two body."]]}))
      (import! dir)
      (let [preview (dry-run dir)]
        (is (string? (:sources-sha256 preview)))
        (is (= (-> preview :units first :source-sha256) (get (:sources preview) (keyword rel-path)))
            "the preview lists each source's hash")
        (write! dir rel-path (doc {:sections [["One" "One body."]]}))
        (let [response (tools/handle-call 1 {:name "sandbar.project.import"
                                             :arguments {"from" (.getPath dir) "persist" true
                                                         "expect-sources" (:sources-sha256 preview)}})]
          (is (true? (-> response :result :isError)) "the token refuses")
          (is (str/includes? (-> response :result :content first :text) "changed since the preview")))
        (let [hashes   (into {} (map (fn [[k v]] [(name k) v])) (:sources preview))
              response (tools/handle-call 1 {:name "sandbar.project.import"
                                             :arguments {"from" (.getPath dir) "persist" true
                                                         "expect-source-hashes" hashes}})]
          (is (true? (-> response :result :isError)) "the map refuses")
          (is (str/includes? (-> response :result :content first :text) "replace_probe.md") "and names the file"))
        (is (= #{"One" "Two"} (section-headings)) "nothing applied")
        (let [fresh  (dry-run dir)
              report (import! dir {"expect-sources" (:sources-sha256 fresh)})]
          (is (= 1 (:persisted-count report)) "a fresh preview's pin persists")
          (is (= #{"One"} (section-headings)))))
      (finally (rm-rf! dir)))))

(deftest the-display-label-is-derived-kept-on-omission-and-written-only-when-it-differs
  ;; the ownership census: mm/pref-label is the display tier a migration
  ;; copied from the name; a file that omits it is not retracting it, and the
  ;; emitter writes it only when it says something the name does not
  (let [dir (fresh-tmp-dir "pref-label")]
    (try
      (write! dir rel-path (doc {}))
      (import! dir)
      (let [eid (d/entid (d/db (db/conn)) ident)]
        @(d/transact (db/conn) [{:db/id eid :mm/pref-label "Replacement probe"}])
        (is (= "Replacement probe" (:mm/pref-label (entity))))
        (import! dir)
        (is (= "Replacement probe" (:mm/pref-label (entity))) "a re-import does not retract the derived label")
        (is (not (str/includes? (rendered) "pref-label:")) "a label equal to the name is not written")
        @(d/transact (db/conn) [{:db/id eid :mm/pref-label "A different display label"}])
        (is (str/includes? (rendered) "pref-label: A different display label") "a label that differs is written"))
      (finally (rm-rf! dir)))))

(deftest a-write-that-is-not-ours-between-two-units-refuses-the-remainder
  ;; Astra's acceptance case (2026-09-20 13:49Z): an attended run advances its
  ;; expected basis only through its own transactions.  A bystander write
  ;; landing after the first unit commits and before the second is planned
  ;; refuses the second (and any later) unit, and the report says which units
  ;; applied and which were refused.
  (let [dir (fresh-tmp-dir "own-basis")]
    (try
      (write! dir "decisions/own_basis_a.md" (str/replace (doc {}) "name: Replacement probe" "name: Own basis A"))
      (write! dir "decisions/own_basis_b.md" (str/replace (doc {}) "name: Replacement probe" "name: Own basis B"))
      (let [original @#'import/apply-plan!
            applied  (atom 0)]
        (with-redefs-fn
          {#'import/apply-plan!
           (fn [plan]
             (let [result (original plan)]
               (when (= 1 (swap! applied inc))
                 ;; a write that is not ours lands right after the first unit's commit
                 @(d/transact (db/conn) [{:db/id "bystander" :dt/type :mm/Observation :mm.memory/rel-path "observations/own_basis_bystander.md"
                                          :mm.memory/name "bystander" :mm.memory/memory-type :observation}]))
               result))}
          (fn []
            (let [preview (dry-run dir)
                  report  (import! dir {"expect-basis" (:basis preview)})]
              (is (= 1 (:persisted-count report)) (pr-str report))
              (is (= 1 (:conflict-count report)))
              (is (= "basis-moved-before-plan" (-> report :conflicts first :conflicts first :reason)))
              (is (true? (:reconciled? report)))
              (is (some? (:final-basis report)))
              (is (= 2 (:attempted report)))))))
      (finally (rm-rf! dir)))))

(deftest an-excluded-unit-is-fingerprinted-but-neither-planned-nor-transacted
  ;; the maintenance import's deferral: a row kept with both versions held is
  ;; walked (it belongs to the covered input set the source pin binds) but
  ;; neither planned nor transacted, and the totals reconcile
  (let [dir (fresh-tmp-dir "exclude")]
    (try
      (write! dir rel-path (doc {:sections [["One" "One body."] ["Two" "Two body."]]}))
      (write! dir "decisions/deferred_probe.md" (str/replace (doc {}) "name: Replacement probe" "name: Deferred probe"))
      (import! dir)
      (write! dir "decisions/deferred_probe.md" (str/replace (doc {:sections [["Only" "Only body."]]}) "name: Replacement probe" "name: Deferred probe"))
      (let [preview (dry-run dir {"exclude" ["decisions/deferred_probe.md"]})]
        (is (= 1 (:excluded-count preview)) (pr-str preview))
        (is (= ["decisions/deferred_probe.md"] (:excluded preview)))
        (is (= 1 (count (:units preview))) "the excluded unit is not planned")
        (is (contains? (:sources preview) (keyword "decisions/deferred_probe.md")) "but it is in the covered input set")
        (let [report (import! dir {"exclude" ["decisions/deferred_probe.md"] "expect-sources" (:sources-sha256 preview)})]
          (is (= 1 (:persisted-count report)))
          (is (= 1 (:excluded-count report)))
          (is (true? (:reconciled? report)))
          (is (= #{"One" "Two"} (set (d/q '[:find [?h ...] :in $ ?m :where [?s :mm.section/parent ?m] [?s :mm.section/heading ?h]] (d/db (db/conn)) :memory.decisions/deferred_probe)))
              "the deferred row's store version is untouched")))
      (finally (rm-rf! dir)))))
