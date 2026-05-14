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
            [sandbar.project-graph     :as project-graph]
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

   URI shape: <class-ns>/<class-name>/<ident-or-eid>.  The class path
   comes from `dt/class-ident-of` (explicit return-shape: keyword ident).

   Per the metacircular property — :dt/Class is an instance of :dt/Class —
   for a class entity X, class-ident-of(X) = :dt/Class; for an instance Y
   of class X, class-ident-of(Y) = X.

   Prior bug (codex MUST-FIX #2 + ultrareview #6 at resources.clj:53):
   used `(or (:db/ident (dt/class-of entity)) :unknown)`.  But
   `dt/class-of` already returns a keyword ident, and
   `(:db/ident keyword)` is keyword-as-fn lookup that returns nil →
   `cls-ident` was always `:unknown`.  Migration to
   `dt/class-ident-of` makes the return-shape explicit at the call
   site."
  [entity]
  (let [cls-ident    (or (dt/class-ident-of entity) :unknown)
        entity-ident (:db/ident entity)
        identifier   (cond
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
   resources/list responses.

   Uses `dt/class-ident-of` (returns keyword ident) directly — the
   prior `(:db/ident (dt/class-of entity))` pattern returned nil
   because `dt/class-of` already returns an ident, and keyword-as-fn
   lookup on a keyword for `:db/ident` is a no-op (codex MUST-FIX #2)."
  [entity]
  (let [cls-ident (dt/class-ident-of entity)
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
   resource descriptions. Returns ALL named instances of :dt/Resource
   (the metamodel root); subsequent stages add pagination.

   Uses `dt/named-entities-of` (returns entity maps; explicit return
   shape per the Q1=B dt/* split) rather than the prior
   `dt/all-named-instances-of` (returns idents — which then fed into
   `entity->resource-description` expecting entity maps, producing
   nil URIs and broken descriptions; codex MUST-FIX #2)."
  [id _params]
  (try
    (let [resources (->> (dt/named-entities-of :dt/Resource)
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
  "Look up an entity by parsed URI.  Uses `dt/find-by-ident` (the
   Stage-A Q1=B primitive that replaces the prior
   `dt/all-named-instances-of` + filter-by-ident pattern — the prior
   shape silently collapsed to nil because the filter tried to read
   `:db/ident` off idents that ARE the idents already).

   The `:class-ident` is currently unused — `dt/find-by-ident`
   resolves the entity directly without needing the class.  Kept in
   the destructure for future use (e.g., asserting the resolved
   entity's class matches the URI's class component)."
  [{:keys [entity-ident _class-ident]}]
  (when entity-ident
    (dt/find-by-ident entity-ident)))

(defn- render-entity-content
  "Render an entity's content for resources/read.

   Delegates to `sandbar.project-graph/realize-and-emit-entity` — the
   lifted substrate primitive that handles realize-tree-then-emit for
   any class with `:dt/native-codec` declared (and a registered
   walker for tree-shaped classes; mm/Memory + mm/Section today).
   For entities without a native codec, falls back to canonical EDN.

   Codex MUST-FIX #4 lift target — the prior implementation inlined
   realize + dispatch + emit here, duplicating logic with
   `sandbar.project-graph/project-graph`.  The shared substrate
   primitive eliminates the duplication."
  [entity _cls-ident]
  (try
    (or (project-graph/realize-and-emit-entity entity)
        (pr-str (into {} entity)))
    (catch Exception e
      (log/warn e :MCP/render-entity-fallback
                {:entity-id (:db/id entity)})
      (pr-str (into {} entity)))))

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
