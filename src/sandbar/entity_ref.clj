(ns sandbar.entity-ref
  "Public boundary abstraction for entity reference coercion + resolution.

   See `decisions/sandbar_entity_ref_abstraction_2026_05_14.md` (Phase R
   Stage R-1 ADR) in the corpus repo for the full design rationale.

   ## What this exists for

   The MCP / REST / in-process API surfaces all need to accept entity
   references in multiple forms (keyword ident, integer eid, string,
   entity map) and produce structured errors for malformed or
   non-existent references.  Before this namespace, every public boundary
   re-solved the coercion + error-projection problem locally (split
   between `->ident` shape-coercion in `sandbar.mcp.tools` and `db/entity`
   permissive resolution at REST), with inconsistent error behavior
   (`AssertionError` escapes from `:pre`, `nil`-returns silently
   propagating, etc.).  `entity-ref` is the single canonical entry point.

   ## Accepted input forms

   - **Keyword** (`:foo/bar`) — entity ident; direct lookup
   - **Prefixed string** (`\":foo/bar\"`) — string form of ident
   - **Unprefixed string** (`\"foo/bar\"`) — string form of ident
   - **Numeric string** (`\"12345\"`) — parsed to integer eid
   - **Integer** (`12345`) — Datomic eid; direct lookup
   - **Entity map** — returned as-is (idempotent)
   - **Lookup vector** (`[:slot value]`) — currently raises
     `:entity-ref/lookup-vector-unsupported`; deferred to post-0.1.0 per
     REST audit (no REST callsite uses lookup-vectors today)

   ## Error envelope

   All errors raised as `clojure.lang.ExceptionInfo` with `:reasons` key
   carrying a SET of namespaced keywords.  See `(entity-ref/error)`
   constructor.  Reason set semantics: multiple reasons can co-occur
   (e.g., lookup-vector input is both `:malformed-input` and
   `:lookup-vector-unsupported`).

   Reason keywords:
   - `:entity-ref/malformed-input` — input form not in accepted set
   - `:entity-ref/not-found` — ref shape valid but no entity at that ident/eid
   - `:entity-ref/lookup-vector-unsupported` — `[:slot value]` deferred
   - `:entity-ref/no-ident` — `resolve-ident` called on entity without `:db/ident`

   ## Consumer use

     ;; Resolve any form to canonical entity map
     (entity-ref/resolve :mm/Memory)        ; keyword
     (entity-ref/resolve \":mm/Memory\")      ; prefixed string
     (entity-ref/resolve \"mm/Memory\")       ; unprefixed string
     (entity-ref/resolve 12345)             ; integer eid
     (entity-ref/resolve \"12345\")           ; numeric string

     ;; Get canonical ident keyword
     (entity-ref/resolve-ident :mm/Memory)  ; → :mm/Memory

     ;; Predicate-style check (never raises)
     (entity-ref/validate \"bogus-not-found\")
     ;; → {:valid? false :reasons #{:entity-ref/not-found} :message \"...\"}

   ## Dispatch on errors

     (try
       (entity-ref/resolve ref)
       (catch clojure.lang.ExceptionInfo e
         (case (first (:reasons (ex-data e)))
           :entity-ref/malformed-input        (handle-bad-input ...)
           :entity-ref/not-found              (handle-not-found ...)
           :entity-ref/lookup-vector-unsupported (handle-unsupported ...)
           (throw e))))

   For multi-reason fine-grained matching:

     (let [reasons (:reasons (ex-data e))]
       (cond
         (contains? reasons :entity-ref/lookup-vector-unsupported) ...
         (contains? reasons :entity-ref/not-found) ...))"
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
