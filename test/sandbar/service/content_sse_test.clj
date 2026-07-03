(ns sandbar.service.content-sse-test
  "Regression coverage for the POST /mcp SSE-first content-negotiation crash.

   Guards the bug memorialized in
   memory/bugs/mcp_post_sse_first_accept_arity_crash_content_types_placeholder_encoder_2026_07_03.md
   (Codex-diagnosed, Dan-relayed 2026-07-03): sandbar.service.content/+content-types+
   carried a ZERO-arity throw-placeholder for \"text/event-stream\".  Content
   negotiation could SELECT that entry; data-body wraps it as a normal
   [body output-stream] stream encoder; Pedestal invokes it with two args →
   clojure.lang.ArityException at content.clj, wire result HTTP 200 +
   Content-Type: text/event-stream + EMPTY body.  Codex (streamable_http
   transport, SSE-first Accept) therefore could not initialize.

   These cases exercise the FULL Pedestal chain (io.pedestal.test/response-for
   over sandbar.test-util/service) so the encoder is reached exactly the way
   the running server reaches it.  Option 1 (endorsed): a real SSE encoder
   emitting the JSON-RPC response as a single `data:` SSE frame."
  (:require [clojure.test        :refer :all]
            [clojure.string      :as str]
            [cheshire.core       :as json]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util   :as tu :refer [service get-header]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "content-sse-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixtures — the /mcp route requires a Bearer token (bearer-interceptor).  A
;; read-only service account suffices: the token gate only consults the
;; principal for tools/call; initialize accepts and ignores it (see
;; sandbar.mcp.protocol/dispatch).  The seed mints the principal the way the
;; wire mints it — authenticate-api-key via a bcrypt-hashed api-key.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private api-key "content-sse-test-not-a-real-key-4b7e")
(def ^:private service-name :content-sse-test-sa)

(defn- seed-sa!
  "Seed a :read-only role + ServiceAccount whose api-key hashes to `api-key`.
   Returns the Bearer token string `<service>:<key>`."
  []
  (let [role (dt/make :auth/Role
                      {:auth/role-name  auth/read-only-role
                       :auth/role-label "Fixture (content-sse)"}
                      {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name service-name
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name service-name) ":" api-key)))

(def ^:private initialize-msg
  {:jsonrpc "2.0"
   :id      1
   :method  "initialize"
   :params  {:protocolVersion "2025-11-25"
             :clientInfo      {:name "content-sse-test" :version "1.0"}}})

(defn- post-mcp
  "POST the initialize handshake to /mcp with the given Accept header + a
   Bearer token over the full interceptor chain."
  [accept]
  (let [bearer (seed-sa!)]
    (response-for service :post "/mcp"
                  :headers {"Content-Type"  "application/json"
                            "Accept"        accept
                            "Authorization" (str "Bearer " bearer)}
                  :body (json/generate-string initialize-msg))))

(defn- sse-data-payload
  "Extract the concatenated `data:` payload from an SSE frame body (SSE spec:
   each data line is prefixed `data:` with an optional leading space)."
  [body]
  (->> (str/split-lines body)
       (keep (fn [line]
               (when (str/starts-with? line "data:")
                 (-> line (subs (count "data:")) (str/replace #"^ " "")))))
       (str/join "\n")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (a) JSON-preferred initialize still returns JSON — the pre-existing path
;;     must not regress.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest json-preferred-initialize-returns-json
  (let [resp (post-mcp "application/json")]
    (is (= 200 (:status resp))
        "JSON-preferred initialize must succeed")
    (is (str/includes? (get-header resp "Content-Type") "application/json")
        "Content-Type must be JSON")
    (let [body (json/parse-string (:body resp) true)]
      (is (= "2.0" (:jsonrpc body)))
      (is (= 1 (:id body)))
      (is (= "sandbar" (-> body :result :serverInfo :name))
          "body must parse as a JSON-RPC initialize response"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (b) SSE-first Accept (text/event-stream, application/json) returns a valid
;;     single SSE frame carrying the JSON-RPC initialize response.  This is the
;;     Codex streamable_http path — the crash reproduction.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sse-first-initialize-returns-sse-frame
  (let [resp (post-mcp "text/event-stream, application/json")]
    (is (= 200 (:status resp))
        "SSE-first initialize must not crash — HTTP 200")
    (is (str/includes? (get-header resp "Content-Type") "text/event-stream")
        "Content-Type must include text/event-stream")
    (let [body (:body resp)]
      (is (and (string? body) (seq body))
          "body must be non-empty (the crash left it empty)")
      (is (str/includes? body "data:")
          "body must contain a data: SSE line")
      (let [payload (sse-data-payload body)
            parsed  (json/parse-string payload true)]
        (is (= "2.0" (:jsonrpc parsed)))
        (is (= 1 (:id parsed)))
        (is (= "sandbar" (-> parsed :result :serverInfo :name))
            "the data: payload must parse as a JSON-RPC initialize response")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (c) SSE-only Accept does not crash.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sse-only-initialize-does-not-crash
  (let [resp (post-mcp "text/event-stream")]
    (is (= 200 (:status resp))
        "SSE-only initialize must not crash — HTTP 200")
    (is (str/includes? (get-header resp "Content-Type") "text/event-stream")
        "Content-Type must include text/event-stream")
    (is (seq (:body resp))
        "body must be non-empty")))
