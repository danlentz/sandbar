(ns sandbar.api.aggregate
  "REST API for the aggregation surface — count / group-by / rank-by.

  Stage 15 of comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.  Thin
  HTTP-layer wrappers around sandbar.aggregate's three public verbs.

  Substrate-quality discipline preserved per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
  handlers route through the consumer-facing aggregate namespace,
  never raw dt/* primitives or datomic.api.

  ## Endpoints

    GET /api/aggregate/count?class=:mm/Memory[&where=<EDN>]
    GET /api/aggregate/group-by?class=:mm/Memory&group-by=:mm.memory/memory-type[&where=<EDN>]
    GET /api/aggregate/rank-by?class=:mm/Memory&rank-by=:degree[&limit=20&temporal-slot=:mm.memory/last-touched]

  Query params arrive URL-decoded as strings; idents are coerced via
  `str->keyword` (accepting both `:foo/bar` and `foo/bar` shapes).
  `:where` arrives as an EDN string because URL query syntax has no
  native representation for Datalog symbols (`?e`, `?v`)."
  (:refer-clojure :exclude [count group-by])
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sandbar.aggregate :as aggregate]
            [sandbar.service.endpoint :as endpoint :refer [defhandler return]]
            [sandbar.service.params :as params :refer [defvalidator]]
            [sandbar.util.http-status :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coercion helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- str->keyword
  "Coerce a query-param string to a Clojure keyword.
   Accepts `\"foo/bar\"`, `\":foo/bar\"`, already-a-keyword, or nil."
  [s]
  (cond
    (keyword? s)                                  s
    (and (string? s) (str/starts-with? s ":"))    (keyword (subs s 1))
    (and (string? s) (not (str/blank? s)))        (keyword s)
    :else                                         nil))

(defn- str->long
  "Coerce a query-param string to a Long; nil on parse failure."
  [s]
  (cond
    (integer? s) s
    (string? s)  (try (Long/parseLong s)
                      (catch NumberFormatException _ nil))
    :else        nil))

(defn- str->where
  "Parse a `:where` query-param to a vector of Datalog clauses.
   At the REST boundary, `:where` arrives as an EDN string because URL
   query params have no native representation for Datalog symbols.
   Throws ex-info on malformed EDN."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try
      (edn/read-string s)
      (catch Exception e
        (throw (ex-info (str "Invalid :where EDN: " (.getMessage e))
                        {:received s}))))))

(def ^:private rank-axes
  #{:degree :backlink-density :recency :freshness})

(def ^:private temporal-axes
  #{:recency :freshness})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity projection — convert Datomic entity-map to plain map for
;; EDN/JSON serialization.  Same shape as sandbar.mcp.tools/entity-projection:
;; preserve :db/id, :db/ident, and namespaced-keyword slots; drop other
;; bookkeeping.  Required at REST boundary because raw entities don't
;; round-trip through pr-str (EDN reader can't parse #object[...] forms).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity-projection
  "Project a Datomic entity-map to a plain map for EDN serialization.

  Note: Datomic entity-iteration does NOT include `:db/id` in the
  key-seq (it's accessed via a special method).  We explicitly add
  `:db/id` to the projection."
  [entity]
  (when entity
    (let [base (into {}
                     (filter (fn [[k _v]]
                               (or (= :db/ident k)
                                   (and (keyword? k) (some? (namespace k))))))
                     entity)]
      (cond-> base
        (:db/id entity) (assoc :db/id (:db/id entity))))))

(defn- project-rank-hits
  "Project each hit's `:entity` to a plain map so the response body
   serializes cleanly as EDN/JSON."
  [result]
  (update result :hits
          (fn [hits]
            (mapv (fn [hit] (update hit :entity entity-projection))
                  hits))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validators (pass-through; type coercion happens in handlers)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvalidator ::count    [_] identity)
(defvalidator ::group-by [_] identity)
(defvalidator ::rank-by  [_] identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler count
  "GET /api/aggregate/count - Count entities of :class matching optional :where.

   Query params:
     ?class=:mm/Memory         - Class ident (REQUIRED)
     ?where=<EDN>              - Optional EDN-string of Datalog clauses
                                 (e.g., \"[[?e :mm.memory/memory-type :decision]]\")

   Response:
     {:count <int>}

   Per fulltext arc Stage 15."
  [_ _ params]
  (let [class-ident (str->keyword (:class params))]
    (if (nil? class-ident)
      (return http-status/bad-request
              {:error "Missing required query param: class"})
      (try
        (let [where (str->where (:where params))]
          (aggregate/count-by {:class class-ident :where where}))
        (catch clojure.lang.ExceptionInfo e
          (return http-status/bad-request
                  {:error (.getMessage e) :details (ex-data e)}))))))

(defhandler group-by
  "GET /api/aggregate/group-by - Group instances by slot value; count per group.

   Query params:
     ?class=:mm/Memory                              - Class ident (REQUIRED)
     ?group-by=:mm.memory/memory-type               - Slot ident (REQUIRED)
     ?where=<EDN>                                   - Optional Datalog filter

   Response:
     {:groups {value count} :total <int>}

   Per fulltext arc Stage 15."
  [_ _ params]
  (let [class-ident  (str->keyword (:class params))
        group-by-arg (str->keyword (:group-by params))]
    (cond
      (nil? class-ident)
      (return http-status/bad-request
              {:error "Missing required query param: class"})

      (nil? group-by-arg)
      (return http-status/bad-request
              {:error "Missing required query param: group-by"})

      :else
      (try
        (let [where (str->where (:where params))]
          (aggregate/group-by {:class    class-ident
                               :group-by group-by-arg
                               :where    where}))
        (catch clojure.lang.ExceptionInfo e
          (return http-status/bad-request
                  {:error (.getMessage e) :details (ex-data e)}))))))

(defhandler rank-by
  "GET /api/aggregate/rank-by - Structural-rank re-ordering across 4 axes.

   Query params:
     ?class=:mm/Memory                  - Class ident (REQUIRED)
     ?rank-by=:degree                   - Axis (REQUIRED): :degree / :backlink-density
                                          / :recency / :freshness
     ?limit=20                          - Max hits (default 20; 0 = no cap)
     ?temporal-slot=:mm.memory/last-touched  - REQUIRED for :recency / :freshness

   Response:
     {:hits [{:entity <entity-map> :rank-score <num>} ...]
      :total <int>
      :returned <int>}

   Per fulltext arc Stage 15."
  [_ _ params]
  (let [class-ident   (str->keyword (:class params))
        rank-by-arg   (str->keyword (:rank-by params))
        limit         (str->long   (:limit params))
        temporal-slot (str->keyword (:temporal-slot params))]
    (cond
      (nil? class-ident)
      (return http-status/bad-request
              {:error "Missing required query param: class"})

      (nil? rank-by-arg)
      (return http-status/bad-request
              {:error "Missing required query param: rank-by"})

      (not (rank-axes rank-by-arg))
      (return http-status/bad-request
              {:error    (str "Invalid :rank-by axis; must be one of "
                              (str/join ", " (sort (map str rank-axes))))
               :received rank-by-arg})

      (and (temporal-axes rank-by-arg)
           (nil? temporal-slot))
      (return http-status/bad-request
              {:error (str ":temporal-slot is required for :rank-by "
                           rank-by-arg)})

      :else
      (try
        (let [opts (cond-> {:class class-ident :rank-by rank-by-arg}
                     (some? limit)         (assoc :limit limit)
                     (some? temporal-slot) (assoc :temporal-slot temporal-slot))]
          (project-rank-hits (aggregate/rank-by opts)))
        (catch clojure.lang.ExceptionInfo e
          (return http-status/bad-request
                  {:error (.getMessage e) :details (ex-data e)}))))))
