(ns sandbar.navigate.path.datomic
  "Sandbar Path-Grammar — Canonical-8 Datomic Compiler (Stage P-3 of
  comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  Compiles a canonical path-grammar AST (output of P-2 IR) to a Datomic
  query fragment — a `:where` clause vec + a `:rules` recursive-rule
  vec — that, when run starting from a seed entity, yields the set of
  reachable entities under the path semantics.

  Per-operator compilation strategy follows
  syntheses/sandbar_path_grammar_substrate_design_research_2026_05_13.md
  §3.1.  This file ships the Canonical-8 (Tier-1) operators:

    :SEQ       — sequence composition (relation composition)
    :OR        — alternation (Datomic or-join)
    :REP+      — transitive closure (recursive rule)
    :REP*      — reflexive-transitive closure (recursive rule + identity)
    :INV       — inverse (compile-time variable swap)
    :SELF      — identity step ([(identity ?from) ?to] binding)
    :ANY       — wildcard predicate (predicate-position variable)
    :RESTRICT  — specific-node restriction (literal attribute clause)

  Plus the atomic predicate primitive `:PREDICATE` (which the parser
  produces for any bare keyword that's not a registered operator).

  Tier-2 (:NOT / :OPT / :REP / :FILTER / :TEST) and Tier-3 operators
  are not compiled here; P-4 adds them.

  Substrate-quality preserved: class-agnostic; no hardcoded slot
  knowledge; pure data → data (no DB queries during compilation).

  ## Output shape

    {:where [<clause> <clause> ...]
     :rules [[<rule-head-clause> <body-clauses>...] ...]}

  Splice into a Datalog query via:

    (let [{:keys [where rules]} (compile ast '?start '?end)
          q (vec (concat '[:find ?end :in $ % ?start :where] where))]
      (d/q q (db) rules start-eid))"
  (:refer-clojure :exclude [compile])
  (:require [clojure.string :as str]
            [sandbar.navigate.path.ast :as ast]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fresh-variable generation — stable per-compile counter via atom.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- new-fresh-ctr
  "Create a fresh-variable counter.  Compiled expressions thread this
   through so intermediate-variable names are unique-within-compile +
   stable-across-test-runs (no gensym)."
  []
  (atom 0))

(defn- fresh-var
  "Generate a fresh intermediate variable like `?int-1`, `?int-2`."
  [ctr]
  (let [n (swap! ctr inc)]
    (symbol (str "?int-" n))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Rule-name mangling — derive deterministic rule names from sub-AST
;; content.  Stable across compile runs (testable) + unique across
;; distinct sub-expressions (avoids rule collision).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- name-safe
  "Convert a keyword to a name-safe string fragment (alphanum + dashes)."
  [kw]
  (-> (str (symbol kw))
      (str/replace #"[^A-Za-z0-9\-]" "-")))

(defn- ast-suffix
  "Derive a name-safe suffix encoding an AST's structure.  Used to mint
   deterministic recursive-rule names."
  [ast]
  (case (:op ast)
    :PREDICATE (name-safe (:predicate ast))
    :SEQ       (str "seq-" (str/join "-and-" (map ast-suffix (:args ast))))
    :OR        (str "or-" (str/join "-or-" (map ast-suffix (:args ast))))
    :REP+      (str "plus-" (ast-suffix (first (:args ast))))
    :REP*      (str "star-" (ast-suffix (first (:args ast))))
    :INV       (str "inv-" (ast-suffix (first (:args ast))))
    :SELF      "self"
    :ANY       "any"
    :RESTRICT  (str "restrict-"
                    (name-safe (first (:target ast)))
                    "-"
                    (name-safe (second (:target ast))))))

(defn- rule-name
  "Mint a deterministic recursive-rule symbol from a child AST + tag."
  [tag child-ast]
  (symbol (str tag "-" (ast-suffix child-ast))))

(defn- dedupe-rules
  "Dedupe a sequence of compiled rules by full-structure equality.

   `rule-name` is content-derived (via `ast-suffix`) — same child AST
   produces the same rule symbol — so two rules with identical
   structure are functionally identical; emitting both causes Datomic
   to reject the query as duplicate-rule-definition.

   Phase U Stage U-6 (UR-11) fix: nested `(:REP+ ...)` / `(:REP* ...)`
   compositions whose children contain repeated subterms (e.g.,
   `(:REP+ (:SEQ (:REP+ a) b))` or
   `(:OR (:REP+ a) (:REP+ b))` when both branches share predicates)
   used to emit the same rule twice through different traversal
   paths.  `(vec (distinct rules))` collapses to one copy."
  [rules]
  (vec (distinct rules)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-operator compilation.
;;
;; Each helper takes the AST node + `from-var` + `to-var` + a counter,
;; returns `{:where [...] :rules [...]}`.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare compile-node)

(defn- compile-predicate
  "(:PREDICATE :p) from ?x to ?y → [?x :p ?y]"
  [ast from-var to-var _ctr]
  {:where [[from-var (:predicate ast) to-var]]
   :rules []})

(defn- compile-self
  ":SELF from ?x to ?y → bind ?y = ?x via [(identity ?x) ?y]"
  [_ast from-var to-var _ctr]
  {:where [[(list 'identity from-var) to-var]]
   :rules []})

(defn- compile-any
  ":ANY from ?x to ?y → [?x ?_ ?y]  (variable in predicate position)
   Note: planner cannot use predicate-specific indexes; full EAVT scan
   bounded by ?x cardinality.  Document the cost; allow but don't
   optimize."
  [_ast from-var to-var ctr]
  (let [pred-var (fresh-var ctr)]
    {:where [[from-var pred-var to-var]]
     :rules []}))

(defn- compile-restrict
  "(:RESTRICT [pred value]) at the current walk-position — adds a
  constraint at `from-var` (the entity we walked to) and binds
  `to-var = from-var` (RESTRICT is a filter, not a step; it doesn't
  move the walk position).

  Compilation:
    [from-var pred value]            ; constraint on the current node
    [(identity from-var) to-var]     ; bind to-var = from-var

  The target is a [predicate value] tuple — pred is an attribute
  ident (keyword), value is a literal value (keyword / number /
  string)."
  [ast from-var to-var _ctr]
  (let [[pred value] (:target ast)]
    {:where [[from-var pred value]
             [(list 'identity from-var) to-var]]
     :rules []}))

(defn- compile-seq
  "(:SEQ p1 p2 ... pN) from ?x to ?z → chain via fresh intermediates"
  [ast from-var to-var ctr]
  (let [args   (:args ast)
        n      (count args)
        pairs  (loop [i 0
                      cur from-var
                      acc []]
                 (if (= i n)
                   acc
                   (let [nxt (if (= i (dec n))
                               to-var
                               (fresh-var ctr))
                         res (compile-node (nth args i) cur nxt ctr)]
                     (recur (inc i) nxt (conj acc res)))))]
    {:where (vec (mapcat :where pairs))
     ;; UR-11 (Phase U Stage U-6): repeated sub-terms across SEQ
     ;; positions emit identical rules; dedupe.
     :rules (dedupe-rules (mapcat :rules pairs))}))

(defn- compile-or
  "(:OR p1 p2 ... pN) from ?x to ?y → (or-join [?x ?y] <branch> ...)
   Each branch shares the ?x and ?y vars; intermediate vars local to
   each branch."
  [ast from-var to-var ctr]
  (let [branches (mapv (fn [child]
                         (compile-node child from-var to-var ctr))
                       (:args ast))
        ;; UR-11 (Phase U Stage U-6): branches with shared sub-rules
        ;; (e.g., (:OR (:REP+ p) (:REP+ q)) where both branches emit
        ;; the same recursive rule) emit duplicates pre-dedupe.
        rules    (dedupe-rules (mapcat :rules branches))
        ;; Each branch's :where becomes one clause-list inside or-join.
        ;; If a branch produces multiple clauses, wrap in (and ...).
        wrap-branch (fn [clauses]
                      (if (= 1 (count clauses))
                        (first clauses)
                        (cons 'and clauses)))
        or-clause (cons 'or-join
                        (cons [from-var to-var]
                              (map (comp wrap-branch :where) branches)))]
    {:where [or-clause]
     :rules rules}))

(defn- compile-rep+
  "(:REP+ p) from ?x to ?y → recursive rule:
     [(p-trans ?x ?y) <body>]
     [(p-trans ?x ?y) <body-to-?z> (p-trans ?z ?y)]"
  [ast from-var to-var ctr]
  (let [child  (first (:args ast))
        rname  (rule-name "plus" child)
        ;; Base case: one application of child from ?x to ?y
        base-ctr  (new-fresh-ctr)
        base-comp (compile-node child '?rep-from '?rep-to base-ctr)
        ;; Recursive case: one application of child from ?x to ?z, then
        ;; recurse from ?z to ?y
        rec-ctr   (new-fresh-ctr)
        rec-mid   '?rep-mid
        step-comp (compile-node child '?rep-from rec-mid rec-ctr)
        base-rule (into [(list rname '?rep-from '?rep-to)]
                        (:where base-comp))
        rec-rule  (into [(list rname '?rep-from '?rep-to)]
                        (concat (:where step-comp)
                                [(list rname rec-mid '?rep-to)]))
        ;; UR-11 (Phase U Stage U-6): base-comp + step-comp both
        ;; compile the same child AST and emit identical sub-rules;
        ;; dedupe to avoid Datomic duplicate-rule rejection.
        child-rules (dedupe-rules (concat (:rules base-comp) (:rules step-comp)))]
    {:where [(list rname from-var to-var)]
     :rules (into [base-rule rec-rule] child-rules)}))

(defn- compile-rep*
  "(:REP* p) from ?x to ?y → recursive rule with identity base:
     [(p-star ?x ?x)]                            ; identity / 0 applications
     [(p-star ?x ?y) <body-from-?x-to-?z>
                     (p-star ?z ?y)]             ; 1+ applications"
  [ast from-var to-var ctr]
  (let [child   (first (:args ast))
        rname   (rule-name "star" child)
        rec-ctr (new-fresh-ctr)
        rec-mid '?rep-mid
        step    (compile-node child '?rep-from rec-mid rec-ctr)
        ;; Identity-base rule: head takes two distinct variables, body
        ;; binds them to be equal via [(identity ?from) ?to].  Datomic
        ;; rejects empty-body rules ("Can't pop empty vector") AND chokes
        ;; on `(= ?x ?x)` (Index 1 out of bounds — internal optimizer
        ;; degenerates the clause).  identity-bind is the same shape
        ;; :SELF uses; the binding propagates ?rep-to ← ?rep-from.
        id-rule [(list rname '?rep-from '?rep-to)
                 [(list 'identity '?rep-from) '?rep-to]]
        rec-rule (into [(list rname '?rep-from '?rep-to)]
                       (concat (:where step)
                               [(list rname rec-mid '?rep-to)]))]
    {:where [(list rname from-var to-var)]
     ;; UR-11 (Phase U Stage U-6): dedupe nested rules surfaced by
     ;; the step compile (composite children with repeated subterms).
     :rules (into [id-rule rec-rule] (dedupe-rules (:rules step)))}))

(defn- compile-inv
  "(:INV p) from ?x to ?y → compile p from ?y to ?x (swap variables).

   :INV is a compile-time meta-operation — it doesn't generate code,
   it just swaps the variable roles before delegating to the child's
   compilation.  Semantically correct for any sub-expression: if p
   denotes (x,y) tuples, (:INV p) denotes (y,x).

   Datomic's reverse-attribute syntax `:_attr` is a notational variant
   yielding equivalent results (VAET-indexed); not used here to keep
   the compiler uniform.  Optimization opportunity flagged for later
   profiling-driven refinement."
  [ast from-var to-var ctr]
  (compile-node (first (:args ast)) to-var from-var ctr))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tier-2 operators (Stage P-4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- atomic-or-inv-atomic?
  "True if `node` is an atomic predicate AST or :INV-wrapping one.
  :NOT can only operate over atomic property sets (SPARQL parity:
  !p / !(p1|p2) / !^p)."
  [node]
  (or (= :PREDICATE (:op node))
      (and (= :INV (:op node))
           (= :PREDICATE (:op (first (:args node)))))))

(defn- compile-not
  "(:NOT p1 p2 ... pN) from ?x to ?y where each pᵢ is an atomic
  predicate (or :INV of an atomic predicate) → variable in predicate
  position + not= constraints:

    [?x ?p ?y]
    [(not= ?p :p1)]
    [(not= ?p :p2)]
    ...

  Performance: variable in predicate position (same as :ANY) requires
  EAVT scan; bound by ?x cardinality.  Document the cost.

  Compilation refuses non-atomic children — SPARQL/Wilbur semantics
  define :NOT only over property sets."
  [ast from-var to-var ctr]
  (doseq [arg (:args ast)]
    (when-not (atomic-or-inv-atomic? arg)
      (throw (ex-info
               (str ":NOT children must be atomic predicates "
                    "(or :INV of an atomic predicate); got " (:op arg))
               {:received-arg arg}))))
  (let [pred-var (fresh-var ctr)
        ;; Inverse atomic predicates need swapped from/to in the base clause
        ;; — but :NOT is a property-set negation, so we treat all args
        ;; uniformly as predicate-name exclusions.  Inverse-atomic semantics
        ;; (forbidden inbound edges via specific predicates) requires a
        ;; separate compilation; we restrict to forward (non-:INV) for now.
        all-forward? (every? #(= :PREDICATE (:op %)) (:args ast))]
    (when-not all-forward?
      (throw (ex-info
               ":NOT with :INV-wrapped predicate not yet supported in compiler — flag for P-4 follow-on"
               {:received-args (:args ast)})))
    ;; Restrict to ref-typed attributes — otherwise ?to may be a
    ;; non-entity value (string / long / etc.) and the navigation
    ;; semantics break.  Path-grammar is about entity-to-entity
    ;; traversal; literal-valued attributes are a separate concern
    ;; handled by :TEST or :FILTER.
    (let [base-clause   [from-var pred-var to-var]
          ref-clause    [pred-var :db/valueType :db.type/ref]
          excl-clauses  (mapv (fn [arg]
                                [(list 'not= pred-var (:predicate arg))])
                              (:args ast))]
      {:where (into [base-clause ref-clause] excl-clauses)
       :rules []})))

(declare compile-or)

(defn- compile-opt
  "(:OPT p) ≡ (:OR p :SELF) — zero-or-one application.
   Desugars at compile time via existing :OR compiler."
  [ast from-var to-var ctr]
  (compile-or {:op :OR
               :args [(first (:args ast)) {:op :SELF}]}
              from-var to-var ctr))

(defn- compile-rep-bounded
  "(:REP p min max) — bounded repetition.

  Strategy A (unfolding): emit an :OR over n-length :SEQ chains for
  n ∈ [min, max].  Works well for small max (≤5 typical); combinatorial
  growth above.

  Strategy B (recursive rule with hop-counter) is more general but
  requires arithmetic + recursion together — deferred for later
  optimization.  Switch threshold not yet wired (always Strategy A);
  add hop-counter compilation when corpus-realistic queries hit the
  growth ceiling.

  Special cases:
    min = max = 0  →  :SELF (identity)
    min = 0, max ≥ 1  →  alternatives include :SELF (zero applications)
    min = max = 1  →  identical to the child"
  [ast from-var to-var ctr]
  (let [child (:child ast)
        min-n (:min ast)
        max-n (:max ast)
        ;; Build alternatives: for each n in [min..max], either :SELF
        ;; (n=0) or n-length :SEQ of child.
        alternatives
        (for [n (range min-n (inc max-n))]
          (cond
            (zero? n) {:op :SELF}
            (= n 1)   child
            :else     {:op :SEQ :args (vec (repeat n child))}))
        alts-vec (vec alternatives)]
    (cond
      ;; Degenerate: no alternatives (shouldn't happen with valid bounds)
      (empty? alts-vec)
      (throw (ex-info "(:REP p min max) requires valid bounds"
                      {:min min-n :max max-n}))

      ;; Single alternative: compile it directly (no :OR wrapping)
      (= 1 (count alts-vec))
      (compile-node (first alts-vec) from-var to-var ctr)

      :else
      (compile-or {:op :OR :args alts-vec} from-var to-var ctr))))

(defn- compile-filter
  "(:FILTER child substring) — URI-substring filter.

  Walks child from ?from to ?to, then asserts that ?to has :db/ident
  and that the stringified ident contains the substring.  Splits the
  string-coercion into its own function-bind clause (Datomic's
  internal compiler dislikes deeply-nested call forms inside predicate
  clauses; binding intermediate values is the conventional shape).

    <child clauses ?from → ?to>
    [?to :db/ident ?filter-ident]
    [(str ?filter-ident) ?filter-str]
    [(clojure.string/includes? ?filter-str substring)]

  Only matches entities with :db/ident.  For broader filtering (e.g.,
  on memory bodies), use :TEST with a custom predicate."
  [ast from-var to-var ctr]
  (let [child       (:child ast)
        substring   (:substring ast)
        child-comp  (compile-node child from-var to-var ctr)
        ident-var   (fresh-var ctr)
        str-var     (fresh-var ctr)
        filter-clauses
        [[to-var :db/ident ident-var]
         [(list 'str ident-var) str-var]
         [(list 'clojure.string/includes? str-var substring)]]]
    {:where (into (:where child-comp) filter-clauses)
     :rules (:rules child-comp)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; :TEST functional-predicate registry.
;;
;; Security: consumers cannot supply arbitrary closures (synthesis §3.2
;; "registry-mediated for security + serialization").  The registry
;; maps fn-name keywords to fully-qualified symbols resolvable at
;; query-execution time.  Default set seeds common safe predicates;
;; consumers may extend via register-test-fn!.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def test-fn-registry
  "Registry of fn-name → fully-qualified symbol for use in :TEST path
  expressions.  Atom so consumers can register additional fns."
  (atom
    {:pos?              'clojure.core/pos?
     :neg?              'clojure.core/neg?
     :zero?             'clojure.core/zero?
     :nil?              'clojure.core/nil?
     :some?             'clojure.core/some?
     :true?             'clojure.core/true?
     :false?            'clojure.core/false?
     :string?           'clojure.core/string?
     :keyword?          'clojure.core/keyword?
     :integer?          'clojure.core/integer?
     :number?           'clojure.core/number?
     :coll?             'clojure.core/coll?
     :map?              'clojure.core/map?
     :empty?            'clojure.core/empty?
     :not-empty         'clojure.core/not-empty}))

(defn register-test-fn!
  "Register a Clojure fn for use in `:TEST` path expressions.

  Args:
    `fn-name`   — keyword identifier used in `:TEST` AST nodes
    `fn-symbol` — fully-qualified symbol (must be resolvable via
                  requiring-resolve at query-execution time)

  Returns the updated registry map.

  Security note: Sandbar substrate ships a default registry of safe
  predicates; consumers register additional ones explicitly rather
  than supplying arbitrary closures."
  [fn-name fn-symbol]
  (when-not (keyword? fn-name)
    (throw (ex-info "register-test-fn! fn-name must be a keyword"
                    {:received fn-name})))
  (when-not (and (symbol? fn-symbol) (namespace fn-symbol))
    (throw (ex-info "register-test-fn! fn-symbol must be a fully-qualified symbol"
                    {:received fn-symbol})))
  (swap! test-fn-registry assoc fn-name fn-symbol))

(defn- compile-test
  "(:TEST child fn-name) — functional predicate.

  Walks child from ?from to ?to, then asserts (fn-symbol ?to) is
  truthy via Datalog predicate clause.

    <child clauses ?from → ?to>
    [(fn-symbol ?to)]

  `fn-name` (keyword in AST) must be in the test-fn-registry.  Unknown
  fn-names raise ex-info at compile time."
  [ast from-var to-var ctr]
  (let [child   (:child ast)
        fn-kw   (:fn-name ast)
        fn-sym  (get @test-fn-registry fn-kw)]
    (when (nil? fn-sym)
      (throw (ex-info
               (str ":TEST fn-name " fn-kw " is not registered. "
                    "Use sandbar.navigate.path.datomic/register-test-fn! "
                    "or pick from the default registry.")
               {:fn-name fn-kw
                :registered (keys @test-fn-registry)})))
    (let [child-comp (compile-node child from-var to-var ctr)
          test-clause [(list fn-sym to-var)]]
      {:where (conj (:where child-comp) test-clause)
       :rules (:rules child-comp)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dispatch — compile-node walks the AST.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- compile-node
  [ast from-var to-var ctr]
  (case (:op ast)
    ;; Canonical-8 (Tier-1)
    :PREDICATE (compile-predicate    ast from-var to-var ctr)
    :SELF      (compile-self         ast from-var to-var ctr)
    :ANY       (compile-any          ast from-var to-var ctr)
    :RESTRICT  (compile-restrict     ast from-var to-var ctr)
    :SEQ       (compile-seq          ast from-var to-var ctr)
    :OR        (compile-or           ast from-var to-var ctr)
    :REP+      (compile-rep+         ast from-var to-var ctr)
    :REP*      (compile-rep*         ast from-var to-var ctr)
    :INV       (compile-inv          ast from-var to-var ctr)
    ;; Tier-2 (Stage P-4)
    :NOT       (compile-not          ast from-var to-var ctr)
    :OPT       (compile-opt          ast from-var to-var ctr)
    :REP       (compile-rep-bounded  ast from-var to-var ctr)
    :FILTER    (compile-filter       ast from-var to-var ctr)
    :TEST      (compile-test         ast from-var to-var ctr)
    ;; Tier-3 deferred — vocabulary registered in P-1; compilation
    ;; lands when consumer demand emerges per synthesis §3.3
    (throw (ex-info (str "Path operator not yet supported by Datomic compiler: "
                         (:op ast)
                         " — Tier-3 deferred per synthesis §3.3.")
                    {:op (:op ast) :ast ast}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public compile entry-point.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn compile
  "Compile a canonical path-grammar AST to a Datomic query fragment.

  Returns:
    {:where [<clause> ...] :rules [[<rule-head> <body>...] ...]}

  Usage:
    (let [ast (sandbar.navigate.path.ast/parse expr)
          ir  (sandbar.navigate.path.ir/canonicalize ast)
          {:keys [where rules]} (compile ir '?start '?end)
          q   (vec (concat '[:find ?end :in $ % ?start :where] where))]
      (d/q q (db) rules start-eid))

  Variables `from-var` and `to-var` default to `?start` and `?end` for
  the consumer-facing form."
  ([ast]
   (compile ast '?start '?end))
  ([ast from-var to-var]
   (compile-node ast from-var to-var (new-fresh-ctr))))
