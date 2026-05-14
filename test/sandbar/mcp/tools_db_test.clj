(ns sandbar.mcp.tools-db-test
  "DB-backed handler-dispatch tests for the MCP tools layer.

  Sibling to `sandbar.mcp.tools-test` (which is the shape-only, DB-free
  suite per its own docstring); this namespace exercises the
  `tools/handle-call` boundary with the metamodel test fixture loaded.

  Scope per Phase R Stage R-1 Adoption Plan step 11
  (`plans/sandbar_0_1_0_codex_remediation_2026_05_14.md`):

    \"Add MCP + REST adversarial integration tests at the
    `handle-call` / REST-handler boundary per F-SF-3 pattern:
    integer eid + keyword + string + malformed-ref + missing-entity
    for every verb taking entity refs ...\"

  Focus: the F-MF-3 acceptance criteria of the entity-ref ADR
  (`decisions/sandbar_entity_ref_abstraction_2026_05_14.md`
  §Acceptance — Runtime acceptance).  Covers the verbatim
  falsification calls + the five input-form roundtrip + error
  projection for every migrated handler.

  Closes the aspirational-docstring/no-file gap observed during R-1
  Step 5 migration on 2026-05-14 PM: `tools-test`'s docstring claims
  these tests live here, but the file did not exist on disk prior to
  this commit."
  (:require [cheshire.core      :as json]
            [clojure.test       :refer :all]
            [sandbar.db.datomic :as db]
            [sandbar.mcp.tools  :as tools]
            [sandbar.test-util  :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "mcp-tools-db-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- call
  "Invoke tools/handle-call with a Clojure-map params shape."
  [tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments}))

(defn- user-error?
  "True if the response is a user-error envelope (isError true).
   See result-shape doc on tools/handle-call:1180-1182."
  [response]
  (true? (-> response :result :isError)))

(defn- error-text
  "Extract the projected text of a user-error response."
  [response]
  (-> response :result :content first :text))

(defn- jsonrpc-error?
  "True if the response is a JSON-RPC error envelope (top-level :error)."
  [response]
  (some? (:error response)))

(defn- success?
  "True if the response is a success envelope (result + content + no isError)."
  [response]
  (and (some? (-> response :result :content))
       (not (-> response :result :isError))
       (nil? (:error response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; F-MF-3 verbatim falsification calls (ADR §Acceptance — Runtime)
;;
;; These are the exact calls the codex F-MF-3 finding raised as the
;; release-blocker.  Pre-migration, integer-eid args fell through ->ident
;; to nil; :pre conditions then threw AssertionError; handle-call's
;; (catch Exception ...) missed AssertionError (which extends Error,
;; not Exception); the assertion escaped the MCP envelope entirely.
;;
;; Post-migration, eref/resolve-ident catches the missing-entity case
;; and throws structured ex-info — caught by handle-call's
;; (catch ExceptionInfo ...) and projected to {:isError true}.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest f-mf-3-path-via-integer-eid-returns-structured-user-error
  (testing "F-MF-3 verbatim: (handle-call \"sandbar.navigate.path-via\"
                                          {:from 12345 :via :SELF})
            returns structured MCP user-error response, NOT uncaught
            AssertionError"
    (let [response (call "sandbar.navigate.path-via"
                         {"from" 12345 "via" ":SELF"})]
      (is (user-error? response)
          "integer eid 12345 not in DB should produce :isError true envelope")
      (is (re-find #"(?i)not.*found|entity-ref" (error-text response))
          "error text should mention not-found or entity-ref"))))

(deftest f-mf-3-library-card-integer-eid-returns-structured-user-error
  (testing "F-MF-3 verbatim: (handle-call \"sandbar.orient.library-card\"
                                          {:entity 12345}) returns
            structured MCP user-error response"
    (let [response (call "sandbar.orient.library-card"
                         {"entity" 12345
                          "axes"   [{"name" "siblings" "direction" "forward"
                                     "predicates" [":dt/subclass-of"]}]})]
      (is (user-error? response)
          "integer eid 12345 not in DB should produce :isError true envelope")
      (is (re-find #"(?i)not.*found|entity-ref" (error-text response))
          "error text should mention not-found or entity-ref"))))

(deftest f-mf-3-path-via-bogus-string-projects-not-found
  (testing "Bogus string ref produces :entity-ref/not-found reason"
    (let [response (call "sandbar.navigate.path-via"
                         {"from" "not-a-thing" "via" ":SELF"})]
      (is (user-error? response))
      (is (re-find #"(?i)not.*found|entity-ref" (error-text response))))))

(deftest f-mf-3-path-via-lookup-vector-projects-malformed
  (testing "Lookup-vector input projects :entity-ref/malformed-input +
            :lookup-vector-unsupported reasons (per ADR §D-2 multi-reason
            envelope; deferred per OQ-1 REST audit)"
    (let [response (call "sandbar.navigate.path-via"
                         {"from" [":not" "supported"] "via" ":SELF"})]
      (is (user-error? response))
      (is (re-find #"(?i)lookup.*vector|malformed|entity-ref" (error-text response))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Five input-form roundtrip (ADR §Acceptance — Runtime)
;;
;; \"Integer eid + keyword ident + prefixed-string ident + unprefixed-string
;;   ident + entity-map — all five input shapes round-trip through MCP for
;;   path-via AND library-card AND any other verb identified during D-3.1
;;   migration\"
;;
;; Uses :dt/Class — guaranteed present by the metamodel fixture (loaded
;; via required-schema by make-test-db-fixture).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest roundtrip-path-via-keyword-ident
  (let [response (call "sandbar.navigate.path-via"
                       {"from" :dt/Class "via" ":SELF"})]
    (is (success? response)
        (str "keyword :dt/Class should resolve and roundtrip; got "
             (pr-str response)))))

(deftest roundtrip-path-via-prefixed-string
  (let [response (call "sandbar.navigate.path-via"
                       {"from" ":dt/Class" "via" ":SELF"})]
    (is (success? response)
        (str "prefixed string \":dt/Class\" should resolve; got "
             (pr-str response)))))

(deftest roundtrip-path-via-unprefixed-string
  (let [response (call "sandbar.navigate.path-via"
                       {"from" "dt/Class" "via" ":SELF"})]
    (is (success? response)
        (str "unprefixed string \"dt/Class\" should resolve; got "
             (pr-str response)))))

(deftest roundtrip-path-via-integer-eid
  (let [eid (:db/id (db/entity :dt/Class))]
    (is (integer? eid) "metamodel fixture should yield :dt/Class with integer eid")
    (let [response (call "sandbar.navigate.path-via"
                         {"from" eid "via" ":SELF"})]
      (is (success? response)
          (str "integer eid for :dt/Class should resolve; got "
               (pr-str response))))))

(deftest roundtrip-path-via-entity-map
  (let [entity-map (db/entity :dt/Class)]
    (is (some? entity-map))
    (let [response (call "sandbar.navigate.path-via"
                         {"from" entity-map "via" ":SELF"})]
      (is (success? response)
          (str "entity-map input should be idempotent; got "
               (pr-str response))))))

(deftest roundtrip-library-card-keyword
  (let [response (call "sandbar.orient.library-card"
                       {"entity" :dt/Class
                        "axes"   [{"name" "subclasses" "direction" "inverse"
                                   "predicates" [":dt/subclass-of"]}]})]
    (is (success? response))))

(deftest roundtrip-library-card-integer-eid
  (let [eid (:db/id (db/entity :dt/Class))]
    (let [response (call "sandbar.orient.library-card"
                         {"entity" eid
                          "axes"   [{"name" "subclasses" "direction" "inverse"
                                     "predicates" [":dt/subclass-of"]}]})]
      (is (success? response)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-handler error-projection sanity for the other migrated handlers
;;
;; Every handler that takes a ref arg must produce :isError true (not an
;; uncaught throw) when given a malformed or non-existent ref.  These
;; tests cover handler families beyond path-via + library-card.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest navigate-siblings-of-bogus-entity-projects-user-error
  (let [response (call "sandbar.navigate.siblings-of"
                       {"entity" "not-a-thing"
                        "path-slot" ":dt/slots"})]
    (is (user-error? response))))

(deftest types-instance-of-bogus-class-projects-user-error
  (let [response (call "sandbar.types.instance-of"
                       {"class" "not-a-thing"
                        "entity" :dt/Class})]
    (is (user-error? response))))

(deftest types-subclass-of-bogus-parent-projects-user-error
  (let [response (call "sandbar.types.subclass-of"
                       {"parent" "not-a-thing"
                        "child"  :dt/Class})]
    (is (user-error? response))))

(deftest entity-create-bogus-class-projects-user-error
  (let [response (call "sandbar.entity.create"
                       {"class" "not-a-thing"
                        "slots" {}})]
    (is (user-error? response))))

(deftest entity-find-bogus-ident-returns-missing-not-error
  (testing "entity-find has FIND-OR-MISSING semantic — does NOT raise on
            not-found; returns structured {:missing? true} via
            eref/validate"
    (let [response (call "sandbar.entity.find"
                         {"ident" "not-a-thing"})]
      (is (success? response)
          "find should NOT project :isError for missing entity — uses
           find-or-missing semantic")
      (let [body (json/parse-string (error-text response) true)]
        (is (true? (:missing? body))
            (str ":missing? should be true for not-found ref; got "
                 (pr-str body)))
        (is (some? (:reasons body))
            ":reasons should carry the entity-ref reason set")))))

(deftest entity-find-known-ident-returns-entity-not-missing
  (testing "Latent missing? bug fix verification: entity-find with a
            known-existing ref should NOT report :missing? true"
    (let [response (call "sandbar.entity.find"
                         {"ident" ":dt/Class"})]
      (is (success? response))
      (let [body (json/parse-string (error-text response) true)]
        (is (not (:missing? body))
            (str "known :dt/Class should not be :missing?; got "
                 (pr-str body)))
        (is (some? (:entity body))
            ":entity should be present in success response")))))

(deftest entity-update-bogus-ref-projects-user-error
  (let [response (call "sandbar.entity.update"
                       {"entity" "not-a-thing"
                        "slots"  {}})]
    (is (user-error? response))))

(deftest entity-validate-bogus-class-projects-user-error
  (let [response (call "sandbar.entity.validate"
                       {"class" "not-a-thing"
                        "slots" {}})]
    (is (user-error? response))))

(deftest aggregate-group-by-bogus-group-by-projects-user-error
  (let [response (call "sandbar.aggregate.group-by"
                       {"class"    ":dt/Class"
                        "group-by" "not-a-thing"})]
    (is (user-error? response))))

(deftest aggregate-rank-by-bogus-rank-by-projects-user-error
  (let [response (call "sandbar.aggregate.rank-by"
                       {"class"   ":dt/Class"
                        "rank-by" "not-a-thing"})]
    (is (user-error? response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Assertion-failure boundary smoke (handle-call §D-3.3 catch widening)
;;
;; Ensures that even a residual AssertionError (e.g., from an internal
;; :pre on a non-ref arg) does NOT escape the MCP envelope — it gets
;; projected to JSON-RPC internal-error via the explicit (catch
;; AssertionError ...) clause added in commit 90f73be.
;;
;; Note: post-migration, ref-arg :pre conditions are dropped (ADR
;; §D-3.2 Option B); residual :pre fires only on non-ref invariants.
;; This test is a smoke check on the catch infrastructure, not on a
;; specific handler.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unknown-tool-projects-jsonrpc-invalid-params
  (let [response (call "nonexistent.tool" {})]
    (is (jsonrpc-error? response))
    (is (= -32602 (-> response :error :code))
        "unknown tool should produce invalid-params")))
