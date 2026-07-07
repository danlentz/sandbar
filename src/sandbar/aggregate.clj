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
            [sandbar.db.datomic     :as db]
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
  (let [groups (dt/group-by-of class group-by where)]
    {:groups groups
     :total  (reduce + 0 (vals groups))}))

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
   Each bin = {tag-ident, tag-value, count}; count is the number of
   entities (any class) that reference the tag via any cardinality-many
   ref slot.

   Optional opts:
     :limit — cap returned bins (default 0 = no cap); sorted descending
              by count, ascending by tag-ident as tie-breaker

   Returns:
     {:histogram [{:tag <ident> :value <string> :count <int>} ...]
      :total <int>}

   Per Stage 5.B-pre #4 of
   decisions/stage_5_mcp_verb_authoring_sub_arc_2026_05_21.md."
  [{:keys [limit] :or {limit 0}}]
  {:pre [(integer? limit) (>= limit 0)]}
  (let [tags    (dt/all-named-instances-of :mm/Tag)
        bins    (for [tag-ident tags
                      :let [e   (db/entity tag-ident)
                            val (:mm.tag/value e)
                            n   (count (dt/inbound-edges-of tag-ident {}))]]
                  {:tag tag-ident :value val :count n})
        sorted  (sort-by (juxt #(- (:count %)) :tag) bins)
        limited (if (zero? limit) sorted (take limit sorted))]
    {:histogram (vec limited)
     :total     (count tags)}))
