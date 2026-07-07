(ns sandbar.firewall.label-test
  "S7 BU-2 / BU-7 — the label resolver's composition + census assertions.

   T-24  firewall-class → sensitivity table (every value → :private;
         :public-bottom → :public; unknown/absent → :private).
   T-28  Context-inherited :mm.memory/* refs are GOVERNED (census from live
         EFFECTIVE slots, no drift — CA-3) — census assertion AND the EP-1
         enforcement leg (a Context-source inherited-ref refusal).
   T-29  most-restrictive 4-way composition leak (CA-4): a :public
         default-visibility project with a restrictive firewall-class (or a
         non-public-bottom context) is labelled :private.
   T-9   L-9: a public-bottom Context SOURCE citing (inherited ref) a private
         decision is REFUSED at EP-1.
   T-10  L-9: a public Project SOURCE whose inherited :mm.memory/cites reaches
         a private target is REFUSED at EP-1."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.core :as fw]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(defn- fw-violation? [thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))))))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-label" :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-24 — the firewall-class sensitivity table (fail-closed)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest firewall-class-sensitivity-table
  (testing "every CURRENT firewall-class value → :private (fail-closed)"
    (is (= :private (label/sensitivity-of-firewall-class :none)))
    (is (= :private (label/sensitivity-of-firewall-class :proprietary-tokens-advisory)))
    (is (= :private (label/sensitivity-of-firewall-class :project-isolated))))
  (testing "unknown + absent → :private (nothing becomes public by reinterpretation)"
    (is (= :private (label/sensitivity-of-firewall-class :some-future-value)))
    (is (= :private (label/sensitivity-of-firewall-class nil))))
  (testing "ONLY the explicit :public-bottom designation → :public"
    (is (= :public (label/sensitivity-of-firewall-class :public-bottom)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-29 — the CA-4 most-restrictive composition leak falsifier
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest firewall-class-composition-leak
  (testing "a :public default-visibility project with a restrictive firewall-class
            is labelled :private (composition leak — FAILS under a memory+default
            -visibility-only label)"
    (sup/seed-context! :ctx/pub-comp :public-bottom)
    ;; default-visibility :public BUT firewall-class :project-isolated
    (sup/seed-project! :proj/leaky :public :ctx/pub-comp :project-isolated)
    (let [db  (db/db)
          lbl (label/label-of-eid db (sup/eid-of :proj/leaky))]
      (is (= :private (:sensitivity lbl))
          "project-isolated firewall-class must force :private despite :public default")))

  (testing "a :public project in a NON-public-bottom context is :private"
    (sup/seed-context! :ctx/private-comp :project-isolated)
    (sup/seed-project! :proj/ctx-leak :public :ctx/private-comp)
    (let [db  (db/db)
          lbl (label/label-of-eid db (sup/eid-of :proj/ctx-leak))]
      (is (= :private (:sensitivity lbl))
          "a private-firewall-class context must force the project :private")))

  (testing "only an ALL-public composition yields :public"
    (sup/seed-context! :ctx/all-pub :public-bottom)
    (sup/seed-project! :proj/all-pub :public :ctx/all-pub :public-bottom)
    (let [db  (db/db)
          lbl (label/label-of-eid db (sup/eid-of :proj/all-pub))]
      (is (= :public (:sensitivity lbl))
          "public default + public-bottom firewall-class + public-bottom context → :public"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-28(b) — census IS the effective governed-ref surface (no drift)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private effective-slot-rule
  "Self-contained :dt/subclass-of-transitive effective-slot rule (mirrors
   datatype's `effective-slot` defrule) so this assertion needs no datatype
   require — it derives the census surface straight from the live schema."
  '[[(eff-slot ?dt ?s)
     [?dt :dt/slots ?i]
     [?i :db/ident ?s]]
    [(eff-slot ?dt ?s)
     [?dt :dt/subclass-of ?p]
     (eff-slot ?p ?s)]])

(defn- effective-ref-slots
  "The ref-typed effective slots of `class-ident` after the full schema load,
   walked over the live db via the self-contained rule above + a
   `:db/valueType :db.type/ref` filter."
  [db class-ident]
  (->> (d/q '[:find ?slot
              :in $ % ?dt
              :where
              (eff-slot ?dt ?slot)
              [?slot :db/valueType :db.type/ref]]
            db effective-slot-rule class-ident)
       (map first)
       set))

(deftest context-inherits-memory-refs
  (testing "the effective :mm/Context slot set INCLUDES the inherited
            :mm.memory/* ref edges (Context ⊑ :mm/Meta ⊑ :mm/Memory) — CA-3"
    (let [db          (db/db)
          ctx-refs    (effective-ref-slots db :mm/Context)]
      (is (contains? ctx-refs :mm.memory/cites)
          ":mm/Context inherits the ref-typed :mm.memory/cites edge")
      (is (contains? ctx-refs :mm.memory/owning-project))))

  (testing "every governed-flow-slot that is a :mm.memory/* edge is in the
            effective :mm/Memory ref surface (census ⊆ effective, no phantom)"
    (let [db       (db/db)
          mem-refs (effective-ref-slots db :mm/Memory)
          mem-flow (filter #(= "mm.memory" (namespace %)) fw/governed-flow-slots)]
      (doseq [slot mem-flow]
        (is (contains? mem-refs slot)
            (str slot " must be a real effective ref slot on :mm/Memory"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-28(b) — the DRIFT-FORCING direction (effective ⊆ dispositioned)
;;
;; The 'census ⊆ effective' leg above catches a PHANTOM (a governed slot that
;; is not a real ref).  The drift-forcing direction is the OTHER way: every
;; effective `:mm.memory/*` ref slot on :mm/Memory (the surface Context also
;; inherits, CA-3) MUST be dispositioned across the four sets (governed-flow ∪
;; governed-carrier ∪ governed-tie ∪ exempt).  An undispositioned effective
;; ref = a MISSED-GOVERNED-EDGE a future schema amend could silently introduce.
;;
;; TODAY every effective `:mm.memory/*` ref slot is dispositioned — the census
;; follows the ratified §2.4 (`:mm.memory/themes` sits in exempt-slots
;; alongside its sibling `tags`: a shared-vocabulary :mm/Tag TERM, not a
;; dependence).  We PIN the undispositioned remainder to the EMPTY set: the
;; moment a schema amend introduces ANY undispositioned `:mm.memory/*` ref,
;; the remainder changes and THIS test FAILS — catching the drift.  (The
;; census sets live in `sandbar.firewall.core`, owned by BU-1.)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private dispositioned-slots
  "The union of the four census dispositions — every slot the firewall has a
  standing verdict for (governed one of three ways, or explicitly exempt)."
  (reduce into #{} [fw/governed-flow-slots
                    fw/governed-carrier-slots
                    fw/governed-tie-slots
                    fw/exempt-slots]))

(deftest memory-ref-surface-fully-dispositioned
  (testing "EVERY effective ref slot across :mm/Memory ∪ :mm/Context ∪
            :mm/Project (the ratified CA-3 governed-ref surface, NOT just the
            :mm.memory/* slice) is dispositioned across the four census sets —
            pinning the remainder to #{} FAILS the moment a schema amend
            introduces ANY undispositioned ref on any of the three firewall
            classes (a missed governed edge — incl. a future :mm.project/* or
            :mm.context/* own ref the old :mm.memory/*-only pin never examined)"
    (let [db              (db/db)
          all-refs        (reduce into #{}
                                  (map #(effective-ref-slots db %)
                                       [:mm/Memory :mm/Context :mm/Project]))
          undispositioned (set/difference all-refs dispositioned-slots)]
      (is (= #{} undispositioned)
          (str "undispositioned governed-ref-surface slots drifted from the "
               "pinned empty remainder: " (pr-str undispositioned))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-28(a) — a Context SOURCE citing (inherited ref) a private target is
;;           REFUSED at EP-1 (the enforcement leg, not only the census).
;;
;; T-9's STRING-carrier leg (`:mm.context/cites` rel-path → private target) is
;; deliberately NOT a refusal test today: a rel-path string is unresolvable as
;; a ref shape, so EP-1's best-effort resolution records it :skipped + WARN
;; (T-23 pins those semantics) and the S9 closure is the guarantee — per the
;; ratified defer, decisions/s7_context_remint_deferred_public_bottom_at_s9_
;; dan_2026_07_06 ("Context-edge governance is best-effort-at-EP-1 +
;; S9-closure-backstopped").
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest context-inherited-ref-refused-at-ep1
  (testing "a public-bottom :mm/Context whose INHERITED :mm.memory/cites (a real
            ref, not the string :mm.context/cites carrier) reaches a private
            decision is REFUSED at EP-1 — CA-3: the inherited ref surface is
            governed by the citation-flow predicate, not skipped as a carrier"
    (sup/seed-context! :ctx/pub-bottom :public-bottom)
    (sup/seed-context! :ctx/work :project-isolated)
    (sup/seed-project! :proj/priv :private :ctx/work)
    (sup/seed-memory!  :mem/priv-decision :private :proj/priv)
    (is (fw-violation?
          ;; make* — the firewall guard fires BEFORE transact regardless of
          ;; schema validation, so the Context spec need not be schema-complete;
          ;; the guard is what is under test.
          #(dt/make* :mm/Context
                     {:mm.memory/name "public-context-leaker"
                      :mm.context/firewall-class :public-bottom
                      :mm.memory/cites (sup/eid-of :mem/priv-decision)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-10 — a Project SOURCE whose inherited :mm.memory/cites reaches a private
;;         target is REFUSED at EP-1
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest project-inherited-ref-refused-at-ep1
  (testing "a :public :mm/Project (public-bottom firewall-class + public-bottom
            context, so effectively :public) whose inherited :mm.memory/cites
            reaches a private target is REFUSED at EP-1 (charter L-9)"
    (sup/seed-context! :ctx/pub-bottom :public-bottom)
    (sup/seed-context! :ctx/work :project-isolated)
    (sup/seed-project! :proj/priv :private :ctx/work)
    (sup/seed-memory!  :mem/priv-target :private :proj/priv)
    (is (fw-violation?
          #(dt/make* :mm/Project
                     {:mm.memory/name "public-project-leaker"
                      :mm.project/ident :proj/pub-leaker
                      :mm.project/default-visibility :public
                      :mm.project/firewall-class :public-bottom
                      :mm.project/runs-in-context (sup/eid-of :ctx/pub-bottom)
                      :mm.memory/cites (sup/eid-of :mem/priv-target)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; EP-1 AUTHORING GAP (round-2 must-fix #4) — an empty pre-commit :contexts
;; composes the :context/UNASSIGNED SINGLETON, never #{}.  An empty
;; source-context set makes the core's (set/subset? src tgt) clause VACUOUSLY
;; TRUE, so a brand-new private source would PERMIT a cross-compartment
;; governed edge (the leak).
;;
;; Falsifier status per branch (pre-fix = the label branches WITHOUT the
;; `unassigned-contexts` fallback):
;;   - Context leg: FALSIFIER — a pre-commit Context has no :db/id, so its
;;     compartment composed #{} pre-fix; the fallback routes it to the singleton.
;;   - Project leg: FALSIFIER — a context-less pre-commit Project composed #{}
;;     pre-fix; the fallback routes it to the singleton.
;;   - Memory leg (3rd `testing` below): NOT a memory-label-fallback falsifier —
;;     an absent owning-project defaults to the :project/UNASSIGNED sentinel,
;;     whose OWN :mm.project/runs-in-context is :context/UNASSIGNED, so
;;     project-context-eids is already non-empty and memory-label's
;;     `(if (seq contexts) …)` fallback never fires.  It asserts the load-
;;     bearing PROPERTY (never #{}) but the memory-label FALLBACK is falsified
;;     separately by `memory-label-fallback-routes-unassigned` (a real project
;;     with NO runs-in-context, the only shape that fires that branch).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest label-from-props-empty-contexts-routes-unassigned
  (testing "a pre-commit :mm/Context spec (no :db/id yet) composes the
            UNASSIGNED singleton, never #{} (fallback falsifier)"
    (let [db  (db/db)
          lbl (label/label-from-props db {:dt/type :mm/Context
                                          :mm.memory/name "new-ctx"})]
      (is (seq (:contexts lbl)) ":contexts must never compose empty")
      (is (= #{(sup/eid-of :context/UNASSIGNED)} (:contexts lbl)))))
  (testing "a context-less pre-commit :mm/Project spec likewise (fallback falsifier)"
    (let [db  (db/db)
          lbl (label/label-from-props db {:dt/type :mm/Project
                                          :mm.memory/name "new-proj"})]
      (is (= #{(sup/eid-of :context/UNASSIGNED)} (:contexts lbl)))))
  (testing "a project-less pre-commit :mm/Memory spec composes the singleton
            via the :project/UNASSIGNED sentinel's OWN runs-in-context (the
            PROPERTY holds; the memory-label fallback itself is falsified by
            memory-label-fallback-routes-unassigned)"
    (let [db  (db/db)
          lbl (label/label-from-props db {:dt/type :mm/Memory
                                          :mm.memory/name "new-mem"})]
      (is (= #{(sup/eid-of :context/UNASSIGNED)} (:contexts lbl))))))

(deftest memory-label-fallback-routes-unassigned
  (testing "a memory owned by a REAL project that itself has NO
            runs-in-context fires memory-label's empty-contexts fallback:
            project-context-eids is #{}, so the compartment routes to the
            :context/UNASSIGNED singleton — never #{} (the pre-fix leak)"
    ;; seed-project! always sets runs-in-context, so raw-transact a project
    ;; WITHOUT it — the only shape that exercises the memory-label fallback.
    (sup/raw-transact! [{:db/ident                      :proj/no-context
                         :dt/type                       :mm/Project
                         :mm.memory/name                "no-context"
                         :mm.project/ident              :proj/no-context
                         :mm.project/corpus-repo        "test-repo"
                         :mm.project/default-visibility :private}])
    (let [db  (db/db)
          lbl (label/label-from-props db {:dt/type :mm/Memory
                                          :mm.memory/name "owned-mem"
                                          :mm.memory/visibility :private
                                          :mm.memory/owning-project :proj/no-context})]
      (is (= #{(sup/eid-of :context/UNASSIGNED)} (:contexts lbl))
          "memory-label fallback must route an empty project-context set to the
           UNASSIGNED singleton, not #{}"))))

(deftest ep1-new-source-empty-contexts-refused
  (testing "a NEW private :mm/Context (no compartment coordinate yet) citing a
            private target in a REAL compartment is REFUSED at EP-1 — pre-fix
            the empty source-context set vacuously permitted this edge"
    (sup/seed-context! :ctx/work :project-isolated)
    (sup/seed-project! :proj/priv :private :ctx/work)
    (sup/seed-memory!  :mem/priv-target :private :proj/priv)
    (is (fw-violation?
          #(dt/make* :mm/Context
                     {:mm.memory/name "new-unanchored-context"
                      :mm.memory/cites (sup/eid-of :mem/priv-target)}))))
  (testing "a NEW context-less private :mm/Project citing a private target in
            a REAL compartment is likewise REFUSED"
    (sup/seed-context! :ctx/work2 :project-isolated)
    (sup/seed-project! :proj/priv2 :private :ctx/work2)
    (sup/seed-memory!  :mem/priv-target2 :private :proj/priv2)
    (is (fw-violation?
          #(dt/make* :mm/Project
                     {:mm.memory/name "new-unanchored-project"
                      :mm.project/ident :proj/unanchored
                      :mm.memory/cites (sup/eid-of :mem/priv-target2)})))))
