(ns sandbar.audit.tag
  "Read-only vocabulary audits over :mm.tag/value carriers and their references.

  Checks report undefined-used, defined-unused, orphan, date-pattern,
  type-pattern, drift and closure-consistency findings. A finding nominates
  data for review; it does not establish that deletion or merging is correct.
  Inspect the population and relationship scope of each check, especially the
  distinction between tags, themes and untyped value carriers.

  Individual results contain :invariant, :violation-count, :violations and
  :description. audit-all returns :invariants, :total-violations and :summary.
  Findings from several invariants can overlap, so their total is not a count
  of distinct bad entities. This namespace reports; mutation operations have
  separate acceptance and identity-preservation responsibilities."
  (:require [clojure.set            :as set]
            [clojure.string         :as str]
            [datomic.api            :as d]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared query helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- all-tag-entities
  "Return every entity carrying :mm.tag/value, including typed Tags and
   lightweight carriers without a type or ident. Attribute presence keeps
   both representations in the audit population. A declared property domain
   does not make this equivalent to the direct/inherited typed-instance query."
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

   This check includes untyped value carriers and considers the tags role
   only. A finding can guide definition authoring; it does not establish that
   the concept is undefined elsewhere or should be deleted.

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

   These are vocabulary entries with definitions but no tags-role use;
   themes and other reference roles are outside this check. Possible readings:
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
  "Report clusters of distinct tag values with the same normalized form.
  Normalization detects spelling, case and singular/plural variants; it does
  not prove conceptual equivalence. Review definitions and member records
  before choosing an identity-preserving mutation.

  Already-superseded variants are excluded. Returns {:invariant :drift
  :violation-count n :violations [{:normalized-form text :variants [...]} ...]
  :description text}. Each variant contains :tag and :value. This audit does
  not merge or rename tags."
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
  "Find an asserted :mm.tag/related edge without its reciprocal assertion.
   This checks stored-edge conventions, not query-time symmetry entailment."
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

   The bundled schema declares :superseded-by but not :mm.tag/supersedes.
   A target must already have a nonempty supersedes set to be examined, so
   an empty result does not prove the inverse relation is represented."
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
  "Audit three conventions over asserted vocabulary relationships:
   (a) acyclic broader-generic / broader-instantial / broader-partitive,
   (b) symmetric :mm.tag/related reciprocity,
   (c) partial inverse :mm.tag/superseded-by / :mm.tag/supersedes reciprocity.

   These are stored-edge findings, not a proof of inferred closure. The
   inverse check only examines targets with an existing supersedes set.

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
