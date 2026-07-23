# `memory/tags/` — Sandbar Substrate-Level Tag Vocabulary

Bootstrap pack of substrate-level `:mm/Tag` memorials shipped with
Sandbar.  Each file is a canonical tag entity (parsed as `:mm/Tag`
via the Stage 7.C codec class-routing — `type: tag` in frontmatter
routes to `:mm/Tag`, not `:mm/Memory`).

## Scope

These are the **substrate-level** tags — the vocabulary Sandbar itself
provides for naming what kinds of memorials, classes, properties, and
predicates exist in the metamodel.  Consumer corpora compose with these
when authoring their own project-level / domain-level tags.

Per the C.5 substrate/editorial boundary in
[`decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md`](#)
§2.7 — sandbar ships substrate-vocabulary tags; consumers author
editorial-vocabulary tags on top.

## Inventory (starter pack)

The Stage 7.E starter pack includes 9 substrate-level tags:

| Tag                    | Vocabulary level | Concept                              |
|------------------------|------------------|--------------------------------------|
| `mm-type`              | substrate-level  | The meta-type "type" itself           |
| `mm-class`             | substrate-level  | The meta-type "class"                |
| `mm-property`          | substrate-level  | The meta-type "property"              |
| `predicate`            | substrate-level  | Typed-edge predicates                |
| `decision`             | substrate-level  | ADR-shaped memorial type             |
| `reference`            | substrate-level  | Reference-type memorial              |
| `library`              | substrate-level  | Library-study memorial               |
| `tag-governance`       | substrate-level  | Vocabulary lifecycle discipline       |
| `controlled-vocabulary`| substrate-level  | Canonical-vocabulary concept          |

## Adding a substrate-level tag

1. Author `memory/tags/<canonical-name>.md` with the canonical frontmatter
   shape (see existing files as templates).
2. Set `type: tag` so the codec routes to `:mm/Tag`.
3. Populate at minimum: `name`, `value`, `definition`, `scope-note`.
4. Tag classification: set `vocabulary-level: substrate-level` and
   `lifecycle-status: active` and `canonical?: true`.
5. Run `sandbar.tag.audit` to verify no invariants are violated.

## See also

- [`schema/mm.edn`](../../schema/mm.edn) — `:mm/Tag` class declaration
  (17 slots across 5 tiers)
- [`src/sandbar/audit/tag.clj`](../../src/sandbar/audit/tag.clj) —
  audit invariants
- [`src/sandbar/mcp/tools.clj`](../../src/sandbar/mcp/tools.clj) —
  `sandbar.ground` + `sandbar.tag.*` MCP verbs
