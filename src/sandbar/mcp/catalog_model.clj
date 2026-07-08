(ns sandbar.mcp.catalog-model
  "The pure, DB-free single-source catalog model — F6 consolidation substrate.

   The ONE authored source of truth is `sandbar.mcp.tools/verb-catalog`.  This
   namespace converts that def into a canonical per-verb data model with no
   Datomic connection, reusing the SAME derivations that previously lived
   inside `sandbar.scripts.seed-verb-catalog` (axis / arg-summary / section
   split / combine + prereq prose-parsers) and the SAME leaf classifier
   `sandbar.mcp.tools/verb-behavioral-hints` that feeds the wire
   ToolAnnotations and the read-only authz gate.  Because every projection
   renders from this one model, no projection can disagree with another or with
   the wire surface.

   The three file-backed projections (doc/mcp-affordance-map.md,
   etc/verb-edges.edn in the corpus, the memory-open affordance table) and the
   :mm/Verb substrate seed are all consumers of `build-catalog-model`.  Making
   the model DB-free is what lets the drift gate run in pre-commit / CI without
   a Datomic spin-up.

   Design source of truth:
   audit-results/xminus-build-2026-07-04/CONSOLIDATION-SINGLE-SOURCE-CATALOG-DESIGN.md §5.1.
   The seed-script derivations moved here (not rewritten) per the design's
   'refactor-with-no-behavior-change' mandate (§4, §5.4)."
  (:require [clojure.string    :as str]
            [sandbar.mcp.tools :as tools]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pure derivations — MOVED verbatim from sandbar.scripts.seed-verb-catalog.
;; The seed script now consumes these instead of re-deriving (design §5.4).
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
  "Slice a verb :description into {:which :when :how :order :combination}."
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
  "Find verb names referenced in `text`. {:resolved #{} :unresolved #{}}."
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

(defn order-prereqs
  "Verb-refs V's ORDER: section EXPLICITLY frames as prerequisites of V."
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
  "Can `src` reach `dst` in directed adjacency `g` (node->#{successors})?  DFS."
  [g src dst]
  (loop [stack [src] seen #{}]
    (if (empty? stack)
      false
      (let [[x & more] stack]
        (cond
          (= x dst) true
          (seen x)  (recur (vec more) seen)
          :else     (recur (into (vec more) (get g x #{})) (conj seen x)))))))

(defn combine-adjacency
  "Undirected name->#{neighbor} from every verb's ORDER+COMBINATION refs.
   Returns [adjacency unresolved-set]."
  [catalog]
  (reduce
   (fn [[adj unres] {:keys [name description]}]
     (let [secs (split-sections description)
           {ro :resolved uo :unresolved} (extract-refs (:order secs) name)
           {rc :resolved uc :unresolved} (extract-refs (:combination secs) name)
           nbrs (into ro rc)
           adj' (reduce (fn [a n]
                          (-> a
                              (update name (fnil conj #{}) n)
                              (update n (fnil conj #{}) name)))
                        adj nbrs)]
       [adj' (into unres (into uo uc))]))
   [{} #{}]
   catalog))

(defn prereq-adjacency
  "Directed prereq graph src->#{dst} (src prerequisite-of dst), cycle-safe.
   Returns {:g adj :skipped [[src dst]...] :unresolved #{}}."
  [catalog]
  (reduce
   (fn [acc {:keys [name description]}]
     (let [secs  (split-sections description)
           {:keys [prereqs unresolved]} (order-prereqs (:order secs) name)
           edges (map (fn [p] [p name]) prereqs)]
       (reduce (fn [acc2 [src dst]]
                 (if (or (= src dst) (reaches? (:g acc2) dst src))
                   (update acc2 :skipped conj [src dst])
                   (update-in acc2 [:g src] (fnil conj #{}) dst)))
               (update acc :unresolved into unresolved)
               edges)))
   {:g {} :skipped [] :unresolved #{}}
   catalog))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; build-catalog-model — the ONE canonical model every projection renders from.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn build-catalog-model
  "verb-catalog vector -> sorted vec of per-verb model maps + derived edge
   graphs, DB-free.  Reuses tools/verb-behavioral-hints (the SAME classifier
   feeding the wire annotations + the authz gate), so the model can never
   disagree with either.  Per-verb map:
     {:name :axis :title :description :safety :read-only? :destructive?
      :idempotent? :open-world? :transition-kind :hint-status :arg-summary
      :which :when :how :combines-with :prereqs}
   :combines-with = undirected prose-parsed neighbor names (sorted).
   :prereqs       = INBOUND prereq-of set (verbs prerequisite OF this verb),
                    the same inversion the verb-edges map performs."
  ([] (build-catalog-model tools/verb-catalog))
  ([catalog]
   (let [[combine-adj combine-unres] (combine-adjacency catalog)
         {:keys [g] :as prereq}      (prereq-adjacency catalog)
         prereq-inverse (reduce (fn [m [src dsts]]
                                  (reduce (fn [m2 dst] (update m2 dst (fnil conj #{}) src))
                                          m dsts))
                                {} g)
         verbs (->> catalog
                    (map (fn [{:keys [name title description inputSchema]}]
                           (let [secs  (split-sections description)
                                 hints (tools/verb-behavioral-hints name)]
                             {:name            name
                              :axis            (axis-of name)
                              :title           (str title)
                              :description     (str description)
                              :read-only?      (:read-only? hints)
                              :destructive?    (:destructive? hints)
                              :idempotent?     (:idempotent? hints)
                              :open-world?     (:open-world? hints)
                              :transition-kind (:transition-kind hints)
                              :hint-status     (:hint-status hints)
                              :safety          (case (:transition-kind hints)
                                                 :safe "safe" :idempotent "idem"
                                                 :unsafe "unsafe" "?")
                              :arg-summary     (arg-summary inputSchema)
                              :which           (:which secs)
                              :when            (:when secs)
                              :how             (:how secs)
                              :combines-with   (vec (sort (get combine-adj name #{})))
                              :prereqs         (vec (sort (get prereq-inverse name #{})))})))
                    (sort-by :name)
                    vec)]
     {:verbs              verbs
      :combine-unresolved (vec (sort combine-unres))
      :prereq-unresolved  (vec (sort (:unresolved prereq)))
      :prereq-skipped     (:skipped prereq)})))

(defn catalog-summary
  "{:verb-count :axis-count :by-axis {axis n}} — the SINGLE source of every
   'N verbs / M axes' string in every projection."
  [model]
  (let [by-axis (->> (:verbs model)
                     (group-by :axis)
                     (map (fn [[a vs]] [a (count vs)]))
                     (into (sorted-map)))]
    {:verb-count (count (:verbs model))
     :axis-count (count by-axis)
     :by-axis    by-axis}))
