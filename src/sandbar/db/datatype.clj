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
            [sandbar.db.datomic :refer [entity describe] :as db]
            [sandbar.reactive :as reactive]))

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

  Note: Use `make` instead for validated instance creation.

  Bug C10 fix (2026-05-22): the entity is identified by a NAMED
  string tempid so the post-transact eid lookup is deterministic.
  The prior implementation used `(-> result :tempids vals first
  entity)`, which is unsound when the transact contains MORE THAN
  ONE tempid — e.g., when props carries cardinality-many ref slots
  whose values are `:db.unique/identity` upsert-maps (each generates
  its own tempid).  `(first (vals ...))` over an unordered tempids
  map then non-deterministically returns the wrong entity.

  Named-tempid lookup ensures we always recover the MAIN entity
  regardless of how many secondary tempids the upsert resolution
  produces.  If `props` already declares `:db/id`, that takes
  precedence (caller-explicit identity wins)."
  ([dt] (make* dt {}))
  ([dt props]
   (let [main-tid    (or (:db/id props) "main")
         row         (assoc props :dt/type dt :db/id main-tid)
         result      @(d/transact (db/conn) [row])
         new-eid     (get (:tempids result) main-tid main-tid)
         new-entity  (entity new-eid)]
     (log/debug :DT/MAKE {:class dt :entity-id (:db/id new-entity)})
     new-entity)))

(declare slots-of)  ; forward reference; defined later in this ns

(defn unique-of
  "Returns the `:db/unique` value of a slot (`:db.unique/identity` /
   `:db.unique/value` / nil) — looks up the Property entity by ident
   via the live Datomic connection.

   Added 2026-05-20 for the bootstrap-memory-substrate sub-arc — needed
   by `sandbar.codec.markdown/frontmatter->slots` to resolve string
   values at ref-typed slots as unique-identity upsert maps without
   hardcoding consumer-class knowledge."
  [slot]
  (when slot
    (some-> slot entity :db/unique)))

(defn unique-identity-slot-of
  "Returns the FIRST `:db.unique/identity` slot declared on `class-ident`,
   or nil if none exists.  Used by `sandbar.codec.markdown` to wrap
   string values at ref-typed slots as upsert maps without hardcoding
   `{:mm/Tag :mm.tag/value}` consumer-class knowledge."
  [class-ident]
  (some (fn [slot]
          (when (= :db.unique/identity (unique-of slot))
            slot))
        (slots-of class-ident)))

(defn make-all*
  "Creates a batch of typed instances in a SINGLE atomic Datomic
   transaction — WITHOUT validation.  Batch analog of `make*` extending
   the `make` / `make*` validated / no-validation parallelism to the
   batch shape; `make-all` (validated batch) reserved for future
   addition.

   Arguments:
     entity-specs - vec of entity-spec maps; each carries `:dt/type` +
                    sandbar / Datomic keys (`:db/ident`, slot idents).

   Returns the Datomic transaction result map.

   Use when multiple entities must be created atomically with cross-
   references intact — e.g., an `mm/Memory` plus its child `mm/Section`
   entities from one corpus markdown file.  Cross-entity refs resolve
   via Datomic's `:db/ident` upsert semantics within the single tx;
   forward references inside the batch resolve at transaction time.

   For single-entity creation with pre-transaction validation, use
   `make` instead.  For single-entity without validation, use `make*`.
   For batch creation WITH validation, use `make-all` (TBD — not yet
   defined).

   Added 2026-05-20 per F#17 of memory/plans/sandbar_0_1_1_coevolution_-
   arc_2026_05_20.md — `sandbar.project.import :persist? true` needs to
   transact each markdown file's memory + sections atomically so that
   `:mm.memory/first-section` and `:mm.section/parent` cross-refs
   resolve via :db/ident upsert."
  [entity-specs]
  (let [result @(d/transact (db/conn) entity-specs)]
    (log/debug :DT/MAKE-ALL* {:count (count entity-specs)})
    result))

(declare validate-data)          ;; forward declaration
(declare type-isa?)              ;; forward reference; defined later in this ns
(declare coerce-ref-slot-values) ;; forward reference; defined with ref->eid

(def ^:dynamic *default-actor*
  "Ident (keyword) or eid of the actor on whose behalf substrate writes
   are performed — or nil.  When bound (e.g. by an MCP / orchestrator
   boundary that knows the calling actor), `make` defaults
   `:mm.memory/created-by` to it for :mm/Memory subclasses.  nil ⇒ no
   created-by default (provenance is left unset, never fabricated)."
  nil)

(defn- apply-memory-defaults
  "For :mm/Memory subclasses, supply provenance/temporal slots the caller
   omitted: `:mm.memory/created` + `:mm.memory/last-touched` ⇒ now;
   `:mm.memory/created-by` ⇒ [*default-actor*] when that var is bound.
   Absent-only — explicit slots AND codec-parsed frontmatter both win
   (this runs AFTER the codec merge in `make`).  No-op for non-:mm/Memory
   classes.  Root fix for MCP-/programmatically-created memorials that
   lacked these slots and therefore dropped out of `:mm.memory/last-touched`
   recency views (e.g. arcs created via `entity.create`)."
  [dt props]
  (if (type-isa? :mm/Memory dt)
    (let [now (java.util.Date.)]
      (cond-> props
        (not (contains? props :mm.memory/created))
        (assoc :mm.memory/created now)
        (not (contains? props :mm.memory/last-touched))
        (assoc :mm.memory/last-touched now)
        (and *default-actor* (not (contains? props :mm.memory/created-by)))
        (assoc :mm.memory/created-by [*default-actor*])))
    props))

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
  ([dt props {:keys [validate? format source project?] :or {validate? true}}]
   ;; F.1 codec arc Stage F per
   ;; plans/sandbar_codec_layer_arc_2026-05-12.md — when :format +
   ;; :source supplied, parse via the codec mediator first; explicit
   ;; props override parsed slots.
   ;;
   ;; Signal 3 (Stage G analysis) — when :source is provided WITHOUT
   ;; explicit :format, fall back to the class's :dt/native-codec
   ;; attribute (the mediator's class-default resolution semantics).
   ;; Symmetric with codec/parse's class-aware default.
   ;;
   ;; Stage A.5 of SSE-reactive-projection arc (decision eid
   ;; 17592186094347 + plan eid 17592186094359): on successful create,
   ;; invoke `reactive/on-entity-changed!` to fire the reactive-
   ;; projection hook.  `:project?` kwarg participates in three-layer
   ;; opt-out resolution (per-call kwarg > dynamic binding > class-
   ;; level skip-list).  Hook is a no-op when no callbacks registered.
   (let [resolved-format (or format
                             (when source
                               (:dt/native-codec (entity dt))))
         props (if (and resolved-format source)
                 (let [parse-fn (requiring-resolve 'sandbar.codec/parse)
                       parsed   (parse-fn source {:format resolved-format :class dt})]
                   (merge (dissoc parsed :dt/type) props))
                 props)
         ;; Memorial-defaults: AFTER the codec merge (so explicit slots +
         ;; parsed frontmatter both win), absent-only.  entity.create-
         ;; defaults fix — MCP/programmatic :mm/Memory creates were missing
         ;; created/last-touched/created-by and fell out of recency views.
         props (apply-memory-defaults dt props)
         ;; Canonicalize `:db.type/ref` slot values to plain eids BEFORE both
         ;; validation and transact so the two agree — an eid / EntityMap /
         ;; {:db/id} / {:db/ident} at a ref slot all reduce to the eid Datomic
         ;; attaches, curing the reject-or-silently-drop split.  Per
         ;; bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md.
         props (coerce-ref-slot-values props)
         new-entity (if-not validate?
                      (make* dt props)
                      (if-let [errors (validate-data dt props)]
                        (do
                          (log/debug :DT/VALIDATION-FAILED {:class dt :errors errors})
                          (throw (ex-info "Validation failed" errors)))
                        (make* dt props)))]
     (reactive/on-entity-changed! dt new-entity project?)
     new-entity)))

(defn make-all
  "Creates a batch of typed instances in a SINGLE atomic Datomic
   transaction WITH pre-transaction validation.  Batch analog of `make`
   extending the `make` / `make*` validated / unvalidated parallelism
   to the batch shape (symmetric with `make-all*` which is the
   unvalidated batch counterpart).

   Arguments:
     entity-specs - vec of entity-spec maps; each carries `:dt/type` +
                    sandbar / Datomic keys (`:db/ident`, slot idents).

   Returns the Datomic transaction result map (same as `make-all*`).

   Validates EVERY spec via `validate-data` before transacting; if ANY
   spec fails validation, raises ex-info with `:errors` carrying per-
   index per-class failure detail and transacts NONE of them (atomic
   all-or-nothing).  The error envelope shape:

     {:errors [{:errors [...] :index <int> :class <ident>} ...]
      :total  <int>}

   Cross-entity refs resolve via Datomic's `:db/ident` upsert semantics
   within the single tx; forward references inside the batch resolve
   at transaction time (same semantics as `make-all*`).

   For batch creation WITHOUT validation (faster; trust-caller path,
   e.g. corpus-bulk-import where the codec has pre-validated), use
   `make-all*` instead.  For single-entity creation, use `make`
   (validated) or `make*` (unvalidated).

   Per Phase 1 B.4 of substrate-stabilization arc + Dan-directive
   2026-05-22 — the validated-batch verb is `make-all` (NOT
   `make-all-validated`); the naming convention is bare-name for
   validated, `*` suffix for unvalidated.

   Stage A.5 of SSE-reactive-projection arc (decision eid 17592186094347
   + plan eid 17592186094359): added optional opts map carrying
   `:project?` kwarg.  After the batch transaction commits, iterates
   entity-specs + invokes `reactive/on-entity-changed!` per entity that
   carries `:db/ident` or `:db/id` (anonymous specs are skipped —
   reactive-projection requires a resolvable post-tx entity to operate
   on).  Per-spec hook failures don't abort the batch (the substrate
   already transacted; reactive side-effects are observability-grade)."
  ([entity-specs] (make-all entity-specs {}))
  ([entity-specs {:keys [project?]}]
   (let [failures (keep-indexed
                    (fn [i spec]
                      (let [dt        (:dt/type spec)
                            spec-only (dissoc spec :dt/type)]
                        (when-let [errs (validate-data dt spec-only)]
                          (assoc errs :index i :class dt))))
                    entity-specs)]
     (if (seq failures)
       (do
         (log/debug :DT/MAKE-ALL-VALIDATION-FAILED
                    {:total (count entity-specs) :failures (count failures)})
         (throw (ex-info "Validation failed for one or more entities"
                         {:errors (vec failures)
                          :total  (count entity-specs)})))
       (let [tx-result (make-all* entity-specs)]
         ;; Per-entity reactive-projection hook fire
         (doseq [spec entity-specs
                 :let [class-ident  (:dt/type spec)
                       ident-or-eid (or (:db/ident spec) (:db/id spec))]
                 :when (and class-ident ident-or-eid)]
           (try
             (when-let [ent (entity ident-or-eid)]
               (reactive/on-entity-changed! class-ident ent project?))
             (catch Throwable t
               (log/warn t :REACTIVE/make-all-hook-skipped
                         {:spec-class class-ident
                          :spec-ident ident-or-eid}))))
         tx-result)))))

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
   `sandbar.projection` entity-collection paths."
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

;; --- Cardinality-many REPLACE semantics (W0.found 2026-06-30) ---
;; Per decisions/entity_update_card_many_replace_by_default_opt_in_additive_2026_06_30:
;; update-entity! REPLACES a card-many slot's set by default (retract the
;; prior members absent from the supplied set, then assert the supplied
;; set); callers opt into the legacy additive UNION via {:additive? true}.
;; Reuses the set-replace diff shape proven in sandbar.db.datomic for class
;; meta-slots (normalize refs by :db/ident; retract refs by :db/id).

(defn- ref->eid
  "Resolve any ref-typed slot value to the :db/id of the live entity it names,
   or nil when it resolves to no live entity.

   Accepts every shape a caller can hand a `:db.type/ref` slot: a Datomic
   Entity map (read :db/id directly), a `{:db/id eid}` map, an eid Long, an
   ident keyword, a Datomic lookup-ref vector `[:unique-attr v]`, and a
   single-key upsert map `{:db/ident kw}` / `{<unique-identity-attr> v}` (the
   codec's ref shape) — converted to a lookup-ref before resolution because
   `db/entity` returns an associative value UNCHANGED (so an upsert map would
   otherwise resolve to itself and yield a nil :db/id).

   The single ref→eid canon shared by two callers that MUST agree: the
   card-many replace diff (stable set-membership comparison) and `make`'s
   pre-transact ref coercion.  nil ⇒ unresolvable — treated as a non-matching
   member by the diff, and (in `make`) left uncoerced so validation rejects it
   loudly rather than silently dropping it.

   Per bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md
   (validation-and-transaction disagreed on ref shapes; upsert maps validated
   then silently dropped on the single-tx create path)."
  [v]
  (cond
    (nil? v)                                    nil
    (and (associative? v) (contains? v :db/id)) (:db/id v)
    ;; Single-key upsert map (e.g. {:db/ident kw}): db/entity returns an
    ;; associative value as-is, so resolve via an explicit lookup-ref.
    (and (map? v) (= 1 (count v)))
    (let [[k val] (first v)]
      (some-> (try (db/entity [k val]) (catch Throwable _ nil)) :db/id))
    :else (some-> (try (db/entity v) (catch Throwable _ nil)) :db/id)))

(defn- ref-valued-slot?
  "True if `slot-ident`'s property is a `:db.type/ref` slot (its values name
   other entities rather than carrying literals)."
  [slot-ident]
  (= :db.type/ref (:db/valueType (entity slot-ident))))

(defn- coerce-ref-slot-values
  "Canonicalizes every `:db.type/ref` slot value in `props` to a plain eid via
   `ref->eid`, returning the rewritten props map.  Card-one slots coerce the
   lone value; card-many slots coerce each member.  A value `ref->eid` cannot
   resolve is left UNCHANGED so downstream validation rejects it loudly —
   coercion never fabricates or silently drops.

   Why this exists: the single-entity `make`/`make*` create path transacted
   ref values verbatim, so an eid or a Datomic EntityMap failed `:dt/Ref`
   validation while a `{:db/id eid}` / `{:db/ident kw}` map validated and then
   silently dropped (Datomic does not attach a bare nested map at a card-one
   ref).  Coercing to the canonical eid — the same shape the update path's
   card-many diff already normalizes to — makes validation and transaction
   agree on every accepted ref shape.

   Per bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md."
  [props]
  (reduce-kv
   (fn [acc slot v]
     (if (and (some? v) (ref-valued-slot? slot))
       (let [coerce-one (fn [x] (or (ref->eid x) x))]
         (assoc acc slot
                (cond
                  (set? v)        (into #{} (map coerce-one) v)
                  (sequential? v) (into (empty v) (map coerce-one) v)
                  :else           (coerce-one v))))
       (assoc acc slot v)))
   {}
   props))

(defn- card-many-replace-retracts
  "For each cardinality-many slot present in `slot-updates`, return the
   [:db/retract eid slot v] ops removing prior members NOT in the supplied
   desired set — the retract half of replace-by-diff.  Card-one slots
   produce no retracts (Datomic auto-retracts the prior single value on
   assert).  Ref slots canonicalize BOTH prior and desired to :db/id so an
   unchanged member is never retracted-and-re-added in the same tx (which
   Datomic would resolve ambiguously); scalar slots compare by value."
  [ent eid slot-updates]
  (mapcat
   (fn [[slot new-val]]
     (let [prop (entity slot)]
       (when (= :db.cardinality/many (:db/cardinality prop))
         (let [ref?    (= :db.type/ref (:db/valueType prop))
               canon   (if ref? ref->eid identity)
               new-vec (if (sequential? new-val) new-val [new-val])
               desired (set (map canon new-vec))
               prior   (get ent slot)]
           (for [v prior
                 :when (not (contains? desired (canon v)))]
             [:db/retract eid slot (if ref? (ref->eid v) v)])))))
   slot-updates))

(defn update-entity!
  "Update slot values on an existing entity.

  Arguments:
    entity        - the entity (entity-map / :db/id / :db/ident keyword)
    slot-updates  - map of {:slot-ident new-value ...}
    opts          - optional:
                    :validate? - default true; if false, skips validation
                    :additive? - default false.  When true, cardinality-many
                                 slots UNION (append) instead of REPLACE.

  Behavior:
  - Resolves entity to its current entity-map shape
  - Merges slot-updates onto the existing slot values
  - When `:validate? true` (default), runs validate-data against the
    merged shape using the entity's class; throws ex-info on failure
  - Transacts {:db/id <eid> slot-updates...} via Datomic
  - Returns the refreshed entity map

  Cardinality-many slots: the supplied value REPLACES the prior set by
  default — prior members absent from the supplied value are retracted in
  the same transaction (retract (prior - desired) + assert desired).  Pass
  `{:additive? true}` to keep the legacy additive UNION (append without
  retracting).  Card-one slots are unaffected either way (Datomic
  auto-retracts the prior single value on assert).  Per
  decisions/entity_update_card_many_replace_by_default_opt_in_additive_2026_06_30.

  Per codex SHOULD-FIX #5 — `sandbar.entity.update` MCP verb advertised
  in the catalog but threw not-yet-implemented; this primitive closes
  that gap.  Per the improve-abstraction-not-bypass discipline (the
  prior gap-throw lampshade pointed exactly here)."
  ([entity slot-updates] (update-entity! entity slot-updates {}))
  ([entity slot-updates {:keys [validate? project? additive?] :or {validate? true}}]
   (when-not (map? slot-updates)
     (throw (ex-info "update-entity! requires slot-updates to be a map"
                     {:received slot-updates})))
   (let [ent     (cond
                   (associative? entity) entity
                   :else (db/entity entity))
         eid     (or (:db/id ent)
                     (throw (ex-info "update-entity! could not resolve :db/id"
                                     {:entity entity})))
         ;; Inline class-ident lookup (class-ident-of is defined below
         ;; in this file; avoid forward-reference for compile order)
         class-ident (:dt/type ent)
         ;; Merged shape — existing + updates (updates win).
         merged  (merge (into {} ent) slot-updates)]
     (when-not class-ident
       (throw (ex-info "update-entity! requires entity to have :dt/type"
                       {:entity entity :merged merged})))
     (when validate?
       (when-let [errors (validate-data class-ident (dissoc merged :db/id :dt/type))]
         (log/debug :DT/UPDATE-VALIDATION-FAILED {:class class-ident :errors errors})
         (throw (ex-info "Validation failed on update" errors))))
     ;; Transact: assert the supplied slot values.  For cardinality-many
     ;; slots this REPLACES the prior set (retract prior members absent
     ;; from the supplied set, prepended so they execute before the assert
     ;; in the same tx) unless the caller opts into additive UNION via
     ;; :additive? true.  Card-one slots are unaffected (Datomic
     ;; auto-retracts the prior value on assert).  Per
     ;; decisions/entity_update_card_many_replace_by_default_opt_in_additive_2026_06_30.
     (let [retracts (when-not additive?
                      (card-many-replace-retracts ent eid slot-updates))]
       (when (seq retracts)
         (log/info :DT/UPDATE-CARD-MANY-REPLACE
                   {:eid eid :class class-ident :retract-count (count retracts)}))
       @(d/transact (db/conn)
                    (into (vec retracts) [(assoc slot-updates :db/id eid)])))
     ;; Stage A.5 of SSE-reactive-projection arc (decision eid
     ;; 17592186094347 + plan eid 17592186094359): on successful update,
     ;; fire the reactive-projection hook.  `:project?` participates in
     ;; the three-layer opt-out priority resolution.  No-op when no
     ;; callbacks registered.
     (let [updated-entity (db/entity eid)]
       (reactive/on-entity-changed! class-ident updated-entity project?)
       updated-entity))))

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

(defn codec-slot-order-of
  "Returns the canonical slot ordering declared on the class via the
  `:dt/codec-slot-order` schema attribute, as a vec of slot-idents in
  emit order; or `[]` if none declared.

  Schema shape: `:dt/codec-slot-order` is cardinality-many; each entry
  is a `[slot-ident position]` heterogeneous tuple (declared via
  `:db/tupleTypes [:db.type/keyword :db.type/long]`).  This function
  sorts by position and projects to slot-idents.

  Used by codecs (e.g., `sandbar.codec.markdown/emit-frontmatter`) for
  class-declared canonical ordering — replaces the prior `:codec/key-order`
  metadata threading pattern that carried source-text order through
  parse → entity → emit.  The class declaration is the introspectable
  source of truth; source-text accident is not preserved.

  Sister to `codec-aliases-of` / `codec-type-keyword-of` — same
  introspection-via-schema pattern, different attribute.

  Per decisions/slot_order_declared_by_class_introspectable_2026_05_20.md
  (Dan-directive 2026-05-20: 'slot order should be declared by the class
  and introspectable')."
  [class-ident]
  (->> (db/entity class-ident)
       :dt/codec-slot-order
       (sort-by second)
       (mapv first)))

(defn codec-type-keywords-of
  "Returns the SET of `:dt/codec-type-keyword` values declared on the
  class, or #{} if none.

  Per-class CLASS-ROUTING keyword set: when a markdown document's
  frontmatter carries `type: <kw>`, the codec routes to the class
  whose `:dt/codec-type-keyword` set CONTAINS `<kw>`.  Cardinality-
  many so one class can claim multiple routing keywords (e.g.,
  :mm/Actor claims both :ai-actor and :human-actor).  Example:
  :mm/Tag declares `:dt/codec-type-keyword :tag` so files with
  `type: tag` parse as :mm/Tag entities.

  Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
  Stage 7.C codec class-routing + decisions/actor_as_first_class_metamodel_class_with_mm_actor_slots_2026_05_20.md
  (cardinality bumped to :many for multi-keyword class routing)."
  [class-ident]
  (or (:dt/codec-type-keyword (db/entity class-ident)) #{}))

(defn class-for-codec-type-keyword
  "Returns the class-ident whose `:dt/codec-type-keyword` matches
  `type-kw`, or nil if no class claims that type-keyword.

  Used by `sandbar.codec.markdown/parse-document` for metamodel-driven
  class routing — when a frontmatter's `type:` value matches a class's
  declared type-keyword, the codec parses the document as that class.

  Schema-attribute is `:db.unique/identity` per meta.edn, so the lookup
  is an O(1) resolution via Datomic's unique-identity index.  Bypasses
  `sandbar.db.datomic/entity` because that wrapper treats vectors as
  already-associative and short-circuits before `d/entity` runs — the
  lookup-ref shape would never reach Datomic.  We call `d/entity`
  directly with the lookup-ref instead.

  Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
  Stage 7.C."
  [type-kw]
  (when type-kw
    (when-let [e (d/entity (db/db) [:dt/codec-type-keyword type-kw])]
      (:db/ident e))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fulltext primitives — Stage 2 of fulltext arc
;; (plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md)
;;
;; Three primitives at the dt/* substrate layer:
;;   bm25f-weights-of  — class-attribute getter for :dt/bm25f-weights
;;                       (sibling of codec-aliases-of)
;;   fulltext-indexed? — predicate over a slot's :db/fulltext flag
;;   search-fulltext   — single-attribute Datomic+Lucene query wrapper
;;
;; Higher-level multi-field BM25F composition lives at sandbar.search/*
;; (Stage 4); the dt/* layer exposes per-field primitives only.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn bm25f-weights-of
  "Returns the per-class BM25F field-weight map declared on the class via
  the `:dt/bm25f-weights` schema attribute, or `{}` if none.

  Schema shape: `:dt/bm25f-weights` is cardinality-many; each entry is a
  `[slot-ident weight-double]` heterogeneous tuple (declared via
  `:db/tupleTypes [:db.type/keyword :db.type/double]`).  This function
  reconstructs the map for fulltext consumers.

  Used by `sandbar.search/search-bm25f` (Stage 4) as the default
  field-weights when no `:field-weights` opt is supplied at query
  time.  A per-query `:field-weights` opt overrides; this getter
  surfaces the class-declared baseline.

  Sister to `codec-aliases-of` — same shape pattern, different attribute.
  The naming follows the algorithm-specific convention (`bm25f-weights`
  not `weights`) per
  interaction/check_substrate_schema_attribute_names_against_rdf_owl_semantics_2026_05_13.md
  to disambiguate from any RDF/OWL weighted-axiom semantics.

  Per fulltext arc Stage 2 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [class-ident]
  (into {} (or (:dt/bm25f-weights (db/entity class-ident)) [])))

(defn memorial-policy-of
  "Returns the `:dt/memorial-policy` keyword declared directly on `class-ident`,
  or nil if undeclared.  One of `:first-class` / `:db-only` / `:inline`.

  Does NOT walk ancestors — call `effective-memorial-policy-of` for
  inheritance.  Sister to `bm25f-weights-of` / `codec-aliases-of` —
  same single-class shape, different attribute.

  Per `decisions/option_b_plus_c_ratified_spec_vs_state_criterion_pivot_to_first_class_memorialization_2026_05_23.md`
  + first-class-memorialization arc Stage B.3 (substrate enforcement
  wiring).  Consumed by `sandbar.reactive.sinks/fs-projection-sink`
  + (future) `sandbar.project.dump-db-only` worker."
  [class-ident]
  (:dt/memorial-policy (db/entity class-ident)))

(defn fulltext-indexed?
  "Returns true if `attribute` (a slot/property ident) is declared with
  `:db/fulltext true`, false otherwise.

  Substrate-level predicate; consumers use this to validate that an
  attribute is fulltext-searchable before invoking `search-fulltext`,
  or to enumerate the fulltext-indexed slots of a class via
  `(filter fulltext-indexed? (slots-of class))`.

  Reads directly off the property entity — no traversal of `:dt/type`
  or domain/range; the `:db/fulltext` Datomic-native flag is the
  source of truth.

  Per fulltext arc Stage 2 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [attribute]
  (boolean (:db/fulltext (db/entity attribute))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aggregation primitives — Stage 13 of fulltext arc
;; (plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md)
;;
;; Six substrate primitives at the dt/* layer:
;;   count-of            — entity count for class (optional where-clauses)
;;   group-by-of         — group-by-count {value count} map
;;   degree-of           — outbound + inbound ref-attribute count
;;   backlink-density-of — inbound-only ref-attribute count
;;   recency-rank-of     — entities ordered by temporal slot descending
;;   freshness-rank-of   — entities ordered by temporal slot ascending
;;
;; Higher-level composition lives at sandbar.aggregate namespace.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn count-of
  "Count instances of `class-ident` (including subclasses) matching the
  optional `where-clauses`.  Returns a non-negative integer.

  The 1-arity counts all instances; the 2-arity adds Datalog clauses
  (which must reference `?e` as the entity variable) for further
  restriction.  Substrate-quality: class-agnostic; the class binding
  drives the query.

  Per fulltext arc Stage 13 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  ([class-ident]
   (count-of class-ident nil))
  ([class-ident where-clauses]
   (let [base    '[:find (count ?e) .
                   :in $ % ?class
                   :where (instance-of ?class ?e)]
         merged  (if (seq where-clauses)
                   (apply conj base where-clauses)
                   base)
         result  (d/q merged (db/db) (all-rules) class-ident)]
     (or result 0))))

(defn group-by-of
  "Group instances of `class-ident` by `group-slot` value; return
  `{slot-value count}` map.  Optional `where-clauses` restrict the
  candidate set before grouping.

  Skips entities where the slot is unset (does not appear in any group).

  Per fulltext arc Stage 13 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  ([class-ident group-slot]
   (group-by-of class-ident group-slot nil))
  ([class-ident group-slot where-clauses]
   (let [base    '[:find ?v (count ?e)
                   :in $ % ?class ?slot
                   :where
                   (instance-of ?class ?e)
                   [?e ?slot ?v]]
         merged  (if (seq where-clauses)
                   (apply conj base where-clauses)
                   base)
         rows    (d/q merged (db/db) (all-rules) class-ident group-slot)]
     (into {} rows))))

(defn degree-of
  "Total ref-attribute count for `entity-ident` — number of (attribute,
  ref-target) outbound pairs plus inbound pairs.  Counts ALL ref-typed
  attributes by default; pass `:predicates` opt to restrict to a
  predicate set.

  Direction options:
    :forward       — outbound only
    :inverse       — inbound only
    :bidirectional — sum of both (default)

  Per fulltext arc Stage 13."
  ([entity-ident]
   (degree-of entity-ident {:direction :bidirectional}))
  ([entity-ident {:keys [direction predicates]
                  :or   {direction :bidirectional}}]
   (let [eid       (:db/id (db/entity entity-ident))
         out-rows  (when (#{:forward :bidirectional} direction)
                     (d/q '[:find ?a ?v
                            :in $ ?e
                            :where
                            [?e ?a ?v]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         ;; F-MF-2 fix (Phase R Stage R-3): inverse rows project
         ;; ?a (attribute) in position 0 to match the out-rows shape;
         ;; the `match?` predicate destructures `[a _]` (attribute
         ;; first), so both row shapes must align.  Pre-fix:
         ;; `:find ?s ?a` placed the source in position 0 and the
         ;; attribute in position 1; `match?` then read the SOURCE
         ;; as the attribute and the predicate filter silently
         ;; missed every inverse row (returned 0 for any
         ;; :predicates-filtered :inverse / :bidirectional call).
         ;; Aligning to `:find ?a ?s` restores per-direction
         ;; symmetry without per-direction match functions.
         in-rows   (when (#{:inverse :bidirectional} direction)
                     (d/q '[:find ?a ?s
                            :in $ ?e
                            :where
                            [?s ?a ?e]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         match?    (if (seq predicates)
                     (let [predicate-set (set predicates)]
                       (fn [[a _]]
                         (predicate-set
                           (or (:db/ident (db/entity a)) a))))
                     (constantly true))]
     (+ (count (filter match? out-rows))
        (count (filter match? in-rows))))))

(defn backlink-density-of
  "Inbound ref-attribute count for `entity-ident`.  Counts entities
  that have any ref-typed attribute pointing at this entity.

  Equivalent to `(degree-of entity-ident {:direction :inverse})`;
  named separately because backlink-density is a distinct retrieval
  axis from edge-degree per
  `decisions/multi_axis_search_catalog_2026_05_08.md` axes 6 vs 7.

  Per fulltext arc Stage 13."
  ([entity-ident]
   (backlink-density-of entity-ident nil))
  ([entity-ident predicates]
   (degree-of entity-ident {:direction :inverse :predicates predicates})))

(defn recency-rank-of
  "Return instances of `class-ident` ordered by `temporal-slot` value
  DESCENDING (most-recent first).  Returns a vec of `[entity-map
  temporal-value]` pairs; consumers may project to entities-only via
  `(map first ...)`.

  Caller supplies `temporal-slot` (e.g., `:mm.memory/last-touched`) —
  substrate does not hardcode class-specific temporal axes.

  Per fulltext arc Stage 13."
  [class-ident temporal-slot]
  (->> (d/q '[:find ?e ?t
              :in $ % ?class ?slot
              :where
              (instance-of ?class ?e)
              [?e ?slot ?t]]
            (db/db) (all-rules) class-ident temporal-slot)
       (sort-by second #(compare %2 %1))
       (mapv (fn [[eid t]] [(db/entity eid) t]))))

(defn freshness-rank-of
  "Return instances of `class-ident` ordered by `temporal-slot` value
  ASCENDING (oldest / stalest first — the freshness axis surfaces
  candidates whose temporal marker is most-in-the-past, meriting
  attention or review).  Returns a vec of `[entity-map temporal-value]`
  pairs.

  Caller supplies `temporal-slot` (typically a `:last-reviewed`-style
  attribute) — substrate does not hardcode class-specific axes.

  Per fulltext arc Stage 13."
  [class-ident temporal-slot]
  (->> (d/q '[:find ?e ?t
              :in $ % ?class ?slot
              :where
              (instance-of ?class ?e)
              [?e ?slot ?t]]
            (db/db) (all-rules) class-ident temporal-slot)
       (sort-by second)
       (mapv (fn [[eid t]] [(db/entity eid) t]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Navigation primitives — Stage 16 (fulltext arc Phase N)
;;
;; Edge = a (predicate-attribute, entity) pair where predicate-attribute is
;; a `:db.type/ref`-typed attribute.  Outbound = edges originating FROM the
;; subject; inbound = edges pointing AT the subject.  Substrate-quality
;; discipline: no hardcoded class/predicate knowledge in primitives.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn outbound-edges-of
  "Outbound typed-edges from `entity-ident` — `:db.type/ref` attribute
  pairs originating FROM the entity.  Returns a vec of maps:

    [{:predicate <pred-ident> :target <entity-map>} ...]

  Optional opts:
    :predicate   — keyword OR collection of keywords; restricts results
                   to edges whose attribute-ident is in the set
    :target-type — class ident; restricts results to edges whose target
                   is an instance-of the class (via `instance-of` rule)

  Substrate-quality: class-agnostic; predicate-set + target-type are
  caller-supplied.  Per fulltext arc Stage 16."
  ([entity-ident]
   (outbound-edges-of entity-ident nil))
  ([entity-ident {:keys [predicate target-type]}]
   (let [eid       (:db/id (db/entity entity-ident))
         rows      (if target-type
                     (d/q '[:find ?a ?v
                            :in $ % ?e ?target-type
                            :where
                            [?e ?a ?v]
                            [?a :db/valueType :db.type/ref]
                            (instance-of ?target-type ?v)]
                          (db/db) (all-rules) eid target-type)
                     (d/q '[:find ?a ?v
                            :in $ ?e
                            :where
                            [?e ?a ?v]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         pred-set  (when predicate
                     (set (if (sequential? predicate) predicate [predicate])))
         project   (fn [[a v]]
                     {:predicate (or (:db/ident (db/entity a)) a)
                      :target    (db/entity v)})
         match?    (if pred-set
                     (fn [edge] (pred-set (:predicate edge)))
                     (constantly true))]
     (->> rows
          (map project)
          (filter match?)
          vec))))

(defn inbound-edges-of
  "Inbound typed-edges to `entity-ident` — `:db.type/ref` attribute
  pairs pointing AT the entity.  Returns a vec of maps:

    [{:predicate <pred-ident> :source <entity-map>} ...]

  Optional opts:
    :predicate   — keyword OR collection of keywords; restricts results
                   to edges whose attribute-ident is in the set
    :source-type — class ident; restricts results to edges whose source
                   is an instance-of the class (via `instance-of` rule)

  Substrate-quality: class-agnostic; predicate-set + source-type are
  caller-supplied.  Per fulltext arc Stage 16."
  ([entity-ident]
   (inbound-edges-of entity-ident nil))
  ([entity-ident {:keys [predicate source-type]}]
   (let [eid       (:db/id (db/entity entity-ident))
         rows      (if source-type
                     (d/q '[:find ?s ?a
                            :in $ % ?e ?source-type
                            :where
                            [?s ?a ?e]
                            [?a :db/valueType :db.type/ref]
                            (instance-of ?source-type ?s)]
                          (db/db) (all-rules) eid source-type)
                     (d/q '[:find ?s ?a
                            :in $ ?e
                            :where
                            [?s ?a ?e]
                            [?a :db/valueType :db.type/ref]]
                          (db/db) eid))
         pred-set  (when predicate
                     (set (if (sequential? predicate) predicate [predicate])))
         project   (fn [[s a]]
                     {:predicate (or (:db/ident (db/entity a)) a)
                      :source    (db/entity s)})
         match?    (if pred-set
                     (fn [edge] (pred-set (:predicate edge)))
                     (constantly true))]
     (->> rows
          (map project)
          (filter match?)
          vec))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Graph-walk BFS — Stage 17 (fulltext arc Phase N)
;;
;; Implemented as Clojure-side BFS rather than recursive Datomic rule
;; because (a) hop-cap semantics aren't naturally expressed in Datalog
;; recursive rules, (b) per-hop projection + path tracking is cleaner
;; in iteration, (c) :include [:paths] is trivial to attach when we
;; control the traversal step.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn library-card-of
  "Multi-axis typed-edge neighborhood view of an entity.

  `axis-specs` is a vec of axis declarations.  Each axis:

    {:name       <string-or-keyword>     ; label for this axis in result
     :direction  :forward | :inverse     ; outbound from entity / inbound to entity
     :predicates [<pred-ident>...]       ; restrict to these typed-edge predicates
                                          ; (or omit for no restriction)
     :target-type <class-ident>           ; restrict :forward axes by target's class
     :source-type <class-ident>           ; restrict :inverse axes by source's class
     :limit      <int>                    ; cap per-axis result count (default 0 = no cap)}

  Returns:

    {:entity <entity-map>
     :axes   {<axis-name> [<edge-record>...]  ...}}

  Each edge-record is the same shape as `inbound-edges-of` / `outbound-edges-of`
  returns: `{:predicate <pred-ident> :target/source <entity-map>}` (target for
  :forward axes; source for :inverse axes).

  Substrate-quality: class-agnostic; axis-specs are caller-supplied.  No
  hardcoded knowledge of any domain class's predicate vocabulary.  Per
  fulltext arc Phase O scope-narrowed to library-card-only per
  `decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md`."
  [entity-ident axis-specs]
  (let [entity (db/entity entity-ident)]
    {:entity entity
     :axes (reduce (fn [acc {:keys [name direction predicates target-type source-type limit]
                             :or   {limit 0}}]
                     (let [opts (cond-> {}
                                  predicates (assoc :predicate predicates)
                                  target-type (assoc :target-type target-type)
                                  source-type (assoc :source-type source-type))
                           edges (case direction
                                   :forward
                                   (outbound-edges-of entity-ident
                                                       (dissoc opts :source-type))
                                   :inverse
                                   (inbound-edges-of entity-ident
                                                      (dissoc opts :target-type))
                                   ;; Default to :forward if unspecified
                                   (outbound-edges-of entity-ident
                                                       (dissoc opts :source-type)))
                           limited (if (zero? limit) edges (take limit edges))]
                       (assoc acc name (vec limited))))
                   {}
                   axis-specs)}))

(defn siblings-of
  "Same-directory peers of `entity-ident` via `path-slot` — entities
  whose `path-slot` value shares the same directory prefix as the
  given entity's, excluding the entity itself.

  Filesystem-style semantics: 'decisions/foo.md' is a sibling of
  'decisions/bar.md' (same dir prefix 'decisions/'); not a sibling of
  'decisions/sub/baz.md' (one level deeper) or of 'patterns/foo.md'
  (different dir).

  Required:
    entity-ident — keyword ident or eid; must have `path-slot` populated
    path-slot    — slot ident (e.g., `:mm.memory/rel-path`) carrying
                   the filesystem-style path string

  Returns a vec of entity-maps; empty if the entity is at the root (no
  parent directory) or has no peers.

  Substrate-quality: class-agnostic; `path-slot` is caller-supplied.
  Per fulltext arc Stage 22."
  [entity-ident path-slot]
  (let [entity     (db/entity entity-ident)
        rel-path   (get entity path-slot)]
    (if (or (nil? rel-path) (not (string? rel-path)))
      []
      (let [self-eid   (:db/id entity)
            sep-idx    (clojure.string/last-index-of rel-path "/")
            dir-prefix (if sep-idx
                         (subs rel-path 0 (inc sep-idx))
                         "")
            candidates (d/q '[:find ?e ?p
                              :in $ ?slot
                              :where
                              [?e ?slot ?p]]
                            (db/db) path-slot)
            same-dir?  (fn [path]
                         (and (clojure.string/starts-with? path dir-prefix)
                              (let [tail (subs path (count dir-prefix))]
                                (not (clojure.string/includes? tail "/")))))]
        (->> candidates
             (filter (fn [[eid path]]
                       (and (not= eid self-eid)
                            (same-dir? path))))
             (mapv (fn [[eid _]] (db/entity eid))))))))

(defn graph-walk-from
  "Walk the typed-edge graph outward from `seed-ident` up to `hops`
  levels of distance.  Returns a vec of result maps for every entity
  reachable WITHIN `hops` (excluding the seed itself):

    [{:entity <entity-map> :hop <int>} ...]

  With `:include #{:paths}`, each map also carries `:path` — a vec of
  `{:predicate <pred-ident> :direction :forward|:inverse}` steps from
  seed to the result entity (shortest-path; BFS guarantees the first
  arrival is via a shortest path).

  Opts:
    :hops       — max distance to walk (default 4)
    :predicates — keyword OR coll restricting edges to a predicate set
    :direction  — :forward (outbound edges only) / :inverse (inbound only)
                  / :bidirectional (union).  Default :forward.
    :include    — coll-of opts; `:paths` attaches step sequence.

  Substrate-quality: class-agnostic.  Per fulltext arc Stage 17."
  ([seed-ident]
   (graph-walk-from seed-ident nil))
  ([seed-ident {:keys [hops predicates direction include]
                :or   {hops 4 direction :forward}}]
   (let [seed-eid       (:db/id (db/entity seed-ident))
         pred-set       (when predicates
                          (set (if (sequential? predicates)
                                 predicates
                                 [predicates])))
         include-paths? (contains? (set include) :paths)
         forward?       (#{:forward :bidirectional} direction)
         inverse?       (#{:inverse :bidirectional} direction)

         pred-match?
         (fn [a]
           (or (nil? pred-set)
               (pred-set (or (:db/ident (db/entity a)) a))))

         step-edges
         (fn [frontier-eids]
           (let [forward-rows (when forward?
                                (d/q '[:find ?f ?a ?n
                                       :in $ [?f ...]
                                       :where
                                       [?f ?a ?n]
                                       [?a :db/valueType :db.type/ref]]
                                     (db/db) frontier-eids))
                 inverse-rows (when inverse?
                                (d/q '[:find ?f ?a ?n
                                       :in $ [?f ...]
                                       :where
                                       [?n ?a ?f]
                                       [?a :db/valueType :db.type/ref]]
                                     (db/db) frontier-eids))]
             (concat
               (keep (fn [[f a n]]
                       (when (pred-match? a)
                         {:from-frontier f :to-new n
                          :attr a :direction :forward}))
                     forward-rows)
               (keep (fn [[f a n]]
                       (when (pred-match? a)
                         {:from-frontier f :to-new n
                          :attr a :direction :inverse}))
                     inverse-rows))))]

     (loop [hop      0
            visited  #{seed-eid}
            frontier {seed-eid (when include-paths? [])}
            results  (transient [])]
       (cond
         (zero? (count frontier)) (persistent! results)
         (>= hop hops)            (persistent! results)
         :else
         (let [discovered
               (reduce
                 (fn [acc edge]
                   (let [{:keys [from-frontier to-new attr direction]} edge]
                     (cond
                       (contains? visited to-new) acc
                       (contains? acc to-new)     acc  ; first match wins
                       :else
                       (let [parent-path (get frontier from-frontier [])
                             step        {:predicate (or (:db/ident (db/entity attr))
                                                          attr)
                                          :direction direction}]
                         (assoc acc to-new
                                {:entity (db/entity to-new)
                                 :hop    (inc hop)
                                 :path   (when include-paths?
                                           (conj parent-path step))})))))
                 {}
                 (step-edges (keys frontier)))]
           (recur (inc hop)
                  (into visited (keys discovered))
                  (into {} (map (fn [[eid r]] [eid (:path r)])) discovered)
                  (reduce
                    (fn [acc [_ r]]
                      (conj! acc (if include-paths? r (dissoc r :path))))
                    results
                    discovered))))))))

(defn search-fulltext
  "Single-attribute fulltext search via Datomic + Lucene.

  Returns a seq of `[eid score]` tuples for entities whose `attribute`
  value matches `query` per Lucene's tokenization + BM25 single-field
  scoring (Lucene's default Similarity since v6).

  `attribute` must be declared with `:db/fulltext true` in the schema
  for the query to return hits; absent that, Datomic returns an empty
  result.  Use `fulltext-indexed?` to validate before calling.

  Query syntax supports Lucene's query-parser shapes: phrase
  (`\"exact phrase\"`), boolean (`AND` / `OR` / `NOT`), wildcard
  (`term*`), fuzzy (`term~`), etc.

  Returns raw `[eid score]` tuples; higher-level concerns (limit,
  result-shape projection, snippet generation, multi-field
  weighting) live at the `sandbar.search/*` layer (Stage 3+).

  Per fulltext arc Stage 2 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  [attribute query]
  (d/q '[:find ?e ?score
         :in $ ?attr ?q
         :where [(fulltext $ ?attr ?q) [[?e ?value ?tx ?score]]]]
       (db/db) attribute query))

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

(defn effective-codec-aliases-of
  "Returns the codec-aliases map merged across the class hierarchy.
  Walks `:dt/subclass-of` ancestors; leaf-class aliases shadow ancestors
  for shared keys (specificity wins).

  Used by codecs to handle alias inheritance — e.g., :mm/Decision
  (subclass of :mm/Memory) inherits :mm/Memory's `:type →
  :mm.memory/memory-type` alias.  Without this, codec routing to a
  Memory-subclass loses the :type→:memory-type aliasing because
  `codec-aliases-of` only consults the leaf class.

  Added 2026-05-21 per
  plans/codec_subclass_routing_follow_up_arc_2026_05_21.md Stage 1.5
  (codec slot-inheritance fix).  Sister to `slots-of` which already
  walks inheritance via the `effective-slot` Datalog rule."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (reduce (fn [acc c] (merge acc (codec-aliases-of c)))
            {}
            (reverse chain))))

(defn effective-codec-slot-order-of
  "Returns the codec-slot-order vector merged across the class hierarchy.
  Leaf class's declared order comes first; ancestor classes' orders follow
  in walked order; duplicate slots are deduplicated keeping the FIRST
  (leaf-closest) occurrence.

  Used by `sandbar.codec.markdown/emit-frontmatter` so that emit respects
  the canonical ordering declared on ancestors (e.g., :mm/Decision inherits
  :mm/Memory's slot-order over :mm.memory/* slots that are populated via
  inheritance).  Without this, emission of Memory-subclass entities would
  use an unstable iteration-order for inherited slots, breaking round-trip
  stability.

  Added 2026-05-21 per
  plans/codec_subclass_routing_follow_up_arc_2026_05_21.md Stage 1.5
  (codec slot-inheritance fix).  Sister to `effective-codec-aliases-of`."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (vec (distinct (mapcat codec-slot-order-of chain)))))

(defn effective-bm25f-weights-of
  "Returns the BM25F field-weight map merged across the class hierarchy.
  Walks `:dt/subclass-of` ancestors; leaf-class weights shadow ancestors
  for shared slot keys (specificity wins).

  Used by `sandbar.search/search-bm25f` so subclasses of a class declaring
  `:dt/bm25f-weights` (e.g., the consumer's memorial-subclass family)
  inherit the parent's declared weights without having to redeclare them.
  Without this, `search.bm25f` against a subclass fails with
  'No :dt/bm25f-weights declared on class'.

  Added 2026-05-23 per Gap 13 fix
  (plans/sandbar_mcp_end_to_end_correctness_pass_substrate_stabilization_arc_2026_05_22.md
  Stage C — subclass inheritance for class-metadata helpers).  Sister to
  `effective-codec-aliases-of` / `effective-codec-slot-order-of` — same
  ancestor-walk pattern, different attribute.  Class-agnostic per
  interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (reduce (fn [acc c] (merge acc (bm25f-weights-of c)))
            {}
            (reverse chain))))

(defn effective-memorial-policy-of
  "Returns the `:dt/memorial-policy` declaration nearest to `class-ident` in
  the `:dt/subclass-of` ancestry chain, or nil if no declaration is found
  anywhere in the chain.  One of `:first-class` / `:db-only` / `:inline`.

  Unlike `effective-bm25f-weights-of` (which MERGES across the chain),
  memorial-policy is scalar — nearest-declaration wins (specificity).
  `:mm/Memory` declares `:first-class` once; all descendants inherit
  unless they override (e.g., `:event/HttpRequest` declares `:db-only`).

  Composes with `ancestors-of` (substrate primitive — not yet memoized
  upward, parallel to memoized downward `subclasses-of-cached`; future
  optimization if projection hot-path warrants).  Used by
  `sandbar.reactive.sinks/fs-projection-sink` (Stage B.3 enforcement)
  + (future) `sandbar.project.dump-db-only` (DB-dump arc Stage B).

  nil return means the class is policy-undeclared.  Caller decides
  default — current MVP at the fs-projection sink treats nil as
  `:db-only` (conservative: skip projection rather than spuriously
  emit).  Stage G of the first-class-memorialization arc will turn
  policy-undeclared into a class-registration-time loud-fail.

  Added 2026-05-23 per
  `decisions/option_b_plus_c_ratified_spec_vs_state_criterion_pivot_to_first_class_memorialization_2026_05_23.md`
  + the SPEC-vs-STATE pivot's substrate-enforcement requirement.
  Sister to `effective-bm25f-weights-of` — same ancestor-walk pattern
  but scalar reduction (first-match) rather than map-merge.  Mirrors
  the `:dt/*` substrate-primitive discipline per
  `interaction/build_on_type_system_reflectively_and_prospectively_dont_reinvent_in_parallel_due_to_tactical_concerns_2026_05_23.md`
  — replaces a private hierarchy-walking helper that had been
  authored inside `sandbar.reactive.sinks`."
  [class-ident]
  (let [chain (cons class-ident (ancestors-of class-ident))]
    (some memorial-policy-of chain)))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Type-relation cache (Stage 5 Phase A.5 — Dan-directive 2026-05-22)
;;
;; Memoizes type-relation queries so substrate operations that iterate
;; type-isa? in tight loops (projection filter, audit invariants, codec
;; routing, BM25F :where filter resolution) don't re-run Datalog queries
;; per entity.
;;
;; Bounded memory: stores ~50 class-idents × sets of ~10-50 idents each
;; (a few KB total).  Independent of corpus size.  Type relations are
;; STRUCTURE, not CONTENT.
;;
;; Invalidation: cleared by `sandbar.db.datomic/load-all-schema!` after
;; each schema reload (schema additions can change subclass closures).
;; Future class-creating MCP verbs invalidate similarly via
;; `clear-type-relation-cache!`.
;;
;; Per `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce ^:private type-relation-cache
  ;; {class-ident #{direct-and-transitive-subclass-idents}}  — subclasses, NOT including the class itself
  (atom {}))

(defn clear-type-relation-cache!
  "Clear the memoized type-relation cache.  Called by
  `sandbar.db.datomic/load-all-schema!` after each schema reload (via
  the post-schema-reload-handlers registry), since schema additions can
  change the subclass closure.  Also callable from tests + ad-hoc to
  force a rebuild.  Returns the prior cache value."
  []
  (let [prior @type-relation-cache]
    (reset! type-relation-cache {})
    prior))

;; Register the cache-clear with datomic's post-schema-reload registry
;; at namespace load time.  Set-valued registry dedupes on REPL reload.
;; Per `interaction/dont_use_requiring_resolve_for_namespace_dep_avoidance_2026_05_22.md`.
(db/register-post-schema-reload-handler! clear-type-relation-cache!)

(defn subclasses-of-cached
  "Returns the cached set of all transitive subclasses of class-ident
  (NOT including class-ident itself).  Populates cache on miss via
  `subclasses-of` (which runs the recursive Datalog query).

  Per `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`."
  [class-ident]
  (or (get @type-relation-cache class-ident)
      (let [computed (set (subclasses-of class-ident))]
        (swap! type-relation-cache assoc class-ident computed)
        computed)))

(defn descendants-of
  "Returns the set of all class-idents that are `class-ident` OR a
  transitive subclass.  Substrate primitive for entity-filtering by
  is-instance-of-class-or-descendant.

  Use this when iterating a collection asking 'is this entity an X
  descendant?' — `(contains? (descendants-of X) (:dt/type entity))` is
  O(1) per call.  Compared to per-call `type-isa?` which (before this
  cache) ran a recursive Datalog query per invocation.

  Per `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`."
  [class-ident]
  (conj (subclasses-of-cached class-ident) class-ident))

(defn subclass-of?
  "Returns true if c is a subclass of dt (direct or transitive).
  Uses the cached `subclasses-of-cached` set; O(1) lookup once warm."
  [dt c]
  (contains? (subclasses-of-cached dt) c))

(defn type-isa?
  "Map-friendly type-membership predicate: returns true if `entity-type`
  is `dt` exactly OR a transitive subclass of `dt`.  Sister to
  `instance-of?` which expects a transacted entity (and does an entity
  lookup); `type-isa?` takes a class-keyword directly and is safe to
  call on entity-spec MAPS where `:dt/type` is just a keyword.

  Use at substrate boundaries where exact `(= :mm/Memory (:dt/type e))`
  would miss legitimate subclass instances (e.g., :mm/Decision via
  :dt/subclass-of :mm/Memory).  Added 2026-05-21 per
  plans/codec_subclass_routing_follow_up_arc_2026_05_21.md Stage 1.5
  — same substrate-pure pattern as `memory-class?` in the codec but
  promoted to dt/* so projection + codec + future consumers share the
  helper."
  [dt entity-type]
  (or (= dt entity-type)
      (try (subclass-of? dt entity-type)
           (catch Exception _ false))))

(defn instance-of?
  "Returns true if entity e is an instance of class dt.
  True when e's :dt/type is dt or a subclass of dt.

  Robust to BOTH shapes of `:dt/type` value:
  - keyword form (entity-spec map; pre-transact; in-memory data)
  - Datomic Entity form (post-DB-read; ref-slot resolution returns
    the target entity rather than its ident)

  Gap 20 fix (2026-05-22): the prior implementation `(= dt t)` /
  `(subclass-of? dt t)` worked when `t` was a keyword but silently
  returned false when `t` was an EntityMap — because the keyword-
  vs-EntityMap comparison is always false + the subclass cache stores
  keyword idents.  Surfaced via entity.update flow which validates
  the merged slot map: DB-read ref values fail the type check even
  though they're already-resolved valid refs."
  [dt e]
  (let [t-val   (-> e entity :dt/type)
        t-ident (cond
                  (keyword? t-val)      t-val
                  (associative? t-val)  (:db/ident t-val)
                  :else                 nil)]
    (or (= dt t-ident)
        (and t-ident (subclass-of? dt t-ident)))))

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

(defn- upsert-map-for?
  "True if `v` is a single-key map whose key is either `:db/ident` OR
  the unique-identity slot of `target-class`.  Datomic transact resolves
  such maps to refs via the :db.unique/identity index — they are
  semantically valid ref-slot values pre-transact even though they are
  not Datomic Entity instances yet.

  Codec produces this shape for cardinality-many ref slots (per the
  .md-canonical principle — frontmatter strings like `tags: [observation,
  test]` become `[{:mm.tag/value \"observation\"} ...]` upsert maps that
  resolve to :mm/Tag refs at transact time).  Pre-transact validation
  must accept this shape; otherwise the codec ↔ validation contract
  breaks at entity.create-with-tags + similar cases.

  Per substrate-stabilization arc C6 / Coupling 1
  (codec ↔ validation contract gap) — added 2026-05-22."
  [v target-class]
  (and (map? v)
       (= 1 (count v))
       (let [k (first (keys v))]
         (or (= :db/ident k)
             (when target-class
               (= k (unique-identity-slot-of target-class)))))))

(defn- value-matches-range?
  "Returns true if `value` is admissible for a slot whose declared range is
   `range-type`.  Literal ranges check the Clojure/Java type; class ranges
   accept an instance of the target class, a `:db.unique/identity` upsert map,
   an untyped stub entity, or — for the universal `:dt/Ref` marker — any value
   resolving to a live entity.  nil range ⇒ anything admissible."
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

    ;; Reference to a class — accept any of:
    ;; (a) a Datomic Entity that is instance-of the target class
    ;; (b) an upsert map `{<unique-attr> <value>}` that Datomic transact
    ;;     will resolve to a ref via :db.unique/identity (codec produces
    ;;     this shape for tags + other ref slots; per C6 of substrate-
    ;;     stabilization arc — codec ↔ validation contract gap)
    ;; (c) Gap 20 — an untyped stub entity (resolved entity with :db/id
    ;;     but NO :dt/type).  Stubs are created via :db/ident upsert when
    ;;     a citation target doesn't exist yet (per the stub-then-fill
    ;;     pattern in decisions/mm_memory_typed_edge_migration_string_to_-
    ;;     ref_2026_05_21.md).  Subsequent ingest of the actual memorial
    ;;     upserts via the same ident, filling in the slots.  Permissive
    ;;     validation here honors that intentional design — refusing
    ;;     untyped stubs would break entity.update on any memorial that
    ;;     cites a not-yet-ingested target (common during MCP cutover).
    ;; (d) When the range is the UNIVERSAL ref marker `:dt/Ref` (not a
    ;;     concrete class), any value that resolves to a live entity — a
    ;;     raw eid, a Datomic EntityMap, or a `{:db/id eid}` map — is a
    ;;     valid reference; `instance-of?` fails here because ref-target
    ;;     classes descend from `:dt/Resource`, a sibling of `:dt/Ref`, not
    ;;     from `:dt/Ref` itself.  Concrete-class ranges keep the stricter
    ;;     `instance-of?` check.  Per
    ;;     bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md.
    :else
    (or (instance-of? range-type value)
        (upsert-map-for? value range-type)
        ;; Universal `:dt/Ref` marker: any value naming a LIVE entity is a
        ;; valid reference.  Resolve through the ref->eid canon (so an eid /
        ;; EntityMap / {:db/id} all reduce identically), then confirm the
        ;; target actually exists — `d/entity` on a bogus eid yields a shell
        ;; with :db/id but no attributes, which must NOT pass.
        (and (= :dt/Ref range-type)
             (when-let [eid (ref->eid value)]
               (some? (seq (entity eid)))))
        ;; Gap 20 untyped-stub acceptance (see (c) above) — a resolved entity
        ;; with :db/id but no :dt/type, for the stub-then-fill citation path.
        (when-let [e (try (entity value) (catch Throwable _ nil))]
          (and (:db/id e)
               (nil? (:dt/type e)))))))

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
  "Validate a single slot value against its range. Returns nil if valid, error map if invalid.

   Multi-value handling: cardinality-many slots may arrive as
   - sets (Datomic peer reads return cardinality-many as sets)
   - vectors / lists / seqs (JSON arrays / EDN vectors at the MCP /
     codec boundaries)

   Both shapes iterate per-element through value-matches-range?.  Maps
   are single values (an upsert-map shape like {:mm.tag/value \"x\"}
   IS the value, not a collection); primitives wrap in a singleton set.

   Before C9 (2026-05-22): only sets iterated; vectors fell through
   to the singleton-set branch, passing the whole vector to
   value-matches-range? which (correctly) rejected it as a non-target-
   class collection.  Surfaced during C6 verification — cardinality-
   many ref slots with upsert-map values failed validation even
   though each individual upsert-map satisfies the range type."
  [slot-ident slot-value]
  (let [range-type (range-of slot-ident)
        values (cond
                 (set? slot-value)        slot-value
                 (sequential? slot-value) (set slot-value)
                 :else                    #{slot-value})]
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
