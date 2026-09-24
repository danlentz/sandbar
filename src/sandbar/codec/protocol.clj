(ns sandbar.codec.protocol
  "Protocol for explicit external representations of entity data.
   The sandbar.codec mediator selects a registered implementation by format,
   MIME type or class default. Implement parse, emit, mime-types, supports?
   and round-trip-test with a declared supported value/identity contract.
   Model metadata supplies class-specific mapping; parsing itself does not
   authorize or persist data. See doc/api/codec-protocol.md."
  )

(defprotocol Codec
  "Wire-format codec — parses native-representation input into Sandbar
   entity-spec maps, emits Sandbar entities as native-representation
   strings/streams."

  (parse [codec input opts]
    "Parse native-representation input into an entity-spec map suitable
     for `dt/make`: `{:dt/type :foo/Bar :slots {...}}` (or a coll of
     such maps for multi-entity inputs).

     Arguments:
       input — string OR java.io.Reader/InputStream
       opts  — map; recognized keys are codec-specific but conventionally:
         :class       — class-ident hint disambiguating the entity's type
         :version     — codec-specific format version
         :strict?     — whether to fail or warn on lossy parse
         :source-uri  — origin URI for error reporting

     Returns: entity-spec map OR coll of entity-spec maps.")

  (emit [codec entity opts]
    "Emit a Sandbar entity (or coll of entities) as a native-representation
     string.

     Arguments:
       entity — entity map (or coll of entity maps) carrying :dt/type +
                slot values
       opts   — map; conventionally:
         :pretty?     — pretty-print output where the format supports it
         :include-id? — include :db/id in the output (default: false)
         :version     — codec-specific output version

     Returns: string (or java.io.OutputStream-shaped value for streaming).")

  (mime-types [codec]
    "Return a vector of MIME type strings this codec accepts/emits.
     The mediator uses this for `parse-mime` dispatch.")

  (supports? [codec class-ident]
    "Predicate: can this codec faithfully round-trip instances of
     `class-ident`?  Used by the mediator to validate that a given
     codec/class pair is sensible (and to surface a clear error if not).

     A codec MAY return true for class-idents it has never seen if it
     handles them generically (e.g., a JSON codec accepts any class);
     a specialized codec (e.g., markdown for `mm/Memory`) returns true
     only for the classes it explicitly supports.")

  (round-trip-test [codec entity]
    "Verify that `(parse (emit entity)) = entity` for the given entity.
     Returns `{:ok? boolean :emitted string :reparsed entity-spec
     :diff diff-or-nil}`.

     Used by the mediator's diagnostic helpers + by golden-fixture tests.
     Codecs SHOULD implement this in terms of their own parse + emit
     methods (not delegated to the mediator) so the test exercises the
     codec in isolation."))
