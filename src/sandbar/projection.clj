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

   From mm/Memory: walks to :mm.memory/first-section (top-of-chain).
   From mm/Section: walks to (1) :mm.section/next-sibling (chain) and
   (2) the first child via :_mm.section/parent reverse-index filtered
   by 'no :previous-sibling' (first-child anchor of the children's
   chain head).  Returns ALREADY-DEDUPLICATED related entities.

   Lifted from sandbar.mcp.resources/mm-walker so both project.export
   and resources/read share the same walk."
  [entity]
  ;; Subsumption-aware: dispatch on Memory-class-or-subclass / Section-class-or-subclass
  ;; per post-2026-05-21 codec slot-inheritance fix.
  (let [t (:dt/type entity)]
    (cond
      (dt/type-isa? :mm/Memory t)
      (when-let [first-sec (:mm.memory/first-section entity)]
        [first-sec])

      (dt/type-isa? :mm/Section t)
      (concat (when-let [next-sib (:mm.section/next-sibling entity)]
                [next-sib])
              (->> (:_mm.section/parent entity)
                   (filter #(nil? (:mm.section/previous-sibling %)))
                   (take 1)))

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
           (codec/emit (first entity-vec) (assoc opts :format native-codec))))

       :else
       (codec/emit entity (assoc opts :format native-codec))))))

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
                          :sections [<mm/Section entity-specs in chain order>...]}`
   maps.  Memory-only inputs (no sections) yield `{:memory ... :sections []}`."
  [entities]
  (let [by-type        (group-by :dt/type entities)
        ;; Subsumption-aware: gather any entity whose :dt/type is :mm/Memory or
        ;; a subclass thereof (e.g., :mm/Decision via :dt/subclass-of :mm/Memory).
        memories       (vec (mapcat (fn [[t es]]
                                      (when (dt/type-isa? :mm/Memory t) es))
                                    by-type))
        all-sections   (vec (mapcat (fn [[t es]]
                                      (when (dt/type-isa? :mm/Section t) es))
                                    by-type))
        section-by-id  (into {} (for [s all-sections] [(:db/ident s) s]))]
    (for [memory memories]
      (let [first-sec-ident (:mm.memory/first-section memory)
            sections (loop [acc []
                            cur (get section-by-id first-sec-ident)]
                       (if cur
                         (recur (conj acc cur)
                                (some->> (:mm.section/next-sibling cur)
                                         (get section-by-id)))
                         acc))]
        {:memory memory :sections sections}))))

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

   Returns: vector of `{:rel-path \"...\" :written true}` records.

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
                   ;; Keep all sections; filter memories; drop sections
                   ;; whose parent didn't pass.
                   (let [memories       (clojure.core/filter (fn [e] (dt/type-isa? :mm/Memory (:dt/type e)))
                                                              entities)
                         pass-memories  (apply-filter memories filter-spec)
                         pass-mem-idents (set (map :db/ident pass-memories))
                         sections       (clojure.core/filter (fn [e] (dt/type-isa? :mm/Section (:dt/type e)))
                                                              entities)
                         pass-sections  (vec (clojure.core/filter
                                               (fn [s]
                                                 (contains? pass-mem-idents
                                                            (:mm.section/parent s)))
                                               sections))]
                     (into pass-memories pass-sections))
                   entities)]
    (.mkdirs out-dir)
    (vec
      (for [{:keys [memory sections]} (group-entities-by-memory filtered)
            :let [rel-path (hierarchy-fn memory)]
            :when rel-path]
        (let [target-file (io/file out-dir rel-path)
              flat        (into [memory] sections)
              content     (md/emit-document flat)]
          (.mkdirs (.getParentFile target-file))
          (spit target-file content)
          (log/debug :PROJECT-GRAPH/wrote {:rel-path rel-path})
          {:rel-path rel-path :written true})))))

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
  subtree-index / root-index patterns)."
  ([^java.io.File root]
   (walk-markdown-files root +default-skip-basenames+))
  ([^java.io.File root skip-basenames]
   (->> (file-seq root)
        (filter #(and (.isFile ^java.io.File %)
                      (str/ends-with? (.getName ^java.io.File %) ".md")
                      (not (contains? skip-basenames
                                      (.getName ^java.io.File %)))))
        (map (fn [^java.io.File f]
               (let [root-path   (.getCanonicalPath root)
                     file-path   (.getCanonicalPath f)
                     rel         (subs file-path (inc (count root-path)))]
                 rel))))))

(defn ingest-graph
  "Walk a filesystem hierarchy + return a coll of entity-spec maps.
   For each `.md` file, parses via sandbar.codec.markdown/parse-document
   using the file's rel-path as the path-derivation source.

   Inputs:
     from-dir — input directory (java.io.File or string)
     opts     — map; supported keys:
       :filter — filter spec per `entity-passes-filter?`; applied
                 AFTER per-file parse.  Memories that fail :class /
                 :pred / :tree-filter drop; sections under dropped
                 memories drop too (consistency invariant).

   Returns: flat vector of entity-spec maps; for each .md file, the
   memory entity + its section entities in chain order are appended."
  ([from-dir] (ingest-graph from-dir {}))
  ([from-dir {filter-spec      :filter
              skip-basenames   :skip-basenames
              :or              {skip-basenames +default-skip-basenames+}}]
   (let [root (io/file from-dir)]
     (when-not (.isDirectory root)
       (throw (ex-info "ingest-graph requires a directory input"
                       {:from-dir (str from-dir)})))
     (let [t-files-start (System/currentTimeMillis)
           files (vec (walk-markdown-files root skip-basenames))
           t-files-end (System/currentTimeMillis)
           _ (log/info :INGEST/FILES-WALKED
                       {:from-dir (str from-dir)
                        :file-count (count files)
                        :ms (- t-files-end t-files-start)})
           tree-filter (:tree-filter filter-spec)
           processed-counter (atom 0)
           all-entities
           (vec
             (mapcat (fn [rel-path]
                       (let [n (swap! processed-counter inc)]
                         (when (zero? (mod n 100))
                           (log/info :INGEST/PARSE-PROGRESS
                                     {:processed n :total (count files)
                                      :ms (- (System/currentTimeMillis) t-files-end)})))
                       ;; Tree-filter optimization — skip parse if rel-path
                       ;; can't pass the :tree-filter prefix anyway.
                       (when (or (nil? tree-filter)
                                 (str/starts-with? rel-path tree-filter))
                         (try
                           (let [source (slurp (io/file root rel-path))]
                             (md/parse-document source rel-path))
                           (catch Throwable ex
                             ;; Per-file parse failures don't abort the whole
                             ;; walk — e.g., a section-ident slug collision
                             ;; in ONE file shouldn't poison the entire corpus
                             ;; ingest.  Log + skip.  Consumers downstream
                             ;; (project-import-handler) report per-group
                             ;; failures explicitly.
                             (log/warn :SANDBAR/INGEST-PARSE-SKIP
                                       {:rel-path rel-path
                                        :error    (.getMessage ex)})
                             nil))))
                     files))
           t-parse-end (System/currentTimeMillis)
           _ (log/info :INGEST/PARSE-COMPLETE
                       {:files-walked (count files)
                        :entities-produced (count all-entities)
                        :ms (- t-parse-end t-files-end)})
           filter-active? (and filter-spec (seq filter-spec))
           _ (log/info :INGEST/FILTER-DECISION
                       {:filter-active? filter-active?
                        :filter-spec filter-spec})]
       (if (or (nil? filter-spec) (empty? filter-spec))
         all-entities
         ;; Filter memories per the spec; sections of dropped memories drop too.
         (let [t-filter-start (System/currentTimeMillis)
               _ (log/info :INGEST/FILTER-START {:entities (count all-entities)})
               ;; Stage 5 Phase A.5 — use cached set-membership lookup
               ;; instead of per-entity dt/type-isa? Datalog query.
               ;; Per `decisions/dt_layer_exposes_memoized_type_relation_ops_with_schema_invalidation_2026_05_22.md`.
               memory-descendants (dt/descendants-of :mm/Memory)
               memories        (vec (clojure.core/filter
                                      #(contains? memory-descendants (:dt/type %))
                                      all-entities))
               t-isa-done (System/currentTimeMillis)
               _ (log/info :INGEST/FILTER-TYPE-ISA-DONE
                           {:memories (count memories)
                            :memory-descendant-count (count memory-descendants)
                            :ms (- t-isa-done t-filter-start)})
               pass-mem-idents (set (map :db/ident (apply-filter memories filter-spec)))
               t-apply-done (System/currentTimeMillis)
               _ (log/info :INGEST/FILTER-APPLY-DONE
                           {:pass-idents (count pass-mem-idents)
                            :ms (- t-apply-done t-isa-done)})]
           (loop [out [] include? false [e & rst] all-entities]
             (cond
               (nil? e)
               out

               (dt/type-isa? :mm/Memory (:dt/type e))
               (let [inc? (contains? pass-mem-idents (:db/ident e))]
                 (recur (cond-> out inc? (conj e)) inc? rst))

               :else
               (recur (cond-> out include? (conj e)) include? rst)))))))))

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
