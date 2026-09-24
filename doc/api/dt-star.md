# `dt/*` API reference

```clojure
(require '[sandbar.db.datatype :as dt])
```

This reference covers the modeling, validation, representation, and query entry points used by the guides. Functions operate through Sandbar's configured Datomic connection unless their signature says otherwise. It is a curated reference; the namespace also exports implementation and cache-management helpers.

In the signatures below, `class` and `property` are keyword idents. An entity result is a Datomic entity view, not necessarily a plain persistent map. Use `(into {} entity)` when a consumer requires a plain map; that conversion does not recursively realize every reference.

## Class introspection

| Call | Result |
| --- | --- |
| `(dt/all-classes)` | Class idents from the metamodel's class population |
| `(dt/all-properties)` | Property idents from the property population |
| `(dt/all-datatypes)` | Idents of entities declared directly as `:dt/Class` |
| `(dt/class-ident-of entity)` | The entity's declared class ident |
| `(dt/class-entity-of class)` | The entity describing that class |
| `(dt/find-by-ident ident)` | The named entity, or `nil` if absent |
| `(dt/abstract? class)` | The declared abstract flag; absent is falsey |

`class-entity-of` takes a class ident; it does not follow an instance's type. To obtain an instance's class metadata, compose `class-ident-of` with `class-entity-of`.

`class-of` is a deprecated alias for `class-ident-of`. Its result is an ident, not the class entity. There is no general `dt/find-by` function in this interface.

## Hierarchy navigation

| Call | Meaning |
| --- | --- |
| `(dt/parents-of class)` | Direct parent class idents |
| `(dt/ancestors-of class)` | Transitive ancestor class idents |
| `(dt/direct-subclasses-of class)` | Direct child class idents |
| `(dt/subclasses-of class)` | Transitive subclass idents |
| `(dt/descendants-of class)` | Set containing the class itself and all transitive subclass idents, via the cached class-relation path |
| `(dt/subclass-of? parent child)` | Whether `child` is a transitive subclass of `parent` |
| `(dt/type-isa? parent candidate)` | Whether the class `candidate` is `parent` or a subclass |
| `(dt/instance-of? class entity)` | Whether the entity belongs to the class or a subclass |

Keep the broader class first in these predicates. Do not infer query result order from the hierarchy; sort when a stable presentation is needed. For the distinction between these core operations and explicitly requested property entailment, see [inference](../concepts/rdfs-entailment.md).

`type-isa?`, `subclass-of?`, `instance-of?` and `descendants-of` use cached relations. `db/load-all-schema!` invokes registered cache-clear callbacks; a single `db/load-schema` or ordinary class/inheritance mutation does not. Prefer loading the complete model before application work; do not use a stale cached answer to adjudicate a dynamic model change. Fresh instance queries and cached type predicates have different freshness boundaries in this revision.

## Instance enumeration

| Call | Population and return shape |
| --- | --- |
| `(dt/direct-instances-of class)` | Entity views with exactly that declared class |
| `(dt/all-instances-of class)` | Entity views including subclass instances |
| `(dt/named-idents-of class)` | Idents of named instances, including subclasses |
| `(dt/named-entities-of class)` | Entity views for that named population |

`all-named-instances-of` is a deprecated alias for `named-idents-of`. Migrate according to the result shape the caller needs.

## Slot and property queries

| Call | Result |
| --- | --- |
| `(dt/direct-slots-of class)` | The directly declared `:dt/slots` values |
| `(dt/slots-of class)` | Set of effective property idents, including inherited slots |
| `(dt/required-slots-of class)` | Sequence of effective slot idents marked required |
| `(dt/domain-of property)` | Declared domain |
| `(dt/range-of property)` | Declared modeled range |
| `(dt/cardinality-of property)` | Datomic cardinality ident, or `nil` when absent |
| `(dt/cardinality-one? property)` | Whether cardinality is one |
| `(dt/cardinality-many? property)` | Whether cardinality is many |
| `(dt/required? property)` | The declared required flag, or `nil` when absent |
| `(dt/properties-with-domain class)` | Property idents whose domain is the class or an ancestor |
| `(dt/validator-of class)` | Resolved custom-validator var, or `nil` when unavailable |

Effective slots follow `:dt/slots` declarations through inheritance. A property's domain is related metadata, not a substitute for a class slot declaration. `validator-of` resolves the class's `:dt/validator` symbol; it does not return that symbol as its result.

## Entity creation and update

### `make`

```clojure
(dt/make class)
(dt/make class properties)
(dt/make class properties options)
```

Returns the created entity view. By default, the prepared data is checked for an abstract class, required slots, modeled ranges, and cardinality before transaction. Memory defaults and reference normalization are part of preparation. The operation notifies the reactive change mechanism after creation.

| Option | Meaning |
| --- | --- |
| `:validate?` | Defaults to `true`; `false` skips class data validation |
| `:format` | Codec format used with a source representation |
| `:source` | Representation to parse; explicit properties override parsed values |
| `:project?` | Per-call participation in reactive projection policy |

Codec options belong in the third argument:

```clojure
(dt/make :mm/Decision properties
  {:format :markdown :source markdown-text})
```

`dt/make`'s class validation is distinct from a shape evaluation or a stored-entity custom-validator call. Do not interpret the name “validated creation” as a promise that every registered validation mechanism runs here.

### `update-entity!`

```clojure
(dt/update-entity! entity slot-updates)
(dt/update-entity! entity slot-updates options)
```

Accepts an entity view, ident, or entity id and returns the refreshed entity. By default it validates the merged class data before transacting. Cardinality-many updates replace the supplied slot's value set; `:additive? true` requests union instead. Options also include `:validate?` and `:project?`.

For a Memory class whose native body is `:mm.memory/body-raw`, replacing that slot reconciles the section tree in the same transaction. Surviving section identities and unrelated host slots are retained. Removing a section cited from outside the document, colliding with another document's section identity, or changing the host ident in the same body edit refuses the update. An identless memory can receive a plain body, but a sectioned edit requires an existing stable ident. A body-edit plan is valid at one database basis only. If that basis changes before acceptance, the call refuses with `:body-update/basis-moved` and `:retryable? true`; reread the memory and retry. The fixed plan is neither reused nor automatically replanned at a new basis. This does not add section decomposition to `make` or reconcile class-specific native body slots.

### Batch and lower-level creation

| Call | Contract |
| --- | --- |
| `(dt/make-all entity-specs)` or `(dt/make-all entity-specs options)` | Validate the prepared batch, then transact it together; options include `:project?` |
| `(dt/make* class)` or `(dt/make* class properties)` | Lower-level construction without class data validation |
| `(dt/make-all* entity-specs)` | Lower-level batch transaction without the validated wrapper |

Batch entity specifications carry their own `:dt/type`. The batch APIs return a Datomic transaction result, not a vector of created entities. The internal second arity of `make-all*` accepts a specification index for reference handling; ordinary callers should use the validated wrapper.

Lower-level APIs are useful for controlled schema and import work. Their existence does not establish the strict acceptance guarantees of an external mutation boundary.

## Validation

| Call | Success | Failure | Coverage |
| --- | --- | --- | --- |
| `(dt/validate-data class properties)` | `nil` | `{:errors [...]}` | Class data checks on a plain map; does not add creation defaults |
| `(dt/validate entity)` | `nil` | `{:entity ..., :errors [...]}` | Stored entity's class checks and custom class validator |
| `(dt/valid? entity)` | `true` | `false` | Boolean wrapper around `validate` |
| `(dt/validate-all-instances class)` | A summary map | The same map with invalid entries | Stored validation across the class and subclass population |

Error entries use `:type`, with values such as `:no-class`, `:abstract-class`, `:missing-required`, `:invalid-type`, `:cardinality-violation`, `:custom-validation`, or `:validator-error`. Data-validation failure during `make` or validated update throws `ExceptionInfo`; inspect `ex-data` for the errors.

A class validator accepts one entity and returns `nil` for success or an error value. Shape validators use another interface. None of the validation calls above is a substitute for `sandbar.shape/validate`; see [Shape validation](../concepts/shape-validation.md) for target selection, modes, and the acceptance boundary.

## Representation and inherited metadata

```clojure
(dt/realize-with seed walk-fn)
(dt/emit-entity entity)
(dt/emit-entity entity options)
```

`realize-with` performs a breadth-first traversal defined by `walk-fn`, deduplicates by entity id, and returns a vector of entity specification maps including the seed. The walk function chooses which relationships form the representation; this is not an automatic dump of every reachable reference.

`emit-entity` passes a realized entity representation to the codec mediator and returns the emitted value, normally text. Pass an entity view, plain map, or numeric id. Options include `:format` and codec-specific settings; resolve an ident with `find-by-ident` before emission. See [codecs](../concepts/codec-layer.md) and [projection](../concepts/projection.md) for format and preservation contracts.

| Direct declaration | Effective form including inheritance |
| --- | --- |
| `codec-aliases-of` | `effective-codec-aliases-of` |
| `codec-slot-order-of` | `effective-codec-slot-order-of` |
| `bm25f-weights-of` | `effective-bm25f-weights-of` |
| `memorial-policy-of` | `effective-memorial-policy-of` |

Each accepts one class ident. Effective methods apply their metadata-specific inheritance policy. `native-codec-of-class`, `codec-type-keywords-of`, and `class-for-codec-type-keyword` support codec selection and lookup. `corpus-document-class?` reports the class-level policy used for document projection; it does not prove a particular entity has a complete document representation.

## Fulltext and aggregation primitives

| Call | Result |
| --- | --- |
| `(dt/fulltext-indexed? property)` | Whether the Datomic fulltext flag is enabled |
| `(dt/search-fulltext property query)` | Raw `[entity-id score]` tuples from single-attribute fulltext search |
| `(dt/count-of class)` or `(dt/count-of class where-clauses)` | Count including subclasses |
| `(dt/group-by-of class property)` or `(dt/group-by-of class property where-clauses)` | Map from present slot values to counts; missing values omitted |
| `(dt/degree-of entity)` or `(dt/degree-of entity options)` | Count of reference-attribute edges |
| `(dt/backlink-density-of entity)` or `(dt/backlink-density-of entity predicates)` | Inbound edge count |
| `(dt/recency-rank-of class temporal-slot)` | `[entity temporal-value]` pairs, newest first |
| `(dt/freshness-rank-of class temporal-slot)` | The same shape, oldest first |

Single-attribute fulltext search is distinct from Sandbar's multi-field BM25F search API. Do not assume their scores have the same scale. Structural `where-clauses` refer to `?e` and pass through the supported query sanitizer; they are not an arbitrary Clojure evaluation surface.

`degree-of` options include `:direction` (`:forward`, `:inverse`, or default `:bidirectional`) and `:predicates`. It counts edges, so several predicates can connect the same pair of entities. The supplied temporal slot determines what “recent” or “stale” means; these functions do not establish a record's authority.

## Navigation primitives

| Call | Result and options |
| --- | --- |
| `(dt/outbound-edges-of entity)` or with an options map | Maps with `:predicate` and `:target`; filters `:predicate`, `:target-type` |
| `(dt/inbound-edges-of entity)` or with an options map | Maps with `:predicate` and `:source`; filters `:predicate`, `:source-type` |
| `(dt/graph-walk-from seed)` or with an options map | Maps with `:entity` and `:hop`, excluding the seed |
| `(dt/library-card-of entity axis-specs)` | Entity plus an `:axes` map of requested edge views |
| `(dt/siblings-of entity path-slot)` | Peers sharing a directory prefix in the chosen path slot |

`graph-walk-from` supports `:hops` (default `4`), `:predicates`, and `:direction` (default `:forward`). `:include [:paths]` adds the traversed predicate/direction steps. Enumeration and traversal follow their API's visibility rules; schema reachability is not permission to disclose everything a graph contains.

The higher-level `sandbar.navigate.edges` wrappers accept an eid as the anchor, resolve qualified or bare predicate names, and report `distinct-total` separately from the edge count. Use those wrappers for concept membership where records can lack idents or appear through both tags and themes.

See [navigation](../concepts/navigation.md), [aggregation](../concepts/aggregation.md), and [search](../concepts/fulltext-search.md) for choosing a query by the question being asked.

## Errors and execution costs

Validation errors, missing lookups, and transaction exceptions have different return conventions. Handle the documented case instead of treating every falsey value as an empty successful query. MCP adds its own error envelope around the corresponding operation.

Enumeration, graph walks, and conformance reports can visit substantial populations. Restrict the class, predicates, and traversal depth to the question. There is no blanket complexity guarantee for this namespace; cost depends on the query, the indexes and caches it uses, and the size of the selected graph.

## See also

- [Class-authoring guide](../guides/defining-new-classes.md)
- [Shape-authoring guide](../guides/authoring-shapes.md)
- [Zorp tutorial](../guides/zorp-tutorial.md)
- [Clojure client guide](../guides/writing-a-clojure-client.md)
