(ns sandbar.mcp.tool-name-rename-wire-test
  "WIRE-level proof of the dots→underscores MCP tool-name rename over the FULL
   interceptor chain (io.pedestal.test/response-for against service.routes) —
   the real path a connector serves, not the in-process handler.

   Probes:
   1. `tools-list-wire-advertises-underscore-only` — tools/list returns the full
      catalog with ONLY underscore names, none dotted, all pattern-conformant.
   2. `dotted-alias-still-dispatches-over-wire` — tools/call by the OLD dotted
      name still resolves + dispatches (the one-release alias).
   3. `authz-flip-guard-over-wire` — a read-only principal is DENIED a mutating
      verb called by its NEW underscore name AND PERMITTED a read verb by its
      NEW underscore name: the classifier reads the canonical dotted name, so
      the rename cannot flip a verb's authorization class.

   Mirrors the read-only-token WIRE harness (Bearer minted via
   authenticate-api-key; JSON-preferred Accept dodges the SSE-first crash bug).
   Per decisions/sandbar_mcp_tool_names_underscore_not_dot_durable_fix_not_papering_over_dan_directive_2026_07_04.md."
  (:require [cheshire.core       :as json]
            [clojure.string      :as str]
            [clojure.test        :refer :all]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.mcp.tools   :as tools]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "mcp-tool-name-rename-wire-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

(def ^:private api-key "wire-rename-not-a-real-key-2b7e")
(def ^:private service-name :codex-review-rename-wire)

(defn- seed-read-only-sa!
  "Seed a :read-only role + ServiceAccount whose api-key hashes to `api-key`.
   Returns the Bearer token `<service>:<key>`."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Fixture read-only (rename wire)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name service-name
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name service-name) ":" api-key)))

(defn- mcp-post
  "POST a JSON-RPC message to /mcp over the full chain with a Bearer token.
   `Accept: application/json` so the body is JSON we can parse."
  [bearer body]
  (response-for service :post "/mcp"
                :headers {"Content-Type"  "application/json"
                          "Accept"        "application/json"
                          "Authorization" (str "Bearer " bearer)}
                :body (json/generate-string body)))

(defn- parse [resp] (some-> resp :body (json/parse-string true)))

(defn- tools-call [tool-name arguments]
  {:jsonrpc "2.0" :id 1 :method "tools/call"
   :params  {:name tool-name :arguments arguments}})

(def ^:private wire-pattern #"^[a-zA-Z0-9_-]{1,64}$")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest tools-list-wire-advertises-underscore-only
  (let [bearer (seed-read-only-sa!)
        resp   (mcp-post bearer {:jsonrpc "2.0" :id 1 :method "tools/list" :params {}})
        body   (parse resp)
        names  (map :name (get-in body [:result :tools]))]
    (is (= 200 (:status resp)) "tools/list is a :read method, permitted for read-only principal")
    (is (= (count tools/verb-catalog) (count names)) "served count == catalog count (82)")
    (is (seq names))
    (is (every? #(not (str/includes? % ".")) names) "NO dotted names on the wire")
    (is (every? #(re-matches wire-pattern %) names)
        "every served name passes the Anthropic tool-name pattern")
    (is (= (set (map tools/wire-name (map :name tools/verb-catalog))) (set names))
        "served set is exactly the wire projection of the catalog")))

(deftest dotted-alias-still-dispatches-over-wire
  (let [bearer (seed-read-only-sa!)
        ;; entity.find is a READ verb → permitted for the read-only principal;
        ;; the OLD dotted name must still resolve + dispatch (one-release alias).
        body   (parse (mcp-post bearer (tools-call "sandbar.entity.find" {"ident" ":dt/Class"})))]
    (is (not= -32602 (get-in body [:error :code]))
        (str "dotted alias must NOT report Unknown-tool: " (pr-str body)))
    (is (nil? (:error body)) (str "dotted alias must dispatch, not error: " (pr-str body)))
    (is (some? (:result body)))))

(deftest authz-flip-guard-over-wire
  (let [bearer (seed-read-only-sa!)]
    (testing "mutating verb by NEW underscore name is DENIED for a read-only principal"
      (let [body (parse (mcp-post bearer (tools-call "sandbar_entity_create" {})))]
        (is (some? (:error body)) (str "entity.create (wire name) must be gate-denied: " (pr-str body)))
        (is (re-find #"(?i)permission|read-only"
                     (str (get-in body [:error :message])))
            "denial names the read-only contract")))
    (testing "read verb by NEW underscore name is PERMITTED for a read-only principal"
      (let [body (parse (mcp-post bearer (tools-call "sandbar_entity_find" {"ident" ":dt/Class"})))]
        (is (nil? (:error body)) (str "entity.find (wire name) must be permitted: " (pr-str body)))
        (is (some? (:result body)))))))
