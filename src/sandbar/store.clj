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
            ;; sandbar.db.datomic is the LOWEST layer (datatype→datomic), so it is
            ;; safe to require here (store→datatype→datomic; no cycle).  We reuse
            ;; its `schedule-content-key-string` as the SINGLE source of the
            ;; content-key string so the create-path key and the prune-path key
            ;; can never drift byte-for-byte (W3.B REVISE must-fix #1).
            [sandbar.db.datomic :as db]
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

(defn- first-class-memorial?
  "True when `class`'s EFFECTIVE `:dt/memorial-policy` is `:first-class` — i.e.
   the reactive fs-projection sink is expected to write an FS file for its
   instances.  This is the SAME predicate the sink itself uses to decide whether
   to project (sandbar.reactive.sinks), so the create-path loud-fail below fires
   for EXACTLY the classes the sink would otherwise skip-for-no-rel-path.

   Conservative + non-throwing: any lookup failure (or a non-:first-class /
   undeclared policy) returns false, so we NEVER reject a create on uncertainty
   — a genuinely-first-class class whose policy lookup fails still falls through
   to (at worst) the pre-fix behavior, where the sink's now-WARN skip is the
   backstop.  Per bugs/entity_create_codec_path_mints_identless_relpathless_-
   entities_fs_projection_silently_skipped_2026_07_08 (it6)."
  [class]
  (boolean
   (try (= :first-class (dt/effective-memorial-policy-of class))
        (catch Throwable _ false))))

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
;; IDENTITY so re-creating a logically identical schedule UPSERTS onto the same
;; eid instead of appending.  clj-uuid v5 over the content-key gives a
;; collision-free deterministic name; the same :mm/id-anchoring
;; `ident/+authority+` namespaces it so two deployments with the same authority
;; agree.
;;
;; W3.B REVISE (DATA-LOSS fix): the original key spanned ONLY (target,
;; recurrence, timezone) — it EXCLUDED six first-class, dispatcher-read slots
;; (:until, :count, :exdates, :rdates, :misfire-policy, :concurrency).  Two
;; schedules differing only on one of those (e.g. :until 2027 vs 2099) then
;; collapsed onto one eid — silently deleting a legitimately-distinct schedule
;; and overwriting terminator/policy slots.  The key now spans the FULL
;; semantically-distinguishing slot set; the canonical content-key STRING is
;; built by the shared `sandbar.db.datomic/schedule-content-key-string` so the
;; create-path key and the prune-path key are byte-identical (they cannot drift
;; because there is one builder).  Keyed slots (enumerated):
;;   target · recurrence (RRULE — the complete FREQ/INTERVAL/BY-* form) ·
;;   timezone · until · count · exdates · rdates · misfire-policy · concurrency.
;; The policy slots (:misfire-policy, :concurrency) are FOLDED IN per the LEAD
;; RULING (safest option): two schedules firing the same recurrence with
;; different concurrency/misfire behavior are OPERATIONALLY distinct rows.
;;
;; :mm.schedule/dtstart is DELIBERATELY EXCLUDED (the ONLY exclusion among the
;; recurrence-defining slots): callers re-anchor dtstart at (now) on every create
;; (that is the proliferation mechanism itself), so folding it in would defeat
;; upsert for logically-identical schedules.  ACCEPTED TRADEOFF: an idempotent
;; re-create upserts onto the survivor and thus OVERWRITES its dtstart — a phase
;; shift of the recurrence anchor — but never changes which distinct schedules
;; exist.  Two schedules identical on every keyed slot ARE the same logical
;; schedule; any differing keyed slot keeps them distinct.
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
  "Normalize a :db.type/ref slot VALUE, as it appears in a PRE-transact entity-
   spec map, to the CANONICAL target token — the SAME value the prune-path
   derives from a post-transact EntityMap (must-fix #2), so an idented target
   passed as its raw eid vs its :db/ident keyword keys identically.  Accepts a
   keyword ident, a numeric eid, a `{:db/ident k}` / `{:db/id e}` upsert map, or
   a Datomic EntityMap; prefers the :db/ident (stable across DBs) over the eid
   (DB-local).  nil → nil (the shared key-builder renders it as the empty token,
   matching the prune-side nil handling).  Returns the RAW keyword/eid — the
   shared `schedule-content-key-string` stringifies it, so both sides feed the
   builder the same primitive."
  [v]
  (cond
    (nil? v)     nil
    (keyword? v) v
    (map? v)     (or (:db/ident v) (:db/id v))
    :else        v))

(defn canonicalize-schedule-target
  "Resolve a :mm/Schedule's `:mm.schedule/target` to its CANONICAL token so the
   create-path key matches the prune-path key when the SAME idented target is
   passed as its raw numeric eid vs its :db/ident keyword (W3.B REVISE must-fix
   #2).  The prune path reads the target back off a post-transact EntityMap,
   where Datomic renders an ident-bearing ref AS its :db/ident keyword — so an
   eid-passed create would otherwise key on the eid string and diverge.

   When the target is a raw eid (or `{:db/id e}`) whose entity HAS a :db/ident,
   returns `props` with :mm.schedule/target rewritten to that keyword; otherwise
   returns `props` unchanged (a keyword target, an identless eid, or a nil target
   all already canonicalize correctly).  DB-aware: uses `db/entity` against the
   live conn — hence applied here at the transact boundary, not inside the pure
   key builder.  Only touches :mm/Schedule props."
  [class props]
  (let [target (:mm.schedule/target props)]
    (if (and (dt/type-isa? :mm/Schedule class)
             (some? target)
             (not (keyword? target)))
      (let [ent (db/entity (if (map? target) (or (:db/ident target) (:db/id target))
                               target))
            id  (:db/ident ent)]
        (if id (assoc props :mm.schedule/target id) props))
      props)))

(defn derive-schedule-ident
  "Derive a stable, deterministic, EDN-safe `:db/ident` for a `:mm/Schedule`
   from its FULL logical identity via clj-uuid v5.  Returns nil for
   non-:mm/Schedule classes, or when the schedule carries NEITHER a target NOR a
   recurrence (no stable content to key on — leave it identless rather than
   collapse all content-free schedules onto one eid).

   Keyed slot set (W3.B REVISE — the FULL semantically-distinguishing identity;
   see sandbar.db.datomic/schedule-content-key-string, THE shared canonical key
   builder both this create-path and the prune-path delegate to so they cannot
   drift byte-for-byte):
     target · recurrence · timezone · until · count · exdates · rdates ·
     misfire-policy · concurrency
   DELIBERATELY EXCLUDED: :mm.schedule/dtstart (the re-anchored recurrence
   anchor — the proliferation driver; see the comment block above).

   The returned keyword's name is `s-<uuid>`; the `s-` prefix guarantees it
   never leads with a digit, so it always round-trips through the Clojure/EDN
   reader (the same hazard `codec-md/edn-safe-ident` dodges for memory idents).

   Public for testability + reuse by the prune migration's content-keying."
  [class props]
  (when (dt/type-isa? :mm/Schedule class)
    (let [target     (:mm.schedule/target props)
          recurrence (:mm.schedule/recurrence props)]
      (when (or (some? target) (some? recurrence))
        (let [content-key
              (db/schedule-content-key-string
               {:target-token   (ref-value->key-token target)
                :recurrence     recurrence
                :timezone       (:mm.schedule/timezone props)
                :until          (:mm.schedule/until props)
                :count          (:mm.schedule/count props)
                :exdates        (db/date-set-token (:mm.schedule/exdates props))
                :rdates         (db/date-set-token (:mm.schedule/rdates props))
                :misfire-policy (:mm.schedule/misfire-policy props)
                :concurrency    (:mm.schedule/concurrency props)})]
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
   (let [memory?        (dt/type-isa? :mm/Memory class)
         explicit-ident (:db/ident props)
         rel-path       (or (:mm.memory/rel-path props)
                            (get props "mm.memory/rel-path"))
         ;; CREATE-PATH FIX (it6, bugs/entity_create_codec_path_mints_identless_-
         ;; relpathless_entities_fs_projection_silently_skipped_2026_07_08):
         ;; when a :mm/Memory is created with an explicit :db/ident but NO
         ;; rel-path, DERIVE the rel-path from the ident via the single shared
         ;; inverse `codec-md/memory-ident->rel-path`, so the reactive fs sink
         ;; can still project a file instead of silently skipping.  Absent-only,
         ;; :mm/Memory-only; nil for non-`memory.*` idents (nothing to derive).
         derived-rel-path (when (and memory? (not rel-path) explicit-ident)
                            (codec-md/memory-ident->rel-path explicit-ident))
         rel-path       (or rel-path derived-rel-path)
         ;; LOUD-FAIL (it6): a first-class :mm/Memory with NEITHER a rel-path NOR
         ;; an ident from which one is derivable cannot be given a corpus path,
         ;; so the sink would skip it and mint a DB-only orphan — the FS↔DB
         ;; bijection break this bug fixes, on the PRIMARY capture path.  Reject
         ;; at the create boundary rather than orphan silently.  Gated on the
         ;; SAME :first-class policy the sink uses (`first-class-memorial?`), so
         ;; :db-only / :inline memory subclasses pass through untouched.
         _ (when (and memory? (not rel-path) (first-class-memorial? class))
             (throw (ex-info
                     (str "Cannot create first-class memorial " class
                          " without :mm.memory/rel-path: no corpus path can be"
                          " composed, so it would be a DB-only orphan (FS↔DB"
                          " bijection break on the capture path). Supply"
                          " :mm.memory/rel-path (e.g. \"decisions/foo.md\") — or"
                          " an explicit memory :db/ident it can be derived from.")
                     {:class class
                      :sandbar/error :create-path-missing-rel-path
                      :supplied-slots (vec (keys props))})))
         derived  (when (and memory? rel-path (not explicit-ident))
                    (derive-memory-ident class rel-path))
         ;; Canonicalize an idented :mm/Schedule target passed as a raw eid to its
         ;; :db/ident keyword BEFORE keying, so the create-key matches the prune-
         ;; key regardless of the target's passed form (W3.B REVISE must-fix #2).
         props    (canonicalize-schedule-target class props)
         ;; :mm/Schedule content-key ident (W3.B proliferation fix) — absent-only,
         ;; and only when the memory-ident derivation did not already supply one.
         sched-id (when-not (or derived explicit-ident)
                    (derive-schedule-ident class props))
         props    (cond-> props
                    ;; Persist the ident-derived rel-path so the entity carries
                    ;; the slot the sink routes on (and re-ingest round-trips).
                    derived-rel-path (assoc :mm.memory/rel-path derived-rel-path)
                    derived          (assoc :db/ident derived)
                    sched-id         (assoc :db/ident sched-id))
         the-id   (:db/ident props)
         props    (cond-> props
                    (and memory? the-id (not (:mm/id props)))
                    (assoc :mm/id (ident/ident-uuid the-id)))]
     (dt/make class props opts))))
