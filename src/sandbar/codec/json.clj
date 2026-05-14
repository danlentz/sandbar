(ns sandbar.codec.json
  "JSON codec implementation per
   decisions/sandbar_codec_layer_owns_wire_format_concerns_consumer_native_representation_2026_05_12.md §2.4 step 4.

   Wire format: JSON object whose keys are slot-shortname strings + a
   reserved `:dt/type` entry naming the class.

   ## Wire shape

     {\"_class\": \"mm/Memory\",
      \"name\":    \"Foo\",
      \"type\":    \"decision\",
      \"scope\":   \"global\",
      \"body-raw\": \"...\"}

   The `_class` reserved key carries the class ident (with the leading
   `:` stripped per JSON conventions); remaining keys are
   frontmatter-style shortnames mapped to namespaced slot idents via
   the same per-class alias + namespace-prefixing convention used by
   sandbar.codec.markdown.

   Class-specific knowledge is read from the metamodel at runtime:
   - Per-class aliases via `dt/codec-aliases-of` (the `:dt/codec-aliases`
     schema attribute on the class)
   - Keyword-typed slot detection via `dt/range-of` on the slot ident

   No hardcoded consumer-class knowledge in this namespace.  The codec
   is wire-format-agnostic at the per-class level — adding a new
   consumer class requires no edits to this file; the class's
   `:dt/codec-aliases` schema declaration is read at parse / emit time.

   ## Stage C scope

   Minimum-viable: single-entity parse + emit.  Multi-entity sources
   (memory + section vector) are passed through as a JSON array of
   entity objects; the consumer handles flattening / ref-wiring.

   Composes with the MCP JSON-RPC envelope (codec produces the 'result'
   content; envelope wraps it) per codec ADR §2.4 step 4.

   Layer-targeting: codec operates at the MODEL layer (`:dt/type`,
   class-keyed slot mapping) — never at Datomic-schema attributes
   (`:db.*`)."
  (:require [cheshire.core          :as json]
            [clojure.string         :as str]
            [sandbar.codec.protocol :as proto]
            [sandbar.db.datatype    :as dt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class-aware slot mapping (substrate-discipline: read from the
;; metamodel at runtime via `dt/codec-aliases-of` + `dt/range-of`;
;; no hardcoded per-class knowledge.  Mirrors sandbar.codec.markdown).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- class-slot-namespace
  "<class-namespace>.<lowercase-class-local> — see codec.markdown for
   the convention rationale."
  [class-ident]
  (str (namespace class-ident) "." (str/lower-case (name class-ident))))

(defn- body-slot-for
  "Body slot ident for a class.  Per-class override; falls back to
   `<class-ns>/body`."
  [class-ident]
  (case class-ident
    :mm/Memory  :mm.memory/body-raw
    :mm/Section :mm.section/body
    (keyword (class-slot-namespace class-ident) "body")))

(defn- wire-coerced-as-keyword?
  "Returns true if the slot's value should round-trip through the wire
   as a keyword-string (`:foo/bar` → `\"foo/bar\"` → `:foo/bar`).

   True for two range shapes:
   - `:db.type/keyword` — declared keyword slots (memory-type, scope,
     status, ...) — values are bare keywords
   - A metamodel class-ident range (e.g., `:dt/Resource`, `:mm/Section`)
     — slot is a ref; at-wire value is the target's `:db/ident`.
     Distinguished from `:db.type/*` literals by namespace: ranges
     whose `(namespace ...)` is `\"db.type\"` are Datomic value-types
     (not class idents); anything else with a namespace is a class
     ident.

   If the runtime value of a ref slot is an entity map instead of an
   ident keyword, the coerce fns pass through via `:else v` so this
   classification doesn't break entity-map-valued refs.

   Replaces the prior hardcoded `known-class-keyword-slots` map.  Reads
   the slot's range via `dt/range-of` — the metamodel introspection
   path (no hardcoded per-class wire-coercion table)."
  [slot-ident]
  (let [range (dt/range-of slot-ident)]
    (or (= :db.type/keyword range)
        (and (keyword? range)
             (not= "db.type" (namespace range))))))

(defn- wire-key->slot
  "Map a JSON wire key (string) to the slot ident for `class-ident`.
   Reads class-declared aliases via `dt/codec-aliases-of` (keyword
   short-keys); falls back to namespace-prefixing."
  [class-ident wire-key]
  (let [aliases (dt/codec-aliases-of class-ident)]
    (or (get aliases (keyword wire-key))
        (keyword (class-slot-namespace class-ident) wire-key))))

(defn- invert-aliases
  "Invert the class's codec-aliases map for emission — slot-ident →
   wire-key (string).  Reads aliases via `dt/codec-aliases-of`."
  [class-ident]
  (into {}
        (for [[k v] (dt/codec-aliases-of class-ident)]
          [v (name k)])))

(defn- slot->wire-key
  "Inverse of wire-key->slot.  Returns a string (JSON keys are strings).
   Resolution: class-declared alias (via `dt/codec-aliases-of`) first;
   fall back to `(name slot-ident)`."
  [class-ident slot-ident]
  (or (get (invert-aliases class-ident) slot-ident)
      (name slot-ident)))

(defn- coerce-string->keyword
  [v]
  (cond
    (keyword? v) v
    (string?  v) (keyword v)
    (sequential? v) (mapv coerce-string->keyword v)
    :else v))

(defn- coerce-keyword->string
  "Serialize a keyword as a JSON-safe string preserving the namespace
   (`:decisions/foo` → `\"decisions/foo\"`).  Use `subs (str ...) 1` to
   strip the leading `:` rather than `name` (which drops the namespace)."
  [v]
  (cond
    (keyword? v) (subs (str v) 1)
    (sequential? v) (mapv coerce-keyword->string v)
    :else v))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON object ↔ entity-spec conversion

(defn- json-obj->entity
  "Build an entity-spec map from a parsed JSON object.  Reads `_class`
   (or `:dt/type`); maps remaining keys to slot idents via
   class-declared `:dt/codec-aliases` (runtime metamodel lookup).
   Keyword-typed slot values (detected via `dt/range-of`) are coerced
   string → keyword."
  [json-obj]
  (let [class-name (or (get json-obj "_class")
                       (get json-obj "_dt/type")
                       (throw (ex-info "JSON object missing _class field"
                                       {:json-obj json-obj})))
        class-ident (if (str/starts-with? class-name ":")
                      (keyword (subs class-name 1))
                      (keyword class-name))]
    (reduce-kv
      (fn [acc k v]
        (if (or (= k "_class") (= k "_dt/type"))
          acc
          (let [slot (wire-key->slot class-ident k)
                v'   (if (wire-coerced-as-keyword? slot)
                       (coerce-string->keyword v)
                       v)]
            (assoc acc slot v'))))
      {:dt/type class-ident}
      json-obj)))

(defn- entity->json-obj
  "Build a JSON object map from an entity-spec.  Uses `_class` to carry
   the type ident.  Slot keys are projected via
   `dt/codec-aliases-of` (runtime metamodel lookup); keyword-typed
   values (detected via `dt/range-of`) are coerced keyword → string."
  [entity]
  (let [class-ident (or (:dt/type entity)
                        (throw (ex-info "Entity missing :dt/type" {:entity entity})))]
    (reduce-kv
      (fn [acc k v]
        (cond
          (= :dt/type k) acc
          :else
          (let [wire-key (slot->wire-key class-ident k)
                v'       (if (wire-coerced-as-keyword? k)
                           (coerce-keyword->string v)
                           v)]
            (assoc acc wire-key v'))))
      {"_class" (subs (str class-ident) 1)}
      entity)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JsonCodec record — implements proto/Codec

(defrecord JsonCodec []
  proto/Codec

  (parse [_ input _opts]
    ;; Single entity OR JSON array of entities.
    (let [parsed (json/parse-string input)]
      (cond
        (sequential? parsed)
        (mapv json-obj->entity parsed)

        (map? parsed)
        (json-obj->entity parsed)

        :else
        (throw (ex-info "JSON codec: input is neither object nor array"
                        {:type (type parsed)})))))

  (emit [_ entity-or-coll opts]
    ;; Pretty-printing is controlled via `(:pretty? opts)` per the Codec
    ;; protocol contract.  The prior implementation read `:_pretty?` off
    ;; the ENTITY itself (and ignored opts entirely) — ultrareview #3 at
    ;; codec/json.clj:165.  That conflated opts-shape with entity-shape;
    ;; opts is the correct channel for codec-behavior hints.
    (let [pretty? (boolean (:pretty? opts))
          payload (cond
                    (sequential? entity-or-coll)
                    (mapv entity->json-obj entity-or-coll)

                    (map? entity-or-coll)
                    (entity->json-obj entity-or-coll))]
      (if pretty?
        (json/generate-string payload {:pretty true})
        (json/generate-string payload))))

  (mime-types [_]
    ["application/json"
     "application/x-sandbar+json"])

  (supports? [_ _class-ident]
    ;; Generic codec — works with any class.
    true)

  (round-trip-test [self entity]
    (let [emitted  (proto/emit self entity {})
          reparsed (proto/parse self emitted {:class (:dt/type entity)})]
      {:ok?      (= entity reparsed)
       :emitted  emitted
       :reparsed reparsed
       :diff     (when (not= entity reparsed)
                   {:original entity
                    :reparsed reparsed})})))

(defn make-codec
  "Construct a JsonCodec instance.  Stateless — singleton-friendly."
  []
  (->JsonCodec))

(defn register!
  "Register a fresh JsonCodec instance with the codec mediator under
   the `:json` format keyword."
  []
  (let [register-fn (requiring-resolve 'sandbar.codec/register!)]
    (register-fn :json (make-codec))
    :json))
