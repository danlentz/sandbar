(ns sandbar.entity-ref
  "Entity-reference coercion and resolution for public boundaries.
   Accept keyword idents, prefixed/unprefixed ident strings, integer eids,
   numeric eid strings and entity maps. Lookup vectors are unsupported.
   resolve returns an entity; resolve-ident requires an interned ident;
   validate converts structured resolver ExceptionInfo into diagnostics.
   Other failures, including numeric overflow, may still throw.

   Failures use ExceptionInfo with a :reasons set, including malformed-input,
   not-found, lookup-vector-unsupported and no-ident in the entity-ref
   namespace. Inspect membership rather than assuming one reason or stable
   set order. Callers still enforce their own authorization boundary."
  (:refer-clojure :exclude [resolve])
  (:require
   [clojure.string :as str]
   [sandbar.db.datomic :as db]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Error constructor

(defn error
  "Construct entity-ref ex-info with one or more reasons.

   Variadic dispatch on first arg:
     - keyword → wrapped as #{reason} (single-reason common case)
     - set → multi-reason envelope (preserve set as-is)

   Adds `:reasons` key to ex-data as a set of namespaced keywords.

   Examples:
     (entity-ref/error :entity-ref/not-found
                       \"Entity not found\"
                       {:ref ref})

     (entity-ref/error #{:entity-ref/malformed-input
                         :entity-ref/lookup-vector-unsupported}
                       \"Lookup vector not supported\"
                       {:ref ref})

   Returns: ExceptionInfo ready for (throw ...)."
  [reasons-or-reason msg context]
  (let [reasons (cond
                  (set? reasons-or-reason)     reasons-or-reason
                  (keyword? reasons-or-reason) #{reasons-or-reason}
                  :else (throw (IllegalArgumentException.
                                "entity-ref/error: reasons-or-reason must be keyword or set")))]
    (ex-info msg (assoc context :reasons reasons))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal coercion

(defn- numeric-string? [s]
  (boolean (re-matches #"\d+" s)))

(defn- coerce-string
  "Coerce a string ref-form to keyword or integer.
   - Numeric: \"12345\" → 12345 (Long)
   - Prefixed: \":foo/bar\" → :foo/bar
   - Unprefixed: \"foo/bar\" → :foo/bar"
  [s]
  (cond
    (numeric-string? s)        (Long/parseLong s)
    (str/starts-with? s ":")   (keyword (subs s 1))
    :else                      (keyword s)))

(defn- entity-exists?
  "Check whether a Datomic EntityMap represents a real (existing) entity.

   `db/entity` returns an EntityMap for ANY input — even a totally bogus
   eid that has no datoms.  The returned EntityMap reports `:db/id` as
   the requested eid, but `(seq e)` yields kv pairs ONLY for attributes
   that actually exist on the entity.  Therefore an entity exists iff
   `(seq e)` is non-empty (i.e., the entity has at least one materialized
   attribute beyond `:db/id`)."
  [e]
  (boolean (and e (seq e))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public API

(defn resolve
  "Resolve any entity-reference form to its canonical entity map.

   Accepts: keyword, prefixed-string, unprefixed-string, numeric-string,
   integer, entity-map.  Throws structured ex-info for other forms.

   Returns: entity map (Datomic EntityMap; `(:db/id e)` is truthy).

   Throws: clojure.lang.ExceptionInfo per error envelope.  Possible reasons:
     - `:entity-ref/malformed-input` — input form not in accepted set
     - `:entity-ref/not-found` — ref shape valid but no entity exists
     - `:entity-ref/lookup-vector-unsupported` — [:slot value] syntax"
  [ref]
  (cond
    ;; Idempotent: entity map → entity map
    (and (associative? ref) (:db/id ref))
    ref

    ;; Keyword (ident) — direct Datomic lookup
    (keyword? ref)
    (let [e (db/entity ref)]
      (if (entity-exists? e)
        e
        (throw (error :entity-ref/not-found
                      (str "Entity not found for ident: " ref)
                      {:ref ref :resolved-via :ident}))))

    ;; Integer (eid) — direct Datomic lookup
    (integer? ref)
    (let [e (db/entity ref)]
      (if (entity-exists? e)
        e
        (throw (error :entity-ref/not-found
                      (str "Entity not found for eid: " ref)
                      {:ref ref :resolved-via :eid}))))

    ;; String — coerce then recur
    (string? ref)
    (if (str/blank? ref)
      (throw (error :entity-ref/malformed-input
                    "Empty or blank string is not a valid entity ref"
                    {:ref ref :accepted #{:keyword :integer :string :entity-map}}))
      (resolve (coerce-string ref)))

    ;; Lookup-vector — deferred
    (vector? ref)
    (throw (error #{:entity-ref/malformed-input
                    :entity-ref/lookup-vector-unsupported}
                  "Datomic lookup-vector syntax `[:slot value]` is not yet supported"
                  {:ref ref :note "Deferred to post-0.1.0 per REST audit 2026-05-14"}))

    ;; Anything else — malformed
    :else
    (throw (error :entity-ref/malformed-input
                  (str "Unsupported entity-ref form: " (type ref))
                  {:ref ref
                   :got-type (some-> ref class .getName)
                   :accepted #{:keyword :integer :string :entity-map}}))))


(defn resolve-ident
  "Coerce any reference form to its canonical `:db/ident` keyword.

   Resolves the entity via `resolve`, then extracts `:db/ident`.

   Returns: keyword ident.

   Throws:
     - `:entity-ref/no-ident` — resolved entity has no `:db/ident` (anonymous entity)
     - any reason from `resolve` (malformed-input, not-found, etc.)"
  [ref]
  (let [e (resolve ref)]
    (or (:db/ident e)
        (throw (error :entity-ref/no-ident
                      "Resolved entity has no :db/ident; cannot return ident keyword"
                      {:ref ref
                       :resolved-entity-eid (:db/id e)})))))


(defn validate
  "Predicate-style entity-ref check.  Never raises.

   Returns:
     {:valid? true :entity <entity-map>}
   or:
     {:valid? false :reasons #{<reason-kw> ...} :message <string> :details <ex-data>}"
  [ref]
  (try
    {:valid? true :entity (resolve ref)}
    (catch clojure.lang.ExceptionInfo e
      (let [data (ex-data e)]
        {:valid? false
         :reasons (:reasons data #{})
         :message (.getMessage e)
         :details data}))))
