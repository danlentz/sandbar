(ns sandbar.bench.synthetic
  "Synthetic-graph factory for Sandbar bench harnesses (F-DF-1
  Phase 1; Phase R Stage R-6).

  Builds a Datomic-backed test DB with N synthetic dt/Class
  instances + a configurable class-hierarchy depth so the bench
  axes (rank-by :degree / :backlink-density / path-via :REP+)
  have predictable cardinalities to time against.

  ## Graph shape

  Two-layer structure:
    1. A linear subclass chain of depth `chain-depth` rooted at
       :dt/Resource (every synthetic class is a transitive
       descendant of :dt/Resource via :dt/subclass-of).
    2. `size` synthetic-class leaves attached to that chain.

  Each leaf carries `:dt/slots` references to a small fan-out of
  property classes; this gives `rank-by :degree` + `:backlink-
  density` per-class scores that grow with chain depth + slot
  fan-out.

  ## Sizes

  Default ladder: 10 / 100 / 1k / 10k.  10k is the upper bound
  (per query-engine cornerstone ADR — Datomic primary-backend
  threshold).  Each rung is its own test DB so cross-size results
  don't pollute each other."
  (:require [datomic.api        :as d]
            [sandbar.db.datatype :as dt]
            [sandbar.db.datomic :as db]))

(defn- synthetic-class-ident
  [i]
  (keyword "bench" (str "Class" i)))

(defn populate!
  "Populate the currently-connected sandbar DB with `size`
   synthetic dt/Class instances under a linear subclass chain of
   `chain-depth` ancestor classes.  Returns a map of populated
   identifiers for downstream bench harnesses:

     {:leaves [<keyword> ...]
      :chain-root <keyword>
      :chain-depth <int>
      :size <int>}

   Requires the standard sandbar test fixture (metamodel loaded;
   `:dt/Class` + `:dt/Resource` available)."
  [{:keys [size chain-depth]
    :or   {chain-depth 3}}]
  (let [conn        (db/conn)
        chain-root  (keyword "bench" "ChainRoot")
        ;; Build the chain: ChainRoot ← Chain1 ← Chain2 ← ... ← ChainN
        chain-keys  (vec (cons chain-root
                               (for [i (range chain-depth)]
                                 (keyword "bench" (str "Chain" i)))))
        chain-txns  (for [[idx ident] (map-indexed vector chain-keys)]
                      (cond-> {:db/ident          ident
                               :dt/type           :dt/Class
                               :dt/label          (name ident)
                               :dt/context        "bench"}
                        (pos? idx)
                        (assoc :dt/subclass-of (nth chain-keys (dec idx)))
                        (zero? idx)
                        (assoc :dt/subclass-of :dt/Resource)))
        ;; Build the leaves: each subclasses-of the last chain-class.
        leaf-keys   (vec (for [i (range size)]
                           (synthetic-class-ident i)))
        last-chain  (last chain-keys)
        leaf-txns   (for [ident leaf-keys]
                      {:db/ident       ident
                       :dt/type        :dt/Class
                       :dt/label       (name ident)
                       :dt/context     "bench"
                       :dt/subclass-of last-chain})]
    ;; Two-pass transact: chain first (so each chain-class's
    ;; :db/ident is in the DB before subsequent transactions
    ;; reference it as a ref-target).  Datomic resolves ident
    ;; references at transaction time; intra-transaction
    ;; forward-reference to an :db/ident being installed in the
    ;; same transact fails.
    (doseq [tx chain-txns]
      @(d/transact conn [tx]))
    @(d/transact conn (vec leaf-txns))
    {:leaves      leaf-keys
     :chain-root  chain-root
     :chain-depth chain-depth
     :size        size}))
