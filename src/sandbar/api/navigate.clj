(ns sandbar.api.navigate
  "REST API for the navigation surface — path-grammar walker.

  Stage P-6 of comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.  Thin
  HTTP-layer wrapper around sandbar.navigate.path/path-via.

  Substrate-quality discipline preserved per
  interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
  routes through the consumer-facing path namespace, never raw dt/*
  primitives or datomic.api.

  ## Endpoints

    GET /api/navigate/path?from=:dt/Property&via=<EDN>[&limit=20&include=paths]

  Query params arrive URL-decoded as strings.  `:from` is coerced to a
  keyword; `:via` is parsed as EDN at the consumer layer; `:include`
  is split on commas."
  (:require [clojure.string :as str]
            [sandbar.navigate.path     :as nav-path]
            [sandbar.navigate.siblings :as nav-siblings]
            [sandbar.service.endpoint  :as endpoint :refer [defhandler return]]
            [sandbar.service.params    :as params :refer [defvalidator]]
            [sandbar.util.http-status  :as http-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Coercion helpers (mirror sandbar.api.aggregate pattern)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- str->keyword
  [s]
  (cond
    (keyword? s)                                  s
    (and (string? s) (str/starts-with? s ":"))    (keyword (subs s 1))
    (and (string? s) (not (str/blank? s)))        (keyword s)
    :else                                         nil))

(defn- str->long
  [s]
  (cond
    (integer? s) s
    (string? s)  (try (Long/parseLong s)
                      (catch NumberFormatException _ nil))
    :else        nil))

(defn- str->include-set
  "Coerce a comma-separated string (e.g. 'paths,rank-axes') OR a vector
   to a set of keywords."
  [s]
  (cond
    (set? s)        s
    (sequential? s) (set (map keyword s))
    (string? s)     (->> (str/split s #",")
                         (remove str/blank?)
                         (map (comp keyword str/trim))
                         set)
    :else           nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validators (pass-through; coercion in handler)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defvalidator ::path-via    [_] identity)
(defvalidator ::siblings-of [_] identity)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defhandler path-via
  "GET /api/navigate/path - Walk a path-grammar expression from a seed.

   Query params:
     ?from=:dt/Property    - Seed entity ident (REQUIRED)
     ?via=<EDN>            - EDN-string path expression (REQUIRED)
                             e.g., \"[:REP* :dt/subclass-of]\"
     ?limit=20             - Max returned entities (default 0 = no cap)
     ?include=paths        - Comma-separated projection opts (deferred)

   Response:
     {:reachable [...] :total <int> :returned <int>}
     With :include=paths: also :path-data-deferred true

   Per fulltext arc Stage P-6."
  [_ _ params]
  (let [from-ident (str->keyword (:from params))
        via-str    (:via params)
        limit      (str->long (:limit params))
        include    (str->include-set (:include params))]
    (cond
      (nil? from-ident)
      (return http-status/bad-request
              {:error "Missing required query param: from"})

      (or (nil? via-str) (str/blank? via-str))
      (return http-status/bad-request
              {:error "Missing required query param: via"})

      :else
      (try
        (let [opts (cond-> {:from from-ident :via via-str}
                     (some? limit)        (assoc :limit limit)
                     (some? include)      (assoc :include include))]
          (nav-path/path-via opts))
        (catch clojure.lang.ExceptionInfo e
          (return http-status/bad-request
                  {:error (.getMessage e) :details (ex-data e)}))))))

(defhandler siblings-of
  "GET /api/navigate/siblings - Same-directory peers of an entity.

   Query params:
     ?entity=:mm.memory/decisions-foo    - Anchor entity ident (REQUIRED)
     ?path-slot=:mm.memory/rel-path      - Slot ident carrying the
                                            filesystem-style path (REQUIRED)
     ?limit=20                            - Max returned siblings (default 0 = no cap)

   Response:
     {:siblings [...] :total <int> :returned <int>}

   Per fulltext arc Stage 22."
  [_ _ params]
  (let [entity-ident (str->keyword (:entity params))
        path-slot    (str->keyword (:path-slot params))
        limit        (str->long (:limit params))]
    (cond
      (nil? entity-ident)
      (return http-status/bad-request
              {:error "Missing required query param: entity"})

      (nil? path-slot)
      (return http-status/bad-request
              {:error "Missing required query param: path-slot"})

      :else
      (try
        (let [opts (cond-> {:entity entity-ident :path-slot path-slot}
                     (some? limit) (assoc :limit limit))]
          (nav-siblings/siblings-of opts))
        (catch clojure.lang.ExceptionInfo e
          (return http-status/bad-request
                  {:error (.getMessage e) :details (ex-data e)}))))))
