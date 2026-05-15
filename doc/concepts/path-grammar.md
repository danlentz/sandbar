# Path-Grammar

> Sandbar speaks Kleene-algebra-over-binary-relations as a first-class navigation surface.  EDN path expressions parse to a canonical AST, normalize through algebraic identities, and compile to Datomic recursive rules.  The algebra is settled — the Wilbur lineage (Lassila 1989 → Nokia 2001-2009) discovered it from Lisp-side first principles; SPARQL 1.1 (2013) formalized the same algebra independently; Cypher's variable-length paths and the ISO GQL 39075:2024 standard converge on the same surface.  Sandbar inherits the algebra, ships subset-first, and exposes paths as first-class values that compose with the rest of the retrieval substrate.

## Thesis

A path expression denotes a **binary relation** — given a starting resource, it evaluates to the set of resources reachable by following the relation.  Path expressions **compose** algebraically (sequence / inverse / union / Kleene closure / filtering / specific-node restriction) and **compile** to a stable evaluation form.

This is not novelty.  Kleene algebra over binary relations is a mathematical structure — not a design choice, a *discovery*.  The same algebra emerged in regular-expression theory (Kleene 1956), in relational algebra (Codd 1970, Tarski 1941), and in description-logic role expressions (Brachman 1979).  When the same algebraic structure arrives independently from four different traditions, you build on it.

Sandbar's path-grammar is the navigation-axis primitive of the four-axis retrieval surface (search / aggregate / navigate / orient).  Search ranks by content; aggregation summarizes the candidate set; orientation summarizes session state; navigation walks the typed-edge graph from a seed.  Path-grammar is the most expressive navigation form — bounded BFS via `sandbar.navigate.walk` covers the common case; path-grammar handles the cases where "everything reachable via this specific shape" is the right question.

## Lineage

The design draws from four traditions, deliberately.

### Wilbur (Lassila 1989, Nokia 2001-2009)

Wilbur is the source-of-truth lineage.  Ora Lassila — co-author of the original RDF Recommendation (1999) — built Wilbur at Nokia Research as a CL-CLOS RDF/CBD framework with first-class path-grammar.  Source-verified at `~/src/templeton/src/core/wilbur-ql.lisp:179-225` (Templeton's vendored Wilbur2; 643 LOC).  `db-canonical-path` defines the canonical operator vocabulary: 18 operators (eight combinators, six filters, four meta-navigation) implemented as a paths-as-s-expressions DSL compiled to a finite-state automaton walking the triple store.

The discipline Wilbur articulated:

- **Paths-as-values** — a path is data; filters are data; predicates are data.  All composable via the host language's native list operations.  Homoiconicity at the navigation layer.
- **Algebraic identities apply at compile time** — `(:SEQ p (:SEQ q r))` ≡ `(:SEQ p q r)` (associative); `(:OR p p)` ≡ `p` (idempotent); `(:INV (:INV p))` ≡ `p` (involutive); twelve identities applied as rewrites.
- **FSA compilation amortizes over many walks** — pre-canonicalize, build the NFA once, walk many times with cycle-detection via memoization.

Sandbar inherits the algebra, the homoiconicity, and the rewrite-as-canonicalize discipline.  FSA compilation is deferred (a Phase-2 optimization per cornerstone D10); the recursive-Datalog compiler is the baseline backend.

### SPARQL 1.1 property paths (2013)

The W3C SPARQL 1.1 Recommendation (Harris & Seaborne 2013, §9 Property Paths) formalized the same algebra independently.  The operators map cleanly: SPARQL `p1/p2` ≡ Wilbur `(:seq p1 p2)`; SPARQL `p+` ≡ Wilbur `(:rep+ p)`; SPARQL `^p` ≡ Wilbur `(:inv p)`; SPARQL `!p` (negated property set) ≡ Sandbar `:NOT`.

SPARQL added negated property sets (`!p`) — not in Wilbur but useful in practice.  Sandbar adopts as Tier-2 `:NOT`.

### Cypher and ISO GQL (2024)

Cypher's variable-length patterns (`[*1..3]`) added **bounded repetition** to the algebra — termination guarantees in cases where unbounded `:REP*` over a high-fanout graph would explode.  Sandbar adopts as Tier-2 `(:REP p min max)`.

ISO GQL 39075:2024 (the emerging international standard that subsumes Cypher + GSQL + similar) standardizes the same algebra.  Path-pattern semantics in GQL is conformable to Wilbur's: same Kleene closure + alternation + sequence shape.  **The algebra Sandbar inherits from Wilbur IS the algebra GQL standardizes.**  Future GQL conformance is a syntactic translation surface, not an algebraic rewrite.

### Asami (Paula Gearon)

Asami is the only Clojure-native graph database where Wilbur `:REP+` / `:REP*` compile 1:1 via Asami's native `+` / `*` operators.  Gearon authored both Asami AND the SPARQL property-paths spec — direct lineage.  Sandbar's three-layer DSL/IR/Backend architecture means Asami can land as a future backend (`sandbar.navigate.path.asami`) without DSL changes; deferred unless path-grammar-heavy workloads justify it.

## The operator surface

Twenty-one operators committed across three tiers.  Canonical-8 + Tier-2 = thirteen operators executable today; Tier-3 vocabulary-registered for parse-time recognition + future-proofing, compilation deferred.

### Casing convention

Per `decisions/multi_axis_search_catalog_2026_05_08.md` D4: **UPPERCASE combinators / lowercase predicates**.  `[:SEQ [:REP* :cites] :evidences]` reads with the algebraic role of each operator visually distinct from the content role of typed-edge predicates.  Diverges from Wilbur's all-lowercase; preserves the canonical semantics.

### Canonical-8 (Tier-1)

The ship-first surface.  Maps cleanly to native Datomic constructs.

| Operator      | Arity              | Semantics                                              |
|---------------|--------------------|--------------------------------------------------------|
| `:SEQ`        | variadic           | Sequence composition (relation composition)             |
| `:OR`         | variadic           | Alternation / set union                                 |
| `:REP+`       | 1                  | Kleene-plus: transitive closure (1+ applications)      |
| `:REP*`       | 1                  | Kleene-star: reflexive-transitive closure (0+)         |
| `:INV`        | 1                  | Inverse (swap subject/object roles)                    |
| `:SELF`       | 0                  | Identity step (no-op)                                  |
| `:RESTRICT`   | 1 `[pred value]`   | Specific-node restriction (filter at current node)     |
| `:ANY`        | 0                  | Wildcard predicate match                               |

### Tier-2 (production-safety + filtering)

| Operator           | Arity                | Semantics                                                       |
|--------------------|----------------------|-----------------------------------------------------------------|
| `:NOT`             | variadic atomic preds | Negated property set (SPARQL `!p` / `!(p1\|p2)` parity)        |
| `:OPT`             | 1                    | Zero-or-one (desugars to `(:OR p :SELF)`)                       |
| `:REP p min max`   | 3                    | Bounded repetition (Cypher `[*min..max]` parity)                |
| `:FILTER`          | 2 `[p substr]`       | URI-substring filter (matches against `:db/ident`)              |
| `:TEST`            | 2 `[p fn-name]`      | Functional predicate via registered fn (registry-mediated)      |

### Tier-3 (vocabulary registered; compilation deferred)

`:LANG` (language-tag filter), `:VALUE` (constant default), `:DAEMON` (computed property), `:NOREWRITE` (reasoner-opaque), `:MEMBERS` (RDF container iteration), `:PREDICATE-OF-SUBJECT` / `:PREDICATE-OF-OBJECT` (meta-navigation).  Parser accepts; compiler raises descriptive `ex-info` at call time pending consumer demand.

### Atomic predicates

Any lowercase keyword not in the registry is an atomic predicate — a single typed-edge step.  `:cites` walks one `:cites` edge; `:mm.memory/parent` walks one `:mm.memory/parent` edge.  Substrate-quality discipline: no hardcoded predicate vocabulary; the registry is purely combinator-keywords.

## Algebraic identities

Kleene algebra over binary relations gives a closed set of identities.  Sandbar's IR layer (`sandbar.navigate.path.ir`) applies them as rewrites until fixpoint.

| Identity                                                  | Structure                          |
|-----------------------------------------------------------|------------------------------------|
| `(:SEQ p (:SEQ q r))` ≡ `(:SEQ p q r)`                    | Sequence associative               |
| `(:OR p (:OR q r))` ≡ `(:OR p q r)`                       | Union associative                  |
| `(:OR p p)` ≡ `p`                                         | Union idempotent                   |
| `(:SEQ p (:OR q r))` ≡ `(:OR (:SEQ p q) (:SEQ p r))`      | Sequence distributes left over union |
| `(:SEQ (:OR p q) r)` ≡ `(:OR (:SEQ p r) (:SEQ q r))`      | Sequence distributes right over union |
| `(:INV (:INV p))` ≡ `p`                                   | Inverse involutive                 |
| `(:INV (:SEQ p q))` ≡ `(:SEQ (:INV q) (:INV p))`          | Inverse reverses sequence          |
| `(:INV (:OR p q))` ≡ `(:OR (:INV p) (:INV q))`            | Inverse distributes over union     |
| `(:REP* (:REP* p))` ≡ `(:REP* p)`                         | Closure idempotent                 |
| `(:REP+ (:REP+ p))` ≡ `(:REP+ p)`                         | Closure idempotent (transitive)    |
| `(:REP* (:REP+ p))` ≡ `(:REP* p)`                         | Closure absorbs one-or-more        |
| `(:INV :SELF)` ≡ `:SELF`                                  | Inverse of identity is identity    |

Canonical form applies all expression-shrinking + shape-normalizing rewrites.  Distribution (sequence over union) is opt-in — it grows expression size, useful for downstream NFA construction or optimizer plan-cost analysis but not as a default normalization.

## The three-layer architecture

Per `decisions/query_engine_architectural_cornerstone_2026_05_11.md` D1.

```
┌─────────────────────────────────────────────────────────────────┐
│  DSL LAYER (stable; user-visible)                                │
│    EDN path expressions: '[:SEQ [:REP* :cites] [:RESTRICT ...]] │
│    Exposed via: in-process Clojure / MCP / REST                  │
└─────────────────────────────────────────────────────────────────┘
                          ↓ parse + canonicalize
┌─────────────────────────────────────────────────────────────────┐
│  IR LAYER (backend-neutral)                                      │
│    Canonical path-AST (algebraic identities applied to fixpoint) │
└─────────────────────────────────────────────────────────────────┘
                          ↓ compile
┌─────────────────────────────────────────────────────────────────┐
│  BACKEND LAYER (pluggable executor)                              │
│    PRIMARY:  Datomic recursive rules + reverse-attribute syntax  │
│    FUTURE:   Asami native :cites+ (path-grammar-native)          │
│    FUTURE:   NFA × graph product (large-corpus optimization)     │
└─────────────────────────────────────────────────────────────────┘
```

Per Dan's architectural principle: **scale-up requires backend swap, not DSL rewrite**.  The DSL stays stable; the IR is the substrate of optimization; the backend implements.

The Sandbar source layout reflects this:

```
sandbar.navigate.path/        ← consumer-facing: path-via, the top-level verb
sandbar.navigate.path.ast/    ← DSL parsing + 20-operator vocabulary registry
sandbar.navigate.path.ir/     ← algebraic-identity rewriter (canonicalize + distribute)
sandbar.navigate.path.datomic ← Canonical-8 + Tier-2 Datomic compiler
sandbar.navigate.path.value/  ← path-as-first-class-value (length / prefix / subpath)
```

## Paths as first-class values

Per `decisions/query_engine_architectural_cornerstone_2026_05_11.md` D9.  Paths are not just reachability witnesses — they are returnable values with structural predicates.

```clojure
{:nodes [<node-0> <node-1> ... <node-N>]              ; N+1 nodes
 :edges [<edge-1> <edge-2> ... <edge-N>]}             ; N edges
```

Each edge: `{:predicate <pred-ident> :direction :forward|:inverse}`.  Invariant: `(count :nodes) = (count :edges) + 1`.

Operations on paths:

- **`length`** — number of edges
- **`prefix?` / `suffix?` / `subpath?`** — structural relations between paths
- **`extend-path`** / **`concat-paths`** / **`reverse`** — composition (reverse is involutive)
- **`predicates`** / **`directions`** — projection helpers

Path-data surfacing through path expressions (`:include #{:paths}`) is currently flagged `:path-data-deferred true` — recursive-path reconstruction requires post-hoc Clojure-side walks (analogous to `sandbar.navigate.walk`'s BFS pattern).  The abstraction is ready; the surfacing lands at a follow-on.

## What path-grammar is NOT for

Per Wilbur's discipline — path expressions are a **binary-relation algebra**.  They cover navigation: *"starting from X, what's reachable via Y?"*  They do NOT cover:

- **N-ary joins** — *"find triples matching two independent patterns with a shared variable"* lives at Datalog WHERE-clause level above
- **Aggregation** — *"how many siblings does X have?"* lives at the `sandbar.aggregate` layer
- **Named variables in the middle** — *"find X such that X cites something whose type is Y"* with Y projected — path-grammar conflates intermediates; full Datalog handles
- **Literal comparison** — *"find resources whose age > 30"* — `:TEST` can filter but the filter is opaque to the optimizer

This bound is **the right one** for the navigation axis.  Aggregations + named-variable queries live at sibling axes (per the four-axis decomposition).  Path-grammar composes with them via the cross-axis composition contract.

## References

- Lassila, Ora.  *"Wilbur"*.  Nokia Research Center, 2001-2009.  CL CLOS RDF/CBD framework with first-class path-grammar.  Source-verified at `~/src/templeton/src/core/wilbur-ql.lisp:179-225`.
- Lassila, Ora & Swick, Ralph R.  *"Resource Description Framework (RDF) Model and Syntax Specification"*.  W3C Recommendation, 1999.
- Harris, Steve & Seaborne, Andy.  *"SPARQL 1.1 Query Language"*.  W3C Recommendation, 2013.  §9 Property Paths formalizes the same algebra.
- Kleene, S.C.  *"Representation of events in nerve nets and finite automata"*.  Automata Studies, Princeton 1956.  The regular-expression theory underlying the closure operators.
- Codd, E.F.  *"A relational model of data for large shared data banks"*.  CACM 13(6), 1970.  The relational-algebra emergence of the same operators.
- ISO/IEC 39075:2024.  *"Information technology — Database languages — GQL"*.  International standard subsuming Cypher + GSQL + similar; path-pattern algebra conformable to Wilbur.
- Bonifati, Angela, Martens, Wim & Timm, Thomas.  *"An analytical study of large SPARQL query logs"*.  VLDB Journal 26(2), 2017.  Empirical analysis of property-path usage: <0.1% of real queries.

## See also

- `doc/concepts/navigation.md` — edges / walk / path-grammar overview
- `doc/guides/navigating-with-paths.md` — task-oriented worked examples
- `doc/api/mcp-verbs.md` — `sandbar.navigate.path-via` MCP entry
- `doc/api/http-rest.md` — `GET /api/navigate/path` REST entry
- The corpus's `syntheses/sandbar_path_grammar_substrate_design_research_2026_05_13.md` — ~800-line design synthesis (Wilbur source-verification + per-operator Datomic compilation analysis + cross-system comparison + 7-substage implementation roadmap)
