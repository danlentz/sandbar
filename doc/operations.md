# Sandbar Operations

Lifecycle workflows for the Sandbar MCP server.  See [`bin/sandbar`](../bin/sandbar) for the wrapper script.

Per [`plans/sandbar_operational_lifecycle_and_full_mcp_cutover_arc_2026_05_21.md`](../../claude/memory/plans/sandbar_operational_lifecycle_and_full_mcp_cutover_arc_2026_05_21.md) Stage X.1.

## Concepts

- **Sandbar JVM** — the Pedestal/Datomic-peer process that serves MCP on port 8080.  Started via `lein run`.
- **Datomic transactor** — separate process on port 4334; provides DB persistence INDEPENDENT of the sandbar JVM.  Killing sandbar does NOT kill the transactor; the DB state survives sandbar restarts.
- **Client project** — the consumer-side project (e.g., the memory-model corpus at `~/claude`).  Bearer tokens live PER CLIENT-PROJECT under `${CLIENT_PROJECT}/.sandbar/token`.
- **Canonical filesystem** — the client-project's `memory/` tree is the SOURCE OF TRUTH.  Per `decisions/restore_sandbar_from_durable_git_export_at_memory_open_never_rely_on_db_durability_2026_05_12.md`, the Datomic DB is a TRANSIENT computation layer.

## Daily workflow

```sh
# Check what's running
~/src/sandbar/bin/sandbar status

# Start sandbar (idempotent — no-op if already running)
~/src/sandbar/bin/sandbar start

# After a fresh DB (Datomic delete-database OR new install), if a token
# is resolvable, `sandbar start` auto-imports memory/ into the DB.
# Otherwise:
~/src/sandbar/bin/sandbar init           # explicit fresh-DB auto-import

# Stop sandbar (graceful SIGTERM with 30s wait; SIGKILL fallback)
~/src/sandbar/bin/sandbar stop

# Restart (stop + start)
~/src/sandbar/bin/sandbar restart

# Tail the server log
~/src/sandbar/bin/sandbar log
```

## Token management

Tokens live at `${SANDBAR_CLIENT_DIR}/.sandbar/token` (default: `~/claude/.sandbar/token`).  Chmod 600.  Gitignored.

```sh
# Issue a token for the corpus client + cache to .sandbar/token
~/src/sandbar/bin/sandbar rotate-token corpus <api-key>

# Override path via env (e.g., for a different client project)
SANDBAR_CLIENT_DIR=/path/to/other-project ~/src/sandbar/bin/sandbar status
```

The wrapper resolves the token in this order:

1. `SANDBAR_TOKEN` environment variable (if set)
2. `${SANDBAR_CLIENT_DIR}/.sandbar/token` file

## Fresh-DB workflow

When the Datomic DB has been wiped (e.g., `d/delete-database`), the auth/ServiceAccount records are gone too.  Bearer tokens become `:unknown-service`.

To recover:

```sh
# 1. Stop sandbar if running
~/src/sandbar/bin/sandbar stop

# 2. Re-issue a token (creates the service account in the fresh DB)
cd ~/src/sandbar
lein issue-mcp-token corpus <key>

# 3. Cache the token to client-project location
echo "corpus:<key>" > ~/claude/.sandbar/token
chmod 600 ~/claude/.sandbar/token

# 4. Start — auto-init fires since DB is empty + token is resolvable
~/src/sandbar/bin/sandbar start
# → "DB is empty — auto-importing /Users/dan/claude/memory/ ..."
# → "auto-import complete: 23378 entities across 1518 memorials (0 failed)"
```

## DB → filesystem projection (write-back)

The corpus filesystem is canonical, but the DB can mutate during a session.  To project DB state back to the filesystem:

```sh
# Default: export to a SAFE scratch dir (no canonical overwrites)
~/src/sandbar/bin/sandbar export
# → /tmp/sandbar-export-<timestamp>/memory/

# Explicit output directory
~/src/sandbar/bin/sandbar export /path/to/output

# OVERWRITE the canonical client-project memory/ (explicit opt-in)
~/src/sandbar/bin/sandbar export --canonical
```

**Warning**: the FIRST canonical export rewrites every file with normalization differences from source (description single-quoting, cardinality-many list shape, slot-order canonicalization).  This is by design per the round-trip-stability ADR — but it causes a large one-time git diff.  Subsequent exports produce zero diff (fixed-point stability).

## Status snapshot

```sh
$ ~/src/sandbar/bin/sandbar status
RUNNING — port 8080 (PID 34560)
TOKEN  — present (env)
MEMORY — 1518 :mm/Memory entities in DB
```

Three lines:

- Process state (`RUNNING` + PID OR `STOPPED`)
- Token presence (env OR cached file OR MISSING)
- Memory-count sanity (count of `:mm/Memory` instances in the DB)

## Troubleshooting

| Symptom | Likely cause | Resolution |
|---|---|---|
| `sandbar start` reports `Address already in use` | Another process holds port 8080 (likely orphaned sandbar JVM) | `lsof -i :8080 -t \| xargs kill`; then `sandbar start` |
| `sandbar status` shows MEMORY missing | Token rejected | DB may have been wiped — re-issue token + re-init |
| `sandbar start` hangs at `lein run` | Datomic transactor not running on 4334 | Start transactor; check `~/.config/datomic/transactor.properties` |
| Export takes >5s | Normal — search over 1500+ memorials is ~6s | Future arc: inverted index |

## Configuration

Environment overrides:

| Var | Default | Purpose |
|---|---|---|
| `SANDBAR_HOME` | `$HOME/src/sandbar` | Repo root |
| `SANDBAR_PORT` | `8080` | MCP port |
| `SANDBAR_CLIENT_DIR` | `$HOME/claude` | Client-project root (token + log + pid live here) |
| `SANDBAR_TOKEN` | — | Override token file resolution |

## Related ADRs

- [`decisions/sandbar_token_lives_in_client_project_not_user_global_2026_05_21.md`](../../claude/memory/decisions/sandbar_token_lives_in_client_project_not_user_global_2026_05_21.md) — token location
- [`decisions/restore_sandbar_from_durable_git_export_at_memory_open_never_rely_on_db_durability_2026_05_12.md`](../../claude/memory/decisions/restore_sandbar_from_durable_git_export_at_memory_open_never_rely_on_db_durability_2026_05_12.md) — DB is transient
- [`decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md`](../../claude/memory/decisions/round_trip_stable_normalization_acceptance_criterion_2026_05_20.md) — export normalization
