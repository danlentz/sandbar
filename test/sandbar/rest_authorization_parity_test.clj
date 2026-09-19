(ns sandbar.rest-authorization-parity-test
  "The principal × operation × transport matrix for REST authorization parity
   (reliability sprint D4b, 2026-09-19; contracts CT-01, CT-02, CT-03 of the
   0.2.0 review fleet, whose corrected probe this file is modeled on).

   Every principal is minted the way the wire mints it — an active
   `:auth/ServiceAccount` whose key hashes to a fixture constant, authenticated
   through the REAL interceptor chains (`X-API-Key` for `/api`, `Authorization:
   Bearer` for `/mcp`) over `io.pedestal.test/response-for` — so nothing here is
   an injected-principal shortcut (the ceremony-#4 lesson).

   The matrix:

   - CT-01 — a mutation over REST is refused for the read-only and the
     unscoped principal with the SAME reasons the MCP gate gives, and nothing
     is persisted; the writer's mutation succeeds; an ordinary read stays
     available to the read-only principal; the session self-service routes are
     exempt.
   - CT-02 — the generic store reads never serialize a credential hash: the
     `:auth/*` instance enumerations are refused by namespace (loud, static),
     the class DEFINITION stays introspectable, and no accepted response body
     contains a seeded hash.
   - CT-03 — one visibility decision: an explicitly `:private` memory with no
     owning project is hidden from an uncleared principal on every path
     (`entity.find` full and metadata-only, `resources/read`, `resources/list`,
     `class.instances`, the REST entity read) with the not-found shape of an
     absent entity, while a full-clearance principal — writer or read-only —
     sees it everywhere; a `:public` memory is visible to all; the in-process
     nil-principal path stays unrestricted."
  (:require [cheshire.core       :as json]
            [clojure.string      :as str]
            [clojure.test        :refer :all]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.resources :as resources]
            [sandbar.mcp.tools   :as tools]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "rest-authorization-parity-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seeds
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-sa!
  "Mint an active ServiceAccount `service-name` whose key is `key`, carrying
   one role per `role-names` (none for `[]`) and any `extra` slots.  Returns
   the token string the wire presents (`<service>:<key>`)."
  [service-name key role-names & [extra]]
  (let [roles (mapv (fn [rn]
                      (:db/id (dt/make :auth/Role
                                       {:auth/role-name  rn
                                        :auth/role-label (str "parity " (name rn))}
                                       {:validate? false})))
                    role-names)]
    (dt/make :auth/ServiceAccount
             (merge {:auth/service-name service-name
                     :auth/api-key-hash (auth/hash-password key)
                     :auth/roles        roles
                     :auth/active?      true}
                    extra)
             {:validate? false})
    (str (name service-name) ":" key)))

(defn- seed-principals!
  "The four synthetic principals of the matrix."
  []
  {:writer  (seed-sa! :parity-writer  "parity-key-writer-1a2b"  [auth/read-write-role]
                      {:auth/full-clearance? true})
   :reader  (seed-sa! :parity-reader  "parity-key-reader-3c4d"  [auth/read-only-role])
   :cleared (seed-sa! :parity-cleared "parity-key-cleared-5e6f" [auth/read-only-role]
                      {:auth/full-clearance? true})
   :unroled (seed-sa! :parity-unroled "parity-key-unroled-7a8b" [])})

(def ^:private private-ident :memory.examples/parity-private)
(def ^:private public-ident  :memory.examples/parity-public)
(def ^:private private-body  "SYNTHETIC PRIVATE BODY")
(def ^:private public-body   "SYNTHETIC PUBLIC BODY")

(defn- seed-memories!
  "An explicitly :private observation with no owning project (the CT-03
   trigger) and an explicitly :public sibling.  Returns their resource URIs."
  []
  (let [priv (dt/make :mm/Observation
                      {:db/ident               private-ident
                       :mm.memory/name         "Synthetic private observation"
                       :mm.memory/memory-type  :observation
                       :mm.memory/scope        :project
                       :mm.memory/visibility   :private
                       :mm.memory/rel-path     "examples/parity-private.md"
                       :mm.memory/body-raw     private-body})
        pub  (dt/make :mm/Observation
                      {:db/ident               public-ident
                       :mm.memory/name         "Synthetic public observation"
                       :mm.memory/memory-type  :observation
                       :mm.memory/scope        :project
                       :mm.memory/visibility   :public
                       :mm.memory/rel-path     "examples/parity-public.md"
                       :mm.memory/body-raw     public-body})]
    {:private-uri (resources/entity->uri priv)
     :public-uri  (resources/entity->uri pub)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transports
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse [resp]
  (when (seq (:body resp))
    (try (json/parse-string (:body resp) true)
         (catch Exception _ (:body resp)))))

(defn- rest-call
  "An `/api` request with the API key the way REST accepts it."
  ([token method path] (rest-call token method path nil))
  ([token method path body]
   (let [headers (cond-> {"Accept" "application/json" "X-API-Key" token}
                   body (assoc "Content-Type" "application/json"))
         args    (cond-> [:headers headers]
                   body (into [:body (json/generate-string body)]))]
     (apply response-for service method path args))))

(defn- mcp-post
  "A JSON-RPC message over `/mcp` with a Bearer token."
  [token message]
  (response-for service :post "/mcp"
                :headers {"Content-Type"  "application/json"
                          "Accept"        "application/json"
                          "Authorization" (str "Bearer " token)}
                :body (json/generate-string message)))

(defn- rpc [method params] {:jsonrpc "2.0" :id 1 :method method :params params})
(defn- tool [name args] (rpc "tools/call" {:name name :arguments args}))

(defn- tool-payload
  "The handler-shape payload of a tools/call response."
  [resp]
  (some-> resp parse :result :content first :text (json/parse-string true)))

(defn- event-rows-named
  "How many `:event/ServerEvent` rows carry `event-name`."
  [event-name]
  (count (filter #(= event-name (:event/name %))
                 (dt/all-instances-of :event/ServerEvent))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CT-01 — mutation parity over REST
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rest-mutation-parity
  (let [{:keys [writer reader unroled]} (seed-principals!)
        event-name "parity-mutation-probe"
        post-event (fn [token]
                     (rest-call token :post "/api/events/server"
                                {"event/name" event-name
                                 "event/level" "info"
                                 "event/namespace" "parity"}))]
    (testing "the writer's mutation succeeds and persists"
      (let [resp (post-event writer)]
        (is (= 201 (:status resp)) (pr-str (parse resp)))
        (is (true? (:created (parse resp))))
        (is (= 1 (event-rows-named event-name)))))

    (testing "the read-only principal is refused with the MCP gate's reason, nothing persisted"
      (let [resp (post-event reader)
            body (parse resp)]
        (is (= 403 (:status resp)) (pr-str body))
        (is (= "read-only-principal-forbidden-mutation" (:reason body)))
        (is (= "POST /api/events/server" (:method body)))
        (is (= "read-only" (:role body)))
        (is (= 1 (event-rows-named event-name)) "no second row")))

    (testing "the unscoped principal is refused fail-closed, nothing persisted"
      (let [resp (post-event unroled)
            body (parse resp)]
        (is (= 403 (:status resp)) (pr-str body))
        (is (= "unscoped-principal-denied" (:reason body)))
        (is (= 1 (event-rows-named event-name)))))

    (testing "the rule is the family, not the route: jobs and processes refuse the same way"
      (is (= "read-only-principal-forbidden-mutation"
             (:reason (parse (rest-call reader :post "/api/jobs" {"job/name" "parity"})))))
      (is (= "unscoped-principal-denied"
             (:reason (parse (rest-call unroled :post "/api/processes" {"workflow" "parity"}))))))))

(deftest rest-read-parity-and-exemptions
  (let [{:keys [reader unroled]} (seed-principals!)]
    (testing "an ordinary read stays available to the read-only principal"
      (let [resp (rest-call reader :get "/api/store/classes/dt/Class/instances")]
        (is (= 200 (:status resp)) (pr-str (parse resp)))
        (is (pos? (:count (parse resp)))))
      (is (= 200 (:status (rest-call reader :get "/api/status")))))

    (testing "an unscoped principal is refused a read too (AP-2, as over MCP)"
      (let [resp (rest-call unroled :get "/api/store/classes/dt/Class/instances")]
        (is (= 403 (:status resp)))
        (is (= "unscoped-principal-denied" (:reason (parse resp))))))

    (testing "session self-service is exempt from the scope gate"
      (let [resp (rest-call unroled :post "/api/auth/logout")]
        (is (not= 403 (:status resp)) (pr-str (parse resp)))
        (is (nil? (:reason (parse resp))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CT-02 — the generic store reads never serialize a credential
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest rest-store-reads-never-serialize-credentials
  (let [{:keys [writer]} (seed-principals!)
        ;; a firewalled-class instance WITH a :db/ident, to read by ident
        _      (dt/make :auth/Role {:db/ident        :auth.roles/parity-probe
                                    :auth/role-name  :parity-probe
                                    :auth/role-label "PARITY-ROLE-LABEL-SECRET"}
                        {:validate? false})
        hashes (keep :auth/api-key-hash (dt/all-instances-of :auth/ServiceAccount))
        leaks? (fn [resp] (some #(str/includes? (str (:body resp)) %) hashes))]
    (is (seq hashes) "the fixture seeded hashes to look for")

    (testing "the :auth/* instance enumerations are refused by namespace, direct and inherited"
      (doseq [path ["/api/store/classes/auth/ServiceAccount/instances"
                    "/api/store/classes/auth/ServiceAccount/instances/direct"
                    "/api/store/classes/auth/Principal/instances"
                    "/api/store/classes/auth/ServiceAccount/validate"]]
        (let [resp (rest-call writer :get path)]
          (is (= 403 (:status resp)) path)
          (is (= "namespace-not-read-plane-allowed" (:reason (parse resp))) path)
          (is (not (leaks? resp)) path))))

    (testing "the class DEFINITION stays introspectable, without instance data"
      (let [resp (rest-call writer :get "/api/store/classes/auth/ServiceAccount")]
        (is (= 200 (:status resp)) (pr-str (parse resp)))
        (is (map? (:description (parse resp))))
        (is (not (leaks? resp)))))

    (testing "an allowed enumeration and an entity read are scrubbed projections"
      (let [resp (rest-call writer :get "/api/store/classes/dt/Class/instances")]
        (is (= 200 (:status resp)))
        (is (every? map? (:instances (parse resp))))
        (is (not (leaks? resp))))
      (let [resp (rest-call writer :get "/api/store/entities/dt/Resource")]
        (is (= 200 (:status resp)))
        (is (map? (:entity (parse resp))))))

    (testing "a firewalled instance read by ident answers exactly as an absent one"
      (let [firewalled (rest-call writer :get "/api/store/entities/auth.roles/parity-probe")
            absent     (rest-call writer :get "/api/store/entities/auth.roles/no-such-role")]
        (is (= 404 (:status firewalled)))
        (is (= 404 (:status absent)))
        (is (= (:error (parse firewalled)) (:error (parse absent))))
        (is (not (str/includes? (str (:body firewalled)) "PARITY-ROLE-LABEL-SECRET")) "no role data")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; CT-03 — one visibility decision across tools, resources and REST
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest one-visibility-decision-across-transports
  (let [{:keys [writer reader cleared]} (seed-principals!)
        {:keys [private-uri public-uri]} (seed-memories!)
        find-full (fn [token ident]
                    (tool-payload (mcp-post token (tool "sandbar_entity_find"
                                                        {"ident" (str ident) "projection" "full"}))))
        find-meta (fn [token ident]
                    (tool-payload (mcp-post token (tool "sandbar_entity_find" {"ident" (str ident)}))))
        read-res  (fn [token uri] (parse (mcp-post token (rpc "resources/read" {:uri uri}))))
        list-res  (fn [token]
                    (set (map :uri (get-in (parse (mcp-post token (rpc "resources/list" {})))
                                           [:result :resources]))))
        instances (fn [token]
                    (tool-payload (mcp-post token (tool "sandbar_class_instances"
                                                        {"class" ":mm/Observation" "projection" "full"}))))
        rest-ent  (fn [token ident]
                    (rest-call token :get (str "/api/store/entities/" (namespace ident) "/" (name ident))))
        body-of   (fn [payload] (get-in payload [:entity :mm.memory/body-raw]))
        not-found-res? (fn [r] (str/includes? (str (get-in r [:error :message])) "Resource not found"))]

    (testing "the uncleared read-only principal: the private memory is absent on every path"
      (let [p (find-full reader private-ident)]
        (is (true? (:missing? p)) (pr-str p))
        (is (nil? (:entity p)))
        (is (= ["entity-ref/not-found"] (:reasons p)) "the absent shape, no refusal reason"))
      (is (true? (:missing? (find-meta reader private-ident))) "projection-independent")
      (let [r (read-res reader private-uri)]
        (is (not-found-res? r) (pr-str r))
        (is (not (str/includes? (str r) private-body))))
      (is (not (contains? (list-res reader) private-uri)) "not advertised by resources/list")
      (let [inst (instances reader)]
        (is (not (str/includes? (pr-str inst) private-body)) "class.instances carries no private body")
        (is (some #(= "compartment" (:mm/redacted %)) (:instances inst))
            (str "the private instance is the compartment marker; got " (pr-str inst))))
      (let [resp (rest-ent reader private-ident)]
        (is (= 404 (:status resp)))
        (is (not (str/includes? (str (:body resp)) private-body)))))

    (testing "the uncleared read-only principal still sees the public memory everywhere"
      (is (= public-body (body-of (find-full reader public-ident))))
      (let [r (read-res reader public-uri)]
        (is (not (not-found-res? r)) (pr-str r)))
      (is (contains? (list-res reader) public-uri))
      (is (some #(= public-body (:mm.memory/body-raw %)) (:instances (instances reader))))
      (is (= 200 (:status (rest-ent reader public-ident)))))

    (testing "a full-clearance principal sees the private memory on every path, writer or read-only"
      (doseq [[label token] [[:writer writer] [:cleared-reader cleared]]]
        (is (= private-body (body-of (find-full token private-ident))) label)
        (let [r (read-res token private-uri)]
          (is (not (not-found-res? r)) (str label " " (pr-str r))))
        (is (contains? (list-res token) private-uri) label)
        (is (some #(= private-body (:mm.memory/body-raw %)) (:instances (instances token))) label)
        (let [resp (rest-ent token private-ident)]
          (is (= 200 (:status resp)) label)
          (is (str/includes? (str (:body resp)) private-body) label))))

    (testing "the in-process nil-principal path is unrestricted (the legacy contract)"
      (let [resp    (tools/handle-call 1 {:name "sandbar.entity.find"
                                          :arguments {"ident" (str private-ident) "projection" "full"}})
            payload (json/parse-string (get-in resp [:result :content 0 :text]) true)]
        (is (= private-body (body-of payload)))))))
