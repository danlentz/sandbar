# Markdown as Canonical

> Why Sandbar uses Markdown + YAML frontmatter as the Layer-1 canonical format for the memory-corpus consumer.  Explains the round-trip discipline that makes markdown a *real* canonical form rather than a serialization, the section-tree shape (H1/H2/H3 → `:mm/Section` entities with path-derived idents), and the pairwise sibling-chain navigation that informed the schema design.  For the mechanical codec see [`doc/api/codec-protocol.md`](../api/codec-protocol.md); for hands-on authoring see [`doc/guides/implementing-a-codec.md`](../guides/implementing-a-codec.md).

## Thesis

The memory-corpus consumer's canonical representation is **Markdown documents with YAML frontmatter**, organized in a filesystem hierarchy.  This is not a serialization of an in-database canonical form — the markdown is the canonical form, and the database is a projected view that consumers query when graph operations are useful.

Three commitments justify the choice:

1. **Human-authored without translation friction.**  A note written in vim, saved to disk, and committed to git is *already* in the canonical form.  No intermediate ceremony.
2. **External tooling works immediately.**  `grep -r` on the corpus produces meaningful results.  `git diff` produces meaningful patches.  Every editor opens the file.  These are not Sandbar features; they are consequences of the canonical form being a flat text file.
3. **Round-trip discipline is enforceable.**  Markdown + YAML have well-specified grammars (CommonMark for the body; YAML 1.2 for the header); the codec layer (see [`codec-layer.md`](codec-layer.md)) maintains semantic equivalence through parse/emit.

The filesystem-canonical commitment (see [`project-graph.md`](project-graph.md)) operates one level up — directory hierarchies are canonical for collections of entities; markdown is canonical for individual entities.

## Lineage

### Markdown (Gruber 2004; CommonMark 2014)

John Gruber's original Markdown (2004) was designed to be readable in source form — a syntax that "feels intuitive to the writer" rather than imposing structural ceremony.  Its central conceit: the document is plain text; markup is minimal; the rendered form is incidental.

CommonMark (MacFarlane et al, 2014) formalized the syntax with a precise grammar, making Markdown a *specification-grade* canonical form.  Before CommonMark, multiple Markdown implementations disagreed on edge cases (Babelmark documented dozens of incompatible renderings of the same input).  CommonMark resolved the ambiguities; today, Markdown is suitable as a *canonical* representation because round-trip semantics are well-defined.

Sandbar's `sandbar.codec.markdown` targets CommonMark for the body grammar.

### YAML 1.2 (Ben-Kiki et al, 2009)

YAML (YAML Ain't Markup Language; Ben-Kiki, Evans & Net 2009) provides the typed-attribute carrier in the frontmatter.  YAML's grammar is more permissive than JSON's — supporting comments, multi-line strings, and bareword keys — but the formal spec admits a canonical subset (effectively the JSON-Schema-compatible subset) that round-trips through parsers without ambiguity.

Sandbar's frontmatter uses the canonical subset.  Type-rich values (datetimes, references) round-trip via canonical string representations (ISO 8601 for instants; `:ns/ident` for keyword references).  The codec normalizes on emit (alphabetical key ordering when ordering is not semantically significant; standardized indentation).

### Frontmatter convention

The combination — YAML between `---` delimiters at the top of the file, followed by Markdown body — is an established convention in the static-site-generator and note-taking ecosystems (Jekyll, Hugo, Eleventy, Obsidian).  Sandbar adopts the same convention because it is recognized by virtually every Markdown editor and tooling chain.

### Org-mode / AsciiDoc lineage (for comparison)

Carsten Dominik's Org-mode (Emacs, 2003–) and the AsciiDoc spec (Stuart Rackham, 2002–) are alternatives — both are richer than Markdown but less broadly tooled.  Org-mode has superior structural editing; AsciiDoc has more precise semantic markup.  Sandbar chose Markdown because the tooling network effect is dominant — every consumer reads it; every editor edits it; every search engine indexes it.

## Round-trip discipline

The discipline that makes Markdown a *canonical* form rather than a *serialization*:

1. **Parse-then-emit on normalized input is identity.**  If a document is in canonical form (single trailing newline; bodies trimmed; frontmatter alphabetized where order is irrelevant), then `(emit (parse doc))` produces the same bytes.
2. **Emit-then-parse round-trips through the database.**  An entity round-tripped through emit → markdown → parse → entity produces an entity structurally equivalent to the original (allowing for derived attributes like `:db/id` that may differ).
3. **Derived attributes are stripped on emit.**  `:db/ident`, `:mm.memory/rel-path`, `:mm.memory/first-section` — anything computable from the document's location or structure — does not appear in the emitted YAML.  They are reconstructed on parse from the file path.

The discipline is enforced by codec tests (`sandbar.codec.markdown-test`) using property-style round-trip checks.

## Section tree shape

A Markdown document's structural shape — H1/H2/H3 headers nesting into sections — is mapped to a tree of `:mm/Section` entities.  Each section is a typed entity with:

| Slot                              | Meaning                                                                          |
|-----------------------------------|----------------------------------------------------------------------------------|
| `:mm.section/title`               | The header's text content                                                        |
| `:mm.section/level`               | The header level (1 / 2 / 3)                                                     |
| `:mm.section/body`                | The Markdown body between this header and the next                              |
| `:mm.section/parent`              | Reference to the enclosing parent section (or `:dt/Resource` for top-level)      |
| `:mm.section/previous-sibling`    | Reference to the previous section at this level under the same parent           |
| `:mm.section/next-sibling`        | Reference to the next section at this level under the same parent               |
| `:mm.section/parent-document`     | Reference to the `:mm/Memory` containing this section                           |

### Path-derived idents

Each section's `:db/ident` is derived from its document's `:db/ident` and the title path of enclosing sections.  For a document `:decisions/foo` with sections `Context` → `Decision` → `Consequences`, the idents are:

```
:decisions/foo                                  ; the document
:decisions/foo__context                         ; section: Context
:decisions/foo__context__decision               ; section: Decision (nested in Context)
:decisions/foo__context__decision__consequences ; section: Consequences (nested in Decision)
```

The double-underscore separator (`__`) is deliberate — single underscores commonly appear in real header text; double-underscores rarely do, making path collisions unlikely without explicit construction.

This preserves correlation with the filesystem hierarchy.  A consumer that knows the file path can construct the document's ident; a consumer that knows the ident can construct the file path.  The two namespaces — files on disk; idents in the database — round-trip through a deterministic function.

See [`decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13`](../../memory/decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md) for the design discussion that landed the path-derived ident form.

### Pairwise sibling chain vs `rdf:List`

A document with multiple top-level sections — say, three H1s — could be modeled two ways:

1. **`rdf:List` of cons-cells.**  The document holds `:mm.memory/first-section`; each section's `:mm.section/rest` points to the next.  This is the RDFS `rdf:List` shape.
2. **Pairwise siblings.**  Each section carries `:mm.section/previous-sibling` and `:mm.section/next-sibling` pointers.  The document holds `:mm.memory/first-section`; the chain walks via `next-sibling`.

Sandbar uses pairwise siblings.  The trade-off:

- `rdf:List` is more elegant theoretically — each cons-cell is its own entity; the list is a chain.  But: inserting in the middle requires rewriting every subsequent cons-cell (the cons-cell *is* the position; changing the position changes the cell).
- Pairwise siblings are slightly less elegant — there is no "the list as a single entity" handle, just "first-section + walk."  But: inserting a section updates exactly two pointers (the new section's neighbors).  Edits are local.

Since the memory-corpus is heavily edited in place (sections added, removed, reordered), pairwise siblings won.  The SIOC `sioc:has_next_sibling` design (Breslin & Decker 2007) informed the choice.

## What survives the round trip

Every YAML frontmatter field maps to a `:dt/Property` on `:mm/Memory`.  Every Markdown section maps to a `:mm/Section` entity.  Every nested section gets a path-derived ident, a parent reference, and sibling pointers reconstructed from position.

What **doesn't** survive round-trip:

- **Comments in the YAML frontmatter.**  YAML comments are not part of the canonical subset and are stripped on parse.
- **Whitespace beyond the canonical normalization.**  Multiple blank lines between sections collapse to one; trailing whitespace is trimmed.
- **HTML embedded in Markdown.**  CommonMark allows raw HTML; Sandbar parses it as opaque body content and re-emits it verbatim — but if an HTML element relies on whitespace-sensitive layout, it may not survive normalization unchanged.
- **Editor-specific metadata.**  Lockfiles, `.directory`, `_drafts/` conventions belong to the filesystem layer, not the markdown content.

These omissions are deliberate — the canonical form is *the semantic content*, not the keystrokes that produced it.

## Addressing scheme

Each section is addressable via two URI schemes:

```
mcp://sandbar/mm/Memory/<rel-path>#<section-path>
file:///<absolute-path>#<section-path>
```

The `mcp://` URI is the database-side address.  The `file://` URI is the filesystem-side address.  Both resolve to the same `:mm/Section` entity through `dt/lookup`.

The fragment (`#<section-path>`) uses the same double-underscore-separated form as the path-derived ident (`context__decision__consequences`).

## Comparison with adjacent canonical-format choices

### vs. JSON

JSON is unambiguous and machine-readable but loses information that markdown carries — header levels, prose flow, inline emphasis.  Storing a `:mm/Memory` as JSON would force a structured-data representation of what is fundamentally narrative content.  Markdown lets the narrative stay narrative.

### vs. RDF / Turtle

RDF/Turtle is theoretically pure — every triple is explicit — but no human writes notes in Turtle.  Adopting Turtle as canonical would force authors to translate their thinking through a graph-shaped intermediate.  Markdown stays in the author's medium; the codec maps to the graph at the boundary.

A future Turtle *codec* (alongside markdown) is straightforward: the metamodel is the same; only the wire-format differs.  But the *canonical* form for human-authored content remains markdown.

### vs. JSON-LD

JSON-LD has the virtue of being JSON-shaped (so all JSON tooling applies) while carrying RDF semantics in `@context`.  For a different consumer — one whose source-of-truth is JSON — JSON-LD would be a natural canonical form.  For the memory-corpus consumer, where the source-of-truth is hand-authored prose, JSON-LD's overhead is not worth it.

### vs. AsciiDoc

AsciiDoc has richer semantic markup than Markdown — better tables, better cross-references, better support for documentation-as-code.  It is the right choice when authoring rich technical documentation.  For note-taking and memory-style corpus, AsciiDoc's complexity is overhead; Markdown's simplicity wins.

### vs. Org-mode

Org-mode has the strongest structural editing of any plain-text format — outlining, agenda views, code blocks with execution, transclusion.  But Org-mode's tooling network is Emacs-centric; consumers outside Emacs see weak support.  Markdown's universal tooling wins for canonical interchange.

## Why this matters for Sandbar's design

The choice of canonical format is a design force that propagates through the system:

1. **The codec layer's existence is implied.**  If canonical form is markdown but the database is graph-shaped, there must be a translator at the boundary.  That is the codec layer.
2. **Project-graph's value proposition is implied.**  If the FS is canonical, there must be a bidirectional projection between FS state and DB state.  That is project-graph.
3. **Section-tree addressability is implied.**  If individual sections are referenced by URI (which they are, for citations, cross-references, and resource subscriptions), they must be first-class entities with stable idents — leading to path-derived idents and pairwise sibling navigation.
4. **The hybrid FS/DB topology becomes a question.**  Once both sides are first-class, the question of "which side authoritatively owns which class" opens — see [`multi-store-architecture.md`](multi-store-architecture.md).

Choosing markdown as canonical is the substrate-level decision that made these other concerns load-bearing.

## References

**Markdown / CommonMark**

- Gruber, J. (2004). *Markdown — A Syntax for Writing Markup.* https://daringfireball.net/projects/markdown/
- MacFarlane, J. (2014–). *CommonMark Spec — A Strongly Defined, Highly Compatible Specification of Markdown.* https://spec.commonmark.org/

**YAML**

- Ben-Kiki, O., Evans, C. & Net, I. (2009). *YAML Ain't Markup Language (YAML™) Version 1.2 Specification.* https://yaml.org/spec/1.2.2/

**Frontmatter convention**

- Hanson, T. & contributors (2011–). *Jekyll — Front Matter convention.*  https://jekyllrb.com/docs/front-matter/

**Adjacent canonical-format choices (for contrast)**

- Sporny, M., Longley, D., Kellogg, G., Lanthaler, M. & Lindström, N. (2020). *JSON-LD 1.1.* W3C Recommendation. https://www.w3.org/TR/json-ld11/
- Rackham, S. (2002–). *AsciiDoc User Guide.* https://docs.asciidoctor.org/asciidoc/latest/
- Dominik, C. (2003–). *Org-mode Manual.* https://orgmode.org/manual/

**SIOC pairwise sibling vocabulary**

- Breslin, J.G. & Decker, S. (2007). *The SIOC Project — Semantically-Interlinked Online Communities.*  http://rdfs.org/sioc/spec/

**Pandoc (the universal document model, for context on round-trip)**

- MacFarlane, J. (2006–). *Pandoc — A Universal Document Converter.*  https://pandoc.org/

## See also

- [`codec-layer.md`](codec-layer.md) — `sandbar.codec.markdown` is the codec implementation
- [`project-graph.md`](project-graph.md) — markdown files in a hierarchy form the canonical projection
- [`metamodel.md`](metamodel.md) — `:mm/Memory` and `:mm/Section` are the metamodel entities markdown round-trips with
- [`doc/api/codec-protocol.md`](../api/codec-protocol.md) — mechanical protocol surface
- [`doc/guides/implementing-a-codec.md`](../guides/implementing-a-codec.md) — authoring a codec for a new wire format
