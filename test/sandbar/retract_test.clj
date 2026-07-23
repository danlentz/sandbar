(ns sandbar.retract-test
  "Fixture-based tests for the first-class entity-retraction safety layer
   (`sandbar.retract`) + its MCP handler (`sandbar.mcp.tools`).

   Per SPEC scratchpad/retract-verb-2026-07-02/SPEC.md, Tests T1-T7:
     T1 dry-run default — report complete + correct, NOTHING retracted
     T2 persist         — targets gone; dependents ORPHANED (cascade false)
                          / GONE (cascade true)
     T3 protected       — :dt/* ident + :mm/Actor skipped w/ reasons; rest proceed
     T4 cap             — 101 targets → loud error, nothing retracted
     T5 reason-required — persist without reason → loud error
     T6 audit event     — exactly N :mm.event/EntityRetracted events queryable
     T7 idempotence-adj — already-missing target → per-target exists? false skip

   All runs execute under `sandbar.test-util/make-test-db-fixture`
   (in-memory Datomic; no live-DB / shared-transactor / nREPL contact)."
  (:require [clojure.test :refer :all]
            [clojure.edn :as edn]
            [datomic.api :as d]
            [sandbar.retract :as retract]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.entity-ref :as eref]
            [sandbar.util.event :as event]
            [sandbar.test-util :as tu])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "retract-test"}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Victim builders
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- make-memory-with-dependents!
  "Create a :mm/Memory `ident` carrying a real blast radius in ONE atomic
   tx (cross-refs resolve via tempids):
     - a :mm/Frontmatter carrier via :mm.memory/frontmatter
     - a top-level :mm/Section (parent = the memory) via :mm.memory/first-section
     - a nested child :mm/Section (parent = the top section)
     - a second top-level :mm/Section chained via :mm.section/next-sibling
   Returns {:memory-eid :fm-eid :sec1-eid :sec1a-eid :sec2-eid}."
  [ident nm]
  (let [result @(d/transact (db/conn)
                 [{:db/id "mem" :db/ident ident :dt/type :mm/Memory
                   :mm.memory/rel-path (str "test/" nm ".md")
                   :mm.memory/name nm
                   :mm.memory/body-raw "victim body"
                   :mm.memory/frontmatter "fm"
                   :mm.memory/first-section "sec1"}
                  {:db/id "fm" :dt/type :mm/Frontmatter
                   :mm.frontmatter/extra "{:novel-key \"v\"}"}
                  {:db/id "sec1" :dt/type :mm/Section
                   :mm.section/heading "Top One" :mm.section/heading-level 1
                   :mm.section/body "top one body"
                   :mm.section/parent "mem"
                   :mm.section/next-sibling "sec2"}
                  {:db/id "sec1a" :dt/type :mm/Section
                   :mm.section/heading "Child" :mm.section/heading-level 2
                   :mm.section/body "child body"
                   :mm.section/parent "sec1"}
                  {:db/id "sec2" :dt/type :mm/Section
                   :mm.section/heading "Top Two" :mm.section/heading-level 1
                   :mm.section/body "top two body"
                   :mm.section/parent "mem"}])
        tid (:tempids result)]
    {:memory-eid (get tid "mem")
     :fm-eid     (get tid "fm")
     :sec1-eid   (get tid "sec1")
     :sec1a-eid  (get tid "sec1a")
     :sec2-eid   (get tid "sec2")}))

(defn- make-memory-with-identful-sections!
  "Create a :mm/Memory `ident` whose :mm/Section subtree carries PATH-DERIVED
   `:db/ident`s — the shape every codec-ingested corpus memory has (sections
   get `<memory-ident>__<slug>` idents per the section-schema ADR).  This is
   the shape that triggers the bare-keyword projection: Datomic returns a
   ref-to-an-identful-entity as its `:db/ident` KEYWORD, not an EntityMap, so
   `(:db/id (:mm.memory/first-section e))` is nil and the old dependents walk
   sees nothing.  Contrast `make-memory-with-dependents!` whose sections have
   NO `:db/ident` (Datomic returns those as EntityMaps → `:db/id` works),
   which is why that fixture masked the blind spot.
     - a top-level :mm/Section (parent = the memory) via :mm.memory/first-section
     - a nested child :mm/Section (parent = the top section)
     - a second top-level :mm/Section chained via :mm.section/next-sibling
   Returns {:memory-eid :sec1-eid :sec1a-eid :sec2-eid}."
  [ident nm]
  (let [sec1-ident  (keyword "memory.test" (str nm "__sec1"))
        sec1a-ident (keyword "memory.test" (str nm "__sec1a"))
        sec2-ident  (keyword "memory.test" (str nm "__sec2"))
        result @(d/transact (db/conn)
                 [{:db/id "mem" :db/ident ident :dt/type :mm/Memory
                   :mm.memory/rel-path (str "test/" nm ".md")
                   :mm.memory/name nm
                   :mm.memory/body-raw "victim body"
                   :mm.memory/first-section "sec1"}
                  {:db/id "sec1" :db/ident sec1-ident :dt/type :mm/Section
                   :mm.section/heading "Top One" :mm.section/heading-level 1
                   :mm.section/body "top one body"
                   :mm.section/parent "mem"
                   :mm.section/next-sibling "sec2"}
                  {:db/id "sec1a" :db/ident sec1a-ident :dt/type :mm/Section
                   :mm.section/heading "Child" :mm.section/heading-level 2
                   :mm.section/body "child body"
                   :mm.section/parent "sec1"}
                  {:db/id "sec2" :db/ident sec2-ident :dt/type :mm/Section
                   :mm.section/heading "Top Two" :mm.section/heading-level 1
                   :mm.section/body "top two body"
                   :mm.section/parent "mem"}])
        tid (:tempids result)]
    {:memory-eid (get tid "mem")
     :sec1-eid   (get tid "sec1")
     :sec1a-eid  (get tid "sec1a")
     :sec2-eid   (get tid "sec2")}))

(defn- make-cited-target-with-sections!
  "Create a :mm/Memory `ident` that is BOTH the head of an owned :mm/Section
   subtree AND the TARGET of inbound citation edges from surviving memories —
   the two-axis blast radius Bug 5's regression demands: outbound sections
   (blind spot #1) plus inbound cites/motivated-by (blind spot #2).  Built in
   ONE atomic tx (the citers reference the target via its tempid):
     - a top-level :mm/Section (parent = the target) + a nested child section
     - N `citer` memories each pointing at the target via :mm.memory/cites
     - M `motivator` memories each pointing via :mm.memory/motivated-by
   Returns {:target-eid :sec1-eid :sec1a-eid :citer-eids :motivator-eids}."
  [ident nm n-cites n-motivated]
  (let [sec1-ident  (keyword "memory.test" (str nm "__sec1"))
        sec1a-ident (keyword "memory.test" (str nm "__sec1a"))
        citer-specs (for [i (range n-cites)]
                      {:db/id (str "citer" i)
                       :db/ident (keyword "memory.test" (str nm "-citer" i))
                       :dt/type :mm/Memory
                       :mm.memory/rel-path (str "test/" nm "-citer" i ".md")
                       :mm.memory/name (str nm "-citer" i)
                       :mm.memory/body-raw "citer body"
                       :mm.memory/cites "mem"})
        motiv-specs (for [i (range n-motivated)]
                      {:db/id (str "motiv" i)
                       :db/ident (keyword "memory.test" (str nm "-motiv" i))
                       :dt/type :mm/Memory
                       :mm.memory/rel-path (str "test/" nm "-motiv" i ".md")
                       :mm.memory/name (str nm "-motiv" i)
                       :mm.memory/body-raw "motivator body"
                       :mm.memory/motivated-by "mem"})
        result @(d/transact (db/conn)
                 (into [{:db/id "mem" :db/ident ident :dt/type :mm/Memory
                         :mm.memory/rel-path (str "test/" nm ".md")
                         :mm.memory/name nm
                         :mm.memory/body-raw "cited-target body"
                         :mm.memory/first-section "sec1"}
                        {:db/id "sec1" :db/ident sec1-ident :dt/type :mm/Section
                         :mm.section/heading "Top One" :mm.section/heading-level 1
                         :mm.section/body "top one body"
                         :mm.section/parent "mem"}
                        {:db/id "sec1a" :db/ident sec1a-ident :dt/type :mm/Section
                         :mm.section/heading "Child" :mm.section/heading-level 2
                         :mm.section/body "child body"
                         :mm.section/parent "sec1"}]
                       (concat citer-specs motiv-specs)))
        tid (:tempids result)]
    {:target-eid    (get tid "mem")
     :sec1-eid      (get tid "sec1")
     :sec1a-eid     (get tid "sec1a")
     :citer-eids    (mapv #(get tid (str "citer" %)) (range n-cites))
     :motivator-eids (mapv #(get tid (str "motiv" %)) (range n-motivated))}))

(defn- make-plain-memory! [ident nm]
  (:db/id (dt/make :mm/Memory {:db/ident ident
                               :mm.memory/rel-path (str "test/" nm ".md")
                               :mm.memory/name nm
                               :mm.memory/body-raw "plain body"})))

(defn- make-actor! [ident nm]
  ;; :mm/Actor is abstract; :mm/AIActor is a concrete subtype (inherits the
  ;; :mm.memory/* slot surface up the chain).  type-isa? :mm/Actor still
  ;; holds, so the protected-class guard covers it.
  (:db/id (dt/make :mm/AIActor {:db/ident ident
                                :mm.memory/rel-path (str "test/" nm ".md")
                                :mm.memory/name nm
                                :mm.actor/actor-type :ai-actor})))

(defn- count-retracted-events [db]
  (count (d/q '[:find ?e
                :where [?e :event/kind :mm.event/EntityRetracted]]
              db)))

(defn- exists? [ref]
  (:valid? (eref/validate ref)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T1 — dry-run default: report complete + correct, NOTHING retracted
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t1-dry-run-default-test
  (testing "T1: without :persist the verb returns the full report + transacts NOTHING"
    (let [{:keys [memory-eid fm-eid sec1-eid sec1a-eid sec2-eid]}
          (make-memory-with-dependents! :memory.test/t1-victim "t1-victim")
          report (retract/retract! [:memory.test/t1-victim] {})]
      (is (false? (:persist report)) "dry-run: :persist false")
      (let [t (first (:targets report))]
        (is (true? (:exists? t)) "victim exists")
        (is (= :memory.test/t1-victim (:ident t)) "ident echoed")
        (is (= :mm/Memory (:dt-type t)) "dt-type resolved")
        (is (pos? (:datom-count t)) "datom-count populated")
        (is (false? (:protected? t)) "not protected")
        ;; dependents ALWAYS enumerated: 3 sections + 1 frontmatter carrier
        (let [dep-eids (set (map :eid (:dependents t)))]
          (is (= 4 (count (:dependents t)))
              "4 dependents: 3 sections + 1 frontmatter")
          (is (contains? dep-eids sec1-eid)  "top section 1 enumerated")
          (is (contains? dep-eids sec1a-eid) "nested child section enumerated")
          (is (contains? dep-eids sec2-eid)  "top section 2 enumerated (sibling chain)")
          (is (contains? dep-eids fm-eid)    "frontmatter carrier enumerated")))
      ;; NOTHING retracted
      (is (exists? memory-eid) "memory survives dry-run")
      (is (exists? fm-eid)     "frontmatter survives dry-run")
      (is (exists? sec1a-eid)  "nested section survives dry-run"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T2 — persist: targets gone; dependents orphaned (cascade false) / gone (true)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t2-persist-cascade-false-orphans-dependents-test
  (testing "T2a: persist cascade=false — target GONE, dependents ORPHANED (survive)"
    (let [{:keys [memory-eid fm-eid sec1-eid]}
          (make-memory-with-dependents! :memory.test/t2a-victim "t2a-victim")
          report (retract/retract! [:memory.test/t2a-victim]
                                   {:persist true :reason "T2a cleanup"})]
      (is (true? (:persist report)))
      (is (= 1 (:retracted-count report)) "only the named target retracted")
      (is (false? (exists? memory-eid)) "memory GONE")
      (is (exists? fm-eid)   "frontmatter ORPHANED (survives — not :db/isComponent)")
      (is (exists? sec1-eid) "section ORPHANED (survives)"))))

(deftest t2-persist-cascade-true-removes-dependents-test
  (testing "T2b: persist cascade=true — target AND all dependents GONE"
    (let [{:keys [memory-eid fm-eid sec1-eid sec1a-eid sec2-eid]}
          (make-memory-with-dependents! :memory.test/t2b-victim "t2b-victim")
          report (retract/retract! [:memory.test/t2b-victim]
                                   {:persist true :cascade true
                                    :reason "T2b cascade cleanup"})]
      (is (true? (:persist report)))
      (is (= 5 (:retracted-count report))
          "target + 4 dependents retracted in one tx")
      (is (false? (exists? memory-eid)) "memory GONE")
      (is (false? (exists? fm-eid))     "frontmatter GONE (cascade)")
      (is (false? (exists? sec1-eid))   "top section 1 GONE (cascade)")
      (is (false? (exists? sec1a-eid))  "nested section GONE (cascade)")
      (is (false? (exists? sec2-eid))   "top section 2 GONE (cascade)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T3 — protected: :dt/* ident + :mm/Actor skipped w/ reasons; rest proceed
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t3-protected-skipped-rest-proceed-test
  (testing "T3: protected :dt/* + :mm/Actor targets skipped with reasons; plain proceeds"
    (let [actor-eid (make-actor! :memory.test/t3-actor "t3-actor")
          plain-eid (make-plain-memory! :memory.test/t3-plain "t3-plain")
          report    (retract/retract! [:dt/Class :memory.test/t3-actor :memory.test/t3-plain]
                                       {:persist true :reason "T3 mixed batch"})
          by-ident  (into {} (map (juxt :ident identity) (:targets report)))]
      ;; :dt/Class — protected namespace
      (let [dt-t (get by-ident :dt/Class)]
        (is (true? (:protected? dt-t)) ":dt/Class protected")
        (is (re-find #"protected namespace" (:protection-reason dt-t))
            "reason names the protected namespace"))
      ;; :mm/Actor instance — protected class
      (let [actor-t (get by-ident :memory.test/t3-actor)]
        (is (true? (:protected? actor-t)) ":mm/Actor instance protected")
        (is (re-find #"protected class" (:protection-reason actor-t))
            "reason names the protected class"))
      ;; plain memory — proceeds
      (let [plain-t (get by-ident :memory.test/t3-plain)]
        (is (false? (:protected? plain-t)) "plain memory not protected"))
      (is (= 1 (:retracted-count report)) "only the plain memory retracted")
      (is (exists? :dt/Class)              ":dt/Class NEVER retracted")
      (is (exists? actor-eid)              ":mm/Actor NEVER retracted")
      (is (false? (exists? plain-eid))     "plain memory retracted"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T4 — cap: 101 targets → loud error, nothing retracted
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t4-cap-exceeded-test
  (testing "T4: 101 targets → loud error naming the cap; nothing retracted"
    (let [eid (make-plain-memory! :memory.test/t4-survivor "t4-survivor")
          targets (conj (vec (range 1 101)) :memory.test/t4-survivor)] ; 101 targets
      (is (= 101 (count targets)))
      (let [ex (try (retract/retract! targets {:persist true :reason "should-not-run"})
                    nil
                    (catch ExceptionInfo e e))]
        (is (some? ex) "over-cap raises")
        (is (contains? (:reasons (ex-data ex)) :retract/target-cap-exceeded)
            "reason keyword names the cap violation")
        (is (re-find #"cap" (.getMessage ex)) "message names the cap"))
      (is (exists? eid) "survivor NOT retracted — cap check precedes any tx"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T5 — reason-required: persist without reason → loud error
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t5-reason-required-test
  (testing "T5: :persist without :reason → loud error; nothing retracted"
    (let [eid (make-plain-memory! :memory.test/t5-victim "t5-victim")
          ex  (try (retract/retract! [:memory.test/t5-victim] {:persist true})
                   nil
                   (catch ExceptionInfo e e))]
      (is (some? ex) "persist-without-reason raises")
      (is (contains? (:reasons (ex-data ex)) :retract/reason-required)
          "reason keyword names the missing-reason violation")
      (is (exists? eid) "victim NOT retracted"))
    (testing "blank reason also rejected"
      (let [eid (make-plain-memory! :memory.test/t5-blank "t5-blank")
            ex  (try (retract/retract! [:memory.test/t5-blank]
                                       {:persist true :reason "   "})
                     nil
                     (catch ExceptionInfo e e))]
        (is (some? ex) "blank reason raises")
        (is (exists? eid) "victim NOT retracted")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T6 — audit event: exactly N :mm.event/EntityRetracted events w/ payloads
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t6-audit-event-test
  (testing "T6: one :mm.event/EntityRetracted event per proceeding target, correct payload"
    (make-plain-memory! :memory.test/t6-a "t6-a")
    (make-plain-memory! :memory.test/t6-b "t6-b")
    (is (zero? (count-retracted-events (db/db))) "no events before retraction")
    (let [report (retract/retract! [:memory.test/t6-a :memory.test/t6-b]
                                   {:persist true :reason "T6 audit check"})]
      (is (= 2 (:events-emitted report)) "handler reports 2 events emitted")
      (let [db (db/db)]
        (is (= 2 (count-retracted-events db))
            "exactly 2 :mm.event/EntityRetracted events queryable in the fixture")
        ;; payload check — the structured EDN rides on :event/description
        (let [ev-eids (map first (d/q '[:find ?e
                                        :where [?e :event/kind :mm.event/EntityRetracted]]
                                      db))
              payloads (map (fn [e]
                              (edn/read-string (:event/description (d/entity db e))))
                            ev-eids)
              idents   (set (map :target-ident payloads))]
          (is (= #{:memory.test/t6-a :memory.test/t6-b} idents)
              "both target idents captured in event payloads")
          (is (every? #(= "T6 audit check" (:reason %)) payloads)
              "reason carried into every event payload")
          (is (every? #(= :mm/Memory (:dt-type %)) payloads)
              "dt-type carried into every event payload")
          (is (every? #(pos? (:datom-count %)) payloads)
              "datom-count carried into every event payload"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T7 — idempotence-adjacent: already-missing target → exists? false skip
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t7-idempotence-adjacent-missing-target-test
  (testing "T7: an already-missing target → per-target exists? false skip, NOT an abort"
    (let [live-eid (make-plain-memory! :memory.test/t7-live "t7-live")
          report   (retract/retract! [:memory.test/t7-never-existed :memory.test/t7-live]
                                      {:persist true :reason "T7 idempotence"})
          by-ident (into {} (map (juxt :target identity) (:targets report)))]
      ;; missing target → exists? false, not an exception
      (let [missing-t (get by-ident :memory.test/t7-never-existed)]
        (is (false? (:exists? missing-t)) "missing target reported exists? false")
        (is (nil? (:resolved-eid missing-t)) "missing target has no eid"))
      ;; live target proceeds
      (is (= 1 (:retracted-count report)) "only the live target retracted")
      (is (false? (exists? live-eid)) "live target retracted")
      ;; the missing target is in the skipped set, batch did NOT abort
      (is (= 1 (count (filter #(false? (:exists? %)) (:skipped report))))
          "missing target lands in the skipped set"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T8 — actor resolution (live-probe regression, hot-load ceremony #2
;; 2026-07-02: a raw keyword :event/actor failed dt/make validation AFTER
;; a successful retraction; actors now resolve EARLY + LOUDLY)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t8-actor-resolution-test
  (testing "persist with a keyword actor ident → audit description carries the RESOLVED actor"
    ;; NB: the actor rides :event/description, not :event/actor — the
    ;; dt/make ref-slot layer cannot attach :dt/Ref values on this path
    ;; in any form (rejects eids/EntityMaps; silently drops maps).  See
    ;; bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md;
    ;; when that lands, this test should be extended to assert the typed
    ;; :event/actor ref as well.
    (let [victim    (make-plain-memory! :memory.test/t8-victim "t8-victim")
          actor-eid (make-actor! :memory.test-actors/t8-actor "t8-actor")
          report    (retract/retract! [victim]
                                      {:persist true
                                       :reason  "T8 actor resolution"
                                       :actor   :memory.test-actors/t8-actor})]
      (is (= 1 (:events-emitted report)) "one audit event emitted")
      (is (zero? (:audit-failures report)) "no audit failures")
      (let [descs (d/q '[:find ?d
                         :where [?e :event/kind :mm.event/EntityRetracted]
                                [?e :event/description ?d]]
                       (db/db))
            payload (edn/read-string (ffirst descs))]
        (is (= :memory.test-actors/t8-actor (:actor-ident payload))
            "audit payload carries the resolved actor ident")
        (is (= actor-eid (:actor-eid payload))
            "audit payload carries the resolved actor eid, not a raw keyword"))))
  (testing "persist with an unresolvable actor → loud EARLY error, NOTHING retracted"
    (let [victim (make-plain-memory! :memory.test/t8-victim-2 "t8-victim-2")]
      (is (thrown? ExceptionInfo
                   (retract/retract! [victim]
                                     {:persist true :reason "x"
                                      :actor   :no.such/actor-9999}))
          "bad actor fails before any tx")
      (is (true? (exists? victim)) "victim survives the bad-actor call"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T9 — audit emission is BEST-EFFORT for real: an audit failure never
;; masks a committed retraction (the docstring promised it; the live probe
;; proved the body didn't deliver — this pins the contract)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t9-audit-failure-isolation-test
  (testing "log-event! throwing → retraction still succeeds; report flags the failure"
    (let [victim (make-plain-memory! :memory.test/t9-victim "t9-victim")
          report (with-redefs [event/log-event!
                               (fn [& _] (throw (ex-info "injected audit failure"
                                                         {:injected true})))]
                   (retract/retract! [victim]
                                     {:persist true :reason "T9 audit isolation"}))]
      (is (= 1 (:retracted-count report)) "retraction committed")
      (is (false? (exists? victim)) "victim gone despite audit failure")
      (is (zero? (:events-emitted report)) "no events claimed")
      (is (= 1 (:audit-failures report)) "audit failure surfaced honestly in the report"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T10 — retract-cascade blind spot #1 (outbound section chains): the
;; dependents walk must see the :mm/Section subtree even when the sections
;; carry :db/ident (the codec-ingested corpus shape), where Datomic projects
;; :mm.memory/first-section as a bare KEYWORD (not an EntityMap) so the old
;; (:db/id (:mm.memory/first-section e)) resolved to nil and enumerated NONE.
;; Reproduces bugs/retract_cascade_blind_to_first_section_dependents_bare_keyword_projection_2026_07_03.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t10-cascade-sees-identful-section-subtree-test
  (testing "T10a dry-run: sections with :db/ident (bare-keyword first-section projection) ARE enumerated as dependents"
    (let [{:keys [sec1-eid sec1a-eid sec2-eid]}
          (make-memory-with-identful-sections! :memory.test/t10a-victim "t10a-victim")
          report (retract/retract! [:memory.test/t10a-victim] {})
          t      (first (:targets report))
          dep-eids (set (map :eid (:dependents t)))]
      (is (= 3 (count (:dependents t)))
          "all 3 identful sections enumerated (was 0 under the bare-keyword blind spot)")
      (is (contains? dep-eids sec1-eid)  "top section 1 (identful) enumerated")
      (is (contains? dep-eids sec1a-eid) "nested child section (identful) enumerated")
      (is (contains? dep-eids sec2-eid)  "top section 2 via sibling chain (identful) enumerated")))
  (testing "T10b cascade: the identful section subtree is actually retracted (not silently orphaned)"
    (let [{:keys [memory-eid sec1-eid sec1a-eid sec2-eid]}
          (make-memory-with-identful-sections! :memory.test/t10b-victim "t10b-victim")
          report (retract/retract! [:memory.test/t10b-victim]
                                   {:persist true :cascade true
                                    :reason "T10b identful-section cascade"})]
      (is (= 4 (:retracted-count report))
          "target + 3 identful sections retracted in one tx")
      (is (false? (exists? memory-eid)) "memory GONE")
      (is (false? (exists? sec1-eid))   "top section 1 GONE (cascade reached the identful subtree)")
      (is (false? (exists? sec1a-eid))  "nested section GONE (cascade)")
      (is (false? (exists? sec2-eid))   "top section 2 GONE (cascade)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T11 — retract-cascade blind spot #2 (inbound citation edges): the report
;; enumerated only OUTBOUND component refs — it never queried who points AT
;; the target.  A dry-run on a heavily-cited entity reported an empty inbound
;; picture while surviving memories held live :mm.memory/cites / motivated-by
;; edges into it; retracting on that report silently dangled every citation.
;; The report must now surface an :inbound-refs section (count + sample) so
;; the caller sees the DANGLE radius, and :persist with un-acknowledged
;; inbound must refuse.
;; Reproduces bugs/retract_dependents_blind_to_inbound_citation_edges_dangling_refs_2026_07_03.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest t11-inbound-citation-edges-in-report-test
  (testing "T11a dry-run: inbound cites + motivated-by edges surface in :inbound-refs (count + sample) ALONGSIDE the owned section dependents"
    (let [{:keys [sec1-eid sec1a-eid citer-eids motivator-eids]}
          (make-cited-target-with-sections! :memory.test/t11a-victim "t11a-victim" 3 2)
          report (retract/retract! [:memory.test/t11a-victim] {})
          t      (first (:targets report))]
      ;; BOTH axes present: outbound sections (blind spot #1) ...
      (let [dep-eids (set (map :eid (:dependents t)))]
        (is (= 2 (count (:dependents t)))
            "2 owned sections enumerated as dependents (outbound axis)")
        (is (contains? dep-eids sec1-eid)  "top section enumerated")
        (is (contains? dep-eids sec1a-eid) "nested child section enumerated"))
      ;; ... AND inbound citation edges (blind spot #2 — the lie this fixes)
      (is (= 5 (:inbound-count t))
          "inbound-count = 3 cites + 2 motivated-by = 5 (was silently 0/absent)")
      (let [src-eids  (set (map :source-eid (:inbound-refs t)))
            preds     (set (map :predicate (:inbound-refs t)))]
        (is (= 5 (count (:inbound-refs t)))
            "every inbound edge sampled (3 cites + 2 motivated-by)")
        (is (every? src-eids citer-eids)     "all citers appear as inbound sources")
        (is (every? src-eids motivator-eids) "all motivators appear as inbound sources")
        (is (contains? preds :mm.memory/cites)        ":cites predicate surfaced")
        (is (contains? preds :mm.memory/motivated-by) ":motivated-by predicate surfaced"))))
  (testing "T11b: :persist with live inbound refs and NO :acknowledge-dangling → loud refusal, NOTHING retracted"
    (let [{:keys [target-eid citer-eids]}
          (make-cited-target-with-sections! :memory.test/t11b-victim "t11b-victim" 2 0)
          ex (try (retract/retract! [:memory.test/t11b-victim]
                                    {:persist true :reason "T11b should refuse"})
                  nil
                  (catch ExceptionInfo e e))]
      (is (some? ex) "persist over live inbound refs raises")
      (is (contains? (:reasons (ex-data ex)) :retract/inbound-refs-unacknowledged)
          "reason keyword names the un-acknowledged dangle")
      (is (exists? target-eid) "target NOT retracted — the dangle guard precedes any tx")
      (is (every? exists? citer-eids) "citers untouched")))
  (testing "T11c: :persist with :acknowledge-dangling true → proceeds (caller accepted the dangle)"
    (let [{:keys [target-eid citer-eids]}
          (make-cited-target-with-sections! :memory.test/t11c-victim "t11c-victim" 2 0)
          report (retract/retract! [:memory.test/t11c-victim]
                                   {:persist true :reason "T11c ack dangle"
                                    :acknowledge-dangling true})]
      (is (= 1 (:retracted-count report)) "target retracted once acknowledged")
      (is (false? (exists? target-eid)) "target GONE")
      (is (every? exists? citer-eids)
          "citers survive as DANGLING sources (the acknowledged cost — refs are not :db/isComponent)"))))
