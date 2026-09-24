(ns sandbar.project.destination
  "Operator-authorized filesystem destinations for one shared store.

   :project-roots maps stable project keys to absolute corpus directories in
   the service configuration. Database corpus-repo metadata never grants a
   path. Each corpus owns its memory/ subtree; unmapped projects retain the
   legacy global destination. Changing this map is stopped-writer maintenance,
   not a live migration. All functions take an explicit database when needed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.config :as config]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.label :as label]
            [sandbar.project.route :as route]))

(defn global-root []
  (or (System/getenv "SANDBAR_CORPUS_ROOT")
      (str (System/getProperty "user.home") "/claude")))

(defn configured-roots []
  (let [roots (config/value :project-roots)]
    (when-not (or (nil? roots) (map? roots))
      (throw (ex-info ":project-roots must be a map of stable project keys to absolute directories"
                      {:sandbar/error :project-destination-refusal :reason :invalid-map})))
    (or roots {})))

(defn- refuse! [reason data]
  (throw (ex-info "Project destination refused; inspect the service's operator-owned :project-roots configuration"
                  (merge {:sandbar/error :project-destination-refusal :reason reason} data))))

(defn- canonical [path] (.getCanonicalPath (io/file path)))

(defn- inside? [parent child]
  (or (= parent child) (str/starts-with? child (str parent java.io.File/separator))))

(defn- memory-root [root]
  (let [memory (canonical (io/file root "memory"))]
    (when (or (= root memory) (not (inside? root memory)))
      (refuse! :memory-root-escapes {:root root :memory-root memory}))
    memory))

(defn project-entity
  "Resolve a stable project key, requiring an actual Project (including subclasses)."
  [database key]
  (when-let [eid (ref/ref->eid database [:mm.project/ident key])]
    (let [e (d/entity database eid)]
      (when (label/class-isa? database :mm/Project (label/class-ident-of e)) e))))

(defn validate-roots!
  "Check the operator map without creating directories or changing the database.
   Reject missing/wrong-kind projects, relative paths, overlapping roots and
   escaped memory/ symlinks. Return canonical roots keyed by stable project key.
   Call before the service accepts requests and at filesystem boundaries."
  [database fallback]
  (let [roots (configured-roots)
        resolved
        (into {}
              (for [[key path] roots]
                (do
                  (when-not (and (keyword? key) (not= key route/unassigned-project-ident)
                                 (project-entity database key))
                    (refuse! :unknown-project {:project-key key}))
                  (when-not (and (string? path) (not (str/blank? path))
                                 (.isAbsolute (io/file path)))
                    (refuse! :root-not-absolute {:project-key key :root path}))
                  (let [root (canonical path) f (io/file root)]
                    (when-not (if (.exists f) (.isDirectory f)
                                  (some-> f .getParentFile .isDirectory))
                      (refuse! :root-not-directory {:project-key key :root root}))
                    (memory-root root)
                    [key root]))))
        all-roots (vec (cons [::global (canonical fallback)] resolved))]
    (doseq [i (range (count all-roots)) j (range (inc i) (count all-roots))
            :let [[a-key a] (all-roots i) [b-key b] (all-roots j)]]
      (when (or (inside? a b) (inside? b a))
        (refuse! :overlapping-roots {:project-keys [a-key b-key] :roots [a b]})))
    resolved))

(defn for-entity
  "Capture an entity's selected root. Missing owner retains UNASSIGNED/global;
   an explicit malformed owner is refused when routing is enabled. Ignore
   :mm.project/corpus-repo. The descriptor is retained before a retraction."
  [database ent fallback]
  (let [roots (validate-roots! database fallback)
        owner (when database (route/owning-project database ent))
        key (route/project-key owner)
        root (or (get roots key) (canonical fallback))]
    (when (and (seq roots) (:mm.memory/owning-project ent)
               (or (nil? (ref/ref->eid database (:mm.memory/owning-project ent)))
                   (not (label/class-isa? database :mm/Project (label/class-ident-of owner)))))
      (refuse! :invalid-owner {:entity (:db/id ent)}))
    {:project-key key :root root :memory-root (memory-root root)
     :mapped? (contains? roots key)}))

(defn assert-current!
  "A captured descriptor cannot silently follow a changed operator map or symlink.
   Does not require the former owner to survive its own retraction."
  [destination fallback]
  (let [configured (get (configured-roots) (:project-key destination))
        root (canonical (or configured fallback))]
    (when (or (not= root (:root destination))
              (not= (memory-root root) (:memory-root destination)))
      (refuse! :destination-changed {:project-key (:project-key destination)}))
    destination))

(defn target-path
  "Resolve a canonical relative path strictly inside the selected memory/ tree.
   With mappings enabled, refuse dot segments and symlink aliases even when they
   stay inside the tree. Ordinary persistence must use one spelling per target;
   historical aliases require operator disposition before enrollment."
  [destination rel-path]
  (when-not (and (string? rel-path) (not (str/blank? rel-path))
                 (not (.isAbsolute (io/file rel-path))))
    (throw (ex-info "Projection requires a nonempty relative file path"
                    {:sandbar/error :rel-path-traversal-refusal :rel-path rel-path})))
  (let [base (:memory-root destination)
        target (canonical (io/file base rel-path))]
    (when (or (= base target) (not (inside? base target)))
      (throw (ex-info "Projection target escapes its authorized memory tree"
                      {:sandbar/error :rel-path-traversal-refusal
                       :corpus-root base :rel-path rel-path :canonical target})))
    (when (and (seq (configured-roots))
               (or (some #{"" "." ".."} (str/split rel-path #"/" -1))
                   (not= target (str base java.io.File/separator rel-path))))
      (refuse! :noncanonical-rel-path {:rel-path rel-path}))
    target))

(defn other-claimants
  "Entities other than eid claiming the same stored rel-path at this destination.
   Compare destinations, not project keys: unmapped projects share the global root."
  [database eid destination rel-path fallback]
  (let [target (target-path destination rel-path)]
    (->> (d/q '[:find [?e ...] :in $ ?rp :where [?e :mm.memory/rel-path ?rp]]
              database rel-path)
         (remove #{eid})
         (filter (fn [other]
                   (= target (target-path (for-entity database (d/entity database other) fallback)
                                          rel-path))))
         vec)))

(defn assert-stable-update!
  "While mappings are enabled, ordinary updates cannot migrate an existing
   corpus file or rename a mapped project's routing key. Use the stopped-writer
   migration procedure; no automatic delete/move is implied by an entity edit."
  [database ent updates fallback]
  (when (seq (configured-roots))
    (when (and (:mm.memory/rel-path ent) (:mm/id ent)
               (contains? updates :mm/id)
               (not= (str (:mm/id ent)) (str (:mm/id updates))))
      (refuse! :file-identity-change {:entity (:db/id ent)}))
    (when (and (contains? (configured-roots) (:mm.project/ident ent))
               (contains? updates :mm.project/ident)
               (not= (:mm.project/ident ent) (:mm.project/ident updates)))
      (refuse! :mapped-project-key-change {:entity (:db/id ent)}))
    (when-let [old-path (:mm.memory/rel-path ent)]
      (when (some #(contains? updates %) [:mm.memory/rel-path :mm.memory/owning-project])
        (let [merged (merge (into {} ent) updates)
              before (for-entity database ent fallback)
              after (for-entity database merged fallback)]
          (when (not= (target-path before old-path)
                      (target-path after (:mm.memory/rel-path merged)))
            (refuse! :file-move-requires-maintenance
                     {:entity (:db/id ent) :rel-path old-path}))))))
  nil)
