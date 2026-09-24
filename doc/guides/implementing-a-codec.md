# Implement a codec with an explicit preservation contract

Use a codec when an application needs a distinct external representation. This guide implements a small EDN representation for one synthetic class. It demonstrates protocol and registry behavior; it is not a database backup or a universal serializer.

## Define the supported values

The example accepts an EDN map with `:dt/type :example/Note` and a string `:example.note/text`. Its durable representation excludes `:db/id`, which is local to one database. Other fields are refused so the format's promise stays explicit.

```clojure
(require '[clojure.edn :as edn]
         '[clojure.data :as data]
         '[sandbar.codec :as codec]
         '[sandbar.codec.protocol :as proto])

(defn note-data [entity]
  (let [value (dissoc entity :db/id)]
    (when-not (and (= :example/Note (:dt/type value))
                   (string? (:example.note/text value))
                   (= #{:dt/type :example.note/text} (set (keys value))))
      (throw (ex-info "Expected an example Note with text" {})))
    value))

(defrecord NoteEdnCodec []
  proto/Codec
  (parse [_ input _opts]
    (when-not (string? input)
      (throw (ex-info "Note EDN input must be a string" {})))
    (with-open [reader (java.io.PushbackReader. (java.io.StringReader. input))]
      (let [eof (Object.)
            value (edn/read {:eof eof} reader)
            extra (edn/read {:eof eof} reader)]
        (when (or (identical? value eof) (not (identical? extra eof)))
          (throw (ex-info "Expected exactly one EDN value" {})))
        (note-data value))))
  (emit [_ entity _opts]
    (str (pr-str (note-data entity)) "\n"))
  (mime-types [_] ["application/vnd.example.note+edn"])
  (supports? [_ class-ident] (= :example/Note class-ident))
  (round-trip-test [this entity]
    (let [expected (note-data entity)
          emitted (proto/emit this entity {})
          reparsed (proto/parse this emitted {})]
      {:ok? (= expected reparsed)
       :emitted emitted
       :reparsed reparsed
       :diff (when-not (= expected reparsed)
               (data/diff expected reparsed))})))
```

The parser uses the EDN reader rather than evaluating Clojure forms. It checks for a second value so valid initial content cannot conceal trailing input. Type and shape checks happen inside the codec; the mediator does not call `supports?` automatically.

## Register and use it

```clojure
(codec/register! :example/note-edn (->NoteEdnCodec))

(def note {:dt/type :example/Note
           :example.note/text "Record the reason for a decision."})

(def rendered (codec/emit note {:format :example/note-edn}))
(assert (= note (codec/parse rendered {:format :example/note-edn})))
(assert (:ok? (codec/round-trip-test :example/note-edn note)))

(codec/parse-mime "application/vnd.example.note+edn" rendered)
```

Explicit format selection works without installing the example class. For class-default routing, first declare the class in the metamodel and set its `:dt/native-codec` to the registered format keyword. Registry entries are process-local; loading a schema declaration does not load a Clojure codec implementation.

Choose a unique MIME type and format name. Re-registering a format replaces its implementation. Avoid MIME collisions rather than relying on registration order to choose a winner.

## Test independent observations

Exercise empty text, Unicode, quotes, backslashes, embedded newlines, invalid types, unknown keys, and trailing input. Compare semantic values; EDN map iteration order is not the format's meaning. Confirm that removing `:db/id` is intentional and that no durable identity field is removed by an overbroad normalization helper.

If the codec is used for persisted entities, add a second test across the real accepting boundary: parse, validate, commit in a disposable database, read back, emit, and reparse. Include references and missing targets if they belong to the format. A direct round-trip test cannot establish that the database layer accepts every parsed value or restores relationships correctly.

If the representation spans several entities, define document grouping and per-document failure semantics. The [projection layer](../concepts/projection.md) is the place to discuss collection layout and destinations; do not hide filesystem effects in `parse`.

After experimentation, unregister the example format if the process is shared:

```clojure
(codec/unregister! :example/note-edn)
```

See the [codec reference](../api/codec-protocol.md) for signatures and the [class guide](defining-new-classes.md) for schema installation.
