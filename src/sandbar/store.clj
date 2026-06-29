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
  (:require [sandbar.codec.markdown :as codec-md]
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

(defn create-memory!
  "Create a `class` entity with full ζ identity, then transact via `dt/make`.

   For :mm/Memory classes: when `props` carries `:mm.memory/rel-path` and no
   explicit `:db/ident`, derives an EDN-safe `:db/ident` from it; then mints
   `:mm/id` (clj-uuid v5) from the resulting ident.  Both are absent-only —
   an explicitly-supplied `:db/ident` / `:mm/id` always wins.  For non-:mm/Memory
   classes this is a thin pass-through to `dt/make` (no ident/:mm/id logic).

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
         props    (cond-> props derived (assoc :db/ident derived))
         the-id   (:db/ident props)
         props    (cond-> props
                    (and memory? the-id (not (:mm/id props)))
                    (assoc :mm/id (ident/ident-uuid the-id)))]
     (dt/make class props opts))))
