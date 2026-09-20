(ns sandbar.projection
  "Bidirectional projection between Sandbar's in-Datomic entity state
   and a filesystem-organized native-representation hierarchy per
   decisions/sandbar_project_graph_boundary_layer_primitive_per_anderson_de_setf_resource_2026_05_12.md.

   ## Anderson lineage

   The shape borrows from James Anderson's
   `de.setf.rdf:project-graph` (de.setf.resource; Datagraph/Dydra-era
   CL CLOS-metaclass framework) — *transparent bidirectional
   projection between CLOS models and linked data repositories*.
   Sandbar inherits the architectural shape; the new contribution is
   filesystem-organized native-representation hierarchy as the
   projection target (markdown for memory-corpus consumer; TTL for
   RDF consumer; etc.).

   ## Two operations

   `project-graph`  — entities → filesystem hierarchy of native-format
                      files (e.g., markdown for `:dt/native-codec
                      :markdown` classes).  Each entity gets a path
                      derived from a consumer-specified `hierarchy-fn`
                      + emitted via the class's native codec.

   `ingest-graph`   — filesystem hierarchy → entities.  Walks the
                      directory; for each file, parses via the
                      mime-type-matched codec or class-derived codec;
                      returns a coll of entity-spec maps.

   Round-trip identity: `ingest-graph(project-graph(entities)) =
   entities` for the entities the consumer's hierarchy-fn can
   round-trip lossless.

   ## Stage D scope

   Minimum-viable per
   plans/sandbar_codec_layer_arc_2026-05-12.md Stage D:
     - mm/Memory entities project to single markdown files per
       :mm.memory/rel-path
     - Sections embedded inside their host mm/Memory's file (heading
       structure) — sections do NOT get their own filesystem files
     - Default hierarchy-fn: read `:mm.memory/rel-path` directly
     - Idempotent: project twice → same files; ingest twice → same
       entity-specs
     - Codec resolution via class's :dt/native-codec (default
       :markdown for mm/Memory)

   Deferred (later Stage D substages):
     - Metadata sidecars (workflows / history / provenance as optional
       sidecar files)
     - Filter mechanism (project subset by class/attribute/predicate)
     - Cross-class hierarchy-fns (multiple classes interleaving in
       the same directory tree)

   ## Layer-targeting

   Operates over entity-spec MAPS in-memory; never touches Datomic
   directly.  The DB-aware variant (lifting persisted Datomic
   entities into entity-spec maps) lives at Stage F (dt/* + MCP
   integration)."
  (:require [clojure.java.io        :as io]
            [clojure.set            :as set]
            [clojure.string         :as str]
            [clojure.tools.logging  :as log]
            [sandbar.codec          :as codec]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype    :as dt]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Realize-and-emit primitive — shared between resources/read and
;; project.export per codex MUST-FIX #4 + Q2=A Dan-decision
;;
;; The shape: realize the entity's related-entity bundle (via a
;; class-specific walker) + emit the bundle as a single
;; native-representation document via the class's codec.  The same
;; shape was previously duplicated in `sandbar.mcp.resources/render-entity-content`
;; (which inlined the realize + emit), and in `sandbar.projection/project-graph`
;; (which inlined the group-by-memory + emit-document call).  Lifting
;; here makes both callers share the substrate primitive.
;;
;; α-scope per Q2=A: walker is class-keyed (mm/Memory + mm/Section
;; handled).  Per-class walker declaration via a `:dt/walker` schema
;; attribute is β-scope (post-0.1.0).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mm-walker
  "Walker for `dt/realize-with` over mm/Memory + mm/Section trees.

   From mm/Memory: walks to :mm.memory/first-section and to every
   direct child (the reverse `:mm.section/_parent` index).  From
   mm/Section: walks to :mm.section/next-sibling and to every child.
   Order is the emitter's business (`md/ordered-children`); this walker
   only guarantees that every section of the tree is realized (REP-01,
   D7 2026-09-20).  Returns ALREADY-DEDUPLICATED related entities.

   Lifted from sandbar.mcp.resources/mm-walker so both project.export
   and resources/read share the same walk."
  [entity]
  ;; Subsumption-aware: dispatch on Memory-class-or-subclass / Section-class-or-subclass
  ;; per post-2026-05-21 codec slot-inheritance fix.
  (let [t (:dt/type entity)]
    (cond
      ;; REP-01 (Astra's review; D7 2026-09-20): every child, through the
      ;; reverse attribute Datomic actually names (`:mm.section/_parent` — the
      ;; former `:_mm.section/parent` was no attribute at all, so no nested
      ;; section was ever realized); the emitter orders the children.
      ;; The reverse index already holds the first section, so it is returned
      ;; alone (realize-with wants a deduplicated frontier); the first-section
      ;; link is the fallback for a record whose sections lost their parent.
      (dt/type-isa? :mm/Memory t)
      (or (seq (:mm.section/_parent entity))
          (when-let [first-sec (:mm.memory/first-section entity)]
            [first-sec]))

      (dt/type-isa? :mm/Section t)
      (concat (when-let [next-sib (:mm.section/next-sibling entity)]
                [next-sib])
              (:mm.section/_parent entity))

      :else nil)))

(defn walker-for-class
  "Return the realize-with walker fn for the given class-ident.
   α-scope: hardcoded dispatch for mm/Memory + mm/Section.  Other
   classes return nil (no bundle realization needed; emit the entity
   alone)."
  [class-ident]
  ;; Subsumption-aware: Memory-subclasses (:mm/Decision, :mm/Plan, etc.) share mm-walker.
  (cond
    (dt/type-isa? :mm/Memory class-ident)  mm-walker
    (dt/type-isa? :mm/Section class-ident) mm-walker
    :else nil))

(defn realize-and-emit-entity
  "Realize the bundle of related entities for `entity` (via the
   class's walker) and emit the bundle as a single
   native-representation document via the class's codec.

   For mm/Memory: walks the section tree via `mm-walker`; calls
   `sandbar.codec.markdown/emit-document` on the realized vector.

   For classes with a `:dt/native-codec` but no walker: emits the
   entity alone via the codec mediator.

   For classes without `:dt/native-codec`: returns nil — caller
   decides the fallback (e.g., EDN pr-str).

   Codex MUST-FIX #4 — lift the realize-tree-then-emit shape into
   a substrate primitive so resources/read and project.export share
   one implementation."
  ([entity] (realize-and-emit-entity entity {}))
  ([entity opts]
   (let [class-ident   (:dt/type entity)
         native-codec  (dt/native-codec-of-class class-ident)
         walker        (walker-for-class class-ident)]
     (cond
       (nil? native-codec)
       nil

       walker
       (let [entity-vec        (dt/realize-with entity walker)
             sections-present? (some #(not= class-ident (:dt/type %)) (rest entity-vec))]
         (if sections-present?
           (md/emit-document entity-vec)
           ;; Belt-and-braces strip parity: the raw codec `emit` excludes
           ;; :db/* + :mm.memory/rel-path but NOT :mm.memory/first-section,
           ;; so a section-less memory whose slot still carries a first-
           ;; section ref would leak `first-section:` into frontmatter.
           ;; `md/emit-document` strips the derived attrs; mirror it here so
           ;; the two notions can never diverge.  Per observations/live_sink_-
           ;; emits_derived_first_section_for_subclass_memorials_regenerating_-
           ;; debris_130_files_2026_07_10.
           (codec/emit (md/strip-derived-memory-attrs (first entity-vec))
                       (assoc opts :format native-codec))))

       :else
       ;; No walker (native-codec-bearing non-memory class): same strip
       ;; parity as the section-less branch above — defense-in-depth so no
       ;; emit path reachable from here can leak a derived memory attr.
       (codec/emit (md/strip-derived-memory-attrs entity)
                   (assoc opts :format native-codec))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pre-write registry guard — refuse frontmatter-key strips at the write
;; boundary (S2 substrate-fidelity, X-minus 0.2.0)
;;
;; The DB→FS emit path can silently degrade: an unresolvable carrier
;; ident, a malformed EDN payload, or a post-tx map lacking the
;; :mm.memory/frontmatter key all read-back as `carrier nil` and the
;; codec falls through to the legacy lossy emit — stripping every extras
;; key (`at-startup:`, `one-line:`, the long tail) with NO log line
;; (`resolve-carrier-value` / `read-carrier-extra` are never-throw by
;; contract, sandbar.codec.markdown:1256,1274).  The codec is model-layer
;; (no target path, legitimately emits carrier-less DB-first entities), so
;; the only place "would this write strip a registry key from an existing
;; file?" is answerable is the write call-site — where old bytes and new
;; bytes coexist.
;;
;; ONE shared primitive, TWO call-sites (IP-3): the reactive sink's
;; `atomic-write!` (sandbar.reactive.sinks) AND the batch `project-graph`
;; loop below.  A single definition of "registry-critical" so the reactive
;; and batch notions can never diverge (mirrors the ceremony-#4 FIX-1
;; `walk-rel->stored-rel-path` single-definition precedent).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn registry-critical-keys
  "The set of frontmatter wire-keys (strings) whose loss on a write is
   FATAL (refuse the write) rather than merely warn-logged.

   Sourced from the `SANDBAR_REGISTRY_CRITICAL_KEYS` env-var (comma-
   separated wire-keys); default `#{\"at-startup\" \"one-line\"}` — the
   discipline-visibility registry pair, which both ride the same extras
   carrier and are both load-bearing for the startup-banner tiering
   (regen captures `one-line:` into the cache; `at-startup:` drives the
   tier).  A carrier-degradation event strips both together, so the
   marginal cost of including `one-line` is ~zero.

   Read via a defn (not a def) so it is hot-load / test overridable,
   consistent with the `SANDBAR_CORPUS_ROOT` env-read convention in
   `sandbar.reactive.sinks/corpus-root`.

   Env-parse edge (empty ⇒ empty set, NOT `#{\"\"}`): a set/present-but-
   blank `SANDBAR_REGISTRY_CRITICAL_KEYS=` parses to the EMPTY set, which
   disables the fatal-refusal tier entirely (every drop becomes warn-only).

   NB the baked default embeds minimal consumer knowledge in the substrate
   (tension with the no-hardcoded-consumer-class-knowledge discipline,
   already honored twice on this codepath) — arbitrated narrow (AP-S2-3):
   the carrier delivers any-key survival; this guard is the backstop for
   the one named catastrophic consumer, and warn-log frequency is the
   evidence for widening after telemetry lands."
  []
  (if-let [env (System/getenv "SANDBAR_REGISTRY_CRITICAL_KEYS")]
    (into #{} (->> (str/split env #",")
                   (map str/trim)
                   (remove str/blank?)))
    #{"at-startup" "one-line"}))

(defn- frontmatter-key-set
  "Parse `source`'s frontmatter block and return the set of top-level
   wire-keys (strings) it declares.  Returns `nil` when `source` has no
   frontmatter block (an unterminated or absent `---` fence).

   Uses the already-public `md/split-frontmatter` + `md/parse-frontmatter-text`
   (cheap; no full document parse).  `parse-frontmatter-text` returns
   KEYWORD keys, so each is normalized to its wire-key string via `name`."
  [source]
  (let [fm (first (md/split-frontmatter source))]
    (when fm
      (->> (md/parse-frontmatter-text fm)
           keys
           (map name)
           set))))

(defn guard-registry-critical-write!
  "Pre-write fidelity guard for `target-path`.  Compares the frontmatter
   key set of the EXISTING on-disk file against `new-content`'s and:

   1. no file at `target-path` → no-op (fresh writes are never refused);
   2. on-disk file has no frontmatter block → no-op;
   3. `dropped` = on-disk wire-keys absent from `new-content`;
   4. if `dropped` intersects `(registry-critical-keys)` → THROW an
      ex-info marked `:sandbar/error :registry-strip-refusal` (the sink's
      companion catch rethrows this marker so it counts in
      `sink-error-total`; the batch path lets it abort the export);
   5. else if `dropped` is non-empty → warn-log
      `:REACTIVE/frontmatter-keys-dropped` and proceed (closes the
      silent-degradation observability gap without fail-closing the whole
      projection surface).

   Returns nil (proceed) or throws (refuse).  Called BEFORE the spit at
   both write call-sites, so a refusal leaves the target — and any `.tmp`
   sibling — untouched."
  [target-path new-content]
  (let [target (io/file target-path)]
    (when (.exists target)
      (when-let [disk-keys (frontmatter-key-set (slurp target))]
        (let [new-keys (or (frontmatter-key-set new-content) #{})
              dropped  (set/difference disk-keys new-keys)
              critical (set/intersection dropped (registry-critical-keys))]
          (cond
            (seq critical)
            (throw (ex-info (str "registry-critical frontmatter key would be stripped "
                                 "by this write; refusing")
                            {:sandbar/error :registry-strip-refusal
                             :target-path   target-path
                             :missing-keys  (vec (sort critical))
                             :on-disk-keys  (vec (sort disk-keys))
                             :emitted-keys  (vec (sort new-keys))}))

            (seq dropped)
            (log/warn :REACTIVE/frontmatter-keys-dropped
                      {:target-path  target-path
                       :dropped-keys (vec (sort dropped))})))))
    nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Hierarchy-fn — entity → rel-path
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn default-hierarchy-fn
  "Default entity → rel-path mapping for mm/Memory entities.  Reads
   `:mm.memory/rel-path` directly (the slot already carries the
   filesystem path).  Returns nil for entities not classified as
   mm/Memory or lacking :mm.memory/rel-path."
  [entity]
  (when (and (dt/type-isa? :mm/Memory (:dt/type entity))
             (:mm.memory/rel-path entity))
    (:mm.memory/rel-path entity)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Filter mechanism (Stage G Signal 2 — essential for hybrid-backend
;; experimentation per
;; ideas/sandbar_project_export_filtering_for_hybrid_backend_experimentation_2026_05_13.md)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn entity-passes-filter?
  "Test an entity-spec map against a filter spec.  Filter dimensions:
     :class           - single class-ident; entity's :dt/type must match
     :classes         - set of class-idents; entity's :dt/type must be a member
     :pred            - arbitrary predicate fn (entity → boolean)
     :tree-filter     - rel-path prefix; entity must carry :mm.memory/rel-path
                        starting with this prefix (applies to mm/Memory only;
                        non-mm/Memory entities pass through unchanged)

   Multiple keys compose via AND.  An empty / nil filter spec passes all
   entities."
  [entity filter-spec]
  ;; Class matching walks :dt/subclass-of so :class :mm/Memory matches
  ;; :mm/Decision instances (subsumption per RDFS rdfs9).  Substrate-pure;
  ;; uses dt/subclass-of?.  Post-2026-05-21 codec slot-inheritance fix.
  (let [{:keys [class classes pred tree-filter]} filter-spec
        entity-type (:dt/type entity)
        class-matches? (fn [c]
                         (or (= c entity-type)
                             (try (dt/subclass-of? c entity-type)
                                  (catch Exception _ false))))]
    (boolean
      (and (or (nil? class)        (class-matches? class))
           (or (nil? classes)      (some class-matches? classes))
           (or (nil? pred)         (pred entity))
           (or (nil? tree-filter)
               ;; tree-filter applies to memory-shape entities (memory or subclasses);
               ;; non-memory-shape entities (e.g. :mm/Section, :mm/Tag) pass through.
               (not (class-matches? :mm/Memory))
               (and (:mm.memory/rel-path entity)
                    (str/starts-with? (:mm.memory/rel-path entity)
                                      tree-filter)))))))

(defn apply-filter
  "Filter a coll of entity-spec maps via `filter-spec`.  Returns a
   vector preserving original order."
  [entities filter-spec]
  (if (empty? filter-spec)
    (vec entities)
    (vec (filter #(entity-passes-filter? % filter-spec) entities))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; project-graph — entities → filesystem
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- group-entities-by-memory
  "Given a coll of entity-spec maps (mm/Memory + mm/Section refs in
   the same coll), group sections under their host memories.

   Returns a vector of `{:memory <mm/Memory entity-spec>
                          :sections [<mm/Section entity-specs in document order>...]}`
   maps.  Memory-only inputs (no sections) yield `{:memory ... :sections []}`.

   The sections are the memory's FULL tree via `md/section-tree` (REP-01 of
   Astra's review, D7 2026-09-20) — every section whose parent chain reaches
   the memory, in document order — where the previous grouping followed the
   top `:first-section` / `:next-sibling` chain only and dropped every
   nested section from the export."
  [entities]
  (let [by-type        (group-by :dt/type entities)
        ;; Subsumption-aware: gather any entity whose :dt/type is :mm/Memory or
        ;; a subclass thereof (e.g., :mm/Decision via :dt/subclass-of :mm/Memory).
        memories       (vec (mapcat (fn [[t es]]
                                      (when (dt/type-isa? :mm/Memory t) es))
                                    by-type))
        all-sections   (vec (mapcat (fn [[t es]]
                                      (when (dt/type-isa? :mm/Section t) es))
                                    by-type))]
    (for [memory memories]
      {:memory   memory
       :sections (md/section-tree all-sections (:db/ident memory) (:mm.memory/first-section memory))})))

(defn- source-descriptor
  "A slim source-entity descriptor of a projected entity — the ADOPTED 3-key
  shape (W1 defaults 2026-07-21): `:db/ident` + `:dt/type` +
  `:mm.memory/owning-project`.  Carried on every `project-graph` written row
  under `:entity` so the W1.E manifest gate (`sandbar.project.provenance/
  verify-written-against-route!`) can route the ACTUAL projected entity instead
  of re-resolving the written rel-path — closing the collision fail-open where
  a rel-path shared by a `:public` and a `:private` entity could resolve to the
  public twin and admit the private twin's file.

  TRUST CONTRACT (r4 CODEX-A MEDIUM fix): the gate consumes ONLY `:db/ident`,
  as an IDENTITY CLAIM it verifies against the DB (ident must resolve to a
  live entity whose own rel-path equals the row's) before routing the LIVE
  entity — the two routing slots here are NOT trusted routing inputs (a forged
  or stale `:mm.memory/owning-project` is inert); they ride for row
  self-description/debuggability and shape stability.  A `select-keys` (not
  the whole entity) so no body/content rides the transient row."
  [entity]
  (select-keys entity [:dt/type :mm.memory/owning-project :db/ident]))

(defn project-graph
  "Project a coll of entity-spec maps onto a filesystem hierarchy.

   Inputs:
     entities — coll of entity-spec maps (mm/Memory + mm/Section
                refs; sections are emitted inside their host memory's
                markdown file via the section-tree codec).
     opts     — map; supported keys:
       :to            — REQUIRED; output directory (java.io.File or string).
                        Created if absent.
       :hierarchy-fn  — optional; entity → rel-path-string.  Defaults
                        to default-hierarchy-fn (reads
                        :mm.memory/rel-path).
       :filter        — optional filter spec per `entity-passes-filter?`;
                        when supplied, only matching entities project to
                        disk.  Sections under a matching mm/Memory always
                        accompany the memory regardless of filter.

   Returns: vector of `{:rel-path \"...\" :written true}` records.  The
   vector's METADATA carries `{:failed [{:rel-path :written false :error
   :entity} …]}` — the units whose emit or write failed (D7, 2026-09-20);
   the export continues past them, so every unit's fate is knowable.

   Idempotence: re-projecting the same entities to the same dir yields
   byte-identical files (per the markdown codec's normalization
   invariants in
   decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md §4)."
  [entities {:keys [to hierarchy-fn] filter-spec :filter
             :or   {hierarchy-fn default-hierarchy-fn}}]
  (when-not to
    (throw (ex-info "project-graph requires :to opt (output directory)" {})))
  (let [out-dir    (io/file to)
        ;; Filter applied at the memory level — sections under a matching
        ;; memory always travel with their host
        filtered (if (and filter-spec (seq filter-spec))
                   (let [memories       (clojure.core/filter (fn [e] (dt/type-isa? :mm/Memory (:dt/type e)))
                                                              entities)
                         pass-memories  (apply-filter memories filter-spec)
                         pass-mem-idents (set (map :db/ident pass-memories))
                         sections       (clojure.core/filter (fn [e] (dt/type-isa? :mm/Section (:dt/type e)))
                                                              entities)
                         section-by-ident (into {} (keep (fn [s] (when-let [i (:db/ident s)] [i s]))) sections)
                         ;; A section accompanies its memory when its parent CHAIN
                         ;; reaches a passing memory — not only when its immediate
                         ;; parent is one (REP-01, D7 2026-09-20: the old test kept
                         ;; top-level sections and dropped every nested one).
                         root-passes?   (fn [s]
                                          (loop [cur s seen #{}]
                                            (let [p (:mm.section/parent cur)]
                                              (cond
                                                (nil? p)                        false
                                                (contains? pass-mem-idents p)   true
                                                (contains? seen p)              false
                                                :else (if-let [ps (get section-by-ident p)]
                                                        (recur ps (conj seen p))
                                                        false)))))
                         pass-sections  (vec (clojure.core/filter root-passes? sections))]
                     (into pass-memories pass-sections))
                   entities)]
    (.mkdirs out-dir)
    ;; Every unit's fate is reported (D7, 2026-09-20): a unit whose emit or
    ;; write fails — a name over the filesystem's limit, an I/O error — is
    ;; collected under the result's `:failed` metadata and the export goes
    ;; on, so one bad name never aborts a corpus-wide projection (the dry run
    ;; against the live corpus stopped at file 66 of 2,700 on a 291-byte
    ;; name).  A registry-strip refusal is a fidelity refusal, not an I/O
    ;; failure: it still aborts the export, as before.
    (let [failed  (atom [])
          written (vec
                    (for [{:keys [memory sections]} (group-entities-by-memory filtered)
                          :let [rel-path (hierarchy-fn memory)]
                          :when rel-path
                          :let [record
                                (try
                                  (let [target-file (io/file out-dir rel-path)
                                        flat        (into [memory] sections)
                                        content     (md/emit-document flat)]
                                    (.mkdirs (.getParentFile target-file))
                                    ;; Pre-write registry guard (IP-3 call-site 2).  Inert for fresh
                                    ;; scratch/temp dirs (no existing file → no-op); engages only when
                                    ;; exporting over an existing corpus, where a degraded emit would
                                    ;; otherwise strip a registry-critical key in-place.  A refusal
                                    ;; ex-info aborts the export; the MCP handler reports it.
                                    (guard-registry-critical-write! (.getPath target-file) content)
                                    (spit target-file content)
                                    (log/debug :PROJECT-GRAPH/wrote {:rel-path rel-path})
                                    ;; `:entity` carries the SOURCE entity's identity claim so the W1.E
                                    ;; provenance manifest gate can verify it against the DB and route the
                                    ;; ACTUAL projected entity, never re-resolving the rel-path (which
                                    ;; fails open under a rel-path collision) and never trusting the
                                    ;; descriptor's own slots (r4).  Additive key; downstream consumers
                                    ;; read only :rel-path.
                                    {:rel-path rel-path :written true
                                     :entity   (source-descriptor memory)})
                                  (catch Throwable t
                                    (if (= :registry-strip-refusal (:sandbar/error (ex-data t)))
                                      (throw t)
                                      (do (log/warn t :PROJECT-GRAPH/write-failed
                                                    {:rel-path rel-path :error (.getMessage t)})
                                          (swap! failed conj {:rel-path rel-path :written false
                                                              :error    (.getMessage t)
                                                              :entity   (source-descriptor memory)})
                                          nil))))]
                          :when record]
                      record))]
      (with-meta written {:failed @failed}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ingest-graph — filesystem → entities

(def ^:const +default-skip-basenames+
  "Default skip-set for ingest-graph file enumeration.  Subtree-index +
  root-index filenames that conventionally are NOT memorials in markdown
  memory-model corpora:

  - README.md — subtree-index / human-readable directory pointer (any depth)
  - MEMORY.md — root-index of active arcs / curated entries (top-level)

  Per `etc/lib/memory.clj` corpus convention (`+skip-index-basenames+`
  + `+skip-top-files+`).  Surfacing in BM25F search results would pollute
  ranked output with non-memorial content (the corpus's parity probe at
  observations/sandbar_bm25f_parity_probe_5_divergences_2026_05_22.md
  D1+D2 surfaced README.md as top-ranked sandbar hit for `predicate
  vocabulary` query because this filter was missing substrate-side).

  Consumers can override via :skip-basenames opt to ingest-graph
  (empty set #{} disables skipping; their-own-set replaces these
  defaults)."
  #{"README.md" "MEMORY.md"})

(defn- walk-markdown-files
  "Walk a directory recursively; return a seq of rel-paths to .md files.
  Skips files whose basename is in `skip-basenames` (default:
  `+default-skip-basenames+` — README.md + MEMORY.md, the conventional
  subtree-index / root-index patterns).  When `skip-rel-prefixes` (a
  coll of walk-relative path prefixes, e.g. #{\"memory/\"}) is non-empty,
  rel-paths starting with any of those prefixes are excluded — the
  enumeration-level subtree exclusion the η.5 drift-audit hardening uses
  to keep the `memory/memory/` orphan-twin tree out of the parse set."
  ([^java.io.File root]
   (walk-markdown-files root +default-skip-basenames+))
  ([^java.io.File root skip-basenames]
   (walk-markdown-files root skip-basenames nil))
  ([^java.io.File root skip-basenames skip-rel-prefixes]
   (->> (file-seq root)
        (filter #(and (.isFile ^java.io.File %)
                      (str/ends-with? (.getName ^java.io.File %) ".md")
                      (not (contains? skip-basenames
                                      (.getName ^java.io.File %)))))
        (map (fn [^java.io.File f]
               (let [root-path   (.getCanonicalPath root)
                     file-path   (.getCanonicalPath f)
                     rel         (subs file-path (inc (count root-path)))]
                 rel)))
        (remove (fn [rel]
                  (boolean (some #(str/starts-with? rel %)
                                 skip-rel-prefixes)))))))

(defn walk-markdown-rel-paths
  "PUBLIC enumeration surface over the ingest walk — return a sorted vec
   of walk-relative rel-paths to `.md` files under `root` (a directory),
   honoring the same defaults as `ingest-graph`'s internal walk
   (`+default-skip-basenames+` — README.md + MEMORY.md skipped) so
   enumeration-only consumers (e.g. `sandbar.audit.fs-substrate-drift`'s
   orphan-twin detection) see exactly the file universe the parse walk
   sees, WITHOUT duplicating the walk logic.

   Opts:
     :skip-basenames    — set of basenames to exclude (default
                          `+default-skip-basenames+`; pass #{} to disable)
     :skip-rel-prefixes — coll of walk-relative prefixes to exclude
                          (default nil — nothing excluded)

   A non-directory `root` returns [] (the single-file ingest short-circuit
   has no walk to expose)."
  ([root] (walk-markdown-rel-paths root {}))
  ([root {:keys [skip-basenames skip-rel-prefixes]
          :or   {skip-basenames +default-skip-basenames+}}]
   (let [root-f (io/file root)]
     (if (.isDirectory root-f)
       (vec (sort (walk-markdown-files root-f skip-basenames skip-rel-prefixes)))
       []))))

(defn- filter-ingested-entities
  "Return `entities` reduced to the memories passing `filter-spec` plus
   their sections; sections whose host memory drops also drop (the
   consistency invariant `entity-passes-filter?` documents).  A nil/empty
   filter-spec returns `entities` unchanged.  Shared by both ingest paths
   (directory walk + single-file) so the filter semantics are identical."
  [entities filter-spec]
  (if (or (nil? filter-spec) (empty? filter-spec))
    entities
    ;; Set-membership on the passing idents (cached descendants-of lookup
    ;; per decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md)
    ;; then a single ordered pass that carries the host memory's include?
    ;; verdict onto its trailing sections.
    (let [memory-descendants (dt/descendants-of :mm/Memory)
          memories           (vec (clojure.core/filter
                                    #(contains? memory-descendants (:dt/type %))
                                    entities))
          pass-mem-idents    (set (map :db/ident (apply-filter memories filter-spec)))]
      (loop [out [] include? false [e & rst] entities]
        (cond
          (nil? e)
          out

          (dt/type-isa? :mm/Memory (:dt/type e))
          (let [inc? (contains? pass-mem-idents (:db/ident e))]
            (recur (cond-> out inc? (conj e)) inc? rst))

          :else
          (recur (cond-> out include? (conj e)) include? rst))))))

(defn- unit-entities
  "Apply `filter-spec` to ONE source unit's parsed entities.  A memory-rooted
   unit keeps the memory and its trailing sections when the memory passes
   (`filter-ingested-entities`); a unit rooted in any other class (a Tag
   document) passes or drops WHOLE on its root.  The filter decision is by
   source (REP-06, D6 2026-09-19) — never carried over from the previous
   file, as the flat walk once did.  A nil/empty filter-spec keeps everything."
  [entities filter-spec]
  (cond
    (or (nil? filter-spec) (empty? filter-spec))
    entities

    (dt/type-isa? :mm/Memory (:dt/type (first entities)))
    (filter-ingested-entities entities filter-spec)

    (entity-passes-filter? (first entities) filter-spec)
    entities

    :else
    []))

(defn- sha256-hex
  "The SHA-256 of `text` as lowercase hex — the source fingerprint a unit
   carries so a preview and its apply can be told apart (D7, 2026-09-20)."
  [^String text]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" %) (.digest md (.getBytes text "UTF-8"))))))

(defn- parse-unit
  "Parse ONE `.md` file into a SOURCE UNIT — `{:source :status :entities
   :class :ident :unknown-keys}`, plus `:error` when the parse threw.  The
   unit carries what the flat entity vector cannot (REP-06, D6 2026-09-19):
   which file each entity came from, which files failed to parse and why,
   and which front-matter keys the lenient parse could only carry.  Status
   `:parsed` (entities after the filter), `:parse-failed` (no entities;
   `:error` names the cause), `:filtered` (parsed, every entity dropped by
   the filter) or `:skipped` (not a `.md` file).  A parse failure is logged
   AND reported — it never aborts the walk, and it never reads as success.

   `source` is the rel-path handed to `md/parse-document` as the
   path-derivation source: the walk-relative path, or the BASENAME for a
   file the caller pointed at directly — a bare file carries no corpus
   anchor from which a subtree prefix could be recovered, and for the
   corpus ROOT files (MEMORY.md / README.md) the basename IS the corpus
   rel-path (bugs/project_import_cannot_select_root_files_memory_md_readme_-
   bare_ident_stubs_2026_07_02).  An explicitly-named file is ALWAYS
   parsed — the skip-set governs the walk, not a file the caller named."
  [^java.io.File file source filter-spec]
  (if-not (str/ends-with? (.getName file) ".md")
    {:source source :status :skipped :entities []}
    (let [text (slurp file)
          sha  (sha256-hex text)
          [parsed error] (try
                           [(vec (md/parse-document text source)) nil]
                           (catch Throwable ex
                             ;; Per-file parse failures don't abort the whole
                             ;; walk — e.g., a section-ident slug collision in
                             ;; ONE file shouldn't poison the entire corpus
                             ;; ingest.  Log, and REPORT through the unit.
                             (log/warn :SANDBAR/INGEST-PARSE-SKIP
                                       {:rel-path source
                                        :file     (.getPath file)
                                        :error    (.getMessage ex)})
                             [nil (or (.getMessage ex) (str (class ex)))]))]
      (if error
        {:source source :status :parse-failed :entities [] :error error :source-sha256 sha}
        (let [root (first parsed)
              kept (unit-entities parsed filter-spec)]
          {:source       source
           :source-sha256 sha
           :status       (if (and (seq parsed) (empty? kept)) :filtered :parsed)
           :entities     (vec kept)
           :class        (:dt/type root)
           :ident        (:db/ident root)
           :unknown-keys (md/unknown-frontmatter-keys root)})))))

(defn ingest-units
  "Walk `from-dir` (a directory, or ONE `.md` file) and return a vector of
   SOURCE UNITS, one per file in walk order — `parse-unit` gives the unit
   shape.  The unit is the import's transaction unit: `project.import`
   transacts each `:parsed` unit on its own, names each `:parse-failed`
   unit with its error, and counts the `:filtered` / `:skipped` units, so
   the report's totals reconcile with the files walked (REP-06 of Astra's
   representation review, folded into D6 2026-09-19).  `ingest-graph` is
   this walk flattened.

   `opts` as for `ingest-graph` — `:filter` (applied per unit AFTER the
   parse), `:skip-basenames`, `:skip-rel-prefixes`.  A `:tree-filter` that
   cannot match a file's STORED-form rel-path skips the parse (`:skipped`)
   — tested via `md/walk-rel->stored-rel-path` so this parse-skip fork and
   `entity-passes-filter?` (the stored-slot fork) compare the SAME target
   (bugs/project_import_tree_filter_double_fork_walk_rel_vs_stored_rel_-
   path_2026_07_02).  A single file is parsed under its basename, bypassing
   the walk and its skip-set (the MEMORY.md / README.md heal path).
   Throws ex-info when `from-dir` is neither a directory nor a file."
  ([from-dir] (ingest-units from-dir {}))
  ([from-dir {filter-spec       :filter
              skip-basenames    :skip-basenames
              skip-rel-prefixes :skip-rel-prefixes
              :or               {skip-basenames +default-skip-basenames+}}]
   (let [root (io/file from-dir)]
     (cond
       (.isFile root)
       [(parse-unit root (.getName root) filter-spec)]

       (not (.isDirectory root))
       (throw (ex-info "ingest-graph requires a directory or file input"
                       {:from-dir (str from-dir)}))

       :else
       (let [t-files-start (System/currentTimeMillis)
             files         (vec (walk-markdown-files root skip-basenames skip-rel-prefixes))
             t-files-end   (System/currentTimeMillis)
             _ (log/info :INGEST/FILES-WALKED
                         {:from-dir   (str from-dir)
                          :file-count (count files)
                          :ms         (- t-files-end t-files-start)})
             tree-filter   (:tree-filter filter-spec)
             processed     (atom 0)
             units         (mapv (fn [rel-path]
                                   (let [n (swap! processed inc)]
                                     (when (zero? (mod n 100))
                                       (log/info :INGEST/PARSE-PROGRESS
                                                 {:processed n :total (count files)
                                                  :ms (- (System/currentTimeMillis) t-files-end)})))
                                   (if (and (some? tree-filter)
                                            (not (str/starts-with?
                                                   (md/walk-rel->stored-rel-path rel-path)
                                                   tree-filter)))
                                     {:source rel-path :status :skipped :entities []}
                                     (parse-unit (io/file root rel-path) rel-path filter-spec)))
                                 files)
             by-status     (frequencies (map :status units))]
         (log/info :INGEST/PARSE-COMPLETE
                   {:files-walked      (count files)
                    :entities-produced (transduce (map (comp count :entities)) + 0 units)
                    :parse-failed      (get by-status :parse-failed 0)
                    :filtered          (get by-status :filtered 0)
                    :skipped           (get by-status :skipped 0)
                    :filter-active?    (boolean (and filter-spec (seq filter-spec)))
                    :ms                (- (System/currentTimeMillis) t-files-end)})
         ;; F12 LOUD-empty: a non-nil :tree-filter that selects NOTHING is the
         ;; double-fork's silent-zero symptom.  Warn rather than return an
         ;; entity-less walk mutely so a mis-anchored or mis-prefixed filter
         ;; is visible to the caller instead of masquerading as "nothing to
         ;; import" (bugs/project_import_tree_filter_double_fork_walk_rel_vs_-
         ;; stored_rel_path_2026_07_02 §Notes, F12 fail-silent lens).
         (when (and (some? tree-filter)
                    (pos? (count files))
                    (every? #(empty? (:entities %)) units))
           (log/warn :INGEST/TREE-FILTER-SELECTED-NONE
                     {:tree-filter  tree-filter
                      :files-walked (count files)
                      :from-dir     (str from-dir)
                      :hint "tree-filter matches the UNPREFIXED stored rel-path (e.g. \"decisions/\" not \"memory/decisions/\")"}))
         units)))))

(defn ingest-graph
  "Walk a filesystem hierarchy + return a flat coll of entity-spec maps —
   the `:entities` of `ingest-units` concatenated in walk order.  For each
   `.md` file, parses via sandbar.codec.markdown/parse-document using the
   file's rel-path as the path-derivation source.

   Inputs:
     from-dir — input directory OR a single `.md` file (java.io.File or
                string).  A directory is walked recursively (README.md +
                MEMORY.md skipped by default per `:skip-basenames`); a
                file is parsed directly with its basename as the rel-path,
                bypassing the walk + skip-set — the heal path for the
                corpus root files the walk cannot enumerate.
     opts     — map; supported keys:
       :filter — filter spec per `entity-passes-filter?`; applied
                 per source unit AFTER the parse.  Memories that fail
                 :class / :pred / :tree-filter drop; sections under
                 dropped memories drop too (consistency invariant).
       :skip-basenames — basename skip-set for the walk (default
                 `+default-skip-basenames+`).
       :skip-rel-prefixes — coll of walk-relative path prefixes to
                 EXCLUDE at enumeration time (default nil).  Unlike
                 :filter's :tree-filter (which matches the STORED
                 rel-path and so cannot separate a `memory/`-prefixed
                 orphan-twin from the real file it aliases), this
                 matches the raw WALK rel-path — the drift-audit uses
                 #{\"memory/\"} to keep the orphan-twin tree out of
                 the parse set (η.5 hardening).

   Returns: flat vector of entity-spec maps; for each .md file, the
   memory entity + its section entities in chain order are appended.
   A file that failed to parse contributes NOTHING here — the flat shape
   cannot carry the failure; `ingest-units` names it (REP-06, D6
   2026-09-19).  Throws ex-info when `from-dir` is neither a directory nor
   a file."
  ([from-dir] (ingest-graph from-dir {}))
  ([from-dir opts]
   (into [] (mapcat :entities) (ingest-units from-dir opts))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip-test convenience

(defn round-trip-test
  "Verify project-graph + ingest-graph round-trip a coll of entities
   losslessly.  Writes to a fresh temp dir; reads back; compares.

   Normalization applied before comparison:
     - Strip `:db/id` (transaction-time only; not preserved across a
       round-trip)
     - Strip `:mm.memory/body-raw` from memories that have sections
       (body-raw is redundant with the section tree + reconstructed on
       round-trip — codec emits the section content into the file body,
       and ingest re-derives body-raw from the emitted text; the
       reconstructed form differs from a manually-supplied 'empty'
       body-raw in the original entity-spec)

   Returns: `{:ok? boolean
              :original entities
              :reingested entity-coll
              :diff diff-or-nil}`.

   Intended for tests + diagnostics; not a production codepath."
  [entities]
  (let [tmpdir (str (java.io.File/createTempFile "project-graph-rt" ""))]
    (.delete (io/file tmpdir))
    (.mkdirs (io/file tmpdir))
    (try
      (project-graph entities {:to tmpdir})
      (let [reingested (ingest-graph tmpdir)
            normalize  (fn [coll]
                         (->> coll
                              (mapv (fn [e]
                                      (cond-> (dissoc e :db/id)
                                        (and (dt/type-isa? :mm/Memory (:dt/type e))
                                             (some? (:mm.memory/first-section e)))
                                        (dissoc :mm.memory/body-raw))))
                              ;; Order-independent comparison — sort by
                              ;; (type, ident) so directory-walk ordering
                              ;; doesn't matter
                              (sort-by (juxt :dt/type :db/ident))
                              vec))
            n-orig     (normalize entities)
            n-back     (normalize reingested)]
        {:ok?        (= n-orig n-back)
         :original   entities
         :reingested reingested
         :diff       (when (not= n-orig n-back)
                       {:original   n-orig
                        :reingested n-back})})
      (finally
        (doseq [f (reverse (file-seq (io/file tmpdir)))]
          (.delete ^java.io.File f))))))
