(ns sandbar.scripts.seed-verb-catalog
  "S2 of the MCP-Surface-Mastery arc — seed the `:mm/Verb` catalog from the
   live `verb-catalog` (sandbar.mcp.tools), so the MCP verb surface becomes a
   queryable / BM25F-retrievable / graph-navigable substrate (foundation for
   the affordance map P1 + per-task verb retrieval P2).

   Source of truth is `tools/verb-catalog`; this is a PROJECTION of it, and as
   of F6 it is the one STATEFUL projection (writes a live DB, not a repo file).
   The derived fields (axis / arg-summary / section split / combines-with /
   prereq-of graph) now come from the shared `sandbar.mcp.catalog-model` — the
   SAME derivation the doc + edges-map projections use — so the three copies of
   that logic collapse to one (design §5.4, a behavior-preserving refactor: the
   same datoms get written, from one shared model).

   Idempotent — `:mm.verb/name` is `:db.unique/identity`, so re-running
   upserts rather than duplicating.

   Passes:
     0. idents   — intern a semantic `:db/ident` per verb (`:sandbar.<axis>/<leaf>`).
     1. entities — one `:mm/Verb` per verb (scalar slots from the model).
     2. combines-with — assert the symmetric related-verb graph from the model's
        :combines-with (undirected, prose-parsed).
     3. prereq-of — DIRECTIONAL edges: for each verb, the model's :prereqs are
        the verbs that are prerequisite OF it (edge prereq->verb).  Cycle-safe
        (the model already dropped cycle-closing edges).

   Usage:
     lein run -m sandbar.scripts.seed-verb-catalog        ; separate peer → prod DB
     (sandbar.scripts.seed-verb-catalog/seed!)            ; in-JVM via nREPL (live conn)"
  (:require [clojure.string            :as str]
            [datomic.api               :as d]
            [sandbar.mcp.tools         :as tools]
            [sandbar.mcp.catalog-model :as model]
            [sandbar.db.datatype       :as dt]
            [sandbar.db.datomic        :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ident derivation (seed-specific — not a projection concern, stays here)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn verb-ident
  "Semantic :db/ident for a verb name: \"sandbar.entity.create\" -> :sandbar.entity/create.
   ns = sandbar.<axis>, name = <leaf-path> (everything after the axis segment).
   Fixes the BRITTLE-identifier defect — verbs were addressable only by a
   name-lookup-ref or an eid; a semantic ident is valuable to human + LLM +
   backend (the three-tier identifier hierarchy).  Mirrors the wire MCP tool
   name (sandbar.entity.create) for recognizability + reversibility."
  [verb-name]
  (let [segs (str/split (str verb-name) #"\.")]
    (cond
      (>= (count segs) 3) (keyword (str (nth segs 0) "." (nth segs 1))
                                   (str/join "." (drop 2 segs)))
      (= (count segs) 2)  (keyword (nth segs 0) (nth segs 1)) ; sandbar.ground -> :sandbar/ground
      :else nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Slot-map — projected from the shared catalog-model (design §5.4)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn model-verb->slots
  "Build the :mm/Verb scalar slot-map for one MODEL verb map (the model already
   carries the derived hints via tools/verb-behavioral-hints — the SAME
   derivation that feeds the wire annotations in handle-list)."
  [{:keys [name title description arg-summary read-only? destructive? idempotent?
           open-world? transition-kind hint-status axis which when how]}]
  (cond-> {:mm.verb/name            name
           :mm.verb/title           (str title)
           :mm.verb/description     (str description)
           :mm.verb/arg-summary     arg-summary
           :mm.verb/read-only?      read-only?
           :mm.verb/destructive?    destructive?
           :mm.verb/idempotent?     idempotent?
           :mm.verb/open-world?     open-world?
           :mm.verb/transition-kind transition-kind
           :mm.verb/hint-status     hint-status}
    axis  (assoc :mm.verb/axis axis)
    which (assoc :mm.verb/which which)
    when  (assoc :mm.verb/when  when)
    how   (assoc :mm.verb/how   how)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seeding passes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- seed-entities!
  "Pass 1 — upsert one :mm/Verb per model verb (validated via dt/make)."
  [m]
  (doseq [v (:verbs m)]
    (dt/make :mm/Verb (model-verb->slots v)))
  (count (:verbs m)))

(defn- seed-idents!
  "Pass 0 — intern a semantic :db/ident per verb (:sandbar.<axis>/<leaf>) via
   unique-identity upsert on :mm.verb/name.  Idempotent.  Returns {:idents n}."
  [m]
  (let [conn  (db/conn)
        pairs (keep (fn [{:keys [name]}]
                      (when-let [id (verb-ident name)] [name id]))
                    (:verbs m))]
    (doseq [[name id] pairs]
      (d/transact conn [{:mm.verb/name name :db/ident id}]))
    {:idents (count pairs)}))

(defn- seed-edges!
  "Pass 2 — assert the symmetric :mm.verb/combines-with graph from the model's
   :combines-with (one tx per verb that has neighbors).  Returns edge stats."
  [m]
  (let [conn (db/conn)
        with-edges (filter (comp seq :combines-with) (:verbs m))]
    (doseq [{:keys [name combines-with]} with-edges]
      (d/transact conn
        [{:mm.verb/name name
          :mm.verb/combines-with (mapv (fn [n] [:mm.verb/name n]) combines-with)}]))
    {:verbs-with-edges (count with-edges)
     :edge-pairs (quot (reduce + (map (comp count :combines-with) (:verbs m))) 2)
     :unresolved (:combine-unresolved m)}))

(defn- seed-prereq-edges!
  "Pass 3 — REPLACE :mm.verb/prereq-of with the model's directional graph.
   Cardinality-many assertions are ADDITIVE, so first RETRACT all existing
   prereq-of datoms (clean replace).  The model's :prereqs are INBOUND (verbs
   prerequisite OF v), so the edge is prereq :prereq-of v (prereq->v)."
  [m]
  (let [conn     (db/conn)
        existing (d/q '[:find ?e ?t :where [?e :mm.verb/prereq-of ?t]] (d/db conn))
        ;; invert model :prereqs back to outbound edges keyed by the prereq verb
        outbound (reduce (fn [acc {:keys [name prereqs]}]
                           (reduce (fn [a p] (update a p (fnil conj #{}) name)) acc prereqs))
                         {} (:verbs m))]
    (when (seq existing)
      (d/transact conn (mapv (fn [[e t]] [:db/retract e :mm.verb/prereq-of t]) existing)))
    (doseq [[src dsts] outbound :when (seq dsts)]
      (d/transact conn
        [{:mm.verb/name src
          :mm.verb/prereq-of (mapv (fn [dv] [:mm.verb/name dv]) dsts)}]))
    {:prereq-verbs  (count (filter (comp seq val) outbound))
     :prereq-edges  (reduce + 0 (map (comp count val) outbound))
     :retracted     (count existing)
     :skipped-cycles (:prereq-skipped m)
     :unresolved    (:prereq-unresolved m)}))

(defn seed!
  "Seed (idempotently) the :mm/Verb catalog into the current substrate
   (uses the live `db/**conn*`).  Builds the shared model once, then runs the
   four transaction passes off it.  Returns a summary map."
  []
  (let [m      (model/build-catalog-model tools/verb-catalog)
        n      (seed-entities! m)
        idents (seed-idents! m)
        edges  (seed-edges! m)
        prereq (seed-prereq-edges! m)]
    {:seeded n
     :idents (:idents idents)
     :verbs-with-edges (:verbs-with-edges edges)
     :edge-pairs (:edge-pairs edges)
     :prereq-verbs (:prereq-verbs prereq)
     :prereq-edges (:prereq-edges prereq)
     :prereq-retracted (:retracted prereq)
     :skipped-cycles (:skipped-cycles prereq)
     :flagged (vec (distinct (concat (:unresolved edges) (:unresolved prereq))))}))

(defn -main
  "Separate-peer entry: connect to the configured (prod) DB, ensure schema
   (incl. :mm-verb) is loaded, seed, print summary."
  [& _]
  (reset! db/**conn* (db/conn (db/db-uri)))
  (db/load-all-schema! (db/db-uri))
  (let [summary (seed!)]
    (println "seed-verb-catalog ✓" (pr-str (dissoc summary :flagged :skipped-cycles)))
    (when (seq (:skipped-cycles summary))
      (println "SKIPPED cycle-closing prereq edges:" (pr-str (:skipped-cycles summary))))
    (when (seq (:flagged summary))
      (println "FLAGGED unresolved refs (review):" (pr-str (:flagged summary))))
    (shutdown-agents)
    (System/exit 0)))
