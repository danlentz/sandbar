# Writing a Clojure client

Use Sandbar in the same JVM when your application needs direct access to typed entities, model inspection, and Datalog. This guide starts with a disposable database and then explains the boundaries an embedded application must own. For a remote service, use the [MCP](writing-an-mcp-client.md) or [REST](writing-a-rest-client.md) guide instead.

## Adding Sandbar to your project

Start from a source checkout and run `lein repl` at its root. Its `project.clj` supplies the Clojure, Datomic Peer and other dependencies, and places the schema resources on the classpath. See the [development guide](../development.md) for setup.

When packaging an application, pin a reviewed Sandbar source revision or an actually published artifact and retain its schema/configuration resources. A server's reported version is not evidence that a matching Maven artifact has been published. Use the coordinates in the selected revision's `project.clj`.

## Connecting

This complete example creates a uniquely named in-memory database, loads Sandbar's configured schema, creates and updates a Tag, and deletes the database afterward. It needs no external transactor or HTTP server.

```clojure
(require '[datomic.api :as d]
         '[sandbar.db.datomic :as db]
         '[sandbar.db.datatype :as dt]
         '[sandbar.reactive :as reactive])

(defn try-sandbar []
  (let [uri (str "datomic:mem://sandbar-example-" (java.util.UUID/randomUUID))]
    (d/create-database uri)
    (try
      (binding [db/**conn* (atom (d/connect uri))
                reactive/*reactive-projection-enabled?* false]
        (db/initialize-db! uri)
        (let [tag (dt/make :mm/Tag {:mm.tag/value "cache-policy"})
              updated (dt/update-entity! tag
                         {:mm.tag/scope-note "Decisions about cache freshness."})]
          {:value (:mm.tag/value updated)
           :scope-note (:mm.tag/scope-note updated)
           :tag? (boolean (dt/instance-of? :mm/Tag updated))
           :valid? (dt/valid? updated)
           :has-value-slot? (contains? (dt/slots-of :mm/Tag) :mm.tag/value)}))
      (finally
        (d/delete-database uri)))))

(try-sandbar)
```

The returned map contains the value and scope note above, with the three predicates true. The entity itself exists only during the call.

`db/**conn*` holds an atom containing the connection. Bind it to a new atom for a bounded operation; resetting its process-wide root would change the database seen by other callers. `db/initialize-db!` takes a URI, loads the configured schema and declared functions, and applies initialization checks. It does not install that connection as the current one. The binding does that separately.

The reactive binding suppresses projection callbacks for this database exercise. A production application that wants projected files must configure the codecs, destinations and worker lifecycle explicitly. Creating a connection does not start those services.

## Reading the metamodel

Run these forms inside a connection binding such as the one above:

```clojure
(dt/all-classes)
(dt/slots-of :mm/Decision)
(dt/direct-slots-of :mm/Decision)
(dt/ancestors-of :mm/Decision)
(dt/range-of :mm.memory/name)
(dt/cardinality-of :mm.memory/name)
```

`slots-of` returns the effective slot identifiers, including inherited slots. `direct-slots-of` returns property entities declared directly on the class; it is not the same result shape. The [API reference](../api/dt-star.md) distinguishes identifier sets, entity collections, predicates and validation results.

Load the complete schema before ordinary application work. Configured full schema loading invokes model-cache callbacks; single-file loading and hot class/inheritance edits still have an invalidation gap. See the [class guide](defining-new-classes.md#load-and-inspect-it) before relying on dynamic model extension.

Type predicates take the class first: `(dt/instance-of? :mm/Tag entity)`. The subclass predicate also puts the ancestor first: `(dt/subclass-of? :mm/Memory :mm/Decision)`.

## Creating entities

`dt/make` validates the candidate's class, required slots and declared slot types before committing. It returns the created entity. `dt/update-entity!` accepts an entity, ID or ident and returns a refreshed entity after updating it. Cardinality-many updates replace the supplied slot's prior members by default; `{:additive? true}` requests union behavior.

Use `dt/validate-data` to inspect a candidate without writing it. Its success result is nil; a failure is an error map. `dt/validate` checks an existing entity, and `dt/valid?` turns that result into a boolean. Declared shape checks are a separate surface; see [authoring shapes](authoring-shapes.md).

### Codec-mediated creation

For memories, use `sandbar.store/create-memory!` so creation includes the durable identity and path conventions. It delegates typed creation to `dt/make` and accepts the same third-argument representation options. Register the Markdown codec before use, then run this inside a disposable connection binding:

```clojure
(require '[sandbar.codec.markdown :as markdown]
         '[sandbar.store :as store])

(markdown/register!)
(store/create-memory! :mm/Observation
  {:mm.memory/rel-path "examples/cache-observation.md"}
  {:format :markdown
   :source "---\nname: Cache observation\ntype: observation\n---\n\nA warm lookup returned an old value.\n"})
```

The relative path is part of the memory's model identity, not a request to read that file from disk. The [getting-started guide](getting-started.md) exercises the same creation boundary through MCP; the [codec guide](implementing-a-codec.md) explains representation registration.

## Queries

Prefer typed operations for the questions they already answer. Use Datomic for an application-specific query, with an explicit database value:

```clojure
(let [snapshot (db/db)]
  (d/q '[:find ?tag ?value
         :where
         [?tag :dt/type :mm/Tag]
         [?tag :mm.tag/value ?value]]
       snapshot))
```

This query asks for directly asserted `:mm/Tag` instances. `dt/all-instances-of` includes the inferred subclass population. Choose deliberately; writing raw Datalog does not automatically add Sandbar's inheritance rules to the query.

Datomic entity values belong to the database value from which they were obtained. Read a fresh entity after a mutation when you need current state. Numeric IDs are local to a database; use the model's durable identity when storing a reference outside it.

## Working with workflows

The engine is `sandbar.util.workflow`: define a workflow, start a process with a persisted subject entity, and request a transition by action name. A process is an execution instance, separate from its workflow definition. The [workflow guide](designing-workflows.md) provides a complete definition and subject, and the [workflow concept](../concepts/workflow-substrate.md) explains state, history and effects.

Do not infer that a callback's external actions are rolled back by a failed database transaction. Design the acceptance and retry policy for those actions explicitly.

## Other application boundaries

| Need | Namespace or guide |
| --- | --- |
| Typed creation, inspection and validation | `sandbar.db.datatype`; [`dt/*` reference](../api/dt-star.md) |
| Representation parsing and emission | `sandbar.codec`; [codec guide](implementing-a-codec.md) |
| Memory identity and creation | `sandbar.store`; [memory model](../concepts/memory-model.md) |
| Shape reports | `sandbar.shape`; [shape guide](authoring-shapes.md) |
| Workflow processes | `sandbar.util.workflow`; [workflow guide](designing-workflows.md) |
| Events and logging | [event subscriptions](subscribing-to-events.md), [logging](using-logging.md) |
| File export and reconstruction | [projection](../concepts/projection.md), [operations](../operations.md) |

## Testing

Use a unique in-memory database per independent test and keep the connection binding around the whole operation. Join asynchronous work before deleting its database. The repository's `sandbar.test-util` helpers live on the test classpath; they are not a production client API. The [development guide](../development.md#testing) shows their actual fixture interface and the test-profile database fence.

An embedded client runs inside the trusted process. It does not acquire MCP or REST authorization merely by calling the same underlying functions. The embedding application owns who may invoke those functions and which connection and output destinations they can reach.
