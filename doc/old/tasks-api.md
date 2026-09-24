# Historical workflow-backed task handles

> **Historical — superseded.** Use the [MCP concept](../concepts/mcp-protocol.md), [MCP client guide](../guides/writing-an-mcp-client.md), and [workflow substrate](../concepts/workflow-substrate.md) for current contracts.

The early task adapter reused a workflow process as the durable record of a long-running operation. The process identifier served as the handle, workflow state supplied status, and cancellation delegated to the workflow transition API. Reusing one state record avoided maintaining a separate task registry with a second lifecycle.

That design choice did not settle the wire protocol. A portable task interface also needs a defined start response, capability negotiation, status vocabulary, result retrieval, retention policy, cancellation behavior, and notification correlation. A workflow process reaching a terminal state does not establish that the operation succeeded. Success, failure, and cancellation need distinct meanings.

The former page mixed an experimental adapter's response shapes with future work and implementation-stage notes. Its payload examples, progress promises, and planned automatic dispatch rules are retired rather than presented as a current MCP Tasks specification. No completion or conformance claim is carried forward from those examples.

The useful architectural lesson remains: share the domain lifecycle, then specify and test the protocol adapter separately. See [designing workflows](../guides/designing-workflows.md) for the domain model and the [historical MCP design](mcp-server.md) for context.
