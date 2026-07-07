(ns sandbar.firewall.xexam-probe-test
  "Cross-examiner probe — empirical verification of codex L2/L3 leak claims."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.firewall.label :as label]
            [sandbar.firewall.core :as fw]
            [sandbar.navigate.siblings :as sib]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-xexam" :auth? false}))

(defn- fw-violation? [thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))))))

;;;; ---------------------------------------------------------------------------
;;;; L2-finding-1: update-entity! sparse-map bypasses the owning-project
;;;; intrinsic-visibility (declassification) guard.
;;;; ---------------------------------------------------------------------------

(deftest l2f1-update-entity-sparse-map-owning-project
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (sup/seed-project! :proj/privA :private :ctx/priv)
  ;; an EXPLICIT :public memory owned in the public project
  (sup/seed-memory! :mem/pub-target :public :proj/pub)

  (testing "BASELINE — full entity map (real visibility present) should refuse
            re-owning a public memory into a private project (T-12 shape)"
    ;; Full DB entity carries :mm.memory/visibility :public
    (is (fw-violation?
          #(dt/update-entity! :mem/pub-target
                              {:mm.memory/owning-project :proj/privA}))
        "full-map re-own public->private MUST refuse"))

  (testing "L2-CLAIM — SPARSE caller-supplied entity map (no :mm.memory/visibility)
            bypasses the intrinsic-visibility guard"
    (let [sparse {:db/id (sup/eid-of :mem/pub-target) :dt/type :mm/Memory}
          bypassed? (try
                      (dt/update-entity! sparse
                                         {:mm.memory/owning-project :proj/privA}
                                         {:validate? false})
                      true ; write went through == BYPASS
                      (catch clojure.lang.ExceptionInfo e
                        (if (some #(= :firewall-violation (:type %))
                                  (:errors (ex-data e)))
                          false ; refused == guard held
                          (throw e))))]
      (println "L2F1 sparse-map bypass result — write-succeeded?" bypassed?)
      ;; Report both directions; the finding predicts bypassed? = true
      (is (boolean? bypassed?))))

  (testing "L2-SEVERITY — the MCP-shaped path (ident/eid through the DB entity)
            should still REFUSE (bypass is in-process hand-rolled-map ONLY)"
    ;; Re-seed since prior test may have mutated owning-project
    (sup/seed-memory! :mem/pub-target2 :public :proj/pub)
    (let [full-ent (db/entity :mem/pub-target2)  ; == what eref/resolve returns for an ident
          refused? (fw-violation?
                     #(dt/update-entity! full-ent
                                         {:mm.memory/owning-project :proj/privA}
                                         {:validate? false}))]
      (println "L2F1 MCP-shaped (full DB entity) refused?" refused?)
      (is (true? refused?)
          "the MCP wire path (db/entity → full realized map) MUST still refuse"))))

;;;; ---------------------------------------------------------------------------
;;;; L2-finding-2: siblings-of returns private siblings with no firewall guard.
;;;; ---------------------------------------------------------------------------

(deftest l2f2-siblings-of-no-guard
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (sup/seed-project! :proj/privA :private :ctx/priv)
  (sup/seed-memory! :mem/pub-sib :public :proj/pub
                    {:mm.memory/rel-path "decisions/pub.md"})
  (sup/seed-memory! :mem/priv-sib :private :proj/privA
                    {:mm.memory/rel-path "decisions/secret.md"})
  (testing "siblings-of from a public anchor over rel-path returns the private
            sibling as a full entity map (codex L2-finding-2)"
    (let [res (sib/siblings-of {:entity :mem/pub-sib
                                :path-slot :mm.memory/rel-path})
          sib-idents (set (map :db/ident (:siblings res)))]
      (println "L2F2 siblings result idents:" sib-idents)
      (println "L2F2 full siblings:" (mapv #(select-keys % [:db/id :db/ident :mm.memory/visibility]) (:siblings res)))
      ;; finding predicts :mem/priv-sib IS present (leak of private eid+content)
      (is (set? sib-idents)))))

;;;; ---------------------------------------------------------------------------
;;;; L3-finding-1: public-bottom Context masked private via inherited
;;;; :mm.memory/visibility, then admits a private project through the tie.
;;;; ---------------------------------------------------------------------------

(deftest l3f1-public-bottom-masking
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/priv :private :ctx/priv)
  (testing "a public-bottom context carrying :mm.memory/visibility :private is
            labelled private, so the tie permits a private project (L3-finding-1)"
    (let [bypassed?
          (try
            (dt/make :mm/Context
                     {:mm.memory/name "masked-public-bottom"
                      :mm.context/firewall-class :public-bottom
                      :mm.memory/visibility :private
                      :mm.context/visible-projects (sup/eid-of :proj/priv)})
            true ; write succeeded == tie did NOT refuse == masking bypass
            (catch clojure.lang.ExceptionInfo e
              (if (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))
                false
                (throw e))))]
      (println "L3F1 public-bottom masking — write-succeeded (tie-permitted)?" bypassed?)
      (is (boolean? bypassed?))))

  (testing "control — same context WITHOUT the visibility mask IS refused"
    (is (fw-violation?
          #(dt/make :mm/Context
                    {:mm.memory/name "honest-public-bottom"
                     :mm.context/firewall-class :public-bottom
                     :mm.context/visible-projects (sup/eid-of :proj/priv)}))
        "honest public-bottom ∋ private project MUST refuse")))

;;;; ---------------------------------------------------------------------------
;;;; Direct label inspection for the masking case (no write path)
;;;; ---------------------------------------------------------------------------

(deftest l3f1-masking-both-legs
  ;; symmetric leg: runs-in-context (project end) into a masked public-bottom ctx
  (sup/seed-context! :ctx/pubmask2 :public-bottom)
  (sup/raw-transact! [{:db/ident :ctx/pubmask2 :mm.memory/visibility :private}])
  (sup/seed-context! :ctx/privc :project-isolated)
  (sup/seed-project! :proj/privX :private :ctx/privc)
  (testing "runs-in-context leg: private project → masked public-bottom context
            (the co-load root) is admitted because the mask makes the ctx label
            private, defeating the tie"
    (let [bypassed?
          (try
            (dt/update-entity! :proj/privX
                               {:mm.project/runs-in-context (sup/eid-of :ctx/pubmask2)})
            true
            (catch clojure.lang.ExceptionInfo e
              (if (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))
                false (throw e))))]
      (println "L3F1 runs-in-context leg into masked public-bottom — admitted?" bypassed?)
      (is (boolean? bypassed?)))))

;;;; ---------------------------------------------------------------------------
;;;; L4-finding-2: CA-6 source-side gap has a LEAK-ADJACENT consequence —
;;;; same-batch public project P + public A + private B (both owned by P) +
;;;; A cites B: both members collapse to UNASSIGNED so A->B (public->private)
;;;; is PERMITTED at batch write, storing an edge EP-1 should have refused.
;;;; ---------------------------------------------------------------------------

(deftest l4f2-same-batch-owner-collapse-permits-public-to-private
  (sup/seed-context! :ctx/home :public-bottom)
  (let [ctx-home-eid (sup/eid-of :ctx/home)
        batch [{:db/ident :proj/P-new :dt/type :mm/Project
                :mm.memory/name "P-new" :mm.project/ident :proj/P-new
                :mm.project/corpus-repo "r" :mm.project/default-visibility :public
                :mm.project/firewall-class :public-bottom
                :mm.project/runs-in-context ctx-home-eid}
               {:db/ident :mem/A-pub :dt/type :mm/Memory :mm.memory/name "A"
                :mm.memory/visibility :public :mm.memory/owning-project :proj/P-new
                :mm.memory/cites :mem/B-priv}
               {:db/ident :mem/B-priv :dt/type :mm/Memory :mm.memory/name "B"
                :mm.memory/visibility :private :mm.memory/owning-project :proj/P-new}]]
    (testing "same-batch make-all* with A(public in P) cites B(private in P):
              does the batch floor REFUSE the public->private edge, or does the
              source-side UNASSIGNED collapse PERMIT it (L4-finding-2)?"
      (let [permitted?
            (try (dt/make-all* batch) true
                 (catch clojure.lang.ExceptionInfo e
                   (if (some #(= :firewall-violation (:type %)) (:errors (ex-data e)))
                     false (throw e))))]
        (println "L4F2 same-batch public->private (owner-collapse) — PERMITTED at write?" permitted?)
        (when permitted?
          ;; verify the bad edge actually stored AND that EP-3 now lists it blocked
          (let [ep3 (dt/outbound-edges-of :mem/A-pub {:predicate :mm.memory/cites})]
            (println "L4F2 stored-edge EP-3 view:" (mapv #(dissoc % :target) ep3))))
        (is (boolean? permitted?))))))

(deftest l3f1-label-inspection
  (sup/seed-context! :ctx/pubmask :public-bottom)
  ;; add the visibility mask via raw transact
  (sup/raw-transact! [{:db/ident :ctx/pubmask
                       :mm.memory/visibility :private}])
  (let [db (db/db)
        ent (d/entity db :ctx/pubmask)
        lbl (label/label-of db ent)]
    (println "L3F1 masked-context label:" lbl)
    (println "L3F1 public?:" (= :public (:sensitivity lbl)))
    (is (map? lbl))))
