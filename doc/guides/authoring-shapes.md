# Authoring shapes

This guide creates a shape requiring a decision to have a nonblank description, then checks both a valid and an invalid example. Use a disposable development database with Sandbar's required schemas loaded. The examples use a connected Clojure REPL and fictional data.

For the distinction between a constraint, its target population, and write acceptance, see [Shape validation](../concepts/shape-validation.md).

## Create the constraint and shape

Constraints with several fields are separate typed entities. Create the pattern constraint first, then refer to it from the shape:

```clojure
(require '[sandbar.db.datomic :as db]
         '[sandbar.db.datatype :as dt]
         '[sandbar.shape :as shape])

(def nonblank
  (dt/make :mm.shape/PatternConstraint
    {:db/ident :shape.examples/nonblank-description
     :mm.shape.pattern/property :mm.memory/description
     :mm.shape.pattern/regex "\\S"}))

(def description-shape
  (dt/make :mm/Shape
    {:db/ident :shape.examples/decision-description
     :mm.memory/name "Decisions have an explanation"
     :mm.memory/memory-type :shape
     :mm.memory/scope :project
     :mm.shape/shape-id "decision-description"
     :mm.shape/applies-to :mm/Decision
     :mm.shape/severity :violation
     :mm.shape/required-property [:mm.memory/description]
     :mm.shape/pattern-constraints [(:db/id nonblank)]}))
```

The presence requirement detects an absent value. The regular expression detects whether a present value contains a non-whitespace character. This is only a structural check: “Because” would pass, even if it were an inadequate explanation of a real decision.

`:mm.shape/shape-id` is a string. The property and constraint fields are references. Supplying the created constraint's entity id keeps the relationship explicit; an explanatory paragraph in a shape's Markdown body does not install these fields.

## Check a passing example

```clojure
(def decision
  (dt/make :mm/Decision
    {:db/ident :memory.examples/explained-refresh
     :mm.memory/name "Refresh after accepted writes"
     :mm.memory/description "Readers need the newly accepted value."
     :mm.memory/memory-type :decision
     :mm.memory/scope :project}))

(shape/walk-entity (db/db) (:db/id decision) (:db/id description-shape))
;; => {:status :pass, :entity ..., :shape ..., :checks-passed 7}

(shape/validate (db/db) (:db/id decision) :audit)
;; one result for each applicable shape
```

`walk-entity` exercises the named entity/shape pair directly. `validate` also exercises target selection. Both checks are useful when developing a shape: an empty result from `validate` could mean that no shape was selected.

## Check the negative case

For this audit exercise, use the class-level update API to store a blank description, then evaluate the shape explicitly:

```clojure
(dt/update-entity! decision {:mm.memory/description "   "})

(shape/validate (db/db) (:db/id decision) :audit)
;; the description shape fails its :pattern check

(try
  (shape/validate (db/db) (:db/id decision) :strict)
  (catch clojure.lang.ExceptionInfo e
    (:violations (ex-data e))))
;; returns the violation findings caught from the exception

(dt/update-entity! decision
  {:mm.memory/description "Readers need the newly accepted value."})
```

This demonstrates auditing already stored data. `shape/validate` does not undo a write. For a strict MCP mutation, use the mutation tool's `validation-mode` so the accepting boundary can reject a violating proposal before commit. See [boundary integration](../concepts/shape-validation.md#boundary-integration).

## Use MCP for an audit

This is the parameter object for `tools/call`:

```json
{
  "name": "sandbar_shape_validate",
  "arguments": {
    "entity": ":memory.examples/explained-refresh",
    "mode": "audit"
  }
}
```

The payload includes `entity`, `mode`, `result-count`, and `results`. Inspect transport errors and the MCP `isError` field before treating the payload as an ordinary result. `mode` belongs to this audit tool; entity create/update use the distinct argument `validation-mode`.

For a class report:

```json
{
  "name": "sandbar_shape_conformance-report",
  "arguments": {"class": ":mm/Decision"}
}
```

The equivalent Clojure call is `(shape/conformance-report (db/db) :mm/Decision)`. Read `instance-count`, `shape-count`, and `total-checks` alongside the failures. `total-checks` counts entity/shape pairs; a report with no evaluated pairs does not establish that the intended population passed.

This report selects direct `:mm/Decision` instances and shapes targeting that exact class. A specialized decision class needs its own target declaration and report; a parent-targeted shape is not inherited by the current selector.

## Add a different requirement

| Requirement | Representation |
| --- | --- |
| At least one value | `:mm.shape/required-property`, or a cardinality minimum |
| Between two and five values | A `:mm.shape/CardinalityConstraint` with minimum `2`, maximum `5` |
| A ticket matches a complete format | A `:mm.shape/PatternConstraint` with an anchored expression |
| A stored value has the required primitive type | Declare the Datomic attribute type; the additional shape datatype check currently has a false-pass defect |
| Exactly one of two properties is populated | A `:mm.shape/XorConstraint` |
| A rule needs application logic | A deployed custom validator, after verifying the reference resolves and the negative case fails |

Inspect these classes with the schema tools before constructing them. As in the first example, create the constraint entity and attach its reference to the corresponding shape slot. Pattern flags are strings using the supported Java-regex options `i`, `m`, `s`, and `x`; do not assume another regex dialect has identical behavior.

Leave the shape open unless the requirement is specifically to constrain all present properties. A closed shape's allowed set is assembled from the properties it declares, plus a small set of substrate fields. It does not automatically admit every inherited memory slot. See the [constraint meanings](../concepts/shape-validation.md#what-each-constraint-means) before enabling `:mm.shape/closed?`.

## Custom validator functions

A shape's `:mm.shape/validator-fn` refers to a function entity carrying `:dt.fn/source-ns` and `:dt.fn/source-var`. The intended callback interface is shown below; this is a signature sketch, not a complete validator registration example:

```clojure
(defn check-example [database entity-eid shape-eid]
  ;; Read the supplied database value and evaluate the actual requirement.
  {:status :pass})
```

The function must already be available to the running process. The metadata points to its implementation; it does not install code. A failure result uses `:status :fail`, includes useful details, and supplies a severity such as `:violation`. An exception is captured as a failed validator check.

The current resolver fails on an ident-bearing function reference before invoking it. An unnamed reference can invoke the same function, but that difference is a defect rather than a recommended authoring convention. Until it is repaired, do not make a named custom callback the sole acceptance check. Confirm actual invocation and inspect failure details; see [shape limitations](../concepts/shape-validation.md#what-each-constraint-means).

The custom check runs alongside the built-in checks. It does not replace the whole walker. Keep validation free of external side effects: an audit may invoke it repeatedly, and evaluation against a database value should not itself perform the operation being validated.

This signature differs from the class-level `:dt/validator`, whose function accepts one entity and returns `nil` or an error value. Use the contract for the extension point you are implementing.

## Choose severity and mode deliberately

Use `:violation` for a requirement that must prevent strict acceptance. Use `:warning` or `:info` for findings the application may act on without refusing the operation. Strict mode still returns those findings; it does not turn them into passes. Disabled evaluation returns `[]` and establishes no conformance result.

Before relying on a shape, exercise one pass and one failure, every concrete target class, an overlapping shape if relevant, and the actual write boundary used by the application. These checks verify different parts of the contract. Do not infer target coverage from a zero-failure report.

## See also

- [Shape validation](../concepts/shape-validation.md)
- [Defining classes](defining-new-classes.md)
- [First-class functions](../concepts/first-class-fn.md)
- [MCP reference](../api/mcp-verbs.md)
