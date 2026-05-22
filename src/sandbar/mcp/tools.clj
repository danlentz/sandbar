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
            [datomic.api                :as d]
            [sandbar.aggregate          :as aggregate]
            [sandbar.audit.tag          :as audit-tag]
            [sandbar.codec              :as codec]
            [sandbar.codec.markdown     :as codec-md]
            [sandbar.entity-ref         :as eref]
            [sandbar.navigate.edges     :as nav-edges]
            [sandbar.navigate.path      :as nav-path]
            [sandbar.navigate.siblings  :as nav-siblings]
            [sandbar.orient             :as orient]
            [sandbar.projection      :as pg]
            [sandbar.search             :as search]
            [sandbar.db.datatype        :as dt]
            [sandbar.db.datomic         :as db]
            [sandbar.mcp.envelope       :as envelope]
            [sandbar.mcp.notifications  :as notifications]
            [sandbar.mcp.resources      :as resources]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]
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

(defn- class-arg
  "Shape-coerce the `class` arg from MCP args to an ident keyword.

   Accepts the arg under either `\"class\"` (JSON-keyword) or `:class`
   (EDN-keyword) key.  Uses `->ident` shape-coercion (NOT `eref/resolve`)
   intentionally — this helper is DB-independent per the
   `sandbar.mcp.tools-test` (non-DB) test contract.

   Handlers that NEED runtime existence validation should call
   `eref/resolve-ident` (or `eref/resolve` for entity map) on
   `(class-arg args)` explicitly — boundary validation lives in the
   HANDLER, not in this shape-coercion helper.

   Returns: class's ident keyword (shape-coerced; not validated)
   Throws: ex-info on missing arg (no `\"class\"` / `:class` key)"
  [args]
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
  (let [c-raw (or (get args "class") (get args :class))
        e-raw (or (get args "entity") (get args :entity))]
    (when (nil? c-raw) (throw (ex-info "Missing required argument: class" {:args args})))
    (when (nil? e-raw) (throw (ex-info "Missing required argument: entity" {:args args})))
    (let [c (eref/resolve-ident c-raw)
          e (eref/resolve-ident e-raw)]
      {:class (str c) :entity (str e) :instance-of? (boolean (dt/instance-of? c e))})))

(defn- types-subclass-of-handler [args]
  (let [parent-raw (or (get args "parent") (get args :parent))
        child-raw  (or (get args "child") (get args :child))]
    (when (nil? parent-raw) (throw (ex-info "Missing required argument: parent" {:args args})))
    (when (nil? child-raw) (throw (ex-info "Missing required argument: child" {:args args})))
    (let [parent (eref/resolve-ident parent-raw)
          child  (eref/resolve-ident child-raw)]
      {:parent (str parent) :child (str child)
       :subclass-of? (boolean (dt/subclass-of? parent child))})))

;; ---------- Property introspection ----------

(defn- property-arg
  "Shape-coerce the `property` arg from MCP args to an ident keyword.
   DB-independent per `class-arg` doctrine — see `class-arg` docstring."
  [args]
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
  (let [class-arg   (or (get args "class") (get args :class))
        slots       (or (get args "slots") (get args :slots) {})
        ;; Codec arc Stage F.3a per
        ;; plans/sandbar_codec_layer_arc_2026-05-12.md — optional
        ;; :format + :source opts for codec-driven entity construction.
        format-arg  (or (get args "format") (get args :format))
        source-arg  (or (get args "source") (get args :source))]
    (when (nil? class-arg)
      (throw (ex-info "Missing required argument: class" {:args args})))
    (let [class-ident (eref/resolve-ident class-arg)]
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
  ;; Find-or-missing semantic — does NOT throw on not-found; returns a
  ;; structured `{:missing? true}` response.  Uses `eref/validate` (the
  ;; never-raises predicate-style entry point) instead of `eref/resolve`
  ;; to preserve that contract.
  ;;
  ;; Previously called `(db/entity lookup)` then checked `(some? e)`,
  ;; but `db/entity` returns a non-nil EntityMap for ANY input — so the
  ;; `(some? e)` branch was vacuously true and `:missing? true` never
  ;; fired (latent bug; reported entity maps for non-existent eids).
  ;; `eref/validate` correctly distinguishes existing vs missing.
  (let [ident-or-id (or (get args "ident") (get args :ident)
                        (get args "id")    (get args :id))]
    (when (nil? ident-or-id)
      (throw (ex-info "Missing required argument: ident (or id)" {:args args})))
    (let [{:keys [valid? entity reasons]} (eref/validate ident-or-id)]
      (if valid?
        {:entity (entity-projection entity)}
        {:entity nil :missing? true :lookup (str ident-or-id) :reasons reasons}))))

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
                                     (map #(if (dt/type-isa? :mm/Memory (:dt/type %))
                                             ;; Preserve existing :dt/type (which may be a Memory subclass
                                             ;; e.g. :mm/Decision); fall back to :mm/Memory if absent.
                                             (update % :dt/type (fn [t] (or t :mm/Memory)))
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

;; F#17 transact-boundary helpers (group-by-source + tempid-translation) moved
;; to `sandbar.codec.markdown/group-by-source` + `entity-specs->tx-data` per
;; 2026-05-20 consolidation.  Single source of truth at the codec layer per
;; decisions/sandbar_codec_layer_owns_wire_format_concerns_consumer_native_representation_2026_05_12.md.
;; Callers below delegate to the codec helpers.

(defn- project-import-handler [args]
  (let [from        (or (get args "from") (get args :from))
        filter-spec (->filter-spec (or (get args "filter") (get args :filter)))
        persist?    (boolean (or (get args "persist?")
                                  (get args "persist")
                                  (get args :persist?)
                                  (get args :persist)))]
    (when-not from
      (throw (ex-info "project.import requires :from (input directory path)"
                      {:args args})))
    (let [entities (pg/ingest-graph from (cond-> {}
                                            filter-spec (assoc :filter filter-spec)))]
      (if-not persist?
        ;; Dry-run: return summaries only
        {:from     from
         :filter   filter-spec
         :persist? false
         :imported (count entities)
         :entities (mapv (fn [e]
                           {:dt/type (:dt/type e)
                            :ident   (:db/ident e)})
                         entities)}
        ;; Persist: group by source file; one atomic transact per group.
        ;; Cross-entity refs (Memory ↔ Section) resolve via :db/ident
        ;; upsert within the single tx.  Per-group failures isolated;
        ;; one bad file does NOT abort the whole import.
        (let [groups  (codec-md/group-by-source entities)
              results (reduce
                       (fn [acc group]
                         (let [memory     (first group)
                               ident      (:db/ident memory)
                               class-ident (:dt/type memory)
                               ;; Bug fix 2026-05-21: do NOT dissoc :dt/type
                               ;; before transact.  Without :dt/type the entity
                               ;; has no class, and class.instances / aggregate.count
                               ;; can't find it.  The earlier dissoc was scope
                               ;; creep at boundary code that prevented the ingest
                               ;; from yielding queryable entities.
                               tx-data    (codec-md/entity-specs->tx-data group)]
                           (try
                             (dt/make-all* tx-data)
                             (update acc :persisted conj
                                     {:dt/type     class-ident
                                      :ident       ident
                                      :tx-entities (count group)})
                             (catch Throwable ex
                               (update acc :failed conj
                                       {:dt/type     class-ident
                                        :ident       ident
                                        :tx-entities (count group)
                                        :error       (.getMessage ex)})))))
                       {:persisted [] :failed []}
                       groups)]
          {:from           from
           :filter         filter-spec
           :persist?       true
           :imported       (count entities)
           :groups         (count groups)
           :persisted-count (count (:persisted results))
           :failed-count   (count (:failed results))
           :failed         (:failed results)})))))

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
  (let [class-ident      (class-arg args)
        group-by-raw     (or (get args "group-by") (get args :group-by))]
    (when (nil? group-by-raw)
      (throw (ex-info "Missing required argument: group-by" {:args args})))
    (let [group-by-ident (eref/resolve-ident group-by-raw)
          where          (->where-clauses
                           (or (get args "where") (get args :where)))]
      (aggregate/group-by {:class class-ident :group-by group-by-ident :where where}))))

(defn- aggregate-rank-by-handler [args]
  (let [class-ident        (class-arg args)
        rank-by-raw        (or (get args "rank-by") (get args :rank-by))
        limit-arg          (or (get args "limit") (get args :limit))
        temporal-slot-raw  (or (get args "temporal-slot") (get args :temporal-slot))]
    (when (nil? rank-by-raw)
      (throw (ex-info "Missing required argument: rank-by" {:args args})))
    (let [rank-by-ident      (eref/resolve-ident rank-by-raw)
          temporal-slot      (when (some? temporal-slot-raw)
                               (eref/resolve-ident temporal-slot-raw))
          opts (cond-> {:class class-ident :rank-by rank-by-ident}
                 (some? limit-arg)     (assoc :limit limit-arg)
                 (some? temporal-slot) (assoc :temporal-slot temporal-slot))]
      (aggregate/rank-by opts))))

;; ---------- Search operations (Stage 5.B-pre — 0.1.1 co-evolution arc) ----------
;;
;; Per decisions/stage_5_mcp_verb_authoring_sub_arc_2026_05_21.md — MCP
;; boundary wrapper around `sandbar.search/search-bm25f`.  The Clojure
;; function has lived in `sandbar.search` since the fulltext arc Stage 4c;
;; this verb exposes it as an MCP tool so memory-model client slash
;; commands like `/memory-search` can dispatch via the MCP server.

(defn- aggregate-tag-histogram-handler [args]
  (let [limit-arg (or (get args "limit") (get args :limit))
        opts      (cond-> {} (some? limit-arg) (assoc :limit limit-arg))]
    (aggregate/tag-histogram opts)))

(defn- search-bm25f-handler [args]
  (let [query           (or (get args "query") (get args :query))
        class-ident     (class-arg args)
        limit-arg       (or (get args "limit") (get args :limit))
        where-raw       (or (get args "where") (get args :where))
        facet-by-raw    (or (get args "facet-by") (get args :facet-by))
        include-raw     (or (get args "include") (get args :include))
        field-wts-raw   (or (get args "field-weights") (get args :field-weights))]
    (when (nil? query)
      (throw (ex-info "Missing required argument: query" {:args args})))
    (let [where     (when where-raw
                      (cond
                        (string? where-raw)     (read-string where-raw)
                        (sequential? where-raw) (vec where-raw)))
          facet-by  (when facet-by-raw
                      (mapv eref/resolve-ident
                            (if (sequential? facet-by-raw) facet-by-raw [facet-by-raw])))
          include   (when include-raw
                      (mapv keyword
                            (if (sequential? include-raw) include-raw [include-raw])))
          field-wts (when (map? field-wts-raw)
                      (into {} (for [[k v] field-wts-raw]
                                 [(eref/resolve-ident k) (double v)])))
          opts (cond-> {:query query :class class-ident}
                 (some? limit-arg) (assoc :limit limit-arg)
                 where             (assoc :where where)
                 facet-by          (assoc :facet-by facet-by)
                 (seq include)     (assoc :include include)
                 field-wts         (assoc :field-weights field-wts))]
      (search/search-bm25f opts))))

;; ---------- Navigate edges (Stage 5.B-pre #2 — 0.1.1 co-evolution arc) ----------
;;
;; Per decisions/stage_5_mcp_verb_authoring_sub_arc_2026_05_21.md.  Thin
;; MCP boundary wrappers around sandbar.navigate.edges/{inbound,outbound}-
;; edges.  Underpin /memory-xref + /memory-show slash commands.

(defn- parse-predicate-arg
  "Predicate arg may be a single keyword-string or a vec of keyword-strings.
   Resolve each to an ident via eref/resolve-ident."
  [raw]
  (when (some? raw)
    (if (sequential? raw)
      (mapv eref/resolve-ident raw)
      (eref/resolve-ident raw))))

(defn- navigate-outbound-edges-handler [args]
  (let [entity-raw       (or (get args "entity") (get args :entity))
        predicate-raw    (or (get args "predicate") (get args :predicate))
        target-type-raw  (or (get args "target-type") (get args :target-type))
        limit-arg        (or (get args "limit") (get args :limit))]
    (when (nil? entity-raw)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (let [opts (cond-> {:entity (eref/resolve-ident entity-raw)}
                 predicate-raw   (assoc :predicate (parse-predicate-arg predicate-raw))
                 target-type-raw (assoc :target-type (eref/resolve-ident target-type-raw))
                 (some? limit-arg) (assoc :limit limit-arg))]
      (nav-edges/outbound-edges opts))))

(defn- navigate-inbound-edges-handler [args]
  (let [entity-raw       (or (get args "entity") (get args :entity))
        predicate-raw    (or (get args "predicate") (get args :predicate))
        source-type-raw  (or (get args "source-type") (get args :source-type))
        limit-arg        (or (get args "limit") (get args :limit))]
    (when (nil? entity-raw)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (let [opts (cond-> {:entity (eref/resolve-ident entity-raw)}
                 predicate-raw   (assoc :predicate (parse-predicate-arg predicate-raw))
                 source-type-raw (assoc :source-type (eref/resolve-ident source-type-raw))
                 (some? limit-arg) (assoc :limit limit-arg))]
      (nav-edges/inbound-edges opts))))

;; ---------- Navigation operations (Stage P-6 — fulltext arc Phase N) ----------
;;
;; Per fulltext arc Stage P-6 of
;; plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.
;; MCP boundary wrapper around sandbar.navigate.path/path-via — the
;; path-grammar walker primitive.

(defn- ->axis-spec
  "Coerce a JSON-shaped axis-spec to the Clojure-shape expected by
   sandbar.orient/library-card.  Each axis arrives with string keys
   from JSON; predicate idents arrive as strings (':cites' or 'cites')."
  [m]
  (when (map? m)
    (let [g (fn [k] (or (get m (name k)) (get m k)))
          preds (g :predicates)
          target-type (g :target-type)
          source-type (g :source-type)]
      (cond-> {}
        (g :name)
        (assoc :name (g :name))

        (g :direction)
        (assoc :direction (keyword (g :direction)))

        preds
        (assoc :predicates
               (if (sequential? preds)
                 (mapv ->ident preds)
                 [(->ident preds)]))

        target-type
        (assoc :target-type (->ident target-type))

        source-type
        (assoc :source-type (->ident source-type))

        (g :limit)
        (assoc :limit (g :limit))))))

(defn- orient-type-tree-handler [args]
  (let [root-raw (or (get args "root") (get args :root))
        opts     (cond-> {}
                   root-raw (assoc :root (eref/resolve-ident root-raw)))]
    (orient/type-tree opts)))

(defn- orient-tree-handler [args]
  (let [class-ident   (class-arg args)
        path-slot-raw (or (get args "path-slot") (get args :path-slot))
        sample-size   (or (get args "sample-size") (get args :sample-size))]
    (when (nil? path-slot-raw)
      (throw (ex-info "Missing required argument: path-slot" {:args args})))
    (let [opts (cond-> {:class class-ident
                        :path-slot (eref/resolve-ident path-slot-raw)}
                 (some? sample-size) (assoc :sample-size sample-size))]
      (orient/tree opts))))

(defn- orient-library-card-handler [args]
  (let [entity-arg (or (get args "entity") (get args :entity))
        axes-arg   (or (get args "axes") (get args :axes))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (when-not (sequential? axes-arg)
      (throw (ex-info "Missing or non-sequential argument: axes (must be array of axis-spec objects)"
                      {:args args})))
    (let [entity-ident (eref/resolve-ident entity-arg)
          axes (mapv ->axis-spec axes-arg)]
      (orient/library-card {:entity entity-ident :axes axes}))))

(defn- navigate-siblings-of-handler [args]
  (let [entity-arg    (or (get args "entity") (get args :entity))
        path-slot-arg (or (get args "path-slot") (get args :path-slot))
        limit         (or (get args "limit") (get args :limit))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (when (nil? path-slot-arg)
      (throw (ex-info "Missing required argument: path-slot" {:args args})))
    (let [entity-ident (eref/resolve-ident entity-arg)
          path-slot    (eref/resolve-ident path-slot-arg)
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
    (let [from-ident (eref/resolve-ident from-arg)
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
        slot-arg   (or (get args "slots")  (get args :slots))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity (ident or eid)" {:args args})))
    (when (or (nil? slot-arg) (not (map? slot-arg)))
      (throw (ex-info "Missing or non-map argument: slots (must be {:slot-ident value ...} map)"
                      {:args args})))
    ;; Resolve the entity's class so we can coerce JSON-shaped slot
    ;; values into Datomic-shaped values via dt/range-of (slot map's
    ;; values from JSON arrive as strings; codec needs proper keyword /
    ;; instant / etc.).
    (let [entity-ident   (eref/resolve-ident entity-arg)
          entity-current (dt/find-by-ident entity-ident)
          class-ident    (dt/class-ident-of entity-current)
          slot-map       (coerce-slot-map class-ident slot-arg)
          updated        (dt/update-entity! entity-ident slot-map)]
      {:entity (str entity-ident)
       :slots  slot-map
       :result (entity-projection updated)})))

(defn- entity-validate-handler [args]
  (let [class-arg (or (get args "class") (get args :class))
        slots     (or (get args "slots") (get args :slots) {})]
    (when (nil? class-arg)
      (throw (ex-info "Missing required argument: class" {:args args})))
    (let [class-ident (eref/resolve-ident class-arg)
          props       (coerce-slot-map class-ident slots)
          errors      (dt/validate-data class-ident props)]
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
;; Tag-vocabulary operations — Stage 7.D of
;; decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
;;
;; Verbs:
;;   sandbar.ground <concept>                — compositional grounding workflow
;;   sandbar.tag.lookup <concept>            — tag-vocabulary primitive
;;   sandbar.tag.define <name> <slots>       — author new canonical tag
;;   sandbar.tag.audit                       — run sandbar.audit.tag/audit-all
;;   sandbar.tag.consolidate <from> <into>   — merge into canonical; preserve alt-label
;;   sandbar.tag.split <tag> <new>           — declare partition into narrower tags
;;   sandbar.tag.rename <old> <new>          — rename canonical; preserve hidden-label
;;   sandbar.tag.align <tag> <iri> <type>    — declare cross-vocabulary mapping
;;   sandbar.tag.harmonize                   — bulk harmonization report
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- tag-by-value
  "Resolve a tag's canonical :mm.tag/value string to its entity-map, or
   nil if no tag claims that value.  Uses Datomic's unique-identity index
   on :mm.tag/value (O(1))."
  [value]
  (when (and (string? value) (seq value))
    (d/entity (db/db) [:mm.tag/value value])))

(defn- string-contains-ci?
  "Case-insensitive substring containment.  nil-safe."
  [haystack needle]
  (and (string? haystack) (string? needle)
       (str/includes? (str/lower-case haystack) (str/lower-case needle))))

(defn- tag-summary
  "Project a tag entity-map to a JSON-friendly summary map carrying the
   canonical value + key documentation slots + broader/narrower context."
  [tag]
  (let [project-ref (fn [x] (some-> x :mm.tag/value))
        project-refs (fn [xs] (vec (keep project-ref xs)))]
    (cond-> {:value (:mm.tag/value tag)}
      (:db/ident tag)              (assoc :ident (str (:db/ident tag)))
      (seq (:mm.tag/alt-label tag))    (assoc :alt-label    (vec (:mm.tag/alt-label tag)))
      (seq (:mm.tag/hidden-label tag)) (assoc :hidden-label (vec (:mm.tag/hidden-label tag)))
      (:mm.tag/definition tag)     (assoc :definition (:mm.tag/definition tag))
      (:mm.tag/scope-note tag)     (assoc :scope-note (:mm.tag/scope-note tag))
      (:mm.tag/example tag)        (assoc :example    (:mm.tag/example tag))
      (:mm.tag/canonical? tag)     (assoc :canonical? (:mm.tag/canonical? tag))
      (:mm.tag/lifecycle-status tag) (assoc :lifecycle-status (:mm.tag/lifecycle-status tag))
      (seq (:mm.tag/broader-generic tag))    (assoc :broader-generic    (project-refs (:mm.tag/broader-generic tag)))
      (seq (:mm.tag/broader-instantial tag)) (assoc :broader-instantial (project-refs (:mm.tag/broader-instantial tag)))
      (seq (:mm.tag/broader-partitive tag))  (assoc :broader-partitive  (project-refs (:mm.tag/broader-partitive tag)))
      (seq (:mm.tag/related tag))            (assoc :related            (project-refs (:mm.tag/related tag))))))

(defn- tag-lookup-handler
  "Step 1 of the sandbar.ground compositional workflow: tag-vocabulary
   primitive.  Surfaces tags whose canonical-form / alt-label / hidden-label
   / definition / scope-note / example align with the query concept; returns
   ranked candidates with broader/narrower context.  When no canonical tag
   scores positive, reports the gap suggesting a sandbar.tag.define call.

   Uses `sandbar.search/search-bm25f` against `:mm/Tag` — leverages the
   `:dt/bm25f-weights` declaration shipped at Stage 7.A:
     :mm.tag/value         12.0
     :mm.tag/alt-label      8.0
     :mm.tag/definition     6.0
     :mm.tag/scope-note     4.0
     :mm.tag/hidden-label   2.0
     :mm.tag/example        1.0

   Note: search-bm25f operates over `:dt/type :mm/Tag` instances.  F#18
   anonymous-upsert entities (pre-Stage-7.A; lack :dt/type) won't appear
   in lookup results — they surface in `sandbar.tag.audit` as
   `:undefined-used` violations + migrate to canonical via Stage 8 M.2."
  [args]
  (let [concept (or (get args "concept") (get args :concept))
        limit   (or (get args "limit")   (get args :limit) 10)]
    (when (str/blank? (str concept))
      (throw (ex-info "Missing required argument: concept" {:args args})))
    (let [{:keys [hits total]}
          (try
            (search/search-bm25f {:query concept
                                  :class :mm/Tag
                                  :limit limit})
            (catch Exception e
              ;; Defensive — surface the gap rather than crash if BM25F
              ;; isn't ready (e.g., no :mm/Tag instances yet).
              {:hits [] :total 0 :error (.getMessage e)}))]
      {:concept     concept
       :matches     (vec (for [hit hits]
                           (assoc (tag-summary (:entity hit))
                                  :score (:score hit))))
       :match-total total
       :gap?        (zero? (count hits))
       :gap-hint    (when (zero? (count hits))
                      (str "No tag in the corpus aligns with \"" concept
                           "\".  Consider sandbar.tag.define :name \"" concept
                           "\" :slots {:definition \"...\" :scope-note \"...\"}.  "
                           "F#18 anonymous tags (if any) surface in sandbar.tag.audit's "
                           ":undefined-used invariant."))})))

(defn- tag-define-handler
  "Author a new canonical tag.  Per ADR §2.5 — `sandbar.tag.define` forces
   explicit definition before a tag can be applied; the :scope-note slot
   should be supplied to anchor the canonical boundary.  Errors if a tag
   with this :value already exists (use sandbar.tag.consolidate to merge
   into an existing canonical, or sandbar.tag.rename to change canonical)."
  [args]
  (let [value (or (get args "name") (get args :name))
        slots (or (get args "slots") (get args :slots) {})]
    (when (str/blank? (str value))
      (throw (ex-info "Missing required argument: name" {:args args})))
    (when (tag-by-value value)
      (throw (ex-info (str "Tag already exists with value: " value)
                      {:value value
                       :hint "Use sandbar.tag.consolidate to merge, or sandbar.tag.rename to change canonical."})))
    (let [coerced (coerce-slot-map :mm/Tag slots)
          props   (merge {:mm.tag/value value} coerced)
          new-ent (dt/make :mm/Tag props {})]
      (log/info :MCP/tag-define {:value value :entity-id (:db/id new-ent)})
      {:tag     (tag-summary new-ent)
       :created true})))

(defn- tag-audit-handler
  "Run the full tag-lifecycle audit (sandbar.audit.tag/audit-all).  No
   arguments — runs all 7 invariants."
  [_args]
  (audit-tag/audit-all))

(defn- tag-consolidate-handler
  "Merge :from tag INTO :into tag.  Effects:
   (1) :from's :value becomes a :mm.tag/alt-label on :into
   (2) :from is marked :mm.tag/lifecycle-status :superseded
   (3) :from gains :mm.tag/superseded-by ref to :into
   (4) Every :mm.memory/tags ref to :from is rewritten to :into

   Errors if either tag is missing."
  [args]
  (let [from-val (or (get args "from") (get args :from))
        into-val (or (get args "into") (get args :into))]
    (when (str/blank? (str from-val)) (throw (ex-info "Missing required argument: from" {:args args})))
    (when (str/blank? (str into-val)) (throw (ex-info "Missing required argument: into" {:args args})))
    (when (= from-val into-val) (throw (ex-info "from and into must differ" {:args args})))
    (let [from-ent (tag-by-value from-val)
          into-ent (tag-by-value into-val)]
      (when (nil? from-ent) (throw (ex-info (str "Tag not found: " from-val) {:value from-val})))
      (when (nil? into-ent) (throw (ex-info (str "Tag not found: " into-val) {:value into-val})))
      ;; Step (1)-(3): alt-label + lifecycle + supersede
      @(d/transact (db/conn)
                   [[:db/add (:db/id into-ent) :mm.tag/alt-label from-val]
                    [:db/add (:db/id from-ent) :mm.tag/lifecycle-status :superseded]
                    [:db/add (:db/id from-ent) :mm.tag/superseded-by    (:db/id into-ent)]])
      ;; Step (4): rewrite all :mm.memory/tags refs
      (let [memorials-with-from (d/q '[:find [?m ...]
                                       :in $ ?from
                                       :where [?m :mm.memory/tags ?from]]
                                     (db/db) (:db/id from-ent))
            rewrite-tx (vec (mapcat (fn [m]
                                      [[:db/retract m :mm.memory/tags (:db/id from-ent)]
                                       [:db/add     m :mm.memory/tags (:db/id into-ent)]])
                                    memorials-with-from))]
        (when (seq rewrite-tx)
          @(d/transact (db/conn) rewrite-tx))
        (log/info :MCP/tag-consolidate
                  {:from from-val :into into-val :memorials (count memorials-with-from)})
        {:from               from-val
         :into               into-val
         :memorials-rewritten (count memorials-with-from)
         :alt-label-added    from-val
         :lifecycle-status   :superseded}))))

(defn- tag-split-handler
  "Declare that :tag is being partitioned into multiple narrower tags
   :into-tags (vec of `{:value :scope-note}` maps).  Creates each new tag
   as :mm.tag/broader-generic :tag.  Does NOT auto-reroute existing
   memorial refs — editorial reassignment is a follow-on per the ADR
   (split surfaces the partition; memorial migration is per-memorial
   judgment)."
  [args]
  (let [tag-val   (or (get args "tag")        (get args :tag))
        into-tags (or (get args "into-tags")  (get args :into-tags))]
    (when (str/blank? (str tag-val))
      (throw (ex-info "Missing required argument: tag" {:args args})))
    (when (or (not (sequential? into-tags)) (< (count into-tags) 2))
      (throw (ex-info "Missing or invalid :into-tags — must be a vector of 2+ tag specs"
                      {:args args})))
    (let [parent-ent (tag-by-value tag-val)]
      (when (nil? parent-ent) (throw (ex-info (str "Tag not found: " tag-val) {:value tag-val})))
      (doseq [spec into-tags
              :let [v  (or (get spec "value") (get spec :value))]]
        (when (str/blank? (str v))
          (throw (ex-info "into-tags entry missing :value" {:spec spec})))
        (when (tag-by-value v)
          (throw (ex-info (str "into-tags entry already exists: " v) {:value v}))))
      (let [new-tx (vec (for [spec into-tags
                              :let [v  (or (get spec "value")      (get spec :value))
                                    sn (or (get spec "scope-note") (get spec :scope-note))]]
                          (cond-> {:mm.tag/value v
                                   :mm.tag/broader-generic (:db/id parent-ent)}
                            sn (assoc :mm.tag/scope-note sn))))]
        @(d/transact (db/conn) new-tx)
        (log/info :MCP/tag-split {:parent tag-val :into-tags (map :value new-tx)})
        {:parent      tag-val
         :into-tags   (mapv :mm.tag/value new-tx)
         :note        (str "Created " (count new-tx) " narrower tags under "
                           tag-val ".  Memorial refs NOT auto-rerouted — "
                           "editorial reassignment is per-memorial judgment.")}))))

(defn- tag-rename-handler
  "Change a tag's canonical :mm.tag/value from :old to :new.  Preserves
   :old as :mm.tag/hidden-label (search-recall path).  Refs by :db/id are
   unaffected.  Errors if :new is already taken."
  [args]
  (let [old-val (or (get args "old") (get args :old))
        new-val (or (get args "new") (get args :new))]
    (when (str/blank? (str old-val)) (throw (ex-info "Missing required argument: old" {:args args})))
    (when (str/blank? (str new-val)) (throw (ex-info "Missing required argument: new" {:args args})))
    (when (= old-val new-val) (throw (ex-info "old and new must differ" {:args args})))
    (let [old-ent (tag-by-value old-val)]
      (when (nil? old-ent) (throw (ex-info (str "Tag not found: " old-val) {:value old-val})))
      (when (tag-by-value new-val)
        (throw (ex-info (str "Tag with new value already exists: " new-val)
                        {:value new-val
                         :hint "Use sandbar.tag.consolidate to merge instead."})))
      ;; Atomic rename — single transaction targeting :db/id directly.
      ;; (Lookup-ref form `[:mm.tag/value old-val]` would fail because the
      ;; same tx also reassigns :mm.tag/value; but :db/id-direct assertions
      ;; don't depend on the lookup-ref resolving post-tx.)
      @(d/transact (db/conn)
                   [{:db/id              (:db/id old-ent)
                     :mm.tag/value       new-val
                     :mm.tag/hidden-label old-val}])
      (log/info :MCP/tag-rename {:old old-val :new new-val})
      {:old                    old-val
       :new                    new-val
       :hidden-label-preserved old-val})))

(defn- tag-align-handler
  "Declare a cross-vocabulary mapping from :tag to :external-iri under one
   of the SKOS mapping relations (:exact-match / :close-match /
   :broader-match / :narrower-match / :related-match).  Per ADR §2.1 Tier E.

   MVP: stores the external IRI as a :mm.tag/<mapping-type> ref to a
   :mm/Tag entity whose :mm.tag/value is the external IRI string.  A
   richer :mm/Vocabulary-aware federation backbone lands in Stage 8."
  [args]
  (let [tag-val      (or (get args "tag")          (get args :tag))
        external-iri (or (get args "external-iri") (get args :external-iri))
        mapping-type (or (get args "mapping-type") (get args :mapping-type) "exact-match")
        slot         (keyword "mm.tag" mapping-type)]
    (when (str/blank? (str tag-val)) (throw (ex-info "Missing required argument: tag" {:args args})))
    (when (str/blank? (str external-iri)) (throw (ex-info "Missing required argument: external-iri" {:args args})))
    (when-not (#{:mm.tag/exact-match :mm.tag/close-match :mm.tag/broader-match
                 :mm.tag/narrower-match :mm.tag/related-match} slot)
      (throw (ex-info (str "Invalid mapping-type: " mapping-type)
                      {:valid #{"exact-match" "close-match" "broader-match" "narrower-match" "related-match"}})))
    (let [tag-ent (tag-by-value tag-val)]
      (when (nil? tag-ent) (throw (ex-info (str "Tag not found: " tag-val) {:value tag-val})))
      ;; Upsert the external IRI as a :mm/Tag entity (via :mm.tag/value
      ;; unique identity) + create the mapping ref on the local tag.
      ;; Uses map-form transaction so Datomic resolves the nested upsert
      ;; before linking; :db/add with a bare upsert-map fails because
      ;; :db/add expects an already-resolvable entity reference.
      @(d/transact (db/conn)
                   [{:db/id (:db/id tag-ent)
                     slot   {:mm.tag/value external-iri}}])
      (log/info :MCP/tag-align {:tag tag-val :external-iri external-iri :mapping-type mapping-type})
      {:tag           tag-val
       :external-iri  external-iri
       :mapping-type  mapping-type
       :slot          (str slot)})))

(defn- tag-harmonize-handler
  "Bulk-harmonization report.  Runs the full audit + identifies auto-
   mergeable drift clusters (M.3 candidates).  MVP returns a DRY-RUN
   report — actual auto-merge requires explicit user invocation of
   sandbar.tag.consolidate per cluster.

   Per ADR §2.6 M.3 — silent-remap policy is configurable via a future
   :auto-apply? flag once Stage 8 migration begins; for now the verb is
   advisory."
  [_args]
  (let [report          (audit-tag/audit-all)
        drift-report    (->> (:invariants report)
                             (filter #(= :drift (:invariant %)))
                             first)
        drift-clusters  (:violations drift-report)]
    {:audit-report     report
     :drift-clusters   drift-clusters
     :drift-cluster-count (count drift-clusters)
     :auto-mergeable-count (->> drift-clusters
                                (filter #(= 2 (count (:variants %))))
                                count)
     :note (str "DRY-RUN.  Apply per-cluster consolidations via "
                "sandbar.tag.consolidate :from <variant> :into <canonical>.  "
                "Auto-apply policy (M.3 silent-remap) deferred to Stage 8.")}))

(defn- ground-handler
  "Compositional grounding workflow at sandbar level (NOT in tag namespace).
   Multi-step orchestration that composes tag-vocabulary examination +
   meta-vocabulary discovery + future BM25F/path-grammar integration.

   Stage 7.D MVP returns structured output the LLM consumer can use:
     :step-1-tag-lookup     — tag-vocabulary primitive (tag-lookup-handler)
     :step-2-meta-vocab     — class + predicate candidates aligned with concept
     :step-3-suggested-next — next actions the consumer might want

   Stage 7.F+ refinement: integrate sandbar.search.bm25f for fulltext +
   sandbar.navigate.path-via for typed-edge traversal.

   Per observations/grounding_is_compositional_mcp_workflow_thin_client_2026_05_20.md."
  [args]
  (let [concept (or (get args "concept") (get args :concept))]
    (when (str/blank? (str concept))
      (throw (ex-info "Missing required argument: concept" {:args args})))
    (let [tag-result    (tag-lookup-handler {"concept" concept "limit" 10})
          ;; Meta-vocab: classes whose ident-name or label contains the concept
          all-classes   (dt/all-classes)
          class-matches (->> all-classes
                             (filter (fn [c]
                                       (or (string-contains-ci? (name c) concept)
                                           (string-contains-ci? (some-> (db/entity c) :dt/label) concept))))
                             (mapv ->ident-str)
                             (sort))
          ;; Predicates whose ident-name contains the concept
          all-props     (dt/all-properties)
          pred-matches  (->> all-props
                             (filter (fn [p] (string-contains-ci? (name p) concept)))
                             (mapv ->ident-str)
                             (sort))]
      {:concept              concept
       :step-1-tag-lookup    tag-result
       :step-2-meta-vocab    {:classes-matching    class-matches
                              :predicates-matching pred-matches}
       :step-3-suggested-next
       (cond
         (:gap? tag-result)
         ["sandbar.tag.define — author the canonical tag with definition + scope-note"
          "sandbar.search.bm25f — try a fulltext sweep over corpus body content"]

         (seq (:matches tag-result))
         ["sandbar.tag.lookup — inspect specific candidate tags"
          "sandbar.search.bm25f — fulltext sweep informed by selected tag's scope-note"
          "sandbar.navigate.outbound :from <tag> — explore broader/narrower context"])
       :note "Stage 7.D MVP — full BM25F + path-grammar integration follows in 7.F."})))

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
  [;; Schema introspection — verbs follow the LLM-consumability discipline
   ;; per authorizations/sandbar_focus_for_0_1_0_release_plus_mcp_llm_consumability_discipline_2026_05_14.md
   {:name "sandbar.schema.classes"
    :title "List every class ident in the metamodel"
    :description "WHICH: returns the sorted vec of every `:dt/Class` instance ident (e.g. `:mm/Memory`, `:dt/Property`, `:auth/User`) registered in the metamodel.\n\nWHEN: use as the FIRST DISCOVERY CALL when an AI client needs to understand what kinds of entities the substrate manages.  Foundational for bootstrap-by-discovery — the catalog at `tools/list` is the verb surface; this verb is the class surface.  When NOT to use: (a) you only need ONE class's details — call `sandbar.class.describe` directly; (b) you need INSTANCES rather than class idents — `sandbar.class.instances` after picking a class.\n\nHOW: no arguments.  Returns `{:classes [<ident-string>...]}`.\n\nORDER: typical bootstrap sequence — `sandbar.schema.classes` (this verb; discover classes) → `sandbar.class.describe :class :foo/X` (inspect one) → `sandbar.class.instances :class :foo/X` (enumerate its entities).\n\nCOMBINATION: pairs with `sandbar.schema.properties` (parallel — property surface) and `sandbar.schema.datatypes` (the value-type surface beneath classes).  For a single-call batch alternative to multiple `sandbar.class.instances` invocations, use `sandbar.schema.entities` instead."
    :inputSchema no-args-schema
    :handler schema-classes-handler}
   {:name "sandbar.schema.properties"
    :title "List every property ident in the metamodel"
    :description "WHICH: returns the sorted vec of every `:dt/Property` instance ident (e.g. `:mm.memory/name`, `:dt/subclass-of`, `:auth/email`).  Every attribute that has been declared at the metamodel layer — both the substrate's own (`:dt/*`) and consumer-class declarations (`:mm.memory/*` etc.).\n\nWHEN: use to discover the PREDICATE vocabulary available for typed-edge navigation, structured queries, or schema introspection.  Foundational discovery call — companion to `sandbar.schema.classes`.  When NOT to use: (a) you want a SPECIFIC property's domain/range/cardinality — `sandbar.property.{domain,range,cardinality}`; (b) you want only the properties declared on ONE class — `sandbar.class.slots :class :foo/X` (returns inherited + direct); (c) you want properties whose domain IS a specific class — no direct verb; query via `sandbar.property.domain` over a candidate set.\n\nHOW: no arguments.  Returns `{:properties [<ident-string>...]}`.\n\nORDER: typical sequence — `sandbar.schema.properties` (discover) → `sandbar.property.range :property :foo/bar` (inspect one's value type) → `sandbar.property.domain :property :foo/bar` (its applicable class).\n\nCOMBINATION: pairs with `sandbar.schema.classes` (the class surface) and `sandbar.class.slots` (per-class subset).  Predicate-set values feed `sandbar.navigate.*` verbs (path-grammar / edges / walk) as the `:predicates` opt."
    :inputSchema no-args-schema
    :handler schema-properties-handler}
   {:name "sandbar.schema.entities"
    :title "Batch fetch entity-spec maps grouped by class (N+1 elimination)"
    :description "WHICH: returns entity-spec maps grouped by class — one substrate round-trip instead of N+1 separate `sandbar.class.instances` calls.  Default fetches every non-abstract class's instances; optional `:classes` filter restricts to a specified subset.\n\nWHEN: use when you need to enumerate the substrate's entire entity state (or a slice across multiple classes) in one call — e.g., for bulk export, schema visualization, full-corpus reporting.  This verb exists specifically to eliminate the N+1 round-trip cost of iterating over classes and calling `sandbar.class.instances` per class.  When NOT to use: (a) you only need one class's instances — call `sandbar.class.instances` directly (smaller payload); (b) you need ranked / filtered instances — use `sandbar.aggregate.rank-by` or `sandbar.search.bm25f` instead.\n\nHOW: `:classes` (optional) is a JSON array of class-ident strings.  If omitted, fetches all non-abstract classes.  Returns `{:by-class {<class-ident-string> [<entity-map>...] ...} :total-classes <int> :total-entities <int>}`.\n\nORDER: discover classes via `sandbar.schema.classes` first (if you don't already know them).  After this call, you have the full per-class entity inventory; downstream operations (per-entity inspection, projection, etc.) follow.\n\nCOMBINATION: alternative to N × `sandbar.class.instances`.  Composes with downstream filtering: take the result's `:by-class` map and apply consumer-side predicates.  For structured filtering at the substrate, use `sandbar.aggregate.count` / `.group-by` with a `:where` Datalog clause instead.  Per Stage G Signal 8 (corpus-side friction-discovery)."
    :inputSchema {:type "object"
                  :properties {:classes {:type "array"
                                          :items {:type "string"}
                                          :description "Optional array of class-ident strings to fetch; default fetches all non-abstract classes"}}
                  :required []}
    :handler schema-entities-handler}
   {:name "sandbar.schema.datatypes"
    :title "List every Datomic value type (`:db.type/*`) registered"
    :description "WHICH: returns the sorted vec of every `:db.type/*` value type registered in the metamodel — primitives (`:db.type/string`, `:db.type/long`, `:db.type/boolean`, `:db.type/instant`, `:db.type/keyword`, `:db.type/uuid`, `:db.type/uri`), refs (`:db.type/ref`), and Sandbar-specific extensions if present.\n\nWHEN: use when introspecting the SUBSTRATE layer — what primitive types can attributes carry?  Less commonly needed than `sandbar.schema.classes` (which inspects the user-facing class surface); useful for tooling that needs to understand the underlying type system.  When NOT to use: (a) you want a specific property's value type — `sandbar.property.range :property :foo/bar`; (b) you want consumer-class types — `sandbar.schema.classes`.\n\nHOW: no arguments.  Returns `{:datatypes [<ident-string>...]}`.\n\nORDER: no prerequisites; foundational discovery call.\n\nCOMBINATION: pairs with `sandbar.property.range` (which returns one of these datatype idents for a property).  Rarely needed at the AI-client level — most reads stay at the class / property layer."
    :inputSchema no-args-schema
    :handler schema-datatypes-handler}

   ;; Class introspection
   {:name "sandbar.class.describe"
    :title "Full class description — abstract? + parents + ancestors + subclasses + slots"
    :description "WHICH: returns the comprehensive descriptor for a class — its abstract-flag, direct parents, all ancestors, direct subclasses, and effective slot set.  The 'inspect this class' verb.\n\nWHEN: use after picking a class from `sandbar.schema.classes` to understand its full shape before creating instances or composing queries.  Most useful first-touch verb for a new-to-you class.  When NOT to use: (a) only the slot set is needed — `sandbar.class.slots` (lighter); (b) only the hierarchy is needed — `sandbar.class.hierarchy` (via the REST endpoint; not in MCP catalog) or compose `parents` + `subclasses`; (c) you want the INSTANCES — `sandbar.class.instances`.\n\nHOW: `:class` is the class ident string (e.g. `:mm/Memory`).  Returns `{:class <ident-string> :abstract? <bool> :parents [...] :ancestors [...] :subclasses [...] :slots [...]}`.\n\nORDER: typical sequence — `sandbar.schema.classes` (discover) → `sandbar.class.describe :class :foo/X` (this verb; inspect) → `sandbar.class.instances` or `sandbar.entity.create`.\n\nCOMBINATION: the slot list feeds `sandbar.property.{domain,range,cardinality}` for per-slot deep inspection.  Subclass list feeds polymorphic `sandbar.class.instances` calls.  Per-class typed-edge composition feeds `sandbar.orient.library-card` axis-specs."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-describe-handler}
   {:name "sandbar.class.slots"
    :title "Effective slot set of a class (inherited + directly-declared)"
    :description "WHICH: returns the sorted vec of slot idents that apply to instances of this class — including slots inherited from `:dt/subclass-of` ancestors PLUS slots declared directly on the class.\n\nWHEN: use to enumerate the FULL attribute surface available for instances of a class — for entity creation, validation, or query composition.  This is the 'what fields does this class have' question.  When NOT to use: (a) you want only the directly-declared slots (excluding inherited) — `sandbar.class.direct-slots`; (b) you want only REQUIRED slots — `sandbar.class.required-slots`; (c) you want to know which classes a property applies to — `sandbar.property.domain`.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`.\n\nORDER: foundational; no prerequisites beyond knowing the class ident (discover via `sandbar.schema.classes` if needed).\n\nCOMBINATION: feeds `sandbar.property.range` (per-slot value type for entity construction), `sandbar.entity.create` (slot map keys), and `sandbar.aggregate.group-by` (`:group-by` candidate slots).  For each slot's typed-edge nature (is it a `:db.type/ref` for navigation purposes?), call `sandbar.property.range`."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-slots-handler}
   {:name "sandbar.class.direct-slots"
    :title "Directly-declared slots only (no inheritance)"
    :description "WHICH: returns slots declared directly on `:class` — EXCLUDING inherited slots from `:dt/subclass-of` ancestors.\n\nWHEN: use when you need to know what THIS class adds beyond its parents — e.g., for class-evolution analysis, schema-shape comparison between siblings, or understanding the class's own contribution to the surface.  When NOT to use: (a) you want the full effective set (inherited + direct) — `sandbar.class.slots`; (b) you want just the required subset — `sandbar.class.required-slots`.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`.\n\nORDER: no prerequisites.\n\nCOMBINATION: subtract from `sandbar.class.slots` result to derive the INHERITED slots.  Use alongside `sandbar.class.parents` to understand how the class augments its ancestors."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-direct-slots-handler}
   {:name "sandbar.class.required-slots"
    :title "Required slots of a class (`:dt/required? true`)"
    :description "WHICH: returns the subset of effective slots flagged `:dt/required? true` on `:class`.  Required slots must be supplied when creating instances via `sandbar.entity.create`.\n\nWHEN: use as a PREREQUISITE check before calling `sandbar.entity.create` — to confirm the slot map contains every required key.  Also useful for documentation generation and error-message authoring (\"missing required slot X\").  When NOT to use: (a) you want all slots — `sandbar.class.slots`; (b) you want validation FEEDBACK after providing a slot map — `sandbar.entity.validate` (pre-transaction validation with error details).\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`.\n\nORDER: critical pre-step before `sandbar.entity.create`.  After this verb, you know the minimum slot set; gather values for those, then call create.\n\nCOMBINATION: pairs with `sandbar.entity.validate` (full pre-transaction validation including required-check AND type-conformance) and `sandbar.entity.create` (the actual mutation)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-required-slots-handler}
   {:name "sandbar.class.instances"
    :title "All instances of a class (incl. subclass instances)"
    :description "WHICH: returns every entity that is an instance of `:class` — directly OR via `:dt/subclass-of` (i.e., subclass instances are included; instance-of relation is transitive through inheritance).\n\nWHEN: use to enumerate a class's full instance population.  Foundational read for any class-based traversal.  When NOT to use: (a) the population is large and you only want top-K by some rank — `sandbar.aggregate.rank-by`; (b) you want only DIRECT instances (no subclass instances) — there's no MCP verb for this in the current catalog; substrate has `dt/direct-instances-of` accessible via in-process Clojure; (c) you want a count, not the entities — `sandbar.aggregate.count`; (d) you want to filter by some predicate — `sandbar.aggregate.count` / `.group-by` with `:where` Datalog, or `sandbar.search.bm25f` for fulltext-filtered instances.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :instances [<entity-map>...]}`.  Each entity-map has `:db/id`, `:db/ident` (if interned), and namespaced-keyword slots.\n\nORDER: typical sequence — `sandbar.schema.classes` (discover class) → `sandbar.class.describe` (inspect) → `sandbar.class.instances` (this verb; enumerate).  No strict prerequisites.\n\nCOMBINATION: pairs with `sandbar.aggregate.rank-by` (rank the enumerated set), `sandbar.aggregate.group-by` (faceted counts), `sandbar.search.bm25f` (fulltext-search within a class's instances).  For batch fetch across multiple classes, use `sandbar.schema.entities` (N+1 elimination) instead."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-instances-handler}
   {:name "sandbar.class.subclasses"
    :title "All transitive subclasses of a class"
    :description "WHICH: returns the sorted vec of every class that is a `:dt/subclass-of` descendant of `:class` (direct + transitive).\n\nWHEN: use to discover the polymorphic surface of a class — what concrete classes might satisfy 'instance of `:class`'?  E.g., subclasses of `:dt/Resource` is essentially every domain class.  When NOT to use: (a) you want only DIRECT subclasses — no MCP verb in current catalog (substrate has `dt/direct-subclasses-of`); (b) you want to test 'is X a subclass of Y' specifically — `sandbar.types.subclass-of` predicate.\n\nHOW: `:class` is the parent class ident.  Returns `{:class <ident-string> :subclasses [<ident-string>...]}`.\n\nORDER: no prerequisites.\n\nCOMBINATION: with `sandbar.class.instances` over each subclass for instance enumeration; with `sandbar.aggregate.group-by` (`:group-by :dt/subclass-of`) for hierarchy distribution analysis."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-subclasses-handler}
   {:name "sandbar.class.parents"
    :title "Direct parents + transitive ancestors of a class"
    :description "WHICH: returns the class's direct `:dt/subclass-of` parents AND the full transitive-ancestor list (walks up to `:dt/Resource` typically).\n\nWHEN: use to understand a class's inheritance lineage — what slots / behaviors does it inherit?  Especially useful when debugging unexpected attribute behavior (slot might be declared on an ancestor).  When NOT to use: (a) you only want the DIRECT parents — currently returned alongside ancestors in this verb's result (filter result-side); (b) you want a subclass-of predicate test — `sandbar.types.subclass-of`.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :parents [<direct-parents>...] :ancestors [<all-ancestors>...]}`.\n\nORDER: no prerequisites.\n\nCOMBINATION: pairs with `sandbar.class.direct-slots` per ancestor to trace inherited slot origins.  Use alongside `sandbar.types.subclass-of` for polymorphic-dispatch decisions."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-parents-handler}
   {:name "sandbar.class.validate-all-instances"
    :title "Run validation against every instance of a class; return the report"
    :description "WHICH: validates every entity that is an instance of `:class` (including subclass instances) against the class's declared slot constraints (required, range, custom validators).  Returns a per-entity validation report.\n\nWHEN: use for batch schema-conformance checking — e.g., after a schema change, before a migration, or for periodic substrate-health audits.  When NOT to use: (a) you want to validate ONE entity's proposed slot map without committing — `sandbar.entity.validate` (pre-transaction); (b) you want CANCELLABLE / long-running validation with workflow-backed history — `sandbar.validation.start` (the workflow-backed equivalent for large classes).\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :report <validation-report>}`.\n\nORDER: no prerequisites.  Synchronous — for large classes this can be slow; consider `sandbar.validation.start` for the workflow-backed equivalent.\n\nCOMBINATION: alternative to `sandbar.validation.start` (workflow-backed; better for large classes).  Pairs with `sandbar.entity.validate` (per-entity pre-transaction check) and `sandbar.entity.update` (after fixing failures, update affected entities)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-validate-all-instances-handler}

   ;; Type predicates
   {:name "sandbar.types.instance-of"
    :title "Predicate — is `:entity` an instance of `:class`?"
    :description "WHICH: returns boolean true if `:entity` is an instance of `:class` (directly via `:dt/type` OR transitively via `:dt/subclass-of` to an ancestor that's `:dt/type :class`).\n\nWHEN: use for dispatch / branching decisions in consumer code — 'if this entity is a :mm/Memory, handle it as a memory; else handle generically'.  When NOT to use: (a) you want the entity's actual class — `sandbar.entity.find` then read `:dt/type`; (b) you want ALL instances of a class — `sandbar.class.instances`; (c) you want polymorphic instance enumeration — `sandbar.class.instances` already includes subclass instances.\n\nHOW: `:class` is the candidate class ident; `:entity` is the entity ident or eid.  Returns `{:class :entity :instance-of? <bool>}`.\n\nORDER: no prerequisites; predicate-form leaf call.\n\nCOMBINATION: pairs with `sandbar.types.subclass-of` (class-level analog: is X a subclass of Y).  For filtering a candidate-set by instance-of relation, use `sandbar.aggregate.count` / `.group-by` with `:where '[[?e :dt/type :foo/X]]'` Datalog clause."
    :inputSchema (one-required
                   (merge class-arg-schema
                          {:entity {:type "string" :description "Entity ident or eid"}})
                   [:class :entity])
    :handler types-instance-of-handler}
   {:name "sandbar.types.subclass-of"
    :title "Predicate — is `:child` a (transitive) subclass of `:parent`?"
    :description "WHICH: returns boolean true if `:child` class is a transitive `:dt/subclass-of` descendant of `:parent` class — direct OR through any chain of ancestors.\n\nWHEN: use for class-hierarchy dispatch logic — 'if this class extends :auth/User, apply auth-flavored behavior'.  Companion to `sandbar.types.instance-of` (entity-level) — this is the class-level equivalent.  When NOT to use: (a) you want the full ancestor list — `sandbar.class.parents`; (b) you want all subclasses — `sandbar.class.subclasses`.\n\nHOW: `:parent` + `:child` are class ident strings.  Returns `{:parent :child :subclass-of? <bool>}`.\n\nORDER: no prerequisites.\n\nCOMBINATION: pairs with `sandbar.types.instance-of` (entity-of-class flavor)."
    :inputSchema (one-required
                   {:parent {:type "string" :description "Parent class ident"}
                    :child  {:type "string" :description "Child class ident"}}
                   [:parent :child])
    :handler types-subclass-of-handler}

   ;; Property introspection
   {:name "sandbar.property.domain"
    :title "Domain class of a property (`:dt/domain`)"
    :description "WHICH: returns the declared domain class of a property — the class whose instances may carry this attribute (per RDFS / KL-ONE semantics).  For SPARQL-fluent readers: the `rdfs:domain` analogue.\n\nWHEN: use to discover which class a property applies to — useful when authoring `:where` clauses (you need to know which entity type carries the slot) or when validating that a slot map's keys are appropriate for a target class.  When NOT to use: (a) you want the VALUE type of the property — `sandbar.property.range`; (b) you want every slot on a class — `sandbar.class.slots`.\n\nHOW: `:property` is the attribute ident.  Returns `{:property <ident-string> :domain <class-ident-string-or-nil>}`.  Returns nil if no domain declared.\n\nORDER: no prerequisites; foundational property-introspection call.\n\nCOMBINATION: triad with `sandbar.property.range` + `sandbar.property.cardinality` — all three together describe the property's shape.  Domain + range together let you reason about a typed-edge: 'edges of predicate :p go from class :D to class :R'."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-domain-handler}
   {:name "sandbar.property.range"
    :title "Value-type range of a property (`:dt/range` / `:db/valueType`)"
    :description "WHICH: returns the value-type range of a property — what kind of value the property holds.  For `:db.type/ref` properties, the range is a class ident (the target's class).  For primitive properties, the range is a `:db.type/*` keyword (`:db.type/string`, `:db.type/long`, etc.).\n\nWHEN: use when constructing slot maps for `sandbar.entity.create` (you need to know what type each slot expects) or when authoring path-grammar expressions (typed-edge predicates have `:db.type/ref` range; primitive-valued slots don't).  When NOT to use: (a) you want which class CARRIES the property — `sandbar.property.domain`; (b) you want every value type registered — `sandbar.schema.datatypes`.\n\nHOW: `:property` is the attribute ident.  Returns `{:property <ident-string> :range <class-or-datatype-ident-string>}`.\n\nORDER: no prerequisites.  Critical pre-step for `sandbar.entity.create` slot construction.\n\nCOMBINATION: triad with `sandbar.property.domain` + `.cardinality`.  For ref-typed properties, the range class can be inspected via `sandbar.class.describe`.  Identifies which properties are typed-edges (range is a class) vs primitive-valued (range is `:db.type/*`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-range-handler}
   {:name "sandbar.property.cardinality"
    :title "Cardinality of a property (`:db.cardinality/one` or `/many`)"
    :description "WHICH: returns the cardinality of a property — either `:db.cardinality/one` (scalar; at most one value per entity) or `:db.cardinality/many` (set-valued; multiple values per entity).\n\nWHEN: use when constructing slot maps — `:cardinality/many` slots accept vec / set; `:cardinality/one` slots accept the scalar value directly.  Critical for `sandbar.entity.create` slot construction and for authoring `:where` Datalog clauses that traverse multi-cardinality attributes.  When NOT to use: (a) you want the value type — `sandbar.property.range`; (b) you want the domain — `sandbar.property.domain`.\n\nHOW: `:property` is the attribute ident.  Returns `{:property <ident-string> :cardinality <ident-string>}`.\n\nORDER: no prerequisites.\n\nCOMBINATION: triad with `sandbar.property.domain` + `.range`.  Many-cardinality ref properties (e.g., `:mm.memory/tags`) are natural targets for navigation verbs (`sandbar.navigate.outbound` with `:predicates [:mm.memory/tags]`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-cardinality-handler}

   ;; Entity operations
   {:name "sandbar.entity.create"
    :title "Create a new validated entity of a class (with optional codec parsing)"
    :description "WHICH: creates a new entity of `:class` from a slot map (or from a raw wire-format source via the codec mediator), validates it against the class's declared constraints, and transacts it into the substrate.  The primary mutation verb.\n\nWHEN: use to bring a new entity into the substrate — whether constructing from explicit slot values (programmatic) or from a raw representation (e.g., markdown source for `:mm/Memory`, JSON for any class).  When NOT to use: (a) updating an EXISTING entity — `sandbar.entity.update`; (b) you want to validate without committing — `sandbar.entity.validate` (pre-transaction); (c) entity already exists and you want to read it back — `sandbar.entity.find`.\n\nHOW: `:class` is the target class ident (REQUIRED; abstract classes rejected).  `:slots` is a slot-ident-string → value map (optional if `:source` is provided).  `:format` + `:source` (both optional, must come together) invoke the codec mediator: `:source` is parsed as the named wire format (e.g., `:markdown`), parsed slots merge with explicit `:slots` (explicit wins on conflict).  Validates required slots, type-conformance, custom validators before transacting; raises ex-info on validation failure.\n\nORDER: prerequisites — discover `:class` via `sandbar.schema.classes`; understand required slots via `sandbar.class.required-slots`; understand slot value types via `sandbar.property.range`.  Optional pre-check: `sandbar.entity.validate` (validates a slot map WITHOUT committing).\n\nCOMBINATION: paired with `sandbar.entity.validate` (pre-check), `sandbar.entity.find` (read back), `sandbar.entity.update` (subsequent mutations).  For codec-driven creation, ensure the codec is registered via `sandbar.codec.list`.  Per codec arc Stage F.3a of plans/sandbar_codec_layer_arc_2026-05-12.md."
    :inputSchema (one-required
                   {:class  {:type "string" :description "Class ident (concrete, not abstract)"}
                    :slots  {:type "object" :description "Slot map (slot-ident-string → value); optional when :source is provided"}
                    :format {:type "string" :description "Optional codec format keyword (e.g., :markdown / :json); requires :source"}
                    :source {:type "string" :description "Optional raw native-representation string parsed via :format codec"}}
                   [:class])
    :handler entity-create-handler}
   {:name "sandbar.entity.find"
    :title "Look up an entity by ident or eid"
    :description "WHICH: looks up an entity by `:ident` (interned keyword) or `:id` (numeric eid).  Returns the entity-map projection (`:db/id`, `:db/ident` if interned, namespaced-keyword slots).\n\nWHEN: use to fetch the current state of a known entity.  Most common 'read one entity' verb.  When NOT to use: (a) you want all instances of a class — `sandbar.class.instances`; (b) you want fulltext search — `sandbar.search.bm25f`; (c) you don't know the ident — discover via `sandbar.class.instances` first.\n\nHOW: provide ONE of `:ident` (keyword-form string like `\":decisions/foo\"`) OR `:id` (numeric eid).  Returns `{:entity <entity-map>}` if found, or `{:entity nil :missing? true :lookup <provided>}` if not found.\n\nORDER: leaf-call; no prerequisites.\n\nCOMBINATION: pre-step before `sandbar.entity.update` (confirm the entity exists); after `sandbar.entity.create` (read back the created entity, though create returns the entity directly so this is rarely needed).  For RELATED entities, use `sandbar.navigate.{outbound,inbound,siblings-of}` or `sandbar.orient.library-card`."
    :inputSchema {:type "object"
                  :properties {:ident {:type "string" :description "Entity ident (keyword string)"}
                               :id    {:type "integer" :description "Entity eid (numeric)"}}
                  :required []}
    :handler entity-find-handler}
   {:name "sandbar.entity.update"
    :title "Update slots on an existing entity"
    :description "WHICH: applies slot-value updates to an existing entity.  Validates the updated slot map against the entity's class constraints before transacting.\n\nWHEN: use to MUTATE an existing entity — change a slot value, set a previously-empty slot, etc.  When NOT to use: (a) creating a new entity — `sandbar.entity.create`; (b) you want to validate proposed updates WITHOUT committing — `sandbar.entity.validate` (against the class with the merged slot map); (c) you want to retract a slot value entirely — Datomic retraction is a separate concern not currently exposed via MCP.\n\nHOW: `:entity` is the target entity ident or eid (REQUIRED).  `:slots` is a slot-ident-string → new-value map (REQUIRED; non-map values rejected).  Substrate auto-coerces JSON-shaped values via `dt/range-of` (e.g., `:db.type/keyword` slots accept either keyword strings or already-coerced keywords).  Cardinality-many slots accept either a single value (wrapped to vec) or a vec / array.\n\nORDER: prerequisite — `sandbar.entity.find` to confirm the entity exists.  Optional pre-check: `sandbar.entity.validate` against the FULL merged slot map (current slots ∪ updates).\n\nCOMBINATION: pairs with `sandbar.entity.find` (pre-confirm + post-read-back).  For bulk class-wide updates, no single-call alternative; iterate `sandbar.class.instances` and apply per-entity.  Per Stage I of plans/sandbar_codex_review_remediation_arc_2026_05_13.md (`dt/update-entity!` substrate primitive)."
    :inputSchema (one-required
                   {:entity {:type "string" :description "Entity ident (keyword string) or eid (numeric)"}
                    :slots  {:type "object" :description "Slot-ident-string → new-value map"}}
                   [:entity :slots])
    :handler entity-update-handler}
   {:name "sandbar.entity.validate"
    :title "Pre-transaction validation of a slot map against a class"
    :description "WHICH: checks that `:slots` would constitute a valid instance of `:class` — required-slot presence, range-conformance, custom-validator pass — WITHOUT transacting.  Returns the validation report (errors, if any) without side effect.\n\nWHEN: use as a PRE-FLIGHT CHECK before `sandbar.entity.create` (especially when constructing from external input where validation feedback drives consumer-side error messages).  Also useful for `sandbar.entity.update` proposals (validate the merged slot map before committing).  When NOT to use: (a) you want to CREATE the entity once valid — `sandbar.entity.create` (which validates internally); (b) you want to validate ALL existing instances of a class — `sandbar.class.validate-all-instances` (or `sandbar.validation.start` for workflow-backed).\n\nHOW: `:class` is the target class ident.  `:slots` is the slot-ident-string → value map to validate.  Returns `{:valid? <bool> :errors <error-detail-or-nil>}`.\n\nORDER: typical use — `sandbar.class.required-slots` (discover requirements) → `sandbar.entity.validate` (pre-check) → `sandbar.entity.create` (commit).\n\nCOMBINATION: pairs with `sandbar.entity.create` (the actual mutation; uses the same validation under the hood) and `sandbar.class.validate-all-instances` (sibling read-only verb at the class population level)."
    :inputSchema (one-required
                   {:class {:type "string" :description "Class ident"}
                    :slots {:type "object" :description "Slot map to validate"}}
                   [:class :slots])
    :handler entity-validate-handler}

   ;; Workflow operations — sandbar's first-class state-machine substrate
   {:name "sandbar.workflow.define"
    :title "Register a new workflow definition (states + transitions)"
    :description "WHICH: registers a new workflow definition from a spec.  A workflow is a named state machine — states (with terminal-kind classification: `:success` / `:failure` / `:cancel` for terminal states) + transitions (named actions moving between states, optionally guarded).  Per Sandbar's first-class-workflow substrate.\n\nWHEN: use to introduce a new state-machine model — order fulfillment, validation flow, approval pipeline, etc.  Workflows are entities in the substrate (queryable, evolvable).  When NOT to use: (a) inspecting an existing workflow — `sandbar.workflow.find`; (b) starting a process on an existing workflow — `sandbar.workflow.start-process`.\n\nHOW: `:spec` is a JSON object describing the workflow shape — `:workflow/states` vec with `:db/ident` + `:workflow/terminal-kind` (for terminals); `:workflow/transitions` vec with `:db/ident` + source/target state refs + optional guard.\n\nORDER: PRECEDES any `sandbar.workflow.start-process` for this workflow — the workflow must exist before processes can run.  Inspect existing workflows via `sandbar.workflow.find` to avoid duplicate idents.\n\nCOMBINATION: pairs with `sandbar.workflow.find` (lookup), `sandbar.workflow.start-process` (instantiate process), and the validation-service verbs (`sandbar.validation.*`) which are workflow-backed.  Workflows are visible as `:workflow/Definition` instances via `sandbar.class.instances :class :workflow/Definition`."
    :inputSchema (one-required {:spec {:type "object" :description "Workflow spec (states + transitions)"}} [:spec])
    :handler workflow-define-handler}
   {:name "sandbar.workflow.find"
    :title "Look up a workflow definition by ident"
    :description "WHICH: returns the entity-map of a workflow definition (its states + transitions + metadata) given the workflow ident.\n\nWHEN: use to inspect an existing workflow — discover its state-machine shape before starting a process or analyzing process histories.  When NOT to use: (a) you want all workflows — `sandbar.class.instances :class :workflow/Definition`; (b) you want process-state inspection — `sandbar.workflow.process-state`.\n\nHOW: `:workflow` is the workflow ident string.  Returns `{:workflow <ident-string> :definition <entity-map>}`.\n\nORDER: typical sequence — `sandbar.class.instances :class :workflow/Definition` (discover) → `sandbar.workflow.find :workflow :foo/wf` (inspect).\n\nCOMBINATION: pairs with `sandbar.workflow.start-process` (start a new process against this definition) and `sandbar.workflow.active-processes` (current processes against this workflow)."
    :inputSchema (one-required {:workflow {:type "string"}} [:workflow])
    :handler workflow-find-handler}
   {:name "sandbar.workflow.start-process"
    :title "Start a new workflow process attached to a subject entity"
    :description "WHICH: instantiates a new workflow process — a running instance of a workflow definition — attached to a subject entity (the entity the workflow operates on).  Returns the new process id.\n\nWHEN: use to BEGIN a state-machine flow against a target entity — e.g., start an order-fulfillment workflow for a `:order/Order`, start a validation workflow for a `:dt/Class` instance set.  When NOT to use: (a) the workflow definition doesn't exist yet — `sandbar.workflow.define` first; (b) you want to transition an EXISTING process — `sandbar.workflow.transition`.\n\nHOW: `:workflow` is the workflow definition ident (REQUIRED).  `:subject` is the subject entity ident or eid (REQUIRED).  `:data` is an optional initial process-data object (kwarg-shaped; merged into the process's initial state).\n\nORDER: PREREQUISITE — workflow defined (via `sandbar.workflow.define` or pre-seed).  After this verb, the process is in its initial state; advance via `sandbar.workflow.transition`.\n\nCOMBINATION: pairs with `sandbar.workflow.transition` (advance state), `sandbar.workflow.process-state` (current state read), `sandbar.workflow.process-history` (transition log).  MCP Tasks (long-running operations) are workflow processes — task-id IS process-id."
    :inputSchema (one-required
                   {:workflow {:type "string" :description "Workflow definition ident"}
                    :subject  {:type "string" :description "Subject entity ident or eid"}
                    :data     {:type "object" :description "Initial process data (kwargs-merged into initial state)"}}
                   [:workflow :subject])
    :handler workflow-start-process-handler}
   {:name "sandbar.workflow.transition"
    :title "Apply a named transition to a workflow process"
    :description "WHICH: advances a workflow process by applying a named transition — moves the process from its current state to the transition's target state (subject to guard validation).  Returns the new state + terminal flag.\n\nWHEN: use to advance a process through its state machine — invoke a transition by name.  When NOT to use: (a) just reading the current state — `sandbar.workflow.process-state`; (b) starting a process — `sandbar.workflow.start-process`; (c) cancelling — there's no separate cancel verb; transitions whose target state has `:workflow/terminal-kind :cancel` are the cancellation path.\n\nHOW: `:process-id` (REQUIRED) is the numeric eid of the process.  `:transition` is the transition ident (REQUIRED).  `:reason` (optional) is a human-readable rationale carried in the history.\n\nORDER: PREREQUISITE — process started via `sandbar.workflow.start-process`.  Discover the available transitions from the current state via `sandbar.workflow.process-state` + workflow-definition inspection.\n\nCOMBINATION: pairs with `sandbar.workflow.process-state` (current state read) + `sandbar.workflow.process-history` (history after transitions).  For validation flows specifically, the validation-service verbs (`sandbar.validation.run` etc.) are workflow-backed and call this internally."
    :inputSchema (one-required
                   {:process-id {:type "integer" :description "Process eid"}
                    :transition {:type "string" :description "Transition ident"}
                    :reason     {:type "string" :description "Optional human-readable reason (carried in history)"}}
                   [:process-id :transition])
    :handler workflow-transition-handler}
   {:name "sandbar.workflow.process-state"
    :title "Current state + terminal flag + completion flag of a workflow process"
    :description "WHICH: returns the current-state ident, terminal-flag (is this a terminal state?), and completion-flag (did this process reach a `:success` terminal?) of a workflow process.\n\nWHEN: use to read the live state of a process — for status displays, conditional logic, post-completion handling.  When NOT to use: (a) you want the full transition history — `sandbar.workflow.process-history`; (b) you want to ADVANCE the state — `sandbar.workflow.transition`.\n\nHOW: `:process-id` is the numeric process eid.  Returns `{:process-id :state :terminal? :completed?}`.\n\nORDER: leaf-call; no prerequisites beyond knowing the process-id (from `start-process` return or from `sandbar.workflow.active-processes` enumeration).\n\nCOMBINATION: pairs with `sandbar.workflow.transition` (call after a transition to confirm new state); `sandbar.workflow.process-history` (full log of how we got here)."
    :inputSchema (one-required {:process-id {:type "integer" :description "Process eid"}} [:process-id])
    :handler workflow-process-state-handler}
   {:name "sandbar.workflow.process-history"
    :title "Full transition history of a workflow process"
    :description "WHICH: returns the chronological transition history of a workflow process — every state transition that's been applied, with timestamps, transition idents, and any `:reason` notes.\n\nWHEN: use for audit logging, debugging unexpected process states, or rendering a process timeline for UI.  When NOT to use: (a) you only need the CURRENT state — `sandbar.workflow.process-state` (lighter); (b) you want active processes across a workflow — `sandbar.workflow.active-processes`.\n\nHOW: `:process-id` is the numeric process eid.  Returns `{:process-id :history [<transition-record>...]}`.\n\nORDER: leaf-call.\n\nCOMBINATION: pairs with `sandbar.workflow.process-state` (current snapshot)."
    :inputSchema (one-required {:process-id {:type "integer" :description "Process eid"}} [:process-id])
    :handler workflow-process-history-handler}
   {:name "sandbar.workflow.active-processes"
    :title "All active (non-terminal) workflow processes; optionally filtered by workflow"
    :description "WHICH: returns the list of currently-active (non-terminal-state) workflow processes — every process that's currently running.  Optional `:workflow` filter restricts to processes against a specific workflow definition.\n\nWHEN: use to enumerate live state-machine flows — dashboards, oncall views, 'what's currently in flight'.  When NOT to use: (a) one specific process — `sandbar.workflow.process-state`; (b) finished processes — query via `sandbar.class.instances :class :workflow/Process` + filter terminal states.\n\nHOW: `:workflow` (optional) restricts to one workflow definition's processes.  Without it, returns active processes across ALL workflows.  Returns `{:workflow <ident-or-nil> :processes [<process-entity-map>...]}`.\n\nORDER: leaf-call.\n\nCOMBINATION: pairs with `sandbar.workflow.process-state` (drill into one) + `sandbar.workflow.transition` (advance one)."
    :inputSchema {:type "object"
                  :properties {:workflow {:type "string" :description "Optional workflow ident to filter by"}}
                  :required []}
    :handler workflow-active-processes-handler}

   ;; Validation service — workflow-backed long-running validation
   {:name "sandbar.validation.start"
    :title "Start a workflow-backed validation run against all instances of a class"
    :description "WHICH: begins a validation workflow — a tracked, cancellable, long-running process that validates every instance of `:class` against its declared constraints.  Returns a validation-id (workflow process eid) used to manage the run.\n\nWHEN: use for LARGE class populations where synchronous validation (`sandbar.class.validate-all-instances`) would block too long or where cancellation / history is needed.  Workflow-backed: cancellable mid-run, retriable on failure, history preserved.  When NOT to use: (a) small class population — `sandbar.class.validate-all-instances` is synchronous and simpler; (b) single-entity proposed-slot-map check — `sandbar.entity.validate`.\n\nHOW: `:class` is the target class ident.  Returns `{:validation <process-entity>}` with the eid in `:db/id`.\n\nORDER: typical sequence — `sandbar.validation.start` (this verb; create + queue) → `sandbar.validation.run :validation-id <eid>` (execute) → `sandbar.validation.results :validation-id <eid>` (read result).  Mid-flight: `sandbar.validation.cancel` to abort.\n\nCOMBINATION: pairs with `sandbar.validation.run` (execute), `.cancel` (abort), `.retry` (re-run on failure), `.results` (read), `.history` (recent runs)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler validation-start-handler}
   {:name "sandbar.validation.run"
    :title "Execute a previously-started (queued) validation run"
    :description "WHICH: executes a validation workflow process that was previously queued via `sandbar.validation.start`.  Advances the process through its state machine (queued → running → terminal).\n\nWHEN: use after `sandbar.validation.start` to actually run the queued validation.  The start-then-run two-step lets consumers create-and-queue many validations and execute them later (rate-limiting, scheduling, batch sequencing).  When NOT to use: (a) you haven't created the validation yet — `sandbar.validation.start` first.\n\nHOW: `:validation-id` is the numeric process eid returned by `start`.  Returns `{:result <validation-report>}`.\n\nORDER: PREREQUISITE — `sandbar.validation.start` to obtain the validation-id.\n\nCOMBINATION: pairs with `.cancel` (abort mid-run) + `.results` (post-run report read)."
    :inputSchema (one-required {:validation-id {:type "integer" :description "Validation process eid (from .start return)"}} [:validation-id])
    :handler validation-run-handler}
   {:name "sandbar.validation.cancel"
    :title "Cancel an in-flight validation run"
    :description "WHICH: cancels a running validation workflow — transitions the process to a `:workflow/terminal-kind :cancel` terminal state.\n\nWHEN: use to abort a long-running validation that's no longer needed or that's running against stale data.  When NOT to use: (a) the validation already finished — no-op (or rejected); (b) you want to RETRY after failure — `sandbar.validation.retry`.\n\nHOW: `:validation-id` is the numeric process eid.  Returns `{:cancelled <result>}`.\n\nORDER: only meaningful for running processes (use `sandbar.workflow.process-state` to confirm state before cancelling).\n\nCOMBINATION: pairs with `sandbar.workflow.process-state` (confirm in-flight) and `sandbar.validation.history` (audit cancelled runs).  Cancellation is workflow-substrate-defined per `decisions/sandbar_workflow_cancellation_modeled_as_terminal_kind_on_states_2026_05_12.md`."
    :inputSchema (one-required {:validation-id {:type "integer" :description "Validation process eid"}} [:validation-id])
    :handler validation-cancel-handler}
   {:name "sandbar.validation.retry"
    :title "Re-run a previously-failed validation"
    :description "WHICH: re-executes a validation workflow that previously reached a `:failure` terminal state.  Useful when the failure was due to transient causes (e.g., stale instances now corrected).\n\nWHEN: use after a validation failed and you want to re-run against the (presumably now-valid) instance set.  When NOT to use: (a) the original run succeeded — no-op; (b) you want a FRESH validation — `sandbar.validation.start` (creates a new run).\n\nHOW: `:validation-id` is the numeric process eid of the failed run.  Returns `{:retried <result>}`.\n\nORDER: prerequisite — the validation must be in a failed terminal state.\n\nCOMBINATION: pairs with `.results` (compare retry results to original failure) and `.history` (audit retry chains)."
    :inputSchema (one-required {:validation-id {:type "integer" :description "Validation process eid (must be in failed terminal state)"}} [:validation-id])
    :handler validation-retry-handler}
   {:name "sandbar.validation.results"
    :title "Fetch the report from a completed validation run"
    :description "WHICH: returns the validation report for a completed run — per-entity validation outcomes (valid / errors).\n\nWHEN: use to read the result of a `sandbar.validation.run` after it completes (or to check on a still-running process — partial results may be available).  When NOT to use: (a) you want the high-level state only — `sandbar.workflow.process-state`; (b) you want history of MULTIPLE runs — `sandbar.validation.history`.\n\nHOW: `:validation-id` is the numeric process eid.  Returns `{:results <report>}`.\n\nORDER: post-`sandbar.validation.run`.  Calling on an unfinished process returns partial results.\n\nCOMBINATION: pairs with `sandbar.entity.update` (after reading errors, fix and update affected entities)."
    :inputSchema (one-required {:validation-id {:type "integer" :description "Validation process eid"}} [:validation-id])
    :handler validation-results-handler}
   {:name "sandbar.validation.history"
    :title "Recent validation runs (all classes or filtered)"
    :description "WHICH: returns recent validation workflow runs — class, start time, status, terminal-kind.  Optional `:class` filter restricts to one class's history.\n\nWHEN: use for substrate-health audits — has class X been validated recently?  Were there failures?  When NOT to use: (a) you want one specific run's results — `sandbar.validation.results`; (b) you want all active runs across the substrate — `sandbar.workflow.active-processes :workflow :validation/Workflow` (workflow-substrate query).\n\nHOW: `:class` (optional) is the class-ident filter.  Returns `{:class <ident-or-nil> :history [<run-record>...]}`.\n\nORDER: leaf-call.\n\nCOMBINATION: pairs with `sandbar.validation.results` (drill into one) and `sandbar.workflow.process-history` (full transition log for one run)."
    :inputSchema {:type "object"
                  :properties (merge class-arg-schema {})
                  :required []}
    :handler validation-history-handler}

   ;; Codec + projection operations (Stage F.3b)
   {:name "sandbar.codec.list"
    :title "List registered codecs (wire-format mediator inventory)"
    :description "WHICH: returns the set of codecs currently registered with the Sandbar codec mediator — each codec entry has a format keyword (e.g. `:codec/markdown`), supported MIME types, and (optionally) the classes it supports.\n\nWHEN: use to discover what wire formats Sandbar can parse / emit.  Foundational for codec-driven entity construction (`sandbar.entity.create` with `:format` + `:source`) and for projection/ingestion (`sandbar.project.export` / `.import` choose codecs per class's `:dt/native-codec`).  When NOT to use: (a) you want a specific class's declared native codec — `sandbar.class.describe` and read `:dt/native-codec`; (b) you want to register a NEW codec — not exposed via MCP; programmatic Clojure call against `sandbar.codec`.\n\nHOW: no arguments.  Returns `{:codecs [<codec-info>...]}`.\n\nORDER: foundational discovery.\n\nCOMBINATION: pairs with `sandbar.entity.create` (use `:format` + `:source` opts with one of the listed codec keywords) and `sandbar.project.export` / `.import` (codecs underpin the bidirectional projection).  Per codec arc Stage F.3b."
    :inputSchema {:type "object" :properties {} :required []}
    :handler codec-list-handler}
   {:name "sandbar.project.export"
    :title "Project entities from DB to a filesystem hierarchy via native-format codecs"
    :description "WHICH: projects entities from the Datomic substrate to a filesystem hierarchy under `:to` — each entity emits as a file in its class's `:dt/native-codec` format.  The bidirectional half of the Anderson `de.setf.rdf:project-graph` boundary-layer primitive (see `doc/concepts/projection.md`).  Bidirectionally inverse of `sandbar.project.import`.\n\nWHEN: use to materialize the current substrate state as a filesystem hierarchy — for backup, git versioning, manual editing, or hybrid FS/DB experimentation.  The filesystem format is the CANONICAL ground-truth; any backend must comply with it.  When NOT to use: (a) you want a single entity's representation — `sandbar.entity.find` returns the entity-map directly; (b) you want a subset — use `:filter` opt; (c) you want to read FROM filesystem — `sandbar.project.import`.\n\nHOW: `:to` is the output directory path (REQUIRED).  `:filter` (optional) restricts which entities project; keys: `:class` (single class-ident — only that class's instances), `:classes` (array — multiple classes), `:tree-filter` (string — rel-path prefix restriction).  Returns `{:to :filter :exported <count> :files [<rel-path>...]}`.\n\nORDER: idempotent; safe to run repeatedly (overwrites).  For round-trip verification, follow with `sandbar.project.import` against the output directory and compare results.\n\nCOMBINATION: inverse of `sandbar.project.import`.  For hybrid-backend experimentation, use `:filter` to project subsets selectively (per `ideas/sandbar_project_export_filtering_for_hybrid_backend_experimentation_2026_05_13.md`).  Codec selection driven by `sandbar.class.describe` `:dt/native-codec` per class."
    :inputSchema (one-required
                   {:to     {:type "string" :description "Output directory path"}
                    :filter {:type "object"
                              :description "Optional filter spec: {:class :mm/Memory, :classes [..], :tree-filter \"decisions/\"}"}}
                   [:to])
    :handler project-export-handler}
   {:name "sandbar.project.import"
    :title "Ingest entities from a filesystem hierarchy (inverse of project.export)"
    :description "WHICH: walks the `:from` directory, parses each file via the appropriate codec (per file extension / declared format), and returns the parsed entity-spec maps.  The ingestion half of the Anderson `de.setf.rdf:project-graph` boundary-layer primitive — inverse of `sandbar.project.export`.\n\nWHEN: use to load filesystem-canonical entity state into the substrate — restore from a project-export, ingest external content, or round-trip-validate after editing files manually.  When NOT to use: (a) you want to create entities programmatically — `sandbar.entity.create`; (b) you want to write TO filesystem — `sandbar.project.export`.\n\nHOW: `:from` is the input directory path (REQUIRED).  `:filter` (optional) restricts which entities ingest; same shape as `sandbar.project.export`'s filter — `:class`, `:classes`, `:tree-filter`.  Returns `{:from :filter :imported <count> :entities [<entity-summary>...]}`.\n\nORDER: idempotent on the same filesystem state.  Note: ingestion validates against schema; failures raise.  Pre-check schema compatibility via `sandbar.entity.validate` for sample inputs if uncertain.\n\nCOMBINATION: inverse of `sandbar.project.export`.  Round-trip property: `ingest-graph(project-graph(entities)) = entities` — verify via dual export + import + comparison.  Codec selection by file extension; registered codecs visible via `sandbar.codec.list`."
    :inputSchema (one-required
                   {:from     {:type "string" :description "Input directory path"}
                    :filter   {:type "object"
                               :description "Optional filter spec (same shape as project.export)"}
                    :persist? {:type        "boolean"
                               :description "When true, dt/make each parsed entity-spec into the Datomic substrate after import (one-shot ingest).  When false / omitted, this verb is a DRY-RUN that returns entity summaries without persisting.  Per Friction Item #11 of the 0.1.1 co-evolution arc — gives clients a single-call bootstrap path instead of N+1 round-trips (import + entity.create per).  On persist failure for any individual entity, the per-entity failure is captured in the response's `:failed` list (does NOT abort the whole ingest).  Returns `{:persisted-count :failed-count :failed [...]}` when :persist? true."}}
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

   ;; Aggregate — tag histogram (Stage 5.B-pre #4 — 0.1.1 co-evolution arc)
   {:name "sandbar.aggregate.tag-histogram"
    :title "Frequency histogram of :mm/Tag usage across the corpus"
    :description "WHICH: returns a frequency histogram of `:mm/Tag` usage across the corpus.  Each bin is `{:tag <ident> :value <string> :count <int>}` — count is the number of entities (any class) that reference the tag via inbound edges.\n\nWHEN: use for tag-vocabulary observability — 'which tags are most-used?', 'which tags are orphans (used by ≤1 entity)?'.  Underpins /memory-tags (no-arg form).  When NOT to use: (a) you want one tag's full citing-set — use `sandbar.navigate.inbound-edges` with the tag as `:entity`; (b) you want tag schema-introspection (not usage) — use `sandbar.tag.lookup`; (c) you want audit-shaped tag concerns (undefined-used, orphans, drift) — use `sandbar.tag.audit`.\n\nHOW: optional `:limit` caps returned bins (default 0 = no cap); sorted descending by count then ascending by tag-ident as tie-breaker.\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.tag.audit` (qualitative tag concerns) and `sandbar.tag.lookup` (single-tag detail).\n\nResult: `{:histogram [{:tag <ident> :value <string> :count <int>} ...] :total <int>}`."
    :inputSchema {:type "object"
                  :properties {:limit {:type "integer"
                                       :description "Cap returned bins (default 0 = no cap)"}}
                  :required []}
    :handler aggregate-tag-histogram-handler}

   ;; Search — BM25F multi-field fulltext (Stage 5.B-pre — 0.1.1 co-evolution arc)
   {:name "sandbar.search.bm25f"
    :title "Multi-field BM25F fulltext search over a class's instances"
    :description "WHICH: returns the top-K instances of `:class` ranked by Robertson-Zaragoza canonical BM25F over multi-field length-normalized scoring.  Field weights are introspected from the class's `:dt/bm25f-weights` declaration unless overridden via `:field-weights` opt.\n\nWHEN: use for content-relevance ranking — 'which memorials mention this concept'.  When NOT to use: (a) structural ranking by degree / backlink-density / recency / freshness — use `sandbar.aggregate.rank-by`; (b) exact-string lookup — use `sandbar.entity.find` (by ident); (c) Lucene query-language operators (AND / OR / NOT / phrase / wildcard / fuzzy / field-prefix) — these are NOT recognized; bag-of-words only.  Use `search-attribute` (single-slot via :db.fn/fulltext-search) for Lucene syntax over one fulltext-indexed slot.\n\nHOW: `:query` is a bag-of-words string (tokenized via Porter stemmer + lowercase + word-boundary split).  `:class` is the class ident.  Optional: `:limit` caps hits (default 20; 0 = no cap).  `:where` is a Datalog clause vec (or EDN string) restricting hits to entities matching the predicate; clauses must reference `?e` as the entity variable.  `:facet-by` is a vec of slot-idents to facet over the FULL match-set (before limit).  `:include` is a vec of projection options — `:field-scores` (per-slot scores) and `:snippets` (per-slot ~240-char window with **term** highlighting).  `:field-weights` overrides the class's declared weights.\n\nORDER: prerequisite — the target class must declare `:dt/bm25f-weights` (or supply `:field-weights` opt).  Discover via `sandbar.class.describe` if uncertain.\n\nCOMBINATION: composes with `sandbar.aggregate.rank-by` (re-rank search hits by structural axis), `sandbar.aggregate.group-by` (faceted counts via `:facet-by` opt is the in-one-call alternative), `sandbar.navigate.path-via` (cross-axis: search restricted to a graph-walk neighborhood — Stage 29 composition).  Pre-step: `sandbar.schema.classes` to discover candidate classes.\n\nResult: `{:hits [{:entity <entity-map> :eid <id> :score <double> :field-scores {<slot> <double>}? :snippets {<slot> <string>}?} ...] :total <int> :returned <int> :timing {:total-ms <int>} :facets {<slot> {<value> <count>}}?}`.  Per fulltext arc Stage 4c."
    :inputSchema (one-required
                   {:query         {:type "string"
                                    :description "Query string (bag-of-words; no Lucene query-language operators)"}
                    :class         {:type "string"
                                    :description "Class ident whose `:dt/bm25f-weights` drives field selection"}
                    :limit         {:type "integer"
                                    :description "Max hits (default 20; 0 = no cap)"}
                    :where         {:type "string"
                                    :description "Optional EDN-string of Datalog clauses; entity variable is `?e`"}
                    :facet-by      {:type "array"
                                    :items {:type "string"}
                                    :description "Slot-idents to facet over the full match-set"}
                    :include       {:type "array"
                                    :items {:type "string"}
                                    :description "Projection options: 'field-scores' / 'snippets'"}
                    :field-weights {:type "object"
                                    :description "Optional {slot-ident weight} map overriding class declaration"}}
                   [:query :class])
    :handler search-bm25f-handler}

   ;; Orientation — type-tree + tree (Stage 5.B-pre #3 — 0.1.1 co-evolution arc)
   {:name "sandbar.orient.type-tree"
    :title "Class-hierarchy subtree rooted at a class (nested rendering)"
    :description "WHICH: returns the class-hierarchy subtree rooted at `:root` (default `:dt/Resource` — the metamodel root).  Recursive walk via `dt/direct-subclasses-of`; produces a nested-map tree with `:class` + `:children` per node.\n\nWHEN: use to visualize the full subclass hierarchy from a root class.  Underpins /memory-type-tree.  When NOT to use: (a) only direct subclasses needed — use `sandbar.class.subclasses`; (b) flat list of all subclasses — use `sandbar.class.subclasses` (returns flat).\n\nHOW: optional `:root` — root class ident string (default `:dt/Resource`).  Cycles in the inheritance graph are detected + flagged with `:cycle? true` (no infinite recursion).\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.class.describe` / `.slots` (drill into individual classes) and `sandbar.types.subclass-of` (relation query).\n\nResult: `{:root <ident> :tree {:class <ident> :children [<subtree>...]}}`."
    :inputSchema {:type "object"
                  :properties {:root {:type "string"
                                      :description "Root class ident (default ':dt/Resource')"}}
                  :required []}
    :handler orient-type-tree-handler}

   {:name "sandbar.orient.tree"
    :title "Top-level directory grouping of class instances by path-slot"
    :description "WHICH: groups instances of `:class` by their `:path-slot` value's first-level directory prefix.  Returns per-directory counts + optional sample entities.\n\nWHEN: use for filesystem-style overview of a corpus subtree.  Underpins /memory-tree.  When NOT to use: (a) sibling enumeration within ONE directory — use `sandbar.navigate.siblings-of`; (b) recursive descent through sub-directories — compose multiple `tree` calls or use a path-grammar walk.\n\nHOW: `:class` is the class ident.  `:path-slot` is the slot carrying the filesystem-style path.  Optional `:sample-size` includes that many sample entities per directory in the result (default 0 = counts only).\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.navigate.siblings-of` (drill into a single directory) and `sandbar.aggregate.group-by` (more general group-by-slot).\n\nResult: `{:dirs {<dir-name> {:count N :sample [<entity-map>...]?}} :total N}`."
    :inputSchema (one-required
                   {:class       {:type "string"
                                  :description "Class ident whose instances to group"}
                    :path-slot   {:type "string"
                                  :description "Slot ident carrying filesystem-style path"}
                    :sample-size {:type "integer"
                                  :description "Sample entities per directory (default 0 = none)"}}
                   [:class :path-slot])
    :handler orient-tree-handler}

   ;; Orientation — library-card (Phase O — fulltext arc; substrate-quality scope per
   ;; corpus decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md)
   {:name "sandbar.orient.library-card"
    :title "Multi-axis typed-edge neighborhood view of an entity"
    :description "WHICH: returns a labeled, multi-axis view of an entity's typed-edge neighborhood.  Each `:axis` is a labeled subset of inbound or outbound edges optionally filtered by predicate-set and target/source-type.  Substrate-correct shape of the corpus's 'library-card' pattern — Sandbar ships the composition primitive; the consumer supplies the semantics (which axes mean what).\n\nWHEN: use when an AI client / consumer needs a structured overview of an entity — 'show me everything connected to this seed, broken down by relationship type'.  Especially useful for AI-orientation flows (load an unfamiliar entity; see its typed-edge surface across 10 axes at once).  When NOT to use: (a) single-predicate edge enumeration — use `sandbar.navigate.outbound` or `.inbound` directly (one call, simpler); (b) reachability across multiple hops — use `sandbar.navigate.walk` or `.path-via` instead; (c) fulltext-relevance ranking of the neighborhood — combine search with this verb's output downstream.\n\nHOW: `:entity` is the anchor entity (ident or eid).  `:axes` is a JSON array of axis-spec objects; each:\n  - `name` (REQUIRED) — string or keyword label for the axis in the result (e.g. \"cited-by-decisions\")\n  - `direction` (REQUIRED) — \"forward\" (outbound from entity) or \"inverse\" (inbound to entity)\n  - `predicates` (optional) — array of predicate-ident strings to restrict to (e.g. [\":cites\", \":evidences\"]); omit for no restriction\n  - `target-type` (optional, for :forward axes) — class-ident string restricting target-instance-of\n  - `source-type` (optional, for :inverse axes) — class-ident string restricting source-instance-of\n  - `limit` (optional) — per-axis edge cap; default 0 = no cap\nThe substrate is CLASS-AGNOSTIC; predicate-vocabulary + axis-labels are caller-supplied.  No hardcoded knowledge of any domain class's predicate vocabulary.\n\nORDER: prerequisite — the caller must know the predicate vocabulary applicable to the entity's class.  Discover via `sandbar.navigate.outbound` (one-shot peek at outbound edges) or `sandbar.class.slots` (declared slots on the entity's class) FIRST.  No other ordering dependencies.\n\nCOMBINATION: composes with `sandbar.navigate.inbound` / `.outbound` (use them to DISCOVER predicate vocab first, then author library-card axis-specs covering them).  For ranked subsets within an axis, post-rank the results via `sandbar.aggregate.rank-by` (using the axis-result eids as the candidate set).  For path-shaped neighborhoods (recursive / Kleene), use `sandbar.navigate.path-via` instead — library-card is one-hop-per-axis by design.\n\nResult: `{:entity <entity-map> :axes {<axis-name> [{:predicate ... :target/source <entity-map>}...] ...}}`.  Per Phase O of plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
    :inputSchema (one-required
                   {:entity {:type "string"
                             :description "Anchor entity ident (e.g. ':decisions/foo') or eid"}
                    :axes   {:type "array"
                             :items {:type "object"
                                     :description "Axis-spec: {name, direction:'forward'|'inverse', predicates?, target-type?, source-type?, limit?}"}
                             :description "Vec of axis-spec objects; one labeled subset per axis"}}
                   [:entity :axes])
    :handler orient-library-card-handler}

   ;; Navigation — outbound + inbound edges (Stage 5.B-pre #2 — 0.1.1 co-evolution arc)
   {:name "sandbar.navigate.outbound-edges"
    :title "Typed-edges originating FROM an entity"
    :description "WHICH: returns typed-edges originating from `:entity` — what does this entity reference, via which predicate, to which target.  Foundational outbound traversal primitive.\n\nWHEN: use for one-hop forward navigation when you need the predicate-and-target shape (not just the targets).  Underpins /memory-xref + /memory-show.  When NOT to use: (a) targets-only (no predicate label) — use a Datalog query directly; (b) recursive / Kleene-closure traversal — use `sandbar.navigate.path-via`; (c) bounded-depth BFS — use `sandbar.navigate.walk`.\n\nHOW: `:entity` is the seed entity (ident or eid).  Optional `:predicate` is a single keyword-string or vec to restrict to specific edge-predicates.  Optional `:target-type` is a class-ident-string restricting targets to instances-of.  Optional `:limit` caps returned edges (default 0 = no cap).\n\nORDER: leaf-call shape.  Discover candidate predicates first via `sandbar.class.slots` on the entity's class if uncertain.\n\nCOMBINATION: pairs with `sandbar.navigate.inbound-edges` (the dual; who references this entity).  Composes with `sandbar.orient.library-card` (one-call multi-axis breakdown).  Pre-step for `sandbar.navigate.path-via` (discover predicate vocab before authoring path expressions).\n\nResult: `{:edges [{:predicate <pred-ident> :target <entity-map>} ...] :total <int> :returned <int>}`."
    :inputSchema (one-required
                   {:entity      {:type "string"
                                  :description "Anchor entity ident or eid"}
                    :predicate   {:type "string"
                                  :description "Single predicate ident OR JSON array of idents (restricts to these edges)"}
                    :target-type {:type "string"
                                  :description "Class ident restricting target-instance-of"}
                    :limit       {:type "integer"
                                  :description "Max edges (default 0 = no cap)"}}
                   [:entity])
    :handler navigate-outbound-edges-handler}

   {:name "sandbar.navigate.inbound-edges"
    :title "Typed-edges pointing AT an entity (who references it)"
    :description "WHICH: returns typed-edges pointing at `:entity` — who references this entity, via which predicate, from which source.  Foundational inbound traversal primitive (dual of `sandbar.navigate.outbound-edges`).\n\nWHEN: use for backlink discovery — 'which decisions cite this ADR?'.  Underpins /memory-xref + library-card inverse-axes.  When NOT to use: (a) sources-only without predicate label — use Datalog directly; (b) bounded-depth backlink walk — use `sandbar.navigate.walk` with `:inbound` flag; (c) Kleene closure — use `sandbar.navigate.path-via` with `:INV`.\n\nHOW: `:entity` is the target entity (ident or eid).  Optional `:predicate` is a single keyword-string or vec to restrict to specific edge-predicates.  Optional `:source-type` is a class-ident-string restricting sources to instances-of.  Optional `:limit` caps returned edges.\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.navigate.outbound-edges` (the dual).  Composes with `sandbar.orient.library-card` (`:inverse` axes use the inbound shape).\n\nResult: `{:edges [{:predicate <pred-ident> :source <entity-map>} ...] :total <int> :returned <int>}`."
    :inputSchema (one-required
                   {:entity      {:type "string"
                                  :description "Anchor entity ident or eid"}
                    :predicate   {:type "string"
                                  :description "Single predicate ident OR JSON array of idents"}
                    :source-type {:type "string"
                                  :description "Class ident restricting source-instance-of"}
                    :limit       {:type "integer"
                                  :description "Max edges (default 0 = no cap)"}}
                   [:entity])
    :handler navigate-inbound-edges-handler}

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
    :handler navigate-path-via-handler}

   ;; Tag-vocabulary operations — Stage 7.D of decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
   {:name "sandbar.ground"
    :title "Compositional grounding workflow — tag lookup + meta-vocab + suggested next-step"
    :description "WHICH: load-bearing entry point for grounding-before-action.  Composes tag-vocabulary examination (step 1) + meta-vocabulary discovery (step 2; classes / predicates aligned with concept) + suggested-next-step routing (step 3).  Per observations/grounding_is_compositional_mcp_workflow_thin_client_2026_05_20.md — grounding is a multi-step MCP workflow, NOT a single primitive verb.\n\nWHEN: use BEFORE introspection / planning / authoring / research to anchor the concept in the substrate's vocabulary.  The 5th retrieval axis (formal-semantic vocabulary) per observations/tags_as_5th_retrieval_axis_with_formal_semantics_2026_05_20.md.  Composes with the other four retrieval axes (search / aggregation / orientation / navigation).  When NOT to use: the concept is already grounded (e.g., you have a concrete tag / class / predicate ident); skip to the specific verb.\n\nHOW: `:concept` is the concept-string to ground.  Returns:\n  `:step-1-tag-lookup` — sandbar.tag.lookup result (canonical / alt-label / scope-note matching)\n  `:step-2-meta-vocab` — classes + predicates whose name aligns with the concept\n  `:step-3-suggested-next` — vec of suggested next MCP calls based on what step-1 and step-2 surfaced\n\nORDER: typically the FIRST call when an LLM consumer encounters a new concept in user input.  After this verb, the consumer either (a) calls sandbar.tag.define if step-1 reported `:gap? true`, (b) calls sandbar.search.bm25f informed by a selected tag's scope-note, or (c) calls sandbar.navigate.outbound to explore typed-edge context from a matched tag.\n\nCOMBINATION: anchor for tag.* operations.  Stage 7.D MVP scope — Stage 7.F+ refinement integrates sandbar.search.bm25f + sandbar.navigate.path-via for true compositional workflow."
    :inputSchema (one-required {:concept {:type "string" :description "Concept-string to ground"}}
                               [:concept])
    :handler ground-handler}
   {:name "sandbar.tag.lookup"
    :title "Tag-vocabulary primitive — find canonical tags aligned with a concept"
    :description "WHICH: surfaces tags whose canonical-form / alt-label / hidden-label / definition / scope-note / example align with the query concept.  Step 1 of the sandbar.ground compositional workflow.  Returns ranked candidates with broader/narrower context.\n\nWHEN: use to discover whether the corpus's tag vocabulary already has a concept covered before authoring a new tag.  Disambiguation primitive — if scope-notes differ across candidates, the right tag becomes obvious.  When NOT to use: (a) the concept is corpus-wide (try sandbar.search.bm25f over body content instead); (b) you already have a specific tag-value (use sandbar.entity.find or read directly).\n\nHOW: `:concept` is the concept-string; `:limit` (optional) caps returned matches (default 10).  Returns `:concept`, `:matches` (vec of tag-summary maps with `:score`), `:gap?` (true when no tag matches), `:gap-hint` (suggested sandbar.tag.define invocation when gap).\n\nORDER: step 1 of sandbar.ground.  Called directly when you want JUST the tag-vocabulary primitive (no meta-vocab / suggested-next).\n\nCOMBINATION: pairs with sandbar.tag.define (when `:gap? true` — author the canonical), sandbar.tag.consolidate (when matches show drift), sandbar.tag.audit (which tags' lifecycle-status is healthy?)."
    :inputSchema (one-required {:concept {:type "string" :description "Concept-string to look up"}
                                :limit   {:type "integer" :description "Max matches returned (default 10)"}}
                               [:concept])
    :handler tag-lookup-handler}
   {:name "sandbar.tag.define"
    :title "Author a new canonical :mm/Tag with required documentation slots"
    :description "WHICH: creates a new :mm/Tag entity with the supplied canonical :value + optional documentation slots (definition / scope-note / example / broader-* / in-scheme / etc.).  Forces explicit authoring at the boundary — `sandbar.tag.audit` will surface tags without definitions as the `:undefined-used` invariant.\n\nWHEN: use after sandbar.tag.lookup reports `:gap? true` (no canonical exists for this concept).  Authoring includes scope-note — the editorial boundary anchoring the canonical.  When NOT to use: (a) a canonical already exists — use sandbar.tag.consolidate to merge instead; (b) you want to rename — use sandbar.tag.rename; (c) the new tag overlaps a memorial-type — don't define (memorial-type slot already carries that information).\n\nHOW: `:name` is the canonical tag string (becomes :mm.tag/value).  `:slots` (optional) is a map of additional :mm.tag/* slot values:\n  `:definition`  — SKOS canonical definition\n  `:scope-note`  — editorial boundary\n  `:example`     — usage illustration\n  `:in-scheme`   — :mm/ConceptScheme ref (e.g., `:memory-system-meta-vocabulary`)\n  `:canonical?`  — boolean (default true once defined)\n  `:vocabulary-level` — :substrate-level / :corpus-level / etc.\n  `:lifecycle-status` — :proposed / :active / :deprecated / :superseded\n\nReturns `{:tag <tag-summary> :created true}`.  Errors when a tag with this :value already exists.\n\nORDER: after sandbar.tag.lookup confirms gap.\n\nCOMBINATION: pairs with sandbar.tag.lookup (gap discovery), sandbar.tag.audit (post-define audit-check), sandbar.tag.align (cross-vocabulary mapping after defining)."
    :inputSchema (one-required {:name  {:type "string" :description "Canonical tag string (becomes :mm.tag/value)"}
                                :slots {:type "object" :description "Optional :mm.tag/* slots (definition, scope-note, example, broader-*, etc.)"}}
                               [:name])
    :handler tag-define-handler}
   {:name "sandbar.tag.audit"
    :title "Run the 7 tag-lifecycle invariants and return the violation report"
    :description "WHICH: runs `sandbar.audit.tag/audit-all` — seven independent invariants over the corpus's tag vocabulary.  Returns per-invariant violations + an aggregate count.\n\nThe seven invariants:\n  1. `:undefined-used`      — tags referenced via :mm.memory/tags lacking :mm.tag/definition\n  2. `:defined-unused`      — tags with :mm.tag/definition but no inbound :mm.memory/tags refs\n  3. `:orphan`              — tags with no :mm.tag/in-scheme membership\n  4. `:date-pattern`        — tags whose :mm.tag/value matches a date pattern\n  5. `:type-pattern`        — tags whose :mm.tag/value overlaps a memorial-type keyword\n  6. `:drift`               — clusters of tags with same normalized form (case + plural)\n  7. `:closure-consistency` — cycles on broader-* / asymmetries on :related / missing inverse pairs on :superseded-by\n\nWHEN: use periodically to monitor vocabulary health.  Foundational pre-step for sandbar.tag.harmonize.  Foundational diagnostic for migration M.1-M.5 staging.  When NOT to use: (a) you want ONE invariant — call sandbar.audit.tag/<invariant-fn> via the in-process API directly (no individual MCP verb yet; aggregate-only at this stage).\n\nHOW: no arguments.  Returns `{:invariants [<map per invariant>] :total-violations N :summary <string>}`.\n\nORDER: no prerequisites; foundational diagnostic.\n\nCOMBINATION: feeds sandbar.tag.harmonize (drift cluster reconciliation), sandbar.tag.consolidate (per-cluster merges), sandbar.tag.define (for :undefined-used findings)."
    :inputSchema no-args-schema
    :handler tag-audit-handler}
   {:name "sandbar.tag.consolidate"
    :title "Merge :from tag INTO :into tag; preserves :from as alt-label + lifecycle :superseded"
    :description "WHICH: merges two tags by adding :from's canonical :value as a :mm.tag/alt-label on :into, marking :from with :mm.tag/lifecycle-status :superseded + :mm.tag/superseded-by ref to :into, and rewriting every :mm.memory/tags ref from :from to :into.  The merge preserves history (alt-label + superseded-by) for search-recall + audit trail.\n\nWHEN: use to resolve drift clusters surfaced by sandbar.tag.audit `:drift` invariant — `{tag, tags}` → consolidate \"tags\" into \"tag\".  Also use for editorial vocabulary cleanup (synonyms / variant spellings).  When NOT to use: (a) the tags are NOT synonyms — keep them separate; (b) you want a true rename (no source tag preserved) — use sandbar.tag.rename instead; (c) you want to partition a tag into narrower tags — use sandbar.tag.split.\n\nHOW: `:from` is the variant being merged out; `:into` is the canonical being merged into.  Both are :mm.tag/value strings.  Returns `:from`, `:into`, `:memorials-rewritten` (count of memorials whose :tags ref was rewritten), `:alt-label-added` (the preserved-as-alt-label value), `:lifecycle-status`.\n\nORDER: after sandbar.tag.audit surfaces a drift cluster + editorial decision selects canonical.\n\nCOMBINATION: pairs with sandbar.tag.audit (cluster discovery), sandbar.tag.harmonize (bulk drift-cluster planner), sandbar.tag.rename (when no merge is needed)."
    :inputSchema (one-required {:from {:type "string" :description "Tag :value to merge OUT (becomes alt-label on :into)"}
                                :into {:type "string" :description "Tag :value to merge INTO (canonical preserved)"}}
                               [:from :into])
    :handler tag-consolidate-handler}
   {:name "sandbar.tag.split"
    :title "Partition a tag into narrower tags (creates :broader-generic children)"
    :description "WHICH: declares that :tag is being partitioned into 2+ narrower tags (:into-tags).  Each new tag is created as :mm.tag/broader-generic :tag.  Does NOT auto-reroute existing memorial refs — surfaces the partition; per-memorial reassignment is editorial follow-on.\n\nWHEN: use when scope-creep has accumulated under a single tag and the editorial decision is to partition (e.g., \"audit\" → \"audit-corpus\" + \"audit-discipline\" + \"audit-schema\").  When NOT to use: (a) you want to merge tags — sandbar.tag.consolidate; (b) you want to rename — sandbar.tag.rename; (c) the narrower tags already exist — manually wire :broader-generic via sandbar.entity.update.\n\nHOW: `:tag` is the parent tag :value.  `:into-tags` is a vector of `{:value :scope-note}` maps (2+ entries).  Returns `:parent`, `:into-tags` (vec of created values), `:note` (reminder about manual memorial reassignment).\n\nORDER: after editorial decision to partition.  After this verb, manually reassign existing memorial :mm.memory/tags refs via sandbar.entity.update.\n\nCOMBINATION: pairs with sandbar.entity.update (for memorial reassignment), sandbar.tag.audit (post-split, audit confirms partition is wired)."
    :inputSchema (one-required {:tag {:type "string" :description "Parent tag :value to partition"}
                                :into-tags {:type "array"
                                            :description "Vector of {:value :scope-note} maps for narrower tags (2+ entries)"
                                            :items {:type "object"
                                                    :properties {:value      {:type "string"}
                                                                 :scope-note {:type "string"}}
                                                    :required ["value"]}}}
                               [:tag :into-tags])
    :handler tag-split-handler}
   {:name "sandbar.tag.rename"
    :title "Change a tag's canonical :value; preserves old as hidden-label"
    :description "WHICH: changes the canonical :mm.tag/value from :old to :new.  Preserves :old as :mm.tag/hidden-label (kept in fulltext search index for recall; not displayed as canonical or alt-label).  Refs by :db/id are unaffected — no memorial rewrite needed.\n\nWHEN: use when canonical form needs to change (typo fix; convention shift; canonicalization).  When NOT to use: (a) you want to merge with an existing canonical — sandbar.tag.consolidate; (b) you want to split — sandbar.tag.split; (c) the tag should be deprecated, not renamed — use sandbar.entity.update to set :mm.tag/lifecycle-status :deprecated.\n\nHOW: `:old` is the current :value; `:new` is the new canonical.  Both must be non-blank strings; must differ.  Returns `:old`, `:new`, `:hidden-label-preserved`.\n\nErrors when `:new` is already taken by another tag — use sandbar.tag.consolidate to merge instead.\n\nORDER: no prerequisites beyond having the tag in the corpus.\n\nCOMBINATION: pairs with sandbar.tag.audit (post-rename verification), sandbar.tag.consolidate (alternative when merging instead of pure-rename)."
    :inputSchema (one-required {:old {:type "string" :description "Current canonical :value"}
                                :new {:type "string" :description "New canonical :value (becomes :mm.tag/value)"}}
                               [:old :new])
    :handler tag-rename-handler}
   {:name "sandbar.tag.align"
    :title "Declare a cross-vocabulary SKOS mapping from a tag to an external IRI"
    :description "WHICH: records a cross-vocabulary mapping from :tag to :external-iri under a SKOS mapping relation (`:exact-match` / `:close-match` / `:broader-match` / `:narrower-match` / `:related-match`).  Per ADR §2.1 Tier E + ISO 25964 Part 2 inter-vocabulary mapping.\n\nWHEN: use when the corpus's tag aligns with a tag in an external vocabulary (e.g., a Wikidata Q-id, a Dewey class, a Schema.org type, a SKOS concept in a referenced ontology).  Foundational for the federation backbone — VoID :mm/Linkset entities aggregate these mappings.  When NOT to use: (a) the external concept isn't actually mapped — don't fabricate; (b) you want a tag-to-tag mapping within the corpus — use :mm.tag/related instead.\n\nHOW: `:tag` is the corpus tag :value.  `:external-iri` is the external concept's IRI (e.g., \"http://www.wikidata.org/entity/Q12345\").  `:mapping-type` is one of \"exact-match\" / \"close-match\" / \"broader-match\" / \"narrower-match\" / \"related-match\" (default \"exact-match\").\n\nReturns `:tag`, `:external-iri`, `:mapping-type`, `:slot` (the resolved :mm.tag/<type> slot).\n\nMVP: stores the external IRI as a :mm/Tag entity (via :mm.tag/value upsert) referenced by the mapping slot.  Stage 8+ federation lands richer :mm/Vocabulary / :mm/Linkset modeling.\n\nORDER: after the tag is defined (sandbar.tag.define).\n\nCOMBINATION: pairs with sandbar.tag.audit (the alignment is auditable as a SKOS-mapping relation), sandbar.tag.harmonize (bulk alignment proposals from Wikidata / external SKOS schemes)."
    :inputSchema (one-required {:tag           {:type "string" :description "Corpus tag :value"}
                                :external-iri  {:type "string" :description "External concept IRI (e.g., Wikidata Q-id URL)"}
                                :mapping-type  {:type "string"
                                                :description "SKOS mapping relation — one of exact-match / close-match / broader-match / narrower-match / related-match (default exact-match)"}}
                               [:tag :external-iri])
    :handler tag-align-handler}
   {:name "sandbar.tag.harmonize"
    :title "Bulk-harmonization DRY-RUN report — drift clusters + auto-mergeable counts"
    :description "WHICH: runs the full audit + identifies auto-mergeable drift clusters (M.3 candidates).  DRY-RUN report — actual auto-merge requires per-cluster sandbar.tag.consolidate invocations.\n\nWHEN: use during migration M.1-M.5 staging to plan the consolidation pass.  Surfaces which drift clusters are safe to auto-merge (2-variant clusters where canonical choice is obvious) vs. those needing editorial review (3+ variants; ambiguous canonical).  When NOT to use: (a) you want to apply consolidations — call sandbar.tag.consolidate per cluster (this verb is advisory-only at MVP); (b) you want one specific invariant — sandbar.tag.audit returns all 7.\n\nHOW: no arguments.  Returns `:audit-report` (full audit), `:drift-clusters` (M.3 cluster list), `:drift-cluster-count`, `:auto-mergeable-count`, `:note` (explains DRY-RUN + auto-apply policy deferred to Stage 8).\n\nORDER: pre-step for the M.3 phase of vocabulary migration.\n\nCOMBINATION: feeds sandbar.tag.consolidate (per-cluster merges).  Composes with sandbar.tag.audit (deeper audit detail) and sandbar.tag.split (when a cluster reveals partition need)."
    :inputSchema no-args-schema
    :handler tag-harmonize-handler}])

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
   - Unknown verb: JSON-RPC `invalid-params`
   - Precondition failure: JSON-RPC `internal-error`
   - Internal error: JSON-RPC `internal-error`

   Error codes via `sandbar.util.jsonrpc-status` (semantic constants)."
  [id params]
  (let [tool-name (:name params)
        arguments (:arguments params {})
        verb      (get verb-by-name tool-name)]
    (cond
      (nil? verb)
      (envelope/jsonrpc-error id jsonrpc-status/invalid-params
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
        ;; Catch :pre / assertion-error failures separately from Exception.
        ;; AssertionError extends java.lang.Error (NOT Exception), so without
        ;; this explicit catch, assertion failures escape the MCP envelope
        ;; entirely — the codex F-MF-3 release-blocker.  Sibling catch
        ;; (rather than (catch Throwable ...)) preserves JVM-error
        ;; propagation discipline: OutOfMemoryError / StackOverflowError /
        ;; etc. should not be masked as MCP -32603.
        ;; See decisions/sandbar_entity_ref_abstraction_2026_05_14.md §D-3.3.
        (catch AssertionError e
          (log/error e :MCP/precondition-failed {:tool tool-name})
          (envelope/jsonrpc-error id jsonrpc-status/internal-error
                                  "Internal-invariant precondition failed at MCP boundary"
                                  {:tool tool-name
                                   :assertion (.getMessage e)}))
        (catch Exception e
          (log/error e :MCP/tools-call-error {:tool tool-name})
          (envelope/jsonrpc-error id jsonrpc-status/internal-error
                                  "Tool execution failed"
                                  {:tool tool-name
                                   :exception-message (.getMessage e)}))))))
