(ns sandbar.migrations.zeta-backfill
  "Compute transaction data for missing durable IDs and preferred labels.
   The pure per-entity helpers compose sandbar.identifier with existing
   memory metadata; calling a helper does not transact. Mutation, cohort
   selection and backup are the migration driver's responsibility.

   Preferred-label fallback uses name, a bounded description, then the ident
   slug. Existing values and identity require their documented preservation
   rules rather than unconditional re-derivation."
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
  "Choose a preferred label from the current entity map: nonblank memory
   name, then description truncated to the configured maximum, then the
   title derived from the ident slug. The map must contain a usable ident
   for the final fallback. Returns the chosen string."
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
        existing-pl  (:mm/pref-label entity-map)
        ident-ok?    (and (keyword? ident)
                          (some? (namespace ident))
                          (some? (name ident)))]
    (cond
      ;; No proper ident → cannot derive UUID; skip
      (not ident-ok?)
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
