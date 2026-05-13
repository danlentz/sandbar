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
            [sandbar.codec.markdown :as md]))

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

   Returns: vector of `{:rel-path \"...\" :written true}` records.

   Idempotence: re-projecting the same entities to the same dir yields
   byte-identical files (per the markdown codec's normalization
   invariants in
   decisions/mm_section_schema_path_derived_idents_sibling_chain_navigation_2026_05_13.md §4)."
  [entities {:keys [to hierarchy-fn]
             :or   {hierarchy-fn default-hierarchy-fn}}]
  (when-not to
    (throw (ex-info "project-graph requires :to opt (output directory)" {})))
  (let [out-dir (io/file to)]
    (.mkdirs out-dir)
    (vec
      (for [{:keys [memory sections]} (group-entities-by-memory entities)
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
     opts     — currently unused (reserved for filter + class-override
                opts in later Stage D substages)

   Returns: flat vector of entity-spec maps; for each .md file, the
   memory entity + its section entities in chain order are appended."
  ([from-dir] (ingest-graph from-dir {}))
  ([from-dir _opts]
   (let [root (io/file from-dir)]
     (when-not (.isDirectory root)
       (throw (ex-info "ingest-graph requires a directory input"
                       {:from-dir (str from-dir)})))
     (vec
       (mapcat (fn [rel-path]
                 (let [source (slurp (io/file root rel-path))]
                   (md/parse-document source rel-path)))
               (walk-markdown-files root))))))

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
