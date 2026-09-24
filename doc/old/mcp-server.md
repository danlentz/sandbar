# Historical MCP server design

> **Historical — superseded.** Use [Sandbar's MCP surface](../concepts/mcp-protocol.md), the [MCP client guide](../guides/writing-an-mcp-client.md), and the [verb catalog](../api/mcp-verbs.md) for current behavior.

The early MCP adapter explored exposing each concrete metamodel class as a constructor tool. Resource URIs identified entities, workflow definitions supplied prompts, and workflow processes supplied a possible execution model for long-running work.

The class-per-tool design was superseded by an operational catalog: a stable operation such as entity creation accepts a class argument. Class and property discovery remains available without requiring every schema change to create a new tool name. This distinction matters when the model grows beyond a small demonstration.

The lasting design questions are how to make a changing data model discoverable, how to share domain behavior across protocol adapters, and how to describe effects accurately enough for clients to compose operations. Reflection supplies information about classes and slots; it does not by itself establish authorization, transaction guarantees, or protocol compatibility.

The previous setup recipe, credentials example, transport description, per-class tool envelopes, and implementation-stage narrative are retired. They are not a compatibility contract. Likewise, an earlier source-reference count is not evidence that the current adapter respects every architectural boundary. Current protocol claims require checks against the release implementation.

For the related historical experiment, see [workflow-backed task handles](tasks-api.md).
