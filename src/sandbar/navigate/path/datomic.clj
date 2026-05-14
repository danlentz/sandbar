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
     :rules (vec (mapcat :rules pairs))}))

(defn- compile-or
  "(:OR p1 p2 ... pN) from ?x to ?y → (or-join [?x ?y] <branch> ...)
   Each branch shares the ?x and ?y vars; intermediate vars local to
   each branch."
  [ast from-var to-var ctr]
  (let [branches (mapv (fn [child]
                         (compile-node child from-var to-var ctr))
                       (:args ast))
        rules    (vec (mapcat :rules branches))
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
        child-rules (concat (:rules base-comp) (:rules step-comp))]
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
     :rules (into [id-rule rec-rule] (:rules step))}))

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
;; Dispatch — compile-node walks the AST.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- compile-node
  [ast from-var to-var ctr]
  (case (:op ast)
    :PREDICATE (compile-predicate ast from-var to-var ctr)
    :SELF      (compile-self      ast from-var to-var ctr)
    :ANY       (compile-any       ast from-var to-var ctr)
    :RESTRICT  (compile-restrict  ast from-var to-var ctr)
    :SEQ       (compile-seq       ast from-var to-var ctr)
    :OR        (compile-or        ast from-var to-var ctr)
    :REP+      (compile-rep+      ast from-var to-var ctr)
    :REP*      (compile-rep*      ast from-var to-var ctr)
    :INV       (compile-inv       ast from-var to-var ctr)
    ;; Unsupported operators (Tier-2 / Tier-3 — added at P-4 / later)
    (throw (ex-info (str "Path operator not yet supported by Datomic compiler: "
                         (:op ast)
                         " — see Stage P-3 § Canonical-8; Tier-2 in P-4 / Tier-3 deferred.")
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
