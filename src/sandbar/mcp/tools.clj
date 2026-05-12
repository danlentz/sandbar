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

   Stage C.1 foundation:
   - tools/list walks dt/all-classes
   - tools/call dispatches by tool name to dt/* operations
   - Auto-generated JSON Schema from dt/range-of slot types

   Subsequent stages:
   - C.3 notifications/tools/list_changed when dt/Class instances change
   - C.4 richer parameter validation; per-tool authorization hooks"
  (:require [clojure.tools.logging :as log]
            [clojure.string        :as str]
            [sandbar.db.datatype   :as dt]))

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
   tools. Stage C.1 returns ONLY class-derived tools; subsequent stages
   add schema-introspection tools (sandbar.schema.*) + cross-cutting
   query tools."
  [id _params]
  {:jsonrpc "2.0"
   :id      id
   :result  {:tools (all-class-tools)}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tools/call handler — Stage C.1 STUB (returns class metadata; real
;;                     dispatch lands in subsequent stages)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn handle-call
  "MCP `tools/call` — invoke a discovered tool. Stage C.1 returns the
   class metadata as the call result (proves the dispatch path works);
   subsequent stages route to actual dt/* operations.

   Per B.1.3 discipline: when this is fleshed out, each tool's body
   calls dt/* / sandbar.util.* / sandbar.service.* — NEVER raw datomic.api."
  [id params]
  (let [{:keys [name arguments]} params
        cls-ident (tool-name->class-ident name)]
    (cond
      (nil? cls-ident)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32602
                 :message (str "Invalid tool name: " name)
                 :data    {:received-name name}}}

      :else
      (try
        (let [cls (dt/class-of cls-ident)]
          (if cls
            {:jsonrpc "2.0"
             :id      id
             :result  {:content [{:type "text"
                                  :text (str "Stage C.1 stub: tool " name
                                             " called with " (count arguments)
                                             " argument(s). "
                                             "Real dispatch lands in subsequent stages. "
                                             "Class metadata: " (pr-str
                                                                  {:ident       cls-ident
                                                                   :slots       (mapv :db/ident (dt/slots-of cls))
                                                                   :abstract?   (dt/abstract? cls)
                                                                   :parents     (mapv :db/ident (dt/parents-of cls))
                                                                   :subclasses  (mapv :db/ident (dt/subclasses-of cls))}))}]}}
            {:jsonrpc "2.0"
             :id      id
             :error   {:code    -32602
                       :message (str "No class found for tool: " name)
                       :data    {:resolved-ident cls-ident}}}))
        (catch Exception e
          (log/error e :MCP/tools-call-error {:tool name})
          {:jsonrpc "2.0"
           :id      id
           :error   {:code    -32603
                     :message "Tool execution failed"
                     :data    {:exception-message (.getMessage e)}}})))))
