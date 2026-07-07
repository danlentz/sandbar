(ns sandbar.firewall.shape-safety-test
  "S7 BU-6 / BU-7 — T-22: the 3 directional-firewall :mm/Shape seeds
   (schema/mm-artifact.edn, S7-PLAN §6; charter idents kept per §8-R18).

   T-22  shape-safety — `corpus-repo \"/Users/dan/x\"` fails
         no-absolute-path; `..` traversal fails; submodule-escape fails
         project-layout-safety; a public project + populated URL +
         `code-ref-public?` absent fails repo-handle-url-safety — PLUS
         the green complements (relative path passes; code-ref-public?
         true passes; a private project with a URL passes; a
         non-:submodule/:subdir layout is not examined by layout-safety;
         :public-bottom firewall-class does NOT put a :private-default
         project in url-safety scope).

   These are C3 single-entity invariants exercised through the SHACL
   walker (sandbar.shape/validate) — disjoint from the two-entity flow
   rule (EP-1/EP-3), so setup transacts RAW (sandbar.firewall.support)
   and no assertion here touches the firewall guard."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.shape :as shape]
            [sandbar.shape.safety :as safety]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "fw-shape" :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — raw-seeded subjects + per-shape walk-result indexing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-project*!
  "Raw-seed a :mm/Project with full slot control (unlike sup/seed-project!,
   which fixes corpus-repo); returns the eid.  Ties to the seeded
   :context/UNASSIGNED sentinel — range-valid and firewall-inert."
  [ident slots]
  (sup/raw-transact!
    [(merge {:db/ident                      ident
             :dt/type                       :mm/Project
             :mm.memory/name                (name ident)
             :mm.project/ident              ident
             :mm.project/runs-in-context    :context/UNASSIGNED
             :mm.project/default-visibility :private
             :mm.project/corpus-repo        "memory"}
            slots)])
  (sup/eid-of ident))

(defn- seed-codebase!
  "Raw-seed a :mm/Codebase; returns the eid."
  [ident slots]
  (sup/raw-transact!
    [(merge {:db/ident       ident
             :dt/type        :mm/Codebase
             :mm.memory/name (name ident)}
            slots)])
  (sup/eid-of ident))

(defn- results-by-shape
  "shape/validate (:audit) results for `eid`, keyed by the shape's :db/ident."
  [eid]
  (let [db (db/db)]
    (into {}
          (map (fn [r] [(:db/ident (d/entity db (:shape r))) r]))
          (shape/validate db eid :audit))))

(defn- fails?  [by-shape shape-ident] (= :fail (:status (get by-shape shape-ident))))
(defn- passes? [by-shape shape-ident] (= :pass (:status (get by-shape shape-ident))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seed sanity — the 4 shape entities land from schema EDN with their targets
;; (3 charter shapes + the -codebase leg forced by card-one applies-to)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest shape-seeds-present-and-targeted
  (testing "the shape seeds resolve by charter ident with the ruled applies-to"
    (let [db (db/db)
          applies-to (fn [ident]
                       (let [v (:mm.shape/applies-to (d/entity db ident))]
                         (if (keyword? v) v (:db/ident v))))]
      (is (= :mm/Project  (applies-to :mm.shape/project-layout-safety)))
      (is (= :mm/Project  (applies-to :mm.shape/no-absolute-path)))
      (is (= :mm/Codebase (applies-to :mm.shape/no-absolute-path-codebase))
          "the Codebase leg of the charter shape (applies-to is card-one)")
      (is (= :mm/Project  (applies-to :mm.shape/repo-handle-url-safety))
          "RETARGETED: :mm/Project ONLY — code-ref-public? lives on Project")))
  (testing "the validator-fn refs deref as sub-ENTITIES (NOT ident keywords —
            an ident-bearing :mm/Fn ref would collapse to a keyword and
            check-validator-fn would fail every entity closed)"
    (let [db (db/db)]
      (doseq [shape-ident [:mm.shape/project-layout-safety
                           :mm.shape/repo-handle-url-safety]]
        (let [vfn (:mm.shape/validator-fn (d/entity db shape-ident))]
          (is (not (keyword? vfn)) (str shape-ident " validator-fn must not deref as a keyword"))
          (is (= "sandbar.shape.safety" (:dt.fn/source-ns vfn)))
          (is (some? (:dt.fn/source-var vfn))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-22 leg 1 — no-absolute-path (Project): absolute + .. traversal REFUSED
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest no-absolute-path-refuses-absolute-corpus-repo
  (testing "corpus-repo \"/Users/dan/x\" fails no-absolute-path (the T-22 probe)"
    (let [eid (seed-project*! :proj/t22-abs {:mm.project/corpus-repo "/Users/dan/x"})
          rs  (results-by-shape eid)]
      (is (fails? rs :mm.shape/no-absolute-path))
      (is (= :violation (-> (get rs :mm.shape/no-absolute-path)
                            :failures first :severity)))))
  (testing "~-anchored, drive-letter, and file:// forms fail"
    (doseq [[ident bad] [[:proj/t22-tilde "~/corpus"]
                         [:proj/t22-drive "C:\\corpus"]
                         [:proj/t22-fileurl "file:///Users/dan/x"]]]
      (let [eid (seed-project*! ident {:mm.project/corpus-repo bad})]
        (is (fails? (results-by-shape eid) :mm.shape/no-absolute-path)
            (str bad " must fail no-absolute-path"))))))

(deftest no-absolute-path-refuses-dot-dot-traversal
  (testing ".. traversal in corpus-repo fails no-absolute-path"
    (let [eid (seed-project*! :proj/t22-dotdot
                              {:mm.project/corpus-repo "memory/../../etc/secrets"})]
      (is (fails? (results-by-shape eid) :mm.shape/no-absolute-path))))
  (testing "an absolute push-allowlist member fails (card-many checked EACH)"
    (let [eid (seed-project*! :proj/t22-push
                              {:mm.project/push-allowlist
                               ["git@github.com:danlentz/claude.git" "/etc/exfil"]})]
      (is (fails? (results-by-shape eid) :mm.shape/no-absolute-path)))))

(deftest no-absolute-path-green-complement
  (testing "a relative corpus-repo + remote-URL push-allowlist pass"
    (let [eid (seed-project*! :proj/t22-clean
                              {:mm.project/corpus-repo "memory"
                               :mm.project/push-allowlist
                               ["https://github.com/danlentz/claude.git"
                                "git@github.com:danlentz/claude.git"]})]
      (is (passes? (results-by-shape eid) :mm.shape/no-absolute-path)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-22 leg 1b — no-absolute-path-codebase: the :mm/Codebase carriers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest no-absolute-path-codebase-leg
  (testing "an absolute repo-root fails the -codebase leg"
    (let [eid (seed-codebase! :codebase/t22-abs
                              {:mm.codebase/repo-root "/Users/dan/src/sandbar"})]
      (is (fails? (results-by-shape eid) :mm.shape/no-absolute-path-codebase))))
  (testing ".. traversal in canonical-reference fails"
    (let [eid (seed-codebase! :codebase/t22-dotdot
                              {:mm.codebase/canonical-reference "github.com/../evil"})]
      (is (fails? (results-by-shape eid) :mm.shape/no-absolute-path-codebase))))
  (testing "registry-resolvable relative forms pass"
    (let [eid (seed-codebase! :codebase/t22-clean
                              {:mm.codebase/repo-root "src/sandbar"
                               :mm.codebase/canonical-reference "github.com/danlentz/sandbar"})]
      (is (passes? (results-by-shape eid) :mm.shape/no-absolute-path-codebase)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-22 leg 2 — project-layout-safety: submodule-escape REFUSED (cross-slot)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest layout-safety-refuses-submodule-escape
  (testing "a :submodule layout whose corpus-repo is a REMOTE URL escapes the
            project tree — fails layout-safety while PASSING no-absolute-path
            (the discriminating case: the two shapes are distinct invariants)"
    (let [eid (seed-project*! :proj/t22-sub-remote
                              {:mm.project/corpus-layout :submodule
                               :mm.project/corpus-repo "https://github.com/other/corpus.git"})
          rs  (results-by-shape eid)]
      (is (fails?  rs :mm.shape/project-layout-safety))
      (is (passes? rs :mm.shape/no-absolute-path)
          "a remote URL is a legal corpus-repo per se — the ESCAPE is layout-relative")))
  (testing "a :submodule layout climbing out via ../ escapes (both shapes fire)"
    (let [eid (seed-project*! :proj/t22-sub-updir
                              {:mm.project/corpus-layout :submodule
                               :mm.project/corpus-repo "../sibling-corpus"})
          rs  (results-by-shape eid)]
      (is (fails? rs :mm.shape/project-layout-safety))
      (is (fails? rs :mm.shape/no-absolute-path))))
  (testing "a :subdir layout with an absolute corpus-repo escapes"
    (let [eid (seed-project*! :proj/t22-subdir-abs
                              {:mm.project/corpus-layout :subdir
                               :mm.project/corpus-repo "/Users/dan/elsewhere"})]
      (is (fails? (results-by-shape eid) :mm.shape/project-layout-safety)))))

(deftest layout-safety-green-complement
  (testing "a :submodule layout with an in-tree relative corpus-repo passes"
    (let [eid (seed-project*! :proj/t22-sub-clean
                              {:mm.project/corpus-layout :submodule
                               :mm.project/corpus-repo "memory"})]
      (is (passes? (results-by-shape eid) :mm.shape/project-layout-safety))))
  (testing "a NON-in-tree layout is not examined (a remote URL is fine)"
    (let [eid (seed-project*! :proj/t22-separate
                              {:mm.project/corpus-layout :separate-repo
                               :mm.project/corpus-repo "https://github.com/danlentz/claude.git"})
          rs  (results-by-shape eid)]
      (is (passes? rs :mm.shape/project-layout-safety))))
  (testing "an absent corpus-layout carries no in-tree obligation"
    (let [eid (seed-project*! :proj/t22-nolayout
                              {:mm.project/corpus-repo "https://github.com/danlentz/claude.git"})]
      (is (passes? (results-by-shape eid) :mm.shape/project-layout-safety)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; T-22 leg 3 — repo-handle-url-safety: the L-6 consent gate
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest url-safety-refuses-public-url-without-consent
  (testing "public project + populated URL + code-ref-public? ABSENT fails
            (absent => false, the ruling-6 read-time default)"
    (let [eid (seed-project*! :proj/t22-url-absent
                              {:mm.project/default-visibility :public
                               :mm.project/corpus-repo "https://github.com/danlentz/claude.git"})
          rs  (results-by-shape eid)]
      (is (fails? rs :mm.shape/repo-handle-url-safety))
      (is (= :violation (-> (get rs :mm.shape/repo-handle-url-safety)
                            :failures first :severity)))))
  (testing "an explicit stored false is refused the same"
    (let [eid (seed-project*! :proj/t22-url-false
                              {:mm.project/default-visibility :public
                               :mm.project/corpus-repo "https://github.com/danlentz/claude.git"
                               :mm.project/code-ref-public? false})]
      (is (fails? (results-by-shape eid) :mm.shape/repo-handle-url-safety)))))

(deftest url-safety-green-complement
  (testing "explicit code-ref-public? true consents — passes"
    (let [eid (seed-project*! :proj/t22-url-true
                              {:mm.project/default-visibility :public
                               :mm.project/corpus-repo "https://github.com/danlentz/claude.git"
                               :mm.project/code-ref-public? true})]
      (is (passes? (results-by-shape eid) :mm.shape/repo-handle-url-safety))))
  (testing "a PRIVATE project with a populated URL is out of scope — passes"
    (let [eid (seed-project*! :proj/t22-url-private
                              {:mm.project/default-visibility :private
                               :mm.project/corpus-repo "https://github.com/danlentz/claude.git"})]
      (is (passes? (results-by-shape eid) :mm.shape/repo-handle-url-safety))))
  (testing ":public-bottom firewall-class on a :private-default project is
            IRRELEVANT here — the shape reads default-visibility only (the
            CA-4 composed label is the firewall's concern, not this C3
            single-entity invariant's)"
    (let [eid (seed-project*! :proj/t22-url-pub-bottom
                              {:mm.project/default-visibility :private
                               :mm.project/firewall-class :public-bottom
                               :mm.project/corpus-repo "https://github.com/danlentz/claude.git"})]
      (is (passes? (results-by-shape eid) :mm.shape/repo-handle-url-safety))))
  (testing "the UNASSIGNED sentinel value counts as UNpopulated — passes"
    (let [eid (seed-project*! :proj/t22-url-unassigned
                              {:mm.project/default-visibility :public
                               :mm.project/corpus-repo "UNASSIGNED"})]
      (is (passes? (results-by-shape eid) :mm.shape/repo-handle-url-safety)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Walker integration — strict mode, delegate direct-dispatch, seed conformance
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest strict-mode-throws-and-clean-project-conforms
  (testing ":strict mode throws on a violating project"
    (let [eid (seed-project*! :proj/t22-strict {:mm.project/corpus-repo "/Users/dan/x"})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (shape/validate (db/db) eid :strict)))))
  (testing "a fully-clean project passes ALL 3 project shapes"
    (let [eid (seed-project*! :proj/t22-all-clean
                              {:mm.project/corpus-layout :submodule
                               :mm.project/corpus-repo "memory"})
          rs  (results-by-shape eid)]
      (is (= 3 (count rs)) "all 3 project shapes walked")
      (is (every? #(= :pass (:status %)) (vals rs)))))
  (testing "the seeded :project/UNASSIGNED sentinel conforms to all 3 shapes"
    (let [rs (results-by-shape (sup/eid-of :project/UNASSIGNED))]
      (is (every? #(= :pass (:status %)) (vals rs))
          "the schema-seeded sentinel must never violate its own class's shapes"))))

(deftest schema-reload-idempotence
  ;; initialize-db! retransacts schema on every boot; ident-less sub-entities
  ;; proliferate unless a stable upsert key unifies them (the RULE at the
  ;; mm-temporal.edn XOR seeds).  The :mm/Fn validator sub-entities carry NO
  ;; :db/ident (an ident would keyword-collapse the validator-fn deref) —
  ;; their :mm.memory/identity uuid must upsert instead.
  (testing "retransacting the seed batch neither proliferates the ident-less
            :mm/Fn sub-entities nor breaks the shapes' validator-fn refs"
    (let [count-fns #(or (d/q '[:find (count ?e) .
                                :where [?e :dt.fn/source-ns "sandbar.shape.safety"]]
                              (db/db))
                         0)
          before    (count-fns)]
      (tu/load-schema (db/conn) :mm-artifact)
      (is (= 2 before))
      (is (= 2 (count-fns)) "reload must upsert on :mm.memory/identity, not append")
      (let [vfn (:mm.shape/validator-fn (d/entity (db/db) :mm.shape/project-layout-safety))]
        (is (= "sandbar.shape.safety" (:dt.fn/source-ns vfn))
            "the shape still derefs to a live sub-entity after reload")))))

(deftest validator-delegates-dispatch-directly
  (testing "the classpath delegates answer the check-validator-fn contract"
    (let [eid (seed-project*! :proj/t22-direct
                              {:mm.project/corpus-layout :submodule
                               :mm.project/corpus-repo "../out-of-tree"})
          db  (db/db)
          layout-shape (:db/id (d/entity db :mm.shape/project-layout-safety))
          url-shape    (:db/id (d/entity db :mm.shape/repo-handle-url-safety))]
      (let [r (safety/check-project-layout-safety db eid layout-shape)]
        (is (= :fail (:status r)))
        (is (= :corpus-repo-escapes-project-tree (:reason r)))
        (is (= :violation (:severity r))))
      (is (= :pass (:status (safety/check-repo-handle-url-safety db eid url-shape)))
          "a :private-default project is out of url-safety scope"))))
