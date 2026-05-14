(ns sandbar.project-graph
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
;; (which inlined the realize + emit), and in `sandbar.project-graph/project-graph`
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
  (case (:dt/type entity)
    :mm/Memory
    (when-let [first-sec (:mm.memory/first-section entity)]
      [first-sec])

    :mm/Section
    (concat (when-let [next-sib (:mm.section/next-sibling entity)]
              [next-sib])
            (->> (:_mm.section/parent entity)
                 (filter #(nil? (:mm.section/previous-sibling %)))
                 (take 1)))

    nil))

(defn walker-for-class
  "Return the realize-with walker fn for the given class-ident.
   α-scope: hardcoded dispatch for mm/Memory + mm/Section.  Other
   classes return nil (no bundle realization needed; emit the entity
   alone)."
  [class-ident]
  (case class-ident
    :mm/Memory  mm-walker
    :mm/Section mm-walker
    nil))

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
  (when (and (= :mm/Memory (:dt/type entity))
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
  (let [{:keys [class classes pred tree-filter]} filter-spec]
    (and (or (nil? class)        (= class (:dt/type entity)))
         (or (nil? classes)      (contains? classes (:dt/type entity)))
         (or (nil? pred)         (pred entity))
         (or (nil? tree-filter)
             (not= :mm/Memory (:dt/type entity))   ; non-memory passes
             (and (:mm.memory/rel-path entity)
                  (str/starts-with? (:mm.memory/rel-path entity)
                                    tree-filter))))))

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
        memories       (or (get by-type :mm/Memory) [])
        all-sections   (or (get by-type :mm/Section) [])
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
                   (let [memories       (clojure.core/filter (fn [e] (= :mm/Memory (:dt/type e)))
                                                              entities)
                         pass-memories  (apply-filter memories filter-spec)
                         pass-mem-idents (set (map :db/ident pass-memories))
                         sections       (clojure.core/filter (fn [e] (= :mm/Section (:dt/type e)))
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

(defn- walk-markdown-files
  "Walk a directory recursively; return a seq of rel-paths to .md files."
  [^java.io.File root]
  (->> (file-seq root)
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".md")))
       (map (fn [^java.io.File f]
              (let [root-path   (.getCanonicalPath root)
                    file-path   (.getCanonicalPath f)
                    rel         (subs file-path (inc (count root-path)))]
                rel)))))

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
  ([from-dir {filter-spec :filter}]
   (let [root (io/file from-dir)]
     (when-not (.isDirectory root)
       (throw (ex-info "ingest-graph requires a directory input"
                       {:from-dir (str from-dir)})))
     (let [all-entities
           (vec
             (mapcat (fn [rel-path]
                       ;; Tree-filter optimization — skip parse if rel-path
                       ;; can't pass the :tree-filter prefix anyway.
                       (when (or (nil? (:tree-filter filter-spec))
                                 (str/starts-with? rel-path
                                                   (:tree-filter filter-spec)))
                         (let [source (slurp (io/file root rel-path))]
                           (md/parse-document source rel-path))))
                     (walk-markdown-files root)))]
       (if (or (nil? filter-spec) (empty? filter-spec))
         all-entities
         ;; Filter memories first; drop sections of dropped memories
         (let [memories       (clojure.core/filter #(= :mm/Memory (:dt/type %)) all-entities)
               pass-memories  (apply-filter memories filter-spec)
               pass-mem-idents (set (map :db/ident pass-memories))
               sections       (clojure.core/filter #(= :mm/Section (:dt/type %)) all-entities)
               pass-sections  (vec (clojure.core/filter
                                     (fn [s] (contains? pass-mem-idents
                                                        (:mm.section/parent s)))
                                     sections))]
           (into pass-memories pass-sections)))))))

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
                                        (and (= :mm/Memory (:dt/type e))
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
