(ns sandbar.codec.ordered-map
  "Insertion-ordered map abstraction for codec wire-format fidelity.

   The sandbar codec layer needs a map type whose iteration order matches
   INSERTION ORDER so source frontmatter key-ordering round-trips through
   parse + emit per
   `decisions/markdown_as_canonical_sandbar_export_format_2026_05_12.md`
   M.3.  Clojure's `array-map` promotes to PHM at 16+ entries (losing
   order); `sorted-map` requires a key comparator (wrong shape — we want
   insertion order, not sorted-by-key).

   ## Current backend (2026-05-20)

   Backed by `java.util.LinkedHashMap` — Java stdlib insertion-ordered
   Map.  Encapsulated behind this namespace's API; consumers don't see
   the Java backing.

   ## Future backend swap

   Per Dan-directive 2026-05-20 (captured at
   `~/claude/memory/ideas/ordered_collections_insertion_ordered_map_addition_2026_05_20.md`):
   when dco-dev/ordered-collections ships an `insertion-ordered-map`
   type, swap the backend HERE — consumers don't need to change.

   ## API surface

     create        — empty ordered-map
     put!          — add (mutating; returns the same map)
     pairs         — ordered seq of [k v] pairs
     keys-vec      — ordered vec of keys
     ->clojure-map — convert to Clojure hash-map (drops order; for API boundary)
     from-pairs    — build from a seq of [k v] pairs (preserving the seq's order)
     ordered-map?  — predicate

   ## Layering discipline

   This namespace is the SINGLE POINT where the codec touches the
   ordered-map backend.  All other codec code calls these helpers — never
   `java.util.LinkedHashMap` directly.  This is the modularization that
   makes the future backend swap a single-namespace edit.")

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
