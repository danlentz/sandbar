# Inference and entailment

> A declared relationship is useful when the system can derive its consequences. In Sandbar, Datalog rules make those consequences available to queries and to the APIs built on them.

## Thesis

A specialized decision is still a decision. A slot inherited from a parent class belongs in the child's effective vocabulary. A transitive relationship can connect entities through several intermediate steps. Applications should be able to ask these questions using the declarations they have already made.

Sandbar includes rule-based inference for this purpose. Datomic evaluates its Datalog rules. Core metamodel operations use recursive rules for membership and inheritance, and an additional rule library provides selected RDFS and OWL property entailments. Inference is part of the running system's behavior, including operations that appear to be simple class inspection or retrieval.

The important distinction is which facts and rules a particular operation uses. An ordinary data-pattern query reads assertions. A rule-aware query can also derive answers. Those answers become stored facts only if an operation explicitly writes them.

## The inference used by everyday operations

Consider the specialized decision in the [metamodel example](metamodel.md):

```text
EngineeringDecision → Decision → … → Memory
         ↑
   cache-refresh
```

The entity declares its specialized class. The inheritance path supplies the broader memberships.

| Operation | Question answered | Rule behavior |
| --- | --- | --- |
| `dt/direct-instances-of` | Which entities declare this class directly? | Matches the declared `:dt/type` |
| `dt/all-instances-of` | Which entities belong to this class, including its subclasses? | Recursively follows class inheritance |
| `dt/slots-of` | Which properties are available to this class? | Combines direct slots with inherited slots |
| `dt/type-isa?` | Is one class this class or a subclass of it? | Uses the class hierarchy's transitive relation |

A search over `:mm/Memory` needs the inherited population rather than only entities declared directly as `:mm/Memory`. A document codec needs to recognize an instance of a specialized memory class. Effective-slot inspection needs to include parent properties. These uses explain why inference belongs in the architecture's main account.

Core rules are defined alongside the metamodel API in `sandbar.db.datatype`. They are separate from the named standards-oriented rule sets in `sandbar.db.entailment.core`; the two should not be treated as one implicit rule set attached to every query.

## The additional entailment library

`sandbar.db.entailment.core` supplies rules as Clojure data. A caller passes the selected rule set to a Datalog query through its `%` input. `apply-entailment` is a convenience wrapper for that invocation.

With the metamodel example loaded, this query asks for memories using the library's direct-or-inherited membership relation:

```clojure
(require '[sandbar.db.datomic :as db]
         '[sandbar.db.entailment.core :as ent])

(ent/apply-entailment
  (db/db)
  '[:find [?ident ...]
    :in $ %
    :where
    (rdfs9-isa ?entity :mm/Memory)
    [?entity :db/ident ?ident]])
;; The result includes :memory.examples/cache-refresh.
```

The named relations make the inference requested by this query visible. This is especially useful when a caller needs an explicit subset, wants to compare asserted and derived answers, or is diagnosing the effect of a model change.

The RDFS-oriented rules cover:

| Rule family | Consequence |
| --- | --- |
| Domain and range | A property's assertion supports a subject or object membership conclusion from its declared domain or range |
| Subclass closure | A subclass path connects a specialized class to a broader one |
| Instance lifting | An instance of a subclass also belongs to the superclass |
| Subproperty closure | A property can be related to its transitive superproperties |
| Subproperty entailment | An assertion using a specialized property supports the corresponding broader relationship |

This is a specified fragment of RDFS-style reasoning over Sandbar's model. Its named rules define the available derivations; they do not establish complete RDF Schema conformance or a global closure over every possible rule combination. The vocabulary and semantic reference are [RDF Schema](https://www.w3.org/TR/rdf-schema/) and [RDF 1.1 Semantics](https://www.w3.org/TR/rdf11-mt/).

## Property characteristics are declarations too

A property can be an instance of a characteristic class such as `:dt/TransitiveProperty` or `:dt/SymmetricProperty`. Combined characteristic classes inherit from several such classes. The rule library uses inherited membership to recognize those characteristics.

For example, `:mm.memory/related` has a symmetric characteristic. Given an asserted relationship from the cache decision to a measurement, a symmetry query can derive the reverse direction. The reverse need not exist as a second stored edge.

The selected property rules have three distinct kinds of result:

| Kind | Supported cases | Meaning of the result |
| --- | --- | --- |
| Derived relationships | Transitivity, symmetry, inverse properties | Another relationship follows under the selected rule |
| Integrity findings | Asymmetry, irreflexivity | Assertions exhibit a forbidden pattern |
| Identity conflicts | Functional and inverse-functional properties | Multiple values or subjects need an explicit identity decision |

A conflict result does not merge two entities. An integrity finding does not itself reject a transaction. A caller that uses those findings for validation or repair must define that behavior at the relevant boundary. [OWL 2 RL](https://www.w3.org/TR/owl2-profiles/#OWL_2_RL) provides the semantic reference for these selected property rules.

Selecting several named predicates does not make each predicate consume every conclusion of the others. For a symmetric, transitive property with assertions A→B and A→C, the current symmetry and transitivity queries do not by themselves compute the combined closure that would include B→C. Use the derivation promised by the selected predicate; an application needing closure across rule families must establish that additional contract.

## Inference and validation cooperate

Inference asks what follows from declarations and facts. Validation asks whether an entity or proposed mutation satisfies an acceptance contract. A derived type membership can help check the range of a reference, but the inference rule and the write-rejection policy remain distinct.

This distinction also applies to shapes. A class hierarchy may make an entity eligible for a broad query, while shape applicability follows the shape system's own target-selection rules. The [shape chapter](shape-validation.md) specifies those rules and the difference between strict and audit modes. Neither a standards citation nor the presence of a rule in a library proves that a particular write path runs it.

## Database values and freshness

Datomic queries run against a database value. Rules derive their answers from the facts in that value. If a later transaction retracts a premise, a query against the newer value can have a different answer; the earlier value still represents its earlier state.

Derived answers computed at query time do not require a separate pass that persists all conclusions. Sandbar does cache some useful derived structures, including class relations and search populations. Those caches must be invalidated when their premises change. This is a dependency of correct retrieval, and belongs in the same correctness contract as the rule itself.

Full configured schema loading invokes registered cache-clear callbacks; single-file loading and ordinary class/inheritance mutations do not yet invalidate every cached type relation. After a hot model change, a fresh instance query can include a subclass that `type-isa?` still fails to recognize. Load the complete schema before application work; keep hot schema mutation outside a correctness claim until its invalidation is repaired and tested. This is a cache defect, not a limitation of the recursive rule itself.

For this reason, distinguish three observations when diagnosing a result: the asserted facts at a database basis, the selected rule query's answer, and any cached or projected view used by the caller. The [reactive chapter](reactive-substrate.md) explains propagation to derived views.

## Working with the rule boundary

Use the core `dt/*` APIs when their membership and inheritance semantics answer the question. Use an explicit entailment query when you need a named property rule or a particular derivation. Inspect both the rule and the input facts when explaining a result.

For larger application policies, the [first-class rule model](first-class-rule.md) describes rule declarations and their execution context. That model is related to this substrate inference layer, but storing a rule declaration and evaluating a Datalog rule set are distinct operations.

Further reading: [metamodel](metamodel.md), [`dt/*` reference](../api/dt-star.md), [shape validation](shape-validation.md), and [Datomic's query and rule reference](https://docs.datomic.com/query/query-data-reference.html).
