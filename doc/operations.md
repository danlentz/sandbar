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

## Maintenance import (quiescent — the 0.2.0 import contract)

For 0.2.0, an import into the live store is a **maintenance operation**: the server stopped, every writer excluded, fixed input files, a receipt for every step, the audit read before anything restarts.  Ordinary serialized use of the server (the MCP verbs, one caller at a time) needs none of this; the procedure below is for repairing or replacing canonical files in bulk.  The `sandbar_project_import` verb stays available over the wire as a **read-only preview** (no `persist`); persisting through it while the server runs is outside the supported 0.2.0 recipe.

**One runnable sequence** (`CLIENT` is the client-project root, `STAGING` a scratch directory, `RECEIPTS` the receipts directory):

```sh
# 0. Before-images, taken while the server is still up.
~/src/sandbar/bin/sandbar backup pre-maintenance-import-$(date +%Y%m%d)      # the store
~/src/sandbar/bin/sandbar export /tmp/store-rendering-$(date +%Y%m%d)        # the store's own rendering of every file
git -C "$CLIENT" tag pre-maintenance-import-$(date +%Y%m%d)               # the canonical files

# 1. Stop and exclude every writer: the server (its projection queue drains on stop),
#    any nREPL or admin JVM on the store, any editor or agent session touching $CLIENT/memory.
~/src/sandbar/bin/sandbar stop && ~/src/sandbar/bin/sandbar status          # must report STOPPED

# 2. Edit the selected canonical files under $CLIENT/memory, under git; record each
#    file's before and after hash in $RECEIPTS.

# 3. Stage byte-identical copies of exactly the files to import, at $STAGING/memory/<rel-path>,
#    and list deferred rows in an exclusion file (rel-paths, one per line).
mkdir -p "$STAGING/memory" && (cd "$CLIENT" && shasum -a 256 memory/<rel-path> ...) > "$RECEIPTS/staging-sha256.txt"

# 4. Preview (transacts nothing; installs nothing): read $RECEIPTS/import-preview.edn before going on.
~/src/sandbar/bin/sandbar maintenance-import --from "$STAGING" --exclude-file "$RECEIPTS/excluded.txt" --receipts "$RECEIPTS"

# 5. Persist into the existing store, pinned to that preview's basis and source hashes.
~/src/sandbar/bin/sandbar maintenance-import --from "$STAGING" --exclude-file "$RECEIPTS/excluded.txt" --receipts "$RECEIPTS" --persist

# 6. Read the receipts, then audit the store in-process while it is still stopped.
~/src/sandbar/bin/sandbar drift-audit --from "$CLIENT" --out "$RECEIPTS/audit-after.json"

# 7. Restart and wire-check: the memory count, the imported entities read back,
#    a search, sandbar_reactive_health; then commit $CLIENT with the receipts.
~/src/sandbar/bin/sandbar start && ~/src/sandbar/bin/sandbar status
```

**What the steps guarantee**

- The walker takes every `.md` under `--from`; a root mixing `memory/` with other directories is refused before any transaction (a `refused.edn` receipt).  Never point it at the client-project root.
- `replace` is the default: the file's declared slots win, an omitted source-owned slot is retracted, sections the file no longer carries are retracted, the substrate-owned slots (identity, timestamps, provenance, incoming references) are kept.  `--mode additive` opts out.  A class change, an identity conflict or a dropped section another record references is refused as a conflict, never guessed.
- The persist is pinned to the preview: a moved basis or a changed, added or removed source refuses the whole call before any transaction.  Database functions are installed only on `--persist`; a dry run leaves nothing behind.
- Optional retraction lists (`--retract-file`) and identity handoffs are outside the accepted 0.2.0 subset of this procedure; `complete?` validates the import report, not optional retraction or file effects.

**Read the receipts, not the exit code alone**

- `import-preview.edn`: `:units` with `:mode`, `:retracted-sections`, `:retracted-slots`, `:retracted-slot-attrs` (the attributes behind the count), `:retracted-carrier?`, `:conflicts`; the `:basis` and `:sources-sha256` the persist is pinned to.
- `import-persist.edn`: `:persisted`, `:conflicts`, `:failed`, `:refused`, `:final-basis`, `:reconciled?`.
- `audit-after.json`: every remaining drift row; the done-when is zero unexplained rows, each carrying a named reason, not a numerical zero.
- Exit codes: the shell wrapper exits 1 when the server is running; the JVM exits 3 when its preflight refused the run (a mixed root) before any transaction, 1 when a report is incomplete, and 0 only when the preview had no parse failure or conflict and every previewed unit persisted (`complete?`).

**Recovery**: `git checkout <tag> -- memory/` for the files; `bin/sandbar restore <backup-dir>` for the store, server stopped.

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
