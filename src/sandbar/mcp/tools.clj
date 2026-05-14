(ns sandbar.mcp.tools
  "MCP `tools/list` + `tools/call` handlers — operational verb catalog.

   Per decisions/sandbar_mcp_tool_surface_resolution_operational_verb_catalog_per_adr_b13_2026_05_12.md
   (F-B-001 resolution): the tool surface is a STABLE OPERATIONAL VERB
   CATALOG, NOT per-class constructors.  ~33 verbs grouped into:

   - schema introspection   — sandbar.schema.{classes,properties,datatypes}
   - class introspection    — sandbar.class.{describe,slots,direct-slots,
                                              required-slots,instances,
                                              subclasses,parents,
                                              validate-all-instances}
   - type predicates        — sandbar.types.{instance-of,subclass-of}
   - property introspection — sandbar.property.{domain,range,cardinality}
   - entity operations      — sandbar.entity.{create,find,update,validate}
   - workflow operations    — sandbar.workflow.{define,find,start-process,
                                                 transition,process-state,
                                                 process-history,
                                                 active-processes}
   - validation service     — sandbar.validation.{start,run,cancel,retry,
                                                   results,history}

   Discipline per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
   every handler routes through `dt/*` / `sandbar.util.*` /
   `sandbar.service.*` higher-layer APIs — NEVER raw `datomic.api`.

   Per codex F-M-003 resolution: handlers respect each `dt/*` function's
   actual return-shape contract.  `dt/all-classes` / `dt/all-properties`
   / `dt/all-named-instances-of` / `dt/class-of` return IDENTS
   (keywords).  `dt/all-instances-of` / `dt/direct-instances-of` return
   ENTITY MAPS.  `dt/direct-slots-of` returns ENTITY MAPS (Datomic
   ref-traversal); `dt/slots-of` returns IDENTS (rule-based).  The
   `->ident-str` helper coerces either shape to a string at the
   projection boundary."
  (:require [cheshire.core              :as json]
            [clojure.edn                :as edn]
            [clojure.string             :as str]
            [clojure.tools.logging      :as log]
            [sandbar.aggregate          :as aggregate]
            [sandbar.codec              :as codec]
            [sandbar.navigate.path      :as nav-path]
            [sandbar.navigate.siblings  :as nav-siblings]
            [sandbar.projection      :as pg]
            [sandbar.db.datatype        :as dt]
            [sandbar.db.datomic         :as db]
            [sandbar.mcp.envelope       :as envelope]
            [sandbar.mcp.notifications  :as notifications]
            [sandbar.mcp.resources      :as resources]
            [sandbar.service.validation :as validation]
            [sandbar.util.workflow      :as workflow]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projection helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ->ident
  "Coerce an ident-string or keyword to a Clojure keyword.
   `\"foo/bar\"`, `\":foo/bar\"`, `:foo/bar` all → `:foo/bar`."
  [x]
  (cond
    (keyword? x) x
    (and (string? x) (str/starts-with? x ":")) (keyword (subs x 1))
    (string? x) (keyword x)
    :else nil))

(defn- ->ident-str
  "Project an ident (keyword) or entity-map to a string form.
   Handles dt/* contract variance — some functions return idents,
   others return entity-maps (Datomic ref-traversal)."
  [x]
  (cond
    (keyword? x) (str x)
    (map? x)     (str (:db/ident x))
    :else        (str x)))

(defn- entity-projection
  "Project an entity-map to a JSON-friendly map.
   Keeps `:db/id`, `:db/ident`, and namespaced-keyword slots.

   Note: Datomic entity-iteration does NOT include `:db/id` in the
   key-seq (it's accessed via a special method).  We explicitly add
   `:db/id` to the projection so callers can rely on it being present
   in serialized JSON / EDN output."
  [entity]
  (when entity
    (let [base (into {}
                     (filter (fn [[k _v]]
                               (or (= :db/ident k)
                                   (and (keyword? k) (some? (namespace k))))))
                     entity)]
      (cond-> base
        (:db/id entity) (assoc :db/id (:db/id entity))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Schema type mapping (carried over from per-class implementation
;; because the property.range / class.slots / entity.create verbs still
;; project Datomic types to JSON Schema for parameter description)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn datomic-type->json-schema
  "Map a Datomic value type keyword to a JSON Schema type stub."
  [t]
  (case t
    :db.type/string  {:type "string"}
    :db.type/long    {:type "integer"}
    :db.type/double  {:type "number"}
    :db.type/boolean {:type "boolean"}
    :db.type/instant {:type "string" :format "date-time"}
    :db.type/keyword {:type "string" :description "Clojure keyword string"}
    :db.type/uuid    {:type "string" :format "uuid"}
    :db.type/uri     {:type "string" :format "uri"}
    :db.type/ref     {:type "string" :description "Reference to another entity (ident or eid)"}
    {:type "string" :description (str "Datomic type " t)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Argument coercion for entity.create / entity.update — JSON values
;; arrive as strings/numbers/bools per JSON Schema; coerce per the
;; target slot's :db.type/* using dt/range-of + dt/cardinality-many?.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- coerce-value
  "Coerce one argument value to its target Datomic type."
  [value target-type many?]
  (let [coerce-one (fn [v]
                     (case target-type
                       :db.type/keyword (->ident v)
                       :db.type/instant (if (string? v)
                                          (java.util.Date/from
                                            (java.time.Instant/parse v))
                                          v)
                       :db.type/uuid    (if (string? v)
                                          (java.util.UUID/fromString v)
                                          v)
                       :db.type/ref     (->ident v)
                       v))]
    (cond
      (and many? (sequential? value))
      (mapv coerce-one value)

      many?
      [(coerce-one value)]

      :else
      (coerce-one value))))

(defn- coerce-slot-map
  "Coerce a JSON-shaped slot map (string keys → arbitrary values) to a
   Datomic-shaped props map (keyword keys → coerced values).  Uses
   `dt/range-of` + `dt/cardinality-many?` for each declared slot."
  [class-ident slot-map]
  (let [slots (dt/slots-of class-ident)]
    (reduce
      (fn [acc slot-ident]
        (let [k        slot-ident
              key-name (name slot-ident)
              v        (or (get slot-map key-name)
                           (get slot-map (str k))
                           (get slot-map (subs (str k) 1)))]
          (if (some? v)
            (assoc acc k (coerce-value v
                                       (dt/range-of slot-ident)
                                       (dt/cardinality-many? slot-ident)))
            acc)))
      {}
      slots)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Datalog :where coercion (used by aggregate verbs; will extend to
;; search verbs in Stage 27).  At the MCP boundary, :where typically
;; arrives as an EDN string because JSON has no native representation
;; for Datalog symbols (`?e`, `?v`).  In-process callers may pass an
;; already-parsed vector directly.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ->where-clauses
  "Coerce a `:where` argument to a vector of Datalog clauses.

   Accepts:
     nil          → nil
     vector/seq   → returned as a vector (in-process callers)
     EDN string   → read via clojure.edn/read-string (MCP boundary)

   Throws ex-info on any other shape."
  [where]
  (cond
    (nil? where)        nil
    (sequential? where) (vec where)
    (string? where)     (try
                          (edn/read-string where)
                          (catch Exception e
                            (throw (ex-info (str "Invalid :where EDN: "
                                                 (.getMessage e))
                                            {:received where}))))
    :else
    (throw (ex-info "Invalid :where shape — must be EDN string or sequential"
                    {:received where :type (type where)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-verb handlers
;;
;; Each handler takes an argument map (already JSON-decoded; string keys
;; or keyword keys depending on the parser) and returns a JSON-friendly
;; data shape.  Wrapping in MCP `content` arrays + envelope happens in
;; `handle-call`.
;;
;; Handlers raise `ex-info` on user-input errors; `handle-call` catches
;; and projects to `isError: true` MCP responses.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; ---------- Schema introspection ----------

(defn- schema-classes-handler [_args]
  {:classes (->> (dt/all-classes) (map ->ident-str) sort vec)})

(defn- schema-properties-handler [_args]
  {:properties (->> (dt/all-properties) (map ->ident-str) sort vec)})

(defn- schema-datatypes-handler [_args]
  {:datatypes (->> (dt/all-datatypes) (map ->ident-str) sort vec)})

;; ---------- Class introspection ----------

(defn- class-arg [args]
  (or (->ident (get args "class"))
      (->ident (get args :class))
      (throw (ex-info "Missing required argument: class" {:args args}))))

(defn- class-describe-handler [args]
  (let [c (class-arg args)]
    {:class      (str c)
     :abstract?  (boolean (dt/abstract? c))
     :parents    (mapv ->ident-str (dt/parents-of c))
     :ancestors  (mapv ->ident-str (dt/ancestors-of c))
     :subclasses (mapv ->ident-str (dt/subclasses-of c))
     :slots      (->> (dt/slots-of c) (map ->ident-str) sort vec)}))

(defn- class-slots-handler [args]
  {:class (str (class-arg args))
   :slots (->> (dt/slots-of (class-arg args)) (map ->ident-str) sort vec)})

(defn- class-direct-slots-handler [args]
  {:class (str (class-arg args))
   :slots (->> (dt/direct-slots-of (class-arg args)) (map ->ident-str) sort vec)})

(defn- class-required-slots-handler [args]
  {:class (str (class-arg args))
   :slots (->> (dt/required-slots-of (class-arg args)) (map ->ident-str) sort vec)})

(defn- class-instances-handler [args]
  (let [c (class-arg args)
        instances (dt/all-instances-of c)]
    {:class (str c)
     :instances (mapv entity-projection instances)}))

(defn- schema-entities-handler
  "Batch fetch — return entity-spec maps for all non-abstract classes
   (or a filtered subset via :classes).  Single round-trip alternative
   to N+1 sandbar.class.instances calls.  Per Stage G Signal 8
   (corpus-side friction-discovery 2026-05-13)."
  [args]
  (let [classes-arg (or (get args "classes") (get args :classes))
        target-classes (cond
                         (sequential? classes-arg)
                         (mapv ->ident classes-arg)

                         (some? classes-arg)
                         [(->ident classes-arg)]

                         :else
                         (->> (dt/all-classes)
                              (remove dt/abstract?)))
        by-class (into {}
                       (for [cls target-classes]
                         [(->ident-str cls)
                          (mapv entity-projection (dt/all-instances-of cls))]))
        total    (reduce + (map count (vals by-class)))]
    {:by-class       by-class
     :total-classes  (count target-classes)
     :total-entities total}))

(defn- class-subclasses-handler [args]
  {:class (str (class-arg args))
   :subclasses (->> (dt/subclasses-of (class-arg args)) (map ->ident-str) sort vec)})

(defn- class-parents-handler [args]
  (let [c (class-arg args)]
    {:class (str c)
     :parents (mapv ->ident-str (dt/parents-of c))
     :ancestors (mapv ->ident-str (dt/ancestors-of c))}))

(defn- class-validate-all-instances-handler [args]
  (let [c (class-arg args)
        report (dt/validate-all-instances c)]
    {:class (str c)
     :report report}))

;; ---------- Type predicates ----------

(defn- types-instance-of-handler [args]
  (let [c (->ident (or (get args "class") (get args :class)))
        e (or (get args "entity") (get args :entity))]
    (when (nil? c) (throw (ex-info "Missing required argument: class" {:args args})))
    (when (nil? e) (throw (ex-info "Missing required argument: entity" {:args args})))
    {:class (str c) :entity (str e) :instance-of? (boolean (dt/instance-of? c (->ident e)))}))

(defn- types-subclass-of-handler [args]
  (let [parent (->ident (or (get args "parent") (get args :parent)))
        child  (->ident (or (get args "child") (get args :child)))]
    (when (nil? parent) (throw (ex-info "Missing required argument: parent" {:args args})))
    (when (nil? child) (throw (ex-info "Missing required argument: child" {:args args})))
    {:parent (str parent) :child (str child)
     :subclass-of? (boolean (dt/subclass-of? parent child))}))

;; ---------- Property introspection ----------

(defn- property-arg [args]
  (or (->ident (get args "property"))
      (->ident (get args :property))
      (throw (ex-info "Missing required argument: property" {:args args}))))

(defn- property-domain-handler [args]
  (let [p (property-arg args)
        d (dt/domain-of p)]
    {:property (str p) :domain (some-> d ->ident-str)}))

(defn- property-range-handler [args]
  (let [p (property-arg args)
        r (dt/range-of p)]
    {:property (str p) :range (some-> r ->ident-str)}))

(defn- property-cardinality-handler [args]
  (let [p (property-arg args)
        c (dt/cardinality-of p)]
    {:property (str p) :cardinality (some-> c ->ident-str)}))

;; ---------- Entity operations ----------

(defn- entity-create-handler [args]
  (let [class-ident (->ident (or (get args "class") (get args :class)))
        slots       (or (get args "slots") (get args :slots) {})
        ;; Codec arc Stage F.3a per
        ;; plans/sandbar_codec_layer_arc_2026-05-12.md — optional
        ;; :format + :source opts for codec-driven entity construction.
        format-arg  (or (get args "format") (get args :format))
        source-arg  (or (get args "source") (get args :source))]
    (when (nil? class-ident)
      (throw (ex-info "Missing required argument: class" {:args args})))
    (let [cls (db/entity class-ident)]
      (when (nil? cls)
        (throw (ex-info (str "Class not found: " class-ident) {:class class-ident})))
      (when (dt/abstract? class-ident)
        (throw (ex-info (str "Cannot instantiate abstract class: " class-ident)
                        {:class class-ident :reason :abstract})))
      (let [props        (coerce-slot-map class-ident slots)
            ;; When format + source provided, dt/make's :format opt
            ;; parses via codec mediator; explicit slots override.
            make-opts    (cond-> {}
                           (and format-arg source-arg)
                           (assoc :format (keyword format-arg)
                                  :source source-arg))
            new-entity   (dt/make class-ident props make-opts)]
        (log/info :MCP/entity-create
                  {:class  class-ident
                   :entity-id (:db/id new-entity)
                   :format (when format-arg (keyword format-arg))})
        ;; Per ADR B.1.4: a new dt/Class or dt/Property changes the
        ;; schema surface (visible to schema.* + class.* verbs).
        ;; tools/list itself doesn't change (verb catalog is stable),
        ;; so we DON'T fire tools/list_changed; instead, fire
        ;; resources/list_changed because the new class/property
        ;; becomes a resource.
        (when (#{:dt/Class :dt/Property} class-ident)
          (notifications/resources-list-changed!))
        ;; Per F-S-001 resolution: every new entity is potentially a
        ;; resource subscribers care about — fire resources/updated for
        ;; the entity's URI.  resources/entity-updated! is a no-op when
        ;; no subscriptions exist; per-URI routing handles fan-out.
        (try
          (resources/entity-updated! new-entity)
          (catch Exception e
            (log/warn e :MCP/entity-create-notify-failed
                      {:class class-ident :entity-id (:db/id new-entity)})))
        {:entity (entity-projection new-entity)}))))

(defn- entity-find-handler [args]
  (let [ident-or-id (or (get args "ident") (get args :ident)
                        (get args "id")    (get args :id))]
    (when (nil? ident-or-id)
      (throw (ex-info "Missing required argument: ident (or id)" {:args args})))
    (let [lookup (if (number? ident-or-id) ident-or-id (->ident ident-or-id))
          e      (db/entity lookup)]
      (if (some? e)
        {:entity (entity-projection e)}
        {:entity nil :missing? true :lookup (str ident-or-id)}))))

;; ---------- Codec + project-graph operations (Stage F.3b) ----------

(defn- codec-list-handler [_args]
  {:codecs (codec/list-codecs)})

(defn- ->filter-spec
  "Coerce JSON-shaped filter arg to a Clojure filter spec for
   sandbar.projection/entity-passes-filter?.  Accepts string keys
   (from JSON) or keyword keys.  String class-idents are coerced to
   keywords via ->ident; `:classes` value may be array or single ident."
  [filter-arg]
  (when (and filter-arg (map? filter-arg))
    (let [g (fn [k] (or (get filter-arg (name k)) (get filter-arg k)))]
      (cond-> {}
        (g :class)
        (assoc :class (->ident (g :class)))

        (g :classes)
        (assoc :classes (set (mapv ->ident
                                   (let [c (g :classes)]
                                     (if (sequential? c) c [c])))))

        (g :tree-filter)
        (assoc :tree-filter (g :tree-filter))))))

(defn- project-export-handler [args]
  (let [to     (or (get args "to") (get args :to))
        filter-spec (->filter-spec (or (get args "filter") (get args :filter)))]
    (when-not to
      (throw (ex-info "project.export requires :to (output directory path)"
                      {:args args})))
    ;; Stage G Signal 2 — filter opt enables hybrid-backend
    ;; experimentation per
    ;; ideas/sandbar_project_export_filtering_for_hybrid_backend_experimentation_2026_05_13.md
    ;;
    ;; Codex MUST-FIX #4 resolution — realize the section tree from DB
    ;; for each mm/Memory before passing to project-graph.  The prior
    ;; shape fetched only mm/Memory entities (no sections), so
    ;; project-graph's emit-document path silently dropped sections from
    ;; persisted memory state.  `pg/mm-walker` (lifted to project-graph
    ;; in Stage E) walks :mm.memory/first-section + sibling/parent
    ;; chains.
    (let [memories     (dt/all-instances-of :mm/Memory)
          entity-maps  (vec
                         (mapcat (fn [memory]
                                   (let [realized (dt/realize-with memory pg/mm-walker)]
                                     ;; realize-with returns entity-spec maps with
                                     ;; :dt/type populated; ensure mm/Memory entries
                                     ;; carry it explicitly for downstream filter logic
                                     (map #(if (= :mm/Memory (:dt/type %))
                                             (into {:dt/type :mm/Memory} %)
                                             %)
                                          realized)))
                                 memories))
          result       (pg/project-graph entity-maps
                                         (cond-> {:to to}
                                           filter-spec (assoc :filter filter-spec)))]
      {:to       to
       :filter   filter-spec
       :exported (count result)
       :files    (mapv :rel-path result)})))

(defn- project-import-handler [args]
  (let [from        (or (get args "from") (get args :from))
        filter-spec (->filter-spec (or (get args "filter") (get args :filter)))]
    (when-not from
      (throw (ex-info "project.import requires :from (input directory path)"
                      {:args args})))
    (let [entities (pg/ingest-graph from (cond-> {}
                                            filter-spec (assoc :filter filter-spec)))]
      {:from     from
       :filter   filter-spec
       :imported (count entities)
       :entities (mapv (fn [e]
                         {:dt/type (:dt/type e)
                          :ident   (:db/ident e)})
                       entities)})))

;; ---------- Aggregation operations (Stage 14 — fulltext arc Phase G) ----------
;;
;; Per fulltext arc Stage 14 of
;; plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.
;; MCP boundary wrappers around sandbar.aggregate's three public verbs:
;; count-by / group-by / rank-by.  Substrate-quality discipline preserved:
;; handlers route through the consumer-facing aggregate namespace, never
;; raw datatype primitives.

(defn- aggregate-count-handler [args]
  (let [class-ident (class-arg args)
        where       (->where-clauses
                      (or (get args "where") (get args :where)))]
    (aggregate/count-by {:class class-ident :where where})))

(defn- aggregate-group-by-handler [args]
  (let [class-ident  (class-arg args)
        group-by-arg (->ident (or (get args "group-by") (get args :group-by)))
        where        (->where-clauses
                       (or (get args "where") (get args :where)))]
    (when (nil? group-by-arg)
      (throw (ex-info "Missing required argument: group-by" {:args args})))
    (aggregate/group-by {:class class-ident :group-by group-by-arg :where where})))

(defn- aggregate-rank-by-handler [args]
  (let [class-ident   (class-arg args)
        rank-by-arg   (->ident (or (get args "rank-by") (get args :rank-by)))
        limit-arg     (or (get args "limit") (get args :limit))
        temporal-slot (->ident
                        (or (get args "temporal-slot") (get args :temporal-slot)))]
    (when (nil? rank-by-arg)
      (throw (ex-info "Missing required argument: rank-by" {:args args})))
    (let [opts (cond-> {:class class-ident :rank-by rank-by-arg}
                 (some? limit-arg)     (assoc :limit limit-arg)
                 (some? temporal-slot) (assoc :temporal-slot temporal-slot))]
      (aggregate/rank-by opts))))

;; ---------- Navigation operations (Stage P-6 — fulltext arc Phase N) ----------
;;
;; Per fulltext arc Stage P-6 of
;; plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.
;; MCP boundary wrapper around sandbar.navigate.path/path-via — the
;; path-grammar walker primitive.

(defn- navigate-siblings-of-handler [args]
  (let [entity-arg    (or (get args "entity") (get args :entity))
        path-slot-arg (or (get args "path-slot") (get args :path-slot))
        limit         (or (get args "limit") (get args :limit))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (when (nil? path-slot-arg)
      (throw (ex-info "Missing required argument: path-slot" {:args args})))
    (let [entity-ident (->ident entity-arg)
          path-slot    (->ident path-slot-arg)
          opts (cond-> {:entity entity-ident :path-slot path-slot}
                 (some? limit) (assoc :limit limit))]
      (nav-siblings/siblings-of opts))))

(defn- navigate-path-via-handler [args]
  (let [from-arg (or (get args "from") (get args :from))
        via-arg  (or (get args "via")  (get args :via))
        limit    (or (get args "limit") (get args :limit))
        include  (or (get args "include") (get args :include))]
    (when (nil? from-arg)
      (throw (ex-info "Missing required argument: from" {:args args})))
    (when (nil? via-arg)
      (throw (ex-info "Missing required argument: via" {:args args})))
    (let [from-ident (->ident from-arg)
          include-set (when (sequential? include)
                        (set (map keyword include)))
          opts (cond-> {:from from-ident :via via-arg}
                 (some? limit)   (assoc :limit limit)
                 include-set     (assoc :include include-set))]
      (nav-path/path-via opts))))

(defn- entity-update-handler [args]
  ;; Stage I of plans/sandbar_codex_review_remediation_arc_2026_05_13.md
  ;; landed dt/update-entity!; this verb now wires through.  Per codex
  ;; SHOULD-FIX #5 (sandbar.entity.update advertised but unimplemented).
  (let [entity-arg (or (get args "entity") (get args :entity))
        slot-arg   (or (get args "slots")  (get args :slots))
        entity-ref (->ident entity-arg)]
    (when (nil? entity-ref)
      (throw (ex-info "Missing required argument: entity (ident or eid)" {:args args})))
    (when (or (nil? slot-arg) (not (map? slot-arg)))
      (throw (ex-info "Missing or non-map argument: slots (must be {:slot-ident value ...} map)"
                      {:args args})))
    ;; Resolve the entity's class so we can coerce JSON-shaped slot
    ;; values into Datomic-shaped values via dt/range-of (slot map's
    ;; values from JSON arrive as strings; codec needs proper keyword /
    ;; instant / etc.).
    (let [entity-current (dt/find-by-ident entity-ref)
          class-ident    (dt/class-ident-of entity-current)
          slot-map       (coerce-slot-map class-ident slot-arg)
          updated        (dt/update-entity! entity-ref slot-map)]
      {:entity (str entity-ref)
       :slots  slot-map
       :result (entity-projection updated)})))

(defn- entity-validate-handler [args]
  (let [class-ident (->ident (or (get args "class") (get args :class)))
        slots       (or (get args "slots") (get args :slots) {})]
    (when (nil? class-ident)
      (throw (ex-info "Missing required argument: class" {:args args})))
    (let [props  (coerce-slot-map class-ident slots)
          errors (dt/validate-data class-ident props)]
      (if errors
        {:valid? false :errors errors}
        {:valid? true}))))

;; ---------- Workflow operations ----------

(defn- workflow-arg [args]
  (or (->ident (get args "workflow"))
      (->ident (get args :workflow))
      (throw (ex-info "Missing required argument: workflow" {:args args}))))

(defn- workflow-define-handler [args]
  (let [spec (or (get args "spec") (get args :spec))]
    (when (nil? spec) (throw (ex-info "Missing required argument: spec" {:args args})))
    {:workflow (workflow/define-workflow! spec)}))

(defn- workflow-find-handler [args]
  (let [w (workflow-arg args)
        def (workflow/find-workflow w)]
    {:workflow (str w) :definition (entity-projection def)}))

(defn- workflow-start-process-handler [args]
  (let [w       (workflow-arg args)
        subject (or (get args "subject") (get args :subject))
        data    (or (get args "data") (get args :data) {})]
    ;; workflow/start-process! signature: [workflow subject & {:keys [data]}]
    ;; — :data is a KWARG, not positional.  Prior call `(start-process! w
    ;; subject data)` placed data in the rest-seq which never matched the
    ;; :data destructure, so user-supplied data was silently dropped
    ;; (ultrareview #5 at tools.clj:454).
    (let [process (workflow/start-process! w subject :data data)]
      {:process-id (str (:db/id process))
       :workflow   (str w)
       :state      (->ident-str (workflow/get-current-state process))})))

(defn- workflow-transition-handler [args]
  (let [process-id (or (get args "process-id") (get args :process-id))
        transition (->ident (or (get args "transition") (get args :transition)))
        reason     (or (get args "reason") (get args :reason))
        opts       (cond-> {}
                     reason (assoc :reason reason))]
    (when (nil? process-id) (throw (ex-info "Missing required argument: process-id" {:args args})))
    (when (nil? transition) (throw (ex-info "Missing required argument: transition" {:args args})))
    (let [eid (if (number? process-id) process-id (Long/parseLong (str process-id)))
          p   (workflow/find-process eid)
          result (workflow/transition! p transition opts)]
      {:process-id (str eid)
       :new-state  (->ident-str (workflow/get-current-state result))
       :terminal?  (boolean (workflow/process-in-terminal-state? result))})))

(defn- workflow-process-state-handler [args]
  (let [process-id (or (get args "process-id") (get args :process-id))]
    (when (nil? process-id) (throw (ex-info "Missing required argument: process-id" {:args args})))
    (let [eid (if (number? process-id) process-id (Long/parseLong (str process-id)))
          p   (workflow/find-process eid)]
      {:process-id (str eid)
       :state      (->ident-str (workflow/get-current-state p))
       :terminal?  (boolean (workflow/process-in-terminal-state? p))
       :completed? (boolean (workflow/process-completed? p))})))

(defn- workflow-process-history-handler [args]
  (let [process-id (or (get args "process-id") (get args :process-id))]
    (when (nil? process-id) (throw (ex-info "Missing required argument: process-id" {:args args})))
    (let [eid (if (number? process-id) process-id (Long/parseLong (str process-id)))
          p   (workflow/find-process eid)]
      {:process-id (str eid)
       :history    (workflow/get-process-history p)})))

(defn- workflow-active-processes-handler [args]
  (let [w (or (->ident (get args "workflow")) (->ident (get args :workflow)))]
    {:workflow (when w (str w))
     :processes (mapv entity-projection
                      (if w
                        (workflow/active-processes :workflow w)
                        (workflow/active-processes)))}))

;; ---------- Validation service ----------

(defn- validation-start-handler [args]
  (let [class-ident (->ident (or (get args "class") (get args :class)))]
    (when (nil? class-ident)
      (throw (ex-info "Missing required argument: class" {:args args})))
    {:validation (validation/start-validation! class-ident)}))

;; validation/{run,cancel,retry,get-validation-results}! all expect a
;; PROCESS ENTITY (a `:workflow/Process` map), not a raw eid.  Prior
;; handlers passed `validation-id` (eid as string or number) directly,
;; which produced silent failures or wrong-shape errors deep in the
;; validation service.  Per ultrareview #9 at tools.clj:512 — resolve
;; eid → entity at the MCP boundary using `workflow/find-process`.
;;
;; The eid coercion pattern matches workflow-transition-handler above.

(defn- resolve-validation-process
  "Coerce the MCP-supplied validation-id (string or number) to a workflow
   process entity.  Throws when the id is missing or doesn't resolve."
  [args]
  (let [validation-id (or (get args "validation-id") (get args :validation-id))]
    (when (nil? validation-id)
      (throw (ex-info "Missing required argument: validation-id" {:args args})))
    (let [eid     (if (number? validation-id) validation-id (Long/parseLong (str validation-id)))
          process (workflow/find-process eid)]
      (when (nil? process)
        (throw (ex-info (str "Validation process not found: " validation-id)
                        {:validation-id validation-id :eid eid})))
      process)))

(defn- validation-run-handler [args]
  (let [process (resolve-validation-process args)]
    {:result (validation/run-validation! process)}))

(defn- validation-cancel-handler [args]
  (let [process (resolve-validation-process args)]
    {:cancelled (validation/cancel-validation! process)}))

(defn- validation-retry-handler [args]
  (let [process (resolve-validation-process args)]
    {:retried (validation/retry-validation! process)}))

(defn- validation-results-handler [args]
  (let [process (resolve-validation-process args)]
    {:results (validation/get-validation-results process)}))

(defn- validation-history-handler [args]
  (let [class-ident (->ident (or (get args "class") (get args :class)))]
    {:class (when class-ident (str class-ident))
     :history (if class-ident
                (validation/get-validation-history class-ident)
                (validation/recent-validations))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Verb catalog — data-driven dispatch
;;
;; Each entry: tool name + title + description + inputSchema + handler.
;; tools/list emits the catalog (sans :handler); tools/call looks up the
;; handler by name and invokes it with the request arguments.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private class-arg-schema
  {:class {:type "string" :description "Class ident (e.g. ':zorp/Footwear' or 'zorp/Footwear')"}})

(def ^:private property-arg-schema
  {:property {:type "string" :description "Property ident"}})

(def ^:private no-args-schema
  {:type "object" :properties {} :required []})

(defn- one-required [props required-keys]
  {:type "object" :properties props :required (mapv name required-keys)})

(def verb-catalog
  "The stable operational verb catalog.  Adding a verb is one entry
   here + one handler function above + (optionally) a test in
   `test/sandbar/mcp/tools_test.clj`."
  [;; Schema introspection
   {:name "sandbar.schema.classes"
    :title "List all classes"
    :description "Return all `:dt/Class` instances (idents) in the metamodel."
    :inputSchema no-args-schema
    :handler schema-classes-handler}
   {:name "sandbar.schema.properties"
    :title "List all properties"
    :description "Return all `:dt/Property` instances (idents) in the metamodel."
    :inputSchema no-args-schema
    :handler schema-properties-handler}
   {:name "sandbar.schema.entities"
    :title "Batch fetch entities across classes"
    :description "Return entity-spec maps grouped by class for all non-abstract classes (default) or a `:classes` filter.  Single round-trip alternative to N+1 sandbar.class.instances calls.  Result shape: `{:by-class {class-ident-string [entity-map ...]} :total-classes int :total-entities int}`.  Per Stage G Signal 8 (corpus-side friction-discovery)."
    :inputSchema {:type "object"
                  :properties {:classes {:type "array"
                                          :items {:type "string"}
                                          :description "Optional list of class-ident strings to fetch; default fetches all non-abstract classes"}}
                  :required []}
    :handler schema-entities-handler}
   {:name "sandbar.schema.datatypes"
    :title "List all Datomic value types"
    :description "Return all `:db.type/*` value types available."
    :inputSchema no-args-schema
    :handler schema-datatypes-handler}

   ;; Class introspection
   {:name "sandbar.class.describe"
    :title "Describe a class"
    :description "Return abstract? + parents + ancestors + subclasses + slots for a class."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-describe-handler}
   {:name "sandbar.class.slots"
    :title "All slots of a class (inherited + direct)"
    :description "Return the effective slot set for a class (inherited from parents + directly declared)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-slots-handler}
   {:name "sandbar.class.direct-slots"
    :title "Direct slots only (no inheritance)"
    :description "Return only the slots declared directly on a class (no inherited slots)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-direct-slots-handler}
   {:name "sandbar.class.required-slots"
    :title "Required slots of a class"
    :description "Return the subset of slots that are required (`:dt/required true`)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-required-slots-handler}
   {:name "sandbar.class.instances"
    :title "All instances of a class"
    :description "Return all entities that are instances of a class (including subclass instances)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-instances-handler}
   {:name "sandbar.class.subclasses"
    :title "All subclasses (transitive)"
    :description "Return all transitive subclasses of a class."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-subclasses-handler}
   {:name "sandbar.class.parents"
    :title "Direct parents + all ancestors"
    :description "Return direct parents + all ancestor classes."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-parents-handler}
   {:name "sandbar.class.validate-all-instances"
    :title "Validate all instances of a class"
    :description "Run validation against every instance of a class; return the report."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-validate-all-instances-handler}

   ;; Type predicates
   {:name "sandbar.types.instance-of"
    :title "Is entity an instance of class?"
    :description "Predicate: returns true if entity is an instance of class (direct or subclass)."
    :inputSchema (one-required
                   (merge class-arg-schema
                          {:entity {:type "string" :description "Entity ident or eid"}})
                   [:class :entity])
    :handler types-instance-of-handler}
   {:name "sandbar.types.subclass-of"
    :title "Is child a subclass of parent?"
    :description "Predicate: returns true if child is a transitive subclass of parent."
    :inputSchema (one-required
                   {:parent {:type "string" :description "Parent class ident"}
                    :child  {:type "string" :description "Child class ident"}}
                   [:parent :child])
    :handler types-subclass-of-handler}

   ;; Property introspection
   {:name "sandbar.property.domain"
    :title "Property domain"
    :description "Return the domain class of a property (`:dt/domain`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-domain-handler}
   {:name "sandbar.property.range"
    :title "Property range"
    :description "Return the value-type range of a property (`:dt/range` / `:db/valueType`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-range-handler}
   {:name "sandbar.property.cardinality"
    :title "Property cardinality"
    :description "Return the cardinality of a property (`:db.cardinality/one` or `/many`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-cardinality-handler}

   ;; Entity operations
   {:name "sandbar.entity.create"
    :title "Create an entity"
    :description "Create a new entity of `:class` with `:slots`; validated via `dt/make`.  Optionally accepts `:format` + `:source` — when both are provided, the codec mediator parses `:source` as the named wire format (e.g., :markdown) and merges the parsed slots with the explicit `:slots` map (explicit wins).  Codec arc Stage F.3a per plans/sandbar_codec_layer_arc_2026-05-12.md."
    :inputSchema (one-required
                   {:class  {:type "string" :description "Class ident"}
                    :slots  {:type "object" :description "Slot map (slot-ident-string → value); optional when :source is provided"}
                    :format {:type "string" :description "Optional codec format keyword (e.g., :markdown / :json); requires :source"}
                    :source {:type "string" :description "Optional raw native-representation string parsed via :format codec"}}
                   [:class])
    :handler entity-create-handler}
   {:name "sandbar.entity.find"
    :title "Find an entity by ident or id"
    :description "Look up an entity by `:ident` (keyword string) or `:id` (eid)."
    :inputSchema {:type "object"
                  :properties {:ident {:type "string" :description "Entity ident"}
                               :id    {:type "integer" :description "Entity eid"}}
                  :required []}
    :handler entity-find-handler}
   {:name "sandbar.entity.update"
    :title "Update an entity's slots"
    :description "Update slot values on an existing entity (NOT YET IMPLEMENTED — pending dt/update-entity primitive)."
    :inputSchema (one-required
                   {:entity {:type "string" :description "Entity ident or eid"}
                    :slots  {:type "object" :description "Slot updates"}}
                   [:entity :slots])
    :handler entity-update-handler}
   {:name "sandbar.entity.validate"
    :title "Validate a slot map against a class"
    :description "Pre-transaction validation: check that `:slots` would be valid for `:class`. No write."
    :inputSchema (one-required
                   {:class {:type "string" :description "Class ident"}
                    :slots {:type "object" :description "Slot map to validate"}}
                   [:class :slots])
    :handler entity-validate-handler}

   ;; Workflow operations
   {:name "sandbar.workflow.define"
    :title "Define a workflow"
    :description "Register a new workflow definition from a spec (states + transitions)."
    :inputSchema (one-required {:spec {:type "object" :description "Workflow spec"}} [:spec])
    :handler workflow-define-handler}
   {:name "sandbar.workflow.find"
    :title "Find a workflow definition"
    :description "Look up a workflow definition by ident."
    :inputSchema (one-required {:workflow {:type "string"}} [:workflow])
    :handler workflow-find-handler}
   {:name "sandbar.workflow.start-process"
    :title "Start a workflow process"
    :description "Create a new workflow process attached to a subject; returns the new process id."
    :inputSchema (one-required
                   {:workflow {:type "string"}
                    :subject  {:type "string" :description "Subject entity ident or eid"}
                    :data     {:type "object" :description "Initial process data"}}
                   [:workflow :subject])
    :handler workflow-start-process-handler}
   {:name "sandbar.workflow.transition"
    :title "Transition a workflow process"
    :description "Apply a named transition to a workflow process."
    :inputSchema (one-required
                   {:process-id {:type "integer"}
                    :transition {:type "string"}
                    :reason     {:type "string" :description "Optional human-readable reason"}}
                   [:process-id :transition])
    :handler workflow-transition-handler}
   {:name "sandbar.workflow.process-state"
    :title "Current state of a process"
    :description "Return the current state + terminal flag + completion flag for a workflow process."
    :inputSchema (one-required {:process-id {:type "integer"}} [:process-id])
    :handler workflow-process-state-handler}
   {:name "sandbar.workflow.process-history"
    :title "Process transition history"
    :description "Return the full transition history of a workflow process."
    :inputSchema (one-required {:process-id {:type "integer"}} [:process-id])
    :handler workflow-process-history-handler}
   {:name "sandbar.workflow.active-processes"
    :title "Active processes"
    :description "Return all active (non-terminal) workflow processes; optionally filtered by workflow."
    :inputSchema {:type "object"
                  :properties {:workflow {:type "string" :description "Optional workflow ident"}}
                  :required []}
    :handler workflow-active-processes-handler}

   ;; Validation service
   {:name "sandbar.validation.start"
    :title "Start a validation run"
    :description "Begin validating all instances of a class as a tracked workflow."
    :inputSchema (one-required class-arg-schema [:class])
    :handler validation-start-handler}
   {:name "sandbar.validation.run"
    :title "Run a queued validation"
    :description "Execute a previously-started validation."
    :inputSchema (one-required {:validation-id {:type "integer"}} [:validation-id])
    :handler validation-run-handler}
   {:name "sandbar.validation.cancel"
    :title "Cancel a validation"
    :description "Cancel an in-flight validation run."
    :inputSchema (one-required {:validation-id {:type "integer"}} [:validation-id])
    :handler validation-cancel-handler}
   {:name "sandbar.validation.retry"
    :title "Retry a validation"
    :description "Re-run a failed validation."
    :inputSchema (one-required {:validation-id {:type "integer"}} [:validation-id])
    :handler validation-retry-handler}
   {:name "sandbar.validation.results"
    :title "Get validation results"
    :description "Fetch the results of a completed validation run."
    :inputSchema (one-required {:validation-id {:type "integer"}} [:validation-id])
    :handler validation-results-handler}
   {:name "sandbar.validation.history"
    :title "Validation history"
    :description "Recent validation runs (all classes or filtered by `:class`)."
    :inputSchema {:type "object"
                  :properties (merge class-arg-schema {})
                  :required []}
    :handler validation-history-handler}

   ;; Codec + project-graph operations (Stage F.3b)
   {:name "sandbar.codec.list"
    :title "List registered codecs"
    :description "Return the set of codecs registered with the Sandbar codec mediator (format keyword + supported MIME types).  Per codec arc Stage F.3b."
    :inputSchema {:type "object" :properties {} :required []}
    :handler codec-list-handler}
   {:name "sandbar.project.export"
    :title "Project entities to filesystem hierarchy"
    :description "Project mm/Memory instances to a filesystem hierarchy at `:to` via sandbar.projection.  Each entity emits as native representation per its class's `:dt/native-codec`.  Anderson de.setf.rdf:project-graph lineage per the codec ADR §1.1.  Optional `:filter` spec enables partition flexibility for hybrid FS/DB experimentation (per ideas/sandbar_project_export_filtering_for_hybrid_backend_experimentation_2026_05_13.md) — keys: `:class` (single ident), `:classes` (array), `:tree-filter` (rel-path prefix)."
    :inputSchema (one-required
                   {:to     {:type "string" :description "Output directory path"}
                    :filter {:type "object"
                              :description "Optional filter spec: {:class :mm/Memory, :classes [..], :tree-filter \"decisions/\"}"}}
                   [:to])
    :handler project-export-handler}
   {:name "sandbar.project.import"
    :title "Ingest entities from filesystem hierarchy"
    :description "Walk `:from` directory; parse each .md file via sandbar.codec.markdown; return the entity-spec maps.  Inverse of project.export.  Optional `:filter` spec same shape as project.export."
    :inputSchema (one-required
                   {:from   {:type "string" :description "Input directory path"}
                    :filter {:type "object"
                              :description "Optional filter spec (same shape as project.export)"}}
                   [:from])
    :handler project-import-handler}

   ;; Aggregation operations (Stage 14 — fulltext arc Phase G).
   ;; Descriptions follow the WHICH/WHEN/HOW/ORDER/COMBINATION discipline
   ;; per authorizations/sandbar_focus_for_0_1_0_release_plus_mcp_llm_consumability_discipline_2026_05_14.md.
   {:name "sandbar.aggregate.count"
    :title "Count entities of a class (with optional Datalog filter)"
    :description "WHICH: returns a single integer count of entities that are instances of `:class` (including subclass instances) matching an optional `:where` Datalog filter.\n\nWHEN: use when you need the SIZE of a class's instance set — total count or a filtered subset.  When NOT to use: (a) you also need the entities themselves — use `sandbar.class.instances` (full set) or `sandbar.aggregate.rank-by` (top-K); (b) you need counts BROKEN DOWN BY a slot value — use `sandbar.aggregate.group-by` instead.\n\nHOW: `:class` is the class ident (e.g. `:mm/Memory`).  `:where` is an EDN-STRING of Datalog clauses (JSON has no native representation for Datalog symbols `?e`, `?v`); the convention is `?e` for the entity at the head of the count walk.  Example: `\"[[?e :mm.memory/memory-type :decision]]\"`.\n\nORDER: no prerequisites; leaf-call.  To discover available classes first, use `sandbar.schema.classes`; to discover slot idents on a class, use `sandbar.class.slots`.\n\nCOMBINATION: composes with `sandbar.aggregate.group-by` (`count` for the cardinality, `group-by` for the breakdown) and with `sandbar.aggregate.rank-by` (use `count` to size the candidate population first).\n\nResult: `{:count <int>}`.  Per fulltext arc Stage 14 of plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
    :inputSchema (one-required
                   {:class {:type "string"
                            :description "Class ident (e.g. ':mm/Memory')"}
                    :where {:type "string"
                            :description "Optional EDN-string of Datalog clauses; ?e is the entity at the head of the count walk"}}
                   [:class])
    :handler aggregate-count-handler}
   {:name "sandbar.aggregate.group-by"
    :title "Group-by-count facet aggregation over a class's instances"
    :description "WHICH: groups instances of `:class` by `:group-by` slot value, returns `{value: count}` map + total.\n\nWHEN: use for facet-style breakdowns — 'how many memories of each memory-type?', 'how many properties in each domain?'.  When NOT to use: (a) only the total count is needed — use `sandbar.aggregate.count`; (b) you need ranked instances rather than counts — use `sandbar.aggregate.rank-by`; (c) you need group-counts over a FULLTEXT match-set — use `sandbar.search.bm25f` with `:include [:facets]` opt instead (search → facet in one call).\n\nHOW: `:class` is the class ident.  `:group-by` is the slot ident to group by (must be a single-valued slot — multi-cardinality slots will count each value separately).  Optional `:where` (EDN-string Datalog) constrains the candidate set BEFORE grouping.  Entities where `:group-by` is unset are skipped (not counted in any group).\n\nORDER: no prerequisites.  Discover slot idents first via `sandbar.class.slots` if uncertain.\n\nCOMBINATION: pairs with `sandbar.aggregate.count` (total) and `sandbar.aggregate.rank-by` (top-K within a group, via a follow-up call per group).  Use `sandbar.search.bm25f` `:facet-by` opt for the same shape over a search match-set.\n\nResult: `{:groups {value count} :total <int>}`.  Per fulltext arc Stage 14."
    :inputSchema (one-required
                   {:class    {:type "string"
                               :description "Class ident"}
                    :group-by {:type "string"
                               :description "Slot ident to group by (e.g. ':mm.memory/memory-type')"}
                    :where    {:type "string"
                               :description "Optional EDN-string of Datalog clauses"}}
                   [:class :group-by])
    :handler aggregate-group-by-handler}
   {:name "sandbar.aggregate.rank-by"
    :title "Structural-rank re-ordering by edge degree / backlink-density / recency / freshness"
    :description "WHICH: re-orders instances of `:class` by one of four structural-rank axes:\n  * `:degree` — total ref-attribute count (outbound + inbound by default; substrate's most-connected entities)\n  * `:backlink-density` — inbound ref-attribute count (who CITES this entity?  prominence-by-citation)\n  * `:recency` — descending order by `:temporal-slot` value (most-recently-touched first)\n  * `:freshness` — ASCENDING order by `:temporal-slot` value (stalest first; candidates meriting attention/review)\n\nWHEN: use for 'top-K' retrieval where ranking is structural, not content-based.  Compose with content-based ranking via `sandbar.search.bm25f` (bm25f scores content; rank-by re-orders structural axes).  When NOT to use: (a) content-relevance ranking — use `sandbar.search.bm25f`; (b) you only need counts — use `sandbar.aggregate.count` / `.group-by`.\n\nHOW: `:class` + `:rank-by` are required.  `:rank-by` is the axis keyword.  `:limit` defaults to 20 (0 = no cap).  CRITICAL: `:temporal-slot` is REQUIRED for `:rank-by :recency` and `:freshness` — substrate is class-agnostic; you must supply the temporal-axis slot (e.g. `:mm.memory/last-touched` for memory recency, `:mm.memory/last-reviewed` for freshness).  Calling `:recency` without `:temporal-slot` raises 400.  For `:degree` / `:backlink-density`, no `:temporal-slot` needed.\n\nORDER: no prerequisites.  To discover temporal-axis slot candidates on a class, use `sandbar.class.slots`.\n\nCOMBINATION: ranked instances become a candidate set for downstream filtering or projection.  Combine with `sandbar.aggregate.group-by` for stratified ranking (rank-then-group; though only the top-K within the global rank is preserved).  For ranking over a fulltext match-set, the cross-axis composition lands at Stage 29 (`:where` opt on rank-by today provides Datalog-filter composition; full structural-rank-over-bm25f-match-set is Stage 29).\n\nResult: `{:hits [{:entity <entity-map> :rank-score <num>} ...] :total <int> :returned <int>}`.  Per fulltext arc Stage 14."
    :inputSchema (one-required
                   {:class         {:type "string"
                                    :description "Class ident"}
                    :rank-by       {:type "string"
                                    :description "Axis: ':degree' / ':backlink-density' / ':recency' / ':freshness'"}
                    :limit         {:type "integer"
                                    :description "Max hits to return (default 20; 0 = no cap)"}
                    :temporal-slot {:type "string"
                                    :description "REQUIRED for :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')"}}
                   [:class :rank-by])
    :handler aggregate-rank-by-handler}

   ;; Navigation — siblings-of (Stage 22 — fulltext arc Phase N)
   {:name "sandbar.navigate.siblings-of"
    :title "Same-directory peers of an entity via a filesystem-style path slot"
    :description "WHICH: returns entities whose `:path-slot` value shares the same directory prefix as `:entity`'s `:path-slot` value (filesystem-style — 'decisions/foo.md' is a sibling of 'decisions/bar.md' but NOT of 'decisions/sub/baz.md' or 'patterns/foo.md').\n\nWHEN: use to enumerate documents stored under the same logical 'directory' as a given anchor — e.g., listing all decision memorials in `decisions/`, all guides in `doc/guides/`.  When NOT to use: (a) entities lacking a filesystem-style path slot — reach for `sandbar.navigate.inbound` with `:next-sibling` / `:previous-sibling` predicate filter for typed-edge SIOC-pairwise sibling chains (mm/Section pattern); (b) recursive descent through sub-directories — reach for `sandbar.navigate.path-via` with `:FILTER` over a directory prefix.\n\nHOW: `:entity` is the anchor entity (ident or eid); `:path-slot` is the attribute ident carrying the filesystem-style path (e.g., `:mm.memory/rel-path`).  The substrate is CLASS-AGNOSTIC — the slot is caller-supplied; no hardcoded knowledge of memory-model vs other domain classes.  Optional `:limit` caps returned siblings (default 0 = no cap; cap is applied after substrate lookup so `:total` reflects the full set).\n\nORDER: prerequisite — `:entity` must have `:path-slot` populated.  If uncertain, verify first via `sandbar.entity.find` (reads the entity by ident).  No other ordering dependencies; this verb is leaf-call shape.\n\nCOMBINATION: pairs naturally with `sandbar.navigate.inbound` / `.outbound` (typed-edge neighbors) for complete sibling-discovery (filesystem-style + typed-edge).  For ranked sibling subsets, compose downstream with `sandbar.aggregate.rank-by` using the sibling-eid set as the candidate population.  For fulltext search restricted to siblings, the cross-axis `:from` + `:via` composition lands at Stage 29.\n\nResult: `{:siblings [<entity-map>...] :total <int> :returned <int>}`.  Each entity-map carries `:db/id`, `:db/ident` (if interned), and namespaced-keyword slots.  Per fulltext arc Stage 22 of plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
    :inputSchema (one-required
                   {:entity    {:type "string"
                                :description "Anchor entity ident (e.g. ':mm.memory/decisions-foo') or eid"}
                    :path-slot {:type "string"
                                :description "Slot ident carrying the filesystem-style path (e.g. ':mm.memory/rel-path')"}
                    :limit     {:type "integer"
                                :description "Max returned siblings (default 0 = no cap)"}}
                   [:entity :path-slot])
    :handler navigate-siblings-of-handler}

   ;; Navigation — path-grammar walker (Stage P-6 — fulltext arc Phase N / Stage P)
   {:name "sandbar.navigate.path-via"
    :title "Walk a Wilbur-lineage path-grammar expression from a seed entity"
    :description "WHICH: walks a path-grammar expression (`:via`) starting from a seed entity (`:from`); returns the set of entities reachable under the binary-relation algebra denoted by the expression.  Path-grammar is Kleene-algebra-over-binary-relations — same lineage as SPARQL 1.1 property paths and ISO GQL 39075:2024.\n\nWHEN: use when navigation needs more expressiveness than direct edges (`sandbar.navigate.inbound` / `.outbound`) or bounded BFS — specifically when you need Kleene closure (`:REP*` / `:REP+`), alternation (`:OR`), inverse traversal at depth, or shape-specific restrictions.  Real-world property-path queries are <0.1% of total per Bonifati 2017 — but when you need them, only path-grammar fits.  When NOT to use: (a) single hop — use `sandbar.navigate.outbound` / `.inbound` (simpler + faster); (b) bounded N-hop reachability — use `sandbar.navigate.walk` (BFS with hop-cap is more efficient than `:REP*` for known-depth walks); (c) you need the seed itself in results — `:REP*` (or `:OPT`) includes the seed via the zero-application branch.\n\nHOW: `:from` is the seed entity ident or eid.  `:via` is an EDN-STRING path expression using one of 13 currently-executable operators:\n  * Canonical-8 (Tier-1): `:SEQ` (n-ary sequence) / `:OR` (n-ary union) / `:REP+` (transitive closure 1+) / `:REP*` (reflexive-transitive 0+) / `:INV` (inverse — swap subject/object roles) / `:SELF` (identity) / `:RESTRICT [pred value]` (specific-node filter) / `:ANY` (wildcard predicate)\n  * Tier-2: `:NOT` (atomic-predicate property-set negation) / `:OPT` (zero-or-one; desugars to `(:OR p :SELF)`) / `:REP p min max` (bounded repetition) / `:FILTER p substring` (URI-substring filter on `:db/ident`) / `:TEST p fn-name` (functional predicate via registered fn)\nCasing: UPPERCASE combinators / lowercase predicates.  Examples:\n  * `\"[:REP+ :dt/subclass-of]\"` — transitive ancestor walk\n  * `\"[:SEQ [:REP* [:OR :cites :evidences]] [:RESTRICT [:dt/type :mm.memory/decision]]]\"` — closure-then-filter\n  * `\"[:INV [:REP+ :cites]]\"` — entities that transitively cite this seed\nTier-3 operators (`:LANG`, `:VALUE`, `:DAEMON`, `:NOREWRITE`, `:MEMBERS`, `:PREDICATE-OF-*`) are vocabulary-registered but compilation deferred; passing them raises descriptive ex-info.  `:include [\"paths\"]` is accepted but path-data is not yet populated (recursive-path reconstruction lands at follow-on); result carries `:path-data-deferred true` flag when requested.\n\nORDER: no strict prerequisites.  To explore the typed-edge vocabulary available at the seed first, call `sandbar.navigate.outbound` to see what predicates emerge from the entity; to discover class-hierarchy predicates, use `sandbar.class.slots` on a class.\n\nCOMBINATION: composes with `sandbar.navigate.inbound`/`.outbound` (use them to discover predicate vocab before authoring path expressions) and `sandbar.navigate.walk` (use walk first if depth-bounded reachability is enough; reach for path-via only when Kleene closure adds value).  Cross-axis composition with `sandbar.search.bm25f` (`:from` + `:via` opts to restrict candidate set) and `sandbar.aggregate.rank-by` (rank within a graph-walk neighborhood) lands at Stage 29.\n\nResult: `{:reachable [<entity-map>...] :total <int> :returned <int>}`.  Per fulltext arc Stage P-6 of plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
    :inputSchema (one-required
                   {:from    {:type "string"
                              :description "Seed entity ident (e.g. ':dt/Property') or eid"}
                    :via     {:type "string"
                              :description "EDN-string path expression (e.g. \"[:REP* [:OR :cites :evidences]]\")"}
                    :limit   {:type "integer"
                              :description "Max returned entities (default 0 = no cap)"}
                    :include {:type "array"
                              :items {:type "string"}
                              :description "Projection options; supports 'paths' (deferred surfacing)"}}
                   [:from :via])
    :handler navigate-path-via-handler}])

(def ^:private verb-by-name
  (into {} (map (juxt :name identity)) verb-catalog))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/list — return the verb catalog
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-list
  "MCP `tools/list` — return the stable verb catalog.
   Catalog is constant regardless of schema state; schema evolution
   surfaces through the `sandbar.schema.*` + `sandbar.class.*` read
   verbs, not through tools/list."
  [id _params]
  (envelope/jsonrpc-result id
                           {:tools (mapv #(dissoc % :handler) verb-catalog)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/call — dispatch verb by name; project result to MCP content array
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- result->content
  "Project a handler's return value to an MCP `content` array entry.
   Per MCP spec: `content` is an array of typed parts; `text` parts
   carry stringified content.  We JSON-encode the handler's return
   value for consistent client-side parsing."
  [data]
  [{:type "text"
    :text (json/generate-string data {:pretty true})}])

(defn handle-call
  "MCP `tools/call` — dispatch a named verb from the catalog and project
   its result.

   Response shapes:
   - Success: `{:content [{:type \"text\" :text <json>}]}`
   - User error (ex-info from handler): `{:content [...] :isError true}`
   - Unknown verb: JSON-RPC `-32602` invalid params
   - Internal error: JSON-RPC `-32603`"
  [id params]
  (let [tool-name (:name params)
        arguments (:arguments params {})
        verb      (get verb-by-name tool-name)]
    (cond
      (nil? verb)
      (envelope/jsonrpc-error id -32602
                              (str "Unknown tool: " tool-name)
                              {:received-name tool-name
                               :available-tools (mapv :name verb-catalog)})

      :else
      (try
        (let [result (try
                       ((:handler verb) arguments)
                       (catch clojure.lang.ExceptionInfo e
                         {:_user-error true
                          :message (.getMessage e)
                          :details (ex-data e)}))]
          (if (:_user-error result)
            (envelope/jsonrpc-result id
                                     {:content (result->content
                                                 (dissoc result :_user-error))
                                      :isError true})
            (envelope/jsonrpc-result id
                                     {:content (result->content result)})))
        (catch Exception e
          (log/error e :MCP/tools-call-error {:tool tool-name})
          (envelope/jsonrpc-error id -32603
                                  "Tool execution failed"
                                  {:tool tool-name
                                   :exception-message (.getMessage e)}))))))
