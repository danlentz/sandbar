# The Sandbar Metamodel

> A theoretical reference for Sandbar's RDFS-inspired type system layered on Datomic.  Explains the lineage (RDFS / KL-ONE / frames / CLOS metaobject protocol), the metacircular core, and the role Datomic plays as substrate.  For the mechanical `dt/*` API surface see [`doc/api/dt-star.md`](../api/dt-star.md); for hands-on use see [`doc/guides/defining-new-classes.md`](../guides/defining-new-classes.md).

## Thesis

Sandbar's metamodel is **a metacircular RDFS-style class system stored inside the database it describes**.  The same primitives that define `:order/Order` define `:dt/Class` itself.  Schema is data; data is queryable through the same API; the type system is evolvable at runtime by the same operations that produce application entities.

This is not a novelty.  It is the precondition for everything else Sandbar does — bootstrap-by-discovery, multi-protocol projection, validation-as-workflow.  Each higher layer can introspect the metamodel because the metamodel introspects itself.

## Lineage

The design draws from four traditions, deliberately.  None alone is sufficient.

### RDFS (Resource Description Framework Schema)

The vocabulary — *class*, *property*, *domain*, *range*, *subClassOf*, *type* — is taken almost wholesale from RDFS 1.1 (Brickley & Guha 2014).  RDFS gave the world a clean, minimal kit for declaring class hierarchies and predicate-shaped properties with named applicability and value-type constraints.  Sandbar's `:dt/subclass-of`, `:dt/domain`, `:dt/range`, and `:dt/type` are direct analogues of `rdfs:subClassOf`, `rdfs:domain`, `rdfs:range`, and `rdf:type`.

| RDFS 1.1                  | Sandbar              | Notes                                             |
|---------------------------|----------------------|---------------------------------------------------|
| `rdfs:Resource`           | `:dt/Resource`       | Root of the class hierarchy                       |
| `rdfs:Class`              | `:dt/Class`          | Class of all classes                              |
| `rdf:Property`            | `:dt/Property`       | Class of all predicates                           |
| `rdf:type`                | `:dt/type`           | Class-of relation                                 |
| `rdfs:subClassOf`         | `:dt/subclass-of`    | Class inheritance                                 |
| `rdfs:domain`             | `:dt/domain`         | Where the property applies                        |
| `rdfs:range`              | `:dt/range`          | The property's value type                         |
| `rdf:List` (cons-cells)   | `:dt/List`           | Inherited form; Sandbar prefers pairwise siblings |

What Sandbar **does not** take from RDFS wholesale is the open-world assumption.  Sandbar is closed-world and ground-truth-respecting: if it isn't in the database, it isn't.  But the *entailment regime* — the SWCLOS-style metaclass + property-characteristic pattern that materializes RDFS+OWL2RL inferences — IS implemented, composing through Datomic-native recursive Datalog rules without an external reasoner.  See [`rdfs-entailment.md`](rdfs-entailment.md) for the metaclass-driven entailment substrate (Layer 0 of the layered rule subsystem).  RDFS semantics in the strict sense (Hayes & Patel-Schneider 2014) is now an *implementation reference*, not merely a *theoretical reference point*.

### KL-ONE and frame systems

The shape of `:dt/Class` — a definition with slots that constrain instances — is older than RDFS.  Brachman & Schmolze (1985) describe KL-ONE as a structured inheritance network of *concepts* (classes) with *roles* (slots) bearing type restrictions and number restrictions.  Earlier still, Minsky (1974) and the FRL system (Roberts & Goldstein 1977) gave us the *frame* — a structured representation with named slots whose values are typed or defaulted.

Sandbar's `:dt/slots` declaration on a class, with each slot's `:dt/range` constraining its value type and `:dt/required?` constraining its presence, is recognizably frame-shaped — closer to KL-ONE-derived frames-with-inheritance than to OWL DL's description-logic concepts.

The Cyc project (Lenat & Guha 1990) and Protégé/Ontolingua-era frame systems carried this lineage forward; CLOS's metaclass facility (Bobrow et al 1988) is a Lisp realization of the same shape.

### Datomic schema-on-read

The substrate is Datomic (Hickey, 2012-present).  Datomic stores atomic facts ("datoms") of the form `[entity attribute value transaction op]`.  Schema is data: defining a new attribute means transacting a datom that says "this ident has this `:db/valueType` and this `:db/cardinality`".  Schema lives in the same store as data, queryable through the same Datalog, evolvable through the same transaction API.

This property — *schema is data; schema definitions and instance data live in the same fact store* — is what makes the metamodel metacircular in a literal sense, not just a slogan.  When Sandbar declares `:dt/Class` to be a class whose instances are classes, it does so by transacting datoms that the database treats exactly like datoms about `:order/Order`.

### CLOS metaobject protocol

The metaobject protocol (Kiczales, des Rivières & Bobrow 1991) is the design discipline that says: *the language's object system should itself be implemented in the object system, and that implementation should be a stable extension point*.  The protocol exposes hooks like `compute-class-precedence-list`, `slot-value-using-class`, and `validate-superclass` — operations on the *metaclass* of a class.

Sandbar does not implement a metaobject protocol in CLOS's full sense — there is no `compute-effective-method` analogue — but it shares the discipline: the introspection API (`dt/all-classes`, `dt/slots-of`, `dt/ancestors-of`, `dt/instance-of?`, etc.) is itself implemented in terms of queries over `:dt/Class` and `:dt/Property` entities.  Walking the metamodel uses the same `dt/*` calls as walking application entities.  Adding a new class is `dt/make :dt/Class {...}`.

## The metacircular core

Three statements define the metacircularity.

**1. `:dt/Class` is an instance of `:dt/Class`.**

```clojure
(dt/instance-of? :dt/Class :dt/Class)
;; => true
```

This isn't a quirk of bootstrap order — it is the literal database state.  The entity `:dt/Class` has a `:dt/type` attribute whose value is `:dt/Class`.  When `dt/all-instances-of` walks the class, it finds the class itself among the results.

**2. `:dt/Property` is an instance of `:dt/Class`, and `:dt/type` is an instance of `:dt/Property`.**

Every property — including `:dt/type` itself, including `:dt/subclass-of`, including `:dt/slots` — is a `:dt/Property` instance with its own `:dt/domain` and `:dt/range`.  Predicates describing the type system are themselves typed members of the type system.

**3. Adding a class is a transaction; introspecting the schema is a query.**

```clojure
;; Adding the Order class — same shape as adding any entity
(dt/make :dt/Class
  {:db/ident :order/Order
   :dt/subclass-of :dt/Resource
   :dt/slots [:order/customer :order/total :order/status]})

;; Introspecting the schema — same query API as for any entity
(dt/slots-of :order/Order)         ; => #{:order/customer :order/total ...}
(dt/instance-of? :dt/Class :order/Order)  ; => true
(d/q '[:find ?c :where [?c :dt/type :dt/Class]] (d/db conn))
```

The consequence: **higher layers do not need a separate schema description format**.  An MCP `tools/list` walks `dt/all-classes` and reflects JSON Schema from `:dt/range`.  A REST OpenAPI document is the same walk, projected differently.  Adding a class adds the surface — there is no parallel registry, no mapping table, no server restart.

## Core primitives

Six core idents anchor the metamodel.  Every other class derives transitively from these.

| Ident            | Role                                                                                  |
|------------------|---------------------------------------------------------------------------------------|
| `:dt/Resource`   | Root of the class lattice.  Everything that has a `:dt/type` is a Resource.           |
| `:dt/Class`      | Class of all classes.  `:dt/type :dt/Class` ⇒ this entity is a class definition.      |
| `:dt/Property`   | Class of all predicates.  `:dt/type :dt/Property` ⇒ this entity is a predicate.       |
| `:dt/Fn`         | Class of all first-class functions.  `:dt/type :dt/Fn` ⇒ this entity is a function definition (compiled body persisted at the schema layer; sibling of `:dt/Class` / `:dt/Property` under `:dt/Resource`). |
| `:dt/List`       | Inherited RDFS cons-cell list shape (`:dt/first` / `:dt/rest`).                       |
| `:dt/Literal`    | Abstract superclass of scalar types.  Bridges Datomic's `:db.type/*` value types.     |

A class definition is just an entity with `:dt/type :dt/Class` and a vector of `:dt/slots` referencing `:dt/Property` entities.  Inheritance is the transitive closure of `:dt/subclass-of`.  Effective slots are the union of declared slots over the ancestor chain.

### Memorial-level classes

The application-facing memorial namespace `:mm/*` sits on top of the substrate.  Every memorial class descends from `:mm/Memory`.  Six concrete memorials matter for substrate-level orientation:

| Ident            | Role                                                                                  |
|------------------|---------------------------------------------------------------------------------------|
| `:mm/Activity`   | Abstract PROV-O `prov:Activity`-shaped supertype (under `:mm/Artifact`).  Concrete subclasses include `:mm/Log` (session/handoff chronicle), `:mm/EventLog` (per-event-firing memorial), `:mm/Run` (scheduler/job execution).  See [`activity-hierarchy.md`](activity-hierarchy.md). |
| `:mm/Fn`         | Memorial-face for first-class functions.  Documents the body / signature / intent.  Companion to substrate-level `:dt/Fn`.  See [`first-class-fn.md`](first-class-fn.md).  |
| `:mm/Shape`      | First-class shape/constraint memorial.  Mirrors SHACL `sh:NodeShape` / `sh:PropertyShape` vocabulary.  See [`shape-validation.md`](shape-validation.md). |
| `:mm/Rule`       | First-class rule memorial.  Carries Datalog rule body + activation policy for the layered rule subsystem.  |
| `:mm/Workflow`   | Workflow-definition memorial-classifier (under `:mm/Meta`); see [`workflow-substrate.md`](workflow-substrate.md). |
| `:mm/Event`      | Event-classifier memorial (one per lifecycle position).  Distinct from `:dt/Event` substrate-runtime hierarchy and `:mm/EventLog` per-firing memorial.  See [`event-substrate.md`](event-substrate.md). |

The convention: memorial-classifiers use the `:mm/*` ident; substrate-runtime hierarchies use their own domain namespace (`:dt/*`, `:event/*`, `:workflow/*`).  See `decisions/memorial_class_naming_convention_mm_prefix_workflow_definition_to_mm_workflow_2026_05_23.md`.

## Validation as a class concern

Validation is not a separate schema language.  A class definition is the schema.  `dt/validate-data` traverses:

1. **Class existence** — the named class must resolve to a `:dt/Class` entity.
2. **Abstract check** — `:dt/abstract?` classes cannot be directly instantiated.
3. **Required slots** — `:dt/required? true` properties must be present.
4. **Range checking** — slot values must conform to the property's `:dt/range`.
5. **Custom validators** — `:dt/validator` may name a fully-qualified symbol for class-specific predicate logic.

Validation results are pure data — `{:errors [...]}` — never thrown unless the API explicitly says so.  This is what makes validation composable with workflows: the *validation-as-workflow* pattern (see [`workflow-substrate.md`](workflow-substrate.md)) wraps these per-instance checks in a state machine whose terminal state classifies the outcome.

## Comparison with adjacent systems

### OWL DL

OWL DL (Hitzler et al 2012) sits one level up in expressive power: existential restrictions, disjoint classes, full description-logic reasoning.  Sandbar deliberately stops short.  The cost of OWL DL is a reasoner; the value of stopping is that closed-world queries are immediate and the runtime is the Datomic runtime, not a separate inference layer.  Sandbar's metamodel could be projected into OWL with loss; the inverse projection would require encoding closed-world commitments that OWL does not naturally express.

### JSON Schema

JSON Schema (Wright et al, draft-2020-12) is structural and acyclic — it describes the shape of a tree.  Sandbar's metamodel is relational — it describes nodes in a graph, with `:dt/Property` instances first-class.  An MCP `tools/list` reflects per-class JSON Schema from `:dt/range` on demand; the canonical declaration is the class definition, not the schema fragment.

### GraphQL SDL

GraphQL SDL (Facebook 2015) shares Sandbar's "schema is queryable" property — the introspection query is built into the protocol.  GraphQL's typing is structural and statically resolved against types; Sandbar's typing is data and is resolved against `:dt/Class` entities at query time.  The two are complementary projections of the same idea.

### Datomic alone

Datomic gives you `:db.type/*` for value types, `:db/cardinality` for multiplicity, `:db/unique` for keys.  It does not give you classes, inheritance, slot inheritance, or required-property semantics.  Sandbar's metamodel adds those *on top* of Datomic without replacing anything: every `:dt/Property` is also a Datomic attribute; native `:db/doc` and `:db/ident` are incorporated as properties with appropriate domain/range.

## How extensions land

A new domain class is a single transaction:

```clojure
(dt/make :dt/Class
  {:db/ident :inventory/Item
   :dt/subclass-of :dt/Resource
   :dt/slots [:inventory/sku :inventory/quantity]})
```

After the transaction commits:

- `dt/all-classes` includes `:inventory/Item`.
- MCP `tools/list` includes `sandbar.entity.create` resolved for `:inventory/Item`, with a JSON Schema reflected from the slot ranges.
- REST `/api/store/classes/inventory/Item/slots` returns the slot list.
- A future RDF/TTL projection lists `:inventory/Item` as `:rdfs:Class`.
- Validation against `:inventory/Item` is immediately available.

No code change.  No restart.  No registration step.  This is what *bootstrap-by-discovery* means concretely — the protocol surface is a function of the metamodel state, recomputed on each call.

## Aggregate classes (`T*`, `T**`)

For any class `T`, the system automatically derives `T*` (1D aggregate of T-instances) and `T**` (2D aggregate).  These are useful when modeling collection-typed slots without committing to a specific collection class (RDFS's `rdf:Bag` / `rdf:Seq` / `rdf:List`).  Aggregate classes are first-class members of the hierarchy and participate in slot inheritance.  See [`doc/api/dt-star.md`](../api/dt-star.md) for the mechanical access pattern.

## References

**RDFS / RDF**

- Brickley, D. & Guha, R.V. (2014). *RDF Schema 1.1.* W3C Recommendation.  https://www.w3.org/TR/rdf-schema/
- Hayes, P. & Patel-Schneider, P.F. (2014). *RDF 1.1 Semantics.* W3C Recommendation.  https://www.w3.org/TR/rdf11-mt/
- Cyganiak, R., Wood, D. & Lanthaler, M. (2014). *RDF 1.1 Concepts and Abstract Syntax.* W3C Recommendation.  https://www.w3.org/TR/rdf11-concepts/

**Frame systems and KL-ONE lineage**

- Minsky, M. (1974). *A Framework for Representing Knowledge.* MIT-AI Laboratory Memo 306.
- Roberts, R.B. & Goldstein, I.P. (1977). *The FRL Primer.* MIT AI Memo 408.
- Brachman, R.J. & Schmolze, J.G. (1985). *An Overview of the KL-ONE Knowledge Representation System.* Cognitive Science, 9(2), 171–216.
- Lenat, D.B. & Guha, R.V. (1990). *Building Large Knowledge-Based Systems: Representation and Inference in the Cyc Project.* Addison-Wesley.

**Metaobject protocols and metacircularity**

- Bobrow, D.G., DeMichiel, L.G., Gabriel, R.P., Keene, S.E., Kiczales, G. & Moon, D.A. (1988). *Common Lisp Object System Specification.* ANSI X3J13.
- Kiczales, G., des Rivières, J. & Bobrow, D.G. (1991). *The Art of the Metaobject Protocol.* MIT Press.

**Description logic and OWL (for contrast)**

- Hitzler, P., Krötzsch, M., Parsia, B., Patel-Schneider, P.F. & Rudolph, S. (2012). *OWL 2 Web Ontology Language Primer (Second Edition).* W3C Recommendation.  https://www.w3.org/TR/owl2-primer/
- Baader, F., Calvanese, D., McGuinness, D., Nardi, D. & Patel-Schneider, P.F. (eds.) (2007). *The Description Logic Handbook.* Cambridge University Press.

**Datomic semantics**

- Hickey, R. (2012). *The Datomic Information Model.* https://docs.datomic.com/cloud/whatis/data-model.html
- Hickey, R. (2012). *The Database as a Value.* Strange Loop talk.

## See also

- [`codec-layer.md`](codec-layer.md) — how the wire-format boundary is absorbed by the same metamodel
- [`projection.md`](projection.md) — Anderson-lineage bidirectional FS↔DB projection of the metamodel
- [`memory-model.md`](memory-model.md) — the `:mm/*` user-domain layer that sits on the `:dt/*` substrate
- [`rdfs-entailment.md`](rdfs-entailment.md) — SWCLOS-style metaclass entailment over the same metamodel
- [`first-class-fn.md`](first-class-fn.md) — `:dt/Fn` + `:mm/Fn` first-class function substrate
- [`shape-validation.md`](shape-validation.md) — `:mm/Shape` SHACL-mirrored constraint memorial
- [`workflow-substrate.md`](workflow-substrate.md) — workflows + processes are themselves typed metamodel entities
- [`activity-hierarchy.md`](activity-hierarchy.md) — PROV-O Activity supertype unifying logs, runs, event-logs, workflow processes
- [`mcp-protocol.md`](mcp-protocol.md) — how the MCP surface bootstraps from `dt/all-classes`
- [`doc/api/dt-star.md`](../api/dt-star.md) — mechanical API reference
- [`doc/guides/defining-new-classes.md`](../guides/defining-new-classes.md) — hands-on how-to
