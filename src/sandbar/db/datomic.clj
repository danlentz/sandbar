(ns sandbar.db.datomic
  (:require [clojure.string       :as string]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [datomic.api          :as d]
            [sandbar.db.fn      :as fn]
            [sandbar.db.rules   :as rules]
            [sandbar.util.common       :as util]
            [sandbar.util.edn        :as dedn]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Connectivity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (def ^:dynamic *db*   nil)

(def ^:dynamic **conn*   (atom nil))

(defn db-spec []
  (dedn/config-value :db))

(defn db-uri
  ([] (db-uri (db-spec)))
  ([spec] (apply str  ((juxt :url :sid) spec))))

(defn conn
   ([]     (or @**conn* (conn (db-uri))))
   ([uri] (d/connect uri)))

(defn ensure-db! [uri]
  (d/create-database uri))

(defn db
  ([] (d/db (conn)))
  ([c] (d/db c)))

;; (defmacro with-db [db-instance & body]
;;   `(binding [*db* ~db-instance]
;;      ~@body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn schema-value [designator]
  (dedn/resource-value designator nil))

(defn required-schema []
  (dedn/config-value :required-schema))

;; Class-level cardinality-many meta-slots whose re-load from schema EDN
;; should have SET-REPLACE semantics (not the default additive upsert).
;;
;; Without this set-replace pre-pass, edits to schema/*.edn that REMOVE
;; tuples from these slots (e.g., trimming :dt/slots after a Phase I
;; canonicalization, rewriting :dt/codec-slot-order with new positions)
;; leave the prior values behind in the DB.  Each new load adds; nothing
;; retracts.  Cardinality-many ref-typed :dt/slots is hit hardest:
;; observed accumulation to 19 entries when EDN declared 5.
;;
;; Tuple-typed :dt/codec-slot-order + :dt/bm25f-weights are partially
;; mitigated by Datomic's tuple value-equality (identical tuples don't
;; duplicate) but unique-by-position tuples can still collide (e.g.,
;; [:mm.context/name 0] + [:mm.memory/name 0] both present at position 0).
;;
;; Per `observations/schema_edn_reload_is_additive_not_set_replace_for_cardinality_many_class_meta_slots_2026_05_26.md`
;; + `interaction/foundational_substrate_concerns_are_never_follow_up_sub_arcs_2026_05_21.md`.
;;
;; Mechanism: before transacting each schema stmt, walk its entity-maps
;; for the set-replace meta-slots.  For each, compute the symmetric
;; difference (current DB values not present in new EDN values) and
;; prepend [:db/retract ...] operations.  Retract-then-add semantics
;; in a single tx means same-value-redeclarations are no-ops (Datomic
;; idempotency); strictly-removed values get retracted; strictly-new
;; values get added.

(def class-set-replace-meta-slots
  "Cardinality-many class-level meta-slots that the schema-EDN-load
  mechanism treats with set-replace semantics.  Adding to this set
  extends the discipline to other class meta-slots that should be
  fully-declared per EDN (versus accumulated across loads).

  Public for testability + extensibility — consumers can rebind in
  scoped contexts via `(with-redefs ...)` to exercise alternative sets."
  #{:dt/slots :dt/codec-slot-order :dt/bm25f-weights})

(defn normalize-slot-value-for-set
  "Normalize a DB-side slot value for set-membership comparison with an
  EDN-side value.  Ref-typed slots come back as Entity instances; we
  compare by :db/ident.  Tuple- + scalar-typed values compare directly.

  Public for testability."
  [v]
  (if (and (associative? v) (contains? v :db/ident))
    (:db/ident v)
    v))

(defn class-meta-slot-retracts
  "Given current DB + an entity-map being asserted, return [:db/retract ...]
  operations for any class-set-replace meta-slot whose current DB value
  contains entries not present in the new EDN declaration.  Returns nil
  when no entity exists yet (first-time load) or no relevant slot is
  being asserted.

  Public for testability."
  [db ent-map]
  (when-let [ident (:db/ident ent-map)]
    (when-let [current (d/entity db ident)]
      (mapcat
       (fn [slot]
         (when (contains? ent-map slot)
           (let [new-values (set (map normalize-slot-value-for-set
                                      (get ent-map slot)))
                 cur-values (get current slot)]
             (for [v cur-values
                   :let [v-norm (normalize-slot-value-for-set v)]
                   :when (not (contains? new-values v-norm))]
               [:db/retract ident slot
                ;; For ref-typed slots, retract by :db/id (Datomic
                ;; accepts :db/ident lookup-ref too but :db/id is the
                ;; canonical form post-resolution).
                (if (and (associative? v) (contains? v :db/id))
                  (:db/id v)
                  v)]))))
       class-set-replace-meta-slots))))

(defn with-class-meta-slot-set-replace
  "Augment a schema-load stmt with retract operations for class-set-replace
  meta-slots that are being redeclared.  Retracts are prepended to the
  stmt so they execute before the new asserts in the same tx.  Per the
  set-replace mechanism documented above class-set-replace-meta-slots.

  Public for testability."
  [db stmt]
  (let [retracts (vec (mapcat #(class-meta-slot-retracts db %) stmt))]
    (if (seq retracts)
      (vec (concat retracts stmt))
      stmt)))

(defn load-schema
  ([schema-designator]
     (load-schema (db-uri) schema-designator))
  ([uri schema-designator]
   (log/info :DB/SCHEMA (str "Load " schema-designator))
   (doseq [stmt (schema-value schema-designator)]
     (let [c (conn uri)
           tx (with-class-meta-slot-set-replace (d/db c) stmt)]
       (log/info :DB/STMT stmt)
       (when (not= tx stmt)
         (log/info :DB/SET-REPLACE-RETRACTS
                   {:retract-count (- (count tx) (count stmt))}))
       @(d/transact c tx)))))

;(schema-value :literal)
;(schema-value :resource)

;; Stage 5 Phase A.5 — post-schema-reload callback registry.
;;
;; Higher-level namespaces (e.g., sandbar.db.datatype) hold caches that
;; depend on schema state.  When `load-all-schema!` runs, those caches
;; need to invalidate.  Direct deps would create a cycle (datatype already
;; requires datomic for `db/db` etc.), so the higher-level ns REGISTERS
;; a clear-fn at namespace-load time + this ns invokes the registered
;; handlers after each schema reload.  Set-valued for idempotent
;; registration across REPL reloads.
;;
;; Per `interaction/dont_use_requiring_resolve_for_namespace_dep_avoidance_2026_05_22.md`
;; + `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`.

(defonce post-schema-reload-handlers
  (atom #{}))

(defn register-post-schema-reload-handler!
  "Register a no-arg function to run after each `load-all-schema!`.
  Set-valued: re-registration is idempotent.  Typical usage: clear a
  cache whose validity depends on schema state."
  [f]
  (swap! post-schema-reload-handlers conj f))

(defn fire-post-schema-reload-handlers!
  "Invoke every registered post-schema-reload handler, swallowing per-
  handler failures so one bad handler can't block the others.  Public so
  alternative schema-load entry points (e.g. `sandbar.test-util`) can
  preserve the cache-invalidation invariant that `load-all-schema!`
  guarantees."
  []
  (doseq [handler @post-schema-reload-handlers]
    (try (handler)
         (catch Throwable t
           (log/warn t :DB/POST-SCHEMA-RELOAD-HANDLER-FAILED
                     {:handler (str handler)})))))

(defn load-all-schema! [uri]
  (doseq [sd (required-schema)]
    (load-schema uri sd))
  (fire-post-schema-reload-handlers!))

; (load-all-schema! (db-uri))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema-derived cache invalidator registrations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per the precedent of `sandbar.db.datatype` (which registers its own
;; type-relation-cache invalidator) + the symmetric-handler-fire pattern of
;; decisions/zorp_test_cross_fixture_cache_survival_fix_option_2_2026_05_23.md,
;; register schema-derived cache invalidators here in db.datomic — the one ns
;; that already requires the consumer namespaces (inverse would cycle).
;;
;; NOT registered:
;;  - `fn/clear-fnbase!` / `fn/clear-mm-fn-memorial-base!` — populated at
;;    namespace-load (ONCE per JVM) by `defdbfn` macro; consumed by
;;    `fn/load-all-dbfn` which runs AFTER `load-all-schema!` in
;;    `initialize-db!` (line ~145 above).  Clearing post-schema-reload would
;;    wipe the bases BEFORE dbfn install, breaking production startup.
;;  - `rules/clear-rulebase!` — same shape; populated at namespace-load via
;;    `defrule` macros, consumed by test fixtures via `(d/transact conn (all-rules))`.
;;    Clearing wipes rules needed by subsequent fixtures.
;;
;; Wave 0 W.0.4 of the metamodel-unification arc.

;; (No additional registrations needed beyond dt.datatype's auto-registration of
;; clear-type-relation-cache! + search.clj's auto-registration of clear-bm25f-cache!)


(defn initialize-db! [uri & schema]
  ;; Stage 5 Phase B (2026-05-22): always reload schema + dbfns at start,
  ;; regardless of whether the DB needed to be created.  Datomic's
  ;; :db/ident upsert makes load-all-schema! idempotent — re-running
  ;; against an existing DB is safe + applies any schema edits made
  ;; since last start.  Without this, editing schema/mm.edn requires
  ;; full DB wipe + re-init to take effect, which is hostile to
  ;; substrate evolution.  Per Dan-directive 2026-05-22:
  ;;   "sandbar start always retransacting schema seems convenient for now."
  ;; Composes with `ideas/sandbar_reflective_schema_from_type_memorials_-
  ;; for_dynamic_client_type_evolution_2026_05_22.md` as the substrate-
  ;; evolution discipline.
  ;;
  ;; β.2.0 pre-work (2026-05-26): after schema-load, validate the
  ;; :dt/subclass-of + :dt/subproperty-of entailment graphs are acyclic
  ;; (S.8 mitigation).  Q.B.0.b ratification: loud-fail on cycle detect.
  ;; Per the β.2 plan (`/Users/dan/.claude/plans/wise-splashing-stardust.md`)
  ;; + master pre-0.2.0 plan §3.β.2 + metamodel-unification arc §10.B.0.
  ;; Resolved late-binding to avoid load-time cyclic dependency between
  ;; sandbar.db.datomic and sandbar.db.entailment.quality (which requires
  ;; this ns for db/conn + db/db-uri).
  (let [created? (ensure-db! uri)]
    (apply load-all-schema! uri schema)
    ((requiring-resolve 'sandbar.db.entailment.quality/validate-entailment-graph!) uri)
    (fn/load-all-dbfn uri)
    (fn/load-all-mm-fn-memorials uri)
    (when created?
      (log/info :DB/INIT :uri uri))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Peer Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord DatomicPeer [spec uri c]
  component/Lifecycle

  (start [self]
    (initialize-db! uri)
    (let [t-con (conn uri)]
      (reset! **conn* t-con)
      (log/info "Datomic Peer started @" uri)
      (assoc self :c t-con)))

  (stop [self]
    (when c
      (reset! **conn* nil)
      (log/info "Datomic Peer stopped")
      (assoc self :c nil))))

(defn make-datomic-peer [spec]
  (map->DatomicPeer {:spec spec :uri (db-uri spec)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn entity [e]
  (if (associative? e)
    e
    (d/entity (db) e)))

(defn describe
  "Returns the Concise Bounded Description (CBD) of an entity 'e'"
  [e]
  (if (map? e)
    e
    (d/touch (entity e))))

(defn entity-id [e]
  (cond
    (number? e)      (long e)
    (associative? e) (:db/id e)
    (keyword? e)     (entity-id (entity e))
    true             nil))

(defn excise-entity [e-or-eid]
  @(d/transact (conn)
     [{:db/id #db/id[db.part/user]
       :db/excise (entity-id e-or-eid)}]))

(defn retract-entity [e-or-eid]
  @(d/transact (conn)
     [[:db.fn/retractEntity (entity-id e-or-eid)]]))

(defn retract-entities [& e-or-eids]
  @(d/transact (conn)
     (map #(vec [:db.fn/retractEntity (entity-id %)]) e-or-eids)))


(defn delete-db [uri]
  (log/warn :DB/DELETE :uri uri)
  (d/delete-database uri))

;; TODO: clean up

(defn delete! []
  (delete-db (db-uri)))

(defn refresh! []
  (delete!)
  (initialize-db! (db-uri))
  (reset! **conn* (conn (db-uri))))


(comment

  (refresh!)

  (log/enabled? :info)


  (describe :dt/Resource)

  (describe :dt/domain)

  (describe :User)




  ;; {:db/id 17592186045419,
  ;;  :db/ident :dt/Resource,
  ;;  :db/doc "Resource is the abstract superclass of all classes",
  ;;  :dt/type :dt/Class,
  ;;  :dt/context "system",
  ;;  :dt/name "Resource",
  ;;  :dt/list :dt/Resource*,
  ;;  :dt/slots #{:dt/type}}

  (describe :dt/Literal)

  ;; {:db/id 17592186045439,
  ;;  :db/ident :dt/Literal,
  ;;  :db/doc "Abstract superclass of literal/scalar types",
  ;;  :dt/type :dt/Class,
  ;;  :dt/context "system",
  ;;  :dt/name "Literal",
  ;;  :dt/subclass-of #{:dt/Resource}}

  (sandbar.core/stop)
  (delete!)
  (sandbar.core/go)


;;  (ensure-db! (db-uri))
  (initialize-db! (db-uri))

  (load-schema :meta)
  (load-schema :resource)
  (load-schema :literal)
  (load-schema :ref)
  (load-schema :fn)
  (load-schema :any)
  (load-schema :user)

  (defn tx [x]
    @(d/transact (conn) x))


(delete!)


  )
