# Known gaps — Sandbar 0.2.0

This is the riding known-gaps ledger for the 0.2.0 release: items that are
**accepted-and-documented** rather than fixed-before-tag.  Each is either covered
by an explicit Dan ruling or sits inside an already-accepted residual class.  Every
entry states **what** it is, **why it rides** (not a ship-blocker), the **authorizing
record**, and the **planned close**.

Cross-references below are repo-relative paths in the memory corpus
(`memory/...`) or in this repo (`src/...`, `doc/...`).

---

## 1. Read-plane structure-only oracles (RPAF residual class)

Three orthogonal read-plane gaps share one root and one Dan ruling.  The credential
**VALUES** are closed (RPAF v2 output scrub, and it stays closed); what remains are
**structure-only** side-channels — existence / cardinality / membership / metadata —
over the co-resident `:auth/*` records.

### 1a. `resources/list` enumerates `:auth/*` metadata

- **What.**  A read-only MCP client can call `resources/list` and enumerate the auth
  service-account records' existence / names / descriptions (NOT the secret hashes) —
  one MCP endpoint that is not routed through the read-plane firewall scrub.
- **Why it rides.**  Metadata, not values, and within the read-only scope Dan already
  accepted; the ceremony-8 mint does not touch it.  Same residual class as the accepted
  0.2.0 logical firewall.
- **Authorizing record.**  `memory/decisions/dan_rides_orthogonal_readplane_oracles_as_documented_known_gaps_ceremony8_proceeds_2026_07_07.md`
  (Dan, AskUserQuestion, in-loop: RIDE as a documented known-gap like the CA-6 window
  discipline); bug memorial
  `memory/bugs/mcp_resources_list_enumerates_auth_instances_no_firewall_scrub_existence_ident_metadata_leak_orthogonal_to_rpaf_2026_07_07.md`.
- **Planned close.**  Route `resources/list` through the central read-plane class guard
  (the "RPAF must be central, not per-verb whack-a-mole" principle), **or** physical
  auth-store separation.  Both post-0.2.0; both already accepted in direction.

### 1b. `entity.update` RPAF dispatch gap

- **What.**  `sandbar.entity.update` reaches the write primitive without an RPAF
  dispatch refusal on the target entity: its `entity` arg is not a class-arg in
  `read-plane-class-arg-keys`, and there is no per-handler `assert-entity-allowed!`.
  Pre-existing at `639705e` (not merge-introduced).  The return is value-scrubbed by the
  central projection firewall (`api/projection.clj` `apply-projection` →
  `read-plane-scrub-projection`), so NO credential VALUE leaks — the residual is an
  existence oracle + write-reachability of an auth entity, and it is principal-gated (S5).
- **Why it rides.**  Explicitly the SAME residual class Dan accepted (value-scrubbed, no
  value leak); the ceremony packet gave it an `entity.update` composition probe so Dan
  saw it at execution.
- **Authorizing record.**  Same ruling as 1a
  (`memory/decisions/dan_rides_orthogonal_readplane_oracles_as_documented_known_gaps_ceremony8_proceeds_2026_07_07.md`,
  operator-extension paragraph);
  `memory/bugs/entity_update_handler_entity_arg_not_rpaf_class_arg_no_assert_entity_allowed_write_reachability_existence_oracle_2026_07_07.md`.
- **Planned close.**  Central read-plane class guard over the stray write surface, or
  physical auth-store separation.  Post-0.2.0.

### 1c. Aggregate / registry / path structure oracles

- **What.**  Documented residual structure-only oracles that survive the logical
  firewall.  (For the record: the aggregate credential-VALUE exfil bug —
  `memory/bugs/read_plane_aggregate_verbs_no_attribute_authz_credential_hash_exfil_orthogonal_to_f5_2026_07_07.md`
  — is **CLOSED**; every aggregate verb now runs the deny-by-default namespace
  firewall on its `:class`/`:group-by`/`:where` args per
  `memory/decisions/read_plane_namespace_firewall_deny_by_default_closes_auth_exfil_dan_delegated_direction_2026_07_07.md`.)
  What still rides is the structure-not-value residue: aggregate count/membership
  structure oracles over the allow-listed read surface;
  the count-of-redaction-markers weak oracle (kept-markers ruling); registry-shape
  class/property DEFINITION exposure (registry-accept ruling); and the
  `navigate.path-via` `:RESTRICT`/`:TEST`/`:FILTER` value positions (fleet #4,
  UNCONFIRMED — returned empty).
- **Why it rides.**  Four adversarial fleets established the logical-firewall floor
  empirically; the credential values are closed; continuing to patch structure-only
  oracles logically is an arms race the judge itself said only physical exclusion ends.
- **Authorizing record.**  `memory/decisions/dan_accepts_0_2_0_logical_readplane_firewall_physical_separation_post_0_2_0_proceed_ceremony_8_2026_07_07.md`
  (Dan accepts the 0.2.0 logical firewall as the posture);
  `memory/decisions/rpaf_must_be_central_class_arg_entity_return_guard_not_per_verb_whackamole_2026_07_07.md`.
- **Planned close.**  **Physical auth-store separation** — a separate transactor/DB so
  `:auth/*` is not co-resident in the read-plane corpus DB.  Closes the
  count/membership/existence oracles structurally.  Slated post-0.2.0.

---

## 2. Public-bottom-edit firewall bug (OPEN — design ruling pending)

- **What.**  After the ceremony-8 `:public-bottom` stamp, a routine `entity.update` to
  the stamped global corpus context (`memory.contexts/unsandboxed-home-laptop`) — even
  one that changes ONLY description prose — is atomically refused by the live directional
  firewall: `flow-forbidden` on its EXISTING `:mm.memory/related` edges
  (`memory.types/human-actor`, `memory.types/context`) and `:mm.memory/cites` edge
  (`actor_as_first_class_type` decision), because those targets still resolve `:private`
  via `:project/UNASSIGNED`.  The public root is effectively **read-only** for ordinary
  edits as long as anything it references remains unassigned/private.  It also blocks the
  cosmetic "Firewall-class: none" → "public-bottom" prose fix flagged in the ceremony-8
  completion record.
- **Why it rides.**  Not an F6/Wave-3 landing blocker.  It is the write-plane twin of the
  ceremony-8 stamp-ordering bug (which was resolved for the STAMP by re-ordering; this
  instance is post-stamp steady-state, so no re-order fixes it).  The seat correctly did
  NOT bypass, retry via another path, or FS-edit.
- **Authorizing record.**  `memory/bugs/firewall_refuses_ordinary_edits_to_public_bottom_stamped_entity_flow_forbidden_via_private_edge_targets_2026_07_08.md`
  (status: OPEN);  context in
  `memory/decisions/ceremony_8_COMPLETE_directional_firewall_live_project_schema_minted_public_bottom_stamped_2026_07_08.md`
  (residual R1/R2 + the cosmetic-prose follow-up).
- **Planned close.**  A **design ruling** (not a hotfix) on edits-to-public-entities-with-
  private-targets.  Candidate designs recorded in the bug: evaluate flow on the DELTA not
  the whole entity; grandfather pre-existing edges; or confirm that the most-restrictive
  read of the composition ruling in fact intends this.  Belongs with the W1.H composition
  semantics; goes to Dan in the next briefing.

---

## 3. The 14 pinned baseline codec test vars (posture pending Dan)

- **What.**  `lein test` carries **14 pre-existing failing vars**, all in the
  codec / round-trip / projection family — no auth / wire / dispatch var among them.  The
  full set (re-recorded 2026-07-05):
  `ingest-graph-applies-tree-filter`, `ingest-graph-reads-nested-directories`,
  `r1-semantic-fidelity`, `r2-extras-byte-identity`, `r3-idempotency`,
  `r4-full-byte-identity-target`, `r5-db-round-trip`, `r6-drift-sweep`,
  `round-trip-memory-with-sections`, `round-trip-multiple-memories`,
  `round-trip-simple-memory`, `t1-reimport-upserts-carrier-in-place`,
  `t2-carrier-ident-is-derived-from-host`, `t3-identless-host-falls-back-to-anonymous-carrier`.
- **Why it rides.**  Pinned as a baseline: every Wave-3 landing gated on "zero NEW
  failures beyond these 14" (`comm -13` against the pinned baseline file), and the
  authoritative gate is the **live-store re-gate**, which is deterministic and green.  The
  14 have not regressed any consumer surface; they are round-trip-fidelity edge cases.
- **Authorizing record.**  `memory/observations/s5_baseline_rerecorded_14_vars_wire_test_passes_build_workflow_launched_2026_07_05.md`
  (the exact 14);  `memory/observations/w1_phase0_approved_baseline_pinning_lesson_and_no_stash_fleet_rule_2026_07_07.md`
  (the baseline-pinning discipline);  §1 of
  `memory/plans/sandbar_0_2_0_co_release_plan_of_record_2026_07_08.md` (14 pinned, zero new
  through ceremony-8).
- **Planned close.**  **Posture pending Dan** — either fix the round-trip-fidelity family
  before tag, or explicitly accept the 14 as documented baseline for 0.2.0 and schedule
  the codec-fidelity repair for 0.2.1.  Dan's ruling is one of the enumerated pre-tag
  tasks.

---

## 4. Dotted-alias retirement prerequisites

- **What.**  The MCP tool-name rename (dots → underscores) ships the underscore wire
  form plus a **one-release** dotted alias (never advertised, deprecation-WARN,
  single-dispatch).  Retiring the alias in the release AFTER 0.2.0 has hard prerequisites
  that are NOT yet done.
- **Why it rides.**  Removing the alias now would break in-flight dotted callers and leave
  the 621 in-card composition references pointing at removed names.  The one-release alias
  is the deliberate migration window.
- **Authorizing record.**  `memory/decisions/mcp_tool_name_rename_LANDED_live_7a47122_underscore_wire_dotted_alias_one_release_authz_flip_green_2026_07_08.md`
  (binding follow-up #1);  Dan-ruling
  `memory/decisions/sandbar_mcp_tool_names_underscore_not_dot_durable_fix_not_papering_over_dan_directive_2026_07_04.md`.
- **Planned close (all prerequisites, next release after v0.2.0).**
  1. Project the **621 dotted composition refs inside tool-card descriptions** to the
     underscore form.
  2. Reconcile the dotted **instructions / prompt spellings** to underscore.
  3. `tools.describe` underscore resolution (the describe surface must resolve the new
     wire names).
  4. A live-log deprecation-WARN sweep showing **no remaining dotted callers** before the
     alias is dropped.

---

## 5. Physical auth-store separation (post-0.2.0 durable close)

- **What.**  `:auth/*` service-account records are co-resident in the read-plane corpus
  DB.  The 0.2.0 posture is the **logical** firewall (RPAF v1–v3.1 + the directional
  firewall); physical separation is deferred.
- **Why it rides.**  Larger scope than a 0.2.0 point of hardening; the logical firewall
  closes credential VALUES and the residuals in §1 are the accepted structure-only class.
  Pulling physical separation forward was explicitly weighed and **deferred**.
- **Authorizing record.**  `memory/decisions/dan_accepts_0_2_0_logical_readplane_firewall_physical_separation_post_0_2_0_proceed_ceremony_8_2026_07_07.md`
  (alternatives-weighed: pull-physical-separation-forward → DEFERRED, post-0.2.0).
- **Planned close.**  A separate transactor / DB so `:auth/*` is not co-resident in the
  read-plane corpus DB — the durable structural close for the entire §1 residual class.
  Post-0.2.0.

---

## Related fast-follows (tracked, not gaps)

Two mandatory it7 fast-follows from the It-6 landing are engineering follow-ups (already
scoped, not accepted residuals): (1) a per-consumer `safe-path-test-symbol?` projection
gate + negative tests for the path `:TEST` compiler; (2) class-level (not root-ancestry)
create-path gating covering `:mm/Log`/`:mm/Fn`/`:mm/Workflow` + a pre-transact
normalization / containment / collision check at the mutation boundary.  Per
`memory/decisions/it6_LANDED_live_3ab31a8_f5_allowlist_single_source_plus_create_path_identless_fix_live_probe_rejects_loudly_2026_07_08.md`.
