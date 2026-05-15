(ns sandbar.navigate.path.ir
  "Sandbar Path-Grammar — IR + Algebraic-Identity Rewriter (Stage P-2 of
  comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md).

  The IR layer sits between the DSL-layer AST (sandbar.navigate.path.ast)
  and the backend compiler (sandbar.navigate.path.datomic at P-3).  Its
  role is to NORMALIZE path expressions — apply algebraic identities of
  Kleene-algebra-over-relations as rewrites until a fixpoint is reached
  — so the compiler sees a canonical form regardless of input variation.

  Identities applied per
  syntheses/sandbar_path_grammar_substrate_design_research_2026_05_13.md
  §1.3 — Kleene algebra over relations.  Four rewrite categories per
  §6 P-2 spec:

  1. Associative-flatten
     - (:SEQ ... (:SEQ a b) ... c)  →  (:SEQ ... a b ... c)
     - (:OR  ... (:OR  a b) ... c)  →  (:OR  ... a b ... c)

  2. Idempotent-collapse
     - (:SEQ p)                     →  p           ; degenerate sequence
     - (:OR p)                      →  p           ; degenerate union
     - (:OR ... p p ...)            →  (:OR ... p ...)  ; dedupe in n-ary
     - (:REP* (:REP* p))            →  (:REP* p)   ; closure idempotent
     - (:REP* (:REP+ p))            →  (:REP* p)   ; closure absorbs +
     - (:REP+ (:REP+ p))            →  (:REP+ p)
     - (:REP+ (:REP* p))            →  (:REP* p)   ; (a+)* = a*

  3. Inverse-double-eliminate (push-inv-inward + eliminate-double)
     - (:INV (:INV p))              →  p
     - (:INV (:SEQ a b))            →  (:SEQ (:INV b) (:INV a))
     - (:INV (:OR a b))             →  (:OR (:INV a) (:INV b))
     - (:INV :SELF)                 →  :SELF

  4. Distributive-rewrite (opt-in via `distribute`; expression-growing)
     - (:SEQ p (:OR q r))           →  (:OR (:SEQ p q) (:SEQ p r))
     - (:SEQ (:OR p q) r)           →  (:OR (:SEQ p r) (:SEQ q r))

  `canonicalize` applies (1)+(2)+(3) to fixpoint — all are expression-
  shrinking or shape-normalizing, safe-by-default.  `distribute`
  applies (4) — grows expressions; opt-in for downstream optimization
  scenarios (e.g., NFA construction at deferred P-FSA sub-stage).

  Stage P-2 ships rewriter only; the Datomic compiler at P-3 consumes
  canonical IR."
  (:require [sandbar.navigate.path.ast :as ast]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Local rewrites — each takes a node, returns a possibly-rewritten node.
;; All are pure data → data; no side-effects.  All are confluent (order-
;; independent at a single node).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- flatten-assoc
  "Associative-flatten: if `node` is an n-ary op (:SEQ or :OR), inline
   any child of the same op into the args vec."
  [node target-op]
  (if (and (= target-op (:op node))
           (some #(= target-op (:op %)) (:args node)))
    (let [flat-args (into []
                          (mapcat (fn [arg]
                                    (if (= target-op (:op arg))
                                      (:args arg)
                                      [arg])))
                          (:args node))]
      (assoc node :args flat-args))
    node))

(defn- simplify-degenerate
  "Degenerate single-arg n-ary ops collapse to their single child.

  Applies to `:SEQ` and `:OR` (which are commutative-monoid identities
  with single args).  NOT applied to `:NOT` — single-arg `:NOT` is
  the canonical form for negated property set with one predicate; its
  semantics is the complement relation (target is set difference of
  reachable via :ANY minus via the predicate), NOT the predicate
  itself.  Collapsing `(:NOT p)` to `p` would invert semantics."
  [node]
  (cond
    (and (#{:SEQ :OR} (:op node))
         (= 1 (count (:args node))))
    (first (:args node))

    :else
    node))

(defn- dedupe-or
  "Dedupe identical args inside `:OR` while preserving order
   (commutativity holds but we keep insertion order for stable output)."
  [node]
  (if (= :OR (:op node))
    (let [seen (volatile! #{})
          deduped (vec (filter (fn [arg]
                                 (if (contains? @seen arg)
                                   false
                                   (do (vswap! seen conj arg) true)))
                               (:args node)))]
      (if (= (count deduped) (count (:args node)))
        node
        (assoc node :args deduped)))
    node))

(defn- collapse-rep
  "Closure idempotence + absorption:
     (:REP* (:REP* p))  →  (:REP* p)
     (:REP* (:REP+ p))  →  (:REP* p)
     (:REP+ (:REP+ p))  →  (:REP+ p)
     (:REP+ (:REP* p))  →  (:REP* p)  ; (a+)* = a*; (a*)+ also = a*"
  [node]
  (let [op    (:op node)
        child (first (:args node))]
    (cond
      ;; (:REP* (:REP* p)) → (:REP* p)
      (and (= :REP* op) (= :REP* (:op child)))
      child

      ;; (:REP* (:REP+ p)) → (:REP* p)  [closure absorbs one-or-more]
      (and (= :REP* op) (= :REP+ (:op child)))
      (assoc node :args [(first (:args child))])

      ;; (:REP+ (:REP+ p)) → (:REP+ p)
      (and (= :REP+ op) (= :REP+ (:op child)))
      child

      ;; (:REP+ (:REP* p)) → (:REP* p)
      (and (= :REP+ op) (= :REP* (:op child)))
      child

      :else
      node)))

(defn- eliminate-double-inv
  "Double-inverse elimination + push-inv-inward (normalize inverse to
   leaf positions):
     (:INV (:INV p))     →  p
     (:INV (:SEQ a b))   →  (:SEQ (:INV b) (:INV a))  [reverses sequence]
     (:INV (:OR a b))    →  (:OR (:INV a) (:INV b))   [distributes over union]
     (:INV :SELF)        →  :SELF                      [inverse of identity is identity]"
  [node]
  (let [op    (:op node)
        child (first (:args node))]
    (cond
      ;; Not an :INV — pass through
      (not= :INV op)
      node

      ;; (:INV (:INV p)) → p
      (= :INV (:op child))
      (first (:args child))

      ;; (:INV (:SEQ a b ...)) → (:SEQ (:INV last) ... (:INV first))
      (= :SEQ (:op child))
      {:op   :SEQ
       :args (mapv (fn [arg] {:op :INV :args [arg]})
                   (reverse (:args child)))}

      ;; (:INV (:OR a b ...)) → (:OR (:INV a) (:INV b) ...)
      (= :OR (:op child))
      {:op   :OR
       :args (mapv (fn [arg] {:op :INV :args [arg]})
                   (:args child))}

      ;; (:INV :SELF) → :SELF
      (= :SELF (:op child))
      child

      :else
      node)))

(defn- rewrite-node
  "Apply all canonical (expression-shrinking + shape-normalizing)
   rewrites at one node, in a fixed order designed for confluence:
     1. flatten-assoc      — surface nested same-op ops
     2. simplify-degenerate — collapse 1-ary n-ary ops
     3. dedupe-or          — remove repeats in :OR
     4. collapse-rep       — closure simplifications
     5. eliminate-double-inv — push :INV inward; eliminate doubles"
  [node]
  (-> node
      (flatten-assoc :SEQ)
      (flatten-assoc :OR)
      (simplify-degenerate)
      (dedupe-or)
      (collapse-rep)
      (eliminate-double-inv)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; AST traversal — post-order: rewrite children, then current node.
;; Re-recurse if a rewrite at the current node changed the structure.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- recurse-with
  "Apply transform `f` to each sub-AST slot of `node` (post-order).
   Each operator's `:arg-shape` determines which slots carry sub-
   expressions; this helper handles the structural variants."
  [f node]
  (cond
    ;; :path-children carry sub-expressions in :args
    (contains? node :args)
    (update node :args (partial mapv f))

    ;; :rep-bounded / :path+substring / :path+fn-name / :path+string
    ;; carry the sub-expression in :child
    (contains? node :child)
    (update node :child f)

    ;; :PREDICATE / nullary / :RESTRICT (target is data, not sub-AST)
    ;; / :VALUE — no sub-expressions to recurse into
    :else
    node))

(defn canonicalize
  "Apply algebraic-identity rewrites to fixpoint.  Recurse into children
  first (post-order), then rewrite the current node.  If the rewrite
  changes the structure, re-canonicalize the result (in case the change
  exposed new rewrite opportunities for the parent).

  Properties (proven via P-2 tests):
    - Idempotent: `(canonicalize (canonicalize x)) ≡ (canonicalize x)`
    - Semantics-preserving: every applied rewrite is a Kleene-algebra
      identity, so the result denotes the same binary relation as the
      input.
    - Terminating: every rewrite is reducing-or-normalizing (size
      decreases or shape converges); the rewrite set has no oscillating
      pairs.

  Use this on parsed AST before passing to the Datomic compiler (P-3)."
  [ast]
  (let [recursed  (recurse-with canonicalize ast)
        rewritten (rewrite-node recursed)]
    (if (= rewritten recursed)
      rewritten
      (canonicalize rewritten))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Distribution rewriter — opt-in expression-growing transforms.
;; Used for downstream NFA construction or hand-driven optimization,
;; not as part of the default canonical form (because it grows
;; expression size).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- distribute-seq-over-or-at-node
  "Apply sequence-distributes-over-union at one node:
     (:SEQ ... (:OR a b) ...) → (:OR (:SEQ ... a ...) (:SEQ ... b ...))

   Applies to the leftmost :OR child; iterates until no :OR remains
   in :SEQ children (one pass; caller iterates via `distribute` to
   fixpoint)."
  [node]
  (if (and (= :SEQ (:op node))
           (some #(= :OR (:op %)) (:args node)))
    (let [seq-args (:args node)
          [before [or-node & after]] (split-with #(not= :OR (:op %)) seq-args)]
      {:op :OR
       :args (mapv (fn [or-branch]
                     {:op :SEQ
                      :args (vec (concat before [or-branch] after))})
                   (:args or-node))})
    node))

(defn- distribute*
  "Internal distribute pass — recursive distribute without final flatten."
  [ast]
  (let [recursed    (recurse-with distribute* ast)
        distributed (distribute-seq-over-or-at-node recursed)]
    (if (= distributed recursed)
      distributed
      (distribute* (canonicalize distributed)))))

(defn distribute
  "Apply distributive-rewrite to fixpoint — distribute sequence over
  union: every `(:SEQ ... (:OR ...) ...)` is rewritten so that all
  `:OR` branches are at the top of the sub-expression.  Result is a
  union of pure sequences (no `:OR` inside `:SEQ`), in canonical form
  (single flat top-level `:OR` if multiple branches result).

  Note: this GROWS expression size — `(:SEQ p (:OR q r))` of 3 nodes
  becomes `(:OR (:SEQ p q) (:SEQ p r))` of 5 nodes.  Use only for
  downstream stages that benefit from distributed form (e.g., NFA
  construction, optimizer plan-cost analysis).

  Apply AFTER `canonicalize` for best results — canonicalization
  produces a stable form against which distribution iterates cleanly.
  The final canonicalize-pass at the top of this function flattens
  any nested `:OR` introduced by recursive distribution."
  [ast]
  (-> ast distribute* canonicalize))
