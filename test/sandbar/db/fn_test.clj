(ns sandbar.db.fn-test
  "Tests for sandbar.db.fn — the defdbfn macro, dual-emit, install-as
   conditional, and :mm/Fn memorial round-trip.

   Per interaction/verification_is_tests_memorialized_not_repl_verification_2026_05_23.md
   — verification is tests + memorialization, not REPL verification.

   Covers Stage D D2 of the first-class :dt/Fn integration arc 2026-05-23."
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.db.fn :as fn]
            [sandbar.test-util :as tu]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-test fn-base + memorial-base isolation
;;
;; The defdbfn macro mutates two global atoms (*fn-base* + *mm-fn-memorial-base*)
;; via `new-dbfn` + `new-mm-fn-memorial`.  Tests must save + restore them so
;; each test sees a clean slate.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn with-fresh-fn-bases [test-fn]
  (let [saved-fn-base       @fn/*fn-base*
        saved-memorial-base @fn/*mm-fn-memorial-base*]
    (fn/clear-fnbase!)
    (fn/clear-mm-fn-memorial-base!)
    (try
      (test-fn)
      (finally
        (fn/set-fnbase saved-fn-base)
        (fn/set-mm-fn-base saved-memorial-base)))))

;; NOTE: combined fixture call (test-DB + fresh-fn-bases) — clojure.test's
;; `use-fixtures :each` REPLACES the fixture vector on each invocation
;; (alter-meta! assoc ::each-fixtures), so two standalone calls silently
;; cause only the LAST to run.  The existing 9 deftests above don't expose
;; this because they only check in-memory atoms (no DB needed); the new
;; load-all-mm-fn-memorials-integration-test below surfaced the latent bug
;; (it tries to d/connect to the test-DB which was never created without
;; the make-test-db-fixture firing).  Fix per `foundational_substrate_-
;; concerns_are_never_follow_up_sub_arcs_2026_05_21` — investigate-to-root-
;; cause + fix concretely, not defer.  Pre-0.2.0 release arc Phase β.0.
(use-fixtures :each
              (tu/make-test-db-fixture {:test-name "db-fn-test"})
              with-fresh-fn-bases)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Macro expansion + ordinary-fn behavior
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(fn/defdbfn test-add [db x y]
  {:dt.fn/purpose      :transform
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :db-fn}
  [[:db/add 0 :test/value (+ x y)]])

(deftest defdbfn-produces-callable-fn
  (testing "defdbfn defines a var that can be called as ordinary Clojure"
    (is (var? #'test-add))
    (is (= [[:db/add 0 :test/value 5]] (test-add :dummy-db 2 3))
        "The defn-bound fn is callable like any other fn")))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dual-emit: :db-fn install-as emits BOTH schema entity AND memorial
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest defdbfn-db-fn-mode-dual-emits
  (testing ":installed-as :db-fn emits BOTH schema entity + memorial"
    (fn/defdbfn dual-emit-test [db e v]
      {:dt.fn/installed-as :db-fn
       :dt.fn/purpose      :transform}
      [[:db/add e :test/v v]])
    (let [schemas    (fn/all-dbfn)
          memorials  (fn/all-mm-fn-memorials)
          schema-fn  (first (filter #(= :dual-emit-test (:db/ident %)) schemas))
          memorial   (first (filter #(= :dual-emit-test (:db/ident %)) memorials))]
      (is (some? schema-fn) "Schema :db/fn entity should be queued")
      (is (= :fn (:dt/dt schema-fn)) "Schema entity carries legacy :dt/dt :fn tag")
      (is (some? memorial) ":mm/Fn memorial should be queued")
      (is (= :mm/Fn (:dt/type memorial)) "Memorial carries first-class :dt/type :mm/Fn"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Single-emit: :classpath-fn install-as emits ONLY memorial
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest defdbfn-classpath-fn-mode-single-emits
  (testing ":installed-as :classpath-fn emits ONLY memorial (no schema entity)"
    (fn/defdbfn classpath-emit-test [db e]
      {:dt.fn/installed-as :classpath-fn
       :dt.fn/purpose      :validate}
      {:status :pass})
    (let [schemas   (fn/all-dbfn)
          memorials (fn/all-mm-fn-memorials)
          schema-fn (first (filter #(= :classpath-emit-test (:db/ident %)) schemas))
          memorial  (first (filter #(= :classpath-emit-test (:db/ident %)) memorials))]
      (is (nil? schema-fn) "NO schema :db/fn entity emitted for :classpath-fn mode")
      (is (some? memorial) ":mm/Fn memorial IS emitted for :classpath-fn mode")
      (is (= :classpath-fn (:dt.fn/installed-as memorial))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Attr-map extraction + default population
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest defdbfn-applies-default-attrs-when-omitted
  (testing "When attr-map is omitted, sane defaults are applied"
    (fn/defdbfn no-attr-map-fn [db x] [[:db/add 0 :test/x x]])
    (let [memorial (first (filter #(= :no-attr-map-fn (:db/ident %))
                                  (fn/all-mm-fn-memorials)))]
      (is (= :transform  (:dt.fn/purpose memorial))    "Default :purpose :transform")
      (is (= :pure-total (:dt.fn/purity memorial))     "Default :purity :pure-total")
      (is (= :cheap      (:dt.fn/cost-class memorial)) "Default :cost-class :cheap")
      (is (= :db-fn      (:dt.fn/installed-as memorial)) "Default :installed-as :db-fn")
      (is (= :clojure    (:dt.fn/lang memorial))       "Default :lang :clojure")
      (is (= "1.0.0"     (:dt.fn/version memorial))    "Default :version 1.0.0"))))

(deftest defdbfn-explicit-attrs-override-defaults
  (testing "Explicit attr-map values override the defaults"
    (fn/defdbfn explicit-attr-fn [db x]
      {:dt.fn/purpose     :validate
       :dt.fn/purity      :pure-partial
       :dt.fn/cost-class  :expensive
       :dt.fn/version     "2.5.1"}
      [[:db/add 0 :test/x x]])
    (let [memorial (first (filter #(= :explicit-attr-fn (:db/ident %))
                                  (fn/all-mm-fn-memorials)))]
      (is (= :validate     (:dt.fn/purpose memorial)))
      (is (= :pure-partial (:dt.fn/purity memorial)))
      (is (= :expensive    (:dt.fn/cost-class memorial)))
      (is (= "2.5.1"       (:dt.fn/version memorial))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Source-of-truth metadata capture (gen-fn pattern)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest defdbfn-captures-source-ns-and-var
  (testing ":dt.fn/source-ns + :dt.fn/source-var are captured at macro-expansion time"
    (fn/defdbfn source-tracked-fn [db x] [[:db/add 0 :test/x x]])
    (let [memorial (first (filter #(= :source-tracked-fn (:db/ident %))
                                  (fn/all-mm-fn-memorials)))]
      (is (= "sandbar.db.fn-test"   (:dt.fn/source-ns memorial))
          "Source ns matches the calling namespace")
      (is (= "source-tracked-fn"    (:dt.fn/source-var memorial))
          "Source var matches the symbol name"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Memorial structure invariants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest memorial-carries-expected-mm-memory-slots
  (testing "Memorial includes :mm.memory/* slots required for FS-projection"
    (fn/defdbfn projection-test-fn [db x] [[:db/add 0 :test/x x]])
    (let [memorial (first (filter #(= :projection-test-fn (:db/ident %))
                                  (fn/all-mm-fn-memorials)))]
      (is (= "fns/projection-test-fn.md" (:mm.memory/rel-path memorial))
          "rel-path follows the memory/fns/<slug>.md convention")
      (is (= "projection-test-fn" (:mm.memory/name memorial)))
      ;; KEYWORD values per schema/mm.edn L731-745 (:db.type/keyword for both).
      ;; Prior assertions pinned string values which were a latent substrate
      ;; bug in build-mm-fn-memorial — see fn.clj's :mm.memory/memory-type
      ;; comment block.  Fixed in pre-0.2.0 β.0 (load-all-mm-fn-memorials
      ;; wire-up surfaced the schema-type mismatch on first transact).
      (is (= :fn (:mm.memory/memory-type memorial)))
      (is (= :project (:mm.memory/scope memorial))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Load-helper semantics (clear / set / read)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest clear-mm-fn-memorial-base-empties-atom
  (testing "clear-mm-fn-memorial-base! resets to empty"
    (fn/defdbfn dummy-1 [db] [])
    (is (pos? (count (fn/all-mm-fn-memorials))))
    (fn/clear-mm-fn-memorial-base!)
    (is (empty? (fn/all-mm-fn-memorials)))))

(deftest new-mm-fn-memorial-appends
  (testing "new-mm-fn-memorial appends to the atom"
    (fn/clear-mm-fn-memorial-base!)
    (let [m1 {:db/ident :test-m1 :dt/type :mm/Fn}
          m2 {:db/ident :test-m2 :dt/type :mm/Fn}]
      (fn/new-mm-fn-memorial m1)
      (fn/new-mm-fn-memorial m2)
      (is (= 2 (count (fn/all-mm-fn-memorials))))
      (is (= [m1 m2] (fn/all-mm-fn-memorials))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Live-DB integration — load-all-mm-fn-memorials transacts queued memorials
;;
;; Closes the Wave 4 follow-up gap (per memory.observations/wave_4_…/§7 item #1
;; + pre-0.2.0 release arc plan §3.β.0): the load-all-mm-fn-memorials substrate
;; primitive (fn.clj L137-146) is now wired into initialize-db! (datomic.clj
;; L148), so :mm/Fn memorials emitted by defdbfn callsites transact into
;; Datomic at boot.  This test verifies the live-DB round-trip:
;;
;;   defdbfn  →  memorial in *mm-fn-memorial-base*  →  load-all-mm-fn-memorials
;;            →  :mm/Fn entity queryable via d/entity
;;
;; The test is self-contained (defines its own defdbfn inside the test body;
;; doesn't depend on shape.clj's 8 production callsites, which the
;; with-fresh-fn-bases fixture would have cleared anyway).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest load-all-mm-fn-memorials-integration-test
  (testing "load-all-mm-fn-memorials transacts queued :mm/Fn memorials + entity is queryable"
    (fn/defdbfn integration-test-fn [db x]
      {:dt.fn/purpose      :validate
       :dt.fn/purity       :pure-total
       :dt.fn/cost-class   :cheap
       :dt.fn/installed-as :classpath-fn
       :dt.fn/description  "Test fn for load-all-mm-fn-memorials integration test."
       :dt.fn/version      "1.0.0"}
      {:status :pass :x x})
    (let [uri "datomic:mem://db-fn-test"]
      ;; Memorial is queued in the atom (defdbfn evaluated above; fixture cleared bases)
      (is (some #(= :integration-test-fn (:db/ident %))
                (fn/all-mm-fn-memorials))
          "integration-test-fn memorial is queued in *mm-fn-memorial-base*")
      ;; Transact via load-all-mm-fn-memorials (the wire-up point in initialize-db!).
      ;; Deref the d/transact future — load-all-mm-fn-memorials returns it un-deref'd
      ;; (fire-and-forget for the boot path where no immediate read follows); tests
      ;; verifying the post-tx state must force-wait via @.  Substrate-semantics
      ;; question: should load-all-mm-fn-memorials deref internally?  Deferred
      ;; consideration; current behavior is consistent with d/transact's contract.
      @(fn/load-all-mm-fn-memorials uri)
      ;; Query DB to verify the :mm/Fn entity is now first-class
      (let [conn   (d/connect uri)
            entity (d/entity (d/db conn) :integration-test-fn)]
        (is (some? entity)
            ":integration-test-fn entity exists in DB after load-all-mm-fn-memorials")
        (is (= :mm/Fn (:dt/type entity))
            "Entity has :dt/type :mm/Fn (first-class memorial)")
        (is (= :validate (:dt.fn/purpose entity))
            ":dt.fn/purpose preserved through transact")
        (is (= "1.0.0" (:dt.fn/version entity))
            ":dt.fn/version preserved through transact")
        (is (= :classpath-fn (:dt.fn/installed-as entity))
            ":dt.fn/installed-as preserved through transact")
        (is (= "sandbar.db.fn-test" (:dt.fn/source-ns entity))
            ":dt.fn/source-ns captured at macro-expansion time + preserved through transact")))))
