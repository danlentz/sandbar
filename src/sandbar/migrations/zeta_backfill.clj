(ns sandbar.migrations.zeta-backfill
  "ζ Scope B per-entity backfill — compute tx-data for `:mm/id` v5 UUID +
  `:mm/pref-label` (default-source `:mm.memory/name`) population on
  existing :mm/Memory entities.

  Build-prove-promote sub-stage 0 (per Dan-directive 2026-05-26
  `interaction/build_protocol_extensions_in_sandbar_first_prove_consume_locally_then_promote_to_public_library_release_dan_directive_2026_05_26.md`):
  this namespace BUILDS the per-entity backfill computation as pure
  helper fns (no Datomic side-effects); PROVES via unit-tests +
  later β.2.3 driver consumption; the live-corpus migration run +
  Gate-2 backup-cycle is a SEPARATE β.2.3 commit under
  Dan-supervision (per the `db_preservation_during_cutover`
  authorization discipline).

  Composes with:
  - sandbar.identifier (the v5 derivation primitives)
  - ζ Scope B ADR §7 (migration strategy)
  - β.2.2 ADR §1 (Phase B per-family migration template; this ns
    handles the ζ PIGGYBACK portion; created-by ref-rewrite portion
    handled in a sibling ns per-family)
  - β.2.3 per-family migration tx-fns (consume these helpers
    + add their per-family ref-slot rewrite logic)

  Scope (pragmatic per build-prove-promote §3.3):
  - BUILT: per-entity tx-data computation (pure fn; no DB side-effects)
  - DEFERRED: defdbfn wrapping; live-corpus driver; Gate-2 backup-cycle

  Per ζ Scope B ADR §7.2 default-source for :mm/pref-label:
    1. :mm.memory/name if populated
    2. :mm.memory/description (truncated to ~80 chars) if name empty
    3. The slug from :db/ident (last-resort fallback)"
  (:require [clojure.string :as str]
            [sandbar.identifier :as id]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pref-label default-source resolution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; Per ζ Scope B ADR §7.2: when backfilling :mm/pref-label, prefer existing
;; :mm.memory/name; fall back to :mm.memory/description (truncated); last
;; resort = derive from slug.

(def ^{:doc "Max characters for :mm/pref-label fallback derived from
            :mm.memory/description.  Per ζ Scope B ADR §7.2 ('truncated to
            ~80 chars if longer')."}
  +pref-label-fallback-max-chars+
  80)


(defn slug->title
  "Convert a substrate-ident slug (e.g. \"three_tier_identifier_value_hierarchy\")
   to a human-readable title (e.g. \"Three Tier Identifier Value Hierarchy\").

   Last-resort fallback for :mm/pref-label when :mm.memory/name is absent
   AND :mm.memory/description is absent. Per ζ Scope B ADR §7.2 step 3.

   Behavior:
   - Splits on underscores + hyphens
   - Title-cases each word (first character upper; rest lower)
   - Rejoins with single space"
  [slug]
  {:pre [(string? slug)]}
  (->> (str/split slug #"[_-]+")
       (filter seq)
       (map (fn [word]
              (if (seq word)
                (str (str/upper-case (subs word 0 1))
                     (str/lower-case (subs word 1)))
                word)))
       (str/join " ")))


(defn truncate-fallback
  "Truncate `s` to at most `max-chars` (no ellipsis suffix to keep
   fallback predictable).  Per ζ Scope B ADR §7.2 description-fallback."
  [s max-chars]
  {:pre [(string? s) (pos? max-chars)]}
  (if (<= (count s) max-chars)
    s
    (subs s 0 max-chars)))


(defn resolve-pref-label
  "Resolve the :mm/pref-label value for an entity given its current slot map.

   Per ζ Scope B ADR §7.2 default-source cascade:
   1. :mm.memory/name (preferred)
   2. :mm.memory/description (truncated to +pref-label-fallback-max-chars+)
   3. Derived from slug via slug->title

   `entity-map` is a map containing at least :db/ident.  Returns a non-blank
   string (always; the slug fallback guarantees a result).

   Example:
     (resolve-pref-label {:db/ident :memory.decisions/foo
                          :mm.memory/name \"Foo Decision\"})
     ;; → \"Foo Decision\"

     (resolve-pref-label {:db/ident :memory.decisions/three_tier_identifier_value_hierarchy})
     ;; → \"Three Tier Identifier Value Hierarchy\""
  [entity-map]
  {:pre [(map? entity-map)]}
  (let [name-value (:mm.memory/name entity-map)
        desc-value (:mm.memory/description entity-map)
        ident      (:db/ident entity-map)]
    (cond
      (and (string? name-value) (not (str/blank? name-value)))
      name-value

      (and (string? desc-value) (not (str/blank? desc-value)))
      (truncate-fallback desc-value +pref-label-fallback-max-chars+)

      ident
      (slug->title (name ident))

      :else
      (throw (ex-info "Cannot resolve :mm/pref-label: no :mm.memory/name, :mm.memory/description, or :db/ident"
                      {:entity-map entity-map})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-entity tx-data computation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;
;; The build-prove-promote pure-fn shape: take an entity-map (read from
;; substrate via d/entity or similar) + return a tx-data vector for the
;; ζ backfill.  No DB side-effects in this fn; the caller transacts.

(defn entity-backfill-tx
  "Compute tx-data for ζ Scope B per-entity backfill: :mm/id v5 UUID +
   :mm/pref-label resolved per `resolve-pref-label`.

   Inputs:
   - `entity-map`: a map containing at least :db/id + :db/ident; may also
                   contain :mm.memory/name + :mm.memory/description +
                   already-populated :mm/id (in which case backfill skips).

   Returns a tx-data vector ready for `(d/transact conn tx-data)`:
   - One add-assertion for :mm/id (if not already set)
   - One add-assertion for :mm/pref-label (if not already set)

   Skip-conditions (returns empty tx-data vec):
   - Entity already has both :mm/id and :mm/pref-label populated
   - Entity has no :db/ident (cannot derive UUID without ident)

   Per ζ Scope B ADR §7.1 piggyback strategy + §7.2 default-source cascade."
  [entity-map]
  {:pre [(map? entity-map) (some? (:db/id entity-map))]}
  (let [eid          (:db/id entity-map)
        ident        (:db/ident entity-map)
        existing-id  (:mm/id entity-map)
        existing-pl  (:mm/pref-label entity-map)]
    (cond
      ;; No ident → cannot derive UUID; skip
      (nil? ident)
      []

      ;; Already fully populated → no-op
      (and existing-id existing-pl)
      []

      :else
      (let [tx-id  (when-not existing-id
                     [:db/add eid :mm/id (id/ident-uuid ident)])
            tx-pl  (when-not existing-pl
                     [:db/add eid :mm/pref-label (resolve-pref-label entity-map)])]
        (vec (remove nil? [tx-id tx-pl]))))))


(defn entities-backfill-tx
  "Compute tx-data for a SEQUENCE of entity-maps via `entity-backfill-tx`.
   Concatenates per-entity tx-data into one tx batch.

   Useful for chunked driver-loops: caller queries a batch of unmigrated
   entities, calls this fn, transacts the result.

   Per ζ Scope B ADR §7.1 piggyback strategy + the chunked-driver pattern
   for migration scale (5800+ entities; single-tx may exceed Datomic
   practical limits)."
  [entity-maps]
  {:pre [(sequential? entity-maps)]}
  (into [] cat (map entity-backfill-tx entity-maps)))


(comment
  ;; REPL demonstrations

  (resolve-pref-label {:db/ident :memory.decisions/three_tier_identifier_value_hierarchy
                       :mm.memory/name "Three-Tier Identifier Value Hierarchy"})
  ;; → "Three-Tier Identifier Value Hierarchy"

  (resolve-pref-label {:db/ident :memory.observations/some_observation})
  ;; → "Some Observation"

  (entity-backfill-tx {:db/id 12345
                       :db/ident :memory.decisions/foo
                       :mm.memory/name "Foo Decision"})
  ;; → [[:db/add 12345 :mm/id #uuid "..."]
  ;;    [:db/add 12345 :mm/pref-label "Foo Decision"]]

  (entity-backfill-tx {:db/id 12345
                       :db/ident :memory.decisions/foo
                       :mm.memory/name "Foo Decision"
                       :mm/id (clj-uuid/v5 clj-uuid/+namespace-url+ "already-here")
                       :mm/pref-label "Already Set"})
  ;; → []  (no-op; already populated)
  )
