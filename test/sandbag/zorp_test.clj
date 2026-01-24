(ns sandbag.zorp-test
  "Test suite for Zorp's Galactic Footwear Emporium ontology.
   Tests the example from doc/example-footwear.adoc
   Schema defined in schema/zorp.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbag.db.datatype :as dt]
            [sandbag.db.datomic :as db]
            [sandbag.util.edn :as edn]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Fixtures
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-uri "datomic:mem://zorp-test")

(defn load-schema
  "Load schema files into test database"
  [conn schema-names]
  (doseq [schema-name schema-names]
    (doseq [stmt (edn/resource-value schema-name nil)]
      @(d/transact conn stmt))))

(defn with-zorp-db
  "Fixture that creates an in-memory database with base + Zorp's schema"
  [f]
  (d/create-database test-uri)
  (let [conn (d/connect test-uri)]
    (reset! db/**conn* conn)
    (try
      ;; Load base schema plus zorp schema
      (load-schema conn (conj (vec (edn/config-value :required-schema)) :zorp))
      (f)
      (finally
        (reset! db/**conn* nil)
        (d/delete-database test-uri)))))

(use-fixtures :each with-zorp-db)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest footwear-class-hierarchy-test
  (testing "Footwear class exists and is abstract"
    (is (some? (db/entity :zorp/Footwear)) "Footwear class should exist")
    (is (= :dt/Class (dt/class-of :zorp/Footwear)) "Footwear should be a Class")
    (is (dt/abstract? :zorp/Footwear) "Footwear should be abstract"))

  (testing "Footwear inherits from dt/Ref"
    (is (= #{:dt/Ref} (set (dt/parents-of :zorp/Footwear)))
        "Footwear should have dt/Ref as parent"))

  (testing "Footwear ancestors include dt/Resource"
    (let [ancestors (set (dt/ancestors-of :zorp/Footwear))]
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest sneaker-hierarchy-test
  (testing "Sneaker class hierarchy"
    (is (= #{:zorp/Footwear} (set (dt/parents-of :zorp/Sneaker)))
        "Sneaker parent should be Footwear")
    (let [ancestors (set (dt/ancestors-of :zorp/Sneaker))]
      (is (contains? ancestors :zorp/Footwear) "Should include Footwear")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource")))

  (testing "HighTop and LowTop inherit from Sneaker"
    (is (= #{:zorp/Sneaker} (set (dt/parents-of :zorp/HighTop)))
        "HighTop parent should be Sneaker")
    (is (= #{:zorp/Sneaker} (set (dt/parents-of :zorp/LowTop)))
        "LowTop parent should be Sneaker"))

  (testing "HighTop full ancestor chain"
    (let [ancestors (set (dt/ancestors-of :zorp/HighTop))]
      (is (contains? ancestors :zorp/Sneaker) "Should include Sneaker")
      (is (contains? ancestors :zorp/Footwear) "Should include Footwear")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest boot-hierarchy-test
  (testing "Boot class hierarchy"
    (is (= #{:zorp/Footwear} (set (dt/parents-of :zorp/Boot)))
        "Boot parent should be Footwear"))

  (testing "SpaceBoot and MoonBoot inherit from Boot"
    (is (= #{:zorp/Boot} (set (dt/parents-of :zorp/SpaceBoot)))
        "SpaceBoot parent should be Boot")
    (is (= #{:zorp/Boot} (set (dt/parents-of :zorp/MoonBoot)))
        "MoonBoot parent should be Boot")))

(deftest sandal-hierarchy-test
  (testing "Sandal class hierarchy"
    (is (= #{:zorp/Footwear} (set (dt/parents-of :zorp/Sandal)))
        "Sandal parent should be Footwear"))

  (testing "FlipFlop inherits from Sandal"
    (is (= #{:zorp/Sandal} (set (dt/parents-of :zorp/FlipFlop)))
        "FlipFlop parent should be Sandal"))

  (testing "FlipFlop full ancestor chain"
    (let [ancestors (set (dt/ancestors-of :zorp/FlipFlop))]
      (is (contains? ancestors :zorp/Sandal) "Should include Sandal")
      (is (contains? ancestors :zorp/Footwear) "Should include Footwear")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest subclass-relationships-test
  (testing "subclasses-of returns all transitive subclasses"
    (let [footwear-subclasses (set (dt/subclasses-of :zorp/Footwear))]
      (is (contains? footwear-subclasses :zorp/Sneaker) "Should include Sneaker")
      (is (contains? footwear-subclasses :zorp/Boot) "Should include Boot")
      (is (contains? footwear-subclasses :zorp/Sandal) "Should include Sandal")
      (is (contains? footwear-subclasses :zorp/HighTop) "Should include HighTop")
      (is (contains? footwear-subclasses :zorp/LowTop) "Should include LowTop")
      (is (contains? footwear-subclasses :zorp/SpaceBoot) "Should include SpaceBoot")
      (is (contains? footwear-subclasses :zorp/MoonBoot) "Should include MoonBoot")
      (is (contains? footwear-subclasses :zorp/FlipFlop) "Should include FlipFlop")))

  (testing "direct-subclasses-of returns immediate subclasses only"
    (let [footwear-direct (set (dt/direct-subclasses-of :zorp/Footwear))]
      (is (contains? footwear-direct :zorp/Sneaker) "Should include Sneaker")
      (is (contains? footwear-direct :zorp/Boot) "Should include Boot")
      (is (contains? footwear-direct :zorp/Sandal) "Should include Sandal")
      (is (not (contains? footwear-direct :zorp/HighTop)) "Should NOT include HighTop")
      (is (not (contains? footwear-direct :zorp/FlipFlop)) "Should NOT include FlipFlop")))

  (testing "subclass-of? predicate"
    (is (dt/subclass-of? :zorp/Footwear :zorp/Sneaker) "Sneaker is subclass of Footwear")
    (is (dt/subclass-of? :zorp/Footwear :zorp/HighTop) "HighTop is subclass of Footwear")
    (is (dt/subclass-of? :zorp/Sneaker :zorp/HighTop) "HighTop is subclass of Sneaker")
    (is (dt/subclass-of? :dt/Resource :zorp/FlipFlop) "FlipFlop is subclass of dt/Resource")
    (is (not (dt/subclass-of? :zorp/Sneaker :zorp/Boot)) "Boot is NOT subclass of Sneaker")
    (is (not (dt/subclass-of? :zorp/FlipFlop :zorp/Sneaker)) "Sneaker is NOT subclass of FlipFlop")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property/Slot Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest footwear-properties-test
  (testing "Footwear properties exist"
    (is (some? (db/entity :footwear/name)) "footwear/name should exist")
    (is (some? (db/entity :footwear/size)) "footwear/size should exist")
    (is (some? (db/entity :footwear/color)) "footwear/color should exist")
    (is (some? (db/entity :footwear/tentacle-count)) "footwear/tentacle-count should exist")
    (is (some? (db/entity :footwear/gravity-rating)) "footwear/gravity-rating should exist")
    (is (some? (db/entity :footwear/price)) "footwear/price should exist")
    (is (some? (db/entity :footwear/sentient?)) "footwear/sentient? should exist"))

  (testing "Footwear property domains"
    (is (= :zorp/Footwear (dt/domain-of :footwear/name)) "footwear/name domain")
    (is (= :zorp/Footwear (dt/domain-of :footwear/tentacle-count)) "footwear/tentacle-count domain")
    (is (= :zorp/Footwear (dt/domain-of :footwear/sentient?)) "footwear/sentient? domain"))

  (testing "Footwear property ranges"
    (is (= :db.type/string (dt/range-of :footwear/name)) "footwear/name range")
    (is (= :db.type/long (dt/range-of :footwear/tentacle-count)) "footwear/tentacle-count range")
    (is (= :db.type/double (dt/range-of :footwear/gravity-rating)) "footwear/gravity-rating range")
    (is (= :db.type/bigdec (dt/range-of :footwear/price)) "footwear/price range")
    (is (= :db.type/boolean (dt/range-of :footwear/sentient?)) "footwear/sentient? range")))

(deftest sneaker-properties-test
  (testing "Sneaker-specific properties exist"
    (is (some? (db/entity :sneaker/bounce-factor)) "sneaker/bounce-factor should exist")
    (is (some? (db/entity :sneaker/glow-in-dark?)) "sneaker/glow-in-dark? should exist")
    (is (some? (db/entity :sneaker/squeak-volume)) "sneaker/squeak-volume should exist")
    (is (some? (db/entity :sneaker/lace-type)) "sneaker/lace-type should exist")
    (is (some? (db/entity :sneaker/air-pump?)) "sneaker/air-pump? should exist"))

  (testing "Sneaker property domains"
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/bounce-factor)) "sneaker/bounce-factor domain")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/glow-in-dark?)) "sneaker/glow-in-dark? domain")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/squeak-volume)) "sneaker/squeak-volume domain")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/lace-type)) "sneaker/lace-type domain")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/air-pump?)) "sneaker/air-pump? domain"))

  (testing "Sneaker property ranges"
    (is (= :db.type/double (dt/range-of :sneaker/bounce-factor)) "sneaker/bounce-factor range")
    (is (= :db.type/boolean (dt/range-of :sneaker/glow-in-dark?)) "sneaker/glow-in-dark? range")
    (is (= :db.type/long (dt/range-of :sneaker/squeak-volume)) "sneaker/squeak-volume range")
    (is (= :db.type/string (dt/range-of :sneaker/lace-type)) "sneaker/lace-type range")
    (is (= :db.type/boolean (dt/range-of :sneaker/air-pump?)) "sneaker/air-pump? range")))

(deftest boot-properties-test
  (testing "Boot-specific properties"
    (is (some? (db/entity :boot/vacuum-rated?)) "boot/vacuum-rated? should exist")
    (is (some? (db/entity :boot/temperature-range)) "boot/temperature-range should exist")
    (is (= :zorp/Boot (dt/domain-of :boot/vacuum-rated?)) "boot/vacuum-rated? domain")
    (is (= :zorp/Boot (dt/domain-of :boot/temperature-range)) "boot/temperature-range domain")))

(deftest flipflop-properties-test
  (testing "FlipFlop-specific properties exist"
    (is (some? (db/entity :flipflop/flop-frequency)) "flipflop/flop-frequency should exist")
    (is (some? (db/entity :flipflop/toe-separator-count)) "flipflop/toe-separator-count should exist")
    (is (some? (db/entity :flipflop/escape-velocity)) "flipflop/escape-velocity should exist")
    (is (some? (db/entity :flipflop/mood)) "flipflop/mood should exist"))

  (testing "FlipFlop property domains"
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/flop-frequency)) "flipflop/flop-frequency domain")
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/toe-separator-count)) "flipflop/toe-separator-count domain")
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/escape-velocity)) "flipflop/escape-velocity domain")
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/mood)) "flipflop/mood domain"))

  (testing "FlipFlop property ranges"
    (is (= :db.type/double (dt/range-of :flipflop/flop-frequency)) "flipflop/flop-frequency range")
    (is (= :db.type/long (dt/range-of :flipflop/toe-separator-count)) "flipflop/toe-separator-count range")
    (is (= :db.type/double (dt/range-of :flipflop/escape-velocity)) "flipflop/escape-velocity range")
    (is (= :db.type/string (dt/range-of :flipflop/mood)) "flipflop/mood range")))

(deftest slot-inheritance-test
  (testing "HighTop inherits all slots from Sneaker and Footwear"
    (let [slots (dt/slots-of :zorp/HighTop)]
      ;; From dt/Resource
      (is (contains? slots :dt/type) "Should have dt/type from Resource")
      (is (contains? slots :db/ident) "Should have db/ident from Resource")
      ;; From Footwear
      (is (contains? slots :footwear/name) "Should have footwear/name")
      (is (contains? slots :footwear/size) "Should have footwear/size")
      (is (contains? slots :footwear/color) "Should have footwear/color")
      (is (contains? slots :footwear/tentacle-count) "Should have footwear/tentacle-count")
      (is (contains? slots :footwear/gravity-rating) "Should have footwear/gravity-rating")
      (is (contains? slots :footwear/price) "Should have footwear/price")
      (is (contains? slots :footwear/sentient?) "Should have footwear/sentient?")
      ;; From Sneaker
      (is (contains? slots :sneaker/bounce-factor) "Should have sneaker/bounce-factor")
      (is (contains? slots :sneaker/glow-in-dark?) "Should have sneaker/glow-in-dark?")
      (is (contains? slots :sneaker/squeak-volume) "Should have sneaker/squeak-volume")
      (is (contains? slots :sneaker/lace-type) "Should have sneaker/lace-type")
      (is (contains? slots :sneaker/air-pump?) "Should have sneaker/air-pump?")))

  (testing "FlipFlop inherits from Sandal and Footwear, adds its own"
    (let [slots (dt/slots-of :zorp/FlipFlop)]
      ;; From Footwear
      (is (contains? slots :footwear/name) "Should have footwear/name")
      (is (contains? slots :footwear/sentient?) "Should have footwear/sentient?")
      ;; FlipFlop's own slots
      (is (contains? slots :flipflop/flop-frequency) "Should have flipflop/flop-frequency")
      (is (contains? slots :flipflop/toe-separator-count) "Should have flipflop/toe-separator-count")
      (is (contains? slots :flipflop/escape-velocity) "Should have flipflop/escape-velocity")
      (is (contains? slots :flipflop/mood) "Should have flipflop/mood")
      ;; Should NOT have sneaker or boot slots
      (is (not (contains? slots :sneaker/bounce-factor)) "Should NOT have sneaker/bounce-factor")
      (is (not (contains? slots :boot/vacuum-rated?)) "Should NOT have boot/vacuum-rated?")))

  (testing "SpaceBoot inherits boot slots"
    (let [slots (dt/slots-of :zorp/SpaceBoot)]
      (is (contains? slots :boot/vacuum-rated?) "Should have boot/vacuum-rated?")
      (is (contains? slots :boot/temperature-range) "Should have boot/temperature-range")
      (is (contains? slots :footwear/name) "Should have footwear/name")
      (is (not (contains? slots :sneaker/bounce-factor)) "Should NOT have sneaker/bounce-factor"))))

(deftest direct-slots-test
  (testing "direct-slots-of returns only directly declared slots"
    (let [sneaker-direct (dt/direct-slots-of :zorp/Sneaker)
          sneaker-slot-idents (set (map #(:db/ident (db/entity %)) sneaker-direct))]
      (is (contains? sneaker-slot-idents :sneaker/bounce-factor) "Should have bounce-factor")
      (is (contains? sneaker-slot-idents :sneaker/glow-in-dark?) "Should have glow-in-dark?")
      (is (contains? sneaker-slot-idents :sneaker/squeak-volume) "Should have squeak-volume")
      (is (contains? sneaker-slot-idents :sneaker/lace-type) "Should have lace-type")
      (is (contains? sneaker-slot-idents :sneaker/air-pump?) "Should have air-pump?")
      (is (not (contains? sneaker-slot-idents :footwear/name)) "Should NOT have footwear/name"))

    (let [flipflop-direct (dt/direct-slots-of :zorp/FlipFlop)
          flipflop-slot-idents (set (map #(:db/ident (db/entity %)) flipflop-direct))]
      (is (contains? flipflop-slot-idents :flipflop/flop-frequency) "Should have flop-frequency")
      (is (contains? flipflop-slot-idents :flipflop/mood) "Should have mood")
      (is (not (contains? flipflop-slot-idents :footwear/name)) "Should NOT have footwear/name"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Creation Tests (dt/make)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-hightop-test
  (testing "Create Anti-Gravity Dunks 3000"
    (let [dunks (dt/make :zorp/HighTop
                  {:footwear/name "Anti-Gravity Dunks 3000"
                   :footwear/size "7-tentacle"
                   :footwear/color "Ultraviolet Sparkle"
                   :footwear/tentacle-count 7
                   :footwear/gravity-rating 0.063
                   :footwear/price 299.99M
                   :footwear/sentient? false
                   :sneaker/bounce-factor 47.5
                   :sneaker/glow-in-dark? true
                   :sneaker/squeak-volume 45
                   :sneaker/lace-type "tentacle-knot"
                   :sneaker/air-pump? true})]
      (is (some? dunks) "Should create entity")
      (is (= :zorp/HighTop (:dt/type dunks)) "Should have correct type")
      (is (= "Anti-Gravity Dunks 3000" (:footwear/name dunks)) "Should have name")
      (is (= 7 (:footwear/tentacle-count dunks)) "Should have tentacle count")
      (is (= 47.5 (:sneaker/bounce-factor dunks)) "Should have bounce factor")
      (is (true? (:sneaker/glow-in-dark? dunks)) "Should glow in dark")
      (is (= 45 (:sneaker/squeak-volume dunks)) "Should have squeak volume")
      (is (= "tentacle-knot" (:sneaker/lace-type dunks)) "Should have lace type")
      (is (true? (:sneaker/air-pump? dunks)) "Should have air pump"))))

(deftest create-lowtop-test
  (testing "Create Blob Runner Basics"
    (let [blob-runner (dt/make :zorp/LowTop
                        {:footwear/name "Blob Runner Basics"
                         :footwear/size "juvenile-blob"
                         :footwear/color "Transparent"
                         :footwear/tentacle-count 0
                         :footwear/gravity-rating 0.1
                         :footwear/price 49.99M
                         :footwear/sentient? false
                         :sneaker/bounce-factor 12.0
                         :sneaker/glow-in-dark? false
                         :sneaker/squeak-volume 0
                         :sneaker/lace-type "psychic"
                         :sneaker/air-pump? false})]
      (is (some? blob-runner) "Should create entity")
      (is (= :zorp/LowTop (:dt/type blob-runner)) "Should have correct type")
      (is (= 0 (:footwear/tentacle-count blob-runner)) "Blobs have no tentacles")
      (is (= "psychic" (:sneaker/lace-type blob-runner)) "Psychic laces for blobs")
      (is (= 0 (:sneaker/squeak-volume blob-runner)) "Silent for blobs"))))

(deftest create-spaceboot-test
  (testing "Create Void Walker Pro"
    (let [void-walker (dt/make :zorp/SpaceBoot
                        {:footwear/name "Void Walker Pro"
                         :footwear/size "5-tentacle"
                         :footwear/color "Infrared Black"
                         :footwear/tentacle-count 5
                         :footwear/gravity-rating 0.0
                         :footwear/price 1299.99M
                         :footwear/sentient? false
                         :boot/vacuum-rated? true
                         :boot/temperature-range "-270C to +150C"})]
      (is (some? void-walker) "Should create entity")
      (is (= :zorp/SpaceBoot (:dt/type void-walker)) "Should have correct type")
      (is (true? (:boot/vacuum-rated? void-walker)) "Should be vacuum rated")
      (is (= "-270C to +150C" (:boot/temperature-range void-walker)) "Should have temp range"))))

(deftest create-moonboot-test
  (testing "Create 1970s Earth Replica"
    (let [moonboot (dt/make :zorp/MoonBoot
                     {:footwear/name "1970s Earth Replica"
                      :footwear/size "earth-medium"
                      :footwear/color "Silver Metallic"
                      :footwear/tentacle-count 2
                      :footwear/gravity-rating 0.166
                      :footwear/price 89.99M
                      :footwear/sentient? false
                      :boot/vacuum-rated? false
                      :boot/temperature-range "-40C to +40C"})]
      (is (some? moonboot) "Should create entity")
      (is (= :zorp/MoonBoot (:dt/type moonboot)) "Should have correct type")
      (is (false? (:boot/vacuum-rated? moonboot)) "Retro moonboots not vacuum rated"))))

(deftest create-kevin-flipflop-test
  (testing "Create Kevin the sentient flip-flop"
    (let [kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Kevin"
                   :footwear/size "2-foot"
                   :footwear/color "Existential Dread Gray"
                   :footwear/tentacle-count 2
                   :footwear/gravity-rating 1.0
                   :footwear/price 19.99M
                   :footwear/sentient? true
                   :flipflop/flop-frequency 3.7
                   :flipflop/toe-separator-count 1
                   :flipflop/escape-velocity 8.2
                   :flipflop/mood "philosophical"})]
      (is (some? kevin) "Should create entity")
      (is (= :zorp/FlipFlop (:dt/type kevin)) "Should have correct type")
      (is (= "Kevin" (:footwear/name kevin)) "Should be named Kevin")
      (is (true? (:footwear/sentient? kevin)) "Kevin is sentient")
      (is (= 3.7 (:flipflop/flop-frequency kevin)) "Kevin flops pensively")
      (is (= 8.2 (:flipflop/escape-velocity kevin)) "Kevin can outrun customers")
      (is (= "philosophical" (:flipflop/mood kevin)) "Kevin ponders free will"))))

(deftest abstract-class-rejection-test
  (testing "Cannot instantiate abstract Footwear class"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Validation failed"
                          (dt/make :zorp/Footwear
                            {:footwear/name "Abstract Shoe"
                             :footwear/size "impossible"}))
        "Should reject abstract class instantiation")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Query Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest instance-of-predicate-test
  (testing "instance-of? with created products"
    (let [dunks (dt/make :zorp/HighTop
                  {:footwear/name "Test Dunks"
                   :footwear/size "test"
                   :footwear/tentacle-count 1})
          kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Test Kevin"
                   :footwear/size "test"
                   :flipflop/mood "testing"})]
      ;; HighTop instances
      (is (dt/instance-of? :zorp/HighTop dunks) "Dunks is instance of HighTop")
      (is (dt/instance-of? :zorp/Sneaker dunks) "Dunks is instance of Sneaker")
      (is (dt/instance-of? :zorp/Footwear dunks) "Dunks is instance of Footwear")
      (is (dt/instance-of? :dt/Ref dunks) "Dunks is instance of dt/Ref")
      (is (dt/instance-of? :dt/Resource dunks) "Dunks is instance of dt/Resource")
      (is (not (dt/instance-of? :zorp/Boot dunks)) "Dunks is NOT instance of Boot")
      (is (not (dt/instance-of? :zorp/FlipFlop dunks)) "Dunks is NOT instance of FlipFlop")

      ;; FlipFlop instances
      (is (dt/instance-of? :zorp/FlipFlop kevin) "Kevin is instance of FlipFlop")
      (is (dt/instance-of? :zorp/Sandal kevin) "Kevin is instance of Sandal")
      (is (dt/instance-of? :zorp/Footwear kevin) "Kevin is instance of Footwear")
      (is (not (dt/instance-of? :zorp/Sneaker kevin)) "Kevin is NOT instance of Sneaker"))))

(deftest all-instances-query-test
  (testing "all-instances-of returns instances including subclasses"
    ;; Create some products
    (dt/make :zorp/HighTop {:footwear/name "Instance Test 1" :footwear/size "1"})
    (dt/make :zorp/LowTop {:footwear/name "Instance Test 2" :footwear/size "2"})
    (dt/make :zorp/SpaceBoot {:footwear/name "Instance Test 3" :footwear/size "3"})
    (dt/make :zorp/FlipFlop {:footwear/name "Instance Test 4" :footwear/size "4"
                             :flipflop/mood "content"})

    (testing "Querying Footwear returns all products"
      (let [all-footwear (dt/all-instances-of :zorp/Footwear)
            names (set (map :footwear/name all-footwear))]
        (is (contains? names "Instance Test 1") "Should include HighTop")
        (is (contains? names "Instance Test 2") "Should include LowTop")
        (is (contains? names "Instance Test 3") "Should include SpaceBoot")
        (is (contains? names "Instance Test 4") "Should include FlipFlop")))

    (testing "Querying Sneaker returns only sneakers"
      (let [all-sneakers (dt/all-instances-of :zorp/Sneaker)
            names (set (map :footwear/name all-sneakers))]
        (is (contains? names "Instance Test 1") "Should include HighTop")
        (is (contains? names "Instance Test 2") "Should include LowTop")
        (is (not (contains? names "Instance Test 3")) "Should NOT include SpaceBoot")
        (is (not (contains? names "Instance Test 4")) "Should NOT include FlipFlop")))

    (testing "Querying Boot returns only boots"
      (let [all-boots (dt/all-instances-of :zorp/Boot)
            names (set (map :footwear/name all-boots))]
        (is (contains? names "Instance Test 3") "Should include SpaceBoot")
        (is (not (contains? names "Instance Test 1")) "Should NOT include HighTop")))))

(deftest direct-instances-query-test
  (testing "direct-instances-of excludes subclass instances"
    ;; Create instances at different levels
    (dt/make :zorp/HighTop {:footwear/name "Direct Test HighTop" :footwear/size "1"})
    (dt/make :zorp/LowTop {:footwear/name "Direct Test LowTop" :footwear/size "2"})

    (testing "Sneaker has no direct instances (all are HighTop/LowTop)"
      (let [direct-sneakers (dt/direct-instances-of :zorp/Sneaker)
            names (set (map :footwear/name direct-sneakers))]
        ;; HighTop and LowTop are subclasses, not direct instances
        (is (not (contains? names "Direct Test HighTop")) "HighTop is not direct Sneaker instance")
        (is (not (contains? names "Direct Test LowTop")) "LowTop is not direct Sneaker instance")))

    (testing "HighTop direct instances"
      (let [direct-hightops (dt/direct-instances-of :zorp/HighTop)
            names (set (map :footwear/name direct-hightops))]
        (is (contains? names "Direct Test HighTop") "Should include the HighTop")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation Tests
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest validate-footwear-instance-test
  (testing "Valid instances pass validation"
    (let [valid-product (dt/make :zorp/HighTop
                          {:footwear/name "Valid Product"
                           :footwear/size "standard"
                           :footwear/tentacle-count 2
                           :sneaker/bounce-factor 10.0})]
      (is (dt/valid? valid-product) "Valid product should pass validation"))))

(deftest class-type-checking-test
  (testing "Class types are correctly identified"
    (is (= :dt/Class (dt/class-of :zorp/Footwear)) "Footwear is a Class")
    (is (= :dt/Class (dt/class-of :zorp/Sneaker)) "Sneaker is a Class")
    (is (= :dt/Class (dt/class-of :zorp/FlipFlop)) "FlipFlop is a Class")
    (is (= :dt/Property (dt/class-of :footwear/name)) "footwear/name is a Property")
    (is (= :dt/Property (dt/class-of :sneaker/bounce-factor)) "sneaker/bounce-factor is a Property")
    (is (= :dt/Property (dt/class-of :flipflop/mood)) "flipflop/mood is a Property")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests - Full Scenarios
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest zorps-inventory-scenario-test
  (testing "Complete inventory scenario from the documentation"
    ;; Create the full inventory
    (let [dunks (dt/make :zorp/HighTop
                  {:footwear/name "Anti-Gravity Dunks 3000"
                   :footwear/size "7-tentacle"
                   :footwear/color "Ultraviolet Sparkle"
                   :footwear/tentacle-count 7
                   :footwear/gravity-rating 0.063
                   :footwear/price 299.99M
                   :footwear/sentient? false
                   :sneaker/bounce-factor 47.5
                   :sneaker/glow-in-dark? true
                   :sneaker/squeak-volume 45
                   :sneaker/lace-type "tentacle-knot"
                   :sneaker/air-pump? true})

          blob-runner (dt/make :zorp/LowTop
                        {:footwear/name "Blob Runner Basics"
                         :footwear/size "juvenile-blob"
                         :footwear/color "Transparent"
                         :footwear/tentacle-count 0
                         :footwear/gravity-rating 0.1
                         :footwear/price 49.99M
                         :footwear/sentient? false
                         :sneaker/bounce-factor 12.0
                         :sneaker/glow-in-dark? false
                         :sneaker/squeak-volume 0
                         :sneaker/lace-type "psychic"
                         :sneaker/air-pump? false})

          void-walker (dt/make :zorp/SpaceBoot
                        {:footwear/name "Void Walker Pro"
                         :footwear/size "5-tentacle"
                         :footwear/color "Infrared Black"
                         :footwear/tentacle-count 5
                         :footwear/gravity-rating 0.0
                         :footwear/price 1299.99M
                         :footwear/sentient? false
                         :boot/vacuum-rated? true
                         :boot/temperature-range "-270C to +150C"})

          kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Kevin"
                   :footwear/size "2-foot"
                   :footwear/color "Existential Dread Gray"
                   :footwear/tentacle-count 2
                   :footwear/gravity-rating 1.0
                   :footwear/price 19.99M
                   :footwear/sentient? true
                   :flipflop/flop-frequency 3.7
                   :flipflop/toe-separator-count 1
                   :flipflop/escape-velocity 8.2
                   :flipflop/mood "philosophical"})

          moonboot (dt/make :zorp/MoonBoot
                     {:footwear/name "1970s Earth Replica"
                      :footwear/size "earth-medium"
                      :footwear/color "Silver Metallic"
                      :footwear/tentacle-count 2
                      :footwear/gravity-rating 0.166
                      :footwear/price 89.99M
                      :footwear/sentient? false
                      :boot/vacuum-rated? false
                      :boot/temperature-range "-40C to +40C"})]

      (testing "Business query: What types of footwear do I sell?"
        (let [subclasses (set (dt/subclasses-of :zorp/Footwear))]
          (is (= 8 (count subclasses)) "Should have 8 footwear subclasses")))

      (testing "Business query: Show me all sneakers"
        (let [sneakers (dt/all-instances-of :zorp/Sneaker)
              names (set (map :footwear/name sneakers))]
          (is (= 2 (count sneakers)) "Should have 2 sneakers")
          (is (contains? names "Anti-Gravity Dunks 3000"))
          (is (contains? names "Blob Runner Basics"))))

      (testing "Business query: Is Kevin really a FlipFlop?"
        (is (dt/instance-of? :zorp/FlipFlop kevin) "Kevin is a FlipFlop"))

      (testing "Business query: Are FlipFlops a type of Footwear?"
        (is (dt/subclass-of? :zorp/Footwear :zorp/FlipFlop) "FlipFlop is subclass of Footwear"))

      (testing "Business query: Find sentient products"
        (let [all-products (dt/all-instances-of :zorp/Footwear)
              sentient (filter :footwear/sentient? all-products)]
          (is (= 1 (count sentient)) "Only Kevin is sentient")
          (is (= "Kevin" (:footwear/name (first sentient))))))

      (testing "Business query: Products for 7-tentacled beings"
        (let [all-products (dt/all-instances-of :zorp/Footwear)
              seven-tentacle (filter #(= 7 (:footwear/tentacle-count %)) all-products)]
          (is (= 1 (count seven-tentacle)))
          (is (= "Anti-Gravity Dunks 3000" (:footwear/name (first seven-tentacle))))))

      (testing "Business query: Vacuum-rated boots only"
        (let [all-boots (dt/all-instances-of :zorp/Boot)
              vacuum-rated (filter :boot/vacuum-rated? all-boots)]
          (is (= 1 (count vacuum-rated)))
          (is (= "Void Walker Pro" (:footwear/name (first vacuum-rated))))))

      (testing "Business query: Products under 100 credits"
        (let [all-products (dt/all-instances-of :zorp/Footwear)
              affordable (filter #(< (:footwear/price %) 100M) all-products)
              names (set (map :footwear/name affordable))]
          (is (= 3 (count affordable)))
          (is (contains? names "Blob Runner Basics"))
          (is (contains? names "Kevin"))
          (is (contains? names "1970s Earth Replica")))))))

(deftest kevin-mood-tracking-test
  (testing "Kevin's mood can change over time"
    (let [kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Moody Kevin"
                   :footwear/size "2-foot"
                   :footwear/sentient? true
                   :flipflop/mood "content"})]
      (is (= "content" (:flipflop/mood kevin)) "Kevin starts content")

      ;; Update Kevin's mood
      @(d/transact (db/conn)
         [[:db/add (:db/id kevin) :flipflop/mood "hangry"]])

      (let [updated-kevin (db/entity (:db/id kevin))]
        (is (= "hangry" (:flipflop/mood updated-kevin)) "Kevin is now hangry")))))

(deftest glow-in-dark-query-test
  (testing "Find all glow-in-dark sneakers for Pluto's night side"
    (dt/make :zorp/HighTop {:footwear/name "Glowy 1" :footwear/size "1"
                            :sneaker/glow-in-dark? true})
    (dt/make :zorp/HighTop {:footwear/name "Dark 1" :footwear/size "2"
                            :sneaker/glow-in-dark? false})
    (dt/make :zorp/LowTop {:footwear/name "Glowy 2" :footwear/size "3"
                           :sneaker/glow-in-dark? true})

    (let [all-sneakers (dt/all-instances-of :zorp/Sneaker)
          glowing (filter :sneaker/glow-in-dark? all-sneakers)]
      (is (= 2 (count glowing)) "Should have 2 glowing sneakers"))))
