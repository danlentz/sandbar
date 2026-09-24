(ns sandbar.scripts.verb-edges-map
  "Render a compact EDN map of verb hints and composition edges without a
   database. Each verb maps to :read-only?, :transition-kind, :prereqs, and
   :combines-with. Prerequisites are inbound: the verbs needed before this verb.

   The shared catalog model derives related and prerequisite edges. Sorted
   entries and fixed print settings produce reproducible output for a client
   composition hook. Usage: lein verb-edges-map > <review-output.edn>
   Review the result before updating a consumer's generated artifact."
  (:require [clojure.pprint            :as pp]
            [sandbar.mcp.catalog-model :as model]))

(defn build-edges-map
  "Build {verb-name -> {:read-only? :transition-kind :prereqs :combines-with}}
   sorted-map from a catalog-model."
  [m]
  (into (sorted-map)
        (for [v (:verbs m)]
          [(:name v)
           {:read-only?      (boolean (:read-only? v))
            :transition-kind (:transition-kind v)
            :prereqs         (vec (sort (:prereqs v)))
            :combines-with   (vec (sort (:combines-with v)))}])))

(defn render
  "Catalog-model -> the byte-stable EDN string (pinned pprint settings)."
  [m]
  (binding [pp/*print-right-margin* 72
            *print-length*          nil
            *print-level*           nil]
    (with-out-str (pp/pprint (build-edges-map m)))))

(defn -main
  "Project the verb catalog (DB-free) and pprint the edges-map EDN to stdout."
  [& _]
  (print (render (model/build-catalog-model)))
  (flush)
  (shutdown-agents)
  (System/exit 0))
