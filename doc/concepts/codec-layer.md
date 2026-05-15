# The Codec Layer

> A theoretical reference for Sandbar's wire-format boundary abstraction.  Explains why codecs live at the boundary (not inside the model, not inside the database, not inside each consumer), how per-class `:dt/native-codec` resolution works, and the round-trip discipline that keeps the abstraction load-bearing.  For the mechanical `Codec` protocol see [`doc/api/codec-protocol.md`](../api/codec-protocol.md); for authoring a new codec see [`doc/guides/implementing-a-codec.md`](../guides/implementing-a-codec.md).

## Thesis

The codec layer **absorbs the parse/emit translation between a consumer's native representation and the metamodel's typed entity shape**.  It is a boundary layer in the Parnas (1972) / Anderson (de.setf.rdf) sense: a stable interface that one side may evolve without disturbing the other.

The motivation is not abstract.  The memory-corpus consumer thinks in markdown with YAML frontmatter.  A future RDF/TTL consumer will think in Turtle.  A JSON-only client thinks in JSON objects.  None of these consumers should — and none of them need to — know about `:dt/Class`, `:dt/slots`, or Datomic's storage idiom.  The codec layer is the place where "the consumer's source-of-truth representation" is converted into "the metamodel's typed entity," and back again, **with round-trip semantic equivalence**.

This mirrors how `dt/*` absorbs Datomic.  Consumers of `dt/*` never write `datomic.api/q` or `d/transact` directly; they call `dt/make` and `dt/instance-of?` and let the layer do the translation.  Codecs apply the same discipline one boundary out.

## Lineage

### Parnas decomposition

Parnas (1972) gave the canonical statement: a module's interface should hide design decisions that are likely to change.  Wire formats are exactly such a decision — markdown today, TTL tomorrow, IPLD-Codec the day after.  The metamodel and the application are unlikely to change in lockstep with the wire format; isolating the wire format behind a codec interface means we can change the wire format without rewriting the model, and we can extend the model without rewriting every codec.

### Anderson's `de.setf.rdf:project-graph`

James Anderson's `de.setf.rdf` (Datagraph/Dydra-era Common Lisp CLOS-metaclass framework) introduced the *boundary-layer primitive* idiom this codec design adopts wholesale.  In Anderson's model, `project-graph` took the raw state of an RDF graph and projected it into a native-representation hierarchy (filesystem; rendered HTML; etc.), and `ingest-graph` did the inverse — accepting a native-representation hierarchy and re-deriving the graph state.  The translation lived **at the boundary**, neither inside the model nor inside the consumer.

Sandbar adopts the same shape one layer up: `project-graph` / `ingest-graph` operate on collections of entities at the filesystem boundary (see [`project-graph.md`](project-graph.md)); the codec layer operates on individual entities at the wire-format boundary.  Both share the property that translation is a boundary concern, not a model concern.

### Postel's robustness principle

RFC 793 (Postel 1981) — "be conservative in what you do, be liberal in what you accept from others" — is the operational discipline for codecs.  A codec's parser must tolerate input variation (whitespace, trailing newlines, ordering of frontmatter keys, optional fields) while its emitter must produce a canonical form (single trailing newline; sorted frontmatter when ordering is semantically irrelevant; stable indentation).  Without this discipline, round-trips drift and the codec stops being a boundary abstraction.

## What a codec is

A codec is a value satisfying the `sandbar.codec.protocol/Codec` protocol with two methods:

```clojure
(parse  codec source)   ; native-representation string → typed entity map
(emit   codec entity)   ; typed entity map → native-representation string
```

The contract is:

1. **Parsing produces a map** suitable for `dt/make` against the codec's bound class.  The map carries `:dt/type` resolved.
2. **Emitting produces a canonical string** — bytewise determinism for the same input is desirable but not required; semantic round-trip is required.
3. **Round-trip is the test of correctness.**  Parse-then-emit on a normalized input should produce the same normalized output; emit-then-parse on a model entity should produce a structurally equivalent entity.

Codecs are values, not singletons.  A codec can be parameterized (e.g., a markdown codec with strict YAML mode versus relaxed YAML mode); a registry of codec values lives in `sandbar.codec/registry`.  Per-class `:dt/native-codec` declares the default codec for a class; the mediator (`sandbar.codec/resolve`) walks the registry and the class's declaration to find the codec to use.

## The mediator

`sandbar.codec/parse` and `sandbar.codec/emit` are mediator functions.  They take an explicit codec name, or fall back to the class's `:dt/native-codec`:

```clojure
;; Explicit codec
(codec/parse :codec/markdown source)

;; Class-default codec — resolves via :dt/native-codec on :mm/Memory
(codec/parse-for-class :mm/Memory source)
```

This is the same architectural shape as `dt/*` absorbing Datomic.  Consumers do not import individual codec implementations; they call the mediator and let class-level declarations route.

## Reference codecs

Sandbar ships two reference codecs.  Both are in `src/sandbar/codec/`.

### markdown

`sandbar.codec.markdown/MarkdownCodec` — Markdown body with YAML frontmatter, used by the memory-corpus consumer and any class whose canonical representation is hand-authored text.

- Frontmatter is parsed via clj-yaml (clj-commons).  YAML keys are converted to namespaced keywords per the class's slot declarations.
- Body is parsed as a section tree: H1/H2/H3 headers become nested `:mm/Section` entities; bodies between headers become section bodies.
- Sibling-chain navigation: each section has `:mm.section/previous-sibling` and `:mm.section/next-sibling` references (RDFS-inspired pairwise links rather than `rdf:List` cons-cells).  See the design discussion in [`mm-section-schema-path-derived-idents-sibling-chain-navigation`](../../memory/decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md) (or equivalent in-tree ADR if migrated).
- Round-trip discipline: bodies are normalized (trimmed; single trailing newline); empty bodies emit as empty (not `"\n"`); derived attributes (`:db/ident`, `:mm.memory/rel-path`, `:mm.memory/first-section`) are stripped during emit.

The markdown codec's complexity is real: it must handle the asymmetry between a freely-authored document and a strictly-typed entity, including ordering of frontmatter (preserved on parse, sorted on emit when no canonical order exists), inline vs block bodies, and section-tree round-trip.  These compromises are what make the codec the *right place* for the complexity — pushing it into the model would couple the model to one wire format; pushing it into consumers would replicate the same logic per consumer.

### JSON

`sandbar.codec.json/JsonCodec` — JSON object with typed slot values, used by MCP clients (JSON-RPC payloads) and any class whose canonical wire form is JSON.

- Cheshire handles the serialization; the codec handles the keyword-namespace preservation (a JSON object's keys are strings; the codec restores namespaced keywords like `:order/total` from `"order/total"` rather than `"total"`).
- Numeric types are routed by the class's slot declarations: `:db.type/long` → JSON number; `:db.type/bigdec` → JSON string (because JSON has no decimal); `:db.type/instant` → ISO 8601 string.
- Round-trip discipline: stable key ordering on emit (alphabetical); numeric types preserved through the round-trip even when JSON's native types would collapse them.

## Round-trip discipline as the load-bearing invariant

A codec without round-trip discipline is a translator, not a boundary abstraction.  If parse(emit(x)) ≠ x for typical x, then consumers downstream of the codec have to know about the asymmetry, and the boundary leaks.

The discipline is enforceable mechanically.  Each codec implementation in Sandbar carries a `codec/<name>-test` namespace with property-style round-trip tests:

```clojure
(deftest markdown-round-trip
  (testing "parse-then-emit is identity on normalized input"
    (let [normalized (markdown/normalize-document source)]
      (is (= normalized
             (codec/emit codec (codec/parse codec normalized)))))))
```

Failures of round-trip discipline have been the source of every codec-layer bug we have caught in development (see the codec sub-arc memorials).  The discipline is not aspirational — it is what makes the abstraction load-bearing.

## How the mediator routes

When an MCP client calls `sandbar.entity.create` with `{:class "mm/Memory", :format "markdown", :source "..."}`:

1. The handler resolves the class — `:mm/Memory`.
2. It looks up the class's `:dt/native-codec` — `:codec/markdown`.
3. If `:format` matches the native codec, it uses that codec directly.  Otherwise it walks the codec registry to find one bound to the requested format.
4. It calls `codec/parse codec source` to obtain the entity map.
5. It calls `dt/make :mm/Memory parsed-map` to transact.

The same path runs in reverse for `resources/read`: the resource handler queries the entity, looks up the codec, calls `codec/emit codec entity`, and returns the native-representation string.

No consumer of `sandbar.entity.create` or `resources/read` knows about the codec implementation.  The codec is a routing decision made at the boundary, hidden from both sides.

## Relationship to other layers

### vs. Datomic serialization

Datomic has its own serialization concerns — fressian for storage, EDN for transactions, projection through `datomic.api/pull`.  These are **in-store** concerns, not wire-format concerns.  The codec layer does not interact with them.  By the time a codec receives an entity from the database, the entity is already a Clojure map; by the time a codec produces an entity for the database, the codec hands the result to `dt/make`, which translates it into a Datomic transaction.

### vs. HTTP content negotiation

HTTP `Content-Type` negotiation selects *which* codec to apply at the protocol boundary.  An HTTP handler accepts `Content-Type: text/markdown` and routes to the markdown codec; `Content-Type: application/json` routes to JSON.  The codec layer does not own the HTTP-level negotiation — that lives in the protocol layer — but it provides the implementations the protocol layer dispatches to.

### vs. MCP `tools/call` arguments

MCP `tools/call` passes `{:arguments {...}}` as JSON-RPC, so the wire format at the protocol boundary is always JSON.  But the *value* inside `:source` may be a markdown string; in that case the codec invoked is the markdown codec, even though the outer envelope was JSON.  The two layers — protocol envelope and codec body — are orthogonal.

### vs. RDF/TTL projection

A future Turtle codec would let a Sandbar instance serve `text/turtle` from `resources/read` for any class.  The shape is already prepared: declare `:dt/native-codec :codec/turtle` on the class, implement the protocol, register, done.  No model change required.

## Comparison with adjacent patterns

### vs. ORMs

ORMs (Hibernate, ActiveRecord, Datalevin's projection mode) sit at the same boundary but on the inside of the database, not the outside.  An ORM hides "which SQL did the model emit?"; the codec layer hides "which wire format is the consumer presenting?"  They solve different problems with the same shape.

### vs. Protocol Buffers / Avro / Thrift

Wire-format schema languages (Protobuf, Avro, Thrift IDL) generate code from a schema definition; the generated code performs parse/emit at the protocol boundary.  This is the same idea Sandbar implements, but Sandbar's *schema is the metamodel itself*, and codec generation is on-demand at runtime via `:dt/range` reflection rather than build-time codegen.  A consumer requesting `tools/list` receives JSON Schema reflected from the live class definitions; there is no compiled schema artifact to keep in sync.

### vs. GraphQL resolvers

GraphQL resolvers sit one level higher: they answer "how do I compute this field on this type?"  Codecs sit one level lower: "how do I marshal this typed entity to/from this wire format?"  A GraphQL projection of Sandbar would use codecs to handle the marshaling; resolvers would be unnecessary because the metamodel is already the type system.

## When to author a new codec

Author a new codec when:

1. A new consumer's source-of-truth representation does not match an existing codec.
2. The wire format has well-defined round-trip semantics (i.e., it is not lossy by design — a codec for HTML pretty-printing would not satisfy round-trip).
3. The metamodel needs no class-shape change to accommodate the new format.  If accommodating the format requires new slot semantics, that is a metamodel change, not a codec change.

Do **not** author a new codec when:

1. The transformation is class-specific (e.g., "render this memory as a tweet").  That is application logic, not boundary translation.
2. The transformation is asymmetric (parse-only or emit-only).  A codec that cannot round-trip is a translator; put it in the application.
3. The transformation is internal to the database (e.g., projection of `:db/id` to ident form).  Those concerns belong to `dt/*`.

## References

**Decomposition and boundary-layer thinking**

- Parnas, D.L. (1972). *On the Criteria To Be Used in Decomposing Systems into Modules.* Communications of the ACM, 15(12), 1053–1058.
- Conway, M.E. (1968). *How Do Committees Invent?* Datamation, 14(4), 28–31.

**Robustness principle**

- Postel, J. (1981). *Transmission Control Protocol.* RFC 793. (The robustness principle: §2.10.)

**Anderson `de.setf.rdf` lineage**

- Anderson, J.M. (2008–). *de.setf.rdf — CLOS-metaclass RDF graph framework for Common Lisp.* (Datagraph / Dydra era.)  Source archive and design discussion in the lib's commit history.

**Wire-format schema languages (for contrast)**

- Google (2008–). *Protocol Buffers Language Guide.* https://protobuf.dev/programming-guides/proto3/
- Apache Avro Project (2010–). *Apache Avro Specification.* https://avro.apache.org/docs/current/spec.html

**Markdown / YAML specifications**

- MacFarlane, J. (2014–). *CommonMark Spec.* https://spec.commonmark.org/
- Ben-Kiki, O., Evans, C. & Net, I. (2009). *YAML Ain't Markup Language (YAML™) Version 1.2.* https://yaml.org/spec/1.2.2/

**Turtle / RDF wire formats (for the planned TTL codec)**

- Beckett, D., Berners-Lee, T., Prud'hommeaux, E. & Carothers, G. (2014). *RDF 1.1 Turtle.* W3C Recommendation.  https://www.w3.org/TR/turtle/

## See also

- [`metamodel.md`](metamodel.md) — the typed-entity shape codecs translate to/from
- [`project-graph.md`](project-graph.md) — the boundary-layer primitive at the filesystem level; same shape, different scale
- [`mcp-protocol.md`](mcp-protocol.md) — how MCP `tools/call` routes through codecs
- [`doc/api/codec-protocol.md`](../api/codec-protocol.md) — mechanical protocol surface
- [`doc/guides/implementing-a-codec.md`](../guides/implementing-a-codec.md) — hands-on how-to
