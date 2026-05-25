(ns sandbar.audit.fs-substrate-drift
  "Audits drift between the FS-side `memory/` corpus and the substrate-side
   `:mm/Memory` entities.

   Per η.2 substrate execution per the wave-1 ratification ADR
   `:memory.decisions/iota_eta_q_checkpoint_wave_one_ratification_session_workflow_substrate_design_fs_audit_scope_finalized_2026_05_25`
   (Q.η.3 full-corpus single-shot audit / Q.η.6 operational+extensible
   report shape / Q.η.7 MCP-first not CLI-only).

   ## Drift categories (4)

   - `:missing-from-substrate` — FS file exists; no `:mm/Memory` entity at the
     corresponding `:mm.memory/rel-path`.  Signal: reactive-projection sink
     missed it, OR FS edit since last load, OR boot-path FS-auto-import gap
     (Q.η.8).
   - `:missing-from-fs` — `:mm/Memory` entity exists; no FS file at its
     `:mm.memory/rel-path`.  Signal: reactive-projection sink wrote-but-deleted,
     OR codec emission Stage-D not yet landed (e.g., `:mm/Workflow` doesn't
     project today — `:workflow/session` is the canonical example).
   - `:content-divergence` — Both exist; markdown parse of FS file's
     `:mm.memory/body-raw` differs from substrate entity's `:mm.memory/body-raw`.
     Signal: reactive-projection sink crashed mid-write, OR external FS edit
     since last reactive sync.
   - `:ref-slot-mismatch` — FS frontmatter has refs (cites / related /
     composes-with / etc.) that don't match substrate ref slots.  Signal:
     the ref-slot-writes-rejected fault per
     `:memory.observations/sandbar_substrate_ref_slot_writes_rejected_during_library_memorial_batch_2026_05_23`
     (Q.η.9 root-cause investigation in η.4; THIS verb just SURFACES the drift).

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
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.projection :as pg]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; FS side — parse via sandbar.projection/ingest-graph (dry-run; returns specs)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fs-entity-specs
  "Walk `from-dir` recursively via `sandbar.projection/ingest-graph`; return
   only the TOP-LEVEL `:mm/Memory` entity-specs (not the per-section sub-specs).
   Each spec carries `:mm.memory/rel-path` + `:mm.memory/body-raw` + any
   frontmatter slot values the codec parsed out."
  [from-dir]
  (->> (pg/ingest-graph from-dir {})
       ;; ingest-graph returns a flat vec of entity-specs.  Memory-level entries
       ;; carry :mm.memory/rel-path; sub-section entries don't (they have their
       ;; own ident namespace).  Filter to memory-level only.
       (filter #(contains? % :mm.memory/rel-path))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Substrate side — Datalog enumeration of :mm/Memory entities with rel-path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- substrate-memory-entities
  "All `:mm/Memory` entities (incl. subclass instances) that carry a
   `:mm.memory/rel-path`.  Returns a vec of `{:db/id, :entity-ident,
   :rel-path, :entity}` — the `:entity` slot is the live Datomic
   entity-map for downstream slot-by-slot comparison."
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

(defn- compare-fs-to-entity
  "Compare an FS-side entity-spec against the substrate entity at the same
   rel-path.  Emits a map with `:content-diff?` / `:ref-diff?` flags +
   detail.  Returns nil when fully in-sync."
  [fs-spec substrate-row]
  (let [substrate-entity (:entity substrate-row)
        rel-path         (:rel-path substrate-row)
        entity-ident     (:entity-ident substrate-row)
        ;; Body-raw comparison (after trim for trailing-newline neutrality)
        fs-body          (some-> (:mm.memory/body-raw fs-spec) str/trim-newline)
        sub-body         (some-> (:mm.memory/body-raw substrate-entity) str/trim-newline)
        body-diff?       (and (or fs-body sub-body) (not= fs-body sub-body))
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
                                  (count (or fs-body "")) (count (or sub-body ""))))
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

   Returns a structured drift report per the η.2 ADR contract:

     {:summary {:fs-file-count, :substrate-entity-count, …,
                :total-drift-count, :audit-duration-ms, :audit-instant}
      :missing-from-substrate [<rel-path-str> ...]
      :missing-from-fs        [<entity-ident> ...]
      :content-divergence     [{:rel-path, :entity-ident, :diff-summary,
                                :differing-slots} ...]
      :ref-slot-mismatch      [{:rel-path, :entity-ident, :ref-diffs {<attr>
                                {:fs-refs, :substrate-refs,
                                 :missing-in-substrate, :missing-in-fs}}} ...]}"
  [{:keys [from] :as _opts}]
  (when (str/blank? (str from))
    (throw (ex-info "sandbar.audit.fs-substrate-drift/audit-all requires :from (corpus root directory)"
                    {:reason :missing-from-arg})))
  (let [t-start              (System/currentTimeMillis)
        _                    (log/info :FS-DRIFT-AUDIT/START {:from from})
        fs-specs             (fs-entity-specs from)
        entities             (substrate-memory-entities)
        fs-by-path           (into {} (map (juxt :mm.memory/rel-path identity)) fs-specs)
        ent-by-path          (into {} (map (juxt :rel-path identity)) entities)
        fs-paths             (set (keys fs-by-path))
        sub-paths            (set (keys ent-by-path))
        missing-from-substrate (vec (sort (set/difference fs-paths sub-paths)))
        missing-from-fs        (vec (sort (map :entity-ident
                                               (vals (select-keys ent-by-path
                                                                   (set/difference sub-paths fs-paths))))))
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
                   :content-divergence-count      (count content-divergence)
                   :ref-slot-mismatch-count       (count ref-slot-mismatch)
                   :total-drift-count             (+ (count missing-from-substrate)
                                                      (count missing-from-fs)
                                                      (count content-divergence)
                                                      (count ref-slot-mismatch))
                   :audit-duration-ms             (- t-end t-start)
                   :audit-instant                 (java.util.Date.)
                   :corpus-root                   (str from)}
         :missing-from-substrate missing-from-substrate
         :missing-from-fs        missing-from-fs
         :content-divergence     (mapv #(select-keys % [:rel-path :entity-ident
                                                         :diff-summary :differing-slots])
                                       content-divergence)
         :ref-slot-mismatch      (mapv #(select-keys % [:rel-path :entity-ident :ref-diffs])
                                       ref-slot-mismatch)}]
    (log/info :FS-DRIFT-AUDIT/COMPLETE
              (select-keys (:summary report)
                            [:fs-file-count :substrate-entity-count
                             :total-drift-count :audit-duration-ms]))
    report))

(comment
  ;; REPL usage:
  ;;   (require '[sandbar.audit.fs-substrate-drift :as fs-drift])
  ;;   (fs-drift/audit-all {:from "/Users/dan/claude/memory"})
  ;;
  ;; Expected drift against the live corpus circa 2026-05-25:
  ;;   :missing-from-fs INCLUDES :workflow/session (Stage-D codec not yet landed)
  ;;   :ref-slot-mismatch INCLUDES the 4 :mm/Library memorials per Q.η.9 fault
  )
