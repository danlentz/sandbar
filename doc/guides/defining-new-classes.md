# Defining new classes

This guide adds a specialized decision with a required review ticket. You will declare its schema, create an instance, inspect inherited properties, and check how a client discovers it.

Use a disposable development database with Sandbar's required schemas loaded. The [quickstart](quickstart.md) covers service setup; this guide assumes a connected Clojure REPL. All example names are fictional.

## Decide what the class adds

Our `:mm.example/ReviewDecision` is a kind of `:mm/Decision`. It inherits the common memory properties and adds `:mm.example.decision/ticket`, a required string. That distinction is useful if applications query for reviewed decisions or require a ticket before accepting one.

If the difference were only a topic, an ordinary decision with a tag could be sufficient. A class should earn its place through a meaningful query, vocabulary, or constraint.

## Declare the schema

Create a new file named `schema/example-review.edn`. Schema files contain a vector of transaction batches. Predeclare the class, install its property, then give the class its full definition:

```clojure
[
 [{:db/ident :mm.example/ReviewDecision}]

 [{:db/ident :mm.example.decision/ticket
   :db/valueType :db.type/string
   :db/cardinality :db.cardinality/one
   :dt/type :dt/Property
   :dt/domain :mm.example/ReviewDecision
   :dt/range :db.type/string
   :dt/required? true}]

 [{:db/ident :mm.example/ReviewDecision
   :dt/type :dt/Class
   :dt/subclass-of :mm/Decision
   :dt/slots [:mm.example.decision/ticket]}]
]
```

The property declaration has two jobs. `:db/valueType` and `:db/cardinality` install the underlying Datomic attribute. The `:dt/*` fields describe it in Sandbar's metamodel. The class's `:dt/slots` makes it an effective slot alongside inherited properties. A domain declaration alone is not a replacement for listing the slot on the class.

Separating the batches makes referenced identities available before later definitions use them. For storage-level details, see [Datomic's schema reference](https://docs.datomic.com/schema/schema-reference.html).

## Load and inspect it

From the development REPL, with the new schema resource available:

```clojure
(require '[sandbar.db.datomic :as db]
         '[sandbar.db.datatype :as dt])

(db/load-schema :example-review)

(dt/parents-of :mm.example/ReviewDecision)
;; includes :mm/Decision

(contains? (dt/slots-of :mm.example/ReviewDecision) :mm.memory/name)
;; => true

(set (dt/required-slots-of :mm.example/ReviewDecision))
;; includes :mm.example.decision/ticket
```

For subsequent service starts, add `:example-review` after its dependencies in the deployment's `:required-schema` configuration. Preserve the required schemas already there; a partial replacement list can remove prerequisites. See [configuration and development](../development.md) for the environment being used.

Load model extensions before application work. Full configured schema loading invokes cache-clear callbacks; the single-file `load-schema` above and ad hoc class/inheritance edits do not. Use this example in a fresh development process, and configure the schema for startup in a deployed service. Fresh instance queries can otherwise disagree with a previously cached `type-isa?` answer. The [metamodel](../concepts/metamodel.md#a-class-extension-in-practice) describes this remaining dynamic-extension limit.

## Create and retrieve an instance

```clojure
(dt/make :mm.example/ReviewDecision
  {:db/ident :memory.examples/reviewed-refresh
   :mm.memory/name "Reviewed refresh policy"
   :mm.memory/memory-type :decision
   :mm.memory/scope :project
   :mm.example.decision/ticket "REVIEW-42"})

(dt/find-by-ident :memory.examples/reviewed-refresh)

(contains? (set (dt/named-idents-of :mm/Decision))
           :memory.examples/reviewed-refresh)
;; => true
```

The broader decision query includes the specialized instance. `dt/direct-instances-of` selects only entities whose declared class matches exactly; `dt/all-instances-of` includes subclasses.

The explicit ident makes this small example easy to retrieve. Document import has additional path and project identity rules; do not derive production identities by copying this example namespace. See [projection](../concepts/projection.md).

## Exercise the requirement

Try creating another instance without its ticket:

```clojure
(try
  (dt/make :mm.example/ReviewDecision
    {:mm.memory/name "Missing ticket"
     :mm.memory/memory-type :decision
     :mm.memory/scope :project})
  (catch clojure.lang.ExceptionInfo e
    (:errors (ex-data e))))
;; includes {:type :missing-required,
;;           :slot :mm.example.decision/ticket, ...}
```

`dt/make` performs class data validation before transacting. A present ticket must also satisfy the property's modeled range. “Required string” still allows an empty string; add a [shape](authoring-shapes.md) if the ticket must follow a particular format.

`dt/validate-data` accepts an ordinary property map and returns `nil` when it passes, or an error map. It does not add the defaults supplied by `dt/make`. `dt/validate` checks an existing entity and also invokes its class-level custom validator. Neither call is the same operation as `shape/validate`.

## Inspect through a client

An MCP client can discover the class through the existing operational tools. This `tools/call` parameter object asks for its effective slots:

```json
{
  "name": "sandbar_class_slots",
  "arguments": {"class": ":mm.example/ReviewDecision"}
}
```

Read the instance with `sandbar_entity_find` and its ident. Adding a class does not add a new MCP tool; the catalog's schema-aware operations handle the extension.

This example uses the memory-model namespace exposed by the MCP read surface. An independent domain namespace, such as the [Zorp tutorial's](zorp-tutorial.md), also needs an explicit exposure policy to make its instances available through a client. Schema discovery and access to instance data are distinct capabilities.

## Extend only the behavior you need

An abstract class, declared with `:dt/abstract? true`, can supply vocabulary without being directly instantiated through validated creation. Multiple subclasses can then share its slots.

A class-level `:dt/validator` names a deployed Clojure function that accepts an entity and returns `nil` or an error value. That is the stored-entity validation extension point. Check the accepting API's coverage before relying on it for write refusal. A shape's custom validator has a different signature and result format, described in [Authoring shapes](authoring-shapes.md).

A codec binding describes a representation understood by an installed codec. Merely naming a format on a class does not implement a codec. Likewise, inheriting a memorial policy does not establish a complete document representation for new slots. Verify the round trip when the new class needs readable persistence.

Finish the extension by checking the consequence that motivated it: an instance is accepted or refused as intended, appears in the right broader queries, is inspectable by its client, and survives its supported representation. Those observations make the schema addition useful.

## See also

- [Metamodel](../concepts/metamodel.md)
- [Shape validation](../concepts/shape-validation.md)
- [`dt/*` reference](../api/dt-star.md)
- [Zorp's Galactic Footwear Emporium](zorp-tutorial.md)
