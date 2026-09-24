# Codec protocol and mediator reference

The protocol is defined in [`sandbar.codec.protocol`](../../src/sandbar/codec/protocol.clj). The registry and caller API are in [`sandbar.codec`](../../src/sandbar/codec.clj). For a working implementation, use [Implementing a codec](../guides/implementing-a-codec.md).

## Protocol

```clojure
(defprotocol Codec
  (parse [codec input opts])
  (emit [codec entity opts])
  (mime-types [codec])
  (supports? [codec class-ident])
  (round-trip-test [codec entity]))
```

| Method | Result and responsibility |
| --- | --- |
| `parse` | Entity specification map or collection; input kinds and options are codec-specific |
| `emit` | The codec's output representation, normally a string |
| `mime-types` | Vector of exact media-type strings |
| `supports?` | Boolean declaration of supported classes; not automatically enforced by the mediator |
| `round-trip-test` | Diagnostic map with `:ok?`, `:emitted`, `:reparsed`, and `:diff` |

Specifications use flat namespaced slots, for example `{:dt/type :example/Note :example.note/text "Hello"}`. They do not universally wrap fields in `:slots`. Parsing does not transact or establish validation success.

The protocol describes conventional options such as `:class`, `:version`, `:strict?`, `:source-uri`, `:pretty?`, and `:include-id?`. A conventional name does not mean every codec implements it. Check the concrete codec, particularly input streams, strictness, and which identity fields it emits.

## Registry

| Function | Behavior |
| --- | --- |
| `(register! format codec)` | Validate the keyword/protocol pair, replace the registration, return the format |
| `(unregister! format)` | Remove it; return nil |
| `(list-codecs)` | Sorted vector of format and MIME-type maps |
| `(codec-for format)` | Registered codec or nil |
| `(codec-for-mime mime-type)` | Matching `[format codec]` or nil |
| `(native-codec-for-class class-ident)` | Read the class default, returning nil if unavailable |

Registrations are process-local. Avoid duplicate MIME claims; iteration order is not a documented priority guarantee. `clear-all!` is test-only.

## Caller API

| Function and arities | Selection |
| --- | --- |
| `(parse input)` / `(parse input opts)` | Explicit format, MIME, or class default; no entity to infer from |
| `(emit entity)` / `(emit entity opts)` | Explicit format, MIME, explicit class default, or entity class default |
| `(parse-mime mime input)` / `(parse-mime mime input opts)` | Exact registered MIME match |
| `(emit-mime mime entity)` / `(emit-mime mime entity opts)` | Exact registered MIME match |
| `(parse-for-class class input)` / `(parse-for-class class input opts)` | Add the class hint, then use normal parse selection |
| `(round-trip-test format entity)` | Dispatch directly to the selected codec diagnostic |

No implicit format is selected when all choices fail. Missing formats and MIME registrations throw informative exceptions. Remaining options pass through to the codec. A MIME string with parameters is not automatically normalized by the registry.

## Built-in representations

Markdown and JSON register through their concrete namespaces. Markdown document helpers additionally support a host/section collection, relative path context, and extras carriers. The JSON entity codec uses `_class` and model-aware short field names. Neither is synonymous with the generic JSON serialization of an HTTP or MCP envelope.

Round-trip diagnostics have codec-specific normalization. A passing direct diagnostic does not establish database persistence, reference reconstruction, complete directory import, or conflict-safe restore. Those are separate accepting and projection journeys described in [the codec concept](../concepts/codec-layer.md).
