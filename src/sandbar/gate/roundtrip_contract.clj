(ns sandbar.gate.roundtrip-contract
  "The 8-query semantic round-trip contract — the durable G1 fidelity asset.

   Realizes the 2026-05-12 export ADR §D.5 contract (`mem
   sandbar-roundtrip-test`: export + fresh-import + semantic-equivalence
   across 8 representative Datalog queries) — re-homed from the retired
   `mem`-verb framing onto the live substrate per the W1 arc plan FORK-4
   ruling (implement the finalized spec, don't re-derive).

   `capture` snapshots the CURRENT db (`sandbar.db.datomic/db`) into a
   plain-data fingerprint across 8 semantic dimensions; `compare-contracts`
   diffs two snapshots MODULO a caller-supplied `:allowed-drift` set of
   rel-paths.  Semantic (not byte-strict) per the W1 arc plan G3 — the
   queries compare counts / slot values / edge + tag SETS, all invariant
   under the codec's whitespace/key-order normalization, so a
   post-settle export→import is exactly equal (the fixture has ZERO drift;
   the live-corpus run subtracts the documented 226-item deferred drift).

   The 8 dimensions (each a distinct round-trip regression class):
     Q1 population         — # file-backed :mm/Memory (rel-path present)
     Q2 type-distribution  — {memory-type -> count}
     Q3 scope-distribution — {scope -> count}
     Q4 rel-path set       — the corpus file-set (Q4 = keyset of :by-rel-path)
     Q5 name fidelity      — per-file :mm.memory/name
     Q6 body fidelity      — per-file SHA-256 of trimmed body-raw
     Q7 citation edges     — per-file resolved cites target-key set
     Q8 tag + structure    — tag-value vocabulary + :mm/Section count"
  (:require [clojure.set    :as set]
            [clojure.string :as str]
            [datomic.api    :as d]
            [sandbar.db.datomic :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sha256-hex
  "Hex SHA-256 of `s` (nil ⇒ the empty-string digest).  Used for the Q6
   per-document content fingerprint — a whitespace-exact but compact
   equality key that localizes a content divergence to one rel-path."
  [s]
  (let [md    (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn- target-key
  "Stable comparison key for a cites/related ref target: its rel-path when
   file-backed, else its `:db/ident` (both survive a DB reload; a numeric
   :db/id would not)."
  [target-entity]
  (or (:mm.memory/rel-path target-entity)
      (:db/ident target-entity)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; capture — the 8-query snapshot over the current db
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn capture
  "Snapshot the current db into the 8-query contract fingerprint (plain
   data).  The corpus population is anchored on the file-backed predicate
   `[?e :mm.memory/rel-path ?rp]` — precisely the set a DB→FS→DB round-trip
   is required to reconstruct."
  []
  (let [db     (db/db)
        ;; Q4/Q5/Q6/Q7 — per-file rows keyed by rel-path (the natural
        ;; round-trip join key).
        rows   (d/q '[:find ?e ?rp
                      :where [?e :mm.memory/rel-path ?rp]]
                    db)
        by-rel-path
        (into {}
              (map (fn [[eid rp]]
                     (let [e     (d/entity db eid)
                           cites (->> (:mm.memory/cites e)
                                      (keep target-key)
                                      set)]
                       [rp {:name        (:mm.memory/name e)
                            :memory-type (:mm.memory/memory-type e)
                            :scope       (:mm.memory/scope e)
                            :body-sha    (sha256-hex
                                          (some-> (:mm.memory/body-raw e)
                                                  str/trim))
                            :cites       cites}])))
              rows)
        ;; Q2/Q3 — corpus-level distributions (over the file-backed set).
        dist   (fn [slot]
                 (->> (vals by-rel-path)
                      (map slot)
                      (frequencies)))
        ;; Q8 — controlled-vocabulary + substructure fidelity.
        tag-vocabulary (into #{}
                             (d/q '[:find [?v ...]
                                    :where [?t :mm.tag/value ?v]]
                                  db))
        section-count  (or (d/q '[:find (count ?s) .
                                  :where [?s :mm.section/heading _]]
                                db)
                           0)]
    {:population         (count by-rel-path)
     :type-distribution  (dist :memory-type)
     :scope-distribution (dist :scope)
     :tag-vocabulary     tag-vocabulary
     :section-count      section-count
     :by-rel-path        by-rel-path}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; compare — semantic equivalence modulo allowed drift
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- per-file-divergences
  "Rel-paths whose per-file fingerprint differs between `a` and `b`, minus
   `allowed-drift`.  Reports each divergence with its differing keys."
  [a-map b-map allowed-drift]
  (let [ks (set/difference (set/union (set (keys a-map)) (set (keys b-map)))
                           allowed-drift)]
    (reduce
     (fn [acc rp]
       (let [av (get a-map rp)
             bv (get b-map rp)]
         (if (= av bv)
           acc
           (conj acc {:rel-path rp
                      :only-in  (cond (nil? av) :reconstructed
                                      (nil? bv) :source
                                      :else     :both)
                      :differing-keys (when (and av bv)
                                        (->> (keys av)
                                             (remove #(= (get av %) (get bv %)))
                                             vec))
                      :source  av
                      :reconstructed bv}))))
     []
     (sort ks))))

(defn compare-contracts
  "Diff a `source` snapshot vs a `reconstructed` snapshot; return
     {:equivalent? bool
      :checks      [{:query kw :equivalent? bool :detail ...} x8]
      :divergences [...]}    ; empty iff equivalent

   `opts` key `:allowed-drift` — a set of rel-paths excused from the
   per-file (Q4/Q5/Q6/Q7) comparison (empty for the fixture; the live
   run passes the documented 226-item deferred-drift rel-path set)."
  ([source reconstructed] (compare-contracts source reconstructed {}))
  ([source reconstructed {:keys [allowed-drift] :or {allowed-drift #{}}}]
   (let [file-divs (per-file-divergences (:by-rel-path source)
                                         (:by-rel-path reconstructed)
                                         allowed-drift)
         ;; per-file divergences fan out into the Q4..Q7 checks
         div-keys  (fn [k] (some #(some #{k} (:differing-keys %)) file-divs))
         checks
         [{:query :Q1-population
           :equivalent? (= (:population source) (:population reconstructed))
           :detail {:source (:population source) :reconstructed (:population reconstructed)}}
          {:query :Q2-type-distribution
           :equivalent? (= (:type-distribution source) (:type-distribution reconstructed))
           :detail {:source (:type-distribution source) :reconstructed (:type-distribution reconstructed)}}
          {:query :Q3-scope-distribution
           :equivalent? (= (:scope-distribution source) (:scope-distribution reconstructed))
           :detail {:source (:scope-distribution source) :reconstructed (:scope-distribution reconstructed)}}
          {:query :Q4-rel-path-set
           :equivalent? (not (some #(#{:source :reconstructed :both} (:only-in %)) file-divs))
           :detail {:source-only (mapv :rel-path (filter #(= :source (:only-in %)) file-divs))
                    :reconstructed-only (mapv :rel-path (filter #(= :reconstructed (:only-in %)) file-divs))}}
          {:query :Q5-name-fidelity
           :equivalent? (not (div-keys :name))
           :detail {:diverging (filterv #(some #{:name} (:differing-keys %)) file-divs)}}
          {:query :Q6-body-fidelity
           :equivalent? (not (div-keys :body-sha))
           :detail {:diverging (mapv :rel-path (filter #(some #{:body-sha} (:differing-keys %)) file-divs))}}
          {:query :Q7-citation-edges
           :equivalent? (not (div-keys :cites))
           :detail {:diverging (filterv #(some #{:cites} (:differing-keys %)) file-divs)}}
          {:query :Q8-tag-and-structure
           :equivalent? (and (= (:tag-vocabulary source) (:tag-vocabulary reconstructed))
                             (= (:section-count source) (:section-count reconstructed)))
           :detail {:tag-source (:tag-vocabulary source) :tag-reconstructed (:tag-vocabulary reconstructed)
                    :sections-source (:section-count source) :sections-reconstructed (:section-count reconstructed)}}]]
     {:equivalent? (every? :equivalent? checks)
      :checks      checks
      :divergences file-divs})))
