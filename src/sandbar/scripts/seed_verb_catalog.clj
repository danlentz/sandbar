(ns sandbar.scripts.seed-verb-catalog
  "S2 of the MCP-Surface-Mastery arc — seed the `:mm/Verb` catalog from the
   live `verb-catalog` (sandbar.mcp.tools), so the MCP verb surface becomes a
   queryable / BM25F-retrievable / graph-navigable substrate (foundation for
   the affordance map P1 + per-task verb retrieval P2).

   Source of truth is `tools/verb-catalog`; this is a PROJECTION of it.
   Idempotent — `:mm.verb/name` is `:db.unique/identity`, so re-running
   upserts rather than duplicating.

   Two passes:
     1. entities — one `:mm/Verb` per verb (name/title/description/axis/
        arg-summary + WHICH/WHEN/HOW split out of the description).
     2. edges    — parse each verb's ORDER:/COMBINATION: prose, resolve the
        referenced verbs, and assert the symmetric `:mm.verb/combines-with`
        related-verb graph (the retrieval-expansion lever). Directed
        `:prereq-of` / `:produces-input-for` are declared but left for an
        S4 refinement (prose direction is ambiguous; combines-with is
        direction-agnostic and correct for graph expansion).

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
  "Build the :mm/Verb scalar slot-map for one verb-catalog entry."
  [{:keys [name title description inputSchema]}]
  (let [secs (split-sections description)]
    (cond-> {:mm.verb/name        name
             :mm.verb/title       (str title)
             :mm.verb/description (str description)
             :mm.verb/arg-summary (arg-summary inputSchema)}
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

(defn seed!
  "Seed (idempotently) the :mm/Verb catalog into the current substrate
   (uses the live `db/**conn*`). Returns a summary map."
  []
  (let [n     (seed-entities!)
        edges (seed-edges!)]
    {:seeded n
     :verbs-with-edges (:verbs-with-edges edges)
     :edge-pairs (:edge-pairs edges)
     :flagged (:unresolved edges)}))

(defn -main
  "Separate-peer entry: connect to the configured (prod) DB, ensure schema
   (incl. :mm-verb) is loaded, seed, print summary."
  [& _]
  (reset! db/**conn* (db/conn (db/db-uri)))
  (db/load-all-schema! (db/db-uri))
  (let [summary (seed!)]
    (println "seed-verb-catalog ✓" (pr-str (dissoc summary :flagged)))
    (when (seq (:flagged summary))
      (println "FLAGGED unresolved refs (review for S4):" (pr-str (:flagged summary))))
    (shutdown-agents)
    (System/exit 0)))
