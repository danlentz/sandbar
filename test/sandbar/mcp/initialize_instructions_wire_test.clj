(ns sandbar.mcp.initialize-instructions-wire-test
  "S11/Rec-2 over-the-wire receipt — an `initialize` handshake POSTed to /mcp
   through the FULL Pedestal interceptor chain (`io.pedestal.test/response-for`:
   content-negotiation + JSON-RPC envelope + dispatch) against a SCRATCH
   in-process service (isolated in-mem DB, NOT the live server on 8389) must
   return, in the serialized response body, the `instructions` affordance field
   and the 0.2.0 server-info version, and the instructions must stay
   token-bounded.

   Auth note: /mcp `require-bearer` 401s an unauthenticated request even for the
   scope-EXEMPT `initialize` (read-only-token wire suite, `wire-fail-closed`), so
   we seed a throwaway ServiceAccount whose api-key is generated at RUNTIME — NO
   credential literal is committed.  `Accept: application/json` dodges the
   SSE-first content-negotiation crash
   (bugs/mcp_post_sse_first_accept_arity_crash_content_types_placeholder_encoder)."
  (:require [cheshire.core       :as json]
            [clojure.test        :refer :all]
            [io.pedestal.test    :refer [response-for]]
            [sandbar.db.datatype :as dt]
            [sandbar.test-util   :as tu :refer [service]]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name    "mcp-initialize-instructions-wire-test"
                                              :auth?        false
                                              :extra-schema [:auth :event]}))

(defn- seed-bearer!
  "Seed a read-only ServiceAccount whose api-key is generated at RUNTIME (no
   committed credential literal); return its Bearer token `<service>:<key>`."
  []
  (let [api-key (str (java.util.UUID/randomUUID))
        svc     :s11-init-wire-probe
        role    (dt/make :auth/Role
                         {:auth/role-name  auth/read-only-role
                          :auth/role-label "S11 init-wire read-only"}
                         {:validate? false})]
    (dt/make :auth/ServiceAccount
             {:auth/service-name svc
              :auth/api-key-hash (auth/hash-password api-key)
              :auth/roles        [(:db/id role)]
              :auth/active?      true}
             {:validate? false})
    (str (name svc) ":" api-key)))

(deftest initialize-carries-instructions-over-the-wire
  (let [bearer (seed-bearer!)
        resp   (response-for service :post "/mcp"
                             :headers {"Content-Type"  "application/json"
                                       "Accept"        "application/json"
                                       "Authorization" (str "Bearer " bearer)}
                             :body (json/generate-string
                                     {:jsonrpc "2.0" :id 1 :method "initialize"
                                      :params  {:protocolVersion "2025-11-25"
                                                :clientInfo {:name "codex-wire-probe" :version "1.0"}}}))
        body   (json/parse-string (:body resp) true)
        result (:result body)]
    (testing "initialize returns HTTP 200 + a JSON-RPC success envelope over the full chain"
      (is (= 200 (:status resp)) (str "expected 200; got " (:status resp) " body=" (:body resp)))
      (is (= "2.0" (:jsonrpc body)))
      (is (= 1 (:id body)))
      (is (some? result))
      (is (nil? (:error body))))
    (testing "the 0.2.0 server-info version bump rode the wire"
      (is (= "0.2.0" (get-in result [:serverInfo :version]))
          "server-info :version must arrive as 0.2.0 in the serialized wire body"))
    (testing "the instructions affordance is present in the wire body and token-bounded"
      (let [instr (:instructions result)]
        (is (string? instr) "the serialized initialize body must carry :instructions")
        (is (seq instr) "instructions must be non-empty over the wire")
        (is (< (count instr) 1500) "instructions must stay token-bounded over the wire")
        (is (re-find #"sandbar\.search\.bm25f" instr)
            "instructions must orient the client to the workhorse retrieval verb")))))
