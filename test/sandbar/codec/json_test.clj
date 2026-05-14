(ns sandbar.codec.json-test
  "Tests for sandbar.codec.json — Stage C JSON codec.

   Single-entity + entity-array round-trip; class-aware keyword
   coercion; mediator dispatch via :format / mime.

   Phase U Stage U-1 refactor (per UR-2 substrate-discipline fix):
   the codec now reads `:dt/codec-aliases` from class entities at
   runtime via `dt/codec-aliases-of` + detects keyword-typed slots
   via `dt/range-of` (no hardcoded substrate-side maps), so these
   tests require a real metamodel — uses `tu/make-test-db-fixture`
   to load `schema/*.edn` including `schema/mm.edn`.  Mirrors the
   `sandbar.codec.markdown-test` fixture contract."
  (:require [clojure.test           :refer :all]
            [cheshire.core          :as ch]
            [sandbar.codec          :as codec]
            [sandbar.codec.json     :as cjson]
            [sandbar.codec.protocol :as proto]
            [sandbar.test-util      :as tu]))

(use-fixtures :each
  (tu/make-test-db-fixture {:test-name "codec-json-test"})
  (fn [t]
    (codec/clear-all!)
    (try (t) (finally (codec/clear-all!)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parse — single entity

(deftest parse-single-entity-with-class-name
  (let [c (cjson/make-codec)
        src (ch/generate-string {"_class" "mm/Memory"
                                  "name"   "Foo"
                                  "type"   "decision"
                                  "scope"  "global"
                                  "body-raw" "# Body\n"})
        result (proto/parse c src {})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "Foo"     (:mm.memory/name result)))
    (is (= :decision (:mm.memory/memory-type result)))
    (is (= :global   (:mm.memory/scope result)))
    (is (= "# Body\n" (:mm.memory/body-raw result)))))

(deftest parse-accepts-colon-prefixed-class
  (let [c (cjson/make-codec)
        src (ch/generate-string {"_class" ":mm/Memory" "name" "X"})
        result (proto/parse c src {})]
    (is (= :mm/Memory (:dt/type result)))))

(deftest parse-rejects-missing-class
  (let [c (cjson/make-codec)
        src (ch/generate-string {"name" "Foo"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"_class"
          (proto/parse c src {})))))

(deftest parse-accepts-legacy-dt-type-field
  (let [c (cjson/make-codec)
        src (ch/generate-string {"_dt/type" "mm/Memory" "name" "X"})
        result (proto/parse c src {})]
    (is (= :mm/Memory (:dt/type result)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Emit — single entity

(deftest emit-single-entity
  (let [c (cjson/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/name "Foo"
                :mm.memory/memory-type :decision
                :mm.memory/scope :global}
        emitted (proto/emit c entity {})
        parsed  (ch/parse-string emitted)]
    (is (= "mm/Memory" (get parsed "_class")))
    (is (= "Foo"       (get parsed "name")))
    (is (= "decision"  (get parsed "type"))
        "memory-type slot emits back as `type` JSON key per alias")
    (is (= "global"    (get parsed "scope")))))

(deftest emit-rejects-missing-dt-type
  (let [c (cjson/make-codec)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":dt/type"
          (proto/emit c {:mm.memory/name "Orphan"} {})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip — single entity

(deftest round-trip-mm-memory-keyword-slots
  (let [c (cjson/make-codec)
        entity {:dt/type :mm/Memory
                :mm.memory/name "Foo"
                :mm.memory/memory-type :decision
                :mm.memory/scope :global
                :mm.memory/status :active
                :mm.memory/body-raw "# Body\n"}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "round-trip diff: " (pr-str (:diff result))))))

(deftest round-trip-section
  (let [c (cjson/make-codec)
        entity {:dt/type :mm/Section
                :mm.section/heading "Context"
                :mm.section/heading-level 2
                :mm.section/parent :decisions/foo
                :mm.section/body "Section body."}
        result (proto/round-trip-test c entity)]
    (is (true? (:ok? result))
        (str "section round-trip diff: " (pr-str (:diff result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Array shape — multi-entity

(deftest parse-array-of-entities
  (let [c (cjson/make-codec)
        src (ch/generate-string [{"_class" "mm/Memory" "name" "Foo"}
                                  {"_class" "mm/Section" "heading" "A"}])
        result (proto/parse c src {})]
    (is (sequential? result))
    (is (= 2 (count result)))
    (is (= :mm/Memory  (-> result first :dt/type)))
    (is (= :mm/Section (-> result second :dt/type)))))

(deftest emit-array-of-entities
  (let [c (cjson/make-codec)
        entities [{:dt/type :mm/Memory  :mm.memory/name "Foo"}
                  {:dt/type :mm/Section :mm.section/heading "A"}]
        emitted (proto/emit c entities {})
        parsed  (ch/parse-string emitted)]
    (is (sequential? parsed))
    (is (= 2 (count parsed)))
    (is (= "mm/Memory"  (-> parsed first  (get "_class"))))
    (is (= "mm/Section" (-> parsed second (get "_class"))))))

(deftest round-trip-array
  (let [c (cjson/make-codec)
        entities [{:dt/type :mm/Memory :mm.memory/name "Foo"
                   :mm.memory/memory-type :decision}
                  {:dt/type :mm/Section :mm.section/heading "Context"
                   :mm.section/heading-level 2}]
        result (proto/round-trip-test c entities)]
    (is (true? (:ok? result))
        (str "array round-trip diff: " (pr-str (:diff result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mediator integration

(deftest register-and-parse-via-mediator
  (cjson/register!)
  (let [src (ch/generate-string {"_class" "mm/Memory" "name" "Foo"})
        result (codec/parse src {:format :json})]
    (is (= :mm/Memory (:dt/type result)))
    (is (= "Foo" (:mm.memory/name result)))))

(deftest mime-types-include-application-json
  (let [c (cjson/make-codec)
        mts (proto/mime-types c)]
    (is (some #{"application/json"} mts))))

(deftest mediator-dispatches-via-mime
  (cjson/register!)
  (let [[fmt _codec] (codec/codec-for-mime "application/json")]
    (is (= :json fmt))))
