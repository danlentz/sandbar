(ns sandbar.codec.json
  "JSON entity codec using class-aware key mapping.

   The reserved _class field names the entity class. Other keys map to
   namespaced model slots using class aliases and slot ranges. Keyword and
   reference values have codec-specific conversions. Entity arrays are
   parsed/emitted as collections; callers own persistence and reference
   reconstruction. This representation is distinct from a JSON-RPC response
   envelope. See doc/concepts/codec-layer.md and doc/api/codec-protocol.md."
  (:require [cheshire.core          :as json]
            [clojure.edn            :as edn]
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

(defn- edn-readable-keyword?
  "True iff keyword K survives an EDN round trip as exactly itself: printed
   inside a one-element vector and read back with clojure.edn, the same
   vector returns.  A reader error, extra reader forms (whitespace, `,`,
   brackets, a `::` spelling) and comment truncation (`;` in the name) all
   fail — the oracle the Markdown codec and the MCP slot coercion apply."
  [k]
  (try (= [k] (edn/read-string (pr-str [k])))
       (catch Exception _ false)))

(defn- refuse-unreadable-keywords!
  "Return COERCED unchanged unless it is, or contains as a member, a keyword
   that cannot round-trip EDN; then throw an ex-info naming the wire KEY, the
   resolved SLOT, the ORIGINAL wire value (bounded; the member for a list)
   and the keyword's text — never the unreadable keyword itself, so the
   diagnostic reads back as EDN and serialises as JSON.  Runs at conversion
   time, before any caller transacts, so a refused document writes nothing."
  [key slot original coerced]
  (let [bounded (fn [v] (let [s (str v)] (if (> (count s) 200) (str (subs s 0 200) "…") s)))
        refuse! (fn [k index]
                  (let [value (if (and (some? index) (sequential? original))
                                (nth original index nil)
                                original)]
                    (throw (ex-info (str "JSON codec refused key " (pr-str key)
                                         " (slot " slot "): the value " (pr-str (bounded value))
                                         " would become a keyword that cannot round-trip EDN ("
                                         (pr-str (bounded (str k))) ")"
                                         (when (some? index) (str " at index " index)))
                                    (cond-> {:type         :codec.json/unreadable-keyword
                                             :key          key
                                             :slot         slot
                                             :value        (bounded value)
                                             :keyword-text (bounded (str k))}
                                      (some? index) (assoc :index index))))))]
    (cond
      (keyword? coerced)
      (when-not (edn-readable-keyword? coerced) (refuse! coerced nil))

      (sequential? coerced)
      (doseq [[i member] (map-indexed vector coerced)]
        (when (and (keyword? member) (not (edn-readable-keyword? member)))
          (refuse! member i))))
    coerced))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON object ↔ entity-spec conversion

(defn- json-obj->entity
  "Build an entity-spec map from a parsed JSON object.  Reads `_class`
   (or `:dt/type`); maps remaining keys to slot idents via
   class-declared `:dt/codec-aliases` (runtime metamodel lookup).
   Keyword-typed slot values (detected via `dt/range-of`) are coerced
   string → keyword, and a value whose keyword cannot round-trip EDN is
   refused with `:codec.json/unreadable-keyword` before any caller
   transacts — a document is parsed whole or not at all."
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
                       (refuse-unreadable-keywords! k slot v (coerce-string->keyword v))
                       v)]
            (assoc acc slot v'))))
      {:dt/type class-ident}
      json-obj)))

(defn- internal-key?
  "True for an internal database or entity-location key excluded from
   JSON entity emission, including :db/*, :db.* and :mm.memory/rel-path."
  [k]
  (and (keyword? k)
       (or (= :mm.memory/rel-path k)
           (when-let [ns (namespace k)]
             (or (= "db" ns)
                 (str/starts-with? ns "db."))))))

(defn- entity->json-obj
  "Build a JSON object map from an entity-spec.  Uses `_class` to carry
   the type ident.  Slot keys are projected via
   `dt/codec-aliases-of` (runtime metamodel lookup); keyword-typed
   values (detected via `dt/range-of`) are coerced keyword → string.

   Datomic-internal keys (`:db/id`, `:db/ident`) + the entity-locator
   `:mm.memory/rel-path` are filtered out via `internal-key?` so
   persisted entities don't leak these to the wire format."
  [entity]
  (let [class-ident (or (:dt/type entity)
                        (throw (ex-info "Entity missing :dt/type" {:entity entity})))]
    (reduce-kv
      (fn [acc k v]
        (cond
          (= :dt/type k) acc
          (internal-key? k) acc
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
