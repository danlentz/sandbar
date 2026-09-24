(ns sandbar.security.where-identity-test
  "Readable identity values in :where filters (task
   repair_readable_entity_values_in_query_filters_2026_09_21; cases WV-01…WV-22
   of where-value-contract/opus-cases.edn with the lead corrections).  In
   process, isolated store: the guard `dt/assert-where-identities-allowed!` is
   exercised exactly as the three wrappers call it, and the populations are
   read through `aggregate/count-by`, `aggregate/group-by` and
   `search/search-bm25f`.  Principals are maps: the operator has full
   clearance; the limited reader reads public entities only."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datomic.api :as d]
            [sandbar.aggregate :as aggregate]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.search :as search]
            [sandbar.security.query :as secq]
            [sandbar.security.visibility :as visibility]
            [sandbar.test-util :as tu])
  (:import [java.util UUID]))

(def operator {:auth/full-clearance? true :auth/roles [{:auth/role-name :read-write}]})
(def limited  {:auth/full-clearance? false :auth/roles [{:auth/role-name :read-only}]})
(def uuid-d1 (UUID/randomUUID))
(def uuid-s2 (UUID/randomUUID))

(defn- memory! [ident visibility project extra]
  (sup/seed-memory! ident visibility project
    (merge {:mm.memory/rel-path (str "notes/" (name ident) ".md")
            :mm.memory/body-raw (str "Body of " (name ident) ".\n")} extra)))

(defn- seed! []
  ;; The projects are re-identified below, so every owner reference goes
  ;; through the stable :mm.project/ident lookup ref (as export_test does).
  (let [pub [:mm.project/ident :proj/pub] priv [:mm.project/ident :proj/priv]]
    (sup/seed-context! :ctx/pub :public-bottom)
    (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
    (sup/seed-context! :ctx/priv :project-isolated)
    (sup/seed-project! :proj/priv :private :ctx/priv :project-isolated)
    (sup/raw-transact! [{:db/id (sup/eid-of :proj/pub) :db/ident :memory.projects/pub :mm.memory/visibility :public}
                       {:db/id (sup/eid-of :proj/priv) :db/ident :memory.projects/priv :mm.memory/visibility :private}])
    ;; actors are Memory descendants (:mm/Actor ⊑ :mm/Meta ⊑ :mm/Memory): compartmented
    (sup/raw-transact! [{:db/ident :memory.actors/a1 :dt/type :mm/AIActor :mm.memory/name "a1"
                         :mm.memory/visibility :public :mm.memory/owning-project pub}
                        {:db/ident :memory.actors/a2 :dt/type :mm/AIActor :mm.memory/name "a2"
                         :mm.memory/visibility :private :mm.memory/owning-project priv}])
    ;; tags are not compartmented; one carries an ident, one is identified by its value alone
    (sup/raw-transact! [{:db/ident :tag/alpha :dt/type :mm/Tag :mm.tag/value "alpha"}
                        {:dt/type :mm/Tag :mm.tag/value "plain"}])
    ;; an identless instance of a firewalled class, as a value nobody may name
    (sup/raw-transact! [{:dt/type :auth/ServiceAccount :auth/service-name :where-identity-probe
                         :auth/api-key-hash "not-a-real-hash" :auth/active? true}])
    (memory! :memory.decisions/d1 :public pub {:mm/id uuid-d1})
    (memory! :memory.private/s2 :private priv {:mm/id uuid-s2 :mm.memory/name "Secret plan"})
    ;; the refusal control of lead correction 2: a PRIVATE memory under an mm.* ident
    (memory! :mm.probe/hidden :private priv {})
    ;; tags by eid: a two-element vector holding an ident and a lookup ref
    ;; would be ambiguous with a lookup ref in a transaction map
    (memory! :memory.notes/p1 :public pub
             {:mm.memory/created-by [:memory.actors/a1] :mm.memory/cites [:memory.decisions/d1]
              :mm.memory/tags [(sup/eid-of :tag/alpha) (:db/id (d/entity (db/db) [:mm.tag/value "plain"]))]})
    (memory! :memory.notes/s1 :private priv
             {:mm.memory/created-by [:memory.actors/a2] :mm.memory/cites [:memory.decisions/d1]})
    (memory! :memory.notes/p2 :public pub {:mm.memory/cites [:memory.private/s2]})
    (memory! :memory.notes/p3 :public pub {:mm.memory/cites [:mm.probe/hidden]})))

(use-fixtures :each (fn [f] ((tu/make-test-db-fixture {:test-name "where-identity" :auth? false})
                             (fn [] (seed!) (f)))))

(defn- readable? [principal] #(visibility/entity-visible-to? principal %))
(defn- guard [where principal]
  (binding [visibility/*principal* principal]
    (dt/assert-where-identities-allowed! where (readable? principal))))
(defn- count! [where principal]
  (binding [visibility/*principal* principal]
    (:count (aggregate/count-by {:class :mm/Memory :where where}))))
(defn- refusal [thunk]
  (try (thunk) nil (catch clojure.lang.ExceptionInfo ex (ex-data ex))))
(defn- reason [thunk] (:reason (refusal thunk)))
(defn- eid [ref] (:db/id (d/entity (db/db) ref)))
(def unavailable :filter-identity-unavailable)
(def namespace-refusal :namespace-not-read-plane-allowed)

;;; ── WV-01 … WV-06: the four ordinary cases, three spellings ─────────────────

(deftest readable-identity-constants-are-accepted-in-every-spelling
  (let [d1 (eid :memory.decisions/d1) a1 (eid :memory.actors/a1) pub (eid :memory.projects/pub)
        alpha (eid :tag/alpha) plain (eid [:mm.tag/value "plain"])]
    (testing "citation target (WV-01/02/03)"
      (doseq [v [:memory.decisions/d1 d1 [:mm/id uuid-d1]]]
        (is (= 2 (count! [['?e :mm.memory/cites v]] operator)) (pr-str v))))
    (testing "author (WV-04)"
      (doseq [v [:memory.actors/a1 a1]]
        (is (= 1 (count! [['?e :mm.memory/created-by v]] operator)) (pr-str v))))
    (testing "owner (WV-05), including the lookup ref whose value keyword is in the 'proj' namespace"
      ;; owned by pub: d1, the actor a1 (an Actor is a Memory), p1, p2, p3
      (doseq [v [:memory.projects/pub pub [:mm.project/ident :proj/pub]]]
        (is (= 5 (count! [['?e :mm.memory/owning-project v]] operator)) (pr-str v))))
    (testing "tag (WV-06): ident, eid, lookup ref and the value join agree; an identless tag has two spellings"
      (doseq [v [:tag/alpha alpha [:mm.tag/value "alpha"]]]
        (is (= 1 (count! [['?e :mm.memory/tags v]] limited)) (pr-str v)))
      (is (= 1 (count! '[[?e :mm.memory/tags ?t] [?t :mm.tag/value "alpha"]] limited)))
      (doseq [v [plain [:mm.tag/value "plain"]]]
        (is (= 1 (count! [['?e :mm.memory/tags v]] limited)) (pr-str v))))
    (testing "the guard returns the clauses unchanged"
      (let [w [['?e :mm.memory/cites :memory.decisions/d1]]]
        (is (= w (guard w operator)))))))

;;; ── WV-07 / WV-08 / WV-11 and the mm.* control: one non-echoing refusal ─────

(deftest absent-hidden-and-firewalled-instances-get-one-identical-refusal
  (let [s2 (eid :memory.private/s2)
        probe (ffirst (d/q '[:find ?e :where [?e :auth/service-name :where-identity-probe]] (db/db)))
        values {:hidden-keyword :memory.private/s2 :hidden-eid s2 :hidden-lookup [:mm/id uuid-s2]
                :hidden-mm-ident :mm.probe/hidden
                :absent-keyword :memory.decisions/never :absent-eid 17592186999999
                :absent-lookup [:mm/id (UUID/randomUUID)] :bare-keyword :nothing-here
                :firewalled-instance-eid probe}
        datas (into {} (map (fn [[k v]] [k (refusal #(guard [['?e :mm.memory/cites v]] limited))]) values))]
    (doseq [[k data] datas]
      (is (= unavailable (:reason data)) (str k " " (pr-str data))))
    (testing "reason, message-bearing details and kind are identical across every case (no oracle)"
      (is (= 1 (count (set (vals datas)))) (pr-str datas))
      (is (not-any? (fn [[_ data]] (some #(contains? data %) [:offending :context :rejected-clause :where])) datas)))
    (testing "the messages are identical too"
      (is (= 1 (count (set (map (fn [[_ v]] (try (guard [['?e :mm.memory/cites v]] limited) nil
                                                 (catch clojure.lang.ExceptionInfo ex (.getMessage ex))))
                                values))))))
    (testing "the mm.* control: an allowed-namespace spelling never bypasses readability"
      (is (= unavailable (reason #(count! '[[?e :mm.memory/cites :mm.probe/hidden]] limited)))))
    (testing "hidden is relative to the principal (WV-09)"
      (is (= 1 (count! '[[?e :mm.memory/cites :memory.private/s2]] operator)))
      (is (= 1 (count! '[[?e :mm.memory/cites :mm.probe/hidden]] operator))))))

;;; ── WV-10 / WV-12 / WV-13 / WV-17 / WV-18: everything else is unchanged ─────

(deftest definitions-attributes-scalars-and-predicates-keep-the-older-checks
  (testing "protected definitions keep the namespace refusal that names them (WV-10)"
    (is (= namespace-refusal (reason #(guard '[[?x :dt/type :auth/User]] operator))))
    (is (= namespace-refusal (reason #(guard '[[?e :mm.memory/cites :auth/ServiceAccount]] operator)))
        "a firewalled definition as a value keeps the namespace answer")
    (is (= unavailable (reason #(guard '[[?e :mm.memory/cites :auth/nothing-of-that-name]] operator)))
        "an absent spelling is absent whatever its namespace: no definition is revealed")
    (let [r (refusal #(guard [['?e :dt/type (eid :auth/ServiceAccount)]] operator))]
      (is (= namespace-refusal (:reason r)))
      (is (= :auth/ServiceAccount (:offending r)) "a definition's ident is echoed, as today")))
  (testing "allowed definitions and enums are accepted without resolution changing anything"
    (is (pos? (count! '[[?e :dt/type :mm/Memory]] limited)))
    (binding [visibility/*principal* operator]
      (is (pos? (:count (aggregate/count-by {:class :dt/Property :where '[[?e :db/cardinality :db.cardinality/many]]}))))))
  (testing "attribute position is never an identity (WV-12)"
    (doseq [w ['[[?e :memory.actors/a1 ?v]] '[[?e :tag/alpha ?v]] '[[?e :auth/roles ?r]]]]
      (is (= namespace-refusal (reason #(guard w operator))) (pr-str w))))
  (testing "scalar values are unchanged (WV-13)"
    ;; private: the priv project, the actor a2, s2, the mm.* probe and s1
    (is (= 5 (count! '[[?e :mm.memory/visibility :private]] limited)) "S-2: the count includes what the reader cannot read")
    (is (= namespace-refusal (reason #(guard '[[?e :mm.memory/memory-type :memory.x/y]] operator)))))
  (testing "predicate arguments keep the older keyword and numeric checks (WV-17)"
    (is (some? (guard '[[(missing? $ ?e :mm.memory/owning-project)]] operator)))
    (is (= namespace-refusal (reason #(guard '[[(missing? $ ?e :auth/x)]] operator))))
    (is (= namespace-refusal (reason #(guard '[[(get-else $ ?e :mm.memory/owning-project :memory.projects/none)]] operator))))
    (is (= namespace-refusal (reason #(guard '[[?e :mm.memory/owning-project ?p] [(= ?p :memory.projects/pub)]] operator))))
    (is (= 5 (count! [['?e :mm.memory/owning-project '?p] [(list '= '?p (eid :memory.projects/pub))]] operator))
        "the numeric predicate form keeps its existing success; it is not a parity claim"))
  (testing "a value under a variable attribute is a scalar (WV-18)"
    (is (= namespace-refusal (reason #(guard '[[?e ?a :memory.decisions/d1]] operator))))
    (is (some? (guard [['?e '?a (eid :memory.decisions/d1)]] operator))))
  (testing "the exported guards of security/query.clj are unchanged"
    (is (= namespace-refusal (reason #(secq/assert-where-namespaces! '[[?x :auth/api-key-hash ?h]]))))
    (is (= namespace-refusal (reason #(secq/assert-where-namespaces! '[[?e :mm.memory/cites :memory.decisions/d1]]))))))

;;; ── WV-16 / WV-19: :db/ident values and entity-position constants ───────────

(deftest db-ident-keyword-values-and-entity-anchors-are-identities
  (testing ":db/ident keyword value is judged by its target and keeps its literal meaning (WV-16)"
    (is (= 2 (count! '[[?e :mm.memory/cites ?t] [?t :db/ident :memory.decisions/d1]] operator)))
    (is (= unavailable (reason #(guard '[[?e :mm.memory/cites ?t] [?t :db/ident :memory.private/s2]] limited))))
    (let [w [['?e :mm.memory/cites '?t] ['?t :db/ident (eid :memory.decisions/d1)]]]
      ;; An integer under :db/ident is not an identity: the guard leaves the
      ;; clauses unchanged and the older checks admit the integer (its ident is
      ;; a memorial's), exactly as before this increment.  The engine's own type
      ;; rule then rejects a non-keyword constant against a keyword attribute —
      ;; preserved, not normalized: the same error through the old guards and
      ;; the splice site as through the wrapper.
      (is (= w (guard w operator)))
      (is (= w (dt/assert-where-eids-allowed! (secq/assert-where-namespaces! w))))
      (is (thrown-with-msg? Exception #"not-a-keyword" (count! w operator)))
      (is (thrown-with-msg? Exception #"not-a-keyword" (dt/count-of :mm/Memory w)))))
  (testing "a constant in entity position must be readable (WV-19)"
    (is (= 1 (count! '[[:memory.decisions/d1 :mm.memory/name ?n] [?e :mm.memory/name ?n]] limited)))
    (is (= 1 (count! [[(eid :memory.decisions/d1) :mm.memory/name '?n] ['?e :mm.memory/name '?n]] limited)))
    (is (= unavailable (reason #(guard '[[:memory.private/s2 :mm.memory/name ?n] [?e :mm.memory/name ?n]] limited))))
    (is (= unavailable (reason #(guard [[(eid :memory.private/s2) :mm.memory/name '?n] ['?e :mm.memory/name '?n]] limited))))
    (is (some? (guard '[[$ ?e :mm.memory/cites :memory.decisions/d1]] operator)) "an explicit source variable shifts the positions")))

;;; ── WV-14 / WV-15: the recorded S-2 limitations, and WV-20 ──────────────────

(deftest recorded-limitations-hold-as-stated
  (testing "WV-15: count populations are not clearance-filtered — the selector is readable, the count is S-2"
    (is (= 2 (count! '[[?e :mm.memory/cites :memory.decisions/d1]] limited)))
    (is (= (count! '[[?e :mm.memory/cites :memory.decisions/d1]] limited)
           (count! '[[?e :mm.memory/cites :memory.decisions/d1]] operator))))
  (testing "WV-14: a variable join still tests a hidden referent's attributes (not closed by this increment)"
    (is (= 1 (count! '[[?e :mm.memory/cites ?t] [?t :mm.memory/name "Secret plan"]] limited))))
  (testing "WV-20: nested logic is still refused by the sanitizer at the splice"
    (is (= :nested-logic-unsupported-v1
           (reason #(count! [(list 'or ['?e :mm.memory/cites (eid :memory.decisions/d1)])] operator))))))

;;; ── the three wrappers ───────────────────────────────────────────────────────

(deftest group-by-and-bm25f-wrappers-accept-readable-identities-and-refuse-hidden-ones
  (testing "group-by"
    (binding [visibility/*principal* operator]
      (is (= {:public 1 :private 1}
             (:groups (aggregate/group-by {:class :mm/Memory :group-by :mm.memory/visibility
                                           :where '[[?e :mm.memory/cites :memory.decisions/d1]]})))))
    (binding [visibility/*principal* limited]
      (is (= unavailable (reason #(aggregate/group-by {:class :mm/Memory :group-by :mm.memory/visibility
                                                       :where '[[?e :mm.memory/cites :memory.private/s2]]}))))))
  (testing "bm25f: the selector is readable and the hits are still authorized per principal"
    (binding [visibility/*principal* limited]
      (let [r (search/search-bm25f {:class :mm/Memory :query "body" :limit 0
                                    :where '[[?e :mm.memory/cites :memory.decisions/d1]]})]
        (is (= #{:memory.notes/p1} (set (map #(:db/ident (:entity %)) (:hits r)))) (pr-str (:total r))))
      (is (= unavailable (reason #(search/search-bm25f {:class :mm/Memory :query "body"
                                                        :where '[[?e :mm.memory/cites :memory.private/s2]]})))))
    (binding [visibility/*principal* operator]
      (let [r (search/search-bm25f {:class :mm/Memory :query "body" :limit 0
                                    :where '[[?e :mm.memory/cites :memory.decisions/d1]]})]
        (is (= #{:memory.notes/p1 :memory.notes/s1} (set (map #(:db/ident (:entity %)) (:hits r)))))))))
