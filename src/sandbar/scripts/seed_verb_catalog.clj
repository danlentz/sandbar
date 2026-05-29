(ns sandbar.scripts.seed-verb-catalog
  "S2 of the MCP-Surface-Mastery arc — seed the `:mm/Verb` catalog from the
   live `verb-catalog` (sandbar.mcp.tools), so the MCP verb surface becomes a
   queryable / BM25F-retrievable / graph-navigable substrate (foundation for
   the affordance map P1 + per-task verb retrieval P2).

   Source of truth is `tools/verb-catalog`; this is a PROJECTION of it.
   Idempotent — `:mm.verb/name` is `:db.unique/identity`, so re-running
   upserts rather than duplicating.

   Passes:
     0. idents   — intern a semantic `:db/ident` per verb (`:sandbar.<axis>/<leaf>`)
        so verbs are addressable by a stable, human/LLM/backend-meaningful id
        rather than only an eid / name-lookup-ref (fixes the BRITTLE-id defect).
     1. entities — one `:mm/Verb` per verb (name/title/description/axis/
        arg-summary + WHICH/WHEN/HOW split out of the description).
     2. combines-with — parse each verb's ORDER:/COMBINATION: prose, resolve the
        referenced verbs, and assert the symmetric `:mm.verb/combines-with`
        related-verb graph (the retrieval-expansion lever).
     3. prereq-of — DIRECTIONAL, HIGH-PRECISION: a verb whose ORDER: section
        explicitly frames \"prerequisites — …\" gets ref→self edges (the named
        verbs are its prerequisites).  Ambiguous arrow-sequence ordering +
        data-flow ('feeds', 'pre-step for') are NOT guessed from prose — their
        direction is unreliable; they await first-class catalog keys (Phase 1).
        Cycle-safe (a prereq-of cycle = planning deadlock → skip the closing edge).
        `:produces-input-for` likewise awaits first-class keys.

   Usage:
     lein run -m sandbar.scripts.seed-verb-catalog        ; separate peer → prod DB
     (sandbar.scripts.seed-verb-catalog/seed!)            ; in-JVM via nREPL (live conn)"
  (:require [clojure.string      :as str]
            [datomic.api         :as d]
            [sandbar.mcp.tools   :as tools]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic  :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Parsing helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn axis-of
  "\"sandbar.entity.find\" -> :entity (second dotted segment)."
  [verb-name]
  (let [segs (str/split (str verb-name) #"\.")]
    (when (>= (count segs) 2) (keyword (nth segs 1)))))

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

(defn arg-summary
  "Distill an :inputSchema into a lean arg string: required params get a `*`."
  [input-schema]
  (let [props (some-> input-schema :properties keys)
        req   (set (map name (or (:required input-schema) [])))]
    (if (empty? props)
      "(no args)"
      (->> props
           (map name)
           sort
           (map (fn [p] (if (contains? req p) (str p "*") p)))
           (str/join ", ")))))

(def ^:private section-markers ["WHICH" "WHEN" "HOW" "ORDER" "COMBINATION"])

(defn split-sections
  "Slice a verb :description into its {:which :when :how :order :combination}
   sections by the literal `MARKER:` boundaries (order-independent)."
  [description]
  (let [d (str description)
        hits (->> section-markers
                  (keep (fn [m] (when-let [i (str/index-of d (str m ":"))] [m i])))
                  (sort-by second)
                  vec)]
    (into {}
          (map-indexed
           (fn [n [m start]]
             (let [end (or (some-> (get hits (inc n)) second) (count d))
                   seg (subs d (+ start (count m) 1) end)]
               [(keyword (str/lower-case m)) (str/trim seg)]))
           hits))))

(def ^:private verb-names (delay (set (map :name tools/verb-catalog))))

(defn extract-refs
  "Find verb names referenced in `text`. Returns {:resolved #{names} :unresolved #{names}}.
   Grabs dotted tokens, normalizes short-forms (`entity.find` -> `sandbar.entity.find`),
   keeps known verbs as :resolved and sandbar-shaped-but-unknown as :unresolved."
  [text self-name]
  (if (str/blank? (str text))
    {:resolved #{} :unresolved #{}}
    (let [toks   (re-seq #"[a-zA-Z][\w-]*(?:\.[\w-]+)+" text)
          norm   (fn [t] (if (str/starts-with? t "sandbar.") t (str "sandbar." t)))
          cands  (->> toks
                      (map norm)
                      (filter #(re-matches #"sandbar\.[\w-]+\.[\w-]+" %))
                      set)
          resolved   (disj (set (filter @verb-names cands)) self-name)
          unresolved (disj (set (remove @verb-names cands)) self-name)]
      {:resolved resolved :unresolved unresolved})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seeding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn verb->slots
  "Build the :mm/Verb scalar slot-map for one verb-catalog entry — incl. the
   derived MCP behavioral hints (via sandbar.mcp.tools/verb-behavioral-hints,
   the SAME derivation that feeds the wire annotations in handle-list)."
  [{:keys [name title description inputSchema]}]
  (let [secs (split-sections description)
        {:keys [read-only? destructive? idempotent? open-world?
                transition-kind hint-status]} (tools/verb-behavioral-hints name)]
    (cond-> {:mm.verb/name            name
             :mm.verb/title           (str title)
             :mm.verb/description     (str description)
             :mm.verb/arg-summary     (arg-summary inputSchema)
             :mm.verb/read-only?      read-only?
             :mm.verb/destructive?    destructive?
             :mm.verb/idempotent?     idempotent?
             :mm.verb/open-world?     open-world?
             :mm.verb/transition-kind transition-kind
             :mm.verb/hint-status     hint-status}
      (axis-of name)   (assoc :mm.verb/axis (axis-of name))
      (:which secs)    (assoc :mm.verb/which (:which secs))
      (:when secs)     (assoc :mm.verb/when  (:when secs))
      (:how secs)      (assoc :mm.verb/how   (:how secs)))))

(defn- seed-entities!
  "Pass 1 — upsert one :mm/Verb per catalog entry (validated via dt/make)."
  []
  (doseq [v tools/verb-catalog]
    (dt/make :mm/Verb (verb->slots v)))
  (count tools/verb-catalog))

(defn- seed-idents!
  "Pass 0 — intern a semantic :db/ident per verb (:sandbar.<axis>/<leaf>) via
   unique-identity upsert on :mm.verb/name.  Idempotent (re-asserting the same
   ident is a no-op).  Returns {:idents n}."
  []
  (let [conn  (db/conn)
        pairs (keep (fn [{:keys [name]}]
                      (when-let [id (verb-ident name)] [name id]))
                    tools/verb-catalog)]
    (doseq [[name id] pairs]
      (d/transact conn [{:mm.verb/name name :db/ident id}]))
    {:idents (count pairs)}))

(defn- combine-adjacency
  "Build undirected name->#{neighbor-names} from every verb's ORDER+COMBINATION
   refs. Returns [adjacency unresolved-set]."
  []
  (reduce
   (fn [[adj unres] {:keys [name description]}]
     (let [secs (split-sections description)
           {ro :resolved uo :unresolved} (extract-refs (:order secs) name)
           {rc :resolved uc :unresolved} (extract-refs (:combination secs) name)
           nbrs (into ro rc)
           adj' (reduce (fn [a n]
                          (-> a
                              (update name (fnil conj #{}) n)
                              (update n (fnil conj #{}) name)))   ; symmetric
                        adj nbrs)]
       [adj' (into unres (into uo uc))]))
   [{} #{}]
   tools/verb-catalog))

(defn- seed-edges!
  "Pass 2 — assert the symmetric :mm.verb/combines-with graph via lookup-ref
   upsert (one tx per verb that has neighbors). Returns edge-pair count."
  []
  (let [[adj unresolved] (combine-adjacency)
        conn (db/conn)]
    (doseq [[verb-name neighbors] adj :when (seq neighbors)]
      (d/transact conn
        [{:mm.verb/name verb-name
          :mm.verb/combines-with (mapv (fn [n] [:mm.verb/name n]) neighbors)}]))
    {:verbs-with-edges (count (filter (comp seq val) adj))
     :edge-pairs (quot (reduce + (map (comp count val) adj)) 2)
     :unresolved (vec (sort unresolved))}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pass 3 — DIRECTIONAL :mm.verb/prereq-of from the ORDER: section
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn order-prereqs
  "Verb-refs that V's ORDER: section EXPLICITLY frames as prerequisites of V.
   HIGH-PRECISION (correct-direction over complete): fires only when the ORDER
   text carries a \"prerequisite\" cue and is NOT a \"no prerequisites\" /
   \"leaf-call\" shape; then every resolved verb-ref in the ORDER section is a
   prerequisite OF V (edge ref→V).  Arrow-sequence ordering + data-flow
   ('feeds', 'pre-step for') are deliberately NOT inferred from prose — their
   direction is ambiguous and was the source of wrong-direction edges; they
   await first-class catalog keys (Phase 1 of the maximization arc).
   Returns {:prereqs #{names} :unresolved #{names}}."
  [order-text self-name]
  (let [t  (str order-text)
        lc (str/lower-case t)]
    (if (or (str/blank? t)
            (not (str/includes? lc "prerequisite"))
            (str/includes? lc "no prerequisite")
            (str/includes? lc "leaf-call")
            (str/includes? lc "leaf call"))
      {:prereqs #{} :unresolved #{}}
      (let [{:keys [resolved unresolved]} (extract-refs t self-name)]
        {:prereqs resolved :unresolved unresolved}))))

(defn- reaches?
  "Can `src` reach `dst` in directed adjacency `g` (node→#{successors})?  DFS."
  [g src dst]
  (loop [stack [src] seen #{}]
    (if (empty? stack)
      false
      (let [[x & more] stack]
        (cond
          (= x dst) true
          (seen x)  (recur (vec more) seen)
          :else     (recur (into (vec more) (get g x #{})) (conj seen x)))))))

(defn- prereq-adjacency
  "Directed prereq graph src->#{dst} (edge src→dst ≙ 'src is prerequisite-of dst'),
   built from every verb's ORDER: section.  Cycle-safe: skips any edge that would
   close a cycle.  Returns {:g adj :skipped [[src dst]...] :unresolved #{}}."
  []
  (reduce
   (fn [acc {:keys [name description]}]
     (let [secs  (split-sections description)
           {:keys [prereqs unresolved]} (order-prereqs (:order secs) name)
           edges (map (fn [p] [p name]) prereqs)]   ; p prereq-of name  (p→name)
       (reduce (fn [acc2 [src dst]]
                 (if (or (= src dst) (reaches? (:g acc2) dst src))
                   (update acc2 :skipped conj [src dst])
                   (update-in acc2 [:g src] (fnil conj #{}) dst)))
               (update acc :unresolved into unresolved)
               edges)))
   {:g {} :skipped [] :unresolved #{}}
   tools/verb-catalog))

(defn- seed-prereq-edges!
  "Pass 3 — REPLACE :mm.verb/prereq-of with the high-precision directional graph.
   Cardinality-many assertions are ADDITIVE, so first RETRACT all existing
   prereq-of datoms (clean replace — otherwise a changed heuristic leaves stale
   edges).  src→dst ≙ src :prereq-of dst."
  []
  (let [{:keys [g skipped unresolved]} (prereq-adjacency)
        conn     (db/conn)
        existing (d/q '[:find ?e ?t :where [?e :mm.verb/prereq-of ?t]] (d/db conn))]
    (when (seq existing)
      (d/transact conn (mapv (fn [[e t]] [:db/retract e :mm.verb/prereq-of t]) existing)))
    (doseq [[src dsts] g :when (seq dsts)]
      (d/transact conn
        [{:mm.verb/name src
          :mm.verb/prereq-of (mapv (fn [d] [:mm.verb/name d]) dsts)}]))
    {:prereq-verbs  (count (filter (comp seq val) g))
     :prereq-edges  (reduce + 0 (map (comp count val) g))
     :retracted     (count existing)
     :skipped-cycles skipped
     :unresolved    (vec (sort unresolved))}))

(defn seed!
  "Seed (idempotently) the :mm/Verb catalog into the current substrate
   (uses the live `db/**conn*`). Returns a summary map."
  []
  (let [n      (seed-entities!)
        idents (seed-idents!)
        edges  (seed-edges!)
        prereq (seed-prereq-edges!)]
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
