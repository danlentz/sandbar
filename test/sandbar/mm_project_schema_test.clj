(ns sandbar.mm-project-schema-test
  "S6 KEYSTONE SCHEMA MINT — acceptance battery (falsification-first).

   This ns verifies the S6 schema transaction that lands the :mm/Project
   class + the firewall slots so the S7 visibility-label function is TOTAL
   at first write.  It is a [CEREMONY] schema-tx verification: it authors
   NOTHING (no src, no schema edit) — it loads the mint into a fresh
   in-memory Datomic (the test harness) and asserts the mint's shape.

   FALSIFICATION-FIRST CONTRACT (the load-bearing property of this file):
   EVERY assertion here FAILS before the S6 schema mint lands and PASSES
   after.  Pre-mint the substrate has NO :mm/Project class, NO
   :mm.memory/visibility / owning-project slots, NO :mm.context/* S6 slots,
   NO :mm.activity/* discriminators, NO :project/UNASSIGNED sentinel — so
   every `range-of` / `cardinality-of` / `slots-of` / `make` / edge probe
   below resolves to nil/absent/throw and the tests are RED.  The green
   flip IS the acceptance signal.

   Battery → XMINUS-BUILD-PLAN §S6 + S6-PLAN.md §7:
     (a) schema-load smoke      — :mm/Project introspects (pre-S6 absent)
     (b) default-deny           — absent visibility resolves :private (AP-6)
     (c) label-totality (L-9)   — the label inputs are DEFINED on a
                                   :mm/Context source (compartment primitive)
     (d) tie-fidelity           — runs-in-context card-many + Project<->
                                   Codebase<->Context resolve (ADR-F2/-(d))
     (e) MF-1 edge-traversable  — a new REF slot is a real graph edge
                                   (a :dt/domain-only slot FAILS this)
     (f) Activity-discriminator — a declassification Activity carries
                                   visibility-from/to; a plain one does not
   plus the ratified-manifest guard invariants (sentinel-seeded,
   machine-id declared-but-unpopulated, RepoHandle no-mint, AP-1 reduction).

   Verification surfaces are the substrate PRIMITIVES the MCP verbs wrap:
     - sandbar.db.datatype    — class.describe / class.slots / property.*
                                introspection (dt/ancestors-of, dt/slots-of,
                                dt/range-of, dt/cardinality-of, dt/required?)
     - sandbar.navigate.edges — navigate.outbound-edges (the exact fn the
                                MCP verb dispatches; MF-1 acceptance names it)
     - sandbar.mcp.clearance  — the ALREADY-EXISTING default-deny read helper
                                (entity-compartment); S6 mints the slots it
                                reads — this ns asserts the semantics land,
                                it does NOT invent a parallel read path.

   Fixture: the datomic:mem:// fresh-DB pattern from sandbar.test-util
   (schema loaded from :required-schema, which already enumerates :mm /
   :mm-artifact / :context — the files the mint edits; no config change).

   Spec: audit-results/xminus-build-2026-07-04/S6/S6-PLAN.md,
   DESIGN-ONTOLOGY §2.2/§2.3, DESIGN-W1-FIREWALL, S3-RATIFICATION §A."
  (:require [clojure.test          :refer :all]
            [datomic.api           :as datomic.api]
            [sandbar.db.datatype   :as dt]
            [sandbar.db.datomic    :as db]
            [sandbar.navigate.edges :as nav]
            [sandbar.mcp.clearance :as clearance]
            [sandbar.test-util     :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "mm-project-schema-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — thin, substrate-native.  A ref slot reads back as the target
;; entity's :db/id under `db/entity` navigation; `db/entity` on an
;; unresolvable keyword ident returns nil (the no-mint / absent probe).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ident-entity
  "Datomic entity for a keyword ident, or nil if the ident is unregistered.
   Pre-mint every S6 ident is unregistered → nil (the falsification probe)."
  [ident]
  (db/entity ident))

(defn- registered?
  "True iff `ident` resolves to a substrate entity carrying that :db/ident.
   nil / unresolvable ident → false.  The primitive behind every
   class-describe / property-exists assertion below."
  [ident]
  (boolean (some-> (ident-entity ident) :db/ident (= ident))))

(defn- make-memory!
  "Create a plain :mm/Memory (the corpus memorial primitive) with `props`."
  [props]
  (dt/make :mm/Memory
    (merge {:mm.memory/rel-path (str "test/" (name (gensym "m")) ".md")
            :mm.memory/name     "s6-test-memory"
            :mm.memory/body-raw "s6 acceptance body"}
           props)))

(defn- make-project!
  "Create a :mm/Project instance satisfying its required slots
   (ident / runs-in-context / corpus-repo / default-visibility).
   `:context/UNASSIGNED` is the seeded inert :mm/Context sentinel
   (schema/mm-artifact.edn batch (iv)) — a RANGE-VALID :mm/Context
   runs-in-context target.  NB it is NOT `:context/Empty`, which is a
   `:context/Context` of a DIFFERENT class and fails the slot's
   `:dt/range :mm/Context` (dt/make throws 'expects :mm/Context')."
  [props]
  (dt/make :mm/Project
    (merge {:mm.project/ident              (keyword "project" (name (gensym "p")))
            :mm.project/runs-in-context    :context/UNASSIGNED
            :mm.project/corpus-repo        "test/corpus"
            :mm.project/default-visibility :private}
           props)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) SCHEMA-LOAD SMOKE — :mm/Project introspects (pre-S6 it is absent)
;;     Maps XMINUS-BUILD-PLAN §S6 (a) "class.describe :mm/Project returns".
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-schema-load-smoke--project-class-introspects
  (testing ":mm/Project is a registered :dt/Class (pre-S6 this FAILS — no class)"
    (is (registered? :mm/Project)
        ":mm/Project must resolve to a class entity after the S6 mint")
    (is (= :dt/Class (:dt/type (ident-entity :mm/Project)))
        ":mm/Project must be a :dt/Class"))
  (testing ":mm/Project parent = :mm/Artifact and its lineage includes :mm/Memory (OF-P1)"
    ;; OF-P1 ruling: :dt/subclass-of :mm/Artifact so it INHERITS :mm.memory/*
    ;; (name/description/scope/status) — the AP-1 reduction basis — and the
    ;; total S7 label is TOTAL over :mm/Project sources by construction.
    (is (= #{:mm/Artifact} (set (dt/parents-of :mm/Project)))
        ":mm/Project's direct parent must be :mm/Artifact (sibling of :mm/Codebase)")
    (is (contains? (set (dt/ancestors-of :mm/Project)) :mm/Memory)
        ":mm/Project must descend :mm/Memory (carries the :mm.memory/* edge set — L-9)"))
  (testing ":mm/Project is concrete (instantiable)"
    (is (false? (boolean (dt/abstract? :mm/Project)))
        ":mm/Project must NOT be abstract — it is a first-class instantiable record"))
  (testing ":mm.project/ident is the frozen rename-proof identity key (unique-identity)"
    (is (registered? :mm.project/ident) ":mm.project/ident must be a registered property")
    (is (= :db.type/keyword (dt/range-of :mm.project/ident)))
    (is (dt/cardinality-one? :mm.project/ident))
    (is (true? (boolean (dt/required? :mm.project/ident))))
    (is (= :db.unique/identity (:db/unique (ident-entity :mm.project/ident)))
        ":mm.project/ident must be :db.unique/identity (resolves across a DB rebuild)")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) DEFAULT-DENY FALSIFICATION — absent visibility resolves :private
;;     Maps XMINUS-BUILD-PLAN §S6 (b).  The read helper ALREADY exists
;;     (clearance/entity-compartment); S6 mints the slots it reads.  This
;;     is a DB-live assertion: create a real :mm/Memory, read it back.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest b-default-deny--absent-visibility-resolves-private
  (testing "the :mm.memory/visibility slot minted (pre-S6 range is nil → FAIL)"
    (is (registered? :mm.memory/visibility))
    (is (= :db.type/keyword (dt/range-of :mm.memory/visibility)))
    (is (dt/cardinality-one? :mm.memory/visibility)))
  (testing "a :mm/Memory created WITHOUT :mm.memory/visibility reads :private (AP-6 fail-closed)"
    ;; The keystone: default-deny is READ-TIME (absent ⇒ :private via
    ;; *default-visibility*), never a stored backfill.  We assert the LIVE
    ;; entity carries no stored visibility AND the read helper defaults it.
    (let [m   (make-memory! {})
          ent (db/entity (:db/id m))]
      (is (nil? (:mm.memory/visibility ent))
          "no :private is written onto a fresh row (read-time default, not backfill)")
      (is (= :private (:visibility (clearance/entity-compartment ent)))
          "absent visibility resolves :private via the existing read helper")
      (is (nil? (:project (clearance/entity-compartment ent)))
          "absent owning-project resolves to nil (S9 resolves nil → :project/UNASSIGNED)")))
  (testing "a :mm/Memory created WITH :mm.memory/visibility :public reads :public"
    (let [m   (make-memory! {:mm.memory/visibility :public})
          ent (db/entity (:db/id m))]
      (is (= :public (:mm.memory/visibility ent))
          "an explicit :public visibility is stored + read back (the slot accepts writes)")
      (is (= :public (:visibility (clearance/entity-compartment ent)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) LABEL-TOTALITY (L-9) — the S7 label inputs are DEFINED on a
;;     :mm/Context source (the compartment primitive itself, which carries
;;     live cites/related edges).  S6 asserts the SLOTS the total label
;;     reads exist ON a Context source — the flow-rule enforcement is S7.
;;     Maps XMINUS-BUILD-PLAN §S6 (c) + DESIGN-W1-FIREWALL §6.2 (L-9 fold).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest c-label-totality--label-inputs-defined-on-context-source
  (testing ":mm.context/visible-projects minted as a card-many ref → :mm/Project"
    (is (registered? :mm.context/visible-projects))
    (is (= :mm/Project (dt/range-of :mm.context/visible-projects)))
    (is (dt/cardinality-many? :mm.context/visible-projects)))
  (testing ":mm.context/visible-projects is an effective slot on :mm/Context (the membership input)"
    (is (contains? (dt/slots-of :mm/Context) :mm.context/visible-projects)
        ":mm.context/visible-projects is an effective slot on :mm/Context"))
  (testing "the total label is DEFINED on a :mm/Context source with a foreign-context cites edge (L-9)"
    ;; The L-9 hazard: a :mm/Context carries live :mm.memory/cites/related
    ;; edges (it reuses the :mm.memory/* attribute surface), so the S7 label
    ;; function MUST be TOTAL over :mm/Context SOURCES, not just corpus
    ;; memorials.  S6's job here: (1) the Context->foreign-memory cites edge
    ;; is CONSTRUCTIBLE (the governable hazard exists to govern), and (2) the
    ;; label INPUTS the read helper consumes (:mm.memory/visibility +
    ;; owning-project) are RESOLVABLE on a Context entity — Datomic permits
    ;; these attributes on any entity, so the label never goes UNDEFINED for
    ;; a Context source.  (The write-time flow-rule REFUSAL of the bad edge
    ;; is S7 enforcement; S6 asserts the label is total, i.e. never nil.)
    ;; S7 NOTE: the write-time REFUSAL of this exact public→private edge is now
    ;; LIVE (EP-1, S7 BU-4) — `dt/make` on this Context THROWS a firewall
    ;; violation.  This S6 assertion is about CONSTRUCTIBILITY + label-totality
    ;; (its own line-190 comment: "the write-time flow-rule REFUSAL … is S7
    ;; enforcement"), so we seed the hazardous edge via RAW transact (bypassing
    ;; the S7 guard) to keep proving the edge exists + the label is total.  The
    ;; S7 refusal of the same edge through dt/make is covered by
    ;; sandbar.firewall.ep1-commit-path-test (T-1) + the L-9 source tests.
    (let [target (make-memory! {:mm.memory/visibility :private})
          ctx-id (do @(datomic.api/transact
                        (db/conn)
                        [{:db/ident             :mem/s6-l9-context
                          :dt/type              :mm/Context
                          :mm.memory/name       "s6-l9-context"
                          :mm.memory/visibility :public
                          :mm.memory/cites      (:db/id target)}])
                     (:db/id (db/entity :mem/s6-l9-context)))
          ent    (db/entity ctx-id)]
      (is (seq (:mm.memory/cites ent))
          "the Context->foreign-memory cites edge exists (the governable hazard S7 refuses)")
      (is (= :public (:mm.memory/visibility ent))
          "a :mm/Context SOURCE carries a resolvable visibility (label input DEFINED on Context)")
      (is (= :public (:visibility (clearance/entity-compartment ent)))
          "label(Context) resolves via the SAME read helper — TOTAL over Context sources (L-9)"))
    (testing "a :mm/Context with NO visibility still yields a DEFINED (fail-closed :private) label"
      (let [bare (dt/make :mm/Context {:mm.memory/name "s6-l9-bare-context"})
            ent  (db/entity (:db/id bare))]
        (is (= :private (:visibility (clearance/entity-compartment ent)))
            "label(Context) is DEFINED (fail-closed :private) even absent visibility — never nil (L-9 totality)")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (d) TIE-FIDELITY (ADR-F2 / acceptance-(d)) — runs-in-context card-many,
;;     Project<->Codebase<->Context resolve.
;;     Maps XMINUS-BUILD-PLAN §S6 (d) + S6-PLAN §7.4.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest d-tie-fidelity--project-context-codebase-resolve
  (testing ":mm.project/runs-in-context is a REQUIRED card-MANY ref → :mm/Context"
    (is (registered? :mm.project/runs-in-context))
    (is (= :mm/Context (dt/range-of :mm.project/runs-in-context)))
    (is (dt/cardinality-many? :mm.project/runs-in-context)
        "card-MANY — a project runs in home AND work contexts (R2 multi-machine)")
    (is (true? (boolean (dt/required? :mm.project/runs-in-context)))))
  (testing ":mm.project/code-repo is a card-many ref → :mm/Codebase"
    (is (registered? :mm.project/code-repo))
    (is (= :mm/Codebase (dt/range-of :mm.project/code-repo)))
    (is (dt/cardinality-many? :mm.project/code-repo)))
  (testing ":mm.project/corpus-repo is a STRING (RepoHandle no-mint), card-one, required"
    (is (= :db.type/string (dt/range-of :mm.project/corpus-repo)))
    (is (dt/cardinality-one? :mm.project/corpus-repo))
    (is (true? (boolean (dt/required? :mm.project/corpus-repo)))))
  (testing "a :mm/Project with BOTH a runs-in-context ref AND a code-repo ref — both resolve"
    (let [codebase (dt/make :mm/Codebase
                     {:mm.memory/name "s6-tie-codebase"
                      :mm.memory/rel-path "test/tie-codebase.md"})
          proj     (make-project! {:mm.project/code-repo (:db/id codebase)})
          ent      (db/entity (:db/id proj))]
      (is (seq (:mm.project/runs-in-context ent))
          "Project→Context tie resolves (acceptance-(d) forward leg)")
      (is (= #{:context/UNASSIGNED}
             (set (:mm.project/runs-in-context ent)))
          "runs-in-context resolves to the seeded :context/UNASSIGNED sentinel — a ref to an ident-bearing entity navigates to its keyword ident (Datomic enum-ref behavior), so the members ARE the idents (do NOT :db/ident-map over them). The raw datom stores the sentinel eid correctly.")
      (is (= #{(:db/id codebase)}
             (set (map :db/id (:mm.project/code-repo ent))))
          "Project→Codebase tie resolves (Codebase<->Context via Project is live)"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (e) MF-1 EDGE-TRAVERSABLE FALSIFICATION — a new REF slot materializes as
;;     a TRAVERSABLE edge (navigate.outbound-edges), NOT :dt/domain-only
;;     carrier text.  A ref slot missing from its class's :dt/slots vector
;;     round-trips as domain-only and this assertion FAILS.
;;     Maps XMINUS-BUILD-PLAN §S6 (e) + S6-PLAN §7.5 + MF-1 (the #1 seam).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- outbound-predicates
  "Predicate idents on the outbound edges FROM `entity-ident` — the exact
   surface `navigate.outbound-edges` (nav/outbound-edges, :full projection)
   returns.  A ref slot NOT in :dt/slots produces NO edge here (MF-1 fails)."
  [entity-ident & [predicate]]
  (->> (nav/outbound-edges (cond-> {:entity entity-ident :projection :full}
                             predicate (assoc :predicate predicate)))
       :edges
       (map :predicate)
       set))

(deftest e-mf1--ref-slots-materialize-as-traversable-edges
  (testing "Project→Context runs-in-context is a TRAVERSABLE edge (not domain-only)"
    (let [proj (make-project! {})
          eid  (:db/id proj)]
      (is (contains? (outbound-predicates eid) :mm.project/runs-in-context)
          "runs-in-context must appear as a navigate.outbound-edges edge (MF-1)")
      (is (= #{:mm.project/runs-in-context}
             (outbound-predicates eid :mm.project/runs-in-context))
          "predicate-scoped traversal returns the runs-in-context edge")
      (let [edge (->> (nav/outbound-edges {:entity eid
                                           :predicate :mm.project/runs-in-context
                                           :projection :full})
                      :edges first)]
        (is (= :context/UNASSIGNED (:db/ident (:target edge)))
            "the traversed edge dereferences to the :context/UNASSIGNED :mm/Context target"))))
  (testing "Memory→Project owning-project is a TRAVERSABLE edge (MF-1)"
    (let [proj (make-project! {})
          mem  (make-memory! {:mm.memory/visibility :private
                              :mm.memory/owning-project (:db/id proj)})
          eid  (:db/id mem)]
      (is (contains? (outbound-predicates eid) :mm.memory/owning-project)
          "owning-project must materialize as an edge — the S9 routing anchor (MF-1)")
      (let [edge (->> (nav/outbound-edges {:entity eid
                                           :predicate :mm.memory/owning-project
                                           :projection :full})
                      :edges first)]
        (is (= (:db/id proj) (:db/id (:target edge)))
            "the owning-project edge dereferences to the owning :mm/Project"))))
  (testing "Context→Project visible-projects is a TRAVERSABLE edge (MF-1)"
    (let [proj (make-project! {})
          ctx  (dt/make :mm/Context
                 {:mm.memory/name "s6-mf1-context"
                  :mm.context/visible-projects (:db/id proj)})
          eid  (:db/id ctx)]
      (is (contains? (outbound-predicates eid) :mm.context/visible-projects)
          "visible-projects must materialize as an edge — the membership inverse (MF-1)")
      (let [edge (->> (nav/outbound-edges {:entity eid
                                           :predicate :mm.context/visible-projects
                                           :projection :full})
                      :edges first)]
        (is (= (:db/id proj) (:db/id (:target edge)))
            "the visible-projects edge dereferences to the member :mm/Project")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (f) ACTIVITY-DISCRIMINATOR SMOKE (ONT-F5) — a declassification Activity
;;     carries activity-type + visibility-from/to; a plain projection does
;;     NOT.  Recorded on :mm/Log (concrete :mm/Activity subclass; the
;;     discriminators land on the ABSTRACT :mm/Activity, inherited by :mm/Log).
;;     Maps XMINUS-BUILD-PLAN §S6 (f) + S6-PLAN §7.6.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest f-activity-discriminators--declassification-vs-plain
  (testing "the three discriminators mint as keyword slots on :mm/Activity"
    (doseq [slot [:mm.activity/activity-type
                  :mm.activity/visibility-from
                  :mm.activity/visibility-to]]
      (is (registered? slot) (str slot " must be a registered property"))
      (is (= :db.type/keyword (dt/range-of slot)) (str slot " must be a keyword"))
      (is (= :mm/Activity (dt/domain-of slot)) (str slot " domain is :mm/Activity")))
    ;; inherited by the concrete :mm/Log (declassification is a narrative Log)
    (let [log-slots (dt/slots-of :mm/Log)]
      (is (contains? log-slots :mm.activity/visibility-from)
          ":mm/Log inherits the declassification discriminators from :mm/Activity")))
  (testing "a declassification :mm/Log carries visibility-from/to; a plain :mm/Log does NOT"
    (let [declassify (dt/make :mm/Log
                       {:mm.memory/name "s6-declassification"
                        :mm.activity/activity-type   :declassification
                        :mm.activity/visibility-from :private
                        :mm.activity/visibility-to   :public})
          plain      (dt/make :mm/Log
                       {:mm.memory/name "s6-plain-projection"})
          d-ent      (db/entity (:db/id declassify))
          p-ent      (db/entity (:db/id plain))]
      (is (= :declassification (:mm.activity/activity-type d-ent)))
      (is (= :private (:mm.activity/visibility-from d-ent)))
      (is (= :public  (:mm.activity/visibility-to d-ent)))
      (is (nil? (:mm.activity/visibility-from p-ent))
          "a plain projection carries NO visibility-from (the ONT-F5 discriminator)")
      (is (nil? (:mm.activity/visibility-to p-ent))
          "a plain projection carries NO visibility-to — the two are distinguishable"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; RATIFIED-MANIFEST GUARD INVARIANTS — build EXACTLY the manifest, no more,
;; no less.  Each guards a HARD RULE against drift.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest g-sentinel-seeded--project-unassigned
  ;; §4: :mm.memory/owning-project ABSENT resolves (read-time) to the
  ;; :project/UNASSIGNED sentinel — a :mm/Project INSTANCE seeded IN the
  ;; schema EDN so it exists at first write.
  (testing ":project/UNASSIGNED is a seeded :mm/Project instance"
    (is (registered? :project/UNASSIGNED)
        ":project/UNASSIGNED must be seeded (the owning-project default target)")
    (is (= :mm/Project (:dt/type (ident-entity :project/UNASSIGNED)))
        ":project/UNASSIGNED must be a :mm/Project INSTANCE (a sentinel VALUE, not a type)"))
  (testing "the sentinel is fail-closed :private and satisfies its required slots"
    (let [s (ident-entity :project/UNASSIGNED)]
      (is (= :private (:mm.project/default-visibility s))
          "the sentinel reads :private — an unassigned memory is confined (fail-closed)")
      (is (= :project/UNASSIGNED (:mm.project/ident s)))
      ;; it is a VALID instance ⇒ schema-load did not reject it on required slots
      (doseq [req (dt/required-slots-of :mm/Project)]
        (is (some? (get s req))
            (str "sentinel must satisfy required slot " req " (else schema-load rejects it)"))))))

(deftest h-machine-id--declared-but-unpopulated
  ;; AP-MACHINE-ID O3: :mm.context/machine-id is DECLARED schema-ready but
  ;; S6 MUST NOT write a VALUE onto any Context.
  (testing ":mm.context/machine-id is a resolvable string, card-one property"
    (is (registered? :mm.context/machine-id))
    (is (= :db.type/string (dt/range-of :mm.context/machine-id)))
    (is (dt/cardinality-one? :mm.context/machine-id)))
  (testing "NO seeded/existing :mm/Context instance carries a machine-id VALUE"
    (let [ctxs (dt/all-instances-of :mm/Context)]
      (is (every? (fn [c] (nil? (:mm.context/machine-id c))) ctxs)
          "machine-id is schema-ready but UNPOPULATED — no value written (AP-MACHINE-ID O3)"))))

(deftest i-repohandle-no-mint--corpus-repo-is-string
  ;; S3-RATIFICATION §A: RepoHandle NO-MINT.  corpus-repo is a string, not a
  ;; ref; NO :mm/RepoHandle class exists.
  (testing "NO :mm/RepoHandle class was minted (a stray mint contradicts the manifest)"
    (is (not (registered? :mm/RepoHandle))
        ":mm/RepoHandle must NOT exist — RepoHandle is NO-MINT per the S3 census"))
  (testing ":mm.project/corpus-repo is a :db.type/string (NOT a ref)"
    (is (= :db.type/string (dt/range-of :mm.project/corpus-repo))
        "corpus-repo is a string pointer, not a ref — the no-mint collapse")))

(deftest j-ap1-reduction--inherited-slots-not-minted
  ;; AP-1: :mm/Project REUSES the inherited :mm.memory/name/description/
  ;; scope/status — it does NOT mint project-level copies.
  (testing "NO :mm.project/name / description / scope / status were minted"
    (doseq [slot [:mm.project/name :mm.project/description
                  :mm.project/scope :mm.project/status]]
      (is (not (registered? slot))
          (str slot " must NOT be minted — reused from inherited :mm.memory/* (AP-1)"))))
  (testing "a :mm/Project reads the inherited :mm.memory/name via inheritance"
    ;; The inherited scalar slots are effective on :mm/Project (via :mm/Artifact
    ;; → :mm/Memory) and writable/readable on an instance.
    (let [proj-slots (dt/slots-of :mm/Project)]
      (is (contains? proj-slots :mm.memory/name)
          ":mm.memory/name is an effective (inherited) slot on :mm/Project")
      (is (contains? proj-slots :mm.memory/scope)
          ":mm.memory/scope is an effective (inherited) slot on :mm/Project"))
    (let [proj (make-project! {:mm.memory/name "s6-inherited-name"})
          ent  (db/entity (:db/id proj))]
      (is (= "s6-inherited-name" (:mm.memory/name ent))
          "a :mm/Project stores + reads the INHERITED :mm.memory/name (AP-1 reuse)"))))

(deftest k-firewall-posture-slots--minted-with-declared-shape
  ;; The 5 project-level firewall slots + the 2 memory firewall slots — the
  ;; core mint the whole S6 exists for.  Assert each lands with its ratified
  ;; range/cardinality (DESIGN-ONTOLOGY §2.2/§2.3, the manifest).
  (testing ":mm/Project firewall posture slots"
    (is (= :db.type/keyword (dt/range-of :mm.project/default-visibility)))
    (is (true? (boolean (dt/required? :mm.project/default-visibility)))
        "default-visibility is REQUIRED on a project")
    (is (= :db.type/keyword (dt/range-of :mm.project/firewall-class)))
    (is (= :db.type/boolean (dt/range-of :mm.project/code-ref-public?))
        "code-ref-public? SLOT ships (policy stays default-deny)")
    (is (= :db.type/string  (dt/range-of :mm.project/push-allowlist)))
    (is (dt/cardinality-many? :mm.project/push-allowlist)
        "push-allowlist is card-MANY (corpus-canonical push destinations, L-2)")
    (is (= :db.type/keyword (dt/range-of :mm.project/corpus-layout)))
    (is (= :db.type/uuid    (dt/range-of :mm.project/authority-uuid))
        "authority-uuid is the per-project v5-derivation root (WF-I2)"))
  (testing ":mm/Memory firewall slots + MF-1 for owning-project"
    (is (= :mm/Project (dt/range-of :mm.memory/owning-project))
        "owning-project is a ref → :mm/Project (the routing anchor)")
    (is (dt/cardinality-one? :mm.memory/owning-project))
    ;; MF-1: owning-project must be an effective slot on :mm/Memory to
    ;; materialize as an edge (the (e) battery exercises traversal; here we
    ;; guard the :dt/slots membership directly).
    (is (contains? (dt/slots-of :mm/Memory) :mm.memory/owning-project)
        "owning-project is an effective slot on :mm/Memory (MF-1 edge prerequisite)")
    (is (contains? (dt/slots-of :mm/Memory) :mm.memory/visibility)
        "visibility is an effective slot on :mm/Memory")))
