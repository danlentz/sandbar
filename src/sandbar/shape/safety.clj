(ns sandbar.shape.safety
  "S7 BU-6 — validator-fn delegates for the 3 directional-firewall
   `:mm/Shape` seeds (schema/mm-artifact.edn, S7-PLAN §6).

   Two of the three shapes need cross-slot logic `check-pattern` cannot
   express, so they dispatch through `sandbar.shape/check-validator-fn`
   into the classpath fns here (the same `:dt.fn/installed-as
   :classpath-fn` registration pattern as the walker fns in
   `sandbar.shape`; resolved at check time via `:dt.fn/source-ns` +
   `:dt.fn/source-var` on the schema-seeded `:mm/Fn` sub-entity).

   Delegate contract (per `check-validator-fn`): `[db entity-eid
   shape-eid] -> {:status :pass ...}` or `{:status :fail :severity <s>
   ...}`.  The walker re-keys a fail's `:check` to `:validator-fn`; the
   delegate identity survives in `:constraint`.

   Boundary discipline (S7-PLAN §6): absolute-path + `..`-traversal
   REJECTION is `:mm.shape/no-absolute-path`'s job (`check-pattern`,
   negative regex).  `project-layout-safety`'s specific concern is the
   CROSS-SLOT invariant — a `:submodule`/`:subdir` layout whose
   corpus-repo does not stay inside the project's own tree (a submodule
   crossing a visibility boundary), which INCLUDES forms the pattern
   shape permits (e.g. a remote URL is a legal corpus-repo for a
   `:separate-repo` layout but is NOT in-tree for a `:submodule` one).
   `repo-handle-url-safety` is the L-6 consent gate: a `:public`-default
   project may not carry a populated corpus-repo URL without an explicit
   `:mm.project/code-ref-public? true` (absent => false, the ruling-6
   read-time default)."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [sandbar.db.fn :refer [defdbfn]]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers (not memorialized; internal to the delegates)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- shape-severity
  "Resolve the shape's declared severity, defaulting to :violation."
  [shape]
  (or (:mm.shape/severity shape) :violation))

(defn- remote-spec?
  "True when `s` names a REMOTE repo rather than an in-tree path: a URI
   scheme (`https://`, `git://`, `file://`, ...) or an scp-like
   `user@host:path` git remote."
  [s]
  (boolean (or (re-find #"\A[A-Za-z][A-Za-z0-9+.-]*://" s)
               (re-find #"\A[^/@]+@[^/:]+:" s))))

(defn- absolute-path?
  "True for the L-2/R7 absolute forms: `^/`, `^~`, `^[A-Za-z]:\\`."
  [s]
  (boolean (re-find #"\A(?:[/~]|[A-Za-z]:\\)" s)))

(defn- escapes-upward?
  "Segment-walk over a relative path: true when the path climbs above its
   own root at ANY point (`..` outruns the preceding depth)."
  [s]
  (loop [segs (remove #{"" "."} (str/split s #"/"))
         depth 0]
    (cond
      (neg? depth)  true
      (empty? segs) false
      :else         (recur (rest segs)
                           (if (= ".." (first segs)) (dec depth) (inc depth))))))

(defn- in-project-tree?
  "True iff `s` is a path CONFINED to the project's own tree: non-blank,
   not a remote spec, not absolute, and never climbing above its root.
   Fail-closed: any form not positively shown in-tree is out."
  [s]
  (and (string? s)
       (not (str/blank? s))
       (not (remote-spec? s))
       (not (absolute-path? s))
       (not (escapes-upward? s))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validator-fn delegates — each a first-class :mm/Fn (classpath install)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defdbfn check-project-layout-safety [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Delegate for :mm.shape/project-layout-safety — a :submodule/:subdir corpus-layout's :mm.project/corpus-repo must stay inside the project's own tree (a submodule crossing a visibility boundary is a :violation).  Other layouts are out of scope (pass)."
   :dt.fn/version      "1.0.0"}
  (let [entity   (d/entity db entity-eid)
        shape    (d/entity db shape-eid)
        layout   (:mm.project/corpus-layout entity)]
    (if-not (contains? #{:submodule :subdir} layout)
      ;; The invariant scopes ONLY the in-tree layouts; :separate-repo /
      ;; :sibling-repo / absent corpus-layout carry no in-tree obligation.
      {:status :pass :check :project-layout-safety :reason :layout-not-in-tree}
      (let [corpus-repo (:mm.project/corpus-repo entity)]
        (if (in-project-tree? corpus-repo)
          {:status :pass :check :project-layout-safety}
          {:status      :fail
           :check       :project-layout-safety
           :constraint  :project-layout-safety
           :layout      layout
           :corpus-repo corpus-repo
           :reason      :corpus-repo-escapes-project-tree
           :severity    (shape-severity shape)})))))


(defdbfn check-repo-handle-url-safety [db entity-eid shape-eid]
  {:dt.fn/purpose      :validate
   :dt.fn/purity       :pure-total
   :dt.fn/cost-class   :cheap
   :dt.fn/installed-as :classpath-fn
   :dt.fn/description  "Delegate for :mm.shape/repo-handle-url-safety (RETARGETED to :mm/Project — S6 minted no RepoHandle) — a :public default-visibility project carrying a populated :mm.project/corpus-repo URL with :mm.project/code-ref-public? absent-or-false is a :violation (L-6; absent => false per ruling 6)."
   :dt.fn/version      "1.0.0"}
  (let [entity      (d/entity db entity-eid)
        shape       (d/entity db shape-eid)
        corpus-repo (:mm.project/corpus-repo entity)
        ;; "UNASSIGNED" is the seeded sentinel placeholder ("never pushed",
        ;; mm-artifact.edn batch (v)) — semantically UNpopulated, not a URL.
        populated?  (and (string? corpus-repo)
                         (not (str/blank? corpus-repo))
                         (not= "UNASSIGNED" corpus-repo))
        ;; absent => false is the ruling-6 READ-TIME default; only an
        ;; explicit stored `true` consents.  The shape reads ONLY the
        ;; project-intrinsic default-visibility (NOT the CA-4 composed
        ;; label): a :private-default project is never in scope, whatever
        ;; its firewall-class (:public-bottom included).
        consented?  (true? (:mm.project/code-ref-public? entity))
        public?     (= :public (:mm.project/default-visibility entity))]
    (if (and public? populated? (not consented?))
      {:status      :fail
       :check       :repo-handle-url-safety
       :constraint  :repo-handle-url-safety
       :corpus-repo corpus-repo
       :reason      :public-project-url-without-code-ref-consent
       :severity    (shape-severity shape)}
      {:status :pass :check :repo-handle-url-safety})))
