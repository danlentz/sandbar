(ns sandbar.firewall.tie-test
  "S7 BU-7 — the compartment-tie clause (tie-permits?) at EP-1, exercised
   through dt/make / dt/update-entity! on the two tie legs.

   T-11 four corners:
     (a) public-bottom Context visible-projects ∋ PRIVATE project → REFUSED
     (b) PRIVATE project runs-in-context → public-bottom context → REFUSED
         (the SAME forbidden pair as (a) — public compartment ∋ private member
         — checked from the OTHER end, the runs-in-context write on the Project;
         closes the S7-DESIGN-B OQ-2 smuggling gap symmetrically)
     (c) deadlock regression [falsifier]: a private project ADDS a 2nd private
         context → PERMITTED (tie-permits?, not firewall-permits?)
     (d) private context visible-projects ∋ public project (co-load) → PERMITTED"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [sandbar.db.datatype :as dt]
            [sandbar.firewall.support :as sup]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-tie" :auth? false}))

(defn- fw-violation? [thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-11a — public compartment ∋ private member (via visible-projects) REFUSED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tie-public-context-visible-private-project-refused
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/priv :private :ctx/priv)
  (testing "adding a PRIVATE project to a public-bottom context's
            visible-projects is REFUSED (the catastrophic co-load fail-open)"
    (is (fw-violation?
          #(dt/update-entity! :ctx/pub
                              {:mm.context/visible-projects (sup/eid-of :proj/priv)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-11b — PRIVATE project runs-in-context → public-bottom context REFUSED
;;         (the SAME forbidden pair as T-11a, from the OTHER end — the tie is
;;          checked from WHICHEVER end carries the write; closes OQ-2)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tie-private-project-runs-in-public-bottom-context-refused
  (sup/seed-context! :ctx/pub  :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  ;; a PRIVATE project, initially tied to its own private compartment
  (sup/seed-project! :proj/priv :private :ctx/priv)
  (testing "a PRIVATE project declaring runs-in-context → a PUBLIC-BOTTOM context
            is REFUSED — the (public compartment ∋ private member) pair checked
            from the runs-in-context (Project) end, symmetric with T-11a's
            visible-projects (Context) end"
    (is (fw-violation?
          #(dt/update-entity! :proj/priv
                              {:mm.project/runs-in-context (sup/eid-of :ctx/pub)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-11c — deadlock regression [falsifier]: private project ADDS a 2nd private
;;         context → PERMITTED (would DEADLOCK under a firewall-permits? tie)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tie-private-project-adds-second-private-context-permitted
  (sup/seed-context! :ctx/workA :project-isolated)
  (sup/seed-context! :ctx/workB :project-isolated)
  (sup/seed-project! :proj/multi :private :ctx/workA)
  (testing "a private project declaring a 2nd private context via runs-in-context
            SUCCEEDS — tie-permits? (both ends private ⇒ permit), NOT the subset
            clause that would deadlock this legitimate multi-context declaration"
    (is (some?
          (dt/update-entity! :proj/multi
                             {:mm.project/runs-in-context
                              [(sup/eid-of :ctx/workA) (sup/eid-of :ctx/workB)]})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-11d — private context visible-projects ∋ PUBLIC project (co-load) PERMITTED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tie-private-context-visible-public-project-permitted
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-context! :ctx/pub  :public-bottom)
  ;; firewall-class :public-bottom so :proj/pub is EFFECTIVELY :public under the
  ;; CA-4 4-way composition — the "public project in a private context" co-load
  ;; shape must be a genuinely PUBLIC member, not an absent→:private one.
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (testing "a PRIVATE context listing a PUBLIC project in visible-projects is
            PERMITTED (the co-load shape — public member in a private
            compartment is fine; only public-compartment ∋ private is refused)"
    (is (some?
          (dt/update-entity! :ctx/priv
                             {:mm.context/visible-projects (sup/eid-of :proj/pub)})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-11e/f (adjudication must-fix #3) — a :public-bottom context MASKED with
;; intrinsic :private visibility must STILL refuse a private member from BOTH
;; legs.  The mask composes :sensitivity :private, so a tie keyed on the
;; composed sensitivity is defeated; the tie must key on the :public-bottom
;; DESIGNATION (:tie-designation).  The four T-11 tests above seed public-bottom
;; contexts WITHOUT the mask — they miss this (pre-fix BOTH legs PERMITTED).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-masked-public-bottom-context! [ident]
  ;; :public-bottom DESIGNATION but intrinsic :private visibility (the mask)
  (sup/raw-transact! [{:db/ident              ident
                       :dt/type               :mm/Context
                       :mm.memory/name        (name ident)
                       :mm.context/firewall-class :public-bottom
                       :mm.memory/visibility  :private}])
  (sup/eid-of ident))

(deftest tie-masked-public-bottom-context-visible-private-project-refused
  (seed-masked-public-bottom-context! :ctx/masked)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/priv :private :ctx/priv)
  (testing "a :public-bottom context MASKED with :private visibility STILL
            refuses a private project in visible-projects — the tie keys on the
            :public-bottom DESIGNATION, not the visibility-masked sensitivity"
    (is (fw-violation?
          #(dt/update-entity! :ctx/masked
                              {:mm.context/visible-projects (sup/eid-of :proj/priv)})))))

(deftest tie-private-project-runs-in-masked-public-bottom-context-refused
  (seed-masked-public-bottom-context! :ctx/masked)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/priv :private :ctx/priv)
  (testing "the symmetric runs-in-context leg: a private project declaring
            runs-in-context → the MASKED public-bottom context is STILL refused"
    (is (fw-violation?
          #(dt/update-entity! :proj/priv
                              {:mm.project/runs-in-context (sup/eid-of :ctx/masked)})))))
