(ns sandbar.import
  "Replacement semantics for a re-imported source document (REP-03 of Astra's
   0.2.0 representation review, folded into D7 on 2026-09-20): the PLAN that
   makes a managed file's re-import assert the file's current content — the
   source-owned representation replaced, obsolete sections, links and carrier
   contents retracted in the same per-file transaction — while the host's
   identity, the refs into it, and the facts the substrate or another owner
   maintains are kept.  Additive import (the pre-D7 behaviour) stays an
   explicit mode; ambiguity (a conflicting identity, a class change, a
   section another record references) is refused, never guessed.

   Under Dan's decision 1 (2026-09-18) the filesystem is the canonical
   record, so re-importing a file the substrate already holds is the file
   asserting itself.  The planner is pure over a database VALUE; the apply
   runs the batch firewall floor over the asserted half and transacts the
   retractions with it."
  (:require [datomic.api            :as d]
            [sandbar.codec.markdown :as md]
            [sandbar.db.datatype    :as dt]))

(def substrate-owned-slots
  "Slots the substrate or another owner maintains, never retracted from an
   entity because a re-imported file omits them (Astra's ownership rule for
   REP-03): the provenance and lifecycle stamps, the firewall's two labels,
   the identity slots, and the attributes the codec re-derives.  A file that
   carries one of them still asserts it."
  #{:mm.memory/created :mm.memory/last-touched :mm.memory/created-by
    :mm.memory/owning-project :mm.memory/visibility
    :mm/id :mm.memory/identity :mm.memory/rel-path :mm.memory/first-section
    :mm.memory/frontmatter :db/ident :db/id :dt/type})

(def ^:private section-link-slots
  #{:mm.section/next-sibling :mm.section/previous-sibling})

(defn- prop [db slot] (d/entity db slot))
(defn- many? [db slot] (= :db.cardinality/many (:db/cardinality (prop db slot))))
(defn- ref? [db slot]  (= :db.type/ref (:db/valueType (prop db slot))))

(defn- ->eid
  "The eid an existing entity a parsed ref value names, or nil when it names
   nothing yet (a new tag, a forward ref): a number, an ident keyword, a map
   with `:db/id` or `:db/ident`, or an upsert map on a unique attribute."
  [db v]
  (cond
    (number? v)  (long v)
    (keyword? v) (d/entid db v)
    (map? v)     (cond
                   (:db/id v)    (->eid db (:db/id v))
                   (:db/ident v) (d/entid db (:db/ident v))
                   :else (let [[a val] (first (dissoc v :dt/type))]
                           (when (and a (not (coll? val)))
                             (try (d/entid db [a val]) (catch Exception _ nil)))))
    :else nil))

(defn- stored-value
  "A stored slot value canonicalized for a retraction datom: a ref as its
   eid (Datomic hands back an ident keyword for an ident-bearing target, an
   entity map otherwise), a scalar as itself."
  [db slot v]
  (if (ref? db slot)
    (cond (keyword? v) (d/entid db v)
          (number? v)  v
          ;; a Datomic Entity (what `d/entity` hands back for a ref) or a
          ;; map: its eid — an Entity object is not an identifier tx data
          ;; accepts (":db.error/not-an-entity #:db{:id …}")
          :else        (or (:db/id v) v))
    v))

(defn section-tree-eids
  "Every `:mm/Section` eid whose parent chain reaches `mem-eid`, through the
   reverse parent index, cycle-safe."
  [db mem-eid]
  (loop [frontier [mem-eid] seen #{}]
    (if (empty? frontier)
      seen
      (let [kids (mapcat (fn [e] (map :db/id (:mm.section/_parent (d/entity db e)))) frontier)
            new  (remove seen kids)]
        (recur new (into seen new))))))

(defn- inbound-sources
  "The entities OUTSIDE `doc-eids` that reference `eid` through any ref
   attribute, as `[source-eid attribute]` pairs."
  [db eid doc-eids]
  (->> (d/q '[:find ?src ?a :in $ ?e :where [?src ?a ?e]] db eid)
       (remove (fn [[src _]] (contains? doc-eids src)))
       vec))

(defn- slot-ops
  "The retractions that make `stored` (the existing entity) carry only what
   the parsed root `m` declares among the class's source-owned slots: an
   omitted cardinality-one slot loses its value, an omitted or narrowed
   cardinality-many slot loses the members the file no longer names; a
   present cardinality-one slot needs no retraction (the assert replaces it).
   Substrate-owned slots and the class's body slot are never touched here."
  [db class-ident stored m]
  (let [eid       (:db/id stored)
        body-slot (#'md/body-slot-for class-ident)
        owned     (->> (dt/slots-of class-ident)
                       (remove substrate-owned-slots)
                       (remove #(= % body-slot)))]
    (vec
      (mapcat
        (fn [slot]
          (let [prior (get stored slot)
                new-v (get m slot ::absent)]
            (cond
              (nil? prior) nil

              (= new-v ::absent)
              (if (many? db slot)
                (for [p prior] [:db/retract eid slot (stored-value db slot p)])
                [[:db/retract eid slot (stored-value db slot prior)]])

              (many? db slot)
              (let [new-set (if (ref? db slot)
                              (set (keep #(->eid db %) (if (sequential? new-v) new-v [new-v])))
                              (set (if (sequential? new-v) new-v [new-v])))]
                (for [p prior
                      :let [pv (stored-value db slot p)]
                      :when (not (contains? new-set pv))]
                  [:db/retract eid slot pv]))

              :else nil)))
        owned))))

(defn- section-ops
  "The retractions that make the stored section tree of `mem-eid` match the
   parse's: sections the file no longer carries (by ident; identless debris
   too) are retracted whole, unless a record OUTSIDE the document references
   them — that is a conflict, refused rather than remapped; a surviving
   section loses a sibling link the parse no longer records."
  [db mem-eid specs]
  (let [old-eids   (section-tree-eids db mem-eid)
        old        (map #(d/entity db %) old-eids)
        by-ident   (into {} (keep (fn [e] (when-let [i (:db/ident e)] [i e]))) old)
        new-secs   (filter #(dt/type-isa? :mm/Section (:dt/type %)) specs)
        new-idents (set (keep :db/ident new-secs))
        doc-eids   (conj old-eids mem-eid)
        gone       (remove #(contains? new-idents (:db/ident %)) old)
        conflicts  (for [g gone
                         :let [srcs (inbound-sources db (:db/id g) doc-eids)]
                         :when (seq srcs)]
                     {:reason  :externally-referenced-section
                      :section (or (:db/ident g) (:db/id g))
                      :sources (mapv (fn [[src a]] {:source (or (:db/ident (d/entity db src)) src) :attribute a}) srcs)})
        retracts   (for [g gone] [:db.fn/retractEntity (:db/id g)])
        link-ops   (for [s new-secs
                         :let [old-e (get by-ident (:db/ident s))]
                         :when old-e
                         slot section-link-slots
                         :let [old-v (get old-e slot)]
                         :when (and old-v (not (contains? s slot)))]
                     [:db/retract (:db/id old-e) slot (stored-value db slot old-v)])]
    {:ops (vec (concat retracts link-ops))
     :conflicts (vec conflicts)
     :retracted-sections (count gone)}))

(defn plan-unit
  "The plan for one source unit's `specs` (a parse: the root first, then its
   sections and carrier) against the database value `db`: `:mode` `:insert`
   when the root is new, `:additive` when asked (no retractions, the pre-D7
   behaviour), else `:replace` with `:ops` (the retractions that make the
   store carry only what the file declares among the source-owned slots, the
   section tree and the carrier), `:conflicts` (a class change, an identity
   conflict, an externally referenced section — each refuses the unit), and
   the counts the report shows.  Pure: transacts nothing."
  [db specs {:keys [mode] :or {mode :replace}}]
  (let [m     (first specs)
        ident (:db/ident m)
        eid   (when ident (d/entid db ident))
        stored (when eid (d/entity db eid))]
    (cond
      ;; new to the store — or an ident whose entity was retracted: Datomic
      ;; keeps resolving the ident to its old eid, and the assert repopulates
      ;; that eid, so there is nothing to reconcile against
      (or (nil? eid) (nil? (:dt/type stored)))
      {:mode :insert :specs specs :ops [] :conflicts []}

      (= mode :additive)
      {:mode :additive :specs specs :ops [] :conflicts []}

      :else
      (let [class-ident    (:dt/type m)
            stored-class   (dt/class-ident-of stored)
            class-conflict (when (not= class-ident stored-class)
                             {:reason :class-changed :from stored-class :to class-ident})
            file-id        (:mm/id m)
            store-id       (:mm/id stored)
            id-conflict    (when (and file-id store-id (not= file-id store-id))
                             {:reason :identity-conflict :file-id (str file-id) :store-id (str store-id)})
            sections       (section-ops db eid specs)
            slots          (slot-ops db class-ident stored m)
            old-carrier    (get stored :mm.memory/frontmatter)
            carrier-op     (when (and old-carrier (not (contains? m :mm.memory/frontmatter)))
                             [:db.fn/retractEntity (stored-value db :mm.memory/frontmatter old-carrier)])
            old-first      (get stored :mm.memory/first-section)
            first-op       (when (and old-first (not (contains? m :mm.memory/first-section)))
                             [:db/retract eid :mm.memory/first-section (stored-value db :mm.memory/first-section old-first)])
            conflicts      (vec (concat (keep identity [class-conflict id-conflict]) (:conflicts sections)))]
        {:mode               :replace
         :specs              specs
         :ops                (vec (concat (:ops sections) slots (keep identity [carrier-op first-op])))
         :conflicts          conflicts
         :retracted-sections (:retracted-sections sections)
         :retracted-slots    (count slots)
         :retracted-carrier? (boolean carrier-op)}))))

(defn apply-plan!
  "Transact a conflict-free plan: the asserted half through the batch
   firewall floor, the retractions in the same transaction.  Throws when the
   plan carries conflicts — the caller reports those and never applies."
  [plan]
  (when (seq (:conflicts plan))
    (throw (ex-info "import plan carries conflicts; refusing to apply"
                    {:reasons #{:import/conflicts} :conflicts (:conflicts plan)})))
  (dt/make-all-with-retractions* (md/entity-specs->tx-data (:specs plan)) (:ops plan)))
