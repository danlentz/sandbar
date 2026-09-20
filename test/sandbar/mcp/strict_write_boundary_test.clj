(ns sandbar.mcp.strict-write-boundary-test
  "RT-01 (Astra's 0.2.0 review, folded into D6 on 2026-09-19): a strict MCP
   write that its shapes refuse must leave NOTHING behind — no committed
   facts, no projection enqueue, no search work, no notification — and a
   change accepted between the check and the commit cannot invalidate what
   the check examined.

   Her reproduction at 3fee3b6 (an isolated probe against the real
   dispatcher): a strict create with a description of `INVALID` under a
   shape requiring `^APPROVED$` returned an error AND left the value
   committed, with the notification and search hooks already called; a
   strict update did the same.  The create and update handlers persisted
   first and validated afterwards.

   What this pins, through the REAL dispatched verbs against a fresh
   store with the transactor-side basis guard installed the way boot
   installs it:
     - a strict create the shape refuses: error envelope, basis unmoved,
       enqueue counter unmoved, the search and notification spies never
       called, the entity absent;
     - a strict create the shape accepts: committed, the shape report in
       the response, the hooks called once;
     - a strict update the shape refuses: error, the prior value intact,
       the search spy never called; a strict update it accepts: committed;
     - audit mode (the default) commits an invalid write and returns an
       explicit report — the retained contract, stated, not a guarantee;
     - the basis guard: when the shape is tightened AFTER the preflight
       read its database, the commit aborts and the re-run refuses; when an
       unrelated transaction lands in that window, the re-run accepts.
   Batch semantics are defined by exclusion: `project.import` runs no shape
   validation (the firewall floor only), and its card says so."
  (:require [cheshire.core             :as json]
            [clojure.test              :refer [deftest is testing use-fixtures]]
            [datomic.api               :as d]
            [sandbar.db.datatype       :as dt]
            [sandbar.db.datomic        :as db]
            [sandbar.db.fn             :as fn]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.mcp.tools         :as tools]
            [sandbar.reactive.queue    :as reactive-queue]
            [sandbar.search            :as search]
            [sandbar.shape             :as shape]
            [sandbar.test-util         :as tu]))

(def ^:private test-uri "datomic:mem://strict-write-boundary-test")

(use-fixtures :each
  (fn [f]
    ((tu/make-test-db-fixture {:test-name "strict-write-boundary-test" :auth? false})
     (fn []
       ;; Boot installs the transactor-side functions through this very call
       ;; (sandbar.db.datomic init); the test schema loader stops short of it.
       (fn/load-all-dbfn test-uri)
       (f)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- call [verb arguments]
  (tools/handle-call 1 {:name verb :arguments arguments}))

(defn- error? [resp] (true? (-> resp :result :isError)))

(defn- payload [resp]
  (some-> resp :result :content first :text (json/parse-string true)))

(defn- basis [] (d/basis-t (d/db (db/conn))))

(defn- enqueued [] (:enqueue-total (reactive-queue/health)))

(def ^:private probe-ident :memory.decisions/strict_probe)

(defn- approved-shape!
  "A shape on :mm/Decision requiring the description to match ^APPROVED$
   at violation severity — Astra's probe shape.  Returns the pattern
   constraint's eid so a test can tighten it concurrently."
  []
  (let [patt-eid (:db/id (dt/make :mm.shape/PatternConstraint
                                  {:mm.shape.pattern/property :mm.memory/description
                                   :mm.shape.pattern/regex    "^APPROVED$"}))]
    (dt/make :mm/Shape {:mm.shape/shape-id            "strict-boundary-approved-description"
                        :mm.shape/applies-to          :mm/Decision
                        :mm.shape/description         "the description must be exactly APPROVED"
                        :mm.shape/severity            :violation
                        :mm.shape/pattern-constraints [patt-eid]
                        :mm.memory/scope              :project
                        :mm.memory/memory-type        :shape})
    patt-eid))

(defn- create-args [description mode]
  {"class"           ":mm/Decision"
   "slots"           {"mm.memory/rel-path"    "decisions/strict_probe.md"
                      "mm.memory/name"        "Strict boundary probe"
                      "mm.memory/description" description
                      "mm.memory/memory-type" "decision"}
   "validation-mode" mode})

(defn- description-of [ident]
  (:mm.memory/description (dt/find-by-ident ident)))

(defmacro with-spies
  "Run `body` with the search enqueue and the notification publish replaced
   by counters bound to `search-calls` and `publish-calls`."
  [[search-calls publish-calls] & body]
  `(let [~search-calls  (atom 0)
         ~publish-calls (atom 0)]
     (with-redefs [search/entity-changed-async! (fn [& _#] (swap! ~search-calls inc) nil)
                   notifications/publish!       (fn [& _#] (swap! ~publish-calls inc) nil)]
       ~@body)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; create
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-strict-create-the-shape-refuses-leaves-nothing-behind
  (approved-shape!)
  (let [basis-before   (basis)
        enqueue-before (enqueued)]
    (with-spies [search-calls publish-calls]
      (let [resp (call "sandbar.entity.create" (create-args "INVALID" "strict"))]
        (is (error? resp) (pr-str resp))
        (is (re-find #"(?i)shape" (str (:message (payload resp)))) (pr-str (payload resp)))
        (is (= basis-before (basis)) "no committed facts: the basis did not move")
        (is (= enqueue-before (enqueued)) "no projection enqueue")
        (is (zero? @search-calls) "no search work")
        (is (zero? @publish-calls) "no notification")
        (is (nil? (dt/find-by-ident probe-ident)) "the entity does not exist")))))

(deftest a-strict-create-the-shape-accepts-commits-with-its-report
  (approved-shape!)
  (with-spies [search-calls _publish-calls]
    (let [resp (call "sandbar.entity.create" (create-args "APPROVED" "strict"))
          body (payload resp)]
      (is (not (error? resp)) (pr-str body))
      (is (= "APPROVED" (description-of probe-ident)))
      (is (= "strict" (get-in body [:shape-validation :mode])) (pr-str (keys body)))
      (is (every? #(= "pass" (:status %)) (get-in body [:shape-validation :results])))
      (is (= 1 @search-calls) "the search hook ran once, after the commit"))))

(deftest audit-mode-commits-an-invalid-write-and-says-so
  (approved-shape!)
  (let [resp (call "sandbar.entity.create" (create-args "INVALID" "audit"))
        body (payload resp)]
    (is (not (error? resp)) (pr-str body))
    (is (= "INVALID" (description-of probe-ident)) "audit commits")
    (is (= "audit" (get-in body [:shape-validation :mode])))
    (is (some #(= "fail" (:status %)) (get-in body [:shape-validation :results]))
        "and returns the explicit report")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; update
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-strict-update-the-shape-refuses-leaves-the-prior-value-intact
  (approved-shape!)
  (is (not (error? (call "sandbar.entity.create" (create-args "APPROVED" "strict")))))
  (let [basis-before (basis)]
    (with-spies [search-calls _publish-calls]
      (let [resp (call "sandbar.entity.update"
                       {"entity"          (str probe-ident)
                        "slots"           {"mm.memory/description" "INVALID AGAIN"}
                        "validation-mode" "strict"})]
        (is (error? resp) (pr-str resp))
        (is (= "APPROVED" (description-of probe-ident)) "the prior value is intact")
        (is (= basis-before (basis)) "the basis did not move")
        (is (zero? @search-calls) "no search work")))))

(deftest a-strict-update-the-shape-accepts-commits
  (approved-shape!)
  (is (not (error? (call "sandbar.entity.create" (create-args "APPROVED" "strict")))))
  ;; loosen the shape to accept the new value, through the ordinary API
  (let [resp (call "sandbar.entity.update"
                   {"entity"          (str probe-ident)
                    "slots"           {"mm.memory/name" "renamed, description untouched"}
                    "validation-mode" "strict"})
        body (payload resp)]
    (is (not (error? resp)) (pr-str body))
    (is (= "renamed, description untouched" (:mm.memory/name (dt/find-by-ident probe-ident))))
    (is (= "strict" (get-in body [:shape-validation :mode])))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; the basis guard closes the window between check and commit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest a-shape-tightened-after-the-preflight-still-refuses-the-write
  (let [patt-eid (approved-shape!)
        calls    (atom 0)
        orig     shape/validate]
    (with-redefs [shape/validate
                  (fn [db eid mode]
                    (when (= 1 (swap! calls inc))
                      ;; another author tightens the shape after the preflight
                      ;; took its database value and before the commit lands
                      @(d/transact (db/conn) [[:db/add patt-eid :mm.shape.pattern/regex "^REJECTED$"]]))
                    (orig db eid mode))]
      (let [resp (call "sandbar.entity.create" (create-args "APPROVED" "strict"))]
        (is (error? resp) "refused at the moved basis")
        (is (= 2 @calls) "the guard aborted the first commit and the check ran again")
        (is (nil? (dt/find-by-ident probe-ident)) "nothing was committed")))))

(deftest an-unrelated-transaction-in-the-window-only-re-runs-the-check
  (approved-shape!)
  (let [calls (atom 0)
        orig  shape/validate]
    (with-redefs [shape/validate
                  (fn [db eid mode]
                    (when (= 1 (swap! calls inc))
                      @(d/transact (db/conn) [{:db/id "bystander" :dt/type :mm/Memory
                                               :mm.memory/rel-path "decisions/bystander.md"
                                               :mm.memory/name "a bystander"}]))
                    (orig db eid mode))]
      (let [resp (call "sandbar.entity.create" (create-args "APPROVED" "strict"))]
        (is (not (error? resp)) (pr-str (payload resp)))
        (is (= 2 @calls) "the guard aborted the first commit; the second passed")
        (is (= "APPROVED" (description-of probe-ident)))))))
