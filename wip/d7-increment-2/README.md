# D7 increment 2 — replacement semantics on re-import (REP-03), DRAFTED, NOT APPLIED

Drafted 2026-09-20 ~11:40Z by the lead, on Astra's 11:11Z terms (her file
`codex/to-claude/2026-09-20T111137Z_d7-six-choices-and-d6-acceptance.md`,
question 1), just before the session handed off.  Nothing here is on the
classpath; the source tree does not reference this directory.

Files:

- `import.clj` — the new namespace `sandbar.import` (goes to `src/sandbar/import.clj`):
  `plan-unit` (pure over a db value: `:insert` / `:additive` / `:replace` with the
  retraction ops for source-owned slots, the section tree and the carrier, and
  `:conflicts` for a class change, an identity conflict or an externally
  referenced section) and `apply-plan!` (the batch firewall floor over the
  asserted half, retractions in the same transaction).
- `apply-patch.clj` — a babashka script that installs the namespace and patches
  `sandbar.db.datatype` (`make-all-with-retractions*`), `sandbar.db.datomic`
  (`basis-t`), `sandbar.projection` (`:source-sha256` on every unit) and
  `sandbar.mcp.tools` (the import handler plans every unit, refuses conflicts,
  pins `expect-basis`; the dry run reports `:units` with modes and conflicts and
  `:basis`; the card).  Run it from anywhere: `bb wip/d7-increment-2/apply-patch.clj`.
  Its anchors were written against the tree at the D7 increment-1 commit; a
  failed `replace-once` assertion means an anchor moved — nothing is written
  until every replacement in a file resolves.
- `import_replace_test.clj` — Astra's REP-03 acceptance as tests (goes to
  `test/sandbar/project/import_replace_test.clj`): edit-and-remove with the
  shared tag target surviving; reorder and rename; scalar replace, authored
  omission retracted, substrate-owned fact kept; headingless replacement; the
  externally referenced section and the identity conflict refused; idempotent
  repeated imports; additive mode; the dry-run plan and the stale-basis refusal.

Untested as written.  After applying: `lein test :only sandbar.project.import-replace-test
sandbar.project.import-units-test sandbar.project.export-import-roundtrip-test
sandbar.mcp.tools-test`, then the full suite, then `lein mcp-verbs-doc > doc/api/mcp-verbs.md`
and `lein catalog-check`, then backup, restart, wire checks, push, and delete this directory.
