(ns sandbar.db.datatype
  "Datatype Metamodel API

  This namespace provides functions for working with the RDFS-like metamodel
  built on top of Datomic. The metamodel supports:

  - Classes (:dt/Class) with inheritance via :dt/subclass-of
  - Properties (:dt/Property) with domain/range constraints
  - Typed instances via :dt/type
  - Validation including required slots, type checking, and custom validators

  Key concepts:
  - Class: A type definition (like rdfs:Class)
  - Property: An attribute definition with domain and range (like rdf:Property)
  - Resource: The root class of all things (like rdfs:Resource)
  - Slots: Properties that belong to a class

  Main entry points:
  - make/make*: Create typed instances
  - validate/valid?: Validate entities against their class
  - class-of, slots-of, ancestors-of: Introspection
  - instance-of?, subclass-of?: Type predicates"
  (:refer-clojure :exclude [cat])
  (:require [clojure.pprint :as pp]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.rules :refer [defrule clear-rulebase! all-rules] :as rule]
            [sandbar.db.fn :refer [defdbfn dbfn clear-fnbase! all-dbfn] :as fn]
            [sandbar.db.datomic :refer [entity describe] :as db]))

(defn all-datatypes
  "Returns a sequence of all class idents in the database.
  These are entities where :dt/type is :dt/Class."
  []
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

(defn direct-instances-of
  "Returns all entities that are direct instances of class dt.
  Direct instances have :dt/type exactly equal to dt, not a subclass.
  Returns entity maps."
  [dt]
  (map (comp db/entity first)
       (d/q '[:find ?e :in $ % ?dt :where
              (direct-instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn all-instances-of
  "Returns all entities that are instances of class dt or any of its subclasses.
  Uses the instance-of Datalog rule for recursive subclass traversal.
  Returns entity maps."
  [dt]
  (map (comp db/entity first)
       (d/q '[:find ?e :in $ % ?dt :where
              (instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn named-idents-of
  "Returns the :db/ident KEYWORDS of all named entities that are
  instances of class dt or any of its subclasses.

  Return shape (idents) is explicit in the name.  When you need
  entity maps, use `named-entities-of` instead.

  Replaces the older `all-named-instances-of` (kept as deprecated alias
  for one-release migration window per
  decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md)."
  [dt]
  (map first
       (d/q '[:find ?ident :in $ % ?dt :where
              [?e :db/ident ?ident]
              (instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn named-entities-of
  "Returns entity MAPS for all named entities that are instances of
  class dt or any of its subclasses.

  Return shape (entity maps) is explicit in the name.  Use this when
  you need to read metadata off the entities (`:db/ident`,
  `:dt/native-codec`, slot values, etc.).  When you only need idents,
  use `named-idents-of` instead.

  Per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md."
  [dt]
  (map (comp db/entity first)
       (d/q '[:find ?ident :in $ % ?dt :where
              [?e :db/ident ?ident]
              (instance-of ?dt ?e)]
            (db/db) (all-rules) dt)))

(defn ^{:deprecated "0.1.0"} all-named-instances-of
  "DEPRECATED: name does not disambiguate return shape.  Use:
    - `named-idents-of`     when you want idents (current behavior)
    - `named-entities-of`   when you want entity maps

  Kept as an alias for `named-idents-of` for one-release migration window
  per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md.
  Slated for removal post-0.1.x."
  [dt]
  (named-idents-of dt))

(defn all-classes
  "Returns the :db/ident keywords of all classes in the metamodel.
  Equivalent to (named-idents-of :dt/Class)."
  []
  (named-idents-of :dt/Class))

(defn all-properties
  "Returns the :db/ident keywords of all properties in the metamodel.
  Equivalent to (named-idents-of :dt/Property)."
  []
  (named-idents-of :dt/Property))

(defn make*
  "Creates a typed instance without validation.

  Arguments:
    dt    - The class ident (keyword) for the new instance
    props - Optional map of property values

  Returns the newly created entity map.

  Example:
    (make* :User {:user/login \"dan\" :user/secret \"hash\"})

  Note: Use `make` instead for validated instance creation."
  ([dt] (make* dt {}))
  ([dt props]
   (let [row (merge props {:dt/type dt})
         result @(d/transact (db/conn) [row])
         new-entity (-> result :tempids vals first entity)]
     (log/debug :DT/MAKE {:class dt :entity-id (:db/id new-entity)})
     new-entity)))

(declare validate-data)  ;; forward declaration

(defn make
  "Creates a typed instance with pre-transaction validation.

  Arguments:
    dt    - The class ident (keyword) for the new instance
    props - Optional map of property values
    opts  - Optional options map:
            :validate? - if false, skips validation (default true)
            :format    - codec format keyword (e.g., :markdown / :json)
                         When provided with :source, parses source via
                         the codec mediator and uses the resulting
                         entity-spec as the props base; explicit `props`
                         keys override parsed slots
            :source    - raw native-representation string to parse via
                         :format codec (e.g., markdown text for :markdown)

  Returns the newly created entity map.

  Throws ex-info with {:errors [...]} if validation fails.

  Validation includes:
    - Class is not abstract
    - Required slots are present
    - Slot values match their declared range types
    - Cardinality constraints are satisfied

  Example:
    (make :User {:user/login \"dan\"})
    (make :User {:user/login \"dan\"} {:validate? false})

    ;; Parse markdown via codec.markdown; transact result
    (make :mm/Memory {} {:format :markdown
                          :source \"---\\nname: Foo\\n---\\n# Body\\n\"})"
  ([dt] (make dt {} {}))
  ([dt props] (make dt props {}))
  ([dt props {:keys [validate? format source] :or {validate? true}}]
   ;; F.1 codec arc Stage F per
   ;; plans/sandbar_codec_layer_arc_2026-05-12.md — when :format +
   ;; :source supplied, parse via the codec mediator first; explicit
   ;; props override parsed slots.
   ;;
   ;; Signal 3 (Stage G analysis) — when :source is provided WITHOUT
   ;; explicit :format, fall back to the class's :dt/native-codec
   ;; attribute (the mediator's class-default resolution semantics).
   ;; Symmetric with codec/parse's class-aware default.
   (let [resolved-format (or format
                             (when source
                               (:dt/native-codec (entity dt))))
         props (if (and resolved-format source)
                 (let [parse-fn (requiring-resolve 'sandbar.codec/parse)
                       parsed   (parse-fn source {:format resolved-format :class dt})]
                   (merge (dissoc parsed :dt/type) props))
                 props)]
     (if-not validate?
       (make* dt props)
       (if-let [errors (validate-data dt props)]
         (do
           (log/debug :DT/VALIDATION-FAILED {:class dt :errors errors})
           (throw (ex-info "Validation failed" errors)))
         (make* dt props))))))

(defn realize-with
  "General-purpose entity realization helper — given a seed entity + a
   `walk-fn`, returns a vector of entity-spec maps including the seed
   plus all transitively-reachable related entities (BFS order).

   Arguments:
     entity  - the seed entity (Datomic Entity record OR ident OR :db/id)
     walk-fn - fn entity → coll of related entities; defines the walk shape
               (e.g., for mm/Memory: (:mm.memory/first-section + walks); for
               mm/Section: (:mm.section/next-sibling + :_mm.section/parent)).
               walk-fn should return ALREADY-DEDUPLICATED related entities;
               realize-with dedupes by :db/id across the BFS visited-set.

   Returns: vector of entity-spec maps; each map is `(into {:dt/type ...}
   datomic-entity)` for the seed and each walked entity.

   Codec arc Stage F Signal 6 per
   plans/sandbar_codec_layer_arc_2026-05-12.md — addresses the friction
   that `emit-entity`'s shallow `(into {} entity)` misses lazy-loaded
   refs.  Composable with `sandbar.codec/emit` on collections + with
   `sandbar.project-graph` entity-collection paths."
  [entity walk-fn]
  (let [seed (cond
               (keyword? entity) (db/entity entity)
               (number?  entity) (db/entity entity)
               :else entity)]
    (loop [acc      []
           visited  #{}
           frontier [seed]]
      (if (empty? frontier)
        acc
        (let [next-frontier (atom [])
              new-acc (reduce
                        (fn [a e]
                          (let [eid (:db/id e)]
                            (if (or (nil? eid) (contains? visited eid))
                              a
                              (let [related (or (walk-fn e) [])
                                    e-map   (into {:dt/type (:dt/type e)} e)]
                                (doseq [r related
                                        :let [r-eid (:db/id r)]]
                                  (when (and r-eid (not (contains? visited r-eid)))
                                    (swap! next-frontier conj r)))
                                (conj a e-map)))))
                        acc
                        frontier)
              new-visited (into visited (keep :db/id frontier))]
          (recur new-acc new-visited @next-frontier))))))

(defn emit-entity
  "Emit an entity in its native representation via the codec mediator.

  Arguments:
    entity - the entity (or entity map / entity ID)
    opts   - optional codec opts:
             :format — format keyword (default: from the class's
                       :dt/native-codec attribute)
             others  — forwarded to the codec's emit method
                       (e.g., :pretty?, :include-id?)

  Returns the native-representation string (typically markdown / JSON
  / TTL depending on the resolved codec).

  Per codec arc Stage F (plans/sandbar_codec_layer_arc_2026-05-12.md):
  the inverse of `dt/make` with `:format` opt — together they form a
  full codec round-trip surface at the model layer.

  Example:
    (emit-entity my-memory)               ; uses :dt/native-codec default
    (emit-entity my-memory {:format :json})"
  ([entity] (emit-entity entity {}))
  ([entity opts]
   (let [emit-fn (requiring-resolve 'sandbar.codec/emit)
         ;; Realize Datomic entity → plain map (codecs operate on
         ;; entity-spec maps, not Entity records).
         entity-map (cond
                      (map? entity) entity
                      (number? entity) (into {} (db/entity entity))
                      :else (into {} entity))]
     (emit-fn entity-map opts))))

(defn class-ident-of
  "Returns the class IDENT (keyword) for entity e — the `:dt/type`
  value as an ident.

  Return shape (ident keyword) is explicit in the name.  When you
  need the class's full entity map (to read class-level metadata
  like `:dt/native-codec`, `:dt/slots`, `:dt/aliases`), use
  `class-entity-of` instead.

  For an instance:  returns the class the instance is in.
  For a class itself: returns the meta-class (`:dt/Class`).
  For a property: returns `:dt/Property`.

  Per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md."
  [e]
  (-> e entity :dt/type))

(defn class-entity-of
  "Returns the class ENTITY map for class-ident.

  Resolves a class-ident keyword (e.g., `:mm/Memory`) to its entity
  for reading class-level metadata: `:dt/native-codec`, `:dt/slots`,
  `:dt/aliases`, `:dt/abstract?`, `:dt/subclass-of`.

  IMPORTANT: this does NOT follow `:dt/type` — it returns the entity
  for the class itself.  If you have an instance and want its class's
  metadata, compose: `(-> instance class-ident-of class-entity-of)`.

  This explicit helper exists because the duplicate `(-> x entity
  :dt/type)`-then-read pattern was the source of the codex MUST-FIX
  #1 + ultrareview bug class at `codec.clj:116`.

  Per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md."
  [class-ident]
  (db/entity class-ident))

(defn ^{:deprecated "0.1.0"} class-of
  "DEPRECATED: name does not disambiguate return shape.  Use:
    - `class-ident-of`   when you want the class ident (current behavior)
    - `class-entity-of`  when you want the class entity (for metadata)

  Kept as an alias for `class-ident-of` for one-release migration window
  per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md.
  Slated for removal post-0.1.x."
  [e]
  (class-ident-of e))

(defn find-by-ident
  "Returns the entity map for the given `:db/ident`, or nil if no
  entity has that ident.

  Convenience helper used when callsites have an ident in hand and
  need the entity (most often: realizing idents returned by
  `named-idents-of` into entities suitable for projection).

  Per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md."
  [ident]
  (db/entity ident))

(defn native-codec-of-class
  "Returns the `:dt/native-codec` format keyword declared on the class,
  or nil if none.

  Resolves the per-class default codec for the codec mediator's
  class-default routing path (`sandbar.codec/native-codec-for-class`).
  Purpose-built helper that does NOT traverse `:dt/type` — it reads
  the codec directly off the class entity.

  Replaces the buggy `(:dt/native-codec (entity (dt/class-of class)))`
  pattern that triggered codex MUST-FIX #1 (the `class-of` call
  resolved to `:dt/Class`, and `:dt/Class` has no `:dt/native-codec`).

  Per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md."
  [class-ident]
  (:dt/native-codec (db/entity class-ident)))

(defn codec-aliases-of
  "Returns the codec-layer alias map declared on the class via the
  `:dt/codec-aliases` schema attribute, or `{}` if none.

  Schema shape: `:dt/codec-aliases` is cardinality-many; each entry
  is a `[short-key slot-ident]` keyword-pair tuple.  This function
  reconstructs the map for codec consumers.

  IMPORTANT — these are NOT `owl:sameAs`-shaped identity aliases.
  They are context-specific naming conventions for the codec layer
  ONLY: when a class is encoded via a codec, the short-key surfaces
  in the wire form as a stand-in for the canonical namespaced slot
  ident.  The slot retains its full canonical identity in the model;
  only the wire-form name is `short-key`.  Per
  interaction/check_substrate_schema_attribute_names_against_rdf_owl_semantics_2026_05_13.md
  the attribute is named `:dt/codec-aliases` (not `:dt/aliases`) to
  disambiguate from OWL identity-relation semantics + to match the
  `:dt/native-codec` sister-attribute naming pattern.

  Used by codecs (e.g., `sandbar.codec.markdown/frontmatter-key->slot`)
  for class-declared alias resolution — replaces the prior hardcoded
  `known-class-slot-aliases` map in the codec implementation, per
  interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md.

  Per decisions/sandbar_dt_star_explicit_ident_entity_helper_split_2026_05_13.md."
  [class-ident]
  (into {} (or (:dt/codec-aliases (db/entity class-ident)) [])))

(defn parents-of
  "Returns the direct parent classes of class dt.
  These are the immediate values of :dt/subclass-of."
  [dt]
  (:dt/subclass-of (entity dt)))

(defn ancestors-of
  "Returns all ancestor classes of class dt (parents, grandparents, etc.).
  Recursively traverses the :dt/subclass-of hierarchy."
  [dt]
  (let [direct-parents (parents-of dt)]
    (distinct
      (concat direct-parents
              (mapcat ancestors-of direct-parents)))))

(defn direct-subclasses-of
  "Returns the idents of classes that directly extend class dt.
  Only returns immediate children, not transitive descendants."
  [dt]
  (map first
       (d/q '[:find ?t :in $ ?dt :where
              [?e :dt/subclass-of ?dt]
              [?e :db/ident ?t]]
            (db/db) dt)))

(defrule subclass-of [?dt ?s]
  [?e  :dt/subclass-of ?dt]
  [?e  :db/ident ?s])

(defrule subclass-of [?dt ?c]
  [?e :dt/subclass-of ?dt]
  (subclass-of ?e ?s)
  [?s :db/ident ?c])

(defn subclasses-of
  "Returns idents of all transitive subclasses of class dt.
  Uses Datalog rules for recursive traversal of :dt/subclass-of."
  [dt]
  (mapv first
        (d/q '[:find ?c :in $ % ?dt :where
               [?t :db/ident  ?c]
               (subclass-of ?dt ?c)]
             (db/db) (all-rules) dt)))

(defn subclass-of?
  "Returns true if c is a subclass of dt (direct or transitive)."
  [dt c]
  (some? ((set (subclasses-of dt)) c)))

(defn instance-of?
  "Returns true if entity e is an instance of class dt.
  True when e's :dt/type is dt or a subclass of dt."
  [dt e]
  (let [t (-> e entity :dt/type)]
    (or (= dt t) (subclass-of? dt t))))

(defn abstract?
  "Returns true if class dt is marked as abstract.
  Abstract classes should not be directly instantiated."
  [dt]
  (:dt/abstract? (entity dt)))

(defrule direct-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defn direct-slots-of
  "Returns the properties directly declared on class dt.
  Does not include slots inherited from parent classes."
  [dt]
  (:dt/slots (entity dt)))

(defrule effective-slot [?dt ?s]
  [?dt :dt/slots ?i]
  [?i  :db/ident ?s])

(defrule effective-slot [?dt ?s]
  [?dt  :dt/subclass-of ?p]
  (effective-slot ?p ?s))

(defn slots-of
  "Returns all effective slots for class dt, including inherited.
  Uses Datalog rules to traverse the class hierarchy."
  [dt]
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

(defn domain-of
  "Returns the domain class of property prop.
  The domain specifies which class instances may have this property."
  [prop]
  (:dt/domain (entity prop)))

(defn range-of
  "Returns the range type of property prop.
  The range specifies the allowed type of the property's values."
  [prop]
  (:dt/range (entity prop)))

(defn properties-with-domain
  "Returns idents of all properties whose domain is dt or an ancestor of dt.
  Useful for finding all properties applicable to instances of a class."
  [dt]
  (let [dt-and-ancestors (cons dt (ancestors-of dt))]
    (mapv first
          (d/q '[:find ?p :in $ [?domains ...] :where
                 [?e :dt/domain ?d]
                 [?d :db/ident ?domains]
                 [?e :db/ident ?p]]
               (db/db) dt-and-ancestors))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- literal-type?
  "Check if dt is a Datomic literal type (db.type/*)"
  [dt]
  (and (keyword? dt)
       (= "db.type" (namespace dt))))

(defn- value-matches-range?
  "Check if a value matches the expected range type"
  [value range-type]
  (cond
    ;; No range specified - anything goes
    (nil? range-type)
    true

    ;; Literal types - check Clojure/Java type
    (literal-type? range-type)
    (case range-type
      :db.type/string  (string? value)
      :db.type/boolean (boolean? value)
      :db.type/long    (int? value)
      :db.type/keyword (keyword? value)
      :db.type/uuid    (uuid? value)
      :db.type/instant (inst? value)
      :db.type/uri     (instance? java.net.URI value)
      :db.type/double  (double? value)
      :db.type/float   (float? value)
      :db.type/bigint  (instance? java.math.BigInteger value)
      :db.type/bigdec  (decimal? value)
      :db.type/bytes   (bytes? value)
      :db.type/ref     true  ;; ref type accepts any entity
      :db.type/symbol  (symbol? value)
      :db.type/fn      (fn? value)
      :db.type/tuple   (vector? value)
      true)  ;; unknown literal type - pass

    ;; Reference to a class - check instance-of
    :else
    (instance-of? range-type value)))

(defn required? [prop]
  "Check if a property is required"
  (:dt/required? (entity prop)))

(defn cardinality-of [prop]
  "Get the cardinality of a property"
  (:db/cardinality (entity prop)))

(defn cardinality-one?
  "Returns true if property prop has cardinality one (single-valued)."
  [prop]
  (= :db.cardinality/one (cardinality-of prop)))

(defn cardinality-many?
  "Returns true if property prop has cardinality many (multi-valued)."
  [prop]
  (= :db.cardinality/many (cardinality-of prop)))

(defn required-slots-of [dt]
  "Get all required slots for a class (including inherited)"
  (filter required? (slots-of dt)))

(defn validator-of [dt]
  "Get the custom validator function for a class, if any"
  (when-let [sym (:dt/validator (entity dt))]
    (try
      (requiring-resolve sym)
      (catch Exception _ nil))))

(defn- validate-slot-type
  "Validate a single slot value against its range. Returns nil if valid, error map if invalid."
  [slot-ident slot-value]
  (let [range-type (range-of slot-ident)
        values (if (set? slot-value) slot-value #{slot-value})]
    (when-let [invalid (seq (remove #(value-matches-range? % range-type) values))]
      {:type :invalid-type
       :slot slot-ident
       :expected range-type
       :actual (mapv class invalid)
       :values (vec invalid)
       :message (str "Slot " slot-ident " expects " range-type)})))

(defn- validate-slot-cardinality
  "Validate a slot value against its cardinality. Returns nil if valid, error map if invalid."
  [slot-ident slot-value]
  (when (and (cardinality-one? slot-ident)
             (set? slot-value)
             (> (count slot-value) 1))
    {:type :cardinality-violation
     :slot slot-ident
     :expected :db.cardinality/one
     :actual (count slot-value)
     :message (str "Slot " slot-ident " has cardinality one but got " (count slot-value) " values")}))

(defn- validate-required-slots
  "Check that all required slots have values. Returns seq of error maps."
  [ent dt]
  (let [required (required-slots-of dt)]
    (keep (fn [slot]
            (when (nil? (get ent slot))
              {:type :missing-required
               :slot slot
               :message (str "Required slot " slot " is missing")}))
          required)))

(defn- run-custom-validator
  "Run the custom validator for a class if one exists. Returns seq of error maps."
  [ent dt]
  (when-let [validator-fn (validator-of dt)]
    (try
      (when-let [result (validator-fn ent)]
        (if (map? result)
          [(assoc result :type :custom-validation)]
          [{:type :custom-validation
            :message (str result)}]))
      (catch Exception e
        [{:type :validator-error
          :message (str "Validator threw exception: " (.getMessage e))}]))))

(defn validate
  "Validate an entity against its class.
   Returns nil if valid, or a map of validation errors:
   {:entity e
    :errors [{:type :no-class | :abstract-class | :missing-required |
                    :invalid-type | :cardinality-violation | :custom-validation}]}"
  [e]
  (let [ent (entity e)
        dt (class-of e)]
    (cond
      ;; No class - not a typed entity
      (nil? dt)
      {:entity e
       :errors [{:type :no-class
                 :message "Entity has no :dt/type"}]}

      ;; Abstract class
      (abstract? dt)
      {:entity e
       :errors [{:type :abstract-class
                 :class dt
                 :message (str "Cannot instantiate abstract class " dt)}]}

      ;; Run all validations
      :else
      (let [slots (slots-of dt)

            ;; Check required slots
            required-errors (validate-required-slots ent dt)

            ;; Check slot types and cardinality
            slot-errors (->> slots
                             (keep (fn [slot]
                                     (when-let [v (get ent slot)]
                                       (or (validate-slot-type slot v)
                                           (validate-slot-cardinality slot v)))))
                             (into []))

            ;; Run custom validator
            custom-errors (run-custom-validator ent dt)

            ;; Combine all errors
            all-errors (concat required-errors slot-errors custom-errors)]

        (when (seq all-errors)
          {:entity e
           :errors (vec all-errors)})))))

(defn valid?
  "Returns true if entity passes validation"
  [e]
  (nil? (validate e)))

(defn validate-all-instances
  "Validate all instances of a class (including subclass instances).
   Returns a map with validation results:
   {:class dt
    :total N
    :valid N
    :invalid N
    :errors [{:entity e :errors [...]} ...]}"
  [dt]
  (let [instances (all-instances-of dt)
        results (map (fn [inst]
                       {:entity (:db/id inst)
                        :class (class-of inst)
                        :validation (validate inst)})
                     instances)
        invalid (filter #(some? (:validation %)) results)
        valid-count (- (count results) (count invalid))]
    {:class dt
     :total (count results)
     :valid valid-count
     :invalid (count invalid)
     :errors (mapv (fn [{:keys [entity class validation]}]
                     {:entity entity
                      :class class
                      :errors (:errors validation)})
                   invalid)}))

(defn validate-data
  "Validate data map before transaction (pre-transaction validation).
   Takes a class and a props map, returns nil if valid or error map."
  [dt props]
  (cond
    (abstract? dt)
    {:errors [{:type :abstract-class
               :class dt
               :message (str "Cannot instantiate abstract class " dt)}]}

    :else
    (let [slots (slots-of dt)
          ent (assoc props :dt/type dt)

          ;; Check required slots
          required-errors (validate-required-slots ent dt)

          ;; Check slot types and cardinality
          slot-errors (->> slots
                           (keep (fn [slot]
                                   (when-let [v (get ent slot)]
                                     (or (validate-slot-type slot v)
                                         (validate-slot-cardinality slot v)))))
                           (into []))

          all-errors (concat required-errors slot-errors)]

      (when (seq all-errors)
        {:errors (vec all-errors)}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Notes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  ;; ============================================================
  ;; Validation Examples
  ;; ============================================================

  ;; Basic validation
  ;; (validate (make* :User {:user/login "dan"}))
  ;; => nil  ;; valid

  ;; Abstract class error
  ;; (validate (make* :dt/Resource {}))
  ;; => {:entity ..., :errors [{:type :abstract-class, :class :dt/Resource, ...}]}

  ;; Quick validity check
  ;; (valid? (make* :User {:user/login "dan"}))
  ;; => true

  ;; ============================================================
  ;; Pre-transaction Validation
  ;; ============================================================

  ;; Validated creation (throws on error)
  ;; (make :User {:user/login "dan"})
  ;; => entity

  ;; Skip validation
  ;; (make :User {:user/login "dan"} {:validate? false})
  ;; => entity

  ;; Pre-check data without transacting
  ;; (validate-data :User {:user/login "dan"})
  ;; => nil  ;; valid

  ;; ============================================================
  ;; Required Slots
  ;; ============================================================

  ;; Mark a property as required in schema:
  ;; {:db/ident :user/login :dt/required? true ...}

  ;; Then validation catches missing required slots:
  ;; (validate-data :User {})
  ;; => {:errors [{:type :missing-required, :slot :user/login, ...}]}

  ;; ============================================================
  ;; Cardinality Validation
  ;; ============================================================

  ;; Single-valued slot with multiple values:
  ;; (validate-slot-cardinality :user/login #{"a" "b"})
  ;; => {:type :cardinality-violation, :slot :user/login, ...}

  ;; ============================================================
  ;; Custom Validators
  ;; ============================================================

  ;; Add validator to class in schema:
  ;; {:db/ident :User :dt/validator 'myapp.validators/validate-user ...}

  ;; Validator fn signature: (fn [entity] -> nil | error-map)
  ;; (defn validate-user [ent]
  ;;   (when (< (count (:user/login ent)) 3)
  ;;     {:message "Login must be at least 3 characters"}))

  ;; ============================================================
  ;; Property Queries
  ;; ============================================================

  ;; (domain-of :user/login)
  ;; => :User

  ;; (range-of :user/login)
  ;; => :db.type/string

  ;; (required? :user/login)
  ;; => true/false

  ;; (cardinality-of :user/login)
  ;; => :db.cardinality/one

  ;; (properties-with-domain :User)
  ;; => [:user/uuid :user/login :user/secret :dt/type :db/doc ...]

  ;; (required-slots-of :User)
  ;; => (:user/login ...)

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
  ;;  :dt/context "system",
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
  ;; => #{:dt/label :dt/context :db/doc :db/ident :dt/type}

  (datatype-slots :dt/Class)

  ;; => #{:dt/list :dt/label :dt/context :dt/abstract? :db/doc :dt/slots :db/ident
  ;;      :dt/subclass-of :dt/type :dt/component}

  (datatype-slots :dt/Property)

  ;; => #{:db/unique :dt/label :dt/domain :dt/context :dt/range :db/fulltext :db/cardinality
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
