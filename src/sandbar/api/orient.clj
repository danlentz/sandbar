(ns sandbar.api.orient
  "REST API for the orientation surface — library-card multi-axis
  entity-neighborhood view.

  Phase O of comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md.  Scope per
  decisions/sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md:
  Sandbar substrate ships `library-card` only; corpus-specific
  orientation surfaces (arc-forest / ready-queue / session-state /
  index-snapshot) live at the corpus layer.

  ## Endpoint

    GET /api/orient/library-card?entity=:slug&axes=<EDN-string>

  `:axes` arrives as an EDN-string because URL query syntax has no
  native representation for the nested-vec axis-spec structure.  Same
  convention as `:where` on /api/aggregate/* and `:via` on
  /api/navigate/path."
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
      :axes   {<axis-name> [{:predicate ... :target/source ...} ...] ...}}

   Per Phase O of fulltext arc; scope-narrowed to library-card only
   per ADR sandbar_phase_o_substrate_quality_scope_library_card_only_2026_05_14.md."
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
