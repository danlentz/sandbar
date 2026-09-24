(ns sandbar.project.dump
  "EDN diagnostic dump for classes with effective :db-only memorial policy.
   Enumerate direct instances and serialize entity specifications with a
   metadata header. Identful references use idents; identless references can
   retain database-local numeric IDs. Undeclared-policy classes are reported.

   This is not a portable replacement for native database backup: bytes and
   reference collections need an independently verified restore contract.
   Documents plus this dump do not establish complete service recovery.
   See doc/operations.md for backup and restoration procedures."
  (:require [clojure.edn          :as edn]
            [clojure.java.io      :as io]
            [clojure.pprint       :as pprint]
            [clojure.tools.logging :as log]
            [sandbar.db.datatype  :as dt]
            [sandbar.db.datomic   :as db]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Class classification — partition all classes by their effective policy
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn classify-classes
  "Walk all classes via `dt/all-datatypes`; classify each by its effective
   `:dt/memorial-policy` (resolved through ancestry walk via
   `dt/effective-memorial-policy-of`).

   Returns `{:first-class [class-idents] :db-only [...] :inline [...] nil [...]}`
   — the nil bucket holds policy-undeclared classes (a loud-warning signal
   per the first-class-memorialization arc Stage G future enforcement)."
  []
  (let [class-idents (dt/all-datatypes)]
    (reduce (fn [acc cls]
              (let [policy (try (dt/effective-memorial-policy-of cls)
                                (catch Throwable _ nil))]
                (update acc policy (fnil conj []) cls)))
            {}
            class-idents)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entity-spec emission — Datomic Entity → plain map with ref normalization
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- datomic-entity?
  "Is v a Datomic Entity (the lazy map-like object returned by `db/entity`)?
   Tested via the EntityMap protocol marker — works without a hard
   class-import to datomic.query.EntityMap."
  [v]
  (instance? datomic.Entity v))

(defn- ref->canonical
  "Normalize a single Datomic-Entity ref to its stable form for dump
   serialization.  Prefer `:db/ident` (stable across DB reloads); fall
   back to numeric `:db/id` for ident-less entities (restore-side will
   tempid-translate)."
  [ref-entity]
  (or (:db/ident ref-entity)
      (:db/id ref-entity)))

(defn- normalize-value
  "Normalize a slot value for EDN serialization.

   - Datomic Entity → ident-or-eid
   - Set of Entities → set of ident-or-eids
   - Sequence of Entities → vec of ident-or-eids
   - Scalar (keyword / string / number / boolean / inst / bigdec / uuid /
     byte-array / etc.) → passed through verbatim

   For mixed sets / sequences (any Datomic-Entity members), maps every
   member through `ref->canonical`.  For non-Entity collections (rare in
   Datomic slot returns), passes through."
  [v]
  (cond
    (datomic-entity? v)  (ref->canonical v)

    (set? v)
    (if (some datomic-entity? v)
      (set (map (fn [x] (if (datomic-entity? x) (ref->canonical x) x)) v))
      v)

    (sequential? v)
    (if (some datomic-entity? v)
      (mapv (fn [x] (if (datomic-entity? x) (ref->canonical x) x)) v)
      v)

    :else v))

(defn entity->spec
  "Convert a single Datomic Entity into a flat entity-spec map suitable for
   EDN serialization + downstream `entity-specs->tx-data` round-trip.

   Each slot value is normalized via `normalize-value`:
   - Ref slots → `:db/ident` or `:db/id`
   - Ref-set / ref-seq slots → set / vec of `:db/ident` / `:db/id`
   - Scalars → verbatim

   `:dt/type` + `:db/id` are emitted explicitly (Entity iteration does not
   yield `:db/id`)."
  [entity]
  (let [base {:dt/type (:dt/type entity)
              :db/id   (:db/id entity)}]
    (reduce (fn [acc [k v]]
              (assoc acc k (normalize-value v)))
            base
            entity)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Dump assembly + atomic write
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- atomic-write!
  "Write `content` to `target-path` atomically — write to .tmp + rename.
   Mirrors `sandbar.reactive.sinks/atomic-write!` shape but lifted into
   this ns to avoid a sinks→dump dependency."
  [^String target-path ^String content]
  (let [target ^java.io.File (io/file target-path)
        parent (.getParentFile target)
        tmp    ^java.io.File (io/file (str target-path ".tmp"))]
    (when parent (.mkdirs parent))
    (spit tmp content)
    (.renameTo tmp target)
    nil))

(defn- assemble-dump
  "Build the dump map (metadata + entities) for the given class-bucket map.
   Pure — no IO."
  [{:keys [db-only-classes policy-undeclared-classes sandbar-version]}]
  (let [entities (vec (mapcat (fn [cls]
                                (->> (dt/direct-instances-of cls)
                                     (map entity->spec)))
                              db-only-classes))]
    {:dump/metadata
     {:dump/format          :edn-datomic-canonical
      :dump/sandbar-version (or sandbar-version "0.2.0")
      :dump/dumped-at       (java.util.Date.)
      :dump/memorial-policy :db-only
      :dump/classes-included (vec db-only-classes)
      :dump/entity-count     (count entities)
      :dump/policy-undeclared-classes (vec policy-undeclared-classes)}

     :dump/entities entities}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public entry point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn dump-db-only
  "Write the selected :db-only class instances to :to as an EDN dump.
   :sandbar-version sets the header version; :pretty? selects pretty printing.
   Return :to, :classes-dumped, :entities-dumped and :policy-undeclared counts.
   Start/done log signals describe this operation; validate recovery
   separately before relying on the dump as a backup."
  [{:keys [to sandbar-version pretty?]
    :or   {sandbar-version "0.2.0"
           pretty?         false}}]
  (when-not to
    (throw (ex-info "sandbar.project.dump/dump-db-only requires :to (target path)"
                    {:opts-received {:to to :sandbar-version sandbar-version}})))
  (log/info :DUMP/start {:to to :sandbar-version sandbar-version})
  (let [partition       (classify-classes)
        db-only-classes (get partition :db-only [])
        undeclared      (get partition nil      [])
        dump-map        (assemble-dump
                          {:db-only-classes           db-only-classes
                           :policy-undeclared-classes undeclared
                           :sandbar-version           sandbar-version})
        edn-content     (if pretty?
                          (with-out-str (pprint/pprint dump-map))
                          (pr-str dump-map))]
    (atomic-write! to edn-content)
    (let [summary {:to                to
                   :classes-dumped    (count db-only-classes)
                   :entities-dumped   (get-in dump-map [:dump/metadata :dump/entity-count])
                   :policy-undeclared (count undeclared)}]
      (log/info :DUMP/done summary)
      summary)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  ;; Quick smoke test from nREPL:
  (dump-db-only {:to "/tmp/sandbar-dump.edn"})

  ;; Verify partition:
  (-> (classify-classes)
      (update-vals count))
  )
