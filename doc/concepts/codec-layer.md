# Codecs: keep representation choices at the boundary

**An application should be able to work with meaningful entities while readers use a suitable external representation.** A codec owns the translation between those representations. Sandbar's mediator selects a codec; model operations decide whether the parsed entity is valid and whether to accept it.

For a decision, Markdown keeps the rationale readable. For a structured client, JSON offers a familiar object representation. The two formats need an explicit account of which values and identities they preserve. Adding a format should not require each client to rediscover the class's aliases or invent a different reference convention.

## The protocol and mediator

The `sandbar.codec.protocol/Codec` protocol defines five operations:

| Method | Contract |
| --- | --- |
| `parse` | External input and options to an entity specification or collection |
| `emit` | Entity data and options to its external representation |
| `mime-types` | Supported media-type names |
| `supports?` | Whether a class is within this codec's intended fidelity contract |
| `round-trip-test` | A diagnostic with emitted text, reparsed data, and any difference |

Entity specifications are ordinary maps with `:dt/type` and namespaced slot values. A document parser may return several maps because a host, its sections, and a metadata carrier are separate entities. The result is proposed data, not evidence that a transaction occurred.

`sandbar.codec` keeps a process-local registry and dispatches calls. Selection precedence is explicit `:format`, a registered `:mime-type`, an explicit class's `:dt/native-codec`, then the emitted entity's class default. Failure to select or find a codec is an error; no universal default is assumed. Explicit `parse-mime` and `emit-mime` require a matching registration.

The registry accepts one codec per format keyword; registering again replaces that entry. Avoid multiple formats claiming the same MIME type: map iteration is not a reliable priority mechanism. The mediator delegates parsing and emission, but does not automatically enforce `supports?`. Callers and codec implementations must enforce the class boundary they promise.

Normal server startup registers Markdown. Use `sandbar.codec.list` to discover the formats available in the running server before sending a source document to `sandbar.entity.create`. The JSON entity codec exists in source but is not registered by default; JSON authoring is outside the default 0.2.0 profile. This is separate from the JSON encoding used for every MCP request and response.

## Put format knowledge where it can be reused

Class metadata supplies `:dt/native-codec`, aliases, ranges, and effective slots. The Markdown and JSON codecs use that information to map external keys and typed values. A new domain class should normally supply model declarations rather than require a switch statement in every codec.

This separation has practical limits. A format still needs explicit rules for references, unknown fields, numbers, instants, collections, and identity. Similar slot coercion in different codecs deserves shared model-level helpers where the semantics agree. A shared helper must preserve deliberate format differences rather than obscure them.

The built-in Markdown path uses a line-based frontmatter convention and a document/section model. It is not a complete YAML parser or CommonMark syntax tree. The optional JSON entity codec carries `_class` and class-relative field names. Its direct class aliases do not resolve inherited Memory fields for a subclass, and its array parse result is not supported by the single-entity create operation. These limits require separate evaluation before enabling JSON authoring. See [Markdown's supported representation](markdown-as-canonical.md) and the [protocol reference](../api/codec-protocol.md).

## State the preservation law

A useful codec test starts with a declared comparison:

```text
parse(emit(entity)) = represented entity data
emit(parse(normalized document)) = normalized document
```

The comparison may omit database-local IDs and explicitly derived fields. It must retain durable identity, supported values, relationships, and ordering that belong to the represented model. These exclusions should be named before running the test; a blanket removal of inconvenient fields can conceal loss.

A direct parse/emit check is only one layer. The complete journey also includes reference resolution, accepted database storage, read-back, and collection projection. A parser can preserve a metadata carrier that the transaction layer cannot persist. A serializer can emit readable text whose references fail to resolve in an empty target. Tests need to cross these boundaries with representative fixtures.

## Keep responsibilities distinct

Codecs translate values. The [projection layer](projection.md) groups a collection into documents and locations. The accepting mutation boundary validates and commits. The [project boundary](../firewall-and-projects.md) determines what content may reach a destination. Backup and restore include service state beyond the document model.

A codec should not silently write files, assign transport permissions, or decide how to overwrite newer accepted data. Those effects belong to explicit operations around it. Conversely, a successful HTTP request does not prove that a custom codec's promised round trip works.

Start a new codec when a real representation has a distinct reader or fidelity requirement. The [implementation guide](../guides/implementing-a-codec.md) demonstrates a small explicit EDN codec without introducing a new persistence backend. Source: [`protocol`](../../src/sandbar/codec/protocol.clj), [`mediator`](../../src/sandbar/codec.clj), [`Markdown`](../../src/sandbar/codec/markdown.clj), and [`JSON`](../../src/sandbar/codec/json.clj).
