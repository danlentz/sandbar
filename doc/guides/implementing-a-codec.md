# Implementing a Codec

> How to author a new codec for a wire format Sandbar doesn't yet support — implementing the `Codec` protocol, satisfying round-trip discipline, registering with the mediator, and binding the codec to a class via `:dt/native-codec`.  For the theoretical background see [`doc/concepts/codec-layer.md`](../concepts/codec-layer.md); for the protocol's mechanical surface see [`doc/api/codec-protocol.md`](../api/codec-protocol.md).

## When to author a codec

Author a codec when:

- A consumer's source-of-truth representation does not match an existing codec (markdown, JSON).
- The wire format has well-defined round-trip semantics — bytes can be re-parsed into structurally equivalent entities.
- The format is *neutral and portable* (per [`interaction/export_format_must_be_neutral_and_database_agnostic`](../../memory/interaction/export_format_must_be_neutral_and_database_agnostic_2026_05_12.md)) — codecs operate at the **model** layer (`:dt/Class`, `:dt/slots`), never at the Datomic-schema layer.

Do not author a codec when:

- The transformation is class-specific application logic (e.g., "render an order as a receipt"). That's not a wire-format boundary; it's a domain concern.
- The transformation is asymmetric — parse-only or emit-only. A codec that cannot round-trip is a translator, not a boundary abstraction; put it in your application.
- The transformation involves Datomic-specific identifiers (`:db/id`, `:db.unique/identity`). The wire format must travel between model-equivalent backends.

## The protocol

```clojure
(defprotocol Codec
  (parse  [codec input opts])              ; native string → entity-spec
  (emit   [codec entity opts])             ; entity map → native string
  (mime-types [codec])                     ; vector of MIME strings
  (supports? [codec class-ident])          ; codec/class compatibility
  (round-trip-test [codec entity]))        ; self-diagnostic
```

The contract:

- `parse` returns an entity-spec map suitable for `dt/make`: `{:dt/type :foo/Bar, :slots {...}}`.  For multi-entity inputs, returns a collection of such maps.
- `emit` returns a native-representation string (or a streaming output for large content).  By default, omits `:db/id` (database-local identifier).
- `mime-types` returns the MIME types the codec accepts.  Used by the mediator's content-negotiation routing.
- `supports?` tells the mediator whether this codec can faithfully round-trip the given class.  Generic codecs (JSON) return `true` broadly; specialized codecs (markdown for `:mm/Memory`) return `true` narrowly.
- `round-trip-test` is the codec's *own* round-trip diagnostic — used by golden-fixture tests and by mediator self-checks.

## Walkthrough — implementing an EDN codec

A worked example: an EDN codec that treats Clojure's reader format as the wire form.  This is useful for entity exchange between Clojure systems where keyword + symbol + instant types need to survive without JSON's lowest-common-denominator collapse.

### Step 1 — Scaffold the namespace

Create `src/sandbar/codec/edn.clj`:

```clojure
(ns sandbar.codec.edn
  "EDN codec — Clojure reader format as wire form.

   Useful for Clojure-to-Clojure exchange where keyword + symbol +
   instant types are first-class on both sides.  Less useful for
   cross-language consumers — for those, prefer the JSON codec."
  (:require [clojure.edn :as edn]
            [sandbar.codec.protocol :refer [Codec]]))
```

### Step 2 — Implement the type

```clojure
(deftype EdnCodec []
  Codec

  (parse [_ input opts]
    (let [s (if (string? input) input (slurp input))
          data (edn/read-string {:readers *data-readers*} s)]
      ;; Expect data to be {:dt/type :foo/Bar, :slots {...}}
      ;; or a collection of such maps
      data))

  (emit [_ entity {:keys [pretty? include-id?]}]
    (let [stripped (if include-id?
                     entity
                     (dissoc entity :db/id))
          serialize (if pretty? clojure.pprint/pprint pr-str)]
      (with-out-str (serialize stripped))))

  (mime-types [_]
    ["application/edn"])

  (supports? [_ _]
    ;; EDN is generic — supports any class
    true)

  (round-trip-test [this entity]
    (let [emitted (emit this entity {})
          reparsed (parse this emitted {})
          ok? (= entity reparsed)]
      {:ok? ok?
       :emitted emitted
       :reparsed reparsed
       :diff (when-not ok?
               {:in-original (clojure.data/diff entity reparsed)})})))
```

### Step 3 — Register the codec

In `src/sandbar/codec.clj` (the mediator namespace):

```clojure
(def edn-codec (->EdnCodec))

(swap! registry assoc :codec/edn edn-codec)
```

Or — if you're contributing the codec externally — invoke `sandbar.codec/register-codec!` from your application's init:

```clojure
(sandbar.codec/register-codec! :codec/edn (sandbar.codec.edn/->EdnCodec))
```

### Step 4 — Bind to a class

If `:codec/edn` should be the default wire format for a class:

```clojure
;; In your schema EDN
{:db/ident :event/Booking
 :dt/type :dt/Class
 :dt/native-codec :codec/edn
 ...}
```

After this, `dt/make :event/Booking {:format :edn, :source "..."}` routes through your codec.

### Step 5 — Test

Create `test/sandbar/codec/edn_test.clj`:

```clojure
(ns sandbar.codec.edn-test
  (:require [clojure.test :refer :all]
            [sandbar.codec.edn :as edn-codec]
            [sandbar.codec.protocol :as protocol]))

(def codec (edn-codec/->EdnCodec))

(deftest round-trip-simple-entity
  (let [entity {:dt/type :event/Booking
                :event.booking/title "Weekly Sync"
                :event.booking/starts-at #inst "2026-05-14T15:00:00Z"}
        result (protocol/round-trip-test codec entity)]
    (is (:ok? result)
        (str "round-trip failed: " (:diff result)))))

(deftest emit-omits-db-id-by-default
  (let [entity {:db/id 12345
                :dt/type :event/Booking
                :event.booking/title "Foo"}
        out (protocol/emit codec entity {})]
    (is (not (.contains out ":db/id")))))

(deftest emit-includes-db-id-when-asked
  (let [entity {:db/id 12345 :dt/type :event/Booking}
        out (protocol/emit codec entity {:include-id? true})]
    (is (.contains out ":db/id 12345"))))
```

Run:

```bash
lein test :only sandbar.codec.edn-test
```

## The round-trip discipline

Round-trip is *the* test of correctness for a codec.  Three flavors:

### parse-then-emit on canonical input

```clojure
(= input (emit codec (parse codec input opts) opts))
```

Holds when the input is in canonical form (whatever your codec defines as canonical).  Whitespace normalization, key ordering, trailing newlines — these are all your codec's call, but be *consistent*: emit the canonical form and `(parse (emit x))` is identity *for any canonical x*.

### emit-then-parse on an entity

```clojure
(= entity (parse codec (emit codec entity opts) opts))
```

Holds for any entity whose state can be expressed in the wire format.

### structural equivalence with derived attributes

Some attributes are *derived* (computable from other state) — `:db/ident`, `:mm.memory/rel-path`, `:db/id`.  These should be stripped on emit and reconstructed on parse.  The round-trip test should compare *structurally* — equal up to derived attributes.

Codec implementations should expose a helper like `normalize-entity-for-comparison` if derived attributes are non-trivial.

## Mediator routing

After registration, the mediator (`sandbar.codec`) handles three resolution paths:

1. **Explicit format hint** — `(codec/parse :codec/edn source)` uses the named codec.
2. **MIME-type hint** — `(codec/parse-mime "application/edn" source)` resolves via `mime-types`.
3. **Class-default codec** — `(codec/parse-for-class :event/Booking source)` reads `:dt/native-codec` on the class.

Implement all five protocol methods so all three resolution paths work.

## Implementation gotchas

**Keyword namespacing.**  JSON-, YAML-, and most wire formats collapse keyword namespaces to strings.  On parse, you must re-namespace based on the target class's slots.  Failing to do so means `:order/total` becomes `:total` and validation fails with "unknown slot."

**Numeric coercion.**  Different formats have different numeric capacities.  JSON has only floating-point; EDN has bigint and bigdec.  Be explicit about which Clojure type each input maps to, with reference to the slot's `:dt/range`.

**Trailing newlines.**  A canonical form should have *one* trailing newline (POSIX convention).  Emit no-trailing-newline for empty bodies; emit single-trailing-newline for non-empty.  Inconsistency here breaks parse-then-emit identity.

**Ordering.**  Maps in many languages are unordered.  Emit alphabetized keys (for stable diffs).  On parse, accept any order.

**Lossy fields.**  Comments, presentation hints, layout metadata — these may not survive round-trip.  Document the lossy fields in your namespace docstring.  Test your codec with realistic inputs to discover which fields are actually lossy.

**Derived attributes leak.**  If your codec emits `:db/id` or `:db/ident` in default mode, you risk database-local identifiers escaping into a "neutral" wire format — violating the neutrality directive.  Default to *not* emitting derived attributes; gate them behind `:include-id?` or similar opts.

## Discovery and registration

A consumer's `tools/list` over MCP includes `sandbar.codec.list`, which returns all registered codecs:

```json
[
  {"name": ":codec/markdown", "mime-types": ["text/markdown"], "supports": ["mm/Memory"]},
  {"name": ":codec/json", "mime-types": ["application/json"], "supports": "any"},
  {"name": ":codec/edn", "mime-types": ["application/edn"], "supports": "any"}
]
```

Your codec appears here as soon as it's registered.

## See also

- [`doc/concepts/codec-layer.md`](../concepts/codec-layer.md) — the theoretical foundation
- [`doc/api/codec-protocol.md`](../api/codec-protocol.md) — full protocol surface reference
- [`defining-new-classes.md`](defining-new-classes.md) — adding the class your codec is bound to
- [`doc/concepts/markdown-as-canonical.md`](../concepts/markdown-as-canonical.md) — case study: how the markdown codec handles real-world round-trip discipline
