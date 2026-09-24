# Functions: connect executable work to its explanation

**A system can describe a computation as part of the same graph that records its purpose and use.** A function's implementation remains ordinary code, while its identity, source, behavior, and relationships become inspectable data. A job can refer to that function; a reader can discover why it exists and which implementation it names.

Sandbar distinguishes `:dt/Fn`, the metamodel vocabulary for functions, from `:mm/Fn`, a function record that also participates in the [memory model](memory-model.md). The latter inherits the descriptive properties of a memory and declares the function-specific properties. A function record is not automatically an executable program in every consumer.

## What the model describes

| Concern | Representative properties |
| --- | --- |
| Signature | `:dt.fn/parameters`, `:dt.fn/return-type` |
| Implementation | `:dt.fn/body`, `:dt.fn/lang`, `:dt.fn/implementation` |
| Intent and behavior | `:dt.fn/description`, `:dt.fn/purpose`, `:dt.fn/purity`, `:dt.fn/cost-class` |
| Source | `:dt.fn/source-ns`, `:dt.fn/source-var` |
| Evolution | `:dt.fn/version`, `:dt.fn/status`, `:dt.fn/superseded-by` |
| Installation | `:dt.fn/installed-as` |

These declarations make useful questions possible: which jobs use this implementation, which functions declare side effects, or which version replaced an older one? They do not, by themselves, prove purity, enforce a cost bound, install an Ion, or choose among arbitrary language implementations.

## One definition can emit code and metadata

`sandbar.db.fn/defdbfn` defines a Clojure function and queues an accompanying `:mm/Fn` record. The namespace remains the source of truth. For `:installed-as :db-fn`, the macro also queues a Datomic transaction-function definition; for `:classpath-fn`, the implementation stays in the peer JVM.

```clojure
(require '[sandbar.db.fn :refer [defdbfn]])

(defdbfn normalize-label [s]
  {:dt.fn/installed-as :classpath-fn
   :dt.fn/purpose :transform
   :dt.fn/purity :pure-total
   :dt.fn/description "Normalize a label for comparison"}
  (clojure.string/lower-case s))

(normalize-label "APPROVED")
;; => "approved"
```

The function works as an ordinary Clojure function. Its queued record captures the body, source namespace and var, and supported descriptive attributes. Queuing is separate from installation: the database initialization path loads the registered transaction functions and function records. A running deployment must deliberately load changed definitions; editing a document is not a general code-deployment mechanism.

Use `all-mm-fn-memorials` to inspect queued records and the ordinary class/entity APIs to inspect installed records. Not every property in the schema is emitted by this macro. In particular, do not assume an arbitrary attribute map is forwarded unchanged, or that adding rule properties turns a generated `:mm/Fn` into a `:mm/Rule`.

## Execution belongs to a named consumer

The scheduler resolves a job's function reference through `source-ns` and `source-var` and calls the classpath function with a run-context map. A shape callback has a different signature and result contract. A transaction function produces transaction data under Datomic's transaction rules. These are distinct execution boundaries even when their descriptions share the function vocabulary.

Applications should specify which consumer executes a function, its arguments, allowed effects, error behavior, and retry policy. A `:pure-total` label is useful documentation; a consumer must still handle failure. A timeout is also not evidence that external effects have been undone.

This arrangement supports inspection without conflating description, deployment, and invocation. Continue with [rules](first-class-rule.md), [workflows](workflow-substrate.md), and [temporal scheduling](temporal-substrate.md). The implementation and vocabulary are in [`sandbar.db.fn`](../../src/sandbar/db/fn.clj), [`schema/fn.edn`](../../schema/fn.edn), and [`schema/mm-meta.edn`](../../schema/mm-meta.edn).
