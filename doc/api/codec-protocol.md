# Codec Protocol Reference

> Layer 4 — mechanical reference for the `sandbar.codec.protocol/Codec` defprotocol and the `sandbar.codec` mediator namespace.  For practical authoring see [`doc/guides/implementing-a-codec.md`](../guides/implementing-a-codec.md); for the design rationale see [`doc/concepts/codec-layer.md`](../concepts/codec-layer.md).

## The protocol

```clojure
(ns sandbar.codec.protocol)

(defprotocol Codec
  (parse           [codec input opts])
  (emit            [codec entity opts])
  (mime-types      [codec])
  (supports?       [codec class-ident])
  (round-trip-test [codec entity]))
```

## Method contracts

### `(parse codec input opts)`

Parse a native-representation input into an entity-spec map suitable for `dt/make`.

**Arguments**

- `codec` — the codec value (a deftype/defrecord instance)
- `input` — `String` or `java.io.Reader` / `java.io.InputStream`
- `opts` — map; recognized keys:
  - `:class` — class-ident hint disambiguating the entity's type
  - `:version` — codec-specific format version
  - `:strict?` — whether to fail (true) or warn (false) on lossy parse
  - `:source-uri` — origin URI for error reporting

**Returns** entity-spec map of the form `{:dt/type :foo/Bar :slots {...}}` — or a collection of such maps for multi-entity inputs.

**Errors** — codec implementations may throw `ex-info` with `:codec/parse-error` on malformed input; `:data` should include enough context to locate the error (line number, byte offset).

### `(emit codec entity opts)`

Emit a Sandbar entity as a native-representation string.

**Arguments**

- `codec` — the codec value
- `entity` — entity map (or collection of entity maps) carrying `:dt/type` and slot values
- `opts` — map; recognized keys:
  - `:pretty?` — pretty-print where the format supports it
  - `:include-id?` — include `:db/id` in the output (default `false`)
  - `:version` — codec-specific output version

**Returns** `String` (or `java.io.OutputStream`-shaped value for streaming codecs).

**Default behavior** — derived attributes (`:db/id`, `:db/ident`) are *not* emitted unless `:include-id?` is `true`.  This is the neutrality discipline: emitted output must travel between model-equivalent backends without database-local identifiers leaking.

### `(mime-types codec)`

Returns a vector of MIME type strings the codec accepts and emits.

```clojure
(proto/mime-types markdown-codec)
;; => ["text/markdown" "text/x-markdown"]
```

Used by `sandbar.codec/parse-mime` and `codec-for-mime` for content-negotiation routing.

### `(supports? codec class-ident)`

Predicate — can this codec faithfully round-trip instances of `class-ident`?

**Returns** `true` / `false`.

Generic codecs (JSON, EDN) typically return `true` for any class.  Specialized codecs (markdown for `:mm/Memory`) return `true` only for the classes they explicitly handle.

Used by the mediator to validate codec/class pairing and surface clear errors on mismatch.

### `(round-trip-test codec entity)`

Self-diagnostic — verify `(parse (emit entity)) ≅ entity` for the given entity.

**Returns**

```clojure
{:ok?      <boolean>
 :emitted  <string>
 :reparsed <entity-spec>
 :diff     <diff-or-nil>}
```

When `:ok?` is `false`, `:diff` carries a structural diff of the original vs. reparsed entity.

Used by golden-fixture tests and mediator self-checks.

## The mediator (`sandbar.codec`)

```clojure
(ns my.app
  (:require [sandbar.codec :as codec]))
```

### Registry management

#### `(codec/register! format codec)`

Registers a codec under a format keyword.  Idempotent — re-registering replaces.

```clojure
(codec/register! :codec/edn (->EdnCodec))
;; => :codec/edn
```

Throws `ex-info` if `format` is not a keyword or `codec` doesn't satisfy the `Codec` protocol.

#### `(codec/unregister! format)`

Removes a codec from the registry.  Idempotent.

#### `(codec/list-codecs)`

Returns a vector of `{:format ... :mime-types [...]}` for every registered codec, sorted by format keyword.

```clojure
(codec/list-codecs)
;; => [{:format :codec/json     :mime-types ["application/json"]}
;;     {:format :codec/markdown :mime-types ["text/markdown"]}]
```

#### `(codec/codec-for format)`

Looks up the codec for the format keyword.  Returns `nil` if absent.

```clojure
(codec/codec-for :codec/markdown)
;; => #<MarkdownCodec ...>
```

#### `(codec/codec-for-mime mime-type)`

Looks up the codec whose `mime-types` includes the given MIME string.  Returns `[format codec]` or `nil`.  When multiple codecs claim the same MIME type, the first registered wins.

### Parse/emit dispatch

#### `(codec/parse input opts)`

Parses `input` using the codec resolved per `opts`.

**Resolution order:**

1. If `opts` has `:format`, look up by format keyword.
2. Else if `opts` has `:mime-type`, look up by MIME.
3. Else if `opts` has `:class`, use the class's `:dt/native-codec`.
4. Else throw `:codec/no-codec-resolvable`.

```clojure
(codec/parse "---\nname: Foo\n---\n# Hello"
             {:format :codec/markdown :class :mm/Memory})
;; => {:dt/type :mm/Memory
;;     :mm.memory/name "Foo"
;;     :mm.memory/first-section ...}
```

#### `(codec/parse-mime mime-type input)`

Convenience shorthand: `(codec/parse input {:mime-type mime-type})`.

Used by REST handlers consuming `Content-Type`.

#### `(codec/parse-for-class class input opts)`

Convenience shorthand: `(codec/parse input (assoc opts :class class))`.

Resolves the codec from the class's `:dt/native-codec` directly.

#### `(codec/emit entity opts)`

Emits `entity` using the codec resolved per `opts`.

**Resolution order** (same as `parse`):

1. `:format` keyword in opts.
2. `:mime-type` in opts.
3. Entity's `:dt/type` → class's `:dt/native-codec`.

```clojure
(codec/emit some-memory-entity {:format :codec/markdown})
;; => "---\nname: Foo\n---\n# Hello"
```

#### `(codec/emit-mime mime-type entity)`

Convenience shorthand: `(codec/emit entity {:mime-type mime-type})`.

### Round-trip helpers

#### `(codec/round-trip-test format-or-codec entity)`

Delegates to the codec's `proto/round-trip-test`.  Returns the same `{:ok? :emitted :reparsed :diff}` shape.

## Layering discipline

Codecs operate at the **model** layer (`:dt/Class`, `:dt/slots`, class hierarchy) — never at the **Datomic-schema** layer (`:db.install/_attribute`, `:db.unique/identity`, `:db/id`).

The mediator targets `sandbar.db.datatype/*` for class-default-codec lookups — never raw `datomic.api`.  This preserves the substrate-layering discipline established for the `dt/*` API.

Per [`interaction/export_format_must_be_neutral_and_database_agnostic`](../../memory/interaction/export_format_must_be_neutral_and_database_agnostic_2026_05_12.md), the wire format **must** be portable across model-equivalent backends.  A future Sandbar instance with a different storage backend (XTDB, raw filesystem) should ingest a Markdown projection from a Datomic-backed instance without loss.

## Built-in codecs

Sandbar ships two reference codecs:

### `sandbar.codec.markdown` — `:codec/markdown`

- **MIME types:** `["text/markdown" "text/x-markdown"]`
- **Supports:** `:mm/Memory` and `:mm/Section` (via section-tree walk)
- **Round-trip discipline:** normalize body whitespace; strip derived `:db/ident`, `:mm.memory/rel-path`, `:mm.memory/first-section`; preserve frontmatter ordering on parse; alphabetize on emit when no order is semantically significant
- **Frontmatter:** YAML 1.2 via clj-yaml; keys normalized to namespaced keywords per class slot declarations
- **Body:** CommonMark grammar; section tree H1/H2/H3 produces `:mm/Section` entities with pairwise sibling navigation

### `sandbar.codec.json` — `:codec/json`

- **MIME types:** `["application/json"]`
- **Supports:** any class (generic codec)
- **Round-trip discipline:** stable key ordering (alphabetical); namespaced-keyword preservation through string round-trip; numeric coercion per slot range
- **Numerics:** `:db.type/long` → JSON number; `:db.type/bigdec` → JSON string (no precision loss); `:db.type/instant` → ISO 8601 string

## Errors

| `ex-info` `:codec/...` key      | Cause                                                |
|---------------------------------|------------------------------------------------------|
| `:codec/parse-error`            | Malformed input                                      |
| `:codec/no-codec-resolvable`    | None of the resolution paths produced a codec        |
| `:codec/unsupported-class`      | `supports?` returned false for the requested class   |
| `:codec/round-trip-failed`      | `round-trip-test` produced `:ok? false`              |

All carry an `:ex-data` map with structured context.

## See also

- [`doc/concepts/codec-layer.md`](../concepts/codec-layer.md) — theoretical foundation
- [`doc/guides/implementing-a-codec.md`](../guides/implementing-a-codec.md) — practical authoring guide
- [`doc/api/dt-star.md`](dt-star.md) — `dt/make :format/:source` and `dt/emit-entity` integration
- [`doc/api/mcp-verbs.md`](mcp-verbs.md) — `sandbar.codec.list` MCP verb
