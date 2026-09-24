(ns sandbar.mcp.tools
  "MCP tools/list and tools/call handlers for a stable operational catalog.

   Operations are parameterized by class or property rather than generated
   once per class. The catalog supplies wire schemas, descriptions and handler
   dispatch; catalog-model supplies the file and persisted-card projections.

   Domain APIs return different shapes: some introspection calls return idents,
   others entity maps or Datomic entities. Projection helpers normalize these
   values at the wire boundary. Loading this namespace defines handlers; it
   does not by itself start a server or connect to a database."
  (:require [cheshire.core              :as json]
            [clojure.edn                :as edn]
            [clojure.string             :as str]
            [clojure.tools.logging      :as log]
            [datomic.api                :as d]
            [sandbar.aggregate          :as aggregate]
            [sandbar.api.projection     :as projection]
            [sandbar.audit.fs-substrate-drift :as audit-fs-drift]
            [sandbar.audit.tag          :as audit-tag]
            [sandbar.codec              :as codec]
            [sandbar.codec.markdown     :as codec-md]
            [sandbar.util.edn           :as cfg]
            [sandbar.entity-ref         :as eref]
            [sandbar.identifier         :as id]
            [sandbar.navigate.edges     :as nav-edges]
            [sandbar.navigate.path      :as nav-path]
            [sandbar.navigate.siblings  :as nav-siblings]
            [sandbar.orient             :as orient]
            [sandbar.import          :as import]
            [sandbar.projection      :as pg]
            [sandbar.project.import-destination :as import-destination]
            [sandbar.project.export :as project-export]
            [sandbar.project.recovery-check :as recovery-check]
            [sandbar.project.provenance :as prov]
            [sandbar.reactive.queue     :as reactive-queue]
            [sandbar.retract            :as retract]
            [sandbar.schedule           :as sched]
            [sandbar.search             :as search]
            [sandbar.security.query     :as secq]
            [sandbar.security.visibility :as visibility]
            [sandbar.shape              :as shape]
            [sandbar.store              :as store]
            [sandbar.db.datatype        :as dt]
            [sandbar.db.datomic         :as db]
            [sandbar.firewall.enforce   :as fw-enforce]
            [sandbar.mcp.envelope       :as envelope]
            [sandbar.mcp.notifications  :as notifications]
            [sandbar.mcp.resources      :as resources]
            [sandbar.util.auth          :as auth]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]
            [sandbar.service.validation :as validation]
            [sandbar.util.workflow      :as workflow]
            [sandbar.workflow.orchestrate :as orchestrate]))

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
  "Project an ident (keyword), entity-map, OR Datomic Entity to a string form.
   Handles dt/* contract variance — some functions return idents, others return
   entity-maps, others raw Datomic Entity objects (ref-traversal).

   Datomic Entity objects are NOT `map?`, so without an explicit :db/ident
   lookup they fell to the `(str x)` branch and printed \"{:db/id N}\" — which
   masked workflow process-state behind an opaque eid (e.g. workflow.process-state
   on a leaked process showed {:db/id …} instead of :session/active).  Fixed
   2026-05-29 (lifecycle-hardening arc) by trying :db/ident before the fallback."
  [x]
  (cond
    (keyword? x)         (str x)
    (map? x)             (str (:db/ident x))
    (some-> x :db/ident) (str (:db/ident x))
    :else                (str x)))

;; Projection helpers lifted to `sandbar.api.projection` per Task #12.
;; Local aliases: `projection/full-projection` → `projection/full-projection`;
;; `->projection-mode` → `projection/->projection-mode`; etc.

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
;; storage type for references, semantic range for literals, and cardinality.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- ->instant
  "Parse a string to java.util.Date for a :db.type/instant slot.
   Accepts an ISO-8601 instant, a bare date (UTC start of day), or a zoneless
   local date-time (interpreted as UTC). Non-strings pass through."
  [v]
  (if (string? v)
    (java.util.Date/from
      (try
        (java.time.Instant/parse v)
        (catch java.time.format.DateTimeParseException _
          (try
            (-> (java.time.LocalDate/parse v)
                (.atStartOfDay java.time.ZoneOffset/UTC)
                (.toInstant))
            (catch java.time.format.DateTimeParseException _
              (-> (java.time.LocalDateTime/parse v)
                  (.toInstant java.time.ZoneOffset/UTC)))))))
    v))

(defn- coerce-value
  "Coerce one argument value to its target Datomic type."
  [value target-type many?]
  (let [coerce-one (fn [v]
                     (case target-type
                       :db.type/keyword (->ident v)
                       :db.type/instant (->instant v)
                       :db.type/uuid    (if (string? v)
                                          (java.util.UUID/fromString v)
                                          v)
                       :db.type/ref     (if (string? v) (->ident v) v)
                       v))]
    (cond
      (and many? (sequential? value))
      (mapv coerce-one value)

      many?
      [(coerce-one value)]

      :else
      (coerce-one value))))

(defn- edn-readable-keyword?
  "True iff keyword K survives an EDN round trip as exactly itself: printed
   inside a one-element vector and read back with clojure.edn, the same
   vector returns.  A reader error, extra reader forms (whitespace, `,`,
   `\\`, brackets) and comment truncation (`;` inside the name) all fail —
   the same oracle `sandbar.codec.markdown` applies to authored keywords."
  [k]
  (try (= [k] (edn/read-string (pr-str [k])))
       (catch Exception _ false)))

(defn- refuse-unreadable-keywords!
  "Return COERCED unchanged unless it is, or contains as a cardinality-many
   member, a keyword that cannot round-trip EDN; then throw the user-error
   ex-info that `handle-call` maps to an `isError` result, naming SLOT-IDENT,
   the ORIGINAL wire value (bounded) and the offending keyword's text — never
   the unreadable keyword itself, so the diagnostic stays readable.  Nothing
   is transacted before this check runs.  Keyword-typed slots (including an
   explicit `db/ident`) and reference strings are the write paths that mint
   keywords through `->ident`; a reference map's `:db/ident` is checked as a
   keyword (an in-process caller's upsert form) or as an ident string read
   the way `->ident` reads it, because the transactor coerces that string to
   the same keyword and mints the target when no entity carries it yet; eids,
   `{:db/id …}` and other map keys pass through untouched."
  [slot-ident original coerced]
  (let [bounded  (fn [v] (let [s (str v)] (if (> (count s) 200) (str (subs s 0 200) "…") s)))
        refuse!  (fn [k index]
                   (throw (ex-info (str "Slot " slot-ident " refused: the value "
                                        (pr-str (bounded (if (and (some? index) (sequential? original))
                                                           (nth original index nil) original)))
                                        " would become a keyword that cannot round-trip EDN ("
                                        (pr-str (bounded (str k))) ")"
                                        (when (some? index) (str " at index " index)))
                                   (cond-> {:type         :mcp/unreadable-keyword
                                            :slot         slot-ident
                                            :value        (bounded (if (and (some? index) (sequential? original))
                                                                     (nth original index nil) original))
                                            :keyword-text (bounded (str k))}
                                     (some? index) (assoc :index index)))))
        candidate (fn [v] (cond (keyword? v) v
                                (map? v) (let [id (:db/ident v)]
                                           (cond (keyword? id) id
                                                 (string? id)  (->ident id)
                                                 :else         nil))
                                :else nil))]
    (cond
      (keyword? coerced)
      (when-not (edn-readable-keyword? coerced) (refuse! coerced nil))

      (map? coerced)
      (when-let [k (candidate coerced)]
        (when-not (edn-readable-keyword? k) (refuse! k nil)))

      (sequential? coerced)
      (doseq [[i member] (map-indexed vector coerced)]
        (when-let [k (candidate member)]
          (when-not (edn-readable-keyword? k) (refuse! k i)))))
    coerced))

(defn- slot-candidate-keys
  "Key-shapes an incoming slot-map might use for a declared slot-ident:
   the ident keyword; the bare local name; the printed-ident string; the
   stripped-colon string; AND the cheshire-mangled colon-namespace keyword.

   The last shape is the 2026-06-29 silent-drop bug: cheshire's `:key-fn
   keyword` turns a leading-colon JSON key `:ns/name` into a keyword whose
   NAMESPACE carries the colon — `(keyword \":mm.memory/cites\")` splits on
   the first '/' into ns \":mm.memory\" + name \"cites\" — which matched none
   of the original four shapes, so the slot was silently dropped (producing
   identless entities + shape-nonconformant memorials).  Adding it is a strict
   SUPERSET of the prior matching, so bare local names (relied on by e.g.
   tag.define) still match."
  [slot-ident]
  (let [nm (name slot-ident)
        ns (namespace slot-ident)]
    (cond-> [slot-ident nm (str slot-ident) (subs (str slot-ident) 1)]
      ns (conj (keyword (str ":" ns) nm)))))

(defn- coerce-slot-map
  "Coerce a JSON-shaped slot map (string OR keyword keys → arbitrary values)
   to a Datomic-shaped props map (keyword keys → coerced values).  Uses
   The storage type identifies references even when the semantic range names
   a class (e.g. :mm/Project). Literal coercion continues to use dt/range-of;
   class membership and ref existence remain the substrate's validation job.

   Accepts keys in any of these shapes (checked in priority order):
   1. Keyword ident — `:mm.memory/name` — MCP boundary arrives this way
      because cheshire JSON-parse uses `:key-fn keyword` per
      sandbar.util.codec/json-read; cheshire's keyword coercion handles
      both `\"mm.memory/name\"` (typical JSON) and `\":mm.memory/name\"`
      (leading-colon variant) shapes.
   2. Bare-name string — `\"name\"` — legacy callers passing the slot's
      local name only (rare; class introspection ambiguous if multiple
      slots share a local name).
   3. Full-ident string — `\":mm.memory/name\"` — in-process callers
      passing the printed-keyword shape as a string.
   4. Stripped-colon string — `\"mm.memory/name\"` — in-process callers
      passing the namespaced-name shape as a string."
  [class-ident slot-map]
  (let [slots (dt/slots-of class-ident)
        ;; For each declared slot, the first incoming key (across all candidate
        ;; shapes, incl. the cheshire-mangled colon-namespace form) that is
        ;; present.  contains?/get (not `or`) so a legit `false` value isn't
        ;; skipped.
        hits  (keep (fn [slot-ident]
                      (when-let [hk (some #(when (contains? slot-map %) %)
                                          (slot-candidate-keys slot-ident))]
                        [slot-ident hk]))
                    slots)
        props (reduce (fn [acc [slot-ident hk]]
                        (let [original (get slot-map hk)
                              coerced  (coerce-value original
                                                     (if (= :db.type/ref
                                                            (:db/valueType (db/entity slot-ident)))
                                                       :db.type/ref
                                                       (dt/range-of slot-ident))
                                                     (dt/cardinality-many? slot-ident))]
                          ;; Readable-keyword guard (2026-09-21): a keyword-typed
                          ;; value, an explicit db/ident or a reference string
                          ;; that would mint an EDN-unreadable keyword is refused
                          ;; here, before any handler transacts.
                          (assoc acc slot-ident
                                 (refuse-unreadable-keywords! slot-ident original coerced))))
                      {} hits)
        matched (set (map second hits))
        unknown (remove matched (keys slot-map))]
    ;; Loud signal instead of silent drop: any incoming key that resolved to
    ;; NO declared slot (across every shape) is logged — this path silently
    ;; swallowed colon-prefixed keys twice (see slot-candidate-keys).
    (when (seq unknown)
      (log/warn :MCP/coerce-slot-map-unknown-keys
                {:class               class-ident
                 :unknown-keys        (mapv str unknown)
                 :declared-slot-count (count slots)}))
    props))

(defn- unmatched-slot-keys
  "The subset of `slot-map`'s keys that resolve to NO declared slot of
   `class-ident` (across every candidate key-shape `coerce-slot-map`
   accepts).  These are the keys `coerce-slot-map` silently discards —
   surfacing them lets a boundary handler refuse rather than echo a false
   success.  Returns a (possibly empty) vector of the original keys.

   Complements `coerce-slot-map`'s server-side `:MCP/coerce-slot-map-unknown-keys`
   log with a caller-visible signal — the F12 fail-silent lens: a dropped
   payload key must be LOUD, never a wire-success with a lost slot (per
   bugs/tag_define_upgrade_silently_drops_slots_payload_2026_07_03.md)."
  [class-ident slot-map]
  (let [slots   (dt/slots-of class-ident)
        matched (into #{}
                      (comp (mapcat slot-candidate-keys)
                            (filter #(contains? slot-map %)))
                      slots)]
    (vec (remove matched (keys slot-map)))))

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

(defn- registry-arg
  "Resolve the introspection family's `class` / `property` argument to a
   live metamodel definition of the expected kind, refusing everything else
   as a user error.

   Before this check (2026-09-20) an unknown class ident reached the Datalog
   helpers and escaped as a JSON-RPC internal error, while an unknown
   property or an entity-read class verb answered an empty descriptor — the
   same typo answered three ways, none of which told a client what to do
   next.  The `class-arg` / `property-arg` helpers stay DB-independent by
   contract; this is the handler-level resolution their docstring defers to,
   scoped to the nine class/property verbs and to nothing else.

   `arg-name` is \"class\" or \"property\"; `shape-coerce` is `class-arg` or
   `property-arg`; `kind` is `:dt/Class` or `:dt/Property`.  Refuses, with
   the `:reasons` envelope `handle-call` projects to `isError`:
   - a present argument that is not an ident string (`:entity-ref/malformed-input`);
   - an ident without a namespace (`:entity-ref/malformed-input`);
   - an ident naming no entity (`:entity-ref/not-found`, from `eref/resolve`);
   - an ident naming an entity of another kind — a memorial passed as a
     class, a class passed as a property (`:introspection/wrong-kind`).
   Returns the ident keyword unchanged, so the callers' success shapes are
   untouched.  Scope is not checked here: the family stays on
   `read-plane-registry-exempt-verbs` (definitions, never instance data)."
  [args arg-name shape-coerce kind]
  (let [raw (or (get args arg-name) (get args (keyword arg-name)))]
    (when (and (some? raw) (not (or (string? raw) (keyword? raw))))
      (throw (ex-info (str arg-name " must be an ident string such as :mm/Memory; got " (pr-str raw))
                      {:reasons #{:entity-ref/malformed-input} :argument arg-name :value raw}))))
  (let [k (shape-coerce args)]
    (when-not (namespace k)
      (throw (ex-info (str arg-name " must be a namespaced ident such as :mm/Memory; got " k)
                      {:reasons #{:entity-ref/malformed-input} :argument arg-name :value (str k)})))
    (let [e         (eref/resolve k)
          raw-type  (dt/class-ident-of e)
          type      (if (keyword? raw-type) raw-type (:db/ident raw-type))
          kind-name (if (= kind :dt/Class) "class" "property")]
      (when-not (and type (dt/type-isa? kind type))
        (throw (ex-info (str k " is not a " kind-name
                             (if type (str " (its dt/type is " type ")") " (it has no dt/type)"))
                        {:reasons  #{:introspection/wrong-kind}
                         :argument arg-name
                         :value    (str k)
                         :expected (str kind)
                         :actual   (some-> type str)})))
      k)))

(defn- introspection-class-arg
  "`class-arg` plus existence and kind resolution, for the class-introspection
   verbs only (see `registry-arg`)."
  [args]
  (registry-arg args "class" class-arg :dt/Class))

(defn- class-describe-handler [args]
  (let [c (introspection-class-arg args)]
    {:class      (str c)
     :abstract?  (boolean (dt/abstract? c))
     :parents    (mapv ->ident-str (dt/parents-of c))
     :ancestors  (mapv ->ident-str (dt/ancestors-of c))
     :subclasses (mapv ->ident-str (dt/subclasses-of c))
     :slots      (->> (dt/slots-of c) (map ->ident-str) sort vec)}))

(defn- class-slots-handler [args]
  (let [c (introspection-class-arg args)]
    {:class (str c)
     :slots (->> (dt/slots-of c) (map ->ident-str) sort vec)}))

(defn- class-direct-slots-handler [args]
  (let [c (introspection-class-arg args)]
    {:class (str c)
     :slots (->> (dt/direct-slots-of c) (map ->ident-str) sort vec)}))

(defn- class-required-slots-handler [args]
  (let [c (introspection-class-arg args)]
    {:class (str c)
     :slots (->> (dt/required-slots-of c) (map ->ident-str) sort vec)}))

(defn- class-instances-handler [args]
  (let [c              (class-arg args)
        _              (secq/assert-class-allowed! c) ; read-plane namespace firewall (no :auth/* dump)
        projection-raw (or (get args "projection") (get args :projection))
        ;; MCP boundary default per Gap 12 follow-on (B.3 — :projection opt
        ;; on bulky-response verbs).  Enumeration ships :metadata-only by
        ;; default for payload safety (10-300x reduction).  Symmetric with
        ;; search.bm25f + aggregate.rank-by.  Consumers opt to :full when
        ;; slot bodies are needed.
        projection-fn  (projection/projection-fn-for
                         (or (projection/->projection-mode projection-raw) :metadata-only))
        instances      (dt/all-instances-of c)]
    {:class     (str c)
     :instances (mapv projection-fn instances)}))

(defn- schema-entities-handler
  "Batch fetch entity-spec maps for non-abstract classes, optionally narrowed
   by :classes. Avoids one class.instances call per class."
  [args]
  (let [classes-arg (or (get args "classes") (get args :classes))
        target-classes (->> (cond
                              (sequential? classes-arg) (mapv ->ident classes-arg)
                              (some? classes-arg)       [(->ident classes-arg)]
                              :else                     (remove dt/abstract? (dt/all-classes)))
                            ;; SECURITY (read-plane firewall): never enumerate a
                            ;; firewalled-namespace class (:auth/* etc.) in the batch
                            ;; — closes the cardinality/existence oracle even for the
                            ;; default (no :classes) fetch.  Explicit firewalled
                            ;; :classes are already rejected by the central guard.
                            (filter secq/read-plane-namespace-allowed?))
        by-class (into {}
                       (for [cls target-classes]
                         [(->ident-str cls)
                          (mapv projection/full-projection (dt/all-instances-of cls))]))
        total    (reduce + (map count (vals by-class)))]
    {:by-class       by-class
     :total-classes  (count target-classes)
     :total-entities total}))

(defn- class-subclasses-handler [args]
  (let [c (introspection-class-arg args)]
    {:class (str c)
     :subclasses (->> (dt/subclasses-of c) (map ->ident-str) sort vec)}))

(defn- class-parents-handler [args]
  (let [c (introspection-class-arg args)]
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

(defn- introspection-property-arg
  "`property-arg` plus existence and kind resolution, for the three
   property-introspection verbs only (see `registry-arg`)."
  [args]
  (registry-arg args "property" property-arg :dt/Property))

(defn- property-domain-handler [args]
  (let [p (introspection-property-arg args)
        d (dt/domain-of p)]
    {:property (str p) :domain (some-> d ->ident-str)}))

(defn- property-range-handler [args]
  (let [p (introspection-property-arg args)
        r (dt/range-of p)]
    {:property (str p) :range (some-> r ->ident-str)}))

(defn- property-cardinality-handler [args]
  (let [p (introspection-property-arg args)
        c (dt/cardinality-of p)]
    {:property (str p) :cardinality (some-> c ->ident-str)}))

;; ---------- Entity operations ----------

(def ^:private mcp-default-actor
  "Layer-2-configured default actor ident (.sandbar/config.edn :default-actor)
   bound to `dt/*default-actor*` at the MCP boundary so `entity.create` auto-
   populates `:mm.memory/created-by` for :mm/Memory subclasses.  nil ⇒ unset
   (no created-by default — provenance never fabricated).  Read once via delay."
  (delay (try (cfg/config-value :default-actor) (catch Throwable _ nil))))

(defn- format-keyword
  "The codec format a client named, as the keyword the mediator registers.
   The verb card advertises the keyword spelling (`:markdown`) and clients
   send it as a string; `(keyword \":markdown\")` would yield `::markdown`,
   which no codec registers (found 2026-09-20 on the first write of the
   onboarding wave).  A leading colon is stripped; an already-bare name
   passes through unchanged."
  [format-arg]
  (if (keyword? format-arg)
    format-arg
    (keyword (str/replace (str format-arg) #"^:" ""))))

(defn- entity-create-handler [args]
  (let [class-arg   (or (get args "class") (get args :class))
        slots       (or (get args "slots") (get args :slots) {})
        ;; Codec arc Stage F.3a per
        ;; doc/concepts/codec-layer.md — optional
        ;; :format + :source opts for codec-driven entity construction.
        format-arg  (or (get args "format") (get args :format))
        source-arg  (or (get args "source") (get args :source))
        ;; D6 (2026-09-19): the interactive create is STRICT by default — an
        ;; unknown front-matter key, a dropped value or a wrong-typed value is
        ;; refused with a verdict before anything is built.  allow-unknown-keys
        ;; opts out into the lenient carrier behaviour of the bulk import.
        allow-unknown? (boolean (or (get args "allow-unknown-keys") (get args :allow-unknown-keys)))]
    (when (nil? class-arg)
      (throw (ex-info "Missing required argument: class" {:args args})))
    (let [class-ident (eref/resolve-ident class-arg)]
      (when (dt/abstract? class-ident)
        (throw (ex-info (str "Cannot instantiate abstract class: " class-ident)
                        {:class class-ident :reason :abstract})))
      (let [props-raw    (coerce-slot-map class-ident slots)
            ;; When format + source provided, dt/make's :format opt
            ;; parses via codec mediator; explicit slots override.
            ;; SHACL strict mode (RT-01, D6 2026-09-19): the mode is read BEFORE
            ;; the write so `:strict` validates on the SPECULATIVE database and
            ;; refuses with nothing committed, enqueued or notified; the results
            ;; are kept for the response.  `:audit` (the default) still commits
            ;; and reports afterwards; `:disabled` skips the shapes.
            mode-arg        (or (get args "validation-mode") (get args :validation-mode))
            validation-mode (or (some-> mode-arg keyword) :audit)
            strict-results  (atom nil)
            make-opts    (cond-> {}
                           (and format-arg source-arg)
                           (assoc :format (format-keyword format-arg)
                                  :source source-arg
                                  :strict? (not allow-unknown?))
                           (= :strict validation-mode)
                           (assoc :pre-commit (fn [db eid]
                                                (reset! strict-results (shape/validate db eid :strict)))))
            ;; Unified create path (2026-05-29 lifecycle-hardening arc):
            ;; sandbar.store/create-memory! derives an EDN-safe :db/ident from
            ;; :mm.memory/rel-path (when absent; :mm/Memory only — the Gap-17
            ;; auto-derivation, now digit-dodged) AND mints the opaque-stable
            ;; :mm/id, so MCP-authored memorials get full ζ identity at birth
            ;; rather than an ident-only (or identless) entity.  Replaces the
            ;; previous inline rel-path->ident derivation (which minted no :mm/id).
            new-entity   (binding [dt/*default-actor* @mcp-default-actor]
                           (store/create-memory! class-ident props-raw make-opts))]
        (log/info :MCP/entity-create
                  {:class  class-ident
                   :entity-id (:db/id new-entity)
                   :format (when format-arg (format-keyword format-arg))})
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
        ;; Stage 5 D5 — invalidate/refresh the BM25F search cache.
        ;; Per-entity hook; skipped (no-op) when the class has no
        ;; :dt/bm25f-weights declaration.  ASYNC since 2026-07-07
        ;; (arc/bm25f-async-index): entity-changed! is ~144ms+ (scales
        ;; with body size) and inline it caused MCP write-response
        ;; timeouts — enqueue and return; the read handlers await
        ;; quiescence.  See sandbar.search/entity-changed-async!.
        (try
          (search/entity-changed-async! class-ident new-entity)
          (catch Exception e
            (log/warn e :MCP/entity-create-cache-failed
                      {:class class-ident :entity-id (:db/id new-entity)})))
        ;; SHACL arc Stage E (2026-05-23) — shape validation.  `:strict`
        ;; already ran on the speculative database BEFORE the commit (the
        ;; :pre-commit above; a violation threw there and nothing was
        ;; committed — RT-01, D6 2026-09-19); its results are echoed here.
        ;; `:audit` (the default) runs post-commit: logs + returns the report,
        ;; never throws.  `:disabled` skips entirely.  Per plans/shacl_deeply_-
        ;; incorporated_capstone_activation_arc_2026_05_23.md §4.5.
        (let [shape-results   (if (= :strict validation-mode)
                                (or @strict-results [])
                                (try
                                  (shape/validate (db/db) (:db/id new-entity) validation-mode)
                                  (catch Throwable t
                                    (log/warn t :MCP/entity-create-shape-validation-error
                                              {:class class-ident :entity-id (:db/id new-entity)})
                                    [])))]
          (when (seq shape-results)
            (log/info :MCP/entity-create-shape-validated
                      {:class class-ident
                       :entity-id (:db/id new-entity)
                       :mode validation-mode
                       :result-count (count shape-results)
                       :failures (count (filter #(= :fail (:status %)) shape-results))}))
          (cond-> {:entity (projection/full-projection new-entity)}
            (seq shape-results) (assoc :shape-validation
                                       {:mode validation-mode
                                        :results shape-results})))))))

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
  (let [ident-or-id    (or (get args "ident") (get args :ident)
                           (get args "id")    (get args :id))
        projection-raw (or (get args "projection") (get args :projection))]
    (when (nil? ident-or-id)
      (throw (ex-info "Missing required argument: ident (or id)" {:args args})))
    (let [{:keys [valid? entity reasons]} (eref/validate ident-or-id)
          ;; Gap #1 fix 2026-05-27 — default to :metadata-only at the MCP
          ;; boundary (mirrors entity-update-handler Gap #7 fix landed
          ;; 2026-05-23).  Opt-in `:projection :full` still works for
          ;; consumers who want the body.  Per
          ;; doc/concepts/shape-validation.md §2
          ;; + doc/concepts/workflow-substrate.md Stage A.
          projection (or (projection/->projection-mode projection-raw)
                         :metadata-only)]
      (if valid?
        ;; SECURITY (read-plane namespace firewall): refuse to hand back a
        ;; firewalled-class entity (e.g. an :auth/* account) fetched by ident/eid.
        (do (secq/assert-entity-allowed! entity)
            (if (visibility/entity-visible-to? visibility/*principal* entity)
              {:entity (projection/apply-projection entity projection)}
              ;; D4b / CT-03: an entity whose compartment the principal does
              ;; not clear answers the SAME shape as an absent one (no
              ;; existence oracle), the decision resources/read applies.
              (visibility/missing-shape (str ident-or-id))))
        {:entity nil :missing? true :lookup (str ident-or-id) :reasons reasons}))))

(defn- entity-find-by-rel-path-handler [args]
  ;; Look up an :mm/Memory entity by its corpus rel-path.  Resolves the
  ;; rel-path → :memory.<dir>/<name> ident via the canonical codec
  ;; conversion (sandbar.codec.markdown/rel-path->memory-ident), then
  ;; delegates to eref/validate for the find-or-missing semantic.
  ;;
  ;; Eliminates the ident-guessing friction surfaced in the MCP cutover
  ;; exercise 2026-05-22 (Gap 1).  Consumers can now look up entities
  ;; by the filesystem path they actually have on hand (e.g.,
  ;; "doc/concepts/mcp-protocol.md") instead of
  ;; reverse-engineering the substrate's ident form.
  ;;
  ;; Accepts rel-paths with or without the leading "memory/" prefix.
  ;; Optional :projection per Gap 3 — default :full (single-entity).
  (let [rel-path       (or (get args "rel-path") (get args :rel-path))
        projection-raw (or (get args "projection") (get args :projection))]
    (when (nil? rel-path)
      (throw (ex-info "Missing required argument: rel-path" {:args args})))
    (let [ident (codec-md/rel-path->memory-ident rel-path)
          ;; Gap #1 fix 2026-05-27 — sibling of entity-find-handler fix.
          ;; Default :metadata-only at MCP boundary; opt-in `:projection :full`
          ;; preserved.  See entity-find-handler above for rationale.
          projection (or (projection/->projection-mode projection-raw)
                         :metadata-only)]
      (if (nil? ident)
        {:entity nil :missing? true :lookup rel-path
         :reasons #{:rel-path/unparseable}}
        (let [{:keys [valid? entity reasons]} (eref/validate ident)]
          (if valid?
            (if (visibility/entity-visible-to? visibility/*principal* entity)
              {:entity (projection/apply-projection entity projection)
               :resolved-ident (str ident)}
              ;; D4b / CT-03: uncleared answers as absent (see entity-find).
              (assoc (visibility/missing-shape rel-path) :resolved-ident (str ident)))
            {:entity nil :missing? true :lookup rel-path
             :resolved-ident (str ident) :reasons reasons}))))))

;; ---------- Codec + project-graph operations (Stage F.3b) ----------

(defn- codec-list-handler [_args]
  {:codecs (codec/list-codecs)})

(defn- reactive-health-handler [_args]
  ;; Project queue health timestamps to ISO-8601 strings for JSON.
  (let [h (reactive-queue/health)
        ->str (fn [^java.time.Instant inst]
                (when inst (.toString inst)))]
    (-> h
        (update :startup-instant       ->str)
        (update :last-enqueue-instant  ->str)
        (update :last-drain-instant    ->str))))

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
  (let [g #(if (contains? args (name %)) (get args (name %)) (get args %))]
    (project-export/export!
      {:project (->ident (g :project)) :destination (g :destination) :to (g :to)
       :filter (->filter-spec (g :filter))
       :dry-run (not (false? (g :dry-run))) :expect-plan (g :expect-plan)
       :provenance (true? (g :provenance))})))

(defn- project-recovery-check-handler [args]
  (let [g #(if (contains? args (name %)) (get args (name %)) (get args %))]
    (recovery-check/check
      {:project (->ident (g :project)) :destination (g :destination) :from (g :from)
       :expect-manifest (g :expect-manifest)})))

;; F#17 transact-boundary helpers (group-by-source + tempid-translation) moved
;; to `sandbar.codec.markdown/group-by-source` + `entity-specs->tx-data` per
;; 2026-05-20 consolidation.  Single source of truth at the codec layer per
;; doc/concepts/codec-layer.md
;; Callers below delegate to the codec helpers.

(defn- import-unit-summary
  "The wire shape of a source unit: source path, status, root class and ident,
   unknown front-matter keys retained by lenient parsing, and any parse error."
  [unit]
  (cond-> {:source (:source unit) :status (:status unit)}
    (:source-sha256 unit)      (assoc :source-sha256 (:source-sha256 unit))
    (:class unit)              (assoc :dt/type (:class unit) :ident (:ident unit))
    (seq (:unknown-keys unit)) (assoc :unknown-keys (:unknown-keys unit))
    (:error unit)              (assoc :error (:error unit))))

(defn- source-fingerprints
  "Sorted {source sha256} fingerprints covering every walked Markdown unit.
   An unreadable file has the value \"unreadable\". Non-Markdown entries are not
   source units."
  [units]
  (into (sorted-map)
        (for [u units :when (and (:source u) (or (not= :skipped (:status u)) (:excluded? u)))]
          [(:source u) (or (:source-sha256 u) "unreadable")])))

(defn- import-plan-identity
  "Identity and owner actually asserted by the plan, including absent-only
   defaults. The maintenance stamp consumes the successful unit's receipt."
  [plan]
  (let [root (first (:specs plan))]
    (cond-> {:identity-minted? (boolean (:identity-minted? plan))}
      (:mm/id root) (assoc :document-id (str (:mm/id root)))
      (:mm.memory/owning-project root) (assoc :owning-project (:mm.memory/owning-project root)))))

(defn- fingerprint-token
  "One hash over the sorted fingerprints — the preview's `:sources-sha256`."
  [fingerprints]
  (pg/sha256-hex (str/join "\n" (map (fn [[s h]] (str s " " h)) fingerprints))))

(defn- assert-sources-unchanged!
  "Refuse a persist whose input set differs from the pinned preview.
   expect-token is the preview's :sources-sha256; expect-hashes is its :sources
   map. Either suffices; the map reports which files changed, appeared or
   disappeared. Nothing has been transacted when this check throws."
  [sources token expect-token expect-hashes]
  (when (and expect-token (not= expect-token token))
    (throw (ex-info "project.import: the source files changed since the preview (fingerprint differs); preview again"
                    {:reasons #{:import/sources-changed} :expected expect-token :actual token})))
  (when (map? expect-hashes)
    (let [expected (into {} (map (fn [[k v]] [(name k) v])) expect-hashes)
          changed  (vec (sort (for [[s h] expected :when (and (contains? sources s) (not= h (get sources s)))] s)))
          added    (vec (sort (remove #(contains? expected %) (keys sources))))
          removed  (vec (sort (remove #(contains? sources %) (keys expected))))]
      (when (or (seq changed) (seq added) (seq removed))
        (throw (ex-info (str "project.import: the source files changed since the preview — changed "
                             (count changed) " (" (str/join ", " (take 5 changed)) "), appeared "
                             (count added) ", disappeared " (count removed) "; preview again")
                        {:reasons #{:import/sources-changed} :changed changed :added added :removed removed}))))))

(defn project-import-handler
  "Import Markdown source units, one file per transaction, and report every
   file's disposition. Persisted accounting reconciles attempted files with
   persisted, failed, refused, conflicting, parse-failed and skipped files.
   Unknown front-matter keys are retained by lenient parsing and reported.
   Without persist, report the plan and accounting without transacting."
  [args]
  (let [t-start     (System/currentTimeMillis)
        from        (or (get args "from") (get args :from))
        filter-spec (->filter-spec (or (get args "filter") (get args :filter)))
        persist?    (boolean (or (get args "persist?")
                                  (get args "persist")
                                  (get args :persist?)
                                  (get args :persist)))
        ;; REP-03 (D7, 2026-09-20): a file the substrate already holds is
        ;; REPLACED — its source-owned representation, sections and carrier
        ;; reconciled in one transaction — unless `mode` is `additive`.
        mode        (let [m (or (get args "mode") (get args :mode))]
                      (if (= "additive" (some-> m name)) :additive :replace))
        expect-basis (or (get args "expect-basis") (get args :expect-basis))
        ;; D7-R5 (Astra, 2026-09-20): the preview's source pin — its
        ;; `:sources-sha256` token or its `:sources` map — verified over this
        ;; call's own walk before any transaction; a file that changed,
        ;; appeared or disappeared since the preview refuses the whole call
        expect-sources (or (get args "expect-sources") (get args :expect-sources))
        expect-hashes  (or (get args "expect-source-hashes") (get args :expect-source-hashes))]
    (when-not from
      (throw (ex-info "project.import requires :from (input directory path)"
                      {:args args})))
    (when (and persist? expect-basis (not= (long expect-basis) (db/basis-t)))
      (throw (ex-info (str "project.import: the database moved since the preview (expected basis "
                           expect-basis ", now " (db/basis-t) "); preview again")
                      {:reasons #{:import/basis-moved} :expected expect-basis :actual (db/basis-t)})))
    (log/info :IMPORT/START {:from from :filter filter-spec :persist? persist?})
    (let [;; Destination ownership (onboarding wave, 2026-09-20): classify the
          ;; walked root against the operator's :project-roots map ONCE, before
          ;; any file is read — a misconfigured map refuses here — and verify
          ;; each unit's owner against it below, in the preview and the persist
          ;; alike, so a unit that would let a file choose another project's
          ;; tree is a reported conflict and never transacted.
          source-root  (import-destination/classify-root (db/db) from)
          ;; the plan first, then the destination conflicts decided from it
          ;; (owner, tree, and a mapped Project's routing key) merged into its own
          plan-unit    (fn [db-now group]
                         (let [group (import-destination/with-root-owner db-now source-root group)
                               p (import/plan-unit db-now group {:mode mode})]
                           (update p :conflicts into (import-destination/unit-conflicts db-now source-root p))))
          t-walk-start (System/currentTimeMillis)
          _ (log/info :IMPORT/WALK-START {:from from})
          excluded     (set (or (get args "exclude") (get args :exclude)))
          units        (->> (pg/ingest-units from (cond-> {}
                                                     filter-spec (assoc :filter filter-spec)))
                            ;; D7 maintenance import (2026-09-20): a unit named in
                            ;; `exclude` is walked and fingerprinted (it belongs to the
                            ;; covered input set) but neither planned nor transacted —
                            ;; a deferred row kept with both versions held.
                            (mapv (fn [u] (if (contains? excluded (:source u))
                                            (assoc u :status :skipped :excluded? true)
                                            u))))
          excluded-units (filterv :excluded? units)
          entities     (into [] (mapcat :entities) units)
          parsed       (filterv #(= :parsed (:status %)) units)
          parse-failed (into [] (comp (filter #(= :parse-failed (:status %)))
                                      (map import-unit-summary))
                             units)
          unknown      (into [] (comp (filter #(seq (:unknown-keys %)))
                                      (map import-unit-summary))
                             units)
          skipped      (count (filter #(contains? #{:filtered :skipped} (:status %)) units))
          accounting   {:attempted          (count units)
                        :imported           (count entities)
                        :parse-failed-count (count parse-failed)
                        :parse-failed       parse-failed
                        :unknown-keys-count (count unknown)
                        :unknown-keys       unknown
                        :skipped-count      skipped
                        :excluded-count     (count excluded-units)
                        :excluded           (mapv :source excluded-units)}
          t-walk-end   (System/currentTimeMillis)
          _ (log/info :IMPORT/WALK-DONE (assoc (dissoc accounting :parse-failed :unknown-keys)
                                               :units (count parsed)
                                               :ms (- t-walk-end t-walk-start)))
          sources       (source-fingerprints units)
          sources-token (fingerprint-token sources)
          _ (when persist? (assert-sources-unchanged! sources sources-token expect-sources expect-hashes))]
      (if-not persist?
        ;; Dry-run: the same accounting, entity summaries, nothing transacted.
        (do (log/info :IMPORT/DRY-RUN-COMPLETE {:entities (count entities)
                                                 :parse-failed (count parse-failed)
                                                 :ms (- (System/currentTimeMillis) t-start)})
            (let [db-now  (db/db)
                  planned (mapv (fn [u]
                                  (let [p (plan-unit db-now (:entities u))]
                                    (cond-> (merge (import-unit-summary u) (import-plan-identity p) {:mode (:mode p)})
                                      (= :replace (:mode p)) (assoc :retracted-sections (:retracted-sections p)
                                                                   :retracted-slots (:retracted-slots p)
                                                                   :retracted-slot-attrs (:retracted-slot-attrs p)
                                                                   :retracted-carrier? (:retracted-carrier? p))
                                      (seq (:conflicts p)) (assoc :conflicts (:conflicts p)))))
                                parsed)]
              (merge {:from     from
                     :filter   filter-spec
                     :persist? false
                     :mode     mode
                     ;; the basis the plans were computed against, not a later one (D7-R3)
                     :basis    (d/basis-t db-now)
                     ;; the source pin a persist hands back (D7-R5)
                     :sources  sources
                     :sources-sha256 sources-token
                     :source-root source-root
                     :units    planned
                     :conflict-count (count (filter :conflicts planned))
                     :entities (mapv (fn [e]
                                       {:dt/type (:dt/type e)
                                        :ident   (:db/ident e)})
                                     entities)}
                    accounting)))
        ;; Persist: ONE atomic transact per parsed source unit (per file).
        ;; Cross-entity refs (Memory ↔ Section) resolve via :db/ident upsert
        ;; within the single tx.  Per-unit failures isolated; one bad file
        ;; does NOT abort the whole import, and never rolls back a neighbour.
        (let [total   (count parsed)
              t-transact-start (System/currentTimeMillis)
              _ (log/info :IMPORT/TRANSACT-START {:units total})
              results (reduce
                       (fn [acc [idx unit]]
                         (when (zero? (mod idx 100))
                           (log/info :IMPORT/TRANSACT-PROGRESS
                                     {:idx idx :total total
                                      :persisted (count (:persisted acc))
                                      :failed (count (:failed acc))
                                      :ms (- (System/currentTimeMillis) t-transact-start)}))
                         (let [group    (:entities unit)
                               db-now   (db/db)
                               basis-now (d/basis-t db-now)
                               expected (:expected-basis acc)
                               base-rec {:dt/type       (:class unit)
                                         :ident         (:ident unit)
                                         :source        (:source unit)
                                         :source-sha256 (:source-sha256 unit)
                                         :tx-entities   (count group)}]
                           ;; An attended run advances its expected basis only through its OWN
                           ;; transactions (Astra, 2026-09-20): a write that is not ours, landing
                           ;; between one unit's commit and the next unit's planning, refuses the
                           ;; rest of the run unit by unit — never silently accepted by planning
                           ;; against whatever the database happens to be.
                           (if (and expected (not= basis-now expected))
                             (do (log/warn :IMPORT/UNIT-BASIS-MOVED-BEFORE-PLAN {:idx idx :source (:source unit) :expected expected :actual basis-now})
                                 (update acc :conflicts conj
                                         (assoc base-rec :conflicts [{:reason :basis-moved-before-plan :expected-basis expected :actual-basis basis-now}])))
                           (let [plan (plan-unit db-now group)
                                 rec  (cond-> (merge base-rec (import-plan-identity plan) {:mode (:mode plan)})
                                        (= :replace (:mode plan))
                                        (assoc :retracted-sections (:retracted-sections plan)
                                               :retracted-slots (:retracted-slots plan)
                                               :retracted-slot-attrs (:retracted-slot-attrs plan)
                                               :retracted-carrier? (:retracted-carrier? plan)))]
                           (try
                             (if (seq (:conflicts plan))
                               (do (log/warn :IMPORT/UNIT-CONFLICT {:idx idx :source (:source unit) :conflicts (:conflicts plan)})
                                   (update acc :conflicts conj (assoc rec :conflicts (:conflicts plan))))
                               (let [result (import/apply-plan! plan)]
                                 (-> acc
                                     (update :persisted conj rec)
                                     ;; our own transaction is the only advance an attended run accepts
                                     (assoc :expected-basis (d/basis-t (:db-after result))))))
                             (catch Throwable ex
                               ;; T-4: a FIREWALL refusal is shaped as :refused
                               ;; (a governed edge the import floor forbade),
                               ;; distinct from a generic :failed (schema/tx
                               ;; error).  Detect the :firewall-violation error
                               ;; envelope; everything else stays :failed.
                               ;; D7-R3: a plan refused because the database
                               ;; moved between planning and apply is a CONFLICT
                               ;; of the unit — reported, never replanned.
                               (let [data      (ex-data ex)
                                     moved?    (contains? (:reasons data) :import/basis-moved-during-apply)
                                     firewall? (and (instance? clojure.lang.ExceptionInfo ex)
                                                    (some #(= :firewall-violation (:type %))
                                                          (:errors data)))
                                     bucket    (cond moved? :conflicts firewall? :refused :else :failed)]
                                 (log/warn ex (cond moved?    :IMPORT/UNIT-BASIS-MOVED
                                                    firewall? :IMPORT/GROUP-REFUSED
                                                    :else     :IMPORT/GROUP-FAILED)
                                           {:idx idx :source (:source unit)
                                            :ident (:ident unit) :class (:class unit)})
                                 (update acc bucket conj
                                         (if moved?
                                           (assoc rec :conflicts (:conflicts data))
                                           (cond-> (assoc rec :error (.getMessage ex))
                                             firewall?
                                             (assoc :violations
                                                    (mapv #(select-keys % [:type :severity :reason :slot :target-ref :message])
                                                          (filter #(= :firewall-violation (:type %))
                                                                  (:errors data))))))))))))))
                       ;; the expected basis starts at the preview's pin when there is one, else
                       ;; at the first unit's own commit
                       {:persisted [] :failed [] :refused [] :conflicts [] :expected-basis (some-> expect-basis long)}
                       (map-indexed vector parsed))
              t-end   (System/currentTimeMillis)
              totals  {:attempted    (count units)
                       :persisted    (count (:persisted results))
                       :failed       (count (:failed results))
                       :refused      (count (:refused results))
                       :conflicts    (count (:conflicts results))
                       :parse-failed (count parse-failed)
                       :skipped      skipped}
              reconciled? (= (:attempted totals)
                             (+ (:persisted totals) (:failed totals) (:refused totals) (:conflicts totals)
                                (:parse-failed totals) (:skipped totals)))]
          (log/info :IMPORT/COMPLETE (assoc totals
                                            :imported    (count entities)
                                            :reconciled? reconciled?
                                            :total-ms    (- t-end t-start)
                                            :transact-ms (- t-end t-transact-start)))
          (merge {:from            from
                  :filter          filter-spec
                  :persist?        true
                  :mode            mode
                  :basis           (db/basis-t)
                  ;; one transaction unit per parsed file
                  :groups          total
                  :persisted       (:persisted results)
                  :persisted-count (count (:persisted results))
                  ;; REP-03: a unit refused for ambiguity (a class change, an
                  ;; identity conflict, a section another record references)
                  :conflict-count  (count (:conflicts results))
                  :conflicts       (:conflicts results)
                  ;; the basis the run's own last commit left (D7 2d, Astra's acceptance case)
                  :final-basis     (:expected-basis results)
                  :failed-count    (count (:failed results))
                  :failed          (:failed results)
                  ;; T-4: firewall-refused units surface separately from generic
                  ;; failures (a governed edge the import floor forbade).
                  :refused-count   (count (:refused results))
                  :refused         (:refused results)
                  :totals          totals
                  :reconciled?     reconciled?
                  :source-root     source-root}
                 accounting))))))

;; ---------- Aggregation operations (Stage 14 — fulltext arc Phase G) ----------
;;
;; Per fulltext arc Stage 14 of
;; doc/concepts/fulltext-search.md
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

(defn- ->rank-by-mode
  "Coerce a JSON-shaped `:rank-by` arg to the keyword form
   `sandbar.api.aggregate/rank-by` expects.  Accepts the four mode
   keywords (`:degree`, `:backlink-density`, `:recency`, `:freshness`)
   in either keyword or string form, with or without leading colon.
   Loud rejection for unknown modes — silent fallback would let
   degenerate calls succeed with surprising shapes.

   Before Gap 11 fix (2026-05-22): handler called `eref/resolve-ident`
   on the rank-by arg, treating mode keywords as entity idents.  Mode
   keywords (`:degree`, etc.) are NOT entities in the substrate; they
   are an axis-selector enum.  `eref/resolve-ident` rejected them as
   entity-not-found, blocking the verb entirely from MCP callers."
  [raw]
  (let [kw (cond
             (keyword? raw) raw
             (string? raw)  (keyword (clojure.string/replace raw #"^:" ""))
             :else
             (throw (ex-info (str "Unparseable :rank-by arg `" raw "`")
                             {:rank-by raw})))]
    (when-not (#{:degree :backlink-density :recency :freshness} kw)
      (throw (ex-info (str "Unknown :rank-by mode `" kw
                           "`.  Valid: :degree, :backlink-density, "
                           ":recency, :freshness.")
                      {:rank-by kw
                       :valid-modes #{:degree :backlink-density
                                      :recency :freshness}})))
    kw))

(defn- aggregate-rank-by-handler [args]
  (let [class-ident        (class-arg args)
        rank-by-raw        (or (get args "rank-by") (get args :rank-by))
        limit-arg          (or (get args "limit") (get args :limit))
        temporal-slot-raw  (or (get args "temporal-slot") (get args :temporal-slot))
        projection-raw     (or (get args "projection") (get args :projection))]
    (when (nil? rank-by-raw)
      (throw (ex-info "Missing required argument: rank-by" {:args args})))
    (let [rank-by-mode       (->rank-by-mode rank-by-raw)
          temporal-slot      (when (some? temporal-slot-raw)
                               (eref/resolve-ident temporal-slot-raw))
          ;; MCP boundary default per Gap 11 follow-on — exploration verbs
          ;; ship :metadata-only hits.  Avoids raw Datomic Entity values
          ;; reaching the safe-for-json fallback (which would render them
          ;; as toString'd strings).  Symmetric with search.bm25f.
          projection (or (projection/->projection-mode projection-raw) :metadata-only)
          opts (cond-> {:class class-ident :rank-by rank-by-mode :projection projection}
                 (some? limit-arg)     (assoc :limit limit-arg)
                 (some? temporal-slot) (assoc :temporal-slot temporal-slot))]
      (aggregate/rank-by opts))))

;; ---------- Search operations (Stage 5.B-pre — 0.1.1 co-evolution arc) ----------
;;
;; Per doc/concepts/mcp-protocol.md — MCP
;; boundary wrapper around `sandbar.search/search-bm25f`.  The Clojure
;; function has lived in `sandbar.search` since the fulltext arc Stage 4c;
;; this verb exposes it as an MCP tool so memory-model client slash
;; commands like `/memory-search` can dispatch via the MCP server.

(defn- aggregate-tag-histogram-handler [args]
  (let [limit-arg (or (get args "limit") (get args :limit))
        opts      (cond-> {} (some? limit-arg) (assoc :limit limit-arg))]
    (aggregate/tag-histogram opts)))

(defn- search-attribute-handler [args]
  (let [attribute-raw  (or (get args "attribute") (get args :attribute))
        query          (or (get args "query") (get args :query))
        limit-arg      (or (get args "limit") (get args :limit))
        projection-raw (or (get args "projection") (get args :projection))]
    (when (nil? attribute-raw)
      (throw (ex-info "Missing required argument: attribute" {:args args})))
    (when (nil? query)
      (throw (ex-info "Missing required argument: query" {:args args})))
    (let [attribute  (eref/resolve-ident attribute-raw)
          _          (secq/assert-attribute-allowed! attribute) ; read-plane firewall (no :auth/api-key-hash search)
          ;; B.3 — MCP boundary defaults to :metadata-only for the bulky
          ;; search-result case; symmetric with search.bm25f + class.instances
          ;; + aggregate.rank-by.
          projection (or (projection/->projection-mode projection-raw) :metadata-only)
          opts       (cond-> {:attribute attribute :query query :projection projection}
                       (some? limit-arg) (assoc :limit limit-arg))]
      (search/search-attribute opts))))

(defn- class-arg-multi
  "Shape-coerce the `class` arg for `sandbar.search.bm25f`, which
  accepts EITHER a single class-ident (string/keyword) OR a JSON array of
  class-ident strings (the multi-class strategic-subgroup vec form).

  Returns a single ident keyword for the scalar form, or a vec of ident
  keywords for the array form.  Throws ex-info on a missing `class` arg.
  Boundary validation (2..8 cap, non-empty, keyword membership) lives in
  `sandbar.search/search-bm25f-multi` — this helper is shape-coercion only,
  symmetric with `class-arg`."
  [args]
  (let [raw (or (get args "class") (get args :class))]
    (cond
      (nil? raw)         (throw (ex-info "Missing required argument: class" {:args args}))
      (sequential? raw)  (mapv ->ident raw)
      ;; A JSON array param may arrive STRING-encoded at the MCP boundary (some
      ;; clients serialize the array to a string); parse it via the same safe
      ;; edn reader so the D7 multi-class path works AND the read-plane firewall
      ;; sees a proper vec (not a garbage single pseudo-ident — the over-block).
      (and (string? raw) (str/starts-with? (str/trim raw) "["))
      (let [parsed (try (edn/read-string raw) (catch Exception _ nil))]
        (if (sequential? parsed) (mapv ->ident parsed) (->ident raw)))
      :else              (->ident raw))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read-plane INPUT firewall — CENTRAL dispatch-boundary class/attr-arg guard
;; (RPAF v3).  The complement to the projection-layer OUTPUT scrub: EVERY
;; non-registry-exempt verb's class/attribute args are validated in handle-call
;; BEFORE the handler runs — so class-arg oracles that return counts/booleans
;; with no entity to scrub (schema.entities cardinality, types.instance-of
;; membership) and any FUTURE class-arg verb are closed by construction
;; (deny-by-default: a new verb is guarded until explicitly exempted).  Entity /
;; anchor idents are handled by the per-handler `secq/assert-entity-allowed!`
;; (now :dt/type-only, so a metamodel DEF anchor passes, an instance rejects) +
;; the output scrub.  Per
;; doc/concepts/mcp-protocol.md
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private read-plane-registry-exempt-verbs
  "Metamodel introspection verbs that remain fully introspectable (the
  schema REGISTRY — class/property DEFINITIONS, never instance data; 2026-07-07).
  The central class-arg guard SKIPS these; EVERY other verb is guarded."
  #{"sandbar.schema.classes" "sandbar.schema.properties" "sandbar.schema.datatypes"
    "sandbar.class.describe" "sandbar.class.slots" "sandbar.class.direct-slots"
    "sandbar.class.required-slots" "sandbar.class.subclasses" "sandbar.class.parents"
    "sandbar.property.domain" "sandbar.property.range" "sandbar.property.cardinality"
    "sandbar.types.subclass-of" "sandbar.tools.search" "sandbar.tools.describe"})

(def ^:private read-plane-class-arg-keys
  ["class" "classes" "group-by" "target-type" "source-type" "root"])

(def ^:private read-plane-attr-arg-keys ["attribute"])

(defn- read-plane-arg->kw
  "Coerce a raw class/attribute arg value to a namespaced keyword for the
  firewall check.  ':x'/'x' string → :x; keyword passes.  Returns nil (SKIP —
  the per-handler guards + output scrub backstop) for a bare non-namespaced
  token, a JSON-array-encoded string (multi-class; handled by class-arg-multi +
  the search per-member guard), an eid number, nil, or a collection."
  [v]
  (cond
    (keyword? v) v
    (string? v)  (let [s (str/trim (str/replace v #"^:" ""))]
                   (when (and (str/includes? s "/") (not (str/starts-with? s "[")))
                     (keyword s)))
    :else        nil))

(defn- assert-read-plane-call!
  "Central read-plane INPUT firewall for a dispatched verb: reject a firewalled
  CLASS / group-by / target-type arg and a firewalled ATTRIBUTE arg BEFORE the
  handler runs.  No-op for registry-exempt (metamodel-shape) verbs.  Throws the
  loud `sandbar.security.query` ex-info, which handle-call maps to an isError
  envelope."
  [tool-name arguments]
  (when-not (contains? read-plane-registry-exempt-verbs tool-name)
    ;; args may be string- OR keyword-keyed at the MCP boundary (handlers read
    ;; both); check both forms so the guard never silently misses.
    (letfn [(arg [k] (or (get arguments k) (get arguments (keyword k))))]
      (doseq [k     read-plane-class-arg-keys
              :let  [raw (arg k)]
              v     (if (sequential? raw) raw [raw])]
        (some-> (read-plane-arg->kw v) secq/assert-class-allowed!))
      (doseq [k read-plane-attr-arg-keys]
        (some-> (read-plane-arg->kw (arg k)) secq/assert-attribute-allowed!)))))

(def ^:private +bm25f-read-barrier-timeout-ms+
  "Upper bound the MCP BM25F read handlers (search.bm25f / tag.lookup)
  wait for pending async index refreshes before serving.  Normally the
  refresh queue is empty (wait ≈ 0ms) or one write deep (~150ms); the
  bound only bites under bulk-write backlogs, where serving
  possibly-stale results beats blocking the read plane."
  3000)

(defn- search-bm25f-handler [args]
  (let [query           (or (get args "query") (get args :query))
        class-ident     (class-arg-multi args)
        limit-arg       (or (get args "limit") (get args :limit))
        where-raw       (or (get args "where") (get args :where))
        facet-by-raw    (or (get args "facet-by") (get args :facet-by))
        include-raw     (or (get args "include") (get args :include))
        field-wts-raw   (or (get args "field-weights") (get args :field-weights))
        from-raw        (or (get args "from") (get args :from))
        via-raw         (or (get args "via") (get args :via))
        rank-by-raw     (or (get args "rank-by") (get args :rank-by))
        temporal-raw    (or (get args "temporal-slot") (get args :temporal-slot))
        projection-raw  (or (get args "projection") (get args :projection))]
    (when (nil? query)
      (throw (ex-info "Missing required argument: query" {:args args})))
    (let [where     (->where-clauses where-raw)  ; shared edn-based parser (:293) — no bare read-string / reader-eval on the wire path (AP-S3-6 vector B)
          facet-by  (when facet-by-raw
                      (mapv eref/resolve-ident
                            (if (sequential? facet-by-raw) facet-by-raw [facet-by-raw])))
          include   (when include-raw
                      (mapv keyword
                            (if (sequential? include-raw) include-raw [include-raw])))
          field-wts (when (map? field-wts-raw)
                      (into {} (for [[k v] field-wts-raw]
                                 [(eref/resolve-ident k) (double v)])))
          from      (when from-raw (eref/resolve-ident from-raw))
          rank-by   (when rank-by-raw (keyword (clojure.string/replace (name (if (keyword? rank-by-raw)
                                                                               rank-by-raw
                                                                               (keyword rank-by-raw)))
                                                                     #"^:" "")))
          temporal  (when temporal-raw (eref/resolve-ident temporal-raw))
          ;; MCP boundary default per Gap 12 (substrate-stabilization arc
          ;; Phase 1 B.3) — exploration verbs ship :metadata-only hits;
          ;; consumers opt INTO :full when they need the body shape.
          ;; In-process callers still get :full by default (substrate
          ;; apply-projection's nil-default is :full per the legacy contract).
          ;; Avoids the 431KB payload friction surfaced during cutover.
          projection (or (projection/->projection-mode projection-raw) :metadata-only)
          opts (cond-> {:query query :class class-ident :projection projection}
                 (some? limit-arg) (assoc :limit limit-arg)
                 where             (assoc :where where)
                 facet-by          (assoc :facet-by facet-by)
                 (seq include)     (assoc :include include)
                 field-wts         (assoc :field-weights field-wts)
                 (and from via-raw) (assoc :from from :via via-raw)
                 rank-by           (assoc :rank-by rank-by)
                 temporal          (assoc :temporal-slot temporal))]
      ;; Read barrier (2026-07-07, arc/bm25f-async-index): writes enqueue
      ;; their BM25F refresh asynchronously; await quiescence (bounded)
      ;; so the MCP surface keeps read-your-writes semantics.  On timeout
      ;; we serve possibly-stale results rather than block the read plane.
      (search/await-bm25f-quiescent! +bm25f-read-barrier-timeout-ms+)
      (search/search-bm25f opts))))

;; ---------- Navigate edges (Stage 5.B-pre #2 — 0.1.1 co-evolution arc) ----------
;;
;; Per doc/concepts/mcp-protocol.md  Thin
;; MCP boundary wrappers around sandbar.navigate.edges/{inbound,outbound}-
;; edges.  Underpin /memory-xref + /memory-show slash commands.

(defn- ->predicate-keyword
  "Coerce a string-or-keyword predicate arg to a keyword without
  entity-existence validation.  Predicates are slot-idents (Datomic
  attribute-idents); they need not exist as standalone entities — the
  bare form `:cites` is valid as input even though there's no entity
  at `:cites` (the slot-ident on :mm/Memory is `:mm.memory/cites`,
  resolved downstream by `sandbar.navigate.edges/resolve-predicates`).

  Distinct from `eref/resolve-ident` which validates entity-existence
  and rejects bare predicate forms.  Per Gap 7 fix (MCP cutover
  exercise 2026-05-22) — the navigate handlers must NOT pre-validate
  predicate args as entities or they'd reject the bare forms the
  resolver is designed to accept."
  [raw]
  (cond
    (keyword? raw) raw
    (string? raw)  (keyword (clojure.string/replace raw #"^:" ""))
    :else          (throw (ex-info (str "Cannot coerce predicate arg to keyword: " raw)
                                   {:value raw}))))

(defn- parse-predicate-arg
  "Predicate arg may be a single keyword-string or a vec of keyword-strings.
   Coerce each to a keyword (no entity-existence validation — see
   `->predicate-keyword`).  Both bare forms (`:cites`) and fully-qualified
   forms (`:mm.memory/cites`) are accepted; bare forms are resolved to
   slot-idents downstream by the navigate/library-card layer."
  [raw]
  (when (some? raw)
    (cond
      (sequential? raw)
      (mapv ->predicate-keyword raw)

      ;; D7b (2026-09-20): a client whose cached tool schema predates the
      ;; `oneOf` string-or-array declaration stringifies an array argument
      ;; into "[\":a/b\", \":c/d\"]".  Keywordizing that whole string would
      ;; match no attribute and answer a silent zero — the very failure the
      ;; Gap 7 resolver exists to prevent — so a JSON-array-shaped string is
      ;; parsed as the array it carries.
      (and (string? raw) (str/starts-with? (str/trim raw) "["))
      (let [parsed (try (json/parse-string (str/trim raw))
                        (catch Exception e
                          (throw (ex-info (str "Predicate array could not be parsed: " raw)
                                          {:value raw :error (.getMessage e)}))))]
        (when-not (and (sequential? parsed) (every? string? parsed))
          (throw (ex-info (str "Predicate array must hold ident strings: " raw) {:value raw})))
        (mapv ->predicate-keyword parsed))

      :else
      (->predicate-keyword raw))))

(defn- navigate-outbound-edges-handler [args]
  (let [entity-raw       (or (get args "entity") (get args :entity))
        predicate-raw    (or (get args "predicate") (get args :predicate))
        target-type-raw  (or (get args "target-type") (get args :target-type))
        limit-arg        (or (get args "limit") (get args :limit))
        projection-raw   (or (get args "projection") (get args :projection))]
    (when (nil? entity-raw)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    ;; D7b (RT-14 item 4, 2026-09-20): the anchor resolves through
    ;; `eref/resolve` and travels as its eid, so an identless entity (a bare
    ;; tag value carrier) is a valid anchor; the read-plane class check is
    ;; unchanged.
    (let [anchor     (eref/resolve entity-raw)
          ;; SECURITY (read-plane namespace firewall): deny traversing FROM a
          ;; firewalled-class anchor (:auth/* etc.) or filtering TO a firewalled
          ;; target-type.  Guard by the entity's CLASS (:dt/type), never its
          ;; ident (corpus idents are :memory.*, not :mm.*).
          _          (secq/assert-entity-allowed! anchor)
          projection (projection/->projection-mode projection-raw)
          opts (cond-> {:entity (:db/id anchor)}
                 predicate-raw    (assoc :predicate (parse-predicate-arg predicate-raw))
                 target-type-raw  (assoc :target-type (secq/assert-class-allowed! (eref/resolve-ident target-type-raw)))
                 (some? limit-arg) (assoc :limit limit-arg)
                 projection       (assoc :projection projection))]
      (nav-edges/outbound-edges opts))))

(defn- navigate-inbound-edges-handler [args]
  (let [entity-raw       (or (get args "entity") (get args :entity))
        predicate-raw    (or (get args "predicate") (get args :predicate))
        source-type-raw  (or (get args "source-type") (get args :source-type))
        limit-arg        (or (get args "limit") (get args :limit))
        projection-raw   (or (get args "projection") (get args :projection))]
    (when (nil? entity-raw)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    ;; D7b (RT-14 item 4, 2026-09-20): the anchor resolves through
    ;; `eref/resolve` and travels as its eid — an identless entity is a valid
    ;; anchor — and bare predicates resolve against the source-type when
    ;; given, else schema-wide (see `nav-edges/resolve-predicates`).
    (let [anchor     (eref/resolve entity-raw)
          ;; SECURITY (read-plane namespace firewall): deny traversing INTO a
          ;; firewalled-class anchor or filtering by a firewalled source-type.
          _          (secq/assert-entity-allowed! anchor)
          projection (projection/->projection-mode projection-raw)
          opts (cond-> {:entity (:db/id anchor)}
                 predicate-raw    (assoc :predicate (parse-predicate-arg predicate-raw))
                 source-type-raw  (assoc :source-type (secq/assert-class-allowed! (eref/resolve-ident source-type-raw)))
                 (some? limit-arg) (assoc :limit limit-arg)
                 projection       (assoc :projection projection))]
      (nav-edges/inbound-edges opts))))

;; ---------- Tools meta-verbs (Phase 3 of the mm/Verb-maximization arc) ----------
;;
;; Metacircular self-introspection: the running MCP server SEARCHES + DESCRIBES
;; its OWN verb catalog (the :mm/Verb entities projected from `verb-catalog`).
;; tools.search = BM25F-retrieve the right verb for a task intent; tools.describe
;; = full card + typed composition edges (prereqs / combines-with / produces-
;; input-for) for one verb.  Graph-backed Tool Search — closes the loop: the
;; server serving verbs that query the entities projected from the catalog that
;; defines those very verbs.

(defn- verb-ref->ident
  "Coerce a verb reference to its :db/ident keyword.  Accepts a keyword, an ident
   string (\":sandbar.entity/create\"), or a wire name (\"sandbar.entity.create\")."
  [v]
  (cond
    (keyword? v)                   v
    (str/starts-with? (str v) ":") (keyword (subs (str v) 1))
    :else (let [segs (str/split (str v) #"\.")]
            (cond
              (>= (count segs) 3) (keyword (str (nth segs 0) "." (nth segs 1))
                                           (str/join "." (drop 2 segs)))
              (= (count segs) 2)  (keyword (nth segs 0) (nth segs 1))
              :else               (keyword (str v))))))

(defn- tools-search-handler
  "BM25F over the :mm/Verb catalog → lean verb cards ranked by task intent."
  [args]
  (let [query    (or (get args "query") (get args :query))
        limit    (or (get args "limit") (get args :limit) 10)
        axis-raw (or (get args "axis")  (get args :axis))]
    (when (nil? query)
      (throw (ex-info "Missing required argument: query" {:args args})))
    (let [axis  (when axis-raw (keyword (str/replace (str axis-raw) #"^:" "")))
          where (when axis [['?e :mm.verb/axis axis]])
          res   (search/search-bm25f (cond-> {:query query :class :mm/Verb
                                              :projection :full :limit limit}
                                       where (assoc :where where)))
          ;; nil-guard (defense-in-depth): project ONLY genuine :mm/Verb hits.
          ;; A hit whose entity lacks :mm.verb/name is non-verb residue and must
          ;; never render as an all-null verb card.  With the :class-scope fix
          ;; (search-bm25f now fail-closes on instances-of :mm/Verb) this residue
          ;; set is empty; the guard keeps the discovery surface honest even if a
          ;; future cache-pollution vector reappears.  Per
          ;; bugs/tools_search_axisless_returns_corpus_memorials_as_null_verb_cards_2026_07_10.md.
          verb-hits (filterv #(:mm.verb/name (:entity %)) (:hits res))
          cards (mapv (fn [{:keys [entity score]}]
                        {:verb            (:mm.verb/name entity)
                         :ident           (some-> (:db/ident entity) str)
                         :title           (:mm.verb/title entity)
                         :axis            (:mm.verb/axis entity)
                         :transition-kind (:mm.verb/transition-kind entity)
                         :read-only?      (:mm.verb/read-only? entity)
                         :arg-summary     (:mm.verb/arg-summary entity)
                         :score           score})
                      verb-hits)]
      ;; :total is the full verb-scoped match count (before :limit); :returned
      ;; is this page's guarded card count.  Post-fix the two agree when the
      ;; match-set fits under :limit.
      {:query query :matches cards :total (:total res) :returned (count cards)})))

(defn- tools-describe-handler
  "Full verb card + typed composition edges for one verb (by wire name or ident)."
  [args]
  (let [verb-raw (or (get args "verb") (get args :verb))]
    (when (nil? verb-raw)
      (throw (ex-info "Missing required argument: verb" {:args args})))
    (let [ident (verb-ref->ident verb-raw)
          {:keys [valid? entity reasons]} (eref/validate ident)]
      (if-not valid?
        {:verb nil :missing? true :lookup (str verb-raw)
         :resolved-ident (str ident) :reasons reasons}
        (let [v       (projection/full-projection entity)
              inbound (:edges (nav-edges/inbound-edges
                               {:entity (:db/id v) :predicate :mm.verb/prereq-of
                                :projection :metadata-only}))]
          {:verb               (:mm.verb/name v)
           :ident              (some-> (:db/ident v) str)
           :title              (:mm.verb/title v)
           :axis               (:mm.verb/axis v)
           :which              (:mm.verb/which v)
           :when               (:mm.verb/when v)
           :how                (:mm.verb/how v)
           :arg-summary        (:mm.verb/arg-summary v)
           :annotations        {:readOnlyHint    (:mm.verb/read-only? v)
                                :destructiveHint (:mm.verb/destructive? v)
                                :idempotentHint  (:mm.verb/idempotent? v)
                                :openWorldHint   (:mm.verb/open-world? v)
                                :transition-kind (:mm.verb/transition-kind v)
                                :hint-status     (:mm.verb/hint-status v)}
           :prerequisites      (mapv #(get-in % [:source :db/ident]) inbound)
           :prerequisite-for   (vec (:mm.verb/prereq-of v))
           :combines-with      (vec (:mm.verb/combines-with v))
           :produces-input-for (vec (:mm.verb/produces-input-for v))})))))

;; ---------- Navigation operations (Stage P-6 — fulltext arc Phase N) ----------
;;
;; Per fulltext arc Stage P-6 of
;; doc/concepts/fulltext-search.md
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
  (let [entity-arg     (or (get args "entity") (get args :entity))
        axes-arg       (or (get args "axes") (get args :axes))
        projection-raw (or (get args "projection") (get args :projection))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (when-not (sequential? axes-arg)
      (throw (ex-info "Missing or non-sequential argument: axes (must be array of axis-spec objects)"
                      {:args args})))
    (let [entity-ident (eref/resolve-ident entity-arg)
          _            (secq/assert-entity-allowed! (db/entity entity-ident)) ; read-plane firewall
          axes (mapv ->axis-spec axes-arg)
          projection (projection/->projection-mode projection-raw)
          opts (cond-> {:entity entity-ident :axes axes}
                 projection (assoc :projection projection))]
      (orient/library-card opts))))

(defn- navigate-siblings-of-handler [args]
  (let [entity-arg    (or (get args "entity") (get args :entity))
        path-slot-arg (or (get args "path-slot") (get args :path-slot))
        limit         (or (get args "limit") (get args :limit))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity" {:args args})))
    (when (nil? path-slot-arg)
      (throw (ex-info "Missing required argument: path-slot" {:args args})))
    (let [entity-ident (eref/resolve-ident entity-arg)
          _            (secq/assert-entity-allowed! (db/entity entity-ident)) ; read-plane firewall
          path-slot    (eref/resolve-ident path-slot-arg)
          opts (cond-> {:entity entity-ident :path-slot path-slot}
                 (some? limit) (assoc :limit limit))]
      (nav-siblings/siblings-of opts))))

(defn- navigate-path-via-handler [args]
  (let [from-arg       (or (get args "from") (get args :from))
        via-arg        (or (get args "via")  (get args :via))
        limit          (or (get args "limit") (get args :limit))
        include        (or (get args "include") (get args :include))
        projection-raw (or (get args "projection") (get args :projection))]
    (when (nil? from-arg)
      (throw (ex-info "Missing required argument: from" {:args args})))
    (when (nil? via-arg)
      (throw (ex-info "Missing required argument: via" {:args args})))
    (let [from-ident    (eref/resolve-ident from-arg)
          _             (secq/assert-entity-allowed! (db/entity from-ident)) ; read-plane firewall
          include-set   (when (sequential? include)
                          (set (map keyword include)))
          ;; Project once, while the endpoint is still a Datomic Entity.
          ;; Re-projecting a plain map loses the immutable ownership snapshot.
          opts          (cond-> {:from from-ident :via via-arg
                                 :projection (or (projection/->projection-mode projection-raw)
                                                 :metadata-only)}
                          (some? limit) (assoc :limit limit)
                          include-set   (assoc :include include-set))]
      (nav-path/path-via opts))))

(defn- entity-update-handler [args]
  ;; Stage I of doc/concepts/mcp-protocol.md
  ;; landed dt/update-entity!; this verb now wires through.  Per codex
  ;; SHOULD-FIX #5 (sandbar.entity.update advertised but unimplemented).
  ;;
  ;; Gap #6 fix (2026-05-23): use eref/resolve (returns canonical entity)
  ;; instead of eref/resolve-ident — supports identless entities (those
  ;; authored via codec ingest where :db/ident derivation didn't fire).
  ;; Substrate dt/update-entity! already accepts any ref-form including
  ;; raw entity-maps via (db/entity entity); only the MCP handler was
  ;; over-requiring ident.  See observations/entity_update_rejects_
  ;; identless_entities_substrate_gap_6_2026_05_23.md.
  ;;
  ;; Gap #7 fix (2026-05-23): default response :projection mode to
  ;; :metadata-only (was unconditional :full).  Large entities' full
  ;; projection overflowed the MCP tool-result wire limit, blocking
  ;; updates by overflowing the response (transaction commits OK but
  ;; client can't see the response shape).  Mirrors the projection
  ;; pattern already used by search.bm25f / class.instances etc.
  ;; Consumers opt to :projection :full when they want the body echo.
  (let [entity-arg     (or (get args "entity") (get args :entity))
        slot-arg       (or (get args "slots")  (get args :slots))
        projection-raw (or (get args "projection") (get args :projection))]
    (when (nil? entity-arg)
      (throw (ex-info "Missing required argument: entity (ident or eid)" {:args args})))
    (when (or (nil? slot-arg) (not (map? slot-arg)))
      (throw (ex-info "Missing or non-map argument: slots (must be {:slot-ident value ...} map)"
                      {:args args})))
    ;; Resolve the entity (works for identless too) so we can derive its
    ;; class for slot coercion (JSON-shaped values → Datomic-shaped via
    ;; dt/range-of).
    (let [entity-current  (eref/resolve entity-arg)
          class-ident     (dt/class-ident-of entity-current)
          slot-map        (coerce-slot-map class-ident slot-arg)
          ;; W0.found 2026-06-30 — cardinality-many slots REPLACE by
          ;; default; opt into legacy additive UNION via the bare `additive`
          ;; wire flag (the MCP property-key regex forbids `:`/`?`, so the
          ;; wire name is bare and maps to the in-process :additive? opt).
          ;; Per doc/concepts/memory-model.md
          additive?       (boolean (or (get args "additive") (get args :additive)))
          ;; SHACL strict mode (RT-01, D6 2026-09-19): the mode is read BEFORE the
          ;; write so `:strict` validates on the SPECULATIVE database and refuses
          ;; with nothing committed or enqueued; the results are kept for the
          ;; response.  `:audit` (the default) commits and reports afterwards.
          mode-arg        (or (get args "validation-mode") (get args :validation-mode))
          validation-mode (or (some-> mode-arg keyword) :audit)
          strict-results  (atom nil)
          updated         (dt/update-entity! entity-current slot-map
                                             (cond-> {:additive? additive?}
                                               (= :strict validation-mode)
                                               (assoc :pre-commit
                                                      (fn [db eid]
                                                        (reset! strict-results (shape/validate db eid :strict))))))
          projection-mode (or (projection/->projection-mode projection-raw)
                              :metadata-only)]
      ;; Stage 5 D5 — invalidate/refresh the BM25F search cache.
      ;; Per-entity hook; skipped (no-op) when the class has no
      ;; :dt/bm25f-weights declaration.  ASYNC since 2026-07-07
      ;; (arc/bm25f-async-index) — see the entity-create-handler note +
      ;; sandbar.search/entity-changed-async!.
      (try
        (search/entity-changed-async! class-ident updated)
        (catch Exception e
          (log/warn e :MCP/entity-update-cache-failed
                    {:class class-ident :entity-id (:db/id updated)})))
      ;; SHACL arc Stage E (2026-05-23) — shape validation, the mirror of the
      ;; entity-create-handler hook (Dan-directive 2026-05-23: 'wire up
      ;; entity-update too while we're here to surface friction early').
      ;; `:strict` already ran on the speculative database BEFORE the commit
      ;; (the :pre-commit above; a violation threw there and nothing was
      ;; committed — RT-01, D6 2026-09-19); its results are echoed here.
      ;; `:audit` (the default) runs post-commit and never throws; `:disabled`
      ;; skips entirely.
      (let [shape-results   (if (= :strict validation-mode)
                              (or @strict-results [])
                              (try
                                (shape/validate (db/db) (:db/id updated) validation-mode)
                                (catch Throwable t
                                  (log/warn t :MCP/entity-update-shape-validation-error
                                            {:class class-ident :entity-id (:db/id updated)})
                                  [])))]
        (when (seq shape-results)
          (log/info :MCP/entity-update-shape-validated
                    {:class class-ident
                     :entity-id (:db/id updated)
                     :mode validation-mode
                     :result-count (count shape-results)
                     :failures (count (filter #(= :fail (:status %)) shape-results))}))
        (cond-> {:entity (or (some-> (:db/ident updated) str)
                             (:db/id updated))
                 :slots  slot-map
                 :result (projection/apply-projection updated projection-mode)}
          (seq shape-results) (assoc :shape-validation
                                     {:mode validation-mode
                                      :results shape-results}))))))

(defn- entity-validate-handler [args]
  (let [class-arg (or (get args "class") (get args :class))
        slots     (or (get args "slots") (get args :slots) {})]
    (when (nil? class-arg)
      (throw (ex-info "Missing required argument: class" {:args args})))
    (let [class-ident  (eref/resolve-ident class-arg)
          props        (coerce-slot-map class-ident slots)
          schema-errs  (:errors (dt/validate-data class-ident props))
          ;; S7 advisory arm (CA-1.3 / R19): the read-only entity.validate verb
          ;; must surface the SAME firewall verdict the commit floor would throw,
          ;; so a caller cannot get a clean bill here and then have entity.create
          ;; refuse the identical spec.  Single-spec, nil spec-index (no batch),
          ;; targets resolved against the live db — the interactive EP-1 shape.
          fw-errs      (mapv fw-enforce/verdict->error
                            (:violations (fw-enforce/check-entity-flow
                                           (db/db) class-ident props)))
          ;; Sprint 2.4 (2026-09-18) advisory arm — the same future-timestamp
          ;; verdict the commit floor throws (dt/future-timestamp-guard! in
          ;; make / update-entity!), for the same reason as the firewall arm.
          ts-errs      (dt/future-timestamp-errors props)
          all-errs     (-> (vec schema-errs) (into ts-errs) (into fw-errs))]
      (if (seq all-errs)
        {:valid? false :errors {:errors all-errs}}
        {:valid? true}))))

;; ---------- Workflow operations ----------

(defn- workflow-arg [args]
  (or (->ident (get args "workflow"))
      (->ident (get args :workflow))
      (throw (ex-info "Missing required argument: workflow" {:args args}))))

(defn- ->workflow-kw
  "Coerce a workflow-spec keyword-shaped value (state name, transition name,
   from/to, terminal-kind) to a Clojure keyword.  Accepts already-keywords,
   string with leading colon (':session/opening'), or namespaced-name string
   ('session/opening').  Returns nil for nil; non-string/non-keyword inputs
   pass through unchanged (validator catches the type error downstream)."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) v
    (string? v)  (keyword (clojure.string/replace v #"^:" ""))
    :else        v))

(defn- coerce-workflow-spec
  "Walk a JSON-shaped workflow spec map and coerce keyword-shaped string
   values to Clojure keywords.  Handles both string-key (JSON-typical) and
   keyword-key (cheshire-coerced) maps for the nested state + transition
   entries.  Per Gap fix 2026-05-23 (sandbar.workflow.define handler).

   Coerces these fields:
     - :states[*].:name          (state ident)
     - :states[*].:terminal-kind (:success | :failure | :cancel)
     - :transitions[*].:name     (transition action)
     - :transitions[*].:from     (source state ident)
     - :transitions[*].:to       (target state ident)"
  [spec]
  (let [get*            (fn [m k] (or (get m k) (get m (name k))))
        coerce-state    (fn [s]
                          (cond-> s
                            (contains? s :name)          (assoc :name (->workflow-kw (:name s)))
                            (contains? s "name")         (-> (assoc :name (->workflow-kw (get s "name")))
                                                             (dissoc "name"))
                            (contains? s :terminal-kind) (assoc :terminal-kind (->workflow-kw (:terminal-kind s)))
                            (contains? s "terminal-kind") (-> (assoc :terminal-kind (->workflow-kw (get s "terminal-kind")))
                                                              (dissoc "terminal-kind"))))
        coerce-trans    (fn [t]
                          (cond-> t
                            (contains? t :name)  (assoc :name (->workflow-kw (:name t)))
                            (contains? t "name") (-> (assoc :name (->workflow-kw (get t "name")))
                                                     (dissoc "name"))
                            (contains? t :from)  (assoc :from (->workflow-kw (:from t)))
                            (contains? t "from") (-> (assoc :from (->workflow-kw (get t "from")))
                                                     (dissoc "from"))
                            (contains? t :to)    (assoc :to (->workflow-kw (:to t)))
                            (contains? t "to")   (-> (assoc :to (->workflow-kw (get t "to")))
                                                     (dissoc "to"))))
        states          (or (get* spec :states) [])
        transitions     (or (get* spec :transitions) [])]
    (-> spec
        (dissoc "states" "transitions")
        (assoc :states      (mapv coerce-state states))
        (assoc :transitions (mapv coerce-trans transitions)))))

(defn- workflow-define-handler [args]
  ;; Gap fix 2026-05-23 — handler was calling (define-workflow! spec) with
  ;; one arg, but the substrate fn expects [definition-name spec].  Plus the
  ;; nested state/transition keyword-shaped fields arrive as JSON strings;
  ;; coerce-workflow-spec normalizes them to actual keywords before transact.
  (let [spec (or (get args "spec") (get args :spec))]
    (when (nil? spec) (throw (ex-info "Missing required argument: spec" {:args args})))
    (let [raw-name (or (get spec "name") (get spec :name))
          _        (when (nil? raw-name)
                     (throw (ex-info "spec must contain :name (workflow definition ident)"
                                     {:spec spec})))
          definition-name (->workflow-kw raw-name)
          spec'           (coerce-workflow-spec spec)]
      {:workflow (workflow/define-workflow! definition-name spec')})))

(defn- workflow-find-handler [args]
  (let [w              (workflow-arg args)
        projection-raw (or (get args "projection") (get args :projection))
        ;; B.3 — single-entity verb defaults to :full (caller wants the
        ;; workflow definition body); consumers opt to :metadata-only
        ;; for lightweight existence-check use cases.
        projection-fn  (projection/projection-fn-for
                         (or (projection/->projection-mode projection-raw) :full))
        def            (workflow/find-workflow w)]
    {:workflow (str w) :definition (projection-fn def)}))

(defn- workflow-start-process-handler [args]
  (let [w           (workflow-arg args)
        subject-raw (or (get args "subject") (get args :subject))
        data        (or (get args "data") (get args :data) {})]
    (when (nil? subject-raw)
      (throw (ex-info "Missing required argument: subject (ident or eid)" {:args args})))
    ;; Gap fix 2026-05-23 — handler passed subject as a raw string; substrate
    ;; start-process! calls (:db/id subject) which returns nil for strings →
    ;; :db.error/nil-value at transact.  Resolve subject via eref to a real
    ;; entity first.  (Boundary owns boundary validation per
    ;; doc/concepts/memory-model.md Option B.)
    ;;
    ;; workflow/start-process! signature: [workflow subject & {:keys [data]}]
    ;; — :data is a KWARG, not positional.  Prior call `(start-process! w
    ;; subject data)` placed data in the rest-seq which never matched the
    ;; :data destructure, so user-supplied data was silently dropped
    ;; (ultrareview #5 at tools.clj:454).
    (let [subject (eref/resolve subject-raw)
          process (workflow/start-process! w subject :data data)]
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
  (let [w              (or (->ident (get args "workflow")) (->ident (get args :workflow)))
        projection-raw (or (get args "projection") (get args :projection))
        ;; B.3 — MCP boundary defaults to :metadata-only for the bulky
        ;; process-list case; symmetric with class.instances + search.bm25f.
        projection-fn  (projection/projection-fn-for
                         (or (projection/->projection-mode projection-raw) :metadata-only))]
    {:workflow  (when w (str w))
     :processes (mapv projection-fn
                      (if w
                        (workflow/active-processes :workflow w)
                        (workflow/active-processes)))}))

;; ---------- ι.3 substrate orchestrator (sandbar.workflow.orchestrate) ----------
;;
;; MCP-facing handler for the ι.3 substrate orchestrator implemented in
;; `sandbar.workflow.orchestrate`.  Closes the ι.4/ι.6 skill-rewrite dependency
;; surface — slash commands + AI clients can drive the session-lifecycle
;; ceremony via this verb per the ι.3 design ratification Q.ι.3.10 naming.
;;
;; Per Q.ι.3.1 (dual-surface design): the Clojure fn AND this MCP verb both
;; resolve to `orchestrate/orchestrate` — same code path, different entry.

(defn- lean-phase-work-result
  "Project an orchestrate phase-work result to a LEAN, JSON-safe wire view.
   The fat phases (:phase/orient, :phase/capture) carry full entity bodies
   (body-raw, full projections, top-N memorials) that the skill does NOT need
   once the banner is composed server-side — and shipping them over MCP risks
   offloading the tool-result to disk, which is the transcript-brick vector
   (see ~/claude/BRICK-RECOVERY.md).  The compact :banner string carries the
   human-readable corpus state; idents/counts let a client re-fetch on demand.
   Other phases pass through unchanged (their results are already small)."
  [phase r]
  (cond
    (nil? r) r

    (= phase :phase/orient)
    {:banner               (:banner r)
     :memory-count         (:memory-count r)
     :type-lattice         (:type-lattice r)
     :active-arc-count     (get-in r [:active-arc-forest :count])
     :active-task-count    (count (:active-tasks r))
     :active-process-count (count (:active-processes r))
     :prior-session-ident  (some-> (:prior-session r) :db/ident str)
     :prior-log-ident      (some-> (:prior-log r) :db/ident str)
     :in-flight-plan-ident (some-> (:in-flight-plan r) :db/ident str)}

    (= phase :phase/capture)
    (-> r
        (dissoc :recent-memorials)
        (assoc :recent-memorial-count (count (:recent-memorials r))
               :recent-memorials
               (mapv (fn [m]
                       {:db/ident (or (some-> (:db/ident m) str)
                                      (some-> (:db/id m) str))
                        :name     (:mm.memory/name m)})
                     (:recent-memorials r))))

    :else r))

(defn- orchestrate-handler [args]
  (let [workflow-ident (or (->ident (get args "workflow")) (->ident (get args :workflow)))
        process-id-raw (or (get args "process-id") (get args :process-id))
        phase          (->workflow-kw (or (get args "phase") (get args :phase)))
        context        (or (get args "context") (get args :context))
        actor-raw      (or (get args "actor") (get args :actor))
        reason         (or (get args "reason") (get args :reason))
        timeouts       (or (get args "timeouts") (get args :timeouts))
        audit?         (or (get args "audit-on-open")  (get args :audit-on-open)
                            (get args "audit-on-open?") (get args :audit-on-open?))]
    (when (nil? workflow-ident)
      (throw (ex-info "Missing required argument: workflow" {:args args})))
    (when (nil? phase)
      (throw (ex-info "Missing required argument: phase" {:args args})))
    ;; :process-id is OPTIONAL at the MCP boundary.  orchestrate/validate-args!
    ;; enforces it per-phase (:phase/orient + :phase/initialize are exempt via
    ;; phases-not-requiring-process-id).  Parse only when supplied; otherwise let
    ;; the orchestrator surface the canonical per-phase missing-arg error.  This
    ;; removes the sentinel (process-id 0) workaround clients previously needed.
    (let [process-id (when (some? process-id-raw)
                       (if (number? process-id-raw)
                         process-id-raw
                         (Long/parseLong (str process-id-raw))))
          actor      (when actor-raw (eref/resolve actor-raw))
          result     (orchestrate/orchestrate
                       (cond-> {:workflow workflow-ident
                                :phase    phase}
                         (some? process-id) (assoc :process-id process-id)
                         context            (assoc :context context)
                         actor              (assoc :actor actor)
                         reason             (assoc :reason reason)
                         timeouts           (assoc :timeouts timeouts)
                         (some? audit?)     (assoc :audit-on-open? audit?)))]
      ;; JSON-safe projection of the result — keywords preserved as strings;
      ;; eids preserved as numbers for clients that need to re-fetch the events.
      {:phase-completed    (str (:phase-completed result))
       :next-phase         (when-let [p (:next-phase result)] (str p))
       :transition-applied (mapv str (:transition-applied result))
       :events-emitted     (vec (:events-emitted result))  ;; numeric eids; JSON-safe
       :duration-ms        (:duration-ms result)
       :degraded?          (:degraded? result)
       :phase-work-result  (lean-phase-work-result phase (:phase-work-result result))})))

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
;; doc/guides/searching-the-corpus.md
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

(defn- ref-identity
  "The identity of a tag-side reference for the meaning view: eid, ident
   when interned, value when it carries one — enough to read or traverse
   from without a second lookup.  A string mapping target passes through
  ."
  [x]
  (cond
    (string? x)  x
    (keyword? x) (str x)
    (and (associative? x) (:db/id x))
    (cond-> {:eid (:db/id x)}
      (:db/ident x)     (assoc :ident (str (:db/ident x)))
      (:mm.tag/value x) (assoc :value (:mm.tag/value x)))
    :else        (str x)))

(defn- ref-identities
  "`ref-identity` over a cardinality-one value or a cardinality-many set."
  [xs]
  (if (or (set? xs) (sequential? xs))
    (mapv ref-identity xs)
    (ref-identity xs)))

(defn- tag-summary
  "Project a tag entity-map to the meaning view: identity (eid, ident when
   interned, value, whether it is a typed :mm/Tag) together with the
   documentation slots, the lifecycle (`:canonical?` present when asserted
   — true OR false — and absent when missing; `:lifecycle-status`; the
   successor under `:superseded-by`), the scheme, the SKOS mapping slots
   and the broader/related context — enough to identify, read and
   traverse from one result."
  [tag]
  (let [vals      (fn [xs] (vec (keep #(some-> % :mm.tag/value) xs)))
        canonical (get tag :mm.tag/canonical?)]
    (cond-> {:eid    (:db/id tag)
             :value  (:mm.tag/value tag)
             :typed? (some? (:dt/type tag))}
      (:db/ident tag)                  (assoc :ident (str (:db/ident tag)))
      (seq (:mm.tag/alt-label tag))    (assoc :alt-label    (vec (:mm.tag/alt-label tag)))
      (seq (:mm.tag/hidden-label tag)) (assoc :hidden-label (vec (:mm.tag/hidden-label tag)))
      (:mm.tag/definition tag)         (assoc :definition (:mm.tag/definition tag))
      (:mm.tag/scope-note tag)         (assoc :scope-note (:mm.tag/scope-note tag))
      (:mm.tag/example tag)            (assoc :example    (:mm.tag/example tag))
      (some? canonical)                (assoc :canonical? (boolean canonical))
      (:mm.tag/lifecycle-status tag)   (assoc :lifecycle-status (:mm.tag/lifecycle-status tag))
      (:mm.tag/superseded-by tag)      (assoc :superseded-by (ref-identities (:mm.tag/superseded-by tag)))
      (:mm.tag/in-scheme tag)          (assoc :in-scheme     (ref-identities (:mm.tag/in-scheme tag)))
      (seq (:mm.tag/exact-match tag))    (assoc :exact-match    (ref-identities (:mm.tag/exact-match tag)))
      (seq (:mm.tag/close-match tag))    (assoc :close-match    (ref-identities (:mm.tag/close-match tag)))
      (seq (:mm.tag/broader-match tag))  (assoc :broader-match  (ref-identities (:mm.tag/broader-match tag)))
      (seq (:mm.tag/narrower-match tag)) (assoc :narrower-match (ref-identities (:mm.tag/narrower-match tag)))
      (seq (:mm.tag/related-match tag))  (assoc :related-match  (ref-identities (:mm.tag/related-match tag)))
      (seq (:mm.tag/broader-generic tag))    (assoc :broader-generic    (vals (:mm.tag/broader-generic tag)))
      (seq (:mm.tag/broader-instantial tag)) (assoc :broader-instantial (vals (:mm.tag/broader-instantial tag)))
      (seq (:mm.tag/broader-partitive tag))  (assoc :broader-partitive  (vals (:mm.tag/broader-partitive tag)))
      (seq (:mm.tag/related tag))            (assoc :related            (vals (:mm.tag/related tag))))))

(defn- tag-identity-projection
  "The metadata-only lookup projection still carries a usable identity:
   the substrate-universal metadata plus `:eid` and `:value`."
  [tag]
  (assoc (projection/metadata-projection tag)
         :eid   (:db/id tag)
         :value (:mm.tag/value tag)))

(def ^:private +exact-label-attributes+
  "The label attributes the exact pass compares, with the match reason each
   one reports."
  {:mm.tag/value        :exact-value
   :mm.tag/alt-label    :exact-alt-label
   :mm.tag/hidden-label :exact-hidden-label})

(defn- exact-tag-matches
  "The exact pass of tag.lookup: every entity carrying `:mm.tag/value`
   whose value, alt-label or hidden-label equals `concept` (case-
   insensitive, trimmed) — typed `:mm/Tag` instances and the bare value
   carriers the class-scoped BM25F index cannot see alike.  Returns
   `[{:entity <entity-map> :reasons [<reason> ...]}]` in eid order; two
   concepts sharing an alternative label stay two candidates, each with
   its reason."
  [concept]
  (let [needle (str/lower-case (str/trim (str concept)))
        db-now (db/db)]
    (->> (for [[attr reason] +exact-label-attributes+
               datom         (d/datoms db-now :aevt attr)
               :when (= needle (str/lower-case (str (:v datom))))]
           [(:e datom) reason])
         (group-by first)
         (sort-by key)
         (mapv (fn [[e hits]]
                 {:entity  (db/entity e)
                  :reasons (vec (distinct (map second hits)))})))))

(defn- tag-population
  "The population a lookup searched, counted at read time: every entity
   carrying `:mm.tag/value`, the typed `:mm/Tag` instances among them, and
   the untyped carriers the exact pass alone can reach."
  [db-now]
  (let [carriers (count (d/q '[:find [?e ...] :where [?e :mm.tag/value]] db-now))
        typed    (count (d/q '[:find [?e ...] :where [?e :dt/type :mm/Tag]] db-now))
        untyped  (count (d/q '[:find [?e ...]
                               :where [?e :mm.tag/value]
                                      (not [?e :dt/type :mm/Tag])]
                             db-now))]
    {:value-carriers   carriers
     :typed-tags       typed
     :untyped-carriers untyped}))

(defn- tag-lookup-handler
  "Step 1 of the sandbar.ground compositional workflow: find an EXISTING
   vocabulary identity before proposing a new one.  Two passes: the exact
   pass compares the concept with every `:mm.tag/value` carrier's value,
   alt-label and hidden-label (typed or not); the conceptual pass is
   `sandbar.search/search-bm25f` over typed `:mm/Tag` instances with the
   `:dt/bm25f-weights` (value 12, alt-label 8, definition 6,
   scope-note 4, hidden-label 2, example 1).  Exact hits come first, each
   with its `:match-reason`; an entity both passes found appears once with
   both reasons.  The response states the population it searched.

   A read-barrier timeout or a search failure is an ERROR and propagates
   through the tool envelope; it is never reported as an empty vocabulary.
   A miss is `:gap? true` with the population named — a lexical miss in
   that population is not proof the concept is absent, and the hint no
   longer advises authoring a tag on that evidence alone."
  [args]
  (let [concept        (or (get args "concept") (get args :concept))
        limit          (or (get args "limit")   (get args :limit) 10)
        projection-raw (or (get args "projection") (get args :projection))]
    (when (str/blank? (str concept))
      (throw (ex-info "Missing required argument: concept" {:args args})))
    (let [;; :projection — `:full` is the meaning view (`tag-summary`), NOT the
          ;; generic full projection; `:metadata-only` still carries eid +
          ;; value so every projection supplies a usable identity.
          projection-mode (or (projection/->projection-mode projection-raw) :full)
          summarize       (case projection-mode
                            :full          tag-summary
                            :metadata-only tag-identity-projection)
          exact           (exact-tag-matches concept)
          ;; Read barrier — preserves the Gap-27 tag.define→tag.lookup
          ;; read-your-writes contract (the define-side refresh is async).
          ;; No catch: a failure here is an error, not a gap (RT-14 item 3).
          _               (search/await-bm25f-quiescent! +bm25f-read-barrier-timeout-ms+)
          {:keys [hits total]} (search/search-bm25f {:query concept
                                                     :class :mm/Tag
                                                     :limit limit})
          hit-by-eid      (into {} (map (fn [h] [(:db/id (:entity h)) h]) hits))
          exact-eids      (set (map (comp :db/id :entity) exact))
          matches         (-> []
                              (into (for [{:keys [entity reasons]} exact
                                          :let [hit (get hit-by-eid (:db/id entity))]]
                                      (cond-> (assoc (summarize entity)
                                                     :match-reason (cond-> reasons hit (conj :conceptual)))
                                        hit (assoc :score (:score hit)))))
                              (into (for [hit hits
                                          :when (not (exact-eids (:db/id (:entity hit))))]
                                      (assoc (summarize (:entity hit))
                                             :match-reason [:conceptual]
                                             :score        (:score hit)))))
          overlap         (count (filter exact-eids (map (comp :db/id :entity) hits)))
          population      (tag-population (db/db))]
      {:concept     concept
       :matches     matches
       :returned    (count matches)
       :match-total (+ (count exact) (max 0 (- (or total 0) overlap)))
       :population  population
       :method      (str "exact value, alt-label and hidden-label over every :mm.tag/value carrier "
                         "(typed or not), then BM25F over typed :mm/Tag instances")
       :gap?        (empty? matches)
       :gap-hint    (when (empty? matches)
                      (str "No exact label or conceptual match for \"" concept "\" among "
                           (:typed-tags population) " typed tags and "
                           (:untyped-carriers population) " untyped value carriers (a carrier is "
                           "matched by exact value only).  A miss in this population is not proof "
                           "the concept is absent from the corpus: search content with "
                           "sandbar.search.bm25f over :mm/Memory before treating the concept as new."))})))

(defn- tag-define-handler
  "Author a canonical tag or add supplied slots to an existing tag.
   upgrade? false rejects an existing value; upgrade? true uses the unique
   :mm.tag/value identity to update it. Definitions and scope notes explain
   meaning; the undefined-used audit identifies missing definitions."
  [args]
  (let [value     (or (get args "name") (get args :name))
        slots     (or (get args "slots") (get args :slots) {})
        upgrade?  (boolean (or (get args "upgrade")
                               (get args "upgrade?")
                               (get args :upgrade)
                               (get args :upgrade?)))]
    (when (str/blank? (str value))
      (throw (ex-info "Missing required argument: name" {:args args})))
    (let [existing (tag-by-value value)]
      (when (and existing (not upgrade?))
        (throw (ex-info (str "Tag already exists with value: " value)
                        {:value value
                         :existing-summary (tag-summary existing)
                         :hint "Pass :upgrade? true to add slots to existing tag, or use sandbar.tag.consolidate / .rename."})))
      ;; Honest-contract guard #1 — refuse a payload key that resolves to no
      ;; declared :mm/Tag slot BEFORE transacting.  `coerce-slot-map` would
      ;; drop it with only a server-side log; on the wire that reads as
      ;; success-with-a-lost-slot (the validates-then-silently-drops family,
      ;; per bugs/tag_define_upgrade_silently_drops_slots_payload_2026_07_03.md).
      (let [unmatched (unmatched-slot-keys :mm/Tag slots)]
        (when (seq unmatched)
          (throw (ex-info (str "tag.define: " (count unmatched)
                               " slot key(s) match no declared :mm/Tag slot "
                               "and would be silently dropped: "
                               (str/join ", " (map pr-str unmatched)))
                          {:value          value
                           :unmatched-keys (mapv str unmatched)
                           :declared-slots (mapv str (dt/slots-of :mm/Tag))
                           :hint "Use canonical :mm.tag/* slot names (definition, scope-note, example, alt-label, ...)."}))))
      (let [coerced (coerce-slot-map :mm/Tag slots)
            props   (merge {:mm.tag/value value} coerced)
            ;; dt/make on :mm/Tag uses Datomic :db.unique/identity
            ;; upsert via :mm.tag/value — same call path covers both
            ;; create + upgrade (named-tempid + upsert resolves to the
            ;; existing eid when the tag exists).
            new-ent (dt/make :mm/Tag props {})
            ;; Honest-contract guard #2 — re-read the entity FRESH from the
            ;; post-tx db and confirm every supplied slot actually persisted.
            ;; The upgrade path relies on :db.unique/identity upsert carrying
            ;; the slots onto the resolved eid; this proves it landed rather
            ;; than echoing `dt/make`'s in-memory return blind.  If any
            ;; supplied slot's value is absent on re-read the upgrade was a
            ;; silent drop — refuse loudly (the 68-shell WAVE-1 failure mode).
            persisted (tag-by-value value)
            dropped   (into {}
                            (keep (fn [[slot-ident wanted]]
                                    ;; Cardinality-aware landed? check: card-many
                                    ;; slots read back as a set, so require every
                                    ;; supplied member to be present; card-one
                                    ;; requires value equality.  `coerce-value`
                                    ;; already wrapped card-many values in a vec.
                                    (let [got     (get persisted slot-ident)
                                          landed? (if (dt/cardinality-many? slot-ident)
                                                    (let [got-set (set got)]
                                                      (every? got-set wanted))
                                                    (= got wanted))]
                                      (when-not landed?
                                        [slot-ident {:wanted wanted :got got}]))))
                            coerced)]
        (when (seq dropped)
          (throw (ex-info (str "tag.define: " (count dropped)
                               " slot(s) did not persist on upgrade of tag "
                               (pr-str value) " — payload silently dropped.")
                          {:value        value
                           :entity-id    (:db/id persisted)
                           :dropped-slots (into {} (map (fn [[k v]] [(str k) v]) dropped))
                           :hint "Substrate write did not land the supplied slots; use sandbar.entity.update on the tag eid as a fallback."})))
        (log/info :MCP/tag-define {:value value
                                   :entity-id (:db/id new-ent)
                                   :upgraded  (boolean existing)})
        ;; Gap 27 fix (2026-05-22) — fire entity-changed! hook so
        ;; the BM25F cache reindexes this tag.  Without this, tag.lookup
        ;; continues to miss the upgraded tag until the next full cache
        ;; rebuild.  Matches the equivalent fire in entity-create-handler.
        ;; ASYNC since 2026-07-07 (arc/bm25f-async-index); the Gap-27
        ;; define→lookup read-your-writes contract is preserved by the
        ;; quiescence barrier in tag-lookup-handler.
        (try
          (search/entity-changed-async! :mm/Tag new-ent)
          (catch Exception e
            (log/warn e :MCP/tag-define-cache-failed
                      {:value value :entity-id (:db/id new-ent)})))
        {:tag      (tag-summary persisted)
         :created  (not existing)
         :upgraded (boolean existing)}))))

(defn- tag-audit-handler
  "Run the full tag-lifecycle audit (sandbar.audit.tag/audit-all).  No
   arguments — runs all 7 invariants."
  [_args]
  (audit-tag/audit-all))

(defn- fs-substrate-drift-audit-handler
  "Run the file/database drift audit. Requires :from, the project or document
   root path. The report is diagnostic and does not perform repairs."
  [args]
  (let [from (or (get args "from") (get args :from))]
    (audit-fs-drift/audit-all {:from from})))

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

(defn- tag-consolidate-all-handler
  "Batch-merge a sequence of `:pairs` via tag.consolidate semantics.
   Each pair is `{from, into}` (or `{:from, :into}`).  Iterates in
   order; collects per-pair result; surfaces aggregate counts.

   Per Phase 2 cutover discipline 2026-05-22 — the 70 drift clusters
   surfaced by tag.harmonize need per-cluster consolidation; doing
   70 separate MCP calls is tedious + slow.  Batch verb amortizes
   the round-trip + transaction overhead.

   Failure semantics: per-pair errors collected as `{:from :into
   :error <message>}`; iteration continues (does NOT halt on first
   error).  Returns `{:results [<per-pair...>] :total :succeeded
   :failed :memorials-rewritten-total}` so consumers see the full
   picture.

   Per Gap 29 fix 2026-05-22 (substrate-stabilization arc Phase 2
   followup for the tag-vocabulary normalization arc)."
  [args]
  (let [pairs (or (get args "pairs") (get args :pairs))]
    (when (or (nil? pairs) (not (sequential? pairs)))
      (throw (ex-info "Missing or invalid required argument: pairs (must be sequential)"
                      {:args args})))
    (let [results
          (mapv
            (fn [pair]
              (let [from-val (or (get pair "from") (get pair :from))
                    into-val (or (get pair "into") (get pair :into))]
                (try
                  (cond
                    (str/blank? (str from-val))
                    {:from from-val :into into-val :error "Missing :from"}

                    (str/blank? (str into-val))
                    {:from from-val :into into-val :error "Missing :into"}

                    (= from-val into-val)
                    {:from from-val :into into-val :error ":from and :into must differ"}

                    :else
                    (let [from-ent (tag-by-value from-val)
                          into-ent (tag-by-value into-val)]
                      (cond
                        (nil? from-ent)
                        {:from from-val :into into-val :error (str "Tag not found: " from-val)}

                        (nil? into-ent)
                        {:from from-val :into into-val :error (str "Tag not found: " into-val)}

                        :else
                        (do
                          @(d/transact (db/conn)
                                       [[:db/add (:db/id into-ent) :mm.tag/alt-label from-val]
                                        [:db/add (:db/id from-ent) :mm.tag/lifecycle-status :superseded]
                                        [:db/add (:db/id from-ent) :mm.tag/superseded-by    (:db/id into-ent)]])
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
                            {:from from-val
                             :into into-val
                             :memorials-rewritten (count memorials-with-from)
                             :ok true})))))
                  (catch Throwable e
                    {:from from-val :into into-val :error (.getMessage e)}))))
            pairs)
          succeeded (count (filter :ok results))
          failed    (count (filter :error results))
          total-rw  (reduce + 0 (keep :memorials-rewritten results))]
      (log/info :MCP/tag-consolidate-all
                {:total (count pairs) :succeeded succeeded :failed failed
                 :memorials-rewritten-total total-rw})
      {:results                    results
       :total                      (count pairs)
       :succeeded                  succeeded
       :failed                     failed
       :memorials-rewritten-total  total-rw})))

(defn- tag-split-handler
  "Declare a partition into narrower tags. into-tags contains value and
   scope-note maps. Each new tag points to the parent through broader-generic.
   Existing record references need a separate, reviewed reassignment."
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
  "Record a SKOS mapping from a tag to an external IRI. The mapping slot
   references a :mm/Tag whose value is the IRI. This operation does not construct
   a complete Vocabulary or Linkset model."
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
  "Return a vocabulary audit and proposed consolidation clusters.
   The proposal is advisory; applying a reviewed merge is a separate operation."
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
  "Compose vocabulary discovery and suggested next steps.
   step-1-tag-lookup returns vocabulary matches; step-2-meta-vocab describes
   model vocabulary; step-3-suggested-next identifies possible follow-up calls.
   Callers still read candidate definitions and traverse relevant records."
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
       ;; D7b (RT-14 item 3, 2026-09-20): every suggestion is a CALLABLE
       ;; operation — a catalog verb name with arguments that can be passed
       ;; as written — and a miss in step 1 never recommends authoring a tag
       ;; on that evidence alone.
       :step-3-suggested-next
       (let [top    (first (:matches tag-result))
             anchor (when top (or (:ident top) (str (:eid top))))]
         (cond-> [{:verb "sandbar.search.bm25f"
                   :args {"class" ":mm/Memory" "query" concept}
                   :why  (if (:gap? tag-result)
                           "no label or concept matched in the stated population; content search decides whether the concept is present before it is treated as new"
                           "records whose content carries the concept, whatever their tags")}]
           top (conj {:verb "sandbar.navigate.inbound-edges"
                      :args {"entity"    anchor
                             "predicate" [":mm.memory/tags" ":mm.memory/themes"]}
                      :why  (str "the records the best match (" (:value top)
                                 ") classifies, by both membership relations; deduplicate sources by :db/id and keep :predicate as the role")})
           top (conj {:verb "sandbar.tag.lookup"
                      :args {"concept" (:value top) "projection" "full"}
                      :why  "the meaning view of the best match: definition, scope note, alternatives, scheme, lifecycle and successor"})))
       :note (str "Step 1 states the population it searched; step 3 names catalog verbs with "
                  "arguments that can be passed as written (D7b, 2026-09-20).")})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shape operations — SHACL arc Stage F (2026-05-23)
;;
;; Per doc/concepts/shape-validation.md §4.6.
;; Five verbs: shape.list / shape.validate / shape.conformance-report /
;; shape.create / shape.update.  The list / validate / conformance-report
;; verbs are shape-specific; create / update thin-wrap entity.{create,update}
;; with :class :mm/Shape pre-bound.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- shape-list-handler [args]
  ;; Per doc/concepts/mcp-protocol.md
  ;; — handlers route through dt/* not raw datomic.api.
  (let [applies-to-arg (or (get args "applies-to") (get args :applies-to))
        applies-to     (when applies-to-arg (eref/resolve-ident applies-to-arg))
        all-shapes     (dt/all-instances-of :mm/Shape)
        filtered       (if applies-to
                         (filter #(= applies-to (:db/ident (:mm.shape/applies-to %)))
                                 all-shapes)
                         all-shapes)
        shapes         (mapv projection/full-projection filtered)]
    {:applies-to-filter (when applies-to (str applies-to))
     :count             (count shapes)
     :shapes            shapes}))

(defn- shape-validate-handler [args]
  (let [entity-arg (or (get args "entity") (get args :entity))
        mode-arg   (or (get args "mode") (get args :mode))
        mode       (or (some-> mode-arg keyword) :audit)
        entity     (eref/resolve entity-arg)
        db         (db/db)
        results    (shape/validate db (:db/id entity) mode)]
    {:entity (str (or (:db/ident entity) (:db/id entity)))
     :mode   mode
     :result-count (count results)
     :results results}))

(defn- shape-conformance-report-handler [args]
  (let [class-arg   (or (get args "class") (get args :class))
        _           (when (nil? class-arg)
                      (throw (ex-info "Missing required argument: class" {:args args})))
        class-ident (eref/resolve-ident class-arg)
        db          (db/db)
        report      (shape/conformance-report db class-ident)]
    report))

(defn- shape-create-handler [args]
  ;; Thin wrapper over entity-create-handler with :class :mm/Shape pre-bound.
  (let [args' (assoc args "class" ":mm/Shape")]
    (entity-create-handler args')))

(defn- shape-update-handler [args]
  ;; Thin wrapper over the generic update path.  :entity must already
  ;; identify a :mm/Shape instance; we don't enforce class-check here
  ;; (the underlying update path will validate the slot map against the
  ;; entity's class).
  (let [entity-arg (or (get args "entity") (get args :entity))
        slots      (or (get args "slots") (get args :slots) {})
        _          (when (nil? entity-arg)
                      (throw (ex-info "Missing required argument: entity" {:args args})))
        entity     (eref/resolve entity-arg)
        updated    (dt/update-entity! (:db/id entity) slots)]
    {:entity (projection/full-projection updated)}))

(defn- entity-retract-handler [args]
  ;; First-class entity retraction — thin boundary over `sandbar.retract`.
  ;; Wire keys (per the ratified safety semantics):
  ;;   :targets (REQUIRED vec) — idents (keyword-strings) or numeric eids
  ;;   :persist  (bool, default false — dry-run unless true; NO `?` suffix
  ;;              per the Anthropic MCP property-key regex)
  ;;   :cascade  (bool, default false)
  ;;   :reason   (string; REQUIRED when :persist)
  ;;   :actor    (optional entity ref carried into the audit event)
  ;; The safety/report layer (cap, protected-skip, dry-run-default,
  ;; reason-required, atomic tx, audit event) lives in sandbar.retract —
  ;; this handler only marshals args + surfaces the report.
  (let [targets (or (get args "targets") (get args :targets))
        persist (boolean (or (get args "persist") (get args :persist)))
        cascade (boolean (or (get args "cascade") (get args :cascade)))
        reason  (or (get args "reason") (get args :reason))
        actor   (or (get args "actor")  (get args :actor))
        ack     (boolean (or (get args "acknowledge-dangling")
                             (get args :acknowledge-dangling)))]
    (when (nil? targets)
      (throw (ex-info "Missing required argument: targets (vec of idents or eids)"
                      {:args args})))
    (retract/retract! targets (cond-> {:persist persist :cascade cascade
                                       :acknowledge-dangling ack}
                                reason (assoc :reason reason)
                                actor  (assoc :actor actor)))))


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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ζ Scope B substrate-primitive verbs — namespace.policy + resolve
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per ζ Scope B ADR §6 (Q.ζ.B.8 + Q.ζ.B.9 RATIFIED 2026-05-26).
;; Build-prove-promote phase: BUILD here in sandbar; PROVE via MCP-client
;; consumption; PROMOTE patterns to broader libraries if/when stable.

(defn- namespace-policy-handler
  "Look up the :mm.namespace/CommitmentStatement entity for `:namespace`.
   ARK `??`-inflection pattern: 'what's the policy under this namespace?'

   `:namespace` is a string like \"decisions\" or \"libraries.clojure\".
   The handler queries for a CommitmentStatement entity whose rel-path
   matches `namespaces/<namespace>_commitment_*.md`.

   Read-only.  Authoring a new CommitmentStatement is via the standard
   sandbar.entity.create with class :mm.namespace/CommitmentStatement."
  [args]
  (let [ns-name (or (get args "namespace") (get args :namespace))]
    (when (str/blank? (str ns-name))
      (throw (ex-info "Missing required argument: namespace" {:args args})))
    (let [db    (db/db)
          ;; Query for CommitmentStatement entities whose rel-path starts
          ;; with namespaces/<ns>_commitment_
          rel-prefix (str "namespaces/" ns-name "_commitment_")
          matches    (d/q '[:find [?e ...]
                            :in $ ?prefix
                            :where [?e :dt/type :mm.namespace/CommitmentStatement]
                                   [?e :mm.memory/rel-path ?rp]
                                   [(clojure.string/starts-with? ?rp ?prefix)]]
                          db rel-prefix)]
      (if (empty? matches)
        {:namespace            ns-name
         :commitment-statement nil
         :commitment-statement-entity-ident nil
         :ark-question-inflection-form (str ns-name "/?")
         :note (str "No CommitmentStatement exists for namespace '" ns-name
                    "'.  Author via sandbar.entity.create with "
                    ":mm.namespace/CommitmentStatement class.")}
        (let [ent  (d/entity db (first matches))
              cs   {:identity-stability (:mm.namespace/identity-stability ent)
                    :content-stability  (:mm.namespace/content-stability ent)
                    :service-stability  (:mm.namespace/service-stability ent)
                    :authority-uuid     (:mm.namespace/authority-uuid ent)
                    :first-issued       (:mm.namespace/first-issued ent)}]
          {:namespace            ns-name
           :commitment-statement cs
           :commitment-statement-entity-ident (str (:db/ident ent))
           :ark-question-inflection-form (str ns-name "/?")
           :commitment-statement-name (:mm.memory/name ent)})))))


(defn- resolve-handler
  "Resolve an entity-reference of any wire form to its canonical entity.
   PURL-style indirection: federation-shaped references resolve to canonical
   entities regardless of which wire form was used.

   Accepted input forms (detected by pattern):
   - urn:uuid:<v5>          → lookup by :mm/id
   - :memory.X/Y (kw form)  → eref/resolve as ident
   - memory.X/Y  (str form) → coerced to keyword + resolved
   - <dir>/<slug>.md        → corpus rel-path lookup
   - <dir>/<slug>           → corpus rel-path lookup (extension optional)
   - numeric string         → eref/resolve as eid

   Returns: {:reference :resolved-entity :resolution-path}.
   :resolution-path is :urn-uuid | :substrate-ident | :rel-path | :eid."
  [args]
  (let [ref-str (or (get args "reference") (get args :reference))]
    (when (str/blank? (str ref-str))
      (throw (ex-info "Missing required argument: reference" {:args args})))
    (let [db     (db/db)
          result (cond
        ;; URN form: urn:uuid:<v5>
        (str/starts-with? ref-str "urn:uuid:")
        (let [uuid-str (subs ref-str (count "urn:uuid:"))
              uuid-val (try (java.util.UUID/fromString uuid-str)
                            (catch IllegalArgumentException _ nil))]
          (if uuid-val
            (let [eid (d/q '[:find ?e .
                             :in $ ?u
                             :where [?e :mm/id ?u]]
                           db uuid-val)]
              (if eid
                {:reference        ref-str
                 :resolved-entity  (into {} (d/entity db eid))
                 :resolution-path  :urn-uuid}
                {:reference ref-str
                 :resolved-entity nil
                 :resolution-path :urn-uuid
                 :error "No entity found with this :mm/id"}))
            {:reference ref-str
             :resolved-entity nil
             :resolution-path :urn-uuid
             :error "Invalid UUID in urn:uuid: form"}))

        ;; rel-path form: contains "/" + doesn't start with ":" + doesn't look like memory.X/Y
        (and (str/includes? ref-str "/")
             (not (str/starts-with? ref-str ":"))
             (not (str/starts-with? ref-str "memory.")))
        (let [;; Strip .md if present
              rel-path (if (str/ends-with? ref-str ".md")
                         ref-str
                         (str ref-str ".md"))
              ;; Convert dir/slug.md to :memory.<dir>/<slug>
              [dir slug] (str/split (subs rel-path 0
                                          (- (count rel-path) 3))  ; strip .md
                                    #"/" 2)
              ident-kw (when (and dir slug)
                         (keyword (str "memory." (str/replace dir "/" "."))
                                  slug))
              ent (when ident-kw
                    (try (eref/resolve ident-kw)
                         (catch Exception _ nil)))]
          (if (and ent (:db/id ent))
            {:reference       ref-str
             :resolved-entity (into {} ent)
             :resolution-path :rel-path
             :resolved-ident  (str ident-kw)}
            {:reference ref-str
             :resolved-entity nil
             :resolution-path :rel-path
             :error (str "No entity found for rel-path: " ref-str)}))

        ;; ident form: keyword or memory.X/Y string → eref/resolve
        :else
        (let [ent (try (eref/resolve ref-str)
                       (catch Exception e
                         (throw (ex-info (.getMessage e)
                                         (assoc (ex-data e)
                                                :reference ref-str
                                                :resolution-path :substrate-ident)))))]
          {:reference       ref-str
           :resolved-entity (into {} ent)
           :resolution-path :substrate-ident}))]
      ;; SECURITY (read-plane namespace firewall): refuse to resolve a reference
      ;; to a firewalled-class entity (:auth/* etc.).  Re-load by :db/id so the
      ;; class check sees a proper EntityMap (:dt/type), not the into-map form.
      (some-> (:resolved-entity result) :db/id db/entity secq/assert-entity-allowed!)
      result)))


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
    :description "WHICH: returns entity-spec maps grouped by class — one substrate round-trip instead of N+1 separate `sandbar.class.instances` calls.  Default fetches every non-abstract class's instances; optional `:classes` filter restricts to a specified subset.\n\nWHEN: use when you need to enumerate the substrate's entire entity state (or a slice across multiple classes) in one call — e.g., for bulk export, schema visualization, full-corpus reporting.  This verb exists specifically to eliminate the N+1 round-trip cost of iterating over classes and calling `sandbar.class.instances` per class.  When NOT to use: (a) you only need one class's instances — call `sandbar.class.instances` directly (smaller payload); (b) you need ranked / filtered instances — use `sandbar.aggregate.rank-by` or `sandbar.search.bm25f` instead.\n\nHOW: `:classes` (optional) is a JSON array of class-ident strings.  If omitted, fetches all non-abstract classes.  Returns `{:by-class {<class-ident-string> [<entity-map>...] ...} :total-classes <int> :total-entities <int>}`.\n\nORDER: discover classes via `sandbar.schema.classes` first (if you don't already know them).  After this call, you have the full per-class entity inventory; downstream operations (per-entity inspection, projection, etc.) follow.\n\nCOMBINATION: alternative to N × `sandbar.class.instances`.  Composes with downstream filtering: take the result's `:by-class` map and apply consumer-side predicates.  For structured filtering at the substrate, use `sandbar.aggregate.count` / `.group-by` with a `:where` Datalog clause instead."
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
    :description "WHICH: returns the comprehensive descriptor for a class — its abstract-flag, direct parents, all ancestors, direct subclasses, and effective slot set.  The 'inspect this class' verb.\n\nWHEN: use after picking a class from `sandbar.schema.classes` to understand its full shape before creating instances or composing queries.  Most useful first-touch verb for a new-to-you class.  When NOT to use: (a) only the slot set is needed — `sandbar.class.slots` (lighter); (b) only the hierarchy is needed — `sandbar.class.hierarchy` (via the REST endpoint; not in MCP catalog) or compose `parents` + `subclasses`; (c) you want the INSTANCES — `sandbar.class.instances`.\n\nHOW: `:class` is the class ident string (e.g. `:mm/Memory`).  Returns `{:class <ident-string> :abstract? <bool> :parents [...] :ancestors [...] :subclasses [...] :slots [...]}`.  `:slots` is sorted; `:subclasses` is in no particular order — compare it as a set.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class (a memorial, a property); earlier revisions answered an internal error or an empty descriptor.  Definitions stay introspectable for every namespace; this check adds existence and kind, not scope.\n\nORDER: typical sequence — `sandbar.schema.classes` (discover) → `sandbar.class.describe :class :foo/X` (this verb; inspect) → `sandbar.class.instances` or `sandbar.entity.create`.\n\nCOMBINATION: the slot list feeds `sandbar.property.{domain,range,cardinality}` for per-slot deep inspection.  Subclass list feeds polymorphic `sandbar.class.instances` calls.  Per-class typed-edge composition feeds `sandbar.orient.library-card` axis-specs."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-describe-handler}
   {:name "sandbar.class.slots"
    :title "Effective slot set of a class (inherited + directly-declared)"
    :description "WHICH: returns the sorted vec of slot idents that apply to instances of this class — including slots inherited from `:dt/subclass-of` ancestors PLUS slots declared directly on the class.\n\nWHEN: use to enumerate the FULL attribute surface available for instances of a class — for entity creation, validation, or query composition.  This is the 'what fields does this class have' question.  When NOT to use: (a) you want only the directly-declared slots (excluding inherited) — `sandbar.class.direct-slots`; (b) you want only REQUIRED slots — `sandbar.class.required-slots`; (c) you want to know which classes a property applies to — `sandbar.property.domain`.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`, sorted.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to escape as an internal error.\n\nORDER: foundational; no prerequisites beyond knowing the class ident (discover via `sandbar.schema.classes` if needed).\n\nCOMBINATION: feeds `sandbar.property.range` (per-slot value type for entity construction), `sandbar.entity.create` (slot map keys), and `sandbar.aggregate.group-by` (`:group-by` candidate slots).  For each slot's typed-edge nature (is it a `:db.type/ref` for navigation purposes?), call `sandbar.property.range`."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-slots-handler}
   {:name "sandbar.class.direct-slots"
    :title "Directly-declared slots only (no inheritance)"
    :description "WHICH: returns slots declared directly on `:class` — EXCLUDING inherited slots from `:dt/subclass-of` ancestors.\n\nWHEN: use when you need to know what THIS class adds beyond its parents — e.g., for class-evolution analysis, schema-shape comparison between siblings, or understanding the class's own contribution to the surface.  When NOT to use: (a) you want the full effective set (inherited + direct) — `sandbar.class.slots`; (b) you want just the required subset — `sandbar.class.required-slots`.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`, sorted; an empty vector means the class declares no slots of its own.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to answer an empty vector.\n\nORDER: no prerequisites.\n\nCOMBINATION: subtract from `sandbar.class.slots` result to derive the INHERITED slots.  Use alongside `sandbar.class.parents` to understand how the class augments its ancestors."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-direct-slots-handler}
   {:name "sandbar.class.required-slots"
    :title "Required slots of a class (`:dt/required? true`)"
    :description "WHICH: returns the subset of effective slots flagged `:dt/required? true` on `:class`.  Required slots must be supplied when creating instances via `sandbar.entity.create`.\n\nWHEN: use as a PREREQUISITE check before calling `sandbar.entity.create` — to confirm the slot map contains every required key.  Also useful for documentation generation and error-message authoring (\"missing required slot X\").  When NOT to use: (a) you want all slots — `sandbar.class.slots`; (b) you want validation FEEDBACK after providing a slot map — `sandbar.entity.validate` (pre-transaction validation with error details).\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :slots [<slot-ident-string>...]}`, sorted.  This is PROPERTY-LEVEL requiredness only — the slots whose property carries `:dt/required? true` (for example the four on `:mm/Project`: `corpus-repo`, `default-visibility`, `ident`, `runs-in-context`).  The memorial classes (Observation, Decision, Inbox, …) declare none at that level and answer an empty vector; their requirements (a name, a relative path, …) are enforced by shapes and the strict front matter at `sandbar.entity.create`, so an empty answer here does not mean a create will accept an empty slot map — use `sandbar.entity.validate` for that question.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to escape as an internal error.\n\nORDER: a pre-step before `sandbar.entity.create` for classes that declare property-level requirements; for a memorial class, `sandbar.entity.validate` is the pre-step that answers.\n\nCOMBINATION: pairs with `sandbar.entity.validate` (full pre-transaction validation including required-check AND type-conformance) and `sandbar.entity.create` (the actual mutation)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-required-slots-handler}
   {:name "sandbar.class.instances"
    :title "All instances of a class (incl. subclass instances)"
    :description "WHICH: returns every entity that is an instance of `:class` — directly OR via `:dt/subclass-of` (i.e., subclass instances are included; instance-of relation is transitive through inheritance).\n\nWHEN: use to enumerate a class's full instance population.  Foundational read for any class-based traversal.  When NOT to use: (a) the population is large and you only want top-K by some rank — `sandbar.aggregate.rank-by`; (b) you want only DIRECT instances (no subclass instances) — there's no MCP verb for this in the current catalog; substrate has `dt/direct-instances-of` accessible via in-process Clojure; (c) you want a count, not the entities — `sandbar.aggregate.count`; (d) you want to filter by some predicate — `sandbar.aggregate.count` / `.group-by` with `:where` Datalog, or `sandbar.search.bm25f` for fulltext-filtered instances.\n\nHOW: `:class` is the class ident.  Optional `:projection` — `metadata-only` (default at MCP boundary; `:db/id` + `:db/ident` + `:dt/type` per entity; 10-300x payload reduction) or `full` (all slots; recursive ref-projection to one-hop-deep metadata-only).  Returns `{:class <ident-string> :instances [<entity-map>...]}`.\n\nORDER: typical sequence — `sandbar.schema.classes` (discover class) → `sandbar.class.describe` (inspect) → `sandbar.class.instances` (this verb; enumerate).  No strict prerequisites.\n\nCOMBINATION: pairs with `sandbar.aggregate.rank-by` (rank the enumerated set), `sandbar.aggregate.group-by` (faceted counts), `sandbar.search.bm25f` (fulltext-search within a class's instances).  For batch fetch across multiple classes, use `sandbar.schema.entities` (N+1 elimination) instead."
    :inputSchema (one-required
                   (merge class-arg-schema
                          {:projection {:type "string"
                                        :description "Per-entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies."}})
                   [:class])
    :handler class-instances-handler}
   {:name "sandbar.class.subclasses"
    :title "All transitive subclasses of a class"
    :description "WHICH: returns the sorted vec of every class that is a `:dt/subclass-of` descendant of `:class` (direct + transitive).\n\nWHEN: use to discover the polymorphic surface of a class — what concrete classes might satisfy 'instance of `:class`'?  E.g., subclasses of `:dt/Resource` is essentially every domain class.  When NOT to use: (a) you want only DIRECT subclasses — no MCP verb in current catalog (substrate has `dt/direct-subclasses-of`); (b) you want to test 'is X a subclass of Y' specifically — `sandbar.types.subclass-of` predicate.\n\nHOW: `:class` is the parent class ident.  Returns `{:class <ident-string> :subclasses [<ident-string>...]}`, sorted; an empty vector means a leaf class.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to escape as an internal error.\n\nORDER: no prerequisites.\n\nCOMBINATION: with `sandbar.class.instances` over each subclass for instance enumeration; with `sandbar.aggregate.group-by` (`:group-by :dt/subclass-of`) for hierarchy distribution analysis."
    :inputSchema (one-required class-arg-schema [:class])
    :handler class-subclasses-handler}
   {:name "sandbar.class.parents"
    :title "Direct parents + transitive ancestors of a class"
    :description "WHICH: returns the class's direct `:dt/subclass-of` parents AND the full transitive-ancestor list (walks up to `:dt/Resource` typically).\n\nWHEN: use to understand a class's inheritance lineage — what slots / behaviors does it inherit?  Especially useful when debugging unexpected attribute behavior (slot might be declared on an ancestor).  When NOT to use: (a) you only want the DIRECT parents — currently returned alongside ancestors in this verb's result (filter result-side); (b) you want a subclass-of predicate test — `sandbar.types.subclass-of`.\n\nHOW: `:class` is the class ident.  Returns `{:class <ident-string> :parents [<direct-parents>...] :ancestors [<all-ancestors>...]}`; the root class answers two empty vectors.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a class; an unknown class used to answer two empty vectors.\n\nORDER: no prerequisites.\n\nCOMBINATION: pairs with `sandbar.class.direct-slots` per ancestor to trace inherited slot origins.  Use alongside `sandbar.types.subclass-of` for polymorphic-dispatch decisions."
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
    :description "WHICH: returns the declared domain class of a property — the class whose instances may carry this attribute (per RDFS / KL-ONE semantics).  For SPARQL-fluent readers: the `rdfs:domain` analogue.\n\nWHEN: use to discover which class a property applies to — useful when authoring `:where` clauses (you need to know which entity type carries the slot) or when validating that a slot map's keys are appropriate for a target class.  When NOT to use: (a) you want the VALUE type of the property — `sandbar.property.range`; (b) you want every slot on a class — `sandbar.class.slots`.\n\nHOW: `:property` is the attribute ident.  Returns `{:property <ident-string> :domain <class-ident-string-or-nil>}`.  `nil` means the property exists and declares no domain (for example a property of the bundled `twit` sample schema, whose properties declare none); it never means the property is unknown.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a property (a class, a memorial); an unknown property used to answer `nil`, indistinguishable from an undeclared domain.\n\nORDER: no prerequisites; foundational property-introspection call.\n\nCOMBINATION: triad with `sandbar.property.range` + `sandbar.property.cardinality` — all three together describe the property's shape.  Domain + range together let you reason about a typed-edge: 'edges of predicate :p go from class :D to class :R'."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-domain-handler}
   {:name "sandbar.property.range"
    :title "Value-type range of a property (`:dt/range` / `:db/valueType`)"
    :description "WHICH: returns the value-type range of a property — what kind of value the property holds.  For `:db.type/ref` properties, the range is a class ident (the target's class).  For primitive properties, the range is a `:db.type/*` keyword (`:db.type/string`, `:db.type/long`, etc.).\n\nWHEN: use when constructing slot maps for `sandbar.entity.create` (you need to know what type each slot expects) or when authoring path-grammar expressions (typed-edge predicates have `:db.type/ref` range; primitive-valued slots don't).  When NOT to use: (a) you want which class CARRIES the property — `sandbar.property.domain`; (b) you want every value type registered — `sandbar.schema.datatypes`.\n\nHOW: `:property` is the attribute ident.  Returns `{:property <ident-string> :range <class-or-datatype-ident-string>}`; `nil` means the property exists and declares no range.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a property; an unknown property used to answer `nil`.\n\nORDER: no prerequisites.  Critical pre-step for `sandbar.entity.create` slot construction.\n\nCOMBINATION: triad with `sandbar.property.domain` + `.cardinality`.  For ref-typed properties, the range class can be inspected via `sandbar.class.describe`.  Identifies which properties are typed-edges (range is a class) vs primitive-valued (range is `:db.type/*`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-range-handler}
   {:name "sandbar.property.cardinality"
    :title "Cardinality of a property (`:db.cardinality/one` or `/many`)"
    :description "WHICH: returns the cardinality of a property — either `:db.cardinality/one` (scalar; at most one value per entity) or `:db.cardinality/many` (set-valued; multiple values per entity).\n\nWHEN: use when constructing slot maps — `:cardinality/many` slots accept vec / set; `:cardinality/one` slots accept the scalar value directly.  Critical for `sandbar.entity.create` slot construction and for authoring `:where` Datalog clauses that traverse multi-cardinality attributes.  When NOT to use: (a) you want the value type — `sandbar.property.range`; (b) you want the domain — `sandbar.property.domain`.\n\nHOW: `:property` is the attribute ident.  Returns `{:property <ident-string> :cardinality <ident-string>}`; every installed attribute has a cardinality, so a `nil` here names a declared property that is not yet installed as a Datomic attribute.  REFUSES with `isError` (since 2026-09-20): an unknown ident, an ident without a namespace, an argument that is not an ident string, and an ident that names something other than a property; an unknown property used to answer `nil`.\n\nORDER: no prerequisites.\n\nCOMBINATION: triad with `sandbar.property.domain` + `.range`.  Many-cardinality ref properties (e.g., `:mm.memory/tags`) are natural targets for navigation verbs (`sandbar.navigate.outbound` with `:predicates [:mm.memory/tags]`)."
    :inputSchema (one-required property-arg-schema [:property])
    :handler property-cardinality-handler}

   ;; Entity operations
   {:name "sandbar.entity.create"
    :title "Create a typed entity with optional codec parsing"
    :description "WHICH: creates a new entity of `:class` from a slot map (or from a raw wire-format source via the codec mediator), checks its declared slot requirements, value types and cardinalities, and transacts it into the substrate.  The primary mutation verb.\n\nWHEN: use to bring a new entity into the substrate — whether constructing from explicit slot values (programmatic) or from a raw representation in a registered codec format (normally Markdown for `:mm/Memory`; discover available formats with `sandbar.codec.list`).  When NOT to use: (a) updating an EXISTING entity — `sandbar.entity.update`; (b) you want to validate without committing — `sandbar.entity.validate` (pre-transaction); (c) entity already exists and you want to read it back — `sandbar.entity.find`.\n\nHOW: `:class` is the target class ident (REQUIRED; abstract classes rejected).  `:slots` is a slot-ident-string → value map (optional if `:source` is provided).  `:format` + `:source` (both optional, must come together) invoke the codec mediator: `:source` is parsed as the named wire format (e.g., `:markdown`), parsed slots merge with explicit `:slots` (explicit wins on conflict).  Checks required slots, declared value types and cardinality before transacting; raises ex-info on validation failure. This pre-transaction slot check does not run class custom validators. A stored-entity audit through sandbar.class.validate-all-instances includes those validators; shape validation is a separate mechanism.  SHAPES (`:validation-mode`): `audit` (default) commits and returns the shape report; `strict` validates against the class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with nothing committed, enqueued or notified — a change accepted between the check and the commit is caught by a transactor-side basis guard and the check re-runs; `disabled` skips the shapes.  Bulk `sandbar.project.import` does not run shape validation. Use sandbar.shape.conformance-report for an explicit post-import shape audit; schema validation is a separate check.  STRICT FRONT MATTER: with `:format` markdown, an unknown front-matter key, an empty or mis-shaped value, or a value of the wrong primitive type is REFUSED before anything is built, with a verdict naming each refused key and its reason, the class's accepted keys, and the opt-out; nothing is committed, enqueued or notified.  `:allow-unknown-keys` true opts out: unknown keys ride in the front-matter carrier instead (the bulk-import behaviour).\n\nORDER: prerequisites — discover `:class` via `sandbar.schema.classes`; understand required slots via `sandbar.class.required-slots`; understand slot value types via `sandbar.property.range`.  Optional pre-check: `sandbar.entity.validate` (validates a slot map WITHOUT committing).\n\nCOMBINATION: paired with `sandbar.entity.validate` (pre-check), `sandbar.entity.find` (read back), `sandbar.entity.update` (subsequent mutations).  For codec-driven creation, ensure the codec is registered via `sandbar.codec.list`."
    :inputSchema (one-required
                   {:class  {:type "string" :description "Class ident (concrete, not abstract)"}
                    :slots  {:type "object" :description "Slot map (slot-ident-string → value); optional when :source is provided"}
                    :format {:type "string" :description "Optional codec format — one of the :format values sandbar.codec.list returns (e.g. markdown; a leading colon is tolerated); requires :source"}
                    :source {:type "string" :description "Optional raw native-representation string parsed via :format codec"}
                    :allow-unknown-keys {:type "boolean" :description "Opt out of strict front matter: carry unknown keys in the front-matter carrier instead of refusing them.  Default false."}
                    :validation-mode {:type "string" :description "Shape validation: 'audit' (default) commits and returns the shape report; 'strict' validates against the class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with nothing committed, enqueued or notified; 'disabled' skips the shapes."}}
                   [:class])
    :handler entity-create-handler}
   {:name "sandbar.entity.find"
    :title "Look up an entity by ident or eid"
    :description "WHICH: looks up an entity by `:ident` (interned keyword) or `:id` (numeric eid).  Returns the entity-map projection (`:db/id`, `:db/ident` if interned, namespaced-keyword slots).\n\nWHEN: use to fetch the current state of a known entity.  Most common 'read one entity' verb.  When NOT to use: (a) you want all instances of a class — `sandbar.class.instances`; (b) you want fulltext search — `sandbar.search.bm25f`; (c) you don't know the ident — discover via `sandbar.class.instances` first; (d) you have a corpus rel-path (e.g. 'decisions/foo.md') but not the ident — use `sandbar.entity.find-by-rel-path` instead.\n\nHOW: provide ONE of `:ident` (keyword-form string) OR `:id` (numeric eid).  IDENT FORM: corpus :mm/Memory entities use the `memory.`-prefixed namespace convention — e.g. `\":memory.decisions/foo\"` (NOT `\":decisions/foo\"`); `\":memory.notes.examples/sample\"` for nested dirs.  Metamodel idents (`:dt/Class`, `:mm/Memory`, `:mm.tag/value`, etc.) use their own namespaces and don't have the memory. prefix.  Returns `{:entity <entity-map>}` if found, or `{:entity nil :missing? true :lookup <provided> :reasons #{}}` if not found.  A memory-shaped entity whose confidentiality compartment the authenticated principal does not clear answers that SAME not-found shape (no existence oracle) — the one visibility decision `resources/read`, `resources/list` and the REST entity read also apply.\n\nORDER: leaf-call; no prerequisites.\n\nCOMBINATION: pre-step before `sandbar.entity.update` (confirm the entity exists); after `sandbar.entity.create` (read back the created entity, though create returns the entity directly so this is rarely needed).  For RELATED entities, use `sandbar.navigate.{outbound,inbound,siblings-of}` or `sandbar.orient.library-card`.  When you have a filesystem rel-path instead of an ident, use `sandbar.entity.find-by-rel-path` to avoid ident-guessing."
    :inputSchema {:type "object"
                  :properties {:ident      {:type "string" :description "Entity ident (keyword string, e.g. ':memory.decisions/foo' for corpus memories or ':dt/Class' for metamodel)"}
                               :id         {:type "integer" :description "Entity eid (numeric)"}
                               :projection {:type "string" :description "Entity shape: 'full' (default; complete entity-map) or 'metadata-only' (just :db/id/:db/ident/:dt/type — for lightweight pre-check / enumeration use cases)"}}
                  :required []}
    :handler entity-find-handler}
   {:name "sandbar.entity.find-by-rel-path"
    :title "Look up an :mm/Memory entity by corpus rel-path"
    :description "WHICH: looks up an :mm/Memory entity by its corpus rel-path (e.g. 'notes/example.md').  Resolves the rel-path to the substrate's `:memory.<dir>/<name>` ident via the canonical codec convention, then returns the entity-map projection.\n\nWHEN: use when you have a corpus filesystem path on hand and need the entity — without reverse-engineering the substrate's ident form.  The most common 'I know the file path, give me the entity' use case.  When NOT to use: (a) you already have the ident — `sandbar.entity.find` (slightly faster — skips rel-path parsing); (b) the entity isn't an :mm/Memory (e.g., :mm/Tag, :dt/Class) — those don't use the `memory.X/Y` ident convention so `sandbar.entity.find` with the appropriate ident is the right call; (c) fulltext search — `sandbar.search.bm25f`.\n\nHOW: `:rel-path` is the corpus rel-path string.  Accepts forms with or without the leading 'memory/' prefix: 'decisions/foo.md' AND 'memory/decisions/foo.md' both resolve to `:memory.decisions/foo`.  The .md extension is optional but conventional.  Returns `{:entity <entity-map> :resolved-ident <ident-string>}` if found, or `{:entity nil :missing? true :lookup <rel-path> :resolved-ident <ident-or-nil> :reasons <set>}` if not found.  The `:resolved-ident` field is included on both success and miss so consumers see what ident the rel-path mapped to.  An entity whose compartment the authenticated principal does not clear answers the miss shape (no existence oracle), as `sandbar.entity.find` does.\n\nORDER: leaf-call; no prerequisites.\n\nCOMBINATION: pairs with `sandbar.orient.library-card` / `sandbar.navigate.*` for typed-edge exploration once the entity is in hand."
    :inputSchema (one-required
                   {:rel-path   {:type "string" :description "Corpus rel-path (e.g. 'decisions/foo.md' or 'memory/decisions/foo.md'); leading 'memory/' and trailing '.md' optional"}
                    :projection {:type "string" :description "Entity shape: 'full' (default; complete entity-map) or 'metadata-only' (just :db/id/:db/ident/:dt/type)"}}
                   [:rel-path])
    :handler entity-find-by-rel-path-handler}
   {:name "sandbar.entity.update"
    :title "Update slots on an existing entity"
    :description "WHICH: applies slot-value updates to an existing entity.  Validates the updated slot map against the entity's class constraints before transacting.  SHAPES (`:validation-mode`): `audit` (default) commits and returns the shape report; `strict` validates the updated entity against its class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with the entity unchanged and nothing enqueued — a change accepted between the check and the commit is caught by a transactor-side basis guard and the check re-runs; `disabled` skips the shapes.  Accepts identful AND identless entities.\n\nWHEN: use to MUTATE an existing entity — change a slot value, set a previously-empty slot, etc.  When NOT to use: (a) creating a new entity — `sandbar.entity.create`; (b) you want to validate proposed updates WITHOUT committing — `sandbar.entity.validate` (against the class with the merged slot map); (c) you want to fully retract a cardinality-ONE slot — Datomic full-retraction is not exposed via MCP (note: cardinality-MANY members ARE removable now by passing a smaller set under the default replace semantics, or pass `additive: true` to only append).\n\nHOW: `:entity` is the target entity ident or eid (REQUIRED).  `:slots` is a slot-ident-string → new-value map (REQUIRED; non-map values rejected).  Substrate auto-coerces JSON-shaped values via `dt/range-of` (e.g., `:db.type/keyword` slots accept either keyword strings or already-coerced keywords).  Cardinality-many slots accept either a single value (wrapped to vec) or a vec / array, and by DEFAULT the supplied value REPLACES the slot's prior set — members absent from your value are retracted in the same tx, so you can now shrink or clear a card-many slot.  Pass `additive: true` to keep the legacy additive UNION (append without retracting).  Card-one slots are unaffected.  Optional `:projection` — `metadata-only` (DEFAULT — lightweight :db/id/:db/ident/:dt/type echo; avoids MCP wire-limit overflow on large entities) or `full` (complete entity-map; opt in when you want the body echo). Whole-body edits reconcile sections in the same transaction for Memory classes whose native body is :mm.memory/body-raw. Existing document identity and unrelated slots are retained. An externally referenced removed section, a foreign section identity collision, or a simultaneous document-ident change refuses the edit. A body edit is planned once at one database basis and given one guarded transaction, with speculative preflight only in strict mode; if the database moved in between (any writer, not necessarily on this memory), the edit is refused with `isError`, `body-update/basis-moved` in its details, `retryable? true`, and the instruction to read the memory again and retry — the fixed plan is neither reused nor silently replanned. That refusal is specific to body edits; the strict-mode preflight retry for generic strict writes (a moved basis re-runs the shape check, up to three attempts) is unchanged and does not apply to a body plan. Sectioned edits require a stable document ident; identless plain-body edits remain supported. This does not decompose initial entity.create bodies or reconcile other class-specific body slots.\n\nORDER: prerequisite — `sandbar.entity.find` to confirm the entity exists.  Optional pre-check: `sandbar.entity.validate` against the FULL merged slot map (current slots ∪ updates).\n\nCOMBINATION: pairs with `sandbar.entity.find` (pre-confirm + post-read-back).  For bulk class-wide updates, no single-call alternative; iterate `sandbar.class.instances` and apply per-entity."
    :inputSchema (one-required
                   {:entity     {:type "string" :description "Entity ident (keyword string) or eid (numeric).  Identless entities accepted by eid."}
                    :slots      {:type "object" :description "Slot-ident-string → new-value map"}
                    :projection {:type "string" :description "Response :result entity shape — 'metadata-only' (default; :db/id + :db/ident + :dt/type) or 'full' (complete entity-map; ~10-300x larger; risks wire-limit overflow on large bodies)."}
                    :additive   {:type "boolean" :description "Cardinality-many semantics.  DEFAULT false ⇒ the supplied value REPLACES the slot's prior set (members you omit are retracted).  true ⇒ legacy additive UNION (append the supplied value without retracting).  No effect on cardinality-one slots."}
                    :validation-mode {:type "string" :description "Shape validation: 'audit' (default) commits and returns the shape report; 'strict' validates the updated entity against its class's shapes on a SPECULATIVE database BEFORE the transaction and refuses a violation with the entity unchanged and nothing enqueued; 'disabled' skips the shapes."}}
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
    :description "WHICH: registers a new workflow definition from a spec.  A workflow is a named state machine — states (with terminal-kind classification: `:success` / `:failure` / `:cancel` for terminal states) + transitions (named actions moving between states, optionally guarded).\n\nWHEN: use to introduce a new state-machine model — order fulfillment, validation flow, approval pipeline, etc.  Workflows are entities in the substrate (queryable, evolvable).  When NOT to use: (a) inspecting an existing workflow — `sandbar.workflow.find`; (b) starting a process on an existing workflow — `sandbar.workflow.start-process`.\n\nHOW: `:spec` is a JSON object describing the workflow shape — `:workflow/states` vec with `:db/ident` + `:workflow/terminal-kind` (for terminals); `:workflow/transitions` vec with `:db/ident` + source/target state refs + optional guard.\n\nORDER: PRECEDES any `sandbar.workflow.start-process` for this workflow — the workflow must exist before processes can run.  Inspect existing workflows via `sandbar.workflow.find` to avoid duplicate idents.\n\nCOMBINATION: pairs with `sandbar.workflow.find` (lookup), `sandbar.workflow.start-process` (instantiate process), and the validation-service verbs (`sandbar.validation.*`) which are workflow-backed.  Workflows are visible as `:mm/Workflow` instances via `sandbar.class.instances :class :mm/Workflow`."
    :inputSchema (one-required {:spec {:type "object" :description "Workflow spec (states + transitions)"}} [:spec])
    :handler workflow-define-handler}
   {:name "sandbar.workflow.find"
    :title "Look up a workflow definition by ident"
    :description "WHICH: returns the entity-map of a workflow definition (its states + transitions + metadata) given the workflow ident.\n\nWHEN: use to inspect an existing workflow — discover its state-machine shape before starting a process or analyzing process histories.  When NOT to use: (a) you want all workflows — `sandbar.class.instances :class :mm/Workflow`; (b) you want process-state inspection — `sandbar.workflow.process-state`.\n\nHOW: `:workflow` is the workflow ident string.  Optional `:projection` — `full` (default; single-entity lookup ships the full definition) or `metadata-only` (lightweight existence check).  Returns `{:workflow <ident-string> :definition <entity-map>}`.\n\nORDER: typical sequence — `sandbar.class.instances :class :mm/Workflow` (discover) → `sandbar.workflow.find :workflow :foo/wf` (inspect).\n\nCOMBINATION: pairs with `sandbar.workflow.start-process` (start a new process against this definition) and `sandbar.workflow.active-processes` (current processes against this workflow)."
    :inputSchema (one-required
                   {:workflow   {:type "string"}
                    :projection {:type "string"
                                 :description "Definition entity shape — 'full' (default; complete entity-map) or 'metadata-only' (lightweight; :db/id + :db/ident + :dt/type only)."}}
                   [:workflow])
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
    :description "WHICH: returns the list of currently-active (non-terminal-state) workflow processes — every process that's currently running.  Optional `:workflow` filter restricts to processes against a specific workflow definition.\n\nWHEN: use to enumerate live state-machine flows — dashboards, oncall views, 'what's currently in flight'.  When NOT to use: (a) one specific process — `sandbar.workflow.process-state`; (b) finished processes — query via `sandbar.class.instances :class :workflow/Process` + filter terminal states.\n\nHOW: `:workflow` (optional) restricts to one workflow definition's processes.  Without it, returns active processes across ALL workflows.  Optional `:projection` — `metadata-only` (default at MCP boundary) or `full`.  Returns `{:workflow <ident-or-nil> :processes [<process-entity-map>...]}`.\n\nORDER: leaf-call.\n\nCOMBINATION: pairs with `sandbar.workflow.process-state` (drill into one) + `sandbar.workflow.transition` (advance one)."
    :inputSchema {:type "object"
                  :properties {:workflow   {:type "string" :description "Optional workflow ident to filter by"}
                               :projection {:type "string"
                                            :description "Per-process entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies."}}
                  :required []}
    :handler workflow-active-processes-handler}

   {:name "sandbar.workflow.orchestrate"
    :title "session-workflow orchestrator — drive a workflow.process through a phase of its ceremony"
    :description "WHICH: invokes the session-workflow orchestrator (`sandbar.workflow.orchestrate/orchestrate`) on a workflow.process — drives it through ONE phase of its ceremony per the canonical phase vocabulary.  Returns the phase outcome including the workflow.transition(s) applied, events emitted, duration, and degraded-path flag.\n\nWHEN: use to advance a session-lifecycle workflow.process through its phases (orient → initialize → activate → imprint for opening a session; capture → author → link → finalize for handing off a session).  One MCP call per phase — the client iterates over `open-phases` / `handoff-phases` and invokes this verb for each.  When NOT to use: (a) direct workflow.transition application without the orchestrator's phase semantics — use `sandbar.workflow.transition`; (b) inspecting current state — `sandbar.workflow.process-state`; (c) starting a process — `sandbar.workflow.start-process` (this verb assumes the process already exists).\n\nHOW: `:workflow` is the workflow definition ident (REQUIRED; typically `:workflow/session`).  `:process-id` is the numeric workflow.process eid (REQUIRED).  `:phase` is the phase keyword (REQUIRED; one of `:phase/orient` / `:phase/initialize` / `:phase/activate` / `:phase/imprint` for open OR `:phase/capture` / `:phase/author` / `:phase/link` / `:phase/finalize` for handoff).  Optional: `:context` (map passed to phase-work + transition guards/effects), `:actor` (ident or eid for workflow.history actor slot), `:reason` (string for transitions whose `:workflow/requires-reason?` is true), `:timeouts` (per-phase override map; falls back to `default-phase-timeouts-ms`), `:audit-on-open` (bool; invoke audit_fs-substrate-drift in :phase/orient — default false).  (Wire-format key MUST be `audit-on-open` — no `?` suffix — to comply with Anthropic MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`.  Handler accepts both `audit-on-open` and legacy `audit-on-open?` for back-compat.)\n\nORDER: PREREQUISITE — workflow.process must exist (created via `sandbar.workflow.start-process`).  Phases SHOULD be invoked in canonical order per ceremony (orient → initialize → activate → imprint).  No strict enforcement — caller MAY skip phases for testing or re-invoke a phase, subject to the underlying workflow.transition guard constraints.\n\nCOMBINATION: pairs with `sandbar.workflow.start-process` (creates the process this verb drives), `sandbar.workflow.process-state` (read current state between phase calls), `sandbar.workflow.process-history` (audit transitions after orchestrate completes).\n\nReturns: `{:phase-completed :next-phase :transition-applied :events-emitted :duration-ms :degraded? :phase-work-result}` — see the `sandbar.workflow.orchestrate/orchestrate` Clojure fn docstring for slot semantics."
    :inputSchema (one-required
                   {:workflow       {:type "string"  :description "Workflow definition ident (typically ':workflow/session')"}
                    :process-id     {:type "integer" :description "Numeric workflow.process eid"}
                    :phase          {:type "string"  :description "Phase keyword — one of :phase/orient :phase/initialize :phase/activate :phase/imprint (open ceremony) OR :phase/capture :phase/author :phase/link :phase/finalize (handoff ceremony)"}
                    :context        {:type "object"  :description "Optional context map passed to phase-work + transition guards/effects"}
                    :actor          {:type "string"  :description "Optional actor ident/eid for the workflow.history actor slot"}
                    :reason         {:type "string"  :description "Optional reason string for transitions whose :workflow/requires-reason? is true"}
                    :timeouts       {:type "object"  :description "Optional per-phase timeout override map (else default-phase-timeouts-ms applies)"}
                    :audit-on-open  {:type "boolean" :description "Optional — invoke audit_fs-substrate-drift in :phase/orient (default false).  Note: wire-format key MUST be `audit-on-open` (no `?` suffix) per Anthropic MCP property-key regex; handler accepts legacy `audit-on-open?` for back-compat."}}
                   ;; :process-id is NOT universally required — :phase/orient (pure-read)
                   ;; and :phase/initialize (creates the process) are exempt per
                   ;; orchestrate/phases-not-requiring-process-id.  Marking it required
                   ;; here forced clients to pass a sentinel (process-id 0) for those
                   ;; phases; the orchestrator enforces it per-phase instead.
                   [:workflow :phase])
    :handler orchestrate-handler}

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
    :description "WHICH: cancels a running validation workflow — transitions the process to a `:workflow/terminal-kind :cancel` terminal state.\n\nWHEN: use to abort a long-running validation that's no longer needed or that's running against stale data.  When NOT to use: (a) the validation already finished — no-op (or rejected); (b) you want to RETRY after failure — `sandbar.validation.retry`.\n\nHOW: `:validation-id` is the numeric process eid.  Returns `{:cancelled <result>}`.\n\nORDER: only meaningful for running processes (use `sandbar.workflow.process-state` to confirm state before cancelling).\n\nCOMBINATION: pairs with `sandbar.workflow.process-state` (confirm in-flight) and `sandbar.validation.history` (audit cancelled runs).  Cancellation follows a transition to a state with the cancel terminal kind."
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
    :description "WHICH: returns the codecs currently registered with the Sandbar codec mediator — one entry per codec, each `{:format <keyword> :mime-types [<string>...]}`, sorted by format.  The `:format` value (for example `:markdown`, serialised as `\"markdown\"`) is exactly the spelling `sandbar.entity.create` accepts as its `:format` argument (a leading colon is tolerated there); there is no `:codec/` namespace, and the entry carries no per-codec class list — a class's codec is its own `:dt/native-codec` declaration.\n\nWHEN: use to discover what wire formats Sandbar can parse / emit.  Foundational for codec-driven entity construction (`sandbar.entity.create` with `:format` + `:source`) and for projection/ingestion (`sandbar.project.export` / `.import` choose codecs per class's `:dt/native-codec`).  When NOT to use: (a) you want a specific class's declared native codec — `sandbar.entity.find` on the class ident and read its `:dt/native-codec` slot (`sandbar.class.describe` does not return it); (b) you want to register a NEW codec — not exposed via MCP; programmatic Clojure call against `sandbar.codec`.\n\nHOW: no arguments.  Returns `{:codecs [<codec-info>...]}`.\n\nORDER: foundational discovery.\n\nCOMBINATION: pairs with `sandbar.entity.create` (use `:format` + `:source` opts with one of the listed codec keywords) and `sandbar.project.export` / `.import` (codecs underpin the bidirectional projection)."
    :inputSchema {:type "object" :properties {} :required []}
    :handler codec-list-handler}
   {:name "sandbar.reactive.health"
    :title "Reactive-projection pipeline health snapshot (queue depth, throughput, error counts)"
    :description "WHICH: returns a snapshot of the reactive-projection pipeline's health metrics — queue depth, dirty-entity count, throughput counters (enqueue / drain / coalesce), sink-error count, saturation flag, lifecycle timestamps.\n\nWHEN: use for substrate-health monitoring during reactive-projection work — diagnosing queue backpressure, verifying the worker is running, checking whether the dirty-set is draining cleanly.  When NOT to use: (a) you want the per-event log timeline — read sandbar.log for `:REACTIVE/<event-name>` records; (b) you want to check the registered callback / sink count specifically — those counters are in the response but `sandbar.reactive/callback-count` + `sandbar.reactive.queue/sink-count` (in-process API) give direct access.\n\nHOW: no arguments.  Returns:\n  - `:worker-running?` — bool (was `(reactive-queue/start!)` called?)\n  - `:buffer-size` — int (sliding-buffer capacity)\n  - `:dirty-entity-count` — distinct entities currently pending projection\n  - `:oldest-pending-age-ms` — int or nil (lag indicator)\n  - `:enqueue-total` / `:drain-total` / `:coalesce-total` — cumulative counters since startup\n  - `:sink-error-total` — cumulative sink-fn failures\n  - `:registered-sinks` — sink count\n  - `:saturated?` — bool (oldest-pending-age-ms exceeds threshold)\n  - `:startup-instant` / `:last-enqueue-instant` / `:last-drain-instant` — ISO-8601 timestamps\n\nORDER: leaf-call; no prerequisites beyond sandbar being up.\n\nCOMBINATION: composes with `:REACTIVE/<event-name>` log records (timeline forensics)."
    :inputSchema {:type "object" :properties {} :required []}
    :handler reactive-health-handler}
   {:name "sandbar.project.export"
    :title "Preview or export an enrolled project's eligible documents"
    :description "WHICH: guarded Markdown export of source-owned Memory documents and their owned sections. Explicit project and operator-configured destination are required. The caller's clearance, destination audience, emitted references, identity round trip and fresh staging paths are checked before any output file. Unsafe documents are held whole; originals remain unchanged. This is not a complete database backup or semantic prose declassification.\n\nHOW: preview is the default (dry-run true). It writes a private audit, returns counts, basis and plan-token, and creates no staging output. Inspect that audit using the operator's private audit directory. Execute with identical arguments, dry-run false and expect-plan equal to that token. A changed basis, selection or destination requires another preview. Optional filter only narrows the selected project. The to path must be a fresh direct child beneath the named destination's staging root. No overwrite or source-root export is allowed.\n\nRESULT: status is preview, complete or incomplete; only complete? true establishes completion. Planned holds are counted separately from write failures. A complete output contains export-manifest.edn with file hashes and an opaque private audit reference. Partial output has no valid completion manifest. Exact held identities and reasons remain in the private audit. Optional provenance requests a DB Run only when server recording is also enabled; privacy checks always apply.\n\nORDER: use the attended stopped-writer procedure through preview, execution and audit. Publication, import, git and recovery are separate operator actions."
    :inputSchema {:type "object" :additionalProperties false
                  :properties
                  {:project {:type "string" :description "Explicit enrolled Project entity ident"}
                   :destination {:type "string" :description "Operator-configured export destination name"}
                   :to {:type "string" :description "Fresh absolute child of the authorized staging root"}
                   :dry-run {:type "boolean" :default true}
                   :expect-plan {:type "string" :description "Preview token required for execution"}
                   :provenance {:type "boolean" :default false}
                   :filter {:type "object" :description "Optional narrowing class, classes or tree-filter"}}
                  :required ["project" "destination" "to"]}
    :handler project-export-handler}
   {:name "sandbar.project.recovery-check"
    :title "Compare a completed export's trusted origin with the same existing database"
    :description "WHICH: read-only origin comparison of one completed guarded export with the current database. It verifies the tree's exact bytes and population against export-manifest.edn (the same check as `sandbar verify-export`), locates the export's private ready audit only under the named destination's configured audit root by the manifest's opaque reference, requires that receipt to bind the exact marker hash, the recorded database identity and basis, the destination, audience, project and file rows, and then compares the recorded identity and basis with one live snapshot. Nothing is imported, written, restored, committed or approved.\n\nWHEN: use before reusing a verified staging child against the existing store, to learn whether these files have a trusted origin in this database and whether the database moved since the export. When NOT to use: (a) to decide whether an import is safe — the result carries import-approved? false; use `sandbar.project.import` preview with its own pins; (b) for file integrity alone — `sandbar verify-export`; (c) for a tree from another database or a restored one — bases are not compared across identities.\n\nHOW: `:project` is the enrolled Project ident, `:destination` the operator-configured export destination name and `:from` an existing direct child of that destination's staging root; optional `:expect-manifest` pins the manifest bytes to a SHA-256 from trusted custody. The caller must be an authenticated principal with full clearance (a read-only role is acceptable); the check refuses before any file is read otherwise. RESULT: `{:status :checked :scope :snapshot-comparison :state <state> :file-count N :audience public|private :held-count N :manifest-sha256 <hex> :import-approved? false}` with an optional keyword `:reason`. States: `:same-store-same-basis` (the recorded identity matches and the basis is unchanged — still preview the import); `:store-advanced` (same database, later basis: review the intervening state, which may be unrelated work or the export's own provenance record — no claim that these files changed); `:store-behind-export` (same identity, earlier basis: hold for review); `:different-store` (identities differ; bases are not ordered); `:origin-unverified` (receipt absent, from before origin binding, malformed or disagreeing; integrity was still verified). Coverage is the listed files at this audience: a public export covers its selected public documents, a filtered or held export covers less than its project. Integrity, authorization, configuration and read failures are isError refusals with a keyword reason, never a checked state. No database identity, private path, held identity or exception text appears in the result.\n\nORDER: after `sandbar verify-export` (or with the same pin) and before `sandbar.project.import` preview, inside the attended stopped-writer procedure.\n\nCOMBINATION: `sandbar.project.export` writes the receipt this verb consumes; `sandbar.project.import` preview and persist remain the place where intended changes are assessed and pinned."
    :inputSchema {:type "object" :additionalProperties false
                  :properties
                  {:project {:type "string" :description "Explicit enrolled Project entity ident"}
                   :destination {:type "string" :description "Operator-configured export destination name"}
                   :from {:type "string" :description "Existing absolute direct child of the destination's staging root holding a completed export"}
                   :expect-manifest {:type "string" :description "Optional SHA-256 pin of export-manifest.edn from trusted custody"}}
                  :required ["project" "destination" "from"]}
    :handler project-recovery-check-handler}
   {:name "sandbar.project.import"
    :title "Import reviewed Markdown source units from a filesystem hierarchy"
    :description "WHICH: walks the `:from` directory, parses each `.md` file via the markdown codec into ONE SOURCE UNIT per file (a memory with its sections, or a Tag / other native-codec document), and — with `:persist` — transacts each parsed unit on its own, reporting every file's fate.  Document import complements sandbar.project.export. Its preservation contract depends on the document class and codec; it is not a full database restore.\n\nWHEN: use for attended maintenance import or ingestion of reviewed documents. Stop every writer before editing canonical input files and keep writers stopped through preview, import and audit. Import into the existing store to preserve existing identities. Exclude ambiguous paths and retain both versions until their ownership is resolved.  When NOT to use: (a) you want to create ONE entity programmatically — `sandbar.entity.create` (STRICT on front matter: an unknown key is refused; this bulk verb is LENIENT: unknown keys ride in the front-matter carrier and are REPORTED per file); (b) you want to write TO the filesystem — `sandbar.project.export`.\n\nHOW: `:from` is the input directory path (REQUIRED; a single `.md` file is accepted too).  `:filter` (optional) restricts which units ingest — `:class`, `:classes`, `:tree-filter` (same shape as `sandbar.project.export`); the decision is made per source file.  `:persist` (optional, default false) — WITHOUT it the verb is a DRY RUN: it parses, PLANS every unit and reports, but transacts nothing.  REPLACEMENT: a file the substrate already holds is REPLACED — the file's declared slots win, an omitted source-owned slot is retracted, a cardinality-many slot is set-replaced, sections the file no longer carries are retracted with their links, the carrier is replaced or retracted — in ONE transaction per file, while the host's identity, the refs into it, and the facts the substrate or another owner maintains (created, last-touched, created-by, owning-project, visibility, the id) are kept; `:mode` `additive` opts into assertion-only additive behaviour.  A unit is REFUSED as a conflict, never guessed, when the file's class differs from the stored one, its `id:` differs from the stored `mm/id`, or a section it drops is referenced by a record outside the document (`:conflicts`, with the reason).  With a nonempty operator `:project-roots` map, a mapped source tree requires its Project as effective owner; an explicit owner must resolve, existing documents cannot change physical trees, and a mapped Project key cannot be renamed, retracted or taken over. These checks apply in preview and persist. An omitted owner retains the stored owner for an existing typed document. A genuinely new document under a mapped root, including an untyped forward placeholder, receives that root's Project when the source omits owning-project; an explicit owner is never overwritten. Existing unowned documents remain held for the operator rather than being adopted. Global and staging sources retain the legacy path subject to these guards; an empty map adds no destination guard. The report includes `:source-root`. Import still permits its existing within-tree rel-path changes; the operator must reconcile old files during maintenance. Ordinary entity.update has a stricter physical-target refusal.\n\nNEW DOCUMENT IDENTITY: an insert plan for a new Memory document with an ident supplies an absent :mm/id UUID only when neither the source nor an existing forward placeholder has one. Existing identities are retained; a conflicting source and placeholder identity refuses the unit. An existing typed document without a UUID is not silently assigned one. Preview :units and planned persist result rows carry :identity-minted?; :document-id and :owning-project are included when present on the plan's root assertion. These fields describe the plan's assertions, not a full read-back of retained store metadata. Planning a UUID in preview does not transact it.\n\nFILE COMPLETION: this MCP verb does not stamp source files. The maintenance CLI's --stamp option, with --receipts when persisting, can add only the missing id line for a successfully imported new document at its canonical destination. It checks the import receipt, unchanged source, current stored identity and competing path claimants; changed, foreign, ambiguous or noncanonical files remain held. Staging copies at other destinations cannot be stamped as canonical files. Keep every writer stopped through stamping and audit. --stamp is not an MCP argument and does not relax ordinary file-write or delete ownership guards.\n\n`:expect-basis` (optional) pins a persist to the basis a preview reported: if the database moved, the call is refused and the preview must be repeated; every unit reports its `:source-sha256`.  THE SOURCE PIN: `:expect-sources` (the preview's `:sources-sha256` token) or `:expect-source-hashes` (its `:sources` map) pins the persist to the preview's input set — a file that changed, appeared or disappeared since refuses the WHOLE call before anything is transacted, the map form naming the files.  THE BASIS GUARD: each plan is applied under `[:assert-basis t]` for the database value it was computed against, so a change that landed between planning and apply — a citation added to a section the plan retracts — aborts that unit's commit and is reported as a `:basis-moved-during-apply` conflict, never replanned.  An attended run advances its expected basis only through its OWN commits: a write that is not its own, landing between two units (or between the preview's pin and the first unit), refuses the remaining units as `:basis-moved-before-plan` conflicts, with the applied units and the refused remainder both reported and `:final-basis` the basis the run's last commit left.  A file that cannot be read is its own `:parse-failed` unit.  `:exclude` (an array of rel-paths) names units that are walked and fingerprinted but neither planned nor transacted — deferred rows kept with both versions held; they count as skipped and are listed under `:excluded`.  ACCOUNTING — every response carries `:attempted` (files walked), `:imported` (entities parsed), `:parse-failed-count` + `:parse-failed [{:source :error}]` (files whose parse threw — named, never silently dropped), `:unknown-keys-count` + `:unknown-keys [{:source :dt/type :ident :unknown-keys [...]}]` (files whose front matter carried keys the class does not declare), and `:skipped-count` (files the filter excluded, or that could not match the tree filter); a dry run adds `:units` (each file's planned `:mode` — `insert`, `replace` or `additive` — with the retractions a replace would make and any `:conflicts`) and `:basis`.  With `:persist` the response adds `:persisted-count` and `:persisted` (each unit's mode and retraction counts), `:failed-count` + `:failed [...]` (a unit whose transaction failed — a schema or transactor error, with `:source`, `:ident`, `:error`), `:refused-count` + `:refused [...]` (a unit the import firewall refused — a governed edge the floor forbade; firewall refusal rows include `:error` and `:violations`, a vector selecting the present `:type`, `:severity`, `:reason`, `:slot`, `:target-ref` and `:message` fields from existing `:firewall-violation` verdicts, without fetching target bodies), `:conflict-count` + `:conflicts [...]` (a unit refused for ambiguity under replacement), `:groups` (the transaction units), and `:totals` + `:reconciled?` — attempted = persisted + failed + refused + conflicts + parse-failed + skipped.  ONE transaction per file: a failing file never rolls back its neighbours.  (Wire-format key MUST be `persist` — no `?` suffix — to comply with the MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`; the handler accepts legacy `persist?` too.)\n\nORDER: follow the quiescent maintenance procedure in doc/operations.md. Preview without persist, retain the exact source manifest and report, then persist with the matching basis and source pins. Inspect every refusal, failure and conflict; earlier successful files remain committed if a later file fails. entity.validate is advisory and does not replace preview or shape auditing.\n\nCOMBINATION: sandbar.project.export supplies document representations; sandbar.audit.fs-substrate-drift diagnoses differences after an import. Verify content, identity and references explicitly. Registered codecs are visible via sandbar.codec.list; this import path walks Markdown files."
    :inputSchema (one-required
                   {:from     {:type "string" :description "Input directory path (or a single .md file)"}
                    :filter   {:type "object"
                               :description "Optional filter spec (same shape as project.export): class / classes / tree-filter; decided per source file"}
                    :mode     {:type "string" :description "'replace' (default): a file the substrate already holds replaces its source-owned representation in one transaction; 'additive': assertions only (for ingestion from independent sources)."}
                    :expect-basis {:type "number" :description "Pin a persist to the :basis a dry run reported; refused if the database moved since (preview again)."}
                    :expect-sources {:type "string" :description "Pin a persist to the :sources-sha256 token a dry run reported: refused before any transaction if any walked file changed, appeared or disappeared since."}
                    :expect-source-hashes {:type "object" :description "The dry run's :sources map ({rel-path sha256}); like expect-sources but the refusal names the files that changed, appeared or disappeared."}
                    :exclude {:type "array" :items {:type "string"} :description "Rel-paths of units to walk and fingerprint but neither plan nor transact — deferred rows kept with both versions held."}
                    :persist  {:type        "boolean"
                               :description "When true, transact each parsed source unit (ONE transaction per file) into the substrate.  When false / omitted, DRY RUN: parse + report, transact nothing.  Either way the response accounts for every file walked — attempted = persisted + failed + refused + parse-failed + skipped — and names every file whose parse failed.  (Wire-format key MUST be `persist` — no `?` suffix — to comply with the MCP tool-schema property-key regex; the handler accepts legacy `persist?` too.)"}}
                   [:from])
    :handler project-import-handler}

   ;; Entity retraction (first-class MCP retraction verb — 2026-07-02).
   ;; Per doc/concepts/mcp-protocol.md
   {:name "sandbar.entity.retract"
    :title "Retract explicit entities with a dry-run-by-default safety layer"
    :description "WHICH: retracts an EXPLICIT set of entities (`:targets` — idents or eids, 1..100) via `:db.fn/retractEntity` in ONE atomic transaction, wrapped in the retraction safeguards: dry-run-by-default, per-target blast-radius report, protected-namespace/class guard, cascade opt-in, required audit reason.  The first-class MCP retraction verb — replaces the nREPL-toolchain workaround.  Substrate half is `sandbar.db.datomic/retract-entity`; this verb is the MCP surface + safety layer.\n\nWHEN: use to remove named entities from the substrate — cleanup packages (bulk-retract, bare-ident dups, orphan sections, anonymous carriers).  When NOT to use: (a) predicate/query-based MASS retraction — NOT supported in v1 (explicit targets only; enumerate first via `sandbar.class.instances` / `sandbar.search.bm25f`, then pass the eids); (b) you want to EDIT an entity — `sandbar.entity.update`; (c) you want to physically excise history — out of scope (this is logical retraction).\n\nHOW: `:targets` (REQUIRED) is an array of idents (keyword-strings like `\":memory.decisions/foo\"`) or numeric eids; 1..100 (over-cap ⇒ loud error).  `:persist` (bool, default FALSE) — WITHOUT it the verb is a DRY-RUN returning the full report and transacting NOTHING (same convention as `sandbar.project.import`; wire key is `persist`, no `?`).  `:cascade` (bool, default false) — when true, the enumerated dependents (the target's `:mm/Section` tree + `:mm.memory/frontmatter` carrier) are INCLUDED in the retraction; the section refs are not `:db/isComponent` so without cascade the tree survives as ORPHANS (the report says so), while the frontmatter carrier is a component and retracts with its host either way.  THE FILE GOES WITH THE ENTITY: after a persisted retraction the projected file each target owned is removed — only when no other live entity claims the rel-path and the file's front-matter id matches the entity's `mm/id` — and reported under `:files` as `:removed`, `:kept` (with a reason), `:absent` or `:failed` (an I/O failure is reported, never hidden); the dry run carries each target's `:file-effect`.  `:reason` (string) — REQUIRED when `:persist` (carried into the `:mm.event/EntityRetracted` audit event; persist without it ⇒ loud error).  `:actor` (optional ref) — recorded on the audit event.  `:acknowledge-dangling` (bool, default false) — the report's `:inbound-refs`/`:inbound-count` per target enumerate the INBOUND citation edges (`:mm.memory/cites` / `:mm.memory/motivated-by` / any ref) that would be left DANGLING by the retraction; a `:persist` over a target with nonzero inbound refs is REFUSED unless you pass `:acknowledge-dangling true` (or repoint those inbound edges first).  Protected targets (namespace `dt`/`db`/`workflow`/`mm.event`, plus `:mm/Actor` instances + `:mm/Workflow` definitions) are SKIPPED with a reason, NOT retracted, and do NOT abort the batch.\n\nORDER: run once WITHOUT `:persist` to inspect the blast-radius report (datom-counts + outbound dependents + INBOUND refs + protected flags), then re-run WITH `:persist true` + `:reason` (and `:acknowledge-dangling true` if inbound refs exist and you accept the dangle) to commit.  Discover target eids first via `sandbar.class.instances` / `sandbar.search.bm25f` / `sandbar.entity.find`.\n\nCOMBINATION: pairs with `sandbar.entity.find` (confirm a target exists first); the inbound-citation check `sandbar.navigate.inbound-edges` once needed before orphaning is now BUILT IN as the report's `:inbound-refs` section.  Result: the dry-run report `{:targets [{:target :resolved-eid :exists? :ident :dt-type :datom-count :dependents :inbound-refs :inbound-count :protected? :protection-reason} ...] :cascade :dependents-note}`, augmented on `:persist` with `:retracted-eids :retracted-count :skipped :events-emitted`."
    :inputSchema (one-required
                   {:targets {:type "array"
                              :items {:type "string"}
                              :description "Explicit targets (1..100): idents (keyword-strings, e.g. ':memory.decisions/foo') or numeric eids"}
                    :persist {:type "boolean"
                              :description "When true, COMMIT the retraction.  Default false = DRY-RUN (report only, transacts nothing).  Wire key MUST be `persist` (no `?`) per the MCP property-key regex."}
                    :cascade {:type "boolean"
                              :description "When true, include the target's enumerated dependents (section tree + frontmatter carrier) in the retraction.  Default false — dependents survive as orphans (refs are not :db/isComponent)."}
                    :reason  {:type "string"
                              :description "REQUIRED when :persist — human-readable audit string carried into the :mm.event/EntityRetracted event."}
                    :actor   {:type "string"
                              :description "Optional actor ref recorded on the audit event."}
                    :acknowledge-dangling
                    {:type "boolean"
                     :description "When true, PERMIT a :persist that leaves inbound citation edges (the report's :inbound-refs) dangling.  Default false ⇒ a persist over a target with nonzero :inbound-count is refused loudly (repoint the inbound refs first, or acknowledge the dangle)."}}
                   [:targets])
    :handler entity-retract-handler}

   ;; Aggregation operations (Stage 14 — fulltext arc Phase G).
   ;; Descriptions follow the WHICH/WHEN/HOW/ORDER/COMBINATION discipline
   ;; per authorizations/sandbar_focus_for_0_1_0_release_plus_mcp_llm_consumability_discipline_2026_05_14.md.
   {:name "sandbar.aggregate.count"
    :title "Count entities of a class (with optional Datalog filter)"
    :description "WHICH: returns a single integer count of entities that are instances of `:class` (including subclass instances) matching an optional `:where` Datalog filter.\n\nWHEN: use when you need the SIZE of a class's instance set — total count or a filtered subset.  When NOT to use: (a) you also need the entities themselves — use `sandbar.class.instances` (full set) or `sandbar.aggregate.rank-by` (top-K); (b) you need counts BROKEN DOWN BY a slot value — use `sandbar.aggregate.group-by` instead.\n\nHOW: `:class` is the class ident (e.g. `:mm/Memory`).  `:where` is an EDN-STRING of Datalog clauses (JSON has no native representation for Datalog symbols `?e`, `?v`); the convention is `?e` for the entity at the head of the count walk.  Example: `\"[[?e :mm.memory/memory-type :decision]]\"`.\n\nORDER: no prerequisites; leaf-call.  To discover available classes first, use `sandbar.schema.classes`; to discover slot idents on a class, use `sandbar.class.slots`.\n\nCOMBINATION: composes with `sandbar.aggregate.group-by` (`count` for the cardinality, `group-by` for the breakdown) and with `sandbar.aggregate.rank-by` (use `count` to size the candidate population first).\n\nResult: `{:count <int>}`."
    :inputSchema (one-required
                   {:class {:type "string"
                            :description "Class ident (e.g. ':mm/Memory')"}
                    :where {:type "string"
                            :description "Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. The counted source population is not clearance-filtered; variable joins can still test hidden targets."}}
                   [:class])
    :handler aggregate-count-handler}
   {:name "sandbar.aggregate.group-by"
    :title "Group-by-count facet aggregation over a class's instances"
    :description "WHICH: groups instances of `:class` by `:group-by` slot value, returns `{value: count}` map + total.\n\nWHEN: use for facet-style breakdowns — 'how many memories of each memory-type?', 'how many properties in each domain?'.  When NOT to use: (a) only the total count is needed — use `sandbar.aggregate.count`; (b) you need ranked instances rather than counts — use `sandbar.aggregate.rank-by`; (c) you need group-counts over a FULLTEXT match-set — use `sandbar.search.bm25f` with `:include [:facets]` opt instead (search → facet in one call).\n\nHOW: `:class` is the class ident.  `:group-by` is the slot ident to group by (must be a single-valued slot — multi-cardinality slots will count each value separately).  Optional `:where` (EDN-string Datalog) constrains the candidate set BEFORE grouping.  Entities where `:group-by` is unset are skipped (not counted in any group).\n\nORDER: no prerequisites.  Discover slot idents first via `sandbar.class.slots` if uncertain.\n\nCOMBINATION: pairs with `sandbar.aggregate.count` (total) and `sandbar.aggregate.rank-by` (top-K within a group, via a follow-up call per group).  Use `sandbar.search.bm25f` `:facet-by` opt for the same shape over a search match-set.\n\nResult: `{:groups {value count} :total <int>}`."
    :inputSchema (one-required
                   {:class    {:type "string"
                               :description "Class ident"}
                    :group-by {:type "string"
                               :description "Slot ident to group by (e.g. ':mm.memory/memory-type')"}
                    :where    {:type "string"
                               :description "Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. The grouped source population is not clearance-filtered; variable joins can still test hidden targets."}}
                   [:class :group-by])
    :handler aggregate-group-by-handler}
   {:name "sandbar.aggregate.rank-by"
    :title "Structural-rank re-ordering by edge degree / backlink-density / recency / freshness"
    :description "WHICH: re-orders instances of `:class` by one of four structural-rank axes:\n  * `:degree` — total ref-attribute count (outbound + inbound by default; substrate's most-connected entities)\n  * `:backlink-density` — inbound ref-attribute count (who CITES this entity?  prominence-by-citation)\n  * `:recency` — descending order by `:temporal-slot` value (most-recently-touched first)\n  * `:freshness` — ASCENDING order by `:temporal-slot` value (stalest first; candidates meriting attention/review)\n\nWHEN: use for 'top-K' retrieval where ranking is structural, not content-based.  Compose with content-based ranking via `sandbar.search.bm25f` (bm25f scores content; rank-by re-orders structural axes).  When NOT to use: (a) content-relevance ranking — use `sandbar.search.bm25f`; (b) you only need counts — use `sandbar.aggregate.count` / `.group-by`.\n\nHOW: `:class` + `:rank-by` are required.  `:rank-by` is the axis keyword.  `:limit` defaults to 20 (0 = no cap).  CRITICAL: `:temporal-slot` is REQUIRED for `:rank-by :recency` and `:freshness` — substrate is class-agnostic; you must supply the temporal-axis slot (e.g. `:mm.memory/last-touched` for memory recency, `:mm.memory/last-reviewed` for freshness).  Calling `:recency` without `:temporal-slot` raises 400.  For `:degree` / `:backlink-density`, no `:temporal-slot` needed.\n\nORDER: no prerequisites.  To discover temporal-axis slot candidates on a class, use `sandbar.class.slots`.\n\nCOMBINATION: ranked instances become a candidate set for downstream filtering or projection.  For summaries of a selected result set, group the returned records in the client; sandbar.aggregate.group-by has no candidate-ID argument.  This operation does not accept where or a candidate-ID array. Use sandbar.search.bm25f with rank-by for content-constrained structural ranking. Result: `{:hits [{:entity <entity-map> :rank-score <num>} ...] :total <int> :returned <int>}`."
    :inputSchema (one-required
                   {:class         {:type "string"
                                    :description "Class ident"}
                    :rank-by       {:type "string"
                                    :description "Axis: ':degree' / ':backlink-density' / ':recency' / ':freshness'"}
                    :limit         {:type "integer"
                                    :description "Max hits to return (default 20; 0 = no cap)"}
                    :temporal-slot {:type "string"
                                    :description "REQUIRED for :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')"}
                    :projection    {:type "string"
                                    :description "Per-hit entity-shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-100× larger payload).  Opt to 'full' when consumers need slot bodies."}}
                   [:class :rank-by])
    :handler aggregate-rank-by-handler}

   ;; Aggregate — tag histogram (Stage 5.B-pre #4 — 0.1.1 co-evolution arc)
   {:name "sandbar.aggregate.tag-histogram"
    :title "Frequency histogram of :mm/Tag usage across the corpus"
    :description "WHICH: returns a frequency histogram of `:mm/Tag` usage across the corpus.  Each bin is `{:tag <ident|value|eid> :value <string> :count <int>}` — count is the number of entities (any class) that reference the tag via inbound edges.\n\nWHEN: use for tag-vocabulary observability — 'which tags are most-used?', 'which tags are orphans (used by ≤1 entity)?'.  When NOT to use: (a) you want one tag's full citing-set — use `sandbar.navigate.inbound-edges` with the tag as `:entity`; (b) you want tag schema-introspection (not usage) — use `sandbar.tag.lookup`; (c) you want audit-shaped tag concerns (undefined-used, orphans, drift) — use `sandbar.tag.audit`.\n\nHOW: optional `:limit` caps returned bins (default 0 = no cap); sorted descending by count then ascending by stringified-tag-identifier as tie-breaker.\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.tag.audit` (qualitative tag concerns) and `sandbar.tag.lookup` (single-tag detail).\n\nResult: `{:histogram [{:tag <ident|value|eid> :value <string> :count <int>} ...] :total <int>}`."
    :inputSchema {:type "object"
                  :properties {:limit {:type "integer"
                                       :description "Cap returned bins (default 0 = no cap)"}}
                  :required []}
    :handler aggregate-tag-histogram-handler}

   ;; Search — Lucene-syntax single-attribute fulltext (Stage 3 + Phase B P2 polish)
   {:name "sandbar.search.attribute"
    :title "Single-attribute Lucene-syntax fulltext search (`:db.fn/fulltext-search`)"
    :description "WHICH: returns entities whose `:attribute` value matches the Lucene query under Datomic's `:db.fn/fulltext-search`.  Single-slot search — unlike `sandbar.search.bm25f` which scores across multi-field weights, this verb hits ONE attribute (which must be `:db/fulltext true`) with full Lucene query-syntax support.\n\nWHEN: use when the query needs Lucene operators — phrase quoting (`\"exact phrase\"`), boolean (`foo AND bar`, `foo OR bar`, `NOT foo`), wildcards (`foo*`), fuzzy (`foo~`), field-prefixed (`field:value`).  Also: when you want single-attribute targeted retrieval without multi-field weighting (e.g., search ONLY the description slot).  When NOT to use: (a) multi-field weighted ranking across name + description + body + tags — use `sandbar.search.bm25f`; (b) bag-of-words across the entity surface — `sandbar.search.bm25f` (which lacks Lucene syntax but covers the full weighted-field set).\n\nHOW: `:attribute` is the slot ident (must be `:db/fulltext true`).  `:query` is a Lucene query string.  Optional `:limit` caps hits (default 50; 0 = no cap).\n\nAUTHENTICATED SEARCH: unreadable matches are excluded using the current stored record before sorting, counting and limiting. Hit projection uses the same store snapshot as that decision.\n\nORDER: prerequisite — discover fulltext-indexed attributes via `sandbar.class.slots` + check `:db/fulltext` flag (or by domain knowledge of which slots are indexed).\n\nCOMBINATION: pairs with `sandbar.search.bm25f` (BM25F handles the multi-field bag-of-words case; this verb handles the Lucene-syntax single-slot case).  Returned entity IDs can seed sandbar.navigate.path-via or edge navigation; sandbar.aggregate.rank-by does not accept a candidate-ID array.\n\nResult: `{:hits [{:entity <entity-map> :score <double>} ...] :total <int> :returned <int> :timing {:total-ms <int>}}`."
    :inputSchema (one-required
                   {:attribute  {:type "string" :description "Slot ident with :db/fulltext true (e.g. ':mm.memory/body-raw')"}
                    :query      {:type "string" :description "Lucene query string"}
                    :limit      {:type "integer" :description "Max hits (default 50; 0 = no cap)"}
                    :projection {:type "string"
                                 :description "Per-hit entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  Opt to 'full' when consumers need slot bodies."}}
                   [:attribute :query])
    :handler search-attribute-handler}

   ;; Search — BM25F multi-field fulltext (Stage 5.B-pre — 0.1.1 co-evolution arc)
   {:name "sandbar.search.bm25f"
    :title "Multi-field BM25F fulltext search over a class's instances"
    :description "WHICH: returns the top-K instances of `:class` ranked by Robertson-Zaragoza canonical BM25F over multi-field length-normalized scoring.  Field weights are introspected from the class's `:dt/bm25f-weights` declaration unless overridden via `:field-weights` opt.  Ref-typed slots whose `:dt/range` is a class with its own `:dt/bm25f-weights` automatically resolve to the target's weighted text content — e.g. on `:mm/Memory`, `:mm.memory/tags` + `:mm.memory/themes` tokenize via their referenced `:mm/Tag` content (value + alt-label + definition + scope-note + hidden-label + example).\n\nWHEN: use for content-relevance ranking — 'which memorials mention this concept'.  When NOT to use: (a) pure structural ranking with no content filter — use `sandbar.aggregate.rank-by`; (b) exact-string lookup — use `sandbar.entity.find` (by ident); (c) Lucene query-language operators (AND / OR / NOT / phrase / wildcard / fuzzy / field-prefix) — these are NOT recognized; bag-of-words only.\n\nHOW: `:query` is a bag-of-words string (tokenized via Porter stemmer + lowercase + word-boundary split).  `:class` is the class ident.  Optional: `:limit` caps hits (default 20; 0 = no cap).  `:where` is a Datalog clause vec (or EDN string) restricting hits to entities matching the predicate; clauses must reference `?e` as the entity variable.  `:facet-by` is a vec of slot-idents to facet over the FULL match-set (before limit).  `:include` is a vec of projection options — `:field-scores` (per-slot scores) and `:snippets` (per-slot ~240-char window with **term** highlighting).  `:field-weights` overrides the class's declared weights.\n\nAUTHENTICATED SEARCH: unreadable matches are excluded before ranking, snippets, field scores, facets and limiting. Clearance, hit payloads, snippets and scalar facets use the current stored record. Analyzed terms, frequencies and class-wide BM25F statistics remain cached; this does not establish lexical freshness after arbitrary writes or privacy of corpus statistics.\n\nCROSS-AXIS COMPOSITION:\n  `:from` + `:via` — graph-walk PRE-FILTER restricting candidate set to entities reachable from `:from` under path-grammar expression `:via` (same path-grammar dialect as `sandbar.navigate.path-via`; EDN-string form `\"[:REP+ :cites]\"`).  Composes with `:where` (intersection).\n  `:rank-by` — `:degree` / `:backlink-density` / `:recency` / `:freshness` re-rank top-K by structural axis instead of by BM25F score.  BM25F score is preserved on each hit as `:relevance-score`; the primary `:score` becomes the structural rank value.  `:relevance` opts out of the default ordering (below) for pure BM25F order.\n  `:temporal-slot` — REQUIRED when `:rank-by` is `:recency` or `:freshness`.\n\nDEFAULT ORDERING (no `:rank-by`): status- and recency-aware.  A hit the class declares SUPERSEDED (`:dt/superseded-when` on the class, e.g. a `superseded-by` edge or a superseded status on `:mm/Memory`) orders as if its score were halved, so a superseded twin of comparable relevance ranks below its current successor while a far more relevant superseded record still leads (a historical question keeps its answer); among equal weights the more recent (`:dt/recency-slot`, `last-touched` for memorials) comes first.  Demoted hits carry `:superseded? true`; `:score` stays the raw relevance; hits carry `:recency` (epoch ms) when the class declares a recency slot.  Applied before `:limit`.  A class declaring neither keeps pure relevance order.\n\nMULTI-CLASS: `:class` also accepts a JSON ARRAY of 2..8 class-ident strings (e.g. [':mm/Memory' ':mm/Tag' ':mm/Verb']).  Each class is queried with its OWN declared `:dt/bm25f-weights` (per-class field weighting is the design center of BM25F), hit lists are merged, sorted by raw score descending, and `:limit` is applied post-merge.  Each hit's `:entity` carries `:dt/type` so callers can tell classes apart in the merged list.  CAVEAT: cross-class raw-score comparability is APPROXIMATE — each class computes its own IDF (df/N over that class) and length-normalization (per-class avgdl), so raw BM25F scores are not on a unified scale across classes; deeper score-unification is a future refinement, explicitly out of v1.  `:where` / `:from`+`:via` / `:rank-by` apply per-class; `:facet-by` facets over the merged full match-set; `:field-weights` is single-class-only (loud error with a vec).\n\nORDER: prerequisite — the target class(es) must declare `:dt/bm25f-weights` (or supply `:field-weights` opt in single-class mode).  Discover via `sandbar.class.describe` if uncertain.\n\nCOMBINATION: replaces N+1 round-trips of `bm25f` → `aggregate.rank-by` → `navigate.path-via` filtering with one substrate-side call.  For pure-structural ranking with no content, use `sandbar.aggregate.rank-by` (skips tokenization entirely).  For path-walk without scoring, use `sandbar.navigate.path-via`.\n\nResult: `{:hits [{:entity <entity-map> :eid <id> :score <double> :relevance-score <double>? :field-scores {<slot> <double>}? :snippets {<slot> <string>}?} ...] :total <int> :returned <int> :timing {:total-ms <int>} :facets {<slot> {<value> <count>}}?}`."
    :inputSchema (one-required
                   {:query         {:type "string"
                                    :description "Query string (bag-of-words; no Lucene query-language operators)"}
                    :class         {:oneOf [{:type "string"}
                                            {:type "array"
                                             :items {:type "string"}
                                             :minItems 2 :maxItems 8}]
                                    :description "Class ident whose `:dt/bm25f-weights` drives field selection — a single class-ident string (e.g. ':mm/Memory'), OR a JSON array of 2..8 class-ident strings for strategic-subgroup scope (e.g. [':mm/Memory' ':mm/Tag' ':mm/Verb']).  Multi-class queries each class with its OWN declared weights, merges hits, and sorts by raw score descending.  CAVEAT: cross-class raw-score comparability is APPROXIMATE (per-class IDF + length-normalization differ); deeper score unification is out of v1.  `:field-weights` override is single-class-only (loud error with a vec)."}
                    :limit         {:type "integer"
                                    :description "Max hits (default 20; 0 = no cap)"}
                    :where         {:type "string"
                                    :description "Optional EDN-string of Datalog clauses with candidate ?e. Explicit reference values accept a readable ident, eid or lookup ref; missing/hidden targets refuse with filter-identity-unavailable. Attribute/scalar restrictions remain. Returned entities are clearance-checked, but variable joins can still test hidden targets."}
                    :facet-by      {:type "array"
                                    :items {:type "string"}
                                    :description "Slot-idents to facet over the full match-set"}
                    :include       {:type "array"
                                    :items {:type "string"}
                                    :description "Projection options: 'field-scores' / 'snippets'"}
                    :field-weights {:type "object"
                                    :description "Optional {slot-ident weight} map overriding class declaration"}
                    :from          {:type "string"
                                    :description "seed entity ident (e.g. ':memory.notes/example') or numeric eid as a string for `:via` graph-walk pre-filter; require :via together"}
                    :via           {:type "string"
                                    :description "EDN-string path-grammar expression (same dialect as sandbar.navigate.path-via)"}
                    :rank-by       {:type "string"
                                    :description "re-rank axis — ':degree' / ':backlink-density' / ':recency' / ':freshness'; or ':relevance' to opt out of the default status- and recency-aware ordering for pure BM25F order"}
                    :temporal-slot {:type "string"
                                    :description "required for :rank-by :recency / :freshness — temporal-axis slot ident (e.g. ':mm.memory/last-touched')"}
                    :projection    {:type "string"
                                    :description "Per-hit entity-shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only), 'frontmatter' (all scalar+ref slots EXCEPT the bulky :mm.memory/body-raw — the lean middle ground for consumers that read name/description/rel-path/memory-type without bodies, for example, a summary listing), or 'full' (all slots; ~10-100× larger payload).  Opt to 'full' only when consumers need slot bodies; otherwise the leaner shapes keep payloads small."}}
                   [:query :class])
    :handler search-bm25f-handler}

   ;; Orientation — type-tree + tree (Stage 5.B-pre #3 — 0.1.1 co-evolution arc)
   {:name "sandbar.orient.type-tree"
    :title "Class-hierarchy subtree rooted at a class (nested rendering)"
    :description "WHICH: returns the class-hierarchy subtree rooted at `:root` (default `:dt/Resource` — the metamodel root).  Recursive walk via `dt/direct-subclasses-of`; produces a nested-map tree with `:class` + `:children` per node.\n\nWHEN: use to visualize the full subclass hierarchy from a root class.  When NOT to use: (a) only direct subclasses needed — use `sandbar.class.subclasses`; (b) flat list of all subclasses — use `sandbar.class.subclasses` (returns flat).\n\nHOW: optional `:root` — root class ident string (default `:dt/Resource`).  Cycles in the inheritance graph are detected + flagged with `:cycle? true` (no infinite recursion).\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.class.describe` / `.slots` (drill into individual classes) and `sandbar.types.subclass-of` (relation query).\n\nResult: `{:root <ident> :tree {:class <ident> :children [<subtree>...]}}`."
    :inputSchema {:type "object"
                  :properties {:root {:type "string"
                                      :description "Root class ident (default ':dt/Resource')"}}
                  :required []}
    :handler orient-type-tree-handler}

   {:name "sandbar.orient.tree"
    :title "Top-level directory grouping of class instances by path-slot"
    :description "WHICH: groups instances of `:class` by their `:path-slot` value's first-level directory prefix.  Returns per-directory counts + optional sample entities.\n\nWHEN: use for filesystem-style overview of a corpus subtree.  When NOT to use: (a) sibling enumeration within ONE directory — use `sandbar.navigate.siblings-of`; (b) recursive descent through sub-directories — compose multiple `tree` calls or use a path-grammar walk.\n\nHOW: `:class` is the class ident.  `:path-slot` is the slot carrying the filesystem-style path.  Optional `:sample-size` includes that many sample entities per directory in the result (default 0 = counts only).\n\nORDER: leaf-call shape.\n\nCOMBINATION: pairs with `sandbar.navigate.siblings-of` (drill into a single directory) and `sandbar.aggregate.group-by` (more general group-by-slot).\n\nResult: `{:dirs {<dir-name> {:count N :sample [<entity-map>...]?}} :total N}`."
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
   ;; corpus doc/concepts/mcp-protocol.md)
   {:name "sandbar.orient.library-card"
    :title "Multi-axis typed-edge neighborhood view of an entity"
    :description "WHICH: returns a labeled, multi-axis view of an entity's typed-edge neighborhood.  Each `:axis` is a labeled subset of inbound or outbound edges optionally filtered by predicate-set and target/source-type.  Substrate-correct shape of the corpus's 'library-card' pattern — Sandbar ships the composition primitive; the consumer supplies the semantics (which axes mean what).\n\nWHEN: use when an AI client / consumer needs a structured overview of an entity — 'show me everything connected to this seed, broken down by relationship type'.  Especially useful for AI-orientation flows (load an unfamiliar entity; see its typed-edge surface across 10 axes at once).  When NOT to use: (a) single-predicate edge enumeration — use `sandbar.navigate.outbound` or `.inbound` directly (one call, simpler); (b) reachability across multiple hops — use `sandbar.navigate.walk` or `.path-via` instead; (c) fulltext-relevance ranking of the neighborhood — combine search with this verb's output downstream.\n\nHOW: `:entity` is the anchor entity (ident or eid).  `:axes` is a JSON array of axis-spec objects; each:\n  - `name` (REQUIRED) — string or keyword label for the axis in the result (e.g. \"cited-by-decisions\")\n  - `direction` (REQUIRED) — \"forward\" (outbound from entity) or \"inverse\" (inbound to entity)\n  - `predicates` (optional) — array of predicate-ident strings to restrict to.  Bare forms (`:cites`) auto-resolve per-axis to the slot-ident (`:mm.memory/cites`) on the entity's class.  Fully-qualified forms pass through unchanged.  Unresolvable bare predicates throw ex-info with a hint suggesting the canonical slot ident.\n  - `target-type` (optional, for :forward axes) — class-ident string restricting target-instance-of\n  - `source-type` (optional, for :inverse axes) — class-ident string restricting source-instance-of\n  - `limit` (optional) — per-axis edge cap; default 0 = no cap\nThe substrate is CLASS-AGNOSTIC; predicate-vocabulary + axis-labels are caller-supplied.  No hardcoded knowledge of any domain class's predicate vocabulary.\n\nOptional `:projection` — controls entity + edge target/source shape: `:metadata-only` (DEFAULT) returns just `:db/id`/`:db/ident`/`:dt/type` for the seed entity AND every edge's target/source (10-300x smaller payload — addresses 364KB+ responses on multi-axis queries); `:full` returns complete entity-maps.\n\nORDER: prerequisite — the caller must know the predicate vocabulary applicable to the entity's class.  Discover via `sandbar.navigate.outbound` (one-shot peek at outbound edges) or `sandbar.class.slots` (declared slots on the entity's class) FIRST.  No other ordering dependencies.\n\nCOMBINATION: composes with `sandbar.navigate.inbound` / `.outbound` (use them to DISCOVER predicate vocab first, then author library-card axis-specs covering them).  For ranked subsets within an axis, post-rank the results via `sandbar.aggregate.rank-by` (using the axis-result eids as the candidate set).  For path-shaped neighborhoods (recursive / Kleene), use `sandbar.navigate.path-via` instead — library-card is one-hop-per-axis by design.\n\nResult: `{:entity <entity-map> :axes {<axis-name> [{:predicate ... :target/source <entity-map>}...] ...}}`."
    :inputSchema (one-required
                   {:entity     {:type "string"
                                 :description "Anchor entity ident (e.g. ':memory.decisions/foo') or eid"}
                    :axes       {:type "array"
                                 :items {:type "object"
                                         :description "Axis-spec: {name, direction:'forward'|'inverse', predicates?, target-type?, source-type?, limit?}.  predicates accept BOTH bare (':cites') and slot-ident (':mm.memory/cites') forms — bare resolves per-axis to the entity's class."}
                                 :description "Vec of axis-spec objects; one labeled subset per axis"}
                    :projection {:type "string"
                                 :description "Entity + edge target/source shape: 'metadata-only' (default; lightweight) or 'full' (complete entity-maps)"}}
                   [:entity :axes])
    :handler orient-library-card-handler}

   ;; Navigation — outbound + inbound edges (Stage 5.B-pre #2 — 0.1.1 co-evolution arc)
   {:name "sandbar.navigate.outbound-edges"
    :title "Typed-edges originating FROM an entity"
    :description "WHICH: returns typed-edges originating from `:entity` — what does this entity reference, via which predicate, to which target.  Foundational outbound traversal primitive.\n\nWHEN: use for one-hop forward navigation when you need the predicate-and-target shape (not just the targets).  When NOT to use: (a) targets-only (no predicate label) — use a Datalog query directly; (b) recursive / Kleene-closure traversal — use `sandbar.navigate.path-via`; (c) bounded-depth BFS — use `sandbar.navigate.walk`.\n\nHOW: `:entity` is the seed entity (ident or eid; an identless entity is a valid anchor by eid).  Optional `:predicate` is a single ident string OR an array of ident strings restricting the edge predicates; BARE forms (no namespace) like `:cites` resolve to the slot-ident `:mm.memory/cites` on the entity's class (one match used; none or several refused with a hint), or schema-wide when the anchor has no class.  Optional `:target-type` is a class-ident-string restricting targets to instances-of.  Optional `:limit` caps returned edges (default 0 = no cap).  Optional `:projection` controls per-edge target shape — `:metadata-only` (DEFAULT) returns just `:db/id`/`:db/ident`/`:dt/type` per target (10-300x smaller payload than `:full`); `:full` returns the complete target entity-map.\n\nORDER: leaf-call shape.  Discover candidate predicates first via `sandbar.class.slots` on the entity's class if uncertain.\n\nCOMBINATION: pairs with `sandbar.navigate.inbound-edges` (the dual; who references this entity).  Composes with `sandbar.orient.library-card` (one-call multi-axis breakdown).  Pre-step for `sandbar.navigate.path-via` (discover predicate vocab before authoring path expressions).\n\nResult: `{:edges [{:predicate <pred-ident> :target <entity-map>} ...] :total <edges before the limit> :distinct-total <distinct targets before the limit> :returned <int> :limit <int> :truncated? <bool>}` — each edge keeps its `:predicate` as the role; a target reached through two predicates is two edges and one distinct record."
    :inputSchema (one-required
                   {:entity      {:type "string"
                                  :description "Anchor entity ident or eid (an identless entity anchors by eid)"}
                    :predicate   {:oneOf [{:type "string"}
                                          {:type "array" :items {:type "string"}}]
                                  :description "Single predicate ident OR JSON array of idents.  Bare forms (`:cites`) resolve to slot-idents (`:mm.memory/cites`) on the entity's class, or schema-wide when the anchor has no class."}
                    :target-type {:type "string"
                                  :description "Class ident restricting target-instance-of"}
                    :limit       {:type "integer"
                                  :description "Max edges (default 0 = no cap)"}
                    :projection  {:type "string"
                                  :description "Per-edge target projection: 'metadata-only' (default; lightweight) or 'full' (complete target entity-map)"}}
                   [:entity])
    :handler navigate-outbound-edges-handler}

   {:name "sandbar.navigate.inbound-edges"
    :title "Typed-edges pointing AT an entity (who references it)"
    :description "WHICH: returns typed-edges pointing at `:entity` — who references this entity, via which predicate, from which source.  Foundational inbound traversal primitive (dual of `sandbar.navigate.outbound-edges`).\n\nWHEN: use for backlink discovery — 'which decisions cite this ADR?'.  When NOT to use: (a) sources-only without predicate label — use Datalog directly; (b) bounded-depth backlink walk — use `sandbar.navigate.walk` with `:inbound` flag; (c) Kleene closure — use `sandbar.navigate.path-via` with `:INV`.\n\nHOW: `:entity` is the target entity (ident or eid; an identless entity — a bare tag value carrier — is a valid anchor by eid).  Optional `:predicate` is a single ident string OR an array of ident strings restricting the edge predicates.  Pass membership predicates FULLY QUALIFIED: `:mm.memory/tags` and `:mm.memory/themes` (one call covers both).  A BARE form (no namespace) resolves against `:source-type`'s class when given, else against EVERY ref-typed property in the schema with that local name — `:tags` covers memories, rules, actors and contexts at once, each edge reporting the qualified predicate it was found through; a name no ref property carries is refused.  Optional `:source-type` is a class-ident-string restricting sources to instances-of.  Optional `:limit` caps returned edges.  Optional `:projection` controls per-edge source shape — `:metadata-only` (DEFAULT) returns just `:db/id`/`:db/ident`/`:dt/type` per source; `:full` returns the complete source entity-map.\n\nORDER: leaf-call shape.  For a vocabulary journey: `sandbar.tag.lookup` (the identity), this verb from its eid with both membership predicates (the records), `sandbar.entity.find` on a selected record.\n\nCOMBINATION: pairs with `sandbar.navigate.outbound-edges` (the dual).  Composes with `sandbar.orient.library-card` (`:inverse` axes use the inbound shape).\n\nResult: `{:edges [{:predicate <pred-ident> :source <entity-map>} ...] :total <edges before the limit> :distinct-total <distinct sources before the limit> :returned <int> :limit <int> :truncated? <bool>}` — clients deduplicate sources by `:db/id` without losing which relationship found them; edge totals and distinct-record totals stay distinguishable."
    :inputSchema (one-required
                   {:entity      {:type "string"
                                  :description "Anchor entity ident or eid (an identless entity anchors by eid)"}
                    :predicate   {:oneOf [{:type "string"}
                                          {:type "array" :items {:type "string"}}]
                                  :description "Single predicate ident OR JSON array of idents.  Membership: pass `:mm.memory/tags` and `:mm.memory/themes` fully qualified.  Bare forms resolve against `source-type` when given, else schema-wide by local name."}
                    :source-type {:type "string"
                                  :description "Class ident restricting source-instance-of"}
                    :limit       {:type "integer"
                                  :description "Max edges (default 0 = no cap)"}
                    :projection  {:type "string"
                                  :description "Per-edge source projection: 'metadata-only' (default; lightweight) or 'full' (complete source entity-map)"}}
                   [:entity])
    :handler navigate-inbound-edges-handler}

   ;; Navigation — siblings-of (Stage 22 — fulltext arc Phase N)
   {:name "sandbar.navigate.siblings-of"
    :title "Same-directory peers of an entity via a filesystem-style path slot"
    :description "WHICH: returns entities whose `:path-slot` value shares the same directory prefix as `:entity`'s `:path-slot` value (filesystem-style — 'decisions/foo.md' is a sibling of 'decisions/bar.md' but NOT of 'decisions/sub/baz.md' or 'patterns/foo.md').\n\nWHEN: use to enumerate documents stored under the same logical 'directory' as a given anchor — e.g., listing all decision memorials in `decisions/`, all guides in `doc/guides/`.  When NOT to use: (a) entities lacking a filesystem-style path slot — reach for `sandbar.navigate.inbound` with `:next-sibling` / `:previous-sibling` predicate filter for typed-edge SIOC-pairwise sibling chains (mm/Section pattern); (b) recursive descent through sub-directories — reach for `sandbar.navigate.path-via` with `:FILTER` over a directory prefix.\n\nHOW: `:entity` is the anchor entity (ident or eid); `:path-slot` is the attribute ident carrying the filesystem-style path (e.g., `:mm.memory/rel-path`).  The substrate is CLASS-AGNOSTIC — the slot is caller-supplied; no hardcoded knowledge of memory-model vs other domain classes.  Optional `:limit` caps returned siblings (default 0 = no cap; cap is applied after substrate lookup so `:total` reflects the full set).\n\nORDER: prerequisite — `:entity` must have `:path-slot` populated.  If uncertain, verify first via `sandbar.entity.find` (reads the entity by ident).  No other ordering dependencies; this verb is leaf-call shape.\n\nCOMBINATION: pairs naturally with `sandbar.navigate.inbound` / `.outbound` (typed-edge neighbors) for complete sibling-discovery (filesystem-style + typed-edge).  For ranked sibling subsets, compose downstream with `sandbar.aggregate.rank-by` using the sibling-eid set as the candidate population.  For a graph-defined candidate set, sandbar.search.bm25f accepts from plus via; directory membership itself is a separate condition.\n\nResult: `{:siblings [<entity-map>...] :total <int> :returned <int>}`.  Each entity-map carries `:db/id`, `:db/ident` (if interned), and namespaced-keyword slots."
    :inputSchema (one-required
                   {:entity    {:type "string"
                                :description "Anchor entity ident (e.g. ':memory.notes/example') or numeric eid as a string"}
                    :path-slot {:type "string"
                                :description "Slot ident carrying the filesystem-style path (e.g. ':mm.memory/rel-path')"}
                    :limit     {:type "integer"
                                :description "Max returned siblings (default 0 = no cap)"}}
                   [:entity :path-slot])
    :handler navigate-siblings-of-handler}

   ;; Navigation — path-grammar walker (Stage P-6 — fulltext arc Phase N / Stage P)
   {:name "sandbar.navigate.path-via"
    :title "Walk a Wilbur-lineage path-grammar expression from a seed entity"
    :description "WHICH: walks a path-grammar expression (`:via`) starting from a seed entity (`:from`); returns the set of entities reachable under the binary-relation algebra denoted by the expression.  Path-grammar is Kleene-algebra-over-binary-relations — same lineage as SPARQL 1.1 property paths and ISO GQL 39075:2024.\n\nWHEN: use when navigation needs more expressiveness than direct edges (`sandbar.navigate.inbound` / `.outbound`) or bounded BFS — specifically when you need Kleene closure (`:REP*` / `:REP+`), alternation (`:OR`), inverse traversal at depth, or shape-specific restrictions.  Real-world property-path queries are <0.1% of total per Bonifati 2017 — but when you need them, only path-grammar fits.  When NOT to use: (a) single hop — use `sandbar.navigate.outbound` / `.inbound` (simpler + faster); (b) bounded N-hop reachability — use `sandbar.navigate.walk` (BFS with hop-cap is more efficient than `:REP*` for known-depth walks); (c) you need the seed itself in results — `:REP*` (or `:OPT`) includes the seed via the zero-application branch.\n\nHOW: `:from` is the seed entity ident or eid.  `:via` is an EDN-STRING path expression using one of 13 currently-executable operators:\n  * Canonical-8 (Tier-1): `:SEQ` (n-ary sequence) / `:OR` (n-ary union) / `:REP+` (transitive closure 1+) / `:REP*` (reflexive-transitive 0+) / `:INV` (inverse — swap subject/object roles) / `:SELF` (identity) / `:RESTRICT [pred value]` (specific-node filter) / `:ANY` (wildcard predicate)\n  * Tier-2: `:NOT` (atomic-predicate property-set negation) / `:OPT` (zero-or-one; desugars to `(:OR p :SELF)`) / `:REP p min max` (bounded repetition) / `:FILTER p substring` (URI-substring filter on `:db/ident`) / `:TEST p fn-name` (functional predicate via registered fn)\nCasing: UPPERCASE combinators / lowercase predicates.  Examples:\n  * `\"[:REP+ :dt/subclass-of]\"` — transitive ancestor walk\n  * `\"[:SEQ [:REP* [:OR :cites :evidences]] [:RESTRICT [:dt/type :mm.memory/decision]]]\"` — closure-then-filter\n  * `\"[:INV [:REP+ :cites]]\"` — entities that transitively cite this seed\nTier-3 operators (`:LANG`, `:VALUE`, `:DAEMON`, `:NOREWRITE`, `:MEMBERS`, `:PREDICATE-OF-*`) are vocabulary-registered but compilation deferred; passing them raises descriptive ex-info.  `:include [\"paths\"]` is accepted but path-data is not yet populated (recursive-path reconstruction lands at follow-on); result carries `:path-data-deferred true` flag when requested.\n\nORDER: no strict prerequisites.  To explore the typed-edge vocabulary available at the seed first, call `sandbar.navigate.outbound` to see what predicates emerge from the entity; to discover class-hierarchy predicates, use `sandbar.class.slots` on a class.\n\nCOMBINATION: composes with `sandbar.navigate.inbound`/`.outbound` (use them to discover predicate vocab before authoring path expressions) and `sandbar.navigate.walk` (use walk first if depth-bounded reachability is enough; reach for path-via only when Kleene closure adds value).  Cross-axis composition with `sandbar.search.bm25f` (`:from` + `:via` opts to restrict candidate set) and its `rank-by` option (rank within a graph-walk neighborhood) is available in search.bm25f.\n\nResult: `{:reachable [<entity-map>...] :total <int> :returned <int>}`."
    :inputSchema (one-required
                   {:from       {:type "string"
                                 :description "Seed entity ident (e.g. ':dt/Property') or eid"}
                    :via        {:type "string"
                                 :description "EDN-string path expression (e.g. \"[:REP* [:OR :cites :evidences]]\")"}
                    :limit      {:type "integer"
                                 :description "Max returned entities (default 0 = no cap)"}
                    :include    {:type "array"
                                 :items {:type "string"}
                                 :description "Projection options; supports 'paths' (deferred surfacing)"}
                    :projection {:type "string"
                                 :description "Per-reachable-entity shape — 'metadata-only' (default for MCP — :db/id + :db/ident + :dt/type only) or 'full' (all slots; ~10-300x larger payload).  When :include includes 'paths', applies to the :entity field of each {:entity :path} entry."}}
                   [:from :via])
    :handler navigate-path-via-handler}

   ;; Tag-vocabulary operations — Stage 7.D of doc/guides/searching-the-corpus.md
   {:name "sandbar.ground"
    :title "Compositional grounding workflow — tag lookup + meta-vocab + suggested next-step"
    :description "WHICH: load-bearing entry point for grounding-before-action.  Composes tag-vocabulary examination (step 1) + meta-vocabulary discovery (step 2; classes / predicates aligned with concept) + suggested-next-step routing (step 3).\n\nWHEN: use BEFORE introspection / planning / authoring / research to anchor the concept in the substrate's vocabulary.  The 5th retrieval axis (formal-semantic vocabulary) per doc/concepts/mcp-protocol.md  Composes with the other four retrieval axes (search / aggregation / orientation / navigation).  When NOT to use: the concept is already grounded (e.g., you have a concrete tag / class / predicate ident); skip to the specific verb.\n\nHOW: `:concept` is the concept-string to ground.  Returns:\n  `:step-1-tag-lookup` — the sandbar.tag.lookup result: exact label matches over every value carrier first, then conceptual matches, with `:match-reason` per candidate and the `:population` searched\n  `:step-2-meta-vocab` — classes + predicates whose name aligns with the concept\n  `:step-3-suggested-next` — CALLABLE suggestions, each `{:verb :args :why}` naming a catalog verb with arguments that can be passed as written: `sandbar.search.bm25f` over `:mm/Memory` for the concept in content; from the best match, `sandbar.navigate.inbound-edges` with `[\":mm.memory/tags\" \":mm.memory/themes\"]` for the records it classifies and `sandbar.tag.lookup` for its meaning view\n\nORDER: typically the FIRST call when an LLM consumer encounters a new concept in user input.  A step-1 miss states its population and is not proof the concept is absent — content search decides before a tag is authored; a search or read-barrier failure surfaces as an error, never as an empty vocabulary.\n\nCOMBINATION: anchor for tag.* operations; composes with `sandbar.search.bm25f` (content) and `sandbar.navigate.inbound-edges` (membership)."
    :inputSchema (one-required {:concept {:type "string" :description "Concept-string to ground"}}
                               [:concept])
    :handler ground-handler}
   {:name "sandbar.tag.lookup"
    :title "Tag-vocabulary primitive — find canonical tags aligned with a concept"
    :description "WHICH: finds EXISTING vocabulary identities for a concept before any new vocabulary is proposed.  Two passes: an exact pass over every entity carrying `:mm.tag/value` (typed `:mm/Tag` instances AND bare value carriers alike) comparing the concept with the value, alt-label and hidden-label; then a conceptual pass, BM25F over typed `:mm/Tag` instances (value / alt-label / definition / scope-note / hidden-label / example).  Step 1 of the sandbar.ground compositional workflow.\n\nWHEN: use to learn whether the vocabulary already has a concept, under which identity, and what it means — before authoring a tag, before a membership traversal.  Disambiguation primitive — collisions (two concepts sharing an alternative label) stay distinct candidates with their match reasons.  When NOT to use: (a) the concept is corpus-wide (try sandbar.search.bm25f over body content instead); (b) you already have the tag's eid or ident (use sandbar.entity.find).\n\nHOW: `:concept` is the concept-string; `:limit` (optional) caps the conceptual pass (default 10).  Optional `:projection` — `full` (default; the MEANING VIEW: `:eid`, `:ident` when interned, `:value`, `:typed?`, definition, scope-note, example, alt/hidden labels, `:canonical?` present when asserted true OR false and absent when missing, `:lifecycle-status`, `:superseded-by` (the successor's identity), `:in-scheme`, the SKOS mapping slots, broader/related context) or `metadata-only` (lightweight; :db/id + :db/ident + :dt/type + `:eid` + `:value` per match — every projection supplies a usable identity).  Every match carries `:match-reason` (`:exact-value` / `:exact-alt-label` / `:exact-hidden-label` / `:conceptual`; an entity both passes found lists both) and conceptual matches carry `:score`; exact matches come first.  The conceptual limit does not cap exact matches. The current `:match-total` can overcount overlap when the conceptual pass is limited; do not use it as a distinct-identity denominator. Returns `:concept`, `:matches`, `:returned`, `:match-total`, `:population` (`:value-carriers`, `:typed-tags`, `:untyped-carriers`, counted at read time), `:method`, `:gap?` (true when NEITHER pass matched) and `:gap-hint`.\n\nERROR VERSUS ABSENCE: a read-barrier timeout or a search failure is an error through the tool envelope, never an empty result; a miss means no match in the stated population and is NOT proof the concept is absent from the corpus (untyped carriers are matched by exact value or label, without a conceptual BM25F pass) — the hint points at sandbar.search.bm25f over :mm/Memory, not at authoring a tag.\n\nORDER: step 1 of sandbar.ground.  The vocabulary journey: this verb (identity + meaning) → sandbar.navigate.inbound-edges from the match's eid with `[\":mm.memory/tags\" \":mm.memory/themes\"]` (the records) → sandbar.entity.find on a selected record.\n\nCOMBINATION: pairs with sandbar.navigate.inbound-edges (membership), sandbar.search.bm25f (content), sandbar.tag.consolidate (when matches show drift), sandbar.tag.audit (lifecycle health)."
    :inputSchema (one-required {:concept    {:type "string" :description "Concept-string to look up"}
                                :limit      {:type "integer" :description "Max conceptual matches returned (default 10); exact matches are always returned"}
                                :projection {:type "string"
                                             :description "Per-match shape — 'full' (default; the meaning view with identity, lifecycle, successor, scheme, mapping and broader/related context) or 'metadata-only' (lightweight; :db/id + :db/ident + :dt/type + :eid + :value + :match-reason).  Opt to 'metadata-only' for bulk traversal (e.g., walking thousands of audit-flagged tags)."}}
                               [:concept])
    :handler tag-lookup-handler}
   {:name "sandbar.tag.define"
    :title "Author a new canonical :mm/Tag with required documentation slots"
    :description "WHICH: creates a new :mm/Tag entity with the supplied canonical :value + optional documentation slots (definition / scope-note / example / broader-* / in-scheme / etc.).  Forces explicit authoring at the boundary — `sandbar.tag.audit` will surface tags without definitions as the `:undefined-used` invariant.\n\nWHEN: use after vocabulary lookup and full reading establish that a new concept is warranted. A lexical gap from sandbar.tag.lookup does not establish that no canonical concept exists.  Authoring includes scope-note — the editorial boundary anchoring the canonical.  When NOT to use: (a) a canonical already exists — use sandbar.tag.consolidate to merge instead; (b) you want to rename — use sandbar.tag.rename; (c) the new tag overlaps a memorial-type — don't define (memorial-type slot already carries that information).\n\nHOW: `:name` is the canonical tag string (becomes :mm.tag/value).  `:slots` (optional) is a map of additional :mm.tag/* slot values:\n  `:definition`  — SKOS canonical definition\n  `:scope-note`  — editorial boundary\n  `:example`     — usage illustration\n  `:in-scheme`   — :mm/ConceptScheme ref (e.g., `:example/concept-scheme`)\n  `:canonical?`  — boolean (default true once defined)\n  `:vocabulary-level` — :substrate-level / :corpus-level / etc.\n  `:lifecycle-status` — :proposed / :active / :deprecated / :superseded\n\nReturns `{:tag <tag-summary> :created true}`.  Errors when a tag with this :value already exists.\n\nORDER: sandbar.tag.lookup is a discovery step; inspect candidate definitions and content before deciding to author a tag.\n\nCOMBINATION: pairs with sandbar.tag.lookup (gap discovery), sandbar.tag.audit (post-define audit-check), sandbar.tag.align (cross-vocabulary mapping after defining)."
    :inputSchema (one-required {:name    {:type "string" :description "Canonical tag string (becomes :mm.tag/value)"}
                                :slots   {:type "object" :description "Optional :mm.tag/* slots (definition, scope-note, example, broader-*, etc.)"}
                                :upgrade {:type "boolean" :description "When true, ADD the supplied :slots to an EXISTING tag with this :value (the normalization workflow for the undefined-used tags surfaced by sandbar.tag.audit).  Default false — create-only mode rejects existing tags loudly.  (Wire-format key MUST be `upgrade` — no `?` suffix — to comply with Anthropic MCP tool-schema property-key regex `^[a-zA-Z0-9_.-]{1,64}$`.  Handler accepts both `upgrade` and legacy `upgrade?` for back-compat.)"}}
                               [:name])
    :handler tag-define-handler}
   {:name "sandbar.tag.audit"
    :title "Run the 7 tag-lifecycle invariants and return the violation report"
    :description "WHICH: runs `sandbar.audit.tag/audit-all` — seven independent invariants over the corpus's tag vocabulary.  Returns per-invariant violations + an aggregate count.\n\nThe seven invariants:\n  1. `:undefined-used`      — tags referenced via :mm.memory/tags lacking :mm.tag/definition\n  2. `:defined-unused`      — tags with :mm.tag/definition but no inbound :mm.memory/tags refs\n  3. `:orphan`              — tags with no :mm.tag/in-scheme membership\n  4. `:date-pattern`        — tags whose :mm.tag/value matches a date pattern\n  5. `:type-pattern`        — tags whose :mm.tag/value overlaps a memorial-type keyword\n  6. `:drift`               — clusters of tags with same normalized form (case + plural)\n  7. `:closure-consistency` — cycles on broader-* / asymmetries on :related / missing inverse pairs on :superseded-by\n\nWHEN: use periodically to monitor vocabulary health.  Foundational pre-step for sandbar.tag.harmonize.  When NOT to use: (a) you want ONE invariant — call sandbar.audit.tag/<invariant-fn> via the in-process API directly (no individual MCP verb yet; aggregate-only at this stage).\n\nHOW: no arguments.  Returns `{:invariants [<map per invariant>] :total-violations N :summary <string>}`.\n\nORDER: no prerequisites; foundational diagnostic.\n\nCOMBINATION: feeds sandbar.tag.harmonize (drift cluster reconciliation), sandbar.tag.consolidate (per-cluster merges), sandbar.tag.define (for :undefined-used findings)."
    :inputSchema no-args-schema
    :handler tag-audit-handler}

   {:name "sandbar.audit.fs-substrate-drift"
    :title "Compare document files with stored Memory entities"
    :description "WHICH: compare Markdown files with their stored :mm/Memory entities and report explicit enrollment-preflight conditions. The report identifies missing entities, missing files, content divergence, reference-slot mismatch, files whose paths re-enter the memory root, and multiple database entities claiming one normalized path. Policy-excluded classes are reported separately from drift.\n\nWHEN: use to diagnose file/database differences before and after maintenance and to inspect whether a configured project tree has cleared the reported enrollment conditions. A discrepancy is evidence to investigate, not authorization to delete either version. Keep both versions of unresolved historical ambiguities.\n\nHOW: from is the project or memory-root directory. For /path/to/project with a memory child, the walk is confined to /path/to/project/memory; passing that memory directory selects the same effective root. The summary reports walk-root, root-mode and root-attribution. A configured project tree or the global tree is compared with the entities routed to that tree; an ad hoc directory uses the store-wide population and reports that limitation. Result keys include summary, missing-from-substrate, missing-from-fs, missing-from-fs-policy-excluded, content-divergence, ref-slot-mismatch, twins, substrate-rel-path-collisions, fs-parse-failed and enrollment-preflight. Content comparison normalizes nil, empty and whitespace-only bodies; inspect differences when exact bytes matter.\n\nENROLLMENT PREFLIGHT: :enrollment-preflight reports :files-without-uuid, :files-ambiguous-uuid, :documents-without-uuid, and :ownership rows for absent, ambiguous or mismatched file identities against stored :mm/id. It also reports :noncanonical-paths, :alias-groups resolving to the same physical target (with case folding only where the filesystem does), :invalid-owners, :files-of-other-trees and :document-collisions. Every document claiming a compared path is checked; a representative used for content comparison does not hide the other claimants. Invalid owners are a separately named store-wide population; only those with files in this tree count toward its unresolved conditions, except the global tree counts all invalid owners.\n\nREADINESS AND ERRORS: :clear-for-enrollment? is true only for a configured mapped or global tree with :unresolved-count zero and :error-count zero; an ad hoc directory is never clear. The summary mirrors these as :enrollment-preflight-clear?, :enrollment-preflight-unresolved-count and :enrollment-preflight-error-count. Unresolved counts are condition observations, not unique paths. Source parse failures are named in :fs-parse-failed and :summary/:fs-parse-failed-count. The preflight's :errors additionally names read-identity, canonicalize and parse failures by :phase; errors are unknowns, never a clean zero. Check these explicit fields: :total-drift-count does not include the enrollment or source-error counts. Clearance covers these conditions only, not complete content equivalence, a backup or a confidentiality proof. The MCP input is from only; this verb does not stamp, repair or enroll files.\n\nORDER: a read-only diagnostic. To compare a stable maintenance state, keep all writers stopped through import, any maintenance identity stamp, and the post-import audit.\n\nCOMBINATION: pairs with sandbar.entity.find-by-rel-path and sandbar.entity.find for investigation, and sandbar.project.import and sandbar.project.export for reviewed maintenance."
    :inputSchema (one-required {:from {:type "string" :description "Corpus root directory path (matches :from arg of sandbar.project.import / .export)"}}
                               [:from])
    :handler fs-substrate-drift-audit-handler}
   {:name "sandbar.tag.consolidate"
    :title "Merge :from tag INTO :into tag; preserves :from as alt-label + lifecycle :superseded"
    :description "WHICH: merges two tags by adding :from's canonical :value as a :mm.tag/alt-label on :into, marking :from with :mm.tag/lifecycle-status :superseded + :mm.tag/superseded-by ref to :into, and rewriting every :mm.memory/tags ref from :from to :into.  The merge preserves history (alt-label + superseded-by) for search-recall + audit trail.\n\nWHEN: use to resolve drift clusters surfaced by sandbar.tag.audit `:drift` invariant — `{tag, tags}` → consolidate \"tags\" into \"tag\".  Also use for editorial vocabulary cleanup (synonyms / variant spellings).  When NOT to use: (a) the tags are NOT synonyms — keep them separate; (b) you want a true rename (no source tag preserved) — use sandbar.tag.rename instead; (c) you want to partition a tag into narrower tags — use sandbar.tag.split.\n\nHOW: `:from` is the variant being merged out; `:into` is the canonical being merged into.  Both are :mm.tag/value strings.  Returns `:from`, `:into`, `:memorials-rewritten` (count of memorials whose :tags ref was rewritten), `:alt-label-added` (the preserved-as-alt-label value), `:lifecycle-status`.\n\nORDER: after sandbar.tag.audit surfaces a drift cluster + editorial decision selects canonical.\n\nCOMBINATION: pairs with sandbar.tag.audit (cluster discovery), sandbar.tag.harmonize (bulk drift-cluster planner), sandbar.tag.rename (when no merge is needed)."
    :inputSchema (one-required {:from {:type "string" :description "Tag :value to merge OUT (becomes alt-label on :into)"}
                                :into {:type "string" :description "Tag :value to merge INTO (canonical preserved)"}}
                               [:from :into])
    :handler tag-consolidate-handler}
   {:name "sandbar.tag.consolidate-all"
    :title "Batch-merge multiple drift clusters in a single MCP call"
    :description "WHICH: applies tag.consolidate semantics to a vector of `{from, into}` pairs in one MCP round-trip.  Per-pair errors collected (does NOT halt on first error); aggregate counts surface in the response.\n\nWHEN: use after sandbar.tag.harmonize surfaces drift clusters + editorial decisions selecting canonicals — apply a reviewed batch with one MCP call.  When NOT to use: (a) you have a single pair — sandbar.tag.consolidate (simpler); (b) pairs need different editorial review per cluster — review then batch the auto-mergeable subset only.\n\nHOW: `:pairs` is a JSON array of `{from, into}` objects.  Both fields per object are required.  Returns `{:results [{:from :into :memorials-rewritten :ok | :error} ...] :total :succeeded :failed :memorials-rewritten-total}`.  Per-pair semantics match sandbar.tag.consolidate exactly (alt-label + lifecycle :superseded + :superseded-by + memorial-rewrite).\n\nORDER: after sandbar.tag.harmonize surfaces cluster list + editorial decisions selected canonical per cluster.\n\nCOMBINATION: amortizes the round-trip overhead of per-cluster sandbar.tag.consolidate during vocabulary cleanup."
    :inputSchema (one-required {:pairs {:type "array"
                                        :description "Vector of {from, into} objects to consolidate"
                                        :items {:type "object"
                                                :properties {:from {:type "string"}
                                                             :into {:type "string"}}
                                                :required ["from" "into"]}}}
                               [:pairs])
    :handler tag-consolidate-all-handler}
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
    :description "WHICH: records a cross-vocabulary mapping from :tag to :external-iri under a SKOS mapping relation (`:exact-match` / `:close-match` / `:broader-match` / `:narrower-match` / `:related-match`).\n\nWHEN: use when the corpus's tag aligns with a tag in an external vocabulary (e.g., a Wikidata Q-id, a Dewey class, a Schema.org type, a SKOS concept in a referenced ontology).  Foundational for the federation backbone — VoID :mm/Linkset entities aggregate these mappings.  When NOT to use: (a) the external concept isn't actually mapped — don't fabricate; (b) you want a tag-to-tag mapping within the corpus — use :mm.tag/related instead.\n\nHOW: `:tag` is the corpus tag :value.  `:external-iri` is the external concept's IRI (e.g., \"http://www.wikidata.org/entity/Q12345\").  `:mapping-type` is one of \"exact-match\" / \"close-match\" / \"broader-match\" / \"narrower-match\" / \"related-match\" (default \"exact-match\").\n\nReturns `:tag`, `:external-iri`, `:mapping-type`, `:slot` (the resolved :mm.tag/<type> slot).\n\nMVP: stores the external IRI as a :mm/Tag entity (via :mm.tag/value upsert) referenced by the mapping slot.  This does not create a complete :mm/Vocabulary or :mm/Linkset model.\n\nORDER: after the tag is defined (sandbar.tag.define).\n\nCOMBINATION: pairs with sandbar.tag.audit (the alignment is auditable as a SKOS-mapping relation), sandbar.tag.harmonize (local vocabulary drift proposals; it does not query external schemes)."
    :inputSchema (one-required {:tag           {:type "string" :description "Corpus tag :value"}
                                :external-iri  {:type "string" :description "External concept IRI (e.g., Wikidata Q-id URL)"}
                                :mapping-type  {:type "string"
                                                :description "SKOS mapping relation — one of exact-match / close-match / broader-match / narrower-match / related-match (default exact-match)"}}
                               [:tag :external-iri])
    :handler tag-align-handler}
   {:name "sandbar.tag.harmonize"
    :title "Bulk-harmonization DRY-RUN report — drift clusters + auto-mergeable counts"
    :description "WHICH: runs a vocabulary audit and proposes drift clusters for consolidation. This is an advisory report; it does not apply a merge.\n\nWHEN: use to plan vocabulary cleanup. The auto-mergeable label is a heuristic suggestion, not evidence that two concepts have the same meaning. Read definitions and usage before choosing a canonical tag.\n\nHOW: no arguments. Returns audit-report, drift-clusters, drift-cluster-count, auto-mergeable-count and an explanatory note.\n\nORDER: review each proposed cluster before applying changes.\n\nCOMBINATION: sandbar.tag.audit gives invariant details; sandbar.tag.consolidate applies a selected merge; sandbar.tag.split records a partition when concepts should stay separate."
    :inputSchema no-args-schema
    :handler tag-harmonize-handler}
   ;; ---------- Shape operations (SHACL arc Stage F 2026-05-23) ----------
   {:name "sandbar.shape.list"
    :title "List :mm/Shape instances; optional filter by :applies-to class"
    :description "WHICH: returns all :mm/Shape entities in the substrate, optionally filtered by :applies-to class.  When :applies-to is provided, only shapes that target that class are returned.\n\nWHEN: use to discover what shape-validation invariants apply to a given class, or to enumerate the whole shape catalog.  Foundational SHACL-discovery verb.  When NOT to use: (a) you want to validate a specific entity — sandbar.shape.validate; (b) you want batch conformance over a class — sandbar.shape.conformance-report.\n\nHOW: optional `:applies-to` is a class ident string (e.g. ':mm/Decision').  Returns `{:applies-to-filter <ident-or-nil> :count <int> :shapes [<entity-projection>...]}`.\n\nORDER: leaf call; no prerequisites.\n\nCOMBINATION: feeds sandbar.shape.validate (per-shape validation) and sandbar.shape.conformance-report (batch validation)."
    :inputSchema {:type "object"
                  :properties {:applies-to {:type "string"
                                            :description "Optional class ident (e.g. ':mm/Decision') to filter shapes"}}
                  :required []}
    :handler shape-list-handler}
   {:name "sandbar.shape.validate"
    :title "Validate a single entity against its applicable :mm/Shape instances"
    :description "WHICH: walks the entity against every shape whose :mm.shape/applies-to exactly matches the entity's class; aggregates per-check results into a structured report.\n\nWHEN: use to verify a single entity conforms to its class invariants.  Most-common SHACL-consumer call.  When NOT to use: (a) batch validation over a class — sandbar.shape.conformance-report; (b) no shape targets the entity's class — the call returns empty results; this is not evidence that any constraint ran.\n\nHOW: `:entity` is the entity ident OR numeric eid.  Optional `:mode` is one of 'audit' (default; returns results), 'strict' (throws ex-info on :violation-severity failures), or 'disabled' (returns [] without checking).  Returns `{:entity <ref-string> :mode <kw> :result-count <int> :results [<walk-entity-result>...]}`.\n\nORDER: leaf call; prerequisite is the entity exists.\n\nCOMBINATION: paired with sandbar.entity.create (which applies its selected shape validation mode) and sandbar.shape.conformance-report (batch)."
    :inputSchema (one-required {:entity {:type "string"
                                         :description "Entity ident (e.g. ':memory.decisions/foo') or numeric eid (as string)"}
                                :mode   {:type "string"
                                         :description "Validation mode: 'audit' (default) / 'strict' / 'disabled'"}}
                               [:entity])
    :handler shape-validate-handler}
   {:name "sandbar.shape.conformance-report"
    :title "Batch conformance report for direct instances and exact-class shapes"
    :description "WHICH: walks entities whose direct :dt/type equals `:class` against every :mm/Shape whose :mm.shape/applies-to equals that same class; aggregates into a structured violation/warning report. It does not include subclass instances or inherit shapes from ancestor classes. Counts such as :total-checks, :passes and :failures are per entity/shape pair, not per individual property constraint.\n\nWHEN: use for class-wide invariant audits — e.g. 'what fraction of my :mm/Decision instances satisfy the decision-shape required-property invariant?'.  Substrate-quality + governance applications.  When NOT to use: (a) single-entity check — sandbar.shape.validate; (b) the class has no applicable shapes — the call returns a zero-failure report without establishing that a constraint ran.\n\nHOW: `:class` is the target class ident string (e.g. ':mm/Decision').  Returns `{:class <ident> :instance-count <int> :shape-count <int> :total-checks <int> :passes <int> :failures <int> :error-count <int> :warning-count <int> :failure-details [<walk-entity-result>...]}`.\n\nORDER: typical sequence — sandbar.shape.list (discover shapes) → sandbar.shape.conformance-report (run batch) → sandbar.shape.validate (drill into a specific violating entity).\n\nCOMBINATION: pairs with sandbar.class.validate-all-instances (the parallel class-level invariant runner) and sandbar.audit.* verbs (the legacy in-code audit surfaces)."
    :inputSchema (one-required class-arg-schema [:class])
    :handler shape-conformance-report-handler}
   {:name "sandbar.shape.create"
    :title "Author a new :mm/Shape entity (thin wrapper over sandbar.entity.create)"
    :description "WHICH: thin wrapper over sandbar.entity.create with :class :mm/Shape pre-bound.  Accepts the same :slots / :format / :source argument shape as entity.create.\n\nWHEN: use to author new shape memorials.  When NOT to use: (a) you're authoring a non-shape entity — sandbar.entity.create directly; (b) you want to modify an existing shape — sandbar.shape.update.\n\nHOW: `:slots` is the slot-map (e.g. {:mm.shape/shape-id 'foo' :mm.shape/applies-to ':mm/Decision' :mm.shape/required-property [':mm.memory/cites']}).  Returns `{:entity <projection>}`.  Optional `:format` + `:source` for codec-driven creation from markdown.\n\nORDER: same as entity.create.\n\nCOMBINATION: composes with sandbar.shape.list (discover post-creation), sandbar.shape.validate (test against the new shape)."
    :inputSchema {:type "object"
                  :properties {:slots {:type "object" :description "Slot map for :mm/Shape"}
                               :format {:type "string" :description "Optional codec format (e.g. 'markdown')"}
                               :source {:type "string" :description "Optional raw source string parsed via :format"}}
                  :required []}
    :handler shape-create-handler}
   {:name "sandbar.shape.update"
    :title "Amend an existing :mm/Shape entity (thin wrapper over sandbar.entity.update)"
    :description "WHICH: applies slot-map updates to an existing :mm/Shape entity.\n\nWHEN: use when an existing shape needs a constraint added/removed/refined (e.g., add a cardinality constraint, change required-property set).  When NOT to use: (a) authoring a new shape — sandbar.shape.create; (b) modifying a non-shape entity — sandbar.entity.update.\n\nHOW: `:entity` is the shape entity ident or eid.  `:slots` is the slot-map of updates.  Returns `{:entity <projection>}`.\n\nORDER: prerequisite — sandbar.shape.list or sandbar.entity.find to confirm the shape exists.\n\nCOMBINATION: same as sandbar.entity.update."
    :inputSchema (one-required {:entity {:type "string" :description ":mm/Shape entity ident or eid"}
                                :slots  {:type "object" :description "Slot updates"}}
                               [:entity :slots])
    :handler shape-update-handler}

   {:name "sandbar.namespace.policy"
    :title "Look up the per-namespace policy commitment statement (ARK ??-inflection)"
    :description "WHICH: returns the :mm.namespace/CommitmentStatement entity declaring identity-stability + content-stability + service-stability covenants + authority-UUID + first-issued instant for the named namespace.\n\nWHEN: use as the ARK `??`-inflection pattern — ask 'what's the policy under this namespace?' before authoring an entity into it. Useful for federation-aware consumers to discover persistence guarantees per-namespace.  When NOT to use: (a) you want to ASSERT a new CommitmentStatement — use sandbar.entity.create with class :mm.namespace/CommitmentStatement; (b) you want to look up the namespace's UUID (computed deterministically from the deployment's authority-UUID + namespace-name; not a stored slot).\n\nHOW: `:namespace` is the namespace-name string (e.g., 'decisions' / 'libraries.clojure' / 'observations').  Returns `{:namespace :commitment-statement :commitment-statement-entity-ident :ark-question-inflection-form}`.  Returns `:commitment-statement nil` + a :note if no CommitmentStatement exists for the namespace.\n\nORDER: leaf-call.  Authoring CommitmentStatements: sandbar.entity.create with :mm.namespace/CommitmentStatement class.\n\nCOMBINATION: pairs with sandbar.resolve (the resolution path for federation-shaped references)."
    :inputSchema (one-required {:namespace {:type "string" :description "Namespace name (e.g., 'decisions' / 'libraries.clojure')"}}
                               [:namespace])
    :handler namespace-policy-handler}

   {:name "sandbar.resolve"
    :title "Resolve an entity-reference of any wire form (URN / ident / rel-path / eid)"
    :description "WHICH: resolves a reference of any wire form to its canonical entity. PURL-style indirection — federation-shaped references resolve to canonical entities regardless of which wire form was used.\n\nWHEN: use to resolve federation-shaped references (URN form `urn:uuid:<v5>`) into substrate entities; sister verb to sandbar.entity.find (ident form) + sandbar.entity.find-by-rel-path (rel-path form).  When NOT to use: (a) you already know the wire form is an ident — sandbar.entity.find is more direct; (b) you have a rel-path string + know it's that form — sandbar.entity.find-by-rel-path.\n\nHOW: `:reference` is the input string in any of:\n  - `urn:uuid:<v5>` — federation wire form; resolves via :mm/id lookup\n  - `:memory.X/Y` or `memory.X/Y` — substrate ident form; resolves via eref/resolve\n  - `<dir>/<slug>.md` or `<dir>/<slug>` — corpus rel-path form; resolves via memory.<dir>/<slug> ident derivation\n  - numeric string — eid form; resolves via eref/resolve\n\nReturns `{:reference :resolved-entity :resolution-path}` where :resolution-path is one of :urn-uuid | :substrate-ident | :rel-path | :eid.  Returns :resolved-entity nil + :error string if the reference cannot be resolved.\n\nCOMBINATION: pairs with sandbar.namespace.policy (each resolution can be policy-checked against the namespace's CommitmentStatement)."
    :inputSchema (one-required {:reference {:type "string" :description "URN form (urn:uuid:...), substrate ident (memory.X/Y), rel-path (dir/slug.md), or eid (numeric string)"}}
                               [:reference])
    :handler resolve-handler}

   ;; ---------- γ scheduler verbs (Alt-D lazy-load checkpoint per ADR) ----------
   ;; Scheduler controls are described in doc/concepts/temporal-substrate.md.
   ;; `doc/concepts/mcp-protocol.md`:
   ;; these verbs land in the catalog but are EXPECTED to be loaded via the
   ;; lazy-load ToolSearch checkpoint (`ToolSearch select:mcp__sandbar__sandbar_schedule_*`)
   ;; only when scheduler work enters scope — NOT eager-loaded at orientation.
   {:name "sandbar.schedule.enable"
    :title "Flip the scheduler `:enabled?` flag true (does NOT start fire-thread)"
    :description "WHICH: sets the scheduler's runtime `:enabled?` flag to true.  Does NOT allocate the handler-pool or spawn the fire-thread — use `sandbar.schedule.start` for full activation.  When the dispatcher is running, enabling permits scheduled fires to actually emit Run-creation events.\n\nWHEN: use to TOGGLE the gate flag without lifecycle effect — e.g., to unblock an already-running scheduler that was paused via `sandbar.schedule.disable`.  When NOT to use: (a) the scheduler is not running — call `sandbar.schedule.start` (which both enables AND starts); (b) you want to STOP fires + release resources — `sandbar.schedule.stop`.\n\nHOW: no arguments.  Returns `{:outcome :enabled}`.  Idempotent."
    :inputSchema no-args-schema
    :handler (fn [_args] {:outcome (sched/enable!)})}

   {:name "sandbar.schedule.disable"
    :title "Flip the scheduler `:enabled?` flag false (does NOT stop fire-thread)"
    :description "WHICH: sets the scheduler's runtime `:enabled?` flag to false.  Does NOT release the handler-pool or stop the fire-thread.  When the fire-thread fires a scheduled event during the disabled period, the subscriber silently skips it (per `handle-scheduled-event`'s enable-gate).\n\nWHEN: use to SUSPEND firings without tearing down resources — e.g., maintenance windows where the scheduler stays warm but produces no Runs.  When NOT to use: (a) you want full lifecycle teardown — `sandbar.schedule.stop`.\n\nHOW: no arguments.  Returns `{:outcome :disabled}`.  Idempotent."
    :inputSchema no-args-schema
    :handler (fn [_args] {:outcome (sched/disable!)})}

   {:name "sandbar.schedule.start"
    :title "Full activation: allocate handler-pool + spawn fire-thread + register subscriber"
    :description "WHICH: invokes `sandbar.schedule/start!` — allocates the handler-pool ExecutorService, spawns the fire-thread, transitions state-machine to `:scheduler.state/active`, AND registers the `:mm.event/Scheduled` subscriber.  Composes the two-side activation that the standalone `dispatcher.start!` + `job-dispatcher.register!` don't.\n\nWHEN: use to bring the scheduler fully online from `:scheduler.state/inactive`.  Production callsite is typically `sandbar.core/start` (auto-invoked when `config.edn :scheduler/enabled? true`); operators invoke this verb to manually start outside config-controlled boot.  When NOT to use: (a) just flipping the enable flag — `sandbar.schedule.enable`; (b) suspending temporarily — `sandbar.schedule.disable`.\n\nHOW: no arguments.  Returns `{:outcome :started}` on success, `{:outcome :already-active}` when already running.  Idempotent."
    :inputSchema no-args-schema
    :handler (fn [_args] {:outcome (sched/start!)})}

   {:name "sandbar.schedule.stop"
    :title "Full deactivation: unregister subscriber + drain handler-pool + join fire-thread"
    :description "WHICH: invokes `sandbar.schedule/stop!` — unregisters the `:mm.event/Scheduled` subscriber, transitions state-machine to `:scheduler.state/draining`, interrupts + joins the fire-thread (bounded by `:drain-timeout-ms`, default 5000), shuts down the handler-pool, transitions to `:scheduler.state/inactive`.  Composes the inverse-side deactivation of `sandbar.schedule.start`.\n\nWHEN: use to fully release scheduler resources — typically at JVM shutdown via `sandbar.core/stop`, OR for operator-initiated lifecycle cycles.  When NOT to use: (a) just disabling without teardown — `sandbar.schedule.disable`; (b) restarting — call this verb then `sandbar.schedule.start` (no atomic restart verb).\n\nHOW: optional `:drain-timeout-ms` (default 5000).  Returns `{:outcome :stopped}` on success, `{:outcome :already-inactive}` when not running.  Idempotent."
    :inputSchema {:type "object"
                  :properties {:drain-timeout-ms {:type "integer"
                                                  :description "Max ms to wait for handler-pool drain (default 5000)"}}
                  :required []}
    :handler (fn [args]
               {:outcome (sched/stop! (cond-> {}
                                        (:drain-timeout-ms args)
                                        (assoc :drain-timeout-ms (:drain-timeout-ms args))))})}

   {:name "sandbar.schedule.add"
    :title "Add a :mm/Schedule to the priority queue"
    :description "WHICH: invokes `sandbar.schedule/add-schedule!` — resolves the :mm/Schedule entity by eid, computes its next-fire-at from `(now)` via the RRULE iterator (honoring `:mm.schedule/exdates` + `:mm.schedule/until`), inserts a `[next-fire-at schedule-eid]` entry into the priority queue, and `.interrupts` the fire-thread to re-park on the new head if appropriate.\n\nWHEN: use to bring a :mm/Schedule into the scheduler's active queue — either at boot (γ.5 demo job autostart) or via operator-initiated additions.  When NOT to use: (a) the Schedule entity doesn't exist yet — author it via `sandbar.entity.create :class :mm/Schedule` first; (b) you want to REMOVE — `sandbar.schedule.remove`.\n\nHOW: `:schedule-eid` is the numeric eid of the :mm/Schedule entity.  Returns `{:schedule-eid :next-fire-at <iso-string-or-nil>}`.  Returns next-fire-at nil when the schedule has no future fires (terminated RRULE / malformed schedule) — queue unchanged in that case.  Idempotent — re-adding an already-queued schedule replaces (not duplicates) its entry."
    :inputSchema (one-required
                   {:schedule-eid {:type "integer" :description "Numeric eid of the :mm/Schedule entity"}}
                   [:schedule-eid])
    :handler (fn [args]
               (let [eid (:schedule-eid args)
                     next-at (sched/add-schedule! eid)]
                 {:schedule-eid eid
                  :next-fire-at (when next-at (str next-at))}))}

   {:name "sandbar.schedule.remove"
    :title "Remove a :mm/Schedule from the priority queue"
    :description "WHICH: invokes `sandbar.schedule/remove-schedule!` — removes all queue entries for the given :mm/Schedule eid, `.interrupts` the fire-thread to re-park on the new head.\n\nWHEN: use to deschedule a :mm/Schedule without retracting the entity itself — e.g., temporary suppression while keeping the Schedule available for re-add later.  When NOT to use: (a) you want to permanently retract — combine with `sandbar.entity.update` or substrate-level retract; (b) you want to disable ALL fires (gate flag) — `sandbar.schedule.disable`.\n\nHOW: `:schedule-eid` is the numeric eid.  Returns `{:schedule-eid :outcome :removed}`.  Idempotent."
    :inputSchema (one-required
                   {:schedule-eid {:type "integer" :description "Numeric eid of the :mm/Schedule entity"}}
                   [:schedule-eid])
    :handler (fn [args]
               (sched/remove-schedule! (:schedule-eid args))
               {:schedule-eid (:schedule-eid args)
                :outcome      :removed})}

   {:name "sandbar.schedule.list"
    :title "Diagnostic: list all currently-queued schedule fires"
    :description "WHICH: returns the priority queue's current entries — each entry is `[next-fire-at-instant schedule-eid]`, in priority order (earliest fire first).\n\nWHEN: use for diagnostic introspection of what the scheduler will fire next.  When NOT to use: (a) you want the full operator snapshot (state-machine + handler-pool + in-flight runs) — `sandbar.schedule.inspect`.\n\nHOW: no arguments.  Returns `{:queue-size :entries [{:next-fire-at <iso-string> :schedule-eid <integer>} ...]}`."
    :inputSchema no-args-schema
    :handler (fn [_args]
               (let [q (sched/list-schedules)]
                 {:queue-size (count q)
                  :entries    (mapv (fn [[fire-at eid]]
                                      {:next-fire-at (str fire-at)
                                       :schedule-eid eid})
                                    q)}))}

   {:name "sandbar.schedule.inspect"
    :title "Diagnostic: full operator-facing scheduler runtime snapshot"
    :description "WHICH: returns the canonical operator-facing snapshot of scheduler runtime state — state-machine + enabled-flag + queue size + handler-pool allocated + fire-thread allocated + clock-drift + in-flight Runs + subscriber-registered flag.\n\nWHEN: use as the one-call operator-status verb — for MCP-driven dashboards, health-check tooling, debugging session-orientation.  When NOT to use: (a) you want only the queue entries — `sandbar.schedule.list` (smaller payload); (b) you want only the state keyword — there's no smaller verb; this one's payload is bounded.\n\nHOW: no arguments.  Returns the canonical inspect map keys: `:state :enabled? :queue-size :handler-pool? :fire-thread? :clock-drift-ms :in-flight-runs :subscriber-registered?`."
    :inputSchema no-args-schema
    :handler (fn [_args] (sched/inspect))}

   ;; Meta — verb-catalog self-introspection (Phase 3 of the mm/Verb-maximization
   ;; arc).  The running server searching + describing its OWN tool surface.
   {:name "sandbar.tools.search"
    :title "Find the right verb(s) for a task — BM25F over the verb catalog"
    :description "WHICH: ranked verb matches for a natural-language task intent — BM25F over the `:mm/Verb` catalog (sandbar's MCP surface modeled as substrate entities), returning lean verb cards (name / title / axis / transition-kind / read-only? / arg-summary / score).  Metacircular: the server searching its own tool surface.\n\nWHEN: use FIRST when you know WHAT you want to do but not WHICH verb does it — rank verbs by intent instead of scanning all ~80.  The retrieve half of retrieve-then-describe.  When NOT to use: (a) you already know the verb — call it directly; (b) you want the full card + composition edges for a known verb — `sandbar.tools.describe`; (c) searching CORPUS content (memories), not verbs — `sandbar.search.bm25f` against the corpus class.\n\nHOW: `:query` is a bag-of-words task intent (e.g. 'rank memories by recency', 'who cites this entity').  Optional `:limit` (default 10).  Optional `:axis` restricts to a verb family (e.g. ':navigate' / ':aggregate' / ':entity').  Returns `{:query :matches [{:verb :ident :title :axis :transition-kind :read-only? :arg-summary :score}...] :total :returned}`.\n\nORDER: leaf-call; the canonical FIRST step of verb discovery.\n\nCOMBINATION: feed a chosen verb into `sandbar.tools.describe` for the full card + prerequisites + combines-with neighbors, then call the verb itself."
    :inputSchema (one-required
                   {:query {:type "string" :description "Natural-language task intent (bag-of-words)"}
                    :limit {:type "integer" :description "Max verb matches (default 10)"}
                    :axis  {:type "string" :description "Optional verb-family filter (e.g. ':navigate' / ':aggregate' / ':entity')"}}
                   [:query])
    :handler tools-search-handler}
   {:name "sandbar.tools.describe"
    :title "Full verb card + typed composition edges for a named verb"
    :description "WHICH: the full card for one verb from the `:mm/Verb` catalog — title, axis, WHICH/WHEN/HOW, arg-summary, behavioral annotations (readOnly / destructive / idempotent / openWorld / transition-kind / hint-status), AND its typed composition edges: `:prerequisites` (verbs to call first), `:prerequisite-for` (verbs this one enables), `:combines-with` (verbs it composes with), `:produces-input-for` (verbs its output feeds).  Metacircular + graph-backed: the server describing its own verb, edges included.\n\nWHEN: use AFTER `sandbar.tools.search` (or when you already know a verb name) to understand a verb deeply + discover what to call before / with / after it.  The describe half of retrieve-then-describe.  When NOT to use: (a) ranked discovery across verbs — `sandbar.tools.search`; (b) only the raw wire input-schema — it is already in `tools/list`.\n\nHOW: `:verb` is the verb's wire name ('sandbar.entity.create') OR its ident (':sandbar.entity/create').  Returns the card map, or `{:missing? true}` if the verb is unknown.\n\nORDER: prerequisite — typically `sandbar.tools.search` (to pick the verb).\n\nCOMBINATION: the `:prerequisites` / `:combines-with` / `:produces-input-for` lists are themselves verb names — feed them back into `sandbar.tools.describe` to plan a multi-verb chain, or call them directly."
    :inputSchema (one-required
                   {:verb {:type "string" :description "Verb wire name (e.g. 'sandbar.entity.create') or ident (e.g. ':sandbar.entity/create')"}}
                   [:verb])
    :handler tools-describe-handler}])

(def ^:private verb-by-name
  (into {} (map (juxt :name identity)) verb-catalog))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Wire-name projection (dots→underscores) + one-release dotted-alias dispatch.
;;
;; The Anthropic API tool-NAME pattern ^[a-zA-Z0-9_-]{1,64}$ forbids dots, so a
;; client that forwards raw names (Claude Desktop/web frontend-remote-MCP,
;; Codex) bricks on the dotted catalog names.  The DURABLE server-side fix
;; (doc/concepts/mcp-protocol.md)
;; is to emit underscore names natively.  We keep the INTERNAL catalog identity
;; dotted — the leaf classifier (verb-behavioral-hints, split on "."), the
;; read-plane firewall (read-plane-registry-exempt-verbs, keyed by dotted
;; name), the read-only gate (verb-permitted-for-read-only?), and the prose
;; composition-graph (catalog-model/extract-refs, matches dotted tokens) all
;; parse the dotted form — and project to underscores ONLY at the wire
;; boundary.  Because the classifier/firewall never see the wire form, a verb's
;; read-only / destructive / exempt classification is provably IDENTICAL
;; pre/post rename (guarded by the §5.8 completeness invariant + the
;; tool-name-rename tests).  tools/list advertises ONLY the underscore names;
;; tools/call accepts BOTH the underscore name (canonical going forward) and
;; the deprecated dotted name (one-release alias, dispatched with a WARN).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn wire-name
  "Project an internal dotted verb name (\"sandbar.entity.find\") to its MCP
   WIRE name (\"sandbar_entity_find\").  Only the dotted SEGMENT separators
   become underscores; hyphens inside leaf tokens (\"validate-all-instances\",
   \"find-by-rel-path\") are preserved, so the result matches the Anthropic API
   pattern ^[a-zA-Z0-9_-]{1,64}$ for every client.  Pure boundary projection —
   the internal catalog identity stays dotted."
  [verb-name]
  (str/replace (str verb-name) "." "_"))

(def ^:private wire->canonical
  "Underscore WIRE name -> canonical dotted verb name, for tools/call dispatch
   of the NEW names.  Derived once from verb-catalog so it can never drift from
   the served surface."
  (into {} (map (fn [{:keys [name]}] [(wire-name name) name])) verb-catalog))

(defn- resolve-tool-name
  "Resolve an incoming tools/call `:name` — the NEW underscore wire form OR the
   DEPRECATED dotted form — to the canonical dotted verb name that keys
   verb-by-name / the classifier / the read-plane firewall.  Returns
   {:canonical <dotted-or-nil> :deprecated? <bool>}; :deprecated? is true iff
   the caller used the old dotted name (the alias kept alive for ONE release).
   Wire forms (dot-free) and dotted forms (≥2 dots) are disjoint key-spaces,
   so the resolution is unambiguous; an unrecognized name yields nil canonical."
  [raw-name]
  (cond
    (contains? wire->canonical raw-name) {:canonical (get wire->canonical raw-name) :deprecated? false}
    (contains? verb-by-name raw-name)    {:canonical raw-name                       :deprecated? true}
    :else                                {:canonical nil                            :deprecated? false}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Behavioral hints (MCP ToolAnnotations) — derived from the leaf action token.
;; ONE derivation feeding BOTH the wire annotations (handle-list, below) AND the
;; :mm/Verb entity hint-slots (sandbar.scripts.seed-verb-catalog/verb->slots).
;; hint-status is :asserted — the Phase-5 per-verb uplift authors :verified
;; overrides.  open-world? is false for every verb (closed memory substrate;
;; the MCP spec's literal closed-world example).  Part of the mm/Verb-
;; maximization arc (the ontology-hints ≡ MCP-annotations convergence).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private read-only-verb-leaves
  "Leaf action tokens whose verbs do NOT mutate the substrate."
  #{"count" "group-by" "rank-by" "tag-histogram" "fs-substrate-drift" "describe"
    "direct-slots" "instances" "parents" "required-slots" "slots" "subclasses"
    "validate-all-instances" "list" "find" "find-by-rel-path" "validate" "policy"
    "inbound-edges" "outbound-edges" "path-via" "siblings-of" "library-card"
    "tree" "type-tree" "export" "cardinality" "domain" "range" "health" "classes"
    "datatypes" "entities" "properties" "attribute" "bm25f" "search" "conformance-report"
    "inspect" "audit" "lookup" "instance-of" "subclass-of" "history" "results"
    "active-processes" "process-history" "process-state" "ground" "resolve"
   "recovery-check"})

(def ^:private destructive-verb-leaves
  "Mutating leaves that perform irreversible removal/cancellation."
  #{"remove" "cancel"})

(def ^:private idempotent-write-leaves
  "Mutating leaves whose repeated identical calls have no additional effect."
  #{"update" "enable" "disable"})

(defn verb-behavioral-hints
  "Derive catalog behavioral hints from the leaf action token.
   Returns read-only, destructive, idempotent, open-world, transition-kind
   and hint-status values. These are asserted classifications, not verified
   effects: export, for example, writes files despite its read-only hint."
  [verb-name]
  (let [leaf  (last (str/split (str verb-name) #"\."))
        ro?   (contains? read-only-verb-leaves leaf)
        dest? (and (not ro?) (contains? destructive-verb-leaves leaf))
        idem? (or ro? (contains? idempotent-write-leaves leaf))]
    {:read-only?      ro?
     :destructive?    dest?
     :idempotent?     idem?
     :open-world?     false
     :transition-kind (cond ro? :safe idem? :idempotent :else :unsafe)
     :hint-status     :asserted}))

(defn verb-annotations
  "MCP ToolAnnotations map (2025-11-25 shape) for a verb-catalog entry.
   destructiveHint is meaningful only when not read-only, so it is omitted for
   read-only verbs."
  [verb]
  (let [{:keys [read-only? destructive? idempotent? open-world?]}
        (verb-behavioral-hints (:name verb))]
    (cond-> {:readOnlyHint   read-only?
             :idempotentHint idempotent?
             :openWorldHint  open-world?}
      (:title verb)    (assoc :title (:title verb))
      (not read-only?) (assoc :destructiveHint destructive?))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/list — return the verb catalog (each tool enriched with annotations)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-list
  "MCP `tools/list` — return the stable verb catalog, each tool enriched with
   derived MCP `annotations` (read-only / destructive / idempotent / open-world
   hints) via verb-annotations.  Advertises ONLY the underscore WIRE names
   (wire-name) so every client passes the Anthropic tool-name pattern; the
   annotations are still derived from the original dotted `v` (the classifier
   parses the dotted form).  Catalog is constant regardless of schema state;
   schema evolution surfaces through the `sandbar.schema.*` + `sandbar.class.*`
   read verbs, not through tools/list."
  [id _params]
  (envelope/jsonrpc-result
   id
   {:tools (mapv (fn [v] (-> v
                             (dissoc :handler)
                             (assoc :annotations (verb-annotations v))
                             (assoc :name (wire-name (:name v)))))
                 verb-catalog)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read-only token gate — the single authorization choke point
;;
;; A read-only principal (util/auth read-only-role)
;; may call read/introspection verbs only.  Rather than maintain a verb
;; allowlist on the role, the gate DERIVES permission from the same
;; `verb-behavioral-hints` leaf classifier that produces the wire
;; ToolAnnotations — one source of truth for a verb's mutation-ness, so the
;; enforced set can never drift from the advertised set.  This is
;; deny-by-default: a future verb whose leaf is unknown classifies as
;; mutating and is rejected until proven read-only.
;; Read-only access rules are described in doc/auth.md.
;; Export is explicitly excluded because it writes the filesystem.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private read-only-denied-overrides
  "Verbs the leaf classifier calls read-only but which a read-only principal
   must NOT reach.  `sandbar.project.export` leaf-classifies read-only (its
   `export` leaf is in `read-only-verb-leaves`) yet WRITES the filesystem, so
   the derived allowlist gets exactly this one curated subtraction."
  #{"sandbar.project.export"})

(defn verb-permitted-for-read-only?
  "True iff a read-only principal may call the verb named `verb-name`: the
   verb classifies read-only via `verb-behavioral-hints` AND is not one of the
   curated `read-only-denied-overrides`.  An unknown/uncataloged leaf
   classifies as mutating, so this returns false — deny-by-default."
  [verb-name]
  (and (:read-only? (verb-behavioral-hints verb-name))
       (not (contains? read-only-denied-overrides verb-name))))

(defn- read-only-denied
  "The JSON-RPC permission-error envelope rejecting a mutating `verb-name` for
   a read-only principal.  Fails loud: names the verb + the principal's role +
   the role's read-only contract, so the caller learns exactly why."
  [id verb-name]
  (log/warn :MCP/read-only-denied {:tool verb-name :role auth/read-only-role})
  (envelope/jsonrpc-error id jsonrpc-status/invalid-params
                          (str "Permission denied: verb '" verb-name
                               "' mutates the substrate and the authenticated"
                               " principal holds the read-only role ("
                               auth/read-only-role
                               "), which may call read/introspection verbs only.")
                          {:tool verb-name
                           :role auth/read-only-role
                           :reason :read-only-principal-forbidden-mutation}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/call — dispatch verb by name; project result to MCP content array
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- safe-for-json
  "Walk a value tree; coerce values cheshire can't natively serialize
   (Class instances, functions, futures, refs, arbitrary Java objects)
   to string form.  Used at the MCP response boundary (`result->content`)
   so a stray Class object in handler return OR ex-data doesn't crash
   the JSON-encoding path + mask the actual error with a generic
   'Tool execution failed' (Bug C5 of substrate-stabilization arc).

   Preserves maps + vectors + sets + sequences structurally; leaves
   primitives + Dates + UUIDs + keywords + symbols alone (cheshire
   handles those natively).  Catch-all for any other type: coerce to
   (str v) — produces a readable representation (e.g. 'class
   clojure.lang.PersistentVector' for Class metaobjects) instead of
   triggering JsonGenerationException."
  [v]
  (cond
    (nil? v) v

    (or (string? v) (boolean? v) (number? v)
        (keyword? v) (symbol? v)
        (instance? java.util.Date v)
        (instance? java.util.UUID v))
    v

    (map? v)        (into {} (map (fn [[k v]] [k (safe-for-json v)]) v))
    (set? v)        (into #{} (map safe-for-json v))
    (vector? v)     (mapv safe-for-json v)
    (sequential? v) (mapv safe-for-json v)

    ;; Catch-all for Class instances / fns / futures / refs / arbitrary
    ;; Java objects — coerce to string.  Cheshire would otherwise throw
    ;; JsonGenerationException; that exception then escapes the user-error
    ;; envelope path + gets caught as generic Exception → masked error.
    :else (str v)))

(defn- result->content
  "Project a handler's return value to an MCP `content` array entry.
   Per MCP spec: `content` is an array of typed parts; `text` parts
   carry stringified content.  We JSON-encode the handler's return
   value for consistent client-side parsing.

   Pre-walks via `safe-for-json` to coerce non-serializable values
   (Class objects, fns, etc.) to string form — protects against
   ex-data + handler-return shapes that include type-mismatch reports,
   class metaobjects, or other Clojure values cheshire can't natively
   encode."
  [data]
  [{:type "text"
    :text (json/generate-string (safe-for-json data) {:pretty true})}])

(def ^:dynamic *slow-tool-call-threshold-ms*
  "A tools/call slower than this threshold logs one :MCP/slow-tool-call warning
   with the tool name and duration. It does not create a database event row."
  1000)

(defonce tool-call-counts
  ;; Verb-diversity metric (reliability sprint D4, 2026-09-19): how many
  ;; `tools/call` dispatches each canonical verb received since this process
  ;; started.  In memory only, reset on restart, read over nREPL; not a
  ;; database row and not a digest verb, per Dan's retention-review ruling
  ;; of 2026-09-19.  Unknown verbs and read-only denials are not dispatches
  ;; and are not counted.
  (atom {}))

(defn handle-call
  "MCP `tools/call` — dispatch a named verb from the catalog and project
   its result.

   `principal` (the 3-arity) is the authenticated MCP principal, or nil.  A
   read-only principal (util/auth read-only-role) is gated here: any verb that
   is not read-only-permitted is rejected with a permission error BEFORE the
   handler runs.  A nil principal (the legacy/local path) is ungated — full
   access, unchanged behavior.  The 2-arity is the nil-principal path,
   preserving the pre-gate call contract used by in-process callers.

   Response shapes:
   - Success: `{:content [{:type \"text\" :text <json>}]}`
   - User error (ex-info from handler): `{:content [...] :isError true}`
   - Unknown verb: JSON-RPC `invalid-params`
   - Read-only principal calling a mutating verb: JSON-RPC `invalid-params`
   - Precondition failure: JSON-RPC `internal-error`
   - Internal error: JSON-RPC `internal-error`

   Error codes via `sandbar.util.jsonrpc-status` (semantic constants)."
  ([id params] (handle-call id params nil))
  ([id params principal]
   (let [raw-name  (:name params)
         {:keys [canonical deprecated?]} (resolve-tool-name raw-name)
         arguments (:arguments params {})
         verb      (when canonical (get verb-by-name canonical))]
     ;; One-release dotted-alias: the deprecated dotted name still dispatches,
     ;; but WARN so callers migrate to the underscore wire name.  The classifier
     ;; / firewall / dispatch below all key off `canonical` (dotted), so a call
     ;; by either name resolves to the SAME verb with the SAME authorization.
     (when (and deprecated? canonical)
       (log/warn :MCP/deprecated-dotted-tool-name
                 {:received raw-name
                  :use      (wire-name canonical)
                  :note     "sandbar MCP tool names are now underscore-form; the dotted alias is deprecated and will be removed after one release"}))
     (cond
       (nil? verb)
       (envelope/jsonrpc-error id jsonrpc-status/invalid-params
                               (str "Unknown tool: " raw-name)
                               {:received-name raw-name
                                :available-tools (mapv (comp wire-name :name) verb-catalog)})

       ;; Read-only token gate — deny-by-default for a restricted principal.
       ;; Placed after the unknown-verb check (so an unknown verb still reports
       ;; as unknown, not as a permission failure) and before dispatch, the one
       ;; authorization choke point the whole verb surface funnels through.
       ;; Classifies the CANONICAL dotted name so a rename cannot flip a verb's
       ;; read-only class.
       (and (auth/read-only-principal? principal)
            (not (verb-permitted-for-read-only? canonical)))
       (read-only-denied id raw-name)

       :else
       (try
         (let [result (try
                        ;; RPAF v3 — central read-plane INPUT firewall (class/attr
                        ;; args) before dispatch; throws map to isError below.
                        ;; Keyed by the CANONICAL dotted name so the registry-
                        ;; exempt set stays coherent under the wire rename.
                        (assert-read-plane-call! canonical arguments)
                        (swap! tool-call-counts update canonical (fnil inc 0))
                        (let [t0 (System/nanoTime)
                              ;; D4b / CT-03: the principal is bound for the
                              ;; read plane so every projected entity — a find,
                              ;; a search hit, a navigation target, a nested
                              ;; ref — passes the one visibility decision.
                              r  (binding [visibility/*principal* principal]
                                   ((:handler verb) arguments))
                              ms (quot (- (System/nanoTime) t0) 1000000)]
                          (when (> ms *slow-tool-call-threshold-ms*)
                            (log/warn :MCP/slow-tool-call {:tool canonical :ms ms}))
                          r)
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
         ;; See doc/concepts/memory-model.md §D-3.3.
         (catch AssertionError e
           (log/error e :MCP/precondition-failed {:tool canonical})
           (envelope/jsonrpc-error id jsonrpc-status/internal-error
                                   "Internal-invariant precondition failed at MCP boundary"
                                   {:tool canonical
                                    :assertion (.getMessage e)}))
         (catch Exception e
           (log/error e :MCP/tools-call-error {:tool canonical})
           (envelope/jsonrpc-error id jsonrpc-status/internal-error
                                   "Tool execution failed"
                                   {:tool canonical
                                    :exception-message (.getMessage e)})))))))
