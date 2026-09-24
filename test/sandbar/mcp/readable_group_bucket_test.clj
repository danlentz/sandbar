(ns sandbar.mcp.readable-group-bucket-test
  "Isolated MCP request/response contracts for readable identity buckets.
   These tests do not claim compartment filtering of the source population."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth]))

(def responses (atom []))
(def seeded-identities (atom {}))
(def ^:dynamic *tokens* nil)
(def targets [:project/bucket-public :context/bucket-public
              :tag/bucket-public :project/bucket-private])

(defn- account! [svc full?]
  (let [secret (str (java.util.UUID/randomUUID))]
    (dt/make :auth/ServiceAccount
      {:auth/service-name svc :auth/api-key-hash (auth/hash-password secret)
       :auth/roles [(tu/ensure-role! :read-only)]
       :auth/active? true :auth/full-clearance? full?})
    (str (name svc) ":" secret)))

(defn- seed! []
  @(d/transact (db/conn)
     [{:db/ident :mm.bucket/number :db/valueType :db.type/long
       :db/cardinality :db.cardinality/one :dt/type :dt/Property
       :dt/domain :mm/Memory :dt/range :db.type/long
       :mm.memory/name "BucketProperty"}
      {:db/ident :mm.bucket/enum-one :db/valueType :db.type/keyword
       :db/cardinality :db.cardinality/one :db/unique :db.unique/identity
       :dt/type :dt/Property :dt/domain :mm/Memory :dt/range :db.type/keyword
       :mm.memory/name "BucketProperty"}
      {:db/ident :mm.bucket/enum-many :db/valueType :db.type/long
       :db/cardinality :db.cardinality/many :dt/type :dt/Property
       :dt/domain :mm/Memory :dt/range :db.type/long
       :mm.memory/name "BucketProperty"}])
  @(d/transact (db/conn)
     (concat
       (map (fn [ident cls visibility]
              {:db/ident ident :dt/type cls :mm.memory/name "BucketTarget"
               :mm.memory/visibility visibility})
            targets [:mm/Project :mm/Context :mm/Tag :mm/Project]
            [:public :public :public :private])
       [{:db/ident :auth.accounts/bucket-canary :dt/type :auth/ServiceAccount
         :auth/api-key-hash "BucketCredentialCanary"}
        {:db/ident :memory.bucket/disguised-auth :dt/type :auth/ServiceAccount}
        {:db/ident :foreign.bucket/definition :dt/type :dt/Fn}
        {:db/ident :memory.bucket/private-target :dt/type :mm/Memory
         :mm.memory/name "BucketPrivateTarget" :mm.memory/visibility :private}
        {:db/ident :sandbar.system/bucket-job :dt/type :mm/Job
         :mm.memory/name "BucketJob" :mm.memory/visibility :public}
        {:db/id "process" :dt/type :workflow/Process}
        {:db/ident :memory.bucket/session :dt/type :mm/Session
         :mm.memory/name "BucketSession" :mm.memory/visibility :public
         :mm.session/workflow-process "process"}
        {:db/id "untyped" :mm.memory/name "UntypedBucketTarget"}
        {:db/ident :memory.bucket/untyped-host :dt/type :mm/Memory
         :mm.memory/name "BucketUntypedHost" :mm.memory/cites ["untyped"]}]))
  @(d/transact (db/conn)
     (concat
       (map-indexed
         (fn [i target]
           {:db/ident (keyword "memory.bucket" (str "host-" i))
            :dt/type :mm/Memory :mm.memory/name "BucketRefHost"
            :mm.memory/visibility :public :mm.memory/cites [target]})
         (conj targets :project/bucket-public))
       [{:db/ident :memory.bucket/protected-host :dt/type :mm/Memory
         :mm.memory/name "BucketProtectedHost" :mm.memory/visibility :public
         :mm.memory/cites [:auth/ServiceAccount :auth/api-key-hash
                           :auth.accounts/bucket-canary]}
        {:db/ident :memory.bucket/unknown :dt/type :mm/Memory
         :mm.memory/name "BucketUnknown" :mm.memory/visibility :public
         :mm.memory/scope :foreign.bucket/unknown}
        {:db/ident :memory.bucket/number :dt/type :mm/Memory
         :mm.memory/name "BucketNumber" :mm.memory/visibility :public
         :mm.bucket/number (:db/id (db/entity :auth/ServiceAccount))}
        {:db/ident :memory.bucket/definition-host :dt/type :mm/Memory
         :mm.memory/name "BucketDefinitionHost" :mm.memory/visibility :public
         :mm.memory/cites [:mm/Memory :auth/ServiceAccount :foreign.bucket/definition]}
        {:db/ident :memory.bucket/disguise-host :dt/type :mm/Memory
         :mm.memory/name "BucketDisguiseHost" :mm.memory/visibility :public
         :mm.memory/cites [:memory.bucket/disguised-auth]}
        {:db/ident :memory.bucket/private-host :dt/type :mm/Memory
         :mm.memory/name "BucketPrivateHost" :mm.memory/visibility :public
         :mm.memory/cites [:memory.bucket/private-target]}
        {:db/ident :memory.bucket/bare-keyword :dt/type :mm/Memory
         :mm.memory/name "BucketBareKeyword" :mm.memory/visibility :public
         :mm.memory/scope :project}
        {:db/ident :memory.bucket/run :dt/type :mm/Run
         :mm.memory/name "BucketRun" :mm.memory/visibility :public
         :mm.activity/spec :sandbar.system/bucket-job
         :mm.run/status :run.status/completed}])))

(defn- fixture [f]
  (reset! responses [])
  (seed!)
  (reset! seeded-identities
    (into {} (for [ident (concat targets [:mm/Memory :auth/ServiceAccount
                                         :memory.bucket/private-target :sandbar.system/bucket-job
                                         :db.cardinality/one :db.cardinality/many
                                         :db.type/long :db.type/keyword :db.unique/identity])]
               [ident (:db/id (db/entity ident))])))
  (binding [*tokens* {:full (account! :bucket-full true)
                     :restricted (account! :bucket-restricted false)}]
    (f)))

(use-fixtures :once
  (tu/make-test-db-fixture {:test-name "readable-group-bucket"
                            :auth? false :extra-schema [:auth :event]})
  fixture)

(defn- request [who verb args]
  (let [resp (response-for tu/service :post "/mcp"
               :headers {"Content-Type" "application/json"
                         "Accept" "application/json"
                         "Authorization" (str "Bearer " (get *tokens* who))}
               :body (json/generate-string
                       {:jsonrpc "2.0" :id (inc (count @responses)) :method "tools/call"
                        :params {:name verb :arguments args}}))
        envelope (json/parse-string (:body resp) true)
        result (:result envelope)
        payload (some-> result :content first :text (json/parse-string true))]
    (swap! responses conj {:principal who :verb verb :arguments args
                           :http-status (:status resp) :envelope envelope})
    {:status (:status resp) :envelope envelope :result result :payload payload}))

(defn- success [who verb args]
  (let [{:keys [status envelope result payload]} (request who verb args)]
    (is (= 200 status))
    (is (nil? (:error envelope)))
    (is (not (:isError result)) (pr-str result))
    (is (some? payload))
    payload))

(defn- groups [who class slot population]
  (let [payload (success who "sandbar_aggregate_group-by"
                  {:class (str class) :group-by (str slot)
                   :where (pr-str [['?e :mm.memory/name population]])})
        counts (:groups payload)]
    (is (= (:total payload) (reduce + 0 (vals counts))))
    payload))

(defn- wire-ident [k] (keyword (subs (str k) 1)))
(defn- wire-eid [k] (keyword (str (:db/id (db/entity k)))))

(deftest readable-ident-and-reference-buckets
  (testing "keyword buckets agree with full reads of ordinary corpus records"
    (doseq [target targets]
      (is (= (subs (str target) 1)
             (get-in (success :full "sandbar_entity_find"
                       {:ident (str target) :projection "full"}) [:entity :db/ident]))))
    (let [p (groups :full :dt/Resource :db/ident "BucketTarget")]
      (is (= (zipmap (map wire-ident targets) (repeat 1)) (:groups p)))
      (is (= 4 (:total p)))))
  (testing "eid/ref buckets retain the same identities and duplicate-source count"
    (let [p (groups :full :mm/Memory :mm.memory/cites "BucketRefHost")]
      (is (= (assoc (zipmap (map wire-eid targets) (repeat 1))
                    (wire-eid :project/bucket-public) 2) (:groups p)))
      (is (= 5 (:total p))))))

(deftest newly-admitted-targets-still-require-clearance
  (let [visible (butlast targets)
        found (success :restricted "sandbar_entity_find"
                {:ident ":project/bucket-private" :projection "full"})]
    (is (:missing? found))
    (is (nil? (:entity found)))
    (let [p (groups :restricted :dt/Resource :db/ident "BucketTarget")]
      (is (= (zipmap (map wire-ident visible) (repeat 1)) (:groups p)))
      (is (= 3 (:total p))))
    (let [p (groups :restricted :mm/Memory :mm.memory/cites "BucketRefHost")]
      (is (= (assoc (zipmap (map wire-eid visible) (repeat 1))
                    (wire-eid :project/bucket-public) 2) (:groups p)))
      (is (= 4 (:total p))))))

(deftest protected-and-unresolved-buckets-stay-hidden
  (testing "auth schema definitions and auth data instances cannot become ref buckets"
    (let [p (groups :full :mm/Memory :mm.memory/cites "BucketProtectedHost")]
      (is (= {} (:groups p)))
      (is (zero? (:total p)))))
  (testing "unknown keywords fail closed in identity context"
    (is (true? (dt/read-plane-group-key-firewalled?
                 :foreign.bucket/unknown))))
  (testing "protected class and attribute selectors remain errors"
    (doseq [args [{:class ":auth/ServiceAccount" :group-by ":auth/api-key-hash"}
                 {:class ":mm/Memory" :group-by ":auth/api-key-hash"}]]
      (let [{:keys [envelope result]} (request :full "sandbar_aggregate_group-by" args)]
        (is (or (:error envelope) (:isError result))))))
  (testing "the separate named-reference where refusal is unchanged"
    (let [{:keys [envelope result]} (request :full "sandbar_aggregate_count"
                                    {:class ":mm/Memory"
                                     :where "[[?e :mm.memory/cites :memory.bucket/unknown]]"})]
      (is (or (:error envelope) (:isError result))))))

(deftest scalar-buckets-are-not-accidental-entity-references
  (let [p (groups :full :mm/Memory :mm.bucket/number "BucketNumber")]
    (is (= {(wire-eid :auth/ServiceAccount) 1} (:groups p)))
    (is (= 1 (:total p))))
  (let [p (groups :full :mm/Memory :mm.memory/name "BucketRefHost")]
    (is (= {:BucketRefHost 5} (:groups p)))
    (is (= 5 (:total p))))
  (doseq [[population expected] [["BucketUnknown" {:foreign.bucket/unknown 1}]
                                ["BucketBareKeyword" {:project 1}]]]
    (let [p (groups :full :mm/Memory :mm.memory/scope population)]
      (is (= expected (:groups p)))
      (is (= 1 (:total p))))))

(deftest identity-keys-use-actual-type-and-clearance
  (testing "identless runtime instances and allowlisted-looking auth idents stay hidden"
    (doseq [[class slot population] [[:mm/Session :mm.session/workflow-process "BucketSession"]
                                   [:mm/Memory :mm.memory/cites "BucketDisguiseHost"]
                                   [:mm/Memory :mm.memory/cites "BucketUntypedHost"]]]
      (let [p (groups :full class slot population)]
        (is (= {} (:groups p)))
        (is (zero? (:total p))))))
  (testing "allowed metamodel buckets survive; protected and foreign definitions do not"
    (let [p (groups :full :mm/Memory :mm.memory/cites "BucketDefinitionHost")]
      (is (= {(wire-eid :mm/Memory) 1} (:groups p)))
      (is (= 1 (:total p))))
    (is (false? (dt/read-plane-group-key-firewalled? (:db/id (db/entity :mm/Memory)))))
    (is (true? (dt/read-plane-group-key-firewalled? (:db/id (db/entity :auth/ServiceAccount))))))
  (testing "allowlisted memorial idents do not bypass target clearance"
    (doseq [[who expected] [[:full {(wire-eid :memory.bucket/private-target) 1}]
                           [:restricted {}]]]
      (let [p (groups who :mm/Memory :mm.memory/cites "BucketPrivateHost")]
        (is (= expected (:groups p))))))
  (testing "activity/spec is a ref to a readable job, not a scalar keyword"
    (is (= :db.type/ref (:db/valueType (db/entity :mm.activity/spec))))
    (let [p (groups :full :mm/Run :mm.activity/spec "BucketRun")]
      (is (= {(wire-eid :sandbar.system/bucket-job) 1} (:groups p)))
      (is (= 1 (:total p))))))

(deftest metamodel-enum-buckets-retain-the-existing-namespace-boundary
  (testing "Datomic enum references and datatype definitions remain useful census values"
    (doseq [[slot expected] [[:db/cardinality {(wire-eid :db.cardinality/one) 2
                                             (wire-eid :db.cardinality/many) 1}]
                            [:db/valueType {(wire-eid :db.type/long) 2
                                           (wire-eid :db.type/keyword) 1}]
                            [:db/unique {(wire-eid :db.unique/identity) 1}]]]
      (let [p (groups :full :dt/Property slot "BucketProperty")]
        (is (= expected (:groups p)))
        (is (= (reduce + 0 (vals expected)) (:total p))))))
  (testing "foreign untyped vocabulary refs remain a named limitation; no broad bypass"
    (let [p (groups :full :mm/Run :mm.run/status "BucketRun")]
      (is (= {} (:groups p)))
      (is (zero? (:total p))))))
