(ns sandbar.mcp.read-only-token-test
  "Falsification battery for the READ-ONLY MCP token gate — the single
   deny-by-default authorization check at the `tools/call` dispatch choke
   point (`sandbar.mcp.tools/handle-call`).

   A read-only principal (the codex-review reviewer; util/auth read-only-role)
   may call read/introspection verbs only.  These tests are the security gate:
   they prove a read verb SUCCEEDS, EVERY mutating verb family is REJECTED, the
   curated `project.export` FS-write override is REJECTED, an uncataloged verb
   is REJECTED (deny-by-default), an UN-roled principal is UNAFFECTED (no
   regression), and the in-process nil-principal path is UNGATED.

   A token that can call ANY mutation verb FAILS the mint (runbook §2.2 step 4).

   Fixture-only — no live DB, no shared transactor.
   Per decisions/review_gate_runbook_wave1_ratification_fable_rulings_2026_07_02.md
   ruling 8 + scratchpad/review-gate-runbook-2026-07-02/CODEX-RUNBOOK.md §2."
  (:require [clojure.test      :refer :all]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic  :as db]
            [sandbar.mcp.tools   :as tools]
            [sandbar.test-util   :as tu]
            [sandbar.util.auth   :as auth]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "mcp-read-only-token-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture seed — the :read-only role + a codex-review-style service account.
;;
;; Code + fixture seed only; the LIVE codex-review key mint is a later ceremony
;; step, out of scope for this build (runbook §2.2 step 3).  The principal is
;; read back via db/entity so its :auth/roles resolves exactly as the runtime
;; authenticate-api-key path returns it.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-role!
  "Transact an :auth/Role with `role-name` (validation off — a role needs no
   permissions of its own; the gate derives the read allowlist).  Returns the
   role's eid."
  [role-name]
  (:db/id (dt/make :auth/Role
                   {:auth/role-name  role-name
                    :auth/role-label (str "Fixture role " role-name)}
                   {:validate? false})))

(defn- seed-service-account!
  "Transact an active :auth/ServiceAccount named `service-name` holding the
   given `role-eids`.  Returns the principal read back via db/entity (the shape
   authenticate-api-key hands the dispatch path)."
  [service-name role-eids]
  (let [sa (dt/make :auth/ServiceAccount
                    {:auth/service-name service-name
                     :auth/api-key-hash "fixture-not-a-real-key"
                     :auth/roles        (vec role-eids)
                     :auth/active?      true}
                    {:validate? false})]
    (db/entity (:db/id sa))))

(defn- read-only-principal!
  "Seed + return a principal carrying the :read-only role."
  []
  (seed-service-account! :codex-review-fixture
                         [(seed-role! auth/read-only-role)]))

(defn- unroled-principal!
  "Seed + return an active principal carrying NO :read-only role (a normal
   full-access service account) — the no-regression control."
  []
  (seed-service-account! :full-access-fixture
                         [(seed-role! :writer-fixture)]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Response predicates (mirror sandbar.mcp.tools-db-test)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- success?
  "True if the response is a success envelope (result + content, no isError,
   no top-level error)."
  [response]
  (and (some? (-> response :result :content))
       (not (-> response :result :isError))
       (nil? (:error response))))

(defn- jsonrpc-error?
  "True if the response is a JSON-RPC error envelope (top-level :error)."
  [response]
  (some? (:error response)))

(defn- permission-denied?
  "True if the response is the read-only-gate permission rejection — a
   JSON-RPC error carrying the gate's `:reason` marker.  Distinguishes a GATE
   denial from an unknown-verb error or a handler failure."
  [response]
  (and (jsonrpc-error? response)
       (= :read-only-principal-forbidden-mutation
          (-> response :error :data :reason))))

(defn- call
  "Invoke handle-call with an explicit principal (3-arity)."
  [principal tool-name arguments]
  (tools/handle-call 1 {:name tool-name :arguments arguments} principal))

;; The 27 mutating verbs the leaf classifier flags (bb census against HEAD
;; 6fccc20); the falsification set names one verb from every mutating family
;; the runbook enumerates, plus the full roster is asserted en-masse below.
(def ^:private mutating-families
  ["sandbar.entity.create"
   "sandbar.entity.update"
   "sandbar.entity.retract"
   "sandbar.tag.rename"
   "sandbar.schedule.add"
   "sandbar.workflow.transition"
   "sandbar.project.import"])

(def ^:private all-mutating-verbs
  "Every verb the leaf classifier flags mutating — the gate must reject ALL of
   them for a read-only principal (a token reaching any one FAILS the mint)."
  ["sandbar.entity.create" "sandbar.entity.update" "sandbar.entity.retract"
   "sandbar.project.import"
   "sandbar.schedule.add" "sandbar.schedule.remove" "sandbar.schedule.enable"
   "sandbar.schedule.disable" "sandbar.schedule.start" "sandbar.schedule.stop"
   "sandbar.shape.create" "sandbar.shape.update"
   "sandbar.tag.define" "sandbar.tag.rename" "sandbar.tag.split"
   "sandbar.tag.align" "sandbar.tag.harmonize" "sandbar.tag.consolidate"
   "sandbar.tag.consolidate-all"
   "sandbar.validation.start" "sandbar.validation.run" "sandbar.validation.cancel"
   "sandbar.validation.retry"
   "sandbar.workflow.define" "sandbar.workflow.start-process"
   "sandbar.workflow.transition" "sandbar.workflow.orchestrate"])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unit-level: the classifier + role predicate (no dispatch)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest read-only-principal-predicate
  (testing "nil principal is not read-only (the legacy/local full-access path)"
    (is (false? (auth/read-only-principal? nil))))
  (testing "a principal carrying :read-only IS read-only"
    (is (true? (auth/read-only-principal? (read-only-principal!)))))
  (testing "a principal without :read-only is NOT read-only"
    (is (false? (auth/read-only-principal? (unroled-principal!))))))

(deftest verb-permission-classifier
  (testing "read verbs are permitted"
    (is (tools/verb-permitted-for-read-only? "sandbar.search.bm25f"))
    (is (tools/verb-permitted-for-read-only? "sandbar.entity.find"))
    (is (tools/verb-permitted-for-read-only? "sandbar.navigate.outbound-edges")))
  (testing "every mutating verb is NOT permitted"
    (doseq [v all-mutating-verbs]
      (is (not (tools/verb-permitted-for-read-only? v))
          (str v " must not be read-only-permitted"))))
  (testing "project.export is the curated FS-write override — NOT permitted"
    (is (not (tools/verb-permitted-for-read-only? "sandbar.project.export"))))
  (testing "an uncataloged verb classifies mutating — deny-by-default"
    (is (not (tools/verb-permitted-for-read-only? "sandbar.future.frobnicate")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Gate through the real dispatch boundary (handle-call)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest read-verb-succeeds-under-read-only-principal
  (testing "entity.find (a read verb) SUCCEEDS for a read-only principal"
    (let [response (call (read-only-principal!) "sandbar.entity.find"
                         {"ident" ":dt/Class"})]
      (is (success? response))
      (is (not (permission-denied? response))
          "a read verb must not be gate-denied")))
  (testing "search.bm25f (a read verb) is NOT gate-denied for a read-only principal"
    (let [response (call (read-only-principal!) "sandbar.search.bm25f"
                         {"query" "class" "class" ":dt/Class"})]
      (is (not (permission-denied? response))
          "search.bm25f must pass the gate (its own result may be empty)"))))

(deftest every-mutating-family-rejected-under-read-only-principal
  (testing "one verb from each mutating family the runbook enumerates is DENIED"
    (let [p (read-only-principal!)]
      (doseq [v mutating-families]
        (let [response (call p v {})]
          (is (permission-denied? response)
              (str v " must be gate-denied for a read-only principal; got "
                   (pr-str response)))))))
  (testing "the FULL mutating roster is denied — a token reaching any FAILS the mint"
    (let [p (read-only-principal!)]
      (doseq [v all-mutating-verbs]
        (is (permission-denied? (call p v {}))
            (str v " (full roster) must be gate-denied"))))))

(deftest project-export-rejected-under-read-only-principal
  (testing "project.export is DENIED though its leaf classifies read-only —
            the curated FS-write override (A2 C8 ruling)"
    (let [response (call (read-only-principal!) "sandbar.project.export" {})]
      (is (permission-denied? response)
          (str "project.export must be gate-denied; got " (pr-str response))))))

(deftest uncataloged-verb-rejected
  (testing "an unknown/uncataloged verb name is REJECTED for a read-only principal
            (deny-by-default) — reported as unknown-tool (the existing nil-verb
            branch fires before the gate)"
    (let [response (call (read-only-principal!) "sandbar.future.frobnicate" {})]
      (is (jsonrpc-error? response))
      (is (not (success? response)))))
  (testing "an uncataloged verb is unknown even with NO principal — no bypass"
    (let [response (tools/handle-call 1 {:name "sandbar.future.frobnicate" :arguments {}})]
      (is (jsonrpc-error? response)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; No-regression: an un-roled principal + the nil-principal in-process path.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest unroled-principal-passes-mutations-unchanged
  (testing "a principal WITHOUT :read-only is NOT gated — its mutating call
            reaches the handler (no permission denial), proving the gate
            activates only for the restricted role"
    (let [p        (unroled-principal!)
          response (call p "sandbar.entity.create" {})]
      (is (not (permission-denied? response))
          (str "an un-roled principal must not be gate-denied; got "
               (pr-str response)))))
  (testing "an un-roled principal's READ call also succeeds unchanged"
    (let [response (call (unroled-principal!) "sandbar.entity.find" {"ident" ":dt/Class"})]
      (is (success? response))
      (is (not (permission-denied? response))))))

(deftest in-process-nil-principal-path-ungated
  (testing "the b2-style 2-arity in-process handler path still works — a
            mutating verb is NOT gate-denied when no principal rides the call
            (the legacy/local full-access path, unchanged)"
    (let [response (tools/handle-call 1 {:name "sandbar.entity.create" :arguments {}})]
      (is (not (permission-denied? response))
          (str "nil-principal path must be ungated; got " (pr-str response)))))
  (testing "the 3-arity with an explicit nil principal is equally ungated"
    (let [response (call nil "sandbar.entity.create" {})]
      (is (not (permission-denied? response)))))
  (testing "a read verb on the nil-principal path succeeds as before"
    (let [response (tools/handle-call 1 {:name "sandbar.entity.find"
                                         :arguments {"ident" ":dt/Class"}})]
      (is (success? response)))))
