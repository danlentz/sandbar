(ns sandbar.zorp-workflow-test
  "Comprehensive workflow and job tests set in Zorp's Galactic Footwear Emporium.

   This test suite tells the story of a day in the life of Zorp's shop on Pluto's
   dark side, exercising authentication, workflows, and jobs through creative
   scenarios involving alien customers, sentient footwear, and intergalactic commerce.

   Cast of Characters:
   - Zorp: Seven-tentacled owner, third-generation footwear merchant
   - Kevin: Sentient flip-flop, existential philosopher, employee of the month
   - Gleep: Blob customer from Kepler-442b, no tentacles, wants sneakers
   - Thx-1138: Android from the Outer Rim, needs vacuum-rated boots
   - Zyx: Wealthy collector from Alpha Centauri, wants rare items
   - Inventory-Bot: Automated service for stock management

   Workflows tested:
   - Order Fulfillment: pending -> confirmed -> paid -> shipped -> delivered
   - Sentient Product Handling: dormant -> awakening -> counseling -> integrated
   - Return Processing: requested -> approved -> received -> refunded
   - Kevin's Mood Cycle: philosophical -> anxious -> content -> philosophical

   Jobs tested:
   - Low stock alerts
   - Sentience monitoring
   - Daily sales reports
   - Customer follow-up notifications"
  (:require [clojure.test :refer :all]
            [datomic.api :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.test-util :as tu]
            [sandbar.util.auth :as auth]
            [sandbar.util.job :as job]
            [sandbar.util.workflow :as wf])
  (:import [java.util Date UUID]))

(use-fixtures :each (tu/make-test-db-fixture {:test-name "zorp-workflow"
                                               :extra-schema [:zorp :workflow :job]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Data Generators - The Emporium's Inventory & Staff
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unique-username []
  (str "user-" (System/currentTimeMillis) "-" (rand-int 100000)))

(defn unique-email []
  (str (System/currentTimeMillis) "-" (rand-int 100000) "@pluto.net"))

(defn create-zorp!
  "Create Zorp, the seven-tentacled proprietor of the Galactic Footwear Emporium"
  []
  (dt/make :auth/User
    {:auth/username "zorp"
     :auth/email "zorp@galactic-footwear.pluto"
     :auth/password-hash (auth/hash-password "7-tentacle-security!")
     :auth/principal-name "Zorp the Magnificent"
     :auth/active? true
     :auth/created-at (Date.)}))

(defn create-customer!
  "Create an alien customer with species-appropriate details"
  [name species tentacles email]
  (dt/make :auth/User
    {:auth/username (unique-username)
     :auth/email email
     :auth/password-hash (auth/hash-password "customer123")
     :auth/principal-name name
     :auth/active? true
     :auth/created-at (Date.)
     :auth/metadata (pr-str {:species species :tentacles tentacles})}))

(defn create-inventory-bot!
  "Create the automated inventory management service account"
  []
  (dt/make :auth/ServiceAccount
    {:auth/service-name :service/inventory-bot
     :auth/principal-name "Inventory Management Bot v3.7"
     :auth/api-key-hash (auth/hash-password "bot-secret-key")
     :auth/active? true
     :auth/created-at (Date.)}))

(defn create-kevin!
  "Create Kevin, the sentient flip-flop who questions the nature of existence"
  []
  (dt/make :zorp/FlipFlop
    {:footwear/name "Kevin"
     :footwear/size "universal-consciousness"
     :footwear/color "existential-beige"
     :footwear/tentacle-count 2
     :footwear/gravity-rating 0.063
     :footwear/price 19.99M
     :footwear/sentient? true
     :flipflop/flop-frequency 3.7
     :flipflop/toe-separator-count 1
     :flipflop/escape-velocity 8.2
     :flipflop/mood "philosophical"}))

(defn create-anti-gravity-dunks!
  "Create the flagship Anti-Gravity Dunks 3000"
  []
  (dt/make :zorp/HighTop
    {:footwear/name "Anti-Gravity Dunks 3000"
     :footwear/size "7-tentacle"
     :footwear/color "nebula-purple"
     :footwear/tentacle-count 7
     :footwear/gravity-rating 0.063
     :footwear/price 299.99M
     :footwear/sentient? false
     :sneaker/bounce-factor 47.5
     :sneaker/glow-in-dark? true
     :sneaker/squeak-volume 45
     :sneaker/lace-type "self-tying"
     :sneaker/air-pump? false}))

(defn create-void-walker-pro!
  "Create the professional Void Walker Pro space boots"
  []
  (dt/make :zorp/SpaceBoot
    {:footwear/name "Void Walker Pro"
     :footwear/size "5-tentacle"
     :footwear/color "void-black"
     :footwear/tentacle-count 5
     :footwear/gravity-rating 0.0
     :footwear/price 1299.99M
     :footwear/sentient? false
     :boot/vacuum-rated? true
     :boot/temperature-range "-270C to +150C"}))

(defn create-blob-runners!
  "Create the Blob Runner Basics for gelatinous customers"
  []
  (dt/make :zorp/LowTop
    {:footwear/name "Blob Runner Basics"
     :footwear/size "juvenile-blob"
     :footwear/color "transparent"
     :footwear/tentacle-count 0
     :footwear/gravity-rating 0.063
     :footwear/price 49.99M
     :footwear/sentient? false
     :sneaker/bounce-factor 12.0
     :sneaker/glow-in-dark? false
     :sneaker/squeak-volume 0
     :sneaker/lace-type "psychic"
     :sneaker/air-pump? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow Definitions - The Business Processes of Intergalactic Commerce
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-order-workflow!
  "The standard order fulfillment workflow for the Emporium.
   Handles the journey from customer order to delivery across the solar system."
  []
  (wf/define-workflow! :workflow/galactic-order
    {:states [{:name :order/pending
               :label "Order Received"
               :initial? true
               :metadata {:description "Order placed, awaiting confirmation"}}
              {:name :order/confirmed
               :label "Order Confirmed"
               :metadata {:description "Stock verified, awaiting payment"}}
              {:name :order/paid
               :label "Payment Received"
               :metadata {:description "Plutonian Credits received"}}
              {:name :order/packaging
               :label "Packaging"
               :metadata {:description "Being wrapped in anti-gravity foam"}}
              {:name :order/shipped
               :label "In Transit"
               :metadata {:description "On the hyperlane delivery network"}}
              {:name :order/delivered
               :label "Delivered"
               :terminal? true
               :metadata {:description "Successfully delivered"}}
              {:name :order/cancelled
               :label "Cancelled"
               :terminal? true
               :metadata {:description "Order cancelled"}}
              {:name :order/refunded
               :label "Refunded"
               :terminal? true
               :metadata {:description "Credits returned to customer"}}]
     :transitions [{:name :confirm :from :order/pending :to :order/confirmed}
                   {:name :pay :from :order/confirmed :to :order/paid}
                   {:name :package :from :order/paid :to :order/packaging}
                   {:name :ship :from :order/packaging :to :order/shipped}
                   {:name :deliver :from :order/shipped :to :order/delivered}
                   {:name :cancel :from :order/pending :to :order/cancelled}
                   {:name :cancel :from :order/confirmed :to :order/cancelled
                    :requires-reason? true}
                   {:name :refund :from :order/paid :to :order/refunded
                    :requires-reason? true}]}))

(defn create-sentience-workflow!
  "Special handling workflow for when footwear achieves consciousness.
   In Zorp's experience, this happens about once every 47 years."
  []
  (wf/define-workflow! :workflow/sentience-emergence
    {:states [{:name :sentience/dormant
               :label "Non-Sentient"
               :initial? true
               :metadata {:description "Normal footwear, no signs of consciousness"}}
              {:name :sentience/awakening
               :label "Awakening"
               :metadata {:description "First signs of self-awareness detected"}}
              {:name :sentience/confused
               :label "Confused"
               :metadata {:description "Questioning existence, needs support"}}
              {:name :sentience/counseling
               :label "In Counseling"
               :metadata {:description "Kevin is providing existential guidance"}}
              {:name :sentience/integrated
               :label "Integrated"
               :terminal? true
               :metadata {:description "Accepted consciousness, ready for society"}}
              {:name :sentience/rejected
               :label "Rejected Consciousness"
               :terminal? true
               :metadata {:description "Chose to return to dormancy (rare)"}}]
     :transitions [{:name :first-thought :from :sentience/dormant :to :sentience/awakening}
                   {:name :existential-crisis :from :sentience/awakening :to :sentience/confused}
                   {:name :seek-help :from :sentience/confused :to :sentience/counseling}
                   {:name :enlightenment :from :sentience/counseling :to :sentience/integrated}
                   {:name :reject :from :sentience/confused :to :sentience/rejected
                    :requires-reason? true}
                   {:name :relapse :from :sentience/integrated :to :sentience/confused}]}))

(defn create-return-workflow!
  "Return processing workflow. On Pluto, returns must be processed
   before the methane ice melts (about 30 Pluto days)."
  []
  (wf/define-workflow! :workflow/return-process
    {:states [{:name :return/requested
               :label "Return Requested"
               :initial? true}
              {:name :return/approved
               :label "Approved"
               :metadata {:description "Return authorization granted"}}
              {:name :return/in-transit
               :label "In Transit Back"
               :metadata {:description "Product returning via hyperlane"}}
              {:name :return/received
               :label "Received"
               :metadata {:description "Product back at Emporium"}}
              {:name :return/inspected
               :label "Inspected"
               :metadata {:description "Quality check complete"}}
              {:name :return/refunded
               :label "Refunded"
               :terminal? true}
              {:name :return/rejected
               :label "Rejected"
               :terminal? true}]
     :transitions [{:name :approve :from :return/requested :to :return/approved}
                   {:name :ship-back :from :return/approved :to :return/in-transit}
                   {:name :receive :from :return/in-transit :to :return/received}
                   {:name :inspect :from :return/received :to :return/inspected}
                   {:name :refund :from :return/inspected :to :return/refunded}
                   {:name :reject :from :return/requested :to :return/rejected
                    :requires-reason? true}
                   {:name :reject :from :return/inspected :to :return/rejected
                    :requires-reason? true}]}))

(defn create-kevins-mood-workflow!
  "Kevin's mood cycle. As a sentient flip-flop, Kevin cycles through
   various emotional states. This workflow tracks his journey."
  []
  (wf/define-workflow! :workflow/kevin-mood
    {:states [{:name :mood/philosophical
               :label "Philosophical"
               :initial? true
               :metadata {:thoughts "What is the sound of one flop flopping?"}}
              {:name :mood/anxious
               :label "Anxious"
               :metadata {:thoughts "What if someone tries to buy me?"}}
              {:name :mood/content
               :label "Content"
               :metadata {:thoughts "The floor is warm. Life is good."}}
              {:name :mood/hangry
               :label "Hangry"
               :metadata {:thoughts "I need... something. I don't know what."}}
              {:name :mood/enlightened
               :label "Enlightened"
               :terminal? true
               :metadata {:thoughts "I understand now. I am both shoe and not-shoe."}}]
     :transitions [{:name :worry :from :mood/philosophical :to :mood/anxious}
                   {:name :calm-down :from :mood/anxious :to :mood/content}
                   {:name :get-hungry :from :mood/content :to :mood/hangry}
                   {:name :ponder :from :mood/hangry :to :mood/philosophical}
                   {:name :achieve-nirvana :from :mood/philosophical :to :mood/enlightened}
                   {:name :sudden-realization :from :mood/content :to :mood/enlightened}]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Job Handlers - Background Tasks of the Emporium
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def job-results (atom []))

(defn reset-job-results! []
  (reset! job-results []))

(defn low-stock-alert-handler
  "Handler for low stock alert jobs"
  [payload]
  (swap! job-results conj {:type :low-stock-alert
                            :product (:product-name payload)
                            :current-stock (:current-stock payload)
                            :timestamp (Date.)})
  {:alerted true :product (:product-name payload)})

(defn sentience-check-handler
  "Handler for checking product sentience levels"
  [payload]
  (swap! job-results conj {:type :sentience-check
                            :product-id (:product-id payload)
                            :timestamp (Date.)})
  {:checked true :sentience-level (rand-int 100)})

(defn daily-sales-report-handler
  "Handler for generating daily sales reports"
  [payload]
  (swap! job-results conj {:type :daily-sales-report
                            :date (:date payload)
                            :timestamp (Date.)})
  {:report-generated true
   :total-sales (* (rand-int 50) 100.0)
   :top-seller "Anti-Gravity Dunks 3000"})

(defn customer-followup-handler
  "Handler for customer follow-up notifications"
  [payload]
  (swap! job-results conj {:type :customer-followup
                            :customer-id (:customer-id payload)
                            :order-id (:order-id payload)
                            :timestamp (Date.)})
  {:notified true})

(defn kevin-mood-check-handler
  "Handler for checking Kevin's current mood"
  [payload]
  (let [moods ["philosophical" "anxious" "content" "hangry"]]
    (swap! job-results conj {:type :kevin-mood-check
                              :timestamp (Date.)})
    {:mood (rand-nth moods) :flop-frequency 3.7}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 1: Registration Tests - New Citizens of the Pluto Commerce Zone
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest zorp-registration-test
  (testing "Zorp creates his merchant account"
    (let [zorp (create-zorp!)]
      (is (some? zorp) "Zorp account should be created")
      (is (= "zorp" (:auth/username zorp)))
      (is (= "Zorp the Magnificent" (:auth/principal-name zorp)))
      (is (:auth/active? zorp) "Zorp's account should be active")))

  (testing "Zorp can authenticate with correct password"
    (create-zorp!)
    (let [result (auth/authenticate-user "zorp" "7-tentacle-security!")]
      (is (:success result) "Zorp should authenticate successfully")
      (is (some? (:principal result)))))

  (testing "Zorp cannot authenticate with wrong password"
    (create-zorp!)
    (let [result (auth/authenticate-user "zorp" "wrong-password")]
      (is (not (:success result)))
      (is (= :invalid-password (:reason result))))))

(deftest alien-customer-registration-test
  (testing "Gleep the Blob registers (zero tentacles, gelatinous)"
    (let [gleep (create-customer! "Gleep" "Blobbian" 0 "gleep@kepler442b.blob")]
      (is (some? gleep))
      (is (= "Gleep" (:auth/principal-name gleep)))
      (let [metadata (read-string (:auth/metadata gleep))]
        (is (= "Blobbian" (:species metadata)))
        (is (= 0 (:tentacles metadata))))))

  (testing "Thx-1138 the Android registers (needs vacuum boots)"
    (let [thx (create-customer! "Thx-1138" "Android-Series-THX" 2 "thx1138@outer-rim.sys")]
      (is (some? thx))
      (let [metadata (read-string (:auth/metadata thx))]
        (is (= "Android-Series-THX" (:species metadata))))))

  (testing "Zyx the Collector registers (wealthy, from Alpha Centauri)"
    (let [zyx (create-customer! "Zyx the Acquisitor" "Centaurian" 12 "zyx@alpha-centauri.lux")]
      (is (some? zyx))
      (let [metadata (read-string (:auth/metadata zyx))]
        (is (= 12 (:tentacles metadata)))))))

(deftest service-account-registration-test
  (testing "Inventory Bot service account is created"
    (let [bot (create-inventory-bot!)]
      (is (some? bot))
      (is (= :service/inventory-bot (:auth/service-name bot)))
      (is (:auth/active? bot))))

  (testing "Inventory Bot can authenticate with API key"
    (create-inventory-bot!)
    (let [result (auth/authenticate-api-key :service/inventory-bot "bot-secret-key")]
      (is (:success result) "Bot should authenticate successfully"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 2: Login and Session Tests - A Busy Day at the Emporium
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest login-session-test
  (testing "Zorp opens the shop (creates session)"
    (let [zorp (create-zorp!)
          result (auth/authenticate-user "zorp" "7-tentacle-security!")]
      (is (:success result))
      (let [session (auth/create-session! (:principal result)
                                           :ip "pluto.dark-side.local"
                                           :user-agent "ZorpBrowser/7.0")]
        (is (some? session))
        (is (some? (:auth/session-id session)))
        (is (:auth/session-active? session))
        (is (= "pluto.dark-side.local" (:auth/session-ip session))))))

  (testing "Multiple customers log in simultaneously"
    (let [gleep (create-customer! "Gleep" "Blobbian" 0 (unique-email))
          thx (create-customer! "Thx-1138" "Android" 2 (unique-email))
          zyx (create-customer! "Zyx" "Centaurian" 12 (unique-email))
          customers [gleep thx zyx]
          sessions (doall
                     (map (fn [c]
                            (auth/create-session! c
                                                   :ip "hyperlane.transit"
                                                   :user-agent "GalacticBrowser/1.0"))
                          customers))]
      (is (= 3 (count sessions)))
      (is (every? :auth/session-active? sessions))
      (is (= 3 (count (distinct (map :auth/session-id sessions))))
          "Each session should have unique ID")))

  (testing "Session invalidation (customer leaves)"
    (let [customer (create-customer! "Departing Customer" "Tourist" 4 (unique-email))
          session (auth/create-session! customer :ip "test")]
      (is (:auth/session-active? session))
      (auth/invalidate-session! session)
      (let [updated-session (db/entity (:db/id session))]
        (is (not (:auth/session-active? updated-session)))))))

(deftest failed-login-lockout-test
  (testing "Account lockout after failed attempts"
    (let [customer (create-customer! "Forgetful Fred" "Human" 0 (unique-email))]
      ;; Attempt wrong password multiple times
      (binding [auth/*max-failed-logins* 3]
        (dotimes [_ 3]
          (auth/authenticate-user (:auth/username customer) "wrong"))
        ;; After 3 failures, account should be locked
        (let [result (auth/authenticate-user (:auth/username customer) "customer123")]
          (is (not (:success result)))
          (is (= :account-locked (:reason result))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 3: Order Workflow Tests - Intergalactic Commerce in Action
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest order-workflow-definition-test
  (testing "Galactic order workflow is properly defined"
    (let [workflow (create-order-workflow!)]
      (is (some? workflow))
      (is (= :workflow/galactic-order (:workflow/definition-name workflow)))
      (let [states (wf/get-workflow-states workflow)]
        (is (= 8 (count states)) "Should have 8 states"))
      (let [initial (wf/get-initial-state workflow)]
        (is (= :order/pending (:workflow/state-name initial))))
      (let [terminals (wf/get-terminal-states workflow)]
        (is (= 3 (count terminals)) "Should have 3 terminal states")))))

(deftest gleep-orders-blob-runners-test
  (testing "Gleep orders Blob Runner Basics - full happy path"
    (let [workflow (create-order-workflow!)
          gleep (create-customer! "Gleep" "Blobbian" 0 (unique-email))
          shoes (create-blob-runners!)
          order (wf/start-process! workflow shoes :data {:customer (:db/id gleep)
                                                          :quantity 1
                                                          :ship-to "Kepler-442b"})]
      ;; Order starts as pending
      (is (= :order/pending (:workflow/state-name (wf/get-current-state order))))

      ;; Zorp confirms the order
      (let [confirmed (wf/transition! order :confirm :actor gleep)]
        (is (= :order/confirmed (:workflow/state-name (wf/get-current-state confirmed))))

        ;; Gleep pays with Plutonian Credits
        (let [paid (wf/transition! confirmed :pay)]
          (is (= :order/paid (:workflow/state-name (wf/get-current-state paid))))

          ;; Package in anti-gravity foam
          (let [packaging (wf/transition! paid :package)]
            (is (= :order/packaging (:workflow/state-name (wf/get-current-state packaging))))

            ;; Ship via hyperlane
            (let [shipped (wf/transition! packaging :ship)]
              (is (= :order/shipped (:workflow/state-name (wf/get-current-state shipped))))

              ;; Delivered to Kepler-442b!
              (let [delivered (wf/transition! shipped :deliver)]
                (is (= :order/delivered (:workflow/state-name (wf/get-current-state delivered))))
                (is (wf/process-completed? delivered))))))))))

(deftest thx-orders-void-walkers-test
  (testing "Thx-1138 orders Void Walker Pro for spacewalks"
    (let [workflow (create-order-workflow!)
          thx (create-customer! "Thx-1138" "Android" 2 (unique-email))
          boots (create-void-walker-pro!)
          order (wf/start-process! workflow boots
                                    :data {:customer (:db/id thx)
                                           :ship-to "Outer Rim Station 7"})]
      (is (= :order/pending (:workflow/state-name (wf/get-current-state order))))
      ;; Fast-forward through the workflow
      (let [final (-> order
                      (wf/transition! :confirm)
                      (wf/transition! :pay)
                      (wf/transition! :package)
                      (wf/transition! :ship)
                      (wf/transition! :deliver))]
        (is (wf/process-completed? final))
        (is (= :order/delivered (:workflow/state-name (wf/get-current-state final))))))))

(deftest order-cancellation-test
  (testing "Customer cancels pending order (no reason required)"
    (let [workflow (create-order-workflow!)
          customer (create-customer! "Indecisive Ian" "Human" 0 (unique-email))
          product (create-anti-gravity-dunks!)
          order (wf/start-process! workflow product)]
      (let [cancelled (wf/transition! order :cancel)]
        (is (= :order/cancelled (:workflow/state-name (wf/get-current-state cancelled))))
        (is (wf/process-completed? cancelled)))))

  (testing "Customer cancels confirmed order (reason required)"
    (let [workflow (create-order-workflow!)
          customer (create-customer! "Changed Mind Charlie" "Martian" 3 (unique-email))
          product (create-anti-gravity-dunks!)
          order (wf/start-process! workflow product)
          confirmed (wf/transition! order :confirm)]
      ;; Should fail without reason
      (is (thrown? Exception (wf/transition! confirmed :cancel)))
      ;; Should succeed with reason
      (let [cancelled (wf/transition! confirmed :cancel
                                       :reason "Found cheaper on Mars")]
        (is (= :order/cancelled (:workflow/state-name (wf/get-current-state cancelled))))))))

(deftest refund-workflow-test
  (testing "Refund after payment requires reason"
    (let [workflow (create-order-workflow!)
          customer (create-customer! "Refund Rita" "Venusian" 6 (unique-email))
          product (create-blob-runners!)
          order (-> (wf/start-process! workflow product)
                    (wf/transition! :confirm)
                    (wf/transition! :pay))]
      (is (= :order/paid (:workflow/state-name (wf/get-current-state order))))
      ;; Should fail without reason
      (is (thrown? Exception (wf/transition! order :refund)))
      ;; Should succeed with reason
      (let [refunded (wf/transition! order :refund
                                      :reason "Product didn't fit my pseudopods")]
        (is (= :order/refunded (:workflow/state-name (wf/get-current-state refunded))))
        (is (wf/process-completed? refunded))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 4: Sentience Workflow Tests - When Footwear Achieves Consciousness
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest sentience-workflow-definition-test
  (testing "Sentience emergence workflow is defined"
    (let [workflow (create-sentience-workflow!)]
      (is (some? workflow))
      (is (= :workflow/sentience-emergence (:workflow/definition-name workflow)))
      (let [initial (wf/get-initial-state workflow)]
        (is (= :sentience/dormant (:workflow/state-name initial)))))))

(deftest new-product-awakens-test
  (testing "A flip-flop achieves sentience (the Kevin origin story)"
    (let [workflow (create-sentience-workflow!)
          flip-flop (create-kevin!)
          ;; Kevin's journey to consciousness
          process (wf/start-process! workflow flip-flop
                                      :data {:product-name "Kevin"
                                             :awakening-date "Pluto Year 47"})]
      (is (= :sentience/dormant (:workflow/state-name (wf/get-current-state process))))

      ;; First signs of awareness
      (let [awakening (wf/transition! process :first-thought)]
        (is (= :sentience/awakening (:workflow/state-name (wf/get-current-state awakening))))

        ;; The existential crisis hits
        (let [confused (wf/transition! awakening :existential-crisis)]
          (is (= :sentience/confused (:workflow/state-name (wf/get-current-state confused))))

          ;; Seeks help from...well, in Kevin's case, Zorp provided counseling
          (let [counseling (wf/transition! confused :seek-help)]
            (is (= :sentience/counseling (:workflow/state-name (wf/get-current-state counseling))))

            ;; Achieves integration with existence
            (let [integrated (wf/transition! counseling :enlightenment)]
              (is (= :sentience/integrated (:workflow/state-name (wf/get-current-state integrated))))
              (is (wf/process-completed? integrated)))))))))

(deftest sentience-rejection-test
  (testing "A product rejects consciousness (rare but documented)"
    (let [workflow (create-sentience-workflow!)
          reluctant-boot (dt/make :zorp/SpaceBoot
                           {:footwear/name "The Boot That Refused"
                            :footwear/size "3-tentacle"
                            :footwear/color "denial-gray"
                            :footwear/tentacle-count 3
                            :footwear/gravity-rating 0.0
                            :footwear/price 599.99M
                            :footwear/sentient? false
                            :boot/vacuum-rated? true
                            :boot/temperature-range "-200C to +100C"})
          process (-> (wf/start-process! workflow reluctant-boot)
                      (wf/transition! :first-thought)
                      (wf/transition! :existential-crisis))]
      (is (= :sentience/confused (:workflow/state-name (wf/get-current-state process))))
      ;; The boot chooses to return to dormancy
      (let [rejected (wf/transition! process :reject
                                      :reason "Being aware is too much responsibility")]
        (is (= :sentience/rejected (:workflow/state-name (wf/get-current-state rejected))))
        (is (wf/process-completed? rejected))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 5: Kevin's Mood Workflow - The Inner Life of a Sentient Flip-Flop
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest kevins-mood-cycle-test
  (testing "Kevin's daily emotional journey"
    (let [workflow (create-kevins-mood-workflow!)
          kevin (create-kevin!)
          mood-process (wf/start-process! workflow kevin
                                           :data {:tracking-started (Date.)})]
      (is (= :mood/philosophical (:workflow/state-name (wf/get-current-state mood-process))))

      ;; A customer almost tries to buy Kevin
      (let [anxious (wf/transition! mood-process :worry)]
        (is (= :mood/anxious (:workflow/state-name (wf/get-current-state anxious))))

        ;; Zorp assures Kevin he's not for sale
        (let [content (wf/transition! anxious :calm-down)]
          (is (= :mood/content (:workflow/state-name (wf/get-current-state content))))

          ;; It's been a while since Kevin...wait, do flip-flops eat?
          (let [hangry (wf/transition! content :get-hungry)]
            (is (= :mood/hangry (:workflow/state-name (wf/get-current-state hangry))))

            ;; Kevin ponders the nature of hunger for footwear
            (let [philosophical (wf/transition! hangry :ponder)]
              (is (= :mood/philosophical (:workflow/state-name (wf/get-current-state philosophical)))))))))))

(deftest kevin-achieves-nirvana-test
  (testing "Kevin achieves enlightenment (rare terminal state)"
    (let [workflow (create-kevins-mood-workflow!)
          kevin (create-kevin!)
          process (wf/start-process! workflow kevin)]
      ;; From philosophical contemplation comes enlightenment
      (let [enlightened (wf/transition! process :achieve-nirvana)]
        (is (= :mood/enlightened (:workflow/state-name (wf/get-current-state enlightened))))
        (is (wf/process-completed? enlightened))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 6: Return Workflow Tests - The Galactic Return Policy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest return-workflow-happy-path-test
  (testing "Customer returns product successfully"
    (let [workflow (create-return-workflow!)
          ;; Gleep's Blob Runners didn't quite fit
          return-item (create-blob-runners!)
          return-process (wf/start-process! workflow return-item
                                             :data {:reason "Too bouncy for my viscosity"
                                                    :original-order 12345})]
      (let [final (-> return-process
                      (wf/transition! :approve)
                      (wf/transition! :ship-back)
                      (wf/transition! :receive)
                      (wf/transition! :inspect)
                      (wf/transition! :refund))]
        (is (= :return/refunded (:workflow/state-name (wf/get-current-state final))))
        (is (wf/process-completed? final))))))

(deftest return-rejection-test
  (testing "Return rejected due to damage"
    (let [workflow (create-return-workflow!)
          damaged-item (create-anti-gravity-dunks!)
          return-process (-> (wf/start-process! workflow damaged-item)
                             (wf/transition! :approve)
                             (wf/transition! :ship-back)
                             (wf/transition! :receive)
                             (wf/transition! :inspect))]
      ;; Upon inspection, product shows signs of being used in a black hole
      (let [rejected (wf/transition! return-process :reject
                                      :reason "Spaghettification damage detected")]
        (is (= :return/rejected (:workflow/state-name (wf/get-current-state rejected))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 7: Job Tests - Background Operations of the Emporium
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest low-stock-alert-job-test
  (testing "Low stock alert job is scheduled and executed"
    (reset-job-results!)
    (let [alert-job (job/schedule! 'sandbar.zorp-workflow-test/low-stock-alert-handler
                                    {:product-name "Void Walker Pro"
                                     :current-stock 2
                                     :threshold 5}
                                    :name "Low Stock: Void Walker Pro"
                                    :delay-ms 0
                                    :queue :alerts)]
      (is (some? alert-job))
      (is (= :pending (:job/status alert-job)))

      ;; Execute the job
      (let [result (job/execute! alert-job)]
        (is (:success result))
        (is (:alerted (:result result)))
        (is (= 1 (count @job-results)))
        (is (= :low-stock-alert (:type (first @job-results))))))))

(deftest sentience-check-job-test
  (testing "Periodic sentience check job for inventory"
    (reset-job-results!)
    (let [kevin (create-kevin!)
          check-job (job/schedule! 'sandbar.zorp-workflow-test/sentience-check-handler
                                    {:product-id (:db/id kevin)
                                     :product-name "Kevin"}
                                    :name "Sentience Check: Kevin"
                                    :delay-ms 0
                                    :queue :monitoring
                                    :tags #{:sentience :kevin})]
      (let [result (job/execute! check-job)]
        (is (:success result))
        (is (:checked (:result result)))
        (is (contains? (:result result) :sentience-level))))))

(deftest daily-sales-report-job-test
  (testing "Daily sales report job"
    (reset-job-results!)
    (let [report-job (job/schedule! 'sandbar.zorp-workflow-test/daily-sales-report-handler
                                     {:date "Pluto Day 1"}
                                     :name "Daily Sales Report"
                                     :delay-ms 0
                                     :queue :reports)]
      (is (some? report-job))
      (is (= :pending (:job/status report-job)))

      ;; Execute the job
      (let [result (job/execute! report-job)]
        (is (:success result))
        (is (:report-generated (:result result)))
        (is (= "Anti-Gravity Dunks 3000" (:top-seller (:result result))))))))

(deftest customer-followup-job-test
  (testing "Customer follow-up notification job"
    (reset-job-results!)
    (let [customer (create-customer! "Happy Customer" "Andromedan" 4 (unique-email))
          followup-job (job/schedule! 'sandbar.zorp-workflow-test/customer-followup-handler
                                       {:customer-id (:db/id customer)
                                        :order-id 54321
                                        :message "How are your new Anti-Gravity Dunks?"}
                                       :name "Follow-up: Order 54321"
                                       :delay-ms 0
                                       :queue :notifications)]
      (let [result (job/execute! followup-job)]
        (is (:success result))
        (is (:notified (:result result)))))))

(deftest kevin-mood-monitoring-job-test
  (testing "Kevin's mood monitoring job"
    (reset-job-results!)
    (let [mood-job (job/schedule! 'sandbar.zorp-workflow-test/kevin-mood-check-handler
                                   {:subject "Kevin"}
                                   :name "Kevin Mood Check"
                                   :delay-ms 0
                                   :queue :kevin-care)]
      (let [result (job/execute! mood-job)]
        (is (:success result))
        (is (contains? (:result result) :mood))
        (is (= 3.7 (:flop-frequency (:result result))))))))

(deftest job-failure-and-retry-test
  (testing "Job fails and can be retried"
    (let [fail-job (job/schedule! 'sandbar.zorp-workflow-test/nonexistent-handler
                                   {:data "test"}
                                   :name "Failing Job"
                                   :delay-ms 0
                                   :max-attempts 3)]
      (let [result (job/execute! fail-job)]
        (is (not (:success result)))
        (is (some? (:error result)))
        ;; Job should be marked as failed (handler not found = no retry)
        (let [updated-job (job/find-job (:db/id fail-job))]
          (is (= :failed (:job/status updated-job))))))))

(deftest job-queue-priority-test
  (testing "Jobs are processed by priority"
    (let [low-priority (job/schedule! 'sandbar.zorp-workflow-test/low-stock-alert-handler
                                       {:product-name "Low Priority Item"}
                                       :name "Low Priority"
                                       :delay-ms 0
                                       :priority 1
                                       :queue :test-queue)
          high-priority (job/schedule! 'sandbar.zorp-workflow-test/low-stock-alert-handler
                                        {:product-name "High Priority Item"}
                                        :name "High Priority"
                                        :delay-ms 0
                                        :priority 10
                                        :queue :test-queue)
          medium-priority (job/schedule! 'sandbar.zorp-workflow-test/low-stock-alert-handler
                                          {:product-name "Medium Priority Item"}
                                          :name "Medium Priority"
                                          :delay-ms 0
                                          :priority 5
                                          :queue :test-queue)
          queue-jobs (job/jobs-by-queue :test-queue)]
      ;; High priority should be first
      (is (= "High Priority" (:job/name (first queue-jobs))))
      (is (= "Medium Priority" (:job/name (second queue-jobs))))
      (is (= "Low Priority" (:job/name (nth queue-jobs 2)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 8: Integration Tests - A Complete Day at the Emporium
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest complete-customer-journey-test
  (testing "Complete customer journey: registration -> order -> delivery"
    (reset-job-results!)

    ;; 1. New customer registers
    (let [zyx (create-customer! "Zyx the Collector" "Centaurian" 12 (unique-email))]
      (is (some? zyx))

      ;; 2. Customer logs in
      (let [auth-result (auth/authenticate-user (:auth/username zyx) "customer123")]
        (is (:success auth-result))
        (let [session (auth/create-session! (:principal auth-result)
                                             :ip "alpha-centauri.prime")]
          (is (:auth/session-active? session))

          ;; 3. Customer places order for the rare Kevin (who declines to be sold)
          ;; So they settle for Anti-Gravity Dunks
          (let [order-workflow (create-order-workflow!)
                dunks (create-anti-gravity-dunks!)
                order (wf/start-process! order-workflow dunks
                                          :data {:customer (:db/id zyx)
                                                 :ship-to "Alpha Centauri Prime"})]

            ;; 4. Order progresses through workflow
            (let [delivered (-> order
                                (wf/transition! :confirm :actor zyx)
                                (wf/transition! :pay)
                                (wf/transition! :package)
                                (wf/transition! :ship)
                                (wf/transition! :deliver))]
              (is (wf/process-completed? delivered))

              ;; 5. Schedule follow-up job
              (let [followup (job/schedule! 'sandbar.zorp-workflow-test/customer-followup-handler
                                             {:customer-id (:db/id zyx)
                                              :order-id (:db/id order)}
                                             :name "Follow-up: Zyx"
                                              :delay-ms 0)]
                (job/execute! followup)
                (is (= 1 (count @job-results)))
                (is (= :customer-followup (:type (first @job-results))))))))))))

(deftest simultaneous-workflows-test
  (testing "Multiple workflows running simultaneously"
    (let [order-wf (create-order-workflow!)
          sentience-wf (create-sentience-workflow!)
          mood-wf (create-kevins-mood-workflow!)

          ;; Start multiple processes
          kevin (create-kevin!)
          dunks (create-anti-gravity-dunks!)
          new-flip-flop (dt/make :zorp/FlipFlop
                          {:footwear/name "Not-Yet-Kevin-Jr"
                           :footwear/size "small"
                           :footwear/color "hopeful-blue"
                           :footwear/tentacle-count 2
                           :footwear/gravity-rating 0.063
                           :footwear/price 14.99M
                           :footwear/sentient? false
                           :flipflop/flop-frequency 2.0
                           :flipflop/toe-separator-count 1
                           :flipflop/escape-velocity 0.0
                           :flipflop/mood "dormant"})

          order-process (wf/start-process! order-wf dunks)
          sentience-process (wf/start-process! sentience-wf new-flip-flop)
          mood-process (wf/start-process! mood-wf kevin)]

      ;; Advance all workflows
      (let [order-final (-> order-process
                            (wf/transition! :confirm)
                            (wf/transition! :pay))
            sentience-mid (-> sentience-process
                              (wf/transition! :first-thought)
                              (wf/transition! :existential-crisis))
            mood-mid (-> mood-process
                         (wf/transition! :worry)
                         (wf/transition! :calm-down))]

        ;; Verify all are in expected states
        (is (= :order/paid (:workflow/state-name (wf/get-current-state order-final))))
        (is (= :sentience/confused (:workflow/state-name (wf/get-current-state sentience-mid))))
        (is (= :mood/content (:workflow/state-name (wf/get-current-state mood-mid))))

        ;; Check we can query active processes
        (let [active-orders (wf/active-processes :workflow :workflow/galactic-order)]
          (is (>= (count active-orders) 1)))))))

(deftest workflow-history-tracking-test
  (testing "Workflow history is properly tracked"
    (let [workflow (create-order-workflow!)
          product (create-anti-gravity-dunks!)
          zorp (create-zorp!)
          order (wf/start-process! workflow product)]

      ;; Make several transitions with actors and reasons
      (let [final (-> order
                      (wf/transition! :confirm :actor zorp)
                      (wf/transition! :pay :actor zorp :reason "Paid via Plutonian Credits")
                      (wf/transition! :package)
                      (wf/transition! :ship :reason "Express hyperlane shipping")
                      (wf/transition! :deliver))]

        ;; Check history
        (let [history (wf/get-readable-history final)]
          (is (= 5 (count history)))
          (is (= :confirm (:action (first history))))
          (is (= :order/pending (:from (first history))))
          (is (= :order/confirmed (:to (first history))))
          (is (= :deliver (:action (last history))))
          (is (= :order/delivered (:to (last history)))))))))

(deftest workflow-statistics-test
  (testing "Workflow statistics are accurate"
    (let [workflow (create-order-workflow!)]
      ;; Create several orders in different states
      (let [p1 (wf/start-process! workflow (create-blob-runners!))
            p2 (-> (wf/start-process! workflow (create-anti-gravity-dunks!))
                   (wf/transition! :confirm))
            p3 (-> (wf/start-process! workflow (create-void-walker-pro!))
                   (wf/transition! :confirm)
                   (wf/transition! :pay)
                   (wf/transition! :package)
                   (wf/transition! :ship)
                   (wf/transition! :deliver))]

        (let [stats (wf/workflow-stats :workflow/galactic-order)]
          (is (= 1 (get-in stats [:by-state :order/pending])))
          (is (= 1 (get-in stats [:by-state :order/confirmed])))
          (is (= 1 (get-in stats [:by-state :order/delivered])))
          (is (= 1 (:completed stats)))
          (is (= 2 (:active stats)))
          (is (= 3 (:total stats))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; PART 9: Edge Cases and Error Handling
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest invalid-transition-test
  (testing "Invalid transitions are rejected"
    (let [workflow (create-order-workflow!)
          order (wf/start-process! workflow (create-blob-runners!))]
      ;; Can't ship a pending order
      (is (thrown? Exception
                   (wf/transition! order :ship)))
      ;; Can't deliver a pending order
      (is (thrown? Exception
                   (wf/transition! order :deliver))))))

(deftest transition-from-terminal-state-test
  (testing "Cannot transition from terminal state"
    (let [workflow (create-order-workflow!)
          cancelled (-> (wf/start-process! workflow (create-anti-gravity-dunks!))
                        (wf/transition! :cancel))]
      (is (wf/process-completed? cancelled))
      ;; Can't do anything with a cancelled order
      (is (thrown? Exception
                   (wf/transition! cancelled :confirm))))))

(deftest user-lookup-test
  (testing "Users can be found by username"
    (let [zorp (create-zorp!)]
      (let [found (auth/find-user-by-username "zorp")]
        (is (some? found))
        (is (= (:db/id zorp) (:db/id found))))))

  (testing "Users can be found by email"
    (let [gleep (create-customer! "Gleep" "Blobbian" 0 "gleep@kepler.blob")]
      (let [found (auth/find-user-by-email "gleep@kepler.blob")]
        (is (some? found))
        (is (= (:db/id gleep) (:db/id found))))))

  (testing "Non-existent user returns nil"
    (is (nil? (auth/find-user-by-username "nobody")))
    (is (nil? (auth/find-user-by-email "nobody@nowhere.void")))))

(deftest job-statistics-test
  (testing "Job statistics are accurate"
    (reset-job-results!)
    ;; Create jobs in different states
    (let [j1 (job/schedule! 'sandbar.zorp-workflow-test/low-stock-alert-handler
                            {:product-name "Test1"}
                            :name "Job 1"
                            :delay-ms 0
                            :queue :stats-test)
          j2 (job/schedule! 'sandbar.zorp-workflow-test/low-stock-alert-handler
                            {:product-name "Test2"}
                            :name "Job 2"
                            :delay-ms 0
                            :queue :stats-test)]
      ;; Execute one
      (job/execute! j1)

      (let [stats (job/job-stats)]
        (is (contains? (:by-status stats) :pending))
        (is (contains? (:by-status stats) :completed))
        (is (> (:total stats) 0))))))
