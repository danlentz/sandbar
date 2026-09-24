# Operate a Sandbar instance

An operational Sandbar instance has a database, an HTTP process, application configuration, and any configured projection or scheduler workers. A listening port proves only that a process answered. Readiness means the intended database and model are available through an authenticated client, with the required workers and recovery path verified.

## Make configuration explicit

The application merges bundled configuration, the client directory's `.sandbar/config.edn`, and supported environment overrides. `SANDBAR_CLIENT_DIR` selects the client directory; the `sandbar.client-dir` JVM property and process working directory are fallbacks. The shell wrapper has its own historical default, so set the directory explicitly when using it.

Important inputs include the database settings, `SANDBAR_PORT`, `SANDBAR_NREPL_PORT`, `SANDBAR_DB_SID`, and `SANDBAR_DB_URL`. A `sandbar.db.uri` JVM property overrides the no-argument database URI lookup. Review [the configuration example](../config/config-example.edn) and [configuration loader](../src/sandbar/config.clj) for the supported keys; an environment variable with a plausible name is not necessarily consumed.

Set the projection destinations explicitly. `SANDBAR_CORPUS_ROOT` supplies the global fallback (otherwise `$HOME/claude`). The service configuration's `:project-roots` map assigns an enrolled Project's stable key to an absolute directory; its files go under that directory's `memory/` subtree. Unmapped projects and `UNASSIGNED` retain the global route. A Project's `mm.project/corpus-repo` remains descriptive metadata and cannot grant filesystem access. Database identity, service configuration directory and projection roots are separate values. See [serve a project repository](#serve-a-project-repository) for enrollment and maintenance requirements.

The client configuration's `:project` key names a stable project key (`mm.project/ident`). It selects the *logical* active project of the service whose client directory holds the file — `SANDBAR_PROJECT` and the `sandbar.project` JVM property take precedence over it — and an absent or unresolvable value selects the private `:project/UNASSIGNED` sentinel. This is operator-controlled configuration, not an authorization boundary and not a per-request binding: a client process elsewhere cannot bind its requests to a project by writing this key into its own checkout.

For example, after choosing absolute paths for this installation:

```sh
export SANDBAR_HOME="/absolute/path/to/sandbar"
export SANDBAR_CLIENT_DIR="/absolute/path/to/client-project"
```

Create `.sandbar/config.edn` in the client directory with the installation's `:db {:url ... :sid ...}`, `:port`, and `:nrepl {:port ...}` values. Nested maps merge with bundled defaults; keep the bundled required-schema list unless deliberately defining a different complete model. The committed example supplies defaults on a fresh checkout when `config/config.edn` is absent. Its `example` database is a placeholder, not a reason to reuse an existing database.

## Start, inspect and stop

From the Sandbar checkout, after configuring the database and its transactor, start the application in the foreground or under the installation's process supervisor:

```sh
cd "$SANDBAR_HOME"
lein run
```

The application does not start a Datomic transactor. Its HTTP component depends on database initialization, so the port opens after that component has loaded schema and established its connection. The usual application port is 8389; use the configured port when checking it.

`bin/sandbar status`, `log`, and `stop` are useful lifecycle helpers when their client directory and port identify this installation. The wrapper's `start` and `init` commands retain an older automatic-import path with legacy arguments and a count check parsed from text. That is not the maintenance-import procedure below. Prefer the explicit application start for a new installation or a maintenance exit, and import the selected files separately.

After startup, run the [MCP quickstart](guides/quickstart.md) using the intended service account. Check initialization, a typed read and the expected model. Verify the required projection/scheduler state separately. A successful class listing is not proof that deferred file writes have drained or persisted schedules are enrolled for execution.

```sh
bin/sandbar stop
```

The wrapper requests graceful termination and has a bounded force-stop fallback. Before stopping an instance doing work, establish which writes or jobs must settle and how incomplete work will be detected on return. Interrupting a job is not evidence that its external effects have stopped. Consult [reactive behavior](concepts/reactive-substrate.md) and [workflow execution](concepts/workflow-substrate.md) for these ownership boundaries.

### Seed the verb catalog for a fresh store

`tools/list` and the `initialize` instructions describe the verb surface from source. `sandbar.tools.search` and `sandbar.tools.describe` answer from persisted `mm/Verb` cards instead, and nothing on the start path writes them. A store that was never seeded therefore advertises the discovery verbs and then answers no matches and a miss for every verb. Startup reads the persisted catalog after the database is up and logs one of `:SYS/VERB-CATALOG-EMPTY`, `:SYS/VERB-CATALOG-COUNT-MISMATCH`, `:SYS/VERB-CATALOG-PRESENT` or `:SYS/VERB-CATALOG-CHECK-FAILED`; it never seeds, and the server continues in every case. Treat the first two as a maintenance item for a new deployment, or for an existing one after the source catalog changed.

Seed in a stopped-server window. The seed command is a separate database peer that loads the schema and upserts one card per source verb; run it from the same checkout revision as the server, with the same `SANDBAR_CLIENT_DIR` and configuration, so it reaches the same database. The transactor stays up. Do not run it while the server is serving: the running server's search cache for `mm/Verb` is built at startup and refreshed only by its own MCP writes, so a seed applied beside a live server leaves `sandbar.tools.search` answering from stale cards until the next restart, and it would be a second writer beside the server's own.

```sh
bin/sandbar stop
cd "$SANDBAR_HOME"
lein seed-verb-catalog
lein run
```

The seed prints a summary line with the seeded count; a `SKIPPED` line names a prerequisite edge that would close a cycle, and a `FLAGGED` line names prose references it could not resolve to verbs. Both are review notes about derived edges, not seed failures, and the counts are not acceptance of those edges. It is idempotent by upsert on the verb name; repeating it does not duplicate cards.

Verify after the restart, not from the seed's own output: the startup log shows `:SYS/VERB-CATALOG-PRESENT`, and `sandbar_tools_describe` of one known verb through an authenticated client answers a card rather than a miss. The startup check compares counts only. An equal count is not proof that every persisted card matches this checkout's source catalog; `lein catalog-check` compares source against the generated documentation, not against the database, and no released command reconciles the persisted cards field by field.

## Serve a project repository

One service serves several code repositories. Each repository keeps its own AI-client settings; the service holds the shared store; captures name their project; a later session from any clone of the repository reopens the same store. The operator enrolls the project and authorizes its destination before clients begin ordinary work.

1. **Enroll the project.** Write the Context and Project records with every value explicit, then the context membership, as [projects and directional information flow](firewall-and-projects.md#enroll-a-project-and-declare-its-privacy) describes. Record the Project's document ident and its stable key separately; clients will need both.
2. **Authorize its destination.** After enrollment, add the stable key and absolute repository root to `:project-roots` in the service's configuration. Follow the stopped-writer procedure below before ordinary client capture; changing a client's working directory or Project metadata cannot change this map. Preserve the global root for unmapped records.
3. **Install the client binding.** Give the client the Project's document ident, stable key, declared privacy, MCP URL and credential environment-variable name. The corpus's `mem onboard-project` previews the local binding and installs it only with `--apply`; it full-reads the enrolled Project and verifies its identity and declared privacy. Follow the [client installation guide](https://github.com/danlentz/claude/blob/master/doc/project-onboarding.md) for Codex or Claude Code. The repository keeps its own settings. The installer creates neither enrollment records nor destination grants, restricted credentials or trust decisions. The service does not read the remote client's `.sandbar/config.edn`: before capture, the client must still verify the Project and pass its entity reference as `mm.memory/owning-project` on each `sandbar_entity_create`, with intended visibility. The [getting started](guides/getting-started.md#working-from-a-project-repository) guide covers capture and readback.
4. **Reopen needs nothing from you.** A new client session reads the existing store. Do not export, import or replace the database to start a session; the maintenance import below is for edited canonical files with all writers stopped.
5. **Publication stays manual.** Use the guarded export preview and private audit below, then review the accepted staging subset. Whole-document holds are supported; automatic redaction, git checkpoints and guarded restore remain outside this increment.

### Install or change a project destination

The service's `.sandbar/config.edn` can include, after the named Project exists:

```clojure
{:project-roots {:project/clj-figlet "/absolute/path/to/clj-figlet"}}
```

This is an operator setting on the service host. A remote code checkout's configuration does not authorize a directory on that host. Roots must be absolute directories (or have an existing parent), must not overlap each other or the global root, and must contain their own `memory/` trees. Unknown project keys, an escaped `memory/` symlink and unsafe relative paths refuse. The service validates the map before opening its request port. With mappings enabled, file paths must use their canonical spelling: no dot segments or symlink aliases within the memory tree.

For initial enrollment, install the destination only after the Project exists and all writers are stopped. An existing legacy file tree can use the [canonical enrollment procedure](#enroll-an-existing-legacy-tree) below. If the Project already owns stored records in another tree, treat enrollment as a migration: preserve the store and file before-images, enumerate the affected records by exact identity, and reconcile each old and proposed path before changing the operator map. Import does not move or adopt existing records between trees. Hold ambiguous files for explicit disposition, and do not resume normal work until selected files and per-root audits agree with the existing store. Changing or removing a mapping also requires this maintenance window; a config reload during ordinary use is unsupported.

Before resuming ordinary writers under the mapping, verify these preconditions for the selected documents and any records claiming the same physical targets:

- Each stored document has a durable `mm/id`, and each existing file has one unambiguous, matching `id:`. A new document can receive an absent UUID during import and have it written to its canonical source with `--stamp`. Existing unidentified records need a separate disposition; a store-only backfill does not establish file ownership.
- Stored relative paths have canonical spelling and select distinct physical targets. Check historical dot segments, symlink aliases and case aliases on the actual filesystem; the exact-string claimant query is not an alias census.
- Every explicit owning-project reference resolves to a Project and selects the intended tree. A malformed owner can block projection, deletion and correction by ordinary owner updates while mappings are enabled.

The drift audit reports these checks under `:enrollment-preflight`, including missing or ambiguous file UUIDs, missing stored UUIDs, ownership mismatches, physical path aliases, invalid owners and read/parse errors. Read `:clear-for-enrollment?`, `:unresolved-count`, `:error-count` and the named rows; an ad hoc directory can never establish a clear tree. Invalid owners also have a separately reported store-wide population. Named exclusions are permitted: preserve unresolved versions and keep their paths outside the enrolled subset and ordinary mapped writes. An import exclusion does not disable projection, so leave the affected mapping disabled if those paths cannot be isolated. Repairs need a separate reviewed maintenance action; neither blanket export nor automatic adoption is part of enrollment.

Ordinary entity edits refuse a change that would move an existing file, alter its durable UUID, or rename a mapped Project's stable key. A mapped Project is protected from ordinary retraction until its mapping is removed through maintenance. Writes and deletes check the selected physical root and existing file identity; uncertain or duplicate file identities are retained. Different unmapped projects still share the global tree, so a different project key does not excuse a path collision. Historical path aliases must be reconciled or excluded before enrollment; the indexed claimant check compares stored relative paths, not a general filesystem alias census.

One store still has one global identity namespace. Import derives a document's ident from its relative path, so different enrolled documents must use different relative paths even across separate project trees. Supplying different UUIDs does not change that path-derived ident. Use project-specific paths where names would otherwise collide. Persistence routing also does not filter private material for publication. Keep the destination under operator control and apply the publication review separately.

## Export into a staging destination

The guarded exporter selects one explicitly enrolled project, checks caller and destination permissions, and holds an entire document if an emitted reference or supported content cannot be preserved safely. Held originals remain in the existing store and canonical tree. It never exports the whole store by default, overwrites canonical files, or removes private fields to make a document pass.

First, the operator configures a named destination alongside the existing `:project-roots` mapping. Paths below are examples, not directories the exporter creates:

```clojure
:project-roots {:project/example "/absolute/source/memory"}
:export-destinations
{"review-public"
 {:project :project/example
  :audience :public
  :staging-root "/absolute/staging"
  :audit-root "/absolute/private-export-audit"}}
```

The staging and audit roots must already exist and be separate from every source tree; the audit root must have mode 0700 and be separate from every staging root. Use `:audience :private` for a destination in the selected project's private compartment. The mapping's `:project` is the stable project key; the command's `--project` is the enrolled Project entity ident. No destination is enabled by default.

Keep other writers, including agents and scheduled clients, stopped from preview through execution and audit. The server must remain available to serve the export requests. This is an attended procedure: the final basis check detects changes before writing but does not lock out concurrent writers.

With the reviewed configuration loaded and an authorized token, preview a fresh direct child of the staging root:

```sh
bin/sandbar export /absolute/staging/review-one \
  --project :memory.projects/example --destination review-public
```

Preview writes a **private audit only** and returns included/held counts, the database basis, an opaque audit reference and a plan token. It creates no staging output. Read the matching `<audit-ref>-preview.edn` in the configured audit root; reconcile each held document and dependency before executing. Exact held identities and reasons remain there, rather than in the MCP response or output manifest.

Use the same arguments and the returned token:

```sh
bin/sandbar export /absolute/staging/review-one \
  --project :memory.projects/example --destination review-public \
  --execute --expect-plan '<token-from-preview>'
```

The server replans and refuses a changed token, store identity, basis or destination. It writes into a new directory with mode 0700; public documents have mode 0644, private documents and audits 0600. Documents land directly at `<dir>/<rel-path>`, without an added `memory/` directory. Only `status: complete` together with `complete?: true` and a valid `export-manifest.edn` marks completion. The manifest binds the basis, included UUIDs, file hashes and permitted dependencies; review it with the private audit. Completion can include held documents: it means the accepted subset was written, not that every source document was exportable.

Check the completed tree before using it for import or a later checkpoint:

```sh
bin/sandbar verify-export /absolute/staging/review-one
```

This local Babashka command checks the completion marker, exact file set and byte hashes. It rejects missing, extra or changed files, malformed or inconsistent file lists, unsafe paths and symbolic links. It writes nothing and does not contact Sandbar. Exit 0 returns a small EDN receipt with `status :verified`, `scope :file-integrity`, the document count and `manifest-sha256`; a refusal exits 1 and bad arguments exit 2. Keep the tree settled during the check.

Retain the manifest digest with the reviewed export receipt outside the export tree. On a later check, use `--expect-manifest '<retained-sha256>'` to require those same manifest bytes. An unpinned check only compares files with the supplied manifest; someone changing both can preserve that agreement. Neither form verifies private audit custody, source-store lineage, database freshness or publication permission. Use the origin comparison below for the separate store/snapshot check.

Errors or partial writes return failure. Partial staging is retained for inspection and has no accepted completion marker; never publish it or reuse that directory as the next export target. Sources and previous output directories are preserved. Optional `--provenance` creates a Run only when server-side recording is also enabled; recording is off by default and does not control the safety checks. The wrapper checks transport, protocol, tool-error and completion envelopes before reporting success.

Eligibility requires source ownership, a durable UUID and compatible labels for every emitted memory reference, including author and project references. Missing visibility is private; an unowned author does not automatically become part of the selected project's compartment. Older unstamped corpus records can therefore remain held until separately approved enrollment/identity work is done. Preview is the executable inventory of those holds.

This first version supports typed references, bare memory paths in string fields, unique exact-name/path wiki links and simple inline links. Unknown frontmatter extras, ambiguous/unresolved links, unsupported reference-style/HTML markup, body/section disagreements and lossy round trips hold the whole document. Ordinary fenced and inline code, free prose and external URLs remain author-classified content; the exporter cannot recognize a paraphrase of private information. Code bytes still have to pass the content-preservation checks. Publication remains a separate review of the written subset; no automatic redaction, git checkpoint or guarded restore is provided. See [projection](concepts/projection.md) and [project boundaries](firewall-and-projects.md).

## Compare a completed export with its source store

Before considering an older export for import, use the read-only origin check against the intended running store:

```sh
bin/sandbar recovery-check /absolute/staging/review-one \
  --project :memory.projects/example --destination review-public \
  --expect-manifest '<retained-manifest-sha256>'
```

The directory must be an existing direct child of the named destination's staging root. The server verifies its exact file set and hashes, finds the matching ready receipt only in the configured private audit root, and compares its recorded database identity and basis with one observed current snapshot. The ready receipt binds the exact manifest bytes, project, destination, audience and file rows; it also retains the export's filter context. Older receipts lacking the origin binding yield `origin-unverified`.

This diagnostic reads private operator evidence. It requires an authenticated full-clearance account; that account may have the read-only role. Project visibility alone does not grant access. The CLI reads existing configuration and credentials, creates no client directory, and sends only this read request. It uses `SANDBAR_TOKEN` or the existing client `.sandbar/token`; port resolution follows `SANDBAR_PORT`, client config, backend config, then 8389.

| State | Meaning | CLI exit |
|---|---|---|
| `same-store-same-basis` | Verified receipt identifies the observed store and the same basis | 0 |
| `store-advanced` | Same store, current basis is later | 3 |
| `store-behind-export` | Same store identity, current basis is earlier | 3 |
| `different-store` | Recorded and observed store identities differ; bases are not ordered | 3 |
| `origin-unverified` | A trusted matching origin receipt could not be established | 3 |

The EDN result states `scope :snapshot-comparison`, selected file count, audience, held count, manifest hash and `import-approved? false`. An `origin-unverified` result includes a finite `reason` such as `audit-missing` or `manifest-hash-mismatch`; exit 3 includes this state and does not imply that the store moved. Authorization, integrity, transport and protocol failures exit 1; local argument or setup failures exit 2. A failed request is never reported as an origin state.

Basis movement does not prove any selected document changed: an unrelated write or the export's optional provenance record also advances it. Matching identity and basis does not prove safe import, complete project/store coverage, or authenticity of the private audit. Preserve that audit under trusted operator custody. A public or filtered export covers only its listed documents; held content stays outside it. Keep the tree settled during checking, reconcile differences, then separately follow the attended import procedure. No comparison result authorizes Git checkpointing, publication, store replacement or a live repair.

## Maintenance import into the existing store

The supported 0.2.0 bulk-import procedure is a maintenance window: stop all writers **before editing canonical files**, keep them stopped through preview, import and audit, and use the existing store so its identities and references can be retained. Ordinary client work does not need this procedure. A read-only `sandbar_project_import` preview can aid investigation while the service runs; persisting a bulk import during live use is outside this procedure.

For input from a completed guarded export, verify its completion manifest, exact file set and hashes before previewing the import. `project.import` walks the Markdown files; it does not consume the export manifest or enforce its lineage. Its basis and source pins bind the reviewed import input; they do not detect an older export that predates intervening store edits. Use the origin comparison above, then reconcile any older export with current content before selecting it for import. Documents absent from the exported subset remain in the existing store; a hold is never a deletion instruction.

Review staged metadata as well as the body. Import retains protected metadata when omitted, but explicit values can replace stored values, including `visibility`. Owner, UUID and class changes still face their existing guards.

Select an evidence-backed set of file changes. Preserve both versions of unresolved historical discrepancies, give each a recorded reason, and exclude their paths. Equal bodies or a larger stored body alone do not prove which copy is authoritative. Unproven twin deletion, identity handoff and adoption of existing records with unresolved identity or ownership require their own resolution; they are not part of this recipe.

With a nonempty `:project-roots` map, preview and persist check destination ownership. A genuinely new document read from a mapped tree receives that tree's Project as owner when the file declares no owner; the operator's map supplies the default. An existing document must already belong to that Project or explicitly name a compatible owner; an existing unowned document remains held. An asserted owner must resolve to a Project, and an explicit conflicting owner is never replaced by the default. Changes that cross physical trees are refused, including adopting an existing unowned document into a mapped tree or sending a mapped document to another tree. Import from an unmapped staging directory is supported, but staging does not supply the mapped-root default or bypass owner and move checks. With an empty map, this destination layer adds no checks and the importer's existing validation still applies.

For a document new to the store, import supplies a UUID only when neither the source nor an existing forward placeholder has one. Supplied and stored identities are preserved; existing typed documents without a UUID are not automatically repaired. Minting the database identity alone does not authorize overwriting a file without a matching `id:`.

Mapped Project keys must keep naming the same Project. Both replacement and additive import refuse a key rename or another document's takeover of that key. Omitting a mapped key in replacement mode is refused because it would retract the key; omitting it in additive mode retains the stored key. These checks also apply from staging.

The import guard checks owner-driven changes between trees. It does not impose ordinary updates' refusal of every file move: import can assert a changed `mm.memory/rel-path` within the same tree. Review the old and proposed paths and reconcile both files during maintenance; do not treat an ordinary update's refusal as proof that import will refuse the same path change.

### Enroll an existing legacy tree

Use this path for canonical files that are new to the existing store, including legacy documents with no `id:` or `owning-project:` line. It completes their initial identity binding without re-rendering their bodies. For selected repairs to already enrolled documents, use the staged recipe that follows.

1. Enroll the Context and Project with explicit privacy and record their identities as described above. Stop **all** database and filesystem writers before changing the destination map or any canonical file. Take and verify a database backup, preserve byte-for-byte file before-images, and record the selected files, their hashes and any exclusions. Follow the [freeze and backup checks](#1-freeze-writers-and-preserve-before-images) below. Keep the same store throughout.
2. Install the Project's stable key and canonical repository root in the service's `:project-roots` map. Keep writers stopped. Use its exact `memory/` directory as the import root, so unrelated repository Markdown is not walked. Set `SANDBAR_CLIENT_DIR` to the service's configuration directory, even when the enrolled repository lives elsewhere.

Prepare `excluded.txt` in the receipts directory before the preview, empty if there are no exclusions. Its entries are relative to `memory/`, for example `observations/deferred.md`, because `--from` names that directory directly. Freeze and compare a manifest of source names, bytes and exclusions between preview and persist; the [manifest helper](#2-edit-and-stage-only-the-selected-files) below also works with the canonical `memory/` directory as its root.

```sh
set -eu
PROJECT_ROOT="/absolute/path/to/project"
CANONICAL="$PROJECT_ROOT/memory"
ENROLLMENT_RECEIPTS="/absolute/path/to/private-enrollment-receipts"
mkdir -p "$ENROLLMENT_RECEIPTS/dry" "$ENROLLMENT_RECEIPTS/persist"

bin/sandbar maintenance-import --from "$CANONICAL" --stamp \
  --exclude-file "$ENROLLMENT_RECEIPTS/excluded.txt" \
  --receipts "$ENROLLMENT_RECEIPTS/dry"
```

3. Review `dry/import-preview.edn`: exact sources, assigned owners and identities, retained references, replacement effects, parse failures and conflicts. Proceed only with the evidence-backed subset and unchanged inputs. The persist command computes a fresh preview; it does not consume the earlier receipt.

```sh
bin/sandbar maintenance-import --from "$CANONICAL" --persist --stamp \
  --exclude-file "$ENROLLMENT_RECEIPTS/excluded.txt" \
  --receipts "$ENROLLMENT_RECEIPTS/persist"

bin/sandbar drift-audit --from "$CANONICAL" \
  --out "$ENROLLMENT_RECEIPTS/audit-after.json"
```

4. Read both preview receipts, `persist/import-persist.edn` and `persist/identity-stamp.edn`. `--stamp` with `--persist` requires a receipts directory. It adds only the newly minted `id:` line to the existing frontmatter of the exact canonical source the import persisted, preserving the other UTF-8 source bytes, including comments, whitespace and line endings. Plain Markdown without frontmatter remains held; explicitly exclude documentation that is outside the selected memory population. A separate staging copy cannot receive this canonical stamp. Changed sources, foreign or ambiguous IDs, conflicting path claimants and existing documents needing identity repair remain named holds. The stamp receipt records each outcome and resulting hash; compare those intentional changes with the before-images rather than expecting the post-stamp manifest to remain identical.
5. Review the per-tree audit's `:enrollment-preflight` and all content/reference discrepancies. Resume writers only when the enrolled tree is clear, or every remaining finding has a recorded disposition and its named paths are truly isolated from ordinary mapped writes. Import exclusions alone do not provide that isolation. Then follow the [service reopening checks](#4-audit-before-reopening-the-service).

Import and stamping are separate steps: a partial stamp can leave documents committed while some files remain unchanged. Keep writers stopped and retain the original `import-persist.edn`. An operator can replay that receipt through `sandbar.project.enrollment-stamp/stamp!` only with the same store and canonical root, in the same maintenance window, while its `:final-basis` still matches the database. Already completed stamps are recognized without another edit; changed sources or a moved basis are held. Re-running the import is a new import, not replay of the original stamp receipt. Reconcile any incomplete result before resuming or recovering.

The following staged procedure is for reviewed repairs; omit `--stamp` there because its input files are copies rather than canonical destinations.

### 1. Freeze writers and preserve before-images

Choose an empty staging directory and a separate receipts directory. Set `CLIENT` to the configured project root or global corpus root whose files you will change and audit. Keep `SANDBAR_CLIENT_DIR` pointing to the service's configuration directory; those directories can differ. The following is an operator template: supply the actual paths and selected file list before running it. It uses Babashka (`bb`) for a deterministic content manifest.

```sh
set -eu
CLIENT="/absolute/path/to/corpus-or-project-root"
STAGING="/absolute/path/to/empty-import-staging"
RECEIPTS="/absolute/path/to/private-import-receipts"
mkdir -p "$STAGING/memory" "$RECEIPTS/dry" "$RECEIPTS/persist"

bin/sandbar stop
bin/sandbar status
```

Require `STOPPED`, and separately stop other writers: admin/nREPL JVMs connected to this store, scheduled processes outside Sandbar, agents, editors and synchronization tools that can touch the canonical or staging files. The port check detects a listener, not every possible writer. A force-killed process also needs an incomplete-work review before proceeding.

Take and verify a native database backup, preserve a byte-for-byte copy of the canonical files including uncommitted edits, and record their hashes. A Git revision or tag only names committed content. Keep these before-images through the final audit. Use the [recovery procedure](#choose-the-right-recovery-artifact) already verified for this installation.

```sh
bin/sandbar backup pre-maintenance-import
bin/sandbar drift-audit --from "$CLIENT" --out "$RECEIPTS/audit-before.json"
```

### 2. Edit and stage only the selected files

Edit the selected canonical files while the writers remain stopped. Put their relative paths in `selected.txt`, one per line, relative to `memory/`; use ordinary file paths without parent traversal. Put deferred paths in `excluded.txt` relative to the staging root, including the `memory/` prefix used in this layout. Create an empty exclusions file if there are none. Keep both lists with the rationale and before/after file hashes.

```sh
while IFS= read -r relative; do
  test -n "$relative" || continue
  mkdir -p "$STAGING/memory/$(dirname "$relative")"
  cp -p "$CLIENT/memory/$relative" "$STAGING/memory/$relative"
done < "$RECEIPTS/selected.txt"
```

The staging root must contain exactly these regular files under `memory/`, with no symlinks or unrelated Markdown. The importer walks every `.md` beneath `--from`. A root mixing `memory/` with other Markdown locations is refused, but that guard is not a substitute for selecting the right root. Exclusions match the preview's exact `:source` value, such as `memory/decisions/example.md`; the stored memory's relative path can omit that leading prefix.

Save this small manifest helper in the receipts directory. It records names as well as hashes, so added and removed files are detectable:

```sh
cat > "$RECEIPTS/staging-manifest.bb" <<'BB'
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])
(import '[java.math BigInteger] '[java.security MessageDigest])
(defn sha256 [path]
  (format "%064x" (BigInteger. 1 (.digest (MessageDigest/getInstance "SHA-256")
                                        (fs/read-all-bytes path)))))
(let [[root-name exclusions] *command-line-args*
      root (fs/path root-name)]
  (when (or (fs/sym-link? root) (not (fs/directory? root)))
    (throw (ex-info "source root must be a directory, not a symlink" {})))
  (let [entries (fs/glob root "**" {:hidden true :follow-links false})]
    (when (some fs/sym-link? (conj entries (fs/path exclusions)))
      (throw (ex-info "source tree and exclusions must not contain symlinks" {})))
    (let [files (into (sorted-map)
                      (for [p entries
                            :when (and (fs/regular-file? p)
                                       (str/ends-with? (str p) ".md"))]
                        [(str (fs/relativize root p)) (sha256 p)]))]
      (println (json/generate-string
                 (sorted-map "files" files "exclusions" (sha256 exclusions))
                 {:pretty true})))))
BB
bb "$RECEIPTS/staging-manifest.bb" "$STAGING" "$RECEIPTS/excluded.txt" \
  > "$RECEIPTS/staging-before.json"

bin/sandbar maintenance-import --from "$STAGING" \
  --exclude-file "$RECEIPTS/excluded.txt" --receipts "$RECEIPTS/dry"
```

### 3. Review, then persist without changing inputs

Read `dry/import-preview.edn` before continuing. Check the exact source set, expected identities, mode, dropped sections, omitted slots and every conflict or parse failure. Replacement is the default: omitted source-owned values mean deletion, so an incomplete rendering must not be mistaken for an intentional edit.

The persist command creates **another preview** in its own invocation. It does not consume the dry-run receipt you just reviewed. Keeping all writers stopped and proving the staging files and exclusions unchanged connects the human review to the persist input. Separate receipt directories preserve both reports.

```sh
bb "$RECEIPTS/staging-manifest.bb" "$STAGING" "$RECEIPTS/excluded.txt" \
  > "$RECEIPTS/staging-at-persist.json"
cmp "$RECEIPTS/staging-before.json" "$RECEIPTS/staging-at-persist.json"

bin/sandbar maintenance-import --from "$STAGING" \
  --exclude-file "$RECEIPTS/excluded.txt" --receipts "$RECEIPTS/persist" --persist
```

Within the persist invocation, the importer pins the write to that invocation's preview basis and source digest. A changed basis or changed source set/bytes is refused before importing. The persist invocation may install database functions before taking its preview, so its basis need not equal the earlier dry run's basis. This is an internal guard, not a cross-invocation approval token.

Replacement preserves substrate-owned identity and metadata while replacing the file's declared slots and section structure. Class changes, identity conflicts and removal of an externally referenced section are reported as conflicts. `--mode additive` is a distinct choice that retains omitted values; it cannot represent a deliberate deletion. Optional `--retract-file` effects and identity handoffs are outside this accepted subset, and the command's `complete?` check does not validate those optional effects.

Each source file is a transaction unit. A later file's failure does not roll back earlier files, so an incomplete run needs reconciliation of its full report before retry or recovery.

Firewall refusal receipts identify the source and give available `:reason`, `:slot` and `:target-ref` fields under `:violations`, without target bodies; identity-only forward carriers remain unresolved during author checks, while targets with content and ordinary reads retain fail-closed checks.

### 4. Audit before reopening the service

For a per-tree audit, use the exact configured project root, the global corpus root, or that root's exact `memory/` directory. The audit compares only store entities routed to that tree. A deeper directory or an ad hoc staging directory is a partial filesystem walk compared with the store-wide population: the report labels it `:ad-hoc` and explains the limitation. Its missing-file rows can refer to another tree or an unwalked sibling. Read `:summary`'s `:walk-root`, `:root-mode` and `:root-attribution` before interpreting the counts; a partial diagnostic cannot establish whole-tree cleanliness. Audit each affected physical tree separately.

```sh
bin/sandbar drift-audit --from "$CLIENT" --out "$RECEIPTS/audit-after.json"
bb "$RECEIPTS/staging-manifest.bb" "$STAGING" "$RECEIPTS/excluded.txt" \
  > "$RECEIPTS/staging-after.json"
cmp "$RECEIPTS/staging-before.json" "$RECEIPTS/staging-after.json"
```

Read both preview receipts and `persist/import-persist.edn`. Confirm the expected units persisted, no failed/refused/conflicting units remain, and the report reconciles. Compare relevant identities, body content, non-body attributes and references with the before-images. Explain each remaining audit row, including deliberately retained historical ambiguities. Completion requires no unexplained change; it does not require a numerical zero for every historical discrepancy.

The wrapper refuses import while a server listens on the selected port. The import JVM exits 3 on preflight refusal, 1 on an incomplete report, and 0 when its checked preview/import reports are complete. `drift-audit` exits 0 when the audit ran; its exit status does not mean that its rows are resolved. Read the receipts, not just the shell status.

If the evidence does not reconcile, keep the writers stopped and follow the rehearsed recovery procedure for both store and files. After accepting the audit, restart the same configured application using `lein run` or its supervisor. Verify authenticated exact reads, expected population, one content search and `sandbar_reactive_health` before releasing other writers. Retain the receipts with the reviewed file change.

## Choose the right recovery artifact

| Artifact | What it is for | What it does not establish |
| --- | --- | --- |
| Native Datomic backup | Database recovery, including data outside native document representations | That the backup can be restored with this installation's configuration |
| Markdown projection/export | Human review, versioning and supported document interchange | Complete database, history, credentials or runtime recovery |
| DB-only dump | Inspection/interchange of otherwise unprojected entities under its value contract | An implemented universal restore path |
| Source/configuration record | Reconstructing the application and its environment | The database contents themselves |

Take a native backup before a destructive database operation and verify recovery in a separate target. Record source revision, database identity, backup basis/time, configuration requirements and the validation queries. Compare semantic content and relationships relevant to the proposed operation; directory existence and equal total row counts alone are weak recovery evidence.

A guarded export manifest records its database basis and file hashes; its private ready audit binds those exact manifest bytes to the source database identity. The read-only origin comparison reports the relationship to one current snapshot. It does not bind that tree to a repository revision or provide a git checkpoint layer. Guarded restore that compares an incoming tree with accepted destination state is also unbuilt; document import uses the maintenance procedure above, with its own review. Ordinary client sessions need none of these: a new session reopens the existing store as it is.

The repository provides `backup-db`, `verify-backup`, `list-backups`, `prune-backups`, `restore-db-from`, and `verify-restore` Leiningen aliases. These scripts have deployment-specific assumptions. In particular, the restore wrapper targets a local development transactor, and the verification script contains fixed ports, a database name and installation location. Parameterizing and verifying them for another installation is a prerequisite to adopting them as its runbook. Do not read the alias name as a portable recovery guarantee.

For the underlying command semantics, use [Datomic's backup and restore documentation](https://docs.datomic.com/operation/backup.html). The restore destination must be deliberately isolated; matching a source database name does not require sharing its storage or transactor. Retain the original backup until recovery checks have passed.

## Diagnose failures by boundary

| Observation | Next check |
| --- | --- |
| No HTTP response | Process, configured port, database/transactor connectivity and startup log |
| HTTP 401 | Header format, account activity/expiry, credential and selected database |
| Authentication works but a call is refused | Operation role, namespace policy, project context and actual tool name |
| Search differs from an exact entity read | Search scope, filters, analyzer and derived-cache freshness |
| Database change exists but a file is stale | Projection enrollment, queue ownership, path and sink outcome |
| A persisted Schedule does not run | Execution enrollment and scheduler state, not just its enabled flag |
| Export looks correct but recovery differs | Identity, references, source replacement policy and omitted state |

Inspect only the data needed for a diagnosis. Configuration, authentication entities, logs and backups can contain information that does not belong in a public issue. Keep a useful diagnostic record with the exact revision, operation and observed outcome, using synthetic data where possible.

See [logging](guides/using-logging.md), [performance measurement](BENCH.md), [the release gate](W1J-RELEASE-GATE.md) and [development](development.md) for narrower procedures.
