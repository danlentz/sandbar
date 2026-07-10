(ns sandbar.deploy.registry
  "W1.deploy — the per-machine STORE-REGISTRY: a LOGICAL trust-scope → THIS
  machine's PHYSICAL `{transactor-endpoint, corpus-repo, local-disk-path, sid}`,
  plus the CREDENTIAL AIR-GAP that makes a cross-scope transactor connection
  impossible rather than merely disallowed (W1.deploy §2/§5).

  ── WHY this exists (the standing physical lock) ────────────────────────────
  The registry is location-only indirection (R7 relocatability): it maps a
  logical scope to where its DB physically lives on THIS host, and NOTHING
  else.  Membership authority stays corpus-canonical
  (`:mm.context/visible-projects`, resolved by `sandbar.project.route`) — a
  project present only in a registry but absent from `visible-projects` is NOT a
  member.  The registry never confers membership; it only answers `where`.

  The load-bearing security property is the CREDENTIAL AIR-GAP (W1.deploy §5,
  arc-plan §1.3 truth #4): a PUBLIC-scope process must hold NO credential /
  endpoint for any PRIVATE-scope transactor.  Acceptance is not \"we don't do
  that\" — it is \"the connection is impossible\": the public process's
  connection surface simply does not contain a private endpoint (A-1), and an
  explicit public→private connect REFUSES at the resolver/process boundary
  (A-2), never at a query filter.  This air-gap IS the standing physical lock
  the CA-6 write-side deferral rests on (two-lock collapse rule, W1.deploy §CA-6
  obligation): weakening it reverts CA-6 to fix-now.  Every function here is
  therefore written to STRENGTHEN physical exclusion, never widen it.

  ── SHAPE (reuses the 2026-05-12 store-registry EDN spirit) ─────────────────
  A registry is a per-machine EDN map stamped with the `:owner-scope` — the
  trust scope of the PROCESS that loads it — plus a `:public` entry and/or a
  `:private` map keyed by `:mm.project/ident`:

    {:schema-version 1
     :owner-scope    :trust-scope/public         ; the loading process's scope
     :public {:transactor-endpoint \"datomic:dev://localhost:4334/\"
              :sid \"global\" :corpus-repo \"…\" :local-disk-path \"…\"}
     :private {:proj/foo {:transactor-endpoint \"datomic:dev://localhost:4336/\"
                          :sid \"foo\" :corpus-repo \"…\" :local-disk-path \"…\"}}}

  `:owner-scope` is the air-gap key, and it must be WELL-FORMED: exactly
  `:trust-scope/public` or a `[:trust-scope/private <key>]` vector.  A registry
  that cannot name its own scope (absent / nil / malformed `:owner-scope`) is
  REFUSED loudly at bring-up (`assert-owner-scope-resolved!`) — never a silent
  no-op of the air-gap assertion.  A `:trust-scope/public` owner registry MUST
  carry NO private-scope ENTRY at all (`assert-credential-air-gap!`) — not
  merely no private transactor endpoint: a public process has no legitimate use
  for a private scope's `:local-disk-path` / `:corpus-repo` handles either, and
  the registry is location indirection, so an entry-level refusal closes the
  disclosed `:local-disk-path` hole in one move (strengthen-never-widen).  The
  strongest posture on the sandboxed work machine is a PRIVATE-only owner
  registry that reaches the public corpus read-only BY REFERENCE
  (`public-corpus-reference`, R9) and never co-locates a public transactor.

  ── LOGICAL scope key shape (consumes `sandbar.project.route`) ──────────────
  The logical trust-scope this registry keys on is EXACTLY what
  `route/trust-scope-of` produces: `:trust-scope/public` or the vector
  `[:trust-scope/private <project-key>]`.  This ns never re-derives a scope from
  a label — it consumes the route resolver's output and maps it to a physical
  endpoint (the W1.ctx spine owns derivation; W1.deploy owns the physical map).

  Spec: W1.deploy-topology.md §2 (store-registry contract), §5 (adversary /
  credential air-gap), DEP-3 (cross-scope connection impossible), DEP-4 (private
  store ≠ public repo).  Prior art: the 2026-05-12 multi-store hybrid ADR
  (`decisions/datomic_multi_store_deployment_strategy_hybrid_*`, D.3 port
  policy) + `decisions/private_sandbar_stores_live_in_their_own_git_repos_*`."
  (:require [clojure.edn     :as edn]
            [clojure.java.io :as io]))

;;; ===========================================================================
;;; LOGICAL trust-scope key shape (the `route/trust-scope-of` output vocabulary)
;;; ===========================================================================

(def public-scope
  "The single shared public-bottom trust scope key (matches
  `route/trust-scope-of`'s `:public` branch).  The ONE scope every process may
  reference; the ONLY scope a public-owner registry may expose an endpoint for."
  :trust-scope/public)

(defn public-scope?
  "Is `trust-scope` the shared public bottom?  Fail-closed: ONLY the exact
  `:trust-scope/public` key is public; every other shape (including a
  malformed/nil scope) is treated as non-public by the callers that gate on it."
  [trust-scope]
  (= public-scope trust-scope))

(defn private-scope?
  "Is `trust-scope` a per-project private scope — the `[:trust-scope/private
  <project-key>]` vector `route/trust-scope-of` emits for any non-`:public`
  sensitivity?"
  [trust-scope]
  (and (vector? trust-scope)
       (= :trust-scope/private (first trust-scope))
       (some? (second trust-scope))))

(defn private-scope-key
  "The `:mm.project/ident` project-key inside a `[:trust-scope/private <key>]`
  scope vector, or nil when `trust-scope` is not a private-scope vector."
  [trust-scope]
  (when (private-scope? trust-scope)
    (second trust-scope)))

;;; ===========================================================================
;;; Loading (pure EDN — no DB, no connection)
;;; ===========================================================================

(defn parse-registry
  "Parse a store-registry EDN `source` (a string) to a map.  PURE — reads no
  connection and opens nothing.  Returns the parsed map, or throws
  `:sandbar/error :registry-parse-error` on unreadable EDN (a malformed
  per-machine registry is an operator error that must fail loudly, never
  degrade to an empty/permissive map)."
  [source]
  (try
    (edn/read-string source)
    (catch Throwable t
      (throw (ex-info "store-registry EDN is unreadable; refusing"
                      {:sandbar/error :registry-parse-error
                       :cause          (.getMessage t)})))))

(defn load-registry
  "Read + parse the store-registry EDN file at `path`.  Throws
  `:sandbar/error :registry-not-found` when the file is absent (a missing
  per-machine registry must fail loudly, not resolve to a permissive default —
  fail-closed).  Delegates parsing to `parse-registry`."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info "store-registry file not found; refusing"
                      {:sandbar/error :registry-not-found :path (str path)})))
    (parse-registry (slurp f))))

(defn owner-scope
  "The trust scope of the PROCESS that owns/loads `registry` — the `:owner-scope`
  field.  This is the air-gap anchor: it decides which scopes the loading
  process may open a live transactor connection to (`reachable?`)."
  [registry]
  (:owner-scope registry))

(defn owner-scope-resolved?
  "Is `registry`'s `:owner-scope` a WELL-FORMED trust scope — exactly
  `:trust-scope/public`, or a `[:trust-scope/private <key>]` vector with a
  non-nil key?  A registry whose owner scope is absent / nil / malformed has no
  air-gap anchor to enforce, so the bring-up composite refuses it loudly
  (`assert-owner-scope-resolved!`).  Fail-closed: anything that is neither the
  public bottom nor a well-formed private vector is unresolved."
  [registry]
  (let [s (owner-scope registry)]
    (or (public-scope? s) (private-scope? s))))

;;; ===========================================================================
;;; Entry lookup (LOCATION only — never membership)
;;; ===========================================================================

(defn scope-entry
  "The physical `{:transactor-endpoint :sid :corpus-repo :local-disk-path}`
  entry for logical `trust-scope` in `registry`, or nil when the registry
  carries no entry for it.  PURE map lookup — performs NO reachability/air-gap
  check (that gate is `resolve-endpoint`); a raw lookup is available so
  `assert-credential-air-gap!` can INSPECT a forbidden entry in order to refuse
  it."
  [registry trust-scope]
  (cond
    (public-scope? trust-scope)  (:public registry)
    (private-scope? trust-scope) (get-in registry [:private (private-scope-key trust-scope)])
    :else                        nil))

(defn private-entries
  "The seq of `[project-key entry]` pairs under `registry`'s `:private` map
  (empty when the registry exposes no private scope).  Used by the air-gap +
  repo-distinctness assertions to sweep every private endpoint the registry
  makes reachable."
  [registry]
  (seq (:private registry)))

(defn scope-entry-pairs
  "Every `[logical-trust-scope physical-entry]` pair `registry` declares — the
  `:public` entry keyed `:trust-scope/public`, plus each `:private` entry keyed
  `[:trust-scope/private <project-key>]`.  The uniform pair list the
  reachability filter (`reachable-endpoints`) ranges over, so the scope-key
  shape is constructed in ONE place (never re-spelled at each call site)."
  [registry]
  (concat
    (when-let [p (:public registry)] [[public-scope p]])
    (for [[k entry] (:private registry)] [[:trust-scope/private k] entry])))

;;; ===========================================================================
;;; The CREDENTIAL AIR-GAP — reachability + the loud refuse-to-serve assertions
;;; ===========================================================================

(defn reachable?
  "Can a process whose scope is `owner` open a LIVE TRANSACTOR CONNECTION to
  `target`?  This is the process-boundary rule the credential air-gap enforces:

    owner :public      ⇒ ONLY :public                 (never any private)
    owner [private k]  ⇒ ONLY that same [private k]    (its own scope)
    else               ⇒ nothing                       (fail-closed)

  A public process therefore can NEVER reach a private transactor (A-1/A-2),
  and a private process reaches only its OWN scope — the public corpus is
  reachable read-only BY REFERENCE (`public-corpus-reference`), NOT as a live
  transactor endpoint (R9, W1.deploy §4).  Cross-private (k1→k2) is likewise
  unreachable, honoring the private↔private separation §1 promises.  PURE."
  [owner target]
  (cond
    (public-scope? owner)  (public-scope? target)
    (private-scope? owner) (= owner target)
    :else                  false))

(defn reachable-endpoints
  "The SET of transactor endpoints a process loading `registry` can actually
  open a live connection to — the registry's entries filtered through
  `reachable?` from its `:owner-scope`.  For a public-owner registry this is
  AT MOST the public endpoint; a private endpoint is never in the set even if
  one were hand-edited into the file (that mere presence is separately REFUSED
  by `assert-credential-air-gap!`).  This is the connection surface A-1
  enumerates: `(contains? (reachable-endpoints pub-registry) <private-ep>)`
  must be false."
  [registry]
  (let [owner (owner-scope registry)]
    (into #{}
          (comp (filter (fn [[scope _]] (reachable? owner scope)))
                (keep    (fn [[_ entry]] (:transactor-endpoint entry))))
          (scope-entry-pairs registry))))

(defn assert-owner-scope-resolved!
  "LOUD refuse-to-serve on a MALFORMED owner scope: a store-registry whose
  `:owner-scope` is absent / nil / neither `:trust-scope/public` nor a
  well-formed `[:trust-scope/private <key>]` vector cannot name the loading
  process's trust scope, so the credential air-gap has no anchor.  THROWS
  `:sandbar/error :registry-owner-unresolved`; returns nil when the owner scope
  is well-formed.

  Why loud, not silent: `reachable?` already fail-closes live CONNECTIONS from a
  malformed owner (it reaches nothing), but the refuse-to-serve CONTRACT must
  fire too — a registry that cannot name its own scope must be refused at
  BRING-UP, not slip past the composite to be caught only later at connect time.
  Called FIRST by `validate-registry!`, and defensively at the head of
  `assert-credential-air-gap!` so the standalone air-gap check is likewise never
  a silent no-op on a malformed owner (the earlier round's air-gap body fired
  ONLY for an exactly-`:public` owner, so a nil owner slipped through)."
  [registry]
  (when-not (owner-scope-resolved? registry)
    (throw (ex-info (str "store-registry :owner-scope is absent or malformed "
                         "(" (pr-str (owner-scope registry)) "); refusing at "
                         "bring-up — the credential air-gap has no anchor")
                    {:sandbar/error :registry-owner-unresolved
                     :owner-scope   (owner-scope registry)})))
  nil)

(defn assert-credential-air-gap!
  "The LOUD refuse-to-serve assertion realizing the credential air-gap (A-1 /
  DEP-3).  A `:trust-scope/public`-owner registry MUST carry NO `:private`
  ENTRY at all — not merely no private transactor endpoint.  If ANY private
  entry is present (even one bearing only a `:local-disk-path` / `:corpus-repo`
  and no endpoint), THROW `:sandbar/error :credential-air-gap-violation`.
  Returns nil (proceed) when the gap holds.

  Why entry-level, not endpoint-only (strengthen-never-widen): the registry is
  location indirection; a PUBLIC process has no legitimate use for a private
  scope's disk path or repo handle either, and disclosing them is the same
  air-gap breach as an endpoint.  Refusing any private entry closes the
  `:local-disk-path` hole the endpoint-only form left open, in one move
  consistent with this module's governance duty.  Preconditions on a RESOLVED
  owner scope (`assert-owner-scope-resolved!` is re-run here so a malformed
  owner is refused loudly rather than falling through the `public-scope?`
  guard).  Mirrors the `guard-registry-critical-write!` refuse-to-serve shape
  (`projection.clj:235`): a marker-tagged ex-info a bring-up must let abort the
  process, not swallow.

  This is the standing physical lock the CA-6 deferral rests on — it exists to
  STRENGTHEN, never weaken, physical exclusion (two-lock collapse rule)."
  [registry]
  (assert-owner-scope-resolved! registry)
  (when (public-scope? (owner-scope registry))
    (when-let [entries (private-entries registry)]
      (throw (ex-info (str "public-scope process registry carries a private-"
                           "scope entry; refusing (credential air-gap — a public "
                           "process holds NO private location or endpoint)")
                      {:sandbar/error      :credential-air-gap-violation
                       :owner-scope        (owner-scope registry)
                       :private-scope-keys (vec (map first entries))
                       ;; The sharpest exfil sub-case, surfaced for the operator:
                       ;; which (if any) of the refused entries carried a LIVE
                       ;; transactor endpoint (vs a bare disk-path/repo handle).
                       :with-transactor-endpoint
                       (vec (for [[k entry] entries
                                  :when (:transactor-endpoint entry)] k))}))))
  nil)

(defn resolve-endpoint
  "The physical transactor endpoint (a string) for logical `trust-scope`,
  resolved through `registry` — the ONE place a scope becomes a connectable
  URL.  REFUSES at the resolver/process boundary (A-2 / DEP-3) rather than a
  filter:

    • `trust-scope` unreachable from the registry's `:owner-scope`
      (`reachable?` false) ⇒ THROW `:sandbar/error :cross-scope-connection-refused`
      — the public→private (or cross-private) connect fails HERE, at the
      boundary, not at a query;
    • no registry entry for a reachable scope ⇒ THROW `:sandbar/error
      :no-such-scope`.

  So a public-scope caller can NEVER obtain a private transactor URL through
  this resolver — the connection is impossible, which is the acceptance
  criterion (W1.deploy §5)."
  [registry trust-scope]
  (let [owner (owner-scope registry)]
    (when-not (reachable? owner trust-scope)
      (throw (ex-info (str "connection to " (pr-str trust-scope) " is not "
                           "reachable from owner scope " (pr-str owner)
                           "; refusing at the process boundary")
                      {:sandbar/error :cross-scope-connection-refused
                       :owner-scope   owner
                       :target-scope  trust-scope})))
    (let [entry (scope-entry registry trust-scope)]
      (when-not (:transactor-endpoint entry)
        (throw (ex-info (str "no store-registry entry / endpoint for reachable "
                             "scope " (pr-str trust-scope) "; refusing")
                        {:sandbar/error :no-such-scope
                         :target-scope  trust-scope})))
      (:transactor-endpoint entry))))

;;; ===========================================================================
;;; DEP-4 — a private store's corpus-repo is NEVER the public corpus repo
;;; ===========================================================================

(defn forbidden-public-repos
  "The SET of repo handles a `:private` store's `:corpus-repo` may NEVER equal —
  BOTH the public scope's own `:corpus-repo` (`[:public :corpus-repo]`) AND the
  R9 by-reference `:public-corpus-ref` a PRIVATE-only owner registry carries.  A
  private corpus routing to EITHER would push private material onto the public
  corpus's git remote (the catastrophic-push shape).  nils dropped, so a
  registry declaring only one of the two handles compares against just that one."
  [registry]
  (into #{}
        (remove nil?)
        [(get-in registry [:public :corpus-repo])
         (:public-corpus-ref registry)]))

(defn assert-private-repo-distinct!
  "DEP-4 refuse-to-serve: every `:private` entry's `:corpus-repo` MUST differ
  from EVERY public corpus handle the registry names — BOTH the public scope's
  `[:public :corpus-repo]` AND the R9 `:public-corpus-ref` (the by-reference
  public repo a PRIVATE-only owner registry carries so a sandboxed private
  process can restore public material WITHOUT a live public transactor).  A
  private corpus routing to EITHER handle would push private material onto the
  public corpus's git remote — the catastrophic-push shape W1.F's air-gap
  guards.  On any collision THROW `:sandbar/error :private-repo-collision`;
  return nil otherwise.  (Fork-6 corpus≠code separation, W1.deploy §9.)

  Why `:public-corpus-ref` too: the earlier round compared ONLY against
  `[:public :corpus-repo]`, so a private-only owner registry (no `:public`
  entry, only `:public-corpus-ref`) — the exact sandboxed work-machine posture
  R9 recommends — vacuously passed even when its private corpus EQUALLED the
  by-reference public repo.  Folding `:public-corpus-ref` into the forbidden set
  closes that hole; a registry naming NEITHER public handle has an empty
  forbidden set and returns nil (nothing to collide with)."
  [registry]
  (let [forbidden (forbidden-public-repos registry)]
    (when (seq forbidden)
      (when-let [collisions (seq (for [[k entry] (private-entries registry)
                                       :when (contains? forbidden (:corpus-repo entry))]
                                   {:private-scope-key k
                                    :corpus-repo       (:corpus-repo entry)}))]
        (throw (ex-info (str "a private store routes to a PUBLIC corpus repo "
                             "handle; refusing (DEP-4 corpus separation)")
                        {:sandbar/error          :private-repo-collision
                         :forbidden-public-repos (vec forbidden)
                         :colliding              (vec collisions)})))))
  nil)

(defn public-corpus-reference
  "The public corpus REPO URL (a string) a PRIVATE-owner process may reach
  read-only BY REFERENCE — a git handle for clone/restore, deliberately WITHOUT
  any transactor endpoint (R9 / W1.deploy §4: \"reachable read-only by
  reference, never by co-locating a public transactor\").  Returns the
  `:public-corpus-ref` repo string, or nil when the registry declares none.
  Used so a sandboxed private process can restore public material without ever
  holding a live public-transactor credential — the by-reference half of the
  air-gap."
  [registry]
  (:public-corpus-ref registry))

;;; ===========================================================================
;;; Composite validation — the single entry point a bring-up calls
;;; ===========================================================================

(defn validate-registry!
  "Run every store-registry refuse-to-serve gate over `registry` and return it
  unchanged on success (so a bring-up can thread `(-> (load-registry p)
  validate-registry! …)`); throws the first marker-tagged violation otherwise.
  The composite standing-lock check, in fail-closed order:
    1. `assert-owner-scope-resolved!` — a registry that cannot name its own
       trust scope is refused FIRST (:registry-owner-unresolved), so no later
       gate silently no-ops on a malformed owner;
    2. `assert-credential-air-gap!`   — no private ENTRY under a public owner
       (:credential-air-gap-violation, A-1/DEP-3);
    3. `assert-private-repo-distinct!` — no private corpus routes to a public
       repo handle (:private-repo-collision, DEP-4).
  Intended to run at process bring-up, BEFORE any transactor connection opens."
  [registry]
  (assert-owner-scope-resolved!  registry)
  (assert-credential-air-gap!    registry)
  (assert-private-repo-distinct! registry)
  registry)
