(ns sandbag.db
  (:require [clojure.string       :as string]
            [clojure.tools.logging :as log]
            [com.stuartsierra.component :as component]
            [datomic.api          :as d]
            [sandbag.db.fn      :as fn]
            [sandbag.db.rules   :as rules]
            [sandbag.util       :as util]
            [sandbag.util.edn        :as dedn]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Connectivity
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; (def ^:dynamic *db*   nil)

(def ^:dynamic *conn*   nil)

(defn db-spec []
  (dedn/config-value :db))

(defn db-uri
  ([] (db-uri (db-spec)))
  ([spec] (apply str  ((juxt :url :sid) spec))))

(defn conn
   ([]     (or *conn* (conn (db-uri))))
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
     (-> uri conn (d/transact stmt) deref))))

;(schema-value :dt)

(defn load-all-schema! [uri]
  (doseq [sd (required-schema)]
    (load-schema uri sd)))

; (load-all-schema! (db-uri))


(defn initialize-db! [uri & schema]
  (when (ensure-db! uri)
    (apply load-all-schema! uri schema)
    (fn/load-all-dbfn  uri)
    (log/info :DB/INIT :uri uri)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Peer Component
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord DatomicPeer [spec uri c]
  component/Lifecycle

  (start [self]
    (initialize-db! uri)
    (let [t-con (conn uri)]
      (alter-var-root #'*conn* (constantly t-con))
      (log/info "Datomic Peer started @" uri)
      (assoc self :c t-con)))

  (stop [self]
    (when c
      (alter-var-root #'*conn* (constantly nil))
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
    true             nil #_(util/exception IllegalArgumentException :entity e)))


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

(defn delete! []
  (delete-db (db-uri)))



(comment

  (log/enabled? :info)

  (sandbag.core/stop)
  (delete!)
  (sandbag.core/go)


;;  (ensure-db! (db-uri))
  (initialize-db! (db-uri))

  )
