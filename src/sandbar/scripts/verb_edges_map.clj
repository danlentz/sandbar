(ns sandbar.scripts.verb-edges-map
  "Project the :mm/Verb catalog → a compact EDN edges-map consumed by the
   PreToolUse composition hook (etc/hooks/inject_verb_composition.bb in the
   client corpus).  Shape:

     {\"sandbar.entity.create\" {:read-only? false
                                 :transition-kind :unsafe
                                 :prereqs [\"sandbar.schema.classes\" ...]
                                 :combines-with [\"sandbar.entity.validate\" ...]}}

   :prereqs is the INBOUND prereq-of set (the verbs that are prerequisites OF
   this verb) — built by inverting each verb's outbound :mm.verb/prereq-of.  The
   hook surfaces these before NON-read-only verbs (selective → low-noise).
   M4 of the mm/Verb-catalog-maximization arc.  Read-only projection.

   NB: ref slots must be read through projection/full-projection — the raw
   dt/all-instances-of entity-maps return ref targets as eids (not name-bearing).

   Usage: lein verb-edges-map > $HOME/claude/etc/verb-edges.edn"
  (:require [clojure.pprint         :as pp]
            [clojure.string         :as str]
            [sandbar.api.projection :as projection]
            [sandbar.db.datatype    :as dt]
            [sandbar.db.datomic     :as db]))

(defn- ident->wire
  "A prereq-of / combines-with target → the verb wire-name.
   :sandbar.class/slots OR \"sandbar.class/slots\" → \"sandbar.class.slots\"."
  [t]
  (let [s (if (map? t) (str (or (:mm.verb/name t) (:db/ident t))) (str t))]
    (-> s (str/replace #"^:" "") (str/replace "/" "."))))

(defn build-edges-map
  "Build {verb-name → {:read-only? :transition-kind :prereqs :combines-with}}
   from FULL-PROJECTED :mm/Verb maps (ref slots rendered as target idents).
   :prereqs is the inverse of outbound prereq-of (X's prereqs = the verbs U with
   U :prereq-of X)."
  [pverbs]
  (let [inverse (reduce (fn [m v]
                          (let [src (:mm.verb/name v)]
                            (reduce (fn [m2 tgt] (update m2 (ident->wire tgt) (fnil conj #{}) src))
                                    m (:mm.verb/prereq-of v))))
                        {} pverbs)]
    (into (sorted-map)
          (for [v pverbs]
            [(:mm.verb/name v)
             {:read-only?      (boolean (:mm.verb/read-only? v))
              :transition-kind (:mm.verb/transition-kind v)
              :prereqs         (vec (sort (get inverse (:mm.verb/name v) #{})))
              :combines-with   (vec (sort (map ident->wire (:mm.verb/combines-with v))))}]))))

(defn -main
  "Connect, project the :mm/Verb catalog, pprint the edges-map EDN to stdout."
  [& _]
  (reset! db/**conn* (db/conn (db/db-uri)))
  (let [verbs (map projection/full-projection (dt/all-instances-of :mm/Verb))]
    (pp/pprint (build-edges-map verbs))
    (flush)
    (shutdown-agents)
    (System/exit 0)))
