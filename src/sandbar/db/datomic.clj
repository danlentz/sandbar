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

(defn load-schema
  ([schema-designator]
     (load-schema (db-uri) schema-designator))
  ([uri schema-designator]
   (log/info :DB/SCHEMA (str "Load " schema-designator))
   (doseq [stmt (schema-value schema-designator)]
     (do
       (log/info :DB/STMT stmt)
       (-> uri conn (d/transact stmt) deref)))))

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

(defn load-all-schema! [uri]
  (doseq [sd (required-schema)]
    (load-schema uri sd))
  ;; Fire post-schema-reload handlers (e.g., type-relation cache clear).
  (doseq [handler @post-schema-reload-handlers]
    (try (handler)
         (catch Throwable t
           (log/warn t :DB/POST-SCHEMA-RELOAD-HANDLER-FAILED
                     {:handler (str handler)})))))

; (load-all-schema! (db-uri))


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
  (let [created? (ensure-db! uri)]
    (apply load-all-schema! uri schema)
    (fn/load-all-dbfn uri)
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
