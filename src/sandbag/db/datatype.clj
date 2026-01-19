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
           [?e :dt/dt :dt/dt]
           [?e :db/ident ?dt]]
      (db/db))))

(defrule instance-of [?dt ?e]
  [?e :dt/dt ?dt])

(defrule instance-of [?dt ?e]
  [?i  :dt/parent ?dt]
  [?i  :db/ident  ?p]
  (instance-of ?p ?e))

(defn all-instances [dt]
   (map first
        (d/q '[:find ?e :in $ % ?dt :where
               (instance-of ?dt ?e)]
             (db/db) (all-rules) dt)))

(defn datatype-doc [dt]
  (:db/doc (entity dt)))

(defn datatype-parents [dt]
  (:dt/parent (entity dt)))

(defn datatype-ancestors [dt]
  (let [direct-parents (datatype-parents dt)]
    (distinct
      (concat direct-parents
        (mapcat datatype-parents direct-parents)))))

(defrule direct-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defn datatype-direct-slots [dt]
  (:dt/slots (entity dt)))

(defrule effective-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defrule effective-slot [?dt ?s]
  [?dt  :dt/parent ?p]
  (effective-slot ?p ?s))

(defn datatype-slots [dt]
  (set
    (map first
      (d/q '[:find ?s :in $ % ?dt :where
             (effective-slot ?dt ?s)]
        (db/db) (all-rules) dt))))

(defn slot-valuetype [dt slot]
  (ffirst
    (d/q '[:find ?t :in $ ?dt ?s :where
           [?e :dt/dt    :dt/dt]
           [?e :db/ident    ?dt]
           [?i :db/ident     ?s]
           [?e :dt/slots     ?i]
           [?i :db/valueType ?v]
           [?v :db/ident     ?t]]
      (db/db) dt slot)))

(defn slot-doc [dt slot]
  (ffirst
    (d/q '[:find ?d :in $ ?dt ?s :where
           [?e :dt/dt    :dt/dt]
           [?e :db/ident    ?dt]
           [?i :db/ident     ?s]
           [?e :dt/slots     ?i]
           [?i :db/doc      ?d]]
      (db/db) dt slot)))

(defn slot-cardinality [dt slot]
  (ffirst
    (d/q '[:find ?c :in $ ?dt ?s :where
           [?e :dt/dt      :dt/dt]
           [?e :db/ident      ?dt]
           [?i :db/ident       ?s]
           [?e :dt/slots       ?i]
           [?i :db/cardinality ?v]
           [?v :db/ident       ?c]]
      (db/db) dt slot)))

(defn slot-uniqueness [dt slot]
  (ffirst
    (d/q '[:find ?u :in $ ?dt ?s :where
           [?e :dt/dt      :dt/dt]
           [?e :db/ident      ?dt]
           [?i :db/ident       ?s]
           [?e :dt/slots       ?i]
           [?i :db/unique      ?v]
           [?v :db/ident       ?u]]
      (db/db) dt slot)))

;; TODO: change of semantics from metaclass to class?

(defn map-datatype-slots [f dt]
  (map (partial f dt) (datatype-slots dt)))

(defn slotwise [f dt]
  (let [slots (datatype-slots dt)
        vals  (map-datatype-slots f dt)]
  (zipmap slots vals)))

(defn slot-valuetypes [dt]
  (slotwise slot-valuetype dt))

(defn slot-docs [dt]
  (slotwise slot-doc dt))

(defn slot-cardinalities [dt]
  (slotwise slot-cardinality dt))

(defn slot-uniquenesses [dt]
  (slotwise slot-uniqueness dt))

(defn about [dt]
  ;; TODO: do
  )




(defn entity-datatype [e]
  (:dt/dt (entity e)))

(defn entity-slots [e]
  (datatype-slots (entity-datatype e)))


(comment

  (all-datatypes)

  ;;  =>( :fn** :any** :user** :dt/dt :fn :t :t** :user* :user :fn* :t* :any* :dt/dt** :dt/dt* :any)

  (describe :dt/dt)

  ;; {:dt/list      :dt/dt*,
  ;;  :db/valueType :db.type/ref,
  ;;  :dt/namespace "system",
  ;;  :db/cardinality :db.cardinality/one,
  ;;  :dt/parent #{:dt/dt},
  ;;  :db/doc "A reference to the data type of an entity. Entities with\n
  ;;          this attribute are known as 'typed entities'",
  ;;  :dt/slots #{:dt/list :dt/namespace :dt/parent :dt/slots :dt/name :dt/dt :dt/component},
  ;;  :db/id 72,
  ;;  :db/ident :dt/dt,
  ;;  :dt/name "Datatype",
  ;;  :dt/dt :dt/dt}

  (datatype-slots :user)

  ;; => #{:user/uuid :user/login :dt/dt :user/secret}

  (describe :user)

  {:dt/list :user*,
   :dt/namespace "model",
   :dt/parent #{:t},
   :db/doc "Superclass of all users.",
   :dt/slots #{:user/uuid :user/login :user/secret},
   :db/id 17592186045441,
   :db/ident :user,
   :dt/name "User",
   :dt/dt :dt/dt}



;; (datatype-slots :user)
;; (entity-datatype :dt/dt)
;; (datatype-slots :any)

)
