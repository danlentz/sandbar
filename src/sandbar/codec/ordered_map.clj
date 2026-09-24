(ns sandbar.codec.ordered-map
  "Insertion-ordered maps for frontmatter key-order fidelity.
   A java.util.LinkedHashMap is encapsulated behind create, put!, pairs,
   keys-vec, from-pairs and ordered-map?. ->clojure-map deliberately drops
   the ordering guarantee. Centralizing the representation lets codecs
   preserve insertion order without depending on one backing map type.")

(defn create
  "Create an empty insertion-ordered map."
  []
  (java.util.LinkedHashMap.))

(defn put!
  "Add k→v to ordered map.  Returns the same map (mutates the backing
   LinkedHashMap).  Idiomatic threaded use:
     (-> (create) (put! :a 1) (put! :b 2) (put! :c 3))."
  [m k v]
  (.put ^java.util.LinkedHashMap m k v)
  m)

(defn pairs
  "Return ordered seq of [k v] pairs preserving insertion order.  Safe to
   pass to `into {}` to build a Clojure map (drops order); or to consumers
   that expect a seq-of-vecs shape."
  [m]
  (mapv (fn [^java.util.Map$Entry e] [(.getKey e) (.getValue e)])
        (.entrySet ^java.util.LinkedHashMap m)))

(defn keys-vec
  "Return ordered vec of keys in insertion order."
  [m]
  (vec (.keySet ^java.util.LinkedHashMap m)))

(defn ->clojure-map
  "Convert to a Clojure hash-map, dropping the order property.  Use at
   API boundaries where downstream consumers expect IPersistentMap (e.g.,
   the codec returns Clojure-native entity-specs to MCP / project-graph
   / etc.).  Order can be RE-ATTACHED via metadata `:codec/key-order`
   on the resulting map so emit-side can recover ordering."
  [m]
  (into {} (pairs m)))

(defn from-pairs
  "Build an ordered-map from a seq of [k v] pairs, preserving the seq's
   iteration order."
  [kvs]
  (let [m (create)]
    (doseq [[k v] kvs]
      (put! m k v))
    m))

(defn ordered-map?
  "Predicate — true if `x` is an instance of the current ordered-map
   backend.  Useful for assertions in codec callsites."
  [x]
  (instance? java.util.LinkedHashMap x))
