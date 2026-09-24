# Path grammar: make a graph question composable

**A route through a graph can be described as data and reused as part of a larger query.** Instead of writing a client loop for each new combination of relationships, compose a path expression and let Sandbar resolve its endpoints.

The language is inspired by Ora Lassila's [Wilbur path queries](https://www.lassila.org/blog/archive/2005/05/querying_rdf.html), including repetition of compound paths. Some operators have analogues in [SPARQL property paths](https://www.w3.org/TR/sparql11-query/#propertypaths). Sandbar exposes its own EDN dialect; these connections do not imply conformance to SPARQL, GQL, or another complete query language.

## A small vocabulary composes

An ordinary property keyword walks one relationship. A vector places an operator before its arguments:

```clojure
[:SEQ [:INV :mm.memory/supersedes] :mm.memory/cites]
```

From an older decision, this finds records cited by a decision that supersedes it. `INV` changes direction, and `SEQ` composes two steps. The expression names the meaning of the query independently of client transport.

| Form | Meaning |
| --- | --- |
| `:mm.memory/cites` | Follow that property |
| `[:SEQ p q]` | Follow `p`, then `q` |
| `[:OR p q]` | Union of endpoints reached by either path |
| `[:REP+ p]` | One or more applications of `p` |
| `[:REP* p]` | Zero or more applications, including the current node |
| `[:REP p 1 3]` | Between one and three applications |
| `[:OPT p]` | Zero or one application |
| `[:INV p]` | Reverse traversal direction |
| `:SELF` | Keep the current node |
| `:ANY` | Follow any available edge |
| `[:RESTRICT [:dt/type :mm/Observation]]` | Keep nodes with that exact property value |

Here `p` and `q` stand for path expressions, not literal symbols accepted by the parser. `SEQ` and `OR` can have more than two children. Fully qualified property keywords make queries portable across application vocabularies. A `RESTRICT` on `dt/type` tests the specified assertion; use the model's class/inference operations when inherited membership is the question.

## Endpoint queries and witnesses

`sandbar.navigate.path-via` accepts an entity identity in `from` and the expression in `via`. Clojure callers can pass data directly; MCP callers pass an EDN string. The string is read as data, not evaluated as Clojure code.

The ordinary result contains `reachable`, `total`, and `returned`, with projected endpoint entities. `include: ["paths"]` requests an entity plus a path witness for each returned endpoint. A path value records nodes and edges, including direction. It can be inspected with the helpers in [`sandbar.navigate.path.value`](../../src/sandbar/navigate/path/value.clj).

The following additional operators support endpoint queries, but not path witnesses in the current dialect:

| Form | Endpoint behavior |
| --- | --- |
| `[:NOT p q]` | Follow a property outside the named atomic property set |
| `[:FILTER p "text"]` | Keep endpoints whose identifier contains the substring |
| `[:TEST p :registered-test]` | Apply a registered test to endpoints |

`TEST` is registry-mediated; it is not a facility for sending arbitrary functions over MCP. Unsupported combinations must be handled as errors, not interpreted as empty successful traversals.

The parser also recognizes reserved vocabulary whose execution is not implemented: `LANG`, `VALUE`, `DAEMON`, `NOREWRITE`, `MEMBERS`, `PREDICATE-OF-SUBJECT`, and `PREDICATE-OF-OBJECT`. Recognition is not capability. Applications should use the executable forms above rather than infer support from the operator registry alone.

## The implementation has explicit boundaries

The parser constructs an abstract syntax tree; normalization rewrites equivalent forms into an internal representation. The core and desugarable operators run through a Clojure evaluator that checks traversal permissions at each hop. Endpoint-only `NOT`, `FILTER`, and `TEST` use a Datomic compilation route. Thus the language has a common representation, but not a claim that every operation shares one execution or policy mechanism.

A result may include `blocked` entries describing withheld traversal. Consult [project boundaries](../firewall-and-projects.md) before using path queries as an isolation boundary. Result limiting occurs after evaluation; it controls response size. Bounded repetition controls path depth, while the branching factor still affects work.

Expressions can also restrict [search](fulltext-search.md) candidates through `from` and `via`. That composition asks, for example, “which relevant records are reachable from this decision?” It preserves the distinction between a graph condition and text relevance.

Try the [path guide](../guides/navigating-with-paths.md). The executable dialect is defined by the [parser](../../src/sandbar/navigate/path/ast.clj), [normalizer](../../src/sandbar/navigate/path/ir.clj), [evaluator](../../src/sandbar/navigate/path/evaluate.clj), and [query adapter](../../src/sandbar/navigate/path.clj).
