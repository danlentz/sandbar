(ns sandbar.mcp.resources
  "MCP `resources/list` + `resources/read` + subscription handlers.

   Per decisions/sandbar_mcp_server_design_2026_05_12.md B.1.5:
   - Every Sandbar entity addressable at a stable URI:
       mcp://sandbar/<class-ns>/<class-name>/<ident-or-uuid>
   - resources/list walks dt/all-named-instances-of (every dt/Resource
     descendant with :db/ident) + emits one resource per entity
   - resources/read returns the entity's data
   - Subscription via resources/subscribe; updates pushed via
     notifications/resources/updated

   Discipline per
   interaction/target_sandbar_introspection_api_layer_not_raw_datomic_2026_05_12.md:
   this namespace targets dt/* introspection (class-of, slots-of,
   all-named-instances-of) — NEVER raw datomic.api.

   Stage C.5 foundation:
   - URI scheme codec (entity ↔ URI roundtrip)
   - resources/list returns the named-entity catalog
   - resources/read returns entity JSON projection
   - Subscription registry (in-memory; integrates with notifications)

   Subsequent stages:
   - C.5.1 mm/Memory body rendering as markdown (per export ADR M.3)
   - C.5.2 pagination (MCP cursor semantics)
   - C.5.3 tx-report-queue listener firing resources/updated"
  (:require [clojure.string            :as str]
            [clojure.tools.logging     :as log]
            [sandbar.db.datatype       :as dt]
            [sandbar.mcp.notifications :as notifications]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URI scheme — mcp://sandbar/<class-ns>/<class-name>/<ident-or-eid>
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const uri-scheme "mcp://sandbar")

(defn entity->uri
  "Build the canonical MCP URI for an entity.

   Named entities (have :db/ident) use the ident path:
     :dt/Class    → mcp://sandbar/dt/Class/dt/Class
     :mm/Memory   → mcp://sandbar/dt/Class/mm/Memory
     :user/alice  → mcp://sandbar/dt/User/user/alice

   Wait — the URI shape is <class-ns>/<class-name>/<ident-or-eid>, so
   for the entity ITSELF the class path comes from dt/class-of.

   Per the metacircular property — :dt/Class is an instance of :dt/Class —
   for a class entity X, class-of(X) = :dt/Class; for an instance Y of
   class X, class-of(Y) = X."
  [entity]
  (let [cls       (dt/class-of entity)
        cls-ident (or (:db/ident cls) :unknown)
        entity-ident (:db/ident entity)
        identifier (cond
                     (and entity-ident (namespace entity-ident))
                     (str (namespace entity-ident) "/" (name entity-ident))

                     entity-ident
                     (name entity-ident)

                     :else
                     (str (:db/id entity)))]
    (str uri-scheme "/"
         (namespace cls-ident) "/" (name cls-ident)
         "/" identifier)))

(defn parse-uri
  "Inverse of entity->uri. Returns a map:
     {:class-ident keyword     — the entity's class
      :entity-ident keyword | nil  — entity's :db/ident if path is keyword-shaped
      :entity-eid Long | nil   — entity's :db/id if path is numeric}

   Returns nil if the URI doesn't match the scheme."
  [uri]
  (when (and uri (str/starts-with? uri (str uri-scheme "/")))
    (let [path (subs uri (inc (count uri-scheme)))
          parts (str/split path #"/")]
      (when (>= (count parts) 3)
        (let [[cls-ns cls-name & rest-parts] parts
              ;; Identifier is everything after class-ns/class-name.
              ;; Two valid shapes:
              ;;   <ns>/<name>  (keyword ident, 2 parts)
              ;;   <eid>        (numeric eid, 1 part)
              entity-path (str/join "/" rest-parts)
              {:keys [ident eid]} (cond
                                    (re-matches #"\d+" entity-path)
                                    {:eid (Long/parseLong entity-path)}

                                    (str/includes? entity-path "/")
                                    (let [[ens ename] (str/split entity-path #"/" 2)]
                                      {:ident (keyword ens ename)})

                                    :else
                                    {:ident (keyword entity-path)})]
          {:class-ident  (keyword cls-ns cls-name)
           :entity-ident ident
           :entity-eid   eid})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resource description (the metadata emitted by resources/list)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- entity->resource-name
  "Human-readable name for an entity. Prefers :dt/name slot;
   falls back to :db/ident → string; falls back to :db/id."
  [entity]
  (or (:dt/name entity)
      (some-> (:db/ident entity) str)
      (str "entity-" (:db/id entity))))

(defn- entity->mime-type
  "MIME type for a resource. mm/Memory entities serialize as markdown;
   everything else as EDN by default."
  [entity cls-ident]
  (cond
    (= cls-ident :mm/Memory) "text/markdown"
    :else                    "application/edn"))

(defn entity->resource-description
  "Build the MCP resource description map for one entity, used in
   resources/list responses."
  [entity]
  (let [cls       (dt/class-of entity)
        cls-ident (:db/ident cls)
        uri       (entity->uri entity)]
    {:uri         uri
     :name        (entity->resource-name entity)
     :description (or (:dt/description entity)
                      (str (or cls-ident "Entity") " instance"))
     :mimeType    (entity->mime-type entity cls-ident)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resources/list handler
;;
;; Per B.1.5: walks the catalog of named entities. Bootstrap-by-discovery
;; means every :db/ident-bearing entity is auto-exposed; no hand-curated
;; resource list.

(defn handle-list
  "MCP `resources/list` — returns the catalog of named entities as
   resource descriptions. Stage C.5 returns ALL named instances of
   :dt/Resource (the metamodel root); subsequent stages add pagination."
  [id _params]
  (try
    (let [resources (->> (dt/all-named-instances-of :dt/Resource)
                         (map entity->resource-description)
                         (sort-by :uri)
                         vec)]
      {:jsonrpc "2.0"
       :id      id
       :result  {:resources resources}})
    (catch Exception e
      (log/error e :MCP/resources-list-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32603
                 :message "Resource list failed"
                 :data    {:exception-message (.getMessage e)}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resources/read handler
;;
;; Per B.1.5: returns the entity's serialized data. mm/Memory entities
;; would render as markdown per export ADR M.3 (Stage C.5.1); other
;; entities return EDN projection.

(defn- resolve-entity
  "Look up an entity by parsed URI. Uses dt/* introspection.

   Stage C.5: assumes named entities (entity-ident set). dt/class-of
   on a keyword ident — Sandbar resolves :db/ident keywords to entities
   transparently when used as values; but for explicit lookup we need
   to find the entity by ident. Stage C.5 uses dt/all-named-instances-of
   then filters; Stage C.5.x can add dt/find-by-ident if it doesn't yet
   exist (improve-abstraction-not-bypass per discipline)."
  [{:keys [entity-ident class-ident]}]
  (when entity-ident
    (->> (dt/all-named-instances-of class-ident)
         (filter #(= entity-ident (:db/ident %)))
         first)))

(defn- render-entity-content
  "Render an entity's content for resources/read. Per B.1.5 +
   B.1.7: mm/Memory renders as markdown (composes with export ADR M.3
   when that emitter lands); other entities render as canonical EDN."
  [entity cls-ident]
  (cond
    (= cls-ident :mm/Memory)
    ;; Placeholder: real markdown rendering composes with the corpus-side
    ;; sandbar-export-markdown emitter (Stage C.5.1 follow-up).
    (str "# " (entity->resource-name entity) "\n\n"
         "_(mm/Memory markdown rendering pending Stage C.5.1; "
         "EDN projection below.)_\n\n"
         "```edn\n" (pr-str (into {} entity)) "\n```\n")

    :else
    (pr-str (into {} entity))))

(defn handle-read
  "MCP `resources/read` — returns the content of a resource at the
   given URI. Stage C.5: EDN projection for most entities; markdown
   placeholder for mm/Memory (Stage C.5.1 wires the export emitter)."
  [id params]
  (try
    (let [uri    (:uri params)
          parsed (parse-uri uri)]
      (cond
        (nil? parsed)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    -32602
                   :message (str "Invalid resource URI: " uri)}}

        :else
        (let [entity (resolve-entity parsed)]
          (cond
            (nil? entity)
            {:jsonrpc "2.0"
             :id      id
             :error   {:code    -32602
                       :message (str "Resource not found: " uri)
                       :data    {:parsed parsed}}}

            :else
            (let [cls-ident (:class-ident parsed)
                  content   (render-entity-content entity cls-ident)
                  mime      (entity->mime-type entity cls-ident)]
              {:jsonrpc "2.0"
               :id      id
               :result  {:contents [{:uri      uri
                                     :mimeType mime
                                     :text     content}]}})))))
    (catch Exception e
      (log/error e :MCP/resources-read-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    -32603
                 :message "Resource read failed"
                 :data    {:exception-message (.getMessage e)}}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Subscription registry
;;
;; Per B.1.5: clients can subscribe to a specific resource URI via
;; resources/subscribe; the server pushes notifications/resources/updated
;; when the entity changes.
;;
;; Stage C.5 implements the registry; the tx-report-queue listener that
;; FIRES updates lands in Stage C.5.3.

(defonce ^:private +subscriptions+
  ;; Map of URI string → set of subscriber-ids that watch this resource.
  ;; The subscriber-id matches the SSE subscription id from
  ;; sandbar.mcp.notifications/register!.  A subscriber-id of ::broadcast
  ;; is the legacy wildcard sentinel meaning "fan out to ALL currently-
  ;; registered SSE subscribers" — preserved for clients that don't yet
  ;; pass an explicit :subscriberId param on resources/subscribe.
  (atom {}))

(def ^:const broadcast-sentinel
  "Subscriber-id sentinel used when a resources/subscribe request arrives
   without an explicit :subscriberId.  See entity-updated! for the fan-out
   semantics."
  ::broadcast)

(defn subscribe!
  "Register a subscription. uri is the resource URI; subscriber-id is the
   SSE subscriber from sandbar.mcp.notifications (or ::broadcast)."
  [uri subscriber-id]
  (swap! +subscriptions+ update uri (fnil conj #{}) subscriber-id))

(defn unsubscribe!
  "Remove a subscription. Idempotent.  Returns nil (canonical idempotent
   shape) rather than the swap! atom value."
  [uri subscriber-id]
  (swap! +subscriptions+
         (fn [subs]
           (let [updated (update subs uri (fnil disj #{}) subscriber-id)]
             (if (empty? (get updated uri))
               (dissoc updated uri)
               updated))))
  nil)

(defn subscriber-count
  "How many subscribers does this URI have?"
  [uri]
  (count (get @+subscriptions+ uri #{})))

(defn all-subscriptions
  "Diagnostic snapshot."
  []
  @+subscriptions+)

(defn clear-all-subscriptions!
  "Test-only: drop all subscriptions."
  []
  (reset! +subscriptions+ {}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resources/subscribe + resources/unsubscribe handlers
;;
;; Stage C.5: client provides URI + their MCP session identifies them;
;; we'd ideally tie subscription to the SSE subscriber-id from the
;; client's open SSE channel. For now Stage C.5 stores by URI alone
;; (broadcast on update). Per-subscriber routing lands in C.5.3.

(defn handle-subscribe
  "MCP `resources/subscribe`. Records URI in subscription registry, bound
   to the client's SSE subscriber-id when provided.

   Accepted params:
     :uri          — required; resource URI
     :subscriberId — optional; SSE subscriber-id received in the
                     `notifications/sandbar/sse-ready` initial event.  When
                     supplied, updates to the URI route only to this
                     subscriber.  When omitted, the legacy ::broadcast
                     sentinel is bound; updates fan out to all registered
                     SSE subscribers (preserved for non-SSE callers + back-
                     compat).  Per F-S-001 resolution."
  [id params]
  (let [uri      (:uri params)
        sub-id   (:subscriberId params)]
    (cond
      (nil? uri)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code -32602 :message "resources/subscribe requires :uri parameter"}}

      :else
      (do
        (subscribe! uri (or sub-id broadcast-sentinel))
        {:jsonrpc "2.0"
         :id      id
         :result  {}}))))

(defn handle-unsubscribe
  "MCP `resources/unsubscribe`.  Symmetric to handle-subscribe — removes
   the (uri, subscriberId-or-broadcast) binding."
  [id params]
  (let [uri    (:uri params)
        sub-id (:subscriberId params)]
    (cond
      (nil? uri)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code -32602 :message "resources/unsubscribe requires :uri parameter"}}

      :else
      (do
        (unsubscribe! uri (or sub-id broadcast-sentinel))
        {:jsonrpc "2.0"
         :id      id
         :result  {}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Update broadcaster — called by callers that know an entity changed
;;
;; Per B.1.9: MCP-write through dt/make should fire this for the affected
;; entity. Stage C.5.3 wires this to tx-report-queue automatically.

(defn entity-updated!
  "Notify subscribers that an entity changed.  Routes
   notifications/resources/updated to subscribers bound to the entity's
   URI per F-S-001 resolution:

   - subscriptions registered with an explicit subscriber-id receive a
     targeted notification via notifications/publish-to!
   - subscriptions registered with the legacy ::broadcast sentinel
     fan out to ALL currently-registered SSE subscribers via
     notifications/resources-updated! (back-compat path)

   No-op when no subscriptions exist for the URI."
  [entity]
  (let [uri        (entity->uri entity)
        subs       (get @+subscriptions+ uri #{})
        broadcast? (contains? subs broadcast-sentinel)
        specific   (disj subs broadcast-sentinel)]
    (when (seq subs)
      (log/debug :MCP/resources-updated
                 {:uri           uri
                  :subscribers   (count subs)
                  :broadcast?    broadcast?
                  :specific-ids  specific})
      (when broadcast?
        (notifications/resources-updated! uri))
      (when (seq specific)
        (notifications/publish-to! specific
                                   "notifications/resources/updated"
                                   {:uri uri})))))
