(ns sandbar.codec.protocol
  "Codec protocol — every concrete codec implements this protocol; the
   mediator at `sandbar.codec` routes to the appropriate codec based on
   explicit format hint, MIME type, or per-class default
   (`:dt/native-codec`).

   Per
   decisions/sandbar_codec_layer_owns_wire_format_concerns_consumer_native_representation_2026_05_12.md
   §2.2: Sandbar owns wire-format concerns; consumers pass native
   representation (markdown / TTL / EDN-TTL-hybrid / JSON / etc.);
   codec parses to / emits from entity-spec maps suitable for
   `sandbar.db.datatype/make`.

   The codec layer extends the layer-targeting discipline one level up
   from `dt/* over datomic.api` — consumers target the codec layer,
   never hand-roll markdown / YAML / TTL parsers.

   ## Implementor contract

   Concrete codecs are typically deftypes / defrecords that close over
   any per-instance configuration (e.g., a markdown codec might close
   over its YAML library choice) and implement all five protocol
   methods.  Stateless codecs may use a singleton instance registered
   at namespace load.

   Codecs MUST operate at the MODEL layer (`:dt/Class`, `:dt/Property`,
   `:dt/slots`, class hierarchy) — never at the Datomic-schema layer
   (`:db.unique/identity`, `:db.install/attribute`).  Per
   interaction/export_format_must_be_neutral_and_database_agnostic_2026_05_12.md
   the wire format MUST be neutral and portable across model-equivalent
   backends.

   See `sandbar.codec` for the consumer-facing mediator API."
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
