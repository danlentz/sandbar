(ns sandbar.section-visibility-test
  "Document-owned sections and frontmatter obey the same read authority as
   their host, through real authenticated entry points in a synthetic store."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [io.pedestal.test :refer [response-for]]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datomic :as db]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.mcp.resources :as resources]
            [sandbar.mcp.tools :as tools]
            [sandbar.security.visibility :as visibility]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth]))

(use-fixtures :each (tu/make-test-db-fixture
                     {:test-name "section-visibility" :auth? false
                      :extra-schema [:auth :event]}))

(def ^:private private-root :memory.observations/owned_private)
(def ^:private public-root :memory.observations/owned_public)
(def ^:private secret "SYNTHETIC-PRIVATE-SECTION-7294")
(def ^:private extra-secret "SYNTHETIC-PRIVATE-FRONTMATTER-9821")

(defn- child-ident [root suffix]
  (keyword (namespace root) (str (name root) "__" (if (= suffix "child") "parent__child" suffix))))

(defn- seed-document! [root visibility project]
  (let [private? (= :private visibility)
        specs (md/parse-document
                (str "---\ntype: observation\nname: Synthetic ownership fixture\n"
                     "visibility: " (name visibility) "\nfixture-extra: "
                     (if private? extra-secret "PUBLIC-FRONTMATTER") "\n---\n"
                     "# Parent\n" (if private? secret "PUBLIC-SECTION") "\n"
                     "## Child\n" (if private? secret "PUBLIC-CHILD") "\n")
                (str "observations/" (name root) ".md"))
        specs (assoc specs 0 (assoc (first specs) :mm.memory/owning-project project))]
    @(d/transact (db/conn) (md/entity-specs->tx-data specs))))

(defn- seed-reader! [label extra]
  (let [key "synthetic-section-fixture-only"
        role (tu/ensure-role! :read-only)
        p (dt/make :auth/ServiceAccount
            (merge {:auth/service-name label
                    :auth/api-key-hash (auth/hash-password key)
                    :auth/roles [role] :auth/active? true}
                   extra))]
    {:token (str (name label) ":" key) :principal p}))

(defn- seed! []
  (md/register!)
  ;; Per-project provisioning is not shipped yet. Exercise its existing read
  ;; predicate with a synthetic fixture-only attribute; this is not a schema
  ;; migration or evidence that production accounts can be provisioned.
  @(d/transact (db/conn)
              [{:db/ident :auth/cleared-projects
                :db/valueType :db.type/ref :db/cardinality :db.cardinality/many}])
  @(d/transact (db/conn)
              [{:db/id "a" :db/ident :project/section-a :dt/type :mm/Project}
               {:db/id "b" :db/ident :project/section-b :dt/type :mm/Project}])
  (seed-document! private-root :private :project/section-a)
  (seed-document! public-root :public :project/section-b)
  {:reader (seed-reader! :section-reader {})
   :cleared (seed-reader! :section-cleared {:auth/cleared-projects [:project/section-a]})
   :wrong (seed-reader! :section-wrong {:auth/cleared-projects [:project/section-b]})
   :full (seed-reader! :section-full {:auth/full-clearance? true})})

(defn- rpc [token method params]
  (let [response (response-for tu/service :post "/mcp"
                   :headers {"Content-Type" "application/json"
                             "Accept" "application/json"
                             "Authorization" (str "Bearer " token)}
                   :body (json/generate-string
                           {:jsonrpc "2.0" :id 1 :method method :params params}))]
    (assert (= 200 (:status response)) (pr-str response))
    (json/parse-string (:body response) true)))

(defn- payload [envelope]
  (let [result (:result envelope)]
    (assert (nil? (:error envelope)) (pr-str envelope))
    (assert (map? result) (pr-str envelope))
    (assert (not (:isError result)) (pr-str result))
    (let [p (or (:structuredContent result)
                (json/parse-string (get-in result [:content 0 :text]) true))]
      (assert (nil? (:error p)) (pr-str p))
      p)))

(defn- tool [token name args]
  (payload (rpc token "tools/call" {:name name :arguments args})))

(defn- find-entity [token ident]
  (tool token "sandbar_entity_find" {:ident (str ident) :projection "full"}))

(defn- rest-read [token path]
  (response-for tu/service :get path
                :headers {"Accept" "application/json" "X-API-Key" token}))

(defn- rest-path [ident]
  (str "/api/store/entities/" (namespace ident) "/" (name ident)))

(defn- secret-free? [v]
  (every? #(not (str/includes? (pr-str v) %)) [secret extra-secret]))

(deftest document-and-components-share-authenticated-exact-read-authority
  (let [principals (seed!)]
    (doseq [[label {:keys [token]}] principals
            root [private-root public-root]
            ident [root (child-ident root "parent") (child-ident root "child")
                   (child-ident root "frontmatter")]]
      (let [allowed? (or (= root public-root) (#{:cleared :full} label))
            found (find-entity token ident)
            uri (resources/entity->uri (db/entity ident))
            resource (rpc token "resources/read" {:uri uri})
            rest (rest-read token (rest-path ident))]
        (testing (str label " " ident)
          (if allowed?
            (do (is (some? (:entity found)))
                (is (seq (get-in resource [:result :contents])))
                (is (= 200 (:status rest))))
            (do (is (= {:entity nil :missing? true :lookup (str ident)
                        :reasons ["entity-ref/not-found"]} found))
                (is (= -32602 (get-in resource [:error :code])))
                (is (str/includes? (get-in resource [:error :message] "") "Resource not found"))
                (is (= 404 (:status rest)))
                (is (secret-free? [found resource rest])))))))))

(deftest enumeration-and-graph-walks-cannot-recover-hidden-components
  (let [{:keys [reader cleared]} (seed!)
        cases [["sandbar_class_instances" {:class ":mm/Section" :projection "full"}]
               ["sandbar_class_instances" {:class ":mm/Frontmatter" :projection "full"}]
               ["sandbar_navigate_inbound-edges"
                {:entity (str private-root) :predicate ":mm.section/parent" :projection "full"}]
               ["sandbar_navigate_outbound-edges"
                {:entity (str private-root) :predicate ":mm.memory/frontmatter" :projection "full"}]
               ["sandbar_navigate_path-via"
                {:from (str private-root) :via "[:REP+ [:INV :mm.section/parent]]" :projection "full"}]]]
    (doseq [[verb args] cases]
      (let [hidden (tool (:token reader) verb args)
            visible (tool (:token cleared) verb args)]
        (is (secret-free? hidden) (str verb " " (pr-str hidden)))
        (is (not (secret-free? visible)) (str verb " positive control " (pr-str visible)))))
    (doseq [mode ["metadata-only" "frontmatter" "full"] paths? [false true]]
      (let [args (cond-> {:from (str private-root) :via "[:REP+ [:INV :mm.section/parent]]"
                         :projection mode}
                   paths? (assoc :include ["paths"]))
            hidden (tool (:token reader) "sandbar_navigate_path-via" args)
            visible (tool (:token cleared) "sandbar_navigate_path-via" args)
            entities (map #(if paths? (:entity %) %) (:reachable visible))]
        (is (secret-free? hidden))
        (is (= 2 (count entities)))
        (is (every? :db/id entities) (pr-str visible))
        (is (= (= "metadata-only" mode) (secret-free? visible)))
        (when paths? (is (every? :path (:reachable visible))))))
    (doseq [class-name ["Section" "Frontmatter"]]
      (let [r (rest-read (:token reader) (str "/api/store/classes/mm/" class-name "/instances"))]
        (is (= 200 (:status r)))
        (is (secret-free? r))))
    (let [hidden (rpc (:token reader) "resources/list" {})
          visible (rpc (:token cleared) "resources/list" {})
          uris #(set (map :uri (get-in % [:result :resources])))
          priv-uri (resources/entity->uri (db/entity (child-ident private-root "child")))
          pub-uri (resources/entity->uri (db/entity (child-ident public-root "child")))
          fm-uri (resources/entity->uri (db/entity (child-ident private-root "frontmatter")))]
      (is (nil? (:error hidden)))
      (is (not (contains? (uris hidden) priv-uri)))
      (is (not (contains? (uris hidden) fm-uri)))
      (is (contains? (uris hidden) pub-uri))
      (is (contains? (uris visible) priv-uri))
      (is (contains? (uris visible) fm-uri)))))

(deftest resources-nil-policy-and-local-tools-policy-remain-distinct
  (seed!)
  (doseq [root [private-root public-root]
          suffix ["parent" "child" "frontmatter"]]
    (let [ident (child-ident root suffix)
          entity (db/entity ident)
          result (resources/handle-read 1 {:uri (resources/entity->uri entity)})]
      (is (= (= root public-root) (boolean (:result result))) (str ident))
      (is (true? (visibility/entity-visible-to? nil entity)) "local tools stay unrestricted")
      (is (:entity (payload (tools/handle-call 1
                             {:name "sandbar_entity_find"
                              :arguments {"ident" (str ident) "projection" "full"}})))))))

(deftest invalid-ownership-fails-closed-even-for-full-clearance
  (let [principals (seed!)
        cases [:memory.sections/orphan :memory.sections/self-cycle
               :memory.sections/cycle-a :memory.sections/wrong-parent
               :memory.sections/missing-type-parent :memory.sections/subclass-orphan
               :memory.carriers/orphan :memory.carriers/shared]]
    ;; Deliberately malformed historical rows, not an authorized write API.
    @(d/transact (db/conn)
       [{:db/id "subclass" :db/ident :mm/OwnedSectionFixture :dt/type :dt/Class
         :dt/subclass-of :mm/Section}
        {:db/id "empty" :db/ident :memory.sections/untyped}
        {:db/id "orphan" :db/ident :memory.sections/orphan :dt/type :mm/Section :mm.section/body secret}
        {:db/id "self" :db/ident :memory.sections/self-cycle :dt/type :mm/Section
         :mm.section/parent "self" :mm.section/body secret}
        {:db/id "a" :db/ident :memory.sections/cycle-a :dt/type :mm/Section
         :mm.section/parent "b" :mm.section/body secret}
        {:db/id "b" :db/ident :memory.sections/cycle-b :dt/type :mm/Section
         :mm.section/parent "a" :mm.section/body secret}
        {:db/id "wrong" :db/ident :memory.sections/wrong-parent :dt/type :mm/Section
         :mm.section/parent :dt/Resource :mm.section/body secret}
        {:db/id "missing" :db/ident :memory.sections/missing-type-parent :dt/type :mm/Section
         :mm.section/parent "empty" :mm.section/body secret}
        {:db/id "sub" :db/ident :memory.sections/subclass-orphan :dt/type "subclass"
         :mm.section/body secret}
        {:db/id "carrier" :db/ident :memory.carriers/orphan :dt/type :mm/Frontmatter
         :mm.frontmatter/extra extra-secret}
        {:db/id "shared" :db/ident :memory.carriers/shared :dt/type :mm/Frontmatter
         :mm.frontmatter/extra extra-secret}
        [:db/add public-root :mm.memory/frontmatter "shared"]
        [:db/add private-root :mm.memory/frontmatter "shared"]])
    (dt/clear-type-relation-cache!)
    (doseq [[label {:keys [token principal]}] principals
            ident cases]
      (testing (str label " " ident)
        (is (false? (visibility/entity-visible-to? principal (db/entity ident))))
        (is (true? (:missing? (find-entity token ident))))
        (is (secret-free? (find-entity token ident)))
        (is (:error (resources/handle-read 1 {:uri (resources/entity->uri (db/entity ident))} principal)))))
    (is (false? (visibility/entity-visible-to? {} {:dt/type :mm/Section
                                                 :mm.section/parent :memory.missing/host
                                                 :mm.memory/visibility :public})))
    (is (false? (resources/read-cleared? nil (db/entity :memory.sections/orphan))))))

(deftest root-policy-wins-over-stale-component-labels-and-reparenting
  (let [{:keys [reader cleared]} (seed!)
        nested (child-ident private-root "child")
        carrier (child-ident private-root "frontmatter")]
    @(d/transact (db/conn)
       [[:db/add nested :mm.memory/visibility :public]
        [:db/add carrier :mm.memory/visibility :public]])
    (is (true? (:missing? (find-entity (:token reader) nested))))
    (is (true? (:missing? (find-entity (:token reader) carrier))))
    (is (:entity (find-entity (:token cleared) nested)))
    (let [before (db/entity nested)]
      @(d/transact (db/conn) [[:db/add nested :mm.section/parent public-root]])
      (is (:entity (find-entity (:token reader) nested)) "fresh read follows the current root")
      (is (false? (visibility/entity-visible-to? (:principal reader) before))
          "an entity snapshot follows its own immutable ancestry")
      (is (true? (visibility/entity-visible-to? (db/db) (:principal reader) before))
          "an explicitly supplied database decides the ancestry"))))

(deftest bare-identifiers-use-the-same-ownership-decision
  (let [{:keys [reader cleared]} (seed!)
        database (db/db)]
    (doseq [root [private-root public-root]
            suffix ["parent" "child" "frontmatter"]
            value [(child-ident root suffix) (:db/id (db/entity (child-ident root suffix)))]]
      (is (true? (visibility/compartmented? value)))
      (is (= (= root public-root)
             (visibility/entity-visible-to? (:principal reader) value)))
      (is (true? (visibility/entity-visible-to? database (:principal cleared) value))))
    (is (false? (visibility/entity-visible-to? (:principal cleared) :memory.missing/no-such-entity)))
    (let [ident (child-ident private-root "child")]
      @(d/transact (db/conn) [[:db/add ident :mm.section/parent public-root]])
      (is (false? (visibility/entity-visible-to? database (:principal reader) ident))
          "explicit database also pins a bare identifier's ownership")
      (is (true? (visibility/entity-visible-to? (:principal reader) ident))))))

(deftest component-subscriptions-and-delivery-follow-current-owner
  (let [principals (assoc (seed!) :anonymous {:principal nil})]
    (notifications/clear-all!)
    (resources/clear-all-subscriptions!)
    (try
      (doseq [root [private-root public-root]
              suffix ["parent" "child" "frontmatter"]]
        (let [entity (db/entity (child-ident root suffix))
              uri (resources/entity->uri entity)
              received (atom {})]
          (doseq [[label {:keys [principal]}] principals]
            (let [subscriber (notifications/register!
                               {:identity principal
                                :send! (fn [_] (swap! received update label (fnil inc 0)) true)})
                  result (resources/handle-subscribe 1 {:uri uri :subscriberId subscriber} principal)
                  allowed? (or (= root public-root) (#{:cleared :full} label))]
              (is (= (boolean allowed?) (contains? result :result)) (str label " " uri))
              ;; Even an existing subscription cannot bypass delivery policy.
              (resources/subscribe! uri subscriber)))
          ;; The reactive sink supplies post-transaction maps, not Entity values.
          (resources/entity-updated! (assoc (into {} (d/touch entity)) :db/id (:db/id entity)))
          (is (= (if (= root public-root) (set (keys principals)) #{:cleared :full})
                 (set (keys @received))) (str uri))
          (when (= suffix "child")
            (reset! received {})
            @(d/transact (db/conn)
               [[:db/add (:db/id entity) :mm.section/parent private-root]])
            ;; A stale public component map must use current ownership on delivery.
            (resources/entity-updated! (assoc (into {} (d/touch entity)) :db/id (:db/id entity)))
            (is (= #{:cleared :full} (set (keys @received)))))))
      (finally
        (notifications/clear-all!)
        (resources/clear-all-subscriptions!)))))
