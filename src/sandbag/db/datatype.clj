(ns sandbag.db.datatype
  "Datatype Metamodel API"
  (:refer-clojure :exclude [cat])
  (:require [datomic.api        :as    d]
            [clojure.pprint     :as   pp]
            [sandbag.db.rules   :refer [defrule clear-rulebase! all-rules] :as rule]
            [sandbag.db.fn      :refer [defdbfn dbfn clear-fnbase! all-dbfn] :as fn]
            [sandbag.db.datomic :refer [entity describe] :as db]))

(defn all-datatypes []
  (map first
    (d/q '[:find ?dt :in $ :where
           [?e :dt/type :dt/Class]
           [?e :db/ident ?dt]]
      (db/db))))

(defrule direct-instance-of [?dt ?e]
  [?e :dt/type ?dt])

(defrule instance-of [?dt ?e]
  [?e :dt/type ?dt])

(defrule instance-of [?dt ?e]
  [?i  :dt/subclass-of ?dt]
  [?i  :db/ident  ?p]
  (instance-of ?p ?e))

(defn direct-instances-of [dt]
   (map (comp db/entity first)
        (d/q '[:find ?e :in $ % ?dt :where
               (direct-instance-of ?dt ?e)]
             (db/db) (all-rules) dt)))

(defn all-instances-of [dt]
   (map (comp db/entity first)
        (d/q '[:find ?e :in $ % ?dt :where
               (instance-of ?dt ?e)]
             (db/db) (all-rules) dt)))

(defn all-named-instances-of [dt]
   (map first
        (d/q '[:find ?ident :in $ % ?dt :where
               [?e :db/ident ?ident]
               (instance-of ?dt ?e)]
             (db/db) (all-rules) dt)))

(defn all-classes []
  (all-named-instances-of :dt/Class))

(defn all-properties []
  (all-named-instances-of :dt/Property))

(defn make* "simple unchecked thing maker"
  ([dt] (make* dt {}))
  ([dt props]
   (let [row (merge props {:dt/type dt})
         result   @(d/transact (db/conn) [row])]
     (-> result :tempids vals first entity))))

(defn class-of [e]
  (-> e entity :dt/type))

(defn parents-of [dt]
  (:dt/subclass-of (entity dt)))

(defn ancestors-of [dt]
  (let [direct-parents (parents-of dt)]
    (distinct
      (concat direct-parents
              (mapcat ancestors-of direct-parents)))))

(defn direct-subclasses-of [dt]
  (d/q '[:find ?t :in $ ?dt :where
         [?e :dt/subclass-of ?dt]
         [?e :db/ident ?t]]
       (db/db) dt))

(defrule subclass-of [?dt ?s]
  [?e  :dt/subclass-of ?dt]
  [?e  :db/ident ?s])

(defrule subclass-of [?dt ?c]
  [?e :dt/subclass-of ?dt]
  (subclass-of ?e ?s)
  [?s :db/ident ?c])

(defn subclasses-of [dt]
  (mapv first
        (d/q '[:find ?c :in $ % ?dt :where
               [?t :db/ident  ?c]
               (subclass-of ?dt ?c)]
             (db/db) (all-rules) dt)))

(defn subclass-of? [dt c]
  (some? ((set (subclasses-of dt)) c)))

(defn instance-of? [dt e]
  (let [t (-> e entity :dt/type)]
    (or (= dt t) (subclass-of? dt t))))

(defn abstract? [dt]
  (:dt/abstract? (entity dt)))

(defrule direct-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defn direct-slots-of [dt]
  (:dt/slots (entity dt)))

(defrule effective-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defrule effective-slot [?dt ?s]
  [?dt  :dt/subclass-of ?p]
  (effective-slot ?p ?s))

(defn slots-of [dt]
  (set
    (map first
      (d/q '[:find ?s :in $ % ?dt :where
             (effective-slot ?dt ?s)]
           (db/db) (all-rules) dt))))


;; (defn map-slots [f e]
;;   (map (partial f dt) (datatype-slots dt)))

;; (defn slotwise [f dt]
;;   (let [slots (datatype-slots dt)
;;         vals  (map-datatype-slots f dt)]
;;   (zipmap slots vals)))


;; (defn about [dt]
;;   ;; TODO: do
;;   )


;; (defn entity-datatype [e]
;;   (:dt/type (entity e)))

;; (defn entity-slots [e]
;;   (datatype-slots (entity-datatype e)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; NOTE: subtly different from effective-slots

;; (defn domain-properties []  )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment


  (all-datatypes)
  (all-classes)
  (all-properties)

  (count (all-instances :dt/Resource))
  (map db/describe (all-instances :dt/Class))
  (map db/describe (all-instances :dt/Property))

  (describe :dt/Number)

  (d/touch (entity :dt/Number))

  ;; {:db/id 17592186045446, :db/ident :dt/Number,
  ;;  :db/doc "Numeric value type",
  ;;  :dt/type :dt/Class,
  ;;  :dt/namespace "system",
  ;;  :dt/label "Number",
  ;;  :dt/subclass-of #{:dt/Literal}}



  (all-named-instances-of-type :User)

  (datatype-parents :Twit)
  (datatype-ancestors :Twit)

  (:dt/type (make* :dt/Resource))

  ;; => :dt/Resource

  (describe (make* :User {:dt/label "test" :user/login "dan"}))
  (describe (make* :User {:dt/label "test" :user/login "jill"}))
  (describe (make* :User {:dt/label "test" :user/login "dexter"}))

  (all-instances-of :User)

  ;; => (#:db{:id 17592186045490} #:db{:id 17592186045492} #:db{:id 17592186045494} )

  (map :user/login (all-instances-of :User))

  ;; => ("dan" "dexter" jill)

  (class-of :dt/Property)

;; => :dt/Class



(datatype-subclasses :dt/Literal)

;; => [:db.type/instant :db.type/uri :db.type/keyword :db.type/bytes :db.type/fn :db.type/bigdec
;;     :db.type/long :db.type/uuid :db.type/bigint :db.type/float :db.type/tuple :db.type/symbol
;;     :db.type/boolean :dt/Number :db.type/string :db.type/double]

(datatype-subclasses :dt/Ref)

;; => [:dt/Fn :Twit :dt/Any :User]

(instance? :dt/Resource :dt/Literal)

;; =>true

  (subclass? :dt/Resource :dt/Literal)


  (datatype-slots :dt/Resource)
  ;; => #{:dt/label :dt/namespace :db/doc :db/ident :dt/type}

  (datatype-slots :dt/Class)

  ;; => #{:dt/list :dt/label :dt/namespace :dt/abstract? :db/doc :dt/slots :db/ident
  ;;      :dt/subclass-of :dt/type :dt/component}

  (datatype-slots :dt/Property)

  ;; => #{:db/unique :dt/label :dt/domain :dt/namespace :dt/range :db/fulltext :db/cardinality
  ;;       :db/doc :db/ident :dt/subproperty-of :dt/type}


  (describe :db/cardinality)

  ;; {:db/id 41,
  ;;  :db/ident :db/cardinality,
  ;;  :db/valueType :db.type/ref,
  ;;  :db/cardinality :db.cardinality/one,
  ;;  :db/doc "Property of an attribute. Two possible values: :db.cardinality/one for single-valued attributes, and :db.cardinality/many for many-valued attributes. Defaults to :db.cardinality/one.",
  ;;  :dt/type :dt/Property,
  ;;  :dt/domain :dt/Property,
  ;;  :dt/range :db.type/ref}

  (describe :dt/Property)

  (:dt/range (entity :dt/domain))


  (datatype-direct-subclasses :dt/Resource)

  ;; => #{[:dt/Ref] [:dt/Literal] [:dt/Resource**] [:dt/Property] [:dt/Resource*] [:dt/List] [:dt/Class]}

  )




;; TODO: change of semantics from metaclass to class?




;; (datatype-slots :user)
;; (entity-datatype :dt/dt)
;; (datatype-slots :any)



  ;; (def x (make* :List {:dt/first (entity (make* :User))}))

  ;;               :dt/rest (make* :List {:dt/first (make* :User)
  ;;                                      :dt/rest (make* :List
  ;;                                                      {:dt/first (make* :User)})})
