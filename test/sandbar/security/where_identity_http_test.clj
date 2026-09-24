(ns sandbar.security.where-identity-http-test
  "Caller-level regression proof for explicit identities in :where.
   Uses disposable Datomic and the authenticated Pedestal MCP chain, not TCP.
   The named S-2 controls describe remaining limits, not privacy successes."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [io.pedestal.test :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.firewall.support :as sup]
            [sandbar.search :as search]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth])
  (:import [java.util UUID]))

(def results (atom []))
(def wire-responses (atom []))
(def ^:dynamic *tokens* nil)
(def ^:dynamic *protected-eid* nil)
(def decision-id (UUID/fromString "10464ba8-c63e-4862-a011-d140883f2c98"))
(def private-id (UUID/fromString "13a8d95c-1383-4739-9bf5-4d2d4096dc73"))
(def p1 "memory.notes/p1")
(def s1 "memory.notes/s1")
(def p2 "memory.notes/p2")

(defn- token! [full?]
  (let [secret (str (UUID/randomUUID)) service (keyword (str "where-" (UUID/randomUUID)))
        entity (dt/make :auth/ServiceAccount
                 {:auth/service-name service :auth/api-key-hash (auth/hash-password secret)
                  :auth/roles [(tu/ensure-role! :read-only)]
                  :auth/active? true :auth/full-clearance? full?})]
    {:token (str (name service) ":" secret) :eid (:db/id entity)}))

(defn- seed! []
  (sup/seed-context! :ctx/pub :public-bottom)
  (sup/seed-project! :proj/pub :public :ctx/pub :public-bottom)
  (sup/seed-context! :ctx/priv :project-isolated)
  (sup/seed-project! :proj/priv :private :ctx/priv :project-isolated)
  (sup/raw-transact!
    [{:db/id (sup/eid-of :proj/pub) :db/ident :memory.projects/pub :mm.memory/visibility :public}
     {:db/id (sup/eid-of :proj/priv) :db/ident :memory.projects/priv :mm.memory/visibility :private}
     {:db/ident :tag/alpha :dt/type :mm/Tag :mm.tag/value "alpha"}
     {:dt/type :mm/Tag :mm.tag/value "plain"}])
  (doseq [[id visibility owner extra]
          [[:memory.actors/a1 :public :proj/pub {:dt/type :mm/AIActor}]
           [:memory.actors/a2 :private :proj/priv {:dt/type :mm/AIActor}]
           [:memory.decisions/d1 :public :proj/pub {:mm/id decision-id}]
           ;; A private Memory deliberately has an otherwise allowed namespace.
           [:mm.hidden/s2 :private :proj/priv {:mm/id private-id}]]]
    (sup/seed-memory! id visibility [:mm.project/ident owner]
      (merge {:mm.memory/name (str "needle " (name id))
              :mm.memory/memory-type :memory} extra)))
  (doseq [[id visibility owner extra]
          [[:memory.notes/p1 :public :proj/pub
            {:mm.memory/cites [:memory.decisions/d1]
             :mm.memory/created-by :memory.actors/a1
             :mm.memory/tags [:tag/alpha [:mm.tag/value "plain"]]}]
           [:memory.notes/s1 :private :proj/priv
            {:mm.memory/cites [:memory.decisions/d1]
             :mm.memory/created-by :memory.actors/a2}]
           [:memory.notes/p2 :public :proj/pub {:mm.memory/cites [:mm.hidden/s2]}]]]
    (sup/seed-memory! id visibility [:mm.project/ident owner]
      (merge {:mm.memory/name (str "needle " (name id))
              :mm.memory/memory-type :memory} extra))))

(defn- fixture [f]
  ((tu/make-test-db-fixture {:test-name (str "where-http-" (UUID/randomUUID)) :auth? false})
   (fn []
     (seed!)
     (let [operator (token! true) limited (token! false)]
       (binding [*tokens* {:operator (:token operator) :limited (:token limited)}
                 *protected-eid* (:eid limited)]
         (search/clear-bm25f-cache!)
         (f))))))
(use-fixtures :once fixture)

(def verbs [:count :group :bm25f])
(defn- request! [case-id caller verb where]
  (let [args (cond-> {:class ":mm/Memory" :where (pr-str where)}
               (= verb :group) (assoc :group-by ":mm.memory/memory-type")
               (= verb :bm25f) (assoc :query "needle" :limit 50 :projection "metadata-only"))
        request {:jsonrpc "2.0" :id (inc (count @wire-responses)) :method "tools/call"
                 :params {:name ({:count "sandbar_aggregate_count"
                                  :group "sandbar_aggregate_group-by"
                                  :bm25f "sandbar_search_bm25f"} verb)
                          :arguments args}}
        response (response-for tu/service :post "/mcp"
                   :headers (cond-> {"Content-Type" "application/json" "Accept" "application/json"}
                              (get *tokens* caller)
                              (assoc "Authorization" (str "Bearer " (get *tokens* caller))))
                   :body (json/generate-string request))
        envelope (json/parse-string (:body response) true)
        result (:result envelope)
        failed? (boolean (or (not= 200 (:status response)) (:error envelope)
                             (nil? result) (:isError result)))
        data (when-not failed?
               (or (:structuredContent result)
                   (json/parse-string (get-in result [:content 0 :text]) true)))
        receipt {:case case-id :caller caller :verb verb :request request
                 :http-status (:status response) :raw-response (:body response)
                 :envelope envelope :error? failed? :data data}]
    (swap! wire-responses conj receipt)
    receipt))

(defn- success! [case-id caller where aggregate-count hit-idents]
  (doseq [verb verbs]
    (let [r (request! case-id caller verb where)
          expected (case verb :count aggregate-count :group {:memory aggregate-count}
                         :bm25f (set hit-idents))
          actual (when-not (:error? r)
                   (case verb :count (get-in r [:data :count]) :group (get-in r [:data :groups])
                         :bm25f (set (map #(get-in % [:entity :db/ident])
                                          (get-in r [:data :hits])))))]
      (swap! results conj {:case case-id :caller caller :verb verb :expected expected
                           :actual actual :error? (:error? r)})
      (is (not (:error? r)) (str case-id " " caller " " verb " " (:raw-response r)))
      (when-not (:error? r)
        (is (= expected actual) (str case-id " " caller " " verb))
        (when (= verb :bm25f)
          (is (= (count hit-idents) (get-in r [:data :total]))))))))

(defn- refusals! [case-id caller where reason]
  (mapv
    (fn [verb]
      (let [r (request! case-id caller verb where)]
        (is (:error? r) (str case-id " " verb " must refuse"))
        (is (str/includes? (:raw-response r) (name reason)) (pr-str r))
        (swap! results conj {:case case-id :caller caller :verb verb :expected reason
                             :error? (:error? r)})
        (dissoc (:envelope r) :id))) verbs))

(deftest readable-ref-spellings-and-db-ident-literals
  (doseq [target [:memory.decisions/d1 (sup/eid-of :memory.decisions/d1)
                  [:mm/id decision-id]]]
    (success! [:citation target] :operator [['?e :mm.memory/cites target]] 2 [p1 s1]))
  (doseq [target [:memory.actors/a1 (sup/eid-of :memory.actors/a1)
                  [:db/ident :memory.actors/a1]]]
    (success! [:author target] :limited [['?e :mm.memory/created-by target]] 1 [p1]))
  (doseq [target [:memory.projects/pub (sup/eid-of :memory.projects/pub)
                  [:mm.project/ident :proj/pub]]]
    (success! [:owner target] :limited
      [['?e :mm.memory/owning-project target] ['?e :mm.memory/cites '_]] 2 [p1 p2]))
  (doseq [target [:tag/alpha (sup/eid-of :tag/alpha) [:mm.tag/value "alpha"]
                  [:mm.tag/value "plain"]]]
    (success! [:tag target] :limited [['?e :mm.memory/tags target]] 1 [p1]))
  (success! :tag-value-walk :limited
    '[[?e :mm.memory/tags ?tag] [?tag :mm.tag/value "plain"]] 1 [p1])
  (success! :db-ident-join :operator
    '[[?e :mm.memory/cites ?d] [?d :db/ident :memory.decisions/d1]] 2 [p1 s1])
  (doseq [target [:memory.decisions/d1 (sup/eid-of :memory.decisions/d1)]]
    (success! [:entity-position target] :limited
      [[target :mm.memory/name '?n] ['?e :mm.memory/name '?n]] 1 ["memory.decisions/d1"]))
  ;; A vector leading a literal data pattern is parsed as a binding form by
  ;; the existing query engine. Authorization does not rewrite that syntax.
  (doseq [verb verbs]
    (let [r (request! :entity-lookup-language-limit :limited verb
              [[[:mm/id decision-id] :mm.memory/name '?n] ['?e :mm.memory/name '?n]])]
      (is (:error? r))
      (is (str/includes? (:raw-response r) "not-a-binding-form")))))

(deftest hidden-missing-and-protected-instances-are-indistinguishable
  (let [responses (for [target [:mm.hidden/s2 (sup/eid-of :mm.hidden/s2)
                                [:mm/id private-id] :memory.notes/s1
                                :memory.notes/nonexistent Long/MAX_VALUE *protected-eid*]]
                    (refusals! [:unavailable target] :limited
                      [['?e :mm.memory/cites target]] :filter-identity-unavailable))]
    (is (apply = (doall responses)) "Same error payload across target forms and classes"))
  (success! :private-target-full-clearance :operator
    '[[?e :mm.memory/cites :mm.hidden/s2]] 1 [p2]))

(deftest existing-guards-and-scalar-behavior
  (doseq [[case-id where]
          [[:protected-attribute '[[?e :auth/api-key-hash ?v]]]
           [:protected-definition '[[?e :dt/type :auth/ServiceAccount]]]
           [:protected-lookup-key '[[?e :mm.memory/cites [:auth/service-name :made-up]]]]
           [:scalar-corpus-keyword '[[?e :mm.memory/visibility :memory.notes/p1]]]
           [:predicate-corpus-keyword '[[?e :mm.memory/cites ?x] [(= ?x :memory.decisions/d1)]]]]]
    (refusals! case-id :operator where :namespace-not-read-plane-allowed))
  ;; An integer in a predicate is not reclassified as an identity just because
  ;; its value happens to equal a private entity's eid.
  (success! :numeric-scalar-predicate :limited
    [['?e :mm.memory/name "needle p1"]
     [(list '= (sup/eid-of :memory.notes/s1) (sup/eid-of :memory.notes/s1))]] 1 [p1])
  (success! :bare-enum :limited
    '[[?e :mm.memory/visibility :public] [?e :mm.memory/name "needle p1"]] 1 [p1])
  (doseq [verb verbs]
    (let [r (request! :unauthenticated nil verb '[[?e :mm.memory/memory-type :memory]])]
      (is (:error? r))
      (is (= 401 (:http-status r))))))

(deftest s2-limitations-remain-explicit
  ;; Count and group-by still include the private source record s1. BM25F
  ;; authorizes its result entities and returns only p1 to this caller.
  (success! :s2-source-population :limited
    '[[?e :mm.memory/cites :memory.decisions/d1]] 2 [p1])
  ;; Existing variable joins can test scalar values of a hidden referent.
  ;; This test documents that limitation; it is NOT an isolation assertion.
  (success! :s2-variable-join :limited
    '[[?e :mm.memory/cites ?target] [?target :mm.memory/name "needle s2"]] 1 [p2]))
