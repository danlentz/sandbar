# Rules: make an instruction inspectable before applying it

**An instruction is easier to understand and govern when its meaning, scope, and implementation are explicit data.** Sandbar can store a rule alongside the evidence and rationale that motivated it. A consumer can retrieve it, decide whether it applies, and use a specified execution mechanism.

The model builds on [first-class functions](first-class-fn.md). `:dt/Rule` is a specialization of the function vocabulary for inference-producing work. `:mm/Rule` inherits from `:mm/Fn` and adds application-facing dispatch metadata. Inheritance makes the record queryable through its parent classes; it does not require storing every inherited type as another `dt/type` assertion.

## Rule description and application are separate

A rule description answers what it means and where it is intended to apply. An execution strategy answers when to select it, how to invoke it, what a result means, and what to do on failure. Keeping these questions separate permits a rule to be inspected without running it.

The current rule vocabulary includes:

| Property | Stored shape and role |
| --- | --- |
| `:mm.rule/event` | One keyword identifying the triggering event |
| `:mm.rule/enforcement` | A keyword describing the intended enforcement disposition |
| `:mm.rule/actor` | A keyword identifying an actor or a consumer-defined wildcard |
| `:mm.rule/context` | A string naming a context or a matching expression |
| `:mm.rule/priority` | A long integer used by a selecting consumer |
| `:mm.rule/engine` | A keyword naming an interpretation mechanism |
| `:mm.rule/dispatch-strategy` | A keyword describing selection/application strategy |
| `:mm.rule/lifecycle` | A keyword describing the rule's lifecycle |

These shapes matter. The actor property is not a typed actor reference in this schema, and context is not a set of graph edges. Consumers must define the interpretation of their labels and expressions. A stored `engine` name is not proof that an adapter for that engine is installed.

## Three different uses of “rule”

Sandbar contains several related mechanisms with different contracts:

1. **Datomic query rules** describe reusable or recursive query relations. The substrate's class/inheritance queries use them.
2. **The entailment engine** evaluates its supported rule fragment for the model. This is implemented, load-bearing inference; see [RDFS entailment](rdfs-entailment.md).
3. **Application rule records** carry instructions and dispatch metadata. Their application depends on a consumer that understands that metadata and invokes an implementation.

Creating an application rule record does not automatically register it with the event bus, compile its body into every available rule language, or grant it permission to reject transactions. Likewise, a shape's declared enforcement mode needs the [validation boundary](shape-validation.md) that actually implements it.

## Define the boundary an application can rely on

For an executable rule, document the selector and entry point, the accepted body format, inputs and output, effects, and failure behavior. Test an applicable case, an inapplicable case, a violated rule, and a failed evaluator. Confirm that a `block` result is observed by the operation that must be blocked, before that operation commits effects.

For a descriptive rule, make that role clear: it may guide a person or a client without participating in automated enforcement. This is still useful first-class knowledge. It preserves the rationale, can be connected to examples and successors, and can be found through ordinary [search](fulltext-search.md) and [navigation](navigation.md).

The schema deliberately leaves room for more execution strategies. A release's supported strategies must be established by its adapters and tests, rather than inferred from the breadth of the vocabulary. The model declarations live in [`schema/fn.edn`](../../schema/fn.edn), [`schema/mm.edn`](../../schema/mm.edn), and [`schema/mm-meta.edn`](../../schema/mm-meta.edn).
