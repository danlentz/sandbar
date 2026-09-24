# Authentication and access

Sandbar identifies a caller before deciding which operations and data that caller may use. Keep three decisions separate: whether a credential is valid, whether an operation is permitted, and whether its result is visible. A valid token or a client tool allowlist answers only part of that problem.

## Service-account credentials

An API credential has the form `<service-name>:<api-key>`. The service account stores a password hash, its active flag, optional expiry and role references. Clients present the same credential differently on the two HTTP surfaces:

| Surface | Header |
| --- | --- |
| MCP at `/mcp` | `Authorization: Bearer <service-name>:<api-key>` |
| REST under `/api` | `X-API-Key: <service-name>:<api-key>` |

The MCP path uses pre-provisioned service accounts. A Bearer header alone does not imply that Sandbar implements OAuth authorization-server discovery or an OAuth grant flow. See [the MCP client guide](guides/writing-an-mcp-client.md) for initialization and response handling.

The API-key verification cache avoids repeated password-hash work for a previously verified key. Account activity and expiry are checked on each authentication; the stored hash is part of the cache key. This cache does not grant a ten-minute exemption from account deactivation or turn a tool annotation into permission.

## Provision an account

An operator working against the intended configured database can generate a credential with:

```sh
lein issue-mcp-token example-reader
```

This command initializes the configured database/schema and prints a secret to its terminal. Store that value in the client's secret mechanism. Use the generated key rather than placing a chosen secret in a shell command argument.

Issuance creates an active account but does not assign its operational role or memory-read clearance. Assign and verify both through the installation's administration procedure before treating it as ready. An account with no role name is refused by the MCP and REST operation gates. The `:read-only` role permits classified read operations; an account carrying it is still restricted even if another role is added. Promotion therefore requires an intentional role change, followed by verification with that account's own credential. The gate currently treats any named role without `:read-only` as write-capable; it does not derive a fine-grained tool allowlist from arbitrary permission entities.

Memory read clearance is separate. The schema supports `:auth/full-clearance?` for a deliberately trusted operator; that grants visibility across compartments, not just one project. The per-project `:auth/cleared-projects` set is not yet available. A token that passes a schema query can therefore still receive no memory body, and a full-clearance token must not be described as project-restricted. Consult [project and firewall semantics](firewall-and-projects.md) before configuring access to private material.

## Rotate or retire access

```sh
lein issue-mcp-token example-reader --rotate
```

Rotation replaces the key hash and sets the account active. It prints a new credential; distribute that credential to the intended client and verify that the old one is refused. Do not use rotation merely to reactivate an account whose access is under review. For retirement, set the account inactive through the authorized administration path and verify refusal with the old credential.

A client's exposed tools are a separate configuration. Changing the account role can take effect on the server while an already running client still has a cached or filtered tool catalog. Conversely, exposing a tool in a client does not grant permission to call it. Verify both layers without copying another principal's credential.

## User sessions

The REST surface also provides user login and session operations. Public endpoints are `POST /login`, `POST /register`, and `GET /me` with authentication processing. Protected session management includes `POST /api/auth/logout`, `POST /api/auth/password`, `GET /api/auth/sessions`, and `DELETE /api/auth/sessions/:id`.

These application sessions are distinct from an MCP transport session and from a domain workflow representing someone's work. Do not substitute one session identifier for another. Inspect the [auth handlers](../src/sandbar/api/auth.clj) for the request fields and application policy before building a user-facing account flow.

## Verify the boundary you intend to depend on

For an integration, verify a permitted read, a refused operation, inactive/expired credentials, and the visible entity projection using the actual transport. For private-project access, also verify absence from listings, search, aggregates and resource metadata. Operation-level read-only access does not itself imply project isolation or safe disclosure of every stored attribute.

The [HTTP reference](api/http-rest.md), [MCP reference](api/mcp-verbs.md) and [firewall concept](firewall-and-projects.md) describe those distinct surfaces. The implementation is in [authentication utilities](../src/sandbar/util/auth.clj), [MCP authentication](../src/sandbar/mcp/auth.clj), and the [MCP dispatcher](../src/sandbar/mcp/tools.clj).
