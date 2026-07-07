(ns sandbar.store
  "Unified :mm/Memory creation entry point — the single path that gives every
   memorial its full ζ three-slot identity at birth.

   BEFORE delegating to `dt/make`, `create-memory!`:
     1. derives a stable, EDN-safe `:db/ident` from `:mm.memory/rel-path`
        (via the codec convention + `codec-md/edn-safe-ident` digit-dodge), and
     2. mints the opaque-stable `:mm/id` (clj-uuid v5, the federation anchor)
        from that ident — using the SAME `sandbar.identifier/ident-uuid` the ζ
        backfill uses, so a create-time mint and a later sweep agree.

   This replaces the divergent per-call identity logic that previously lived in
   two places and disagreed:
     - `sandbar.mcp.tools/entity-create-handler` derived `:db/ident` but NOT
       `:mm/id`; and
     - `sandbar.workflow.orchestrate` (session/log creation) used a raw
       `dt/make` that derived NEITHER — the identless-session bug.

   Routing both through here closes the identless-entity gap corpus-wide and
   makes every future memorial federation-ready at birth.  Per the 2026-05-29
   session-lifecycle-hardening arc + decisions/three_tier_identifier_value_-
   hierarchy + libraries/synthesis/stable_identifier_substrate_clj_uuid_-
   extension_three_slot_model_for_sandbar_zeta_2026_05_25.

   Lives in its own ns (not `sandbar.db.datatype`) because identity derivation
   needs `sandbar.codec.markdown`, and the codec already depends on datatype —
   so putting it in datatype would be a cycle."
  (:require [clj-uuid :as uuid]
            [sandbar.codec.markdown :as codec-md]
            [sandbar.db.datatype :as dt]
            [sandbar.identifier :as ident]))

(defn derive-memory-ident
  "Derive a stable, EDN-safe `:db/ident` for a `:mm/Memory` (or subclass) from
   its corpus `rel-path` (via the codec convention), digit-dodged so it
   round-trips through the EDN reader.  Returns nil when `rel-path` is blank,
   `class` is not a :mm/Memory, or the rel-path is unparseable."
  [class rel-path]
  (when (and rel-path (dt/type-isa? :mm/Memory class))
    (some-> (codec-md/rel-path->memory-ident rel-path)
            codec-md/edn-safe-ident)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; :mm/Schedule create-time stable ident (proliferation fix, W3.B)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; :mm/Schedule is a :mm/Spec, NOT a :mm/Memory, so `create-memory!` used to be a
;; pure pass-through to `dt/make` for it — deriving NO :db/ident.  `dt/make ->
;; make*` then resolves an unnamed `"main"` string tempid to a BRAND-NEW eid on
;; every call, so each `entity.create :class :mm/Schedule` (and each `dt/make
;; :mm/Schedule` in tests / dev-loops) APPENDS a fresh row — the runtime
;; proliferation this arc fixes (target-less :mm/Schedule population; W3.B
;; diagnosis).  The 2 system schedules escaped because seed-system-jobs!
;; hardcodes stable :db/idents (upsert via :db.unique/identity).
;;
;; Fix: derive a deterministic :db/ident from the schedule's STABLE LOGICAL
;; IDENTITY — (target, recurrence, timezone) — so re-creating a logically
;; identical schedule UPSERTS onto the same eid instead of appending.  clj-uuid
;; v5 over the content-key gives a collision-free deterministic name; the same
;; :mm/id-anchoring `ident/+authority+` namespaces it so two deployments with
;; the same authority agree.
;;
;; :mm.schedule/dtstart is DELIBERATELY EXCLUDED from the key: callers re-anchor
;; dtstart at (now) on every create (that is the proliferation mechanism itself),
;; so folding it in would defeat upsert for logically-identical schedules.  Two
;; schedules with the same target + recurrence + timezone ARE the same logical
;; schedule.  Distinct-target schedules stay distinct (target is in the key), so
;; the legitimately-authored 1:1-target population is untouched.
;;
;; Absent-only + non-destructive: an explicit caller-supplied :db/ident always
;; wins, and a schedule with neither a target nor a recurrence (no stable
;; content) is left identless (unchanged pass-through) rather than colliding
;; every content-free schedule onto one eid.
;;
;; Per the W3.B schedule-idempotency arc + the XorConstraint seed-idempotency
;; precedent (commit 1bc426b) + interaction/foundational_substrate_concerns_-
;; are_never_follow_up_sub_arcs_2026_05_21.

(def ^{:doc "clj-uuid v5 namespace anchor for :mm/Schedule content-key idents,
            derived once from the deployment authority-UUID so the derivation is
            deterministic + federation-consistent (same authority → same ident)."}
  +schedule-ns+
  (uuid/v5 ident/+authority+ "mm.schedule/content-key"))

(defn- ref-value->key-token
  "Normalize a :db.type/ref slot VALUE (as it may appear in an entity-spec map
   pre-transact) to a stable string token for content-keying.  Accepts a keyword
   ident, a numeric eid, a `{:db/ident k}` / `{:db/id e}` upsert map, or a
   Datomic EntityMap; nil passes through as \"nil\".  Prefers the :db/ident
   (stable across DBs) over the eid (DB-local) when both are available."
  [v]
  (cond
    (nil? v)     "nil"
    (keyword? v) (str v)
    (map? v)     (str (or (:db/ident v) (:db/id v)))
    :else        (str v)))

(defn derive-schedule-ident
  "Derive a stable, deterministic, EDN-safe `:db/ident` for a `:mm/Schedule`
   from its logical identity — (target, recurrence, timezone) — via clj-uuid v5.
   Returns nil for non-:mm/Schedule classes, or when the schedule carries
   NEITHER a target NOR a recurrence (no stable content to key on — leave it
   identless rather than collapse all content-free schedules onto one eid).

   The returned keyword's name is `s-<uuid>`; the `s-` prefix guarantees it
   never leads with a digit, so it always round-trips through the Clojure/EDN
   reader (the same hazard `codec-md/edn-safe-ident` dodges for memory idents).

   Public for testability + reuse by the prune migration's content-keying."
  [class props]
  (when (dt/type-isa? :mm/Schedule class)
    (let [target     (:mm.schedule/target props)
          recurrence (:mm.schedule/recurrence props)
          timezone   (:mm.schedule/timezone props)]
      (when (or (some? target) (some? recurrence))
        (let [content-key (str (ref-value->key-token target)
                               "|" (some-> recurrence str)
                               "|" (some-> timezone str))]
          (keyword "sandbar.schedule"
                   (str "s-" (uuid/v5 +schedule-ns+ content-key))))))))

(defn create-memory!
  "Create a `class` entity with full ζ identity, then transact via `dt/make`.

   For :mm/Memory classes: when `props` carries `:mm.memory/rel-path` and no
   explicit `:db/ident`, derives an EDN-safe `:db/ident` from it; then mints
   `:mm/id` (clj-uuid v5) from the resulting ident.

   For :mm/Schedule (a :mm/Spec, not a :mm/Memory): when no explicit `:db/ident`
   is supplied, derives a stable content-key `:db/ident` (see
   `derive-schedule-ident`) so re-creating a logically identical schedule
   UPSERTS instead of appending a fresh target-less row — the W3.B proliferation
   fix.  No :mm/id mint (that slot is :mm/Memory-scoped federation identity).

   All derivations are absent-only — an explicitly-supplied `:db/ident` / `:mm/id`
   always wins.  For every OTHER non-:mm/Memory class this is a thin pass-through
   to `dt/make` (no ident/:mm/id logic).

   `opts` is the `dt/make` opts map (e.g. `{:validate? false}` or
   `{:format :markdown :source <md>}`).  Returns the created entity-map.

   This is the canonical create path — prefer it over a bare `dt/make` whenever
   authoring a memorial, so the entity is never identless."
  ([class props] (create-memory! class props {}))
  ([class props opts]
   (let [memory? (dt/type-isa? :mm/Memory class)
         rel-path (or (:mm.memory/rel-path props)
                      (get props "mm.memory/rel-path"))
         derived  (when (and memory? rel-path (not (:db/ident props)))
                    (derive-memory-ident class rel-path))
         ;; :mm/Schedule content-key ident (W3.B proliferation fix) — absent-only,
         ;; and only when the memory-ident derivation did not already supply one.
         sched-id (when-not (or derived (:db/ident props))
                    (derive-schedule-ident class props))
         props    (cond-> props
                    derived  (assoc :db/ident derived)
                    sched-id (assoc :db/ident sched-id))
         the-id   (:db/ident props)
         props    (cond-> props
                    (and memory? the-id (not (:mm/id props)))
                    (assoc :mm/id (ident/ident-uuid the-id)))]
     (dt/make class props opts))))
