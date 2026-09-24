(ns sandbar.project.import-destination
  "Owner verification for a maintenance import, and root attribution for the
   drift audit, over the operator's destination map.

   With `:project-roots` configured, a project's files live in its own
   corpus tree and the sink writes them there (`sandbar.project.destination`).
   An import is the one path where a *file* could choose a tree: its front
   matter carries `owning-project`, which `sandbar.import/substrate-owned-slots`
   lets a file assert.  This namespace keeps that from turning a root's
   permission into another project's ownership: a unit imported from a
   mapped root must belong to that root's project; an existing document is
   never moved between trees by an import (the same no-migration rule the
   update guard `destination/assert-stable-update!` applies; moves are the
   stopped-writer migration procedure); a currently mapped Project keeps its
   routing key (the guard's other half, which `sandbar.import/apply-plan!`
   would bypass: it transacts through `dt/make-all-with-retractions*`, not
   `dt/update-entity!`); the legacy path from an unmapped staging directory
   is retained.  `:mm.project/corpus-repo` is never consulted.  Pure: every
   function takes an explicit database value and transacts nothing; the
   import handler merges the conflicts this namespace reports into the plan
   `sandbar.import/plan-unit` made, so a unit with a destination conflict is
   refused in the preview and in the persist alike.

   The audit half attributes a walked root and each store entity to a
   physical tree so the FS↔DB comparison stays within one tree.  See
   doc/operations.md, §Serve a project repository (enrollment and the
   operator map) and §Maintenance import into the existing store (the
   stopped-writer procedure this namespace's refusals point to)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.db.ref :as ref]
            [sandbar.firewall.label :as label]
            [sandbar.project.destination :as destination]
            [sandbar.project.route :as route]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Roots
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- canonical
  [path]
  (.getCanonicalPath (io/file (str path))))

(defn- inside?
  "`child` is `parent` or lies strictly beneath it (both canonical)."
  [parent child]
  (or (= parent child)
      (str/starts-with? child (str parent java.io.File/separator))))

(defn fallback-root
  "The global corpus root every unmapped owner projects into."
  []
  (destination/global-root))

(defn- classify
  "`classify-root` over an already validated `roots` map."
  [roots dir]
  (let [dir*   (canonical dir)
        global (canonical (fallback-root))
        base   {:enabled? (boolean (seq roots)) :dir dir*}]
    (merge base
           (or (some (fn [[k r]] (when (inside? r dir*) {:kind :mapped :project-key k :root r})) roots)
               (when (inside? global dir*) {:kind :global :root global})
               {:kind :unmapped}))))

(defn classify-root
  "Where a directory sits relative to the validated operator map.

   Returns `{:kind :mapped :project-key k :root r}` when `dir` is a configured
   project root or lies beneath it (its `memory/` subtree included);
   `{:kind :global :root g}` when it lies beneath the global root; otherwise
   `{:kind :unmapped}` — an ad hoc directory such as the maintenance
   procedure's staging root.  Every result carries `:dir` (canonical) and
   `:enabled?` — false when the operator map is empty, in which case routing
   is disabled and no destination check applies (`unit-conflicts` adds
   nothing; the importer's own validation stands alone).  `validate-roots!`
   runs first, so a misconfigured map refuses here, before any file is read
   or compared."
  [database dir]
  (classify (destination/validate-roots! database (fallback-root)) dir))

(defn entity-root
  "The canonical root an entity's owner selects under the validated `roots`
   map — the same selection `destination/for-entity` makes, taken once per
   entity without re-validating the map and without its invalid-owner
   refusal: an owner that resolves to nothing attributes to the global root,
   as `route/owning-project` resolves it.  For attribution and reporting, not
   for authorization; the sink and the retract verb use `for-entity`."
  [database roots ent]
  (let [key (route/project-key (route/owning-project database ent))]
    (or (get roots key) (canonical (fallback-root)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Owner verification per source unit
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- project-key-of
  "The stable key of the Project entity `eid` names, or nil when `eid` is nil
   or names something that is not a Project."
  [database eid]
  (when eid
    (let [e (d/entity database eid)]
      (when-let [cls (label/class-ident-of e)]
        (when (label/class-isa? database :mm/Project cls)
          (route/project-key e))))))

(defn- stored-root-entity
  "The live entity the unit's root spec would update — matched by `:db/ident`
   as `sandbar.import/plan-unit` matches it — or nil for a new document."
  [database root-spec]
  (when-let [ident (:db/ident root-spec)]
    (when-let [eid (d/entid database ident)]
      (let [e (d/entity database eid)]
        (when (:dt/type e) e)))))

(defn- move-conflict
  "A conflict when asserting the file's owner on the stored entity would
   change its physical target — re-owning a mapped document, adopting an
   unowned one into a mapped tree, or sending a mapped one back to the
   global tree.  nil when the target is unchanged."
  [database stored file-ref]
  (when-let [rel-path (:mm.memory/rel-path stored)]
    (try
      (let [fallback (fallback-root)
            before   (destination/for-entity database stored fallback)
            after    (destination/for-entity database
                                             (assoc (into {} stored) :mm.memory/owning-project file-ref)
                                             fallback)
            from-t   (destination/target-path before rel-path)
            to-t     (destination/target-path after rel-path)]
        (when (not= from-t to-t)
          {:reason :import/file-move-requires-maintenance
           :entity (:db/id stored)
           :from-root (:root before)
           :to-root (:root after)}))
      (catch clojure.lang.ExceptionInfo ex
        ;; A stored document whose CURRENT owner the destination resolver
        ;; refuses (a malformed owner under an enabled map) is a per-unit
        ;; conflict for the operator, not a failure of the whole preview.
        (if (= :project-destination-refusal (:sandbar/error (ex-data ex)))
          {:reason :import/destination-refused
           :entity (:db/id stored)
           :error (.getMessage ex)
           :detail (dissoc (ex-data ex) :sandbar/error)}
          (throw ex))))))

(defn- mapped-key-conflict
  "A conflict when the unit would stop a currently mapped routing key from
   naming the Project it names now — the invariant `validate-roots!` checks
   at every boundary (an operator key that no longer resolves refuses the
   whole service's routing) and `destination/assert-stable-update!` protects
   on an ordinary edit.  Decided from the plan the importer will transact,
   not from the mode alone.  Three shapes, one reason, told apart by
   `:change`: `:rename` — the file asserts a different key on the key's
   holder (a cardinality-one assert replaces in every mode, additive
   included); `:retraction` — the plan retracts the key, which a replace
   import does when the file omits it (`sandbar.import/slot-ops`; an
   additive import retracts nothing, so an omission there passes);
   `:takeover` — the file asserts a mapped key another stored Project holds
   (`:mm.project/ident` is `:db.unique/identity`, so the transaction would
   upsert onto the holder or refuse as a unique conflict, after a clean
   preview).  An unchanged key passes; an unmapped project's key is not this
   guard's business.  `roots` is the operator map keyed by project key, as
   `assert-stable-update!` reads it."
  [database roots plan stored]
  (let [m        (first (:specs plan))
        held     (:mm.project/ident stored)
        asserted (:mm.project/ident m)
        retracts (some (fn [op]
                         (and (vector? op)
                              (= :db/retract (first op))
                              (= :mm.project/ident (nth op 2 nil))))
                       (:ops plan))]
    (cond
      (and held (contains? roots held) (contains? m :mm.project/ident) (not= asserted held))
      {:reason :import/mapped-project-key-change :change :rename
       :entity (:db/id stored) :key held :asserted asserted :root (get roots held)}

      (and held (contains? roots held) retracts)
      {:reason :import/mapped-project-key-change :change :retraction
       :entity (:db/id stored) :key held :mode (:mode plan) :root (get roots held)}

      (and asserted (contains? roots asserted))
      (let [holder (destination/project-entity database asserted)]
        (when (and holder (not= (:db/id holder) (:db/id stored)))
          {:reason :import/mapped-project-key-change :change :takeover
           :entity (:db/id stored) :key asserted :holder (:db/id holder)
           :root (get roots asserted)})))))


(defn with-root-owner
  "The unit's `specs` (the parse: the root first) with the mapped root's
   Project asserted as `:mm.memory/owning-project` on the root spec when the
   document is genuinely new — no stored typed entity under its ident, an
   untyped forward placeholder included — is walked from a mapped root
   (`source-root`, from `classify-root`, routing enabled) and names no owner
   at all, and is a document — its class a Memory or a Memory subclass, the
   only classes that carry `:mm.memory/owning-project` and the same test the
   import's identity minting applies.  The operator's map is the ownership
   assertion for that tree, so a new file that says nothing is not choosing
   another tree; asserting the owner here, before `sandbar.import/plan-unit`,
   makes the preview and the apply transact the same default.  Returned
   unchanged in every other case: an existing typed document (an unowned one
   stays held for the operator — `unit-conflicts` still reports
   `:import/owner-missing`; a matching owner survives an omitted line), an
   explicit owner of any kind (its conflicts stay refused), a root spec of
   another class (a Tag, say), an unmapped or global root, or routing
   disabled.  Pure."
  [database source-root specs]
  (let [m (first specs)]
    (if (and (= :mapped (:kind source-root))
             (:enabled? source-root)
             (not (contains? m :mm.memory/owning-project))
             (keyword? (:dt/type m))
             (label/class-isa? database :mm/Memory (:dt/type m))
             (nil? (stored-root-entity database m)))
      (if-let [project (destination/project-entity database (:project-key source-root))]
        (into [(assoc m :mm.memory/owning-project
                      (if-let [ident (:db/ident project)] {:db/ident ident} {:db/id (:db/id project)}))]
              (rest specs))
        specs)
      specs)))

(declare unit-conflicts*)

(defn unit-conflicts
  "The destination conflicts for one source unit, given the `plan`
   `sandbar.import/plan-unit` made for it (`:specs` — the parse, the root
   first; `:mode`; `:ops` — the retractions a replace import transacts) and
   the directory it was walked from, classified as `source-root`
   (`classify-root`).  Pure; returns a vector, empty when the unit may
   proceed, else conflict maps in the `:reason` vocabulary `plan-unit` uses,
   so the handler can merge them into the plan's own:

   - `:import/owner-unresolvable` — the file names an owner that resolves to
     nothing, or to something that is not a Project.
   - `:import/file-move-requires-maintenance` — the document exists and the
     file's owner would change its physical tree (see `move-conflict`).
   - `:import/mapped-project-key-change` — the unit would rename, retract or
     take over a currently mapped Project's routing key (see
     `mapped-key-conflict`; `:change` names which).
   - under a mapped root only:
     `:import/owner-missing` — neither the file nor a stored document names
     an owner.  With the handler applying `with-root-owner` before the plan,
     a genuinely new document that names none has the root's project by
     then, so this reports the existing unowned document, which is never
     adopted silently;
     `:import/owner-conflict` — the effective owner is another project.

   From the global root or an unmapped staging directory the legacy path
   stands: only the root-independent conflicts apply.  With routing
   disabled (an empty operator map, `:enabled? false`) nothing applies: the
   importer's own validation is authoritative, including its tolerance of
   forward and not-yet-resolvable owner refs."
  [database source-root plan]
  (if-not (:enabled? source-root)
    []
    (unit-conflicts* database source-root plan)))

(defn- unit-conflicts*
  "`unit-conflicts` with routing enabled."
  [database source-root plan]
  (let [specs      (:specs plan)
        m          (first specs)
        stored     (stored-root-entity database m)
        file-ref   (:mm.memory/owning-project m)
        file-eid   (when file-ref (ref/ref->eid database file-ref))
        file-key   (project-key-of database file-eid)
        unresolved (and (some? file-ref) (nil? file-key))
        stored-key (when stored
                     (let [k (route/project-key (route/owning-project database stored))]
                       (when (not= k route/unassigned-project-ident) k)))
        conflicts  (cond-> []
                     unresolved
                     (conj {:reason :import/owner-unresolvable :owner (pr-str file-ref)}))
        conflicts  (if-let [moved (when (and stored file-key) (move-conflict database stored file-ref))]
                     (conj conflicts moved)
                     conflicts)
        conflicts  (if-let [key-change (mapped-key-conflict database (destination/configured-roots) plan stored)]
                     (conj conflicts key-change)
                     conflicts)]
    (if (= :mapped (:kind source-root))
      (let [expected  (:project-key source-root)
            effective (or file-key stored-key)]
        (cond
          unresolved                 conflicts
          (nil? effective)           (conj conflicts {:reason :import/owner-missing :expected expected
                                                      :source-root (:root source-root)})
          (not= effective expected)  (conj conflicts {:reason :import/owner-conflict :expected expected
                                                      :actual effective :source-root (:root source-root)})
          :else                      conflicts))
      conflicts)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Audit attribution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn audit-attribution
  "How the drift audit should select store entities for a walked root.

   `{:kind :mapped …}` and `{:kind :global …}` (from `classify-root`) name a
   physical tree: the comparison keeps only the entities whose owner selects
   that tree (`entity-root`), so files of one tree are never reported missing
   against documents of another.  `{:kind :unmapped …}` — an ad hoc
   directory — keeps the store-wide comparison the audit always made, and the
   report labels that limitation.  Returns
   `{:kind :root :project-key :roots :limitation}`; `:roots` is the
   validated map for `entity-root`."
  [database walk-root]
  (let [roots     (destination/validate-roots! database (fallback-root))
        c         (classify roots walk-root)
        tree      (:root c)
        ;; A full-tree comparison is claimed ONLY for the exact corpus root or
        ;; its exact memory/ directory — the two forms `resolve-walk-root`
        ;; maps a whole-tree :from to.  A deeper directory of a configured
        ;; tree (root/memory/observations) walks a part of it, and comparing
        ;; that part with every document of the tree would report the
        ;; unwalked siblings missing; it stays a labelled partial diagnostic.
        whole?    (and tree (contains? #{tree (canonical (io/file tree "memory"))} (:dir c)))
        partial   (fn [what]
                    {:kind :ad-hoc :root (:dir c) :roots roots :tree tree
                     :limitation (str "the walk root is " what
                                      "; the comparison is store-wide, so missing-from-fs may name documents that belong to another tree or to an unwalked part of this one")})]
    (cond
      (and (= :mapped (:kind c)) whole?)
      {:kind :mapped :root tree :project-key (:project-key c) :roots roots}

      (and (= :global (:kind c)) whole?)
      {:kind :global :root tree :roots roots}

      (= :mapped (:kind c))
      (assoc (partial "a subdirectory of a configured project tree, not the tree or its memory/ directory")
             :project-key (:project-key c))

      (= :global (:kind c))
      (partial "a subdirectory of the global tree, not the tree or its memory/ directory")

      :else
      (partial "not a configured corpus root or the global root"))))

(defn entity-in-tree?
  "Whether `ent` belongs to the tree `attribution` names; every entity does for
   an ad hoc attribution."
  [database attribution ent]
  (or (= :ad-hoc (:kind attribution))
      (= (:root attribution) (entity-root database (:roots attribution) ent))))
