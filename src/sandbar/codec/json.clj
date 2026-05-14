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
   sandbar.codec.markdown.  Keyword-typed slots (per
   `known-class-keyword-slots`) are coerced string ↔ keyword
   bidirectionally.

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
            [sandbar.codec.protocol :as proto]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class-aware slot mapping (mirrors sandbar.codec.markdown conventions)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def known-class-slot-aliases
  "Per-class wire-key → slot-ident alias map.  Mirrors
   sandbar.codec.markdown/known-class-slot-aliases — the slot-mapping
   convention is wire-format-agnostic; aliases describe the CLASS not
   the format.  Future refactor (when a 3rd codec lands): extract to a
   shared sandbar.codec.slot-mapping namespace."
  {:mm/Memory  {"type" :mm.memory/memory-type}})

(def known-class-keyword-slots
  "Per-class set of slot idents whose values must be coerced string ↔
   keyword across the JSON wire format (same rationale as markdown
   codec).

   For mm/Section, ref-typed slots (parent / next-sibling /
   previous-sibling) carry KEYWORD :db/ident values at the wire layer
   — entity references are projected as their idents.  Stage F (dt/* +
   MCP integration) may evolve this to also accept :db/id integers
   for ident-less entities."
  {:mm/Memory  #{:mm.memory/memory-type
                 :mm.memory/scope
                 :mm.memory/status}
   :mm/Section #{:mm.section/parent
                 :mm.section/next-sibling
                 :mm.section/previous-sibling}})

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

(defn- wire-key->slot
  "Map a JSON wire key (string) to the slot ident for `class-ident`.
   Aliases checked first; falls back to namespace-prefixing."
  [class-ident wire-key]
  (or (get-in known-class-slot-aliases [class-ident wire-key])
      (keyword (class-slot-namespace class-ident) wire-key)))

(defn- invert-aliases
  [class-ident]
  (into {}
        (for [[k v] (get known-class-slot-aliases class-ident {})]
          [v k])))

(defn- slot->wire-key
  "Inverse of wire-key->slot.  Returns a string (JSON keys are strings)."
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
   (or `:dt/type`); maps remaining keys to slot idents."
  [json-obj]
  (let [class-name (or (get json-obj "_class")
                       (get json-obj "_dt/type")
                       (throw (ex-info "JSON object missing _class field"
                                       {:json-obj json-obj})))
        class-ident (if (str/starts-with? class-name ":")
                      (keyword (subs class-name 1))
                      (keyword class-name))
        kw-slots   (get known-class-keyword-slots class-ident #{})]
    (reduce-kv
      (fn [acc k v]
        (if (or (= k "_class") (= k "_dt/type"))
          acc
          (let [slot (wire-key->slot class-ident k)
                v'   (if (contains? kw-slots slot)
                       (coerce-string->keyword v)
                       v)]
            (assoc acc slot v'))))
      {:dt/type class-ident}
      json-obj)))

(defn- entity->json-obj
  "Build a JSON object map from an entity-spec.  Uses `_class` to carry
   the type ident."
  [entity]
  (let [class-ident (or (:dt/type entity)
                        (throw (ex-info "Entity missing :dt/type" {:entity entity})))
        kw-slots    (get known-class-keyword-slots class-ident #{})]
    (reduce-kv
      (fn [acc k v]
        (cond
          (= :dt/type k) acc
          :else
          (let [wire-key (slot->wire-key class-ident k)
                v'       (if (contains? kw-slots k)
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
