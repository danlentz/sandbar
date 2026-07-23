(ns sandbar.gate.fixture
  "Fixtures for the W1.J release gate — the two-store corpus paths + the
   directional-firewall seed helpers.

   The seed helpers are inlined from `sandbar.firewall.support` (a TEST
   namespace) so the gate runs on the plain `src` classpath as a `lein`
   alias — a script `lein run -m ...` does NOT put `test/` on the
   classpath, and the standing gate must be invokable outside `lein test`.
   Every seed transacts via RAW `d/transact` (never `dt/make`) so SETUP
   itself never trips the firewall guard the CHECK-2 probes exercise.

   Corpus fixtures live at `test/resources/w1-fixtures/{public-corpus,
   second-project}` (see that dir's README). Paths resolve CWD-relative
   (both `lein run` and `lein test` run from the project root); override
   the root via `*fixture-root*`."
  (:require [clojure.java.io :as io]
            [datomic.api     :as d]
            [sandbar.db.datomic :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; two-store corpus paths
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:dynamic *fixture-root*
  "CWD-relative root of the committed two-store fixture corpus."
  "test/resources/w1-fixtures")

(defn store-dir
  "Absolute `File` for fixture store `store` (:public-corpus | :second-project).
   Throws if absent — a missing fixture is a loud gate-setup failure, never
   a silent skip."
  ^java.io.File [store]
  (let [f (io/file *fixture-root* (name store))]
    (when-not (.isDirectory f)
      (throw (ex-info "W1.J fixture store not found"
                      {:store store :resolved (.getAbsolutePath f)
                       :hint "run from the sandbar project root, or bind sandbar.gate.fixture/*fixture-root*"})))
    f))

(def public-store  (constantly :public-corpus))
(def private-store (constantly :second-project))

;; The private markers the absence-probe battery keys on (see fixtures README).
(def private-term "zephyrite")                    ; BM25F zero-hit + zero-IDF term
(def private-name-token "Zephyrite")              ; aggregate-count discriminator
(def private-tag-value "proprietary-secret-sauce"); tag-histogram private-only bin

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; directional-firewall seed helpers (raw d/transact — SETUP only)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn raw-transact!
  "Transact `tx` via raw Datomic (bypassing the firewall guard). SETUP only."
  [tx]
  @(d/transact (db/conn) tx))

(defn eid-of [ident] (:db/id (d/entity (db/db) ident)))

(defn seed-context!
  "Seed a :mm/Context with `firewall-class` (default `:none` = private);
   pass `:public-bottom` for the public compartment."
  ([ident] (seed-context! ident :none))
  ([ident firewall-class]
   (raw-transact! [{:db/ident ident
                    :dt/type  :mm/Context
                    :mm.memory/name (name ident)
                    :mm.context/firewall-class firewall-class}])
   (eid-of ident)))

(defn seed-project!
  "Seed a :mm/Project (visibility :public|:private) tied to `context-ident`."
  ([ident visibility context-ident] (seed-project! ident visibility context-ident nil))
  ([ident visibility context-ident firewall-class]
   (raw-transact! [(cond-> {:db/ident ident
                            :dt/type  :mm/Project
                            :mm.memory/name (name ident)
                            :mm.project/ident ident
                            :mm.project/corpus-repo "fixture-repo"
                            :mm.project/default-visibility visibility
                            :mm.project/runs-in-context context-ident}
                     firewall-class (assoc :mm.project/firewall-class firewall-class))])
   (eid-of ident)))

(defn seed-memory!
  "Seed a :mm/Memory (explicit `visibility`, owned by `project-ident`)."
  ([ident visibility project-ident] (seed-memory! ident visibility project-ident {}))
  ([ident visibility project-ident extra]
   (raw-transact! [(merge {:db/ident ident
                           :dt/type  :mm/Memory
                           :mm.memory/name (name ident)
                           :mm.memory/visibility visibility
                           :mm.memory/owning-project project-ident}
                          extra)])
   (eid-of ident)))

(defn type-corpus-tags!
  "Stamp `:dt/type :mm/Tag` on freshly-imported free-text tag entities in
   the current db; return the count typed.

   The codec upserts a `tags:` frontmatter value as a bare `{:mm.tag/value
   v}` ref-target WITHOUT `:dt/type`, so `dt/all-instances-of :mm/Tag` —
   which `aggregate/tag-histogram` enumerates — misses it (a fresh-import
   tag is not yet a canonical typed instance).  The LIVE corpus's tags ARE
   typed (via canonical-tag promotion), so this is a fixture-fidelity
   normalization that makes the two-store fixture match live-corpus reality
   — WITHOUT which the tag-histogram absence probe's cleared negative
   control would spuriously read the private tag as absent."
  []
  (let [eids (d/q '[:find [?e ...] :where [?e :mm.tag/value _]] (db/db))]
    (when (seq eids)
      (raw-transact! (mapv (fn [e] {:db/id e :dt/type :mm/Tag}) eids)))
    (count eids)))

(defn seed-directional-world!
  "Seed the canonical public/private compartment world the directional
   probes fire against (mirrors ceremony8_composition_test/seed-world!):
   a public-bottom context + a public project & target, a private context
   + two private projects & a private target."
  []
  (seed-context! :ctx/home  :public-bottom)
  (seed-context! :ctx/workA :project-isolated)
  (seed-project! :proj/pub   :public  :ctx/home :public-bottom)
  (seed-project! :proj/privA :private :ctx/workA)
  (seed-memory!  :mem/pub-target   :public  :proj/pub)
  (seed-memory!  :mem/privA-target :private :proj/privA))
