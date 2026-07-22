(ns sandbar.audit.fs-substrate-drift
  "Audits drift between the FS-side `memory/` corpus and the substrate-side
   `:mm/Memory` entities.

   Per η.2 substrate execution per the wave-1 ratification ADR
   `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_session_workflow_substrate_design_fs_audit_scope_finalized_2026_05_25`
   (Q.η.3 full-corpus single-shot audit / Q.η.6 operational+extensible
   report shape / Q.η.7 MCP-first not CLI-only).

   ## Drift categories (5)

   - `:missing-from-substrate` — FS file exists; no `:mm/Memory` entity at the
     corresponding `:mm.memory/rel-path`.  Signal: reactive-projection sink
     missed it, OR FS edit since last load, OR boot-path FS-auto-import gap
     (Q.η.8).
   - `:missing-from-fs` — `:mm/Memory` entity exists; no FS file at its
     `:mm.memory/rel-path`.  Signal: reactive-projection sink wrote-but-deleted,
     OR codec emission Stage-D not yet landed (e.g., `:mm/Workflow` doesn't
     project today — `:workflow/session` is the canonical example).
     η.5 hardening (c): CLASS-SCOPED BY MEMORIAL POLICY — only entities whose
     class satisfies `dt/corpus-document-class?` (effective `:dt/memorial-policy
     :first-class` corpus-document types) can be *missing* from FS; entities
     that carry a rel-path but whose class the projection policy never emits
     (`:db-only` / `:inline` / runtime-behavioral kin) are surfaced separately
     under `:missing-from-fs-policy-excluded`, not counted as drift.
   - `:content-divergence` — Both exist; markdown parse of FS file's
     `:mm.memory/body-raw` differs from substrate entity's `:mm.memory/body-raw`.
     Signal: reactive-projection sink crashed mid-write, OR external FS edit
     since last reactive sync.  η.5 hardening (b): nil / empty / whitespace-only
     bodies are normalized to \"\" on BOTH sides before comparing — absent-slot
     vs empty-string encodings of 'no body' are NOT divergence (46/55 live
     entries at the 2026-07-21 probe were `0 chars vs 0 chars` noise).
   - `:ref-slot-mismatch` — FS frontmatter has refs (cites / related /
     composes-with / etc.) that don't match substrate ref slots.  Signal:
     the ref-slot-writes-rejected fault per
     `:memory.observations/sandbar_substrate_ref_slot_writes_rejected_during_library_memorial_batch_2026_05_23`
     (Q.η.9 root-cause investigation in η.4; THIS verb just SURFACES the drift).
   - `:twins` — η.5 hardening (a): a file whose WALK-derived rel-path differs
     from the STORED rel-path the codec would mint for it
     (`md/walk-rel->stored-rel-path` non-identity — i.e. a `memory/` corpus-
     anchor re-entry subtree, the `memory/memory/` orphan-twin shape).  Twin
     specs carry the SAME stored rel-path as the real file they alias, so
     pre-hardening the spec-by-path index silently collapsed the pair
     (last-write-wins): zero signal, and the twin's stale content could mask
     or fabricate `:content-divergence` for the REAL file.  Twins are now
     excluded from the parse walk (`:skip-rel-prefixes`) and reported as
     first-class drift entries
     `{:walk-rel-path :stored-rel-path :shadows-existing-file?}`.

   ## FS walk scoping (η.5 hardening (c))

   The FS walk is anchored at the memory corpus tree, mirroring the reactive
   sink's write containment (`sandbar.reactive.sinks/corpus-memory-root` —
   `<corpus-root>/memory` is the ONLY subtree the sink writes, so only it can
   correspond to substrate entities).  `resolve-walk-root` resolves the
   operator's `:from` to `{:walk-root :root-mode}` (`:memory-root` /
   `:corpus-root-scoped` / `:as-given`), surfaced in `:summary` — a repo-root
   `:from` no longer floods `:missing-from-substrate` with audit-results/ +
   doc/ non-memorial trees (~553 spurious entries measured 2026-07-21).

   Additionally the substrate-side index surfaces `:substrate-rel-path-collisions`
   — two entities whose rel-paths NORMALIZE to the same comparison key (the
   DB-side twin shape); pre-hardening the second entity silently vanished from
   every category.  Collisions count toward `:total-drift-count`.

   ## Composition

   - Mirrors `sandbar.audit.tag` / `sandbar.shape.conformance-report` audit-verb
     patterns: single public `audit-all` fn returning structured report.
   - Reuses `sandbar.projection/ingest-graph` (FS walker that parses
     markdown → entity-specs; dry-run mode returns specs without persisting).
   - Reuses the Datomic substrate via `sandbar.db.datomic/db`.

   ## See also

   - η plan: `:memory.plans/fs_corpus_audit_round_trip_fidelity_…_eta_sub_arc_2026_05_25`
   - Wave-1 ratification ADR (the design contract): cited above.
   - κ pattern catalog (compositions): P10 + P12 + P18 per the wave-1 ADR §2.
   - Restore-from-Git ADR (bijection-foundation reference for η.8):
     `:memory.decisions/restore_sandbar_from_durable_git_export_at_memory_open_never_rely_on_db_durability_2026_05_12`"
  (:require [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]
            [sandbar.projection :as pg]))

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
  "Enumerate ORPHAN-TWIN files under `walk-root` (η.5 hardening (a)) — files
   whose walk-derived rel-path differs from the STORED rel-path the codec
   would mint (`md/walk-rel->stored-rel-path`), i.e. files under a `memory/`
   re-entry subtree of the corpus root: the `memory/memory/` junk-twin tree.

   Pre-hardening these were STRUCTURALLY INVISIBLE — a twin's parsed spec
   carries the same stored rel-path as the real file it aliases, so the
   `into {}` spec index silently collapsed the pair (last-write-wins, walk-
   order dependent): zero signal, plus the twin's stale content could mask
   or fabricate `:content-divergence` for the REAL file.  Measured
   2026-07-21: 59 junk twins under /Users/dan/claude/memory/memory, all 59
   aliasing an existing real file.

   Entry shape:
     {:walk-rel-path          <walk-relative path of the junk file>
      :stored-rel-path        <the rel-path its spec would alias>
      :shadows-existing-file? <does a real file exist at that rel-path?>}

   Uses the same enumeration surface as the parse walk
   (`pg/walk-markdown-rel-paths` — README.md/MEMORY.md skip-set included)
   so twin enumeration and parse exclusion see the same file universe."
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

(defn- fs-entity-specs
  "Walk `walk-root` recursively via `sandbar.projection/ingest-graph`; return
   only the TOP-LEVEL `:mm/Memory` entity-specs (not the per-section sub-specs).
   Each spec carries `:mm.memory/rel-path` + `:mm.memory/body-raw` + any
   frontmatter slot values the codec parsed out.

   The orphan-twin subtree (`+twin-skip-rel-prefixes+`) is excluded at
   enumeration time so a twin's spec can never alias — and silently
   overwrite — the real file's spec in the by-path index (η.5 (a));
   twins surface via `twin-file-entries` instead."
  [walk-root]
  (->> (pg/ingest-graph walk-root {:skip-rel-prefixes +twin-skip-rel-prefixes+})
       ;; ingest-graph returns a flat vec of entity-specs.  Memory-level entries
       ;; carry :mm.memory/rel-path; sub-section entries don't (they have their
       ;; own ident namespace).  Filter to memory-level only.
       (filter #(contains? % :mm.memory/rel-path))))

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

(defn audit-all
  "Run the full FS↔substrate drift audit.

   Required opt: `:from` — the corpus-root directory path (matching the
   `sandbar.project.import` MCP verb convention; the operator is responsible
   for passing the path where the live substrate's corpus was loaded from).
   Both the repo-root form (`…/claude`) and the memory-root form
   (`…/claude/memory`) resolve to the SAME effective walk root per
   `resolve-walk-root` (η.5 (c)); the resolution is surfaced in `:summary`
   as `:walk-root` + `:root-mode`.

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
                              [{:rel-path, :entity-idents} ...]}

   `:total-drift-count` = missing-from-substrate + missing-from-fs +
   content-divergence + ref-slot-mismatch + twins + substrate-rel-path-
   collisions.  Policy-excluded entries are NOT drift (the projection
   policy says those classes never emit FS files) — they are surfaced for
   visibility only."
  [{:keys [from] :as _opts}]
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
        fs-specs             (fs-entity-specs walk-root)
        entities             (substrate-memory-entities)
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
                   :root-mode                     root-mode}
         :missing-from-substrate missing-from-substrate
         :missing-from-fs        missing-from-fs
         :missing-from-fs-policy-excluded policy-excluded
         :content-divergence     (mapv #(select-keys % [:rel-path :entity-ident
                                                         :diff-summary :differing-slots])
                                       content-divergence)
         :ref-slot-mismatch      (mapv #(select-keys % [:rel-path :entity-ident :ref-diffs])
                                       ref-slot-mismatch)
         :twins                  twins
         :substrate-rel-path-collisions substrate-collisions}]
    (log/info :FS-DRIFT-AUDIT/COMPLETE
              (select-keys (:summary report)
                            [:fs-file-count :substrate-entity-count
                             :twin-count :total-drift-count :audit-duration-ms]))
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
