# RDFS Entailment as Substrate

> Sandbar compiles RDFS and OWL 2 RL entailment rules into native Datomic Datalog.  Predicate characteristics — transitive, symmetric, inverse-of, sub-property, domain, range, sub-class — become entailment-deriving Datalog rules at Layer 0.  The metamodel already declares the characteristics; the substrate just makes them executable.  No parallel rule engine; no external reasoner; no separate inference layer.  For the metaclass shape behind the characteristic classes see [`metamodel.md`](metamodel.md); for the layered rule subsystem this belongs to see the architecture notes at the end of this document.

## Thesis

The naive shape for "knowledge representation with inference" is two systems — a fact store and a reasoner — bolted together at the edges, with each system needing its own data model, its own query language, and its own consistency story.  Apache Jena does this in the JVM tradition.  Most RDF stacks do this in some form.  The cost is duplication: the same `rdf:type` triple lives in two indexes, the same query traverses two engines, and the two engines have to agree about what's true.

Sandbar's commitment runs the other way.  **RDFS entailment is encoded directly as recursive Datomic Datalog rules over the existing substrate.**  The corpus's already-declared predicate characteristics — `:dt/subclass-of`, `:dt/subproperty-of`, `:dt/domain`, `:dt/range`, `:dt/inverse-of`, plus the OWL property-characteristic marker classes (`:dt/TransitiveProperty`, `:dt/SymmetricProperty`, etc.) — drive a 14-rule rule-set that consumers pass through Datomic's `%` rule-binding at query time.  Derived facts surface alongside ground facts in ordinary query results.  There is no second engine to agree with.

This is the metacircular discipline applied to inference.  The substrate that holds the facts also holds the rules that derive new facts from them; the language that queries the facts also fires the rules; the introspection API that walks the metamodel sees both declared and entailed type memberships uniformly.

## What entailment means here

Entailment in the RDFS sense (Hayes & Patel-Schneider 2014) is the procedural reading of model-theoretic implication: given a set of declared triples plus the RDFS axioms, derive every triple that must be true.  Three illustrative shapes:

**Subclass instance lift (rdfs9).**  If `:order/Order` is a subclass of `:dt/Resource`, then every `:order/Order` instance is also a `:dt/Resource` instance — even though the database stores only the direct `:dt/type :order/Order` assertion.  A query asking "what are all the `:dt/Resource` instances?" must see the order.

**Sub-property entailment (rdfs7).**  If `:evidences` is a sub-property of `:cites`, then every `(x :evidences y)` triple entails a `(x :cites y)` triple.  A query asking "what does x cite?" must return everything x explicitly cites and everything x evidences.

**Transitive closure (OWL 2 RL prp-trp).**  If `:descends-from` is declared a `:dt/TransitiveProperty`, then `(a :descends-from b) ∧ (b :descends-from c)` entails `(a :descends-from c)`.  A query asking "what does a descend from?" must return the full chain, not just the direct edges.

In each case, the entailed facts are *not stored* — they are derived on demand from the declared facts plus the rule.  The query engine sees no distinction between a ground fact and a derived fact in the result set.

## Predicate characteristics already in the corpus

The corpus's predicate vocabulary already declares the information rules need.  Six characteristic classes are first-class members of the metamodel (declared in `schema/meta.edn`), each a `:dt/Class` whose subclass relation lifts a property into the corresponding OWL 2 RL entailment regime:

| Marker class                       | OWL 2 RL family | Semantics                                                      |
|------------------------------------|-----------------|----------------------------------------------------------------|
| `:dt/TransitiveProperty`           | prp-trp         | Closure under chain composition.                               |
| `:dt/SymmetricProperty`            | prp-symp        | Bidirectional — `(x p y)` implies `(y p x)`.                   |
| `:dt/AsymmetricProperty`           | prp-asyp        | `(x p y) ∧ (y p x)` is forbidden.  Integrity-check rule.       |
| `:dt/IrreflexiveProperty`          | prp-irp         | `(x p x)` is forbidden.  Integrity-check rule.                 |
| `:dt/FunctionalProperty`           | prp-fp          | `(x p y1) ∧ (x p y2)` entails `(y1 owl:sameAs y2)`.            |
| `:dt/InverseFunctionalProperty`    | prp-ifp         | `(x1 p y) ∧ (x2 p y)` entails `(x1 owl:sameAs x2)`.            |

A property earns its characteristic by being declared an instance of the marker class via the ordinary `:dt/type` slot:

```clojure
{:db/ident :mm.memory/descends-from
 :dt/type  :dt/StrictPartialOrderProperty
 ;; :dt/StrictPartialOrderProperty subclass-of's
 ;; :dt/TransitiveProperty + :dt/AsymmetricProperty + :dt/IrreflexiveProperty
 ...}
```

Because `:dt/type` is cardinality-one (an architectural commitment preserved through the entailment design), orthogonal characteristic combinations — transitive *and* asymmetric *and* irreflexive — compose through an **intersection class**: a hand- or compositionally-named class whose `:dt/subclass-of` declaration enumerates the component characteristics.  RDFS rdfs9 entails membership in each component class from the single declared type.  The corpus currently declares six such intersection classes covering the six characteristic combinations present across its ~66 predicates:

| Intersection class                              | Composes               | Predicate count |
|-------------------------------------------------|------------------------|-----------------|
| `:dt/DirectedBinaryRelationProperty`            | asymmetric + irreflexive | 41 |
| `:dt/StrictPartialOrderProperty`                | transitive + asymmetric + irreflexive | 8 |
| `:dt/AntisymmetricStrictOrderProperty`          | antisymmetric + irreflexive + transitive | 6 |
| `:dt/AsymmetricFunctionalIrreflexiveProperty`   | asymmetric + functional + irreflexive | 3 |
| `:dt/IrreflexiveSymmetricProperty`              | irreflexive + symmetric | 2 |
| `:dt/FunctionalIrreflexiveProperty`             | functional + irreflexive | 1 |

This is the SWCLOS pattern (Koide 2005) — *property characteristics as metaclasses*, instances composing orthogonally via CLOS-style multiple inheritance — adapted to Datomic by encoding the metaclass chain through `:dt/subclass-of` (already cardinality-many) rather than through a multi-valued `class-of`.  The architectural insight ports; the dispatch mechanism is Datalog rather than CLOS method specialization.  See [`metamodel.md`](metamodel.md) for the broader metacircular pattern this fits into.

## Compilation to Datomic Datalog rules

The rules live in `sandbar.db.entailment.core` as two static Datalog rule-sets — `rdfs-rules` (6 core RDFS) and `owl-rl-rules` (8 OWL 2 RL property-characteristic).  The composite `all-rules` is the concatenation.  Consumers pass either to Datomic's `d/q` through the `%` rule-binding.

### The worked compilation table

| Rule | Body                                              | Head             | Datomic encoding |
|------|---------------------------------------------------|------------------|------------------|
| rdfs2 (domain → type) | `(p domain C) ∧ (x p _)`                | `(x type C)`     | Non-recursive Datalog rule |
| rdfs3 (range → type)  | `(p range C) ∧ (_ p y)`                 | `(y type C)`     | Non-recursive Datalog rule |
| rdfs11 (subClassOf transitive) | `(C1 subClass C2) ∧ (C2 subClass* C3)` | `(C1 subClass* C3)` | Recursive Datalog rule |
| rdfs9 (subClassOf instance lift) | `(x type C1) ∧ (C1 subClass* C2)` | `(x type C2)` | Datalog rule invoking rdfs11 |
| rdfs5 (subPropertyOf transitive) | `(p1 subProp p2) ∧ (p2 subProp* p3)` | `(p1 subProp* p3)` | Recursive Datalog rule |
| rdfs7 (sub-property entailment)  | `(x p1 y) ∧ (p1 subProp* p2)` | `(x p2 y)` | Datalog rule invoking rdfs5 |
| prp-trp (transitive closure) | `(p type Transitive) ∧ (x p y) ∧ (y p z)` | `(x p z)` | Recursive Datalog rule |
| prp-symp (symmetric) | `(p type Symmetric) ∧ (x p y)` | `(y p x)` | Datalog rule |
| prp-asyp-violation (integrity) | `(p type Asymmetric) ∧ (x p y) ∧ (y p x)` | violation tuple | Datalog rule (audit surface) |
| prp-irp-violation (integrity)  | `(p type Irreflexive) ∧ (x p x)` | violation tuple | Datalog rule (audit surface) |
| prp-fp-conflict (sameAs surface) | `(p type Functional) ∧ (x p y1) ∧ (x p y2) ∧ y1 ≠ y2` | conflict tuple | Datalog rule (no auto-merge) |
| prp-ifp-conflict (sameAs surface) | `(p type IFP) ∧ (x1 p y) ∧ (x2 p y) ∧ x1 ≠ x2` | conflict tuple | Datalog rule (no auto-merge) |
| prp-inv1 (inverse forward) | `(p inverseOf q) ∧ (x p y)` | `(y q x)` | Datalog rule |
| prp-inv2 (inverse backward) | `(p inverseOf q) ∧ (x q y)` | `(y p x)` | Datalog rule |

A representative encoding — the recursive rdfs11 subClassOf transitive closure:

```clojure
;; Base case: a single declared subclass-of edge.
[(rdfs11-sc ?C1 ?C2)
 [?C1 :dt/subclass-of ?C2]]

;; Inductive case: chain via an intermediate class.
[(rdfs11-sc ?C1 ?C3)
 [?C1 :dt/subclass-of ?C2]
 (rdfs11-sc ?C2 ?C3)]
```

Datomic's Datalog evaluator handles termination on cyclic graphs through set-semantics deduplication — a deliberately-malformed cycle test in `sandbar.db.entailment.core-test` confirms the engine does not hang.

The OWL property-characteristic rules compose through a convenience rule `rdfs9-isa` that unions direct `:dt/type` with derived (via rdfs9-sco) membership — this is what makes the SWCLOS intersection-class pattern compose cleanly with the property rules:

```clojure
[(rdfs9-isa ?x ?C) [?x :dt/type ?C]]
[(rdfs9-isa ?x ?C) (rdfs9-sco ?x ?C)]

;; prp-trp consults rdfs9-isa for property-class membership,
;; so a property typed as :dt/StrictPartialOrderProperty
;; (whose :dt/subclass-of chain includes :dt/TransitiveProperty)
;; satisfies the transitivity precondition through the closure.
[(prp-trp ?x ?p-ident ?y)
 (rdfs9-isa ?p :dt/TransitiveProperty)
 [?x ?p ?y]
 [?p :db/ident ?p-ident]]
```

The composition is the architectural payoff: the intersection-class machinery (a metaclass concern) and the property-characteristic rules (an entailment concern) compose through the *same* `:dt/subclass-of` substrate slot they both already use.  Nothing parallel is required.

## Layer 0 in action

The substrate-transparency commitment (ADR D1, 2026-05-21) means consumers don't see the entailment machinery — they see derived facts arrive in ordinary query results when they pass `all-rules`.  A worked example: declare a transitive predicate and watch the closure surface.

```clojure
;; Declare descends-from as transitive (via the StrictPartialOrder intersection class).
;; The schema already does this in schema/mm.edn.
(mcp__sandbar__sandbar_class_describe :mm.memory/descends-from)
;; => {:dt/type :dt/StrictPartialOrderProperty
;;     :dt/subclass-of-chain
;;       [:dt/StrictPartialOrderProperty
;;        :dt/TransitiveProperty :dt/AsymmetricProperty :dt/IrreflexiveProperty
;;        :dt/Property :dt/Resource]
;;     ...}

;; Assert a chain of ground facts.
(d/transact conn
  [[:db/add :memory/a :mm.memory/descends-from :memory/b]
   [:db/add :memory/b :mm.memory/descends-from :memory/c]
   [:db/add :memory/c :mm.memory/descends-from :memory/d]])

;; Query without entailment — only declared edges.
(d/q '[:find ?ancestor :in $ ?x :where [?x :mm.memory/descends-from ?ancestor]]
     db :memory/a)
;; => #{[:memory/b]}      — only the direct edge

;; Query with entailment — the full closure.
(require '[sandbar.db.entailment.core :as ent])

(ent/apply-entailment db
  '[:find ?ancestor
    :in $ % ?x
    :where (prp-trp ?x :mm.memory/descends-from ?ancestor)]
  ent/all-rules
  :memory/a)
;; => #{[:memory/b] [:memory/c] [:memory/d]}   — closure derived
```

The same pattern surfaces inverse-of bidirectionality: declaring `:mm.tag/broader-match :dt/inverse-of :mm.tag/narrower-match` lets prp-inv1 derive every narrower-match edge from each broader-match assertion (and vice versa via prp-inv2) at query time without write-time materialization.  The substrate stores one direction; consumers see both.

For the entity-facing API, the augmentation point is `sandbar.db.datatype/type-isa?` — `dt/type-isa?` consumes the subsumption closure transparently because its underlying rule (a recursive subclass-of walk) is conceptually equivalent to rdfs11-sc.  Consumers never need to know whether the answer came from a direct `:dt/type` or from rdfs9 entailment up an intersection-class chain.

```clojure
(dt/instance-of? db :dt/TransitiveProperty :mm.memory/descends-from)
;; => true — derived via the :dt/subclass-of chain
;;          :dt/StrictPartialOrderProperty → :dt/TransitiveProperty

(dt/instance-of? db :dt/Property :mm.memory/descends-from)
;; => true — derived through two transitive subclass-of hops
```

## Layered architecture overview

Layer 0 RDFS entailment is the foundational tier of a four-layer rule subsystem (the architecture landed in `plans/layered_rule_subsystem_architecture_arc_2026_05_21.md`).  Each higher layer composes through the substrate-level entailment beneath it:

| Layer | Scope                                               | Owner   | Engine                                  |
|-------|-----------------------------------------------------|---------|-----------------------------------------|
| **Layer 0** — substrate (RDFS / OWL 2 RL)            | `:dt/Class` + `:dt/Property` entities       | Sandbar | Native Datomic Datalog rules            |
| **Layer 1** — user-domain (`:mm/*` invariants)       | Memorial-type instances                      | Sandbar + corpus | Datomic queries + Malli + plain-pattern |
| **Layer 2** — boundary (codec / projection / MCP)    | Filesystem ↔ entity-graph ↔ MCP             | Sandbar | Malli + Datomic queries                 |
| **Layer 3** — client (engagement-specific rules)     | `(actor, context, event)` tuples            | Client  | Datomic queries + plain-pattern         |

Layer 0 changes propagate *up* through entailment — a Layer 1 invariant constraining `:mm/Decision` instances sees Layer 0-derived class memberships, so adding a `:dt/subclass-of` edge under `:mm/Memory` lifts the invariant's reach automatically.  Layer 3 changes do not propagate *down*; backend changes do not propagate up.  The four layers evolve independently with the entailment closure serving as the shared semantic foundation.

A representative rule per layer:

```clojure
;; Layer 0 — substrate entailment.
;; Declared as Datomic Datalog data; lives in sandbar.db.entailment.core.
[(rdfs11-sc ?C1 ?C2) [?C1 :dt/subclass-of ?C2]]
[(rdfs11-sc ?C1 ?C3) [?C1 :dt/subclass-of ?C2] (rdfs11-sc ?C2 ?C3)]

;; Layer 1 — user-domain invariant.
;; Authored as a :mm/Rule memorial; evaluated via Datomic query +
;; the Layer 0 closure (so it sees instances of any subclass of :mm/AntiPattern).
"Every :mm/AntiPattern must :cites at least one :mm/Pattern"

;; Layer 2 — boundary invariant.
;; Lives in sandbar's codec / MCP code; not authored as a memorial.
"MCP tasks/get response must conform to the MCP Task status schema"

;; Layer 3 — client rule.
;; Authored as a :mm/Rule memorial; evaluated at hook activation time.
"PreToolUse on git commit verifies an authorization memorial exists
 in the (actor, context) lattice for the current session"
```

This is the *layered architecture* commitment from `plans/layered_rule_subsystem_architecture_arc_2026_05_21.md` realized concretely.  The Layer 0 substrate is the precondition for the higher layers' regularity: they all see the same closed type and property closure, so they can reason against the substrate without each carrying their own ontology.

## What this enables

The compose-with-the-substrate posture produces specific dividends.

**Uniform query surface for "what does X transitively reach."**  Any predicate declared `:dt/TransitiveProperty` (directly or via an intersection class) supports closure queries through the same `prp-trp` rule.  Adding a new transitive predicate adds zero code — the rule already knows how to handle it.

**Substrate-level validation without a separate engine.**  The asymmetric and irreflexive integrity-check rules (prp-asyp-violation, prp-irp-violation) surface violations as ordinary query results, consumable by `mcp__sandbar__sandbar_class_validate-all-instances` and the audit machinery.  No external constraint validator; the same Datalog substrate that derives also detects.

**Inverse-of bidirectionality without storage doubling.**  Declaring one direction with `:dt/inverse-of` enables both directions through prp-inv1 / prp-inv2 at query time.  The substrate stores half as many edges; queries see all of them.

**Sub-property hierarchies for retrieval-axis composition.**  The corpus's `:related → :mentions → :touches → :cites → :evidences` "cone of strength" (declared via `:dt/subproperty-of` chains in `schema/mm.edn`) means a query for `:related` matches everything below it in the chain — every `:cites` edge is also a `:related` edge via rdfs7 + rdfs5.  This is what makes the typed-edge surface composable rather than balkanized.

**No staleness window.**  Query-time inference is the default (ADR D6).  When a premise is retracted, the next query stops seeing the derivation — no cache invalidation, no materialized-index cleanup, no "wait for the reasoner to re-run."  Selective materialization is available as opt-in for hot-path closures, but the default is always-current.

**SHACL-style shape validation composes through entailment.**  Shape validation (see [`shape-validation.md`](shape-validation.md)) targets classes, and class targeting sees entailed instances via rdfs9 — so a shape declared against `:mm/AntiPattern` validates every memorial that derives membership through its `:dt/subclass-of` chain, not just direct `:dt/type` declarations.

**Babashka-compatible.**  No JVM-only inference library.  The rule-sets are static Clojure data; the engine is Datomic; both port to Asami when the corpus needs pure-Clojure substrate.  The rule syntax does not change across the backend swap.

## What this does not do

A few deliberate restraints worth naming.

The entailment regime is **RDFS plus a curated OWL 2 RL subset** — six core RDFS rules plus eight property-characteristic rules.  The full OWL 2 RL profile (~60 rules) is out of scope; the corpus's predicate vocabulary does not yet need the remainder.

The functional / inverse-functional rules **surface `owl:sameAs` conflicts as query results** but do not auto-merge entities.  Auto-merge is a separate architectural commitment requiring its own ADR.

There is **no built-in support for OWL DL** features beyond what compiles cleanly to Datalog — no existential restrictions, no disjoint-class reasoning, no full description-logic classifier.  The metamodel comparison in [`metamodel.md`](metamodel.md) explains the deliberate stopping-point.

Cardinality of `:dt/type` is **preserved at one** — the SWCLOS-style intersection-class pattern is what makes orthogonal characteristic combinations possible without bumping cardinality.  This is the architectural commitment that lets 212 existing consumer call-sites continue working without change.

## References

**RDFS and RDF semantics**

- Brickley, D. & Guha, R.V. (2014).  *RDF Schema 1.1.*  W3C Recommendation.  https://www.w3.org/TR/rdf-schema/
- Hayes, P. & Patel-Schneider, P.F. (2014).  *RDF 1.1 Semantics.*  W3C Recommendation.  https://www.w3.org/TR/rdf11-mt/

**OWL 2 RL and Datalog compilation**

- Reynolds, D. (2012).  *OWL 2 RL in RIF.*  W3C Working Group Note.  https://www.w3.org/TR/rif-owl-rl/
- Hitzler, P., Krötzsch, M., Parsia, B., Patel-Schneider, P.F. & Rudolph, S. (2012).  *OWL 2 Web Ontology Language Primer (Second Edition).*  W3C Recommendation.  https://www.w3.org/TR/owl2-primer/

**SWCLOS — metaclass property characteristics**

- Koide, S. (2005).  *SWCLOS: A Semantic Web Processor on CLOS.*  Proceedings of the 4th International Semantic Web Conference.

**Datalog and Datomic**

- Hickey, R. (2012).  *Datomic Information Model.*  https://docs.datomic.com/cloud/whatis/data-model.html
- Ceri, S., Gottlob, G. & Tanca, L. (1989).  *What You Always Wanted to Know About Datalog (And Never Dared to Ask).*  IEEE Transactions on Knowledge and Data Engineering, 1(1), 146–166.

**Production-grade reference reasoners (for comparison)**

- Apache Software Foundation (2000–).  *Apache Jena RDFS / OWL Reasoners.*  https://jena.apache.org/documentation/inference/
- Nenov, Y., Piro, R., Motik, B., Horrocks, I., Wu, Z. & Banerjee, J. (2015).  *RDFox: A Highly-Scalable RDF Store.*  ISWC 2015.

## See also

- [`metamodel.md`](metamodel.md) — the SWCLOS-style metaclass pattern and the metacircular core the characteristic classes live in
- [`memory-model.md`](memory-model.md) — the `:mm/*` user-domain layer (Layer 1) that composes through Layer 0 entailment
- [`shape-validation.md`](shape-validation.md) — `:mm/Shape` constraint validation that targets classes through the entailment closure
- [`first-class-fn.md`](first-class-fn.md) — `:dt/Fn` first-class functions; companion substrate-level primitive
- [`workflow-substrate.md`](workflow-substrate.md) — workflow processes are typed entities; their `:dt/type` closure is queryable via rdfs9
- [`navigation.md`](navigation.md) — typed-edge traversal verbs that consume the entailment closure
- [`projection.md`](projection.md) — bidirectional FS↔DB projection that round-trips characteristic-class declarations
- [`doc/api/dt-star.md`](../api/dt-star.md) — `dt/type-isa?` + `dt/instance-of?` are the entity-facing surface for the closure
- [`src/sandbar/db/entailment/core.clj`](../../src/sandbar/db/entailment/core.clj) — the rule implementation
