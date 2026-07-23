# Authentication API

The sandbar authentication system provides a complete Role-Based Access Control (RBAC) implementation with support for users, groups, service accounts, roles, and permissions. It integrates with the Event system for comprehensive audit logging.

## Table of Contents

- [Overview](#overview)
- [Data Model](#data-model)
- [API Endpoints](#api-endpoints)
- [Authentication Methods](#authentication-methods)
- [Authorization](#authorization)
- [Security Features](#security-features)
- [Configuration](#configuration)
- [Events](#events)
- [Usage Examples](#usage-examples)

## Overview

The authentication system supports:

- **Form-based login** with username/email and password
- **API key authentication** for service accounts
- **Session-based authentication** via cookies or headers
- **Role-based access control** with permission inheritance
- **Account lockout** after failed login attempts
- **Session management** with expiration and idle timeout

## Data Model

### Class Hierarchy

```
                   auth/Principal [abstract]
                         |
         ________________|________________
        |                |                |
   auth/User        auth/Group     auth/ServiceAccount

   auth/Role        - Collection of permissions
   auth/Permission  - Atomic authorization unit
   auth/Session     - Active authentication session
```

### Principal (Abstract Base)

All security principals inherit from `auth/Principal`:

| Property | Type | Description |
|----------|------|-------------|
| `auth/principal-name` | string | Display name |
| `auth/roles` | ref (many) | Assigned roles |
| `auth/active?` | boolean | Can this principal authenticate? |
| `auth/created-at` | instant | Creation timestamp |
| `auth/metadata` | string | Additional data (EDN) |

### User

Human user accounts:

| Property | Type | Description |
|----------|------|-------------|
| `auth/username` | string (unique) | Login identifier |
| `auth/email` | string (unique) | Email address |
| `auth/password-hash` | string | Hashed password (bcrypt+sha512) |
| `auth/last-login` | instant | Most recent successful login |
| `auth/failed-logins` | long | Consecutive failed attempts |
| `auth/locked-until` | instant | Account locked until this time |
| `auth/groups` | ref (many) | Group memberships |

### Group

Collections of principals:

| Property | Type | Description |
|----------|------|-------------|
| `auth/group-name` | keyword (unique) | Group identifier |
| `auth/members` | ref (many) | Principals in this group |
| `auth/parent-group` | ref | Parent group for hierarchy |

### ServiceAccount

Non-human service identities:

| Property | Type | Description |
|----------|------|-------------|
| `auth/service-name` | keyword (unique) | Service identifier |
| `auth/api-key-hash` | string | Hashed API key |
| `auth/owner` | ref | User responsible for this account |
| `auth/expires-at` | instant | Optional expiration date |

### Role

Named collections of permissions:

| Property | Type | Description |
|----------|------|-------------|
| `auth/role-name` | keyword (unique) | Role identifier (e.g., `:role/admin`) |
| `auth/role-label` | string | Human-readable name |
| `auth/permissions` | ref (many) | Permissions granted |
| `auth/inherits-from` | ref (many) | Parent roles for inheritance |

### Permission

Atomic authorization units:

| Property | Type | Description |
|----------|------|-------------|
| `auth/permission-name` | keyword (unique) | Permission identifier |
| `auth/permission-label` | string | Human-readable name |
| `auth/resource-type` | keyword | Resource type (e.g., `:user`, `:inventory`) |
| `auth/action` | keyword | Action (e.g., `:read`, `:write`, `:delete`) |

### Session

Active authentication sessions:

| Property | Type | Description |
|----------|------|-------------|
| `auth/session-id` | uuid (unique) | Session identifier |
| `auth/session-principal` | ref | Authenticated principal |
| `auth/session-created` | instant | Creation time |
| `auth/session-expires` | instant | Expiration time |
| `auth/session-last-access` | instant | Last activity (for idle timeout) |
| `auth/session-ip` | string | Client IP address |
| `auth/session-user-agent` | string | Client user agent |
| `auth/session-active?` | boolean | Whether session is valid |

## API Endpoints

### Public Endpoints (No Authentication Required)

These endpoints are accessible without authentication:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/login` | POST | Authenticate with credentials |
| `/register` | POST | Register a new user account |
| `/me` | GET | Check authentication status |

### Protected Endpoints (Authentication Required)

All `/api/*` endpoints require a valid session or API key.

---

### POST /login

Authenticate with username/password. Returns a session token on success.

**Request Body:**

```clojure
{:username "zorp" :password "secret123"}
;; or
{:email "zorp@pluto.net" :password "secret123"}
```

**Success Response (200):**

```clojure
{:session-id "550e8400-e29b-41d4-a716-446655440000"
 :expires "Sun Jan 25 19:47:28 EST 2026"
 :principal {:id 17592186045575
             :username "zorp"
             :email "zorp@pluto.net"
             :name "Zorp the Merchant"}}
```

Also sets a `Set-Cookie` header with the session ID.

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Missing username/email or password |
| 401 | Invalid credentials, account inactive, or account locked |

```clojure
;; 401 response
{:error "Authentication failed"
 :reason :invalid-password}  ; or :unknown-user, :account-inactive, :account-locked
```

### POST /api/auth/logout (Protected)

End the current session.

**Headers:**
- `X-Session-ID: <session-id>` or `Cookie: sandbar-session=<session-id>`

**Success Response (200):**

```clojure
{:success true}
```

Clears the session cookie via `Set-Cookie` header.

### GET /me

Get the current authenticated user's information.

**Headers:**
- `X-Session-ID: <session-id>` or `Cookie: sandbar-session=<session-id>`

**Authenticated Response (200):**

```clojure
{:authenticated true
 :principal {:id 17592186045575
             :type :auth/User
             :username "zorp"
             :email "zorp@pluto.net"
             :name "Zorp the Merchant"
             :roles [{:name :role/viewer :label "Viewer"}]}
 :session {:id "550e8400-e29b-41d4-a716-446655440000"
           :created "Sat Jan 24 19:47:28 EST 2026"
           :expires "Sun Jan 25 19:47:28 EST 2026"
           :last-access "Sat Jan 24 20:15:00 EST 2026"}
 :permissions [{:name :permission/read-inventory
                :resource :inventory
                :action :read}]}
```

**Unauthenticated Response (200):**

```clojure
{:authenticated false}
```

### POST /register

Register a new user account.

**Request Body:**

```clojure
{:username "newuser"
 :email "new@example.com"
 :password "password123"
 :name "New User"}  ; optional, defaults to username
```

**Success Response (201):**

```clojure
{:success true
 :user {:id 17592186045581
        :username "newuser"
        :email "new@example.com"
        :name "New User"}}
```

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Missing required field or password too short (< 8 chars) |
| 409 | Username or email already exists |

### POST /api/auth/password (Protected)

Change the current user's password. Requires authentication.

**Headers:**
- `X-Session-ID: <session-id>` or `Cookie: sandbar-session=<session-id>`

**Request Body:**

```clojure
{:current-password "oldpassword"
 :new-password "newpassword123"}
```

**Success Response (200):**

```clojure
{:success true}
```

**Error Responses:**

| Status | Reason |
|--------|--------|
| 400 | Missing field, new password too short, or current password incorrect |
| 401 | Not authenticated |

### GET /api/auth/sessions (Protected, Admin)

List all active sessions. Requires `:permission/admin-sessions` permission.

**Query Parameters:**
- `user` (optional): Filter by username

**Success Response (200):**

```clojure
{:count 3
 :sessions [{:id "550e8400-e29b-41d4-a716-446655440000"
             :principal-id 17592186045575
             :created "Sat Jan 24 19:47:28 EST 2026"
             :expires "Sun Jan 25 19:47:28 EST 2026"
             :last-access "Sat Jan 24 20:15:00 EST 2026"
             :ip "192.168.1.100"
             :user-agent "Mozilla/5.0..."}
            ...]}
```

**Error Response (403):**

```clojure
{:error "Admin permission required"}
```

### DELETE /api/auth/sessions/:id (Protected, Admin)

Invalidate a specific session by ID. Requires `:permission/admin-sessions` permission.

**Success Response (200):**

```clojure
{:success true}
```

**Error Responses:**

| Status | Reason |
|--------|--------|
| 403 | Admin permission required |
| 404 | Session not found |

## Authentication Methods

### Session Authentication

The primary authentication method. Sessions are created on login and validated via:

1. **Cookie**: `sandbar-session=<session-id>`
2. **Header**: `X-Session-ID: <session-id>`

Sessions have:
- **Default duration**: 24 hours
- **Idle timeout**: 2 hours of inactivity
- **Automatic touch**: Last access time updates on each request

### API Key Authentication

For service accounts, use the `X-API-Key` header:

```
X-API-Key: service-name:api-key
```

Example:
```
X-API-Key: inventory-sync:super-secret-api-key-123
```

The service name is a keyword (without the leading colon), and the API key is verified against the stored hash.

### MCP Bearer Token Authentication

The MCP server (POST `/mcp`, GET `/mcp/sse`) uses **Bearer-token authentication** that reuses the same service-account / API-key infrastructure as the `X-API-Key` header above. The MCP layer adds a thin Bearer-extraction interceptor — token validation routes through the same `authenticate-api-key` path (Buddy-hashers under the hood).

#### Header format

```http
Authorization: Bearer <service-name>:<api-key>
```

The token is the `<service-name>:<api-key>` pair, joined by a colon, with the literal prefix `Bearer ` (case-insensitive). Example:

```http
Authorization: Bearer claude:super-secret-mcp-key
```

#### Interceptors

The MCP route's interceptor chain (in `sandbar.service.routes`):

```clojure
["/mcp" ^:interceptors [event-util/log-request
                        content/data-body
                        content/log-response
                        content/accept-content
                        params/parsed-params
                        mcp-auth/bearer-interceptor
                        mcp-auth/require-bearer]
 {:post mcp-transport/mcp-handler}
 ["/sse" {:get mcp-transport/sse-handler}]]
```

| Interceptor | Behavior |
|-------------|----------|
| `mcp-auth/bearer-interceptor` | Extracts the Bearer token, parses `<service>:<key>`, calls `authenticate-api-key`. Attaches `:identity` on success. Pass-through on missing/malformed token (so other auth interceptors can attempt the request). |
| `mcp-auth/require-bearer` | Terminates the request with HTTP 401 + `WWW-Authenticate: Bearer realm="sandbar-mcp"` (per RFC 6750) if no `:identity` is attached by any upstream interceptor. |

Note that `bearer-interceptor` is composable with `session-interceptor` and `api-key-interceptor` — `require-bearer` only checks that *some* upstream attached `:identity`, so a request authenticated by session or X-API-Key passes the MCP gate too.

#### Failure-mode responses

| Scenario | Response |
|----------|----------|
| Missing Authorization header | 401 + `WWW-Authenticate: Bearer realm="sandbar-mcp"` + `{"error":"Bearer token required"}` |
| Malformed token (no `:` separator) | 401 (same shape; warning log `:reason :token-missing-colon-separator`) |
| Unknown service name | 401 (`authenticate-api-key` returns `{:success false :reason :unknown-service}`) |
| API key mismatch | 401 (`{:success false :reason :invalid-key}`) |
| Service account inactive | 401 (`{:success false :reason :account-inactive}`) |

#### Issuing tokens for MCP clients

The fastest path for bootstrapping a service-account + emitting its Bearer-token value is the `lein issue-mcp-token` script:

```sh
$ lein issue-mcp-token corpus
;; ... logs ...

=== MCP Bearer Token Issued ===

Service:   corpus
Token:     corpus:Xk_p4z9V-2nA8m3LWtRq6F1cYbHs0jKvEdT5oIuPMyN

Set in your shell to enable the MCP client to authenticate:

  export SANDBAR_TOKEN="corpus:Xk_p4z9V-2nA8m3LWtRq6F1cYbHs0jKvEdT5oIuPMyN"

Then any MCP client (e.g., the corpus-side bb mcp-client at
~/claude/etc/lib/mcp_client.clj) will use this token when
calling http://localhost:8080/mcp.
```

Flags:

| Argument | Behavior |
|----------|----------|
| `<service-name>` | required positional; keyword-shape (e.g. `corpus`, `claude`) |
| `<api-key>` | optional positional; auto-generated (32-byte URL-safe base64) if omitted |
| `--rotate` | required when the service account already exists; replaces the API key hash |

Manual flow (the script automates this):



```clojure
(require '[sandbar.db.datatype :as dt])
(require '[sandbar.util.auth   :as auth])

(def api-key "super-secret-mcp-key")

(dt/make :auth/ServiceAccount
  {:auth/service-name   :service/claude
   :auth/principal-name "Claude MCP Client"
   :auth/api-key-hash   (auth/hash-password api-key)
   :auth/active?        true})

;; Authenticate via Bearer header
(let [token "claude:super-secret-mcp-key"
      [svc key] (clojure.string/split token #":" 2)]
  (auth/authenticate-api-key (keyword svc) key))
;; => {:success true :principal <entity>}
```

#### MCP client configuration

A typical client registration (`.mcp.json` in Claude Code, for example):

```json
{
  "mcpServers": {
    "sandbar": {
      "type": "http",
      "url":  "http://localhost:8080/mcp",
      "headers": {
        "Authorization": "Bearer ${SANDBAR_TOKEN:-disabled}"
      }
    }
  }
}
```

The `:-disabled` shell-expansion fallback means the registration is committed-but-inert until the operator sets `SANDBAR_TOKEN` in their shell. See [doc/mcp-server.md](mcp-server.md) for the full MCP surface.

## Authorization

### Role-Based Access Control

Permissions are granted through roles:

```
User -> Role(s) -> Permission(s)
          |
          v
     inherits-from -> Parent Role(s) -> Permission(s)
```

### Permission Checking

```clojure
(require '[sandbar.util.auth :as auth])

;; Check if user has a specific permission
(auth/has-permission? user :permission/edit-inventory)

;; Check if user has a specific role
(auth/has-role? user :role/admin)

;; Get all permissions for a user
(auth/get-principal-permissions user)
```

### Route Protection

Use interceptors to protect routes:

```clojure
;; Require any authentication
["/protected" ^:interceptors [auth/require-authentication]
  ...]

;; Require specific permission
["/admin" ^:interceptors [(auth/require-permission :permission/admin)]
  ...]

;; Require specific role
["/editors" ^:interceptors [(auth/require-role :role/editor)]
  ...]
```

## Security Features

### Password Hashing

Passwords are hashed using bcrypt+sha512:

```clojure
(auth/hash-password "secret")
;; => "bcrypt+sha512$..."

(auth/verify-password "secret" hash)
;; => true
```

### Account Lockout

After 5 consecutive failed login attempts, the account is locked for 15 minutes:

- Failed attempts are tracked in `auth/failed-logins`
- Lock expiration is stored in `auth/locked-until`
- Counter resets on successful login

### Session Security

Session cookies are configured with:
- `HttpOnly` - Not accessible via JavaScript
- `SameSite=Strict` - Prevents CSRF attacks
- `Path=/` - Available to all routes

## Configuration

Dynamic variables control authentication behavior:

| Variable | Default | Description |
|----------|---------|-------------|
| `*session-duration-ms*` | 24 hours | Session lifetime |
| `*session-idle-timeout-ms*` | 2 hours | Idle timeout |
| `*max-failed-logins*` | 5 | Failed attempts before lockout |
| `*lockout-duration-ms*` | 15 minutes | Lockout duration |

Override at runtime:

```clojure
(binding [auth/*max-failed-logins* 3
          auth/*lockout-duration-ms* (* 30 60 1000)]  ; 30 minutes
  (auth/authenticate-user "zorp" "password"))
```

## Events

All authentication events are logged via the Event system:

| Event Kind | Description |
|------------|-------------|
| `:auth/login-success` | Successful authentication |
| `:auth/login-failure` | Failed authentication attempt |
| `:auth/logout` | User logged out |
| `:auth/registration` | New user registered |
| `:auth/password-change` | Password changed |
| `:auth/session-invalidated` | Admin invalidated a session |
| `:auth/access-denied` | Authorization check failed |

Events include:
- `event/actor` - The principal involved (if known)
- `event/tags` - Set including `:auth` and optionally `:security`, `:admin`

## Usage Examples

### Creating a User with Roles

```clojure
(require '[sandbar.db.datatype :as dt])
(require '[sandbar.util.auth :as auth])

;; Create permissions
(def read-perm
  (dt/make :auth/Permission
    {:auth/permission-name :permission/read-inventory
     :auth/resource-type :inventory
     :auth/action :read}))

(def write-perm
  (dt/make :auth/Permission
    {:auth/permission-name :permission/write-inventory
     :auth/resource-type :inventory
     :auth/action :write}))

;; Create a role with permissions
(def editor-role
  (dt/make :auth/Role
    {:auth/role-name :role/editor
     :auth/role-label "Editor"
     :auth/permissions [(:db/id read-perm) (:db/id write-perm)]}
    {:validate? false}))

;; Create a user with the role
(def user
  (dt/make :auth/User
    {:auth/username "zorp"
     :auth/email "zorp@pluto.net"
     :auth/password-hash (auth/hash-password "secret123")
     :auth/principal-name "Zorp the Merchant"
     :auth/active? true
     :auth/roles [(:db/id editor-role)]}
    {:validate? false}))
```

### Creating a Service Account

```clojure
(def api-key "super-secret-api-key")

(def service
  (dt/make :auth/ServiceAccount
    {:auth/service-name :service/inventory-sync
     :auth/principal-name "Inventory Sync Service"
     :auth/api-key-hash (auth/hash-password api-key)
     :auth/active? true}))

;; Authenticate with API key
(auth/authenticate-api-key :service/inventory-sync api-key)
;; => {:success true :principal <entity>}
```

### Authenticating and Creating a Session

```clojure
(let [result (auth/authenticate-user "zorp" "secret123")]
  (when (:success result)
    (let [session (auth/create-session! (:principal result)
                                        :ip "192.168.1.100"
                                        :user-agent "MyApp/1.0")]
      (println "Session ID:" (:auth/session-id session))
      (println "Expires:" (:auth/session-expires session)))))
```

### Checking Permissions in Code

```clojure
(defn update-inventory [user item-id changes]
  (if (auth/has-permission? user :permission/write-inventory)
    (do-update item-id changes)
    (throw (ex-info "Permission denied" {:required :permission/write-inventory}))))
```

### Role Inheritance

```clojure
;; Create a base viewer role
(def viewer-role
  (dt/make :auth/Role
    {:auth/role-name :role/viewer
     :auth/role-label "Viewer"
     :auth/permissions [(:db/id read-perm)]}
    {:validate? false}))

;; Editor inherits from viewer
(def editor-role
  (dt/make :auth/Role
    {:auth/role-name :role/editor
     :auth/role-label "Editor"
     :auth/permissions [(:db/id write-perm)]
     :auth/inherits-from [(:db/id viewer-role)]}
    {:validate? false}))

;; Editor has both read and write permissions
(auth/get-role-permissions (db/entity (:db/id editor-role)))
;; => [<read-perm> <write-perm>]
```

## Interceptor Reference

| Interceptor | Description |
|-------------|-------------|
| `session-interceptor` | Loads session from cookie/header, attaches `:identity` |
| `api-key-interceptor` | Authenticates via `X-API-Key` header |
| `authentication-interceptor` | Combined: tries session, then API key |
| `require-authentication` | Returns 401 if not authenticated |
| `(require-permission p)` | Returns 403 if missing permission `p` |
| `(require-role r)` | Returns 403 if missing role `r` |

## HTTP Response Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 201 | Created (registration) |
| 400 | Bad request (missing/invalid parameters) |
| 401 | Unauthorized (authentication required/failed) |
| 403 | Forbidden (insufficient permissions) |
| 404 | Not found (session not found) |
| 409 | Conflict (username/email exists) |
