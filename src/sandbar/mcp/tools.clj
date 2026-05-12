(ns sandbar.mcp.tools
  "MCP `tools/list` + `tools/call` handlers — bootstrap-by-discovery
   walks `dt/all-classes` and emits one MCP tool description per class
   with parameters auto-generated from `dt/slots-of` + `dt/range-of`.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.3 + B.1.4:
   - Tool implementations call `dt/*` introspection API directly
   - NEVER raw `datomic.api` — bypass smell per
     interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md
   - Bootstrap-by-discovery: new mm/* classes via `dt/make` auto-surface
     as MCP tools after `notifications/tools/list_changed`

   Stage progression:
   - C.1 foundation — tools/list bootstrap-by-discovery; tools/call stub
   - C.4 — real tools/call dispatch via dt/make; argument coercion;
     auto-fires notifications/tools/list_changed on :dt/Class instances
   - Subsequent stages add richer parameter validation; per-tool
     authorization hooks; query tools (dt/all-instances-of); etc."
  (:require [clojure.tools.logging     :as log]
            [clojure.string            :as str]
            [sandbar.db.datatype       :as dt]
            [sandbar.mcp.notifications :as notifications]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tool naming convention
;;
;; Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4:
;;   tool name = "sandbar.class.<class-ns>.<class-name>" for instance ops
;;   tool name = "sandbar.schema.<verb>" for schema-introspection ops

(defn class->tool-name
  "Map a class entity (or its :db/ident keyword) to a tool name.
   `:zorp/Footwear` → `\"sandbar.class.zorp.Footwear\"`."
  [cls-or-ident]
  (let [ident (if (keyword? cls-or-ident) cls-or-ident (:db/ident cls-or-ident))]
    (str "sandbar.class." (namespace ident) "." (name ident))))

(defn tool-name->class-ident
  "Inverse of class->tool-name. `\"sandbar.class.zorp.Footwear\"` →
   `:zorp/Footwear`. Returns nil if name doesn't match the convention."
  [tool-name]
  (when (str/starts-with? tool-name "sandbar.class.")
    (let [suffix (subs tool-name (count "sandbar.class."))
          dot    (str/last-index-of suffix ".")]
      (when (and dot (pos? dot))
        (keyword (subs suffix 0 dot) (subs suffix (inc dot)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON Schema generation from dt/* slot metadata
;;
;; Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.4: parameters
;; auto-generated from `dt/slots-of` + each slot's `dt/range-of`.

(defn datomic-type->json-schema
  "Map a Datomic value type keyword to a JSON Schema type stub."
  [t]
  (case t
    :db.type/string  {:type "string"}
    :db.type/long    {:type "integer"}
    :db.type/double  {:type "number"}
    :db.type/boolean {:type "boolean"}
    :db.type/instant {:type "string" :format "date-time"}
    :db.type/keyword {:type "string" :description "Clojure keyword string"}
    :db.type/uuid    {:type "string" :format "uuid"}
    :db.type/uri     {:type "string" :format "uri"}
    :db.type/ref     {:type "string" :description "Reference to another entity (ident or eid)"}
    {:type "string" :description (str "Datomic type " t)}))

(defn slot->json-schema-property
  "Convert one dt/* slot (a :dt/Property entity) to a JSON Schema property
   pair `[prop-name prop-schema]`.

   Per discipline: uses `dt/range-of` + `dt/cardinality-of` introspection
   (NOT raw d/pull on the property entity)."
  [slot]
  (let [ident   (:db/ident slot)
        range   (dt/range-of slot)
        many?   (dt/cardinality-many? slot)
        base    (datomic-type->json-schema range)
        schema  (if many?
                  {:type "array" :items base}
                  base)
        described (assoc schema :description
                         (or (:db/doc slot)
                             (str "Slot " ident
                                  (when range (str " (range " range ")"))
                                  (when many? " (cardinality-many)"))))]
    [(name ident) described]))

(defn class->input-schema
  "Build a JSON Schema for the inputs to a `tools/call` against a class.
   Uses `dt/slots-of` (inherited) + `dt/required-slots-of`."
  [cls]
  (let [slots          (dt/slots-of cls)
        required-slots (set (map :db/ident (dt/required-slots-of cls)))
        properties     (into {} (map slot->json-schema-property slots))
        required-names (vec (keep #(when (required-slots %) (name %))
                                  (map :db/ident slots)))]
    (cond-> {:type "object" :properties properties}
      (seq required-names) (assoc :required required-names))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tool description generation — bootstrap-by-discovery
;;
;; Per B.1.4: walk dt/all-classes; emit one tool per non-abstract class.

(defn class->tool-description
  "Build the MCP tool-description map for a single class."
  [cls]
  (let [ident (:db/ident cls)]
    {:name        (class->tool-name ident)
     :title       (str (name ident) " operations")
     :description (or (:db/doc cls)
                      (str "Sandbar operations on " ident " instances"))
     :inputSchema (class->input-schema cls)}))

(defn all-class-tools
  "Walk `dt/all-classes` + emit MCP tool descriptions for all non-abstract
   classes. Per the metacircular property — :dt/Class is itself a class,
   so this surfaces dt/Class operations as a tool too."
  []
  (->> (dt/all-classes)
       (remove dt/abstract?)
       (map class->tool-description)
       (sort-by :name)
       vec))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/list handler
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-list
  "MCP `tools/list` — bootstrap-by-discovery returns all class-derived
   tools."
  [id _params]
  {:jsonrpc "2.0"
   :id      id
   :result  {:tools (all-class-tools)}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Argument coercion — JSON-Schema-typed inputs → Datomic-typed values
;;
;; MCP clients send arguments as a JSON object; Datomic slots expect
;; specific value types. Coerce per the slot's :db/valueType +
;; cardinality.

(defn- coerce-value
  "Coerce one argument value to its target Datomic type. Strings remain
   strings; keyword-shaped strings ('foo/bar' or ':foo/bar') become
   keywords; instants parse from ISO-8601; refs by ident-string become
   keywords.

   Cardinality-many: single value wrapped as a vector; existing
   vectors preserved."
  [value target-type many?]
  (let [coerce-one (fn [v]
                     (case target-type
                       :db.type/keyword (if (string? v)
                                          (if (str/starts-with? v ":")
                                            (keyword (subs v 1))
                                            (keyword v))
                                          v)
                       :db.type/instant (if (string? v)
                                          (java.util.Date/from
                                            (java.time.Instant/parse v))
                                          v)
                       :db.type/uuid    (if (string? v)
                                          (java.util.UUID/fromString v)
                                          v)
                       :db.type/ref     (if (and (string? v) (str/starts-with? v ":"))
                                          (keyword (subs v 1))
                                          v)
                       v))]
    (cond
      (and many? (sequential? value))
      (mapv coerce-one value)

      many?
      [(coerce-one value)]

      :else
      (coerce-one value))))

(defn- coerce-arguments
  "Build a Datomic-shaped props map from JSON arguments + a class's
   declared slots. Drops unknown arguments (the JSON Schema validation
   layer should already have rejected them, but we're defensive)."
  [cls arguments]
  (let [slots (dt/slots-of cls)]
    (reduce
      (fn [acc slot]
        (let [k        (:db/ident slot)
              key-name (name k)
              v        (get arguments key-name)]
          (if (some? v)
            (assoc acc k (coerce-value v (dt/range-of slot) (dt/cardinality-many? slot)))
            acc)))
      {}
      slots)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/call handler — Stage C.4 real dispatch via dt/make
;;
;; Per ADR B.1.3 layer-targeting discipline: this dispatcher uses ONLY
;; dt/* introspection (class-of, slots-of, range-of, cardinality-many?,
;; abstract?) + dt/make for the transact. NO raw datomic.api / d/q /
;; d/pull / d/transact references.
;;
;; Behavior:
;;   1. Resolves tool name → class ident
;;   2. Looks up the class via dt/class-of
;;   3. Coerces JSON arguments per slot ranges + cardinality
;;   4. Calls dt/make (ALWAYS validated — no {:validate? false})
;;   5. Returns the new entity's data
;;   6. If the new entity is :dt/Class or :dt/Property (schema evolution),
;;      fires notifications/tools/list_changed per ADR B.1.4

(defn- entity->json-data
  "Project a Sandbar entity to a JSON-friendly map. Keeps :db/id,
   :db/ident, and dt/* + user-namespace slot values."
  [entity]
  (when entity
    (into {}
          (filter (fn [[k _v]]
                    (or (= :db/id k)
                        (= :db/ident k)
                        (and (keyword? k) (some? (namespace k)))))
                  entity))))

(defn handle-call
  "MCP `tools/call` — invoke a discovered tool. Real dispatch via
   dt/make per ADR B.1.3.

   Response shapes:
   - Success: {:content [{:type \"text\" :text <entity-edn>}]}
   - Validation failure: {:content [{:type \"text\" :text ...}] :isError true}
   - Abstract-class instantiation: {:content [...] :isError true}
   - Internal error: JSON-RPC -32603
   - Invalid tool name: JSON-RPC -32602
   - Missing class: JSON-RPC -32602"
  [id params]
  (let [tool-name (:name params)
        arguments (:arguments params {})
        cls-ident (tool-name->class-ident tool-name)]
    (if (nil? cls-ident)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32602
                 :message (str "Invalid tool name: " tool-name)
                 :data    {:received-name tool-name}}}
      (try
        (let [cls (dt/class-of cls-ident)]
          (cond
            (nil? cls)
            {:jsonrpc "2.0"
             :id      id
             :error   {:code    -32602
                       :message (str "No class found for tool: " tool-name)
                       :data    {:resolved-ident cls-ident}}}

            (dt/abstract? cls)
            {:jsonrpc "2.0"
             :id      id
             :result  {:content [{:type "text"
                                  :text (str "Cannot instantiate abstract class " cls-ident)}]
                       :isError true}}

            :else
            (let [props      (coerce-arguments cls arguments)
                  new-entity (dt/make cls-ident props)
                  data       (entity->json-data new-entity)]
              (log/info :MCP/tools-call-success
                        {:tool tool-name :class cls-ident :entity-id (:db/id new-entity)})
              ;; Per ADR B.1.4: when a :dt/Class or :dt/Property lands,
              ;; the tools surface changed — push notification.
              (when (#{:dt/Class :dt/Property} cls-ident)
                (notifications/tools-list-changed!))
              {:jsonrpc "2.0"
               :id      id
               :result  {:content [{:type "text"
                                    :text (pr-str data)}]}})))
        (catch clojure.lang.ExceptionInfo e
          (let [errors (ex-data e)]
            (log/warn :MCP/tools-call-validation-failed
                      {:tool tool-name :errors errors})
            {:jsonrpc "2.0"
             :id      id
             :result  {:content [{:type "text"
                                  :text (str "Validation failed: " (.getMessage e)
                                             " — " (pr-str errors))}]
                       :isError true}}))
        (catch Exception e
          (log/error e :MCP/tools-call-error {:tool tool-name})
          {:jsonrpc "2.0"
           :id      id
           :error   {:code    -32603
                     :message "Tool execution failed"
                     :data    {:exception-message (.getMessage e)}}})))))
