(ns sandbar.mcp.authz
  "Principal-scope authorization for the MCP dispatch layer — the S5
   principal-check-at-dispatch gate.

   This namespace is the PURE decision core of the dispatch-layer gate
   (`sandbar.mcp.protocol/dispatch`).  It answers one question, without any
   side effect, for every inbound JSON-RPC method:

     Given the authenticated principal (or nil) and the method name, may this
     method run — and if not, which published `:reason` explains the deny?

   ## Why a separate namespace (Shape A′, per S5-PLAN §1.4 / AP-3)

   The ceremony-#4 wire breach shipped because the read-only token gate lived
   ONLY inside `tools/handle-call` — so `resources/*`, `prompts/*` and
   `tasks/*` were principal-BLIND over the wire.  S5 lifts a principal-SCOPE
   check to the dispatch layer so EVERY method passes it before its handler
   runs.

   Shape A′ makes the lift ADDITIVE, not a rewrite:

   - The `handle-call` read-only verb-class clause (`tools.clj`) STAYS
     byte-identical — it remains the single source of truth for a `tools/call`
     verb's mutation-ness (derived from the same catalog that produces the wire
     ToolAnnotations).  The dispatch gate does NOT duplicate that check; it does
     NOT classify `tools/call`'s verb.
   - This namespace therefore does NOT require `sandbar.mcp.tools` — it is a
     LEAF over `util.auth` + `mcp.envelope` + `util.jsonrpc-status` only.  No
     require cycle, no coupling to the 300-KB verb catalog.

   ## What this gate enforces on day one (S5-PLAN §2.3)

   - **Exempt pass-through** — `initialize` + `notifications/initialized` are
     protocol lifecycle and run unconditionally (a client cannot present a
     scoped principal before `initialize` completes).
   - **Fail-closed unscoped-deny (AP-2)** — an authenticated principal that
     carries NO capability-bearing role is `:scope/unscoped?` and is DENIED all
     non-exempt methods with `:unscoped-principal-denied`.  This is a
     deliberate BEHAVIOR CHANGE from today's permissive wire (a role-less
     ServiceAccount previously reached every non-tools/call method): the
     briefing's fail-closed rail is a hard constraint and CX2 confirmed the
     hole.
   - **Family policy** for the non-tools methods — `tasks/cancel` is MUTATING
     (deny read-only + unscoped); `resources/*`, `prompts/*`, `tasks/list`,
     `tasks/get` are READ families (allow if scoped).

   ## What stays inert until S6 (S5-PLAN §2.3 / §5-R2)

   The destination/compartment axes have NO substrate yet — the slots
   `:mm.memory/visibility`, `:auth/cleared-projects` and per-entity
   owning-project do not exist (grep-proven absent, D3 §7).  The five
   compartment `:reason` keywords are RESERVED here (so S9's compile-guarded
   tests can pin them) but no code path fires them: the scope carries
   `:scope/contexts #{:ctx/public}` inert, and `principal->scope` never sets
   `:scope/unscoped?` on a role-bearing principal.  `*default-visibility*` is
   `:private` (fail-closed, AP-6) and lives in `sandbar.mcp.clearance`, the
   compartment sibling; this namespace is verb-class + destination-family only.

   ## Purity contract

   `roles->capabilities`, `principal->scope`, `method->family` and
   `method-scope-decision` perform NO DB reads and NO URI resolution.  They
   operate over the principal projection the wire already hands the dispatch
   path.  That projection is a `datomic.query.EntityMap` (the return of
   `authenticate-api-key` → `find-service-account`'s `(db/entity eid)`), and
   its ref-many `:auth/roles` are THEMSELVES `EntityMap` objects carrying
   `:auth/role-name`.  These are NOT `clojure.lang.IPersistentMap` instances —
   `(map? an-entity-map)` is FALSE — so capability derivation reads
   `:auth/role-name` by plain keyword lookup (which resolves on both `EntityMap`
   and literal-map roles), NEVER by a `map?` guard.  The DB-free unit fixtures
   use literal maps of the same key-shape; a DB-backed wire test exercises the
   real `EntityMap` path so the two shapes cannot silently diverge.

   A bare-eid role (an unresolved `Long` ref) contributes no capability — a
   pure classifier cannot resolve it, and `(:auth/role-name <long>)` is nil,
   which `keep` drops.

   Per S5-PLAN.md §2.2 item 2 + §1.3 (AP-2) + §1.4 (AP-3, Shape A′).

   Stage S5 of the X-minus 0.2.0 build."
  (:require [clojure.tools.logging       :as log]
            [sandbar.mcp.envelope        :as envelope]
            [sandbar.util.auth           :as auth]
            [sandbar.util.jsonrpc-status :as jsonrpc-status]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Published :reason keywords
;;
;; These are the decision core's public vocabulary — the `:data :reason` values
;; a deny envelope carries.  D4's acceptance tests + S9's compile-guarded
;; scope-wire tests pin these literals, so they are named constants (never
;; inline keyword literals) to guarantee test ↔ enforcement never drift.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def reason-unscoped-denied
  "Fail-closed deny (AP-2): an authenticated principal carries no
   capability-bearing role, so every non-exempt method is refused at dispatch."
  :unscoped-principal-denied)

(def reason-read-only-forbidden-mutation
  "The read-only verb-class deny.  UNCHANGED and fires from
   `tools/handle-call` (Shape A′), NOT from this namespace — it is published
   here only so the family policy + tests share one keyword vocabulary.  The
   dispatch gate reuses it for the non-tools MUTATING family (`tasks/cancel`)."
  :read-only-principal-forbidden-mutation)

;; ---- Inert until S6 (compartment/destination axes — reserved, never fired) --
;;
;; No slot exists to resolve a destination or a compartment yet
;; (`:mm.memory/visibility`, `:auth/cleared-projects`, owning-project — all
;; grep-proven absent).  These keywords are DECLARED so S9's compile-guarded
;; `principal_scope_wire_test.clj` can reference them and so S6 mints the axes
;; against a stable vocabulary; NO code path in S5 returns any of them.

(def reason-out-of-scope-destination
  "RESERVED (inert until S6): a mutation names a destination outside the
   principal's scoped contexts.  No destination axis exists in S5."
  :out-of-scope-destination)

(def reason-out-of-scope-resource
  "RESERVED (inert until S6): `resources/read` on a URI outside the principal's
   cleared compartments.  No compartment axis exists in S5."
  :out-of-scope-resource)

(def reason-out-of-scope-subscribe
  "RESERVED (inert until S6): `resources/subscribe` to a URI outside the
   principal's cleared compartments.  No compartment axis exists in S5."
  :out-of-scope-subscribe)

(def reason-subscribe-compartment-forbidden
  "RESERVED (inert until S6): `resources/subscribe` refused because the
   subscribed compartment is not cleared for the principal."
  :subscribe-compartment-forbidden)

(def reason-read-compartment-forbidden
  "RESERVED (inert until S6): `resources/read` refused because the resource's
   compartment is not cleared for the principal."
  :read-compartment-forbidden)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Method → family census (the 13-method dispatch table)
;;
;; Mirrors `sandbar.mcp.protocol/method-handlers` exactly (13 rows).  Families:
;;
;;   :exempt   — protocol lifecycle; runs regardless of scope.
;;   :mutating — writes the substrate; deny for read-only AND unscoped.
;;   :read     — read/introspection; allow if scoped.
;;
;; `tools/call` is present as `:tools-call` — a SENTINEL, not a family — to
;; document that the dispatch gate deliberately does NOT re-classify its verb
;; (Shape A′: `handle-call` owns the tools/call verb-class check).  The unscoped
;; and exempt axes still apply to it; only the verb-class classification is
;; delegated.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def method-family
  "Static census: MCP method name → family keyword.  Exactly the 13 rows of
   `protocol/method-handlers`.

   - `:exempt`     `initialize`, `notifications/initialized`
   - `:tools-call` `tools/call` (verb-class check delegated to handle-call)
   - `:read`       `tools/list`, `resources/list`, `resources/read`,
                   `resources/subscribe`, `resources/unsubscribe`,
                   `prompts/list`, `prompts/get`, `tasks/list`, `tasks/get`
   - `:mutating`   `tasks/cancel`

   `tasks/cancel` is MUTATING even though `handle-cancel` is today a stub
   (`tasks.clj`) — future-proofed per CX2-CONCERN-7 / AP-9."
  {"initialize"                :exempt
   "notifications/initialized" :exempt
   "tools/list"                :read
   "tools/call"                :tools-call
   "resources/list"            :read
   "resources/read"            :read
   "resources/subscribe"       :read
   "resources/unsubscribe"     :read
   "prompts/list"              :read
   "prompts/get"               :read
   "tasks/list"                :read
   "tasks/get"                 :read
   "tasks/cancel"              :mutating})

(defn method->family
  "Classify an MCP method name into its scope family.  Returns `:exempt`,
   `:read`, `:mutating`, or `:tools-call` for a known method; `:mutating` for
   an UNKNOWN method (deny-by-default — an unrecognized method is treated as
   the most-restricted family, so a future method is refused for a
   non-full-access principal until it is explicitly censused).

   Pure: a static map lookup with a deny-by-default fallthrough."
  [method]
  (get method-family method :mutating))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Principal → capabilities → scope
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn roles->capabilities
  "Derive the set of capability role-name keywords a principal's roles confer.

   PURE — no DB read.  Reads `:auth/role-name` off each role via a plain
   keyword lookup.  This works UNIFORMLY across the two role shapes the
   dispatch path actually sees:

   - a literal Clojure map (the DB-free fixture shape), and
   - a `datomic.query.EntityMap` (the LIVE wire shape) — `authenticate-api-key`
     → `find-service-account` returns `(db/entity eid)`, whose ref-many
     `:auth/roles` are THEMSELVES `EntityMap` objects.  Critically, `map?` is
     FALSE for an `EntityMap`, so the earlier `(filter map?)` discarded every
     live role and projected every real read-only ServiceAccount as unscoped
     (fail-CLOSED over-denial: `:unscoped-principal-denied` on reads too, which
     regresses the ff176c2 verb-class gate and takes codex-review offline).
     Keyword lookup does NOT care about `map?` — `(:auth/role-name entity-map)`
     resolves on an `EntityMap` just as `auth/has-role?` (auth.clj) does.

   A bare-eid role (an unresolved `Long` ref) still contributes nothing: a pure
   classifier cannot resolve it, and `(:auth/role-name 42)` returns nil (keyword
   invocation on a non-associative yields nil, not an error), which `keep`
   drops.  A nil or role-less principal yields the empty set.

   The returned set is the principal's capability surface — its non-emptiness
   is what distinguishes a SCOPED principal from an unscoped one (AP-2).  Today
   the only capability the corpus mints is `:read-only`; the set-of-role-names
   shape generalizes to the S6 scope model without a signature change."
  [principal]
  (into #{}
        (keep :auth/role-name)
        (:auth/roles principal)))

(defn principal->scope
  "Project a principal into the dispatch gate's scope descriptor.

   PURE — no DB read, no URI resolution.  Returns one of:

   - `::unrestricted` (the keyword, not a map) for a NIL principal — the
     legacy/local in-process full-access path, unchanged.  The dispatch gate
     treats `::unrestricted` as \"run everything\"; it is NEVER produced for a
     wire principal (the /mcp chain's `require-bearer` 401s a nil identity
     before dispatch, so nil here means an in-process caller).

   - a scope map for an AUTHENTICATED principal:
       {:scope/capabilities <set of role-name keywords>
        :scope/read-only?    <bool — carries the :read-only capability>
        :scope/unscoped?     <bool — carries NO capability-bearing role>
        :scope/contexts      #{:ctx/public}}   ; inert until S6

     `:scope/unscoped?` is the AP-2 fail-closed flag: true iff
     `roles->capabilities` is empty.  A role-less authenticated principal is
     unscoped and the dispatch gate denies its non-exempt methods.

     `:scope/read-only?` mirrors `auth/read-only-principal?` so the family
     policy can deny the read-only capability a MUTATING non-tools method
     (`tasks/cancel`) without a second DB round-trip.

     `:scope/contexts` is fixed at `#{:ctx/public}` and is INERT until S6 mints
     `:auth/cleared-projects` — no S5 code path consumes it."
  [principal]
  (if (nil? principal)
    ::unrestricted
    (let [caps (roles->capabilities principal)]
      {:scope/capabilities caps
       :scope/read-only?   (contains? caps auth/read-only-role)
       :scope/unscoped?    (empty? caps)
       :scope/contexts     #{:ctx/public}})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The decision — method × scope → allow | {:deny :reason}
;;
;; ORDER IS LOAD-BEARING (S5-PLAN §2.2 item 2):
;;   1. exempt-allow FIRST   — lifecycle runs before any scope exists.
;;   2. unscoped-deny        — fail-closed for a role-less authenticated
;;                             principal (AP-2), BEFORE family policy.
;;   3. family policy        — read allow-if-scoped; mutating deny read-only;
;;                             tools/call verb-class delegated to handle-call.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private allow
  "The permit outcome of `method-scope-decision`: the method may run."
  ::allow)

(defn- deny
  "A deny outcome of `method-scope-decision`: `{:deny true :reason <kw>}`."
  [reason]
  {:deny true :reason reason})

(defn allow?
  "True iff `decision` (a `method-scope-decision` return) permits the method."
  [decision]
  (= allow decision))

(defn deny?
  "True iff `decision` (a `method-scope-decision` return) refuses the method."
  [decision]
  (and (map? decision) (true? (:deny decision))))

(defn method-scope-decision
  "The pure dispatch-gate decision.  Given a `method` name and the `scope`
   descriptor (`principal->scope`'s return — either `::unrestricted` or a scope
   map), return `::allow` or `{:deny true :reason <keyword>}`.

   PURE — no DB reads, no URI resolution.  Evaluation order (load-bearing):

     1. EXEMPT FIRST — `initialize` + `notifications/initialized` always allow,
        regardless of scope (a client cannot present a scoped principal before
        `initialize` completes).
     2. `::unrestricted` (nil-principal in-process path) — allow everything.
     3. UNSCOPED-DENY (AP-2) — an authenticated but role-less principal is
        `:scope/unscoped?`; deny every non-exempt method with
        `:unscoped-principal-denied`.  Checked BEFORE family policy so a
        role-less principal is refused uniformly, not per-family.
     4. FAMILY POLICY —
          `:tools-call` → allow (verb-class delegated to handle-call, Shape A′)
          `:read`       → allow (scoped principal; compartment axis inert to S6)
          `:mutating`   → deny read-only with `:read-only-principal-forbidden-
                          mutation`; else allow (a scoped, non-read-only
                          principal may mutate).

   The destination/compartment `:reason` keywords are NOT returned by any
   branch — they are reserved for S6 (see the reason-* defs)."
  [method scope]
  (let [family (method->family method)]
    (cond
      ;; (1) Lifecycle exemption — before any scope reasoning.
      (= :exempt family)
      allow

      ;; (2) Legacy/local nil-principal path — full access, unchanged.
      (= ::unrestricted scope)
      allow

      ;; (3) Fail-closed unscoped-deny (AP-2) — role-less authenticated
      ;; principal is refused every non-exempt method.
      (:scope/unscoped? scope)
      (deny reason-unscoped-denied)

      ;; (4) Family policy for a scoped principal.
      (= :mutating family)
      (if (:scope/read-only? scope)
        (deny reason-read-only-forbidden-mutation)
        allow)

      ;; :read and :tools-call for a scoped principal — allow.  (The tools/call
      ;; verb-class check + the read-only mutating-verb deny live in
      ;; handle-call per Shape A′; the compartment axis is inert until S6.)
      :else
      allow)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deny → JSON-RPC error envelope
;;
;; Mirrors `tools/read-only-denied`'s `:data` keys (`:tool`/`:role`/`:reason`)
;; so a dispatch-layer deny is envelope-shaped like the tools-path deny the
;; wire test already asserts.  For the dispatch gate the offending unit is a
;; METHOD (not a catalog verb), so the `:data` carries `:method` alongside
;; `:reason`; `:role` is included when the scope carries one.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- deny-message
  "Human-readable message for a dispatch deny — names the method + the reason so
   the caller learns exactly why (fail-loud, mirroring `read-only-denied`)."
  [method reason]
  (case reason
    :unscoped-principal-denied
    (str "Permission denied: method '" method "' requires a scoped principal;"
         " the authenticated principal carries no capability-bearing role and"
         " is denied all non-exempt methods (fail-closed).")

    :read-only-principal-forbidden-mutation
    (str "Permission denied: method '" method "' mutates the substrate and the"
         " authenticated principal holds the read-only role ("
         auth/read-only-role "), which may call read/introspection methods"
         " only.")

    ;; Fallthrough — any reserved/future reason gets a generic scope message.
    (str "Permission denied: method '" method "' is outside the authenticated"
         " principal's scope (" reason ").")))

(defn deny->jsonrpc-error
  "Build the JSON-RPC deny response for a `method-scope-decision` deny.

   `id` is the inbound message's `:id`; `method` the method name; `decision`
   the `{:deny true :reason <kw>}` map; `scope` the principal's scope
   descriptor (for the optional `:role` data key).

   For a REQUEST (an `id` is present) returns a `jsonrpc-error` whose `:data`
   mirrors `tools/read-only-denied`'s keys (`:reason` + the offending unit,
   here `:method`, plus `:role` when the scope carries one).

   For a NOTIFICATION (no `id` — a client → server push that expects no
   response) LOGS the deny and returns nil: JSON-RPC notifications never get a
   response, so the dispatch gate must swallow, not answer, a denied
   notification.  (The exempt set already covers `notifications/initialized`;
   this nil-return is the general contract for any future denied notification.)"
  [id method decision scope]
  (let [reason (:reason decision)
        role   (when (map? scope)
                 (first (:scope/capabilities scope)))]
    (if (nil? id)
      ;; Notification — no response permitted; log + swallow.
      (do
        (log/warn :MCP/dispatch-denied-notification
                  {:method method :reason reason})
        nil)
      ;; Request — fail-loud JSON-RPC error mirroring the tools-path envelope.
      (do
        (log/warn :MCP/dispatch-denied
                  {:method method :reason reason :role role})
        (envelope/jsonrpc-error
          id jsonrpc-status/invalid-params
          (deny-message method reason)
          (cond-> {:method method
                   :reason reason}
            role (assoc :role role)))))))
