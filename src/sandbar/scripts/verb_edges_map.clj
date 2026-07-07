(ns sandbar.scripts.verb-edges-map
  "Project the verb catalog -> a compact EDN edges-map consumed by the
   PreToolUse composition hook (etc/hooks/inject_verb_composition.bb in the
   client corpus).  Shape:

     {\"sandbar.entity.create\" {:read-only? false
                                 :transition-kind :unsafe
                                 :prereqs [\"sandbar.schema.classes\" ...]
                                 :combines-with [\"sandbar.entity.validate\" ...]}}

   :prereqs is the INBOUND prereq-of set (the verbs that are prerequisites OF
   this verb).  The hook surfaces these before NON-read-only verbs (selective →
   low-noise).  M4 of the mm/Verb-catalog-maximization arc.  Read-only projection.

   DB-FREE as of F6: builds from `sandbar.mcp.catalog-model/build-catalog-model`
   (a pure fn of the wire `verb-catalog` def), NOT from full-projected :mm/Verb
   DB instances.  The model already carries :combines-with (undirected) and
   :prereqs (the inbound inversion), computed once in one place.  This closes
   the drift that let the edges map lag at 81 while the catalog grew to 82 —
   the composition hook was consulting a stale map with no entry for the
   actively-called `entity.retract`.  The drift gate
   (sandbar.scripts.catalog-check) diffs this output against the committed
   etc/verb-edges.edn.  Output formatting is pinned (right-margin 72) for
   byte-reproducibility.

   Usage: lein verb-edges-map > $HOME/claude/etc/verb-edges.edn"
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
