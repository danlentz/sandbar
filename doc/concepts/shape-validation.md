# Shape validation

> A constraint is useful when its meaning, its targets, and its effect on acceptance are all explicit.

## Thesis

A type tells us what kind of thing an entity is. An application often needs a more specific promise: a decision has a rationale, an interval has exactly one representation of each endpoint, or a reference uses an allowed form. A shape makes that promise inspectable and executable.

Sandbar represents a shape as an entity with a target class and declared constraints. The same definition can be inspected by a client, used to audit existing data, and applied at a write boundary. The benefit is a shared account of what valid data means, together with findings that explain a failure.

Three questions determine that account: what does the shape check, which entities does it check, and what does a failed check do? Keeping them separate makes both the model and the API easier to reason about.

## Class checks and shape checks

Datomic enforces its attribute schema, including storage value types and cardinality. Sandbar's class checks add effective slots, required properties, and modeled ranges. Shapes express further content requirements as reusable data. These checks complement one another.

For example, an attribute declared as a string can contain a URL, a ticket number, or arbitrary prose. A pattern shape can constrain the form required in one application. A required string can still be empty; a nonblank requirement needs an additional check. A shape also cannot make an impossible Datomic value legal.

The Clojure interfaces expose these layers separately. `dt/validate-data` checks a property map against a class; `dt/validate` checks a stored entity and its class validator; `shape/validate` applies shape definitions to a database value. Each call has a precise coverage boundary. See the [`dt/*` reference](../api/dt-star.md) and [shape-authoring guide](../guides/authoring-shapes.md).

## The shape vocabulary

A `:mm/Shape` is a memory with additional slots:

| Slot | Role |
| --- | --- |
| `:mm.shape/shape-id` | A string identifying the shape |
| `:mm.shape/applies-to` | The class whose instances are targeted |
| `:mm.shape/severity` | `:violation`, `:warning`, or `:info` |
| `:mm.shape/required-property` | Properties whose values must be present |
| `:mm.shape/cardinality-constraints` | References to property/count constraints |
| `:mm.shape/pattern-constraints` | References to property/regular-expression constraints |
| `:mm.shape/datatype-constraints` | References to property/value-type constraints |
| `:mm.shape/xor-constraints` | Pairs of properties for which exactly one is populated |
| `:mm.shape/closed?` | Whether undeclared properties are refused |
| `:mm.shape/validator-fn` | An optional function that contributes an additional check |

The built-in walker combines seven check families: required properties, cardinality, patterns, datatypes, closed properties, a custom validator, and exclusive-or constraints. A check with no corresponding constraint contributes no restriction. A document's prose can explain a shape, but a heading saying “Required properties” does not itself create a constraint.

The supported vocabulary is narrower than the full [W3C SHACL specification](https://www.w3.org/TR/shacl/). Sandbar borrows the idea of explicit targets, property constraints, and validation reports, using its own Datomic entities and Clojure evaluator. This is not a claim of SHACL document interchange or complete SHACL conformance.

## What each constraint means

| Check | Meaning |
| --- | --- |
| Required property | A value is present; absence fails |
| Cardinality | The value count lies between the declared bounds; maximum `-1` means unbounded |
| Pattern | Each present value, converted to text, contains a match for the Java regular expression |
| Datatype | Declared value-type check; see the current evaluator limitation below |
| Closed properties | Present properties belong to the shape's allowed set or the always-allowed substrate fields |
| Custom validator | A deployed function contributes a structured result; named-reference resolution needs repair |
| Exclusive-or | Exactly one of two named properties has a value |

Patterns use Java regular expressions. Anchor a pattern with `^` and `$` when the whole value must match. An absent optional value does not fail a pattern or datatype check; combine it with a presence requirement when the value is mandatory.

For a closed shape, the allowed set consists of required properties and properties mentioned in cardinality, pattern, and datatype constraints, plus `:db/id`, `:db/ident`, `:dt/type`, `:dt/context`, and `:dt/label`. It is not automatically the class's effective slot set. Inherited memory metadata therefore needs explicit treatment. Start with an open shape when the goal is to add one requirement to an existing memory type.

Executable support must be established for a declared field before an application relies on it. In particular, the schema's `:mm.shape/value-constraints` vocabulary is not an additional check family in this walker. Use the documented built-in constraints or the supported custom-validator contract.

Two evaluator defects remain in this revision. A datatype constraint can accept a wrong primitive type because its fallback accepts datatype keywords without checking the value. A custom validator referenced by an entity with a `:db/ident` can fail resolution before calling the function. Datomic's storage types still apply, but a passing shape report does not establish the additional datatype requirement. For an acceptance rule, use the demonstrated required, pattern, cardinality, closed-property or exclusive-or checks as appropriate, and prove the negative case. Track the datatype and callback repairs in [known gaps](../known-gaps-0.2.0.md).

## Which entities a shape targets

A shape currently targets the entity's directly declared class: its `:mm.shape/applies-to` must equal that `:dt/type`. A shape targeting `:mm/Decision` is not automatically selected for an instance of a specialized decision class. A class conformance report likewise selects direct instances and exact-class shapes. Every selected shape contributes its constraints.

For a requirement needed on several concrete classes, declare and test a shape for each target. Inherited applicability remains an open design and implementation item; the broader membership supported by `dt/all-instances-of` does not provide it. Keep a subclass negative case in the acceptance checks so a missing target cannot look like successful validation.

Overlapping shapes can impose incompatible requirements. The resulting failures should be diagnosed from the definitions and report. Severity controls how a finding affects a caller; it does not resolve a contradictory model.

## Validation modes

`shape/validate` evaluates an entity against a supplied database value. It does not perform a transaction.

| Mode | Result |
| --- | --- |
| `:audit` | Return the applicable shape results, including failures |
| `:strict` | Throw if any result contains a `:violation`; return warning and informational findings |
| `:disabled` | Skip the shape evaluation and return an empty result vector |

A warning remains a failed check even when the operation is allowed to proceed. An empty vector may mean evaluation was disabled or no shape applied. Neither is evidence that a substantive check passed.

MCP `sandbar_shape_validate` accepts a `mode` argument. The entity mutation tools use `validation-mode`. Consult each tool's schema rather than carrying one tool's argument names into another.

## Boundary integration

MCP entity create and update with `validation-mode: "strict"` evaluate selected shapes against a speculative database before committing. A transactor-side basis guard refuses an intervening database change and retries validation against the new basis. A shape violation leaves the proposed mutation uncommitted and does not send accepted-change notifications or enqueue its derived work. Audit mode may commit and return findings.

That boundary enforces the checks the walker actually performs, with the exact-class targeting and evaluator limitations above. Bulk project import does not run shape validation; audit the imported population explicitly. Class custom validators also have a separate coverage contract in the [`dt/*` reference](../api/dt-star.md#validation).

Post-write validation is useful for an audit and does not undo a transaction. An application that validates and then transacts independently must account for changes between those steps; merely calling the same walker does not reproduce the guarded MCP boundary.

The write API owns this acceptance contract. The shape walker supplies findings over a database value. Calling the walker directly, or using a lower-level transaction API, does not automatically inherit the MCP write boundary's behavior.

## Read reports as evidence of coverage

A one-entity result identifies the entity, shape, status, and failing check details. A class conformance report also records its instance count, shape count, and the number of entity/shape pairs evaluated. Its `total-checks` counts those pairs, not the seven internal check families.

Coverage matters as much as the failure count. A report with zero failures and zero evaluated pairs does not establish conformance of the intended population. Check that the expected instances and shapes were selected, then inspect the failures. Use a known negative example to establish that a new shape actually detects its intended defect.

## Self-description and its limits

Shapes are entities, so shapes can describe constraints on shape definitions and other model declarations. This permits useful checks such as requiring a target or a stable identifier. It does not prove the validator correct, make undeployed code executable, or establish a formal soundness theorem for the system.

The practical test remains concrete: a valid example passes; an invalid example fails for the right reason; the target population is correct; and the chosen mutation boundary enforces the result.

## See also

- [Authoring shapes](../guides/authoring-shapes.md): build and exercise a small constraint.
- [Defining classes](../guides/defining-new-classes.md): define properties and inherited structure.
- [Inference and entailment](rdfs-entailment.md): how type and property conclusions are derived.
- [Memory model](memory-model.md): the knowledge the constraints are meant to preserve.
