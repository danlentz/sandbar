(ns sandbar.api.store
  "REST API for querying the datatype metamodel and database entities.

  URL Schema - Namespaced keywords map to URL paths:
    :dt/Resource    -> /dt/Resource
    :example/User   -> /example/User
    :db.type/string -> /db.type/string

  ## Schema Overview
    GET /api/store/schema                         - Schema overview

  ## Classes
    GET /api/store/classes                        - List all classes
    GET /api/store/classes/:ns/:name              - Class (e.g., dt/Resource, example/User)
    GET /api/store/classes/:ns/:name/instances    - All instances
    GET /api/store/classes/:ns/:name/slots        - All effective slots
    GET /api/store/classes/:ns/:name/hierarchy    - Full hierarchy
    GET /api/store/classes/:ns/:name/subclasses   - Subclasses
    GET /api/store/classes/:ns/:name/ancestors    - Ancestors
    GET /api/store/classes/:ns/:name/parents      - Direct parents

  ## Properties
    GET /api/store/properties                     - List all properties
    GET /api/store/properties/:ns/:name           - Property details
    GET /api/store/properties/:ns/:name/domain    - Property domain
    GET /api/store/properties/:ns/:name/range     - Property range

  ## Entities
    GET /api/store/entities/:ns/:name             - Get by namespaced ident

  ## Type Predicates
    GET /api/store/types/instance-of/:ns/:name/:entity-ns/:entity-name
    GET /api/store/types/subclass-of/:parent-ns/:parent-name/:child-ns/:child-name"
  (:require [datomic.api                 :as d]
            [sandbar.db.datomic          :as db]
            [sandbar.db.datatype         :as dt]
            [sandbar.service.endpoint    :as endpoint :refer [defhandler]]
            [sandbar.service.params      :as params :refer [defvalidator]]
            [sandbar.util.http-status    :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity->map
  "Convert a Datomic entity to a plain map for JSON serialization."
  [e]
  (when e
    (into {} (d/touch e))))

(defn- parse-entity-ref
  "Parse an entity reference from path parameters.
  All entities must have namespaced keywords: /ns/name -> :ns/name"
  [ns name]
  (when (and ns name)
    (keyword ns name)))

(defn- resolve-entity
  "Resolve an entity reference to an entity map.
  Returns nil if not found."
  [ref]
  (try
    (when-let [e (db/entity ref)]
      (when (:db/id e) e))
    (catch Exception _ nil)))

(defn- describe-entity
  "Get a serializable description of an entity."
  [ref]
  (when-let [e (resolve-entity ref)]
    (entity->map e)))

(defn- class-exists? [class-kw]
  (some? (resolve-entity class-kw)))

(defn- property-exists? [prop-kw]
  (some? (resolve-entity prop-kw)))

(defn- entity-ident
  "Get the :db/ident of an entity, handling both entity maps and refs"
  [e]
  (when e
    (if (keyword? e)
      e
      (or (:db/ident e)
          (:db/ident (db/entity e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validators
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Schema
(defvalidator ::schema-overview [_] identity)

;; Classes
(defvalidator ::list-classes [_] identity)
(defvalidator ::get-class [_] identity)
(defvalidator ::list-instances [_] identity)
(defvalidator ::list-direct-instances [_] identity)
(defvalidator ::list-slots [_] identity)
(defvalidator ::list-direct-slots [_] identity)
(defvalidator ::list-required-slots [_] identity)
(defvalidator ::class-hierarchy [_] identity)
(defvalidator ::list-subclasses [_] identity)
(defvalidator ::list-direct-subclasses [_] identity)
(defvalidator ::list-ancestors [_] identity)
(defvalidator ::list-parents [_] identity)

;; Properties
(defvalidator ::list-properties [_] identity)
(defvalidator ::get-property [_] identity)
(defvalidator ::property-domain [_] identity)
(defvalidator ::property-range [_] identity)

;; Entities
(defvalidator ::get-entity [_] identity)
(defvalidator ::validate-entity [_] identity)
(defvalidator ::entity-class [_] identity)

;; Type predicates
(defvalidator ::instance-of [_] identity)
(defvalidator ::subclass-of [_] identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema Overview
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler schema-overview
  "GET /api/store/schema - Schema overview with counts"
  [_ _ _]
  (let [classes (dt/all-classes)
        properties (dt/all-properties)]
    {:schema
     {:class-count (count classes)
      :property-count (count properties)
      :classes (sort classes)
      :properties (sort properties)}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class Endpoints
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-classes
  "GET /api/store/classes - List all class idents with summary info"
  [_ _ _]
  (let [classes (dt/all-classes)]
    {:count (count classes)
     :classes (sort classes)}))

(defhandler get-class
  "GET /api/store/classes/:ns/:name - Full class description"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if-let [desc (describe-entity class-kw)]
      (let [slots (dt/slots-of class-kw)
            direct-slots (dt/direct-slots-of class-kw)
            direct-slot-idents (set (keep entity-ident direct-slots))
            parents (dt/parents-of class-kw)
            subclasses (dt/subclasses-of class-kw)]
        {:class class-kw
         :description desc
         :abstract? (boolean (dt/abstract? class-kw))
         :slots (sort slots)
         :direct-slots (sort direct-slot-idents)
         :inherited-slots (sort (clojure.set/difference slots direct-slot-idents))
         :parents (if (set? parents) (sort (keep entity-ident parents)) [])
         :subclasses (sort subclasses)
         :instance-count (count (dt/all-instances-of class-kw))})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-instances
  "GET /api/store/classes/:ns/:name/instances - All instances including subclass instances"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [instances (dt/all-instances-of class-kw)]
        {:class class-kw
         :count (count instances)
         :instances (mapv entity->map instances)})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-direct-instances
  "GET /api/store/classes/:ns/:name/instances/direct - Direct instances only"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [instances (dt/direct-instances-of class-kw)]
        {:class class-kw
         :count (count instances)
         :instances (mapv entity->map instances)})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-slots
  "GET /api/store/classes/:ns/:name/slots - All effective slots (inherited + direct)"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [slots (dt/slots-of class-kw)
            slot-details (mapv (fn [s]
                                 {:ident s
                                  :domain (dt/domain-of s)
                                  :range (dt/range-of s)
                                  :cardinality (dt/cardinality-of s)
                                  :required? (boolean (dt/required? s))})
                               (sort slots))]
        {:class class-kw
         :count (count slots)
         :slots slot-details})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-direct-slots
  "GET /api/store/classes/:ns/:name/slots/direct - Direct slots only (not inherited)"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [slots (dt/direct-slots-of class-kw)
            slot-idents (sort (keep entity-ident slots))]
        {:class class-kw
         :count (count slot-idents)
         :slots slot-idents})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-required-slots
  "GET /api/store/classes/:ns/:name/slots/required - Required slots only"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [required (dt/required-slots-of class-kw)]
        {:class class-kw
         :count (count required)
         :slots (sort required)})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler class-hierarchy
  "GET /api/store/classes/:ns/:name/hierarchy - Full class hierarchy"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [parents (dt/parents-of class-kw)
            ancestors (dt/ancestors-of class-kw)
            direct-subclasses (dt/direct-subclasses-of class-kw)
            all-subclasses (dt/subclasses-of class-kw)]
        {:class class-kw
         :parents (if (set? parents) (sort (keep entity-ident parents)) [])
         :ancestors (sort (keep entity-ident ancestors))
         :direct-subclasses (sort direct-subclasses)
         :all-subclasses (sort all-subclasses)})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-subclasses
  "GET /api/store/classes/:ns/:name/subclasses - All transitive subclasses"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [subclasses (dt/subclasses-of class-kw)]
        {:class class-kw
         :count (count subclasses)
         :subclasses (sort subclasses)})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-direct-subclasses
  "GET /api/store/classes/:ns/:name/subclasses/direct - Direct subclasses only"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [subclasses (dt/direct-subclasses-of class-kw)]
        {:class class-kw
         :count (count subclasses)
         :subclasses (sort subclasses)})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-ancestors
  "GET /api/store/classes/:ns/:name/ancestors - All ancestor classes"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [ancestors (dt/ancestors-of class-kw)
            ancestor-idents (sort (keep entity-ident ancestors))]
        {:class class-kw
         :count (count ancestor-idents)
         :ancestors ancestor-idents})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

(defhandler list-parents
  "GET /api/store/classes/:ns/:name/parents - Direct parent classes"
  [_ _ {:keys [ns name]}]
  (let [class-kw (parse-entity-ref ns name)]
    (if (class-exists? class-kw)
      (let [parents (dt/parents-of class-kw)
            parent-idents (if (set? parents) (sort (keep entity-ident parents)) [])]
        {:class class-kw
         :count (count parent-idents)
         :parents parent-idents})
      (endpoint/not-found {:error "Class not found" :class class-kw}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Property Endpoints
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler list-properties
  "GET /api/store/properties - List all properties with summary"
  [_ _ _]
  (let [properties (dt/all-properties)]
    {:count (count properties)
     :properties (sort properties)}))

(defhandler get-property
  "GET /api/store/properties/:ns/:name - Full property description"
  [_ _ {:keys [ns name]}]
  (let [prop-kw (parse-entity-ref ns name)]
    (if-let [desc (describe-entity prop-kw)]
      {:property prop-kw
       :description desc
       :domain (dt/domain-of prop-kw)
       :range (dt/range-of prop-kw)
       :cardinality (dt/cardinality-of prop-kw)
       :required? (boolean (dt/required? prop-kw))
       :cardinality-one? (dt/cardinality-one? prop-kw)
       :cardinality-many? (dt/cardinality-many? prop-kw)}
      (endpoint/not-found {:error "Property not found" :prop prop-kw}))))

(defhandler property-domain
  "GET /api/store/properties/:ns/:name/domain - Get property's domain class"
  [_ _ {:keys [ns name]}]
  (let [prop-kw (parse-entity-ref ns name)]
    (if (property-exists? prop-kw)
      (let [domain (dt/domain-of prop-kw)]
        {:property prop-kw
         :domain domain
         :domain-description (when domain (describe-entity domain))})
      (endpoint/not-found {:error "Property not found" :prop prop-kw}))))

(defhandler property-range
  "GET /api/store/properties/:ns/:name/range - Get property's range type"
  [_ _ {:keys [ns name]}]
  (let [prop-kw (parse-entity-ref ns name)]
    (if (property-exists? prop-kw)
      (let [range-type (dt/range-of prop-kw)]
        {:property prop-kw
         :range range-type
         :range-description (when range-type (describe-entity range-type))})
      (endpoint/not-found {:error "Property not found" :prop prop-kw}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity Endpoints
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler get-entity
  "GET /api/store/entities/:ns/:name - Get entity by db/id or db/ident"
  [_ _ {:keys [ns name]}]
  (let [ref (parse-entity-ref ns name)]
    (if-let [desc (describe-entity ref)]
      (let [class-type (dt/class-of ref)]
        {:id ref
         :class class-type
         :entity desc})
      (endpoint/not-found {:error "Entity not found" :id ref}))))

(defhandler validate-entity
  "GET /api/store/entities/:ns/:name/validate - Validate entity against its class"
  [_ _ {:keys [ns name]}]
  (let [ref (parse-entity-ref ns name)]
    (if (resolve-entity ref)
      (let [validation (dt/validate ref)]
        {:id ref
         :valid? (nil? validation)
         :validation (or validation {:status "valid"})})
      (endpoint/not-found {:error "Entity not found" :id ref}))))

(defhandler entity-class
  "GET /api/store/entities/:ns/:name/class - Get entity's class"
  [_ _ {:keys [ns name]}]
  (let [ref (parse-entity-ref ns name)]
    (if (resolve-entity ref)
      (let [class-type (dt/class-of ref)]
        {:id ref
         :class class-type
         :class-description (when class-type (describe-entity class-type))})
      (endpoint/not-found {:error "Entity not found" :id ref}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type Predicate Endpoints
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler check-instance-of
  "GET /api/store/types/instance-of/:class-ns/:class-name/:entity-ns/:entity-name"
  [_ _ {:keys [class-ns class-name entity-ns entity-name]}]
  (let [class-kw (parse-entity-ref class-ns class-name)
        entity-ref (parse-entity-ref entity-ns entity-name)]
    (cond
      (not (class-exists? class-kw))
      (endpoint/not-found {:error "Class not found" :class class-kw})

      (not (resolve-entity entity-ref))
      (endpoint/not-found {:error "Entity not found" :id entity-ref})

      :else
      {:class class-kw
       :entity entity-ref
       :instance-of? (dt/instance-of? class-kw entity-ref)})))

(defhandler check-subclass-of
  "GET /api/store/types/subclass-of/:parent-ns/:parent-name/:child-ns/:child-name"
  [_ _ {:keys [parent-ns parent-name child-ns child-name]}]
  (let [parent-kw (parse-entity-ref parent-ns parent-name)
        child-kw (parse-entity-ref child-ns child-name)]
    (cond
      (not (class-exists? parent-kw))
      (endpoint/not-found {:error "Parent class not found" :class parent-kw})

      (not (class-exists? child-kw))
      (endpoint/not-found {:error "Child class not found" :class child-kw})

      :else
      {:parent parent-kw
       :child child-kw
       :subclass-of? (dt/subclass-of? parent-kw child-kw)})))
