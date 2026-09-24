# The memory model

> Useful memory preserves a judgment, the evidence behind it, and enough context to recognize when it should change.

## Thesis

A project accumulates more than documents. It accumulates observations, choices, open questions, working practices, and explanations of why those practices exist. Recovering that knowledge means finding the relevant passage and understanding its role: is this a measurement, a proposal, an accepted decision, or an earlier decision that has been replaced?

Sandbar's memory model makes those distinctions explicit. A memory has a class, readable content, metadata, and named relationships to other entities. A decision can cite an observation and supersede another decision. A plan can lead to work whose outcome becomes new evidence. Programs can follow those relationships while people read the explanation that gives them meaning.

The model supplies structure for this work. Calling something an observation does not establish its truth; calling something an authorization does not grant a caller permission. Readers and applications still need to examine the record, its context, and the policy that governs its use.

## Different roles for knowledge

Four abstract families organize the vocabulary beneath `:mm/Memory`:

| Family | Role | Examples |
| --- | --- | --- |
| `:mm/Artifact` | An output worth retaining | Decision, plan, reference, synthesis |
| `:mm/Signal` | Something observed, asked, or proposed | Observation, question, idea, bug |
| `:mm/Guidance` | A prescription for future conduct | Pattern, protocol, preference |
| `:mm/Meta` | A description of the knowledge system and its participants | Type, shape, actor, session |

The families help queries express a useful level of generality. An application can retrieve all guidance, or narrow the question to protocols. A specialized decision inherits the common memory vocabulary. The [metamodel](metamodel.md) explains the class and slot machinery; [inference](rdfs-entailment.md) explains how broader queries include subclass instances.

These categories are extensible. Add a class when it gives readers or programs a meaningful distinction, especially one with its own properties or checks. Use an existing class when a name, tag, or relationship already expresses the difference. There is no benefit in making every new topic a new class.

## Follow an explanation through a change

Consider a fictional service whose cache initially refreshes on a timer. A measurement reveals that readers sometimes see old data after a successful write. The team decides to refresh after accepted writes instead.

Three records preserve the reasoning:

| Record | Class | Relationship |
| --- | --- | --- |
| Measure cache freshness | `:mm/Observation` | Describes the measurement and its conditions |
| Refresh on a timer | `:mm/Decision` | Retains the original choice and rationale |
| Refresh after accepted writes | `:mm/Decision` | Cites the measurement and supersedes the original choice |

The replacement decision's relationships can be written as:

```clojure
{:mm.memory/cites [:memory.examples/cache-measurement]
 :mm.memory/supersedes [:memory.examples/timed-refresh]}
```

These are stored references, not phrases a search engine must interpret. Starting from the measurement, a reverse-edge query finds the decision that cites it. Starting from the original decision, a reverse `supersedes` query finds its replacement. The older explanation remains available when someone asks why the timer existed.

Different relationships answer different questions:

| Relationship | Question it helps answer |
| --- | --- |
| `:mm.memory/cites` | What source does this record refer to? |
| `:mm.memory/refines` | What earlier account does this develop? |
| `:mm.memory/supersedes` | What earlier record does this replace? |
| `:mm.memory/related` | What else may be useful context? |

A citation alone does not tell a reader whether the cited material supports or challenges a conclusion. The text should explain that relationship, or the model should use a more specific predicate when one is available. Likewise, a broad “related” edge cannot substitute for an explicit supersession.

Stored edges, reverse navigation, and inferred inverse relationships are separate mechanisms. A query that applies an inverse-property rule can derive a reverse relationship without storing a second edge. The navigation or query API determines which of these mechanisms it uses.

## Give concepts a shared vocabulary

A topic can recur in observations, decisions and references under different words. Tags give those records a common point of entry. A definition explains the concept; a scope note helps an author decide when to use it; alternative labels preserve the words a reader may bring to a search. Broader, narrower and related concepts help readers move from a general question toward a useful distinction.

Sandbar models this vocabulary through `:mm/Tag` and concept schemes. Its label, note and relationship vocabulary draws on [SKOS](https://www.w3.org/TR/skos-primer/). A concept's identity is distinct from its labels, and similarity between labels does not establish that two concepts mean the same thing.

Lightweight tags allow a collection to grow before every recurring idea has a curated definition. Curate a concept when its use justifies a shared meaning and a clear boundary. Review alternatives and the records they classify before merging them. A low-frequency tag can still express a useful distinction; a frequent tag can still be vague.

Type, curation and use describe different facts. A tag's type makes it part of the model. Its definition, scope and editorial history explain its meaning. Relationships from records establish where it is used. Collections can use `:mm.memory/tags` and `:mm.memory/themes` to express different classification roles, but a relationship name alone does not certify the target's quality. Read the recorded meaning and lifecycle; an absent curation flag is not an explicit rejection.

Classification needs attention on both sides. A well-defined concept helps only if useful records link to it. When content search recovers a decision that concept membership missed, consider whether the decision needs a classification link or whether the concept's scope needs clarification. Do not broaden the concept merely to absorb every nearby result.

The practical test is retrieval: can someone approach the concept in their own words, understand the vocabulary offered, and find the records that matter? The [search guide](../guides/searching-the-corpus.md#use-vocabulary-to-find-a-concept-and-its-records) shows how to combine vocabulary lookup, membership queries and full reads.

## Recover context before acting

Search is a useful entry point. The next step is to read the record and follow the relationships relevant to the question. A high relevance score establishes neither authority nor current applicability.

For example, an MCP client can read a known decision in full:

```json
{
  "name": "sandbar_entity_find",
  "arguments": {
    "id": ":memory.examples/write-refresh",
    "projection": "full"
  }
}
```

This is a `tools/call` parameter object. The example ident refers to fictional data; it must exist in the database being queried. A returned numeric `db/id` also supports a full read, including for an entity without an ident. See the [MCP guide](../guides/writing-an-mcp-client.md) for the transport envelope and error handling.

To use the result, ask what scope the decision covers, what evidence it cites, whether a successor exists, and whether the successor changes the whole decision or only part of it. The relationships make those questions tractable. The application's retrieval policy decides which questions must be answered for a particular action.

## Identity, scope, and time

A record's identity lets other records refer to it as its content evolves. A relative document path locates a representation within a project tree. These have different jobs: changing a path should be an explicit identity decision, and importing two projects with the same relative path must not accidentally merge their records. Project import and export establish the identity boundary described in [projection](projection.md).

Scope describes where a memory is intended to apply. It is information for interpretation and policy; a scope label alone does not enforce a disclosure boundary.

Time also has several meanings. A recorded observation may concern an earlier event; its creation and last-touched fields describe authored metadata; Datomic transaction time records when an assertion entered the database. A recent import of an old decision does not make that decision the latest ruling. Choose the time axis that answers the question, and use explicit relationships for changes in meaning.

## Activities and provenance

An activity record connects knowledge to work that produced or used it. `:mm/Activity` provides slots for an agent, start and end times, used inputs, and generated outputs. An analysis can use measurements and generate a report; a later decision can cite that report.

This vocabulary draws on [W3C PROV-O](https://www.w3.org/TR/prov-o/), which distinguishes entities, activities, and agents and the relationships among them. Sandbar uses its own schema to represent the relevant connections. A recorded provenance link is an assertion about what happened; its presence does not prove that every step was captured or that an execution succeeded.

Runtime telemetry and retained knowledge also serve different purposes. An application should retain activities that explain outcomes it needs to revisit. A server request does not become a useful memory merely because it happened.

## First-class memorialization

Sandbar supports readable documents as the durable representation of supported knowledge. The database makes their typed contents queryable; the document preserves the explanation in a form people can read, compare, and version. [Projection](projection.md) defines the supported representation and reconciliation contract.

The class-level `:dt/memorial-policy` distinguishes three representation policies:

| Policy | Meaning |
| --- | --- |
| `:first-class` | Eligible for its own document representation |
| `:db-only` | Retained in the database without an individual memory file |
| `:inline` | Represented inside another document rather than in its own file |

A tag can be inline and still have an identity in the database. A first-class policy still needs a supported codec, a document path, and an active projection consumer before a file is produced. The policy describes how the entity is represented; it does not make an arbitrary graph fully recoverable from Markdown.

## Extending the model deliberately

The model is most useful when its declarations have visible consequences. A new class should be inspectable, its instances should appear in the expected queries, its constraints should run at the intended boundary, and its representation should preserve the meaning the application depends on.

The [class guide](../guides/defining-new-classes.md) works through those obligations. The [shape guide](../guides/authoring-shapes.md) turns a content requirement into executable checks. The [Zorp tutorial](../guides/zorp-tutorial.md) introduces the same modeling machinery through a small inventory application.
