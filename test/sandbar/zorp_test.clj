(ns sandbar.zorp-test
  "Test suite for Zorp's Galactic Footwear Emporium.

   Zorp is an alien sneaker salesman on Pluto. His inventory system needs to
   handle beings with 0-12 tentacles, variable gravity preferences, and the
   occasional sentient flip-flop that questions the nature of existence.

   This test suite validates:
   - Class hierarchies (because even alien footwear has taxonomy)
   - Property inheritance (tentacle-count flows down to all subclasses)
   - Instance creation and validation (no instantiating abstract Footwear!)
   - Business queries (\"Show me all vacuum-rated boots under 1000 credits\")

   If these tests fail, Zorp's customers might receive boots rated for the
   wrong number of appendages. Intergalactic lawsuits are expensive.

   Schema defined in schema/zorp.edn"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "zorp-test"
                                              :extra-schema [:zorp]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Hierarchy Tests
;;
;; The foundation of any good footwear empire is proper taxonomy.
;; You can't just throw boots and sandals in the same bin and call it a day.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest footwear-class-hierarchy-test
  ;; Footwear is abstract because you can't just sell "a footwear."
  ;; Customers get very upset when you try.
  (testing "Footwear class exists and is abstract"
    (is (some? (db/entity :zorp/Footwear)) "Footwear class should exist")
    (is (= :dt/Class (dt/class-of :zorp/Footwear)) "Footwear should be a Class")
    (is (dt/abstract? :zorp/Footwear) "Footwear should be abstract - you can't sell 'a footwear'"))

  (testing "Footwear inherits from dt/Ref"
    ;; dt/Ref is the base class for all referenceable entities.
    ;; Even alien shoes need identity.
    (is (= #{:dt/Ref} (set (dt/parents-of :zorp/Footwear)))
        "Footwear should have dt/Ref as parent"))

  (testing "Footwear ancestors include dt/Resource"
    ;; The metamodel goes: Resource -> Ref -> Footwear
    ;; It's turtles (or in this case, shoes) all the way down.
    (let [ancestors (set (dt/ancestors-of :zorp/Footwear))]
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest sneaker-hierarchy-test
  ;; Sneakers are the backbone of Zorp's business.
  ;; High-gravity planets love the bounce factor.
  (testing "Sneaker class hierarchy"
    (is (= #{:zorp/Footwear} (set (dt/parents-of :zorp/Sneaker)))
        "Sneaker parent should be Footwear")
    (let [ancestors (set (dt/ancestors-of :zorp/Sneaker))]
      (is (contains? ancestors :zorp/Footwear) "Should include Footwear")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource")))

  (testing "HighTop and LowTop inherit from Sneaker"
    ;; The great HighTop vs LowTop debate spans galaxies.
    ;; Zorp wisely stocks both.
    (is (= #{:zorp/Sneaker} (set (dt/parents-of :zorp/HighTop)))
        "HighTop parent should be Sneaker")
    (is (= #{:zorp/Sneaker} (set (dt/parents-of :zorp/LowTop)))
        "LowTop parent should be Sneaker"))

  (testing "HighTop full ancestor chain"
    ;; A HighTop is a Sneaker is a Footwear is a Ref is a Resource.
    ;; Very philosophical. Kevin the flip-flop would approve.
    (let [ancestors (set (dt/ancestors-of :zorp/HighTop))]
      (is (contains? ancestors :zorp/Sneaker) "Should include Sneaker")
      (is (contains? ancestors :zorp/Footwear) "Should include Footwear")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest boot-hierarchy-test
  ;; Boots are serious business on Pluto. Vacuum exposure is no joke.
  (testing "Boot class hierarchy"
    (is (= #{:zorp/Footwear} (set (dt/parents-of :zorp/Boot)))
        "Boot parent should be Footwear"))

  (testing "SpaceBoot and MoonBoot inherit from Boot"
    ;; SpaceBoot: for actual space. MoonBoot: for looking cool on moons.
    ;; Important distinction. Many returns have been processed.
    (is (= #{:zorp/Boot} (set (dt/parents-of :zorp/SpaceBoot)))
        "SpaceBoot parent should be Boot")
    (is (= #{:zorp/Boot} (set (dt/parents-of :zorp/MoonBoot)))
        "MoonBoot parent should be Boot")))

(deftest sandal-hierarchy-test
  ;; Sandals: for beings who want their tentacles to breathe.
  (testing "Sandal class hierarchy"
    (is (= #{:zorp/Footwear} (set (dt/parents-of :zorp/Sandal)))
        "Sandal parent should be Footwear"))

  (testing "FlipFlop inherits from Sandal"
    ;; FlipFlops are sandals with attitude. And sometimes sentience.
    (is (= #{:zorp/Sandal} (set (dt/parents-of :zorp/FlipFlop)))
        "FlipFlop parent should be Sandal"))

  (testing "FlipFlop full ancestor chain"
    ;; FlipFlop -> Sandal -> Footwear -> Ref -> Resource
    ;; Kevin has contemplated this chain extensively.
    (let [ancestors (set (dt/ancestors-of :zorp/FlipFlop))]
      (is (contains? ancestors :zorp/Sandal) "Should include Sandal")
      (is (contains? ancestors :zorp/Footwear) "Should include Footwear")
      (is (contains? ancestors :dt/Ref) "Should include dt/Ref")
      (is (contains? ancestors :dt/Resource) "Should include dt/Resource"))))

(deftest subclass-relationships-test
  ;; The family tree of footwear. Thanksgiving dinners are complicated.
  (testing "subclasses-of returns all transitive subclasses"
    (let [footwear-subclasses (set (dt/subclasses-of :zorp/Footwear))]
      ;; All 8 concrete footwear types should be descendants
      (is (contains? footwear-subclasses :zorp/Sneaker) "Should include Sneaker")
      (is (contains? footwear-subclasses :zorp/Boot) "Should include Boot")
      (is (contains? footwear-subclasses :zorp/Sandal) "Should include Sandal")
      (is (contains? footwear-subclasses :zorp/HighTop) "Should include HighTop")
      (is (contains? footwear-subclasses :zorp/LowTop) "Should include LowTop")
      (is (contains? footwear-subclasses :zorp/SpaceBoot) "Should include SpaceBoot")
      (is (contains? footwear-subclasses :zorp/MoonBoot) "Should include MoonBoot")
      (is (contains? footwear-subclasses :zorp/FlipFlop) "Should include FlipFlop")))

  (testing "direct-subclasses-of returns immediate subclasses only"
    ;; Footwear has 3 direct children. The grandchildren are someone else's problem.
    (let [footwear-direct (set (dt/direct-subclasses-of :zorp/Footwear))]
      (is (contains? footwear-direct :zorp/Sneaker) "Should include Sneaker")
      (is (contains? footwear-direct :zorp/Boot) "Should include Boot")
      (is (contains? footwear-direct :zorp/Sandal) "Should include Sandal")
      (is (not (contains? footwear-direct :zorp/HighTop)) "Should NOT include HighTop - that's a grandchild")
      (is (not (contains? footwear-direct :zorp/FlipFlop)) "Should NOT include FlipFlop - also a grandchild")))

  (testing "subclass-of? predicate"
    ;; The "is-a" relationship. Essential for customer service.
    ;; "Is this a type of boot?" "Yes sir, SpaceBoot is indeed a Boot."
    (is (dt/subclass-of? :zorp/Footwear :zorp/Sneaker) "Sneaker is subclass of Footwear")
    (is (dt/subclass-of? :zorp/Footwear :zorp/HighTop) "HighTop is subclass of Footwear (transitive)")
    (is (dt/subclass-of? :zorp/Sneaker :zorp/HighTop) "HighTop is subclass of Sneaker")
    (is (dt/subclass-of? :dt/Resource :zorp/FlipFlop) "FlipFlop is subclass of dt/Resource (way up the chain)")
    (is (not (dt/subclass-of? :zorp/Sneaker :zorp/Boot)) "Boot is NOT subclass of Sneaker - different branches")
    (is (not (dt/subclass-of? :zorp/FlipFlop :zorp/Sneaker)) "Sneaker is NOT subclass of FlipFlop - wrong direction")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property/Slot Tests
;;
;; Properties define what data each class can hold.
;; Get these wrong and you'll have boots with bounce-factors
;; and sneakers rated for vacuum. Chaos.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest footwear-properties-test
  ;; The universal properties all footwear shares.
  ;; Every shoe needs a name. Even Kevin.
  (testing "Footwear properties exist"
    (is (some? (db/entity :footwear/name)) "footwear/name should exist")
    (is (some? (db/entity :footwear/size)) "footwear/size should exist")
    (is (some? (db/entity :footwear/color)) "footwear/color should exist")
    (is (some? (db/entity :footwear/tentacle-count)) "footwear/tentacle-count should exist - crucial for fitting")
    (is (some? (db/entity :footwear/gravity-rating)) "footwear/gravity-rating should exist - Pluto is 0.063g")
    (is (some? (db/entity :footwear/price)) "footwear/price should exist - Zorp needs revenue")
    (is (some? (db/entity :footwear/sentient?)) "footwear/sentient? should exist - it happens more than you'd think"))

  (testing "Footwear property domains"
    ;; Domain = "what class owns this property"
    (is (= :zorp/Footwear (dt/domain-of :footwear/name)) "footwear/name belongs to Footwear")
    (is (= :zorp/Footwear (dt/domain-of :footwear/tentacle-count)) "footwear/tentacle-count belongs to Footwear")
    (is (= :zorp/Footwear (dt/domain-of :footwear/sentient?)) "footwear/sentient? belongs to Footwear"))

  (testing "Footwear property ranges"
    ;; Range = "what type of value this property holds"
    ;; Get these wrong and your database will be very confused.
    (is (= :db.type/string (dt/range-of :footwear/name)) "names are strings")
    (is (= :db.type/long (dt/range-of :footwear/tentacle-count)) "tentacle counts are integers (no half-tentacles)")
    (is (= :db.type/double (dt/range-of :footwear/gravity-rating)) "gravity needs decimal precision")
    (is (= :db.type/bigdec (dt/range-of :footwear/price)) "prices need exact decimal (no floating point money!)")
    (is (= :db.type/boolean (dt/range-of :footwear/sentient?)) "sentience is binary (for now)")))

(deftest sneaker-properties-test
  ;; Sneaker-specific properties. Because sneakers are special.
  (testing "Sneaker-specific properties exist"
    (is (some? (db/entity :sneaker/bounce-factor)) "bounce-factor: higher = bouncier")
    (is (some? (db/entity :sneaker/glow-in-dark?)) "glow-in-dark: essential for Pluto's 6-day nights")
    (is (some? (db/entity :sneaker/squeak-volume)) "squeak-volume: some species find it soothing")
    (is (some? (db/entity :sneaker/lace-type)) "lace-type: tentacle-knot, psychic, velcro, etc.")
    (is (some? (db/entity :sneaker/air-pump?)) "air-pump: retro Earth tech, very popular"))

  (testing "Sneaker property domains"
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/bounce-factor)) "bounce-factor is Sneaker-specific")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/glow-in-dark?)) "glow-in-dark is Sneaker-specific")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/squeak-volume)) "squeak-volume is Sneaker-specific")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/lace-type)) "lace-type is Sneaker-specific")
    (is (= :zorp/Sneaker (dt/domain-of :sneaker/air-pump?)) "air-pump is Sneaker-specific"))

  (testing "Sneaker property ranges"
    (is (= :db.type/double (dt/range-of :sneaker/bounce-factor)) "bounce-factor is a double")
    (is (= :db.type/boolean (dt/range-of :sneaker/glow-in-dark?)) "glow-in-dark is boolean")
    (is (= :db.type/long (dt/range-of :sneaker/squeak-volume)) "squeak-volume in decibels (integer)")
    (is (= :db.type/string (dt/range-of :sneaker/lace-type)) "lace-type is a string")
    (is (= :db.type/boolean (dt/range-of :sneaker/air-pump?)) "air-pump is boolean")))

(deftest boot-properties-test
  ;; Boot properties are literally life-or-death.
  ;; vacuum-rated? = "will your feet explode in space?"
  (testing "Boot-specific properties"
    (is (some? (db/entity :boot/vacuum-rated?)) "vacuum-rated: the difference between life and death")
    (is (some? (db/entity :boot/temperature-range)) "temperature-range: Pluto gets cold")
    (is (= :zorp/Boot (dt/domain-of :boot/vacuum-rated?)) "vacuum-rated belongs to Boot")
    (is (= :zorp/Boot (dt/domain-of :boot/temperature-range)) "temperature-range belongs to Boot")))

(deftest flipflop-properties-test
  ;; FlipFlop properties are... unique. Kevin insisted on the mood property.
  (testing "FlipFlop-specific properties exist"
    (is (some? (db/entity :flipflop/flop-frequency)) "flop-frequency: flops per second")
    (is (some? (db/entity :flipflop/toe-separator-count)) "toe-separator-count: for multi-toed beings")
    (is (some? (db/entity :flipflop/escape-velocity)) "escape-velocity: how fast they can run away")
    (is (some? (db/entity :flipflop/mood)) "mood: sentient flip-flops have feelings"))

  (testing "FlipFlop property domains"
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/flop-frequency)) "flop-frequency domain")
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/toe-separator-count)) "toe-separator-count domain")
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/escape-velocity)) "escape-velocity domain")
    (is (= :zorp/FlipFlop (dt/domain-of :flipflop/mood)) "mood domain"))

  (testing "FlipFlop property ranges"
    (is (= :db.type/double (dt/range-of :flipflop/flop-frequency)) "flop-frequency is a double")
    (is (= :db.type/long (dt/range-of :flipflop/toe-separator-count)) "toe-separator-count is integer")
    (is (= :db.type/double (dt/range-of :flipflop/escape-velocity)) "escape-velocity is double (m/s)")
    (is (= :db.type/string (dt/range-of :flipflop/mood)) "mood is a string (the range of emotions is vast)")))

(deftest slot-inheritance-test
  ;; The magic of inheritance: HighTops get ALL the properties
  ;; from Sneaker AND Footwear. Less typing, more selling.
  (testing "HighTop inherits all slots from Sneaker and Footwear"
    (let [slots (dt/slots-of :zorp/HighTop)]
      ;; From dt/Resource (the primordial ancestor)
      (is (contains? slots :dt/type) "Should have dt/type from Resource")
      (is (contains? slots :db/ident) "Should have db/ident from Resource")
      ;; From Footwear (grandparent)
      (is (contains? slots :footwear/name) "Should have footwear/name")
      (is (contains? slots :footwear/size) "Should have footwear/size")
      (is (contains? slots :footwear/color) "Should have footwear/color")
      (is (contains? slots :footwear/tentacle-count) "Should have footwear/tentacle-count")
      (is (contains? slots :footwear/gravity-rating) "Should have footwear/gravity-rating")
      (is (contains? slots :footwear/price) "Should have footwear/price")
      (is (contains? slots :footwear/sentient?) "Should have footwear/sentient?")
      ;; From Sneaker (parent)
      (is (contains? slots :sneaker/bounce-factor) "Should have sneaker/bounce-factor")
      (is (contains? slots :sneaker/glow-in-dark?) "Should have sneaker/glow-in-dark?")
      (is (contains? slots :sneaker/squeak-volume) "Should have sneaker/squeak-volume")
      (is (contains? slots :sneaker/lace-type) "Should have sneaker/lace-type")
      (is (contains? slots :sneaker/air-pump?) "Should have sneaker/air-pump?")))

  (testing "FlipFlop inherits from Sandal and Footwear, adds its own"
    ;; FlipFlops get footwear props, sandal props (if any), plus their unique ones.
    ;; They do NOT get sneaker or boot props. That would be weird.
    (let [slots (dt/slots-of :zorp/FlipFlop)]
      ;; From Footwear
      (is (contains? slots :footwear/name) "Should have footwear/name")
      (is (contains? slots :footwear/sentient?) "Should have footwear/sentient? (important for Kevin)")
      ;; FlipFlop's own slots
      (is (contains? slots :flipflop/flop-frequency) "Should have flipflop/flop-frequency")
      (is (contains? slots :flipflop/toe-separator-count) "Should have flipflop/toe-separator-count")
      (is (contains? slots :flipflop/escape-velocity) "Should have flipflop/escape-velocity")
      (is (contains? slots :flipflop/mood) "Should have flipflop/mood")
      ;; Should NOT have sneaker or boot slots (different branch of family tree)
      (is (not (contains? slots :sneaker/bounce-factor)) "Should NOT have sneaker/bounce-factor")
      (is (not (contains? slots :boot/vacuum-rated?)) "Should NOT have boot/vacuum-rated?")))

  (testing "SpaceBoot inherits boot slots"
    ;; SpaceBoots are boots. They get boot properties.
    ;; They do not bounce. They are serious footwear.
    (let [slots (dt/slots-of :zorp/SpaceBoot)]
      (is (contains? slots :boot/vacuum-rated?) "Should have boot/vacuum-rated?")
      (is (contains? slots :boot/temperature-range) "Should have boot/temperature-range")
      (is (contains? slots :footwear/name) "Should have footwear/name")
      (is (not (contains? slots :sneaker/bounce-factor)) "Should NOT have sneaker/bounce-factor"))))

(deftest direct-slots-test
  ;; direct-slots-of: "What did THIS class add?"
  ;; Useful for understanding where properties come from.
  (testing "direct-slots-of returns only directly declared slots"
    (let [sneaker-direct (dt/direct-slots-of :zorp/Sneaker)
          sneaker-slot-idents (set (map #(:db/ident (db/entity %)) sneaker-direct))]
      ;; Sneaker declares its own 5 properties
      (is (contains? sneaker-slot-idents :sneaker/bounce-factor) "Should have bounce-factor")
      (is (contains? sneaker-slot-idents :sneaker/glow-in-dark?) "Should have glow-in-dark?")
      (is (contains? sneaker-slot-idents :sneaker/squeak-volume) "Should have squeak-volume")
      (is (contains? sneaker-slot-idents :sneaker/lace-type) "Should have lace-type")
      (is (contains? sneaker-slot-idents :sneaker/air-pump?) "Should have air-pump?")
      ;; But NOT the inherited ones
      (is (not (contains? sneaker-slot-idents :footwear/name)) "Should NOT have footwear/name - that's inherited"))

    (let [flipflop-direct (dt/direct-slots-of :zorp/FlipFlop)
          flipflop-slot-idents (set (map #(:db/ident (db/entity %)) flipflop-direct))]
      ;; FlipFlop declares its own 4 unique properties
      (is (contains? flipflop-slot-idents :flipflop/flop-frequency) "Should have flop-frequency")
      (is (contains? flipflop-slot-idents :flipflop/mood) "Should have mood")
      (is (not (contains? flipflop-slot-idents :footwear/name)) "Should NOT have footwear/name"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Creation Tests (dt/make)
;;
;; This is where the magic happens. dt/make creates validated,
;; typed instances in the database. Get the data wrong? Exception.
;; Try to instantiate an abstract class? Exception.
;; Everything correct? Beautiful entity.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest create-hightop-test
  ;; The Anti-Gravity Dunks 3000: Zorp's flagship product.
  ;; Popular with the 7-tentacled Zygorthian basketball league.
  (testing "Create Anti-Gravity Dunks 3000"
    (let [dunks (dt/make :zorp/HighTop
                  {:footwear/name "Anti-Gravity Dunks 3000"
                   :footwear/size "7-tentacle"
                   :footwear/color "Ultraviolet Sparkle"
                   :footwear/tentacle-count 7
                   :footwear/gravity-rating 0.063  ; Optimized for Pluto
                   :footwear/price 299.99M
                   :footwear/sentient? false  ; Thankfully
                   :sneaker/bounce-factor 47.5  ; Olympic grade
                   :sneaker/glow-in-dark? true  ; Essential for 6-day Plutonian nights
                   :sneaker/squeak-volume 45  ; Moderately squeaky
                   :sneaker/lace-type "tentacle-knot"
                   :sneaker/air-pump? true})]  ; Retro Earth tech is hot right now
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
  ;; Blob Runner Basics: Entry-level sneakers for gelatinous beings.
  ;; Zero tentacles, psychic laces (they have no hands), completely silent.
  (testing "Create Blob Runner Basics"
    (let [blob-runner (dt/make :zorp/LowTop
                        {:footwear/name "Blob Runner Basics"
                         :footwear/size "juvenile-blob"
                         :footwear/color "Transparent"  ; Blobs prefer to see their feet
                         :footwear/tentacle-count 0  ; Blobs are tentacle-free
                         :footwear/gravity-rating 0.1  ; Light gravity worlds
                         :footwear/price 49.99M  ; Affordable!
                         :footwear/sentient? false
                         :sneaker/bounce-factor 12.0  ; Blobs don't need much bounce
                         :sneaker/glow-in-dark? false
                         :sneaker/squeak-volume 0  ; Blobs have sensitive hearing
                         :sneaker/lace-type "psychic"  ; No hands = psychic laces
                         :sneaker/air-pump? false})]
      (is (some? blob-runner) "Should create entity")
      (is (= :zorp/LowTop (:dt/type blob-runner)) "Should have correct type")
      (is (= 0 (:footwear/tentacle-count blob-runner)) "Blobs have no tentacles")
      (is (= "psychic" (:sneaker/lace-type blob-runner)) "Psychic laces for blobs")
      (is (= 0 (:sneaker/squeak-volume blob-runner)) "Silent for blobs"))))

(deftest create-spaceboot-test
  ;; Void Walker Pro: For beings who actually go into space.
  ;; Not for posers. Rated to -270C and full vacuum.
  (testing "Create Void Walker Pro"
    (let [void-walker (dt/make :zorp/SpaceBoot
                        {:footwear/name "Void Walker Pro"
                         :footwear/size "5-tentacle"
                         :footwear/color "Infrared Black"  ; Absorbs heat from stars
                         :footwear/tentacle-count 5
                         :footwear/gravity-rating 0.0  ; Zero-G rated
                         :footwear/price 1299.99M  ; Quality costs
                         :footwear/sentient? false
                         :boot/vacuum-rated? true  ; THE important property
                         :boot/temperature-range "-270C to +150C"})]  ; Near absolute zero to quite toasty
      (is (some? void-walker) "Should create entity")
      (is (= :zorp/SpaceBoot (:dt/type void-walker)) "Should have correct type")
      (is (true? (:boot/vacuum-rated? void-walker)) "Should be vacuum rated")
      (is (= "-270C to +150C" (:boot/temperature-range void-walker)) "Should have temp range"))))

(deftest create-moonboot-test
  ;; 1970s Earth Replica: Retro is in. These are NOT vacuum-rated.
  ;; Zorp has a whole section of disclaimers for these.
  (testing "Create 1970s Earth Replica"
    (let [moonboot (dt/make :zorp/MoonBoot
                     {:footwear/name "1970s Earth Replica"
                      :footwear/size "earth-medium"
                      :footwear/color "Silver Metallic"
                      :footwear/tentacle-count 2  ; Earth standard
                      :footwear/gravity-rating 0.166  ; Luna gravity
                      :footwear/price 89.99M
                      :footwear/sentient? false
                      :boot/vacuum-rated? false  ; IMPORTANT: NOT FOR ACTUAL SPACE
                      :boot/temperature-range "-40C to +40C"})]  ; Earth-ish temps only
      (is (some? moonboot) "Should create entity")
      (is (= :zorp/MoonBoot (:dt/type moonboot)) "Should have correct type")
      (is (false? (:boot/vacuum-rated? moonboot)) "Retro moonboots NOT vacuum rated - just for style"))))

(deftest create-kevin-flipflop-test
  ;; Kevin: The only sentient flip-flop in inventory.
  ;; He questions the nature of existence and has a loyalty program.
  (testing "Create Kevin the sentient flip-flop"
    (let [kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Kevin"
                   :footwear/size "2-foot"
                   :footwear/color "Existential Dread Gray"  ; Kevin chose this color himself
                   :footwear/tentacle-count 2
                   :footwear/gravity-rating 1.0  ; Earth standard (Kevin came from Earth)
                   :footwear/price 19.99M  ; Kevin refuses a higher price. Principles.
                   :footwear/sentient? true  ; Here's the thing about Kevin
                   :flipflop/flop-frequency 3.7  ; Flops pensively
                   :flipflop/toe-separator-count 1  ; Classic design
                   :flipflop/escape-velocity 8.2  ; Kevin can outrun most customers
                   :flipflop/mood "philosophical"})]  ; Current state of being
      (is (some? kevin) "Should create entity")
      (is (= :zorp/FlipFlop (:dt/type kevin)) "Should have correct type")
      (is (= "Kevin" (:footwear/name kevin)) "Should be named Kevin")
      (is (true? (:footwear/sentient? kevin)) "Kevin is sentient - this is canon")
      (is (= 3.7 (:flipflop/flop-frequency kevin)) "Kevin flops pensively at 3.7 Hz")
      (is (= 8.2 (:flipflop/escape-velocity kevin)) "Kevin can outrun customers if needed")
      (is (= "philosophical" (:flipflop/mood kevin)) "Kevin ponders the nature of free will"))))

(deftest abstract-class-rejection-test
  ;; You can't instantiate abstract classes. This is a feature.
  ;; Customers who ask for "a footwear" get redirected to Kevin for counseling.
  (testing "Cannot instantiate abstract Footwear class"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Validation failed"
                          (dt/make :zorp/Footwear
                            {:footwear/name "Abstract Shoe"
                             :footwear/size "impossible"}))
        "Should reject abstract class instantiation - Footwear is a concept, not a product")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Instance Query Tests
;;
;; Once you have products in the database, you need to find them.
;; "Show me all boots." "Is this thing a sneaker?" "Find Kevin."
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest instance-of-predicate-test
  ;; instance-of? answers: "Is this thing one of those?"
  ;; Crucial for customer service. "Is this a boot?" "Yes ma'am, SpaceBoot is a Boot."
  (testing "instance-of? with created products"
    (let [dunks (dt/make :zorp/HighTop
                  {:footwear/name "Test Dunks"
                   :footwear/size "test"
                   :footwear/tentacle-count 1})
          kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Test Kevin"
                   :footwear/size "test"
                   :flipflop/mood "testing"})]
      ;; HighTop instance relationships (the full inheritance chain)
      (is (dt/instance-of? :zorp/HighTop dunks) "Dunks is instance of HighTop (direct)")
      (is (dt/instance-of? :zorp/Sneaker dunks) "Dunks is instance of Sneaker (parent)")
      (is (dt/instance-of? :zorp/Footwear dunks) "Dunks is instance of Footwear (grandparent)")
      (is (dt/instance-of? :dt/Ref dunks) "Dunks is instance of dt/Ref (great-grandparent)")
      (is (dt/instance-of? :dt/Resource dunks) "Dunks is instance of dt/Resource (the ancestor of all)")
      (is (not (dt/instance-of? :zorp/Boot dunks)) "Dunks is NOT instance of Boot - different family branch")
      (is (not (dt/instance-of? :zorp/FlipFlop dunks)) "Dunks is NOT instance of FlipFlop - obviously")

      ;; FlipFlop instance relationships
      (is (dt/instance-of? :zorp/FlipFlop kevin) "Kevin is instance of FlipFlop")
      (is (dt/instance-of? :zorp/Sandal kevin) "Kevin is instance of Sandal (parent)")
      (is (dt/instance-of? :zorp/Footwear kevin) "Kevin is instance of Footwear")
      (is (not (dt/instance-of? :zorp/Sneaker kevin)) "Kevin is NOT instance of Sneaker - don't insult him"))))

(deftest all-instances-query-test
  ;; all-instances-of: "Give me everything of this type, including subclasses."
  ;; Ask for Footwear, get everything. Ask for Sneaker, get HighTops and LowTops.
  (testing "all-instances-of returns instances including subclasses"
    ;; Create a diverse inventory
    (dt/make :zorp/HighTop {:footwear/name "Instance Test 1" :footwear/size "1"})
    (dt/make :zorp/LowTop {:footwear/name "Instance Test 2" :footwear/size "2"})
    (dt/make :zorp/SpaceBoot {:footwear/name "Instance Test 3" :footwear/size "3"})
    (dt/make :zorp/FlipFlop {:footwear/name "Instance Test 4" :footwear/size "4"
                             :flipflop/mood "content"})

    (testing "Querying Footwear returns all products (the whole store)"
      (let [all-footwear (dt/all-instances-of :zorp/Footwear)
            names (set (map :footwear/name all-footwear))]
        (is (contains? names "Instance Test 1") "Should include HighTop")
        (is (contains? names "Instance Test 2") "Should include LowTop")
        (is (contains? names "Instance Test 3") "Should include SpaceBoot")
        (is (contains? names "Instance Test 4") "Should include FlipFlop")))

    (testing "Querying Sneaker returns only sneakers (HighTop + LowTop)"
      (let [all-sneakers (dt/all-instances-of :zorp/Sneaker)
            names (set (map :footwear/name all-sneakers))]
        (is (contains? names "Instance Test 1") "Should include HighTop")
        (is (contains? names "Instance Test 2") "Should include LowTop")
        (is (not (contains? names "Instance Test 3")) "Should NOT include SpaceBoot - it's a boot!")
        (is (not (contains? names "Instance Test 4")) "Should NOT include FlipFlop - different aisle")))

    (testing "Querying Boot returns only boots"
      (let [all-boots (dt/all-instances-of :zorp/Boot)
            names (set (map :footwear/name all-boots))]
        (is (contains? names "Instance Test 3") "Should include SpaceBoot")
        (is (not (contains? names "Instance Test 1")) "Should NOT include HighTop")))))

(deftest direct-instances-query-test
  ;; direct-instances-of: "Only things that are EXACTLY this type."
  ;; If everything is a subclass, you get nothing.
  (testing "direct-instances-of excludes subclass instances"
    ;; Create instances at different levels
    (dt/make :zorp/HighTop {:footwear/name "Direct Test HighTop" :footwear/size "1"})
    (dt/make :zorp/LowTop {:footwear/name "Direct Test LowTop" :footwear/size "2"})

    (testing "Sneaker has no direct instances (all are HighTop/LowTop)"
      ;; Nobody buys just "a Sneaker" - they buy HighTops or LowTops.
      ;; So direct-instances-of Sneaker is empty!
      (let [direct-sneakers (dt/direct-instances-of :zorp/Sneaker)
            names (set (map :footwear/name direct-sneakers))]
        (is (not (contains? names "Direct Test HighTop")) "HighTop is not direct Sneaker instance")
        (is (not (contains? names "Direct Test LowTop")) "LowTop is not direct Sneaker instance")))

    (testing "HighTop direct instances"
      ;; But HighTop has direct instances - things that are literally HighTops.
      (let [direct-hightops (dt/direct-instances-of :zorp/HighTop)
            names (set (map :footwear/name direct-hightops))]
        (is (contains? names "Direct Test HighTop") "Should include the HighTop")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation Tests
;;
;; Validation ensures your data makes sense.
;; Properly typed, properly structured, not abstract classes.
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
  ;; class-of answers: "What kind of thing IS this thing?"
  ;; Footwear is a Class. footwear/name is a Property. Kevin is a FlipFlop.
  (testing "Class types are correctly identified"
    (is (= :dt/Class (dt/class-of :zorp/Footwear)) "Footwear is a Class (a blueprint)")
    (is (= :dt/Class (dt/class-of :zorp/Sneaker)) "Sneaker is a Class")
    (is (= :dt/Class (dt/class-of :zorp/FlipFlop)) "FlipFlop is a Class")
    (is (= :dt/Property (dt/class-of :footwear/name)) "footwear/name is a Property (metadata)")
    (is (= :dt/Property (dt/class-of :sneaker/bounce-factor)) "sneaker/bounce-factor is a Property")
    (is (= :dt/Property (dt/class-of :flipflop/mood)) "flipflop/mood is a Property (Kevin's favorite)")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Integration Tests - Full Business Scenarios
;;
;; These tests simulate real questions Zorp asks every day:
;; "What do I sell?" "Show me all sneakers." "Find vacuum-rated boots."
;; "Is Kevin being weird again?"
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest zorps-inventory-scenario-test
  ;; A complete day in the life of Zorp's inventory system.
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

      ;; Business queries Zorp runs daily:

      (testing "Business query: What types of footwear do I sell?"
        (let [subclasses (set (dt/subclasses-of :zorp/Footwear))]
          (is (= 8 (count subclasses)) "Should have 8 footwear subclasses (diverse catalog)")))

      (testing "Business query: Show me all sneakers"
        ;; Customer walks in: "I want sneakers." Zorp runs this query.
        (let [sneakers (dt/all-instances-of :zorp/Sneaker)
              names (set (map :footwear/name sneakers))]
          (is (= 2 (count sneakers)) "Should have 2 sneakers in stock")
          (is (contains? names "Anti-Gravity Dunks 3000"))
          (is (contains? names "Blob Runner Basics"))))

      (testing "Business query: Is Kevin really a FlipFlop?"
        ;; Kevin sometimes claims to be a Boot. He is not a Boot.
        (is (dt/instance-of? :zorp/FlipFlop kevin) "Kevin is definitely a FlipFlop"))

      (testing "Business query: Are FlipFlops a type of Footwear?"
        ;; Regulatory question. Yes, FlipFlops count as Footwear for tax purposes.
        (is (dt/subclass-of? :zorp/Footwear :zorp/FlipFlop) "FlipFlop is subclass of Footwear"))

      (testing "Business query: Find sentient products"
        ;; Important for liability purposes. Sentient products require special handling.
        (let [all-products (dt/all-instances-of :zorp/Footwear)
              sentient (filter :footwear/sentient? all-products)]
          (is (= 1 (count sentient)) "Only Kevin is sentient (thankfully)")
          (is (= "Kevin" (:footwear/name (first sentient))))))

      (testing "Business query: Products for 7-tentacled beings"
        ;; Zygorthian basketball player walks in. Needs 7-tentacle support.
        (let [all-products (dt/all-instances-of :zorp/Footwear)
              seven-tentacle (filter #(= 7 (:footwear/tentacle-count %)) all-products)]
          (is (= 1 (count seven-tentacle)))
          (is (= "Anti-Gravity Dunks 3000" (:footwear/name (first seven-tentacle))))))

      (testing "Business query: Vacuum-rated boots only"
        ;; Customer: "I'm going to actual space." Zorp: "Then you need THIS."
        (let [all-boots (dt/all-instances-of :zorp/Boot)
              vacuum-rated (filter :boot/vacuum-rated? all-boots)]
          (is (= 1 (count vacuum-rated)))
          (is (= "Void Walker Pro" (:footwear/name (first vacuum-rated))))))

      (testing "Business query: Products under 100 credits"
        ;; Budget-conscious customers. Kevin is a bargain (and he's proud of it).
        (let [all-products (dt/all-instances-of :zorp/Footwear)
              affordable (filter #(< (:footwear/price %) 100M) all-products)
              names (set (map :footwear/name affordable))]
          (is (= 3 (count affordable)))
          (is (contains? names "Blob Runner Basics"))
          (is (contains? names "Kevin"))  ; Kevin insists on accessible pricing
          (is (contains? names "1970s Earth Replica")))))))

(deftest kevin-mood-tracking-test
  ;; Kevin's mood changes. We need to track this for safety reasons.
  ;; A hangry Kevin has been known to hide from customers.
  (testing "Kevin's mood can change over time"
    (let [kevin (dt/make :zorp/FlipFlop
                  {:footwear/name "Moody Kevin"
                   :footwear/size "2-foot"
                   :footwear/sentient? true
                   :flipflop/mood "content"})]
      (is (= "content" (:flipflop/mood kevin)) "Kevin starts content")

      ;; Something happens. Maybe a customer tried to haggle.
      @(d/transact (db/conn)
         [[:db/add (:db/id kevin) :flipflop/mood "hangry"]])

      (let [updated-kevin (db/entity (:db/id kevin))]
        (is (= "hangry" (:flipflop/mood updated-kevin)) "Kevin is now hangry - handle with care")))))

(deftest glow-in-dark-query-test
  ;; Pluto has 6-day-long nights. Glow-in-dark sneakers are popular.
  (testing "Find all glow-in-dark sneakers for Pluto's night side"
    (dt/make :zorp/HighTop {:footwear/name "Glowy 1" :footwear/size "1"
                            :sneaker/glow-in-dark? true})
    (dt/make :zorp/HighTop {:footwear/name "Dark 1" :footwear/size "2"
                            :sneaker/glow-in-dark? false})
    (dt/make :zorp/LowTop {:footwear/name "Glowy 2" :footwear/size "3"
                           :sneaker/glow-in-dark? true})

    (let [all-sneakers (dt/all-instances-of :zorp/Sneaker)
          glowing (filter :sneaker/glow-in-dark? all-sneakers)]
      (is (= 2 (count glowing)) "Should have 2 glowing sneakers for the eternal night"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API Tests - EDN/JSON/Transit Content Types
;;
;; The REST API speaks multiple languages. EDN for Clojure devs,
;; JSON for everyone else, Transit for the best of both worlds.
;; Keywords stay keywords in EDN/Transit. JSON turns them into strings.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest api-status-instant-test
  ;; The status endpoint returns timestamps. Different formats handle them differently.
  (testing "API status returns #inst timestamp in EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/status")]
      (is (= 200 status))
      (is (inst? (:time body)) "EDN: time should be a java.util.Date (#inst)")
      (is (instance? java.util.Date (:time body)))))

  (testing "API status returns timestamp in JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/status")]
      (is (= 200 status))
      (is (string? (:time body)) "JSON: serializes #inst as ISO string (lossy but readable)")))

  (testing "API status returns #inst in Transit"
    (let [{:keys [status body]} (tu/api-get-transit "/api/status")]
      (is (= 200 status))
      (is (inst? (:time body)) "Transit: preserves #inst type perfectly"))))

(deftest api-zorp-class-edn-test
  ;; EDN is the native format. Keywords stay keywords. Life is good.
  (testing "Zorp class via EDN returns keywords"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/Footwear")]
      (is (= 200 status))
      (is (= :zorp/Footwear (:class body)) "class should be keyword")
      (is (keyword? (:class body)))
      (is (every? keyword? (:slots body)) "slots should be keywords")
      (is (every? keyword? (:subclasses body)) "subclasses should be keywords")
      (is (contains? (set (:subclasses body)) :zorp/Sneaker))
      (is (contains? (set (:subclasses body)) :zorp/Boot))
      (is (contains? (set (:subclasses body)) :zorp/Sandal))))

  (testing "Zorp class hierarchy via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/HighTop/hierarchy")]
      (is (= 200 status))
      (is (= :zorp/HighTop (:class body)))
      (is (contains? (set (:parents body)) :zorp/Sneaker))
      (is (contains? (set (:ancestors body)) :zorp/Footwear))
      (is (contains? (set (:ancestors body)) :dt/Resource)))))

(deftest api-zorp-class-json-test
  ;; JSON is the lingua franca. Keywords become strings. Adjust accordingly.
  (testing "Zorp class via JSON returns strings"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/zorp/Footwear")]
      (is (= 200 status))
      (is (= "zorp/Footwear" (:class body)) "JSON converts keywords to strings")
      (is (string? (:class body)))
      (is (every? string? (:slots body)) "slots are strings in JSON")))

  (testing "Zorp subclasses via JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/zorp/Footwear/subclasses")]
      (is (= 200 status))
      (is (= "zorp/Footwear" (:class body)))
      (is (contains? (set (:subclasses body)) "zorp/Sneaker"))
      (is (contains? (set (:subclasses body)) "zorp/FlipFlop")))))

(deftest api-zorp-class-transit-test
  ;; Transit: JSON's cool cousin that preserves Clojure types.
  (testing "Zorp class via Transit preserves keywords"
    (let [{:keys [status body]} (tu/api-get-transit "/api/store/classes/zorp/Footwear")]
      (is (= 200 status))
      (is (= :zorp/Footwear (:class body)) "Transit preserves keywords - best of both worlds")
      (is (keyword? (:class body)))
      (is (every? keyword? (:slots body)) "Transit preserves keyword slots")))

  (testing "Zorp ancestors via Transit"
    (let [{:keys [status body]} (tu/api-get-transit "/api/store/classes/zorp/FlipFlop/ancestors")]
      (is (= 200 status))
      (is (= :zorp/FlipFlop (:class body)))
      (is (keyword? (:class body)))
      (is (every? keyword? (:ancestors body)))
      (is (contains? (set (:ancestors body)) :zorp/Sandal))
      (is (contains? (set (:ancestors body)) :zorp/Footwear))
      (is (contains? (set (:ancestors body)) :dt/Ref)))))

(deftest api-zorp-property-types-test
  ;; Property metadata tells you what type of data to expect.
  (testing "Property with long range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/footwear/tentacle-count")]
      (is (= 200 status))
      (is (= :footwear/tentacle-count (:property body)))
      (is (= :db.type/long (:range body)) "tentacle-count is a long (no fractional tentacles)")))

  (testing "Property with double range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/footwear/gravity-rating")]
      (is (= 200 status))
      (is (= :footwear/gravity-rating (:property body)))
      (is (= :db.type/double (:range body)) "gravity needs decimal precision")))

  (testing "Property with bigdec range"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/footwear/price")]
      (is (= 200 status))
      (is (= :footwear/price (:property body)))
      (is (= :db.type/bigdec (:range body)) "prices are BigDecimal - never use floats for money!")))

  (testing "Property with boolean range"
    ;; Note: ? in property names requires URL encoding as %3F
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/properties/footwear/sentient%3F")]
      (is (= 200 status))
      (is (= :footwear/sentient? (:property body)))
      (is (= :db.type/boolean (:range body)) "sentience is boolean (for now)"))))

(deftest api-zorp-type-predicates-test
  ;; The type-check endpoints: programmatic is-a queries.
  (testing "subclass-of predicate via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/types/subclass-of/zorp/Footwear/zorp/HighTop")]
      (is (= 200 status))
      (is (= :zorp/Footwear (:parent body)))
      (is (= :zorp/HighTop (:child body)))
      (is (true? (:subclass-of? body)) "HighTop is indeed a kind of Footwear")))

  (testing "subclass-of predicate - negative case"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/types/subclass-of/zorp/Sneaker/zorp/Boot")]
      (is (= 200 status))
      (is (false? (:subclass-of? body)) "Boot is NOT a Sneaker - different family branch")))

  (testing "subclass-of via JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/types/subclass-of/zorp/Sandal/zorp/FlipFlop")]
      (is (= 200 status))
      (is (= "zorp/Sandal" (:parent body)))
      (is (= "zorp/FlipFlop" (:child body)))
      (is (true? (:subclass-of? body)))))

  (testing "subclass-of via Transit"
    (let [{:keys [status body]} (tu/api-get-transit "/api/store/types/subclass-of/dt/Resource/zorp/FlipFlop")]
      (is (= 200 status))
      (is (= :dt/Resource (:parent body)))
      (is (= :zorp/FlipFlop (:child body)))
      (is (true? (:subclass-of? body)) "Even Kevin inherits from Resource"))))

(deftest api-zorp-slots-test
  ;; Slot queries show you what properties a class has.
  (testing "HighTop slots include inherited slots via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/HighTop/slots")]
      (is (= 200 status))
      (is (= :zorp/HighTop (:class body)))
      (let [slot-idents (set (map :ident (:slots body)))]
        ;; From Resource (the beginning)
        (is (contains? slot-idents :dt/type))
        ;; From Footwear (grandparent)
        (is (contains? slot-idents :footwear/name))
        (is (contains? slot-idents :footwear/price))
        (is (contains? slot-idents :footwear/sentient?))
        ;; From Sneaker (parent)
        (is (contains? slot-idents :sneaker/bounce-factor))
        (is (contains? slot-idents :sneaker/glow-in-dark?)))))

  (testing "FlipFlop direct slots only"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/FlipFlop/slots/direct")]
      (is (= 200 status))
      (is (= :zorp/FlipFlop (:class body)))
      (is (= 4 (:count body)) "FlipFlop has 4 direct slots (its unique properties)")
      (let [slots (set (:slots body))]
        (is (contains? slots :flipflop/flop-frequency))
        (is (contains? slots :flipflop/toe-separator-count))
        (is (contains? slots :flipflop/escape-velocity))
        (is (contains? slots :flipflop/mood))
        (is (not (contains? slots :footwear/name)) "Should not include inherited slots"))))

  (testing "Sneaker direct slots via JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/zorp/Sneaker/slots/direct")]
      (is (= 200 status))
      (is (= "zorp/Sneaker" (:class body)))
      (is (= 5 (:count body)) "Sneaker has 5 direct slots")
      (let [slots (set (:slots body))]
        (is (contains? slots "sneaker/bounce-factor"))
        (is (contains? slots "sneaker/glow-in-dark?"))
        (is (contains? slots "sneaker/squeak-volume"))
        (is (contains? slots "sneaker/lace-type"))
        (is (contains? slots "sneaker/air-pump?")))))

  (testing "Boot direct slots via Transit"
    (let [{:keys [status body]} (tu/api-get-transit "/api/store/classes/zorp/Boot/slots/direct")]
      (is (= 200 status))
      (is (= :zorp/Boot (:class body)))
      (is (= 2 (:count body)) "Boot has 2 direct slots (the important ones)")
      (let [slots (set (:slots body))]
        (is (contains? slots :boot/vacuum-rated?))
        (is (contains? slots :boot/temperature-range))))))

(deftest api-zorp-abstract-class-test
  ;; The API tells you if a class is abstract. Important for error messages.
  (testing "Footwear is abstract"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/Footwear")]
      (is (= 200 status))
      (is (true? (:abstract? body)) "Footwear should be abstract - you can't sell 'a footwear'")))

  (testing "Sneaker is not abstract"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/Sneaker")]
      (is (= 200 status))
      (is (false? (:abstract? body)) "Sneaker is concrete - you can make instances"))))

(deftest api-zorp-entity-test
  ;; Entity endpoints give you the raw data.
  (testing "Zorp entity via EDN"
    (let [{:keys [status body]} (tu/api-get-edn "/api/store/entities/zorp/Footwear")]
      (is (= 200 status))
      (is (= :zorp/Footwear (:id body)))
      (is (= :dt/Class (:class body)))
      (let [entity (:entity body)]
        (is (= :zorp/Footwear (:db/ident entity)))
        (is (= :dt/Class (:dt/type entity)))
        (is (= "Footwear" (:dt/label entity)))
        (is (string? (:db/doc entity))))))

  (testing "Zorp property entity via JSON"
    (let [{:keys [status body]} (tu/api-get-json "/api/store/entities/footwear/name")]
      (is (= 200 status))
      (is (= "footwear/name" (:id body)))
      (is (= "dt/Property" (:class body))))))

(deftest api-zorp-instances-test
  ;; The instances endpoint shows what's actually in the database.
  (testing "Create instances and query via API"
    ;; Create some test instances
    (dt/make :zorp/HighTop {:footwear/name "API Test Dunks"
                            :footwear/size "api-test"
                            :footwear/price 199.99M
                            :sneaker/bounce-factor 25.5})
    (dt/make :zorp/FlipFlop {:footwear/name "API Test Kevin"
                             :footwear/size "api-test"
                             :footwear/sentient? true
                             :flipflop/mood "testing"})

    (testing "Footwear instances include all subclass instances"
      (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/Footwear/instances")]
        (is (= 200 status))
        (is (= :zorp/Footwear (:class body)))
        (is (>= (:count body) 2))
        (let [names (set (map :footwear/name (:instances body)))]
          (is (contains? names "API Test Dunks"))
          (is (contains? names "API Test Kevin")))))

    (testing "Sneaker instances via JSON"
      (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/zorp/Sneaker/instances")]
        (is (= 200 status))
        (is (= "zorp/Sneaker" (:class body)))
        (let [names (set (map #(get % :footwear/name) (:instances body)))]
          (is (contains? names "API Test Dunks"))
          (is (not (contains? names "API Test Kevin")) "FlipFlop is not a Sneaker"))))

    (testing "Instance values with numeric types via Transit"
      (let [{:keys [status body]} (tu/api-get-transit "/api/store/classes/zorp/HighTop/instances")]
        (is (= 200 status))
        (let [dunks (first (filter #(= "API Test Dunks" (:footwear/name %)) (:instances body)))]
          (is (some? dunks) "Should find the test dunks")
          ;; Transit preserves numeric types perfectly
          (is (= 25.5 (:sneaker/bounce-factor dunks)) "double preserved")
          (is (decimal? (:footwear/price dunks)) "BigDecimal preserved")
          (is (= 199.99M (:footwear/price dunks))))))))

(deftest api-zorp-numeric-precision-test
  ;; Numeric precision matters. Especially for prices.
  ;; "That'll be 1234567.89 credits." "Actually it's 1234567.890000001." NO.
  (testing "BigDecimal precision preserved in EDN"
    (dt/make :zorp/SpaceBoot {:footwear/name "Precision Boot"
                              :footwear/size "test"
                              :footwear/price 1234567.89M
                              :footwear/gravity-rating 0.000063})

    (let [{:keys [status body]} (tu/api-get-edn "/api/store/classes/zorp/SpaceBoot/instances")]
      (is (= 200 status))
      (let [boot (first (filter #(= "Precision Boot" (:footwear/name %)) (:instances body)))]
        (is (some? boot))
        (is (= 1234567.89M (:footwear/price boot)) "BigDecimal precision intact")
        (is (= 0.000063 (:footwear/gravity-rating boot)) "Double precision intact"))))

  (testing "Numeric values in JSON"
    ;; JSON doesn't have BigDecimal, so it becomes a number. Still precise, just a different type.
    (let [{:keys [status body]} (tu/api-get-json "/api/store/classes/zorp/SpaceBoot/instances")]
      (is (= 200 status))
      (let [boot (first (filter #(= "Precision Boot" (get % :footwear/name)) (:instances body)))]
        (is (some? boot))
        (is (number? (get boot :footwear/price)) "Price becomes JSON number")
        (is (number? (get boot :footwear/gravity-rating)) "Gravity becomes JSON number")))))
