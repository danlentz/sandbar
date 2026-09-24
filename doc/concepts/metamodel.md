# The Sandbar metamodel

> A model becomes more useful when programs can inspect and extend it using the same operations they use for ordinary data.

## Thesis

An application needs more than values. It needs to know which kinds of things those values describe, how they relate, and which operations make sense for them. Sandbar keeps that knowledge in the database as classes, properties, and executable declarations. Clients can discover the model instead of carrying a separate, incomplete copy of it.

This is why the metamodel matters. A new kind of decision can inherit the vocabulary of a memory, appear in broader queries, and be inspected through an existing client. The consequences of declaring the class follow through the system.

## The small vocabulary at the center

| Element | What it describes | Example |
| --- | --- | --- |
| `:dt/Class` | A kind of entity | `:mm/Decision` |
| `:dt/Property` | A named attribute or relationship | `:mm.memory/name`, `:mm.memory/cites` |
| `:dt/type` | An entity's declared class | A particular decision has type `:mm/Decision` |
| `:dt/subclass-of` | An inheritance relationship | A specialized decision belongs to a broader decision class |
| `:dt/slots` | Properties declared for a class | The metadata and relationships its instances use |
| `:dt/domain` and `:dt/range` | The modeled subjects and values of a property | A citation connects a memory to another resource |

These declarations are themselves entities. `:dt/Class` is described using the same vocabulary as another class. Properties have descriptions and types; classes can be queried for their slots and ancestors. That self-description is the concrete meaning of *metacircular* here.

Datomic supplies immutable database values, transactions, attribute schema, indexes, and Datalog queries. Sandbar adds its class model, inherited vocabulary, typed operations, and application-facing protocols. Datomic still enforces its own value-type and cardinality rules; the metamodel builds on those guarantees. See [Datomic's schema reference](https://docs.datomic.com/schema/schema-reference.html) for the underlying schema and entity-specification facilities.

## Inference connects a declaration to its consequences

Suppose `:mm.example/EngineeringDecision` is a subclass of `:mm/Decision`. A particular engineering decision is also a memory, because the class hierarchy connects decisions to `:mm/Memory`.

Sandbar uses recursive Datalog rules to derive these relationships. `dt/all-instances-of` includes inherited membership. `dt/slots-of` combines declared slots with inherited slots. `dt/type-isa?` answers the class-subsumption question used by consumers such as codecs and projection. These are ordinary runtime operations that depend on inference.

This gives callers a useful choice:

```clojure
(dt/direct-instances-of :mm/Memory) ; declared directly as Memory
(dt/all-instances-of :mm/Memory)    ; includes instances of subclasses
```

An entity need not acquire a second stored `:dt/type` assertion for the broader query to include it. The rule supplies that conclusion at query time. Conversely, an arbitrary raw data pattern only matches the facts it asks for; passing through Datomic does not automatically apply every rule Sandbar knows.

The [inference chapter](rdfs-entailment.md) distinguishes the rules used by these core APIs from the additional RDFS/OWL property rule library. That distinction explains both how inheritance supports everyday operations and how an application can request more specific entailments.

## A class extension in practice

In a development database with Sandbar's schema loaded, this introduces a specialized decision class and one instance. All names and content in this example are fictional.

```clojure
(require '[sandbar.db.datatype :as dt])

(dt/make :dt/Class
  {:db/ident :mm.example/EngineeringDecision
   :dt/subclass-of :mm/Decision})

(dt/make :mm.example/EngineeringDecision
  {:db/ident :memory.examples/cache-refresh
   :mm.memory/name "Refresh the cache after a write"
   :mm.memory/description "Readers should see newly accepted decisions."
   :mm.memory/memory-type :decision
   :mm.memory/scope :project})

(contains? (dt/slots-of :mm.example/EngineeringDecision) :mm.memory/name)
;; => true

(contains? (set (dt/named-idents-of :mm/Memory))
           :memory.examples/cache-refresh)
;; => true
```

The new class inherits the decision's effective slots, including the memory name. A query for memories includes its instance. An MCP client can inspect the class with `sandbar_class_describe` and read the instance with `sandbar_entity_find`, using the same tools it already used for existing classes. The tool catalog itself remains stable. This example extends the memory-model namespace exposed by the MCP read surface; an independent domain namespace also needs an explicit exposure policy when integrating it with clients.

New properties need their own declarations, including the underlying Datomic attribute schema. Defining a class that refers to undeclared slots does not invent those properties. The [class-authoring guide](../guides/defining-new-classes.md) develops the complete extension process, including naming, validation, and use from a client.

For deployment, load the schema before using the new model. Creating or changing classes in an already running process can leave cached `type-isa?` and descendant answers stale even when fresh instance and slot queries see the change. Dynamic cache invalidation remains a [known gap](../known-gaps-0.2.0.md); the example above demonstrates inherited slots and fresh membership queries, not a fully coherent hot schema update.

## The memory model is an application of the metamodel

The `:mm/*` vocabulary describes durable knowledge: decisions, observations, plans, references, tags, and other artifacts. A typed edge can distinguish citation, evidence, supersession, or containment. That distinction gives retrieval more to work with than a generic “related” link.

The same layer can describe functions, shapes, rules, workflow definitions, schedules, and runs. A function can carry a signature and implementation information; a shape can name the entities it checks; a run can connect an execution to its plan and outcome. Each declaration's consumer determines how it executes. Merely storing an entity with an evocative class name does not schedule or invoke it.

The [memory model](memory-model.md) explains these application concepts. [Functions](first-class-fn.md), [rules](first-class-rule.md), and [workflows](workflow-substrate.md) explain the execution boundaries. Keeping the layers connected lets a reader move from a piece of knowledge to the process or evidence that produced it.

## Inference, validation, and authorization have different jobs

Inference derives conclusions from facts and rules: this specialized decision is a memory; this declared relation has an inverse. Validation asks whether data satisfies a contract: a required value is present, a reference has an acceptable type, or a shape's predicate succeeds. Authorization asks whether a caller or flow is permitted.

These operations share the model, but one does not replace the others. A derived type membership is useful input to a check. A successful inference query does not authorize disclosure. A property declared as symmetric does not, by itself, mean a write was checked for every applicable invariant.

Sandbar exposes class-level checks and separately modeled shapes. The write API specifies which checks run and how they affect acceptance. MCP create/update in strict mode reject selected shape violations before committing; audit mode can commit and return findings. The [shape chapter](shape-validation.md) explains exact-class target selection, current evaluator gaps, and the separate lower-level APIs.

## Representations share the model

The model also helps clients interpret representations. A codec uses declared types and slots to relate a document or wire value to entity specifications. Projection groups supported entities into readable files. MCP describes operations and exposes schema inspection. These boundaries let applications use the model through different representations without recreating its vocabulary in every client.

Each boundary still has an explicit contract. A document representation preserves a specified subset of data; a tool exposes named arguments and result shapes; a query selects facts and rules. Keeping those contracts visible makes extension predictable.

## Semantic foundations

Sandbar uses the class/property/subclass vocabulary associated with [RDF Schema](https://www.w3.org/TR/rdf-schema/), implemented over its own Datomic model. Its inference support includes selected RDFS and OWL property rules. The exact supported rules and their query behavior are documented in [Inference and entailment](rdfs-entailment.md).

The value of this foundation is practical: a declaration participates in inspection, inherited behavior, retrieval, and extension. The following pages show those consequences in more detail.

- [Memory model](memory-model.md): durable knowledge and meaningful relationships.
- [Inference and entailment](rdfs-entailment.md): what the rules derive and how queries use them.
- [Shape validation](shape-validation.md): checking data against declared constraints.
- [MCP](mcp-protocol.md): discovering and using the model from a client.
- [Projection](projection.md): relating entities to readable documents.
- [`dt/*` reference](../api/dt-star.md): precise operation signatures.
