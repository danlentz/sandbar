(ns sandbar.audit.fs-substrate-drift
  "Audit differences between a document tree and stored memory entities.

   The report distinguishes missing files, missing database records, body
   divergence, reference-slot mismatch, duplicate file paths and normalized
   database-path collisions. Missing-file checks apply to classes whose
   memorial policy calls for a first-class document; policy-excluded entities
   are reported separately. Empty and whitespace-only bodies normalize alike.

   File walking is scoped to the collection root. Re-entry paths whose stored
   relative path aliases another file are reported separately rather than
   silently collapsed into a path map. The report exposes the chosen walk
   root and mode. These are discrepancy observations, not deletion authority
   or evidence of which version is correct.

   audit-all composes the projection parser with database inspection and
   returns a structured report without repairing either representation.
   See doc/operations.md for the maintenance procedure.

   The report also carries an enrollment preflight (`:enrollment-preflight`):
   the read-only conditions a tree must clear before an operator maps it in
   `:project-roots` — files without a durable UUID, documents without one,
   file ownership that is absent, ambiguous or mismatched, non-canonical and
   aliased physical paths as the filesystem itself resolves them, invalid
   explicit owners (a store-wide population, reported separately), and every
   read or resolution error as a named unknown.  Detection only; a path the
   operator has dispositioned (`:exclude`) is reported as such, not counted."
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.label :as label]
            [sandbar.project.import-destination :as import-destination]
            [sandbar.projection :as pg]
            [sandbar.reactive.sinks :as sinks]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FS side — walk-root resolution + orphan-twin detection + parse via
;; sandbar.projection/ingest-graph (dry-run; returns specs)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- resolve-walk-root
  "Resolve the EFFECTIVE walk root for the FS side of the audit from the
   operator-supplied `:from` (η.5 hardening (c)).

   The corpus convention (mirroring `sandbar.reactive.sinks/corpus-memory-root`)
   is `<corpus-root>/memory` — the reactive sink writes ONLY there, so only
   that subtree can legitimately correspond to substrate `:mm/Memory` entities.
   Pre-hardening, a repo-root `:from` walked the WHOLE repo and flooded
   `:missing-from-substrate` with non-memorial .md trees (audit-results/ +
   doc/ + plans/ — 562 tracked audit-results/*.md alone at the 2026-07-21
   probe).

   Resolution precedence:
     1. `:from` basename is literally `memory` → `:memory-root`; walk `:from`
        as given (the documented `{:from \"…/claude/memory\"}` invocation).
     2. `<from>/memory` is a directory AND `:from` does not itself look like
        a memory root (no top-level MEMORY.md root-index) →
        `:corpus-root-scoped`; walk `<from>/memory` (the repo-root invocation
        `{:from \"…/claude\"}`).
     3. otherwise → `:as-given`; walk `:from` unchanged (tmpdir test
        fixtures, ad-hoc corpus shapes, single-file heal path).

   Returns `{:walk-root java.io.File, :root-mode kw}`.  Both surface in the
   report `:summary` (`:walk-root` / `:root-mode`) so the resolution is
   always visible to the operator — never a silent re-anchor."
  [from]
  (let [f          (io/file (str from))
        canonical  (try (.getCanonicalFile f) (catch Exception _ f))
        memory-sub (io/file f "memory")]
    (cond
      (= "memory" (.getName canonical))
      {:walk-root f :root-mode :memory-root}

      (and (.isDirectory memory-sub)
           (not (.isFile (io/file f "MEMORY.md"))))
      {:walk-root memory-sub :root-mode :corpus-root-scoped}

      :else
      {:walk-root f :root-mode :as-given})))

(def ^:private +twin-skip-rel-prefixes+
  "Walk-rel prefixes excluded from the audit's PARSE walk: the `memory/`
   corpus-anchor re-entry prefix — exactly the rel-paths for which
   `md/walk-rel->stored-rel-path` is non-identity, i.e. the orphan-twin
   universe `twin-file-entries` reports on.  The two surfaces staying in
   lockstep is pinned by the twin-detection tests (a twin must appear in
   `:twins` AND must never reach the spec-by-path index)."
  #{"memory/"})

(defn- twin-file-entries
  "Enumerate files whose walk-relative path differs from the stored path
   derived by the Markdown codec, such as a memory/ re-entry subtree.

   Such files can alias an ordinary file when indexed by stored relative
   path. Report each separately instead of allowing walk-order-dependent
   replacement in the comparison map.

   Entries carry :walk-rel-path, :stored-rel-path and
   :shadows-existing-file?. Enumeration uses the same Markdown walk and
   default index-file exclusions as parsing. A reported twin is evidence
   for review, not proof that its content may be discarded."
  [walk-root]
  (->> (pg/walk-markdown-rel-paths walk-root {})
       (keep (fn [walk-rel]
               (let [stored (md/walk-rel->stored-rel-path walk-rel)]
                 (when (not= walk-rel stored)
                   {:walk-rel-path          walk-rel
                    :stored-rel-path        stored
                    :shadows-existing-file? (.isFile (io/file walk-root stored))}))))
       (sort-by :walk-rel-path)
       vec))

(declare normalize-rel-path)

(defn- fs-side
  "Walk `walk-root` recursively via `sandbar.projection/ingest-units` and
   return `{:specs … :parse-failed …}`: the TOP-LEVEL `:mm/Memory`
   entity-specs of every parsed unit (not the per-section sub-specs; each
   carries `:mm.memory/rel-path` + `:mm.memory/body-raw` + the frontmatter
   slots the codec parsed out), and the units the parser could not read or
   parse, as `{:rel-path :error}` — a file the audit cannot see is named,
   never silently absent from both sides (`ingest-graph`'s flat shape drops
   them; the unit report carries them, REP-06).

   The orphan-twin subtree (`+twin-skip-rel-prefixes+`) is excluded at
   enumeration time so a twin's spec can never alias — and silently
   overwrite — the real file's spec in the by-path index (η.5 (a));
   twins surface via `twin-file-entries` instead."
  [walk-root]
  (let [units (pg/ingest-units walk-root {:skip-rel-prefixes +twin-skip-rel-prefixes+})]
    {:specs        (->> units
                        (filter #(= :parsed (:status %)))
                        (mapcat :entities)
                        ;; memory-level entries carry :mm.memory/rel-path;
                        ;; sub-section entries don't (their own ident namespace)
                        (filter #(contains? % :mm.memory/rel-path))
                        vec)
     :parse-failed (->> units
                        (filter #(= :parse-failed (:status %)))
                        (map (fn [u] {:rel-path (normalize-rel-path (:source u)) :error (:error u)}))
                        (sort-by :rel-path)
                        vec)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Substrate side — Datalog enumeration of :mm/Memory entities with rel-path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- normalize-rel-path
  "Canonicalize a rel-path for cross-side comparison.  Strips a leading
   `memory/` prefix so the FS-walk-relative form ('actors/foo.md') and
   the substrate `:mm.memory/rel-path` slot form that may carry the
   prefix ('memory/actors/foo.md') collapse to the same comparison key.

   Per η.4 Target #2 (2026-05-28): the η.3 audit reported 1667 spurious
   :missing-from-substrate entries — entities that EXIST + are findable
   by entity-find/find-by-rel-path, but whose :mm.memory/rel-path slot
   carries a leading `memory/` (older codec convention) while the FS
   walk produces the prefix-less form.  The exact-string set-difference
   treated the prefix difference as a missing file.  Normalizing both
   sides before the difference eliminates the false positives WITHOUT
   hiding genuine missing files (a real orphan still has no match on
   either side).

   The substrate-side canonical rel-path FORM ratification (should the
   slot store with-prefix or without?) + the corpus batch-normalize is
   a separate substrate-quality concern deferred to the ε predicate
   sweep; this fix is comparison-robustness only."
  [rel-path]
  (when rel-path
    (str/replace rel-path #"^memory/" "")))

(defn- entity-class-ident
  "The `:dt/type` class ident of a live Datomic entity, tolerant of the two
   surfacings a ref slot has (entity-map via `d/entity` navigation, or a
   bare keyword on spec-shaped maps).  nil when the entity is untyped."
  [entity]
  (let [t (:dt/type entity)]
    (cond
      (keyword? t) t
      (nil? t)     nil
      :else        (:db/ident t))))

(defn- substrate-memory-entities
  "All `:mm/Memory` entities (incl. subclass instances) that carry a
   `:mm.memory/rel-path`.  Returns a vec of `{:db/id, :entity-ident,
   :rel-path, :class, :entity}` — the `:entity` slot is the live Datomic
   entity-map for downstream slot-by-slot comparison; `:class` is the
   `:dt/type` ident consulted by the η.5 memorial-policy scoping of
   `:missing-from-fs`."
  []
  (let [db (db/db)]
    (->> (d/q '[:find ?e ?rel-path
                :where
                [?e :mm.memory/rel-path ?rel-path]]
              db)
         (mapv (fn [[eid rel-path]]
                 (let [entity (d/entity db eid)]
                   {:db/id        eid
                    :entity-ident (:db/ident entity)
                    :rel-path     rel-path
                    :class        (entity-class-ident entity)
                    :entity       entity}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Comparison helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Ref-slot vocabulary inspected for ref-mismatch.  These are the common
;; cardinality-many ref slots that operators edit in frontmatter.  Extend as
;; new substrate ref-slots get authored.
(def ^:private ref-slot-attrs
  #{:mm.memory/cites
    :mm.memory/related
    :mm.memory/composes-with
    :mm.memory/evidences
    :mm.memory/tags
    :mm.memory/themes
    :mm.memory/created-by
    :mm.memory/superseded-by
    :mm.memory/parent
    :mm.memory/motivated-by})

(defn- normalize-ref-set
  "Coerce a slot value to a #{idents}.  Datomic entity slots may surface as
   entity-maps (d/entity wrapped), keywords (already ident), or sets/vectors of
   either.  FS-side specs surface as keyword refs (or vectors thereof) from the
   codec.  Normalize to a set of keywords for comparison."
  [v]
  (cond
    (nil? v)            #{}
    (keyword? v)        #{v}
    (map? v)            (when-let [ident (:db/ident v)] #{ident})
    (or (set? v)
        (sequential? v) (vector? v))
    (->> v
         (keep (fn [item]
                 (cond
                   (keyword? item) item
                   (map? item)     (:db/ident item))))
         set)
    :else               #{}))

(defn- normalize-body
  "Canonicalize a body-raw value for cross-side comparison (η.5 hardening
   (b)): nil, empty-string, and whitespace-only bodies ALL collapse to `\"\"`
   — the three encodings of 'no body content' that the codec parse (empty
   string after frontmatter) and the substrate (slot simply absent) both
   legitimately produce for the same document.  Pre-hardening, nil-vs-empty
   compared UNEQUAL and surfaced as `0 chars vs 0 chars` comparator noise —
   46 of the 55 live `:content-divergence` entries at the 2026-07-21 probe.
   Trailing-newline trim (the pre-existing neutrality) is preserved for
   non-blank bodies."
  [s]
  (let [t (some-> s str/trim-newline)]
    (if (or (nil? t) (str/blank? t)) "" t)))

(defn- compare-fs-to-entity
  "Compare an FS-side entity-spec against the substrate entity at the same
   rel-path.  Emits a map with `:content-diff?` / `:ref-diff?` flags +
   detail.  Returns nil when fully in-sync.

   Body comparison runs over `normalize-body`-canonicalized values on BOTH
   sides, so absent-vs-empty body encodings are never divergence and the
   reported char counts are the counts actually compared."
  [fs-spec substrate-row]
  (let [substrate-entity (:entity substrate-row)
        rel-path         (:rel-path substrate-row)
        entity-ident     (:entity-ident substrate-row)
        fs-body          (normalize-body (:mm.memory/body-raw fs-spec))
        sub-body         (normalize-body (:mm.memory/body-raw substrate-entity))
        body-diff?       (not= fs-body sub-body)
        ;; Ref-slot comparison across the known ref-slot vocabulary
        ref-diffs        (reduce
                          (fn [acc attr]
                            (let [fs-refs  (normalize-ref-set (get fs-spec attr))
                                  sub-refs (normalize-ref-set (get substrate-entity attr))
                                  missing-in-substrate (set/difference fs-refs sub-refs)
                                  missing-in-fs        (set/difference sub-refs fs-refs)]
                              (if (or (seq missing-in-substrate)
                                      (seq missing-in-fs))
                                (assoc acc attr {:fs-refs              fs-refs
                                                 :substrate-refs       sub-refs
                                                 :missing-in-substrate missing-in-substrate
                                                 :missing-in-fs        missing-in-fs})
                                acc)))
                          {}
                          ref-slot-attrs)
        ref-diff?        (seq ref-diffs)]
    (when (or body-diff? ref-diff?)
      (cond-> {:rel-path     rel-path
               :entity-ident entity-ident}
        body-diff? (assoc :content-diff?  true
                          :differing-slots [:mm.memory/body-raw]
                          :diff-summary
                          (format "body-raw differs (fs: %d chars vs substrate: %d chars)"
                                  (count fs-body) (count sub-body)))
        ref-diff?  (assoc :ref-diff?    true
                          :ref-diffs    ref-diffs)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public verb — audit-all
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Enrollment preflight — the read-only conditions a tree must clear before
;; an operator maps it (onboarding wave, 2026-09-20; doc/operations.md,
;; "Serve a project repository")
;;
;; With an operator :project-roots map, a routed write refuses an existing
;; file without one matching durable identity, a non-canonical or aliased
;; path, and a document whose explicit owner is not a Project — so a tree
;; enrolled with those conditions present stalls at its first ordinary edit.
;; This section names them beforehand, attributed to the inspected tree,
;; using the parsers and predicates the write path uses.  Detection only:
;; nothing is minted, adopted, moved or repaired, and a path the operator has
;; dispositioned (`:exclude`) is reported under that disposition rather than
;; counted.  Run it against the proposed configuration while every writer is
;; stopped: the map in the service configuration is the proposal until the
;; service restarts with it, and this audit reads that same map.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- swap-letter-case
  "`s` with every letter's case swapped, or nil when it carries no letter."
  [s]
  (when (some #(Character/isLetter (char %)) s)
    (apply str (map (fn [c]
                      (cond (Character/isUpperCase (char c)) (Character/toLowerCase (char c))
                            (Character/isLowerCase (char c)) (Character/toUpperCase (char c))
                            :else c))
                    s))))

(defn- filesystem-case-insensitive?
  "Whether the filesystem holding `dir` folds case, decided by asking that
   filesystem rather than the platform: the directory's own name with its
   letters' case swapped names the SAME directory (`Files/isSameFile`, by
   file key) only where case is folded.  Read-only — nothing is created.
   `:unknown` when the name carries no letter to swap, the directory has no
   parent, or the filesystem could not be asked; then no case folding is
   applied anywhere in the preflight."
  [^java.io.File dir]
  (let [name    (.getName dir)
        swapped (swap-letter-case name)
        parent  (.getParentFile dir)]
    (if (or (nil? swapped) (nil? parent) (not (.isDirectory dir)))
      :unknown
      (try
        (let [twin (io/file parent swapped)]
          (boolean (and (.isDirectory twin)
                        (java.nio.file.Files/isSameFile (.toPath twin) (.toPath dir)))))
        (catch Exception _ :unknown)))))

(defn- canonical-target
  "The canonical absolute path `rel-path` denotes under `base`, as
   `{:canonical <path>}`, or `{:error <message> :phase :canonicalize}` — a
   failure to resolve a path is a named unknown, never a clean zero."
  [^java.io.File base rel-path]
  (try {:canonical (.getCanonicalPath (io/file base rel-path))}
       (catch Exception e {:error (.getMessage e) :phase :canonicalize})))

(defn- noncanonical-rel-path?
  "The spelling test a routed write applies (`sandbar.project.destination/
   target-path` refuses `:noncanonical-rel-path`): the path carries an empty
   or dot segment, or its canonical target is not exactly base + separator +
   rel-path — a symlinked component, or on a case-folding filesystem a
   spelling that differs from the file on disk."
  [base-canonical rel-path canonical]
  (boolean
    (or (some #{"" "." ".."} (str/split rel-path #"/" -1))
        (not= canonical (str base-canonical java.io.File/separator rel-path)))))

(defn- file-identity-rows
  "One row per walked file under `walk-root` (the parse walk's own universe:
   the same skip set, the twin subtree excluded), keyed by normalized
   rel-path, carrying what the file's front matter claims as its identity
   through the writer's parser (`sinks/projected-file-identity`: `{:id …}`,
   `{:ambiguous? true :ids …}` or nil) — or, when the file cannot be read,
   `:error` and `:phase :read-identity`."
  [walk-root]
  (vec
    (for [rel (pg/walk-markdown-rel-paths walk-root {:skip-rel-prefixes +twin-skip-rel-prefixes+})
          :let [path (normalize-rel-path rel)
                f    (io/file walk-root rel)]]
      (try
        {:rel-path path :identity (sinks/projected-file-identity f)}
        (catch Exception e
          {:rel-path path :error (.getMessage e) :phase :read-identity})))))

(defn- invalid-owner-of
  "Why `entity`'s explicit owner is one a routed write refuses — the test
   `sandbar.project.destination/for-entity` makes before refusing
   `:invalid-owner`, stated here so the preflight names the entity instead
   of the write failing later: `:owner-unresolvable` when the reference
   resolves to nothing, `:owner-not-a-project` when it resolves to an entity
   of another class; nil for an absent or valid owner."
  [db entity]
  (when-let [owner (:mm.memory/owning-project entity)]
    (let [eid (ref/ref->eid db owner)]
      (cond
        (nil? eid) :owner-unresolvable
        (not (label/class-isa? db :mm/Project (label/class-ident-of (d/entity db eid)))) :owner-not-a-project
        :else nil))))

(defn- owner-display
  "A reference as the report shows it: the owner's ident, else its eid."
  [db entity]
  (let [owner (:mm.memory/owning-project entity)
        eid   (ref/ref->eid db owner)]
    (cond
      (and eid (:db/ident (d/entity db eid))) (str (:db/ident (d/entity db eid)))
      eid   (str eid)
      :else (pr-str owner))))

(defn- preflight-condition-rows
  "The condition rows of one tree, before dispositions: a map from condition
   keyword to a vector of rows, each row carrying the `:rel-path` it is
   attributed to.  See `enrollment-preflight` for the vocabulary."
  [{:keys [db base attribution identity-rows ent-groups collisions parse-failed all-entities fs-paths sub-paths]}]
  (let [base-canonical  (.getCanonicalPath ^java.io.File base)
        case-folding?   (filesystem-case-insensitive? base)
        ;; every document of the tree — all the rows at a path, not the
        ;; comparison's one representative, so a second document at the same
        ;; stored path is checked and named, never hidden behind the first
        tree-rows       (vec (apply concat (vals ent-groups)))
        readable        (remove :error identity-rows)
        ;; rel-path -> the file's claimed identity (nil when it claims none);
        ;; only files that could be read have an entry
        identity-by     (into {} (map (juxt :rel-path :identity)) readable)
        ;; files: identity absent or ambiguous
        files-no-uuid   (->> readable (filter #(nil? (:identity %))) (map #(select-keys % [:rel-path])) vec)
        files-ambiguous (->> readable (filter #(:ambiguous? (:identity %)))
                             (map (fn [r] {:rel-path (:rel-path r) :ids (get-in r [:identity :ids])})) vec)
        ;; documents of this tree: no durable identity (the :db/ident is
        ;; present — the row shows it; what is missing is the :mm/id UUID)
        docs-no-uuid    (->> tree-rows
                             (filter #(nil? (:mm/id (:entity %))))
                             (map (fn [row]
                                    (let [path    (normalize-rel-path (:rel-path row))
                                          file-id (:id (identity-by path))]
                                      (cond-> {:rel-path     path
                                               :entity-ident (:entity-ident row)
                                               :missing      :mm/id}
                                        file-id (assoc :file-id file-id)))))
                             (sort-by :rel-path) vec)
        ;; file ownership over the compared pairs
        pairs           (for [path (sort (set/intersection fs-paths sub-paths))
                              row  (ent-groups path)
                              :let [stored-id (some-> (:mm/id (:entity row)) str)]
                              :when (and stored-id (contains? identity-by path))]
                          [path row stored-id (identity-by path)])
        ownership       (->> pairs
                             (keep (fn [[path row stored-id fid]]
                                     (cond
                                       (nil? fid)        {:rel-path path :entity-ident (:entity-ident row)
                                                          :ownership :absent :stored-id stored-id}
                                       (:ambiguous? fid) {:rel-path path :entity-ident (:entity-ident row)
                                                          :ownership :ambiguous :stored-id stored-id :file-ids (:ids fid)}
                                       (not= (:id fid) stored-id)
                                       {:rel-path path :entity-ident (:entity-ident row)
                                        :ownership :mismatched :stored-id stored-id :file-id (:id fid)}
                                       :else nil)))
                             vec)
        matched-count   (count (filter (fn [[_ _ stored-id fid]] (= (:id fid) stored-id)) pairs))
        ;; physical paths: every rel-path this tree knows, from its files and
        ;; its documents, resolved on the actual filesystem
        candidates      (->> (concat (map :rel-path readable)
                                     (map (comp normalize-rel-path :rel-path) tree-rows))
                             distinct sort)
        resolved        (mapv (fn [rel] (assoc (canonical-target base rel) :rel-path rel)) candidates)
        canon-errors    (->> resolved (filter :error) (map #(select-keys % [:rel-path :error :phase])) vec)
        noncanonical    (->> resolved (remove :error)
                             (filter #(noncanonical-rel-path? base-canonical (:rel-path %) (:canonical %)))
                             (map #(select-keys % [:rel-path :canonical])) vec)
        alias-key       (if (true? case-folding?)
                          #(str/lower-case (:canonical %))
                          :canonical)
        alias-groups    (->> resolved (remove :error)
                             (group-by alias-key)
                             (keep (fn [[_ rows]]
                                     (let [paths (vec (sort (distinct (map :rel-path rows))))]
                                       (when (> (count paths) 1)
                                         {:canonical (:canonical (first rows)) :rel-paths paths}))))
                             (sort-by :canonical) vec)
        ;; owners: the store-wide population, never folded into the tree's
        ;; selection — an invalid owner attributes nowhere reliably, so it is
        ;; named here with whether its file sits in the inspected tree
        invalid-owners  (->> all-entities
                             (keep (fn [row]
                                     (when-let [reason (invalid-owner-of db (:entity row))]
                                       (let [path (normalize-rel-path (:rel-path row))]
                                         {:rel-path              path
                                          :entity-ident          (:entity-ident row)
                                          :owner                 (owner-display db (:entity row))
                                          :reason                reason
                                          :file-present-in-tree? (contains? fs-paths path)}))))
                             (sort-by :rel-path) vec)
        ;; files of this tree whose document is routed to another tree — the
        ;; audit reports them missing from the substrate; the preflight says
        ;; which tree their document selects
        other-tree      (when (contains? #{:mapped :global} (:kind attribution))
                          (let [by-path (group-by (comp normalize-rel-path :rel-path) all-entities)]
                            (->> (set/difference fs-paths sub-paths)
                                 (keep (fn [path]
                                         (when-let [rows (seq (by-path path))]
                                           (let [valid (remove #(invalid-owner-of db (:entity %)) rows)]
                                             (when (seq valid)
                                               {:rel-path      path
                                                :entity-idents (vec (sort-by str (map :entity-ident valid)))
                                                :selected-root (import-destination/entity-root db (:roots attribution) (:entity (first valid)))})))))
                                 (sort-by :rel-path) vec)))]
    {:filesystem            {:case-insensitive? case-folding?}
     :files-without-uuid    files-no-uuid
     :files-ambiguous-uuid  files-ambiguous
     :documents-without-uuid docs-no-uuid
     :ownership             ownership
     :ownership-matched-count matched-count
     :noncanonical-paths    noncanonical
     :alias-groups          alias-groups
     :invalid-owners        invalid-owners
     :files-of-other-trees  (or other-tree [])
     ;; the audit's own claimant groups: two or more documents at one stored
     ;; path (`:substrate-rel-path-collisions`), a condition here because the
     ;; comparison keeps one representative and clearance must not
     :document-collisions   (vec collisions)
     :errors                (vec (concat (->> identity-rows (filter :error) (map #(select-keys % [:rel-path :error :phase])))
                                         canon-errors
                                         ;; the ingestion unit report's failures: a file the
                                         ;; parser could not read or parse, whose id line may
                                         ;; still be readable — an unknown, never clear
                                         (map #(assoc % :phase :parse) parse-failed)))}))

(def ^:private preflight-condition-keys
  "The per-path condition lists a disposition can cover, in report order."
  [:files-without-uuid :files-ambiguous-uuid :documents-without-uuid :ownership
   :noncanonical-paths :invalid-owners :files-of-other-trees :document-collisions :errors])

(defn enrollment-preflight
  "The read-only enrollment conditions of the inspected tree, from the parts
   `audit-all` already computed.  Returns a map with, per condition, the rows
   attributed to the tree (`:rel-path` on every row):

   - `:files-without-uuid` — walked files whose front matter declares no `id`
     (a missing durable UUID; nothing to do with `:db/ident`, which a document
     always has); `:files-ambiguous-uuid` — files declaring `id` more than
     once (`:ids`).
   - `:documents-without-uuid` — the tree's documents with no `:mm/id`
     (`:entity-ident` shown; `:file-id` when the file claims one).
   - `:ownership` — compared pairs whose file identity is `:absent`,
     `:ambiguous` or `:mismatched` against the stored `:mm/id`
     (`:ownership-matched-count` counts the pairs that agree).
   - `:noncanonical-paths` — rel-paths a routed write would refuse
     (`:noncanonical-rel-path`): empty or dot segments, a symlinked component,
     a spelling differing from the file on disk where the filesystem folds
     case; `:alias-groups` — two or more distinct rel-paths, from files or
     documents, resolving to one physical target (`:canonical`), case-folded
     only where the filesystem does (`:filesystem`).
   - `:invalid-owners` — the STORE-WIDE population of documents whose explicit
     owner a routed write refuses (`:owner-unresolvable` /
     `:owner-not-a-project`), each with `:file-present-in-tree?`; reported
     separately because such a document attributes to no tree reliably and
     must not vanish as an excluded record.
   - `:files-of-other-trees` — files of this tree whose document is routed to
     another tree (`:selected-root`); the audit lists them missing from the
     substrate, this says why.
   - `:document-collisions` — two or more documents at one stored path
     (the audit's `:substrate-rel-path-collisions`), each document checked
     above on its own, never only the comparison's representative.
   - `:errors` — a file that could not be read (`:read-identity`), a path
     that could not be resolved (`:canonicalize`), or a file the ingestion
     parser rejected (`:parse`, from the unit report — its id line may still
     read), by `:phase`; never a clean zero, and never clear.

   `:exclude` (rel-paths, stored form) are the operator's explicit
   dispositions: their rows move to `:excluded` (`{rel-path [conditions]}`)
   and do not count.  `:unresolved-count` counts the rest — for the tree's
   own conditions and, of the invalid owners, those whose file sits in this
   tree (all of them when the tree is the global root, which is where an
   unowned document projects).  `:clear-for-enrollment?` is true only for a
   mapped or global tree with zero unresolved conditions and no errors; an ad
   hoc directory is never clear (`:reason`)."
  [{:keys [attribution exclude] :as parts}]
  (let [conditions  (preflight-condition-rows parts)
        excluded?   (set (map normalize-rel-path exclude))
        kind        (:kind attribution)
        counts-for  (fn [rows] (vec (remove #(excluded? (:rel-path %)) rows)))
        excluded    (reduce (fn [acc k]
                              (reduce (fn [m row]
                                        (if (excluded? (:rel-path row))
                                          (update m (:rel-path row) (fnil conj []) (assoc row :condition k))
                                          m))
                                      acc (get conditions k)))
                            {} preflight-condition-keys)
        kept        (reduce (fn [m k] (assoc m k (counts-for (get conditions k)))) {} preflight-condition-keys)
        alias-kept  (vec (remove #(every? excluded? (:rel-paths %)) (:alias-groups conditions)))
        owners-here (filter (fn [row] (or (= :global kind) (:file-present-in-tree? row))) (:invalid-owners kept))
        unresolved  (+ (count (:files-without-uuid kept))
                       (count (:files-ambiguous-uuid kept))
                       (count (:documents-without-uuid kept))
                       (count (:ownership kept))
                       (count (:noncanonical-paths kept))
                       (count alias-kept)
                       (count owners-here)
                       (count (:files-of-other-trees kept))
                       (count (:document-collisions kept)))
        errors      (:errors kept)
        tree?       (contains? #{:mapped :global} kind)
        clear?      (and tree? (zero? unresolved) (empty? errors))]
    (merge conditions
           kept
           {:alias-groups            alias-kept
            :excluded                excluded
            :tree                    (select-keys attribution [:kind :root :project-key])
            :invalid-owners-store-wide-count (count (:invalid-owners conditions))
            :invalid-owners-counted-count    (count owners-here)
            :unresolved-count        unresolved
            :error-count             (count errors)
            :clear-for-enrollment?   clear?
            :reason                  (cond
                                       (not tree?)      "the walk root is not a configured project tree or the global root; map it in the proposed configuration and run again"
                                       (seq errors)     "a file or path could not be read or resolved; see :errors"
                                       (pos? unresolved) "unresolved conditions remain; see the lists, or disposition a path with :exclude"
                                       :else            nil)})))

(defn audit-all
  "Run the full FS↔substrate drift audit.

   Required opt: `:from` — the corpus-root directory path (matching the
   `sandbar.project.import` MCP verb convention; the operator is responsible
   for passing the path where the live substrate's corpus was loaded from).
   Both the repo-root form (`…/claude`) and the memory-root form
   (`…/claude/memory`) resolve to the SAME effective walk root per
   `resolve-walk-root` (η.5 (c)); the resolution is surfaced in `:summary`
   as `:walk-root` + `:root-mode`.

   Optional opt: `:exclude` — rel-paths (stored form) the operator has
   dispositioned; the enrollment preflight reports their conditions under
   `:excluded` instead of counting them.

   Returns a structured drift report per the η.2 ADR contract (η.5-extended):

     {:summary {:fs-file-count, :substrate-entity-count, …, :twin-count,
                :missing-from-fs-policy-excluded-count,
                :substrate-rel-path-collision-count,
                :total-drift-count, :audit-duration-ms, :audit-instant,
                :corpus-root, :walk-root, :root-mode}
      :missing-from-substrate [<rel-path-str> ...]
      :missing-from-fs        [<entity-ident> ...]   ; corpus-document classes only
      :missing-from-fs-policy-excluded
                              [{:entity-ident, :class, :rel-path} ...]
      :content-divergence     [{:rel-path, :entity-ident, :diff-summary,
                                :differing-slots} ...]
      :ref-slot-mismatch      [{:rel-path, :entity-ident, :ref-diffs {<attr>
                                {:fs-refs, :substrate-refs,
                                 :missing-in-substrate, :missing-in-fs}}} ...]
      :twins                  [{:walk-rel-path, :stored-rel-path,
                                :shadows-existing-file?} ...]
      :substrate-rel-path-collisions
                              [{:rel-path, :entity-idents} ...]
      :fs-parse-failed        [{:rel-path, :error} ...]   ; files the parser could not read or parse
      :enrollment-preflight   (see `enrollment-preflight`; its
                               `:unresolved-count`, `:error-count` and
                               `:clear-for-enrollment?` also in `:summary`)}

   `:total-drift-count` = missing-from-substrate + missing-from-fs +
   content-divergence + ref-slot-mismatch + twins + substrate-rel-path-
   collisions.  Policy-excluded entries are NOT drift (the projection
   policy says those classes never emit FS files) — they are surfaced for
   visibility only."
  [{:keys [from exclude] :as _opts}]
  (when (str/blank? (str from))
    (throw (ex-info "sandbar.audit.fs-substrate-drift/audit-all requires :from (corpus root directory)"
                    {:reason :missing-from-arg})))
  (let [t-start              (System/currentTimeMillis)
        {:keys [walk-root root-mode]} (resolve-walk-root from)
        _                    (log/info :FS-DRIFT-AUDIT/START
                                       {:from      (str from)
                                        :walk-root (str walk-root)
                                        :root-mode root-mode})
        twins                (twin-file-entries walk-root)
        {fs-specs :specs parse-failed :parse-failed} (fs-side walk-root)
        _                    (when (seq parse-failed)
                               (log/warn :FS-DRIFT-AUDIT/FS-PARSE-FAILED
                                         {:count (count parse-failed) :files parse-failed}))
        ;; Per-root selection (onboarding wave, 2026-09-20): with an operator
        ;; :project-roots map, a walked root that is a configured project tree
        ;; (or the global root) is compared only with the entities whose owner
        ;; selects that tree, so the files of one tree are never reported
        ;; missing against the documents of another.  An ad hoc :from keeps the
        ;; store-wide comparison the audit always made, and the report says so
        ;; (`:root-attribution`).  Attribution reads the same operator map the
        ;; sink writes by (`sandbar.project.destination`), never corpus-repo.
        db-now               (db/db)
        attribution          (import-destination/audit-attribution db-now walk-root)
        all-entities         (substrate-memory-entities)
        entities             (if (= :ad-hoc (:kind attribution))
                               all-entities
                               (filterv #(import-destination/entity-in-tree? db-now attribution (:entity %))
                                        all-entities))
        _                    (log/info :FS-DRIFT-AUDIT/ROOT-ATTRIBUTION
                                       {:kind        (:kind attribution)
                                        :root        (:root attribution)
                                        :project-key (:project-key attribution)
                                        :selected    (count entities)
                                        :store-wide  (count all-entities)})
        ;; η.4 Target #2 (2026-05-28): key both maps by the NORMALIZED
        ;; rel-path (leading `memory/` stripped) so prefix-convention
        ;; differences between the FS-walk form and the substrate
        ;; :mm.memory/rel-path slot don't surface as spurious drift.
        ;; (FS-side keys are collision-free by construction since η.5 (a):
        ;; the twin subtree — the only source of stored-rel aliasing — is
        ;; excluded from the parse walk, so normalize is identity there.)
        fs-by-path           (into {} (map (juxt (comp normalize-rel-path :mm.memory/rel-path) identity)) fs-specs)
        ;; η.5: the substrate side CAN collide (two entities whose rel-path
        ;; slots normalize to the same key — the DB-side twin shape; live
        ;; probe 2026-07-22: 61 collisions, every one pairing an IDENT-LESS
        ;; junk duplicate with the real entity).  The old `into {}` was
        ;; last-write-wins: the losing entity silently vanished from every
        ;; drift category.  Group first; surface collisions; index
        ;; deterministically with IDENTFUL rows preferred (then lowest
        ;; ident) so the canonical entity — never the ident-less junk
        ;; duplicate — is the comparison representative for the path.
        ent-groups           (group-by (comp normalize-rel-path :rel-path) entities)
        substrate-collisions (->> ent-groups
                                  (keep (fn [[path rows]]
                                          (when (> (count rows) 1)
                                            {:rel-path      path
                                             :entity-idents (vec (sort-by str (map :entity-ident rows)))})))
                                  (sort-by :rel-path)
                                  vec)
        _                    (when (seq substrate-collisions)
                               (log/warn :FS-DRIFT-AUDIT/SUBSTRATE-REL-PATH-COLLISIONS
                                         {:count      (count substrate-collisions)
                                          :collisions substrate-collisions}))
        ent-by-path          (into {} (map (fn [[path rows]]
                                             [path (first (sort-by (fn [row]
                                                                     [(nil? (:entity-ident row))
                                                                      (str (:entity-ident row))])
                                                                   rows))]))
                                   ent-groups)
        fs-paths             (set (keys fs-by-path))
        sub-paths            (set (keys ent-by-path))
        missing-from-substrate (vec (sort (set/difference fs-paths sub-paths)))
        ;; η.5 (c): class-scope :missing-from-fs by memorial policy — only
        ;; classes the projection policy actually emits FS files for
        ;; (dt/corpus-document-class?) can be MISSING from FS.  Entities
        ;; carrying a rel-path despite a never-projects class (:db-only /
        ;; :inline / runtime-behavioral kin / untyped) surface separately.
        absent-rows            (map ent-by-path (set/difference sub-paths fs-paths))
        {projectable-absent true policy-excluded-absent false}
        (group-by (fn [row] (boolean (dt/corpus-document-class? (:class row))))
                  absent-rows)
        missing-from-fs        (vec (sort (map :entity-ident projectable-absent)))
        policy-excluded        (->> policy-excluded-absent
                                    (map #(select-keys % [:entity-ident :class :rel-path]))
                                    (sort-by (comp str :entity-ident))
                                    vec)
        both-present-paths     (set/intersection fs-paths sub-paths)
        comparisons            (keep (fn [path]
                                       (compare-fs-to-entity (fs-by-path path)
                                                              (ent-by-path path)))
                                     both-present-paths)
        content-divergence     (vec (filter :content-diff? comparisons))
        ref-slot-mismatch      (vec (filter :ref-diff? comparisons))
        ;; Enrollment preflight (onboarding wave, 2026-09-20): the read-only
        ;; conditions this tree must clear before the operator maps it, from
        ;; the parts above; the walked directory is the physical base every
        ;; path is resolved against.
        preflight              (enrollment-preflight {:db           db-now
                                                      :base         walk-root
                                                      :attribution  attribution
                                                      :identity-rows (file-identity-rows walk-root)
                                                      :ent-groups   ent-groups
                                                      :collisions   substrate-collisions
                                                      :parse-failed parse-failed
                                                      :all-entities all-entities
                                                      :fs-paths     fs-paths
                                                      :sub-paths    sub-paths
                                                      :exclude      exclude})
        t-end                  (System/currentTimeMillis)
        report
        {:summary {:fs-file-count                 (count fs-specs)
                   :substrate-entity-count        (count entities)
                   :missing-from-substrate-count  (count missing-from-substrate)
                   :missing-from-fs-count         (count missing-from-fs)
                   :missing-from-fs-policy-excluded-count (count policy-excluded)
                   :content-divergence-count      (count content-divergence)
                   :ref-slot-mismatch-count       (count ref-slot-mismatch)
                   :twin-count                    (count twins)
                   :substrate-rel-path-collision-count (count substrate-collisions)
                   :total-drift-count             (+ (count missing-from-substrate)
                                                      (count missing-from-fs)
                                                      (count content-divergence)
                                                      (count ref-slot-mismatch)
                                                      (count twins)
                                                      (count substrate-collisions))
                   :audit-duration-ms             (- t-end t-start)
                   :audit-instant                 (java.util.Date.)
                   :corpus-root                   (str from)
                   :walk-root                     (str walk-root)
                   :root-mode                     root-mode
                   :substrate-entity-count-store-wide (count all-entities)
                   :root-attribution              (select-keys attribution [:kind :root :project-key :limitation])
                   :fs-parse-failed-count         (count parse-failed)
                   :enrollment-preflight-unresolved-count (:unresolved-count preflight)
                   :enrollment-preflight-error-count      (:error-count preflight)
                   :enrollment-preflight-clear?           (:clear-for-enrollment? preflight)}
         :missing-from-substrate missing-from-substrate
         :missing-from-fs        missing-from-fs
         :missing-from-fs-policy-excluded policy-excluded
         :content-divergence     (mapv #(select-keys % [:rel-path :entity-ident
                                                         :diff-summary :differing-slots])
                                       content-divergence)
         :ref-slot-mismatch      (mapv #(select-keys % [:rel-path :entity-ident :ref-diffs])
                                       ref-slot-mismatch)
         :twins                  twins
         :substrate-rel-path-collisions substrate-collisions
         :fs-parse-failed        parse-failed
         :enrollment-preflight   preflight}]
    (log/info :FS-DRIFT-AUDIT/COMPLETE
              (select-keys (:summary report)
                            [:fs-file-count :substrate-entity-count
                             :twin-count :total-drift-count :audit-duration-ms
                             :enrollment-preflight-unresolved-count
                             :enrollment-preflight-clear?]))
    report))

(comment
  ;; REPL usage:
  ;;   (require '[sandbar.audit.fs-substrate-drift :as fs-drift])
  ;;   (fs-drift/audit-all {:from "/Users/dan/claude/memory"})
  ;;   ;; …or equivalently (same effective walk root, η.5 (c)):
  ;;   (fs-drift/audit-all {:from "/Users/dan/claude"})
  ;;
  ;; Expected drift against the live corpus circa 2026-05-25:
  ;;   :missing-from-fs INCLUDES :workflow/session (Stage-D codec not yet landed)
  ;;   :ref-slot-mismatch INCLUDES the 4 :mm/Library memorials per Q.η.9 fault
  ;;
  ;; Expected against the live corpus circa 2026-07-21 (η.5 hardening probe):
  ;;   :twins ≈ 59 entries — the memory/memory/ orphan-twin tree, every one
  ;;   aliasing an existing real file (:shadows-existing-file? true); zero
  ;;   audit-results/ entries in :missing-from-substrate at ANY :from form.
  )
