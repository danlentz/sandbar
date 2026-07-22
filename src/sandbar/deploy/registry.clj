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
  no-op of the air-gap assertion.

  The air-gap is ONE invariant with two directions (`assert-credential-air-gap!`,
  `foreign-private-entries`): a registry may carry a `:private` ENTRY only for
  its OWNER's own scope.
    • a `:trust-scope/public` owner carries NO `:private` entry at all — not
      merely no private transactor endpoint: a public process has no legitimate
      use for a private scope's `:local-disk-path` / `:corpus-repo` handles
      either, and the registry is location indirection, so an entry-level refusal
      closes the disclosed `:local-disk-path` hole in one move
      (strengthen-never-widen); and
    • a `[:trust-scope/private k]` owner carries EXACTLY its own `:proj/k` entry
      — any `:private` entry keyed to a DIFFERENT scope is a FOREIGN disclosure
      and is refused (the private↔private separation, W1.deploy §1 / brief line
      28; alpha's registry names only alpha's location, never beta's).
  Both legs throw the same `:credential-air-gap-violation` marker,
  disambiguated by `:air-gap-direction` in ex-data.

  ONE retained, INTENDED asymmetry: a `[:trust-scope/private k]` owner holding a
  `:public` transactor ENTRY is permitted-but-unreachable — NOT refused by the
  air-gap (public location handles are the shared-bottom, not secrets; R9
  private-only is a recommendation), because a private→public live connect is
  already refused at `resolve-endpoint` (`reachable?` false).  This stays
  explicitly distinct from the foreign-PRIVATE refusal above.

  ── PORT DOCTRINE (the second half of A-1's physical exclusion) ─────────────
  Reachability is filtered by SCOPE, and the air-gap sweeps `:private` ENTRIES —
  NEITHER inspects endpoint PORTS.  So the shared `:public` slot needs one more
  physical lock: its transactor port must be the PUBLIC port (4334), never a
  RESERVED PRIVATE port (>= 4336).  Without it, a private-port endpoint
  hand-edited into the `:public` slot would be REACHABLE by the public owner
  (public→public), and `resolve-endpoint` would hand the public process the
  ruled-private 4336 — the air-gap misses it because the forgery lives in
  `:public`, not `:private`.  `assert-endpoint-port-doctrine!` (a
  `validate-registry!` gate) refuses it, and symmetrically refuses a PUBLIC port
  in a `:private` slot (a private scope co-located on the public transactor,
  DEP-1).  This is why A-1 — \"the public process's connection surface does not
  contain a private endpoint\" — holds for a VALIDATED registry across EVERY
  slot, not only `:private` (D.3 port policy).

  The gate is BLIND TO ENDPOINT FORM and FAIL-CLOSED on an unreadable one.  The
  port is parsed from BOTH a plain host (`localhost:4336`) AND a bracketed IPv6
  literal (`[::1]:4336`, `[2001:db8::1]:4336`) — an earlier parser stopped at the
  first colon of an IPv6 literal, read a nil port, and the gate SKIPPED nil-port
  slots, so a private-port endpoint smuggled in as bracketed IPv6 slipped BOTH
  slots' checks (the round-4 A-1 re-opening).  Now a PRESENT endpoint whose port
  cannot be parsed at all is itself a violation (`:unparseable-port`) — the slot
  is REFUSED, not skipped — so no endpoint form the parser cannot place can evade
  the doctrine.  (Only a slot with NO endpoint is exempt; that is
  `resolve-endpoint`'s `:no-such-scope` concern.)

  The strongest posture on the sandboxed work machine is a PRIVATE-only owner
  registry that reaches the public corpus read-only BY REFERENCE
  (`public-corpus-reference`, R9) and never co-locates a public transactor.

  ── DISCOVERY — how a process finds ITS registry (ruled read seam) ──────────
  `registry-path` resolves the file, two sources in order (confirm-only C-8 of
  the 2026-07-10 Dan docket, adopted as the standing default):
    1. the `SANDBAR_STORE_REGISTRY` env var when set + non-blank — the
       explicit per-machine ops override (R2);
    2. else the PER-PROJECT file `etc/sandbar-store-registry.edn` under the
       directory the process started FROM — fork-5's operating model (start
       the tool from the project's own directory) makes that file the
       project's OWN registry, per-project + per-machine, never shared
       (DEP-6).
  `bring-up-registry!` composes resolve → load → validate in the ONE call a
  bring-up makes.  A missing / unreadable / gate-violating file REFUSES
  loudly; resolution never invents a permissive default.

  ── SUPERVISION / BRING-UP POSTURE (A4 ruling, 2026-07-20) ──────────────────
  Ruled (docket C-6 → A4): process supervision is the IN-PROCESS component /
  bring-up script that invokes `bring-up-registry!` (⇒ `validate-registry!`)
  at process start — NO launchd-per-scope service, NO OS-level separation
  (no per-scope OS users, security domains, or storage ACLs).  The
  threat-model calibration is A4's \"enforce separation during normal use,
  not attack-paranoid\": scope separation is enforced IN-PROCESS by this
  registry's credential air-gap + the closure refuse-to-serve gates
  (`sandbar.deploy.closure`), not by OS machinery.

  INTERIM POSTURE (explicit): until the multi-store fork actually forks a
  private scope, the substrate runs as a SINGLE sandbar JVM serving the
  public scope only (one Datomic transactor slot, public 4334/4335).  DEP-1's
  one-transactor-process-per-trust-scope topology (public 4334 + net-new
  private 4336) becomes live WHEN that fork lands — and each per-scope
  process is then STILL brought up by script/component under this same
  posture, each validating its own registry at start.

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
            [clojure.java.io :as io]
            [clojure.string  :as str]))

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
  "Is `trust-scope` a per-project private scope — EXACTLY the two-element vector
  `[:trust-scope/private <project-key>]` `route/trust-scope-of` emits for any
  non-`:public` sensitivity, whose key is the KEYWORD `:mm.project/ident`
  (`:db.type/keyword` in schema; the `:project/UNASSIGNED` sentinel is a keyword
  too)?

  STRICT (fail-closed), because every downstream gate keys on this shape: the
  vector must be EXACTLY length 2 and its second element must be a keyword.  A
  junk-tailed `[:trust-scope/private :k :extra]` or a non-keyword key
  (`[:trust-scope/private \"k\"]`) is NOT a private scope — otherwise a malformed
  owner could resolve (`owner-scope-resolved?`), look up entries (`scope-entry`),
  and even satisfy `reachable?` against its own malformed twin.
  `route/trust-scope-of` only ever emits the exact shape, so this strictness
  rejects nothing legitimate."
  [trust-scope]
  (and (vector? trust-scope)
       (= 2 (count trust-scope))
       (= :trust-scope/private (first trust-scope))
       (keyword? (second trust-scope))))

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

;;; ===========================================================================
;;; DISCOVERY — the per-project file + the SANDBAR_STORE_REGISTRY env seam
;;; (the ruled read seam: docket C-8 confirm-only, adopted 2026-07-20)
;;; ===========================================================================

(def registry-env-var
  "The environment variable naming an EXPLICIT store-registry file path — the
  per-machine ops override (R2).  When set + non-blank it wins over the
  per-project default; it is an override seam ONLY, never a requirement."
  "SANDBAR_STORE_REGISTRY")

(def default-registry-relpath
  "The PER-PROJECT registry file location, relative to the directory the
  process is started FROM.  Fork-5's operating model (start the tool from the
  project's own directory) makes this THAT project's own registry — per-project
  + per-machine, never shared across projects (DEP-6).  Mirrors the committed
  template `etc/sandbar-store-registry.example.edn`."
  "etc/sandbar-store-registry.edn")

(defn registry-path
  "Resolve the store-registry file path for THIS process — the ruled read seam:

    1. `env-value` (the `SANDBAR_STORE_REGISTRY` env var) when set + non-blank
       — the explicit per-machine ops override; else
    2. `etc/sandbar-store-registry.edn` under `project-dir` — the PER-PROJECT
       default (fork-5: the process starts in the project's own directory, so
       this is that project's OWN registry).

  PURE in the 2-arity (both sources injected, so tests exercise the precedence
  without touching the process environment); the 0-arity reads the live env
  var + `user.dir`.  Resolution NEVER invents a registry — the resolved path
  may not exist, and `load-registry` then refuses loudly
  (`:registry-not-found`), which is the fail-closed contract."
  ([]
   (registry-path (System/getenv registry-env-var)
                  (System/getProperty "user.dir")))
  ([env-value project-dir]
   (if (and env-value (not (str/blank? env-value)))
     env-value
     (str (io/file project-dir default-registry-relpath)))))

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

(defn own-private-scope-key
  "The single `:mm.project/ident` key the registry's OWNER is entitled to carry a
  `:private` ENTRY for: the owner's own private-scope key when `:owner-scope` is
  `[:trust-scope/private k]`, or nil for a `:trust-scope/public` owner (which is
  entitled to NO private entry at all).  The air-gap admits a private entry keyed
  by this value ONLY — every other `:private` key is a FOREIGN scope's location
  the owning process has no business holding.  Requires a resolved owner scope
  (callers run `assert-owner-scope-resolved!` first)."
  [registry]
  (private-scope-key (owner-scope registry)))

(defn foreign-private-entries
  "The `[project-key entry]` pairs under `registry`'s `:private` map that the
  OWNER is NOT entitled to carry — every private entry whose key is not the
  owner's own private-scope key (`own-private-scope-key`).  This is the ONE
  predicate BOTH air-gap directions share:

    • PUBLIC owner (`own-private-scope-key` nil ⇒ allowed set `#{}`): EVERY
      private entry is foreign — a public process holds NO private location.
    • `[:trust-scope/private k]` owner (allowed set `#{k}`): every private entry
      keyed != k is foreign — alpha's registry discloses ONLY alpha's own
      location, never beta's (the private↔private separation, W1.deploy §1).

  Partitioned against the explicit allowed-key SET (not a bare `not=` on the
  owner key) so a pathological nil-keyed private entry is still foreign under a
  public owner.  Requires a resolved owner scope."
  [registry]
  (let [allowed (if-let [k (own-private-scope-key registry)] #{k} #{})]
    (for [[k entry] (private-entries registry)
          :when     (not (contains? allowed k))]
      [k entry])))

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
  AT MOST the `:public` slot's endpoint.

  This filter enforces reachability by SCOPE (a public owner ranges over the
  `:public` slot only); it deliberately does NOT inspect endpoint PORTS.  The
  A-1 property — a public session's surface never contains a PRIVATE endpoint —
  therefore holds over a VALIDATED registry, upheld by the two
  `validate-registry!` gates a bring-up runs BEFORE any connection opens, NOT by
  this filter dropping a same-scope endpoint on its own:
    • a private endpoint hand-edited into the `:private` map is a FOREIGN entry a
      public owner may not hold — refused by `assert-credential-air-gap!` (and
      dropped here anyway, since a public owner does not reach `:private` scopes);
      and
    • a private-PORT endpoint mislabeled into the `:public` slot IS reachable by
      scope, so this filter WOULD surface it — that is the forgeable hole, and it
      is refused by `assert-endpoint-port-doctrine!` (the reserved private port
      never sits in the public slot).
  So `(contains? (reachable-endpoints validated-pub-registry) <private-ep>)` is
  false because validation REFUSES the registry that would carry one; an
  UNVALIDATED forged map may still surface it, which is exactly why the port gate
  exists."
  [registry]
  (let [owner (owner-scope registry)]
    (into #{}
          (comp (filter (fn [[scope _]] (reachable? owner scope)))
                (keep    (fn [[_ entry]] (:transactor-endpoint entry))))
          (scope-entry-pairs registry))))

;;; ===========================================================================
;;; PORT DOCTRINE — the reserved private transactor port (D.3 port policy)
;;;   The second half of A-1's physical exclusion: `reachable?` filters by
;;;   SCOPE and the air-gap sweeps `:private` ENTRIES — neither inspects PORTS,
;;;   so a private-PORT endpoint mislabeled into the `:public` slot slips both.
;;; ===========================================================================

(def public-transactor-port
  "The ruled PUBLIC-bottom Datomic transactor port: the public scope owns 4334
  (transactor) + 4335 (h2 storage) (D.3 port policy / DEP-1; the shipped
  `etc/sandbar-store-registry.example.edn`).  The public bottom sits strictly
  BELOW the reserved private range."
  4334)

(def private-transactor-port-floor
  "The FLOOR of the RESERVED PRIVATE transactor port range.  Each private scope
  owns a NET-NEW transactor STRICTLY ABOVE the public 4334/4335 pair, in +2
  slots (4336/4337, 4338/4339, …), so a transactor port `>= 4336` belongs to a
  PRIVATE scope and a port `< 4336` (the public 4334/4335) does not.  This is
  the adopted-default private port the CA-6 lock rests on (FLEET 2026-07-10;
  example registry — \"4336 keeps the two scopes' storage from colliding\")."
  4336)

(defn endpoint-transactor-port
  "The integer TCP port in a `datomic:<proto>://HOST:PORT/…` transactor endpoint
  URL — where HOST is EITHER a plain host (`localhost`, `db.example.com`) OR a
  bracketed IPv6 literal (`[::1]`, `[2001:db8::1]`) — or nil when `endpoint` is
  not a string or carries no parseable `://HOST:PORT` segment.

  The host alternation `(?:\\[[^]]+\\]|[^/:]+)` is load-bearing: the bracketed
  branch is tried FIRST so a `datomic:dev://[::1]:4336/` endpoint's port is read
  from AFTER the closing `]`, not truncated at the first inner `:` of the IPv6
  literal.  The earlier `://[^/:]+:(\\d+)` form had ONLY the plain-host class,
  which stops at the first colon — so `[::1]:4336` matched no port and returned
  nil, and a private-PORT endpoint smuggled in as bracketed IPv6 slipped the
  port-doctrine gate (the round-4 A-1 hole).

  Returning nil means \"no port could be parsed\" — genuinely portless
  (`datomic:mem://scratch`), portless-dev (`datomic:dev://localhost/nodb`), a
  bracketed-IPv6 with no `:PORT` suffix, or an otherwise-unreadable string.  The
  DOCTRINE GATE treats a nil port on a PRESENT endpoint as fail-CLOSED
  (`slot-port-doctrine-violations`) — a slot whose port cannot be placed is
  refused, never skipped.  (A slot with NO endpoint at all is
  `resolve-endpoint`'s `:no-such-scope` concern, not this parser's.)  PURE."
  [endpoint]
  (when (string? endpoint)
    (some-> (re-find #"://(?:\[[^]]+\]|[^/:]+):(\d+)" endpoint) second parse-long)))

(defn private-port?
  "Is `port` in the RESERVED PRIVATE transactor range — an integer at or above
  `private-transactor-port-floor` (4336)?  A private scope's transactor lives
  here; the public bottom (4334/4335) is strictly below.  A nil / non-integer
  port is not private (fail-closed for callers that gate on it)."
  [port]
  (and (integer? port) (>= port private-transactor-port-floor)))

(defn slot-port-doctrine-violations
  "The seq of `{:slot :scope :endpoint :port :reason :expected}` findings naming
  every slot in `registry` whose transactor endpoint PORT contradicts its slot's
  scope class OR cannot be parsed at all.  `scope-entry-pairs` sweeps EVERY slot,
  so the check covers the `:public` slot AND each `:private` entry — the property
  the judge required to hold for ALL slots, not just `:private`.

  Each PRESENT endpoint is classified (`:reason`):
    • `:scope-class-mismatch` — the port parsed but sits in the wrong class:
        · a `:public` slot endpoint on a RESERVED PRIVATE port (>= 4336) — the
          FORGEABLE A-1 hole: `reachable?`/`reachable-endpoints` filter by SCOPE
          not port, so a private-port endpoint mislabeled into the public slot is
          reachable by a public owner and `resolve-endpoint` would hand it out; or
        · a `:private` slot endpoint on a PUBLIC port (< 4336, i.e. 4334/4335) —
          a private scope co-located on the public transactor, collapsing the
          one-transactor-per-scope topology (DEP-1).
    • `:unparseable-port` — the endpoint is PRESENT but no port can be parsed
      from it (a portless / bracketed-IPv6-without-port / otherwise-unreadable
      URL).  This fails CLOSED: a slot whose port cannot be PLACED is REFUSED,
      never skipped — the safe default that stops a form the parser cannot read
      (the round-4 bracketed-IPv6 bypass shape) from slipping the gate.

  A slot with NO `:transactor-endpoint` at all is the ONLY skip — a MISSING
  endpoint places no port in the doctrine and is `resolve-endpoint`'s
  `:no-such-scope` concern, not this gate's.  PURE — the raw finding list
  `assert-endpoint-port-doctrine!` refuses on."
  [registry]
  (for [[scope entry] (scope-entry-pairs registry)
        :let  [endpoint  (:transactor-endpoint entry)
               pub-slot? (public-scope? scope)]
        :when (some? endpoint)                    ; a MISSING endpoint ⇒ resolve-endpoint's concern
        :let  [port   (endpoint-transactor-port endpoint)
               reason (cond
                        (nil? port)                                    :unparseable-port
                        (if pub-slot? (private-port? port)
                                      (not (private-port? port)))      :scope-class-mismatch)]
        :when reason]                             ; a coherent, parseable port ⇒ no finding
    {:slot     (if pub-slot? :public :private)
     :scope    scope
     :endpoint endpoint
     :port     port
     :reason   reason
     :expected (str "a PARSEABLE "
                    (if pub-slot?
                      (str "PUBLIC-bottom transactor port below the private floor "
                           private-transactor-port-floor " (canonically "
                           public-transactor-port ")")
                      (str "RESERVED PRIVATE transactor port >= "
                           private-transactor-port-floor)))}))

(defn assert-endpoint-port-doctrine!
  "The LOUD refuse-to-serve realizing the PORT DOCTRINE — the physical lock that
  closes the FORGEABLE A-1 hole.  `reachable?` / `reachable-endpoints` filter
  reachability by SCOPE, not by port, and the credential air-gap sweeps
  `:private` ENTRIES only — so a private-PORT transactor endpoint hand-edited
  into the `:public` slot slips BOTH: it is reachable by a public owner, and
  `resolve-endpoint` would hand the public process the ruled-PRIVATE port
  (4336).  This gate is where that is caught.

  It checks SLOT/PORT COHERENCE across EVERY slot
  (`slot-port-doctrine-violations`), for BOTH plain-host and bracketed-IPv6
  endpoint forms (`endpoint-transactor-port` parses `[::1]:4336` correctly):
    • no `:public` slot endpoint on a RESERVED PRIVATE port (>= 4336) — refused
      REGARDLESS of owner, so neither a public owner (the forgery) nor a private
      owner can smuggle a private-port endpoint into the shared `:public` slot;
    • no `:private` slot endpoint on a PUBLIC port (4334/4335) — the reverse
      co-location (a private scope pointed at the public transactor, DEP-1); and
    • FAIL-CLOSED: no slot whose endpoint is PRESENT but carries no parseable
      port (`:unparseable-port`) — a port that cannot be placed is refused, never
      skipped, so a form the parser cannot read (the round-4 bracketed-IPv6
      bypass, or any malformed/portless URL hand-edited into a slot) can never
      slip the gate.  (A slot with NO endpoint at all is `resolve-endpoint`'s
      `:no-such-scope` concern, and is NOT refused here.)

  On any violation THROW `:sandbar/error :endpoint-port-doctrine-violation`
  (ex-data `:violations` naming each offending `{:slot :scope :endpoint :port
  :reason :expected}`, `:reason` ∈ `#{:scope-class-mismatch :unparseable-port}`);
  returns nil when every PRESENT slot endpoint carries a parseable port matching
  its scope class.

  This STRENGTHENS physical exclusion (two-lock collapse rule): wired into
  `validate-registry!`, it refuses the forged registry at bring-up, so a
  VALIDATED public registry's `reachable-endpoints` can never contain a
  private-port URL — the A-1 property the CA-6 deferral rests on.  PURE."
  [registry]
  (when-let [violations (seq (slot-port-doctrine-violations registry))]
    (throw (ex-info (str "store-registry transactor endpoint violates the port "
                         "doctrine — a port does not match its slot's scope class "
                         "(reserved private floor " private-transactor-port-floor
                         "); refusing at bring-up")
                    {:sandbar/error :endpoint-port-doctrine-violation
                     :violations    (vec violations)})))
  nil)

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
  DEP-3).  The ONE invariant, BOTH directions: a registry may carry a `:private`
  ENTRY only for its OWNER's own scope — never a FOREIGN scope's location.

    • a `:trust-scope/public` owner may carry NO `:private` entry at all (a
      public process holds no private location — the load-bearing public→private
      leg the CA-6 lock rests on); and
    • a `[:trust-scope/private k]` owner may carry EXACTLY its own `:proj/k`
      entry — ANY private entry keyed != k is a FOREIGN private disclosure and is
      refused (the private↔private separation, W1.deploy §1: alpha's registry
      names only alpha's location, never beta's; brief line 28 — \"separation
      holds across every public↔private AND private↔private boundary\").

  Refusal is ENTRY-LEVEL, not endpoint-only (strengthen-never-widen): the
  registry is location indirection, and a foreign scope's `:local-disk-path` /
  `:corpus-repo` handle is as much a disclosure as its transactor endpoint.  If
  ANY foreign private entry is present (`foreign-private-entries`) — even one
  bearing only a disk-path / repo handle — THROW `:sandbar/error
  :credential-air-gap-violation`.  ONE uniform marker for both legs (bring-up
  catches a single tag), disambiguated by ex-data: `:air-gap-direction` names
  which leg fired (`:public-owner-holds-private` |
  `:private-owner-holds-foreign-private`), `:private-scope-keys` names the
  refused FOREIGN keys, and `:with-transactor-endpoint` surfaces the sharpest
  exfil sub-case (which refused entries carried a LIVE transactor endpoint vs a
  bare handle).  Returns nil (proceed) when the gap holds.

  Preconditions on a RESOLVED owner scope: `assert-owner-scope-resolved!` is
  re-run here FIRST, so a malformed / nil owner is refused loudly with
  `:registry-owner-unresolved` rather than reaching the sweep (a nil owner would
  otherwise take the empty-allowed-set branch and refuse every private entry
  under a bogus reading).  Mirrors the `guard-registry-critical-write!`
  refuse-to-serve shape (`projection.clj:235`): a marker-tagged ex-info a
  bring-up must let abort the process, not swallow.

  ── the ONE retained, INTENDED asymmetry ────────────────────────────────────
  A `[:trust-scope/private k]` owner holding a `:public` transactor ENTRY is
  NOT refused here — this gate sweeps `:private` entries only.  That is
  DELIBERATE, not an undisclosed hole: public location handles (the shared-bottom
  corpus repo / transactor endpoint) are NOT secrets; R9 private-only is a
  RECOMMENDATION, not a hard rule; and a private→public LIVE connect is ALREADY
  refused at `resolve-endpoint` (`reachable?` false).  So the residual
  private-holds-public posture is permitted-but-unreachable — kept explicitly
  DISTINCT from the foreign-PRIVATE refusal above so the next reviewer does not
  rediscover it as a gap.

  This is the standing physical lock the CA-6 deferral rests on — it exists to
  STRENGTHEN, never weaken, physical exclusion (two-lock collapse rule)."
  [registry]
  (assert-owner-scope-resolved! registry)
  (when-let [foreign (seq (foreign-private-entries registry))]
    (let [public? (public-scope? (owner-scope registry))]
      (throw (ex-info (if public?
                        (str "public-scope process registry carries a private-"
                             "scope entry; refusing (credential air-gap — a "
                             "public process holds NO private location or endpoint)")
                        (str "private-scope process registry carries a FOREIGN "
                             "private-scope entry; refusing (credential air-gap — "
                             "a private process holds ONLY its own scope's "
                             "location, never another private scope's)"))
                      {:sandbar/error      :credential-air-gap-violation
                       :air-gap-direction  (if public?
                                             :public-owner-holds-private
                                             :private-owner-holds-foreign-private)
                       :owner-scope        (owner-scope registry)
                       :private-scope-keys (vec (map first foreign))
                       ;; The sharpest exfil sub-case, surfaced for the operator:
                       ;; which (if any) of the refused entries carried a LIVE
                       ;; transactor endpoint (vs a bare disk-path/repo handle).
                       :with-transactor-endpoint
                       (vec (for [[k entry] foreign
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
    1. `assert-owner-scope-resolved!`   — a registry that cannot name its own
       trust scope is refused FIRST (:registry-owner-unresolved), so no later
       gate silently no-ops on a malformed owner;
    2. `assert-credential-air-gap!`     — no FOREIGN private ENTRY: none at all
       under a public owner, and only the owner's own scope under a private
       owner (:credential-air-gap-violation, A-1/DEP-3 + private↔private §1);
    3. `assert-endpoint-port-doctrine!` — no slot carries a transactor port that
       contradicts its scope class: no RESERVED PRIVATE port (>= 4336) in the
       `:public` slot (the forgeable A-1 hole the scope-only air-gap misses), and
       no PUBLIC port in a `:private` slot; and — FAIL-CLOSED — no slot whose
       endpoint is present but carries no parseable port (parsed for plain-host
       AND bracketed-IPv6 forms; :endpoint-port-doctrine-violation);
    4. `assert-private-repo-distinct!`  — no private corpus routes to a public
       repo handle (:private-repo-collision, DEP-4).
  Intended to run at process bring-up, BEFORE any transactor connection opens."
  [registry]
  (assert-owner-scope-resolved!   registry)
  (assert-credential-air-gap!     registry)
  (assert-endpoint-port-doctrine! registry)
  (assert-private-repo-distinct!  registry)
  registry)

(defn bring-up-registry!
  "The ONE call a process bring-up makes at start — resolve the registry file
  (`registry-path`: the `SANDBAR_STORE_REGISTRY` env seam, else the
  per-project `etc/sandbar-store-registry.edn`), load it, and run EVERY
  refuse-to-serve gate (`validate-registry!`).  Returns the validated registry
  map the process then resolves endpoints through (`resolve-endpoint`); throws
  the first marker-tagged violation (`:registry-not-found` /
  `:registry-parse-error` / `:registry-owner-unresolved` /
  `:credential-air-gap-violation` / `:endpoint-port-doctrine-violation` /
  `:private-repo-collision`) — the caller MUST let that abort bring-up, never
  swallow it.

  This function realizes the A4 supervision ruling (2026-07-20; ns docstring
  SUPERVISION section): the supervisor IS the in-process component /
  bring-up script that calls this at process start — not a launchd-per-scope
  service, not OS-level separation.  Under the INTERIM single-JVM posture the
  one public-scope process calls it; when the multi-store fork forks a
  private scope, each per-scope process calls it over its own registry, same
  posture."
  ([] (bring-up-registry! (registry-path)))
  ([path]
   (validate-registry! (load-registry path))))
