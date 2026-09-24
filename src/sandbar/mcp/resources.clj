(ns sandbar.mcp.resources
  "MCP resource catalog, content reads, and subscriptions.

   The catalog describes named :dt/Resource descendants the caller may read.
   Native codecs render content where available, with an EDN fallback.
   Resource URIs include class and entity identity; clients should discover
   them from resources/list rather than infer universal URI round trips.

   Subscriptions are held in process and delivered through the SSE subscriber
   registry. Read visibility and delivery-time clearance checks apply before
   content or update notifications reach a caller. See doc/concepts/mcp-protocol.md."
  (:require [clojure.string            :as str]
            [clojure.tools.logging     :as log]
            [sandbar.codec             :as codec]
            [sandbar.codec.protocol    :as codec-proto]
            [sandbar.db.datomic        :as db]
            [sandbar.db.datatype       :as dt]
            [sandbar.projection     :as project-graph]
            [sandbar.mcp.clearance     :as clearance]
            [sandbar.mcp.notifications :as notifications]
            [sandbar.security.query    :as secq]
            [sandbar.security.visibility :as visibility]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; URI scheme — mcp://sandbar/<class-ns>/<class-name>/<ident-or-eid>
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:const uri-scheme "mcp://sandbar")

(defn entity->uri
  "Construct mcp://sandbar/<class-ns>/<class-name>/<ident-or-eid>.
   The class ident comes from `dt/class-ident-of`, whose return value is a
   keyword. A class entity is itself an instance of :dt/Class: :mm/Memory
   therefore has URI mcp://sandbar/dt/Class/mm/Memory. Named entities use
   their ident; an unnamed entity falls back to its local database eid."
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
  "Parse a Sandbar resource URI into :class-ident and either :entity-ident
   or :entity-eid. Return nil when the scheme or minimum path shape does not
   match. This parser does not resolve the entity or validate its class;
   resources/read currently resolves only the ident branch."
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

(defn- format->mime-type
  "Label a known produced format. Custom codecs declare their MIME types;
   omit the optional label when no type is declared rather than guess."
  [format]
  (case format
    :markdown "text/markdown"
    :edn "application/edn"
    (some-> (codec/codec-for format) codec-proto/mime-types first)))

(defn entity->resource-description
  "Build resource metadata with the class's directly declared native MIME type.
   Read responses report the actual produced format
   (which can differ after an EDN fallback); listing does not render bodies."
  [entity]
  (let [cls-ident (dt/class-ident-of entity)
        uri       (entity->uri entity)
        mime      (format->mime-type (or (codec/native-codec-for-class cls-ident) :edn))]
    (cond-> {:uri uri
             :name (entity->resource-name entity)
             :description (or (:dt/description entity)
                              (str (or cls-ident "Entity") " instance"))}
      mime (assoc :mimeType mime))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resources/list handler
;;
;; Per B.1.5: walks the catalog of named entities. Bootstrap-by-discovery
;; means every :db/ident-bearing entity is auto-exposed; no hand-curated
;; resource list.

(declare read-cleared?)

(defn handle-list
  "Return resource descriptions for the named :dt/Resource descendants
   allowed by `read-cleared?`, sorted by URI. The catalog and content reads
   use the same visibility decision. There is no pagination here.
   The two-argument form uses the resource plane's restricted nil-principal
   policy; authenticated dispatch calls the three-argument form."
  ([id params] (handle-list id params nil))
  ([id _params principal]
  (try
    (let [resources (->> (dt/named-entities-of :dt/Resource)
                         ;; D4b / CT-03: the catalog applies the SAME decision
                         ;; as resources/read — a firewalled or uncleared entity
                         ;; is not advertised (closes the 2026-07-07 known gap:
                         ;; :auth/* instances were enumerated by ident and URI).
                         (filter #(read-cleared? principal %))
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
       :error   {:code    jsonrpc-status/internal-error
                 :message "Resource list failed"
                 :data    {:exception-message (.getMessage e)}}}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; resources/read handler
;;
;; Per B.1.5: returns the entity's serialized data. mm/Memory entities
;; would render as markdown per export ADR M.3 (Stage C.5.1); other
;; entities return EDN projection.

(defn- resolve-entity
  "Resolve the entity-ident parsed from a resource URI with dt/find-by-ident.
   handle-read checks the class component after the visibility decision."
  [{:keys [entity-ident _class-ident]}]
  (when entity-ident
    (dt/find-by-ident entity-ident)))

(defn- render-entity-content
  "Render resources/read content through the shared realize-and-emit
   projection primitive, carrying its actual format. Entities without a native
   codec and render exceptions both return an explicitly labelled EDN fallback."
  [entity]
  (try
    (or (project-graph/realize-and-emit-entity-representation entity)
        {:format :edn :text (pr-str (into {} entity))})
    (catch Exception e
      (log/warn e :MCP/render-entity-fallback
                {:entity-id (:db/id entity)})
      {:format :edn :text (pr-str (into {} entity))})))

(defn read-cleared?
  "Decide whether a resource may be listed or read by `principal`.
   The namespace firewall always applies. A nil principal sees public
   memories, their owned content, and entities outside the compartment model. An authenticated
   principal uses `visibility/entity-visible-to?`; absent memory visibility
   defaults to private. Full clearance can authorize private memories, but
   project-scoped credential provisioning is not implemented.

   Sharing this predicate between catalog and body reads avoids advertising
   a resource that the same caller cannot read."
  [principal entity]
  (and (secq/read-plane-entity-visible? entity)
       (if (nil? principal)
         (or (not (visibility/compartmented? entity))
             (= :public (:visibility (visibility/read-compartment entity))))
         (visibility/entity-visible-to? principal entity))))

(defn handle-read
  "Read a discovered resource URI after namespace and memory-clearance checks.
   Missing, forbidden and class-mismatched resources use the same not-found envelope,
   avoiding a distinct content-read error that reveals private existence.
   Native codecs render content with an EDN fallback; memory rendering is
   implemented, not a placeholder.

   Authenticated dispatch passes the principal. The two-argument in-process
   form uses `read-cleared?` with nil and does not grant private clearance."
  ([id params] (handle-read id params nil))
  ([id params principal]
  (try
    (let [uri    (:uri params)
          parsed (parse-uri uri)]
      (cond
        (nil? parsed)
        {:jsonrpc "2.0"
         :id      id
         :error   {:code    jsonrpc-status/invalid-params
                   :message (str "Invalid resource URI: " uri)}}

        :else
        (let [entity (resolve-entity parsed)]
          (cond
            ;; NOT-FOUND and FORBIDDEN return the SAME envelope (no existence
            ;; oracle, AP-10 / EP-N3 §5.1): an absent entity and an entity the
            ;; principal is not cleared for are indistinguishable to the caller.
            (or (nil? entity)
                (not (read-cleared? principal entity))
                (not= (:class-ident parsed) (dt/class-ident-of entity)))
            {:jsonrpc "2.0"
             :id      id
             :error   {:code    jsonrpc-status/invalid-params
                       :message (str "Resource not found: " uri)
                       :data    {:uri uri}}}

            :else
            (let [{:keys [format text]} (render-entity-content entity)
                  mime (format->mime-type format)]
              {:jsonrpc "2.0"
               :id      id
               :result  {:contents [(cond-> {:uri uri :text text}
                                      mime (assoc :mimeType mime))]}})))))
    (catch Exception e
      (log/error e :MCP/resources-read-error)
      {:jsonrpc "2.0"
       :id      id
       :error   {:code    jsonrpc-status/internal-error
                 :message "Resource read failed"
                 :data    {:exception-message (.getMessage e)}}}))))

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

(defn- subscribe-uri-cleared?
  "Perform an early compartment check for a resolvable resource URI.
   A nil principal clears public compartments only. Unresolvable URIs,
   including resolution errors, are permitted at this stage; this check is
   therefore not the delivery authorization boundary. `entity-updated!`
   checks each recipient's clearance again on every notification."
  [principal uri]
  (if-let [entity (try (resolve-entity (parse-uri uri))
                       (catch Exception _ nil))]
    (let [database (db/db)
          compartment (visibility/read-compartment database entity)]
      (if (nil? principal)
        (= :public (:visibility compartment))
        (clearance/cleared-for-compartment? database principal compartment)))
    ;; unresolvable (or unresolvable-due-to-error) URI → permit;
    ;; delivery gate (EP-N2) is authoritative
    true))

(defn handle-subscribe
  "Register a URI subscription after an early compartment check.
   Params are :uri (required) and :subscriberId (optional, obtained from the
   notifications/sandbar/sse-ready event). Without an id, the legacy broadcast
   sentinel selects all registered subscribers as candidate recipients.

   A resolvable uncleared URI is refused with :subscribe-compartment-forbidden;
   an unresolvable URI is accepted. Every eventual recipient is independently
   checked by `entity-updated!`. Authenticated dispatch supplies the principal;
   the two-argument form gives nil no private-compartment clearance."
  ([id params] (handle-subscribe id params nil))
  ([id params principal]
   (let [uri      (:uri params)
         sub-id   (:subscriberId params)]
     (cond
       (nil? uri)
       {:jsonrpc "2.0"
        :id      id
        :error   {:code jsonrpc-status/invalid-params :message "resources/subscribe requires :uri parameter"}}

       ;; EP-N1: refuse an obviously-uncleared subscribe to a resolvable URI.
       (not (subscribe-uri-cleared? principal uri))
       (do
         (log/warn :MCP/subscribe-denied
                   {:uri uri
                    :principal-eid (:db/id principal)
                    :reason :subscribe-compartment-forbidden})
         {:jsonrpc "2.0"
          :id      id
          :error   {:code    jsonrpc-status/invalid-params
                    :message (str "Subscription denied: caller not cleared for the compartment of " uri)
                    :data    {:uri uri :reason :subscribe-compartment-forbidden}}})

       :else
       (do
         (subscribe! uri (or sub-id broadcast-sentinel))
         {:jsonrpc "2.0"
          :id      id
          :result  {}})))))

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
       :error   {:code jsonrpc-status/invalid-params :message "resources/unsubscribe requires :uri parameter"}}

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
  "Notify subscribers of an entity change after checking recipient clearance.
   Candidate ids are the URI-bound subscribers, plus all registered ids when
   the broadcast sentinel is present. Keep only ids positively cleared for
   the entity's current compartment and deliver through publish-to!.

   Recheck on every delivery: both registries survive namespace reloads, and
   old subscriptions must not preserve stale authorization decisions. A
   missing principal does not grant private clearance. Unknown disconnected
   ids are skipped by publish-to!; no subscription for the URI means no send."
  [entity]
  (let [uri  (entity->uri entity)
        subs (get @+subscriptions+ uri #{})]
    (when (seq subs)
      (let [database      (db/db)
            compartment  (visibility/read-compartment database entity)
            subscribers  (notifications/all-subscribers)
            ;; Expand ::broadcast to the concrete registered subscriber-id set
            ;; BEFORE filtering, so a broadcast-bound subscriber is subjected to
            ;; the same per-subscriber clearance check as a targeted one.
            candidate-ids (if (contains? subs broadcast-sentinel)
                            (into (disj subs broadcast-sentinel)
                                  (keys subscribers))
                            (disj subs broadcast-sentinel))
            ;; Delivery-time clearance filter (keep-if-cleared, fail-closed).
            cleared-ids   (filter #(clearance/subscriber-cleared-for-compartment?
                                     database subscribers compartment %)
                                  candidate-ids)]
        (log/debug :MCP/resources-updated
                   {:uri        uri
                    :candidates (count candidate-ids)
                    :cleared    (count cleared-ids)
                    :dropped    (- (count candidate-ids) (count cleared-ids))})
        (when (seq cleared-ids)
          (notifications/publish-to! cleared-ids
                                     "notifications/resources/updated"
                                     {:uri uri}))))))
