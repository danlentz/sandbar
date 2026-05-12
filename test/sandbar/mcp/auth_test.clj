(ns sandbar.mcp.auth-test
  "Tests for the MCP Bearer-token interceptor (pure parsing tests;
   DB-backed delegate-to-authenticate-api-key tests would require the
   test-db fixture and land in C.4).

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.2."
  (:require [clojure.test     :refer :all]
            [sandbar.mcp.auth :as mcp-auth]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bearer token extraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest extract-bearer-token-canonical
  (testing "Bearer prefix is stripped"
    (is (= "abc123"
           (mcp-auth/extract-bearer-token
            {:headers {"authorization" "Bearer abc123"}}))))
  (testing "lowercase bearer prefix is accepted"
    (is (= "abc123"
           (mcp-auth/extract-bearer-token
            {:headers {"authorization" "bearer abc123"}}))))
  (testing "Authorization (TitleCase) header key is accepted"
    (is (= "xyz"
           (mcp-auth/extract-bearer-token
            {:headers {"Authorization" "Bearer xyz"}}))))
  (testing "extra whitespace is trimmed"
    (is (= "tok"
           (mcp-auth/extract-bearer-token
            {:headers {"authorization" "  Bearer   tok  "}})))))

(deftest extract-bearer-token-rejects-non-bearer
  (testing "Basic auth header returns nil"
    (is (nil? (mcp-auth/extract-bearer-token
               {:headers {"authorization" "Basic abc:def"}}))))
  (testing "missing header returns nil"
    (is (nil? (mcp-auth/extract-bearer-token {:headers {}}))))
  (testing "nil request returns nil"
    (is (nil? (mcp-auth/extract-bearer-token nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Token parsing — <service-name>:<api-key>
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest parse-token-canonical
  (is (= ["mcp-client" "secret"]
         (mcp-auth/parse-token "mcp-client:secret")))
  (is (= ["sa" "k"]
         (mcp-auth/parse-token "sa:k"))))

(deftest parse-token-rejects-malformed
  (testing "no colon"
    (is (nil? (mcp-auth/parse-token "no-colon"))))
  (testing "leading colon (empty service name)"
    (is (nil? (mcp-auth/parse-token ":api-key"))))
  (testing "trailing colon (empty api-key)"
    (is (nil? (mcp-auth/parse-token "service:"))))
  (testing "nil"
    (is (nil? (mcp-auth/parse-token nil))))
  (testing "empty"
    (is (nil? (mcp-auth/parse-token "")))))

(deftest parse-token-preserves-internal-colons
  (testing "api-key portion can contain internal colons"
    (is (= ["service" "key:with:colons"]
           (mcp-auth/parse-token "service:key:with:colons")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; require-bearer interceptor — pure (no DB)
;;
;; Verifies the 401 short-circuit response shape + WWW-Authenticate header.
;; Does NOT verify the bearer-interceptor's success path (that requires
;; authenticate-api-key, which needs a real Datomic connection).

(deftest require-bearer-401-when-no-identity
  (let [ctx     {:request {}}
        result  ((-> mcp-auth/require-bearer :enter) ctx)
        response (:response result)]
    (is (some? response) "require-bearer should attach a response when no identity")
    (is (= 401 (:status response)))
    (is (re-find #"(?i)bearer" (get-in response [:headers "WWW-Authenticate"])))
    (is (= "application/json" (get-in response [:headers "Content-Type"])))))

(deftest require-bearer-passes-through-when-identity-attached
  (let [ctx     {:request {} :identity {:db/id 1 :auth/service-name "mcp-client"}}
        result  ((-> mcp-auth/require-bearer :enter) ctx)]
    (is (= ctx result) "require-bearer should pass through unchanged when :identity present")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; bearer-interceptor — partial coverage (pure paths only)
;;
;; The success path requires authenticate-api-key with a live DB; that
;; lands in DB-backed integration tests in C.4. Pure paths verified here:
;; missing header, malformed token, already-authenticated.

(deftest bearer-interceptor-no-header-passes-through
  (let [ctx     {:request {:headers {}}}
        result  ((-> mcp-auth/bearer-interceptor :enter) ctx)]
    (is (= ctx result))
    (is (nil? (:identity result)))))

(deftest bearer-interceptor-malformed-token-passes-through
  (let [ctx     {:request {:headers {"authorization" "Bearer no-colon-here"}}}
        result  ((-> mcp-auth/bearer-interceptor :enter) ctx)]
    (is (nil? (:identity result))
        "malformed token (no colon) should NOT attach identity")))

(deftest bearer-interceptor-already-authenticated-skips
  (let [existing-identity {:db/id 42 :auth/email "session-user@example.com"}
        ctx     {:request {:headers {"authorization" "Bearer service:key"}}
                 :identity existing-identity}
        result  ((-> mcp-auth/bearer-interceptor :enter) ctx)]
    (is (= existing-identity (:identity result))
        "bearer-interceptor should NOT overwrite an upstream :identity")))
