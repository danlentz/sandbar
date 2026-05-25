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

(use-fixtures :each (tu/make-test-db-fixture {:test-name "db-fn-test"}))

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

(use-fixtures :each with-fresh-fn-bases)


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
      (is (= "fn" (:mm.memory/memory-type memorial)))
      (is (= "project" (:mm.memory/scope memorial))))))


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
