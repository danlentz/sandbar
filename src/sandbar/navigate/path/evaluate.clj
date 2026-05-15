(ns sandbar.navigate.path.evaluate
  "Sandbar Path-Grammar — Clojure-Side IR Evaluator (Stage P-7 / Phase R
  Stage R-7 of comprehensive memory-model MCP arc per
  plans/sandbar_0_1_0_codex_remediation_2026_05_14.md §R-7).

  Resolves F-DB-1 per Option D + Policy A per
  decisions/sandbar_path_data_reconstruction_option_d_policy_a_2026_05_14.md.

  ## Why Clojure-side evaluation

  The Datomic compiler (`sandbar.navigate.path.datomic`) is excellent
  for reachability — \"which entities can I reach via expression e\" —
  but Datalog recursive rules don't natively track the path that
  produced each endpoint.  Reconstructing paths post-hoc from a flat
  endpoint set is awkward (sub-quadratic blowup; multiple
  reach-paths-per-endpoint demand re-querying).  The cleaner separation:
  let Datomic do single-attribute lookups + valueType filtering (its
  strong suit), let Clojure do composition + path-tracking + cycle
  suppression (its strong suit).  Same shape as `dt/graph-walk-from`
  precedent per
  decisions/sandbar_graph_walk_clojure_bfs_over_datomic_recursive_rules_2026_05_14.md.

  ## Algorithm

  Frontier-as-map `{eid path-value}` threaded through per-operator
  evaluators.  Path values are built via the
  `sandbar.navigate.path.value` substrate (`singleton` / `extend-path`
  / `concat-paths` / `reverse`) — NEVER constructed inline.

  Policy A (one-representative-path-per-endpoint, Cypher
  shortestPath-style): when multiple distinct paths to the same
  endpoint exist, BFS first-arrival wins; subsequent re-arrivals are
  dropped.  Frontier-as-map naturally dedupes within an iteration;
  `first-arrival-merge` enforces leftmost-wins across `:OR` branches
  and `:REP+` / `:REP*` iterations.

  ## Operator coverage (0.1.0)

  Canonical-8 (full path-data evaluation):
    :PREDICATE — atomic forward step (single-attribute Datalog lookup)
    :INV       — atomic inverse step (when child is :PREDICATE)
    :SELF      — identity / singleton frontier at seed
    :ANY       — wildcard predicate, ref-typed (composes with R-2)
    :RESTRICT  — node-position filter at current frontier endpoint
    :SEQ       — fold-left composition; concat-paths joins
    :OR        — union of branch frontiers with first-arrival
    :REP+      — iterative fixpoint (1+); visited-set termination
    :REP*      — iterative fixpoint (0+); seed in initial frontier

  Tier-2 desugar-able (evaluated via canonical-8 expansion):
    :OPT       — desugars to (:OR p :SELF)
    :REP m n   — desugars to (:OR n-length :SEQ chains for n in [m,n])

  Tier-2 not-yet-supported for path-data:
    :NOT / :FILTER / :TEST — throw ex-info; path-data evaluation lands
                              in 0.1.x.  Endpoint-only fast-path still
                              works (callers don't request :include
                              #{:paths}).

  ## Path value shape (from path.value substrate)

      {:nodes [<node-0> <node-1> ... <node-N>]      ; N+1 nodes
       :edges [<edge-1> <edge-2> ... <edge-N>]}     ; N edges

  Internally, nodes are eids during BFS (efficient).  At
  `evaluate-from`'s exit boundary, nodes are projected to the
  canonical {:db/id eid :db/ident ident} entity-summary shape so
  JSON serialization through MCP/REST stays compact + meaningful."
  (:require [datomic.api :as d]
            [sandbar.db.datomic :as db]
            [sandbar.navigate.path.value :as pv]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; First-arrival merge — Policy A enforcement helper
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- first-arrival-merge
  "Merge a sequence of frontiers (each a map `{eid path}`) preserving
   leftmost-wins.  The first frontier in the sequence whose key matches
   wins per Policy A first-arrival.  Used by :OR (branch union) and
   :REP+ / :REP* (iteration union)."
  [frontiers]
  (reduce (fn [acc f]
            (reduce-kv (fn [m k v]
                         (if (contains? m k) m (assoc m k v)))
                       acc
                       f))
          {}
          frontiers))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Atomic-step Datalog queries
;;
;; Each returns a seq of {:from <eid> :to <eid> :edge <edge>} maps —
;; one per matching graph edge from the given from-eids set.  The
;; ref-type guard `[?a :db/valueType :db.type/ref]` is symmetric with
;; the compiler's R-2 fix (compile-any) + the dt/graph-walk-from
;; precedent.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- atomic-forward-step
  "Forward atomic step over predicate `pred` from `from-eids`.
   Returns seq of {:from :to :edge} where :edge is :forward-directioned."
  [db pred from-eids]
  (let [rows (d/q '[:find ?f ?t
                    :in $ ?pred [?f ...]
                    :where [?f ?pred ?t]]
                  db pred (vec from-eids))]
    (map (fn [[f t]]
           {:from f :to t :edge (pv/make-edge pred :forward)})
         rows)))

(defn- atomic-inverse-step
  "Inverse atomic step over predicate `pred` from `from-eids`.
   Returns seq of {:from :to :edge} where :edge is :inverse-directioned.
   Datalog: ?from is the OBJECT position; ?to is the SUBJECT position."
  [db pred from-eids]
  (let [rows (d/q '[:find ?f ?t
                    :in $ ?pred [?f ...]
                    :where [?t ?pred ?f]]
                  db pred (vec from-eids))]
    (map (fn [[f t]]
           {:from f :to t :edge (pv/make-edge pred :inverse)})
         rows)))

(defn- atomic-any-step
  "Wildcard atomic step from `from-eids` — every ref-typed outbound
   edge.  Mirrors compile-any's ref-type guard (R-2 fix) at runtime.
   Returns seq of {:from :to :edge} with the predicate ident extracted
   from each row's attribute eid."
  [db from-eids]
  (let [rows (d/q '[:find ?f ?a ?t
                    :in $ [?f ...]
                    :where
                    [?f ?a ?t]
                    [?a :db/valueType :db.type/ref]]
                  db (vec from-eids))]
    (map (fn [[f a t]]
           (let [pred-ident (or (:db/ident (d/entity db a)) a)]
             {:from f :to t :edge (pv/make-edge pred-ident :forward)}))
         rows)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Frontier-step helper — apply atomic-step results to a starting
;; frontier, extending each match's `:from` path by the matched edge.
;;
;; Policy A: first-arrival wins.  If multiple `:from` eids in the
;; current frontier lead to the same `:to` eid, the first match
;; encountered wins (frontier-as-map natural behavior).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- extend-frontier
  "Apply step-results to `frontier` — for each {:from :to :edge}
   step, extend frontier[:from]'s path by the edge to reach :to.
   Returns the new frontier."
  [frontier step-results]
  (reduce (fn [acc {:keys [from to edge]}]
            (if (contains? acc to)
              acc  ; Policy A — first-arrival wins
              (let [parent-path (get frontier from)
                    new-path    (pv/extend-path parent-path edge to)]
                (assoc acc to new-path))))
          {}
          step-results))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Per-operator evaluators.
;;
;; Contract: each takes (ast db frontier) → result-frontier.
;; `frontier` is a map {eid path-value} where each path's end-node
;; equals the eid (invariant maintained by extend-frontier +
;; constructors).
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(declare evaluate-node)

(defn- evaluate-predicate
  "(:PREDICATE p) — atomic forward step."
  [ast db frontier]
  (let [pred (:predicate ast)]
    (extend-frontier frontier (atomic-forward-step db pred (keys frontier)))))

(defn- evaluate-inv
  "(:INV child) — invert the child's traversal direction.

   The IR canonicalizer pushes :INV down to atoms; in canonical form
   the only :INV nodes have :PREDICATE children.  We handle that
   leaf case explicitly (atomic-inverse-step) and fall back to a
   general inversion for defensive coverage of non-canonical input
   (e.g., :INV :ANY surviving canonicalization)."
  [ast db frontier]
  (let [child (first (:args ast))]
    (case (:op child)
      :PREDICATE
      (extend-frontier frontier
                       (atomic-inverse-step db (:predicate child) (keys frontier)))

      ;; :INV :ANY — symmetric (any ref edge in either direction);
      ;; emit inverse-step's wildcard equivalent.  Currently fold
      ;; into the same query but flip :direction at edge construction.
      :ANY
      (let [rows (d/q '[:find ?f ?a ?t
                        :in $ [?f ...]
                        :where
                        [?t ?a ?f]
                        [?a :db/valueType :db.type/ref]]
                      db (vec (keys frontier)))]
        (extend-frontier frontier
                         (map (fn [[f a t]]
                                (let [pred-ident (or (:db/ident (d/entity db a)) a)]
                                  {:from f :to t
                                   :edge  (pv/make-edge pred-ident :inverse)}))
                              rows)))

      ;; Defensive: non-canonical :INV — canonicalizer should have
      ;; pushed :INV down through :SEQ/:OR; if we see it here, fail
      ;; loudly rather than silently produce wrong paths.
      (throw (ex-info
               (str ":INV with non-canonical child :op " (:op child)
                    " — canonicalizer should push :INV to atoms.")
               {:ast ast :child-op (:op child)})))))

(defn- evaluate-self
  "(:SELF) — identity / zero-application.  Returns the frontier
   unchanged.  When called at top level with a single-eid frontier,
   this is the singleton path containing just the seed."
  [_ast _db frontier]
  frontier)

(defn- evaluate-any
  "(:ANY) — wildcard forward step over ref-typed attributes.
   Mirrors compile-any's R-2 ref-type guard."
  [_ast db frontier]
  (extend-frontier frontier (atomic-any-step db (keys frontier))))

(defn- evaluate-restrict
  "(:RESTRICT [pred value]) — keep frontier entries whose endpoint
   has [pred value] in the DB.  Doesn't advance the walk; filters
   in place."
  [ast db frontier]
  (let [[pred value] (:target ast)
        eids         (keys frontier)
        matches      (->> (d/q '[:find [?e ...]
                                 :in $ ?pred ?value [?e ...]
                                 :where [?e ?pred ?value]]
                               db pred value (vec eids))
                          set)]
    (select-keys frontier matches)))

(defn- evaluate-seq
  "(:SEQ p₁ p₂ ... pₙ) — fold-left through operands; final frontier
   is the composition of all steps."
  [ast db frontier]
  (reduce (fn [acc child]
            (evaluate-node child db acc))
          frontier
          (:args ast)))

(defn- evaluate-or
  "(:OR p₁ p₂ ... pₙ) — evaluate each branch independently from the
   same starting frontier, then union with first-arrival.  Policy A:
   leftmost branch's endpoint wins if multiple branches reach it."
  [ast db frontier]
  (let [branch-frontiers (mapv (fn [child]
                                 (evaluate-node child db frontier))
                               (:args ast))]
    (first-arrival-merge branch-frontiers)))

(defn- evaluate-rep+
  "(:REP+ p) — iterative fixpoint, 1+ applications.  BFS from current
   frontier; at each iteration, apply p once + accumulate.  Visited-set
   suppresses re-arrivals; loop terminates when no new endpoints.

   Policy A: first iteration to reach an endpoint wins (BFS naturally
   yields shortest-path)."
  [ast db frontier]
  (let [child (first (:args ast))]
    (loop [active     frontier   ; eids to step from this iteration
           accumulated {}         ; all (eid → path) discovered so far
           iter-guard 0]          ; defensive iteration cap
      (cond
        ;; Defensive: cap at a reasonable iteration count to catch
        ;; pathological loops the visited-set should already preclude.
        (> iter-guard 10000)
        (throw (ex-info ":REP+ iteration cap exceeded — possible cycle escape"
                        {:accumulated-count (count accumulated)
                         :active-count (count active)}))

        (empty? active)
        accumulated

        :else
        (let [stepped (evaluate-node child db active)
              ;; Drop any endpoint already accumulated — Policy A.
              new     (apply dissoc stepped (keys accumulated))]
          (recur new
                 (first-arrival-merge [accumulated new])
                 (inc iter-guard)))))))

(defn- evaluate-rep*
  "(:REP* p) — iterative fixpoint, 0+ applications.  Identical to
  :REP+ except the seed-frontier is INCLUDED in results (the
  identity / zero-application case)."
  [ast db frontier]
  (let [child   (first (:args ast))
        ;; REP+ over the same child captures the 1+ applications.
        plus-results (evaluate-rep+ {:op :REP+ :args [child]} db frontier)]
    ;; Union with the seed-frontier (zero applications); first-arrival
    ;; means the seed wins if it's also reachable via 1+ applications.
    (first-arrival-merge [frontier plus-results])))

(defn- evaluate-opt
  "(:OPT p) ≡ (:OR p :SELF) — zero-or-one.  Desugar to canonical :OR
   per the compiler's strategy."
  [ast db frontier]
  (evaluate-or {:op :OR :args [(first (:args ast)) {:op :SELF}]} db frontier))

(defn- evaluate-rep-bounded
  "(:REP p min max) — unfold to (:OR n-length :SEQ chains for n in
   [min..max]); reuse :OR evaluator."
  [ast db frontier]
  (let [child (:child ast)
        min-n (:min ast)
        max-n (:max ast)
        alts  (vec
                (for [n (range min-n (inc max-n))]
                  (cond
                    (zero? n) {:op :SELF}
                    (= n 1)   child
                    :else     {:op :SEQ :args (vec (repeat n child))})))]
    (cond
      (empty? alts)
      (throw (ex-info "(:REP p min max) requires valid bounds"
                      {:min min-n :max max-n}))

      (= 1 (count alts))
      (evaluate-node (first alts) db frontier)

      :else
      (evaluate-or {:op :OR :args alts} db frontier))))

(defn- unsupported-op
  "Throw a descriptive ex-info for Tier-2 operators not yet supported
   for path-data evaluation (:NOT / :FILTER / :TEST).  Endpoint-only
   evaluation still works through the compiler; only the
   `:include #{:paths}` request path goes through this evaluator."
  [ast]
  (throw (ex-info
           (str "Path-data evaluation not yet supported for operator "
                (:op ast)
                " — Canonical-8 + Tier-2 desugarable (:OPT / :REP m n) "
                "only in 0.1.0.  Full Tier-2 :NOT / :FILTER / :TEST "
                "path-data lands in 0.1.x.  For endpoint-only "
                "reachability via these operators, omit :include #{:paths}.")
           {:op (:op ast) :ast ast})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dispatch
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- evaluate-node
  [ast db frontier]
  (case (:op ast)
    :PREDICATE (evaluate-predicate    ast db frontier)
    :INV       (evaluate-inv          ast db frontier)
    :SELF      (evaluate-self         ast db frontier)
    :ANY       (evaluate-any          ast db frontier)
    :RESTRICT  (evaluate-restrict     ast db frontier)
    :SEQ       (evaluate-seq          ast db frontier)
    :OR        (evaluate-or           ast db frontier)
    :REP+      (evaluate-rep+         ast db frontier)
    :REP*      (evaluate-rep*         ast db frontier)
    :OPT       (evaluate-opt          ast db frontier)
    :REP       (evaluate-rep-bounded  ast db frontier)
    (:NOT :FILTER :TEST) (unsupported-op ast)
    (throw (ex-info (str "Unknown path operator: " (:op ast))
                    {:ast ast}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Node projection — convert eid-keyed path nodes to JSON-friendly
;; entity summaries so MCP/REST serialization stays compact +
;; meaningful.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- project-node
  "Project an eid to a {:db/id :db/ident} summary map (omit :db/ident
   for anonymous entities)."
  [db eid]
  (let [ent (d/entity db eid)]
    (cond-> {:db/id eid}
      (:db/ident ent) (assoc :db/ident (:db/ident ent)))))

(defn- project-path-nodes
  "Replace each eid in `path`'s :nodes with its entity-summary."
  [db path]
  (update path :nodes (fn [nodes] (mapv #(project-node db %) nodes))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public entry: evaluate-from
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn evaluate-from
  "Walk canonical IR `ast` starting from `seed-eid`; return a vec of
   `{:eid <eid> :path <path-value>}` maps for every reached endpoint.

   The seed itself is INCLUDED in results iff the operator includes
   identity (e.g., :SELF or :REP*); evaluator semantics drive
   inclusion — no special-casing at the boundary.

   Path nodes are projected to JSON-friendly entity summaries
   ({:db/id :db/ident}) at the boundary; edges are already
   serialization-friendly ({:predicate :direction}).

   Caller is responsible for entity-map projection of `:eid` if the
   full entity is desired (cf. sandbar.navigate.path/path-via, which
   composes evaluate-from with its entity-projection)."
  [db ast seed-eid]
  (let [initial-frontier {seed-eid (pv/singleton seed-eid)}
        result-frontier  (evaluate-node ast db initial-frontier)]
    (mapv (fn [[eid path]]
            {:eid  eid
             :path (project-path-nodes db path)})
          result-frontier)))
