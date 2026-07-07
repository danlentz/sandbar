(ns sandbar.security.query
  "Read-plane query-layer security boundary — the ONE shared `sanitize-where`
  validator + curated operator allowlist for caller-supplied Datalog `:where`
  clauses.

  ## What this closes (AP-S3-6 vector A, query-time)

  The substrate's read-plane verbs (`sandbar.aggregate.count`,
  `sandbar.aggregate.group-by`, `sandbar.search.bm25f`) accept a caller-supplied
  `:where` clause vector that is spliced (`apply conj`) onto a base query and
  handed to `datomic.api/q`.  Datomic RESOLVES AND CALLS any function symbol
  named in a `:where` expression clause.  A read-only principal could therefore
  name `clojure.java.shell/sh`, `spit`, `slurp`, `System/getProperty`, or
  `deref`+`future` and drive arbitrary code execution / file write / host-state
  exfiltration through a verb the system advertises as read-only.  This was
  proven on an isolated `datomic:mem` DB earlier this arc.

  The parse-time half of the vector (a reader-eval trick that ran code before
  the query started) was closed live in commit c7d836c (`->where-clauses` now
  uses a data-only EDN reader; `*read-eval*` is disabled process-wide at
  `core.clj`).  THIS namespace closes the surviving query-time half: even a
  perfectly well-formed, innocent-looking clause vector only becomes dangerous
  when Datomic resolves a head symbol to a fn and invokes it, so we refuse to
  let any non-allowlisted head symbol reach `d/q`.

  ## The posture: deny-by-default

  `sanitize-where` walks the clause vector and returns it UNCHANGED iff every
  function/predicate call form it contains names a head symbol that is either
  (a) a member of the curated `safe-query-op-allowlist` (pure, total,
  side-effect-free operators), or (b) one of the substrate's own registered
  Datalog rules (`registered-query-rules`).  Anything else throws a LOUD
  `ex-info` — never silently drops a clause, never falls back to an unfiltered
  query.  Data-pattern triples (`[?e :some/slot value-or-?var]`) contain no call
  form and pass unconditionally.

  ## THE shared artifact (do not fork this list)

  `safe-query-op-allowlist` is the SINGLE SOURCE OF TRUTH for \"operators that
  untrusted-authored input may cause the server to invoke.\"  Per
  `libraries/codeact_programmatic_tool_calling_native_composition_surface_2026_07_06`:
  a fn unsafe to resolve in a `:where` clause is unsafe to call in a CodeAct
  composition step, and vice-versa — two lists WILL drift and one becomes the
  hole.  Future consumers MUST depend down onto THIS constant, never fork it:
    - the prospective path-grammar `:TEST`/`:FILTER` fn-name resolver
      (`sandbar.navigate.path.evaluate`, currently throws `unsupported-op`);
    - a future CodeAct sandbox's `allowed-ops` set (this set, or a DOCUMENTED
      superset that adds sandbox-only-safe ops — never a divergent list);
    - the prospective Layer-2 structured-JSON `:where` DSL compiler (emits only
      allowlisted operators).
  This namespace is a LEAF: it depends only on `clojure.string` (for alias-aware
  var resolution), so `db.datatype`, `search`, `navigate`, and a future
  `codeact` sandbox can all depend UP onto it with no dependency cycle.

  ## v1 conservative defaults (surfaced to the architect for ratification)

  Everything policy-shaped below is a named var/const so ratification is a
  one-line edit:
    - NESTED LOGIC forms (`or`/`and`/`not`/`or-join`/`not-join`): REJECT in v1
      (`logic-grouping-forms`).  No current legitimate `:where` writes them.
      TODO fast-follow: allowlist the grouping heads structurally and recurse
      into their sub-clauses instead of rejecting.
    - REGEX (`re-matches`/`re-find`): DROPPED from the v1 allowlist (fork
      decision, 2026-07-07).  There is NO `d/q` query timeout anywhere in this
      codebase (verified 2026-07-07) to backstop ReDoS, so deny-by-default must
      NOT open an unbounded-CPU operator with no backstop — and no current legit
      corpus `:where` needs regex.  TODO: re-add regex ONLY paired with an
      input-length cap / match budget (marked TODO at `safe-query-op-allowlist`).
      Architect may override to include-with-residual by restoring the two vars.
    - QUERY BUILT-INS (`missing?`/`get-else`/`ground`/`fulltext`/`tuple`/
      `untuple`): ALLOWED via a SEPARATE name-based passlist
      (`safe-query-builtin-forms`) — these are Datomic query special forms that
      resolve to NIL (verified 2026-07-07), NOT classpath vars, so they cannot
      live in the var-identity allowlist and carry zero RCE risk.
    - ALLOWLIST: MINIMAL-curated (see `safe-query-op-allowlist`).  `apply` is
      DELIBERATELY EXCLUDED (it would launder a forbidden symbol past a
      head-symbol check).  Extend only on demonstrated need — each addition is a
      security review, not a feature tweak.

  Design of record:
  `audit-results/xminus-build-2026-07-04/READPLANE-LAYER1-ALLOWLIST-DESIGN.md`
  + its F5 verification.  Governing ADR:
  `decisions/read_plane_trust_boundary_unified_safe_where_sublanguage_reopen_ap_s3_6_2026_07_06`."
  (:require [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The one allowlist — curated set of pure, total, side-effect-free operators
;; whose resolution+invocation by Datomic is safe for untrusted input to cause.
;;
;; Stored as a set of resolved VARS (not string names) so that alias-qualified
;; forms (`str/starts-with?`) and bare-referred forms (`starts-with?`) compare
;; EQUAL to their fully-qualified var after resolution (bare-referred string
;; predicates are canonicalized via `safe-op-bare-name-index`, see below).
;;
;; EXACT v1 membership (enumerated for audit — deny-by-default means anything
;; absent is rejected; the dangerous namespaces need NOT be blocklisted):
;;   Comparison / equality : =  not=  <  <=  >  >=
;;   String predicates     : clojure.string/{starts-with? ends-with?
;;                                           includes? blank?}
;;   Type / nil predicates : nil?  some?  string?  keyword?  number?  int?
;;                           boolean?  contains?
;;   Pure accessors        : get  count  nth
;;   (Datomic query built-ins missing?/get-else/ground/fulltext/tuple/untuple
;;    live in the SEPARATE `safe-query-builtin-forms` name passlist — they
;;    resolve to nil and cannot be var-identity members.)
;;
;; DELIBERATELY ABSENT (dangerous or unneeded — rejected by deny-by-default):
;;   eval read-string read load load-string slurp spit deref future future-call
;;   pmap apply resolve requiring-resolve find-var  (indirection / side effects)
;;   clojure.java.shell/*  clojure.java.*  java.*  System/*  datomic.api/*
;;   re-matches  re-find  (regex — DROPPED in v1, no d/q timeout to backstop
;;                         ReDoS; see ns doc + TODO below)
;;   +  -  *  first  identity  (candidates for fast-follow on demonstrated need,
;;                              kept off the minimal v1 set)
;;   or and not or-join not-join  (nested logic — REJECT in v1, see below)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def safe-query-op-allowlist
  "THE shared operator allowlist (set of resolved vars).  Single source of
  truth for read-plane `:where` sanitation AND every future consumer (path
  `:TEST` resolver, CodeAct sandbox, Layer-2 DSL compiler).  See ns docstring."
  #{;; comparison / equality
    #'clojure.core/=  #'clojure.core/not=
    #'clojure.core/<  #'clojure.core/<=  #'clojure.core/>  #'clojure.core/>=
    ;; string predicates
    #'clojure.string/starts-with?  #'clojure.string/ends-with?
    #'clojure.string/includes?     #'clojure.string/blank?
    ;; TODO(regex): re-matches / re-find were DROPPED from v1 (fork decision
    ;; 2026-07-07 — no d/q timeout to backstop ReDoS).  Re-add ONLY paired with
    ;; an input-length cap / match budget:
    ;;   #'clojure.core/re-matches  #'clojure.core/re-find
    ;; type / nil predicates
    #'clojure.core/nil?     #'clojure.core/some?    #'clojure.core/string?
    #'clojure.core/keyword? #'clojure.core/number?  #'clojure.core/int?
    #'clojure.core/boolean? #'clojure.core/contains?
    ;; pure accessors
    #'clojure.core/get  #'clojure.core/count  #'clojure.core/nth})

(def ^:private safe-op-bare-name-index
  "Simple-name → allowlisted-var index.  A `:where` may write a string predicate
  bare (`starts-with?`) rather than aliased (`str/starts-with?`); because
  `clojure.string` is `:as`-aliased (NOT `:refer`-ed) into this namespace, a
  bare `starts-with?` does not `ns-resolve` here and would be over-blocked.
  This index canonicalizes such a bare simple-symbol back to its allowlisted
  var.  DERIVED from `safe-query-op-allowlist`, so it can NEVER grant anything
  the allowlist does not already contain — it only recovers already-allowed
  operators written in bare form."
  (into {}
        (map (fn [v] [(symbol (name (symbol v))) v]))
        safe-query-op-allowlist))

(def safe-query-builtin-forms
  "Datomic query BUILT-IN / special forms (`missing?`, `get-else`, `ground`,
  `fulltext`, `tuple`, `untuple`).  Each RESOLVES TO NIL (verified 2026-07-07 —
  they are query-engine forms interpreted by Datomic, NOT classpath vars), so
  they carry ZERO RCE risk and CANNOT be members of the var-identity
  `safe-query-op-allowlist`.  Kept as a SEPARATE bare-symbol name passlist,
  mirroring the `registered-query-rules` pattern.  `missing?` was on the
  design-of-record allowlist and is restored here (it was silently dropped when
  the allowlist became var-identity-only).  Extend only on demonstrated need +
  verification that the new symbol truly resolves to nil / is a Datomic special
  form (never a classpath var)."
  '#{missing? get-else ground fulltext tuple untuple})

(def registered-query-rules
  "The substrate's own registered Datalog rule names (bare symbols, expanded
  against the rulebase — NOT classpath vars, so they cannot reach arbitrary
  code).  A rule invocation `(instance-of ?x ?e)` is a list headed by one of
  these.  Callers never need to write them (the base query at each splice site
  already supplies `(instance-of ?class ?e)`), so this is defensive
  completeness.  Kept as a separate small constant per design §3.5 Option A.
  Source: `sandbar.db.datatype` `defrule instance-of` / `direct-instance-of`."
  '#{instance-of direct-instance-of})

(def logic-grouping-forms
  "Datalog logic-grouping heads.  v1 CONSERVATIVE DEFAULT: REJECT any clause
  containing one of these (loud, with a distinct message).  No current
  legitimate `:where` uses them.  TODO fast-follow: allowlist these as
  structural forms and recurse into their sub-clauses (which must themselves
  pass `sanitize-where`) rather than rejecting.  Named const so the
  reject-vs-recurse ratification is a one-line change."
  '#{or and not or-join not-join})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read-plane NAMESPACE FIREWALL (RPAF) — deny-by-default class/attribute/entity
;; read-scope, ORTHOGONAL to the call-form allowlist above.
;;
;; The call-form allowlist closes the query-time RCE (a `:where` cannot RESOLVE
;; a dangerous fn).  It does NOT scope WHICH classes/attributes a read-plane
;; caller may touch: the aggregate/search/count/group-by/class.instances verbs
;; impose no read-scope authz, so `group-by :auth/ServiceAccount
;; :auth/api-key-hash` dumps credential hashes verbatim and a `:where [[?x
;; :auth/api-key-hash ?h] [(starts-with? ?h P)]]` is a char-by-char existence
;; ORACLE (unjoined ?x ⇒ global predicate).  This firewall denies the read plane
;; any namespace outside the corpus + metamodel.  Deny-by-default (allow-list),
;; mirroring the call-form gate.  Governing ADR:
;; decisions/read_plane_namespace_firewall_deny_by_default_closes_auth_exfil_...
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def read-plane-namespace-allowlist
  "First-segment namespace prefixes the read plane may touch (matched on the
  keyword namespace's FIRST dotted segment).  DENY-BY-DEFAULT: a `:class`, a
  `:group-by`/`:attribute` slot, a `:where` attribute, or a RETURNED entity
  whose class namespace's first segment is NOT in this set is refused loudly.
  Covers the ENTIRE memory model + metamodel; EXCLUDES substrate-internal /
  secret / operational namespaces — :auth/* (credentials), :user/* (secrets),
  :audit/* (data snapshots), :event/:http/:api/:tx (runtime), :job/* (scheduler
  internals), :model/:twit (demo), :context/:workflow/:fn (substrate).
    mm    — memory model    (:mm/*, :mm.memory/*, :mm.tag/*, :mm.session/* …)
    dt    — metamodel       (:dt/Class, :dt/Property, :dt.fn/* …)
    db    — structural      (:db/ident, :db/valueType, :db.type/* …)
    value — value-type refs (:value/* …)
  Extend ONLY on a demonstrated legitimate read-plane need + review — each
  addition widens the exfiltration surface.  Single source of truth."
  #{"mm" "dt" "db" "value"})

(defn- ns-first-segment
  "First dotted segment of `kw`'s namespace, or nil if `kw` is not a namespaced
  keyword.  :mm.memory/name -> \"mm\"; :auth/api-key-hash -> \"auth\"."
  [kw]
  (when (and (keyword? kw) (namespace kw))
    (first (str/split (namespace kw) #"\."))))

(defn read-plane-namespace-allowed?
  "True iff `kw` is safe for the read plane: a namespaced keyword whose FIRST
  namespace segment is on `read-plane-namespace-allowlist`, OR a keyword with NO
  namespace (a bare value like `:decision` — it names no class/attribute
  surface, so it cannot select firewalled data).  Non-keywords are allowed
  (only keywords name the class/attribute surface)."
  [kw]
  (if (and (keyword? kw) (namespace kw))
    (contains? read-plane-namespace-allowlist (ns-first-segment kw))
    true))

(defn- reject-read-plane!
  [kind offending context]
  (throw (ex-info
           (str "Rejected read-plane " (name kind) " '" offending "' — its "
                "namespace is firewalled off the read plane.  The read plane "
                "exposes only the corpus + metamodel namespaces ("
                (str/join " / " (sort read-plane-namespace-allowlist))
                "); substrate-internal namespaces (:auth/*, :user/*, :audit/*, "
                "runtime/scheduler/demo) are denied by default.  See "
                "sandbar.security.query/read-plane-namespace-allowlist.")
           {:sanitizer 'sandbar.security.query/read-plane-firewall
            :reason    :namespace-not-read-plane-allowed
            :kind      kind
            :offending offending
            :context   context
            :allowed   (vec (sort read-plane-namespace-allowlist))})))

(defn assert-class-allowed!
  "Read-plane guard for a class-scoped read verb (aggregate.count/group-by/
  rank-by, search.bm25f, class.instances).  Throws loud ex-info if
  `class-ident`'s namespace is firewalled.  Returns `class-ident` (threadable).
  Class-agnostic; the caller supplies the class."
  [class-ident]
  (when-not (read-plane-namespace-allowed? class-ident)
    (reject-read-plane! :class class-ident nil))
  class-ident)

(defn assert-attribute-allowed!
  "Read-plane guard for an attribute/slot argument (search.attribute
  `:attribute`, aggregate.group-by `:group-by`).  Throws if `attr-ident`'s
  namespace is firewalled.  Returns `attr-ident`."
  [attr-ident]
  (when-not (read-plane-namespace-allowed? attr-ident)
    (reject-read-plane! :attribute attr-ident nil))
  attr-ident)

(def read-plane-ident-allowlist
  "Namespace first-segments allowed for an entity's OWN `:db/ident`.  Superset of
  `read-plane-namespace-allowlist` by the corpus 'memory' prefix — corpus
  memorials are interned as `:memory.<dir>/<slug>`.  Distinct from the
  class/attribute allow-list because it must ALSO catch a firewalled
  CLASS/PROPERTY-ident entity whose class is allowed but whose ident is not:
  e.g. `class.instances :dt/Class` returns the `:auth/User` class entity, whose
  `:dt/type` is `:dt/Class` (allowed) but whose `:db/ident` is `:auth/User`
  (firewalled)."
  (conj read-plane-namespace-allowlist "memory"))

(defn read-plane-entity-visible?
  "True iff an entity may be surfaced on the read plane: BOTH its class
  (`:dt/type`) namespace AND its own ident (`:db/ident`) namespace are
  non-firewalled.  `:dt/type` on a raw Datomic EntityMap is the class as an
  EntityMap (which `map?` returns FALSE for — the F-M-003 trap), so resolve it
  via ILookup `(:db/ident t)`.  A nil class / nil ident is allowed (nothing to
  leak on that axis).  Works on BOTH raw Datomic entities and projected maps."
  [entity]
  (let [t   (:dt/type entity)
        cls (cond (keyword? t) t
                  (some? t)    (:db/ident t)
                  :else        nil)
        id  (:db/ident entity)]
    (and (or (nil? cls) (read-plane-namespace-allowed? cls))
         (or (nil? id)  (contains? read-plane-ident-allowlist (ns-first-segment id))))))

(defn assert-entity-allowed!
  "Read-plane guard for a RETURNED / anchor entity (entity.find / navigate /
  library-card / path-via / siblings / resolve).  Refuses an entity whose class
  (`:dt/type`) OR own ident (`:db/ident`) namespace is firewalled — the latter
  catches a firewalled CLASS ident used as a nav anchor (`:auth/ServiceAccount`,
  whose `:dt/type :dt/Class` is allowed but whose ident is not).  A nil /
  typeless / non-interned entity passes.  Returns `entity`."
  [entity]
  (when-not (read-plane-entity-visible? entity)
    (reject-read-plane! :entity (or (:db/ident entity) (:dt/type entity))
                        {:db/id (:db/id entity)}))
  entity)

(def read-plane-redaction-marker
  "Placeholder returned in place of a firewalled entity in a read-plane
  projection — carries NO id / ident / class / slot (no value, no enumeration
  ident, no class name).  A residual count-of-markers is a weak structural
  oracle only; the ENTRY guards + `assert-entity-allowed!` refuse the highest-
  leverage firewalled selectors/anchors before a collection is even built."
  {:mm/redacted "read-plane-firewalled"})

(defn read-plane-scrub-projection
  "Read-plane OUTPUT firewall for an ALREADY-PROJECTED entity map — the EXIT
  surface complement to the entry guards.  Firewalled entity (by class OR ident)
  → `read-plane-redaction-marker`.  Visible entity → itself with any
  firewalled-namespace SLOT dissoc'd (an allowed-class entity may still carry
  firewalled slots, e.g. an `:mm/Event` with `:http/*` fields).  nil → nil.
  Total + idempotent; the projection layer applies it to EVERY projected entity
  (top-level AND nested refs) so every entity-returning read verb — navigate /
  library-card / rank-by / class.instances / path-via / siblings / search — is
  sanitized by construction (the F5 shared-splice-site discipline, one layer
  out)."
  [projected]
  (when projected
    (if (read-plane-entity-visible? projected)
      (into {} (remove (fn [[k _]] (and (keyword? k) (namespace k)
                                        (not (read-plane-namespace-allowed? k)))))
            projected)
      read-plane-redaction-marker)))

(defn- reject-denied-where-keywords!
  "Recursively reject any firewalled-namespace keyword in `form` (an expression
  clause or sub-form).  In a `:where` call form a namespaced keyword names an
  attribute / class / value to select on — a firewalled one is an exfiltration
  probe (e.g. `[(missing? $ ?e :auth/api-key-hash)]`)."
  [where clause form]
  (cond
    (keyword? form)
    (when-not (read-plane-namespace-allowed? form)
      (reject-read-plane! :where-namespace form clause))
    (map? form)  (doseq [[k v] form]
                   (reject-denied-where-keywords! where clause k)
                   (reject-denied-where-keywords! where clause v))
    (coll? form) (doseq [x form] (reject-denied-where-keywords! where clause x))
    :else nil))

(defn assert-where-namespaces!
  "Read-plane NAMESPACE guard over the parsed `:where` clause vector (the
  companion to `sanitize-where`'s call-form guard).  Rejects ANY
  firewalled-namespace keyword ANYWHERE in ANY clause — attribute position (the
  `[?x :auth/api-key-hash ?h]` oracle), VALUE position (the `[?x :dt/type
  :auth/User]` existence oracle — unjoined, a satisfiable firewalled-value clause
  leaks whole-population existence), and expression/builtin args
  (`[(missing? $ ?e :auth/api-key-hash)]`).  No legitimate corpus `:where` names
  a firewalled namespace in ANY position (real values are bare keywords like
  `:decision` or corpus/metamodel idents like `:mm/Decision`), so this is
  deny-by-default with no legit over-block.  Throws loud ex-info; `nil`/`[]`
  no-op.  Returns `where-clauses`.  Called by the read-plane WRAPPERS
  (aggregate/search) — NOT by `sanitize-where` — so internal
  `count-of`/`group-by-of` callers keep the RCE guard without the read-scope
  firewall."
  [where-clauses]
  (when (seq where-clauses)
    (doseq [clause where-clauses]
      (reject-denied-where-keywords! where-clauses clause clause)))
  where-clauses)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; The one validator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-head-var
  "Resolve `sym` to its Var, canonicalizing identity.  Uses `ns-resolve`
  against THIS namespace explicitly (NOT bare `resolve`, whose one-arg form
  keys off the runtime `*ns*` and would miss our aliases) so that this
  namespace's mappings apply deterministically — in particular the
  `clojure.string :as str` alias, so `str/starts-with?` resolves to the same
  var as the fully-qualified form.  Returns the Var or nil.  Returns nil (never
  throws, never invokes) for:
    - symbols whose namespace is not loaded / does not exist;
    - host-interop forms (`System/getProperty` resolves to a Class or throws);
    - anything that does not resolve to a Var.
  For a bare simple-symbol that does not resolve here (notably the
  `clojure.string` predicates, aliased `:as str` but not `:refer`-ed), falls
  back to `safe-op-bare-name-index` — a name→var map DERIVED FROM the allowlist,
  so the fallback can only recover an already-allowlisted operator written bare,
  never grant a new capability.

  Because the ONLY accept path is membership in `safe-query-op-allowlist`, a
  symbol that resolves to a non-allowlisted var (even a loaded dangerous one
  like `clojure.java.shell/sh`) is rejected all the same — resolution here is
  used ONLY to canonicalize identity, never to grant capability."
  [sym]
  (or
    (try
      (let [r (ns-resolve (the-ns 'sandbar.security.query) sym)]
        (when (var? r) r))
      (catch Throwable _ nil))
    ;; Bare-referred fallback (e.g. `starts-with?` → clojure.string/starts-with?).
    (when (simple-symbol? sym)
      (get safe-op-bare-name-index sym))))

(defn- reject!
  [where-clauses clause offending-symbol reason-kw message]
  (throw (ex-info message
                  {:sanitizer        'sandbar.security.query/sanitize-where
                   :reason           reason-kw
                   :rejected-clause  clause
                   :offending-symbol offending-symbol
                   :where            where-clauses
                   :allowed          (->> safe-query-op-allowlist
                                          (map symbol)
                                          sort
                                          vec)
                   :allowed-rules    (vec (sort registered-query-rules))
                   :allowed-builtins (vec (sort safe-query-builtin-forms))})))

(defn- check-call-head
  "A call form `(head arg …)` was found (head is a symbol).  Accept iff head is
  a logic-grouping form (→ v1 reject with a distinct message), a registered
  rule, a Datomic query built-in (`safe-query-builtin-forms`), or resolves to an
  allowlisted var; otherwise reject loudly."
  [where-clauses clause head]
  (cond
    (contains? logic-grouping-forms head)
    (reject! where-clauses clause head :nested-logic-unsupported-v1
             (str "Rejected :where clause — nested logic form '" head
                  "' is not supported in v1 (conservative default). "
                  "or/and/not/or-join/not-join are deferred to a recursion "
                  "fast-follow. See sandbar.security.query/logic-grouping-forms."))

    (contains? registered-query-rules head)
    :accept-rule

    (contains? safe-query-builtin-forms head)
    :accept-builtin

    (contains? safe-query-op-allowlist (resolve-head-var head))
    :accept-fn

    :else
    (reject! where-clauses clause head :operator-not-allowlisted
             (str "Rejected :where clause — operator '" head "' is not on the "
                  "safe query-operator allowlist. A read-plane :where filter may "
                  "only use data-pattern triples and a curated set of pure, "
                  "side-effect-free operators. See "
                  "sandbar.security.query/safe-query-op-allowlist."))))

(defn- validate-form
  "Recursively validate one clause or sub-form.  Deny-by-default over EVERY
  call form found ANYWHERE in the tree — head position AND argument position, and
  inside ANY nested collection (list, vector, MAP key/val, SET member, tagged
  literal, and any value's metadata) — so laundering a forbidden symbol into an
  argument (e.g. `[(= ?a (clojure.java.shell/sh \"id\"))]`) OR into a map/set
  literal under an allowlisted head (e.g. `[(= ?n {:k (…/sh \"touch\" X)})]`,
  `[(= ?n #{(…/sh \"id\")})]`) is caught.  Clojure maps and sets are neither
  `seq?` nor `sequential?`, so a `(coll? form)` walk is REQUIRED for the stated
  invariant to hold.  Total and side-effect-free: it inspects and may throw, but
  NEVER resolves-and-invokes a named operator (naming `spit` must not call
  `spit`)."
  [where-clauses clause form]
  ;; Metadata can itself carry a call form (`^{:k (…/sh)} x`) — walk it first.
  ;; `meta` is nil-safe for values that cannot hold metadata.
  (when-let [m (meta form)]
    (validate-form where-clauses clause m))
  (cond
    ;; A call/rule form: a seq headed by a symbol → the only invokable shape.
    (and (seq? form) (symbol? (first form)))
    (do
      (check-call-head where-clauses clause (first form))
      ;; Recurse into arguments to catch laundering in argument position.
      (doseq [arg (rest form)]
        (validate-form where-clauses clause arg)))

    ;; Tagged literal (data-reader output for an unknown tag under the data-only
    ;; EDN reader): the payload hides in its `:form`.  Walk it.
    (tagged-literal? form)
    (validate-form where-clauses clause (:form form))

    ;; MAP literal: walk BOTH keys AND vals (a forbidden call can hide in
    ;; either).  Maps are not sequential?, so this branch is load-bearing.
    (map? form)
    (doseq [[k v] form]
      (validate-form where-clauses clause k)
      (validate-form where-clauses clause v))

    ;; ANY other collection — vector (data-pattern / binding), SET literal, or a
    ;; seq whose head is a non-symbol literal: walk every child.  `coll?` is the
    ;; general net that catches set literals (also not sequential?) and anything
    ;; else nested, so no call form can escape the walk.
    (coll? form)
    (doseq [x form]
      (validate-form where-clauses clause x))

    ;; Scalar (logic var symbol, keyword, string, number, nil, …): safe — it
    ;; cannot cause a resolution.
    :else nil))

(defn sanitize-where
  "THE read-plane query-layer gate.  Takes the already-EDN-parsed `:where`
  clause vector and returns it UNCHANGED iff every call form it contains is
  safe; otherwise throws a loud `ex-info` (structured `ex-data` carries
  `:reason`, `:offending-symbol`, `:rejected-clause`, `:allowed`).  Pure,
  total, side-effect-free; does not touch the database.

  Called INSIDE the three splice-site primitives (`db.datatype/count-of`,
  `db.datatype/group-by-of`, `search/where-matching-eids`) BEFORE the
  `apply conj` splice — so MCP, REST, and in-process callers are all covered by
  construction.  `nil`/`[]` return as-is (no clauses to check).

  Fail-closed contract: one bad clause rejects the WHOLE `:where` (no partial
  acceptance — do not let an attacker probe which symbols pass)."
  [where-clauses]
  (when (seq where-clauses)
    (doseq [clause where-clauses]
      (validate-form where-clauses clause clause)))
  where-clauses)
