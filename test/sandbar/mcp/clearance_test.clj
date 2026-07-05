(ns sandbar.mcp.clearance-test
  "Pure predicate truth table for the compartment-clearance layer
   (AP-S4-2 / DEP-S5→S9).  No DB, no wire — every case is a map fed
   directly to a `sandbar.mcp.clearance` predicate.

   Covers the five ratified axes (DESIGN-D3 §8): :public, :private+cleared,
   :private+uncleared, unknown-visibility, nil-principal — plus:
   - the INERT-UNTIL-S6 fail-closed cases: an entity with NO `:mm.memory/visibility`
     slot defaults to `:private` (*default-visibility*, AP-6) and a restricted
     principal is therefore NOT cleared (the pre-S6 posture);
   - the `:auth/full-clearance?` short-circuit (AP-11 mechanism (b));
   - the nil-identity notify-plane policy: a subscriber with no `:identity`
     clears `:public` ONLY (AP-8), NOT full-access.

   Spec: DESIGN-D3-NOTIFY-PLANE-GATE.md §2/§8, S5-PLAN.md §2.2 item 3 + item 8."
  (:require [clojure.test          :refer :all]
            [sandbar.mcp.clearance :as clearance]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test principals + entities (plain maps — the shape the wire hands us:
;; an :auth/ServiceAccount entity with :db/id; S6 slots :auth/cleared-projects
;; (a multi-ref: seq of {:db/id …} refs) and :auth/full-clearance?)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def proj-a-eid 100)
(def proj-b-eid 200)

(def prin-clears-a
  "Restricted principal cleared for project A only."
  {:db/id 1
   :auth/service-name :sub-a
   :auth/cleared-projects [{:db/id proj-a-eid}]})

(def prin-clears-b
  "Restricted principal cleared for project B only."
  {:db/id 2
   :auth/service-name :sub-b
   :auth/cleared-projects [{:db/id proj-b-eid}]})

(def prin-unscoped
  "Authenticated principal with NO cleared-projects slot — the pre-S6
   restricted-by-default shape (clears nothing)."
  {:db/id 3
   :auth/service-name :sub-unscoped})

(def prin-full-clearance
  "Operator principal marked :auth/full-clearance? (AP-11 mechanism (b)) —
   clears every compartment by construction."
  {:db/id 4
   :auth/service-name :operator
   :auth/full-clearance? true})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; entity-compartment — slot extraction + inert-until-S6 default
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest entity-compartment-reads-visibility-and-owning-project
  (testing ":private entity yields its owning-project :db/id as the compartment key"
    (is (= {:visibility :private :project proj-a-eid}
           (clearance/entity-compartment
            {:db/id 10
             :mm.memory/visibility :private
             :mm.memory/owning-project {:db/id proj-a-eid}}))))
  (testing ":public entity yields a nil project (the lattice bottom)"
    (is (= {:visibility :public :project nil}
           (clearance/entity-compartment
            {:db/id 11 :mm.memory/visibility :public})))))

(deftest entity-compartment-defaults-absent-visibility-to-private
  ;; INERT-UNTIL-S6 keystone: pre-S6 no entity carries :mm.memory/visibility,
  ;; so entity-compartment MUST default it to *default-visibility* (:private,
  ;; AP-6) — the fail-closed pre-S6 posture, not :public.
  (testing "an entity with NO visibility slot defaults to :private (fail-closed, AP-6)"
    (is (= {:visibility :private :project nil}
           (clearance/entity-compartment {:db/id 12}))))
  (testing "the default tracks *default-visibility* if it is rebound"
    (binding [clearance/*default-visibility* :public]
      (is (= {:visibility :public :project nil}
             (clearance/entity-compartment {:db/id 13}))))))

(deftest default-visibility-is-fail-closed-private
  (testing "*default-visibility* is :private (AP-6 — fail-closed pre-S6)"
    (is (= :private clearance/*default-visibility*))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; principal-clears-project? — clearance-set membership + full-clearance short-circuit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest principal-clears-project?-membership
  (testing "a principal clears a project in its :auth/cleared-projects set"
    (is (true? (clearance/principal-clears-project? prin-clears-a proj-a-eid))))
  (testing "a principal does NOT clear a project outside its set"
    (is (false? (clearance/principal-clears-project? prin-clears-a proj-b-eid)))))

(deftest principal-clears-project?-full-clearance-short-circuits
  ;; AP-11 mechanism (b): :auth/full-clearance? true clears every project,
  ;; including ones absent from (or with no) :auth/cleared-projects.
  (testing "a full-clearance principal clears ANY project"
    (is (true? (clearance/principal-clears-project? prin-full-clearance proj-a-eid)))
    (is (true? (clearance/principal-clears-project? prin-full-clearance proj-b-eid)))
    (is (true? (clearance/principal-clears-project? prin-full-clearance 999)))))

(deftest principal-clears-project?-fail-closed
  (testing "a nil principal clears nothing"
    (is (false? (clearance/principal-clears-project? nil proj-a-eid))))
  (testing "an unscoped principal (no :auth/cleared-projects slot) clears nothing — pre-S6 default"
    (is (false? (clearance/principal-clears-project? prin-unscoped proj-a-eid))))
  (testing "a nil project-eid is not cleared by a restricted principal"
    ;; a :private entity pre-S6 has project=nil; nil ∉ {proj-a-eid} ⇒ not cleared
    (is (false? (clearance/principal-clears-project? prin-clears-a nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; cleared-for-compartment? — THE predicate truth table (five axes)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest cleared-for-compartment?-public-is-lattice-bottom
  (testing ":public — every principal is cleared (lattice bottom)"
    (is (true? (clearance/cleared-for-compartment? prin-clears-a  {:visibility :public :project nil})))
    (is (true? (clearance/cleared-for-compartment? prin-unscoped  {:visibility :public :project nil})))
    (is (true? (clearance/cleared-for-compartment? nil            {:visibility :public :project nil})))))

(deftest cleared-for-compartment?-private-cleared
  (testing ":private + principal clears the owning project → cleared"
    (is (true? (clearance/cleared-for-compartment?
                prin-clears-a {:visibility :private :project proj-a-eid}))))
  (testing ":private + full-clearance principal → cleared (AP-11)"
    (is (true? (clearance/cleared-for-compartment?
                prin-full-clearance {:visibility :private :project proj-a-eid})))))

(deftest cleared-for-compartment?-private-uncleared
  (testing ":private + principal clears a DIFFERENT project → NOT cleared"
    (is (false? (clearance/cleared-for-compartment?
                 prin-clears-b {:visibility :private :project proj-a-eid}))))
  (testing ":private + unscoped principal → NOT cleared (pre-S6 default-deny)"
    (is (false? (clearance/cleared-for-compartment?
                 prin-unscoped {:visibility :private :project proj-a-eid}))))
  (testing ":private + nil-project compartment (pre-S6 shape) → restricted principal NOT cleared"
    (is (false? (clearance/cleared-for-compartment?
                 prin-clears-a {:visibility :private :project nil})))))

(deftest cleared-for-compartment?-unknown-visibility-fails-closed
  (testing "an unknown lattice level fails closed for every principal"
    (is (false? (clearance/cleared-for-compartment?
                 prin-clears-a {:visibility :internal :project proj-a-eid})))
    (is (false? (clearance/cleared-for-compartment?
                 prin-full-clearance {:visibility :internal :project proj-a-eid})))
    (is (false? (clearance/cleared-for-compartment?
                 prin-clears-a {:visibility nil :project proj-a-eid})))))

(deftest cleared-for-compartment?-inert-until-s6-default-deny
  ;; The load-bearing pre-S6 case: an entity with NO visibility slot defaults
  ;; to :private (via entity-compartment), and a restricted principal (no
  ;; cleared-projects) is therefore NOT cleared — deny-by-default the instant
  ;; the gate lands, before any S6 slot exists.
  (testing "absent-visibility entity → :private default → restricted principal NOT cleared"
    (let [compartment (clearance/entity-compartment {:db/id 20})]  ; no visibility slot
      (is (= :private (:visibility compartment)))
      (is (false? (clearance/cleared-for-compartment? prin-unscoped compartment)))
      (is (false? (clearance/cleared-for-compartment? prin-clears-a compartment)))))
  (testing "absent-visibility entity → a full-clearance operator IS still cleared"
    (let [compartment (clearance/entity-compartment {:db/id 21})]
      (is (true? (clearance/cleared-for-compartment? prin-full-clearance compartment))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; subscriber-cleared-for-entity? — the notify-plane delivery gate + nil policy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def private-a-entity
  "A :private entity owned by project A."
  {:db/id 30
   :mm.memory/visibility :private
   :mm.memory/owning-project {:db/id proj-a-eid}})

(def public-entity
  {:db/id 31 :mm.memory/visibility :public})

(def subscribers
  "A registry map mirroring notifications/all-subscribers:
   subscriber-id → {:identity principal …}."
  {"sub-a"    {:id "sub-a"    :identity prin-clears-a}
   "sub-b"    {:id "sub-b"    :identity prin-clears-b}
   "sub-full" {:id "sub-full" :identity prin-full-clearance}
   "sub-nil"  {:id "sub-nil"  :identity nil}})

(deftest subscriber-cleared-for-entity?-private-filters-by-compartment
  (testing "the same-compartment subscriber is cleared for the :private entity"
    (is (true? (clearance/subscriber-cleared-for-entity? subscribers private-a-entity "sub-a"))))
  (testing "the out-of-compartment subscriber is NOT cleared (the leak, now blocked)"
    (is (false? (clearance/subscriber-cleared-for-entity? subscribers private-a-entity "sub-b"))))
  (testing "the full-clearance operator is cleared for the :private entity (AP-11)"
    (is (true? (clearance/subscriber-cleared-for-entity? subscribers private-a-entity "sub-full")))))

(deftest subscriber-cleared-for-entity?-nil-identity-public-only
  ;; AP-8: a nil-identity subscriber clears :public ONLY — NOT full-access.
  (testing "nil-identity subscriber is cleared for a :public entity"
    (is (true? (clearance/subscriber-cleared-for-entity? subscribers public-entity "sub-nil"))))
  (testing "nil-identity subscriber is NOT cleared for a :private entity"
    (is (false? (clearance/subscriber-cleared-for-entity? subscribers private-a-entity "sub-nil")))))

(deftest subscriber-cleared-for-entity?-public-fans-to-all
  (testing "every subscriber (incl. nil-identity) is cleared for a :public entity"
    (doseq [sub-id ["sub-a" "sub-b" "sub-full" "sub-nil"]]
      (is (true? (clearance/subscriber-cleared-for-entity? subscribers public-entity sub-id))
          (str sub-id " must be cleared for a :public entity (lattice bottom)")))))

(deftest subscriber-cleared-for-entity?-unknown-subscriber-fails-closed
  ;; A subscriber-id absent from the registry has principal=nil → treated as
  ;; nil-identity → cleared for :public only.
  (testing "unknown subscriber-id → nil identity → :public only"
    (is (false? (clearance/subscriber-cleared-for-entity? subscribers private-a-entity "ghost")))
    (is (true?  (clearance/subscriber-cleared-for-entity? subscribers public-entity  "ghost")))))

(deftest subscriber-cleared-for-entity?-inert-until-s6
  ;; Delivery gate over a pre-S6 entity (no visibility slot ⇒ :private default):
  ;; restricted subscribers get nothing, full-clearance operator gets it.
  (let [pre-s6-entity {:db/id 40}]  ; no visibility, no owning-project
    (testing "restricted subscriber NOT cleared for a pre-S6 (default-:private) entity"
      (is (false? (clearance/subscriber-cleared-for-entity? subscribers pre-s6-entity "sub-a")))
      (is (false? (clearance/subscriber-cleared-for-entity? subscribers pre-s6-entity "sub-b"))))
    (testing "nil-identity subscriber NOT cleared for a pre-S6 (default-:private) entity"
      (is (false? (clearance/subscriber-cleared-for-entity? subscribers pre-s6-entity "sub-nil"))))
    (testing "full-clearance operator IS cleared for a pre-S6 entity"
      (is (true? (clearance/subscriber-cleared-for-entity? subscribers pre-s6-entity "sub-full"))))))
