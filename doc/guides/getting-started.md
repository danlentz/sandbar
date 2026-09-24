# Getting started with Sandbar

A useful memory needs more than somewhere to put its text. It needs a type, an identity, ways to find it again, and relationships that explain where it belongs. This guide creates a small observation, reads it back, finds it by content, and follows one of its typed relationships.

## What is Sandbar?

Sandbar stores typed knowledge in Datomic and exposes it through MCP, REST and Clojure. Its metamodel describes classes, properties and their relationships as queryable data. Inference supplies inherited membership and slots, so an observation can use the common memory vocabulary while keeping its more specific meaning.

The built-in memory model includes decisions, observations, plans, tags and other kinds of durable knowledge. Markdown is one representation of that knowledge: the codec turns a document into typed entities and can emit documents from the model. See the [metamodel](../concepts/metamodel.md) and [memory model](../concepts/memory-model.md) for the larger design.

## First connection

Complete the [quickstart](quickstart.md) first. It covers the running service, authentication, MCP initialization, tool discovery and a first class query. Keep its `sandbar_mcp` shell helper available for the requests below. To build the service yourself, start with [development](../development.md) and [operations](../operations.md).

This next step writes an example memory. Use a development collection and a service-account token with permission for creation and the subsequent memory reads. A token that can inspect the schema may still be read-only or lack memory clearance. For this isolated development collection, the operator needs to configure the current full-clearance mechanism explicitly; that is not project-restricted access. See [account setup](../auth.md). You will also need `jq` to build requests and inspect responses.

Tool results have an outer JSON-RPC envelope and an inner MCP result. Define this helper to reject errors before unwrapping a JSON tool payload:

```sh
sandbar_tool_payload() {
  jq -e '
    if .error then error(.error | tojson)
    elif .result.isError == true then error(.result | tojson)
    elif .result.structuredContent != null then .result.structuredContent
    else ([.result.content[]? | select(.type == "text")][0].text | fromjson)
    end'
}
```

Check each command's result before continuing. The [MCP client guide](writing-an-mcp-client.md#error-handling) covers transport errors, response media types and uncertain write outcomes.

## First memorial

A memorial is a durable knowledge record. This fictional observation records what happened during a cache experiment without prematurely turning it into a general rule. Create a temporary input file and give this run its own relative path:

```sh
SANDBAR_EXAMPLE_DIR="$(mktemp -d "${TMPDIR:-/tmp}/sandbar-example.XXXXXX")"
SANDBAR_EXAMPLE_PATH="examples/${SANDBAR_EXAMPLE_DIR##*/}.md"

cat > "$SANDBAR_EXAMPLE_DIR/observation.md" <<'MARKDOWN'
---
name: Cache freshness observation
type: observation
tags:
  - cache-policy
---

# Observation

After changing the source record, a warm cache lookup returned the old value.

# Next check

Compare the source revision with the cached revision before choosing a
refresh policy.
MARKDOWN
```

Create a typed `:mm/Observation` using the Markdown codec. Supply the relative path in `slots`; it lets the creation boundary derive a durable identity and a projection path. The source file's temporary location is only where this client reads the input.

```sh
jq -n \
  --arg path "$SANDBAR_EXAMPLE_PATH" \
  --rawfile source "$SANDBAR_EXAMPLE_DIR/observation.md" \
  '{jsonrpc:"2.0", id:10, method:"tools/call", params:{
    name:"sandbar_entity_create", arguments:{
      class:":mm/Observation",
      slots:{"mm.memory/rel-path":$path},
      format:"markdown", source:$source
    }
  }}' | sandbar_mcp > "$SANDBAR_EXAMPLE_DIR/created.response.json"

sandbar_tool_payload < "$SANDBAR_EXAMPLE_DIR/created.response.json" \
  > "$SANDBAR_EXAMPLE_DIR/created.json"

jq '.entity | {ident: .["db/ident"], name: .["mm.memory/name"], path: .["mm.memory/rel-path"]}' \
  "$SANDBAR_EXAMPLE_DIR/created.json"
```

The successful tool payload contains an `entity` map with namespaced fields. It may also contain a separate `shape-validation` report. Inspect the actual returned identity rather than guessing it from the title. Typed slot validation and declared shape reports answer different questions; [authoring shapes](authoring-shapes.md) explains those contracts.

The Markdown representation supplies the body and its document structure, while the creation boundary supplies memory identity conventions. A database response establishes the creation result; file projection is a separate operation with its own completion state. See [projection](../concepts/projection.md) when you need a file to reflect a change.

## Read back the record

Look up the path you supplied, and explicitly request the full body:

```sh
jq -n --arg path "$SANDBAR_EXAMPLE_PATH" \
  '{jsonrpc:"2.0", id:11, method:"tools/call", params:{
    name:"sandbar_entity_find-by-rel-path",
    arguments:{"rel-path":$path, projection:"full"}
  }}' | sandbar_mcp | sandbar_tool_payload
```

Check the returned name, relative path and `mm.memory/body-raw`, not just the presence of an entity ID. Exact lookup is the right operation when you already know a record's identity or path. Search is useful when you know the subject instead.

## First content query

Search the observation class for the experiment's subject:

```sh
sandbar_mcp <<'JSON' | sandbar_tool_payload
{
  "jsonrpc": "2.0",
  "id": 12,
  "method": "tools/call",
  "params": {
    "name": "sandbar_search_bm25f",
    "arguments": {
      "class": ":mm/Observation",
      "query": "cache freshness old value",
      "limit": 5,
      "projection": "full"
    }
  }
}
JSON
```

BM25F combines evidence from configured fields such as a memory's name, description and body. In a fresh example collection, this record gives the query a relevant candidate. In a larger collection, inspect the returned candidates and read their contents; a high score does not establish that a statement is correct, current or governing. The [search guide](searching-the-corpus.md) explains field weighting, query scope and evaluation.

## First typed-edge walk

The observation's `mm.memory/tags` relation connects it to a Tag entity. Take the identity from the creation result and follow that predicate:

```sh
SANDBAR_EXAMPLE_IDENT="$(jq -er '.entity["db/ident"]' "$SANDBAR_EXAMPLE_DIR/created.json")"

jq -n --arg entity "$SANDBAR_EXAMPLE_IDENT" \
  '{jsonrpc:"2.0", id:13, method:"tools/call", params:{
    name:"sandbar_navigate_outbound-edges", arguments:{
      entity:$entity, predicate:":mm.memory/tags", projection:"full"
    }
  }}' | sandbar_mcp | sandbar_tool_payload
```

The result has an `edges` collection, with the predicate and projected target for each edge. This follows an explicit relationship. Content search found a likely record; navigation now asks what that record is connected to. [Navigating with paths](navigating-with-paths.md) extends this to multi-step questions.

## Working from a project repository

The example above ran against a development collection. Real use starts in a code repository: an AI client is launched from that checkout with the repository's own settings, talks to one shared Sandbar service, and captures knowledge that names its project. A later session, from the same checkout or another clone, reopens the same store and finds the same records. Have the operator [enroll the Context and Project and authorize the destination](../operations.md#serve-a-project-repository) before ordinary capture; installing client settings does not perform those steps.

**Install the binding; keep settings with the repository.** The corpus's `mem onboard-project` verifies an existing Project and prepares a local Codex or Claude Code binding. Follow its [installation guide](https://github.com/danlentz/claude/blob/master/doc/project-onboarding.md): provide the repository root, Project key and document ident, explicit `public` or `private` declaration, MCP URL and credential environment-variable name; preview, inspect, then repeat with `--apply`. Existing instruction text and compatible settings are retained. Conflicting MCP configuration requires manual merge. The command stores a credential reference, never the value, and leaves global settings and client trust decisions to you.

The installed `.sandbar/config.edn` records the verified binding for the client. Sandbar's server configuration loader does not consume the remote checkout or its `.claude/`, `.codex/`, `CLAUDE.md`, `AGENTS.md` or `.mcp.json`. Launch your client from the code checkout with the named credential available, then review its ordinary project/MCP trust prompts. `mem setup-project` remains a quarantined legacy public-corpus tool; use `mem onboard-project` for this journey.

The `bin/sandbar` wrapper's `init` command is a legacy database-import helper with a mismatched path filter at this revision; it does not install project settings. Follow the [explicit startup procedure](../operations.md#start-inspect-and-stop).

**The service is shared; the client names the project.** A `:project` key in the service's own client directory selects that service's logical project (see [operations](../operations.md#make-configuration-explicit)). A remote client's `.sandbar/config.edn` does not travel with its requests: the service cannot see the directory your client was started from, so the client supplies ownership itself. Before the first capture, read the enrolled Project record in full (`sandbar_entity_find` on its document ident), compare its class, stable `mm.project/ident`, `mm/id` and declared privacy with the installed binding, and refuse to continue on a mismatch. Then pass that entity reference as `mm.memory/owning-project` and set the intended `mm.memory/visibility` on each `sandbar_entity_create`. A capture that names the wrong project is a policy error, not a routing accident, so make the check explicit rather than trusting a local label. Private projects may use public memories; public memories must not refer to private ones. The installer verifies the privacy declaration without provisioning a restricted account. [Projects and directional information flow](../firewall-and-projects.md#enroll-a-project-and-declare-its-privacy) describes the enrollment records and the separate read and directional checks.

**Capture, edit, project.** Creation is the `sandbar_entity_create` call shown earlier, with the owning project and explicit visibility added to `slots`. Read the result back in full and record the returned eid, document ident and `mm/id`; those three identify the record across sessions, and none of them is the title. Projection is asynchronous: `sandbar_reactive_health` reports pending work and sink errors, and the file's content proves that a write settled. The service's operator-owned `:project-roots` map selects a project's persistence tree; unmapped projects retain the global root. The [operator procedure](../operations.md#install-or-change-a-project-destination) covers enrollment and manual existing-file migration. Editing `mm.memory/body-raw` replaces the stored body and its derived sections in one transaction on this branch. Verify that the deployed build includes that repair, and read the edited file after projection. Creation stores the body without decomposing it into sections.

**Reopen normally.** A new session reads the existing store. Look the record up by ident or path, or search for its subject, and compare the eid, ident, `mm/id`, owning project and citations with what you recorded. No export, import or database replacement is part of an ordinary reopen; the maintenance import in [operations](../operations.md#maintenance-import-into-the-existing-store) is for edited canonical files with every writer stopped, and nothing in this journey needs it. In the September 2026 installer rehearsal, three fresh Codex sessions used native MCP to capture, edit and reopen a synthetic record from a second checkout. Identity, edited body, owner, citation, BM25F, graph navigation, projected content and retained repository settings passed against one retained in-memory database and its configured project root. This used a fully cleared account without restarting the service. Claude Code binding installation passed checks, but its actual model run stopped at an OAuth refresh failure before native MCP use. Neither result establishes restricted-account privacy or controlled AI-comparison isolation; see [known boundaries](../known-gaps-0.2.0.md#provisioning-and-routing-remain-explicit).

**Publication is a separate act.** Nothing above publishes anything. Export renders into a staging directory for review; a destination-aware filter that checks content, references and provenance before writing into a public destination, a checkpoint/git layer and a guarded restore are unbuilt (the [operations guide](../operations.md#choose-the-right-recovery-artifact) names them). A project label or a fully cleared account proves nothing about whether a rendered file is safe to publish.

## Where to go next

Use [class inspection](quickstart.md#inspect-a-decision) to learn a model before authoring against it. Use [defining new classes](defining-new-classes.md) when your application needs a new kind of knowledge, and [implementing a codec](implementing-a-codec.md) when it needs another representation. The client guides cover [MCP](writing-an-mcp-client.md), [REST](writing-a-rest-client.md) and [embedded Clojure](writing-a-clojure-client.md).

The example remains in the development database. Its temporary input and response files are separate from the server's configured projection destination; deleting those local files does not retract the entity.
