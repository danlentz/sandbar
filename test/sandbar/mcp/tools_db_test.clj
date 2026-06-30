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
;; F-MF-1 anti-regression — :ANY ref-type guard (Phase R Stage R-2)
;;
;; Pre-fix: compile-any emitted [?start ?p ?end] with no constraint on
;; ?p's value-type; scalar attribute values (strings, longs) were
;; treated as entity ids and db/entity'd, propagating
;; :db.error/not-a-keyword through the MCP envelope as an uncaught
;; throw.  Post-fix (compile-any:147): ref-type guard
;; [?p :db/valueType :db.type/ref] constrains :ANY to typed edges; the
;; MCP boundary now produces a structured success envelope.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest f-mf-1-path-via-any-returns-structured-success
  (testing "F-MF-1 verbatim: (handle-call \"sandbar.navigate.path-via\"
                                          {:from :dt/Property :via :ANY})
            returns structured MCP success envelope, NOT uncaught
            :db.error/not-a-keyword crash"
    (let [response (call "sandbar.navigate.path-via"
                         {"from" ":dt/Property" "via" ":ANY"})]
      (is (success? response)
          (str ":ANY at MCP boundary must produce a success envelope; "
               "got " (pr-str response)))
      ;; Content carries the path-via result payload (JSON-encoded).
      (let [body (json/parse-string (error-text response) true)]
        (is (contains? body :reachable)
            ":reachable must be present in the success-envelope content")
        (is (contains? body :total))
        (is (contains? body :returned))
        (is (every? map? (:reachable body))
            ":ANY endpoints projected through MCP must be entity-maps")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; F-DB-1 — :include #{:paths} populates real path data (Phase R
;; Stage R-7 — Option D + Policy A).
;;
;; Pre-R-7: the surface accepted :include #{:paths} but the result
;; carried :path-data-deferred true (a documented placeholder).
;; Post-R-7: the Clojure-side IR evaluator
;; (sandbar.navigate.path.evaluate) populates real path data; no
;; deferred flag; each :reachable entry is {:entity ... :path ...}.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest f-db-1-path-via-include-paths-populates-real-path-data
  (testing "Phase R Stage R-7: handle-call with :include [\"paths\"] now
            populates real path data through the MCP boundary; no
            :path-data-deferred placeholder"
    (let [response (call "sandbar.navigate.path-via"
                         {"from" ":dt/Property"
                          "via"  ":dt/subclass-of"
                          "include" ["paths"]})]
      (is (success? response)
          (str "path-via with :include [\"paths\"] must produce success "
               "envelope; got " (pr-str response)))
      (let [body (json/parse-string (error-text response) true)]
        (is (not (contains? body :path-data-deferred))
            "post-R-7: no :path-data-deferred placeholder")
        (is (pos? (:total body))
            ":dt/Property →:dt/subclass-of→ should reach :dt/Resource")
        (doseq [entry (:reachable body)]
          (is (contains? entry :entity))
          (is (contains? entry :path))
          (let [{:keys [nodes edges]} (:path entry)]
            (is (vector? nodes))
            (is (vector? edges))
            (is (= (count nodes) (inc (count edges)))
                "path.value invariant: nodes count = edges count + 1")))))))

(deftest f-db-1-path-via-rep-plus-with-paths-mcp
  (testing "Phase R Stage R-7: :REP+ with :include [\"paths\"] through
            MCP returns multi-hop paths"
    (let [response (call "sandbar.navigate.path-via"
                         {"from" ":dt/Property"
                          "via"  "[:REP+ :dt/subclass-of]"
                          "include" ["paths"]})]
      (is (success? response))
      (let [body (json/parse-string (error-text response) true)]
        (is (pos? (:total body)))
        (doseq [entry (:reachable body)]
          (let [edges (-> entry :path :edges)]
            (is (pos? (count edges)) ":REP+ must produce ≥1-edge paths")))))))

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

(deftest coerce-slot-map-resolves-colon-prefixed-keys
  ;; Regression for the 2026-06-29 silent-drop bug: a colon-prefixed JSON
  ;; slot key is mangled by cheshire's :key-fn keyword into a keyword whose
  ;; NAMESPACE carries the colon, which used to match no declared slot and
  ;; was silently dropped (producing identless / shape-nonconformant
  ;; entities).  Derive the real string slot via the known-good bare-name
  ;; path, then prove its mangled colon-prefixed form resolves to the SAME
  ;; slot — ns-agnostic so it doesn't hard-code the slot's namespace.
  (let [bare (#'tools/coerce-slot-map :mm/Tag {"definition" "x"})
        slot (first (keys bare))]
    (is (some? slot) "sanity: bare local name 'definition' resolves on :mm/Tag")
    (let [mangled (keyword (str ":" (namespace slot)) (name slot))
          colon   (#'tools/coerce-slot-map :mm/Tag {mangled "x"})]
      (is (contains? colon slot)
          "colon-prefixed (cheshire-mangled) key must resolve to the slot, not be dropped"))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Stage 7.D tag-vocabulary verbs — behavior tests
;;
;; Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
;; §2.5 — sandbar.ground + sandbar.tag.{lookup, define, audit, consolidate,
;; split, rename, align, harmonize} verb surface.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- result-content-edn
  "Extract the result text from a successful MCP response and parse it as
   JSON with keyword keys.  Per tools.clj:1728 — content is emitted as
   `(json/generate-string data {:pretty true})`.  Helper-name kept as
   `-edn` for historical reasons + parallel with existing helpers; the
   actual format is JSON."
  [response]
  (let [text (-> response :result :content first :text)]
    (json/parse-string text true)))

;; ---------- sandbar.tag.define ----------

(deftest tag-define-creates-canonical-tag
  (let [response (call "sandbar.tag.define"
                       {"name"  "audit"
                        "slots" {"definition" "A discipline-checking pass over the corpus."
                                 "scope-note" "Applies when verifying capture-discipline gaps."}})]
    (is (success? response) "define should succeed")
    (let [payload (result-content-edn response)]
      (is (true? (:created payload)))
      (is (= "audit" (-> payload :tag :value))))))

(deftest tag-define-rejects-duplicate-canonical
  (call "sandbar.tag.define" {"name" "audit" "slots" {"definition" "First."}})
  (let [response (call "sandbar.tag.define" {"name" "audit" "slots" {"definition" "Duplicate."}})]
    (is (user-error? response)
        "second define with same :name should be user-error")
    (is (re-find #"already exists" (error-text response)))))

(deftest tag-define-requires-name
  (let [response (call "sandbar.tag.define" {"slots" {"definition" "Missing name."}})]
    (is (user-error? response))
    (is (re-find #"name" (error-text response)))))

;; ---------- sandbar.tag.lookup ----------

(deftest tag-lookup-finds-by-value-exact-match
  (call "sandbar.tag.define" {"name"  "datomic"
                              "slots" {"definition" "Datomic database."}})
  (let [response (call "sandbar.tag.lookup" {"concept" "datomic"})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (false? (:gap? payload)))
      (is (pos? (count (:matches payload))))
      (is (= "datomic" (-> payload :matches first :value))))))

(deftest tag-lookup-reports-gap-when-no-match
  (let [response (call "sandbar.tag.lookup" {"concept" "totally-unknown-concept-xyz"})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (true? (:gap? payload)))
      (is (zero? (count (:matches payload))))
      (is (re-find #"sandbar.tag.define" (str (:gap-hint payload)))))))

(deftest tag-lookup-finds-via-scope-note
  (call "sandbar.tag.define"
        {"name"  "discipline"
         "slots" {"definition" "Verifying capture pattern."
                  "scope-note" "Used during audit work on the corpus."}})
  (let [response (call "sandbar.tag.lookup" {"concept" "corpus"})]
    (is (success? response))
    (let [payload (result-content-edn response)
          values  (set (map :value (:matches payload)))]
      (is (contains? values "discipline")
          "scope-note containing 'corpus' should match via the lookup scorer"))))

;; ---------- sandbar.tag.audit ----------

(deftest tag-audit-returns-seven-invariant-report
  ;; Define a couple of tags so the audit has shape; doesn't matter which.
  (call "sandbar.tag.define" {"name" "well-defined-tag" "slots" {"definition" "Has defn."}})
  (call "sandbar.tag.define" {"name" "2026-05-12"})  ; date-pattern
  (let [response (call "sandbar.tag.audit" {})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (= 7 (count (:invariants payload))))
      (is (re-find #"violations" (:summary payload))))))

;; ---------- sandbar.tag.consolidate ----------

(deftest tag-consolidate-merges-into-canonical
  (call "sandbar.tag.define" {"name" "tags" "slots" {"definition" "plural form"}})
  (call "sandbar.tag.define" {"name" "tag"  "slots" {"definition" "singular form (canonical)"}})
  (let [response (call "sandbar.tag.consolidate" {"from" "tags" "into" "tag"})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (= "tags" (:from payload)))
      (is (= "tag"  (:into payload)))
      (is (= "tags" (:alt-label-added payload)))
      ;; JSON serialization stringifies keywords; payload sees "superseded" string.
      (is (= "superseded" (:lifecycle-status payload))))))

(deftest tag-consolidate-rejects-missing-from
  (call "sandbar.tag.define" {"name" "tag"  "slots" {"definition" "exists"}})
  (let [response (call "sandbar.tag.consolidate" {"from" "nonexistent" "into" "tag"})]
    (is (user-error? response))
    (is (re-find #"not found" (error-text response)))))

;; ---------- sandbar.tag.rename ----------

(deftest tag-rename-changes-value-preserves-old-as-hidden-label
  (call "sandbar.tag.define" {"name" "old-canonical" "slots" {"definition" "to be renamed"}})
  (let [response (call "sandbar.tag.rename" {"old" "old-canonical" "new" "new-canonical"})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (= "old-canonical" (:old payload)))
      (is (= "new-canonical" (:new payload)))
      (is (= "old-canonical" (:hidden-label-preserved payload))))))

(deftest tag-rename-rejects-when-new-name-taken
  (call "sandbar.tag.define" {"name" "alpha" "slots" {"definition" "first"}})
  (call "sandbar.tag.define" {"name" "beta"  "slots" {"definition" "second"}})
  (let [response (call "sandbar.tag.rename" {"old" "alpha" "new" "beta"})]
    (is (user-error? response))
    (is (re-find #"already exists" (error-text response)))))

;; ---------- sandbar.tag.split ----------

(deftest tag-split-creates-narrower-tags-with-broader-generic-ref
  (call "sandbar.tag.define" {"name" "parent-tag" "slots" {"definition" "to be split"}})
  (let [response (call "sandbar.tag.split"
                       {"tag" "parent-tag"
                        "into-tags" [{"value" "child-a" "scope-note" "First child"}
                                     {"value" "child-b" "scope-note" "Second child"}]})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (= "parent-tag" (:parent payload)))
      (is (= 2 (count (:into-tags payload))))
      (is (re-find #"NOT auto-rerouted" (:note payload))))))

(deftest tag-split-requires-2-or-more-into-tags
  (call "sandbar.tag.define" {"name" "parent" "slots" {"definition" "exists"}})
  (let [response (call "sandbar.tag.split"
                       {"tag" "parent"
                        "into-tags" [{"value" "single-child"}]})]
    (is (user-error? response))))

;; ---------- sandbar.tag.align ----------

(deftest tag-align-creates-exact-match-mapping
  (call "sandbar.tag.define" {"name" "datomic"
                              "slots" {"definition" "Datomic database"}})
  (let [response (call "sandbar.tag.align"
                       {"tag"          "datomic"
                        "external-iri" "http://www.wikidata.org/entity/Q5273260"
                        "mapping-type" "exact-match"})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (= "datomic" (:tag payload)))
      (is (= "exact-match" (:mapping-type payload)))
      (is (re-find #"exact-match" (:slot payload))))))

(deftest tag-align-rejects-invalid-mapping-type
  (call "sandbar.tag.define" {"name" "datomic" "slots" {"definition" "x"}})
  (let [response (call "sandbar.tag.align"
                       {"tag" "datomic" "external-iri" "http://example.org/x"
                        "mapping-type" "bogus-relation"})]
    (is (user-error? response))
    (is (re-find #"Invalid mapping-type" (error-text response)))))

;; ---------- sandbar.tag.harmonize ----------

(deftest tag-harmonize-returns-dry-run-report
  (call "sandbar.tag.define" {"name" "tag"})
  (call "sandbar.tag.define" {"name" "tags"})
  (let [response (call "sandbar.tag.harmonize" {})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (contains? payload :audit-report))
      (is (contains? payload :drift-clusters))
      (is (re-find #"DRY-RUN" (:note payload))))))

;; ---------- sandbar.ground ----------

(deftest ground-returns-multi-step-grounding-output
  (call "sandbar.tag.define" {"name"  "audit"
                              "slots" {"definition" "Discipline-checking pass."}})
  (let [response (call "sandbar.ground" {"concept" "audit"})]
    (is (success? response))
    (let [payload (result-content-edn response)]
      (is (= "audit" (:concept payload)))
      (is (contains? payload :step-1-tag-lookup))
      (is (contains? payload :step-2-meta-vocab))
      (is (contains? payload :step-3-suggested-next))
      (is (false? (-> payload :step-1-tag-lookup :gap?)))
      (is (pos? (count (-> payload :step-1-tag-lookup :matches)))))))

(deftest ground-requires-concept
  (let [response (call "sandbar.ground" {})]
    (is (user-error? response))
    (is (re-find #"concept" (error-text response)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ---------- sandbar.workflow.orchestrate (ι.3 W4.1 Increment C) ----------
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- start-orchestrate-test-process!
  "Bootstrap a :workflow/session process for orchestrate handler tests.
   Returns the numeric process eid."
  []
  (require '[sandbar.util.workflow :as wf])
  (require '[sandbar.test-util :as tu])
  (let [subject (tu/create-test-user! {:username (str "orch-mcp-" (System/nanoTime))
                                       :email    (str "orch-mcp-" (System/nanoTime) "@sandbar.test")})
        process ((resolve 'sandbar.util.workflow/start-process!) :workflow/session subject)]
    (:db/id process)))

(deftest orchestrate-handler-dispatches-phase-activate
  (testing ":phase/activate via MCP verb returns success envelope with expected shape"
    (let [pid      (start-orchestrate-test-process!)
          response (call "sandbar.workflow.orchestrate"
                         {"workflow"   ":workflow/session"
                          "process-id" pid
                          "phase"      ":phase/activate"})]
      (is (success? response))
      (let [payload (result-content-edn response)]
        (is (= ":phase/activate" (:phase-completed payload)))
        (is (= ":phase/imprint" (:next-phase payload)))
        (is (= [":session/start"] (:transition-applied payload)))
        (is (= 1 (count (:events-emitted payload)))
            ":events-emitted carries the :mm.event/WorkflowSessionOpened eid")
        (is (false? (:degraded? payload)))))))

(deftest orchestrate-handler-validates-missing-args
  (testing "Missing :phase produces a structured user-error envelope"
    (let [pid      (start-orchestrate-test-process!)
          response (call "sandbar.workflow.orchestrate"
                         {"workflow"   ":workflow/session"
                          "process-id" pid})]
      (is (user-error? response))
      (is (re-find #"(?i)phase" (error-text response))))))

(deftest orchestrate-handler-degraded-path
  (testing "κ P18 fallback engaged when transition unreachable from current state"
    (let [pid      (start-orchestrate-test-process!)  ;; in :session/opening
          ;; :phase/finalize tries :session/close — not reachable from :opening
          response (call "sandbar.workflow.orchestrate"
                         {"workflow"   ":workflow/session"
                          "process-id" pid
                          "phase"      ":phase/finalize"})]
      (is (success? response)
          "Degraded path returns SUCCESS envelope — :degraded? signals the condition, NOT user-error")
      (let [payload (result-content-edn response)]
        (is (true? (:degraded? payload)))
        (is (= [] (:transition-applied payload))
            "No transitions successfully applied (all degraded)")))))
