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
    - REGEX (`re-matches`/`re-find`): INCLUDED.  Documented residual: there is
      NO `d/q` query timeout anywhere in this codebase (verified 2026-07-07), so
      a caller-crafted pathological pattern (ReDoS) against long stored strings
      is an UNBOUNDED-CPU availability residual.  This is strictly less severe
      than the integrity/exfiltration vector this validator closes (no data
      leaves, nothing is written).  Fast-follow: pair regex with an input-length
      cap / match budget, or drop regex if the residual is unacceptable.
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
;; forms (`str/starts-with?`) and bare-referred forms (`starts-with?` via a
;; :refer) compare EQUAL to their fully-qualified var after resolution.
;;
;; EXACT v1 membership (enumerated for audit — deny-by-default means anything
;; absent is rejected; the dangerous namespaces need NOT be blocklisted):
;;   Comparison / equality : =  not=  <  <=  >  >=
;;   String predicates     : clojure.string/{starts-with? ends-with?
;;                                           includes? blank?}
;;   Regex match           : re-matches  re-find   (ReDoS residual — see ns doc)
;;   Type / nil predicates : nil?  some?  string?  keyword?  number?  int?
;;                           boolean?  contains?
;;   Pure accessors        : get  count  nth
;;
;; DELIBERATELY ABSENT (dangerous or unneeded — rejected by deny-by-default):
;;   eval read-string read load load-string slurp spit deref future future-call
;;   pmap apply resolve requiring-resolve find-var  (indirection / side effects)
;;   clojure.java.shell/*  clojure.java.*  java.*  System/*  datomic.api/*
;;   +  -  *  first  identity  re-* beyond match/find  (candidates for
;;   fast-follow on demonstrated need, kept off the minimal v1 set)
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
    ;; regex match (pure — ReDoS availability residual documented in ns doc)
    #'clojure.core/re-matches  #'clojure.core/re-find
    ;; type / nil predicates
    #'clojure.core/nil?     #'clojure.core/some?    #'clojure.core/string?
    #'clojure.core/keyword? #'clojure.core/number?  #'clojure.core/int?
    #'clojure.core/boolean? #'clojure.core/contains?
    ;; pure accessors
    #'clojure.core/get  #'clojure.core/count  #'clojure.core/nth})

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
  Because the ONLY accept path is membership in `safe-query-op-allowlist`, a
  symbol that resolves to a non-allowlisted var (even a loaded dangerous one
  like `clojure.java.shell/sh`) is rejected all the same — resolution here is
  used ONLY to canonicalize identity, never to grant capability."
  [sym]
  (try
    (let [r (ns-resolve (the-ns 'sandbar.security.query) sym)]
      (when (var? r) r))
    (catch Throwable _ nil)))

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
                   :allowed-rules    (vec (sort registered-query-rules))})))

(defn- check-call-head
  "A call form `(head arg …)` was found (head is a symbol).  Accept iff head is
  a logic-grouping form (→ v1 reject with a distinct message), a registered
  rule, or resolves to an allowlisted var; otherwise reject loudly."
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
  call form found ANYWHERE in the tree — head position AND argument position —
  so laundering a forbidden symbol into an argument (e.g.
  `[(= ?a (clojure.java.shell/sh \"id\"))]`) is caught even though the head
  (`=`) is allowlisted.  Total and side-effect-free: it inspects and may throw,
  but NEVER resolves-and-invokes a named operator (naming `spit` must not call
  `spit`)."
  [where-clauses clause form]
  (cond
    ;; A call/rule form: a seq headed by a symbol → the only dangerous shape.
    (and (seq? form) (symbol? (first form)))
    (do
      (check-call-head where-clauses clause (first form))
      ;; Recurse into arguments to catch laundering in argument position.
      (doseq [arg (rest form)]
        (validate-form where-clauses clause arg)))

    ;; Any other sequential (data-pattern vector, binding vector, or a seq
    ;; whose head is a non-symbol literal): recurse into elements.  Contains no
    ;; invokable head of its own.
    (sequential? form)
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
