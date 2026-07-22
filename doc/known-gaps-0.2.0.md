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

## 2. Public-bottom-edit firewall bug (RIDES — Dan B2 ruling, condition verified)

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
- **Why it rides.**  Dan ruled **B2: ride documented through 0.2.0, CONDITIONAL** on
  normal public-corpus claude-code operation remaining possible
  (`memory/decisions/dan_rulings_v0_2_0_decision_packet_2026_07_10.md`).  The condition
  was **verified 2026-07-10**: the refusal is confined to the single public-bottom-stamped
  context entity; ordinary corpus operation is unaffected
  (`memory/observations/b2_condition_verified_holds_bug_confined_to_single_public_bottom_stamped_context_2026_07_10.md`).
  The seat that hit it correctly did NOT bypass, retry via another path, or FS-edit.
- **Authorizing record.**  Dan B2 ruling (above) + the verified condition; bug memorial
  `memory/bugs/firewall_refuses_ordinary_edits_to_public_bottom_stamped_entity_flow_forbidden_via_private_edge_targets_2026_07_08.md`
  (status: OPEN);  context in
  `memory/decisions/ceremony_8_COMPLETE_directional_firewall_live_project_schema_minted_public_bottom_stamped_2026_07_08.md`
  (residual R1/R2 + the cosmetic-prose follow-up).
- **Planned close.**  A **design ruling** (not a hotfix) on edits-to-public-entities-with-
  private-targets, belonging with the W1.H composition semantics.  Candidate designs
  recorded in the bug: evaluate flow on the DELTA not the whole entity; grandfather
  pre-existing edges; or confirm that the most-restrictive read of the composition ruling
  in fact intends this.  If the condition is ever observed FAILING, escalate back to Dan
  for the delta-eval vs grandfathering design instead of riding.  Note the frozen
  artifact state this refusal pins — see §12.
- **Side effect while riding.**  The stamped context file
  (`memory/contexts/unsandboxed-home-laptop.md`) is un-editable substrate-mediated, so
  its known blemishes are FROZEN in place: first-section debris, the self-contradicting
  "Firewall-class: none" prose vs the live `:public-bottom` stamp, FS↔DB content
  divergence, and a junk twin (see §12).

---

## 3. The 14 pinned baseline codec test vars (CLOSED)

CLOSED 2026-07-10 with receipts: the pinned 14 decomposed to 5 stale test
expectations (fixed), 8 non-portable fixtures (vendored +
SANDBAR_CORPUS_ROOT-parametrized), 1 corpus-data drift pinned on a live
emit-path leak (production fix @fd134ec + corpus heal); full-suite acceptance
run green (1823 tests, 9975 assertions, 0 failures, 0 errors). Dan's A2
ruling satisfied.

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

## 6. CA-6 — same-batch co-batch write-plane fail-open (deferred to a hard S9-entry gate)

- **What.**  A CONFIRMED EP-1 **fail-open** (an under-refusal, not an over-refusal — the
  original characterization was corrected 2026-07-06 by the S7 codex+opus+fable
  adjudication with a fresh raw-datom probe): a same-batch `make-all*` creating a NEW
  public-bottom project P **plus** its members — public memory A owned-by-P citing
  private memory B owned-by-P — COMMITS and durably stores the forbidden public→private
  `cites` edge.  Mechanism: EP-1's spec-index resolves same-batch TARGETS, but the
  SOURCE label's `owning-project` resolves only against the live DB; with P uncommitted,
  both A and B collapse to the `:project/UNASSIGNED` private sentinel and the subset
  clause permits.  The `{:db/ident kw}` upsert-map ref shape the production codec emits
  IS the fail-open path.  Full-projection of A (which carries no EP-3 guard) can then
  disclose B's ident — the read backstop is S9 physical exclusion ALONE.
- **Why it rides.**  Not reachable via the two production batch callers today
  (project-import and full-corpus-ingest both batch per-source-file, never co-batching a
  project with its members); reachable via a direct `dt/make-all*` co-batch and
  prospectively at S9's owning-project-stamping filtered ingest.  The safe fix (a
  `d/with` proposed-DB EP-1 closure) is an enforcement-**relaxing** change that the
  trust model says deserves its own adversarial pass against sibling-spec spoofing — not
  a rush job.  A live green **characterization test asserts the bypass**
  (`test/sandbar/firewall/ep1_commit_path_test.clj`,
  `ca6-cobatch-KNOWN-FAILOPEN-characterization`) with a loud INVERT-when-fixed marker,
  so the defect cannot silently persist past its fix or silently regress.
- **Authorizing record.**  Fable-max Dan-delegated deferral ruling
  `memory/decisions/s7_two_escalations_ruled_fable_max_delegated_e1_ca6_defer_hard_s9_gate_e2_s9_physical_exclusion_ratified_2026_07_06.md`;
  corrected bug memorial
  `memory/bugs/s7_ca6_source_side_spec_index_gap_same_batch_project_plus_members_over_refuses_deferred_2026_07_06.md`
  (the filename carries the superseded "over-refuses" wording; the body is corrected).
- **Planned close.**  The **hard S9-entry gate**: S9's owning-project-stamping ingest
  must prove this fail-open cannot store an unexcluded edge BEFORE S9's filtered
  multi-corpus ingest is commissioned; the W1.H export-time citation-aware walk is the
  second catch surface.  Two-lock collapse rule: the deferral is sound only while the
  physical-exclusion gate stands — landing that gate in the release branch is a
  precondition of shipping this entry as a gap rather than a bug.

---

## 7. Rel-path traversal sanitizer — VULNERABLE-class TOCTOU (single-writer model)

- **What.**  The G2 rel-path traversal sanitizer (`reactive/sinks.clj`, W1 Phase-0)
  survived a codex bypass corpus except for one VULNERABLE-class finding: a
  time-of-check/time-of-use race between the sanitizer's path validation and the write.
  Exploitation requires a concurrent writer mutating the filesystem between check and
  use.
- **Why it rides.**  Adjudicated **pre-existing** (not introduced by Phase-0) and
  routed to the paused S9 lane.  The deployment model is single-writer: one sandbar JVM
  owns the corpus projection; there is no concurrent-writer surface in normal use.
  Consistent with the A4 threat-model calibration ("enforce separation during normal
  use, not attack-paranoid," Dan 2026-07-20).
- **Authorizing record.**
  `memory/decisions/w1_phase0_LANDED_live_3140634_traversal_sanitizer_mm_id_covenant_fork3_briefs_amended_c1_c3_conditions_2026_07_08.md`
  ("TOCTOU on the sanitizer: pre-existing single-writer-model property → S9 lane").
- **Planned close.**  Re-adjudicate at S9 entry, or immediately if a second writer ever
  appears in the deployment model (that is the close condition, not a date).

---

## 8. Silent-data-loss substrate bugs riding open (with the related contract family)

Two active bugs on exactly the surface a new 0.2.0 project exercises, each still open at
release; both are silent-loss shaped (the write "succeeds" and data is missing):

- **Colon-prefixed slot-key drop** —
  `memory/bugs/mcp_entity_create_update_silently_drop_colon_prefixed_slot_keys_2026_06_29.md`.
  MCP `entity.create`/`entity.update` silently drop attribute keys sent with a leading
  colon (the natural EDN spelling a Clojure-side client reaches for).  The wire-local
  colon-strip normalization is a small fix; until it lands, send bare `"mm.memory/name"`
  style keys.
- **`dt/make` ref-slot eid/map drop** —
  `memory/bugs/dt_make_ref_slots_reject_eids_and_silently_drop_maps_2026_07_02.md`.
  Ref slots reject numeric eids and silently drop entity-map values — concretely,
  `:event/actor` is never persistable through this path and `workflow.orchestrate`'s
  `:actor` is a latent no-op.
- **Related open contract family** (documented so consumers do not re-discover them):
  `entity.update` additive-vs-replace semantics are under-documented at the docstring
  level; no dotted-ident coercion on wire args; and
  `memory/bugs/shape_check_validator_fn_derefs_ident_bearing_fn_ref_to_keyword_latent_collapse_2026_07_06.md`
  (a shape-check deref collapse, latent).
- **Why they ride.**  Each needs a deliberate fix-small / ride-with-ruling /
  defer-with-record disposition rather than a rushed patch inside the release window;
  none corrupts existing data (the loss is on the new write, loudly testable).
- **Planned close.**  The wire-local colon-strip normalization and the ref-slot
  coercion are both small, test-shaped fixes queued for the post-0.2.0 hardening pass;
  the dogfood window is expected to force-rank them.

---

## 9. Confinement caveats — R3 is disciplinary; F7 declassification is reserved

- **What.**  Two deliberate non-mechanisms, stated so the firewall's guarantee is read
  at its true width.  (1) **Content-semantics confinement is DISCIPLINARY**: the
  firewall governs edges and labels deterministically; it does not and cannot detect a
  *paraphrase* of private content written into public prose.  That residual is named,
  disciplinary, and audit-backed — never claimed mechanical.  (2) **F7
  declassification is RESERVED, unimplemented** (`src/sandbar/firewall/core.clj`
  §F7): no clause consults the future `declassified-handle?` carve-out.  Consequence:
  **0.2.0 ships no mechanized promotion path** — moving private material public is a
  manual sanitized-copy act, firewall-constrained like any other write (see
  `doc/firewall-and-projects.md` §Graduation).
- **Why it rides.**  Both are scope decisions, not defects: the trust model
  (deterministic/physical enforcement, LLM untrusted) explicitly excludes model
  judgement from the enforcement path, and the F7 carve-out waits for the graduation
  design it would serve.
- **Authorizing record.**
  `memory/decisions/firewall_trust_model_deterministic_physical_enforcement_llm_untrusted_audit_orthogonal_optin_dan_2026_07_06.md`;
  the graduation-mechanism ruling
  `memory/decisions/dan_rulings_memory_strategy_docket_q1_q8_2026_07_21.md` (Q3:
  sanitized copy, origin record must not leak).
- **Planned close.**  F7 attaches with the graduation build (post-0.2.0); R3 remains
  disciplinary by design — its "close" is the audit surface, not a mechanism.

---

## 10. Setup-path breach (OPS-F-1/F-2) — legacy setup tool quarantined

- **What.**  The legacy new-project setup tool (`etc/setup_project.bb` in the corpus
  repo) is an isolation breach baked into generated artifacts: it symlinks the public
  corpus LIVE into every new project (OPS-F-2), hardcodes `$HOME/claude` hook shims
  (OPS-F-1), installs slash commands retired 2026-05-23 while omitting the sanctioned
  lifecycle commands, and writes no `.mcp.json`/token wiring — a fresh project's first
  session gets zero `mcp__sandbar__*` verbs and a pre-cutover CLAUDE.md template.  It
  predates the W1 world entirely.
- **Why it rides.**  Dan ruled **PROOF BEFORE AUTOMATION** (2026-07-20): the tool is
  QUARANTINED (marked public-corpus-only; retired-command install stripped; the
  public-corpus symlink step gated) rather than rebuilt inside the release window.  The
  supported 0.2.0 new-project path is the MANUAL, memorialized bring-up — the clj-figlet
  dogfood produces the step-by-step operator runbook, and that runbook IS the proof the
  rebuild will be derived from.
- **Authorizing record.**
  `memory/decisions/setup_proof_before_automation_plus_token_registry_stub_overlay_dan_rulings_2026_07_20.md`
  (decision 1); breach confirmation in
  `audit-results/loop-2026-07-10/w5-eval/REPORT.md` F-2 (OPS-F-1/F-2 confirmed at HEAD).
- **Planned close.**  Setup-tool rebuild derived from the dogfood runbook, carrying a
  standing HIGH-PRIORITY flag (promptly-after-proof, not parked).  0.2.1 unless the
  dogfood proves it trivial.

---

## 11. Bare `read-string` interior sites

- **What.**  Three interior deserialization sites use bare `clojure.core/read-string`
  (not `clojure.edn/read-string`): `src/sandbar/util/workflow.clj:463`,
  `src/sandbar/util/job.clj:73`, `src/sandbar/util/edn.clj:57`.  Bare `read-string` is
  eval-capable under `*read-eval*`.
- **Why it rides.**  All three read INTERIOR data (substrate-authored workflow/job
  payloads and EDN files), not caller-supplied wire input; the read-plane query law
  (F5) closed the exposed surfaces — parse-time reader-eval is refused on `:where` and
  the operator vocabulary is allowlist-gated.  The interior sites are
  defense-in-depth debt, not an open injection path.
- **Authorizing record.**  The 0.2.0 plan-of-record §3 acknowledges the residue;
  perimeter closure per the F5 landings
  (`memory/decisions/it6_LANDED_live_3ab31a8_f5_allowlist_single_source_plus_create_path_identless_fix_live_probe_rejects_loudly_2026_07_08.md`).
- **Planned close.**  Mechanical migration to `clojure.edn/read-string` (or
  `sandbar.util.edn` hardened readers) in the post-0.2.0 hardening pass.

---

## 12. Fidelity residuals — orphan-twin tree, DB-only stock, frozen stamped context

- **What.**  Three corpus-state residuals the fidelity instruments surfaced
  (basis: `audit-results/loop-2026-07-10/w5-eval/REPORT.md` F-6/F-7, probes of
  2026-07-10):
  1. **The `memory/memory/` orphan-twin tree** — 55 debris files structurally
     unreachable by DB re-touch: **54 orphan twins** under the doubled prefix (junk
     copies aliasing real entities; the drift oracle was blind to them) plus the one
     B2-frozen stamped context.  Remediation of the orphan tree is Dan-gated (it is
     pipeline step 3 of the ratified fidelity→backfill→routing sequence).
  2. **The DB-only memorial stock** — dozens of real memorials exist in the DB with no
     FS projection (45 `memory.*` idents + 11 null-ident entities at last census),
     including entities whose derived filenames exceed the 255-byte filesystem limit
     and can never be written (sink failure semantics were warn+swallow).  Dan ruled
     (B6, 2026-07-20) the 4 DB-only authorization memorials recovered now; the
     emit-path filename-length guard rides as board-ruled; bulk triage before the first
     canonical rebuild.
  3. **The frozen stamped-context artifact state** — §2's refusal mechanism pins the
     public-bottom context file's current blemishes in place (first-section debris,
     stamp-contradicting prose, FS↔DB divergence, junk twin) until the B2 design ruling
     lands.
- **Why it rides.**  Under R4/Tempo-C (FS = sole durable carrier) the DB-only stock is
  the one class that could be LOST on a canonical rebuild — which is why B6 recovery and
  triage-before-rebuild are ruled rather than deferred silently; the orphan tree and the
  frozen context are cosmetic-to-annoying but stable, and each has a ruled owner.
- **Authorizing record.**  `memory/decisions/dan_rulings_reorientation_docket_2026_07_20.md`
  (B5 proactive-cleanup authorization, B6 recovery); the w5-eval F-6/F-7 findings.
- **Planned close.**  B6 recovery + filename-length guard + drift-oracle twin
  detection (in flight); orphan-tree strip under the Dan-gated remediation sequence;
  the frozen context unfreezes with the §2 design ruling.

---

## 13. Hook-log cross-boundary flow (harness layer, below the DB firewall)

- **What.**  The corpus repo's per-turn audit hook appends EDN records — including
  `:significant-writes` = absolute paths of every file the session wrote — to a
  git-tracked `hook-log/` in the PUBLIC corpus repo, keyed off `$CLAUDE_ROOT|$HOME/claude`.
  A session working a PRIVATE project with hooks installed would therefore write
  client-identifying absolute paths into public-repo history.  This flow sits at the
  harness layer, entirely BELOW the DB firewall — no `mcp__sandbar__*` verb is
  involved.
- **Why it rides.**  It is a corpus-side (claude repo) flow, not a sandbar defect; no
  private engagement runs with installed hooks until the dogfood, and the dogfood
  target (clj-figlet) is deliberately public/low-stakes.  Ledgered here so the flow is
  a named precondition, not a surprise.
- **Authorizing record.**  `audit-results/loop-2026-07-10/w5-eval/REPORT.md` F-10
  (operator-experience:4, 5-0); historical precedent
  `memory/observations/state_change_capture_leaked_proprietary_token.md`.
- **Planned close.**  Per-project hook-log routing (cwd-scope is already computed), or
  gitignore `hook-log/`, or scope-filter `:project-local` writes out of the public log —
  one of the three MUST land before any private engagement runs with installed hooks.
  The 2026-07-09 hook "reroute" was a performance reroute only; it did not change the
  cross-boundary path.

---

## 14. W1 completeness boundary — what the multi-project build deliberately defers (G3)

- **What.**  0.2.0 ships the multi-project centerpiece full-depth on the enforcement
  spine and deliberately thin on breadth (the arc plan's G3 thinning): **two stores**
  (the public bottom + one private project store); **binary labels**
  (`:public`/`:private` only — tiers are additive values later); **one
  hand-provisioned private repo** (no provisioning verb); **config-driven routing**
  (active project binds from env/prop/config; the W1.K standalone list/route MCP verbs
  arrive in 0.2.1); **semantic, not byte-strict, round-trip** for export/restore;
  meta-class emitter coverage deferred; **a cold-start note, not a tested cold-start
  ceremony** (the manual runbook is the supported path, per §10); N-scope orchestration
  deferred.
- **Why it rides.**  G3 is breadth control, explicitly NOT "spine now, projection
  later" — Dan's A1 ruling (2026-07-10) put the projection half (deploy topology, git
  export, guarded restore, dogfood) IN 0.2.0.  The thinning bounds surface area so the
  shipped depth is real.
- **Authorizing record.**
  `memory/decisions/dan_rulings_v0_2_0_decision_packet_2026_07_10.md` (A1);
  `audit-results/loop-2026-07-10/w5-eval/REPORT.md` §2 (G3 boundary) + D-4.
- **Planned close.**  The deferred breadth lands incrementally from 0.2.1 (list/route
  verbs first); each G3 deferral graduates from this ledger to the 0.2.1 plan as it is
  staffed.

---

## 15. Carrier-degradation fidelity floor (narrow refuse-protection by design)

- **What.**  On the DB→FS emit path, exactly **two** frontmatter keys are
  refuse-protected against being stripped by a write — `at-startup` and `one-line`
  (`sandbar.projection/registry-critical-keys`; env-overridable via
  `SANDBAR_REGISTRY_CRITICAL_KEYS`, and setting it EMPTY disables the fatal tier
  entirely).  Every other extras key survives only best-effort: a drop is warn-logged
  (`:REACTIVE/frontmatter-keys-dropped`) and the write proceeds, and an
  unresolvable-carrier fall-through at the codec layer degrades silently.
- **Why it rides.**  The AP-S2-3 arbitration deliberately kept the fatal set narrow
  (substrate must not hardcode consumer-key knowledge beyond the one named catastrophic
  consumer); warn-log frequency is the designated evidence for widening.
- **Planned close.**  Not a defect — a floor to know about.  Projects with
  load-bearing frontmatter keys add them to the critical set or watch the warn-log;
  widening happens on telemetry evidence.  See `doc/firewall-and-projects.md`
  §"The carrier-degradation fidelity floor".

---

## Related fast-follows (tracked, not gaps)

Two mandatory it7 fast-follows from the It-6 landing are engineering follow-ups (already
scoped, not accepted residuals): (1) a per-consumer `safe-path-test-symbol?` projection
gate + negative tests for the path `:TEST` compiler; (2) class-level (not root-ancestry)
create-path gating covering `:mm/Log`/`:mm/Fn`/`:mm/Workflow` + a pre-transact
normalization / containment / collision check at the mutation boundary.  Per
`memory/decisions/it6_LANDED_live_3ab31a8_f5_allowlist_single_source_plus_create_path_identless_fix_live_probe_rejects_loudly_2026_07_08.md`.
