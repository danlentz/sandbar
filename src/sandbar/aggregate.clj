(ns sandbar.aggregate
  "Sandbar Aggregation Namespace — Phase G of comprehensive memory-model
  MCP arc.

  Consumer-facing wrappers around the dt/* aggregation primitives.
  Three public verbs:

    count-by  — entity count for a class with optional predicate filter
    group-by  — group-by-count facet aggregation
    rank-by   — structural-rank re-ordering across 4 axes
                (:degree :backlink-density :recency :freshness)

  Higher-level concerns (cross-axis composition with search /
  navigation / orientation) compose via shared opts-maps + the
  result-shape contract defined in fulltext arc plan §1.6.

  Per fulltext arc Stage 13 of
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md."
  (:refer-clojure :exclude [count-by group-by rank-by])
  (:require [sandbar.api.projection :as projection]
            [sandbar.db.datatype    :as dt]
            [sandbar.security.query :as secq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; count-by — entity count with optional predicate filter
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn count-by
  "Count instances of `:class` matching optional `:where` Datalog
  clauses.

  Required opts:
    :class  — class ident

  Optional opts:
    :where  — vec of Datalog clauses (must reference `?e`)

  Returns:
    {:count <int>}

  Per fulltext arc Stage 13."
  [{:keys [class where]}]
  {:pre [(keyword? class)
         (or (nil? where) (sequential? where))]}
  ;; SECURITY (read-plane namespace firewall): deny :class / :where in a
  ;; firewalled namespace (:auth/* etc.) BEFORE any query touches the DB.
  (secq/assert-class-allowed! class)
  (secq/assert-where-namespaces! where)
  (dt/assert-where-eids-allowed! where)   ; numeric-eid-form firewall (db-aware)
  {:count (dt/count-of class where)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; group-by — group-by-count facet aggregation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn group-by
  "Group instances of `:class` by `:group-by` slot value; count per
  group.

  Required opts:
    :class     — class ident
    :group-by  — slot ident to group by

  Optional opts:
    :where     — vec of Datalog clauses (must reference `?e`)

  Returns:
    {:groups {value count}
     :total  <int>}

  Per fulltext arc Stage 13."
  [{:keys [class group-by where] :as opts}]
  {:pre [(keyword? class)
         (keyword? group-by)
         (or (nil? where) (sequential? where))]}
  ;; SECURITY (read-plane namespace firewall): deny a firewalled :class,
  ;; :group-by slot (the credential-hash DUMP vector), or :where attribute.
  (secq/assert-class-allowed! class)
  (secq/assert-attribute-allowed! group-by)
  (secq/assert-where-namespaces! where)
  (dt/assert-where-eids-allowed! where)   ; numeric-eid-form firewall (db-aware)
  (let [groups  (dt/group-by-of class group-by where)
        ;; SECURITY (read-plane firewall): drop firewalled-class buckets from a
        ;; :group-by whose slot yields class refs (e.g. :dt/type) — closes the
        ;; eid-keyed per-:auth/*-class instance-cardinality leak.
        visible (into {} (remove (fn [[k _]] (dt/read-plane-group-key-firewalled? k)) groups))]
    {:groups visible
     :total  (reduce + 0 (vals visible))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; rank-by — structural-rank re-ordering across 4 axes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- rank-axis-keyword?
  [k]
  (#{:degree :backlink-density :recency :freshness} k))

(defn rank-by
  "Re-order instances of `:class` by `:rank-by` structural axis.

  Required opts:
    :class    — class ident
    :rank-by  — axis keyword (one of :degree :backlink-density :recency :freshness)

  Optional opts:
    :limit          — max hits to return (default 20; 0 = no cap)
    :temporal-slot  — required for :recency / :freshness axes; the slot
                      ident carrying the temporal value (e.g.
                      :mm.memory/last-touched).  Substrate does not
                      hardcode class-specific temporal axes per
                      substrate-quality discipline.
    :memorial-policy — optional :dt/memorial-policy keyword
                      (:first-class / :db-only / :inline).  When set, keep
                      only instances whose class's EFFECTIVE memorial-policy
                      (ancestry-walk via dt/effective-memorial-policy-of)
                      matches — the lattice-native curated-vs-operational
                      filter (e.g. :first-class excludes the :db-only
                      :event/* runtime-telemetry subtree + :mm/Run).  Reuses
                      the same axis sandbar.project.dump partitions by.

  Returns:
    {:hits     [{:entity <entity-map> :rank-score <number>} ...]
     :total    <int>
     :returned <int>}

  Per fulltext arc Stage 13."
  [{:keys [class rank-by limit temporal-slot projection memorial-policy]
    :or   {limit 20}}]
  {:pre [(keyword? class)
         (rank-axis-keyword? rank-by)
         (integer? limit) (>= limit 0)
         (or (not (#{:recency :freshness} rank-by))
             (keyword? temporal-slot))
         (or (nil? projection) (#{:full :metadata-only} projection))
         (or (nil? memorial-policy) (keyword? memorial-policy))]}
  ;; SECURITY (read-plane namespace firewall): deny ranking over a firewalled
  ;; class (:auth/* etc.) before enumerating its instances.
  (secq/assert-class-allowed! class)
  (let [pairs   (case rank-by
                  :degree
                  (->> (dt/all-instances-of class)
                       (map (fn [e]
                              (let [eid (:db/id e)
                                    eid-or-ident (or (:db/ident e) eid)]
                                [e (dt/degree-of eid-or-ident)])))
                       (sort-by second >))
                  :backlink-density
                  (->> (dt/all-instances-of class)
                       (map (fn [e]
                              (let [eid (:db/id e)
                                    eid-or-ident (or (:db/ident e) eid)]
                                [e (dt/backlink-density-of eid-or-ident)])))
                       (sort-by second >))
                  :recency
                  (dt/recency-rank-of class temporal-slot)
                  :freshness
                  (dt/freshness-rank-of class temporal-slot))
        ;; Optional lattice-driven memorial-policy filter (per Dan-steer
        ;; 2026-05-29 — decisions/filter_curated_memorials_by_lattice_memorial_policy_...):
        ;; keep only entities whose class's EFFECTIVE :dt/memorial-policy
        ;; matches (e.g. :first-class to surface curated memorials, excluding
        ;; :db-only runtime telemetry — the :event/* subtree + :mm/Run).
        ;; Reuses the substrate primitive dt/effective-memorial-policy-of
        ;; (ancestry-walk, nearest-wins) rather than a bespoke per-type check.
        ;; Memoized per class-ident (few distinct classes; avoids re-walking
        ;; ancestry per entity across the full ranked set).
        filtered (if memorial-policy
                   (let [policy-of (memoize dt/effective-memorial-policy-of)]
                     (filterv (fn [[e _]]
                                (let [t   (:dt/type e)
                                      cls (if (keyword? t) t (:db/ident t))]
                                  (= memorial-policy (policy-of cls))))
                              pairs))
                   pairs)
        total   (count filtered)
        limited (if (zero? limit) filtered (take limit filtered))
        hits    (mapv (fn [[entity rank-score]]
                        {:entity     (projection/apply-projection entity projection)
                         :rank-score rank-score})
                      limited)]
    {:hits     hits
     :total    total
     :returned (count hits)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; tag-histogram — frequency of :mm/Tag usage across the corpus
;; (Stage 5.B-pre #4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn tag-histogram
  "Return a frequency histogram of :mm/Tag usage across the corpus.
   Each bin = {:tag <identifier>, :value <string>, :count <int>}; count is
   the number of entities (any class) that reference the tag via any
   cardinality-many ref slot.  `:tag` is the canonical tag-ref fallback
   (:db/ident, else :mm.tag/value, else :db/id — mirrors
   `sandbar.audit.tag/tag-ref`), so it may be a keyword, string, or Long.

   Optional opts:
     :limit — cap returned bins (default 0 = no cap); sorted descending
              by count, ascending by the stringified tag identifier as
              tie-breaker (type-safe across keyword/string/Long)

   Returns:
     {:histogram [{:tag <ident|value|eid> :value <string> :count <int>} ...]
      :total <int>}

   Per Stage 5.B-pre #4 of
   decisions/stage_5_mcp_verb_authoring_sub_arc_2026_05_21.md."
  [{:keys [limit] :or {limit 0}}]
  {:pre [(integer? limit) (>= limit 0)]}
  ;; Enumerate ALL :mm/Tag instances as entity maps via `dt/all-instances-of`.
  ;; Corpus tags are identless by design (ref-typed-slot upserts carrying only
  ;; :mm.tag/value — see sandbar.audit.tag), so the former
  ;; `dt/all-named-instances-of` (a deprecated alias for `named-idents-of`,
  ;; whose Datalog requires `[?e :db/ident ?ident]`) matched ZERO tags and the
  ;; histogram collapsed to {:histogram [] :total 0} across all 118 live tags —
  ;; the S11/Rec-9 anomaly.  Each tag's `:tag` identifier follows the
  ;; codebase-canonical fallback (mirrors `sandbar.audit.tag/tag-ref`): its
  ;; :db/ident when interned, else its :mm.tag/value string, else its numeric
  ;; :db/id — so an identless-but-valued corpus tag surfaces a human-meaningful
  ;; key (its value) rather than a bare eid, while `:value` still carries the raw
  ;; :mm.tag/value (redundant for identless tags, which is acceptable).  `:count`
  ;; is the number of DISTINCT source entities (tool-card contract: "the number of
  ;; entities ... that reference the tag"), deduped by source :db/id since one
  ;; entity may cite a tag via >1 ref slot (e.g. both :mm.memory/tags and
  ;; :mm.memory/themes).
  (let [tags    (dt/all-instances-of :mm/Tag)
        bins    (for [e tags
                      :let [tag (or (:db/ident e) (:mm.tag/value e) (:db/id e))
                            val (:mm.tag/value e)
                            n   (->> (dt/inbound-edges-of (:db/id e) {})
                                     (map (comp :db/id :source))
                                     distinct
                                     count)]]
                  {:tag tag :value val :count n})
        sorted  (sort-by (juxt #(- (:count %)) (comp str :tag)) bins)
        limited (if (zero? limit) sorted (take limit sorted))]
    {:histogram (vec limited)
     :total     (count tags)}))
