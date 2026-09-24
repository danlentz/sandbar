(ns sandbar.api.orient
  "REST adapter for the caller-defined library-card neighborhood view.

  GET /api/orient/library-card?entity=:example/record&axes=<EDN>

  axes is an EDN vector of maps describing direction, predicates, type filters
  and limits. It follows the same representation convention as where on
  aggregation and via on path queries. Domain-specific planning/session views
  belong to the application, not this transport adapter."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [sandbar.orient :as orient]
            [sandbar.service.endpoint :as endpoint :refer [defhandler return]]
            [sandbar.service.params :as params :refer [defvalidator]]
            [sandbar.util.http-status :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coercion helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- str->keyword
  [s]
  (cond
    (keyword? s)                                  s
    (and (string? s) (str/starts-with? s ":"))    (keyword (subs s 1))
    (and (string? s) (not (str/blank? s)))        (keyword s)
    :else                                         nil))

(defn- parse-axes
  "Parse the `:axes` query param.  Accepts an EDN-string of a vector
   of axis-spec maps.  Throws ex-info on malformed EDN."
  [s]
  (when (and (string? s) (not (str/blank? s)))
    (try
      (edn/read-string s)
      (catch Exception e
        (throw (ex-info (str "Invalid :axes EDN: " (.getMessage e))
                        {:received s}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvalidator ::library-card [_] identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler library-card
  "GET /api/orient/library-card - Multi-axis typed-edge neighborhood
   view of an entity.

   Query params:
     ?entity=:slug              - Anchor entity ident (REQUIRED)
     ?axes=<EDN>                - EDN-string of axis-spec vec (REQUIRED)

   Response:
     {:entity <entity-map>
      :axes   {<axis-name> [{:predicate ... :target/source ...} ...] ...}}"
  [_ _ params]
  (let [entity-ident (str->keyword (:entity params))
        axes-str     (:axes params)]
    (cond
      (nil? entity-ident)
      (return http-status/bad-request
              {:error "Missing required query param: entity"})

      (or (nil? axes-str) (str/blank? axes-str))
      (return http-status/bad-request
              {:error "Missing required query param: axes (EDN-string of axis-spec vec)"})

      :else
      (try
        (let [axes (parse-axes axes-str)]
          (when-not (sequential? axes)
            (throw (ex-info "axes must be a sequential collection of axis-spec maps"
                            {:received axes})))
          (orient/library-card {:entity entity-ident :axes (vec axes)}))
        (catch clojure.lang.ExceptionInfo e
          (return http-status/bad-request
                  {:error (.getMessage e) :details (ex-data e)}))))))
