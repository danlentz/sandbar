(ns sandbar.audit.tag-test
  "Tests for sandbar.audit.tag — tag-lifecycle invariant audits.

   Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
   Stage 7.B.  Verifies that each invariant correctly surfaces the
   violation pattern it's designed to catch + that the aggregator
   produces a coherent report."
  (:require [clojure.test       :refer :all]
            [datomic.api        :as d]
            [sandbar.audit.tag  :as audit]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util  :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "audit-tag-test"
                                              :auth? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers — transact tags + memorials directly

(defn- tx
  "Transact `data` and return the resolved DB after."
  [data]
  @(d/transact (db/conn) data)
  (db/db))

(defn- make-tag
  "Build a :mm/Tag entity-spec.  `value` is the canonical label; `extras` is
   a map of additional :mm.tag/* slots."
  ([value] (make-tag value {}))
  ([value extras]
   (merge {:dt/type      :mm/Tag
           :mm.tag/value value}
          extras)))

(defn- make-memory
  "Build a :mm/Memory entity-spec with the given :db/ident + tag refs."
  [ident memory-type tag-values]
  (cond-> {:dt/type                :mm/Memory
           :db/ident               ident
           :mm.memory/name         (str (name ident))
           :mm.memory/memory-type  memory-type}
    (seq tag-values)
    (assoc :mm.memory/tags (mapv (fn [v] {:mm.tag/value v}) tag-values))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 1 — undefined-used

(deftest undefined-used-surfaces-tags-referenced-but-not-defined
  (tx [(make-memory :test/foo :decision ["used-undefined" "used-defined"])
       (make-tag "used-defined" {:mm.tag/definition "Has a definition."})
       ;; Note: "used-undefined" is auto-upserted by :mm.tag/value uniqueness via
       ;; make-memory's tag-spec; no definition transacted.
       (make-tag "orphan-defined" {:mm.tag/definition "Defined but unused."})])

  (let [report (audit/undefined-used)]
    (is (= :undefined-used (:invariant report)))
    (is (pos? (:violation-count report))
        "should find at least one undefined-used tag")
    (let [violations (:violations report)
          values     (set (map :value violations))]
      (is (contains? values "used-undefined")
          "used-undefined should be in violations")
      (is (not (contains? values "used-defined"))
          "used-defined has :definition; should NOT be in violations")
      (is (not (contains? values "orphan-defined"))
          "orphan-defined has no inbound :tags refs; not in this invariant"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 2 — defined-unused

(deftest defined-unused-surfaces-defined-tags-with-no-inbound-refs
  (tx [(make-memory :test/foo :decision ["used"])
       (make-tag "used" {:mm.tag/definition "Has a definition + is used."})
       (make-tag "unused-defined" {:mm.tag/definition "Has a definition; no refs."})])

  (let [report (audit/defined-unused)]
    (is (= :defined-unused (:invariant report)))
    (is (pos? (:violation-count report)))
    (let [values (set (map :value (:violations report)))]
      (is (contains? values "unused-defined")
          "unused-defined should be in violations")
      (is (not (contains? values "used"))
          "used has inbound :tags ref; not in violations"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 3 — orphan

(deftest orphan-tags-surfaces-tags-with-no-in-scheme
  ;; Without :mm/ConceptScheme instances in the fixture we can simply verify
  ;; that tags without :mm.tag/in-scheme appear as orphans; once schemes exist
  ;; we'll add positive-case tests.
  (tx [(make-tag "orphan-tag")
       (make-tag "another-orphan")])

  (let [report (audit/orphan-tags)]
    (is (= :orphan (:invariant report)))
    (let [values (set (map :value (:violations report)))]
      (is (contains? values "orphan-tag"))
      (is (contains? values "another-orphan")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 4 — date-pattern

(deftest date-pattern-tags-surfaces-iso-and-underscore-and-yyyymmdd-forms
  (tx [(make-tag "2026-05-12")            ; ISO date
       (make-tag "2026_05_12")            ; underscore variant
       (make-tag "20260512")              ; bare YYYYMMDD
       (make-tag "real-concept-tag")      ; not a date
       (make-tag "audit-2026")            ; not a full date
       (make-tag "2026-not-a-date")])     ; not a date

  (let [report (audit/date-pattern-tags)
        values (set (map :value (:violations report)))]
    (is (= :date-pattern (:invariant report)))
    (is (contains? values "2026-05-12"))
    (is (contains? values "2026_05_12"))
    (is (contains? values "20260512"))
    (is (not (contains? values "real-concept-tag")))
    (is (not (contains? values "audit-2026")))
    (is (not (contains? values "2026-not-a-date")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 5 — type-pattern

(deftest type-pattern-tags-surfaces-tags-overlapping-memory-type
  (tx [(make-memory :test/a :decision    [])
       (make-memory :test/b :observation [])
       (make-tag "decision")     ; matches memory-type
       (make-tag "observation")  ; matches memory-type
       (make-tag "real-concept")]) ; doesn't

  (let [report (audit/type-pattern-tags)
        values (set (map :value (:violations report)))]
    (is (= :type-pattern (:invariant report)))
    (is (contains? values "decision"))
    (is (contains? values "observation"))
    (is (not (contains? values "real-concept")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 6 — drift

(deftest drift-clusters-surfaces-singular-plural-and-case-drift
  (tx [(make-tag "tag")
       (make-tag "tags")       ; plural drift with "tag"
       (make-tag "Audit")
       (make-tag "audit")      ; case drift with "Audit"
       (make-tag "category")
       (make-tag "categories") ; -ies drift with "category"
       (make-tag "singleton")]) ; no cluster

  (let [report (audit/drift-clusters)]
    (is (= :drift (:invariant report)))
    (let [clusters     (:violations report)
          by-norm-form (into {} (map (juxt :normalized-form :variants) clusters))]
      (is (>= (count clusters) 3) "at least 3 drift clusters")
      (is (contains? by-norm-form "tag") "tag/tags cluster")
      (is (contains? by-norm-form "audit") "Audit/audit cluster")
      (is (contains? by-norm-form "category") "category/categories cluster")
      ;; Each cluster has 2+ variants
      (doseq [[_norm variants] by-norm-form]
        (is (>= (count variants) 2) "every cluster has 2+ variants")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 7 — closure-consistency

(deftest closure-consistency-detects-broader-generic-cycle
  ;; Build a 3-tag cycle on :mm.tag/broader-generic: A → B → C → A
  (let [tx-data [(make-tag "tag-a")
                 (make-tag "tag-b")
                 (make-tag "tag-c")]]
    (tx tx-data))
  ;; Now wire up the cycle via lookup-ref ref-typed slot upserts
  (tx [{:mm.tag/value "tag-a"
        :mm.tag/broader-generic [{:mm.tag/value "tag-b"}]}
       {:mm.tag/value "tag-b"
        :mm.tag/broader-generic [{:mm.tag/value "tag-c"}]}
       {:mm.tag/value "tag-c"
        :mm.tag/broader-generic [{:mm.tag/value "tag-a"}]}])

  (let [report (audit/closure-consistency)
        cycles (get-in report [:violations :broader-generic-cycles])]
    (is (= :closure-consistency (:invariant report)))
    (is (pos? (count cycles))
        "should detect the 3-tag broader-generic cycle")))

(deftest closure-consistency-detects-related-asymmetry
  ;; A :related B but B does NOT :related A
  (tx [(make-tag "rel-a")
       (make-tag "rel-b")])
  (tx [{:mm.tag/value "rel-a"
        :mm.tag/related [{:mm.tag/value "rel-b"}]}])

  (let [report (audit/closure-consistency)
        asymm  (get-in report [:violations :related-asymmetries])]
    (is (pos? (count asymm))
        "should detect the A→B but not B→A related-asymmetry")
    (let [pair (first asymm)]
      (is (string? (:a pair)))
      (is (string? (:b pair))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aggregator — audit-all

(deftest audit-all-aggregates-all-invariants
  (tx [(make-memory :test/aggregator :decision ["bare-used-tag"])
       (make-tag "defined-but-unused" {:mm.tag/definition "Hello."})
       (make-tag "2026-05-12")
       (make-tag "decision")  ; type-pattern match
       (make-tag "tag")
       (make-tag "tags")])    ; drift cluster

  (let [report (audit/audit-all)]
    (is (= 7 (count (:invariants report))) "seven invariants run")
    (is (pos? (:total-violations report)) "violations found across the seven")
    (is (string? (:summary report)))
    (is (re-find #"violations" (:summary report)))
    ;; Each invariant report carries the expected keys
    (doseq [r (:invariants report)]
      (is (contains? r :invariant))
      (is (contains? r :violation-count))
      (is (contains? r :violations))
      (is (contains? r :description)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Empty-DB behavior — invariants don't crash on no tags

(deftest invariants-handle-empty-tag-corpus
  ;; No tags transacted in this test's fresh DB.
  (let [report (audit/audit-all)]
    (is (= 7 (count (:invariants report))))
    (is (zero? (:total-violations report))
        "empty corpus → zero violations")))
