(ns sandbar.navigate.path.ast
  "Sandbar Path-Grammar — AST + Parser + Canonical-Form (Stage P-1 of
  comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  This is the DSL-layer surface for Sandbar's Wilbur-lineage path-grammar.
  Three concerns live in this namespace:

  1. The operator vocabulary registry — 21 committed operators per
     syntheses/sandbar_path_grammar_substrate_design_research_2026_05_13.md
     §1.2:
       - Canonical-8 (Tier 1): :SEQ :OR :REP+ :REP* :INV :SELF :RESTRICT :ANY
       - Tier 2 (production-safety + filtering): :NOT :OPT :REP :FILTER :TEST
       - Tier 3 (deferred compilation; vocabulary registered for parse-
         time recognition + future-proofing): :LANG :VALUE :DAEMON
         :NOREWRITE :MEMBERS :PREDICATE-OF-SUBJECT :PREDICATE-OF-OBJECT

  2. The canonical AST schema — each AST node is a map keyed by `:op`
     plus operator-specific slots.  Uniform shape allows downstream
     stages (IR rewriter at P-2, Datomic compiler at P-3) to dispatch
     via `:op` without parsing-knowledge.

  3. The parser — EDN form → canonical AST.  EDN form is the
     stable user-visible surface:
       atomic predicate keyword         (:cites)
       nullary operator keyword         :SELF
       operator form vector             [:SEQ [:REP* :cites] :evidences]
     Per casing convention from
     decisions/multi_axis_search_catalog_2026_05_08.md D4:
     UPPERCASE combinators / lowercase predicates.  The registry
     itself is the disambiguator — a keyword is an atomic predicate
     iff it is NOT a registered operator.

  Stage P-1 ships parse + arity validation; algebraic-identity
  rewriting lives at P-2 (`sandbar.navigate.path.ir`).  Datomic
  compilation lives at P-3 (`sandbar.navigate.path.datomic`)."
  (:refer-clojure :exclude [parse]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Operator vocabulary registry
;;
;; Each entry:
;;   :arity            — :nullary | <int> | :variadic-1+
;;   :tier             — :tier-1 | :tier-2 | :tier-3
;;   :semantics-class  — :combinator | :filter | :meta | :escape
;;                       | :rdf-specific | :meta-navigation
;;   :arg-shape        — :nullary | :path-children | :target-spec
;;                       | :rep-bounded | :path+substring | :path+fn-name
;;                       | :path+string | :constant-value
;;   :doc              — one-line semantics summary
;;
;; The :arg-shape discriminates how the parser interprets the args
;; vector — most operators take sub-expressions (recursive parse), but
;; :RESTRICT takes a [predicate value] target tuple, :FILTER takes
;; [path string], etc.  See parse-args dispatch.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def operator-vocabulary
  "Named operators per
  syntheses/sandbar_path_grammar_substrate_design_research_2026_05_13.md
  §1.2.  Synthesis frames the conceptual surface as '21 operators'
  (18 Wilbur-derived + 3 SPARQL-parity); this registry contains 20
  NAMED operators because the atomic-predicate form is the parser's
  catch-all (any keyword not in this registry parses as `:PREDICATE`),
  not a registry entry.  20 named + 1 implicit atomic = 21 conceptual
  surface.

  Atomic predicates (lowercase keywords like `:cites`) are NOT in
  this registry — they're parsed as `:PREDICATE` AST nodes by virtue
  of not matching any registered operator."

  {;; --- Canonical-8 (Tier 1; ship first) ---
   :SEQ      {:arity :variadic-1+
              :tier  :tier-1
              :semantics-class :combinator
              :arg-shape :path-children
              :doc "Sequence composition: walk p1 then p2 then ..."}
   :OR       {:arity :variadic-1+
              :tier  :tier-1
              :semantics-class :combinator
              :arg-shape :path-children
              :doc "Alternation / set union: try p1 or p2 or ..."}
   :REP+     {:arity 1
              :tier  :tier-1
              :semantics-class :combinator
              :arg-shape :path-children
              :doc "Kleene-plus: transitive closure (one-or-more applications)"}
   :REP*     {:arity 1
              :tier  :tier-1
              :semantics-class :combinator
              :arg-shape :path-children
              :doc "Kleene-star: reflexive-transitive closure (zero-or-more)"}
   :INV      {:arity 1
              :tier  :tier-1
              :semantics-class :combinator
              :arg-shape :path-children
              :doc "Inverse: walk the predicate in reverse (subject/object swap)"}
   :SELF     {:arity :nullary
              :tier  :tier-1
              :semantics-class :meta
              :arg-shape :nullary
              :doc "Identity step: no-op walk (current node)"}
   :ANY      {:arity :nullary
              :tier  :tier-1
              :semantics-class :meta
              :arg-shape :nullary
              :doc "Wildcard predicate match: any edge"}
   :RESTRICT {:arity 1
              :tier  :tier-1
              :semantics-class :filter
              :arg-shape :target-spec
              :doc "Specific-node restriction: filter to a fixed [predicate value] target"}

   ;; --- Tier 2 (production-safety + filtering; ship second) ---
   :NOT      {:arity :variadic-1+
              :tier  :tier-2
              :semantics-class :filter
              :arg-shape :path-children
              :doc "Negated property set (SPARQL !p / !(p1|p2) parity)"}
   :OPT      {:arity 1
              :tier  :tier-2
              :semantics-class :combinator
              :arg-shape :path-children
              :doc "Zero-or-one: desugars to (:OR p :SELF)"}
   :REP      {:arity 3
              :tier  :tier-2
              :semantics-class :combinator
              :arg-shape :rep-bounded
              :doc "Bounded repetition: walk p at least min and at most max times (Cypher [*min..max] parity)"}
   :FILTER   {:arity 2
              :tier  :tier-2
              :semantics-class :filter
              :arg-shape :path+substring
              :doc "URI-substring filter on the result set"}
   :TEST     {:arity 2
              :tier  :tier-2
              :semantics-class :filter
              :arg-shape :path+fn-name
              :doc "Functional predicate via registered fn (security: registry-mediated)"}

   ;; --- Tier 3 (vocabulary registered; compilation deferred) ---
   :LANG     {:arity 2
              :tier  :tier-3
              :semantics-class :filter
              :arg-shape :path+string
              :doc "Language-tag filter on literal targets (compilation deferred)"}
   :VALUE    {:arity 1
              :tier  :tier-3
              :semantics-class :combinator
              :arg-shape :constant-value
              :doc "Constant default value (compilation deferred)"}
   :DAEMON   {:arity 2
              :tier  :tier-3
              :semantics-class :combinator
              :arg-shape :path+fn-name
              :doc "Computed property via daemon function (compilation deferred)"}
   :NOREWRITE {:arity 1
               :tier  :tier-3
               :semantics-class :escape
               :arg-shape :path-children
               :doc "Reasoner-opaque wrapper — disables rule rewriting (compilation deferred)"}
   :MEMBERS  {:arity :nullary
              :tier  :tier-3
              :semantics-class :rdf-specific
              :arg-shape :nullary
              :doc "RDF container membership iteration (compilation deferred)"}
   :PREDICATE-OF-SUBJECT
   {:arity :nullary
    :tier  :tier-3
    :semantics-class :meta-navigation
    :arg-shape :nullary
    :doc "Quantify over predicate position; node is subject (compilation deferred)"}
   :PREDICATE-OF-OBJECT
   {:arity :nullary
    :tier  :tier-3
    :semantics-class :meta-navigation
    :arg-shape :nullary
    :doc "Quantify over predicate position; node is object (compilation deferred)"}})

(def ^:private op-keywords
  "Set of registered operator keywords — used by the parser to
   discriminate atomic predicates from operators."
  (set (keys operator-vocabulary)))

(defn registered-operator?
  "True if `kw` is a registered path-grammar operator."
  [kw]
  (contains? op-keywords kw))

(defn atomic-predicate?
  "True if `kw` is a keyword AND is NOT a registered operator —
   i.e. it represents an atomic predicate (typed-edge step)."
  [kw]
  (and (keyword? kw) (not (registered-operator? kw))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Arity validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- arity-ok?
  [arity n]
  (cond
    (= arity :nullary)     (zero? n)
    (= arity :variadic-1+) (pos? n)
    (integer? arity)       (= n arity)
    :else                  false))

(defn- arity-error!
  [op arity got]
  (throw (ex-info
           (str "Operator " op " arity mismatch: "
                "expected " (pr-str arity) ", got " got)
           {:op op :expected-arity arity :got got})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parser — EDN form → canonical AST
;;
;; Atomic predicate keyword     → {:op :PREDICATE :predicate <kw>}
;; Nullary operator keyword     → {:op <kw>}
;; Operator form [:op arg ...]  → operator-specific shape (see parse-args)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare parse)

(defn- parse-args
  "Dispatch on the operator's :arg-shape to interpret args vector."
  [op arg-shape args]
  (case arg-shape
    :path-children
    {:op op :args (mapv parse args)}

    :target-spec  ; :RESTRICT takes one arg: a [predicate value] tuple
    (let [[target] args]
      (when-not (and (sequential? target) (= 2 (count target)))
        (throw (ex-info
                 (str op " :target must be a [predicate value] 2-tuple")
                 {:op op :received target})))
      {:op op :target (vec target)})

    :rep-bounded  ; :REP takes [path min max]
    (let [[p min-bound max-bound] args]
      (when-not (and (integer? min-bound) (integer? max-bound)
                     (<= 0 min-bound) (<= min-bound max-bound))
        (throw (ex-info
                 (str op " requires 0 ≤ min ≤ max integer bounds")
                 {:op op :min min-bound :max max-bound})))
      {:op op :child (parse p) :min min-bound :max max-bound})

    :path+substring  ; :FILTER takes [path string]
    (let [[p s] args]
      (when-not (string? s)
        (throw (ex-info (str op " requires a string substring arg")
                        {:op op :received s})))
      {:op op :child (parse p) :substring s})

    :path+fn-name  ; :TEST / :DAEMON take [path fn-name-keyword]
    (let [[p fn-name] args]
      (when-not (keyword? fn-name)
        (throw (ex-info (str op " requires a keyword fn-name arg")
                        {:op op :received fn-name})))
      {:op op :child (parse p) :fn-name fn-name})

    :path+string  ; :LANG takes [path lang-tag-string]
    (let [[p s] args]
      (when-not (string? s)
        (throw (ex-info (str op " requires a string lang-tag arg")
                        {:op op :received s})))
      {:op op :child (parse p) :lang-tag s})

    :constant-value  ; :VALUE takes [value]
    (let [[v] args]
      {:op op :value v})

    :nullary  ; defensive; nullary ops should not reach here
    (throw (ex-info
             (str op " is nullary; cannot take arguments")
             {:op op :args args}))))

(defn parse
  "Parse an EDN path expression into a canonical AST.

  Atomic predicate (bare keyword not in operator vocabulary):
    :cites
    →  {:op :PREDICATE :predicate :cites}

  Nullary operator (bare keyword in operator vocabulary):
    :SELF
    →  {:op :SELF}

  Operator form (vector with operator keyword followed by args):
    [:SEQ :cites :evidences]
    →  {:op :SEQ :args [{:op :PREDICATE :predicate :cites}
                        {:op :PREDICATE :predicate :evidences}]}

    [:RESTRICT [:type :decision]]
    →  {:op :RESTRICT :target [:type :decision]}

    [:REP :cites 1 3]
    →  {:op :REP :child {:op :PREDICATE :predicate :cites} :min 1 :max 3}

  Throws ex-info on unknown operators, arity violations, or invalid
  arg-shapes."
  [expr]
  (cond
    ;; Atomic predicate keyword
    (atomic-predicate? expr)
    {:op :PREDICATE :predicate expr}

    ;; Bare operator keyword (must be nullary)
    (keyword? expr)
    (let [opspec (operator-vocabulary expr)]
      (when-not opspec
        (throw (ex-info (str "Unknown path operator: " expr)
                        {:received expr})))
      (when-not (= :nullary (:arity opspec))
        (throw (ex-info (str "Operator " expr " is not nullary; "
                             "expected vector form [" expr " arg ...]")
                        {:op expr :arity (:arity opspec)})))
      {:op expr})

    ;; Operator form: [:OP arg ...]
    (sequential? expr)
    (let [[op & args] expr
          opspec     (operator-vocabulary op)]
      (when-not opspec
        (throw (ex-info (str "Unknown path operator: " op)
                        {:received op :form expr})))
      (let [n (count args)]
        (when-not (arity-ok? (:arity opspec) n)
          (arity-error! op (:arity opspec) n)))
      (parse-args op (:arg-shape opspec) args))

    :else
    (throw (ex-info "Invalid path expression: expected keyword or vector"
                    {:received expr :type (type expr)}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Unparse — canonical AST → EDN form (round-trip support for tests +
;; downstream tooling).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn unparse
  "Inverse of `parse` — convert a canonical AST back to EDN form.

  Round-trip property: `(= expr (unparse (parse expr)))` for canonical
  EDN inputs.  (Inputs that don't survive round-trip — e.g., redundant
  wrapping — would canonicalize at the IR layer in P-2.)"
  [ast]
  (let [op     (:op ast)
        opspec (operator-vocabulary op)]
    (cond
      (= :PREDICATE op)
      (:predicate ast)

      (= :nullary (:arity opspec))
      op

      :else
      (case (:arg-shape opspec)
        :path-children
        (into [op] (map unparse) (:args ast))

        :target-spec
        [op (:target ast)]

        :rep-bounded
        [op (unparse (:child ast)) (:min ast) (:max ast)]

        :path+substring
        [op (unparse (:child ast)) (:substring ast)]

        :path+fn-name
        [op (unparse (:child ast)) (:fn-name ast)]

        :path+string
        [op (unparse (:child ast)) (:lang-tag ast)]

        :constant-value
        [op (:value ast)]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Vocabulary introspection helpers (for downstream tooling + tests).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn operators-by-tier
  "Return the seq of operator keywords belonging to the given tier:
   `:tier-1` / `:tier-2` / `:tier-3`."
  [tier]
  (->> operator-vocabulary
       (filter (fn [[_ spec]] (= tier (:tier spec))))
       (map key)
       sort
       vec))

(defn tier-of
  "Return the tier keyword for a registered operator, or nil."
  [op]
  (get-in operator-vocabulary [op :tier]))

(defn arity-of
  "Return the :arity spec for a registered operator, or nil."
  [op]
  (get-in operator-vocabulary [op :arity]))
