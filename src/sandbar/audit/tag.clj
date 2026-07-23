(ns sandbar.audit.tag
  "Tag-class lifecycle invariants — read-only audits over :mm/Tag entities + their
   references on :mm/Memory.  Per decisions/tag_as_first_class_introspectable_type_in_metamodel_2026_05_20.md
   §2.5 audit-invariant categories.

   All invariants are READ-ONLY — they produce a report; they NEVER mutate the
   DB.  Tag-lifecycle commands (consolidate / split / rename / etc.) live in
   sandbar.mcp.tools `tag.*` verbs and call these audit functions for
   pre-action validation.

   ## The seven invariants

   | Invariant            | What it surfaces                                                                     |
   |----------------------|--------------------------------------------------------------------------------------|
   | undefined-used       | Tags referenced via :mm.memory/tags whose :mm/Tag lacks :mm.tag/definition           |
   | defined-unused       | :mm/Tag with :mm.tag/definition but no inbound :mm.memory/tags references            |
   | orphan               | :mm/Tag with no :mm.tag/in-scheme membership (not in any concept-scheme)             |
   | date-pattern         | :mm.tag/value matching `YYYY-MM-DD` — likely date-frontmatter pollution, not concept |
   | type-pattern         | :mm.tag/value matching a memorial-type keyword — duplicates :mm.memory/memory-type   |
   | drift                | Multiple :mm/Tag with same normalized form (lower / depluralized) — variant clusters |
   | closure-consistency  | Broader-* / supersedes / related edges checked for acyclicity + symmetric reciprocity |

   ## Return shape contract

   Every public audit function returns a map of the form:

     {:invariant      :undefined-used
      :violation-count 12
      :violations     [{...detail-of-each-violation...}]
      :description    \"Human-readable summary line\"}

   The `audit-all` aggregator returns:

     {:invariants  [...]                          ; map per invariant
      :total-violations 47
      :summary     \"47 violations across 7 invariants\"}

   ## Layering discipline

   Audit code targets `sandbar.db.datatype/*` introspection + `datomic.api/q`
   queries — never raw `:db/...` traversal nor consumer-class hardcoding.
   The :mm.* refs in queries below are first-class :mm-module class knowledge
   that the corpus-of-record uses; sandbar substrate is consumer-class-agnostic
   per
   interaction/no_hardcoded_consumer_class_knowledge_in_substrate_2026_05_13.md,
   but the AUDIT layer is consumer-aware by design (it audits a specific
   class's lifecycle invariants — :mm/Tag in this namespace's case)."
  (:require [clojure.set            :as set]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared query helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- all-tag-entities
  "Returns a vector of entity-maps for every entity with `:mm.tag/value`
   populated.  This includes BOTH:

     - Canonical :mm/Tag entities (post-Stage-7.C — `:dt/type :mm/Tag`
       explicitly set when parsed from `memory/tags/<name>.md` via the
       routed codec)
     - Legacy F#18 anonymous-upsert entities (pre-Stage-7.C — entities
       created by ref-typed-slot upserts that carry only `:mm.tag/value`
       and lack `:dt/type :mm/Tag`)

   Using the attribute-presence query rather than `dt/all-instances-of`
   makes the audit robust to both substrate shapes — important during
   migration M.1-M.5 where both legacy + canonical entities coexist in
   the same DB.  The :mm.tag/value attribute's domain is :mm/Tag, so
   any entity carrying it IS a tag by the schema."
  []
  (let [eids (d/q '[:find [?e ...]
                    :where [?e :mm.tag/value _]]
                  (db/db))]
    (mapv #(db/entity %) eids)))

(defn- tag-value
  "Project the canonical :mm.tag/value off a tag entity-map."
  [tag-ent]
  (:mm.tag/value tag-ent))

(defn- tag-ident
  "Project the :db/ident off a tag entity-map (may be nil for tags that have
   only :mm.tag/value as their identity attribute)."
  [tag-ent]
  (:db/ident tag-ent))

(defn- tag-ref
  "Project a stable string reference for a tag in audit reports.  Prefers
   :db/ident when present; falls back to :mm.tag/value; finally :db/id."
  [tag-ent]
  (or (some-> (tag-ident tag-ent) str)
      (tag-value tag-ent)
      (str (:db/id tag-ent))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 1 — undefined-used
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn undefined-used
  "Surfaces tags that are USED (referenced by some :mm/Memory via
   :mm.memory/tags) but NOT DEFINED (no :mm.tag/definition slot populated).

   These are pre-Stage-7 free-text tags that the corpus has accumulated but
   that lack the SKOS-derived canonical-definition slot.  Migration M.1
   inventory phase surfaces these for hand-authoring at M.2.

   Returns:
     {:invariant :undefined-used
      :violation-count N
      :violations [{:tag <ref> :value <string> :used-by-count N} ...]
      :description \"...\"}"
  []
  (let [tags         (all-tag-entities)
        used-tag-ids (->> (d/q '[:find ?t (count ?m)
                                 :where [?m :mm.memory/tags ?t]]
                               (db/db))
                          (into {}))
        violations   (for [t tags
                           :let [t-id (:db/id t)
                                 used-by-count (get used-tag-ids t-id 0)]
                           :when (and (pos? used-by-count)
                                      (str/blank? (str (:mm.tag/definition t))))]
                       {:tag           (tag-ref t)
                        :value         (tag-value t)
                        :used-by-count used-by-count})]
    {:invariant       :undefined-used
     :violation-count (count violations)
     :violations      (vec violations)
     :description     (str "Tags referenced via :mm.memory/tags but lacking "
                           ":mm.tag/definition — migration candidates for M.2 "
                           "(hand-author canonical definition).")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 2 — defined-unused
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn defined-unused
  "Surfaces tags that ARE DEFINED (:mm.tag/definition populated) but NOT
   USED (no inbound :mm.memory/tags reference).

   These are vocabulary entries that have canonical definitions but no
   corpus memorial currently applies them.  Two-way valid interpretations:
   (a) recently-defined canonical tag awaiting adoption — keep; (b) defined
   tag whose use-cases all migrated away — candidate for lifecycle :retired.

   The audit doesn't decide; it surfaces the set so editorial judgment can
   apply.

   Returns:
     {:invariant :defined-unused
      :violation-count N
      :violations [{:tag <ref> :value <string> :definition <string>} ...]
      :description \"...\"}"
  []
  (let [tags         (all-tag-entities)
        used-tag-ids (->> (d/q '[:find [?t ...]
                                 :where [_ :mm.memory/tags ?t]]
                               (db/db))
                          set)
        violations   (for [t tags
                           :let [t-id (:db/id t)
                                 defn  (:mm.tag/definition t)]
                           :when (and (not (str/blank? (str defn)))
                                      (not (contains? used-tag-ids t-id)))]
                       {:tag        (tag-ref t)
                        :value      (tag-value t)
                        :definition defn})]
    {:invariant       :defined-unused
     :violation-count (count violations)
     :violations      (vec violations)
     :description     (str "Tags with :mm.tag/definition but no inbound "
                           ":mm.memory/tags reference — either pending-adoption "
                           "(keep) or retire-candidate (set :lifecycle-status).")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 3 — orphan
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn orphan-tags
  "Surfaces tags with no :mm.tag/in-scheme membership — tags not declared as
   members of any :mm/ConceptScheme.

   In a mature vocabulary, every canonical tag belongs to at least one scheme
   (the corpus's substrate-level scheme `:memory-system-meta-vocabulary` at
   minimum).  Orphan tags either need scheme-membership assignment or are
   substrate-level vocabulary that hasn't yet been scheme-registered.

   Returns:
     {:invariant :orphan
      :violation-count N
      :violations [{:tag <ref> :value <string>} ...]
      :description \"...\"}"
  []
  (let [tags       (all-tag-entities)
        violations (for [t tags
                         :when (empty? (:mm.tag/in-scheme t))]
                     {:tag   (tag-ref t)
                      :value (tag-value t)})]
    {:invariant       :orphan
     :violation-count (count violations)
     :violations      (vec violations)
     :description     (str "Tags with no :mm.tag/in-scheme membership — not "
                           "declared as members of any :mm/ConceptScheme.  Assign "
                           "scheme via sandbar.tag.define or harmonize.")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 4 — date-pattern
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private date-pattern-re
  "ISO-8601 date pattern + the corpus's `YYYY_MM_DD` underscore variant.
   Matches: `2026-05-12`, `2026_05_12`, `20260512`."
  #"^(?:\d{4}-\d{2}-\d{2}|\d{4}_\d{2}_\d{2}|\d{8})$")

(defn date-pattern-tags
  "Surfaces tags whose :mm.tag/value matches a date pattern.  These are
   typically date-frontmatter pollution that landed as tags — not
   conceptual-vocabulary entries.  Should be moved to a date-typed slot
   or removed.

   Returns:
     {:invariant :date-pattern
      :violation-count N
      :violations [{:tag <ref> :value <string>} ...]
      :description \"...\"}"
  []
  (let [tags       (all-tag-entities)
        violations (for [t tags
                         :let [v (str (tag-value t))]
                         :when (and (seq v)
                                    (re-matches date-pattern-re v))]
                     {:tag   (tag-ref t)
                      :value v})]
    {:invariant       :date-pattern
     :violation-count (count violations)
     :violations      (vec violations)
     :description     (str "Tags whose :mm.tag/value matches a date pattern "
                           "(YYYY-MM-DD / YYYY_MM_DD / YYYYMMDD) — likely "
                           "date-frontmatter pollution rather than conceptual "
                           "vocabulary.  M.4 retire-candidates.")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 5 — type-pattern
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn type-pattern-tags
  "Surfaces tags whose :mm.tag/value matches a known memorial-type keyword
   (`decision`, `observation`, `library`, etc.).  These DUPLICATE the
   `:mm.memory/memory-type` slot — the same information lives in two places.

   Resolution: drop from :mm.memory/tags (memory-type already carries it).
   M.4 retire-candidates.

   The list of known memorial-type keywords is derived from the corpus's
   `memory/types/*.md` self-description (per types/ directory convention).
   For this audit's purposes, we use the small set of canonical types
   surfaced via :mm.memory/memory-type values currently in the DB.

   Returns:
     {:invariant :type-pattern
      :violation-count N
      :violations [{:tag <ref> :value <string> :overlaps-type <kw>} ...]
      :description \"...\"}"
  []
  (let [tags        (all-tag-entities)
        ;; Memorial-type vocabulary derived from the DB itself — every
        ;; distinct :mm.memory/memory-type value currently in use.
        type-values (->> (d/q '[:find [?t ...]
                                :where [_ :mm.memory/memory-type ?t]]
                              (db/db))
                         (map (fn [t]
                                (cond
                                  (keyword? t) (name t)
                                  (string?  t) t
                                  :else        nil)))
                         (remove nil?)
                         set)
        violations  (for [t tags
                          :let [v (str (tag-value t))]
                          :when (and (seq v) (contains? type-values v))]
                      {:tag           (tag-ref t)
                       :value         v
                       :overlaps-type (keyword v)})]
    {:invariant       :type-pattern
     :violation-count (count violations)
     :violations      (vec violations)
     :description     (str "Tags whose :mm.tag/value matches a known memorial-"
                           "type keyword (overlaps :mm.memory/memory-type) — "
                           "redundant; retire-candidates.")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 6 — drift
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- depluralize
  "Light depluralization for drift detection — removes trailing `s` from
   words ending in `s` BUT NOT `ss` (so `class` survives, `tags` → `tag`,
   `audits` → `audit`).  Doesn't attempt irregular plurals; the audit just
   needs to surface drift clusters for editorial review."
  [s]
  (let [s (or s "")]
    (cond
      (and (>= (count s) 4) (str/ends-with? s "ies"))    (str (subs s 0 (- (count s) 3)) "y")
      (and (>= (count s) 3) (str/ends-with? s "es")
           (not (str/ends-with? s "ses")))               (subs s 0 (- (count s) 2))
      (and (>= (count s) 2) (str/ends-with? s "s")
           (not (str/ends-with? s "ss"))
           (not (str/ends-with? s "us"))
           (not (str/ends-with? s "is")))                (subs s 0 (dec (count s)))
      :else                                              s)))

(defn- normalize-tag-form
  "Normalize a tag value for drift detection: lowercase + depluralize.  Used
   ONLY for clustering — the canonical-form choice is editorial."
  [s]
  (-> (or s "") str/lower-case depluralize))

(defn drift-clusters
  "Surfaces clusters of :mm/Tag entities whose :mm.tag/value normalizes to
   the same form — singular/plural drift (`tag` / `tags`), case drift
   (`Audit` / `audit`), and obvious variants.

   Each cluster is a set of 2+ distinct :mm.tag/value strings that
   normalize to the same form.  Migration M.3 auto-merges clusters by
   choosing a canonical + declaring others as :mm.tag/alt-label.

   EXCLUDES already-superseded variants (`:mm.tag/lifecycle-status
   :superseded`) — these have already been consolidated via
   `sandbar.tag.consolidate` / `.consolidate-all`; surfacing them again
   in the drift invariant produces false-positive findings that the
   editorial review cannot act on (already-acted-on).  Per
   `observations/phase_h_M3_drift_consolidation_69_of_71_clusters_landed_…_2026_05_26.md`
   substrate-quality follow-up.

   Returns:
     {:invariant :drift
      :violation-count N-clusters
      :violations [{:normalized-form <string>
                    :variants [{:tag <ref> :value <string>} ...]} ...]
      :description \"...\"}"
  []
  (let [tags     (->> (all-tag-entities)
                      ;; Exclude superseded variants (already consolidated).
                      (remove #(= :superseded (:mm.tag/lifecycle-status %))))
        groups   (->> tags
                      (group-by #(normalize-tag-form (tag-value %)))
                      (filter (fn [[k vs]]
                                (and (seq k)
                                     (>= (count vs) 2))))
                      (sort-by first))
        violations (for [[norm vs] groups]
                     {:normalized-form norm
                      :variants        (mapv (fn [t]
                                               {:tag   (tag-ref t)
                                                :value (tag-value t)})
                                             vs)})]
    {:invariant       :drift
     :violation-count (count violations)
     :violations      (vec violations)
     :description     (str "Drift clusters — multiple :mm/Tag entries with "
                           "the same normalized form (case-insensitive + "
                           "depluralized).  M.3 auto-merge candidates.")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Invariant 7 — closure-consistency
;;
;; Three sub-checks:
;;   (a) acyclic check on broader-generic / broader-instantial / broader-partitive
;;       (per ADR §2.1 declared acyclic for generic/partitive; instantial may
;;        cycle but typically doesn't — flag anyway).
;;   (b) symmetric reciprocity on :mm.tag/related (declared symmetric in ADR §2.1
;;       Tier C; if A → B then B → A should hold).
;;   (c) inverse reciprocity on :mm.tag/superseded-by ↔ :mm.tag/supersedes
;;       (inverse pair per ADR §2.1 Tier D; for now flag asymmetric pairs).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- cycles-via
  "Walk the directed graph defined by `edge-attr` (a :db.type/ref attribute)
   from each :mm/Tag and detect cycles.  Returns a vector of cycle paths,
   each a vector of tag-refs in cycle order.  Empty when graph is acyclic."
  [edge-attr]
  (let [tags     (all-tag-entities)
        ref-map  (into {}
                       (for [t tags
                             :let [refs (get t edge-attr)]
                             :when (seq refs)]
                         [(:db/id t) (set (map :db/id refs))]))
        cycles   (atom #{})
        visit    (fn visit [start path visited]
                   (doseq [next (get ref-map (peek path) #{})]
                     (cond
                       (= next start)
                       (swap! cycles conj (vec path))

                       (contains? visited next)
                       nil

                       :else
                       (visit start (conj path next) (conj visited next)))))]
    (doseq [t tags
            :let [t-id (:db/id t)]
            :when (contains? ref-map t-id)]
      (visit t-id [t-id] #{t-id}))
    (let [tag-by-id (into {} (for [t tags] [(:db/id t) (tag-ref t)]))]
      (vec (for [path @cycles]
             (mapv tag-by-id path))))))

(defn- symmetric-asymmetries
  "Find pairs (A, B) where A has B in :mm.tag/related but B does NOT have A
   in :mm.tag/related (asymmetric reciprocity violation)."
  []
  (let [tags     (all-tag-entities)
        rel-out  (into {}
                       (for [t tags]
                         [(:db/id t) (set (map :db/id (:mm.tag/related t)))]))
        tag-by-id (into {} (for [t tags] [(:db/id t) (tag-ref t)]))
        violations (for [[a-id b-ids] rel-out
                         b-id b-ids
                         :when (not (contains? (get rel-out b-id #{}) a-id))]
                     {:a (tag-by-id a-id)
                      :b (tag-by-id b-id)
                      :issue "A has :related B but B lacks :related A"})]
    (vec violations)))

(defn- inverse-asymmetries
  "Find pairs (A, B) where A has B in :mm.tag/superseded-by but B does NOT
   have A in the inverse :mm.tag/supersedes (or A has B in :supersedes but
   B lacks A in :superseded-by).

   :mm.tag/supersedes isn't declared in the Stage 7.A schema yet (only
   :superseded-by); this check returns empty until the inverse pair is
   declared.  Future-extensible."
  []
  (let [tags     (all-tag-entities)
        super-by (into {}
                       (for [t tags]
                         [(:db/id t) (set (map :db/id (:mm.tag/superseded-by t)))]))
        super-of (into {}
                       (for [t tags]
                         [(:db/id t) (set (map :db/id (:mm.tag/supersedes t)))]))
        tag-by-id (into {} (for [t tags] [(:db/id t) (tag-ref t)]))
        violations (concat
                     (for [[a-id b-ids] super-by
                           b-id b-ids
                           :when (and (seq (get super-of b-id #{}))
                                      (not (contains? (get super-of b-id #{}) a-id)))]
                       {:a     (tag-by-id a-id)
                        :b     (tag-by-id b-id)
                        :issue "A :superseded-by B but B's :supersedes lacks A"}))]
    (vec violations)))

(defn closure-consistency
  "Aggregates the three closure-consistency sub-checks:
   (a) acyclic broader-generic / broader-instantial / broader-partitive,
   (b) symmetric :mm.tag/related reciprocity,
   (c) inverse :mm.tag/superseded-by ↔ :mm.tag/supersedes reciprocity.

   Returns:
     {:invariant :closure-consistency
      :violation-count N
      :violations {:broader-generic-cycles [...]
                   :broader-instantial-cycles [...]
                   :broader-partitive-cycles [...]
                   :related-asymmetries [...]
                   :supersedes-asymmetries [...]}
      :description \"...\"}"
  []
  (let [bg-cycles (cycles-via :mm.tag/broader-generic)
        bi-cycles (cycles-via :mm.tag/broader-instantial)
        bp-cycles (cycles-via :mm.tag/broader-partitive)
        rel-asym  (symmetric-asymmetries)
        sup-asym  (inverse-asymmetries)
        total     (+ (count bg-cycles) (count bi-cycles) (count bp-cycles)
                     (count rel-asym) (count sup-asym))]
    {:invariant       :closure-consistency
     :violation-count total
     :violations      {:broader-generic-cycles    bg-cycles
                       :broader-instantial-cycles bi-cycles
                       :broader-partitive-cycles  bp-cycles
                       :related-asymmetries       rel-asym
                       :supersedes-asymmetries    sup-asym}
     :description     (str "Closure consistency — cycles on broader-* edges, "
                           "asymmetries on :related (declared symmetric), "
                           "missing inverse pairs on :superseded-by/:supersedes.")}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Aggregator — audit-all
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn audit-all
  "Run every audit invariant + aggregate.  Returns:

     {:invariants  [<map per invariant>...]
      :total-violations N
      :summary  \"N violations across 7 invariants\"}

   Implementation: each invariant's :violation-count is summed; the per-
   invariant report is preserved verbatim in :invariants for drill-down.

   Callable from MCP as `sandbar.tag.audit` (no args) — runs everything."
  []
  (let [reports [(undefined-used)
                 (defined-unused)
                 (orphan-tags)
                 (date-pattern-tags)
                 (type-pattern-tags)
                 (drift-clusters)
                 (closure-consistency)]
        total   (reduce + (map :violation-count reports))]
    {:invariants       reports
     :total-violations total
     :summary          (str total " violations across "
                            (count reports) " invariants")}))
