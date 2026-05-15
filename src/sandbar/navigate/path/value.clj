(ns sandbar.navigate.path.value
  "Sandbar Path-Grammar — Paths as First-Class Values (Stage P-5 of
  comprehensive memory-model MCP arc per
  plans/sandbar_fulltext_search_substrate_arc_2026_05_13.md +
  decisions/query_engine_architectural_cornerstone_2026_05_11.md D9).

  Establishes the path-data abstraction + predicate operations so paths
  can be returned, compared, composed, and queried — not just used as
  reachability witnesses.

  ## Path data shape

      {:nodes [<node-0> <node-1> ... <node-N>]              ; N+1 nodes
       :edges [<edge-1> <edge-2> ... <edge-N>]}             ; N edges

  Each edge:

      {:predicate <pred-ident>
       :direction :forward | :inverse}

  Invariant: `(= (count :nodes) (inc (count :edges)))`.  A path of
  length 0 has one node and no edges (the singleton/identity path).

  ## Compiler integration

  Stage P-5 ships path-data + predicates only.  The compiler integration
  — surfacing path data via `:include [:paths]` opt — lands at P-6 with
  the `sandbar.navigate.path/path-via` consumer-facing API.

  P-5 abstraction is consumed by P-6 and by downstream client code
  that walks/projects path results (e.g., MCP/REST result projection,
  corpus migration adapter at P-7)."
  (:refer-clojure :exclude [reverse]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Path construction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn singleton
  "Construct the identity-length path containing a single node."
  [node]
  {:nodes [node] :edges []})

(defn make
  "Construct a path from explicit nodes + edges.  Validates the
   invariant `(count :nodes) = (count :edges) + 1`."
  [nodes edges]
  (when-not (= (count nodes) (inc (count edges)))
    (throw (ex-info
             (str "Invalid path: (count :nodes) must equal "
                  "(count :edges) + 1; got "
                  (count nodes) " nodes vs " (count edges) " edges")
             {:nodes nodes :edges edges})))
  {:nodes (vec nodes) :edges (vec edges)})

(defn make-edge
  "Construct an edge record."
  ([predicate]
   (make-edge predicate :forward))
  ([predicate direction]
   {:pre [(#{:forward :inverse} direction)]}
   {:predicate predicate :direction direction}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Schema validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn valid?
  "Predicate: is `path` a well-formed path-value?"
  [path]
  (and (map? path)
       (vector? (:nodes path))
       (vector? (:edges path))
       (= (count (:nodes path)) (inc (count (:edges path))))
       (every? (fn [edge]
                 (and (map? edge)
                      (contains? edge :predicate)
                      (#{:forward :inverse} (:direction edge))))
               (:edges path))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Accessors
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn length
  "Number of edges in the path (path length / hop count)."
  [path]
  (count (:edges path)))

(defn empty-path?
  "True if `path` has zero edges (singleton path)."
  [path]
  (zero? (length path)))

(defn nodes
  "Return the nodes vec."
  [path]
  (:nodes path))

(defn edges
  "Return the edges vec."
  [path]
  (:edges path))

(defn start-node
  "First node in the path."
  [path]
  (first (:nodes path)))

(defn end-node
  "Last node in the path."
  [path]
  (last (:nodes path)))

(defn node-at
  "Return the node at position `i` (0-indexed); nil if out of range."
  [path i]
  (get (:nodes path) i))

(defn edge-at
  "Return the edge at position `i` (0-indexed; 0 ≤ i < length); nil
   if out of range."
  [path i]
  (get (:edges path) i))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Composition
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn extend-path
  "Extend `path` by one edge to a new node.  Returns the longer path."
  [path edge new-node]
  {:nodes (conj (:nodes path) new-node)
   :edges (conj (:edges path) edge)})

(defn concat-paths
  "Concatenate two paths.  The last node of `p1` must equal the first
   node of `p2`; the duplicate is dropped at the join.  Throws ex-info
   if the join nodes differ."
  [p1 p2]
  (when-not (= (end-node p1) (start-node p2))
    (throw (ex-info
             "concat-paths join mismatch: end of p1 != start of p2"
             {:p1-end (end-node p1) :p2-start (start-node p2)})))
  {:nodes (into (:nodes p1) (rest (:nodes p2)))
   :edges (into (:edges p1) (:edges p2))})

(defn reverse
  "Reverse the path: swap node order and flip each edge's :direction.

   `(reverse (reverse p))` ≡ `p` (involutive)."
  [path]
  {:nodes (vec (clojure.core/reverse (:nodes path)))
   :edges (vec (clojure.core/reverse
                 (mapv (fn [edge]
                         (update edge :direction
                                 {:forward :inverse :inverse :forward}))
                       (:edges path))))})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates — prefix? / suffix? / subpath? — Cypher-style path
;; relations for filtering, deduplication, and query composition.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn prefix?
  "True if `p1` is a prefix of `p2` — i.e., the first `(length p1)`
   edges of `p2` are exactly `p1`'s edges, and the corresponding
   nodes match."
  [p1 p2]
  (let [n1-len (length p1)
        n2-len (length p2)]
    (and (<= n1-len n2-len)
         (= (:nodes p1) (subvec (:nodes p2) 0 (inc n1-len)))
         (= (:edges p1) (subvec (:edges p2) 0 n1-len)))))

(defn suffix?
  "True if `p1` is a suffix of `p2`."
  [p1 p2]
  (let [n1-len (length p1)
        n2-len (length p2)]
    (and (<= n1-len n2-len)
         (= (:nodes p1)
            (subvec (:nodes p2) (- n2-len n1-len)))
         (= (:edges p1)
            (subvec (:edges p2) (- n2-len n1-len))))))

(defn subpath?
  "True if `p1` is a contiguous subpath of `p2` — i.e., `p1`'s
   sequence of nodes + edges appears intact at some position within
   `p2`.  Includes the trivial cases: any path is a subpath of itself;
   the singleton at any node of `p2` is a subpath."
  [p1 p2]
  (let [n1-len (length p1)
        n2-len (length p2)]
    (cond
      (> n1-len n2-len)
      false

      (zero? n1-len)
      ;; Singleton p1: check if its node appears anywhere in p2
      (contains? (set (:nodes p2)) (start-node p1))

      :else
      (boolean
        (some (fn [start]
                (and (= (:nodes p1)
                        (subvec (:nodes p2) start (+ start (inc n1-len))))
                     (= (:edges p1)
                        (subvec (:edges p2) start (+ start n1-len)))))
              (range (inc (- n2-len n1-len))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Projection helpers — extract predicate-only or node-only views.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn predicates
  "Sequence of predicate idents along the path, in walk order."
  [path]
  (mapv :predicate (:edges path)))

(defn directions
  "Sequence of direction keywords along the path, in walk order."
  [path]
  (mapv :direction (:edges path)))
